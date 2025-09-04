package com.valoser.futaburakari

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
/**
 * アプリ全体の初期化を担う `Application` 実装。
 *
 * - WorkManager の設定（HiltWorkerFactory/ログレベル/プロセス名）を提供
 * - OkHttp の初期化を安全にウォームアップ（初回リクエストの遅延を軽減）
 * - Coil 用の ImageLoader を提供（GIF/動画フレームのデコードを有効化）
 */
class MyApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // アプリケーションスコープ（初期化の非同期実行に使用）
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // OkHttp（PublicSuffixDatabaseなど）の初期化を安全に実行
        initializeOkHttpSafely()
        // WorkManager の AutoInit はこの Configuration 経由で行われる
    }

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

    override val workManagerConfiguration: Configuration
        get() {
            val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            return Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(if (isDebug) Log.DEBUG else Log.INFO)
                .setDefaultProcessName(packageName) // マルチプロセス想定時のための設定
                .build()
        }

    // Coil: GIF 再生と動画URLからの代表フレーム抽出を有効化
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
            }
            .build()
    }
}
