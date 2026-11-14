package com.gamesuite.games.partypack

/** The moves of Rock-Paper-Showdown. The first three are the classic game; all five are Lizard-Spock. */
enum class RpsMove { ROCK, PAPER, SCISSORS, LIZARD, SPOCK }

enum class RpsVariant(val id: String, val moves: List<RpsMove>) {
    CLASSIC("classic", listOf(RpsMove.ROCK, RpsMove.PAPER, RpsMove.SCISSORS)),
    LIZARD_SPOCK("lizard", RpsMove.entries.toList());

    companion object {
        fun parse(id: String?): RpsVariant = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

/** Best of 3 / 5 / 7 (first to 2 / 3 / 4 round wins) or Elimination (a round's losers drop out; the last player standing wins). */
enum class RpsFormat(val id: String, val winsNeeded: Int, val elimination: Boolean) {
    BEST_OF_3("bo3", 2, false),
    BEST_OF_5("bo5", 3, false),
    BEST_OF_7("bo7", 4, false),
    ELIMINATION("elim", 0, true);

    companion object {
        fun parse(id: String?): RpsFormat = entries.firstOrNull { it.id == id } ?: BEST_OF_3
    }
}

/** What happened in one round. [dominant] is the move that beat every other move on the table, null on a tie. */
data class RpsOutcome(
    val picks: List<RpsMove?>,
    val dominant: RpsMove?,
    val winners: List<Int>,
    val losers: List<Int>,
    /** A move that lost to [dominant] (for the "Rock crushes Scissors" line), null on a tie. */
    val beaten: RpsMove?
) {
    val tie: Boolean get() = dominant == null
}

object RpsRules {
    /** What each move beats. Classic: rock > scissors > paper > rock. Lizard-Spock adds lizard and Spock. */
    private val beatsTable: Map<RpsMove, Set<RpsMove>> = mapOf(
        RpsMove.ROCK to setOf(RpsMove.SCISSORS, RpsMove.LIZARD),
        RpsMove.PAPER to setOf(RpsMove.ROCK, RpsMove.SPOCK),
        RpsMove.SCISSORS to setOf(RpsMove.PAPER, RpsMove.LIZARD),
        RpsMove.LIZARD to setOf(RpsMove.PAPER, RpsMove.SPOCK),
        RpsMove.SPOCK to setOf(RpsMove.SCISSORS, RpsMove.ROCK)
    )

    fun beats(variant: RpsVariant, a: RpsMove, b: RpsMove): Boolean =
        a in variant.moves && b in variant.moves && b in beatsTable.getValue(a)

    /** The moves of [variant] that beat [m]. */
    fun beatenBy(variant: RpsVariant, m: RpsMove): List<RpsMove> = variant.moves.filter { beats(variant, it, m) }

    /** The move among [picks] that beats every other distinct move there, or null (a tie: one move only, or no move on top). */
    fun dominantMove(variant: RpsVariant, picks: Collection<RpsMove>): RpsMove? {
        val distinct = picks.toSet()
        if (distinct.size < 2) return null
        return distinct.firstOrNull { m -> (distinct - m).all { beats(variant, m, it) } }
    }

    /** Resolves a round. [picks] has one entry per seat; null means the seat is out of play. */
    fun resolve(variant: RpsVariant, picks: List<RpsMove?>): RpsOutcome {
        val present = picks.filterNotNull()
        val dom = dominantMove(variant, present)
        if (dom == null) return RpsOutcome(picks, null, emptyList(), emptyList(), null)
        val winners = picks.indices.filter { picks[it] == dom }
        val losers = picks.indices.filter { picks[it] != null && picks[it] != dom }
        val beaten = present.first { it != dom }
        return RpsOutcome(picks, dom, winners, losers, beaten)
    }

    /** Result of seat [seat] in [o] as +1 win, -1 loss, 0 tie or out of play. */
    fun resultFor(o: RpsOutcome, seat: Int): Int = when (seat) {
        in o.winners -> 1
        in o.losers -> -1
        else -> 0
    }
}
