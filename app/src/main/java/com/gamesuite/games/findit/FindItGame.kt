package com.gamesuite.games.findit

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.json.Json

/**
 * Find It Frenzy (id `find-it`): spot the picture two cards share (Match Race, Solo Dash) or find every picture on a
 * list (Group Hunt, cooperative). Same screen only in this version: no Nearby, no Online (see the SCALE section of
 * docs/design/kids-games/find-it.md for the host-authoritative Nearby plan).
 *
 * All rules live in [FindItEngine]; this class is the shell glue: lifecycle, Compose state, result and resume.
 * Set the option vars before [init] (the screen does it from the Hub launch extras).
 */
class FindItGame(
    internal val engine: FindItEngine = StubFindItEngine()
) : GameModule, ResumableGame {
    override val gameId = "find-it"
    override val displayName = "Find It Frenzy"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf(FindItState.EMPTY)
    val matchOver = mutableStateOf(false)

    var mode: FindItMode = FindItMode.MATCH_RACE
    var cardSize: FindItCardSize = FindItCardSize.LITTLE
    var length: FindItLength = FindItLength.STANDARD
    var relaxed: Boolean = false
    var handicap: String = "none"
    var huntHint: String = "slow"
    var oneSwitch: Boolean = false
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** 0 draws a fresh seed at [init]; a test or a replay sets it. The clock is read HERE only, never in the engine. */
    var seed: Long = 0L

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this

    private fun seats(): List<FindItSeat> = context.players.map { FindItSeat(it.playerId, it.displayName, it.isBot) }

    private fun drawSeed(): Long = if (seed != 0L) seed else (System.nanoTime() or 1L)

    private fun config(seed: Long) = FindItConfig(
        mode = mode, cardSize = cardSize, length = length, relaxed = relaxed, handicap = handicap,
        huntHint = huntHint, oneSwitch = oneSwitch, difficulty = difficulty, seed = seed
    )

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            state.value = FindItState.EMPTY
            engine.setup(config(drawSeed()), seats())
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
    override fun pause() { engine.pause(); commit() }

    @Synchronized
    override fun resume() { engine.resume(); commit() }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** The "Ready?" tap (or the end of a 3-2-1): the clock starts. */
    @Synchronized
    fun go() {
        if (matchOver.value) return
        engine.go()
        commit()
    }

    /** The screen's heartbeat; [dtMs] is real elapsed time since the last call. */
    @Synchronized
    fun tick(dtMs: Long): Boolean {
        if (matchOver.value) return false
        engine.advance(dtMs)
        commit()
        return true
    }

    /** A person tapped picture [symbol] for [seat] (finger, TalkBack double-tap or the one-switch scan). */
    @Synchronized
    fun tap(seat: Int, symbol: Int, atMs: Long): FindItTapResult {
        if (matchOver.value) return FindItTapResult.NOT_PLAYING
        val r = engine.tap(seat, symbol, atMs)
        commit()
        return r
    }

    /** Another game with the same seats (the session tally stays with the shell). */
    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        engine.playAgain()
        commit()
    }

    /**
     * Ends the session. Group Hunt is cooperative: everyone who played counts as winning with the team (house
     * convention, like Bridge Builders). Match Race and Dash: the top score wins, ties share.
     */
    fun leaveSession() {
        if (matchOver.value) return
        val s = state.value
        val scores = s.seats.map { it.score }
        val top = scores.maxOrNull() ?: 0
        val coop = mode == FindItMode.GROUP_HUNT
        val progress = s.found.size + s.sceneIndex
        endMatch(
            GameResult(
                scores = context.players.mapIndexed { i, p ->
                    val score = scores.getOrElse(i) { 0 }
                    PlayerScore(p.playerId, score, isWinner = if (coop) progress > 0 else (top > 0 && score == top))
                }
            )
        )
    }

    private fun commit() { state.value = engine.state() }

    // ---- resume ---------------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) {
        try {
            if (matchOver.value) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            val save = engine.toSave() ?: return null
            FindItSaveCodec.encode(save)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val save = FindItSaveCodec.decode(snapshot, seats()) ?: return false
        if (!engine.restore(save)) return false
        mode = FindItMode.fromId(save.mode)
        cardSize = FindItCardSize.fromId(save.cardSize)
        length = FindItLength.fromId(save.length)
        relaxed = save.relaxed
        handicap = save.handicap
        huntHint = save.huntHint
        oneSwitch = save.oneSwitch
        matchOver.value = false
        commit()
        return true
    }
}

/** JSON codec for [FindItSave]. Never throws: a bad snapshot returns null so the shell starts a fresh game. */
object FindItSaveCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(save: FindItSave): String = json.encodeToString(FindItSave.serializer(), save)

    fun decode(text: String, seats: List<FindItSeat>): FindItSave? = try {
        val save = json.decodeFromString(FindItSave.serializer(), text)
        if (validate(save, seats)) save else null
    } catch (_: Exception) {
        null
    }

    /** TODO(find-it): the full rules are in the [FindItSave] KDoc. This stub only checks version and seat count. */
    internal fun validate(save: FindItSave, seats: List<FindItSeat>): Boolean =
        save.version == 1 && save.playerIds.size == seats.size && save.scores.size == seats.size
}
