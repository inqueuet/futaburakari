package com.valoser.futaburakari

/**
 * カタログ画面から通知されるプリフェッチヒント。
 *
 * UI が可視領域と先読み範囲を算出し、その範囲のアイテムと
 * Coil リクエストに必要なピクセルサイズを ViewModel へ渡す。
 * - `items` はプリフェッチ対象の `ImageItem` スナップショット
 * - `cellWidthPx` / `cellHeightPx` は Coil の `size` に渡すピクセル寸法（0 の場合は未指定）
 */
data class CatalogPrefetchHint(
    val items: List<ImageItem>,
    val cellWidthPx: Int,
    val cellHeightPx: Int,
)
