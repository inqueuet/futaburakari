package com.valoser.hutaburakari.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withSave

class BrushOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(30, 255, 255, 255)
    }

    private var cx = -1f
    private var cy = -1f
    private var radiusViewPx = 0f
    private var brushVisible = false

    fun showCircle(cx: Float, cy: Float, radiusViewPx: Float) {
        this.cx = cx
        this.cy = cy
        this.radiusViewPx = radiusViewPx
        this.brushVisible = true
        invalidate()
    }

    fun hideCircle() {
        brushVisible = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!brushVisible || radiusViewPx <= 0f) return

        canvas.withSave {
            canvas.drawCircle(cx, cy, radiusViewPx, paintFill)
            canvas.drawCircle(cx, cy, radiusViewPx, paint)
        }
    }
}
