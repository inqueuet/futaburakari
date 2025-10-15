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
import com.valoser.futaburakari.videoeditor.presentation.ui.components.ToolbarView
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
                        Text("<")
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
                        Text("↶")
                    }

                    // Redoボタン
                    IconButton(
                        onClick = {
                            viewModel.handleIntent(com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.Redo)
                        },
                        enabled = state.session != null && !state.isLoading
                    ) {
                        Text("↷")
                    }

                    TextButton(
                        onClick = { showExportDialog = true },
                        enabled = state.session != null && !state.isLoading
                    ) {
                        Text("完了")
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
                        Text("⏮")
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
                        Text("[-]")
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
                        Text("[+]")
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
                        Text("⏭")
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
                    zoom = state.zoom,
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

            // ツールバー（72dp）
            AnimatedVisibility(visible = state.selection != null && state.mode != com.valoser.futaburakari.videoeditor.domain.model.EditMode.RANGE_SELECT) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(72.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 削除ボタン
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        IconButton(onClick = {
                                            when (val selection = state.selection) {
                                                is com.valoser.futaburakari.videoeditor.domain.model.Selection.VideoClip -> {
                                                    viewModel.handleIntent(
                                                        com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.DeleteClip(selection.clipId)
                                                    )
                                                }
                                                is com.valoser.futaburakari.videoeditor.domain.model.Selection.AudioClip -> {
                                                    viewModel.handleIntent(
                                                        com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.DeleteAudioClip(
                                                            selection.trackId,
                                                            selection.clipId
                                                        )
                                                    )
                                                }
                                                else -> {}
                                            }
                                            viewModel.handleIntent(com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.ClearSelection)
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                                        }
                                        Text("削除", style = MaterialTheme.typography.labelSmall)
                                    }
                
                                    // コピーボタン
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        IconButton(onClick = {
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
                                        }) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                                        }
                                        Text("コピー", style = MaterialTheme.typography.labelSmall)
                                    }
                
                                    // 分割ボタン
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        IconButton(onClick = {
                                            when (val selection = state.selection) {
                                                is com.valoser.futaburakari.videoeditor.domain.model.Selection.VideoClip -> {
                                                    viewModel.handleIntent(
                                                        com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SplitClip(
                                                            selection.clipId,
                                                            state.playhead
                                                        )
                                                    )
                                                }
                                                is com.valoser.futaburakari.videoeditor.domain.model.Selection.AudioClip -> {
                                                    viewModel.handleIntent(
                                                        com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SplitAudioClip(
                                                            selection.trackId,
                                                            selection.clipId,
                                                            state.playhead
                                                        )
                                                    )
                                                }
                                                else -> {}
                                            }
                                        }) {
                                            Icon(Icons.Default.ContentCut, contentDescription = "Split")
                                        }
                                        Text("分割", style = MaterialTheme.typography.labelSmall)
                                    }
                
                                    // 範囲選択ボタン
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        IconButton(onClick = {
                                            viewModel.handleIntent(
                                                com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetEditMode(
                                                    com.valoser.futaburakari.videoeditor.domain.model.EditMode.RANGE_SELECT
                                                )
                                            )
                                        }) {
                                            Icon(Icons.Default.Crop, contentDescription = "Select Range") // アイコン変更
                                        }
                                        Text("範囲選択", style = MaterialTheme.typography.labelSmall)
                                    }
                
                                    if (state.selection is com.valoser.futaburakari.videoeditor.domain.model.Selection.AudioClip) {
                                        val audioSelection = state.selection as com.valoser.futaburakari.videoeditor.domain.model.Selection.AudioClip
                                        val clip = state.session?.audioTracks?.find { it.id == audioSelection.trackId }?.clips?.find { it.id == audioSelection.clipId }
                                        var showFadeDialog by remember { mutableStateOf(false) }
                
                                        // フェードボタン
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            IconButton(onClick = { showFadeDialog = true }) {
                                                Icon(Icons.Default.GraphicEq, contentDescription = "Fade")
                                            }
                                            Text("フェード", style = MaterialTheme.typography.labelSmall)
                                        }
                
                                        if (showFadeDialog) {
                                            val fadeDurations = listOf(
                                                com.valoser.futaburakari.videoeditor.domain.model.FadeDuration.SHORT,
                                                com.valoser.futaburakari.videoeditor.domain.model.FadeDuration.MEDIUM,
                                                com.valoser.futaburakari.videoeditor.domain.model.FadeDuration.LONG
                                            )
                                            AlertDialog(
                                                onDismissRequest = { showFadeDialog = false },
                                                title = { Text("フェード設定") },
                                                text = {
                                                    Column {
                                                        Text("フェードイン")
                                                        Row {
                                                            fadeDurations.forEach { duration ->
                                                                TextButton(onClick = {
                                                                    viewModel.handleIntent(
                                                                        com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.AddFade(
                                                                            audioSelection.trackId,
                                                                            audioSelection.clipId,
                                                                            com.valoser.futaburakari.videoeditor.domain.model.FadeType.FADE_IN,
                                                                            duration
                                                                        )
                                                                    )
                                                                    showFadeDialog = false
                                                                }) { Text("${duration.millis / 1000f}s") }
                                                            }
                                                        }
                                                        Spacer(Modifier.height(16.dp))
                                                        Text("フェードアウト")
                                                        Row {
                                                            fadeDurations.forEach { duration ->
                                                                TextButton(onClick = {
                                                                    viewModel.handleIntent(
                                                                        com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.AddFade(
                                                                            audioSelection.trackId,
                                                                            audioSelection.clipId,
                                                                            com.valoser.futaburakari.videoeditor.domain.model.FadeType.FADE_OUT,
                                                                            duration
                                                                        )
                                                                    )
                                                                    showFadeDialog = false
                                                                }) { Text("${duration.millis / 1000f}s") }
                                                            }
                                                        }
                                                    }
                                                },
                                                confirmButton = {}
                                            )
                                        }
                
                                        // ミュートボタン
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            IconButton(onClick = {
                                                viewModel.handleIntent(
                                                    com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.ToggleMuteAudioClip(
                                                        audioSelection.trackId,
                                                        audioSelection.clipId
                                                    )
                                                )
                                            }) {
                                                Icon(
                                                    imageVector = if (clip?.muted == true) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                                    contentDescription = "Mute"
                                                )
                                            }
                                            Text(if (clip?.muted == true) "ミュート解除" else "ミュート", style = MaterialTheme.typography.labelSmall)
                                        }
                
                                        var sliderValue by remember(clip?.volume) { mutableStateOf(clip?.volume ?: 1f) }
                
                                        // 音量スライダー
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                            Slider(
                                                value = sliderValue,
                                                onValueChange = { sliderValue = it },
                                                onValueChangeFinished = {
                                                    viewModel.handleIntent(
                                                        com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetVolume(
                                                            audioSelection.trackId,
                                                            audioSelection.clipId,
                                                            sliderValue
                                                        )
                                                    )
                                                },
                                                valueRange = 0f..2f
                                            )
                                            Text(text = "音量: ${(sliderValue * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                
                                    // 速度変更ボタン (映像クリップ選択時のみ)
                                    if (state.selection is com.valoser.futaburakari.videoeditor.domain.model.Selection.VideoClip) {
                                        var showSpeedDialog by remember { mutableStateOf(false) }
                
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            IconButton(onClick = { showSpeedDialog = true }) {
                                                Icon(Icons.Default.Speed, contentDescription = "Speed")
                                            }
                                            Text("速度", style = MaterialTheme.typography.labelSmall)
                                        }
                
                                        if (showSpeedDialog) {
                                            val speeds = listOf(0.25f, 0.5f, 1f, 2f, 4f)
                                            AlertDialog(
                                                onDismissRequest = { showSpeedDialog = false },
                                                title = { Text("速度変更") },
                                                text = {
                                                    Column {
                                                        speeds.forEach { speed ->
                                                            TextButton(onClick = {
                                                                viewModel.handleIntent(
                                                                    com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetSpeed(
                                                                        (state.selection as com.valoser.futaburakari.videoeditor.domain.model.Selection.VideoClip).clipId,
                                                                        speed
                                                                    )
                                                                )
                                                                showSpeedDialog = false
                                                            }) { Text("${speed}x") }
                                                        }
                                                    }
                                                },
                                                confirmButton = {}
                                            )
                                        }
                                    }
                                }
            }

            AnimatedVisibility(visible = state.mode == com.valoser.futaburakari.videoeditor.domain.model.EditMode.RANGE_SELECT) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 範囲を削除ボタン
                    Button(onClick = {
                        val selection = state.selection
                        val range = state.rangeSelection
                        if (selection != null && range != null) {
                            when (selection) {
                                is com.valoser.futaburakari.videoeditor.domain.model.Selection.VideoClip -> {
                                    viewModel.handleIntent(
                                        com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.DeleteRange(
                                            selection.clipId,
                                            range.start.value,
                                            range.end.value
                                        )
                                    )
                                }
                                is com.valoser.futaburakari.videoeditor.domain.model.Selection.AudioClip -> {
                                    // 音声クリップの場合は何もしないか、別の削除ロジックを検討
                                }
                                else -> {}
                            }
                        }
                        viewModel.handleIntent(com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetEditMode(com.valoser.futaburakari.videoeditor.domain.model.EditMode.NORMAL))
                    }) {
                        Text("範囲を削除")
                    }

                    // 範囲を無音化ボタン (音声クリップ選択時のみ)
                    if (state.selection is com.valoser.futaburakari.videoeditor.domain.model.Selection.AudioClip) {
                        Button(onClick = {
                            val selection = state.selection as com.valoser.futaburakari.videoeditor.domain.model.Selection.AudioClip
                            val range = state.rangeSelection
                            if (range != null) {
                                viewModel.handleIntent(
                                    com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.MuteRange(
                                        selection.trackId,
                                        selection.clipId,
                                        range.start.value,
                                        range.end.value
                                    )
                                )
                            }
                            viewModel.handleIntent(com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetEditMode(com.valoser.futaburakari.videoeditor.domain.model.EditMode.NORMAL))
                        }) {
                            Text("範囲を無音化")
                        }

                        // 音声差し替えボタン
                        Button(onClick = {
                            replaceAudioLauncher.launch("audio/*")
                        }) {
                            Text("音声を差し替え")
                        }
                    }

                    // キャンセルボタン
                    Button(onClick = {
                        viewModel.handleIntent(com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetEditMode(com.valoser.futaburakari.videoeditor.domain.model.EditMode.NORMAL))
                    }) {
                        Text("キャンセル")
                    }
                }
            }

            AnimatedVisibility(visible = state.selection == null) {
                ToolbarView(
                    mode = state.mode,
                    isClipSelected = false, // No clip selected in this state
                    onEditClick = {},
                    onVolumeClick = {
                        // Not implemented
                    },
                    onAudioTrackClick = {
                        viewModel.handleIntent(
                            com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.SetEditMode(
                                com.valoser.futaburakari.videoeditor.domain.model.EditMode.AUDIO_TRACK
                            )
                        )
                    },
                    onAddKeyframeClick = {
                        val selection = state.selection as? com.valoser.futaburakari.videoeditor.domain.model.Selection.AudioClip
                        if (selection != null) {
                            val clip = state.session?.audioTracks?.find { it.id == selection.trackId }?.clips?.find { it.id == selection.clipId }
                            if (clip != null) {
                                val keyframeTime = state.playhead - clip.position
                                val keyframes = clip.volumeKeyframes.sortedBy { it.time }
                                val prevKeyframe = keyframes.lastOrNull { it.time <= keyframeTime }
                                val nextKeyframe = keyframes.firstOrNull { it.time > keyframeTime }

                                val currentValue = when {
                                    prevKeyframe != null && nextKeyframe != null -> {
                                        val timeFraction = (keyframeTime - prevKeyframe.time).toFloat() / (nextKeyframe.time - prevKeyframe.time)
                                        prevKeyframe.value + (nextKeyframe.value - prevKeyframe.value) * timeFraction
                                    }
                                    prevKeyframe != null -> prevKeyframe.value
                                    nextKeyframe != null -> nextKeyframe.value
                                    else -> clip.volume
                                }
                                viewModel.handleIntent(
                                    com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.AddVolumeKeyframe(
                                        selection.trackId,
                                        selection.clipId,
                                        keyframeTime,
                                        currentValue
                                    )
                                )
                            }
                        }
                    },
                    onAddAudioTrackClick = {
                        addAudioLauncher.launch("audio/*")
                    },
                    onAddMarkerClick = {
                        viewModel.handleIntent(
                            com.valoser.futaburakari.videoeditor.domain.model.EditorIntent.AddMarker(
                                time = state.playhead,
                                label = ""
                            )
                        )
                    },
                    onDeleteRangeClick = {},
                    onCancelRangeSelectClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
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
