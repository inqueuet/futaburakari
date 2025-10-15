package com.valoser.futaburakari.videoeditor.media.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * PlayerEngineの実装（Media3 ExoPlayer使用）
 */
@Singleton
class PlayerEngineImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PlayerEngine {

    private val renderersFactory = CustomRenderersFactory(context)
    override val player: ExoPlayer

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentPosition = MutableStateFlow(0L)
    override val currentPosition: StateFlow<Long> = _currentPosition

    private val handler = Handler(Looper.getMainLooper())
    private val positionUpdateRunnable: Runnable

    init {
        player = ExoPlayer.Builder(context, renderersFactory).build().apply {
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    if (isPlaying) {
                        handler.post(positionUpdateRunnable)
                    } else {
                        handler.removeCallbacks(positionUpdateRunnable)
                    }
                }
            })
        }

        positionUpdateRunnable = Runnable {
            _currentPosition.value = player.currentPosition
            handler.postDelayed(positionUpdateRunnable, 100) // Update every 100ms
        }
    }

    override fun prepare(session: EditorSession) {
        val mediaItems = session.videoClips.map { clip ->
            MediaItem.Builder()
                .setUri(clip.source)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startTime)
                        .setEndPositionMs(clip.endTime)
                        .build()
                )
                .build()
        }

        player.setMediaItems(mediaItems)
        player.prepare()
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(timeMs: Long) {
        player.seekTo(timeMs)
        _currentPosition.value = timeMs
    }

    override fun setRate(rate: Float) {
        player.setPlaybackSpeed(rate)
    }

    override fun release() {
        handler.removeCallbacks(positionUpdateRunnable)
        player.release()
    }
}
