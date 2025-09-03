package com.valoser.futaburakari.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import com.valoser.futaburakari.databinding.ActivityDetailBinding

/**
 * Hybrid container for gradual Compose migration.
 * Currently embeds the existing XML root inside Compose.
 */
@Composable
fun DetailScreenHybrid(binding: ActivityDetailBinding) {
    // Host the legacy View hierarchy (activity_detail.xml) inside Compose.
    AndroidView(factory = { binding.root })
}

