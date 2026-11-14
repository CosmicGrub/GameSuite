package com.gamesuite.games.territorywars

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.CpuLevel
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** The Hub's choices for one war, parsed from `LaunchOptions.extras` (keys in [TerritoryWarsHubSpecs]). */
data class TerritoryWarsOptions(
    val length: WarLength = WarLength.STANDARD,
    val fortifyReach: FortifyReach = FortifyReach.CONNECTED,
    val autoPlace: Boolean = false,
    val handicap: HandicapPreset = HandicapPreset.NONE
) {
    companion object {
        /** Unknown or missing values fall back to the defaults (a stale saved choice must never crash a launch). */
        fun fromExtras(extras: Map<String, String>): TerritoryWarsOptions = TerritoryWarsOptions(
            length = when (extras[TerritoryWarsHubSpecs.KEY_LENGTH]) {
                "blitz" -> WarLength.BLITZ
                "long" -> WarLength.LONG
                else -> WarLength.STANDARD
            },
            fortifyReach = if (extras[TerritoryWarsHubSpecs.KEY_FORTIFY] == "adjacent") FortifyReach.ADJACENT else FortifyReach.CONNECTED,
            autoPlace = extras[TerritoryWarsHubSpecs.KEY_AUTO_PLACE] == "true",
            handicap = HandicapPreset.fromId(extras[TerritoryWarsHubSpecs.KEY_HANDICAP])
        )
    }
}

/**
 * Snapshot DTOs (JSON string in the saved-game store). Territories are saved by index into the map named by [mapId];
 * cards by id. TODO(restore validation), all required before [TerritoryWarsGame.restore] may return true:
 *  - schemaVersion == 1 (refuse newer; migrate older);
 *  - mapId names a known map; owners/armies sizes == map size; every owner in -1..seats-1; every army >= 0 (and >= 1 once dealt);
 *  - players match the launch roster in count, ids and isBot (same rule as every other game);
 *  - hands + deck + discard together are exactly the deck of that map, each card id once (no duplicated or invented cards);
 *  - phase / pendingMoveIn / armiesToPlace consistent (MOVE_IN needs a pending move-in whose from is owned by currentSeat
 *    and whose to is owned by currentSeat with armies == 0 or just conquered; REINFORCE/PLACE_INITIAL need pool >= 0);
 *  - currentSeat in range and not eliminated unless phase is GAME_OVER; eliminated flags match "owns no territory";
 *  - winnerSeats empty unless GAME_OVER; sessionWins ids are roster ids; diceDrawn >= 0 and <= MAX_DICE_LOG;
 *  - round >= 1 and <= roundCap + 1; setsTurnedIn >= 0; every list bounded (no unbounded growth from a hostile save).
 */
@Serializable
internal data class TerritoryWarsSnapshot(
    val schemaVersion: Int,
    val mapId: String,
    val length: String,
    val fortifyReach: String,
    val autoPlace: Boolean,
    val handicapArmies: List<Int>,
    val winRule: String,
    val captureTarget: Int,
    val roundCap: Int,
    val seed: Long,
    val players: List<TerritoryWarsSnapshotPlayer>,
    val owners: List<Int>,
    val armies: List<Int>,
    val hands: List<List<String>>,
    val deck: List<String>,
    val discard: List<String>,
    val setsTurnedIn: Int,
    val currentSeat: Int,
    val phase: String,
    val armiesToPlace: Int,
    val initialPools: List<Int>,
    val conqueredThisTurn: Boolean,
    val fortifiedThisTurn: Boolean,
    val moveInFrom: Int = -1,
    val moveInTo: Int = -1,
    val moveInMin: Int = 0,
    val moveInMax: Int = 0,
    val lastBattleDice: Int = 0,
    val round: Int,
    val eliminated: List<Boolean>,
    val winnerSeats: List<Int>,
    val winReason: String,
    val roundsWithoutConquest: Int,
    val battles: Int,
    val conquests: Int,
    /** Dice drawn from the seeded stream so far; a restore fast-forwards a fresh stream by this many. */
    val diceDrawn: Int,
    val sessionWins: Map<String, Int>,
    val difficulty: String
)

@Serializable
internal data class TerritoryWarsSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val territoryWarsJson = Json { ignoreUnknownKeys = true }

/**
 * Territory Wars as a [GameModule] (id `territory-wars`, 2 to 6 seats, any mix of people and computers on one device
 * in the MVP). SCAFFOLD: it wires the engine, the seeded dice and the snapshot together, but with the stub engine a
 * started match stays on the undealt blank table. NOT registered anywhere (see the integration notes in the design doc
 * section 4.2).
 *
 * THE DICE come only from the [SeededDice] this class builds from the war seed and hands to [engineFactory]; the deal
 * and deck shuffle come from the same seed through the engine, so a seed replays a whole war.
 *
 * Threading: [state] is Compose state read on the main thread; bots are chosen off the main thread in
 * [playBotStepAsync] and the result is applied back on the caller's thread only if the table has not moved.
 */
class TerritoryWarsGame(
    private val engineFactory: (DiceSource) -> TerritoryWarsEngine = { StubTerritoryWarsEngine(dice = it) },
    private val bot: TerritoryWarsBot = StubTerritoryWarsBot()
) : GameModule, ResumableGame {
    override val gameId = TerritoryWarsHubSpecs.GAME_ID
    override val displayName = "Territory Wars"
    override val category = GameCategory.BOARD
    override val minPlayers = TerritoryWarsRules.MIN_PLAYERS
    override val maxPlayers = TerritoryWarsRules.MAX_PLAYERS
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<TerritoryWarsState?>(null)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)

    /** Rules for the NEXT war (the Hub's options). */
    var options: TerritoryWarsOptions = TerritoryWarsOptions()

    /** Tests and replays: a fixed war seed (null = a fresh random seed per war). */
    var seedOverride: Long? = null

    var cpuLevel: CpuLevel = CpuLevel.MEDIUM
    var firstSeat: Int = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var engine: TerritoryWarsEngine = StubTerritoryWarsEngine()
    private var dice: SeededDice = SeededDice(0L)

    override fun init(context: GameContext) {
        require(context.players.size in minPlayers..maxPlayers) { "Territory Wars needs 2 to 6 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        val seats = context.players.mapIndexed { i, p -> TerritoryWarsPlayer(p.playerId, p.displayName, p.isBot, i) }
        val seed = seedOverride ?: Random.Default.nextLong()
        val handicap = TerritoryWarsRules.handicapArmies(options.handicap, seats.map { it.isBot })
        val config = TerritoryWarsRules.configFor(
            options.length, seats.size, seed, options.fortifyReach, options.autoPlace, handicap
        )
        dice = SeededDice(seed xor DICE_STREAM_SALT)
        engine = engineFactory(dice)
        matchOver.value = false
        state.value = engine.newGame(seats, config)
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session, recording the wars won so far (a lone leader is the winner). */
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

    /** Applies [action] to the current table. A rejected action changes nothing. */
    fun apply(action: TerritoryWarsAction): ActionResult {
        val s = state.value ?: return ActionResult.Rejected(RejectReason.GAME_OVER)
        if (matchOver.value || s.phase == TurnPhase.GAME_OVER) return ActionResult.Rejected(RejectReason.GAME_OVER)
        val result = engine.apply(s, action)
        if (result is ActionResult.Accepted) {
            state.value = result.state
            if (result.state.phase == TurnPhase.GAME_OVER) recordWar(result.state)
        }
        return result
    }

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: TerritoryWarsState): Boolean =
        !matchOver.value && s.phase != TurnPhase.GAME_OVER && s.players[s.currentSeat].isBot

    /**
     * One computer action: chosen OFF the main thread, applied only if the table has not changed meanwhile (undo,
     * leave, a new war). Returns true when an action was applied. With no action to take it returns false and the
     * screen advances the phase itself, so a computer seat can never stall the table.
     */
    suspend fun playBotStepAsync(): Boolean {
        val s = state.value ?: return false
        if (!botToMove(s)) return false
        val budget = ThinkBudgets.forLevel(cpuLevel)
        val rng = Random(s.config.seed * 31L + s.round * 131L + s.eventSerial)
        val action = withContext(Dispatchers.Default) { bot.chooseAction(s, cpuLevel, budget, rng) } ?: return false
        if (state.value !== s || matchOver.value) return false
        return apply(action) is ActionResult.Accepted
    }

    private fun recordWar(s: TerritoryWarsState) {
        val winners = s.winnerSeats
        val winnerIds = winners.mapNotNull { s.players.getOrNull(it)?.playerId }
        sessionWins.value = sessionWins.value.toMutableMap().also { m ->
            winnerIds.forEach { id -> m[id] = (m[id] ?: 0) + 1 }
        }
    }

    /** The result of the finished (or abandoned) war: score = territories held, winners flagged. */
    fun buildResult(): GameResult {
        val s = state.value ?: return GameResult(wasAborted = true)
        return GameResult(
            scores = s.players.map { PlayerScore(it.playerId, s.territoriesOf(it.seat), it.seat in s.winnerSeats) }
        )
    }

    // --- Resume ----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        val move = s.pendingMoveIn
        return territoryWarsJson.encodeToString(
            TerritoryWarsSnapshot.serializer(),
            TerritoryWarsSnapshot(
                schemaVersion = SCHEMA_VERSION,
                mapId = s.mapId,
                length = s.config.length.name,
                fortifyReach = s.config.fortifyReach.name,
                autoPlace = s.config.autoPlace,
                handicapArmies = s.config.handicapArmies,
                winRule = s.config.winRule.name,
                captureTarget = s.config.captureTarget,
                roundCap = s.config.roundCap,
                seed = s.config.seed,
                players = s.players.map { TerritoryWarsSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                owners = s.owners,
                armies = s.armies,
                hands = s.hands.map { hand -> hand.map { it.id } },
                deck = s.deck.map { it.id },
                discard = s.discard.map { it.id },
                setsTurnedIn = s.setsTurnedIn,
                currentSeat = s.currentSeat,
                phase = s.phase.name,
                armiesToPlace = s.armiesToPlace,
                initialPools = s.initialPools,
                conqueredThisTurn = s.conqueredThisTurn,
                fortifiedThisTurn = s.fortifiedThisTurn,
                moveInFrom = move?.from ?: -1,
                moveInTo = move?.to ?: -1,
                moveInMin = move?.minMove ?: 0,
                moveInMax = move?.maxMove ?: 0,
                lastBattleDice = s.lastBattleDice,
                round = s.round,
                eliminated = s.eliminated,
                winnerSeats = s.winnerSeats,
                winReason = s.winReason.name,
                roundsWithoutConquest = s.roundsWithoutConquest,
                battles = s.battles,
                conquests = s.conquests,
                diceDrawn = dice.rolled,
                sessionWins = sessionWins.value,
                difficulty = cpuLevel.name
            )
        )
    }

    /** Null when not started, already over, or when encoding fails (never throws). */
    override fun snapshot(): String? {
        return try {
            if (matchOver.value || state.value == null) return null
            captureSnapshot()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * SCAFFOLD: always false (the screen then starts a fresh war). The real restore decodes [TerritoryWarsSnapshot],
     * validates EVERY item in that class's KDoc, rebuilds the state, fast-forwards the dice by `diceDrawn`, and only
     * then assigns [state]; on any failure it leaves the game untouched and returns false. Never throws.
     */
    override fun restore(snapshot: String): Boolean = false

    companion object {
        const val SCHEMA_VERSION = 1

        /** Keeps the dice stream independent of the deal / deck stream derived from the same war seed. */
        const val DICE_STREAM_SALT: Long = 0x5DEECE66DL
    }
}
