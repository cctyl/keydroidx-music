package io.github.cctyl.keydroidx.music.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@OptIn(UnstableApi::class)
object AudioCacheManager {

    private const val MAX_CACHE_SIZE = 512L * 1024 * 1024 // 512MB 缓存上限
    private const val CACHE_DIR_NAME = "media_cache"

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null

    /**
     * 获取全局 SimpleCache 单例
     */
    @Synchronized
    fun getCache(context: Context): SimpleCache {
        return simpleCache ?: synchronized(this) {
            simpleCache ?: run {
                val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply {
                    if (!exists()) mkdirs()
                }
                val dbProvider = databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext).also {
                    databaseProvider = it
                }
                val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE)
                SimpleCache(cacheDir, evictor, dbProvider).also {
                    simpleCache = it
                }
            }
        }
    }

    /**
     * 生成统一的缓存 key（对于网络歌曲直接使用 songId 作为 key）
     */
    fun buildCacheKey(songId: Long): String {
        return "ncm_song_$songId"
    }

    /**
     * 检查某首网络歌曲是否在本地有可用缓存数据
     */
    fun isSongCached(context: Context, songId: Long): Boolean {
        if (songId <= 0L) return false
        val cache = getCache(context)
        val key = buildCacheKey(songId)
        val spans = cache.getCachedSpans(key)
        return spans.isNotEmpty() && cache.getCachedBytes(key, 0, -1) > 100 * 1024 // 缓存超过100KB视作有缓存
    }

    /**
     * 创建基于缓存的 CacheDataSource.Factory
     */
    fun createCacheDataSourceFactory(context: Context): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val defaultDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)

        return CacheDataSource.Factory()
            .setCache(getCache(context))
            .setUpstreamDataSourceFactory(defaultDataSourceFactory)
            .setCacheKeyFactory { dataSpec ->
                dataSpec.customData?.toString() ?: dataSpec.key ?: dataSpec.uri.toString()
            }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
