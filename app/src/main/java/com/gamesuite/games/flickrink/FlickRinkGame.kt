package com.gamesuite.games.flickrink

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
 * Flick Rink: a same-screen flicking game for one to four (turns, co-op relay, solo practice). All rules live in
 * [FlickRinkEngine]; this class is the shell glue (lifecycle, the Compose state the screen reads, the resume snapshot).
 * Same screen only: no Nearby, no Online in this version.
 *
 * Set [config] before [init] / [startMatch] (the screen builds it from the Hub's launch options). The engine is injected
 * so tests drive a fake and the scaffold ships a [StubFlickRinkEngine].
 */
class FlickRinkGame(
    private val random: Random = Random.Default,
    private val engine: FlickRinkEngine = StubFlickRinkEngine()
) : GameModule, ResumableGame {
    override val gameId = "flick-rink"
    override val displayName = "Flick Rink"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<FlickRinkState?>(null)
    val matchOver = mutableStateOf(false)

    /** Set by the screen from the Hub extras before [init]. The seed is filled in by [init] when it is 0. */
    var config: FlickConfig = FlickConfig()

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this

    private fun seats(): List<FlickSeat> {
        val assisted = FlickRinkRules.assistSeats(config.assistFor, context.players.size)
        return context.players.mapIndexed { i, p ->
            FlickSeat(p.playerId, p.displayName, p.isBot, colorIndex = i, assist = if (i in assisted) config.assist else AimAssist.OFF)
        }
    }

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

    /** A person (or the bot) flicks. Returns the outcome so the screen can play it back. */
    @Synchronized
    fun flick(input: FlickInput, record: Boolean = true): FlickOutcome {
        if (state.value == null || matchOver.value) return FlickOutcome.NONE
        val out = engine.flick(input, record)
        commit()
        if (engine.state().phase == FlickPhase.MATCH_DONE) finish()
        return out
    }

    @Synchronized
    fun nextRink(): Boolean {
        if (state.value == null || matchOver.value) return false
        val ok = engine.nextRink()
        commit()
        if (engine.state().phase == FlickPhase.MATCH_DONE) finish()
        return ok
    }

    @Synchronized
    fun retryRink(): Boolean {
        if (state.value == null || matchOver.value) return false
        val ok = engine.retryRink()
        commit()
        return ok
    }

    /** The aim helper line for the active seat, for the screen to draw while the player drags. */
    fun previewLine(input: FlickInput, maxLength: Int): FloatArray = engine.previewLine(input, maxLength)

    fun summary(): FlickMatchSummary? = engine.summary()

    /** Ends the session from the leave dialog. In co-op everyone counts as having won together once a rink was cleared. */
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
        val together = config.style != FlickStyle.TURNS
        val scores = context.players.mapIndexed { i, p ->
            val score = s?.seatScores?.getOrNull(i) ?: 0
            val winner = if (together) (s?.cleared == true) else (s?.winners?.contains(i) == true)
            PlayerScore(p.playerId, score, isWinner = winner)
        }
        endMatch(GameResult(scores = scores))
    }

    // ---- resume ---------------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) {
        try {
            if (matchOver.value || state.value == null) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            val save = engine.toSave() ?: return null
            FlickSaveCodec.encode(save)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val save = FlickSaveCodec.decode(snapshot, seats()) ?: return false
        if (!engine.restore(save)) return false
        config = config.copy(
            style = FlickStyle.fromId(save.style), kind = FlickGameKind.fromId(save.kind), band = FlickBand.fromId(save.band),
            length = FlickLength.fromId(save.length), relaxed = save.relaxed, seed = save.seed
        )
        matchOver.value = false
        commit()
        return true
    }
}
