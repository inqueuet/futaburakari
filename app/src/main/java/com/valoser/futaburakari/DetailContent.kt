package com.valoser.futaburakari

/**
 * スレ詳細画面（Composeリスト）およびキャッシュ層で扱うコンテンツの共通モデル。
 * 各要素は `id` を安定キーとして持ち、Image/Text/Video/ThreadEndTime などの種別を sealed クラスで表現する。
 */
sealed class DetailContent {
    abstract val id: String

    /** 画像コンテンツ。`imageUrl` は実体へのURL。`prompt` は説明やALT相当、`fileName` は任意。 */
    data class Image(
        override val id: String,
        val imageUrl: String,
        val prompt: String? = null,
        val fileName: String? = null,
        val thumbnailUrl: String? = null
    ) : DetailContent()

    /** HTML本文を含むテキストコンテンツ。`resNum` は投稿番号等（任意）。 */
    data class Text(
        override val id: String,
        val htmlContent: String,
        val resNum: String? = null
    ) : DetailContent()

    /** 動画コンテンツ。`videoUrl` は実体へのURL。`prompt`・`fileName`・`thumbnailUrl` は任意。 */
    data class Video(
        override val id: String,
        val videoUrl: String,
        val prompt: String? = null,
        val fileName: String? = null,
        val thumbnailUrl: String? = null
    ) : DetailContent()

    /** スレ終了時刻の表示用メタ情報。 */
    data class ThreadEndTime(override val id: String, val endTime: String) : DetailContent()
}
