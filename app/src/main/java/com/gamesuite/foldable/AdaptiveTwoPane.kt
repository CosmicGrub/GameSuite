package com.gamesuite.foldable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The single entry point game screens should use for a "table vs. hand"
 * split going forward — combines the existing, on-device-verified
 * [FoldAwareTwoPane] hinge split with a NEW wide-screen side-panel layout
 * for large windows that aren't an active fold split (a Galaxy Tab S9, or
 * a Z Fold 5 fully flat-open in landscape). See docs/DEVICE_SPECIFIC_PLAN.md
 * §3 for the design this implements.
 *
 * Deliberately does NOT special-case [AdaptiveLayoutMode.COVER] — a narrow
 * cover-screen-class window already gets a correct single-column layout
 * from [FoldAwareTwoPane]'s own fallback branch (primary then secondary,
 * stacked), and every game's own board/grid already has the responsive
 * cell-size/scroll-fallback treatment added during the compatibility audit.
 * A bespoke bottom-sheet-style cover layout is real future work (see the
 * roadmap), not something this pass claims to deliver.
 */
@Composable
fun AdaptiveTwoPane(
    foldState: FoldState,
    modifier: Modifier = Modifier,
    primary: @Composable () -> Unit,
    secondary: (@Composable () -> Unit)? = null
) {
    val mode = rememberAdaptiveLayoutMode(foldState)

    when (mode) {
        AdaptiveLayoutMode.TABLET -> {
            if (secondary != null) {
                // ~70/30 supporting-pane split per DEVICE_SPECIFIC_PLAN.md §3: primary
                // (the board/table) is capped rather than stretched to the full width —
                // an unbounded-width board looks stretched and thin on an 11" tablet —
                // and the freed width becomes a real side panel instead of empty margin.
                //
                // widthIn(max) must NOT be chained on the same modifier as
                // weight(1f) (or fillMaxSize()) — an exact-fill constraint
                // upstream (weight's forced slot width) wins over a later
                // widthIn(max) cap on the SAME element, silently making the
                // cap a no-op. Confirmed on-device with a temporary debug
                // border: the "capped" box still spanned the full weighted
                // slot width, uncapped. The fix is to split into an outer
                // Box that fills the weighted slot and centers its content
                // (contentAlignment = TopCenter), and a separate INNER Box
                // that only carries widthIn(max) — no competing fillMax*
                // call — which is what actually constrains the content's
                // measured width. Re-verified on-device after the fix:
                // primary's content sits centered within the weighted slot,
                // matching within a couple dp.
                Row(modifier = modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(modifier = Modifier.widthIn(max = 840.dp).fillMaxHeight()) { primary() }
                    }
                    Box(modifier = Modifier.width(320.dp).fillMaxHeight()) { secondary() }
                }
            } else {
                // Same widthIn-must-not-share-a-chain-with-fillMax* fix as above —
                // outer Box fills+centers, inner Box carries the width cap alone.
                Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(modifier = Modifier.widthIn(max = 840.dp).fillMaxHeight()) { primary() }
                }
            }
        }

        // FOLD_SPLIT, COVER, DEFAULT — all handled correctly by the existing,
        // already-verified fallback/hinge-split logic in FoldAwareTwoPane.
        else -> FoldAwareTwoPane(foldState = foldState, modifier = modifier, primary = primary, secondary = secondary)
    }
}
