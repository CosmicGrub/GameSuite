package com.gamesuite.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.profiles.ProfilesViewModel

/**
 * The Settings entry for profiles: who is playing, switch/add, parent controls (behind the gate) and, for an
 * ADULT profile, the global "Online play" switch (default on). Passed to SettingsScreen as its `extraContent` slot.
 */
@Composable
fun ProfilesSettingsSection(
    vm: ProfilesViewModel,
    onOpenPicker: () -> Unit,
    onOpenParentGate: () -> Unit
) {
    val state by vm.profiles.collectAsStateWithLifecycle()
    val controls by vm.controls.collectAsStateWithLifecycle()
    val active = state?.active ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.prof_settings_section), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.prof_settings_current, active.name), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenPicker, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.prof_settings_switch))
            }
            OutlinedButton(onClick = onOpenParentGate, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.prof_settings_parent))
            }
        }
        if (!active.isKid) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.prof_settings_online), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.prof_settings_online_desc), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = controls.onlinePlayEnabled, onCheckedChange = vm::setOnlinePlayEnabled)
            }
        }
    }
}
