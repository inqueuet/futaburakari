package com.valoser.futaburakari

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Event Sourcing パターンのイベントストア
 *
 * このクラスの責任:
 * 1. イベントの蓄積と順序保証
 * 2. 状態の算出と更新
 * 3. 状態変更の通知
 * 4. スレッドセーフティの保証
 */
class DetailEventStore {
    private val mutex = Mutex()

    /** 発生したイベントの履歴（デバッグ・リプレイ用） */
    private val eventHistory = CopyOnWriteArrayList<DetailEvent>()

    /** 現在の状態を保持するMutableStateFlow（外部にはStateFlowとして公開） */
    private val _currentState = MutableStateFlow(DetailScreenState())
    /** UIが購読する読み取り専用StateFlow */
    val currentState: StateFlow<DetailScreenState> = _currentState.asStateFlow()

    /**
     * 単一イベントをスレッドセーフに適用して状態を更新
     *
     * @param event 適用するイベント
     * @return 更新後の状態（イベント履歴へ記録済み）
     */
    suspend fun applyEvent(event: DetailEvent): DetailScreenState = mutex.withLock {
        // イベント履歴に追加
        eventHistory.add(event)

        // 現在の状態に対してイベントを適用
        val newState = reduceDetailState(_currentState.value, event)

        // 状態を更新
        _currentState.update { newState }

        return newState
    }

    /**
     * 複数のイベントを単一トランザクションとして順次適用
     *
     * @param events 適用するイベントのリスト（順序を保持）
     * @return 最終的な状態
     */
    suspend fun applyEvents(events: List<DetailEvent>): DetailScreenState = mutex.withLock {
        var currentState = _currentState.value

        events.forEach { event ->
            eventHistory.add(event)
            currentState = reduceDetailState(currentState, event)
        }

        _currentState.update { currentState }
        return currentState
    }

    /**
     * 現在の状態を取得（スナップショット）
     */
    fun getCurrentSnapshot(): DetailScreenState = _currentState.value

    /**
     * イベント履歴を取得（デバッグ用）
     */
    fun getEventHistory(): List<DetailEvent> = eventHistory.toList()

    /**
     * 状態とイベント履歴をリセット（新しいスレッド表示時など）
     */
    suspend fun reset() = mutex.withLock {
        eventHistory.clear()
        _currentState.update { DetailScreenState() }
    }

    /**
     * 特定の時点の状態を再構築（デバッグ・時間旅行用）
     *
     * @param upToEventIndex 0始まりのイベントインデックス（範囲外は末尾まで適用）
     * @return 再構築された状態
     */
    fun replayToState(upToEventIndex: Int): DetailScreenState {
        val eventsToReplay = eventHistory.take(upToEventIndex + 1)

        var state = DetailScreenState()
        eventsToReplay.forEach { event ->
            state = reduceDetailState(state, event)
        }

        return state
    }
}

/**
 * DetailEventStore 向けの拡張ユーティリティ
 * よく使われる複合操作や派生プロパティを提供
 */

/**
 * 静的コンテンツの読み込みとローディング状態の切り替えを一度に実行
 */
suspend fun DetailEventStore.loadStaticContent(
    content: List<StaticDetailContent>,
    url: String
): DetailScreenState {
    return applyEvents(listOf(
        DetailEvent.LoadingStateChanged(true),
        DetailEvent.StaticContentLoaded(content, url),
        DetailEvent.LoadingStateChanged(false)
    ))
}

/**
 * ローディングを終了させたうえでエラー状態を設定
 */
suspend fun DetailEventStore.setError(error: String?): DetailScreenState {
    return applyEvents(listOf(
        DetailEvent.LoadingStateChanged(false),
        DetailEvent.ErrorSet(error)
    ))
}

/**
 * メタデータの段階的更新。
 * `MetadataExtractionStarted` で InProgress に遷移させ、結果の有無に応じて
 * Completed / Failed と抽出時刻を含む `MetadataUpdated` を連続適用する。
 */
suspend fun DetailEventStore.updateMetadataProgressively(
    contentId: String,
    extractedPrompt: String?
): DetailScreenState {
    return applyEvents(listOf(
        DetailEvent.MetadataExtractionStarted(contentId),
        DetailEvent.MetadataUpdated(
            contentId,
            DynamicMetadata(
                prompt = extractedPrompt,
                extractionStatus = if (extractedPrompt != null)
                    MetadataExtractionStatus.Completed
                else
                    MetadataExtractionStatus.Failed,
                extractedAt = System.currentTimeMillis()
            )
        )
    ))
}

/**
 * 表示用コンテンツの算出（拡張プロパティ）
 */
val DetailEventStore.displayContent: List<DetailContent>
    get() = getCurrentSnapshot().computeDisplayContent()
