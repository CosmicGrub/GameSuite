package com.gamesuite.uikit

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.gamesuite.settings.GameSpeed

/**
 * A one-tap "Game speed" entry for [GameChrome]'s `extraItems` menu: each tap steps to the next
 * [GameSpeed] (Slow -> Relaxed -> Normal -> Quick) and the menu stays open so the new label is
 * visible. [current] must come from the settings flow (or [com.gamesuite.settings.LocalGameSpeed])
 * and [onChange] must persist it, so the change survives the game and applies everywhere.
 */
@Composable
fun ColumnScope.GameSpeedMenuItem(current: GameSpeed, onChange: (GameSpeed) -> Unit) {
    DropdownMenuItem(
        text = { Text("Game speed: ${current.label}") },
        onClick = { onChange(current.next()) },
        modifier = Modifier.semantics {
            contentDescription = "Game speed: ${current.label}. ${current.description} Tap to change."
        }
    )
}
