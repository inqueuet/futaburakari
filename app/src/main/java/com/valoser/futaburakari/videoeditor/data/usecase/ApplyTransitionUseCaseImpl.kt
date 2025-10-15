package com.valoser.futaburakari.videoeditor.data.usecase

import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import com.valoser.futaburakari.videoeditor.domain.model.Transition
import com.valoser.futaburakari.videoeditor.domain.model.TransitionType
import com.valoser.futaburakari.videoeditor.domain.session.EditorSessionManager
import com.valoser.futaburakari.videoeditor.domain.usecase.ApplyTransitionUseCase
import javax.inject.Inject

/**
 * トランジション適用UseCaseの実装
 */
class ApplyTransitionUseCaseImpl @Inject constructor(
    private val sessionManager: EditorSessionManager
) : ApplyTransitionUseCase {

    override suspend fun addTransition(
        clipId: String,
        type: TransitionType,
        duration: Long
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            // クリップを見つける
            val clip = session.videoClips.find { it.id == clipId }
                ?: return Result.failure(Exception("Clip not found"))

            // クリップの終わりにトランジションを追加
            val transition = Transition(
                position = clip.position + clip.duration - duration,
                type = type,
                duration = duration
            )

            val updatedTransitions = session.transitions + transition
            val updatedSession = session.copy(transitions = updatedTransitions)

            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeTransition(position: Long): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTransitions = session.transitions.filter { it.position != position }
            val updatedSession = session.copy(transitions = updatedTransitions)

            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateTransition(
        position: Long,
        type: TransitionType,
        duration: Long
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTransitions = session.transitions.map { transition ->
                if (transition.position == position) {
                    transition.copy(type = type, duration = duration)
                } else {
                    transition
                }
            }

            val updatedSession = session.copy(transitions = updatedTransitions)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
