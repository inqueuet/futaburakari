/**
 * スレ URL を監視してレス内容と媒体をアーカイブする WorkManager 用 Worker。
 * - 定期監視と単発スナップショットの両方を受け付け、常時有効なバックグラウンド監視としてキャッシュ/履歴を更新。
 * - 取得したメディアは可能な限りローカルへ保存し、既存の `prompt` を温存したままマージする。
 */
package com.valoser.futaburakari.worker

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.preference.PreferenceManager
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
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private const val PREF_KEY_BACKGROUND_MEDIA_ARCHIVE = "pref_key_background_media_archive"
private val SAFE_EXTENSION_REGEX = Regex("^[A-Za-z0-9]{1,8}$")

/**
 * 背景でスレ URL を監視し、媒体のアーカイブとキャッシュ/履歴更新を行う Worker。
 *
 * スケジューリング:
 * - URL ごとにユニークな PeriodicWork として監視し、WorkManager 側で 1 件のみ継続実行。
 * - 即時スナップショット取得にも対応（監視設定の有無に関係なく実行）。
 *
 * 主な処理:
 * 1) HTML を取得・パースして Text/Image/Video の直列リストを作成
 * 1.5) 既存のキャッシュ/スナップショットから `prompt` をマージ（null での上書きを防止）
 * 2) 媒体を内部ストレージへ保存し、URL を file:// に差し替え
 * 3) キャッシュ/スナップショットを保存し、履歴（サムネ/最新レス番号）を更新
 *
 * 備考:
 * - 本 Worker 自身は新規のメタデータ抽出は行わず、既存の `prompt` を温存する戦略を採用
 * - プロンプトの抽出/補完は UI 側（`DetailViewModel`）が段階的に行い、保存も併用
 */
@HiltWorker
class ThreadMonitorWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val networkClient: NetworkClient,
    private val cacheManager: DetailCacheManager,
) : CoroutineWorker(appContext, params) {

    /**
     * 監視タスク本体。
     * - スレHTMLを取得・パースし、媒体をアーカイブ（file://へ置換）。
     * - 既存キャッシュ/スナップショットの `prompt` をマージしてから保存（nullでの上書きを防止）。
     * - 履歴（サムネイル/最新レス番号）を更新し、周期実行は WorkManager のスケジュールに委ねて終了。
     * - 404はdat落ちとみなし停止。その他のIOエラー時はポリシーに従い再試行。
     */
    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.success()
        val oneShot = inputData.getBoolean(KEY_ONE_SHOT, false)

        // 常時有効（ユーザー設定での無効化はなし）

        // 履歴に無いスレッドは監視しない（存在しない場合はユニーク作業も停止）
        val key = UrlNormalizer.threadKey(url)
        val historyEntry = try {
            HistoryManager.getAll(applicationContext).firstOrNull { it.key == key }
        } catch (_: Exception) { null }
        if (historyEntry == null) {
            cancelUnique(url)
            return Result.success()
        }
        if (historyEntry.isArchived) {
            cancelUnique(url)
            return Result.success()
        }

        return try {
            val doc: Document = networkClient.fetchDocument(url)
            val exists = doc.selectFirst("div.thre") != null
            if (!exists) {
                // HTML が取得できたがスレ DOM が無い → dat 落ちとみなして停止
                HistoryManager.markArchived(applicationContext, url, autoExpireIfStale = true)
                cancelUnique(url)
                return Result.success()
            }

            // 1) パース（UI 側と同等の簡易ロジック）
            val parsed = parseContentFromDocument(doc, url)

            // 1.5) 既存キャッシュ/スナップショットのプロンプトをマージ（nullでの上書きを防止）
            val cacheMgr = cacheManager
            val existing: List<DetailContent>? = cacheMgr.loadDetails(url) ?: cacheMgr.loadArchiveSnapshot(url)
            val merged = if (existing.isNullOrEmpty()) parsed else run {
                val promptByName: Map<String, String> = existing.mapNotNull { dc ->
                    when (dc) {
                        is DetailContent.Image -> dc.fileName?.let { fn -> dc.prompt?.let { fn to it } }
                        is DetailContent.Video -> dc.fileName?.let { fn -> dc.prompt?.let { fn to it } }
                        else -> null
                    }
                }.toMap()
                parsed.map { dc ->
                    when (dc) {
                        is DetailContent.Image -> {
                            if (!dc.prompt.isNullOrBlank()) dc else {
                                val p = dc.fileName?.let { promptByName[it] }
                                if (p != null) dc.copy(prompt = p) else dc
                            }
                        }
                        is DetailContent.Video -> {
                            if (!dc.prompt.isNullOrBlank()) dc else {
                                val p = dc.fileName?.let { promptByName[it] }
                                if (p != null) dc.copy(prompt = p) else dc
                            }
                        }
                        else -> dc
                    }
                }
            }

            // 2) 自動で媒体を保存するかは利用者設定で制御（既定では保存しない）
            val shouldArchiveMedia = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .getBoolean(PREF_KEY_BACKGROUND_MEDIA_ARCHIVE, false)
            val cachedContent = if (shouldArchiveMedia) {
                archiveMedia(url, merged)
            } else {
                merged
            }

            // 3) キャッシュへ保存（置き換え保存）
            val cm = cacheMgr
            cm.saveDetails(url, cachedContent)

            // 3.5) サムネイル（履歴）をローカルに更新（OPの画像のみを使用）
            try {
                val firstTextIndex = cachedContent.indexOfFirst { it is com.valoser.futaburakari.DetailContent.Text }
                val media = when {
                    firstTextIndex >= 0 -> {
                        // OPレスの直後で次の Text レスが現れるまでの範囲から、実体URLを持つ最初の媒体を採用
                        cachedContent.drop(firstTextIndex + 1)
                            .takeWhile { it !is com.valoser.futaburakari.DetailContent.Text }
                            .firstOrNull {
                                when (it) {
                                    is com.valoser.futaburakari.DetailContent.Image -> it.imageUrl.isNotBlank()
                                    is com.valoser.futaburakari.DetailContent.Video -> it.videoUrl.isNotBlank()
                                    else -> false
                                }
                            }
                    }
                    else -> {
                        // OP本文が検出できなかった場合（画像のみ等）はリスト先頭から最初の媒体を採用
                        cachedContent.firstOrNull {
                            when (it) {
                                is com.valoser.futaburakari.DetailContent.Image -> it.imageUrl.isNotBlank()
                                is com.valoser.futaburakari.DetailContent.Video -> it.videoUrl.isNotBlank()
                                else -> false
                            }
                        }
                    }
                }
                val thumb = when (media) {
                    is com.valoser.futaburakari.DetailContent.Image -> media.imageUrl
                    is com.valoser.futaburakari.DetailContent.Video -> media.thumbnailUrl ?: media.videoUrl
                    else -> null
                }
                if (!thumb.isNullOrBlank()) {
                    HistoryManager.updateThumbnail(applicationContext, url, thumb)
                } else {
                    HistoryManager.clearThumbnail(applicationContext, url)
                }
            } catch (e: Exception) {
                Log.w("ThreadMonitorWorker", "Failed to update thumbnail for $url", e)
            }

            // 4) 既知の最終レス番号（Textの件数）を履歴へ反映（未読数更新のため）
            val latestReplyNo = parsed.asSequence()
                .filterIsInstance<com.valoser.futaburakari.DetailContent.Text>()
                .mapNotNull { it.resNum?.toIntOrNull() }
                .maxOrNull()
                ?: parsed.count { it is com.valoser.futaburakari.DetailContent.Text }
            HistoryManager.applyFetchResult(applicationContext, url, latestReplyNo)

            Result.success()
        } catch (e: OutOfMemoryError) {
            // メモリ不足エラー：システムに任せて失敗を返す
            Log.e("ThreadMonitorWorker", "Out of memory error for $url", e)
            // キャッシュマネージャーに任せてメモリを解放
            runCatching {
                cacheManager.cleanup()
            }
            Result.failure()
        } catch (e: StackOverflowError) {
            // スタックオーバーフロー：再帰処理の問題を示唆、失敗として扱う
            Log.e("ThreadMonitorWorker", "Stack overflow error for $url", e)
            Result.failure()
        } catch (e: java.io.IOException) {
            // fetchDocument は非 200 で IOException を投げることがある
            val msg = e.message ?: ""
            if (msg.contains("HTTPエラー: 404") || msg.contains("404")) {
                // dat 落ち（404）とみなし停止
                Log.i("ThreadMonitorWorker", "Thread archived (404): $url")
                try {
                    HistoryManager.markArchived(applicationContext, url, autoExpireIfStale = true)
                } catch (markError: Exception) {
                    Log.w("ThreadMonitorWorker", "Failed to mark archive state for $url", markError)
                }
                cancelUnique(url)
                Result.success()
            } else {
                // 一時的な失敗は再試行（WorkManagerのバックオフに委任）
                Log.w("ThreadMonitorWorker", "Network error for $url, will retry: ${e.message}")
                Result.retry()
            }
        } catch (e: Exception) {
            // その他の例外：再試行可能とみなすがログに記録
            Log.w("ThreadMonitorWorker", "Unexpected error for $url, will retry: ${e.javaClass.simpleName} - ${e.message}", e)
            Result.retry()
        }
    }

    private fun cancelUnique(url: String) {
        // URL 正規化キーに基づくユニーク名で登録された Work をキャンセル（互換キーも併せてキャンセル）
        val wm = WorkManager.getInstance(applicationContext)
        wm.cancelUniqueWork(uniqueName(url))
        wm.cancelUniqueWork(uniqueNameLegacy(url))
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_ONE_SHOT = "one_shot"
        // 以前のバックグラウンド監視トグル設定は廃止（常時有効）

        private fun uniqueName(url: String): String = "monitor-" + UrlNormalizer.threadKey(url)
        private fun uniqueNameLegacy(url: String): String = "monitor-" + UrlNormalizer.legacyThreadKey(url)
        private fun uniqueNameFromKey(key: String): String = "monitor-" + key

        /**
         * 指定したスレURLの監視を定期的にスケジュールする。
         * - ユニーク名ごとに PeriodicWork を利用し、REPLACE ポリシーで過去のチェーンを置き換える。
         * - 既存のワーカー鎖を延々と積み増さず、WorkManager に1件の定期タスクとして管理させる。
         */
        fun schedule(context: Context, url: String) {
            val data = workDataOf(KEY_URL to url)
            val repeatIntervalMinutes = 15L
            val repeatIntervalMillis = TimeUnit.MINUTES.toMillis(repeatIntervalMinutes)
            // WorkManager の仕様上、初期遅延は繰り返し間隔より短くする必要がある。
            val initialDelayMillis = kotlin.random.Random.nextLong(repeatIntervalMillis)

            val req = PeriodicWorkRequestBuilder<ThreadMonitorWorker>(
                repeatIntervalMinutes, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES  // WorkManager の最小 flex は 5 分
            )
                .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("thread-monitor")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                uniqueName(url),
                ExistingPeriodicWorkPolicy.REPLACE,
                req
            )
        }

        /**
         * 即時に単発のスナップショット取得。
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

        /** サポートされている画像/動画拡張子で終わるURLなら true を返す。 */
        private fun isMediaUrl(href: String): Boolean {
        val lower = href.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".webp") ||
                lower.endsWith(".mp4") || lower.endsWith(".webm")
    }

    /**
     * スレッドHTMLを直列の `DetailContent` リストへ変換する。
     * - 先頭のOPブロック（index 0）と、`.rtd` 周辺の table をたどって各返信ブロックを抽出
     * - 本文テキストはインラインの <img> を除去してHTML文字列として保持
     * - `target=_blank` かつメディア拡張子を指すリンクがあれば、絶対URLに解決した Image/Video を1件追加
     * - `contdisp` を含む script タグを走査してスレ終了時刻をベストエフォートで検出
     */
    private fun parseContentFromDocument(document: Document, baseUrl: String): List<DetailContent> {
        val result = mutableListOf<DetailContent>()

        val threadContainer = document.selectFirst("div.thre") ?: return emptyList()

        // スレッドIDを抽出（DetailViewModelと同じロジック）
        val threadId = baseUrl.substringAfterLast('/').substringBefore(
            ".htm",
            missingDelimiterValue = baseUrl.substringAfterLast('/')
        ).ifBlank {
            baseUrl.hashCode().toUInt().toString(16)
        }

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
                // レス番号を抽出（DetailViewModelと同じロジック）
                val resNum = if (isOp) {
                    threadId
                } else {
                    Regex("""No\.?\s*(\n?\s*)?(\d+)""").find(html)?.groupValues?.getOrNull(2)
                        ?: Regex("""No\.?\s*(\d+)""").find(html)?.groupValues?.getOrNull(1)
                }

                // DetailViewModelと同じID形式
                val stableId = if (isOp) {
                    "text_op_$threadId"
                } else {
                    "text_${resNum ?: "reply_${threadId}_${index}"}"
                }

                result += DetailContent.Text(id = stableId, htmlContent = html, resNum = resNum)
            }

            val a = block.select("a[target=_blank][href]").firstOrNull { el -> isMediaUrl(el.attr("href")) }
            if (a != null) {
                val href = a.attr("href")
                try {
                    val absolute = URL(URL(baseUrl), href).toString()
                    val fileName = absolute.substringAfterLast('/')
                    val lower = href.lowercase()
                    if (lower.endsWith(".mp4") || lower.endsWith(".webm")) {
                        result += DetailContent.Video(
                            id = "video_${absolute.hashCode().toUInt().toString(16)}",
                            videoUrl = absolute,
                            prompt = null,
                            fileName = fileName,
                            thumbnailUrl = null
                        )
                    } else {
                        result += DetailContent.Image(id = "image_${absolute.hashCode().toUInt().toString(16)}", imageUrl = absolute, prompt = null, fileName = fileName)
                    }
                } catch (_: MalformedURLException) { /* ignore */ }
            }
        }

        // スレ終了時刻の検出（ベストエフォート・任意）
        val scriptElements = document.select("script")
        for (script in scriptElements) {
            val data = script.data()
            if (data.contains("document.write") && data.contains("contdisp")) {
                val t = Regex("""(\d{2}/\d{2}/\d{2}\([^)]*\)\d{2}:\d{2})""")
                val m = t.find(data)
                val end = m?.groupValues?.getOrNull(1)
                if (!end.isNullOrBlank()) {
                    result += DetailContent.ThreadEndTime(id = "thread_end_time_${end.hashCode().toUInt().toString(16)}", endTime = end)
                    break
                }
            }
        }

        return result
    }

    /**
     * 媒体をスレッド別のアーカイブディレクトリに保存し、URLをローカル file URI に差し替える。
     * ファイル名は元URLのSHA-256 + 元拡張子（小文字）。既存の非空ファイルがあれば再取得を省略し、
     * ダウンロードに失敗した場合は元のURLを維持する。
     */
    private suspend fun archiveMedia(threadUrl: String, list: List<DetailContent>): List<DetailContent> {
        val dir = cacheManager.getArchiveDirForUrl(threadUrl)
        fun fileFor(url: String): File {
            val sanitizedUrl = url.substringBefore('#').substringBefore('?')
            val extCandidate = sanitizedUrl.substringAfterLast('.', "")
            val ext = extCandidate.takeIf { SAFE_EXTENSION_REGEX.matches(it) }?.lowercase(Locale.ROOT).orEmpty()
            val name = url.sha256() + if (ext.isNotBlank()) ".${ext}" else ""
            return File(dir, name)
        }
        suspend fun ensureDownloaded(remoteUrl: String): File? {
            val f = fileFor(remoteUrl)
            if (f.exists() && f.length() > 0) return f
            return try {
                f.outputStream().buffered(64 * 1024).use { out ->
                    val ok = networkClient.downloadTo(remoteUrl, out, referer = threadUrl)
                    if (!ok) {
                        // ダウンロード失敗時は部分ファイルを確実に削除
                        runCatching {
                            if (f.exists()) {
                                f.delete()
                            }
                        }
                        return null
                    }
                }
                // ダウンロード成功後、ファイルサイズを確認
                if (f.exists() && f.length() > 0) {
                    f
                } else {
                    // ファイルが空または存在しない場合は削除
                    runCatching { f.delete() }
                    null
                }
            } catch (e: Exception) {
                // ダウンロード失敗時は部分ファイルを確実に削除
                Log.w("ThreadMonitorWorker", "Failed to download $remoteUrl: ${e.message}")
                runCatching {
                    if (f.exists()) {
                        f.delete()
                    }
                }
                null
            }
        }

        fun ensureVideoThumbnail(videoFile: File): File? {
            val thumbFile = File(videoFile.parentFile, videoFile.nameWithoutExtension + "_thumb.jpg")
            if (thumbFile.exists() && thumbFile.length() > 0) return thumbFile

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoFile.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val frameTimeUs = when {
                    durationMs == null || durationMs <= 0L -> 1_000_000L
                    else -> (durationMs * 1000L / 2).coerceAtLeast(1_000_000L)
                }
                val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

                val (targetW, targetH) = scaleDimensions(videoWidth, videoHeight, 1280)

                val bitmap = try {
                    when {
                        targetW > 0 && targetH > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 ->
                            retriever.getScaledFrameAtTime(
                                frameTimeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                targetW,
                                targetH
                            )
                        else -> retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                } catch (e: Exception) {
                    Log.w("ThreadMonitorWorker", "Failed to extract frame from ${videoFile.name}: ${e.message}")
                    null
                }

                if (bitmap == null) {
                    runCatching { thumbFile.delete() }
                    return null
                }

                try {
                    thumbFile.outputStream().buffered().use { out ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                            runCatching { thumbFile.delete() }
                            return null
                        }
                    }

                    // 圧縮成功後、ファイルサイズを確認
                    return if (thumbFile.exists() && thumbFile.length() > 0) {
                        thumbFile
                    } else {
                        runCatching { thumbFile.delete() }
                        null
                    }
                } finally {
                    // ビットマップを確実に解放
                    runCatching { bitmap.recycle() }
                }
            } catch (e: Exception) {
                Log.w("ThreadMonitorWorker", "Failed to create thumbnail for ${videoFile.name}: ${e.message}")
                runCatching { thumbFile.delete() }
                return null
            } finally {
                // リトリーバーを確実に解放
                runCatching { retriever.release() }
            }
        }

        fun File.toFileUriString(): String = toURI().toString()

        return list.map { c ->
            when (c) {
                is DetailContent.Image -> {
                    val local = ensureDownloaded(c.imageUrl)
                    if (local != null) c.copy(imageUrl = local.toFileUriString()) else c
                }
                is DetailContent.Video -> {
                    val local = ensureDownloaded(c.videoUrl)
                    if (local != null) {
                        val thumb = ensureVideoThumbnail(local)
                        c.copy(
                            videoUrl = local.toFileUriString(),
                            thumbnailUrl = thumb?.toURI()?.toString() ?: c.thumbnailUrl
                        )
                    } else c
                }
                else -> c
            }
        }
    }

    private fun scaleDimensions(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longest = max(width, height)
        if (longest <= maxDimension) return width to height
        val scale = maxDimension.toDouble() / longest.toDouble()
        val scaledW = (width * scale).roundToInt().coerceAtLeast(1)
        val scaledH = (height * scale).roundToInt().coerceAtLeast(1)
        return scaledW to scaledH
    }

    /** 文字列のSHA-256（16進表現）。アーカイブのファイル名に使用。 */
    private fun String.sha256(): String {
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }
    }
}
