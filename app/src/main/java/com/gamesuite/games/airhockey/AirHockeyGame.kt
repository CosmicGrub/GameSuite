package com.gamesuite.games.airhockey

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.math.abs
import kotlin.math.sqrt

/**
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

        // CPU depth (y-axis) tuning — see chooseCpuTargetY's KDoc.
        const val CPU_HOME_Y = 0.15f // matches cpuPaddle's start position — the old fixed rail
        const val DEEP_BALL_Y = 0.75f // ball this far into the player's half reads as "deep"
        const val POST_CONCEDE_DEFENSE_SECONDS = 1.2f // how long to hang back after conceding
    }

    data class Vec(val x: Float, val y: Float) {
        operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
        operator fun times(s: Float) = Vec(x * s, y * s)
        fun length() = sqrt(x * x + y * y)
    }

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
        val winnerIsPlayer: Boolean = false
    )

    val state = mutableStateOf(AirHockeyState())

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var lastPlayerPaddle = Offset(0.5f, 0.85f)
    private var lastTopPaddle = Offset(0.5f, 0.15f)

    /** Counts down from [POST_CONCEDE_DEFENSE_SECONDS] after the CPU concedes a goal, forcing
     *  [chooseCpuTargetY] to hang back at [CPU_HOME_Y] for a beat instead of immediately
     *  reading the just-reset ball as "crossed center, push forward". */
    private var postConcedeDefenseTimer = 0f

    override fun init(context: GameContext) {
        this.context = context
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        state.value = AirHockeyState(ballVel = Vec(if (Math.random() < 0.5) -0.5f else 0.5f, 0.5f))
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

        // Track player paddle velocity for transferring momentum on hit.
        val playerVel = Vec(
            (s.playerPaddle.x - lastPlayerPaddle.x) / dt.coerceAtLeast(0.001f),
            (s.playerPaddle.y - lastPlayerPaddle.y) / dt.coerceAtLeast(0.001f)
        )
        lastPlayerPaddle = s.playerPaddle

        // Same tracking for the top paddle, but only meaningful in pass-and-play — when
        // topPaddleIsBot the top paddle only ever moves via the AI targeting below, at a speed
        // resolvePaddleCollision is told to ignore (Vec(0f, 0f) is still passed for it there),
        // so vs-CPU's collision behavior is unchanged.
        val topPaddleVel = Vec(
            (s.cpuPaddle.x - lastTopPaddle.x) / dt.coerceAtLeast(0.001f),
            (s.cpuPaddle.y - lastTopPaddle.y) / dt.coerceAtLeast(0.001f)
        )
        lastTopPaddle = s.cpuPaddle

        var ball = s.ballPos
        var vel = s.ballVel

        ball = Offset(ball.x + vel.x * dt, ball.y + vel.y * dt)

        // Wall bounce (left/right).
        if (ball.x - BALL_RADIUS < 0f) { ball = ball.copy(x = BALL_RADIUS); vel = Vec(-vel.x, vel.y) }
        if (ball.x + BALL_RADIUS > 1f) { ball = ball.copy(x = 1f - BALL_RADIUS); vel = Vec(-vel.x, vel.y) }

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

        // Paddle collisions (circle-circle) — resolved BEFORE the goal check below, so a paddle
        // that is physically touching the ball this frame gets to intercept it instead of the
        // goal branch discarding the collision outcome. `blockedByPaddle` also catches the case
        // where the ball is inside a paddle's hit circle but resolvePaddleCollision left velocity
        // unchanged (ball already moving away from the paddle's center, e.g. grazing past it) —
        // geometric overlap, not just a change in velocity, is what should veto a goal.
        vel = resolvePaddleCollision(ball, vel, s.playerPaddle, playerVel)
        vel = resolvePaddleCollision(ball, vel, cpuPaddle, if (topPaddleIsBot) Vec(0f, 0f) else topPaddleVel)
        val blockedByPaddle = ballOverlapsPaddle(ball, s.playerPaddle) || ballOverlapsPaddle(ball, cpuPaddle)

        // Goal check (top = CPU's goal scored by player, bottom = player's goal scored by CPU).
        var playerScore = s.playerScore
        var cpuScore = s.cpuScore
        var scored = false

        if (ball.y - BALL_RADIUS < 0f) {
            if (abs(ball.x - 0.5f) < GOAL_HALF_WIDTH && !blockedByPaddle) {
                playerScore++; scored = true
                // The top paddle (CPU) just conceded — see chooseCpuTargetY's KDoc for why
                // this briefly overrides its usual "ball crossed center, push forward" read of
                // the freshly-reset ball.
                postConcedeDefenseTimer = POST_CONCEDE_DEFENSE_SECONDS
            } else {
                ball = ball.copy(y = BALL_RADIUS)
                if (!blockedByPaddle) vel = Vec(vel.x, -vel.y)
            }
        }
        if (ball.y + BALL_RADIUS > 1f) {
            if (abs(ball.x - 0.5f) < GOAL_HALF_WIDTH && !blockedByPaddle) {
                cpuScore++; scored = true
            } else {
                ball = ball.copy(y = 1f - BALL_RADIUS)
                if (!blockedByPaddle) vel = Vec(vel.x, -vel.y)
            }
        }

        if (scored) {
            val matchOver = playerScore >= WIN_SCORE || cpuScore >= WIN_SCORE
            state.value = AirHockeyState(
                ballPos = Offset(0.5f, 0.5f),
                ballVel = Vec(if (Math.random() < 0.5) -0.5f else 0.5f, if (playerScore > s.playerScore) -0.6f else 0.6f),
                playerPaddle = s.playerPaddle,
                cpuPaddle = cpuPaddle,
                playerScore = playerScore,
                cpuScore = cpuScore,
                matchOver = matchOver,
                winnerIsPlayer = playerScore >= WIN_SCORE
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
                    scores += PlayerScore(playerId = it.playerId, score = playerScore, isWinner = playerScore >= WIN_SCORE)
                }
                if (!topPaddleIsBot) {
                    context.players.getOrNull(1 - context.localPlayerIndex)?.let {
                        scores += PlayerScore(playerId = it.playerId, score = cpuScore, isWinner = cpuScore >= WIN_SCORE)
                    }
                }
                endMatch(GameResult(scores = scores))
            }
        } else {
            state.value = s.copy(ballPos = ball, ballVel = vel, cpuPaddle = cpuPaddle, playerScore = playerScore, cpuScore = cpuScore)
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
            CpuDifficulty.EASY -> ball.x + (Math.random().toFloat() - 0.5f) * 0.24f
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

    private fun resolvePaddleCollision(ball: Offset, ballVel: Vec, paddle: Offset, paddleVel: Vec): Vec {
        val dx = ball.x - paddle.x
        val dy = ball.y - paddle.y
        val dist = sqrt(dx * dx + dy * dy)
        val minDist = BALL_RADIUS + PADDLE_RADIUS
        if (dist >= minDist || dist == 0f) return ballVel

        val nx = dx / dist
        val ny = dy / dist
        val relVel = Vec(ballVel.x - paddleVel.x, ballVel.y - paddleVel.y)
        val speedAlongNormal = relVel.x * nx + relVel.y * ny
        if (speedAlongNormal > 0) return ballVel // already separating

        val bounced = Vec(
            ballVel.x - 2 * speedAlongNormal * nx + paddleVel.x * 0.3f,
            ballVel.y - 2 * speedAlongNormal * ny + paddleVel.y * 0.3f
        )
        val speed = bounced.length().coerceIn(0.4f, MAX_SPEED)
        val normalized = if (bounced.length() > 0f) bounced * (speed / bounced.length()) else bounced
        return normalized
    }
}
