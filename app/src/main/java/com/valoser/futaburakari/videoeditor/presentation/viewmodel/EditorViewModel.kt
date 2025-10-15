package com.valoser.futaburakari.videoeditor.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valoser.futaburakari.videoeditor.domain.model.*
import com.valoser.futaburakari.videoeditor.domain.session.EditorSessionManager
import com.valoser.futaburakari.videoeditor.domain.usecase.EditClipUseCase
import com.valoser.futaburakari.videoeditor.domain.usecase.ManageAudioTrackUseCase
import com.valoser.futaburakari.videoeditor.domain.usecase.ExportVideoUseCase
import com.valoser.futaburakari.videoeditor.domain.usecase.ApplyTransitionUseCase
import com.valoser.futaburakari.videoeditor.media.player.PlayerEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * エディタのViewModel（MVIパターン）
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val sessionManager: EditorSessionManager,
    private val editClipUseCase: EditClipUseCase,
    private val manageAudioTrackUseCase: ManageAudioTrackUseCase,
    private val exportVideoUseCase: ExportVideoUseCase,
    private val applyTransitionUseCase: ApplyTransitionUseCase,
    val playerEngine: PlayerEngine,
    val waveformGenerator: com.valoser.futaburakari.videoeditor.media.audio.WaveformGenerator
) : ViewModel() {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // PlayerEngineの現在位置をStateに同期
        viewModelScope.launch {
            playerEngine.currentPosition.collect { position ->
                _state.update { it.copy(playhead = position) }
            }
        }

        // PlayerEngineの再生状態をStateに同期
        viewModelScope.launch {
            playerEngine.isPlaying.collect { isPlaying ->
                _state.update { it.copy(isPlaying = isPlaying) }
            }
        }


    }

    /**
     * インテント（ユーザーアクション）を処理
     */
    fun handleIntent(intent: EditorIntent) {
        viewModelScope.launch {
            when (intent) {
                // セッション管理
                is EditorIntent.CreateSession -> createSession(intent)
                is EditorIntent.ClearSession -> clearSession()

                // クリップ編集
                is EditorIntent.TrimClip -> trimClip(intent)
                is EditorIntent.SplitClip -> splitClip(intent)
                is EditorIntent.DeleteRange -> deleteRange(intent)
                is EditorIntent.DeleteClip -> deleteClip(intent)
                is EditorIntent.MoveClip -> moveClip(intent)
                is EditorIntent.CopyClip -> copyClip(intent)
                is EditorIntent.SetSpeed -> setSpeed(intent)

                // 音声トラック編集
                is EditorIntent.MuteRange -> muteRange(intent)
                is EditorIntent.ReplaceAudio -> replaceAudio(intent)
                is EditorIntent.AddAudioTrack -> addAudioTrack(intent)
                is EditorIntent.SetVolume -> setVolume(intent)
                is EditorIntent.AddVolumeKeyframe -> addVolumeKeyframe(intent)
                is EditorIntent.AddFade -> addFade(intent)
                is EditorIntent.TrimAudioClip -> trimAudioClip(intent)
                is EditorIntent.MoveAudioClip -> moveAudioClip(intent)
                is EditorIntent.DeleteAudioClip -> deleteAudioClip(intent)
                is EditorIntent.CopyAudioClip -> copyAudioClip(intent)
                is EditorIntent.SplitAudioClip -> splitAudioClip(intent)
                is EditorIntent.ToggleMuteAudioClip -> toggleMuteAudioClip(intent)
                is EditorIntent.RemoveVolumeKeyframe -> removeVolumeKeyframe(intent)

                // トランジション
                is EditorIntent.AddTransition -> addTransition(intent)
                is EditorIntent.RemoveTransition -> removeTransition(intent)

                // マーカー
                is EditorIntent.AddMarker -> addMarker(intent)
                is EditorIntent.RemoveMarker -> removeMarker(intent)

                // エクスポート
                is EditorIntent.Export -> export(intent)

                // 再生制御
                is EditorIntent.Play -> play()
                is EditorIntent.Pause -> pause()
                is EditorIntent.SeekTo -> seekTo(intent)

                // Undo/Redo
                is EditorIntent.Undo -> undo()
                is EditorIntent.Redo -> redo()

                // 選択
                is EditorIntent.SelectClip -> selectClip(intent)
                is EditorIntent.ClearSelection -> clearSelection()

                // モード変更
                is EditorIntent.SetEditMode -> setEditMode(intent)

                // ズーム
                is EditorIntent.SetZoom -> setZoom(intent)
                is EditorIntent.SetRangeSelection -> setRangeSelection(intent)
            }
        }
    }

    private suspend fun createSession(intent: EditorIntent.CreateSession) {
        _state.update { it.copy(isLoading = true) }
        sessionManager.createSession(intent.videoUris)
            .onSuccess { session ->
                _state.update {
                    it.copy(
                        session = session,
                        isLoading = false,
                        error = null
                    )
                }
                playerEngine.prepare(session)
                _events.send(EditorEvent.SessionCreated)
            }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = error.message
                    )
                }
                _events.send(EditorEvent.ShowError(error.message ?: "セッションの作成に失敗しました"))
            }
    }

    private suspend fun clearSession() {
        sessionManager.clearSession()
        playerEngine.release()
        _state.update {
            EditorState()
        }
    }

    private suspend fun trimClip(intent: EditorIntent.TrimClip) {
        editClipUseCase.trim(intent.clipId, intent.start, intent.end)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "トリムに失敗しました"))
            }
    }

    private suspend fun splitClip(intent: EditorIntent.SplitClip) {
        editClipUseCase.split(intent.clipId, intent.position)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "分割に失敗しました"))
            }
    }

    private suspend fun deleteRange(intent: EditorIntent.DeleteRange) {
        editClipUseCase.deleteRange(intent.clipId, intent.start, intent.end)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "範囲削除に失敗しました"))
            }
    }

    private suspend fun deleteClip(intent: EditorIntent.DeleteClip) {
        editClipUseCase.delete(intent.clipId)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "削除に失敗しました"))
            }
    }

    private suspend fun moveClip(intent: EditorIntent.MoveClip) {
        editClipUseCase.move(intent.clipId, intent.newPosition)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "移動に失敗しました"))
            }
    }

    private suspend fun copyClip(intent: EditorIntent.CopyClip) {
        editClipUseCase.copy(intent.clipId)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "コピーに失敗しました"))
            }
    }

    private suspend fun setSpeed(intent: EditorIntent.SetSpeed) {
        editClipUseCase.setSpeed(intent.clipId, intent.speed)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "速度変更に失敗しました"))
            }
    }

    private suspend fun muteRange(intent: EditorIntent.MuteRange) {
        manageAudioTrackUseCase.muteRange(
            intent.trackId,
            intent.clipId,
            intent.startTime,
            intent.endTime
        )
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "ミュートに失敗しました"))
            }
    }

    private suspend fun replaceAudio(intent: EditorIntent.ReplaceAudio) {
        manageAudioTrackUseCase.replaceAudio(
            intent.trackId,
            intent.clipId,
            intent.startTime,
            intent.endTime,
            intent.newAudioUri
        )
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "音声差し替えに失敗しました"))
            }
    }

    private suspend fun addAudioTrack(intent: EditorIntent.AddAudioTrack) {
        manageAudioTrackUseCase.addAudioTrack(
            intent.name,
            intent.audioUri,
            intent.position
        )
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "音声トラック追加に失敗しました"))
            }
    }

    private suspend fun setVolume(intent: EditorIntent.SetVolume) {
        manageAudioTrackUseCase.setVolume(
            intent.trackId,
            intent.clipId,
            intent.volume
        )
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "音量設定に失敗しました"))
            }
    }

    private suspend fun addVolumeKeyframe(intent: EditorIntent.AddVolumeKeyframe) {
        manageAudioTrackUseCase.addVolumeKeyframe(
            intent.trackId,
            intent.clipId,
            intent.time,
            intent.value
        )
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "キーフレーム追加に失敗しました"))
            }
    }

    private suspend fun addFade(intent: EditorIntent.AddFade) {
        manageAudioTrackUseCase.addFade(
            intent.trackId,
            intent.clipId,
            intent.fadeType,
            intent.duration
        )
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "フェード追加に失敗しました"))
            }
    }

    private suspend fun trimAudioClip(intent: EditorIntent.TrimAudioClip) {
        manageAudioTrackUseCase.trimAudioClip(intent.trackId, intent.clipId, intent.start, intent.end)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "音声クリップのトリムに失敗しました"))
            }
    }

    private suspend fun moveAudioClip(intent: EditorIntent.MoveAudioClip) {
        manageAudioTrackUseCase.moveAudioClip(intent.trackId, intent.clipId, intent.newPosition)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "音声クリップの移動に失敗しました"))
            }
    }

    private suspend fun deleteAudioClip(intent: EditorIntent.DeleteAudioClip) {
        manageAudioTrackUseCase.deleteAudioClip(intent.trackId, intent.clipId)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "音声クリップの削除に失敗しました"))
            }
    }

    private suspend fun copyAudioClip(intent: EditorIntent.CopyAudioClip) {
        manageAudioTrackUseCase.copyAudioClip(intent.trackId, intent.clipId)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "音声クリップのコピーに失敗しました"))
            }
    }

    private suspend fun splitAudioClip(intent: EditorIntent.SplitAudioClip) {
        manageAudioTrackUseCase.splitAudioClip(intent.trackId, intent.clipId, intent.position)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "音声クリップの分割に失敗しました"))
            }
    }

    private suspend fun toggleMuteAudioClip(intent: EditorIntent.ToggleMuteAudioClip) {
        manageAudioTrackUseCase.toggleMuteAudioClip(intent.trackId, intent.clipId)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "ミュートの切り替えに失敗しました"))
            }
    }

    private suspend fun removeVolumeKeyframe(intent: EditorIntent.RemoveVolumeKeyframe) {
        manageAudioTrackUseCase.removeVolumeKeyframe(intent.trackId, intent.clipId, intent.keyframe)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "キーフレームの削除に失敗しました"))
            }
    }

    private suspend fun addTransition(intent: EditorIntent.AddTransition) {
        applyTransitionUseCase.addTransition(
            intent.clipId,
            intent.type,
            intent.duration
        )
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "トランジション追加に失敗しました"))
            }
    }

    private suspend fun removeTransition(intent: EditorIntent.RemoveTransition) {
        applyTransitionUseCase.removeTransition(intent.position)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                playerEngine.prepare(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "トランジション削除に失敗しました"))
            }
    }

    private suspend fun addMarker(intent: EditorIntent.AddMarker) {
        val session = _state.value.session
            ?: run {
                _events.send(EditorEvent.ShowError("セッションがありません"))
                return
            }

        val newMarker = Marker(time = intent.time, label = intent.label)
        val updatedSession = session.copy(
            markers = session.markers + newMarker
        )

        sessionManager.updateSession(updatedSession)
            .onSuccess {
                _state.update { it.copy(session = updatedSession) }
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "マーカー追加に失敗しました"))
            }
    }

    private suspend fun removeMarker(intent: EditorIntent.RemoveMarker) {
        val session = _state.value.session
            ?: run {
                _events.send(EditorEvent.ShowError("セッションがありません"))
                return
            }

        val updatedSession = session.copy(
            markers = session.markers.filter { it.time != intent.time }
        )

        sessionManager.updateSession(updatedSession)
            .onSuccess {
                _state.update { it.copy(session = updatedSession) }
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "マーカー削除に失敗しました"))
            }
    }

    private suspend fun export(intent: EditorIntent.Export) {
        val session = _state.value.session
            ?: run {
                _events.send(EditorEvent.ShowError("セッションがありません"))
                return
            }

        _state.update { it.copy(isLoading = true) }

        try {
            exportVideoUseCase.export(session, intent.preset, intent.outputUri)
                .collect { progress ->
                    // エクスポート進捗を更新（必要に応じてstateに追加）
                    if (progress.percentage >= 100f) {
                        _state.update { it.copy(isLoading = false) }
                        _events.send(EditorEvent.ExportComplete)
                        _events.send(EditorEvent.ShowSuccess("エクスポートが完了しました"))
                    }
                }
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false) }
            _events.send(EditorEvent.ShowError(e.message ?: "エクスポートに失敗しました"))
        }
    }

    private fun play() {
        playerEngine.play()
        _state.update { it.copy(isPlaying = true) }
    }

    private fun pause() {
        playerEngine.pause()
        _state.update { it.copy(isPlaying = false) }
    }

    private fun seekTo(intent: EditorIntent.SeekTo) {
        playerEngine.seekTo(intent.timeMs)
        _state.update { it.copy(playhead = intent.timeMs) }
    }

    private suspend fun undo() {
        sessionManager.undo()?.let { session ->
            _state.update { it.copy(session = session) }
            playerEngine.prepare(session)
        }
    }

    private suspend fun redo() {
        sessionManager.redo()?.let { session ->
            _state.update { it.copy(session = session) }
            playerEngine.prepare(session)
        }
    }

    private fun selectClip(intent: EditorIntent.SelectClip) {
        _state.update { it.copy(selection = intent.selection) }
    }

    private fun clearSelection() {
        _state.update { it.copy(selection = null) }
    }

    private fun setEditMode(intent: EditorIntent.SetEditMode) {
        _state.update { it.copy(mode = intent.mode) }
    }

    private fun setZoom(intent: EditorIntent.SetZoom) {
        _state.update { it.copy(zoom = intent.zoom) }
    }

    private fun setRangeSelection(intent: EditorIntent.SetRangeSelection) {
        _state.update {
            it.copy(rangeSelection = TimeRange.fromMillis(intent.start, intent.end))
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerEngine.release()
    }
}
