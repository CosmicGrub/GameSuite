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
 * Set [config] before [init] (the screen builds it from the Hub's launch options). The engine is injected so tests can drive
 * their own. The match does not end by itself when the last rink is done: the score card is a screen state, and
 * [finishMatch] (the "All done" button) or [leaveSession] reports the result to the shell.
 */
class FlickRinkGame(
    private val random: Random = Random.Default,
    private val engine: FlickRinkEngine = DefaultFlickRinkEngine()
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

    /** False when the match has no table (the engine refused the config), so the screen can show nothing instead of crashing. */
    var configured: Boolean = false
        private set

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            state.value = null
            if (config.seed == 0L) config = config.copy(seed = random.nextLong())
            val style = if (context.players.size == 1) FlickStyle.SOLO else config.style.takeIf { it != FlickStyle.SOLO } ?: FlickStyle.TURNS
            config = config.copy(style = style, firstSeat = config.firstSeat.coerceIn(0, (context.players.size - 1).coerceAtLeast(0)))
            configured = engine.configure(config, seatsFor(context, config))
        }
    }

    private fun seatsFor(c: GameContext, cfg: FlickConfig): List<FlickSeat> {
        val assisted = FlickRinkRules.assistSeats(cfg.assistFor, c.players.size)
        return c.players.mapIndexed { i, p ->
            FlickSeat(p.playerId, p.displayName, p.isBot, colorIndex = i, assist = if (i in assisted) cfg.assist else AimAssist.OFF)
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    @Synchronized
    override fun startMatch() {
        matchOver.value = false
        if (!configured) return
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
        return out
    }

    @Synchronized
    fun nextRink(): Boolean {
        if (state.value == null || matchOver.value) return false
        val ok = engine.nextRink()
        commit()
        return ok
    }

    @Synchronized
    fun retryRink(): Boolean {
        if (state.value == null || matchOver.value) return false
        val ok = engine.retryRink()
        commit()
        return ok
    }

    /** Another match with the same settings and a fresh seed ("Play again"). */
    @Synchronized
    fun playAgain() {
        if (matchOver.value || !configured) return
        config = config.copy(seed = random.nextLong())
        engine.configure(config, seatsFor(context, config))
        engine.start()
        commit()
    }

    /** The aim helper line for the active seat, written into [out] (no allocation). Returns floats written. */
    fun previewInto(input: FlickInput, maxLength: Int, out: FloatArray): Int = engine.previewInto(input, maxLength, out)

    fun seatAssist(seat: Int): AimAssist = engine.seatAssist(seat)

    fun level(): RinkLevel? = engine.level()

    fun summary(): FlickMatchSummary? = engine.summary()

    /**
     * The computer pal's decision for the active seat. Slow for Hard: call it on a worker thread while it is the bot's turn
     * and nobody is aiming (the engine's scratch simulation is shared with the aim helper). Not synchronized on purpose.
     */
    fun chooseBotMove(bot: FlickRinkBot): FlickInput = bot.choose(engine, config.difficulty, config.seed)

    /** "All done" on the score card: reports the result to the shell. */
    fun finishMatch() {
        if (!::context.isInitialized || matchOver.value) return
        finish()
    }

    /**
     * Ends the session from the leave dialog (or after the score card). With at least one rink played the result is reported
     * (Co-op and Solo count as won together once a rink was cleared); with none it is an abort that records nothing.
     */
    fun leaveSession() {
        if (!::context.isInitialized || matchOver.value) return
        val st = engine.state()
        val played = st.phase == FlickPhase.MATCH_DONE || st.rinkIndex > 0 || st.phase == FlickPhase.RINK_DONE
        if (played) finish() else abortMatch()
    }

    private fun commit() {
        state.value = engine.state()
    }

    private fun finish() {
        if (matchOver.value) return
        val s = engine.summary()
        val together = config.style != FlickStyle.TURNS
        val teamWon = s != null && (s.cleared || s.rinkStars.isNotEmpty())
        val scores = context.players.mapIndexed { i, p ->
            val score = s?.seatScores?.getOrNull(i) ?: 0
            val winner = if (together) teamWon else (s?.winners?.contains(i) == true)
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
        if (!::context.isInitialized || !configured) return false
        val save = FlickSaveCodec.decode(snapshot, seatsFor(context, config)) ?: return false
        if (!engine.restore(save)) return false
        config = engine.config()
        matchOver.value = false
        commit()
        return true
    }
}
