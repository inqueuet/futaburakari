package com.valoser.futaburakari.videoeditor.export

import android.content.Context
import android.media.*
import android.net.Uri
import android.util.Log
import android.opengl.GLES30
import android.opengl.EGL14
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import com.valoser.futaburakari.videoeditor.domain.model.ExportPreset
import com.valoser.futaburakari.videoeditor.domain.model.ExportProgress
import com.valoser.futaburakari.videoeditor.domain.model.VideoClip
import com.valoser.futaburakari.videoeditor.domain.model.Keyframe
import com.valoser.futaburakari.videoeditor.utils.findTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min
import android.opengl.Matrix
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlinx.coroutines.android.asCoroutineDispatcher

interface ExportPipeline {
    fun export(session: EditorSession, preset: ExportPreset, outputUri: Uri): Flow<ExportProgress>
}

class ExportPipelineImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ExportPipeline {

    private val TIMEOUT_US = 10000L
    private val TAG = "ExportPipeline"
    private fun logMuxerGate(msg: String) = Log.d(TAG, "[MuxerGate] $msg")

    // ★ GL操作用の専用シングルスレッドDispatcher
    private val glThread = HandlerThread("ExportGL").apply { start() }
    private val glDispatcher = Handler(glThread.looper).asCoroutineDispatcher()

    override fun export(
        session: EditorSession,
        preset: ExportPreset,
        outputUri: Uri
    ): Flow<ExportProgress> = flow {
        // ✅ エクスポート処理全体をIOディスパッチャで実行
        withContext(Dispatchers.IO) {
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

            // Muxer の起動は絶対に 1 回・かつ原子的に行う
            val muxerLock = Any()
            val muxerStarted = java.util.concurrent.atomic.AtomicBoolean(false)

            // 途中で参照するためのヘルパー(null 安全のため lateinit 回避)
            var audioTrackIndexForLog = -1

            lateinit var videoProcessor: VideoProcessor
            lateinit var audioProcessor: AudioProcessor

            val videoEncoder = VideoProcessor.createEncoder(preset)
            val audioEncoder = AudioProcessor.createEncoder(preset)
            // ★ セッション内に本当に音声が存在するかで判定する(MediaExtractor は Closeable ではないので try/finally)
            val hasAudioNeeded = (audioEncoder != null) && session.videoClips.any { clip ->
                var hasAudio = false
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(context, clip.source, null)
                    hasAudio = (ex.findTrack("audio/") != null)
                } finally {
                    ex.release()
                }
                hasAudio
            }
            logMuxerGate("hasAudioNeeded=$hasAudioNeeded, audioEncoder=${audioEncoder != null}")

            // --- Muxer start 条件 ---
            // ・hasAudioNeeded==true のときは video+audio の両トラック追加後に start
            // ・hasAudioNeeded==false のときは video トラックだけで start
            // ・毎回判定ログを出す
            val startMuxerIfReady: () -> Unit = {
                synchronized(muxerLock) {
                    // --- 冪等・再入可能な Muxer 起動ゲート ---
                    val vIndex = runCatching { videoProcessor.getTrackIndex() }.getOrDefault(-1)
                    val aIndex = runCatching { audioProcessor.getTrackIndex() }.getOrDefault(-1)
                    val audioFailed = runCatching { audioProcessor.hasFailed() }.getOrDefault(false)
                    audioTrackIndexForLog = aIndex

                    val ready = (vIndex >= 0) && (!hasAudioNeeded || aIndex >= 0 || audioFailed)
                    logMuxerGate("muxerStarted=${muxerStarted.get()}, vIndex=$vIndex, aIndex=$aIndex, ready=$ready")

                    // ready が true になった瞬間を逃さない(再入呼び出しでも安全)
                    if (ready) {
                        if (!muxerStarted.get()) {
                            muxer.start()
                            muxerStarted.set(true)
                            // ★ より詳細なログ出力
                            Log.d(TAG, "Muxer started: videoTrack=$vIndex, audioTrack=$aIndex, hasAudioNeeded=$hasAudioNeeded, audioFailed=$audioFailed, mode=${if (hasAudioNeeded && !audioFailed) "video+audio" else "video-only"}")

                            // === 起動直後に書き込み可能状態へ ===
                            videoProcessor.setMuxerStarted(true)
                            videoProcessor.onMuxerStartedLocked()
                            runCatching { audioProcessor.setMuxerStarted(true) }
                        }
                    }
                }
            }

            val totalDurationUs = session.videoClips.sumOf { (it.duration / it.speed).toLong() } * 1000
            val totalFrames = (totalDurationUs / 1_000_000f * preset.frameRate).toInt()
            val framesProcessed = java.util.concurrent.atomic.AtomicInteger(0)

            // ✅ EGL初期化はGLスレッドで実行
            val encoderInputSurface = EncoderInputSurface(videoEncoder.createInputSurface())
            var decoderSurface: DecoderOutputSurface? = null
            withContext(glDispatcher) {
                encoderInputSurface.setup()
                decoderSurface = DecoderOutputSurface(
                    preset.width,
                    preset.height,
                    encoderInputSurface.eglContext()
                ).apply { setup() }
            }
            val activeDecoderSurface = decoderSurface
                ?: throw IllegalStateException("Decoder surface initialization failed")

            // ★ 最初のクリップから元動画のサイズを取得してアスペクト比を設定
            if (session.videoClips.isNotEmpty()) {
                val firstClip = session.videoClips[0]
                val extractor = MediaExtractor().apply { setDataSource(context, firstClip.source, null) }
                val videoTrackIndex = extractor.findTrack("video/")
                if (videoTrackIndex != null) {
                    val format = extractor.getTrackFormat(videoTrackIndex)
                    val srcWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    val srcHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                    withContext(glDispatcher) {
                        activeDecoderSurface.setSourceAspectRatio(srcWidth, srcHeight)
                    }
                    Log.d(TAG, "Source video size: ${srcWidth}x${srcHeight}")
                }
                extractor.release()
            }

            // Build processors (the REAL classes below in this file)
            videoProcessor = VideoProcessor(context, videoEncoder, muxer, muxerLock, startMuxerIfReady, glDispatcher)
            // ターゲットのサンプルレート/チャンネル数を明示的に渡す
            audioProcessor = AudioProcessor(
                context,
                audioEncoder,
                muxer,
                muxerLock,
                session,
                preset.audioSampleRate,
                preset.audioChannels,
                startMuxerIfReady
            )

            try {
                videoEncoder.start()
                audioEncoder?.start()

                var videoPtsOffsetUs = 0L
                var audioPtsOffsetUs = 0L

                for (clip in session.videoClips) {
                    // Audio処理(既にwithContext(Dispatchers.IO)内)
                    val aRes = audioProcessor.processClip(clip, audioPtsOffsetUs)
                    audioPtsOffsetUs += aRes.durationUs

                    // Video処理
                    val vRes = videoProcessor.processClip(
                        clip,
                        encoderInputSurface,
                        activeDecoderSurface,
                        videoPtsOffsetUs
                    ) { progressedFrames ->
                        val currentFrames = framesProcessed.addAndGet(progressedFrames)
                        val percent = (currentFrames.toFloat() / totalFrames).coerceIn(0f, 1f) * 100f

                        // ✅ 進捗通知だけメインスレッドで
                        // emit(ExportProgress(currentFrames, totalFrames, percent, 0))
                    }
                    videoPtsOffsetUs += vRes.durationUs
                }

                // --- EOS(終了処理):すべてのクリップ処理後に一度だけ送信 ---
                // Audio EOS を送信してから完全ドレイン
                if (audioEncoder != null) {
                    val eosInputBufferIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
                    if (eosInputBufferIndex >= 0) {
                        audioEncoder.queueInputBuffer(
                            eosInputBufferIndex,
                            0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                }
                audioProcessor.drainAudioEncoder(true)

                // 続いて Video に EOS を投げてから最後までドレイン
                videoEncoder.signalEndOfInputStream()
                videoProcessor.drainEncoder(true)

            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                throw e
            } finally {
                // Muxer 状態の最終ログ
                val finalV = runCatching { videoProcessor.getTrackIndex() }.getOrDefault(-1)
                logMuxerGate("FINALLY muxerStarted=${muxerStarted.get()} vTrack=$finalV aTrack=$audioTrackIndexForLog")

                if (!muxerStarted.get()) {
                    // === 不変条件違反を明確に検知(強行 start は行わない) ===
                    Log.e(TAG, "⚠️ Muxer never started – output is likely empty (0B). Check track add & startMuxerIfReady timing.")
                    if (finalV >= 0) {
                        Log.e(TAG, "⚠️ Video track existed but muxer never started. This indicates race condition before ready=true evaluation.")
                    }
                }
                try {
                    videoEncoder.stop()
                } catch (_: Throwable) {}
                try {
                    videoEncoder.release()
                } catch (_: Throwable) {}
                try {
                    audioEncoder?.stop()
                } catch (_: Throwable) {}
                try {
                    audioEncoder?.release()
                } catch (_: Throwable) {}

                // Release EGL surfaces last
                try {
                    withContext(glDispatcher) {
                        decoderSurface?.let {
                            try { it.release() } catch (_: Throwable) {}
                        }
                        try { encoderInputSurface.release() } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}

                if (muxerStarted.get()) {
                    try { muxer.stop() } catch (_: Throwable) {}
                }
                try { muxer.release() } catch (_: Throwable) {}
                try { pfd.close() } catch (_: Throwable) {}

                logMuxerGate("Export finished. (file should be non-empty if muxerStarted=true)")
            }

            // ✅ 最終進捗を送信
            emit(ExportProgress(totalFrames, totalFrames, 100f, 0))
            Log.d(TAG, "Export finished.")
        } // withContext(Dispatchers.IO)
    }
}

private class VideoProcessor(
    private val context: Context,
    private val encoder: MediaCodec,
    private val muxer: MediaMuxer,
    private val muxerLock: Any,
    private val muxerStartCallback: () -> Unit,
    private val glCoroutineContext: CoroutineContext
) {
    private val TAG = "VideoProcessor"
    private val TIMEOUT_US: Long = 10000L
    private val EOS_DRAIN_TIMEOUT_MS = 4_000L

    data class Result(val durationUs: Long)
    private data class EncodedSample(val data: ByteArray, val ptsUs: Long, val flags: Int)
    private val pendingVideo = ArrayDeque<EncodedSample>()
    private val MAX_PENDING_FRAMES = 300  // ★ pending上限を設定(約10秒分 @30fps)

    // ★ BufferInfoを再利用してGC圧を軽減
    private val reusableBufferInfo = MediaCodec.BufferInfo()

    // ★ 公開:Muxer 起動直後に呼べるように
    fun onMuxerStartedLocked() = flushPendingVideoLocked()
    private fun flushPendingVideoLocked() {
        if (!muxerStarted.get() || trackIndex < 0) return
        var flushed = 0
        while (pendingVideo.isNotEmpty()) {
            val s = pendingVideo.removeFirst()
            val info = MediaCodec.BufferInfo().apply { set(0, s.data.size, s.ptsUs, s.flags) }
            muxer.writeSampleData(trackIndex, java.nio.ByteBuffer.wrap(s.data), info)
            flushed++
        }
        Log.d(TAG, "flushPendingVideoLocked: flushed=$flushed samples to muxer (track=$trackIndex)")
    }

    private var trackIndex: Int = -1
    private val muxerStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun processClip(
        clip: VideoClip,
        encoderInputSurface: EncoderInputSurface,
        decoderOutputSurface: DecoderOutputSurface,
        presentationTimeOffsetUs: Long,
        onProgress: (Int) -> Unit
    ): Result {
        Log.d(TAG, "processClip: Starting to process clip ${clip.source}")
        Log.d(TAG, "processClip: clip.startTime=${clip.startTime}ms, clip.endTime=${clip.endTime}ms, clip.duration=${clip.duration}ms")

        val extractor = MediaExtractor().apply { setDataSource(context, clip.source, null) }
        val decoder = createDecoder(extractor, decoderOutputSurface.surface)
            ?: throw RuntimeException("Failed to create video decoder for ${clip.source}")

        var framesInClip = 0
        var isInputDone = false
        var isOutputDone = false
        try {
            Log.d(TAG, "processClip: Starting decoder")
            decoder.start()
            val videoTrackIndex = extractor.findTrack("video/") ?: throw RuntimeException("No video track found")
            extractor.selectTrack(videoTrackIndex)
            extractor.seekTo(clip.startTime * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var loopIteration = 0
            while (!isOutputDone) {
                if (loopIteration % 30 == 0) { // ざっくり状態確認（約1秒おき @30fps想定）
                    Log.d(TAG, "processClip: loop iteration=$loopIteration, inputDone=$isInputDone, outputDone=$isOutputDone, pendingVideo=${pendingVideo.size}")
                }
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
                    val outputBufferIndex = decoder.dequeueOutputBuffer(reusableBufferInfo, TIMEOUT_US)
                    if (outputBufferIndex >= 0) {
                        if ((reusableBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputDone = true
                            Log.d(TAG, "processClip: Decoder reached end of stream")
                        }
                        val doRender = reusableBufferInfo.size != 0
                        val adjustedPts = reusableBufferInfo.presentationTimeUs + presentationTimeOffsetUs
                        reusableBufferInfo.presentationTimeUs = adjustedPts

                        if (doRender) {
                            Log.d(TAG, "processClip: Rendering frame $framesInClip, pts=${adjustedPts}us")
                            decoder.releaseOutputBuffer(outputBufferIndex, doRender)
                            
                            withContext(glCoroutineContext) {
                                decoderOutputSurface.awaitNewImage(encoderInputSurface)
                                decoderOutputSurface.drawImage(encoderInputSurface)
                                
                                // ★ 4. PTSを設定してswap
                                encoderInputSurface.setPresentationTime(reusableBufferInfo.presentationTimeUs * 1000L)
                                encoderInputSurface.swapBuffers()
                                
                                // ★ 5. GPUフェンスで同期
                                val sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                                if (sync != 0L) {
                                    val timeoutNs = 50_000_000L // 50ms
                                    var waitResult = GLES30.GL_UNSIGNALED
                                    while (waitResult != GLES30.GL_SIGNALED && waitResult != GLES30.GL_TIMEOUT_EXPIRED) {
                                        waitResult = GLES30.glClientWaitSync(sync, 0, timeoutNs)
                                    }
                                    GLES30.glDeleteSync(sync)
                                } else {
                                    Log.w(TAG, "glFenceSync returned 0 (no sync created)")
                                }
                            }
                            
                            // フェンス待ち後にエンコーダー出力をノンブロッキングで取得
                            var produced = drainEncoderNonBlocking()
                            if (produced == 0) {
                                repeat(2) {
                                    Thread.sleep(5)
                                    produced += drainEncoderNonBlocking()
                                }
                            }
                            if (produced > 0) {
                                framesInClip++
                                onProgress(1)
                            } else {
                                Log.w(TAG, "processClip: Encoder did not produce output for frame $framesInClip, continuing...")
                                framesInClip++  // フレームカウントは進める
                                onProgress(1)
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
                loopIteration++
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
     * エンコーダーから利用可能な出力をドレインする(ノンブロッキング)
     * @return エンコードされたフレーム数
     */
    fun drainEncoderNonBlocking(): Int {
        var outputCount = 0

        try {
            while (true) {
                val encoderStatus = encoder.dequeueOutputBuffer(reusableBufferInfo, 0)

                when {
                    encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        Log.v(TAG, "drainEncoderNonBlocking: INFO_TRY_AGAIN_LATER")
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
                                muxerStartCallback() // Call the callback here
                                // muxerの開始は全トラック追加後に外部で行う
                            }
                        }
                    }
                    encoderStatus >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                        if (reusableBufferInfo.size != 0 && (reusableBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            synchronized(muxerLock) {
                                if (muxerStarted.get() && trackIndex >= 0) {
                                    muxer.writeSampleData(trackIndex, encodedData, reusableBufferInfo)
                                } else {
                                    // ★ pending queueが大きくなりすぎないようチェック
                                    if (pendingVideo.size >= MAX_PENDING_FRAMES) {
                                        Log.w(TAG, "Pending video queue full (${pendingVideo.size}), dropping oldest frame")
                                        pendingVideo.removeFirst()
                                    }
                                    // ★ 深いコピーで保留(破棄しない)
                                    encodedData.position(reusableBufferInfo.offset)
                                    encodedData.limit(reusableBufferInfo.offset + reusableBufferInfo.size)
                                    val copy = ByteArray(reusableBufferInfo.size)
                                    encodedData.get(copy)
                                    pendingVideo.addLast(
                                        EncodedSample(copy, reusableBufferInfo.presentationTimeUs, reusableBufferInfo.flags)
                                    )
                                }
                            }
                            // ★ 出力は出たので、進捗カウントは増やす(レンダループが進む)
                            outputCount++
                        }
                        encoder.releaseOutputBuffer(encoderStatus, false)

                        if ((reusableBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
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
        muxerStarted.set(started)
        if (started) {
            synchronized(muxerLock) { flushPendingVideoLocked() }
        }
    }

    fun getTrackIndex(): Int = trackIndex

    fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            Log.d(TAG, "drainEncoder: Signaling end of input stream")
            // encoder.signalEndOfInputStream() // Removed to avoid double EOS signaling
        }

        Log.d(TAG, "drainEncoder: Starting to drain encoder (endOfStream=$endOfStream)")
        var outputCount = 0
        val eosStartRealtime = if (endOfStream) SystemClock.elapsedRealtime() else 0L
        while (true) {
            val encoderStatus = encoder.dequeueOutputBuffer(reusableBufferInfo, TIMEOUT_US)
            Log.d(TAG, "drainEncoder: encoderStatus=$encoderStatus")

            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Log.d(TAG, "drainEncoder: INFO_TRY_AGAIN_LATER, endOfStream=$endOfStream")
                if (!endOfStream) {
                    break
                } else {
                    val waitedMs = SystemClock.elapsedRealtime() - eosStartRealtime
                    if (waitedMs >= EOS_DRAIN_TIMEOUT_MS) {
                        Log.w(
                            TAG,
                            "drainEncoder: timed out waiting for EOS after ${waitedMs}ms, forcing completion with $outputCount pending samples"
                        )
                        break
                    }
                    continue
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "drainEncoder: INFO_OUTPUT_FORMAT_CHANGED")
                // should happen before receiving buffers, and should only happen once
                if (synchronized(muxerLock) { muxerStarted.get() }) {
                    throw RuntimeException("format changed twice")
                }
                val newFormat = encoder.outputFormat
                Log.d(TAG, "video encoder output format changed: $newFormat")
                synchronized(muxerLock) {
                    if (trackIndex == -1) {
                        trackIndex = muxer.addTrack(newFormat)
                        // ★ 非ブロッキングと同様にコールバックしておく
                        muxerStartCallback()
                    }
                }
            } else if (encoderStatus >= 0) {
                val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                if (reusableBufferInfo.size != 0 && (reusableBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    synchronized(muxerLock) {
                        if (trackIndex >= 0 && muxerStarted.get()) {
                            Log.d(TAG, "drainEncoder: Writing sample data, size=${reusableBufferInfo.size}, pts=${reusableBufferInfo.presentationTimeUs}us")
                            muxer.writeSampleData(trackIndex, encodedData, reusableBufferInfo)
                            outputCount++
                        } else {
                            // Muxer未開始の間は捨てずに pending へ退避(深いコピー)
                            encodedData.position(reusableBufferInfo.offset)
                            encodedData.limit(reusableBufferInfo.offset + reusableBufferInfo.size)
                            val copy = ByteArray(reusableBufferInfo.size)
                            encodedData.get(copy)
                            pendingVideo.addLast(
                                EncodedSample(copy, reusableBufferInfo.presentationTimeUs, reusableBufferInfo.flags)
                            )
                            // ★ データが来た段階でも起動条件を再チェック(タイミング競合の保険)
                            //   ※ addTrack() 側でも呼んでいるが、ここでも念のため
                            //   (弱い呼び出し:起動済みなら何もしない)
                            // muxerStartCallback は外側キャプチャ
                            muxerStartCallback()
                        }
                    }
                }
                encoder.releaseOutputBuffer(encoderStatus, false)
                if ((reusableBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
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
    // ★ 追加: エンコーダに渡すべきターゲット仕様
    private val targetSampleRate: Int,
    private val targetChannelCount: Int,
    private val muxerStartCallback: () -> Unit
) {
    private val TAG = "AudioProcessor"
    private val TIMEOUT_US: Long = 10000L
    private val EOS_DRAIN_TIMEOUT_MS = 4_000L

    data class Result(val durationUs: Long)

    // 音声パスが致命的に失敗した場合のフォールバックフラグ
    @Volatile
    private var failed: Boolean = false
    fun hasFailed(): Boolean = failed

    private data class EncodedSample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private val pendingAudio = ArrayDeque<EncodedSample>()


    // ★ BufferInfoを再利用
    private val reusableBufferInfo = MediaCodec.BufferInfo()
    private val reusableEncoderBufferInfo = MediaCodec.BufferInfo()

    /**
     * リサンプリング(線形補間・16bit PCM想定)。戻り値は (出力PCM, 出力サンプル数/1ch)。
     * 入力: ByteBuffer(PCM16), bufferInfo(offset/size), 入力レート/チャンネル
     */
    private fun resampleIfNeeded(
        src: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        inSampleRate: Int,
        inChannels: Int
    ): Pair<ByteBuffer, Int> {
        // ★ サンプルレートの検証

        // ★ バッファサイズの妥当性チェックを追加
        val expectedAlignment = 2 * inChannels  // 16bit * channels
        if (bufferInfo.size % expectedAlignment != 0) {
            val originalSize = bufferInfo.size
            val alignedSize = (bufferInfo.size / expectedAlignment) * expectedAlignment
            bufferInfo.size = alignedSize
            Log.w(TAG, "Buffer size adjusted from $originalSize to $alignedSize")
        }

        if (inSampleRate <= 0 || targetSampleRate <= 0) {
            Log.e(TAG, "Invalid sample rate: in=$inSampleRate, target=$targetSampleRate")
            throw IllegalArgumentException("Invalid sample rate")
        }
        if (inChannels <= 0 || targetChannelCount <= 0) {
            Log.e(TAG, "Invalid channel count: in=$inChannels, target=$targetChannelCount")
            throw IllegalArgumentException("Invalid channel count")
        }

        // すでにターゲット仕様ならコピー不要
        if (inSampleRate == targetSampleRate && inChannels == targetChannelCount) {
            val dup = src.duplicate()
            dup.position(bufferInfo.offset)
            dup.limit(bufferInfo.offset + bufferInfo.size)
            return Pair(dup.slice(), (bufferInfo.size / 2 /*bytes*/ / inChannels))
        }

        // チャンネル本数の調整(簡易:多ch→ダウンミックス/少ch→複製)
        // ここでは in->target への最小限の対応
        val srcShort = ShortArray(bufferInfo.size / 2)
        run {
            val dup = src.duplicate()
            dup.position(bufferInfo.offset)
            dup.limit(bufferInfo.offset + bufferInfo.size)
            val sb = dup.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
            sb.get(srcShort)
        }

        // ダウンミックス/アップミックスを中間バッファに整形(interleaved)
        val framesIn = srcShort.size / inChannels
        val interleavedInMono = ShortArray(framesIn) // 1ch仮想(後で必要に応じ複製)
        if (inChannels == 1) {
            System.arraycopy(srcShort, 0, interleavedInMono, 0, framesIn)
        } else {
            // 単純平均でダウンミックス
            var si = 0
            for (i in 0 until framesIn) {
                var acc = 0
                for (c in 0 until inChannels) {
                    acc += srcShort[si + c].toInt()
                }
                interleavedInMono[i] = (acc / inChannels).toShort()
                si += inChannels
            }
        }

        // レート変換(線形補間)
        val outFrames = Math.max(1, (framesIn.toLong() * targetSampleRate / inSampleRate).toInt())
        val outMono = ShortArray(outFrames)
        if (framesIn == 0) {
            // 無音
            // outMono は zero 初期化済み
        } else if (inSampleRate == targetSampleRate) {
            // レート同一ならコピー
            val copy = Math.min(framesIn, outFrames)
            System.arraycopy(interleavedInMono, 0, outMono, 0, copy)
        } else {
            val ratio = framesIn.toDouble() / outFrames
            for (i in 0 until outFrames) {
                val srcPos = i * ratio
                val i0 = kotlin.math.floor(srcPos).toInt().coerceIn(0, framesIn - 1)
                val i1 = (i0 + 1).coerceAtMost(framesIn - 1)
                val t = (srcPos - i0)
                val s0 = interleavedInMono[i0].toInt()
                val s1 = interleavedInMono[i1].toInt()
                outMono[i] = (s0 + (s1 - s0) * t).toInt().toShort()
            }
        }

        // ターゲットchへ拡張(1→Nch複製)
        val outInterleaved = ShortArray(outFrames * targetChannelCount)
        var di = 0
        for (i in 0 until outFrames) {
            val v = outMono[i]
            for (c in 0 until targetChannelCount) {
                outInterleaved[di++] = v
            }
        }

        val outBytes = ByteBuffer.allocate(outInterleaved.size * 2).order(ByteOrder.nativeOrder())
        outBytes.asShortBuffer().put(outInterleaved)
        outBytes.position(0)
        outBytes.limit(outInterleaved.size * 2)
        return Pair(outBytes, outFrames)
    }

    private fun flushPendingAudioLocked() {
        if (!muxerStarted.get() || trackIndex < 0) return
        while (pendingAudio.isNotEmpty()) {
            val s = pendingAudio.removeFirst()
            val info = MediaCodec.BufferInfo().apply { set(0, s.data.size, s.ptsUs, s.flags) }
            muxer.writeSampleData(trackIndex, java.nio.ByteBuffer.wrap(s.data), info)
        }
    }

    private var trackIndex: Int = -1
    private val muxerStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    fun setMuxerStarted(started: Boolean) {
        muxerStarted.set(started)
        if (started) synchronized(muxerLock) { flushPendingAudioLocked() }
    }

    fun getTrackIndex(): Int = trackIndex

    suspend fun processClip(clip: VideoClip, presentationTimeOffsetUs: Long): Result {
        if (encoder == null) {
            return Result(durationUs = ((clip.duration / clip.speed) * 1000).toLong())
        }

        val audioClip = session.audioTracks.flatMap { it.clips }
            .find { it.source == clip.source && it.startTime == clip.startTime }
        val sortedVolumeKeyframes = audioClip?.volumeKeyframes
            ?.takeIf { it.isNotEmpty() }
            ?.sortedBy { it.time }
        val extractor = MediaExtractor().apply { setDataSource(context, clip.source, null) }
        val decoder = AudioProcessor.createDecoder(extractor)
            ?: return Result(durationUs = ((clip.duration / clip.speed) * 1000).toLong()).also { extractor.release() }

        try {
            decoder.start()
            val audioTrackIndex = extractor.findTrack("audio/")
            if (audioTrackIndex == null) {
                // まれにコンテンツに音声が無いケース
                decoder.stop(); decoder.release()
                extractor.release()
                return Result(durationUs = ((clip.duration / clip.speed) * 1000).toLong())
            }
            extractor.selectTrack(audioTrackIndex)
            extractor.seekTo(clip.startTime * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // まず Extractor のトラックフォーマットから暫定のレート/チャンネル数を取得し、
            // 入力を供給しながら出力フォーマット変更を待つ
            val inFormat = extractor.getTrackFormat(audioTrackIndex)
            var sampleRate: Int = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount: Int = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var outputFormatKnown = false

            var isInputDone = false
            var isOutputDone = false

            // ★ クリップごとにローカル変数で管理
            var clipOutSamples = 0L

            while (!isOutputDone) {
                if (!isInputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val sampleTime = extractor.sampleTime
                        if (sampleTime == -1L || sampleTime >= clip.endTime * 1000) {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isInputDone = true
                        } else {
                            val sampleSize = extractor.readSampleData(
                                decoder.getInputBuffer(inputBufferIndex)!!,
                                0
                            )
                            if (sampleSize < 0) {
                                isInputDone = true
                            } else {
                                val presentationTimeUs =
                                    ((sampleTime - clip.startTime * 1000) / clip.speed).toLong()
                                decoder.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sampleSize,
                                    presentationTimeUs,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                var decoderOutputAvailable = true
                while (decoderOutputAvailable) {
                    val outputBufferIndex =
                        decoder.dequeueOutputBuffer(reusableBufferInfo, TIMEOUT_US)
                    if (outputBufferIndex >= 0) {
                        if ((reusableBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) isOutputDone =
                            true
                        if (reusableBufferInfo.size > 0) {
                            val decodedData = decoder.getOutputBuffer(outputBufferIndex)!!
                            sortedVolumeKeyframes?.let { keyframes ->
                                applyVolumeAutomation(
                                    decodedData,
                                    reusableBufferInfo,
                                    sampleRate,
                                    channelCount,
                                    keyframes
                                )
                            }

                            // ★ リサンプリングしてターゲット仕様へ
                            val (pcmForEncoder, outFrames) = resampleIfNeeded(
                                decodedData,
                                reusableBufferInfo,
                                sampleRate,
                                channelCount
                            )

                            // ★ エンコーダ向けPTSを「これまでにエンコーダへ渡した総サンプル数」から再計算
                            // 注意: 長時間動画では累積誤差が発生する可能性がある
                            // より精密な計算が必要な場合は、各クリップの開始時にリセットするか、
                            // 元のPTSを基準とした相対計算を行うこと
                            val ptsUsForEncoder =
                                presentationTimeOffsetUs + (clipOutSamples * 1_000_000L / targetSampleRate)

                            reusableEncoderBufferInfo.apply {
                                set(
                                    0,
                                    pcmForEncoder.remaining(),
                                    ptsUsForEncoder,
                                    reusableBufferInfo.flags
                                )
                            }
                            try {
                                queueToAudioEncoder(pcmForEncoder, reusableEncoderBufferInfo)
                                drainAudioEncoder(false) // ← クリップ処理中は定期的にdrain(EOSなし)
                            } catch (e: Exception) {
                                Log.e(TAG, "processClip: Audio encoding failed", e)
                                throw e
                            }
                            // 出力サンプル数を加算(1ch換算)
                            clipOutSamples += outFrames.toLong()
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                        if (isOutputDone) break
                    } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        decoderOutputAvailable = false
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // ★ 入力を流しつつ、ここで正式な出力フォーマットを取得する
                        val outFormat = decoder.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outputFormatKnown = true
                        Log.d(
                            TAG,
                            "processClip: audio decoder out format = ${sampleRate} Hz, ch=${channelCount}"
                        )
                        Log.d(TAG, "Audio decoder output format changed: $outFormat")
                    } else {
                        Log.d(
                            TAG,
                            "processClip: Audio decoder output buffer index: $outputBufferIndex"
                        )
                        decoderOutputAvailable = false
                    }
                }
            }

            // ☆ クリップごとのEOS送信を削除(全クリップ処理後に一度だけ送信)
        } finally {
            decoder.stop(); decoder.release()
            extractor.release()
        }
        return Result(durationUs = ((clip.duration / clip.speed) * 1000).toLong())
    }

    private fun applyVolumeAutomation(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        sampleRate: Int,
        channelCount: Int,
        keyframes: List<Keyframe>
    ) {
        if (keyframes.isEmpty()) return

        val shortBuffer = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val totalSamples = info.size / 2 / channelCount
        if (totalSamples <= 0) return

        val samples = ShortArray(totalSamples * channelCount)
        shortBuffer.get(samples)

        val startTimeUs = info.presentationTimeUs
        val startTimeMs = startTimeUs / 1000

        var frameIndex = 0
        var keyframeIndex = 0

        while (frameIndex < totalSamples) {
            while (
                keyframeIndex + 1 < keyframes.size &&
                keyframes[keyframeIndex + 1].time <= startTimeMs + (frameIndex * 1_000L) / sampleRate
            ) {
                keyframeIndex++
            }

            val currentKeyframe = keyframes[keyframeIndex]
            val nextKeyframe = keyframes.getOrNull(keyframeIndex + 1)

            val currentTimeMs = startTimeMs + (frameIndex * 1_000L) / sampleRate
            val segmentEndTimeMs = nextKeyframe?.time ?: Long.MAX_VALUE

            val remainingFrames = totalSamples - frameIndex
            val framesUntilNext = if (segmentEndTimeMs == Long.MAX_VALUE) remainingFrames else {
                val deltaMs = segmentEndTimeMs - currentTimeMs
                if (deltaMs <= 0) 1 else min(remainingFrames.toLong(), (deltaMs * sampleRate) / 1000L).toInt().coerceAtLeast(1)
            }

            val framesThisSegment = min(remainingFrames, framesUntilNext)

            val totalSegmentFrames = when {
                nextKeyframe == null -> 0L
                nextKeyframe.time == currentKeyframe.time -> 0L
                else -> max(1L, (nextKeyframe.time - currentKeyframe.time) * sampleRate / 1000L)
            }

            val offsetFromCurrent = max(0L, (currentTimeMs - currentKeyframe.time) * sampleRate / 1000L)
            val slope = if (nextKeyframe == null || totalSegmentFrames == 0L) {
                0f
            } else {
                (nextKeyframe.value - currentKeyframe.value) / totalSegmentFrames.toFloat()
            }

            var currentVolume = if (nextKeyframe == null || totalSegmentFrames == 0L) {
                currentKeyframe.value
            } else {
                currentKeyframe.value + slope * offsetFromCurrent
            }

            val baseIndex = frameIndex * channelCount
            for (i in 0 until framesThisSegment) {
                val volume = currentVolume
                val frameBase = baseIndex + i * channelCount
                for (c in 0 until channelCount) {
                    val sample = samples[frameBase + c]
                    samples[frameBase + c] = (sample * volume).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
                currentVolume += slope
            }

            frameIndex += framesThisSegment
        }

        shortBuffer.position(0)
        shortBuffer.put(samples)
    }

    fun queueToAudioEncoder(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (encoder == null) return
        val encoderInputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (encoderInputIndex >= 0) {
            val inBuf = encoder.getInputBuffer(encoderInputIndex)!!
            inBuf.clear()
            // ★ 有効範囲だけをコピー
            val oldLimit = data.limit()
            val oldPos = data.position()
            data.limit(info.offset + info.size)
            data.position(info.offset)
            inBuf.put(data)
            // ★ 戻す
            data.limit(oldLimit)
            data.position(oldPos)

            encoder.queueInputBuffer(
                encoderInputIndex, 0, info.size,
                info.presentationTimeUs, info.flags
            )
        } else {
            Log.w(
                TAG,
                "queueToAudioEncoder: Failed to get input buffer for encoder, index=$encoderInputIndex"
            )
        }
    }

    fun drainAudioEncoder(endOfStream: Boolean): Boolean {
        if (encoder == null) return true // If no encoder, consider it drained
        var encoderOutputAvailable = false
        val eosStartRealtime = if (endOfStream) SystemClock.elapsedRealtime() else 0L
        try {
            while (true) {
                val encoderStatus =
                    encoder.dequeueOutputBuffer(reusableEncoderBufferInfo, TIMEOUT_US)
                when {
                    encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) {
                            encoderOutputAvailable = false
                            break
                        } else {
                            val waitedMs = SystemClock.elapsedRealtime() - eosStartRealtime
                            if (waitedMs >= EOS_DRAIN_TIMEOUT_MS) {
                                Log.w(
                                    TAG,
                                    "drainAudioEncoder: timed out waiting for EOS after ${waitedMs}ms, muting audio and continuing with video-only export"
                                )
                                failed = true
                                pendingAudio.clear()
                                muxerStartCallback()
                                return true
                            }
                            encoderOutputAvailable = true // Keep trying if EOS
                            continue
                        }
                    }

                    encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "drainAudioEncoder: INFO_OUTPUT_FORMAT_CHANGED")
                        synchronized(muxerLock) {
                            if (trackIndex == -1) {
                                val format = encoder.outputFormat
                                Log.d(TAG, "drainAudioEncoder: Adding audio track, format=$format")
                                try {
                                    trackIndex = muxer.addTrack(format)
                                    Log.d(TAG, "drainAudioEncoder: Audio track index=$trackIndex")
                                    muxerStartCallback() // 追加後に Muxer start を再評価
                                } catch (e: IllegalStateException) {
                                    // 端末/仮想デバイスでまれに "Muxer is not initialized" が発生するケースにフォールバック
                                    Log.w(
                                        TAG,
                                        "drainAudioEncoder: addTrack failed, fallback to video-only export",
                                        e
                                    )
                                    failed = true
                                    // 以降の音声出力は破棄し、video-only で進める
                                }
                            }
                        }
                    }

                    encoderStatus >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                        if (!failed && reusableEncoderBufferInfo.size != 0 && (reusableEncoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            synchronized(muxerLock) {
                                if (muxerStarted.get() && trackIndex >= 0) {
                                    muxer.writeSampleData(
                                        trackIndex,
                                        encodedData,
                                        reusableEncoderBufferInfo
                                    )
                                } else {
                                    encodedData.position(reusableEncoderBufferInfo.offset)
                                    encodedData.limit(reusableEncoderBufferInfo.offset + reusableEncoderBufferInfo.size)
                                    val copy = ByteArray(reusableEncoderBufferInfo.size)
                                    encodedData.get(copy)
                                    pendingAudio.addLast(
                                        EncodedSample(
                                            copy,
                                            reusableEncoderBufferInfo.presentationTimeUs,
                                            reusableEncoderBufferInfo.flags
                                        )
                                    )
                                }
                            }
                        }
                        encoder.releaseOutputBuffer(encoderStatus, false)
                        if ((reusableEncoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "drainAudioEncoder: End of stream reached")
                            return true // Fully drained
                        }
                        encoderOutputAvailable = true
                    }
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "drainAudioEncoder: encoder not in executing state", e)
            failed = true
            return true // フォールバック扱いで続行
        }
        return !encoderOutputAvailable // Return true if no more output is expected (not drained yet)
    }

    companion object {
        fun createEncoder(preset: ExportPreset): MediaCodec? {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                preset.audioSampleRate,
                preset.audioChannels
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, preset.audioBitrate)
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
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
            val trackIndex = extractor.findTrack("audio/")
            if (trackIndex == null) return null
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
