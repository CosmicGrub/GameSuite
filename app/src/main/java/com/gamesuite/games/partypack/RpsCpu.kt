package com.gamesuite.games.partypack

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/** A finished, REVEALED round: what every seat played (null = out of play) and how each seat fared (+1, 0, -1). */
data class RpsRoundRecord(val picks: List<RpsMove?>, val results: List<Int>)

/**
 * What a computer seat is allowed to know when it chooses: the rules, who is still playing and (through
 * [RpsBrain.observe]) rounds that are already over. It deliberately has NO field for anybody's current pick:
 * the computer cannot peek, by construction of this type.
 */
class RpsBrainContext(val variant: RpsVariant, val seat: Int, val opponents: List<Int>)

interface RpsBrain {
    /** Called once after every revealed round. */
    fun observe(record: RpsRoundRecord)

    fun choose(ctx: RpsBrainContext): RpsMove
}

/**
 * A bounded-memory model of one opponent's habits. It blends four small models over the last [memory]
 * rounds (recency-weighted): overall frequency, what followed their previous move (order 1), what followed
 * their previous two moves (order 2) and how they react to their own last result (win-stay, lose-shift).
 * Memory never grows past [memory] entries, however long the session.
 */
class RpsPredictor(private val moves: List<RpsMove>, val memory: Int = 60, private val useO1: Boolean = true, private val useO2: Boolean = true, private val useReaction: Boolean = true) {
    private val history = ArrayDeque<Pair<RpsMove, Int>>() // (move, result for them)

    val size: Int get() = history.size

    fun observe(move: RpsMove, result: Int) {
        history.addLast(move to result)
        while (history.size > memory) history.removeFirst()
    }

    private fun idx(m: RpsMove) = moves.indexOf(m)

    /** Probability of each of [moves] being played next. Uniform with no history. */
    fun distribution(): DoubleArray {
        val k = moves.size
        val uniform = DoubleArray(k) { 1.0 / k }
        if (history.isEmpty()) return uniform
        val list = history.toList()
        val freq = DoubleArray(k)
        var w = 1.0
        for (i in list.indices.reversed()) { freq[idx(list[i].first)] += w; w *= 0.93 }
        val f = normalize(freq)
        val parts = mutableListOf<Pair<DoubleArray, Double>>() // distribution, weight
        parts += f to 1.0
        fun follow(ctxLen: Int, key: (Int) -> Any, now: Any, weightScale: Double) {
            val counts = DoubleArray(k)
            var n = 0.0
            var wt = 1.0
            for (i in list.indices.reversed()) {
                if (i - ctxLen < 0) break
                if (key(i) == now) { counts[idx(list[i].first)] += wt; n += wt }
                wt *= 0.95
            }
            if (n > 0.0) parts += normalize(counts) to weightScale * n / (n + 2.0)
        }
        val last = list.last()
        if (useO1 && list.size >= 2) follow(1, { i -> list[i - 1].first }, last.first, 2.0)
        if (useO2 && list.size >= 3) follow(2, { i -> list[i - 2].first to list[i - 1].first }, list[list.size - 2].first to last.first, 3.0)
        if (useReaction && list.size >= 2) follow(1, { i -> list[i - 1].first to list[i - 1].second }, last.first to last.second, 2.5)
        val out = DoubleArray(k)
        val total = parts.sumOf { it.second }
        for ((d, wt) in parts) for (i in 0 until k) out[i] += d[i] * wt / total
        // keep a little mass everywhere: nobody is perfectly predictable
        for (i in 0 until k) out[i] = out[i] * 0.92 + 0.08 / k
        return normalize(out)
    }

    private fun normalize(a: DoubleArray): DoubleArray {
        val s = a.sum()
        if (s <= 0.0) return DoubleArray(a.size) { 1.0 / a.size }
        return DoubleArray(a.size) { a[it] / s }
    }
}

/**
 * The computer of Rock-Paper-Showdown. All levels pick from the PAST only (see [RpsBrainContext]):
 *  - EASY is near random: it follows a plain frequency count only one time in seven.
 *  - MEDIUM counts frequencies and what follows a move, and plays randomly three rounds in ten.
 *  - HARD reads the whole pattern history of every opponent (frequency, order 1, order 2, win-stay/lose-shift)
 *    over a bounded window, plays the move with the best expected result, and still strays one round in
 *    twelve so it cannot be exploited by a bot of its own.
 * [random] is injected so tests replay a session exactly.
 */
class RpsCpuBrain(private val level: CpuDifficulty, private val random: Random, private val variant: RpsVariant = RpsVariant.CLASSIC) : RpsBrain {
    private val predictors = HashMap<Int, RpsPredictor>()

    private val epsilon = when (level) { CpuDifficulty.EASY -> 0.86; CpuDifficulty.MEDIUM -> 0.3; CpuDifficulty.HARD -> 0.08 }

    private fun predictorFor(seat: Int, moves: List<RpsMove>): RpsPredictor = predictors.getOrPut(seat) {
        when (level) {
            CpuDifficulty.EASY -> RpsPredictor(moves, memory = 12, useO1 = false, useO2 = false, useReaction = false)
            CpuDifficulty.MEDIUM -> RpsPredictor(moves, memory = 30, useO1 = true, useO2 = false, useReaction = false)
            CpuDifficulty.HARD -> RpsPredictor(moves, memory = 60)
        }
    }

    override fun observe(record: RpsRoundRecord) {
        val moves = variant.moves
        for (seat in record.picks.indices) {
            val m = record.picks[seat] ?: continue
            predictorFor(seat, moves).observe(m, record.results.getOrElse(seat) { 0 })
        }
    }

    override fun choose(ctx: RpsBrainContext): RpsMove {
        val moves = ctx.variant.moves
        if (random.nextDouble() < epsilon || ctx.opponents.isEmpty()) return moves[random.nextInt(moves.size)]
        val dists = ctx.opponents.map { predictorFor(it, moves).distribution() }
        var best = moves[0]
        var bestScore = Double.NEGATIVE_INFINITY
        for ((mi, m) in moves.withIndex()) {
            var score = 0.0
            for (d in dists) for ((oi, o) in moves.withIndex()) {
                score += d[oi] * (if (RpsRules.beats(ctx.variant, m, o)) 1.0 else if (RpsRules.beats(ctx.variant, o, m)) -1.0 else 0.0)
            }
            score += random.nextDouble() * 1e-6 // random tie-break between equally good counters
            if (score > bestScore) { bestScore = score; best = moves[mi] }
        }
        return best
    }
}
