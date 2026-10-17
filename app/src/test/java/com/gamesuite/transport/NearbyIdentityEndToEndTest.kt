package com.gamesuite.transport

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.games.uno.UnoCard
import com.gamesuite.games.uno.UnoColor
import com.gamesuite.games.uno.UnoGame
import com.gamesuite.games.uno.UnoIntentPayload
import com.gamesuite.games.uno.UnoNetMessage
import com.gamesuite.games.uno.UnoRank
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole Nearby path with the REAL UNO engine, built from the same objects the lobby screens use:
 * guests' JoinRequests -> [NearbyRoster] -> [NearbyRoster.launch] (the player list, the mapping, each guest's
 * GameStart) -> [NearbyGameRouter] (what NearbyConnectionsTransport stamps `fromPlayerId` from) -> a payload
 * arriving from an endpoint -> [UnoGame]'s intent handling; and on the guest side [resolveForGuest].
 *
 * Before the fix a guest proposing "host" was stamped "host", so its intents passed UNO's "sender must equal
 * the seat" check as seat 0. [controlTheOldLobbyMappingWouldHaveLetTheAttackerPlayTheHostsCard] reproduces that
 * mapping to show the scenario really is sensitive to the stamp.
 *
 * What this does NOT cover: the Compose screens and the Android Nearby client themselves (they can't run in a
 * JVM unit test). Every identity decision they make is delegated to the code exercised here, and they are
 * compile-verified glue over it; they have not been run on two physical devices for this change.
 */
class NearbyIdentityEndToEndTest {

    /** A [MultiplayerTransport] that records what the host sends and lets a test deliver inbound bytes. */
    private class RecordingTransport : MultiplayerTransport {
        data class Sent(val fromPlayerId: String, val toPlayerId: String?, val payload: ByteArray)

        val sent = mutableListOf<Sent>()
        private var listener: ((String, ByteArray) -> Unit)? = null

        override fun connect() {}
        override fun disconnect() {}
        override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {
            sent += Sent(fromPlayerId, toPlayerId, payload)
        }
        override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) { this.listener = listener }
        override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
        override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}

        fun deliver(fromPlayerId: String, message: UnoNetMessage) {
            listener!!.invoke(fromPlayerId, Json.encodeToString(message).toByteArray(Charsets.UTF_8))
        }
    }

    private class Fixture(makeRouter: (NearbyLaunch) -> NearbyGameRouter) {
        private var minted = 0

        // ep-honest is a normal guest; ep-attacker proposes the reserved host id.
        val launch: NearbyLaunch = NearbyRoster { "p-minted-${++minted}" }
            .withJoinRequest("ep-honest", NearbyLobbyMessage.JoinRequest("p-honest", "Honest"))
            .withJoinRequest("ep-attacker", NearbyLobbyMessage.JoinRequest("host", "Mallory"))
            .launch("uno")
        val router: NearbyGameRouter = makeRouter(launch)
        val transport = RecordingTransport()
        val game = UnoGame()

        val hostCard = UnoCard(UnoColor.RED, UnoRank.SEVEN, 901)
        val honestCard = UnoCard(UnoColor.RED, UnoRank.NINE, 902)
        private val topCard = UnoCard(UnoColor.RED, UnoRank.FIVE, 900)

        init {
            game.init(GameContext(PlayMode.LOCAL_AD_HOC, launch.players, localPlayerIndex = 0, transport = transport))
            game.startMatch()
            setTable(currentPlayerIndex = 0)
        }

        /** The table with a known, legal card in the host's and the honest guest's hands, [currentPlayerIndex] to move. */
        fun setTable(currentPlayerIndex: Int) {
            val s = game.state.value!!
            game.state.value = s.copy(
                players = s.players.mapIndexed { i, p ->
                    when (i) {
                        0 -> p.copy(hand = listOf(hostCard) + p.hand.filter { it.instanceId != 901 && it.instanceId != 902 })
                        1 -> p.copy(hand = listOf(honestCard) + p.hand.filter { it.instanceId != 901 && it.instanceId != 902 })
                        else -> p
                    }
                },
                discardPile = listOf(topCard),
                currentColor = UnoColor.RED,
                currentPlayerIndex = currentPlayerIndex,
                direction = 1,
                pendingDraw = 0,
                awaitingColorChoice = false,
                awaitingChallenge = false,
            )
        }

        /** Delivers [intent] the way NearbyConnectionsTransport does: stamped from the ENDPOINT it arrived on. */
        fun receiveFrom(endpointId: String, intent: UnoIntentPayload) {
            val stamp = router.playerIdFor(endpointId) ?: return // unmapped endpoints are dropped
            transport.deliver(stamp, UnoNetMessage.Intent(intent))
        }

        fun discardTopId() = game.state.value!!.discardPile.last().instanceId
        fun hand(seat: Int) = game.state.value!!.players[seat].hand
    }

    /** The host as the app builds it: the launch plan's mapping, installed with the host's reserved-id rule. */
    private fun fixed() = Fixture { launch -> NearbyGameRouter().apply { install(launch.endpointToPlayerId, isHost = true) } }

    // ---- the attack, now refused ----

    @Test
    fun `a guest that claimed the host id is stamped as its own seat, not as the host`() {
        val f = fixed()
        assertEquals("p-honest", f.router.playerIdFor("ep-honest"))
        val attackerStamp = f.router.playerIdFor("ep-attacker")!!
        assertNotEquals("host", attackerStamp)
        assertEquals(listOf("host", "p-honest", attackerStamp), f.launch.players.map { it.playerId })
    }

    @Test
    fun `the impersonating guest cannot play the hosts card`() {
        val f = fixed()

        f.receiveFrom("ep-attacker", UnoIntentPayload.PlayCard(playerIndex = 0, cardInstanceId = f.hostCard.instanceId))

        assertEquals("the discard pile must be untouched", 900, f.discardTopId())
        assertTrue("the host still holds its card", f.hand(0).any { it.instanceId == f.hostCard.instanceId })
        assertEquals("still the host's turn", 0, f.game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `the impersonating guest cannot draw for the host either`() {
        val f = fixed()
        val handSize = f.hand(0).size

        f.receiveFrom("ep-attacker", UnoIntentPayload.DrawCard(playerIndex = 0))

        assertEquals(handSize, f.hand(0).size)
    }

    @Test
    fun `the impersonating guest cannot act for another guest's seat`() {
        val f = fixed()
        f.setTable(currentPlayerIndex = 1)

        f.receiveFrom("ep-attacker", UnoIntentPayload.PlayCard(playerIndex = 1, cardInstanceId = f.honestCard.instanceId))

        assertEquals(900, f.discardTopId())
        assertTrue(f.hand(1).any { it.instanceId == f.honestCard.instanceId })
    }

    @Test
    fun `a state request from the impersonator is answered with the host's hand hidden`() {
        val f = fixed()
        val attackerStamp = f.router.playerIdFor("ep-attacker")!!
        f.transport.sent.clear()

        f.transport.deliver(attackerStamp, UnoNetMessage.RequestState)

        val reply = f.transport.sent.single { it.toPlayerId == attackerStamp }
        val sync = Json.decodeFromString<UnoNetMessage>(String(reply.payload, Charsets.UTF_8)) as UnoNetMessage.StateSync
        assertTrue("the host's real cards must be redacted for the attacker's seat", sync.state.players[0].hand.all { it.instanceId == -1 })
        assertTrue("the honest guest's real cards must be redacted too", sync.state.players[1].hand.all { it.instanceId == -1 })
        assertTrue("its own hand is real", sync.state.players[2].hand.none { it.instanceId == -1 })
    }

    // ---- honest play still works through the very same path ----

    @Test
    fun `an honest guest's move is applied, stamped from its own endpoint`() {
        val f = fixed()
        f.setTable(currentPlayerIndex = 1)

        f.receiveFrom("ep-honest", UnoIntentPayload.PlayCard(playerIndex = 1, cardInstanceId = f.honestCard.instanceId))

        assertEquals(902, f.discardTopId())
        assertFalse(f.hand(1).any { it.instanceId == f.honestCard.instanceId })
    }

    @Test
    fun `the host's own moves are unaffected`() {
        val f = fixed()

        f.game.playCard(0, f.hostCard)

        assertEquals(901, f.discardTopId())
    }

    @Test
    fun `an endpoint with no seat is dropped before it reaches the game`() {
        val f = fixed()

        f.receiveFrom("ep-late-joiner", UnoIntentPayload.PlayCard(playerIndex = 0, cardInstanceId = f.hostCard.instanceId))

        assertEquals(900, f.discardTopId())
    }

    // ---- the guest side, from the very messages the host sends ----

    @Test
    fun `each guest resolves its own seat from the GameStart the host sent it, and none of them is seat 0`() {
        val f = fixed()
        val proposals = mapOf("ep-honest" to "p-honest", "ep-attacker" to "host") // what each guest sent as its clientPlayerId

        for ((endpointId, start) in f.launch.startMessages) {
            // On the guest's own device the host is whatever endpoint id THAT device sees; here just a label.
            val resolved = start.resolveForGuest("ep-host-as-seen-by-guest", hostEndpointId = "ep-host-as-seen-by-guest", clientPlayerId = proposals.getValue(endpointId))
            assertNotNull("$endpointId must find a seat", resolved)
            assertTrue("$endpointId must not be told it is the host", resolved!!.localPlayerIndex > 0)
            assertEquals("the guest's only mapping: its connection is seat 0 (the host)", mapOf("ep-host-as-seen-by-guest" to "host"), resolved.hostEndpointToPlayerId)
        }
        val indexes = f.launch.startMessages.map { (endpoint, start) ->
            start.resolveForGuest("h", "h", mapOf("ep-honest" to "p-honest", "ep-attacker" to "host").getValue(endpoint))!!.localPlayerIndex
        }
        assertEquals("every guest gets a different seat", listOf(1, 2), indexes)
    }

    @Test
    fun `the impersonator, told its real id, is not confused with the host`() {
        val f = fixed()
        val (_, start) = f.launch.startMessages.single { it.first == "ep-attacker" }

        assertNotEquals("host", start.yourPlayerId)
        assertNotNull("its proposal was replaced, so it is told the real id", start.yourPlayerId)
        // Finding itself by the id it PROPOSED would mean seat 0: never believed.
        val byProposal = start.resolveForGuest("h", "h", clientPlayerId = "host")
        assertEquals("yourPlayerId wins over the proposal, so the guest is seated as itself", start.yourPlayerId, f.launch.players[byProposal!!.localPlayerIndex].playerId)
    }

    // ---- control: the pre-fix behaviour, to prove the scenario detects the hole ----

    @Test
    fun `control - the old lobby mapping would have let the attacker play the hosts card`() {
        // What NearbyHostLobbyScreen used to build: the guest's own claim, verbatim. (Installed with the
        // guest-side rule, since the host-side rule now refuses exactly this.)
        val f = Fixture { launch ->
            NearbyGameRouter().apply { install(launch.endpointToPlayerId + ("ep-attacker" to "host"), isHost = false) }
        }

        f.receiveFrom("ep-attacker", UnoIntentPayload.PlayCard(playerIndex = 0, cardInstanceId = f.hostCard.instanceId))

        assertEquals("with the forged stamp the engine accepts it: the stamp is the whole defence", 901, f.discardTopId())
    }

    @Test
    fun `control - and the host-side router refuses to install that mapping`() {
        val e = runCatching {
            NearbyGameRouter().install(mapOf("ep-attacker" to "host", "ep-honest" to "p-honest"), isHost = true)
        }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $e", e is IllegalArgumentException)
    }
}
