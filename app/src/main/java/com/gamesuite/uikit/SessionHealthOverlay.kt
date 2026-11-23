package com.gamesuite.uikit

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.gamesuite.R
import com.gamesuite.core.GameSessionManager
import com.gamesuite.transport.SessionHealthSource
import com.gamesuite.transport.SessionNotice
import com.gamesuite.transport.sessionNoticeFor

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/**
 * The shell's [GameSessionManager] as seen from inside a game screen, found through the host Activity (the
 * manager is the Activity-scoped ViewModel the nav graph shares; a nav back-stack entry would hand out a
 * different owner). Null when there is no Activity (a Compose preview), in which case the chrome shows nothing.
 */
@Composable
private fun rememberSessionManager(): GameSessionManager? {
    val activity = LocalContext.current.findComponentActivity()
    return remember(activity) { activity?.let { ViewModelProvider(it)[GameSessionManager::class.java] } }
}

/**
 * Game-agnostic connection health for a networked match, drawn by [GameChrome] so no game needs any code of its own:
 *  - a short drop (this device's, the host's or another player's) shows a polite, TalkBack-announced banner;
 *  - the host leaving for good, or this device failing to get back in, shows a dialog and then [onEnded], which the
 *    chrome wires to the game's existing abort (no win or loss recorded). The dialog cannot be dismissed any other
 *    way: the match is already over, and there is nothing to keep playing.
 */
@Composable
internal fun BoxScope.SessionHealthOverlay(onEnded: () -> Unit) {
    val manager = rememberSessionManager() ?: return
    val ctx by manager.activeContext.collectAsState()
    val current = ctx
    val transport = current?.transport as? SessionHealthSource ?: return
    val local = current.players.getOrNull(current.localPlayerIndex)?.playerId
    val end by transport.sessionEnd.collectAsState()
    val reconnecting by transport.isReconnecting.collectAsState()
    val away by transport.awayPlayers.collectAsState()
    val notice = sessionNoticeFor(end, reconnecting, away, transport.sessionHostId, local)

    when (notice) {
        SessionNotice.NONE -> Unit
        SessionNotice.HOST_LEFT, SessionNotice.CONNECTION_LOST -> {
            val hostLeft = notice == SessionNotice.HOST_LEFT
            AlertDialog(
                onDismissRequest = onEnded,
                title = { Text(stringResource(if (hostLeft) R.string.sh_host_left_title else R.string.sh_connection_lost_title)) },
                text = { Text(stringResource(if (hostLeft) R.string.sh_host_left_body else R.string.sh_connection_lost_body)) },
                confirmButton = { TextButton(onClick = onEnded) { Text(stringResource(R.string.sh_ok)) } }
            )
        }
        else -> {
            val text = stringResource(
                when (notice) {
                    SessionNotice.RECONNECTING -> R.string.sh_reconnecting
                    SessionNotice.HOST_AWAY -> R.string.sh_host_away
                    else -> R.string.sh_player_away
                }
            )
            Box(Modifier.align(Alignment.TopCenter).padding(start = GameChromeEndInset, end = GameChromeEndInset, top = 8.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}
