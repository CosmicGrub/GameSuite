package com.gamesuite.games.hanafuda

import kotlin.math.max
import kotlin.math.min

/** A rectangle in dp-like units (no Compose types, so the geometry is unit tested on the JVM). */
data class HfRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    fun contains(px: Float, py: Float): Boolean = px >= x && px <= right && py >= y && py <= bottom
}

/** Where one card rests and how big it is drawn. [zone] says which area it belongs to. */
data class HfPlacement(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val faceUp: Boolean,
    val z: Int,
    val zone: HfZone
)

enum class HfZone { OPP_HAND, OPP_CAPTURED, FIELD, DECK, DRAWN, MY_CAPTURED, MY_HAND }

/**
 * The table geometry. Every card (real or a redacted placeholder) is positioned by [HanafudaLayout.placements],
 * so any change of the table, local or sent by the host, animates by gliding each card to its new slot.
 */
data class HfMetrics(
    val width: Float,
    val height: Float,
    val cardW: Float,
    val cardH: Float,
    val landscape: Boolean,
    val oppHand: HfRect,
    val oppCaptured: HfRect,
    val field: HfRect,
    val myCaptured: HfRect,
    val myHand: HfRect,
    val fieldCols: Int,
    val fieldCellW: Float,
    val fieldCellH: Float,
    val deckX: Float,
    val deckY: Float,
    val drawnX: Float,
    val drawnY: Float
)

object HanafudaLayout {
    const val ASPECT = 1.58f
    const val OPP_HAND_SCALE = 0.72f
    const val FIELD_SCALE = 0.86f
    const val CAPTURED_SCALE = 0.5f
    private const val GAP = 0.07f

    fun metrics(availW: Float, availH: Float): HfMetrics {
        val landscape = availW > availH * 1.15f
        val units = if (landscape) 4.7f else 5.9f
        val byHeight = availH / units / ASPECT
        val byWidth = if (landscape) availW / 9.5f else availW / 5.7f
        val cardW = min(byHeight, byWidth).coerceAtLeast(18f)
        val cardH = cardW * ASPECT
        val gap = cardH * GAP
        val fieldCellW = cardW * FIELD_SCALE
        val fieldCellH = cardH * FIELD_SCALE
        return if (!landscape) {
            val oppH = cardH * OPP_HAND_SCALE
            val capH = cardH * CAPTURED_SCALE
            val fieldH = fieldCellH * 3 + gap * 2
            var y = 0f
            val oppHand = HfRect(0f, y, availW, oppH).also { y += oppH + gap }
            val oppCap = HfRect(0f, y, availW, capH).also { y += capH + gap }
            val field = HfRect(0f, y, availW, fieldH).also { y += fieldH + gap }
            val myCap = HfRect(0f, y, availW, capH).also { y += capH + gap }
            val myHand = HfRect(0f, y, availW, cardH).also { y += cardH }
            finish(availW, y, cardW, cardH, false, oppHand, oppCap, field, myCap, myHand, fieldCellW, fieldCellH, gap)
        } else {
            val oppH = cardH * OPP_HAND_SCALE
            val capH = cardH * CAPTURED_SCALE
            val fieldH = fieldCellH * 3 + gap * 2
            val leftW = availW * 0.62f
            var y = 0f
            val oppHand = HfRect(0f, y, leftW, oppH)
            val oppCap = HfRect(leftW + gap, y, availW - leftW - gap, capH)
            y += oppH + gap
            val field = HfRect(0f, y, availW, fieldH).also { y += fieldH + gap }
            val myHand = HfRect(0f, y, leftW, cardH)
            val myCap = HfRect(leftW + gap, y, availW - leftW - gap, capH)
            y += cardH
            finish(availW, y, cardW, cardH, true, oppHand, oppCap, field, myCap, myHand, fieldCellW, fieldCellH, gap)
        }
    }

    private fun finish(
        w: Float, h: Float, cardW: Float, cardH: Float, landscape: Boolean,
        oppHand: HfRect, oppCap: HfRect, field: HfRect, myCap: HfRect, myHand: HfRect,
        cellW: Float, cellH: Float, gap: Float
    ): HfMetrics {
        val cols = max(5, ((field.w - (cellW + gap)) / (cellW + gap)).toInt())
        return HfMetrics(
            width = w, height = h, cardW = cardW, cardH = cardH, landscape = landscape,
            oppHand = oppHand, oppCaptured = oppCap, field = field, myCaptured = myCap, myHand = myHand,
            fieldCols = cols, fieldCellW = cellW, fieldCellH = cellH,
            deckX = field.x, deckY = field.y,
            drawnX = field.x, drawnY = field.y + cellH + gap
        )
    }

    /** Top-left of field slot [slot] (row-major, to the right of the deck column). Rows past the third overlap to stay on screen. */
    fun fieldSlot(slot: Int, m: HfMetrics): Pair<Float, Float> {
        val gap = m.cardH * GAP
        val col = slot % m.fieldCols
        val row = slot / m.fieldCols
        val x0 = m.field.x + m.fieldCellW + gap
        val colStep = if (m.fieldCols <= 1) 0f else min(m.fieldCellW + gap, (m.field.right - x0 - m.fieldCellW) / (m.fieldCols - 1))
        val rowStep = m.fieldCellH + gap
        val y = if (row <= 2) m.field.y + row * rowStep else m.field.y + 2 * rowStep + (row - 2) * m.fieldCellH * 0.35f
        return (x0 + col * colStep) to y
    }

    private fun kindOrder(card: Int): Int = HanafudaCards.card(card).kind.ordinal

    /**
     * Every card's resting place for [view] (already redacted for the viewer) from [viewer]'s side:
     * the viewer's hand and captures at the bottom, the other seat's at the top. Negative ids are
     * redacted placeholders and are always drawn face down.
     */
    fun placements(view: KoiRound, viewer: Int, m: HfMetrics): Map<Int, HfPlacement> {
        val out = LinkedHashMap<Int, HfPlacement>()
        val other = 1 - viewer
        fun spread(cards: List<Int>, rect: HfRect, w: Float, h: Float, faceUp: Boolean, zone: HfZone) {
            val n = cards.size
            if (n == 0) return
            val step = if (n == 1) 0f else min(w * 1.06f, (rect.w - w) / (n - 1))
            val total = w + step * (n - 1)
            val x0 = rect.x + (rect.w - total) / 2f
            cards.forEachIndexed { i, id ->
                out[id] = HfPlacement(x0 + i * step, rect.y + (rect.h - h) / 2f, w, h, faceUp && id >= 0, 10 + i, zone)
            }
        }
        spread(view.hands[other], m.oppHand, m.cardW * OPP_HAND_SCALE, m.cardH * OPP_HAND_SCALE, false, HfZone.OPP_HAND)
        spread(view.hands[viewer], m.myHand, m.cardW, m.cardH, true, HfZone.MY_HAND)

        fun captured(cards: List<Int>, rect: HfRect, zone: HfZone) {
            if (cards.isEmpty()) return
            val w = m.cardW * CAPTURED_SCALE
            val h = m.cardH * CAPTURED_SCALE
            val sorted = cards.sortedWith(compareBy({ kindOrder(it) }, { it }))
            val groups = sorted.groupBy { kindOrder(it) }.values
            val groupGap = w * 0.4f
            val boundaries = groups.size - 1
            val inGroupGaps = sorted.size - groups.size
            // Cards in a group step by [step]; between groups the next card starts a full card plus a gap later.
            val room = rect.w - w - boundaries * (w + groupGap)
            val step = (if (inGroupGaps > 0) min(w * 0.62f, room / inGroupGaps) else w * 0.62f).coerceAtLeast(w * 0.1f)
            var x = rect.x
            var z = 10
            for (g in groups) {
                for (id in g) {
                    out[id] = HfPlacement(x, rect.y, w, h, true, z++, zone)
                    x += step
                }
                x += w - step + groupGap
            }
        }
        captured(view.captured[other], m.oppCaptured, HfZone.OPP_CAPTURED)
        captured(view.captured[viewer], m.myCaptured, HfZone.MY_CAPTURED)

        view.field.forEachIndexed { i, id ->
            val (x, y) = fieldSlot(i, m)
            out[id] = HfPlacement(x, y, m.fieldCellW, m.fieldCellH, true, 10 + i, HfZone.FIELD)
        }

        view.deck.forEachIndexed { i, id ->
            val depth = (view.deck.size - 1 - i).coerceAtMost(3)
            out[id] = HfPlacement(m.deckX + 0.6f * (3 - depth), m.deckY + 0.6f * (3 - depth), m.fieldCellW, m.fieldCellH, false, i, HfZone.DECK)
        }
        if (view.drawn >= 0) out[view.drawn] = HfPlacement(m.drawnX, m.drawnY, m.fieldCellW, m.fieldCellH, true, 200, HfZone.DRAWN)
        return out
    }

    /** The index in [view]'s field of the card under (x, y), or -1. */
    fun fieldIndexAt(x: Float, y: Float, view: KoiRound, m: HfMetrics): Int {
        for (i in view.field.indices) {
            val (fx, fy) = fieldSlot(i, m)
            if (HfRect(fx, fy, m.fieldCellW, m.fieldCellH).contains(x, y)) return i
        }
        return -1
    }
}
