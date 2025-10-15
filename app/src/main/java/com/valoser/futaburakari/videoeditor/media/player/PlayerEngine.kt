package com.valoser.futaburakari.videoeditor.media.player

import android.graphics.Bitmap
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import kotlinx.coroutines.flow.StateFlow

/**
 * プレイヤーエンジン インターフェース
 */
interface PlayerEngine {
    /**
     * セッションを準備
     */
    fun prepare(session: EditorSession)

    /**
     * 再生
     */
    fun play()

    /**
     * 一時停止
     */
    fun pause()

    /**
     * シーク
     */
    fun seekTo(timeMs: Long)

    /**
     * 再生速度を設定
     */
    fun setRate(rate: Float)

    /**
     * リソースを解放
     */
    fun release()

    /**
     * 現在の位置（ミリ秒）
     */
    val currentPosition: StateFlow<Long>

    /**
     * 再生中かどうか
     */
    val isPlaying: StateFlow<Boolean>

    /**
     * ExoPlayerインスタンス
     */
    val player: androidx.media3.exoplayer.ExoPlayer
}
