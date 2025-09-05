package com.valoser.futaburakari.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Shapes

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

// ベース（パープル）配色。旧XMLテーマ値に合わせた定義。
private val DarkPurpleScheme = darkColorScheme(
    primary = Color(0xFF3700B3), // purple_700
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF03DAC5), // teal_200
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFF018786), // teal_700
)

private val LightPurpleScheme = lightColorScheme(
    primary = Color(0xFF6200EE), // purple_500
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF03DAC5),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFF018786),
)

// グリーン配色（旧XMLのオーバーレイ/テーマ由来）
private val LightGreenScheme = lightColorScheme(
    primary = Color(0xFF004D2D), // green_primary
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF03DAC5), // teal_200
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFF018786), // teal_700 as tertiary
)

private val DarkGreenScheme = darkColorScheme(
    primary = Color(0xFF004D2D),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF03DAC5),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFF018786),
)

// ブルー配色（旧XML由来）
private val LightBlueScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF26A69A),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFF00796B),
)

private val DarkBlueScheme = darkColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF26A69A),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFF00796B),
)

// オレンジ配色（旧XML由来）
private val LightOrangeScheme = lightColorScheme(
    primary = Color(0xFFEF6C00),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF00897B),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFF00695C),
)

private val DarkOrangeScheme = darkColorScheme(
    primary = Color(0xFFEF6C00),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF00897B),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFF00695C),
)

/**
 * `colorMode`（省略可）とダーク/ライト指定に応じて配色スキームを解決する。
 * サポート: "purple"（既定）/ "green" / "blue" / "orange"。
 * 未指定/不明な場合はパープルをフォールバックとして使用する。
 */
private fun schemeFor(colorMode: String?, dark: Boolean): androidx.compose.material3.ColorScheme {
    return when (colorMode) {
        "green" -> if (dark) DarkGreenScheme else LightGreenScheme
        "blue" -> if (dark) DarkBlueScheme else LightBlueScheme
        "orange" -> if (dark) DarkOrangeScheme else LightOrangeScheme
        "purple" -> if (dark) DarkPurpleScheme else LightPurpleScheme
        else -> if (dark) DarkPurpleScheme else LightPurpleScheme
    }
}

/**
 * アプリのテーマ適用エントリ。
 * - `colorMode` が指定されていれば対応する固定配色を使用（動的カラーは無視）
 * - そうでなく `dynamicColor` が true かつ API 31+ なら、壁紙ベースの動的カラーを使用
 * - それ以外は固定のパープル配色（ダーク/ライト）を使用
 * 併せて Typography/Shapes を適用して `MaterialTheme` に内容を渡す。
 */
@Composable
fun FutaburakariTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorMode: String? = null,
    dynamicColor: Boolean = true,
    expressive: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        expressive -> if (darkTheme) DarkExpressiveScheme else LightExpressiveScheme
        !colorMode.isNullOrBlank() -> schemeFor(colorMode.lowercase(), darkTheme)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> schemeFor("purple", darkTheme)
    }

    val typography = if (expressive) ExpressiveTypography else Typography
    val shapes: Shapes = if (expressive) ExpressiveShapes else BaselineShapes

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = shapes,
        content = content
    )
}
