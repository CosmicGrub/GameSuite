package com.gamesuite.games.cards

import androidx.compose.ui.graphics.Color

/**
 * Generic playing-card face — every card game (UNO now; standard-deck games
 * later) maps its own card model to this so they all render through the
 * same PlayingCardView/FannedHand/animation/sound layer instead of each
 * game reinventing card rendering.
 */
data class CardVisual(
    val id: Int,
    val label: String,
    val backgroundColor: Color,
    val textColor: Color = Color.White,
    /** Small corner index text, e.g. "7" or "A" for a standard deck; null to omit. */
    val cornerIndex: String? = null,
    val faceDown: Boolean = false
)
