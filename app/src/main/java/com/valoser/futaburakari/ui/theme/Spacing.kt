package com.valoser.futaburakari.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * レイアウトのパディング/マージン用スペーシング・トークン。
 * - 生の `dp` ではなく、画面全体で一貫性を保つためにこのトークンを利用してください。
 * - xxs〜xxxl の 8 段階を定義し、コンポーネント間の余白サイズを統一します。
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

/** Baseline（標準・ややコンパクト）なスペーシングセット。デフォルトで使用されます。 */
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

/** Expressive（やわらかく広め）なスペーシングセット。Baseline より 1〜8dp 広めに設定しています。 */
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

/** `CompositionLocal` 経由で現在のスペーシングセットを提供します。デフォルトは [BaselineSpacing]。 */
val LocalSpacing = compositionLocalOf { BaselineSpacing }

/**
 * アクセシビリティのフォントスケールに応じてスペーシングをスケールします。
 * - 過度な拡大/縮小を避けるため `sqrt(fontScale)` を用いて緩和。
 * - 極端な設定でもレイアウトが破綻しにくいよう [0.9, 1.15] にクランプします。
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
