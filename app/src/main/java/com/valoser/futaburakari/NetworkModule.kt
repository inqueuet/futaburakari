package com.valoser.futaburakari

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideCookieJar(@ApplicationContext context: Context): CookieJar {
        // Ensure initialization before any OkHttp uses the jar
        PersistentCookieJar.init(context)
        return PersistentCookieJar
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(cookieJar: CookieJar): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithUserAgent = originalRequest.newBuilder()
                    .header("User-Agent", Ua.STRING)
                    .build()
                chain.proceed(requestWithUserAgent)
            }
            .build()

    @Provides
    @Singleton
    fun provideNetworkClient(okHttpClient: OkHttpClient): NetworkClient = NetworkClient(okHttpClient)
}
