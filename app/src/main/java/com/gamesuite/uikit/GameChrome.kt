package com.gamesuite.uikit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The corner button's own touch target. 48dp is the accessibility floor for a control. */
private val ChromeButtonSize = 48.dp
private val ChromeButtonMargin = 4.dp

/**
 * How much room to leave at the END of whatever sits in the top-right of a game's own layout
 * (a score chip, a status row) so the corner button never overlaps or clips it. Found on a real
 * tablet during the Reversi pilot: the button was aligned to the screen's top-end and sat on top
 * of the CPU chip the status row anchors to its own end, clipping its text.
 */
val GameChromeEndInset: Dp = ChromeButtonSize + ChromeButtonMargin * 2

/**
 * The one piece of chrome every game was missing relative to the UNO / Air Hockey bar: a quiet
 * corner affordance (no permanent top bar competing with the stage) plus the three behaviours a
 * game screen has to get right and almost none did:
 *
 *  - **Back** is intercepted. System back used to silently pop the nav stack mid-match with no
 *    warning and no way to know whether the match counted. While [matchInProgress] it now asks
 *    first; once the board is finished it just leaves.
 *  - **Leaving mid-match is an abort**, never a win/loss: [onAbort] should end the match with
 *    `wasAborted = true` (see `GameModule.abortMatch`), which GameSessionManager never records.
 *    [onLeave] is the finished-board path (typically the game's own `leaveSession()`, which
 *    scores the session).
 *  - **Help**: a short how-to-play dialog from the same menu.
 *
 * Wrap the game's existing root layout as [content]; it's drawn first and the corner button is
 * drawn above it. Callers reserve [GameChromeEndInset] at the end of their top row.
 *
 * [extraItems] lets a game add its own entries (e.g. Pause, New Board) above "Back to Menu".
 *
 * Colors default to the Material theme but a game with its own palette (Reversi's warm cream)
 * should pass it so the button reads as part of that screen rather than a Material overlay.
 */
@Composable
fun GameChrome(
    helpTitle: String,
    helpText: String,
    matchInProgress: Boolean,
    onLeave: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
    buttonFill: Color = MaterialTheme.colorScheme.background,
    buttonContent: Color = MaterialTheme.colorScheme.onBackground,
    leaveTitle: String = "Leave this game?",
    leaveBody: String = "This game is still in progress. Leaving now won't count it as a win or a loss.",
    extraItems: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    // A double-tap on "Leave" (or back while the abort is still propagating) must not end the
    // match twice -- the second endMatch would fire the game's onMatchEnd/navigation again.
    var leaving by remember { mutableStateOf(false) }

    fun requestLeave() {
        if (leaving) return
        if (matchInProgress) showLeaveConfirm = true else { leaving = true; onLeave() }
    }
    BackHandler(onBack = ::requestLeave)

    Box(modifier = modifier.fillMaxSize()) {
        content()

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(ChromeButtonMargin)
        ) {
            Box(
                modifier = Modifier
                    .size(ChromeButtonSize)
                    .clip(CircleShape)
                    .background(buttonFill)
                    .border(1.dp, buttonContent.copy(alpha = 0.35f), CircleShape)
                    .semantics { contentDescription = "Game menu" }
                    .clickable(onClickLabel = "Open game menu", role = Role.Button) { showMenu = true },
                contentAlignment = Alignment.Center
            ) {
                // The glyph is decoration; without this a screen reader reads "vertical ellipsis".
                Text(
                    "⋮",
                    color = buttonContent,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text("How to Play") }, onClick = { showMenu = false; showHelp = true })
                extraItems { showMenu = false }
                DropdownMenuItem(text = { Text("Back to Menu") }, onClick = { showMenu = false; requestLeave() })
            }
        }
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(leaveTitle) },
            text = { Text(leaveBody) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    if (!leaving) { leaving = true; onAbort() }
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { showLeaveConfirm = false }) { Text("Keep Playing") } }
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(helpTitle) },
            text = { Text(helpText) },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Got it") } }
        )
    }
}
