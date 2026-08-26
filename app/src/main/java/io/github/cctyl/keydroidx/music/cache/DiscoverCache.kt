package io.github.cctyl.keydroidx.music.cache

import android.content.Context
import android.util.Log
import io.github.cctyl.keydroidx.music.network.PlaylistApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 发现页「今日推荐歌单」本地缓存（落盘 filesDir/discover_playlists.json）。
 *
 * 策略与 [ToplistCache] / [UserProfileCache] 一致：
 *  - 进入发现页时先用缓存秒显，断网也能看到上次推荐的歌单；
 *  - 有网时后台静默拉取最新数据，成功后覆盖缓存并刷新 UI；
 *  - 拉取失败（如断网）保留缓存不动，不显示空白页。
 */
object DiscoverCache {
    private const val TAG = "DiscoverCache"
    private const val FILE_NAME = "discover_playlists.json"

    private fun fileFor(context: Context) = File(context.filesDir, FILE_NAME)

    fun save(context: Context, cards: List<PlaylistApi.PlaylistCard>) {
        try {
            val arr = JSONArray()
            cards.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("coverUrl", c.coverUrl)
                    put("playCount", c.playCount)
                    put("trackCount", c.trackCount)
                    put("copywriter", c.copywriter)
                })
            }
            fileFor(context).writeText(arr.toString())
            Log.d(TAG, "discover cached: ${cards.size} cards")
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    fun load(context: Context): List<PlaylistApi.PlaylistCard> {
        return try {
            val f = fileFor(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PlaylistApi.PlaylistCard(
                    id = o.optLong("id"),
                    name = o.optString("name"),
                    coverUrl = o.optString("coverUrl"),
                    playCount = o.optLong("playCount"),
                    trackCount = o.optInt("trackCount"),
                    copywriter = o.optString("copywriter")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
            emptyList()
        }
    }

    /** 清空发现页缓存（退出登录等场景调用）。 */
    fun clear(context: Context) {
        fileFor(context).delete()
        Log.d(TAG, "discover cache cleared")
    }
}
