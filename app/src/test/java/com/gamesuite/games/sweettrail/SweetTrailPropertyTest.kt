package com.gamesuite.games.sweettrail

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Properties over many seeded boards, plus the measured numbers the design doc quotes
 * (docs/design/boardgames/sweet-trail.md, part 3). Headless: straight through the engine and the bot rule.
 */
class SweetTrailPropertyTest {
    private fun rulesFor(seed: Long) = SweetTrailRules(TrailLength.values()[(seed % 3).toInt()], sticky = seed % 7 != 0L)

    private fun boardFor(seed: Long): SweetTrailState {
        val n = 2 + (seed % 3).toInt()
        val wish = (0 until n).filter { (seed / 3 + it) % 3 == 0L }.toSet()
        return stBoard(n, rulesFor(seed / 3), seed = seed, wish = wish, starter = (seed % n).toInt())
    }

    @Test fun randomPlayAlwaysTerminatesOnTenThousandSeeds() {
        for (seed in 0L until 10_000L) {
            val end = stPlayOut(boardFor(seed), level = { CpuDifficulty.values()[(it + seed).toInt() % 3] })
            assertTrue("seed $seed", end.over)
            assertTrue("seed $seed took ${end.turnsTaken} turns", end.turnsTaken in 1..400)
        }
    }

    @Test fun positionsNeverDecrease() {
        for (seed in 0L until 3_000L) {
            stPlayOut(boardFor(seed)) { before, after ->
                for (i in before.positions.indices) assertTrue("seed $seed seat $i", after.positions[i] >= before.positions[i])
            }
        }
    }

    @Test fun cardsAreConservedEveryTurn() {
        for (seed in 0L until 3_000L) {
            stPlayOut(boardFor(seed)) { _, after ->
                val all = after.pile + after.discard + listOfNotNull(after.card)
                assertEquals("seed $seed", 66, all.size)
                assertEquals("seed $seed", 66, all.toSet().size)
            }
        }
    }

    @Test fun exactlyOneWinnerPerBoard() {
        for (seed in 0L until 5_000L) {
            val end = stPlayOut(boardFor(seed))
            assertEquals("seed $seed", 1, end.positions.count { it == SweetTrailBoard.FINISH })
            assertTrue(end.winnerPlayerId != null)
            assertEquals(end.players[end.positions.indexOf(SweetTrailBoard.FINISH)].playerId, end.winnerPlayerId)
        }
    }

    @Test fun noStateEverFailsValidate() {
        for (seed in 0L until 1_500L) {
            val start = boardFor(seed)
            assertEquals("seed $seed start", emptyList<String>(), stEngine.validate(start))
            stPlayOut(start) { _, after -> assertEquals("seed $seed turn ${after.turnsTaken}", emptyList<String>(), stEngine.validate(after)) }
        }
    }

    // ---- measured numbers ----

    private fun meanTurns(n: Int, length: TrailLength, boards: Int = 6_000): Double {
        var total = 0L
        for (seed in 0 until boards) total += stPlayOut(stBoard(n, SweetTrailRules(length), seed = seed.toLong())).turnsTaken
        return total.toDouble() / boards
    }

    @Test fun lengthByModeStaysInBudget() {
        // the prototype's means (the design doc, 3.1): every cell within +-15 %
        val target = mapOf(
            TrailLength.SHORT to listOf(10.4, 13.6, 16.5),
            TrailLength.STANDARD to listOf(16.0, 20.8, 25.0),
            TrailLength.LONG to listOf(21.3, 26.8, 31.6)
        )
        val lines = ArrayList<String>()
        for ((length, means) in target) for (n in 2..4) {
            val measured = meanTurns(n, length)
            lines += "$length $n seats: measured %.1f target %.1f".format(measured, means[n - 2])
            assertEquals("$length with $n seats", means[n - 2], measured, means[n - 2] * 0.15)
        }
        println("SWEET-TRAIL-MEASURED lengths\n" + lines.joinToString("\n"))
    }

    @Test fun noStickyStandardThreeSeatsIsAboutTwentyOneTurns() {
        var total = 0L
        val boards = 6_000
        for (seed in 0 until boards) total += stPlayOut(stBoard(3, SweetTrailRules(sticky = false), seed = seed.toLong())).turnsTaken
        val mean = total.toDouble() / boards
        println("SWEET-TRAIL-MEASURED no-sticky standard 3 seats: %.2f turns (prototype 20.7)".format(mean))
        assertEquals(20.7, mean, 20.7 * 0.15)
    }

    private fun firstSeatShare(boards: Int, wish: Set<Int> = emptySet(), level: CpuDifficulty = CpuDifficulty.MEDIUM, n: Int = 2, seat: Int = 0): Double {
        var wins = 0
        for (seed in 0 until boards) {
            val end = stPlayOut(stBoard(n, seed = seed.toLong(), wish = wish), level = { level })
            if (end.winnerPlayerId == "p${seat + 1}") wins++
        }
        return wins * 100.0 / boards
    }

    @Test fun handicapWinRateDeltaIsBounded() {
        val boards = 30_000
        val without = firstSeatShare(boards)
        val withWish = firstSeatShare(boards, wish = setOf(0))
        val delta = withWish - without
        println("SWEET-TRAIL-MEASURED handicap (2 seats, holder moves first, medium): %.1f %% vs %.1f %% without = %+.1f points".format(withWish, without, delta))
        assertTrue("delta $delta", delta > 0.0 && delta < 8.0)
        // the other levels, for the doc
        val easy = firstSeatShare(boards, setOf(0), CpuDifficulty.EASY) - without
        val hard = firstSeatShare(boards, setOf(0), CpuDifficulty.HARD) - without
        println("SWEET-TRAIL-MEASURED handicap deltas: easy %+.1f, medium %+.1f, hard %+.1f".format(easy, delta, hard))
        assertTrue(easy < 8.0 && hard < 8.0)
    }

    @Test fun lastSeatOfFourWithAWishGainsAFewPoints() {
        val boards = 20_000
        val without = firstSeatShare(boards, n = 4, seat = 3)
        val withWish = firstSeatShare(boards, wish = setOf(3), n = 4, seat = 3)
        println("SWEET-TRAIL-MEASURED handicap (4 seats, last seat, medium): %.1f %% vs %.1f %% without = %+.1f points".format(withWish, without, withWish - without))
        assertTrue(withWish - without in 0.0..8.0)
    }

    @Test fun firstMoverAdvantageIsUnderSixPoints() {
        val boards = 30_000
        val share = firstSeatShare(boards)
        println("SWEET-TRAIL-MEASURED first mover (2 seats): %.1f %% (prototype 54.2)".format(share))
        assertTrue("first seat wins $share %", share - 50.0 < 6.0 && share > 50.0)
    }
}
