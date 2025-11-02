package com.valoser.futaburakari.videoeditor.data.repository

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.valoser.futaburakari.videoeditor.domain.model.*
import com.valoser.futaburakari.videoeditor.media.thumbnail.ThumbnailGenerator
import com.valoser.futaburakari.videoeditor.media.audio.WaveformGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * メディアリポジトリ
 */
@Singleton
class MediaRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val waveformGenerator: WaveformGenerator
) {
    /**
     * メディア情報を取得
     */
    suspend fun getMediaInfo(uri: Uri): Result<MediaInfo> {
        return withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)

                var duration = 0L
                var width = 0
                var height = 0
                var hasAudio = false
                var frameRate = 30f
                var bitrate = 0

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                    when {
                        mime.startsWith("video/") -> {
                            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                                val trackDuration = format.getLong(MediaFormat.KEY_DURATION) / 1000 // マイクロ秒からミリ秒へ
                                if (trackDuration > 0) {
                                    duration = max(duration, trackDuration)
                                }
                            }
                            if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                                width = format.getInteger(MediaFormat.KEY_WIDTH)
                            }
                            if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                                height = format.getInteger(MediaFormat.KEY_HEIGHT)
                            }
                            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                                frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                            }
                            if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                                bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                            }
                        }
                        mime.startsWith("audio/") -> {
                            hasAudio = true
                            if (duration == 0L && format.containsKey(MediaFormat.KEY_DURATION)) {
                                duration = format.getLong(MediaFormat.KEY_DURATION) / 1000
                            }
                        }
                    }
                }

                Result.success(
                    MediaInfo(
                        duration = duration,
                        width = width,
                        height = height,
                        hasAudio = hasAudio,
                        frameRate = frameRate,
                        bitrate = bitrate
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                // 確実にリソースを解放
                try {
                    extractor.release()
                } catch (e: Exception) {
                    // release時の例外は無視（既に解放されている可能性）
                }
            }
        }
    }

    /**
     * 動画をインポート
     */
    suspend fun importVideo(uri: Uri): Result<VideoClip> {
        return withContext(Dispatchers.IO) {
            try {
                val mediaInfo = getMediaInfo(uri).getOrThrow()

                val clip = VideoClip(
                    id = UUID.randomUUID().toString(),
                    source = uri,
                    startTime = 0L,
                    endTime = mediaInfo.duration,
                    position = 0L,
                    speed = 1f,
                    hasAudio = mediaInfo.hasAudio,
                    audioEnabled = mediaInfo.hasAudio
                )

                Result.success(clip)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 音声をインポート
     */
    suspend fun importAudio(uri: Uri): Result<AudioClip> {
        return withContext(Dispatchers.IO) {
            try {
                val mediaInfo = getMediaInfo(uri).getOrThrow()

                val clip = AudioClip(
                    id = UUID.randomUUID().toString(),
                    source = uri,
                    sourceType = AudioSourceType.MUSIC,
                    startTime = 0L,
                    endTime = mediaInfo.duration,
                    position = 0L,
                    volume = 1f
                )

                Result.success(clip)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * サムネイルを生成
     */
    suspend fun generateThumbnails(clip: VideoClip): Result<List<Thumbnail>> {
        return try {
            thumbnailGenerator.generate(clip)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 波形データを生成
     */
    suspend fun generateWaveform(clip: AudioClip): Result<FloatArray> {
        return try {
            waveformGenerator.generate(clip)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
