package com.valoser.futaburakari.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * シェイプ・トークン定義（Material 3 相当）。
 * - Baseline: デフォルトに近い角丸で情報密度を優先。
 * - Expressive: Baseline より角丸を 4dp ずつ広げ、柔らかい印象を演出。
 *
 * テーマ切替:
 * - `FutaburakariTheme(expressive = false)` -> [BaselineShapes]
 * - `FutaburakariTheme(expressive = true)`  -> [ExpressiveShapes]
 */
/**
 * Baseline（標準）Material 3 のシェイプセット。ほぼデフォルトに準拠します。
 * `FutaburakariTheme(expressive = false)` のときに適用されます。
 */
val BaselineShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Expressive 向けのやわらかいシェイプセット。各サイズを [BaselineShapes] から +4dp とし、丸みを強めます。
 * `FutaburakariTheme(expressive = true)` のときに適用されます。
 */
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(32.dp)
)
