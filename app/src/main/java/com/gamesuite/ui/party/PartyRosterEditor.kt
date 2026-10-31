package com.gamesuite.ui.party

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.core.MAX_PLAYER_NAME_LENGTH
import com.gamesuite.core.PARTY_ROSTER_MAX
import com.gamesuite.core.PartyRosterLogic
import com.gamesuite.core.cleanPlayerName

/**
 * The shared player roster shown at the top of the Party Toolkit. Collapsed it is one 48dp row with
 * a summary such as "Ann, Bo, Cy +2"; expanded it lets the user add, rename, reorder and remove
 * players (2 to 12 are used by the tools). [roster] is the raw stored list and [onRosterChange]
 * receives the next one (the caller persists it).
 */
@Composable
fun PartyRosterEditor(roster: List<String>, onRosterChange: (List<String>) -> Unit) {
    val palette = partyUiPalette()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }

    PartyUiCard(palette) {
        val summary = if (roster.isEmpty()) {
            stringResource(R.string.party1_roster_summary_empty)
        } else {
            PartyRosterLogic.summary(roster)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(
                        if (expanded) R.string.party1_roster_done else R.string.party1_roster_edit
                    ),
                    role = Role.Button,
                    onClick = { expanded = !expanded }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.party1_roster_title),
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                summary,
                color = palette.textPrimary.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                stringResource(if (expanded) R.string.party1_roster_done else R.string.party1_roster_edit),
                color = palette.accentText,
                fontWeight = FontWeight.Bold
            )
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.party1_roster_hint),
                color = palette.textPrimary.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                stringResource(R.string.party1_roster_count, roster.size, PARTY_ROSTER_MAX),
                color = palette.textPrimary.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            for ((index, name) in roster.withIndex()) {
                PartyRosterRow(
                    palette = palette,
                    index = index,
                    name = name,
                    canMoveUp = index > 0,
                    canMoveDown = index < roster.lastIndex,
                    onRename = { onRosterChange(PartyRosterLogic.rename(roster, index, it)) },
                    onMove = { delta -> onRosterChange(PartyRosterLogic.move(roster, index, delta)) },
                    onRemove = { onRosterChange(PartyRosterLogic.remove(roster, index)) }
                )
            }
            Spacer(Modifier.height(8.dp))
            val full = roster.size >= PARTY_ROSTER_MAX
            val canAdd = !full && cleanPlayerName(newName, "").isNotEmpty()
            val addNow: () -> Unit = {
                if (canAdd) {
                    onRosterChange(PartyRosterLogic.add(roster, newName))
                    newName = ""
                }
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(MAX_PLAYER_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.party1_roster_add_label)) },
                    singleLine = true,
                    enabled = !full,
                    supportingText = { if (full) Text(stringResource(R.string.party1_roster_full)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addNow() }),
                    modifier = Modifier.weight(1f)
                )
                PartyUiButton(
                    palette = palette,
                    onClick = { addNow() },
                    enabled = canAdd,
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text(stringResource(R.string.party1_roster_add)) }
            }
        }
    }
}

@Composable
private fun PartyRosterRow(
    palette: PartyUiPalette,
    index: Int,
    name: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRename: (String) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit
) {
    // Edited locally and committed on Done / focus loss, because the stored name is trimmed and
    // collapsed, which would eat a space typed between two words mid-edit.
    var text by remember(name) { mutableStateOf(name) }
    val commit: () -> Unit = {
        if (cleanPlayerName(text, "").isEmpty()) {
            text = name
        } else {
            onRename(text)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_PLAYER_NAME_LENGTH) },
            label = { Text(stringResource(R.string.party1_roster_name_label, index + 1)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 2.dp)
                .onFocusChanged { if (!it.isFocused && text != name) commit() }
        )
        PartyUiIconButton(
            palette, "↑", stringResource(R.string.party1_roster_move_up, name),
            onClick = { onMove(-1) }, enabled = canMoveUp
        )
        PartyUiIconButton(
            palette, "↓", stringResource(R.string.party1_roster_move_down, name),
            onClick = { onMove(1) }, enabled = canMoveDown
        )
        PartyUiIconButton(
            palette, "✕", stringResource(R.string.party1_roster_remove, name),
            onClick = onRemove
        )
    }
}
