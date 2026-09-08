package com.gamesuite.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The play surface's own resting perspective tilt (rotationX around the
 * horizontal axis) — applied once to a whole board/table container, not per
 * piece/card/tile, so everything sitting "on" it reads as receding slightly
 * toward the top of the screen. Kept small (a few degrees) since this wraps
 * the *entire* surface: anything larger makes text/hit-testing uncomfortable.
 *
 * Moved out of `games/cards/Card3D.kt` (where this started life as
 * `card3DTableTilt`) into this board-agnostic `ui` package per the
 * cross-cutting finding in the animation/physics pitch: the function itself
 * had zero call sites anywhere in the app when that pitch was written, so
 * there was no migration to break, and every consumer of it is a *board*
 * (Checkers, Chess, Mancala, Dominoes, Air Hockey, Sliding Puzzle, UNO's
 * table) rather than a card specifically — importing something named
 * "card3D" to tilt a checkerboard was the wrong shape for where this was
 * headed. `card3DFlip`/`card3DToss` stay in `Card3D.kt`: both are already
 * genuinely reused outside the cards package (e.g. Chess's promotion flip,
 * a domino's fly-to-chain toss) and that cross-package reuse is fine — only
 * the whole-surface tilt needed a neutral home.
 *
 * Gate every call site the same way: `LocalCard3DMode.current &&
 * !LocalReducedMotion.current` (see settings/LocalCard3DMode.kt — its own
 * KDoc was renamed from "3D card mode" to "3D perspective mode" alongside
 * this move, since the setting now visibly does more than tilt cards).
 */
fun Modifier.tablePerspectiveTilt(tiltDeg: Float = 6f, cameraDistanceDp: Float = 40f) = graphicsLayer {
    rotationX = tiltDeg
    cameraDistance = cameraDistanceDp * density * 8f
}
