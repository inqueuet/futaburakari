package com.valoser.futaburakari.videoeditor.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.videoeditor.domain.model.AudioSourceType
import com.valoser.futaburakari.videoeditor.domain.model.AudioTrack
import com.valoser.futaburakari.videoeditor.media.audio.WaveformGenerator
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 波形ビュー
 * 大きく見やすいRMS波形（48dp高さ）、1/10秒解像度
 */
@Composable
fun WaveformView(
    waveformGenerator: WaveformGenerator,
    track: AudioTrack,
    playhead: Long,
    zoom: Float,
    timelineDuration: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val scope = rememberCoroutineScope()

    // 波形キャッシュ
    val waveformCache = remember { mutableStateMapOf<String, FloatArray>() }
    val clipSignature = remember(track.clips) {
        track.clips.map { clip ->
            WaveformClipSignature(
                id = clip.id,
                start = clip.startTime,
                end = clip.endTime,
                position = clip.position
            )
        }
    }
    val currentClipIdsState = rememberUpdatedState(clipSignature.map { it.id }.toSet())

    LaunchedEffect(clipSignature) {
        // 存在しなくなったクリップIDの波形を即座に除去
        val activeIds = currentClipIdsState.value
        val staleKeys = waveformCache.keys.filter { it !in activeIds }
        staleKeys.forEach { waveformCache.remove(it) }
    }

    // 波形生成
    LaunchedEffect(clipSignature) {
        clipSignature.forEach { signature ->
            val clip = track.clips.firstOrNull { it.id == signature.id } ?: return@forEach
            if (clip.sourceType == AudioSourceType.SILENCE || waveformCache.containsKey(clip.id)) return@forEach

            scope.launch {
                waveformGenerator.generate(clip).getOrNull()?.let { waveform ->
                    if (currentClipIdsState.value.contains(clip.id)) {
                        waveformCache[clip.id] = waveform
                    }
                }
            }
        }
    }

    val waveformHeightPx = with(density) { 48.dp.toPx() }

    val contentDuration = max(
        timelineDuration,
        track.clips.maxOfOrNull { it.position + it.duration } ?: 0L
    ).coerceAtLeast(1L)
    val contentWidthPx = max(contentDuration * zoom, with(density) { 1.dp.toPx() })
    val contentWidthDp = with(density) { contentWidthPx.toDp() }

    Box(
        modifier = modifier
    ) {
        Canvas(
            modifier = Modifier
                .width(contentWidthDp)
                .height(48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val centerY = size.height / 2

            track.clips.forEach { clip ->
                val startX = (clip.position * zoom)
                val waveform = waveformCache[clip.id]

                // 波形の色を音源タイプによって変更
                val waveformColor = when (clip.sourceType) {
                    AudioSourceType.VIDEO_ORIGINAL -> Color(0xFF2196F3) // 青
                    AudioSourceType.MUSIC -> Color(0xFF4CAF50) // 緑
                    AudioSourceType.RECORDING -> Color(0xFFF44336) // 赤
                    AudioSourceType.SILENCE -> Color.Transparent
                }

                waveform?.let { samples ->
                    val sampleWidth = ((clip.duration * zoom) / samples.size)

                    samples.forEachIndexed { index, amplitude ->
                        val x = startX + (index * sampleWidth)
                        val barHeight = amplitude * waveformHeightPx / 2

                        // 波形バーを描画（上下対称）
                        drawLine(
                            color = waveformColor,
                            start = Offset(x, centerY - barHeight),
                            end = Offset(x, centerY + barHeight),
                            strokeWidth = max(sampleWidth, 2f)
                        )
                    }
                } ?: run {
                    // 波形データがない場合は矩形を描画
                    if (clip.sourceType != AudioSourceType.SILENCE) {
                        drawRect(
                            color = waveformColor.copy(alpha = 0.3f),
                            topLeft = Offset(startX, centerY - 10f),
                            size = androidx.compose.ui.geometry.Size(
                                (clip.duration * zoom),
                                20f
                            )
                        )
                    }
                }
            }
        }
    }
}

private data class WaveformClipSignature(
    val id: String,
    val start: Long,
    val end: Long,
    val position: Long
)
