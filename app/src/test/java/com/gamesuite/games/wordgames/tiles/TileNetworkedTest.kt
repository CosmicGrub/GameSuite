package com.gamesuite.games.wordgames.tiles

import com.gamesuite.core.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-authoritative Word Tiles over a fake transport: deal and sync, the guest's three request kinds
 * (play, swap, pass) and the host's refusals, reconnect / RequestState, a forged or stale StateSync,
 * the opponent leaving (no result recorded), and Play Again. Plays need a word list, so those tests
 * install a tiny fake one with [TileTestKit.withWords].
 */
class TileNetworkedTest {

    private val hostRack get() = TileTestKit.tiles("CATQZJX", 900)
    private val guestRack get() = TileTestKit.tiles("SEIOULN", 920)

    private fun TileTestKit.Pair2.hostPlaysCat() {
        val rack = host.s.players[0].rack
        host.game.stageTile(7, 7, rack[0], 'C')
        host.game.stageTile(7, 8, rack[1], 'A')
        host.game.stageTile(7, 9, rack[2], 'T')
        assertNull(host.game.submitMove())
    }

    // ---- deal and sync ----

    @Test
    fun `the host deals and the guest receives its own rack and nobody elses`() {
        val p = TileTestKit.pair()
        val g = p.guest.s
        assertEquals(p.host.s.players[1].rack, g.players[1].rack)
        assertEquals(7, g.players[0].rack.size)
        assertEquals(p.host.s.bagCount, g.bagCount)
        assertEquals(listOf("Host", "Guest"), g.players.map { it.displayName })
        assertFalse(g.players.any { it.isBot })
        assertTrue("the guest never rebuilds the host's letters", g.players[0].rack.none { it.letter in 'A'..'Z' })
        assertTrue(p.hub.senderLies.isEmpty())
    }

    @Test
    fun `supported modes include both networked modes and online play works the same way`() {
        assertTrue(TileGame().supportedModes.containsAll(listOf(PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE)))
        val p = TileTestKit.pair(PlayMode.ONLINE)
        assertNotNull(p.guest.game.state.value)
        assertEquals(p.host.s.currentPlayerIndex, p.guest.s.currentPlayerIndex)
    }

    @Test
    fun `a networked game is not resumable`() {
        val p = TileTestKit.pair()
        assertNull(p.host.game.snapshot())
        assertNull(p.guest.game.snapshot())
        val localSnapshot = TileGame().also {
            it.init(
                com.gamesuite.core.GameContext(
                    PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, TileTestKit.roster, 0,
                    com.gamesuite.transport.LocalPassAndPlayTransport()
                )
            )
            it.startMatch()
        }.snapshot()!!
        val before = p.host.game.state.value
        assertFalse(p.host.game.restore(localSnapshot))
        assertSame(before, p.host.game.state.value)
    }

    @Test
    fun `a guest ignores a StateSync from anyone but the host, a stale one, and a malformed one`() {
        val p = TileTestKit.pair()
        val last = p.host.transport.sent.last { it.to == "p1" }.stateSync
        val before = p.guest.game.state.value

        p.guest.transport.inject("p1", last.copy(version = last.version + 5, state = last.state.copy(lastAction = "forged")))
        p.guest.transport.inject("p0", last.copy(state = last.state.copy(lastAction = "stale")))
        p.guest.transport.inject("p0", last.copy(version = last.version + 6, state = last.state.copy(cells = emptyList())))
        p.guest.transport.inject("p0", last.copy(version = last.version + 7, state = last.state.copy(seat = 0)))
        p.guest.transport.inject("p0", "not json".toByteArray())
        assertSame(before, p.guest.game.state.value)

        p.guest.transport.inject("p0", last.copy(version = last.version + 8, state = last.state.copy(lastAction = "from the host")))
        assertEquals("from the host", p.guest.s.lastAction)
    }

    @Test
    fun `RequestState from a guest is answered and from the host's own seat or a stranger is not`() {
        val p = TileTestKit.pair()
        val mark = p.host.transport.mark()
        p.host.transport.inject("p0", TileNetMessage.RequestState)
        p.host.transport.inject("nobody", TileNetMessage.RequestState)
        assertTrue(p.host.transport.sentSince(mark).isEmpty())

        p.host.transport.inject("p1", TileNetMessage.RequestState)
        val reply = p.host.transport.sentSince(mark).single()
        assertEquals("p1", reply.to)
        assertEquals(1, reply.stateSync.state.seat)
    }

    @Test
    fun `a reconnect makes the guest ask again and the host re-send a newer state`() {
        val p = TileTestKit.pair()
        val mark = p.guest.transport.mark()
        p.guest.transport.fireReconnected()
        assertTrue(p.guest.transport.sentSince(mark).single().message is TileNetMessage.RequestState)

        val versionBefore = p.host.transport.sent.last { it.to == "p1" }.stateSync.version
        p.host.transport.fireReconnected()
        assertTrue(p.host.transport.sent.last { it.to == "p1" }.stateSync.version > versionBefore)
    }

    // ---- plays ----

    @Test
    fun `host plays then the guest extends the word through a request the host applies`() = TileTestKit.withWords("cat", "cats") {
        val p = TileTestKit.pair()
        p.setRacks(hostRack, guestRack)
        val plays = mutableListOf<WordPlayResult>()
        p.guest.game.setOnWordPlayed { plays += it }

        p.hostPlaysCat()
        assertEquals(1, p.guest.s.currentPlayerIndex)
        assertEquals(p.host.s.board, p.guest.s.board)
        assertEquals(7, p.guest.s.players[0].rack.size)
        assertEquals(p.host.s.players.map { it.score }, p.guest.s.players.map { it.score })
        assertEquals("the guest is told about the host's play", 1, plays.size)
        assertEquals(0, plays[0].playerIndex)

        val s = p.guest.s.players[1].rack.first { it.letter == 'S' }
        p.guest.game.stageTile(7, 10, s, 'S')
        assertNull(p.guest.game.submitMove())
        val request = p.guest.transport.sent.last { it.message is TileNetMessage.PlayRequest }.message as TileNetMessage.PlayRequest
        assertEquals(listOf(TileNetPlacement(7, 10, s.instanceId, "S")), request.placements)
        assertEquals(listOf("CATS"), request.words)

        assertEquals('S', p.host.s.board[7][10].effectiveLetter)
        assertEquals(0, p.host.s.currentPlayerIndex)
        assertTrue(p.host.s.players[1].score > 0)
        assertEquals(7, p.host.s.players[1].rack.size)
        assertEquals(p.host.s.board, p.guest.s.board)
        assertEquals(p.host.s.players.map { it.score }, p.guest.s.players.map { it.score })
        assertTrue(p.guest.s.pending.isEmpty())
        assertEquals(2, plays.size)
        assertEquals(1, plays[1].playerIndex)
        assertTrue(p.hub.senderLies.isEmpty())
    }

    @Test
    fun `a guest tap that is not its turn stages nothing and sends nothing`() = TileTestKit.withWords("cat") {
        val p = TileTestKit.pair()
        p.setRacks(hostRack, guestRack, current = 0)
        val mark = p.guest.transport.mark()
        val tile = p.guest.s.players[1].rack.first()
        p.guest.game.stageTile(7, 7, tile, tile.letter)
        p.guest.game.pass()
        p.guest.game.swapTiles(setOf(tile.instanceId))
        assertEquals("It is not your turn", p.guest.game.submitMove())
        assertTrue(p.guest.s.pending.isEmpty())
        assertTrue(p.guest.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun `the host refuses a forged or illegal play request and re-syncs the sender`() = TileTestKit.withWords("so", "cat") {
        val p = TileTestKit.pair()
        p.setRacks(hostRack, guestRack, current = 1)
        val before = p.host.game.state.value
        val mark = p.host.transport.mark()

        fun forge(placements: List<TileNetPlacement>, words: List<String>) {
            p.host.transport.inject("p1", TileNetMessage.PlayRequest(placements, words))
            assertSame("a refused play changes nothing", before, p.host.game.state.value)
        }
        val s = TileNetPlacement(7, 7, 920, "S")
        val o = TileNetPlacement(7, 8, 923, "O")
        forge(listOf(s, o), listOf("cat"))                                                         // claims the wrong word
        forge(listOf(TileNetPlacement(7, 7, 900, "C"), o), listOf("CO"))                           // a tile from the HOST's rack
        forge(listOf(TileNetPlacement(7, 7, 5000, "S"), o), listOf("SO"))                          // a tile nobody holds
        forge(listOf(TileNetPlacement(7, 7, 920, "Z"), o), listOf("ZO"))                           // wrong letter for a normal tile
        forge(listOf(s, TileNetPlacement(7, 8, 920, "S")), listOf("SS"))                           // the same tile twice
        forge(listOf(s, TileNetPlacement(7, 7, 923, "O")), listOf("O"))                            // the same square twice
        forge(listOf(TileNetPlacement(99, 7, 920, "S"), o), listOf("SO"))                          // off the board
        forge(listOf(TileNetPlacement(7, 7, 920, "S"), TileNetPlacement(7, 8, 921, "E")), listOf("SE")) // not in the dictionary
        forge(listOf(TileNetPlacement(0, 0, 920, "S"), TileNetPlacement(0, 1, 923, "O")), listOf("SO")) // misses the centre
        forge(emptyList(), emptyList())
        val replies = p.host.transport.sentSince(mark).filter { it.to == "p1" }
        assertTrue("the guest is brought back to the real state", replies.isNotEmpty())
        assertEquals(replies.map { it.stateSync.version }, replies.map { it.stateSync.version }.sorted())

        // The honest request goes through.
        p.host.transport.inject("p1", TileNetMessage.PlayRequest(listOf(s, o), listOf("SO")))
        assertEquals('S', p.host.s.board[7][7].effectiveLetter)
        assertEquals(0, p.host.s.currentPlayerIndex)
    }

    @Test
    fun `a request from the host's own seat, a stranger or the wrong turn is ignored`() = TileTestKit.withWords("so") {
        val p = TileTestKit.pair()
        p.setRacks(hostRack, guestRack, current = 0)
        val before = p.host.game.state.value
        val request = TileNetMessage.PlayRequest(
            listOf(TileNetPlacement(7, 7, 920, "S"), TileNetPlacement(7, 8, 923, "O")), listOf("SO")
        )
        p.host.transport.inject("p0", request)
        p.host.transport.inject("stranger", request)
        p.host.transport.inject("p1", request)          // it is the host's turn
        p.host.transport.inject("p1", TileNetMessage.PassRequest)
        p.host.transport.inject("p1", TileNetMessage.SwapRequest(listOf(920)))
        assertSame(before, p.host.game.state.value)
    }

    @Test
    fun `the host's own staged tiles survive a guest's play`() = TileTestKit.withWords("so", "sox") {
        val p = TileTestKit.pair()
        p.setRacks(hostRack, guestRack, current = 1)
        // The host stages while it waits (the UI forbids it, but the engine must not lose it if it happens).
        p.host.game.state.value = p.host.s.copy(
            pending = listOf(PendingPlacement(0, 0, p.host.s.players[0].rack[0], 'C'))
        )
        p.host.transport.inject(
            "p1",
            TileNetMessage.PlayRequest(
                listOf(TileNetPlacement(7, 7, 920, "S"), TileNetPlacement(7, 8, 923, "O")), listOf("SO")
            )
        )
        assertEquals(1, p.host.s.pending.size)
        assertEquals(0, p.host.s.currentPlayerIndex)
    }

    // ---- swap and pass ----

    @Test
    fun `a guest swap trades tiles for new ones and counts as a pass`() {
        val p = TileTestKit.pair()
        p.setRacks(hostRack, guestRack, current = 1)
        p.guest.game.swapTiles(setOf(920))
        assertEquals(0, p.host.s.currentPlayerIndex)
        assertEquals(1, p.host.s.consecutivePasses)
        val rack = p.host.s.players[1].rack
        assertEquals(7, rack.size)
        assertTrue(rack.none { it.instanceId == 920 })
        assertEquals(rack, p.guest.s.players[1].rack)
        assertEquals(1, p.guest.s.consecutivePasses)
        assertEquals("Guest swapped 1 tile(s)", p.guest.s.lastAction)
    }

    @Test
    fun `the host refuses a swap of tiles the sender does not hold, repeated tiles, or none`() {
        val p = TileTestKit.pair()
        p.setRacks(hostRack, guestRack, current = 1)
        val before = p.host.game.state.value
        p.host.transport.inject("p1", TileNetMessage.SwapRequest(listOf(5000)))
        p.host.transport.inject("p1", TileNetMessage.SwapRequest(listOf(900)))        // the host's tile
        p.host.transport.inject("p1", TileNetMessage.SwapRequest(listOf(920, 920)))
        p.host.transport.inject("p1", TileNetMessage.SwapRequest(emptyList()))
        assertSame(before, p.host.game.state.value)
    }

    @Test
    fun `a guest pass is applied by the host and handed back`() {
        val p = TileTestKit.pair()
        p.setRacks(hostRack, guestRack, current = 1)
        p.guest.game.pass()
        assertTrue(p.guest.transport.sent.last().message is TileNetMessage.PassRequest)
        assertEquals(0, p.host.s.currentPlayerIndex)
        assertEquals("Guest passed", p.host.s.lastAction)
        assertEquals(0, p.guest.s.currentPlayerIndex)
    }

    // ---- ending, Play Again, leaving ----

    @Test
    fun `passes end the game for both seats without ending the session, and Play Again deals a new one`() {
        val p = TileTestKit.pair()
        repeat(2) { p.host.game.pass(); p.guest.game.pass() }
        assertTrue(p.host.s.matchOver)
        assertTrue(p.guest.s.matchOver)
        assertTrue("the session is still open", p.host.results.isEmpty() && p.guest.results.isEmpty())
        assertEquals(1, p.host.game.sessionDraws.value)
        assertEquals(1, p.guest.game.sessionDraws.value)

        p.guest.game.playAgain()
        assertFalse(p.host.s.matchOver)
        assertFalse(p.guest.s.matchOver)
        assertEquals("the opener alternates", 1, p.host.s.currentPlayerIndex)
        assertEquals(1, p.guest.s.currentPlayerIndex)
        assertEquals(7, p.guest.s.players[1].rack.size)
        assertTrue(p.guest.s.board.all { row -> row.all { it.tile == null } })

        // A second request while a game is running must not wipe it.
        val live = p.host.game.state.value
        p.host.transport.inject("p1", TileNetMessage.PlayAgainRequest)
        p.host.game.playAgain()
        assertSame(live, p.host.game.state.value)
    }

    @Test
    fun `leaving reports the session tally with a strict leader as the winner`() {
        val p = TileTestKit.pair()
        val s = p.host.s
        p.host.game.state.value = s.copy(players = s.players.mapIndexed { i, pl -> if (i == 0) pl.copy(score = 10) else pl })
        p.host.transport.fireReconnected()
        repeat(2) { p.host.game.pass(); p.guest.game.pass() }
        assertEquals(1, p.host.game.sessionWins.value["p0"])
        assertEquals(1, p.guest.game.sessionWins.value["p0"])

        p.host.game.leaveSession()
        val result = p.host.results.single()
        assertFalse(result.wasAborted)
        assertEquals(mapOf("p0" to true, "p1" to false), result.scores.associate { it.playerId to it.isWinner })

        p.guest.game.leaveSession()
        assertEquals(mapOf("p0" to true, "p1" to false), p.guest.results.single().scores.associate { it.playerId to it.isWinner })
    }

    @Test
    fun `a drawn session has no winner`() {
        val p = TileTestKit.pair()
        repeat(2) { p.host.game.pass(); p.guest.game.pass() }
        p.host.game.leaveSession()
        val result = p.host.results.single()
        assertFalse(result.wasAborted)
        assertTrue(result.scores.none { it.isWinner })
    }

    @Test
    fun `when the opponent leaves input stops and leaving records nothing`() {
        val p = TileTestKit.pair()
        p.host.transport.fireLeft("p1")
        assertTrue(p.host.game.opponentLeft.value)
        assertEquals("Opponent disconnected", p.host.s.lastAction)
        p.host.game.pass()
        assertEquals(0, p.host.s.currentPlayerIndex)

        p.host.game.leaveSession()
        val result = p.host.results.single()
        assertTrue(result.wasAborted)
        assertTrue(result.scores.isEmpty())
    }

    @Test
    fun `a guest sees the host leave, and a rejoin clears it and re-sends`() {
        val p = TileTestKit.pair()
        p.guest.transport.fireLeft("p0")
        assertTrue(p.guest.game.opponentLeft.value)
        assertEquals("Opponent disconnected", p.guest.s.lastAction)

        p.host.transport.fireLeft("p1")
        val mark = p.host.transport.mark()
        p.host.transport.fireJoined("p1")
        assertFalse(p.host.game.opponentLeft.value)
        assertTrue(p.host.transport.sentSince(mark).any { it.to == "p1" })
    }

    @Test
    fun `an abort mid-game reports an aborted result with no scores`() {
        val p = TileTestKit.pair()
        p.guest.game.abortMatch()
        val result = p.guest.results.single()
        assertTrue(result.wasAborted)
        assertTrue(result.scores.isEmpty())
    }
}
