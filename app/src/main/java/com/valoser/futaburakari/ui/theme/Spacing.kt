package com.valoser.futaburakari.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * Spacing tokens for layout paddings/margins. Use these instead of raw dp.
 */
data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 40.dp,
)

/** Baseline spacing set (compact). */
val BaselineSpacing = Spacing(
    xxs = 2.dp,
    xs = 4.dp,
    s = 8.dp,
    m = 12.dp,
    l = 16.dp,
    xl = 24.dp,
    xxl = 32.dp,
    xxxl = 40.dp,
)

/** Expressive spacing set (softer/wider). */
val ExpressiveSpacing = Spacing(
    xxs = 3.dp,
    xs = 6.dp,
    s = 10.dp,
    m = 14.dp,
    l = 20.dp,
    xl = 28.dp,
    xxl = 36.dp,
    xxxl = 48.dp,
)

val LocalSpacing = compositionLocalOf { BaselineSpacing }

/**
 * Scale spacing by a factor derived from fontScale. We use sqrt() to avoid over-expansion.
 * Clamped for stability across extreme settings.
 */
fun Spacing.scaledByFont(fontScale: Float): Spacing {
    val base = sqrt(fontScale).coerceIn(0.9f, 1.15f)
    fun Dp.scale() = (this.value * base).dp
    return Spacing(
        xxs = xxs.scale(),
        xs = xs.scale(),
        s = s.scale(),
        m = m.scale(),
        l = l.scale(),
        xl = xl.scale(),
        xxl = xxl.scale(),
        xxxl = xxxl.scale(),
    )
}

