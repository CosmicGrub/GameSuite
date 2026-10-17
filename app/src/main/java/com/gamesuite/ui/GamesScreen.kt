package com.gamesuite.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowWidthSizeClass
import com.gamesuite.R
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.HubFamily
import com.gamesuite.core.SavedGame
import com.gamesuite.core.filterGames
import com.gamesuite.core.gameIdForRoute
import com.gamesuite.core.groupByFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The number of card columns for a window: 1 on phones, 2 on a Fold unfolded, 3 on a tablet. */
@Composable
fun rememberGameColumns(): Int {
    return when (currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass) {
        WindowWidthSizeClass.EXPANDED -> 3
        WindowWidthSizeClass.MEDIUM -> 2
        else -> 1
    }
}

/**
 * A counter that goes up each time the host lifecycle (the nav entry) reaches RESUMED. A screen that
 * lists saves keys its read on it, so coming back from a game (whose save is written while the
 * screen is already composing, mid-transition) shows the post-game state, not a stale list.
 */
@Composable
fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick += 1
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}

/** The live saved games (newest first), read off the main thread once each time a tab is shown. */
@Composable
fun rememberSavedGames(sessionManager: GameSessionManager): List<SavedGame> {
    val resumeTick = rememberResumeTick()
    val saves by produceState(initialValue = emptyList<SavedGame>(), sessionManager, resumeTick) {
        value = withContext(Dispatchers.IO) { sessionManager.savedGames?.all().orEmpty() }
    }
    return saves
}

/**
 * The Games tab: search, family filter chips and every game as a card, grouped by family. A card
 * opens the game's Hub (or launches it directly when it has nothing to choose); see [openGameFromCard].
 */
@Composable
fun GamesScreen(
    sessionManager: GameSessionManager,
    onNavigate: (route: String) -> Unit
) {
    val specs = GameHubSpecs.all
    val favorites = rememberFavorites()
    val saves = rememberSavedGames(sessionManager)
    val savedGameIds = remember(saves) { saves.mapNotNull { gameIdForRoute(specs, it.navRoute) }.toSet() }
    val columns = rememberGameColumns()
    val focusManager = LocalFocusManager.current

    var query by rememberSaveable { mutableStateOf("") }
    var familyName by rememberSaveable { mutableStateOf<String?>(null) }
    val family = familyName?.let { name -> HubFamily.values().firstOrNull { it.name == name } }

    val visible = remember(query, family) { filterGames(specs, query, family) }
    val grouped = remember(visible) { groupByFamily(visible) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth().fillMaxHeight(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.nav_games),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() }
                )
            }
            item(key = "search") {
                val clearLabel = stringResource(R.string.games_search_clear)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.games_search_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { query = "" },
                                modifier = Modifier.semantics { contentDescription = clearLabel }
                            ) { Text("✕", modifier = Modifier.clearAndSetSemantics { }) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                )
            }
            item(key = "chips") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = family == null,
                        onClick = { familyName = null },
                        label = { Text(stringResource(R.string.games_filter_all)) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    com.gamesuite.core.catalogFamilyOrder.forEach { f ->
                        FilterChip(
                            selected = family == f,
                            onClick = { familyName = f.name },
                            label = { Text(stringResource(familyLabelRes(f))) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }

            if (grouped.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.games_no_results),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            grouped.forEach { (fam, list) ->
                item(key = "header-${fam.name}") {
                    Text(
                        stringResource(familyLabelRes(fam)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() }
                    )
                }
                val rows = list.chunked(columns)
                rows.forEachIndexed { index, rowSpecs ->
                    item(key = "row-${fam.name}-$index") {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowSpecs.forEach { spec ->
                                GameCard(
                                    spec = spec,
                                    hasSave = spec.gameId in savedGameIds,
                                    isFavorite = spec.gameId in favorites.ids,
                                    onToggleFavorite = { favorites.toggle(spec.gameId) },
                                    onClick = { openGameFromCard(spec, sessionManager, onNavigate) },
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                            // A short last row keeps every card the same width as the rows above it.
                            repeat(columns - rowSpecs.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}
