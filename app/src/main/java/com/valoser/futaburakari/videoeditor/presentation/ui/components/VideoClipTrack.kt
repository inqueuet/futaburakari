package com.valoser.futaburakari.videoeditor.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valoser.futaburakari.videoeditor.domain.model.EditMode
import com.valoser.futaburakari.videoeditor.domain.model.Selection
import com.valoser.futaburakari.videoeditor.domain.model.TimeRange
import com.valoser.futaburakari.videoeditor.domain.model.VideoClip
import kotlin.math.max

@Composable
fun VideoClipTrack(
    clips: List<VideoClip>,
    selection: Selection?,
    playhead: Long,
    zoom: Float,
    timelineDuration: Long,
    mode: EditMode,
    rangeSelection: TimeRange?,
    onClipSelected: (String) -> Unit,
    onClipTrimmed: (String, Long, Long) -> Unit,
    onClipMoved: (String, Long) -> Unit,
    onRangeChange: (Long, Long) -> Unit,
    onTransitionClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val contentDuration = max(
        timelineDuration,
        clips.maxOfOrNull { it.position + it.duration } ?: 0L
    ).coerceAtLeast(1L)
    val contentWidthPx = max(contentDuration * zoom, with(density) { 1.dp.toPx() })
    val contentWidthDp = with(density) { contentWidthPx.toDp() }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .width(contentWidthDp)
                .fillMaxHeight()
        ) {
            clips.forEach { clip ->
                key(clip.id) {
                    val isSelected = selection is Selection.VideoClip && selection.clipId == clip.id
                    val clipWidthPx = max(clip.duration * zoom, with(density) { 24.dp.toPx() })
                    val clipWidthDp = with(density) { clipWidthPx.toDp() }
                    val clipPositionDp = with(density) { (clip.position * zoom).toDp() }

                    var dragOffset by remember { mutableStateOf(Offset.Zero) }
                    var trimStartOffset by remember { mutableStateOf(0f) }
                    var trimEndOffset by remember { mutableStateOf(0f) }

                    Box(
                        modifier = Modifier
                            .offset(x = clipPositionDp + with(density) { dragOffset.x.toDp() })
                            .width(clipWidthDp)
                            .fillMaxHeight()
                            .padding(2.dp)
                            .background(if (isSelected) Color.Blue else Color.Gray)
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = Color.Yellow
                            )
                            .pointerInput(clip.id, mode) {
                                if (mode != EditMode.RANGE_SELECT) {
                                    detectTapGestures(
                                        onTap = {
                                            onClipSelected(clip.id)
                                        }
                                    )
                                }
                            }
                            .pointerInput(clip.id, mode) {
                                if (mode != EditMode.RANGE_SELECT) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val clipWidth = clipWidthDp.toPx()
                                            val handleWidth = 20f // ハンドル幅

                                            when {
                                                offset.x < handleWidth -> {
                                                    // 左ハンドル（トリムスタート）
                                                    trimStartOffset = offset.x
                                                }
                                                offset.x > clipWidth - handleWidth -> {
                                                    // 右ハンドル（トリムエンド）
                                                    trimEndOffset = offset.x - clipWidth
                                                }
                                                else -> {
                                                    // クリップ移動
                                                    dragOffset = Offset.Zero
                                                }
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            when {
                                                trimStartOffset != 0f -> {
                                                    // トリムスタート処理
                                                    val newStart = clip.startTime + (dragAmount.x / zoom).toLong()
                                                    onClipTrimmed(
                                                        clip.id,
                                                        newStart.coerceAtLeast(0L),
                                                        clip.endTime
                                                    )
                                                }
                                                trimEndOffset != 0f -> {
                                                    // トリムエンド処理
                                                    val newEnd = clip.endTime + (dragAmount.x / zoom).toLong()
                                                    onClipTrimmed(
                                                        clip.id,
                                                        clip.startTime,
                                                        newEnd.coerceAtMost(clip.sourceEndTime)
                                                    )
                                                }
                                                else -> {
                                                    // 移動処理
                                                    dragOffset += dragAmount
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            if (dragOffset != Offset.Zero) {
                                                val newPosition = (clip.position + (dragOffset.x / zoom).toLong())
                                                    .coerceAtLeast(0L)
                                                onClipMoved(clip.id, newPosition)
                                                dragOffset = Offset.Zero
                                            }
                                            trimStartOffset = 0f
                                            trimEndOffset = 0f
                                        }
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // クリップ名を表示
                        Text(
                            text = "Clip ${clip.id.take(4)}",
                            color = Color.White,
                            fontSize = 10.sp
                        )

                        // 左ハンドル
                        if (isSelected && mode != EditMode.RANGE_SELECT) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .width(20.dp)
                                    .fillMaxHeight()
                                    .background(Color.Yellow.copy(alpha = 0.5f))
                            )
                        }

                        // 右ハンドル
                        if (isSelected && mode != EditMode.RANGE_SELECT) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(20.dp)
                                    .fillMaxHeight()
                                    .background(Color.Yellow.copy(alpha = 0.5f))
                            )
                        }
                    }
                }
            }

            // トランジション追加ボタン
            clips.forEachIndexed { index, clip ->
                if (index < clips.size - 1) {
                    val transitionPosition = clip.position + clip.duration
                    val transitionPositionDp = with(density) { (transitionPosition * zoom).toDp() }
                    IconButton(
                        onClick = { onTransitionClick(transitionPosition) },
                        modifier = Modifier.offset(x = transitionPositionDp - 18.dp, y = 18.dp)
                    ) {
                        Icon(Icons.Default.AddCircle, contentDescription = "Add Transition")
                    }
                }
            }

            // 範囲選択UI
            if (mode == EditMode.RANGE_SELECT && rangeSelection != null) {
                val startX = rangeSelection.start.value * zoom
                val endX = rangeSelection.end.value * zoom

                // 選択範囲のオーバーレイ
                Box(modifier = Modifier
                    .offset(x = with(density) { startX.toDp() })
                    .width(with(density) { (endX - startX).toDp() })
                    .fillMaxHeight()
                    .background(Color.Yellow.copy(alpha = 0.3f))
                    .border(width = 2.dp, color = Color.Yellow) // 枠線を追加
                )

                // 開始ハンドル
                Box(modifier = Modifier
                    .offset(x = with(density) { startX.toDp() })
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.Yellow)
                    .pointerInput(Unit) {
                        detectDragGestures {
                            change, dragAmount ->
                            change.consume()
                            val newStart = (rangeSelection.start.value + (dragAmount.x / zoom).toLong()).coerceAtLeast(0L)
                            onRangeChange(newStart, rangeSelection.end.value)
                        }
                    }
                )

                // 終了ハンドル
                Box(modifier = Modifier
                    .offset(x = with(density) { endX.toDp() })
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.Yellow)
                    .pointerInput(Unit) {
                        detectDragGestures {
                            change, dragAmount ->
                            change.consume()
                            val newEnd = (rangeSelection.end.value + (dragAmount.x / zoom).toLong()).coerceAtMost(contentDuration)
                            onRangeChange(rangeSelection.start.value, newEnd)
                        }
                    }
                )
            }
        }
    }
}