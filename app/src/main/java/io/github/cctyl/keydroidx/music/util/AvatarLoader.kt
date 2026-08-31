package io.github.cctyl.keydroidx.music.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.cctyl.keydroidx.music.util.NLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 评论区头像异步加载器。
 *
 * 项目无 Glide / Coil 依赖，沿用 `MainActivity.loadAvatarAsync` 的思路
 * （HttpURLConnection + BitmapFactory），额外加一层内存 [LruCache]，
 * 避免上下滚动时同一头像被反复解码。
 *
 * 网络与解码全部在 [Dispatchers.IO] 完成，加载失败返回 null，由调用方回落人形图标。
 */
object AvatarLoader {
    private const val TAG = "AvatarLoader"
    private const val MAX_ENTRIES = 40

    private val memory = object : LruCache<String, Bitmap>(MAX_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** 加载头像；命中内存缓存直接返回，否则走网络。失败返回 null。 */
    suspend fun load(url: String): Bitmap? {
        if (url.isBlank()) return null
        memory.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bmp = download(url)
            if (bmp != null) memory.put(url, bmp)
            bmp
        }
    }

    private fun download(urlStr: String): Bitmap? = try {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        Log.w(TAG, "avatar download failed: ${e.message}")
        null
    }
}
