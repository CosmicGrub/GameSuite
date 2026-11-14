package com.gamesuite.games.snake

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import kotlin.random.Random

/** Where a run is: waiting to start, the 3-2-1 lead-in, moving, or finished. */
enum class SnakePhase { IDLE, COUNTDOWN, PLAYING, OVER }

/**
 * What the UI draws. Replaced (never mutated) after every tick, countdown beat or turn.
 * [snake] is head first; [food] is -1 once the board is full.
 */
data class SnakeState(
    val cols: Int = 16,
    val rows: Int = 16,
    val wrap: Boolean = false,
    val snake: List<Int> = emptyList(),
    val direction: SnakeDirection = SnakeDirection.RIGHT,
    val food: Int = -1,
    val foods: Int = 0,
    val score: Int = 0,
    val level: Int = 1,
    val status: SnakeStatus = SnakeStatus.RUNNING,
    val deathCause: SnakeDeathCause? = null,
    /** Bumped by every [SnakeGame.startMatch], so the UI can tell two runs of the same setup apart. */
    val runSeq: Int = 0
)

/**
 * Snake, the real-time arcade classic. A thin [GameModule] shell around the pure [SnakeEngine]:
 * it owns the run lifecycle (3-2-1 countdown, play, game over, play again), pause, and the
 * session tally reported on the way out.
 *
 * The game object outlives the screen's composition (a rotation recreates the composition, not
 * this object), so the run, its pause flag and the countdown all survive: the screen only calls
 * [startMatch] when [started] is false. The tick LOOP lives in the screen (a delay scaled by
 * [tickMillis]); this class never touches a clock, which keeps it testable.
 *
 * Not resumable: a real-time run has no sensible save point, so there is no snapshot.
 * No Undo or Hint either.
 *
 * PAUSE: [pause] freezes a countdown or a run in progress and is idempotent; [resume] (the host
 * Activity's onResume) deliberately leaves the run paused so coming back never drops the player
 * into a moving snake; the player opts back in with [resumePlay].
 */
class SnakeGame(private val randomSource: () -> Random = { Random.Default }) : GameModule {
    override val gameId = "snake"
    override val displayName = "Snake"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf(SnakeState())
    val phase = mutableStateOf(SnakePhase.IDLE)

    /** 3, 2, 1 during [SnakePhase.COUNTDOWN]; 0 otherwise. */
    val countdown = mutableStateOf(0)

    val paused = mutableStateOf(false)

    /** True only once the whole session ends (Back to Menu), not per run. */
    val matchOver = mutableStateOf(false)

    /** How many runs this session have ended (died or cleared the board). */
    val runsFinished = mutableStateOf(0)

    /** True once [startMatch] has been called; the screen uses it so a rotation never restarts the run. */
    val started = mutableStateOf(false)

    var config: SnakeConfig = SnakeConfig()
        private set

    private var engine: SnakeEngine? = null
    private var context: GameContext? = null
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var bestScoreThisSession = 0
    private var runSeq = 0

    /** Applies the Hub's per-launch choices. Takes effect on the NEXT [startMatch]. */
    fun configure(extras: Map<String, String>) {
        config = SnakeConfig.fromExtras(extras)
    }

    fun configure(newConfig: SnakeConfig) {
        config = newConfig
    }

    override fun init(context: GameContext) {
        this.context = context
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        engine = SnakeEngine(config, randomSource())
        runSeq += 1
        started.value = true
        matchOver.value = false
        paused.value = false
        countdown.value = COUNTDOWN_FROM
        phase.value = SnakePhase.COUNTDOWN
        publish()
    }

    /** Milliseconds the loop should wait before the next [step]. */
    fun tickMillis(): Long = engine?.tickMillis() ?: SnakeEngine.tickMillis(config.speed, 1)

    /** One countdown beat (the loop calls this once per second). Ignored unless counting down and not paused. */
    fun tickCountdown() {
        if (phase.value != SnakePhase.COUNTDOWN || paused.value || matchOver.value) return
        val next = countdown.value - 1
        if (next <= 0) {
            countdown.value = 0
            phase.value = SnakePhase.PLAYING
        } else {
            countdown.value = next
        }
    }

    /** Queues a turn (swipe, D-pad, key). Accepted during the countdown too, so the first move can already turn. */
    fun steer(dir: SnakeDirection): Boolean {
        val e = engine ?: return false
        if (matchOver.value || paused.value) return false
        if (phase.value != SnakePhase.COUNTDOWN && phase.value != SnakePhase.PLAYING) return false
        return e.queueDirection(dir)
    }

    /** One simulation tick. Ignored unless playing and not paused. */
    fun step(): SnakeStepResult {
        val e = engine ?: return SnakeStepResult.NOOP
        if (phase.value != SnakePhase.PLAYING || paused.value || matchOver.value) return SnakeStepResult.NOOP
        val result = e.step()
        publish()
        if (e.status != SnakeStatus.RUNNING) {
            phase.value = SnakePhase.OVER
            paused.value = false
            runsFinished.value += 1
            bestScoreThisSession = maxOf(bestScoreThisSession, e.score)
        }
        return result
    }

    override fun pause() {
        if (paused.value || matchOver.value) return
        if (phase.value != SnakePhase.COUNTDOWN && phase.value != SnakePhase.PLAYING) return
        paused.value = true
    }

    /** The host Activity's onResume: leaves a paused run paused (see the class KDoc). */
    override fun resume() {}

    /** The player's own "carry on". A no-op when not paused. */
    fun resumePlay() {
        if (!paused.value) return
        paused.value = false
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** "Play Again" on the result panel. A no-op unless the current run has ended. */
    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value || phase.value != SnakePhase.OVER) return
        startMatch()
    }

    /**
     * Ends the session, reporting the best FINISHED run's score so the stats record it. A run still
     * in progress never counts (the screen sends mid-run exits through [abortMatch] instead).
     */
    fun leaveSession() {
        if (matchOver.value) return
        val player = context?.let { it.players.getOrNull(it.localPlayerIndex) }
        val result = GameResult(
            scores = if (player != null) listOf(
                PlayerScore(player.playerId, bestScoreThisSession, isWinner = bestScoreThisSession > 0)
            ) else emptyList()
        )
        endMatch(result)
    }

    private fun publish() {
        val e = engine ?: return
        state.value = SnakeState(
            cols = e.cols,
            rows = e.rows,
            wrap = e.config.wrap,
            snake = e.snake,
            direction = e.direction,
            food = e.food,
            foods = e.foods,
            score = e.score,
            level = e.level,
            status = e.status,
            deathCause = e.deathCause,
            runSeq = runSeq
        )
    }

    companion object {
        const val COUNTDOWN_FROM = 3

        /** A finished run sets a record only when it scored something and beat the old best. */
        fun isNewBestScore(existing: Int?, score: Int): Boolean =
            score > 0 && (existing == null || score > existing)
    }
}
