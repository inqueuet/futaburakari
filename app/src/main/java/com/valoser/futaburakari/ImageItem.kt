package com.valoser.futaburakari

/**
 * 一覧やギャラリーで表示する画像項目のモデル。
 *
 * - サムネイルURL、タイトル、レス数表示用文字列、詳細画面遷移用URLを保持
 * - 必要に応じてフルサイズ画像のURLも保持（任意）
 */
data class ImageItem(
    val previewUrl: String,      // サムネイル画像のURL（旧 imageUrl）
    val title: String,           // 表示タイトル
    val replyCount: String,      // レス数等の表示用文字列
    val detailUrl: String,       // 詳細表示へ遷移するためのURL
    val fullImageUrl: String? = null, // フルサイズ画像のURL（任意）
    val urlFixNote: String? = null    // 個別404時の候補探索で置換された際のメモ（UI表示用）
)
