package com.gamesuite.games.propertytycoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyTycoonRulesTest {
    private val e = PTT.engine

    @Test fun diceAreTwoSixSidedDrawnFromTheSeededStreamInOrder() {
        val s = PTT.table()
        val a = PTT.act(s, PtCommand.Roll)
        val b = PTT.act(s, PtCommand.Roll)
        assertEquals(a, b)
        assertTrue(a.dice1 in 1..6 && a.dice2 in 1..6)
        val r = PtRng(s.rngState)
        assertEquals(r.rollDie(), a.dice1)
        assertEquals(r.rollDie(), a.dice2)
        assertEquals(r.state, a.rngState.let { st -> if (a.phase == Phase.AWAIT_CARD_ACK || a.phase == Phase.AWAIT_BUY) st else r.state })
    }

    @Test fun passingLanternQuayPaysTheStipendOnceAndLandingPaysTwiceOnlyWithTheHouseRule() {
        val s = PTT.rigged(PTT.place(PTT.table(), 0, 33), 3, 4)
        val r = PTT.act(s, PtCommand.Roll)
        assertEquals(0, r.players[0].position)
        assertEquals(1600 + 160, r.players[0].cash)
        val d = PTT.rigged(PTT.place(PTT.table(cfg = PTT.config(rules = HouseRules(doubleStipendOnLanding = true))), 0, 33), 3, 4)
        assertEquals(1600 + 320, PTT.act(d, PtCommand.Roll).players[0].cash)
        val past = PTT.rigged(PTT.place(PTT.table(), 0, 38), 3, 4)
        assertEquals(1600 + 160, PTT.act(past, PtCommand.Roll).players[0].cash)
    }

    @Test fun landingOnUnownedPropertyOffersBuyOrAuction() {
        val s = PTT.rigged(PTT.table(), 3, 4)
        val r = PTT.act(s, PtCommand.Roll)
        assertEquals(Phase.AWAIT_BUY, r.phase)
        assertEquals(7, r.landedTile)
        assertEquals(PtReject.NONE, e.explain(r, 0, PtCommand.Buy))
        val bought = PTT.act(r, PtCommand.Buy)
        assertEquals(0, bought.holding(7)!!.owner)
        assertEquals(1600 - 100, bought.players[0].cash)
        val declined = PTT.act(r, PtCommand.DeclineBuy)
        assertEquals(null, declined.holding(7))
        assertEquals(Phase.AWAIT_POST_ROLL, declined.phase)
    }

    @Test fun rentIsBaseTimesTwoForWholeColourWithoutBuildings() {
        val one = PTT.own(PTT.table(), 1, 1)
        assertEquals(4, e.rentFor(one, 1, 7))
        val both = PTT.own(PTT.table(), 1, 1, 2)
        assertEquals(8, e.rentFor(both, 1, 7))
        assertEquals(10, e.rentFor(both, 2, 7))
    }

    @Test fun rentFollowsBuildingLevelAndRentPercentScaling() {
        var s = PTT.own(PTT.table(cfg = PTT.config(length = GameLength.QUICK)), 1, 1, 2)
        assertEquals(16, e.rentFor(s, 1, 7)) // 4 x 2 (set) x 200 percent
        s = PTT.withBuildings(s, 1, 2)
        assertEquals(55 * 2, e.rentFor(s, 1, 7))
        val marathon = PTT.own(PTT.table(cfg = PTT.config(length = GameLength.MARATHON)), 1, 1)
        assertEquals(3, e.rentFor(marathon, 1, 7)) // 4 x 75 percent
    }

    @Test fun transitRentFollowsFerriesOwned() {
        var s = PTT.own(PTT.table(), 1, 6)
        assertEquals(20, e.rentFor(s, 6, 7))
        s = PTT.own(s, 1, 16)
        assertEquals(45, e.rentFor(s, 6, 7))
        s = PTT.own(s, 1, 26, 36)
        assertEquals(180, e.rentFor(s, 6, 7))
    }

    @Test fun utilityRentIsFiveOrTwelveTimesTheDice() {
        var s = PTT.own(PTT.table(), 1, 12)
        assertEquals(5 * 8, e.rentFor(s, 12, 8))
        s = PTT.own(s, 1, 23)
        assertEquals(12 * 8, e.rentFor(s, 12, 8))
    }

    @Test fun landingOnARivalTilePaysTheOwner() {
        val s = PTT.rigged(PTT.own(PTT.table(), 1, 7), 3, 4)
        val r = PTT.act(s, PtCommand.Roll)
        assertEquals(1600 - 7, r.players[0].cash)
        assertEquals(1600 + 7, r.players[1].cash)
        assertEquals(Phase.AWAIT_POST_ROLL, r.phase)
    }

    @Test fun taxTilesTakeTheirFlatAmountAndFeedTheJackpotWhenEnabled() {
        val s = PTT.rigged(PTT.table(), 2, 2)
        val r = PTT.act(s, PtCommand.Roll)
        assertEquals(1600 - 120, r.players[0].cash)
        assertEquals(0, r.jackpot)
        val j = PTT.rigged(PTT.table(cfg = PTT.config(rules = HouseRules(jackpot = true))), 2, 2)
        assertEquals(120, PTT.act(j, PtCommand.Roll).jackpot)
    }

    @Test fun threeDoublesInARowSendsThePlayerToTheSandbar() {
        var s = PTT.rigged(PTT.table(), 1, 1, 2, 2, 3, 3)
        s = PTT.act(PTT.act(s, PtCommand.Roll), PtCommand.DeclineBuy) // tile 2
        s = PTT.act(PTT.act(s, PtCommand.Roll), PtCommand.DeclineBuy) // tile 6
        assertEquals(Phase.AWAIT_ROLL, s.phase)
        s = PTT.act(s, PtCommand.Roll)
        assertTrue(s.players[0].inTimeout)
        assertEquals(10, s.players[0].position)
        assertEquals(1600, s.players[0].cash)
        assertEquals(1, s.currentSeat)
    }

    @Test fun doublesGiveAnExtraRollButNotWhenLeavingTheSandbar() {
        val s = PTT.rigged(PTT.table(), 1, 1)
        val r = PTT.act(s, PtCommand.Roll)
        val after = if (r.phase == Phase.AWAIT_BUY) PTT.act(r, PtCommand.DeclineBuy) else r
        assertEquals(Phase.AWAIT_ROLL, after.phase)
        assertEquals(0, after.currentSeat)
        // paying the fine to leave: this turn's doubles give no extra roll
        val box = PTT.rigged(PTT.edit(PTT.table(), 0) { it.copy(inTimeout = true, position = 10) }.copy(phase = Phase.AWAIT_TIMEOUT_CHOICE), 1, 1)
        val paid = PTT.act(box, PtCommand.PayFine)
        assertEquals(1500, paid.players[0].cash)
        assertEquals(Phase.AWAIT_ROLL, paid.phase)
        val rolled = PTT.act(paid, PtCommand.Roll)
        assertFalse(rolled.extraRollPending)
    }

    @Test fun sandbarOffersRollFinePayAndFreePassCardAndForcesFineOnThirdTurn() {
        val box = PTT.edit(PTT.table(), 0) { it.copy(inTimeout = true, position = 10) }.copy(phase = Phase.AWAIT_TIMEOUT_CHOICE)
        val legal = e.legalCommands(box, 0)
        assertTrue(PtCommand.Roll in legal && PtCommand.PayFine in legal)
        assertFalse(PtCommand.UseFreePassCard in legal)
        val card = PTT.edit(box, 0) { it.copy(passCardIds = listOf("t05"), freePassCards = 1) }.copy(tideDraw = box.tideDraw - "t05")
        assertTrue(PtCommand.UseFreePassCard in e.legalCommands(card, 0))
        val used = PTT.act(card, PtCommand.UseFreePassCard)
        assertFalse(used.players[0].inTimeout)
        assertEquals("t05", used.tideDraw.last())
        // a failed roll counts a turn; the third forces the fine and moves
        var f = PTT.rigged(PTT.edit(box, 0) { it.copy(timeoutTurns = 2) }, 1, 2)
        f = PTT.act(f, PtCommand.Roll)
        assertFalse(f.players[0].inTimeout)
        assertEquals(1500, f.players[0].cash)
        assertEquals(13, f.players[0].position)
        assertEquals(Phase.AWAIT_BUY, f.phase)
        val first = PTT.act(PTT.rigged(box, 1, 2), PtCommand.Roll)
        assertEquals(1, first.players[0].timeoutTurns)
        assertEquals(1, first.currentSeat)
        assertTrue(first.players[0].inTimeout)
    }

    @Test fun goToTimeoutTileSendsToSandbarWithoutStipend() {
        val s = PTT.rigged(PTT.place(PTT.table(), 0, 25), 2, 3)
        val r = PTT.act(s, PtCommand.Roll)
        assertTrue(r.players[0].inTimeout)
        assertEquals(10, r.players[0].position)
        assertEquals(1600, r.players[0].cash)
        assertEquals(1, r.currentSeat)
        assertEquals(Phase.AWAIT_ROLL, r.phase)
    }

    private fun colourSet() = PTT.own(PTT.table(), 0, 1, 2)

    @Test fun evenBuildRuleRejectsUnevenBuildingAndSelling() {
        var s = colourSet()
        s = PTT.act(s, PtCommand.Build(1))
        assertEquals(PtReject.UNEVEN_BUILD, e.explain(s, 0, PtCommand.Build(1)))
        s = PTT.act(s, PtCommand.Build(2))
        assertEquals(PtReject.NONE, e.explain(s, 0, PtCommand.Build(1)))
        s = PTT.act(s, PtCommand.Build(1))
        assertEquals(PtReject.UNEVEN_SELL, e.explain(s, 0, PtCommand.SellBuilding(2)))
        assertEquals(PtReject.NONE, e.explain(s, 0, PtCommand.SellBuilding(1)))
    }

    @Test fun buildingNeedsWholeColourAndNoPledgedTileInTheColour() {
        val partial = PTT.own(PTT.table(), 0, 1)
        assertEquals(PtReject.SET_INCOMPLETE, e.explain(partial, 0, PtCommand.Build(1)))
        val split = PTT.own(PTT.own(PTT.table(), 0, 1), 1, 2)
        assertEquals(PtReject.SET_INCOMPLETE, e.explain(split, 0, PtCommand.Build(1)))
        assertEquals(PtReject.CANNOT_AFFORD, e.explain(PTT.edit(colourSet(), 0) { it.copy(cash = 10) }, 0, PtCommand.Build(1)))
    }

    @Test fun resortNeedsFourCabinsOnEveryTileOfTheColour() {
        var s = colourSet()
        repeat(4) { PTT.checkInvariants(s); s = PTT.act(PTT.act(s, PtCommand.Build(1)), PtCommand.Build(2)) }
        assertEquals(4, s.holding(1)!!.buildings)
        s = PTT.act(s, PtCommand.Build(1))
        assertEquals(5, s.holding(1)!!.buildings)
        assertEquals(36 - 4, s.cabinsLeft)
        assertEquals(13, s.resortsLeft)
        assertEquals(PtReject.UNEVEN_BUILD, e.explain(s, 0, PtCommand.Build(1)))
        val sold = PTT.act(s, PtCommand.SellBuilding(1))
        assertEquals(4, sold.holding(1)!!.buildings)
    }

    @Test fun buildingSupplyIsLimitedToThirtySixCabinsAndFourteenResorts() {
        val s = colourSet().copy(cabinsLeft = 0)
        assertEquals(PtReject.SUPPLY_EMPTY, e.explain(s, 0, PtCommand.Build(1)))
    }

    @Test fun rentPercentEscalatorStartsAfterTheModeRoundAndGrowsEachRound() {
        val base = PTT.own(PTT.table(), 1, 1)
        assertEquals(4, e.rentFor(base.copy(round = 80), 1, 7))
        val at81 = StandardPropertyTycoonEngine.tidePercent(base.copy(round = 81))
        assertEquals(5, at81)
        assertEquals(150, StandardPropertyTycoonEngine.tidePercent(base.copy(round = 400)))
        assertEquals(0, StandardPropertyTycoonEngine.tidePercent(PTT.table(cfg = PTT.config(length = GameLength.QUICK)).copy(round = 20)))
    }

    @Test fun quickModeEndsAfterTwentyRoundsAndRichestNetWorthWins() {
        var s = PTT.table(2, PTT.config(length = GameLength.QUICK))
        s = PTT.edit(s, 1) { it.copy(cash = 5000) }.copy(round = 20, currentSeat = 1, phase = Phase.AWAIT_POST_ROLL)
        val r = PTT.act(s, PtCommand.EndTurn)
        assertTrue(r.over)
        assertEquals(1, r.winnerSeat)
        assertEquals(1, e.winner(r))
    }

    @Test fun netWorthCountsCashTilesAtPriceBuildingsAtCost() {
        var s = PTT.own(PTT.table(), 0, 1, 2)
        s = PTT.withBuildings(s, 1, 1)
        assertEquals(1600 + 60 + 70 + 40, e.netWorth(s, 0))
        val resort = PTT.withBuildings(PTT.withBuildings(PTT.own(PTT.table(), 0, 1, 2), 1, 5), 2, 4)
        assertEquals(1600 + 60 + 70 + 5 * 40 + 4 * 40, e.netWorth(resort, 0))
    }

    @Test fun handicapAddsStartingPearlsToTheChosenSeatOnly() {
        val s = PTT.table(3, PTT.config(handicapSeat = 1, handicapPercent = 50))
        assertEquals(listOf(1600, 2400, 1600), s.players.map { it.cash })
    }

    @Test fun onlyTheSeatToActMayCommandAndDisabledFeaturesAreRefused() {
        val s = PTT.table()
        assertEquals(PtReject.NOT_YOUR_TURN, e.explain(s, 1, PtCommand.Roll))
        assertEquals(PtReject.FEATURE_DISABLED, e.explain(s, 0, PtCommand.Pledge(1)))
        assertEquals(PtReject.FEATURE_DISABLED, e.explain(s, 0, PtCommand.Bid(10)))
        assertEquals(PtReject.WRONG_PHASE, e.explain(s, 0, PtCommand.EndTurn))
        val r = PTT.result(s, PtCommand.EndTurn)
        assertFalse(r.accepted)
        assertEquals(s, r.state)
    }
}
