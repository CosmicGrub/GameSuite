package com.gamesuite.ui.party

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.PartyRosterLogic
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import kotlin.random.Random

/** Team colours (light theme, dark theme). The team's letter label is always shown too. */
private val PARTY_TEAM_COLORS_LIGHT = listOf(Color(0xFFB23A2A), Color(0xFF1F5FA8), Color(0xFF2E7D32), Color(0xFF7B3FA0))
private val PARTY_TEAM_COLORS_DARK = listOf(Color(0xFFFF8A75), Color(0xFF7DB4FF), Color(0xFF7FD186), Color(0xFFCFA0F0))

/**
 * Team Splitter: splits the roster into 2 to 4 random teams whose sizes differ by at most one,
 * with Re-roll and Copy as text. Teams carry a text label (Team A...) as well as a colour.
 * [roster] is the shared roster (empty or 2..12 unique names); empty falls back to "Player 1..n"
 * with its own player-count stepper. [random] seeds each split so tests/callers can inject one.
 */
@Composable
fun PartyTeamSplitterTool(roster: List<String>, random: Random = Random.Default) {
    val palette = partyUiPalette()
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val clipboard = LocalClipboardManager.current
    val resources = LocalContext.current.resources

    val rosterActive = roster.size >= com.gamesuite.core.PARTY_ROSTER_MIN
    var fallbackCount by rememberSaveable { mutableStateOf(4) }
    val names = PartyRosterLogic.effective(
        if (rosterActive) roster else emptyList(),
        fallbackCount,
        stringResource(R.string.party1_player_prefix)
    )
    var teamCountRaw by rememberSaveable { mutableStateOf(2) }
    var seed by rememberSaveable { mutableStateOf(0L) }
    var splitKey by rememberSaveable { mutableStateOf("") }
    var copiedSeed by rememberSaveable { mutableStateOf(0L) }

    val maxTeams = PartyTeamSplit.maxTeamsFor(names.size)
    val teamCount = teamCountRaw.coerceIn(PartyTeamSplit.MIN_TEAMS, maxTeams)
    val key = names.joinToString("\n")
    val teams = if (seed != 0L && splitKey == key) {
        PartyTeamSplit.split(names, teamCount, Random(seed))
    } else {
        emptyList()
    }
    val teamWord = stringResource(R.string.party1_team_word)

    val doSplit: () -> Unit = {
        val next = random.nextLong()
        seed = if (next == 0L) 1L else next
        splitKey = key
        haptics(HapticSignal.SUCCESS)
        playSfx(SfxKind.SUCCESS_CHIME)
    }

    PartyUiCard(palette) {
        Text(
            stringResource(R.string.party1_teams_title),
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            stringResource(R.string.party1_teams_hint),
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
            PartyFallbackCount(palette, names.size) { fallbackCount = it }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.party1_teams_count_label), color = palette.textPrimary)
            PartyUiStepper(
                palette = palette,
                value = teamCount,
                range = PartyTeamSplit.MIN_TEAMS..maxTeams,
                label = stringResource(R.string.party1_teams_count_cd, teamCount),
                decreaseDescription = stringResource(R.string.party1_teams_fewer),
                increaseDescription = stringResource(R.string.party1_teams_more),
                onChange = { teamCountRaw = it }
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (teams.isEmpty()) {
                PartyUiButton(palette = palette, onClick = doSplit) {
                    Text(stringResource(R.string.party1_teams_split))
                }
            } else {
                PartyUiButton(palette = palette, onClick = doSplit) {
                    Text(stringResource(R.string.party1_teams_reroll))
                }
                PartyUiOutlinedButton(
                    palette = palette,
                    onClick = {
                        clipboard.setText(AnnotatedString(PartyTeamSplit.asText(teams, teamWord)))
                        copiedSeed = seed
                        haptics(HapticSignal.LIGHT_TICK)
                    }
                ) { Text(stringResource(R.string.party1_teams_copy)) }
            }
        }
        if (teams.isNotEmpty() && copiedSeed == seed) {
            Text(
                stringResource(R.string.party1_teams_copied),
                color = palette.accentText,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        if (teams.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val colors = if (palette.isDark) PARTY_TEAM_COLORS_DARK else PARTY_TEAM_COLORS_LIGHT
            for ((i, members) in teams.withIndex()) {
                val color = colors[i % colors.size]
                val label = "$teamWord ${PartyTeamSplit.letter(i)}"
                val size = resources.getQuantityString(R.plurals.party1_team_size, members.size, members.size)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(bottom = 8.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "$label, $size: ${members.joinToString(", ")}"
                        }
                ) {
                    Box(modifier = Modifier.width(8.dp).fillMaxHeight().background(color))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            "$label ($size)",
                            color = color,
                            fontWeight = FontWeight.Bold
                        )
                        for (m in members) {
                            Text(m, color = palette.textPrimary)
                        }
                    }
                }
            }
        }
    }
}
