package com.gamesuite.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.dailyModeOf
import com.gamesuite.core.gameGlyph
import com.gamesuite.core.gameIdForRoute
import com.gamesuite.core.launchHubMode
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.stats.StatsViewModel
import com.gamesuite.ui.effects.meshGradientBackground

/**
 * The Home tab of the app shell (docs/design/game-hub.md): the first-run welcome banner, today's
 * daily puzzles, saved games to continue, starred favourites and recently played games. The full
 * game library lives on the Games tab ([GamesScreen]); every card here opens a game's Hub (or, for
 * a Today card, the daily puzzle itself). [onNavigate] receives a nav route.
 *
 * Dropped from the old menu: the "My Stats" / "Settings" header buttons (the navigation bar has
 * those tabs now) and the old per-game launch table (the Hub launches games).
 */
@Composable
fun MainMenuScreen(
    sessionManager: GameSessionManager,
    settingsViewModel: SettingsViewModel,
    statsViewModel: StatsViewModel,
    onNavigate: (route: String) -> Unit
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val stats by statsViewModel.allStats.collectAsStateWithLifecycle()
    val specs = GameHubSpecs.all
    val favorites = rememberFavorites()
    val saves = rememberSavedGames(sessionManager)
    val reducedMotion = LocalReducedMotion.current
    val context = LocalContext.current
    // Reading the configuration re-resolves the catalog text when the locale changes.
    val configuration = LocalConfiguration.current
    val localize: (String) -> String = remember(context, configuration) { { text -> HubText.resolve(context, text) } }

    // Saved games that belong to a Hub game, newest first (a save from a retired route has no Hub and is skipped).
    val continueItems = remember(saves) {
        saves.mapNotNull { saved ->
            val gameId = gameIdForRoute(specs, saved.navRoute) ?: return@mapNotNull null
            val spec = specs.firstOrNull { it.gameId == gameId } ?: return@mapNotNull null
            spec to saved
        }.take(3)
    }
    val favoriteSpecs = specs.filter { it.gameId in favorites.ids }
    val recentStats = stats.values
        .filter { GameHubSpecs.byId(it.gameId) != null && !it.dismissedFromContinue }
        .sortedByDescending { it.lastPlayedEpochMillis }
        .take(4)
    val dailySpecs = specs.filter { it.hasDaily }
    var recentMenuFor by remember { mutableStateOf<String?>(null) }

    // Soft drifting gradient from the theme's own container colours; off under Reduced Motion.
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .meshGradientBackground(
                enabled = !reducedMotion,
                colorA = colorScheme.primaryContainer,
                colorB = colorScheme.secondaryContainer,
                colorC = colorScheme.tertiaryContainer
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 960.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(16.dp))

            if (!settings.hasSeenOnboarding) {
                OnboardingBanner(onDismiss = { settingsViewModel.setHasSeenOnboarding(true) })
                Spacer(Modifier.height(20.dp))
            }

            if (dailySpecs.isNotEmpty()) {
                HomeRow(title = stringResource(R.string.home_today)) {
                    dailySpecs.forEach { spec ->
                        val mode = dailyModeOf(spec) ?: return@forEach
                        GameTile(
                            glyph = gameGlyph(spec.gameId),
                            title = "${localize(spec.title)} · ${localize(mode.title)}",
                            subtitle = "",
                            accent = familyAccent(spec.family),
                            onClick = {
                                if (saves.any { it.navRoute == mode.route }) {
                                    // A daily already in progress: the Hub offers Resume instead of replacing it.
                                    onNavigate("hub/${spec.gameId}")
                                } else {
                                    val result = launchHubMode(sessionManager, spec, mode, mode.defaults())
                                    if (result is HubLaunch.Local) onNavigate(result.route)
                                }
                            }
                        )
                    }
                }
            }

            if (continueItems.isNotEmpty()) {
                HomeRow(title = stringResource(R.string.home_continue)) {
                    continueItems.forEach { (spec, saved) ->
                        GameTile(
                            glyph = gameGlyph(spec.gameId),
                            title = saved.label.ifBlank { localize(spec.title) },
                            subtitle = saved.summary.ifBlank { HubText.savedAgo(context, saved.savedAtMillis, System.currentTimeMillis()) },
                            accent = familyAccent(spec.family),
                            onClick = { onNavigate("hub/${spec.gameId}") }
                        )
                    }
                }
            }

            if (favoriteSpecs.isNotEmpty()) {
                HomeRow(title = stringResource(R.string.home_favourites)) {
                    favoriteSpecs.forEach { spec ->
                        GameTile(
                            glyph = gameGlyph(spec.gameId),
                            title = localize(spec.title),
                            subtitle = localize(spec.tagline),
                            accent = familyAccent(spec.family),
                            onClick = { openGameFromCard(spec, sessionManager, onNavigate) }
                        )
                    }
                }
            }

            if (recentStats.isNotEmpty()) {
                HomeRow(title = stringResource(R.string.home_recent)) {
                    recentStats.forEach { s ->
                        val spec = GameHubSpecs.byId(s.gameId) ?: return@forEach
                        Box {
                            GameTile(
                                glyph = gameGlyph(spec.gameId),
                                title = localize(spec.title),
                                subtitle = stringResource(R.string.home_record, s.wins, s.losses),
                                accent = familyAccent(spec.family),
                                onClick = { openGameFromCard(spec, sessionManager, onNavigate) },
                                // Long-press: hide this tile from the row (the win/loss record is untouched).
                                onLongClick = { recentMenuFor = s.gameId }
                            )
                            DropdownMenu(
                                expanded = recentMenuFor == s.gameId,
                                onDismissRequest = { recentMenuFor = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.continue_menu_continue)) },
                                    onClick = {
                                        recentMenuFor = null
                                        openGameFromCard(spec, sessionManager, onNavigate)
                                    },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.continue_menu_delete)) },
                                    onClick = {
                                        recentMenuFor = null
                                        statsViewModel.dismissFromContinueRow(s.gameId)
                                    },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                )
                            }
                        }
                    }
                }
            }

            // Everything else lives on the Games tab.
            OutlinedButton(
                onClick = { onNavigate("games") },
                modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
            ) { Text(stringResource(R.string.home_browse_games)) }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A titled, horizontally scrolling row of tiles. */
@Composable
private fun HomeRow(title: String, content: @Composable RowScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(Modifier.height(8.dp))
    Row(
        // focusGroup(): a hardware keyboard's Tab moves through a row's tiles together.
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
    Spacer(Modifier.height(24.dp))
}

/**
 * First-run guidance: a single dismiss-once banner rather than a tutorial.
 */
@Composable
private fun OnboardingBanner(onDismiss: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.onboarding_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                ) { Text(stringResource(R.string.onboarding_dismiss)) }
            }
        }
    }
}
