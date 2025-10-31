package com.valoser.futaburakari

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * 覗き見防止オーバーレイの描画を担う Drawable。
 *
 * 設定された色/模様の組み合わせに応じて描画内容を調整する。
 */
class PrivacyOverlayDrawable(
    private val style: PrivacyScreenStyle,
    density: Float
) : Drawable() {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highlightPaint: Paint? = if (style.pattern == PrivacyScreenPattern.Pattern) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    } else {
        null
    }

    private val baseColor: Int
    private val baseAlpha: Int
    private val highlightColor: Int?
    private val highlightAlpha: Int?

    private val spacingPx: Float
    private val lineThicknessPx: Float

    private var globalAlpha: Int = 255

    init {
        val intensityMultiplier = style.intensity.alphaMultiplier

        baseColor = when (style.color) {
            PrivacyScreenColor.Dark -> Color.BLACK
            PrivacyScreenColor.Light -> Color.WHITE
        }
        val baseAlphaFraction = when (style.color) {
            PrivacyScreenColor.Dark -> 0.65f
            PrivacyScreenColor.Light -> 0.55f
        } * intensityMultiplier
        baseAlpha = (baseAlphaFraction.coerceIn(0.2f, 0.95f) * 255f).roundToInt()

        if (style.pattern == PrivacyScreenPattern.Pattern) {
            spacingPx = 32f * density
            lineThicknessPx = 4f * density
            when (style.color) {
                PrivacyScreenColor.Dark -> {
                    highlightColor = Color.WHITE
                    val highlightFraction = (0.20f * intensityMultiplier).coerceIn(0.08f, 0.35f)
                    highlightAlpha = (highlightFraction * 255f).roundToInt()
                }
                PrivacyScreenColor.Light -> {
                    highlightColor = Color.BLACK
                    val highlightFraction = (0.12f * intensityMultiplier).coerceIn(0.05f, 0.25f)
                    highlightAlpha = (highlightFraction * 255f).roundToInt()
                }
            }
        } else {
            highlightColor = null
            highlightAlpha = null
            spacingPx = 0f
            lineThicknessPx = 0f
        }
    }

    override fun draw(canvas: Canvas) {
        val alphaFraction = globalAlpha / 255f
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val right = bounds.right.toFloat()
        val bottom = bounds.bottom.toFloat()

        basePaint.color = colorWithAlpha(baseColor, baseAlpha, alphaFraction)
        canvas.drawRect(left, top, right, bottom, basePaint)

        if (style.pattern == PrivacyScreenPattern.Pattern && highlightPaint != null && spacingPx > 0f && lineThicknessPx > 0f) {
            val effectiveHighlightColor = colorWithAlpha(
                highlightColor ?: Color.WHITE,
                highlightAlpha ?: 0,
                alphaFraction
            )
            highlightPaint.color = effectiveHighlightColor

            var y = top - lineThicknessPx
            while (y < bottom) {
                canvas.drawRect(left, y, right, y + lineThicknessPx, highlightPaint)
                y += spacingPx
            }

            var x = left - lineThicknessPx
            while (x < right) {
                canvas.drawRect(x, top, x + lineThicknessPx, bottom, highlightPaint)
                x += spacingPx
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        val newAlpha = alpha.coerceIn(0, 255)
        if (globalAlpha != newAlpha) {
            globalAlpha = newAlpha
            invalidateSelf()
        }
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        highlightPaint?.colorFilter = colorFilter
        invalidateSelf()
    }

    private fun colorWithAlpha(color: Int, baseAlpha: Int, alphaFraction: Float): Int {
        val effectiveAlpha = (baseAlpha * alphaFraction).roundToInt().coerceIn(0, 255)
        return ColorUtils.setAlphaComponent(color, effectiveAlpha)
    }
}
