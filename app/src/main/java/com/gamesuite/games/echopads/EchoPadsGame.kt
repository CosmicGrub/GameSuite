package com.gamesuite.games.echopads

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class EchoSnapshot(
    val mode: String,
    val players: List<String>,
    val speed: String,
    val forgiving: Boolean,
    val sequence: List<Int>,
    val order: List<Int>,
    val orderPos: Int,
    val results: List<Int>,
    val out: List<Boolean>,
    val elimination: List<Int>,
    val completed: List<Int>,
    val sessionWins: Map<String, Int>,
    val sessionBest: Int
)

private val echoJson = Json { ignoreUnknownKeys = true }

/**
 * Echo Pads: a four-pad pattern memory game for the whole family (solo, pass and play, and a same-screen duel).
 * All the rules live in the pure [EchoPadsEngine]; this class adds the shell contract, the session tally and
 * save/resume. The screen sets [mode], [speed], [forgiving] and [delayScale] from the Hub before [init].
 *
 * Nothing runs by itself: the screen calls [tick] every frame, [tap] on a pad touch and [ready] on the hand-off
 * button. Use a seeded [Random] and a hand-driven [clock] to replay a whole match exactly.
 */
class EchoPadsGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "echo-pads"
    override val displayName = "Echo Pads"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = EchoRules.MAX_PLAYERS
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)

    /** Bumped whenever something the screen draws from discrete state changed. */
    val version = mutableIntStateOf(0)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())

    /** The longest pattern copied in Solo this session. */
    val sessionBest = mutableIntStateOf(0)
    val matchOver = mutableStateOf(false)

    /** Pre-set by the screen from the Hub before [init]. */
    var mode: EchoMode = EchoMode.SOLO
    var speed: EchoSpeed = EchoSpeed.NORMAL
    var forgiving: Boolean = false
    var delayScale: Float = 1f

    var engine: EchoPadsEngine? = null
        private set

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var recorded = false
    private var lastSig: Sig? = null
    private var pausedAt = -1L
    private val lock: Any = this

    val players get() = context.players

    fun nowMs(): Long = clock()

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            sessionWins.value = context.players.associate { it.playerId to 0 }
            sessionBest.intValue = 0
            engine = null
            recorded = false
            bump()
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    /** The mode actually played, which always fits the roster (a lone player is Solo, a duel needs exactly two). */
    fun effectiveMode(): EchoMode {
        val n = context.players.size
        return when {
            n <= 1 -> EchoMode.SOLO
            mode == EchoMode.DUEL && n == EchoRules.DUEL_PLAYERS -> EchoMode.DUEL
            else -> EchoMode.PASS_AND_PLAY
        }
    }

    private fun config(): EchoConfig {
        val m = effectiveMode()
        val n = context.players.size.coerceIn(EchoRules.seatRange(m).first, EchoRules.seatRange(m).last)
        return EchoConfig(m, n, speed, delayScale, forgiving)
    }

    @Synchronized
    override fun startMatch() {
        matchOver.value = false
        recorded = false
        val e = EchoPadsEngine(config(), random)
        engine = e
        e.startMatch(clock())
        lastSig = signature(e)
        bump()
    }

    /** Idempotent: a second pause without a resume keeps the first moment. The engine stands still until [resume]. */
    override fun pause() {
        synchronized(lock) { if (pausedAt < 0L) pausedAt = clock() }
    }

    /**
     * Carries on after a pause. Waiting and feedback pauses simply continue (their deadlines are shifted by the time
     * away); a pattern that was playing is never resumed mid-way (the player never saw the lights that played while
     * away) but restarts from its first pad, without handing out a free retry.
     */
    override fun resume() {
        synchronized(lock) {
            val at = pausedAt
            pausedAt = -1L
            if (at < 0L) return
            val e = engine ?: return
            val now = clock()
            e.shiftTime(now - at)
            e.restartShowing(now)
            afterChange(e)
            bump()
        }
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session reporting the results of the last finished match (or nothing when none finished). */
    fun leaveSession() {
        if (matchOver.value) return
        val e = engine
        val over = e != null && e.phase == EchoPhase.OVER
        endMatch(
            GameResult(
                scores = if (!over || e == null) emptyList() else context.players.mapIndexed { i, p ->
                    PlayerScore(p.playerId, e.completedBy(i), isWinner = i in e.winners && (e.config.mode != EchoMode.SOLO || e.completedBy(i) > 0))
                }
            )
        )
    }

    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        if (engine?.phase != EchoPhase.OVER) return
        startMatch()
    }

    /**
     * Everything the screen draws from discrete state. A data class (compared field by field) rather than a packed
     * number, so two different states can never look alike and swallow a version bump.
     */
    internal data class Sig(val serial: Int, val phase: EchoPhase, val progress: Int, val player: Int)

    private fun signature(e: EchoPadsEngine): Sig = Sig(e.eventSerial, e.phase, e.progress, e.currentPlayer)

    private fun bump() { version.intValue = version.intValue + 1 }

    /** Advances the engine to the clock; call every frame. True when something discrete changed. */
    @Synchronized
    fun tick(): Boolean {
        val e = engine ?: return false
        if (pausedAt >= 0L) return false
        e.tick(clock())
        return afterChange(e)
    }

    /**
     * The current player taps [pad]. [seat] is the player whose half or board was touched (-1 when the board is
     * shared): a touch on anyone else's half is ignored, so in a duel only the player whose turn it is can answer.
     */
    @Synchronized
    fun tap(pad: Int, seat: Int = -1): TapResult {
        val e = engine ?: return TapResult.IGNORED
        if (seat >= 0 && seat != e.currentPlayer) return TapResult.IGNORED
        val r = e.tap(pad, clock())
        afterChange(e)
        return r
    }

    /** The hand-off button. */
    @Synchronized
    fun ready(): Boolean {
        val e = engine ?: return false
        val r = e.ready(clock())
        afterChange(e)
        return r
    }

    private fun afterChange(e: EchoPadsEngine): Boolean {
        if (e.phase == EchoPhase.OVER && !recorded) {
            recorded = true
            if (e.config.mode == EchoMode.SOLO) {
                sessionBest.intValue = maxOf(sessionBest.intValue, e.completedBy(0))
            } else if (!e.isTie) {
                e.winners.firstOrNull()?.let { w ->
                    val id = context.players[w].playerId
                    sessionWins.value = sessionWins.value + (id to ((sessionWins.value[id] ?: 0) + 1))
                }
            }
        }
        val sig = signature(e)
        if (sig == lastSig) return false
        lastSig = sig
        bump()
        return true
    }

    // ---- resume ----

    override fun snapshot(): String? = synchronized(lock) {
        try {
            val e = engine ?: return null
            if (matchOver.value || e.phase == EchoPhase.OVER) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            val p = e.progressSnapshot()
            echoJson.encodeToString(
                EchoSnapshot.serializer(),
                EchoSnapshot(
                    mode = e.config.mode.id, players = context.players.map { it.playerId },
                    speed = e.config.speed.id, forgiving = e.config.forgiving,
                    sequence = p.sequence, order = p.order, orderPos = p.orderPos, results = p.results,
                    out = p.out, elimination = p.elimination, completed = p.completed,
                    sessionWins = sessionWins.value, sessionBest = sessionBest.intValue
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(text: String): EchoSnapshot? = try {
        val s = echoJson.decodeFromString(EchoSnapshot.serializer(), text)
        val roster = context.players
        val valid = s.mode == effectiveMode().id &&
            s.players.size == roster.size && roster.indices.all { s.players[it] == roster[it].playerId } &&
            EchoSpeed.entries.any { it.id == s.speed } &&
            s.sessionBest >= 0 && s.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } }
        if (valid) s else null
    } catch (_: Exception) {
        null
    }

    /** Puts the match back as saved, restarting the turn in progress. False (engine untouched) for a stale or corrupt save. */
    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val s = decode(snapshot) ?: return false
        val oldSpeed = speed
        val oldForgiving = forgiving
        speed = EchoSpeed.fromId(s.speed)
        forgiving = s.forgiving
        val e = try {
            EchoPadsEngine(config(), random)
        } catch (_: IllegalArgumentException) {
            speed = oldSpeed; forgiving = oldForgiving
            return false
        }
        val ok = e.resume(
            clock(),
            EchoProgress(s.sequence, s.order, s.orderPos, s.results, s.out, s.elimination, s.completed)
        )
        if (!ok) {
            speed = oldSpeed; forgiving = oldForgiving
            return false
        }
        engine = e
        matchOver.value = false; recorded = false
        sessionWins.value = s.sessionWins; sessionBest.intValue = s.sessionBest
        lastSig = signature(e)
        bump()
        return true
    }
}
