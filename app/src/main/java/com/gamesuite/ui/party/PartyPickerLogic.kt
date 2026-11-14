package com.gamesuite.ui.party

import kotlin.random.Random

/** Pure rules for the Who Pays / Picker tool. */
object PartyPicker {
    const val HISTORY_LIMIT = 8

    /** A uniformly random index into [names], or null when empty. */
    fun pickIndex(names: List<String>, random: Random = Random): Int? =
        if (names.isEmpty()) null else random.nextInt(names.size)

    /**
     * The flash sequence for the suspense animation: [steps] indices, in range, that END on
     * [winner] and do not flash the winner just before the final reveal.
     */
    fun flashSequence(size: Int, winner: Int, steps: Int, random: Random = Random): List<Int> {
        if (size <= 0 || winner !in 0 until size) return emptyList()
        val n = steps.coerceAtLeast(1)
        if (size == 1) return List(n) { 0 }
        val out = ArrayList<Int>(n)
        var prev = -1
        for (i in 0 until n - 1) {
            var next = random.nextInt(size)
            if (next == prev) next = (next + 1) % size
            out.add(next)
            prev = next
        }
        // The last flash is the winner; make sure it differs from the one before it.
        if (prev == winner) {
            val idx = out.lastIndex
            if (idx >= 0) out[idx] = (winner + 1) % size
        }
        out.add(winner)
        return out
    }

    /** Delay in ms before flash [step] of [total]: starts quick, slows toward the end. */
    fun delayMs(step: Int, total: Int, baseMs: Long = 60L, extraMs: Long = 260L): Long {
        if (total <= 1) return baseMs
        val f = (step.toFloat() / (total - 1)).coerceIn(0f, 1f)
        return baseMs + (extraMs * f * f).toLong()
    }

    /** Newest-first history, capped at [HISTORY_LIMIT]. */
    fun pushHistory(history: List<String>, picked: String): List<String> =
        (listOf(picked) + history).take(HISTORY_LIMIT)
}
