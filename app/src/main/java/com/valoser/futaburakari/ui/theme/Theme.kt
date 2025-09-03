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

// Base (Purple) scheme aligned to XML
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

// Green scheme (from XML ThemeOverlay/Theme)
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

// Blue scheme (from XML)
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

// Orange scheme (from XML)
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

private fun schemeFor(colorMode: String?, dark: Boolean): androidx.compose.material3.ColorScheme {
    return when (colorMode) {
        "green" -> if (dark) DarkGreenScheme else LightGreenScheme
        "blue" -> if (dark) DarkBlueScheme else LightBlueScheme
        "orange" -> if (dark) DarkOrangeScheme else LightOrangeScheme
        "purple" -> if (dark) DarkPurpleScheme else LightPurpleScheme
        else -> if (dark) DarkPurpleScheme else LightPurpleScheme
    }
}

@Composable
fun FutaburakariTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorMode: String? = null,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = run {
        val cm = colorMode?.lowercase()
        // If colorMode is explicitly specified, prefer it over dynamic colors
        if (!cm.isNullOrBlank()) {
            schemeFor(cm, darkTheme)
        } else if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            schemeFor("purple", darkTheme)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
