package com.valoser.futaburakari

/**
 * ネットワーク関連の依存を提供する Hilt モジュール。
 *
 * 提供内容
 * - `ConnectionPool`: アプリ全体で共有する OkHttp の接続プール（デフォルト設定）
 * - `CookieJar`: 永続化されたアプリ共通の Cookie を提供（`PersistentCookieJar`）
 * - `OkHttpClient`（用途別に2系統）:
 *   - API 用（デフォルト DI）
 *     - 共通 UA 付与、タイムアウト、CookieJar/ConnectionPool 設定
 *     - Dispatcher: `maxRequests=2`, `maxRequestsPerHost=2`
 *     - 2chan 系ホストへは軽い遅延（100ms）でレート抑制
 *     - 例外発生時は段階的にフォールバック（最小構成→完全デフォルト）
 *   - 画像取得用（`@Named("coil")`）
 *     - 設定は API 用と同等（Dispatcher 2/2、100ms 遅延、UA/Timeout/CookieJar/ConnectionPool）
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
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

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
    fun provideOkHttpClient(cookieJar: CookieJar, connectionPool: ConnectionPool): OkHttpClient {
        return try {
            // 同時接続数を抑制（特にホスト単位）。Coil等の並列アクセスを穏やかにする
            val dispatcher = Dispatcher().apply {
                maxRequests = 2
                maxRequestsPerHost = 2
            }

            OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .cookieJar(cookieJar)
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val originalRequest = chain.request()
                    val host = originalRequest.url.host
                    val builder = originalRequest.newBuilder()
                        .header("User-Agent", Ua.STRING)

                    // 2chan 系はアクセス頻度をさらに抑制（100ms）
                    if (host == "2chan.net" || host.endsWith(".2chan.net")) {
                        try { Thread.sleep(100L) } catch (_: InterruptedException) {}
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
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
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
    fun provideCoilOkHttpClient(cookieJar: CookieJar, connectionPool: ConnectionPool): OkHttpClient {
        return try {
            val dispatcher = Dispatcher().apply {
                maxRequests = 2
                maxRequestsPerHost = 2
            }

            OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .cookieJar(cookieJar)
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val originalRequest = chain.request()
                    val host = originalRequest.url.host
                    val builder = originalRequest.newBuilder()
                        .header("User-Agent", Ua.STRING)

                    if (host == "2chan.net" || host.endsWith(".2chan.net")) {
                        try { Thread.sleep(100L) } catch (_: InterruptedException) {}
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
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
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
