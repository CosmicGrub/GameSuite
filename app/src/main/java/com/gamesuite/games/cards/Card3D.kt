package com.gamesuite.games.cards

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Shared "3D perspective mode" card transform helpers (Settings -> Display
 * -> "3D perspective mode", see settings/LocalCard3DMode.kt — renamed from
 * "3D card mode" once board games started reading the same flag too, per
 * the animation/physics pitch's cross-cutting finding) — every card-game
 * screen that wants a perspective card effect reads through here instead of
 * hand-rolling its own graphicsLayer math, so the "how much tilt/how much
 * depth" tuning lives in exactly one place. UNO is the proof-of-concept
 * consumer (its fly-to-discard-pile animation reads card3DToss) — the same
 * pattern this project already used to prove Checkers' slide animation
 * before extending it to other ESP32 games. The whole-surface tilt these
 * functions used to include (`card3DTableTilt`) has since moved to
 * `com.gamesuite.ui.TablePerspective.kt` as `tablePerspectiveTilt` — see
 * that file's KDoc for why.
 *
 * These are real perspective transforms (rotationY through actual 3D space,
 * with a finite cameraDistance so the rotation reads as depth instead of a
 * flat squash), rendered by the normal Compose/View compositor — not a
 * mesh-based 3D engine. See the roadmap note in AppSettings.card3DEnabled's
 * KDoc for why that's the deliberate scope, not a shortcut.
 *
 * Every function here is a no-op-shaped identity transform at its rest
 * value (rotationYDeg = 0f, liftDp = 0f) — so a call site that always
 * applies these modifiers, gated only by whether 3D mode is *active* right
 * now for that one animation frame, never needs a separate flat-vs-3D code
 * path for its layout.
 */

/**
 * A face flip through real 3D space: [progress] 0f = fully face-down/at
 * rest, 1f = fully flipped to the other face. Rotates the layer up to 180°
 * around the vertical axis with a finite camera distance so the card
 * visibly recedes/advances mid-flip instead of just squashing horizontally
 * the way a scaleX-based fake flip does. Callers swap which face's content
 * they draw at progress >= 0.5f (the halfway point where the card is
 * edge-on and either face would look identical).
 *
 * cameraDistanceDp is in Dp, not the raw px [android.graphics.Camera] scale
 * TFT_eSPI-style code might expect — converted internally via
 * [LocalDensity] so the same perceived depth holds across card-size
 * settings and device densities.
 */
fun Modifier.card3DFlip(progress: Float, cameraDistanceDp: Float = 20f) = graphicsLayer {
    rotationY = progress.coerceIn(0f, 1f) * 180f
    cameraDistance = cameraDistanceDp * density * 8f
    // Perspective rotation past 90° draws front-to-back at that exact frame,
    // which can show a sliver of the opposite face's edge if compositing
    // isn't isolated per-layer — pin it to its own offscreen layer so the
    // flip always reads as one clean card, not a double-exposed one.
    compositingStrategy = CompositingStrategy.Offscreen
}

/**
 * A gentle "tossed card" roll for in-flight animations (e.g. hand -> discard
 * pile): a small, continuously-increasing rotationY as the card travels,
 * settling flat (0°) once [progress] reaches 1f. Distinct from
 * [card3DFlip] — this never needs a face-swap since it never actually
 * passes through the 90° edge-on point ([maxRotationDeg] defaults well
 * under that).
 */
fun Modifier.card3DToss(progress: Float, maxRotationDeg: Float = 35f, cameraDistanceDp: Float = 20f) = graphicsLayer {
    val eased = progress.coerceIn(0f, 1f)
    // Rolls up through the first two-thirds of the flight, then straightens
    // out for the final third so it "lands" flat on the pile rather than
    // arriving still visibly tilted.
    rotationY = if (eased < 0.66f) (eased / 0.66f) * maxRotationDeg else maxRotationDeg * (1f - (eased - 0.66f) / 0.34f)
    cameraDistance = cameraDistanceDp * density * 8f
}

// The whole-surface resting tilt formerly lived here as `card3DTableTilt`.
// It moved to `com.gamesuite.ui.TablePerspective.kt` as `tablePerspectiveTilt`
// — see that file's KDoc. It had zero call sites at the time of the move, so
// nothing here needed updating besides this pointer.
