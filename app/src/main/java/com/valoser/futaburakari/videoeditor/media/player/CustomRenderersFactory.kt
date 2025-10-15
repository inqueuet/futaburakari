package com.valoser.futaburakari.videoeditor.media.player

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.valoser.futaburakari.videoeditor.media.audio.VideoEditorAudioSink

class CustomRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink, // Use the provided default AudioSink
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
    ) {
        val audioRenderer = MediaCodecAudioRenderer(
            context,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            audioSink // Pass the default sink
        )
        out.add(audioRenderer)
    }
}