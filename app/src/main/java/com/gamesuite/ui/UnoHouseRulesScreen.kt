package com.gamesuite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.uno.UnoRules

/**
 * Pre-game picker for UNO's house-rule toggles (draw stacking, 7-0, jump-in, and whether
 * playing a drawn card is mandatory) — UnoRules/UnoGame have fully implemented every one of
 * these since the original UNO pass, but until this screen there was no way for a player to
 * ever turn any of them on: MainActivity only ever constructed UnoRules() (classic) or
 * UnoRules(teamPlay = true) (the "uno-teams" route). This is the fix for the audited finding
 * that the whole toggle set was fully-built, dead code from a player's perspective.
 *
 * Scoped to local play (vs CPU bots / pass-and-play) for now — Nearby and Online still launch
 * with classic defaults via their own existing lobby flow, unchanged by this screen; syncing a
 * custom ruleset into those lobbies is a reasonable follow-up, not part of this fix.
 */
@Composable
fun UnoHouseRulesScreen(
    onStart: (rules: UnoRules, players: List<PlayerInfo>) -> Unit,
    onBack: () -> Unit
) {
    var stackDraw by remember { mutableStateOf(false) }
    var stackDrawFourOnDrawTwo by remember { mutableStateOf(false) }
    var sevenZero by remember { mutableStateOf(false) }
    var jumpIn by remember { mutableStateOf(false) }
    var forcePlayDrawnCard by remember { mutableStateOf(false) }
    var vsBot by remember { mutableStateOf(true) }

    // Wide-window fix (Fold/Tab compatibility audit): this is a toggle-list form, same
    // shape as SettingsScreen — a plain fillMaxSize() column would stretch every
    // RuleToggle's SpaceBetween Row edge to edge across a Tab S9 landscape or
    // Fold-unfolded window, leaving the switch stranded far from its own label. Same
    // widthIn(max = 840.dp)-before-fillMaxWidth() centering trick as SettingsScreen/
    // AdaptiveTwoPane caps this screen to a sane reading width instead.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
            }
            Spacer(Modifier.height(8.dp))
            Text("UNO House Rules", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Classic UNO is every toggle off. Turn on whichever house rules your table plays with.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(20.dp))

            RuleToggle(
                label = "Draw stacking",
                description = "Answer a +2 with a +2 (or +4) instead of drawing immediately.",
                checked = stackDraw,
                onCheckedChange = {
                    stackDraw = it
                    if (!it) stackDrawFourOnDrawTwo = false
                }
            )
            if (stackDraw) {
                RuleToggle(
                    label = "Allow +4 on +2",
                    description = "Also lets a +4 answer a stacked +2 (not just matching +2s).",
                    checked = stackDrawFourOnDrawTwo,
                    onCheckedChange = { stackDrawFourOnDrawTwo = it }
                )
            }
            RuleToggle(
                label = "7-0",
                description = "Playing a 7 swaps hands with an opponent; playing a 0 rotates every hand.",
                checked = sevenZero,
                onCheckedChange = { sevenZero = it }
            )
            RuleToggle(
                label = "Jump-in",
                description = "Any player holding an exact match of the top card may play it out of turn.",
                checked = jumpIn,
                onCheckedChange = { jumpIn = it }
            )
            RuleToggle(
                label = "Must play a drawn card",
                description = "Off (official rule): playing a card you just drew is your choice, not mandatory.",
                checked = forcePlayDrawnCard,
                onCheckedChange = { forcePlayDrawnCard = it }
            )

            Spacer(Modifier.height(24.dp))
            Text("Players", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            RuleToggle(
                label = "vs 2 CPU bots",
                description = "Off: 4-player local pass-and-play instead.",
                checked = vsBot,
                onCheckedChange = { vsBot = it }
            )

            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                val rules = UnoRules(
                    stackDraw = stackDraw,
                    stackDrawFourOnDrawTwo = stackDrawFourOnDrawTwo,
                    sevenZero = sevenZero,
                    jumpIn = jumpIn,
                    forcePlayDrawnCard = forcePlayDrawnCard
                )
                val players = if (vsBot) {
                    listOf(
                        PlayerInfo(playerId = "p1", displayName = "You"),
                        PlayerInfo(playerId = "bot1", displayName = "CPU 1", isBot = true),
                        PlayerInfo(playerId = "bot2", displayName = "CPU 2", isBot = true)
                    )
                } else {
                    listOf(
                        PlayerInfo(playerId = "p1", displayName = "Player 1"),
                        PlayerInfo(playerId = "p2", displayName = "Player 2"),
                        PlayerInfo(playerId = "p3", displayName = "Player 3"),
                        PlayerInfo(playerId = "p4", displayName = "Player 4")
                    )
                }
                onStart(rules, players)
            }) {
                Text("Start game")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RuleToggle(label: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
