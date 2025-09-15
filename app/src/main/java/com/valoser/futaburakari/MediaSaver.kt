package com.valoser.futaburakari

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

    // 共通の保存処理（画像/動画）。呼び出し元で種別に応じたURIとベースディレクトリを指定する。
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

                // 実体を書き出す：file/content はローカルコピー、http(s) はダウンロード
                resolver.openOutputStream(uri).use { outputStream ->
                    if (outputStream == null) {
                        showToast(context, "ファイルの保存に失敗しました。")
                        return@withContext
                    }
                    if (url.startsWith("file://") || url.startsWith("content://")) {
                        val src = context.contentResolver.openInputStream(Uri.parse(url))
                        if (src == null) {
                            showToast(context, "ファイルの読み込みに失敗しました。")
                            resolver.delete(uri, null, null)
                            return@withContext
                        }
                        src.use { input -> input.copyTo(outputStream) }
                    } else {
                        val ok = networkClient.downloadTo(url, outputStream)
                        if (!ok) {
                            showToast(context, "ファイルのダウンロードに失敗しました。")
                            resolver.delete(uri, null, null)
                            return@withContext
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
                e.printStackTrace()
                showToast(context, "エラーが発生しました: ${e.message}")
            }
        }
    }

    // 拡張子からMIME Typeを推測し、取得できなければ既定値を返す
    private fun getMimeTypeOrDefault(url: String, defaultMime: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)
        val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return guessed ?: defaultMime
    }

    // 画像用のMediaStoreコンテンツURI（Q以降はプライマリ外部ストレージのボリュームを指定）
    private fun imagesContentUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }

    // 動画用のMediaStoreコンテンツURI（Q以降はプライマリ外部ストレージのボリュームを指定）
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
