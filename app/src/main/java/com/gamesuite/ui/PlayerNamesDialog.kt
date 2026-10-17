package com.gamesuite.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.gamesuite.core.MAX_PLAYER_NAME_LENGTH
import com.gamesuite.core.cleanPlayerName

/**
 * The shared "Your name" field for the Nearby/Online entry screens. Keeps the raw text locally
 * (so spaces can be typed) and saves the cleaned value on every change; blank clears it, which
 * makes the lobbies fall back to their default ("Host" / "Guest (model)").
 */
@Composable
fun LobbyNameField(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { com.gamesuite.core.PlayerNameStore(context) }
    var text by remember(store) { mutableStateOf(store.hostName) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.take(MAX_PLAYER_NAME_LENGTH)
            store.hostName = text
        },
        label = { Text("Your name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
    )
}

/**
 * Asks for the display names of [count] human players before a pass-and-play launch.
 * Blank fields fall back to "Player N". Display strings only.
 */
@Composable
fun PlayerNamesDialog(
    title: String,
    count: Int,
    initial: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    showTeamHint: Boolean = false
) {
    val names = remember { mutableStateListOf<String>().also { l ->
        repeat(count) { i -> l.add(initial.getOrElse(i) { "" }) }
    } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (i in 0 until count) {
                    OutlinedTextField(
                        value = names[i],
                        onValueChange = { v -> names[i] = v.take(MAX_PLAYER_NAME_LENGTH) },
                        label = { Text("Player ${i + 1} name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (i == count - 1) ImeAction.Done else ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (showTeamHint) {
                    Text(
                        "Players 1 and 3 are a team; players 2 and 4 are a team.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(List(count) { i -> cleanPlayerName(names[i], "Player ${i + 1}") })
            }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
