package com.gamesuite.games.shootinggallery

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Shooting Gallery over a networked MultiplayerTransport (Nearby / Online), 2 to 4
 * seats, taking turns on the same seeded range. Host-authoritative: the host (seat 0) owns the
 * engine. A guest plays its turn on its own device and sends only the tap log ([ShotsRequest]); the
 * host replays the log against the range and decides the score, so a guest cannot claim points. The
 * host answers every change with a versioned [StateSync]. Nothing is hidden (the range is a function
 * of the public seed). A request counts only from the transport-established sender whose seat is on
 * turn; no message names a seat.
 */
@Serializable
sealed class GalleryNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: GalleryState, val sessionWins: Map<String, Int>) : GalleryNetMessage()

    @Serializable
    data class ShotsRequest(val shots: List<GalleryShot>) : GalleryNetMessage()

    @Serializable
    data object RequestState : GalleryNetMessage()

    @Serializable
    data object RequestPlayAgain : GalleryNetMessage()
}
