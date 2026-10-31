package com.gamesuite.ui.party

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.PARTY_ROSTER_MIN
import com.gamesuite.core.PartyRosterLogic
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalReducedMotion
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Who Pays / Picker: chooses one name fairly at random with a flash-through suspense animation
 * that slows down and lands on the winner. Under reduced motion there is no flashing: the result
 * appears at once. The last picks are listed newest first. [roster] is the shared roster (empty or
 * 2..12 unique names); empty falls back to "Player 1..n". [random] is injectable.
 */
@Composable
fun PartyPickerTool(roster: List<String>, random: Random = Random.Default) {
    val palette = partyUiPalette()
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val reducedMotion = LocalReducedMotion.current

    val rosterActive = roster.size >= PARTY_ROSTER_MIN
    var fallbackCount by rememberSaveable { mutableStateOf(4) }
    val names = PartyRosterLogic.effective(
        if (rosterActive) roster else emptyList(),
        fallbackCount,
        stringResource(R.string.party1_player_prefix)
    )
    val currentNames by rememberUpdatedState(names)

    var spinSeq by remember { mutableStateOf(0) }
    var spinning by remember { mutableStateOf(false) }
    var flashing by remember { mutableStateOf(-1) }
    var result by rememberSaveable { mutableStateOf("") }
    var historyRaw by rememberSaveable { mutableStateOf("") }
    val history = if (historyRaw.isEmpty()) emptyList() else historyRaw.split('\n')

    LaunchedEffect(spinSeq) {
        if (spinSeq == 0) return@LaunchedEffect
        val list = currentNames
        val winner = PartyPicker.pickIndex(list, random) ?: return@LaunchedEffect
        spinning = true
        if (!reducedMotion) {
            val seq = PartyPicker.flashSequence(list.size, winner, 16 + random.nextInt(6), random)
            for ((i, idx) in seq.withIndex()) {
                flashing = idx
                haptics(HapticSignal.LIGHT_TICK)
                delay(PartyPicker.delayMs(i, seq.size))
            }
        }
        val name = list.getOrElse(winner) { "" }
        flashing = winner
        result = name
        historyRaw = PartyPicker.pushHistory(history, name).joinToString("\n")
        spinning = false
        haptics(HapticSignal.SUCCESS)
        playSfx(SfxKind.SUCCESS_CHIME)
    }

    val highlighted = if (spinning) flashing else names.indexOf(result)
    val shape = RoundedCornerShape(16.dp)

    PartyUiCard(palette) {
        Text(
            stringResource(R.string.party1_picker_title),
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            stringResource(R.string.party1_picker_hint),
            color = palette.textPrimary.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodySmall
        )
        if (!rosterActive) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.party1_fallback_hint),
                color = palette.textPrimary.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall
            )
            PartyFallbackCount(palette, names.size) {
                if (!spinning) {
                    fallbackCount = it
                    result = ""
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // The big display. While flashing it is hidden from screen readers (it changes every
        // fraction of a second); the final result is announced politely.
        val displayText = when {
            spinning -> names.getOrElse(flashing) { "" }
            result.isNotEmpty() -> result
            else -> stringResource(R.string.party1_picker_ready)
        }
        val pickedSpoken = stringResource(R.string.party1_picker_result_cd, result)
        val shownSemantics: Modifier = if (spinning) {
            Modifier.clearAndSetSemantics { contentDescription = "" }
        } else {
            Modifier.semantics(mergeDescendants = true) {
                if (result.isNotEmpty()) contentDescription = pickedSpoken
                liveRegion = LiveRegionMode.Polite
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .border(if (!spinning && result.isNotEmpty()) 4.dp else 1.dp, palette.accentText, shape)
                .padding(16.dp)
                .then(shownSemantics),
            contentAlignment = Alignment.Center
        ) {
            Text(
                displayText,
                color = if (!spinning && result.isNotEmpty()) palette.accentText else palette.textPrimary,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(12.dp))
        PartyUiButton(
            palette = palette,
            enabled = !spinning && names.isNotEmpty(),
            onClick = {
                result = ""
                spinSeq++
            }
        ) {
            Text(
                stringResource(
                    if (spinning) R.string.party1_picker_picking
                    else if (result.isEmpty()) R.string.party1_picker_pick
                    else R.string.party1_picker_again
                )
            )
        }

        Spacer(Modifier.height(12.dp))
        Column(modifier = Modifier.clearAndSetSemantics {}) {
            for ((i, n) in names.withIndex()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (i == highlighted) "▶" else "  ",
                        color = palette.accentText,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        n,
                        color = palette.textPrimary,
                        fontWeight = if (i == highlighted) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.party1_picker_history),
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(history.joinToString(", "), color = palette.textPrimary)
        }
    }
}
