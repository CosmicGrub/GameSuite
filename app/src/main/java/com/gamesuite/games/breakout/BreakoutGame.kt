package com.gamesuite.games.breakout

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Ninth game of the new-games batch (README Roadmap item 22), per
 * `docs/NEW_GAMES_BRAINSTORM.md`'s own "Breakout / Brick Breaker" entry: real-time
 * paddle-and-ball arcade action, reusing [com.gamesuite.games.airhockey.AirHockeyGame]'s
 * existing continuous-physics/frame-stepped-loop infrastructure (a `tick(dtSeconds)`
 * driven every frame from the UI's own `withFrameNanos` loop, normalized 0f..1f
 * coordinates, a `dtSeconds.coerceIn(0f, 0.05f)` clamp against a huge step after the
 * app is backgrounded) rather than inventing a new real-time shape from scratch — the
 * brainstorm doc's own reasoning for why this is lower-risk than Tower Defence.
 *
 * Coordinates: normalized 0f..1f on both axes, y=0 top / y=1 bottom, same convention as
 * AirHockeyGame — the UI multiplies by canvas width/height when drawing.
 *
 * No approved design doc exists for this one (unlike Edge Match/Party Toolkit) — the
 * brainstorm doc's own scope note is the only prior decision, and it doesn't flag this
 * entry as needing its own dedicated design pass the way it explicitly did for Tessel's
 * edge-matching puzzle and Boardgame Pal's toolkit. Scoped directly from that note plus
 * this app's own established idioms, same as Minesweeper/Sudoku/Lights Out/Dots and
 * Boxes/Connect Four were.
 *
 * DIFFICULTY, deliberately scoped differently than every solo puzzle in this batch:
 * Minesweeper/Sudoku/Lights Out/Edge Match all scale board SIZE per tier, because their
 * difficulty is about reasoning over a bigger state space. Breakout's difficulty is about
 * real-time REFLEXES instead, so the brick grid stays a FIXED 5x8 layout across every
 * tier and [difficultyConfig] instead scales [DifficultyConfig.paddleHalfWidth] (a
 * narrower paddle is a harder target to keep under the ball) and
 * [DifficultyConfig.ballSpeed] — a deliberate departure from the puzzle-genre idiom,
 * justified by genre difference, not an oversight.
 *
 * No daily-challenge route, also deliberate: every other solo game in this batch seeds
 * something genuinely random per day (Minesweeper's mines, Sudoku's clue removal, Color
 * Flood's board colors, Edge Match's seam colors) so "today's puzzle" is a real, distinct
 * instance worth comparing scores on. Breakout's brick grid is a fixed deterministic
 * layout with no randomness to seed at all — its replay value is real-time skill against
 * a constant board, not a distinct daily instance — so a `-daily` route here would be
 * cosmetic, not a real feature. [BreakoutStatsStore] tracks a plain best-score instead,
 * same "arcade high score" shape as the genre expects.
 *
 * SCOPE CUTS (honest MVP, same spirit as every other game's own documented cuts):
 *  - Single-hit bricks only, no multi-hit "tough brick" tier — a real, obvious future
 *    addition (per-brick HP + a distinct color per remaining hit), not built for a first
 *    version, same spirit as Dice staying d6-only in Party Toolkit.
 *  - Brick/paddle collision is a plain point-in-time circle-vs-rect test (see
 *    [circleRectHit]), NOT the swept-segment test [com.gamesuite.games.airhockey.AirHockeyGame.resolvePaddleCollision]
 *    uses to fix its own real, already-shipped paddle-tunneling bug. Deliberately not
 *    replicated here: the failure mode is far less consequential in this game (a missed
 *    brick hit just leaves that brick alive for a later pass; a missed paddle catch costs
 *    one life) than Air Hockey's own case (a missed paddle block there is an unfair
 *    conceded goal, a direct match-outcome impact), and it only manifests at the
 *    `dtSeconds` clamp's 0.05s worst case combined with the fastest ball speed this game
 *    ever reaches — normal 60fps frames (~0.0167s) are nowhere near it. [MAX_SPEED_MULTIPLIER]
 *    and each tier's [DifficultyConfig.ballSpeed] were chosen to keep that worst case
 *    comfortably rare, not to make it impossible — a real, named trade-off rather than an
 *    unexamined gap.
 *  - Ball launch angle is genuinely randomized (via [Random], not an injectable seed) —
 *    there's no puzzle-solvability or daily-challenge concern riding on it the way
 *    SlidingPuzzleGame.scramble's own random source has, so this doesn't need the
 *    injectable-`Random`-parameter testability idiom other engines use for their
 *    generators; [BreakoutGameTest] covers the deterministic collision/scoring math
 *    instead, where the actual logic worth verifying lives.
 */
class BreakoutGame : GameModule {
    override val gameId = "breakout"
    override val displayName = "Breakout"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    companion object {
        const val PADDLE_Y = 0.92f
        const val PADDLE_HALF_HEIGHT = 0.012f
        const val BALL_RADIUS = 0.014f
        const val RESTING_BALL_Y = PADDLE_Y - PADDLE_HALF_HEIGHT - BALL_RADIUS

        const val STARTING_LIVES = 3
        const val BRICK_ROWS = 5
        const val BRICK_COLS = 8
        const val BRICK_TOP_Y = 0.08f
        const val BRICK_AREA_HEIGHT = 0.42f
        const val BRICK_GAP = 0.006f

        /** How much [DifficultyConfig.ballSpeed] grows per level cleared (linear, not
         *  compounding) — see [launchBall]. Capped by [MAX_SPEED_MULTIPLIER] so an
         *  extended session doesn't run away toward the point-sample collision scope
         *  cut's own worst case (see the class KDoc). */
        const val LEVEL_SPEED_GROWTH = 0.06f
        const val MAX_SPEED_MULTIPLIER = 1.6f

        /** How much a paddle-hit's offset from the paddle's own center steers the ball's
         *  x velocity on the bounce (see [tick]'s paddle-collision block) — the classic
         *  Breakout "aim with where you catch it" feel, not just a flat vertical bounce. */
        const val PADDLE_ANGLE_FACTOR = 0.85f

        /** If a bounce ever leaves the ball's vertical speed under this fraction of its
         *  total speed, [tick] nudges it back up — guards the one real degenerate steady
         *  state this simplified physics has: a near-horizontal ball shuttling between the
         *  two side walls forever, never reaching the paddle or a brick row. Same
         *  "simplified physics has a known degenerate case, guard against it explicitly"
         *  principle as AirHockeyGame's own STALL_TIMEOUT_SECONDS, scoped to this game's
         *  actual (narrower, cheaper-to-guard) failure mode rather than a general stall
         *  timeout. */
        const val MIN_VERTICAL_SPEED_FRACTION = 0.35f
    }

    /** See the class KDoc's DIFFICULTY section — brick grid stays fixed; only paddle width
     *  and ball speed scale per tier. */
    data class DifficultyConfig(val paddleHalfWidth: Float, val ballSpeed: Float)

    private val difficultyConfig: Map<CpuDifficulty, DifficultyConfig> = mapOf(
        CpuDifficulty.EASY to DifficultyConfig(paddleHalfWidth = 0.13f, ballSpeed = 0.45f),
        CpuDifficulty.MEDIUM to DifficultyConfig(paddleHalfWidth = 0.10f, ballSpeed = 0.55f),
        CpuDifficulty.HARD to DifficultyConfig(paddleHalfWidth = 0.075f, ballSpeed = 0.68f)
    )

    data class BrickBrokenEvent(val seq: Long, val position: Offset, val row: Int, val scoreValue: Int)
    data class PaddleBounceEvent(val seq: Long, val position: Offset)
    data class WallBounceEvent(val seq: Long, val position: Offset)
    /** [gameOver] true when this was the player's last life — see [tick]'s life-lost block. */
    data class LifeLostEvent(val seq: Long, val livesRemaining: Int, val gameOver: Boolean)
    data class LevelClearedEvent(val seq: Long, val level: Int)

    data class BreakoutState(
        val paddleX: Float = 0.5f,
        val paddleHalfWidth: Float = 0.10f,
        val ballPos: Offset = Offset(0.5f, RESTING_BALL_Y),
        val ballVel: Offset = Offset(0f, 0f),
        /** False while the ball rests on the paddle waiting for [BreakoutGame.launchBall]. */
        val ballLaunched: Boolean = false,
        /** Row-major, size [rows] * [cols]; true = still standing. */
        val bricks: List<Boolean> = List(BRICK_ROWS * BRICK_COLS) { true },
        val rows: Int = BRICK_ROWS,
        val cols: Int = BRICK_COLS,
        val lives: Int = STARTING_LIVES,
        val score: Int = 0,
        val level: Int = 1,
        /** True once this RUN ends (lives hit 0) — distinct from [BreakoutGame.matchOver],
         *  which only becomes true once the whole session ends via [BreakoutGame.leaveSession].
         *  Same "per-round flag vs. session-level flag" split every solo game in this batch
         *  uses (e.g. LightsOutState.won vs. LightsOutGame.matchOver). */
        val gameOver: Boolean = false,
        val lastBrickBroken: BrickBrokenEvent? = null,
        val lastPaddleBounce: PaddleBounceEvent? = null,
        val lastWallBounce: WallBounceEvent? = null,
        val lastLifeLost: LifeLostEvent? = null,
        val lastLevelCleared: LevelClearedEvent? = null
    )

    val state = mutableStateOf(BreakoutState())

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-run —
     *  see [BreakoutState.gameOver]'s KDoc. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var eventSeq = 0L

    /** The best single-run score seen so far THIS SESSION (across any [playAgain] restarts) —
     *  reported by [leaveSession], same "session-level tally reported on the way out" shape as
     *  LightsOutGame's own `puzzlesSolved`. */
    private var bestScoreThisSession = 0

    override fun init(context: GameContext) {
        this.context = context
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        val cfg = difficultyConfig.getValue(difficulty)
        state.value = BreakoutState(
            paddleX = 0.5f,
            paddleHalfWidth = cfg.paddleHalfWidth,
            ballPos = Offset(0.5f, RESTING_BALL_Y)
        )
        matchOver.value = false
    }

    /**
     * Genuine no-ops — same real, already-shipped precedent as
     * [com.gamesuite.games.airhockey.AirHockeyGame.pause]/`resume`, not a fresh guess. There's
     * no wall-clock solve-timer here for a backgrounding gap to silently inflate (the bug every
     * solo PUZZLE game in this batch had to fix); the only state that advances with real time is
     * the physics [tick] loop itself, which the UI's own `withFrameNanos` loop simply stops
     * calling while the screen isn't being drawn, and [tick]'s own `dtSeconds.coerceIn(0f, 0.05f)`
     * clamp (see the class KDoc) already prevents a huge stale `dt` on resume from teleporting
     * the ball. Nothing extra to track.
     */
    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    fun movePaddle(x: Float) {
        val s = state.value
        if (matchOver.value || s.gameOver) return
        val clampedX = x.coerceIn(s.paddleHalfWidth, 1f - s.paddleHalfWidth)
        state.value = if (!s.ballLaunched) {
            // Ball rides along with the paddle while resting on it, pre-launch.
            s.copy(paddleX = clampedX, ballPos = Offset(clampedX, RESTING_BALL_Y))
        } else {
            s.copy(paddleX = clampedX)
        }
    }

    /** Called from the UI on a tap once the ball is resting on the paddle — gives it its
     *  initial velocity. A no-op if already launched (idempotent against a double-tap), matching
     *  every other engine's own idempotency guards on a single-shot action. */
    fun launchBall() {
        val s = state.value
        if (matchOver.value || s.gameOver || s.ballLaunched) return
        val cfg = difficultyConfig.getValue(difficulty)
        val speedMultiplier = (1f + LEVEL_SPEED_GROWTH * (s.level - 1)).coerceAtMost(MAX_SPEED_MULTIPLIER)
        val speed = cfg.ballSpeed * speedMultiplier
        // Launches within +-30 degrees of straight up -- genuinely randomized, see the class
        // KDoc's "ball launch angle" scope note for why this doesn't need an injectable seed.
        val angle = (Random.nextFloat() - 0.5f) * (Math.PI.toFloat() / 3f)
        state.value = s.copy(
            ballVel = Offset(speed * sin(angle), -speed * cos(angle)),
            ballLaunched = true
        )
    }

    /** Called every frame from the UI's game loop with elapsed seconds — same shape as
     *  AirHockeyGame.tick(dtSeconds). A no-op while the session or this run has already ended,
     *  or while the ball is still resting pre-launch (nothing to simulate yet). */
    fun tick(dtSeconds: Float) {
        val s = state.value
        if (matchOver.value || s.gameOver || !s.ballLaunched) return
        val dt = dtSeconds.coerceIn(0f, 0.05f) // see the class KDoc's tunneling scope-cut note

        var ball = s.ballPos + s.ballVel * dt
        var vel = s.ballVel
        var wallBounce = s.lastWallBounce
        var paddleBounce = s.lastPaddleBounce
        var brickBroken = s.lastBrickBroken

        // Side walls -- fully elastic.
        if (ball.x - BALL_RADIUS < 0f) {
            ball = ball.copy(x = BALL_RADIUS); vel = vel.copy(x = -vel.x)
            eventSeq++; wallBounce = WallBounceEvent(eventSeq, ball)
        } else if (ball.x + BALL_RADIUS > 1f) {
            ball = ball.copy(x = 1f - BALL_RADIUS); vel = vel.copy(x = -vel.x)
            eventSeq++; wallBounce = WallBounceEvent(eventSeq, ball)
        }
        // Top wall -- fully elastic; only reachable once the top row(s) are gone or through a gap.
        if (ball.y - BALL_RADIUS < 0f) {
            ball = ball.copy(y = BALL_RADIUS); vel = vel.copy(y = -vel.y)
            eventSeq++; wallBounce = WallBounceEvent(eventSeq, ball)
        }

        // Bricks -- at most one resolved per frame; see the class KDoc's collision scope cut.
        var bricks = s.bricks
        var score = s.score
        if (bricks.any { it }) {
            for (i in bricks.indices) {
                if (!bricks[i]) continue
                val rect = brickRect(i, s.rows, s.cols)
                val hit = circleRectHit(ball, BALL_RADIUS, rect)
                if (!hit.hit) continue
                bricks = bricks.toMutableList().also { it[i] = false }
                val row = i / s.cols
                val value = scoreForRow(row, s.rows)
                score += value
                eventSeq++
                brickBroken = BrickBrokenEvent(eventSeq, rect.center, row, value)
                vel = if (hit.hitVertical) vel.copy(y = -vel.y) else vel.copy(x = -vel.x)
                break
            }
        }

        // Degenerate-rally guard -- see MIN_VERTICAL_SPEED_FRACTION's KDoc.
        val speed = vel.getDistance()
        if (speed > 0f && abs(vel.y) < speed * MIN_VERTICAL_SPEED_FRACTION) {
            val vySign = if (vel.y >= 0f) 1f else -1f
            val vxSign = if (vel.x >= 0f) 1f else -1f
            val newVy = vySign * speed * MIN_VERTICAL_SPEED_FRACTION
            val newVx = vxSign * sqrt((speed * speed - newVy * newVy).coerceAtLeast(0f))
            vel = Offset(newVx, newVy)
        }

        // Paddle -- only while the ball is heading down, matching a real paddle's one-sided face.
        if (vel.y > 0f) {
            val paddleRect = Rect(
                s.paddleX - s.paddleHalfWidth, PADDLE_Y - PADDLE_HALF_HEIGHT,
                s.paddleX + s.paddleHalfWidth, PADDLE_Y + PADDLE_HALF_HEIGHT
            )
            val hit = circleRectHit(ball, BALL_RADIUS, paddleRect)
            if (hit.hit) {
                val hitOffset = ((ball.x - s.paddleX) / s.paddleHalfWidth).coerceIn(-1f, 1f)
                val speedNow = vel.getDistance()
                val newVx = hitOffset * speedNow * PADDLE_ANGLE_FACTOR
                val newVy = -sqrt((speedNow * speedNow - newVx * newVx).coerceAtLeast(speedNow * speedNow * 0.1f))
                vel = Offset(newVx, newVy)
                ball = ball.copy(y = paddleRect.top - BALL_RADIUS)
                eventSeq++; paddleBounce = PaddleBounceEvent(eventSeq, ball)
            }
        }

        // Life lost -- ball fell past the paddle.
        if (ball.y - BALL_RADIUS > 1f) {
            val livesRemaining = s.lives - 1
            val over = livesRemaining <= 0
            eventSeq++
            val lifeLost = LifeLostEvent(eventSeq, livesRemaining, over)
            if (over) bestScoreThisSession = maxOf(bestScoreThisSession, score)
            state.value = s.copy(
                lives = livesRemaining,
                score = score,
                bricks = bricks,
                gameOver = over,
                ballPos = Offset(s.paddleX, RESTING_BALL_Y),
                ballVel = Offset(0f, 0f),
                ballLaunched = false,
                lastLifeLost = lifeLost,
                lastWallBounce = wallBounce,
                lastBrickBroken = brickBroken,
                lastPaddleBounce = paddleBounce
            )
            return
        }

        // Level cleared -- every brick gone; regenerate a fresh full grid, one notch faster.
        if (bricks.none { it }) {
            val level = s.level + 1
            eventSeq++
            state.value = s.copy(
                level = level,
                score = score,
                bricks = List(s.rows * s.cols) { true },
                ballPos = Offset(s.paddleX, RESTING_BALL_Y),
                ballVel = Offset(0f, 0f),
                ballLaunched = false,
                lastLevelCleared = LevelClearedEvent(eventSeq, level),
                lastBrickBroken = brickBroken,
                lastWallBounce = wallBounce,
                lastPaddleBounce = paddleBounce
            )
            return
        }

        state.value = s.copy(
            ballPos = ball,
            ballVel = vel,
            score = score,
            bricks = bricks,
            lastWallBounce = wallBounce,
            lastPaddleBounce = paddleBounce,
            lastBrickBroken = brickBroken
        )
    }

    /** Called from the finished-run panel's "Play Again" button — keeps [bestScoreThisSession]
     *  running, same as every solo game's own `playAgain()`. A no-op unless the current run has
     *  actually ended. */
    fun playAgain() {
        if (matchOver.value || !state.value.gameOver) return
        startMatch()
    }

    /** Called from the finished-run panel's (or in-progress screen's) "Back to Menu" button —
     *  ends the whole session, reporting [bestScoreThisSession] — same shape as
     *  LightsOutGame.leaveSession() reporting `puzzlesSolved`. */
    fun leaveSession() {
        if (matchOver.value) return
        bestScoreThisSession = maxOf(bestScoreThisSession, state.value.score)
        val player = context.players.getOrNull(context.localPlayerIndex)
        val result = GameResult(
            scores = if (player != null) listOf(
                PlayerScore(playerId = player.playerId, score = bestScoreThisSession, isWinner = bestScoreThisSession > 0)
            ) else emptyList()
        )
        endMatch(result)
    }

    /** Top row (index 0) is worth the most, same classic Arkanoid/Breakout convention — a
     *  concrete reason to prefer clearing upward rather than picking off the easiest bricks. */
    private fun scoreForRow(row: Int, rows: Int): Int = (rows - row) * 10

    private fun brickRect(index: Int, rows: Int, cols: Int): Rect {
        val col = index % cols
        val row = index / cols
        val brickWidth = (1f - BRICK_GAP * (cols + 1)) / cols
        val brickHeight = (BRICK_AREA_HEIGHT - BRICK_GAP * (rows + 1)) / rows
        val left = BRICK_GAP + col * (brickWidth + BRICK_GAP)
        val top = BRICK_TOP_Y + BRICK_GAP + row * (brickHeight + BRICK_GAP)
        return Rect(left, top, left + brickWidth, top + brickHeight)
    }

    /** Result of a circle-vs-axis-aligned-rect overlap test. [hitVertical] means the ball's
     *  center x fell within the rect's own horizontal span (approached from directly above or
     *  below) — reflect `vel.y`. Otherwise it approached from a side, or a corner (this
     *  simplified test resolves a corner hit the same as a side hit — see the class KDoc's
     *  collision scope cut) — reflect `vel.x`. */
    private data class CircleRectHit(val hit: Boolean, val hitVertical: Boolean)

    private fun circleRectHit(ball: Offset, radius: Float, rect: Rect): CircleRectHit {
        val closestX = ball.x.coerceIn(rect.left, rect.right)
        val closestY = ball.y.coerceIn(rect.top, rect.bottom)
        val dx = ball.x - closestX
        val dy = ball.y - closestY
        if (dx * dx + dy * dy >= radius * radius) return CircleRectHit(hit = false, hitVertical = false)
        return CircleRectHit(hit = true, hitVertical = ball.x in rect.left..rect.right)
    }
}
