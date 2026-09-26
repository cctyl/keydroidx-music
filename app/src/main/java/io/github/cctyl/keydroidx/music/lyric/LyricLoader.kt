package io.github.cctyl.keydroidx.music.lyric

import android.content.Context
import io.github.cctyl.keydroidx.music.download.DownloadManager
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.util.NLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 歌词加载器（本地优先 → 联网兜底）。
 *
 * 与 [io.github.cctyl.keydroidx.music.player.PlaybackService] 后台歌词跟踪、
 * [io.github.cctyl.keydroidx.music.ui.MusicPlayerActivity] 播放页歌词区采用
 * 完全一致的取数顺序，保证「后台首句」与「界面整篇」不会出现内容不一致。
 *
 * 返回空列表表示：无歌词 / 纯音乐 / 网络异常（失败静默降级，不抛异常打断界面）。
 */
object LyricLoader {

    private const val TAG = "LyricLoader"

    suspend fun load(songId: Long): List<LrcLine> = withContext(Dispatchers.IO) {
        try {
            // 1. 优先读取已下载的本地歌词文件（离线可用）
            val downloaded = DownloadManager.getDownloadedSong(songId)
            val lyricPath = downloaded?.lyricPath
            if (!lyricPath.isNullOrBlank()) {
                val lrcFile = java.io.File(lyricPath)
                if (lrcFile.exists()) {
                    val raw = lrcFile.readText(Charsets.UTF_8)
                    if (raw.isNotBlank()) {
                        return@withContext LrcParser.parse(raw)
                    }
                }
            }

            // 2. 本地无歌词时联网拉取
            val resp = RetrofitClient.api.getLyric(id = songId)
            val raw = resp.lrc?.lyric
            if (raw.isNullOrBlank()) {
                Log.d(TAG, "no lyric for song $songId")
                emptyList()
            } else {
                LrcParser.parse(raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "load lyric failed songId=$songId: ${e.message}")
            emptyList()
        }
    }
}
