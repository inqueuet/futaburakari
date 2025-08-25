package com.valoser.hutaburakari

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.valoser.hutaburakari.edit.EditingEngine

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

    override fun onCleared() {
        super.onCleared()
        // ★ 修正: 自身が保持しているBitmapを解放する
        sourceBitmap?.recycle()
        sourceBitmap = null
        editingEngine = null
    }
}