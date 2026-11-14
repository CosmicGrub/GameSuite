package com.gamesuite.games.toyboxing

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

enum class BoxingPhase { COUNTDOWN, FIGHT, KNOCKDOWN, BREAK, OVER }

/** What the last change to the ring was; the screen words, animates, sounds and announces it. */
object BoxingEvent {
    const val START = "start"
    const val ROUND_START = "round_start"
    const val JAB_HIT = "jab_hit"
    const val HOOK_HIT = "hook_hit"
    const val BLOCKED = "blocked"
    const val DODGED = "dodged"
    const val GUARD_BREAK = "guard_break"
    const val KNOCKDOWN = "knockdown"
    const val COUNT = "count"
    const val GET_UP = "get_up"
    const val ROUND_END = "round_end"
    const val KO = "ko"
    const val TKO = "tko"
    const val DECISION = "decision"
    const val DRAW = "draw"

    /** Turn-based: an attacker has declared a punch and the defender must answer. */
    const val ANNOUNCE = "announce"
    const val REST = "rest"
}

/** Why an action did nothing. [NONE] means it worked. */
enum class BoxingReject {
    NONE, NOT_PLAYING, NOT_STARTED, NOT_YOUR_SEAT, BUSY, STUNNED, EXHAUSTED, NOT_YOUR_TURN, BAD_STAGE, NEUTRAL_CORNER
}

@Serializable
data class BoxingPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

/** One fighter as the screen sees it. [action] is IDLE, JAB, HOOK, DODGE, STAGGER or DOWN. */
@Serializable
data class BoxFighter(
    val hp: Int,
    val stamina: Int,
    val action: String,
    val actionAgeMs: Int,
    val guard: Boolean,
    val points: Int,
    val totalPoints: Int,
    val roundsWon: Int,
    val knockdowns: Int,
    val counter: Boolean,
    val riseTaps: Int,
    val riseNeeded: Int
)

/**
 * What the ring looks like to a device. Serializable: it is also the host's network snapshot (boxing has
 * no hidden information). Seats are -1 when there is none. [eventSerial] only ever grows and keys the
 * screen's animations and sounds.
 */
@Serializable
data class BoxingState(
    val players: List<BoxingPlayer>,
    val fighters: List<BoxFighter>,
    val phase: String,
    val round: Int,
    val rounds: Int,
    val roundLeftMs: Int,
    val phaseLeftMs: Int,
    val count: Int,
    val downSeat: Int,
    val assist: Boolean,
    val assistActor: Int,
    val assistStage: String,
    val assistPending: String,
    val exchangesLeft: Int,
    val knockouts: Boolean,
    val event: String,
    val eventSeat: Int,
    val eventAmount: Int,
    val eventSerial: Int,
    val over: Boolean,
    val winnerPlayerId: String?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
) {
    val phaseValue: BoxingPhase get() = BoxingPhase.entries.firstOrNull { it.name == phase } ?: BoxingPhase.OVER
}

@Serializable
private data class BoxingSnapshot(
    val players: List<BoxingPlayer>,
    val hp: List<Int>,
    val stamina: List<Int>,
    val roundsWon: List<Int>,
    val points: List<Int>,
    val totals: List<Int>,
    val knockdowns: List<Int>,
    val round: Int,
    val rounds: Int,
    val roundLeftMs: Int,
    val exchangesLeft: Int,
    val assist: Boolean,
    val assistActor: Int,
    val knockouts: Boolean,
    val difficulty: String,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val eventSerial: Int = 0
)

private val boxingJson = Json { ignoreUnknownKeys = true }

/**
 * Toy Boxing: two toy boxers in a ring. See [BoxingRules] for the numbers.
 *
 * REAL TIME BY SIMULATION. Nothing runs by itself: the screen calls [tick] about 25 times a second and the
 * engine advances a fixed-step simulation up to the injected [clock]. Input ([play]) first catches the
 * simulation up to "now", then applies, so a fake clock and a seeded [Random] replay a fight exactly.
 * Jab beats a winding-up hook, a dodge beats a hook, a guard beats a jab, a hook breaks a guard: skill
 * (reading the tell, managing stamina) beats button-mashing.
 *
 * THE COMPUTER reacts like a boxer: it notices a wind-up, waits a reaction time that depends on the level
 * (Easy 0.6 s, Medium 0.3 s, Hard 0.17 s, each varied 25 percent), then answers it correctly only so often.
 * A hook has a 450 ms tell, so Easy practically never dodges one; Hard usually jabs or dodges it. A jab (100
 * ms) cannot be reacted to by anybody: only a guard raised in advance stops it.
 *
 * TURN-BASED ASSIST ([assist]) is for players who cannot play in real time: the seats alternate exchanges;
 * the attacker announces a punch (or rests), then the defender, who has seen it, chooses block, dodge or
 * take it. Same damage, stamina, knockdown and round rules; no clock at all.
 *
 * NETWORKING: the host (seat 0) runs the only simulation. A guest sends [ToyBoxingNetMessage.Intent]s and
 * adopts the host's strictly newer, validated [ToyBoxingNetMessage.StateSync]s.
 */
class ToyBoxingGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "toy-boxing"
    override val displayName = "Toy Boxing"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<BoxingState?>(null)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)
    val matchOver = mutableStateOf(false)
    val opponentLeft = mutableStateOf(false)

    /** What the last [hint] suggested; cleared by the next change to the ring. */
    val hintAction = mutableStateOf<BoxingAction?>(null)

    /** The last refusal of a local seat (reason, serial that only grows). */
    val lastReject = mutableStateOf<Pair<BoxingReject, Int>?>(null)

    /** Pre-set by the screen from the Hub before [init]. */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var assist: Boolean = false
    var rounds: Int = BoxingRules.DEFAULT_ROUNDS
    var knockouts: Boolean = true

    /** Tests may give the two computer seats different levels. */
    internal val seatLevelOverride = arrayOfNulls<CpuDifficulty>(2)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this // one monitor for @Synchronized entry points and network callbacks alike

    private enum class Act { IDLE, JAB, HOOK, DOWN, DODGE, STAGGER }
    private enum class Defence { NONE, BLOCK, DODGE }

    private class F {
        var hp = BoxingRules.MAX_HP
        var stamina = BoxingRules.MAX_STAMINA.toDouble()
        var act = Act.IDLE
        var actStart = 0L
        var actEnd = 0L
        var move: BoxingMove? = null
        var struck = false
        var guard = false
        var guardAt = 0L
        var counterUntil = 0L
        var counterAssist = false
        var points = 0
        var total = 0
        var roundsWon = 0
        var kds = 0
        var riseTaps = 0
        var riseNeeded = 0
        val isAttacking get() = act == Act.JAB || act == Act.HOOK
    }

    private val f = arrayOf(F(), F())
    private val comboHits = IntArray(2)
    private val jabStreak = IntArray(2)
    private val lastJabEnd = LongArray(2) { -100_000L }
    private val lastHitAt = LongArray(2) { -100_000L }
    private var phase = BoxingPhase.OVER
    private var round = 1
    private var simT = 0L
    private var lastSim = -1L
    private var phaseEnd = 0L
    private var roundLeft = 0L
    private var count = 0
    private var countAt = 0L
    private var downSeat = -1
    private var assistActor = 0
    private var assistStage = "ATTACK"
    private var assistPending: BoxingMove? = null
    private var exchangesLeft = 0
    private var event = BoxingEvent.START
    private var eventSeat = -1
    private var eventAmount = 0
    private var eventSerial = 0
    private var over = false
    private var winnerPlayerId: String? = null
    private var nextOpener = 0
    private var rejectSerial = 0

    // computer memory
    private val nextAct = LongArray(2)
    private val seenAttack = LongArray(2) { -1L }
    private val reactAt = LongArray(2)
    private val reactOk = BooleanArray(2)
    private val reactDone = LongArray(2) { -1L }
    private val nextTap = LongArray(2)
    private val guardedAfterHit = LongArray(2) { -1L }
    private val counteredJab = LongArray(2) { -1L }
    private val guardUntil = LongArray(2)
    private val assistThinkAt = LongArray(2)

    // networking
    private val isNetworked: Boolean
        get() = this::context.isInitialized && (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0
    private var lastBroadcastSim = -1_000L
    private var lastBroadcastEvent = -1
    private var lastBroadcastPhase = ""

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            opponentLeft.value = false
            sessionWins.value = context.players.associate { it.playerId to 0 }
            sessionDraws.value = 0
            hintAction.value = null
            lastReject.value = null
            state.value = null
            phase = BoxingPhase.OVER
            over = false
            winnerPlayerId = null
            nextOpener = 0
            stateVersion = com.gamesuite.core.SyncEpoch.base()
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
        newBout()
        commit(force = true)
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session, reporting bouts won (only a strict leader wins). */
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
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        if (isNetworked) {
            if (opponentLeft.value || state.value?.over != true) return
            if (isHost) startMatch() else sendToHost(ToyBoxingNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    private fun newBout() {
        for (s in 0..1) {
            f[s] = F()
        }
        comboHits.fill(0); jabStreak.fill(0); lastJabEnd.fill(-100_000L); lastHitAt.fill(-100_000L); guardedAfterHit.fill(-1L); counteredJab.fill(-1L)
        rounds = rounds.coerceIn(1, 9)
        round = 1
        over = false
        winnerPlayerId = null
        simT = 0L
        lastSim = clock()
        count = 0; downSeat = -1
        hintAction.value = null
        nextAct.fill(0L); seenAttack.fill(-1L); reactDone.fill(-1L); nextTap.fill(0L); guardUntil.fill(0L); assistThinkAt.fill(0L)
        assistActor = nextOpener % 2
        nextOpener = (nextOpener + 1) % 2
        assistStage = "ATTACK"; assistPending = null
        exchangesLeft = BoxingRules.ASSIST_EXCHANGES
        setEvent(BoxingEvent.START, -1, 0)
        if (assist) {
            phase = BoxingPhase.FIGHT
        } else {
            phase = BoxingPhase.COUNTDOWN
            phaseEnd = simT + BoxingRules.COUNTDOWN_MS
        }
        roundLeft = BoxingRules.ROUND_MS
    }

    private fun setEvent(kind: String, seat: Int, amount: Int) {
        event = kind; eventSeat = seat; eventAmount = amount; eventSerial++
        hintAction.value = null
    }

    // ---- input ---------------------------------------------------------------------------

    /**
     * Seat [seat] asks its fighter to [action]. Offline either side may be tapped; networked only this
     * device's own seat (a guest sends an intent and waits for the host's state). Returns [BoxingReject.NONE]
     * when it happened (or, for a guest, was sent).
     */
    @Synchronized
    fun play(seat: Int, action: BoxingAction): BoxingReject {
        val s = state.value ?: return BoxingReject.NOT_PLAYING
        if (matchOver.value || s.over) return BoxingReject.NOT_PLAYING
        if (isNetworked) {
            if (seat != context.localPlayerIndex || opponentLeft.value) return BoxingReject.NOT_YOUR_SEAT
            if (!isHost) {
                if (s.phaseValue == BoxingPhase.COUNTDOWN) return noteReject(BoxingReject.NOT_STARTED)
                sendToHost(ToyBoxingNetMessage.Intent(action.name))
                return BoxingReject.NONE
            }
        }
        val r = applyIntent(seat, action)
        // Even a refused action may have caught the simulation up (and ended the bout): always publish the ring.
        commit(force = r == BoxingReject.NONE)
        if (r != BoxingReject.NONE && !isBotSeat(seat)) noteReject(r)
        return r
    }

    private fun noteReject(r: BoxingReject): BoxingReject {
        lastReject.value = r to ++rejectSerial
        return r
    }

    private fun applyIntent(seat: Int, action: BoxingAction): BoxingReject {
        if (seat !in 0..1) return BoxingReject.NOT_YOUR_SEAT
        if (phase == BoxingPhase.OVER) return BoxingReject.NOT_PLAYING
        if (assist) return applyAssist(seat, action)
        advanceTo(clock())
        if (phase == BoxingPhase.OVER) return BoxingReject.NOT_PLAYING
        if (phase == BoxingPhase.COUNTDOWN || phase == BoxingPhase.BREAK) return BoxingReject.NOT_STARTED
        val me = f[seat]
        if (me.act == Act.DOWN) {
            me.riseTaps++
            return BoxingReject.NONE
        }
        if (phase == BoxingPhase.KNOCKDOWN) return BoxingReject.NEUTRAL_CORNER
        when (action) {
            BoxingAction.TAP, BoxingAction.REST -> return BoxingReject.BAD_STAGE
            BoxingAction.GUARD_DOWN -> { me.guard = false; return BoxingReject.NONE }
            else -> {}
        }
        if (me.act == Act.STAGGER) return BoxingReject.STUNNED
        if (me.act != Act.IDLE) return BoxingReject.BUSY
        return when (action) {
            BoxingAction.BLOCK -> { if (!me.guard) { me.guard = true; me.guardAt = simT }; BoxingReject.NONE }
            BoxingAction.DODGE -> {
                if (me.stamina < BoxingRules.DODGE_COST) return BoxingReject.EXHAUSTED
                me.stamina -= BoxingRules.DODGE_COST
                me.guard = false; me.act = Act.DODGE; me.actStart = simT; me.actEnd = simT + BoxingRules.DODGE_TOTAL_MS; me.move = null
                BoxingReject.NONE
            }
            BoxingAction.JAB, BoxingAction.HOOK -> {
                val m = if (action == BoxingAction.JAB) BoxingMove.JAB else BoxingMove.HOOK
                if (me.stamina < costOf(seat, m)) return BoxingReject.EXHAUSTED
                startMove(seat, m)
                BoxingReject.NONE
            }
            else -> BoxingReject.BAD_STAGE
        }
    }

    /** Which jab of a streak the next jab by [seat] would be (1 = a fresh one). */
    private fun nextJabStreak(seat: Int): Int =
        if (simT - lastJabEnd[seat] <= BoxingRules.JAB_STREAK_GAP_MS) jabStreak[seat] + 1 else 1

    /** What [m] costs [seat] right now: jabs in a streak cost more, so button-mashing burns out. */
    private fun costOf(seat: Int, m: BoxingMove): Int =
        if (m == BoxingMove.JAB) m.cost + BoxingRules.jabSurcharge(nextJabStreak(seat)) else m.cost

    private fun startMove(seat: Int, m: BoxingMove) {
        val me = f[seat]
        me.stamina -= costOf(seat, m)
        if (m == BoxingMove.JAB) { jabStreak[seat] = nextJabStreak(seat); lastJabEnd[seat] = simT + m.totalMs } else jabStreak[seat] = 0
        me.guard = false
        me.act = if (m == BoxingMove.JAB) Act.JAB else Act.HOOK
        me.move = m; me.struck = false
        me.actStart = simT; me.actEnd = simT + m.totalMs
    }

    // ---- the clock -----------------------------------------------------------------------

    /**
     * Moves time on: runs the fight simulation to now and lets the computer act. Call it often (about every
     * 40 ms). A networked guest does nothing here (the host runs the clock). Returns true if the ring changed.
     */
    @Synchronized
    fun tick(): Boolean {
        if (isNetworked && !isHost) return false
        if (state.value == null || matchOver.value) return false
        val changed = if (assist) assistTick(clock()) else advanceTo(clock())
        if (changed) commit(force = false)
        return changed
    }

    private fun advanceTo(now: Long): Boolean {
        if (assist || phase == BoxingPhase.OVER) return false
        if (lastSim < 0) lastSim = now
        var steps = 0
        var any = false
        while (lastSim + BoxingRules.STEP_MS <= now && steps < BoxingRules.MAX_CATCHUP_STEPS && phase != BoxingPhase.OVER) {
            lastSim += BoxingRules.STEP_MS
            simT += BoxingRules.STEP_MS
            step()
            steps++
            any = true
        }
        if (lastSim + BoxingRules.STEP_MS <= now) lastSim = now // a long gap (app paused): drop the excess instead of fast-forwarding a fight
        return any
    }

    private fun step() {
        when (phase) {
            BoxingPhase.COUNTDOWN -> if (simT >= phaseEnd) {
                phase = BoxingPhase.FIGHT
                setEvent(BoxingEvent.ROUND_START, -1, round)
            }
            BoxingPhase.BREAK -> if (simT >= phaseEnd) beginNextRound()
            BoxingPhase.KNOCKDOWN -> knockdownStep()
            BoxingPhase.FIGHT -> fightStep()
            BoxingPhase.OVER -> {}
        }
    }

    private fun beginNextRound() {
        round++
        for (s in 0..1) {
            val x = f[s]
            x.points = 0; x.kds = 0; x.act = Act.IDLE; x.guard = false; x.stamina = BoxingRules.MAX_STAMINA.toDouble(); x.move = null
        }
        roundLeft = BoxingRules.ROUND_MS
        exchangesLeft = BoxingRules.ASSIST_EXCHANGES
        phase = BoxingPhase.COUNTDOWN
        phaseEnd = simT + 1_500L
        setEvent(BoxingEvent.START, -1, round)
    }

    private fun fightStep() {
        for (seat in 0..1) if (isBotSeat(seat)) botThink(seat)
        if (phase != BoxingPhase.FIGHT) return
        val strikes = (0..1).filter { val x = f[it]; x.isAttacking && !x.struck && simT >= x.actStart + x.move!!.windupMs }
        val dodging = BooleanArray(2) { val x = f[it]; x.act == Act.DODGE && simT - x.actStart in BoxingRules.DODGE_SAFE_FROM_MS..BoxingRules.DODGE_SAFE_TO_MS }
        val guarding = BooleanArray(2) { val x = f[it]; x.guard && x.act == Act.IDLE && simT >= x.guardAt + BoxingRules.BLOCK_RAISE_MS }
        val dizzy = BooleanArray(2) { f[it].act == Act.STAGGER }
        val recovering = BooleanArray(2) { f[it].isAttacking && f[it].struck }
        val cancel = arrayOfNulls<BoxingMove>(2)
        val results = ArrayList<Triple<Int, Outcome, BoxingMove>>(2)
        for (a in strikes) {
            val d = 1 - a
            val m = f[a].move!!
            f[a].struck = true
            val defence = when {
                dodging[d] -> Defence.DODGE
                guarding[d] -> Defence.BLOCK
                else -> Defence.NONE
            }
            val out = resolve(a, d, m, defence, counter = f[a].counterUntil > simT || (BoxingRules.CATCH_OFF_BALANCE && recovering[d]), dizzy = dizzy[d])
            results += Triple(a, out, m)
            if (out.landed && d !in strikes && f[d].isAttacking && !f[d].struck) cancel[d] = m
        }
        for ((a, out, m) in results) {
            val d = 1 - a
            when (out.kind) {
                BoxingEvent.DODGED -> { f[a].actEnd += BoxingRules.WHIFF_PENALTY_MS; f[d].counterUntil = simT + BoxingRules.COUNTER_WINDOW_MS }
                BoxingEvent.BLOCKED -> {}
                BoxingEvent.GUARD_BREAK -> if (!out.knocked) stagger(d, BoxingRules.GUARD_BREAK_STAGGER_MS.toLong())
                else -> if (!out.knocked) stagger(d, m.hitStaggerMs.toLong())
            }
            val ev = if (out.knocked) BoxingEvent.KNOCKDOWN else out.kind
            setEvent(ev, if (out.knocked) d else a, out.amount)
            if (out.knocked) knockDown(d)
        }
        if (phase != BoxingPhase.FIGHT) return
        for (s in 0..1) {
            val x = f[s]
            if (x.act != Act.IDLE && x.act != Act.DOWN && simT >= x.actEnd) {
                x.act = Act.IDLE; x.move = null
            }
            val rate = when {
                x.act == Act.IDLE && x.guard -> BoxingRules.REGEN_PER_SEC_GUARD
                x.act == Act.IDLE -> BoxingRules.REGEN_PER_SEC_IDLE
                else -> 0.0
            }
            x.stamina = (x.stamina + rate * BoxingRules.STEP_MS / 1000.0).coerceAtMost(BoxingRules.MAX_STAMINA.toDouble())
        }
        roundLeft -= BoxingRules.STEP_MS
        if (roundLeft <= 0L) endRound()
    }

    private fun stagger(seat: Int, ms: Long) {
        val x = f[seat]
        x.guard = false
        x.act = Act.STAGGER; x.actStart = simT; x.actEnd = simT + ms; x.move = null
    }

    private class Outcome(val kind: String, val amount: Int, val landed: Boolean, val knocked: Boolean)

    /** One punch against whatever defence it met. Shared by real time and turn-based. Mutates hp, stamina and points. */
    private fun resolve(a: Int, d: Int, m: BoxingMove, defence: Defence, counter: Boolean, dizzy: Boolean): Outcome {
        val atk = f[a]; val def = f[d]
        atk.counterUntil = 0L; atk.counterAssist = false
        when (defence) {
            Defence.DODGE -> {
                def.counterUntil = simT + BoxingRules.COUNTER_WINDOW_MS
                def.counterAssist = true
                return Outcome(BoxingEvent.DODGED, 0, landed = false, knocked = false)
            }
            Defence.BLOCK -> {
                val dmg = (m.damage * m.guardPass).toInt() // a guarded jab does no damage: only a hook gets through
                val ko = hurt(d, dmg)
                def.stamina -= m.guardCost
                if (def.stamina <= 0.0) {
                    def.stamina = 0.0
                    return Outcome(BoxingEvent.GUARD_BREAK, dmg, landed = true, knocked = ko)
                }
                return Outcome(BoxingEvent.BLOCKED, dmg, landed = false, knocked = ko)
            }
            Defence.NONE -> {
                // Real time only: a chain of hits on the same boxer does less and less (and stops scoring).
                var combo = 1
                if (!assist) {
                    combo = if (simT - lastHitAt[d] <= BoxingRules.COMBO_WINDOW_MS) comboHits[d] + 1 else 1
                    comboHits[d] = combo; lastHitAt[d] = simT
                }
                var dmg = BoxingRules.damage(m, counter, atk.stamina < BoxingRules.TIRED_BELOW, dizzy)
                if (combo > 1) dmg = Math.round(dmg * BoxingRules.comboMultiplier(combo)).toInt().coerceAtLeast(1)
                val pts = if (combo > BoxingRules.COMBO_SCORED_HITS) 0 else BoxingRules.points(m, counter)
                atk.points += pts; atk.total += pts
                val ko = hurt(d, dmg)
                return Outcome(if (m == BoxingMove.JAB) BoxingEvent.JAB_HIT else BoxingEvent.HOOK_HIT, dmg, landed = true, knocked = ko)
            }
        }
    }

    /** Applies damage; true when it floors the fighter (knockouts on). */
    private fun hurt(seat: Int, dmg: Int): Boolean {
        val x = f[seat]
        x.hp -= dmg
        if (x.hp <= 0) {
            if (knockouts) { x.hp = 0; return true }
            x.hp = 1
        }
        return false
    }

    // ---- knockdowns, rounds, the decision ---------------------------------------------------

    private fun knockDown(seat: Int) {
        val x = f[seat]
        val o = f[1 - seat]
        x.kds++
        o.points += BoxingRules.KNOCKDOWN_POINTS; o.total += BoxingRules.KNOCKDOWN_POINTS
        x.guard = false; o.guard = false
        if (x.kds >= BoxingRules.TKO_KNOCKDOWNS) {
            finishBout(1 - seat, BoxingEvent.TKO)
            return
        }
        if (assist) {
            x.hp = BoxingRules.ASSIST_RISE_HP
            return
        }
        phase = BoxingPhase.KNOCKDOWN
        downSeat = seat
        count = 0
        countAt = simT + BoxingRules.COUNT_STEP_MS
        x.act = Act.DOWN; x.actStart = simT; x.move = null
        x.riseTaps = 0; x.riseNeeded = BoxingRules.riseNeeded(x.kds - 1)
        o.act = Act.IDLE; o.move = null
        nextTap[seat] = simT + 400L
    }

    private fun knockdownStep() {
        val x = f[downSeat]
        if (isBotSeat(downSeat)) botThink(downSeat)
        if (simT >= countAt) {
            count++
            countAt += BoxingRules.COUNT_STEP_MS
            if (count >= BoxingRules.COUNT_MAX) {
                finishBout(1 - downSeat, BoxingEvent.KO)
                return
            }
            setEvent(BoxingEvent.COUNT, downSeat, count)
        }
        if (x.riseTaps >= x.riseNeeded && count >= BoxingRules.COUNT_MIN_TO_RISE) {
            x.hp = BoxingRules.riseHp(x.kds - 1)
            x.stamina = maxOf(x.stamina, 50.0)
            for (s in 0..1) stagger(s, 700L)
            val who = downSeat
            downSeat = -1; count = 0
            phase = BoxingPhase.FIGHT
            setEvent(BoxingEvent.GET_UP, who, 0)
        }
    }

    private fun endRound() {
        val a = f[0]; val b = f[1]
        val winner = when {
            a.points > b.points -> 0
            b.points > a.points -> 1
            a.hp > b.hp -> 0
            b.hp > a.hp -> 1
            else -> -1
        }
        if (winner >= 0) f[winner].roundsWon++
        setEvent(BoxingEvent.ROUND_END, winner, round)
        if (round >= rounds) {
            decide()
            return
        }
        for (s in 0..1) {
            val x = f[s]
            x.hp = minOf(BoxingRules.MAX_HP, x.hp + BoxingRules.BREAK_HEAL)
            x.act = Act.IDLE; x.guard = false; x.move = null
        }
        if (assist) {
            beginNextRound()
            phase = BoxingPhase.FIGHT
            assistStage = "ATTACK"; assistPending = null
        } else {
            phase = BoxingPhase.BREAK
            phaseEnd = simT + BoxingRules.BREAK_MS
        }
    }

    private fun decide() {
        val a = f[0]; val b = f[1]
        val w = when {
            a.roundsWon > b.roundsWon -> 0
            b.roundsWon > a.roundsWon -> 1
            a.total > b.total -> 0
            b.total > a.total -> 1
            else -> -1
        }
        finishBout(w, if (w >= 0) BoxingEvent.DECISION else BoxingEvent.DRAW)
    }

    private fun finishBout(winnerSeat: Int, how: String) {
        phase = BoxingPhase.OVER
        over = true
        winnerPlayerId = if (winnerSeat >= 0) context.players[winnerSeat].playerId else null
        setEvent(how, winnerSeat, 0)
        for (s in 0..1) { f[s].guard = false; if (f[s].act != Act.DOWN) f[s].act = Act.IDLE }
        if (winnerSeat >= 0) {
            val id = context.players[winnerSeat].playerId
            sessionWins.value = sessionWins.value + (id to (sessionWins.value[id] ?: 0) + 1)
        } else {
            sessionDraws.value += 1
        }
    }

    // ---- the computer (real time) -------------------------------------------------------------

    private fun jit(base: Int): Long = (base * (1.0 - BoxingRules.JITTER + random.nextDouble() * 2 * BoxingRules.JITTER)).toLong()

    private fun botThink(seat: Int) {
        val me = f[seat]; val opp = f[1 - seat]
        val p = BoxingRules.profile(levelOf(seat))
        if (me.act == Act.DOWN) {
            if (simT >= nextTap[seat]) {
                me.riseTaps++
                nextTap[seat] = simT + jit(p.tapMs)
            }
            return
        }
        if (phase != BoxingPhase.FIGHT || me.act != Act.IDLE) return
        // Just taken a hit and free again: a boxer who has been hit puts the guard up before the next one lands.
        if (lastHitAt[seat] != guardedAfterHit[seat] && simT - lastHitAt[seat] <= 600L) {
            guardedAfterHit[seat] = lastHitAt[seat]
            if (random.nextDouble() < p.reactAccuracy) {
                me.guard = true; me.guardAt = simT; guardUntil[seat] = simT + jit(500)
                return
            }
        }
        // A quick rival's jab has just landed or been blocked and is still recovering: punish it with a jab of our own.
        if (opp.act == Act.JAB && opp.struck && counteredJab[seat] != opp.actStart && me.stamina >= costOf(seat, BoxingMove.JAB)) {
            counteredJab[seat] = opp.actStart
            if (random.nextDouble() < p.reactAccuracy * p.reactAccuracy * 0.9) {
                startMove(seat, BoxingMove.JAB)
                return
            }
        }
        // A rival mashing jabs: sit behind the guard (their jabs do nothing against it and the mashing burns their stamina out).
        val oppSpam = jabStreak[1 - seat] >= 2 && (opp.act == Act.JAB || simT - lastJabEnd[1 - seat] <= 350L)
        if (oppSpam && !me.guard && !opp.struck && random.nextDouble() < p.reactAccuracy * p.exploitRate) {
            me.guard = true; me.guardAt = simT; guardUntil[seat] = simT + jit(500)
            return
        }
        if (opp.isAttacking && !opp.struck) {
            if (seenAttack[seat] != opp.actStart) {
                seenAttack[seat] = opp.actStart
                reactAt[seat] = opp.actStart + jit(p.reactMs)
                reactOk[seat] = random.nextDouble() < p.reactAccuracy
            }
            if (reactOk[seat] && simT >= reactAt[seat] && reactDone[seat] != opp.actStart) {
                reactDone[seat] = opp.actStart
                if (opp.move == BoxingMove.HOOK) {
                    val canJab = me.stamina >= costOf(seat, BoxingMove.JAB)
                    val canDodge = me.stamina >= BoxingRules.DODGE_COST
                    when {
                        canJab && (!canDodge || random.nextDouble() < 0.5) -> startMove(seat, BoxingMove.JAB)
                        canDodge -> {
                            me.stamina -= BoxingRules.DODGE_COST
                            me.guard = false; me.act = Act.DODGE; me.actStart = simT; me.actEnd = simT + BoxingRules.DODGE_TOTAL_MS
                        }
                        else -> if (!me.guard) { me.guard = true; me.guardAt = simT }
                    }
                } else if (!me.guard) {
                    me.guard = true; me.guardAt = simT; guardUntil[seat] = simT + 500L
                }
                return
            }
        }
        if (me.guard && simT < guardUntil[seat]) return
        if (simT < nextAct[seat]) return
        nextAct[seat] = simT + jit(p.decisionMs)
        val oppStunned = opp.act == Act.STAGGER
        val oppOpen = oppStunned || (opp.isAttacking && opp.struck) || (opp.act == Act.DODGE && simT - opp.actStart > BoxingRules.DODGE_SAFE_TO_MS)
        if (!oppOpen && random.nextDouble() < p.guardRate) {
            me.guard = true; me.guardAt = simT; guardUntil[seat] = simT + jit(700)
            return
        }
        // A rival too tired to jab cannot cancel a hook's wind-up (the stamina bar is on screen for both): hit them with one.
        val oppCantCancel = (opp.act == Act.IDLE || (opp.isAttacking && opp.struck)) &&
            opp.stamina + BoxingRules.REGEN_PER_SEC_IDLE * BoxingMove.HOOK.windupMs / 1000.0 * 0.6 < costOf(1 - seat, BoxingMove.JAB) + 8 &&
            me.stamina >= BoxingMove.HOOK.cost && !opp.guard
        if (oppCantCancel && random.nextDouble() < p.exploitRate) {
            startMove(seat, BoxingMove.HOOK)
            return
        }
        val wantHook = when {
            // A hook needs the rival locked down long enough: otherwise their quick jab cancels the wind-up.
            oppOpen -> opp.actEnd - simT > 350L && random.nextDouble() < 0.85
            opp.guard -> random.nextDouble() < 0.5
            else -> random.nextDouble() < p.hookRate
        }
        val move = if (wantHook && me.stamina >= BoxingMove.HOOK.cost) BoxingMove.HOOK else BoxingMove.JAB
        if (me.stamina < costOf(seat, move) || (me.stamina < p.staminaFloor && !oppOpen)) {
            me.guard = true; me.guardAt = simT; guardUntil[seat] = simT + 600L
            return
        }
        startMove(seat, move)
    }

    // ---- turn-based assist ---------------------------------------------------------------------

    private fun applyAssist(seat: Int, action: BoxingAction): BoxingReject {
        if (phase != BoxingPhase.FIGHT) return BoxingReject.NOT_PLAYING
        val actor = assistActor
        if (assistStage == "ATTACK") {
            if (seat != actor) return BoxingReject.NOT_YOUR_TURN
            val me = f[seat]
            when (action) {
                BoxingAction.JAB, BoxingAction.HOOK -> {
                    val m = if (action == BoxingAction.JAB) BoxingMove.JAB else BoxingMove.HOOK
                    if (me.stamina < m.cost) return BoxingReject.EXHAUSTED
                    me.stamina -= m.cost
                    assistPending = m
                    assistStage = "DEFEND"
                    setEvent(BoxingEvent.ANNOUNCE, seat, m.damage)
                }
                BoxingAction.REST -> {
                    me.stamina = minOf(BoxingRules.MAX_STAMINA.toDouble(), me.stamina + BoxingRules.ASSIST_REST_REGEN)
                    setEvent(BoxingEvent.REST, seat, BoxingRules.ASSIST_REST_REGEN)
                    finishExchange()
                }
                else -> return BoxingReject.BAD_STAGE
            }
            return BoxingReject.NONE
        }
        val def = 1 - actor
        if (seat != def) return BoxingReject.NOT_YOUR_TURN
        val m = assistPending ?: return BoxingReject.BAD_STAGE
        val defence = when (action) {
            BoxingAction.BLOCK -> Defence.BLOCK
            BoxingAction.DODGE -> {
                if (f[seat].stamina < BoxingRules.DODGE_COST) return BoxingReject.EXHAUSTED
                f[seat].stamina -= BoxingRules.DODGE_COST
                Defence.DODGE
            }
            BoxingAction.REST -> Defence.NONE
            else -> return BoxingReject.BAD_STAGE
        }
        val atk = f[actor]
        val out = resolve(actor, def, m, defence, counter = atk.counterAssist, dizzy = false)
        val ev = if (out.knocked) BoxingEvent.KNOCKDOWN else out.kind
        setEvent(ev, if (out.knocked) def else actor, out.amount)
        assistPending = null
        if (out.knocked) knockDown(def)
        if (phase == BoxingPhase.OVER) return BoxingReject.NONE
        finishExchange()
        return BoxingReject.NONE
    }

    private fun finishExchange() {
        assistStage = "ATTACK"
        assistPending = null
        for (s in 0..1) f[s].stamina = minOf(BoxingRules.MAX_STAMINA.toDouble(), f[s].stamina + BoxingRules.ASSIST_REGEN)
        assistActor = 1 - assistActor
        exchangesLeft--
        assistThinkAt.fill(0L)
        if (exchangesLeft <= 0) endRound()
    }

    private fun assistTick(now: Long): Boolean {
        if (phase != BoxingPhase.FIGHT) return false
        val seat = if (assistStage == "ATTACK") assistActor else 1 - assistActor
        if (!isBotSeat(seat)) return false
        val p = BoxingRules.profile(levelOf(seat))
        if (assistThinkAt[seat] == 0L) {
            assistThinkAt[seat] = now + (p.thinkMs * (0.8 + random.nextDouble() * 0.4)).toLong()
            return false
        }
        if (now < assistThinkAt[seat]) return false
        assistThinkAt[seat] = 0L
        val smart = random.nextDouble() < p.assistAccuracy
        val me = f[seat]; val opp = f[1 - seat]
        val action: BoxingAction = if (assistStage == "ATTACK") {
            when {
                me.stamina < BoxingMove.JAB.cost -> BoxingAction.REST
                smart -> when {
                    opp.stamina < BoxingRules.DODGE_COST && me.stamina >= BoxingMove.HOOK.cost -> BoxingAction.HOOK
                    me.stamina < 30 -> if (me.stamina < 18) BoxingAction.REST else BoxingAction.JAB
                    else -> if (random.nextDouble() < 0.3) BoxingAction.HOOK else BoxingAction.JAB
                }
                else -> if (random.nextBoolean() || me.stamina < BoxingMove.HOOK.cost) BoxingAction.JAB else BoxingAction.HOOK
            }
        } else {
            val m = assistPending ?: BoxingMove.JAB
            if (smart) BoxingRules.bestDefence(m, me.stamina.toInt())
            else listOf(BoxingAction.BLOCK, BoxingAction.DODGE, BoxingAction.REST)[random.nextInt(3)]
        }
        var r = applyIntent(seat, action)
        if (r != BoxingReject.NONE) {
            // The random pick was not affordable: fall back to the cheapest safe choice.
            r = applyIntent(seat, if (assistStage == "ATTACK") BoxingAction.REST else BoxingAction.BLOCK)
            if (r != BoxingReject.NONE) applyIntent(seat, BoxingAction.REST)
        }
        return true
    }

    // ---- hint ----------------------------------------------------------------------------------

    /** Suggests the move that answers the ring right now for [seat] (offline only); null when nothing helps. */
    @Synchronized
    fun hint(seat: Int = if (this::context.isInitialized) context.localPlayerIndex.coerceIn(0, 1) else 0): BoxingAction? {
        if (isNetworked) return null
        val s = state.value ?: return null
        if (matchOver.value || s.over) return null
        val me = f[seat]; val opp = f[1 - seat]
        val h: BoxingAction? = if (assist) {
            when {
                s.assistStage == "ATTACK" && s.assistActor == seat ->
                    if (me.stamina < 18) BoxingAction.REST
                    else if (opp.stamina < BoxingRules.DODGE_COST && me.stamina >= BoxingMove.HOOK.cost) BoxingAction.HOOK else BoxingAction.JAB
                s.assistStage == "DEFEND" && s.assistActor != seat ->
                    BoxingRules.bestDefence(assistPending ?: BoxingMove.JAB, me.stamina.toInt())
                else -> null
            }
        } else if (phase != BoxingPhase.FIGHT) null else when {
            opp.isAttacking && !opp.struck && opp.move == BoxingMove.HOOK ->
                if (me.stamina >= BoxingRules.DODGE_COST) BoxingAction.DODGE else BoxingAction.BLOCK
            opp.isAttacking && !opp.struck -> BoxingAction.BLOCK
            opp.act == Act.STAGGER && me.stamina >= BoxingMove.HOOK.cost -> BoxingAction.HOOK
            me.stamina < 30 -> BoxingAction.BLOCK
            else -> BoxingAction.JAB
        }
        hintAction.value = h
        return h
    }

    // ---- state building --------------------------------------------------------------------------

    private fun commit(force: Boolean) {
        state.value = buildState()
        if (isNetworked && isHost) {
            val due = force || eventSerial != lastBroadcastEvent || phase.name != lastBroadcastPhase || simT - lastBroadcastSim >= 100L
            if (due) {
                lastBroadcastSim = simT; lastBroadcastEvent = eventSerial; lastBroadcastPhase = phase.name
                stateVersion++
                broadcastState()
            }
        }
        if (over) hintAction.value = null
    }

    private fun fighterState(s: Int): BoxFighter {
        val x = f[s]
        val age = if (x.act == Act.IDLE) 0 else (simT - x.actStart).toInt().coerceIn(0, 10_000)
        val guardUp = x.guard && x.act == Act.IDLE
        return BoxFighter(
            hp = x.hp, stamina = x.stamina.toInt().coerceIn(0, BoxingRules.MAX_STAMINA), action = x.act.name,
            actionAgeMs = age, guard = guardUp, points = x.points, totalPoints = x.total, roundsWon = x.roundsWon,
            knockdowns = x.kds,
            counter = if (assist) x.counterAssist else x.counterUntil > simT,
            riseTaps = x.riseTaps, riseNeeded = x.riseNeeded
        )
    }

    private fun buildState() = BoxingState(
        players = context.players.map { BoxingPlayer(it.playerId, it.displayName, it.isBot) },
        fighters = listOf(fighterState(0), fighterState(1)),
        phase = phase.name, round = round, rounds = rounds,
        roundLeftMs = roundLeft.coerceAtLeast(0L).toInt(),
        phaseLeftMs = if (phase == BoxingPhase.COUNTDOWN || phase == BoxingPhase.BREAK) (phaseEnd - simT).coerceAtLeast(0L).toInt() else 0,
        count = count, downSeat = downSeat, assist = assist, assistActor = assistActor, assistStage = assistStage,
        assistPending = assistPending?.name ?: "", exchangesLeft = exchangesLeft, knockouts = knockouts,
        event = event, eventSeat = eventSeat, eventAmount = eventAmount, eventSerial = eventSerial,
        over = over, winnerPlayerId = winnerPlayerId,
        sessionWins = sessionWins.value, sessionDraws = sessionDraws.value
    )

    // ---- resume --------------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) {
        try {
            if (matchOver.value || state.value == null || over || phase == BoxingPhase.KNOCKDOWN) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            boxingJson.encodeToString(
                BoxingSnapshot.serializer(),
                BoxingSnapshot(
                    players = context.players.map { BoxingPlayer(it.playerId, it.displayName, it.isBot) },
                    hp = f.map { it.hp }, stamina = f.map { it.stamina.toInt() }, roundsWon = f.map { it.roundsWon },
                    points = f.map { it.points }, totals = f.map { it.total }, knockdowns = f.map { it.kds },
                    round = round, rounds = rounds, roundLeftMs = roundLeft.toInt().coerceAtLeast(1), exchangesLeft = exchangesLeft.coerceAtLeast(1),
                    assist = assist, assistActor = assistActor, knockouts = knockouts, difficulty = difficulty.name,
                    sessionWins = sessionWins.value, sessionDraws = sessionDraws.value, eventSerial = eventSerial
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSnapshot(text: String): BoxingSnapshot? = try {
        val s = boxingJson.decodeFromString(BoxingSnapshot.serializer(), text)
        val roster = context.players
        val two = listOf(s.hp, s.stamina, s.roundsWon, s.points, s.totals, s.knockdowns).all { it.size == 2 }
        val valid = two && s.players.size == 2 && roster.size == 2 &&
            s.players.indices.all { s.players[it].playerId == roster[it].playerId && s.players[it].isBot == roster[it].isBot } &&
            s.hp.all { it in 1..BoxingRules.MAX_HP } && s.stamina.all { it in 0..BoxingRules.MAX_STAMINA } &&
            s.rounds in 1..9 && s.round in 1..s.rounds && s.roundsWon.all { it in 0..s.rounds } &&
            s.points.all { it >= 0 } && s.totals.all { it >= 0 } && s.knockdowns.all { it in 0 until BoxingRules.TKO_KNOCKDOWNS } &&
            s.roundLeftMs in 1..BoxingRules.ROUND_MS.toInt() && s.exchangesLeft in 1..BoxingRules.ASSIST_EXCHANGES &&
            s.assistActor in 0..1 && s.sessionDraws >= 0 &&
            s.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } && s.eventSerial >= 0
        if (valid) s else null
    } catch (_: Exception) {
        null
    }

    /** Puts the bout back as saved, into a fresh countdown (turn-based resumes straight away). */
    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val s = decodeSnapshot(snapshot) ?: return false
        for (i in 0..1) {
            val x = F()
            x.hp = s.hp[i]; x.stamina = s.stamina[i].toDouble(); x.roundsWon = s.roundsWon[i]
            x.points = s.points[i]; x.total = s.totals[i]; x.kds = s.knockdowns[i]
            f[i] = x
        }
        matchOver.value = false
        over = false; winnerPlayerId = null
        round = s.round; rounds = s.rounds; roundLeft = s.roundLeftMs.toLong(); exchangesLeft = s.exchangesLeft
        assist = s.assist; assistActor = s.assistActor; assistStage = "ATTACK"; assistPending = null
        knockouts = s.knockouts
        difficulty = CpuDifficulty.entries.firstOrNull { it.name == s.difficulty } ?: difficulty
        sessionWins.value = s.sessionWins; sessionDraws.value = s.sessionDraws
        eventSerial = s.eventSerial
        simT = 0L; lastSim = clock(); count = 0; downSeat = -1
        nextAct.fill(0L); seenAttack.fill(-1L); reactDone.fill(-1L); assistThinkAt.fill(0L)
        hintAction.value = null
        if (s.assist) {
            phase = BoxingPhase.FIGHT
        } else {
            phase = BoxingPhase.COUNTDOWN
            phaseEnd = simT + BoxingRules.COUNTDOWN_MS
        }
        setEvent(BoxingEvent.START, -1, round)
        state.value = buildState()
        return true
    }

    // ---- networking ----------------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
        if (!isHost) sendToHost(ToyBoxingNetMessage.RequestState)
        transport.onReconnected {
            synchronized(lock) {
                if (isHost) { if (state.value != null) { stateVersion++; broadcastState() } } else sendToHost(ToyBoxingNetMessage.RequestState)
            }
        }
        transport.onPlayerLeft { id -> synchronized(lock) { handlePlayerLeft(id) } }
        transport.onPlayerJoined { id -> synchronized(lock) { handlePlayerJoined(id) } }
    }

    private fun otherSeatId(): String? = context.players.getOrNull(1 - context.localPlayerIndex.coerceIn(0, 1))?.playerId

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked || playerId != otherSeatId()) return
        opponentLeft.value = true
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked || !opponentLeft.value || playerId != otherSeatId()) return
        opponentLeft.value = false
        if (isHost && state.value != null) {
            stateVersion++
            broadcastState()
        }
    }

    private fun broadcastState() {
        val s = state.value ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val payload = boxingJson.encodeToString(ToyBoxingNetMessage.serializer(), ToyBoxingNetMessage.StateSync(stateVersion, s)).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun sendToHost(message: ToyBoxingNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = boxingJson.encodeToString(ToyBoxingNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun sendToGuest(message: ToyBoxingNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val payload = boxingJson.encodeToString(ToyBoxingNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            boxingJson.decodeFromString(ToyBoxingNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        synchronized(lock) {
            val hostId = context.players.getOrNull(0)?.playerId
            val guestId = context.players.getOrNull(1)?.playerId
            when (message) {
                // Only the host is authoritative: a StateSync from any other sender is a forgery.
                is ToyBoxingNetMessage.StateSync ->
                    if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
                is ToyBoxingNetMessage.Rejected ->
                    if (!isHost && fromPlayerId == hostId) {
                        BoxingReject.entries.firstOrNull { it.name == message.reason }?.takeIf { it != BoxingReject.NONE }?.let { noteReject(it) }
                    }
                is ToyBoxingNetMessage.RequestState ->
                    if (isHost && fromPlayerId == guestId && state.value != null) { stateVersion++; broadcastState() }
                is ToyBoxingNetMessage.Intent ->
                    if (isHost && fromPlayerId == guestId) hostHandleIntent(message.action)
                is ToyBoxingNetMessage.RequestPlayAgain ->
                    if (isHost && fromPlayerId == guestId && !opponentLeft.value && state.value?.over == true && !matchOver.value) startMatch()
            }
        }
    }

    private fun hostHandleIntent(actionName: String) {
        if (state.value == null || matchOver.value || opponentLeft.value) return
        val action = BoxingAction.entries.firstOrNull { it.name == actionName } ?: return
        val r = applyIntent(1, action)
        commit(force = r == BoxingReject.NONE)
        if (r != BoxingReject.NONE) sendToGuest(ToyBoxingNetMessage.Rejected(r.name))
    }

    /** Guest: adopts a strictly newer, well-formed host state. */
    private fun applyRemoteState(version: Int, net: BoxingState) {
        if (version <= lastAppliedRemoteVersion) return
        if (!validRemote(net)) return
        lastAppliedRemoteVersion = version
        assist = net.assist
        rounds = net.rounds
        knockouts = net.knockouts
        val roster = context.players
        state.value = net.copy(players = net.players.mapIndexed { i, p -> p.copy(isBot = roster[i].isBot) })
        sessionWins.value = net.sessionWins
        sessionDraws.value = net.sessionDraws
        hintAction.value = null
    }

    private fun validRemote(net: BoxingState): Boolean {
        val roster = context.players
        val actions = Act.entries.map { it.name }.toSet()
        return roster.size == 2 && context.localPlayerIndex in 0..1 &&
            net.players.size == 2 && net.players.indices.all { net.players[it].playerId == roster[it].playerId } &&
            net.fighters.size == 2 &&
            net.fighters.all {
                it.hp in 0..BoxingRules.MAX_HP && it.stamina in 0..BoxingRules.MAX_STAMINA && it.action in actions &&
                    it.actionAgeMs in 0..10_000 && it.points in 0..10_000 && it.totalPoints in 0..100_000 &&
                    it.roundsWon in 0..9 && it.knockdowns in 0..BoxingRules.TKO_KNOCKDOWNS && it.riseTaps in 0..1_000 && it.riseNeeded in 0..100
            } &&
            BoxingPhase.entries.any { it.name == net.phase } &&
            net.rounds in 1..9 && net.round in 1..net.rounds && net.roundLeftMs in 0..BoxingRules.ROUND_MS.toInt() &&
            net.phaseLeftMs in 0..10_000 && net.count in 0..BoxingRules.COUNT_MAX &&
            net.downSeat in -1..1 && net.assistActor in 0..1 && (net.assistStage == "ATTACK" || net.assistStage == "DEFEND") &&
            (net.assistPending.isEmpty() || BoxingMove.entries.any { it.name == net.assistPending }) &&
            net.exchangesLeft in 0..BoxingRules.ASSIST_EXCHANGES && net.eventSeat in -1..1 && net.eventAmount in -1..10_000 &&
            net.eventSerial >= 0 && (net.winnerPlayerId == null || roster.any { it.playerId == net.winnerPlayerId }) &&
            net.sessionDraws >= 0 && net.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } }
    }

    companion object {
        /** The seat whose punch is currently winding up (real time), else -1. For the screen's tell. */
        fun windingUp(s: BoxingState): Int =
            s.fighters.indexOfFirst { it.action == "HOOK" || it.action == "JAB" }
    }
}
