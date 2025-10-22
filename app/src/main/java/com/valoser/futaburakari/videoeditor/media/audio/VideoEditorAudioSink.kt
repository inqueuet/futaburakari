package com.valoser.futaburakari.videoeditor.media.audio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import java.nio.ByteBuffer
import android.media.AudioFormat
import android.media.AudioTrack

class VideoEditorAudioSink : AudioSink {

    private var session: EditorSession? = null
    private lateinit var inputFormat: Format
    private var listener: AudioSink.Listener? = null
    private var frameworkAttrs: android.media.AudioAttributes? = null
    private var audioTrack: AudioTrack? = null
    private var audioSessionId: Int = 0
    private var channelCount: Int = 2
    private var sampleRate: Int = 44100
    private var bufferSizeInBytes: Int = 0
    private var framesWritten: Long = 0
    private var lastPlaybackHeadPosition: Long = 0
    private var sourceEnded: Boolean = false
    private var isPlaying: Boolean = false
    // ↑ フィールドはこの1か所に集約（以下の重複クラス宣言と重複フィールドは削除）

    fun setSession(session: EditorSession) {
        this.session = session
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean {
        // We will handle PCM 16-bit audio from the decoder.
        return MimeTypes.AUDIO_RAW == format.sampleMimeType && format.pcmEncoding == C.ENCODING_PCM_16BIT
    }

    override fun getFormatSupport(format: Format): Int {
        return if (supportsFormat(format)) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!this::inputFormat.isInitialized) return 0
        
        // ⭐ 実際に再生された位置を取得
        val at = audioTrack ?: return 0
        if (!isPlaying) return 0
        
        // ★ ラップアラウンド対策
        val currentPosition = at.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        
        // 巻き戻りを検出(ラップアラウンド発生)
        if (currentPosition < lastPlaybackHeadPosition) {
            // 2^32フレーム分を加算
            val wrappedFrames = (1L shl 32) + currentPosition
            lastPlaybackHeadPosition = currentPosition
            return (wrappedFrames * 1_000_000L) / sampleRate
        }
        
        lastPlaybackHeadPosition = currentPosition
        val playedFrames = currentPosition
        return (playedFrames * 1_000_000L) / sampleRate
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        this.inputFormat = inputFormat
        this.sampleRate = inputFormat.sampleRate
        this.channelCount = inputFormat.channelCount
        val channelConfig =
            if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

        // 既存のトラックを解放
        audioTrack?.release()
        bufferSizeInBytes = AudioTrack.getMinBufferSize(
            sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(specifiedBufferSize.takeIf { it > 0 } ?: 0)

        val attrs = frameworkAttrs ?: android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSizeInBytes)
            .also { if (audioSessionId != 0) it.setSessionId(audioSessionId) }
            .build()

        framesWritten = 0
        sourceEnded = false
        isPlaying = false
    }

    override fun play() {
        audioTrack?.play()
        isPlaying = true
    }

    override fun handleDiscontinuity() {
        // NOP（最小実装）
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // 最小実装：入力 PCM16 をそのまま AudioTrack へ非ブロッキング書き込み
        val at = audioTrack ?: return true.also { buffer.position(buffer.limit()) }
        if (!isPlaying) at.play()

        val remaining = buffer.remaining()
        if (remaining == 0) return true

        val written = at.write(buffer, remaining, AudioTrack.WRITE_NON_BLOCKING)
        if (written > 0) {
            // 1フレーム = (16bit * ch) / 16bit = ch サンプル分
            val frames = written / (2 * channelCount)
            framesWritten += frames
        }
        // すべて消費できたら true、未消費があれば false（ExoPlayer が再度呼ぶ）
        return written >= remaining
    }

    override fun playToEndOfStream() {
        sourceEnded = true
    }

    override fun isEnded(): Boolean {
        return sourceEnded && !hasPendingData()
    }

    override fun hasPendingData(): Boolean {
        val at = audioTrack ?: return false
        // 再生済みフレーム（wrap 対策の簡易版）
        val playedFrames = at.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return playedFrames < framesWritten
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // 速度変更は未対応（最小実装）
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return PlaybackParameters.DEFAULT
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        // 未対応（最小実装）
    }

    override fun getSkipSilenceEnabled(): Boolean {
        return false
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        // Framework の AudioAttributes にマッピング
        frameworkAttrs = android.media.AudioAttributes.Builder()
            .setUsage(
                when (audioAttributes.usage) {
                    C.USAGE_ALARM -> android.media.AudioAttributes.USAGE_ALARM
                    C.USAGE_ASSISTANCE_SONIFICATION -> android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                    C.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                    else -> android.media.AudioAttributes.USAGE_MEDIA
                }
            )
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    override fun getAudioAttributes(): AudioAttributes? {
        // Exo の属性オブジェクト自体は保持していないため null のまま
        return null
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
        // 既存の AudioTrack があれば次回 configure で反映
    }

    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {
        // 未対応（最小実装）
    }

    override fun enableTunnelingV21() {
        // 未対応（最小実装）
    }

    override fun disableTunneling() {
        // 未対応（最小実装）
    }

    override fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume)
    }

    override fun getAudioTrackBufferSizeUs(): Long {
        if (bufferSizeInBytes <= 0) return 0
        val frames = bufferSizeInBytes / (2 * channelCount)
        return (frames * 1_000_000L) / sampleRate
    }

    override fun pause() {
        audioTrack?.pause()
        isPlaying = false
    }

    override fun flush() {
        audioTrack?.flush()
        framesWritten = 0
    }

    override fun reset() {
        try {
            audioTrack?.release()
        } catch (_: Throwable) { }
        audioTrack = null
        framesWritten = 0
        sourceEnded = false
        isPlaying = false
    }
}
