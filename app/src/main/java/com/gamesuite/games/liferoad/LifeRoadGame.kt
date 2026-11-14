package com.gamesuite.games.liferoad

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.settings.CpuDifficulty

/**
 * Life Road for 2 to 6 seats, any mix of people and CPUs, all on one device (first release: not networked).
 * A thin wrapper in the house pattern (compare SlideClimbGame): the rules live in a [LifeRoadEngine], this class
 * owns the Compose-observable state, the session tally, and save / resume.
 *
 * Options (set BEFORE [startMatch]; a restored save carries its own): [config]. The Hub's `LaunchOptions.extras`
 * map onto it as `rules`, `length`, `handicap`, `handicapFor`, `catchUp`, with `difficulty` from the standard key.
 *
 * Randomness: only [LifeRoadRng] with the seed in the config. [seedSource] picks a fresh seed per board; tests pass
 * a constant. Undo is not offered (a spin is chance, so taking it back would only allow a re-spin).
 *
 * SCAFFOLD: with [StubLifeRoadEngine] every [perform] is refused and no CPU step changes the state. The wrapper is
 * the final shape; only the engine behind it is a stub.
 */
class LifeRoadGame(
    private val engine: LifeRoadEngine = StubLifeRoadEngine(),
    private val seedSource: () -> Long = { System.nanoTime() }
) : GameModule, ResumableGame {
    override val gameId = "life-road"
    override val displayName = "Life Road"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 6
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<LifeRoadState?>(null)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)

    /** Rules for the NEXT board (the Hub's options). The seed is replaced per board unless [fixedSeed] is set. */
    var config: LifeRoadConfig = LifeRoadConfig()
    var fixedSeed: Long? = null
    var firstOpenerSeat: Int = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStartingSeat = 0

    override fun init(context: GameContext) {
        require(context.players.size in minPlayers..maxPlayers) { "Life Road needs 2 to 6 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        nextStartingSeat = if (firstOpenerSeat in context.players.indices) firstOpenerSeat else 0
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // --- Queries ---------------------------------------------------------------------------

    fun seatOnTurn(): Int? = state.value?.currentSeat

    /** True when a person (not a CPU) is to act on [s]. */
    fun isMyTurn(s: LifeRoadState): Boolean = !matchOver.value && !s.over && !s.players[s.currentSeat].isBot

    fun botToMove(s: LifeRoadState): Boolean = !matchOver.value && !s.over && s.players[s.currentSeat].isBot

    fun explain(action: LifeRoadAction): LifeRoadReject {
        val s = state.value ?: return LifeRoadReject.GAME_OVER
        return engine.explain(s, s.currentSeat, action)
    }

    fun legalActions(): List<LifeRoadAction> {
        val s = state.value ?: return emptyList()
        return engine.legalActions(s, s.currentSeat)
    }

    fun scoreCards(): List<ScoreCard> = state.value?.let { engine.scoreCards(it) } ?: emptyList()

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        val n = context.players.size
        val seed = fixedSeed ?: seedSource()
        matchOver.value = false
        val fresh = engine.newGame(config.copy(seed = seed), context.players)
        val opener = nextStartingSeat % n
        // The opener is the seat that spins first once everyone has chosen their start road.
        state.value = fresh.copy(currentSeat = opener)
        nextStartingSeat = (nextStartingSeat + 1) % n
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session, recording the boards won so far (a lone leader is the winner). */
    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.value
        val best = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == best }
        endMatch(
            GameResult(
                scores = context.players.map {
                    val w = wins[it.playerId] ?: 0
                    PlayerScore(it.playerId, w, isWinner = best > 0 && w == best && leaders == 1)
                }
            )
        )
    }

    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    // --- Acting ----------------------------------------------------------------------------

    /** A person's action for the seat on turn. True when accepted. Records a board win when the game ends. */
    fun perform(action: LifeRoadAction): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || !isMyTurn(s)) return false
        return step(s, action)
    }

    /** One CPU action for the seat on turn; does nothing unless [botToMove]. */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        step(s, engine.botAction(s, config.difficulty))
    }

    private fun step(s: LifeRoadState, action: LifeRoadAction): Boolean {
        val result = engine.apply(s, s.currentSeat, action)
        if (!result.accepted) return false
        state.value = result.state
        if (result.state.over && !s.over) {
            val wins = sessionWins.value.toMutableMap()
            for (seat in result.state.winnerSeats) {
                val id = result.state.players[seat].playerId
                wins[id] = (wins[id] ?: 0) + 1
            }
            sessionWins.value = wins
        }
        return true
    }

    // --- Resume ----------------------------------------------------------------------------

    /** The save text, or null when there is nothing to resume (no board, or the board is over). */
    override fun snapshot(): String? {
        return try {
            val s = state.value ?: return null
            if (matchOver.value) return null
            LifeRoadSnapshots.encode(s, SessionExtras(sessionWins.value, nextStartingSeat))
        } catch (_: Exception) {
            null
        }
    }

    /** True when [snapshot] text was valid for the current roster and was loaded. See [LifeRoadSnapshots.decode] for the checks. */
    override fun restore(snapshot: String): Boolean {
        val (restored, extras) = LifeRoadSnapshots.decode(snapshot, context.players) ?: return false
        matchOver.value = false
        state.value = restored
        config = restored.config
        sessionWins.value = extras.sessionWins
        nextStartingSeat = extras.nextStartingSeat
        return true
    }

    /** The CPU level used for new boards; a restored save carries its own. */
    var difficulty: CpuDifficulty
        get() = config.difficulty
        set(value) {
            config = config.copy(difficulty = value)
        }
}
