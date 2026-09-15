package com.gamesuite.games.wordguess

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.games.wordgames.WordDictionary
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/** One letter's own feedback: exactly correct spot, present but wrong spot, or not in the secret at all. */
enum class LetterFeedback { CORRECT, PRESENT, ABSENT }

/** One submitted guess and its per-letter feedback — see [WordGuessGame.scoreGuess]'s own KDoc for the exact algorithm behind [feedback]. */
data class WordGuessEntry(val word: String, val feedback: List<LetterFeedback>)

/**
 * [secret] is the hidden word the player is guessing — present in the state the whole time (so
 * it CAN be inspected once the round ends) but never rendered by `WordGuessScreen` until
 * [solved] or [outOfGuesses] is true, the same "the engine holds the full truth, the UI chooses
 * when to show it" idiom `MinesweeperState`'s own hidden mine layout / `MastermindState`'s own
 * hidden secret already use. [solved] (guessed the word exactly) and [outOfGuesses] (used every
 * guess without solving it) are mutually exclusive terminal outcomes for the CURRENT round only,
 * distinct from [WordGuessGame.matchOver] — the same split every other solo puzzle here uses
 * between a round's outcome and the session's.
 */
data class WordGuessState(
    val secret: String,
    val maxGuesses: Int,
    val guesses: List<WordGuessEntry> = emptyList(),
    val solved: Boolean = false,
    val outOfGuesses: Boolean = false
) {
    val isOver: Boolean get() = solved || outOfGuesses
}

/**
 * Word Guess (New Games Wave 3 — see docs/NEW_GAMES_BRAINSTORM_WAVE_3.md) — guess a hidden
 * 5-letter word within a limited number of tries; each guess reveals, letter by letter, whether
 * it's in the right spot ([LetterFeedback.CORRECT]), the right letter in the wrong spot
 * ([LetterFeedback.PRESENT]), or not in the word at all ([LetterFeedback.ABSENT]).
 *
 * WORD SOURCE (see [WordGuessAnswerList]): the secret comes from a small, curated pool of 2,314
 * common words (real Wordle's own published answer list) — deliberately NOT the app's huge
 * general-purpose `WordDictionary` (~359k words shared with Word Search/Crossword/Word Tiles),
 * which is sourced for breadth and contains plenty of words too obscure to be a fair "word of
 * the day." Guess VALIDATION, by contrast, DOES use the broad `WordDictionary` (via
 * [isValidGuess]'s default) — any real 5-letter English word is an acceptable guess, matching
 * the genre's own real convention (a much larger "allowed guesses" list than "possible answers"
 * list) without this app needing to embed or curate a second bespoke word list of its own.
 *
 * TESTABILITY: [pickSecret] and [isValidGuess] are injectable seams (like every other engine's
 * own [nowMillis]) for exactly the same reason — their REAL implementations
 * (`WordGuessAnswerList.randomAnswer`/`WordDictionary.isValidWord`) both need a real Android
 * `Context` to read an assets file, which throws under plain JUnit (no Robolectric in this
 * project, same reasoning `MinesweeperGame`'s own `nowMillis` seam KDoc gives). Tests inject a
 * small fixed fake word pool instead of touching either real asset-backed store at all.
 *
 * DIFFICULTY: word length stays fixed at [WORD_LENGTH] (5) across every tier — that's the whole
 * cultural reference point this pick exists for, not something to dilute for a difficulty knob.
 * What DOES scale is the guess allowance: EASY 8, MEDIUM 6 (the genre's own classic default),
 * HARD 5 — still a real, meaningful difficulty lever without touching the "5-letter word" identity.
 *
 * DAILY VS. UNLIMITED REPLAY (a real design question docs/NEW_GAMES_BRAINSTORM_WAVE_3.md's own
 * entry flagged before any code was written): real Wordle's own defining restriction is exactly
 * ONE attempt per day, locked afterward. This engine does NOT reproduce that lock — it follows
 * this app's own universal daily-challenge convention instead (a `startMatch(dailySeed)` variant
 * that seeds today's date so every player sees the same word that day, sitting ALONGSIDE an
 * ordinary unlimited-replay `startMatch()` for a fresh random word any time) — because that
 * "same puzzle for everyone today, but nothing stops you from re-playing it or playing more"
 * shape is EXACTLY what every other daily-seeded solo puzzle in this app already does (Minesweeper,
 * Sudoku, Nonogram, Kakuro, KenKen, Edge Match, Mastermind — none of them lock you out after one
 * attempt either). Reproducing real Wordle's own harder, once-only restriction would have been a
 * genuinely NEW, unprecedented restriction for this app to invent, not a neutral choice — so this
 * follows established precedent rather than the specific outside genre's own stricter norm.
 */
class WordGuessGame(
    private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val pickSecret: (Random) -> String = { WordGuessAnswerList.randomAnswer(it) },
    private val isValidGuess: (String) -> Boolean = { WordDictionary.isValidWord(it) }
) : GameModule {
    override val gameId = "word-guess"
    override val displayName = "Word Guess"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<WordGuessState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-round. */
    val matchOver = mutableStateOf(false)

    /** Same "stopwatch starts on the first real guess" idiom as every other solo puzzle's own field — see [submitGuess]. */
    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)

    /** Total time for the current round, frozen the instant [submitGuess] detects a solve OR a loss; null until then. */
    val finishedElapsedMillis = mutableStateOf<Long?>(null)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** [pause]'s own nowMillis() reading, or null while not currently paused — see [pause]/[resume]'s KDoc. */
    private var pausedAtElapsedRealtime: Long? = null

    /** Total time spent paused during the CURRENT round, subtracted out wherever [finishedElapsedMillis] is computed — see [pause]/[resume]'s KDoc. */
    private var totalPausedMillis: Long = 0L

    /** Guess allowance per difficulty — see the class KDoc's DIFFICULTY section. Word length is NOT keyed here; it's the fixed [WORD_LENGTH] constant for every tier. */
    private val difficultyConfig: Map<CpuDifficulty, Int> = mapOf(
        CpuDifficulty.EASY to 8,
        CpuDifficulty.MEDIUM to 6,
        CpuDifficulty.HARD to 5
    )

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        matchOver.value = false
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    /**
     * Call once, from the UI, before [startMatch] — loads the shared `WordDictionary` asset
     * (guess validation) and the curated [WordGuessAnswerList] asset (secret selection).
     * Mirrors `WordSearchGame.loadDictionary()`'s own exact reasoning: [GameContext] carries no
     * Android [Context], so this needs its own separate call site the Composable screen (which
     * DOES have one via `LocalContext.current`) is responsible for calling.
     */
    fun loadWordData(androidContext: Context) {
        WordDictionary.ensureLoaded(androidContext)
        WordGuessAnswerList.ensureLoaded(androidContext)
    }

    override fun startMatch() = startMatch(dailySeed = null)

    /** See the class KDoc's DAILY VS. UNLIMITED REPLAY section / SlidingPuzzleGame.startMatch(dailySeed)'s KDoc for what [dailySeed] is for. */
    fun startMatch(dailySeed: Long?) {
        val maxGuesses = difficultyConfig[difficulty] ?: difficultyConfig.getValue(CpuDifficulty.MEDIUM)
        timerStartElapsedRealtime.value = null
        finishedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
        // A fresh round is always playable, regardless of whether a PRIOR round's
        // endMatch() left matchOver stuck true -- built in from the start here (not
        // found after the fact), the same fix already needed across every other
        // engine in this batch (see e.g. ColorFloodGame's own KDoc on this exact fix).
        matchOver.value = false
        val random = if (dailySeed != null) Random(dailySeed) else Random
        state.value = WordGuessState(
            secret = pickSecret(random).lowercase(),
            maxGuesses = maxGuesses
        )
    }

    /**
     * Snapshots the current time so [resume] can measure how long the app was actually paused,
     * so backgrounding mid-round never inflates the recorded solve time — the same fix
     * Minesweeper/Sudoku/Lights Out/Edge Match/Mastermind all needed. Built in here from the
     * start. Guarded by `pausedAtElapsedRealtime == null` so a second [pause] call with no
     * [resume] in between is a no-op rather than silently losing the intervening interval.
     */
    override fun pause() {
        if (pausedAtElapsedRealtime == null && timerStartElapsedRealtime.value != null && state.value?.isOver != true) {
            pausedAtElapsedRealtime = nowMillis()
        }
    }

    /** Accumulates the just-finished pause's duration into [totalPausedMillis] — see [pause]'s KDoc. */
    override fun resume() {
        pausedAtElapsedRealtime?.let {
            totalPausedMillis += nowMillis() - it
            pausedAtElapsedRealtime = null
        }
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /**
     * Submits [guess] as the next attempt — must be exactly [WORD_LENGTH] letters and pass
     * [isValidGuess], or this is a no-op (the UI's own input only enables "Submit" once a
     * full-length guess is typed, and separately surfaces an "not a word" message on rejection
     * rather than silently swallowing it — but this method itself stays a defensive floor, not
     * user-facing validation). Also a no-op if the round is already decided or the whole session
     * has already ended via [leaveSession]/[endMatch]. [guess] is lowercased before every check
     * and before scoring, so the UI can pass user input in whatever case it was typed.
     */
    fun submitGuess(guess: String) {
        val s = state.value ?: return
        if (matchOver.value || s.isOver) return
        val normalized = guess.lowercase()
        if (normalized.length != WORD_LENGTH) return
        if (!isValidGuess(normalized)) return

        // Stopwatch starts on the first *actual* guess, not on secret selection -- same idiom
        // every other solo puzzle's own timer start uses.
        if (timerStartElapsedRealtime.value == null) {
            timerStartElapsedRealtime.value = nowMillis()
        }

        val feedback = scoreGuess(normalized, s.secret)
        val entries = s.guesses + WordGuessEntry(normalized, feedback)
        val solved = normalized == s.secret
        val outOfGuesses = !solved && entries.size >= s.maxGuesses
        state.value = s.copy(guesses = entries, solved = solved, outOfGuesses = outOfGuesses)

        if (solved) {
            puzzlesSolved.value += 1
            freezeTimer()
        } else if (outOfGuesses) {
            freezeTimer()
        }
    }

    private fun freezeTimer() {
        val start = timerStartElapsedRealtime.value ?: nowMillis()
        finishedElapsedMillis.value = (nowMillis() - start) - totalPausedMillis
    }

    /** Called from the finished-round panel's "New Word" button — keeps the running tally. */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    /** Called from the finished-round panel's "Back to Menu" button — ends the whole session. */
    fun leaveSession() {
        if (matchOver.value) return
        val player = context.players.getOrNull(context.localPlayerIndex)
        val result = GameResult(
            scores = if (player != null) listOf(
                PlayerScore(playerId = player.playerId, score = puzzlesSolved.value, isWinner = puzzlesSolved.value > 0)
            ) else emptyList()
        )
        endMatch(result)
    }

    /**
     * The standard two-pass Wordle scoring algorithm, mirroring [com.gamesuite.games.mastermind.MastermindGame.scoreGuess]'s
     * own two-pass shape but producing PER-LETTER feedback instead of aggregate black/white
     * counts.
     *
     * PASS 1: for every position, an exact letter+position match is marked [LetterFeedback.CORRECT],
     * and that letter is consumed out of a working copy of the secret (overwritten with a
     * sentinel that can never equal a real lowercase letter) so it can never be matched again in
     * pass 2 — from either side.
     *
     * PASS 2: for every position that wasn't an exact match, walking LEFT TO RIGHT, mark it
     * [LetterFeedback.PRESENT] if its letter still exists anywhere in the secret's remaining
     * (post-pass-1) pool — consuming that one occurrence too — else [LetterFeedback.ABSENT].
     *
     * This two-pass, left-to-right-consuming shape is what prevents a repeated letter in the
     * guess from being marked PRESENT more times than it actually appears in the secret, AND
     * produces the correct canonical assignment when there are multiple candidate positions for
     * a single remaining occurrence (real Wordle's own actual behavior gives priority to the
     * leftmost one, which is exactly what processing pass 2 in index order does automatically —
     * not a separate rule bolted on). Worked example: secret="lemon", guess="lolly" — position 0
     * ('l'=='l') is CORRECT, consuming the secret's only 'l'. The remaining secret is
     * "_emon" (positions 1-4). Walking left to right through guess's remaining positions
     * (1='o', 2='l', 3='l', 4='y'): position 1's 'o' IS still in the remaining secret (at index
     * 3) — PRESENT, consumed. Position 2's 'l' is NOT (already consumed as the pass-1 CORRECT
     * match) — ABSENT. Position 3's 'l' — also ABSENT, same reason. Position 4's 'y' — ABSENT,
     * never present at all. Final: [CORRECT, PRESENT, ABSENT, ABSENT, ABSENT] — a case this
     * class's own tests check exactly (not just via aggregate counts, which a subtly-wrong
     * implementation could still get right in total while assigning PRESENT to the wrong one of
     * two candidate positions).
     */
    private fun scoreGuess(guess: String, secret: String): List<LetterFeedback> {
        val feedback = MutableList(guess.length) { LetterFeedback.ABSENT }
        val remainingSecret = secret.toMutableList()

        for (i in guess.indices) {
            if (guess[i] == secret[i]) {
                feedback[i] = LetterFeedback.CORRECT
                remainingSecret[i] = CONSUMED_MARKER
            }
        }
        for (i in guess.indices) {
            if (feedback[i] == LetterFeedback.CORRECT) continue
            val idx = remainingSecret.indexOf(guess[i])
            if (idx != -1) {
                feedback[i] = LetterFeedback.PRESENT
                remainingSecret[idx] = CONSUMED_MARKER
            }
        }
        return feedback
    }

    companion object {
        /** Fixed across every difficulty tier — see the class KDoc's DIFFICULTY section for why. */
        const val WORD_LENGTH = 5

        /** A sentinel character no real lowercase letter can ever equal, used to mark a secret letter as "already consumed" by [scoreGuess]'s own two-pass algorithm. */
        private const val CONSUMED_MARKER = ' '
    }
}
