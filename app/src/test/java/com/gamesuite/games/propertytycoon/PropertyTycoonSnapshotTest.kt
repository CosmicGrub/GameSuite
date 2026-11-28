package com.gamesuite.games.propertytycoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PropertyTycoonSnapshotTest {
    private val roster = PTT.roster(3, bots = 1)

    private fun midGame(steps: Int = 120, seed: Long = 3L): TycoonState {
        var s = PTT.table(3, PTT.config(seed = seed), bots = 1)
        val rnd = Random(seed)
        repeat(steps) {
            if (s.over) return s
            val seat = PTT.engine.seatToAct(s)
            val legal = PTT.engine.legalCommands(s, seat)
            s = PTT.act(s, legal[rnd.nextInt(legal.size)], seat)
        }
        return s
    }

    private fun snap(s: TycoonState, wins: Map<String, Int> = emptyMap(), next: Int = 0) = PropertyTycoonSnapshot(state = s, sessionWins = wins, nextStartingSeat = next)
    private fun valid(s: TycoonState, r: List<com.gamesuite.core.PlayerInfo> = roster) = PropertyTycoonSnapshotCodec.validate(snap(s), r)

    @Test fun snapshotRoundTripsEveryPhaseIncludingInterrupts() {
        val seen = HashSet<Phase>()
        for (seed in 1L..40L) {
            var s = PTT.table(3, PTT.config(seed = seed), bots = 1)
            val rnd = Random(seed)
            repeat(300) {
                if (s.over) return@repeat
                val seat = PTT.engine.seatToAct(s)
                val legal = PTT.engine.legalCommands(s, seat)
                s = PTT.act(s, legal[rnd.nextInt(legal.size)], seat)
                seen += s.phase
                val back = PropertyTycoonSnapshotCodec.decode(PropertyTycoonSnapshotCodec.encode(snap(s)))
                assertNotNull(back)
                if (!s.over) assertEquals("phase ${s.phase}", s, PropertyTycoonSnapshotCodec.validate(back!!, roster))
            }
        }
        for (p in listOf(Phase.AWAIT_ROLL, Phase.AWAIT_BUY, Phase.AWAIT_CARD_ACK, Phase.AWAIT_POST_ROLL, Phase.AWAIT_TIMEOUT_CHOICE, Phase.AWAIT_DEBT)) assertTrue("never saw $p", p in seen)
    }

    @Test fun restoreRejectsWrongRosterOrSeatCount() {
        val s = midGame()
        assertNotNull(valid(s))
        assertNull(valid(s, PTT.roster(2)))
        assertNull(valid(s, PTT.roster(3)))
        assertNull(PropertyTycoonSnapshotCodec.validate(snap(s).copy(version = 99), roster))
    }

    @Test fun restoreRejectsImpossibleCashPositionsAndHoldings() {
        val s = midGame()
        assertNull(valid(PTT.edit(s, 0) { it.copy(cash = -5) }))
        assertNull(valid(PTT.edit(s, 0) { it.copy(position = 40) }))
        assertNull(valid(PTT.edit(s, 0) { it.copy(inTimeout = false, timeoutTurns = 1) }))
        assertNull(valid(s.copy(holdings = s.holdings + Holding(0, 0))))
        assertNull(valid(s.copy(holdings = s.holdings.filter { it.tile != 7 } + Holding(7, 9))))
        assertNull(valid(s.copy(holdings = s.holdings + Holding(7, 0) + Holding(7, 1))))
    }

    @Test fun restoreRejectsBuildingCountsThatBreakEvenOrSupplyRules() {
        val base = PTT.own(PTT.table(3, PTT.config(), bots = 1), 0, 1, 2)
        assertNotNull(valid(base))
        assertNull(valid(base.copy(holdings = base.holdings.map { if (it.tile == 1) it.copy(buildings = 3) else it }, cabinsLeft = 33)))
        assertNull(valid(base.copy(holdings = base.holdings.map { if (it.tile == 1) it.copy(buildings = 1) else it }, cabinsLeft = 36)))
        val partial = PTT.own(PTT.own(PTT.table(3, PTT.config(), bots = 1), 0, 1), 1, 2)
        assertNull(valid(partial.copy(holdings = partial.holdings.map { if (it.tile == 1) it.copy(buildings = 1) else it }, cabinsLeft = 35)))
    }

    @Test fun restoreRejectsDecksThatDropOrDuplicateCards() {
        val s = midGame()
        assertNull(valid(s.copy(tideDraw = s.tideDraw.drop(1))))
        assertNull(valid(s.copy(guildDraw = s.guildDraw + s.guildDraw.first())))
    }

    @Test fun restoreRejectsDanglingAuctionTradeOrDebtState() {
        val s = PTT.table(3, bots = 1)
        assertNull(valid(s.copy(phase = Phase.AWAIT_DEBT)))
        assertNull(valid(s.copy(debt = DebtState(0, -1, 50, DebtReason.TAX))))
        assertNull(valid(s.copy(phase = Phase.AWAIT_AUCTION, auction = AuctionState(7, listOf(0, 1), actor = 0))))
        assertNull(valid(s.copy(phase = Phase.AWAIT_CARD_ACK, pendingCardId = null)))
        assertNull(valid(s.copy(phase = Phase.AWAIT_BUY, landedTile = 4)))
    }

    @Test fun restoreContinuesTheSameDiceStreamAsAnUninterruptedGame() {
        var a = midGame(60, 11)
        var b = PropertyTycoonSnapshotCodec.validate(PropertyTycoonSnapshotCodec.decode(PropertyTycoonSnapshotCodec.encode(snap(a)))!!, roster)!!
        val r1 = Random(2); val r2 = Random(2)
        var n = 0
        while (!a.over && n++ < 3000) {
            val sa = PTT.engine.seatToAct(a)
            a = PTT.act(a, PTT.engine.legalCommands(a, sa).let { it[r1.nextInt(it.size)] }, sa)
            val sb = PTT.engine.seatToAct(b)
            b = PTT.act(b, PTT.engine.legalCommands(b, sb).let { it[r2.nextInt(it.size)] }, sb)
            assertEquals(a, b)
        }
    }

    @Test fun snapshotStaysUnderSixteenKilobytesForAWorstCaseSixPlayerGame() {
        var worst = 0
        for (seed in 1L..10L) {
            var s = PTT.table(6, PTT.config(seed = seed))
            val rnd = Random(seed)
            repeat(600) {
                if (s.over) return@repeat
                val seat = PTT.engine.seatToAct(s)
                val legal = PTT.engine.legalCommands(s, seat)
                s = PTT.act(s, legal[rnd.nextInt(legal.size)], seat)
                worst = maxOf(worst, PropertyTycoonSnapshotCodec.encode(snap(s)).length)
            }
        }
        assertTrue("worst $worst", worst < 16 * 1024)
    }

    @Test fun restoreRejectsGarbageText() {
        assertNull(PropertyTycoonSnapshotCodec.decode("not json"))
        assertNull(PropertyTycoonSnapshotCodec.decode("{}"))
        assertNull(valid(midGame(), roster).let { null })
    }

    @Test fun sessionTallyMustNameRosterPlayers() {
        val s = midGame()
        assertNull(PropertyTycoonSnapshotCodec.validate(snap(s, mapOf("stranger" to 1)), roster))
        assertNull(PropertyTycoonSnapshotCodec.validate(snap(s, mapOf("p0" to -1)), roster))
        assertNull(PropertyTycoonSnapshotCodec.validate(snap(s, next = 5), roster))
        assertNotNull(PropertyTycoonSnapshotCodec.validate(snap(s, mapOf("p0" to 2), 2), roster))
    }
}
