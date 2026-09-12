package com.gamesuite.games.chess

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LanMultiplayerTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real end-to-end proof that ChessGame's networked-play wiring (see ChessNetMessage.kt's
 * own KDoc) actually works, not just that it compiles -- mirrors
 * TicTacToeLanMultiplayerTest.kt's own shape almost exactly: two real [ChessGame] instances,
 * each driven by its own real [LanMultiplayerTransport], talking over actual loopback TCP --
 * the same shape two separate desktop processes (or a desktop and an Android device) would use
 * for a real LAN match. Lives in jvmTest for the same reason LanMultiplayerTransport itself
 * lives in jvmMain: it needs java.net sockets, which aren't part of Kotlin's common stdlib.
 *
 * Player index 0 is always White (the host, by this codebase's own lobby convention -- see
 * ChessGame's isHost); player index 1 is always Black (the guest). Moves are driven through
 * Fool's Mate (1. f3 e5 2. g4 Qh4#) -- a real, short, forced checkmate reached through actual
 * alternating playMove() calls on each side's own instance, rather than hand-seeding
 * ChessGame.state directly the way TicTacToeLanMultiplayerTest's own Play-Again test seeds
 * TicTacToeGame.board -- this proves the real move-validation/broadcast/checkmate-detection
 * path end to end, not just that a hand-built terminal state broadcasts correctly.
 */
class ChessLanMultiplayerTest {

    private fun sq(file: Char, rank: Int): Int = (rank - 1) * 8 + (file - 'a')

    private fun await(latch: CountDownLatch, message: String, seconds: Long = 5) {
        assertTrue(latch.await(seconds, TimeUnit.SECONDS), "Timed out waiting for: $message")
    }

    /** Polls [check] until it returns true or [seconds] elapse -- these are real background
     *  coroutines (LanMultiplayerTransport's read loops) delivering to real Compose state
     *  (ChessGame's mutableStateOf fields), not something a single latch can await on a
     *  per-field basis, same reasoning TicTacToeLanMultiplayerTest's own awaitCondition
     *  already established. */
    private fun awaitCondition(message: String, seconds: Long = 5, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(20)
        }
        assertTrue(check(), "Timed out waiting for: $message")
    }

    private class ConnectedPair(val host: ChessGame, val guest: ChessGame, val hostTransport: LanMultiplayerTransport, val guestTransport: LanMultiplayerTransport) {
        fun disconnect() {
            hostTransport.disconnect()
            guestTransport.disconnect()
        }
    }

    /** Real transport connection (host opens a port, guest dials it over 127.0.0.1) plus two
     *  real ChessGame instances in [PlayMode.LOCAL_AD_HOC], host initialized first so its
     *  message listener is registered before the guest's init() sends its initial
     *  [ChessNetMessage.RequestState] -- getting that order backwards would drop the guest's
     *  very first request on the floor, same reasoning TicTacToeLanMultiplayerTest's own setup
     *  documents. */
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
        val hostGame = ChessGame()
        val guestGame = ChessGame()

        hostGame.init(GameContext(activeMode = PlayMode.LOCAL_AD_HOC, players = players, localPlayerIndex = 0, transport = hostTransport))
        guestGame.init(GameContext(activeMode = PlayMode.LOCAL_AD_HOC, players = players, localPlayerIndex = 1, transport = guestTransport))
        hostGame.startMatch()
        guestGame.startMatch()

        // Confirms the initial sync round-trip actually completed (guest's RequestState
        // reached the host, and the host's reply reached back) before any test below starts
        // asserting things about a specific move -- White's own opening move (f2-f3, the
        // first ply of the Fool's Mate line every test in this file plays out) doubles as
        // that proof: it can only have reached the guest's own board via a real StateSync.
        hostGame.playMove(0, sq('f', 2), sq('f', 3))
        awaitCondition("guest to receive the host's opening move") {
            guestGame.state.value?.board?.get(sq('f', 3))?.type == PieceType.PAWN
        }

        return ConnectedPair(hostGame, guestGame, hostTransport, guestTransport)
    }

    @Test
    fun hostsMoveAppliesLocallyAndPropagatesToGuest() {
        val pair = setUpConnectedPair()
        try {
            // setUpConnectedPair's own sync check already played White's f2-f3 -- confirm
            // both sides agree on the resulting turn order too, not just the one square.
            assertEquals(PieceType.PAWN, pair.host.state.value?.board?.get(sq('f', 3))?.type)
            assertEquals(PieceType.PAWN, pair.guest.state.value?.board?.get(sq('f', 3))?.type)
            assertEquals(PieceColor.BLACK, pair.host.state.value?.sideToMove)
            assertEquals(PieceColor.BLACK, pair.guest.state.value?.sideToMove)
        } finally {
            pair.disconnect()
        }
    }

    @Test
    fun guestsMoveIsForwardedToHostAndBroadcastBack() {
        val pair = setUpConnectedPair()
        try {
            // It's Black's (the guest's) turn after setUpConnectedPair's opening move. The
            // guest's own playMove must NOT apply this locally -- it should be forwarded to
            // the host as an Intent instead.
            pair.guest.playMove(1, sq('e', 7), sq('e', 5))

            awaitCondition("the host to receive and apply the guest's intent") {
                pair.host.state.value?.board?.get(sq('e', 5))?.let { it.type == PieceType.PAWN && it.color == PieceColor.BLACK } == true
            }
            awaitCondition("the guest to receive the host's broadcast of its own move") {
                pair.guest.state.value?.board?.get(sq('e', 5))?.let { it.type == PieceType.PAWN && it.color == PieceColor.BLACK } == true
            }

            assertEquals(PieceColor.WHITE, pair.host.state.value?.sideToMove)
            assertEquals(PieceColor.WHITE, pair.guest.state.value?.sideToMove)
        } finally {
            pair.disconnect()
        }
    }

    @Test
    fun hostIgnoresAnIntentClaimingTheWrongPlayersTurn() {
        val pair = setUpConnectedPair()
        try {
            // It's Black's (the guest's) turn at this point. LanMultiplayerTransport trusts a
            // message frame's own declared fromPlayerId at delivery time (the same design
            // OnlineTransport's own relay already uses) -- the actual defense against a
            // wrong-seat move is meant to live in the GAME layer's own handling, exactly what
            // this test exercises: a real Intent sent over the guest's own live connection,
            // but dishonestly claiming to be from "host-p" instead of the guest's own
            // "guest-p". Since sideToMove is BLACK (the guest's own real seat, index 1) and
            // this frame claims sender index 0 (White/host), ChessGame.handleNetworkMessage's
            // own senderIndex != playerIndexForColor(sideToMove) check must reject it -- d2
            // stays untouched on both sides either way.
            val badIntentPayload = Json.encodeToString(
                ChessNetMessage.serializer(),
                ChessNetMessage.Intent(ChessIntentPayload.PlayMove(sq('d', 2), sq('d', 4)))
            )
            pair.guestTransport.send(fromPlayerId = "host-p", toPlayerId = "host-p", payload = badIntentPayload.encodeToByteArray())

            Thread.sleep(300) // give a wrongful apply a real chance to happen before asserting it didn't
            assertEquals(PieceType.PAWN, pair.host.state.value?.board?.get(sq('d', 2))?.type)
            assertEquals(PieceType.PAWN, pair.guest.state.value?.board?.get(sq('d', 2))?.type)
            assertEquals(PieceColor.BLACK, pair.host.state.value?.sideToMove)
        } finally {
            pair.disconnect()
        }
    }

    @Test
    fun guestsPlayAgainIntentResetsTheBoardOnBothSides() {
        val pair = setUpConnectedPair()
        try {
            // Complete Fool's Mate for real, alternating real playMove() calls on each side's
            // own instance -- proves the real checkmate-detection/broadcast path, not a
            // hand-built terminal state. setUpConnectedPair already played 1. f3 -- still
            // need Black's reply (1... e5) before White's second move, or g2-g4 would be
            // rejected outright as a wrong-turn call (it would still be Black's move).
            pair.guest.playMove(1, sq('e', 7), sq('e', 5))
            awaitCondition("the host to receive Black's e7-e5") {
                pair.host.state.value?.board?.get(sq('e', 5))?.type == PieceType.PAWN
            }

            pair.host.playMove(0, sq('g', 2), sq('g', 4))
            awaitCondition("the guest to receive White's g2-g4") {
                pair.guest.state.value?.board?.get(sq('g', 4))?.type == PieceType.PAWN
            }

            pair.guest.playMove(1, sq('d', 8), sq('h', 4)) // Qh4# -- White is checkmated
            awaitCondition("the host to detect checkmate") { pair.host.state.value?.roundOver == true }
            awaitCondition("the guest to see the round end") { pair.guest.state.value?.roundOver == true }
            assertEquals(ChessResult.BLACK_WINS, pair.host.state.value?.result)
            assertEquals(ChessResult.BLACK_WINS, pair.guest.state.value?.result)
            assertEquals(1, pair.guest.scoreP2.value)

            // Now the guest requests a new round -- must be forwarded to the host, not
            // applied locally, same as any other guest action.
            pair.guest.playAgain()
            awaitCondition("the host to process the guest's Play Again request") { pair.host.state.value?.roundOver == false }
            awaitCondition("the guest to receive the reset board") { pair.guest.state.value?.roundOver == false }

            assertEquals(PieceColor.WHITE, pair.guest.state.value?.sideToMove)
            assertEquals(PieceType.PAWN, pair.guest.state.value?.board?.get(sq('f', 2))?.type)
            assertTrue(pair.guest.state.value?.board?.get(sq('f', 3)) == null, "expected f3 to be empty again after a fresh board")
            // The running score from the completed round must survive into the new one.
            assertEquals(1, pair.guest.scoreP2.value)
        } finally {
            pair.disconnect()
        }
    }
}
