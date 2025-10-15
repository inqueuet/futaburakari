package com.valoser.futaburakari.videoeditor.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.valoser.futaburakari.videoeditor.domain.model.AudioTrack
import com.valoser.futaburakari.videoeditor.domain.model.Selection

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valoser.futaburakari.videoeditor.domain.model.AudioSourceType
import com.valoser.futaburakari.videoeditor.domain.model.EditMode
import com.valoser.futaburakari.videoeditor.domain.model.TimeRange
import kotlin.math.max

/**
 * 音声クリップトラック（64dp高さ）
 */
@Composable
fun AudioClipTrack(
    track: AudioTrack,
    selection: Selection?,
    playhead: Long,
    zoom: Float,
    timelineDuration: Long,
    mode: EditMode,
    rangeSelection: TimeRange?,
    onClipSelected: (String, String) -> Unit,
    onClipTrimmed: (String, String, Long, Long) -> Unit,
    onClipMoved: (String, String, Long) -> Unit,
    onRangeChange: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val contentDuration = max(
        timelineDuration,
        track.clips.maxOfOrNull { it.position + it.duration } ?: 0L
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
            track.clips.forEach { clip ->
                val isSelected =
                    selection is Selection.AudioClip && selection.trackId == track.id && selection.clipId == clip.id
                val clipWidthPx = max(clip.duration * zoom, with(density) { 24.dp.toPx() })
                val clipWidthDp = with(density) { clipWidthPx.toDp() }
                val clipPositionDp = with(density) { (clip.position * zoom).toDp() }

                var dragOffset by remember { mutableStateOf(Offset.Zero) }
                var trimStartOffset by remember { mutableStateOf(0f) }
                var trimEndOffset by remember { mutableStateOf(0f) }

                val clipColor = when (clip.sourceType) {
                    AudioSourceType.VIDEO_ORIGINAL -> Color(0xFF888888)
                    AudioSourceType.MUSIC -> Color(0xFF4CAF50)
                    AudioSourceType.RECORDING -> Color(0xFFF44336)
                    AudioSourceType.SILENCE -> Color.Transparent
                }

                Box(
                    modifier = Modifier
                        .offset(x = clipPositionDp + with(density) { dragOffset.x.toDp() })
                        .width(clipWidthDp)
                        .fillMaxHeight()
                        .padding(2.dp)
                        .background(clipColor)
                        .border(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = Color.Yellow
                        )
                        .pointerInput(clip.id, mode) {
                            if (mode != EditMode.RANGE_SELECT) {
                                detectTapGestures(
                                    onTap = {
                                        onClipSelected(track.id, clip.id)
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
                                                trimStartOffset = offset.x
                                            }
                                            offset.x > clipWidth - handleWidth -> {
                                                trimEndOffset = offset.x - clipWidth
                                            }
                                            else -> {
                                                dragOffset = Offset.Zero
                                            }
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        when {
                                            trimStartOffset != 0f -> {
                                                val newStart = clip.startTime + (dragAmount.x / zoom).toLong()
                                                onClipTrimmed(
                                                    track.id,
                                                    clip.id,
                                                    newStart.coerceAtLeast(0L),
                                                    clip.endTime
                                                )
                                            }
                                            trimEndOffset != 0f -> {
                                                val newEnd = clip.endTime + (dragAmount.x / zoom).toLong()
                                                onClipTrimmed(
                                                    track.id,
                                                    clip.id,
                                                    clip.startTime,
                                                    newEnd
                                                )
                                            }
                                            else -> {
                                                dragOffset += dragAmount
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        if (dragOffset != Offset.Zero) {
                                            val newPosition = (clip.position + (dragOffset.x / zoom).toLong())
                                                .coerceAtLeast(0L)
                                            onClipMoved(track.id, clip.id, newPosition)
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
                    Text(
                        text = clip.sourceType.name.take(4),
                        color = Color.White,
                        fontSize = 10.sp
                    )
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
