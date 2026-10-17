package com.gamesuite.games.mancala

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol for Mancala over a networked MultiplayerTransport (Nearby / Online). Two seats:
 * the host is seat 0 and owns the engine; the guest is seat 1 and only forwards taps.
 *
 *  - [StateSync]: host -> guest, the authoritative board after every change (and in reply to a
 *    [RequestState]). [StateSync.version] only ever grows; a receiver drops anything not newer
 *    than what it already applied.
 *  - [SowRequest]: guest -> host, "sow absolute board pit [pit]", stamped with the version the
 *    guest was looking at ([baseVersion]) so a stale or duplicated tap can be told apart.
 *  - [RequestState]: guest -> host on start and after a reconnect.
 *  - [PlayAgain]: either seat -> host once a board is over; the host re-deals and broadcasts.
 */
@Serializable
sealed class MancalaNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: MancalaNetState) : MancalaNetMessage()

    @Serializable
    data class SowRequest(val pit: Int, val baseVersion: Int) : MancalaNetMessage()

    @Serializable
    data object RequestState : MancalaNetMessage()

    @Serializable
    data object PlayAgain : MancalaNetMessage()
}

@Serializable
data class MancalaNetCapture(val landingPit: Int, val oppositePit: Int, val totalSwept: Int)

/** Everything a guest needs to render the board and the session tally; Mancala has no hidden information. */
@Serializable
data class MancalaNetState(
    val pits: List<Int>,
    val currentPlayerIndex: Int,
    val lastAction: String,
    val roundOver: Boolean = false,
    val winnerPlayerId: String? = null,
    val lastSowPath: List<Int> = emptyList(),
    val lastCapture: MancalaNetCapture? = null,
    val scoreP1: Int = 0,
    val scoreP2: Int = 0,
    val draws: Int = 0
)

/** Encodes/decodes [MancalaNetMessage] exactly as [MancalaGame] does on the wire (JSON, UTF-8). */
object MancalaNetCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(message: MancalaNetMessage): ByteArray =
        json.encodeToString(MancalaNetMessage.serializer(), message).encodeToByteArray()

    /** Null for malformed or foreign bytes. */
    fun decode(bytes: ByteArray): MancalaNetMessage? =
        try {
            json.decodeFromString(MancalaNetMessage.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            null
        }
}
