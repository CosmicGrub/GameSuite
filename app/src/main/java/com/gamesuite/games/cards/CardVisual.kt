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
    val faceDown: Boolean = false,
    /** A small non-color shape/symbol drawn in the opposite corner from [cornerIndex] — the
     *  actual fix behind Settings -> Accessibility -> Colorblind-safe mode, not just its own
     *  color-choice dialog. Null (default, every existing caller unaffected) omits it entirely;
     *  a caller whose card faces are meaningfully color-coded (UNO's four suit colors, the
     *  worst case being red/green) sets this per-color whenever that setting is on, so a
     *  colorblind player can tell cards apart by more than hue during actual play, not only in
     *  a one-off color-choice moment. Ignored while [faceDown] (nothing to disambiguate on a
     *  card back). */
    val colorblindGlyph: String? = null
)
