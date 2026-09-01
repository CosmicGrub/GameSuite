package com.gamesuite.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.cards.CardVisual
import com.gamesuite.games.cards.FannedHand
import com.gamesuite.games.cards.LocalCardScale
import com.gamesuite.games.cards.PlayingCardView
import com.gamesuite.games.uno.UnoCard
import com.gamesuite.games.uno.UnoColor
import com.gamesuite.games.uno.UnoGame
import com.gamesuite.games.uno.UnoRules
import kotlinx.coroutines.delay

@Composable
fun UnoScreen(
    sessionManager: GameSessionManager,
    game: UnoGame,
    onMatchEnded: () -> Unit,
    initialRules: UnoRules = UnoRules()
) {
    val context by sessionManager.activeContext.collectAsState()
    val state by game.state
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.init(ctx)
        game.rules = initialRules
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
        }
        game.startMatch()
        sounds.playShuffle()
    }

    // Drive bot turns automatically with a short delay so moves are readable.
    LaunchedEffect(state?.currentPlayerIndex, state?.awaitingColorChoice, state?.awaitingChallenge, state?.roundOver) {
        val s = state ?: return@LaunchedEffect
        if (s.matchOver || s.roundOver) return@LaunchedEffect
        val current = s.players.getOrNull(s.currentPlayerIndex) ?: return@LaunchedEffect
        if (current.isBot || (s.awaitingChallenge && s.challengeVictimIndex?.let { s.players[it].isBot } == true)) {
            delay(700)
            game.playBotTurn()
        }
    }

    val s = state ?: return
    val activeContext = context ?: return

    if (s.matchOver) {
        UnoBackground { MatchOverContent(state = s, onDone = onMatchEnded) }
        return
    }

    if (s.roundOver) {
        UnoBackground { RoundOverContent(state = s, onNextRound = { game.startNextRound() }) }
        return
    }

    val myIndex = humanIndex(s, activeContext)
    val myTurn = s.currentPlayerIndex == myIndex && !s.awaitingColorChoice && !s.awaitingChallenge
    val myHand = s.players.getOrNull(myIndex)?.hand ?: emptyList()

    // The user's card-size preference (Settings → Card size), already mapped to
    // a device-aware multiplier — see CardScale.kt. FannedHand reads this same
    // CompositionLocal itself for the player's own hand; everywhere else this
    // screen draws a card directly (discard pile, opponent fans, the challenge
    // hand-reveal) applies it explicitly here, since PlayingCardView itself
    // deliberately does not scale on its own (see CardScale.kt's KDoc for why).
    val cardScale = LocalCardScale.current

    // Transient, presentation-only state for the two big dramatic beats the
    // reference gives special treatment — neither is real game state, both
    // are purely "what's playing on screen right now" on this one device.
    var challengeReveal by remember { mutableStateOf<ChallengeRevealSnapshot?>(null) }
    var showChallengeHand by remember { mutableStateOf(false) }
    LaunchedEffect(challengeReveal) {
        val snapshot = challengeReveal ?: return@LaunchedEffect
        showChallengeHand = false
        delay(650) // "CHALLENGE!" alone first, same beat as the reference
        showChallengeHand = true
        delay(1300) // then the accused hand lays face-up before resolving for real
        game.resolveChallenge(accept = false)
        challengeReveal = null
        showChallengeHand = false
    }

    var showUnoCallout by remember { mutableStateOf(false) }
    LaunchedEffect(showUnoCallout) {
        if (showUnoCallout) {
            delay(1100)
            showUnoCallout = false
        }
    }

    // On a book-posture foldable (Z Fold 5 unfolded), the table (opponents,
    // discard pile, draw/UNO buttons) renders on one side of the hinge and
    // your hand on the other — like sitting across a real card table with
    // your cards close to you. On any non-separating device (Tab S9, the
    // Fold's cover screen, a phone) this collapses to the original single
    // stacked layout automatically, no separate code path needed.
    UnoBackground {
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize().padding(16.dp),
        primary = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Opponents summary — horizontally scrollable since UNO supports up to 10
                // players and an unweighted, unscrolled Row would push later tiles off-screen
                // on narrow devices.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    // spacedBy's Alignment overload centers the whole group when it's
                    // narrower than the available width (typical case, few players) while
                    // still just spacing-then-scrolling once content actually overflows
                    // (many players) — plain spacedBy always left-packs regardless of
                    // leftover space, which looked stuck-to-the-edge on a wide tablet
                    // layout even after AdaptiveTwoPane centers the pane itself.
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                ) {
                    s.players.forEachIndexed { index, p ->
                        val isTurn = index == s.currentPlayerIndex
                        Column(
                            modifier = Modifier.widthIn(min = 72.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Seat-anchored turn tag rides with whichever player is acting,
                            // instead of a single fixed "your turn" label living in one
                            // corner regardless of who's actually up — reads correctly for
                            // pass-and-play/Nearby now that humanIndex() rotates per seat.
                            // Skip it for the local player's own seat here — the hand side
                            // below already carries the same tag next to their actual cards,
                            // and showing both at once just repeats the same line twice.
                            if (isTurn && index != myIndex) {
                                TurnTag(text = turnPrompt(s))
                                Spacer(Modifier.height(4.dp))
                            }
                            PlayerBadge(seatIndex = index)
                            Spacer(Modifier.height(4.dp))
                            Text(p.displayName, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal)
                            Spacer(Modifier.height(4.dp))
                            // At one card the fan collapses to a single large card instead of
                            // a counter — the hand's own shape is the "they're close" tell.
                            OpponentHandFan(count = p.hand.size, scale = cardScale)
                            // index != myIndex: never let the human catch themselves for a self-inflicted penalty.
                            if (index != myIndex && p.hand.size == 1 && !p.calledUno) {
                                TextButton(onClick = { game.catchUnoFailure(accuserIndex = myIndex, targetIndex = index) }) {
                                    Text("Catch!", color = Color.Red)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Discard pile / current color / status — the top card animates in on change.
                // Direction arrows flank the pile permanently (not a one-off reverse
                // animation) — an always-on read of s.direction, which previously had no
                // visual at all anywhere on this screen.
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // Capped to hug the pile the way the reference's pair does — full-width
                    // spacedBy pushed these out to the screen edges instead of flanking it.
                    DirectionArrows(
                        direction = s.direction,
                        modifier = Modifier.width(160.dp)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedContent(
                            targetState = s.topCard.instanceId,
                            transitionSpec = {
                                (scaleIn(animationSpec = tween(220), initialScale = 0.6f) + fadeIn(tween(220)))
                                    .togetherWith(scaleOut(animationSpec = tween(150), targetScale = 0.8f) + fadeOut(tween(150)))
                            },
                            label = "discardPile"
                        ) { _ ->
                            PlayingCardView(
                                card = unoCardToVisual(s.topCard, overrideColor = s.currentColor),
                                width = 72.dp * cardScale,
                                height = 104.dp * cardScale
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(s.lastAction, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        game.drawCard(myIndex)
                        sounds.playDraw()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }) {
                        Text(if (s.pendingDraw > 0) "Draw ${s.pendingDraw}" else "Draw")
                    }
                    Spacer(Modifier.width(12.dp))
                    if (myHand.size == 1) {
                        Button(onClick = {
                            game.callUno(myIndex)
                            showUnoCallout = true
                        }) { Text("UNO!") }
                    }
                }
            }
        },
        secondary = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (myTurn) {
                    TurnTag(text = "${turnPrompt(s)} — drag a card up to play it")
                } else {
                    Text("Your hand", style = MaterialTheme.typography.titleSmall)
                }

                Spacer(Modifier.height(8.dp))

                FannedHand(
                    items = myHand,
                    idOf = { it.instanceId },
                    visualOf = { unoCardToVisual(it) },
                    enabled = myTurn,
                    onPlay = { card ->
                        game.playCard(myIndex, card)
                        sounds.playPlace()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )

    if (s.awaitingColorChoice && s.currentPlayerIndex == myIndex) {
        ColorPickerDialog(onColorChosen = { game.chooseColor(it) })
    }

    // challengeReveal == null: this device isn't mid-animation, so the normal
    // Accept/Challenge decision dialog is still live. Once "Challenge!" is
    // tapped, onChallenge captures a snapshot instead of resolving immediately
    // — the LaunchedEffect above plays the CHALLENGE!/hand-reveal beat first,
    // then calls resolveChallenge() for real. Accept has no equivalent
    // fanfare in the reference, so it still resolves instantly.
    if (s.awaitingChallenge && s.challengeVictimIndex == myIndex && challengeReveal == null) {
        val playedByIndex = s.challengePlayedByIndex
        val playedBy = playedByIndex?.let { s.players.getOrNull(it) }
        ChallengeDialog(
            playedByName = playedBy?.displayName ?: "them",
            onAccept = { game.resolveChallenge(accept = true) },
            onChallenge = {
                if (playedBy != null) {
                    challengeReveal = ChallengeRevealSnapshot(playedBy.displayName, playedBy.hand)
                } else {
                    game.resolveChallenge(accept = false)
                }
            }
        )
    }

    challengeReveal?.let { snapshot ->
        ChallengeFlashOverlay(snapshot = snapshot, showHand = showChallengeHand, cardScale = cardScale)
    }

    if (showUnoCallout) {
        UnoCalloutOverlay()
    }
    }
}

private fun unoCardToVisual(card: UnoCard, overrideColor: UnoColor? = null): CardVisual = CardVisual(
    id = card.instanceId,
    label = card.displayLabel(),
    backgroundColor = colorFor(overrideColor ?: card.color)
)

@Composable
private fun ColorPickerDialog(onColorChosen: (UnoColor) -> Unit) {
    Dialog(onDismissRequest = {}) {
        UnoPanel {
            PanelTitle("Choose a color")
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(UnoColor.RED, UnoColor.YELLOW, UnoColor.GREEN, UnoColor.BLUE).forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colorFor(c))
                            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .clickable { onColorChosen(c) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChallengeDialog(playedByName: String, onAccept: () -> Unit, onChallenge: () -> Unit) {
    Dialog(onDismissRequest = {}) {
        UnoPanel {
            PanelTitle("Wild Draw Four")
            Spacer(Modifier.height(8.dp))
            Text("$playedByName played it on you.", color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                "Think they had a matching color card in hand? Challenge them.",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onAccept) { Text("Accept (draw 4)") }
                Button(
                    onClick = onChallenge,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) { Text("Challenge!") }
            }
        }
    }
}

@Composable
private fun RoundOverContent(state: com.gamesuite.games.uno.UnoState, onNextRound: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        UnoPanel {
            PanelTitle(winnerLabel(state, "wins round ${state.roundNumber}"))
            Spacer(Modifier.height(14.dp))
            Text("Scores (first to 500)", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            state.players.distinctBy { it.teamId.takeIf { t -> t >= 0 } ?: it.playerId }.forEach { p ->
                Text("${p.displayName}: ${state.cumulativeScores[p.playerId] ?: 0}", color = Color.White)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onNextRound) { Text("Next round") }
        }
    }
}

@Composable
private fun MatchOverContent(state: com.gamesuite.games.uno.UnoState, onDone: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        UnoPanel {
            PanelTitle(winnerLabel(state, "wins the match!"))
            Spacer(Modifier.height(10.dp))
            state.players.distinctBy { it.teamId.takeIf { t -> t >= 0 } ?: it.playerId }.forEach { p ->
                Text("${p.displayName}: ${state.cumulativeScores[p.playerId] ?: 0}", color = Color.White)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDone) { Text("Back to menu") }
        }
    }
}

/**
 * Every screen in the reference sits on a deep purple-to-black radial glow —
 * the single strongest tonal signature distinguishing it from GameSuite's flat
 * shared AppTheme surface. Scoped to this screen alone: must not touch the
 * four suit colors themselves, which stay theme-independent by GameSuite's own
 * established rule (game-critical colors never read MaterialTheme.colorScheme)
 * — this is set dressing behind the cards, not a recolor of them.
 */
@Composable
private fun UnoBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(colors = listOf(Color(0xFF3A2E6E), Color(0xFF120C24)))),
        content = content
    )
}

private val panelBackground = Color(0xFF1D1440)
private val panelBorder = Color(0xFF6C5CE7).copy(alpha = 0.55f)
private val panelGold = Color(0xFFFFC933)

/** The rounded, dark, violet-bordered panel every overlay in the reference
 *  uses (first seen on its in-game "Command Cards" rules screen) — applied
 *  here to every dialog/end screen so they read as one consistent game
 *  rather than a mix of this treatment and plain Material dialogs. */
@Composable
private fun UnoPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .background(panelBackground, RoundedCornerShape(16.dp))
            .border(1.5.dp, panelBorder, RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text,
        color = panelGold,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 20.sp,
        letterSpacing = 0.5.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

private data class ChallengeRevealSnapshot(val playedByName: String, val hand: List<UnoCard>)

/**
 * The most dramatic beat in the reference: a huge diagonal "CHALLENGE!" flash
 * across the table, then the challenged player's hand laid face-up as proof,
 * before the real outcome resolves. UnoGame.resolveChallenge() already computes
 * the correct legal/illegal result (traced this session) — this only delays
 * calling it long enough for the animation to play; see the LaunchedEffect that
 * drives [showHand] and the eventual resolveChallenge() call in UnoScreen.
 */
@Composable
private fun ChallengeFlashOverlay(snapshot: ChallengeRevealSnapshot, showHand: Boolean, cardScale: Float) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "CHALLENGE!",
                color = Color(0xFF4CE05A),
                fontWeight = FontWeight.Black,
                fontSize = 40.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.rotate(-8f)
            )
            AnimatedVisibility(visible = showHand) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(28.dp))
                    Text("${snapshot.playedByName}'s hand", color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.widthIn(max = 320.dp).horizontalScroll(rememberScrollState())
                    ) {
                        snapshot.hand.forEach { card ->
                            PlayingCardView(
                                card = unoCardToVisual(card),
                                width = 36.dp * cardScale,
                                height = 52.dp * cardScale
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Fired off the "UNO!" button — the reference flashes its own call-out text
 *  the instant a player calls, distinct from its squared display type
 *  elsewhere; approximated here with italic+bold since a real script
 *  typeface isn't part of this project's font setup. */
@Composable
private fun UnoCalloutOverlay() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "UNO!",
            color = panelGold,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            fontSize = 48.sp,
            modifier = Modifier.rotate(-6f)
        )
    }
}

private fun winnerLabel(s: com.gamesuite.games.uno.UnoState, suffix: String): String {
    val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
        ?: s.players.firstOrNull { it.teamId == s.winningTeamId }
    return if (winner != null) "${winner.displayName} $suffix" else "Round over"
}

/**
 * "Which seat is this device/screen currently representing" — mode-dependent, since
 * that means something different in each:
 *  - LOCAL_AD_HOC: each device IS one specific, fixed player for the whole match —
 *    context.localPlayerIndex (assigned once by the lobby) is exactly that.
 *  - SINGLE_DEVICE_PASS_AND_PLAY: there's no single "local player" at all — the one
 *    shared device is handed around, so "my seat" is whoever's turn it currently is.
 *    Found via on-device Nearby testing: this function used to always return the
 *    first non-bot player's index regardless of mode (a comment here literally said
 *    "the human player is always index 0"), which happens to be harmless for vs-bot
 *    (there's only one human seat, so "first non-bot" and "current human's turn"
 *    coincide) but is a real bug for multi-human pass-and-play — players 2/3/4 could
 *    never act, since turn-gating requires currentPlayerIndex == myIndex and myIndex
 *    was frozen at 0. Nothing had exercised a full 4-player/2v2 pass-and-play match
 *    to the point of a later player's turn before this.
 *  - SINGLE_PLAYER_VS_BOT (default): the one human seat — first non-bot player.
 */
private fun humanIndex(s: com.gamesuite.games.uno.UnoState, context: com.gamesuite.core.GameContext): Int =
    when (context.activeMode) {
        com.gamesuite.core.PlayMode.LOCAL_AD_HOC -> context.localPlayerIndex
        com.gamesuite.core.PlayMode.SINGLE_DEVICE_PASS_AND_PLAY -> s.currentPlayerIndex
        else -> s.players.indexOfFirst { !it.isBot }.let { if (it >= 0) it else 0 }
    }

private fun colorFor(color: UnoColor): Color = when (color) {
    UnoColor.RED -> Color(0xFFD32F2F)
    UnoColor.YELLOW -> Color(0xFFFBC02D)
    UnoColor.GREEN -> Color(0xFF388E3C)
    UnoColor.BLUE -> Color(0xFF1976D2)
    UnoColor.WILD -> Color(0xFF424242)
}

// ---- Reference-driven presentation (see docs — "Reshuffling UNO" design memo) ----
//
// Modeled after a 2006 Xbox 360 longplay that identifies every seat with a small
// colored icon badge instead of a portrait or Avatar — deliberately no per-player
// photo/character art here, matching that reference exactly.

/** Cycled by seat index, not tied to any player data — GameSuite doesn't (and
 *  shouldn't need to) model a "player color" just to give badges some variety. */
private val badgeGradients = listOf(
    listOf(Color(0xFFFFA726), Color(0xFFEF6C00)), // orange
    listOf(Color(0xFFAB47BC), Color(0xFF6A1B9A)), // purple
    listOf(Color(0xFF26A69A), Color(0xFF00695C)), // teal
    listOf(Color(0xFFEC407A), Color(0xFFAD1457)), // pink
    listOf(Color(0xFF42A5F5), Color(0xFF1565C0)), // blue
    listOf(Color(0xFFFFCA28), Color(0xFFF57F17)), // gold
)

/** The reference's player identity: a gradient tile with a small wild-pinwheel
 *  icon, cycled per seat — no avatar, no photo, matching the source exactly. */
@Composable
private fun PlayerBadge(seatIndex: Int, size: androidx.compose.ui.unit.Dp = 44.dp, modifier: Modifier = Modifier) {
    val gradient = badgeGradients[seatIndex % badgeGradients.size]
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(gradient))
            .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.6f)) {
            val wedgeColors = listOf(Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5))
            for (i in 0 until 4) {
                drawArc(color = wedgeColors[i], startAngle = -45f + i * 90f, sweepAngle = 90f, useCenter = true)
            }
        }
    }
}

/** Opponent hands as a fan of face-down cards rather than a "N cards" count —
 *  and, at exactly one card, the fan collapses to a single large card instead
 *  of a badge or counter. The hand's own silhouette is the tell, same as the
 *  reference (and it applies to opponents and the local player's own hand
 *  alike there — this covers the opponents side of that).
 *  [scale] is the same LocalCardScale multiplier every other card on this
 *  screen applies — passed explicitly (not read internally) so the caller's
 *  single `val cardScale = LocalCardScale.current` stays the one source of
 *  truth, matching the rule in CardScale.kt's KDoc. Applied to the fan's own
 *  spacing/offset math too, not just the cards themselves, so a scaled-up fan
 *  still overlaps proportionally instead of gapping or crowding. */
@Composable
private fun OpponentHandFan(count: Int, scale: Float, modifier: Modifier = Modifier) {
    if (count <= 0) return
    if (count == 1) {
        PlayingCardView(
            card = CardVisual(id = -1, label = "", backgroundColor = Color.Transparent, faceDown = true),
            modifier = modifier,
            width = 40.dp * scale,
            height = 58.dp * scale
        )
        return
    }
    val displayCount = count.coerceAtMost(8) // cap fan width past ~8 cards, same as the reference quietly does
    val cardStep = 9.dp * scale
    Box(
        modifier = modifier
            .height(50.dp * scale)
            .width(22.dp * scale + cardStep * displayCount),
        contentAlignment = Alignment.TopCenter
    ) {
        for (i in 0 until displayCount) {
            val offsetFromCenter = i - (displayCount - 1) / 2f
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = cardStep * offsetFromCenter)
                    .rotate(offsetFromCenter * 5f)
            ) {
                PlayingCardView(
                    card = CardVisual(id = i, label = "", backgroundColor = Color.Transparent, faceDown = true),
                    width = 24.dp * scale,
                    height = 36.dp * scale
                )
            }
        }
    }
}

/** One permanently-visible curved arrow. [mirror] flips the base shape (used to
 *  draw the right-side arrow as a mirror of the left-side one so the pair reads
 *  as a matched set); the caller flips the whole pair again for s.direction. */
@Composable
private fun DirectionArrow(mirror: Boolean, modifier: Modifier = Modifier) {
    val color = Color(0xFF9B8FEA).copy(alpha = 0.55f)
    Canvas(
        modifier = modifier
            .size(22.dp, 56.dp)
            .scale(scaleX = if (mirror) -1f else 1f, scaleY = 1f)
    ) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.7f, h * 0.92f)
            quadraticTo(w * 1.7f, h * 0.5f, w * 0.7f, h * 0.08f)
        }
        drawPath(path, color = color, style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round))
        val tipX = w * 0.7f
        val tipY = h * 0.08f
        val head = Path().apply {
            moveTo(tipX, tipY)
            lineTo(tipX - 7.dp.toPx(), tipY + 3.dp.toPx())
            lineTo(tipX + 3.dp.toPx(), tipY + 8.dp.toPx())
            close()
        }
        drawPath(head, color = color)
    }
}

/** Two arrows flanking the discard pile, always on screen — an ambient fixture
 *  per the reference, not a one-off reverse animation. Mirroring the whole Row
 *  when direction reverses keeps the pair self-consistent regardless of the
 *  exact curve geometry either arrow was drawn with. */
@Composable
private fun DirectionArrows(direction: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.scale(scaleX = if (direction < 0) -1f else 1f, scaleY = 1f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DirectionArrow(mirror = false)
        DirectionArrow(mirror = true)
    }
}

@Composable
private fun TurnTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .background(Color(0xFF43A047), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold
    )
}

private fun turnPrompt(s: com.gamesuite.games.uno.UnoState): String = when {
    s.awaitingChallenge -> "Accept or challenge"
    s.awaitingColorChoice -> "Choose a color"
    s.pendingDraw > 0 -> "Draw or stack"
    else -> "Play a card"
}
