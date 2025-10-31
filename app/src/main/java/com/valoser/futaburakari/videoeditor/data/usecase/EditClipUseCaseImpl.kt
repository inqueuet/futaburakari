package com.valoser.futaburakari.videoeditor.data.usecase

import android.util.Log
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import com.valoser.futaburakari.videoeditor.domain.model.VideoClip
import com.valoser.futaburakari.videoeditor.domain.model.AudioClip
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

            // 分割位置における元動画ソースの時刻を計算
            val splitSourceTime = targetClip.startTime + position

            // クリップを2つに分割
            val firstClip = targetClip.copy(
                endTime = splitSourceTime
            )
            val secondClip = targetClip.copy(
                id = UUID.randomUUID().toString(),
                startTime = splitSourceTime,
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

    /**
     * 共通: AudioClip削除/トリム処理
     */
    private fun processAudioClipsForTimeRange(
        tracks: List<com.valoser.futaburakari.videoeditor.domain.model.AudioTrack>,
        absoluteDeleteStart: Long,
        absoluteDeleteEnd: Long,
        deleteLength: Long
    ): List<com.valoser.futaburakari.videoeditor.domain.model.AudioTrack> {
        return tracks.map { track ->
            val newAudioClipsForTrack = mutableListOf<AudioClip>()
            for (clip in track.clips) {
                val clipAbsoluteStart = clip.position
                val clipAbsoluteEnd = clip.position + clip.duration

                when {
                    // Case 1: Clip is entirely before the deleted range
                    clipAbsoluteEnd <= absoluteDeleteStart -> {
                        newAudioClipsForTrack.add(clip)
                    }
                    // Case 2: Clip is entirely after the deleted range
                    clipAbsoluteStart >= absoluteDeleteEnd -> {
                        newAudioClipsForTrack.add(clip.copy(position = clip.position - deleteLength))
                    }
                    // Case 3: Clip is entirely within the deleted range (remove it)
                    clipAbsoluteStart >= absoluteDeleteStart && clipAbsoluteEnd <= absoluteDeleteEnd -> {
                        // Do not add
                    }
                    // Case 4: Deleted range is entirely within the clip (split into two)
                    clipAbsoluteStart < absoluteDeleteStart && clipAbsoluteEnd > absoluteDeleteEnd -> {
                        val leftClip = clip.copy(
                            id = UUID.randomUUID().toString(),
                            endTime = clip.startTime + (absoluteDeleteStart - clipAbsoluteStart),
                            volumeKeyframes = clip.volumeKeyframes.filter { it.time < (absoluteDeleteStart - clipAbsoluteStart) }
                        )
                        newAudioClipsForTrack.add(leftClip)

                        val rightClip = clip.copy(
                            id = UUID.randomUUID().toString(),
                            startTime = clip.startTime + (absoluteDeleteEnd - clipAbsoluteStart),
                            position = clip.position + (absoluteDeleteEnd - clipAbsoluteStart) - deleteLength,
                            volumeKeyframes = clip.volumeKeyframes
                                .filter { it.time >= (absoluteDeleteEnd - clipAbsoluteStart) }
                                .map { it.copy(time = it.time - (absoluteDeleteEnd - absoluteDeleteStart)) }
                        )
                        newAudioClipsForTrack.add(rightClip)
                    }
                    // Case 5: Deleted range overlaps the start of the clip (trim start)
                    clipAbsoluteStart < absoluteDeleteStart && clipAbsoluteEnd > absoluteDeleteStart && clipAbsoluteEnd <= absoluteDeleteEnd -> {
                        val trimmedClip = clip.copy(
                            endTime = clip.startTime + (absoluteDeleteStart - clipAbsoluteStart),
                            volumeKeyframes = clip.volumeKeyframes.filter { it.time < (absoluteDeleteStart - clipAbsoluteStart) }
                        )
                        newAudioClipsForTrack.add(trimmedClip)
                    }
                    // Case 6: Deleted range overlaps the end of the clip (trim end)
                    clipAbsoluteStart >= absoluteDeleteStart && clipAbsoluteStart < absoluteDeleteEnd && clipAbsoluteEnd > absoluteDeleteEnd -> {
                        val trimmedClip = clip.copy(
                            startTime = clip.startTime + (absoluteDeleteEnd - clipAbsoluteStart),
                            position = clip.position + (absoluteDeleteEnd - clipAbsoluteStart) - deleteLength,
                            volumeKeyframes = clip.volumeKeyframes
                                .filter { it.time >= (absoluteDeleteEnd - clipAbsoluteStart) }
                                .map { it.copy(time = it.time - (absoluteDeleteEnd - clipAbsoluteStart)) }
                        )
                        newAudioClipsForTrack.add(trimmedClip)
                    }
                    // Unexpected case - log warning and keep original clip
                    else -> {
                        Log.w("EditClipUseCase", "Unexpected audio clip overlap case: clipStart=$clipAbsoluteStart, clipEnd=$clipAbsoluteEnd, deleteStart=$absoluteDeleteStart, deleteEnd=$absoluteDeleteEnd")
                        newAudioClipsForTrack.add(clip)
                    }
                }
            }
            track.copy(clips = newAudioClipsForTrack.sortedBy { it.position })
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

            val targetVideoClip = session.videoClips.find { it.id == clipId }
                ?: return Result.failure(Exception("Video clip not found"))

            // Convert relative times to absolute session times
            val absoluteDeleteStart = targetVideoClip.position + startTime
            val absoluteDeleteEnd = targetVideoClip.position + endTime
            val deleteLength = absoluteDeleteEnd - absoluteDeleteStart

            val updatedVideoClips = mutableListOf<VideoClip>()
            for (clip in session.videoClips) {
                val clipAbsoluteStart = clip.position
                val clipAbsoluteEnd = clip.position + clip.duration

                // Case 1: Clip is entirely before the deleted range
                if (clipAbsoluteEnd <= absoluteDeleteStart) {
                    updatedVideoClips.add(clip)
                }
                // Case 2: Clip is entirely after the deleted range
                else if (clipAbsoluteStart >= absoluteDeleteEnd) {
                    updatedVideoClips.add(clip.copy(position = clip.position - deleteLength))
                }
                // Case 3: Clip is entirely within the deleted range (remove it)
                else if (clipAbsoluteStart >= absoluteDeleteStart && clipAbsoluteEnd <= absoluteDeleteEnd) {
                    // Do not add, effectively deleting it
                }
                // Case 4: Deleted range is entirely within the clip (split into two)
                else if (clipAbsoluteStart < absoluteDeleteStart && clipAbsoluteEnd > absoluteDeleteEnd) {
                    val leftClip = clip.copy(
                        id = UUID.randomUUID().toString(),
                        endTime = clip.startTime + (absoluteDeleteStart - clipAbsoluteStart)
                    )
                    val rightClip = clip.copy(
                        id = UUID.randomUUID().toString(),
                        startTime = clip.startTime + (absoluteDeleteEnd - clipAbsoluteStart),
                        position = clip.position + (absoluteDeleteEnd - clipAbsoluteStart) - deleteLength
                    )
                    updatedVideoClips.add(leftClip)
                    updatedVideoClips.add(rightClip)
                }
                // Case 5: Deleted range overlaps the start of the clip (trim start)
                else if (clipAbsoluteStart < absoluteDeleteStart && clipAbsoluteEnd > absoluteDeleteStart && clipAbsoluteEnd <= absoluteDeleteEnd) {
                    updatedVideoClips.add(clip.copy(
                        endTime = clip.startTime + (absoluteDeleteStart - clipAbsoluteStart)
                    ))
                }
                // Case 6: Deleted range overlaps the end of the clip (trim end)
                else if (clipAbsoluteStart >= absoluteDeleteStart && clipAbsoluteStart < absoluteDeleteEnd && clipAbsoluteEnd > absoluteDeleteEnd) {
                    updatedVideoClips.add(clip.copy(
                        startTime = clip.startTime + (absoluteDeleteEnd - clipAbsoluteStart),
                        position = clip.position + (absoluteDeleteEnd - clipAbsoluteStart) - deleteLength
                    ))
                }
            }

            val updatedAudioTracks = processAudioClipsForTimeRange(
                session.audioTracks, absoluteDeleteStart, absoluteDeleteEnd, deleteLength
            )

            val updatedSession = session.copy(
                videoClips = updatedVideoClips.sortedBy { it.position },
                audioTracks = updatedAudioTracks
            )
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

            Log.d("EditClipUseCase", "delete() - looking for clipId: $clipId")
            Log.d("EditClipUseCase", "delete() - session has ${session.videoClips.size} clips")
            Log.d("EditClipUseCase", "delete() - clip IDs in session: ${session.videoClips.map { it.id }}")

            val targetVideoClip = session.videoClips.find { it.id == clipId }
            if (targetVideoClip == null) {
                Log.e("EditClipUseCase", "delete() - Video clip not found!")
                return Result.failure(Exception("Video clip not found"))
            }
            Log.d("EditClipUseCase", "delete() - Found target clip at position ${targetVideoClip.position}")

            val absoluteDeleteStart = targetVideoClip.position
            val absoluteDeleteEnd = targetVideoClip.position + targetVideoClip.duration
            val deleteLength = absoluteDeleteEnd - absoluteDeleteStart

            val updatedVideoClips = session.videoClips.filter { it.id != clipId }
                .map { clip ->
                    if (clip.position > targetVideoClip.position) {
                        clip.copy(position = clip.position - deleteLength)
                    } else {
                        clip
                    }
                }

            val updatedAudioTracks = processAudioClipsForTimeRange(
                session.audioTracks, absoluteDeleteStart, absoluteDeleteEnd, deleteLength
            )

            val updatedSession = session.copy(
                videoClips = updatedVideoClips.sortedBy { it.position },
                audioTracks = updatedAudioTracks
            )
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
            val validatedSpeed = when {
                speed.isFinite() && speed > 0f -> speed
                else -> 1f
            }
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedClips = session.videoClips.map { clip ->
                if (clip.id == clipId) {
                    clip.copy(speed = validatedSpeed)
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
