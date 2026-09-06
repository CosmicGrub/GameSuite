package com.gamesuite.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("GameSuite", style = MaterialTheme.typography.headlineMedium)
            Row {
                TextButton(onClick = onNavigateToStats) {
                    Text("📊 My Stats", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = onNavigateToSettings) {
                    Text("⚙ Settings", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (!settings.hasSeenOnboarding) {
            OnboardingBanner(onDismiss = { settingsViewModel.setHasSeenOnboarding(true) })
            Spacer(Modifier.height(20.dp))
        }

        val recentlyPlayed = stats.values
            .filter { primaryLaunch.containsKey(it.gameId) }
            .sortedByDescending { it.lastPlayedEpochMillis }
            .take(3)
        if (recentlyPlayed.isNotEmpty()) {
            Text("Continue playing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                recentlyPlayed.forEach { s ->
                    ElevatedCard(onClick = { primaryLaunch[s.gameId]?.invoke() }) {
                        Column(modifier = Modifier.padding(14.dp).widthIn(min = 110.dp)) {
                            Text(s.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text(
                                "${s.wins}W – ${s.losses}L",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        GameSection(title = "Board games") {
            GameButton("Tic-Tac-Toe (vs CPU)") { primaryLaunch.getValue("tic-tac-toe").invoke() }
            GameButton("Tic-Tac-Toe (pass & play)") {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                    players = listOf(
                        PlayerInfo(playerId = "p1", displayName = "Player 1"),
                        PlayerInfo(playerId = "p2", displayName = "Player 2")
                    ),
                    localPlayerIndex = 0
                )
                onNavigateToGame("tic-tac-toe")
            }
            GameButton("Tic-Tac-Toe (Misère vs CPU)") {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(
                        PlayerInfo(playerId = "p1", displayName = "You"),
                        PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                    ),
                    localPlayerIndex = 0
                )
                onNavigateToGame("tic-tac-toe-misere")
            }
            GameButton("Tic-Tac-Toe (Wild vs CPU)") {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(
                        PlayerInfo(playerId = "p1", displayName = "You"),
                        PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                    ),
                    localPlayerIndex = 0
                )
                onNavigateToGame("tic-tac-toe-wild")
            }
            GameButton("Dominoes (vs CPU)") { primaryLaunch.getValue("dominoes").invoke() }
            GameButton("Mancala (vs CPU)") { primaryLaunch.getValue("mancala").invoke() }
        }

        Spacer(Modifier.height(16.dp))

        GameSection(title = "Cards") {
            GameButton("UNO (vs 2 CPU bots)") { primaryLaunch.getValue("uno").invoke() }
            GameButton("UNO (4-player pass & play)") {
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
            }
            GameButton("UNO (2v2 teams)") {
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
            }
            // No launchGame() call here — the house-rules picker screen decides the ruleset
            // AND the player roster itself, then launches the game from there.
            GameButton("UNO (house rules)") { onNavigateToGame("uno-house-rules") }
            // No launchGame() call here either — Nearby/Online's roster isn't known until
            // the lobby finishes connecting, unlike every other button on this screen.
            GameButton("UNO (nearby multiplayer)") { onNavigateToGame("nearby-entry") }
            GameButton("UNO (online)") { onNavigateToGame("online-entry") }
            GameButton("Solitaire") { primaryLaunch.getValue("solitaire").invoke() }
        }

        Spacer(Modifier.height(16.dp))

        GameSection(title = "Word games") {
            GameButton("Hangman") { primaryLaunch.getValue("hangman").invoke() }
            GameButton("Word Search") { primaryLaunch.getValue("word-search").invoke() }
            GameButton("Crossword") { primaryLaunch.getValue("crossword").invoke() }
            GameButton("Word Tiles (vs CPU)") { primaryLaunch.getValue("word-tiles").invoke() }
        }

        Spacer(Modifier.height(16.dp))

        GameSection(title = "Arcade & puzzles") {
            GameButton("Air Hockey (vs CPU)") { primaryLaunch.getValue("air-hockey").invoke() }
            GameButton("Air Hockey (pass & play)") {
                sessionManager.launchGame(
                    mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                    players = listOf(
                        PlayerInfo(playerId = "p1", displayName = "Player 1"),
                        PlayerInfo(playerId = "p2", displayName = "Player 2")
                    ),
                    localPlayerIndex = 0
                )
                onNavigateToGame("air-hockey")
            }
            GameButton("Sliding Puzzle") { primaryLaunch.getValue("sliding-puzzle").invoke() }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GameSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

@Composable
private fun GameButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
    Spacer(Modifier.height(8.dp))
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
            Text("Welcome to GameSuite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap any game below to jump straight in — each has its own controls (drag " +
                    "cards to play them, tap tiles to move them). Check Settings for CPU " +
                    "difficulty, card size, and accessibility options, and My Stats to track " +
                    "your record once you've played a few matches.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Got it") }
            }
        }
    }
}
