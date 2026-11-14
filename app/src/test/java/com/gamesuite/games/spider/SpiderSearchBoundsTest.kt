package com.gamesuite.games.spider

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Review finding 5: the winnable-deal search is bounded, cancellable, and cannot be started twice. */
class SpiderSearchBoundsTest {
    private fun game(): SpiderGame = SpiderGame(Random(1)).also {
        it.suits = 4
        it.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("p1", "You")), 0, LocalPassAndPlayTransport()))
    }

    @Test fun aCancelledSearchStopsAtOnceAndReturnsTheHonestPlainDeal() {
        val polls = AtomicInteger()
        val deal = SpiderDealer.winnable(5L, 4, isCancelled = { polls.incrementAndGet(); true })
        assertFalse(deal.provenWinnable)
        assertEquals(SpiderDealer.plain(SpiderDealer.seededRandom(5L, 4), 4), deal.state)
        assertTrue("search kept polling after being cancelled: ${polls.get()}", polls.get() <= 3)
    }

    @Test fun theWallClockCapEndsTheSearchEvenWhenNothingCancels() {
        var t = 0L
        val deal = SpiderDealer.winnable(5L, 4, capMs = 50, nowNanos = { t += 10_000_000L; t }) // each clock read = 10 ms
        assertFalse(deal.provenWinnable)
        assertTrue(SpiderRules.isConsistent(deal.state))
        assertTrue("clock was read ${t / 10_000_000L} times", t / 10_000_000L < 200)
    }

    @Test fun aSecondNewDealWhileOneIsSearchingIsIgnored() = runBlocking {
        val g = game()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        g.dealer = { seed, suits, _ ->
            calls.incrementAndGet()
            entered.complete(Unit)
            runBlocking { release.await() }
            SpiderDealer.Deal(SpiderDealer.plain(Random(3), suits), provenWinnable = true)
        }
        val first = async(Dispatchers.Default) { g.newDeal(77L) }
        withTimeout(10_000) { entered.await() }
        g.newDeal(77L) // returns immediately: a search is in flight
        assertEquals(1, calls.get())
        release.complete(Unit)
        first.await()
        assertEquals(1, g.dealSeq.value)
        // and the guard is released afterwards
        g.dealer = { _, suits, _ -> SpiderDealer.Deal(SpiderDealer.plain(Random(4), suits), provenWinnable = true) }
        g.newDeal(78L)
        assertEquals(2, g.dealSeq.value)
    }

    @Test fun cancellingTheCallerStopsTheSearchAndCommitsNothing() = runBlocking {
        val g = game()
        val sawCancel = CompletableDeferred<Boolean>()
        val entered = CompletableDeferred<Unit>()
        g.dealer = { _, suits, isCancelled ->
            entered.complete(Unit)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!isCancelled() && System.nanoTime() < deadline) Thread.sleep(5)
            sawCancel.complete(isCancelled())
            SpiderDealer.Deal(SpiderDealer.plain(Random(3), suits), provenWinnable = false)
        }
        val job = launch(Dispatchers.Default) { g.newDeal(9L) }
        withTimeout(10_000) { entered.await() }
        job.cancelAndJoin()
        assertTrue("the search never saw the cancellation", withTimeout(10_000) { sawCancel.await() })
        assertEquals(0, g.dealSeq.value)
    }
}
