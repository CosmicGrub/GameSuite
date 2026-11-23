package com.gamesuite.games.liferoad

import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Save format of a Life Road board (section 3.6). Private DTOs: the state classes may change without
 * breaking old saves as long as [SNAPSHOT_VERSION] gates the mapping. Enums are stored by name so an old save
 * with a removed name fails closed instead of crashing. SCAFFOLD: written, not compiled or run.
 */

internal const val SNAPSHOT_VERSION = 1

@Serializable
private data class HomeDto(val houseId: String, val paid: Int, val soldFor: Int? = null)

@Serializable
private data class PlayerDto(
    val seat: Int, val playerId: String, val displayName: String, val isBot: Boolean,
    val nodeId: String, val cash: Int, val loans: Int = 0, val jobId: String? = null, val degree: Boolean = false,
    val raises: Int = 0, val homes: List<HomeDto> = emptyList(), val familyPegs: Int = 0,
    val milestonesClaimed: List<String> = emptyList(), val cardMemories: Int = 0, val covers: List<String> = emptyList(),
    val luckyCoins: Int = 0, val nextPay: String = "NORMAL", val freshStarts: Int = 0, val retired: Boolean = false,
    val retireOrder: Int = 0, val lane: String? = null, val turnsTaken: Int = 0, val stepsMoved: Int = 0
)

@Serializable
private data class PendingDto(
    val spin: Int = 0, val stepsLeft: Int = 0, val forkNodeId: String? = null, val offerIds: List<String> = emptyList(),
    val mustPick: Boolean = false, val cardId: String? = null, val cardIsFamily: Boolean = false,
    val needsSpin: Boolean = false, val settleIndex: Int = 0
)

@Serializable
private data class DecksDto(val jobs: List<List<String>>, val events: List<String>, val family: List<String>, val houseStock: List<String>)

@Serializable
private data class LifeRoadSnapshot(
    val v: Int,
    val size: String, val variant: String, val headStart: String, val headStartSeats: List<Int>, val catchUp: Boolean,
    val seed: Long, val difficulty: String,
    val players: List<PlayerDto>, val currentSeat: Int, val phase: String, val pending: PendingDto, val decks: DecksDto,
    val cursorSpin: Long, val cursorDeck: Long, val cursorBot: Long,
    val turnNumber: Int, val retiredCount: Int, val luckyUsedThisTurn: Boolean, val lastSpin: Int, val spinSerial: Int,
    val extraTurn: Boolean,
    val sessionWins: Map<String, Int> = emptyMap(), val nextStartingSeat: Int = 0
)

/** What a save carries besides the board: the session's tally and who opens the next board. */
internal data class SessionExtras(val sessionWins: Map<String, Int>, val nextStartingSeat: Int)

internal object LifeRoadSnapshots {
    private val json = Json { ignoreUnknownKeys = true }

    /** A finished board (phase GAME_OVER) is never saved: returns null. */
    fun encode(state: LifeRoadState, extras: SessionExtras): String? {
        if (state.over || state.phase == Phase.GAME_OVER) return null
        // Transient phases are never saved; the game wrapper only snapshots between actions.
        if (state.phase == Phase.MOVING || state.phase == Phase.TURN_END) return null
        val c = state.config
        val snap = LifeRoadSnapshot(
            v = SNAPSHOT_VERSION, size = c.size.name, variant = c.variant.name, headStart = c.headStart.name,
            headStartSeats = c.headStartSeats.sorted(), catchUp = c.catchUp, seed = c.seed, difficulty = c.difficulty.name,
            players = state.players.map { p ->
                PlayerDto(
                    p.seat, p.playerId, p.displayName, p.isBot, p.nodeId, p.cash, p.loans, p.jobId, p.degree, p.raises,
                    p.homes.map { HomeDto(it.houseId, it.paid, it.soldFor) }, p.familyPegs, p.milestonesClaimed,
                    p.cardMemories, p.covers.map { it.name }.sorted(), p.luckyCoins, p.nextPay.name, p.freshStarts,
                    p.retired, p.retireOrder, p.lane?.name, p.turnsTaken, p.stepsMoved
                )
            },
            currentSeat = state.currentSeat, phase = state.phase.name,
            pending = state.pending.let {
                PendingDto(it.spin, it.stepsLeft, it.forkNodeId, it.offerIds, it.mustPick, it.cardId, it.cardIsFamily, it.needsSpin, it.settleIndex)
            },
            decks = DecksDto(state.decks.jobs, state.decks.events, state.decks.family, state.decks.houseStock),
            cursorSpin = state.cursors.spin, cursorDeck = state.cursors.deck, cursorBot = state.cursors.bot,
            turnNumber = state.turnNumber, retiredCount = state.retiredCount, luckyUsedThisTurn = state.luckyUsedThisTurn,
            lastSpin = state.lastSpin, spinSerial = state.spinSerial, extraTurn = state.extraTurn,
            sessionWins = extras.sessionWins, nextStartingSeat = extras.nextStartingSeat
        )
        return json.encodeToString(LifeRoadSnapshot.serializer(), snap)
    }

    /**
     * Decodes and VALIDATES [text] against the live [roster]. Returns null (fail closed) for anything wrong.
     *
     * Implemented here (cheap structure checks): version, roster match (count, ids, bot flags), enum names parse,
     * seat and phase ranges, non-negative counters, cash >= 0.
     *
     * TODO(P4) before enabling Resume in the Hub (each has a test in LifeRoadSnapshotTest):
     *  - every nodeId exists in `LifeRoadBoard.build(size)` and is reachable from the Start Gate;
     *  - loans <= MAX_LOANS (0 in Junior), homes <= HOUSE_CAP, familyPegs <= FAMILY_CAP, memories <= MEMORY_CAP,
     *    raises <= MAX_RAISES, luckyCoins <= 9, covers empty in Junior;
     *  - every job / house / milestone / card id exists in LifeRoadContent; no home owned twice or also in houseStock;
     *    decks hold no duplicates and jobs sit in their own tier pile;
     *  - phase and pending agree (AWAIT_JOB needs offerIds, AWAIT_CARD needs a known cardId, SETTLING needs
     *    settleIndex <= homes.size, AWAIT_FORK needs a fork node id that is the player's node or the next fork);
     *  - retiredCount equals the number of retired players; retireOrder values are 1..n without gaps or repeats;
     *  - the seat on turn is not retired; turnsTaken <= size cap; cursors >= 0;
     *  - sessionWins keys are roster ids and values >= 0; nextStartingSeat in range.
     */
    fun decode(text: String, roster: List<PlayerInfo>): Pair<LifeRoadState, SessionExtras>? {
        return try {
            val s = json.decodeFromString(LifeRoadSnapshot.serializer(), text)
            if (s.v != SNAPSHOT_VERSION) return null
            val n = roster.size
            if (s.players.size != n) return null
            for (i in 0 until n) {
                val p = s.players[i]
                if (p.playerId != roster[i].playerId || p.isBot != roster[i].isBot || p.seat != i) return null
                if (p.cash < 0 || p.loans < 0 || p.raises < 0 || p.familyPegs < 0 || p.cardMemories < 0 ||
                    p.luckyCoins < 0 || p.freshStarts < 0 || p.turnsTaken < 0 || p.stepsMoved < 0
                ) return null
            }
            if (s.currentSeat !in 0 until n || s.nextStartingSeat !in 0 until n) return null
            val phase = Phase.values().firstOrNull { it.name == s.phase } ?: return null
            if (phase == Phase.MOVING || phase == Phase.TURN_END || phase == Phase.GAME_OVER) return null
            val size = RoadSize.values().firstOrNull { it.name == s.size } ?: return null
            val variant = RulesVariant.values().firstOrNull { it.name == s.variant } ?: return null
            val head = HeadStart.values().firstOrNull { it.name == s.headStart } ?: return null
            val level = CpuDifficulty.values().firstOrNull { it.name == s.difficulty } ?: return null
            if (s.cursorSpin < 0 || s.cursorDeck < 0 || s.cursorBot < 0) return null
            if (s.turnNumber < 0 || s.retiredCount !in 0..n || s.lastSpin !in 0..LifeRoadRules.SPIN_MAX || s.spinSerial < 0) return null
            val players = s.players.map { p ->
                PlayerState(
                    seat = p.seat, playerId = p.playerId, displayName = p.displayName, isBot = p.isBot, nodeId = p.nodeId,
                    cash = p.cash, loans = p.loans, jobId = p.jobId, degree = p.degree, raises = p.raises,
                    homes = p.homes.map { HomeHolding(it.houseId, it.paid, it.soldFor) }, familyPegs = p.familyPegs,
                    milestonesClaimed = p.milestonesClaimed, cardMemories = p.cardMemories,
                    covers = p.covers.map { name -> CoverKind.values().firstOrNull { it.name == name } ?: return null }.toSet(),
                    luckyCoins = p.luckyCoins,
                    nextPay = NextPay.values().firstOrNull { it.name == p.nextPay } ?: return null,
                    freshStarts = p.freshStarts, retired = p.retired, retireOrder = p.retireOrder,
                    lane = p.lane?.let { name -> RetireLane.values().firstOrNull { it.name == name } ?: return null },
                    turnsTaken = p.turnsTaken, stepsMoved = p.stepsMoved
                )
            }
            val state = LifeRoadState(
                config = LifeRoadConfig(size, variant, head, s.headStartSeats.filter { it in 0 until n }.toSet(), s.catchUp, s.seed, level),
                players = players, currentSeat = s.currentSeat, phase = phase,
                pending = s.pending.let {
                    PendingState(it.spin, it.stepsLeft, it.forkNodeId, it.offerIds, it.mustPick, it.cardId, it.cardIsFamily, it.needsSpin, it.settleIndex)
                },
                decks = DeckState(s.decks.jobs, s.decks.events, s.decks.family, s.decks.houseStock),
                cursors = RngCursors(s.cursorSpin, s.cursorDeck, s.cursorBot),
                turnNumber = s.turnNumber, retiredCount = s.retiredCount, luckyUsedThisTurn = s.luckyUsedThisTurn,
                lastSpin = s.lastSpin, spinSerial = s.spinSerial, extraTurn = s.extraTurn
            )
            state to SessionExtras(s.sessionWins, s.nextStartingSeat)
        } catch (_: Exception) {
            null
        }
    }
}
