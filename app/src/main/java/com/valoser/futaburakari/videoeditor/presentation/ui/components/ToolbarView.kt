package com.valoser.futaburakari.videoeditor.presentation.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.videoeditor.domain.model.EditMode

/**
 * ツールバー（72dp高さ）
 */
@Composable
fun ToolbarView(
    mode: EditMode,
    isClipSelected: Boolean, // クリップが選択されているか
    onEditClick: () -> Unit,
    onVolumeClick: () -> Unit,
    onAudioTrackClick: () -> Unit,
    onAddKeyframeClick: () -> Unit,
    onAddAudioTrackClick: () -> Unit,
    onAddMarkerClick: () -> Unit,
    onDeleteRangeClick: () -> Unit, // 範囲削除実行
    onCancelRangeSelectClick: () -> Unit, // 範囲選択キャンセル
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (mode) {
                EditMode.RANGE_SELECT -> {
                    // 削除ボタン
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = onDeleteRangeClick) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "範囲を削除"
                            )
                        }
                        Text(
                            text = "削除",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    // キャンセルボタン
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = onCancelRangeSelectClick) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "キャンセル"
                            )
                        }
                        Text(
                            text = "キャンセル",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                EditMode.AUDIO_TRACK -> {
                    // キーフレーム追加ボタン
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = onAddKeyframeClick) {
                            Icon(
                                imageVector = Icons.Default.AddCircle,
                                contentDescription = "キーフレーム追加"
                            )
                        }
                        Text(
                            text = "キーフレーム",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    // 音声トラック追加ボタン
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = onAddAudioTrackClick) {
                            Icon(
                                imageVector = Icons.Default.QueueMusic,
                                contentDescription = "音声トラック追加"
                            )
                        }
                        Text(
                            text = "トラック追加",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                else -> { // NORMALモードなど
                    // 編集ボタン
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = onEditClick, enabled = isClipSelected) { // クリップ選択中にのみ有効
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = "編集"
                            )
                        }
                        Text(
                            text = "編集",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    // マーカー追加ボタン
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = onAddMarkerClick) {
                            Icon(
                                imageVector = Icons.Default.BookmarkAdd,
                                contentDescription = "マーカー追加"
                            )
                        }
                        Text(
                            text = "マーカー",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    // 音声トラックボタン
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = onAudioTrackClick) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = "音声トラック"
                            )
                        }
                        Text(
                            text = "音声トラック",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}
