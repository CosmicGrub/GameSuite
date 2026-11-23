package com.gamesuite.games.tankbattle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physics and terrain with known values on a flat field. */
class TankRulesTest {
    private fun flat(h: Int = 50) = IntArray(TankWorld.W) { h }
    private fun hp(n: Int) = IntArray(n) { TankWorld.START_HP }
    private fun impactX(r: SimResult) = r.impacts.last().x10 / 10.0

    private fun shoot(
        terrain: IntArray = flat(), tanks: List<TankBody> = listOf(TankBody(40, true), TankBody(300, true)),
        weapon: String = TankWeapons.SHELL, angle: Int = 45, power: Int = 60, wind: Int = 0, shooter: Int = 0
    ) = TankSim.simulate(terrain, tanks, hp(tanks.size), shooter, weapon, angle, power, wind)

    @Test fun flatFieldRangeMatchesProjectileMaths() {
        // v = 200 * 0.6 = 120 u/s at 45 degrees: range v^2 sin(2a) / g = 205.7 from the muzzle, which sits ~6 units ahead.
        val r = shoot(tanks = listOf(TankBody(20, true), TankBody(310, true)))
        val x = impactX(r)
        assertEquals(TankImpact.GROUND, r.impacts.last().kind)
        assertEquals(244.7, x, 3.0) // 20 + 6.4 muzzle + v*t with the muzzle ~13 above the ground
    }

    @Test fun windPushesTheShellItsOwnWay() {
        val tanks = listOf(TankBody(20, true), TankBody(310, true))
        val calm = impactX(shoot(tanks = tanks, wind = 0))
        val tail = impactX(shoot(tanks = tanks, wind = TankWorld.WIND_MAX))
        val head = impactX(shoot(tanks = tanks, wind = -TankWorld.WIND_MAX))
        assertTrue(tail > calm + 20)
        assertTrue(head < calm - 20)
    }

    @Test fun anAngleOfNinetyOrOneEightyFiresStraightUpOrLeft() {
        val up = shoot(angle = 90, power = 40, tanks = listOf(TankBody(160, true), TankBody(300, true)))
        // straight up and back down onto the shooter: it hurts itself
        assertTrue(up.damage[0] > 0)
        val left = shoot(angle = 180, power = 30, tanks = listOf(TankBody(200, true), TankBody(300, true)))
        assertTrue(impactX(left) < 200)
    }

    @Test fun aCraterLowersTheGroundAroundTheImpactOnly() {
        val t = flat()
        val r = shoot(terrain = t, tanks = listOf(TankBody(20, true), TankBody(310, true)))
        val x = impactX(r).toInt()
        assertTrue(t[x] < 50)
        assertEquals(50, t[x + 40])
        assertEquals(50, t[(x - 40).coerceAtLeast(0)])
        // never below the floor, never above what it was
        assertTrue(t.all { it in 0..50 })
        // the bowl is roughly the shell's radius deep
        assertTrue(50 - t[x] in 12..17)
    }

    @Test fun aBigShellDigsWiderAndDeeperThanAShell() {
        val a = flat(); val b = flat()
        val tanks = listOf(TankBody(20, true), TankBody(310, true))
        shoot(terrain = a, tanks = tanks)
        shoot(terrain = b, tanks = tanks, weapon = TankWeapons.BIG)
        assertTrue(b.count { it < 50 } > a.count { it < 50 })
        assertTrue(b.min() < a.min())
    }

    @Test fun aDirectHitDoesFullDamageAndKillsAWeakTank() {
        val probe = shoot(tanks = listOf(TankBody(20, true), TankBody(310, true)))
        val col = impactX(probe).toInt()
        val tanks = listOf(TankBody(20, true), TankBody(col, true))
        val r = TankSim.simulate(flat(), tanks, intArrayOf(100, 20), 0, TankWeapons.SHELL, 45, 60, 0)
        assertEquals(TankImpact.TANK, r.impacts.last().kind)
        assertTrue("direct hit ${r.damage[1]}", r.damage[1] >= 24)
        assertEquals(listOf(1), r.killed)
    }

    @Test fun damageFallsOffWithDistanceAndStopsAtTheBlastEdge() {
        val probe = shoot(tanks = listOf(TankBody(20, true), TankBody(310, true)))
        val col = impactX(probe).toInt()
        fun dmgAt(offset: Int): Int {
            val tanks = listOf(TankBody(20, true), TankBody((col + offset).coerceIn(0, 319), true))
            // land short of the victim so the shell hits the ground, not the tank
            return TankSim.simulate(flat(), tanks, hp(2), 0, TankWeapons.SHELL, 45, 60, 0).damage[1]
        }
        val near = dmgAt(6)
        val mid = dmgAt(12)
        val far = dmgAt(40)
        assertTrue("$near $mid", near >= mid)
        assertTrue(mid > 0)
        assertEquals(0, far)
    }

    @Test fun deadTanksTakeNoDamageAndDoNotBlockShells() {
        val probe = shoot(tanks = listOf(TankBody(20, true), TankBody(310, true)))
        val col = impactX(probe).toInt()
        val r = shoot(tanks = listOf(TankBody(20, true), TankBody(col, false)))
        assertEquals(0, r.damage[1])
        assertTrue(r.killed.isEmpty())
    }

    @Test fun aBouncerHopsBeforeItExplodes() {
        val tanks = listOf(TankBody(20, true), TankBody(310, true))
        val r = shoot(tanks = tanks, weapon = TankWeapons.BOUNCER, angle = 40, power = 55)
        val hops = r.impacts.count { it.kind == TankImpact.BOUNCE }
        assertTrue("hops $hops", hops in 1..3)
        assertEquals(TankImpact.GROUND, r.impacts.last().kind)
        val plain = shoot(tanks = tanks, angle = 40, power = 55)
        assertTrue(impactX(r) > impactX(plain))
    }

    @Test fun aShellThatLeavesTheWorldIsLostAndLeavesNoCrater() {
        val t = flat()
        val r = shoot(terrain = t, angle = 45, power = 100, tanks = listOf(TankBody(20, true), TankBody(310, true)))
        assertEquals(TankImpact.LOST, r.impacts.last().kind)
        assertTrue(t.all { it == 50 })
        assertTrue(r.damage.all { it == 0 })
    }

    @Test fun simulationIsDeterministicAndMatchesItsRecording() {
        val a = shoot(angle = 52, power = 71, wind = 3)
        val b = shoot(angle = 52, power = 71, wind = 3)
        assertTrue(a.path.contentEquals(b.path))
        assertEquals(a.impacts, b.impacts)
        val bare = TankSim.simulate(flat(), listOf(TankBody(40, true), TankBody(300, true)), hp(2), 0, TankWeapons.SHELL, 52, 71, 3, record = false)
        assertEquals(a.impacts, bare.impacts)
        assertTrue(a.path.size % 2 == 0 && a.path.size > 20)
    }

    @Test fun terrainGenerationIsSeededAndTanksSitOnFlatPads() {
        val (t1, c1) = TankTerrain.generate(42L, 4)
        val (t2, c2) = TankTerrain.generate(42L, 4)
        assertEquals(t1, t2); assertEquals(c1, c2)
        assertNotEquals(t1, TankTerrain.generate(43L, 4).first)
        assertEquals(TankWorld.W, t1.size)
        assertTrue(t1.all { it in 0..TankWorld.H })
        for (c in c1) for (d in -6..6) assertEquals(t1[c], t1[c + d])
        val sorted = c1.sorted()
        for (i in 1 until sorted.size) assertTrue("tanks too close $sorted", sorted[i] - sorted[i - 1] >= 40)
        assertTrue(c1.toSet().size == 4)
    }

    @Test fun seatOrderAcrossTheFieldVariesWithTheSeed() {
        val orders = (1L..30L).map { TankTerrain.generate(it, 2).second[0] < 160 }.toSet()
        assertEquals(setOf(true, false), orders)
    }

    @Test fun windIsAFunctionOfSeedAndTurnInRange() {
        for (turn in 0..50) {
            val w = TankWorld.windFor(7L, turn)
            assertTrue(w in -TankWorld.WIND_MAX..TankWorld.WIND_MAX)
            assertEquals(w, TankWorld.windFor(7L, turn))
        }
        assertTrue((0..50).map { TankWorld.windFor(7L, it) }.toSet().size > 5)
    }
}
