package com.valoser.futaburakari.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.OutOfQuotaPolicy
import com.valoser.futaburakari.HistoryManager
import com.valoser.futaburakari.NetworkClient
import com.valoser.futaburakari.UrlNormalizer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit
import java.net.MalformedURLException
import java.net.URL
import com.valoser.futaburakari.DetailContent
import com.valoser.futaburakari.cache.DetailCacheManager
import okhttp3.Request
import java.io.File

/**
 * Background worker that monitors a thread URL, archives media, and updates caches/history.
 *
 * Scheduling model:
 * - Launched as a unique one-time work per URL; upon successful run, reschedules itself when
 *   background monitoring is enabled in preferences.
 * - Also supports an explicit one-shot snapshot that runs immediately regardless of settings.
 */
@HiltWorker
class ThreadMonitorWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val networkClient: NetworkClient,
) : CoroutineWorker(appContext, params) {

    /**
     * Main monitoring task. Fetches the thread page, parses Text/Image/Video entries,
     * saves an archive snapshot and cache, updates history (thumbnail and latest count),
     * and reschedules if applicable. Handles 404 as archived; other IO errors are retried.
     */
    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.success()
        val oneShot = inputData.getBoolean(KEY_ONE_SHOT, false)

        // 設定が無効なら即終了
        val prefs = applicationContext.getSharedPreferences(PREFS_BG, Context.MODE_PRIVATE)
        if (!oneShot) {
            if (!prefs.getBoolean(KEY_BG_ENABLED, false)) {
                return Result.success()
            }
        }

        // 履歴に無いスレッドは監視しない（存在しない場合はユニーク作業も停止）
        val key = UrlNormalizer.threadKey(url)
        val inHistory = try {
            HistoryManager.getAll(applicationContext).any { it.key == key }
        } catch (_: Exception) { false }
        if (!inHistory) {
            cancelUnique(url)
            return Result.success()
        }

        return try {
            val doc: Document = networkClient.fetchDocument(url)
            val exists = doc.selectFirst("div.thre") != null
            if (!exists) {
                // HTMLが取得できたがスレDOMが無い → dat落ちとみなして停止
                HistoryManager.markArchived(applicationContext, url)
                cancelUnique(url)
                return Result.success()
            }

            // 1) パース（UI側と同等の簡易ロジック）
            val parsed = parseContentFromDocument(doc, url)

            // 2) メディアを内部保存し、ローカル file: URI に差し替え
            val archived = archiveMedia(applicationContext, url, parsed)

            // 3) キャッシュへ保存（置き換え保存） + アーカイブスナップショット保存
            val cm = DetailCacheManager(applicationContext)
            cm.saveDetails(url, archived)
            cm.saveArchiveSnapshot(url, archived)

            // 3.5) サムネイル（履歴）をローカルに更新（先頭のメディアを使用）
            runCatching {
                val firstMedia = archived.firstOrNull { it is com.valoser.futaburakari.DetailContent.Image || it is com.valoser.futaburakari.DetailContent.Video }
                val thumb = when (firstMedia) {
                    is com.valoser.futaburakari.DetailContent.Image -> firstMedia.imageUrl
                    is com.valoser.futaburakari.DetailContent.Video -> firstMedia.videoUrl
                    else -> null
                }
                if (!thumb.isNullOrBlank()) {
                    HistoryManager.updateThumbnail(applicationContext, url, thumb)
                }
            }

            // 4) 既知の最終レス番号（Textの件数）を履歴へ反映（未読数更新のため）
            val latestReplyNo = parsed.count { it is com.valoser.futaburakari.DetailContent.Text }
            HistoryManager.applyFetchResult(applicationContext, url, latestReplyNo)

            // 次回スケジュール（通常監視のみ）
            if (!oneShot) schedule(applicationContext, url)
            Result.success()
        } catch (e: java.io.IOException) {
            // fetchDocument は非200で IOException を投げることがある
            val msg = e.message ?: ""
            if (msg.contains("HTTPエラー: 404")) {
                // dat落ち（404）とみなし停止
                HistoryManager.markArchived(applicationContext, url)
                cancelUnique(url)
                Result.success()
            } else {
                // 一時的な失敗は再試行（WorkManagerのバックオフに委任）
                Result.retry()
            }
        } catch (_: Exception) {
            // 想定外のエラーは再試行に委ねる
            Result.retry()
        }
    }

    private fun cancelUnique(url: String) {
        val wm = WorkManager.getInstance(applicationContext)
        wm.cancelUniqueWork(uniqueName(url))
        wm.cancelUniqueWork(uniqueNameLegacy(url))
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_ONE_SHOT = "one_shot"
        const val PREFS_BG = "com.valoser.futaburakari.bg"
        const val KEY_BG_ENABLED = "pref_key_bg_monitor_enabled"

        private fun uniqueName(url: String): String = "monitor-" + UrlNormalizer.threadKey(url)
        private fun uniqueNameLegacy(url: String): String = "monitor-" + UrlNormalizer.legacyThreadKey(url)
        private fun uniqueNameFromKey(key: String): String = "monitor-" + key

        /**
         * Schedule a one-time monitoring run for the given thread URL.
         * - Requires `KEY_BG_ENABLED` in `PREFS_BG` to be true; otherwise, no-op.
         * - Uses a unique work name derived from the normalized thread key and replaces existing work.
         * - Adds a short initial delay and requires CONNECTED network.
         */
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

        /**
         * 即時に単発のスナップショット取得（設定ON/OFFに関係なく実行）。
         * - Expedited リクエスト（クォータ不足時は非Expeditedで実行）。
         * - ユニーク名は `snapshot-<threadKey>` を使用し、既存を置き換える。
         */
        fun snapshotNow(context: Context, url: String) {
            val data = workDataOf(KEY_URL to url, KEY_ONE_SHOT to true)
            val req = OneTimeWorkRequestBuilder<ThreadMonitorWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("thread-monitor")
                .build()
            val unique = "snapshot-" + UrlNormalizer.threadKey(url)
            WorkManager.getInstance(context).enqueueUniqueWork(unique, ExistingWorkPolicy.REPLACE, req)
        }

        /**
         * タグ `thread-monitor` の全Workをキャンセル。
         */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag("thread-monitor")
        }

        /**
         * URLに紐づくユニークWorkをキャンセル（現行キー／互換キーの両方）。
         */
        fun cancelByUrl(context: Context, url: String) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(uniqueName(url))
            wm.cancelUniqueWork(uniqueNameLegacy(url))
        }

        /**
         * 正規化済みスレッドキーからユニークWorkをキャンセル。
         */
        fun cancelByKey(context: Context, key: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueNameFromKey(key))
        }
    }

    /** Returns true if the URL ends with a supported image/video extension. */
    private fun isMediaUrl(href: String): Boolean {
        val lower = href.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".webp") ||
                lower.endsWith(".mp4") || lower.endsWith(".webm")
    }

    /**
     * Parse the thread HTML into a linear list of DetailContent items.
     * - OP block (index 0) plus each reply block extracted via the table around `.rtd`.
     * - Text HTML is captured with inline <img> tags removed.
     * - If a `target=_blank` anchor points to a supported media file, append one Image/Video item
     *   resolved to an absolute URL.
     * - Attempts to detect the thread end time by scanning script tags for `contdisp`.
     */
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

        // Thread end time (best-effort; optional)
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

    /**
     * Download media to the per-thread archive directory and rewrite URLs to local file URIs.
     * Uses SHA-256 of the original URL plus the original extension (lowercased) for file names.
     * Skips downloading if a non-empty file already exists.
     */
    private suspend fun archiveMedia(context: Context, threadUrl: String, list: List<DetailContent>): List<DetailContent> {
        val cache = DetailCacheManager(context)
        val dir = cache.getArchiveDirForUrl(threadUrl)
        fun fileFor(url: String): File {
            val ext = url.substringAfterLast('.', "")
            val name = url.sha256() + if (ext.isNotBlank()) ".${ext.lowercase()}" else ""
            return File(dir, name)
        }
        suspend fun ensureDownloaded(remoteUrl: String): String? {
            val f = fileFor(remoteUrl)
            if (f.exists() && f.length() > 0) return f.toURI().toString()
            return try {
                val bytes = networkClient.fetchBytes(remoteUrl)
                if (bytes == null) return null
                f.outputStream().use { out -> out.write(bytes) }
                f.toURI().toString()
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

    /** Hex-encoded SHA-256 hash of the string (used as filename). */
    private fun String.sha256(): String {
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }
    }
}
