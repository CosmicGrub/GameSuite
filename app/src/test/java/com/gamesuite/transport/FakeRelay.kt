package com.gamesuite.transport

/**
 * An in-memory stand-in for the relay (server/index.js) for JVM engine tests: one [Device] per player, each a
 * [MultiplayerTransport]. Like the real relay it stamps `fromPlayerId` from the sending device (never from the
 * caller's argument), delivers a broadcast to everyone EXCEPT the sender, and an addressed send to that one device.
 *
 * Delivery is queued, not re-entrant, and only happens in [pump]: that mirrors a real network (a send never runs the
 * receiver's code inside the sender's call stack) and keeps engines' own locks from nesting across devices.
 */
class FakeRelay {
    private class Envelope(val to: String?, val from: String, val payload: ByteArray)

    inner class Device(val playerId: String) : MultiplayerTransport {
        var onMessage: ((String, ByteArray) -> Unit)? = null
        var onJoined: ((String) -> Unit)? = null
        var onLeft: ((String) -> Unit)? = null
        var onReconnect: (() -> Unit)? = null
        var connected = true
        val sentCount: Int get() = sent
        private var sent = 0

        override fun connect() {}
        override fun disconnect() { connected = false }
        override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {
            if (!connected) return
            sent++
            queue.addLast(Envelope(toPlayerId, playerId, payload.copyOf()))
        }
        override fun onMessageReceived(listener: (String, ByteArray) -> Unit) { onMessage = listener }
        override fun onPlayerJoined(listener: (String) -> Unit) { onJoined = listener }
        override fun onPlayerLeft(listener: (String) -> Unit) { onLeft = listener }
        override fun onReconnected(listener: () -> Unit) { onReconnect = listener }
    }

    private val queue = ArrayDeque<Envelope>()
    private val devices = LinkedHashMap<String, Device>()

    fun device(playerId: String): Device = devices.getOrPut(playerId) { Device(playerId) }

    /** Delivers queued messages (and whatever those provoke) until quiet; returns how many were delivered. */
    fun pump(limit: Int = 20_000): Int {
        var delivered = 0
        while (queue.isNotEmpty()) {
            check(delivered < limit) { "message storm: more than $limit deliveries without going quiet" }
            val e = queue.removeFirst()
            val targets = if (e.to == null) devices.values.filter { it.playerId != e.from } else listOfNotNull(devices[e.to])
            for (t in targets) {
                if (!t.connected) continue
                t.onMessage?.invoke(e.from, e.payload)
                delivered++
            }
        }
        return delivered
    }

    /** [playerId] vanished for good: everybody else's `onPlayerLeft` fires (the relay's `playerLeft`). */
    fun drop(playerId: String) {
        val gone = devices.remove(playerId) ?: return
        gone.connected = false
        devices.values.forEach { it.onLeft?.invoke(playerId) }
    }
}
