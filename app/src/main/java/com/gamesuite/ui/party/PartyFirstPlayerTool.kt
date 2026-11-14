package com.gamesuite.ui.party

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalReducedMotion
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val PARTY_PICK_HISTORY_LIMIT = 12

/**
 * A fair pick of the first player (uniform over [roster]; an empty roster means "no names set":
 * Player 1..n with a stepper). A short suspense animation walks a highlight round the names and
 * slows to a stop on a winner chosen up front; under reduced motion the result appears at once.
 * Success haptic + chime on the result. Past picks are kept in [PartySession].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyFirstPlayerTool(roster: List<String>) {
    val palette = partyPalette()
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val reducedMotion = LocalReducedMotion.current

    var runSeq by remember { mutableStateOf(0) }
    var target by remember { mutableStateOf(-1) }
    var highlight by remember { mutableStateOf(-1) }
    var spinning by remember { mutableStateOf(false) }

    PartyPanel(palette) {
        val names = partyRosterNames(roster, palette, haptics)
        val latestNames by rememberUpdatedState(names)

        LaunchedEffect(runSeq) {
            if (runSeq == 0) return@LaunchedEffect
            val chosen = target
            if (!reducedMotion) {
                spinning = true
                val sequence = PartyFirstPick.suspenseSequence(latestNames.size, chosen)
                sequence.forEachIndexed { i, index ->
                    highlight = index
                    playSfx(SfxKind.LIGHT_TICK)
                    delay(PartyFirstPick.delayMs(i, sequence.size))
                }
            }
            highlight = chosen
            spinning = false
            latestNames.getOrNull(chosen)?.let { winner ->
                PartySession.pickHistory = (listOf(winner) + PartySession.pickHistory).take(PARTY_PICK_HISTORY_LIMIT)
            }
            haptics(HapticSignal.SUCCESS)
            playSfx(SfxKind.SUCCESS_CHIME)
        }

        PartyHeading(stringResource(R.string.party2_first_title), palette)
        Spacer(Modifier.height(12.dp))

        // The name chips are decorative: the result below is what a screen reader announces.
        FlowRow(
            modifier = Modifier.clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            names.forEachIndexed { i, name ->
                val lit = i == highlight
                Box(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (lit) palette.accent else palette.chipBackground)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val marker = if (lit) (if (spinning) "▶ " else "★ ") else ""
                    Text(
                        marker + name,
                        color = if (lit) palette.textOnAccent else palette.textPrimary,
                        fontWeight = if (lit) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        PartyButton(
            palette = palette,
            enabled = !spinning && names.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                target = PartyFirstPick.pick(names.size, Random.Default)
                highlight = -1
                runSeq++
                haptics(HapticSignal.NORMAL_ACTION)
            }
        ) { Text(stringResource(R.string.party2_first_pick)) }

        Spacer(Modifier.height(12.dp))
        val winner = if (!spinning && highlight >= 0) names.getOrNull(highlight) else null
        Text(
            text = when {
                spinning -> stringResource(R.string.party2_first_choosing)
                winner != null -> stringResource(R.string.party2_first_result, winner)
                else -> ""
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = palette.accentText,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )

        val history = PartySession.pickHistory
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.party2_first_history), color = palette.textPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                history.joinToString(", "),
                color = palette.textPrimary.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(8.dp))
            PartyOutlinedButton(
                palette = palette,
                onClick = {
                    PartySession.pickHistory = emptyList()
                    haptics(HapticSignal.LIGHT_TICK)
                }
            ) { Text(stringResource(R.string.party2_first_clear)) }
        }
    }
}
