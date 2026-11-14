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
    val eventWished: Boolean = false,
    val eventReshuffled: Boolean = false,
    val drawSerial: Int = 0,
    val moveSerial: Int = 0,
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

private val sweetTrailJson = Json { ignoreUnknownKeys = true }

/**
 * Sweet Trail: a colour-card race along a 60-square trail for 2 to 4 seats, any mix of people and computers, all
 * on one device (not networked). Draw a card, the piece moves itself, first to the Party Table wins. Pure luck, so
 * an adult and a three-year-old are on equal terms; the optional wish card (handicap) is a comfort, not a skill.
 *
 * Options (set before [startMatch]; a restored save carries its own): [length], [sticky], [wishSeats].
 * All chance comes from the engine's board seed, drawn once per board from [random] (or [fixedSeed] in tests),
 * so a seeded [random] replays a whole session.
 *
 * Snapshot validation. [snapshotProblems] checks the roster, array sizes and ranges, the event name and that
 * pile + discard + card are exactly the 66 card ids. STILL TO DO in Phase 1 (each needs a test in
 * SweetTrailSnapshotTest): a stuck seat must stand on a puddle square of the saved rules; [pending] iff the event
 * is PENDING and then the seat on turn must hold a wish and a card; a MOVES event must replay (card, eventFrom)
 * to eventTo through the engine; exactly one piece on the Party Table iff [over], and it is the winner's;
 * positions never below the length's start square; shortcutsFound / puddleStops non-negative; turnsTaken
 * and serials within sane bounds; boardSeed unconstrained.
 */
class SweetTrailGame(
    private val random: Random = Random.Default,
    private val engine: SweetTrailEngine = StubSweetTrailEngine()
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

    /** Rules for the NEXT board (the Hub's options). */
    var length: TrailLength = TrailLength.STANDARD
    var sticky: Boolean = true
    var wishSeats: Set<Int> = emptySet()
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var firstOpenerIndex: Int = 0

    /** Tests only: the board seed to use instead of one drawn from [random]. */
    var fixedSeed: Long? = null

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
            !isMyTurn(s) -> SweetTrailReject.NOT_YOUR_TURN
            s.pending -> SweetTrailReject.DECIDE_FIRST
            else -> engine.explainDraw(s)
        }
    }

    fun explainDecision(wish: Boolean): SweetTrailReject {
        val s = state.value ?: return SweetTrailReject.OVER
        return when {
            matchOver.value || s.over -> SweetTrailReject.OVER
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
        val starter = nextStartingPlayerIndex % n
        state.value = engine.newBoard(
            players, SweetTrailRules(length, sticky), seed, wishSeats.filter { it in 0 until n }.toSet(), starter
        )
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
        commit(s, engine.draw(s))
        return true
    }

    private fun applyDecision(wish: Boolean): Boolean {
        val s = state.value ?: return false
        commit(s, engine.decide(s, wish))
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
     * One computer action: draw, or (holding a pending card) keep it or wish. With the real engine every call
     * changes the state when [botToMove]; the scaffold engine does not, so do not drive this from a screen yet.
     */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        if (!s.pending) {
            applyDraw()
            return
        }
        // TODO Phase 1: derive gain / landsInPuddle / wins / takesShortcut from the pending card, then ask SweetTrailBot.
        val seat = s.currentPlayerIndex
        val coin = Random(s.drawSerial * 31L + s.positions[seat] + seat)
        val wish = s.wishes[seat] > 0 &&
            SweetTrailBot.wantsWish(0, landsInPuddle = false, wins = false, takesShortcut = false, difficulty = difficulty, coin = coin)
        applyDecision(wish)
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
                eventTo = s.eventTo, eventWished = s.eventWished, eventReshuffled = s.eventReshuffled,
                drawSerial = s.drawSerial, moveSerial = s.moveSerial, shortcutsFound = s.shortcutsFound,
                puddleStops = s.puddleStops, turnsTaken = s.turnsTaken, over = s.over,
                winnerPlayerId = s.winnerPlayerId, sessionWins = sessionWins.value,
                nextStartingPlayerIndex = nextStartingPlayerIndex, difficulty = difficulty.name,
                boardSeed = boardSeed
            )
        )
    }

    private fun snapshotProblems(snap: SweetTrailSnapshot, roster: List<PlayerInfo>): List<String> {
        val out = ArrayList<String>()
        val n = roster.size
        if (TrailLength.values().none { it.id == snap.length }) out += "length"
        if (snap.players.size != n ||
            snap.players.indices.any { snap.players[it].playerId != roster[it].playerId || snap.players[it].isBot != roster[it].isBot }
        ) out += "roster"
        if (snap.positions.size != n || snap.positions.any { it !in 0..SweetTrailBoard.FINISH }) out += "positions"
        if (snap.stuck.size != n || snap.stuck.any { it !in 0..1 }) out += "stuck"
        if (snap.wishes.size != n || snap.wishes.any { it !in 0..1 }) out += "wishes"
        if (snap.shortcutsFound.size != n || snap.puddleStops.size != n) out += "stats"
        if (snap.currentPlayerIndex !in 0 until n || snap.nextStartingPlayerIndex !in 0 until n) out += "seat"
        if (snap.eventSeat != null && snap.eventSeat !in 0 until n) out += "eventSeat"
        if (snap.event !in SweetTrailEvent.ALL) out += "event"
        val cards = snap.pile + snap.discard + listOfNotNull(snap.card)
        if (cards.size != SweetTrailDeck.SIZE || cards.toSet() != (0 until SweetTrailDeck.SIZE).toSet()) out += "cards"
        if (snap.sessionWins.any { (id, w) -> w < 0 || roster.none { it.playerId == id } }) out += "sessionWins"
        if (snap.over != (snap.winnerPlayerId != null)) out += "winner"
        if (snap.winnerPlayerId != null && roster.none { it.playerId == snap.winnerPlayerId }) out += "winnerId"
        if (snap.drawSerial < 0 || snap.moveSerial < 0 || snap.turnsTaken < 0) out += "serials"
        return out
    }

    private fun decodeSnapshot(text: String): SweetTrailSnapshot? {
        return try {
            val snap = sweetTrailJson.decodeFromString(SweetTrailSnapshot.serializer(), text)
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
        state.value = SweetTrailState(
            rules = SweetTrailRules(TrailLength.fromId(snap.length), snap.sticky),
            players = snap.players.map { SweetTrailPlayer(it.playerId, it.displayName, it.isBot) },
            positions = snap.positions, stuck = snap.stuck, wishes = snap.wishes,
            currentPlayerIndex = snap.currentPlayerIndex, pile = snap.pile, discard = snap.discard, card = snap.card,
            pending = snap.pending, event = snap.event, eventSeat = snap.eventSeat, eventFrom = snap.eventFrom,
            eventTo = snap.eventTo, eventWished = snap.eventWished, eventReshuffled = snap.eventReshuffled,
            drawSerial = snap.drawSerial, moveSerial = snap.moveSerial, shortcutsFound = snap.shortcutsFound,
            puddleStops = snap.puddleStops, turnsTaken = snap.turnsTaken, over = snap.over,
            winnerPlayerId = snap.winnerPlayerId
        )
        sessionWins.value = snap.sessionWins
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
        boardSeed = snap.boardSeed
        length = TrailLength.fromId(snap.length)
        sticky = snap.sticky
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
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
