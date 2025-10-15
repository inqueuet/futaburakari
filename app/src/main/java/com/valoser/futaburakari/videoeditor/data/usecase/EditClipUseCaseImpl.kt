package com.valoser.futaburakari.videoeditor.data.usecase

import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import com.valoser.futaburakari.videoeditor.domain.model.VideoClip
import com.valoser.futaburakari.videoeditor.domain.session.EditorSessionManager
import com.valoser.futaburakari.videoeditor.domain.usecase.EditClipUseCase
import java.util.UUID
import javax.inject.Inject

/**
 * クリップ編集UseCaseの実装
 */
class EditClipUseCaseImpl @Inject constructor(
    private val sessionManager: EditorSessionManager
) : EditClipUseCase {

    override suspend fun trim(
        clipId: String,
        startTime: Long,
        endTime: Long
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedClips = session.videoClips.map { clip ->
                if (clip.id == clipId) {
                    clip.copy(startTime = startTime, endTime = endTime)
                } else {
                    clip
                }
            }

            val updatedSession = session.copy(videoClips = updatedClips)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun split(
        clipId: String,
        position: Long
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val targetClip = session.videoClips.find { it.id == clipId }
                ?: return Result.failure(Exception("Clip not found"))

            // クリップを2つに分割
            val firstClip = targetClip.copy(
                endTime = targetClip.startTime + position
            )
            val secondClip = targetClip.copy(
                id = UUID.randomUUID().toString(),
                startTime = targetClip.startTime + position,
                position = targetClip.position + position
            )

            val updatedClips = session.videoClips.flatMap { clip ->
                if (clip.id == clipId) {
                    listOf(firstClip, secondClip)
                } else {
                    listOf(clip)
                }
            }

            val updatedSession = session.copy(videoClips = updatedClips)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteRange(
        clipId: String,
        startTime: Long,
        endTime: Long
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val targetClip = session.videoClips.find { it.id == clipId }
                ?: return Result.failure(Exception("Clip not found"))

            // 範囲削除の実装
            val deleteLength = endTime - startTime

            val updatedClips = session.videoClips.flatMap { clip ->
                if (clip.id == clipId) {
                    if (startTime == clip.startTime && endTime == clip.endTime) {
                        // 全削除の場合は空リストを返す
                        emptyList()
                    } else if (startTime == clip.startTime) {
                        // 先頭から削除
                        listOf(clip.copy(startTime = endTime))
                    } else if (endTime == clip.endTime) {
                        // 末尾から削除
                        listOf(clip.copy(endTime = startTime))
                    } else {
                        // 中間削除 - 2つに分割
                        val firstClip = clip.copy(endTime = startTime)
                        val secondClip = clip.copy(
                            id = UUID.randomUUID().toString(),
                            startTime = endTime,
                            position = clip.position + (startTime - clip.startTime) / clip.speed.toLong()
                        )
                        listOf(firstClip, secondClip)
                    }
                } else if (clip.position > targetClip.position) {
                    // 後続のクリップの位置を調整
                    listOf(clip.copy(position = clip.position - deleteLength))
                } else {
                    listOf(clip)
                }
            }

            val updatedSession = session.copy(videoClips = updatedClips)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun delete(clipId: String): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val targetClip = session.videoClips.find { it.id == clipId }
                ?: return Result.failure(Exception("Clip not found"))

            val updatedClips = session.videoClips.filter { it.id != clipId }
                .map { clip ->
                    if (clip.position > targetClip.position) {
                        clip.copy(position = clip.position - targetClip.duration)
                    } else {
                        clip
                    }
                }

            val updatedSession = session.copy(videoClips = updatedClips)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun move(
        clipId: String,
        newPosition: Long
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedClips = session.videoClips.map { clip ->
                if (clip.id == clipId) {
                    clip.copy(position = newPosition)
                } else {
                    clip
                }
            }.sortedBy { it.position }

            val updatedSession = session.copy(videoClips = updatedClips)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun copy(clipId: String): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val targetClip = session.videoClips.find { it.id == clipId }
                ?: return Result.failure(Exception("Clip not found"))

            val copiedClip = targetClip.copy(
                id = UUID.randomUUID().toString(),
                position = targetClip.position + targetClip.duration
            )

            val updatedClips = session.videoClips + copiedClip

            val updatedSession = session.copy(videoClips = updatedClips)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setSpeed(
        clipId: String,
        speed: Float
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedClips = session.videoClips.map { clip ->
                if (clip.id == clipId) {
                    clip.copy(speed = speed)
                } else {
                    clip
                }
            }

            val updatedSession = session.copy(videoClips = updatedClips)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
