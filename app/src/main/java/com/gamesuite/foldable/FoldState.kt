package com.gamesuite.foldable

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.collect

enum class HingeOrientation { NONE, VERTICAL, HORIZONTAL }

/**
 * Snapshot of the device's current fold posture, derived from Jetpack
 * WindowManager's WindowLayoutInfo. `isSeparating` + a real orientation is
 * the signal to actually split the UI across the hinge (Z Fold 5 unfolded,
 * "book" posture = vertical hinge, "tabletop" = horizontal hinge lying
 * flat with the screen angled). A device with no folding feature at all
 * (Tab S9, a phone, the Fold's cover screen) reports NONE/not separating —
 * screens should render their normal single-pane layout in that case.
 */
data class FoldState(
    val hasFoldingFeature: Boolean,
    val isSeparating: Boolean,
    val orientation: HingeOrientation,
    /** Hinge bounds in this window's coordinate space (px), or null if not applicable. */
    val hingeBoundsPx: Rect?
) {
    val isBookPosture: Boolean get() = isSeparating && orientation == HingeOrientation.VERTICAL
    val isTabletopPosture: Boolean get() = isSeparating && orientation == HingeOrientation.HORIZONTAL

    companion object {
        val None = FoldState(hasFoldingFeature = false, isSeparating = false, orientation = HingeOrientation.NONE, hingeBoundsPx = null)
    }
}

val LocalFoldState = compositionLocalOf { FoldState.None }

/**
 * Observes WindowInfoTracker for the given Activity and exposes the latest
 * FoldState. Call once near the root (MainActivity) and provide it via
 * LocalFoldState so any game screen can read it without each screen wiring
 * up its own WindowManager listener.
 */
@Composable
fun rememberFoldState(activity: Activity): FoldState {
    val state by produceState(initialValue = FoldState.None, activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { layoutInfo: WindowLayoutInfo ->
            value = deriveFoldState(layoutInfo)
        }
    }
    return state
}

private fun deriveFoldState(layoutInfo: WindowLayoutInfo): FoldState {
    val feature = layoutInfo.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        ?: return FoldState.None

    val orientation = when (feature.orientation) {
        FoldingFeature.Orientation.VERTICAL -> HingeOrientation.VERTICAL
        FoldingFeature.Orientation.HORIZONTAL -> HingeOrientation.HORIZONTAL
        else -> HingeOrientation.NONE
    }

    val b = feature.bounds
    return FoldState(
        hasFoldingFeature = true,
        isSeparating = feature.isSeparating,
        orientation = orientation,
        hingeBoundsPx = Rect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
    )
}
