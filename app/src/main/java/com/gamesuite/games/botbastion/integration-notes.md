# Bot Bastion (`bot-bastion`): what the coordinator must integrate

Nothing here is registered anywhere yet (the shared files were not touched). "Bot Bastion" is a working title:
it needs a trademark and store-name search before public use (design question 1). All names, art, maps and
numbers are original; the design is in `docs/design/tower-defence/zacisa-mode/` (the source game is named only
there as research context; it must never appear in the product).

Local only: one device, 1 to 4 people, not networked. Audience FAMILY, minAge 10 (E10+, cartoon violence; the
Cadet tier is gentle).

Pure engine: `com.gamesuite.games.botbastion` (JVM tested, no Compose in the engine). Game module:
`BotBastionGame` (`GameModule` + `ResumableGame`, validated snapshots). Screen: `com.gamesuite.ui.BotBastionScreen`
(+ `BotBastionArt.kt`), 2D baseline, one Canvas driven by `rememberGameFrameClock`.

## 1. Hub spec (`core/GameHubSpecs.kt`)

Add `BotBastionHubSpecs.botBastion` to `baseSpecs()` in the ARCADE_PUZZLE section. Do NOT add `"bot-bastion"` to
`GameHubSpecs.networkedGames`. It already sets `audience = FAMILY, minAge = 10`; if `GameAudienceTest` insists on a
table row, add `put("bot-bastion", f(10))` in the table's own idiom. One SOLO mode (`play`, route `bot-bastion`) with
options `tier` (cadet, captain, hero, insanity, endless) and `players` (1 to 4). The screen opens its own map picker
(it knows the unlocks); an optional `map` extra skips the picker.

## 2. Glyph (`core/GameCatalog.kt`)

```
"bot-bastion" -> "🛸"   // flying saucer; also BotBastionHubSpecs.GLYPH
```

## 3. Accent colours (`ui/GameTheme.kt`, `GameThemes.accents`)

```
"bot-bastion" to GameAccent(0xFF8A3A00, 0xFFFFB066),
```
(also `BotBastionHubSpecs.ACCENT_LIGHT` / `ACCENT_DARK`; the tests check contrast against every theme surface.
Distinctness from the other accents was not checked: it is a burnt orange; pick another if it is too close.)

## 4. Route (`MainActivity.kt`)

```kotlin
composable("bot-bastion") {
    val g = rememberActiveModule(sessionManager, "bot-bastion") { BotBastionGame() }
    BotBastionScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, onMatchEnded = finishMatch)
}
```
Imports: `com.gamesuite.games.botbastion.BotBastionGame`, `com.gamesuite.ui.BotBastionScreen`.

## 5. Tips (`ui/GameTips.kt`, `GameTips.tips`)

Literals must equal `tip_bot_bastion_1..3` in `res/values/bot_bastion_coach_strings.xml` (no apostrophes):

```kotlin
"bot-bastion" to listOf(
    "Put a Tender near the entry and your guns near the Core.",
    "Lobbers clear crowds but miss ships right beside them.",
    "Keep a Nova Pulse for the boss wave."
),
```

## 6. Coach card (`core/GameCoach.kt`)

Motif: any grid / board motif, for example `"bot-bastion" to CoachMotif.<grid>`. Entry:

```kotlin
entry("bot-bastion", listOf(R.string.coach_bot_bastion_s1, R.string.coach_bot_bastion_s2, R.string.coach_bot_bastion_s3,
    R.string.coach_bot_bastion_s4, R.string.coach_bot_bastion_s5), R.string.coach_bot_bastion_fact),
```
The screen does not open the coach card itself (an unregistered id would fail). After registering, add
`rememberClCoach("bot-bastion", ready = true)` and a `ClCoachButton` as the other screens do.

## 7. Assets and resources

* `app/src/main/assets/bot_bastion/maps.json`: 11 maps (see below), all validated by `MapDataValidationTest`.
  Regenerate with `python -I scripts/gen_bot_bastion_maps.py` (reads `docs/design/tower-defence/zacisa-mode/MAPS.md`).
* Strings: `bot_bastion_strings.xml`, `bot_bastion_hub_strings.xml`, `bot_bastion_coach_strings.xml` in `values` and
  `values-es`. Regenerate with `python -I scripts/gen_bot_bastion_strings.py`. Spanish is a first pass for a native
  speaker to review.
* Persistent data: `BbStore` (DataStore `bot_bastion`, one JSON profile with per-map-and-tier bests and top-10 boards,
  keys `bb/$mapId:$tier`). Local only; the only personal text is three typed initials.
* Saved game: `BotBastionGame.snapshot()` / `restore()` through the existing `ResumableGame` machinery; the screen
  calls `sessionManager.tryResume(game)`.

## 8. What was built and what was not

Built: engine (maps, waves, 5 bots + Aegis, targeting, damage model, Tender slow/freeze, Skiff rim patrol, specials,
scoring, stars, five tiers, boss every 10th wave, swarm events, co-op rules for 1 to 4 players with pilot ships),
validated snapshot, profile/leaderboard store, hub spec, strings, screen.

Screen scope: map picker with stars/locks, tier and crew chips, HUD, node taps (invisible 48 dp buttons with
TalkBack labels), build cards, upgrade panel with before/after values and target chips, Aegis, Skiffs, specials,
instant Pause, 1x/2x/3x speed, call-early, result card with stars, defeat tip, initials entry. Co-op in the screen is
the shared-screen version: player chips choose who is acting, and dragging on the arena steers that player's pilot
ship. NOT built: the tabletop two-bar layout, gamepad input, Scrap gifting button (engine supports it), the first-run
overlay, per-sector music and the richer sound set (the screen reuses the existing `SfxKind` / `HapticSignal` sets),
Lively motion (only Calm and Standard; Reduced Motion removes flashes, bursts and the blast growth).

Maps: 11 of the 12 designed maps ship. `furnace` was left out (its entry is inside the arena and 7 of its 20 nodes are
closer than 0.72 u to the lane). Two data fixes were made in the generator, not in `MAPS.md`: `pebble` lost node 1
(0.24 u from the lane) and `hourglass` lanes 2 and 3 now continue from the merge point to the Core. The three launch
maps are `hearth`, `twin`, `pebble`.

## 9. Open items

* Balance: the simulator (`BalanceSimulationTest`) shows Captain is won with the Core intact by a scripted all-round
  loadout (design target 60 to 75% left) and by spamming Lobbers (target: fails between waves 15 and 22); spamming
  Pickets falls at wave 24 (Hearth) to 27 (Twin) and Skiffs alone at wave 7 to 12. The BALANCE.md numbers were not changed; tune before art lock.
* Nothing was run on a device or emulator; the screen was compiled, not played.
* The Fold book posture and phone portrait were not designed for; phone portrait uses a stacked layout.

## 10. Follow-ups branch session/ws-bb2 (2026-11-22): done vs open

Done: (1) first-run coach: `rememberClCoach("bot-bastion")` + `ClCoachButton` in the menu title and the HUD; the run pauses while the card or a dialog has focus. (2) tabletop co-op layout (`BotBastionLayout.kt`, `TabletopUi`/`PlayerBar` in the screen): used in tabletop posture or on a tablet (min side 600 dp) with 2+ players; far bar (seats 2 and 4) rotated 180 degrees, near bar (seats 1 and 3), shared strip and arena in the middle; every bar buys as its own player. (3) Scrap gift buttons in the panel and bars (`BotBastionGame.gift`, 25 Scrap). (4) four sector backdrops plus ambient layers (`BotBastionSectorArt.kt`; Reduced Motion freezes, `BbAmbientMotion.CALM` exists but nothing selects it yet) and sector thumbnails in the map picker. (5) node touch boxes are clamped inside the arena and never overlap (below 48 dp only on narrow phones, at least 24 dp at 344 dp wide); layout math is unit tested. (6) Resume: `BotBastionGame` is a `ResumableGame`; `BotBastionGameResumeTest` covers snapshot/restore and the shell save, resume and corrupt-save paths. (7) SPEC.md and the ZSWeaponsEnemies board synced to the tuned BALANCE values. (8) `furnace` is back (16 nodes, lane starts on the rim; MAPS.md and the generator updated), so 12 maps ship; strings in `bot_bastion_more_strings.xml` (EN + ES).

Open: nothing was run on a device or emulator, so the tabletop layout, the rotated bar, the ambient art and the 24 dp phone touch boxes are compile- and math-tested only; the middle status strip is not rotated for the far player (the far bar repeats wave, Core and Scrap); a hinge-aware split in tabletop posture is not done; gamepad input, per-sector music and Lively motion are still not built; Calm motion is not wired to a setting.
