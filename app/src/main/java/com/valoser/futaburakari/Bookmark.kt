package com.valoser.futaburakari

/**
 * Immutable model representing a single bookmark item.
 *
 * @property name Display label for the bookmark.
 * @property url Absolute or relative URL the bookmark points to.
 */
data class Bookmark(val name: String, val url: String)
