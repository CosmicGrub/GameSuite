package com.gamesuite.games.cards

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamesuite.settings.LocalReducedMotion
import kotlin.math.absoluteValue

/**
 * Renders a hand of cards as a natural overlapping fan (like holding real
 * cards) and supports drag-to-play: drag a card upward past [playThreshold]
 * and release to play it; release early and it snaps back. Falls back to a
 * plain tap (short drag / no drag) also invoking onPlay, so it stays usable
 * without a deliberate drag.
 *
 * enabled=false (not this player's turn) disables both drag and tap.
 *
 * [descriptionOf], when supplied, gives each rendered card a screen-reader
 * contentDescription (e.g. "Red Seven") at the render call site — optional and
 * defaulting to null so existing/future callers that don't supply one keep
 * today's no-semantics behavior.
 */
@Composable
fun <T> FannedHand(
    items: List<T>,
    idOf: (T) -> Int,
    visualOf: (T) -> CardVisual,
    enabled: Boolean,
    onPlay: (T) -> Unit,
    cardWidth: Dp = 64.dp,
    cardHeight: Dp = 92.dp,
    playThreshold: Dp = 60.dp,
    modifier: Modifier = Modifier,
    // Per-card screen-reader identity (e.g. "Red Seven", "Wild Draw Four") — this
    // whole app had zero screen-reader semantics before this pass, so this stays
    // an optional trailing parameter defaulting to no description, meaning no
    // contentDescription is added and today's silent behavior is unchanged for
    // any caller that doesn't supply one.
    descriptionOf: ((T) -> String)? = null
) {
    val haptics = LocalHapticFeedback.current
    // Settings -> Accessibility -> Reduced Motion (see settings/LocalReducedMotion.kt) — the
    // audited finding that this toggle was stored but consumed nowhere in the app.
    val reducedMotion = LocalReducedMotion.current
    // Scaled here, once, before any of the size-dependent math below — see
    // CardScale.kt's KDoc for why this must not also happen inside
    // PlayingCardView itself (this component's own overlap/fan-width/offset
    // calculations are derived FROM cardWidth/cardHeight, so they need the
    // real, already-scaled card size to stay visually consistent with what
    // actually gets drawn).
    val scale = LocalCardScale.current
    val scaledCardWidth = cardWidth * scale
    val scaledCardHeight = cardHeight * scale
    val overlap = scaledCardWidth * 0.55f
    val fanWidth = if (items.isEmpty()) scaledCardWidth else scaledCardWidth + overlap * (items.size - 1)

    // The fan's natural width grows with hand size and can exceed the
    // screen (e.g. a stacked UNO hand after Draw Two/Draw Four chains, or
    // any hand on a narrow foldable cover screen). Scope the fan to its
    // intrinsic width inside a horizontally scrolling viewport so every
    // card — including ones pushed past the visible edge — stays reachable
    // regardless of hand size or screen width.
    Box(
        modifier = modifier
            .height(scaledCardHeight + 24.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        // A Box does not size itself to offset-positioned children (offset
        // doesn't contribute to measured size), so the fan needs an inner
        // Box explicitly sized to fanWidth — otherwise the scroll container
        // above would see only a single card's width of content and there
        // would be nothing to scroll.
        Box(
            modifier = Modifier
                .height(scaledCardHeight + 24.dp)
                .width(fanWidth)
        ) {
            items.forEachIndexed { index, item ->
                val centerOffset = index - (items.size - 1) / 2f
                val rotationDeg = centerOffset * 4f
                val liftForArc = -(centerOffset.absoluteValue) * 3f

                var dragOffsetY by remember(idOf(item)) { mutableFloatStateOf(0f) }
                var isDragging by remember(idOf(item)) { mutableStateOf(false) }
                var pastThreshold by remember(idOf(item)) { mutableStateOf(false) }

                val animatedOffsetY by animateFloatAsState(
                    targetValue = if (isDragging) dragOffsetY else 0f,
                    animationSpec = if (reducedMotion) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    label = "cardLift"
                )

                Box(
                    modifier = Modifier
                        .offset(x = overlap * index, y = liftForArc.dp + animatedOffsetY.dp)
                        .pointerInput(enabled, idOf(item)) {
                            if (!enabled) return@pointerInput
                            detectDragGestures(
                                onDragStart = { isDragging = true },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetY = (dragOffsetY + dragAmount.y / density).coerceAtMost(0f)
                                    val nowPast = -dragOffsetY > playThreshold.value
                                    if (nowPast && !pastThreshold) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    pastThreshold = nowPast
                                },
                                onDragEnd = {
                                    isDragging = false
                                    if (pastThreshold) {
                                        onPlay(item)
                                    }
                                    dragOffsetY = 0f
                                    pastThreshold = false
                                },
                                onDragCancel = {
                                    isDragging = false
                                    dragOffsetY = 0f
                                    pastThreshold = false
                                }
                            )
                        }
                ) {
                    RotatedCard(
                        visual = visualOf(item),
                        rotationDeg = rotationDeg,
                        width = scaledCardWidth,
                        height = scaledCardHeight,
                        onTap = if (enabled) { { onPlay(item) } } else null,
                        description = descriptionOf?.invoke(item)
                    )
                }
            }
        }
    }
}

@Composable
private fun RotatedCard(
    visual: CardVisual,
    rotationDeg: Float,
    width: Dp,
    height: Dp,
    onTap: (() -> Unit)?,
    description: String? = null
) {
    Box(
        modifier = Modifier
            .then(
                // Wraps the same tappable element onTap is attached to below, so a
                // TalkBack user gets one spoken identity per card ("Red Seven") —
                // PlayingCardView itself draws no semantics of its own to collide with.
                if (description != null) Modifier.semantics { contentDescription = description } else Modifier
            )
            .then(
                if (onTap != null) Modifier.pointerInput(visual.id) {
                    detectTapGestures(onTap = { onTap() })
                } else Modifier
            )
    ) {
        Box(modifier = Modifier.rotate(rotationDeg)) {
            PlayingCardView(card = visual, width = width, height = height)
        }
    }
}
