package io.github.cctyl.keydroidx.music.network

import android.content.Context
import io.github.cctyl.keydroidx.music.auth.CookieManager
import io.github.cctyl.keydroidx.music.network.crypto.EapiCrypto
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = "https://music.163.com/"
    private const val INTERFACE_URL = "https://interface3.music.163.com/"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private var currentCookie: String? = null

    fun init(context: Context) {
        currentCookie = CookieManager.getCookie(context)
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
