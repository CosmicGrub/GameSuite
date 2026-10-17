package com.gamesuite.games.checkers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol for Checkers over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (PlayMode.LOCAL_AD_HOC and PlayMode.ONLINE). Two seats: the HOST is seat 0 (plays Light, moves
 * first) and owns the only real engine; the GUEST is seat 1 (plays Dark) and renders the host's
 * broadcast state. A guest never changes its own board from a tap: it sends one whole
 * [MoveRequest] (a multi-jump is the full list of landing squares) and waits for the next
 * [StateSync]. Squares are `row * 8 + col`.
 */
@Serializable
sealed class CheckersNetMessage {

    /**
     * The host's authoritative position. [snapshot] is the engine's own resume snapshot (board,
     * turn, forced continuation, game-over flag + winner, session score, opening seat), so the
     * guest needs nothing else; [lastMove]/[hops] are the animation detail a snapshot leaves out.
     * [version] increases by one on every host commit; a receiver applies a version only if it is
     * newer than the last one it applied.
     */
    @Serializable
    data class StateSync(
        val version: Int,
        val snapshot: String,
        val lastMove: CheckersWireMove? = null,
        val hops: List<CheckersWireMove> = emptyList()
    ) : CheckersNetMessage()

    /**
     * A guest's whole turn: the piece on [from] and the landing square of every hop in order.
     * [baseVersion] is the [StateSync.version] the guest computed it against; the host drops a
     * request whose base is not its current version (the board has moved on since).
     */
    @Serializable
    data class MoveRequest(val baseVersion: Int, val from: Int, val path: List<Int>) : CheckersNetMessage()

    /** Sent by the guest after it registers its listener and after a reconnect: "send me the board". */
    @Serializable
    class RequestState : CheckersNetMessage()

    /** Sent by the guest from the results panel; the host re-deals if the round is actually over. */
    @Serializable
    class PlayAgainRequest : CheckersNetMessage()
}

/** [CheckersMove] on the wire (that class is not serializable and lives in the engine file). */
@Serializable
data class CheckersWireMove(
    val fromRow: Int,
    val fromCol: Int,
    val toRow: Int,
    val toCol: Int,
    val isCapture: Boolean,
    val capRow: Int = -1,
    val capCol: Int = -1
)

fun CheckersMove.toWire(): CheckersWireMove =
    CheckersWireMove(fromRow, fromCol, toRow, toCol, isCapture, capRow, capCol)

/** Null when any coordinate is off the board, so a hostile StateSync cannot feed the UI a bad square. */
fun CheckersWireMove.toMoveOrNull(): CheckersMove? {
    val squaresOk = fromRow in 0..7 && fromCol in 0..7 && toRow in 0..7 && toCol in 0..7
    val captureOk = if (isCapture) (capRow in 0..7 && capCol in 0..7) || (capRow == -1 && capCol == -1) else true
    if (!squaresOk || !captureOk) return null
    return CheckersMove(fromRow, fromCol, toRow, toCol, isCapture, capRow, capCol)
}

private val checkersNetJson = Json { ignoreUnknownKeys = true }

/** Encodes exactly as [CheckersGame] does on the wire (polymorphic [CheckersNetMessage], JSON, UTF-8). */
fun encodeCheckersNetMessage(message: CheckersNetMessage): ByteArray =
    checkersNetJson.encodeToString(CheckersNetMessage.serializer(), message).encodeToByteArray()

/** Null for a malformed or foreign payload. */
fun decodeCheckersNetMessage(payload: ByteArray): CheckersNetMessage? = try {
    checkersNetJson.decodeFromString(CheckersNetMessage.serializer(), payload.decodeToString())
} catch (_: Exception) {
    null
}
