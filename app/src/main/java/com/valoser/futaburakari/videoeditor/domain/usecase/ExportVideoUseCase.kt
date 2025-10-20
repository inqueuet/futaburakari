package com.valoser.futaburakari.videoeditor.domain.usecase

import android.net.Uri
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import com.valoser.futaburakari.videoeditor.domain.model.ExportProgress
import kotlinx.coroutines.flow.Flow

/**
 * 動画をエクスポートするユースケース
 */
interface ExportVideoUseCase {
    fun export(
        session: EditorSession,
        outputUri: Uri
    ): Flow<ExportProgress>
}
