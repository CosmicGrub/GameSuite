package com.gamesuite.games.liferoad

/** The three independent random streams (section 3.3). A hint, a re-render or a CPU think never moves SPIN. */
enum class RngStream(val salt: Long) {
    SPIN(0x1L), DECK(0x2L), BOT(0x3L)
}

/**
 * Counter-based random numbers: draw number `n` of a stream is a pure function of (seed, stream, n), using the
 * SplitMix64 finalizer. Nothing else is stored, so a save needs only the seed and three cursors, and a
 * replay is exact on every platform (kotlin.random.Random's algorithm is not a frozen contract; this is).
 */
object LifeRoadRng {
    private const val GOLDEN: Long = -0x61C8864680B583EBL // 0x9E3779B97F4A7C15

    private fun mix64(x: Long): Long {
        var z = x
        z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** The raw 64 bits of draw number [cursor] (0-based) of [stream]. */
    fun raw(seed: Long, stream: RngStream, cursor: Long): Long =
        mix64(seed + (stream.salt shl 56) + (cursor + 1L) * GOLDEN)

    /** A number in 0 until [bound] (bound 1..1000; the modulo bias at that size is below 1e-15). */
    fun below(seed: Long, stream: RngStream, cursor: Long, bound: Int): Int {
        require(bound in 1..1000) { "bound must be 1..1000" }
        return ((raw(seed, stream, cursor) ushr 1) % bound.toLong()).toInt()
    }

    /** A spinner result, 1..10. */
    fun spin(seed: Long, cursor: Long): Int = below(seed, RngStream.SPIN, cursor, LifeRoadRules.SPIN_MAX) + 1
}
