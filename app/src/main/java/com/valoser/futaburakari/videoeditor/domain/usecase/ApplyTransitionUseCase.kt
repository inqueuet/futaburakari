package com.valoser.futaburakari.videoeditor.domain.usecase

import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import com.valoser.futaburakari.videoeditor.domain.model.Transition
import com.valoser.futaburakari.videoeditor.domain.model.TransitionType

/**
 * トランジション適用のユースケース
 */
interface ApplyTransitionUseCase {
    /**
     * トランジションを追加
     */
    suspend fun addTransition(
        clipId: String,
        type: TransitionType,
        duration: Long
    ): Result<EditorSession>

    /**
     * トランジションを削除
     */
    suspend fun removeTransition(
        position: Long
    ): Result<EditorSession>

    /**
     * トランジションを更新
     */
    suspend fun updateTransition(
        position: Long,
        type: TransitionType,
        duration: Long
    ): Result<EditorSession>
}
