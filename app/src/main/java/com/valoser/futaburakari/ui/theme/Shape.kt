package com.valoser.futaburakari.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

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
 * Expressive 向けのやわらかいシェイプセット。カード/コンポーネントの丸みを強めます。
 * `FutaburakariTheme(expressive = true)` のときに適用されます。
 */
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(32.dp)
)
