/**
 * スレ URL を監視してレス内容と媒体をアーカイブする WorkManager 用 Worker。
 * - 定期監視と単発スナップショットの両方を受け付け、常時有効なバックグラウンド監視としてキャッシュ/履歴を更新。
 * - 取得したメディアはローカルへ保存し、既存の `prompt` を温存したままマージする。
 */
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
 * 背景でスレ URL を監視し、媒体のアーカイブとキャッシュ/履歴更新を行う Worker。
 *
 * スケジューリング:
 * - URL ごとにユニークな OneTimeWork として起動し、完了後は one-shot でない限り自動で再スケジュール。
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
     * - 履歴（サムネイル/最新レス番号）を更新し、必要に応じて再スケジュール。
     * - 404はdat落ちとみなし停止。その他のIOエラー時はポリシーに従い再試行。
     */
    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.success()
        val oneShot = inputData.getBoolean(KEY_ONE_SHOT, false)

        // 常時有効（ユーザー設定での無効化はなし）

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
                // HTML が取得できたがスレ DOM が無い → dat 落ちとみなして停止
                HistoryManager.markArchived(applicationContext, url)
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

            // 2) メディアを内部保存し、ローカル file: URI に差し替え（マージ後のリストを使用）
            val archived = archiveMedia(url, merged)

            // 3) キャッシュへ保存（置き換え保存） + アーカイブスナップショット保存
            val cm = cacheMgr
            cm.saveDetails(url, archived)
            cm.saveArchiveSnapshot(url, archived)

            // 3.5) サムネイル（履歴）をローカルに更新（OPの画像のみを使用）
            runCatching {
                val firstTextIndex = archived.indexOfFirst { it is com.valoser.futaburakari.DetailContent.Text }
                val media = if (firstTextIndex >= 0) {
                    // OPレスの直後の画像/動画を探す（次のTextレスが現れるまで）
                    // 空のURLを持つプレースホルダー画像は除外
                    archived.drop(firstTextIndex + 1).takeWhile { it !is com.valoser.futaburakari.DetailContent.Text }
                        .firstOrNull {
                            when (it) {
                                is com.valoser.futaburakari.DetailContent.Image -> it.imageUrl.isNotBlank()
                                is com.valoser.futaburakari.DetailContent.Video -> it.videoUrl.isNotBlank()
                                else -> false
                            }
                        }
                } else null
                val opResNum = (archived.getOrNull(firstTextIndex) as? com.valoser.futaburakari.DetailContent.Text)?.resNum
                val mediaId = when (media) {
                    is com.valoser.futaburakari.DetailContent.Image -> media.id
                    is com.valoser.futaburakari.DetailContent.Video -> media.id
                    else -> null
                }
                // OPレス番号とメディアIDの末尾が一致する場合のみ OP のサムネとみなす
                val isOpMedia = if (!opResNum.isNullOrBlank() && !mediaId.isNullOrBlank()) {
                    mediaId.endsWith("#$opResNum")
                } else media != null
                val thumb = if (isOpMedia) when (media) {
                    is com.valoser.futaburakari.DetailContent.Image -> media.imageUrl
                    is com.valoser.futaburakari.DetailContent.Video -> media.videoUrl
                    else -> null
                } else null
                if (!thumb.isNullOrBlank()) {
                    HistoryManager.updateThumbnail(applicationContext, url, thumb)
                } else {
                    HistoryManager.clearThumbnail(applicationContext, url)
                }
            }

            // 4) 既知の最終レス番号（Textの件数）を履歴へ反映（未読数更新のため）
            val latestReplyNo = parsed.count { it is com.valoser.futaburakari.DetailContent.Text }
            HistoryManager.applyFetchResult(applicationContext, url, latestReplyNo)

            // 次回スケジュール（通常監視のみ）
            if (!oneShot) schedule(applicationContext, url)
            Result.success()
        } catch (e: java.io.IOException) {
            // fetchDocument は非 200 で IOException を投げることがある
            val msg = e.message ?: ""
            if (msg.contains("HTTPエラー: 404")) {
                // dat 落ち（404）とみなし停止
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
         * 指定したスレURLの監視を一回分スケジュールする。
         * - 正規化したスレッドキーからユニーク名を生成し、既存の同名Workを置き換える。
         * - 短い初期待機を設定し、ネットワーク接続（CONNECTED）を要求する。
         */
        fun schedule(context: Context, url: String) {
            val data = workDataOf(KEY_URL to url)
            // 基本1分 + 小さなジッターで実行時刻を分散（波状実行を軽減）
            val delayMillis = 60_000L + kotlin.random.Random.nextLong(0L, 30_000L)
            val req = OneTimeWorkRequestBuilder<ThreadMonitorWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("thread-monitor")
                .build()

            // 連続監視はユニークチェーンに順次追加し、実行中の Work を中断しない
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(url),
                ExistingWorkPolicy.APPEND,
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

        // スレ終了時刻の検出（ベストエフォート・任意）
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
     * 媒体をスレッド別のアーカイブディレクトリに保存し、URLをローカル file URI に差し替える。
     * ファイル名は元URLのSHA-256 + 元拡張子（小文字）。既存の非空ファイルがあれば再取得を省略。
     */
    private suspend fun archiveMedia(threadUrl: String, list: List<DetailContent>): List<DetailContent> {
        val dir = cacheManager.getArchiveDirForUrl(threadUrl)
        fun fileFor(url: String): File {
            val ext = url.substringAfterLast('.', "")
            val name = url.sha256() + if (ext.isNotBlank()) ".${ext.lowercase()}" else ""
            return File(dir, name)
        }
        suspend fun ensureDownloaded(remoteUrl: String): String? {
            val f = fileFor(remoteUrl)
            if (f.exists() && f.length() > 0) return f.toURI().toString()
            return try {
                f.outputStream().buffered(64 * 1024).use { out ->
                    val ok = networkClient.downloadTo(remoteUrl, out)
                    if (!ok) return null
                }
                f.toURI().toString()
            } catch (_: Exception) {
                // ダウンロード失敗時は部分ファイルを削除
                if (f.exists()) {
                    f.delete()
                }
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

    /** 文字列のSHA-256（16進表現）。アーカイブのファイル名に使用。 */
    private fun String.sha256(): String {
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }
    }
}
