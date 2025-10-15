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
        Log.d(TAG, "=== Export Started ===")
        Log.d(TAG, "Session has ${session.videoClips.size} video clips")
        session.videoClips.forEachIndexed { index, clip ->
            Log.d(TAG, "Clip $index: duration=${clip.duration}ms, speed=${clip.speed}, position=${clip.position}ms")
            Log.d(TAG, "Clip $index: startTime=${clip.startTime}ms, endTime=${clip.endTime}ms, source=${clip.source}")
        }
        Log.d(TAG, "Preset: ${preset.width}x${preset.height} @ ${preset.frameRate}fps")

        val pfd = context.contentResolver.openFileDescriptor(outputUri, "w")
            ?: throw IOException("Failed to open file descriptor for $outputUri")

        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex: Int
        var audioTrackIndex: Int
        var muxerStarted = false
        val muxerLock = Any()

        val totalDurationUs = session.videoClips.sumOf { (it.duration / it.speed).toLong() } * 1000
        Log.d(TAG, "Total duration: ${totalDurationUs}us (${totalDurationUs / 1000}ms)")
        val totalFrames = (totalDurationUs / 1_000_000f * preset.frameRate).toInt()
        Log.d(TAG, "Total frames to export: $totalFrames")
        val framesProcessed = java.util.concurrent.atomic.AtomicInteger(0)

        val videoEncoder = VideoProcessor.createEncoder(preset)
        val audioEncoder = AudioProcessor.createEncoder(preset)

        try {
            // Video Processing - エンコーダーを設定してSurfaceを作成
            Log.d(TAG, "Creating video encoder input surface...")
            val videoSurface = videoEncoder.createInputSurface()
            Log.d(TAG, "Starting video encoder...")
            videoEncoder.start()

            // Audio Processing
            audioEncoder?.start()

            Log.d(TAG, "Creating video and audio processors...")
            val videoProcessor = VideoProcessor(context, videoEncoder, muxer, muxerLock)
            val audioProcessor = AudioProcessor(context, audioEncoder, muxer, muxerLock, session)

            var videoPresentationTimeOffsetUs = 0L
            var audioPresentationTimeOffsetUs = 0L

            for ((clipIndex, clip) in session.videoClips.withIndex()) {
                // Process Video
                val videoResult = withContext(Dispatchers.IO) {
                    videoProcessor.processClip(clip, videoSurface, videoPresentationTimeOffsetUs) { progress ->
                        val currentFrames = framesProcessed.addAndGet(progress)
                        val percentage = (currentFrames.toFloat() / totalFrames).coerceIn(0f, 100f) * 100f
                        emit(ExportProgress(currentFrames, totalFrames, percentage, 0))
                    }
                }
                videoPresentationTimeOffsetUs += videoResult.durationUs

                if (!muxerStarted && videoProcessor.getTrackIndex() >= 0 &&
                    (audioEncoder == null || audioProcessor.getTrackIndex() >= 0)) {
                    synchronized(muxerLock) {
                        if (!muxerStarted) {
                            muxer.start()
                            muxerStarted = true
                            videoProcessor.setMuxerStarted(true)
                            audioProcessor.setMuxerStarted(true)
                            Log.d(TAG, "Muxer started (all tracks added)")
                        }
                    }
                }

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
    private val muxerLock: Any,
    private val TIMEOUT_US: Long = 10000L
) {
    private val TAG = "VideoProcessor"
    data class Result(val durationUs: Long)

    private var trackIndex: Int = -1
    private var muxerStarted = false

    suspend fun processClip(
        clip: VideoClip,
        surface: android.view.Surface,
        presentationTimeOffsetUs: Long,
        onProgress: suspend (Int) -> Unit
    ): Result {
        Log.d(TAG, "processClip: Starting to process clip ${clip.source}")
        Log.d(TAG, "processClip: clip.startTime=${clip.startTime}ms, clip.endTime=${clip.endTime}ms, clip.duration=${clip.duration}ms")

        val extractor = MediaExtractor().apply { setDataSource(context, clip.source, null) }
        val decoder = createDecoder(extractor, surface)
            ?: throw RuntimeException("Failed to create video decoder for ${clip.source}")

        var framesInClip = 0
        try {
            Log.d(TAG, "processClip: Starting decoder")
            decoder.start()
            val videoTrackIndex = extractor.findTrack("video/") ?: -1
            Log.d(TAG, "processClip: Video track index: $videoTrackIndex")
            extractor.selectTrack(videoTrackIndex)
            extractor.seekTo(clip.startTime * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            Log.d(TAG, "processClip: Seeked to ${clip.startTime * 1000}us")

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
                            Log.d(TAG, "processClip: Decoder reached end of stream")
                        }
                        val doRender = bufferInfo.size != 0
                        bufferInfo.presentationTimeUs += presentationTimeOffsetUs

                        if (doRender) {
                            Log.d(TAG, "processClip: Rendering frame $framesInClip, pts=${bufferInfo.presentationTimeUs}us")
                            decoder.releaseOutputBuffer(outputBufferIndex, doRender)

                            // Surface経由でエンコーダーにフレームが送られるまで待機
                            try {
                                // エンコーダーが少なくとも1フレームを処理するまで待つ
                                var encodedFrames = 0
                                var retries = 0
                                while (encodedFrames == 0 && retries < 100) { // 最大10秒待つ
                                    encodedFrames = drainEncoderNonBlocking()
                                    if (encodedFrames == 0) {
                                        Thread.sleep(100) // 100ms待つ
                                        retries++
                                    }
                                }
                                if (encodedFrames > 0) {
                                    framesInClip++
                                } else {
                                    Log.w(TAG, "processClip: Encoder did not produce output for frame $framesInClip")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "processClip: drainEncoder failed at frame $framesInClip", e)
                                throw e
                            }
                        } else {
                            decoder.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        decoderOutputAvailable = false
                    } else {
                        Log.d(TAG, "processClip: Decoder output buffer index: $outputBufferIndex")
                        decoderOutputAvailable = false
                    }
                }
            }
            Log.d(TAG, "processClip: Processed $framesInClip frames")
            onProgress(framesInClip)
        } finally {
            decoder.stop(); decoder.release()
            extractor.release()
        }
        return Result(durationUs = ((clip.duration / clip.speed) * 1000).toLong())
    }

    /**
     * エンコーダーから利用可能な出力をドレインする（ノンブロッキング）
     * @return エンコードされたフレーム数
     */
    fun drainEncoderNonBlocking(): Int {
        val bufferInfo = MediaCodec.BufferInfo()
        var outputCount = 0

        try {
            while (true) {
                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 0) // タイムアウト0 = ノンブロッキング

                when {
                    encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        break // 出力なし
                    }
                    encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "drainEncoderNonBlocking: INFO_OUTPUT_FORMAT_CHANGED")
                        synchronized(muxerLock) {
                            if (trackIndex == -1) {
                                val format = encoder.outputFormat
                                Log.d(TAG, "drainEncoderNonBlocking: Adding video track, format=$format")
                                trackIndex = muxer.addTrack(format)
                                Log.d(TAG, "drainEncoderNonBlocking: Video track index=$trackIndex")
                                // muxerの開始は全トラック追加後に外部で行う
                            }
                        }
                    }
                    encoderStatus >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                        if (bufferInfo.size != 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (trackIndex >= 0 && muxerStarted) {
                                synchronized(muxerLock) {
                                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                                }
                                outputCount++
                            }
                        }
                        encoder.releaseOutputBuffer(encoderStatus, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "drainEncoderNonBlocking: encoder not in executing state", e)
            return 0
        }
        return outputCount
    }

    fun setMuxerStarted(started: Boolean) {
        muxerStarted = started
    }

    fun getTrackIndex(): Int = trackIndex

    fun drainEncoder(endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        if (endOfStream) {
            Log.d(TAG, "drainEncoder: Signaling end of input stream")
            // encoder.signalEndOfInputStream() // Removed to avoid double EOS signaling
        }

        Log.d(TAG, "drainEncoder: Starting to drain encoder (endOfStream=$endOfStream)")
        var outputCount = 0
        while (true) {
            val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            Log.d(TAG, "drainEncoder: encoderStatus=$encoderStatus")

            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Log.d(TAG, "drainEncoder: INFO_TRY_AGAIN_LATER, endOfStream=$endOfStream")
                if (!endOfStream) break
                            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                Log.d(TAG, "drainEncoder: INFO_OUTPUT_FORMAT_CHANGED")
                                // should happen before receiving buffers, and should only happen once
                                if (muxerStarted) {
                                    throw RuntimeException("format changed twice")
                                }
                                val newFormat = encoder.outputFormat
                                Log.d(TAG, "video encoder output format changed: $newFormat")
                                synchronized(muxerLock) {
                                    if (trackIndex == -1) {
                                        trackIndex = muxer.addTrack(newFormat)
                                    }
                                }
                            } else if (encoderStatus >= 0) {                val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                if (bufferInfo.size != 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    if (trackIndex >= 0 && muxerStarted) {
                        Log.d(TAG, "drainEncoder: Writing sample data, size=${bufferInfo.size}, pts=${bufferInfo.presentationTimeUs}us")
                        synchronized(muxerLock) { muxer.writeSampleData(trackIndex, encodedData, bufferInfo) }
                        outputCount++
                    }
                }
                encoder.releaseOutputBuffer(encoderStatus, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "drainEncoder: End of stream reached")
                    break
                }
            }
        }
        Log.d(TAG, "drainEncoder: Drained $outputCount output buffers")
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
    private val muxerLock: Any,
    private val session: EditorSession,
    private val TIMEOUT_US: Long = 10000L
) {
    private val TAG = "AudioProcessor"
    data class Result(val durationUs: Long)

    private var trackIndex: Int = -1
    private var muxerStarted = false

    fun setMuxerStarted(started: Boolean) {
        muxerStarted = started
    }

    fun getTrackIndex(): Int = trackIndex

    suspend fun processClip(clip: VideoClip, presentationTimeOffsetUs: Long): Result {
            if (encoder == null || !muxerStarted) {
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
                            try {
                                drainEncoder(false, decodedData, bufferInfo)
                            } catch (e: Exception) {
                                Log.e(TAG, "processClip: Audio drainEncoder failed", e)
                                throw e
                            }
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
        try {
            while (true) {
                val encoderStatus = encoder.dequeueOutputBuffer(info ?: MediaCodec.BufferInfo(), TIMEOUT_US)
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) break
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // should happen before receiving buffers, and should only happen once
                    if (muxerStarted) {
                        throw RuntimeException("format changed twice")
                    }
                    val newFormat = encoder.outputFormat
                    Log.d(TAG, "audio encoder output format changed: $newFormat")

                    // Add track to muxer
                    synchronized(muxerLock) {
                        if (trackIndex == -1) {
                            trackIndex = muxer.addTrack(newFormat)
                        }
                    }
                } else if (encoderStatus >= 0) {
                    val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                    if (info != null && info.size != 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (trackIndex >= 0 && muxerStarted) {
                            synchronized(muxerLock) { muxer.writeSampleData(trackIndex, encodedData, info) }
                        }
                    }
                    encoder.releaseOutputBuffer(encoderStatus, false)
                    if (info != null && (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "drainEncoder: encoder not in executing state", e)
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