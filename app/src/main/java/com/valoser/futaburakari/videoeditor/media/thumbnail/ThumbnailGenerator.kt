package com.valoser.futaburakari.videoeditor.media.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.valoser.futaburakari.videoeditor.domain.model.Thumbnail
import com.valoser.futaburakari.videoeditor.domain.model.VideoClip
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * サムネイル生成クラス
 * 大型フィルムストリップ用の高解像度サムネイルを生成
 */
@Singleton
class ThumbnailGenerator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // 大型フィルムストリップ用の高解像度サムネイル
        private const val THUMBNAIL_WIDTH = 256  // @3x density対応
        private const val THUMBNAIL_HEIGHT = 144 // 16:9アスペクト比
        private const val DEFAULT_INTERVAL = 500L // 0.5秒間隔
        private const val WEBP_QUALITY = 85 // 高品質
    }

    /**
     * サムネイルを生成
     */
    suspend fun generate(
        clip: VideoClip,
        interval: Long = DEFAULT_INTERVAL
    ): Result<List<Thumbnail>> = withContext(Dispatchers.IO) {
        try {
            val thumbnails = mutableListOf<Thumbnail>()
            var currentTime = clip.startTime
            var index = 0

            val retriever = MediaMetadataRetriever()

            try {
                context.contentResolver.openFileDescriptor(clip.source, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: throw IllegalArgumentException("Cannot open file descriptor for ${clip.source}")

                while (currentTime < clip.endTime) {
                    // 高解像度でフレーム抽出
                    val bitmap = retriever.getFrameAtTime(
                        currentTime * 1000, // マイクロ秒に変換
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )

                    bitmap?.let {
                        // 高品質リサイズ
                        val scaledBitmap = Bitmap.createScaledBitmap(
                            it,
                            THUMBNAIL_WIDTH,
                            THUMBNAIL_HEIGHT,
                            true // 高品質フィルタリング
                        )

                        // WebPで保存（品質85）
                        val file = getThumbnailFile(clip.id, index)
                        file.parentFile?.mkdirs()

                        FileOutputStream(file).use { out ->
                            scaledBitmap.compress(
                                Bitmap.CompressFormat.WEBP_LOSSY,
                                WEBP_QUALITY,
                                out
                            )
                        }

                        thumbnails.add(
                            Thumbnail(
                                time = currentTime,
                                path = file.absolutePath
                            )
                        )

                        scaledBitmap.recycle()
                        it.recycle()
                    }

                    currentTime += interval
                    index++
                }
            } finally {
                retriever.release()
            }

            Result.success(thumbnails)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * サムネイルファイルのパスを取得
     */
    private fun getThumbnailFile(clipId: String, index: Int): File {
        val dir = File(context.cacheDir, "thumbnails")
        return File(dir, "${clipId}_${String.format("%03d", index)}.webp")
    }

    /**
     * サムネイルキャッシュをクリア
     */
    fun clearCache() {
        val dir = File(context.cacheDir, "thumbnails")
        dir.deleteRecursively()
    }

    /**
     * 特定のクリップのサムネイルをクリア
     */
    fun clearClipCache(clipId: String) {
        val dir = File(context.cacheDir, "thumbnails")
        dir.listFiles()?.filter { it.name.startsWith(clipId) }?.forEach { it.delete() }
    }
}
