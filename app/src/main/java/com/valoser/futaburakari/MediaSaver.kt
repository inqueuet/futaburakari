package com.valoser.futaburakari

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
 

/**
 * 画像/動画をMediaStoreへ保存するユーティリティ。
 *
 * - Android 10以降は `RELATIVE_PATH` + `IS_PENDING` を利用してブランドフォルダ（`Futaburakari`）配下へ保存
 * - Android 9以前は `MediaColumns.DATA` によるパス指定で同フォルダを作成して保存
 * - `file://`/`content://` はローカルコピー、`http(s)://` は `NetworkClient` でダウンロード
 * - 拡張子からMIMEを推測（不明時は既定値）
 * - 成否はトーストで通知
 */
object MediaSaver {

    data class ExistingMedia(
        val url: String,
        val fileName: String,
        val contentUri: Uri
    )

    /**
     * 指定した画像URLを MediaStore（Pictures/Futaburakari）へ保存する。
     * URL が `content://`/`file://` の場合はローカルコピー、`http(s)://` はキャッシュ優先でダウンロードして保存する。
     */
    suspend fun saveImage(context: Context, imageUrl: String, networkClient: NetworkClient) {
        saveMedia(
            context = context,
            url = imageUrl,
            subfolder = "Images",
            mimeType = getMimeTypeOrDefault(imageUrl, "image/jpeg"),
            mediaContentUri = imagesContentUri(),
            relativeBaseDir = Environment.DIRECTORY_PICTURES,
            networkClient = networkClient
        )
    }

    /**
     * 指定した画像URLを MediaStore（Pictures/Futaburakari）へ保存する（重複チェック付き）。
     * 既に同名ファイルが存在する場合や保存に失敗した場合は false を返し、保存に成功した場合のみ true を返す。
     */
    suspend fun saveImageIfNotExists(context: Context, imageUrl: String, networkClient: NetworkClient): Boolean {
        return saveMediaIfNotExists(
            context = context,
            url = imageUrl,
            subfolder = "Images",
            mimeType = getMimeTypeOrDefault(imageUrl, "image/jpeg"),
            mediaContentUri = imagesContentUri(),
            relativeBaseDir = Environment.DIRECTORY_PICTURES,
            networkClient = networkClient
        )
    }

    suspend fun findExistingImages(
        context: Context,
        imageUrls: List<String>
    ): Map<String, List<ExistingMedia>> {
        return findExistingMedia(
            context = context,
            urls = imageUrls,
            mediaContentUri = imagesContentUri(),
            relativeBaseDir = Environment.DIRECTORY_PICTURES
        )
    }

    suspend fun deleteMedia(context: Context, entries: List<ExistingMedia>) {
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            entries.forEach { entry ->
                try {
                    resolver.delete(entry.contentUri, null, null)
                } catch (e: Exception) {
                    android.util.Log.w("MediaSaver", "Failed to delete existing media: ${entry.contentUri}", e)
                }
            }
        }
    }

    suspend fun deleteMedia(context: Context, entry: ExistingMedia) {
        deleteMedia(context, listOf(entry))
    }

    /**
     * 指定した動画URLを MediaStore（Movies/Futaburakari）へ保存する。
     * URL が `content://`/`file://` の場合はローカルコピー、`http(s)://` はダウンロードして保存する。
     */
    suspend fun saveVideo(context: Context, videoUrl: String, networkClient: NetworkClient) {
        saveMedia(
            context = context,
            url = videoUrl,
            subfolder = "Videos",
            mimeType = getMimeTypeOrDefault(videoUrl, "video/mp4"),
            mediaContentUri = videosContentUri(),
            relativeBaseDir = Environment.DIRECTORY_MOVIES,
            networkClient = networkClient
        )
    }

    /**
     * ファイルが既に存在するかをチェックする。
     */
    private suspend fun isFileExists(
        context: Context,
        fileName: String,
        mediaContentUri: android.net.Uri,
        relativeBaseDir: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            } else {
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA)
            }
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            } else {
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.DATA} LIKE ?"
            }
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(fileName, "$relativeBaseDir/Futaburakari/")
            } else {
                arrayOf(fileName, "%/Futaburakari/%")
            }

            resolver.query(mediaContentUri, projection, selection, selectionArgs, null)?.use { cursor ->
                cursor.count > 0
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    // 共通の保存処理（画像/動画）。呼び出し元で種別に応じたURIとベースディレクトリを指定する。
    // subfolder はレガシー互換の名残で、実際の保存先は Futaburakari フォルダ配下で固定。
    private suspend fun saveMedia(
        context: Context,
        url: String,
        subfolder: String,
        mimeType: String?,
        mediaContentUri: android.net.Uri,
        relativeBaseDir: String,
        networkClient: NetworkClient
    ) {
        withContext(Dispatchers.IO) {
            try {
                val fileName = url.substring(url.lastIndexOf('/') + 1)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    mimeType?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // ImageEditActivity と同じブランドフォルダ配下に保存（RELATIVE_PATH）
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeBaseDir/Futaburakari")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    } else {
                        // Pre-Q: DATA で保存先パスを直接指定。必要ならブランドフォルダを作成。
                        @Suppress("DEPRECATION")
                        val base = Environment.getExternalStoragePublicDirectory(relativeBaseDir)
                        val brandDir = java.io.File(base, "Futaburakari")
                        if (!brandDir.exists()) brandDir.mkdirs()
                        val outFile = java.io.File(brandDir, fileName)
                        put(MediaStore.MediaColumns.DATA, outFile.absolutePath)
                    }
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(mediaContentUri, values)

                if (uri == null) {
                    showToast(context, "ファイルの保存に失敗しました。")
                    return@withContext
                }

                // 実体を書き出す：file/content はローカルコピー、http(s) はキャッシュ優先でダウンロード
                var downloadSuccess = false
                try {
                    resolver.openOutputStream(uri).use { outputStream ->
                        if (outputStream == null) {
                            showToast(context, "ファイルの保存に失敗しました。")
                            return@withContext
                        }
                        if (url.startsWith("file://") || url.startsWith("content://")) {
                            val src = context.contentResolver.openInputStream(Uri.parse(url))
                            if (src == null) {
                                showToast(context, "ファイルの読み込みに失敗しました。")
                                return@withContext
                            }
                            src.use { input ->
                                input.copyTo(outputStream, bufferSize = 64 * 1024) // 64KBバッファでストリーミング
                            }
                        } else {
                            // NetworkClientを使用（OkHttpキャッシュが効く）
                            val ok = networkClient.downloadTo(url, outputStream)
                            if (!ok) {
                                showToast(context, "ファイルのダウンロードに失敗しました。")
                                return@withContext
                            }
                        }
                        downloadSuccess = true
                    }
                } catch (e: Exception) {
                    showToast(context, "ファイルの保存中にエラーが発生しました: ${e.message}")
                    return@withContext
                } finally {
                    if (!downloadSuccess) {
                        // 失敗時のクリーンアップ
                        try {
                            resolver.delete(uri, null, null)
                        } catch (e: Exception) {
                            // 削除失敗は無視（既に削除されている可能性）
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }

                showToast(context, "ファイルを保存しました: $fileName")

            } catch (e: Exception) {
                // プロダクション環境では詳細なエラー情報をログに記録し、ユーザーには安全なメッセージを表示
                android.util.Log.e("MediaSaver", "Failed to save media", e)
                val userMessage = when (e) {
                    is java.io.IOException -> "ファイルの保存に失敗しました。ストレージの空き容量を確認してください。"
                    is SecurityException -> "ファイルの保存に必要な権限がありません。"
                    else -> "保存中にエラーが発生しました。"
                }
                showToast(context, userMessage)
            }
        }
    }

    // 重複チェック付きの共通保存処理（画像/動画）。既存ファイルがある場合や保存に失敗した場合は false を返す。
    // subfolder 引数は互換目的で維持されているのみで、保存先は Futaburakari フォルダ固定。
    private suspend fun saveMediaIfNotExists(
        context: Context,
        url: String,
        subfolder: String,
        mimeType: String?,
        mediaContentUri: android.net.Uri,
        relativeBaseDir: String,
        networkClient: NetworkClient
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = url.substring(url.lastIndexOf('/') + 1)

            // 既存ファイルをチェック
            if (isFileExists(context, fileName, mediaContentUri, relativeBaseDir)) {
                // 既に存在する場合はスキップ
                return@withContext false
            }

            // 存在しない場合は通常の保存処理を実行
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                mimeType?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeBaseDir/Futaburakari")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val base = Environment.getExternalStoragePublicDirectory(relativeBaseDir)
                    val brandDir = java.io.File(base, "Futaburakari")
                    if (!brandDir.exists()) brandDir.mkdirs()
                    val outFile = java.io.File(brandDir, fileName)
                    put(MediaStore.MediaColumns.DATA, outFile.absolutePath)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(mediaContentUri, values)

            if (uri == null) {
                showToast(context, "ファイルの保存に失敗しました。")
                return@withContext false
            }

            // 実体を書き出す
            var downloadSuccess = false
            try {
                resolver.openOutputStream(uri).use { outputStream ->
                    if (outputStream == null) {
                        showToast(context, "ファイルの保存に失敗しました。")
                        return@withContext false
                    }
                    if (url.startsWith("file://") || url.startsWith("content://")) {
                        val src = context.contentResolver.openInputStream(Uri.parse(url))
                        if (src == null) {
                            showToast(context, "ファイルの読み込みに失敗しました。")
                            return@withContext false
                        }
                        src.use { input ->
                            input.copyTo(outputStream, bufferSize = 64 * 1024)
                        }
                    } else {
                        // NetworkClientを使用（OkHttpキャッシュが効く）
                        val ok = networkClient.downloadTo(url, outputStream)
                        if (!ok) {
                            showToast(context, "ファイルのダウンロードに失敗しました。")
                            return@withContext false
                        }
                    }
                    downloadSuccess = true
                }
            } catch (e: Exception) {
                showToast(context, "ファイルの保存中にエラーが発生しました: ${e.message}")
                return@withContext false
            } finally {
                if (!downloadSuccess) {
                    try {
                        resolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        // 削除失敗は無視
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            showToast(context, "ファイルを保存しました: $fileName")
            return@withContext true

        } catch (e: Exception) {
            android.util.Log.e("MediaSaver", "Failed to save media", e)
            val userMessage = when (e) {
                is java.io.IOException -> "ファイルの保存に失敗しました。ストレージの空き容量を確認してください。"
                is SecurityException -> "ファイルの保存に必要な権限がありません。"
                else -> "保存中にエラーが発生しました。"
            }
            showToast(context, userMessage)
            return@withContext false
        }
    }

    private suspend fun findExistingMedia(
        context: Context,
        urls: List<String>,
        mediaContentUri: Uri,
        relativeBaseDir: String
    ): Map<String, List<ExistingMedia>> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val result = mutableMapOf<String, MutableList<ExistingMedia>>()
        val projectionQ = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        @Suppress("DEPRECATION")
        val projectionLegacy = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA
        )

        urls.forEach { url ->
            val fileName = url.substringAfterLast('/')
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) projectionQ else projectionLegacy
            try {
                resolver.query(
                    mediaContentUri,
                    projection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(fileName),
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val pathMatches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val relIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                            val relPath = cursor.getString(relIndex)
                            val expectedRelative = "$relativeBaseDir/Futaburakari"
                            relPath?.contains("Futaburakari") == true || relPath?.contains(expectedRelative) == true
                        } else {
                            val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                            val dataPath = cursor.getString(dataIndex)
                            dataPath?.contains("/Futaburakari/") == true
                        }

                        if (!pathMatches) continue

                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: fileName
                        val existing = ExistingMedia(
                            url = url,
                            fileName = displayName,
                            contentUri = ContentUris.withAppendedId(mediaContentUri, id)
                        )
                        val list = result.getOrPut(url) { mutableListOf() }
                        list += existing
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MediaSaver", "Failed to query existing media for $fileName", e)
            }
        }

        result
    }

    // 拡張子からMIME Typeを推測し、取得できなければ既定値を返す
    private fun getMimeTypeOrDefault(url: String, defaultMime: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)
        val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return guessed ?: defaultMime
    }

    // 画像用の MediaStore コンテンツURI（Q以降はプライマリ外部ストレージのボリュームを指定）
    private fun imagesContentUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }

    // 動画用の MediaStore コンテンツURI（Q以降はプライマリ外部ストレージのボリュームを指定）
    private fun videosContentUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
    }

    // Toast 表示は Main ディスパッチャで行う
    private suspend fun showToast(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
