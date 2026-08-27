package io.github.cctyl.keydroidx.music.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import io.github.cctyl.keydroidx.music.util.NLog as Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import io.github.cctyl.keydroidx.music.auth.CookieManager
import io.github.cctyl.nokia.keycore.ui.NokiaFontManager
import io.github.cctyl.keydroidx.music.network.RetrofitClient

/**
 * 网易云音乐 WebView 登录页（抄自 fork_Ncrust 的 WebLogin）。
 *
 * 流程：
 *  1. 打开 https://music.163.com/#/login
 *  2. 用户在网页内完成登录（手机号 / 二维码 / 邮箱）
 *  3. onPageFinished 时抓 cookie，若含 "MUSIC_U=" 视为登录成功
 *  4. 存入 CookieManager + 同步 RetrofitClient + setResult(RESULT_OK) + finish()
 *
 * 按键机：BACK / 返回键直接 finish()（未登录可放弃）。
 * 这是个纯 WebView Activity，不走 NokiaBaseActivity，让网页占满整屏。
 */
class WebLoginActivity : Activity() {

    companion object {
        private const val TAG = "WebLoginActivity"
        private const val LOGIN_URL = "https://music.163.com/#/login"
        const val RESULT_LOGIN_OK = Activity.RESULT_OK
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 根容器：WebView 铺满 + 右上角一个「✕ 关闭」浮层
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // 允许第三方 cookie，确保登录态完整
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
                    Log.d(TAG, "onPageFinished url=$url")
                    if (cookie != null) {
                        Log.d(TAG, "raw cookie length=${cookie.length}, has MUSIC_U=${cookie.contains("MUSIC_U=")}")
                        Log.d(TAG, "cookie preview: ${cookie.take(120)}…")
                        if (cookie.contains("MUSIC_U=")) {
                            // 登录成功：持久化 + 同步运行时 + 返回
                            CookieManager.saveCookie(this@WebLoginActivity, cookie)
                            // 验证：读回确认已持久化
                            val saved = CookieManager.getCookie(this@WebLoginActivity)
                            Log.d(TAG, "saved cookie length=${saved?.length ?: -1}, has MUSIC_U=${saved?.contains("MUSIC_U=")}")
                            if (saved != null && saved.contains("MUSIC_U=")) {
                                Log.d(TAG, "✅ Cookie 持久化验证成功")
                            } else {
                                Log.e(TAG, "❌ Cookie 持久化验证失败！")
                            }

                            RetrofitClient.updateCookie(this@WebLoginActivity, cookie)
                            val runtimeCookie = RetrofitClient.getCookie()
                            Log.d(TAG, "runtime cookie after update: ${runtimeCookie?.take(80)}…")
                            Log.d(TAG, "✅ Login OK, cookie saved and synced to RetrofitClient.")

                            setResult(RESULT_LOGIN_OK)
                            finish()
                        } else {
                            Log.d(TAG, "⏳ 页面加载完成，但未检测到 MUSIC_U=，继续等待登录…")
                        }
                    } else {
                        Log.d(TAG, "⏳ cookie 为空，继续等待登录…")
                    }
                }
            }

            // 进页前先清掉 WebView 旧 cookie，避免误判已登录
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            loadUrl(LOGIN_URL)
        }
        root.addView(webView)

        // 右上角关闭按钮（点阵风：✕ 字符，按键机也可按 BACK）
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(0xFFFFFFFF.toInt())
            NokiaFontManager.setTextSize(this, android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(28, 18, 28, 18)
            setBackgroundColor(0x88000000.toInt())
            setOnClickListener { finish() }
        }
        val closeLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        root.addView(closeBtn, closeLp)

        setContentView(root)
    }

    /**
     * 按键机返回键：直接关闭（放弃登录）。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
