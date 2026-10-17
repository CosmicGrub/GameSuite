package com.gamesuite

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
     *  return to the app shell between games in a multi-game test. A game that
     *  is mid-match shows `GameChrome`'s "Leave" confirmation first, so this
     *  confirms it when present (a no-op for screens without that dialog).
     *  A game now sits behind its Game Hub (Games -> Hub -> game), so one back
     *  press from a game lands on the Hub; this keeps pressing back (at most
     *  twice more) until the bottom bar's "Games" tab is on screen again. */
    fun UiDevice.backToMenu() {
        pressBack()
        Thread.sleep(SETTLE_MS)
        findObject(androidx.test.uiautomator.By.text("Leave"))?.let {
            it.click()
            Thread.sleep(SETTLE_MS)
        }
        val gamesLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(com.gamesuite.R.string.nav_games)
        repeat(2) {
            if (findObject(androidx.test.uiautomator.By.text(gamesLabel)) == null) {
                pressBack()
                Thread.sleep(SETTLE_MS)
            }
        }
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
     * [openGameFromMenu] is the actual fix for that specific race; this
     * function is still the right tool for waiting on an in-screen element
     * once navigation is already confirmed.
     */
    fun ComposeTestRule.waitForNode(matcher: SemanticsMatcher, timeoutMillis: Long = 15000L) {
        waitUntil(timeoutMillis) {
            onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The Games-tab card for [title]: its content description is "Title. Tagline[. badges]" (see GameCard). */
    private fun gameCardMatcher(englishTitle: String): SemanticsMatcher {
        val title = localizedHubText(englishTitle)
        return SemanticsMatcher("contentDescription starts with \"$title. \"") { node ->
            SemanticsProperties.ContentDescription in node.config &&
                node.config[SemanticsProperties.ContentDescription].any { it.startsWith("$title. ") }
        }
    }

    /** The target app's current-locale text for an English catalog string (HubText.resolve's lookup, English fallback). */
    private fun localizedHubText(text: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = context.resources.getIdentifier(com.gamesuite.core.hubKey(text), "string", context.packageName)
        return if (id == 0) text else context.getString(id)
    }

    /** The Game Hub's big Play button; its text is "Play: <mode>" (see GameHubScreen's HubPlayButton). */
    private fun hubPlayMatcher(): SemanticsMatcher =
        hasText(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getString(com.gamesuite.R.string.hub_ui_play_title, ""),
            substring = true
        ) and hasClickAction()

    /**
     * Opens the game titled [title] the way a player does in the new app shell: bottom bar "Games" ->
     * the game's card -> its Game Hub -> the big "Play: ..." button -> the game. Replaces the old flat
     * menu's one-button-per-mode lookup (e.g. "Chess (vs CPU)"); the Play button repeats the Hub's
     * last/default mode, which for every game used by these tests is the vs-CPU / solo / free-play one.
     *
     * [familyChipLabel] (e.g. `getString(R.string.section_board_games)`) optionally taps that family
     * filter chip first to shorten the list; the Games list is also scrolled to the card
     * (`performScrollToNode`, since the card may not be composed yet in the lazy list).
     *
     * [confirmLanded]: when true, polls until the Play button is gone (the Hub was left, i.e. the
     * navigation side effect really landed -- see [waitForNode]'s KDoc for the on-device race). Pass
     * false for the continuously-animating screens (UNO, Air Hockey) whose tests deliberately issue no
     * Compose sync after the final click. Not for games with `skipsHub` (Breakout, Tower Defence, Party
     * Toolkit): their card launches straight in, so the Play wait here would time out.
     */
    fun ComposeTestRule.openGameFromMenu(
        title: String,
        familyChipLabel: String? = null,
        confirmLanded: Boolean = true,
        timeoutMillis: Long = 15000L
    ) {
        val card = gameCardMatcher(title)
        val play = hubPlayMatcher()
        if (onAllNodes(card).fetchSemanticsNodes().isEmpty()) {
            val gamesLabel = InstrumentationRegistry.getInstrumentation().targetContext
                .getString(com.gamesuite.R.string.nav_games)
            // The tab label comes first in the tree; on the Games screen itself the heading "Games" is a harmless no-op click.
            onAllNodesWithText(gamesLabel).onFirst().performClick()
            waitForNode(hasScrollToNodeAction(), timeoutMillis)
        }
        if (familyChipLabel != null) {
            onAllNodesWithText(familyChipLabel).onFirst().performClick()
        }
        onNode(hasScrollToNodeAction()).performScrollToNode(card)
        onNode(card).performClick()
        waitForNode(play, timeoutMillis)
        onNode(play).performScrollTo().performClick()
        if (confirmLanded) {
            waitUntil(timeoutMillis) {
                onAllNodes(play).fetchSemanticsNodes().isEmpty()
            }
        }
        // Deliberately NOT calling waitForIdle() here -- at least one real
        // destination screen (UNO) runs a continuous rememberInfiniteTransition
        // for as long as it's on screen, which means Compose never reports
        // "idle" there and waitForIdle() hangs until ComposeNotIdleException.
        // Confirmed on a real on-device run. The waitUntil() above already
        // proves the navigation side effect landed; callers needing further
        // settling should use a bounded Thread.sleep() instead.
    }

    // A pure-UiAutomator alternative to openGameFromMenu/waitForNode was
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
