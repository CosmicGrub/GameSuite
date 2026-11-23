package com.gamesuite.ui.party

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics

private fun partySigned(value: Int): String = if (value > 0) "+$value" else value.toString()

/**
 * Round / bid tracker for trick-taking and bidding games, for [roster] (an empty roster means "no
 * names set": Player 1..n with a stepper). Two modes: "Bid & tricks" scores each round from the bid
 * and the tricks won (default: bid made = 10 + bid, otherwise minus the bid; the made bonus, the
 * miss rule and "extra tricks still make it" are configurable) and "Manual points" just takes
 * points. Rounds are listed, the last can be undone, totals and the leader are always shown, and a
 * full reset asks first. State lives in [PartySession] so it survives tab switches and rotation; a
 * changed player count starts a fresh game.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyRoundTrackerTool(roster: List<String>) {
    val palette = partyPalette()
    val haptics = rememberHaptics()
    var confirmReset by remember { mutableStateOf(false) }
    var manualStep by remember { mutableStateOf(1) }

    PartyPanel(palette) {
        val names = partyRosterNames(roster, palette, haptics)
        val n = names.size
        val state = PartyRoundTracker.fit(PartySession.rounds, n)
        val set: (PartyRoundState) -> Unit = { PartySession.rounds = it }
        val bidMode = state.mode == PartyRoundMode.BID_TRICKS
        val draft = PartyRoundTracker.draftScores(state)
        val totals = PartyRoundTracker.totals(state)
        val leaders = PartyRoundTracker.leaders(totals, state.rounds.size)

        PartyHeading(stringResource(R.string.party2_round_title), palette)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PartyChip(stringResource(R.string.party2_round_mode_bid), bidMode, palette, {
                set(state.copy(mode = PartyRoundMode.BID_TRICKS)); haptics(HapticSignal.LIGHT_TICK)
            })
            PartyChip(stringResource(R.string.party2_round_mode_manual), !bidMode, palette, {
                set(state.copy(mode = PartyRoundMode.MANUAL)); haptics(HapticSignal.LIGHT_TICK)
            })
        }

        if (bidMode) {
            Spacer(Modifier.height(12.dp))
            RulesSection(state.rules, palette, haptics) { set(state.copy(rules = it)) }
        } else {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.party2_round_step), color = palette.textPrimary, fontWeight = FontWeight.Bold)
                for (s in listOf(1, 5, 10)) {
                    PartyChip(s.toString(), manualStep == s, palette, { manualStep = s })
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        PartyHeading(stringResource(R.string.party2_round_n, state.rounds.size + 1), palette)
        Spacer(Modifier.height(4.dp))
        names.forEachIndexed { i, name ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        name,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.party2_round_preview, partySigned(draft.getOrElse(i) { 0 })),
                        color = palette.accentText,
                        fontWeight = FontWeight.Bold
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (bidMode) {
                        val bidLabel = stringResource(R.string.party2_round_bid)
                        val wonLabel = stringResource(R.string.party2_round_won)
                        LabelledStepper(bidLabel, name, state.bids[i], 0..PartyRoundTracker.MAX_TRICKS, 1, palette, haptics) {
                            set(state.copy(bids = PartyRoundTracker.withValue(state.bids, i, it)))
                        }
                        LabelledStepper(wonLabel, name, state.tricks[i], 0..PartyRoundTracker.MAX_TRICKS, 1, palette, haptics) {
                            set(state.copy(tricks = PartyRoundTracker.withValue(state.tricks, i, it)))
                        }
                    } else {
                        LabelledStepper(
                            stringResource(R.string.party2_round_points), name, state.points[i],
                            -PartyRoundTracker.MAX_POINTS..PartyRoundTracker.MAX_POINTS, manualStep, palette, haptics
                        ) { set(state.copy(points = PartyRoundTracker.withValue(state.points, i, it))) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PartyButton(palette = palette, onClick = {
                set(PartyRoundTracker.commit(state))
                haptics(HapticSignal.NORMAL_ACTION)
            }) { Text(stringResource(R.string.party2_round_add)) }
            PartyOutlinedButton(palette = palette, enabled = state.rounds.isNotEmpty(), onClick = {
                set(PartyRoundTracker.undo(state))
                haptics(HapticSignal.LIGHT_TICK)
            }) { Text(stringResource(R.string.party2_round_undo)) }
            PartyOutlinedButton(palette = palette, enabled = state.rounds.isNotEmpty(), onClick = { confirmReset = true }) {
                Text(stringResource(R.string.party2_round_reset))
            }
        }

        Spacer(Modifier.height(16.dp))
        PartyHeading(stringResource(R.string.party2_round_totals), palette)
        Spacer(Modifier.height(4.dp))
        if (state.rounds.isEmpty()) {
            PartyBodyText(stringResource(R.string.party2_round_none), palette)
        } else {
            Text(
                stringResource(R.string.party2_round_leader, leaders.joinToString(", ") { names.getOrElse(it) { "?" } }),
                color = palette.accentText,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.height(4.dp))
        }
        names.forEachIndexed { i, name ->
            val total = totals.getOrElse(i) { 0 }
            val isLeader = i in leaders
            val description = stringResource(R.string.party2_round_total_desc, name, total) +
                if (isLeader) " " + stringResource(R.string.party2_round_leading) else ""
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .semantics(mergeDescendants = true) { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    (if (isLeader) "★ " else "") + name,
                    color = palette.textPrimary,
                    fontWeight = if (isLeader) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clearAndSetSemantics {}
                )
                Text(
                    total.toString(),
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
        }

        if (state.rounds.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            PartyHeading(stringResource(R.string.party2_round_history), palette)
            Spacer(Modifier.height(4.dp))
            for (index in state.rounds.indices.reversed()) {
                val round = state.rounds[index]
                val line = names.indices.joinToString(", ") { p -> names[p] + " " + partySigned(round.points.getOrElse(p) { 0 }) }
                Text(
                    stringResource(R.string.party2_round_history_line, index + 1, line),
                    color = palette.textPrimary.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(2.dp))
            }
        }

        if (confirmReset) {
            PartyConfirmDialog(
                title = stringResource(R.string.party2_round_reset_title),
                body = stringResource(R.string.party2_round_reset_body),
                confirmLabel = stringResource(R.string.party2_round_reset),
                onConfirm = {
                    set(PartyRoundTracker.reset(state))
                    confirmReset = false
                    haptics(HapticSignal.NORMAL_ACTION)
                },
                onDismiss = { confirmReset = false }
            )
        }
    }
}

/** A short caption next to a stepper; the stepper's own description names the player and the value. */
@Composable
private fun LabelledStepper(
    caption: String,
    playerName: String,
    value: Int,
    range: IntRange,
    step: Int,
    palette: PartyPalette,
    haptics: (HapticSignal) -> Unit,
    onChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(caption, color = palette.textPrimary.copy(alpha = 0.85f), modifier = Modifier.clearAndSetSemantics {})
        PartyStepper(
            value = value,
            range = range,
            onChange = onChange,
            label = "$caption, $playerName",
            palette = palette,
            haptics = haptics,
            step = step
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesSection(
    rules: PartyBidRules,
    palette: PartyPalette,
    haptics: (HapticSignal) -> Unit,
    onChange: (PartyBidRules) -> Unit
) {
    val bonusLabel = stringResource(R.string.party2_round_made_bonus)
    PartyHeading(stringResource(R.string.party2_round_rules), palette)
    Spacer(Modifier.height(4.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.heightIn(min = PARTY_MIN_TOUCH_DP.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(bonusLabel, color = palette.textPrimary, modifier = Modifier.clearAndSetSemantics {})
        }
        PartyStepper(
            value = rules.madeBonus,
            range = 0..20,
            onChange = { onChange(rules.copy(madeBonus = it)) },
            label = bonusLabel,
            palette = palette,
            haptics = haptics
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.party2_round_miss), color = palette.textPrimary)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val options = listOf(
            PartyMissRule.MINUS_BID to R.string.party2_miss_minus_bid,
            PartyMissRule.MINUS_DIFFERENCE to R.string.party2_miss_minus_diff,
            PartyMissRule.ZERO to R.string.party2_miss_zero
        )
        for ((rule, label) in options) {
            PartyChip(stringResource(label), rules.missRule == rule, palette, {
                onChange(rules.copy(missRule = rule)); haptics(HapticSignal.LIGHT_TICK)
            })
        }
    }
    Spacer(Modifier.height(4.dp))
    val overLabel = stringResource(R.string.party2_round_over_makes)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PARTY_MIN_TOUCH_DP.dp)
            .toggleable(
                value = rules.overMakes,
                role = Role.Switch,
                onValueChange = { onChange(rules.copy(overMakes = it)); haptics(HapticSignal.LIGHT_TICK) }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(overLabel, color = palette.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = rules.overMakes,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.textOnAccent,
                checkedTrackColor = palette.accent
            )
        )
    }
}
