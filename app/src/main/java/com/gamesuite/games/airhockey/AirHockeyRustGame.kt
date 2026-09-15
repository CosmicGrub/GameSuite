package com.gamesuite.games.airhockey

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.sim.AirHockeySim
import com.gamesuite.sim.AirHockeySnapshot

/**
 * Rust-backed Air Hockey engine — the real-time-loop proof-of-concept for
 * moving GameSuite's tick-loop physics off the JVM/ART (no GC pauses during
 * `tick(dtSeconds)`, predictable frame time). Every actual physics/collision/
 * scoring computation now happens in `rust/gamesuite-sim` (a faithful,
 * line-for-line port of [AirHockeyGame]'s `tick()`), called through the
 * UniFFI-generated [AirHockeySim] bindings; this class only translates
 * [AirHockeySim.snapshot] into the exact same [AirHockeyGame.AirHockeyState]
 * shape [AirHockeyGame] itself produces, so `AirHockeyScreen.kt`'s rendering
 * code (which reads `state.ballPos`, `state.lastPaddleImpact?.seq`, the
 * `AirHockeyGame.PADDLE_RADIUS`/`BALL_RADIUS`/`GOAL_HALF_WIDTH`/`MAX_SPEED`
 * companion constants, etc.) needed zero changes beyond which class it
 * constructs and holds — see MainActivity.kt's single "air-hockey" nav route
 * and this class's own KDoc-equivalent note in the project's final report for
 * exactly why that one extra call site had to change too.
 *
 * [AirHockeyGame] itself (shared/commonMain — also compiled for the desktop
 * target, see shared/build.gradle.kts) is intentionally left completely
 * untouched: it remains both the reference implementation this port was
 * checked against and the desktop target's only implementation, and its own
 * existing shared/commonTest suite keeps passing unmodified. See the final
 * report's Testability section for why a Kotlin-only, JNI-free path staying
 * fully intact (rather than an expect/actual split inside the shared module)
 * was judged the lower-risk choice for an Air-Hockey-only pilot.
 */
class AirHockeyRustGame : GameModule {
    override val gameId = "air-hockey"
    override val displayName = "Air Hockey"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    )

    /** One native sim per match, mirroring one [AirHockeyGame] instance. Freed via UniFFI's
     *  registered Cleaner when this object becomes unreachable (same "no explicit dispose"
     *  lifecycle every other [GameModule] in this app already has — [close] is available for
     *  a caller that wants deterministic cleanup, but nothing in this app calls it today). */
    private val sim = AirHockeySim()

    val state = mutableStateOf(AirHockeyGame.AirHockeyState())

    /** Same public shape as [AirHockeyGame.difficulty] — AirHockeyScreen.kt's existing
     *  `game.difficulty = settings.defaultCpuDifficulty` assignment works unchanged. */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
        set(value) {
            field = value
            sim.setDifficulty(value.toSim())
        }

    /** Same public shape as [AirHockeyGame.matchTarget]. */
    var matchTarget: Int = AirHockeyGame.WIN_SCORE
        set(value) {
            field = value
            sim.setMatchTarget(value)
        }

    /** Same public shape as [AirHockeyGame.motionTier] — AirHockeyScreen.kt's existing
     *  `LaunchedEffect(motionTier) { game.motionTier = motionTier }` works unchanged. */
    var motionTier: AirHockeyGame.AirHockeyMotionTier = AirHockeyGame.AirHockeyMotionTier.STANDARD
        set(value) {
            field = value
            sim.setMotionTier(value.toSim())
        }

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** Mirrors [AirHockeyGame.topPaddleIsBot] — resolved once from [GameContext] here (Rust has
     *  no GameContext/PlayMode of its own) and pushed down to the sim in [init]. */
    private var topPaddleIsBot: Boolean = true

    override fun init(context: GameContext) {
        this.context = context
        topPaddleIsBot = context.activeMode == PlayMode.SINGLE_PLAYER_VS_BOT
        sim.setTopPaddleIsBot(topPaddleIsBot)
        // matchTarget's own property initializer (line below) never runs its custom setter --
        // Kotlin property initializers assign the backing field directly, bypassing `set(value)`
        // entirely, so `sim.setMatchTarget()` would otherwise never fire for whatever value
        // matchTarget holds unless some caller happens to explicitly reassign it later (nothing
        // in this app does today). Pushed explicitly here so the Rust side is never left on its
        // own independently-hardcoded default -- found by an audit of this freshly-committed file.
        sim.setMatchTarget(matchTarget)
        sim.init()
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        sim.startMatch()
        syncStateFromSim()
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        state.value = state.value.copy(matchOver = true)
        onMatchEnd?.invoke(result)
    }

    fun movePlayerPaddle(x: Float, y: Float) {
        sim.movePlayerPaddle(x, y)
        syncStateFromSim()
    }

    fun moveTopPaddle(x: Float, y: Float) {
        sim.moveTopPaddle(x, y)
        syncStateFromSim()
    }

    /** Called every frame from the UI's game loop with elapsed seconds — same call shape as
     *  [AirHockeyGame.tick]. All physics/collision/scoring math happens in Rust; this only
     *  reads the result back and fires [onMatchEnd] on the exact frame the match ends, the
     *  same "fires exactly once" guarantee [AirHockeyGame.tick] has (it early-returns before
     *  reaching that code on every subsequent call once `matchOver` is true). */
    fun tick(dtSeconds: Float) {
        val wasMatchOver = state.value.matchOver
        sim.tick(dtSeconds)
        syncStateFromSim()
        if (!wasMatchOver && state.value.matchOver) {
            fireMatchEnd()
        }
    }

    /** Releases the native Rust object deterministically. Not called anywhere in this app today
     *  (no [GameModule] has an explicit dispose lifecycle — see this class's own KDoc) but kept
     *  public so a future caller isn't forced to rely solely on the UniFFI Cleaner's GC-driven
     *  cleanup. */
    fun close() {
        sim.close()
    }

    /**
     * Mirrors the score-reporting block inline in [AirHockeyGame.tick]'s `scored` branch --
     * with one deliberate improvement over a literal transliteration: uses [s]'s own
     * `winnerIsPlayer` (computed once, correctly, inside Rust's `tick_impl` and synced via
     * [toAirHockeyState]) rather than re-deriving `isWinner` a second time from the local
     * [matchTarget] field. [AirHockeyGame.kt]'s original can safely recompute
     * `playerScore >= matchTarget` inline since it's the ONE place that value lives; here,
     * with two independently-maintained copies of match-target state (this class's own field,
     * and Rust's `match_target`), redundantly re-deriving the same boolean a second Kotlin-side
     * way is exactly the kind of duplication a future divergence between the two copies could
     * silently break -- trusting the single already-synced source of truth instead removes
     * that whole risk class, not just today's specific instance of it (see [init]'s own
     * comment on why the two copies could drift in the first place).
     */
    private fun fireMatchEnd() {
        val s = state.value
        val scores = mutableListOf<PlayerScore>()
        context.players.getOrNull(context.localPlayerIndex)?.let {
            scores += PlayerScore(playerId = it.playerId, score = s.playerScore, isWinner = s.winnerIsPlayer)
        }
        if (!topPaddleIsBot) {
            context.players.getOrNull(1 - context.localPlayerIndex)?.let {
                scores += PlayerScore(playerId = it.playerId, score = s.cpuScore, isWinner = !s.winnerIsPlayer)
            }
        }
        endMatch(GameResult(scores = scores))
    }

    private fun syncStateFromSim() {
        state.value = sim.snapshot().toAirHockeyState()
    }
}

// ---------------------------------------------------------------------------
// Translation between the UniFFI-generated com.gamesuite.sim.* types and the
// shared module's AirHockeyGame.* nested types AirHockeyScreen.kt already
// reads/draws with. Kept local to this file (not needed anywhere else).
// ---------------------------------------------------------------------------

private fun com.gamesuite.sim.Vec2.toOffset() = Offset(x, y)

private fun com.gamesuite.sim.Vec2.toVec() = AirHockeyGame.Vec(x, y)

private fun com.gamesuite.sim.TrailPoint.toKotlin() = AirHockeyGame.TrailPoint(pos.toOffset(), speed)

private fun com.gamesuite.sim.PaddleImpactEvent.toKotlin() =
    AirHockeyGame.PaddleImpactEvent(seq, position.toOffset(), speed)

private fun com.gamesuite.sim.WallBounceEvent.toKotlin() =
    AirHockeyGame.WallBounceEvent(seq, position.toOffset())

private fun com.gamesuite.sim.GoalEvent.toKotlin() =
    AirHockeyGame.GoalEvent(seq, scoredByPlayer, matchOver)

private fun com.gamesuite.sim.StaleRallyResetEvent.toKotlin() = AirHockeyGame.StaleRallyResetEvent(seq)

private fun CpuDifficulty.toSim(): com.gamesuite.sim.CpuDifficulty = when (this) {
    CpuDifficulty.EASY -> com.gamesuite.sim.CpuDifficulty.EASY
    CpuDifficulty.MEDIUM -> com.gamesuite.sim.CpuDifficulty.MEDIUM
    CpuDifficulty.HARD -> com.gamesuite.sim.CpuDifficulty.HARD
}

private fun AirHockeyGame.AirHockeyMotionTier.toSim(): com.gamesuite.sim.MotionTier = when (this) {
    AirHockeyGame.AirHockeyMotionTier.STANDARD -> com.gamesuite.sim.MotionTier.STANDARD
    AirHockeyGame.AirHockeyMotionTier.MAXIMUM -> com.gamesuite.sim.MotionTier.MAXIMUM
}

private fun AirHockeySnapshot.toAirHockeyState() = AirHockeyGame.AirHockeyState(
    ballPos = ballPos.toOffset(),
    ballVel = ballVel.toVec(),
    playerPaddle = playerPaddle.toOffset(),
    cpuPaddle = cpuPaddle.toOffset(),
    playerScore = playerScore,
    cpuScore = cpuScore,
    matchOver = matchOver,
    winnerIsPlayer = winnerIsPlayer,
    ballTrail = ballTrail.map { it.toKotlin() },
    lastPaddleImpact = lastPaddleImpact?.toKotlin(),
    lastWallBounce = lastWallBounce?.toKotlin(),
    goalEvent = goalEvent?.toKotlin(),
    staleRallyReset = staleRallyReset?.toKotlin()
)
