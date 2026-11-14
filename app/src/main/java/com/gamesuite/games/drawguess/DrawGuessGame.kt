package com.gamesuite.games.drawguess

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.random.Random

/** CHOOSE: the drawer picks one of three words (behind the curtain). READY: word picked, drawer holds to see it, then starts. DRAW: clock runs. GUESS: someone buzzed. REVEAL: round result. OVER: final scores. */
enum class DrawPhase { CHOOSE, READY, DRAW, GUESS, REVEAL, OVER }

/** [guesserSeat] is -1 when nobody got it. Points are the round's awards. */
data class DrawRoundResult(
    val wordKey: String,
    val solved: Boolean,
    val timedOut: Boolean,
    val guesserSeat: Int,
    val guesserPoints: Int,
    val drawerPoints: Int
)

/**
 * Everything the screen shows. A "seat" is an index into the roster (computers never take a seat in this game).
 * [wordKey] is the secret: the screen must only show it to the drawer (behind the curtain / hold to peek) until REVEAL.
 */
data class DrawState(
    val phase: DrawPhase,
    val turn: Int,
    val totalTurns: Int,
    val drawerSeat: Int,
    val offered: List<String>,
    val wordKey: String?,
    val options: List<String>,
    val eliminated: List<String>,
    val lockedOut: List<Int>,
    val guesserSeat: Int,
    val scores: List<Int>,
    val used: List<String>,
    val result: DrawRoundResult?
)

/** The launch choices. [timerSeconds] is 0 (no timer), 30, 60 or 90; [rounds] is how many times everyone draws (1..4). */
data class DrawGuessConfig(
    val timerSeconds: Int = 60,
    val typeIt: Boolean = false,
    val rounds: Int = 1,
    val lang: WordLang = WordLang.EN
) {
    val limitMs: Long get() = timerSeconds * 1000L

    companion object {
        val TIMERS = listOf(0, 30, 60, 90)

        /** Reads the Hub's `timer`, `guess` and `rounds` extras; anything unknown falls back to the default. */
        fun fromExtras(extras: Map<String, String>, lang: WordLang): DrawGuessConfig {
            val timer = extras["timer"]?.toIntOrNull()?.takeIf { it in TIMERS } ?: 60
            val rounds = (extras["rounds"]?.toIntOrNull() ?: 1).coerceIn(1, 4)
            return DrawGuessConfig(timer, extras["guess"] == "type", rounds, lang)
        }
    }
}

/** The points. Faster is worth more: up to [MAX_SPEED_BONUS] extra, by the share of the time still left. */
object DrawGuessScoring {
    const val MAX_SPEED_BONUS = 10

    /** The scale for the speed bonus when there is no timer: a guess within the first 90 seconds still earns a bonus. */
    const val NO_TIMER_SCALE_MS = 90_000L

    fun speedBonus(elapsedMs: Long, limitMs: Long): Int {
        val scale = if (limitMs > 0) limitMs else NO_TIMER_SCALE_MS
        val left = (scale - elapsedMs).coerceAtLeast(0L).toDouble() / scale
        return (left * MAX_SPEED_BONUS).roundToInt().coerceIn(0, MAX_SPEED_BONUS)
    }

    fun guesserPoints(difficulty: Int, bonus: Int, typed: Boolean): Int = 10 + bonus + (difficulty - 1) * 2 + if (typed) 3 else 0
    fun drawerPoints(difficulty: Int, bonus: Int): Int = 5 + bonus / 2 + (difficulty - 1)
}

@Serializable
private data class SnapStroke(val c: Int, val s: Int, val e: Boolean, val p: List<Int>)

@Serializable
private data class DrawSnapshot(
    val v: Int,
    val ids: List<String>,
    val bots: List<Boolean>,
    val timer: Int,
    val typeIt: Boolean,
    val rounds: Int,
    val lang: String,
    val turn: Int,
    val phase: String,
    val offered: List<String>,
    val word: String?,
    val options: List<String>,
    val eliminated: List<String>,
    val locked: List<Int>,
    val guesser: Int,
    val scores: List<Int>,
    val used: List<String>,
    val result: SnapResult?,
    val elapsedMs: Long,
    val strokes: List<SnapStroke>
)

@Serializable
private data class SnapResult(val word: String, val solved: Boolean, val timedOut: Boolean, val guesser: Int, val gp: Int, val dp: Int)

private val drawJson = Json { ignoreUnknownKeys = true }

/** A valid save is at most about 60 KB (two budgets of 6000 points); this leaves room and stops a huge string being decoded. */
private const val MAX_SNAPSHOT_CHARS = 200_000

/** How far past the time limit a saved clock may be and still restore (then the turn is treated as timed out). */
internal const val RESTORE_OVERSHOOT_MS = 5_000L

/** Upper bound for one turn's award to one player (the real maximum is 27). */
private const val MAX_TURN_POINTS = 100

/**
 * Draw and Guess: a pass-the-device drawing game for 2 to 8 people (computers never play). Each turn one person
 * draws a secret word (three to choose from, behind a curtain) while the others watch and buzz in; a buzzer gets
 * one go at six picture cards (or typing, in the older-player mode). The clock stops while someone is guessing.
 * A right guess scores for the guesser and the drawer, both more the faster it comes. In team play (an even table of
 * four or more) only the drawer's team-mates may buzz and points add up per team.
 *
 * The engine knows nothing of Compose. [random] and [nowMillis] are injected. The drawing is a [DrawingBoard] of
 * normalised point lists, so a snapshot (see [snapshot]/[restore]) is small, bounded and validated.
 */
class DrawGuessGame(
    private val random: Random = Random.Default,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "draw-guess"
    override val displayName = "Draw and Guess"
    override val category = GameCategory.WORD
    override val minPlayers = 2
    override val maxPlayers = 8
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)

    val state = mutableStateOf<DrawState?>(null)

    /** Bumped when a stroke is finished, undone or cleared, so the canvas knows to refresh its cached paths. */
    val boardVersion = mutableStateOf(0)

    /** True for every game state the player left (Back to menu); the screen stops reacting. */
    val matchOver = mutableStateOf(false)

    /** False while DRAW's clock is stopped by the app being backgrounded or a restore; the screen offers "keep drawing". */
    val clockRunning = mutableStateOf(false)

    val board = DrawingBoard()

    var config: DrawGuessConfig = DrawGuessConfig()

    /** The roster seat that draws first (the Hub's "who goes first"), set by the screen before [init]; anything that does not play is ignored. */
    var firstMover: Int = 0

    /** True when a match is under way (started and not left): a screen that re-runs its set-up must not start another over it. */
    val hasLiveMatch: Boolean get() = state.value != null && !matchOver.value

    /** True once [init] found fewer than two human seats, so no match can ever start (the screen says so instead of waiting). */
    val unplayable = mutableStateOf(false)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** Roster indexes that play, in drawing order (teams alternate in team play). */
    var order: List<Int> = emptyList()
        private set
    var teamMode: Boolean = false
        private set

    private var accumulatedMs = 0L
    private var runStart: Long? = null

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        val seats = context.players.indices.filter { !context.players[it].isBot }
        teamMode = seats.size >= 4 && seats.size % 2 == 0 &&
            seats.all { context.players[it].teamId in 0..1 } &&
            seats.count { context.players[it].teamId == 0 } == seats.size / 2
        order = if (teamMode) {
            val a = seats.filter { context.players[it].teamId == 0 }
            val b = seats.filter { context.players[it].teamId == 1 }
            a.indices.flatMap { listOf(a[it], b[it]) }
        } else seats
        // The Hub's "who goes first": the drawing order starts at that seat (teams keep alternating, the order is a cycle).
        val at = order.indexOf(firstMover)
        if (at > 0) order = order.drop(at) + order.take(at)
        unplayable.value = order.size < 2
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    val playerCount: Int get() = context.players.size
    fun playerName(seat: Int): String = context.players.getOrNull(seat)?.displayName ?: ""
    fun teamOf(seat: Int): Int = if (teamMode) context.players[seat].teamId else -1

    /** Seats that may buzz for [drawerSeat]'s picture: everyone else, or just the drawer's team in team play. */
    fun eligibleGuessers(drawerSeat: Int): List<Int> =
        order.filter { it != drawerSeat && (!teamMode || context.players[it].teamId == context.players[drawerSeat].teamId) }

    /** Seats that can still buzz right now (not the drawer, not locked out). */
    fun activeGuessers(): List<Int> {
        val s = state.value ?: return emptyList()
        return eligibleGuessers(s.drawerSeat).filter { it !in s.lockedOut }
    }

    // ---- match flow ----------------------------------------------------------------------------

    override fun startMatch() {
        if (order.size < 2) return
        matchOver.value = false
        board.clear()
        boardVersion.value++
        stopClock(); accumulatedMs = 0L
        val total = order.size * config.rounds
        state.value = DrawState(
            phase = DrawPhase.CHOOSE, turn = 0, totalTurns = total, drawerSeat = order[0],
            offered = DrawGuessWordBank.choices(random, emptyList()).map { it.key },
            wordKey = null, options = emptyList(), eliminated = emptyList(), lockedOut = emptyList(),
            guesserSeat = -1, scores = List(context.players.size) { 0 }, used = emptyList(), result = null
        )
    }

    /** The drawer picks offered word [index]. Moves to READY. */
    fun chooseWord(index: Int): Boolean {
        val s = state.value ?: return false
        if (s.phase != DrawPhase.CHOOSE || index !in s.offered.indices) return false
        val key = s.offered[index]
        val word = DrawGuessWordBank.byKey(key) ?: return false
        val options = DrawGuessWordBank.multipleChoice(word, random).map { it.key }
        state.value = s.copy(phase = DrawPhase.READY, wordKey = key, options = options, used = s.used + key)
        return true
    }

    /** The drawer has seen the word and starts: a blank canvas and a running clock. */
    fun beginDrawing(): Boolean {
        val s = state.value ?: return false
        if (s.phase != DrawPhase.READY) return false
        board.clear()
        boardVersion.value++
        accumulatedMs = 0L
        startClock()
        state.value = s.copy(phase = DrawPhase.DRAW)
        return true
    }

    // ---- the drawing -----------------------------------------------------------------------------

    private fun canDraw() = state.value?.phase == DrawPhase.DRAW && clockRunning.value

    fun beginStroke(color: Int, size: Int, eraser: Boolean, nx: Float, ny: Float): Boolean =
        canDraw() && board.begin(color, size, eraser, nx, ny)

    fun extendStroke(nx: Float, ny: Float): Boolean = board.extend(nx, ny)

    fun endStroke() { if (board.end()) boardVersion.value++ }

    fun undoStroke(): Boolean = canDraw() && board.undo().also { if (it) boardVersion.value++ }

    fun clearBoard() { if (canDraw() && board.strokes.isNotEmpty()) { board.clear(); boardVersion.value++ } }

    // ---- the clock ---------------------------------------------------------------------------------

    private fun startClock() { if (runStart == null) runStart = nowMillis(); clockRunning.value = true }

    private fun stopClock() {
        runStart?.let { accumulatedMs += (nowMillis() - it).coerceAtLeast(0L) }
        runStart = null
        clockRunning.value = false
    }

    /** Drawing time used so far this turn (stops while guessing or paused). */
    fun elapsedMs(): Long {
        val sum = accumulatedMs + (runStart?.let { (nowMillis() - it).coerceAtLeast(0L) } ?: 0L)
        // The screen ticks every 250 ms, so the clock can overshoot the limit; nothing downstream (saves, bonus) wants that.
        return if (config.limitMs > 0) sum.coerceAtMost(config.limitMs) else sum
    }

    /** Time left on the clock, or null with no timer. */
    fun remainingMs(): Long? = if (config.limitMs > 0) (config.limitMs - elapsedMs()).coerceAtLeast(0L) else null

    /** Call a few times a second: ends the turn when the time is up. Returns true if it did. */
    fun tick(): Boolean {
        val s = state.value ?: return false
        if (s.phase != DrawPhase.DRAW || !clockRunning.value) return false
        val left = remainingMs() ?: return false
        if (left > 0L) return false
        endRound(timedOut = true, guesser = -1, gPts = 0, dPts = 0)
        return true
    }

    override fun pause() {
        if (state.value?.phase != DrawPhase.DRAW) return
        // A stroke under the finger is closed here: the pointer loop is cancelled when drawing stops being interactive.
        if (board.end()) boardVersion.value++
        stopClock()
    }

    override fun resume() { if (state.value?.phase == DrawPhase.DRAW && !clockRunning.value) startClock() }

    // ---- guessing ------------------------------------------------------------------------------------

    /** [seat] buzzes in ("I know it"): the drawing stops and the clock pauses. */
    fun buzz(seat: Int): Boolean {
        val s = state.value ?: return false
        if (s.phase != DrawPhase.DRAW || !clockRunning.value) return false
        if (seat !in activeGuessers()) return false
        board.end().also { if (it) boardVersion.value++ }
        stopClock()
        state.value = s.copy(phase = DrawPhase.GUESS, guesserSeat = seat)
        return true
    }

    /** The buzzer changed their mind: back to drawing, no penalty. */
    fun cancelGuess(): Boolean {
        val s = state.value ?: return false
        if (s.phase != DrawPhase.GUESS) return false
        state.value = s.copy(phase = DrawPhase.DRAW, guesserSeat = -1)
        startClock()
        return true
    }

    /** A card tapped. True when it was the answer. */
    fun guessCard(key: String): Boolean {
        val s = state.value ?: return false
        if (s.phase != DrawPhase.GUESS || config.typeIt || key !in s.options || key in s.eliminated) return false
        return resolve(s, key == s.wordKey, typed = false, wrongKey = key)
    }

    /** Typed text. True when it names the word (either language, accents and case ignored). */
    fun guessTyped(text: String): Boolean {
        val s = state.value ?: return false
        if (s.phase != DrawPhase.GUESS) return false
        val word = DrawGuessWordBank.byKey(s.wordKey ?: return false) ?: return false
        // Nothing real was typed (empty, or only punctuation): not a guess, so it never costs the guesser their go.
        if (DrawGuessWordBank.normalize(text).length < 2) return false
        return resolve(s, DrawGuessWordBank.matches(word, text), typed = true, wrongKey = null)
    }

    private fun resolve(s: DrawState, correct: Boolean, typed: Boolean, wrongKey: String?): Boolean {
        val word = DrawGuessWordBank.byKey(s.wordKey ?: return false) ?: return false
        if (correct) {
            val bonus = DrawGuessScoring.speedBonus(elapsedMs(), config.limitMs)
            val gp = DrawGuessScoring.guesserPoints(word.difficulty, bonus, typed)
            val dp = DrawGuessScoring.drawerPoints(word.difficulty, bonus)
            endRound(timedOut = false, guesser = s.guesserSeat, gPts = gp, dPts = dp)
            return true
        }
        val locked = s.lockedOut + s.guesserSeat
        val eliminated = if (wrongKey != null) s.eliminated + wrongKey else s.eliminated
        val next = s.copy(phase = DrawPhase.DRAW, guesserSeat = -1, lockedOut = locked, eliminated = eliminated)
        state.value = next
        if (eligibleGuessers(s.drawerSeat).none { it !in locked }) {
            endRound(timedOut = false, guesser = -1, gPts = 0, dPts = 0)
        } else startClock()
        return false
    }

    /** The drawer ends the turn: nobody got it. */
    fun giveUp(): Boolean {
        val s = state.value ?: return false
        if (s.phase != DrawPhase.DRAW && s.phase != DrawPhase.GUESS) return false
        endRound(timedOut = false, guesser = -1, gPts = 0, dPts = 0)
        return true
    }

    private fun endRound(timedOut: Boolean, guesser: Int, gPts: Int, dPts: Int) {
        val s = state.value ?: return
        board.end().also { if (it) boardVersion.value++ }
        stopClock()
        val scores = s.scores.toMutableList()
        if (guesser >= 0) {
            scores[guesser] += gPts
            scores[s.drawerSeat] += dPts
        }
        state.value = s.copy(
            phase = DrawPhase.REVEAL, guesserSeat = -1, scores = scores,
            result = DrawRoundResult(s.wordKey ?: "", guesser >= 0, timedOut, guesser, gPts, dPts)
        )
    }

    /** From REVEAL: the next drawer's turn, or the final scores after the last one. */
    fun nextTurn(): Boolean {
        val s = state.value ?: return false
        if (s.phase != DrawPhase.REVEAL) return false
        val turn = s.turn + 1
        board.clear()
        boardVersion.value++
        accumulatedMs = 0L
        state.value = if (turn >= s.totalTurns) {
            s.copy(phase = DrawPhase.OVER, turn = turn - 1, offered = emptyList(), options = emptyList(), eliminated = emptyList(), lockedOut = emptyList(), result = null, wordKey = null)
        } else {
            s.copy(
                phase = DrawPhase.CHOOSE, turn = turn, drawerSeat = order[turn % order.size],
                offered = DrawGuessWordBank.choices(random, s.used).map { it.key },
                wordKey = null, options = emptyList(), eliminated = emptyList(), lockedOut = emptyList(), guesserSeat = -1, result = null
            )
        }
        return true
    }

    fun playAgain() { if (!matchOver.value && com.gamesuite.core.LaunchPolicy.requestNewRound()) startMatch() }

    // ---- results -----------------------------------------------------------------------------------

    /** Team totals (index = team id) in team play; empty otherwise. */
    fun teamScores(): List<Int> {
        val s = state.value ?: return emptyList()
        if (!teamMode) return emptyList()
        return (0..1).map { t -> order.filter { context.players[it].teamId == t }.sumOf { s.scores[it] } }
    }

    /** Playing seats best first (a team's members rank by their team's total in team play). Ties keep roster order. */
    fun standings(): List<Int> {
        val s = state.value ?: return emptyList()
        val team = teamScores()
        return order.sortedByDescending { if (teamMode) team[context.players[it].teamId] * 10_000 + s.scores[it] else s.scores[it] }
    }

    /** The seats that won: the top scorer(s), or every member of the top team (empty on a tied team total). */
    fun winners(): List<Int> {
        val s = state.value ?: return emptyList()
        if (teamMode) {
            val t = teamScores()
            if (t[0] == t[1]) return emptyList()
            val top = if (t[0] > t[1]) 0 else 1
            return order.filter { context.players[it].teamId == top }
        }
        val best = order.maxOf { s.scores[it] }
        return order.filter { s.scores[it] == best }
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Back to menu from the final scores: reports the result. */
    fun leaveSession() {
        if (matchOver.value) return
        val s = state.value
        val won = winners().toSet()
        val scores = if (s == null) emptyList() else order.map { PlayerScore(context.players[it].playerId, s.scores[it], it in won) }
        endMatch(GameResult(scores = scores))
    }

    // ---- save and restore ------------------------------------------------------------------------------

    override fun snapshot(): String? = try {
        val s = state.value
        if (matchOver.value || s == null || s.phase == DrawPhase.OVER) null
        else drawJson.encodeToString(
            DrawSnapshot.serializer(),
            DrawSnapshot(
                v = 1, ids = context.players.map { it.playerId }, bots = context.players.map { it.isBot },
                timer = config.timerSeconds, typeIt = config.typeIt, rounds = config.rounds, lang = config.lang.name,
                turn = s.turn, phase = s.phase.name, offered = s.offered, word = s.wordKey, options = s.options,
                eliminated = s.eliminated, locked = s.lockedOut, guesser = s.guesserSeat, scores = s.scores, used = s.used,
                result = s.result?.let { SnapResult(it.wordKey, it.solved, it.timedOut, it.guesserSeat, it.guesserPoints, it.drawerPoints) },
                elapsedMs = elapsedMs(),
                strokes = board.strokes.map { SnapStroke(it.color, it.size, it.eraser, it.xy.toList()) }
            )
        )
    } catch (_: Exception) {
        null
    }

    /** All-or-nothing: every field is checked before anything is applied. False leaves the engine untouched. */
    override fun restore(snapshot: String): Boolean {
        try {
            if (snapshot.length > MAX_SNAPSHOT_CHARS) return false
            val snap = drawJson.decodeFromString(DrawSnapshot.serializer(), snapshot)
            if (snap.v != 1) return false
            val roster = context.players
            if (snap.ids.size != roster.size || snap.bots.size != roster.size) return false
            for (i in roster.indices) if (snap.ids[i] != roster[i].playerId || snap.bots[i] != roster[i].isBot) return false
            if (snap.timer !in DrawGuessConfig.TIMERS || snap.rounds !in 1..4) return false
            val lang = WordLang.values().firstOrNull { it.name == snap.lang } ?: return false
            val cfg = DrawGuessConfig(snap.timer, snap.typeIt, snap.rounds, lang)
            val total = order.size * cfg.rounds
            if (order.size < 2 || snap.turn !in 0 until total) return false
            val phase = DrawPhase.values().firstOrNull { it.name == snap.phase } ?: return false
            if (phase == DrawPhase.OVER) return false
            val drawer = order[snap.turn % order.size]

            fun known(k: String) = DrawGuessWordBank.byKey(k) != null
            // One word is used per turn started: `turn` before the pick (CHOOSE), `turn + 1` from the pick on.
            val expectedUsed = if (phase == DrawPhase.CHOOSE) snap.turn else snap.turn + 1
            if (!snap.used.all { known(it) } || snap.used.toSet().size != snap.used.size || snap.used.size != expectedUsed) return false
            if (!snap.offered.all { known(it) } || snap.offered.toSet().size != snap.offered.size) return false
            if (phase == DrawPhase.CHOOSE && snap.offered.any { it in snap.used }) return false
            if (snap.scores.size != roster.size || snap.scores.any { it < 0 || it > 100_000 }) return false
            if (snap.scores.indices.any { it !in order && snap.scores[it] != 0 }) return false

            val needsWord = phase != DrawPhase.CHOOSE
            if (needsWord) {
                val w = snap.word ?: return false
                if (!known(w) || snap.offered.isNotEmpty() && w !in snap.offered) return false
                if (snap.options.size != 6 || snap.options.toSet().size != 6 || w !in snap.options || !snap.options.all { known(it) }) return false
                if (w !in snap.used) return false
            } else {
                if (snap.word != null || snap.offered.size != 3 || snap.options.isNotEmpty()) return false
            }
            if (!snap.eliminated.all { it in snap.options && it != snap.word }) return false
            if (snap.eliminated.toSet().size != snap.eliminated.size || snap.eliminated.size > snap.locked.size) return false
            val eligible = eligibleGuessers(drawer)
            if (!snap.locked.all { it in eligible } || snap.locked.toSet().size != snap.locked.size) return false
            // Every eligible guesser locked out cannot happen in play (the round would have ended).
            if (phase == DrawPhase.DRAW && eligible.all { it in snap.locked }) return false
            if (phase == DrawPhase.GUESS) {
                if (snap.guesser !in eligible || snap.guesser in snap.locked) return false
            } else if (snap.guesser != -1) return false
            if (phase == DrawPhase.REVEAL) {
                val r = snap.result ?: return false
                if (r.word != snap.word || r.guesser !in -1..roster.lastIndex || r.gp < 0 || r.dp < 0) return false
                if (r.solved != (r.guesser >= 0) || (r.guesser >= 0 && r.guesser !in eligible)) return false
                if (r.timedOut && r.solved) return false
                if (r.gp > MAX_TURN_POINTS || r.dp > MAX_TURN_POINTS) return false
            } else if (snap.result != null) return false
            if (snap.elapsedMs < 0L || snap.elapsedMs > 24L * 3_600_000L) return false
            // The clock overshoots the limit by up to a tick; older saves hold that overshoot. Anything much larger is corrupt.
            if (cfg.limitMs > 0 && snap.elapsedMs > cfg.limitMs + RESTORE_OVERSHOOT_MS) return false

            val strokes = snap.strokes.map { DrawStroke(it.c, it.s, it.e, it.p.toIntArray()) }
            if (!DrawingBoard.validate(strokes)) return false
            if (strokes.isNotEmpty() && (phase == DrawPhase.CHOOSE || phase == DrawPhase.READY)) return false

            // ---- apply ----
            config = cfg
            board.load(strokes)
            boardVersion.value++
            stopClock()
            accumulatedMs = if (cfg.limitMs > 0) snap.elapsedMs.coerceAtMost(cfg.limitMs) else snap.elapsedMs
            matchOver.value = false
            state.value = DrawState(
                phase = phase, turn = snap.turn, totalTurns = total, drawerSeat = drawer, offered = snap.offered,
                wordKey = snap.word, options = snap.options, eliminated = snap.eliminated, lockedOut = snap.locked,
                guesserSeat = snap.guesser, scores = snap.scores, used = snap.used,
                result = snap.result?.let { DrawRoundResult(it.word, it.solved, it.timedOut, it.guesser, it.gp, it.dp) }
            )
            // DRAW resumes only when the player says so (see [clockRunning]); the clock stays stopped until [resume].
            // A turn saved at or past its limit was already out of time: finish it now instead of offering more drawing.
            if (phase == DrawPhase.DRAW && cfg.limitMs > 0 && snap.elapsedMs >= cfg.limitMs) {
                endRound(timedOut = true, guesser = -1, gPts = 0, dPts = 0)
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }
}
