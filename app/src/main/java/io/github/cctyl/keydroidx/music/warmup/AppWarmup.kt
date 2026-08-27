package io.github.cctyl.keydroidx.music.warmup

import android.content.Context
import io.github.cctyl.keydroidx.music.util.NLog as Log
import io.github.cctyl.keydroidx.music.cache.ContentCache
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
                    dailyDeferred.await()
                    playlistDeferred.await()
                }
                Log.d(TAG, "Background warmup completed successfully.")
            } catch (e: Exception) {
                Log.w(TAG, "Warmup encountered an issue: ${e.message}")
            }
        }
    }
}
