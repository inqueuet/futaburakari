package com.valoser.futaburakari.videoeditor.data.usecase

import android.net.Uri
import com.valoser.futaburakari.videoeditor.domain.model.*
import com.valoser.futaburakari.videoeditor.domain.session.EditorSessionManager
import com.valoser.futaburakari.videoeditor.domain.usecase.ManageAudioTrackUseCase
import java.util.UUID
import javax.inject.Inject

import android.content.Context
import android.media.MediaMetadataRetriever
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * 音声トラック管理UseCaseの実装
 */
class ManageAudioTrackUseCaseImpl @Inject constructor(
    private val sessionManager: EditorSessionManager,
    @param:ApplicationContext private val context: Context
) : ManageAudioTrackUseCase {

    override suspend fun muteRange(
        trackId: String,
        clipId: String,
        startTime: Long,
        endTime: Long
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTracks = session.audioTracks.map { track ->
                if (track.id == trackId) {
                    val updatedClips = track.clips.flatMap { clip ->
                        if (clip.id == clipId) {
                            val muteClipId = UUID.randomUUID().toString()
                            listOf(
                                clip.copy(endTime = startTime),
                                AudioClip(
                                    id = muteClipId,
                                    source = Uri.EMPTY,
                                    sourceType = AudioSourceType.SILENCE,
                                    startTime = startTime,
                                    endTime = endTime,
                                    position = clip.position + (startTime - clip.startTime)
                                ),
                                clip.copy(
                                    id = UUID.randomUUID().toString(),
                                    startTime = endTime,
                                    position = clip.position + (endTime - clip.startTime)
                                )
                            ).filter { it.duration > 0 }
                        } else {
                            listOf(clip)
                        }
                    }
                    track.copy(clips = updatedClips)
                } else {
                    track
                }
            }

            val updatedSession = session.copy(audioTracks = updatedTracks)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun replaceAudio(
        trackId: String,
        clipId: String,
        startTime: Long,
        endTime: Long,
        newAudioUri: Uri
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTracks = session.audioTracks.map { track ->
                if (track.id == trackId) {
                    val updatedClips = track.clips.flatMap { clip ->
                        if (clip.id == clipId) {
                            val newClipId = UUID.randomUUID().toString()
                            listOf(
                                clip.copy(endTime = startTime),
                                AudioClip(
                                    id = newClipId,
                                    source = newAudioUri,
                                    sourceType = AudioSourceType.MUSIC,
                                    startTime = 0L,
                                    endTime = endTime - startTime,
                                    position = clip.position + (startTime - clip.startTime)
                                ),
                                clip.copy(
                                    id = UUID.randomUUID().toString(),
                                    startTime = endTime,
                                    position = clip.position + (endTime - clip.startTime)
                                )
                            ).filter { it.duration > 0 }
                        } else {
                            listOf(clip)
                        }
                    }
                    track.copy(clips = updatedClips)
                } else {
                    track
                }
            }

            val updatedSession = session.copy(audioTracks = updatedTracks)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addAudioTrack(
        name: String,
        audioUri: Uri?,
        position: Long
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val newTrack = AudioTrack(
                id = UUID.randomUUID().toString(),
                name = name,
                clips = if (audioUri != null) {
                    val retriever = MediaMetadataRetriever()
                    var duration = 0L
                    try {
                        retriever.setDataSource(context, audioUri)
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally {
                        retriever.release()
                    }

                    listOf(
                        AudioClip(
                            id = UUID.randomUUID().toString(),
                            source = audioUri,
                            sourceType = AudioSourceType.MUSIC,
                            startTime = 0L,
                            endTime = duration,
                            position = position
                        )
                    )
                } else {
                    emptyList()
                }
            )

            val updatedSession = session.copy(
                audioTracks = session.audioTracks + newTrack
            )
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setVolume(
        trackId: String,
        clipId: String,
        volume: Float
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTracks = session.audioTracks.map { track ->
                if (track.id == trackId) {
                    val updatedClips = track.clips.map { clip ->
                        if (clip.id == clipId) {
                            clip.copy(volume = volume)
                        } else {
                            clip
                        }
                    }
                    track.copy(clips = updatedClips)
                } else {
                    track
                }
            }

            val updatedSession = session.copy(audioTracks = updatedTracks)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addVolumeKeyframe(
        trackId: String,
        clipId: String,
        time: Long,
        value: Float
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTracks = session.audioTracks.map { track ->
                if (track.id == trackId) {
                    val updatedClips = track.clips.map { clip ->
                        if (clip.id == clipId) {
                            val newKeyframe = Keyframe(time, value)
                            val updatedKeyframes = (clip.volumeKeyframes + newKeyframe)
                                .sortedBy { it.time }
                            clip.copy(volumeKeyframes = updatedKeyframes)
                        } else {
                            clip
                        }
                    }
                    track.copy(clips = updatedClips)
                } else {
                    track
                }
            }

            val updatedSession = session.copy(audioTracks = updatedTracks)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addFade(
        trackId: String,
        clipId: String,
        fadeType: FadeType,
        duration: FadeDuration
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTracks = session.audioTracks.map { track ->
                if (track.id == trackId) {
                    val updatedClips = track.clips.map { clip ->
                        if (clip.id == clipId) {
                            when (fadeType) {
                                FadeType.FADE_IN -> clip.copy(fadeIn = duration)
                                FadeType.FADE_OUT -> clip.copy(fadeOut = duration)
                            }
                        } else {
                            clip
                        }
                    }
                    track.copy(clips = updatedClips)
                } else {
                    track
                }
            }

            val updatedSession = session.copy(audioTracks = updatedTracks)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun trimAudioClip(
        trackId: String,
        clipId: String,
        startTime: Long,
        endTime: Long
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTracks = session.audioTracks.map { track ->
                if (track.id == trackId) {
                    val updatedClips = track.clips.map { clip ->
                        if (clip.id == clipId) {
                            clip.copy(startTime = startTime, endTime = endTime)
                        } else {
                            clip
                        }
                    }
                    track.copy(clips = updatedClips)
                } else {
                    track
                }
            }

            val updatedSession = session.copy(audioTracks = updatedTracks)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun moveAudioClip(
        trackId: String,
        clipId: String,
        newPosition: Long
    ): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTracks = session.audioTracks.map { track ->
                if (track.id == trackId) {
                    val updatedClips = track.clips.map { clip ->
                        if (clip.id == clipId) {
                            clip.copy(position = newPosition)
                        } else {
                            clip
                        }
                    }
                    track.copy(clips = updatedClips)
                } else {
                    track
                }
            }

            val updatedSession = session.copy(audioTracks = updatedTracks)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAudioClip(trackId: String, clipId: String): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTracks = session.audioTracks.map { track ->
                if (track.id == trackId) {
                    val updatedClips = track.clips.filterNot { it.id == clipId }
                    track.copy(clips = updatedClips)
                } else {
                    track
                }
            }

            val updatedSession = session.copy(audioTracks = updatedTracks)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun copyAudioClip(trackId: String, clipId: String): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTracks = session.audioTracks.map { track ->
                if (track.id == trackId) {
                    val clipToCopy = track.clips.find { it.id == clipId }
                        ?: return Result.failure(Exception("Clip not found"))

                    val newClip = clipToCopy.copy(
                        id = UUID.randomUUID().toString(),
                        position = clipToCopy.position + clipToCopy.duration
                    )

                    val updatedClips = track.clips + newClip
                    track.copy(clips = updatedClips)
                } else {
                    track
                }
            }

            val updatedSession = session.copy(audioTracks = updatedTracks)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun splitAudioClip(trackId: String, clipId: String, position: Long): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTracks = session.audioTracks.map { track ->
                if (track.id == trackId) {
                    val clipToSplit = track.clips.find { it.id == clipId }
                        ?: return Result.failure(Exception("Clip not found"))

                    val splitPoint = position - clipToSplit.position
                    if (splitPoint <= 0 || splitPoint >= clipToSplit.duration) {
                        return Result.failure(Exception("Invalid split position"))
                    }

                    val firstPart = clipToSplit.copy(
                        endTime = clipToSplit.startTime + splitPoint
                    )

                    val secondPart = clipToSplit.copy(
                        id = UUID.randomUUID().toString(),
                        startTime = clipToSplit.startTime + splitPoint,
                        position = position
                    )

                    val updatedClips = track.clips.map {
                        if (it.id == clipId) firstPart else it
                    } + secondPart

                    track.copy(clips = updatedClips)
                } else {
                    track
                }
            }

            val updatedSession = session.copy(audioTracks = updatedTracks)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun toggleMuteAudioClip(trackId: String, clipId: String): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTracks = session.audioTracks.map { track ->
                if (track.id == trackId) {
                    val updatedClips = track.clips.map { clip ->
                        if (clip.id == clipId) {
                            clip.copy(muted = !clip.muted)
                        } else {
                            clip
                        }
                    }
                    track.copy(clips = updatedClips)
                } else {
                    track
                }
            }

            val updatedSession = session.copy(audioTracks = updatedTracks)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeVolumeKeyframe(trackId: String, clipId: String, keyframe: Keyframe): Result<EditorSession> {
        return try {
            val session = sessionManager.getCurrentSession()
                ?: return Result.failure(Exception("No active session"))

            val updatedTracks = session.audioTracks.map { track ->
                if (track.id == trackId) {
                    val updatedClips = track.clips.map { clip ->
                        if (clip.id == clipId) {
                            val updatedKeyframes = clip.volumeKeyframes.filterNot { it == keyframe }
                            clip.copy(volumeKeyframes = updatedKeyframes)
                        } else {
                            clip
                        }
                    }
                    track.copy(clips = updatedClips)
                } else {
                    track
                }
            }

            val updatedSession = session.copy(audioTracks = updatedTracks)
            sessionManager.updateSession(updatedSession)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
