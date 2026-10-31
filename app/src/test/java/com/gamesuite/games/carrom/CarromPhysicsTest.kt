package com.gamesuite.games.carrom

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CarromPhysicsTest {
    private fun strikeFrom(coins: List<CarromCoin>, sx: Double, sy: Double, vx: Double, vy: Double) =
        CarromPhysics.simulate(coins, sx, sy, vx, vy)

    @Test fun openingLayoutHasNineNineAndTheQueenWithoutOverlap() {
        val c = CarromBoard.openingCoins()
        assertEquals(19, c.size)
        assertEquals((0..18).toList(), c.map { it.id })
        assertEquals(9, c.count { CarromBoard.colourOf(it.id) == 0 })
        assertEquals(9, c.count { CarromBoard.colourOf(it.id) == 1 })
        val q = c.first { it.id == 18 }
        assertEquals(CarromBoard.CENTER, q.x, 1e-9); assertEquals(CarromBoard.CENTER, q.y, 1e-9)
        for (i in c.indices) for (j in i + 1 until c.size) {
            val d = Math.hypot(c[i].x - c[j].x, c[i].y - c[j].y)
            assertTrue("coins $i/$j overlap ($d)", d >= 2 * CarromBoard.COIN_R)
        }
    }

    @Test fun sameShotPlaysOutIdentically() {
        val a = strikeFrom(CarromBoard.openingCoins(), 370.0, 635.0, 120.0, -1500.0)
        val b = strikeFrom(CarromBoard.openingCoins(), 370.0, 635.0, 120.0, -1500.0)
        assertEquals(a.steps, b.steps)
        assertEquals(a.pocketed, b.pocketed)
        assertEquals(a.coins, b.coins)
    }

    @Test fun aStraightHitMovesTheCoin() {
        val coin = CarromCoin(0, 370.0, 300.0)
        val r = strikeFrom(listOf(coin), 370.0, 600.0, 0.0, -800.0)
        val moved = r.pocketed.isNotEmpty() || r.coins.single().y < 300.0 - 5
        assertTrue("coin did not move", moved)
    }

    @Test fun aFastStrikerNeverTunnelsThroughACoin() {
        for (speed in listOf(1000.0, 1800.0, 2400.0)) {
            for (off in listOf(-18.0, -8.0, 0.0, 9.0, 17.0)) {
                val coin = CarromCoin(0, 370.0 + off, 300.0)
                val r = strikeFrom(listOf(coin), 370.0, 640.0, 0.0, -speed)
                val moved = r.pocketed.isNotEmpty() || Math.hypot(r.coins.single().x - coin.x, r.coins.single().y - coin.y) > 1.0
                assertTrue("tunnelled at speed $speed offset $off", moved)
            }
        }
    }

    @Test fun energyNeverGrowsAndNoDiscLeavesTheBoard() {
        val rnd = Random(5)
        repeat(12) {
            val sim = CarromSim(CarromBoard.openingCoins(), 120.0 + rnd.nextDouble() * 500, 635.0, (rnd.nextDouble() - 0.5) * 800, -(800 + rnd.nextDouble() * 1600))
            var last = sim.kineticEnergy()
            var guard = 0
            while (sim.isMoving() && guard++ < CarromPhysics.MAX_STEPS) {
                sim.step()
                val e = sim.kineticEnergy()
                assertTrue("energy grew $last -> $e", e <= last * (1 + 1e-9) + 1e-9)
                last = e
                for (i in 0 until sim.n) if (sim.alive[i]) {
                    assertTrue(sim.x[i] >= sim.radius[i] - 1e-6 && sim.x[i] <= CarromBoard.SIZE - sim.radius[i] + 1e-6)
                    assertTrue(sim.y[i] >= sim.radius[i] - 1e-6 && sim.y[i] <= CarromBoard.SIZE - sim.radius[i] + 1e-6)
                }
            }
        }
    }

    @Test fun aDiagonalShotIntoTheCornerIsPocketed() {
        val shot = strikeFrom(listOf(CarromCoin(0, 150.0, 150.0)), 300.0, 300.0, -600.0, -600.0)
        assertTrue("diagonal shot into the corner should pocket the coin or the striker", shot.pocketed.contains(0) || shot.strikerPocketed)
    }

    @Test fun fuzzRandomShotsTerminateAndKeepTheBoardSane() {
        val rnd = Random(99)
        repeat(120) {
            var coins = CarromBoard.openingCoins()
            repeat(3) {
                val side = rnd.nextInt(4)
                val shot = CarromShot(rnd.nextInt(0, 1001), rnd.nextInt(-8000, 8001), rnd.nextInt(1, 1001))
                if (CarromRules.standBlocked(coins, side, shot.t)) return@repeat
                val l = CarromRules.launch(side, shot)
                val r = CarromPhysics.simulate(coins, l[0], l[1], l[2], l[3])
                assertTrue(r.steps <= CarromPhysics.MAX_STEPS)
                assertEquals(r.coins.size + r.pocketed.size, coins.size)
                assertEquals(r.coins.map { it.id }.toSet().size, r.coins.size)
                r.coins.forEach {
                    assertTrue(it.x.isFinite() && it.y.isFinite())
                    assertTrue(it.x in 0.0..CarromBoard.SIZE && it.y in 0.0..CarromBoard.SIZE)
                }
                coins = r.coins
            }
        }
    }

    @Test fun restingCoinsDoNotOverlapBadly() {
        val rnd = Random(3)
        repeat(30) {
            val r = CarromPhysics.simulate(CarromBoard.openingCoins(), 100.0 + rnd.nextDouble() * 540, 635.0, (rnd.nextDouble() - 0.5) * 500, -(1200 + rnd.nextDouble() * 1200))
            val c = r.coins
            for (i in c.indices) for (j in i + 1 until c.size) {
                val d = Math.hypot(c[i].x - c[j].x, c[i].y - c[j].y)
                assertTrue("overlap $d", d > 2 * CarromBoard.COIN_R - 3.0)
            }
        }
    }

    @Test fun recordedFramesStartAtTheBoard() {
        val coins = CarromBoard.openingCoins()
        val r = CarromPhysics.simulate(coins, 370.0, 635.0, 0.0, -1800.0, record = true)
        assertTrue(r.frames.size > 3)
        assertEquals(coins.size + 1, r.discIds.size)
        assertEquals(coins[0].x.toFloat(), r.frames.first()[0], 1e-3f)
    }

    @Test fun aimAndPlacementAreConsistentOnEverySide() {
        for (side in 0..3) {
            val (fx, fy) = CarromBoard.shotDirection(side, 0)
            val (sx, sy) = CarromBoard.strikerStart(side, 500)
            val toC = Math.hypot(CarromBoard.CENTER - sx, CarromBoard.CENTER - sy)
            val dot = ((CarromBoard.CENTER - sx) * fx + (CarromBoard.CENTER - sy) * fy) / toC
            assertEquals("side $side", 1.0, dot, 1e-6)
            val (rx, ry) = CarromBoard.shotDirection(side, 4500)
            val cross = fx * ry - fy * rx
            assertTrue("side $side right turn", cross > 0)
            // left-to-right placement agrees with the aim's "right"
            val (ax, ay) = CarromBoard.strikerStart(side, 0)
            val (bx, by) = CarromBoard.strikerStart(side, 1000)
            val across = fx * (by - ay) - fy * (bx - ax)
            assertTrue("side $side placement runs left to right", across > 0)
        }
    }
}
