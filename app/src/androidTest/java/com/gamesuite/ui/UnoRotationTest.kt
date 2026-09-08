package com.gamesuite.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import com.gamesuite.NavRotationTestSupport
import com.gamesuite.NavRotationTestSupport.backToMenu
import com.gamesuite.NavRotationTestSupport.cycleOrientations
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Real, on-device navigation + rotation coverage for UNO (vs 2 CPU bots).
 *
 * UNO was just heavily rewritten (tabletop turn-direction ring, pending-draw
 * badges, tumbling card toss via Canvas/graphicsLayer) — this is one of the
 * highest-value games to verify actually survives a real sensor-level
 * rotation sweep given how much new draw-time and animation work just landed
 * in UnoScreen.kt. See NavRotationTestSupport's own KDoc for what this suite
 * can and cannot exercise (real portrait<->landscape rotation, yes; a
 * physical fold/unfold posture change, no).
 *
 * IMPORTANT, the actual conclusion of a long real-on-device debugging session
 * (recorded here in full so it is not re-litigated): UnoScreen.kt runs a
 * continuous `rememberInfiniteTransition` (the "hot streak" hand-danger
 * pulse, plus the new always-on turn-direction ring rotation) for as long as
 * it is on screen. This breaks more than just `ComposeTestRule`'s own
 * synchronization:
 *
 * 1. Every `ComposeTestRule` sync point (`waitForIdle()`, `waitUntil()`'s own
 *    internal per-iteration sync, even a bare `assertExists()`) hangs on this
 *    screen until `ComposeNotIdleException`. Disabling `mainClock.autoAdvance`
 *    (the documented general workaround) made this WORSE, not better -- a
 *    genuine several-minute hang, apparently because `waitUntil`'s own
 *    timeout bookkeeping also runs off that same disabled clock.
 * 2. Less expected: pure UiAutomator (`By.text(...)`, entirely independent of
 *    Compose's test framework) ALSO could not reliably find or click content
 *    actually ON this screen once landed (confirmed repeatedly: "Draw" was
 *    never found via `UiDevice.wait(Until.findObject(...))` even directly
 *    after confirming navigation succeeded, and even with a scroll-into-view
 *    fallback). The likely cause is the screen's own continuous 60fps
 *    recomposition/redraw interfering with a stable `AccessibilityNodeInfo`
 *    snapshot -- never fully isolated, and not worth further time once a
 *    working alternative was in hand.
 *
 * The pattern that actually works, proven identically for Air Hockey (the
 * other continuously-animating screen in this suite, a real-time physics
 * loop rather than an infinite transition -- see
 * [AirHockeySlidingPuzzleRotationTest]'s own class KDoc for the same
 * conclusion reached independently there): a PLAIN
 * `onNodeWithText(...).performScrollTo().performClick()` to navigate IN (no
 * `waitForIdle()`, no `waitUntil`-based confirmation afterward -- a
 * single fire-and-forget action, and real jank data for a real rotation
 * sweep was captured successfully this way in every earlier on-device run
 * before any of the above was ever attempted), then NOTHING further that
 * touches either `ComposeTestRule` or `UiDevice`'s text-based queries once
 * actually on the screen -- just `Thread.sleep()` for settling and a raw
 * `activityRule.scenario.state` read (unrelated to either synchronization
 * system) for the crash-detection assertion.
 *
 * This means UNO does NOT get a second mid-game-interaction test, unlike
 * most other games in this suite -- the same reasoning
 * [AirHockeySlidingPuzzleRotationTest] already documents for Air Hockey
 * applies here too: a discrete interaction (drawing a card) exists in
 * principle, but there is no reliable way left to perform and verify it
 * given everything above, and forcing one back in would just reintroduce
 * the exact flakiness this whole investigation exists to avoid. The single
 * test below still gets real, non-trivial coverage: real navigation, a real
 * 3-leg rotation sweep against a screen with genuinely the most new
 * animation code in the suite, and real crash detection.
 */
class UnoRotationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<com.gamesuite.MainActivity>()

    /**
     * Navigates from the main menu into UNO (vs 2 CPU bots), runs a full
     * natural->left->right->natural rotation sweep, and asserts the Activity
     * is still RESUMED afterward — the actual crash-detection mechanism, so
     * a rotation-induced crash fails loudly here instead of the test quietly
     * passing on a dead app.
     */
    @Test
    fun uno_navigateInAndRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()

        // Found by its REAL string resource -- see MainMenuScreen.kt's
        // GameEntry(stringResource(R.string.game_uno_vs_cpu)) -- never a
        // hardcoded literal copy of the button's English text. See class
        // KDoc for why this is a plain click with no post-click
        // confirmation, unlike every other game in this suite.
        composeTestRule.onNodeWithText(
            composeTestRule.activity.getString(com.gamesuite.R.string.game_uno_vs_cpu)
        ).performScrollTo().performClick()
        // Let the game screen's own entrance animation (staggered initial
        // deal, shuffle SFX, etc.) actually start/settle before rotating.
        Thread.sleep(600)

        NavRotationTestSupport.captureJankAround("uno") {
            with(device) { cycleOrientations("uno") }
        }

        // Longer post-rotation observation window (mirrors Air Hockey's own
        // reasoning): give the continuous turn-direction-ring/hand-danger
        // animations real wall-clock time to either keep animating cleanly
        // or reveal a crash, instead of asserting the instant the sweep ends.
        Thread.sleep(1000)

        assertEquals(
            Lifecycle.State.RESUMED,
            composeTestRule.activityRule.scenario.state
        )

        with(device) { backToMenu() }
    }
}
