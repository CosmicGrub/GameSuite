package com.gamesuite.games.reactionduel

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
private data class DuelSnapshot(
    val players: List<String>,
    val bots: List<Boolean>,
    val variant: String,
    val bestOf: Int,
    val relaxed: Boolean,
    val oneSwitch: Boolean,
    val difficulty: String,
    val seed: Long,
    val completed: Int,
    val roundsPlayed: Int,
    val wins: List<Int>,
    val falseStarts: List<Int>,
    val bestMs: List<Int>,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
)

private val duelJson = Json { ignoreUnknownKeys = true }

/**
 * Reaction Duel on one shared screen: 2 to 4 people (computer seats fill empty zones) each own a zone and press at
 * the same time. All the rules live in the pure [ReactionDuelEngine]; this class adds the shell contract, the
 * session tally and save/resume.
 *
 * MODES (this version): "Same screen" (pass-and-play roster) and vs Computer. There is deliberately no Nearby or
 * Online mode: two phones cannot share one fair signal moment without a clock-sync protocol this release does not
 * have, so the game is same-screen only and is not listed in the networked games.
 *
 * REAL TIME BY INJECTED CLOCK. Nothing runs by itself: the screen calls [tick] each frame and [press] on every
 * touch with how long ago the touch happened ([ageMs]); the engine ranks presses by event time. A fake clock
 * and a seeded [Random] replay a whole match exactly.
 */
class ReactionDuelGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "reaction-duel"
    override val displayName = "Reaction Duel"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    /** Bumped whenever something the screen draws from discrete state changed (a press, a phase, an event). */
    val version = mutableIntStateOf(0)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)
    val matchOver = mutableStateOf(false)

    /** Pre-set by the screen from the Hub before [init]. */
    var variant: DuelVariant = DuelVariant.CLASSIC
    var bestOf: Int = DuelRules.DEFAULT_BEST_OF
    var relaxed: Boolean = false
    var oneSwitch: Boolean = false
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    var engine: ReactionDuelEngine? = null
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

    private fun config(seed: Long) = DuelConfig(
        seats = context.players.size.coerceIn(DuelRules.MIN_SEATS, DuelRules.MAX_SEATS),
        variant = variant,
        bestOf = bestOf.takeIf { it in DuelRules.BEST_OF_CHOICES } ?: DuelRules.DEFAULT_BEST_OF,
        relaxed = relaxed, oneSwitch = oneSwitch, seed = seed,
        cpuSeats = context.players.indices.filter { context.players[it].isBot }.toSet(),
        level = difficulty
    )

    @Synchronized
    override fun startMatch() {
        matchOver.value = false
        recorded = false
        val e = ReactionDuelEngine(config(random.nextLong()))
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

    /** Ends the session reporting duels won (only a strict leader wins). */
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
        if (engine?.phase != DuelPhase.OVER) return
        startMatch()
    }

    private fun signature(e: ReactionDuelEngine): Long =
        e.eventSerial * 31L + e.phase.ordinal * 7L + e.seatStatus.fold(0L) { a, s -> a * 3 + s.ordinal }

    private fun bump() { version.intValue = version.intValue + 1 }

    /** Advances the engine to the clock; call every frame. True when something discrete changed. */
    @Synchronized
    fun tick(): Boolean {
        val e = engine ?: return false
        e.tick(clock())
        return afterChange(e)
    }

    private fun afterChange(e: ReactionDuelEngine): Boolean {
        if (e.phase == DuelPhase.OVER && !recorded) {
            recorded = true
            val w = e.matchWinner
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

    /**
     * Seat [seat] pressed [ageMs] ago (the screen derives it from the touch's own timestamp, so a frame of delay
     * never costs a player their reaction time). [pad] is the echo pad (0..3) the finger landed on, -1 elsewhere.
     */
    @Synchronized
    fun press(seat: Int, ageMs: Long = 0L, pad: Int = -1): PressResult {
        val e = engine ?: return PressResult.IGNORED
        // In echo a finger that missed every pad is not a wrong answer, it is nothing (one-switch has no pads to miss).
        if (e.config.variant == DuelVariant.ECHO && !e.config.oneSwitch && pad !in 0 until DuelSymbol.ECHO_PADS) return PressResult.IGNORED
        val now = clock()
        val r = e.press(seat, now - ageMs.coerceAtLeast(0L), pad, now)
        afterChange(e)
        return r
    }

    // ---- resume ----

    override fun snapshot(): String? = synchronized(lock) {
        try {
            val e = engine ?: return null
            if (matchOver.value || e.phase == DuelPhase.OVER || e.overPending) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            val p = e.progress()
            duelJson.encodeToString(
                DuelSnapshot.serializer(),
                DuelSnapshot(
                    players = context.players.map { it.playerId }, bots = context.players.map { it.isBot },
                    variant = e.config.variant.id, bestOf = e.config.bestOf, relaxed = e.config.relaxed, oneSwitch = e.config.oneSwitch,
                    difficulty = e.config.level.name, seed = e.config.seed,
                    completed = p.completedAttempts, roundsPlayed = p.roundsPlayed, wins = p.wins, falseStarts = p.falseStarts, bestMs = p.bestMs,
                    sessionWins = sessionWins.value, sessionDraws = sessionDraws.value
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(text: String): DuelSnapshot? = try {
        val s = duelJson.decodeFromString(DuelSnapshot.serializer(), text)
        val roster = context.players
        val n = roster.size
        val target = DuelRules.target(s.bestOf)
        val valid = n in DuelRules.MIN_SEATS..DuelRules.MAX_SEATS &&
            s.players.size == n && s.bots.size == n &&
            roster.indices.all { s.players[it] == roster[it].playerId && s.bots[it] == roster[it].isBot } &&
            DuelVariant.entries.any { it.id == s.variant } &&
            s.bestOf in DuelRules.BEST_OF_CHOICES &&
            CpuDifficulty.entries.any { it.name == s.difficulty } &&
            listOf(s.wins, s.falseStarts, s.bestMs).all { it.size == n } &&
            s.wins.all { it in 0 until target } && s.falseStarts.all { it in 0..10_000 } && s.bestMs.all { it in -1..60_000 } &&
            s.completed in 0 until DuelRules.attemptCap(s.bestOf) &&
            s.roundsPlayed in s.wins.sum()..s.completed &&
            s.sessionDraws >= 0 && s.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } }
        if (valid) s else null
    } catch (_: Exception) {
        null
    }

    /** Puts the match back as saved, into a fresh countdown of the round that was in progress (a mid-round save replays that round). */
    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val s = decode(snapshot) ?: return false
        variant = DuelVariant.fromId(s.variant); bestOf = s.bestOf; relaxed = s.relaxed; oneSwitch = s.oneSwitch
        difficulty = CpuDifficulty.valueOf(s.difficulty)
        val e = ReactionDuelEngine(config(s.seed))
        e.resume(clock(), DuelProgress(s.completed, s.roundsPlayed, s.wins, s.falseStarts, s.bestMs))
        engine = e
        matchOver.value = false; recorded = false
        sessionWins.value = s.sessionWins; sessionDraws.value = s.sessionDraws
        lastSig = signature(e)
        bump()
        return true
    }
}
