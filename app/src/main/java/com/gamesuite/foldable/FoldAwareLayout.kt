package com.gamesuite.foldable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Splits content across a book/tabletop hinge when one is present. On any
 * non-separating device (a flat-open foldable, or a device with no hinge at
 * all — which is most of the time, including the Fold fully open flat, not
 * just non-foldables) BOTH `primary` and `secondary` still render, stacked
 * in a Column exactly like the original single-pane layout — this is not
 * decoration, `secondary` typically holds essential content (e.g. a card
 * game's own hand), so silently dropping it would make the game unplayable
 * outside the narrow book/tabletop posture window. (An earlier version of
 * this function only rendered `primary` in the fallback case — a real bug
 * caught by on-device testing: UNO/Word Tiles/Dominoes lost their entire
 * hand/rack UI on a flat-open device. Fixed here; this comment documents it
 * so the mistake isn't reintroduced.)
 *
 * This is intentionally simple (two even-ish panes + a hinge gap) rather
 * than reading exact hinge pixel bounds per composable — precise
 * hinge-relative sizing can be layered in later per-screen if a game needs
 * it, but this covers the common "table vs. hand" split most games want.
 */
@Composable
fun FoldAwareTwoPane(
    foldState: FoldState,
    modifier: Modifier = Modifier,
    primary: @Composable () -> Unit,
    secondary: (@Composable () -> Unit)? = null
) {
    val density = LocalDensity.current

    when {
        foldState.isBookPosture && secondary != null -> {
            val hingeWidthDp = foldState.hingeBoundsPx?.let {
                with(density) { (it.right - it.left).toDp() }
            } ?: 24.dp

            Row(modifier = modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) { primary() }
                Box(modifier = Modifier.width(hingeWidthDp))
                Box(modifier = Modifier.weight(1f)) { secondary() }
            }
        }

        foldState.isTabletopPosture && secondary != null -> {
            val hingeHeightDp = foldState.hingeBoundsPx?.let {
                with(density) { (it.bottom - it.top).toDp() }
            } ?: 24.dp

            Column(modifier = modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) { primary() }
                Box(modifier = Modifier.height(hingeHeightDp))
                Box(modifier = Modifier.weight(1f)) { secondary() }
            }
        }

        else -> {
            // primary gets the remaining space after secondary's natural
            // (wrap-content) height is measured — weight(1f) on primary,
            // none on secondary, so Compose measures secondary first for
            // its intrinsic size, then gives primary the rest. This also
            // matters for screens like Word Tiles, where content INSIDE
            // primary (a LazyVerticalGrid) uses its own weight(1f) — that
            // only resolves sanely if primary itself has a bounded height
            // to divide, not an unbounded/fillMaxSize one.
            //
            // Screen content passed as primary/secondary must use
            // fillMaxWidth() for its own root, not fillMaxSize() — a
            // fillMaxSize() root would greedily claim all available height
            // for itself and starve the other pane. (This exact mistake
            // shipped once already — caught via on-device testing, not
            // code review — and is why this comment exists.)
            Column(modifier = modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) { primary() }
                secondary?.invoke()
            }
        }
    }
}
