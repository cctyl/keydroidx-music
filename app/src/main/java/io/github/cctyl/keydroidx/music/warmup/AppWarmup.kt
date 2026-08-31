package io.github.cctyl.keydroidx.music.warmup

import android.content.Context
import io.github.cctyl.keydroidx.music.util.NLog as Log
import io.github.cctyl.keydroidx.music.cache.ContentCache
import io.github.cctyl.keydroidx.music.library.FavoriteStore
import io.github.cctyl.keydroidx.music.network.PlaylistApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

object AppWarmup {
    private const val TAG = "AppWarmup"
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startWarmup(context: Context) {
        warmupScope.launch {
            Log.d(TAG, "Starting background warmup...")
            try {
                // 预热首页发现数据
                coroutineScope {
                    val dailyDeferred = async {
                        runCatching {
                            if (ContentCache.homeDailySongs == null) {
                                val songs = PlaylistApi.getDailyRecommendSongs()
                                ContentCache.homeDailySongs = songs
                            }
                        }
                    }
                    val playlistDeferred = async {
                        runCatching {
                            if (ContentCache.homeRecommendPlaylists == null) {
                                val pls = PlaylistApi.getRecommendPlaylists()
                                ContentCache.homeRecommendPlaylists = pls
                            }
                        }
                    }
                    // 预拉云端收藏（仅 id，10 分钟节流）：让「最近播放」等页面一进来红心就正确，
                    // 而不必等用户先进一次「我喜欢的音乐」才回填索引。失败不影响其它预热。
                    val favoriteDeferred = async {
                        runCatching { FavoriteStore.syncFromCloud(context) }
                            .onFailure { Log.w(TAG, "sync favorites failed: ${it.message}") }
                    }
                    dailyDeferred.await()
                    playlistDeferred.await()
                    favoriteDeferred.await()
                }
                Log.d(TAG, "Background warmup completed successfully.")
            } catch (e: Exception) {
                Log.w(TAG, "Warmup encountered an issue: ${e.message}")
            }
        }
    }
}
