package com.valoser.futaburakari.videoeditor.domain.usecase

import android.net.Uri
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import com.valoser.futaburakari.videoeditor.domain.model.FadeType
import com.valoser.futaburakari.videoeditor.domain.model.FadeDuration

/**
 * 音声トラック管理のユースケース
 */
interface ManageAudioTrackUseCase {
    /**
     * 範囲をミュート（無音化）
     */
    suspend fun muteRange(
        trackId: String,
        clipId: String,
        startTime: Long,
        endTime: Long
    ): Result<EditorSession>

    /**
     * 音声を差し替え
     */
    suspend fun replaceAudio(
        trackId: String,
        clipId: String,
        startTime: Long,
        endTime: Long,
        newAudioUri: Uri
    ): Result<EditorSession>

    /**
     * 音声トラックを追加
     */
    suspend fun addAudioTrack(
        name: String,
        audioUri: Uri?,
        position: Long
    ): Result<EditorSession>

    /**
     * 音量を設定
     */
    suspend fun setVolume(
        trackId: String,
        clipId: String,
        volume: Float
    ): Result<EditorSession>

    /**
     * 音量キーフレームを追加
     */
    suspend fun addVolumeKeyframe(
        trackId: String,
        clipId: String,
        time: Long,
        value: Float
    ): Result<EditorSession>

    /**
     * フェードイン/アウトを追加
     */
    suspend fun addFade(
        trackId: String,
        clipId: String,
        fadeType: FadeType,
        duration: FadeDuration
    ): Result<EditorSession>

    /**
     * 音声クリップをトリム
     */
    suspend fun trimAudioClip(
        trackId: String,
        clipId: String,
        startTime: Long,
        endTime: Long
    ): Result<EditorSession>

    /**
     * 音声クリップを移動
     */
    suspend fun moveAudioClip(
        trackId: String,
        clipId: String,
        newPosition: Long
    ): Result<EditorSession>

    /**
     * 音声クリップを削除
     */
    suspend fun deleteAudioClip(
        trackId: String,
        clipId: String
    ): Result<EditorSession>

    /**
     * 音声クリップをコピー
     */
    suspend fun copyAudioClip(
        trackId: String,
        clipId: String
    ): Result<EditorSession>

    /**
     * 音声クリップを分割
     */
    suspend fun splitAudioClip(
        trackId: String,
        clipId: String,
        position: Long
    ): Result<EditorSession>

    /**
     * 音声クリップのミュートを切り替え
     */
    suspend fun toggleMuteAudioClip(
        trackId: String,
        clipId: String
    ): Result<EditorSession>

    /**
     * 音量キーフレームを削除
     */
    suspend fun removeVolumeKeyframe(
        trackId: String,
        clipId: String,
        keyframe: com.valoser.futaburakari.videoeditor.domain.model.Keyframe
    ): Result<EditorSession>
}
