package com.valoser.futaburakari.videoeditor.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.videoeditor.domain.model.FadeDuration
import com.valoser.futaburakari.videoeditor.domain.model.Selection

@Composable
fun InspectorPanel(
    selection: Selection?,
    clipVolume: Float?,
    isClipMuted: Boolean?,
    onDeleteClick: () -> Unit,
    onCopyClick: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onFadeInChange: (FadeDuration) -> Unit,
    onFadeOutChange: (FadeDuration) -> Unit,
    onToggleMute: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val isVideoClip = selection is Selection.VideoClip
    val isAudioClip = selection is Selection.AudioClip

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text("インスペクター", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // --- Basic Tools ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDeleteClick) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
            IconButton(onClick = onCopyClick) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
            }
            if (isAudioClip) {
                IconButton(onClick = onToggleMute) {
                    Icon(
                        imageVector = if (isClipMuted == true) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = "Mute"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Speed Control (for video clips) ---
        if (isVideoClip) {
            Column {
                Text("速度", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val speeds = listOf(0.5f, 1.0f, 1.5f, 2.0f)
                    speeds.forEach { speed ->
                        TextButton(onClick = { onSpeedChange(speed) }) {
                            Text("${speed}x")
                        }
                    }
                }
            }
        }

        // --- Audio Controls (for audio clips) ---
        if (isAudioClip) {
            // Volume Slider
            var sliderValue by remember(clipVolume) { mutableStateOf(clipVolume ?: 1f) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("音量: ${(sliderValue * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onVolumeChange(sliderValue) },
                    valueRange = 0f..2f
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Fade Controls
            val fadeDurations = listOf(FadeDuration.SHORT, FadeDuration.MEDIUM, FadeDuration.LONG)
            Column {
                Text("フェードイン", style = MaterialTheme.typography.bodyMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    fadeDurations.forEach { duration ->
                        TextButton(onClick = { onFadeInChange(duration) }) {
                            Text("${duration.millis / 1000f}s")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("フェードアウト", style = MaterialTheme.typography.bodyMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    fadeDurations.forEach { duration ->
                        TextButton(onClick = { onFadeOutChange(duration) }) {
                            Text("${duration.millis / 1000f}s")
                        }
                    }
                }
            }
        }
    }
}
