package com.valoser.futaburakari

import android.content.Context
import android.net.Uri
import android.os.Environment
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
import java.io.OutputStream
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
                                    Log.w(TAG, "Failed to download: ${mediaItem.url}")
                                }
                            } catch (e: Exception) {
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

            val successMessage = """
                アーカイブが完了しました
                保存先: ${archiveDir.absolutePath}
                ダウンロード: ${downloadedFiles.size}/${uniqueMediaItems.size}件
            """.trimIndent()
            Log.i(TAG, successMessage)

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
            // アプリ固有の外部ストレージディレクトリを使用（権限不要、ユーザーからアクセス可能）
            // パス例: /storage/emulated/0/Android/data/com.valoser.futaburakari/files/ThreadArchives
            val baseDir = context.getExternalFilesDir(null)
            if (baseDir == null) {
                Log.e(TAG, "External files directory is not available")
                return null
            }

            val appDir = File(baseDir, "ThreadArchives")
            val archiveDir = File(appDir, dirName)

            if (!archiveDir.exists()) {
                val success = archiveDir.mkdirs()
                if (!success) {
                    Log.e(TAG, "Failed to create directory: ${archiveDir.absolutePath}")
                    return null
                }
            }

            Log.d(TAG, "Archive directory created: ${archiveDir.absolutePath}")
            archiveDir
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create archive directory", e)
            null
        }
    }

    /**
     * メディアファイルをダウンロード
     */
    private suspend fun downloadMediaFile(
        url: String,
        fileName: String,
        targetDir: File,
        referer: String? = null
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

            // ネットワークからダウンロード
            val success = targetFile.outputStream().use { output ->
                networkClient.downloadTo(url, output, referer = referer)
            }

            if (success) {
                Log.d(TAG, "Network file downloaded successfully: $fileName (${targetFile.length()} bytes)")
            } else {
                Log.w(TAG, "Network download returned false for: $url")
            }

            success
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
        }
        .post-number {
            color: #0066cc;
            font-weight: bold;
            margin-bottom: 10px;
        }
        .post-content {
            color: #333;
            word-wrap: break-word;
        }
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
        contents.forEach { content ->
            when (content) {
                is DetailContent.Text -> {
                    sb.append("""
        <div class="post">
""")
                    if (!content.resNum.isNullOrBlank()) {
                        sb.append("""            <div class="post-number">No.${escapeHtml(content.resNum)}</div>
""")
                    }
                    sb.append("""            <div class="post-content">${content.htmlContent}</div>
        </div>
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
                        val escapedFullPath = fullPath?.let { escapeHtml(it) }
                        val escapedDisplayPath = escapeHtml(displayPath)
                        sb.append("""
        <div class="media-container">
""")
                        if (escapedFullPath != null) {
                            sb.append("""            <a href="$escapedFullPath" target="_blank">
""")
                            sb.append("""                <img src="$escapedDisplayPath" alt="${escapeHtml(content.prompt ?: "画像")}">
""")
                            sb.append("""            </a>
""")
                        } else {
                            sb.append("""            <img src="$escapedDisplayPath" alt="${escapeHtml(content.prompt ?: "画像")}">
""")
                        }
                        if (!content.prompt.isNullOrBlank()) {
                            sb.append("""            <div class="prompt">${escapeHtml(content.prompt)}</div>
""")
                        }
                        sb.append("""        </div>
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
                            sb.append("""            <div class="prompt">${escapeHtml(content.prompt)}</div>
""")
                        }
                        sb.append("""        </div>
""")
                    }
                }
                is DetailContent.ThreadEndTime -> {
                    sb.append("""
        <div class="thread-end">
            ${escapeHtml(content.endTime)}
        </div>
""")
                }
            }
        }

        // HTMLフッター
        sb.append("""
    </div>
</body>
</html>
""".trimIndent())

        return sb.toString()
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
