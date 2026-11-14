package com.gamesuite.games.findit

import kotlin.math.floor

/**
 * One-switch scanning, pure and clocked by [advance]. A highlight visits the choices one at a time and a press picks
 * the highlighted one. A card is one row (visit every picture, then wrap); a Group Hunt scene is two levels: rows
 * first, then the pictures of the chosen row (row-column scanning, 2 presses per pick).
 * Nobody is penalised for scan speed: the engine has no wrong-tap wait in one-switch games.
 */
class FindItScanner(rows: List<List<Int>>, private val twoLevel: Boolean, val stepMs: Long = DEFAULT_STEP_MS) {
    private val rows: List<List<Int>> = rows.filter { it.isNotEmpty() }
    private var acc = 0L

    /** 0 = choosing a row (or the only row of a card), 1 = choosing within [row]. */
    var level = 0
        private set
    var row = 0
        private set
    var col = 0
        private set

    /** Everything that can be picked, in scan order (stable for the same input). */
    val order: List<Int> get() = rows.flatten()

    /** The id the highlight is on now (the first of the row at level 0 of a two-level scan), or -1 when there is nothing to scan. */
    fun highlighted(): Int = when {
        rows.isEmpty() -> -1
        twoLevel && level == 0 -> rows[row][0]
        else -> rows[row][col]
    }

    /** The ids of the row being offered (all of them at level 0 of a two-level scan), for drawing the row band. */
    fun highlightedRow(): List<Int> = if (rows.isEmpty()) emptyList() else rows[row]

    fun advance(dtMs: Long) {
        if (rows.isEmpty() || dtMs <= 0) return
        acc += dtMs
        val steps = floor(acc.toDouble() / stepMs).toInt()
        if (steps <= 0) return
        acc -= steps * stepMs
        repeat(steps) { step() }
    }

    private fun step() {
        if (twoLevel && level == 0) row = (row + 1) % rows.size
        else col = (col + 1) % rows[row].size
    }

    /** A switch press. Returns the picked id, or null when the press only chose a row. */
    fun press(): Int? {
        if (rows.isEmpty()) return null
        if (twoLevel && level == 0) {
            level = 1; col = 0; acc = 0
            return null
        }
        val id = rows[row][col]
        level = 0; row = 0; col = 0; acc = 0
        return id
    }

    companion object {
        const val DEFAULT_STEP_MS = 1_200L
        const val MIN_STEP_MS = 800L
        const val MAX_STEP_MS = 2_500L

        /** Scan order of a card: the centre slot first (STANDARD), then the ring clockwise from the top. Ids are slot indexes. */
        fun cardRows(layout: List<FindItCardSlot>): List<List<Int>> {
            val centre = layout.indices.filter { Math.hypot(layout[it].cx.toDouble(), layout[it].cy.toDouble()) < 0.3 }
            val ring = layout.indices.filter { it !in centre }.sortedBy { (FindItTableLayout.angleOf(layout[it]) + 90f) % 360f }
            return listOf(centre + ring)
        }

        /** Rows of a hunt scene: items grouped into bands of [bandHeight], each band left to right. Ids are item indexes. */
        fun sceneRows(scene: FindItScene, bandHeight: Float = 0.2f): List<List<Int>> =
            scene.items.indices.groupBy { floor(scene.items[it].y / bandHeight).toInt() }
                .toSortedMap().values.map { band -> band.sortedBy { scene.items[it].x } }

        fun forCard(layout: List<FindItCardSlot>, stepMs: Long = DEFAULT_STEP_MS) = FindItScanner(cardRows(layout), false, stepMs.coerceIn(MIN_STEP_MS, MAX_STEP_MS))

        fun forScene(scene: FindItScene, stepMs: Long = DEFAULT_STEP_MS) = FindItScanner(sceneRows(scene), true, stepMs.coerceIn(MIN_STEP_MS, MAX_STEP_MS))
    }
}
