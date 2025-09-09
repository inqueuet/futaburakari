/*
 * アプリケーション全体の初期化を担う Application 実装。
 * - WorkManager 構成、Coil ImageLoader、各種初期化（OkHttp ウォームアップ等）を提供。
 */
package com.valoser.futaburakari

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.decode.SvgDecoder
import coil.util.DebugLogger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.preference.PreferenceManager
import com.valoser.futaburakari.worker.ThreadMonitorWorker
import com.valoser.futaburakari.HistoryManager

@HiltAndroidApp
/**
 * アプリ全体の初期化を担う `Application` 実装。
 *
 * - WorkManager の設定（HiltWorkerFactory/ログレベル/プロセス名）
 * - OkHttp の安全なウォームアップ（初回リクエストの遅延を軽減）
 * - Coil 用 ImageLoader の提供（GIF/動画フレーム/SVG/AVIF のデコードを有効化）
 */
class MyApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

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
     * - GIF/動画フレーム/SVG/AVIF のデコードを有効化
     * - メモリ/ディスクキャッシュを調整し、再利用性を高める
     * - 開発時のデバッグロガーを有効化
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                // アニメーションGIFのデコードを有効化
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                // 動画の代表フレームの抽出を有効化
                add(VideoFrameDecoder.Factory())

                // SVGをサポート（サーバ側で配信される場合のフォールバック）
                add(SvgDecoder.Factory())
            }
            // メモリ/ディスクキャッシュを明示設定（プリフェッチの効果を高める）
            .memoryCache(
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // メモリの25%まで
                    .build()
            )
            .diskCache(
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024L * 1024L) // 256MB
                    .build()
            )
            .respectCacheHeaders(false) // サーバーのキャッシュヘッダが厳しい場合でも再利用
            // 開発時のデバッグログを有効化（失敗理由の特定に有用）
            .logger(DebugLogger())
            .build()
    }
}
