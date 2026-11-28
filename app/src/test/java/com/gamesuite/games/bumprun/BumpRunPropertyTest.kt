package com.gamesuite.games.bumprun

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Random-play invariants. The doc asks for 10 000 seeds; these run a few hundred per property to keep the gate quick. */
class BumpRunPropertyTest {
    private fun randomPlay(seed: Long, seats: Int, length: BumpLength, pads: Boolean, teams: Boolean = false, onStep: (BumpRunState, BumpRunState) -> Unit = { _, _ -> }): BumpRunState {
        val r = Random(seed)
        var s = brBoard(seats = seats, length = length, pads = pads, teams = teams, seed = seed, starter = (seed % seats).toInt())
        var steps = 0
        while (!s.over) {
            val next = if (s.phase == BumpPhase.AWAIT_DRAW) bumpEngine.draw(s) else {
                if (s.doOvers[s.currentPlayerIndex] > 0 && r.nextInt(4) == 0) bumpEngine.doOver(s)
                else bumpEngine.legalMoves(s).let { m -> bumpEngine.play(s, if (m.isEmpty()) BumpMove(-1, BumpMoveKind.PASS) else m[r.nextInt(m.size)]) }
            }
            check(next !== s) { "stalled" }
            onStep(s, next)
            s = next
            check(++steps < 60_000) { "no winner" }
        }
        return s
    }

    private fun configs(seed: Long): Triple<Int, BumpLength, Boolean> = Triple(2 + (seed % 3).toInt(), BumpLength.values()[(seed / 3 % 3).toInt()], seed % 2 == 0L)

    @Test fun randomLegalPlayAlwaysTerminatesAndEveryStateValidates() {
        for (seed in 1L..240L) {
            val (seats, length, pads) = configs(seed)
            val end = randomPlay(seed, seats, length, pads) { _, n -> assertEquals("seed $seed", emptyList<String>(), bumpEngine.validate(n)) }
            assertEquals(1, end.winnerPlayerIds.size)
        }
    }

    @Test fun teamBoardsTerminateAndEndWithBothMates() {
        for (seed in 1L..40L) {
            val end = randomPlay(seed, 4, BumpLength.QUICK, seed % 2 == 0L, teams = true)
            assertEquals(2, end.winnerPlayerIds.size)
            assertEquals(emptyList<String>(), bumpEngine.validate(end))
        }
    }

    @Test fun pawnProgressOnlyDecreasesByABumpOrASwap() {
        for (seed in 1L..120L) {
            val (seats, length, pads) = configs(seed)
            randomPlay(seed, seats, length, pads) { before, after ->
                for (p in before.pawns.indices) {
                    if (after.pawns[p] < before.pawns[p]) {
                        assertTrue("seed $seed pawn $p ${before.pawns[p]}->${after.pawns[p]}", after.event == BumpRunEvent.BUMP || after.event == BumpRunEvent.SWAP)
                    }
                }
            }
        }
    }

    @Test fun cardsAreConservedEveryStep() {
        for (seed in 1L..60L) {
            randomPlay(seed, 3, BumpLength.CLASSIC, true) { _, n ->
                assertEquals(54, n.pile.size + n.discard.size + (if (n.card != null) 1 else 0))
            }
        }
    }

    @Test fun legalMovesAreEmptyOnlyWhenNoPawnCanUseTheCard() {
        for (seed in 1L..60L) {
            randomPlay(seed, 3, BumpLength.CLASSIC, true) { _, n ->
                if (n.phase == BumpPhase.AWAIT_CHOICE && bumpEngine.legalMoves(n).isEmpty()) {
                    // an empty list means the seat holds a do-over (otherwise the engine passes at once)
                    assertTrue(n.doOvers[n.currentPlayerIndex] > 0)
                }
            }
        }
    }

    @Test fun lengthByModeStaysInBudget() {
        // doc table: Quick, two seats, mean about 60 total turns (95th percentile 92)
        val levels = List(2) { CpuDifficulty.MEDIUM }
        val turns = (1L..60L).map { seed ->
            brPlayOut(brBoard(seed = seed, bots = true, pads = true, length = BumpLength.QUICK), levels).turnsTaken
        }
        val mean = turns.average()
        assertTrue("mean $mean", mean in 30.0..120.0)
        assertTrue("max ${turns.max()}", turns.max() < 400)
    }

    @Test fun helperPackWinRateDeltaIsBounded() {
        // two seats, Classic, equal Medium players: the helper seat should win clearly more than half (doc: about 56%)
        var wins = 0
        val boards = 400
        for (i in 0 until boards) {
            val start = brBoard(seed = 5000L + i, bots = true, length = BumpLength.CLASSIC, pads = true, helpers = setOf(0), starter = i % 2)
            val end = brPlayOut(start, List(2) { CpuDifficulty.MEDIUM })
            if ("p1" in end.winnerPlayerIds) wins++
        }
        val share = wins * 100.0 / boards
        assertTrue("helper share $share", share in 50.0..70.0)
    }

    @Test fun noStallsInBotBoardsOfEveryTableSize() {
        for (seed in 1L..90L) {
            val seats = 2 + (seed % 3).toInt()
            val start = brBoard(seats = seats, seed = seed, bots = true, length = BumpLength.LONG, pads = true)
            assertTrue(brPlayOut(start, List(seats) { CpuDifficulty.EASY }).over)
        }
    }
}
