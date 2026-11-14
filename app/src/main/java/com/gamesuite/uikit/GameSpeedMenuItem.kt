package com.gamesuite.uikit

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.gamesuite.R
import com.gamesuite.settings.GameSpeed

/**
 * A one-tap "Game speed" entry for [GameChrome]'s `extraItems` menu: each tap steps to the next
 * [GameSpeed] (Slow -> Relaxed -> Normal -> Quick) and the menu stays open so the new label is
 * visible. [current] must come from the settings flow (or [com.gamesuite.settings.LocalGameSpeed])
 * and [onChange] must persist it, so the change survives the game and applies everywhere.
 */
@Composable
fun ColumnScope.GameSpeedMenuItem(current: GameSpeed, onChange: (GameSpeed) -> Unit) {
    val (labelRes, descRes) = when (current) {
        GameSpeed.SLOW -> R.string.gamespeed_slow to R.string.gamespeed_slow_desc
        GameSpeed.RELAXED -> R.string.gamespeed_relaxed to R.string.gamespeed_relaxed_desc
        GameSpeed.NORMAL -> R.string.gamespeed_normal to R.string.gamespeed_normal_desc
        GameSpeed.QUICK, GameSpeed.ARENA_X4, GameSpeed.ARENA_MAX -> R.string.gamespeed_quick to R.string.gamespeed_quick_desc
    }
    val label = stringResource(labelRes)
    val itemText = stringResource(R.string.gamespeed_menu_item, label)
    val itemDesc = stringResource(R.string.gamespeed_menu_item_desc, label, stringResource(descRes))
    DropdownMenuItem(
        text = { Text(itemText) },
        onClick = { onChange(current.next()) },
        modifier = Modifier.semantics { contentDescription = itemDesc }
    )
}
