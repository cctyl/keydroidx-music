package io.github.cctyl.keydroidx.music.player

import android.content.Context

/**
 * 播放偏好（音质等），本地持久化。
 * 音质档位与网易云 eapi level 字段一致：
 *   standard(标准) / higher(较高) / exhigh(极高) / lossless(无损) / hires(Hi-Res)
 */
object PlaybackPrefs {

    private const val FILE = "playback_prefs"
    private const val KEY_QUALITY = "quality_level"

    /** 档位顺序即回退顺序，由低到高 */
    val QUALITY_LEVELS = listOf("standard", "higher", "exhigh", "lossless", "hires")

    fun qualityLevel(context: Context): String {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getString(KEY_QUALITY, null)?.takeIf { it in QUALITY_LEVELS } ?: DEFAULT_LEVEL
    }

    fun setQualityLevel(context: Context, level: String) {
        if (level !in QUALITY_LEVELS) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_QUALITY, level).apply()
    }

    /** 默认标准音质——按键机流量/性能优先 */
    const val DEFAULT_LEVEL = "standard"
}
