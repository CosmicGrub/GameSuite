package com.gamesuite.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ---- Classic — the app's original look, just formalized into an explicit scheme
// instead of relying on MaterialTheme's unstated defaults. ----

private val ClassicPrimaryLight = Color(0xFF6750A4)
private val ClassicSecondaryLight = Color(0xFF625B71)

val ClassicLightScheme: ColorScheme = lightColorScheme(
    primary = ClassicPrimaryLight,
    secondary = ClassicSecondaryLight,
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE)
)

val ClassicDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    background = Color(0xFF1C1B1F),
    surface = Color(0xFF1C1B1F)
)

// ---- High Contrast — near-black/near-white, thick separation, no
// low-contrast pastels. Targets WCAG AAA-oriented contrast per
// docs/SETTINGS_THEMING_ACCESSIBILITY.md §2. ----

val HighContrastLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF000000),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    error = Color(0xFFB00020),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF000000)
)

val HighContrastDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    error = Color(0xFFFF6E6E),
    onError = Color(0xFF000000),
    outline = Color(0xFFFFFFFF)
)
