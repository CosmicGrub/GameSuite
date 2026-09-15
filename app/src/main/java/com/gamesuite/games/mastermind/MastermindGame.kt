package com.gamesuite.games.mastermind

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * One submitted guess and its feedback: [blackPegs] = correct color AND position;
 * [whitePegs] = correct color, wrong position. Each peg in both the guess and the secret is
 * counted at most once total (either as black or white, never both) — see
 * [MastermindGame.scoreGuess]'s own KDoc for the exact two-pass algorithm this relies on.
 */
data class MastermindGuess(val colors: List<Int>, val blackPegs: Int, val whitePegs: Int)

/**
 * [secret] is the hidden color sequence the player is guessing — present in the state (so it
 * CAN be inspected once the round ends) but never rendered by `MastermindScreen` until [solved]
 * or [outOfGuesses] is true, the same "the engine holds the full truth, the UI chooses when to
 * show it" idiom `MinesweeperState`'s own hidden mine layout already uses. [solved] (guessed the
 * secret exactly) and [outOfGuesses] (used every guess without solving it) are mutually
 * exclusive terminal outcomes for the CURRENT round only, distinct from
 * [MastermindGame.matchOver] — the same split every other solo puzzle here uses between a
 * round's outcome and the session's.
 */
data class MastermindState(
    val positions: Int,
    val colorCount: Int,
    val maxGuesses: Int,
    val secret: List<Int>,
    val guesses: List<MastermindGuess> = emptyList(),
    val solved: Boolean = false,
    val outOfGuesses: Boolean = false
) {
    val isOver: Boolean get() = solved || outOfGuesses
}

/**
 * Mastermind (New Games Wave 3's own top recommendation — see
 * docs/NEW_GAMES_BRAINSTORM_WAVE_3.md) — guess a hidden color sequence within a limited number
 * of tries, using black-peg (right color, right spot) / white-peg (right color, wrong spot)
 * feedback after each guess to narrow it down.
 *
 * GENERATION: a uniformly random [secret][MastermindState.secret], nothing more — genuinely the
 * lowest-risk generator in this whole app. Unlike every uniqueness-guaranteeing puzzle here
 * (Sudoku/Nonogram/Kakuro/KenKen/Edge Match), there is no fairness question to verify at
 * generation time at all: ANY random secret is an equally fair puzzle, since the player never
 * sees a pre-scrambled board the way those puzzles' own generators have to guarantee a
 * *guess-free* solution path for. Colors MAY repeat within the secret (the standard genre rule)
 * — see [generateSecret] and [scoreGuess]'s own handling of repeated colors.
 *
 * DIFFICULTY: EASY/MEDIUM/HARD scale positions AND color count together (4/4, 4/6, 5/8) — the
 * same "more cells AND more choices, together" lever ColorFloodGame/EdgeMatchGame already use.
 * MEDIUM (4 positions, 6 colors) is the genre's own original/classic configuration. Every tier
 * gets [MAX_GUESSES] (10) tries — the genre's own standard allowance, not scaled per tier (unlike
 * board size/colors, more guesses wouldn't make a harder board *feel* harder, just longer).
 *
 * MOVES/SESSION: same solo-puzzle session pattern as every other engine in this app —
 * module-level [matchOver] distinct from the per-round [MastermindState.isOver]; finishing a
 * round (solved OR out of guesses) does not end the match, just bumps [puzzlesSolved] on a
 * genuine solve; [playAgain] deals a fresh secret keeping the tally; [leaveSession] builds the
 * [GameResult] from the tally and ends the match.
 */
class MastermindGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule {
    override val gameId = "mastermind"
    override val displayName = "Mastermind"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<MastermindState?>(null)
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

    /** (positions, colorCount) per difficulty — see the class KDoc's DIFFICULTY section. */
    private val difficultyConfig: Map<CpuDifficulty, Pair<Int, Int>> = mapOf(
        CpuDifficulty.EASY to (4 to 4),
        CpuDifficulty.MEDIUM to (4 to 6),
        CpuDifficulty.HARD to (5 to 8)
    )

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        matchOver.value = false
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() = startMatch(dailySeed = null)

    /** See the class KDoc / SlidingPuzzleGame.startMatch(dailySeed)'s KDoc for what [dailySeed] is for. */
    fun startMatch(dailySeed: Long?) {
        val (positions, colorCount) = difficultyConfig[difficulty] ?: difficultyConfig.getValue(CpuDifficulty.MEDIUM)
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
        state.value = MastermindState(
            positions = positions,
            colorCount = colorCount,
            maxGuesses = MAX_GUESSES,
            secret = generateSecret(positions, colorCount, random)
        )
    }

    /**
     * Snapshots the current time so [resume] can measure how long the app was
     * actually paused, so backgrounding mid-round never inflates the recorded
     * solve time — the same fix Minesweeper/Sudoku/Lights Out/Sliding Puzzle/
     * Color Flood/Edge Match all needed. Built in here from the start, per
     * docs/NEW_GAMES_BRAINSTORM_WAVE_3.md's own reminder to apply every already-
     * known bug-pattern fix proactively rather than rediscover it. Guarded by
     * `pausedAtElapsedRealtime == null` so a second [pause] call with no [resume]
     * in between is a no-op rather than silently losing the intervening interval.
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
     * Submits [colors] as the next guess — must be exactly
     * [MastermindState.positions] long, each color in `0 until colorCount`,
     * or this is a no-op (the UI's own "Submit" button only enables once a
     * full, valid guess is built, so this is a defensive floor, not
     * user-facing validation). Also a no-op if the round is already decided
     * or the whole session has already ended via [leaveSession]/[endMatch].
     */
    fun submitGuess(colors: List<Int>) {
        val s = state.value ?: return
        if (matchOver.value || s.isOver) return
        if (colors.size != s.positions) return
        if (colors.any { it !in 0 until s.colorCount }) return

        // Stopwatch starts on the first *actual* guess, not on secret generation --
        // same idiom every other solo puzzle's own timer start uses.
        if (timerStartElapsedRealtime.value == null) {
            timerStartElapsedRealtime.value = nowMillis()
        }

        val (black, white) = scoreGuess(colors, s.secret)
        val guesses = s.guesses + MastermindGuess(colors, black, white)
        val solved = black == s.positions
        val outOfGuesses = !solved && guesses.size >= s.maxGuesses
        state.value = s.copy(guesses = guesses, solved = solved, outOfGuesses = outOfGuesses)

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

    /** Called from the finished-round panel's "New Secret" button — keeps the running tally. */
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

    /** A uniformly random color sequence, [positions] long, each in `0 until colorCount` — colors may repeat, the standard genre rule. [random] is threaded through so a daily-seeded round is reproducible, same idiom every other generator in this app uses. */
    private fun generateSecret(positions: Int, colorCount: Int, random: Random): List<Int> =
        List(positions) { random.nextInt(colorCount) }

    /**
     * The standard two-pass Mastermind scoring algorithm, returning (blackPegs, whitePegs).
     *
     * PASS 1: walk both sequences position-by-position; an exact match (same color, same
     * position) counts as a black peg, and that position is consumed out of BOTH sequences —
     * its color never participates in pass 2 at all, from either side.
     *
     * PASS 2: of whatever's left (every position that wasn't an exact match), count the size of
     * the color MULTISET intersection — for each color, `min(how many times it appears in the
     * remaining guess, how many times it appears in the remaining secret)` — and sum that over
     * every color. That sum is the white peg count.
     *
     * This two-pass shape is what prevents a repeated color from being double- or over-counted.
     * Worked example: secret=[1,1,2,3], guess=[1,2,2,2] — position 0 (1==1) and position 2
     * (2==2) are black (2 total); the remaining guess is [2,2] (positions 1 and 3) and the
     * remaining secret is [1,3] (positions 1 and 3) — remaining guess has zero '2's to match
     * against a remaining secret with zero '2's left (its own single '2' was already consumed
     * as a black peg at position 2), so white=0. Total peg matches (black+white) equals the
     * full-multiset color intersection between the ORIGINAL guess and secret (2, since guess has
     * one '1' and three '2's, secret has two '1's/one '2'/one '3', and
     * min(1,2)+min(3,1)+min(0,1)=1+1+0=2) minus the black pegs already accounted for — a real
     * invariant this class's own tests check directly, not just this one worked example.
     */
    private fun scoreGuess(guess: List<Int>, secret: List<Int>): Pair<Int, Int> {
        val remainingGuess = mutableListOf<Int>()
        val remainingSecret = mutableListOf<Int>()
        var black = 0
        for (i in guess.indices) {
            if (guess[i] == secret[i]) {
                black++
            } else {
                remainingGuess += guess[i]
                remainingSecret += secret[i]
            }
        }

        val secretCounts = remainingSecret.groupingBy { it }.eachCount().toMutableMap()
        var white = 0
        for (color in remainingGuess) {
            val remaining = secretCounts[color] ?: 0
            if (remaining > 0) {
                white++
                secretCounts[color] = remaining - 1
            }
        }
        return black to white
    }

    companion object {
        /** The genre's own standard guess allowance — not scaled per difficulty tier, see the class KDoc's DIFFICULTY section. */
        const val MAX_GUESSES = 10
    }
}
