package com.gamesuite.ui.profiles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamesuite.R
import com.gamesuite.core.Avatar
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.gameGlyph
import com.gamesuite.profiles.Profile
import com.gamesuite.ui.club.AvatarBadge
import com.gamesuite.ui.hubText
import com.gamesuite.ui.openGameFromCard
import kotlinx.coroutines.launch

/**
 * The kid's home: big icon-first tiles for the games this profile may see, a time-left ring, a sticker count
 * (placeholder until the sticker system exists) and a lock that opens the parent gate only after a 1.5 s hold.
 * [timeLeftFraction] is 0..1 of today's budget left, [timeLeftMinutes] null = no daily limit.
 */
@Composable
fun KidsHomeScreen(
    profile: Profile,
    specs: List<GameHubSpec>,
    sessionManager: GameSessionManager,
    timeLeftMinutes: Int?,
    timeLeftFraction: Float,
    stickerCount: Int = 0,
    onNavigate: (String) -> Unit,
    onOpenParent: () -> Unit
) {
    KidsTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AvatarBadge(Avatar.decode(profile.avatar), size = 64.dp, contentDescription = "")
                    Text(
                        stringResource(R.string.prof_kids_hi, profile.name),
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), maxLines = 1
                    )
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface) {
                        Text(
                            "⭐ " + stringResource(R.string.prof_kids_stickers, stickerCount),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    TimeRing(timeLeftMinutes, timeLeftFraction)
                    HoldToOpenButton(onOpenParent)
                }
                if (specs.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.prof_kids_empty), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(specs, key = { it.gameId }) { spec ->
                            val title = hubText(spec.title)
                            Surface(
                                onClick = { openGameFromCard(spec, sessionManager, onNavigate) },
                                shape = RoundedCornerShape(28.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shadowElevation = 4.dp,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp)
                                    .semantics { contentDescription = title; role = Role.Button }
                            ) {
                                Column(
                                    Modifier.padding(12.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(gameGlyph(spec.gameId), fontSize = 56.sp)
                                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeRing(minutes: Int?, fraction: Float) {
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val arc = MaterialTheme.colorScheme.secondary
    val label = if (minutes == null) stringResource(R.string.prof_kids_play) else stringResource(R.string.prof_kids_time_left, minutes)
    Box(Modifier.size(64.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            val inset = 4.dp.toPx()
            val s = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = s, style = stroke)
            drawArc(arc, -90f, 360f * (if (minutes == null) 1f else fraction.coerceIn(0f, 1f)), false,
                topLeft = Offset(inset, inset), size = s, style = stroke)
        }
        Text(if (minutes == null) "∞" else minutes.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** A 56dp lock that fills while pressed and fires [onOpen] only after [HOLD_MILLIS] of continuous press. */
@Composable
private fun HoldToOpenButton(onOpen: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOpen by rememberUpdatedState(onOpen)
    val label = stringResource(R.string.prof_kids_lock)
    val holding = stringResource(R.string.prof_kids_lock_holding)
    var isHolding by remember { mutableStateOf(false) }
    Box(
        Modifier.size(56.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .semantics {
                contentDescription = if (isHolding) holding else label
                role = Role.Button
                // TalkBack users cannot do a timed hold: the maths gate behind this is the real protection.
                onClick(label) { currentOpen(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isHolding = true
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(HOLD_MILLIS, easing = LinearEasing))
                        isHolding = false
                        currentOpen()
                    }
                    tryAwaitRelease()
                    job.cancel()
                    isHolding = false
                    scope.launch { progress.snapTo(0f) }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        val ring = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxSize()) {
            drawArc(ring, -90f, 360f * progress.value, false, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
        }
        Text("🔒", fontSize = 22.sp)
    }
}

const val HOLD_MILLIS = 1500
