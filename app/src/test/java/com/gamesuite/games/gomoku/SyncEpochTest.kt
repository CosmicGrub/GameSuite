package com.gamesuite.games.gomoku

import com.gamesuite.core.PlayMode
import com.gamesuite.core.SyncEpoch
import com.gamesuite.games.gomoku.TestSeats.GUEST
import com.gamesuite.games.gomoku.TestSeats.HOST
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Review finding 7 (epoch half): a host whose game object is recreated is not ignored by a guest that kept its counter. */
class SyncEpochTest {
    private val n = GOMOKU_SIZE

    // The build turns epochs off for the unit tests (hand-written syncs use small versions); these tests need them.
    @Before fun epochsOn() { SyncEpoch.enabled = true }
    @After fun epochsOff() { SyncEpoch.enabled = false }

    @Test fun everyNewBaseIsStrictlyHigherAndPositive() {
        var last = -1
        repeat(200) {
            val b = SyncEpoch.base()
            assertTrue("$b !> $last", b > last)
            assertTrue(b >= 0)
            last = b
        }
        assertTrue(SyncEpoch.epochOf(last) in 1..2047)
    }

    private fun syncsOf(t: FakeTransport) =
        t.sentSince(0).map { Json.decodeFromString(GomokuNetMessage.serializer(), it.text) }.filterIsInstance<GomokuNetMessage.StateSync>()

    private fun host(firstStone: Int): Pair<GomokuGame, FakeTransport> {
        val hub = FakeHub()
        val ht = hub.endpoint(HOST); hub.endpoint(GUEST)
        val g = GomokuGame()
        g.init(TestSeats.context(PlayMode.LOCAL_AD_HOC, TestSeats.humans, 0, ht))
        g.startMatch()
        g.placeStone(firstStone)
        return g to ht
    }

    @Test fun aGuestThatKeptItsCounterAcceptsARestartedHost() {
        val (_, ht1) = host(7 * n + 7)
        val old = syncsOf(ht1).last()
        // a guest that has followed host #1 through a long match
        val hub = FakeHub()
        val gt = hub.endpoint(GUEST); hub.endpoint(HOST)
        val guest = GomokuGame()
        guest.init(TestSeats.context(PlayMode.LOCAL_AD_HOC, TestSeats.humans, 1, gt))
        gt.inject(HOST, Json.encodeToString(GomokuNetMessage.serializer(), GomokuNetMessage.StateSync(old.version + 5000, old.state)))
        val seenBefore = guest.state.value!!

        // host #2: a brand new game object (the old one was dropped), same room
        val (_, ht2) = host(3 * n + 3)
        val fresh = syncsOf(ht2).last()
        assertTrue("the new host must start above the old host's epoch", fresh.version > old.version)
        gt.inject(HOST, Json.encodeToString(GomokuNetMessage.serializer(), fresh))
        assertNotEquals(seenBefore, guest.state.value)
        assertEquals(fresh.state.lastMove, guest.state.value!!.lastMove)
    }
}
