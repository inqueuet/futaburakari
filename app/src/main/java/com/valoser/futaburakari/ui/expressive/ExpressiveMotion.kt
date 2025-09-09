package com.valoser.futaburakari.ui.expressive

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Expressive テーマ向けのアニメーション仕様（モーション・トークン）。
 * - しなやかで抑揚のある動きを意図した、ばね係数/減衰/イージング/時間を提供します。
 * - 値は Compose の `AnimationSpec<Float>` に合わせてあり、`animate*` 系 API にそのまま渡せます。
 *
 * 使用例:
 * - `animateFloatAsState(targetValue, animationSpec = ExpressiveMotionDefaults.Enter)`
 * - `tween(..., easing = ExpressiveMotionDefaults.TweenMedium().easing)` など
 *
 * 注意:
 * - 視覚的な一貫性のため、画面内/外の移動には `Enter`/`Exit` を優先的に用いてください。
 * - 時間の短縮が必要な箇所のみ `TweenShort/Medium/Long` を選択します。
 */
object ExpressiveMotionDefaults {
    /** 画面・要素の入場に適したスプリング。軽めのバネでしなやかな加速。 */
    val Enter = spring<Float>(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = Spring.DampingRatioMediumBouncy
    )
    /** 画面・要素の退場に適したスプリング。低めの剛性で余韻を抑制。 */
    val Exit = spring<Float>(
        stiffness = Spring.StiffnessLow,
        dampingRatio = Spring.DampingRatioNoBouncy
    )
    /** 短いタイムライン。クリック応答など瞬発的な変化に。 */
    fun TweenShort() = tween<Float>(durationMillis = 180, easing = LinearOutSlowInEasing)
    /** 標準のタイムライン。多くの UI 変化の基本に。 */
    fun TweenMedium() = tween<Float>(durationMillis = 280, easing = FastOutSlowInEasing)
    /** 長めのタイムライン。移動量が大きい/目立つ変化に。 */
    fun TweenLong() = tween<Float>(durationMillis = 420, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
}
