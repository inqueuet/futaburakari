package com.valoser.futaburakari

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MyApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()

        // PublicSuffixDatabaseの初期化を安全に実行
        initializeOkHttpSafely()

        // WorkManager auto-init is handled by Jetpack Startup using this configuration.
    }

    private fun initializeOkHttpSafely() {
        // メインスレッドをブロックしないようにバックグラウンドで実行
        Thread {
            try {
                // ダミーのOkHttpClientを作成して初期化を促進
                val dummyClient = okhttp3.OkHttpClient.Builder().build()
                // 実際にリクエストは送らず、初期化だけを行う
                Log.d("MyApplication", "OkHttp initialized successfully")
            } catch (e: Exception) {
                // エラーをログに記録するが、アプリのクラッシュは避ける
                Log.w("MyApplication", "OkHttp initialization warning (non-critical)", e)
            }
        }.start()
    }

    override val workManagerConfiguration: Configuration
        get() {
            val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            return Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(if (isDebug) Log.DEBUG else Log.INFO)
                .setDefaultProcessName(packageName) // マルチプロセス想定時は有効化
                .build()
        }
}