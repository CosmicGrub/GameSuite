package com.gamesuite.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
private fun failureText(f: UpdateFailure): String = when (f) {
    UpdateFailure.Offline -> stringResource(R.string.upd_fail_offline)
    is UpdateFailure.RateLimited -> stringResource(R.string.upd_fail_rate_limited)
    is UpdateFailure.HttpError -> stringResource(R.string.upd_fail_http, f.code)
    is UpdateFailure.BlockedUrl -> stringResource(R.string.upd_fail_blocked)
    UpdateFailure.TooManyRedirects -> stringResource(R.string.upd_fail_redirects)
    UpdateFailure.BadResponse -> stringResource(R.string.upd_fail_bad_response)
    UpdateFailure.TooLarge -> stringResource(R.string.upd_fail_too_large)
    UpdateFailure.HashMissing -> stringResource(R.string.upd_fail_hash_missing)
    UpdateFailure.HashMismatch -> stringResource(R.string.upd_fail_hash_mismatch)
    UpdateFailure.NoApkAsset -> stringResource(R.string.upd_fail_no_apk)
    UpdateFailure.NotEnoughSpace -> stringResource(R.string.upd_fail_space)
    UpdateFailure.Cancelled -> stringResource(R.string.upd_fail_cancelled)
    UpdateFailure.ApkUnreadable -> stringResource(R.string.upd_fail_unreadable)
    UpdateFailure.WrongPackage -> stringResource(R.string.upd_fail_wrong_package)
    UpdateFailure.NotNewer -> stringResource(R.string.upd_fail_not_newer)
    UpdateFailure.SignatureMismatch -> stringResource(R.string.upd_fail_signature)
    UpdateFailure.InstallerUnavailable -> stringResource(R.string.upd_fail_installer)
}

/**
 * Settings > App updates. Sideload builds only and adults only: the caller passes [isAdult] (false for kid profiles)
 * and nothing is drawn unless [UpdateFeature.enabled]. One obvious Update button does check, download, verify and
 * opens the system installer.
 */
@Composable
fun UpdateSettingsSection(isAdult: Boolean) {
    if (!UpdateFeature.enabled || !isAdult) return
    val context = LocalContext.current
    val controller = remember { UpdateServices.get(context) }
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { controller.start() }
    // Coming back from the "Install unknown apps" screen: continue by itself when the switch is now on.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (controller.state.value.phase is UpdatePhase.NeedsPermission) controller.continueAfterPermission()
    }

    val phase = state.phase
    val busy = phase is UpdatePhase.Checking || phase is UpdatePhase.Downloading || phase is UpdatePhase.Verifying

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.upd_section_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            stringResource(R.string.upd_installed, state.installed.versionName, state.installed.versionCode),
            style = MaterialTheme.typography.bodyMedium
        )
        val summary = when {
            phase is UpdatePhase.Checking -> stringResource(R.string.upd_checking)
            !state.checkedOnce && state.catalog.entries.isEmpty() -> stringResource(R.string.upd_not_checked)
            state.badgeCount == 0 -> stringResource(R.string.upd_up_to_date)
            state.badgeCount == 1 -> stringResource(R.string.upd_available_one)
            else -> stringResource(R.string.upd_available_many, state.badgeCount)
        }
        Text(summary, style = MaterialTheme.typography.titleSmall)
        if (state.settings.lastCheckedMs > 0) {
            Text(
                stringResource(
                    R.string.upd_last_checked,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.settings.lastCheckedMs))
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
        state.checkFailure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        // The one obvious button.
        val updateDesc = stringResource(R.string.upd_button_update_desc)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { scope.launch { controller.updateNewest() } },
                enabled = !busy && phase !is UpdatePhase.NeedsPermission,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = updateDesc }
            ) { Text(stringResource(R.string.upd_button_update)) }
            OutlinedButton(
                onClick = { scope.launch { controller.check(manual = true) } },
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.upd_button_check)) }
        }

        PhaseBlock(phase, controller, onCancel = controller::cancel)

        // Every release, newest first.
        Text(stringResource(R.string.upd_releases_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        if (state.catalog.entries.isEmpty()) {
            Text(stringResource(R.string.upd_no_releases), style = MaterialTheme.typography.bodySmall)
        }
        state.catalog.entries.forEach { e ->
            ReleaseCard(e, enabled = !busy && phase !is UpdatePhase.NeedsPermission) { scope.launch { controller.install(e.release.tag) } }
        }

        // Settings.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = state.settings.autoCheck, role = Role.Switch,
                onValueChange = { v -> scope.launch { controller.setAutoCheck(v) } }
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.upd_auto_check), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.upd_auto_check_desc), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = state.settings.autoCheck, onCheckedChange = null)
        }
        Text(stringResource(R.string.upd_channel), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.upd_channel_desc), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChannelChoice(stringResource(R.string.upd_channel_stable), state.settings.channel == UpdateChannel.STABLE) {
                scope.launch { controller.setChannel(UpdateChannel.STABLE) }
            }
            ChannelChoice(stringResource(R.string.upd_channel_preview), state.settings.channel == UpdateChannel.PREVIEW) {
                scope.launch { controller.setChannel(UpdateChannel.PREVIEW) }
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = state.settings.allowReinstall, role = Role.Switch,
                onValueChange = { v -> scope.launch { controller.setAllowReinstall(v) } }
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.upd_allow_reinstall), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.upd_allow_reinstall_desc), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = state.settings.allowReinstall, onCheckedChange = null)
        }
        Text(stringResource(R.string.upd_privacy_note), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChannelChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 4.dp, end = 12.dp))
    }
}

@Composable
private fun PhaseBlock(phase: UpdatePhase, controller: UpdateController, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    when (phase) {
        is UpdatePhase.Downloading -> {
            val name = phase.tag
            if (phase.total > 0) {
                val pct = (phase.done * 100 / phase.total).toInt().coerceIn(0, 100)
                val text = stringResource(R.string.upd_downloading, name, pct)
                Text(text, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = text }
                )
            } else {
                Text(stringResource(R.string.upd_downloading_unknown, name), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.upd_cancel)) }
        }
        is UpdatePhase.Verifying -> Text(stringResource(R.string.upd_verifying), style = MaterialTheme.typography.bodyMedium)
        is UpdatePhase.NeedsPermission -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.upd_perm_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.upd_perm_body), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { controller.markPermissionGuideSeen() }; controller.openPermissionSettings() },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.upd_perm_open)) }
                    OutlinedButton(onClick = controller::continueAfterPermission, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.upd_perm_continue))
                    }
                }
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.upd_cancel)) }
            }
        }
        is UpdatePhase.HandedOff -> Text(stringResource(R.string.upd_handed_off), style = MaterialTheme.typography.bodyMedium)
        is UpdatePhase.Failed -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(failureText(phase.failure), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = controller::clearFailure, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.upd_dismiss_failure))
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun ReleaseCard(e: ReleaseEntry, enabled: Boolean, onInstall: () -> Unit) {
    var notes by rememberSaveable(e.release.tag) { mutableStateOf(false) }
    val relationLabel = when (e.relation) {
        Relation.NEWER -> stringResource(R.string.upd_badge_newer)
        Relation.SAME -> stringResource(R.string.upd_badge_installed)
        Relation.OLDER -> stringResource(R.string.upd_badge_older)
        Relation.UNKNOWN -> stringResource(R.string.upd_badge_unknown)
    }
    val channelLabel = stringResource(if (e.release.prerelease) R.string.upd_ch_preview else R.string.upd_ch_stable)
    val size = e.apk?.size?.takeIf { it > 0 }?.let { stringResource(R.string.upd_size_mb, it / 1_048_576.0) }
        ?: stringResource(R.string.upd_size_unknown)
    val date = e.release.publishedAt?.take(10).orEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(e.release.name, style = MaterialTheme.typography.titleSmall)
            Text("$relationLabel | $channelLabel", style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.upd_release_meta, date, size), style = MaterialTheme.typography.bodySmall)
            if (e.release.body.isNotBlank()) {
                TextButton(onClick = { notes = !notes }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (notes) R.string.upd_notes_hide else R.string.upd_notes_show))
                }
                if (notes) Text(e.release.body.take(1500), style = MaterialTheme.typography.bodySmall)
            }
            if (e.installable) {
                OutlinedButton(onClick = onInstall, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.upd_install_this))
                }
            }
        }
    }
}

/**
 * Optional hub banner (adults only, sideload only): shown when the newest release in the channel is newer than the
 * installed build and has not been dismissed. [onOpen] should navigate to Settings.
 */
@Composable
fun UpdateBanner(isAdult: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (!UpdateFeature.enabled || !isAdult) return
    val context = LocalContext.current
    val controller = remember { UpdateServices.get(context) }
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { controller.start(); controller.autoCheckIfDue() }
    val newest = state.catalog.newest
    if (newest == null || !state.showBanner) return
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.upd_banner_text, newest.release.name),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.upd_banner_open)) }
            TextButton(onClick = { scope.launch { controller.dismissBanner() } }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.upd_banner_dismiss))
            }
        }
    }
}
