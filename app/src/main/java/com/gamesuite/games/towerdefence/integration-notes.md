# Tower Defence "Castle Siege" redesign: what the coordinator must integrate

Branch `session/ws-td` (from `session/wave-3` at 4545ef5). Design: `docs/design/tower-defence/`. Nothing was installed on
a device and no emulator was used (standing rule); everything below was verified by JVM unit tests and compilation only.

## What exists

Pure engine (`com.gamesuite.games.towerdefence`, JVM tested, no Compose beyond `Offset`):

| File | Content |
| --- | --- |
| `TdNav.kt` | 48 x 28 nav grid, `TdTerrain`, Dijkstra flow field (8 neighbours, no corner cutting, deterministic), `TdPlacement.validate` (UNBUILDABLE, OVERLAP, NO_GOLD, BLOCKS_ROUTE, TOO_LONG with the 2.5 x route cap), terrain builder |
| `TdMaps.kt` | three original open maps: `castle-pass`, `river-bend`, `twin-gates` (attack from the left, castle on the right) |
| `TdRules.kt` | 4 roles (Bolt, Blast, Control, Support), branch at level 3, stats and costs from the codex board, 4 archetypes (Runner, Brute, Flyer, Boss), 10-wave table with bosses on waves 5 and 10, Gentle tier numbers |
| `TdSiege.kt` | the open-field tick: spawn, flow-field movement, castle damage, auras, targeting (First, Last, Strong, Close), splash, slow, stun, burn, gold, win/loss |
| `TdSnapshot.kt` | strict save format for the build phase of an open map (validated on decode) |
| `TdBoardMath.kt` | pointer to anchor, tower hit test, 14 x 8 TalkBack grid, one-switch scan state machine |
| `TowerDefenceGame.kt` | extended, not replaced: Classic maps run the old code path unchanged; open maps add `placeTower(cx, cy, role)`, `previewPlacement`, `upgradeTower(id, branch)`, `sellTower`, `moveTower`, `setTargeting`, `callNextWave`, `routePreview`, `nextWavePreview`, `exportSnapshot` / `restoreSnapshot`, `gentle` |
| `TowerDefenceStatsStore.kt` | same keys `"$mapId:DIFFICULTY"`; `TdStatsKeys` adds `"$mapId:GENTLE"`; stored blobs are sanitised on read (bad entries dropped, valid old records kept) |
| `TowerDefenceHubSpecs.kt` | hub spec with options `map`, `theme`, `gentle` |

UI (`com.gamesuite.ui`): `TowerDefenceScreen.kt` (rewritten), `TdScreenParts.kt`, `TdBoardDraw.kt`, `TdArt.kt` (original vector
art), `TdTheme.kt` (three skins), `TdAmbient.kt` (living world). Strings: `res/values{,-es}/tower_defence_strings.xml` and
`tower_defence_hub_strings.xml` (new files only).

## The one edit to a shared file (already made)

`core/GameHubSpecs.kt`, in `baseSpecs()`: the plain entry
`solo("tower-defence", "Tower Defence", "Build towers and stop the waves.", HubFamily.ARCADE_PUZZLE, resumable = false)`
was replaced by `com.gamesuite.games.towerdefence.TowerDefenceHubSpecs.towerDefence`. Same game id, same mode id
(`solo`), same route (`tower-defence`), same title and tagline, so existing hub preferences keep working. It adds three
options (`map`, `theme`, `gentle`); their texts are in `tower_defence_hub_strings.xml` under the `hubKey` names. Nothing
else shared was touched (GameCatalog, GameTheme, MainActivity, GameTips, GameCoach are unchanged; the existing
`composable("tower-defence")` route in MainActivity already constructs `TowerDefenceGame()` and calls
`TowerDefenceScreen(sessionManager, game, settingsViewModel, onMatchEnded)`, whose signature did not change).

## Optional follow-ups for the coordinator (not done, to avoid shared files)

1. Tips: `GameTips.tips["tower-defence"]` and `res/values{,-es}/polish_strings.xml` (`tip_tower_defence_1..2`) still carry the
   old two tips. New tips exist as `td_tip_1..3` in the new string files; to switch, set the three literals in `GameTips`
   to those English texts (no apostrophes) and make `tip_tower_defence_*` equal them in both languages
   (`GameTipsTest` checks literal equality), or point the tips at the `td_tip_*` resources.
2. Coach card text (`coach_tower_defence_s1..s4`) still describes "a path"; update the copy when convenient.
3. Resume: `resumable = false` is kept. `TowerDefenceGame.exportSnapshot()` / `restoreSnapshot()` exist and are tested; making
   Tower Defence a `ResumableGame` (hub Resume) is a small follow-up if wanted.
4. Hub option `difficulty` is not added: difficulty still comes from Settings (`defaultCpuDifficulty`) or a hub CPU override,
   and the in-game setup dialog changes it (with the restart prompt). Gentle is a hub toggle and a chip in the setup dialog.

## Behaviour notes

- Stats keys are unchanged for the three Classic maps (`switchback`, `spiral`, `gauntlet`). Open maps use the same format
  (`castle-pass:MEDIUM`). Gentle files under `<map>:GENTLE`. The theme is never part of a key.
- Castle health replaces lives on open maps (default 20, boss costs 5, Gentle 30); the engine field is still `state.lives`.
- Placement is snapped to tiles (a tower is 2 x 2 nav cells). Free (Snap off) placement is supported by the engine
  (`anchorFor(snap = false)`) but there is no UI toggle yet.
- Gentle is implemented as a Tower Defence only flag layered on any difficulty (not a new `CpuDifficulty` value, which would
  touch every game); it was cheap and safe, so it shipped.
- Motion levels: only Calm and Standard exist. Reduced Motion (OS setting) freezes ambience (the same scenery drawn still).
  Calm is used when `enhancedAnimationsEnabled` is off. Lively is intentionally not offered.
- The per-frame recomposition problem is fixed on this screen: the body reads only derived values; the board and the
  backdrop are single Canvases that read game state, the ambient clock, the ghost and particles inside draw lambdas.
  This was verified by reading the code, not by a profiler run.

## Not built / skipped (honest list)

- Five documented themes (Roman Frontier, Cherry Ridge, Sunstone Oasis, Frostfjord, Coral Reef): documented only.
- Modes beyond Campaign (Endless, Daily, Sandbox, Co-op, Toy Room mode), unlocks by stars, parent kid-variant switch.
- Per-theme synthesised audio palettes and voice prompts: the existing procedural SFX and music profile are reused.
- Lively motion level, quality tiers and battery auto-downgrade, macrobenchmarks, time-of-day and weather overlays.
- Fold posture layouts and phone-portrait redraws (the board simply scales to the available space; Fold book posture is an
  untested posture as everywhere in the app).
- Dragging a card from the tray: the ghost is dragged from the board (press, drag with the ghost 56dp above the finger,
  release), or placed with tap, tap.
- Keyboard / D-pad cursor, and the TalkBack list-view alternative: TalkBack gets the 14 x 8 grid and tower nodes with custom
  actions; one-switch gets the scan path (setup dialog); keyboard navigation is not implemented.
- Burn is a damage-over-time on hit enemies (8 per second for 6 or 9 seconds) rather than a ground fire patch.
- Ambient budgets (0.4 / 1.0 ms) are design targets only: they are not measured. `TdAmbient.MAX_ELEMENTS` and `drawCalls`
  give a tested ceiling on draw commands instead.
- No device run: touch handling, gestures, TalkBack behaviour, rendering quality and frame times are unverified on hardware.
- Balance numbers are the board defaults. A scripted greedy player wins all three maps on Medium in the JVM simulation
  (`aSensiblePlayerSurvivesTheCampaignOnMedium`) and doing nothing loses; that is a smoke check, not playtesting.
