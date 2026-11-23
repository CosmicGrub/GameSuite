package com.gamesuite.transport

import kotlin.random.Random

/**
 * Pure admission policy for incoming Nearby connection requests on the host side, kept free of
 * Play Services types so it is JVM-testable. See [NearbyConnectionsTransport].
 */
object NearbyAdmission {
    /** Most guests a host will take unless the caller passes a smaller seat count. */
    const val DEFAULT_MAX_GUESTS = 7

    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I

    /**
     * Accept only while the host lobby is open ([acceptingGuests]) and a seat is free,
     * counting both already-connected guests and connections accepted but not yet resolved.
     */
    fun shouldAcceptGuest(acceptingGuests: Boolean, connectedGuests: Int, pendingGuests: Int, maxGuests: Int): Boolean =
        acceptingGuests && maxGuests > 0 && connectedGuests + pendingGuests < maxGuests

    /** Short random code that lets a guest tell nearby hosts apart without a real name. */
    fun randomCode(random: Random = Random.Default, length: Int = 4): String =
        buildString { repeat(length) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) } }

    /** What is broadcast to every nearby device: never a player name or device model. */
    fun advertisedLabel(code: String): String = "GameSuite host $code"

    /** Neutral name a guest presents when requesting a connection. */
    const val GUEST_LABEL = "GameSuite guest"
}
