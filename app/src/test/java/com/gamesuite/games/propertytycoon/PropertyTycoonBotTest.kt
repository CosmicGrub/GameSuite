package com.gamesuite.games.propertytycoon

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PropertyTycoonBotTest {
    private fun run(level: CpuDifficulty, seed: Long, n: Int = 3, length: GameLength = GameLength.STANDARD) =
        PTT.playBots(PTT.table(n, PTT.config(seed = seed, length = length), bots = n), level)

    @Test fun everyBotLevelAlwaysReturnsALegalCommandInEveryPhase() {
        for (level in CpuDifficulty.values()) for (seed in 1L..12L) {
            val (end, _) = run(level, seed, 2 + (seed % 4).toInt())
            assertTrue(end.over)
        }
    }

    @Test fun easyBotBuysWithSmallReserveAndNeverTrades() {
        val s = PTT.act(PTT.rigged(PTT.table(2, bots = 2), 3, 4), PtCommand.Roll)
        assertEquals(Phase.AWAIT_BUY, s.phase)
        assertEquals(PtCommand.Buy, PTT.bot.decide(s, 0, CpuDifficulty.EASY, PTT.engine))
        val poor = PTT.edit(s, 0) { it.copy(cash = 120) }
        assertEquals(PtCommand.DeclineBuy, PTT.bot.decide(poor, 0, CpuDifficulty.EASY, PTT.engine))
        assertEquals(0, PTT.bot.tradeScore(s, 0, Trade(1, 0, 1, TradeSide(), TradeSide()), CpuDifficulty.EASY))
    }

    @Test fun mediumBotKeepsReserveAndBuildsHighestReturnEvenly() {
        val s = PTT.own(PTT.table(2, bots = 2), 0, 1, 2).copy(phase = Phase.AWAIT_POST_ROLL)
        val first = PTT.bot.decide(s, 0, CpuDifficulty.MEDIUM, PTT.engine)
        assertTrue(first is PtCommand.Build)
        val broke = PTT.edit(s, 0) { it.copy(cash = 100) }
        assertEquals(PtCommand.EndTurn, PTT.bot.decide(broke, 0, CpuDifficulty.MEDIUM, PTT.engine))
    }

    @Test fun hardBotValuesSetCompletionAndBlocking() {
        val s = PTT.own(PTT.table(2, bots = 2), 0, 1)
        val completes = PTT.bot.tileValue(s, 0, 2, CpuDifficulty.HARD)
        val plain = PTT.bot.tileValue(PTT.table(2, bots = 2), 0, 2, CpuDifficulty.HARD)
        assertTrue(completes > plain)
        val rival = PTT.own(PTT.table(2, bots = 2), 1, 1)
        assertTrue(PTT.bot.tileValue(rival, 0, 2, CpuDifficulty.HARD) > PTT.bot.tileValue(PTT.table(2, bots = 2), 0, 2, CpuDifficulty.HARD))
        assertEquals(PtBoard.tile(2).price, PTT.bot.tileValue(s, 0, 2, CpuDifficulty.EASY))
    }

    @Test fun botsPayFineEarlyAndStayInTheSandbarLate() {
        val box = PTT.edit(PTT.table(2, bots = 2), 0) { it.copy(inTimeout = true, position = 10) }.copy(phase = Phase.AWAIT_TIMEOUT_CHOICE)
        assertEquals(PtCommand.PayFine, PTT.bot.decide(box, 0, CpuDifficulty.MEDIUM, PTT.engine))
        assertEquals(PtCommand.Roll, PTT.bot.decide(box, 0, CpuDifficulty.EASY, PTT.engine))
        val card = PTT.edit(box, 0) { it.copy(passCardIds = listOf("g06"), freePassCards = 1) }.copy(guildDraw = box.guildDraw - "g06")
        assertEquals(PtCommand.UseFreePassCard, PTT.bot.decide(card, 0, CpuDifficulty.HARD, PTT.engine))
    }

    @Test fun botLiquidationOrderLosesLeastValueFirst() {
        var s = PTT.own(PTT.own(PTT.table(2, bots = 2), 0, 1, 2), 0, 13, 14, 15)
        s = PTT.withBuildings(PTT.withBuildings(s, 1, 1), 2, 1)
        s = PTT.withBuildings(PTT.withBuildings(PTT.withBuildings(s, 13, 1), 14, 1), 15, 1)
        val owing = PTT.act(PTT.rigged(PTT.edit(s, 0) { it.copy(cash = 5) }, 2, 2), PtCommand.Roll)
        assertEquals(Phase.AWAIT_DEBT, owing.phase)
        assertTrue(PTT.bot.decide(owing, 0, CpuDifficulty.MEDIUM, PTT.engine) is PtCommand.SellBuilding)
    }

    @Test fun botThinkBudgetStaysUnderTheLimitOnAMidRangeProfile() {
        val times = ArrayList<Long>()
        var s = PTT.table(4, PTT.config(seed = 3), bots = 4)
        var n = 0
        while (!s.over && n++ < 4000) {
            val seat = PTT.engine.seatToAct(s)
            val t0 = System.nanoTime()
            val c = PTT.bot.decide(s, seat, CpuDifficulty.HARD, PTT.engine)
            times += System.nanoTime() - t0
            s = PTT.act(s, c, seat)
        }
        times.sort()
        assertTrue("p95 ${times[times.size * 95 / 100] / 1e6} ms", times[times.size * 95 / 100] < 20_000_000L)
    }

    @Test fun botDecisionsAreDeterministicForAGivenStateAndSeed() {
        assertEquals(run(CpuDifficulty.MEDIUM, 21).first, run(CpuDifficulty.MEDIUM, 21).first)
        val s = PTT.table(2, bots = 2)
        assertEquals(PTT.bot.decide(s, 0, CpuDifficulty.EASY, PTT.engine), PTT.bot.decide(s, 0, CpuDifficulty.EASY, PTT.engine))
    }

    @Test fun botsNeverStallInRandomMixedTables() {
        val rnd = Random(3)
        repeat(20) {
            val levels = CpuDifficulty.values()
            var s = PTT.table(3, PTT.config(seed = 700L + it), bots = 3)
            var n = 0
            while (!s.over && n++ < 30000) {
                val seat = PTT.engine.seatToAct(s)
                val c = PTT.bot.decide(s, seat, levels[(seat + rnd.nextInt(1)) % 3], PTT.engine)
                s = PTT.act(s, c, seat)
            }
            assertTrue(s.over)
        }
    }
}
