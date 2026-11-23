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
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.heightIn
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
    // Four labels share a 312dp bar (78dp each): above 130% font a word like "Settings" no longer
    // fits on one line, so the bar caps its own font scale (Material's guidance for nav bars). The
    // rest of the app keeps scaling, and every item keeps a 48dp+ target.
    CappedFontScale(NAV_MAX_FONT_SCALE) {
        NavigationBar(
            // Read after the screen content, as it is below it.
            modifier = modifier.semantics {
                isTraversalGroup = true
                traversalIndex = NAV_TRAVERSAL_INDEX
            }
        ) {
            AppTab.values().forEach { tab ->
                val label = stringResource(tab.label)
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    // The glyph is decorative: the label carries the meaning.
                    icon = { Text(tab.glyph, modifier = Modifier.clearAndSetSemantics { }) },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    alwaysShowLabel = true,
                    // The tab role and its selected state come from NavigationBarItem itself.
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
    }
}

private const val NAV_MAX_FONT_SCALE = 1.3f
private const val NAV_TRAVERSAL_INDEX = 100f

/** Runs [content] with the font scale limited to [maxScale] (layout density is unchanged). */
@Composable
private fun CappedFontScale(maxScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= maxScale) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, maxScale)) {
            content()
        }
    }
}

/** The same four destinations as a side rail, for EXPANDED windows. */
@Composable
fun AppNavRail(selected: AppTab?, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    NavigationRail(modifier = modifier.fillMaxHeight().semantics { isTraversalGroup = true }) {
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
                    label = { Text(stringResource(tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    alwaysShowLabel = true,
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
