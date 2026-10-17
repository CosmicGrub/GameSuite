package com.gamesuite.games.uno

import kotlinx.serialization.Serializable

/**
 * Toggleable house rules — this is what "all modes" maps to. Classic mode is
 * every flag false: official Mattel/Uno rules only.
 */
@Serializable
data class UnoRules(
    /** Stack +2 on +2 (and +4 on +4, or +2 on +4 depending on stackDrawFourOnDrawTwo) instead of drawing immediately. */
    val stackDraw: Boolean = false,
    /** If stacking is on, allow a +2 to be answered with a +4 (not just matching +2s). */
    val stackDrawFourOnDrawTwo: Boolean = false,
    /** Playing a 7 lets you swap hands with any opponent; playing a 0 rotates all hands in play direction. */
    val sevenZero: Boolean = false,
    /** Any player holding an exact match (color+rank) of the top card may jump in out of turn. */
    val jumpIn: Boolean = false,
    /** Must-play-if-able: if you drew a playable card, you must play it immediately instead of choosing to keep it.
     *  Defaults to false to match this class's own "classic = every flag false" contract above — official
     *  Mattel/UNO rules make playing a drawn playable card the player's OPTION, never mandatory. See
     *  UnoGame.drawCard()/keepDrawnCard() for how the optional case is actually offered to the player. */
    val forcePlayDrawnCard: Boolean = false,
    /** Team play: players are grouped by PlayerInfo.teamId; a team wins when any of its members plays their last card. */
    val teamPlay: Boolean = false,
    /** Cards dealt to each player (3..10). The engine clamps further so a big table never exhausts the deck, see [dealtHandSize]. */
    val handSize: Int = UNO_DEFAULT_HAND_SIZE,
    /** Cumulative score that ends the match; 0 = a single round decides the match. */
    val targetScore: Int = UNO_DEFAULT_TARGET_SCORE,
    /** With no playable card, a player keeps drawing until a playable card turns up (then may play it as usual). */
    val drawUntilPlayable: Boolean = false
) {
    /**
     * The hand size actually dealt at a table of [playerCount]: [handSize] limited to 3..10 and to
     * what the 108-card deck can cover while still leaving a start card (players * hand <= 100).
     */
    fun dealtHandSize(playerCount: Int): Int {
        val wanted = handSize.coerceIn(UNO_MIN_HAND_SIZE, UNO_MAX_HAND_SIZE)
        val cap = UNO_MAX_DEALT_CARDS / playerCount.coerceAtLeast(1)
        return wanted.coerceAtMost(cap).coerceAtLeast(1)
    }
}

const val UNO_DEFAULT_HAND_SIZE = 7
const val UNO_MIN_HAND_SIZE = 3
const val UNO_MAX_HAND_SIZE = 10
const val UNO_DEFAULT_TARGET_SCORE = 500
/** Most cards that may be dealt at the start of a round (108-card deck, 8 kept back for the draw pile and start card). */
const val UNO_MAX_DEALT_CARDS = 100

private val unoAllowedTargetScores = setOf(0, 200, 300, 500)

/**
 * Maps the Game Hub's per-launch extras (keys equal the [UnoRules] field names, values are strings)
 * onto [base]. Tolerant: unknown keys are ignored, an unparsable value leaves the field of [base]
 * as it was, a hand size outside 3..10 is clamped, and a target score that is not one of
 * 0 / 200 / 300 / 500 snaps to the nearest of them (negative = 0). teamPlay is never touched here.
 */
fun unoRulesFromExtras(extras: Map<String, String>, base: UnoRules): UnoRules {
    fun bool(key: String, fallback: Boolean): Boolean = when (extras[key]?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> fallback
    }
    val stack = bool("stackDraw", base.stackDraw)
    val hand = extras["handSize"]?.trim()?.toIntOrNull()?.coerceIn(UNO_MIN_HAND_SIZE, UNO_MAX_HAND_SIZE) ?: base.handSize
    val target = extras["targetScore"]?.trim()?.toIntOrNull()?.let { raw ->
        if (raw <= 0) 0 else unoAllowedTargetScores.minByOrNull { kotlin.math.abs(it - raw) } ?: base.targetScore
    } ?: base.targetScore
    return base.copy(
        stackDraw = stack,
        // +4-on-+2 only means anything with stacking on.
        stackDrawFourOnDrawTwo = stack && bool("stackDrawFourOnDrawTwo", base.stackDrawFourOnDrawTwo),
        sevenZero = bool("sevenZero", base.sevenZero),
        jumpIn = bool("jumpIn", base.jumpIn),
        forcePlayDrawnCard = bool("forcePlayDrawnCard", base.forcePlayDrawnCard),
        drawUntilPlayable = bool("drawUntilPlayable", base.drawUntilPlayable),
        handSize = hand,
        targetScore = target
    )
}

/** True when [extras] carries any of the keys [unoRulesFromExtras] reads. */
fun unoExtrasHaveRules(extras: Map<String, String>): Boolean = unoRuleExtraKeys.any { it in extras }

private val unoRuleExtraKeys = listOf(
    "stackDraw", "stackDrawFourOnDrawTwo", "sevenZero", "jumpIn", "forcePlayDrawnCard",
    "drawUntilPlayable", "handSize", "targetScore"
)
