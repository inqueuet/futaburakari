package com.valoser.futaburakari.ui.detail

/**
 * UI-facing state for in-thread text search navigation.
 *
 * @property active True when a search query exists and at least one match is available.
 * @property currentIndexDisplay 1-based index of the currently selected hit; 0 when no match.
 * @property total Total number of matches in the current result set.
 */
data class SearchState(
    val active: Boolean,
    val currentIndexDisplay: Int,
    val total: Int,
)
