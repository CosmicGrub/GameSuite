# 09 - Static accessibility audit of the newest games' screens

Scope: Compose screens that exist for the newest games on `session/wave-3` (2026-11-28): `GemCascadeScreen`,
`BotBastionScreen` (+ art), and a read of `TowerDefenceScreen`. Gem Cascade and Bot Bastion are the only new games
with a screen so far. Sweet Trail, Trail Together, Flick Rink, Pie Detective, Stack Zoo, Find It Frenzy, Egg Garden
are logic-only (no `*Screen.kt` yet); audit them when their screens land.

Method: static read and grep only. Nothing was compiled, run, or checked with TalkBack or a device. Everything here is
**unverified** until the local gate.

## Findings

| # | Sev | Where | Finding | Status |
|---|---|---|---|---|
| 1 | High | `BotBastionScreen` node overlay | Tap box was `min(48, width/16*1.15)` dp, about 26 dp on a 360 dp phone, so the map-node buttons (the main way to place a bot) were under 48 dp. | **Fixed**: `bbNodeTapBoxDp` never goes below 48 dp (`BbTapTarget.kt`), with a JVM test. Adjacent nodes' boxes may now overlap slightly on narrow screens; later nodes win overlaps. Check on a device. |
| 2 | Med | `GemCascadeScreen` board cells | Cell size is `fit.cellPx`; at 9-10 columns on a phone cells are under 48 dp (about 36 dp). Cell semantics exist (role=Button, description), and a 64 dp "scan select" button gives a switch/TalkBack route. | Not changed (layout rule; needs a design call: cap board size or rely on scan-select). |
| 3 | Low | `GemCascadeScreen` ~L644 | Status line `heightIn(min = 24.dp)` is a text region (live region), not a control. | OK, no change. |
| 4 | Low | `BotBastionScreen` result stars | `"*".repeat(n) + "-".repeat(..)` is drawn as text, but has a localized `contentDescription`. | OK. |
| 5 | Low | `BotBastionScreen` buy rows | `"$name  -  $price"` is a visible string built in code; `$name` is localized, the separator is not language-bearing. Spoken text uses `bot_bastion_buy_desc`. | OK, but a number-format string would be tidier. |
| 6 | Info | Both screens | Reduced motion: Gem Cascade reads `LocalReducedMotion` (animation durations 0, no shrink on fade, float text 1 ms). Bot Bastion passes `reduced` to core flash and `drawBbFx`. | OK by reading; **not verified** that every ambient effect (backdrops, star twinkle) honors it. |
| 7 | Info | Both screens | Hard-coded English: no `Text("literal")` or `contentDescription = "literal"` found in either screen. Strings go through `stringResource`. | OK. |
| 8 | Info | Both screens | Live regions: Gem Cascade polite status, assertive "why not" and result; Bot Bastion arena summary (polite), message (polite), result dialog (assertive). | OK. |

## Contrast (computed, WCAG 2.x relative luminance)

Against `BbPalette.space` (#0B1026): danger text 6.3:1; node marker 7.7:1; selected node 14.4:1.
Black label on the bot buy buttons (bot colour at 85% over space): Picket 8.1, Lobber 7.6, Haze 10.6, Skiff 4.25, Tender 5.8.
**Skiff (4.25:1) is just under the 4.5:1 AA bar for normal text** (the label is 11-14 sp). I did not change the art
palette (design call). The test `blackLabelOnBuyButtonsStaysReadable` uses a 4.0 floor so it passes; tighten it to 4.5 once the
owner lightens Skiff (for example towards #FF4DA0) or changes the button treatment. **Unverified until a build runs.**
`laneEdge` on `lane` is 2.2:1; lanes are decorative (non-interactive), so not a violation.

Gem Cascade tile colours are not in this audit's reach (drawn from `GemCascadeArt`); the board uses shapes plus colour,
see the colour-blind notes in its integration-notes.

## Open
- Decide the Skiff colour (finding above), then raise the test floor to 4.5. Gem Cascade 9-10 column cell size (finding 2).
- Re-audit Sweet Trail, Trail Together, Flick Rink, Pie Detective, Stack Zoo, Find It Frenzy, Egg Garden, Bump and Run when screens exist.
- TalkBack walkthrough on a device (owner-gated).
