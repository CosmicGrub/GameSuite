package com.gamesuite.games.cards

import androidx.compose.ui.graphics.Color

/** How [PlayingCardView] draws a card's face/back. [FLAT] is the original solid-color card every
 *  existing caller gets by default; [UNO] is the printed-deck look (white frame, slanted white
 *  center oval, mirrored corner marks, red-oval "UNO" back). */
enum class CardStyle { FLAT, UNO }

/** The center mark of a [CardStyle.UNO] face -- text glyphs use [CardVisual.label]; the rest are
 *  drawn as vector shapes so they never depend on a font having the right symbol. */
enum class CardGlyph { TEXT, SKIP, REVERSE, WILD }

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
    val colorblindGlyph: String? = null,
    /** See [CardStyle]. Defaults to [CardStyle.FLAT] so every existing caller is unaffected. */
    val style: CardStyle = CardStyle.FLAT,
    /** Only read when [style] is [CardStyle.UNO] and the card is face-up. */
    val glyph: CardGlyph = CardGlyph.TEXT
)
