package com.gamesuite.games.sweettrail

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The computer's one decision, whether to spend a wish (docs/design/boardgames/sweet-trail.md, 2.4). */
class SweetTrailBotTest {
    private val heads = object : Random() { override fun nextBits(bitCount: Int) = 0 }  // nextBoolean() == false
    private val tails = object : Random() { override fun nextBits(bitCount: Int) = -1 } // nextBoolean() == true

    private fun wants(gain: Int, puddle: Boolean = false, wins: Boolean = false, shortcut: Boolean = false, level: CpuDifficulty, coin: Random = heads) =
        SweetTrailBot.wantsWish(gain, puddle, wins, shortcut, level, coin)

    /** A table where seat 0 holds a wish and the card [id] is pending, the piece on [from]. */
    private fun pending(from: Int, id: Int, rules: SweetTrailRules = SweetTrailRules()): SweetTrailState {
        val b = stBoard(2, rules, wish = setOf(0)).at(from, 0)
        return stEngine.draw(b.withTop(id))
    }

    @Test fun easyBotNeverStallsTheTable() {
        for (seed in 0L until 200L) {
            val g = stNewGame(stRoster(3, setOf(0, 1, 2))) { fixedSeed = seed; difficulty = CpuDifficulty.EASY; wishSeats = setOf(0, 1, 2) }
            g.playBotsToTheEnd()
            assertTrue(g.state.value!!.over)
        }
    }

    @Test fun easyBotFlipsACoinUnlessItWouldWasteAWinOrAShortcut() {
        assertTrue(wants(5, level = CpuDifficulty.EASY, coin = tails))
        assertFalse(wants(5, level = CpuDifficulty.EASY, coin = heads))
        assertFalse(wants(0, wins = true, level = CpuDifficulty.EASY, coin = tails))
        assertFalse(wants(0, shortcut = true, level = CpuDifficulty.EASY, coin = tails))
    }

    @Test fun mediumBotWishesOnAZeroGainCard() {
        assertTrue(wants(0, level = CpuDifficulty.MEDIUM))
        assertFalse(wants(1, level = CpuDifficulty.MEDIUM))
        assertFalse(wants(3, level = CpuDifficulty.MEDIUM, coin = tails))
        // through the state: a landmark behind the piece is a dance, gain 0
        val s = pending(30, landmark(TrailLandmark.COOKIE_HILL))
        assertTrue(SweetTrailBot.decide(s, CpuDifficulty.MEDIUM))
        assertTrue(SweetTrailBot.decide(s, CpuDifficulty.HARD))
        // a one-square hop is kept by Medium
        val one = pending(0, single(TrailColour.CHERRY))
        assertFalse(SweetTrailBot.decide(one, CpuDifficulty.MEDIUM))
    }

    @Test fun mediumBotWishesOutOfThePuddle() {
        assertTrue(wants(5, puddle = true, level = CpuDifficulty.MEDIUM))
        val s = pending(29, single(TrailColour.LIME)) // 33 is the Gooey Puddle: gain 4, but stuck
        assertTrue(SweetTrailBot.decide(s, CpuDifficulty.MEDIUM))
        assertTrue(SweetTrailBot.decide(s, CpuDifficulty.HARD))
        // with the puddle switched off the same card is a plain gain of four
        val plain = pending(29, single(TrailColour.LIME), SweetTrailRules(sticky = false))
        assertFalse(SweetTrailBot.decide(plain, CpuDifficulty.MEDIUM))
        assertFalse(SweetTrailBot.decide(plain, CpuDifficulty.HARD))
    }

    @Test fun hardBotWishesOnAGainUnderFour() {
        assertTrue(wants(3, level = CpuDifficulty.HARD))
        assertFalse(wants(4, level = CpuDifficulty.HARD))
        assertFalse(wants(3, level = CpuDifficulty.MEDIUM))
        val s = pending(0, single(TrailColour.LEMON)) // square 3: gain 3
        assertTrue(SweetTrailBot.decide(s, CpuDifficulty.HARD))
        assertFalse(SweetTrailBot.decide(s, CpuDifficulty.MEDIUM))
    }

    @Test fun noBotWishesAwayAWinOrAShortcut() {
        for (level in CpuDifficulty.values()) for (coin in listOf(heads, tails)) {
            assertFalse(wants(0, wins = true, level = level, coin = coin))
            assertFalse(wants(0, puddle = true, wins = true, level = level, coin = coin))
            assertFalse(wants(0, shortcut = true, level = level, coin = coin))
            assertFalse(wants(1, puddle = true, shortcut = true, level = level, coin = coin))
        }
        val win = pending(57, single(TrailColour.CHERRY))
        val shortcut = pending(7, single(TrailColour.CHERRY)) // 8 is the Jelly Bridge
        for (level in CpuDifficulty.values()) {
            assertFalse(SweetTrailBot.decide(win, level))
            assertFalse(SweetTrailBot.decide(shortcut, level))
        }
    }

    @Test fun nothingToDecideMeansKeep() {
        val none = stBoard(wish = setOf(0))
        for (level in CpuDifficulty.values()) assertFalse(SweetTrailBot.decide(none, level))
        val noWish = stEngine.draw(stBoard().withTop(0))
        for (level in CpuDifficulty.values()) assertFalse(SweetTrailBot.decide(noWish, level))
    }

    @Test fun botDecisionsAreDeterministicForAState() {
        for (seed in 0L until 60L) {
            val s = stEngine.draw(stBoard(2, wish = setOf(0), seed = seed))
            for (level in CpuDifficulty.values()) {
                val first = SweetTrailBot.decide(s, level)
                repeat(5) { assertEquals(first, SweetTrailBot.decide(s, level)) }
            }
        }
    }

    @Test fun botNeverTouchesTheDeckRandom() {
        val counting = CountingRandom(3)
        val g = stNewGame(stRoster(3, setOf(0, 1, 2)), random = counting) {
            difficulty = CpuDifficulty.EASY; wishSeats = setOf(0, 1, 2)
        }
        val afterBoardSeed = counting.calls
        g.playBotsToTheEnd()
        assertEquals("the computer's coin comes from the state, not the deck stream", afterBoardSeed, counting.calls)
    }

    @Test fun aBotPlaysTheSameBoardTheSameWayEveryTime() {
        fun play(): List<Int> {
            val g = stNewGame(stRoster(2, setOf(0, 1)), random = Random(42)) { difficulty = CpuDifficulty.HARD; wishSeats = setOf(0, 1) }
            g.playBotsToTheEnd()
            return g.state.value!!.positions + g.state.value!!.turnsTaken
        }
        assertEquals(play(), play())
    }

    @Test fun botsPlayEveryLevelToTheEndOnAThousandSeeds() {
        for (level in CpuDifficulty.values()) for (seed in 0L until 1_000L) {
            val n = 2 + (seed % 3).toInt()
            val rules = SweetTrailRules(TrailLength.values()[(seed % 3).toInt()], sticky = seed % 4 != 0L)
            val end = stPlayOut(stBoard(n, rules, seed = seed, wish = (0 until n).toSet()), level = { level })
            assertTrue("$level seed $seed", end.over)
            assertTrue(end.positions.count { it == SweetTrailBoard.FINISH } == 1)
        }
    }

    @Test fun harderComputersHoldingAWishWinMoreOften() {
        // two seats, seat 0 (moves first) holds the wish, seat 1 does not: the same 30 000 boards per level
        val boards = 30_000
        fun share(level: CpuDifficulty): Double {
            var wins = 0
            for (seed in 0 until boards) {
                val end = stPlayOut(stBoard(2, seed = seed.toLong(), wish = setOf(0)), level = { level })
                if (end.winnerPlayerId == "p1") wins++
            }
            return wins * 100.0 / boards
        }
        val easy = share(CpuDifficulty.EASY)
        val medium = share(CpuDifficulty.MEDIUM)
        val hard = share(CpuDifficulty.HARD)
        println("wish-holder win share (seat 0, 2 seats, standard): easy=$easy medium=$medium hard=$hard")
        assertTrue("hard $hard >= medium $medium", hard >= medium)
        assertTrue("medium $medium >= easy $easy", medium >= easy)
    }
}
