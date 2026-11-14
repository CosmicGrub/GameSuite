package com.gamesuite.games.uno

/**
 * Pure state-redaction helpers for host-authoritative networked UNO. Kept free of any engine or
 * transport state so the hidden-information rule ("no hand card face ever leaves the host for a
 * peer that does not own that seat") can be unit tested directly. See [UnoGame] for the callers.
 */

/** The face-down stand-in every redacted hand is made of; instanceId -1 never matches a real card. */
internal val UNO_REDACTED_CARD = UnoCard(UnoColor.WILD, UnoRank.WILD, instanceId = -1)

/**
 * [s] with every hand except [keepPlayerId]'s replaced by same-length placeholder cards. Hand SIZES
 * stay real (the table shows card counts); the faces do not. A null [keepPlayerId] keeps nobody's
 * hand, which is exactly what a spectator is allowed to see.
 */
internal fun unoRedactHandsExcept(s: UnoState, keepPlayerId: String?): UnoState =
    s.copy(
        players = s.players.map { p ->
            if (keepPlayerId != null && p.playerId == keepPlayerId) p
            else p.copy(hand = List(p.hand.size) { UNO_REDACTED_CARD })
        }
    )

/**
 * The only view of the game a spectator may receive: public table information only. Card counts per
 * player, the discard pile (it is face up on a real table), current colour, turn, direction, scores
 * and the draw pile size are kept; no player's card faces are. The draw pile ORDER is never part of
 * [UnoState] (it lives privately in the engine), so it cannot leak through this either.
 */
internal fun unoRedactForSpectator(s: UnoState): UnoState = unoRedactHandsExcept(s, keepPlayerId = null)

/** How many distinct watchers a host will keep streaming state to (the relay itself bounds a room). */
internal const val UNO_MAX_SPECTATORS = 16

/**
 * What a spectator (no seat in the roster) puts in `fromPlayerId` when it sends. The relay stamps the
 * real sender id on delivery and ignores this value, so it only has to be non-misleading.
 */
internal const val UNO_SPECTATOR_SENDER = "spectator"
