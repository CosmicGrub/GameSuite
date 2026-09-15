package com.gamesuite.games.towerdefence

import androidx.compose.ui.geometry.Offset
import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.towerdefence.TowerDefenceGame.TowerDefenceLevel
import com.gamesuite.games.towerdefence.TowerDefenceGame.TowerDefenceRunResult
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No injectable clock needed here -- like BreakoutGame (and unlike the wall-clock-timer puzzle
 * games), Tower Defence's own stats are wave-based, not time-based, so [TowerDefenceGame.tick]
 * takes `dtSeconds` directly and every test steps simulated time explicitly via [tickSeconds].
 */
class TowerDefenceGameTest {

    private fun newGame(difficulty: CpuDifficulty = CpuDifficulty.MEDIUM, level: TowerDefenceLevel = TowerDefenceGame.LEVELS.first()): TowerDefenceGame {
        val game = TowerDefenceGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = difficulty
        game.level = level
        return game
    }

    /** tick() clamps dtSeconds to [0, 0.05] internally (the same anti-teleport clamp
     *  AirHockeyGame/BreakoutGame use), so simulating N seconds of real time means N/step real
     *  tick() calls, not one big dt -- this helper does that stepping. */
    private fun tickSeconds(game: TowerDefenceGame, seconds: Float, step: Float = 0.05f) {
        var remaining = seconds
        while (remaining > 0f) {
            game.tick(step)
            remaining -= step
        }
    }

    /** Ticks until [predicate] holds or [maxSteps] is exhausted -- used for "run until something
     *  happens" scenarios (a wave finishing, a run ending) where the exact elapsed time isn't the
     *  point, bounded so a real regression (an infinite non-terminating loop) fails fast instead
     *  of hanging the test process. */
    private fun tickUntil(game: TowerDefenceGame, step: Float = 0.05f, maxSteps: Int = 20_000, predicate: () -> Boolean): Boolean {
        var steps = 0
        while (!predicate() && steps < maxSteps) {
            game.tick(step)
            steps++
        }
        return predicate()
    }

    private val SHORT_LEAK_LEVEL = TowerDefenceLevel(
        id = "short-leak-test",
        displayName = "Short Leak Test",
        // 0.01 units total -- an enemy leaks almost immediately after spawning (well under one
        // SPAWN_INTERVAL_SECONDS later), so leak/life-loss assertions aren't muddied by a second
        // enemy having already spawned.
        path = listOf(Offset(0f, 0f), Offset(0.01f, 0f)),
        towerZones = listOf(Offset(0.5f, 0.1f))
    )

    private val CLUSTERED_DEFENSE_LEVEL = TowerDefenceLevel(
        id = "clustered-defense-test",
        displayName = "Clustered Defense Test",
        path = listOf(Offset(0f, 0f), Offset(1f, 0f)),
        // Every zone sits within BASE_TOWER_RANGE of the spawn point (distanceTraveled == 0), so
        // a full cluster of towers can start damaging an enemy the instant it spawns -- used by
        // the WON-scenario test to guarantee overwhelming, deterministic defense rather than
        // relying on precisely-tuned balance numbers to happen to keep up over 10 waves.
        towerZones = listOf(
            Offset(0.03f, 0.03f), Offset(0.03f, -0.03f), Offset(-0.03f, 0.03f),
            Offset(-0.03f, -0.03f), Offset(0.06f, 0f), Offset(0f, 0.06f)
        )
    )

    // -- startMatch / difficulty scaling --

    @Test
    fun `startMatch initializes wave 1 with empty entities and the difficulty's starting gold`() {
        for (difficulty in CpuDifficulty.entries) {
            val game = newGame(difficulty)
            game.startMatch()
            val s = game.state.value
            assertEquals(1, s.waveNumber)
            assertEquals(TowerDefenceGame.TOTAL_WAVES, s.totalWaves)
            assertEquals(TowerDefenceGame.STARTING_LIVES, s.lives)
            assertEquals(TowerDefenceGame.INTER_WAVE_SECONDS, s.interWaveCooldown)
            assertTrue(s.enemies.isEmpty())
            assertTrue(s.towers.isEmpty())
            assertTrue(s.projectiles.isEmpty())
            assertFalse(s.paused)
            assertNull(s.runResult)
        }
    }

    @Test
    fun `starting lives are constant across difficulty tiers but starting gold and wave 1 enemy stats scale`() {
        val easy = newGame(CpuDifficulty.EASY, SHORT_LEAK_LEVEL).also { it.startMatch() }
        val medium = newGame(CpuDifficulty.MEDIUM, SHORT_LEAK_LEVEL).also { it.startMatch() }
        val hard = newGame(CpuDifficulty.HARD, SHORT_LEAK_LEVEL).also { it.startMatch() }

        assertEquals(easy.state.value.lives, medium.state.value.lives)
        assertEquals(medium.state.value.lives, hard.state.value.lives)

        assertTrue("HARD should start with less gold than MEDIUM", hard.state.value.gold < medium.state.value.gold)
        assertTrue("MEDIUM should start with less gold than EASY", medium.state.value.gold < easy.state.value.gold)

        // Advance each to the moment its first wave-1 enemy has just spawned, and compare that
        // enemy's own stats plus the total wave-1 count still in flight (queued + alive).
        for (game in listOf(easy, medium, hard)) {
            tickUntil(game) { game.state.value.enemies.isNotEmpty() }
        }
        val easyEnemy = easy.state.value.enemies.first()
        val mediumEnemy = medium.state.value.enemies.first()
        val hardEnemy = hard.state.value.enemies.first()

        assertTrue("HARD wave-1 enemy HP should exceed MEDIUM's", hardEnemy.maxHp > mediumEnemy.maxHp)
        assertTrue("MEDIUM wave-1 enemy HP should exceed EASY's", mediumEnemy.maxHp > easyEnemy.maxHp)
        assertTrue("HARD wave-1 enemy speed should exceed MEDIUM's", hardEnemy.speed > mediumEnemy.speed)
        assertTrue("MEDIUM wave-1 enemy speed should exceed EASY's", mediumEnemy.speed > easyEnemy.speed)

        val easyCount = easy.state.value.enemiesRemainingToSpawn + easy.state.value.enemies.size
        val mediumCount = medium.state.value.enemiesRemainingToSpawn + medium.state.value.enemies.size
        val hardCount = hard.state.value.enemiesRemainingToSpawn + hard.state.value.enemies.size
        assertTrue("HARD wave-1 enemy count should exceed MEDIUM's", hardCount > mediumCount)
        assertTrue("MEDIUM wave-1 enemy count should exceed EASY's", mediumCount > easyCount)
    }

    // -- Enemy movement / leaks --

    @Test
    fun `an enemy that reaches the end of the path costs exactly one life and is removed`() {
        val game = newGame(CpuDifficulty.MEDIUM, SHORT_LEAK_LEVEL)
        game.startMatch()
        val livesBefore = game.state.value.lives

        // Cross the inter-wave cooldown (the first enemy spawns the same tick it elapses) plus a
        // little more -- SHORT_LEAK_LEVEL's 0.01-unit path is crossed almost instantly at any
        // wave-1 speed, well before the second enemy is due 0.8s later.
        tickSeconds(game, TowerDefenceGame.INTER_WAVE_SECONDS + 0.3f)

        val s = game.state.value
        assertEquals(livesBefore - 1, s.lives)
        assertTrue("the leaked enemy should have been removed, not left dangling", s.enemies.isEmpty())
    }

    @Test
    fun `losing every life ends the run as LOST and further ticks become no-ops`() {
        // No towers placed -- every spawned enemy leaks on this level's near-zero-length path.
        val game = newGame(CpuDifficulty.MEDIUM, SHORT_LEAK_LEVEL)
        game.startMatch()

        val reachedLost = tickUntil(game) { game.state.value.runOver }
        assertTrue("expected the run to end (lives exhausted) within the step budget", reachedLost)
        assertEquals(TowerDefenceRunResult.LOST, game.state.value.runResult)
        assertEquals(0, game.state.value.lives)

        val frozen = game.state.value
        game.tick(0.05f)
        assertSame("tick() must no-op once the run is over", frozen, game.state.value)
    }

    @Test
    fun `overwhelming clustered defense clears every wave and ends the run as WON without losing a life`() {
        val game = newGame(CpuDifficulty.EASY, CLUSTERED_DEFENSE_LEVEL)
        game.startMatch()
        // Bypass the real economy pacing for this test's purpose (proving the win path/phase
        // ordering works end to end), same "directly seed engine state" technique already used
        // elsewhere in this app's own test suites -- `state` is public MutableState like every
        // other engine here.
        game.state.value = game.state.value.copy(gold = 10_000)
        for (zoneIndex in CLUSTERED_DEFENSE_LEVEL.towerZones.indices) {
            game.placeTower(zoneIndex)
        }
        assertEquals(CLUSTERED_DEFENSE_LEVEL.towerZones.size, game.state.value.towers.size)

        val reachedEnd = tickUntil(game) { game.state.value.runOver }
        assertTrue("expected the run to finish within the step budget", reachedEnd)
        assertEquals(TowerDefenceRunResult.WON, game.state.value.runResult)
        assertEquals(
            "a 6-tower cluster sitting on top of the spawn point should kill every wave-1..10 enemy before it can travel anywhere",
            TowerDefenceGame.STARTING_LIVES, game.state.value.lives
        )
    }

    // -- Towers & economy --

    @Test
    fun `placeTower deducts gold and rejects duplicate, invalid, or unaffordable placement`() {
        val game = newGame(CpuDifficulty.MEDIUM, CLUSTERED_DEFENSE_LEVEL)
        game.startMatch()
        val goldBefore = game.state.value.gold

        game.placeTower(0)
        assertEquals(1, game.state.value.towers.size)
        assertEquals(goldBefore - TowerDefenceGame.BASE_TOWER_COST, game.state.value.gold)

        game.placeTower(0) // already occupied
        assertEquals("placing on an occupied zone must be a no-op", 1, game.state.value.towers.size)

        game.placeTower(999) // out of range
        assertEquals("placing on an invalid zone must be a no-op", 1, game.state.value.towers.size)

        game.state.value = game.state.value.copy(gold = 0)
        game.placeTower(1)
        assertEquals("placing without enough gold must be a no-op", 1, game.state.value.towers.size)
    }

    @Test
    fun `upgradeTower deducts cost and enforces the max upgrade level`() {
        val game = newGame(CpuDifficulty.MEDIUM, CLUSTERED_DEFENSE_LEVEL)
        game.startMatch()
        game.state.value = game.state.value.copy(gold = 100_000)
        game.placeTower(0)
        val towerId = game.state.value.towers.first().id

        var previousCost = 0
        for (expectedLevel in 2..TowerDefenceGame.MAX_UPGRADE_LEVEL) {
            val goldBefore = game.state.value.gold
            game.upgradeTower(towerId)
            val tower = game.state.value.towers.first { it.id == towerId }
            assertEquals(expectedLevel, tower.upgradeLevel)
            val costPaid = goldBefore - game.state.value.gold
            assertTrue("upgrade cost should be positive", costPaid > 0)
            if (expectedLevel > 2) assertTrue("later upgrades should cost more than earlier ones", costPaid > previousCost)
            previousCost = costPaid
        }

        val goldAtCap = game.state.value.gold
        game.upgradeTower(towerId) // already at MAX_UPGRADE_LEVEL
        assertEquals("upgrading past the cap must be a no-op", TowerDefenceGame.MAX_UPGRADE_LEVEL, game.state.value.towers.first { it.id == towerId }.upgradeLevel)
        assertEquals(goldAtCap, game.state.value.gold)

        game.upgradeTower(-1) // nonexistent tower id
        assertEquals("upgrading a nonexistent tower must be a no-op", goldAtCap, game.state.value.gold)
    }

    @Test
    fun `a tower in range fires on cooldown and its projectile kills the enemy and awards gold`() {
        val game = newGame(CpuDifficulty.MEDIUM, CLUSTERED_DEFENSE_LEVEL)
        game.startMatch()
        game.state.value = game.state.value.copy(gold = 10_000)
        game.placeTower(0)

        // Manufacture a single low-HP enemy directly in range rather than waiting on the real
        // spawn timer -- isolates "does a tower actually damage and kill" from wave pacing.
        val enemy = TowerDefenceGame.TowerDefenceEnemy(id = 777, distanceTraveled = 0f, hp = 1f, maxHp = 1f, speed = 0.09f, goldReward = 10)
        game.state.value = game.state.value.copy(enemies = listOf(enemy), enemiesRemainingToSpawn = 0, interWaveCooldown = 0f)
        val goldBefore = game.state.value.gold

        val killed = tickUntil(game) { game.state.value.enemies.none { it.id == 777 } }
        assertTrue("expected the tower to kill the manufactured enemy within the step budget", killed)
        assertEquals(goldBefore + enemy.goldReward, game.state.value.gold)
    }

    @Test
    fun `a projectile within the overshoot danger band converges to a hit within a couple ticks, not an oscillating chase`() {
        // Adversarial-review regression test: an uncapped homing step of exactly
        // PROJECTILE_SPEED*dt (~0.055 at the engine's own max dt of 0.05) could overshoot past a
        // target sitting strictly between PROJECTILE_HIT_EPSILON (0.02) and that step size, landing
        // on the far side at the same offset -- a period-2 orbit for a near-stationary target that
        // never lands the hit. 0.03 sits squarely in that danger band.
        val game = newGame(CpuDifficulty.MEDIUM, CLUSTERED_DEFENSE_LEVEL)
        game.startMatch()
        val enemy = TowerDefenceGame.TowerDefenceEnemy(id = 1, distanceTraveled = 0.5f, hp = 100f, maxHp = 100f, speed = 0f, goldReward = 10)
        val projectile = TowerDefenceGame.TowerDefenceProjectile(id = 1, position = Offset(0.47f, 0f), targetEnemyId = 1, damage = 10f)
        game.state.value = game.state.value.copy(
            enemies = listOf(enemy), projectiles = listOf(projectile),
            enemiesRemainingToSpawn = 0, interWaveCooldown = 999f
        )

        val landed = tickUntil(game, step = 0.05f, maxSteps = 3) {
            val current = game.state.value.enemies.firstOrNull { it.id == 1 }
            current == null || current.hp < 100f
        }
        assertTrue("expected the projectile to land its hit within a couple ticks instead of overshooting past the target repeatedly", landed)
    }

    @Test
    fun `a tower kill sets lastEnemyDeath with the kill's own position, and leaking never touches it`() {
        val game = newGame(CpuDifficulty.MEDIUM, CLUSTERED_DEFENSE_LEVEL)
        game.startMatch()
        assertNull(game.state.value.lastEnemyDeath)
        game.state.value = game.state.value.copy(gold = 10_000)
        game.placeTower(0)

        val deathPos = Offset(0.03f, 0f)
        val enemy = TowerDefenceGame.TowerDefenceEnemy(id = 555, distanceTraveled = 0f, hp = 1f, maxHp = 1f, speed = 0f, goldReward = 10)
        game.state.value = game.state.value.copy(enemies = listOf(enemy), enemiesRemainingToSpawn = 0, interWaveCooldown = 0f)

        val killed = tickUntil(game) { game.state.value.lastEnemyDeath != null }
        assertTrue("expected the tower to kill the manufactured enemy within the step budget", killed)
        val death = game.state.value.lastEnemyDeath!!
        assertEquals(TowerDefenceGame.positionAlongPath(CLUSTERED_DEFENSE_LEVEL.path, enemy.distanceTraveled), death.position)

        // The event persists (same seq) across ticks where nothing new dies -- same "last one
        // wins, stays until the next" idiom BreakoutGame's own lastBrickBroken already uses.
        val seqAfterKill = death.seq
        game.tick(0.05f)
        assertEquals(seqAfterKill, game.state.value.lastEnemyDeath?.seq)

        // A leak (not a kill) must never set or touch lastEnemyDeath.
        val leakGame = newGame(CpuDifficulty.MEDIUM, SHORT_LEAK_LEVEL)
        leakGame.startMatch()
        tickSeconds(leakGame, TowerDefenceGame.INTER_WAVE_SECONDS + 0.3f)
        assertEquals(1, TowerDefenceGame.STARTING_LIVES - leakGame.state.value.lives)
        assertNull("a leaked enemy must not be reported as a kill", leakGame.state.value.lastEnemyDeath)
    }

    @Test
    fun `a projectile whose target already died is dropped without side effects`() {
        val game = newGame(CpuDifficulty.MEDIUM, CLUSTERED_DEFENSE_LEVEL)
        game.startMatch()
        val orphanProjectile = TowerDefenceGame.TowerDefenceProjectile(
            id = 1, position = Offset(0.9f, 0.9f), targetEnemyId = 4242, damage = 50f
        )
        game.state.value = game.state.value.copy(projectiles = listOf(orphanProjectile), gold = 100)

        game.tick(0.05f) // must not throw

        assertTrue("an orphaned projectile should simply be dropped", game.state.value.projectiles.isEmpty())
        assertEquals("dropping an orphaned projectile must not award gold", 100, game.state.value.gold)
    }

    // -- Pause --

    @Test
    fun `pause freezes tick() completely but still allows placing and upgrading towers`() {
        val game = newGame(CpuDifficulty.MEDIUM, CLUSTERED_DEFENSE_LEVEL)
        game.startMatch()
        game.togglePause()
        assertTrue(game.state.value.paused)

        val frozen = game.state.value
        game.tick(0.05f)
        assertSame("tick() must be a complete no-op while paused", frozen, game.state.value)

        game.state.value = game.state.value.copy(gold = 1_000)
        game.placeTower(0)
        assertEquals("placing a tower must still work while paused -- that's the whole point of pausing", 1, game.state.value.towers.size)
        game.upgradeTower(game.state.value.towers.first().id)
        assertEquals(2, game.state.value.towers.first().upgradeLevel)

        game.togglePause()
        assertFalse(game.state.value.paused)
        val before = game.state.value.interWaveCooldown
        game.tick(0.05f)
        assertTrue("tick() should mutate state again once resumed", game.state.value.interWaveCooldown < before)
    }

    @Test
    fun `GameModule pause and resume lifecycle hooks drive the same paused flag as togglePause`() {
        val game = newGame()
        game.startMatch()
        game.pause()
        assertTrue(game.state.value.paused)
        game.resume()
        assertFalse(game.state.value.paused)
    }

    @Test
    fun `resume() must not silently cancel a manual pause the app-lifecycle hooks did not cause`() {
        val game = newGame(CpuDifficulty.MEDIUM, CLUSTERED_DEFENSE_LEVEL)
        game.startMatch()

        // Player manually pauses via the in-game button to review the board.
        game.togglePause()
        assertTrue(game.state.value.paused)

        // App is backgrounded (e.g. a phone call or notification) while already paused --
        // the lifecycle pause() must be a harmless no-op, not something resume() later "owns".
        game.pause()
        assertTrue(game.state.value.paused)

        // App returns to the foreground -- resume() didn't cause this pause, so it must leave
        // it alone rather than silently un-pausing the game the player deliberately paused.
        game.resume()
        assertTrue(
            "resume() must never clear a pause set manually via togglePause -- only one it caused itself",
            game.state.value.paused
        )
        val frozen = game.state.value
        game.tick(0.05f)
        assertSame("the game must still be genuinely paused after the spurious resume()", frozen, game.state.value)

        // The player's own togglePause() still works normally afterward.
        game.togglePause()
        assertFalse(game.state.value.paused)
    }

    // -- Session lifecycle / matchOver guard --

    @Test
    fun `matchOver guard blocks every gameplay-mutating method`() {
        val game = newGame(CpuDifficulty.MEDIUM, CLUSTERED_DEFENSE_LEVEL)
        game.startMatch()
        game.leaveSession()
        assertTrue(game.matchOver.value)

        val frozen = game.state.value
        game.tick(0.05f)
        game.placeTower(0)
        game.togglePause()
        game.playAgain()
        assertSame("every gameplay-mutating method must no-op once matchOver", frozen, game.state.value)
    }

    @Test
    fun `leaveSession reports the furthest wave reached this session and whether any run was won, and is idempotent`() {
        val game = newGame(CpuDifficulty.MEDIUM, SHORT_LEAK_LEVEL)
        game.startMatch()
        var reportedResult: com.gamesuite.core.GameResult? = null
        game.setOnMatchEnd { reportedResult = it }

        tickUntil(game) { game.state.value.runOver } // loses on SHORT_LEAK_LEVEL with no towers
        assertEquals(TowerDefenceRunResult.LOST, game.state.value.runResult)
        val waveReached = game.state.value.waveNumber

        game.leaveSession()
        val result = reportedResult
        assertNotNull(result)
        val score = result!!.scores.single()
        assertEquals(waveReached, score.score)
        assertFalse("no run in this session ever reached WON", score.isWinner)
        assertTrue(game.matchOver.value)

        reportedResult = null
        game.leaveSession() // matchOver guard -- must not double-report
        assertNull(reportedResult)
    }

    @Test
    fun `playAgain restarts the same level and difficulty only after the run has ended`() {
        val game = newGame(CpuDifficulty.HARD, TowerDefenceGame.LEVELS[1])
        game.startMatch()

        val beforeRunOver = game.state.value
        game.playAgain()
        assertSame("playAgain must no-op before the run has ended", beforeRunOver, game.state.value)

        tickUntil(game) { game.state.value.runOver }
        val runSeqBeforeReplay = game.state.value.runSeq
        game.playAgain()
        val s = game.state.value
        assertEquals(runSeqBeforeReplay + 1, s.runSeq)
        assertEquals(1, s.waveNumber)
        assertNull(s.runResult)
        assertEquals(CpuDifficulty.HARD, s.difficulty)
        assertEquals(TowerDefenceGame.LEVELS[1].id, s.level.id)
    }

    // -- positionAlongPath geometry --

    @Test
    fun `positionAlongPath interpolates along straight and multi-segment paths and returns null past the end`() {
        val straight = listOf(Offset(0f, 0f), Offset(1f, 0f))
        assertEquals(Offset(0f, 0f), TowerDefenceGame.positionAlongPath(straight, 0f))
        assertEquals(Offset(0.5f, 0f), TowerDefenceGame.positionAlongPath(straight, 0.5f))
        assertEquals(Offset(1f, 0f), TowerDefenceGame.positionAlongPath(straight, 1f))
        assertNull(TowerDefenceGame.positionAlongPath(straight, 1.01f))

        val bent = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f))
        assertEquals(Offset(1f, 0f), TowerDefenceGame.positionAlongPath(bent, 1f))
        assertEquals(Offset(1f, 0.5f), TowerDefenceGame.positionAlongPath(bent, 1.5f))
        assertNull(TowerDefenceGame.positionAlongPath(bent, 2.01f))
    }

    @Test
    fun `every built-in level's path has at least two waypoints and at least one tower zone`() {
        for (level in TowerDefenceGame.LEVELS) {
            assertTrue("level ${level.id} needs a real path", level.path.size >= 2)
            assertTrue("level ${level.id} needs at least one tower zone", level.towerZones.isNotEmpty())
        }
        assertEquals(3, TowerDefenceGame.LEVELS.map { it.id }.distinct().size)
    }
}
