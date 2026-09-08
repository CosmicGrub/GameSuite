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

// ---- Midnight Arcade — ports the ESP32 hardware arcade cabinet's own
// palette (esp32-tictactoe/TicTacToeESP32/Theme.h/.cpp) so the Android app
// and the physical cabinet share one visual identity instead of two
// independently-invented "dark" looks: a cool charcoal/slate base, a teal
// accent for "you"/the human side, and a warm amber accent for the
// opponent/AI side.
//
// The ESP32 stores each color as a packed RGB565 uint16_t (5 bits red, 6
// bits green, 5 bits blue) via
//   themeRGB(r,g,b) = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3)
// which quantizes every channel — e.g. blue keeps only its top 5 bits — so
// the color the physical screen actually shows is not quite Theme.h's
// literal 8-bit input, it's whatever that 5/6/5 value expands back out to.
// To port the REAL displayed colors rather than the pre-quantization
// inputs, each value below was decoded the same way the hardware decodes
// it (r5 = r>>3, g6 = g>>2, b5 = b>>3) and rescaled to 0-255 with
// round(channel * 255 / maxForBitDepth) — 31 for the 5-bit channels, 63 for
// the 6-bit green channel. For example THEME_BG's themeRGB(16, 20, 28)
// truncates blue's 28 down to a 5-bit 3, which expands back out to 25, not
// 28 — hence Color(0xFF101419) below ending in 0x19 (25), not 28's 0x1C.
private val MidnightBackground = Color(0xFF101419)   // THEME_BG:          themeRGB(16,20,28)   -> (16,20,25)
private val MidnightSurface = Color(0xFF192431)      // THEME_SURFACE:     themeRGB(30,38,52)   -> (25,36,49)
private val MidnightSurfaceAlt = Color(0xFF29354A)   // THEME_SURFACE_ALT: themeRGB(44,54,72)   -> (41,53,74)
private val MidnightBorder = Color(0xFF636D84)       // THEME_BORDER:      themeRGB(96,108,128) -> (99,109,132)
private val MidnightText = Color(0xFFEFF3F7)         // THEME_TEXT:        themeRGB(236,240,245)-> (239,243,247)
private val MidnightTextDim = Color(0xFF94A2AD)      // THEME_TEXT_DIM:    themeRGB(150,160,175)-> (148,162,173)
private val MidnightHuman = Color(0xFF42CABD)        // THEME_HUMAN (teal):      themeRGB(64,200,190) -> (66,202,189)
private val MidnightAi = Color(0xFFEF9642)           // THEME_AI (warm amber):   themeRGB(235,150,70) -> (239,150,66)
private val MidnightDanger = Color(0xFFDE595A)       // THEME_DANGER:      themeRGB(220,90,90)  -> (222,89,90)

/**
 * A single fixed scheme used regardless of [ThemeMode][com.gamesuite.settings.ThemeMode] —
 * Midnight Arcade IS the deliberately dark/moody "arcade cabinet" look, the
 * same way the physical cabinet's own screen only ever renders this one
 * palette; there's no sensible light-mode inverse of a charcoal cabinet
 * shell, so ThemeMode.LIGHT + MIDNIGHT_ARCADE still renders this same dark
 * scheme (see theme/AppTheme.kt's resolution order).
 */
val MidnightArcadeScheme: ColorScheme = darkColorScheme(
    primary = MidnightHuman,
    onPrimary = MidnightBackground,
    secondary = MidnightAi,
    onSecondary = MidnightBackground,
    background = MidnightBackground,
    onBackground = MidnightText,
    surface = MidnightSurface,
    onSurface = MidnightText,
    surfaceVariant = MidnightSurfaceAlt,
    onSurfaceVariant = MidnightTextDim,
    error = MidnightDanger,
    onError = MidnightText,
    outline = MidnightBorder
)
