package io.github.cctyl.keydroidx.music.cache

import android.content.Context
import android.util.Log
import io.github.cctyl.keydroidx.music.network.model.ToplistBoard
import io.github.cctyl.keydroidx.music.network.model.ToplistTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 云音乐官方榜单列表本地缓存（落盘 filesDir/toplist_cache.json）。
 *
 * 策略与 [UserProfileCache] / [PlaylistSongCache] 一致：
 *  - 进入榜单 Tab 时先用缓存秒显，断网也能看到上次榜单；
 *  - 有网时后台静默拉取最新数据，成功后覆盖缓存并刷新 UI；
 *  - 拉取失败（如断网）保留缓存不动，不误报「加载失败」。
 */
object ToplistCache {
    private const val TAG = "ToplistCache"
    private const val FILE_NAME = "toplist_cache.json"

    private fun fileFor(context: Context) = File(context.filesDir, FILE_NAME)

    fun save(context: Context, boards: List<ToplistBoard>) {
        try {
            val arr = JSONArray()
            boards.forEach { b ->
                val tracksArr = JSONArray()
                b.tracks?.forEach { t ->
                    tracksArr.put(JSONObject().apply {
                        put("first", t.first ?: "")
                        put("second", t.second ?: "")
                    })
                }
                arr.put(JSONObject().apply {
                    put("id", b.id)
                    put("name", b.name)
                    put("updateFrequency", b.updateFrequency ?: "")
                    put("trackCount", b.trackCount ?: 0)
                    put("tracks", tracksArr)
                })
            }
            fileFor(context).writeText(arr.toString())
            Log.d(TAG, "toplist cached: ${boards.size} boards")
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    fun load(context: Context): List<ToplistBoard> {
        return try {
            val f = fileFor(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val tracksArr = o.optJSONArray("tracks")
                val tracks = tracksArr?.let {
                    (0 until it.length()).map { j ->
                        val t = it.getJSONObject(j)
                        ToplistTrack(
                            first = t.optString("first").ifBlank { null },
                            second = t.optString("second").ifBlank { null }
                        )
                    }
                }
                ToplistBoard(
                    id = o.optLong("id"),
                    name = o.optString("name"),
                    updateFrequency = o.optString("updateFrequency").ifBlank { null },
                    trackCount = o.optInt("trackCount").takeIf { it > 0 },
                    tracks = tracks
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
            emptyList()
        }
    }

    /** 清空榜单缓存（退出登录等场景调用）。 */
    fun clear(context: Context) {
        fileFor(context).delete()
        Log.d(TAG, "toplist cache cleared")
    }
}
