package com.gamesuite.games.carrom

import org.junit.Assert.*
import org.junit.Test

/** Rules with known values, by feeding crafted simulation results to [CarromRules.resolve]. */
class CarromRulesTest {
    private val players = listOf(
        CarromPlayerState("a", "A", false, 0, 0),
        CarromPlayerState("b", "B", false, 1, 2)
    )
    private val shot = CarromShot(500, 0, 500)

    private fun state(coins: List<CarromCoin>, seat: Int = 0, queenOwner: Int = -1, queenPending: Int = -1) =
        CarromState(players, coins, seat, queenOwner = queenOwner, queenPending = queenPending)

    private fun sim(coins: List<CarromCoin>, pocketed: List<Int> = emptyList(), striker: Boolean = false) =
        CarromSimResult(coins, pocketed, striker, 10, emptyList(), IntArray(0), emptyList())

    private val full = CarromBoard.openingCoins()
    private fun without(vararg ids: Int) = full.filter { it.id !in ids }

    @Test fun pocketingYourOwnCoinKeepsTheTurn() {
        val r = CarromRules.resolve(state(full), shot, sim(without(0), listOf(0)), 0)
        assertEquals(0, r.state.currentPlayerIndex)
        assertTrue(r.state.flags and CarromFlag.OWN != 0)
        assertEquals(1, r.state.pocketed(0))
    }

    @Test fun missingPassesTheTurn() {
        val r = CarromRules.resolve(state(full), shot, sim(full), 0)
        assertEquals(1, r.state.currentPlayerIndex)
    }

    @Test fun pocketingTheOpponentsCoinCountsForThemAndEndsTheTurn() {
        val r = CarromRules.resolve(state(full), shot, sim(without(9), listOf(9)), 0)
        assertEquals(1, r.state.currentPlayerIndex)
        assertEquals(1, r.state.pocketed(1))
        assertEquals(0, r.state.pocketed(0))
        assertTrue(r.state.flags and CarromFlag.OPPONENT != 0)
    }

    @Test fun theQueenMustBeCoveredOnTheSameFlick() {
        val r = CarromRules.resolve(state(full), shot, sim(without(18, 1), listOf(18, 1)), 0)
        assertEquals(0, r.state.queenOwner)
        assertEquals(-1, r.state.queenPending)
        assertTrue(r.state.flags and CarromFlag.COVERED != 0)
        assertEquals(0, r.state.currentPlayerIndex)
    }

    @Test fun theQueenAloneGivesACoverFlickAndThenComesBackIfMissed() {
        val first = CarromRules.resolve(state(full), shot, sim(without(18), listOf(18)), 0)
        assertEquals(0, first.state.queenPending)
        assertEquals(0, first.state.currentPlayerIndex) // the cover flick
        val missed = CarromRules.resolve(first.state, shot, sim(first.state.coins), 0)
        assertEquals(-1, missed.state.queenPending)
        assertEquals(-1, missed.state.queenOwner)
        assertTrue(missed.state.coins.any { it.id == 18 })
        assertTrue(missed.state.flags and CarromFlag.QUEEN_BACK != 0)
        assertEquals(1, missed.state.currentPlayerIndex)
    }

    @Test fun theCoverFlickCoversWhenAnOwnCoinGoesIn() {
        val first = CarromRules.resolve(state(full), shot, sim(without(18), listOf(18)), 0)
        val covered = CarromRules.resolve(first.state, shot, sim(first.state.coins.filter { it.id != 2 }, listOf(2)), 0)
        assertEquals(0, covered.state.queenOwner)
        assertEquals(0, covered.state.currentPlayerIndex)
    }

    @Test fun aPocketedStrikerIsAFoulThatReturnsCoinsAndEndsTheTurn() {
        // white already has coin 3 in; this flick pockets coin 0 and the striker
        val base = state(without(3))
        val r = CarromRules.resolve(base, shot, sim(without(3, 0), listOf(0), striker = true), 0)
        assertTrue(r.state.flags and CarromFlag.FOUL != 0)
        assertEquals(1, r.state.currentPlayerIndex)
        assertTrue("coin 0 returns", r.state.coins.any { it.id == 0 })
        assertTrue("penalty coin 3 returns", r.state.coins.any { it.id == 3 })
        assertEquals(0, r.state.pocketed(0))
    }

    @Test fun theLastCoinCannotGoInBeforeTheQueen() {
        val almost = full.filter { it.id !in 0..7 }
        val r = CarromRules.resolve(state(almost), shot, sim(almost.filter { it.id != 8 }, listOf(8)), 0)
        assertFalse(r.state.boardOver)
        assertTrue(r.state.coins.any { it.id == 8 })
        assertTrue(r.state.flags and CarromFlag.LAST_COIN_BACK != 0)
    }

    @Test fun clearingYourCoinsWithTheQueenWinsAndScoresTheOpponentsCoinsPlusThree() {
        val left = listOf(CarromCoin(0, 200.0, 200.0)) + (9..13).map { CarromCoin(it, 300.0 + (it - 9) * 40, 500.0) }
        val r = CarromRules.resolve(state(left, queenOwner = 0), shot, sim(left.drop(1), listOf(0)), 0)
        assertTrue(r.state.boardOver)
        assertEquals(0, r.state.winnerTeam)
        assertEquals(5 + 3, r.state.winPoints)
        assertEquals(listOf(8, 0), r.state.teamPoints)
    }

    @Test fun winningWithoutTheQueenScoresOnlyTheCoinsLeft() {
        val left = listOf(CarromCoin(0, 200.0, 200.0)) + (9..11).map { CarromCoin(it, 300.0 + (it - 9) * 40, 500.0) }
        val r = CarromRules.resolve(state(left, queenOwner = 1), shot, sim(left.drop(1), listOf(0)), 0)
        assertEquals(0, r.state.winnerTeam)
        assertEquals(3, r.state.winPoints)
    }

    @Test fun theShotLimitEndsTheBoardOnCoinsPocketed() {
        val s = state(without(0, 1)).copy(shotsTaken = CarromBoard.MAX_SHOTS - 1)
        val r = CarromRules.resolve(s, shot, sim(without(0, 1)), 0)
        assertTrue(r.state.boardOver)
        assertEquals(0, r.state.winnerTeam)
        assertEquals(2, r.state.winPoints)
        val tie = CarromRules.resolve(state(full).copy(shotsTaken = CarromBoard.MAX_SHOTS - 1), shot, sim(full), 0)
        assertTrue(tie.state.boardOver); assertEquals(-1, tie.state.winnerTeam)
    }

    @Test fun placementBlockedByACoinIsRejectedAndAFreeOneIsNot() {
        val s = state(full)
        val onLine = state(listOf(CarromCoin(0, 370.0, 635.0)))
        assertEquals(CarromReject.BLOCKED, CarromRules.explain(onLine, 0, CarromShot(500, 0, 500)))
        assertEquals(CarromReject.NONE, CarromRules.explain(onLine, 0, CarromShot(0, 0, 500)))
        assertEquals(CarromReject.NOT_YOUR_TURN, CarromRules.explain(s, 1, shot))
        assertEquals(CarromReject.BAD_SHOT, CarromRules.explain(s, 0, CarromShot(500, 9000, 500)))
        assertEquals(CarromReject.BAD_SHOT, CarromRules.explain(s, 0, CarromShot(500, 0, 0)))
    }

    @Test fun sidesFollowTheSeats() {
        assertEquals(listOf(0, 2), listOf(0, 1).map { CarromRules.sideFor(it, 2) })
        assertEquals(listOf(0, 1, 2, 3), listOf(0, 1, 2, 3).map { CarromRules.sideFor(it, 4) })
        assertTrue(CarromRules.validSeatCount(2) && CarromRules.validSeatCount(4) && CarromRules.validSeatCount(3) && !CarromRules.validSeatCount(5) && !CarromRules.validSeatCount(1))
    }

    @Test fun returnedCoinsFindAFreeSpotNearTheCentre() {
        val crowded = listOf(CarromCoin(1, CarromBoard.CENTER, CarromBoard.CENTER))
        val (x, y) = CarromBoard.freeSpot(crowded)
        assertTrue(Math.hypot(x - CarromBoard.CENTER, y - CarromBoard.CENTER) >= 2 * CarromBoard.COIN_R)
        assertEquals(CarromBoard.CENTER to CarromBoard.CENTER, CarromBoard.freeSpot(emptyList()))
    }
}
