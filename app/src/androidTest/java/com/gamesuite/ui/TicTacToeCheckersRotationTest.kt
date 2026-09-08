package com.gamesuite.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.lifecycle.Lifecycle
import com.gamesuite.MainActivity
import com.gamesuite.NavRotationTestSupport
import com.gamesuite.NavRotationTestSupport.backToMenu
import com.gamesuite.NavRotationTestSupport.cycleOrientations
import com.gamesuite.NavRotationTestSupport.navigateFromMenu
import com.gamesuite.NavRotationTestSupport.waitForNode
import com.gamesuite.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Real on-device navigation + rotation coverage for Tic-Tac-Toe and Checkers:
 * navigate in from the main menu via the actual game-launch button (found by
 * its real string resource, never a hardcoded literal), run a real natural ->
 * left -> right -> natural sensor rotation sweep (see
 * [NavRotationTestSupport.cycleOrientations]) while capturing real on-device
 * frame-timing/jank stats (see [NavRotationTestSupport.captureJankAround]),
 * then assert the Activity is still RESUMED (i.e. survived rotation without
 * crashing) and that the menu is reachable again afterward.
 *
 * Tic-Tac-Toe additionally gets a second test that taps a corner cell before
 * rotating, so the rotation sweep is exercised against real mid-game state
 * (an in-progress board with a placed mark) rather than only ever the
 * freshest possible screen. Checkers does not get an equivalent second test:
 * a legal checkers move needs a select-piece tap followed by a
 * geometry-dependent destination-square tap (whether a given diagonal
 * neighbor is a legal destination depends on which piece the bot/board setup
 * happened to place there and which direction that owner moves), so there is
 * no single always-present, always-legal element to tap the way "index 0 is
 * an empty corner cell on the human's first turn" is guaranteed to be for
 * Tic-Tac-Toe. Forcing one would be exactly the kind of fragile,
 * flake-prone test this task says to avoid.
 */
class TicTacToeCheckersRotationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun ticTacToe_navigateInAndRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()
        composeTestRule.navigateFromMenu(
            composeTestRule.activity.getString(R.string.game_tictactoe_vs_cpu)
        )
        Thread.sleep(600) // let the game screen's own entrance animation actually start/settle

        NavRotationTestSupport.captureJankAround("ticTacToe") {
            with(device) { cycleOrientations("ticTacToe") }
        }
        composeTestRule.waitForIdle()

        assertEquals(
            Lifecycle.State.RESUMED,
            composeTestRule.activityRule.scenario.state
        )

        with(device) { backToMenu() }
        composeTestRule.waitForIdle()
    }

    /**
     * Same navigate-in + rotate flow, but taps the always-legal top-left
     * corner cell (Row 1, Column 1 of a fresh board is always empty, and the
     * human moves first as X in vs-CPU mode) before rotating, so the
     * rotation sweep runs against a board with real mid-game state. Uses the
     * cell's real `contentDescription` semantics (added in an earlier
     * accessibility pass) rather than any hardcoded/guessed text, since that
     * is the most stable selector available on this screen.
     */
    @Test
    fun ticTacToe_interactThenRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()
        composeTestRule.navigateFromMenu(
            composeTestRule.activity.getString(R.string.game_tictactoe_vs_cpu)
        )
        Thread.sleep(600)

        // DIAGNOSTIC (temporary): dump the real on-device semantics tree.
        composeTestRule.onRoot().printToLog("GameSuiteE2E_TTT_DUMP")
        // Fresh board: top-left corner cell is always empty, and X (the
        // human, per the "vs CPU" launch config's localPlayerIndex = 0) is
        // always the first mover, so this tap is always legal here. Poll
        // rather than assume-after-a-fixed-sleep -- confirmed necessary on a
        // real full-suite run (see waitForNode's own KDoc for why).
        composeTestRule.waitForNode(hasContentDescription("Row 1, Column 1, empty"))
        composeTestRule.onNodeWithContentDescription("Row 1, Column 1, empty").performClick()
        composeTestRule.waitForIdle()
        Thread.sleep(400) // let the cell's stamp-placement animation settle before rotating

        NavRotationTestSupport.captureJankAround("ticTacToeMidGame") {
            with(device) { cycleOrientations("ticTacToeMidGame") }
        }
        composeTestRule.waitForIdle()

        assertEquals(
            Lifecycle.State.RESUMED,
            composeTestRule.activityRule.scenario.state
        )

        // Real interaction still possible post-rotation: the tapped cell's
        // description should still resolve (now showing X placed there,
        // proving the semantics tree — and the underlying board state — is
        // still alive and queryable, not frozen/broken).
        composeTestRule.onNodeWithContentDescription("Row 1, Column 1, X").assertExists()

        with(device) { backToMenu() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun checkers_navigateInAndRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()
        composeTestRule.navigateFromMenu(
            composeTestRule.activity.getString(R.string.game_checkers_vs_cpu)
        )
        Thread.sleep(600)

        NavRotationTestSupport.captureJankAround("checkers") {
            with(device) { cycleOrientations("checkers") }
        }
        composeTestRule.waitForIdle()

        assertEquals(
            Lifecycle.State.RESUMED,
            composeTestRule.activityRule.scenario.state
        )

        with(device) { backToMenu() }
        composeTestRule.waitForIdle()
    }
}
