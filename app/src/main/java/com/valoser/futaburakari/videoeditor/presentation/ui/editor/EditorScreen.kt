package com.valoser.futaburakari.videoeditor.presentation.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.valoser.futaburakari.videoeditor.domain.model.ExportPreset
import com.valoser.futaburakari.videoeditor.presentation.viewmodel.EditorViewModel
import com.valoser.futaburakari.videoeditor.presentation.ui.components.TimelineView
import com.valoser.futaburakari.videoeditor.presentation.ui.components.PreviewView

import com.valoser.futaburakari.videoeditor.presentation.ui.components.MainToolbar
import com.valoser.futaburakari.videoeditor.presentation.ui.components.InspectorPanel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Slider
import androidx.compose.ui.Alignment

/**
 * エディタ画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showExportDialog by remember { mutableStateOf(false) }
    var showTransitionDialog by remember { mutableStateOf(false) }
    var selectedTransitionPosition by remember { mutableStateOf(0L) }

    var selectedPreset by remember { mutableStateOf<ExportPreset?>(null) }

    // エクスポート先ファイル選択ランチャー
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri: Uri? ->
        uri?.let { outputUri ->
            selectedPreset?.let { preset ->
                viewModel.handleIntent(
                    com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.Export(
                        preset = preset,
                        outputUri = outputUri
                    )
                )
            }
        }
    }

    val addAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { audioUri ->
            viewModel.handleIntent(
                com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.AddAudioTrack(
                    name = "New Audio",
                    audioUri = audioUri,
                    position = state.playhead
                )
            )
        }
    }

    val replaceAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { audioUri ->
            val selection = state.selection as? com.valoser.futaburakari.videoeditor.domain.model.Selection.AudioClip
            val range = state.rangeSelection
            if (selection != null && range != null) {
                viewModel.handleIntent(
                    com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.ReplaceAudio(
                        selection.trackId,
                        selection.clipId,
                        range.start.value,
                        range.end.value,
                        audioUri
                    )
                )
            }
            viewModel.handleIntent(com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetEditMode(com.valoser.futaburakari.videoeditor.domain.model.EditMode.NORMAL))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("動画編集") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        // バックアイコン
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Undoボタン
                    IconButton(
                        onClick = {
                            viewModel.handleIntent(com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.Undo)
                        },
                        enabled = state.session != null && !state.isLoading
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo")
                    }

                    // Redoボタン
                    IconButton(
                        onClick = {
                            viewModel.handleIntent(com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.Redo)
                        },
                        enabled = state.session != null && !state.isLoading
                    ) {
                        Icon(Icons.Default.Redo, contentDescription = "Redo")
                    }

                    TextButton(
                        onClick = { showExportDialog = true },
                        enabled = state.session != null && !state.isLoading
                    ) {
                        Text("書き出し")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            PreviewView(
                player = viewModel.playerEngine.player,
                isPlaying = state.isPlaying,
                onPlayPause = {
                    if (state.isPlaying) {
                        viewModel.handleIntent(com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.Pause)
                    } else {
                        viewModel.handleIntent(com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.Play)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f)
            )

            Divider()

            // タイムライン操作バー（48dp）
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    // 最初へ移動
                    IconButton(
                        onClick = {
                            viewModel.handleIntent(
                                com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SeekTo(0L)
                            )
                        },
                        enabled = state.session != null
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Seek to Start")
                    }

                    // ズームアウト
                    IconButton(
                        onClick = {
                            val newZoom = (state.zoom - 0.25f).coerceAtLeast(0.25f)
                            viewModel.handleIntent(
                                com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetZoom(newZoom)
                            )
                        },
                        enabled = state.zoom > 0.25f
                    ) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out")
                    }

                    // ズーム表示
                    Text(
                        text = "ズーム: ${String.format("%.2f", state.zoom)}x",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // ズームイン
                    IconButton(
                        onClick = {
                            val newZoom = (state.zoom + 0.25f).coerceAtMost(4f)
                            viewModel.handleIntent(
                                com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetZoom(newZoom)
                            )
                        },
                        enabled = state.zoom < 4f
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In")
                    }

                    // 範囲削除ボタン
                    if (state.mode == com.valoser.futaburakari.videoeditor.domain.model.EditMode.RANGE_SELECT
                        && state.rangeSelection != null) {
                        IconButton(onClick = {
                            val r = state.rangeSelection!!
                            viewModel.handleIntent(
                                com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.DeleteTimeRange(
                                    start = r.start.value,
                                    end = r.end.value,
                                    ripple = true
                                )
                            )
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "範囲削除")
                        }
                    }

                    // 最後へ移動
                    IconButton(
                        onClick = {
                            state.session?.let { session ->
                                viewModel.handleIntent(
                                    com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SeekTo(session.duration)
                                )
                            }
                        },
                        enabled = state.session != null
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Seek to End")
                    }
                }
            }

            // タイムライン（残り）
            state.session?.let { session ->
                TimelineView(
                    waveformGenerator = viewModel.waveformGenerator,
                    session = session,
                    selection = state.selection,
                    playhead = state.playhead,
                    isPlaying = state.isPlaying,
                    zoom = state.zoom,
                    splitMarkerPosition = state.splitMarkerPosition,
                    onClipSelected = { clipId ->
                        viewModel.handleIntent(
                            com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SelectClip(
                                com.valoser.futaburakari.videoeditor.domain.model.Selection.VideoClip(clipId)
                            )
                        )
                    },
                    onClipTrimmed = { clipId, start, end ->
                        viewModel.handleIntent(
                            com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.TrimClip(
                                clipId, start, end
                            )
                        )
                    },
                    onClipMoved = { clipId, position ->
                        viewModel.handleIntent(
                            com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.MoveClip(
                                clipId, position
                            )
                        )
                    },
                    onAudioClipSelected = { trackId, clipId ->
                        viewModel.handleIntent(
                            com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SelectClip(
                                com.valoser.futaburakari.videoeditor.domain.model.Selection.AudioClip(trackId, clipId)
                            )
                        )
                    },
                    onAudioClipTrimmed = { trackId, clipId, start, end ->
                        viewModel.handleIntent(
                            com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.TrimAudioClip(
                                trackId, clipId, start, end
                            )
                        )
                    },
                    onAudioClipMoved = { trackId, clipId, position ->
                        viewModel.handleIntent(
                            com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.MoveAudioClip(
                                trackId, clipId, position
                            )
                        )
                    },
                    onZoomChange = { newZoom ->
                        viewModel.handleIntent(
                            com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetZoom(newZoom)
                        )
                    },
                    onSeek = { t -> // ★追加
                        viewModel.handleIntent(
                            com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SeekTo(t)
                        )
                    },
                    onMarkerClick = { marker ->
                        viewModel.handleIntent(
                            com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.RemoveMarker(marker.time)
                        )
                    },
                    onKeyframeClick = { trackId, clipId, keyframe ->
                        viewModel.handleIntent(
                            com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.RemoveVolumeKeyframe(
                                trackId,
                                clipId,
                                keyframe
                            )
                        )
                    },
                    mode = state.mode,
                    rangeSelection = state.rangeSelection,
                    onRangeChange = { start, end ->
                        viewModel.handleIntent(
                            com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetRangeSelection(start, end)
                        )
                    },
                    onTransitionClick = { position ->
                        selectedTransitionPosition = position
                        showTransitionDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.45f)
                )
            }

            Divider()

            // Main toolbar (always visible)
            MainToolbar(
                onSplitClick = {
                    viewModel.handleIntent(com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SplitAtPlayhead)
                },
                onAddAudioClick = {
                    addAudioLauncher.launch("audio/*")
                }
            )

            // Inspector panel (slides in when a clip is selected)
            AnimatedVisibility(visible = state.selection != null) {
                val audioSelection = state.selection as? com.valoser.futaburakari.videoeditor.domain.model.Selection.AudioClip
                val selectedAudioClip = audioSelection?.let { sel ->
                    state.session?.audioTracks?.find { it.id == sel.trackId }?.clips?.find { it.id == sel.clipId }
                }

                InspectorPanel(
                    selection = state.selection,
                    clipVolume = selectedAudioClip?.volume,
                    isClipMuted = selectedAudioClip?.muted,
                    onDeleteClick = {
                        android.util.Log.d("EditorScreen", "Delete button clicked, selection: ${state.selection}")
                        when (val selection = state.selection) {
                            is com.valoser.futaburakari.videoeditor.domain.model.Selection.VideoClip -> {
                                android.util.Log.d("EditorScreen", "Deleting video clip: ${selection.clipId}")
                                viewModel.handleIntent(
                                    com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.DeleteClip(selection.clipId)
                                )
                            }
                            is com.valoser.futaburakari.videoeditor.domain.model.Selection.AudioClip -> {
                                android.util.Log.d("EditorScreen", "Deleting audio clip: ${selection.clipId}")
                                viewModel.handleIntent(
                                    com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.DeleteAudioClip(
                                        selection.trackId,
                                        selection.clipId
                                    )
                                )
                            }
                            else -> {
                                android.util.Log.w("EditorScreen", "Delete clicked but selection is: ${selection}")
                            }
                        }
                        viewModel.handleIntent(com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.ClearSelection)
                    },
                    onCopyClick = {
                        when (val selection = state.selection) {
                            is com.valoser.futaburakari.videoeditor.domain.model.Selection.VideoClip -> {
                                viewModel.handleIntent(
                                    com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.CopyClip(selection.clipId)
                                )
                            }
                            is com.valoser.futaburakari.videoeditor.domain.model.Selection.AudioClip -> {
                                viewModel.handleIntent(
                                    com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.CopyAudioClip(
                                        selection.trackId,
                                        selection.clipId
                                    )
                                )
                            }
                            else -> {}
                        }
                    },
                    onSpeedChange = { speed ->
                        val videoSelection = state.selection as? com.valoser.futaburakari.videoeditor.domain.model.Selection.VideoClip
                        if (videoSelection != null) {
                            viewModel.handleIntent(
                                com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetSpeed(
                                    videoSelection.clipId,
                                    speed
                                )
                            )
                        }
                    },
                    onFadeInChange = { duration ->
                        if (audioSelection != null) {
                            viewModel.handleIntent(
                                com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.AddFade(
                                    audioSelection.trackId,
                                    audioSelection.clipId,
                                    com.valoser.futaburakari.videoeditor.domain.model.FadeType.FADE_IN,
                                    duration
                                )
                            )
                        }
                    },
                    onFadeOutChange = { duration ->
                        if (audioSelection != null) {
                            viewModel.handleIntent(
                                com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.AddFade(
                                    audioSelection.trackId,
                                    audioSelection.clipId,
                                    com.valoser.futaburakari.videoeditor.domain.model.FadeType.FADE_OUT,
                                    duration
                                )
                            )
                        }
                    },
                    onToggleMute = {
                        if (audioSelection != null) {
                            viewModel.handleIntent(
                                com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.ToggleMuteAudioClip(
                                    audioSelection.trackId,
                                    audioSelection.clipId
                                )
                            )
                        }
                    },
                    onVolumeChange = { volume ->
                        if (audioSelection != null) {
                            viewModel.handleIntent(
                                com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetVolume(
                                    audioSelection.trackId,
                                    audioSelection.clipId,
                                    volume
                                )
                            )
                        }
                    }
                )
            }
        }
    }

    // エラー表示
    state.error?.let { error ->
        LaunchedEffect(error) {
            // Snackbarなどで表示
        }
    }

    // エクスポートダイアログ
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("エクスポート") },
            text = {
                Column {
                    Text("動画を保存します。品質を選択してください。")
                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(
                        onClick = {
                            showExportDialog = false
                            selectedPreset = ExportPreset.SNS
                            exportLauncher.launch("edited_video_sns.mp4")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("SNS最適化（720p, 30fps）")
                    }

                    TextButton(
                        onClick = {
                            showExportDialog = false
                            selectedPreset = ExportPreset.STANDARD
                            exportLauncher.launch("edited_video_standard.mp4")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("標準品質（1080p, 30fps）")
                    }

                    TextButton(
                        onClick = {
                            showExportDialog = false
                            selectedPreset = ExportPreset.HIGH_QUALITY
                            exportLauncher.launch("edited_video_high.mp4")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("高品質（1080p, 60fps）")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // トランジション設定ダイアログ
    if (showTransitionDialog) {
        val transitionDurations = listOf(300L, 500L, 1000L)
        AlertDialog(
            onDismissRequest = { showTransitionDialog = false },
            title = { Text("トランジション設定") },
            text = {
                Column {
                    Text("クロスフェードの長さを選択してください。")
                    Row {
                        transitionDurations.forEach { duration ->
                            TextButton(onClick = {
                                val clip = state.session?.videoClips?.find { it.position + it.duration == selectedTransitionPosition }
                                if (clip != null) {
                                    viewModel.handleIntent(
                                        com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.AddTransition(
                                            clipId = clip.id,
                                            type = com.valoser.futaburakari.videoeditor.domain.model.TransitionType.CROSSFADE,
                                            duration = duration
                                        )
                                    )
                                }
                                showTransitionDialog = false
                            }) {
                                Text("${duration / 1000f}s")
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // ローディング表示
    if (state.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}
