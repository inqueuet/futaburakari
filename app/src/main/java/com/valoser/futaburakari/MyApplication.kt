package com.valoser.futaburakari

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import android.util.Log
import androidx.work.Configuration
import android.content.pm.ApplicationInfo

@HiltAndroidApp
class MyApplication : Application() {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // Manually initialize WorkManager since we disabled its auto-init via manifest.
        val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val config = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (isDebug) Log.DEBUG else Log.INFO)
            .build()
        WorkManager.initialize(this, config)
    }

}
