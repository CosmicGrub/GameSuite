package com.gamesuite.ui

import com.gamesuite.games.botbastion.BbArena
import com.gamesuite.games.botbastion.Pt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** How the Bot Bastion play screen is arranged. */
internal enum class BbLayoutMode {
    /** Arena on top, build panel below (phones held upright). */
    PHONE_PORTRAIT,

    /** Arena on the left, 260 dp panel on the right. */
    WIDE,

    /** Co-op: a build bar on each player's side, the arena and shared status in the middle. */
    TABLETOP
}

/** A node's touch target in dp, centred on the node and kept inside the arena. */
internal data class BbHitBox(val left: Float, val top: Float, val size: Float)

/**
 * The screen's layout rules as plain numbers, so they can be unit tested without Compose (the 360 dp phone check,
 * the tabletop split, the node touch boxes). All sizes are dp.
 */
internal object BbLayout {
    const val PAD = 8f
    const val RATIO = 16f / 9f
    const val SIDE_PANEL = 260f
    const val MIN_TARGET = 48f
    /** Height reserved for the shared status strip (wave, Core, pause, speed) in the tabletop layout. */
    const val TABLETOP_STRIP = 96f
    /** Smallest height a tabletop player bar may be squeezed to. */
    const val MIN_BAR = 112f
    /** The Scrap a gift button sends (the engine's floor is `BbRules.MIN_GIFT`). */
    const val GIFT_AMOUNT = 25
    const val TABLET_MIN_DP = 600f

    fun mode(widthDp: Float, heightDp: Float, players: Int, tabletopPosture: Boolean): BbLayoutMode {
        val tablet = min(widthDp, heightDp) >= TABLET_MIN_DP
        return when {
            tabletopPosture -> BbLayoutMode.TABLETOP
            tablet && players >= 2 -> BbLayoutMode.TABLETOP
            widthDp > heightDp -> BbLayoutMode.WIDE
            else -> BbLayoutMode.PHONE_PORTRAIT
        }
    }

    /** Arena width in the stacked phone layout. */
    fun portraitArenaWidth(widthDp: Float): Float = max(0f, widthDp - 2 * PAD)

    /** Arena width beside the 260 dp panel (landscape). [boxW] and [boxH] are the space left for the arena. */
    fun wideArenaWidth(boxW: Float, boxH: Float): Float = min(boxW, boxH * RATIO)

    /** Arena width in the tabletop layout: what is left after the status strip and two minimum bars. */
    fun tabletopArenaWidth(widthDp: Float, heightDp: Float, bars: Int): Float {
        val room = heightDp - 2 * PAD - TABLETOP_STRIP - bars * MIN_BAR
        return max(0f, min(widthDp - 2 * PAD, room * RATIO))
    }

    /** Players on the bottom bar (seats 1 and 3, 0-based 0 and 2) and the far bar (0-based 1 and 3). */
    fun nearPlayers(players: Int): List<Int> = listOf(0, 2).filter { it < players }
    fun farPlayers(players: Int): List<Int> = listOf(1, 3).filter { it < players }

    /** The 0-based player the first tap on a bar chip selects: the bar's first seat. */
    fun firstOf(side: List<Int>): Int = side.firstOrNull() ?: 0

    /**
     * Touch boxes for [nodes] at [arenaWidthDp]: 48 dp where there is room, otherwise just under the gap to the nearest
     * neighbour so two boxes never overlap, always clamped inside the arena so no part is clipped by the Canvas edge.
     */
    fun nodeBoxes(nodes: List<Pt>, arenaWidthDp: Float): List<BbHitBox> {
        val scale = arenaWidthDp / BbArena.W.toFloat()
        val h = arenaWidthDp / RATIO
        return nodes.mapIndexed { i, n ->
            var gap = Double.MAX_VALUE
            for ((j, o) in nodes.withIndex()) if (j != i) gap = min(gap, hypot(n.x - o.x, n.y - o.y))
            val size = min(MIN_TARGET, (gap * scale * 0.96).toFloat())
            val cx = (n.x * scale).toFloat()
            val cy = (n.y * scale).toFloat()
            BbHitBox((cx - size / 2).coerceIn(0f, max(0f, arenaWidthDp - size)), (cy - size / 2).coerceIn(0f, max(0f, h - size)), size)
        }
    }
}
