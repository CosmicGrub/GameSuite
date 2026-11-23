package com.gamesuite.ui.profiles

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.profiles.AgeBand
import com.gamesuite.profiles.ControlsState
import com.gamesuite.profiles.GateMethod
import com.gamesuite.profiles.GateMethods
import com.gamesuite.profiles.GateResult
import com.gamesuite.profiles.ParentGate
import com.gamesuite.profiles.PinHasher
import com.gamesuite.profiles.ProcessGateStateStore
import com.gamesuite.profiles.PromptRateLimiter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun gateMethodName(m: GateMethod): String = stringResource(
    when (m) {
        GateMethod.PHONE_LOCK -> R.string.prof_gate_method_phone
        GateMethod.WORD_CODE -> R.string.prof_gate_method_word
        GateMethod.PIN -> R.string.prof_gate_method_pin
        GateMethod.SUMS -> R.string.prof_gate_method_sums
    }
)

@Composable
internal fun gateMethodDescription(m: GateMethod): String = stringResource(
    when (m) {
        GateMethod.PHONE_LOCK -> R.string.prof_gate_method_phone_desc
        GateMethod.WORD_CODE -> R.string.prof_gate_method_word_desc
        GateMethod.PIN -> R.string.prof_gate_method_pin_desc
        GateMethod.SUMS -> R.string.prof_gate_method_sums_desc
    }
)

/**
 * The parent gate. It opens on the default method ([GateMethods.defaultFor]: the parent's choice, else the phone's
 * screen lock when the device has one, else the word code) and offers the other available methods as chips. Every
 * method shares ONE lockout (the process-wide [ProcessGateStateStore]): 3 wrong answers in any mix of methods lock
 * it, and switching method never resets attempts. [onUnlocked] runs after a correct answer or a confirmed screen lock.
 * [ageBand] (the active profile's) picks the word-code length.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParentGateScreen(
    controls: ControlsState,
    ageBand: AgeBand?,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credential = remember { AndroidDeviceCredential(context) }
    val deviceSecure = remember { credential.isAvailable() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        credential.onResult(r.resultCode == Activity.RESULT_OK)
    }
    credential.launcher = { launcher.launch(it) }
    val rateLimiter = remember { PromptRateLimiter() }

    val digitWords = stringArrayResource(R.array.prof_gate_digit_words).toList()
    val controlsNow = rememberUpdatedState(controls)
    val available = GateMethods.available(deviceSecure, controls.hasPin)
    val startMethod = GateMethods.defaultFor(controls.gateMethod, deviceSecure, controls.hasPin)
    val wordDigits = GateMethods.wordDigits(ageBand)

    // The attempts and the lockout live in the process-wide store (persisted by the profile bootstrap), so leaving
    // this screen and opening it again continues the same lockout instead of handing out fresh tries.
    val gate = remember {
        ParentGate(
            store = ProcessGateStateStore.instance,
            initialMethod = startMethod,
            digitWords = digitWords,
            wordDigits = wordDigits,
            pinCheck = { PinHasher.verify(it, controlsNow.value.pinSalt, controlsNow.value.pinHash) }
        )
    }
    var method by remember { mutableStateOf(gate.method) }
    var question by remember { mutableStateOf(gate.question) }
    var answer by remember { mutableStateOf("") }
    var prompting by remember { mutableStateOf(false) }
    var lockLeft by remember { mutableIntStateOf(gate.lockoutSecondsLeft()) }
    var message by remember { mutableStateOf<GateResult?>(if (lockLeft > 0) GateResult.LockedOut(lockLeft) else null) }

    fun switchTo(m: GateMethod) {
        gate.useMethod(m)
        method = m
        question = gate.question
        answer = ""
        if (message !is GateResult.LockedOut) message = null
    }

    // The PIN was removed while this screen was open: fall back to the default.
    LaunchedEffect(available) { if (method !in available) switchTo(startMethod) }

    // Counts a lockout down once a second so the button re-enables itself.
    LaunchedEffect(lockLeft > 0) {
        while (lockLeft > 0) {
            delay(1000)
            lockLeft = gate.lockoutSecondsLeft()
        }
    }

    fun handle(r: GateResult) {
        when (r) {
            GateResult.Correct -> onUnlocked()
            is GateResult.LockedOut -> { lockLeft = r.secondsLeft; message = r; question = gate.question; answer = "" }
            is GateResult.Wrong -> { message = r; question = gate.question; answer = "" }
            GateResult.NotANumber, GateResult.CredentialNotConfirmed -> message = r
        }
    }

    val submit = { handle(gate.submit(answer)) }

    val startPrompt = {
        // The button is disabled while locked; credentialSucceeded() re-checks the lockout after the prompt anyway.
        if (lockLeft == 0 && !prompting && rateLimiter.tryAcquire()) {
            prompting = true
            scope.launch {
                val ok = credential.authenticate()
                prompting = false
                handle(if (ok) gate.credentialSucceeded() else gate.credentialFailed())
            }
        }
    }

    val typed = method == GateMethod.WORD_CODE || method == GateMethod.PIN || method == GateMethod.SUMS
    val needed = when (method) {
        GateMethod.WORD_CODE -> wordDigits
        GateMethod.PIN -> PinHasher.PIN_LENGTH
        else -> 1
    }

    MidnightClubTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.prof_gate_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(
                        when (method) {
                            GateMethod.PHONE_LOCK -> R.string.prof_gate_prompt_phone
                            GateMethod.WORD_CODE -> R.string.prof_gate_prompt_word
                            GateMethod.PIN -> R.string.prof_gate_prompt_pin
                            GateMethod.SUMS -> R.string.prof_gate_prompt
                        }
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )

                when (method) {
                    GateMethod.SUMS -> Text(
                        question.text + " = ?",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    GateMethod.WORD_CODE -> Text(
                        question.text,
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    GateMethod.PIN, GateMethod.PHONE_LOCK -> Unit
                }

                if (typed) {
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { v ->
                            answer = v.filter { c -> c in '0'..'9' }.take(if (method == GateMethod.SUMS) 5 else needed)
                        },
                        label = {
                            Text(stringResource(if (method == GateMethod.PIN) R.string.prof_gate_pin_label else R.string.prof_gate_answer))
                        },
                        singleLine = true,
                        enabled = lockLeft == 0,
                        visualTransformation = if (method == GateMethod.PIN) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (method == GateMethod.PIN) KeyboardType.NumberPassword else KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (lockLeft == 0 && answer.length >= needed) submit() }),
                        modifier = Modifier.padding(top = 16.dp).fillMaxWidth(0.6f)
                    )
                }

                val text = when (val m = message) {
                    is GateResult.Wrong -> stringResource(R.string.prof_gate_wrong, m.attemptsLeft)
                    is GateResult.LockedOut -> if (lockLeft > 0) stringResource(R.string.prof_gate_locked, lockLeft) else ""
                    GateResult.NotANumber -> stringResource(
                        if (method == GateMethod.PIN) R.string.prof_gate_pin_incomplete else R.string.prof_gate_not_number
                    )
                    GateResult.CredentialNotConfirmed -> stringResource(R.string.prof_gate_phone_failed)
                    else -> ""
                }
                Text(
                    text,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp).heightIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite }
                )

                if (method == GateMethod.PHONE_LOCK) {
                    Button(
                        onClick = startPrompt,
                        enabled = lockLeft == 0 && !prompting,
                        modifier = Modifier.padding(top = 16.dp).heightIn(min = 56.dp).fillMaxWidth(0.8f)
                    ) { Text(stringResource(R.string.prof_gate_phone_button)) }
                } else {
                    Button(
                        onClick = submit,
                        enabled = lockLeft == 0 && answer.length >= needed,
                        modifier = Modifier.padding(top = 16.dp).heightIn(min = 56.dp)
                    ) { Text(stringResource(R.string.prof_gate_check)) }
                }

                val others = available.filter { it != method }
                if (others.isNotEmpty()) {
                    Text(
                        stringResource(R.string.prof_gate_other_way),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        others.forEach { m ->
                            FilterChip(
                                selected = false,
                                onClick = { switchTo(m) },
                                label = { Text(gateMethodName(m)) },
                                modifier = Modifier.heightIn(min = 56.dp)
                            )
                        }
                    }
                }

                TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp).heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.prof_cancel))
                }
            }
        }
    }
}
