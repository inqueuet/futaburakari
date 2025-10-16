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
import kotlinx.coroutines.delay
import java.util.UUID
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

    private val _suppressPositionSync = MutableStateFlow(false)

    init {
        // PlayerEngineの現在位置をStateに同期
        viewModelScope.launch {
            playerEngine.currentPosition.collect { position ->
                if (!_suppressPositionSync.value) {
                    _state.update { it.copy(playhead = position) }
                }
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
                is EditorIntent.SplitAtPlayhead -> splitAtPlayhead()
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
                is EditorIntent.DeleteTimeRange -> deleteTimeRange(intent)
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
                prepareSessionPreservePosition(session, targetMs = 0L)
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
        playerEngine.reset()
        _state.update {
            EditorState()
        }
    }

    private suspend fun trimClip(intent: EditorIntent.TrimClip) {
        editClipUseCase.trim(intent.clipId, intent.start, intent.end)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                prepareSessionPreservePosition(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "トリムに失敗しました"))
            }
    }

    private suspend fun splitClip(intent: EditorIntent.SplitClip) {
        editClipUseCase.split(intent.clipId, intent.position)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                prepareSessionPreservePosition(session)   // ← これに差し替え
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
        android.util.Log.d("EditorViewModel", "deleteClip called with clipId: ${intent.clipId}")
        val currentSession = _state.value.session
        if (currentSession == null) {
            android.util.Log.e("EditorViewModel", "deleteClip: currentSession is null")
            return
        }

        android.util.Log.d("EditorViewModel", "Current session has ${currentSession.videoClips.size} clips")
        android.util.Log.d("EditorViewModel", "Clip IDs in session: ${currentSession.videoClips.map { it.id }}")

        // Undo用に現在の状態を保存
        sessionManager.saveState(currentSession)

        editClipUseCase.delete(intent.clipId)
            .onSuccess { session ->
                android.util.Log.d("EditorViewModel", "deleteClip success, new session has ${session.videoClips.size} clips")
                _state.update {
                    it.copy(
                        session = session,
                        selection = null  // 削除後は選択をクリア
                    )
                }
                prepareSessionPreservePosition(session)
            }
            .onFailure { error ->
                android.util.Log.e("EditorViewModel", "deleteClip failed: ${error.message}", error)
                _events.send(EditorEvent.ShowError(error.message ?: "削除に失敗しました"))
            }
    }

    private suspend fun moveClip(intent: EditorIntent.MoveClip) {
        editClipUseCase.move(intent.clipId, intent.newPosition)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                prepareSessionPreservePosition(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "移動に失敗しました"))
            }
    }

    private suspend fun copyClip(intent: EditorIntent.CopyClip) {
        editClipUseCase.copy(intent.clipId)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                prepareSessionPreservePosition(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "コピーに失敗しました"))
            }
    }

    private suspend fun setSpeed(intent: EditorIntent.SetSpeed) {
        editClipUseCase.setSpeed(intent.clipId, intent.speed)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                prepareSessionPreservePosition(session)
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
                prepareSessionPreservePosition(session)
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
                prepareSessionPreservePosition(session)
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
                prepareSessionPreservePosition(session)
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
                prepareSessionPreservePosition(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "音声クリップのトリムに失敗しました"))
            }
    }

    private suspend fun moveAudioClip(intent: EditorIntent.MoveAudioClip) {
        manageAudioTrackUseCase.moveAudioClip(intent.trackId, intent.clipId, intent.newPosition)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                prepareSessionPreservePosition(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "音声クリップの移動に失敗しました"))
            }
    }

    private suspend fun deleteAudioClip(intent: EditorIntent.DeleteAudioClip) {
        manageAudioTrackUseCase.deleteAudioClip(intent.trackId, intent.clipId)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                prepareSessionPreservePosition(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "音声クリップの削除に失敗しました"))
            }
    }

    private suspend fun copyAudioClip(intent: EditorIntent.CopyAudioClip) {
        manageAudioTrackUseCase.copyAudioClip(
            intent.trackId,
            intent.clipId
        )
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                prepareSessionPreservePosition(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "音声クリップのコピーに失敗しました"))
            }
    }

    private suspend fun splitAudioClip(intent: EditorIntent.SplitAudioClip) {
        manageAudioTrackUseCase.splitAudioClip(intent.trackId, intent.clipId, intent.position)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                prepareSessionPreservePosition(session)
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
                prepareSessionPreservePosition(session)
            }
            .onFailure { error ->
                _events.send(EditorEvent.ShowError(error.message ?: "トランジション追加に失敗しました"))
            }
    }

    private suspend fun removeTransition(intent: EditorIntent.RemoveTransition) {
        applyTransitionUseCase.removeTransition(intent.position)
            .onSuccess { session ->
                _state.update { it.copy(session = session) }
                prepareSessionPreservePosition(session)
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

        android.util.Log.d("EditorViewModel", "=== Export Called ===")
        android.util.Log.d("EditorViewModel", "Session has ${session.videoClips.size} clips")
        session.videoClips.forEachIndexed { index, clip ->
            android.util.Log.d("EditorViewModel", "Clip $index: duration=${clip.duration}ms, position=${clip.position}ms")
        }

        // SessionManagerも同期
        sessionManager.updateSession(session)

        _state.update { it.copy(isLoading = true) }

        try {
            exportVideoUseCase.export(session, intent.preset, intent.outputUri)
                .collect { progress ->
                    android.util.Log.d("EditorViewModel", "Export progress: ${progress.percentage}%")
                    // エクスポート進捗を更新（必要に応じてstateに追加）
                    if (progress.percentage >= 100f) {
                        _state.update { it.copy(isLoading = false) }
                        _events.send(EditorEvent.ExportComplete)
                        _events.send(EditorEvent.ShowSuccess("エクスポートが完了しました"))
                    }
                }
        } catch (e: Exception) {
            android.util.Log.e("EditorViewModel", "Export failed", e)
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
        // 再構築中は UI→Player のシークを抑制してズレを防ぐ
        if (_suppressPositionSync.value) return
        playerEngine.seekTo(intent.timeMs)
        _state.update { it.copy(playhead = intent.timeMs) }
    }

    private suspend fun undo() {
        sessionManager.undo()?.let { session ->
            _state.update { it.copy(session = session) }
            prepareSessionPreservePosition(session)
        }
    }

    private suspend fun redo() {
        sessionManager.redo()?.let { session ->
            _state.update { it.copy(session = session) }
            prepareSessionPreservePosition(session)
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

    private suspend fun splitAtPlayhead() {
        val session = _state.value.session ?: return
        val playhead = _state.value.playhead

        // Push current state for undo
        sessionManager.saveState(session)

        // Set a temporary visual marker for the split attempt
        _state.update { it.copy(splitMarkerPosition = playhead) }

        // Clear the marker after a short delay
        viewModelScope.launch {
            kotlinx.coroutines.delay(500) // 0.5 seconds
            _state.update { it.copy(splitMarkerPosition = null) }
        }

        var newSelectedVideoId: String? = null
        var newSelectedAudio: Pair<String, String>? = null // trackId to clipId

        val newVideoClips = mutableListOf<VideoClip>()
        for (clip in session.videoClips.sortedBy { it.position }) {
            val start = clip.position
            val end = clip.position + clip.duration
            if (playhead <= start || playhead >= end) {
                newVideoClips += clip
            } else {
                val leftDuration = playhead - start
                val rightDuration = end - playhead

                val left = clip.copy(
                    id = generateId(),
                    startTime = clip.startTime,
                    endTime = clip.startTime + leftDuration
                )
                val right = clip.copy(
                    id = generateId(),
                    position = playhead,
                    startTime = clip.startTime + leftDuration,
                    endTime = clip.endTime
                )
                newVideoClips += left
                newVideoClips += right

                if (newSelectedVideoId == null) {
                    newSelectedVideoId = right.id
                }
            }
        }

        val newAudioTracks = session.audioTracks.map { track ->
            val newClips = mutableListOf<AudioClip>()
            for (clip in track.clips.sortedBy { it.position }) {
                val start = clip.position
                val end = clip.position + clip.duration
                if (playhead <= start || playhead >= end) {
                    newClips += clip
                } else {
                    val leftDur = playhead - start
                    val rightDur = end - playhead

                    val leftKeys = clip.volumeKeyframes.filter { it.time <= leftDur }
                    val rightKeys = clip.volumeKeyframes
                        .filter { it.time > leftDur }
                        .map { it.copy(time = it.time - leftDur) } // Shift origin

                    val left = clip.copy(
                        id = generateId(),
                        startTime = clip.startTime,
                        endTime = clip.startTime + leftDur,
                        volumeKeyframes = leftKeys
                    )
                    val right = clip.copy(
                        id = generateId(),
                        position = playhead,
                        startTime = clip.startTime + leftDur,
                        volumeKeyframes = rightKeys
                    )
                    newClips += left
                    newClips += right

                    if (newSelectedVideoId == null && newSelectedAudio == null) {
                        newSelectedAudio = track.id to right.id
                    }
                }
            }
            track.copy(clips = newClips)
        }

        val newSession = session.copy(
            videoClips = newVideoClips.sortedBy { it.position },
            audioTracks = newAudioTracks
        )

        val newSelection =
            if (newSelectedVideoId != null) {
                Selection.VideoClip(newSelectedVideoId!!)
            } else if (newSelectedAudio != null) {
                Selection.AudioClip(newSelectedAudio!!.first, newSelectedAudio!!.second)
            } else {
                _state.value.selection // If nothing was split, keep current selection
            }

        // Calculate new range selection
        val currentRangeSelection = _state.value.rangeSelection
        val updatedRangeSelection = currentRangeSelection?.let { r ->
            val rangeStart = r.start.value
            val newStart = minOf(rangeStart, playhead)
            val newEnd = maxOf(rangeStart, playhead)
            TimeRange.fromMillis(newStart, newEnd)
        }

        _state.update {
            it.copy(
                session = newSession,
                selection = newSelection,
                rangeSelection = updatedRangeSelection, // Update range selection
                splitMarkerPosition = null // Clear the marker after processing
            )
        }
        prepareSessionPreservePosition(newSession)
    }

    private suspend fun deleteTimeRange(intent: EditorIntent.DeleteTimeRange) {
        val session = _state.value.session ?: return

        // Undo用に現在の状態を保存
        sessionManager.saveState(session)

        val start = intent.start
        val end = intent.end
        val delta = (end - start).coerceAtLeast(0L)

        // 1) VideoClips: 範囲外は残し、範囲に被る部分は左右に分割。右側は Δだけpositionを前詰め。
        val newVideoClips = session.videoClips.flatMap { clip ->
            val cs = clip.position
            val ce = clip.position + clip.duration

            when {
                ce <= start -> listOf(clip) // 完全に前
                cs >= end   -> listOf(clip.copy(position = cs - delta)) // 完全に後ろ→前詰め
                else -> {
                    val leftDur  = (start - cs).coerceAtLeast(0L)
                    val rightDur = (ce - end).coerceAtLeast(0L)
                    buildList {
                        if (leftDur > 0) add(clip.copy(endTime = clip.startTime + leftDur))
                        if (rightDur > 0) add(
                            clip.copy(
                                id = generateId(),
                                position = cs + leftDur - delta,
                                startTime = clip.endTime - rightDur
                            )
                        )
                    }
                }
            }
        }.sortedBy { it.position }

        // 2) AudioTracks: 各トラックの各 AudioClip も同様に分割・前詰め。
        //    さらに volumeKeyframes は:
        //      - 範囲前: そのまま
        //      - 範囲内: 破棄
        //      - 範囲後: time -= delta（クリップ内座標に注意）
        val newAudioTracks = session.audioTracks.map { track ->
            val newClips = track.clips.flatMap { clip ->
                val cs = clip.position
                val ce = clip.position + clip.duration

                when {
                    ce <= start -> listOf(clip)
                    cs >= end   -> listOf(clip.copy(position = cs - delta))
                    else -> {
                        val leftDur  = (start - cs).coerceAtLeast(0L)
                        val rightDur = (ce - end).coerceAtLeast(0L)

                        val left = if (leftDur > 0) {
                            clip.copy(
                                endTime = clip.startTime + leftDur,
                                volumeKeyframes = clip.volumeKeyframes.filter { it.time <= leftDur }
                            )
                        } else null

                        val right = if (rightDur > 0) {
                            clip.copy(
                                id = generateId(),
                                position = cs + leftDur - delta,
                                startTime = clip.endTime - rightDur,
                                volumeKeyframes = clip.volumeKeyframes
                                    .filter { it.time >= (clip.duration - rightDur) }
                                    .map { it.copy(time = it.time - (clip.duration - rightDur)) } // 原点を右片へ
                            )
                        } else null

                        listOfNotNull(left, right)
                    }
                }
            }.sortedBy { it.position }
            track.copy(clips = newClips)
        }

        // 3) マーカー・トランジションなど: 範囲内は削除、後方は -delta シフト
        val newMarkers = session.markers
            .filter { it.time < start || it.time >= end }
            .map { m -> if (m.time >= end) m.copy(time = m.time - delta) else m }

        val newDuration = (session.duration - delta).coerceAtLeast(0L)

        val newSession = session.copy(
            videoClips = newVideoClips,
            audioTracks = newAudioTracks,
            markers = newMarkers
        )

        // 永続化 → State 反映 → プレイヤー再構築
        sessionManager.updateSession(newSession).onSuccess {
            _state.update {
                it.copy(
                    session = newSession,
                    rangeSelection = null,
                    mode = EditMode.NORMAL  // 範囲選択モードを解除
                )
            }
            prepareSessionPreservePosition(newSession, targetMs = start) // プレビュー更新（既存の各編集処理と同じ流れ）
        }.onFailure { e ->
            _events.send(EditorEvent.ShowError(e.message ?: "範囲削除に失敗しました"))
        }
    }

    private fun generateId(): String {
        return java.util.UUID.randomUUID().toString()
    }

    private fun prepareSessionPreservePosition(
    session: EditorSession,
    targetMs: Long = _state.value.playhead
) {
    val wasPlaying = _state.value.isPlaying
    _suppressPositionSync.value = true

    viewModelScope.launch {
        // ★ durationが0または1の場合の境界値を適切に処理
        val maxPosition = session.duration.coerceAtLeast(0L)
        val safeTarget = if (maxPosition > 0) {
            targetMs.coerceIn(0L, maxPosition)
        } else {
            0L
        }
        _state.update { it.copy(playhead = safeTarget) }

        // 修正版 prepare
        playerEngine.prepareAndAwaitReady(session)

        playerEngine.seekTo(safeTarget)
        if (wasPlaying) playerEngine.play() else playerEngine.pause()

        // 少し余裕を持って同期解除
        delay(300)
        _suppressPositionSync.value = false
    }
}

    override fun onCleared() {
        super.onCleared()
        playerEngine.release()
    }
}
