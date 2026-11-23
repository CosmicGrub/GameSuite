package com.gamesuite.ui.party

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Colour tokens and small controls for the roster editor and the Score Keeper / Team Splitter /
 * Who Pays tools. Same warm tokens as the Party Toolkit screen's own palette (private to that
 * file), so these tools sit visually with the original ones. Every name is prefixed `PartyUi` to
 * stay clear of the other Party Toolkit half's helpers in this package.
 */
internal data class PartyUiPalette(
    val isDark: Boolean,
    val background: Color,
    val accent: Color,
    val accentText: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color
)

@Composable
internal fun partyUiPalette(): PartyUiPalette {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (!dark) {
        PartyUiPalette(
            isDark = false,
            background = Color(0xFFFBF1E6),
            accent = Color(0xFFE08D4B),
            accentText = Color(0xFF9A4E12),
            textPrimary = Color(0xFF3A2E22),
            textOnAccent = Color(0xFF3A2E22),
            chipBackground = Color(0xFFE3CBA9)
        )
    } else {
        PartyUiPalette(
            isDark = true,
            background = Color(0xFF1C1712),
            accent = Color(0xFFE8985B),
            accentText = Color(0xFFE8985B),
            textPrimary = Color(0xFFF3E9DB),
            textOnAccent = Color(0xFF1C1712),
            chipBackground = Color(0xFF453A2E)
        )
    }
}

@Composable
internal fun PartyUiCard(palette: PartyUiPalette, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = palette.background, contentColor = palette.textPrimary),
        border = BorderStroke(1.dp, palette.chipBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
internal fun PartyUiButton(
    palette: PartyUiPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.accent,
            contentColor = palette.textOnAccent,
            disabledContainerColor = palette.chipBackground,
            disabledContentColor = palette.textPrimary.copy(alpha = 0.55f)
        ),
        content = content
    )
}

@Composable
internal fun PartyUiOutlinedButton(
    palette: PartyUiPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = palette.textPrimary,
            disabledContentColor = palette.textPrimary.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, palette.textPrimary.copy(alpha = 0.55f)),
        content = content
    )
}

/** A 48dp touch target drawn as a 36dp circle with a symbol. [description] is the spoken name. */
@Composable
internal fun PartyUiIconButton(
    palette: PartyUiPalette,
    symbol: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (enabled) palette.accent else palette.chipBackground),
            contentAlignment = Alignment.Center
        ) {
            Text(
                symbol,
                color = if (enabled) palette.textOnAccent else palette.textPrimary.copy(alpha = 0.55f),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    }
}

/** A pill-shaped 48dp-high button, used for the quick score amounts. */
@Composable
internal fun PartyUiPill(
    palette: PartyUiPalette,
    text: String,
    description: String,
    onClick: () -> Unit,
    filled: Boolean = true
) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 56.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (filled) palette.accent else palette.chipBackground)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (filled) palette.textOnAccent else palette.textPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

/** A minus / value / plus control with 48dp targets. [label] is the spoken value. */
@Composable
internal fun PartyUiStepper(
    palette: PartyUiPalette,
    value: Int,
    range: IntRange,
    label: String,
    decreaseDescription: String,
    increaseDescription: String,
    onChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PartyUiIconButton(
            palette, "−", decreaseDescription,
            onClick = { onChange((value - 1).coerceIn(range)) },
            enabled = value > range.first
        )
        Text(
            value.toString(),
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            modifier = Modifier
                .widthIn(min = 32.dp)
                .semantics {
                    contentDescription = label
                    liveRegion = LiveRegionMode.Polite
                }
        )
        PartyUiIconButton(
            palette, "+", increaseDescription,
            onClick = { onChange((value + 1).coerceIn(range)) },
            enabled = value < range.last
        )
    }
}
