package com.gamesuite.ui.profiles

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** "Midnight Club" (adult) and warm "Kids" palettes from the profile mockups. */
object ProfilePalette {
    val Ink = Color(0xFF0B0D1A)
    val InkRaised = Color(0xFF141829)
    val InkHigh = Color(0xFF1C2138)
    val Ivory = Color(0xFFF3F1EA)
    val Saffron = Color(0xFFFFB547)
    val Iris = Color(0xFF8B7CFF)

    val Cream = Color(0xFFFFF4DC)
    val CreamRaised = Color(0xFFFFE9BD)
    val InkPurple = Color(0xFF2B1B4A)
    val KidAccent = Color(0xFFFF8A3D)
}

private val midnightScheme = darkColorScheme(
    primary = ProfilePalette.Saffron,
    onPrimary = ProfilePalette.Ink,
    secondary = ProfilePalette.Iris,
    onSecondary = ProfilePalette.Ink,
    background = ProfilePalette.Ink,
    onBackground = ProfilePalette.Ivory,
    surface = ProfilePalette.InkRaised,
    onSurface = ProfilePalette.Ivory,
    surfaceVariant = ProfilePalette.InkHigh,
    onSurfaceVariant = ProfilePalette.Ivory,
    surfaceContainerHighest = ProfilePalette.InkHigh,
    outline = Color(0xFF8A8FA8),
    error = Color(0xFFFF8A80)
)

private val kidsScheme = lightColorScheme(
    primary = ProfilePalette.InkPurple,
    onPrimary = ProfilePalette.Cream,
    secondary = ProfilePalette.KidAccent,
    onSecondary = ProfilePalette.InkPurple,
    background = ProfilePalette.Cream,
    onBackground = ProfilePalette.InkPurple,
    surface = ProfilePalette.CreamRaised,
    onSurface = ProfilePalette.InkPurple,
    surfaceVariant = ProfilePalette.CreamRaised,
    onSurfaceVariant = ProfilePalette.InkPurple,
    surfaceContainerHighest = Color(0xFFFFFFFF),
    outline = ProfilePalette.InkPurple
)

@Composable
fun MidnightClubTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = midnightScheme, typography = MaterialTheme.typography, content = content)

@Composable
fun KidsTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = kidsScheme, typography = MaterialTheme.typography, content = content)
