package com.gamesuite.games.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration

/**
 * The live card-size multiplier every card-rendering call site (FannedHand,
 * UnoScreen's discard pile / opponent fans / challenge-reveal hand) reads and
 * applies to its OWN width/height math — never applied silently inside
 * [PlayingCardView] itself, since components like [FannedHand] compute
 * overlap/fan-width/offsets FROM the card size they're given; if PlayingCardView
 * scaled again internally on top of that, the visual card size and the fan's
 * own layout math would disagree (wrong overlap, wrong scroll width). One
 * multiplier, applied once, at the point where each component does its own
 * size-dependent layout — that's the rule this file exists to make easy to
 * follow correctly.
 *
 * Provided once at the MainActivity root (see [rememberCardScaleMultiplier]),
 * default 1f so anything that reads this before the provider is set up (e.g.
 * a preview/test context) just gets an unscaled card.
 */
val LocalCardScale = compositionLocalOf { 1f }

/**
 * Maps the persisted 0f..1f slider preference to an actual size multiplier,
 * with device-aware bounds — see docs on the "resize the cards" settings
 * feature. Two guarantees this makes regardless of the stored preference:
 *  - Never shrinks a card below a real touch target: the floor (0.65x of
 *    PlayingCardView/FannedHand's tuned 64dp default ≈ 42dp) stays a usable
 *    tap size on every device, not just the ones this was tuned on.
 *  - Never grows a card past what the table actually has room to show
 *    several of at once: the ceiling scales with the window's *effective*
 *    width — capped at 840dp, matching AdaptiveTwoPane's own TABLET-mode
 *    cap, since that's the actual width games render into on a big/tablet
 *    window, not the device's raw (often much wider) physical width.
 */
@Composable
fun rememberCardScaleMultiplier(preference: Float): Float {
    val maxMultiplier = maxCardScaleMultiplier(LocalConfiguration.current.screenWidthDp)
    return MIN_CARD_SCALE + (maxMultiplier - MIN_CARD_SCALE) * preference.coerceIn(0f, 1f)
}

/** The smallest multiplier the Card size slider maps to, on every device. */
const val MIN_CARD_SCALE = 0.65f

/** The largest multiplier the slider maps to on a window [screenWidthDp] wide (see the class doc:
 *  the ceiling scales with the window's effective width, capped at 840dp). */
fun maxCardScaleMultiplier(screenWidthDp: Int): Float =
    (minOf(screenWidthDp, 840) * 0.16f / 64f).coerceIn(1.15f, 1.75f)

/** Inverse of [rememberCardScaleMultiplier]: the raw 0..1 slider preference that produced [cardScale]
 *  on a window [screenWidthDp] wide. Lets a screen that needs the setting on a device-independent
 *  footing (UNO sizes several things relative to it) read the real curve instead of copying its
 *  constants. */
fun cardSizePreferenceFor(cardScale: Float, screenWidthDp: Int): Float =
    ((cardScale - MIN_CARD_SCALE) / (maxCardScaleMultiplier(screenWidthDp) - MIN_CARD_SCALE)).coerceIn(0f, 1f)
