package com.gamesuite.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.gamesuite.settings.NamedTheme
import com.gamesuite.settings.ThemeMode

/**
 * App-wide theme, driven entirely by settings values instead of hardcoded
 * defaults. Resolution order per docs/SETTINGS_THEMING_ACCESSIBILITY.md §2:
 *   1. themeMode picks light vs. dark (SYSTEM defers to isSystemInDarkTheme()
 *      — this is the ONLY place that call should ever happen; nowhere else
 *      in the app should read the system dark-mode flag directly, or an
 *      explicit Light/Dark choice here would get silently overridden).
 *   2. If dynamicColor is on and the device is API 31+, Material You's
 *      wallpaper-derived palette wins outright — dynamic color and a
 *      curated namedTheme are mutually exclusive.
 *   3. Otherwise namedTheme picks which hand-authored ColorScheme family to
 *      use (light or dark variant per step 1).
 *
 * IMPORTANT for every game screen: colors that are part of gameplay itself
 * (UNO's red/yellow/green/blue, a domino's pip color, etc.) must NEVER read
 * MaterialTheme.colorScheme — those stay fixed literals in each game's own
 * code regardless of app theme, or changing the theme would make cards
 * unreadable/ambiguous. Only chrome (app bars, buttons, dialogs, score
 * panels, this settings screen) should read MaterialTheme.colorScheme.
 */
@Composable
fun AppTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    namedTheme: NamedTheme,
    textScale: Float,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        else -> when (namedTheme) {
            NamedTheme.HIGH_CONTRAST -> if (darkTheme) HighContrastDarkScheme else HighContrastLightScheme
            // MIDNIGHT_ARCADE / FELT_TABLE fall back to Classic until they get their own
            // palettes (see NamedTheme's KDoc) — never an unhandled-branch crash.
            NamedTheme.CLASSIC, NamedTheme.MIDNIGHT_ARCADE, NamedTheme.FELT_TABLE ->
                if (darkTheme) ClassicDarkScheme else ClassicLightScheme
        }
    }

    // remember(textScale) so the 15 TextStyle copies below are only rebuilt when
    // the slider actually moves, not on every unrelated recomposition of this
    // composable (e.g. a theme/color change while textScale stays the same).
    val scaledTypography = remember(textScale) { scaledTypography(textScale) }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = scaledTypography,
        content = content
    )
}

/**
 * Builds a [Typography] whose every style's font size (and line height, where
 * the default specifies one) is [textScale]'s multiple of Typography()'s own
 * default, so the "Text size" setting affects every screen that reads
 * MaterialTheme.typography.* instead of only the settings screen that exposes
 * the slider. Weight/letter-spacing/font family are left untouched — only
 * size should move when this setting changes.
 */
private fun scaledTypography(textScale: Float): Typography {
    val default = Typography()
    return default.copy(
        displayLarge = default.displayLarge.scaled(textScale),
        displayMedium = default.displayMedium.scaled(textScale),
        displaySmall = default.displaySmall.scaled(textScale),
        headlineLarge = default.headlineLarge.scaled(textScale),
        headlineMedium = default.headlineMedium.scaled(textScale),
        headlineSmall = default.headlineSmall.scaled(textScale),
        titleLarge = default.titleLarge.scaled(textScale),
        titleMedium = default.titleMedium.scaled(textScale),
        titleSmall = default.titleSmall.scaled(textScale),
        bodyLarge = default.bodyLarge.scaled(textScale),
        bodyMedium = default.bodyMedium.scaled(textScale),
        bodySmall = default.bodySmall.scaled(textScale),
        labelLarge = default.labelLarge.scaled(textScale),
        labelMedium = default.labelMedium.scaled(textScale),
        labelSmall = default.labelSmall.scaled(textScale)
    )
}

/** Multiplies [factor] into this style's fontSize and lineHeight (if set), leaving
 *  everything else — weight, letter spacing, font family — untouched. */
private fun TextStyle.scaled(factor: Float): TextStyle = copy(
    fontSize = fontSize.scaled(factor),
    lineHeight = lineHeight.scaled(factor)
)

/** Scales a Sp value by [factor]; left as-is if unspecified or expressed in Em,
 *  since Em is already relative to fontSize and would double-scale otherwise. */
private fun TextUnit.scaled(factor: Float): TextUnit =
    if (isSpecified && type == TextUnitType.Sp) (value * factor).sp else this
