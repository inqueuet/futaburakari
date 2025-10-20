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
        private const val THUMBNAIL_TARGET_HEIGHT = 144 // 高さベースでスケール
        private const val THUMBNAIL_MAX_WIDTH = 384      // 横長動画の上限幅
        private const val DEFAULT_INTERVAL = THUMBNAIL_BASE_INTERVAL_MS // 0.1秒間隔
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
            val step = interval.coerceAtLeast(1L)

            val retriever = MediaMetadataRetriever()

            try {
                context.contentResolver.openFileDescriptor(clip.source, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: throw IllegalArgumentException("Cannot open file descriptor for ${clip.source}")

                while (currentTime < clip.endTime) {
                    // 高解像度でフレーム抽出
                    val bitmap = retriever.getFrameAtTime(
                        currentTime * 1000, // マイクロ秒に変換
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )

                    bitmap?.let {
                        // 高品質リサイズ（アスペクト比保持）
                        val aspectRatio = if (it.height != 0) {
                            it.width.toFloat() / it.height.toFloat()
                        } else {
                            1f
                        }
                        val targetHeight = THUMBNAIL_TARGET_HEIGHT
                        val targetWidth = (targetHeight * aspectRatio)
                            .toInt()
                            .coerceAtLeast(1)
                            .coerceAtMost(THUMBNAIL_MAX_WIDTH)
                        val scaledBitmap = Bitmap.createScaledBitmap(
                            it,
                            targetWidth,
                            targetHeight,
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

                    currentTime += step
                    index++
                }

                // 末尾付近の不足を補うため、終端直前でもう1フレーム確保する
                if (clip.endTime > clip.startTime) {
                    val finalSampleTime = (clip.endTime - 1).coerceAtLeast(clip.startTime)
                    val hasFinalThumbnail = thumbnails.any { it.time == finalSampleTime }
                    if (!hasFinalThumbnail) {
                        val bitmap = retriever.getFrameAtTime(
                            finalSampleTime * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST
                        )

                        bitmap?.let {
                            val aspectRatio = if (it.height != 0) {
                                it.width.toFloat() / it.height.toFloat()
                            } else {
                                1f
                            }
                            val targetHeight = THUMBNAIL_TARGET_HEIGHT
                            val targetWidth = (targetHeight * aspectRatio)
                                .toInt()
                                .coerceAtLeast(1)
                                .coerceAtMost(THUMBNAIL_MAX_WIDTH)
                            val scaledBitmap = Bitmap.createScaledBitmap(
                                it,
                                targetWidth,
                                targetHeight,
                                true
                            )

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
                                    time = finalSampleTime,
                                    path = file.absolutePath
                                )
                            )

                            scaledBitmap.recycle()
                            it.recycle()

                            index++
                        }
                    }
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
