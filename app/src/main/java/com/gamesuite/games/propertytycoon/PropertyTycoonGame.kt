package com.gamesuite.games.propertytycoon

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.LaunchPolicy
import com.gamesuite.core.PausableGame
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.settings.CpuDifficulty

/**
 * Property Tycoon (`property-tycoon`): a buy-rent-build loop-board game for 2 to 6 seats, any mix of people and
 * computers on ONE device (same-screen first; Nearby / Online come in a later phase, see the design doc).
 * Play money ("pearls") only. Audience ADULTS (see [PropertyTycoonHubSpecs]).
 *
 * This is the house-pattern wrapper (compare SlideClimbGame): it owns the Compose state the screen reads, drives the
 * [engine] and [bot], and saves / restores through [PropertyTycoonSnapshotCodec].
 *
 * Options (set before [startMatch]; a restored save carries its own): [length], [houseRules], [startCashOverride],
 * [handicapSeat] / [handicapPercent], [seed] (null = a fresh random game), [firstSeat] (null = drawn from the seed) and [difficulty].
 *
 * THE DICE come only from [TycoonState.rngState] (see [PtRng]); undo is not offered (a roll is chance).
 */
class PropertyTycoonGame(
    private val engine: PropertyTycoonEngine = StandardPropertyTycoonEngine,
    private val bot: PropertyTycoonBot = StandardPropertyTycoonBot
) : GameModule, ResumableGame, PausableGame {
    override val gameId = "property-tycoon"
    override val displayName = "Property Tycoon"
    override val category = GameCategory.BOARD
    override val minPlayers = PtRules.MIN_SEATS
    override val maxPlayers = PtRules.MAX_SEATS
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<TycoonState?>(null)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)

    /** What the last accepted command did (for hops, sounds and announcements) and a serial that grows with every change. */
    val lastEvents = mutableStateOf<List<PtEvent>>(emptyList())
    val changeSerial = mutableStateOf(0)

    val paused = mutableStateOf(false)
    override val isPaused: Boolean get() = paused.value

    /** Rules for the NEXT game (the Hub's options). */
    var length: GameLength = GameLength.STANDARD
    var houseRules: HouseRules = HouseRules()
    var startCashOverride: Int? = null
    var handicapSeat: Int = -1
    var handicapPercent: Int = 0
    var seed: Long? = null
    var firstSeat: Int? = null
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStartingSeat = 0

    override fun init(context: GameContext) {
        require(context.players.size in minPlayers..maxPlayers) { "Property Tycoon needs 2 to 6 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        nextStartingSeat = 0
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // --- Queries -----------------------------------------------------------------------------

    fun seatToAct(s: TycoonState): Int = engine.seatToAct(s)

    /** True when a person (not the computer) is the seat to act on [s]. */
    fun isHumanToAct(s: TycoonState): Boolean =
        !matchOver.value && !s.over && !s.players[engine.seatToAct(s)].isBot

    fun botToAct(s: TycoonState): Boolean =
        !matchOver.value && !s.over && s.players[engine.seatToAct(s)].isBot

    fun explain(seat: Int, command: PtCommand): PtReject {
        val s = state.value ?: return PtReject.WRONG_PHASE
        return engine.explain(s, seat, command)
    }

    fun legalCommands(seat: Int): List<PtCommand> {
        val s = state.value ?: return emptyList()
        return engine.legalCommands(s, seat)
    }

    fun rentFor(tile: Int, diceTotal: Int): Int = state.value?.let { engine.rentFor(it, tile, diceTotal) } ?: 0

    fun netWorth(seat: Int): Int = state.value?.let { engine.netWorth(it, seat) } ?: 0

    // --- Lifecycle ---------------------------------------------------------------------------

    override fun startMatch() {
        val cash = startCashOverride ?: length.startCash
        val config = TycoonConfig(
            length = length, startCash = cash, seed = seed ?: System.nanoTime(),
            houseRules = houseRules, handicapSeat = handicapSeat, handicapPercent = handicapPercent
        )
        matchOver.value = false
        val fresh = engine.newGame(config, context.players)
        val opener = firstSeat?.takeIf { it in context.players.indices }
        state.value = if (opener != null) fresh.copy(currentSeat = opener) else fresh
        lastEvents.value = emptyList()
        changeSerial.value = changeSerial.value + 1
        nextStartingSeat = (nextStartingSeat + 1) % context.players.size
    }

    override fun pause() { paused.value = true }
    override fun resume() { paused.value = false }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Leaves the session. A finished game is scored; leaving mid-game ends with no result (the caller uses [abortMatch] for that). */
    fun leaveSession() {
        if (matchOver.value) return
        val s = state.value
        if (s != null && s.over) endMatch(buildResult(s)) else endMatch(GameResult(wasAborted = true))
    }

    /** Starts another game of the session (the same table and rules, a fresh seed). */
    fun playAgain() {
        if (matchOver.value) return
        if (!LaunchPolicy.requestNewRound()) return
        seed = null
        firstSeat = nextStartingSeat
        startMatch()
    }

    /** Applies a person's command. True when the engine accepted it. */
    fun command(seat: Int, command: PtCommand): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.over) return false
        val result = engine.apply(s, seat, command)
        if (!result.accepted) return false
        state.value = result.state
        lastEvents.value = result.events
        changeSerial.value = changeSerial.value + 1
        if (result.state.over && !s.over) {
            val winnerId = result.state.players.getOrNull(result.state.winnerSeat)?.playerId
            if (winnerId != null) sessionWins.value = sessionWins.value + (winnerId to ((sessionWins.value[winnerId] ?: 0) + 1))
        }
        return true
    }

    /** One computer action. Every call changes the state while [botToAct], so a computer seat cannot stall the table. */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToAct(s)) return
        val seat = engine.seatToAct(s)
        command(seat, bot.decide(s, seat, difficulty, engine))
    }

    /** Ranked result of the finished game: the winner first, then by net worth. */
    fun buildResult(s: TycoonState): GameResult {
        val winner = engine.winner(s)
        return GameResult(
            scores = s.players.map {
                PlayerScore(it.playerId, engine.netWorth(s, it.seat), isWinner = it.seat == winner)
            }
        )
    }

    // --- Resume ------------------------------------------------------------------------------

    override fun snapshot(): String? {
        return try {
            val s = state.value ?: return null
            if (matchOver.value || s.over) return null
            PropertyTycoonSnapshotCodec.encode(
                PropertyTycoonSnapshot(
                    state = s, sessionWins = sessionWins.value,
                    difficulty = difficulty.name, nextStartingSeat = nextStartingSeat
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        val snap = PropertyTycoonSnapshotCodec.decode(snapshot) ?: return false
        val valid = PropertyTycoonSnapshotCodec.validate(snap, context.players) ?: return false
        matchOver.value = false
        state.value = valid
        lastEvents.value = emptyList()
        sessionWins.value = snap.sessionWins
        nextStartingSeat = snap.nextStartingSeat
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        return true
    }
}
