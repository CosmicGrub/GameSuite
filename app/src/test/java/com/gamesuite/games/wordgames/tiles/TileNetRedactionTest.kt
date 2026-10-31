package com.gamesuite.games.wordgames.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hidden information: what the host puts on the wire for the guest must contain the guest's OWN rack
 * and only the COUNT of the host's rack and of the bag. The full tile set has ids 0..99, so any id
 * that appears in a guest-bound payload but is neither on the board nor in the guest's own rack would
 * be a leaked host tile or a leaked bag tile.
 */
class TileNetRedactionTest {

    private val allTileIds = (0 until 100).toSet()
    private val instanceIdPattern = Regex("\"instanceId\":(\\d+)")

    private fun idsIn(text: String): Set<Int> = instanceIdPattern.findAll(text).map { it.groupValues[1].toInt() }.toSet()

    @Test
    fun `the guest-bound state holds only the guest's rack, the board, and counts`() {
        val p = TileTestKit.pair()
        val hostRackIds = p.host.s.players[0].rack.map { it.instanceId }.toSet()
        val guestRackIds = p.host.s.players[1].rack.map { it.instanceId }.toSet()
        val bagIds = allTileIds - hostRackIds - guestRackIds

        val toGuest = p.host.transport.sent.filter { it.to == "p1" }
        assertTrue(toGuest.isNotEmpty())
        for (sent in toGuest) {
            val net = sent.stateSync.state
            assertEquals(1, net.seat)
            assertEquals(p.host.s.players[1].rack.map { TileNetTile(it.instanceId, it.letter.toString(), it.isBlank) }, net.myRack)
            assertEquals(listOf(7, 7), net.rackCounts)
            assertEquals(p.host.s.bagCount, net.bagCount)
            assertTrue(bagIds.size == net.bagCount)

            val onWire = idsIn(sent.text)
            assertTrue("a host rack tile leaked: ${onWire intersect hostRackIds}", (onWire intersect hostRackIds).isEmpty())
            assertTrue("a bag tile leaked: ${onWire intersect bagIds}", (onWire intersect bagIds).isEmpty())
            assertEquals(guestRackIds, onWire)
            assertFalse("no bag contents field", sent.text.contains("\"bag\""))
            assertFalse("no pending field", sent.text.contains("pending"))
        }
    }

    @Test
    fun `after plays the board is public but the racks and bag stay hidden`() = TileTestKit.withWords("cat", "cats") {
        val p = TileTestKit.pair()
        p.setRacks(TileTestKit.tiles("CATQZJX", 900), TileTestKit.tiles("SEIOULN", 920))
        val rack = p.host.s.players[0].rack
        p.host.game.stageTile(7, 7, rack[0], 'C')
        p.host.game.stageTile(7, 8, rack[1], 'A')
        p.host.game.stageTile(7, 9, rack[2], 'T')
        assertTrue(p.host.game.submitMove() == null)

        val hostRackIds = p.host.s.players[0].rack.map { it.instanceId }.toSet()
        val boardIds = setOf(900, 901, 902)
        val guestRackIds = p.host.s.players[1].rack.map { it.instanceId }.toSet()
        val last = p.host.transport.sent.last { it.to == "p1" }
        val onWire = idsIn(last.text)
        assertEquals(boardIds + guestRackIds, onWire)
        assertTrue((onWire intersect hostRackIds).isEmpty())
        // The guest's own engine state agrees: its view of the host's rack is only placeholders.
        assertTrue(p.guest.s.players[0].rack.none { it.instanceId in hostRackIds })
        assertEquals(hostRackIds.size, p.guest.s.players[0].rack.size)
    }

    @Test
    fun `each seat is sent its own view, never another seat's rack`() {
        val p = TileTestKit.pair()
        val net = p.host.transport.sent.last { it.to == "p1" }.stateSync.state
        assertEquals(1, net.seat)
        // The host's own letters are not representable in what it sends: only seat 1's rack is there.
        val hostLetters = p.host.s.players[0].rack.map { it.instanceId }
        assertTrue(net.myRack.none { it.instanceId in hostLetters })
    }
}
