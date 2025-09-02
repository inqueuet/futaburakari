package com.valoser.futaburakari.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val drawMatrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var minScale = 1f
    private var maxScale = 6f // 必要に応じて調整してください
    private var currentScale = 1f
    private var lastWidth = 0
    private var lastHeight = 0

    var onMatrixChanged: (() -> Unit)? = null

    // ▼ 追加：変形ロック
    var isTransformLocked: Boolean = false
        private set

    fun setTransformLocked(locked: Boolean) {
        isTransformLocked = locked
    }

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != lastWidth || h != lastHeight) {
            lastWidth = w
            lastHeight = h
            resetToFitCenter()
        }
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        post { resetToFitCenter() }
    }

    fun resetToFitCenter() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw == 0f || vh == 0f || dw <= 0f || dh <= 0f) return

        drawMatrix.reset()
        val scale = min(vw / dw, vh / dh)
        val dx = (vw - dw * scale) * 0.5f
        val dy = (vh - dh * scale) * 0.5f
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
        onMatrixChanged?.invoke()

        minScale = scale
        currentScale = scale
        maxScale = max(4f * minScale, 8f) // 例: 最小スケールの4倍、または8倍の大きい方

        fixTranslation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // ロック中はズーム/パンを無効化（描画用のタッチはActivity側で拾える）
        if (isTransformLocked) return super.onTouchEvent(event) // isTransformLocked のチェックを追加

        parent?.requestDisallowInterceptTouchEvent(true)

        val s = scaleDetector.onTouchEvent(event)
        val g = gestureDetector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            fixTranslation()
        }
        imageMatrix = drawMatrix
        onMatrixChanged?.invoke()
        return s || g || super.onTouchEvent(event)
    }

    fun viewPointToImage(x: Float, y: Float): PointF? {
        val inv = Matrix()
        return if (imageMatrix.invert(inv)) {
            val pts = floatArrayOf(x, y)
            inv.mapPoints(pts)
            PointF(pts[0], pts[1])
        } else null
    }

    fun imageLengthToView(lengthInImagePx: Float): Float {
        val pts = floatArrayOf(0f, 0f, lengthInImagePx, 0f)
        imageMatrix.mapPoints(pts)
        val dx = pts[2] - pts[0]
        val dy = pts[3] - pts[1]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun getTransformedRect(): RectF {
        val d = drawable ?: return RectF()
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(rect)
        return rect
    }

    private fun fixTranslation() {
        val rect = getTransformedRect()
        val vw = width.toFloat()
        val vh = height.toFloat()
        var dx = 0f
        var dy = 0f

        if (rect.width() <= vw) {
            dx = vw * 0.5f - (rect.left + rect.right) * 0.5f
        } else {
            if (rect.left > 0) dx = -rect.left
            if (rect.right < vw) dx = vw - rect.right
        }

        if (rect.height() <= vh) {
            dy = vh * 0.5f - (rect.top + rect.bottom) * 0.5f
        } else {
            if (rect.top > 0) dy = -rect.top
            if (rect.bottom < vh) dy = vh - rect.bottom
        }

        if (dx != 0f || dy != 0f) {
            drawMatrix.postTranslate(dx, dy)
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (isTransformLocked) return false  // ▼ 追加：ロック中は無視
            val scaleFactor = detector.scaleFactor
            val targetScale = (currentScale * scaleFactor).coerceIn(minScale, maxScale)
            val appliedScale = targetScale / currentScale
            drawMatrix.postScale(appliedScale, appliedScale, detector.focusX, detector.focusY)
            currentScale = targetScale
            fixTranslation()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (isTransformLocked) return false  // ▼ 追加
            drawMatrix.postTranslate(-dx, -dy)
            fixTranslation()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isTransformLocked) return false  // ▼ 追加
            val targetZoomScale = if (currentScale < minScale * 1.9f) minScale * 2f else minScale
            val appliedScale = targetZoomScale / currentScale
            drawMatrix.postScale(appliedScale, appliedScale, e.x, e.y)
            currentScale = targetZoomScale.coerceIn(minScale,maxScale)
            fixTranslation()
            return true
        }
    }
}
