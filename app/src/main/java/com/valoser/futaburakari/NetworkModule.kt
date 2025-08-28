package com.valoser.futaburakari

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // Backward-compatible static access during migration
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(PersistentCookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val requestWithUserAgent = originalRequest.newBuilder()
                // UA を単一の定数に統一（ptua と整合）
                .header("User-Agent", Ua.STRING)
                .build()
            chain.proceed(requestWithUserAgent)
        }
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = okHttpClient

    @Provides
    @Singleton
    fun provideNetworkClient(): NetworkClient = NetworkClient
}
