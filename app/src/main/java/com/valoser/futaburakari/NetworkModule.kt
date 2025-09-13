package com.valoser.futaburakari

/**
 * ネットワーク関連の依存を提供する Hilt モジュール。
 *
 * 提供内容
 * - `ConnectionPool`: アプリ全体で共有する OkHttp の接続プール（デフォルト設定）
 * - `CookieJar`: 永続化されたアプリ共通の Cookie を提供（`PersistentCookieJar`）
 * - `OkHttpClient`（用途別に2系統）:
 *   - API 用（デフォルト DI）
 *     - 共通 UA 付与、タイムアウト、CookieJar/ConnectionPool/HTTP キャッシュ（約50MB）を設定
 *     - Dispatcher: ユーザー設定に基づく `maxRequests = N`, `maxRequestsPerHost = N`（N は 1..4）
 *     - 2chan 系ホストへは軽い遅延（約 2ms）でレート抑制
 *     - 例外発生時は段階的にフォールバック（最小構成→完全デフォルト）
 *   - 画像取得用（`@Named("coil")`）
 *     - 設定は API 用と同等（Dispatcher はユーザー設定値、2chan は ~2ms 遅延、UA/Timeout/CookieJar/ConnectionPool）
 * - `NetworkClient`: API 用 OkHttpClient を用いるシングルトンの HTML/Bytes フェッチラッパー
 */

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import okhttp3.CookieJar
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Cache
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import java.io.File
import kotlin.time.Duration.Companion.seconds


@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpCache(@ApplicationContext context: Context): Cache {
        // アプリのキャッシュ領域配下に OkHttp の HTTP キャッシュを作成（約 50MB）
        val dir = File(context.cacheDir, "okhttp_http_cache").apply { mkdirs() }
        val sizeBytes = 50L * 1024L * 1024L
        return Cache(dir, sizeBytes)
    }

    @Provides
    @Singleton
    fun provideConnectionPool(): ConnectionPool {
        // OkHttp の接続プールをアプリ全体で共有（デフォルト設定）
        return ConnectionPool()
    }

    @Provides
    @Singleton
    fun provideCookieJar(@ApplicationContext context: Context): CookieJar {
        // OkHttp が利用する前に永続 CookieJar を初期化しておく
        PersistentCookieJar.init(context)
        return PersistentCookieJar
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        cookieJar: CookieJar,
        connectionPool: ConnectionPool,
        cache: Cache,
    ): OkHttpClient {
        /**
         * API 用の `OkHttpClient` を生成して提供する。
         * - UA/タイムアウト/HTTPキャッシュ/共有ConnectionPool/Cookie を設定
         * - 同時接続数はユーザー設定値（1..4）を Dispatcher に反映
         * - 2chan 系ホストへは軽い遅延（約 2ms）を入れてアクセス頻度を抑制
         * - 異常時は縮退構成でフォールバック
         */
        return try {
            // 同時接続数はユーザー設定値を使用
            val level = AppPreferences.getConcurrencyLevel(context)
            val dispatcher = Dispatcher().apply {
                maxRequests = level
                maxRequestsPerHost = level
            }

            OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .cookieJar(cookieJar)
                .cache(cache)
                .connectTimeout(30.seconds)
                .writeTimeout(60.seconds)
                .readTimeout(60.seconds)
                .addInterceptor { chain ->
                    val originalRequest = chain.request()
                    val host = originalRequest.url.host
                    val builder = originalRequest.newBuilder()
                        .header("User-Agent", Ua.STRING)

                    // 2chan 系はアクセス頻度をさらに抑制（1ms）
                    if (host == "2chan.net" || host.endsWith(".2chan.net")) {
                        try { Thread.sleep(1L) } catch (_: InterruptedException) {}
                    }

                    chain.proceed(builder.build())
                }
                .build()
        } catch (e: Exception) {
            Log.e("NetworkModule", "Failed to create OkHttpClient with full configuration", e)
            // フォールバック：最小構成のクライアントで再試行（Cookie/Timeout のみ）
            try {
                OkHttpClient.Builder()
                    .connectionPool(connectionPool)
                    .cookieJar(cookieJar)
                    .cache(cache)
                    .connectTimeout(30.seconds)
                    .readTimeout(60.seconds)
                    .build()
            } catch (fallbackException: Exception) {
                Log.e("NetworkModule", "Fallback OkHttpClient creation also failed", fallbackException)
                // 最後の手段：完全デフォルトのクライアント
                OkHttpClient.Builder()
                    .connectionPool(connectionPool)
                    .build()
            }
        }
    }

    @Provides
    @Singleton
    @Named("coil")
    fun provideCoilOkHttpClient(
        @ApplicationContext context: Context,
        cookieJar: CookieJar, connectionPool: ConnectionPool): OkHttpClient {
        /**
         * 画像取得（Coil）用の `OkHttpClient` を生成して提供する。
         * - 設定は API 用と同等（UA/Timeout/Cookie/ConnectionPool）
         * - Dispatcher はユーザー設定の同時接続数（1..4）
         * - 2chan 系ホストへは軽い遅延（約 2ms）でアクセス頻度を抑制
         * - 異常時は縮退構成でフォールバック
         */
        return try {
            val level = AppPreferences.getConcurrencyLevel(context)
            val dispatcher = Dispatcher().apply {
                maxRequests = level
                maxRequestsPerHost = level
            }

            OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .cookieJar(cookieJar)
                .connectTimeout(30.seconds)
                .writeTimeout(60.seconds)
                // 画像取得は失敗確定をさらに早める（短め）
                .readTimeout(8.seconds)
                .callTimeout(8.seconds)
                .addInterceptor { chain ->
                    val originalRequest = chain.request()
                    val host = originalRequest.url.host
                    val builder = originalRequest.newBuilder()
                        .header("User-Agent", Ua.STRING)

                    if (host == "2chan.net" || host.endsWith(".2chan.net")) {
                        try { Thread.sleep(1L) } catch (_: InterruptedException) {}
                    }

                    chain.proceed(builder.build())
                }
                .build()
        } catch (e: Exception) {
            Log.e("NetworkModule", "Failed to create Coil OkHttpClient", e)
            try {
                OkHttpClient.Builder()
                    .connectionPool(connectionPool)
                    .cookieJar(cookieJar)
                    .connectTimeout(30.seconds)
                    // 縮退時もタイムアウト方針は維持（さらに短め）
                    .readTimeout(8.seconds)
                    .callTimeout(8.seconds)
                    .build()
            } catch (fallbackException: Exception) {
                Log.e("NetworkModule", "Fallback Coil OkHttpClient creation also failed", fallbackException)
                OkHttpClient.Builder()
                    .connectionPool(connectionPool)
                    .build()
            }
        }
    }

    @Provides
    @Singleton
    fun provideNetworkClient(okHttpClient: OkHttpClient): NetworkClient = NetworkClient(okHttpClient)
}
