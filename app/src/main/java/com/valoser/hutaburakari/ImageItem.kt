package com.valoser.hutaburakari

data class ImageItem(
    val previewUrl: String,      // 旧 imageUrl（サムネ用を保持しておく）
    val title: String,
    val replyCount: String,
    val detailUrl: String,
    val fullImageUrl: String? = null // 追加：フルサイズURL
)
