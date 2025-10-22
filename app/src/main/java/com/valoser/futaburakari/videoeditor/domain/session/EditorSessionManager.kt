package com.valoser.futaburakari.videoeditor.domain.session

import android.net.Uri
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession

/**
 * エディタセッション管理インターフェース
 */
interface EditorSessionManager {
    /**
     * 新しいセッションを作成
     */
    suspend fun createSession(videoUris: List<Uri>): Result<EditorSession>

    /**
     * 現在のセッションを取得
     */
    suspend fun getCurrentSession(): EditorSession?

    /**
     * セッションを更新
     */
    suspend fun updateSession(session: EditorSession): Result<Unit>

    /**
     * セッションをクリア
     */
    suspend fun clearSession(): Result<Unit>

    /**
     * 状態を保存（Undo用）
     */
    suspend fun saveState(session: EditorSession)

    /**
     * Undo
     */
    suspend fun undo(): EditorSession?

    /**
     * Redo
     */
    suspend fun redo(): EditorSession?

    /**
     * Undoが可能か
     */
    fun canUndo(): Boolean

    /**
     * Redoが可能か
     */
    fun canRedo(): Boolean
}
