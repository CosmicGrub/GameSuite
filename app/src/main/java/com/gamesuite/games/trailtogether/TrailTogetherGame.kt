package com.gamesuite.games.trailtogether

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
 * Trail Together: a cooperative same-screen path game for one to four players (helper pals may fill empty seats).
 * Everyone wins or tries again TOGETHER. All the rules live in a [TrailEngine]; this class is the shell glue: the
 * lifecycle, the Compose state the screen reads, and the resume snapshot. Same screen only: no Nearby, no Online.
 *
 * Set [band], [handicap], [difficulty] before [init] / [startMatch] (the screen does it from the Hub's launch options).
 * The engine is injectable so tests can pass a fake; the default is the real one.
 *
 * [holdInputUntilSettled]: the screen sets it so a second tap that arrives before it has even recomposed is refused
 * ([TrailMoveResult.BUSY]) until it calls [settled] (its animation of the accepted action finished). Off by default,
 * so headless play (tests) is never held up. A helper pal's step is held the same way.
 */
class TrailTogetherGame(
    private val random: Random = Random.Default,
    internal val engine: TrailEngine = RealTrailEngine()
) : GameModule, ResumableGame {
    override val gameId = "trail-together"
    override val displayName = "Trail Together"
    override val category = GameCategory.BOARD
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<TrailState?>(null)
    val matchOver = mutableStateOf(false)

    /** True between [pause] and [resume]: no helper pal plays (the screen's helper loop restarts on resume). */
    val paused = mutableStateOf(false)

    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var band: TrailBand = TrailBand.MEDIUM
    var handicap: String = "none"
    var holdInputUntilSettled: Boolean = false

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this
    private var inFlight = false

    private fun seats(): List<TrailSeat> = context.players.map { TrailSeat(it.playerId, it.displayName, it.isBot) }

    private fun configure() {
        engine.band = band
        engine.handicapSpec = handicap
        engine.difficulty = difficulty
    }

    override fun init(context: GameContext) {
        require(context.players.size in minPlayers..maxPlayers) { "Trail Together needs 1 to 4 seats" }
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            state.value = null
            inFlight = false
            configure()
            engine.setup(seats())
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    /** The screen finished animating the last accepted action. */
    @Synchronized
    fun settled() { inFlight = false }

    @Synchronized
    override fun startMatch() {
        matchOver.value = false
        inFlight = false
        configure()
        engine.start(random.nextLong())
        commit()
    }

    @Synchronized
    override fun pause() {
        engine.pause()
        paused.value = true
    }

    @Synchronized
    override fun resume() {
        engine.resume()
        paused.value = false
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /**
     * Ends the session. Everybody counts as having won with the team only if at least one try was won; leaving before
     * any try finished is an abort (never recorded to stats).
     */
    fun leaveSession() {
        if (matchOver.value) return
        val tries = engine.sessionTries
        if (tries == 0) {
            abortMatch()
            return
        }
        val wins = engine.sessionWins
        endMatch(GameResult(scores = context.players.map { PlayerScore(it.playerId, wins, isWinner = wins > 0) }))
    }

    /** Another try with a fresh seed; the session tally stays. */
    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        inFlight = false
        configure()
        engine.start(random.nextLong())
        commit()
    }

    /** A person played the card at [handIndex] of [seat]. */
    @Synchronized
    fun playCard(seat: Int, handIndex: Int, target: TrailTarget): TrailMoveResult {
        if (state.value == null || matchOver.value) return TrailMoveResult.NOT_ACTIVE
        if (holdInputUntilSettled && inFlight) return TrailMoveResult.BUSY
        val r = engine.playCard(seat, handIndex, target)
        if (r == TrailMoveResult.OK) inFlight = true
        commit()
        return r
    }

    /** A person swapped the card at [handIndex] of [seat]. */
    @Synchronized
    fun swap(seat: Int, handIndex: Int): TrailMoveResult {
        if (state.value == null || matchOver.value) return TrailMoveResult.NOT_ACTIVE
        if (holdInputUntilSettled && inFlight) return TrailMoveResult.BUSY
        val r = engine.swap(seat, handIndex)
        if (r == TrailMoveResult.OK) inFlight = true
        commit()
        return r
    }

    /** The screen calls this after its pause when it is a helper pal's turn. True if a helper played. */
    @Synchronized
    fun helperStep(): Boolean {
        if (state.value == null || matchOver.value || paused.value) return false
        if (holdInputUntilSettled && inFlight) return false
        val played = engine.helperStep()
        if (played) inFlight = true
        commit()
        return played
    }

    /** "Help me choose": the suggested play for the seat whose turn it is. */
    @Synchronized
    fun hint(): TrailHint? = if (state.value == null || matchOver.value) null else engine.hint()

    /** True when a person (not a helper pal) is to act. */
    fun isPersonTurn(s: TrailState, seats: List<TrailSeat>): Boolean =
        !matchOver.value && !s.over && seats.getOrNull(s.currentSeat)?.isBot == false

    private fun commit() {
        state.value = engine.state()
    }

    // ---- resume -----------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) {
        try {
            if (matchOver.value || state.value == null) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            val save = engine.toSave() ?: return null
            TrailSaveCodec.encode(save)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val save = TrailSaveCodec.decode(snapshot, seats()) ?: return false
        // The engine takes band, handicap and difficulty from the save itself; a failed restore leaves everything as it was.
        if (!engine.restore(save)) return false
        band = engine.band
        handicap = engine.handicapSpec
        difficulty = engine.difficulty
        matchOver.value = false
        inFlight = false
        commit()
        return true
    }
}
