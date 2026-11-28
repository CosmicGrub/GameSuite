package com.gamesuite.games.territorywars

import com.gamesuite.core.CpuLevel
import org.junit.Assert.assertTrue
import kotlin.random.Random

/** Shared helpers of the Territory Wars tests. */
internal object TWT {
    fun players(n: Int, bots: Int = 0) = List(n) { TerritoryWarsPlayer("p$it", "P$it", it >= n - bots, it) }

    fun config(length: WarLength = WarLength.STANDARD, n: Int = 3, seed: Long = 1L, auto: Boolean = false, fortify: FortifyReach = FortifyReach.CONNECTED, handicap: List<Int> = emptyList()) =
        TerritoryWarsRules.configFor(length, n, seed, fortify, auto, handicap)

    fun engine(vararg dice: Int) = StandardTerritoryWarsEngine(StandardBattleResolver(), ScriptedDice(*dice))

    fun seeded(seed: Long = 1L) = StandardTerritoryWarsEngine(StandardBattleResolver(), SeededDice(seed))

    fun mapOf(s: TerritoryWarsState): WarMap = TerritoryWarsMaps.byId(s.mapId)!!

    /** A started, auto-placed war ready for seat 0 to reinforce. */
    fun started(n: Int = 3, seed: Long = 1L, length: WarLength = WarLength.STANDARD, e: TerritoryWarsEngine = seeded(seed)): TerritoryWarsState =
        e.newGame(players(n), config(length, n, seed, auto = true))

    /** The state with seat 0 in the ATTACK phase, owning [mine] (indices) with [armiesMine] armies each; the neighbour [target] is seat 1's with [theirs]. */
    fun duel(e: TerritoryWarsEngine, mine: Int, armiesMine: Int, target: Int, theirs: Int, n: Int = 3): TerritoryWarsState {
        val s = started(n, 5, e = e)
        val owners = s.owners.toMutableList()
        val armies = s.armies.toMutableList()
        owners[mine] = 0; armies[mine] = armiesMine
        owners[target] = 1; armies[target] = theirs
        return s.copy(owners = owners, armies = armies, currentSeat = 0, phase = TurnPhase.ATTACK, armiesToPlace = 0, pendingMoveIn = null, conqueredThisTurn = false, fortifiedThisTurn = false)
    }

    /** Any pair of adjacent territories (from, to). */
    fun adjacentPair(map: WarMap): Pair<Int, Int> {
        val a = 0
        return a to map.neighbourIndices(a).first()
    }

    fun accepted(r: ActionResult): ActionResult.Accepted {
        check(r is ActionResult.Accepted) { "rejected: ${(r as ActionResult.Rejected).reason}" }
        return r
    }

    fun act(e: TerritoryWarsEngine, s: TerritoryWarsState, a: TerritoryWarsAction): TerritoryWarsState = accepted(e.apply(s, a)).state

    fun checkInvariants(s: TerritoryWarsState) {
        val map = mapOf(s)
        assertTrue(s.owners.size == map.size && s.armies.size == map.size)
        for (i in s.owners.indices) {
            assertTrue("owner ${s.owners[i]}", s.owners[i] in 0 until s.seats)
            assertTrue("armies ${s.armies[i]} at $i in ${s.phase}", s.armies[i] >= (if (s.phase == TurnPhase.MOVE_IN && s.pendingMoveIn?.to == i) 0 else 1))
            assertTrue(!s.eliminated[s.owners[i]])
        }
        val cards = s.hands.flatten() + s.deck + s.discard
        assertTrue("cards ${cards.size} vs ${TerritoryWarsCards.buildDeck(map).size}", cards.size == TerritoryWarsCards.buildDeck(map).size)
        assertTrue(cards.map { it.id }.toSet().size == cards.size)
        for (seat in 0 until s.seats) if (!s.eliminated[seat]) assertTrue(s.territoriesOf(seat) > 0)
    }

    fun playRandom(e: TerritoryWarsEngine, s0: TerritoryWarsState, rnd: Random, cap: Int = 20000, check: (TerritoryWarsState) -> Unit = ::checkInvariants): Pair<TerritoryWarsState, Int> {
        var s = s0
        var n = 0
        while (s.phase != TurnPhase.GAME_OVER) {
            val legal = e.legalActions(s)
            assertTrue("stalled in ${s.phase}", legal.isNotEmpty())
            s = act(e, s, legal[rnd.nextInt(legal.size)])
            check(s)
            n++
            assertTrue("no end after $cap actions", n < cap)
        }
        return s to n
    }

    fun playBots(e: TerritoryWarsEngine, s0: TerritoryWarsState, level: (Int) -> CpuLevel, cap: Int = 30000): Pair<TerritoryWarsState, Int> {
        val bot = StandardTerritoryWarsBot(e)
        var s = s0
        var n = 0
        while (s.phase != TurnPhase.GAME_OVER) {
            val lv = level(s.currentSeat)
            val a = bot.chooseAction(s, lv, ThinkBudgets.forLevel(lv), Random(s.config.seed * 31 + s.round * 131L + s.eventSerial)) ?: error("bot had no action in ${s.phase}")
            s = act(e, s, a)
            n++
            assertTrue("no end after $cap actions", n < cap)
        }
        return s to n
    }
}
