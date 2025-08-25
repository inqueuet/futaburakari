package com.example.hutaburakari.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.hutaburakari.HistoryManager
import com.example.hutaburakari.NetworkClient
import com.example.hutaburakari.UrlNormalizer
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

class ThreadMonitorWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.success()

        // 設定が無効なら即終了
        val prefs = applicationContext.getSharedPreferences(PREFS_BG, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_BG_ENABLED, false)) {
            return Result.success()
        }

        // 履歴に無いスレッドは監視しない
        val key = UrlNormalizer.threadKey(url)
        val inHistory = try {
            HistoryManager.getAll(applicationContext).any { it.key == key }
        } catch (_: Exception) { false }
        if (!inHistory) {
            cancelUnique(url)
            return Result.success()
        }

        return try {
            val doc: Document = NetworkClient.fetchDocument(url)
            val exists = doc.selectFirst("div.thre") != null
            if (!exists) {
                // スレが消滅したと判断 → この監視を停止
                cancelUnique(url)
            }
            // まだ存在するなら次の1分後のチェックをスケジュール
            schedule(applicationContext, url)
            Result.success()
        } catch (e: Exception) {
            // 404等は消滅とみなす
            cancelUnique(url)
            Result.success()
        }
    }

    private fun cancelUnique(url: String) {
        val unique = uniqueName(url)
        WorkManager.getInstance(applicationContext).cancelUniqueWork(unique)
    }

    companion object {
        private const val KEY_URL = "url"
        const val PREFS_BG = "com.example.hutaburakari.bg"
        const val KEY_BG_ENABLED = "pref_key_bg_monitor_enabled"

        private fun uniqueName(url: String): String = "monitor-" + UrlNormalizer.threadKey(url)
        private fun uniqueNameFromKey(key: String): String = "monitor-" + key

        fun schedule(context: Context, url: String) {
            val prefs = context.getSharedPreferences(PREFS_BG, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_BG_ENABLED, false)) return

            val data = workDataOf(KEY_URL to url)
            val req = OneTimeWorkRequestBuilder<ThreadMonitorWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setInputData(data)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("thread-monitor")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(url),
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag("thread-monitor")
        }

        fun cancelByUrl(context: Context, url: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(url))
        }

        fun cancelByKey(context: Context, key: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueNameFromKey(key))
        }
    }
}
