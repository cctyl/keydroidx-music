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
 * 锁屏歌词页的歌曲封面加载器。
 *
 * 为什么不直接复用 [AvatarLoader]：头像只有几十像素，按「条数」计量的缓存足够；
 * 封面是整屏大图，若沿用同一套缓存，40 张全尺寸位图会长期驻留内存，低内存机型有 OOM 风险。
 * 因此这里做了两件事：
 *   1. 解码时按 [BitmapFactory.Options.inSampleSize] 降采样——锁屏最大也就铺满 480px，
 *      无需保留原图（网易云封面常见 1000px+）；
 *   2. 缓存按「字节数」计量，上限 [MAX_BYTES]，超限自动淘汰最久未使用的条目。
 *
 * 网络与解码全部在 [Dispatchers.IO] 完成，加载失败返回 null，由调用方回落主题深色底。
 */
object CoverLoader {
    private const val TAG = "CoverLoader"

    /** 缓存字节上限：保证 2~4 张锁屏封面可复用，同时不挤占播放内核内存 */
    private const val MAX_BYTES = 4 * 1024 * 1024

    /** 降采样目标边长：大于主流按键机屏幕宽度即可，再大纯属浪费 */
    private const val TARGET_SIZE = 480

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 加载封面；命中字节缓存直接返回，否则走网络。失败返回 null。 */
    suspend fun load(url: String): Bitmap? {
        if (url.isBlank()) return null
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            // 先整块读进内存，避免 inJustDecodeBounds 与真实解码各发起一次网络请求
            val bytes = download(url) ?: return@withContext null
            val bmp = decode(bytes) ?: return@withContext null
            cache.put(url, bmp)
            bmp
        }
    }

    private fun download(urlStr: String): ByteArray? = try {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.inputStream.use { it.readBytes() }
    } catch (e: Exception) {
        Log.w(TAG, "cover download failed: ${e.message}")
        null
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** 取最大的 2 次幂采样率，使降采样后的最长边仍在 [TARGET_SIZE, 2 * TARGET_SIZE) 区间。 */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= TARGET_SIZE) {
            sample *= 2
        }
        return sample
    }
}
