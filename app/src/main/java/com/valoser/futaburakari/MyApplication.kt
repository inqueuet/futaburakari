/*
 * アプリケーション全体の初期化を担う Application 実装。
 * - WorkManager 構成、Coil ImageLoader、各種初期化（OkHttp ウォームアップ等）を提供。
 */
package com.valoser.futaburakari

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.util.DebugLogger
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.preference.PreferenceManager
import com.valoser.futaburakari.worker.ThreadMonitorWorker
import com.valoser.futaburakari.HistoryManager
import okio.Path.Companion.toPath

@HiltAndroidApp
/**
 * アプリ全体の初期化を担う `Application` 実装。
 *
 * - WorkManager の設定（HiltWorkerFactory/ログレベル/デフォルトプロセス名）
 * - OkHttp の安全なウォームアップ（初回リクエストの遅延を軽減）
 *   - DI の共有クライアントとは独立したダミー `OkHttpClient` を生成し、
 *     内部コンポーネント（例: PublicSuffixDatabase）を初期化するのみ（実通信なし）
 * - Coil 用 ImageLoader の提供（GIF/動画フレーム/SVG のデコードを有効化）
 *   - OkHttp クライアントは `@Named("coil")` の用途別クライアントを使用
 *   - Dispatcher は 2 並列（per-host も 2）、2chan 系は 100ms 遅延
 */
class MyApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    @Named("coil")
    lateinit var coilOkHttpClient: OkHttpClient // Coil 専用の OkHttpClient（Dispatcher 2/2、2chan は 100ms 遅延）

    // アプリケーションスコープ（初期化の非同期実行に使用）
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // OkHttp（PublicSuffixDatabase など）の初期化を安全に実行
        initializeOkHttpSafely()
        // WorkManager の AutoInit はこの Configuration 経由で行われる

        // 互換目的のプリファレンス移行（旧カラー設定キーの削除。現在は未使用）
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val editor = prefs.edit()
            var changed = false
            listOf(
                "pref_key_color_mode",
                "pref_key_color_theme"
            ).forEach { key ->
                if (prefs.contains(key)) { editor.remove(key); changed = true }
            }
            if (changed) editor.apply()
        } catch (t: Throwable) {
            Log.w("MyApplication", "Preference migration (legacy color) skipped", t)
        }

        // 履歴に登録済みのスレッドはバックグラウンド監視を常時有効化（再起動後も再スケジュール）
        applicationScope.launch {
            runCatching { HistoryManager.getAll(this@MyApplication) }
                .onSuccess { list -> list.forEach { e ->
                    kotlin.runCatching { ThreadMonitorWorker.schedule(this@MyApplication, e.url) }
                } }
        }
    }

    /**
     * OkHttp の内部コンポーネント（例: PublicSuffixDatabase）を事前初期化する。
     * 実通信は行わず、起動直後の初回アクセスで発生する遅延を低減する目的。
     */
    private fun initializeOkHttpSafely() {
        // アプリケーションスコープのコルーチンで非同期に実行
        applicationScope.launch {
            try {
                // ダミーの OkHttpClient を生成して内部初期化を促進（実通信は行わない）
                val dummyClient = okhttp3.OkHttpClient.Builder().build()
                // 実際にリクエストは送らず、初期化だけを行う
                Log.d("MyApplication", "OkHttp initialized successfully")
            } catch (e: Exception) {
                // エラーはログに留め、アプリは継続（非クリティカル）
                Log.w("MyApplication", "OkHttp initialization warning (non-critical)", e)
            }
        }
    }

    /**
     * WorkManager の構成を提供する。
     * HiltWorkerFactory を設定し、ビルド種類に応じたログレベルとデフォルトのプロセス名を指定。
     */
    override val workManagerConfiguration: Configuration
        get() {
            val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            return Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(if (isDebug) Log.DEBUG else Log.INFO)
                .setDefaultProcessName(packageName) // マルチプロセス想定時のための設定
                .build()
        }

    /**
     * Coil の ImageLoader を構築して提供する。
     * - GIF/動画フレーム/SVG のデコードを有効化
     * - メモリ/ディスクキャッシュを調整し、再利用性を高める
     * - デバッグロガーを有効化（失敗理由の追跡に有用）
     */
    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                // OkHttp を使用したネットワークフェッチャーを追加（Coil 3 では必須）
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { coilOkHttpClient }
                    )
                )
                // GIF / 動画 / SVG のデコーダは拡張モジュール（coil-gif / coil-video / coil-svg）
                // を依存関係に追加すると自動登録されるため、手動追加は不要。
            }
            // メモリ/ディスクキャッシュを明示設定（プリフェッチの効果を高める）
            .memoryCache(
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25) // メモリの25%まで
                    .build()
            )
            .diskCache(
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").absolutePath.toPath())
                    .maxSizeBytes(256L * 1024L * 1024L) // 256MB
                    .build()
            )
            // 開発時のデバッグログを有効化（失敗理由の特定に有用）
            .logger(DebugLogger())
            .build()
    }
}
