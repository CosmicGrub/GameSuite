package com.gamesuite.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.dominoes.Domino
import com.gamesuite.games.dominoes.DominoGame
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Research pass (README item 9h) added a real CPU difficulty ladder — see
 * DominoGame's `chooseBotPlay`/`chooseOpeningPlay` KDoc — read here from
 * Settings' "Default CPU difficulty" the same way the other per-game
 * upgrade passes (9a, 9f, 9g) already do.
 */
@Composable
fun DominoesScreen(
    sessionManager: GameSessionManager,
    game: DominoGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = LocalHapticFeedback.current
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result -> sessionManager.endActiveGame(result) }
        game.startMatch()
    }

    LaunchedEffect(state?.currentPlayerIndex, state?.matchOver) {
        val s = state ?: return@LaunchedEffect
        if (s.matchOver) return@LaunchedEffect
        if (s.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            delay(800)
            game.playBotTurn()
        }
    }

    val s = state ?: return

    if (s.matchOver) {
        val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("${winner?.displayName ?: "Nobody"} wins!", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onMatchEnded) { Text("Back to menu") }
        }
        return
    }

    // Address controls at whichever player's turn it currently is, not a fixed
    // "first human" guess — this is what lets a second local human act in
    // SINGLE_DEVICE_PASS_AND_PLAY instead of only ever seeing disabled buttons.
    val activePlayerIndex = s.currentPlayerIndex
    val isMyTurn = s.players.getOrNull(activePlayerIndex)?.isBot == false
    val canPlayNow = isMyTurn && game.canPlay(activePlayerIndex)
    var selectedDomino by remember { mutableStateOf<Domino?>(null) }

    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize().padding(16.dp),
        primary = {
        // CenterHorizontally at the Column level (matching UnoScreen's per-section
        // treatment) so every row here — not just the opponents summary — centers
        // within the capped TABLET-mode column instead of hugging its start edge.
        // Rows that already declare their own fillMaxWidth() (the opponents Row,
        // the chain LazyRow) are unaffected, since a full-width child ignores the
        // parent's alignment.
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            // Centers the group when it fits (typical case), still spaces-then-scrolls
            // once it overflows — see the identical fix/comment in UnoScreen.kt.
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
        ) {
            s.players.forEachIndexed { i, p ->
                Text(
                    "${p.displayName}: ${p.hand.size}",
                    fontWeight = if (i == s.currentPlayerIndex) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
        Text(s.lastAction, style = MaterialTheme.typography.bodySmall)
        Text("Boneyard: ${s.boneyardSize}", style = MaterialTheme.typography.labelSmall)
        Text(
            "CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
            style = MaterialTheme.typography.labelSmall
        )

        Spacer(Modifier.height(12.dp))
        Text("Chain", style = MaterialTheme.typography.titleSmall)

        LazyRow(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            items(s.chain, key = { it.domino.instanceId }) { placed ->
                androidx.compose.animation.AnimatedVisibility(
                    visible = true,
                    enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.4f)
                ) {
                    DominoTileView(top = placed.leftValue, bottom = placed.rightValue, horizontal = true)
                }
            }
        }

        if (s.chain.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Left end: ${s.leftEnd}", style = MaterialTheme.typography.labelMedium)
                Text("Right end: ${s.rightEnd}", style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedDomino != null && s.chain.isNotEmpty()) {
                Button(enabled = isMyTurn, onClick = {
                    selectedDomino?.let {
                        game.playDomino(activePlayerIndex, it, attachToLeft = true)
                        sounds.playTap(); haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    selectedDomino = null
                }) { Text("Play on Left") }
                Button(enabled = isMyTurn, onClick = {
                    selectedDomino?.let {
                        game.playDomino(activePlayerIndex, it, attachToLeft = false)
                        sounds.playTap(); haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    selectedDomino = null
                }) { Text("Play on Right") }
            }
            OutlinedButton(
                enabled = isMyTurn && !canPlayNow && s.boneyardSize > 0,
                onClick = { game.drawFromBoneyard(activePlayerIndex); sounds.playDraw() }
            ) { Text("Draw") }
            OutlinedButton(
                enabled = isMyTurn && !canPlayNow && s.boneyardSize == 0,
                onClick = { game.pass(activePlayerIndex) }
            ) { Text("Pass") }
        }
        }
        },
        secondary = {
        Column(modifier = Modifier.fillMaxWidth()) {
        Text("Your hand — tap to select, then Play Left/Right", style = MaterialTheme.typography.titleSmall)

        LazyRow(modifier = Modifier.padding(top = 8.dp)) {
            val myHand = s.players.getOrNull(activePlayerIndex)?.hand ?: emptyList()
            items(myHand, key = { it.instanceId }) { domino ->
                val selected = selectedDomino?.instanceId == domino.instanceId
                val playable = s.chain.isEmpty() || domino.a == s.leftEnd || domino.b == s.leftEnd ||
                    domino.a == s.rightEnd || domino.b == s.rightEnd
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .border(if (selected) 2.dp else 0.dp, Color(0xFF388E3C), RoundedCornerShape(6.dp))
                        .clickable(enabled = isMyTurn && playable) {
                            selectedDomino = if (selected) null else domino
                            if (s.chain.isEmpty()) {
                                game.playDomino(activePlayerIndex, domino, attachToLeft = true)
                                sounds.playTap(); haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedDomino = null
                            }
                        }
                ) {
                    DominoTileView(top = domino.a, bottom = domino.b, horizontal = false, dimmed = !playable)
                }
            }
        }
        }
        }
    )
}

@Composable
private fun DominoTileView(top: Int, bottom: Int, horizontal: Boolean, dimmed: Boolean = false) {
    val bg = if (dimmed) Color(0xFFE0E0E0) else Color(0xFFFFF8E1)
    val content: @Composable () -> Unit = {
        Text(top.toString(), fontWeight = FontWeight.Bold)
        Box(Modifier.background(Color.Black).size(if (horizontal) 20.dp else 28.dp, 1.dp))
        Text(bottom.toString(), fontWeight = FontWeight.Bold)
    }
    Box(
        modifier = Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, Color.Black.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .size(width = if (horizontal) 44.dp else 32.dp, height = if (horizontal) 32.dp else 60.dp)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        if (horizontal) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) { content() }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) { content() }
        }
    }
}
