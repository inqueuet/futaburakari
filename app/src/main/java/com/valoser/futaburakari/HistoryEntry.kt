package com.valoser.futaburakari

/**
 * 履歴一覧で扱う1エントリの情報。
 *
 * - 正規化キー（例: `https://host/board#threadNo`）と表示用メタ情報を保持
 * - 最終閲覧・最終更新・未読数など並び替え/表示に必要なメタデータを保持
 * - サムネイル取得や NG ルール生成に使う補助 URL、アーカイブ状態を保持
 * - 互換性維持のため一部フィールドはデフォルト値を持つ
 */
data class HistoryEntry(
    val key: String,           // 正規化キー（例: https://host/board#threadNo）
    val url: String,           // スレッドURL
    val title: String,         // スレッドタイトル
    val lastViewedAt: Long,    // 最終閲覧時刻
    val thumbnailUrl: String? = null, // サムネイル画像URL（任意）
    val threadUrl: String? = null,    // サムネイル取得や NG ルール生成時の基準となるスレURL（旧データは未保存）
    // 新着優先の並び替えに必要なフィールド（既存JSONとの互換のため既定値付き）
    val lastUpdatedAt: Long = 0L,      // 新規レスを検知した最終時刻
    val lastKnownReplyNo: Int = 0,     // 取得時点での最終レス番号
    val lastViewedReplyNo: Int = 0,    // ユーザが閲覧した最終レス番号
    val unreadCount: Int = 0,          // 未読数（派生だが保存して高速化）
    // アーカイブ状態
    val isArchived: Boolean = false,   // アーカイブ済みかどうか
    val archivedAt: Long = 0L          // アーカイブ日時（未アーカイブ時は0）
)
