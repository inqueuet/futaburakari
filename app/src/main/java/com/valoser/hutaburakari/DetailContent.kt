package com.valoser.hutaburakari

// import java.util.UUID // ViewModelでIDを生成する場合、ここでは不要になることもあります

/**
 * DetailActivityのRecyclerViewで表示するコンテンツを表すSealed Class。
 * テキストか画像かを区別するために使用します。
 */
sealed class DetailContent {
    abstract val id: String

    data class Image(
        override val id: String,
        val imageUrl: String,
        val prompt: String? = null,
        val fileName: String? = null
    ) : DetailContent()

    data class Text(
        override val id: String,
        val htmlContent: String
    ) : DetailContent()

    data class Video(
        override val id: String,
        val videoUrl: String,
        val prompt: String? = null,
        val fileName: String? = null
    ) : DetailContent()

    data class ThreadEndTime(override val id: String, val endTime: String) : DetailContent()
}
