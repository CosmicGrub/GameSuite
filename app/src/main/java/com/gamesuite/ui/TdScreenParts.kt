package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import com.gamesuite.games.towerdefence.TdGrid
import com.gamesuite.games.towerdefence.TdLayoutMath
import com.gamesuite.games.towerdefence.TdMode
import com.gamesuite.games.towerdefence.TowerDefenceGame
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.games.towerdefence.TdBranch
import com.gamesuite.games.towerdefence.TdEnemyKind
import com.gamesuite.games.towerdefence.TdPlaceResult
import com.gamesuite.games.towerdefence.TdReject
import com.gamesuite.games.towerdefence.TdRole
import com.gamesuite.games.towerdefence.TdRules
import com.gamesuite.games.towerdefence.TdTarget
import com.gamesuite.ui.TdArt.drawEnemyShape
import com.gamesuite.ui.TdArt.drawTowerShape
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Resource lookups (skins supply names; roles and branches have shared copy)
// ---------------------------------------------------------------------------------------------

internal fun tdRoleRes(role: TdRole): Int = when (role) {
    TdRole.BOLT -> R.string.td_role_bolt
    TdRole.BLAST -> R.string.td_role_blast
    TdRole.CONTROL -> R.string.td_role_control
    TdRole.SUPPORT -> R.string.td_role_support
}

internal fun tdRoleDescRes(role: TdRole): Int = when (role) {
    TdRole.BOLT -> R.string.td_role_bolt_desc
    TdRole.BLAST -> R.string.td_role_blast_desc
    TdRole.CONTROL -> R.string.td_role_control_desc
    TdRole.SUPPORT -> R.string.td_role_support_desc
}

internal fun tdTargetRes(t: TdTarget): Int = when (t) {
    TdTarget.FIRST -> R.string.td_target_first
    TdTarget.LAST -> R.string.td_target_last
    TdTarget.STRONG -> R.string.td_target_strong
    TdTarget.CLOSE -> R.string.td_target_close
}

/** Name and description string ids of a role's branch. */
internal fun tdBranchRes(role: TdRole, branch: TdBranch): Pair<Int, Int> = when (role) {
    TdRole.BOLT -> if (branch == TdBranch.A) R.string.td_branch_bolt_a to R.string.td_branch_bolt_a_desc else R.string.td_branch_bolt_b to R.string.td_branch_bolt_b_desc
    TdRole.BLAST -> if (branch == TdBranch.A) R.string.td_branch_blast_a to R.string.td_branch_blast_a_desc else R.string.td_branch_blast_b to R.string.td_branch_blast_b_desc
    TdRole.CONTROL -> if (branch == TdBranch.A) R.string.td_branch_control_a to R.string.td_branch_control_a_desc else R.string.td_branch_control_b to R.string.td_branch_control_b_desc
    TdRole.SUPPORT -> if (branch == TdBranch.A) R.string.td_branch_support_a to R.string.td_branch_support_a_desc else R.string.td_branch_support_b to R.string.td_branch_support_b_desc
}

internal fun tdRejectRes(r: TdReject): Int = when (r) {
    TdReject.UNBUILDABLE -> R.string.td_rej_unbuildable
    TdReject.OVERLAP -> R.string.td_rej_overlap
    TdReject.NO_GOLD -> R.string.td_rej_nogold
    TdReject.BLOCKS_ROUTE -> R.string.td_rej_blocks
    TdReject.TOO_LONG -> R.string.td_rej_toolong
}

internal fun tdMapNameRes(id: String): Int = when (id) {
    "castle-pass" -> R.string.td_map_castle_pass
    "river-bend" -> R.string.td_map_river_bend
    "twin-gates" -> R.string.td_map_twin_gates
    "switchback" -> R.string.td_map_switchback
    "spiral" -> R.string.td_map_spiral
    else -> R.string.td_map_gauntlet
}

/** The feedback line under the ghost: what the placement rules said, in words. */
@Composable
internal fun tdPlacementText(result: TdPlaceResult): String = when (result) {
    is TdPlaceResult.Ok -> {
        val tiles = Math.round(result.detourTiles)
        if (tiles <= 0) stringResource(R.string.td_place_ok_zero) else stringResource(R.string.td_place_ok, tiles)
    }
    is TdPlaceResult.Rejected -> stringResource(tdRejectRes(result.reason))
}

internal fun tdOneDecimal(x: Float): String = String.format(Locale.getDefault(), "%.1f", x)

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

/** A row of equal-width radio chips with 48dp-tall targets. */
@Composable
internal fun TdChipRow(
    labels: List<String>,
    selectedIndex: Int,
    skin: TdSkin,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(modifier = modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) skin.accent else skin.panel.copy(alpha = if (enabled) 0.9f else 0.5f))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (selected) skin.accentOn else skin.panelText,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

/** A round 48dp-minimum icon-less button on the panel colour. */
@Composable
internal fun TdPillButton(
    text: String,
    skin: TdSkin,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    description: String? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) skin.accent else skin.panel,
            contentColor = if (primary) skin.accentOn else skin.panelText,
            disabledContainerColor = skin.panel.copy(alpha = 0.55f),
            disabledContentColor = skin.panelText.copy(alpha = 0.7f)
        ),
        border = if (primary) null else BorderStroke(1.dp, skin.panelText.copy(alpha = 0.35f))
    ) { Text(text, maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge) }
}

/** One of the four tower cards: silhouette, themed name and cost; dimmed (with words) when unaffordable. */
@Composable
internal fun TdTowerCard(
    skin: TdSkin, role: TdRole, gold: Int, selected: Boolean, colorblind: Boolean, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    /** Drag from the card onto the field: called with the finger in ROOT coordinates; [onDragEnd] on release, [onDragCancel] if the system cancels. */
    onDragMove: ((Offset) -> Unit)? = null, onDragEnd: (() -> Unit)? = null, onDragCancel: (() -> Unit)? = null
) {
    var cardRoot by remember { mutableStateOf(Offset.Zero) }
    val moveState = rememberUpdatedState(onDragMove)
    val endState = rememberUpdatedState(onDragEnd)
    val cancelState = rememberUpdatedState(onDragCancel)
    val name = stringResource(skin.towerNames.getValue(role))
    val cost = TdRules.baseCost(role)
    val affordable = gold >= cost
    val roleName = stringResource(tdRoleRes(role))
    val desc = if (affordable) stringResource(R.string.td_tray_card, name, roleName, cost)
    else stringResource(R.string.td_tray_card_cant, name, roleName, cost, cost - gold)
    Column(
        modifier = modifier
            .size(width = 96.dp, height = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(skin.panel.copy(alpha = if (affordable) 0.95f else 0.6f))
            .border(if (selected || highlighted) 3.dp else 1.dp, if (selected || highlighted) skin.accent else skin.panelText.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .onGloballyPositioned { cardRoot = it.positionInRoot() }
            .pointerInput(onDragMove != null) {
                if (onDragMove == null) return@pointerInput
                detectDragGestures(
                    onDragStart = { p -> moveState.value?.invoke(cardRoot + p) },
                    onDrag = { change, _ -> change.consume(); moveState.value?.invoke(cardRoot + change.position) },
                    onDragEnd = { endState.value?.invoke() },
                    onDragCancel = { cancelState.value?.invoke() }
                )
            }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = desc }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(modifier = Modifier.size(28.dp)) {
            drawTowerShape(skin, role, 1, TdBranch.NONE, Offset(size.width / 2f, size.height / 2f), size.width * 0.9f, colorblind, base = false)
        }
        Text(name, color = skin.panelText, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(10.dp)) { drawCircle(Color(0xFFE0A83E)); drawCircle(Color(0xFF8A6A1E), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)) }
            Spacer(Modifier.width(3.dp))
            Text(
                if (affordable) "$cost" else "$cost  x",
                color = if (affordable) skin.panelText else skin.panelText.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1
            )
        }
    }
}

/** Snapshot of the selected tower for the panel; excludes cooldown and age so it rarely changes. */
internal data class TdTowerView(
    val id: Int, val role: TdRole, val level: Int, val branch: TdBranch, val target: TdTarget,
    val sellValue: Int, val upgradeCost: Int, val needsBranch: Boolean, val moveFee: Int
)

/** The selected-tower panel: level, stats, targeting chips, upgrade or branch choice, sell and move. */
@Composable
internal fun TdTowerPanel(
    skin: TdSkin, view: TdTowerView, gold: Int, moving: Boolean,
    onUpgrade: (TdBranch) -> Unit, onSell: () -> Unit, onMove: () -> Unit, onTarget: (TdTarget) -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val name = stringResource(skin.towerNames.getValue(view.role))
    val st = TdRules.stats(view.role, view.level, view.branch)
    val statsLine = if (view.role == TdRole.SUPPORT) {
        stringResource(R.string.td_stats_aura, tdOneDecimal(st.auraRange), Math.round(st.auraRate * 100))
    } else {
        stringResource(R.string.td_stats_attack, "${st.damage.toInt()}", tdOneDecimal(st.shotsPerSecond), tdOneDecimal(st.range))
    }
    Column(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(skin.panel.copy(alpha = 0.96f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.td_tower_line, name, view.level, TdRules.MAX_LEVEL), color = skin.panelText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(statsLine, color = skin.panelText.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                if (view.branch != TdBranch.NONE) {
                    Text(stringResource(tdBranchRes(view.role, view.branch).first), color = skin.accent, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            }
            TdPillButton(stringResource(R.string.td_close_panel), skin, onClose)
        }
        if (view.role != TdRole.SUPPORT) {
            Text(stringResource(R.string.td_target_label), color = skin.panelText.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
            TdChipRow(
                labels = TdTarget.entries.map { stringResource(tdTargetRes(it)) },
                selectedIndex = TdTarget.entries.indexOf(view.target), skin = skin,
                onSelect = { onTarget(TdTarget.entries[it]) }, modifier = Modifier.fillMaxWidth()
            )
        }
        if (view.level >= TdRules.MAX_LEVEL) {
            Text(stringResource(R.string.td_max_level), color = skin.panelText, style = MaterialTheme.typography.bodySmall)
        } else if (view.needsBranch) {
            Text(stringResource(R.string.td_choose_path), color = skin.panelText, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (b in listOf(TdBranch.A, TdBranch.B)) {
                    val (n, d) = tdBranchRes(view.role, b)
                    val nm = stringResource(n)
                    val ds = stringResource(d)
                    TdPillButton(
                        text = "$nm\n${view.upgradeCost}", skin = skin, onClick = { onUpgrade(b) },
                        enabled = gold >= view.upgradeCost, primary = true, modifier = Modifier.weight(1f),
                        description = stringResource(R.string.td_branch_button, nm, view.upgradeCost, ds)
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (view.level < TdRules.MAX_LEVEL && !view.needsBranch) {
                TdPillButton(
                    stringResource(R.string.td_upgrade, view.upgradeCost), skin, { onUpgrade(TdBranch.NONE) },
                    enabled = gold >= view.upgradeCost, primary = true, modifier = Modifier.weight(1f)
                )
            }
            TdPillButton(stringResource(R.string.td_sell, view.sellValue), skin, onSell, modifier = Modifier.weight(1f))
            TdPillButton(
                if (moving) stringResource(R.string.td_cancel) else if (view.moveFee == 0) stringResource(R.string.td_move_free) else stringResource(R.string.td_move_fee, view.moveFee),
                skin, onMove, modifier = Modifier.weight(1f)
            )
        }
    }
}

/** The next-wave card: the archetypes coming, each with its own silhouette; flyers get a warning. */
@Composable
internal fun TdNextWaveCard(skin: TdSkin, preview: List<Pair<TdEnemyKind, Int>>, boss: Boolean, colorblind: Boolean, modifier: Modifier = Modifier) {
    if (preview.isEmpty()) return
    val title = if (boss) stringResource(R.string.td_boss_wave) else stringResource(R.string.td_next_wave_card)
    val parts = preview.map { (k, n) -> stringResource(R.string.td_enemy_count, n, stringResource(skin.enemyNames.getValue(k))) }
    val spoken = title + ": " + parts.joinToString(", ")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(skin.panel.copy(alpha = 0.9f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = if (boss) skin.accent else skin.panelText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        preview.forEachIndexed { i, (kind, _) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    drawEnemyShape(skin, kind, Offset(size.width / 2f, size.height / 2f), size.width * 0.9f, colorblind, 0f, TdMotion.OFF)
                }
                Spacer(Modifier.width(3.dp))
                Text(parts[i], color = skin.panelText, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        if (preview.any { it.first == TdEnemyKind.FLYER }) {
            Text(stringResource(R.string.td_flyers_warning), color = skin.panelText.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** The whole scrim is one big "Resume" button, so the resume target is as large as the board. */
@Composable
internal fun TdPausedScrim(onResume: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xCC120E1C))
            .clickable(onClickLabel = stringResource(R.string.td_resume), role = Role.Button, onClick = onResume)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.td_paused_title), color = Color(0xFFF5F0E6), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.td_tap_resume), color = Color(0xFFF5F0E6).copy(alpha = 0.9f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The finished-run card over the board. Fades in unless reduced motion; kid-first themes get the friendlier defeat line. */
@Composable
internal fun TdResultOverlay(
    skin: TdSkin, won: Boolean, waveReached: Int, totalWaves: Int, castle: Int, castleMax: Int, bestWave: Int?, isNewBest: Boolean,
    reducedMotion: Boolean, onPlayAgain: () -> Unit, onBackToMenu: () -> Unit, modifier: Modifier = Modifier,
    /** Endless shows the wave reached as the score; Daily shows its score ([bestWave] is then the best score). */
    mode: TdMode = TdMode.CAMPAIGN, dailyScore: Int = 0
) {
    val fade = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) { if (fade.value < 1f) fade.animateTo(1f, animationSpec = tween(durationMillis = 250)) }
    Box(
        modifier = modifier.graphicsLayer { alpha = fade.value }.background(Color(0xCC120E1C)).padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(skin.panel).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        won -> stringResource(R.string.td_victory)
                        mode == TdMode.ENDLESS -> stringResource(R.string.td_endless_over)
                        skin.kidFirst -> stringResource(R.string.td_defeat_kid)
                        else -> stringResource(R.string.td_defeat)
                    },
                    style = MaterialTheme.typography.headlineSmall, color = skin.panelText, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (mode == TdMode.ENDLESS) stringResource(R.string.td_endless_reached, waveReached) else stringResource(R.string.td_reached, waveReached, totalWaves),
                    color = skin.panelText
                )
                if (mode == TdMode.DAILY) Text(stringResource(R.string.td_daily_score, dailyScore), color = skin.panelText, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.td_castle_left, castle, castleMax), color = skin.panelText.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                if (isNewBest) Text(stringResource(R.string.td_new_best), color = skin.accent, fontWeight = FontWeight.Bold)
                else if (bestWave != null) Text(
                    if (mode == TdMode.DAILY) stringResource(R.string.td_best_score, bestWave) else stringResource(R.string.td_best, bestWave),
                    color = skin.panelText.copy(alpha = 0.75f)
                )
            }
            Spacer(Modifier.height(16.dp))
            TdPillButton(stringResource(R.string.td_play_again), skin, onPlayAgain, primary = true)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBackToMenu, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.panelText),
                border = BorderStroke(1.dp, skin.panelText.copy(alpha = 0.4f))
            ) { Text(stringResource(R.string.td_back_menu)) }
        }
    }
}

/** Long-press radial menu: five 64dp spokes (tap one). [centre] is in dp inside the board. */
@Composable
internal fun TdRadialMenu(
    skin: TdSkin, centreX: Dp, centreY: Dp, boardW: Dp, boardH: Dp, canUpgrade: Boolean,
    onUpgrade: () -> Unit, onInfo: () -> Unit, onTarget: () -> Unit, onMove: () -> Unit, onSell: () -> Unit, onDismiss: () -> Unit
) {
    // A transparent scrim closes the menu; spokes sit on a ring around the tower.
    Box(Modifier.size(boardW, boardH).clickable(onClickLabel = stringResource(R.string.td_close_panel), onClick = onDismiss))
    val spokes = listOf(
        Triple(stringResource(R.string.td_action_upgrade), canUpgrade, onUpgrade),
        Triple(stringResource(R.string.td_info), true, onInfo),
        Triple(stringResource(R.string.td_target_cycle), true, onTarget),
        Triple(stringResource(R.string.td_action_move), true, onMove),
        Triple(stringResource(R.string.td_action_sell), true, onSell)
    )
    val radius = 84.dp
    spokes.forEachIndexed { i, (label, enabled, action) ->
        val angle = Math.toRadians(-90.0 + i * 72.0)
        val sx = (centreX + radius * Math.cos(angle).toFloat()).coerceIn(34.dp, (boardW - 34.dp).coerceAtLeast(34.dp))
        val sy = (centreY + radius * Math.sin(angle).toFloat()).coerceIn(34.dp, (boardH - 34.dp).coerceAtLeast(34.dp))
        Box(
            modifier = Modifier
                .offset(sx - 32.dp, sy - 32.dp)
                .size(64.dp)
                .clip(CircleShape)
                .background(if (enabled) skin.panel else skin.panel.copy(alpha = 0.5f))
                .border(2.dp, if (enabled) skin.accent else skin.panelText.copy(alpha = 0.3f), CircleShape)
                .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = action)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = skin.panelText, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** Map, theme, difficulty and accessibility toggles. Restarting choices go through [onMap] / [onTier] (the caller confirms). */
@Composable
internal fun TdSetupDialog(
    skin: TdSkin, mapId: String, themeId: TdThemeId, tierIndex: Int, themeLocked: Boolean,
    oneSwitch: Boolean, showRoute: Boolean,
    onMap: (String) -> Unit, onTheme: (TdThemeId) -> Unit, onTier: (Int) -> Unit,
    onOneSwitch: (Boolean) -> Unit, onShowRoute: (Boolean) -> Unit, onDismiss: () -> Unit,
    freePlacement: Boolean = false, onFreePlacement: (Boolean) -> Unit = {}, modeLocked: Boolean = false
) {
    val openIds = listOf("castle-pass", "river-bend", "twin-gates")
    val classicIds = listOf("switchback", "spiral", "gauntlet")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.td_setup_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (modeLocked) {
                    Text(stringResource(R.string.td_mode_locked_note), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.td_group_open), style = MaterialTheme.typography.labelLarge)
                    TdChipRow(openIds.map { stringResource(tdMapNameRes(it)) }, openIds.indexOf(mapId), skin, { onMap(openIds[it]) }, Modifier.fillMaxWidth())
                    Text(stringResource(R.string.td_group_classic), style = MaterialTheme.typography.labelLarge)
                    TdChipRow(classicIds.map { stringResource(tdMapNameRes(it)) }, classicIds.indexOf(mapId), skin, { onMap(classicIds[it]) }, Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.td_theme_label), style = MaterialTheme.typography.labelLarge)
                // Five skins: two rows of chips so the names stay readable.
                val skins = TdSkins.ALL
                val selectedSkin = skins.indexOfFirst { it.id == themeId }
                TdChipRow(skins.take(3).map { stringResource(it.nameRes) }, if (selectedSkin in 0..2) selectedSkin else -1, skin, { onTheme(skins[it].id) }, Modifier.fillMaxWidth(), enabled = !themeLocked)
                TdChipRow(skins.drop(3).map { stringResource(it.nameRes) }, if (selectedSkin >= 3) selectedSkin - 3 else -1, skin, { onTheme(skins[it + 3].id) }, Modifier.fillMaxWidth(0.66f), enabled = !themeLocked)
                if (themeLocked) Text(stringResource(R.string.td_theme_locked_wave), style = MaterialTheme.typography.bodySmall)
                if (!modeLocked) {
                    Text(stringResource(R.string.td_tier_label), style = MaterialTheme.typography.labelLarge)
                    TdChipRow(
                        listOf(R.string.td_tier_gentle, R.string.td_tier_easy, R.string.td_tier_medium, R.string.td_tier_hard).map { stringResource(it) },
                        tierIndex, skin, onTier, Modifier.fillMaxWidth()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.td_snap_off))
                        Text(stringResource(R.string.td_snap_off_help), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = freePlacement, onCheckedChange = onFreePlacement)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.td_show_route))
                    }
                    Switch(checked = showRoute, onCheckedChange = onShowRoute)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.td_one_switch))
                        Text(stringResource(R.string.td_one_switch_help), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = oneSwitch, onCheckedChange = onOneSwitch)
                }
                Text(stringResource(R.string.td_key_help), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.td_done)) } }
    )
}

/**
 * The portrait map overview: the whole field in a strip, the visible part outlined. Tap or drag to move
 * the view ([onScrollTo] gets a scroll offset in px). Reads the game state and the scroll position only
 * inside its draw lambda, so it never recomposes per frame.
 */
@Composable
internal fun TdMinimap(
    skin: TdSkin, game: TowerDefenceGame, terrain: ImageBitmap?, scroll: ScrollState, viewportWpx: Float, zoom: Float,
    onScrollTo: (Float) -> Unit, modifier: Modifier = Modifier
) {
    val desc = stringResource(R.string.td_minimap_desc)
    val jump = rememberUpdatedState(onScrollTo)
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    fun tapTo(x: Float) {
        if (sizePx.width > 0) jump.value(TdLayoutMath.scrollForMinimapTap(x / sizePx.width, viewportWpx, zoom))
    }
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .onGloballyPositioned { sizePx = it.size }
            .semantics { contentDescription = desc }
            .pointerInput(viewportWpx, zoom) { detectTapGestures { p -> tapTo(p.x) } }
            .pointerInput(viewportWpx, zoom) { detectDragGestures { change, _ -> change.consume(); tapTo(change.position.x) } }
    ) {
        drawRect(skin.panel.copy(alpha = 0.92f), size = size)
        if (terrain != null) drawImage(terrain, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        val s = game.state.value
        val ux = size.width / TdGrid.TILES_X
        val uy = size.height / TdGrid.TILES_Y
        for (t in s.towers) {
            val c = TdGrid.towerCentre(t.cx, t.cy)
            drawRect(Color.White, Offset(c.x * ux - 2.5f, c.y * uy - 2.5f), androidx.compose.ui.geometry.Size(5f, 5f))
        }
        for (e in s.enemies) drawCircle(Color(0xFFFF6B6B), 2.4f, Offset(e.pos.x * ux, e.pos.y * uy))
        val left = TdLayoutMath.viewportLeftFraction(scroll.value.toFloat(), viewportWpx, zoom) * size.width
        val w = TdLayoutMath.visibleFraction(zoom) * size.width
        drawRect(skin.accent, Offset(left, 1f), androidx.compose.ui.geometry.Size(w, size.height - 2f), style = Stroke(width = 3f))
    }
}

/** The castle chip pinned to the right edge of a scrolled map while the castle is off screen; tap to show it. */
@Composable
internal fun TdCastleChip(skin: TdSkin, castle: Int, castleMax: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val desc = stringResource(R.string.td_castle_pinned_desc, castle, castleMax)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
            .background(skin.panel.copy(alpha = 0.92f))
            .clickable(onClickLabel = desc, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(R.string.td_castle_pinned, castle, castleMax), color = skin.panelText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
