package com.valoser.futaburakari.videoeditor.export

import android.content.Context
import android.media.*
import android.net.Uri
import android.util.Log
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import com.valoser.futaburakari.videoeditor.domain.model.ExportPreset
import com.valoser.futaburakari.videoeditor.domain.model.ExportProgress
import com.valoser.futaburakari.videoeditor.domain.model.VideoClip
import com.valoser.futaburakari.videoeditor.utils.findTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

interface ExportPipeline {
    fun export(session: EditorSession, preset: ExportPreset, outputUri: Uri): Flow<ExportProgress>
}

class ExportPipelineImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ExportPipeline {

    private val TIMEOUT_US = 10000L
    private val TAG = "ExportPipeline"

    override fun export(
        session: EditorSession,
        preset: ExportPreset,
        outputUri: Uri
    ): Flow<ExportProgress> = flow {
        val pfd = context.contentResolver.openFileDescriptor(outputUri, "w")
            ?: throw IOException("Failed to open file descriptor for $outputUri")

        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex: Int
        var audioTrackIndex: Int
        var muxerStarted = false
        val muxerLock = Any()

        val totalDurationUs = session.videoClips.sumOf { (it.duration / it.speed).toLong() } * 1000
        val totalFrames = (totalDurationUs / 1_000_000f * preset.frameRate).toInt()
        val framesProcessed = java.util.concurrent.atomic.AtomicInteger(0)

        val videoEncoder = VideoProcessor.createEncoder(preset)
        val audioEncoder = AudioProcessor.createEncoder(preset)

        try {
            // Video Processing
            val videoSurface = videoEncoder.createInputSurface()
            videoEncoder.start()
            videoTrackIndex = muxer.addTrack(videoEncoder.outputFormat)

            // Audio Processing
            audioEncoder?.start()
            audioTrackIndex = audioEncoder?.let { muxer.addTrack(it.outputFormat) } ?: -1

            synchronized(muxerLock) {
                muxer.start()
                muxerStarted = true
            }

            val videoProcessor = VideoProcessor(context, videoEncoder, muxer, videoTrackIndex, muxerLock)
            val audioProcessor = AudioProcessor(context, audioEncoder, muxer, audioTrackIndex, muxerLock, session)

            var videoPresentationTimeOffsetUs = 0L
            var audioPresentationTimeOffsetUs = 0L

            for (clip in session.videoClips) {
                // Process Video
                val videoResult = withContext(Dispatchers.IO) {
                    videoProcessor.processClip(clip, videoSurface, videoPresentationTimeOffsetUs) { progress ->
                        val currentFrames = framesProcessed.addAndGet(progress)
                        val percentage = (currentFrames.toFloat() / totalFrames).coerceIn(0f, 100f) * 100f
                        emit(ExportProgress(currentFrames, totalFrames, percentage, 0))
                    }
                }
                videoPresentationTimeOffsetUs += videoResult.durationUs

                // Process Audio
                val audioResult = withContext(Dispatchers.IO) {
                    audioProcessor.processClip(clip, audioPresentationTimeOffsetUs)
                }
                audioPresentationTimeOffsetUs += audioResult.durationUs
            }

            videoEncoder.signalEndOfInputStream()
            videoProcessor.drainEncoder(true)
            audioEncoder?.signalEndOfInputStream()
            audioProcessor.drainEncoder(true)

        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            throw e
        } finally {
            videoEncoder.stop(); videoEncoder.release()
            audioEncoder?.stop(); audioEncoder?.release()
            if (muxerStarted) {
                try {
                    muxer.stop()
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Muxer stop failed", e)
                }
            }
            muxer.release()
            pfd.close()
            Log.d(TAG, "Export finished.")
            emit(ExportProgress(totalFrames, totalFrames, 100f, 0))
        }
    }
}

private class VideoProcessor(
    private val context: Context,
    private val encoder: MediaCodec,
    private val muxer: MediaMuxer,
    private val trackIndex: Int,
    private val muxerLock: Any,
    private val TIMEOUT_US: Long = 10000L
) {
    private val TAG = "VideoProcessor"
    data class Result(val durationUs: Long)

    suspend fun processClip(
        clip: VideoClip,
        surface: android.view.Surface,
        presentationTimeOffsetUs: Long,
        onProgress: suspend (Int) -> Unit
    ): Result {
        val extractor = MediaExtractor().apply { setDataSource(context, clip.source, null) }
        val decoder = createDecoder(extractor, surface)
            ?: throw RuntimeException("Failed to create video decoder for ${clip.source}")

        var framesInClip = 0
        try {
            decoder.start()
            extractor.selectTrack(extractor.findTrack("video/") ?: -1)
            extractor.seekTo(clip.startTime * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputDone = false
            var isOutputDone = false

            while (!isOutputDone) {
                if (!isInputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val sampleTime = extractor.sampleTime
                        if (sampleTime == -1L || sampleTime >= clip.endTime * 1000) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputDone = true
                        } else {
                            val sampleSize = extractor.readSampleData(decoder.getInputBuffer(inputBufferIndex)!!, 0)
                            if (sampleSize < 0) {
                                isInputDone = true
                            } else {
                                val presentationTimeUs = ((sampleTime - clip.startTime * 1000) / clip.speed).toLong()
                                decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                var decoderOutputAvailable = true
                while (decoderOutputAvailable) {
                    val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outputBufferIndex >= 0) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputDone = true
                        }
                        val doRender = bufferInfo.size != 0
                        bufferInfo.presentationTimeUs += presentationTimeOffsetUs
                        decoder.releaseOutputBuffer(outputBufferIndex, doRender)
                        if (doRender) {
                            drainEncoder(false)
                            framesInClip++
                        }
                    } else {
                        decoderOutputAvailable = false
                    }
                }
            }
            onProgress(framesInClip)
        } finally {
            decoder.stop(); decoder.release()
            extractor.release()
        }
        return Result(durationUs = ((clip.duration / clip.speed) * 1000).toLong())
    }

    fun drainEncoder(endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        if (endOfStream) encoder.signalEndOfInputStream()
        while (true) {
            val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (encoderStatus >= 0) {
                val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                if (bufferInfo.size != 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    synchronized(muxerLock) { muxer.writeSampleData(trackIndex, encodedData, bufferInfo) }
                }
                encoder.releaseOutputBuffer(encoderStatus, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    companion object {
        fun createEncoder(preset: ExportPreset): MediaCodec {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, preset.width, preset.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, preset.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, preset.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            return MediaCodec.createEncoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }

        fun createDecoder(extractor: MediaExtractor, surface: android.view.Surface): MediaCodec? {
            val trackIndex = extractor.findTrack("video/") ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            return MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(format, surface, null, 0)
            }
        }
    }
}

private class AudioProcessor(
    private val context: Context,
    private val encoder: MediaCodec?,
    private val muxer: MediaMuxer,
    private val trackIndex: Int,
    private val muxerLock: Any,
    private val session: EditorSession,
    private val TIMEOUT_US: Long = 10000L
) {
    private val TAG = "AudioProcessor"
    data class Result(val durationUs: Long)

    suspend fun processClip(clip: VideoClip, presentationTimeOffsetUs: Long): Result {
        if (encoder == null || trackIndex == -1 || !clip.hasAudio || !clip.audioEnabled) {
            return Result(durationUs = ((clip.duration / clip.speed) * 1000).toLong())
        }

        val audioClip = session.audioTracks.flatMap { it.clips }.find { it.source == clip.source && it.startTime == clip.startTime }
        val extractor = MediaExtractor().apply { setDataSource(context, clip.source, null) }
        val decoder = createDecoder(extractor) ?: return Result(durationUs = ((clip.duration / clip.speed) * 1000).toLong()).also { extractor.release() }

        try {
            val audioFormat = decoder.outputFormat
            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            decoder.start()
            extractor.selectTrack(extractor.findTrack("audio/") ?: -1)
            extractor.seekTo(clip.startTime * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputDone = false
            var isOutputDone = false

            while (!isOutputDone) {
                if (!isInputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val sampleTime = extractor.sampleTime
                        if (sampleTime == -1L || sampleTime >= clip.endTime * 1000) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputDone = true
                        } else {
                            val sampleSize = extractor.readSampleData(decoder.getInputBuffer(inputBufferIndex)!!, 0)
                            if (sampleSize < 0) {
                                isInputDone = true
                            } else {
                                val presentationTimeUs = ((sampleTime - clip.startTime * 1000) / clip.speed).toLong()
                                decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                var decoderOutputAvailable = true
                while (decoderOutputAvailable) {
                    val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outputBufferIndex >= 0) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) isOutputDone = true
                        if (bufferInfo.size > 0) {
                            val decodedData = decoder.getOutputBuffer(outputBufferIndex)!!
                            applyVolumeAutomation(decodedData, bufferInfo, audioClip, sampleRate, channelCount)
                            bufferInfo.presentationTimeUs += presentationTimeOffsetUs
                            drainEncoder(false, decodedData, bufferInfo)
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                        if (isOutputDone) break
                    } else {
                        decoderOutputAvailable = false
                    }
                }
            }
        } finally {
            decoder.stop(); decoder.release()
            extractor.release()
        }
        return Result(durationUs = ((clip.duration / clip.speed) * 1000).toLong())
    }

    private fun applyVolumeAutomation(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        clip: com.valoser.futaburakari.videoeditor.domain.model.AudioClip?,
        sampleRate: Int,
        channelCount: Int
    ) {
        if (clip == null || clip.volumeKeyframes.isEmpty()) return

        val shortBuffer = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val numSamples = info.size / 2 / channelCount

        for (i in 0 until numSamples) {
            val sampleTimeUs = info.presentationTimeUs + (i.toLong() * 1_000_000 / sampleRate)
            val sampleTimeMs = sampleTimeUs / 1000

            val volume = getVolumeAtTime(sampleTimeMs, clip)

            for (c in 0 until channelCount) {
                val index = i * channelCount + c
                val sample = shortBuffer.get(index)
                val newSample = (sample * volume).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                shortBuffer.put(index, newSample)
            }
        }
    }

    private fun getVolumeAtTime(timeMs: Long, clip: com.valoser.futaburakari.videoeditor.domain.model.AudioClip): Float {
        val keyframes = clip.volumeKeyframes.sortedBy { it.time }
        if (keyframes.isEmpty()) return clip.volume

        val nextKeyframeIndex = keyframes.indexOfFirst { it.time >= timeMs }

        return when {
            nextKeyframeIndex == -1 -> keyframes.last().value // After last keyframe
            nextKeyframeIndex == 0 -> keyframes.first().value // Before first keyframe
            else -> { // Between two keyframes
                val prev = keyframes[nextKeyframeIndex - 1]
                val next = keyframes[nextKeyframeIndex]
                val fraction = (timeMs - prev.time).toFloat() / (next.time - prev.time)
                // Linear interpolation
                prev.value + fraction * (next.value - prev.value)
            }
        }
    }

    fun drainEncoder(endOfStream: Boolean, data: ByteBuffer? = null, info: MediaCodec.BufferInfo? = null) {
        if (encoder == null) return
        if (endOfStream) encoder.signalEndOfInputStream()
        if (data != null && info != null) {
            val encoderInputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (encoderInputIndex >= 0) {
                val encoderInputBuffer = encoder.getInputBuffer(encoderInputIndex)!!
                encoderInputBuffer.put(data)
                encoder.queueInputBuffer(encoderInputIndex, 0, info.size, info.presentationTimeUs, info.flags)
            }
        }
        while (true) {
            val encoderStatus = encoder.dequeueOutputBuffer(info ?: MediaCodec.BufferInfo(), TIMEOUT_US)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (encoderStatus >= 0) {
                val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                if (info != null && info.size != 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    synchronized(muxerLock) { muxer.writeSampleData(trackIndex, encodedData, info) }
                }
                encoder.releaseOutputBuffer(encoderStatus, false)
                if (info != null && (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    companion object {
        fun createEncoder(preset: ExportPreset): MediaCodec? {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, preset.audioSampleRate, preset.audioChannels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, preset.audioBitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            return try {
                MediaCodec.createEncoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            } catch (e: IOException) {
                Log.e("AudioProcessor", "Failed to create audio encoder", e)
                null
            }
        }

        fun createDecoder(extractor: MediaExtractor): MediaCodec? {
            val trackIndex = extractor.findTrack("audio/") ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            return try {
                MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                    configure(format, null, null, 0)
                }
            } catch (e: IOException) {
                Log.e("AudioProcessor", "Failed to create audio decoder", e)
                null
            }
        }
    }
}