package com.gamesuite.games.findit

/**
 * SplitMix64: the only random source the Find It engine may use (never `Random.Default`, never the clock), so a
 * seed plus a tap log replays a game exactly. [calls] counts draws so a snapshot can record the stream position.
 * Modulo bias of [nextInt] is below 1e-9 for bounds under 1000, which is irrelevant for shuffles of 57 cards.
 */
class FindItRng(seed: Long) {
    private var state: Long = seed

    /** How many 64-bit values have been drawn. */
    var calls: Long = 0
        private set

    fun nextLong(): Long {
        calls++
        state += GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** Uniform-ish value in 0 until [bound]. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return ((nextLong() ushr 1) % bound.toLong()).toInt()
    }

    /** Uniform-ish value in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / 9007199254740992.0

    /** In-place Fisher-Yates shuffle, drawing from the top index down (documented so ports agree). */
    fun <T> shuffle(list: MutableList<T>) {
        for (i in list.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
        }
    }

    private companion object {
        const val GAMMA: Long = -7046029254386353131L // 0x9E3779B97F4A7C15
        const val MIX1: Long = -4658895280553007687L // 0xBF58476D1CE4E5B9
        const val MIX2: Long = -7723592293110705685L // 0x94D049BB133111EB
    }
}
