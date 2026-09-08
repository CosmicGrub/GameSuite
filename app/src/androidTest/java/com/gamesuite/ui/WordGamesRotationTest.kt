package com.gamesuite.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamesuite.NavRotationTestSupport
import com.gamesuite.NavRotationTestSupport.backToMenu
import com.gamesuite.NavRotationTestSupport.captureJankAround
import com.gamesuite.NavRotationTestSupport.cycleOrientations
import com.gamesuite.NavRotationTestSupport.navigateFromMenu
import com.gamesuite.NavRotationTestSupport.waitForNode
import com.gamesuite.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real on-device navigation + rotation sweep for the Word Games cluster:
 * Hangman, Word Search, Crossword. Every test here navigates in from the
 * real main menu (by real button click, found by the real R.string used on
 * the button — never a hardcoded literal), puts the device through a real
 * sensor-level rotation cycle via [NavRotationTestSupport], and asserts the
 * Activity is still RESUMED afterward — the actual crash-detection signal,
 * since a dead Activity would leave the scenario in a non-RESUMED state
 * instead of silently passing.
 *
 * Hangman and Crossword each get a second test that also performs one real,
 * always-legal gameplay interaction before rotating, so the rotation is
 * exercised against genuine mid-game state (a guessed letter / a selected
 * clue) rather than only ever the freshest possible screen. Word Search does
 * NOT get this treatment: its board is a single Canvas with one summary
 * contentDescription ("Word search grid, drag from one letter to another to
 * select a word") and no per-cell semantics or click targets — the only way
 * to make a "real" move is a drag gesture between two letter positions,
 * which would require computing exact on-screen cell pixel coordinates from
 * the generated puzzle layout. That's exactly the kind of brittle,
 * timing/layout-dependent test this task says not to write, so it's skipped
 * here with this note rather than forced.
 */
@RunWith(AndroidJUnit4::class)
class WordGamesRotationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<com.gamesuite.MainActivity>()

    // ---------------------------------------------------------------
    // Hangman
    // ---------------------------------------------------------------

    @Test
    fun hangman_navigateInAndRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()
        composeTestRule.navigateFromMenu(composeTestRule.activity.getString(R.string.game_hangman))
        Thread.sleep(600) // let the game screen's own entrance animation settle
        captureJankAround("hangman") {
            with(device) { cycleOrientations("hangman") }
        }
        composeTestRule.waitForIdle()
        assertEquals(
            Lifecycle.State.RESUMED,
            composeTestRule.activityRule.scenario.state
        )
        with(device) { backToMenu() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun hangman_guessLetterThenRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()
        composeTestRule.navigateFromMenu(composeTestRule.activity.getString(R.string.game_hangman))
        Thread.sleep(600)
        // DIAGNOSTIC (temporary): dump the real on-device semantics tree.
        composeTestRule.onRoot().printToLog("GameSuiteE2E_HM_DUMP")
        // A fixed sleep is not reliable proof the keyboard has actually
        // composed and attached its semantics yet on real hardware -- confirmed
        // on a real on-device run (this exact click intermittently failed to
        // find the node under a plain sleep). Poll for it instead.
        composeTestRule.waitForNode(hasContentDescription("Letter A, not guessed"))

        // A fresh Hangman round has every letter unguessed, so "Letter A, not
        // guessed" is always present and always a legal move regardless of
        // the (randomly chosen) target word -- guessing a letter is legal
        // whether it turns out right or wrong, so this can never hit a
        // disabled/invalid button.
        composeTestRule.onNodeWithContentDescription("Letter A, not guessed").performClick()
        composeTestRule.waitForIdle()

        captureJankAround("hangman_midgame") {
            with(device) { cycleOrientations("hangman_midgame") }
        }
        composeTestRule.waitForIdle()
        assertEquals(
            Lifecycle.State.RESUMED,
            composeTestRule.activityRule.scenario.state
        )

        // Real interaction remains possible post-rotation: a different,
        // still-unguessed letter's node is found by its real content
        // description and is still genuinely clickable -- proving the UI is
        // live, not frozen, after the rotation cycle (letter A itself is
        // deliberately not re-queried here: it's now guessed, so its
        // "not guessed" content description no longer exists by design).
        composeTestRule.onNodeWithContentDescription("Letter B, not guessed").performClick()
        composeTestRule.waitForIdle()

        with(device) { backToMenu() }
        composeTestRule.waitForIdle()
    }

    // ---------------------------------------------------------------
    // Word Search
    // ---------------------------------------------------------------

    @Test
    fun wordSearch_navigateInAndRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()
        composeTestRule.navigateFromMenu(composeTestRule.activity.getString(R.string.game_word_search))
        Thread.sleep(600)
        captureJankAround("wordSearch") {
            with(device) { cycleOrientations("wordSearch") }
        }
        composeTestRule.waitForIdle()
        assertEquals(
            Lifecycle.State.RESUMED,
            composeTestRule.activityRule.scenario.state
        )
        // The board's single summary contentDescription must still be there
        // and the grid still composed after rotation -- proof the screen
        // didn't end up blank/frozen, without depending on drag pixel math.
        // Poll rather than assert-immediately: confirmed on a real on-device
        // run that post-rotation recomposition can genuinely still be
        // in-flight the instant waitForIdle() returns -- waitForIdle() drains
        // Compose's own frame/snapshot queue, not the specific real-hardware
        // settle time a rotation-triggered AdaptiveLayoutMode branch switch
        // can take.
        // DIAGNOSTIC (temporary): dump the real on-device semantics tree.
        composeTestRule.onRoot().printToLog("GameSuiteE2E_WS_DUMP")
        composeTestRule.waitForNode(
            hasContentDescription("Word search grid, drag from one letter to another to select a word")
        )
        composeTestRule.onNodeWithContentDescription(
            "Word search grid, drag from one letter to another to select a word"
        ).assertExists()
        with(device) { backToMenu() }
        composeTestRule.waitForIdle()
    }

    // ---------------------------------------------------------------
    // Crossword
    // ---------------------------------------------------------------

    @Test
    fun crossword_navigateInAndRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()
        composeTestRule.navigateFromMenu(composeTestRule.activity.getString(R.string.game_crossword))
        Thread.sleep(600)
        captureJankAround("crossword") {
            with(device) { cycleOrientations("crossword") }
        }
        composeTestRule.waitForIdle()
        assertEquals(
            Lifecycle.State.RESUMED,
            composeTestRule.activityRule.scenario.state
        )
        with(device) { backToMenu() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun crossword_selectClueThenRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()
        composeTestRule.navigateFromMenu(composeTestRule.activity.getString(R.string.game_crossword))
        Thread.sleep(600)

        // Every generated crossword grid numbers its first entry "1" (by
        // definition of crossword numbering -- the earliest numbered cell is
        // always 1), and it's always among the top items in the clue list
        // right after entering a fresh puzzle, so a clue whose text starts
        // with "1A." or "1D." is always present and always a legal tap
        // (selecting a clue is never invalid). A standard crossword grid can
        // legally have BOTH a "1A." and a "1D." (the same numbered cell
        // starting both an across and a down word), so this matches on
        // whichever comes first in the list rather than assuming exactly one
        // hit. Tapping it opens the answer dialog via game.selectEntry(...);
        // Cancel dismisses it again, leaving the underlying game with a real
        // selected-entry state (not the freshest possible screen) without
        // leaving a live Dialog window up through the rotation cycle itself.
        val firstClue = hasText("1A.", substring = true) or hasText("1D.", substring = true)
        // DIAGNOSTIC (temporary): dump the real on-device semantics tree.
        composeTestRule.onRoot().printToLog("GameSuiteE2E_CW_DUMP")
        // A fixed sleep is not reliable proof the LazyColumn clue list has
        // actually composed its first row yet on real hardware -- confirmed
        // on a real on-device run. Poll for it instead.
        composeTestRule.waitForNode(firstClue)
        composeTestRule.onAllNodes(firstClue).onFirst().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        captureJankAround("crossword_midgame") {
            with(device) { cycleOrientations("crossword_midgame") }
        }
        composeTestRule.waitForIdle()
        assertEquals(
            Lifecycle.State.RESUMED,
            composeTestRule.activityRule.scenario.state
        )

        // Real interaction remains possible post-rotation: the same clue is
        // still there and still opens its dialog on tap.
        composeTestRule.onAllNodes(firstClue).onFirst().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        with(device) { backToMenu() }
        composeTestRule.waitForIdle()
    }
}
