package com.gamesuite.games.uno

/**
 * Toggleable house rules — this is what "all modes" maps to. Classic mode is
 * every flag false: official Mattel/Uno rules only.
 */
data class UnoRules(
    /** Stack +2 on +2 (and +4 on +4, or +2 on +4 depending on stackDrawFourOnDrawTwo) instead of drawing immediately. */
    val stackDraw: Boolean = false,
    /** If stacking is on, allow a +2 to be answered with a +4 (not just matching +2s). */
    val stackDrawFourOnDrawTwo: Boolean = false,
    /** Playing a 7 lets you swap hands with any opponent; playing a 0 rotates all hands in play direction. */
    val sevenZero: Boolean = false,
    /** Any player holding an exact match (color+rank) of the top card may jump in out of turn. */
    val jumpIn: Boolean = false,
    /** Must-play-if-able: if you drew a playable card, you must play it immediately instead of choosing to keep it. */
    val forcePlayDrawnCard: Boolean = true,
    /** Team play: players are grouped by PlayerInfo.teamId; a team wins when any of its members plays their last card. */
    val teamPlay: Boolean = false
)
