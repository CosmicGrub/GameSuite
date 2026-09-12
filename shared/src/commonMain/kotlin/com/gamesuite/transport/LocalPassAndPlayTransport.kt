package com.gamesuite.transport

/**
 * Simplest possible transport: everyone is on this one device, taking turns
 * handing it to each other. "Sending" a message just delivers it straight
 * back out — there is no network. Games built against this transport are
 * automatically playable in every other mode later, as long as they never
 * assume this specific behavior.
 */
class LocalPassAndPlayTransport : MultiplayerTransport {
    private var messageListener: ((String, ByteArray) -> Unit)? = null
    private var joinedListener: ((String) -> Unit)? = null
    private var leftListener: ((String) -> Unit)? = null

    override fun connect() {
        // Nothing to do — no real connection for a single local device.
    }

    override fun disconnect() {
        // No-op.
    }

    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {
        // Loop the message straight back — the "network" is instantaneous
        // because everyone shares the same device and screen. Report the
        // real sender: toPlayerId is only the destination (or null for a
        // broadcast) and must never be used as the reported sender.
        messageListener?.invoke(fromPlayerId, payload)
    }

    override fun onMessageReceived(listener: (String, ByteArray) -> Unit) {
        messageListener = listener
    }

    override fun onPlayerJoined(listener: (String) -> Unit) {
        joinedListener = listener
    }

    override fun onPlayerLeft(listener: (String) -> Unit) {
        leftListener = listener
    }
}
