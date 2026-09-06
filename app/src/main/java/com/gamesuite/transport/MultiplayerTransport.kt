package com.gamesuite.transport

/**
 * A game sends and receives small move/event payloads through this
 * interface without knowing whether the other side is:
 *  - the same device, waiting for the "next" local player to tap (pass-and-play)
 *  - a second window on the same foldable/tablet (dual-screen)
 *  - a nearby device over Wi-Fi Direct/Bluetooth (ad-hoc, via Nearby Connections)
 *  - a remote device via a matchmaking/relay backend (online)
 *
 * Concrete implementations: LocalPassAndPlayTransport (below), plus
 * DualScreenTransport, NearbyTransport, OnlineTransport added as each is built.
 */
interface MultiplayerTransport {
    fun connect()
    fun disconnect()

    /**
     * Send a game-defined payload from [fromPlayerId] to one player
     * ([toPlayerId]), or null for broadcast to all. [fromPlayerId] must be
     * the actual acting player's id — implementations report it back
     * verbatim via [onMessageReceived], so it must never be derived from
     * [toPlayerId].
     */
    fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray)

    /** Register a listener for incoming messages: (fromPlayerId, payload). */
    fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit)

    fun onPlayerJoined(listener: (playerId: String) -> Unit)
    fun onPlayerLeft(listener: (playerId: String) -> Unit)

    /**
     * Fired when this transport re-establishes a previously-live connection (e.g. after a
     * network blip) WITHOUT a fresh join/host handshake — the game should treat this exactly
     * like the startup "ask the host for current state" case (see UnoGame.init()'s
     * RequestState send) since messages may have been missed while disconnected.
     *
     * Default no-op: only [OnlineTransport] currently has a reconnect story (the relay holds a
     * dropped seat open for a grace period — see server/index.js's header comment).
     * [LocalPassAndPlayTransport] has nothing to reconnect to, and [NearbyConnectionsTransport]
     * doesn't yet implement a reconnect path (a real gap noted in the audit, out of scope for
     * this pass) — both simply never call this listener.
     */
    fun onReconnected(listener: () -> Unit) {}
}
