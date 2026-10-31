package com.gamesuite.ui.party

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val PARTY_REVEAL_ACCESSIBLE_MS = 3000L

/**
 * Deals cards to [roster] (an empty roster means "no names set": Player 1..n with a stepper) from a
 * standard 52-card deck (optional jokers) or a numbered custom deck, with no repeats. Only the
 * dealt COUNT per player is visible; a player's cards show while their "Hold to reveal" button is
 * held down, so one shared device works as a randomiser. Hidden cards are never composed, so they
 * are absent from the accessibility tree; assistive tech gets a "reveal for three seconds" action
 * on the same button instead. The deal lives in [PartySession] (survives tab switches), the
 * revealed state does not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyCardDealerTool(roster: List<String>) {
    val palette = partyPalette()
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revealed by remember { mutableStateOf(-1) }

    PartyPanel(palette) {
        val names = partyRosterNames(roster, palette, haptics)
        val n = names.size
        val spec = PartySession.dealSpec
        val dealt = PartySession.dealt?.takeIf { it.hands.size == n }
        val maxPer = PartyDeck.maxPerPlayer(spec.size, n).coerceAtLeast(1)
        val perPlayer = PartySession.dealPerPlayer.coerceIn(1, maxPer)

        PartyHeading(stringResource(R.string.party2_deal_title), palette)
        Spacer(Modifier.height(8.dp))

        // Deck choice
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PartyChip(stringResource(R.string.party2_deal_standard), spec.kind == PartyDeckKind.STANDARD, palette, {
                PartySession.dealSpec = spec.copy(kind = PartyDeckKind.STANDARD); haptics(HapticSignal.LIGHT_TICK)
            })
            PartyChip(stringResource(R.string.party2_deal_custom), spec.kind == PartyDeckKind.CUSTOM, palette, {
                PartySession.dealSpec = spec.copy(kind = PartyDeckKind.CUSTOM); haptics(HapticSignal.LIGHT_TICK)
            })
        }
        Spacer(Modifier.height(8.dp))
        if (spec.kind == PartyDeckKind.STANDARD) {
            LabelledControl(stringResource(R.string.party2_deal_jokers), palette) {
                PartyStepper(
                    value = spec.jokers, range = 0..2, label = stringResource(R.string.party2_deal_jokers),
                    palette = palette, haptics = haptics,
                    onChange = { PartySession.dealSpec = spec.copy(jokers = it) }
                )
            }
        } else {
            LabelledControl(stringResource(R.string.party2_deal_custom_size), palette) {
                PartyStepper(
                    value = spec.customSize, range = PartyDeck.MIN_CUSTOM..PartyDeck.MAX_CUSTOM,
                    label = stringResource(R.string.party2_deal_custom_size), palette = palette, haptics = haptics,
                    onChange = { PartySession.dealSpec = spec.copy(customSize = it) }
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (size in listOf(20, 32, 40, 60, 100)) {
                    PartyChip(size.toString(), spec.customSize == size, palette, {
                        PartySession.dealSpec = spec.copy(customSize = size); haptics(HapticSignal.LIGHT_TICK)
                    })
                }
            }
        }
        LabelledControl(stringResource(R.string.party2_deal_per), palette) {
            PartyStepper(
                value = perPlayer, range = 1..maxPer, label = stringResource(R.string.party2_deal_per),
                palette = palette, haptics = haptics,
                onChange = { PartySession.dealPerPlayer = it }
            )
        }
        Text(
            stringResource(R.string.party2_deal_limit, maxPer),
            color = palette.textPrimary.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PartyButton(palette = palette, onClick = {
                PartySession.dealPerPlayer = perPlayer
                PartySession.dealt = PartyDeck.deal(spec, n, perPlayer, Random.Default)
                revealed = -1
                haptics(HapticSignal.NORMAL_ACTION)
                playSfx(SfxKind.WHOOSH)
            }) {
                Text(stringResource(if (dealt == null) R.string.party2_deal_deal else R.string.party2_deal_reshuffle))
            }
            PartyOutlinedButton(
                palette = palette,
                enabled = dealt != null && dealt.stock.size >= n,
                onClick = {
                    dealt?.let { PartyDeck.drawOneEach(it) }?.let { PartySession.dealt = it }
                    haptics(HapticSignal.LIGHT_TICK)
                    playSfx(SfxKind.LIGHT_TICK)
                }
            ) { Text(stringResource(R.string.party2_deal_draw)) }
            PartyOutlinedButton(
                palette = palette,
                enabled = dealt != null,
                onClick = {
                    PartySession.dealt = null
                    revealed = -1
                    haptics(HapticSignal.LIGHT_TICK)
                }
            ) { Text(stringResource(R.string.party2_deal_collect)) }
        }

        if (dealt != null) {
            val ranks = stringArrayResource(R.array.party2_ranks)
            val suits = stringArrayResource(R.array.party2_suits)
            val ofFmt = stringResource(R.string.party2_card_spoken)
            val jokerWord = stringResource(R.string.party2_joker)
            val stockText = context.resources.getQuantityString(R.plurals.party2_cards, dealt.stock.size, dealt.stock.size)

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.party2_deal_stock, stockText),
                color = palette.accentText,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.party2_deal_hint),
                color = palette.textPrimary.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))

            names.forEachIndexed { i, name ->
                val hand = dealt.hands[i]
                val countText = context.resources.getQuantityString(R.plurals.party2_cards, hand.size, hand.size)
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = PARTY_MIN_TOUCH_DP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        name,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(countText, color = palette.textPrimary.copy(alpha = 0.85f))
                    HoldToReveal(
                        description = stringResource(R.string.party2_deal_hold_desc, name),
                        action = stringResource(R.string.party2_deal_hold_action),
                        label = stringResource(R.string.party2_deal_hold),
                        active = revealed == i,
                        palette = palette,
                        onPress = { revealed = i; haptics(HapticSignal.LIGHT_TICK) },
                        onRelease = { if (revealed == i) revealed = -1 },
                        onAccessibleReveal = {
                            scope.launch {
                                revealed = i
                                delay(PARTY_REVEAL_ACCESSIBLE_MS)
                                if (revealed == i) revealed = -1
                            }
                        }
                    )
                }
                // Hidden cards are not composed at all, so they cannot leak to the accessibility tree.
                if (revealed == i) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (card in hand) {
                            val spoken = when {
                                dealt.spec.kind == PartyDeckKind.CUSTOM -> (card + 1).toString()
                                PartyDeck.isJoker(dealt.spec, card) -> jokerWord
                                else -> String.format(ofFmt, ranks[PartyDeck.rankIndex(card)], suits[PartyDeck.suitIndex(card)])
                            }
                            CardChip(
                                text = PartyDeck.shortLabel(dealt.spec, card),
                                spoken = spoken,
                                red = PartyDeck.isRed(dealt.spec, card),
                                palette = palette
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelledControl(label: String, palette: PartyPalette, control: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(modifier = Modifier.heightIn(min = PARTY_MIN_TOUCH_DP.dp), contentAlignment = Alignment.CenterStart) {
            Text(label, color = palette.textPrimary, fontWeight = FontWeight.Bold)
        }
        control()
    }
}

/**
 * Press-and-hold button: [onPress] on touch down, [onRelease] when the finger lifts or the gesture
 * is cancelled. Screen-reader users get [onAccessibleReveal] as the button's click action instead,
 * since a hold is not possible there.
 */
@Composable
private fun HoldToReveal(
    description: String,
    action: String,
    label: String,
    active: Boolean,
    palette: PartyPalette,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onAccessibleReveal: () -> Unit
) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val reveal by rememberUpdatedState(onAccessibleReveal)
    Box(
        modifier = Modifier
            .heightIn(min = PARTY_MIN_TOUCH_DP.dp)
            .widthIn(min = 112.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (active) palette.accent else palette.chipBackground)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    press()
                    try {
                        tryAwaitRelease()
                    } finally {
                        release()
                    }
                })
            }
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick(label = action) {
                    reveal()
                    true
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) palette.textOnAccent else palette.textPrimary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

/** A dealt card. Hearts and diamonds are also red, but the suit symbol is always shown. */
@Composable
private fun CardChip(text: String, spoken: String, red: Boolean, palette: PartyPalette) {
    Box(
        modifier = Modifier
            .widthIn(min = 44.dp)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.background)
            .border(2.dp, palette.accentText, RoundedCornerShape(8.dp))
            .semantics { contentDescription = spoken }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (red) palette.danger else palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}
