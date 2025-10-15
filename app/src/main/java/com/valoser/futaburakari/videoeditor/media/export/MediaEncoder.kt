// This file is deprecated and should not be used.
// It contains an older, incorrect pattern for MediaCodec and MediaMuxer usage.
// Refer to ExportPipeline.kt for the correct implementation.
package com.valoser.futaburakari.videoeditor.media.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import com.valoser.futaburakari.videoeditor.domain.model.ExportPreset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * メディアエンコーダー
 * 編集済み動画をエクスポート
 */
@Singleton
class MediaEncoder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 動画をエンコード
     */
    suspend fun encode(
        session: EditorSession,
        preset: ExportPreset,
        outputPath: String,
        onProgress: (Float) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 出力ファイルの準備
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()

            // MediaMuxerの初期化
            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            var videoTrackIndex = -1
            var audioTrackIndex = -1

            // ビデオエンコーダの設定
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                preset.resolution.width,
                preset.resolution.height
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, preset.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, preset.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // オーディオエンコーダの設定
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                48000,
                2
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, preset.audioBitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }

            val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            videoEncoder.start()
            audioEncoder.start()

            // トラックの追加
            videoTrackIndex = muxer.addTrack(videoEncoder.outputFormat)
            audioTrackIndex = muxer.addTrack(audioEncoder.outputFormat)

            muxer.start()

            // エンコード処理
            val totalDuration = session.duration
            var processedDuration = 0L

            // クリップごとに処理
            session.videoClips.forEach { clip ->
                processClip(
                    clip = clip,
                    videoEncoder = videoEncoder,
                    audioEncoder = audioEncoder,
                    muxer = muxer,
                    videoTrackIndex = videoTrackIndex,
                    audioTrackIndex = audioTrackIndex
                )

                processedDuration += clip.duration
                onProgress(processedDuration.toFloat() / totalDuration)
            }

            // クリーンアップ
            videoEncoder.stop()
            videoEncoder.release()
            audioEncoder.stop()
            audioEncoder.release()
            muxer.stop()
            muxer.release()

            Result.success(outputFile.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun processClip(
        clip: com.valoser.futaburakari.videoeditor.domain.model.VideoClip,
        videoEncoder: MediaCodec,
        audioEncoder: MediaCodec,
        muxer: MediaMuxer,
        videoTrackIndex: Int,
        audioTrackIndex: Int
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, clip.source, null)

        // ビデオトラックの処理
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

            when {
                mime.startsWith("video/") -> {
                    extractor.selectTrack(i)
                    processVideoTrack(
                        extractor,
                        videoEncoder,
                        muxer,
                        videoTrackIndex,
                        clip.startTime,
                        clip.endTime,
                        clip.speed
                    )
                    extractor.unselectTrack(i)
                }
                mime.startsWith("audio/") && clip.audioEnabled -> {
                    extractor.selectTrack(i)
                    processAudioTrack(
                        extractor,
                        audioEncoder,
                        muxer,
                        audioTrackIndex,
                        clip.startTime,
                        clip.endTime,
                        clip.speed
                    )
                    extractor.unselectTrack(i)
                }
            }
        }

        extractor.release()
    }

    private fun processVideoTrack(
        extractor: MediaExtractor,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        trackIndex: Int,
        startTime: Long,
        endTime: Long,
        speed: Float
    ) {
        // シンプルな実装: フレームをコピー
        // 実際にはデコード→エンコードが必要
        val bufferInfo = MediaCodec.BufferInfo()

        extractor.seekTo(startTime * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        while (true) {
            val sampleTime = extractor.sampleTime / 1000
            if (sampleTime >= endTime) break

            val inputBufferId = encoder.dequeueInputBuffer(10000)
            if (inputBufferId >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputBufferId)!!
                val sampleSize = extractor.readSampleData(inputBuffer, 0)

                if (sampleSize < 0) {
                    encoder.queueInputBuffer(
                        inputBufferId, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    break
                } else {
                    encoder.queueInputBuffer(
                        inputBufferId, 0, sampleSize,
                        (sampleTime * 1000).toLong(), 0
                    )
                    extractor.advance()
                }
            }

            // 出力バッファの処理
            val outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferId >= 0) {
                val outputBuffer = encoder.getOutputBuffer(outputBufferId)!!

                if (bufferInfo.size > 0) {
                    muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                }

                encoder.releaseOutputBuffer(outputBufferId, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
            }
        }
    }

    private fun processAudioTrack(
        extractor: MediaExtractor,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        trackIndex: Int,
        startTime: Long,
        endTime: Long,
        speed: Float
    ) {
        // ビデオトラックと同様の処理
        val bufferInfo = MediaCodec.BufferInfo()

        extractor.seekTo(startTime * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        while (true) {
            val sampleTime = extractor.sampleTime / 1000
            if (sampleTime >= endTime) break

            val inputBufferId = encoder.dequeueInputBuffer(10000)
            if (inputBufferId >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputBufferId)!!
                val sampleSize = extractor.readSampleData(inputBuffer, 0)

                if (sampleSize < 0) {
                    encoder.queueInputBuffer(
                        inputBufferId, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    break
                } else {
                    encoder.queueInputBuffer(
                        inputBufferId, 0, sampleSize,
                        (sampleTime * 1000).toLong(), 0
                    )
                    extractor.advance()
                }
            }

            val outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferId >= 0) {
                val outputBuffer = encoder.getOutputBuffer(outputBufferId)!!

                if (bufferInfo.size > 0) {
                    muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                }

                encoder.releaseOutputBuffer(outputBufferId, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
            }
        }
    }
}
