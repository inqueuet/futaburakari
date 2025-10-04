package com.valoser.futaburakari

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.valoser.futaburakari.edit.EditingEngine

/**
 * 画像編集用のViewModel。
 *
 * - 元画像（Bitmap）と編集エンジン（EditingEngine）のライフサイクルを管理
 * - Activity/Composeからの編集操作に用いるエンジンを提供
 * - 非UIスレッドで準備したエンジンを適用する手段も提供
 * - 既存エンジンの差し替え（setPreparedEngine）にも対応し、事前計算結果を再利用できる
 * - ViewModel破棄時にBitmapを明示的にrecycleしてメモリを解放
 */
class ImageEditViewModel : ViewModel() {

    // 編集処理本体。準備前は null で、初期化後は ImageEditorCanvas 等から参照される
    var editingEngine: EditingEngine? = null
        private set

    // 元画像の参照を保持。初期化時に設定し、onClearedで recycle/null にする
    var sourceBitmap: Bitmap? = null
        private set

    /**
     * 編集エンジンが未初期化の場合のみ生成するヘルパー。
     * 最初に渡された `bitmap` を `sourceBitmap` として保持し、その参照から `EditingEngine` を構築する。
     * 2回目以降の呼び出しでは既存のインスタンスをそのまま再利用する。
     */
    fun initializeEngine(bitmap: Bitmap) {
        if (editingEngine == null) {
            this.sourceBitmap = bitmap
            editingEngine = EditingEngine(bitmap)
        }
    }

    /**
     * 非UIスレッドで事前に用意した Bitmap/Engine を ViewModel に差し替える。
     * 既存の参照がある場合はそのまま上書きするため、必要に応じて呼び出し側で解放する。
     */
    fun setPreparedEngine(bitmap: Bitmap, engine: EditingEngine) {
        this.sourceBitmap = bitmap
        this.editingEngine = engine
    }

    override fun onCleared() {
        super.onCleared()
        // 保持しているBitmapを解放し、エンジン参照も落としてメモリリークを防止
        sourceBitmap?.recycle()
        sourceBitmap = null
        editingEngine = null
    }
}
