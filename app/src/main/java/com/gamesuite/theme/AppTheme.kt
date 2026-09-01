package com.gamesuite.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
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

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
