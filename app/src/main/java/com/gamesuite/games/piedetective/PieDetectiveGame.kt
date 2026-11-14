package com.gamesuite.games.piedetective

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * Pie Detective: a cooperative same-screen deduction game for two to four players (Sidekick seats may fill empty
 * seats). Everyone catches the thief or tries again TOGETHER. All the rules live in a [PieEngine]; this class is the
 * shell glue: the lifecycle, the Compose state the screen reads, and the resume snapshot. Same screen only: no
 * Nearby, no Online.
 *
 * Set [band], [handicap], [difficulty], [hints] and [autoFlip] before [init] / [startMatch] (the screen does it from
 * the Hub's launch options). The engine is injectable so tests can pass a fake; the default is the stub until phase 0
 * lands the real one.
 */
class PieDetectiveGame(
    private val random: Random = Random.Default,
    internal val engine: PieEngine = StubPieEngine()
) : GameModule, ResumableGame {
    override val gameId = "pie-detective"
    override val displayName = "Pie Detective"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<PieState?>(null)
    val matchOver = mutableStateOf(false)

    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var band: PieBand = PieBand.MEDIUM
    var handicap: String = "none"
    var hints: Boolean = true
    var autoFlip: Boolean = false

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this

    private fun seats(): List<PieSeat> = context.players.map { PieSeat(it.playerId, it.displayName, it.isBot) }

    private fun configure() {
        engine.band = band
        engine.handicapSpec = handicap
        engine.difficulty = difficulty
        engine.hints = hints
        engine.autoFlip = autoFlip
    }

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            state.value = null
            configure()
            engine.setup(seats())
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    @Synchronized
    override fun startMatch() {
        matchOver.value = false
        configure()
        engine.startBook(random.nextLong())
        commit()
    }

    @Synchronized
    override fun pause() { engine.pause() }

    @Synchronized
    override fun resume() { engine.resume() }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /**
     * Ends the session. Leaving before the first case finished is an abort (never recorded to stats). Otherwise the
     * team result follows the book rule (design doc O3): a finished book wins when cases solved reach
     * [PieRules.bookWinThreshold]; leaving early wins when at least one case was solved.
     */
    fun leaveSession() {
        if (matchOver.value) return
        val s = engine.session
        if (s.casesPlayed == 0) {
            abortMatch()
            return
        }
        val won = if (s.bookFinished) s.casesSolved >= PieRules.bookWinThreshold(s.casesInBook) else s.casesSolved >= 1
        endMatch(GameResult(scores = context.players.map { PlayerScore(it.playerId, s.casesSolved, isWinner = won) }))
    }

    /** A new Case Book with a fresh seed. */
    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        configure()
        engine.startBook(random.nextLong())
        commit()
    }

    @Synchronized
    fun passAcknowledged(seat: Int): PieResult = act { engine.passAcknowledged(seat) }

    @Synchronized
    fun chooseTrait(seat: Int, trait: PieTrait, lucky: Boolean = false): PieResult = act { engine.chooseTrait(seat, trait, lucky) }

    @Synchronized
    fun peekHeld(seat: Int): PieResult = act { engine.peekHeld(seat) }

    @Synchronized
    fun showEveryone(seat: Int): PieResult = act { engine.showEveryone(seat) }

    @Synchronized
    fun flip(seat: Int, suspectId: String): PieResult = act { engine.flip(seat, suspectId) }

    @Synchronized
    fun startCatch(seat: Int): PieResult = act { engine.startCatch(seat) }

    @Synchronized
    fun catchSuspect(seat: Int, suspectId: String): PieResult = act { engine.catchSuspect(seat, suspectId) }

    @Synchronized
    fun cancelToChoose(seat: Int): PieResult = act { engine.cancelToChoose(seat) }

    @Synchronized
    fun nextCase(): PieResult = act { engine.nextCase() }

    /** The screen calls this after its 1.2 s pause when it is a Sidekick's turn. True if the Sidekick acted. */
    @Synchronized
    fun sidekickStep(): Boolean {
        if (state.value == null || matchOver.value) return false
        val acted = engine.sidekickStep()
        commit()
        return acted
    }

    /** "Sidekick hint": the suggested next step for the seat whose turn it is. */
    @Synchronized
    fun hint(): PieHint? = if (state.value == null || matchOver.value) null else engine.hint()

    private fun act(block: () -> PieResult): PieResult {
        if (state.value == null || matchOver.value) return PieResult.NOT_ACTIVE
        val r = block()
        commit()
        return r
    }

    private fun commit() {
        state.value = engine.state()
    }

    // ---- resume -----------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) {
        try {
            if (matchOver.value || state.value == null) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            val save = engine.toSave() ?: return null
            PieSaveCodec.encode(save)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val save = PieSaveCodec.decode(snapshot, seats()) ?: return false
        configure()
        if (!engine.restore(save)) return false
        band = engine.band
        handicap = engine.handicapSpec
        difficulty = engine.difficulty
        hints = engine.hints
        autoFlip = engine.autoFlip
        matchOver.value = false
        commit()
        return true
    }
}
