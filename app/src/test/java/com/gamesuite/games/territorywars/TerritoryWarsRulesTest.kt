package com.gamesuite.games.territorywars

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD test stubs: Engine rules, scripted full turns (doc 2.1 rules 1-24 and the state machine 2.2).
 * Every test is @Ignore until the engine exists, so the gate stays green. Each name is a test-plan item
 * (docs/design/boardgames/territory-wars.md section 3.10); un-ignore as the phase that owns it lands.
 */
class TerritoryWarsRulesTest {
    @Ignore("scaffold: after deal: owners set, armies == 1 everywhere")
    @Test fun dealGivesEveryTerritoryOneOwnerAndOneArmy() {}

    @Ignore("scaffold: 2..6 seats on 36 and 2..4 on 18: max - min <= 1")
    @Test fun dealSharesTerritoriesWithinOneForEverySeatCount() {}

    @Ignore("scaffold: 30/26/22/18/16 and 16/14/12 totals, handicap added on top")
    @Test fun startingArmiesFollowTheTable() {}

    @Ignore("scaffold: PLACE_INITIAL cycles seats, 3 armies per visit")
    @Test fun openingPlacementGoesInBatchesOfThreeInSeatOrder() {}

    @Ignore("scaffold: Auto-place and Blitz skip PLACE_INITIAL")
    @Test fun autoPlaceFillsEveryPoolAndStartsTheFirstTurn() {}

    @Ignore("scaffold: 8 -> 3, 12 -> 4, 36 -> 12")
    @Test fun reinforcementIsMaxOfThreeAndTerritoriesOverThree() {}

    @Ignore("scaffold: holding Heartmark adds 5")
    @Test fun reinforcementAddsEveryHeldRegionBonus() {}

    @Ignore("scaffold: PLACE_FIRST rejection")
    @Test fun cannotAttackBeforeAllArmiesArePlaced() {}

    @Ignore("scaffold: NEED_TWO_ARMIES, NOT_ADJACENT, NOT_YOURS, OWN_TARGET")
    @Test fun attackNeedsTwoArmiesAnAdjacentEnemyAndYourOwnSource() {}

    @Ignore("scaffold: BAD_DICE for 4 dice or dice >= armies")
    @Test fun attackerDiceAreCappedAtThreeAndAtArmiesMinusOne() {}

    @Ignore("scaffold: min(2, defender armies)")
    @Test fun defenderAlwaysRollsTheMaximumDice() {}

    @Ignore("scaffold: min = last dice, max = armies - 1")
    @Test fun conquestMovesInAtLeastTheDiceRolledAndLeavesOneBehind() {}

    @Ignore("scaffold: MOVE_IN_FIRST")
    @Test fun conquestWithoutMoveInBlocksEveryOtherAction() {}

    @Ignore("scaffold: blitz action with scripted dice")
    @Test fun attackUntilDoneStopsOnConquestOrAtOneArmy() {}

    @Ignore("scaffold: two conquests, one card")
    @Test fun firstConquestOfTheTurnEarnsExactlyOneCard() {}

    @Ignore("scaffold: no card at turn end")
    @Test fun noConquestEarnsNoCard() {}

    @Ignore("scaffold: path rule, BFS through own territories")
    @Test fun fortifyConnectedMovesAlongAnyOwnChainIncludingSeaLinks() {}

    @Ignore("scaffold: FortifyReach.ADJACENT")
    @Test fun fortifyAdjacentOnlyAllowsANeighbour() {}

    @Ignore("scaffold: LEAVE_ONE, ALREADY_FORTIFIED")
    @Test fun fortifyLeavesAtLeastOneArmyAndHappensOncePerTurn() {}

    @Ignore("scaffold: eliminated seats are skipped")
    @Test fun skipFortifyEndsTheTurnAndPassesToTheNextLivingSeat() {}

    @Ignore("scaffold: round increments once per full cycle")
    @Test fun roundCounterAdvancesAfterTheLastLivingSeat() {}

    @Ignore("scaffold: hands merge, eliminated seat out")
    @Test fun eliminationTransfersEveryCardToTheEliminator() {}

    @Ignore("scaffold: CARDS_FORCED mid-attack, attack resumes")
    @Test fun eliminatorOverFourCardsMustTurnInBeforeAnyFurtherAttack() {}

    @Ignore("scaffold: CARDS_FORCED before REINFORCE")
    @Test fun holdingFiveCardsForcesATurnInAtTurnStart() {}

    @Ignore("scaffold: winReason DOMINATION")
    @Test fun lastPlayerStandingWinsByDomination() {}

    @Ignore("scaffold: 26 of 36, 12 of 18, 24 of 36 in Blitz with 5+ seats")
    @Test fun captureTargetWinsTheMomentTheTerritoryCountIsReached() {}

    @Ignore("scaffold: territories, armies, cards, then shared win")
    @Test fun roundCapEndsOnPointsWithTheTieBreakChain() {}

    @Ignore("scaffold: STALEMATE_ROUNDS rule")
    @Test fun fiveRoundsWithoutAConquestEndTheWarAsAStalemate() {}

    @Ignore("scaffold: structural equality before and after, for every RejectReason")
    @Test fun rejectedActionLeavesTheStateUnchanged() {}

    @Ignore("scaffold: GAME_OVER")
    @Test fun actionsAfterGameOverAreRejected() {}

    @Ignore("scaffold: determinism: replay a recorded action list")
    @Test fun sameSeedAndSameActionsGiveTheSameWar() {}

    @Ignore("scaffold: fuzz: 2,000 random-legal wars, bounded length")
    @Test fun randomLegalPlayNeverThrowsAndAlwaysTerminates() {}

    @Ignore("scaffold: armies on map + pools consistent; cards in hands+deck+discard == deck size")
    @Test fun armyAndCardConservationHoldsAfterEveryAction() {}

    @Ignore("scaffold: TerritoryWarsRules.setValue and baseReinforcement known values")
    @Test fun setValueFormulaMatchesTheEscalationTable() {}
}
