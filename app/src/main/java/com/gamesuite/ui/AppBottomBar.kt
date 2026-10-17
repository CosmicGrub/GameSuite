package com.gamesuite.ui

import androidx.annotation.StringRes
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.gamesuite.R

/** The four top-level destinations of the app shell. [route] is the nav route each one lives at. */
enum class AppTab(val route: String, val glyph: String, @StringRes val label: Int) {
    HOME("menu", "🏠", R.string.nav_home),
    GAMES("games", "🎮", R.string.nav_games),
    STATS("stats", "📊", R.string.nav_stats),
    SETTINGS("settings", "⚙", R.string.nav_settings);

    companion object {
        /** The tab whose route is [route], or null for any non-top-level route (a game, the Hub, a lobby). */
        fun forRoute(route: String?): AppTab? = values().firstOrNull { it.route == route }
    }
}

/** Bottom navigation for COMPACT and MEDIUM windows. Shown only on the four top-level routes. */
@Composable
fun AppBottomBar(selected: AppTab?, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier = modifier) {
        AppTab.values().forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                // The glyph is decorative: the label carries the meaning.
                icon = { Text(tab.glyph, modifier = Modifier.clearAndSetSemantics { }) },
                label = { Text(stringResource(tab.label)) },
                alwaysShowLabel = true
            )
        }
    }
}

/** The same four destinations as a side rail, for EXPANDED windows. */
@Composable
fun AppNavRail(selected: AppTab?, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    NavigationRail(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppTab.values().forEach { tab ->
                NavigationRailItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = { Text(tab.glyph, modifier = Modifier.clearAndSetSemantics { }) },
                    label = { Text(stringResource(tab.label)) },
                    alwaysShowLabel = true
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
