package com.gamesuite.ui.party

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.haptics.HapticSignal

/*
 * Shared building blocks for the five game-kit tools. Visually matches the Party Toolkit screen's
 * warm palette (copied, not shared: that screen keeps its palette private).
 */

/**
 * Process-wide state of the tools. The host screen shows one tool at a time and leaves the others
 * out of composition, so anything that must survive a tab switch lives here (snapshot state, so
 * Compose still observes it). It also survives rotation because the process does.
 */
internal object PartySession {
    var fallbackPlayers by mutableStateOf(4)

    var turnKey by mutableStateOf("")
    var turnOrder by mutableStateOf<List<Int>>(emptyList())
    var turnLocked by mutableStateOf<Set<Int>>(emptySet())

    var pickHistory by mutableStateOf<List<String>>(emptyList())

    var clock by mutableStateOf<PartyClockState?>(null)
    var rounds by mutableStateOf(PartyRoundState())

    var dealSpec by mutableStateOf(PartyDeckSpec())
    var dealPerPlayer by mutableStateOf(5)
    var dealt by mutableStateOf<PartyDealt?>(null)
}

internal data class PartyPalette(
    val background: Color,
    val panel: Color,
    val accent: Color,
    val accentText: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val danger: Color
)

@Composable
internal fun partyPalette(): PartyPalette {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (!dark) {
        PartyPalette(
            background = Color(0xFFFBF1E6),
            panel = Color(0xFFF4E3CE),
            accent = Color(0xFFE08D4B),
            accentText = Color(0xFF9A4E12),
            textPrimary = Color(0xFF3A2E22),
            textOnAccent = Color(0xFF3A2E22),
            chipBackground = Color(0xFFE3CBA9),
            danger = Color(0xFFA8281C)
        )
    } else {
        PartyPalette(
            background = Color(0xFF1C1712),
            panel = Color(0xFF2A231B),
            accent = Color(0xFFE8985B),
            accentText = Color(0xFFE8985B),
            textPrimary = Color(0xFFF3E9DB),
            textOnAccent = Color(0xFF1C1712),
            chipBackground = Color(0xFF453A2E),
            danger = Color(0xFFFFB4AB)
        )
    }
}

internal const val PARTY_MIN_TOUCH_DP = 48

@Composable
internal fun PartyPanel(palette: PartyPalette, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = palette.panel, contentColor = palette.textPrimary)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), content = content)
    }
}

@Composable
internal fun PartyHeading(text: String, palette: PartyPalette) {
    Text(
        text,
        color = palette.textPrimary,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
}

@Composable
internal fun PartyBodyText(text: String, palette: PartyPalette, modifier: Modifier = Modifier) {
    Text(text, color = palette.textPrimary.copy(alpha = 0.85f), modifier = modifier)
}

@Composable
internal fun PartyButton(
    palette: PartyPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = PARTY_MIN_TOUCH_DP.dp),
        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.textOnAccent),
        content = content
    )
}

@Composable
internal fun PartyOutlinedButton(
    palette: PartyPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = PARTY_MIN_TOUCH_DP.dp),
        border = BorderStroke(1.dp, palette.accentText),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
        content = content
    )
}

/** A selectable chip (radio-style) at least 48dp tall. */
@Composable
internal fun PartyChip(
    text: String,
    selected: Boolean,
    palette: PartyPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = text
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = PARTY_MIN_TOUCH_DP.dp, minHeight = PARTY_MIN_TOUCH_DP.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) palette.accent else palette.chipBackground)
            .semantics { contentDescription = description }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (selected) "✓ $text" else text,
            color = if (selected) palette.textOnAccent else palette.textPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

@Composable
private fun PartyStepButton(symbol: String, description: String, enabled: Boolean, palette: PartyPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(PARTY_MIN_TOUCH_DP.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(24.dp))
            .background(palette.chipBackground)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

/** A minus / value / plus control. [label] names the value for screen readers ("Bid", "Minutes"). */
@Composable
internal fun PartyStepper(
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    label: String,
    palette: PartyPalette,
    haptics: (HapticSignal) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    valueText: (Int) -> String = { it.toString() }
) {
    val shown = valueText(value)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        PartyStepButton(
            symbol = "−",
            description = stringResource(R.string.party2_decrease, label),
            enabled = value > range.first,
            palette = palette
        ) {
            onChange((value - step).coerceAtLeast(range.first))
            haptics(HapticSignal.LIGHT_TICK)
        }
        Text(
            shown,
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 56.dp)
                .semantics { contentDescription = "$label: $shown" }
        )
        PartyStepButton(
            symbol = "+",
            description = stringResource(R.string.party2_increase, label),
            enabled = value < range.last,
            palette = palette
        ) {
            onChange((value + step).coerceAtMost(range.last))
            haptics(HapticSignal.LIGHT_TICK)
        }
    }
}

/**
 * The names a tool works with. When the host passes an empty roster this also draws a "Players"
 * stepper (2..12) and returns "Player 1..n"; otherwise it draws nothing.
 */
@Composable
internal fun partyRosterNames(roster: List<String>, palette: PartyPalette, haptics: (HapticSignal) -> Unit): List<String> {
    val template = stringResource(R.string.party2_player_n)
    val hasNames = roster.any { it.isNotBlank() }
    if (!hasNames) {
        val label = stringResource(R.string.party2_players)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, color = palette.textPrimary, fontWeight = FontWeight.Bold)
            PartyStepper(
                value = PartySession.fallbackPlayers,
                range = PartyRoster.MIN_PLAYERS..PartyRoster.MAX_PLAYERS,
                onChange = { PartySession.fallbackPlayers = it },
                label = label,
                palette = palette,
                haptics = haptics
            )
        }
        Spacer(Modifier.height(12.dp))
    }
    return PartyRoster.resolve(roster, PartySession.fallbackPlayers) { String.format(template, it) }
}

@Composable
internal fun PartyConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = PARTY_MIN_TOUCH_DP.dp)) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = PARTY_MIN_TOUCH_DP.dp)) {
                Text(stringResource(R.string.party2_cancel))
            }
        }
    )
}

internal fun partyCopyToClipboard(context: Context, label: String, text: String): Boolean = try {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
    true
} catch (_: Exception) {
    false
}
