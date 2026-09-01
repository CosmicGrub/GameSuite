package com.gamesuite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.foldable.rememberFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.cards.LocalCardScale
import com.gamesuite.games.cards.rememberCardScaleMultiplier
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.theme.AppTheme
import com.gamesuite.games.airhockey.AirHockeyGame
import com.gamesuite.games.dominoes.DominoGame
import com.gamesuite.games.hangman.HangmanGame
import com.gamesuite.games.mancala.MancalaGame
import com.gamesuite.games.uno.UnoGame
import com.gamesuite.games.wordgames.crossword.CrosswordGame
import com.gamesuite.games.wordgames.tiles.TileGame
import com.gamesuite.games.wordgames.wordsearch.WordSearchGame
import com.gamesuite.ui.AirHockeyScreen
import com.gamesuite.ui.CrosswordScreen
import com.gamesuite.ui.DominoesScreen
import com.gamesuite.ui.HangmanScreen
import com.gamesuite.ui.MainMenuScreen
import com.gamesuite.ui.MancalaScreen
import com.gamesuite.ui.NearbyEntryScreen
import com.gamesuite.ui.NearbyHostLobbyScreen
import com.gamesuite.ui.NearbyJoinLobbyScreen
import com.gamesuite.ui.SettingsScreen
import com.gamesuite.ui.TicTacToeScreen
import com.gamesuite.ui.TileGameScreen
import com.gamesuite.ui.UnoScreen
import com.gamesuite.ui.WordSearchScreen

/**
 * Single-activity host. Navigation between the shell (main menu) and any
 * game screen happens entirely in Compose via NavHost — no new Activities
 * per game. GameSessionManager is shared across the whole nav graph so any
 * screen can launch/end a game.
 */
class MainActivity : ComponentActivity() {

    private val sessionManager: GameSessionManager by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    // GameModule's contract (init/startMatch/pause/resume/endMatch) says the
    // shell "only calls these lifecycle methods" — pause()/resume() need to
    // actually be wired up for that to be true. Forward the Activity's own
    // pause/resume here to whichever GameModule instance the active game
    // screen registered via rememberActiveModule below, so a game doing
    // per-frame work (Air Hockey's tick(), a bot-turn delay, ...) gets a
    // chance to stop while the app is backgrounded (an incoming call,
    // switching apps, a fold posture-change recreate) and pick back up.
    override fun onPause() {
        super.onPause()
        sessionManager.pauseActiveGame()
    }

    override fun onResume() {
        super.onResume()
        sessionManager.resumeActiveGame()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val foldState = rememberFoldState(this)
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

            // CardSounds is a plain singleton with no Compose/settings awareness of its
            // own (see its KDoc) — keep its static mute flag in sync with the live
            // setting here, once, at the root, rather than threading soundEnabled
            // through every game screen individually.
            LaunchedEffect(settings.soundEnabled) {
                CardSounds.soundEnabled = settings.soundEnabled
            }

            AppTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                namedTheme = settings.namedTheme
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val cardScale = rememberCardScaleMultiplier(settings.cardSizePreference)

                    CompositionLocalProvider(LocalFoldState provides foldState, LocalCardScale provides cardScale) {
                    NavHost(navController = navController, startDestination = "menu") {
                        composable("menu") {
                            MainMenuScreen(
                                sessionManager = sessionManager,
                                onNavigateToGame = { route -> navController.navigate(route) },
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("nearby-entry") {
                            NearbyEntryScreen(
                                sessionManager = sessionManager,
                                onNavigateToHostLobby = { navController.navigate("nearby-host-lobby") },
                                onNavigateToJoinLobby = { navController.navigate("nearby-join-lobby") },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("nearby-host-lobby") {
                            // Hardcoded to UNO for this pass — the lobby itself is generic
                            // (see NearbyHostLobbyScreen's KDoc), just not yet exposed for
                            // any other LOCAL_AD_HOC-capable game.
                            NearbyHostLobbyScreen(
                                sessionManager = sessionManager,
                                gameRoute = "uno",
                                gameDisplayName = "UNO",
                                onGameStarted = { route -> navController.navigate(route) { popUpTo("menu") } },
                                onBack = { navController.popBackStack("menu", inclusive = false) }
                            )
                        }
                        composable("nearby-join-lobby") {
                            NearbyJoinLobbyScreen(
                                sessionManager = sessionManager,
                                onGameStarted = { route -> navController.navigate(route) { popUpTo("menu") } },
                                onBack = { navController.popBackStack("menu", inclusive = false) }
                            )
                        }
                        composable("tic-tac-toe") {
                            TicTacToeScreen(
                                sessionManager = sessionManager,
                                onMatchEnded = { navController.popBackStack("menu", inclusive = false) }
                            )
                        }
                        composable("uno") {
                            val unoGame = rememberActiveModule(sessionManager) { UnoGame() }
                            UnoScreen(
                                sessionManager = sessionManager,
                                game = unoGame,
                                onMatchEnded = { navController.popBackStack("menu", inclusive = false) }
                            )
                        }
                        composable("uno-teams") {
                            val unoGame = rememberActiveModule(sessionManager) { UnoGame() }
                            UnoScreen(
                                sessionManager = sessionManager,
                                game = unoGame,
                                onMatchEnded = { navController.popBackStack("menu", inclusive = false) },
                                initialRules = com.gamesuite.games.uno.UnoRules(teamPlay = true)
                            )
                        }
                        composable("hangman") {
                            val hangmanGame = rememberActiveModule(sessionManager) { HangmanGame() }
                            HangmanScreen(
                                sessionManager = sessionManager,
                                game = hangmanGame,
                                onMatchEnded = { navController.popBackStack("menu", inclusive = false) }
                            )
                        }
                        composable("word-search") {
                            val wordSearchGame = rememberActiveModule(sessionManager) { WordSearchGame() }
                            WordSearchScreen(
                                sessionManager = sessionManager,
                                game = wordSearchGame,
                                onMatchEnded = { navController.popBackStack("menu", inclusive = false) }
                            )
                        }
                        composable("crossword") {
                            val crosswordGame = rememberActiveModule(sessionManager) { CrosswordGame() }
                            CrosswordScreen(
                                sessionManager = sessionManager,
                                game = crosswordGame,
                                onMatchEnded = { navController.popBackStack("menu", inclusive = false) }
                            )
                        }
                        composable("word-tiles") {
                            val tileGame = rememberActiveModule(sessionManager) { TileGame() }
                            TileGameScreen(
                                sessionManager = sessionManager,
                                game = tileGame,
                                onMatchEnded = { navController.popBackStack("menu", inclusive = false) }
                            )
                        }
                        composable("dominoes") {
                            val dominoGame = rememberActiveModule(sessionManager) { DominoGame() }
                            DominoesScreen(
                                sessionManager = sessionManager,
                                game = dominoGame,
                                onMatchEnded = { navController.popBackStack("menu", inclusive = false) }
                            )
                        }
                        composable("mancala") {
                            val mancalaGame = rememberActiveModule(sessionManager) { MancalaGame() }
                            MancalaScreen(
                                sessionManager = sessionManager,
                                game = mancalaGame,
                                onMatchEnded = { navController.popBackStack("menu", inclusive = false) }
                            )
                        }
                        composable("air-hockey") {
                            val airHockeyGame = rememberActiveModule(sessionManager) { AirHockeyGame() }
                            AirHockeyScreen(
                                sessionManager = sessionManager,
                                game = airHockeyGame,
                                onMatchEnded = { navController.popBackStack("menu", inclusive = false) }
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

/**
 * Creates and remembers a game's [GameModule] instance, and registers it as
 * the shell's active module for the lifetime of the composition that calls
 * this — so [MainActivity]'s onPause/onResume have something to forward to
 * (see [GameSessionManager.pauseActiveGame]/[GameSessionManager.resumeActiveGame]).
 * Clears the active module on dispose (navigating away from the game).
 */
@Composable
private fun <T : GameModule> rememberActiveModule(
    sessionManager: GameSessionManager,
    factory: () -> T
): T {
    val module = remember { factory() }
    DisposableEffect(module) {
        sessionManager.setActiveModule(module)
        onDispose { sessionManager.setActiveModule(null) }
    }
    return module
}
