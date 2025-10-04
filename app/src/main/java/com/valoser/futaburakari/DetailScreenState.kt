package com.valoser.futaburakari

import com.valoser.futaburakari.ui.detail.SearchState

/**
 * DetailScreen の状態を表現するデータモデル群
 *
 * 根本的な設計原則:
 * 1. イミュータブルな State - 状態更新は新しいインスタンス生成で表現
 * 2. 単一責任(Single Responsibility) - 各データクラスは明確な役割を持つ
 * 3. データ指向の合成 - 表示内容は静的データと動的メタデータの合成で決定
 * 4. 予測可能性(Predictability) - 副作用のない変換関数で状態を導出
 */

/**
 * スレッド詳細画面の完全な状態を表す不変データクラス
 *
 * 設計思想:
 * - staticContent: パース時に決定される不変コンテンツ（テキスト、画像URL等）
 * - dynamicMetadata: 非同期で取得される追加情報（プロンプト、メタデータ等）
 * - uiState: UI表示に関する状態（ローディング、エラー、検索情報、参照URL等）
 */
data class DetailScreenState(
    /** パース時に決定される不変のコンテンツリスト */
    val staticContent: List<StaticDetailContent> = emptyList(),

    /** 各コンテンツIDに対する動的メタデータ（非同期取得） */
    val dynamicMetadata: Map<String, DynamicMetadata> = emptyMap(),

    /** NGフィルタ適用状態 */
    val ngFilterState: NgFilterState = NgFilterState(),

    /** UI表示状態（ローディング、エラー、現在URL、検索状態などを集約） */
    val uiState: DetailUiState = DetailUiState()
) {
    /**
     * 表示用のコンテンツリストを算出する純粋関数
     * 静的コンテンツ + 動的メタデータ + NGフィルタを合成
     */
    fun computeDisplayContent(): List<DetailContent> {
        return staticContent
            .filter { content -> !ngFilterState.shouldHide(content) }
            .map { static ->
                val metadata = dynamicMetadata[static.id]
                static.toDetailContent(metadata)
            }
    }
}

/**
 * パース時に決定される不変のコンテンツ
 * 後から変更されることはない
 */
sealed class StaticDetailContent {
    abstract val id: String

    /** 不変データを表示用DetailContentに変換 */
    abstract fun toDetailContent(metadata: DynamicMetadata?): DetailContent

    data class StaticImage(
        override val id: String,
        val imageUrl: String,
        val fileName: String? = null
    ) : StaticDetailContent() {
        override fun toDetailContent(metadata: DynamicMetadata?): DetailContent.Image {
            return DetailContent.Image(
                id = id,
                imageUrl = imageUrl,
                prompt = metadata?.prompt,
                fileName = fileName
            )
        }
    }

    data class StaticText(
        override val id: String,
        val htmlContent: String,
        val resNum: String? = null
    ) : StaticDetailContent() {
        override fun toDetailContent(metadata: DynamicMetadata?): DetailContent.Text {
            return DetailContent.Text(
                id = id,
                htmlContent = htmlContent,
                resNum = resNum
            )
        }
    }

    data class StaticVideo(
        override val id: String,
        val videoUrl: String,
        val fileName: String? = null,
        val thumbnailUrl: String? = null
    ) : StaticDetailContent() {
        override fun toDetailContent(metadata: DynamicMetadata?): DetailContent.Video {
            return DetailContent.Video(
                id = id,
                videoUrl = videoUrl,
                prompt = metadata?.prompt,
                fileName = fileName,
                thumbnailUrl = thumbnailUrl
            )
        }
    }

    data class StaticThreadEndTime(
        override val id: String,
        val endTime: String
    ) : StaticDetailContent() {
        override fun toDetailContent(metadata: DynamicMetadata?): DetailContent.ThreadEndTime {
            return DetailContent.ThreadEndTime(
                id = id,
                endTime = endTime
            )
        }
    }
}

/**
 * 非同期で取得される動的メタデータ
 * 後から追加・更新されるプロンプトや抽出状態、最終反映時刻を保持する。
 */
data class DynamicMetadata(
    val prompt: String? = null,
    val extractionStatus: MetadataExtractionStatus = MetadataExtractionStatus.Pending,
    val extractedAt: Long? = null
)

/**
 * メタデータ抽出の状態
 */
enum class MetadataExtractionStatus {
    Pending,    // 抽出待ち
    InProgress, // 抽出中
    Completed,  // 抽出完了
    Failed      // 抽出失敗
}

/**
 * NGフィルタの適用状態
 */
data class NgFilterState(
    val rules: List<NgRule> = emptyList(),
    val hiddenContentIds: Set<String> = emptySet()
) {
    fun shouldHide(content: StaticDetailContent): Boolean {
        return hiddenContentIds.contains(content.id)
    }
}

/**
 * UI表示状態
 */
data class DetailUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentUrl: String? = null,
    val searchState: SearchState? = null
)

/**
 * 状態変更イベントの定義
 * すべての状態変更はイベントを通じて行われる
 */
sealed class DetailEvent {
    /** 静的コンテンツの読み込み完了（併せて現在の参照URLを更新） */
    data class StaticContentLoaded(
        val content: List<StaticDetailContent>,
        val url: String
    ) : DetailEvent()

    /** 動的メタデータの更新 */
    data class MetadataUpdated(
        val contentId: String,
        val metadata: DynamicMetadata
    ) : DetailEvent()

    /** メタデータ抽出の開始 */
    data class MetadataExtractionStarted(
        val contentId: String
    ) : DetailEvent()

    /** NGフィルタの適用 */
    data class NgFilterApplied(
        val rules: List<NgRule>,
        val hiddenContentIds: Set<String>
    ) : DetailEvent()

    /** ローディング状態の変更 */
    data class LoadingStateChanged(
        val isLoading: Boolean
    ) : DetailEvent()

    /** エラー状態の設定 */
    data class ErrorSet(
        val error: String?
    ) : DetailEvent()

    /** 検索状態の更新 */
    data class SearchStateUpdated(
        val searchState: SearchState?
    ) : DetailEvent()
}

/**
 * 純粋なリデューサー関数
 * 現在の状態とイベントから新しい状態を算出する
 *
 * この関数は副作用を持たない純粋関数である
 */
fun reduceDetailState(
    currentState: DetailScreenState,
    event: DetailEvent
): DetailScreenState = when (event) {
    is DetailEvent.StaticContentLoaded -> {
        currentState.copy(
            staticContent = event.content,
            uiState = currentState.uiState.copy(currentUrl = event.url)
        )
    }

    is DetailEvent.MetadataUpdated -> {
        currentState.copy(
            dynamicMetadata = currentState.dynamicMetadata + (event.contentId to event.metadata)
        )
    }

    is DetailEvent.MetadataExtractionStarted -> {
        val currentMetadata = currentState.dynamicMetadata[event.contentId]
            ?: DynamicMetadata()
        val updatedMetadata = currentMetadata.copy(
            extractionStatus = MetadataExtractionStatus.InProgress
        )
        currentState.copy(
            dynamicMetadata = currentState.dynamicMetadata + (event.contentId to updatedMetadata)
        )
    }

    is DetailEvent.NgFilterApplied -> {
        currentState.copy(
            ngFilterState = NgFilterState(
                rules = event.rules,
                hiddenContentIds = event.hiddenContentIds
            )
        )
    }

    is DetailEvent.LoadingStateChanged -> {
        currentState.copy(
            uiState = currentState.uiState.copy(isLoading = event.isLoading)
        )
    }

    is DetailEvent.ErrorSet -> {
        currentState.copy(
            uiState = currentState.uiState.copy(error = event.error)
        )
    }

    is DetailEvent.SearchStateUpdated -> {
        currentState.copy(
            uiState = currentState.uiState.copy(searchState = event.searchState)
        )
    }
}
