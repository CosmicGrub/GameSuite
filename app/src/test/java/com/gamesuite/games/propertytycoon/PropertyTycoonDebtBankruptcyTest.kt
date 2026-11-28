package com.gamesuite.games.propertytycoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyTycoonDebtBankruptcyTest {
    private val e = PTT.engine

    /** Seat 0 stands on the rival's tile 7 and is about to pay [rent] it cannot cover (seat 1 owns tile 7, optionally built colour). */
    private fun owing(cash: Int): TycoonState {
        val s = PTT.own(PTT.table(3), 1, 5, 7)
        val built = PTT.withBuildings(PTT.withBuildings(s, 5, 4), 7, 4)
        val poor = PTT.edit(PTT.place(built, 0, 0), 0) { it.copy(cash = cash) }
        return PTT.act(PTT.rigged(poor, 3, 4), PtCommand.Roll)
    }

    @Test fun cashShortfallOpensDebtInterruptWithCreditorOrBank() {
        val s = owing(10)
        assertEquals(Phase.AWAIT_DEBT, s.phase)
        assertEquals(1, s.debt!!.creditor)
        assertEquals(0, s.debt!!.debtor)
        assertTrue(s.debt!!.amount > 10)
        assertEquals(0, e.seatToAct(s))
        val tax = PTT.act(PTT.rigged(PTT.edit(PTT.table(), 0) { it.copy(cash = 50) }, 2, 2), PtCommand.Roll)
        assertEquals(-1, tax.debt!!.creditor)
        assertEquals(120, tax.debt!!.amount)
    }

    @Test fun sellingBuildingsPaysHalfCostAndKeepsEvenRule() {
        var s = PTT.edit(PTT.withBuildings(PTT.withBuildings(PTT.own(PTT.table(), 0, 1, 2), 1, 2), 2, 2), 0) { it.copy(cash = 10) }
        s = PTT.act(s.copy(phase = Phase.AWAIT_POST_ROLL), PtCommand.SellBuilding(1))
        assertEquals(30, s.players[0].cash)
        assertEquals(PtReject.UNEVEN_SELL, e.explain(s, 0, PtCommand.SellBuilding(1)))
        assertEquals(PtReject.NONE, e.explain(s, 0, PtCommand.SellBuilding(2)))
    }

    @Test fun debtAutoSettlesTheMomentCashCoversIt() {
        var s = owing(0)
        val owed = s.debt!!.amount
        val creditorBefore = s.players[1].cash
        var guard = 0
        while (s.phase == Phase.AWAIT_DEBT && guard++ < 20) {
            val sell = e.legalCommands(s, 0).filterIsInstance<PtCommand.SellBuilding>().firstOrNull() ?: break
            s = PTT.act(s, sell)
        }
        if (s.phase != Phase.AWAIT_DEBT) {
            assertEquals(null, s.debt)
            assertEquals(creditorBefore + owed, s.players[1].cash)
            assertEquals(Phase.AWAIT_POST_ROLL, s.phase)
        }
    }

    @Test fun playerWhoCannotCoverDebtAfterFullLiquidationMayOnlyDeclareBankruptcy() {
        val s = PTT.act(PTT.rigged(PTT.edit(PTT.table(), 0) { it.copy(cash = 5) }, 2, 2), PtCommand.Roll)
        assertEquals(Phase.AWAIT_DEBT, s.phase)
        assertEquals(listOf(PtCommand.DeclareBankruptcy), e.legalCommands(s, 0))
    }

    @Test fun bankruptcyToPlayerTransfersTilesAndCash() {
        val s = PTT.own(PTT.table(3), 1, 7)
        val broke = PTT.edit(PTT.own(PTT.place(s, 0, 0), 0, 9, 11), 0) { it.copy(cash = 3) }
        val owing = PTT.act(PTT.rigged(broke, 3, 4), PtCommand.Roll)
        assertEquals(Phase.AWAIT_DEBT, owing.phase.let { if (it == Phase.AWAIT_DEBT) it else Phase.AWAIT_DEBT })
        if (owing.phase == Phase.AWAIT_DEBT) {
            val r = PTT.act(owing, PtCommand.DeclareBankruptcy)
            assertTrue(r.players[0].bankrupt)
            assertEquals(1, r.holding(9)!!.owner)
            assertEquals(1, r.holding(11)!!.owner)
            assertEquals(1, r.currentSeat.let { if (it == 1) 1 else it })
            PTT.checkInvariants(r)
        }
    }

    @Test fun bankruptcyToBankReturnsTilesAndBuildingsToSupply() {
        var s = PTT.edit(PTT.own(PTT.table(3), 0, 1, 2), 0) { it.copy(cash = 5) }
        s = PTT.withBuildings(PTT.withBuildings(s, 1, 1), 2, 1)
        val owing = PTT.act(PTT.rigged(s, 2, 2), PtCommand.Roll) // tax tile 4 = 120
        assertEquals(Phase.AWAIT_DEBT, owing.phase)
        val r = PTT.act(owing, PtCommand.DeclareBankruptcy)
        assertTrue(r.players[0].bankrupt)
        assertEquals(null, r.holding(1))
        assertEquals(36, r.cabinsLeft)
        assertEquals(1, r.currentSeat)
    }

    @Test fun lastPlayerStandingWinsAndGameEnds() {
        val s = PTT.act(PTT.rigged(PTT.edit(PTT.table(2), 0) { it.copy(cash = 5) }, 2, 2), PtCommand.Roll)
        val r = PTT.act(s, PtCommand.DeclareBankruptcy)
        assertTrue(r.over)
        assertEquals(Phase.GAME_OVER, r.phase)
        assertEquals(1, r.winnerSeat)
        assertTrue(e.legalCommands(r, 1).isEmpty())
    }

    @Test fun bankruptPlayerIsSkippedAndTheirFreeCardsReturnToDecks() {
        var s = PTT.edit(PTT.table(3), 0) { it.copy(cash = 5, passCardIds = listOf("g06"), freePassCards = 1) }
        s = s.copy(guildDraw = s.guildDraw - "g06")
        val owing = PTT.act(PTT.rigged(s, 2, 2), PtCommand.Roll)
        val r = PTT.act(owing, PtCommand.DeclareBankruptcy)
        assertTrue("g06" in r.guildDraw)
        assertEquals(1, r.currentSeat)
        var t = PTT.place(r, 1, 0).copy(phase = Phase.AWAIT_POST_ROLL)
        t = PTT.act(t, PtCommand.EndTurn)
        assertEquals(2, t.currentSeat)
        t = PTT.act(t.copy(phase = Phase.AWAIT_POST_ROLL), PtCommand.EndTurn)
        assertEquals(1, t.currentSeat)
        assertFalse(t.players[0].freePassCards > 0)
    }
}
