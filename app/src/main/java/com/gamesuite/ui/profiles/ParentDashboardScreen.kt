package com.gamesuite.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.gameGlyph
import com.gamesuite.core.kidMaySee
import com.gamesuite.profiles.AgeBand
import com.gamesuite.profiles.ControlsState
import com.gamesuite.profiles.GateMethod
import com.gamesuite.profiles.GateMethods
import com.gamesuite.profiles.PinHasher
import com.gamesuite.profiles.KidControls
import com.gamesuite.profiles.ProfilesViewModel
import com.gamesuite.ui.hubText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private fun formatMinuteOfDay(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

/**
 * Parent dashboard (reached only through the gate): per-kid daily limit, today's play time and weekly bars,
 * quiet hours, the locked-off online row, the per-game "shown" toggles, and a way to switch profile.
 * [allSpecs] is the full catalogue (GameHubSpecs.all); the list offered is what the kid's age band could see.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParentDashboardScreen(
    viewModel: ProfilesViewModel,
    allSpecs: List<GameHubSpec>,
    onSwitchProfile: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.profiles.collectAsStateWithLifecycle()
    val controls by viewModel.controls.collectAsStateWithLifecycle()
    val kids = state?.profiles?.filter { it.isKid }.orEmpty()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val kid = kids.firstOrNull { it.id == selectedId } ?: kids.firstOrNull()

    MidnightClubTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.prof_dash_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

                if (kid == null) {
                    Text(stringResource(R.string.prof_dash_no_kids))
                } else {
                    if (kids.size > 1) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            kids.forEach { k ->
                                FilterChip(selected = k.id == kid.id, onClick = { selectedId = k.id },
                                    label = { Text(k.name) }, modifier = Modifier.heightIn(min = 48.dp))
                            }
                        }
                    }
                    Text(stringResource(R.string.prof_dash_for, kid.name), style = MaterialTheme.typography.titleMedium)
                    val kc = controls.forKid(kid.id)
                    PlayTimeCard(viewModel, kid.id, kc)
                    WeeklyReportCard(viewModel.limiter, kid.id, kc)
                    QuietHoursCard(viewModel, kid.id, kc)
                }

                // Online is always locked off for kids; shown so a parent can see it is not a setting.
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.prof_dash_online), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.prof_dash_online_locked), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = false, onCheckedChange = null, enabled = false)
                    }
                }

                UnlockStyleCard(viewModel, controls)

                if (kid != null) {
                    val kc = controls.forKid(kid.id)
                    val maxAge = (kid.ageBand ?: AgeBand.AGE_4_6).maxAge
                    val eligible = allSpecs.filter { kidMaySee(it, maxAge) }
                    SectionCard {
                        Text(stringResource(R.string.prof_dash_games), style = MaterialTheme.typography.titleMedium)
                        eligible.forEach { spec ->
                            val shown = spec.gameId !in kc.hiddenGameIds
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(gameGlyph(spec.gameId), modifier = Modifier.width(36.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(hubText(spec.title))
                                    teachesLine(spec.gameId)?.let { line ->
                                        Text(line, style = MaterialTheme.typography.labelMedium)
                                        Text(teachesSentences(spec.gameId), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Switch(
                                    checked = shown,
                                    onCheckedChange = { on ->
                                        viewModel.updateKid(kid.id) {
                                            it.copy(hiddenGameIds = if (on) it.hiddenGameIds - spec.gameId else it.hiddenGameIds + spec.gameId)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onSwitchProfile, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.prof_dash_switch))
                    }
                    Button(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.prof_done)) }
                }
            }
        }
    }
}

/**
 * "Unlock style": which way the parent gate opens first. Reached only through the gate (the dashboard route is
 * guarded), and the ViewModel setters refuse a kid profile that has not passed it.
 */
@Composable
private fun UnlockStyleCard(vm: ProfilesViewModel, controls: ControlsState) {
    val context = LocalContext.current
    val deviceSecure = remember { AndroidDeviceCredential(context).isAvailable() }
    var pinDialogSelects by remember { mutableStateOf<Boolean?>(null) } // null = closed
    val chosen = controls.gateMethod
    val auto = GateMethods.defaultFor(null, deviceSecure, controls.hasPin)

    SectionCard {
        Text(stringResource(R.string.prof_gate_style_title), style = MaterialTheme.typography.titleMedium)
        GateStyleRow(
            selected = chosen == null,
            enabled = true,
            name = stringResource(R.string.prof_gate_method_auto),
            desc = stringResource(R.string.prof_gate_method_auto_desc, gateMethodName(auto))
        ) { vm.setGateMethod(null) }
        GateMethod.values().forEach { m ->
            val usable = when (m) {
                GateMethod.PHONE_LOCK -> deviceSecure
                else -> true
            }
            GateStyleRow(
                selected = chosen == m,
                enabled = usable,
                name = gateMethodName(m),
                desc = if (usable) gateMethodDescription(m) else stringResource(R.string.prof_gate_phone_unavailable)
            ) {
                if (m == GateMethod.PIN && !controls.hasPin) pinDialogSelects = true else vm.setGateMethod(m)
            }
        }
        Text(stringResource(R.string.prof_gate_phone_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pinDialogSelects = false }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.prof_gate_pin_set))
            }
            if (controls.hasPin) {
                TextButton(onClick = { vm.clearPin() }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.prof_gate_pin_remove))
                }
            }
        }
    }

    pinDialogSelects?.let { selectAfter ->
        PinSetupDialog(
            onDismiss = { pinDialogSelects = null },
            onSave = { pin ->
                vm.setPin(pin, selectAsDefault = selectAfter)
                pinDialogSelects = null
            }
        )
    }
}

@Composable
private fun GateStyleRow(selected: Boolean, enabled: Boolean, name: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Two fields (PIN, again); Save needs exactly 4 digits that match. Nothing is kept in saved state. */
@Composable
private fun PinSetupDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val valid = PinHasher.isValidPin(pin) && pin == again
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prof_gate_pin_set)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.prof_gate_pin_setup_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c in '0'..'9' }.take(PinHasher.PIN_LENGTH) },
                    label = { Text(stringResource(R.string.prof_gate_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                OutlinedTextField(
                    value = again,
                    onValueChange = { again = it.filter { c -> c in '0'..'9' }.take(PinHasher.PIN_LENGTH) },
                    label = { Text(stringResource(R.string.prof_gate_pin_again)) },
                    singleLine = true,
                    isError = again.length == PinHasher.PIN_LENGTH && again != pin,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (again.length == PinHasher.PIN_LENGTH && again != pin) {
                    Text(stringResource(R.string.prof_gate_pin_mismatch), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(pin) }, enabled = valid, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.prof_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.prof_cancel)) }
        }
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayTimeCard(vm: ProfilesViewModel, kidId: String, kc: KidControls) {
    val limiter = vm.limiter
    val played = (limiter.playedToday(kc) / 60_000L).toInt()
    val week = limiter.weekMillis(kc)
    val today = LocalDate.now()
    SectionCard {
        Text(stringResource(R.string.prof_dash_play_time), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.prof_dash_today, played))
        Text(stringResource(R.string.prof_dash_daily), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KidControls.DAILY_CHOICES.forEach { m ->
                FilterChip(
                    selected = kc.dailyMinutes == m,
                    onClick = { vm.updateKid(kidId) { it.copy(dailyMinutes = m) } },
                    label = { Text(if (m == 0) stringResource(R.string.prof_dash_no_limit) else stringResource(R.string.prof_dash_minutes, m)) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
        Text(stringResource(R.string.prof_dash_week), style = MaterialTheme.typography.labelLarge)
        val maxMs = (week.maxOrNull() ?: 0L).coerceAtLeast(15 * 60_000L)
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            week.forEachIndexed { i, ms ->
                val day: DayOfWeek = today.minusDays((6 - i).toLong()).dayOfWeek
                val minutes = (ms / 60_000L).toInt()
                Column(
                    Modifier.weight(1f).semantics { contentDescription = "${day.getDisplayName(TextStyle.FULL, Locale.getDefault())}: $minutes" },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.weight(1f))
                    Box(
                        Modifier.fillMaxWidth().height((64f * ms / maxMs).coerceAtLeast(2f).dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (i == 6) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                    )
                    Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun QuietHoursCard(vm: ProfilesViewModel, kidId: String, kc: KidControls) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.prof_dash_quiet), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.prof_dash_quiet_desc), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = kc.quietEnabled, onCheckedChange = { on -> vm.updateKid(kidId) { it.copy(quietEnabled = on) } })
        }
        if (kc.quietEnabled) {
            TimeStepper(stringResource(R.string.prof_dash_quiet_from), kc.quietStartMinute) { m -> vm.updateKid(kidId) { it.copy(quietStartMinute = m) } }
            TimeStepper(stringResource(R.string.prof_dash_quiet_to), kc.quietEndMinute) { m -> vm.updateKid(kidId) { it.copy(quietEndMinute = m) } }
        }
    }
}

/** A 30-minute-step time picker (no dialog needed, 48dp targets). */
@Composable
private fun TimeStepper(label: String, minute: Int, onChange: (Int) -> Unit) {
    val day = 24 * 60
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { onChange((minute - 30 + day) % day) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text(formatMinuteOfDay(minute), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(64.dp))
        TextButton(onClick = { onChange((minute + 30) % day) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}
