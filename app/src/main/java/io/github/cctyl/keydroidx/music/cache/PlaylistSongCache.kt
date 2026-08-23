package io.github.cctyl.keydroidx.music.cache

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 歌单歌曲列表缓存（按歌单 ID 落盘）。
 *
 * 策略与用户信息缓存一致：
 *  - 打开过的歌单曲目列表存 filesDir/playlist_songs_<id>.json
 *  - 下次打开先用缓存秒显，后台再拉最新数据覆盖
 *  - 断网时也能浏览之前打开过的歌单
 */
object PlaylistSongCache {
    private const val TAG = "PlaylistSongCache"

    private fun fileFor(context: Context, playlistId: Long) =
        File(context.filesDir, "playlist_songs_$playlistId.json")

    /** 缓存的曲目条目：id / 标题 / 歌手（播放需要真实 id，故必须存）。 */
    data class Entry(val id: Long, val title: String, val artist: String)

    fun save(context: Context, playlistId: Long, entries: List<Entry>) {
        try {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().apply {
                    put("id", e.id)
                    put("title", e.title)
                    put("artist", e.artist)
                })
            }
            fileFor(context, playlistId).writeText(arr.toString())
            Log.d(TAG, "playlist $playlistId cached: ${entries.size} songs")
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    fun load(context: Context, playlistId: Long): List<Entry> {
        return try {
            val f = fileFor(context, playlistId)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(o.optLong("id"), o.optString("title"), o.optString("artist"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
            emptyList()
        }
    }

    /** 清空全部歌单缓存（退出登录时调用）。 */
    fun clearAll(context: Context) {
        context.filesDir.listFiles { f -> f.name.startsWith("playlist_songs_") }?.forEach { it.delete() }
        Log.d(TAG, "all playlist caches cleared")
    }
}
