package com.gamesuite.games.sweettrail

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class SweetTrailSnapshot(
    val version: Int = SNAPSHOT_VERSION,
    val length: String,
    val sticky: Boolean,
    val players: List<SweetTrailSnapshotPlayer>,
    val positions: List<Int>,
    val stuck: List<Int>,
    val wishes: List<Int>,
    val currentPlayerIndex: Int,
    val pile: List<Int>,
    val discard: List<Int>,
    val card: Int? = null,
    val pending: Boolean,
    val event: String,
    val eventSeat: Int? = null,
    val eventFrom: Int = 0,
    val eventTo: Int = 0,
    val eventCard: Int? = null,
    val eventWished: Boolean = false,
    val eventReshuffled: Boolean = false,
    val drawSerial: Int = 0,
    val moveSerial: Int = 0,
    val reshuffles: Int = 0,
    val shortcutsFound: List<Int> = emptyList(),
    val puddleStops: List<Int> = emptyList(),
    val turnsTaken: Int = 0,
    val over: Boolean,
    val winnerPlayerId: String?,
    val sessionWins: Map<String, Int>,
    val nextStartingPlayerIndex: Int,
    val difficulty: String,
    val boardSeed: Long = 0
)

@Serializable
private data class SweetTrailSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private const val SNAPSHOT_VERSION = 1

/** A real snapshot is about 1.3 KB; anything this large is not ours and is not even parsed. */
private const val MAX_SNAPSHOT_CHARS = 32_768
private const val MAX_SESSION_WINS = 1_000_000

private val sweetTrailJson = Json { ignoreUnknownKeys = true }

/**
 * Sweet Trail: a colour-card race along a 60-square trail for 2 to 4 seats, any mix of people and computers, all
 * on one device (not networked). Draw a card, the piece moves itself, first to the Party Table wins. Pure luck, so
 * an adult and a three-year-old are on equal terms; the optional wish card (handicap) is a comfort, not a skill.
 *
 * Options (set before [startMatch]; a restored save carries its own): [length], [sticky], [wishSeats].
 * All chance comes from the engine's board seed, drawn once per board from [random] (or [fixedSeed] in tests),
 * so a seeded [random] replays a whole session. The computer's one decision (spend the wish or not) never touches
 * that stream.
 *
 * Snapshot validation. A restore is refused (false, nothing changes) unless every one of these holds: the text is
 * short enough and parses; the version, length id, roster ids and bot flags match; the engine's own
 * [SweetTrailEngine.validate] accepts the rebuilt table (array sizes and ranges, positions never below the
 * length's start square, a stuck seat stands on a puddle of the saved rules, pending iff the PENDING event and
 * then the seat on turn holds a wish and the card, the last move replays through the rules to its eventTo, the
 * turn follows the move, exactly one piece on the Party Table iff over and it is the winner's, the 66 card ids
 * are covered exactly once, serials and counters bounded); the session wins name rostered players and are
 * bounded; the next starter is a seat; the difficulty name is known. Names are taken from the live roster.
 */
class SweetTrailGame(
    private val random: Random = Random.Default,
    private val engine: SweetTrailEngine = StandardSweetTrailEngine()
) : GameModule, ResumableGame {
    override val gameId = "sweet-trail"
    override val displayName = "Sweet Trail"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<SweetTrailState?>(null)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)

    /** True between [pause] and [resume]: the computer takes no step (the screen's bot loop restarts on resume). */
    val paused = mutableStateOf(false)

    /** Rules for the NEXT board (the Hub's options). */
    var length: TrailLength = TrailLength.STANDARD
    var sticky: Boolean = true
    var wishSeats: Set<Int> = emptySet()
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var firstOpenerIndex: Int = 0

    /** Tests only: the board seed to use instead of one drawn from [random]. */
    var fixedSeed: Long? = null

    /**
     * The screen sets this so a second tap that arrives before it has even recomposed is refused: once a draw,
     * keep or wish is accepted the game answers [SweetTrailReject.BUSY] until the screen calls [settled] (its
     * animation finished). Off by default, so headless play (tests) is never held up.
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
    private var boardSeed = 0L

    override fun init(context: GameContext) {
        require(context.players.size in minPlayers..maxPlayers) { "Sweet Trail needs 2 to 4 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        nextStartingPlayerIndex = if (firstOpenerIndex in context.players.indices) firstOpenerIndex else 0
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // --- Queries ---------------------------------------------------------------------------

    fun isMyTurn(): Boolean = state.value?.let { isMyTurn(it) } ?: false

    /** True when a person (not the computer) is to act on [s]: draw, or keep / wish. */
    fun isMyTurn(s: SweetTrailState): Boolean =
        !matchOver.value && !s.over && !s.players[s.currentPlayerIndex].isBot

    fun explainDraw(): SweetTrailReject {
        val s = state.value ?: return SweetTrailReject.OVER
        return when {
            matchOver.value || s.over -> SweetTrailReject.OVER
            holdInputUntilSettled && inFlight -> SweetTrailReject.BUSY
            !isMyTurn(s) -> SweetTrailReject.NOT_YOUR_TURN
            s.pending -> SweetTrailReject.DECIDE_FIRST
            else -> engine.explainDraw(s)
        }
    }

    fun explainDecision(wish: Boolean): SweetTrailReject {
        val s = state.value ?: return SweetTrailReject.OVER
        return when {
            matchOver.value || s.over -> SweetTrailReject.OVER
            holdInputUntilSettled && inFlight -> SweetTrailReject.BUSY
            !isMyTurn(s) -> SweetTrailReject.NOT_YOUR_TURN
            else -> engine.explainDecision(s, wish)
        }
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        val players = context.players.map { SweetTrailPlayer(it.playerId, it.displayName, it.isBot) }
        val n = players.size
        val seed = fixedSeed ?: random.nextLong()
        boardSeed = seed
        matchOver.value = false
        inFlight = false
        val starter = nextStartingPlayerIndex % n
        state.value = engine.newBoard(
            players, SweetTrailRules(length, sticky), seed, wishSeats.filter { it in 0 until n }.toSet(), starter
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

    // --- Playing ---------------------------------------------------------------------------

    /** A person draws. True when accepted. */
    fun draw(): Boolean {
        if (explainDraw() != SweetTrailReject.NONE) return false
        return applyDraw()
    }

    /** A person keeps the pending card and the piece moves. True when accepted. */
    fun keepCard(): Boolean {
        if (explainDecision(wish = false) != SweetTrailReject.NONE) return false
        return applyDecision(wish = false)
    }

    /** A person spends the wish: the pending card is discarded and a new card is drawn. True when accepted. */
    fun useWish(): Boolean {
        if (explainDecision(wish = true) != SweetTrailReject.NONE) return false
        return applyDecision(wish = true)
    }

    private fun applyDraw(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.over || s.pending) return false
        val after = engine.draw(s)
        if (after === s) return false
        inFlight = true // marked synchronously, before any recomposition can offer the next seat a tap
        commit(s, after)
        return true
    }

    private fun applyDecision(wish: Boolean): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.over || !s.pending) return false
        val after = engine.decide(s, wish)
        if (after === s) return false
        inFlight = true
        commit(s, after)
        return true
    }

    private fun commit(before: SweetTrailState, after: SweetTrailState) {
        state.value = after
        val winner = after.winnerPlayerId
        if (!before.over && after.over && winner != null) {
            sessionWins.value = sessionWins.value + (winner to (sessionWins.value[winner] ?: 0) + 1)
        }
    }

    // --- Computer --------------------------------------------------------------------------

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: SweetTrailState): Boolean =
        !matchOver.value && !s.over && s.players[s.currentPlayerIndex].isBot

    /**
     * One computer action: draw, or (holding a pending card) keep it or wish. Every call changes the state when
     * [botToMove], so a computer seat can never stall the table.
     */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s) || paused.value) return
        if (!s.pending) {
            applyDraw()
            return
        }
        applyDecision(SweetTrailBot.decide(s, difficulty))
    }

    // --- Resume ----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return sweetTrailJson.encodeToString(
            SweetTrailSnapshot.serializer(),
            SweetTrailSnapshot(
                length = s.rules.length.id, sticky = s.rules.sticky,
                players = s.players.map { SweetTrailSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                positions = s.positions, stuck = s.stuck, wishes = s.wishes,
                currentPlayerIndex = s.currentPlayerIndex, pile = s.pile, discard = s.discard, card = s.card,
                pending = s.pending, event = s.event, eventSeat = s.eventSeat, eventFrom = s.eventFrom,
                eventTo = s.eventTo, eventCard = s.eventCard, eventWished = s.eventWished,
                eventReshuffled = s.eventReshuffled, drawSerial = s.drawSerial, moveSerial = s.moveSerial,
                reshuffles = s.reshuffles, shortcutsFound = s.shortcutsFound, puddleStops = s.puddleStops,
                turnsTaken = s.turnsTaken, over = s.over, winnerPlayerId = s.winnerPlayerId,
                sessionWins = sessionWins.value, nextStartingPlayerIndex = nextStartingPlayerIndex,
                difficulty = difficulty.name, boardSeed = boardSeed
            )
        )
    }

    private fun stateOf(snap: SweetTrailSnapshot, roster: List<PlayerInfo>): SweetTrailState = SweetTrailState(
        rules = SweetTrailRules(TrailLength.fromId(snap.length), snap.sticky),
        // names come from the live roster (a renamed seat keeps its new name); ids and bot flags were checked against it
        players = snap.players.mapIndexed { i, p -> SweetTrailPlayer(p.playerId, roster.getOrNull(i)?.displayName ?: p.displayName, p.isBot) },
        positions = snap.positions, stuck = snap.stuck, wishes = snap.wishes,
        currentPlayerIndex = snap.currentPlayerIndex, pile = snap.pile, discard = snap.discard, card = snap.card,
        pending = snap.pending, event = snap.event, eventSeat = snap.eventSeat, eventFrom = snap.eventFrom,
        eventTo = snap.eventTo, eventWished = snap.eventWished, eventReshuffled = snap.eventReshuffled,
        drawSerial = snap.drawSerial, moveSerial = snap.moveSerial, shortcutsFound = snap.shortcutsFound,
        puddleStops = snap.puddleStops, turnsTaken = snap.turnsTaken, over = snap.over,
        winnerPlayerId = snap.winnerPlayerId, eventCard = snap.eventCard, boardSeed = snap.boardSeed,
        reshuffles = snap.reshuffles
    )

    private fun snapshotProblems(snap: SweetTrailSnapshot, roster: List<PlayerInfo>): List<String> {
        val out = ArrayList<String>()
        val n = roster.size
        if (snap.version != SNAPSHOT_VERSION) out += "version"
        if (TrailLength.values().none { it.id == snap.length }) out += "length"
        if (snap.players.size != n ||
            snap.players.indices.any { snap.players[it].playerId != roster[it].playerId || snap.players[it].isBot != roster[it].isBot }
        ) out += "roster"
        if (snap.nextStartingPlayerIndex !in 0 until n) out += "nextStartingPlayerIndex"
        if (snap.sessionWins.any { (id, w) -> w !in 0..MAX_SESSION_WINS || roster.none { it.playerId == id } }) out += "sessionWins"
        if (CpuDifficulty.values().none { it.name == snap.difficulty }) out += "difficulty"
        if (out.isNotEmpty()) return out
        return engine.validate(stateOf(snap, roster))
    }

    private fun decodeSnapshot(text: String): SweetTrailSnapshot? {
        if (text.length > MAX_SNAPSHOT_CHARS) return null
        return try {
            val snap = sweetTrailJson.decodeFromString(SweetTrailSnapshot.serializer(), text)
            if (snapshotProblems(snap, context.players).isEmpty()) snap else null
        } catch (_: Exception) {
            null
        }
    }

    /** The reasons a restore of [snapshot] would be refused (empty when it would be accepted); for tests. */
    internal fun snapshotProblemsOf(snapshot: String): List<String> {
        if (snapshot.length > MAX_SNAPSHOT_CHARS) return listOf("size")
        return try {
            snapshotProblems(sweetTrailJson.decodeFromString(SweetTrailSnapshot.serializer(), snapshot), context.players)
        } catch (_: Exception) {
            listOf("parse")
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
        val snap = decodeSnapshot(snapshot) ?: return false
        matchOver.value = false
        inFlight = false
        state.value = stateOf(snap, context.players)
        sessionWins.value = snap.sessionWins
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
        boardSeed = snap.boardSeed
        length = TrailLength.fromId(snap.length)
        sticky = snap.sticky
        difficulty = CpuDifficulty.values().first { it.name == snap.difficulty }
        return true
    }
}

/** Wish extra of the Hub: "none" / "" / unknown for nobody, "all", or "1".."4" for that player (1-based). */
fun parseSweetTrailWish(value: String?, seats: Int): Set<Int> {
    val v = value?.trim()?.lowercase() ?: return emptySet()
    if (v.isEmpty() || v == "none") return emptySet()
    if (v == "all") return (0 until seats).toSet()
    return v.split(',').mapNotNull { it.trim().toIntOrNull() }.map { it - 1 }.filter { it in 0 until seats }.toSet()
}

/**
 * The wish seats for [seats] seats from the launch extras. The Hub offers the choice under `wish` for 2 seats and
 * under `wish3` / `wish4` for 3 and 4 seats (so it never offers a seat that is not at the table); the plain
 * `wish` is the fallback for older saved launches. Seats that do not exist are dropped.
 */
fun sweetTrailWishFromExtras(extras: Map<String, String>, seats: Int): Set<Int> {
    val key = if (seats <= 2) "wish" else "wish$seats"
    return parseSweetTrailWish(extras[key] ?: extras["wish"], seats)
}
