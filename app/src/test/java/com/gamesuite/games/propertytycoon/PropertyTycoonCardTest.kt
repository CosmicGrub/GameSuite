package com.gamesuite.games.propertytycoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyTycoonCardTest {
    private val e = PTT.engine

    /** A table where seat 0 has just drawn [id] and is waiting to read it (standing on a card tile of the right deck). */
    private fun reading(id: String, seat0Tile: Int = if (id.startsWith("t")) 3 else 8, n: Int = 3): TycoonState {
        val s = PTT.place(PTT.table(n), 0, seat0Tile)
        val tide = id.startsWith("t")
        return if (tide) s.copy(phase = Phase.AWAIT_CARD_ACK, pendingCardId = id, tideDraw = s.tideDraw - id, landedTile = seat0Tile)
        else s.copy(phase = Phase.AWAIT_CARD_ACK, pendingCardId = id, guildDraw = s.guildDraw - id, landedTile = seat0Tile)
    }

    @Test fun eachDeckHasSixteenUniqueCardsAndEveryCardHasTextInBothLocales() {
        assertEquals(16, PtCards.tide.map { it.id }.toSet().size)
        assertEquals(16, PtCards.guild.map { it.id }.toSet().size)
        val en = PtStringFiles.parse("res/values/property_tycoon_strings.xml")
        val es = PtStringFiles.parse("res/values-es/property_tycoon_strings.xml")
        for (c in PtCards.all) { assertTrue(c.textKey, c.textKey in en); assertTrue(c.textKey, c.textKey in es) }
    }

    @Test fun decksShuffleDeterministicallyFromTheSeedAndCycleWithoutLosingCards() {
        assertEquals(PTT.table(3, PTT.config(seed = 5)).tideDraw, PTT.table(3, PTT.config(seed = 5)).tideDraw)
        assertTrue(PTT.table(3, PTT.config(seed = 5)).tideDraw != PTT.table(3, PTT.config(seed = 6)).tideDraw)
        var s = PTT.table()
        // draw all 16 tide cards and one more: the discard recycles
        repeat(17) {
            val onTile = PTT.place(s.copy(phase = Phase.AWAIT_ROLL, currentSeat = 0), 0, 34)
            val rolled = PTT.act(PTT.rigged(onTile, 6, 6).let { PTT.edit(it, 0) { p -> p.copy(position = 28) } }, PtCommand.Roll)
            s = if (rolled.phase == Phase.AWAIT_CARD_ACK) PTT.act(rolled, PtCommand.AckCard) else rolled
            s = s.copy(phase = Phase.AWAIT_ROLL, currentSeat = 0, extraRollPending = false)
        }
        val all = s.tideDraw + s.tideDiscard + s.players.flatMap { it.passCardIds }.filter { it.startsWith("t") } + listOfNotNull(s.pendingCardId)
        assertEquals(16, all.toSet().size)
    }

    @Test fun getOutFreeCardLeavesTheDeckUntilUsedThenReturnsToTheBottom() {
        val s = PTT.act(reading("t05"), PtCommand.AckCard)
        assertEquals(listOf("t05"), s.players[0].passCardIds)
        assertEquals(1, s.players[0].freePassCards)
        assertFalse("t05" in s.tideDraw + s.tideDiscard)
    }

    @Test fun moveCardsCollectTheStipendOnlyWhenPassingTheQuayForward() {
        val fwd = PTT.act(PTT.place(reading("t07", 34), 0, 34), PtCommand.AckCard) // MoveTo(39), no wrap
        assertEquals(39, fwd.players[0].position)
        assertEquals(1600, fwd.players[0].cash)
        val wrap = PTT.act(reading("t01", 34), PtCommand.AckCard) // MoveTo(0, collect)
        assertEquals(0, wrap.players[0].position)
        assertEquals(1600 + 160, wrap.players[0].cash)
        val back = PTT.act(reading("t06", 3), PtCommand.AckCard) // MoveBy(-3) from 3 lands on the Quay without stipend
        assertEquals(0, back.players[0].position)
        assertEquals(1600, back.players[0].cash)
    }

    @Test fun moveToNearestResolvesFerryDoubleFareAndUtilityTenTimesDice() {
        val ferry = PTT.own(reading("t02", 3), 1, 6)
        val r = PTT.act(ferry, PtCommand.AckCard)
        assertEquals(6, r.players[0].position)
        assertEquals(1600 - 40, r.players[0].cash) // 20 x 2
        assertEquals(1600 + 40, r.players[1].cash)
        val mill = PTT.own(reading("t03", 3), 1, 12)
        val m = PTT.act(mill, PtCommand.AckCard)
        assertEquals(12, m.players[0].position)
        assertEquals(1600 - 10 * (m.dice1 + m.dice2), m.players[0].cash)
        val free = PTT.act(reading("t02", 3), PtCommand.AckCard)
        assertEquals(Phase.AWAIT_BUY, free.phase)
    }

    @Test fun repairCardsChargeByBuildingCountAndRaiseDebtWhenShort() {
        var s = PTT.own(reading("t10"), 0, 1, 2)
        s = PTT.withBuildings(PTT.withBuildings(s, 1, 2), 2, 1)
        val r = PTT.act(s, PtCommand.AckCard)
        assertEquals(1600 - 35 * 3, r.players[0].cash)
        val poor = PTT.edit(s, 0) { it.copy(cash = 20) }
        val d = PTT.act(poor, PtCommand.AckCard)
        assertEquals(Phase.AWAIT_DEBT, d.phase)
        assertEquals(105, d.debt!!.amount)
        assertEquals(-1, d.debt!!.creditor)
    }

    @Test fun payAndCollectEachRivalCardsSkipBankruptPlayers() {
        val s = PTT.edit(reading("t11"), 2) { it.copy(bankrupt = true, cash = 0) }
        val r = PTT.act(s, PtCommand.AckCard)
        assertEquals(1600 - 25, r.players[0].cash)
        assertEquals(1625, r.players[1].cash)
        assertEquals(0, r.players[2].cash)
        val c = PTT.edit(reading("t12"), 2) { it.copy(bankrupt = true, cash = 0) }
        val g = PTT.act(c, PtCommand.AckCard)
        assertEquals(1620, g.players[0].cash)
        assertEquals(1580, g.players[1].cash)
    }

    @Test fun simpleMoneyCardsPayOrCollect() {
        assertEquals(1700, PTT.act(reading("t08"), PtCommand.AckCard).players[0].cash)
        assertEquals(1540, PTT.act(reading("t09"), PtCommand.AckCard).players[0].cash)
        assertEquals(1780, PTT.act(reading("g10"), PtCommand.AckCard).players[0].cash)
    }

    @Test fun goToTimeoutCardsSendToTheSandbarAndEndTheTurn() {
        val r = PTT.act(reading("t04"), PtCommand.AckCard)
        assertTrue(r.players[0].inTimeout)
        assertEquals(10, r.players[0].position)
        assertEquals(1, r.currentSeat)
    }

    @Test fun landingOnACardTileOpensTheCardPhase() {
        val s = PTT.rigged(PTT.table(), 1, 2)
        val r = PTT.act(s, PtCommand.Roll)
        assertEquals(Phase.AWAIT_CARD_ACK, r.phase)
        assertEquals(3, r.landedTile)
        assertTrue(r.pendingCardId in PtCards.tide.map { it.id })
        assertEquals(listOf(PtCommand.AckCard), e.legalCommands(r, 0))
    }
}
