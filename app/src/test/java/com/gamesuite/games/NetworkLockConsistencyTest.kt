package com.gamesuite.games

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.fishing.FishingGame
import com.gamesuite.games.matching.MatchingGame
import com.gamesuite.games.slotcars.SlotCarsGame
import com.gamesuite.games.speed.SmFakeTransport
import com.gamesuite.games.speed.SpeedGame
import com.gamesuite.games.toysoccer.ToySoccerGame
import com.gamesuite.games.toytennis.ToyTennisGame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Review finding 2: these games declare @Synchronized methods (monitor = the game) but their network callbacks
 * took a separate private `lock` object, so a network handler ran concurrently with advance/tick/restore. The
 * handlers must share the game's own monitor: while another thread holds it, a network callback must wait.
 */
class NetworkLockConsistencyTest {

    private fun cases(): List<Pair<String, () -> GameModule>> = listOf(
        "fishing" to { FishingGame(Random(1)) { 1000L } },
        "matching" to { MatchingGame(Random(1)) { 1000L } },
        "slotcars" to { SlotCarsGame(Random(1)) { 1000L } },
        "speed" to { SpeedGame(Random(1)) { 1000L } },
        "toysoccer" to { ToySoccerGame(Random(1)) { 1000L } },
        "toytennis" to { ToyTennisGame(Random(1)) { 1000L } }
    )

    @Test
    fun `network callbacks wait for the games own monitor`() {
        for ((name, make) in cases()) {
            val game = make()
            val transport = SmFakeTransport()
            val roster = listOf(PlayerInfo("h", "A"), PlayerInfo("g", "B"))
            game.init(GameContext(PlayMode.LOCAL_AD_HOC, roster, 0, transport))

            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread { synchronized(game) { held.countDown(); release.await(10, TimeUnit.SECONDS) } }
            holder.start()
            assertTrue(held.await(5, TimeUnit.SECONDS))

            val done = CountDownLatch(1)
            val net = Thread { transport.fireLeft("g"); done.countDown() }
            net.start()
            val finishedWhileHeld = done.await(300, TimeUnit.MILLISECONDS)
            release.countDown()
            assertTrue("$name: onPlayerLeft ran while another thread held the game's monitor", !finishedWhileHeld)
            assertTrue("$name: onPlayerLeft never completed after release", done.await(5, TimeUnit.SECONDS))
            holder.join(); net.join()
        }
    }
}
