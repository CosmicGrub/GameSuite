package com.gamesuite.ui.profiles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.gamesuite.R
import com.gamesuite.core.Avatar
import com.gamesuite.core.AvatarCharacter
import com.gamesuite.core.AvatarColor
import com.gamesuite.profiles.AddResult
import com.gamesuite.profiles.AgeBand
import com.gamesuite.profiles.DeleteResult
import com.gamesuite.profiles.NameResult
import com.gamesuite.profiles.Profile
import com.gamesuite.profiles.ProfileKind
import com.gamesuite.profiles.ProfilesViewModel
import com.gamesuite.ui.club.AvatarBadge

@Composable
fun nameErrorText(r: NameResult): String? = when (r) {
    is NameResult.Ok -> null
    NameResult.Empty -> stringResource(R.string.prof_err_empty)
    NameResult.TooLong -> stringResource(R.string.prof_err_long)
    NameResult.InvalidChars -> stringResource(R.string.prof_err_chars)
    NameResult.LooksLikeLink -> stringResource(R.string.prof_err_link)
    NameResult.Duplicate -> stringResource(R.string.prof_err_dup)
}

/** "Who's playing?": big avatar tiles, plus Add profile and a Manage mode (rename, delete with confirmation). */
@Composable
fun ProfilePickerScreen(
    viewModel: ProfilesViewModel,
    onPicked: (Profile) -> Unit,
    onAddProfile: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.profiles.collectAsStateWithLifecycle()
    val st = state ?: return
    val context = LocalContext.current
    var managing by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    val msgDefault = stringResource(R.string.prof_err_delete_default)
    val msgActive = stringResource(R.string.prof_err_delete_active)
    val msgLastAdult = stringResource(R.string.prof_err_delete_last_adult)

    MidnightClubTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp)) {
                Text(
                    stringResource(R.string.prof_whos_playing),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(st.profiles, key = { it.id }) { p ->
                        ProfileTile(
                            profile = p,
                            active = p.id == st.activeId,
                            managing = managing,
                            onClick = { if (!managing) onPicked(p) },
                            onRename = { renaming = p },
                            onDelete = {
                                // Refusals are explained immediately; allowed deletes ask for confirmation.
                                when {
                                    p.id == com.gamesuite.core.ProfileScope.DEFAULT_PROFILE_ID ->
                                        Toast.makeText(context, msgDefault, Toast.LENGTH_SHORT).show()
                                    p.id == st.activeId ->
                                        Toast.makeText(context, msgActive, Toast.LENGTH_SHORT).show()
                                    p.kind == ProfileKind.ADULT && st.profiles.count { it.kind == ProfileKind.ADULT } <= 1 ->
                                        Toast.makeText(context, msgLastAdult, Toast.LENGTH_SHORT).show()
                                    else -> deleting = p
                                }
                            }
                        )
                    }
                    item(key = "add") {
                        Surface(
                            onClick = onAddProfile,
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 168.dp)
                        ) {
                            Column(
                                Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text("+", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.prof_add_profile), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { managing = !managing }, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(stringResource(if (managing) R.string.prof_done else R.string.prof_manage))
                    }
                    if (onBack != null) {
                        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.prof_back))
                        }
                    }
                }
            }
        }
    }

    renaming?.let { p ->
        var draft by remember(p.id) { mutableStateOf(p.name) }
        var error by remember(p.id) { mutableStateOf<NameResult?>(null) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.prof_rename)) },
            text = {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it; error = null }, singleLine = true,
                    isError = error != null,
                    supportingText = {
                        val msg = error?.let { nameErrorText(it) }
                        if (msg != null) Text(msg)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(p.id, draft) { r -> if (r is NameResult.Ok) renaming = null else error = r }
                }) { Text(stringResource(R.string.prof_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.prof_cancel)) } }
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.prof_delete_title, p.name)) },
            text = { Text(stringResource(R.string.prof_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(p.id) { r ->
                        deleting = null
                        val msg = when (r) {
                            DeleteResult.IsDefault -> msgDefault
                            DeleteResult.IsActive -> msgActive
                            DeleteResult.LastAdult -> msgLastAdult
                            else -> null
                        }
                        if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.prof_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.prof_cancel)) } }
        )
    }
}

@Composable
private fun ProfileTile(
    profile: Profile,
    active: Boolean,
    managing: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val kindLabel = stringResource(if (profile.isKid) R.string.prof_kind_kid else R.string.prof_kind_adult)
    val description = stringResource(R.string.prof_cd_profile, profile.name, kindLabel)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 168.dp).semantics { contentDescription = description }
    ) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AvatarBadge(Avatar.decode(profile.avatar), size = 88.dp, animated = false, contentDescription = "")
            Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                kindLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (profile.isKid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            if (managing) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRename, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.prof_rename)) }
                    TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.prof_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Add profile: name (validated), type, age band for kids, character and colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddProfileScreen(
    viewModel: ProfilesViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val state by viewModel.profiles.collectAsStateWithLifecycle()
    val existing = state?.profiles?.map { it.name }.orEmpty()
    var name by rememberSaveable { mutableStateOf("") }
    var kidSelected by rememberSaveable { mutableStateOf(false) }
    var band by rememberSaveable { mutableStateOf(AgeBand.AGE_7_9.id) }
    var characterId by rememberSaveable { mutableStateOf(AvatarCharacter.PIP.id) }
    var colorId by rememberSaveable { mutableStateOf(AvatarColor.SKY.id) }
    var error by remember { mutableStateOf<NameResult?>(null) }
    var tooMany by remember { mutableStateOf(false) }
    val avatar = Avatar(
        AvatarCharacter.fromId(characterId) ?: AvatarCharacter.PIP,
        AvatarColor.fromId(colorId) ?: AvatarColor.SKY
    )

    MidnightClubTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.prof_add_profile), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AvatarBadge(avatar, size = 96.dp, contentDescription = "")
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null; tooMany = false },
                    label = { Text(stringResource(R.string.prof_name_label)) },
                    placeholder = { Text(stringResource(R.string.prof_name_hint)) },
                    singleLine = true,
                    isError = error != null || tooMany,
                    supportingText = {
                        val msg = if (tooMany) stringResource(R.string.prof_err_too_many) else error?.let { nameErrorText(it) }
                        Text(msg ?: stringResource(R.string.prof_name_hint))
                    },
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.prof_kind_label), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !kidSelected, onClick = { kidSelected = false },
                        label = { Text(stringResource(R.string.prof_kind_adult)) }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(selected = kidSelected, onClick = { kidSelected = true },
                        label = { Text(stringResource(R.string.prof_kind_kid)) }, modifier = Modifier.heightIn(min = 48.dp))
                }
                if (kidSelected) {
                    Text(stringResource(R.string.prof_age_label), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgeBand.values().forEach { b ->
                            FilterChip(
                                selected = band == b.id, onClick = { band = b.id },
                                label = {
                                    Text(stringResource(when (b) {
                                        AgeBand.AGE_4_6 -> R.string.prof_age_4_6
                                        AgeBand.AGE_7_9 -> R.string.prof_age_7_9
                                        AgeBand.AGE_10_12 -> R.string.prof_age_10_12
                                    }))
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                }
                Text(stringResource(R.string.prof_character_label), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AvatarCharacter.values().forEach { c ->
                        val sel = c.id == characterId
                        Box(
                            Modifier.size(56.dp).clip(CircleShape)
                                .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent)
                                .clickable { characterId = c.id }
                                .semantics { selected = sel; contentDescription = c.id },
                            contentAlignment = Alignment.Center
                        ) { AvatarBadge(Avatar(c, avatar.color), size = 44.dp, animated = false, contentDescription = "") }
                    }
                }
                Text(stringResource(R.string.prof_color_label), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AvatarColor.values().forEach { c ->
                        val sel = c.id == colorId
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Color(c.argb))
                                .then(if (sel) Modifier.background(Color.Transparent) else Modifier)
                                .clickable { colorId = c.id }
                                .semantics { selected = sel; contentDescription = c.id },
                            contentAlignment = Alignment.Center
                        ) { if (sel) Text("✓", color = Color(com.gamesuite.core.AvatarPalette.inkFor(c.argb))) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val local = com.gamesuite.profiles.validateProfileName(name, existing)
                            if (local !is NameResult.Ok) { error = local; return@Button }
                            viewModel.addProfile(
                                name, if (kidSelected) ProfileKind.KID else ProfileKind.ADULT,
                                if (kidSelected) AgeBand.fromId(band) else null, avatar
                            ) { r ->
                                when (r) {
                                    is AddResult.Added -> onDone()
                                    is AddResult.BadName -> error = r.reason
                                    AddResult.TooMany -> tooMany = true
                                }
                            }
                        },
                        modifier = Modifier.heightIn(min = 56.dp)
                    ) { Text(stringResource(R.string.prof_save)) }
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.prof_cancel))
                    }
                }
            }
        }
    }
}
