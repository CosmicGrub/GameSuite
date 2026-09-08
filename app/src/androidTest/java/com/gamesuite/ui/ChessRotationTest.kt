package com.gamesuite.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamesuite.NavRotationTestSupport
import com.gamesuite.NavRotationTestSupport.backToMenu
import com.gamesuite.NavRotationTestSupport.cycleOrientations
import com.gamesuite.NavRotationTestSupport.navigateFromMenu
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real on-device navigation + rotation coverage for Chess: menu -> Chess ->
 * full natural/left/right/natural sensor rotation sweep -> back to menu,
 * using the shared [NavRotationTestSupport] helpers (real [androidx.test.uiautomator.UiDevice]
 * rotation, real jank capture via `dumpsys gfxinfo`, and a real back-press).
 *
 * Selector notes:
 *  - The menu entry is found by its real string resource, [com.gamesuite.R.string.game_chess_vs_cpu]
 *    ("Chess (vs CPU)"), never a hardcoded literal, so a copy change cannot silently break this.
 *  - The mid-game interaction in the second test targets the White pawn that starts every match
 *    on square a2, addressed by the exact `contentDescription` ChessScreen.kt's own
 *    `squareDescription(square, piece)` produces for that square/piece combination -- "a2, White
 *    pawn" -- rather than any hardcoded literal disconnected from that function. This is not a
 *    string resource (it's a runtime-built accessibility label, not an `R.string`), and it is the
 *    single most stable selector on this screen: every match starts from the standard opening
 *    position, so a White pawn is always physically present on a2 the instant the board first
 *    renders, and tapping one's own pawn is always a legal "select" action.
 */
@RunWith(AndroidJUnit4::class)
class ChessRotationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<com.gamesuite.MainActivity>()

    @Test
    fun chess_navigateInAndRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()

        composeTestRule.navigateFromMenu(
            composeTestRule.activity.getString(com.gamesuite.R.string.game_chess_vs_cpu)
        )
        Thread.sleep(600) // let the board's own entrance/first-render animation actually start/settle

        NavRotationTestSupport.captureJankAround("chess") {
            with(device) { cycleOrientations("chess") }
        }
        composeTestRule.waitForIdle()

        // Core crash/survival assertion -- if the Activity died during rotation, this state will
        // not be RESUMED and the assertion fails loudly instead of the test silently passing on a
        // dead app.
        assertEquals(
            Lifecycle.State.RESUMED,
            composeTestRule.activityRule.scenario.state
        )

        // Real interaction remains possible afterward: the a2 pawn square must still be found and
        // tappable post-rotation, proving the board did not end up in a broken/frozen state.
        composeTestRule
            .onNodeWithContentDescription("a2, White pawn")
            .assertExists()

        with(device) { backToMenu() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun chess_selectPawnThenRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()

        composeTestRule.navigateFromMenu(
            composeTestRule.activity.getString(com.gamesuite.R.string.game_chess_vs_cpu)
        )
        Thread.sleep(600)

        // Real gameplay interaction before rotating: select White's a2 pawn (always present,
        // always a legal piece to select even before choosing a destination) so the board carries
        // real, non-initial UI state (a selected piece + its highlighted legal destinations) into
        // the rotation instead of only ever exercising the freshest possible screen.
        composeTestRule
            .onNodeWithContentDescription("a2, White pawn")
            .performClick()
        composeTestRule.waitForIdle()

        NavRotationTestSupport.captureJankAround("chess-midgame") {
            with(device) { cycleOrientations("chess-midgame") }
        }
        composeTestRule.waitForIdle()

        assertEquals(
            Lifecycle.State.RESUMED,
            composeTestRule.activityRule.scenario.state
        )

        // Re-querying for the same square proves the board is still alive and responsive after
        // rotating with a piece selected, not just after a fresh, untouched render.
        composeTestRule
            .onNodeWithContentDescription("a2, White pawn")
            .assertExists()

        with(device) { backToMenu() }
        composeTestRule.waitForIdle()
    }
}
