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
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeBaseDir/MyApplication/$subfolder")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(mediaContentUri, values)

                if (uri == null) {
                    showToast(context, "ファイルの保存に失敗しました。")
                    return@withContext
                }

                // ファイルをコピーして書き込む（file:// はローカルコピー、http(s) はダウンロード）
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
                        val bytes = networkClient.fetchBytes(url)
                        if (bytes == null) {
                            showToast(context, "ファイルのダウンロードに失敗しました。")
                            resolver.delete(uri, null, null)
                            return@withContext
                        }
                        outputStream.write(bytes)
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

    private fun getMimeTypeOrDefault(url: String, defaultMime: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)
        val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return guessed ?: defaultMime
    }

    private fun imagesContentUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun videosContentUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
    }

    private suspend fun showToast(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
