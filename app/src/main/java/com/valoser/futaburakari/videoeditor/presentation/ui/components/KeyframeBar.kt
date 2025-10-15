package com.valoser.futaburakari.videoeditor.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * キーフレームバー
 * 音量キーフレームの表示/編集（32dp高さ）
 */
@Composable
fun KeyframeBar(
    track: com.valoser.futaburakari.videoeditor.domain.model.AudioTrack,
    playhead: Long,
    zoom: Float,
    onKeyframeClick: (String, String, com.valoser.futaburakari.videoeditor.domain.model.Keyframe) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(track) {
                detectTapGestures {
                    offset ->
                    track.clips.forEach { clip ->
                        clip.volumeKeyframes.forEach { keyframe ->
                            val keyframeX = (clip.position + keyframe.time) * zoom
                            val keyframeY = (1f - keyframe.value) * size.height
                            val touchRadius = 8.dp.toPx()

                            if (kotlin.math.abs(offset.x - keyframeX) < touchRadius && kotlin.math.abs(offset.y - keyframeY) < touchRadius) {
                                onKeyframeClick(track.id, clip.id, keyframe)
                            }
                        }
                    }
                }
            }
    ) {
        val keyframeColor = Color(0xFF2196F3) // 青
        val keyframePath = Path()

        track.clips.forEach { clip ->
            val keyframes = clip.volumeKeyframes.sortedBy { it.time }
            if (keyframes.isNotEmpty()) {
                val startX = (clip.position + keyframes.first().time) * zoom
                val startY = (1f - keyframes.first().value) * size.height
                keyframePath.moveTo(startX, startY)

                for (i in 1 until keyframes.size) {
                    val x = (clip.position + keyframes[i].time) * zoom
                    val y = (1f - keyframes[i].value) * size.height
                    keyframePath.lineTo(x, y)
                }

                // キーフレームの線を描画
                drawPath(
                    path = keyframePath,
                    color = keyframeColor,
                    style = Stroke(width = 2.dp.toPx())
                )

                // キーフレームの点を描画
                keyframes.forEach { keyframe ->
                    val x = (clip.position + keyframe.time) * zoom
                    val y = (1f - keyframe.value) * size.height
                    drawCircle(
                        color = keyframeColor,
                        radius = 4.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }
        }

        // プレイヘッド描画
        val playheadX = (playhead * zoom)
        drawLine(
            color = Color.Red,
            start = Offset(playheadX, 0f),
            end = Offset(playheadX, size.height),
            strokeWidth = 2.dp.toPx()
        )
    }
}
