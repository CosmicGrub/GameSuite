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
 * CPU paddle (basic tracking AI). Coordinates are normalized 0f..1f across
 * both axes so the same simulation works at any screen size — the UI layer
 * multiplies by canvas width/height when drawing.
 *
 * Layout: y=0 is the CPU's goal edge (top), y=1 is the player's goal edge
 * (bottom). Goal mouth is centered on x=0.5.
 */
class AirHockeyGame : GameModule {
    override val gameId = "air-hockey"
    override val displayName = "Air Hockey"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    companion object {
        const val PADDLE_RADIUS = 0.07f
        const val BALL_RADIUS = 0.035f
        const val GOAL_HALF_WIDTH = 0.16f
        const val WIN_SCORE = 7
        const val MAX_SPEED = 1.6f // normalized units/sec
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
        val cpuPaddle: Offset = Offset(0.5f, 0.15f),
        val playerScore: Int = 0,
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

        var ball = s.ballPos
        var vel = s.ballVel

        ball = Offset(ball.x + vel.x * dt, ball.y + vel.y * dt)

        // Wall bounce (left/right).
        if (ball.x - BALL_RADIUS < 0f) { ball = ball.copy(x = BALL_RADIUS); vel = Vec(-vel.x, vel.y) }
        if (ball.x + BALL_RADIUS > 1f) { ball = ball.copy(x = 1f - BALL_RADIUS); vel = Vec(-vel.x, vel.y) }

        // CPU paddle: tracking AI, scaled by difficulty — see chooseCpuTargetX's KDoc for what
        // actually differs between the three tiers (speed, aim error, lookahead), not just a
        // single reskinned number.
        var cpuPaddle = s.cpuPaddle
        val targetX = chooseCpuTargetX(ball, vel)
        val cpuSpeed = cpuSpeedFor(difficulty)
        val dx = (targetX - cpuPaddle.x).coerceIn(-cpuSpeed * dt, cpuSpeed * dt)
        cpuPaddle = Offset(
            (cpuPaddle.x + dx).coerceIn(PADDLE_RADIUS, 1f - PADDLE_RADIUS),
            cpuPaddle.y
        )

        // Paddle collisions (circle-circle) — resolved BEFORE the goal check below, so a paddle
        // that is physically touching the ball this frame gets to intercept it instead of the
        // goal branch discarding the collision outcome. `blockedByPaddle` also catches the case
        // where the ball is inside a paddle's hit circle but resolvePaddleCollision left velocity
        // unchanged (ball already moving away from the paddle's center, e.g. grazing past it) —
        // geometric overlap, not just a change in velocity, is what should veto a goal.
        vel = resolvePaddleCollision(ball, vel, s.playerPaddle, playerVel)
        vel = resolvePaddleCollision(ball, vel, cpuPaddle, Vec(0f, 0f))
        val blockedByPaddle = ballOverlapsPaddle(ball, s.playerPaddle) || ballOverlapsPaddle(ball, cpuPaddle)

        // Goal check (top = CPU's goal scored by player, bottom = player's goal scored by CPU).
        var playerScore = s.playerScore
        var cpuScore = s.cpuScore
        var scored = false

        if (ball.y - BALL_RADIUS < 0f) {
            if (abs(ball.x - 0.5f) < GOAL_HALF_WIDTH && !blockedByPaddle) {
                playerScore++; scored = true
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
                val player = context.players.getOrNull(context.localPlayerIndex)
                endMatch(GameResult(scores = listOfNotNull(player?.let {
                    PlayerScore(playerId = it.playerId, score = playerScore, isWinner = playerScore >= WIN_SCORE)
                })))
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
