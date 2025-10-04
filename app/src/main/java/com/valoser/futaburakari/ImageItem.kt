package com.valoser.futaburakari

/**
 * 一覧やギャラリーで表示する画像項目のモデル。
 *
 * - サムネイルURL、タイトル、レス数表示用文字列、詳細画面遷移用URLを保持
 * - 必要に応じてフルサイズ画像のURLと、その検証状態を併せて保持
 *
 * @property previewUrl サムネイル画像のURL（旧 imageUrl）
 * @property title 表示タイトル
 * @property replyCount レス数等の表示用文字列
 * @property detailUrl 詳細表示へ遷移するためのURL
 * @property fullImageUrl フルサイズ画像のURL（任意、推測/補完された最新候補）
 * @property urlFixNote URL 補正・停止などの注記（UI表示用）
 * @property preferPreviewOnly フル画像が恒常的に404等の場合にプレビュー固定で表示するためのフラグ
 * @property previewUnavailable プレビュー画像自体が存在しない（404/未添付/削除）場合の停止フラグ
 * @property hadFullSuccess 一度でもフル画像の実描画に成功したかどうか
 * @property lastVerifiedFullUrl 直近で実描画に成功したフル画像URL（最優先で利用）
 * @property failedUrls 試行済みで失敗したURLの集合（候補生成から除外）
 */
data class ImageItem(
    val previewUrl: String,      // サムネイル画像のURL（旧 imageUrl）
    val title: String,           // 表示タイトル
    val replyCount: String,      // レス数等の表示用文字列
    val detailUrl: String,       // 詳細表示へ遷移するためのURL
    val fullImageUrl: String? = null, // フルサイズ画像のURL（任意、推測/補完された最新候補）
    val urlFixNote: String? = null,   // URL 補正・停止などの注記（UI表示用）
    val preferPreviewOnly: Boolean = false, // フル画像が恒常的に404等の場合にプレビュー固定で表示するためのフラグ
    val previewUnavailable: Boolean = false, // プレビュー自体が404等で存在しない（未添付/削除）場合の停止フラグ
    val hadFullSuccess: Boolean = false, // 一度でもフル画像の実描画に成功したかどうか
    // 新規追加: フル画像の確定状態をより安定化するためのメタ情報
    val lastVerifiedFullUrl: String? = null, // 直近で実描画に成功したフル画像URL（最優先で利用）
    val failedUrls: Set<String> = emptySet(), // 試行済みで失敗したURLの集合（候補生成から除外）
) {
    /**
     * 実際に表示する画像URLを決定する。
     *
     * 優先度:
     * 1) `lastVerifiedFullUrl` があれば最優先
     * 2) `fullImageUrl` が未失敗かつ `preferPreviewOnly` が無効ならそれを採用
     * 3) それ以外は `previewUrl`
     * `preferPreviewOnly` は一時的にプレビュー固定するためのフラグで、実描画が確認できた URL (`lastVerifiedFullUrl`) には適用しない。
     */
    fun getEffectiveUrl(): String {
        return when {
            !lastVerifiedFullUrl.isNullOrBlank() -> lastVerifiedFullUrl
            !fullImageUrl.isNullOrBlank() && !failedUrls.contains(fullImageUrl) && !preferPreviewOnly -> fullImageUrl
            else -> previewUrl
        }
    }
}
