package com.gamesuite

import android.util.Log
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * Shared helpers for the orientation/rotation/navigation E2E sweep — every
 * per-game instrumented test in this package uses these instead of each
 * reimplementing its own rotation/jank-capture logic slightly differently.
 *
 * This suite tests REAL device behavior: actual sensor-level orientation
 * changes via [UiDevice] (not a config override), actual navigation through
 * the real Compose semantics tree, running on the real Galaxy Z Fold 5. It
 * cannot simulate a physical fold/unfold — that is a real hardware posture
 * change no software call can trigger — so it covers portrait/landscape
 * rotation exhaustively and leaves the fold/unfold transition itself to a
 * manual on-device check, which is already covered at the code level by the
 * FoldState/AdaptiveTwoPane audit from the prior pass.
 */
object NavRotationTestSupport {
    private const val TAG = "GameSuiteE2E"
    const val PACKAGE_NAME = "com.gamesuite"

    /** Bounded wait after any action that triggers a config change or a
     *  navigation transition, so the next assertion/action runs against
     *  settled UI rather than a mid-recomposition frame. */
    private const val SETTLE_MS = 900L

    fun uiDevice(): UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /**
     * Cycles the real device through natural -> left -> right -> natural,
     * settling after each leg. This is the actual rotation matrix a player
     * can hit on a phone-class window (folded cover screen, or a plain
     * phone) and exercises the full portrait<->landscape transition twice
     * (once each direction) per call, catching a bug that only appears on
     * the SECOND rotation (a stale cached position/size from before the
     * first rotation, the exact class of bug the last pass found and fixed
     * in Solitaire's card-flight destinations).
     */
    fun UiDevice.cycleOrientations(label: String) {
        waitForIdle()
        Log.i(TAG, "[$label] rotating: natural -> left")
        setOrientationLeft()
        wait(Until.hasObject(androidx.test.uiautomator.By.pkg(PACKAGE_NAME)), 3000)
        Thread.sleep(SETTLE_MS)
        Log.i(TAG, "[$label] rotating: left -> right")
        setOrientationRight()
        Thread.sleep(SETTLE_MS)
        Log.i(TAG, "[$label] rotating: right -> natural")
        setOrientationNatural()
        Thread.sleep(SETTLE_MS)
    }

    /** Resets this app's frame-timing stats, run [block], then dumps the
     *  post-block stats to logcat under [TAG] -- a real, on-device proxy for
     *  "was this responsive" (janky-frame count, 90th/95th/99th percentile
     *  frame time) without ever capturing a screenshot. Grep the host-side
     *  logcat for "GFXINFO[label]" after a run to read the numbers. */
    fun captureJankAround(label: String, block: () -> Unit) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            automation.executeShellCommand("dumpsys gfxinfo $PACKAGE_NAME reset").close()
        }
        block()
        runCatching {
            val stream = automation.executeShellCommand("dumpsys gfxinfo $PACKAGE_NAME")
            val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(stream)
                .bufferedReader().use { it.readText() }
            Log.i(TAG, "GFXINFO[$label] ----- begin -----")
            text.lineSequence()
                .filter { line ->
                    line.contains("Janky", ignoreCase = true) ||
                        line.contains("Percentile", ignoreCase = true) ||
                        line.contains("Number Missed Vsync", ignoreCase = true) ||
                        line.contains("Total frames rendered", ignoreCase = true)
                }
                .forEach { Log.i(TAG, "GFXINFO[$label] $it") }
            Log.i(TAG, "GFXINFO[$label] ----- end -----")
        }
    }

    /** Presses the real hardware/gesture back action and settles -- used to
     *  return to the main menu between games in a multi-game test. */
    fun UiDevice.backToMenu() {
        pressBack()
        Thread.sleep(SETTLE_MS)
    }

    /**
     * Polls (via the Compose test framework's own [ComposeTestRule.waitUntil],
     * not a blind [Thread.sleep]) until at least one node matching [matcher]
     * exists, up to [timeoutMillis]. This replaces a fixed post-navigation
     * sleep before the FIRST interaction on a freshly-entered or just-rotated
     * screen -- confirmed necessary on real hardware, not just in principle.
     *
     * IMPORTANT, learned the hard way on a real on-device run: this alone is
     * NOT sufficient proof a menu-to-game navigation actually happened.
     * Raising the timeout from 5s to 15s made zero difference to several
     * failures -- a `printToLog()` dump taken right at the timeout showed the
     * app was still sitting on the MAIN MENU, not the target game, with no
     * exception anywhere. The real bug was upstream: `onNodeWithText(button).
     * performClick()` invokes a semantics click action directly, and on real
     * hardware that invocation can race the app's own recomposition/
     * navigation-commit timing -- `waitForIdle()` plus a fixed sleep is not
     * proof the click's SIDE EFFECT (NavController actually changing screens)
     * has landed, only that Compose's own snapshot/frame queue is drained.
     * [navigateFromMenu] is the actual fix for that specific race; this
     * function is still the right tool for waiting on an in-screen element
     * once navigation is already confirmed.
     */
    fun ComposeTestRule.waitForNode(matcher: SemanticsMatcher, timeoutMillis: Long = 15000L) {
        waitUntil(timeoutMillis) {
            onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Clicks the main-menu button whose text is exactly [buttonText], then
     * polls until that same button's node is no longer part of the semantics
     * tree -- real proof the click's navigation side effect actually landed,
     * not just that the click action fired without throwing. See
     * [waitForNode]'s own KDoc for the real on-device failure this fixes:
     * `performClick()` succeeding is not proof `NavController.navigate(...)`
     * has actually committed yet on real hardware.
     *
     * Every game-launch button's text is unique across the whole main menu
     * (confirmed against `strings.xml` and `MainMenuScreen.kt`), and no game
     * screen re-renders that same literal string anywhere in its own content,
     * so "the button's text is gone" is a safe, specific signal that the menu
     * itself is no longer what's on screen.
     */
    fun ComposeTestRule.navigateFromMenu(buttonText: String, timeoutMillis: Long = 15000L) {
        // performScrollTo() first unconditionally -- several menu entries sit
        // below the fold in the scrollable menu list, and a real click
        // gesture (not just a semantics action invocation) needs the node's
        // actual on-screen bounds to be meaningful. A harmless no-op for a
        // button already fully visible.
        onNodeWithText(buttonText).performScrollTo().performClick()
        val buttonMatcher = hasText(buttonText)
        waitUntil(timeoutMillis) {
            onAllNodes(buttonMatcher).fetchSemanticsNodes().isEmpty()
        }
        // Deliberately NOT calling waitForIdle() here -- at least one real
        // destination screen (UNO) runs a continuous rememberInfiniteTransition
        // for as long as it's on screen, which means Compose never reports
        // "idle" there and waitForIdle() hangs until ComposeNotIdleException.
        // Confirmed on a real on-device run. The waitUntil() above already
        // proves the navigation side effect landed; callers needing further
        // settling should use a bounded Thread.sleep() instead.
    }

    // A pure-UiAutomator alternative to navigateFromMenu/waitForNode was
    // tried and removed here (see UnoRotationTest's and
    // AirHockeySlidingPuzzleRotationTest's own class KDocs for the full
    // story): for a screen with a continuous animation running, UiAutomator
    // queries against real on-screen content turned out to be UNRELIABLE too
    // (not just Compose's own test-synchronization), for reasons never fully
    // isolated. The pattern that actually works for those two screens is
    // simpler -- a plain Compose click with no confirmation to navigate in,
    // and nothing that queries either framework once landed -- and lives
    // directly in each of those two test classes rather than as a shared
    // helper here, since it deliberately does NOT do the extra verification
    // every other game in this suite gets.
}
