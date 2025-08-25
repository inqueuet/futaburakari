package com.valoser.hutaburakari

data class HistoryEntry(
    val key: String,           // normalized key (board/thread)
    val url: String,
    val title: String,
    val lastViewedAt: Long,
    val thumbnailUrl: String? = null
)
