package com.valoser.futaburakari

/**
 * 既存のDetailContentから新しいEvent-Sourcingアーキテクチャへの移行ユーティリティ
 *
 * 役割:
 * 1. 既存のDetailContentを新しいStaticDetailContent + DynamicMetadataに分離
 * 2. 互換性レイヤーとして機能
 * 3. 段階的な移行をサポート
 */
object DetailContentMigration {

    /**
     * 既存のDetailContentリストを新しいアーキテクチャに変換
     *
     * @param oldContent 既存のDetailContentリスト
     * @return Pair of (StaticDetailContent, DynamicMetadata map)
     */
    fun migrateFromOldContent(
        oldContent: List<DetailContent>
    ): Pair<List<StaticDetailContent>, Map<String, DynamicMetadata>> {
        val staticContent = mutableListOf<StaticDetailContent>()
        val dynamicMetadata = mutableMapOf<String, DynamicMetadata>()

        oldContent.forEach { content ->
            when (content) {
                is DetailContent.Image -> {
                    // 静的部分（URL、ファイル名）
                    staticContent.add(
                        StaticDetailContent.StaticImage(
                            id = content.id,
                            imageUrl = content.imageUrl,
                            fileName = content.fileName
                        )
                    )

                    // 動的部分（プロンプト）
                    if (content.prompt != null) {
                        dynamicMetadata[content.id] = DynamicMetadata(
                            prompt = content.prompt,
                            extractionStatus = MetadataExtractionStatus.Completed,
                            extractedAt = System.currentTimeMillis()
                        )
                    }
                }

                is DetailContent.Text -> {
                    staticContent.add(
                        StaticDetailContent.StaticText(
                            id = content.id,
                            htmlContent = content.htmlContent,
                            resNum = content.resNum
                        )
                    )
                    // テキストコンテンツに動的メタデータは現在なし
                }

                is DetailContent.Video -> {
                    staticContent.add(
                        StaticDetailContent.StaticVideo(
                            id = content.id,
                            videoUrl = content.videoUrl,
                            fileName = content.fileName
                        )
                    )

                    // 動的部分（プロンプト）
                    if (content.prompt != null) {
                        dynamicMetadata[content.id] = DynamicMetadata(
                            prompt = content.prompt,
                            extractionStatus = MetadataExtractionStatus.Completed,
                            extractedAt = System.currentTimeMillis()
                        )
                    }
                }

                is DetailContent.ThreadEndTime -> {
                    staticContent.add(
                        StaticDetailContent.StaticThreadEndTime(
                            id = content.id,
                            endTime = content.endTime
                        )
                    )
                    // ThreadEndTimeに動的メタデータは現在なし
                }
            }
        }

        return Pair(staticContent, dynamicMetadata)
    }

    /**
     * 新しいアーキテクチャから既存のDetailContentに変換
     * （後方互換性のため）
     *
     * @param staticContent 静的コンテンツ
     * @param dynamicMetadata 動的メタデータ
     * @return 既存形式のDetailContentリスト
     */
    fun convertToOldFormat(
        staticContent: List<StaticDetailContent>,
        dynamicMetadata: Map<String, DynamicMetadata>
    ): List<DetailContent> {
        return staticContent.map { static ->
            val metadata = dynamicMetadata[static.id]
            static.toDetailContent(metadata)
        }
    }

    /**
     * 既存のparseContentFromDocument関数の結果を新形式に変換
     */
    fun migrateParseResult(
        parsedContent: List<DetailContent>
    ): Triple<List<StaticDetailContent>, Map<String, DynamicMetadata>, List<DetailEvent>> {
        val (staticContent, dynamicMetadata) = migrateFromOldContent(parsedContent)

        // 移行に伴って発生させるべきイベント
        val events = mutableListOf<DetailEvent>()

        // 既存のメタデータがある場合のメタデータ更新イベント
        dynamicMetadata.forEach { (contentId, metadata) ->
            events.add(DetailEvent.MetadataUpdated(contentId, metadata))
        }

        return Triple(staticContent, dynamicMetadata, events)
    }
}

/**
 * DetailViewModelで使用する拡張関数
 */

/**
 * 既存のコンテンツリストをEventStoreに読み込む
 */
suspend fun DetailEventStore.loadFromOldContent(
    oldContent: List<DetailContent>,
    url: String
) {
    val (staticContent, dynamicMetadata, events) = DetailContentMigration.migrateParseResult(oldContent)

    // 基本的な読み込みイベント
    val loadEvents = listOf(
        DetailEvent.LoadingStateChanged(true),
        DetailEvent.StaticContentLoaded(staticContent, url)
    ) + events.filter { it is DetailEvent.MetadataUpdated } + listOf(
        DetailEvent.LoadingStateChanged(false)
    )

    applyEvents(loadEvents)
}

/**
 * プロンプトの段階的更新（旧APIとの互換性）
 */
suspend fun DetailEventStore.updatePromptCompatible(
    contentId: String,
    newPrompt: String?
) {
    updateMetadataProgressively(contentId, newPrompt)
}
