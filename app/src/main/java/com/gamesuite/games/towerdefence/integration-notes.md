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

## Follow-up pass (branch session/ws-td2) - done vs open

Done (engine and logic are JVM tested; UI is compile-checked only, nothing was run on a device):

1. Tips and coach copy now describe Castle Siege. Shared files touched, tower-defence entries only: `ui/GameTips.kt`
   (3 tips), `res/values{,-es}/polish_strings.xml` (`tip_tower_defence_1..3`), `res/values{,-es}/coach_strings.xml`
   (`coach_tower_defence_s1..s4`). `GameTipsTest` literal equality holds.
2. Resume: `TowerDefenceGame` is a `ResumableGame` (`snapshot()` / `restore()`); hub spec `resumable = true`; the screen calls
   `sessionManager.tryResume(game)` before `startMatch()`. Between waves it saves the build phase; while a wave runs it saves
   the checkpoint taken when that wave began (a resume replays the wave from its start with the towers and gold it began
   with). Classic maps, finished runs and non-single-player launches save nothing. `TdSnapshot` validation also checks mode
   and Daily day (Daily saves are bound to that day's map and Medium tier).
3. Snap off: "Free placement" switch in the setup dialog (`freePlacement`, per screen, saved across rotation only).
4. Drag a tower card from the tray onto the field (ghost lifted 56dp above the finger, release builds if valid, release
   outside the board cancels). Pure mapping in `TdBoardMath.anchorForDrag`.
5. Burn is now a ground fire patch (`TdFirePatch`, `TdFire`): Blast branch B shells leave an area DoT (8/s, ground units only,
   patches never stack, merge within half a tile, at most 24). The per-enemy burn timer is gone.
6. Keyboard / D-pad: the board is focusable; arrows move a cursor (a tile when snapped, a cell when free), Enter/Space/DPAD
   centre builds (twice: ghost, then commit) or selects; 1-4 pick a tower, U upgrade (I = branch B), S sell, M move, T target,
   P pause, N next wave, Esc cancel. Menus use normal focus traversal. The board takes initial focus only when a hardware
   keyboard or D-pad is reported.
7. Layouts (`TdLayoutMath`, tested): SPLIT_LANDSCAPE (board beside a control column), PHONE_PORTRAIT (zoomed 1.7x map, castle
   side shown first, a pinned castle chip when the castle is off screen, a minimap to move the view; the map does NOT scroll
   by touch, to keep drag-to-place unambiguous), STACKED (the old layout) for everything else.
8. Themes: Roman Frontier and Cherry Ridge (skins only; Cherry adds a falling-petal ambient). Names are in
   `tower_defence_more_strings.xml`.
9. Modes (hub option `mode`): Endless (waves past 10 repeat, bigger; ends only when the castle falls; score = wave reached,
   key `<map>:ENDLESS`) and Daily siege (`DailySeed` UTC day picks map, theme and one twist: more enemies, tougher, faster or
   less starting gold; fixed Medium tier; score = wave*100 + castle left, key `daily-<day>:DAILY`, keys older than 30 days
   are pruned). Local bests only.
10. Calm / Standard ambient only; Lively is not built.

Still open / not verified:
- No device run: drag, focus, scrolling, minimap and key handling are untested on hardware; Fold posture layouts beyond the
  three window-shape rules above are not tuned (book posture untested).
- Mid-wave leave resumes at the wave start, not mid-wave (by design, see 2).
- Daily has no leaderboard or share; Sandbox, Co-op, Toy Room mode, Sunstone Oasis, Frostfjord, Coral Reef still documented only.
- The portrait map has no auto-scroll while dragging a ghost near an edge; use the minimap.
