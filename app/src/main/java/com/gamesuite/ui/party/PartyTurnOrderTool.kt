package com.gamesuite.ui.party

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import kotlin.random.Random

/**
 * Randomises the seating / turn order of [roster] (an empty roster means "no names set": Player 1..n
 * with a stepper). Individual seats can be locked so a re-shuffle leaves those players where they
 * are. The order is kept in [PartySession], so it survives tab switches; it resets when the roster
 * changes. The result is copyable as plain text. There is no animation, so nothing to reduce.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyTurnOrderTool(roster: List<String>) {
    val palette = partyPalette()
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    PartyPanel(palette) {
        val names = partyRosterNames(roster, palette, haptics)
        val n = names.size
        val key = names.joinToString("\u0000")
        val valid = PartySession.turnKey == key && PartyTurnOrder.isPermutation(PartySession.turnOrder, n)
        val order = if (valid) PartySession.turnOrder else PartyTurnOrder.initial(n)
        val locked = if (valid) PartySession.turnLocked else emptySet()

        // First visit (or a changed roster): deal a random order right away.
        LaunchedEffect(key) {
            if (!(PartySession.turnKey == key && PartyTurnOrder.isPermutation(PartySession.turnOrder, n))) {
                PartySession.turnOrder = PartyTurnOrder.shuffle(PartyTurnOrder.initial(n), emptySet(), Random.Default)
                PartySession.turnLocked = emptySet()
                PartySession.turnKey = key
            }
        }

        PartyHeading(stringResource(R.string.party2_turn_title), palette)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.party2_turn_hint),
            color = palette.textPrimary.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))

        order.forEachIndexed { seat, player ->
            val name = names.getOrElse(player) { "?" }
            val isLocked = seat in locked
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PARTY_MIN_TOUCH_DP.dp)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isLocked) palette.chipBackground else palette.accent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (seat + 1).toString(),
                        color = if (isLocked) palette.textPrimary else palette.textOnAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    name,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val lockDescription = stringResource(
                    if (isLocked) R.string.party2_turn_unlock_seat else R.string.party2_turn_lock_seat,
                    seat + 1,
                    name
                )
                Box(
                    modifier = Modifier
                        .size(PARTY_MIN_TOUCH_DP.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (isLocked) palette.accent else palette.chipBackground)
                        .semantics { contentDescription = lockDescription }
                        .toggleable(
                            value = isLocked,
                            role = Role.Switch,
                            onValueChange = { nowLocked ->
                                PartySession.turnLocked = if (nowLocked) locked + seat else locked - seat
                                haptics(HapticSignal.LIGHT_TICK)
                                copied = false
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isLocked) "🔒" else "🔓",
                        color = if (isLocked) palette.textOnAccent else palette.textPrimary,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val canShuffle = PartyTurnOrder.freeSeatCount(order, locked) >= 2
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PartyButton(
                palette = palette,
                enabled = canShuffle,
                onClick = {
                    PartySession.turnOrder = PartyTurnOrder.shuffle(order, locked, Random.Default)
                    PartySession.turnLocked = locked
                    PartySession.turnKey = key
                    copied = false
                    haptics(HapticSignal.NORMAL_ACTION)
                    playSfx(SfxKind.WHOOSH)
                }
            ) { Text(stringResource(R.string.party2_turn_reshuffle)) }
            PartyOutlinedButton(
                palette = palette,
                onClick = {
                    val label = context.getString(R.string.party2_turn_clip_label)
                    copied = partyCopyToClipboard(context, label, PartyTurnOrder.asText(names, order))
                    haptics(HapticSignal.LIGHT_TICK)
                }
            ) { Text(stringResource(R.string.party2_turn_copy)) }
        }
        if (!canShuffle) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.party2_turn_all_locked),
                color = palette.textPrimary.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (copied) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.party2_copied),
                color = palette.accentText,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .widthIn(min = 48.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}
