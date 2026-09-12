package com.gamesuite.games.airhockey

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Ported into shared/commonMain for docs/ENGINE_DECISION.md Action Item 3 --
 * the "harder pilot" precisely because, unlike TicTacToeGame.kt, this file
 * does NOT port with literally zero changes. Everything else below is
 * unchanged from app/src/main/java/com/gamesuite/games/airhockey/AirHockeyGame.kt;
 * the one real edit is every `Math.random()` call (java.lang.Math -- a
 * JVM-only class, invisible from commonMain even though both of this
 * module's current targets happen to be JVM-based) replaced with
 * `kotlin.random.Random`, a genuine multiplatform stdlib API -- the same
 * import TicTacToeGame.kt already uses for its own EASY-difficulty
 * randomization. `Random.nextDouble() < 0.5` and `Random.nextFloat()` are
 * drop-in equivalents of `Math.random() < 0.5` and `Math.random().toFloat()`
 * respectively: same uniform-distribution shape, not a byte-for-byte
 * identical call, but the closest thing to "unchanged" for a coin flip.
 * None of the three call sites are covered by AirHockeyGameTest.kt's
 * deterministic fixtures (both tests overwrite `state.value` immediately
 * after startMatch(), before ever reading the random serve direction; the
 * bot-easy-aim site isn't exercised by either test's pass-and-play mode at
 * all), so this substitution carries no regression risk to verify against.
 *
 * Real (if simplified) 2D physics air hockey: a ball with velocity that
 * bounces off walls and paddles, a player paddle (drag-controlled) and a
 * top paddle. Coordinates are normalized 0f..1f across both axes so the
 * same simulation works at any screen size — the UI layer multiplies by
 * canvas width/height when drawing.
 *
 * Layout: y=0 is the top goal edge, y=1 is the player's goal edge (bottom).
 * Goal mouth is centered on x=0.5.
 *
 * Local pass-and-play pass added [PlayMode.SINGLE_DEVICE_PASS_AND_PLAY]: the
 * top paddle is a second local player's own paddle (moved via
 * [moveTopPaddle], same drag-to-position model as the bottom player) rather
 * than always being the CPU. [topPaddleIsBot] is the single flag tick() and
 * the CPU-only pieces below it are gated on, so the two modes share one
 * state shape and physics loop with only the top paddle's *source of
 * movement* differing.
 *
 * The same pass also gave the CPU real depth (y-axis) movement — see
 * [chooseCpuTargetY]'s KDoc — since before this the CPU paddle only ever
 * tracked the ball's x position on a fixed-height rail.
 */
class AirHockeyGame : GameModule {
    override val gameId = "air-hockey"
    override val displayName = "Air Hockey"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    )

    companion object {
        const val PADDLE_RADIUS = 0.07f
        const val BALL_RADIUS = 0.035f
        const val GOAL_HALF_WIDTH = 0.16f
        const val WIN_SCORE = 7
        const val MAX_SPEED = 1.6f // normalized units/sec

        // Physics correctness fix: without this, every wall bounce is fully elastic and every
        // paddle hit re-floors speed (see resolvePaddleCollision's MIN_SPEED-style floor below),
        // so nothing in the loop ever removes energy from a rally — long rallies monotonically
        // converge on MAX_SPEED and stay there. This drag is applied only during free flight
        // (tick() skips it on any frame that just resolved a paddle hit), so a soft tap still
        // stays relatively soft and a hard slap stays fast longer, without blunting how lively
        // paddle CONTACT itself feels.
        const val BALL_DRAG_PER_SECOND = 0.2f

        // CPU depth (y-axis) tuning — see chooseCpuTargetY's KDoc.
        const val CPU_HOME_Y = 0.15f // matches cpuPaddle's start position — the old fixed rail
        const val DEEP_BALL_Y = 0.75f // ball this far into the player's half reads as "deep"
        const val POST_CONCEDE_DEFENSE_SECONDS = 1.2f // how long to hang back after conceding

        // "Premium 2026 vision" pitch additions below — real-physics juice layered on top of
        // the simulation above rather than a separate cosmetic system (see each field's KDoc).
        /** Ring-buffer length for [AirHockeyState.ballTrail] at [AirHockeyMotionTier.STANDARD]. */
        const val TRAIL_LENGTH_STANDARD = 8
        /** Longer, showier trail at [AirHockeyMotionTier.MAXIMUM]. */
        const val TRAIL_LENGTH_MAXIMUM = 16
        /** A paddle-contact [PaddleCollisionResult.impactSpeed] above this reads as a "hard"
         *  hit — a longer hit-stop freeze and the loudest haptic/sound bucket. */
        const val STRONG_IMPACT_SPEED = 0.95f
        /** ~160ms at 60fps — how long the freshly-reset ball holds still after a goal so the
         *  celebration sequence (camera-shake/flash/particles/scoreboard punch) reads before
         *  play resumes. See tick()'s hit-stop handling. */
        const val GOAL_FREEZE_FRAMES = 10

        /**
         * Real, observed bug fix: this simplified physics has at least one genuine degenerate
         * steady state a rally can fall into and never leave on its own -- a ball settling into
         * a near-horizontal trajectory at a paddle's own height gets perpetually re-floored to
         * MIN_PADDLE_BOUNCE_SPEED in roughly the same direction by shallow, repeated paddle
         * grazes (see [resolvePaddleCollision]'s speed floor), while the two side walls just
         * flip its x-velocity elastically -- so it shuttles left-right forever at a y nowhere
         * near either goal mouth, never scoring and never naturally resolving. [tick] tracks
         * real elapsed seconds since the last goal ([secondsSinceLastGoal]) and forces a fresh,
         * randomized-direction "stale rally reset" (see [AirHockeyState.staleRallyReset]) if
         * this much time passes with no goal -- a hard guarantee against a permanently stuck
         * table, independent of whatever specific geometry caused the stall. 15s was chosen
         * against MAX_SPEED=1.6 covering this whole 1.0-unit-wide/tall table in well under a
         * second at full speed -- any single rally genuinely still in progress after 15
         * continuous seconds is already far outside normal play, not a false-positive risk for
         * a merely slow-but-live rally.
         */
        const val STALL_TIMEOUT_SECONDS = 15f
    }

    /**
     * Local (this-game-only) motion-intensity tier — see the "premium 2026 vision" pitch's
     * per-game notes for why Air Hockey specifically earns a real Standard/Maximum split where
     * most other games in this pass deliberately don't: it can stack more independent
     * compounding juice effects (trail length, camera-shake, particle burst) than most games in
     * the suite. Persisted per-player via [AirHockeyPrefsStore], NOT a new AppSettings field —
     * same "one tiny dedicated store" shape as Solitaire's draw-1/3 toggle.
     *
     * STANDARD still gets the full baseline "juice" (trail, paddle shockwave ring, goal flash,
     * scoreboard digit punch, layered sound/haptics) — MAXIMUM additionally layers camera-shake
     * and the goal particle burst on top, and lengthens the puck trail. See AirHockeyScreen's
     * read sites for exactly which effects each tier gates.
     */
    enum class AirHockeyMotionTier { STANDARD, MAXIMUM }

    data class Vec(val x: Float, val y: Float) {
        operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
        operator fun times(s: Float) = Vec(x * s, y * s)
        fun length() = sqrt(x * x + y * y)
    }

    /** One sample in [AirHockeyState.ballTrail] — a past ball position plus the ball's real
     *  speed magnitude at that instant, so the trail's fade/width can reflect actual physics
     *  history rather than a uniform cosmetic decay. */
    data class TrailPoint(val pos: Offset, val speed: Float)

    /** Fired whenever [tick] resolves a genuine paddle/ball collision — [speed] is the real
     *  incoming relative-velocity magnitude at contact (see [PaddleCollisionResult.impactSpeed]),
     *  used both to pick a haptic bucket and to scale the shockwave-ring/sound. [seq] is a
     *  monotonically increasing id (shared with [WallBounceEvent]/[GoalEvent]) so the UI's
     *  `LaunchedEffect(key)` fires exactly once per event, even if two hits land on the same
     *  spot with the same speed. */
    data class PaddleImpactEvent(val seq: Long, val position: Offset, val speed: Float)

    /** Fired on every real wall/board bounce (left/right rail, or the boards either side of a
     *  goal mouth) — today these carry zero audio feedback at all; see AirHockeyScreen. */
    data class WallBounceEvent(val seq: Long, val position: Offset)

    /** Fired exactly once per goal, from the same branch that resets the ball to center. */
    data class GoalEvent(val seq: Long, val scoredByPlayer: Boolean, val matchOver: Boolean)

    /** Fired whenever [tick] force-resets a rally that ran [STALL_TIMEOUT_SECONDS] with no goal
     *  (see that constant's KDoc) -- a real, previously-possible-forever-stuck-table bug fix,
     *  not a cosmetic event. No score changes; this is a "the physics stalled" signal, not a
     *  scoring one. A UI MAY react to this (e.g. a brief "Rally reset" toast) but doesn't have
     *  to -- the reset itself is what actually fixes the stall regardless of whether anything
     *  visibly announces it. */
    data class StaleRallyResetEvent(val seq: Long)

    data class AirHockeyState(
        val ballPos: Offset = Offset(0.5f, 0.5f),
        val ballVel: Vec = Vec(0f, 0f),
        val playerPaddle: Offset = Offset(0.5f, 0.85f),
        /** The top paddle. CPU-controlled in SINGLE_PLAYER_VS_BOT (driven by tick()'s AI
         *  targeting); a second local player's own paddle in SINGLE_DEVICE_PASS_AND_PLAY
         *  (moved via [moveTopPaddle]). Same field either way — see [topPaddleIsBot]. */
        val cpuPaddle: Offset = Offset(0.5f, 0.15f),
        val playerScore: Int = 0,
        /** The bottom player's opponent's score — the CPU's in vs-bot, Player 2's in pass-and-play. */
        val cpuScore: Int = 0,
        val matchOver: Boolean = false,
        val winnerIsPlayer: Boolean = false,
        /** Ring buffer of recent ball positions (see [TrailPoint]), newest last, capped at
         *  [TRAIL_LENGTH_STANDARD]/[TRAIL_LENGTH_MAXIMUM] depending on [motionTier]. Reset to
         *  empty on every goal (a fresh serve has no trail yet) since a brand-new
         *  [AirHockeyState] is built there rather than [AirHockeyState.copy]d. */
        val ballTrail: List<TrailPoint> = emptyList(),
        val lastPaddleImpact: PaddleImpactEvent? = null,
        val lastWallBounce: WallBounceEvent? = null,
        val goalEvent: GoalEvent? = null,
        val staleRallyReset: StaleRallyResetEvent? = null
    )

    val state = mutableStateOf(AirHockeyState())

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /**
     * The score needed to win the match. Pre-set by the UI before startMatch(), same pattern
     * as [difficulty] — defaults to [WIN_SCORE] so behavior is unchanged unless a caller
     * explicitly configures it. Used everywhere the old hardcoded WIN_SCORE win-check lived;
     * no settings UI reads/writes this yet (see the quick-win pass that added it).
     */
    var matchTarget: Int = WIN_SCORE

    /** Pre-set by the UI from [AirHockeyPrefsStore] before startMatch(), same pattern as
     *  [difficulty]/[matchTarget]. Read by [tick] to size [AirHockeyState.ballTrail]; the
     *  heavier UI-only effects (camera-shake, particle burst) it also gates live entirely in
     *  AirHockeyScreen, which reads the same persisted value independently. */
    var motionTier: AirHockeyMotionTier = AirHockeyMotionTier.STANDARD

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var lastPlayerPaddle = Offset(0.5f, 0.85f)
    private var lastTopPaddle = Offset(0.5f, 0.15f)

    /** Counts down whole [tick] calls to skip entirely — a genuine freeze-frame (the world,
     *  not just the ball, holds still) on real paddle contact or a goal, the same trick
     *  fighting games use for hit-stop. Paddle-position tracking (`lastPlayerPaddle`/
     *  `lastTopPaddle`) still updates every call even while this is counting down, so paddle
     *  momentum doesn't spike once play resumes — see [tick]. */
    private var hitStopFramesRemaining = 0

    /** Monotonic id shared by [PaddleImpactEvent]/[WallBounceEvent]/[GoalEvent] so the UI can
     *  key a `LaunchedEffect` on "this exact event", not just "this field is non-null" (which
     *  would miss a second identical-looking event, e.g. two hits at the same spot). */
    private var eventSeq = 0L

    /** Counts down from [POST_CONCEDE_DEFENSE_SECONDS] after the CPU concedes a goal, forcing
     *  [chooseCpuTargetY] to hang back at [CPU_HOME_Y] for a beat instead of immediately
     *  reading the just-reset ball as "crossed center, push forward". */
    private var postConcedeDefenseTimer = 0f

    /** Real elapsed seconds since the last goal (or match start) -- see [STALL_TIMEOUT_SECONDS]'s
     *  KDoc. Reset to 0 on every goal (including a stale-rally reset itself, so a table that
     *  somehow re-stalls immediately after a forced reset gets another full timeout, not zero). */
    private var secondsSinceLastGoal = 0f

    override fun init(context: GameContext) {
        this.context = context
        secondsSinceLastGoal = 0f
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        state.value = AirHockeyState(ballVel = Vec(if (Random.nextDouble() < 0.5) -0.5f else 0.5f, 0.5f))
        secondsSinceLastGoal = 0f
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        state.value = state.value.copy(matchOver = true)
        onMatchEnd?.invoke(result)
    }

    fun movePlayerPaddle(x: Float, y: Float) {
        val s = state.value
        if (s.matchOver) return
        // Player confined to bottom half of the table.
        val clampedY = y.coerceIn(0.5f + PADDLE_RADIUS, 1f - PADDLE_RADIUS)
        val clampedX = x.coerceIn(PADDLE_RADIUS, 1f - PADDLE_RADIUS)
        state.value = s.copy(playerPaddle = Offset(clampedX, clampedY))
    }

    /**
     * Called from AirHockeyScreen's second drag zone in SINGLE_DEVICE_PASS_AND_PLAY — the
     * top-half mirror of [movePlayerPaddle]. No-ops if the top paddle is CPU-controlled
     * ([topPaddleIsBot]) so a stray call (e.g. from a leftover drag zone) can't fight tick()'s
     * own AI targeting for the same paddle.
     */
    fun moveTopPaddle(x: Float, y: Float) {
        val s = state.value
        if (s.matchOver || topPaddleIsBot) return
        // Second player confined to top half of the table.
        val clampedY = y.coerceIn(PADDLE_RADIUS, 0.5f - PADDLE_RADIUS)
        val clampedX = x.coerceIn(PADDLE_RADIUS, 1f - PADDLE_RADIUS)
        state.value = s.copy(cpuPaddle = Offset(clampedX, clampedY))
    }

    /**
     * True when the top paddle should be driven by tick()'s CPU AI rather than a second local
     * player's drags via [moveTopPaddle]. Keyed off the active [PlayMode] rather than the
     * player roster's `isBot` flags (the way TicTacToeGame's `playBotTurn()` does) because
     * AirHockeyGame's existing vs-CPU launch site doesn't add a roster entry for the CPU at
     * all — there's no second [PlayerInfo] slot to inspect here.
     */
    private val topPaddleIsBot: Boolean
        get() = context.activeMode == PlayMode.SINGLE_PLAYER_VS_BOT

    /** Called every frame from the UI's game loop with elapsed seconds. */
    fun tick(dtSeconds: Float) {
        val s = state.value
        if (s.matchOver) return
        val dt = dtSeconds.coerceIn(0f, 0.05f) // clamp to avoid huge steps on frame hitches

        // Track player paddle velocity for transferring momentum on hit. Deliberately
        // unconditional — even during a hit-stop freeze below — so a player still dragging
        // their paddle through a frozen frame doesn't get an artificially inflated velocity
        // reading the instant play resumes.
        val playerVel = Vec(
            (s.playerPaddle.x - lastPlayerPaddle.x) / dt.coerceAtLeast(0.001f),
            (s.playerPaddle.y - lastPlayerPaddle.y) / dt.coerceAtLeast(0.001f)
        )
        // Captured before lastPlayerPaddle is overwritten below — the segment start for
        // resolvePaddleCollision's swept tunneling check further down (this frame's paddle
        // travel is playerPaddleFrom -> s.playerPaddle).
        val playerPaddleFrom = lastPlayerPaddle
        lastPlayerPaddle = s.playerPaddle

        // Same tracking for the top paddle, but only meaningful in pass-and-play — when
        // topPaddleIsBot the top paddle only ever moves via the AI targeting below, at a speed
        // resolvePaddleCollision is told to ignore (Vec(0f, 0f) is still passed for it there),
        // so vs-CPU's collision behavior is unchanged.
        val topPaddleVel = Vec(
            (s.cpuPaddle.x - lastTopPaddle.x) / dt.coerceAtLeast(0.001f),
            (s.cpuPaddle.y - lastTopPaddle.y) / dt.coerceAtLeast(0.001f)
        )
        // Same swept-segment start as playerPaddleFrom above, captured before the CPU AI block
        // further down computes this frame's new cpuPaddle position (the segment end).
        val topPaddleFrom = lastTopPaddle
        lastTopPaddle = s.cpuPaddle

        // Hit-stop: a genuine freeze-frame set by a paddle impact or a goal further down in a
        // PREVIOUS call to this function — skip this frame's ball/CPU simulation entirely
        // (state.value is left untouched) while the UI still reads the still-fresh impact/goal
        // event and plays its one-shot reaction against a held frame. Paddle velocity tracking
        // above already ran, so this can't desync lastPlayerPaddle/lastTopPaddle.
        if (hitStopFramesRemaining > 0) {
            hitStopFramesRemaining--
            return
        }

        // See STALL_TIMEOUT_SECONDS' KDoc -- reset to 0 the moment a real goal is scored
        // (below), so this only ever measures a single, currently-live rally's own duration.
        secondsSinceLastGoal += dt

        var ball = s.ballPos
        var vel = s.ballVel

        ball = Offset(ball.x + vel.x * dt, ball.y + vel.y * dt)

        // Wall bounce (left/right) — also a real, previously-silent event: see WallBounceEvent.
        var wallBounceEvent = s.lastWallBounce
        if (ball.x - BALL_RADIUS < 0f) {
            ball = ball.copy(x = BALL_RADIUS); vel = Vec(-vel.x, vel.y)
            eventSeq++; wallBounceEvent = WallBounceEvent(eventSeq, ball)
        }
        if (ball.x + BALL_RADIUS > 1f) {
            ball = ball.copy(x = 1f - BALL_RADIUS); vel = Vec(-vel.x, vel.y)
            eventSeq++; wallBounceEvent = WallBounceEvent(eventSeq, ball)
        }

        postConcedeDefenseTimer = (postConcedeDefenseTimer - dt).coerceAtLeast(0f)

        // Top paddle: in SINGLE_PLAYER_VS_BOT this is tracking AI (x from chooseCpuTargetX,
        // depth from chooseCpuTargetY — see their KDocs), scaled by difficulty. In
        // SINGLE_DEVICE_PASS_AND_PLAY the top paddle belongs to a second local player instead
        // (see topPaddleIsBot/moveTopPaddle) — none of this AI targeting runs then, and
        // cpuPaddle is simply left wherever that player's last drag put it.
        var cpuPaddle = s.cpuPaddle
        if (topPaddleIsBot) {
            val targetX = chooseCpuTargetX(ball, vel)
            val cpuSpeed = cpuSpeedFor(difficulty)
            val dx = (targetX - cpuPaddle.x).coerceIn(-cpuSpeed * dt, cpuSpeed * dt)
            val targetY = chooseCpuTargetY(ball, justConceded = postConcedeDefenseTimer > 0f)
            val dy = (targetY - cpuPaddle.y).coerceIn(-cpuSpeed * dt, cpuSpeed * dt)
            cpuPaddle = Offset(
                (cpuPaddle.x + dx).coerceIn(PADDLE_RADIUS, 1f - PADDLE_RADIUS),
                (cpuPaddle.y + dy).coerceIn(PADDLE_RADIUS, 0.5f - PADDLE_RADIUS)
            )
        }

        // Paddle collisions (circle-circle, swept — see resolvePaddleCollision's KDoc for the
        // tunneling fix) — resolved BEFORE the goal check below, so a paddle that is physically
        // touching (or swept through) the ball this frame gets to intercept it instead of the
        // goal branch discarding the collision outcome. `blockedByPaddle` also catches the case
        // where the ball is inside a paddle's hit circle but resolvePaddleCollision left velocity
        // unchanged (ball already moving away from the paddle's center, e.g. grazing past it) —
        // geometric overlap, not just a change in velocity, is what should veto a goal.
        val playerHit = resolvePaddleCollision(ball, vel, s.playerPaddle, playerVel, playerPaddleFrom)
        ball = playerHit.ballPos; vel = playerHit.ballVel
        val topHit = resolvePaddleCollision(ball, vel, cpuPaddle, if (topPaddleIsBot) Vec(0f, 0f) else topPaddleVel, topPaddleFrom)
        ball = topHit.ballPos; vel = topHit.ballVel
        val paddleHitThisFrame = playerHit.hit || topHit.hit
        val blockedByPaddle = paddleHitThisFrame ||
            ballOverlapsPaddle(ball, s.playerPaddle) || ballOverlapsPaddle(ball, cpuPaddle)

        // Real paddle-contact event + hit-stop — reserved for an actual hit this frame, not
        // fired every tick. impactSpeed is the genuine incoming relative-velocity magnitude at
        // contact (see PaddleCollisionResult.impactSpeed), reused below for both the freeze
        // length and (by the UI) the shockwave/haptic/sound intensity.
        var paddleImpactEvent = s.lastPaddleImpact
        if (paddleHitThisFrame) {
            val impactSpeed = maxOf(playerHit.impactSpeed, topHit.impactSpeed)
            eventSeq++
            paddleImpactEvent = PaddleImpactEvent(eventSeq, ball, impactSpeed)
            hitStopFramesRemaining = if (impactSpeed > STRONG_IMPACT_SPEED) 2 else 1
        }

        // Drag: bleeds a little speed off the ball every tick during free flight only (never on
        // a frame that just resolved a paddle hit above, so paddle CONTACT keeps its existing
        // speed-floor liveliness) — see BALL_DRAG_PER_SECOND's KDoc for why this is needed at
        // all. Wall bounces are unaffected (they stay fully elastic; only the magnitude decays).
        if (!paddleHitThisFrame) {
            vel = vel * (1f - BALL_DRAG_PER_SECOND * dt).coerceAtLeast(0f)
        }

        // Goal check (top = CPU's goal scored by player, bottom = player's goal scored by CPU).
        var playerScore = s.playerScore
        var cpuScore = s.cpuScore
        var scored = false
        var scoredByPlayer = false

        if (ball.y - BALL_RADIUS < 0f) {
            if (abs(ball.x - 0.5f) < GOAL_HALF_WIDTH && !blockedByPaddle) {
                playerScore++; scored = true; scoredByPlayer = true
                // The top paddle (CPU) just conceded — see chooseCpuTargetY's KDoc for why
                // this briefly overrides its usual "ball crossed center, push forward" read of
                // the freshly-reset ball.
                postConcedeDefenseTimer = POST_CONCEDE_DEFENSE_SECONDS
            } else {
                ball = ball.copy(y = BALL_RADIUS)
                if (!blockedByPaddle) {
                    vel = Vec(vel.x, -vel.y)
                    eventSeq++; wallBounceEvent = WallBounceEvent(eventSeq, ball)
                }
            }
        }
        if (ball.y + BALL_RADIUS > 1f) {
            if (abs(ball.x - 0.5f) < GOAL_HALF_WIDTH && !blockedByPaddle) {
                cpuScore++; scored = true; scoredByPlayer = false
            } else {
                ball = ball.copy(y = 1f - BALL_RADIUS)
                if (!blockedByPaddle) {
                    vel = Vec(vel.x, -vel.y)
                    eventSeq++; wallBounceEvent = WallBounceEvent(eventSeq, ball)
                }
            }
        }

        // Trail: a ring buffer of real recent ball positions/speeds (see TrailPoint), capped
        // per motion tier — visualizes physics state that already exists above, not a separate
        // cosmetic simulation.
        val trailCap = if (motionTier == AirHockeyMotionTier.MAXIMUM) TRAIL_LENGTH_MAXIMUM else TRAIL_LENGTH_STANDARD
        val newTrail = (s.ballTrail + TrailPoint(ball, vel.length())).takeLast(trailCap)

        if (scored) {
            secondsSinceLastGoal = 0f
            val matchOver = playerScore >= matchTarget || cpuScore >= matchTarget
            eventSeq++
            // Hold the freshly-reset ball still for a beat so the goal celebration
            // (camera-shake/flash/particles/scoreboard punch, all UI-side) reads clearly
            // before play resumes — overrides whatever shorter paddle-hit freeze (if any) was
            // already pending from this same frame's collision resolution above.
            hitStopFramesRemaining = GOAL_FREEZE_FRAMES
            state.value = AirHockeyState(
                ballPos = Offset(0.5f, 0.5f),
                ballVel = Vec(if (Random.nextDouble() < 0.5) -0.5f else 0.5f, if (playerScore > s.playerScore) -0.6f else 0.6f),
                playerPaddle = s.playerPaddle,
                cpuPaddle = cpuPaddle,
                playerScore = playerScore,
                cpuScore = cpuScore,
                matchOver = matchOver,
                winnerIsPlayer = playerScore >= matchTarget,
                goalEvent = GoalEvent(eventSeq, scoredByPlayer, matchOver)
            )
            if (matchOver) {
                // Score both seats in local pass-and-play (a real second player, not a bot,
                // per topPaddleIsBot) so GameResult — and the stats layer that reads it —
                // reflects Player 2's result too, instead of only ever crediting whoever's
                // context.localPlayerIndex happens to be (always the bottom seat, per
                // MainMenuScreen's launch configs). In vs-CPU mode this is unchanged: only
                // the one real player is scored, exactly as before this pass.
                val scores = mutableListOf<PlayerScore>()
                context.players.getOrNull(context.localPlayerIndex)?.let {
                    scores += PlayerScore(playerId = it.playerId, score = playerScore, isWinner = playerScore >= matchTarget)
                }
                if (!topPaddleIsBot) {
                    context.players.getOrNull(1 - context.localPlayerIndex)?.let {
                        scores += PlayerScore(playerId = it.playerId, score = cpuScore, isWinner = cpuScore >= matchTarget)
                    }
                }
                endMatch(GameResult(scores = scores))
            }
        } else if (secondsSinceLastGoal >= STALL_TIMEOUT_SECONDS) {
            // Real, previously-possible-forever-stuck-table bug fix -- see STALL_TIMEOUT_SECONDS'
            // KDoc. No score change either way: this is "the physics stalled," not a goal.
            // Both velocity components get a fresh random direction (unlike a real goal-reset,
            // which biases y toward whoever just conceded -- there's no "conceder" here) so a
            // reset can't just fall straight back into the same degenerate trajectory.
            eventSeq++
            secondsSinceLastGoal = 0f
            hitStopFramesRemaining = GOAL_FREEZE_FRAMES
            state.value = AirHockeyState(
                ballPos = Offset(0.5f, 0.5f),
                ballVel = Vec(if (Random.nextDouble() < 0.5) -0.5f else 0.5f, if (Random.nextDouble() < 0.5) -0.6f else 0.6f),
                playerPaddle = s.playerPaddle,
                cpuPaddle = cpuPaddle,
                playerScore = playerScore,
                cpuScore = cpuScore,
                staleRallyReset = StaleRallyResetEvent(eventSeq)
            )
        } else {
            state.value = s.copy(
                ballPos = ball,
                ballVel = vel,
                cpuPaddle = cpuPaddle,
                playerScore = playerScore,
                cpuScore = cpuScore,
                ballTrail = newTrail,
                lastPaddleImpact = paddleImpactEvent,
                lastWallBounce = wallBounceEvent
            )
        }
    }

    private fun cpuSpeedFor(difficulty: CpuDifficulty): Float = when (difficulty) {
        CpuDifficulty.EASY -> 0.55f
        CpuDifficulty.MEDIUM -> 0.9f // the original, single fixed speed this ladder replaces
        CpuDifficulty.HARD -> 1.3f
    }

    /**
     * Drifts to center once the ball is back on the player's half, same as
     * before at every tier. On the CPU's half, the three tiers genuinely
     * play differently rather than just moving at different speeds:
     * EASY re-rolls a wide random aim offset every frame (reads as a sloppy,
     * wobbly paddle, not just a slow one); MEDIUM keeps the original
     * behavior — track the ball's exact x, no error, no anticipation;
     * HARD adds lookahead, aiming slightly ahead of the ball along its
     * current velocity instead of where it is *right now*, so it starts
     * closing on fast shots before they arrive.
     */
    private fun chooseCpuTargetX(ball: Offset, ballVel: Vec): Float {
        if (ball.y >= 0.5f) return 0.5f
        return when (difficulty) {
            CpuDifficulty.EASY -> ball.x + (Random.nextFloat() - 0.5f) * 0.24f
            CpuDifficulty.MEDIUM -> ball.x
            CpuDifficulty.HARD -> ball.x + ballVel.x * 0.15f
        }
    }

    /**
     * Depth (y-axis) targeting for a bot-controlled top paddle, layered alongside
     * [chooseCpuTargetX]'s left-right tracking so the CPU actually moves in 2D instead of
     * gliding along a fixed-height rail. Two postures, chosen per frame from the ball's
     * position (and a recent-concession override):
     *  - Defensive, at [CPU_HOME_Y] (its starting depth) — when the ball is deep in the
     *    player's half (past [DEEP_BALL_Y], nothing to intercept yet, so guard the goal
     *    line) or [justConceded] is true (see [postConcedeDefenseTimer] — don't immediately
     *    read the just-reset, still-centered ball as "safe to push forward").
     *  - Advancing, toward [cpuForwardYFor]'s difficulty-scaled depth — once the ball has
     *    crossed into the CPU's own half (y < 0.5f), closing the distance to challenge it
     *    instead of waiting back at the goal line.
     * [cpuForwardYFor] is what actually differentiates the tiers here: EASY barely leaves
     * [CPU_HOME_Y] (closest to the old fixed-rail feel), HARD advances the furthest — the
     * most depth movement of the three, mirroring how HARD is already the most aggressive
     * tier in [chooseCpuTargetX].
     */
    private fun chooseCpuTargetY(ball: Offset, justConceded: Boolean): Float {
        if (justConceded || ball.y > DEEP_BALL_Y) return CPU_HOME_Y
        if (ball.y < 0.5f) return cpuForwardYFor(difficulty)
        return CPU_HOME_Y
    }

    private fun cpuForwardYFor(difficulty: CpuDifficulty): Float = when (difficulty) {
        CpuDifficulty.EASY -> 0.22f
        CpuDifficulty.MEDIUM -> 0.30f
        CpuDifficulty.HARD -> 0.40f
    }

    /** True when the ball's hit circle overlaps the paddle's, regardless of travel direction. */
    private fun ballOverlapsPaddle(ball: Offset, paddle: Offset): Boolean {
        val dx = ball.x - paddle.x
        val dy = ball.y - paddle.y
        val minDist = BALL_RADIUS + PADDLE_RADIUS
        return dx * dx + dy * dy < minDist * minDist
    }

    /** Result of a single paddle's collision test: the ball's (possibly corrected) position and
     *  velocity, plus whether a collision actually resolved this frame (used by tick() both to
     *  veto a same-frame goal and to skip that frame's free-flight drag). [impactSpeed] is the
     *  real incoming relative-velocity magnitude at contact (0f when [hit] is false) — used by
     *  tick() to size the hit-stop freeze and by the UI to scale the shockwave/haptic/sound. */
    private data class PaddleCollisionResult(val ballPos: Offset, val ballVel: Vec, val hit: Boolean, val impactSpeed: Float = 0f)

    /** Closest point on segment a→b to point p. Degenerates to `a` (== `b`) when the segment has
     *  zero length (a stationary paddle) — see [resolvePaddleCollision]'s KDoc. */
    private fun closestPointOnSegment(a: Offset, b: Offset, p: Offset): Offset {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq < 1e-12f) return a
        val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
        return Offset(a.x + abx * t, a.y + aby * t)
    }

    /**
     * Resolves one ball/paddle collision, if any.
     *
     * Tunneling fix: rather than testing the ball only against the paddle's current-frame
     * position [paddle], this tests it against the closest point on the segment the paddle swept
     * across this frame — from [paddleFrom] (its position last frame) to [paddle]. A fast enough
     * flick can move a paddle clear from one side of the ball to the other between two rendered
     * frames without either endpoint's circle ever overlapping the ball's, even though the
     * paddle's path passed directly through it; the ball would then visibly pass through with
     * zero bounce. Note the clamp in [closestPointOnSegment] means t=1 (i.e. [paddle] itself) is
     * already one candidate point on the segment, so this swept test is a strict superset of the
     * old point-in-time-only check — a paddle that barely moved (paddleFrom ≈ paddle) behaves
     * exactly as before.
     *
     * Positional correction fix: on a genuine hit, also nudges the ball's position back onto the
     * paddle's edge along the collision normal (from the contact point found above) so it never
     * renders visibly sunk into the paddle sprite for a frame while waiting for the next
     * integration step to carry it clear — reflecting velocity alone (the old behavior) doesn't
     * move the ball at all.
     */
    private fun resolvePaddleCollision(
        ball: Offset,
        ballVel: Vec,
        paddle: Offset,
        paddleVel: Vec,
        paddleFrom: Offset
    ): PaddleCollisionResult {
        val minDist = BALL_RADIUS + PADDLE_RADIUS
        val contact = closestPointOnSegment(paddleFrom, paddle, ball)

        val dx = ball.x - contact.x
        val dy = ball.y - contact.y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist >= minDist || dist == 0f) return PaddleCollisionResult(ball, ballVel, hit = false)

        val nx = dx / dist
        val ny = dy / dist
        val relVel = Vec(ballVel.x - paddleVel.x, ballVel.y - paddleVel.y)
        val speedAlongNormal = relVel.x * nx + relVel.y * ny
        if (speedAlongNormal > 0) return PaddleCollisionResult(ball, ballVel, hit = false) // already separating

        val bounced = Vec(
            ballVel.x - 2 * speedAlongNormal * nx + paddleVel.x * 0.3f,
            ballVel.y - 2 * speedAlongNormal * ny + paddleVel.y * 0.3f
        )
        val speed = bounced.length().coerceIn(0.4f, MAX_SPEED)
        val normalized = if (bounced.length() > 0f) bounced * (speed / bounced.length()) else bounced

        val correctedBall = Offset(contact.x + nx * minDist, contact.y + ny * minDist)
        return PaddleCollisionResult(correctedBall, normalized, hit = true, impactSpeed = abs(speedAlongNormal))
    }
}
