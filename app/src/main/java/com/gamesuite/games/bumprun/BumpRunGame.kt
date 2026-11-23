package com.gamesuite.games.bumprun

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
private data class BumpRunSnapshot(
    val length: String,
    val pads: Boolean,
    val autoPlay: Boolean,
    val teams: Boolean,
    val players: List<BumpRunSnapshotPlayer>,
    val sides: List<Int>,
    val pawns: List<Int>,
    val shields: List<Boolean>,
    val doOvers: List<Int>,
    val currentPlayerIndex: Int,
    val phase: String,
    val pile: List<Int>,
    val discard: List<Int>,
    val card: Int? = null,
    val event: String,
    val eventSeat: Int? = null,
    val eventPawn: Int = -1,
    val eventFrom: Int = -1,
    val eventTo: Int = -1,
    val eventVictim: Int = -1,
    val eventReshuffled: Boolean = false,
    val turnSerial: Int = 0,
    val bumpsMade: List<Int> = emptyList(),
    val bumpsTaken: List<Int> = emptyList(),
    val turnsTaken: Int = 0,
    val winnerPlayerIds: List<String> = emptyList(),
    val sessionWins: Map<String, Int>,
    val nextStartingPlayerIndex: Int,
    val difficulty: String,
    val boardSeed: Long = 0
)

@Serializable
private data class BumpRunSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean, val teamId: Int = -1)

private val bumpRunJson = Json { ignoreUnknownKeys = true }

/**
 * Bump and Run: a card-driven race for 2 to 4 seats (any mix of people and computers, all on one device; not
 * networked in the MVP) with an optional 2 v 2 team mode. Draw a card, move a pawn, bump rivals back to their Nest,
 * get every pawn Home by exact count. See docs/design/boardgames/bump-run.md for the complete rules.
 *
 * Options (set before [startMatch]; a restored save carries its own): [length], [pads], [autoPlay], [teams],
 * [helperSeats]. All chance comes from the engine's board seed, drawn once per board from [random]
 * (or [fixedSeed] in tests).
 *
 * Snapshot validation. [snapshotProblems] checks the roster, array sizes and ranges, the phase and event names and
 * that pile + discard + card are exactly the 54 card ids. STILL TO DO in Phase 2 (each needs a test in
 * BumpRunSnapshotTest): no two pawns on one ring square, no two of a seat in one lane square; shields only on ring
 * pawns; AWAIT_CHOICE needs a card in play and AWAIT_DRAW none; the card in play is consistent with the engine's
 * legal moves; teams only with 4 seats and teamId = seat % 2; winnerPlayerIds are exactly the seats (team) whose
 * pawns are all Home iff phase is OVER; doOvers in 0..3; counters non-negative; sides equal BumpRunBoard.sidesFor(n).
 */
class BumpRunGame(
    private val random: Random = Random.Default,
    private val engine: BumpRunEngine = StubBumpRunEngine()
) : GameModule, ResumableGame {
    override val gameId = "bump-run"
    override val displayName = "Bump and Run"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<BumpRunState?>(null)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)

    /** Rules for the NEXT board (the Hub's options). */
    var length: BumpLength = BumpLength.QUICK
    var pads: Boolean = true
    var autoPlay: Boolean = true
    var teams: Boolean = false
    var helperSeats: Set<Int> = emptySet()
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var firstOpenerIndex: Int = 0

    /** Tests only: the board seed to use instead of one drawn from [random]. */
    var fixedSeed: Long? = null

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStartingPlayerIndex = 0
    private var boardSeed = 0L

    override fun init(context: GameContext) {
        require(context.players.size in minPlayers..maxPlayers) { "Bump and Run needs 2 to 4 seats" }
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

    /** True when a person (not the computer) is to act on [s]. */
    fun isMyTurn(s: BumpRunState): Boolean =
        !matchOver.value && !s.over && !s.players[s.currentPlayerIndex].isBot

    /** The legal plays for the card in play (empty before a draw or when there is no move). */
    fun legalMoves(): List<BumpMove> {
        val s = state.value ?: return emptyList()
        return if (s.phase == BumpPhase.AWAIT_CHOICE) engine.legalMoves(s) else emptyList()
    }

    fun explainDraw(): BumpRunReject {
        val s = state.value ?: return BumpRunReject.OVER
        return when {
            matchOver.value || s.over -> BumpRunReject.OVER
            !isMyTurn(s) -> BumpRunReject.NOT_YOUR_TURN
            else -> engine.explainDraw(s)
        }
    }

    fun explainMove(move: BumpMove): BumpRunReject {
        val s = state.value ?: return BumpRunReject.OVER
        return when {
            matchOver.value || s.over -> BumpRunReject.OVER
            !isMyTurn(s) -> BumpRunReject.NOT_YOUR_TURN
            else -> engine.explainMove(s, move)
        }
    }

    fun explainDoOver(): BumpRunReject {
        val s = state.value ?: return BumpRunReject.OVER
        return when {
            matchOver.value || s.over -> BumpRunReject.OVER
            !isMyTurn(s) -> BumpRunReject.NOT_YOUR_TURN
            s.phase != BumpPhase.AWAIT_CHOICE -> BumpRunReject.DRAW_FIRST
            s.doOvers[s.currentPlayerIndex] <= 0 -> BumpRunReject.NO_DO_OVER
            else -> BumpRunReject.NONE
        }
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        val players = context.players.map { BumpRunPlayer(it.playerId, it.displayName, it.isBot, it.teamId) }
        val n = players.size
        val seed = fixedSeed ?: random.nextLong()
        boardSeed = seed
        matchOver.value = false
        val starter = nextStartingPlayerIndex % n
        val rules = BumpRunRules(length, pads, autoPlay, teams && n == 4)
        state.value = engine.newBoard(players, rules, seed, helperSeats.filter { it in 0 until n }.toSet(), starter)
        nextStartingPlayerIndex = (nextStartingPlayerIndex + 1) % n
    }

    override fun pause() {}
    override fun resume() {}

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
        if (explainDraw() != BumpRunReject.NONE) return false
        return commit(engine.draw(state.value ?: return false))
    }

    /** A person plays [move] (one of [legalMoves]). True when accepted. */
    fun playMove(move: BumpMove): Boolean {
        if (explainMove(move) != BumpRunReject.NONE) return false
        return commit(engine.play(state.value ?: return false, move))
    }

    /** A person spends a do-over. True when accepted. */
    fun doOver(): Boolean {
        if (explainDoOver() != BumpRunReject.NONE) return false
        return commit(engine.doOver(state.value ?: return false))
    }

    /** A person with no legal move (and no do-over wanted) passes. True when accepted. */
    fun pass(): Boolean = playMove(BumpMove(-1, BumpMoveKind.PASS))

    private fun commit(after: BumpRunState): Boolean {
        val before = state.value
        state.value = after
        if (before != null && !before.over && after.over) {
            var wins = sessionWins.value
            for (id in after.winnerPlayerIds) wins = wins + (id to (wins[id] ?: 0) + 1)
            sessionWins.value = wins
        }
        return true
    }

    // --- Computer --------------------------------------------------------------------------

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: BumpRunState): Boolean =
        !matchOver.value && !s.over && s.players[s.currentPlayerIndex].isBot

    /**
     * One computer action: draw, or (holding a card) do-over / play the chosen move / pass. With the real engine every
     * call changes the state when [botToMove]; the scaffold engine does not, so do not drive this from a screen yet.
     * The Hard bot's search must run off the main thread (see BumpRunBot and the doc's performance budget).
     */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        if (s.phase == BumpPhase.AWAIT_DRAW) {
            commit(engine.draw(s))
            return
        }
        val rnd = Random(s.turnSerial * 31L + s.currentPlayerIndex)
        val legal = engine.legalMoves(s)
        if (s.doOvers[s.currentPlayerIndex] > 0 && BumpRunBot.wantsDoOver(s, legal, difficulty, rnd)) {
            commit(engine.doOver(s))
            return
        }
        val move = BumpRunBot.choose(s, legal, difficulty, rnd) ?: BumpMove(-1, BumpMoveKind.PASS)
        commit(engine.play(s, move))
    }

    // --- Resume ----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return bumpRunJson.encodeToString(
            BumpRunSnapshot.serializer(),
            BumpRunSnapshot(
                length = s.rules.length.id, pads = s.rules.pads, autoPlay = s.rules.autoPlay, teams = s.rules.teams,
                players = s.players.map { BumpRunSnapshotPlayer(it.playerId, it.displayName, it.isBot, it.teamId) },
                sides = s.sides, pawns = s.pawns, shields = s.shields, doOvers = s.doOvers,
                currentPlayerIndex = s.currentPlayerIndex, phase = s.phase.name, pile = s.pile, discard = s.discard,
                card = s.card, event = s.event, eventSeat = s.eventSeat, eventPawn = s.eventPawn,
                eventFrom = s.eventFrom, eventTo = s.eventTo, eventVictim = s.eventVictim,
                eventReshuffled = s.eventReshuffled, turnSerial = s.turnSerial, bumpsMade = s.bumpsMade,
                bumpsTaken = s.bumpsTaken, turnsTaken = s.turnsTaken, winnerPlayerIds = s.winnerPlayerIds,
                sessionWins = sessionWins.value, nextStartingPlayerIndex = nextStartingPlayerIndex,
                difficulty = difficulty.name, boardSeed = boardSeed
            )
        )
    }

    private fun snapshotProblems(snap: BumpRunSnapshot, roster: List<PlayerInfo>): List<String> {
        val out = ArrayList<String>()
        val n = roster.size
        val length = BumpLength.values().firstOrNull { it.id == snap.length }
        if (length == null) out += "length"
        val per = length?.pawns ?: 0
        if (snap.players.size != n ||
            snap.players.indices.any { snap.players[it].playerId != roster[it].playerId || snap.players[it].isBot != roster[it].isBot }
        ) out += "roster"
        if (snap.sides != BumpRunBoard.sidesFor(n)) out += "sides"
        if (snap.pawns.size != n * per || snap.pawns.any { it !in BumpRunBoard.NEST..BumpRunBoard.HOME }) out += "pawns"
        if (snap.shields.size != n * per) out += "shields"
        if (snap.doOvers.size != n || snap.doOvers.any { it !in 0..3 }) out += "doOvers"
        if (snap.bumpsMade.size != n || snap.bumpsTaken.size != n ||
            snap.bumpsMade.any { it < 0 } || snap.bumpsTaken.any { it < 0 }
        ) out += "stats"
        if (snap.currentPlayerIndex !in 0 until n || snap.nextStartingPlayerIndex !in 0 until n) out += "seat"
        if (snap.eventSeat != null && snap.eventSeat !in 0 until n) out += "eventSeat"
        if (BumpPhase.values().none { it.name == snap.phase }) out += "phase"
        if (snap.event !in BumpRunEvent.ALL) out += "event"
        val cards = snap.pile + snap.discard + listOfNotNull(snap.card)
        if (cards.size != BumpRunDeck.SIZE || cards.toSet() != (0 until BumpRunDeck.SIZE).toSet()) out += "cards"
        if (snap.sessionWins.any { (id, w) -> w < 0 || roster.none { it.playerId == id } }) out += "sessionWins"
        if ((snap.phase == BumpPhase.OVER.name) != snap.winnerPlayerIds.isNotEmpty()) out += "winner"
        if (snap.winnerPlayerIds.any { id -> roster.none { it.playerId == id } }) out += "winnerId"
        if (snap.turnSerial < 0 || snap.turnsTaken < 0) out += "serials"
        return out
    }

    private fun decodeSnapshot(text: String): BumpRunSnapshot? {
        return try {
            val snap = bumpRunJson.decodeFromString(BumpRunSnapshot.serializer(), text)
            if (snapshotProblems(snap, context.players).isEmpty()) snap else null
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
        val snap = decodeSnapshot(snapshot) ?: return false
        matchOver.value = false
        state.value = BumpRunState(
            rules = BumpRunRules(BumpLength.fromId(snap.length), snap.pads, snap.autoPlay, snap.teams),
            players = snap.players.map { BumpRunPlayer(it.playerId, it.displayName, it.isBot, it.teamId) },
            sides = snap.sides, pawns = snap.pawns, shields = snap.shields, doOvers = snap.doOvers,
            currentPlayerIndex = snap.currentPlayerIndex,
            phase = BumpPhase.values().first { it.name == snap.phase },
            pile = snap.pile, discard = snap.discard, card = snap.card,
            event = snap.event, eventSeat = snap.eventSeat, eventPawn = snap.eventPawn,
            eventFrom = snap.eventFrom, eventTo = snap.eventTo, eventVictim = snap.eventVictim,
            eventReshuffled = snap.eventReshuffled, turnSerial = snap.turnSerial, bumpsMade = snap.bumpsMade,
            bumpsTaken = snap.bumpsTaken, turnsTaken = snap.turnsTaken, winnerPlayerIds = snap.winnerPlayerIds
        )
        sessionWins.value = snap.sessionWins
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
        boardSeed = snap.boardSeed
        length = BumpLength.fromId(snap.length)
        pads = snap.pads
        autoPlay = snap.autoPlay
        teams = snap.teams
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        return true
    }
}

/** Helper-pack extra of the Hub: "none" / "" / unknown for nobody, "all", or "1".."4" for that player (1-based). */
fun parseBumpRunHelper(value: String?, seats: Int): Set<Int> {
    val v = value?.trim()?.lowercase() ?: return emptySet()
    if (v.isEmpty() || v == "none") return emptySet()
    if (v == "all") return (0 until seats).toSet()
    return v.split(',').mapNotNull { it.trim().toIntOrNull() }.map { it - 1 }.filter { it in 0 until seats }.toSet()
}
