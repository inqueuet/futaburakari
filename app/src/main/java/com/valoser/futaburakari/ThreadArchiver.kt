package com.valoser.futaburakari

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val IMAGES_SUBDIR = "images"
private const val THUMBNAILS_SUBDIR = "thumbnails"
private const val VIDEOS_SUBDIR = "videos"

/**
 * スレッド保存の進捗を表すデータクラス
 */
data class ThreadArchiveProgress(
    val current: Int,
    val total: Int,
    val currentFileName: String? = null,
    val isActive: Boolean = true
) {
    val percentage: Int get() = if (total > 0) (current * 100 / total) else 0
}

/**
 * スレッド全体をアーカイブする機能を提供するクラス。
 * 画像・動画・HTMLを一括でダウンロードし、同じディレクトリに保存する。
 * HTMLファイル内のリンクはローカルの画像・動画を参照するように書き換えられる。
 */
class ThreadArchiver(
    private val context: Context,
    private val networkClient: NetworkClient
) {
    private val TAG = "ThreadArchiver"

    /**
     * スレッドをアーカイブする
     * @param threadTitle スレッドのタイトル（ディレクトリ名に使用）
     * @param threadUrl スレッドのURL
     * @param contents スレッドのコンテンツリスト
     * @param onProgress 進捗コールバック
     * @return 成功した場合はtrue
     */
    suspend fun archiveThread(
        threadTitle: String,
        threadUrl: String,
        contents: List<DetailContent>,
        onProgress: (ThreadArchiveProgress) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting thread archive: title='$threadTitle', url='$threadUrl', contents=${contents.size}")

            // URLベースのディレクトリ名を生成（定期スレでも一意になる）
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dirName = generateDirectoryNameFromUrl(threadUrl, timestamp)
            Log.d(TAG, "Archive directory name: $dirName")

            // メディアファイル（画像・動画）を収集
            val mediaItems = mutableListOf<MediaItem>()
            contents.forEach { content ->
                when (content) {
                    is DetailContent.Image -> {
                        if (content.imageUrl.isNotBlank()) {
                            mediaItems.add(
                                MediaItem(
                                    url = content.imageUrl,
                                    type = MediaType.IMAGE,
                                    id = content.id,
                                    subDirectory = IMAGES_SUBDIR,
                                    preferredFileName = content.fileName
                                )
                            )
                            Log.d(TAG, "Collected image: ${content.imageUrl}")
                        }
                        val thumbUrl = content.thumbnailUrl
                        if (!thumbUrl.isNullOrBlank()) {
                            mediaItems.add(
                                MediaItem(
                                    url = thumbUrl,
                                    type = MediaType.IMAGE,
                                    id = "${content.id}_thumb",
                                    subDirectory = THUMBNAILS_SUBDIR,
                                    preferredFileName = thumbUrl.substringAfterLast('/', "")
                                )
                            )
                            Log.d(TAG, "Collected thumbnail: $thumbUrl")
                        }
                    }
                    is DetailContent.Video -> {
                        if (content.videoUrl.isNotBlank()) {
                            mediaItems.add(
                                MediaItem(
                                    url = content.videoUrl,
                                    type = MediaType.VIDEO,
                                    id = content.id,
                                    subDirectory = VIDEOS_SUBDIR,
                                    preferredFileName = content.fileName
                                )
                            )
                            Log.d(TAG, "Collected video: ${content.videoUrl}")
                        }
                        val thumbUrl = content.thumbnailUrl
                        if (!thumbUrl.isNullOrBlank()) {
                            mediaItems.add(
                                MediaItem(
                                    url = thumbUrl,
                                    type = MediaType.IMAGE,
                                    id = "${content.id}_video_thumb",
                                    subDirectory = THUMBNAILS_SUBDIR,
                                    preferredFileName = thumbUrl.substringAfterLast('/', "")
                                )
                            )
                            Log.d(TAG, "Collected video thumbnail: $thumbUrl")
                        }
                    }
                    else -> {} // Text と ThreadEndTime はスキップ
                }
            }
            val uniqueMediaItems = mediaItems.distinctBy { it.url }
            Log.i(TAG, "Total media items collected: ${uniqueMediaItems.size}")

            val totalItems = uniqueMediaItems.size + 1 // +1 はHTMLファイル
            var currentProgress = 0

            // ディレクトリを作成
            val archiveDir = createArchiveDirectory(dirName)
                ?: return@withContext Result.failure(Exception("アーカイブディレクトリの作成に失敗しました"))

            // メディアファイルをダウンロード（4並列）
            val downloadedFiles = mutableMapOf<String, String>() // URL -> ローカル相対パス
            val failedUrls = mutableListOf<String>()
            val semaphore = Semaphore(4)

            coroutineScope {
                uniqueMediaItems.map { mediaItem ->
                    async {
                        semaphore.withPermit {
                            try {
                                val fileName = resolveArchiveFileName(mediaItem)
                                val relativePath = buildRelativePath(mediaItem.subDirectory, fileName)
                                onProgress(
                                    ThreadArchiveProgress(
                                        current = currentProgress,
                                        total = totalItems,
                                        currentFileName = relativePath
                                    )
                                )

                                val targetDir = if (mediaItem.subDirectory.isNotBlank()) {
                                    File(archiveDir, mediaItem.subDirectory)
                                } else {
                                    archiveDir
                                }
                                val success = downloadMediaFile(
                                    url = mediaItem.url,
                                    fileName = fileName,
                                    targetDir = targetDir,
                                    referer = threadUrl
                                )

                                if (success) {
                                    synchronized(downloadedFiles) {
                                        downloadedFiles[mediaItem.url] = relativePath
                                    }
                                    synchronized(this@ThreadArchiver) {
                                        currentProgress++
                                    }
                                    Log.d(TAG, "Downloaded: $relativePath")
                                } else {
                                    synchronized(failedUrls) {
                                        failedUrls.add(mediaItem.url)
                                    }
                                    Log.w(TAG, "Failed to download: ${mediaItem.url}")
                                }
                            } catch (e: Exception) {
                                synchronized(failedUrls) {
                                    failedUrls.add(mediaItem.url)
                                }
                                Log.e(TAG, "Error downloading ${mediaItem.url}", e)
                            }
                        }
                    }
                }.awaitAll()
            }

            // HTMLファイルを生成
            onProgress(
                ThreadArchiveProgress(
                    current = currentProgress,
                    total = totalItems,
                    currentFileName = "index.html"
                )
            )

            val htmlContent = generateHtml(threadTitle, threadUrl, contents, downloadedFiles, archiveDir)
            val htmlFileName = "index.html"
            val htmlSuccess = saveHtmlFile(htmlContent, htmlFileName, archiveDir)

            if (htmlSuccess) {
                currentProgress++
                Log.d(TAG, "HTML file created: $htmlFileName")
            } else {
                Log.w(TAG, "Failed to create HTML file")
            }

            onProgress(
                ThreadArchiveProgress(
                    current = currentProgress,
                    total = totalItems,
                    currentFileName = null,
                    isActive = false
                )
            )

            val successMessage = buildString {
                appendLine("アーカイブが完了しました")
                appendLine("保存先: ${archiveDir.absolutePath}")
                appendLine("成功: ${downloadedFiles.size}件")
                if (failedUrls.isNotEmpty()) {
                    appendLine("失敗: ${failedUrls.size}件")
                    appendLine("※ 失敗したファイルはサーバーから削除された可能性があります")
                }
                appendLine("HTML: ${if (htmlSuccess) "作成済み" else "失敗"}")
            }.trimEnd()

            Log.i(TAG, successMessage)
            if (failedUrls.isNotEmpty()) {
                Log.w(TAG, "Failed URLs (${failedUrls.size}):")
                failedUrls.take(10).forEach { url ->
                    Log.w(TAG, "  - $url")
                }
                if (failedUrls.size > 10) {
                    Log.w(TAG, "  ... and ${failedUrls.size - 10} more")
                }
            }

            Result.success(successMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Archive failed", e)
            Result.failure(e)
        }
    }

    /**
     * アーカイブ用のディレクトリを作成
     * Android 10以降はアプリ固有のディレクトリを使用（権限不要）
     */
    private fun createArchiveDirectory(dirName: String): File? {
        return try {
            val dir = ArchiveStorageResolver.ensureArchiveDirectory(
                context,
                dirName,
                ArchiveStorageResolver.ArchiveScope.USER_EXPORT
            )
            if (dir != null) {
                Log.d(TAG, "Archive directory resolved: ${dir.absolutePath}")
            } else {
                Log.e(TAG, "Failed to resolve archive directory for $dirName")
            }
            dir
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create archive directory", e)
            null
        }
    }

    /**
     * メディアファイルをダウンロード（リトライ機能付き）
     */
    private suspend fun downloadMediaFile(
        url: String,
        fileName: String,
        targetDir: File,
        referer: String? = null,
        maxRetries: Int = 3
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!targetDir.exists()) {
                val created = targetDir.mkdirs()
                if (!created && !targetDir.exists()) {
                    Log.e(TAG, "Failed to create directory: ${targetDir.absolutePath}")
                    return@withContext false
                }
            }
            val targetFile = File(targetDir, fileName)
            Log.d(TAG, "Starting download: $url -> ${targetFile.absolutePath}")

            // ローカルファイルの場合はコピー
            if (url.startsWith("file://") || url.startsWith("content://")) {
                val uri = Uri.parse(url)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Local file copied successfully: $fileName")
                return@withContext true
            }

            // ネットワークからダウンロード（リトライ付き）
            var lastException: Exception? = null
            repeat(maxRetries) { attempt ->
                try {
                    val success = targetFile.outputStream().use { output ->
                        networkClient.downloadTo(url, output, referer = referer)
                    }

                    if (success && targetFile.exists() && targetFile.length() > 0) {
                        Log.d(TAG, "Network file downloaded successfully: $fileName (${targetFile.length()} bytes)")
                        return@withContext true
                    } else {
                        Log.w(TAG, "Download attempt ${attempt + 1}/$maxRetries failed for: $url (file size: ${targetFile.length()})")
                        // 空ファイルが作成された場合は削除
                        if (targetFile.exists() && targetFile.length() == 0L) {
                            targetFile.delete()
                        }
                    }
                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "Download attempt ${attempt + 1}/$maxRetries error for: $url", e)
                    // 次のリトライ前に少し待機
                    if (attempt < maxRetries - 1) {
                        kotlinx.coroutines.delay(1000L * (attempt + 1))
                    }
                }
            }

            Log.e(TAG, "Failed to download after $maxRetries attempts: $url", lastException)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download media file: $url", e)
            false
        }
    }

    /**
     * HTMLファイルを保存
     */
    private suspend fun saveHtmlFile(
        content: String,
        fileName: String,
        archiveDir: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val htmlFile = File(archiveDir, fileName)
            htmlFile.writeText(content, Charsets.UTF_8)
            Log.d(TAG, "HTML file saved successfully: ${htmlFile.absolutePath} (${htmlFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save HTML file: ${archiveDir.absolutePath}/$fileName", e)
            false
        }
    }

    /**
     * HTML文書を生成
     */
    private fun generateHtml(
        threadTitle: String,
        threadUrl: String,
        contents: List<DetailContent>,
        downloadedFiles: Map<String, String>,
        archiveDir: File
    ): String {
        Log.d(TAG, "Generating HTML with ${downloadedFiles.size} downloaded files")
        downloadedFiles.forEach { (url, fileName) ->
            Log.d(TAG, "  Mapping: $url -> $fileName")
        }

        val sb = StringBuilder()

        // HTMLヘッダー
        sb.append("""
<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${escapeHtml(threadTitle)}</title>
    <style>
        body {
            font-family: 'Hiragino Kaku Gothic ProN', 'ヒラギノ角ゴ ProN W3', Meiryo, メイリオ, sans-serif;
            max-width: 1200px;
            margin: 0 auto;
            padding: 20px;
            background-color: #f5f5f5;
            line-height: 1.6;
        }
        .header {
            background-color: #fff;
            padding: 20px;
            margin-bottom: 20px;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        .header h1 {
            margin: 0 0 10px 0;
            color: #333;
        }
        .header .source-url {
            color: #666;
            font-size: 14px;
        }
        .post {
            background-color: #fff;
            margin-bottom: 15px;
            padding: 15px;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            position: relative;
        }
        .post::after {
            content: "";
            display: block;
            clear: both;
        }
        .post-number {
            color: #0066cc;
            font-weight: bold;
            margin-bottom: 10px;
        }
        .post-content {
            color: #333;
            /* テキストは <p>/<br> に整形してから流し込むため normal */
            white-space: normal;
            word-break: break-word;
            overflow-wrap: anywhere;
        }
        .post-content p {
            margin: 0 0 0.8em 0;
        }
        .post-content dl {
            margin: 0 0 0.8em 0 !important;
            padding: 0 !important;
            display: block;
        }
        .post-content dt {
            margin: 0 0 0.4em 0 !important;
            font-weight: 600;
            display: block;
        }
        .post-content dd {
            margin: 0 !important;
            padding: 0 !important;
            display: block;
        }
        .post-content *[style*="margin-left"] {
            margin-left: 0 !important;
        }
        .post-content *[style*="padding-left"] {
            padding-left: 0 !important;
        }
        .post-content *[style*="float"] {
            float: none !important;
        }
        .post-content blockquote {
            margin: 0.6em 0;
            padding: 0.6em 0.8em;
            border-left: 4px solid #d0d7de;
            background: #fafbfc;
            color: #333;
        }
        .post-content details.long-quote {
            margin: 0.6em 0;
            background: #fafbfc;
            border-left: 4px solid #d0d7de;
            padding: 0.2em 0.8em 0.6em 0.8em;
        }
        .post-content details.long-quote > summary {
            cursor: pointer;
            list-style: none;
            font-weight: 600;
            padding: 0.4em 0;
        }
        .post-content details.long-quote > summary::-webkit-details-marker { display: none; }
        .post-content details.long-quote[open] > summary { opacity: 0.8; }
        .media-container {
            margin: 10px 0;
        }
        .media-container img {
            max-width: 100%;
            height: auto;
            border-radius: 4px;
        }
        .media-container video {
            max-width: 100%;
            height: auto;
            border-radius: 4px;
        }
        .prompt {
            color: #666;
            font-size: 14px;
            font-style: italic;
            margin-top: 5px;
        }
        .thread-end {
            text-align: center;
            color: #999;
            padding: 20px;
            font-size: 14px;
        }
        a {
            color: #0066cc;
            text-decoration: none;
        }
        a:hover {
            text-decoration: underline;
        }
    </style>
</head>
<body>
    <div class="header">
        <h1>${escapeHtml(threadTitle)}</h1>
        <div class="source-url">元URL: <a href="${escapeHtml(threadUrl)}" target="_blank">${escapeHtml(threadUrl)}</a></div>
        <div class="archive-date">保存日時: ${SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.JAPAN).format(Date())}</div>
    </div>
    <div class="content">
""".trimIndent())

        // コンテンツを順番に出力
        var postOpen = false

        fun closePostIfOpen() {
            if (postOpen) {
                sb.append("        </div>\n")
                postOpen = false
            }
        }

        fun ensurePostOpen() {
            if (!postOpen) {
                sb.append("""
        <div class="post">
""")
                postOpen = true
            }
        }

        contents.forEach { content ->
            when (content) {
                is DetailContent.Text -> {
                    closePostIfOpen()
                    ensurePostOpen()
                    if (!content.resNum.isNullOrBlank()) {
                        sb.append("""            <div class="post-number">No.${escapeHtml(content.resNum)}</div>
""")
                    }
                    val processedHtmlContent = replaceLinksWithLocalPaths(content.htmlContent, downloadedFiles, archiveDir)
                    // 改行・<br> の段落化、および長大 blockquote の折りたたみ整形を適用
                    val normalizedHtml = formatParagraphsAndQuotes(processedHtmlContent)
                    sb.append("""            <div class="post-content">$normalizedHtml</div>
""")
                }
                is DetailContent.Image -> {
                    val fullPath = resolveLocalRelativePath(
                        url = content.imageUrl,
                        type = MediaType.IMAGE,
                        explicitFileName = content.fileName,
                        archiveDir = archiveDir,
                        downloadedFiles = downloadedFiles,
                        defaultSubDirs = listOf(IMAGES_SUBDIR, "")
                    )
                    val thumbFileNameHint = content.thumbnailUrl?.substringAfterLast('/', "")
                    val thumbPath = resolveLocalRelativePath(
                        url = content.thumbnailUrl,
                        type = MediaType.IMAGE,
                        explicitFileName = thumbFileNameHint,
                        archiveDir = archiveDir,
                        downloadedFiles = downloadedFiles,
                        defaultSubDirs = listOf(THUMBNAILS_SUBDIR, IMAGES_SUBDIR, "")
                    )
                    val displayPath = thumbPath ?: fullPath
                    if (displayPath != null) {
                        ensurePostOpen()
                        val escapedFullPath = fullPath?.let { escapeHtml(it) }
                        val escapedDisplayPath = escapeHtml(displayPath)
                        sb.append("""
            <div class="media-container">
""")
                        if (escapedFullPath != null) {
                            sb.append("""                <a href="$escapedFullPath" target="_blank">
""")
                            sb.append("""                    <img src="$escapedDisplayPath" alt="${escapeHtml(content.prompt ?: "画像")}">
""")
                            sb.append("""                </a>
""")
                        } else {
                            sb.append("""                <img src="$escapedDisplayPath" alt="${escapeHtml(content.prompt ?: "画像")}">
""")
                        }
                        if (!content.prompt.isNullOrBlank()) {
                            sb.append("""                <div class="prompt">${escapeHtml(content.prompt)}</div>
""")
                        }
                        sb.append("""            </div>
""")
                    }
                }
                is DetailContent.Video -> {
                    val localFileName = resolveLocalRelativePath(
                        url = content.videoUrl,
                        type = MediaType.VIDEO,
                        explicitFileName = content.fileName,
                        archiveDir = archiveDir,
                        downloadedFiles = downloadedFiles,
                        defaultSubDirs = listOf(VIDEOS_SUBDIR, "")
                    )
                    val posterFileNameHint = content.thumbnailUrl?.substringAfterLast('/', "")
                    val posterPath = resolveLocalRelativePath(
                        url = content.thumbnailUrl,
                        type = MediaType.IMAGE,
                        explicitFileName = posterFileNameHint,
                        archiveDir = archiveDir,
                        downloadedFiles = downloadedFiles,
                        defaultSubDirs = listOf(THUMBNAILS_SUBDIR, IMAGES_SUBDIR, "")
                    )
                    if (localFileName != null) {
                        ensurePostOpen()
                        val posterAttr = posterPath?.let { """ poster="${escapeHtml(it)}"""" } ?: ""
                        val mimeType = when {
                            localFileName.endsWith(".webm", ignoreCase = true) -> "video/webm"
                            localFileName.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
                            localFileName.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
                            localFileName.endsWith(".m4v", ignoreCase = true) -> "video/x-m4v"
                            localFileName.endsWith(".3gp", ignoreCase = true) -> "video/3gpp"
                            else -> "video/mp4"
                        }
                        sb.append("""
            <div class="media-container">
                <video controls$posterAttr>
                    <source src="${escapeHtml(localFileName)}" type="$mimeType">
                    お使いのブラウザは動画タグをサポートしていません。
                </video>
""")
                        if (!content.prompt.isNullOrBlank()) {
                            sb.append("""                <div class="prompt">${escapeHtml(content.prompt)}</div>
""")
                        }
                        sb.append("""            </div>
""")
                    }
                }
                is DetailContent.ThreadEndTime -> {
                    closePostIfOpen()
                    sb.append("""
        <div class="thread-end">
            ${escapeHtml(content.endTime)}
        </div>
""")
                }
            }
        }

        closePostIfOpen()

        // HTMLフッター
        sb.append("""
    </div>
</body>
</html>
""".trimIndent())

        return sb.toString()
    }

    /**
     * 本文HTMLの整形:
     *  - テキストノードのみ段落分割（空行 / 連続 <br> を段落境界に）
     *  - 各段落内の単独改行のみ <br> 化（段落間に <br> は挿入しない）
     *  - 既存のブロック要素はそのまま保持（<p> 内に入れない）
     *  - 長大な <blockquote> は <details class="long-quote"> で折りたたみ
     */
    private fun formatParagraphsAndQuotes(rawHtml: String): String {
        if (rawHtml.isBlank()) return rawHtml

        // ブロック要素として扱うタグ
        val blockPattern = Regex(
            pattern = "(?is)" +
                    "(" +
                    // 開閉タグを持つブロック
                    "<(?:blockquote|details|figure|div|ul|ol|li|dl|dt|dd|pre|table|thead|tbody|tr|td|th|h[1-6]|p)\\b[^>]*>.*?</\\s*(?:blockquote|details|figure|div|ul|ol|li|dl|dt|dd|pre|table|thead|tbody|tr|td|th|h[1-6]|p)\\s*>" +
                    "|" +
                    // 単独タグ（hr等）
                    "<(?:hr)\\b[^>]*/?>" +
                    ")"
        )

        // テキストとブロック要素に分割
        val parts = mutableListOf<Pair<Boolean, String>>() // (isBlock, content)
        var idx = 0
        blockPattern.findAll(rawHtml).forEach { m ->
            if (m.range.first > idx) {
                parts += false to rawHtml.substring(idx, m.range.first) // テキスト
            }
            parts += true to m.value // ブロック
            idx = m.range.last + 1
        }
        if (idx < rawHtml.length) {
            parts += false to rawHtml.substring(idx)
        }

        fun isLongQuote(html: String): Boolean {
            val text = html.replace(Regex("(?is)<.*?>"), "")
            val brCount = Regex("(?i)<br\\s*/?>").findAll(html).count()
            return text.length >= 400 || brCount >= 6
        }
        fun wrapLongQuoteIfNeeded(html: String): String {
            return if (Regex("(?is)^\\s*<blockquote\\b").containsMatchIn(html) && isLongQuote(html)) {
                """<details class="long-quote"><summary>長文の引用を開く</summary>$html</details>"""
            } else html
        }

        // テキスト → 段落整形
        fun textToParagraphs(t: String): String {
            if (t.isBlank()) return ""
            var s = t.replace("\r\n", "\n")

            // 連続 <br> を段落デリミタへ正規化（後で split する）
            s = s.replace(Regex("(?i)(?:\\s*<br\\s*/?>\\s*){2,}"), "\n\n")

            // 空行（\n\n+）で段落分割
            val chunks = s.trim().split(Regex("\\n{2,}"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            // 各段落内の単独改行のみ <br> に置換して <p> で包む
            return chunks.joinToString(separator = "") { para ->
                val inner = para.replace(Regex("\\n"), "<br>")
                "<p>$inner</p>"
            }
        }

        // 再構築
        val out = StringBuilder()
        for ((isBlock, content) in parts) {
            if (isBlock) {
                out.append(wrapLongQuoteIfNeeded(content))
            } else {
                out.append(textToParagraphs(content))
            }
        }
        return out.toString()
    }

    /**
     * HTMLで参照するローカルファイルの相対パスを解決する。
     * - まずダウンロードマップから取得
     * - 見つからない場合は候補ディレクトリを順番に探索
     */
    private fun resolveLocalRelativePath(
        url: String?,
        type: MediaType,
        explicitFileName: String?,
        archiveDir: File,
        downloadedFiles: Map<String, String>,
        defaultSubDirs: List<String>
    ): String? {
        if (url.isNullOrBlank()) return null

        downloadedFiles[url]?.let { return it }

        val candidateFileNames = buildList {
            explicitFileName
                ?.takeIf { it.isNotBlank() }
                ?.let { add(sanitizeFileName(it)) }
            add(generateFileName(url, type))
        }.distinct()

        val candidateDirs = (defaultSubDirs + "").distinct()
        for (fileName in candidateFileNames) {
            for (dir in candidateDirs) {
                val relativePath = if (dir.isBlank()) fileName else "$dir/$fileName"
                val file = File(archiveDir, relativePath)
                if (file.exists()) {
                    return relativePath
                }
            }
        }

        return null
    }

    /**
     * 保存時に使用するファイル名を決定する。
     * 優先的に元ファイル名を利用し、無い場合はURLから生成。
     */
    private fun resolveArchiveFileName(mediaItem: MediaItem): String {
        mediaItem.preferredFileName
            ?.takeIf { it.isNotBlank() }
            ?.let { return sanitizeFileName(it) }
        return generateFileName(mediaItem.url, mediaItem.type)
    }

    /**
     * サブディレクトリとファイル名からアーカイブ内の相対パスを組み立てる。
     */
    private fun buildRelativePath(subDirectory: String, fileName: String): String {
        return if (subDirectory.isBlank()) fileName else "$subDirectory/$fileName"
    }

    /**
     * ファイル名をサニタイズ
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    /**
     * URLからディレクトリ名を生成
     * 例: https://img.2chan.net/b/res/1234567890.htm -> b_1234567890_20250131_123456
     */
    private fun generateDirectoryNameFromUrl(url: String, timestamp: String): String {
        return try {
            val urlObj = URL(url)
            val pathParts = urlObj.path.split("/").filter { it.isNotBlank() }

            // パスから板名とスレッドIDを抽出
            // 例: /b/res/1234567890.htm -> [b, res, 1234567890.htm]
            val boardName = if (pathParts.isNotEmpty()) pathParts[0] else "unknown"
            val threadId = if (pathParts.size >= 3) {
                // 1234567890.htm から拡張子を除去して 1234567890 を取得
                pathParts[2].substringBeforeLast('.')
            } else {
                // スレッドIDが取得できない場合はURLのハッシュ値を使用
                url.hashCode().toString(16)
            }

            "${boardName}_${threadId}_${timestamp}"
        } catch (e: Exception) {
            // URLのパースに失敗した場合はURLのハッシュ値とタイムスタンプを使用
            Log.w(TAG, "Failed to parse URL for directory name: $url", e)
            "thread_${url.hashCode().toString(16)}_${timestamp}"
        }
    }

    /**
     * URLからファイル名を生成
     */
    private fun generateFileName(url: String, type: MediaType): String {
        return try {
            val urlObj = URL(url)
            val path = urlObj.path
            val fileName = path.substringAfterLast('/')

            if (fileName.isNotBlank() && fileName.contains('.')) {
                sanitizeFileName(fileName)
            } else {
                // ファイル名が取得できない場合はハッシュとタイプから生成
                val hash = url.hashCode().toString(16)
                val extension = when (type) {
                    MediaType.IMAGE -> "jpg"
                    MediaType.VIDEO -> "mp4"
                }
                "${hash}.${extension}"
            }
        } catch (e: Exception) {
            // URLのパースに失敗した場合
            val hash = url.hashCode().toString(16)
            val extension = when (type) {
                MediaType.IMAGE -> "jpg"
                MediaType.VIDEO -> "mp4"
            }
            "${hash}.${extension}"
        }
    }

    /**
     * HTML特殊文字をエスケープ
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /**
     * HTMLコンテンツ内のリモートリンクをローカルパスに置き換える
     */
    private fun replaceLinksWithLocalPaths(
        htmlContent: String,
        downloadedFiles: Map<String, String>,
        archiveDir: File
    ): String {
        var result = htmlContent

        // downloadedFilesのキー（元URL）から置き換えマップを作成
        downloadedFiles.forEach { (originalUrl, localPath) ->
            try {
                val url = URL(originalUrl)
                val path = url.path // 例: "/b/src/1761893219711.png"

                // HTMLコンテンツ内でこのパスを参照している箇所を置き換え
                // href="/b/src/1761893219711.png" -> href="images/1761893219711.png"
                // src="/b/src/1761893219711.png" -> src="images/1761893219711.png"
                result = result.replace("href=\"$path\"", "href=\"$localPath\"")
                result = result.replace("src=\"$path\"", "src=\"$localPath\"")
                result = result.replace("href='$path'", "href='$localPath'")
                result = result.replace("src='$path'", "src='$localPath'")

                Log.d(TAG, "Replaced link in HTML: $path -> $localPath")
            } catch (e: Exception) {
                // URLのパースに失敗した場合はスキップ
                Log.w(TAG, "Failed to parse URL for link replacement: $originalUrl", e)
            }
        }

        return result
    }

    /**
     * メディアアイテム
     */
    private data class MediaItem(
        val url: String,
        val type: MediaType,
        val id: String,
        val subDirectory: String,
        val preferredFileName: String? = null
    )

    /**
     * メディアタイプ
     */
    private enum class MediaType {
        IMAGE,
        VIDEO
    }
}
