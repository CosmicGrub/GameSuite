package com.gamesuite.games.whackamole

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
import kotlin.random.Random

@Serializable
private data class WhackSnapshot(
    val players: List<String>,
    val bots: List<Boolean>,
    val timeS: Int,
    val grid: Int,
    val relaxed: Boolean,
    val oneSwitch: Boolean,
    val difficulty: String,
    val seed: Long,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
)

private val whackJson = Json { ignoreUnknownKeys = true }

/**
 * Whack-a-Mole Race on one shared screen: 2 to 4 zones (computer seats fill empty ones), each a grid of holes, all
 * following the same seeded critter list. The rules are the pure [WhackEngine]; this class adds the shell
 * contract, the session tally and save/resume (a round is one timed run, so a resumed game restarts the round with
 * the same seed).
 *
 * MODES (this version): "Same screen" and vs Computer only. No Nearby or Online: the game is not in the networked
 * games list because two devices cannot share one fair critter clock without a sync protocol this release lacks.
 */
class WhackAMoleGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "whack-a-mole"
    override val displayName = "Whack-a-Mole Race"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val version = mutableIntStateOf(0)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)
    val matchOver = mutableStateOf(false)

    /** Pre-set by the screen from the Hub before [init] (the screen may lower [grid] so the holes stay big enough to hit). */
    var timeSeconds: Int = WhackRules.DEFAULT_TIME_S
    var grid: Int = WhackRules.DEFAULT_GRID
    var relaxed: Boolean = false
    var oneSwitch: Boolean = false
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    var engine: WhackEngine? = null
        private set

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var recorded = false
    private var lastSig = 0L
    private val lock: Any = this

    val players get() = context.players

    fun nowMs(): Long = clock()

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            sessionWins.value = context.players.associate { it.playerId to 0 }
            sessionDraws.value = 0
            engine = null
            recorded = false
            bump()
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    private fun config(seed: Long) = WhackConfig(
        seats = context.players.size.coerceIn(WhackRules.MIN_SEATS, WhackRules.MAX_SEATS),
        durationMs = (timeSeconds.takeIf { it in WhackRules.TIME_CHOICES } ?: WhackRules.DEFAULT_TIME_S) * 1000,
        grid = grid.takeIf { it in WhackRules.GRID_CHOICES } ?: WhackRules.DEFAULT_GRID,
        relaxed = relaxed, oneSwitch = oneSwitch, seed = seed,
        cpuSeats = context.players.indices.filter { context.players[it].isBot }.toSet(),
        level = difficulty
    )

    @Synchronized
    override fun startMatch() = begin(random.nextLong())

    private fun begin(seed: Long) {
        matchOver.value = false
        recorded = false
        val e = WhackEngine(config(seed))
        engine = e
        e.startMatch(clock())
        lastSig = signature(e)
        bump()
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session reporting races won (only a strict leader wins). */
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

    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        if (engine?.phase != WhackPhase.OVER) return
        startMatch()
    }

    private fun signature(e: WhackEngine): Long = e.eventSerial * 31L + e.phase.ordinal * 7L

    private fun bump() { version.intValue = version.intValue + 1 }

    @Synchronized
    fun tick(): Boolean {
        val e = engine ?: return false
        e.tick(clock())
        return afterChange(e)
    }

    private fun afterChange(e: WhackEngine): Boolean {
        if (e.phase == WhackPhase.OVER && !recorded) {
            recorded = true
            val w = e.winner()
            if (w >= 0) {
                val id = context.players[w].playerId
                sessionWins.value = sessionWins.value + (id to ((sessionWins.value[id] ?: 0) + 1))
            } else sessionDraws.value = sessionDraws.value + 1
        }
        val sig = signature(e)
        if (sig == lastSig) return false
        lastSig = sig
        bump()
        return true
    }

    /** Seat [seat] touched hole [hole] [ageMs] ago (one-switch mode ignores the hole). */
    @Synchronized
    fun whack(seat: Int, hole: Int, ageMs: Long = 0L): WhackResult {
        val e = engine ?: return WhackResult(WhackKind.IGNORED)
        val now = clock()
        val r = e.whack(seat, hole, now - ageMs.coerceAtLeast(0L), now)
        afterChange(e)
        return r
    }

    // ---- resume ----

    override fun snapshot(): String? = synchronized(lock) {
        try {
            val e = engine ?: return null
            if (matchOver.value || e.phase == WhackPhase.OVER) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            whackJson.encodeToString(
                WhackSnapshot.serializer(),
                WhackSnapshot(
                    players = context.players.map { it.playerId }, bots = context.players.map { it.isBot },
                    timeS = e.config.durationMs / 1000, grid = e.config.grid, relaxed = e.config.relaxed, oneSwitch = e.config.oneSwitch,
                    difficulty = e.config.level.name, seed = e.config.seed,
                    sessionWins = sessionWins.value, sessionDraws = sessionDraws.value
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(text: String): WhackSnapshot? = try {
        val s = whackJson.decodeFromString(WhackSnapshot.serializer(), text)
        val roster = context.players
        val n = roster.size
        val valid = n in WhackRules.MIN_SEATS..WhackRules.MAX_SEATS &&
            s.players.size == n && s.bots.size == n &&
            roster.indices.all { s.players[it] == roster[it].playerId && s.bots[it] == roster[it].isBot } &&
            s.timeS in WhackRules.TIME_CHOICES && s.grid in WhackRules.GRID_CHOICES &&
            CpuDifficulty.entries.any { it.name == s.difficulty } &&
            s.sessionDraws >= 0 && s.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } }
        if (valid) s else null
    } catch (_: Exception) {
        null
    }

    /** Puts the session back and restarts the round (same seed, so the same critters) with a fresh countdown. */
    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val s = decode(snapshot) ?: return false
        timeSeconds = s.timeS; grid = s.grid; relaxed = s.relaxed; oneSwitch = s.oneSwitch
        difficulty = CpuDifficulty.valueOf(s.difficulty)
        sessionWins.value = s.sessionWins; sessionDraws.value = s.sessionDraws
        begin(s.seed)
        return true
    }
}
