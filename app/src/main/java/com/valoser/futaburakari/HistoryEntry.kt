package com.valoser.futaburakari

data class HistoryEntry(
    val key: String,           // normalized key (board/thread)
    val url: String,
    val title: String,
    val lastViewedAt: Long,
    val thumbnailUrl: String? = null,
    // 新着優先の並び替えに必要なフィールド（既存JSONとの互換のため既定値付き）
    val lastUpdatedAt: Long = 0L,      // 新規レスを検知した最終時刻
    val lastKnownReplyNo: Int = 0,     // 取得時点での最終レス番号
    val lastViewedReplyNo: Int = 0,    // ユーザが閲覧した最終レス番号
    val unreadCount: Int = 0,          // 未読数（派生だが保存して高速化）
    // アーカイブ明示
    val isArchived: Boolean = false,
    val archivedAt: Long = 0L
)
