package com.valoser.futaburakari.videoeditor.domain.usecase

import com.valoser.futaburakari.videoeditor.domain.model.EditorSession

/**
 * クリップ編集のユースケース
 */
interface EditClipUseCase {
    /**
     * クリップをトリム
     */
    suspend fun trim(
        clipId: String,
        startTime: Long,
        endTime: Long
    ): Result<EditorSession>

    /**
     * クリップを分割
     */
    suspend fun split(
        clipId: String,
        position: Long
    ): Result<EditorSession>

    /**
     * クリップの範囲を削除
     */
    suspend fun deleteRange(
        clipId: String,
        startTime: Long,
        endTime: Long
    ): Result<EditorSession>

    /**
     * クリップを削除
     */
    suspend fun delete(clipId: String): Result<EditorSession>

    /**
     * クリップを移動
     */
    suspend fun move(
        clipId: String,
        newPosition: Long
    ): Result<EditorSession>

    /**
     * クリップをコピー
     */
    suspend fun copy(clipId: String): Result<EditorSession>

    /**
     * クリップの速度を変更
     */
    suspend fun setSpeed(
        clipId: String,
        speed: Float
    ): Result<EditorSession>
}
