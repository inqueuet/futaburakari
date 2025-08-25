package com.valoser.hutaburakari

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
object NetworkModule {

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(PersistentCookieJar) // Changed from AppCookieJar
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val requestWithUserAgent = originalRequest.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
                .build()
            chain.proceed(requestWithUserAgent)
        }
        .build()
}