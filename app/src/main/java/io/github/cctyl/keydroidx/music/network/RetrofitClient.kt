package io.github.cctyl.keydroidx.music.network

import android.content.Context
import io.github.cctyl.keydroidx.music.auth.CookieManager
import io.github.cctyl.keydroidx.music.network.crypto.EapiCrypto
import io.github.cctyl.keydroidx.music.network.crypto.WeapiCookie
import io.github.cctyl.keydroidx.music.network.crypto.WeapiCrypto
import okhttp3.*
import org.json.JSONObject
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = "https://music.163.com/"
    private const val INTERFACE_URL = "https://interface3.music.163.com/"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    /** weapi 通道专用 UA（取自 NeteaseCloudMusicApi 的 weapi UA 映射表，桌面 Chrome 形态） */
    private const val WEAPI_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"

    private var currentCookie: String? = null

    fun init(context: Context) {
        currentCookie = CookieManager.getCookie(context)
        WeapiCookie.init(context)
    }

    fun updateCookie(context: Context, cookie: String?) {
        currentCookie = cookie
        if (cookie != null) {
            CookieManager.saveCookie(context, cookie)
        }
    }

    fun getCookie(): String? = currentCookie

    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val api: NcmApi by lazy {
        val client = OkHttpClient.Builder().apply {
            // �򵥴��� Debug �ж�
            addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            addInterceptor(CookieInterceptor())
            connectTimeout(30, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
        }.build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NcmApi::class.java)
    }

    fun eapiPost(
        path: String,
        payload: Map<String, String>,
        useInterface: Boolean = false
    ): Response {
        val host = if (useInterface) INTERFACE_URL else BASE_URL
        val fullUrl = host + path.removePrefix("/")
        
        val anyPayload = payload.mapValues { it.value as Any }
        val params = EapiCrypto.encryptParams(fullUrl, anyPayload)

        val requestBody = FormBody.Builder()
            .add("params", params)
            .build()

        val request = Request.Builder()
            .url(fullUrl)
            .post(requestBody)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", currentCookie ?: "")
            .build()

        return plainClient.newCall(request).execute()
    }

    /**
     * weapi（网页端加密）POST。
     *
     * 对应参考实现 `util/request.js` 的 weapi 分支：
     * `url = https://music.163.com/weapi/ + uri.substr(5)`，即 `/api/xxx` → `/weapi/xxx`。
     * 请求头固定带 `Referer: https://music.163.com` 与桌面 Chrome UA，表单键为
     * `params` / `encSecKey`。
     *
     * 本项目**写操作（发评论等）走这条通道**：参考实现 `module/comment.js`
     * 固定使用 weapi，eapi 对写接口有更严的风控。
     *
     * @param uri 以 `/api/` 开头的网易云接口路径，如 `/api/resource/comments/add`
     */
    fun weapiPost(uri: String, data: Map<String, String>): Response {
        val url = BASE_URL + "weapi/" + uri.removePrefix("/").removePrefix("api/")

        val form = WeapiCrypto.encrypt(JSONObject(data as Map<String, Any?>).toString())
        val requestBody = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("User-Agent", WEAPI_UA)
            .header("Referer", "https://music.163.com")
            // cookie 需按参考实现重建（补全设备档位字段并做 encodeURIComponent），
            // 不能像 eapi 那样原样透传，否则会被判定为异常客户端
            .header("Cookie", WeapiCookie.build(currentCookie, uri))
            .build()

        return plainClient.newCall(request).execute()
    }

    /**
     * 普通 GET（非 eapi，与 ncrust 的 RetrofitClient.get 同源）。
     * 用于 /api/v1/user/detail/{uid} 等公开接口，自动带 cookie。
     */
    fun get(path: String): okhttp3.Response {
        val request = Request.Builder()
            .url(BASE_URL + path)
            .get()
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", currentCookie ?: "")
            .build()
        return plainClient.newCall(request).execute()
    }

    private class CookieInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val cookie = currentCookie
            val newRequest = if (!cookie.isNullOrBlank()) {
                originalRequest.newBuilder()
                    .header("Cookie", cookie)
                    .header("User-Agent", UA)
                    .header("Referer", "https://music.163.com/")
                    .build()
            } else {
                originalRequest
            }
            return chain.proceed(newRequest)
        }
    }
}
