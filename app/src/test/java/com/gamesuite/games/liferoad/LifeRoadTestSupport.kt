package com.gamesuite.games.liferoad

import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.random.Random

/** Shared helpers for the Life Road tests: crafted states, scripted seeds and an invariant checker. */
internal object LRT {
    val engine = StandardLifeRoadEngine()

    fun roster(n: Int, bots: Int = 0) = List(n) { PlayerInfo("p$it", "P$it", isBot = it >= n - bots) }

    fun config(
        size: RoadSize = RoadSize.STANDARD, junior: Boolean = false, seed: Long = 1L, catchUp: Boolean = false,
        level: CpuDifficulty = CpuDifficulty.MEDIUM, head: HeadStart = HeadStart.NONE, seats: Set<Int> = emptySet()
    ) = LifeRoadConfig(size, if (junior) RulesVariant.JUNIOR else RulesVariant.STANDARD, head, seats, catchUp, seed, level)

    /** A table with every start road chosen (Learn for all unless [work] lists a seat). */
    fun start(n: Int = 3, cfg: LifeRoadConfig = config(), bots: Int = 0, work: Set<Int> = emptySet()): LifeRoadState {
        var s = engine.newGame(cfg, roster(n, bots))
        while (s.phase == Phase.START_FORK) {
            val branch = if (s.currentSeat in work) "WRK.0" else "LRN.0"
            s = act(s, LifeRoadAction.ChooseBranch(branch))
        }
        return s
    }

    fun act(s: LifeRoadState, a: LifeRoadAction): LifeRoadState {
        val r = engine.apply(s, s.currentSeat, a)
        assertEquals("$a refused: ${r.reject} in ${s.phase}", LifeRoadReject.NONE, r.reject)
        return r.state
    }

    fun step(s: LifeRoadState, a: LifeRoadAction): LifeRoadStep = engine.apply(s, s.currentSeat, a)

    /** The first seed whose spins start with [spins]. */
    fun seedFor(vararg spins: Int): Long {
        var seed = 1L
        while (true) {
            if (spins.indices.all { LifeRoadRng.spin(seed, it.toLong()) == spins[it] }) return seed
            seed++
        }
    }

    fun edit(s: LifeRoadState, seat: Int, f: (PlayerState) -> PlayerState) =
        s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) f(p) else p })

    fun at(s: LifeRoadState, seat: Int, node: String) = edit(s, seat) { it.copy(nodeId = node, branch = null) }

    fun card(s: LifeRoadState, id: String, family: Boolean = false, needsSpin: Boolean = false) =
        s.copy(phase = Phase.AWAIT_CARD, pending = PendingState(cardId = id, cardIsFamily = family, needsSpin = needsSpin))

    fun checkInvariants(s: LifeRoadState) {
        val junior = s.config.junior
        for (p in s.players) {
            assertTrue("cash ${p.cash}", p.cash >= 0)
            assertTrue(p.loans in 0..(if (junior) 0 else 3))
            assertTrue(p.homes.size in 0..2)
            assertTrue(p.familyPegs in 0..4)
            assertTrue(p.raises in 0..3)
            assertTrue(p.luckyCoins in 0..9)
            assertTrue(p.memories <= 6)
        }
        if (!s.over) assertTrue("seat on turn retired", !s.players[s.currentSeat].retired || s.phase == Phase.SETTLING)
        assertTrue(s.phase != Phase.MOVING && s.phase != Phase.TURN_END)
    }

    /** Plays [s0] with random legal actions; returns the final state and the number of actions. */
    fun playRandom(s0: LifeRoadState, rnd: Random, cap: Int = 6000, check: (LifeRoadState) -> Unit = ::checkInvariants): Pair<LifeRoadState, Int> {
        var s = s0
        var n = 0
        while (!s.over) {
            val legal = engine.legalActions(s, s.currentSeat)
            assertTrue("stalled in ${s.phase}", legal.isNotEmpty())
            s = act(s, legal[rnd.nextInt(legal.size)])
            check(s)
            n++
            assertTrue("no end after $cap actions", n < cap)
        }
        return s to n
    }

    /** Plays [s0] to the end with the CPU policy of [level] for every seat. */
    fun playBots(s0: LifeRoadState, level: CpuDifficulty, cap: Int = 6000): Pair<LifeRoadState, Int> {
        var s = s0
        var n = 0
        while (!s.over) {
            val a = engine.botAction(s, level)
            assertEquals("bot action $a illegal in ${s.phase}", LifeRoadReject.NONE, engine.explain(s, s.currentSeat, a))
            s = act(s, a)
            n++
            assertTrue("no end after $cap actions", n < cap)
        }
        return s to n
    }
}
