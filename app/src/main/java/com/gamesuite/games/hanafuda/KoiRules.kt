package com.gamesuite.games.hanafuda

import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Where a Koi-Koi round stands. The player to move is [KoiRound.turn]. */
enum class KoiPhase {
    /** Play one card from the hand: match it with a field card of the same month, or discard it. */
    PLAY_HAND,

    /** The hand play made a new scoring combination: call koi-koi (play on) or shobu (take the points). */
    DECIDE_AFTER_HAND,

    /** Hand card done: turn up the top card of the deck. */
    DRAW_READY,

    /** The turned-up card matches two field cards: choose which one to take. */
    CHOOSE_DRAW_MATCH,

    /** The deck play made a new scoring combination: koi-koi or shobu. */
    DECIDE_AFTER_DRAW,

    ROUND_OVER
}

/** How a finished round ended. */
enum class KoiEnd {
    NONE,

    /** The player on turn called shobu and scored. */
    SHOBU,

    /** Both hands ran out after a koi-koi: the last caller keeps their combinations. */
    EXHAUSTED_KOI,

    /** Both hands ran out and nobody scored. */
    DRAW,

    /** A dealt hand held all four cards of a month (full scoring set only). */
    DEALT_FOUR_OF_A_MONTH,

    /** A dealt hand held four pairs of a month (full scoring set only). */
    DEALT_FOUR_PAIRS,

    /** Both players were dealt a special hand. */
    DEALT_BOTH
}

/**
 * One round (one "month") of Koi-Koi, as plain data. [deck] is private to the host: the top card is
 * the LAST entry. A view for one seat has the other seat's hand and the whole deck replaced by
 * negative placeholder ids ([redactedFor]), so a guest never receives a hidden card.
 *
 * [decisionBase] is each seat's yaku total at its last koi-koi/shobu decision: a new decision is
 * offered only when the total rises above it.
 */
@Serializable
data class KoiRound(
    val yakuSet: YakuSet,
    val parent: Int,
    val turn: Int,
    val phase: KoiPhase,
    val deck: List<Int>,
    val field: List<Int>,
    val hands: List<List<Int>>,
    val captured: List<List<Int>>,
    /** The card just turned up from the deck while it waits for a field choice; -1 otherwise. */
    val drawn: Int = -1,
    val koiCalls: List<Int> = listOf(0, 0),
    val lastKoi: Int = -1,
    val decisionBase: List<Int> = listOf(0, 0),
    val end: KoiEnd = KoiEnd.NONE,
    /** Seat that scored, or -1 for a drawn round. */
    val winner: Int = -1,
    val points: Int = 0
) {
    val over: Boolean get() = phase == KoiPhase.ROUND_OVER
    val other: Int get() = 1 - turn

    fun matchesOnField(card: Int): List<Int> = field.filter { HanafudaCards.monthOf(it) == HanafudaCards.monthOf(card) }

    fun yakuOf(seat: Int): List<YakuScore> = KoiYaku.evaluate(captured[seat], yakuSet)
    fun yakuTotal(seat: Int): Int = yakuOf(seat).sumOf { it.points }

    /** A copy for [seat]'s eyes only: the other hand and the deck become unique negative placeholders. */
    fun redactedFor(seat: Int): KoiRound = copy(
        hands = hands.mapIndexed { s, h -> if (s == seat) h else List(h.size) { i -> -(1 + s * 100 + i) } },
        deck = List(deck.size) { i -> -(1000 + i) }
    )

    /** True when any hidden card is still in the lists. */
    val isRedacted: Boolean get() = hands.any { h -> h.any { it < 0 } } || deck.any { it < 0 }
}

/** A concrete hand play: [handCard], and the field card it takes when two match (otherwise -1). */
data class KoiPlay(val handCard: Int, val fieldCard: Int = -1)

object KoiRules {
    const val HAND_SIZE = 8
    const val FIELD_SIZE = 8
    const val DOUBLE_AT = 7
    const val DEALT_SPECIAL_POINTS = 6

    // ---- dealing --------------------------------------------------------------------------------

    /**
     * Deals a round from the injected [random]. A field holding all four cards of a month is dealt
     * again. With the full scoring set, a dealt special hand ends the round at once.
     */
    fun deal(random: Random, parent: Int, set: YakuSet): KoiRound {
        require(parent in 0..1)
        var deck: List<Int>
        do {
            deck = (0 until HANAFUDA_CARDS).toList().shuffled(random)
        } while (deck.subList(HAND_SIZE * 2, HAND_SIZE * 2 + FIELD_SIZE).groupBy { HanafudaCards.monthOf(it) }.any { it.value.size == 4 })
        return dealFrom(deck, parent, set)
    }

    /** Deals from an explicit order: parent hand, other hand, field, then the deck (top = last). Test and replay hook. */
    fun dealFrom(order: List<Int>, parent: Int, set: YakuSet): KoiRound {
        require(order.size == HANAFUDA_CARDS && order.toSet().size == HANAFUDA_CARDS && order.all { HanafudaCards.isReal(it) })
        val parentHand = order.subList(0, HAND_SIZE).sortedForHand()
        val childHand = order.subList(HAND_SIZE, HAND_SIZE * 2).sortedForHand()
        val hands = if (parent == 0) listOf(parentHand, childHand) else listOf(childHand, parentHand)
        val round = KoiRound(
            yakuSet = set,
            parent = parent,
            turn = parent,
            phase = KoiPhase.PLAY_HAND,
            deck = order.subList(HAND_SIZE * 2 + FIELD_SIZE, HANAFUDA_CARDS).toList(),
            field = order.subList(HAND_SIZE * 2, HAND_SIZE * 2 + FIELD_SIZE).sortedForHand(),
            hands = hands,
            captured = listOf(emptyList(), emptyList())
        )
        return if (set == YakuSet.FULL) applyDealtSpecials(round) else round
    }

    private fun List<Int>.sortedForHand(): List<Int> = sorted()

    /** A dealt special hand: 4 of one month, or 4 pairs (each pair a month). */
    fun dealtSpecial(hand: List<Int>): KoiEnd {
        val byMonth = hand.groupBy { HanafudaCards.monthOf(it) }.mapValues { it.value.size }
        if (byMonth.values.any { it == 4 }) return KoiEnd.DEALT_FOUR_OF_A_MONTH
        if (byMonth.size == 4 && byMonth.values.all { it == 2 }) return KoiEnd.DEALT_FOUR_PAIRS
        return KoiEnd.NONE
    }

    private fun applyDealtSpecials(r: KoiRound): KoiRound {
        val specials = r.hands.map { dealtSpecial(it) }
        val holders = specials.indices.filter { specials[it] != KoiEnd.NONE }
        return when (holders.size) {
            0 -> r
            1 -> r.copy(phase = KoiPhase.ROUND_OVER, end = specials[holders[0]], winner = holders[0], points = DEALT_SPECIAL_POINTS)
            else -> r.copy(phase = KoiPhase.ROUND_OVER, end = KoiEnd.DEALT_BOTH, winner = -1, points = 0)
        }
    }

    // ---- queries --------------------------------------------------------------------------------

    /** Every legal play in [KoiPhase.PLAY_HAND]: a card with two field matches appears once per choice. */
    fun legalPlays(r: KoiRound): List<KoiPlay> {
        if (r.phase != KoiPhase.PLAY_HAND) return emptyList()
        val out = ArrayList<KoiPlay>()
        for (card in r.hands[r.turn]) {
            val matches = r.matchesOnField(card)
            if (matches.size == 2) matches.forEach { out += KoiPlay(card, it) } else out += KoiPlay(card)
        }
        return out
    }

    /** The field cards the turned-up card may take, in [KoiPhase.CHOOSE_DRAW_MATCH]. */
    fun drawChoices(r: KoiRound): List<Int> = if (r.phase == KoiPhase.CHOOSE_DRAW_MATCH) r.matchesOnField(r.drawn) else emptyList()

    /** What the field cards [card] would take from the field right now (for highlighting). */
    fun capturesOf(r: KoiRound, card: Int): List<Int> = r.matchesOnField(card)

    // ---- transitions ----------------------------------------------------------------------------

    /** Plays [play]. Null when it is not legal (wrong phase, card not in hand, missing/invalid field choice). */
    fun playHand(r: KoiRound, play: KoiPlay): KoiRound? {
        if (r.phase != KoiPhase.PLAY_HAND) return null
        val hand = r.hands[r.turn]
        if (play.handCard !in hand) return null
        val matches = r.matchesOnField(play.handCard)
        val takes: List<Int> = when (matches.size) {
            0 -> if (play.fieldCard == -1) emptyList() else return null
            1 -> if (play.fieldCard == -1 || play.fieldCard == matches[0]) matches else return null
            2 -> if (play.fieldCard in matches) listOf(play.fieldCard) else return null
            else -> matches // three on the field: the fourth in hand takes all of them
        }
        val newHands = r.hands.toMutableList().also { it[r.turn] = hand - play.handCard }
        var next = r.copy(hands = newHands)
        next = if (takes.isEmpty()) {
            next.copy(field = next.field + play.handCard)
        } else {
            next.copy(
                field = next.field - takes.toSet(),
                captured = next.captured.withAdded(r.turn, takes + play.handCard)
            )
        }
        return afterStep(next, afterDraw = false)
    }

    /** Turns up the deck's top card. */
    fun draw(r: KoiRound): KoiRound? {
        if (r.phase != KoiPhase.DRAW_READY || r.deck.isEmpty()) return null
        val card = r.deck.last()
        val base = r.copy(deck = r.deck.dropLast(1))
        val matches = base.matchesOnField(card)
        return when (matches.size) {
            0 -> afterStep(base.copy(field = base.field + card), afterDraw = true)
            2 -> base.copy(phase = KoiPhase.CHOOSE_DRAW_MATCH, drawn = card)
            else -> afterStep(
                base.copy(field = base.field - matches.toSet(), captured = base.captured.withAdded(r.turn, matches + card)),
                afterDraw = true
            )
        }
    }

    /** Picks which of two matching field cards the turned-up card takes. */
    fun chooseDrawMatch(r: KoiRound, fieldCard: Int): KoiRound? {
        if (r.phase != KoiPhase.CHOOSE_DRAW_MATCH || fieldCard !in drawChoices(r)) return null
        val next = r.copy(
            field = r.field - fieldCard,
            captured = r.captured.withAdded(r.turn, listOf(fieldCard, r.drawn)),
            drawn = -1
        )
        return afterStep(next, afterDraw = true)
    }

    /** Koi-koi (keep playing for more) or shobu (stop and score). */
    fun decide(r: KoiRound, koiKoi: Boolean): KoiRound? {
        val afterDraw = when (r.phase) {
            KoiPhase.DECIDE_AFTER_HAND -> false
            KoiPhase.DECIDE_AFTER_DRAW -> true
            else -> return null
        }
        if (!koiKoi) {
            val points = scoreFor(r, r.turn)
            return r.copy(phase = KoiPhase.ROUND_OVER, end = KoiEnd.SHOBU, winner = r.turn, points = points)
        }
        val calls = r.koiCalls.toMutableList().also { it[r.turn] += 1 }
        val base = r.decisionBase.toMutableList().also { it[r.turn] = r.yakuTotal(r.turn) }
        val called = r.copy(koiCalls = calls, lastKoi = r.turn, decisionBase = base)
        return if (afterDraw) endTurn(called) else called.copy(phase = KoiPhase.DRAW_READY)
    }

    /** What [seat] would score if the round ended now: the yaku total, doubled from seven, doubled again if the opponent called koi-koi. */
    fun scoreFor(r: KoiRound, seat: Int): Int {
        var pts = r.yakuTotal(seat)
        if (pts >= DOUBLE_AT) pts *= 2
        if (r.koiCalls[1 - seat] > 0) pts *= 2
        return pts
    }

    private fun List<List<Int>>.withAdded(seat: Int, cards: List<Int>): List<List<Int>> =
        mapIndexed { s, list -> if (s == seat) list + cards else list }

    /** After a hand or deck play: offer koi-koi/shobu if the total rose; otherwise continue. */
    private fun afterStep(r: KoiRound, afterDraw: Boolean): KoiRound {
        val risen = r.yakuTotal(r.turn) > r.decisionBase[r.turn]
        if (risen) return r.copy(phase = if (afterDraw) KoiPhase.DECIDE_AFTER_DRAW else KoiPhase.DECIDE_AFTER_HAND, drawn = -1)
        return if (afterDraw) endTurn(r.copy(drawn = -1)) else r.copy(phase = KoiPhase.DRAW_READY)
    }

    private fun endTurn(r: KoiRound): KoiRound {
        if (r.hands.all { it.isEmpty() }) return exhausted(r)
        return r.copy(turn = 1 - r.turn, phase = KoiPhase.PLAY_HAND)
    }

    private fun exhausted(r: KoiRound): KoiRound {
        val caller = r.lastKoi
        if (caller < 0) return r.copy(phase = KoiPhase.ROUND_OVER, end = KoiEnd.DRAW, winner = -1, points = 0)
        return r.copy(
            phase = KoiPhase.ROUND_OVER, end = KoiEnd.EXHAUSTED_KOI, winner = caller,
            points = scoreFor(r, caller), turn = caller
        )
    }

    /** Structural validity of a FULL (unredacted) round: every card exactly once, sizes and phase plausible. */
    fun isConsistent(r: KoiRound): Boolean {
        if (r.hands.size != 2 || r.captured.size != 2 || r.koiCalls.size != 2 || r.decisionBase.size != 2) return false
        if (r.turn !in 0..1 || r.parent !in 0..1) return false
        val all = r.deck + r.field + r.hands.flatten() + r.captured.flatten() + (if (r.drawn >= 0) listOf(r.drawn) else emptyList())
        if (all.size != HANAFUDA_CARDS || all.toSet().size != HANAFUDA_CARDS || !all.all { HanafudaCards.isReal(it) }) return false
        if (r.koiCalls.any { it < 0 } || r.decisionBase.any { it < 0 }) return false
        if (r.lastKoi !in -1..1 || r.winner !in -1..1 || r.points < 0) return false
        if (r.phase == KoiPhase.CHOOSE_DRAW_MATCH) {
            if (r.drawn < 0 || r.matchesOnField(r.drawn).size != 2) return false
        } else if (r.drawn != -1) return false
        if (r.phase == KoiPhase.ROUND_OVER) return r.end != KoiEnd.NONE
        return r.end == KoiEnd.NONE
    }
}
