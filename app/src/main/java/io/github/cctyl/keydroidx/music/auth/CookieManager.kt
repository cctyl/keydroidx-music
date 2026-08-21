package io.github.cctyl.keydroidx.music.auth

import android.content.Context
import android.content.SharedPreferences

object CookieManager {
    private const val PREF_NAME = "keydroidx_music_auth"
    private const val KEY_COOKIE = "cookie"

    fun getCookie(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COOKIE, null)
    }

    fun saveCookie(context: Context, cookie: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COOKIE, cookie)
            .apply()
    }
}
