package com.gamesuite.ui.party

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import kotlinx.coroutines.delay

private const val PARTY_CLOCK_TICK_MS = 100L

/**
 * Multi-player chess clock for [roster] (an empty roster means "no names set": Player 1..n with a
 * stepper). Every player has a time bank, optionally with an increment per pass and a per-turn
 * limit. TAP THE CLOCK PANEL ANYWHERE to start and to pass the turn; Pause, Skip and Reset are
 * separate buttons. A player who runs out is flagged (an "OUT OF TIME" label and a struck-through
 * name, never colour alone, plus a failure haptic and buzz) and is skipped from then on; hitting the
 * turn limit flags the turn and waits for the tap that passes it on.
 *
 * State lives in [PartySession] (wall-clock based via SystemClock.elapsedRealtime), so it survives
 * tab switches and rotation; while this tab is away the clock keeps counting and catches up on
 * return. The screen is kept awake only while the clock runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyGameClockTool(roster: List<String>) {
    val palette = partyPalette()
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val view = LocalView.current
    var confirmReset by remember { mutableStateOf(false) }

    PartyPanel(palette) {
        val names = partyRosterNames(roster, palette, haptics)
        val n = names.size
        val stored = PartySession.clock
        val state = if (stored != null && stored.banksMs.size == n) stored else PartyClock.create(n)
        val running = state.running
        val fresh = PartyClock.isFresh(state)
        val finished = PartyClock.isFinished(state)
        val waiting = PartyClock.isWaiting(state)

        // Wall-clock ticker: every pass re-derives the charge from the monotonic clock.
        LaunchedEffect(running) {
            while (true) {
                val current = PartySession.clock ?: break
                if (!current.running) break
                delay(PARTY_CLOCK_TICK_MS)
                val latest = PartySession.clock ?: break
                PartySession.clock = PartyClock.tick(latest, SystemClock.elapsedRealtime())
            }
        }

        // Flag cue. The baseline stops a flag that already happened from re-firing on re-entry.
        val baselineFlagSeq = remember { state.flagSeq }
        LaunchedEffect(state.flagSeq) {
            if (state.flagSeq != baselineFlagSeq) {
                haptics(HapticSignal.FAILURE)
                playSfx(SfxKind.INVALID_BUZZ)
            }
        }

        // Same mechanism as the hourglass: View.keepScreenOn while the clock runs.
        DisposableEffect(running, view) {
            if (running) view.keepScreenOn = true
            onDispose { if (running) view.keepScreenOn = false }
        }

        val set: (PartyClockState) -> Unit = { PartySession.clock = it }
        val activeName = names.getOrElse(state.active) { "?" }

        PartyHeading(stringResource(R.string.party2_clock_title), palette)
        Spacer(Modifier.height(12.dp))

        if (fresh) {
            ClockSetup(state.config, palette, haptics) { set(PartyClock.create(n, it)) }
            Spacer(Modifier.height(12.dp))
        }

        // The tap area: everything inside is one big "pass the turn" button.
        val summary = when {
            finished -> clockResultText(names, state)
            fresh || waiting ->stringResource(R.string.party2_clock_summary_idle, activeName)
            else -> stringResource(
                R.string.party2_clock_summary,
                activeName,
                PartyClock.format(state.banksMs.getOrElse(state.active) { 0L })
            )
        }
        val passLabel = stringResource(R.string.party2_clock_tap_pass)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.background)
                .border(BorderStroke(2.dp, palette.accentText), RoundedCornerShape(16.dp))
                .semantics { contentDescription = summary }
                .clickable(enabled = !finished, onClickLabel = passLabel, role = Role.Button) {
                    set(PartyClock.pass(state, SystemClock.elapsedRealtime()))
                    haptics(HapticSignal.LIGHT_TICK)
                    playSfx(SfxKind.LIGHT_TICK)
                }
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp)
                .padding(8.dp)
                .clearAndSetSemantics {},
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            names.forEachIndexed { i, name ->
                val isActive = i == state.active && !finished
                val isOut = i in state.outPlayers
                val bank = state.banksMs.getOrElse(i) { 0L }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (isActive) 72.dp else PARTY_MIN_TOUCH_DP.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isActive) palette.accent else palette.chipBackground)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val onColor = if (isActive) palette.textOnAccent else palette.textPrimary
                    Text(
                        text = when {
                            isOut -> "✕"
                            isActive -> "▶"
                            else -> "·"
                        },
                        color = onColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            name,
                            color = onColor,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            textDecoration = if (isOut) TextDecoration.LineThrough else TextDecoration.None,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isOut) {
                            Text(
                                stringResource(R.string.party2_clock_out),
                                color = if (isActive) palette.textOnAccent else palette.danger,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        } else if (isActive && state.turnTimedOut) {
                            Text(
                                stringResource(R.string.party2_clock_turn_over_label),
                                color = palette.textOnAccent,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        } else if (isActive && state.config.turnLimitMs > 0L) {
                            Text(
                                stringResource(
                                    R.string.party2_clock_turn_left,
                                    PartyClock.format(state.config.turnLimitMs - state.turnElapsedMs)
                                ),
                                color = palette.textOnAccent,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    Text(
                        PartyClock.format(bank),
                        color = onColor,
                        fontWeight = FontWeight.Bold,
                        style = if (isActive) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
        }

        Spacer(Modifier.height(8.dp))
        // Status line: announced politely when a flag or the end of the game appears.
        val status = when {
            finished -> clockResultText(names, state)
            state.turnTimedOut -> stringResource(R.string.party2_clock_turn_over, activeName)
            state.active in state.outPlayers -> stringResource(R.string.party2_clock_out_status, activeName)
            fresh -> stringResource(R.string.party2_clock_tap_start, activeName)
            !running -> stringResource(R.string.party2_clock_paused)
            else -> ""
        }
        Text(
            status,
            color = palette.accentText,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )

        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PartyButton(
                palette = palette,
                enabled = !finished && !state.turnTimedOut && state.active !in state.outPlayers,
                onClick = {
                    val now = SystemClock.elapsedRealtime()
                    set(if (running) PartyClock.pause(state, now) else PartyClock.resume(state, now))
                    haptics(HapticSignal.LIGHT_TICK)
                }
            ) {
                Text(
                    stringResource(
                        when {
                            running -> R.string.party2_clock_pause
                            fresh -> R.string.party2_clock_start
                            else -> R.string.party2_clock_resume
                        }
                    )
                )
            }
            PartyOutlinedButton(
                palette = palette,
                enabled = !finished,
                onClick = {
                    set(PartyClock.skip(state, SystemClock.elapsedRealtime()))
                    haptics(HapticSignal.LIGHT_TICK)
                }
            ) { Text(stringResource(R.string.party2_clock_skip)) }
            PartyOutlinedButton(
                palette = palette,
                enabled = !fresh,
                onClick = { confirmReset = true }
            ) { Text(stringResource(R.string.party2_clock_reset)) }
        }

        if (confirmReset) {
            PartyConfirmDialog(
                title = stringResource(R.string.party2_clock_reset_title),
                body = stringResource(R.string.party2_clock_reset_body),
                confirmLabel = stringResource(R.string.party2_clock_reset),
                onConfirm = {
                    set(PartyClock.create(n, state.config))
                    confirmReset = false
                    haptics(HapticSignal.NORMAL_ACTION)
                },
                onDismiss = { confirmReset = false }
            )
        }
    }
}

@Composable
private fun clockResultText(names: List<String>, state: PartyClockState): String {
    val alive = PartyClock.alivePlayers(state)
    return if (alive.isEmpty()) {
        stringResource(R.string.party2_clock_all_out)
    } else {
        stringResource(R.string.party2_clock_winner, names.getOrElse(alive.first()) { "?" })
    }
}

/** The three settings, shown only while nothing has been played. */
@Composable
private fun ClockSetup(
    config: PartyClockConfig,
    palette: PartyPalette,
    haptics: (HapticSignal) -> Unit,
    onChange: (PartyClockConfig) -> Unit
) {
    val off = stringResource(R.string.party2_off)
    val minutesLabel = stringResource(R.string.party2_clock_minutes)
    val incrementLabel = stringResource(R.string.party2_clock_increment)
    val limitLabel = stringResource(R.string.party2_clock_turn_limit)
    val minFmt = stringResource(R.string.party2_clock_min_fmt)
    val secFmt = stringResource(R.string.party2_clock_sec_fmt)
    val minutes = (config.bankMs / 60_000L).toInt()
    val increment = (config.incrementMs / 1000L).toInt()
    val limit = (config.turnLimitMs / 1000L).toInt()

    SetupLine(minutesLabel, palette) {
        PartyStepper(
            value = minutes, range = 1..180, label = minutesLabel, palette = palette, haptics = haptics,
            valueText = { String.format(minFmt, it) },
            onChange = { onChange(config.copy(bankMs = it * 60_000L)) }
        )
    }
    SetupLine(incrementLabel, palette) {
        PartyStepper(
            value = increment, range = 0..60, step = 5, label = incrementLabel, palette = palette, haptics = haptics,
            valueText = { if (it == 0) off else String.format(secFmt, it) },
            onChange = { onChange(config.copy(incrementMs = it * 1000L)) }
        )
    }
    SetupLine(limitLabel, palette) {
        PartyStepper(
            value = limit, range = 0..300, step = 10, label = limitLabel, palette = palette, haptics = haptics,
            valueText = { if (it == 0) off else String.format(secFmt, it) },
            onChange = { onChange(config.copy(turnLimitMs = it * 1000L)) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupLine(label: String, palette: PartyPalette, control: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(modifier = Modifier.heightIn(min = PARTY_MIN_TOUCH_DP.dp), contentAlignment = Alignment.CenterStart) {
            Text(label, color = palette.textPrimary, fontWeight = FontWeight.Bold)
        }
        control()
    }
}
