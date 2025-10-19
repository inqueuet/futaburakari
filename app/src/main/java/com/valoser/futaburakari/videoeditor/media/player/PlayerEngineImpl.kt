package com.valoser.futaburakari.videoeditor.media.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import com.valoser.futaburakari.videoeditor.domain.model.VideoClip
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

/**
 * PlayerEngineの実装（Media3 ExoPlayer使用）
 */
 @Singleton
class PlayerEngineImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PlayerEngine {

    private val renderersFactory = CustomRenderersFactory(context)
    override val player: ExoPlayer

    // 直近に準備したセッションを保持（絶対時刻→ウィンドウ変換に使用）
    private var lastSession: EditorSession? = null
    private var sortedClips: List<VideoClip> = emptyList()

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
            // ExoPlayerのウィンドウ内位置をタイムライン絶対位置に変換
            val absolutePosition = convertToAbsolutePosition()
            _currentPosition.value = absolutePosition
            if (_isPlaying.value) {
                handler.postDelayed(positionUpdateRunnable, POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    /** 通常の prepare */
    override fun prepare(session: EditorSession) {
        val mediaItems = session.videoClips
            .sortedBy { it.position }
            .map { clip ->
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
        lastSession = session
        sortedClips = session.videoClips.sortedBy { it.position }
    }

    /** READY になるまで待機する prepare */
    override suspend fun prepareAndAwaitReady(session: EditorSession) {
        val mediaItems = session.videoClips
            .sortedBy { it.position }
            .map { clip ->
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

        val ready = CompletableDeferred<Unit>()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    ready.complete(Unit)
                    player.removeListener(this)
                }
            }
        })

        player.setMediaItems(mediaItems)
        player.prepare()
        withTimeout(15000) { ready.await() } // ★ 15秒タイムアウトに延長
        lastSession = session
        sortedClips = session.videoClips.sortedBy { it.position }
    }

    override fun play() { player.play() }

    override fun pause() { player.pause() }

    /** セッション絶対時刻 → (windowIndex, positionInWindow) に変換して seek */
    override fun seekTo(timeMs: Long) {
        val session = lastSession
        if (session == null || session.videoClips.isEmpty()) {
            player.seekTo(timeMs)
            _currentPosition.value = timeMs
            return
        }
        // ★ session.videoClipsの順序はMediaItemsの追加順(position順)と一致している必要がある
        // prepare()で追加した順序と同じものを使用(position順ソート)
        val clips = ensureSortedClips(session)
        
        // タイムライン上の位置からクリップを検索
        val index = clips.indexOfFirst { c ->
            val start = c.position
            val end = c.position + c.duration
            timeMs in start until end
        }.let { idx ->
            when {
                idx >= 0 -> idx
                timeMs < clips.first().position -> 0
                else -> clips.lastIndex
            }
        }

        val clip = clips[index]
        val posInWindow = (timeMs - clip.position)
            .coerceIn(0L, (clip.duration - 1).coerceAtLeast(0L))

        player.seekTo(index, posInWindow)
        _currentPosition.value = timeMs
    }

    override fun setRate(rate: Float) { player.setPlaybackSpeed(rate) }

    override fun reset() {
        player.stop()
        player.clearMediaItems()
        lastSession = null
        sortedClips = emptyList()
        _currentPosition.value = 0L
        _isPlaying.value = false
    }

    override fun release() {
        handler.removeCallbacks(positionUpdateRunnable)
        player.release()
    }

    /**
     * ExoPlayerのウィンドウ内位置をタイムライン絶対位置に変換
     */
    private fun convertToAbsolutePosition(): Long {
        val session = lastSession ?: return player.currentPosition
        val windowIndex = player.currentMediaItemIndex
        val positionInWindow = player.currentPosition
        val clips = ensureSortedClips(session)

        val currentClip = clips.getOrNull(windowIndex)
        
        if (currentClip == null) {
            // フォールバック
            return player.currentPosition
        }

        // タイムライン上の絶対位置 = クリップの開始位置 + ウィンドウ内位置
        return currentClip.position + positionInWindow
    }

    private companion object {
        private const val POSITION_UPDATE_INTERVAL_MS = 33L
    }

    private fun ensureSortedClips(session: EditorSession): List<VideoClip> {
        val current = sortedClips
        if (current.isNotEmpty() && current.size == session.videoClips.size) {
            return current
        }
        return session.videoClips.sortedBy { it.position }.also { sortedClips = it }
    }
}
