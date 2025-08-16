package com.example.hutaburakari.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.example.hutaburakari.edit.EditingEngine

class MosaicOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var zoomImageView: ZoomImageView? = null
    var engine: EditingEngine? = null

    init {
        // PorterDuff 合成の互換性を担保
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val z = zoomImageView ?: return
        val e = engine ?: return
        // 画像と同じ変換を適用してから描く
        val m = z.imageMatrix
        canvas.save()
        canvas.concat(m)
        e.drawMosaicWithMask(canvas)
        canvas.restore()
    }

    /** マスクが変わったらこれを呼ぶ */
    fun invalidateLayer() {
        invalidate()
    }
}
