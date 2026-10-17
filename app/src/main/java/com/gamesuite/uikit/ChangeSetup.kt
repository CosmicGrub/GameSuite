package com.gamesuite.uikit

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gamesuite.R

/**
 * "Change setup" on a finished game's result panel: ends the match exactly like the panel's
 * "Back to Menu" does, but returns to the game's Hub (modes and options) instead of the menu.
 *
 * MainActivity provides this local with a callback that marks the NEXT match end as "return to the
 * Hub"; it is null outside the app shell (previews, tests), where [ChangeSetupButton] draws nothing.
 */
val LocalChangeSetup = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * @param onLeave the same action the panel's "Back to Menu" button runs (the game's `leaveSession()`),
 *   so the finished session is scored once; the shell then routes to the Hub rather than the menu.
 */
@Composable
fun ChangeSetupButton(
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors()
) {
    val request = LocalChangeSetup.current ?: return
    OutlinedButton(
        onClick = {
            request()
            onLeave()
        },
        modifier = modifier.heightIn(min = 48.dp),
        colors = colors,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(stringResource(R.string.result_change_setup), textAlign = TextAlign.Center)
    }
}
