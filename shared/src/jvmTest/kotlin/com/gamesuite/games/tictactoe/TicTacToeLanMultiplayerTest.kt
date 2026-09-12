package com.gamesuite.games.tictactoe

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LanMultiplayerTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real end-to-end proof that TicTacToeGame's networked-play wiring (see
 * TicTacToeNetMessage.kt's own KDoc) actually works, not just that it compiles: two real
 * [TicTacToeGame] instances, each driven by its own real [LanMultiplayerTransport], talking
 * over actual loopback TCP -- exactly the shape two separate desktop processes (or a desktop
 * and an Android device) would use for a real LAN match. Lives in jvmTest (not commonTest)
 * for the same reason [LanMultiplayerTransport] itself lives in jvmMain: it needs java.net
 * sockets, which aren't part of Kotlin's common stdlib.
 */
class TicTacToeLanMultiplayerTest {

    private fun await(latch: CountDownLatch, message: String, seconds: Long = 5) {
        assertTrue(latch.await(seconds, TimeUnit.SECONDS), "Timed out waiting for: $message")
    }

    /** Polls [check] until it returns true or [seconds] elapse -- these are real background
     *  coroutines (LanMultiplayerTransport's read loops) delivering to real Compose state
     *  (TicTacToeGame's mutableStateOf fields), not something a single latch can await on a
     *  per-field basis, so a bounded poll is the correct tool here, same reasoning
     *  LanMultiplayerTransportTest's own discovery test already established. */
    private fun awaitCondition(message: String, seconds: Long = 5, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(20)
        }
        assertTrue(check(), "Timed out waiting for: $message")
    }

    private class ConnectedPair(val host: TicTacToeGame, val guest: TicTacToeGame, val hostTransport: LanMultiplayerTransport, val guestTransport: LanMultiplayerTransport) {
        fun disconnect() {
            hostTransport.disconnect()
            guestTransport.disconnect()
        }
    }

    /** Real transport connection (host opens a port, guest dials it over 127.0.0.1) plus two
     *  real TicTacToeGame instances in [PlayMode.LOCAL_AD_HOC], host initialized first so its
     *  message listener is registered before the guest's init() sends its initial
     *  [TicTacToeNetMessage.RequestState] -- getting that order backwards would drop the
     *  guest's very first request on the floor. */
    private fun setUpConnectedPair(): ConnectedPair {
        val hostTransport = LanMultiplayerTransport()
        val guestTransport = LanMultiplayerTransport()

        val hostedLatch = CountDownLatch(1)
        var hostPort = -1
        hostTransport.onHosted { port -> hostPort = port; hostedLatch.countDown() }
        hostTransport.hostGame("host-p", "Host")
        await(hostedLatch, "host to open its port")

        val hostJoinedLatch = CountDownLatch(1)
        hostTransport.onPlayerJoined { hostJoinedLatch.countDown() }
        val guestJoinedLatch = CountDownLatch(1)
        guestTransport.onJoinedLobby { guestJoinedLatch.countDown() }
        guestTransport.joinHost("127.0.0.1", hostPort, "guest-p", "Guest")
        await(guestJoinedLatch, "guest to join the lobby")
        await(hostJoinedLatch, "host to see the guest register")

        val players = listOf(
            PlayerInfo(playerId = "host-p", displayName = "Host", isBot = false),
            PlayerInfo(playerId = "guest-p", displayName = "Guest", isBot = false)
        )
        val hostGame = TicTacToeGame()
        val guestGame = TicTacToeGame()

        hostGame.init(GameContext(activeMode = PlayMode.LOCAL_AD_HOC, players = players, localPlayerIndex = 0, transport = hostTransport))
        guestGame.init(GameContext(activeMode = PlayMode.LOCAL_AD_HOC, players = players, localPlayerIndex = 1, transport = guestTransport))
        hostGame.startMatch()
        guestGame.startMatch()

        // Confirms the initial sync round-trip actually completed (guest's RequestState
        // reached the host, and the host's reply reached back) before any test below starts
        // asserting things about a specific move -- the guest's board starts as 9 zeros
        // either way, so this can't be verified by inspecting board contents; roundNumber
        // is always 1 at this point too. What IS a genuine, unambiguous signal: the guest's
        // own lastAppliedStateVersion having moved off its initial -1 -- exposed here via a
        // real move-and-check instead of reaching into that private field.
        hostGame.cellClicked(4)
        awaitCondition("guest to receive the host's opening move") { guestGame.board.value[4] == 1 }

        return ConnectedPair(hostGame, guestGame, hostTransport, guestTransport)
    }

    @Test
    fun hostsMoveAppliesLocallyAndPropagatesToGuest() {
        val pair = setUpConnectedPair()
        try {
            // setUpConnectedPair's own sync check already played host's cell 4 -- confirm
            // both sides agree on the resulting turn order too, not just the one cell.
            assertEquals(1, pair.host.board.value[4])
            assertEquals(1, pair.guest.board.value[4])
            assertEquals(2, pair.host.currentPlayer.value)
            assertEquals(2, pair.guest.currentPlayer.value)
        } finally {
            pair.disconnect()
        }
    }

    @Test
    fun guestsMoveIsForwardedToHostAndBroadcastBack() {
        val pair = setUpConnectedPair()
        try {
            // It's player 2's (the guest's) turn after setUpConnectedPair's opening move.
            // The guest's own cellClicked must NOT apply this locally -- it should be
            // forwarded to the host as an Intent instead.
            pair.guest.cellClicked(0)

            awaitCondition("the host to receive and apply the guest's intent") { pair.host.board.value[0] == 2 }
            awaitCondition("the guest to receive the host's broadcast of its own move") { pair.guest.board.value[0] == 2 }

            assertEquals(1, pair.host.currentPlayer.value)
            assertEquals(1, pair.guest.currentPlayer.value)
        } finally {
            pair.disconnect()
        }
    }

    @Test
    fun hostIgnoresAnIntentClaimingTheWrongPlayersTurn() {
        val pair = setUpConnectedPair()
        try {
            // It's player 2's (the guest's) turn at this point. LanMultiplayerTransport
            // trusts a message frame's own declared fromPlayerId at delivery time (the same
            // design OnlineTransport's own relay already uses -- see its "message" case
            // trusting msg.fromPlayerId) -- the actual defense against a wrong-seat move is
            // meant to live in the GAME layer's own handling, which is exactly what this test
            // exercises: a real Intent sent over the guest's own live connection, but
            // dishonestly claiming to be from "host-p" instead of the guest's own "guest-p".
            // Since currentPlayer is 2 (index 1, the guest's own real seat) and this frame
            // claims sender index 0, TicTacToeGame.handleNetworkMessage's own
            // senderIndex != currentPlayer.value - 1 check must reject it -- cell 1 stays
            // untouched on both sides either way.
            val badIntentPayload = kotlinx.serialization.json.Json.encodeToString(
                TicTacToeNetMessage.serializer(),
                TicTacToeNetMessage.Intent(TicTacToeIntentPayload.CellClicked(1))
            )
            pair.guestTransport.send(fromPlayerId = "host-p", toPlayerId = "host-p", payload = badIntentPayload.encodeToByteArray())

            Thread.sleep(300) // give a wrongful apply a real chance to happen before asserting it didn't
            assertEquals(0, pair.host.board.value[1])
            assertEquals(0, pair.guest.board.value[1])
        } finally {
            pair.disconnect()
        }
    }

    @Test
    fun guestsPlayAgainIntentResetsTheBoardOnBothSides() {
        val pair = setUpConnectedPair()
        try {
            // Force a quick win for player 1 (the host) by hand-seeding a near-complete board
            // directly on the host, mirroring how ChessGameTest/MancalaGameTest already seed
            // fixtures through a game module's own public mutableStateOf fields -- then let
            // the host's real applyCellClicked (via the already-proven local cellClicked path)
            // complete it, so this test is exercising the real win-detection/broadcast path,
            // not hand-waving roundOver directly.
            pair.host.board.value = intArrayOf(1, 1, 0, 0, 0, 0, 0, 0, 0)
            pair.host.currentPlayer.value = 1
            pair.host.cellClicked(2) // completes the top row for player 1
            awaitCondition("the guest to see the round end") { pair.guest.roundOver.value }
            assertEquals(1, pair.guest.scoreP1.value)

            // Now the guest requests a new round -- must be forwarded to the host, not
            // applied locally, same as any other guest action.
            pair.guest.playAgain()
            awaitCondition("the host to process the guest's Play Again request") { !pair.host.roundOver.value }
            awaitCondition("the guest to receive the reset board") { !pair.guest.roundOver.value }

            assertEquals(2, pair.host.roundNumber.value)
            assertEquals(2, pair.guest.roundNumber.value)
            assertTrue(pair.guest.board.value.all { it == 0 }, "expected a freshly reset board on the guest after Play Again")
            // The running score from the completed round must survive into the new one.
            assertEquals(1, pair.guest.scoreP1.value)
        } finally {
            pair.disconnect()
        }
    }
}
