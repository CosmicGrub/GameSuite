package com.gamesuite.games.findit

import androidx.compose.runtime.mutableLongStateOf
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
 *
 * [state] holds everything that changes at discrete moments; the two values that change every frame (the clock and
 * the engine time) are separate states ([clockLeftMs], [engineMs]) so reading them in a draw block never recomposes
 * the screen.
 */
class FindItGame(
    internal val engine: FindItEngine = DefaultFindItEngine()
) : GameModule, ResumableGame {
    override val gameId = "find-it"
    override val displayName = "Find It Frenzy"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf(FindItState.EMPTY)
    val matchOver = mutableStateOf(false)

    /** The Dash clock or Hunt sunset, in ms (read it only in draw blocks and tiny composables). */
    val clockLeftMs = mutableLongStateOf(0L)

    /** Engine time since GO, in ms (read it only when a tap needs a stamp). */
    val engineMs = mutableLongStateOf(0L)

    var mode: FindItMode = FindItMode.MATCH_RACE
    var cardSize: FindItCardSize = FindItCardSize.LITTLE
    var length: FindItLength = FindItLength.STANDARD
    var relaxed: Boolean = false
    var handicap: String = "none"
    var huntHint: String = "slow"
    var oneSwitch: Boolean = false
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Game Speed multiplier for helper times, hint timers and clues (1.0 normal, above 1 slower). */
    var timeScale: Double = 1.0

    /** 0 draws a fresh seed at [init]; a test or a replay sets it. The clock is read HERE only, never in the engine. */
    var seed: Long = 0L

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this

    /** How many seats the table has (0 before [init]). */
    val seatCount: Int get() = if (::context.isInitialized) context.players.size else 0

    private fun seats(): List<FindItSeat> = context.players.map { FindItSeat(it.playerId, it.displayName, it.isBot) }

    private fun drawSeed(): Long = findItNonZero(if (seed != 0L) seed else System.nanoTime())

    private fun config(seed: Long) = FindItConfig(
        mode = mode, cardSize = cardSize, length = length, relaxed = relaxed, handicap = handicap,
        huntHint = huntHint, oneSwitch = oneSwitch, difficulty = difficulty, seed = seed,
        timeScale = timeScale.coerceIn(0.25, 4.0)
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

    /** "Try again together" after a sunset: the same scene again, already running. */
    @Synchronized
    fun retryScene() {
        if (matchOver.value) return
        if (engine.state().phase != FindItPhase.SCENE_TIMEOUT) return
        engine.go()
        engine.go()
        commit()
    }

    /** The screen's heartbeat; [dtMs] is real elapsed time since the last call. */
    @Synchronized
    fun tick(dtMs: Long): Boolean {
        if (matchOver.value) return false
        engine.advance(dtMs.coerceIn(0L, 2_000L))
        commit()
        return true
    }

    /**
     * A person tapped picture [symbol] for [seat] (finger, TalkBack double-tap or the one-switch scan). [stampMs] is
     * the tap's time stamp on the engine clock (the screen calibrates the event's uptime against [engineMs]); taps
     * of one batch resolve by the lowest stamp.
     */
    @Synchronized
    fun tap(seat: Int, symbol: Int, stampMs: Long = engineMs.longValue): FindItTapResult {
        if (matchOver.value) return FindItTapResult.NOT_PLAYING
        val r = engine.tap(seat, symbol, stampMs)
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
        val s = engine.state()
        val scores = s.seats.map { it.score }
        val top = scores.maxOrNull() ?: 0
        val coop = mode == FindItMode.GROUP_HUNT
        val progress = s.found.size + s.sceneIndex + scores.sum()
        val winners = s.result?.winnerSeats?.toSet()
        endMatch(
            GameResult(
                scores = context.players.mapIndexed { i, p ->
                    val score = scores.getOrElse(i) { 0 }
                    val win = when {
                        winners != null -> i in winners
                        coop -> progress > 0
                        mode == FindItMode.SOLO_DASH -> score > 0
                        else -> top > 0 && score == top
                    }
                    PlayerScore(p.playerId, score, isWinner = win)
                }
            )
        )
    }

    private fun commit() {
        val s = engine.state()
        clockLeftMs.longValue = s.timeLeftMs
        engineMs.longValue = s.nowMs
        // the per-frame fields are published separately; zeroing them keeps equal states equal so nothing recomposes
        state.value = s.copy(timeLeftMs = 0L, nowMs = 0L)
    }

    // ---- resume ---------------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) {
        try {
            if (matchOver.value) return null
            if (!::context.isInitialized) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            val save = engine.toSave() ?: return null
            FindItSaveCodec.encode(save)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun restore(snapshot: String): Boolean {
        if (!::context.isInitialized) return false
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

    private val handicaps = setOf("none", "all", "p1", "p2", "p3", "p4")
    private val hints = setOf("off", "slow", "fast")

    /** The full rules are in the [FindItSave] KDoc. Never throws. */
    internal fun validate(save: FindItSave, seats: List<FindItSeat>): Boolean = try {
        check(save, seats)
    } catch (_: Exception) {
        false
    }

    private fun check(save: FindItSave, seats: List<FindItSeat>): Boolean {
        if (save.version != 1) return false
        val mode = when (save.mode) {
            "race" -> FindItMode.MATCH_RACE
            "hunt" -> FindItMode.GROUP_HUNT
            "dash" -> FindItMode.SOLO_DASH
            else -> return false
        }
        val size = FindItCardSize.values().firstOrNull { it.id == save.cardSize } ?: return false
        val length = FindItLength.values().firstOrNull { it.id == save.length } ?: return false
        if (save.handicap !in handicaps || save.huntHint !in hints) return false
        if (CpuDifficulty.values().none { it.name == save.difficulty }) return false
        if (save.seed == 0L) return false
        val n = seats.size
        if (n !in 1..4) return false
        if (mode == FindItMode.MATCH_RACE && n < 2) return false
        if (mode == FindItMode.SOLO_DASH && n != 1) return false
        if (save.playerIds != seats.map { it.playerId } || save.playerIds.distinct().size != n) return false
        val max = FindItRules.MAX_COUNTER
        if (save.scores.size != n || save.misses.size != n) return false
        if (save.scores.any { it !in 0..max } || save.misses.any { it !in 0..max }) return false
        if (save.tries !in 0..max) return false
        if (save.elapsedMs !in 0L..FindItRules.MAX_ELAPSED_MS) return false
        val deckSize = size.deckSize
        if (mode == FindItMode.GROUP_HUNT) {
            if (save.deckOrder.isNotEmpty() || save.ownCards.isNotEmpty()) return false
            if (save.pileIndex != 0 || save.centreCard != 0 || save.reshuffles != 0 || save.round != 0) return false
            if (save.sceneIndex !in 0 until length.huntScenes) return false
            val targets = FindItHunt.targets(save.seed, save.sceneIndex, size)
            if (save.found.distinct().size != save.found.size || save.found.size >= targets.size) return false
            if (save.found.any { it !in targets }) return false
            return true
        }
        if (save.found.isNotEmpty() || save.sceneIndex != 0) return false
        if (save.deckOrder.size != deckSize || save.deckOrder.sorted() != (0 until deckSize).toList()) return false
        if (save.pileIndex !in 0..deckSize) return false
        if (save.reshuffles !in 0..max) return false
        if (save.ownCards.size != n || save.ownCards.any { it !in 0 until deckSize } || save.ownCards.distinct().size != n) return false
        if (save.centreCard !in 0 until deckSize || save.centreCard in save.ownCards) return false
        val roundLimit = when {
            mode == FindItMode.MATCH_RACE -> length.raceRounds
            save.relaxed -> FindItRules.DASH_RELAXED_CARDS
            else -> max + 1
        }
        if (save.round !in 0 until roundLimit) return false
        if (save.scores.sum() != save.round) return false
        if (mode == FindItMode.SOLO_DASH && !save.relaxed && save.elapsedMs !in 1L..FindItRules.dashMs(size)) return false
        return true
    }
}
