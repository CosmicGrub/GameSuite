package com.gamesuite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card as Material3Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.cards.CardVisual
import com.gamesuite.games.cards.LocalCardScale
import com.gamesuite.games.cards.PlayingCardView
import com.gamesuite.games.cards.Suit
import com.gamesuite.games.solitaire.SelectionSource
import com.gamesuite.games.solitaire.SolitaireGame
import com.gamesuite.games.solitaire.TableauColumn

/**
 * Renders SolitaireGame's state reactively — same split as every other game
 * here (TicTacToeGame, MancalaGame, ...): all rules live in the GameModule,
 * this composable only draws piles/columns and forwards taps to them.
 *
 * No settingsViewModel/CPU-difficulty plumbing (unlike HangmanScreen or
 * TicTacToeScreen) — a solved-vs-not solitaire deal has no honest
 * difficulty lever (see SolitaireGame's KDoc), and card sizing already
 * comes for free from the app-wide [LocalCardScale] CompositionLocal
 * (UnoScreen reads the exact same value the same way).
 */
@Composable
fun SolitaireScreen(
    sessionManager: GameSessionManager,
    game: SolitaireGame,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val state by game.state
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val cardScale = LocalCardScale.current

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch()
        sounds.playShuffle()
    }

    val s = state ?: return
    val gamesWon = game.gamesWon.value
    val cardWidth = 64.dp * cardScale
    val cardHeight = 92.dp * cardScale

    // Primary-only (no secondary/hand content in this game) — same treatment
    // as TicTacToeScreen/HangmanScreen: caps + centers on a Tab S9 / unfolded
    // Fold instead of the table sitting stretched across the full width.
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize().padding(16.dp),
        primary = {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Games won: $gamesWon", style = MaterialTheme.typography.labelLarge)
                            Text("Score: ${s.score}", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(
                            s.lastAction,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Stock, waste, then the 4 foundations pushed to the far end.
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        StockPileView(
                            hasCards = s.stock.isNotEmpty(),
                            width = cardWidth,
                            height = cardHeight,
                            onClick = {
                                game.tapStock()
                                sounds.playDraw()
                            }
                        )
                        WastePileView(
                            card = s.waste.lastOrNull(),
                            isSelected = s.selected == SelectionSource.Waste,
                            width = cardWidth,
                            height = cardHeight,
                            onClick = {
                                game.tapWaste()
                                sounds.playTap()
                            }
                        )
                        Spacer(Modifier.width(16.dp))
                        Suit.entries.forEach { suit ->
                            FoundationPileView(
                                suit = suit,
                                cards = s.foundations[suit] ?: emptyList(),
                                width = cardWidth,
                                height = cardHeight,
                                onClick = {
                                    game.tapFoundation(suit)
                                    sounds.playTap()
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // 7 tableau columns, cascaded.
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        s.tableau.forEachIndexed { index, column ->
                            TableauColumnView(
                                column = column,
                                isSelected = s.selected == SelectionSource.Tableau(index),
                                width = cardWidth,
                                height = cardHeight,
                                onClick = {
                                    game.tapTableau(index)
                                    sounds.playTap()
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = game::leaveSession) { Text("Back to Menu") }
                }

                if (s.won) {
                    SolvedPanel(onNewGame = game::playAgain, onBackToMenu = game::leaveSession)
                }
            }
        }
    )
}

@Composable
private fun StockPileView(hasCards: Boolean, width: Dp, height: Dp, onClick: () -> Unit) {
    Box(modifier = Modifier.clickable(onClick = onClick)) {
        if (hasCards) {
            PlayingCardView(
                card = CardVisual(id = -1, label = "", backgroundColor = Color.White, faceDown = true),
                width = width,
                height = height
            )
        } else {
            EmptyPileSlot(width = width, height = height, symbol = "↺")
        }
    }
}

@Composable
private fun WastePileView(card: Card?, isSelected: Boolean, width: Dp, height: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .then(if (isSelected) Modifier.border(3.dp, Color(0xFFFFC107), RoundedCornerShape(10.dp)) else Modifier)
    ) {
        if (card != null) {
            PlayingCardView(card = card.toVisual(), width = width, height = height)
        } else {
            EmptyPileSlot(width = width, height = height, symbol = "")
        }
    }
}

@Composable
private fun FoundationPileView(suit: Suit, cards: List<Card>, width: Dp, height: Dp, onClick: () -> Unit) {
    val top = cards.lastOrNull()
    Box(modifier = Modifier.clickable(onClick = onClick)) {
        if (top != null) {
            PlayingCardView(card = top.toVisual(), width = width, height = height)
        } else {
            EmptyPileSlot(
                width = width,
                height = height,
                symbol = suit.symbol,
                symbolColor = if (suit.isRed) Color(0xFFD32F2F) else Color(0xFF212121)
            )
        }
    }
}

/** A column's own single tap target, cascading every card (hidden or shown) with a vertical offset — a Compose "solitaire fan" down a column instead of FannedHand's horizontal one. */
@Composable
private fun TableauColumnView(column: TableauColumn, isSelected: Boolean, width: Dp, height: Dp, onClick: () -> Unit) {
    val overlap = height * 0.28f
    val cards: List<Pair<Card, Boolean>> = column.faceDown.map { it to true } + column.faceUp.map { it to false }
    val totalHeight = if (cards.isEmpty()) height else height + overlap * (cards.size - 1)

    Box(
        modifier = Modifier
            .width(width)
            .height(totalHeight)
            .clickable(onClick = onClick)
    ) {
        if (cards.isEmpty()) {
            EmptyPileSlot(width = width, height = height, symbol = "")
        } else {
            cards.forEachIndexed { i, (card, faceDown) ->
                val isTopCard = i == cards.lastIndex
                Box(modifier = Modifier.offset(y = overlap * i)) {
                    PlayingCardView(
                        card = card.toVisual(faceDown = faceDown),
                        width = width,
                        height = height,
                        modifier = if (isTopCard && isSelected) {
                            Modifier.border(3.dp, Color(0xFFFFC107), RoundedCornerShape(10.dp))
                        } else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPileSlot(width: Dp, height: Dp, symbol: String, symbolColor: Color = Color.White.copy(alpha = 0.7f)) {
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (symbol.isNotEmpty()) {
            Text(symbol, color = symbolColor, fontSize = (width.value * 0.35f).sp)
        }
    }
}

@Composable
private fun SolvedPanel(onNewGame: () -> Unit, onBackToMenu: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Material3Card(modifier = Modifier.padding(16.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Solved!", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onNewGame) { Text("New Game") }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onBackToMenu) { Text("Back to Menu") }
            }
        }
    }
}
