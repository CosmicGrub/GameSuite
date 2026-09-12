package com.gamesuite.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowWidthSizeClass
import com.gamesuite.R
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.stats.StatsViewModel

/**
 * The game library screen. Each button launches a game through the shared
 * GameSessionManager and navigates to that game's route — no game-specific
 * logic lives here, only which players/mode to hand off.
 *
 * Redesigned from a flat 20-button scrolling list (the audited "hasn't scaled with
 * the catalog" finding, independently flagged by both the settings/accessibility and
 * product-strategy reviews) into a categorized library: a first-run welcome banner, a
 * Continue row sourced from real match history (see StatsViewModel), and each game
 * grouped into a titled card instead of one undifferentiated scroll. Every individual
 * button's launch behavior is unchanged from before this pass — only the layout and the
 * two new entry points (Continue, My Stats) are new.
 *
 * Every visible string on this screen is now a string resource (see res/values/strings.xml
 * and res/values-es/strings.xml) — the localization infrastructure starter pass. This is the
 * ONE screen fully extracted as the reference pattern; the other game screens/Settings/Stats
 * are not yet (see README.md).
 */
@Composable
fun MainMenuScreen(
    sessionManager: GameSessionManager,
    settingsViewModel: SettingsViewModel,
    statsViewModel: StatsViewModel,
    onNavigateToGame: (route: String) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToStats: () -> Unit = {}
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val stats by statsViewModel.allStats.collectAsStateWithLifecycle()

    // Fold/Tab compatibility audit finding: this screen's game list was a single fixed
    // column regardless of window width, which is correct on a phone or the Fold's
    // cover screen (COMPACT) but leaves a Fold unfolded (MEDIUM, ~690-829dp wide) or a
    // Tab S9 (EXPANDED, up to ~1280dp landscape) with one skinny column of buttons and
    // huge empty margin on either side. Same runtime size-class signal DeviceClass.kt
    // already established for game boards, read directly here since a menu screen has
    // no two-pane/hinge-split structure worth threading through AdaptiveTwoPane for.
    val widthSizeClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
    val gameColumns = when (widthSizeClass) {
        WindowWidthSizeClass.EXPANDED -> 3
        WindowWidthSizeClass.MEDIUM -> 2
        else -> 1
    }

    // One place per game for "launch its default single-player/CPU configuration" — reused
    // by both that game's own button below and the Continue row, so Continue always lands
    // somewhere immediately playable rather than trying to resume an exact prior lobby
    // (multiplayer modes always need a fresh lobby anyway, so this is a reasonable reading
    // of "continue playing UNO" rather than a limitation).
    val primaryLaunch: Map<String, () -> Unit> = remember(sessionManager) {
        mapOf(
            "tic-tac-toe" to {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(
                        PlayerInfo(playerId = "p1", displayName = "You"),
                        PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                    ),
                    localPlayerIndex = 0
                )
                onNavigateToGame("tic-tac-toe")
            },
            "uno" to {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(
                        PlayerInfo(playerId = "p1", displayName = "You"),
                        PlayerInfo(playerId = "bot1", displayName = "CPU 1", isBot = true),
                        PlayerInfo(playerId = "bot2", displayName = "CPU 2", isBot = true)
                    ),
                    localPlayerIndex = 0
                )
                onNavigateToGame("uno")
            },
            "hangman" to {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                    localPlayerIndex = 0
                )
                onNavigateToGame("hangman")
            },
            "word-search" to {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                    localPlayerIndex = 0
                )
                onNavigateToGame("word-search")
            },
            "crossword" to {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                    localPlayerIndex = 0
                )
                onNavigateToGame("crossword")
            },
            "word-tiles" to {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(
                        PlayerInfo(playerId = "p1", displayName = "You"),
                        PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                    ),
                    localPlayerIndex = 0
                )
                onNavigateToGame("word-tiles")
            },
            "dominoes" to {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(
                        PlayerInfo(playerId = "p1", displayName = "You"),
                        PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                    ),
                    localPlayerIndex = 0
                )
                onNavigateToGame("dominoes")
            },
            "mancala" to {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(
                        PlayerInfo(playerId = "p1", displayName = "You"),
                        PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                    ),
                    localPlayerIndex = 0
                )
                onNavigateToGame("mancala")
            },
            "checkers" to {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(
                        PlayerInfo(playerId = "p1", displayName = "You"),
                        PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                    ),
                    localPlayerIndex = 0
                )
                onNavigateToGame("checkers")
            },
            "chess" to {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(
                        PlayerInfo(playerId = "p1", displayName = "You"),
                        PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                    ),
                    localPlayerIndex = 0
                )
                onNavigateToGame("chess")
            },
            "air-hockey" to {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                    localPlayerIndex = 0
                )
                onNavigateToGame("air-hockey")
            },
            "solitaire" to {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                    localPlayerIndex = 0
                )
                onNavigateToGame("solitaire")
            },
            "sliding-puzzle" to {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                    localPlayerIndex = 0
                )
                onNavigateToGame("sliding-puzzle")
            }
        )
    }

    // Wide-window fix, mirrors AdaptiveTwoPane's own widthIn(max = 840.dp) centering
    // trick: an outer Box fills the real window and centers a width-capped inner
    // Column, so a Tab S9 landscape or Fold-unfolded window gets the game grid (below)
    // centered at a sane reading width instead of every card/row stretching edge to
    // edge across a 1200+dp screen. widthIn(max) is applied BEFORE fillMaxWidth() on
    // the same modifier chain — the reverse order (fillMaxWidth THEN widthIn(max)) is
    // the no-op documented in AdaptiveTwoPane.kt, since fillMaxWidth would already lock
    // min=max=full width before the cap ever ran.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                // Tab S9 input pass (§4c): grouped under focusGroup() so a hardware
                // keyboard's Tab key steps past both header buttons as one cluster before
                // moving into the page body below, instead of landing on every leaf in an
                // order indistinguishable from the rest of the screen.
                Row(modifier = Modifier.focusGroup()) {
                    TextButton(
                        onClick = onNavigateToStats,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Text(stringResource(R.string.menu_my_stats), style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Text(stringResource(R.string.menu_settings), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (!settings.hasSeenOnboarding) {
                OnboardingBanner(onDismiss = { settingsViewModel.setHasSeenOnboarding(true) })
                Spacer(Modifier.height(20.dp))
            }

            val recentlyPlayed = stats.values
                .filter { primaryLaunch.containsKey(it.gameId) && !it.dismissedFromContinue }
                .sortedByDescending { it.lastPlayedEpochMillis }
                .take(3)
            if (recentlyPlayed.isNotEmpty()) {
                Text(stringResource(R.string.menu_continue_playing), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(
                    // focusGroup() (§4c): the Continue row is its own logical cluster of
                    // tiles — Tab moves through them together, then on to the next section.
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    recentlyPlayed.forEach { s ->
                        ContinueTile(
                            stats = s,
                            onContinue = { primaryLaunch[s.gameId]?.invoke() },
                            onRestart = { primaryLaunch[s.gameId]?.invoke() },
                            onDelete = { statsViewModel.dismissFromContinueRow(s.gameId) }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // Seeded from today's date at the call site (see WordSearchGame.startMatch(seed) /
            // SlidingPuzzleGame.startMatch(dailySeed)'s KDocs) so every player gets the same
            // puzzle today — the audited "single highest-leverage replayability feature this
            // genre is missing" pitch.
            GameSection(
                title = stringResource(R.string.section_daily_challenge),
                columns = gameColumns,
                entries = listOf(
                    GameEntry(stringResource(R.string.game_word_search_daily)) {
                        sessionManager.launchGame(
                            mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                            players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                            localPlayerIndex = 0
                        )
                        onNavigateToGame("word-search-daily")
                    },
                    GameEntry(stringResource(R.string.game_sliding_puzzle_daily)) {
                        sessionManager.launchGame(
                            mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                            players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                            localPlayerIndex = 0
                        )
                        onNavigateToGame("sliding-puzzle-daily")
                    }
                )
            )

            Spacer(Modifier.height(16.dp))

            GameSection(
                title = stringResource(R.string.section_board_games),
                columns = gameColumns,
                entries = listOf(
                    GameEntry(stringResource(R.string.game_tictactoe_vs_cpu)) { primaryLaunch.getValue("tic-tac-toe").invoke() },
                    GameEntry(stringResource(R.string.game_tictactoe_pass_play)) {
                        sessionManager.launchGame(
                            mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                            players = listOf(
                                PlayerInfo(playerId = "p1", displayName = "Player 1"),
                                PlayerInfo(playerId = "p2", displayName = "Player 2")
                            ),
                            localPlayerIndex = 0
                        )
                        onNavigateToGame("tic-tac-toe")
                    },
                    GameEntry(stringResource(R.string.game_tictactoe_misere)) {
                        sessionManager.launchGame(
                            mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                            players = listOf(
                                PlayerInfo(playerId = "p1", displayName = "You"),
                                PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                            ),
                            localPlayerIndex = 0
                        )
                        onNavigateToGame("tic-tac-toe-misere")
                    },
                    GameEntry(stringResource(R.string.game_tictactoe_wild)) {
                        sessionManager.launchGame(
                            mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                            players = listOf(
                                PlayerInfo(playerId = "p1", displayName = "You"),
                                PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                            ),
                            localPlayerIndex = 0
                        )
                        onNavigateToGame("tic-tac-toe-wild")
                    },
                    GameEntry(stringResource(R.string.game_dominoes_vs_cpu)) { primaryLaunch.getValue("dominoes").invoke() },
                    GameEntry(stringResource(R.string.game_mancala_vs_cpu)) { primaryLaunch.getValue("mancala").invoke() },
                    GameEntry(stringResource(R.string.game_checkers_vs_cpu)) { primaryLaunch.getValue("checkers").invoke() },
                    GameEntry(stringResource(R.string.game_chess_vs_cpu)) { primaryLaunch.getValue("chess").invoke() }
                )
            )

            Spacer(Modifier.height(16.dp))

            GameSection(
                title = stringResource(R.string.section_cards),
                columns = gameColumns,
                entries = listOf(
                    GameEntry(stringResource(R.string.game_uno_vs_cpu)) { primaryLaunch.getValue("uno").invoke() },
                    GameEntry(stringResource(R.string.game_uno_pass_play)) {
                        sessionManager.launchGame(
                            mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                            players = listOf(
                                PlayerInfo(playerId = "p1", displayName = "Player 1"),
                                PlayerInfo(playerId = "p2", displayName = "Player 2"),
                                PlayerInfo(playerId = "p3", displayName = "Player 3"),
                                PlayerInfo(playerId = "p4", displayName = "Player 4")
                            ),
                            localPlayerIndex = 0
                        )
                        onNavigateToGame("uno")
                    },
                    GameEntry(stringResource(R.string.game_uno_teams)) {
                        sessionManager.launchGame(
                            mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                            players = listOf(
                                PlayerInfo(playerId = "p1", displayName = "Player 1", teamId = 0),
                                PlayerInfo(playerId = "p2", displayName = "Player 2", teamId = 1),
                                PlayerInfo(playerId = "p3", displayName = "Player 3", teamId = 0),
                                PlayerInfo(playerId = "p4", displayName = "Player 4", teamId = 1)
                            ),
                            localPlayerIndex = 0
                        )
                        onNavigateToGame("uno-teams")
                    },
                    // No launchGame() call here — the house-rules picker screen decides the ruleset
                    // AND the player roster itself, then launches the game from there.
                    GameEntry(stringResource(R.string.game_uno_house_rules)) { onNavigateToGame("uno-house-rules") },
                    // No launchGame() call here either — Nearby/Online's roster isn't known until
                    // the lobby finishes connecting, unlike every other button on this screen.
                    GameEntry(stringResource(R.string.game_uno_nearby)) { onNavigateToGame("nearby-entry") },
                    GameEntry(stringResource(R.string.game_uno_online)) { onNavigateToGame("online-entry") },
                    GameEntry(stringResource(R.string.game_solitaire)) { primaryLaunch.getValue("solitaire").invoke() }
                )
            )

            Spacer(Modifier.height(16.dp))

            GameSection(
                title = stringResource(R.string.section_word_games),
                columns = gameColumns,
                entries = listOf(
                    GameEntry(stringResource(R.string.game_hangman)) { primaryLaunch.getValue("hangman").invoke() },
                    GameEntry(stringResource(R.string.game_word_search)) { primaryLaunch.getValue("word-search").invoke() },
                    GameEntry(stringResource(R.string.game_crossword)) { primaryLaunch.getValue("crossword").invoke() },
                    GameEntry(stringResource(R.string.game_word_tiles_vs_cpu)) { primaryLaunch.getValue("word-tiles").invoke() }
                )
            )

            Spacer(Modifier.height(16.dp))

            GameSection(
                title = stringResource(R.string.section_arcade_puzzles),
                columns = gameColumns,
                entries = listOf(
                    GameEntry(stringResource(R.string.game_air_hockey_vs_cpu)) { primaryLaunch.getValue("air-hockey").invoke() },
                    GameEntry(stringResource(R.string.game_air_hockey_pass_play)) {
                        sessionManager.launchGame(
                            mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                            players = listOf(
                                PlayerInfo(playerId = "p1", displayName = "Player 1"),
                                PlayerInfo(playerId = "p2", displayName = "Player 2")
                            ),
                            localPlayerIndex = 0
                        )
                        onNavigateToGame("air-hockey")
                    },
                    GameEntry(stringResource(R.string.game_sliding_puzzle)) { primaryLaunch.getValue("sliding-puzzle").invoke() }
                )
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One tile in the Continue-playing row. A tap launches the game (unchanged
 * from before this pass); a long-press opens a small menu offering Continue/
 * Restart/Delete, per the user's request for a press-and-hold on these tiles.
 *
 * NOTE ON SCOPE: Continue and Restart currently both just launch a fresh
 * game — same as tapping the tile always has — because there is no real
 * per-match save state anywhere in the app yet (GameModule has no
 * saveState()/restoreState(), only pause()/resume() as bare lifecycle hooks;
 * see GameModule.kt's own gameId KDoc, which anticipated "save data" but
 * never got one built). Building an exact resume-where-you-left-off feature
 * is a separate, larger effort across every game engine, not a menu tweak —
 * this is deliberately scaffolded so that feature can slot in later without
 * another UI change: Continue would call the new restore path, Restart would
 * explicitly discard it and call today's fresh-launch path unchanged. Delete
 * is real today — it hides this tile via GameStats.dismissedFromContinue
 * without touching the win/loss record.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ContinueTile(
    stats: com.gamesuite.stats.GameStats,
    onContinue: () -> Unit,
    onRestart: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        ElevatedCard(
            // pointerHoverIcon (§4c): this tile is a real launch/continue target for a
            // game, same as any other game-action button — mouse/trackpad users get a
            // hand cursor over it. combinedClickable() already makes it keyboard-focusable
            // and Enter/Space-activatable on its own, so no separate .focusable() is added.
            modifier = Modifier
                .combinedClickable(
                    onClick = onContinue,
                    onLongClick = { menuExpanded = true }
                )
                .pointerHoverIcon(PointerIcon.Hand)
        ) {
            Column(modifier = Modifier.padding(14.dp).widthIn(min = 110.dp)) {
                Text(stats.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${stats.wins}W – ${stats.losses}L",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.continue_menu_continue)) },
                onClick = { menuExpanded = false; onContinue() },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.continue_menu_restart)) },
                onClick = { menuExpanded = false; onRestart() },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.continue_menu_delete)) },
                onClick = { menuExpanded = false; onDelete() },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            )
        }
    }
}

/** One button's label + launch action inside a [GameSection] — see its KDoc for why this is a list instead of imperative composable calls. */
private data class GameEntry(val label: String, val onClick: () -> Unit)

/**
 * A titled card of game-launch buttons. Reflows [entries] into [columns] columns instead
 * of always one — on a narrow cover screen or plain phone (COMPACT width, columns == 1)
 * this renders exactly like the original single-column list; on a Fold unfolded or Tab S9
 * (MEDIUM/EXPANDED width, columns == 2 or 3, computed once in [MainMenuScreen]) it becomes
 * a real grid instead of one skinny column of buttons with huge empty margin either side —
 * the audited "grid content doesn't reflow on wide windows" finding.
 */
@Composable
private fun GameSection(title: String, columns: Int, entries: List<GameEntry>) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        // focusGroup() (§4c): every button in this category is one Tab-order cluster, so
        // a hardware keyboard moves section-to-section down MainMenuScreen instead of
        // treating each of the 11 games' buttons as an undifferentiated flat sequence.
        Column(modifier = Modifier.padding(12.dp).focusGroup()) {
            val rows = entries.chunked(columns)
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { entry ->
                        GameButton(entry.label, modifier = Modifier.weight(1f), onClick = entry.onClick)
                    }
                    // A row short of `columns` entries (e.g. 8 buttons ÷ 3 columns leaves a
                    // final row of 2) gets weighted spacers in the empty slots instead of
                    // letting its real button(s) stretch wider than every other row's —
                    // keeps every button the same width down a section regardless of count.
                    repeat(columns - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                if (rowIndex != rows.lastIndex) {
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun GameButton(label: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Button(
        onClick = onClick,
        // Material3 buttons already get an automatic 48dp-minimum touch target via
        // minimumInteractiveComponentSize(), but pin it explicitly too now that a button
        // can be one of 2-3 grid columns wide instead of always the full screen width.
        // pointerHoverIcon (§4c): every game-launch button gets a hand cursor for
        // mouse/trackpad users (DeX, Tab S9 keyboard-cover); no effect on touch.
        modifier = modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
    ) {
        Text(label)
    }
}

/**
 * First-run guidance for the audited "flat wall of unlabeled buttons with zero
 * onboarding" finding — a single dismiss-once banner rather than a full tutorial
 * system, since GameSuite's games are simple enough that a short pointer ("tap any
 * game to jump straight in, look for a Settings gear for CPU difficulty and
 * accessibility options") covers the real first-run confusion without building a
 * multi-screen wizard for an 11-game catalog that keeps growing.
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
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) { Text(stringResource(R.string.onboarding_dismiss)) }
            }
        }
    }
}
