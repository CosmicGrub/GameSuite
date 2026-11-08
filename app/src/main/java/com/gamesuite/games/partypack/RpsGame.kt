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
private data class RpsRecordDto(val picks: List<String?>, val results: List<Int>)

@Serializable
private data class RpsSnapshot(
    val version: Int = 1,
    val seats: Int,
    val variant: String,
    val format: String,
    val pickTimerMs: Int,
    val relaxed: Boolean,
    val wins: List<Int>,
    val active: List<Boolean>,
    val round: Int,
    val matchNo: Int,
    val history: List<RpsRecordDto> = emptyList(),
    val sessionWins: Map<String, Int> = emptyMap()
)

private val rpsJson = Json { ignoreUnknownKeys = true }

/**
 * Rock-Paper-Showdown as a [GameModule]. SAME SCREEN ONLY in this version (no Nearby or Online mode): every
 * seat is a person at this device or a computer. The engine is pure; this class owns the clock, the roster,
 * the session tally and Compose state.
 *
 * RESUME: the snapshot holds the match score, who is still in, the round number and the revealed history. It
 * never holds a pick, so even a saved file keeps the round's secrets; a round in progress restarts on restore.
 */
class RpsGame(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val seedSource: () -> Long = { System.nanoTime() }
) : GameModule, ResumableGame {
    override val gameId = "rps-showdown"
    override val displayName = "Rock-Paper-Showdown"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val frame = mutableIntStateOf(0)
    val view = mutableStateOf<RpsView?>(null)
    val matchOver = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())

    /** Set by the screen from the Hub before [init]. */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var variantId: String = RpsVariant.CLASSIC.id
    var formatId: String = RpsFormat.BEST_OF_3.id
    var pickTimerMs: Int = 0
    var relaxed: Boolean = false

    /** 1.0 normally; below 1 slows the countdown and reveal (the app's game-speed setting). */
    var timeScale: Double = 1.0

    lateinit var context: GameContext
        private set
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    var engine: RpsEngine? = null
        private set
    private var matchNo = 0
    private var tallied = -1
    private var lastClock = -1L
    private var paused = false
    private var carry = 0.0

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        engine = null
        view.value = null
        matchNo = 0
        lastClock = -1L
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    private fun config(seed: Long) = RpsConfig(
        seats = context.players.take(PpSeatLayout.MAX_SEATS).map { RpsSeat(it.isBot, difficulty) },
        variant = RpsVariant.parse(variantId), format = RpsFormat.parse(formatId),
        pickTimerMs = pickTimerMs, relaxed = relaxed, seed = seed
    )

    override fun startMatch() {
        matchOver.value = false
        matchNo++
        engine = RpsEngine(config(seedSource()))
        lastClock = -1L
        publish()
    }

    private fun publish() {
        view.value = engine?.view()
        frame.intValue++
    }

    override fun pause() { paused = true }
    override fun resume() { paused = false; lastClock = -1L }

    /** Called about 20 times a second by the screen. */
    fun tick() {
        val e = engine ?: return
        val now = clock()
        if (paused || lastClock < 0L) { lastClock = now; return }
        carry += (now - lastClock) * timeScale
        lastClock = now
        val whole = carry.toInt()
        carry -= whole
        if (whole > 0 && !e.isOver) e.advance(whole)
        publish()
    }

    fun drainEvents(): List<RpsEvent> = engine?.drainEvents() ?: emptyList()

    fun lock(seat: Int, move: RpsMove): RpsReject {
        val r = engine?.lock(seat, move) ?: RpsReject.WRONG_PHASE
        publish(); return r
    }

    fun unlock(seat: Int): RpsReject {
        val r = engine?.unlock(seat) ?: RpsReject.WRONG_PHASE
        publish(); return r
    }

    fun next() { engine?.next(); publish() }

    private fun tally() {
        val e = engine ?: return
        if (!e.isOver || tallied == matchNo) return
        tallied = matchNo
        val w = e.view().winners.firstOrNull() ?: return
        val id = context.players.getOrNull(w)?.playerId ?: return
        sessionWins.value = sessionWins.value + (id to ((sessionWins.value[id] ?: 0) + 1))
    }

    fun noteFinished() = tally()

    fun playAgain() {
        if (matchOver.value) return
        tally()
        startMatch()
    }

    fun leaveSession() {
        if (matchOver.value) return
        tally()
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
        if (e.isOver || matchOver.value) return null
        val v = e.view()
        return rpsJson.encodeToString(
            RpsSnapshot.serializer(),
            RpsSnapshot(
                seats = e.n, variant = e.config.variant.id, format = e.config.format.id, pickTimerMs = e.config.pickTimerMs,
                relaxed = e.config.relaxed, wins = v.wins, active = v.active, round = v.round, matchNo = matchNo,
                history = e.history().map { r -> RpsRecordDto(r.picks.map { it?.name }, r.results) },
                sessionWins = sessionWins.value
            )
        )
    }

    override fun restore(snapshot: String): Boolean {
        if (!this::context.isInitialized) return false
        val snap = try { rpsJson.decodeFromString(RpsSnapshot.serializer(), snapshot) } catch (_: Exception) { return false }
        if (snap.version != 1 || snap.seats != context.players.size.coerceAtMost(PpSeatLayout.MAX_SEATS)) return false
        if (RpsVariant.entries.none { it.id == snap.variant } || RpsFormat.entries.none { it.id == snap.format }) return false
        if (snap.pickTimerMs !in 0..120_000 || snap.matchNo !in 1..10_000) return false
        val ids = context.players.map { it.playerId }.toSet()
        if (snap.sessionWins.keys.any { it !in ids } || snap.sessionWins.values.any { it < 0 || it > 10_000 }) return false
        val history = snap.history.map { d ->
            val picks = d.picks.map { name -> if (name == null) null else RpsMove.entries.firstOrNull { it.name == name } ?: return false }
            RpsRoundRecord(picks, d.results)
        }
        val engine = RpsEngine(
            RpsConfig(
                seats = context.players.take(PpSeatLayout.MAX_SEATS).map { RpsSeat(it.isBot, difficulty) },
                variant = RpsVariant.parse(snap.variant), format = RpsFormat.parse(snap.format),
                pickTimerMs = snap.pickTimerMs, relaxed = snap.relaxed, seed = seedSource()
            )
        )
        if (!engine.restoreState(snap.wins, snap.active, snap.round, history)) return false
        this.engine = engine
        matchNo = snap.matchNo
        variantId = snap.variant; formatId = snap.format; pickTimerMs = snap.pickTimerMs; relaxed = snap.relaxed
        sessionWins.value = context.players.associate { it.playerId to (snap.sessionWins[it.playerId] ?: 0) }
        matchOver.value = false
        lastClock = -1L
        publish()
        return true
    }
}
