package com.gamesuite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.music.MusicManager
import com.gamesuite.settings.MusicSource
import com.gamesuite.ui.MusicSettingsScreen
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.window.core.layout.WindowWidthSizeClass
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.gameIdForRoute
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.PlayMode
import com.gamesuite.core.SavedGameStore
import com.gamesuite.transport.LobbySeatLimits
import com.gamesuite.transport.lobbyRouteFor
import com.gamesuite.uikit.LocalChangeSetup
import com.gamesuite.uikit.LocalProgressSaver
import com.gamesuite.uikit.ProgressSaver
import java.io.File
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.foldable.rememberFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.cards.LocalCardScale
import com.gamesuite.games.cards.rememberCardScaleMultiplier
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalHapticsEnabled
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.stats.StatsViewModel
import com.gamesuite.theme.AppTheme
import com.gamesuite.games.airhockey.AirHockeyRustGame
import com.gamesuite.games.breakout.BreakoutGame
import com.gamesuite.games.checkers.CheckersGame
import com.gamesuite.games.chess.ChessGame
import com.gamesuite.games.colorflood.ColorFloodGame
import com.gamesuite.games.connectfour.ConnectFourGame
import com.gamesuite.games.dominoes.DominoGame
import com.gamesuite.games.dotsandboxes.DotsAndBoxesGame
import com.gamesuite.games.edgematch.EdgeMatchGame
import com.gamesuite.games.hangman.HangmanGame
import com.gamesuite.games.kakuro.KakuroGame
import com.gamesuite.games.kenken.KenKenGame
import com.gamesuite.games.mastermind.MastermindGame
import com.gamesuite.games.wordguess.WordGuessGame
import com.gamesuite.games.lightsout.LightsOutGame
import com.gamesuite.games.mancala.MancalaGame
import com.gamesuite.games.minesweeper.MinesweeperGame
import com.gamesuite.games.nonogram.NonogramGame
import com.gamesuite.games.partytoolkit.PartyToolkitGame
import com.gamesuite.games.reversi.ReversiGame
import com.gamesuite.games.slidingpuzzle.SlidingPuzzleGame
import com.gamesuite.games.solitaire.SolitaireGame
import com.gamesuite.games.sudoku.SudokuGame
import com.gamesuite.games.tictactoe.TicTacToeGame
import com.gamesuite.games.towerdefence.TowerDefenceGame
import com.gamesuite.games.uno.UnoGame
import com.gamesuite.games.wordgames.crossword.CrosswordGame
import com.gamesuite.games.wordgames.tiles.TileGame
import com.gamesuite.games.wordgames.wordsearch.WordSearchGame
import com.gamesuite.ui.AirHockeyScreen
import com.gamesuite.ui.BreakoutScreen
import com.gamesuite.ui.CheckersScreen
import com.gamesuite.ui.ChessScreen
import com.gamesuite.ui.ColorFloodScreen
import com.gamesuite.ui.ConnectFourScreen
import com.gamesuite.ui.CrosswordScreen
import com.gamesuite.ui.DominoesScreen
import com.gamesuite.ui.DotsAndBoxesScreen
import com.gamesuite.ui.AppBottomBar
import com.gamesuite.ui.AppNavRail
import com.gamesuite.ui.AppTab
import com.gamesuite.ui.EdgeMatchScreen
import com.gamesuite.ui.GameHubScreen
import com.gamesuite.ui.GamesScreen
import com.gamesuite.ui.HangmanScreen
import com.gamesuite.ui.KakuroScreen
import com.gamesuite.ui.KenKenScreen
import com.gamesuite.ui.MastermindScreen
import com.gamesuite.ui.ReversiScreen
import com.gamesuite.ui.WordGuessScreen
import com.gamesuite.ui.LightsOutScreen
import com.gamesuite.ui.MainMenuScreen
import com.gamesuite.ui.MancalaScreen
import com.gamesuite.ui.MinesweeperScreen
import com.gamesuite.ui.NearbyEntryScreen
import com.gamesuite.ui.NearbyHostLobbyScreen
import com.gamesuite.ui.NearbyJoinLobbyScreen
import com.gamesuite.ui.NonogramScreen
import com.gamesuite.ui.OnlineEntryScreen
import com.gamesuite.ui.OnlineHostLobbyScreen
import com.gamesuite.ui.OnlineJoinLobbyScreen
import com.gamesuite.ui.PartyToolkitScreen
import com.gamesuite.ui.SettingsScreen
import com.gamesuite.ui.SlidingPuzzleScreen
import com.gamesuite.ui.SolitaireScreen
import com.gamesuite.ui.SudokuScreen
import com.gamesuite.ui.StatsScreen
import com.gamesuite.ui.TicTacToeScreen
import com.gamesuite.ui.TileGameScreen
import com.gamesuite.ui.TowerDefenceScreen
import com.gamesuite.ui.UnoHouseRulesScreen
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
    private val statsViewModel: StatsViewModel by viewModels()

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
        // Where resumable games are parked (see GameSessionManager.saveActiveProgress). The
        // ViewModel outlives the Activity, so this just re-points it at the same directory.
        sessionManager.savedGames = SavedGameStore(File(filesDir, "saved_games"))
        // The music player (library / other-apps modes) pauses while the whole app is in the
        // background and resumes when it returns. Activity-level start/stop is the right signal.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                MusicManager.get(applicationContext).onAppForegrounded()
            }

            override fun onStop(owner: LifecycleOwner) {
                MusicManager.get(applicationContext).onAppBackgrounded()
            }
        })
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

            // Persists every finished match into StatsRepository — the shell-level hand-off
            // that fixes the audited "every game computes a correct result, nothing keeps it"
            // finding. GameSessionManager already resolved which game and which local
            // win/loss/draw this was (see MatchOutcome's KDoc); this is the one place that
            // result gets written down instead of discarded with the GameContext.
            val lastMatchOutcome by sessionManager.lastMatchOutcome.collectAsStateWithLifecycle()
            LaunchedEffect(lastMatchOutcome) {
                lastMatchOutcome?.let {
                    statsViewModel.recordMatch(it)
                    // Consume it immediately: an Activity recreation with no new match (dark mode
                    // at sunset, a font-size change, ...) starts a fresh composition that would
                    // otherwise replay this LaunchedEffect against the same stale value and
                    // double-record the match. See acknowledgeMatchOutcome's KDoc.
                    sessionManager.acknowledgeMatchOutcome()
                }
            }

            AppTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                namedTheme = settings.namedTheme,
                textScale = settings.textScale
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    // "Change setup" on a result panel marks the next match end as "go back to this game's Hub"
                    // instead of all the way to the menu. The Hub is not always on the back stack (a daily
                    // started from Home's Today row, or a lobby launch that cleared back to the menu), so
                    // pop to it when it is there and otherwise open it explicitly.
                    var changeSetupRequested by remember { mutableStateOf(false) }
                    val finishMatch: () -> Unit = {
                        val wantsHub = changeSetupRequested
                        changeSetupRequested = false
                        val gameId = if (wantsHub) {
                            navController.currentBackStackEntry?.destination?.route
                                ?.let { gameIdForRoute(GameHubSpecs.all, it) }
                                ?.takeIf { id -> !GameHubSpecs.get(id).skipsHub }
                        } else null
                        if (gameId != null) {
                            if (!navController.popBackStack("hub/{gameId}", inclusive = false)) {
                                navController.popBackStack("menu", inclusive = false)
                                navController.navigate("hub/$gameId")
                            }
                        } else {
                            navController.popBackStack("menu", inclusive = false)
                        }
                    }
                    val cardScale = rememberCardScaleMultiplier(settings.cardSizePreference)
                    // Handoff-only state for the House Rules picker (uno-house-rules -> uno-custom),
                    // mirroring how "uno-teams" already passes a fixed custom UnoRules inline — kept
                    // here rather than on GameSessionManager so the shell's core package still never
                    // needs to know about a specific game's rule types (see GameModule's own KDoc).
                    var pendingUnoRules by remember { mutableStateOf<com.gamesuite.games.uno.UnoRules?>(null) }

                    // After Android reclaims the process, Compose restores the nav back stack to the
                    // game screen that was open, but the new process has no launch context for it.
                    // Send the player to the menu instead of a blank game; the game's Hub (opened
                    // from a card or the Continue row) offers Resume. (A normal recreation keeps its context and is left alone.)
                    LaunchedEffect(Unit) {
                        if (savedInstanceState != null && sessionManager.activeContext.value == null &&
                            AppTab.forRoute(navController.currentDestination?.route) == null
                        ) {
                            navController.popBackStack("menu", inclusive = false)
                        }
                    }
                    val progressSaver = remember {
                        ProgressSaver(
                            canSave = { sessionManager.canSaveActiveProgress() },
                            save = { sessionManager.saveActiveProgress() }
                        )
                    }

                    CompositionLocalProvider(
                        LocalProgressSaver provides progressSaver,
                        LocalChangeSetup provides { changeSetupRequested = true },
                        LocalFoldState provides foldState,
                        LocalCardScale provides cardScale,
                        LocalReducedMotion provides settings.reducedMotion,
                        LocalCard3DMode provides settings.card3DEnabled,
                        LocalHapticsEnabled provides settings.hapticsEnabled,
                        // The built-in procedural ambience only plays in AMBIENCE mode; with the
                        // user's library or another app's music it stays silent.
                        LocalMusicEnabled provides (settings.musicSource == MusicSource.AMBIENCE),
                        LocalColorblindMode provides settings.colorblindMode,
                        LocalEnhancedAnimations provides settings.enhancedAnimationsEnabled,
                        LocalGameSpeed provides settings.gameSpeed
                    ) {
                    // App shell: a bottom NavigationBar (a side NavigationRail at EXPANDED width) on
                    // the four top-level routes only. Game, Hub and lobby routes use the full screen.
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val currentTab = AppTab.forRoute(backStackEntry?.destination?.route)
                    // Tell the music player which screen is showing (raw route string) so it can
                    // pick a per-context playlist; fires on every route change.
                    val currentRoute: String? = backStackEntry?.destination?.route
                    LaunchedEffect(currentRoute) {
                        currentRoute?.let { MusicManager.get(applicationContext).setContextForRoute(it) }
                    }
                    val expanded = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass ==
                        WindowWidthSizeClass.EXPANDED
                    val selectTab: (AppTab) -> Unit = { tab ->
                        // popUpTo("menu") keeps Home at the bottom of the stack: Back from a tab
                        // returns Home, Back from Home exits.
                        navController.navigate(tab.route) {
                            popUpTo("menu")
                            launchSingleTop = true
                        }
                    }
                    Row(modifier = Modifier.fillMaxSize()) {
                    if (currentTab != null && expanded) {
                        AppNavRail(selected = currentTab, onSelect = selectTab)
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = "menu",
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable("menu") {
                            MainMenuScreen(
                                sessionManager = sessionManager,
                                settingsViewModel = settingsViewModel,
                                statsViewModel = statsViewModel,
                                onNavigate = { route -> navController.navigate(route) }
                            )
                        }
                        composable("games") {
                            GamesScreen(
                                sessionManager = sessionManager,
                                onNavigate = { route -> navController.navigate(route) }
                            )
                        }
                        composable(
                            "hub/{gameId}",
                            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
                        ) { entry ->
                            WithHubSpec(entry, onMissing = { navController.popBackStack() }) { spec ->
                                GameHubScreen(
                                    spec = spec,
                                    sessionManager = sessionManager,
                                    onStartLocal = { route -> navController.navigate(route) },
                                    onOpenLobby = { kind, gid, _ ->
                                        when (kind) {
                                            HubModeKind.NEARBY -> navController.navigate("nearby-entry/$gid")
                                            HubModeKind.ONLINE -> navController.navigate("online-entry/$gid")
                                            else -> Unit
                                        }
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onBack = { navController.popBackStack() },
                                onOpenMusic = { navController.navigate("music") }
                            )
                        }
                        composable("music") {
                            MusicSettingsScreen(
                                settingsViewModel = settingsViewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("stats") {
                            StatsScreen(
                                viewModel = statsViewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(
                            "nearby-entry/{gameId}",
                            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
                        ) { entry ->
                            WithHubSpec(entry, onMissing = { navController.popBackStack() }) { spec ->
                                NearbyEntryScreen(
                                    sessionManager = sessionManager,
                                    gameId = spec.gameId,
                                    gameDisplayName = spec.title,
                                    onNavigateToHostLobby = { gid -> navController.navigate("nearby-host-lobby/$gid") },
                                    onNavigateToJoinLobby = { gid -> navController.navigate("nearby-join-lobby/$gid") },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                        composable(
                            "nearby-host-lobby/{gameId}",
                            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
                        ) { entry ->
                            WithHubSpec(entry, onMissing = { navController.popBackStack("menu", inclusive = false) }) { spec ->
                                val seats = LobbySeatLimits.forGame(spec.gameId, HubModeKind.NEARBY)
                                NearbyHostLobbyScreen(
                                    sessionManager = sessionManager,
                                    gameRoute = lobbyRouteFor(spec.gameId),
                                    gameDisplayName = spec.title,
                                    onGameStarted = { route -> navController.navigate(route) { popUpTo("menu") } },
                                    onBack = { navController.popBackStack("menu", inclusive = false) },
                                    minPlayers = seats.minPlayers,
                                    maxPlayers = seats.maxPlayers
                                )
                            }
                        }
                        composable(
                            "nearby-join-lobby/{gameId}",
                            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
                        ) { entry ->
                            WithHubSpec(entry, onMissing = { navController.popBackStack("menu", inclusive = false) }) { spec ->
                                NearbyJoinLobbyScreen(
                                    sessionManager = sessionManager,
                                    onGameStarted = { route -> navController.navigate(route) { popUpTo("menu") } },
                                    onBack = { navController.popBackStack("menu", inclusive = false) },
                                    gameId = spec.gameId,
                                    gameDisplayName = spec.title
                                )
                            }
                        }
                        composable(
                            "online-entry/{gameId}",
                            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
                        ) { entry ->
                            WithHubSpec(entry, onMissing = { navController.popBackStack() }) { spec ->
                                OnlineEntryScreen(
                                    sessionManager = sessionManager,
                                    settingsViewModel = settingsViewModel,
                                    gameId = spec.gameId,
                                    gameDisplayName = spec.title,
                                    onNavigateToHostLobby = { gid -> navController.navigate("online-host-lobby/$gid") },
                                    onNavigateToJoinLobby = { gid -> navController.navigate("online-join-lobby/$gid") },
                                    onNavigateToSettings = { navController.navigate("settings") },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                        composable(
                            "online-host-lobby/{gameId}",
                            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
                        ) { entry ->
                            WithHubSpec(entry, onMissing = { navController.popBackStack("menu", inclusive = false) }) { spec ->
                                val seats = LobbySeatLimits.forGame(spec.gameId, HubModeKind.ONLINE)
                                OnlineHostLobbyScreen(
                                    sessionManager = sessionManager,
                                    gameRoute = lobbyRouteFor(spec.gameId),
                                    gameDisplayName = spec.title,
                                    onGameStarted = { route -> navController.navigate(route) { popUpTo("menu") } },
                                    onBack = { navController.popBackStack("menu", inclusive = false) },
                                    minPlayers = seats.minPlayers,
                                    maxPlayers = seats.maxPlayers
                                )
                            }
                        }
                        composable(
                            "online-join-lobby/{gameId}",
                            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
                        ) { entry ->
                            WithHubSpec(entry, onMissing = { navController.popBackStack("menu", inclusive = false) }) { spec ->
                                OnlineJoinLobbyScreen(
                                    sessionManager = sessionManager,
                                    onGameStarted = { route -> navController.navigate(route) { popUpTo("menu") } },
                                    onBack = { navController.popBackStack("menu", inclusive = false) },
                                    gameId = spec.gameId,
                                    gameDisplayName = spec.title
                                )
                            }
                        }
                        composable("tic-tac-toe") {
                            val ticTacToeGame = rememberActiveModule(sessionManager, "tic-tac-toe") { TicTacToeGame() }
                            TicTacToeScreen(
                                sessionManager = sessionManager,
                                game = ticTacToeGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("tic-tac-toe-misere") {
                            // Same GameModule/screen as "tic-tac-toe" — misere = true is the only
                            // difference (roadmap item 13c). See TicTacToeScreen's KDoc.
                            val ticTacToeGame = rememberActiveModule(sessionManager, "tic-tac-toe-misere") { TicTacToeGame() }
                            TicTacToeScreen(
                                sessionManager = sessionManager,
                                game = ticTacToeGame,
                                settingsViewModel = settingsViewModel,
                                misere = true,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("tic-tac-toe-wild") {
                            // Same GameModule/screen as "tic-tac-toe" — wild = true is the only
                            // difference. Each mover chooses X or O; the win-check reads the
                            // symbol occupying a cell rather than who placed it.
                            val ticTacToeGame = rememberActiveModule(sessionManager, "tic-tac-toe-wild") { TicTacToeGame() }
                            TicTacToeScreen(
                                sessionManager = sessionManager,
                                game = ticTacToeGame,
                                settingsViewModel = settingsViewModel,
                                wild = true,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("uno") {
                            val unoGame = rememberActiveModule(sessionManager, "uno") { UnoGame() }
                            UnoScreen(
                                sessionManager = sessionManager,
                                game = unoGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("uno-teams") {
                            val unoGame = rememberActiveModule(sessionManager, "uno-teams") { UnoGame() }
                            UnoScreen(
                                sessionManager = sessionManager,
                                game = unoGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                initialRules = com.gamesuite.games.uno.UnoRules(teamPlay = true)
                            )
                        }
                        composable("uno-house-rules") {
                            UnoHouseRulesScreen(
                                onStart = { rules, players ->
                                    pendingUnoRules = rules
                                    sessionManager.launchGame(
                                        mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                                        players = players,
                                        localPlayerIndex = 0
                                    )
                                    navController.navigate("uno-custom") { popUpTo("menu") }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("uno-custom") {
                            // Only ever reached right after uno-house-rules just set pendingUnoRules,
                            // so there's no staleness risk from a plain "Play UNO" button reusing an
                            // old value — those buttons navigate to "uno"/"uno-teams" instead, which
                            // never read this field.
                            val unoGame = rememberActiveModule(sessionManager, "uno-custom") { UnoGame() }
                            UnoScreen(
                                sessionManager = sessionManager,
                                game = unoGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                initialRules = pendingUnoRules ?: com.gamesuite.games.uno.UnoRules()
                            )
                        }
                        composable("hangman") {
                            val hangmanGame = rememberActiveModule(sessionManager, "hangman") { HangmanGame() }
                            HangmanScreen(
                                sessionManager = sessionManager,
                                game = hangmanGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("word-search") {
                            val wordSearchGame = rememberActiveModule(sessionManager, "word-search") { WordSearchGame() }
                            WordSearchScreen(
                                sessionManager = sessionManager,
                                game = wordSearchGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("word-search-daily") {
                            // Same GameModule/screen as "word-search" — a today's-date seed is
                            // the only difference, so every player gets the identical puzzle.
                            val wordSearchGame = rememberActiveModule(sessionManager, "word-search-daily") { WordSearchGame() }
                            WordSearchScreen(
                                sessionManager = sessionManager,
                                game = wordSearchGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                dailySeed = java.time.LocalDate.now().toEpochDay()
                            )
                        }
                        composable("crossword") {
                            val crosswordGame = rememberActiveModule(sessionManager, "crossword") { CrosswordGame() }
                            CrosswordScreen(
                                sessionManager = sessionManager,
                                game = crosswordGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("word-tiles") {
                            val tileGame = rememberActiveModule(sessionManager, "word-tiles") { TileGame() }
                            TileGameScreen(
                                sessionManager = sessionManager,
                                game = tileGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("dominoes") {
                            val dominoGame = rememberActiveModule(sessionManager, "dominoes") { DominoGame() }
                            DominoesScreen(
                                sessionManager = sessionManager,
                                game = dominoGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("dots-and-boxes") {
                            val dotsAndBoxesGame = rememberActiveModule(sessionManager, "dots-and-boxes") { DotsAndBoxesGame() }
                            DotsAndBoxesScreen(
                                sessionManager = sessionManager,
                                game = dotsAndBoxesGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("connect-four") {
                            val connectFourGame = rememberActiveModule(sessionManager, "connect-four") { ConnectFourGame() }
                            ConnectFourScreen(
                                sessionManager = sessionManager,
                                game = connectFourGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("checkers") {
                            val checkersGame = rememberActiveModule(sessionManager, "checkers") { CheckersGame() }
                            CheckersScreen(
                                sessionManager = sessionManager,
                                game = checkersGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("chess") {
                            val chessGame = rememberActiveModule(sessionManager, "chess") { ChessGame() }
                            ChessScreen(
                                sessionManager = sessionManager,
                                game = chessGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("reversi") {
                            val reversiGame = rememberActiveModule(sessionManager, "reversi") { ReversiGame() }
                            ReversiScreen(
                                sessionManager = sessionManager,
                                game = reversiGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("mancala") {
                            val mancalaGame = rememberActiveModule(sessionManager, "mancala") { MancalaGame() }
                            MancalaScreen(
                                sessionManager = sessionManager,
                                game = mancalaGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("air-hockey") {
                            // Rust-backed sim core pilot (rust/gamesuite-sim via UniFFI) — see
                            // AirHockeyRustGame's KDoc and the project's Rust-core migration
                            // report for why this route constructs it instead of the shared
                            // module's AirHockeyGame (still used, unchanged, by the desktop
                            // target — see shared/src/desktopMain/kotlin/Main.kt).
                            val airHockeyGame = rememberActiveModule(sessionManager, "air-hockey") { AirHockeyRustGame() }
                            AirHockeyScreen(
                                sessionManager = sessionManager,
                                game = airHockeyGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("solitaire") {
                            val solitaireGame = rememberActiveModule(sessionManager, "solitaire") { SolitaireGame() }
                            SolitaireScreen(
                                sessionManager = sessionManager,
                                game = solitaireGame,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("sliding-puzzle") {
                            val slidingPuzzleGame = rememberActiveModule(sessionManager, "sliding-puzzle") { SlidingPuzzleGame() }
                            SlidingPuzzleScreen(
                                sessionManager = sessionManager,
                                game = slidingPuzzleGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("sliding-puzzle-daily") {
                            // Same GameModule/screen as "sliding-puzzle" — a today's-date seed is
                            // the only difference, so every player gets the identical scramble.
                            val slidingPuzzleGame = rememberActiveModule(sessionManager, "sliding-puzzle-daily") { SlidingPuzzleGame() }
                            SlidingPuzzleScreen(
                                sessionManager = sessionManager,
                                game = slidingPuzzleGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                dailySeed = java.time.LocalDate.now().toEpochDay()
                            )
                        }
                        composable("minesweeper") {
                            val minesweeperGame = rememberActiveModule(sessionManager, "minesweeper") { MinesweeperGame() }
                            MinesweeperScreen(
                                sessionManager = sessionManager,
                                game = minesweeperGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("minesweeper-daily") {
                            // Same GameModule/screen as "minesweeper" — a today's-date seed is
                            // the only difference, so every player gets the identical board.
                            val minesweeperGame = rememberActiveModule(sessionManager, "minesweeper-daily") { MinesweeperGame() }
                            MinesweeperScreen(
                                sessionManager = sessionManager,
                                game = minesweeperGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                dailySeed = java.time.LocalDate.now().toEpochDay()
                            )
                        }
                        composable("sudoku") {
                            val sudokuGame = rememberActiveModule(sessionManager, "sudoku") { SudokuGame() }
                            SudokuScreen(
                                sessionManager = sessionManager,
                                game = sudokuGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("sudoku-daily") {
                            // Same GameModule/screen as "sudoku" — a today's-date seed is the
                            // only difference, so every player gets the identical puzzle.
                            val sudokuGame = rememberActiveModule(sessionManager, "sudoku-daily") { SudokuGame() }
                            SudokuScreen(
                                sessionManager = sessionManager,
                                game = sudokuGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                dailySeed = java.time.LocalDate.now().toEpochDay()
                            )
                        }
                        composable("lights-out") {
                            val lightsOutGame = rememberActiveModule(sessionManager, "lights-out") { LightsOutGame() }
                            LightsOutScreen(
                                sessionManager = sessionManager,
                                game = lightsOutGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("lights-out-daily") {
                            // Same GameModule/screen as "lights-out" — a today's-date seed is
                            // the only difference, so every player gets the identical board.
                            val lightsOutGame = rememberActiveModule(sessionManager, "lights-out-daily") { LightsOutGame() }
                            LightsOutScreen(
                                sessionManager = sessionManager,
                                game = lightsOutGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                dailySeed = java.time.LocalDate.now().toEpochDay()
                            )
                        }
                        composable("color-flood") {
                            val colorFloodGame = rememberActiveModule(sessionManager, "color-flood") { ColorFloodGame() }
                            ColorFloodScreen(
                                sessionManager = sessionManager,
                                game = colorFloodGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("color-flood-daily") {
                            // Same GameModule/screen as "color-flood" — a today's-date seed is
                            // the only difference, so every player gets the identical board.
                            val colorFloodGame = rememberActiveModule(sessionManager, "color-flood-daily") { ColorFloodGame() }
                            ColorFloodScreen(
                                sessionManager = sessionManager,
                                game = colorFloodGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                dailySeed = java.time.LocalDate.now().toEpochDay()
                            )
                        }
                        composable("edge-match") {
                            val edgeMatchGame = rememberActiveModule(sessionManager, "edge-match") { EdgeMatchGame() }
                            EdgeMatchScreen(
                                sessionManager = sessionManager,
                                game = edgeMatchGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("edge-match-daily") {
                            // Same GameModule/screen as "edge-match" — a today's-date seed is
                            // the only difference, so every player gets the identical puzzle.
                            val edgeMatchGame = rememberActiveModule(sessionManager, "edge-match-daily") { EdgeMatchGame() }
                            EdgeMatchScreen(
                                sessionManager = sessionManager,
                                game = edgeMatchGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                dailySeed = java.time.LocalDate.now().toEpochDay()
                            )
                        }
                        composable("nonogram") {
                            val nonogramGame = rememberActiveModule(sessionManager, "nonogram") { NonogramGame() }
                            NonogramScreen(
                                sessionManager = sessionManager,
                                game = nonogramGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("nonogram-daily") {
                            // Same GameModule/screen as "nonogram" — a today's-date seed is the
                            // only difference, so every player gets the identical puzzle.
                            val nonogramGame = rememberActiveModule(sessionManager, "nonogram-daily") { NonogramGame() }
                            NonogramScreen(
                                sessionManager = sessionManager,
                                game = nonogramGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                dailySeed = java.time.LocalDate.now().toEpochDay()
                            )
                        }
                        composable("party-toolkit") {
                            val partyToolkitGame = rememberActiveModule(sessionManager, "party-toolkit") { PartyToolkitGame() }
                            PartyToolkitScreen(
                                sessionManager = sessionManager,
                                game = partyToolkitGame,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("breakout") {
                            val breakoutGame = rememberActiveModule(sessionManager, "breakout") { BreakoutGame() }
                            BreakoutScreen(
                                sessionManager = sessionManager,
                                game = breakoutGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("kakuro") {
                            val kakuroGame = rememberActiveModule(sessionManager, "kakuro") { KakuroGame() }
                            KakuroScreen(
                                sessionManager = sessionManager,
                                game = kakuroGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("kakuro-daily") {
                            // Same GameModule/screen as "kakuro" -- a today's-date seed is the
                            // only difference, so every player gets the identical puzzle.
                            val kakuroGame = rememberActiveModule(sessionManager, "kakuro-daily") { KakuroGame() }
                            KakuroScreen(
                                sessionManager = sessionManager,
                                game = kakuroGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                dailySeed = java.time.LocalDate.now().toEpochDay()
                            )
                        }
                        composable("kenken") {
                            val kenkenGame = rememberActiveModule(sessionManager, "kenken") { KenKenGame() }
                            KenKenScreen(
                                sessionManager = sessionManager,
                                game = kenkenGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("kenken-daily") {
                            // Same GameModule/screen as "kenken" -- a today's-date seed is the
                            // only difference, so every player gets the identical puzzle.
                            val kenkenGame = rememberActiveModule(sessionManager, "kenken-daily") { KenKenGame() }
                            KenKenScreen(
                                sessionManager = sessionManager,
                                game = kenkenGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                dailySeed = java.time.LocalDate.now().toEpochDay()
                            )
                        }
                        composable("mastermind") {
                            val mastermindGame = rememberActiveModule(sessionManager, "mastermind") { MastermindGame() }
                            MastermindScreen(
                                sessionManager = sessionManager,
                                game = mastermindGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("mastermind-daily") {
                            // Same GameModule/screen as "mastermind" -- a today's-date seed is the
                            // only difference, so every player gets the identical secret.
                            val mastermindGame = rememberActiveModule(sessionManager, "mastermind-daily") { MastermindGame() }
                            MastermindScreen(
                                sessionManager = sessionManager,
                                game = mastermindGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                dailySeed = java.time.LocalDate.now().toEpochDay()
                            )
                        }
                        composable("word-guess") {
                            val wordGuessGame = rememberActiveModule(sessionManager, "word-guess") { WordGuessGame() }
                            WordGuessScreen(
                                sessionManager = sessionManager,
                                game = wordGuessGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                        composable("word-guess-daily") {
                            // Same GameModule/screen as "word-guess" -- a today's-date seed is the
                            // only difference, so every player gets the identical word.
                            val wordGuessGame = rememberActiveModule(sessionManager, "word-guess-daily") { WordGuessGame() }
                            WordGuessScreen(
                                sessionManager = sessionManager,
                                game = wordGuessGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch,
                                dailySeed = java.time.LocalDate.now().toEpochDay()
                            )
                        }
                        composable("tower-defence") {
                            val towerDefenceGame = rememberActiveModule(sessionManager, "tower-defence") { TowerDefenceGame() }
                            TowerDefenceScreen(
                                sessionManager = sessionManager,
                                game = towerDefenceGame,
                                settingsViewModel = settingsViewModel,
                                onMatchEnded = finishMatch
                            )
                        }
                    }
                    }
                    if (currentTab != null && !expanded) {
                        AppBottomBar(selected = currentTab, onSelect = selectTab)
                    }
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
 *
 * [route] is the nav route the screen was reached on. It keys the game's saved state (see
 * [GameSessionManager.saveActiveProgress]), so variants of one engine ("sudoku" vs "sudoku-daily",
 * the three Tic-Tac-Toe rule sets) each get their own save.
 */
@Composable
private fun <T : GameModule> rememberActiveModule(
    sessionManager: GameSessionManager,
    route: String,
    factory: () -> T
): T {
    val module = remember { factory() }
    DisposableEffect(module) {
        sessionManager.setActiveModule(module, route)
        onDispose { sessionManager.setActiveModule(null) }
    }
    return module
}

/**
 * Resolves the `gameId` argument of a Hub / lobby route to its [GameHubSpec] and shows [content];
 * an unknown id (a stale link) calls [onMissing] once instead of showing a blank screen.
 */
@Composable
private fun WithHubSpec(
    entry: NavBackStackEntry,
    onMissing: () -> Unit,
    content: @Composable (GameHubSpec) -> Unit
) {
    val spec = entry.arguments?.getString("gameId")?.let { GameHubSpecs.byId(it) }
    if (spec == null) {
        LaunchedEffect(Unit) { onMissing() }
    } else {
        content(spec)
    }
}
