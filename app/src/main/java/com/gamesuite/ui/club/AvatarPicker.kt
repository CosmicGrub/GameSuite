package com.gamesuite.ui.club

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gamesuite.R
import com.gamesuite.core.Avatar
import com.gamesuite.core.AvatarCharacter
import com.gamesuite.core.AvatarColor
import com.gamesuite.core.AvatarFrame
import com.gamesuite.core.AvatarPalette
import com.gamesuite.core.ClubProfile
import com.gamesuite.core.MAX_PLAYER_NAME_LENGTH

/**
 * The profile editor: display name, 16 characters, 8 colour-blind-safe colours, 4 frames and a favourite
 * game, with a live animated preview. [games] is (gameId, localized title) for the favourite menu.
 * [onSave] receives the edited profile (name cleaned, favourite kept only if it is in [games]); the
 * caller persists it (ClubProfileStore.save) and dismisses.
 *
 * Every choice is a 48dp+ target with a TalkBack name and an explicit "selected" state; selection is shown
 * by an outline and a tick as well as colour. The body scrolls, so it works with large fonts and in landscape.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarPicker(
    initial: ClubProfile,
    games: List<Pair<String, String>>,
    defaultName: String,
    onSave: (ClubProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial.displayName) }
    var avatar by remember { mutableStateOf(initial.avatar) }
    var favorite by remember { mutableStateOf(initial.favoriteGameId) }
    var menuOpen by remember { mutableStateOf(false) }
    val favoriteTitle = games.firstOrNull { it.first == favorite }?.second
        ?: stringResource(R.string.club_picker_none)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(16.dp).fillMaxWidth().widthIn(max = 560.dp).heightIn(max = 640.dp)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.club_picker_title), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AvatarBadge(avatar, size = 96.dp, contentDescription = stringResource(R.string.club_picker_preview_cd))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_PLAYER_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.club_picker_name)) },
                    placeholder = { Text(defaultName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                SectionTitle(R.string.club_picker_character)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AvatarCharacter.values().forEach { ch ->
                        val label = stringResource(characterNameRes(ch))
                        Choice(
                            selected = ch == avatar.character, label = label, size = 60.dp,
                            onClick = { avatar = avatar.copy(character = ch) }
                        ) { AvatarBadge(avatar.copy(character = ch), size = 52.dp, animated = false, contentDescription = "") }
                    }
                }

                SectionTitle(R.string.club_picker_color)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AvatarColor.values().forEach { c ->
                        val label = stringResource(colorNameRes(c))
                        val fill = Color(c.argb.toInt())
                        val tick = Color(AvatarPalette.inkFor(c.argb).toInt())
                        Choice(selected = c == avatar.color, label = label, size = 48.dp, onClick = { avatar = avatar.copy(color = c) }) {
                            Box(Modifier.size(34.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                                if (c == avatar.color) Text("✓", color = tick, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                SectionTitle(R.string.club_picker_frame)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AvatarFrame.values().forEach { f ->
                        val label = stringResource(frameNameRes(f))
                        Choice(selected = f == avatar.frame, label = label, size = 60.dp, onClick = { avatar = avatar.copy(frame = f) }) {
                            AvatarBadge(avatar.copy(frame = f), size = 52.dp, animated = false, contentDescription = "")
                        }
                    }
                }

                SectionTitle(R.string.club_picker_favorite)
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(favoriteTitle)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.club_picker_none)) },
                            onClick = { favorite = null; menuOpen = false },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                        games.forEach { (id, title) ->
                            DropdownMenuItem(
                                text = { Text(title) },
                                onClick = { favorite = id; menuOpen = false },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.club_picker_cancel))
                    }
                    Button(
                        onClick = {
                            onSave(
                                initial.withName(name).withAvatar(avatar)
                                    .withFavorite(favorite, games.map { it.first }.toSet())
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.club_picker_save)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
}

/** One radio-style option: a [size] target with an outline when selected and a spoken "selected" state. */
@Composable
private fun Choice(
    selected: Boolean,
    label: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val spoken = if (selected) stringResource(R.string.club_picker_choice_selected, label) else label
    val border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Box(
        Modifier
            .size(size)
            .border(border, RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center
    ) { Box(Modifier.clearAndSetSemantics { }) { content() } }
}
