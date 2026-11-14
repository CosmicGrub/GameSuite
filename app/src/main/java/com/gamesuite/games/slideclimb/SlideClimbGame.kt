package com.gamesuite.games.slideclimb

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** What the last change to the table was; the screen words, animates and announces it. */
object SlideClimbEvent {
    const val START = "start"

    /** A roll is waiting for its player to keep it or spend the spare roll. */
    const val ROLL = "roll"
    const val MOVE = "move"
    const val CLIMB = "climb"
    const val SLIDE = "slide"
    const val BOUNCE = "bounce"
    const val STUCK = "stuck"
    const val WIN = "win"
    val ALL = setOf(START, ROLL, MOVE, CLIMB, SLIDE, BOUNCE, STUCK, WIN)

    /** The events that describe a finished move (they carry a consistent from / landed / to). */
    val MOVES = setOf(MOVE, CLIMB, SLIDE, BOUNCE, STUCK, WIN)

    fun of(step: SlideClimbRules.Step): String = when {
        step.won -> WIN
        step.jump == JumpKind.CLIMB -> CLIMB
        step.jump == JumpKind.SLIDE -> SLIDE
        step.stuck -> STUCK
        step.bounced -> BOUNCE
        else -> MOVE
    }
}

enum class SlideClimbReject {
    NONE, OVER, NOT_YOUR_TURN, DECIDE_FIRST, NOTHING_TO_DECIDE, NO_SPARE_ROLL,

    /** A roll / keep / spare roll was accepted and the screen has not reported it settled yet (see [SlideClimbGame.holdInputUntilSettled]). */
    BUSY
}

/** Seat colour and shape are both `seat` (the screen picks them by index), so identity never rests on hue alone. */
data class SlideClimbPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

/**
 * The whole table. [positions] is one square (0 = start area .. 100) per seat; [rerolls] the spare rolls each
 * seat still holds (the handicap). [dice] is the most recent roll (0 before the first); [pending] is true while
 * the seat on turn has rolled and may still swap it for a spare roll. [rollSerial] / [moveSerial] only ever grow
 * and key the screen's dice and hop animations. [eventFrom] / [eventLanded] / [eventTo] describe the last move:
 * where the piece started, the square it walked to, and where a rope or slide left it.
 */
data class SlideClimbState(
    val layout: SlideClimbLayout,
    val players: List<SlideClimbPlayer>,
    val positions: List<Int>,
    val rerolls: List<Int>,
    val currentPlayerIndex: Int,
    val exactFinish: Boolean = false,
    val dice: Int = 0,
    val pending: Boolean = false,
    val event: String = SlideClimbEvent.START,
    val eventSeat: Int? = null,
    val eventFrom: Int = 0,
    val eventLanded: Int = 0,
    val eventTo: Int = 0,
    val eventRerolled: Boolean = false,
    val rollSerial: Int = 0,
    val moveSerial: Int = 0,
    val over: Boolean = false,
    val winnerPlayerId: String? = null
)

@Serializable
private data class SlideClimbSnapshot(
    val jumps: List<List<Int>>,
    val players: List<SlideClimbSnapshotPlayer>,
    val positions: List<Int>,
    val rerolls: List<Int>,
    val currentPlayerIndex: Int,
    val exactFinish: Boolean,
    val dice: Int,
    val pending: Boolean,
    val event: String,
    val eventSeat: Int? = null,
    val eventFrom: Int = 0,
    val eventLanded: Int = 0,
    val eventTo: Int = 0,
    val eventRerolled: Boolean = false,
    val rollSerial: Int = 0,
    val moveSerial: Int = 0,
    val over: Boolean,
    val winnerPlayerId: String?,
    val sessionWins: Map<String, Int>,
    val nextStartingPlayerIndex: Int,
    val difficulty: String,
    val rollLog: List<Int> = emptyList()
)

@Serializable
private data class SlideClimbSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val slideClimbJson = Json { ignoreUnknownKeys = true }

/**
 * Slide and Climb: a dice race along a snaking 10 x 10 track for 2 to 4 seats, any mix of people and
 * computers, all on one device (it is not networked). Roll, hop that many squares, take a rope up or a
 * slide down if you land on one, first to square 100 wins. There are no extra turns and no choices to make
 * about pieces, so a game is quick and fair for small children.
 *
 * Options (set before [startMatch]; a restored save carries its own):
 *  - [exactFinish]: false (default) overshooting 100 bounces back, true needs the exact roll;
 *  - [handicapSeats]: seats (0-based) that hold one spare roll for the board. After rolling, such a seat may
 *    keep the roll or spend the spare to roll again (once), then must keep the new roll;
 *  - [layoutSeed]: null for [SlideClimbLayout.DEFAULT], otherwise [SlideClimbLayout.generate] of the seed.
 *
 * THE DICE come only from the [random] passed to the constructor, one `nextInt(1, 7)` per roll, and every roll
 * is appended to [rollLog] and saved, so a seeded Random replays a game exactly. Undo is not offered (a roll
 * is chance, so taking it back would only let a player re-roll). The computer's one decision, spending a
 * spare roll, is deterministic for Medium and Hard and uses a coin derived from the state (never [random])
 * for Easy.
 */
class SlideClimbGame(private val random: Random = Random.Default) : GameModule, ResumableGame {
    override val gameId = "chutes-race"
    override val displayName = "Slide and Climb"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<SlideClimbState?>(null)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)

    /** Rules for the NEXT board (the Hub's options). */
    var exactFinish: Boolean = false
    var handicapSeats: Set<Int> = emptySet()
    var layoutSeed: Long? = null
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Computer Arena only (null otherwise): per-seat CPU levels and pausing. */
    var arena: com.gamesuite.core.ArenaHook? = null
    var firstOpenerIndex: Int = 0

    /** True between [pause] and [resume]: the computer takes no step (the screen's bot loop restarts on resume). */
    val paused = mutableStateOf(false)

    /**
     * The screen sets this so a second tap that arrives before it has even recomposed is refused: after a roll,
     * keep or spare roll is accepted the game answers [SlideClimbReject.BUSY] until the screen calls [settled]
     * (its hop animation finished). Off by default, so headless play (tests, the arena) is never held up.
     */
    var holdInputUntilSettled: Boolean = false
    private var inFlight = false

    /** The screen finished animating the last accepted action. */
    fun settled() {
        inFlight = false
    }

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStartingPlayerIndex = 0
    private val rolls = ArrayList<Int>()

    /** Every die roll of the current board, in order. */
    val rollLog: List<Int> get() = rolls.toList()

    override fun init(context: GameContext) {
        require(context.players.size in minPlayers..maxPlayers) { "Slide and Climb needs 2 to 4 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        nextStartingPlayerIndex = if (firstOpenerIndex in context.players.indices) firstOpenerIndex else 0
        rolls.clear()
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // --- Queries ---------------------------------------------------------------------------

    fun isMyTurn(): Boolean = state.value?.let { isMyTurn(it) } ?: false

    /** True when a person (not the computer) is to act on [s]: roll, or keep / spend a spare roll. */
    fun isMyTurn(s: SlideClimbState): Boolean =
        !matchOver.value && !s.over && !s.players[s.currentPlayerIndex].isBot

    fun explainRoll(): SlideClimbReject {
        val s = state.value ?: return SlideClimbReject.OVER
        return when {
            matchOver.value || s.over -> SlideClimbReject.OVER
            holdInputUntilSettled && inFlight -> SlideClimbReject.BUSY
            !isMyTurn(s) -> SlideClimbReject.NOT_YOUR_TURN
            s.pending -> SlideClimbReject.DECIDE_FIRST
            else -> SlideClimbReject.NONE
        }
    }

    fun explainDecision(spare: Boolean): SlideClimbReject {
        val s = state.value ?: return SlideClimbReject.OVER
        return when {
            matchOver.value || s.over -> SlideClimbReject.OVER
            holdInputUntilSettled && inFlight -> SlideClimbReject.BUSY
            !isMyTurn(s) -> SlideClimbReject.NOT_YOUR_TURN
            !s.pending -> SlideClimbReject.NOTHING_TO_DECIDE
            spare && s.rerolls[s.currentPlayerIndex] <= 0 -> SlideClimbReject.NO_SPARE_ROLL
            else -> SlideClimbReject.NONE
        }
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        val layout = layoutSeed?.let { SlideClimbLayout.generate(it) } ?: SlideClimbLayout.DEFAULT
        val players = context.players.map { SlideClimbPlayer(it.playerId, it.displayName, it.isBot) }
        val n = players.size
        matchOver.value = false
        inFlight = false
        rolls.clear()
        val starter = nextStartingPlayerIndex % n
        state.value = SlideClimbState(
            layout = layout, players = players,
            positions = List(n) { SlideClimbBoard.START },
            rerolls = List(n) { if (it in handicapSeats) SlideClimbRules.HANDICAP_REROLLS else 0 },
            currentPlayerIndex = starter, exactFinish = exactFinish
        )
        nextStartingPlayerIndex = (nextStartingPlayerIndex + 1) % n
    }

    override fun pause() {
        paused.value = true
    }

    override fun resume() {
        paused.value = false
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session, recording the boards won so far (a lone leader is the winner). */
    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.value
        val best = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == best }
        endMatch(
            GameResult(
                scores = context.players.map {
                    val w = wins[it.playerId] ?: 0
                    PlayerScore(it.playerId, w, isWinner = best > 0 && w == best && leaders == 1)
                }
            )
        )
    }

    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    // --- Rolling ---------------------------------------------------------------------------

    /** A person's roll. True when accepted. */
    fun roll(): Boolean {
        if (explainRoll() != SlideClimbReject.NONE) return false
        return doRoll()
    }

    /** A person keeps the pending roll and the piece moves. True when accepted. */
    fun keepRoll(): Boolean {
        if (explainDecision(spare = false) != SlideClimbReject.NONE) return false
        return doKeep()
    }

    /** A person spends a spare roll: the pending roll is thrown away and the die is rolled again. True when accepted. */
    fun spareRoll(): Boolean {
        if (explainDecision(spare = true) != SlideClimbReject.NONE) return false
        return doSpare()
    }

    private fun nextSeat(s: SlideClimbState) = (s.currentPlayerIndex + 1) % s.players.size

    private fun doRoll(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.over || s.pending) return false
        return rollFor(s, rerolled = false, rerolls = s.rerolls)
    }

    private fun doKeep(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.over || !s.pending) return false
        inFlight = true
        applyMove(s.copy(pending = false))
        return true
    }

    private fun doSpare(): Boolean {
        val s = state.value ?: return false
        val seat = s.currentPlayerIndex
        if (matchOver.value || s.over || !s.pending || s.rerolls[seat] <= 0) return false
        val left = s.rerolls.mapIndexed { i, r -> if (i == seat) r - 1 else r }
        return rollFor(s.copy(pending = false), rerolled = true, rerolls = left)
    }

    /** One die roll for the seat on turn: a seat with a spare roll left may decide; any other moves at once. */
    private fun rollFor(s: SlideClimbState, rerolled: Boolean, rerolls: List<Int>): Boolean {
        inFlight = true
        val roll = random.nextInt(1, 7)
        rolls += roll
        val seat = s.currentPlayerIndex
        val here = s.positions[seat]
        val rolled = s.copy(
            rerolls = rerolls, dice = roll, rollSerial = s.rollSerial + 1, eventSeat = seat, eventRerolled = rerolled
        )
        if (rerolls[seat] > 0) {
            state.value = rolled.copy(
                pending = true, event = SlideClimbEvent.ROLL, eventFrom = here, eventLanded = here, eventTo = here
            )
        } else {
            applyMove(rolled)
        }
        return true
    }

    private fun applyMove(s: SlideClimbState) {
        val seat = s.currentPlayerIndex
        val step = SlideClimbRules.step(s.positions[seat], s.dice, s.layout, s.exactFinish)
        val won = step.won
        val winner = s.players[seat]
        if (won) sessionWins.value = sessionWins.value + (winner.playerId to (sessionWins.value[winner.playerId] ?: 0) + 1)
        state.value = s.copy(
            positions = s.positions.mapIndexed { i, p -> if (i == seat) step.to else p },
            currentPlayerIndex = if (won) seat else nextSeat(s),
            pending = false,
            event = SlideClimbEvent.of(step), eventSeat = seat,
            eventFrom = step.from, eventLanded = step.landed, eventTo = step.to,
            moveSerial = s.moveSerial + 1,
            over = won, winnerPlayerId = if (won) winner.playerId else null
        )
    }

    // --- Computer --------------------------------------------------------------------------

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: SlideClimbState): Boolean =
        !matchOver.value && !s.over && s.players[s.currentPlayerIndex].isBot

    /**
     * One computer action: roll, or (holding a pending roll) keep it or spend the spare. Every call changes the
     * state when [botToMove], so a computer seat can never stall the table.
     */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s) || paused.value) return
        if (arena?.deferIfPaused { playBotStep() } == true) return
        if (!s.pending) {
            doRoll()
            return
        }
        val seat = s.currentPlayerIndex
        val coin = Random(s.rollSerial * 31L + s.positions[seat] + seat)
        val spend = s.rerolls[seat] > 0 &&
            SlideClimbBot.wantsSpareRoll(s.positions[seat], s.dice, s.layout, s.exactFinish, arena?.levelFor(seat, difficulty) ?: difficulty, coin)
        if (spend) doSpare() else doKeep()
    }

    /** Computer Arena: who opens the NEXT board, then deals it (a fresh board; the session tally stays). */
    fun playAgainOpenedBy(seat: Int) {
        if (matchOver.value) return
        nextStartingPlayerIndex = seat.coerceIn(0, context.players.size - 1)
        startMatch()
    }

    /** Tests only: puts the pieces on [positions] (a board near the finish is otherwise slow to reach). */
    internal fun forcePositions(positions: List<Int>) {
        val s = state.value ?: return
        require(positions.size == s.positions.size && positions.all { it in 0 until SlideClimbBoard.FINISH })
        state.value = s.copy(positions = positions)
    }

    // --- Resume ----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return slideClimbJson.encodeToString(
            SlideClimbSnapshot.serializer(),
            SlideClimbSnapshot(
                jumps = s.layout.jumps.entries.sortedBy { it.key }.map { listOf(it.key, it.value) },
                players = s.players.map { SlideClimbSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                positions = s.positions, rerolls = s.rerolls, currentPlayerIndex = s.currentPlayerIndex,
                exactFinish = s.exactFinish, dice = s.dice, pending = s.pending,
                event = s.event, eventSeat = s.eventSeat, eventFrom = s.eventFrom, eventLanded = s.eventLanded,
                eventTo = s.eventTo, eventRerolled = s.eventRerolled,
                rollSerial = s.rollSerial, moveSerial = s.moveSerial,
                over = s.over, winnerPlayerId = s.winnerPlayerId,
                sessionWins = sessionWins.value, nextStartingPlayerIndex = nextStartingPlayerIndex,
                difficulty = difficulty.name, rollLog = rolls.toList()
            )
        )
    }

    private fun layoutOf(jumps: List<List<Int>>): SlideClimbLayout? {
        if (jumps.any { it.size != 2 }) return null
        val map = LinkedHashMap<Int, Int>()
        for (j in jumps) {
            if (map.put(j[0], j[1]) != null) return null
        }
        return SlideClimbLayout(map)
    }

    private fun decodeSnapshot(text: String): Pair<SlideClimbSnapshot, SlideClimbLayout>? {
        return try {
            val snap = slideClimbJson.decodeFromString(SlideClimbSnapshot.serializer(), text)
            val roster = context.players
            val n = roster.size
            val layout = layoutOf(snap.jumps) ?: return null
            if (SlideClimbLayout.problems(layout).isNotEmpty()) return null
            val seatOk = { x: Int? -> x == null || x in 0 until n }
            val atFinish = snap.positions.indices.filter { snap.positions.getOrNull(it) == SlideClimbBoard.FINISH }
            val winnerSeat = snap.players.indexOfFirst { it.playerId == snap.winnerPlayerId }
            var valid = snap.players.size == n &&
                snap.players.indices.all {
                    snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot
                } &&
                snap.positions.size == n && snap.positions.all { it in SlideClimbBoard.START..SlideClimbBoard.FINISH } &&
                snap.rerolls.size == n && snap.rerolls.all { it in 0..SlideClimbRules.HANDICAP_REROLLS } &&
                snap.currentPlayerIndex in 0 until n && snap.nextStartingPlayerIndex in 0 until n &&
                snap.dice in 0..SlideClimbRules.DIE_FACES &&
                snap.event in SlideClimbEvent.ALL &&
                seatOk(snap.eventSeat) &&
                snap.eventFrom in 0..SlideClimbBoard.FINISH && snap.eventLanded in 0..SlideClimbBoard.FINISH &&
                snap.eventTo in 0..SlideClimbBoard.FINISH &&
                snap.rollSerial in 0..MAX_SERIAL && snap.moveSerial in 0..MAX_SERIAL &&
                snap.sessionWins.all { (id, w) -> w in 0..MAX_SESSION_WINS && roster.any { it.playerId == id } } &&
                snap.rollLog.size <= MAX_ROLL_LOG && snap.rollLog.all { it in 1..SlideClimbRules.DIE_FACES } &&
                (snap.over == (snap.winnerPlayerId != null)) &&
                (if (snap.over) winnerSeat >= 0 && atFinish == listOf(winnerSeat) else atFinish.isEmpty()) &&
                (snap.pending == (snap.event == SlideClimbEvent.ROLL))
            if (valid && snap.pending) {
                val seat = snap.currentPlayerIndex
                valid = !snap.over && snap.dice in 1..SlideClimbRules.DIE_FACES &&
                    snap.rerolls[seat] > 0 && snap.eventSeat == seat &&
                    snap.eventFrom == snap.positions[seat] && snap.eventLanded == snap.positions[seat] &&
                    snap.eventTo == snap.positions[seat]
            }
            if (valid && snap.event in SlideClimbEvent.MOVES) {
                val seat = snap.eventSeat
                valid = seat != null && snap.dice in 1..SlideClimbRules.DIE_FACES &&
                    snap.eventFrom in SlideClimbBoard.START until SlideClimbBoard.FINISH
                if (valid) {
                    val step = SlideClimbRules.step(snap.eventFrom, snap.dice, layout, snap.exactFinish)
                    valid = step.landed == snap.eventLanded && step.to == snap.eventTo &&
                        SlideClimbEvent.of(step) == snap.event && snap.positions[seat!!] == snap.eventTo &&
                        (snap.event == SlideClimbEvent.WIN) == snap.over &&
                        // the turn must follow the move: the next seat, or the winner (who stays on turn)
                        snap.currentPlayerIndex == (if (snap.over) seat else (seat + 1) % n)
                }
            }
            if (valid && snap.event == SlideClimbEvent.START) {
                valid = snap.dice == 0 && snap.positions.all { it == SlideClimbBoard.START } && !snap.over
            }
            if (valid) snap to layout else null
        } catch (_: Exception) {
            null
        }
    }

    override fun snapshot(): String? {
        return try {
            if (matchOver.value || state.value == null) return null
            captureSnapshot()
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        val (snap, layout) = decodeSnapshot(snapshot) ?: return false
        matchOver.value = false
        inFlight = false
        state.value = SlideClimbState(
            layout = layout,
            // names come from the live roster (a renamed seat keeps its new name), ids and bot flags were validated against it
            players = snap.players.mapIndexed { i, p -> SlideClimbPlayer(p.playerId, context.players[i].displayName, p.isBot) },
            positions = snap.positions, rerolls = snap.rerolls, currentPlayerIndex = snap.currentPlayerIndex,
            exactFinish = snap.exactFinish, dice = snap.dice, pending = snap.pending,
            event = snap.event, eventSeat = snap.eventSeat, eventFrom = snap.eventFrom, eventLanded = snap.eventLanded,
            eventTo = snap.eventTo, eventRerolled = snap.eventRerolled,
            rollSerial = snap.rollSerial, moveSerial = snap.moveSerial,
            over = snap.over, winnerPlayerId = snap.winnerPlayerId
        )
        sessionWins.value = snap.sessionWins
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
        rolls.clear(); rolls.addAll(snap.rollLog)
        exactFinish = snap.exactFinish
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        return true
    }

    private companion object {
        const val MAX_ROLL_LOG = 100_000

        /** Far beyond any real board; keeps `serial + 1` from ever overflowing (the screen reads a drop as a reset). */
        const val MAX_SERIAL = 1_000_000
        const val MAX_SESSION_WINS = 1_000_000
    }
}

/** Handicap extra of the Hub: "none" / "" / unknown for nobody, "all", or "1".."4" for that player (1-based). */
fun parseSlideClimbHandicap(value: String?, seats: Int): Set<Int> {
    val v = value?.trim()?.lowercase() ?: return emptySet()
    if (v.isEmpty() || v == "none") return emptySet()
    if (v == "all") return (0 until seats).toSet()
    return v.split(',').mapNotNull { it.trim().toIntOrNull() }.map { it - 1 }.filter { it in 0 until seats }.toSet()
}

/**
 * The spare-roll seats for [seats] seats from the launch extras. The Hub offers the choice under `handicap` for
 * 2 seats and under `handicap3` / `handicap4` for 3 and 4 seats (so it never offers a seat that is not at the
 * table); the plain `handicap` is the fallback for older saved launches. Seats that do not exist are dropped.
 */
fun slideClimbHandicapFromExtras(extras: Map<String, String>, seats: Int): Set<Int> {
    val key = if (seats <= 2) "handicap" else "handicap$seats"
    return parseSlideClimbHandicap(extras[key] ?: extras["handicap"], seats)
}
