package com.gamesuite.games.holdem

/**
 * Pure hidden-information helpers for host-authoritative networked Hold'em, kept free of engine and
 * transport state so the rule "no hole card leaves the host for a seat that does not own it, unless it
 * has been turned face up" can be unit tested on its own. The shuffled deck is not part of [HoldemState]
 * at all, so it cannot leak through any of this.
 */

/**
 * [s] as the seat of [keepPlayerId] may see it: every other seat's hole cards become [HIDDEN_CARD]
 * placeholders (the count of cards stays, the faces do not) unless that seat's cards are [HoldemSeat.revealed].
 * A null [keepPlayerId] keeps nobody's cards, which is exactly what a spectator is allowed to see.
 */
internal fun holdemRedactFor(s: HoldemState, keepPlayerId: String?): HoldemState =
    s.copy(
        seats = s.seats.map { p ->
            if (p.revealed || p.hole.isEmpty() || (keepPlayerId != null && p.playerId == keepPlayerId)) p
            else p.copy(hole = List(p.hole.size) { HIDDEN_CARD })
        }
    )

internal fun holdemRedactForSpectator(s: HoldemState): HoldemState = holdemRedactFor(s, null)

/** How many watchers a host will keep streaming state to. */
internal const val HOLDEM_MAX_SPECTATORS = 16

/** What a spectator (no seat) puts in `fromPlayerId`; the transport stamps the real id on delivery. */
internal const val HOLDEM_SPECTATOR_SENDER = "spectator"
