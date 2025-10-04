package com.valoser.futaburakari

/**
 * Immutable bookmark entry shared between persistence (`BookmarkManager`) and presentation layers.
 *
 * @property name User-facing label rendered in the bookmark lists.
 * @property url Fully qualified board URL persisted and matched for CRUD operations.
 */
data class Bookmark(val name: String, val url: String)
