package com.gamesuite.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.hangman.HangmanGame
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.specularSweep
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Premium 2026 vision pass: this screen's identity is now "chalk on a
 * chalkboard" — a dark, grained board background (see [chalkboardBackground])
 * with every stroke of the hangman figure drawn in as an actual pen-stroke
 * reveal (see [GallowsBoard]), which is now the PRIMARY tension signal for a
 * wrong guess, not an addition alongside the pre-existing shake/flash (kept
 * below, now secondary). Letter keys are color-coded by outcome (a real gap
 * — they used to look identical whether right or wrong), a correct guess
 * arcs a small glyph from the pressed key to the slot(s) it fills, the guess
 * counter gets tension typography (amber at 2 left, pulsing red at 1), and a
 * win spawns a burst of real Euler-integrated (gravity+drag) tumbling letter
 * glyphs. The final wrong guess gets a ~50ms hit-stop freeze right as its
 * stroke finishes drawing, then a heavier, layered FAILURE-family haptic
 * distinct from an ordinary wrong guess's single buzz.
 *
 * Sound is layered from the shared [CardSounds] clips (see its own KDoc) —
 * this screen can't jitter their pitch per-play since [CardSounds]'s public
 * API only exposes fixed-rate calls (its private `play()` rate parameter
 * isn't exposed publicly, and extending that is out of this bundle's file
 * scope); combining different existing clips with small staggered delays
 * still avoids every event sounding like one flat identical clip.
 */
@Composable
fun HangmanScreen(
    sessionManager: GameSessionManager,
    game: HangmanGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    val haptics = rememberHaptics()
    val sounds = remember { CardSounds.get(androidContext) }
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.HANGMAN, enabled = musicEnabled)
    val reducedMotion = LocalReducedMotion.current
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion
    val sheenEnabled = LocalCard3DMode.current && !reducedMotion
    val scope = rememberCoroutineScope()

    val chalkText = Color(0xFFF5F5F0)

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.loadDictionary(androidContext)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch()
    }

    val s = state ?: return
    val wins = game.wins.value
    val losses = game.losses.value

    // Wrong-guess feedback: a brief horizontal shake plus a red flash behind the
    // word display — kept as a secondary flourish; the gallows stroke reveal
    // below is now the primary tension signal.
    val shakeOffset = remember { Animatable(0f) }
    var flashActive by remember { mutableStateOf(false) }
    val flashColor by animateColorAsState(
        targetValue = if (flashActive) Color(0xFFEF5350).copy(alpha = 0.55f) else Color.Transparent,
        animationSpec = if (reducedMotion) snap() else tween(220),
        label = "hangmanWrongFlash"
    )

    // Whether the loss result panel is allowed to show yet — held back until the
    // final gallows stroke finishes drawing and its hit-stop beat has played, so
    // the drawing itself is what lands the loss, not an instant banner popping up
    // over an unfinished figure. A win has no such gate — it reveals immediately.
    var lossRevealReady by remember(s.word) { mutableStateOf(false) }

    LaunchedEffect(s.wrongGuesses) {
        if (s.wrongGuesses == 0) return@LaunchedEffect
        flashActive = true
        if (!reducedMotion) {
            shakeOffset.snapTo(0f)
            shakeOffset.animateTo(-10f, tween(45))
            shakeOffset.animateTo(10f, tween(65))
            shakeOffset.animateTo(-7f, tween(65))
            shakeOffset.animateTo(7f, tween(55))
            shakeOffset.animateTo(0f, tween(45))
        }
        val isFinalGuess = s.matchOver && !s.won
        if (!isFinalGuess) {
            haptics(HapticSignal.FAILURE)
            sounds.playDraw()
        }
        delay(180)
        flashActive = false
    }

    // Correct-guess pipeline: the key's onClick sets this the instant a NEW
    // correct letter is pressed (computed synchronously against the pre-guess
    // state, so it never fires twice for the same press) — this effect then
    // spawns the flying-glyph arc(s) and lands the confirmation haptic/sound
    // once they arrive, roughly synced with the slot's own reveal animation.
    var pendingCorrectLetter by remember(s.word) { mutableStateOf<Char?>(null) }
    val keyPositions = remember { mutableStateMapOf<Char, Offset>() }
    val slotPositions = remember(s.word) { mutableStateMapOf<Int, Offset>() }
    val arcs = remember(s.word) { mutableStateListOf<LetterArc>() }

    LaunchedEffect(pendingCorrectLetter) {
        val letter = pendingCorrectLetter ?: return@LaunchedEffect
        val keyPos = keyPositions[letter]
        val targets = s.word.indices.filter { s.word[it] == letter }
        if (keyPos != null && targets.isNotEmpty() && !reducedMotion) {
            targets.forEach { idx ->
                val slotPos = slotPositions[idx] ?: return@forEach
                val arc = LetterArc(letter, keyPos, slotPos, Animatable(0f))
                arcs += arc
                launch {
                    // A rapid second correct guess restarts this whole effect (it's keyed on
                    // pendingCorrectLetter), cancelling this coroutine mid-flight — finally
                    // ensures the arc still gets removed instead of staying stuck on screen.
                    try {
                        arc.progress.animateTo(1f, tween(260))
                    } finally {
                        arcs -= arc
                    }
                }
            }
            delay(220)
        }
        haptics(HapticSignal.NORMAL_ACTION)
        sounds.playPlace()
        pendingCorrectLetter = null
    }

    // Win celebration: real Euler-integrated (gravity+drag) tumbling glyph burst.
    var confettiActive by remember(s.word) { mutableStateOf(false) }
    val confetti = remember(s.word) { mutableStateListOf<ConfettiGlyph>() }
    var confettiTick by remember { mutableStateOf(0) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(s.matchOver, s.won) {
        if (s.matchOver && s.won) {
            haptics(HapticSignal.CELEBRATION)
            if (enhanced) {
                sounds.playShuffle()
                delay(60L + Random.nextInt(40))
                sounds.playTap()
                delay(60L + Random.nextInt(40))
                sounds.playDraw()
                confettiActive = true
            }
        }
    }

    LaunchedEffect(confettiActive) {
        if (!confettiActive) return@LaunchedEffect
        val w = overlaySize.width.toFloat().coerceAtLeast(1f)
        val h = overlaySize.height.toFloat().coerceAtLeast(1f)
        val originX = w / 2f
        val originY = h * 0.25f
        val pool = (s.word + "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        confetti.clear()
        repeat(20 + Random.nextInt(11)) {
            confetti += ConfettiGlyph(
                x = originX + Random.nextFloat() * 50f - 25f,
                y = originY,
                vx = (Random.nextFloat() * 2f - 1f) * w * 0.55f,
                vy = -(Random.nextFloat() * 0.5f + 0.45f) * h * 1.0f,
                angle = Random.nextFloat() * 360f,
                angularVelocity = (Random.nextFloat() * 2f - 1f) * 260f,
                alpha = 1f,
                char = pool.random(),
                color = CONFETTI_COLORS.random()
            )
        }
        val gravity = h * 1.7f
        val drag = 0.985f
        val durationNanos = 1_700_000_000L
        val startNanos = withFrameNanos { it }
        var lastNanos = startNanos
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            lastNanos = now
            val elapsed = now - startNanos
            confetti.forEach { p ->
                p.vy += gravity * dt
                p.vx *= drag
                p.x += p.vx * dt
                p.y += p.vy * dt
                p.angle += p.angularVelocity * dt
                if (elapsed > durationNanos * 0.6) {
                    p.alpha = (1f - (elapsed - durationNanos * 0.6f) / (durationNanos * 0.4f)).coerceIn(0f, 1f)
                }
            }
            confettiTick++
            if (elapsed > durationNanos) break
        }
        confettiActive = false
        confetti.clear()
    }

    var overlayRootOffset by remember { mutableStateOf(Offset.Zero) }
    val glyphPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    Box(modifier = Modifier.fillMaxSize().chalkboardBackground()) {
        // Primary-only (no secondary/hand content in this game) — same treatment
        // as TicTacToeScreen: caps + centers on a Tab S9 / unfolded Fold instead
        // of sitting stretched across the full width.
        AdaptiveTwoPane(
            foldState = LocalFoldState.current,
            modifier = Modifier.fillMaxSize().padding(24.dp),
            primary = {
                // Landscape reflow: a cover-screen window rotated to landscape
                // (~344dp tall) cannot fit the score header + a fixed-size gallows +
                // guess counter + word row + on-screen keyboard all stacked in one
                // column without clipping (the header/gallows/word-row alone already
                // exceed that budget with the old hardcoded 200dp gallows). Reflow the
                // chrome BESIDE the gallows in a Row instead of shrinking anything
                // below a usable size — portrait keeps the original single-column
                // stack, which already has plenty of height. The aspect check is done
                // here, from this BoxWithConstraints' own REAL measured bounds, so it
                // is correct no matter which AdaptiveLayoutMode branch placed this
                // primary slot (FOLD_SPLIT/COVER/DEFAULT/TABLET all resolve to some
                // concrete bounded width x height by the time this runs).
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isLandscape = maxWidth > maxHeight
                    val difficultyLabel = settings.defaultCpuDifficulty.name
                        .lowercase().replaceFirstChar { it.uppercase() }
                    val showResultPanel = s.matchOver && (s.won || lossRevealReady)

                    // Gallows sizing: fit BOTH the real available width AND height of
                    // whatever slot it's given (the whole column in portrait, the left
                    // half in landscape) — never a size hardcoded independent of the
                    // actual window, or a tight landscape budget clips it (and, since
                    // it used to eat a fixed 200dp regardless of budget, everything
                    // stacked below it too).
                    val gallowsHeightBudget = if (isLandscape) maxHeight * 0.62f else 200.dp
                    val gallowsWidthBudget = if (isLandscape) maxWidth * 0.42f else maxWidth * 0.55f
                    val gallowsScale = minOf(
                        gallowsHeightBudget.coerceAtMost(200.dp) / 200.dp,
                        gallowsWidthBudget.coerceAtMost(170.dp) / 170.dp
                    ).coerceIn(0.5f, 1f)
                    val gallowsModifier = Modifier
                        .size(170.dp * gallowsScale, 200.dp * gallowsScale)
                        .specularSweep(enabled = sheenEnabled, tint = Color.White.copy(alpha = 0.10f))

                    val onGallowsStageDrawn: (Int) -> Unit = { stage ->
                        if (stage == s.maxWrongGuesses) {
                            scope.launch {
                                delay(50) // hit-stop: a brief freeze right as the last stroke lands
                                lossRevealReady = true
                                sounds.playDraw()
                                haptics(HapticSignal.FAILURE)
                                delay(120)
                                haptics(HapticSignal.FAILURE) // heavier, layered — distinct from one ordinary wrong-guess buzz
                                sounds.playPlace()
                            }
                        }
                    }
                    val onGuess: (Char) -> Unit = { letter ->
                        // Immediate press acknowledgment — the correctness pipeline above
                        // (arc flight + playPlace, or the wrongGuesses effect + playDraw)
                        // only lands its own sound a beat later (up to ~220ms for a correct
                        // arc), so without this a key press was previously silent at the
                        // instant of the tap itself.
                        playSfx(SfxKind.LIGHT_TICK)
                        val wasCorrect = letter !in s.guessedLetters && letter in s.word
                        if (wasCorrect) pendingCorrectLetter = letter
                        game.guessLetter(letter)
                    }

                    if (isLandscape) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                HangmanHeaderText(wins, losses, difficultyLabel, chalkText)
                                Spacer(Modifier.height(6.dp))
                                GallowsBoard(
                                    wrongGuesses = s.wrongGuesses,
                                    maxWrongGuesses = s.maxWrongGuesses,
                                    roundKey = s.word,
                                    reducedMotion = reducedMotion,
                                    lineColor = chalkText,
                                    onStageDrawn = onGallowsStageDrawn,
                                    modifier = gallowsModifier
                                )
                                Spacer(Modifier.height(8.dp))
                                TensionGuessCounter(remaining = s.remainingGuesses, reducedMotion = reducedMotion)
                            }

                            Column(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Spacer(Modifier.height(4.dp))
                                HangmanWordRow(
                                    word = s.word,
                                    guessedLetters = s.guessedLetters,
                                    revealedWord = s.revealedWord,
                                    shakeOffsetX = shakeOffset.value,
                                    flashColor = flashColor,
                                    reducedMotion = reducedMotion,
                                    chalkText = chalkText,
                                    onSlotPositioned = { index, pos -> slotPositions[index] = pos }
                                )
                                Spacer(Modifier.height(8.dp))
                                if (showResultPanel) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f, fill = false)
                                            .verticalScroll(rememberScrollState()),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        HangmanResultPanel(
                                            won = s.won,
                                            word = s.word,
                                            reducedMotion = reducedMotion,
                                            onNewWord = game::playAgain,
                                            onBackToMenu = game::leaveSession
                                        )
                                    }
                                } else if (!s.matchOver) {
                                    // weight(1f, fill = false) gives this LazyVerticalGrid a
                                    // REAL bounded max height (its fair share of this column,
                                    // never more) — it fills that height and scrolls
                                    // internally to reach every key rather than ever being
                                    // measured against an unbounded/oversized height and
                                    // clipping keys off-screen.
                                    HangmanKeyboard(
                                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                                        guessedLetters = s.guessedLetters,
                                        word = s.word,
                                        chalkText = chalkText,
                                        onKeyPositioned = { letter, pos -> keyPositions[letter] = pos },
                                        onGuess = onGuess
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            HangmanHeaderText(wins, losses, difficultyLabel, chalkText)
                            Spacer(Modifier.height(8.dp))
                            GallowsBoard(
                                wrongGuesses = s.wrongGuesses,
                                maxWrongGuesses = s.maxWrongGuesses,
                                roundKey = s.word,
                                reducedMotion = reducedMotion,
                                lineColor = chalkText,
                                onStageDrawn = onGallowsStageDrawn,
                                modifier = gallowsModifier
                            )
                            Spacer(Modifier.height(12.dp))
                            TensionGuessCounter(remaining = s.remainingGuesses, reducedMotion = reducedMotion)
                            Spacer(Modifier.height(16.dp))
                            HangmanWordRow(
                                word = s.word,
                                guessedLetters = s.guessedLetters,
                                revealedWord = s.revealedWord,
                                shakeOffsetX = shakeOffset.value,
                                flashColor = flashColor,
                                reducedMotion = reducedMotion,
                                chalkText = chalkText,
                                onSlotPositioned = { index, pos -> slotPositions[index] = pos }
                            )
                            Spacer(Modifier.height(24.dp))
                            if (showResultPanel) {
                                HangmanResultPanel(
                                    won = s.won,
                                    word = s.word,
                                    reducedMotion = reducedMotion,
                                    onNewWord = game::playAgain,
                                    onBackToMenu = game::leaveSession
                                )
                            } else if (!s.matchOver) {
                                // Same bounded-height safety net as the landscape branch
                                // above — harmless here since portrait normally has room
                                // to spare, but guards the same way if it ever doesn't
                                // (a small phone, a resized freeform window, ...).
                                HangmanKeyboard(
                                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                                    guessedLetters = s.guessedLetters,
                                    word = s.word,
                                    chalkText = chalkText,
                                    onKeyPositioned = { letter, pos -> keyPositions[letter] = pos },
                                    onGuess = onGuess
                                )
                            }
                        }
                    }
                }
            }
        )

        // Flying-glyph arcs + win-confetti overlay — a sibling of AdaptiveTwoPane
        // under the same Box, so subtracting this overlay's own root position from
        // any descendant's positionInRoot() (captured above) gives the correct
        // local coordinate for drawing here, regardless of AdaptiveTwoPane's own
        // internal centering/padding.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    overlayRootOffset = it.positionInRoot()
                    overlaySize = it.size
                }
        ) {
            val canvas = drawContext.canvas
            arcs.forEach { arc ->
                val t = arc.progress.value
                val start = arc.start - overlayRootOffset
                val end = arc.end - overlayRootOffset
                val control = Offset((start.x + end.x) / 2f, minOf(start.y, end.y) - 70f)
                val pos = quadBezier(start, control, end, t)
                val alpha = if (t < 0.85f) 1f else (1f - (t - 0.85f) / 0.15f)
                glyphPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
                glyphPaint.textSize = 22.sp.toPx()
                glyphPaint.color = Color(0xFFFFF59D).toArgb()
                canvas.nativeCanvas.drawText(arc.letter.toString(), pos.x, pos.y, glyphPaint)
            }
            if (confettiTick >= 0 && confetti.isNotEmpty()) {
                confetti.forEach { p ->
                    glyphPaint.alpha = (p.alpha.coerceIn(0f, 1f) * 255).toInt()
                    glyphPaint.textSize = 24.sp.toPx()
                    glyphPaint.color = p.color.toArgb()
                    canvas.save()
                    canvas.translate(p.x, p.y)
                    canvas.rotate(p.angle)
                    canvas.nativeCanvas.drawText(p.char.toString(), 0f, 0f, glyphPaint)
                    canvas.restore()
                }
            }
        }
    }
}

@Composable
private fun HangmanHeaderText(wins: Int, losses: Int, difficultyLabel: String, chalkText: Color) {
    Text(
        "Wins: $wins · Losses: $losses",
        style = MaterialTheme.typography.labelLarge,
        color = chalkText
    )
    Text(
        "Word difficulty: $difficultyLabel",
        style = MaterialTheme.typography.labelSmall,
        color = chalkText.copy(alpha = 0.75f)
    )
}

/**
 * The word-in-progress display, with per-letter reveal animation and the
 * shake/flash wrong-guess feedback — same visual/behavioral contract
 * regardless of which layout branch (portrait stack or landscape
 * side-by-side) places it. [onSlotPositioned] reports each slot's CURRENT
 * root-relative center every time layout runs (via onGloballyPositioned,
 * which re-fires on every real layout pass), so the flying-glyph arc drawn
 * by the overlay Canvas in [HangmanScreen] always targets the real on-screen
 * position for whatever orientation is active, never a value captured once.
 */
@Composable
private fun HangmanWordRow(
    word: String,
    guessedLetters: Set<Char>,
    revealedWord: String,
    shakeOffsetX: Float,
    flashColor: Color,
    reducedMotion: Boolean,
    chalkText: Color,
    onSlotPositioned: (index: Int, center: Offset) -> Unit
) {
    Row(
        modifier = Modifier
            .offset(x = shakeOffsetX.dp)
            .background(flashColor, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "Word: $revealedWord" },
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        word.forEachIndexed { index, letter ->
            key(word, index) {
                val revealed = letter in guessedLetters
                Box(
                    modifier = Modifier.onGloballyPositioned { coords ->
                        onSlotPositioned(
                            index,
                            coords.positionInRoot() + Offset(coords.size.width / 2f, coords.size.height / 2f)
                        )
                    }
                ) {
                    AnimatedContent(
                        targetState = revealed,
                        transitionSpec = {
                            if (reducedMotion) {
                                fadeIn(snap()) togetherWith fadeOut(snap())
                            } else {
                                (fadeIn(tween(200)) + scaleIn(initialScale = 0.5f, animationSpec = tween(200))) togetherWith
                                    fadeOut(tween(80))
                            }
                        },
                        label = "hangmanLetterReveal"
                    ) { isRevealed ->
                        Text(
                            if (isRevealed) letter.toString() else "_",
                            style = MaterialTheme.typography.headlineMedium,
                            color = chalkText
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HangmanResultPanel(
    won: Boolean,
    word: String,
    reducedMotion: Boolean,
    onNewWord: () -> Unit,
    onBackToMenu: () -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = if (reducedMotion) {
            fadeIn(snap())
        } else {
            fadeIn(tween(260)) + scaleIn(initialScale = 0.6f, animationSpec = tween(260))
        }
    ) {
        Text(
            if (won) "You got it!" else "Out of guesses — the word was $word",
            style = MaterialTheme.typography.titleLarge,
            color = if (won) Color(0xFF81C784) else Color(0xFFEF9A9A)
        )
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = onNewWord, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("New Word") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onBackToMenu, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("Back to Menu") }
}

/**
 * The on-screen A-Z keyboard. [modifier] is supplied by the caller with a
 * bounded height (`weight(1f, fill = false)` inside whichever Column is
 * hosting it) so this LazyVerticalGrid always measures against a REAL
 * bounded max height in every orientation — including the tightest
 * cover-screen landscape budget — and scrolls internally to reach every key
 * rather than ever clipping the grid or pushing keys off-screen. Each key
 * gets an explicit 48dp touch-target floor regardless of the adaptive grid's
 * computed cell size, matching the floor pattern in CardScale.kt.
 */
@Composable
private fun HangmanKeyboard(
    modifier: Modifier,
    guessedLetters: Set<Char>,
    word: String,
    chalkText: Color,
    onKeyPositioned: (Char, Offset) -> Unit,
    onGuess: (Char) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 56.dp),
        modifier = modifier
    ) {
        items(('A'..'Z').toList()) { letter ->
            val used = letter in guessedLetters
            val correct = used && letter in word
            // A single letter is not enough for a screen reader to
            // announce meaningfully on its own — this states the
            // letter plus its guessed/correct/incorrect status.
            val status = when {
                !used -> "not guessed"
                correct -> "correct"
                else -> "incorrect"
            }
            val bg = when {
                !used -> Color(0xFF33503F)
                correct -> Color(0xFF4CAF50)
                else -> Color(0xFF8D6E63)
            }
            val fg = if (!used) chalkText else Color.White
            Button(
                onClick = { onGuess(letter) },
                enabled = !used,
                colors = ButtonDefaults.buttonColors(
                    containerColor = bg,
                    contentColor = fg,
                    disabledContainerColor = bg,
                    disabledContentColor = fg
                ),
                modifier = Modifier
                    .padding(2.dp)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .onGloballyPositioned { coords ->
                        onKeyPositioned(
                            letter,
                            coords.positionInRoot() + Offset(coords.size.width / 2f, coords.size.height / 2f)
                        )
                    }
                    .semantics { contentDescription = "Letter $letter, $status" },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Text(letter.toString())
            }
        }
    }
}

@Composable
private fun TensionGuessCounter(remaining: Int, reducedMotion: Boolean) {
    val pulsing = remaining <= 1 && !reducedMotion
    val pulseAlpha = if (pulsing) {
        val infinite = rememberInfiniteTransition(label = "hangmanTensionPulse")
        val alpha by infinite.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(420), repeatMode = RepeatMode.Reverse),
            label = "hangmanTensionAlpha"
        )
        alpha
    } else 1f
    val color = when {
        remaining <= 1 -> Color(0xFFFF5252)
        remaining == 2 -> Color(0xFFFFB300)
        else -> Color(0xFFF5F5F0)
    }
    Text(
        "Guesses left: $remaining",
        color = color.copy(alpha = pulseAlpha),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (remaining <= 2) FontWeight.Bold else FontWeight.Normal
    )
}

/** One flying letter glyph animating from a keyboard key to the word slot(s) it fills. */
private class LetterArc(
    val letter: Char,
    val start: Offset,
    val end: Offset,
    val progress: Animatable<Float, AnimationVector1D>
)

/** One win-celebration particle — plain mutable fields, stepped imperatively by a real Euler-integration loop (see the confetti LaunchedEffect above) rather than driven by Compose animation specs. */
private class ConfettiGlyph(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var angle: Float,
    var angularVelocity: Float,
    var alpha: Float,
    val char: Char,
    val color: Color
)

private val CONFETTI_COLORS = listOf(
    Color(0xFFFFF176), Color(0xFF81D4FA), Color(0xFFFFAB91), Color(0xFFA5D6A7), Color(0xFFCE93D8)
)

private fun quadBezier(p0: Offset, p1: Offset, p2: Offset, t: Float): Offset {
    val u = 1f - t
    val x = u * u * p0.x + 2f * u * t * p1.x + t * t * p2.x
    val y = u * u * p0.y + 2f * u * t * p1.y + t * t * p2.y
    return Offset(x, y)
}

private fun lerpOffset(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/**
 * Static gallows-frame + figure-part geometry, laid out fractionally against
 * whatever [w]x[h] the [GallowsBoard] Canvas actually measures at, so the
 * illustration stays proportional at any size.
 */
private class GallowsGeom(w: Float, h: Float) {
    val baseStart = Offset(0.06f * w, 0.94f * h)
    val baseEnd = Offset(0.58f * w, 0.94f * h)
    val poleBottom = Offset(0.16f * w, 0.94f * h)
    val poleTop = Offset(0.16f * w, 0.08f * h)
    val beamEnd = Offset(0.56f * w, 0.08f * h)
    val ropeEnd = Offset(0.56f * w, 0.22f * h)
    val headRadius = 0.085f * w
    val headCenter = Offset(0.56f * w, 0.22f * h + headRadius)
    val neckBottom = Offset(0.56f * w, 0.22f * h + headRadius * 2)
    val hip = Offset(0.56f * w, 0.58f * h)
    val leftShoulder = Offset(0.56f * w, 0.32f * h)
    val rightShoulder = leftShoulder
    val leftHand = Offset(0.42f * w, 0.46f * h)
    val rightHand = Offset(0.70f * w, 0.46f * h)
    val leftFoot = Offset(0.44f * w, 0.72f * h)
    val rightFoot = Offset(0.68f * w, 0.72f * h)
}

/**
 * Draws the gallows frame (always fully visible) plus the hangman figure,
 * revealed one real pen-stroke per wrong guess (head / torso / left arm /
 * right arm / left leg / right leg — exactly [maxWrongGuesses] stages).
 * Each newly-added stage animates in over ~280ms: a straight-line stage
 * interpolates its endpoint from start to end (equivalent to a progressive
 * stroke reveal for a line), and the head stage sweeps [drawArc] from 0 to
 * 360 degrees — both achieve the same "drawn in as you watch" effect as
 * tracing a PathMeasure segment would, without needing that extra API
 * surface for geometry this simple (plain lines and one circle).
 *
 * [onStageDrawn] fires once, the moment a given stage's draw-in animation
 * completes — the caller uses this to time the final wrong guess's hit-stop
 * + heavier loss haptic right as the last stroke lands.
 */
@Composable
private fun GallowsBoard(
    wrongGuesses: Int,
    maxWrongGuesses: Int,
    roundKey: Any,
    reducedMotion: Boolean,
    lineColor: Color,
    onStageDrawn: (stage: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val stageAnims = remember(roundKey) {
        (1..maxWrongGuesses).associateWith { Animatable(0f) }
    }
    val firedStages = remember(roundKey) { mutableStateListOf<Int>() }

    LaunchedEffect(roundKey, wrongGuesses) {
        if (wrongGuesses <= 0) return@LaunchedEffect
        val anim = stageAnims[wrongGuesses] ?: return@LaunchedEffect
        if (reducedMotion) {
            anim.snapTo(1f)
        } else {
            anim.animateTo(1f, tween(280))
        }
        if (wrongGuesses !in firedStages) {
            firedStages += wrongGuesses
            onStageDrawn(wrongGuesses)
        }
    }

    Canvas(modifier = modifier) {
        val geom = GallowsGeom(size.width, size.height)
        val strokeWidth = size.width * 0.032f

        drawLine(color = lineColor, start = geom.baseStart, end = geom.baseEnd, strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color = lineColor, start = geom.poleBottom, end = geom.poleTop, strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color = lineColor, start = geom.poleTop, end = geom.beamEnd, strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color = lineColor, start = geom.beamEnd, end = geom.ropeEnd, strokeWidth = strokeWidth * 0.55f, cap = StrokeCap.Round)

        fun stageProgress(stage: Int): Float = when {
            stage < wrongGuesses -> 1f
            stage == wrongGuesses -> stageAnims[stage]?.value ?: 1f
            else -> 0f
        }

        val pHead = stageProgress(1)
        if (pHead > 0f) {
            drawArc(
                color = lineColor,
                startAngle = -90f,
                sweepAngle = 360f * pHead,
                useCenter = false,
                topLeft = Offset(geom.headCenter.x - geom.headRadius, geom.headCenter.y - geom.headRadius),
                size = Size(geom.headRadius * 2, geom.headRadius * 2),
                style = Stroke(width = strokeWidth * 0.75f, cap = StrokeCap.Round)
            )
        }
        val pTorso = stageProgress(2)
        if (pTorso > 0f) {
            drawLine(color = lineColor, start = geom.neckBottom, end = lerpOffset(geom.neckBottom, geom.hip, pTorso), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        }
        val pLeftArm = stageProgress(3)
        if (pLeftArm > 0f) {
            drawLine(color = lineColor, start = geom.leftShoulder, end = lerpOffset(geom.leftShoulder, geom.leftHand, pLeftArm), strokeWidth = strokeWidth * 0.8f, cap = StrokeCap.Round)
        }
        val pRightArm = stageProgress(4)
        if (pRightArm > 0f) {
            drawLine(color = lineColor, start = geom.rightShoulder, end = lerpOffset(geom.rightShoulder, geom.rightHand, pRightArm), strokeWidth = strokeWidth * 0.8f, cap = StrokeCap.Round)
        }
        val pLeftLeg = stageProgress(5)
        if (pLeftLeg > 0f) {
            drawLine(color = lineColor, start = geom.hip, end = lerpOffset(geom.hip, geom.leftFoot, pLeftLeg), strokeWidth = strokeWidth * 0.8f, cap = StrokeCap.Round)
        }
        val pRightLeg = stageProgress(6)
        if (pRightLeg > 0f) {
            drawLine(color = lineColor, start = geom.hip, end = lerpOffset(geom.hip, geom.rightFoot, pRightLeg), strokeWidth = strokeWidth * 0.8f, cap = StrokeCap.Round)
        }
    }
}

/**
 * The chalk-on-chalkboard ambient identity shared by this screen's whole
 * background: a dark green gradient board with a faint wooden frame and a
 * scattering of chalk-dust flecks. [drawWithCache] regenerates the fleck
 * positions only when the drawing size actually changes (a real screen
 * rotation/fold event), not on every recomposition, so this stays cheap.
 */
@Composable
private fun Modifier.chalkboardBackground(): Modifier = this.drawWithCache {
    val rng = Random(1337)
    val flecks = List(150) {
        Offset(rng.nextFloat() * size.width, rng.nextFloat() * size.height) to (0.6f + rng.nextFloat() * 1.3f)
    }
    val gradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF14241C), Color(0xFF203828), Color(0xFF162820))
    )
    onDrawBehind {
        drawRect(gradient)
        drawRect(color = Color(0xFF4E342E), style = Stroke(width = 14f))
        flecks.forEach { (pos, r) ->
            drawCircle(color = Color.White.copy(alpha = 0.05f), radius = r, center = pos)
        }
    }
}
