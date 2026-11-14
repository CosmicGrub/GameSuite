package com.gamesuite.games.stackzoo

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
 * Stack Zoo (id `stack-zoo`): balance original animal shapes on a rocking log. Co-op Tower, Take Turns, Solo Balance.
 * Same screen only in this version: no Nearby, no Online (the deterministic lockstep Nearby plan is in the SCALE section
 * of docs/design/kids-games/stack-zoo.md).
 *
 * All rules and physics live in [StackZooEngine]; this class is the shell glue: lifecycle, the frame-time to fixed-step
 * conversion, Compose state, result and resume. Set the option vars before [init].
 */
class StackZooGame(
    internal val engine: StackZooEngine = StubStackZooEngine()
) : GameModule, ResumableGame {
    override val gameId = "stack-zoo"
    override val displayName = "Stack Zoo"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf(ZooState.EMPTY)
    val matchOver = mutableStateOf(false)

    var mode: ZooMode = ZooMode.COOP_TOWER
    var goal: ZooGoal = ZooGoal.MEDIUM
    var length: ZooLength = ZooLength.STANDARD
    var assist: String = "none"
    var relaxed: Boolean = false
    var oneSwitch: Boolean = false
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** 1.0 normal; the slow-motion toggle sets 0.6. It changes steps per second, never the step itself. */
    var timeScale: Double = 1.0

    /** 0 draws a fresh seed at [init]; a test or a replay sets it. The clock is read HERE only, never in the engine. */
    var seed: Long = 0L

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this
    private var carry: Double = 0.0

    private fun seats(): List<ZooSeat> = context.players.map { ZooSeat(it.playerId, it.displayName, it.isBot) }

    private fun drawSeed(): Long = if (seed != 0L) seed else (System.nanoTime() or 1L)

    private fun config(seed: Long) = ZooConfig(
        mode = mode, goal = goal, length = length, assist = assist, relaxed = relaxed,
        oneSwitch = oneSwitch, difficulty = difficulty, seed = seed
    )

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            carry = 0.0
            state.value = ZooState.EMPTY
            engine.setup(config(drawSeed()), seats())
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    @Synchronized
    override fun startMatch() {
        matchOver.value = false
        carry = 0.0
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

    /** The "Ready?" tap: the first turn starts. */
    @Synchronized
    fun begin() {
        if (matchOver.value) return
        engine.begin()
        commit()
    }

    /** The active seat picked a piece. */
    @Synchronized
    fun choose(pieceId: Int): Boolean {
        if (matchOver.value) return false
        val ok = engine.choose(pieceId)
        commit()
        return ok
    }

    /** The assist's preview of where a drag at ([xQ], [angleQ]) would land (no state change). */
    @Synchronized
    fun assisted(xQ: Int, angleQ: Int): ZooPlacement = engine.assisted(xQ, angleQ)

    /** The active seat let go at a quantised placement. */
    @Synchronized
    fun release(xQ: Int, angleQ: Int): ZooReleaseResult {
        if (matchOver.value) return ZooReleaseResult.NOT_POSITIONING
        val r = engine.release(xQ, angleQ)
        commit()
        return r
    }

    /** The screen's heartbeat; [dtMs] is real frame time. Converts to whole fixed steps (never more than the cap). */
    @Synchronized
    fun frame(dtMs: Double): Boolean {
        if (matchOver.value) return false
        carry += dtMs * timeScale / 1000.0 * ZooPhysics.STEP_HZ
        val whole = carry.toInt()
        carry -= whole
        val steps = if (whole > ZooPhysics.MAX_STEPS_PER_FRAME) ZooPhysics.MAX_STEPS_PER_FRAME else whole
        if (steps > 0) engine.advance(steps)
        commit()
        return true
    }

    /** Another game with the same seats (the session tally stays with the shell). */
    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        carry = 0.0
        engine.playAgain()
        commit()
    }

    /**
     * Ends the session. Co-op Tower and Solo Balance: everyone who played counts as winning with the team as soon as one
     * animal was stacked (house convention, like Bridge Builders). Take Turns: fewest animals left wins, ties share.
     */
    fun leaveSession() {
        if (matchOver.value) return
        val s = state.value
        val stacked = s.seats.map { it.stacked }
        val anyStacked = s.bodies.any { !it.fallen }
        val left = s.seats.map { it.handCount }
        val best = left.minOrNull() ?: 0
        endMatch(
            GameResult(
                scores = context.players.mapIndexed { i, p ->
                    if (mode == ZooMode.TAKE_TURNS) {
                        PlayerScore(p.playerId, stacked.getOrElse(i) { 0 }, isWinner = left.getOrElse(i) { 0 } == best)
                    } else {
                        PlayerScore(p.playerId, stacked.getOrElse(i) { 0 }, isWinner = anyStacked)
                    }
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
            StackZooSaveCodec.encode(save)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val save = StackZooSaveCodec.decode(snapshot, seats()) ?: return false
        if (!engine.restore(save)) return false
        mode = ZooMode.fromId(save.mode)
        goal = ZooGoal.fromId(save.goal)
        length = ZooLength.fromId(save.length)
        assist = save.assist
        relaxed = save.relaxed
        oneSwitch = save.oneSwitch
        carry = 0.0
        matchOver.value = false
        commit()
        return true
    }
}

/** JSON codec for [ZooSave]. Never throws: a bad snapshot returns null so the shell starts a fresh game. */
object StackZooSaveCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(save: ZooSave): String = json.encodeToString(ZooSave.serializer(), save)

    fun decode(text: String, seats: List<ZooSeat>): ZooSave? = try {
        val save = json.decodeFromString(ZooSave.serializer(), text)
        if (validate(save, seats)) save else null
    } catch (_: Exception) {
        null
    }

    /** TODO(stack-zoo): the full rules are in the [ZooSave] KDoc. This stub only checks versions and seat count. */
    internal fun validate(save: ZooSave, seats: List<ZooSeat>): Boolean =
        save.version == 1 && save.physicsVersion == ZooPhysics.PHYSICS_VERSION && save.playerIds.size == seats.size
}
