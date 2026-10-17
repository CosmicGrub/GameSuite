package com.gamesuite.games.mastermind

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Everything needed to put a Mastermind session back exactly as it was: the hidden secret, the
 * guesses so far, the session tally, the tier, the daily seed, the hint count and the ACTIVE
 * (pause-adjusted) elapsed time. Absolute clock readings are never stored.
 */
@Serializable
private data class MastermindSnapshot(
    val players: List<MastermindSnapshotPlayer>,
    val positions: Int,
    val colorCount: Int,
    val maxGuesses: Int,
    val secret: List<Int>,
    val guesses: List<MastermindSnapshotGuess>,
    val solved: Boolean,
    val outOfGuesses: Boolean,
    val puzzlesSolved: Int,
    val puzzlesFailed: Int,
    val roundNumber: Int,
    val difficulty: String,
    val dailySeed: Long?,
    val hintsUsed: Int,
    val resultHandled: Boolean,
    /** Active elapsed time of the current code; null while the stopwatch has not started. */
    val elapsedMillis: Long?,
    /** The frozen round time once the code is decided; null while it is live. */
    val finishedMillis: Long?
)

@Serializable
private data class MastermindSnapshotGuess(val colors: List<Int>, val blackPegs: Int, val whitePegs: Int)

@Serializable
private data class MastermindSnapshotPlayer(val playerId: String, val isBot: Boolean)

private val mastermindSnapshotJson = Json { ignoreUnknownKeys = true }

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
 * genuine solve or [puzzlesFailed] on a loss; [playAgain] deals a fresh secret keeping the
 * tally; [leaveSession] builds the [GameResult] from the tally and ends the match. Solved plus
 * failed is the number of FINISHED codes, which the screen reads to decide whether leaving
 * mid-code may be a pure abort (nothing finished yet) or must go through [leaveSession] so the
 * finished codes still count.
 */
class MastermindGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule, ResumableGame {
    override val gameId = "mastermind"
    override val displayName = "Mastermind"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<MastermindState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** Codes lost to running out of guesses this session; with [puzzlesSolved] this is the finished-code count. See the class KDoc. */
    val puzzlesFailed = mutableStateOf(0)

    /**
     * Counts every fresh code dealt (bumped by each [startMatch], never reset, never lowered). The
     * screen keys its per-code state (stats-recorded flag, the half-built guess) on this instead of
     * on [MastermindState.secret]: a random secret can repeat back to back (1 in 256 at EASY), and
     * re-dealing the same daily seed gives the identical secret, which with the secret as the key
     * would skip recording the second solve.
     */
    val roundNumber = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-round. */
    val matchOver = mutableStateOf(false)

    /** Same "stopwatch starts on the first real guess" idiom as every other solo puzzle's own field — see [submitGuess]. */
    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)

    /** Total time for the current round, frozen the instant [submitGuess] detects a solve OR a loss; null until then. */
    val finishedElapsedMillis = mutableStateOf<Long?>(null)

    /** Hints taken on the CURRENT code. Any hint marks the code assisted: it still counts as solved, but must not set a record. Reset by every fresh code. */
    val hintsUsed = mutableStateOf(0)

    /** The code the last [hint] pre-filled, until the next guess, a new code or a restore clears it. */
    val hintCode = mutableStateOf<List<Int>?>(null)

    /** The seed of today's daily code while that code is the one on screen; null for a random code. The screen derives its "Daily code" tag from this. */
    val activeDailySeed = mutableStateOf<Long?>(null)

    /** True once the screen has recorded (or deliberately skipped recording) the current code's result, so a resumed finished code is never recorded twice. */
    val resultHandled = mutableStateOf(false)

    /** True when a hint was used on the current code, so a solve must not set a best-time/best-guesses record. */
    val isAssisted: Boolean get() = hintsUsed.value > 0

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
        puzzlesFailed.value = 0
        matchOver.value = false
        hintsUsed.value = 0
        hintCode.value = null
        activeDailySeed.value = null
        resultHandled.value = false
    }

    // --- Resume -----------------------------------------------------------------------------

    /** Null when there is nothing to save: no code yet, the session already ended, or a mode whose state lives with other players. */
    override fun snapshot(): String? {
        return try {
            val s = state.value ?: return null
            if (matchOver.value) return null
            if (!this::context.isInitialized) return null
            val mode = context.activeMode
            if (mode != PlayMode.SINGLE_PLAYER_VS_BOT && mode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            val tier = difficultyConfig.entries.firstOrNull { it.value == (s.positions to s.colorCount) }?.key ?: difficulty
            mastermindSnapshotJson.encodeToString(
                MastermindSnapshot.serializer(),
                MastermindSnapshot(
                    players = context.players.map { MastermindSnapshotPlayer(it.playerId, it.isBot) },
                    positions = s.positions,
                    colorCount = s.colorCount,
                    maxGuesses = s.maxGuesses,
                    secret = s.secret,
                    guesses = s.guesses.map { MastermindSnapshotGuess(it.colors, it.blackPegs, it.whitePegs) },
                    solved = s.solved,
                    outOfGuesses = s.outOfGuesses,
                    puzzlesSolved = puzzlesSolved.value,
                    puzzlesFailed = puzzlesFailed.value,
                    roundNumber = roundNumber.value,
                    difficulty = tier.name,
                    dailySeed = activeDailySeed.value,
                    hintsUsed = hintsUsed.value,
                    resultHandled = resultHandled.value,
                    elapsedMillis = activeElapsedMillis(),
                    finishedMillis = finishedElapsedMillis.value
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * All-or-nothing: the snapshot is fully decoded and validated (roster, tier shape, secret,
     * every guess's feedback re-scored against the secret, solved/failed flags, timer fields)
     * before a single field of the engine is touched. The clock continues from the saved ACTIVE
     * elapsed time; the time the app was gone is not counted.
     */
    override fun restore(snapshot: String): Boolean {
        try {
            if (!this::context.isInitialized) return false
            val snap = mastermindSnapshotJson.decodeFromString(MastermindSnapshot.serializer(), snapshot)

            val roster = context.players
            if (snap.players.size != roster.size) return false
            if (!snap.players.indices.all { snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot }) return false

            val tier = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: return false
            if (difficultyConfig[tier] != (snap.positions to snap.colorCount)) return false
            if (snap.maxGuesses != MAX_GUESSES) return false
            if (snap.secret.size != snap.positions || snap.secret.any { it !in 0 until snap.colorCount }) return false
            if (snap.guesses.size > snap.maxGuesses) return false
            for (g in snap.guesses) {
                if (g.colors.size != snap.positions || g.colors.any { it !in 0 until snap.colorCount }) return false
                if (scoreGuess(g.colors, snap.secret) != (g.blackPegs to g.whitePegs)) return false
            }
            if (snap.guesses.dropLast(1).any { it.blackPegs == snap.positions }) return false
            val solved = snap.guesses.lastOrNull()?.let { it.blackPegs == snap.positions } == true
            val outOfGuesses = !solved && snap.guesses.size >= snap.maxGuesses
            if (snap.solved != solved || snap.outOfGuesses != outOfGuesses) return false
            val over = solved || outOfGuesses

            if (snap.puzzlesSolved < 0 || snap.puzzlesFailed < 0 || snap.hintsUsed < 0 || snap.roundNumber < 0) return false
            if (solved && snap.puzzlesSolved < 1) return false
            if (outOfGuesses && snap.puzzlesFailed < 1) return false
            if ((snap.elapsedMillis != null) != snap.guesses.isNotEmpty()) return false
            if (snap.elapsedMillis != null && snap.elapsedMillis < 0L) return false
            if ((snap.finishedMillis != null) != over) return false
            if (snap.finishedMillis != null && snap.finishedMillis < 0L) return false

            // Everything checked: now assign.
            val restored = MastermindState(
                positions = snap.positions,
                colorCount = snap.colorCount,
                maxGuesses = snap.maxGuesses,
                secret = snap.secret,
                guesses = snap.guesses.map { MastermindGuess(it.colors, it.blackPegs, it.whitePegs) },
                solved = solved,
                outOfGuesses = outOfGuesses
            )
            difficulty = tier
            puzzlesSolved.value = snap.puzzlesSolved
            puzzlesFailed.value = snap.puzzlesFailed
            // Monotonic: the screen keys its per-code state on this, so a restore must never hand back a number it already used.
            roundNumber.value = if (roundNumber.value >= snap.roundNumber) roundNumber.value + 1 else snap.roundNumber
            pausedAtElapsedRealtime = null
            totalPausedMillis = 0L
            timerStartElapsedRealtime.value = snap.elapsedMillis?.let { nowMillis() - it }
            finishedElapsedMillis.value = snap.finishedMillis
            hintsUsed.value = snap.hintsUsed
            hintCode.value = null
            activeDailySeed.value = snap.dailySeed
            resultHandled.value = snap.resultHandled
            matchOver.value = false
            state.value = restored
            return true
        } catch (_: Exception) {
            return false
        }
    }

    // --- Hint -------------------------------------------------------------------------------

    /**
     * Suggests a code that is consistent with ALL the feedback so far (so it could be the secret),
     * pre-filled by the screen into the current guess row; it is NOT submitted. Every distinct
     * hint taken counts toward [hintsUsed] (asking again for the same unchanged position is free),
     * which marks the code assisted. Null when the code is already decided or the session ended.
     * It never reveals the secret itself: the suggestion is merely one of the codes still possible.
     */
    fun hint(): List<Int>? {
        val s = state.value ?: return null
        if (matchOver.value || s.isOver) return null
        val code = firstConsistentCode(s) ?: return null
        if (hintCode.value != code) {
            hintsUsed.value += 1
            hintCode.value = code
        }
        return code
    }

    /** The first code (in base-[MastermindState.colorCount] counting order) that would have produced exactly the feedback seen on every guess so far. */
    private fun firstConsistentCode(s: MastermindState): List<Int>? {
        var total = 1
        repeat(s.positions) { total *= s.colorCount }
        for (n in 0 until total) {
            var rest = n
            val candidate = MutableList(s.positions) { 0 }
            for (i in s.positions - 1 downTo 0) {
                candidate[i] = rest % s.colorCount
                rest /= s.colorCount
            }
            if (s.guesses.all { scoreGuess(it.colors, candidate) == (it.blackPegs to it.whitePegs) }) return candidate
        }
        return null
    }

    /** Called by the screen once a code's result has been recorded (or deliberately skipped for an assisted solve). */
    fun markResultHandled() {
        resultHandled.value = true
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
        hintsUsed.value = 0
        hintCode.value = null
        resultHandled.value = false
        activeDailySeed.value = dailySeed
        val random = if (dailySeed != null) Random(dailySeed) else Random
        roundNumber.value += 1
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

    /**
     * The stopwatch reading a live clock should show right now, in the SAME terms
     * [finishedElapsedMillis] is computed in: wall time since the first guess minus every paused
     * interval, including a pause that is still running (so the display freezes while paused
     * instead of ticking on and then snapping back when the round ends). Null before the first
     * guess. Once the round is decided it is just [finishedElapsedMillis]. Read-only; never
     * mutates the timer.
     */
    fun activeElapsedMillis(): Long? {
        val start = timerStartElapsedRealtime.value ?: return null
        finishedElapsedMillis.value?.let { return it }
        val upTo = pausedAtElapsedRealtime ?: nowMillis()
        return (upTo - start - totalPausedMillis).coerceAtLeast(0L)
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

        // A pre-filled hint is consumed by the guess it was for.
        hintCode.value = null

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
            puzzlesFailed.value += 1
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
