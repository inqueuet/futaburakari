package com.valoser.futaburakari

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.valoser.futaburakari.edit.EditingEngine

class ImageEditViewModel : ViewModel() {

    var editingEngine: EditingEngine? = null
        private set

    // ★ 修正: ViewModelがBitmapを直接保持する
    var sourceBitmap: Bitmap? = null
        private set

    fun initializeEngine(bitmap: Bitmap) {
        if (editingEngine == null) {
            // ★ 修正: 自身のプロパティにBitmapをセットする
            this.sourceBitmap = bitmap
            editingEngine = EditingEngine(bitmap)
        }
    }

    // 非UIスレッドで準備したエンジンを適用するためのセッター
    fun setPreparedEngine(bitmap: Bitmap, engine: EditingEngine) {
        this.sourceBitmap = bitmap
        this.editingEngine = engine
    }

    override fun onCleared() {
        super.onCleared()
        // ★ 修正: 自身が保持しているBitmapを解放する
        sourceBitmap?.recycle()
        sourceBitmap = null
        editingEngine = null
    }
}
