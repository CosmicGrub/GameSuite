package com.gamesuite.games.holdem

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** How a computer player leans, on top of its level. */
enum class BotStyle(val id: String, val tightness: Double, val aggression: Double) {
    BALANCED("balanced", 0.0, 1.0),
    TIGHT("tight", 1.6, 0.9),
    LOOSE("loose", -1.6, 1.25);

    companion object {
        fun fromId(id: String?): BotStyle? = values().firstOrNull { it.id == id }

        /**
         * The style of the CPU in [seat]. [setting] is a Hub choice: "balanced", "tight", "loose", or "mixed"
         * (anything else), where each seat takes a different style so the table feels like different people.
         */
        fun forSeat(setting: String?, seat: Int): BotStyle = fromId(setting) ?: values()[((seat % 3) + 3) % 3]
    }
}

/** Everything a computer player (or the hint) is allowed to know when it decides: its own cards and the public table. */
class BotView(
    val hole: List<Int>,
    val board: List<Int>,
    val street: HoldemStreet,
    val pot: Int,
    val chips: Int,
    val bet: Int,
    val currentBet: Int,
    val options: HoldemOptions,
    val bigBlind: Int,
    /** Opponents still holding cards (all-in ones included). */
    val opponents: Int,
    /** 0.0 acts first after the flop, 1.0 is the button (acts last). */
    val position: Double,
    val limpers: Int
) {
    val toCall: Int get() = options.toCall
    val raisedPreflop: Boolean get() = street == HoldemStreet.PREFLOP && currentBet > bigBlind
}

enum class HintAdvice { FOLD, CHECK, CALL, RAISE }

/** The hint: the odds in plain numbers plus what a sensible player would do. [raiseTo] is the suggested total for [HintAdvice.RAISE]. */
data class HoldemHint(
    val equity: Double,
    val potOdds: Double,
    val toCall: Int,
    val pot: Int,
    val advice: HintAdvice,
    val raiseTo: Int,
    val opponents: Int
)

object HoldemBot {

    fun equityIterations(level: CpuDifficulty): Int = when (level) {
        CpuDifficulty.EASY -> 0
        CpuDifficulty.MEDIUM -> 160
        CpuDifficulty.HARD -> 900
    }

    /** The view [seat] has of [s]; null when it is not that seat's turn. */
    fun view(s: HoldemState, seat: Int): BotView? {
        val o = HoldemRules.options(s, seat) ?: return null
        val p = s.seats[seat]
        if (p.hole.size != 2 || p.hole.any { it == HIDDEN_CARD }) return null
        val n = s.seats.size
        val order = (1..n).map { (s.button + it) % n }.filter { s.seats[it].live }
        val idx = order.indexOf(seat).coerceAtLeast(0)
        val position = if (order.size <= 1) 1.0 else idx.toDouble() / (order.size - 1)
        val limpers = if (s.street == HoldemStreet.PREFLOP && s.currentBet <= s.bigBlind) {
            s.seats.indices.count { it != seat && s.seats[it].live && s.seats[it].bet == s.bigBlind && it != s.bigBlindSeat }
        } else 0
        return BotView(
            hole = p.hole, board = s.board, street = s.street, pot = s.totalPot, chips = p.chips, bet = p.bet,
            currentBet = s.currentBet, options = o, bigBlind = s.bigBlind,
            opponents = s.seats.indices.count { it != seat && s.seats[it].live },
            position = position, limpers = limpers
        )
    }

    /** Chooses a legal action. [equity] (win share vs random hands) is required for Medium and Hard, ignored by Easy. */
    fun decide(v: BotView, level: CpuDifficulty, style: BotStyle, random: Random, equity: Double?): HoldemAction {
        val raw = when (level) {
            CpuDifficulty.EASY -> easy(v, style, random)
            CpuDifficulty.MEDIUM -> smart(v, style, random, equity ?: HoldemEquity.madeStrength(v.hole, v.board), hard = false)
            CpuDifficulty.HARD -> smart(v, style, random, equity ?: HoldemEquity.madeStrength(v.hole, v.board), hard = true)
        }
        return legalize(raw, v.options)
    }

    /** Turns any wish into something the rules accept. */
    fun legalize(a: HoldemAction, o: HoldemOptions): HoldemAction = when (a.kind) {
        HoldemActionKind.FOLD -> if (o.canCheck) HoldemAction.CHECK else HoldemAction.FOLD
        HoldemActionKind.CHECK -> if (o.canCheck) a else if (o.canCall) HoldemAction.CALL else HoldemAction.FOLD
        HoldemActionKind.CALL -> if (o.canCall) a else if (o.canCheck) HoldemAction.CHECK else HoldemAction.FOLD
        HoldemActionKind.ALL_IN -> if (o.canAllIn) a else callOrCheck(o)
        HoldemActionKind.RAISE_TO -> when {
            !o.canRaise -> callOrCheck(o)
            a.amount >= o.maxRaiseTo -> if (o.canAllIn) HoldemAction.ALL_IN else callOrCheck(o)
            else -> HoldemAction.raiseTo(a.amount.coerceIn(o.minRaiseTo, o.maxRaiseTo))
        }
    }

    private fun callOrCheck(o: HoldemOptions): HoldemAction =
        if (o.canCheck) HoldemAction.CHECK else if (o.canCall) HoldemAction.CALL else HoldemAction.FOLD

    private fun foldOrCheck(o: HoldemOptions): HoldemAction = if (o.canCheck) HoldemAction.CHECK else HoldemAction.FOLD

    /** A raise to [target] (total street bet), becoming a shove once it would commit most of the stack. */
    private fun raiseOrShove(v: BotView, target: Int): HoldemAction {
        val o = v.options
        if (!o.canRaise) return callOrCheck(o)
        val stack = v.chips + v.bet
        val t = target.coerceIn(o.minRaiseTo, o.maxRaiseTo)
        return if (t >= o.maxRaiseTo || t >= stack * 0.55) HoldemAction.ALL_IN else HoldemAction.raiseTo(t)
    }

    private fun betSize(v: BotView, fractionOfPot: Double): Int {
        val o = v.options
        val extra = (fractionOfPot * (v.pot + o.toCall)).toInt()
        return v.currentBet + max(extra, v.bigBlind)
    }

    // ---- Easy: loose and passive, and it slips --------------------------------------------------

    private fun easy(v: BotView, style: BotStyle, rnd: Random): HoldemAction {
        val o = v.options
        val strength = if (v.board.size < 3) HoldemEquity.chen(v.hole) / 20.0 + 0.1 else HoldemEquity.madeStrength(v.hole, v.board)
        val noisy = strength + (rnd.nextDouble() - 0.5) * 0.4 - style.tightness * 0.03
        if (o.toCall == 0) {
            if (o.canRaise && noisy > 0.82 && rnd.nextDouble() < 0.35 * style.aggression) return raiseOrShove(v, betSize(v, 0.4))
            if (o.canRaise && noisy > 0.6 && rnd.nextDouble() < 0.12 * style.aggression) return raiseOrShove(v, betSize(v, 0.3))
            return HoldemAction.CHECK
        }
        val potOdds = o.toCall.toDouble() / (v.pot + o.toCall)
        // The slips: it sometimes throws away a good hand and sometimes pays for nothing.
        if (strength > 0.55 && rnd.nextDouble() < 0.09) return HoldemAction.FOLD
        if (o.toCall <= v.chips * 0.25 && rnd.nextDouble() < 0.12) return HoldemAction.CALL
        if (o.canRaise && noisy > 0.9 && rnd.nextDouble() < 0.25 * style.aggression) return raiseOrShove(v, betSize(v, 0.6))
        val willingness = noisy * 0.9 + 0.12
        return if (willingness > potOdds + 0.08) HoldemAction.CALL else HoldemAction.FOLD
    }

    // ---- Medium and Hard ------------------------------------------------------------------------

    private fun smart(v: BotView, style: BotStyle, rnd: Random, equity: Double, hard: Boolean): HoldemAction =
        if (v.street == HoldemStreet.PREFLOP) preflop(v, style, rnd, equity, hard) else postflop(v, style, rnd, equity, hard)

    private fun preflop(v: BotView, style: BotStyle, rnd: Random, equity: Double, hard: Boolean): HoldemAction {
        val o = v.options
        val chen = HoldemEquity.chen(v.hole)
        val bb = v.bigBlind
        val stackBb = (v.chips + v.bet).toDouble() / bb
        val pos = if (hard) v.position else 0.5
        var openThr = 9.0 - 3.5 * pos + style.tightness + (if (hard) 0.0 else 0.6)
        if (v.opponents <= 2) openThr -= 2.0
        val potOdds = if (o.toCall > 0) o.toCall.toDouble() / (v.pot + o.toCall) else 0.0

        if (stackBb <= 10.0) {
            // Short stack: shove or fold.
            val thr = openThr - 1.5 - (10.0 - stackBb) * 0.25
            return if (chen >= thr) HoldemAction.ALL_IN else foldOrCheck(o)
        }
        if (!v.raisedPreflop) {
            return when {
                chen >= openThr -> {
                    val mult = (2.5 + v.limpers + rnd.nextDouble() * 0.6) * (if (style == BotStyle.LOOSE) 1.1 else 1.0)
                    raiseOrShove(v, (bb * mult).toInt().coerceAtLeast(bb * 2))
                }
                o.canCheck -> HoldemAction.CHECK
                chen >= openThr - 2.0 && o.toCall <= bb && (!hard || v.position < 0.4 || v.opponents <= 2) -> HoldemAction.CALL
                else -> HoldemAction.FOLD
            }
        }
        val premium = chen >= 12.0
        if (premium && o.canRaise) return raiseOrShove(v, (v.currentBet * 3.0 + v.limpers * bb).toInt())
        val ownShare = o.toCall.toDouble() / (v.chips + v.bet)
        val needed = potOdds + 0.04 + style.tightness * 0.008
        return if (equity >= needed && chen >= openThr - 0.5 && (ownShare < 0.35 || chen >= 10.0)) HoldemAction.CALL else HoldemAction.FOLD
    }

    private fun postflop(v: BotView, style: BotStyle, rnd: Random, equity: Double, hard: Boolean): HoldemAction {
        val o = v.options
        val opps = max(1, v.opponents)
        val fair = 1.0 / (opps + 1)
        val tier = (equity - fair) / (1.0 - fair)
        val posBonus = if (hard) (v.position - 0.5) * 0.1 else 0.0
        val aggr = style.aggression
        val bluffFreq = if (hard) 0.16 else 0.05
        val shaded = tier + posBonus - style.tightness * 0.02

        if (o.toCall == 0) {
            if (shaded >= 0.3) {
                if (rnd.nextDouble() < 0.85 * min(1.2, aggr)) {
                    val frac = 0.45 + 0.4 * shaded.coerceAtMost(1.0) + (rnd.nextDouble() - 0.5) * 0.15
                    return raiseOrShove(v, betSize(v, frac))
                }
                return HoldemAction.CHECK
            }
            if (shaded >= 0.12 && rnd.nextDouble() < 0.33 * aggr) return raiseOrShove(v, betSize(v, 0.35 + rnd.nextDouble() * 0.15))
            if (shaded < 0.12 && opps <= 2 && v.street != HoldemStreet.RIVER && rnd.nextDouble() < bluffFreq * aggr) {
                return raiseOrShove(v, betSize(v, 0.55 + rnd.nextDouble() * 0.15))
            }
            if (shaded < 0.12 && opps == 1 && v.street == HoldemStreet.RIVER && hard && rnd.nextDouble() < bluffFreq * 0.5 * aggr) {
                return raiseOrShove(v, betSize(v, 0.65))
            }
            return HoldemAction.CHECK
        }

        val potOdds = o.toCall.toDouble() / (v.pot + o.toCall)
        // Against a bet the opponent's range is stronger than a random hand: shade the estimate down.
        val pressure = min(1.5, o.toCall.toDouble() / max(1, v.pot - o.toCall))
        val effective = if (hard) equity - 0.045 * pressure else equity - 0.02 * pressure
        val margin = (if (hard) 0.0 else 0.03) + style.tightness * 0.01
        if (shaded >= 0.55 && o.canRaise && rnd.nextDouble() < 0.7 * min(1.2, aggr)) {
            return raiseOrShove(v, v.currentBet + ((0.7 + rnd.nextDouble() * 0.4) * (v.pot + o.toCall)).toInt())
        }
        if (effective >= potOdds + margin) return HoldemAction.CALL
        // A semi-bluff raise now and then with a drawing hand, otherwise let it go.
        if (hard && o.canRaise && opps <= 2 && v.board.size < 5 && effective >= potOdds * 0.7 && rnd.nextDouble() < 0.1 * aggr) {
            return raiseOrShove(v, v.currentBet + ((0.75) * (v.pot + o.toCall)).toInt())
        }
        return HoldemAction.FOLD
    }

    // ---- Hint -----------------------------------------------------------------------------------

    /** Advice for the seat on turn given its measured [equity] against random hands. */
    fun hint(v: BotView, equity: Double): HoldemHint {
        val o = v.options
        val opps = max(1, v.opponents)
        val fair = 1.0 / (opps + 1)
        val tier = (equity - fair) / (1.0 - fair)
        val potOdds = if (o.toCall > 0) o.toCall.toDouble() / (v.pot + o.toCall) else 0.0
        val advice: HintAdvice
        var raiseTo = 0
        if (o.toCall == 0) {
            if (tier >= 0.3 && o.canRaise) {
                advice = HintAdvice.RAISE
                raiseTo = betSize(v, 0.6).coerceIn(o.minRaiseTo, o.maxRaiseTo)
            } else advice = HintAdvice.CHECK
        } else {
            val effective = equity - 0.03 * min(1.5, o.toCall.toDouble() / max(1, v.pot - o.toCall))
            advice = when {
                effective < potOdds -> HintAdvice.FOLD
                tier >= 0.55 && o.canRaise -> HintAdvice.RAISE
                else -> HintAdvice.CALL
            }
            if (advice == HintAdvice.RAISE) {
                raiseTo = (v.currentBet + 0.8 * (v.pot + o.toCall)).toInt().coerceIn(o.minRaiseTo, o.maxRaiseTo)
            }
        }
        return HoldemHint(equity, potOdds, o.toCall, v.pot, advice, raiseTo, opps)
    }
}
