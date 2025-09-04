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
 * - ViewModel破棄時にBitmapを明示的にrecycleしてメモリを解放
 */
class ImageEditViewModel : ViewModel() {

    // 編集処理本体。ImageEditorCanvas等から参照される
    var editingEngine: EditingEngine? = null
        private set

    // 元画像の保持。エンジン生成時に参照し、onClearedで解放する
    var sourceBitmap: Bitmap? = null
        private set

    /**
     * 編集エンジンを未初期化の場合のみ生成するヘルパー。
     * `sourceBitmap` を保持し、それを元に `EditingEngine` を構築する。
     */
    fun initializeEngine(bitmap: Bitmap) {
        if (editingEngine == null) {
            this.sourceBitmap = bitmap
            editingEngine = EditingEngine(bitmap)
        }
    }

    /**
     * 非UIスレッドで事前に用意した Bitmap/Engine を適用する。
     */
    fun setPreparedEngine(bitmap: Bitmap, engine: EditingEngine) {
        this.sourceBitmap = bitmap
        this.editingEngine = engine
    }

    override fun onCleared() {
        super.onCleared()
        // 保持しているBitmapを解放してメモリリークを防止
        sourceBitmap?.recycle()
        sourceBitmap = null
        editingEngine = null
    }
}
