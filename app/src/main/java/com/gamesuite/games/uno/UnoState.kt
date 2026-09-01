package com.gamesuite.games.uno

import kotlinx.serialization.Serializable

/** @Serializable throughout this file: UnoState is broadcast whole over the network
 *  transport for host-authoritative Nearby play — see UnoNetMessage.kt. */
@Serializable
data class UnoPlayerState(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    val teamId: Int,
    val hand: List<UnoCard>,
    /** Whether this player has declared "UNO" since last reaching hand size 1. Reset on draw/new card. */
    val calledUno: Boolean = false
)

@Serializable
data class UnoState(
    val players: List<UnoPlayerState>,
    val drawPileSize: Int,
    val discardPile: List<UnoCard>,
    val currentColor: UnoColor,
    val currentPlayerIndex: Int,
    /** +1 = clockwise (list order), -1 = counter-clockwise. */
    val direction: Int,
    /** Cards the current player must draw before their next play (stacked +2/+4), 0 if none pending. */
    val pendingDraw: Int,
    val awaitingColorChoice: Boolean,
    /** True when a Wild Draw Four was just played (non-stacking) and the victim can accept the draw or challenge it. */
    val awaitingChallenge: Boolean = false,
    val challengeVictimIndex: Int? = null,
    val challengePlayedByIndex: Int? = null,
    /** The color in play immediately before the Wild Draw Four overrode it — needed to judge a challenge. */
    val colorBeforeWildDrawFour: UnoColor? = null,
    /** Free-text status line for the UI, e.g. "Player 2 played Skip". */
    val lastAction: String,
    /** This hand/round has ended (someone emptied their hand or the game is blocked) — see UnoGame for round-vs-match distinction. */
    val roundOver: Boolean = false,
    val matchOver: Boolean = false,
    /** Set once roundOver — playerId of the round's winner, or null for a team win (check winningTeamId instead). */
    val winnerPlayerId: String? = null,
    val winningTeamId: Int? = null,
    /** Cumulative match score per playerId, official UNO plays to 500. */
    val cumulativeScores: Map<String, Int> = emptyMap(),
    val roundNumber: Int = 1
) {
    val topCard: UnoCard get() = discardPile.last()
}
