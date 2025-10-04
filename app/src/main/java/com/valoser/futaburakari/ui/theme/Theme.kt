package com.valoser.futaburakari.ui.theme

/**
 * アプリ全体のテーマ設定をまとめたファイル。
 *
 * 役割:
 * - Expressive スタイル用のライト/ダーク配色と Dynamic Color 分岐の定義
 * - `FutaburakariTheme` で動的カラー・ダークテーマ・Expressive の切替を行い `MaterialTheme` に適用
 * - タイポグラフィ/シェイプは `MaterialTheme` に渡し、スペーシングは `CompositionLocal` で提供
 */

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
import androidx.preference.PreferenceManager

/**
 * Expressive スタイルのライト配色。
 * Material 3 の `lightColorScheme` に対して Expressive 用のトークンを適用。
 */
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

/**
 * Expressive スタイルのダーク配色。
 * Material 3 の `darkColorScheme` に対して Expressive 用のトークンを適用。
 */
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
 * - Expressive 時はアプリ設定の `pref_key_expressive_use_dynamic_color` が true かつ Android 12+ 以上なら Dynamic Color、
 *   それ以外は [LightExpressiveScheme]/[DarkExpressiveScheme] を使用
 * - 非 Expressive 時は `dynamicColor = true` かつ Android 12+ 以上なら Dynamic Color、そうでなければ Material 3 の既定ライト/ダーク配色
 * - タイポグラフィ/シェイプ/スペーシングを Expressive/Baseline から選択し、フォントスケールに応じてスペーシングを調整
 *
 * @param darkTheme ダークテーマの有効/無効（未指定時はシステム設定に追従）
 * @param dynamicColor Dynamic Color の使用有無（Android 12+ のみ有効）
 * @param expressive Expressive スタイルの使用有無
 * @param content テーマ適用対象のコンテンツ
 */
@Composable
fun FutaburakariTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    expressive: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    // 設定: Expressive 有効時でも端末の Dynamic Color を配色に使用するかどうか
    //      （タイポ/シェイプ/余白は Expressive を適用したまま）
    val expressiveUseDynamicColor = prefs.getBoolean("pref_key_expressive_use_dynamic_color", false)

    val colorScheme = when {
        // 新オプション: Expressive スタイル + Dynamic Color 配色（Android 12+ のみ）
        expressive && expressiveUseDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // 既存: Expressive スタイル + 固定パレット
        expressive -> if (darkTheme) DarkExpressiveScheme else LightExpressiveScheme
        // 通常: Dynamic Color
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> if (darkTheme) darkColorScheme() else lightColorScheme()
    }

    val typography = if (expressive) ExpressiveTypography else Typography
    val shapes: Shapes = if (expressive) ExpressiveShapes else BaselineShapes

    // Spacing トークン: baseline/expressive を選択後、フォントスケールに応じて拡大縮小
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
