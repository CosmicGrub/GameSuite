package com.gamesuite.games.partypack

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class PdSnapshot(
    val version: Int = 1,
    val seats: Int,
    val format: String,
    val powerups: Boolean,
    val relaxed: Boolean,
    val oneSwitch: Boolean,
    val lives: List<Int>,
    val score: List<Int>,
    val alive: List<Boolean>,
    val rallies: Int,
    val matchNo: Int,
    val sessionWins: Map<String, Int> = emptyMap()
)

private val pdJson = Json { ignoreUnknownKeys = true }

/**
 * Paddle Duel as a [GameModule]: the engine plus the clock, the roster and the session tally.
 *
 * SAME SCREEN ONLY in this version: there is no Nearby or Online mode (every seat is a person at this
 * device or a computer). The engine ([PaddleDuelEngine]) is pure and deterministic; this class only turns
 * the injected [clock] into elapsed play time and exposes Compose state for the screen.
 *
 * RESUME: a snapshot holds the score, lives and settings. A rally in the air is not saved: restoring
 * starts a fresh serve with the saved score.
 */
class PaddleDuelGame(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val seedSource: () -> Long = { System.nanoTime() }
) : GameModule, ResumableGame {
    override val gameId = "paddle-duel"
    override val displayName = "Paddle Duel"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    /** Bumped on every tick and every command so a Canvas that reads it redraws. */
    val frame = mutableIntStateOf(0)
    val matchOver = mutableStateOf(false)

    /** Bumped when a new engine replaces the old one (a new match or a restore), so composition re-reads [engine]. */
    val matchSerial = mutableIntStateOf(0)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())

    /** Set by the screen from the Hub before [init]. */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var formatId: String = PdFormat.DEFAULT.id
    var powerups: Boolean = false
    var relaxed: Boolean = false
    var oneSwitch: Boolean = false

    /** 1.0 normally; below 1 slows the play (the app's game-speed setting). */
    var timeScale: Double = 1.0

    lateinit var context: GameContext
        private set
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    var engine: PaddleDuelEngine? = null
        private set
    private var matchNo = 0
    private var lastClock = -1L
    private var paused = false

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        engine = null
        matchNo = 0
        lastClock = -1L
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    private fun buildConfig(seed: Long): PdConfig = PdConfig(
        seats = context.players.take(PpSeatLayout.MAX_SEATS).map { PdSeat(bot = it.isBot, level = difficulty, oneSwitch = !it.isBot && oneSwitch) },
        format = PdFormat.parse(formatId),
        powerups = powerups,
        relaxed = relaxed,
        seed = seed
    )

    override fun startMatch() {
        matchOver.value = false
        matchNo++
        engine = PaddleDuelEngine(buildConfig(seedSource()))
        lastClock = -1L
        matchSerial.intValue++
        frame.intValue++
    }

    override fun pause() { paused = true }

    override fun resume() { paused = false; lastClock = -1L }

    /** Called every frame by the screen. */
    fun tick() {
        val e = engine ?: return
        val now = clock()
        if (paused || lastClock < 0L) { lastClock = now; return }
        val dt = ((now - lastClock) / 1000.0 * timeScale).coerceIn(0.0, 0.25)
        lastClock = now
        if (!e.over) e.advance(dt)
        frame.intValue++
    }

    fun drainEvents(): List<PdEvent> = engine?.drainEvents() ?: emptyList()

    fun isBotSeat(seat: Int): Boolean = context.players.getOrNull(seat)?.isBot == true

    fun setTarget(seat: Int, along: Double?) { engine?.setTarget(seat, along) }
    fun setHold(seat: Int, dir: Int) { engine?.setHold(seat, dir) }
    fun nudge(seat: Int, dir: Int) { engine?.nudge(seat, dir); frame.intValue++ }
    fun pressSwitch(seat: Int) { engine?.pressSwitch(seat); frame.intValue++ }

    private fun finishedScores(e: PaddleDuelEngine): List<PlayerScore> = context.players.take(e.n).mapIndexed { i, p ->
        PlayerScore(p.playerId, if (e.config.format.points) e.score[i] else e.lives[i], isWinner = i == e.winner)
    }

    /** After a finished match: tally it for the session. */
    private fun tally(e: PaddleDuelEngine) {
        val w = context.players.getOrNull(e.winner)?.playerId ?: return
        sessionWins.value = sessionWins.value + (w to ((sessionWins.value[w] ?: 0) + 1))
    }

    private var tallied = -1

    /** The screen calls this once it notices the match is over (sound, celebration, tally). Idempotent per match. */
    fun noteFinished() {
        val e = engine ?: return
        if (e.over && tallied != matchNo) { tallied = matchNo; tally(e) }
    }

    fun playAgain() {
        if (matchOver.value) return
        noteFinished()
        startMatch()
    }

    /** Ends the session after at least one finished match: reports the matches won (only a strict leader wins). */
    fun leaveSession() {
        if (matchOver.value) return
        noteFinished()
        val wins = sessionWins.value
        val best = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == best }
        val scores = context.players.map {
            val w = wins[it.playerId] ?: 0
            PlayerScore(it.playerId, w, isWinner = best > 0 && w == best && leaders == 1)
        }
        endMatch(GameResult(scores = scores))
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    // ---- resume ----------------------------------------------------------------------------

    override fun snapshot(): String? {
        val e = engine ?: return null
        if (e.over || matchOver.value) return null
        val s = e.scores()
        return pdJson.encodeToString(
            PdSnapshot.serializer(),
            PdSnapshot(
                seats = e.n, format = e.config.format.id, powerups = e.config.powerups, relaxed = e.config.relaxed,
                oneSwitch = oneSwitch, lives = s.lives, score = s.score, alive = s.alive, rallies = s.rallies,
                matchNo = matchNo, sessionWins = sessionWins.value
            )
        )
    }

    override fun restore(snapshot: String): Boolean {
        if (!this::context.isInitialized) return false
        val snap = try { pdJson.decodeFromString(PdSnapshot.serializer(), snapshot) } catch (_: Exception) { return false }
        if (snap.version != 1 || snap.seats != context.players.size.coerceAtMost(PpSeatLayout.MAX_SEATS)) return false
        if (snap.format !in PdFormat.ALL.map { it.id }) return false
        if (snap.matchNo < 1 || snap.matchNo > 10_000) return false
        val ids = context.players.map { it.playerId }.toSet()
        if (snap.sessionWins.keys.any { it !in ids } || snap.sessionWins.values.any { it < 0 || it > 10_000 }) return false
        val engine = PaddleDuelEngine(
            PdConfig(
                seats = context.players.take(PpSeatLayout.MAX_SEATS).map { PdSeat(it.isBot, difficulty, !it.isBot && snap.oneSwitch) },
                format = PdFormat.parse(snap.format), powerups = snap.powerups, relaxed = snap.relaxed, seed = seedSource()
            )
        )
        if (!engine.restoreScores(PdScores(snap.lives, snap.score, snap.alive, snap.rallies))) return false
        this.engine = engine
        matchNo = snap.matchNo
        oneSwitch = snap.oneSwitch
        formatId = snap.format
        powerups = snap.powerups
        relaxed = snap.relaxed
        sessionWins.value = context.players.associate { it.playerId to (snap.sessionWins[it.playerId] ?: 0) }
        matchOver.value = false
        lastClock = -1L
        matchSerial.intValue++
        frame.intValue++
        return true
    }
}
