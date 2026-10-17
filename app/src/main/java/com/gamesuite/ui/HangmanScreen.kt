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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.specularSweep
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
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
 *
 * CHROME: the shared [GameChrome] corner menu (Back to Menu / How to Play / Hint) with its
 * BackHandler. Leaving mid-word is an abort (never scored) when no word has been finished this
 * session; once words have been solved or lost, leaving goes through [HangmanGame.leaveSession]
 * so those results still count — only the unfinished word is dropped. The header is inset by
 * [GameChromeEndInset] so the corner button never overlaps it.
 *
 * RESUME + HINT: start-up asks the session manager to resume (the engine restores the SAME hidden
 * word, guessed letters, hints and tally); a resumed word does not replay its shake/celebration.
 * The Hint button (next to the guess counter, also in the corner menu) reveals one correct letter
 * and marks the word assisted; the result panel then says how many hints were used.
 *
 * LAYOUT: the stage is anchored at the top (so winning/losing, which swaps the keyboard for the
 * result panel, does not re-centre the gallows), the keyboard stays on screen — inert — while the
 * result is held back (win ~600ms, loss until the last stroke is drawn), the portrait result panel
 * scrolls, and the gallows is sized from the measured slot (up to [HangmanGallowsMaxWidth]) rather
 * than a fixed 170x200dp. The word row sizes its slots from the measured width so a 12-letter word
 * fits a 312dp pane at any text scale.
 *
 * ACCESSIBILITY: the word row, gallows and guess counter carry descriptions (the counter and word
 * are polite live regions); correct/wrong keys also get a check/cross mark in colour-blind mode;
 * key text colour is picked by luminance for contrast.
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
        // Picking Hangman after leaving it mid-word offers Resume; when taken, the engine is put
        // back with the SAME hidden word. (Difficulty is restored by the engine; the header label
        // below reads it from the engine, so this screen keeps no copy of its own.)
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    val s = state ?: return
    val wins = game.wins.value
    val losses = game.losses.value
    val colorblind = LocalColorblindMode.current

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
    // over an unfinished figure.
    var lossRevealReady by remember(s.word) { mutableStateOf(false) }

    // A win is held ~600ms before its result panel replaces the keyboard, so the last letter's
    // flight and the reveal animation can land first (and the layout does not jump the same frame).
    var winRevealReady by remember(s.word) { mutableStateOf(false) }
    // True when this word was already finished the first time it was composed (a resumed game):
    // it shows its result panel straight away and does not replay the celebration.
    val finishedOnArrival = remember(s.word) { s.matchOver }
    LaunchedEffect(s.word, s.matchOver, s.won) {
        if (s.matchOver && s.won) {
            if (!reducedMotion && !finishedOnArrival) delay(600)
            winRevealReady = true
        }
    }

    // The wrong-guess count this word started this composition with: a resumed word comes back
    // with wrong guesses already on the board, which must not replay the shake/buzz as if the
    // player had just missed.
    var shownWrongGuesses by remember(s.word) { mutableStateOf(s.wrongGuesses) }

    LaunchedEffect(s.wrongGuesses) {
        if (s.wrongGuesses <= shownWrongGuesses) return@LaunchedEffect
        shownWrongGuesses = s.wrongGuesses
        flashActive = true
        // Haptic and sound fire at the instant of the miss, concurrently with the shake (they
        // used to wait ~275ms for it to finish).
        val isFinalGuess = s.matchOver && !s.won
        if (!isFinalGuess) {
            haptics(HapticSignal.FAILURE)
            sounds.playDraw()
        }
        if (!reducedMotion) {
            shakeOffset.snapTo(0f)
            shakeOffset.animateTo(-10f, tween(45))
            shakeOffset.animateTo(10f, tween(65))
            shakeOffset.animateTo(-7f, tween(65))
            shakeOffset.animateTo(7f, tween(55))
            shakeOffset.animateTo(0f, tween(45))
        }
        delay(180)
        flashActive = false
    }

    // Correct-guess pipeline: the key's onClick (or the Hint action) sets this the instant a NEW
    // correct letter is revealed (computed synchronously against the pre-guess
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
        // The only player is the human, so every win here is the human's win. The celebration
        // waits for the letter's own confirmation tick (~220ms arc) so the two do not overlap.
        if (s.matchOver && s.won && !finishedOnArrival) {
            if (!reducedMotion) delay(240)
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

    // Hint: reveals one correct letter. The returned letter feeds the same flying-glyph /
    // confirmation pipeline a keyboard press uses, so a hinted letter lands like a guessed one.
    val onHintRequest: () -> Unit = {
        val revealed = game.hint()
        if (revealed != null) {
            playSfx(SfxKind.LIGHT_TICK)
            pendingCorrectLetter = revealed
        }
    }

    val difficultyLabel = game.difficulty.name.lowercase().replaceFirstChar { it.uppercase() }
    val finishedWords = game.finishedWords

    // Back / abort-confirm / How to Play / Hint live in the shared GameChrome. Leaving mid-word
    // discards ONLY the unfinished word: if earlier words this session were already solved or
    // lost, leaving goes through leaveSession() so those results still count; a session with
    // nothing finished is a pure abort (never a win or loss). A finished word leaves through
    // leaveSession() too, which scores the session.
    GameChrome(
        helpTitle = "How to Play Hangman",
        helpText = "Guess the hidden word one letter at a time by tapping the keys. A correct " +
            "letter fills every slot it belongs to; a wrong letter draws another part of the " +
            "hangman.\n\n" +
            "Six wrong guesses complete the figure and lose the word. Hint reveals one correct " +
            "letter for you and marks that word as assisted.\n\n" +
            "Word difficulty follows your default difficulty setting. New Word keeps your " +
            "running wins and losses.",
        matchInProgress = !s.matchOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedWords > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedWords == 0,
        onHint = onHintRequest,
        buttonFill = HangmanBoardColor,
        buttonContent = chalkText,
        leaveTitle = "Leave this word?",
        leaveBody = if (finishedWords > 0) {
            "This word is still in progress and won't count, but the words you've already finished stay on your record."
        } else {
            "This word is still in progress. Leaving now won't count it as a win or a loss."
        }
    ) {
        Box(modifier = Modifier.fillMaxSize().chalkboardBackground()) {
            // Primary-only (no secondary/hand content in this game) — same treatment
            // as TicTacToeScreen: caps + centers on a Tab S9 / unfolded Fold instead
            // of sitting stretched across the full width.
            AdaptiveTwoPane(
                foldState = LocalFoldState.current,
                modifier = Modifier.fillMaxSize().padding(HangmanPaneInset),
                primary = {
                    // Landscape reflow: a cover-screen window rotated to landscape
                    // (~344dp tall) cannot fit the score header + a gallows +
                    // guess counter + word row + on-screen keyboard all stacked in one
                    // column without clipping. Reflow the chrome BESIDE the gallows in a
                    // Row instead of shrinking anything below a usable size — portrait
                    // keeps the single-column stack. The aspect check is done here, from
                    // this BoxWithConstraints' own REAL measured bounds, so it is correct
                    // no matter which AdaptiveLayoutMode branch placed this primary slot.
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val isLandscape = maxWidth > maxHeight
                        val showResultPanel = s.matchOver && (if (s.won) winRevealReady else lossRevealReady)

                        // Gallows sizing: derived from the measured slot's width AND height
                        // (the whole column in portrait, the left half in landscape), keeping
                        // the figure's 0.85 aspect, never a size hardcoded independent of the
                        // window. It grows on a tablet up to HangmanGallowsMaxWidth.
                        val gallowsHeightBudget = if (isLandscape) maxHeight * 0.62f else maxHeight * 0.30f
                        val gallowsWidthBudget = if (isLandscape) maxWidth * 0.42f else maxWidth * 0.60f
                        val gallowsWidth = minOf(
                            gallowsWidthBudget,
                            gallowsHeightBudget * HANGMAN_GALLOWS_ASPECT,
                            HangmanGallowsMaxWidth
                        ).coerceAtLeast(0.dp)
                        val gallowsHeight = gallowsWidth / HANGMAN_GALLOWS_ASPECT
                        val gallowsDescription =
                            "Gallows: ${s.wrongGuesses} of ${s.maxWrongGuesses} wrong guesses drawn"
                        val gallowsModifier = Modifier
                            .size(gallowsWidth, gallowsHeight)
                            .specularSweep(enabled = sheenEnabled, tint = Color.White.copy(alpha = 0.10f))
                            .semantics { contentDescription = gallowsDescription }

                        val onGallowsStageDrawn: (Int) -> Unit = { stage ->
                            if (stage == s.maxWrongGuesses && finishedOnArrival) {
                                // A resumed lost word: just show its result, no buzz or sound replay.
                                lossRevealReady = true
                            } else if (stage == s.maxWrongGuesses) {
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
                        val guessStatus: @Composable () -> Unit = {
                            HangmanGuessStatus(
                                remaining = s.remainingGuesses,
                                maxGuesses = s.maxWrongGuesses,
                                canHint = game.canHint,
                                hintsUsed = s.hintsUsed,
                                chalkText = chalkText,
                                reducedMotion = reducedMotion,
                                onHint = onHintRequest
                            )
                        }

                        if (isLandscape) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Top
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
                                    guessStatus()
                                }

                                Column(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Spacer(Modifier.height(4.dp))
                                    HangmanWordRow(
                                        word = s.word,
                                        guessedLetters = s.guessedLetters,
                                        shakeOffsetX = shakeOffset.value,
                                        flashColor = flashColor,
                                        reducedMotion = reducedMotion,
                                        chalkText = chalkText,
                                        onSlotPositioned = { index, pos -> slotPositions[index] = pos },
                                        // Keep the word clear of the corner button in this column's top-right.
                                        modifier = Modifier.padding(end = (GameChromeEndInset - HangmanPaneInset).coerceAtLeast(0.dp))
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
                                                hintsUsed = s.hintsUsed,
                                                reducedMotion = reducedMotion,
                                                chalkText = chalkText,
                                                onNewWord = game::playAgain,
                                                onBackToMenu = game::leaveSession
                                            )
                                        }
                                    } else {
                                        // weight(1f, fill = false) gives this LazyVerticalGrid a
                                        // REAL bounded max height (its fair share of this column,
                                        // never more) — it fills that height and scrolls
                                        // internally to reach every key. While the result is held
                                        // back the keyboard stays (inert) so nothing re-centres.
                                        HangmanKeyboard(
                                            modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                                            guessedLetters = s.guessedLetters,
                                            word = s.word,
                                            inputEnabled = !s.matchOver,
                                            colorblind = colorblind,
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
                                // Top-anchored: swapping the keyboard for the result panel must
                                // not re-centre the header, gallows and word row.
                                verticalArrangement = Arrangement.Top
                            ) {
                                Spacer(Modifier.height(4.dp))
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
                                guessStatus()
                                Spacer(Modifier.height(12.dp))
                                HangmanWordRow(
                                    word = s.word,
                                    guessedLetters = s.guessedLetters,
                                    shakeOffsetX = shakeOffset.value,
                                    flashColor = flashColor,
                                    reducedMotion = reducedMotion,
                                    chalkText = chalkText,
                                    onSlotPositioned = { index, pos -> slotPositions[index] = pos }
                                )
                                Spacer(Modifier.height(16.dp))
                                if (showResultPanel) {
                                    // Scrolls, so the buttons can never be pushed off a short window.
                                    Column(
                                        modifier = Modifier
                                            .weight(1f, fill = false)
                                            .fillMaxWidth()
                                            .verticalScroll(rememberScrollState()),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        HangmanResultPanel(
                                            won = s.won,
                                            word = s.word,
                                            hintsUsed = s.hintsUsed,
                                            reducedMotion = reducedMotion,
                                            chalkText = chalkText,
                                            onNewWord = game::playAgain,
                                            onBackToMenu = game::leaveSession
                                        )
                                    }
                                } else {
                                    HangmanKeyboard(
                                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                                        guessedLetters = s.guessedLetters,
                                        word = s.word,
                                        inputEnabled = !s.matchOver,
                                        colorblind = colorblind,
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
}

/** The screen's own padding around the stage; the chrome inset is measured against it. */
private val HangmanPaneInset = 24.dp

/** Upper bound for the gallows width so it does not sprawl on a tablet (it grows from 170dp up to this). */
private val HangmanGallowsMaxWidth = 300.dp

/** Gallows width / height: the figure is drawn in a 170 x 200 box. */
private const val HANGMAN_GALLOWS_ASPECT = 0.85f

/** The darkest board green: the corner button's fill, matching [chalkboardBackground]'s gradient start. */
private val HangmanBoardColor = Color(0xFF14241C)

/** Widest a single word slot may be; short words keep their familiar compact look. */
private val HangmanSlotMaxWidth = 26.dp

@Composable
private fun HangmanHeaderText(wins: Int, losses: Int, difficultyLabel: String, chalkText: Color) {
    // Inset on both sides (so the text stays centred) by what the corner button needs beyond the
    // screen's own padding, so the two can never overlap.
    Column(
        modifier = Modifier.padding(horizontal = (GameChromeEndInset - HangmanPaneInset).coerceAtLeast(0.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Wins: $wins · Losses: $losses",
            style = MaterialTheme.typography.labelLarge,
            color = chalkText,
            textAlign = TextAlign.Center
        )
        Text(
            "Word difficulty: $difficultyLabel",
            style = MaterialTheme.typography.labelSmall,
            color = chalkText.copy(alpha = 0.75f),
            textAlign = TextAlign.Center
        )
    }
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
 *
 * Slots are sized from the MEASURED width (capped at [HangmanSlotMaxWidth]) and the glyph size
 * follows the slot, in fixed dp (independent of the text-scale setting), so a long word shrinks
 * to fit instead of overflowing.
 */
@Composable
private fun HangmanWordRow(
    word: String,
    guessedLetters: Set<Char>,
    shakeOffsetX: Float,
    flashColor: Color,
    reducedMotion: Boolean,
    chalkText: Color,
    onSlotPositioned: (index: Int, center: Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val spoken = word.map { if (it in guessedLetters) it.toString() else "blank" }.joinToString(", ")
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val rowPadding = 12.dp
        val gap = 6.dp
        val count = word.length.coerceAtLeast(1)
        val slotWidth: Dp = ((maxWidth - rowPadding * 2 - gap * (count - 1)) / count)
            .coerceIn(10.dp, HangmanSlotMaxWidth)
        val fontScale = LocalDensity.current.fontScale
        val glyphSize = (minOf(slotWidth.value * 1.1f, 28f) / fontScale).sp
        Row(
            modifier = Modifier
                .offset(x = shakeOffsetX.dp)
                .background(flashColor, shape = RoundedCornerShape(8.dp))
                .padding(horizontal = rowPadding, vertical = 6.dp)
                .clearAndSetSemantics {
                    contentDescription = "Word, ${word.length} letters: $spoken"
                    liveRegion = LiveRegionMode.Polite
                },
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            word.forEachIndexed { index, letter ->
                key(word, index) {
                    val revealed = letter in guessedLetters
                    Box(
                        modifier = Modifier
                            .width(slotWidth)
                            .onGloballyPositioned { coords ->
                                onSlotPositioned(
                                    index,
                                    coords.positionInRoot() + Offset(coords.size.width / 2f, coords.size.height / 2f)
                                )
                            },
                        contentAlignment = Alignment.Center
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
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = glyphSize,
                                    lineHeight = glyphSize * 1.25f
                                ),
                                color = chalkText,
                                textAlign = TextAlign.Center
                            )
                        }
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
    hintsUsed: Int,
    reducedMotion: Boolean,
    chalkText: Color,
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
            color = if (won) Color(0xFF81C784) else Color(0xFFEF9A9A),
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    if (hintsUsed > 0) {
        Spacer(Modifier.height(4.dp))
        val hintWord = if (hintsUsed == 1) "hint" else "hints"
        Text(
            if (won) "Solved with $hintsUsed $hintWord" else "$hintsUsed $hintWord used",
            style = MaterialTheme.typography.bodyMedium,
            color = chalkText.copy(alpha = 0.85f)
        )
    }
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onNewWord,
        modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
    ) { Text("New Word") }
    Spacer(Modifier.height(8.dp))
    // Explicit content/border colour: the default outlined style is tuned for a light surface and
    // read at ~2.2:1 on this dark board.
    OutlinedButton(
        onClick = onBackToMenu,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = chalkText),
        border = BorderStroke(1.dp, chalkText.copy(alpha = 0.6f)),
        modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
    ) { Text("Back to Menu") }
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
 *
 * [inputEnabled] is false once the word is over but its result is still being held back: the
 * keyboard stays on screen so the layout does not jump, but every key is inert. In
 * [colorblind] mode a guessed key also shows a check (in the word) or cross (not in the word).
 */
@Composable
private fun HangmanKeyboard(
    modifier: Modifier,
    guessedLetters: Set<Char>,
    word: String,
    inputEnabled: Boolean,
    colorblind: Boolean,
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
            // Foreground by luminance: white on the mid-green was ~2.8:1; dark text clears 7:1 there.
            val fg = when {
                !used -> chalkText
                bg.luminance() > 0.179f -> Color(0xFF10201A)
                else -> Color.White
            }
            val label = when {
                colorblind && used && correct -> "$letter✓"
                colorblind && used -> "$letter✗"
                else -> letter.toString()
            }
            Button(
                onClick = { onGuess(letter) },
                enabled = !used && inputEnabled,
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
                // The key's description above already says everything; the visible text (and the
                // colour-blind mark) is decoration for the screen reader.
                Text(label, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

/**
 * The guess counter with the Hint button beside it. The button is a 48dp-high control that stays
 * visible and is disabled (with the reason in its description) when there is nothing to reveal.
 */
@Composable
private fun HangmanGuessStatus(
    remaining: Int,
    maxGuesses: Int,
    canHint: Boolean,
    hintsUsed: Int,
    chalkText: Color,
    reducedMotion: Boolean,
    onHint: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TensionGuessCounter(
            remaining = remaining,
            maxGuesses = maxGuesses,
            reducedMotion = reducedMotion,
            modifier = Modifier.weight(1f, fill = false)
        )
        OutlinedButton(
            onClick = onHint,
            enabled = canHint,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = chalkText,
                disabledContentColor = chalkText.copy(alpha = 0.45f)
            ),
            border = BorderStroke(1.dp, chalkText.copy(alpha = if (canHint) 0.6f else 0.25f)),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics {
                    contentDescription = if (canHint) {
                        "Hint: reveal one correct letter" +
                            if (hintsUsed > 0) ", $hintsUsed used on this word" else ""
                    } else {
                        "Hint unavailable: the word is over"
                    }
                }
        ) {
            Text("Hint", modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
private fun TensionGuessCounter(
    remaining: Int,
    maxGuesses: Int,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier
) {
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
        fontWeight = if (remaining <= 2) FontWeight.Bold else FontWeight.Normal,
        // The pulsing/colour tension is visual only; the polite live region announces the
        // progress ("Wrong guesses 3 of 6, 3 left") when it changes.
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "Wrong guesses ${maxGuesses - remaining} of $maxGuesses, $remaining left"
            liveRegion = LiveRegionMode.Polite
        }
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
