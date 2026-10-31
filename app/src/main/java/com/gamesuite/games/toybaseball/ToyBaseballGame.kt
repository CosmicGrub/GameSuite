package com.gamesuite.games.toybaseball

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

enum class BaseballPhase { PITCH, SWING, OVER }

/** Why a pitch or a swing did nothing. [NONE] means it worked. */
enum class BaseballReject { NONE, NOT_PLAYING, NOT_YOUR_SEAT, NOT_PITCHER, NOT_BATTER, WRONG_PHASE, BAD_INPUT }

@Serializable
data class BaseballPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

/**
 * One pitch, as it was thrown and answered. [row] and [col] are where it landed on the 5x5 grid;
 * [swingRow] / [swingCol] the batter's aim on the 3x3 zone; [timing] a [PitchType] name; [quality] the contact
 * quality (0 for a take).
 */
@Serializable
data class PitchRecord(
    val batterSeat: Int,
    val type: String,
    val row: Int,
    val col: Int,
    val swung: Boolean,
    val swingRow: Int,
    val swingCol: Int,
    val timing: String,
    val power: Boolean,
    val outcome: String,
    val quality: Int
)

/** A pitch chosen but not yet answered (only the pitcher's own device ever holds it). */
@Serializable
data class PendingPitch(val type: String, val row: Int, val col: Int)

/**
 * What the diamond looks like to a device. Serializable: it is also the host's network snapshot, built per
 * seat so the pitcher's [pending] pitch only ever reaches the pitcher. [eventSerial] only ever grows and keys
 * the screen's animation, sound and announcement of [last].
 */
@Serializable
data class BaseballState(
    val players: List<BaseballPlayer>,
    val innings: Int,
    val inning: Int,
    val top: Boolean,
    val outs: Int,
    val balls: Int,
    val strikes: Int,
    val bases: List<Boolean>,
    val score: List<Int>,
    val hits: List<Int>,
    val lineScore: List<List<Int>>,
    val batterSeat: Int,
    val pitcherSeat: Int,
    val phase: String,
    val pending: PendingPitch?,
    val last: PitchRecord?,
    val lastRuns: Int,
    val lastOuts: Int,
    val history: List<PitchRecord>,
    val pitchCount: List<Int>,
    val eventSerial: Int,
    val halfEnded: Boolean,
    val over: Boolean,
    val winnerPlayerId: String?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
) {
    val phaseValue: BaseballPhase get() = BaseballPhase.entries.firstOrNull { it.name == phase } ?: BaseballPhase.OVER
}

/** A suggestion the Hint button shows: a pitch (type + 5x5 cell) for the pitcher, a swing for the batter. */
data class BaseballHint(val type: PitchType, val row: Int, val col: Int, val power: Boolean = false)

@Serializable
private data class BaseballSnapshot(val state: BaseballState, val awaySeat: Int, val difficulty: String)

private val baseballJson = Json { ignoreUnknownKeys = true }

private object ValueTable {
    // [power][strikes>=2][q+60]
    val table: Array<Array<DoubleArray>> = Array(2) { p ->
        Array(2) { s -> DoubleArray(200) { i -> BaseballRules.swingValue(i - 60, p == 1, 2, if (s == 1) 2 else 0) } }
    }

    fun of(q: Int, power: Boolean, strikes: Int): Double =
        table[if (power) 1 else 0][if (strikes >= 2) 1 else 0][(q + 60).coerceIn(0, 199)]
}

/**
 * Toy Baseball: a pitcher-versus-batter duel, one pitch at a time. See [BaseballRules] for the contact model.
 *
 * THE DUEL. The pitcher secretly picks a pitch type and a target cell on the 5x5 grid (the middle 3x3 is the
 * strike zone). The batter, without seeing it, picks TAKE or a swing: a zone cell to aim at, the pitch type
 * they are "ready for" (their timing) and contact or power. The pitch is then revealed and resolved by the
 * deterministic contact model: a good guess of speed AND place hits hard; a poor one misses, fouls or
 * grounds out. Both sides can read the history of the game's pitches and swings, so patterns get punished.
 *
 * TIME comes only from the injected [clock] (the computer's think pauses); the dice (variance and field
 * placement of a contact) come from the injected [rolls], a seeded Random by default. The pitcher's pending
 * choice is private to its device.
 *
 * Halves: the away seat bats in the top of each inning. Three outs end a half; the game runs [BaseballState.innings]
 * innings, then up to [BaseballRules.MAX_EXTRA_INNINGS] extra ones if tied (still tied: a draw). The home seat
 * wins on the spot with a lead in the bottom of the last (or an extra) inning, and does not bat in the
 * bottom of the last inning when already ahead.
 */
class ToyBaseballGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val rolls: BaseballRolls = RandomRolls(random)
) : GameModule, ResumableGame {
    override val gameId = "toy-baseball"
    override val displayName = "Toy Baseball"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<BaseballState?>(null)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)
    val matchOver = mutableStateOf(false)
    val opponentLeft = mutableStateOf(false)
    val hint = mutableStateOf<BaseballHint?>(null)
    val lastReject = mutableStateOf<Pair<BaseballReject, Int>?>(null)

    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var innings: Int = BaseballRules.DEFAULT_INNINGS

    /** The seat that bats first (the away team). */
    var awaySeat: Int = 0

    /** Tests may give the two computer seats different levels. */
    internal val seatLevelOverride = arrayOfNulls<CpuDifficulty>(2)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this // one monitor for @Synchronized entry points and network callbacks alike

    // the game
    private var inning = 1
    private var top = true
    private var outs = 0
    private var balls = 0
    private var strikes = 0
    private val bases = BooleanArray(3)
    private val score = IntArray(2)
    private val hits = IntArray(2)
    private var lineScore = listOf<MutableList<Int>>(mutableListOf(), mutableListOf())
    private var phase = BaseballPhase.OVER
    private var pendingType: PitchType? = null
    private var pendingRow = 0
    private var pendingCol = 0
    private var last: PitchRecord? = null
    private var lastRuns = 0
    private var lastOuts = 0
    private var halfEnded = false
    private val history = ArrayList<PitchRecord>()
    private val pitchCount = IntArray(2)
    private var eventSerial = 0
    private var over = false
    private var winnerPlayerId: String? = null
    private var rejectSerial = 0
    private var thinkAt = 0L

    private val batterSeat: Int get() = if (top) awaySeat else 1 - awaySeat
    private val pitcherSeat: Int get() = 1 - batterSeat

    // networking
    private val isNetworked: Boolean
        get() = this::context.isInitialized && (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            opponentLeft.value = false
            sessionWins.value = context.players.associate { it.playerId to 0 }
            sessionDraws.value = 0
            hint.value = null
            lastReject.value = null
            state.value = null
            phase = BaseballPhase.OVER
            over = false
            winnerPlayerId = null
            stateVersion = 0
            lastAppliedRemoteVersion = 0
            if (isNetworked) registerNetworkListeners()
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    private fun levelOf(seat: Int): CpuDifficulty = seatLevelOverride[seat] ?: difficulty
    private fun isBotSeat(seat: Int): Boolean = this::context.isInitialized && context.players.getOrNull(seat)?.isBot == true

    // ---- lifecycle -----------------------------------------------------------------------

    @Synchronized
    override fun startMatch() {
        if (isNetworked && !isHost) return
        matchOver.value = false
        innings = innings.coerceIn(BaseballRules.MIN_INNINGS, BaseballRules.MAX_INNINGS)
        awaySeat = awaySeat.coerceIn(0, 1)
        inning = 1; top = true; outs = 0; balls = 0; strikes = 0
        bases.fill(false); score.fill(0); hits.fill(0); pitchCount.fill(0)
        lineScore = listOf(mutableListOf(0), mutableListOf(0))
        phase = BaseballPhase.PITCH
        pendingType = null; last = null; lastRuns = 0; lastOuts = 0; halfEnded = false
        history.clear()
        over = false; winnerPlayerId = null
        thinkAt = 0L
        hint.value = null
        eventSerial++
        commit()
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.value
        val best = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == best }
        val scores = context.players.map {
            val w = wins[it.playerId] ?: 0
            PlayerScore(playerId = it.playerId, score = w, isWinner = best > 0 && w == best && leaders == 1)
        }
        endMatch(GameResult(scores = scores))
    }

    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        if (isNetworked) {
            if (opponentLeft.value || state.value?.over != true) return
            if (isHost) { awaySeat = 1 - awaySeat; startMatch() } else sendToHost(ToyBaseballNetMessage.RequestPlayAgain)
            return
        }
        awaySeat = 1 - awaySeat
        startMatch()
    }

    // ---- input ---------------------------------------------------------------------------

    /** The pitcher's seat chooses a pitch. Offline either seat acts for itself; networked only this device's seat. */
    @Synchronized
    fun pitch(seat: Int, type: PitchType, row: Int, col: Int): BaseballReject {
        val s = state.value ?: return BaseballReject.NOT_PLAYING
        if (matchOver.value || s.over) return BaseballReject.NOT_PLAYING
        if (isNetworked) {
            if (seat != context.localPlayerIndex || opponentLeft.value) return BaseballReject.NOT_YOUR_SEAT
            if (!isHost) {
                if (seat != s.pitcherSeat) return note(BaseballReject.NOT_PITCHER)
                if (s.phaseValue != BaseballPhase.PITCH) return note(BaseballReject.WRONG_PHASE)
                if (row !in 0 until BaseballRules.GRID || col !in 0 until BaseballRules.GRID) return note(BaseballReject.BAD_INPUT)
                sendToHost(ToyBaseballNetMessage.PitchIntent(type.name, row, col))
                return BaseballReject.NONE
            }
        }
        val r = applyPitch(seat, type, row, col)
        if (r == BaseballReject.NONE) commit() else note(r)
        return r
    }

    /** The batter's seat answers with a swing or a take. */
    @Synchronized
    fun swing(seat: Int, swing: BaseballSwing): BaseballReject {
        val s = state.value ?: return BaseballReject.NOT_PLAYING
        if (matchOver.value || s.over) return BaseballReject.NOT_PLAYING
        if (isNetworked) {
            if (seat != context.localPlayerIndex || opponentLeft.value) return BaseballReject.NOT_YOUR_SEAT
            if (!isHost) {
                if (seat != s.batterSeat) return note(BaseballReject.NOT_BATTER)
                if (s.phaseValue != BaseballPhase.SWING) return note(BaseballReject.WRONG_PHASE)
                if (!swing.take && (swing.row !in 0..2 || swing.col !in 0..2)) return note(BaseballReject.BAD_INPUT)
                sendToHost(ToyBaseballNetMessage.SwingIntent(swing.take, swing.row, swing.col, swing.timing.name, swing.power))
                return BaseballReject.NONE
            }
        }
        val r = applySwing(seat, swing)
        if (r == BaseballReject.NONE) commit() else note(r)
        return r
    }

    private fun note(r: BaseballReject): BaseballReject {
        lastReject.value = r to ++rejectSerial
        return r
    }

    private fun applyPitch(seat: Int, type: PitchType, row: Int, col: Int): BaseballReject {
        if (phase == BaseballPhase.OVER) return BaseballReject.NOT_PLAYING
        if (seat !in 0..1) return BaseballReject.NOT_YOUR_SEAT
        if (seat != pitcherSeat) return BaseballReject.NOT_PITCHER
        if (phase != BaseballPhase.PITCH) return BaseballReject.WRONG_PHASE
        if (row !in 0 until BaseballRules.GRID || col !in 0 until BaseballRules.GRID) return BaseballReject.BAD_INPUT
        pendingType = type; pendingRow = row; pendingCol = col
        phase = BaseballPhase.SWING
        thinkAt = 0L
        hint.value = null
        return BaseballReject.NONE
    }

    private fun applySwing(seat: Int, swing: BaseballSwing): BaseballReject {
        if (phase == BaseballPhase.OVER) return BaseballReject.NOT_PLAYING
        if (seat !in 0..1) return BaseballReject.NOT_YOUR_SEAT
        if (seat != batterSeat) return BaseballReject.NOT_BATTER
        if (phase != BaseballPhase.SWING) return BaseballReject.WRONG_PHASE
        if (!swing.take && (swing.row !in 0..2 || swing.col !in 0..2)) return BaseballReject.BAD_INPUT
        resolvePitch(swing)
        thinkAt = 0L
        hint.value = null
        return BaseballReject.NONE
    }

    // ---- resolving a pitch ---------------------------------------------------------------------

    private fun resolvePitch(swing: BaseballSwing) {
        val type = pendingType!!
        val row = pendingRow; val col = pendingCol
        pendingType = null
        val batter = batterSeat
        var q = 0
        val raw: String
        if (swing.take) {
            raw = if (BaseballRules.inZone(row, col)) BaseballOutcome.CALLED_STRIKE else BaseballOutcome.BALL
        } else {
            q = BaseballRules.quality(type, row, col, swing, rolls.variance())
            raw = BaseballRules.contactOutcome(q, swing.power, rolls.placement(), row)
        }
        pitchCount[pitcherSeat]++
        val outsBefore = outs
        val runsBefore = score[batter]
        val finalOutcome = applyOutcome(raw, q)
        lastRuns = score[batter] - runsBefore
        lastOuts = outs - outsBefore
        val rec = PitchRecord(
            batterSeat = batter, type = type.name, row = row, col = col, swung = !swing.take,
            swingRow = if (swing.take) 1 else swing.row, swingCol = if (swing.take) 1 else swing.col,
            timing = (if (swing.take) PitchType.FASTBALL else swing.timing).name, power = !swing.take && swing.power,
            outcome = finalOutcome, quality = q
        )
        last = rec
        history += rec
        while (history.size > BaseballRules.HISTORY_KEPT) history.removeAt(0)
        halfEnded = false
        eventSerial++
        // The pitch may have ended the plate appearance, the half or the game.
        if (!over) {
            if (outs >= BaseballRules.OUTS_PER_HALF) endHalf() else if (paOver(finalOutcome)) newBatter()
        }
        if (!over) phase = BaseballPhase.PITCH
    }

    private fun paOver(outcome: String) = outcome !in setOf(
        BaseballOutcome.CALLED_STRIKE, BaseballOutcome.BALL, BaseballOutcome.SWING_MISS, BaseballOutcome.FOUL
    )

    private fun newBatter() { balls = 0; strikes = 0 }

    private fun addRuns(n: Int) {
        if (n <= 0) return
        val seat = batterSeat
        score[seat] += n
        val line = lineScore[seat]
        while (line.size < inning) line += 0
        line[inning - 1] += n
    }

    /** Applies a raw result to the count, the bases and the outs; returns the final outcome (strikeouts, double play...). */
    private fun applyOutcome(raw: String, q: Int): String {
        var outcome = raw
        var runs = 0
        val b0 = bases[0]; val b1 = bases[1]; val b2 = bases[2]
        when (raw) {
            BaseballOutcome.CALLED_STRIKE, BaseballOutcome.SWING_MISS -> {
                strikes++
                if (strikes >= BaseballRules.STRIKES_FOR_OUT) {
                    outcome = if (raw == BaseballOutcome.CALLED_STRIKE) BaseballOutcome.STRIKEOUT_LOOKING else BaseballOutcome.STRIKEOUT_SWINGING
                    outs++
                }
            }
            BaseballOutcome.FOUL -> if (strikes < BaseballRules.STRIKES_FOR_OUT - 1) strikes++
            BaseballOutcome.BALL -> {
                balls++
                if (balls >= BaseballRules.BALLS_FOR_WALK) {
                    outcome = BaseballOutcome.WALK
                    if (b0) {
                        if (b1) {
                            if (b2) runs++
                            bases[2] = true
                        }
                        bases[1] = true
                    }
                    bases[0] = true
                }
            }
            BaseballOutcome.SINGLE -> {
                hits[batterSeat]++
                if (b2) runs++
                if (b1) { if (outs >= 2) runs++ }
                bases[2] = b1 && outs < 2
                bases[1] = b0
                bases[0] = true
            }
            BaseballOutcome.DOUBLE -> {
                hits[batterSeat]++
                if (b2) runs++
                if (b1) runs++
                if (b0 && outs >= 2) runs++
                bases[2] = b0 && outs < 2
                bases[1] = true
                bases[0] = false
            }
            BaseballOutcome.HOME_RUN -> {
                hits[batterSeat]++
                runs = 1 + (if (b0) 1 else 0) + (if (b1) 1 else 0) + (if (b2) 1 else 0)
                bases.fill(false)
            }
            BaseballOutcome.GROUND_OUT -> {
                if (b0 && outs < 2 && q < 48) {
                    outcome = BaseballOutcome.DOUBLE_PLAY
                    outs += 2
                    bases[0] = false
                    if (outs < 3) { if (b2) runs++; bases[2] = b1; bases[1] = false }
                } else {
                    outs++
                    if (outs < 3) {
                        if (b2) runs++
                        bases[2] = b1; bases[1] = b0; bases[0] = false
                    }
                }
            }
            BaseballOutcome.FLY_OUT -> {
                if (b2 && outs < 2 && q >= 45) {
                    outcome = BaseballOutcome.SAC_FLY
                    runs++
                    bases[2] = false
                }
                outs++
            }
            BaseballOutcome.LINE_OUT -> outs++
        }
        if (outs < BaseballRules.OUTS_PER_HALF) addRuns(runs)
        // Walk-off: the home seat takes the lead in the bottom of the last (or an extra) inning.
        val home = 1 - awaySeat
        if (!top && inning >= innings && score[home] > score[awaySeat]) finish(home)
        return outcome
    }

    private fun endHalf() {
        halfEnded = true
        outs = 0; balls = 0; strikes = 0
        bases.fill(false)
        val home = 1 - awaySeat
        if (top) {
            if (inning >= innings && score[home] > score[awaySeat]) { finish(home); return }
            top = false
        } else {
            if (inning >= innings) {
                when {
                    score[0] != score[1] -> { finish(if (score[0] > score[1]) 0 else 1); return }
                    inning >= innings + BaseballRules.MAX_EXTRA_INNINGS -> { finish(-1); return }
                }
            }
            inning++
            top = true
            for (l in lineScore) while (l.size < inning) l += 0
        }
        for (l in lineScore) while (l.size < inning) l += 0
    }

    private fun finish(winnerSeat: Int) {
        over = true
        phase = BaseballPhase.OVER
        winnerPlayerId = if (winnerSeat >= 0) context.players[winnerSeat].playerId else null
        pendingType = null
        if (winnerSeat >= 0) {
            val id = context.players[winnerSeat].playerId
            sessionWins.value = sessionWins.value + (id to (sessionWins.value[id] ?: 0) + 1)
        } else {
            sessionDraws.value += 1
        }
    }

    // ---- the computer --------------------------------------------------------------------------

    /** Lets a computer seat act once its think pause is over. Call about every 100 ms; false for a networked guest. */
    @Synchronized
    fun tick(): Boolean {
        if (isNetworked && !isHost) return false
        if (state.value == null || matchOver.value || over) return false
        val seat = if (phase == BaseballPhase.PITCH) pitcherSeat else batterSeat
        if (!isBotSeat(seat)) { thinkAt = 0L; return false }
        val now = clock()
        val p = BaseballRules.profile(levelOf(seat))
        if (thinkAt == 0L) {
            thinkAt = now + (p.thinkMs * (0.8 + random.nextDouble() * 0.5)).toLong()
            return false
        }
        if (now < thinkAt) return false
        thinkAt = 0L
        if (phase == BaseballPhase.PITCH) {
            val pick = choosePitch(seat, levelOf(seat))
            applyPitch(seat, pick.type, pick.row, pick.col)
        } else {
            val sw = chooseSwing(seat, levelOf(seat))
            applySwing(seat, sw)
        }
        commit()
        return true
    }

    private class PitchPick(val type: PitchType, val row: Int, val col: Int)

    private fun randomPitch(inZoneOnly: Boolean, zoneBias: Double): PitchPick {
        val type = PitchType.entries[random.nextInt(PitchType.entries.size)]
        val zone = inZoneOnly || random.nextDouble() < zoneBias
        return if (zone) PitchPick(type, 1 + random.nextInt(3), 1 + random.nextInt(3))
        else {
            var r: Int; var c: Int
            do { r = random.nextInt(5); c = random.nextInt(5) } while (BaseballRules.inZone(r, c))
            PitchPick(type, r, c)
        }
    }

    /** The records of the opposite seat's at-bats against [seat]'s pitching: what the batter has been doing. */
    private fun batterHabits(pitcher: Int): List<PitchRecord> = history.filter { it.batterSeat == 1 - pitcher }
    private fun pitcherHabits(batter: Int): List<PitchRecord> = history.filter { it.batterSeat == batter }

    private val typeByName = PitchType.entries.associateBy { it.name }

    private fun choosePitch(seat: Int, level: CpuDifficulty): PitchPick {
        val p = BaseballRules.profile(level)
        val mustZone = balls >= 3
        var pick: PitchPick = if (random.nextDouble() < p.noise) {
            // A human-feeling, unplanned pitch; a weak pitcher is also wild.
            randomPitch(mustZone, if (level == CpuDifficulty.EASY) 0.65 else 0.8)
        } else planPitch(seat, p.adapt, mustZone)
        if (p.wild > 0 && random.nextDouble() < p.wild) {
            val r = (pick.row + random.nextInt(3) - 1).coerceIn(0, 4)
            val c = (pick.col + random.nextInt(3) - 1).coerceIn(0, 4)
            pick = PitchPick(pick.type, r, c)
        }
        return pick
    }

    /** The pitch the batter's habits make least dangerous (lowest expected value for the batter). */
    private fun planPitch(seat: Int, adapt: Double, mustZone: Boolean): PitchPick {
        val habits = if (random.nextDouble() < adapt) batterHabits(seat) else emptyList()
        // Weights: recent swings count more; a thin prior of "a fastball hitter aiming at the middle".
        val swings = ArrayList<Pair<BaseballSwing, Double>>()
        swings += BaseballSwing(false, 1, 1, PitchType.FASTBALL, false) to 0.8
        swings += BaseballSwing(false, 1, 1, PitchType.SLIDER, false) to 0.3
        swings += BaseballSwing(false, 0, 1, PitchType.FASTBALL, false) to 0.3
        swings += BaseballSwing.TAKE to 0.2
        var w = 1.0
        for (h in habits.asReversed()) {
            swings += (if (!h.swung) BaseballSwing.TAKE else BaseballSwing(false, h.swingRow, h.swingCol, typeByName[h.timing] ?: PitchType.FASTBALL, h.power)) to w
            w *= 0.88
        }
        var best: PitchPick? = null
        var bestVal = Double.MAX_VALUE
        for (type in PitchType.entries) for (r in 0 until 5) for (c in 0 until 5) {
            if (mustZone && !BaseballRules.inZone(r, c)) continue
            var total = 0.0; var wsum = 0.0
            for ((sw, wt) in swings) {
                total += wt * (if (sw.take) BaseballRules.takeValue(r, c, balls, strikes)
                else ValueTable.of(BaseballRules.quality(type, r, c, sw, 0), sw.power, strikes))
                wsum += wt
            }
            val v = total / wsum + random.nextDouble() * 0.02
            if (v < bestVal) { bestVal = v; best = PitchPick(type, r, c) }
        }
        return best!!
    }

    private fun chooseSwing(seat: Int, level: CpuDifficulty): BaseballSwing {
        val p = BaseballRules.profile(level)
        if (random.nextDouble() < p.noise) {
            if (balls >= 3 && random.nextDouble() < 0.7) return BaseballSwing.TAKE
            if (strikes < 2 && random.nextDouble() < p.takeRate) return BaseballSwing.TAKE
            val timing = if (random.nextDouble() < 0.5) PitchType.FASTBALL else PitchType.entries[random.nextInt(4)]
            return BaseballSwing(false, 1 + random.nextInt(3) - 1, 1 + random.nextInt(3) - 1, timing, random.nextDouble() < 0.25)
        }
        return planSwing(seat, p.adapt)
    }

    private fun planSwing(seat: Int, adapt: Double): BaseballSwing {
        val habits = if (random.nextDouble() < adapt) pitcherHabits(seat) else emptyList()
        data class Sample(val type: PitchType, val row: Int, val col: Int, val w: Double)
        val samples = ArrayList<Sample>()
        samples += Sample(PitchType.FASTBALL, 2, 2, 0.9)
        samples += Sample(PitchType.SLIDER, 2, 3, 0.4)
        samples += Sample(PitchType.CURVEBALL, 3, 2, 0.4)
        samples += Sample(PitchType.CHANGEUP, 2, 1, 0.4)
        samples += Sample(PitchType.FASTBALL, 0, 2, 0.15)
        var w = 1.0
        for (h in habits.asReversed()) {
            samples += Sample(typeByName[h.type] ?: PitchType.FASTBALL, h.row, h.col, w)
            w *= 0.88
        }
        val wsum = samples.sumOf { it.w }
        var best: BaseballSwing = BaseballSwing.TAKE
        var bestVal = samples.sumOf { it.w * BaseballRules.takeValue(it.row, it.col, balls, strikes) } / wsum
        for (power in listOf(false, true)) for (timing in PitchType.entries) for (r in 0..2) for (c in 0..2) {
            val sw = BaseballSwing(false, r, c, timing, power)
            var total = 0.0
            for (s in samples) total += s.w * ValueTable.of(BaseballRules.quality(s.type, s.row, s.col, sw, 0), power, strikes)
            val v = total / wsum + random.nextDouble() * 0.02
            if (v > bestVal) { bestVal = v; best = sw }
        }
        return best
    }

    // ---- hint ----------------------------------------------------------------------------------

    /** Suggests a pitch (when [seat] pitches) or a swing (when it bats), from the game so far. Offline only. */
    @Synchronized
    fun hint(seat: Int = if (this::context.isInitialized) context.localPlayerIndex.coerceIn(0, 1) else 0): BaseballHint? {
        if (isNetworked) return null
        val s = state.value ?: return null
        if (matchOver.value || s.over) return null
        val h = if (seat == pitcherSeat && phase == BaseballPhase.PITCH) {
            val pick = planPitch(seat, 1.0, balls >= 3)
            BaseballHint(pick.type, pick.row, pick.col)
        } else if (seat == batterSeat && phase == BaseballPhase.SWING) {
            val sw = planSwing(seat, 1.0)
            if (sw.take) BaseballHint(PitchType.FASTBALL, -1, -1) else BaseballHint(sw.timing, sw.row, sw.col, sw.power)
        } else null
        hint.value = h
        return h
    }

    // ---- state building --------------------------------------------------------------------------

    private fun commit() {
        state.value = buildState(if (isNetworked) context.localPlayerIndex else -1)
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun pendingVisibleTo(viewer: Int): Boolean =
        if (viewer >= 0) viewer == pitcherSeat else !isBotSeat(pitcherSeat)

    private fun buildState(viewer: Int) = BaseballState(
        players = context.players.map { BaseballPlayer(it.playerId, it.displayName, it.isBot) },
        innings = innings, inning = inning, top = top, outs = outs, balls = balls, strikes = strikes,
        bases = bases.toList(), score = score.toList(), hits = hits.toList(),
        lineScore = lineScore.map { it.toList() },
        batterSeat = batterSeat, pitcherSeat = pitcherSeat, phase = phase.name,
        pending = if (pendingType != null && pendingVisibleTo(viewer)) PendingPitch(pendingType!!.name, pendingRow, pendingCol) else null,
        last = last, lastRuns = lastRuns, lastOuts = lastOuts, history = history.toList(), pitchCount = pitchCount.toList(),
        eventSerial = eventSerial, halfEnded = halfEnded, over = over, winnerPlayerId = winnerPlayerId,
        sessionWins = sessionWins.value, sessionDraws = sessionDraws.value
    )

    // ---- resume --------------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) {
        try {
            if (matchOver.value || state.value == null || over) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            baseballJson.encodeToString(
                BaseballSnapshot.serializer(),
                BaseballSnapshot(buildState(-1).copy(pending = pendingType?.let { PendingPitch(it.name, pendingRow, pendingCol) }), awaySeat, difficulty.name)
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Structural checks shared by a restored save and a host snapshot. */
    private fun validState(s: BaseballState): Boolean {
        val roster = context.players
        if (roster.size != 2 || s.players.size != 2) return false
        if (!s.players.indices.all { s.players[it].playerId == roster[it].playerId }) return false
        val lines = s.lineScore
        return s.innings in BaseballRules.MIN_INNINGS..BaseballRules.MAX_INNINGS &&
            s.inning in 1..(s.innings + BaseballRules.MAX_EXTRA_INNINGS) &&
            s.outs in 0 until BaseballRules.OUTS_PER_HALF && s.balls in 0 until BaseballRules.BALLS_FOR_WALK &&
            s.strikes in 0 until BaseballRules.STRIKES_FOR_OUT && s.bases.size == 3 &&
            s.score.size == 2 && s.score.all { it in 0..999 } && s.hits.size == 2 && s.hits.all { it in 0..999 } &&
            lines.size == 2 && lines.all { l -> l.size <= s.innings + BaseballRules.MAX_EXTRA_INNINGS && l.all { it in 0..99 } } &&
            lines.indices.all { lines[it].sum() == s.score[it] } &&
            s.batterSeat in 0..1 && s.pitcherSeat == 1 - s.batterSeat &&
            BaseballPhase.entries.any { it.name == s.phase } &&
            (s.pending == null || (typeByName[s.pending.type] != null && s.pending.row in 0..4 && s.pending.col in 0..4)) &&
            (s.pending == null || s.phaseValue == BaseballPhase.SWING) &&
            (s.last == null || validRecord(s.last)) && s.history.size <= BaseballRules.HISTORY_KEPT && s.history.all { validRecord(it) } &&
            s.lastRuns in 0..4 && s.lastOuts in 0..3 && s.pitchCount.size == 2 && s.pitchCount.all { it >= 0 } &&
            s.eventSerial >= 0 && (s.winnerPlayerId == null || roster.any { it.playerId == s.winnerPlayerId }) &&
            s.sessionDraws >= 0 && s.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } }
    }

    private fun validRecord(r: PitchRecord): Boolean =
        r.batterSeat in 0..1 && typeByName[r.type] != null && typeByName[r.timing] != null &&
            r.row in 0..4 && r.col in 0..4 && r.swingRow in -1..2 && r.swingCol in -1..2 && r.quality in -200..300 && r.outcome.length < 40

    @Synchronized
    override fun restore(snapshot: String): Boolean {
        return try {
            val snap = baseballJson.decodeFromString(BaseballSnapshot.serializer(), snapshot)
            val s = snap.state
            if (!validState(s) || snap.awaySeat !in 0..1 || s.over || s.phaseValue == BaseballPhase.OVER) return false
            if (s.batterSeat != (if (s.top) snap.awaySeat else 1 - snap.awaySeat)) return false
            innings = s.innings; inning = s.inning; top = s.top; outs = s.outs; balls = s.balls; strikes = s.strikes
            for (i in 0..2) bases[i] = s.bases[i]
            for (i in 0..1) { score[i] = s.score[i]; hits[i] = s.hits[i]; pitchCount[i] = s.pitchCount[i] }
            lineScore = s.lineScore.map { it.toMutableList() }
            for (l in lineScore) while (l.size < inning) l += 0
            awaySeat = snap.awaySeat
            difficulty = CpuDifficulty.entries.firstOrNull { it.name == snap.difficulty } ?: difficulty
            phase = s.phaseValue
            val pend = s.pending
            if (phase == BaseballPhase.SWING && pend == null) return false
            pendingType = pend?.let { typeByName[it.type] }
            pendingRow = pend?.row ?: 0; pendingCol = pend?.col ?: 0
            last = s.last; lastRuns = s.lastRuns; lastOuts = s.lastOuts; halfEnded = false
            history.clear(); history.addAll(s.history)
            over = false; winnerPlayerId = null
            sessionWins.value = s.sessionWins; sessionDraws.value = s.sessionDraws
            matchOver.value = false
            eventSerial = s.eventSerial + 1
            thinkAt = 0L
            hint.value = null
            state.value = buildState(-1)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---- networking ----------------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
        if (!isHost) sendToHost(ToyBaseballNetMessage.RequestState)
        transport.onReconnected {
            synchronized(lock) {
                if (isHost) { if (state.value != null) { stateVersion++; broadcastState() } } else sendToHost(ToyBaseballNetMessage.RequestState)
            }
        }
        transport.onPlayerLeft { id -> synchronized(lock) { if (isNetworked && id == otherSeatId()) opponentLeft.value = true } }
        transport.onPlayerJoined { id ->
            synchronized(lock) {
                if (isNetworked && opponentLeft.value && id == otherSeatId()) {
                    opponentLeft.value = false
                    if (isHost && state.value != null) { stateVersion++; broadcastState() }
                }
            }
        }
    }

    private fun otherSeatId(): String? = context.players.getOrNull(1 - context.localPlayerIndex.coerceIn(0, 1))?.playerId

    private fun broadcastState() {
        if (state.value == null) return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val payload = baseballJson.encodeToString(
            ToyBaseballNetMessage.serializer(), ToyBaseballNetMessage.StateSync(stateVersion, buildState(1))
        ).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun sendToHost(message: ToyBaseballNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = baseballJson.encodeToString(ToyBaseballNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun sendToGuest(message: ToyBaseballNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val payload = baseballJson.encodeToString(ToyBaseballNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            baseballJson.decodeFromString(ToyBaseballNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        synchronized(lock) {
            val hostId = context.players.getOrNull(0)?.playerId
            val guestId = context.players.getOrNull(1)?.playerId
            when (message) {
                is ToyBaseballNetMessage.StateSync ->
                    if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
                is ToyBaseballNetMessage.Rejected ->
                    if (!isHost && fromPlayerId == hostId) {
                        BaseballReject.entries.firstOrNull { it.name == message.reason }?.takeIf { it != BaseballReject.NONE }?.let { note(it) }
                    }
                is ToyBaseballNetMessage.RequestState ->
                    if (isHost && fromPlayerId == guestId && state.value != null) { stateVersion++; broadcastState() }
                is ToyBaseballNetMessage.PitchIntent ->
                    if (isHost && fromPlayerId == guestId && state.value != null && !matchOver.value && !opponentLeft.value) {
                        val t = typeByName[message.pitchType]
                        val r = if (t == null) BaseballReject.BAD_INPUT else applyPitch(1, t, message.row, message.col)
                        if (r == BaseballReject.NONE) commit() else sendToGuest(ToyBaseballNetMessage.Rejected(r.name))
                    }
                is ToyBaseballNetMessage.SwingIntent ->
                    if (isHost && fromPlayerId == guestId && state.value != null && !matchOver.value && !opponentLeft.value) {
                        val t = typeByName[message.timing]
                        val r = if (t == null) BaseballReject.BAD_INPUT
                        else applySwing(1, BaseballSwing(message.take, message.row, message.col, t, message.power))
                        if (r == BaseballReject.NONE) commit() else sendToGuest(ToyBaseballNetMessage.Rejected(r.name))
                    }
                is ToyBaseballNetMessage.RequestPlayAgain ->
                    if (isHost && fromPlayerId == guestId && !opponentLeft.value && state.value?.over == true && !matchOver.value) {
                        awaySeat = 1 - awaySeat
                        startMatch()
                    }
            }
        }
    }

    private fun applyRemoteState(version: Int, net: BaseballState) {
        if (version <= lastAppliedRemoteVersion) return
        if (!validState(net)) return
        // Only the pitcher's own seat may hold the pending pitch.
        if (net.pending != null && net.pitcherSeat != context.localPlayerIndex) return
        lastAppliedRemoteVersion = version
        innings = net.innings
        awaySeat = if (net.top) net.batterSeat else net.pitcherSeat
        val roster = context.players
        state.value = net.copy(players = net.players.mapIndexed { i, p -> p.copy(isBot = roster[i].isBot) })
        sessionWins.value = net.sessionWins
        sessionDraws.value = net.sessionDraws
        hint.value = null
    }
}
