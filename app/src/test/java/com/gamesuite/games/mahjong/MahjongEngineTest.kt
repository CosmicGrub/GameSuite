package com.gamesuite.games.mahjong

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** Layouts, the free-tile rule, tile matching, the solvable dealer and the solver (pure engine). */
class MahjongEngineTest {

    private fun geo(vararg p: Triple<Int, Int, Int>) =
        MahjongGeometry(p.map { MahjongPos(it.first, it.second, it.third) })

    private fun allAlive(g: MahjongGeometry) = BooleanArray(g.size) { true }

    private fun replays(g: MahjongGeometry, types: IntArray, order: List<Pair<Int, Int>>): Boolean {
        val alive = BooleanArray(g.size) { types[it] >= 0 }
        for ((a, b) in order) {
            if (!g.isFree(a, alive) || !g.isFree(b, alive) || !MahjongTiles.matches(types[a], types[b])) return false
            alive[a] = false
            alive[b] = false
        }
        return alive.none { it }
    }

    // --- Layouts ----------------------------------------------------------------------------

    @Test
    fun `turtle is the classic 144 tiles`() {
        assertEquals(144, MahjongLayouts.positions(MahjongLayoutId.TURTLE).size)
    }

    @Test
    fun `pyramid is about 90 tiles and cross about 60`() {
        assertTrue(MahjongLayouts.positions(MahjongLayoutId.PYRAMID).size in 85..95)
        assertTrue(MahjongLayouts.positions(MahjongLayoutId.CROSS).size in 55..65)
    }

    @Test
    fun `every layout has an even tile count`() {
        for (id in MahjongLayoutId.values()) {
            assertEquals("$id", 0, MahjongLayouts.positions(id).size % 2)
        }
    }

    @Test
    fun `layout coordinates are valid and unique`() {
        for (id in MahjongLayoutId.values()) {
            val pos = MahjongLayouts.positions(id)
            assertEquals("$id duplicates", pos.size, pos.toSet().size)
            for (p in pos) {
                assertTrue("$id $p", p.x >= 0 && p.y >= 0 && p.z in 0..4)
            }
        }
    }

    @Test
    fun `no two tiles of a layout overlap`() {
        for (id in MahjongLayoutId.values()) {
            assertTrue("$id", MahjongLayouts.geometry(id).overlappingPairs().isEmpty())
        }
    }

    @Test
    fun `every raised tile rests on a tile one layer below`() {
        for (id in MahjongLayoutId.values()) {
            val pos = MahjongLayouts.positions(id)
            for (p in pos.filter { it.z > 0 }) {
                assertTrue("$id $p", pos.any { it.z == p.z - 1 && abs(it.x - p.x) < 2 && abs(it.y - p.y) < 2 })
            }
        }
    }

    @Test
    fun `layout keys resolve and unknown keys fall back to the turtle`() {
        assertEquals(MahjongLayoutId.PYRAMID, MahjongLayoutId.fromKey("pyramid"))
        assertEquals(MahjongLayoutId.CROSS, MahjongLayoutId.fromKey("cross"))
        assertEquals(MahjongLayoutId.TURTLE, MahjongLayoutId.fromKey("turtle"))
        assertEquals(MahjongLayoutId.TURTLE, MahjongLayoutId.fromKey(null))
        assertEquals(MahjongLayoutId.TURTLE, MahjongLayoutId.fromKey("nonsense"))
    }

    // --- The free-tile rule -----------------------------------------------------------------

    @Test
    fun `a lone tile is free`() {
        val g = geo(Triple(0, 0, 0))
        assertTrue(g.isFree(0, allAlive(g)))
    }

    @Test
    fun `a removed tile is never free`() {
        val g = geo(Triple(0, 0, 0))
        assertFalse(g.isFree(0, booleanArrayOf(false)))
    }

    @Test
    fun `a tile with another on top is blocked and the top one is free`() {
        val g = geo(Triple(0, 0, 0), Triple(0, 0, 1))
        assertFalse(g.isFree(0, allAlive(g)))
        assertTrue(g.isFree(1, allAlive(g)))
    }

    @Test
    fun `a half-offset tile on top still covers`() {
        val g = geo(Triple(0, 0, 0), Triple(1, 1, 1))
        assertFalse(g.isFree(0, allAlive(g)))
    }

    @Test
    fun `a raised tile a full tile away does not cover`() {
        val g = geo(Triple(0, 0, 0), Triple(2, 0, 1))
        assertTrue(g.isFree(0, allAlive(g)))
    }

    @Test
    fun `removing the cover frees the tile`() {
        val g = geo(Triple(0, 0, 0), Triple(0, 0, 1))
        assertTrue(g.isFree(0, booleanArrayOf(true, false)))
    }

    @Test
    fun `a tile hemmed in left and right is blocked but the ends are free`() {
        val g = geo(Triple(0, 0, 0), Triple(2, 0, 0), Triple(4, 0, 0))
        val alive = allAlive(g)
        assertTrue(g.isFree(0, alive))
        assertFalse(g.isFree(1, alive))
        assertTrue(g.isFree(2, alive))
    }

    @Test
    fun `one open side is enough`() {
        val g = geo(Triple(0, 0, 0), Triple(2, 0, 0))
        assertTrue(g.isFree(1, allAlive(g)))
        assertTrue(g.isFree(0, allAlive(g)))
    }

    @Test
    fun `removing a neighbour opens the middle tile`() {
        val g = geo(Triple(0, 0, 0), Triple(2, 0, 0), Triple(4, 0, 0))
        assertTrue(g.isFree(1, booleanArrayOf(false, true, true)))
        assertTrue(g.isFree(1, booleanArrayOf(true, true, false)))
    }

    @Test
    fun `a neighbour half a row off still blocks that side`() {
        val g = geo(Triple(2, 0, 0), Triple(0, 1, 0), Triple(4, 1, 0))
        assertFalse(g.isFree(0, allAlive(g)))
    }

    @Test
    fun `a neighbour a full row off does not block`() {
        val g = geo(Triple(2, 0, 0), Triple(0, 2, 0), Triple(4, 2, 0))
        assertTrue(g.isFree(0, allAlive(g)))
    }

    @Test
    fun `a tile with a gap beside it is free`() {
        val g = geo(Triple(0, 0, 0), Triple(4, 0, 0), Triple(8, 0, 0))
        assertTrue(g.isFree(1, allAlive(g)))
    }

    @Test
    fun `a neighbour on another layer does not block the side`() {
        val g = geo(Triple(2, 0, 0), Triple(0, 0, 1), Triple(4, 0, 1))
        assertTrue(g.isFree(0, allAlive(g)))
    }

    @Test
    fun `a covered tile with both sides open is still blocked`() {
        val g = geo(Triple(0, 0, 0), Triple(0, 0, 1))
        assertFalse(g.isFree(0, allAlive(g)))
        assertEquals(0, g.leftNeighbours(0).size)
        assertEquals(0, g.rightNeighbours(0).size)
    }

    @Test
    fun `the turtle top tile and the left cap start free and the inner cap does not`() {
        val g = MahjongLayouts.geometry(MahjongLayoutId.TURTLE)
        val pos = MahjongLayouts.positions(MahjongLayoutId.TURTLE)
        val alive = allAlive(g)
        assertTrue(g.isFree(pos.indexOf(MahjongPos(13, 7, 4)), alive))
        assertTrue(g.isFree(pos.indexOf(MahjongPos(0, 7, 0)), alive))
        assertTrue(g.isFree(pos.indexOf(MahjongPos(28, 7, 0)), alive))
        assertFalse(g.isFree(pos.indexOf(MahjongPos(26, 7, 0)), alive))
    }

    @Test
    fun `the turtle centre stack is covered from the top`() {
        val g = MahjongLayouts.geometry(MahjongLayoutId.TURTLE)
        val pos = MahjongLayouts.positions(MahjongLayoutId.TURTLE)
        val alive = allAlive(g)
        assertFalse(g.isFree(pos.indexOf(MahjongPos(12, 6, 3)), alive))
        alive[pos.indexOf(MahjongPos(13, 7, 4))] = false
        assertTrue(g.isFree(pos.indexOf(MahjongPos(12, 6, 3)), alive))
    }

    // --- Tiles and matching -----------------------------------------------------------------

    @Test
    fun `the full set is 144 tiles with the right counts`() {
        val set = MahjongTiles.fullSet()
        assertEquals(144, set.size)
        val counts = IntArray(MahjongTiles.TYPE_COUNT)
        for (t in set) counts[t]++
        for (t in 0 until MahjongTiles.FIRST_FLOWER) assertEquals("type $t", 4, counts[t])
        for (t in MahjongTiles.FIRST_FLOWER until MahjongTiles.TYPE_COUNT) assertEquals("type $t", 1, counts[t])
    }

    @Test
    fun `flowers match any flower and seasons any season but not each other`() {
        assertTrue(MahjongTiles.matches(34, 37))
        assertTrue(MahjongTiles.matches(35, 36))
        assertTrue(MahjongTiles.matches(38, 41))
        assertTrue(MahjongTiles.matches(39, 40))
        assertFalse(MahjongTiles.matches(34, 38))
        assertFalse(MahjongTiles.matches(41, 37))
    }

    @Test
    fun `simple tiles match only their own type`() {
        assertTrue(MahjongTiles.matches(0, 0))
        assertTrue(MahjongTiles.matches(27, 27))
        assertTrue(MahjongTiles.matches(33, 33))
        assertFalse(MahjongTiles.matches(0, 9)) // dots 1 vs bamboo 1
        assertFalse(MahjongTiles.matches(27, 28)) // east vs south
        assertFalse(MahjongTiles.matches(31, 32)) // red vs green dragon
        assertFalse(MahjongTiles.matches(0, 1))
        assertFalse(MahjongTiles.matches(-1, -1))
    }

    @Test
    fun `suits and ranks follow the numbering`() {
        assertEquals(MahjongSuit.DOTS, MahjongTiles.suit(0))
        assertEquals(1, MahjongTiles.rank(0))
        assertEquals(9, MahjongTiles.rank(8))
        assertEquals(MahjongSuit.BAMBOO, MahjongTiles.suit(9))
        assertEquals(3, MahjongTiles.rank(11))
        assertEquals(MahjongSuit.CHARACTERS, MahjongTiles.suit(26))
        assertEquals(9, MahjongTiles.rank(26))
        assertEquals(MahjongSuit.WIND, MahjongTiles.suit(27))
        assertEquals(MahjongSuit.DRAGON, MahjongTiles.suit(33))
        assertEquals(MahjongSuit.FLOWER, MahjongTiles.suit(34))
        assertEquals(MahjongSuit.SEASON, MahjongTiles.suit(41))
        assertEquals(4, MahjongTiles.rank(41))
    }

    @Test
    fun `every type has its own mahjong block glyph`() {
        val points = (0 until MahjongTiles.TYPE_COUNT).map { MahjongTiles.codePoint(it) }
        assertEquals(MahjongTiles.TYPE_COUNT, points.toSet().size)
        assertTrue(points.all { it in 0x1F000..0x1F029 })
        assertEquals(0x1F000, MahjongTiles.codePoint(27)) // East wind
        assertEquals(0x1F004, MahjongTiles.codePoint(31)) // Red dragon
        assertEquals(0x1F007, MahjongTiles.codePoint(18)) // Character one
        assertEquals(0x1F019, MahjongTiles.codePoint(0)) // Dots one
        assertEquals(2, MahjongTiles.glyph(31).length) // a surrogate pair
    }

    @Test
    fun `the pair pool is 72 matching pairs that make up the set`() {
        val pool = MahjongTiles.pairPool(Random(3))
        assertEquals(72, pool.size)
        assertTrue(pool.all { MahjongTiles.matches(it.first, it.second) })
        val flat = pool.flatMap { listOf(it.first, it.second) }.sorted()
        assertEquals(MahjongTiles.fullSet().toList().sorted(), flat)
    }

    // --- The dealer -------------------------------------------------------------------------

    @Test
    fun `the turtle deal is exactly the full set`() {
        val geo = MahjongLayouts.geometry(MahjongLayoutId.TURTLE)
        val deal = MahjongDealer.newDeal(geo, Random(11))
        assertEquals(MahjongTiles.fullSet().toList().sorted(), deal.types.toList().sorted())
    }

    @Test
    fun `smaller layouts deal a legal subset with even groups`() {
        for (id in listOf(MahjongLayoutId.PYRAMID, MahjongLayoutId.CROSS)) {
            val geo = MahjongLayouts.geometry(id)
            val deal = MahjongDealer.newDeal(geo, Random(5))
            assertEquals(geo.size, deal.types.count { it >= 0 })
            val counts = IntArray(MahjongTiles.TYPE_COUNT)
            val groups = IntArray(MahjongTiles.GROUP_COUNT)
            for (t in deal.types) {
                counts[t]++
                groups[MahjongTiles.group(t)]++
            }
            assertTrue("$id", MahjongTiles.withinSet(counts))
            assertTrue("$id", groups.all { it % 2 == 0 })
        }
    }

    @Test
    fun `a deal is reproducible for a seed and differs between seeds`() {
        val geo = MahjongLayouts.geometry(MahjongLayoutId.PYRAMID)
        val a = MahjongDealer.newDeal(geo, Random(21))
        val b = MahjongDealer.newDeal(geo, Random(21))
        val c = MahjongDealer.newDeal(geo, Random(22))
        assertEquals(a.types.toList(), b.types.toList())
        assertEquals(a.removalOrder, b.removalOrder)
        assertNotEquals(a.types.toList(), c.types.toList())
    }

    @Test
    fun `every layout deals a winnable board for many seeds`() {
        for (id in MahjongLayoutId.values()) {
            val geo = MahjongLayouts.geometry(id)
            for (seed in 0 until 40) {
                val deal = MahjongDealer.newDeal(geo, Random(seed))
                assertEquals("$id $seed order", geo.size / 2, deal.removalOrder.size)
                assertTrue("$id seed $seed is not clearable", replays(geo, deal.types, deal.removalOrder))
            }
        }
    }

    @Test
    fun `the deterministic fallback order also clears every layout`() {
        for (id in MahjongLayoutId.values()) {
            val geo = MahjongLayouts.geometry(id)
            val order = MahjongDealer.fallbackOrder(geo, BooleanArray(geo.size) { true })
            assertNotNull("$id", order)
            // Any matching assignment on that order is a legal playthrough; use one type per pair.
            val types = IntArray(geo.size)
            order!!.forEachIndexed { k, (a, b) ->
                types[a] = k % 34
                types[b] = k % 34
            }
            assertTrue("$id", replays(geo, types, order))
        }
    }

    @Test
    fun `a row of four clears the ends first`() {
        val g = geo(Triple(0, 0, 0), Triple(2, 0, 0), Triple(4, 0, 0), Triple(6, 0, 0))
        val deal = MahjongDealer.newDeal(g, Random(1))
        assertEquals(setOf(0, 3), setOf(deal.removalOrder[0].first, deal.removalOrder[0].second))
        assertEquals(setOf(1, 2), setOf(deal.removalOrder[1].first, deal.removalOrder[1].second))
        assertTrue(replays(g, deal.types, deal.removalOrder))
    }

    // --- The solver -------------------------------------------------------------------------

    @Test
    fun `the solver never calls a generated deal unsolvable and its lines replay`() {
        for (id in listOf(MahjongLayoutId.PYRAMID, MahjongLayoutId.CROSS)) {
            val geo = MahjongLayouts.geometry(id)
            for (seed in 0 until 6) {
                val deal = MahjongDealer.newDeal(geo, Random(seed))
                val result = MahjongSolver.solve(geo, deal.types, maxNodes = 5_000)
                assertNotEquals("$id $seed", MahjongSolve.UNSOLVABLE, result.outcome)
                if (result.outcome == MahjongSolve.SOLVABLE) {
                    assertTrue("$id $seed line", replays(geo, deal.types, result.line))
                }
            }
        }
    }

    @Test
    fun `the solver proves a stacked pair unsolvable`() {
        val g = geo(Triple(0, 0, 0), Triple(0, 0, 1))
        val result = MahjongSolver.solve(g, intArrayOf(5, 5))
        assertEquals(MahjongSolve.UNSOLVABLE, result.outcome)
    }

    @Test
    fun `the solver solves an adjacent pair and an empty board`() {
        val g = geo(Triple(0, 0, 0), Triple(2, 0, 0))
        val solved = MahjongSolver.solve(g, intArrayOf(5, 5))
        assertEquals(MahjongSolve.SOLVABLE, solved.outcome)
        assertEquals(1, solved.line.size)
        assertEquals(MahjongSolve.SOLVABLE, MahjongSolver.solve(g, intArrayOf(-1, -1)).outcome)
    }

    @Test
    fun `available pairs only list free matching tiles`() {
        // Three in a row, ends match, the middle (blocked) also matches them.
        val g = geo(Triple(0, 0, 0), Triple(2, 0, 0), Triple(4, 0, 0))
        val pairs = MahjongSolver.availablePairs(g, intArrayOf(3, 3, 3))
        assertEquals(listOf(0 to 2), pairs)
        // Different types: no pair at all.
        assertTrue(MahjongSolver.availablePairs(g, intArrayOf(3, 3, 4)).isEmpty())
    }

    @Test
    fun `reshuffle keeps the tiles the slots and a move for many partial boards`() {
        val geo = MahjongLayouts.geometry(MahjongLayoutId.TURTLE)
        for (seed in 0 until 12) {
            val deal = MahjongDealer.newDeal(geo, Random(seed))
            val types = deal.types.copyOf()
            // Play the first 30 pairs of the solution, then re-deal what is left.
            for ((a, b) in deal.removalOrder.take(30)) {
                types[a] = -1
                types[b] = -1
            }
            val fresh = MahjongDealer.reshuffle(geo, types, Random(seed + 100))
            assertNotNull("seed $seed", fresh)
            assertEquals(types.map { it >= 0 }, fresh!!.map { it >= 0 })
            assertEquals(types.filter { it >= 0 }.sorted(), fresh.filter { it >= 0 }.sorted())
            assertTrue(MahjongSolver.availablePairs(geo, fresh).isNotEmpty())
            val check = MahjongSolver.solve(geo, fresh, maxNodes = 3_000)
            assertNotEquals(MahjongSolve.UNSOLVABLE, check.outcome)
        }
    }
}
