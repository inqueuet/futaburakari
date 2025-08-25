package com.valoser.hutaburakari.widget

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var minScale = 1.0f
    var midScale = 2.5f
    var maxScale = 5.0f

    private val TAG = "ZoomableImageView"

    private val baseMatrix = Matrix()      // 初期フィット
    private val userMatrix = Matrix()      // ユーザー操作分
    private val drawMatrix = Matrix()
    private var currentScale = 1f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val delta = target / currentScale
                userMatrix.postScale(delta, delta, detector.focusX, detector.focusY)
                currentScale = target
                fixTranslation()
                applyMatrix()
                Log.d(TAG, "onScale: scale=$currentScale")
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // これがfalseだと以降のイベントが来ない
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = when {
                    currentScale < midScale * 0.9f -> midScale
                    currentScale < maxScale * 0.9f -> maxScale
                    else -> minScale
                }
                val delta = target / currentScale
                userMatrix.postScale(delta, delta, e.x, e.y)
                currentScale = target
                centerIfSmall()
                fixTranslation()
                applyMatrix()
                Log.d(TAG, "onDoubleTap -> scale=$currentScale")
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (currentScale > minScale) {
                    userMatrix.postTranslate(-dx, -dy)
                    fixTranslation()
                    applyMatrix()
                    return true
                }
                return false
            }
        })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        adjustViewBounds = false
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        if (width > 0 && height > 0 && drawable != null) {
            post { resetToFit() }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawable != null) post { resetToFit() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 親（ViewPager2 / RecyclerView / ScrollView）からの奪取を防ぐ
        if (event.pointerCount > 1 || currentScale > minScale) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        val s = scaleDetector.onTouchEvent(event)
        val g = gestureDetector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            fixTranslation()
            applyMatrix()
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return s || g || super.onTouchEvent(event) || true
    }

    /** 親に「今は横/縦にスクロールできる」と知らせる（ViewPager2対策） */
    override fun canScrollHorizontally(direction: Int): Boolean {
        val rect = getImageRect()
        val vw = width.toFloat()
        if (rect.isEmpty) return false
        // 画像が画面より広いときのみスクロール可能
        if (rect.width() <= vw) return false
        return true
    }

    override fun canScrollVertically(direction: Int): Boolean {
        val rect = getImageRect()
        val vh = height.toFloat()
        if (rect.isEmpty) return false
        if (rect.height() <= vh) return false
        return true
    }

    /** 画像を画面内にフィット＆中央配置、ユーザー行列を初期化 */
    fun resetToFit() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return

        baseMatrix.reset()
        val scale = min(vw / dw, vh / dh)
        val tx = (vw - dw * scale) / 2f
        val ty = (vh - dh * scale) / 2f
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(tx, ty)

        userMatrix.reset()
        currentScale = 1f
        minScale = 1f

        applyMatrix()
        Log.d(TAG, "resetToFit: baseScale=$scale")
    }

    private fun applyMatrix() {
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(userMatrix)
        imageMatrix = drawMatrix
        invalidate()
    }

    private fun getImageRect(): RectF {
        val d = drawable ?: return RectF()
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        val m = Matrix(baseMatrix)
        m.postConcat(userMatrix)
        m.mapRect(rect)
        return rect
    }

    private fun centerIfSmall() {
        val rect = getImageRect()
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dx = if (rect.width() < vw) vw / 2f - rect.centerX() else 0f
        val dy = if (rect.height() < vh) vh / 2f - rect.centerY() else 0f
        userMatrix.postTranslate(dx, dy)
    }

    private fun fixTranslation() {
        val rect = getImageRect()
        val vw = width.toFloat()
        val vh = height.toFloat()
        var dx = 0f
        var dy = 0f

        if (rect.width() >= vw) {
            if (rect.left > 0) dx = -rect.left
            if (rect.right < vw) dx = vw - rect.right
        } else {
            dx = vw / 2f - rect.centerX()
        }

        if (rect.height() >= vh) {
            if (rect.top > 0) dy = -rect.top
            if (rect.bottom < vh) dy = vh - rect.bottom
        } else {
            dy = vh / 2f - rect.centerY()
        }

        userMatrix.postTranslate(dx, dy)
    }

    /** 外部から倍率変更（必要なら） */
    fun setScale(scale: Float, focus: PointF? = null) {
        val target = scale.coerceIn(minScale, maxScale)
        val delta = target / currentScale
        val px = focus?.x ?: width / 2f
        val py = focus?.y ?: height / 2f
        userMatrix.postScale(delta, delta, px, py)
        currentScale = target
        centerIfSmall()
        fixTranslation()
        applyMatrix()
    }
}
