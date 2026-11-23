package com.gamesuite.games.egggarden

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import kotlin.random.Random

/**
 * Egg Garden: a same-screen tile-placement game for two to four gardeners, competitive or cooperative ("Build one garden
 * together"). All rules live in [EggGardenEngine]; this class is the shell glue (lifecycle, the Compose state the screen
 * reads, the resume snapshot). Same screen only: no Nearby, no Online in this version.
 *
 * Set [config] before [init] / [startMatch] (the screen builds it from the Hub's launch options). The engine is injected so
 * tests drive a fake and the scaffold ships a [StubEggGardenEngine].
 */
class EggGardenGame(
    private val random: Random = Random.Default,
    private val engine: EggGardenEngine = StubEggGardenEngine()
) : GameModule, ResumableGame {
    override val gameId = "egg-garden"
    override val displayName = "Egg Garden"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<EggGardenState?>(null)
    val matchOver = mutableStateOf(false)

    /** Set by the screen from the Hub extras before [init]. The seed is filled in by [init] when it is 0. */
    var config: EggGardenConfig = EggGardenConfig()

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this

    private fun seats(): List<GardenSeat> =
        context.players.mapIndexed { i, p -> GardenSeat(p.playerId, p.displayName, p.isBot, colorIndex = i) }

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            state.value = null
            if (config.seed == 0L) config = config.copy(seed = random.nextLong())
            engine.configure(config, seats())
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    @Synchronized
    override fun startMatch() {
        matchOver.value = false
        engine.start()
        commit()
    }

    @Synchronized
    override fun pause() {}

    @Synchronized
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Draws the next tile for the active gardener. */
    @Synchronized
    fun draw(): EggTile? {
        if (state.value == null || matchOver.value) return null
        val t = engine.draw()
        commit()
        return t
    }

    @Synchronized
    fun keepPeek(index: Int): Boolean {
        if (state.value == null || matchOver.value) return false
        val ok = engine.keepPeek(index)
        commit()
        return ok
    }

    /** The spots the drawn tile can go, for the screen to highlight (and for a one-switch scan). */
    fun legalPlacements(): List<Placement> = engine.legalPlacements()

    fun verdict(placement: Placement): PlaceVerdict = engine.verdict(placement)

    @Synchronized
    fun place(placement: Placement): PlaceVerdict {
        if (state.value == null || matchOver.value) return PlaceVerdict.WRONG_PHASE
        val v = engine.place(placement)
        commit()
        if (engine.state().phase == GardenPhase.MATCH_DONE) finish()
        return v
    }

    @Synchronized
    fun skipUnplaceable(): Boolean {
        if (state.value == null || matchOver.value) return false
        val ok = engine.skipUnplaceable()
        commit()
        if (engine.state().phase == GardenPhase.MATCH_DONE) finish()
        return ok
    }

    fun summary(): EggGardenSummary? = engine.summary()

    /** Ends the session from the leave dialog. */
    fun leaveSession() {
        if (matchOver.value) return
        finish()
    }

    private fun commit() {
        state.value = engine.state()
    }

    private fun finish() {
        if (matchOver.value) return
        val s = engine.summary()
        val together = config.style == GardenStyle.COOP
        val scores = context.players.mapIndexed { i, p ->
            val total = s?.scores?.getOrNull(i)?.total ?: 0
            val winner = if (together) (s?.cleared == true) else (s?.winners?.contains(i) == true)
            PlayerScore(p.playerId, total, isWinner = winner)
        }
        endMatch(GameResult(scores = scores))
    }

    // ---- resume ---------------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) {
        try {
            if (matchOver.value || state.value == null) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            val save = engine.toSave() ?: return null
            EggGardenSaveCodec.encode(save)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val save = EggGardenSaveCodec.decode(snapshot, seats()) ?: return false
        if (!engine.restore(save)) return false
        config = config.copy(
            style = GardenStyle.fromId(save.style), length = GardenLength.fromId(save.length),
            goalBand = GoalBand.fromId(save.goalBand), peekFor = save.peekFor, freePlacing = save.freePlacing, seed = save.seed
        )
        matchOver.value = false
        commit()
        return true
    }
}
