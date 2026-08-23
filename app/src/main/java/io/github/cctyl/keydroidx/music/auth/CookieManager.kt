package io.github.cctyl.keydroidx.music.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * 网易云音乐 cookie 持久化。
 *
 * 登录流程（抄自 fork_Ncrust）：
 *  - WebView 打开 https://music.163.com/#/login
 *  - onPageFinished 时从 android.webkit.CookieManager.getCookie(url) 抓全量 cookie
 *  - 若包含 "MUSIC_U=" 视为登录成功，存入 SharedPreferences + 同步到 RetrofitClient
 *  - 服务端 cookie 失效时（getUserProfile 返回 userId=0）主动 clearCookie
 */
object CookieManager {
    private const val PREF_NAME = "keydroidx_music_auth"
    private const val KEY_COOKIE = "cookie"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveCookie(context: Context, cookie: String) {
        getPrefs(context).edit().putString(KEY_COOKIE, cookie).apply()
    }

    fun getCookie(context: Context): String? {
        return getPrefs(context).getString(KEY_COOKIE, null)
    }

    /** 是否已存在有效 cookie（仅判断本地是否存过，不校验服务端是否过期）。 */
    fun hasCookie(context: Context): Boolean {
        return !getCookie(context).isNullOrBlank()
    }

    /** 清除本地 cookie（退出登录 / 服务端判定过期时调用）。 */
    fun clearCookie(context: Context) {
        getPrefs(context).edit().remove(KEY_COOKIE).apply()
    }
}
