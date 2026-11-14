package com.gamesuite.games.actitout

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import kotlin.random.Random

/**
 * Act It Out, a charades-style party game: ONE device is the host, the timer and the card dealer. All rules live in
 * the Compose-free [ActItOutEngine]; this class adds the shell contract, the setup step, the finished-match result
 * and save / resume.
 *
 * STAGES. [setup] (teams, bands, time, rounds) comes first, then [engine] runs the match. [version] is bumped after
 * every change so the screen recomposes; the screen reads engine fields after reading it.
 *
 * RESULT. A finished match gives each player their side's score, and every player on a top side is a winner.
 * Leaving after a finished match reports that match ([leaveSession]); leaving mid-match is an abort that never
 * counts for stats.
 */
class ActItOutGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val minCardVisibleMs: Long = ActItOutRules.MIN_CARD_VISIBLE_MS
) : GameModule, ResumableGame {
    override val gameId = "act-it-out"
    override val displayName = "Act It Out"
    override val category = GameCategory.OTHER
    override val minPlayers = ActItOutRules.MIN_PLAYERS
    override val maxPlayers = ActItOutRules.MAX_PLAYERS
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)

    val version = mutableIntStateOf(0)
    val matchOver = mutableStateOf(false)

    /** Set by the screen from the Hub's extras before [init]. */
    var defaults: ActItOutDefaults = ActItOutDefaults()

    /** The name for a blank player (0-based seat), set by the screen so it is in the app's language. */
    var defaultPlayerName: (Int) -> String = { "Player ${it + 1}" }

    private var onDiscardSave: (() -> Unit)? = null

    /** The shell hook that deletes this match's resume save; the screen supplies it. */
    fun setOnDiscardSave(listener: () -> Unit) {
        onDiscardSave = listener
    }

    private fun discardSave() {
        try { onDiscardSave?.invoke() } catch (_: Exception) { }
    }

    var setup: ActItOutSetup? = null
        private set
    var engine: ActItOutEngine? = null
        private set

    /** The result of the latest FINISHED match this session, null until one finishes. */
    var finishedResult: GameResult? = null
        private set

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    val players get() = context.players

    fun nowMs(): Long = clock()

    private fun bump() {
        version.intValue = version.intValue + 1
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        finishedResult = null
        engine = null
        setup = null
        bump()
    }

    /** Opens the setup step with the Hub's choices. */
    override fun startMatch() {
        matchOver.value = false
        engine = null
        setup = newSetup()
        bump()
    }

    private fun newSetup() = ActItOutSetup(
        playerIds = context.players.map { it.playerId },
        names = context.players.map { it.displayName },
        random = random,
        defaults = defaults,
        defaultName = defaultPlayerName
    )

    /** The screen edited [setup]; redraw. */
    fun setupChanged() = bump()

    /** Setup -> the match. Returns false when the setup is not playable. */
    fun beginMatch(): Boolean {
        val s = setup ?: return false
        if (!s.canStart()) return false
        engine = ActItOutEngine(s.build(), random, clock, minCardVisibleMs = minCardVisibleMs)
        setup = null
        finishedResult = null
        bump()
        return true
    }

    /** After a finished match: back to setup with the same choices, for another go. */
    fun playAgain() {
        val e = engine ?: return
        // The finished match must not stay resumable (it would be played out and counted a second time).
        discardSave()
        setup = ActItOutSetup.from(e.config, random, defaults, defaultPlayerName)
        engine = null
        bump()
    }

    // ---- engine calls (each bumps the version) ----

    fun startTurn() { if (engine?.startTurn() == true) bump() }
    fun tick() { if (engine?.tick() == true) bump() }

    /** A tap can land on the expiry frame and move the engine to TIMES_UP: that is a change too, so bump on any phase change. */
    fun markCorrect(): Int {
        val e = engine ?: return 0
        val before = e.phase
        val p = e.markCorrect()
        if (p > 0 || e.phase != before) bump()
        return p
    }

    fun skip(): Boolean {
        val e = engine ?: return false
        val before = e.phase
        val ok = e.skip()
        if (ok || e.phase != before) bump()
        return ok
    }

    fun resolveLastCard(gotIt: Boolean): Int { val p = engine?.resolveLastCard(gotIt) ?: 0; bump(); return p }

    fun continueAfterSummary() {
        val e = engine ?: return
        if (e.continueAfterSummary()) {
            if (e.isOver) {
                recordFinished(e)
                discardSave()
            }
            bump()
        }
    }

    fun continueFromScoreboard() { if (engine?.continueFromScoreboard() == true) bump() }

    private fun recordFinished(e: ActItOutEngine) {
        val leaders = e.leaders().toSet()
        val best = e.scores.maxOrNull() ?: 0
        val scores = ArrayList<PlayerScore>()
        e.config.sides.forEachIndexed { i, side ->
            side.members.forEach { seat ->
                scores.add(PlayerScore(e.config.playerIds[seat], e.scores[i], isWinner = best > 0 && i in leaders))
            }
        }
        finishedResult = GameResult(scores = scores)
    }

    /** Backgrounded: a running turn goes back behind the curtain (see [ActItOutEngine.pause]); redraw if so. */
    override fun pause() {
        if (engine?.pause() == true) bump()
    }

    override fun resume() {
        if (engine?.phase == ActItOutPhase.PLAYING) {
            engine?.resume()
            bump()
        }
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** "Back to menu" after at least one finished match: reports that match. Falls back to an abort. */
    fun leaveSession() {
        if (matchOver.value) return
        val r = finishedResult
        if (r != null) endMatch(r) else abortMatch()
    }

    // ---- resume ----

    override fun snapshot(): String? {
        if (matchOver.value) return null
        if (!this::context.isInitialized) return null
        if (context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
        return engine?.snapshot()
    }

    override fun restore(snapshot: String): Boolean {
        try {
            if (!this::context.isInitialized) return false
            val e = ActItOutEngine.restore(snapshot, random, clock, minCardVisibleMs) ?: return false
            val ids = context.players.map { it.playerId }
            if (e.config.playerIds != ids) return false
            engine = e
            setup = null
            finishedResult = null
            matchOver.value = false
            bump()
            return true
        } catch (_: Exception) {
            return false
        }
    }
}
