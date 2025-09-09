package com.valoser.futaburakari.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Shapes
import androidx.compose.ui.platform.LocalDensity

// Expressive Style Color Schemes
private val LightExpressiveScheme = lightColorScheme(
    primary = expressive_primary_light,
    onPrimary = expressive_onPrimary_light,
    primaryContainer = expressive_primaryContainer_light,
    onPrimaryContainer = expressive_onPrimaryContainer_light,
    secondary = expressive_secondary_light,
    onSecondary = expressive_onSecondary_light,
    secondaryContainer = expressive_secondaryContainer_light,
    onSecondaryContainer = expressive_onSecondaryContainer_light,
    tertiary = expressive_tertiary_light,
    onTertiary = expressive_onTertiary_light,
    tertiaryContainer = expressive_tertiaryContainer_light,
    onTertiaryContainer = expressive_onTertiaryContainer_light,
    error = expressive_error_light,
    onError = expressive_onError_light,
    errorContainer = expressive_errorContainer_light,
    onErrorContainer = expressive_onErrorContainer_light,
    background = expressive_background_light,
    onBackground = expressive_onBackground_light,
    surface = expressive_surface_light,
    onSurface = expressive_onSurface_light,
    surfaceVariant = expressive_surfaceVariant_light,
    onSurfaceVariant = expressive_onSurfaceVariant_light,
    outline = expressive_outline_light,
)

private val DarkExpressiveScheme = darkColorScheme(
    primary = expressive_primary_dark,
    onPrimary = expressive_onPrimary_dark,
    primaryContainer = expressive_primaryContainer_dark,
    onPrimaryContainer = expressive_onPrimaryContainer_dark,
    secondary = expressive_secondary_dark,
    onSecondary = expressive_onSecondary_dark,
    secondaryContainer = expressive_secondaryContainer_dark,
    onSecondaryContainer = expressive_onSecondaryContainer_dark,
    tertiary = expressive_tertiary_dark,
    onTertiary = expressive_onTertiary_dark,
    tertiaryContainer = expressive_tertiaryContainer_dark,
    onTertiaryContainer = expressive_onTertiaryContainer_dark,
    error = expressive_error_dark,
    onError = expressive_onError_dark,
    errorContainer = expressive_errorContainer_dark,
    onErrorContainer = expressive_onErrorContainer_dark,
    background = expressive_background_dark,
    onBackground = expressive_onBackground_dark,
    surface = expressive_surface_dark,
    onSurface = expressive_onSurface_dark,
    surfaceVariant = expressive_surfaceVariant_dark,
    onSurfaceVariant = expressive_onSurfaceVariant_dark,
    outline = expressive_outline_dark,
)

/**
 * アプリのテーマ適用エントリ。
 * - `expressive = true` の場合は固定の Expressive スキームを使用
 * - それ以外で `dynamicColor = true` かつ API 31+ は、動的カラーを使用
 * - 上記以外は Material3 のデフォルトのライト/ダーク配色を使用
 * 併せて Typography/Shapes を適用して `MaterialTheme` に内容を渡す。
 */
@Composable
fun FutaburakariTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    expressive: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        expressive -> if (darkTheme) DarkExpressiveScheme else LightExpressiveScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> if (darkTheme) darkColorScheme() else lightColorScheme()
    }

    val typography = if (expressive) ExpressiveTypography else Typography
    val shapes: Shapes = if (expressive) ExpressiveShapes else BaselineShapes

    // Spacing tokens: choose baseline vs expressive, then scale by current fontScale (from configuration)
    val baseSpacing = if (expressive) ExpressiveSpacing else BaselineSpacing
    val fontScale = LocalDensity.current.fontScale
    val spacing = baseSpacing.scaledByFont(fontScale)

    CompositionLocalProvider(LocalSpacing provides spacing) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}
