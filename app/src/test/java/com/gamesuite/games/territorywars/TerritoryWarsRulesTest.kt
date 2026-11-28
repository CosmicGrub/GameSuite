package com.gamesuite.games.territorywars

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TerritoryWarsRulesTest {
    private fun e() = TWT.seeded(1)

    private fun rejected(r: ActionResult) = (r as ActionResult.Rejected).reason

    @Test fun dealGivesEveryTerritoryOneOwnerAndOneArmy() {
        val e = e()
        val s = e.newGame(TWT.players(3), TWT.config(n = 3))
        assertTrue(s.owners.all { it in 0..2 })
        assertTrue(s.armies.all { it == 1 })
        assertEquals(36, s.owners.size)
    }

    @Test fun dealSharesTerritoriesWithinOneForEverySeatCount() {
        for (n in 2..6) {
            val s = e().newGame(TWT.players(n), TWT.config(n = n, seed = n.toLong()))
            val counts = (0 until n).map { s.territoriesOf(it) }
            assertTrue("$n seats: $counts", counts.max() - counts.min() <= 1)
        }
    }

    @Test fun startingArmiesFollowTheTable() {
        val table = mapOf(2 to 30, 3 to 26, 4 to 22, 5 to 18, 6 to 16)
        for ((n, total) in table) {
            val s = e().newGame(TWT.players(n), TWT.config(n = n, seed = 3))
            for (seat in 0 until n) assertEquals("$n seats seat $seat", total, s.territoriesOf(seat) + s.initialPools[seat])
        }
        assertEquals(14, TerritoryWarsRules.startingArmies(18, 3))
        val h = e().newGame(TWT.players(3), TWT.config(n = 3, handicap = listOf(6, 0, 0)))
        assertEquals(26 + 6, h.territoriesOf(0) + h.initialPools[0])
    }

    @Test fun openingPlacementGoesInBatchesOfThreeInSeatOrder() {
        val e = e()
        var s = e.newGame(TWT.players(3), TWT.config(n = 3))
        assertEquals(TurnPhase.PLACE_INITIAL, s.phase)
        val order = ArrayList<Int>()
        var guard = 0
        while (s.phase == TurnPhase.PLACE_INITIAL && guard++ < 200) {
            order += s.currentSeat
            val own = s.owners.indices.first { s.owners[it] == s.currentSeat }
            val batch = s.armiesToPlace
            assertTrue(batch in 1..3)
            s = TWT.act(e, s, TerritoryWarsAction.PlaceArmies(own, batch))
        }
        assertEquals(TurnPhase.REINFORCE, s.phase)
        assertTrue(s.initialPools.all { it == 0 })
        assertTrue(order.take(3).toSet().size == 3)
        assertEquals(order.take(3), order.take(3).sortedBy { (it - order[0] + 3) % 3 })
    }

    @Test fun autoPlaceFillsEveryPoolAndStartsTheFirstTurn() {
        val s = TWT.started(4, 8)
        assertEquals(TurnPhase.REINFORCE, s.phase)
        for (seat in 0 until 4) assertEquals(22, s.armiesOf(seat))
        assertEquals(1, s.round)
    }

    @Test fun reinforcementIsMaxOfThreeAndTerritoriesOverThree() {
        val e = e()
        val s = TWT.started(3, 2, e = e)
        val owners = MutableList(36) { 1 }
        for (i in 0 until 8) owners[i] = 0
        val st = s.copy(owners = owners)
        assertEquals(3, e.reinforcement(st, 0).territories)
        val big = st.copy(owners = MutableList(36) { if (it < 15) 0 else 1 })
        assertEquals(5, e.reinforcement(big, 0).territories)
    }

    @Test fun reinforcementAddsEveryHeldRegionBonus() {
        val e = e()
        val s = TWT.started(2, 2, e = e)
        val map = TWT.mapOf(s)
        val region = map.regions.first { it.id == "heartmark" }
        val owners = MutableList(36) { 1 }
        for (id in region.territoryIds) owners[map.indexOf(id)] = 0
        val r = e.reinforcement(s.copy(owners = owners), 0)
        assertEquals(region.bonus, r.regions)
        assertEquals(5, region.bonus)
        assertEquals(3, r.territories)
    }

    @Test fun cannotAttackBeforeAllArmiesArePlaced() {
        val e = e()
        val s = TWT.started(3, 4, e = e)
        val map = TWT.mapOf(s)
        val from = s.owners.indices.first { s.owners[it] == s.currentSeat && s.armies[it] >= 2 && map.neighbourIndices(it).any { n -> s.owners[n] != s.currentSeat } }
        val to = map.neighbourIndices(from).first { s.owners[it] != s.currentSeat }
        assertEquals(RejectReason.PLACE_FIRST, rejected(e.apply(s, TerritoryWarsAction.Attack(from, to, 1))))
        assertEquals(RejectReason.PLACE_FIRST, rejected(e.apply(s, TerritoryWarsAction.EndAttack)))
    }

    @Test fun attackNeedsTwoArmiesAnAdjacentEnemyAndYourOwnSource() {
        val e = TWT.engine(6, 1)
        val map = TerritoryWarsMaps.STANDARD
        val (a, b) = TWT.adjacentPair(map)
        val s = TWT.duel(e, a, 1, b, 3)
        assertEquals(RejectReason.NEED_TWO_ARMIES, rejected(e.apply(s, TerritoryWarsAction.Attack(a, b, 1))))
        val s2 = TWT.duel(e, a, 4, b, 3)
        assertEquals(RejectReason.NOT_YOURS, rejected(e.apply(s2, TerritoryWarsAction.Attack(b, a, 1))))
        val own = s2.copy(owners = s2.owners.toMutableList().also { it[b] = 0 })
        assertEquals(RejectReason.OWN_TARGET, rejected(e.apply(own, TerritoryWarsAction.Attack(a, b, 1))))
        val far = (0 until 36).first { it != a && it !in map.neighbourIndices(a) }
        val s3 = s2.copy(owners = s2.owners.toMutableList().also { it[far] = 1 })
        assertEquals(RejectReason.NOT_ADJACENT, rejected(e.apply(s3, TerritoryWarsAction.Attack(a, far, 1))))
    }

    @Test fun attackerDiceAreCappedAtThreeAndAtArmiesMinusOne() {
        val e = TWT.engine(6, 6, 6, 1, 1)
        val (a, b) = TWT.adjacentPair(TerritoryWarsMaps.STANDARD)
        val s = TWT.duel(e, a, 10, b, 5)
        assertEquals(RejectReason.BAD_DICE, rejected(e.apply(s, TerritoryWarsAction.Attack(a, b, 4))))
        val two = TWT.duel(e, a, 2, b, 5)
        assertEquals(RejectReason.BAD_DICE, rejected(e.apply(two, TerritoryWarsAction.Attack(a, b, 2))))
        val ok = TWT.accepted(e.apply(s, TerritoryWarsAction.Attack(a, b, 3)))
        assertEquals(2, ok.events.first().extra)
        assertEquals(5 - 2, ok.state.armies[b])
    }

    @Test fun defenderAlwaysRollsTheMaximumDice() {
        val e = TWT.engine(6, 1, 1)
        val (a, b) = TWT.adjacentPair(TerritoryWarsMaps.STANDARD)
        val s = TWT.duel(e, a, 5, b, 4)
        val r = TWT.accepted(e.apply(s, TerritoryWarsAction.Attack(a, b, 1)))
        // attacker die 6 beats the defender's higher die 1: two defender dice were drawn (6, then 1, 1)
        assertEquals(1, r.events.first().extra)
        assertEquals(3, r.state.armies[b])
        val oneArmy = TWT.engine(6, 1)
        val s1 = TWT.duel(oneArmy, a, 5, b, 1)
        TWT.accepted(oneArmy.apply(s1, TerritoryWarsAction.Attack(a, b, 1)))
    }

    private fun conquest(): Triple<StandardTerritoryWarsEngine, TerritoryWarsState, Pair<Int, Int>> {
        val e = TWT.engine(6, 6, 6, 1)
        val (a, b) = TWT.adjacentPair(TerritoryWarsMaps.STANDARD)
        val s = TWT.duel(e, a, 6, b, 1)
        return Triple(e, TWT.act(e, s, TerritoryWarsAction.Attack(a, b, 3)), a to b)
    }

    @Test fun conquestMovesInAtLeastTheDiceRolledAndLeavesOneBehind() {
        val (e, s, p) = conquest()
        assertEquals(TurnPhase.MOVE_IN, s.phase)
        val m = s.pendingMoveIn!!
        assertEquals(3, m.minMove)
        assertEquals(5, m.maxMove)
        assertEquals(RejectReason.BAD_COUNT, rejected(e.apply(s, TerritoryWarsAction.MoveIn(2))))
        assertEquals(RejectReason.BAD_COUNT, rejected(e.apply(s, TerritoryWarsAction.MoveIn(6))))
        val done = TWT.act(e, s, TerritoryWarsAction.MoveIn(4))
        assertEquals(TurnPhase.ATTACK, done.phase)
        assertEquals(4, done.armies[p.second]); assertEquals(2, done.armies[p.first])
        assertEquals(0, done.owners[p.second])
    }

    @Test fun conquestWithoutMoveInBlocksEveryOtherAction() {
        val (e, s, p) = conquest()
        assertEquals(RejectReason.MOVE_IN_FIRST, rejected(e.apply(s, TerritoryWarsAction.Attack(p.first, p.second, 1))))
        assertEquals(RejectReason.WRONG_PHASE, rejected(e.apply(s, TerritoryWarsAction.EndAttack)))
        assertEquals(RejectReason.WRONG_PHASE, rejected(e.apply(s, TerritoryWarsAction.SkipFortify)))
    }

    @Test fun attackUntilDoneStopsOnConquestOrAtOneArmy() {
        val e = TWT.engine(*IntArray(40) { if (it % 3 == 2) 1 else 6 })
        val (a, b) = TWT.adjacentPair(TerritoryWarsMaps.STANDARD)
        val s = TWT.duel(e, a, 4, b, 2)
        val r = TWT.accepted(e.apply(s, TerritoryWarsAction.AttackUntilDone(a, b)))
        assertTrue(r.state.phase == TurnPhase.MOVE_IN || r.state.armies[a] == 1)
        val lose = TWT.engine(*IntArray(60) { if (it % 5 < 3) 1 else 6 })
        val s2 = TWT.duel(lose, a, 3, b, 30)
        val r2 = TWT.accepted(lose.apply(s2, TerritoryWarsAction.AttackUntilDone(a, b)))
        assertEquals(1, r2.state.armies[a])
        assertEquals(TurnPhase.ATTACK, r2.state.phase)
    }

    @Test fun firstConquestOfTheTurnEarnsExactlyOneCard() {
        val (e, s, _) = conquest()
        val moved = TWT.act(e, s, TerritoryWarsAction.MoveIn(3))
        val fort = TWT.act(e, moved, TerritoryWarsAction.EndAttack)
        val end = TWT.act(e, fort, TerritoryWarsAction.SkipFortify)
        assertEquals(1, end.hands[0].size)
        assertEquals(s.deck.size - 1, end.deck.size)
    }

    @Test fun noConquestEarnsNoCard() {
        val e = e()
        val s = TWT.started(3, 6, e = e)
        val pool = s.copy(armiesToPlace = 0)
        val end = TWT.act(e, TWT.act(e, pool, TerritoryWarsAction.EndAttack), TerritoryWarsAction.SkipFortify)
        assertTrue(end.hands.all { it.isEmpty() })
    }

    @Test fun fortifyConnectedMovesAlongAnyOwnChainIncludingSeaLinks() {
        val e = e()
        val map = TerritoryWarsMaps.STANDARD
        val s0 = TWT.started(2, 1, e = e)
        val chain = listOf(0) + map.neighbourIndices(0).take(1)
        val far = map.neighbourIndices(chain.last()).first { it !in chain }
        val owners = MutableList(36) { 1 }
        for (i in chain + far) owners[i] = 0
        val armies = MutableList(36) { 1 }.also { it[0] = 5 }
        val s = s0.copy(owners = owners, armies = armies, phase = TurnPhase.FORTIFY, armiesToPlace = 0, currentSeat = 0)
        val r = TWT.accepted(e.apply(s, TerritoryWarsAction.Fortify(0, far, 3)))
        assertEquals(3, r.state.armies[far]); assertEquals(2, r.state.armies[0])
        assertEquals(1, r.state.currentSeat)
        val noPath = s.copy(owners = owners.toMutableList().also { it[chain.last()] = 1 })
        assertEquals(RejectReason.NO_PATH, rejected(e.apply(noPath, TerritoryWarsAction.Fortify(0, far, 1))))
    }

    @Test fun fortifyAdjacentOnlyAllowsANeighbour() {
        val e = TWT.engine()
        val map = TerritoryWarsMaps.STANDARD
        val cfg = TWT.config(n = 2, auto = true, fortify = FortifyReach.ADJACENT)
        val s0 = e.newGame(TWT.players(2), cfg)
        val chain = listOf(0, map.neighbourIndices(0).first())
        val far = map.neighbourIndices(chain.last()).first { it !in chain }
        val owners = MutableList(36) { 1 }
        for (i in chain + far) owners[i] = 0
        val s = s0.copy(owners = owners, armies = MutableList(36) { 3 }, phase = TurnPhase.FORTIFY, armiesToPlace = 0, currentSeat = 0)
        assertEquals(RejectReason.NO_PATH, rejected(e.apply(s, TerritoryWarsAction.Fortify(0, far, 1))))
        TWT.accepted(e.apply(s, TerritoryWarsAction.Fortify(0, chain.last(), 1)))
    }

    @Test fun fortifyLeavesAtLeastOneArmyAndHappensOncePerTurn() {
        val e = e()
        val map = TerritoryWarsMaps.STANDARD
        val s0 = TWT.started(2, 1, e = e)
        val nb = map.neighbourIndices(0).first()
        val owners = MutableList(36) { 1 }.also { it[0] = 0; it[nb] = 0 }
        val s = s0.copy(owners = owners, armies = MutableList(36) { 1 }.also { it[0] = 4 }, phase = TurnPhase.FORTIFY, armiesToPlace = 0, currentSeat = 0)
        assertEquals(RejectReason.LEAVE_ONE, rejected(e.apply(s, TerritoryWarsAction.Fortify(0, nb, 4))))
        assertEquals(RejectReason.BAD_COUNT, rejected(e.apply(s, TerritoryWarsAction.Fortify(0, nb, 0))))
        val done = TWT.accepted(e.apply(s, TerritoryWarsAction.Fortify(0, nb, 3)))
        assertEquals(1, done.state.currentSeat)
    }

    @Test fun skipFortifyEndsTheTurnAndPassesToTheNextLivingSeat() {
        val e = e()
        var s = TWT.started(3, 2, e = e)
        s = s.copy(armiesToPlace = 0, eliminated = listOf(false, true, false), currentSeat = 0)
        s = s.copy(owners = s.owners.map { if (it == 1) 2 else it })
        s = TWT.act(e, TWT.act(e, s, TerritoryWarsAction.EndAttack), TerritoryWarsAction.SkipFortify)
        assertEquals(2, s.currentSeat)
    }

    @Test fun roundCounterAdvancesAfterTheLastLivingSeat() {
        val e = e()
        var s = TWT.started(3, 2, e = e)
        assertEquals(1, s.round)
        repeat(3) { s = TWT.act(e, TWT.act(e, s.copy(armiesToPlace = 0), TerritoryWarsAction.EndAttack), TerritoryWarsAction.SkipFortify) }
        assertEquals(2, s.round)
    }

    @Test fun holdingFiveCardsForcesATurnInAtTurnStart() {
        val e = e()
        val s = TWT.started(2, 3, e = e)
        val deck = s.deck
        val hand = deck.take(5)
        val next = s.copy(hands = listOf(emptyList(), hand), deck = deck.drop(5), currentSeat = 0, armiesToPlace = 0)
        val end = TWT.act(e, TWT.act(e, next, TerritoryWarsAction.EndAttack), TerritoryWarsAction.SkipFortify)
        assertEquals(TurnPhase.CARDS_FORCED, end.phase)
        assertEquals(RejectReason.TURN_IN_FIRST, rejected(e.apply(end, TerritoryWarsAction.AutoPlaceRemaining)))
        val set = e.legalActions(end).filterIsInstance<TerritoryWarsAction.TurnInCards>().first()
        val after = TWT.act(e, end, set)
        assertEquals(2, after.hands[1].size.let { 5 - 3 })
        assertEquals(TurnPhase.REINFORCE, after.phase)
        assertEquals(3 + reinforcementBase(after), after.armiesToPlace)
    }

    private fun reinforcementBase(s: TerritoryWarsState): Int = StandardTerritoryWarsEngine().reinforcement(s, s.currentSeat).total

    @Test fun eliminationTransfersEveryCardToTheEliminator() {
        val e = TWT.engine(6, 6, 6, 1)
        val (a, b) = TWT.adjacentPair(TerritoryWarsMaps.STANDARD)
        var s = TWT.duel(e, a, 6, b, 1, n = 2)
        s = s.copy(owners = MutableList(36) { 0 }.also { it[b] = 1 }, hands = listOf(emptyList(), s.deck.take(2)), deck = s.deck.drop(2))
        val r = TWT.accepted(e.apply(s, TerritoryWarsAction.Attack(a, b, 3)))
        assertTrue(r.state.eliminated[1])
        assertEquals(2, r.state.hands[0].size)
        assertTrue(r.state.hands[1].isEmpty())
        assertEquals(TerritoryWarsEventKind.WAR_WON, r.events.last().kind)
        assertEquals(TurnPhase.GAME_OVER, r.state.phase)
        assertEquals(WinReason.DOMINATION, r.state.winReason)
    }

    @Test fun captureTargetWinsTheMomentTheTerritoryCountIsReached() {
        val e = TWT.engine(6, 6, 6, 1)
        val (a, b) = TWT.adjacentPair(TerritoryWarsMaps.STANDARD)
        var s = TWT.duel(e, a, 6, b, 1, n = 3)
        val owners = MutableList(36) { 2 }
        for (i in 0 until 25) owners[i] = 0
        owners[b] = 1
        if (owners[a] != 0) owners[a] = 0
        val cnt = owners.count { it == 0 }
        s = s.copy(owners = owners, config = s.config.copy(captureTarget = cnt + 1))
        val r = TWT.accepted(e.apply(s, TerritoryWarsAction.Attack(a, b, 3)))
        assertEquals(TurnPhase.GAME_OVER, r.state.phase)
        assertEquals(WinReason.CAPTURE_TARGET, r.state.winReason)
        assertEquals(listOf(0), r.state.winnerSeats)
    }

    @Test fun roundCapEndsOnPointsWithTheTieBreakChain() {
        val e = e()
        var s = TWT.started(2, 3, e = e)
        val owners = MutableList(36) { 1 }.also { for (i in 0 until 20) it[i] = 0 }
        s = s.copy(owners = owners, armies = MutableList(36) { 2 }, round = s.config.roundCap, currentSeat = 1, armiesToPlace = 0)
        val end = TWT.act(e, TWT.act(e, s, TerritoryWarsAction.EndAttack), TerritoryWarsAction.SkipFortify)
        assertEquals(TurnPhase.GAME_OVER, end.phase)
        assertEquals(WinReason.ROUND_CAP, end.winReason)
        assertEquals(listOf(0), end.winnerSeats)
        val tie = s.copy(owners = MutableList(36) { it % 2 }, armies = MutableList(36) { 2 })
        val t = TWT.act(e, TWT.act(e, tie, TerritoryWarsAction.EndAttack), TerritoryWarsAction.SkipFortify)
        assertEquals(listOf(0, 1), t.winnerSeats)
    }

    @Test fun fiveRoundsWithoutAConquestEndTheWarAsAStalemate() {
        val e = e()
        var s = TWT.started(2, 3, e = e)
        var rounds = 0
        while (s.phase != TurnPhase.GAME_OVER && rounds++ < 40) s = TWT.act(e, TWT.act(e, s.copy(armiesToPlace = 0), TerritoryWarsAction.EndAttack), TerritoryWarsAction.SkipFortify)
        assertEquals(TurnPhase.GAME_OVER, s.phase)
        assertEquals(WinReason.STALEMATE, s.winReason)
        assertTrue(s.round <= 6)
    }

    @Test fun rejectedActionLeavesTheStateUnchangedAndActionsAfterGameOverAreRejected() {
        val e = e()
        val s = TWT.started(3, 1, e = e)
        assertTrue(e.apply(s, TerritoryWarsAction.SkipFortify) is ActionResult.Rejected)
        val over = s.copy(phase = TurnPhase.GAME_OVER)
        assertEquals(RejectReason.GAME_OVER, rejected(e.apply(over, TerritoryWarsAction.EndAttack)))
        assertTrue(e.legalActions(over).isEmpty())
    }

    @Test fun sameSeedAndSameActionsGiveTheSameWar() {
        fun run(): TerritoryWarsState {
            val e = TWT.seeded(77)
            return TWT.playRandom(e, e.newGame(TWT.players(3), TWT.config(n = 3, seed = 77, auto = true)), Random(4)).first
        }
        assertEquals(run(), run())
    }

    @Test fun randomLegalPlayNeverThrowsAndAlwaysTerminates() {
        val rnd = Random(12)
        repeat(12) { g ->
            val e = TWT.seeded(g.toLong())
            val n = 2 + g % 5
            val len = WarLength.values()[g % 3]
            val s0 = e.newGame(TWT.players(n), TWT.config(len, n, g.toLong(), auto = g % 2 == 0))
            val (end, _) = TWT.playRandom(e, s0, rnd, cap = 60000)
            assertEquals(TurnPhase.GAME_OVER, end.phase)
            assertTrue(end.winnerSeats.isNotEmpty())
        }
    }

    @Test fun armyAndCardConservationHoldsAfterEveryAction() {
        val e = TWT.seeded(3)
        TWT.playRandom(e, e.newGame(TWT.players(4), TWT.config(n = 4, seed = 3)), Random(3)) { TWT.checkInvariants(it) }
    }

    @Test fun setValueFormulaMatchesTheEscalationTable() {
        val expect = listOf(3, 5, 7, 9, 12, 15, 18, 22, 26, 30)
        assertEquals(expect, (0..9).map { TerritoryWarsRules.setValue(it) })
        assertFalse(TerritoryWarsRules.setValue(0) == TerritoryWarsRules.setValue(1))
    }
}
