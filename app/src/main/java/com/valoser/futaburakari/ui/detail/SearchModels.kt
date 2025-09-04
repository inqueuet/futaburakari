package com.valoser.futaburakari.ui.detail

data class SearchState(
    val active: Boolean,
    val currentIndexDisplay: Int, // 1-based, 0 if none
    val total: Int,
)

