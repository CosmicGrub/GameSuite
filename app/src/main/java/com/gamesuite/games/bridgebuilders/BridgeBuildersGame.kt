package com.gamesuite.games.bridgebuilders

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * Bridge Builders: a cooperative same-screen game for two to four builders (helper robots may fill empty lanes).
 * Everyone wins or tries again TOGETHER. All the rules live in [BridgeEngine]; this class is the shell glue:
 * the lifecycle, the Compose state the screen reads, and the resume snapshot. Same screen only: no Nearby, no Online.
 *
 * Set [band], [relaxed], [handicap], [difficulty] and [pace] before [init] / [startMatch] (the screen does it from
 * the Hub's launch options).
 */
class BridgeBuildersGame(
    random: Random = Random.Default,
    clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "bridge-builders"
    override val displayName = "Bridge Builders"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<BridgeState?>(null)
    val matchOver = mutableStateOf(false)

    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var band: BridgeBand = BridgeBand.MEDIUM
    var relaxed: Boolean = false
    var handicap: String = "none"
    var pace: Double = 1.0

    /** Computer Arena only (null otherwise): the arena pauses through [pause] / [resume]; the hook marks the arena run. */
    var arena: com.gamesuite.core.ArenaHook? = null

    internal val engine = BridgeEngine(random, clock)
    internal val seatLevelOverride: HashMap<Int, CpuDifficulty> get() = engine.seatLevelOverride

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this

    /**
     * The lanes of the table: one per seat, 2 to 4. A lone builder gets one helper robot (the bridge needs two lanes),
     * so a table of one is a clear solo mode instead of a crash. [init] rejects more than 4 seats.
     */
    private fun seats(): List<BridgeSeat> {
        val lanes = context.players.map { BridgeSeat(it.playerId, it.displayName, it.isBot) }
        return if (lanes.size == 1) lanes + BridgeSeat(HELPER_ID, "CPU", true) else lanes
    }

    private fun configure() {
        engine.band = band
        engine.relaxed = relaxed
        engine.handicapSpec = handicap
        engine.difficulty = difficulty
        engine.pace = pace
    }

    override fun init(context: GameContext) {
        require(context.players.size in 1..maxPlayers) {
            "Bridge Builders needs 1 to $maxPlayers seats (a lone builder gets a helper robot), got ${context.players.size}"
        }
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            state.value = null
            configure()
            engine.setup(seats())
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    @Synchronized
    override fun startMatch() {
        matchOver.value = false
        configure()
        engine.start()
        commit()
    }

    @Synchronized
    override fun pause() { engine.pause() }

    @Synchronized
    override fun resume() { engine.resume() }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session. Anyone who built at least one bridge together counts as having won with the team. */
    fun leaveSession() {
        if (!::context.isInitialized || matchOver.value) return
        val built = engine.state().bridgesBuilt
        endMatch(
            GameResult(scores = context.players.map { PlayerScore(it.playerId, built, isWinner = built > 0) })
        )
    }

    /** Another try (a fresh bridge, the session tally stays). */
    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        configure()
        engine.start()
        commit()
    }

    /** The screen's heartbeat, every few milliseconds. */
    @Synchronized
    fun tick(): Boolean {
        if (state.value == null || matchOver.value) return false
        engine.pace = pace
        engine.tick()
        commit()
        return true
    }

    /** A person tapped lane [seat] (a finger, TalkBack's double-tap, or a switch). */
    @Synchronized
    fun tap(seat: Int): BridgeTapResult {
        if (state.value == null || matchOver.value) return BridgeTapResult.NOT_BUILDING
        val r = engine.tap(seat)
        commit()
        return r
    }

    private fun commit() {
        state.value = engine.state()
    }

    // ---- resume -----------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) {
        try {
            if (matchOver.value || state.value == null) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            val save = engine.toSave() ?: return null
            BridgeSaveCodec.encode(save)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun restore(snapshot: String): Boolean {
        // Explicit, not accidental: no table yet, or one of a size this game never plays, cannot take a save.
        if (!::context.isInitialized || context.players.size !in 1..maxPlayers) return false
        val save = BridgeSaveCodec.decode(snapshot, seats()) ?: return false
        configure()
        if (!engine.restore(save)) return false
        band = engine.band
        relaxed = engine.relaxed
        handicap = engine.handicapSpec
        difficulty = engine.difficulty
        matchOver.value = false
        commit()
        return true
    }

    private companion object {
        /** The id of the helper robot that joins a lone builder. */
        const val HELPER_ID = "bridge-helper"
    }
}
