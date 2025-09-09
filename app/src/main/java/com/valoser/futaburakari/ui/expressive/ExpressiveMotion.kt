package com.valoser.futaburakari.ui.expressive

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Expressive 向けに調整したモーション定数群。
 * バネ・イージング・時間を少しゆったり/しなやかに設定し、視覚的な抑揚を高めます。
 *
 * 使い方の例:
 * - `animateFloatAsState(targetValue, animationSpec = ExpressiveMotionDefaults.Enter)`
 * - `tween(..., easing = ExpressiveMotionDefaults.TweenMedium().easing)` 等
 */
object ExpressiveMotionDefaults {
    val Enter = spring<Float>(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = Spring.DampingRatioMediumBouncy
    )
    val Exit = spring<Float>(
        stiffness = Spring.StiffnessLow,
        dampingRatio = Spring.DampingRatioNoBouncy
    )
    fun TweenShort() = tween<Float>(durationMillis = 180, easing = LinearOutSlowInEasing)
    fun TweenMedium() = tween<Float>(durationMillis = 280, easing = FastOutSlowInEasing)
    fun TweenLong() = tween<Float>(durationMillis = 420, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
}
