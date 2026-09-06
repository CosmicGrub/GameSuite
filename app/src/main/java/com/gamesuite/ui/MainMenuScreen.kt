package com.gamesuite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo

/**
 * The game library screen. Each button launches a game through the shared
 * GameSessionManager and navigates to that game's route — no game-specific
 * logic lives here, only which players/mode to hand off.
 */
@Composable
fun MainMenuScreen(
    sessionManager: GameSessionManager,
    onNavigateToGame: (route: String) -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onNavigateToSettings) {
                Text("⚙ Settings", style = MaterialTheme.typography.labelLarge)
            }
        }

        Text("GameSuite")
        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = {
            sessionManager.launchGame(
                mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = listOf(
                    PlayerInfo(playerId = "p1", displayName = "Player 1"),
                    PlayerInfo(playerId = "p2", displayName = "Player 2")
                ),
                localPlayerIndex = 0
            )
            onNavigateToGame("tic-tac-toe")
        }) {
            Text("Play Tic-Tac-Toe (pass & play)")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            sessionManager.launchGame(
                mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = "p1", displayName = "You"),
                    PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                ),
                localPlayerIndex = 0
            )
            onNavigateToGame("tic-tac-toe")
        }) {
            Text("Play Tic-Tac-Toe (vs CPU)")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            sessionManager.launchGame(
                mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = "p1", displayName = "You"),
                    PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                ),
                localPlayerIndex = 0
            )
            onNavigateToGame("tic-tac-toe-misere")
        }) {
            Text("Play Tic-Tac-Toe (Misere vs CPU)")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = {
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
        }) {
            Text("Play UNO (vs 2 CPU bots)")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
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
        }) {
            Text("Play UNO (4-player pass & play)")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
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
        }) {
            Text("Play UNO (2v2 teams)")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // No launchGame() call here — Nearby's player roster isn't known until the lobby
        // finishes connecting, unlike every other button on this screen. See
        // NearbyEntryScreen/NearbyHostLobbyScreen/NearbyJoinLobbyScreen.
        Button(onClick = { onNavigateToGame("nearby-entry") }) {
            Text("Play UNO (nearby multiplayer)")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Same reasoning as the Nearby button above — the online lobby's roster
        // isn't known until Host/Join finishes, so no launchGame() call here either.
        Button(onClick = { onNavigateToGame("online-entry") }) {
            Text("Play UNO (online)")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = {
            sessionManager.launchGame(
                mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                localPlayerIndex = 0
            )
            onNavigateToGame("hangman")
        }) {
            Text("Play Hangman")
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Word games")
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            sessionManager.launchGame(
                mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                localPlayerIndex = 0
            )
            onNavigateToGame("word-search")
        }) {
            Text("Word Search")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            sessionManager.launchGame(
                mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                localPlayerIndex = 0
            )
            onNavigateToGame("crossword")
        }) {
            Text("Crossword")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            sessionManager.launchGame(
                mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = "p1", displayName = "You"),
                    PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                ),
                localPlayerIndex = 0
            )
            onNavigateToGame("word-tiles")
        }) {
            Text("Word Tiles (vs CPU)")
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("More board games")
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            sessionManager.launchGame(
                mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = "p1", displayName = "You"),
                    PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                ),
                localPlayerIndex = 0
            )
            onNavigateToGame("dominoes")
        }) {
            Text("Dominoes (vs CPU)")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            sessionManager.launchGame(
                mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = "p1", displayName = "You"),
                    PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                ),
                localPlayerIndex = 0
            )
            onNavigateToGame("mancala")
        }) {
            Text("Mancala (vs CPU)")
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Arcade")
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            sessionManager.launchGame(
                mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                localPlayerIndex = 0
            )
            onNavigateToGame("air-hockey")
        }) {
            Text("Air Hockey (vs CPU)")
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Card games")
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            sessionManager.launchGame(
                mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                localPlayerIndex = 0
            )
            onNavigateToGame("solitaire")
        }) {
            Text("Solitaire")
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Puzzles")
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            sessionManager.launchGame(
                mode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                localPlayerIndex = 0
            )
            onNavigateToGame("sliding-puzzle")
        }) {
            Text("Sliding Puzzle")
        }
    }
}
