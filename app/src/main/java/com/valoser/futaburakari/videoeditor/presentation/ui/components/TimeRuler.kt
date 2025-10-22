package com.valoser.futaburakari.videoeditor.presentation.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.videoeditor.domain.model.Marker

/**
 * 時間軸（32dp高さ）
 */
@Composable
fun TimeRuler(
    duration: Long,
    playhead: Long,
    zoom: Float,
    markers: List<Marker>,
    splitMarkerPosition: Long?,
    onMarkerClick: (Marker) -> Unit,
    onSeekAt: (Long) -> Unit,  // ★追加
    modifier: Modifier = Modifier
) {
    val textPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 24f
        isAntiAlias = true
    }

    Canvas(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(markers, zoom) { // Added zoom to keys for recomposition
                detectTapGestures(onTap = { offset ->
                    // 先にマーカー命中チェック（既存）
                    var handled = false
                    markers.forEach { marker ->
                        val markerX = marker.time * zoom
                        val w = 16.dp.toPx() // Use w for consistency with user's example
                        if (offset.x in (markerX - w/2)..(markerX + w/2)) {
                            onMarkerClick(marker)
                            handled = true
                            return@forEach
                        }
                    }
                    // マーカー以外をタップしたらシーク
                    if (!handled) onSeekAt((offset.x / zoom).toLong())
                })
            }
    ) {
        val secondWidth = zoom * 1000 // 1秒の幅（ピクセル）

        // ズームレベルに応じて描画間隔を調整
        val majorTickInterval = when {
            zoom > 2f -> 1000L // 1秒ごと
            zoom > 0.5f -> 5000L // 5秒ごと
            else -> 10000L // 10秒ごと
        }

        var currentTime = 0L
        while (currentTime <= duration) {
            val x = currentTime * zoom

            // 目盛り線を描画
            drawLine(
                color = Color.Gray,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f
            )

            // 時刻テキストを描画
            drawIntoCanvas { canvas ->
                val minutes = currentTime / 60000
                val seconds = (currentTime % 60000) / 1000
                val text = String.format("%d:%02d", minutes, seconds)
                canvas.nativeCanvas.drawText(text, x + 4f, size.height - 8f, textPaint)
            }

            currentTime += majorTickInterval
        }

        // マーカーを描画
        val markerColor = Color.Yellow
        markers.forEach { marker ->
            val x = marker.time * zoom
            val markerPath = Path().apply {
                moveTo(x, 0f)
                lineTo(x + 8.dp.toPx(), size.height / 2)
                lineTo(x, size.height)
                lineTo(x - 8.dp.toPx(), size.height / 2)
                close()
            }
            drawPath(markerPath, color = markerColor)
        }

        // 分割マーカーを描画
        splitMarkerPosition?.let {
            val x = it * zoom
            drawLine(
                color = Color.Cyan,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f
            )
        }
    }
}
