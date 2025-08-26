package com.valoser.futaburakari.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.valoser.futaburakari.HistoryManager
import com.valoser.futaburakari.NetworkClient
import com.valoser.futaburakari.UrlNormalizer
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit
import java.net.MalformedURLException
import java.net.URL
import com.valoser.futaburakari.DetailContent
import com.valoser.futaburakari.cache.DetailCacheManager
import okhttp3.Request
import java.io.File

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
                cancelUnique(url)
                return Result.success()
            }

            // 1) パース（UI側と同等の簡易ロジック）
            val parsed = parseContentFromDocument(doc, url)

            // 2) メディアを内部保存し、ローカルパスへ差し替え
            val archived = archiveMedia(applicationContext, url, parsed)

            // 3) キャッシュへ保存（置き換え保存）
            DetailCacheManager(applicationContext).saveDetails(url, archived)

            // 次回スケジュール
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
        const val PREFS_BG = "com.valoser.futaburakari.bg"
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

    private fun isMediaUrl(href: String): Boolean {
        val lower = href.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".webp") ||
                lower.endsWith(".mp4") || lower.endsWith(".webm")
    }

    private fun parseContentFromDocument(document: Document, baseUrl: String): List<DetailContent> {
        val result = mutableListOf<DetailContent>()
        var textId = 0L

        val threadContainer = document.selectFirst("div.thre") ?: return emptyList()

        val postBlocks = mutableListOf<Element>()
        postBlocks.add(threadContainer)
        threadContainer.select("td.rtd").mapNotNull { it.closest("table") }.distinct().let { postBlocks.addAll(it) }

        postBlocks.forEachIndexed { index, block ->
            val isOp = index == 0
            val html = if (isOp) {
                val clone = block.clone().apply { select("table").remove(); select("img").remove() }
                clone.html()
            } else {
                val rtd = block.selectFirst(".rtd")
                rtd?.clone()?.apply { select("img").remove() }?.html().orEmpty()
            }
            if (html.isNotBlank()) {
                result += DetailContent.Text(id = "text_${textId++}", htmlContent = html)
            }

            val a = block.select("a[target=_blank][href]").firstOrNull { el -> isMediaUrl(el.attr("href")) }
            if (a != null) {
                val href = a.attr("href")
                try {
                    val absolute = URL(URL(baseUrl), href).toString()
                    val fileName = absolute.substringAfterLast('/')
                    val lower = href.lowercase()
                    if (lower.endsWith(".mp4") || lower.endsWith(".webm")) {
                        result += DetailContent.Video(id = absolute, videoUrl = absolute, prompt = null, fileName = fileName)
                    } else {
                        result += DetailContent.Image(id = absolute, imageUrl = absolute, prompt = null, fileName = fileName)
                    }
                } catch (_: MalformedURLException) { /* ignore */ }
            }
        }

        // thread end time (best-effort; optional)
        val scriptElements = document.select("script")
        for (script in scriptElements) {
            val data = script.data()
            if (data.contains("document.write") && data.contains("contdisp")) {
                val t = Regex("""(\d{2}/\d{2}/\d{2}\([^)]*\)\d{2}:\d{2})""")
                val m = t.find(data)
                val end = m?.groupValues?.getOrNull(1)
                if (!end.isNullOrBlank()) {
                    result += DetailContent.ThreadEndTime(id = "thread_end_time_${textId++}", endTime = end)
                    break
                }
            }
        }

        return result
    }

    private fun archiveMedia(context: Context, threadUrl: String, list: List<DetailContent>): List<DetailContent> {
        val cache = DetailCacheManager(context)
        val dir = cache.getArchiveDirForUrl(threadUrl)
        fun fileFor(url: String): File {
            val ext = url.substringAfterLast('.', "")
            val name = url.sha256() + if (ext.isNotBlank()) ".${ext.lowercase()}" else ""
            return File(dir, name)
        }
        fun ensureDownloaded(remoteUrl: String): String? {
            val f = fileFor(remoteUrl)
            if (f.exists() && f.length() > 0) return f.toURI().toString()
            return try {
                val req = Request.Builder().url(remoteUrl).header("User-Agent", com.valoser.futaburakari.Ua.STRING).build()
                val resp = com.valoser.futaburakari.NetworkModule.okHttpClient.newCall(req).execute()
                resp.use { r ->
                    if (!r.isSuccessful) return null
                    val body = r.body ?: return null
                    f.outputStream().use { out -> body.byteStream().copyTo(out) }
                    f.toURI().toString()
                }
            } catch (_: Exception) {
                null
            }
        }

        return list.map { c ->
            when (c) {
                is DetailContent.Image -> {
                    val local = ensureDownloaded(c.imageUrl)
                    if (local != null) c.copy(imageUrl = local) else c
                }
                is DetailContent.Video -> {
                    val local = ensureDownloaded(c.videoUrl)
                    if (local != null) c.copy(videoUrl = local) else c
                }
                else -> c
            }
        }
    }

    private fun String.sha256(): String {
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }
    }
}
