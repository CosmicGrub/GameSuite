package com.gamesuite.ui.party

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.core.PARTY_ROSTER_MAX
import com.gamesuite.core.PARTY_ROSTER_MIN
import com.gamesuite.core.PartyRosterLogic
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics

/** "+5" or "−3" (a real minus sign), never "+0". */
internal fun partyFormatDelta(delta: Int): String = if (delta >= 0) "+$delta" else "−${-delta}"

/**
 * Score Keeper: a row per player with quick and keypad amounts, a round-by-round history table,
 * running totals, a leader highlight (star plus the word "Leading", not colour alone), an optional
 * target score, undo of the last entry and a confirmed reset.
 *
 * [state] is hoisted by the caller (and persisted by it), so it survives tab switches, rotation
 * and relaunches. [roster] is the shared roster (empty or 2..12 unique names); when it is empty
 * the tool shows "Player 1..n" with its own player-count stepper.
 */
@Composable
fun PartyScoreKeeperTool(
    roster: List<String>,
    state: PartyScoreState,
    onStateChange: (PartyScoreState) -> Unit
) {
    val palette = partyUiPalette()
    val haptics = rememberHaptics()
    val rosterActive = roster.size >= PARTY_ROSTER_MIN
    val count = if (rosterActive) roster.size else state.playerCount.coerceAtLeast(PARTY_ROSTER_MIN)
    val names = PartyRosterLogic.effective(
        if (rosterActive) roster else emptyList(),
        count,
        stringResource(R.string.party1_player_prefix)
    )
    val shown = PartyScoreKeeper.resize(state, count)
    LaunchedEffect(count) {
        if (shown != state) onStateChange(shown)
    }
    val totals = PartyScoreKeeper.totals(shown)
    val leaders = PartyScoreKeeper.leaders(shown)
    val reached = PartyScoreKeeper.targetReached(shown)

    var customFor by rememberSaveable { mutableStateOf(-1) }
    var customText by rememberSaveable { mutableStateOf("") }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    PartyUiCard(palette) {
        Text(
            stringResource(R.string.party1_score_title),
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            stringResource(R.string.party1_score_round, shown.round),
            color = palette.accentText,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        if (!rosterActive) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.party1_fallback_hint),
                color = palette.textPrimary.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall
            )
            PartyFallbackCount(palette, count) { onStateChange(PartyScoreKeeper.resize(shown, it)) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = if (shown.target > 0) shown.target.toString() else "",
            onValueChange = { raw ->
                val digits = raw.filter { it in '0'..'9' }.take(6)
                onStateChange(PartyScoreKeeper.setTarget(shown, digits.toIntOrNull() ?: 0))
            },
            label = { Text(stringResource(R.string.party1_score_target_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        for (p in 0 until shown.playerCount) {
            val name = names.getOrElse(p) { "${stringResource(R.string.party1_player_prefix)} ${p + 1}" }
            val isLeader = p in leaders
            val hitTarget = p in reached
            PartyScorePlayerRow(
                palette = palette,
                name = name,
                total = totals[p],
                isLeader = isLeader,
                hitTarget = hitTarget,
                onAdd = { delta ->
                    haptics(HapticSignal.LIGHT_TICK)
                    onStateChange(PartyScoreKeeper.add(shown, p, delta))
                },
                onCustom = {
                    customText = ""
                    customFor = p
                }
            )
            Spacer(Modifier.height(8.dp))
        }

        // Round, undo, reset.
        val last = PartyScoreKeeper.lastEntry(shown)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PartyUiButton(
                palette = palette,
                enabled = PartyScoreKeeper.canAdvanceRound(shown),
                onClick = {
                    haptics(HapticSignal.NORMAL_ACTION)
                    onStateChange(PartyScoreKeeper.nextRound(shown))
                }
            ) { Text(stringResource(R.string.party1_score_next_round)) }
            PartyUiOutlinedButton(
                palette = palette,
                enabled = last != null,
                onClick = {
                    haptics(HapticSignal.LIGHT_TICK)
                    onStateChange(PartyScoreKeeper.undo(shown))
                }
            ) {
                val lastName = last?.let { names.getOrNull(it.player) }
                Text(
                    if (last != null && lastName != null) {
                        stringResource(R.string.party1_score_undo_last, lastName, partyFormatDelta(last.delta))
                    } else {
                        stringResource(R.string.party1_score_undo)
                    },
                    maxLines = 1
                )
            }
            PartyUiOutlinedButton(
                palette = palette,
                enabled = shown.entries.isNotEmpty(),
                onClick = { confirmReset = true }
            ) { Text(stringResource(R.string.party1_score_reset)) }
        }
    }

    PartyScoreHistory(palette, shown, names, totals, leaders)

    if (customFor in 0 until shown.playerCount) {
        val who = names.getOrElse(customFor) { "" }
        val amount = PartyScoreKeeper.parseAmount(customText)
        AlertDialog(
            onDismissRequest = { customFor = -1 },
            title = { Text(stringResource(R.string.party1_score_custom_title, who)) },
            text = {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.filter { c -> c in '0'..'9' }.take(6) },
                    label = { Text(stringResource(R.string.party1_score_custom_field)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Row {
                    TextButton(
                        enabled = amount != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = {
                            if (amount != null) {
                                onStateChange(PartyScoreKeeper.add(shown, customFor, -amount))
                                haptics(HapticSignal.LIGHT_TICK)
                            }
                            customFor = -1
                        }
                    ) { Text(stringResource(R.string.party1_score_custom_subtract)) }
                    TextButton(
                        enabled = amount != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = {
                            if (amount != null) {
                                onStateChange(PartyScoreKeeper.add(shown, customFor, amount))
                                haptics(HapticSignal.LIGHT_TICK)
                            }
                            customFor = -1
                        }
                    ) { Text(stringResource(R.string.party1_score_custom_add)) }
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { customFor = -1 }
                ) { Text(stringResource(R.string.party1_cancel)) }
            }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.party1_score_reset_title)) },
            text = { Text(stringResource(R.string.party1_score_reset_body)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        confirmReset = false
                        haptics(HapticSignal.NORMAL_ACTION)
                        onStateChange(PartyScoreKeeper.reset(shown))
                    }
                ) { Text(stringResource(R.string.party1_score_reset)) }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { confirmReset = false }
                ) { Text(stringResource(R.string.party1_cancel)) }
            }
        )
    }
}

/** Player-count stepper shown by tools while the roster is empty. */
@Composable
internal fun PartyFallbackCount(palette: PartyUiPalette, count: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.party1_players_label), color = palette.textPrimary)
        PartyUiStepper(
            palette = palette,
            value = count,
            range = PARTY_ROSTER_MIN..PARTY_ROSTER_MAX,
            label = stringResource(R.string.party1_players_count_cd, count),
            decreaseDescription = stringResource(R.string.party1_players_fewer),
            increaseDescription = stringResource(R.string.party1_players_more),
            onChange = onChange
        )
    }
}

@Composable
private fun PartyScorePlayerRow(
    palette: PartyUiPalette,
    name: String,
    total: Int,
    isLeader: Boolean,
    hitTarget: Boolean,
    onAdd: (Int) -> Unit,
    onCustom: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isLeader) 3.dp else 1.dp,
                color = if (isLeader) palette.accentText else palette.chipBackground,
                shape = shape
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isLeader) {
                    Text(
                        stringResource(R.string.party1_score_leading),
                        color = palette.accentText,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (hitTarget) {
                    Text(
                        stringResource(R.string.party1_score_target_reached),
                        color = palette.accentText,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text(
                total.toString(),
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                modifier = Modifier.semantics {
                    contentDescription = "$name, $total"
                    liveRegion = LiveRegionMode.Polite
                }
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (amount in PARTY_QUICK_AMOUNTS) {
                PartyUiPill(
                    palette = palette,
                    text = partyFormatDelta(amount),
                    description = if (amount > 0) {
                        stringResource(R.string.party1_score_add_cd, amount, name)
                    } else {
                        stringResource(R.string.party1_score_subtract_cd, -amount, name)
                    },
                    onClick = { onAdd(amount) },
                    filled = amount > 0
                )
            }
            PartyUiPill(
                palette = palette,
                text = stringResource(R.string.party1_score_custom),
                description = stringResource(R.string.party1_score_custom_cd, name),
                onClick = onCustom,
                filled = false
            )
        }
    }
}

private val PARTY_QUICK_AMOUNTS = listOf(-1, 1, 5, 10)

@Composable
private fun PartyScoreHistory(
    palette: PartyUiPalette,
    state: PartyScoreState,
    names: List<String>,
    totals: List<Int>,
    leaders: Set<Int>
) {
    PartyUiCard(palette) {
        Text(
            stringResource(R.string.party1_score_history),
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))
        if (state.entries.isEmpty()) {
            Text(stringResource(R.string.party1_score_history_empty), color = palette.textPrimary.copy(alpha = 0.85f))
            return@PartyUiCard
        }
        val table = PartyScoreKeeper.roundTable(state)
        val none = stringResource(R.string.party1_score_no_entry)
        val roundWord = stringResource(R.string.party1_score_history_round)
        val totalWord = stringResource(R.string.party1_score_total)
        val cellWidth = 76.dp
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            // Header: names.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    roundWord,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.width(64.dp).padding(vertical = 4.dp)
                )
                for (p in 0 until state.playerCount) {
                    Text(
                        names.getOrElse(p) { "" },
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(cellWidth).padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }
            }
            for ((r, row) in table.withIndex()) {
                val spoken = stringResource(
                    R.string.party1_score_round_cd,
                    r + 1,
                    row.withIndex().joinToString(", ") { (p, v) ->
                        "${names.getOrElse(p) { "" }} ${if (v == null) none else partyFormatDelta(v)}"
                    }
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }
                ) {
                    Text(
                        (r + 1).toString(),
                        color = palette.textPrimary,
                        modifier = Modifier.width(64.dp).padding(vertical = 4.dp)
                    )
                    for (v in row) {
                        Text(
                            if (v == null) "–" else partyFormatDelta(v),
                            color = palette.textPrimary,
                            maxLines = 1,
                            modifier = Modifier.width(cellWidth).padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            // Totals.
            val totalSpoken = totals.withIndex().joinToString(", ") { (p, v) -> "${names.getOrElse(p) { "" }} $v" }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clearAndSetSemantics { contentDescription = "$totalWord: $totalSpoken" }
            ) {
                Text(
                    totalWord,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.width(64.dp).padding(vertical = 4.dp)
                )
                for (p in 0 until state.playerCount) {
                    val t = totals.getOrElse(p) { 0 }
                    Text(
                        if (p in leaders) "★ $t" else t.toString(),
                        color = if (p in leaders) palette.accentText else palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.width(cellWidth).padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
