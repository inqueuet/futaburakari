package com.valoser.futaburakari

/**
 * 一覧やギャラリーで表示する画像項目のモデル。
 *
 * - サムネイルURL、タイトル、レス数表示用文字列、詳細画面遷移用URLを保持
 * - 必要に応じてフルサイズ画像のURLも保持（任意）
 *
 * @property previewUrl サムネイル画像のURL（旧 imageUrl）
 * @property title 表示タイトル
 * @property replyCount レス数等の表示用文字列
 * @property detailUrl 詳細表示へ遷移するためのURL
 * @property fullImageUrl フルサイズ画像のURL（任意）
 * @property urlFixNote 個別404時の候補探索で置換された際のメモ（UI表示用）
 * @property preferPreviewOnly フル画像の取得に失敗するなどの理由で、プレビュー画像のみを優先表示するフラグ
 * @property previewUnavailable プレビュー画像自体が存在しない（404/未添付/削除）場合に、読み込みを停止するフラグ
 */
data class ImageItem(
    val previewUrl: String,      // サムネイル画像のURL（旧 imageUrl）
    val title: String,           // 表示タイトル
    val replyCount: String,      // レス数等の表示用文字列
    val detailUrl: String,       // 詳細表示へ遷移するためのURL
    val fullImageUrl: String? = null, // フルサイズ画像のURL（任意）
    val urlFixNote: String? = null,   // 個別404時の候補探索で置換された際のメモ（UI表示用）
    val preferPreviewOnly: Boolean = false, // フル画像が恒常的に404等の場合にプレビュー固定で表示するためのフラグ
    val previewUnavailable: Boolean = false, // プレビュー自体が404等で存在しない（未添付/削除）場合の停止フラグ
    val hadFullSuccess: Boolean = false // 一度でもフル画像の実描画に成功したかどうか
)
