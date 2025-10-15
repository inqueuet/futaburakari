package com.valoser.futaburakari.videoeditor.media.audio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import java.nio.ByteBuffer

class VideoEditorAudioSink : AudioSink {

    private var session: EditorSession? = null
    private lateinit var inputFormat: Format
    private var listener: AudioSink.Listener? = null

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
        // TODO: This should return the position based on the audio frames we have processed.
        return 0
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        this.inputFormat = inputFormat
        // TODO: Initialize AudioTrack, decoders for audio clips, resamplers, etc.
    }

    override fun play() {
        // TODO: Start the AudioTrack and begin feeding it data.
    }

    override fun handleDiscontinuity() {
        // TODO: Handle discontinuity.
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // This is where the magic happens.
        // The incoming buffer is from the video's audio track, which we might ignore.
        // We need to decode our own audio tracks (from the session) and mix them.
        // Then we write the mixed buffer to the AudioTrack.
        // This implementation is highly complex.

        // For now, we just consume the buffer to avoid blocking the pipeline.
        buffer.position(buffer.limit())
        return true
    }

    override fun playToEndOfStream() {
        // TODO: Play out any remaining buffered data.
    }

    override fun isEnded(): Boolean {
        // TODO: Return true when all audio has been played.
        return true
    }

    override fun hasPendingData(): Boolean {
        // TODO: Return true if there is data that needs to be played.
        return false
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // TODO: Handle speed changes, may require a resampler.
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return PlaybackParameters.DEFAULT
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        // TODO: Not implemented
    }

    override fun getSkipSilenceEnabled(): Boolean {
        return false
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
    }

    override fun getAudioAttributes(): AudioAttributes? {
        return null
    }

    override fun setAudioSessionId(audioSessionId: Int) {
    }

    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {
    }

    override fun enableTunnelingV21() {
    }

    override fun disableTunneling() {
    }

    override fun setVolume(volume: Float) {
    }

    override fun getAudioTrackBufferSizeUs(): Long {
        return 0
    }

    override fun pause() {
        // TODO: Pause the AudioTrack.
    }

    override fun flush() {
        // TODO: Flush all pending data.
    }

    override fun reset() {
        // TODO: Reset the sink to its initial state.
    }
}
