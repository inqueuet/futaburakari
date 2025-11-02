package com.valoser.futaburakari.videoeditor.data.session

import android.content.Context
import android.net.Uri
import com.valoser.futaburakari.videoeditor.domain.model.*
import com.valoser.futaburakari.videoeditor.domain.session.EditorSessionManager
import com.valoser.futaburakari.videoeditor.data.repository.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * エディタセッション管理の実装
 */
@Singleton
class EditorSessionManagerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository
) : EditorSessionManager {

    private var currentSession: EditorSession? = null
    private val undoStack = mutableListOf<EditorSession>()
    private val redoStack = mutableListOf<EditorSession>()
    private val maxUndoSize = 10  // ★ メモリ使用量を削減

    override suspend fun createSession(videoUris: List<Uri>): Result<EditorSession> {
        return try {
            val videoClips = mutableListOf<VideoClip>()
            val audioTracks = mutableListOf<AudioTrack>()
            var currentPosition = 0L

            for (uri in videoUris) {
                val mediaInfo = mediaRepository.getMediaInfo(uri).getOrThrow()

                val clip = VideoClip(
                    id = UUID.randomUUID().toString(),
                    source = uri,
                    startTime = 0L,
                    endTime = mediaInfo.duration,
                    position = currentPosition,
                    speed = 1f
                )
                videoClips.add(clip)
                currentPosition += clip.duration

                if (mediaInfo.hasAudio) {
                    val audioClip = AudioClip(
                        id = UUID.randomUUID().toString(),
                        source = uri,
                        sourceType = AudioSourceType.VIDEO_ORIGINAL,
                        startTime = 0L,
                        endTime = mediaInfo.duration,
                        position = clip.position
                    )
                    val audioTrack = AudioTrack(
                        id = UUID.randomUUID().toString(),
                        name = "Original Audio ${clip.id.take(8)}",
                        clips = listOf(audioClip)
                    )
                    audioTracks.add(audioTrack)
                }
            }

            val session = EditorSession(
                id = UUID.randomUUID().toString(),
                settings = SessionSettings(),
                videoClips = videoClips,
                audioTracks = audioTracks,
                markers = emptyList(),
                transitions = emptyList()
            )

            currentSession = session
            undoStack.clear()
            redoStack.clear()

            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCurrentSession(): EditorSession? {
        return currentSession
    }

    override suspend fun updateSession(session: EditorSession): Result<Unit> {
        return try {
            // saveState()は呼び出し元で行うため、ここでは呼ばない
            currentSession = session
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearSession(): Result<Unit> {
        currentSession = null
        undoStack.clear()
        redoStack.clear()
        return Result.success(Unit)
    }

    override suspend fun saveState(session: EditorSession) {
        undoStack.add(session)
        if (undoStack.size > maxUndoSize) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
    }

    override suspend fun undo(): EditorSession? {
        if (undoStack.isEmpty()) return null

        currentSession?.let { redoStack.add(it) }
        val previousState = undoStack.removeAt(undoStack.lastIndex)
        currentSession = previousState
        return previousState
    }

    override suspend fun redo(): EditorSession? {
        if (redoStack.isEmpty()) return null

        currentSession?.let { undoStack.add(it) }
        val nextState = redoStack.removeAt(redoStack.lastIndex)
        currentSession = nextState
        return nextState
    }

    override fun canUndo(): Boolean = undoStack.isNotEmpty()

    override fun canRedo(): Boolean = redoStack.isNotEmpty()
}
