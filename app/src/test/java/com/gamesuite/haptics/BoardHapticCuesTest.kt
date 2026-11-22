package com.gamesuite.haptics

import com.gamesuite.haptics.HapticSignal.CELEBRATION
import com.gamesuite.haptics.HapticSignal.ESCALATING
import com.gamesuite.haptics.HapticSignal.FAILURE
import com.gamesuite.haptics.HapticSignal.LANDING
import com.gamesuite.haptics.HapticSignal.LIGHT_TICK
import com.gamesuite.haptics.HapticSignal.NORMAL_ACTION
import com.gamesuite.haptics.HapticSignal.RIPPLE_STEP
import com.gamesuite.haptics.HapticSignal.ROLL
import com.gamesuite.haptics.HapticSignal.STRONG_ACTION
import com.gamesuite.haptics.HapticSignal.SUCCESS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReversiHapticCuesTest {
    private fun move(flipped: Int, row: Int = 3, col: Int = 3, size: Int = 8) =
        ReversiHapticCues.Move(row * size + col, 0, flipped, size)

    private fun delays(s: HapticSequence) = s.cues.map { it.delayMs }

    @Test
    fun `quiet human move is a tap then one ripple tick per flip`() {
        val s = ReversiHapticCues.forMove(move(2), lighter = false)
        assertEquals(listOf(NORMAL_ACTION, RIPPLE_STEP, RIPPLE_STEP), s.signals)
        assertEquals(listOf(0L, 60L, 120L), delays(s))
    }

    @Test
    fun `ripple is capped at six ticks and the gesture stays short`() {
        val s = ReversiHapticCues.forMove(move(4), lighter = false)
        assertEquals(4, s.signals.count { it == RIPPLE_STEP })
        val many = ReversiHapticCues.forMove(move(4, 3, 3).copy(flipped = 4), lighter = false)
        assertTrue(many.spanMs < 400)
        // 4 flips, no finisher -> never more than 6 ticks even if the engine reported more flips.
        val capped = ReversiHapticCues.forMove(ReversiHapticCues.Move(27, 0, 4, 8), false)
        assertTrue(capped.signals.count { it == RIPPLE_STEP } <= ReversiHapticCues.MAX_RIPPLE_TICKS)
    }

    @Test
    fun `big flip ends with a strong finisher inside 450 ms`() {
        val s = ReversiHapticCues.forMove(move(9), lighter = false)
        assertEquals(STRONG_ACTION, s.signals.last())
        assertEquals(5, s.signals.count { it == RIPPLE_STEP })
        assertTrue("span ${s.spanMs}", s.spanMs <= HapticSequence.MAX_SPAN_MS)
        assertEquals(5L * 60 + 40, s.cues.last().delayMs)
    }

    @Test
    fun `exactly five flips counts as big and four does not`() {
        assertEquals(STRONG_ACTION, ReversiHapticCues.forMove(move(5), false).signals.last())
        assertFalse(STRONG_ACTION in ReversiHapticCues.forMove(move(4), false).signals)
    }

    @Test
    fun `corner and edge moves earn the finisher even with one flip`() {
        assertEquals(STRONG_ACTION, ReversiHapticCues.forMove(move(1, 0, 0), false).signals.last())
        assertEquals(STRONG_ACTION, ReversiHapticCues.forMove(move(1, 0, 4), false).signals.last())
        assertEquals(STRONG_ACTION, ReversiHapticCues.forMove(move(1, 4, 7), false).signals.last())
        assertTrue(move(1, 7, 7).isCorner)
        assertFalse(move(1, 0, 4).isCorner)
        assertFalse(move(1, 1, 1).isEdge)
    }

    @Test
    fun `every flip count from 1 to 40 stays bounded and sorted`() {
        for (flipped in 1..40) for (row in 0..7) for (col in listOf(0, 3, 7)) for (lighter in listOf(false, true)) {
            val s = ReversiHapticCues.forMove(move(flipped, row, col), lighter)
            assertTrue("$flipped/$row/$col/$lighter span ${s.spanMs}", s.spanMs <= HapticSequence.MAX_SPAN_MS)
            assertFalse(s.hasCelebration)
            assertEquals(delays(s).sorted(), delays(s))
            assertTrue(s.signals.count { it == RIPPLE_STEP } <= ReversiHapticCues.MAX_RIPPLE_TICKS)
        }
    }

    @Test
    fun `bot move is lighter and not silent`() {
        val human = ReversiHapticCues.forMove(move(3), lighter = false)
        val bot = ReversiHapticCues.forMove(move(3), lighter = true)
        assertFalse(bot.isEmpty)
        assertEquals(LIGHT_TICK, bot.signals.first())
        assertFalse(STRONG_ACTION in bot.signals)
        assertTrue(bot.endMs <= human.endMs)
    }

    @Test
    fun `bot corner move finishes with a normal action not a strong one`() {
        val bot = ReversiHapticCues.forMove(move(2, 0, 0), lighter = true)
        assertEquals(NORMAL_ACTION, bot.signals.last())
        assertFalse(STRONG_ACTION in bot.signals)
    }

    @Test
    fun `pass is an escalating pulse`() {
        assertEquals(listOf(ESCALATING), ReversiHapticCues.forPass().signals)
    }

    @Test
    fun `board end maps win loss draw`() {
        assertEquals(listOf(CELEBRATION), ReversiHapticCues.forBoardEnd(HapticOutcome.WIN).signals)
        assertEquals(listOf(FAILURE), ReversiHapticCues.forBoardEnd(HapticOutcome.LOSS).signals)
        assertEquals(listOf(SUCCESS), ReversiHapticCues.forBoardEnd(HapticOutcome.DRAW).signals)
    }

    @Test
    fun `compose chains move then pass then end without overlap`() {
        val s = ReversiHapticCues.compose(move(2), false, passed = true, end = HapticOutcome.WIN)
        assertEquals(
            listOf(NORMAL_ACTION, RIPPLE_STEP, RIPPLE_STEP, ESCALATING, CELEBRATION), s.signals
        )
        val d = delays(s)
        assertTrue(d[3] >= 120 + 8)
        assertTrue(d[4] >= d[3] + HapticPatterns.patternFor(ESCALATING).totalMs)
        assertTrue(ReversiHapticCues.compose(null, false, false, null).isEmpty)
        assertEquals(listOf(ESCALATING), ReversiHapticCues.compose(null, false, true, null).signals)
    }

    @Test
    fun `detect move finds the placed disc and counts flips`() {
        val n = 6
        val prev = MutableList<Int?>(n * n) { null }
        prev[14] = 1; prev[15] = 0; prev[16] = 0
        val next = prev.toMutableList()
        next[13] = 0; next[14] = 0
        val m = ReversiHapticCues.detectMove(prev, next, n)
        assertNotNull(m)
        assertEquals(13, m!!.placedIndex)
        assertEquals(0, m.mover)
        assertEquals(1, m.flipped)
    }

    @Test
    fun `detect move ignores first board, undo, restart and size change`() {
        val n = 6
        val a = MutableList<Int?>(n * n) { null }
        a[14] = 1; a[15] = 0
        val b = a.toMutableList().also { it[13] = 0; it[14] = 0 }
        assertNull(ReversiHapticCues.detectMove(null, b, n))
        assertNull(ReversiHapticCues.detectMove(b, a, n))
        assertNull(ReversiHapticCues.detectMove(b, b, n))
        val fresh = MutableList<Int?>(n * n) { null }.also { it[14] = 0; it[15] = 1; it[20] = 1; it[21] = 0 }
        assertNull(ReversiHapticCues.detectMove(b, fresh, n))
        assertNull(ReversiHapticCues.detectMove(a, MutableList<Int?>(64) { null }, n))
    }
}

class ConnectFourHapticCuesTest {
    private val rows = 6
    private val cols = 7

    private fun board(vararg placed: Pair<Int, Int?>): List<Int?> {
        val c = MutableList<Int?>(rows * cols) { null }
        for ((i, v) in placed) c[i] = v
        return c
    }

    private fun idx(r: Int, c: Int) = r * cols + c

    @Test
    fun `fall time follows the screen tween and is zero with reduced motion`() {
        assertEquals(190L, ConnectFourHapticCues.fallMs(0, false))
        assertEquals(240L, ConnectFourHapticCues.fallMs(1, false))
        assertEquals(350L, ConnectFourHapticCues.fallMs(5, false))
        assertEquals(350L, ConnectFourHapticCues.fallMs(9, false))
        assertEquals(0L, ConnectFourHapticCues.fallMs(5, true))
    }

    @Test
    fun `human drop lands then bounces at the end of the fall`() {
        val s = ConnectFourHapticCues.forDrop(300, lighter = false, threat = false, end = null)
        assertEquals(listOf(LANDING, RIPPLE_STEP), s.signals)
        assertEquals(listOf(300L, 370L), s.cues.map { it.delayMs })
        assertEquals(300L, s.leadInMs)
    }

    @Test
    fun `bot drop is lighter`() {
        val s = ConnectFourHapticCues.forDrop(300, lighter = true, threat = false, end = null)
        assertEquals(listOf(LIGHT_TICK, RIPPLE_STEP), s.signals)
    }

    @Test
    fun `new open three escalates after the bounce`() {
        val s = ConnectFourHapticCues.forDrop(240, lighter = false, threat = true, end = null)
        assertEquals(listOf(LANDING, RIPPLE_STEP, ESCALATING), s.signals)
        assertTrue(s.cues.last().delayMs > s.cues[1].delayMs)
    }

    @Test
    fun `win is a success pulse then celebration, loss a failure, draw a normal action`() {
        val win = ConnectFourHapticCues.forDrop(240, false, threat = true, end = HapticOutcome.WIN)
        assertEquals(listOf(LANDING, RIPPLE_STEP, SUCCESS, CELEBRATION), win.signals)
        assertFalse(ESCALATING in win.signals)
        val loss = ConnectFourHapticCues.forDrop(240, true, false, HapticOutcome.LOSS)
        assertEquals(listOf(LIGHT_TICK, RIPPLE_STEP, FAILURE), loss.signals)
        val draw = ConnectFourHapticCues.forDrop(240, false, false, HapticOutcome.DRAW)
        assertEquals(NORMAL_ACTION, draw.signals.last())
    }

    @Test
    fun `non-celebration drops are bounded once the fall lead-in is excluded`() {
        for (row in 0..5) for (lighter in listOf(false, true)) for (threat in listOf(false, true)) {
            for (end in listOf(null, HapticOutcome.LOSS, HapticOutcome.DRAW)) {
                val s = ConnectFourHapticCues.forDrop(ConnectFourHapticCues.fallMs(row, false), lighter, threat, end)
                assertTrue("span ${s.spanMs}", s.spanMs <= HapticSequence.MAX_SPAN_MS)
            }
        }
    }

    @Test
    fun `detect drop finds exactly one new disc`() {
        val a = board(idx(5, 3) to 0)
        val b = board(idx(5, 3) to 0, idx(5, 4) to 1)
        assertEquals(idx(5, 4), ConnectFourHapticCues.detectDrop(a, b))
        assertNull(ConnectFourHapticCues.detectDrop(null, b))
        assertNull(ConnectFourHapticCues.detectDrop(b, a))
        assertNull(ConnectFourHapticCues.detectDrop(b, b))
        assertNull(ConnectFourHapticCues.detectDrop(a, board()))
        assertNull(ConnectFourHapticCues.detectDrop(a, board(idx(5, 3) to 1, idx(5, 4) to 1)))
    }

    @Test
    fun `open three is detected only when the fourth cell is empty`() {
        val open = board(idx(5, 0) to 0, idx(5, 1) to 0, idx(5, 2) to 0)
        assertTrue(ConnectFourHapticCues.openThreatCount(open, rows, cols, 4, 0) >= 1)
        // Blocked both sides by the opponent at the board edge: three at cols 0-2 with col 3 taken.
        val blocked = board(idx(5, 0) to 0, idx(5, 1) to 0, idx(5, 2) to 0, idx(5, 3) to 1)
        assertEquals(0, ConnectFourHapticCues.openThreatCount(blocked, rows, cols, 4, 0))
        assertEquals(0, ConnectFourHapticCues.openThreatCount(open, rows, cols, 4, 1))
    }

    @Test
    fun `created threat compares before and after`() {
        val before = board(idx(5, 1) to 0, idx(5, 2) to 0)
        val after = board(idx(5, 1) to 0, idx(5, 2) to 0, idx(5, 3) to 0)
        assertTrue(ConnectFourHapticCues.createdThreat(before, after, rows, cols, 4, 0))
        // A fourth disc on an already-open three does not "create" a second one when counts are equal.
        assertFalse(ConnectFourHapticCues.createdThreat(after, after, rows, cols, 4, 0))
        assertFalse(ConnectFourHapticCues.createdThreat(before, before, rows, cols, 4, 0))
    }

    @Test
    fun `vertical three with room above is a threat`() {
        val before = board(idx(5, 2) to 1, idx(4, 2) to 1)
        val after = board(idx(5, 2) to 1, idx(4, 2) to 1, idx(3, 2) to 1)
        assertTrue(ConnectFourHapticCues.createdThreat(before, after, rows, cols, 4, 1))
    }
}

class BackgammonHapticCuesTest {
    private val base = BackgammonHapticCues.RollKey(emptyList(), emptyList(), 0, 0)

    @Test
    fun `roll is a rattle then a settle tick`() {
        val s = BackgammonHapticCues.forRoll()
        assertEquals(listOf(ROLL, LIGHT_TICK), s.signals)
        assertEquals(listOf(0L, 150L), s.cues.map { it.delayMs })
        assertTrue(s.spanMs <= HapticSequence.MAX_SPAN_MS)
        assertTrue(150L >= HapticPatterns.patternFor(ROLL).totalMs)
    }

    @Test
    fun `move cues`() {
        assertEquals(listOf(NORMAL_ACTION), BackgammonHapticCues.forMove(hit = false, bearsOff = false).signals)
        assertEquals(listOf(STRONG_ACTION), BackgammonHapticCues.forMove(hit = true, bearsOff = false).signals)
        assertEquals(listOf(LIGHT_TICK), BackgammonHapticCues.forMove(hit = false, bearsOff = true).signals)
        assertEquals(listOf(STRONG_ACTION), BackgammonHapticCues.forMove(hit = true, bearsOff = true).signals)
    }

    @Test
    fun `game end`() {
        assertEquals(listOf(CELEBRATION), BackgammonHapticCues.forGameEnd(HapticOutcome.WIN).signals)
        assertEquals(listOf(FAILURE), BackgammonHapticCues.forGameEnd(HapticOutcome.LOSS).signals)
        assertEquals(BackgammonHapticCues.END_GAP_MS, BackgammonHapticCues.forGameEnd(HapticOutcome.WIN).cues[0].delayMs)
    }

    @Test
    fun `new roll detection ignores the first state and mere move progress`() {
        val rolled = base.copy(dice = listOf(3, 5))
        assertFalse(BackgammonHapticCues.isNewRoll(null, rolled))
        assertTrue(BackgammonHapticCues.isNewRoll(base, rolled))
        assertFalse(BackgammonHapticCues.isNewRoll(rolled, rolled))
        // Opponent rolls the same numbers next turn.
        assertTrue(BackgammonHapticCues.isNewRoll(rolled, rolled.copy(player = 1)))
        // Dice cleared at end of turn is not a roll.
        assertFalse(BackgammonHapticCues.isNewRoll(rolled, base))
    }

    @Test
    fun `opening roll and a tie re-roll both count`() {
        val open = base.copy(openingDice = listOf(2, 4))
        assertTrue(BackgammonHapticCues.isNewRoll(base, open))
        val tie = base.copy(openingDice = listOf(3, 3), openingTies = 1)
        assertTrue(BackgammonHapticCues.isNewRoll(base.copy(openingDice = listOf(3, 3)), tie))
    }
}
