package com.valoser.futaburakari.image

import coil3.memory.MemoryCache

/**
 * Utilities to build stable Coil memory cache keys so that
 * thumbnails (grids/lists) and full images (detail/zoom) can
 * share cached bitmaps predictably across screens.
 */
object ImageKeys {
    fun thumb(url: String): MemoryCache.Key = MemoryCache.Key("media_thumb:$url")
    fun full(url: String): MemoryCache.Key = MemoryCache.Key("media_full:$url")
    // 将来的な中間サイズやプリフェッチ識別用
    fun prefetch(url: String): MemoryCache.Key = MemoryCache.Key("media_prefetch:$url")
}
