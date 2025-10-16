package com.valoser.futaburakari.videoeditor.export

import android.content.Context
import android.media.*
import android.net.Uri
import android.util.Log
import android.opengl.GLES30
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import com.valoser.futaburakari.videoeditor.domain.model.ExportPreset
import com.valoser.futaburakari.videoeditor.domain.model.ExportProgress
import com.valoser.futaburakari.videoeditor.domain.model.VideoClip
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext
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
    private fun logMuxerGate(msg: String) = Log.d(TAG, "[MuxerGate] $msg")

    override fun export(
        session: EditorSession,
        preset: ExportPreset,
        outputUri: Uri
    ): Flow<ExportProgress> = flow {
        // Flowのコレクタ文脈（通常は Main.immediate）を捕まえる
        val collectorContext = kotlinx.coroutines.currentCoroutineContext()
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

        // 途中で参照するためのヘルパ（null 安全のため lateinit 回避）
        var audioTrackIndexForLog = -1

        lateinit var videoProcessor: VideoProcessor
        lateinit var audioProcessor: AudioProcessor

        val videoEncoder = VideoProcessor.createEncoder(preset)
        val audioEncoder = AudioProcessor.createEncoder(preset)
        // ★ セッション内に本当に音声が存在するかで判定する（MediaExtractor は Closeable ではないので try/finally）
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
        val startMuxerIfReady = {
            // --- 冪等・再入可能な Muxer 起動ゲート ---
            val vIndex = runCatching { videoProcessor.getTrackIndex() }.getOrDefault(-1)
            val aIndex = runCatching { audioProcessor.getTrackIndex() }.getOrDefault(-1)
            val audioFailed = runCatching { audioProcessor.hasFailed() }.getOrDefault(false)
            audioTrackIndexForLog = aIndex

            val ready = (vIndex >= 0) && (!hasAudioNeeded || aIndex >= 0 || audioFailed)
            logMuxerGate("muxerStarted=${muxerStarted.get()} vIndex=$vIndex aIndex=$aIndex hasAudioNeeded=$hasAudioNeeded audioFailed=$audioFailed ready=$ready")

            // ready が true になった瞬間を逃さない（再入呼び出しでも安全）
            if (ready) {
                synchronized(muxerLock) {
                    if (!muxerStarted.get()) {
                        muxer.start()
                        muxerStarted.set(true)
                        Log.d(TAG, "Muxer started (${if (hasAudioNeeded && !audioFailed) "video+audio" else "video-only (audio failed or not needed)"})")

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

        val glCoroutineContext = currentCoroutineContext()

        // Create EGL surfaces for surface-to-surface pipeline
        val encoderInputSurface = EncoderInputSurface(videoEncoder.createInputSurface())
        encoderInputSurface.setup() // EGL 初期化（成功しないとフレームが出ず muxer も起動しにくい）

        val decoderOutputSurface = DecoderOutputSurface(
            preset.width,
            preset.height,
            encoderInputSurface.eglContext() // ★ 共有するのは EGLContext だけ
        )
        decoderOutputSurface.setup() // ★ 追加

        // ★ 最初のクリップから元動画のサイズを取得してアスペクト比を設定
        if (session.videoClips.isNotEmpty()) {
            val firstClip = session.videoClips[0]
            val extractor = MediaExtractor().apply { setDataSource(context, firstClip.source, null) }
            val videoTrackIndex = extractor.findTrack("video/")
            if (videoTrackIndex != null) {
                val format = extractor.getTrackFormat(videoTrackIndex)
                val srcWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                val srcHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                decoderOutputSurface.setSourceAspectRatio(srcWidth, srcHeight)
                Log.d(TAG, "Source video size: ${srcWidth}x${srcHeight}")
            }
            extractor.release()
        }

        // Build processors (the REAL classes below in this file)
        videoProcessor = VideoProcessor(context, videoEncoder, muxer, muxerLock, startMuxerIfReady, glCoroutineContext)
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
                // --- Audio を先に処理してトラック確定を前倒し ---
                val aRes = withContext(Dispatchers.IO) {
                    audioProcessor.processClip(clip, audioPtsOffsetUs)
                }
                audioPtsOffsetUs += aRes.durationUs

                // --- 続いて Video（進捗は従来どおり Video 基準） ---
                val vRes = withContext(Dispatchers.IO) {
                    videoProcessor.processClip(
                        clip,
                        encoderInputSurface,
                        decoderOutputSurface,
                        videoPtsOffsetUs
                    ) { progressedFrames ->
                        val currentFrames = framesProcessed.addAndGet(progressedFrames)
                        val percent = (currentFrames.toFloat() / totalFrames).coerceIn(0f, 1f) * 100f
                        withContext(collectorContext) {
                            emit(ExportProgress(currentFrames, totalFrames, percent, 0))
                        }
                    }
                }
                videoPtsOffsetUs += vRes.durationUs
            }



            // --- EOS（終了処理）：すべてのクリップ処理後に一度だけ送信 ---
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
                // === 不変条件違反を明確に検知（強行 start は行わない） ===
                Log.e(TAG, "⚠️ Muxer never started — output is likely empty (0B). Check track add & startMuxerIfReady timing.")
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
            try { decoderOutputSurface.release() } catch (_: Throwable) {}
            try { encoderInputSurface.release() } catch (_: Throwable) {}

            if (muxerStarted.get()) {
                try { muxer.stop() } catch (_: Throwable) {}
            }
            try { muxer.release() } catch (_: Throwable) {}
            try { pfd.close() } catch (_: Throwable) {}

            logMuxerGate("Export finished. (file should be non-empty if muxerStarted=true)")
            emit(ExportProgress(totalFrames, totalFrames, 100f, 0))
            Log.d(TAG, "Export finished.")
        }
    }
}

private fun MediaExtractor.findTrack(mimePrefix: String): Int? {
    for (i in 0 until trackCount) {
        val format = getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith(mimePrefix)) return i
    }
    return null
}

private class VideoProcessor(
    private val context: Context,
    private val encoder: MediaCodec,
    private val muxer: MediaMuxer,
    private val muxerLock: Any,
    private val muxerStartCallback: () -> Unit,
    private val glCoroutineContext: CoroutineContext,   // ★ 追加
    private val TIMEOUT_US: Long = 10000L               // ★ カンマと配置を正す
) {
    private val TAG = "VideoProcessor"

    data class Result(val durationUs: Long)
    private data class EncodedSample(val data: ByteArray, val ptsUs: Long, val flags: Int)
    private val pendingVideo = ArrayDeque<EncodedSample>()

    // ★ 公開：Muxer 起動直後に呼べるように
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
        onProgress: suspend (Int) -> Unit
    ): Result {
        Log.d(TAG, "processClip: Starting to process clip ${clip.source}")
        Log.d(TAG, "processClip: clip.startTime=${clip.startTime}ms, clip.endTime=${clip.endTime}ms, clip.duration=${clip.duration}ms")

        val extractor = MediaExtractor().apply { setDataSource(context, clip.source, null) }
        val decoder = createDecoder(extractor, decoderOutputSurface.surface)
            ?: throw RuntimeException("Failed to create video decoder for ${clip.source}")

        var framesInClip = 0
        // ★ 追加：未宣言だったワーク変数を定義
        val bufferInfo = MediaCodec.BufferInfo()
        var isInputDone = false
        var isOutputDone = false
        try {
            Log.d(TAG, "processClip: Starting decoder")
            decoder.start()
            val videoTrackIndex = extractor.findTrack("video/") ?: -1
            Log.d(TAG, "processClip: Video track index: $videoTrackIndex")
            extractor.selectTrack(videoTrackIndex)
            extractor.seekTo(clip.startTime * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
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
                            withContext(glCoroutineContext) {
                                // GL スレッドで描画
                                decoderOutputSurface.awaitNewImage(encoderInputSurface)
                                decoderOutputSurface.drawImage(encoderInputSurface)
                                // PTS を ns で渡してから swap
                                encoderInputSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000L)
                                encoderInputSurface.swapBuffers()
                                // ===== GPU フェンスで描画完了を待つ =====
                                val sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                                if (sync != 0L) {
                                    val timeoutNs = 50_000_000L // 50ms
                                    val wait = GLES30.glClientWaitSync(sync, 0, timeoutNs)
                                    GLES30.glDeleteSync(sync)
                                    if (wait == GLES30.GL_TIMEOUT_EXPIRED) {
                                        Log.w(TAG, "GL fence wait timed out for frame $framesInClip (proceeding)")
                                    }
                                } else {
                                    Log.w(TAG, "glFenceSync returned 0 (no sync created)")
                                }
                                // ===== ここまでフェンス待ち =====
                            }
                            // フェンス待ち後にエンコーダ出力をノンブロッキングで取得
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
                                Log.w(TAG, "processClip: Encoder did not produce output for frame $framesInClip")
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
                                muxerStartCallback() // Call the callback here
                                // muxerの開始は全トラック追加後に外部で行う
                            }
                        }
                    }
                    encoderStatus >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                        if (bufferInfo.size != 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            synchronized(muxerLock) {
                                if (muxerStarted.get() && trackIndex >= 0) {
                                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                                } else {
                                    // ★ 深いコピーで保留（破棄しない）
                                    encodedData.position(bufferInfo.offset)
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                    val copy = ByteArray(bufferInfo.size)
                                    encodedData.get(copy)
                                    pendingVideo.addLast(
                                        EncodedSample(copy, bufferInfo.presentationTimeUs, bufferInfo.flags)
                                    )
                                }
                            }
                            // ★ 出力は出たので、進捗カウントは増やす（レンダループが進む）
                            outputCount++
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
        muxerStarted.set(started)
        if (started) {
            synchronized(muxerLock) { flushPendingVideoLocked() }
        }
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
                if (bufferInfo.size != 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    synchronized(muxerLock) {
                        if (trackIndex >= 0 && muxerStarted.get()) {
                            Log.d(TAG, "drainEncoder: Writing sample data, size=${bufferInfo.size}, pts=${bufferInfo.presentationTimeUs}us")
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            outputCount++
                        } else {
                            // Muxer未開始の間は捨てずに pending へ退避（深いコピー）
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            val copy = ByteArray(bufferInfo.size)
                            encodedData.get(copy)
                            pendingVideo.addLast(
                                EncodedSample(copy, bufferInfo.presentationTimeUs, bufferInfo.flags)
                            )
                            // ★ データが来た段階でも起動条件を再チェック（タイミング競合の保険）
                            //   ※ addTrack() 側でも呼んでいるが、ここでも念のため
                            //   （弱い呼び出し：起動済みなら何もしない）
                            // muxerStartCallback は外側キャプチャ
                            muxerStartCallback()
                        }
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
    // ★ 追加: エンコーダに渡すべきターゲット仕様
    private val targetSampleRate: Int,
    private val targetChannelCount: Int,
    private val muxerStartCallback: () -> Unit,
    private val TIMEOUT_US: Long = 10000L
) {
    private val TAG = "AudioProcessor"
    data class Result(val durationUs: Long)

    // 音声パスが致命的に失敗した場合のフォールバックフラグ
    @Volatile private var failed: Boolean = false
    fun hasFailed(): Boolean = failed

    private data class EncodedSample(val data: ByteArray, val ptsUs: Long, val flags: Int)
    private val pendingAudio = ArrayDeque<EncodedSample>()

    // ★ 追加: エンコーダに与えた総出力サンプル数（チャンネル無関係の1ch換算）
    private var totalOutSamples: Long = 0

    /**
     * リサンプリング（線形補間・16bit PCM想定）。戻り値は (出力PCM, 出力サンプル数/1ch)。
     * 入力: ByteBuffer(PCM16), bufferInfo(offset/size), 入力レート/チャンネル
     */
    private fun resampleIfNeeded(
        src: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        inSampleRate: Int,
        inChannels: Int
    ): Pair<ByteBuffer, Int> {
        // すでにターゲット仕様ならコピー不要
        if (inSampleRate == targetSampleRate && inChannels == targetChannelCount) {
            val dup = src.duplicate()
            dup.position(bufferInfo.offset)
            dup.limit(bufferInfo.offset + bufferInfo.size)
            return Pair(dup.slice(), (bufferInfo.size / 2 /*bytes*/ / inChannels))
        }

        // チャンネル本数の調整（簡易：多ch→ダウンミックス/少ch→複製）
        // ここでは in->target への最小限の対応
        val srcShort = ShortArray(bufferInfo.size / 2)
        run {
            val dup = src.duplicate()
            dup.position(bufferInfo.offset)
            dup.limit(bufferInfo.offset + bufferInfo.size)
            val sb = dup.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
            sb.get(srcShort)
        }

        // ダウンミックス/アップミックスを中間バッファに整形（interleaved）
        val framesIn = srcShort.size / inChannels
        val interleavedInMono = ShortArray(framesIn) // 1ch仮想（後で必要に応じ複製）
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

        // レート変換（線形補間）
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

        // ターゲットchへ拡張（1→Nch複製）
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

        val audioClip = session.audioTracks.flatMap { it.clips }.find { it.source == clip.source && it.startTime == clip.startTime }
        val extractor = MediaExtractor().apply { setDataSource(context, clip.source, null) }
        val decoder = AudioProcessor.createDecoder(extractor) ?: return Result(durationUs = ((clip.duration / clip.speed) * 1000).toLong()).also { extractor.release() }

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
            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var outputFormatKnown = false

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

                            // ★ リサンプリングしてターゲット仕様へ
                            val (pcmForEncoder, outFrames) = resampleIfNeeded(decodedData, bufferInfo, sampleRate, channelCount)

                            // ★ エンコーダ向けPTSを「これまでにエンコーダへ渡した総サンプル数」から再計算
                            val ptsUsForEncoder =
                                presentationTimeOffsetUs + (totalOutSamples * 1_000_000L / targetSampleRate)

                            val encInfo = MediaCodec.BufferInfo().apply {
                                set(0, pcmForEncoder.remaining(), ptsUsForEncoder, bufferInfo.flags)
                            }
                            try {
                                queueToAudioEncoder(pcmForEncoder, encInfo)
                                drainAudioEncoder(false) // ← クリップ処理中は定期的にdrain（EOSなし）
                            } catch (e: Exception) {
                                Log.e(TAG, "processClip: Audio encoding failed", e)
                                throw e
                            }
                            // 出力サンプル数を加算（1ch換算）
                            totalOutSamples += outFrames.toLong()
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
                        Log.d(TAG, "processClip: audio decoder out format = ${'$'}sampleRate Hz, ch=${'$'}channelCount")
                        Log.d(TAG, "Audio decoder output format changed: $outFormat")
                    } else {
                        Log.d(TAG, "processClip: Audio decoder output buffer index: $outputBufferIndex")
                        decoderOutputAvailable = false
                    }
                }
            }

            // ☆ クリップごとのEOS送信を削除（全クリップ処理後に一度だけ送信）
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
            Log.w(TAG, "queueToAudioEncoder: Failed to get input buffer for encoder, index=$encoderInputIndex")
        }
    }

    fun drainAudioEncoder(endOfStream: Boolean): Boolean {
        if (encoder == null) return true // If no encoder, consider it drained
        val bufferInfo = MediaCodec.BufferInfo()
        var encoderOutputAvailable = false
        try {
            while (true) {
                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) {
                            encoderOutputAvailable = false
                            break
                        } else {
                            encoderOutputAvailable = true // Keep trying if EOS
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
                                                                    Log.w(TAG, "drainAudioEncoder: addTrack failed, fallback to video-only export", e)
                                                                    failed = true
                                                                    // 以降の音声出力は破棄し、video-only で進める
                                                                }
                                                            }
                                                        }                    }
                    encoderStatus >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                        if (!failed && bufferInfo.size != 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            synchronized(muxerLock) {
                                if (muxerStarted.get() && trackIndex >= 0) {
                                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                                } else {
                                    encodedData.position(bufferInfo.offset)
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                    val copy = ByteArray(bufferInfo.size)
                                    encodedData.get(copy)
                                    pendingAudio.addLast(EncodedSample(copy, bufferInfo.presentationTimeUs, bufferInfo.flags))
                                }
                            }
                        }
                        encoder.releaseOutputBuffer(encoderStatus, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
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