package com.gamesuite.foldable

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowWidthSizeClass

/**
 * How a screen should lay itself out for the device/window it's currently
 * running on. Combines the existing [FoldState] hinge-posture signal with
 * a window-width size class, per docs/DEVICE_SPECIFIC_PLAN.md §3:
 *
 *  1. An active fold split (book/tabletop posture) always wins — screens
 *     already using [FoldAwareTwoPane] keep doing exactly that, untouched.
 *  2. Otherwise, Compact width (a folded Z Fold 5 cover screen, or any
 *     narrow/phone-class window) gets [COVER].
 *  3. Otherwise, Expanded width (a Galaxy Tab S9, or a Z Fold 5 fully
 *     flat-open in landscape) gets [TABLET].
 *  4. Anything else (Medium width) gets [DEFAULT] — today's single-pane
 *     layout, unchanged.
 *
 * Size class is read at runtime, not hardcoded from known device dp
 * values — Settings → Display → Screen zoom changes reported density, and
 * this stays correct either way.
 */
enum class AdaptiveLayoutMode { FOLD_SPLIT, COVER, TABLET, DEFAULT }

@Composable
fun rememberAdaptiveLayoutMode(foldState: FoldState): AdaptiveLayoutMode {
    if (foldState.isSeparating) return AdaptiveLayoutMode.FOLD_SPLIT

    val widthClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
    return when (widthClass) {
        WindowWidthSizeClass.COMPACT -> AdaptiveLayoutMode.COVER
        WindowWidthSizeClass.EXPANDED -> AdaptiveLayoutMode.TABLET
        else -> AdaptiveLayoutMode.DEFAULT
    }
}
