package com.valoser.futaburakari.videoeditor.domain.model

import android.graphics.Bitmap

/**
 * エディタの状態（MVI パターン）
 */
data class EditorState(
    val session: EditorSession? = null,
    val mode: EditMode = EditMode.NORMAL,
    val selection: Selection? = null,
    val playhead: Long = 0L,
    val isPlaying: Boolean = false,
    val zoom: Float = 0.25f,
    val isLoading: Boolean = false,
    val exportProgress: Float? = null,
    val error: String? = null,
    val rangeSelection: TimeRange? = null,
    val splitMarkerPosition: Long? = null
)

/**
 * エディタのインテント（ユーザーアクション）
 */
sealed class EditorIntent {
    // セッション管理
    data class CreateSession(val videoUris: List<android.net.Uri>) : EditorIntent()
    object ClearSession : EditorIntent()

    // クリップ編集
    data class TrimClip(val clipId: String, val start: Long, val end: Long) : EditorIntent()
    data class SplitClip(val clipId: String, val position: Long) : EditorIntent()
    object SplitAtPlayhead : EditorIntent()
    data class DeleteRange(val clipId: String, val start: Long, val end: Long) : EditorIntent()
    data class DeleteClip(val clipId: String) : EditorIntent()
    data class MoveClip(val clipId: String, val newPosition: Long) : EditorIntent()
    data class CopyClip(val clipId: String) : EditorIntent()
    data class SetSpeed(val clipId: String, val speed: Float) : EditorIntent()

    // 音声トラック編集
    data class MuteRange(
        val trackId: String,
        val clipId: String,
        val startTime: Long,
        val endTime: Long
    ) : EditorIntent()

    data class ReplaceAudio(
        val trackId: String,
        val clipId: String,
        val startTime: Long,
        val endTime: Long,
        val newAudioUri: android.net.Uri
    ) : EditorIntent()

    data class AddAudioTrack(
        val name: String,
        val audioUri: android.net.Uri?,
        val position: Long
    ) : EditorIntent()

    data class SetVolume(
        val trackId: String,
        val clipId: String,
        val volume: Float
    ) : EditorIntent()

    data class AddVolumeKeyframe(
        val trackId: String,
        val clipId: String,
        val time: Long,
        val value: Float
    ) : EditorIntent()

    data class AddFade(
        val trackId: String,
        val clipId: String,
        val fadeType: FadeType,
        val duration: FadeDuration
    ) : EditorIntent()

    data class TrimAudioClip(
        val trackId: String,
        val clipId: String,
        val start: Long,
        val end: Long
    ) : EditorIntent()

    data class MoveAudioClip(
        val trackId: String,
        val clipId: String,
        val newPosition: Long
    ) : EditorIntent()

    data class DeleteAudioClip(val trackId: String, val clipId: String) : EditorIntent()

    data class CopyAudioClip(val trackId: String, val clipId: String) : EditorIntent()

    data class SplitAudioClip(val trackId: String, val clipId: String, val position: Long) : EditorIntent()

    data class ToggleMuteAudioClip(val trackId: String, val clipId: String) : EditorIntent()

    data class RemoveVolumeKeyframe(val trackId: String, val clipId: String, val keyframe: Keyframe) : EditorIntent()

    // トランジション
    data class AddTransition(
        val clipId: String,
        val type: TransitionType,
        val duration: Long
    ) : EditorIntent()

    data class RemoveTransition(val position: Long) : EditorIntent()

    // マーカー
    data class AddMarker(val time: Long, val label: String) : EditorIntent()
    data class RemoveMarker(val time: Long) : EditorIntent()

    // エクスポート
    data class Export(
        val outputUri: android.net.Uri
    ) : EditorIntent()

    // 再生制御
    object Play : EditorIntent()
    object Pause : EditorIntent()
    data class SeekTo(val timeMs: Long) : EditorIntent()

    // Undo/Redo
    object Undo : EditorIntent()
    object Redo : EditorIntent()

    // 選択
    data class SelectClip(val selection: Selection) : EditorIntent()
    object ClearSelection : EditorIntent()

    // モード変更
    data class SetEditMode(val mode: EditMode) : EditorIntent()

    // ズーム
    data class SetZoom(val zoom: Float) : EditorIntent()

    // 範囲選択
    data class SetRangeSelection(val start: Long, val end: Long) : EditorIntent()
    data class DeleteTimeRange(val start: Long, val end: Long, val ripple: Boolean = true) : EditorIntent()
}

/**
 * エディタのイベント（一度きりのイベント）
 */
sealed class EditorEvent {
    data class ShowError(val message: String) : EditorEvent()
    data class ShowSuccess(val message: String) : EditorEvent()
    object SessionCreated : EditorEvent()
    object ExportComplete : EditorEvent()
}
