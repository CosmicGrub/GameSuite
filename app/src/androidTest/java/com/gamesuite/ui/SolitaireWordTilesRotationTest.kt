package com.gamesuite.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import com.gamesuite.MainActivity
import com.gamesuite.NavRotationTestSupport
import com.gamesuite.NavRotationTestSupport.backToMenu
import com.gamesuite.NavRotationTestSupport.cycleOrientations
import com.gamesuite.NavRotationTestSupport.navigateFromMenu
import com.gamesuite.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Real on-device nav + rotation coverage for Solitaire and Word Tiles (vs CPU),
 * per the Fold 5 orientation/rotation audit. Every test here:
 *  1. navigates in via a real button click found by its actual R.string resource
 *  2. runs a real sensor-level rotation sweep via NavRotationTestSupport.cycleOrientations
 *  3. asserts the Activity is still RESUMED afterward (the crash/survival signal)
 *  4. returns to the menu via backToMenu() so tests don't leak state into each other
 *  5. wraps the rotation in captureJankAround so real frame-timing lands in logcat
 *
 * Both games also get a SECOND test that performs one real gameplay interaction
 * before rotating, so the rotation exercises real mid-game state/UI:
 *  - Solitaire has no stable text/contentDescription/testTag on its stock pile itself
 *    (StockPileView in SolitaireScreen.kt sets none, unlike its WastePileView/
 *    FoundationPileView/TableauColumnView siblings, which all do). Rather than skip
 *    the historically-buggy stock-draw path this class exists to cover, the stock is
 *    located geometrically: it's the only clickable node sharing the waste pile's row
 *    (same top) that sits to its left -- a real, stable layout invariant of the felt
 *    board (Stock, then Waste, then the foundations, left to right), not a pixel value
 *    that would move on an ordinary restyle. The draw is then verified the same way a
 *    screen reader would notice it: WastePileView's own contentDescription flips from
 *    the literal "Empty waste pile" to "<card>, waste pile" once a card lands on it.
 *  - Word Tiles' "Pass" button (TileGameScreen.kt) is always present and, unlike
 *    Submit/Swap, always enabled with no staged-word or rack-selection precondition --
 *    it only requires it being the human's turn, which it is from the very first frame
 *    of a fresh match (currentPlayerIndex starts at 0, and the "You" seat in
 *    MainMenuScreen's word-tiles launch is localPlayerIndex 0).
 */
class SolitaireWordTilesRotationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun solitaire_navigateInAndRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()
        composeTestRule.navigateFromMenu(
            composeTestRule.activity.getString(R.string.game_solitaire)
        )
        // Let the game screen's own entrance animation (the staggered deal-in) actually
        // start/settle before we start yanking the sensor orientation around underneath it.
        Thread.sleep(600)

        NavRotationTestSupport.captureJankAround("solitaire") {
            with(device) { cycleOrientations("solitaire") }
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
    fun solitaire_drawFromStock_thenRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()
        composeTestRule.navigateFromMenu(
            composeTestRule.activity.getString(R.string.game_solitaire)
        )
        Thread.sleep(600)

        // Locate the stock pile: the only clickable node in the same row (same top) as
        // the waste pile, sitting to its left. See the class KDoc for why this geometric
        // lookup is used instead of a text/contentDescription selector.
        val wasteBounds = composeTestRule
            .onNodeWithContentDescription("Empty waste pile", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        val clickableNodes = composeTestRule.onAllNodes(hasClickAction(), useUnmergedTree = true)
        val stockIndex = clickableNodes.fetchSemanticsNodes()
            .withIndex()
            .filter { (_, node) ->
                kotlin.math.abs(node.boundsInRoot.top - wasteBounds.top) < 8f &&
                    node.boundsInRoot.right <= wasteBounds.left + 8f
            }
            .maxByOrNull { (_, node) -> node.boundsInRoot.right }
            ?.index
            ?: error("Could not locate Solitaire's stock pile (expected a clickable node just left of the waste pile)")

        clickableNodes.get(stockIndex).performClick()
        composeTestRule.waitForIdle()

        // Confirm a real card actually moved onto the waste pile -- WastePileView's own
        // contentDescription flips from "Empty waste pile" to "<card>, waste pile".
        composeTestRule.onNode(hasContentDescription(", waste pile", substring = true), useUnmergedTree = true)
            .assertExists()

        NavRotationTestSupport.captureJankAround("solitaire_midgame") {
            with(device) { cycleOrientations("solitaire_midgame") }
        }
        composeTestRule.waitForIdle()

        assertEquals(
            Lifecycle.State.RESUMED,
            composeTestRule.activityRule.scenario.state
        )

        // Real interaction still possible post-rotation: the drawn card's waste-pile node
        // must still be findable, not stuck in a frozen/broken semantics tree.
        composeTestRule.onNode(hasContentDescription(", waste pile", substring = true), useUnmergedTree = true)
            .assertExists()

        with(device) { backToMenu() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun wordTiles_navigateInAndRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()
        composeTestRule.navigateFromMenu(
            composeTestRule.activity.getString(R.string.game_word_tiles_vs_cpu)
        )
        Thread.sleep(600)

        NavRotationTestSupport.captureJankAround("wordTiles") {
            with(device) { cycleOrientations("wordTiles") }
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
    fun wordTiles_passTurn_thenRotate_survivesAndStaysResponsive() {
        val device = NavRotationTestSupport.uiDevice()
        composeTestRule.waitForIdle()
        composeTestRule.navigateFromMenu(
            composeTestRule.activity.getString(R.string.game_word_tiles_vs_cpu)
        )
        Thread.sleep(600)

        // "Pass" is always present and always a legal move with no staged-word or
        // rack-selection precondition -- see the class KDoc. Tapping it gives this
        // rotation cycle real, non-initial match state (turn already advanced to the
        // CPU and back, rack already redrawn) instead of only ever rotating the
        // freshest possible screen.
        composeTestRule.onNodeWithText("Pass").performClick()
        composeTestRule.waitForIdle()
        // Give the CPU's own turn (and its handoff back to the human) a moment to
        // resolve before rotating on top of it.
        Thread.sleep(1200)

        NavRotationTestSupport.captureJankAround("wordTiles_midgame") {
            with(device) { cycleOrientations("wordTiles_midgame") }
        }
        composeTestRule.waitForIdle()

        assertEquals(
            Lifecycle.State.RESUMED,
            composeTestRule.activityRule.scenario.state
        )

        // Real interaction still possible post-rotation: the board's Pass control must
        // still be findable and clickable, not stuck in a frozen recomposition.
        composeTestRule.onNodeWithText("Pass").assertExists()

        with(device) { backToMenu() }
        composeTestRule.waitForIdle()
    }
}
