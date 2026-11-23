package com.gamesuite.ui.party

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyKitLogicTest {

    // ---- roster -----------------------------------------------------------------------------

    @Test
    fun `roster with names wins and is trimmed`() {
        val names = PartyRoster.resolve(listOf(" Ana ", "", "Ben"), 5) { "Player $it" }
        assertEquals(listOf("Ana", "Ben"), names)
    }

    @Test
    fun `empty roster falls back to numbered players within 2 to 12`() {
        assertEquals(listOf("Player 1", "Player 2", "Player 3"), PartyRoster.resolve(emptyList(), 3) { "Player $it" })
        assertEquals(2, PartyRoster.resolve(emptyList(), 0) { "P$it" }.size)
        assertEquals(12, PartyRoster.resolve(listOf("  "), 99) { "P$it" }.size)
    }

    // ---- turn order -------------------------------------------------------------------------

    @Test
    fun `shuffle is a permutation and keeps locked seats`() {
        val start = PartyTurnOrder.initial(8)
        val locked = setOf(0, 3, 7)
        for (seed in 1..50) {
            val result = PartyTurnOrder.shuffle(start, locked, Random(seed))
            assertTrue(PartyTurnOrder.isPermutation(result, 8))
            for (seat in locked) assertEquals(start[seat], result[seat])
        }
    }

    @Test
    fun `shuffle with a fixed seed is reproducible and moves somebody`() {
        val start = PartyTurnOrder.initial(6)
        val a = PartyTurnOrder.shuffle(start, emptySet(), Random(7))
        val b = PartyTurnOrder.shuffle(start, emptySet(), Random(7))
        assertEquals(a, b)
        val anyMoved = (1..20).any { PartyTurnOrder.shuffle(start, emptySet(), Random(it)) != start }
        assertTrue(anyMoved)
    }

    @Test
    fun `all seats locked leaves the order alone and counts free seats`() {
        val start = listOf(2, 0, 1)
        assertEquals(start, PartyTurnOrder.shuffle(start, setOf(0, 1, 2), Random(1)))
        assertEquals(0, PartyTurnOrder.freeSeatCount(start, setOf(0, 1, 2)))
        assertEquals(2, PartyTurnOrder.freeSeatCount(start, setOf(1)))
    }

    @Test
    fun `permutation check and text`() {
        assertTrue(PartyTurnOrder.isPermutation(listOf(2, 0, 1), 3))
        assertFalse(PartyTurnOrder.isPermutation(listOf(0, 0, 1), 3))
        assertFalse(PartyTurnOrder.isPermutation(listOf(0, 1), 3))
        assertEquals("1. Cy\n2. Ana\n3. Ben", PartyTurnOrder.asText(listOf("Ana", "Ben", "Cy"), listOf(2, 0, 1)))
    }

    // ---- first player -----------------------------------------------------------------------

    @Test
    fun `pick is in range and roughly uniform`() {
        val random = Random(123)
        val counts = IntArray(6)
        repeat(6000) { counts[PartyFirstPick.pick(6, random)]++ }
        for (c in counts) assertTrue("count $c", c in 750..1250)
        assertEquals(-1, PartyFirstPick.pick(0, random))
    }

    @Test
    fun `suspense sequence is consecutive and ends on the winner`() {
        for (count in 1..12) for (final in 0 until count) {
            val seq = PartyFirstPick.suspenseSequence(count, final)
            assertTrue(seq.size >= PartyFirstPick.MIN_STEPS)
            assertEquals(final, seq.last())
            for (i in 1 until seq.size) assertEquals((seq[i - 1] + 1) % count, seq[i])
        }
        assertTrue(PartyFirstPick.suspenseSequence(0, 0).isEmpty())
    }

    @Test
    fun `suspense delays start quick and slow down`() {
        val steps = 16
        assertEquals(60L, PartyFirstPick.delayMs(0, steps))
        assertEquals(300L, PartyFirstPick.delayMs(steps, steps))
        var previous = 0L
        for (i in 0..steps) {
            val d = PartyFirstPick.delayMs(i, steps)
            assertTrue(d >= previous)
            previous = d
        }
    }

    // ---- game clock (fake clock: every time below is an injected value) ---------------------

    private val config = PartyClockConfig(bankMs = 60_000L, incrementMs = 5_000L, turnLimitMs = 0L)

    @Test
    fun `fresh clock waits, a tap starts it, and time is charged to the active player`() {
        var s = PartyClock.create(3, config)
        assertTrue(PartyClock.isFresh(s))
        assertTrue(PartyClock.isWaiting(s))
        s = PartyClock.pass(s, 1_000L)
        assertTrue(s.running)
        assertEquals(0, s.active)
        s = PartyClock.tick(s, 11_000L)
        assertEquals(50_000L, s.banksMs[0])
        assertEquals(60_000L, s.banksMs[1])
        assertFalse(PartyClock.isFresh(s))
    }

    @Test
    fun `passing adds the increment and moves to the next player`() {
        var s = PartyClock.pass(PartyClock.create(3, config), 0L)
        s = PartyClock.pass(s, 10_000L)
        assertEquals(55_000L, s.banksMs[0])
        assertEquals(1, s.active)
        assertTrue(s.running)
        s = PartyClock.pass(s, 12_000L)
        assertEquals(58_000L + 5_000L, s.banksMs[1])
        assertEquals(2, s.active)
        s = PartyClock.pass(s, 12_000L)
        assertEquals(0, s.active)
        assertEquals(3, s.turns)
    }

    @Test
    fun `running out flags the player exactly at zero and skips them afterwards`() {
        var s = PartyClock.pass(PartyClock.create(3, config.copy(incrementMs = 0L)), 0L)
        s = PartyClock.pass(s, 1_000L) // player 1 starts at t=1000
        s = PartyClock.tick(s, 100_000L)
        assertFalse(s.running)
        assertEquals(0L, s.banksMs[1])
        assertEquals(setOf(1), s.outPlayers)
        assertEquals(1, s.flagSeq)
        assertFalse(PartyClock.isFinished(s))
        s = PartyClock.pass(s, 101_000L)
        assertEquals(2, s.active)
        assertTrue(s.running)
        s = PartyClock.pass(s, 102_000L)
        assertEquals(0, s.active) // player 1 is skipped
        s = PartyClock.pass(s, 103_000L)
        assertEquals(2, s.active)
    }

    @Test
    fun `flag cue sequence bumps once per flag and not on later ticks`() {
        var s = PartyClock.pass(PartyClock.create(2, config), 0L)
        s = PartyClock.tick(s, 70_000L)
        assertEquals(1, s.flagSeq)
        s = PartyClock.tick(s, 80_000L)
        assertEquals(1, s.flagSeq)
    }

    @Test
    fun `last player standing ends the game`() {
        var s = PartyClock.pass(PartyClock.create(2, config), 0L)
        s = PartyClock.tick(s, 70_000L)
        assertTrue(PartyClock.isFinished(s))
        assertEquals(listOf(1), PartyClock.alivePlayers(s))
        val after = PartyClock.pass(s, 71_000L)
        assertEquals(s, after)
        assertEquals(s, PartyClock.skip(s, 71_000L))
    }

    @Test
    fun `turn limit flags the turn without draining the bank and passes without increment`() {
        val cfg = PartyClockConfig(bankMs = 60_000L, incrementMs = 5_000L, turnLimitMs = 5_000L)
        var s = PartyClock.pass(PartyClock.create(2, cfg), 0L)
        s = PartyClock.tick(s, 7_000L)
        assertFalse(s.running)
        assertTrue(s.turnTimedOut)
        assertEquals(55_000L, s.banksMs[0])
        assertTrue(s.outPlayers.isEmpty())
        assertEquals(1, s.flagSeq)
        s = PartyClock.pass(s, 9_000L)
        assertEquals(1, s.active)
        assertEquals(55_000L, s.banksMs[0]) // no increment for a timed-out turn
        assertFalse(s.turnTimedOut)
        assertTrue(s.running)
        assertEquals(0L, s.turnElapsedMs)
    }

    @Test
    fun `pause stops charging and resume continues from the new time`() {
        var s = PartyClock.pass(PartyClock.create(2, config), 0L)
        s = PartyClock.pause(s, 3_000L)
        assertFalse(s.running)
        assertEquals(57_000L, s.banksMs[0])
        s = PartyClock.tick(s, 50_000L)
        assertEquals(57_000L, s.banksMs[0])
        s = PartyClock.resume(s, 100_000L)
        s = PartyClock.tick(s, 101_000L)
        assertEquals(56_000L, s.banksMs[0])
    }

    @Test
    fun `tap on a paused clock resumes it instead of passing`() {
        var s = PartyClock.pass(PartyClock.create(2, config), 0L)
        s = PartyClock.pause(s, 1_000L)
        s = PartyClock.pass(s, 2_000L)
        assertTrue(s.running)
        assertEquals(0, s.active)
    }

    @Test
    fun `skip gives no increment and a paused clock stays paused`() {
        var s = PartyClock.pass(PartyClock.create(3, config), 0L)
        s = PartyClock.skip(s, 4_000L)
        assertEquals(56_000L, s.banksMs[0])
        assertEquals(1, s.active)
        assertTrue(s.running)
        s = PartyClock.pause(s, 5_000L)
        s = PartyClock.skip(s, 6_000L)
        assertEquals(2, s.active)
        assertFalse(s.running)
    }

    @Test
    fun `clock never goes backwards and handles a backwards time source`() {
        var s = PartyClock.pass(PartyClock.create(2, config), 10_000L)
        s = PartyClock.tick(s, 5_000L)
        assertEquals(60_000L, s.banksMs[0])
    }

    @Test
    fun `time formatting`() {
        assertEquals("0:00", PartyClock.format(0L))
        assertEquals("0:00", PartyClock.format(-5L))
        assertEquals("0:01", PartyClock.format(1L))
        assertEquals("1:00", PartyClock.format(59_001L))
        assertEquals("10:00", PartyClock.format(600_000L))
        assertEquals("1:00:00", PartyClock.format(3_600_000L))
    }

    @Test
    fun `single player clock is over when that player runs out`() {
        var s = PartyClock.pass(PartyClock.create(1, config), 0L)
        assertFalse(PartyClock.isFinished(s))
        s = PartyClock.tick(s, 61_000L)
        assertTrue(PartyClock.isFinished(s))
    }

    // ---- bid scoring ------------------------------------------------------------------------

    @Test
    fun `default scoring is ten plus bid when made and minus the bid otherwise`() {
        val rules = PartyBidRules()
        assertEquals(13, PartyBidScoring.score(3, 3, rules))
        assertEquals(10, PartyBidScoring.score(0, 0, rules))
        assertEquals(-3, PartyBidScoring.score(3, 1, rules))
        assertEquals(-3, PartyBidScoring.score(3, 5, rules)) // exact bid required by default
        assertEquals(0, PartyBidScoring.score(0, 2, rules.copy(missRule = PartyMissRule.MINUS_BID)))
    }

    @Test
    fun `miss rules`() {
        assertEquals(-2, PartyBidScoring.score(3, 1, PartyBidRules(missRule = PartyMissRule.MINUS_DIFFERENCE)))
        assertEquals(0, PartyBidScoring.score(3, 1, PartyBidRules(missRule = PartyMissRule.ZERO)))
    }

    @Test
    fun `extra tricks can still make the bid`() {
        val rules = PartyBidRules(overMakes = true)
        assertEquals(15, PartyBidScoring.score(3, 5, rules))
        assertEquals(-3, PartyBidScoring.score(3, 2, rules))
        assertEquals(10 + 5, PartyBidScoring.score(5, 5, PartyBidRules(madeBonus = 10)))
    }

    // ---- round tracker ----------------------------------------------------------------------

    @Test
    fun `rounds accumulate totals, undo removes the last and leaders handle ties`() {
        var s = PartyRoundTracker.fit(PartyRoundState(), 3)
        s = s.copy(bids = listOf(2, 1, 0), tricks = listOf(2, 0, 0))
        assertEquals(listOf(12, -1, 10), PartyRoundTracker.draftScores(s))
        s = PartyRoundTracker.commit(s)
        assertEquals(1, s.rounds.size)
        assertEquals(listOf(0, 0, 0), s.bids)
        assertEquals(listOf(12, -1, 10), PartyRoundTracker.totals(s))
        assertEquals(listOf(0), PartyRoundTracker.leaders(PartyRoundTracker.totals(s), s.rounds.size))
        s = s.copy(bids = listOf(0, 0, 0), tricks = listOf(0, 0, 0))
        s = PartyRoundTracker.commit(s)
        assertEquals(listOf(22, 9, 20), PartyRoundTracker.totals(s))
        s = PartyRoundTracker.undo(s)
        assertEquals(listOf(12, -1, 10), PartyRoundTracker.totals(s))
        assertEquals(listOf(0, 1), PartyRoundTracker.leaders(listOf(5, 5, 1), 1))
        assertTrue(PartyRoundTracker.leaders(listOf(0, 0), 0).isEmpty())
        assertTrue(PartyRoundTracker.undo(PartyRoundTracker.fit(PartyRoundState(), 2)).rounds.isEmpty())
    }

    @Test
    fun `manual mode records the typed points`() {
        var s = PartyRoundTracker.fit(PartyRoundState(mode = PartyRoundMode.MANUAL), 2)
        s = s.copy(points = listOf(25, -5))
        s = PartyRoundTracker.commit(s)
        assertEquals(listOf(25, -5), PartyRoundTracker.totals(s))
        assertEquals(listOf(0, 0), s.points)
    }

    @Test
    fun `fit keeps matching state and resets a changed player count but keeps mode and rules`() {
        val rules = PartyBidRules(madeBonus = 5)
        val base = PartyRoundTracker.fit(PartyRoundState(mode = PartyRoundMode.MANUAL, rules = rules), 3)
        assertTrue(base === PartyRoundTracker.fit(base, 3))
        val committed = PartyRoundTracker.commit(base)
        val resized = PartyRoundTracker.fit(committed, 4)
        assertTrue(resized.rounds.isEmpty())
        assertEquals(4, resized.bids.size)
        assertEquals(PartyRoundMode.MANUAL, resized.mode)
        assertEquals(rules, resized.rules)
        val reset = PartyRoundTracker.reset(committed)
        assertTrue(reset.rounds.isEmpty())
        assertEquals(3, reset.players)
    }

    // ---- card dealer ------------------------------------------------------------------------

    @Test
    fun `standard deal gives distinct cards and the stock is the rest`() {
        val spec = PartyDeckSpec()
        val dealt = PartyDeck.deal(spec, 4, 13, Random(5))
        assertEquals(4, dealt.hands.size)
        assertTrue(dealt.hands.all { it.size == 13 })
        assertTrue(dealt.stock.isEmpty())
        assertEquals((0 until 52).toSet(), dealt.hands.flatten().toSet())
        assertEquals(52, dealt.hands.flatten().size)
    }

    @Test
    fun `partial deal leaves a stock with no repeats anywhere`() {
        val spec = PartyDeckSpec(jokers = 2)
        assertEquals(54, spec.size)
        val dealt = PartyDeck.deal(spec, 5, 7, Random(9))
        assertEquals(54 - 35, dealt.stock.size)
        val all = dealt.hands.flatten() + dealt.stock
        assertEquals(54, all.size)
        assertEquals((0 until 54).toSet(), all.toSet())
    }

    @Test
    fun `deal is clamped to what the deck allows and is reproducible`() {
        val spec = PartyDeckSpec(kind = PartyDeckKind.CUSTOM, customSize = 10)
        val dealt = PartyDeck.deal(spec, 3, 99, Random(1))
        assertTrue(dealt.hands.all { it.size == 3 })
        assertEquals(1, dealt.stock.size)
        assertEquals(dealt, PartyDeck.deal(spec, 3, 99, Random(1)))
        assertEquals(3, PartyDeck.maxPerPlayer(10, 3))
        assertEquals(0, PartyDeck.maxPerPlayer(10, 0))
    }

    @Test
    fun `draw one each takes from the stock only when it covers everyone`() {
        val spec = PartyDeckSpec(kind = PartyDeckKind.CUSTOM, customSize = 10)
        val dealt = PartyDeck.deal(spec, 3, 3, Random(2)) // stock of 1
        assertNull(PartyDeck.drawOneEach(dealt))
        val bigger = PartyDeck.deal(spec, 3, 2, Random(2)) // stock of 4
        val drawn = PartyDeck.drawOneEach(bigger)!!
        assertTrue(drawn.hands.all { it.size == 3 })
        assertEquals(1, drawn.stock.size)
        assertEquals((0 until 10).toSet(), (drawn.hands.flatten() + drawn.stock).toSet())
    }

    @Test
    fun `card labels`() {
        val std = PartyDeckSpec(jokers = 1)
        assertEquals("A♠", PartyDeck.shortLabel(std, 0))
        assertEquals("K♠", PartyDeck.shortLabel(std, 12))
        assertEquals("A♥", PartyDeck.shortLabel(std, 13))
        assertEquals("10♦", PartyDeck.shortLabel(std, 26 + 9))
        assertEquals("Q♣", PartyDeck.shortLabel(std, 39 + 11))
        assertEquals("JKR", PartyDeck.shortLabel(std, 52))
        assertTrue(PartyDeck.isJoker(std, 52))
        assertFalse(PartyDeck.isRed(std, 0))
        assertTrue(PartyDeck.isRed(std, 13))
        assertTrue(PartyDeck.isRed(std, 26))
        assertFalse(PartyDeck.isRed(std, 39))
        assertEquals("7", PartyDeck.shortLabel(PartyDeckSpec(kind = PartyDeckKind.CUSTOM), 6))
    }
}
