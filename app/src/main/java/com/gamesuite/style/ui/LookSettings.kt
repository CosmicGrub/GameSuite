package com.gamesuite.style.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamesuite.R
import com.gamesuite.games.gemcascade.Cell
import com.gamesuite.games.gemcascade.GemBoard
import com.gamesuite.games.gemcascade.PieceType
import com.gamesuite.games.gemcascade.SpecialKind
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.style.ArtTier
import com.gamesuite.style.ASSET_GEM_ATLAS
import com.gamesuite.style.BoardCamera
import com.gamesuite.style.DeviceCaps
import com.gamesuite.style.DrawCommandBudget
import com.gamesuite.style.GameStyleSupport
import com.gamesuite.style.MotionLevel
import com.gamesuite.style.QualityLevel
import com.gamesuite.style.RamClass
import com.gamesuite.style.StylePrefs
import com.gamesuite.style.StyleContext
import com.gamesuite.style.StyleReason
import com.gamesuite.style.StyleResolver
import com.gamesuite.style.gem.GemAtlas
import com.gamesuite.style.gem.GemBoardPainter
import com.gamesuite.style.gem.GemDrawState
import com.gamesuite.style.gem.GemFrame
import com.gamesuite.style.gem.GemPalette
import com.gamesuite.ui.GemPaints

internal fun artNameRes(t: ArtTier): Int = when (t) {
    ArtTier.FLAT -> R.string.style_art_flat; ArtTier.SOFT_25D -> R.string.style_art_25d; ArtTier.FULL_3D -> R.string.style_art_3d
}

internal fun artDescRes(t: ArtTier): Int = when (t) {
    ArtTier.FLAT -> R.string.style_art_flat_desc; ArtTier.SOFT_25D -> R.string.style_art_25d_desc; ArtTier.FULL_3D -> R.string.style_art_3d_desc
}

internal fun reasonRes(r: StyleReason): Int? = when (r) {
    StyleReason.REDUCED_MOTION -> R.string.style_reason_reduced_motion
    StyleReason.BATTERY_SAVER -> R.string.style_reason_battery_saver
    StyleReason.THERMAL -> R.string.style_reason_thermal
    StyleReason.ASSETS_MISSING -> R.string.style_reason_assets_missing
    StyleReason.LOW_RAM -> R.string.style_reason_low_ram
    StyleReason.KID_SAFE -> R.string.style_reason_kid_safe
    StyleReason.ECO_MODE -> R.string.style_reason_eco
    StyleReason.UNSUPPORTED_TIER -> R.string.style_reason_unsupported
    StyleReason.HEALTH_DEMOTED -> R.string.style_reason_health
    StyleReason.QUALITY_BUDGET -> R.string.style_reason_quality
    else -> null
}

/**
 * Settings -> "Look and motion": Art style cards with live previews, Motion level, Quality, Reset. Reads and writes the
 * per-profile [StylePrefs]; no restart is needed (games re-resolve on the next frame).
 */
@Composable
fun StyleLookSection() {
    val vm: StyleViewModel = viewModel()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val caps = rememberDeviceCaps()
    val reduced = LocalReducedMotion.current
    val lowRam = caps.ramClass == RamClass.LOW
    val atlas = rememberGemAtlas(!lowRam, caps.ramClass)
    val resolved = remember(prefs, caps, reduced, atlas) {
        StyleResolver.resolve(prefs, GameStyleSupport.GEM_CASCADE, StyleContext(caps, reducedMotion = reduced, assetsReady = if (atlas != null) setOf(ASSET_GEM_ATLAS) else emptySet()))
    }

    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(R.string.style_section_title),
        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() }
    )
    HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
    Column(Modifier.focusGroup()) {
        Text(stringResource(R.string.style_intro), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))

        Text(stringResource(R.string.style_art_title), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (tier in ArtTier.values()) {
                val unavailable = tier == ArtTier.FULL_3D && lowRam
                ArtCard(
                    tier = tier, selected = prefs.art == tier, enabled = !unavailable, atlas = atlas,
                    modifier = Modifier.weight(1f),
                    onClick = { vm.setArt(tier) }
                )
            }
        }
        if (lowRam) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.style_art_3d_unavailable), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.style_preview_label), style = MaterialTheme.typography.labelSmall)

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.style_motion_title), style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.selectableGroup()) {
            // Lively is intentionally not offered: its performance budget is not proven yet.
            RadioRow(stringResource(R.string.style_motion_calm), prefs.motion == MotionLevel.CALM || reduced, enabled = !reduced) { vm.setMotion(MotionLevel.CALM) }
            RadioRow(stringResource(R.string.style_motion_standard), prefs.motion != MotionLevel.CALM && !reduced, enabled = !reduced) { vm.setMotion(MotionLevel.STANDARD) }
        }
        if (reduced) Text(stringResource(R.string.style_motion_locked), style = MaterialTheme.typography.labelSmall)

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.style_quality_title), style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.selectableGroup()) {
            for ((q, res) in listOf(
                QualityLevel.AUTO to R.string.style_quality_auto, QualityLevel.HIGH to R.string.style_quality_high,
                QualityLevel.BALANCED to R.string.style_quality_balanced, QualityLevel.BATTERY_SAVER to R.string.style_quality_saver
            )) RadioRow(stringResource(res), prefs.quality == q, true) { vm.setQuality(q) }
        }

        val reasons = resolved.reasons.mapNotNull { reasonRes(it) }.distinct()
        if (reasons.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            for (r in reasons) Text(stringResource(r), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.style_a11y_note), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.reset() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.style_reset)) }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ArtCard(tier: ArtTier, selected: Boolean, enabled: Boolean, atlas: GemAtlas?, modifier: Modifier, onClick: () -> Unit) {
    val name = stringResource(artNameRes(tier))
    val desc = stringResource(artDescRes(tier))
    val full = stringResource(R.string.style_art_card_desc, name, desc) + if (selected) ", " + stringResource(R.string.style_art_selected) else ""
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(8.dp)
            .semantics(mergeDescendants = true) { contentDescription = full },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GemStylePreview(tier, atlas, Modifier.size(96.dp))
        Spacer(Modifier.height(6.dp))
        // selection never relies on colour alone: the ring is thicker AND the label carries a check mark
        Text((if (selected) "✓ " else "") + name, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.outline)
        Text(desc, style = MaterialTheme.typography.labelSmall)
    }
}

/** A small static board drawn by the REAL Gem Cascade painter in [tier] (3D falls back to 2.5D when the atlas is not decoded). */
@Composable
fun GemStylePreview(tier: ArtTier, atlas: GemAtlas?, modifier: Modifier = Modifier) {
    val text = rememberTextMeasurer()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = MaterialTheme.colorScheme.primary
    val fg = MaterialTheme.colorScheme.onBackground
    val reduced = LocalReducedMotion.current
    val state = remember {
        GemDrawState().also { st ->
            val b = GemBoard(4, 4)
            for (r in 0 until 4) for (c in 0 until 4) b[r, c] = Cell.gem((r * 2 + c * 3) % 7)
            b[1, 1] = Cell.gem(1).copy(special = SpecialKind.FLARE_ROW)
            b[2, 2] = Cell(PieceType.STONE, hp = 2)
            st.board = b
        }
    }
    val paints = remember { GemPaints() }
    val resolved = remember(tier, atlas) {
        val prefs = StylePrefs(art = if (tier == ArtTier.FULL_3D && atlas == null) ArtTier.SOFT_25D else tier)
        StyleResolver.resolve(prefs, GameStyleSupport.GEM_CASCADE, StyleContext(DeviceCaps.CAPABLE, reducedMotion = reduced, assetsReady = if (atlas != null) setOf(ASSET_GEM_ATLAS) else emptySet()))
    }
    val palette = GemPalette(dark, fg, accent, if (dark) Color(0xFF2A2D3A) else Color(0xFFE9E6F2), if (dark) Color(0xFF343848) else Color(0xFFF4F1FA), if (dark) Color(0xFF2C3040) else Color(0xFFE2DEEF))
    val budget = remember { DrawCommandBudget(2400) }
    Canvas(modifier.semantics { contentDescription = "" }) {
        val cell = size.width / 4f
        val cam = BoardCamera(cell * 4f, cell * 4f, resolved.tiltDegrees, size.width, size.height)
        val frame = GemFrame(state, 1f, 1f, 0L, cell, 4, 4, reduced, text, palette, paints, resolved, cam, atlas, budget) { }
        GemBoardPainter.paint(this, frame)
    }
}
