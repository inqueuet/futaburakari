package com.valoser.futaburakari.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Expressive テーマ用のカラーパレット定義。
 * - Material 3 のカラー・ロール（primary/secondary/tertiary など）に対応する色を、
 *   Light/Dark それぞれで定義しています。
 * - 値の一部は `colors.xml` と M3 デフォルトをベースに調整しています。
 * - `FutaburakariTheme(expressive = true)` で Dynamic Color を併用しない場合に利用されます。
 *
 * 注意: ここはカラーの「トークン」定義のみです。Theme への適用は `Theme.kt` 側で行います。
 */
// Light パレット（Expressive）
val expressive_primary_light = Color(0xFF0061A4)
val expressive_onPrimary_light = Color(0xFFFFFFFF)
val expressive_primaryContainer_light = Color(0xFFD1E4FF)
val expressive_onPrimaryContainer_light = Color(0xFF001D36)
val expressive_secondary_light = Color(0xFFB90063)
val expressive_onSecondary_light = Color(0xFFFFFFFF)
val expressive_secondaryContainer_light = Color(0xFFFFD9E2)
val expressive_onSecondaryContainer_light = Color(0xFF3E001D)
val expressive_tertiary_light = Color(0xFF006D3D)
val expressive_onTertiary_light = Color(0xFFFFFFFF)
val expressive_tertiaryContainer_light = Color(0xFF9AF6B9)
val expressive_onTertiaryContainer_light = Color(0xFF00210F)
val expressive_error_light = Color(0xFFB00020)
val expressive_onError_light = Color(0xFFFFFFFF)
val expressive_errorContainer_light = Color(0xFFFCD8DF)
val expressive_onErrorContainer_light = Color(0xFF410E0B)
val expressive_background_light = Color(0xFFFDFCFF)
val expressive_onBackground_light = Color(0xFF1A1C1E)
val expressive_surface_light = Color(0xFFFDFCFF)
val expressive_onSurface_light = Color(0xFF1A1C1E)
val expressive_surfaceVariant_light = Color(0xFFE7E0EC)
val expressive_onSurfaceVariant_light = Color(0xFF49454F)
val expressive_outline_light = Color(0xFF79747E)

// Dark パレット（Expressive）: Light を基準に暗所向けへ最適化
val expressive_primary_dark = Color(0xFF9BCAFF)
val expressive_onPrimary_dark = Color(0xFF003258)
val expressive_primaryContainer_dark = Color(0xFF00497D)
val expressive_onPrimaryContainer_dark = Color(0xFFD1E4FF)
val expressive_secondary_dark = Color(0xFFFFB1C8)
val expressive_onSecondary_dark = Color(0xFF650033)
val expressive_secondaryContainer_dark = Color(0xFF8E004A)
val expressive_onSecondaryContainer_dark = Color(0xFFFFD9E2)
val expressive_tertiary_dark = Color(0xFF7FDD9F)
val expressive_onTertiary_dark = Color(0xFF00391D)
val expressive_tertiaryContainer_dark = Color(0xFF00522C)
val expressive_onTertiaryContainer_dark = Color(0xFF9AF6B9)
val expressive_error_dark = Color(0xFFCF6679)
val expressive_onError_dark = Color(0xFF000000)
val expressive_errorContainer_dark = Color(0xFFB00020)
val expressive_onErrorContainer_dark = Color(0xFFFCD8DF)
val expressive_background_dark = Color(0xFF1A1C1E)
val expressive_onBackground_dark = Color(0xFFE2E2E6)
val expressive_surface_dark = Color(0xFF1A1C1E)
val expressive_onSurface_dark = Color(0xFFE2E2E6)
val expressive_surfaceVariant_dark = Color(0xFF49454F)
val expressive_onSurfaceVariant_dark = Color(0xFFCAC4D0)
val expressive_outline_dark = Color(0xFF938F99)
