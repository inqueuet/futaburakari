/*
 * アプリケーション全体の初期化を担う Application 実装。
 * - WorkManager 構成、Coil ImageLoader、各種初期化（OkHttp ウォームアップ等）を提供。
 */
package com.valoser.futaburakari

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
import com.valoser.futaburakari.cache.DetailCacheManager
import com.valoser.futaburakari.worker.ThreadMonitorWorker
import com.valoser.futaburakari.HistoryManager
import com.valoser.futaburakari.videoeditor.export.ExportPipeline
import com.valoser.futaburakari.videoeditor.media.player.PlayerEngine
import okio.Path.Companion.toPath
import java.util.concurrent.TimeUnit

@HiltAndroidApp
/**
 * アプリ全体の初期化を担う `Application` 実装。
 *
 * - WorkManager の設定（HiltWorkerFactory/ログレベル/デフォルトプロセス名）。
 * - OkHttp の安全なウォームアップ（ダミークライアントで内部コンポーネントを先行初期化）。
 * - Coil 用 ImageLoader の提供（用途別 OkHttp クライアントとメモリ/ディスクキャッシュ構成、デバッグビルド時のロガー対応）。
 * - 起動時に履歴へ登録済みのスレッド監視を再スケジュールし、キャッシュ操作用のユーティリティも公開。
 */
class MyApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    @Named("coil")
    lateinit var coilOkHttpClient: OkHttpClient // Coil の画像読み込み専用に DI された OkHttpClient

    @Inject
    lateinit var detailCacheManager: DetailCacheManager

    @Inject
    lateinit var metadataCache: MetadataCache

    @Inject
    lateinit var exportPipeline: ExportPipeline

    @Inject
    lateinit var playerEngine: PlayerEngine

    // プロセス全体で使い回すアプリケーションスコープ（初期化やバックグラウンド再スケジュールで利用）
    private val supervisorJob = SupervisorJob()
    private val applicationScope = CoroutineScope(supervisorJob + Dispatchers.Default)

    // Coil キャッシュ管理やリクエストユーティリティをまとめたコンパニオン
    companion object {
        private const val AUTO_RESCHEDULE_MAX_THREADS = 8
        private val AUTO_RESCHEDULE_WINDOW_MS = TimeUnit.HOURS.toMillis(6)

        fun clearCoilImageCache(context: Context) {
            try {
                // Coilのシングルトンインスタンスを取得してメモリキャッシュをクリア
                SingletonImageLoader.get(context).memoryCache?.clear()
                Log.i("MyApplication", "Coil memory cache cleared")
            } catch (e: Exception) {
                Log.w("MyApplication", "Failed to clear Coil memory cache", e)
            }
        }

        fun clearCoilDiskCache(context: Context) {
            try {
                // ディスクキャッシュも非同期でクリア
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    SingletonImageLoader.get(context).diskCache?.clear()
                    Log.i("MyApplication", "Coil disk cache cleared")
                }
            } catch (e: Exception) {
                Log.w("MyApplication", "Failed to clear Coil disk cache", e)
            }
        }

        fun getCoilCacheInfo(context: Context): String {
            return try {
                val imageLoader = SingletonImageLoader.get(context)
                val memCache = imageLoader.memoryCache
                val diskCache = imageLoader.diskCache

                val memInfo = if (memCache != null && memCache.maxSize > 0) {
                    "Memory: ${memCache.size}/${memCache.maxSize} (${(memCache.size.toFloat() / memCache.maxSize * 100).toInt()}%)"
                } else {
                    "Memory: N/A"
                }

                val diskInfo = if (diskCache != null && diskCache.maxSize > 0) {
                    "Disk: ${diskCache.size / 1024 / 1024}MB/${diskCache.maxSize / 1024 / 1024}MB"
                } else {
                    "Disk: N/A"
                }

                "$memInfo, $diskInfo"
            } catch (e: Exception) {
                "Cache info unavailable: ${e.message}"
            }
        }

        /**
         * ディスクキャッシュを優先したImageRequestを作成するヘルパー関数
         * メモリ使用量を抑制しつつ、ディスクキャッシュからの高速読み込みを優先する
         */
        fun createDiskOptimizedImageRequest(
            context: Context,
            data: Any,
            memoryCacheRead: Boolean = true,
            memoryCacheWrite: Boolean = false // デフォルトでメモリキャッシュへの書き込みを無効化
        ): coil3.request.ImageRequest {
            return coil3.request.ImageRequest.Builder(context)
                .data(data)
                .memoryCachePolicy(
                    if (memoryCacheRead && memoryCacheWrite) coil3.request.CachePolicy.ENABLED
                    else if (memoryCacheRead) coil3.request.CachePolicy.READ_ONLY
                    else if (memoryCacheWrite) coil3.request.CachePolicy.WRITE_ONLY
                    else coil3.request.CachePolicy.DISABLED
                )
                .diskCachePolicy(coil3.request.CachePolicy.ENABLED) // ディスクキャッシュは常に有効
                .build()
        }

    }

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

        // 履歴情報をもとにバックグラウンド監視を再評価。
        // 直近に閲覧したスレッドのみ定期監視を再スケジュールし、古いものは停止させる。
        applicationScope.launch {
            runCatching { HistoryManager.getAll(this@MyApplication) }
                .onSuccess { list ->
                    val now = System.currentTimeMillis()
                    val active = list
                        .filter { !it.isArchived && now - it.lastViewedAt <= AUTO_RESCHEDULE_WINDOW_MS }
                        .sortedByDescending { it.lastViewedAt }
                        .take(AUTO_RESCHEDULE_MAX_THREADS)

                    list.filter { it.isArchived || now - it.lastViewedAt > AUTO_RESCHEDULE_WINDOW_MS }
                        .forEach { entry ->
                            kotlin.runCatching { ThreadMonitorWorker.cancelByKey(this@MyApplication, entry.key) }
                        }

                    active.forEach { entry ->
                        kotlin.runCatching { ThreadMonitorWorker.schedule(this@MyApplication, entry.url) }
                    }
                }
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
     *
     * @return WorkManager のグローバル構成
     */
    override val workManagerConfiguration: Configuration
        get() {
            val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            return Configuration.Builder()
                .setWorkerFactory(workerFactory)
                // Suppress WorkManager's informational logs in release builds.
                .setMinimumLoggingLevel(if (isDebug) Log.DEBUG else Log.ERROR)
                .setDefaultProcessName(packageName) // マルチプロセス想定時のための設定
                .build()
        }

    /**
     * Coil の ImageLoader を構築して提供する。
     * - GIF/動画フレーム/SVG のデコードは対応モジュールの自動登録に任せる構成
     * - メモリ/ディスクキャッシュを調整し、再利用性を高める
     * - デバッグロガーを有効化（失敗理由の追跡に有用）
     *
     * @param context アプリケーションコンテキスト
     * @return 構成済みの `ImageLoader`
     */
    override fun newImageLoader(context: Context): ImageLoader {
        val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        // 利用可能なディスク容量に基づいてキャッシュサイズを動的に計算
        val diskCacheSize = calculateDiskCacheSize(context)

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
            // メモリ/ディスクキャッシュを明示設定（ディスクキャッシュを優先、メモリ使用量を抑制）
            .memoryCache(
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.10) // メモリの10%までに抑えつつ、ヒット率を確保
                    .strongReferencesEnabled(true) // プリフェッチ結果を強参照で保持して再デコードを抑止
                    .build()
            )
            .diskCache(
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").absolutePath.toPath())
                    .maxSizeBytes(diskCacheSize)
                    .cleanupDispatcher(Dispatchers.IO) // ディスクI/Oの最適化
                    .build()
            )
            // デバッグビルド時のみ詳細ログを有効化
            .apply { if (isDebug) logger(DebugLogger()) }
            .build()
    }

    /**
     * 利用可能なディスク容量に基づいて最適なキャッシュサイズを計算する。
     * - 利用可能な容量の10%、ただし最小500MB、最大8GBとする。
     * - 利用可能な容量が5GB未満の場合は、より控えめな設定を使用する。
     */
    private fun calculateDiskCacheSize(context: Context): Long {
        return try {
            val cacheDir = context.cacheDir
            val usableSpace = cacheDir.usableSpace
            val totalSpace = cacheDir.totalSpace

            // 利用可能な容量の10%を基準とする
            val tenPercent = usableSpace / 10

            // 最小500MB、最大8GB
            val minSize = 500L * 1024L * 1024L // 500MB
            val maxSize = 8L * 1024L * 1024L * 1024L // 8GB

            val calculatedSize = when {
                // 利用可能な容量が5GB未満の場合は最大2GBに制限
                usableSpace < 5L * 1024L * 1024L * 1024L -> {
                    minOf(tenPercent, 2L * 1024L * 1024L * 1024L)
                }
                // 通常は10%、ただし最大8GB
                else -> tenPercent.coerceIn(minSize, maxSize)
            }

            Log.i("MyApplication", "Disk cache size: ${calculatedSize / 1024 / 1024}MB (usable: ${usableSpace / 1024 / 1024}MB, total: ${totalSpace / 1024 / 1024}MB)")
            calculatedSize
        } catch (e: Exception) {
            Log.w("MyApplication", "Failed to calculate disk cache size, using default 2GB", e)
            // エラー時は控えめな2GBを返す
            2L * 1024L * 1024L * 1024L
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        // アプリケーション終了時にリソースクリーンアップ
        try {
            ThreadMonitorWorker.cancelAll(this)
            detailCacheManager.cleanup()
            metadataCache.close()
            exportPipeline.cleanup()
            playerEngine.release()
            supervisorJob.cancel()
        } catch (e: Exception) {
            Log.w("MyApplication", "Error during resource cleanup", e)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w("MyApplication", "onLowMemory called - performing emergency memory cleanup")
        // メモリ不足時は即座にメモリキャッシュのみクリア（軽量操作）
        clearCoilImageCache(this)
        // ディスクキャッシュクリアは非同期で遅延実行（重い操作）
        applicationScope.launch {
            try {
                clearCoilDiskCache(this@MyApplication)
                detailCacheManager.cleanup()
            } catch (e: Exception) {
                Log.w("MyApplication", "Error during low memory cleanup", e)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d("MyApplication", "onTrimMemory called with level=$level")

        // UI非表示時はメタデータキャッシュをフラッシュ
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            metadataCache.flush().invokeOnCompletion { error ->
                if (error != null) {
                    Log.w("MyApplication", "Metadata cache flush failed on trim", error)
                }
            }
        }

        // メモリトリムレベルに応じて段階的なキャッシュクリア
        when (level) {
            // 最も深刻：メモリキャッシュクリア + 非同期でディスクキャッシュクリア
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w("MyApplication", "Critical memory pressure - clearing all caches")
                clearCoilImageCache(this)
                applicationScope.launch {
                    try {
                        clearCoilDiskCache(this@MyApplication)
                        detailCacheManager.cleanup()
                    } catch (e: Exception) {
                        Log.w("MyApplication", "Error during critical memory cleanup", e)
                    }
                }
            }
            // 中程度：メモリキャッシュのみクリア（軽量）
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.i("MyApplication", "Moderate memory pressure - clearing memory cache only")
                clearCoilImageCache(this)
            }
        }
    }
}
