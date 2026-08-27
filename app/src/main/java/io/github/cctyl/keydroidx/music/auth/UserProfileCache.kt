package io.github.cctyl.keydroidx.music.auth

import android.content.Context
import io.github.cctyl.keydroidx.music.util.NLog as Log
import io.github.cctyl.keydroidx.music.network.PlaylistApi
import org.json.JSONObject

/**
 * 用户信息本地缓存。
 *
 * 目的：断网/接口失败时，「我的」tab 依然能展示上次登录的用户信息，
 * 而不是错误地显示「未登录」。有网时后台静默刷新并覆盖缓存。
 *
 * 存储：SharedPreferences 内一个 JSON 字符串。
 */
object UserProfileCache {
    private const val TAG = "UserProfileCache"
    private const val PREF_NAME = "keydroidx_music_auth"
    private const val KEY_PROFILE = "cached_profile"
    private const val KEY_PLAYLISTS = "cached_playlists"
    private const val AVATAR_FILE = "user_avatar.jpg"

    fun save(context: Context, profile: PlaylistApi.UserProfile) {
        try {
            val json = JSONObject().apply {
                put("userId", profile.userId)
                put("nickname", profile.nickname)
                put("avatarUrl", profile.avatarUrl)
                put("signature", profile.signature)
                put("level", profile.level)
                put("followeds", profile.followeds)
                put("follows", profile.follows)
                put("playlistCount", profile.playlistCount)
                put("vipType", profile.vipType)
            }
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PROFILE, json.toString()).apply()
            Log.d(TAG, "profile cached: ${profile.nickname}")
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    fun load(context: Context): PlaylistApi.UserProfile? {
        return try {
            val str = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PROFILE, null) ?: return null
            val json = JSONObject(str)
            PlaylistApi.UserProfile(
                userId = json.optLong("userId", 0),
                nickname = json.optString("nickname", ""),
                avatarUrl = json.optString("avatarUrl", ""),
                signature = json.optString("signature", ""),
                level = json.optInt("level", 0),
                followeds = json.optLong("followeds", 0),
                follows = json.optLong("follows", 0),
                playlistCount = json.optLong("playlistCount", 0),
                vipType = json.optInt("vipType", 0)
            ).takeIf { it.userId != 0L }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
            null
        }
    }

    /** 是否为 VIP 会员用户（vipType > 0 视为黑胶/音乐包等 VIP 用户） */
    fun isVip(context: Context): Boolean {
        val profile = load(context)
        return (profile?.vipType ?: 0) > 0
    }

    /** 退出登录 / cookie 被服务端判定过期时清除（含歌单与头像）。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PROFILE)
            .remove(KEY_PLAYLISTS)
            .apply()
        avatarFile(context).delete()
    }

    // ── 歌单列表缓存 ────────────────────────────────

    fun savePlaylists(context: Context, playlists: List<PlaylistApi.PlaylistInfo>) {
        try {
            val arr = org.json.JSONArray()
            playlists.forEach { p ->
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("coverImgUrl", p.coverImgUrl)
                    put("trackCount", p.trackCount)
                    put("specialType", p.specialType)
                })
            }
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
            Log.d(TAG, "playlists cached: ${playlists.size}")
        } catch (e: Exception) {
            Log.w(TAG, "savePlaylists failed: ${e.message}")
        }
    }

    fun loadPlaylists(context: Context): List<PlaylistApi.PlaylistInfo> {
        return try {
            val str = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PLAYLISTS, null) ?: return emptyList()
            val arr = org.json.JSONArray(str)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PlaylistApi.PlaylistInfo(
                    id = o.optLong("id"),
                    name = o.optString("name"),
                    coverImgUrl = o.optString("coverImgUrl"),
                    trackCount = o.optInt("trackCount"),
                    creatorUserId = 0,
                    specialType = o.optInt("specialType"),
                    privacy = 0
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadPlaylists failed: ${e.message}")
            emptyList()
        }
    }

    // ── 头像文件缓存 ────────────────────────────────

    private fun avatarFile(context: Context) = java.io.File(context.filesDir, AVATAR_FILE)

    /** 下载成功后落盘；下次启动离线也能显示。 */
    fun saveAvatar(context: Context, bitmap: android.graphics.Bitmap) {
        try {
            avatarFile(context).outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            Log.d(TAG, "avatar cached")
        } catch (e: Exception) {
            Log.w(TAG, "saveAvatar failed: ${e.message}")
        }
    }

    fun loadAvatar(context: Context): android.graphics.Bitmap? {
        return try {
            val f = avatarFile(context)
            if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (e: Exception) {
            null
        }
    }
}
