package com.gamesuite.games.propertytycoon

import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.random.Random

/** Shared helpers of the Property Tycoon tests: crafted tables, scripted dice and an invariant checker. */
internal object PTT {
    val engine = StandardPropertyTycoonEngine
    val bot = StandardPropertyTycoonBot

    fun roster(n: Int, bots: Int = 0) = List(n) { PlayerInfo("p$it", "P$it", isBot = it >= n - bots) }

    fun config(seed: Long = 1L, length: GameLength = GameLength.STANDARD, rules: HouseRules = HouseRules(), handicapSeat: Int = -1, handicapPercent: Int = 0) =
        TycoonConfig(length = length, startCash = length.startCash, seed = seed, houseRules = rules, handicapSeat = handicapSeat, handicapPercent = handicapPercent)

    /** A fresh table with seat 0 to move. */
    fun table(n: Int = 3, cfg: TycoonConfig = config(), bots: Int = 0): TycoonState =
        engine.newGame(cfg, roster(n, bots)).copy(currentSeat = 0)

    fun act(s: TycoonState, c: PtCommand, seat: Int = engine.seatToAct(s)): TycoonState {
        val r = engine.apply(s, seat, c)
        assertEquals("$c refused in ${s.phase}: ${r.reject}", PtReject.NONE, r.reject)
        return r.state
    }

    fun result(s: TycoonState, c: PtCommand, seat: Int = engine.seatToAct(s)) = engine.apply(s, seat, c)

    /** The same table with the dice stream set so the next rolls are exactly [dice] (pairs d1, d2, d1, d2, ...). */
    fun rigged(s: TycoonState, vararg dice: Int): TycoonState {
        var x = 1L
        while (true) {
            val r = PtRng(x)
            if (dice.all { r.rollDie() == it }) return s.copy(rngState = x)
            x++
        }
    }

    fun place(s: TycoonState, seat: Int, tile: Int) = edit(s, seat) { it.copy(position = tile) }

    fun edit(s: TycoonState, seat: Int, f: (PlayerState) -> PlayerState) =
        s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) f(p) else p })

    fun own(s: TycoonState, seat: Int, vararg tiles: Int, buildings: Int = 0): TycoonState {
        val mine = tiles.map { Holding(it, seat, buildings) }
        return s.copy(holdings = (s.holdings.filter { h -> tiles.none { it == h.tile } } + mine).sortedBy { it.tile })
    }

    fun withBuildings(s: TycoonState, tile: Int, level: Int): TycoonState {
        val h = s.holding(tile)!!
        val cabins = if (level == 5) 0 else level
        return s.copy(
            holdings = s.holdings.map { if (it.tile == tile) h.copy(buildings = level) else it },
            cabinsLeft = s.cabinsLeft - cabins, resortsLeft = s.resortsLeft - if (level == 5) 1 else 0
        )
    }

    /** Pearls + tile prices are conserved except by bank flows; this checks the structural invariants only. */
    fun checkInvariants(s: TycoonState) {
        for (p in s.players) {
            assertTrue("cash ${p.cash}", p.cash >= 0)
            assertTrue(p.position in 0 until 40)
            assertEquals(p.passCardIds.size, p.freePassCards)
        }
        val seen = HashSet<Int>()
        var cabins = 0; var resorts = 0
        for (h in s.holdings) {
            assertTrue(seen.add(h.tile))
            assertTrue(PtBoard.tile(h.tile).ownable)
            assertTrue(!s.players[h.owner].bankrupt)
            if (h.buildings == 5) resorts++ else cabins += h.buildings
        }
        assertEquals(36 - cabins, s.cabinsLeft)
        assertEquals(14 - resorts, s.resortsLeft)
        for (g in ColorGroup.values()) {
            val levels = PtBoard.tilesOf(g).map { s.holding(it.index)?.buildings ?: 0 }
            assertTrue("uneven $g $levels", levels.max() - levels.min() <= 1 || levels.min() == 0 && levels.max() <= 1)
        }
        if (!s.over) assertTrue(!s.players[s.currentSeat].bankrupt)
    }

    fun playRandom(s0: TycoonState, rnd: Random, cap: Int = 20000, check: (TycoonState) -> Unit = ::checkInvariants): Pair<TycoonState, Int> {
        var s = s0
        var n = 0
        while (!s.over) {
            val seat = engine.seatToAct(s)
            val legal = engine.legalCommands(s, seat)
            assertTrue("stalled in ${s.phase}", legal.isNotEmpty())
            s = act(s, legal[rnd.nextInt(legal.size)], seat)
            check(s)
            n++
            assertTrue("no end after $cap commands", n < cap)
        }
        return s to n
    }

    fun playBots(s0: TycoonState, level: CpuDifficulty, cap: Int = 30000): Pair<TycoonState, Int> {
        var s = s0
        var n = 0
        while (!s.over) {
            val seat = engine.seatToAct(s)
            val c = bot.decide(s, seat, level, engine)
            assertEquals("bot $c illegal in ${s.phase}", PtReject.NONE, engine.explain(s, seat, c))
            s = act(s, c, seat)
            n++
            assertTrue("no end after $cap commands", n < cap)
        }
        return s to n
    }
}
