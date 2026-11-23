# Tower Defence redesign: scope

Status: DESIGN DRAFT, 2026-11-22. Nothing here is built. No Kotlin was edited, no build was run, nothing was committed.
Reads with: `MAP-MODEL.md`, `THEMES.md`, `LIVING-WORLD.md`, `SCRIPT.md`, `SCALE.md`, `ACCESSIBILITY.md`, `OPEN-QUESTIONS.md`.
Mockups: `docs/design/canvas/TD*.dc.html` (12 boards, manifest `RF-manifest-TD.json`).
Also reads with the style system: `docs/design/style-packs/` and `docs/design/gem-cascade/GAME-IN-STYLE-SYSTEM.md`.

## 1. Owner request (verbatim intent)

"For the tower defense games, I want a more fluid and more defined map design with more open placement as I choose. I also want different modes/themes: a designed mode where an attack comes from the LEFT to the RIGHT where I am, and I am defending what looks like a castle; another a space theme, a roman theme, a medieval theme, etc. I want HIGH QUALITY markups, since the aesthetic will be mostly static aside from the parts I control, though you may also add non-static features so the environment feels living and breathing."

## 2. What exists today (read from source, 2026-11-22)

`games/towerdefence/TowerDefenceGame.kt` (622 lines) and `ui/TowerDefenceScreen.kt` (1058 lines), `TowerDefenceStatsStore.kt`.

| Area | Today |
|---|---|
| Map model | `TowerDefenceLevel(id, displayName, path: List<Offset>, towerZones: List<Offset>)`, coordinates normalised 0..1 in both axes. Three hand-built levels: `switchback`, `spiral` ("Spiral Keep"), `gauntlet` ("Zigzag Gauntlet"). Enemies walk a fixed polyline (`positionAlongPath`). |
| Placement | 8 fixed `towerZones` per level; `placeTower(zoneIndex)`; one tower per zone; cost 50. |
| Towers | One type. 5 levels (`MAX_UPGRADE_LEVEL`). Damage 18 + 9 per level, cooldown 0.65 s minus 0.04 per level (floor 0.25), range 0.22 of width plus 0.02 per level. Upgrade cost `40 x 1.6^(level-1)`. No sell, no move, no targeting choice (targets the furthest-along enemy, "First"). |
| Enemies | One archetype; hp 40 + 12 per wave, speed 0.09 + 0.003 per wave (normalised units per second), count 6 + 1 per wave, 10 gold each, 0.8 s spawn interval. |
| Waves and lives | 10 waves, 20 lives, 5 s inter-wave cooldown. Lives lost per leaked enemy. |
| Difficulty | `CpuDifficulty` EASY / MEDIUM / HARD: start gold 150 / 100 / 70, hp x 0.75 / 1 / 1.4, speed x 0.85 / 1 / 1.2, count x 0.8 / 1 / 1.3. Separate axis from level. |
| Stats | `TowerDefenceStatsStore`: furthest wave reached, keyed `"$levelId:${difficulty.name}"`. |
| Pause | Manual pause and lifecycle pause share one flag; building while paused is allowed on purpose. |
| Render | One Compose `Canvas`; flat colours from a private `TowerDefencePalette`; the whole screen recomposes each frame (scorecard systemic item 3). |

Sources of the "fluid, defined, open" gap: fixed pads feel like a puzzle with the answers pre-printed, one tower and one enemy give no decisions beyond "where", and the flat palette gives no place or fantasy.

## 3. Goals

1. Open placement: put a tower anywhere buildable, with one safety rule (enemies always keep a route) shown live while dragging.
2. A more fluid, more defined map: smooth painted terrain and a readable road; the route is data (a flow field), not a drawn line.
3. Left to right siege as the default layout: enemies enter at the west edge, the player's castle (a real, visible object with health) stands at the east edge.
4. Eight original themes sharing one balance sheet: Castle Siege (default), Space Station, Roman Frontier, Cherry Ridge (Feudal Japan-inspired), Sunstone Oasis (Ancient Egypt-inspired), Frostfjord (Viking-inspired), Toy Room (kids), Coral Reef (undersea, kids).
5. High-quality static art plus a short list of living ambient layers, all inside the existing style system (Art style, Motion level, Quality, Reduced Motion).
6. More decisions per minute without more clutter: four tower roles with a branch at level 3, four enemy archetypes, boss waves, sell, move, targeting modes.
7. Modes beyond one campaign: Campaign by map, Endless, Daily Challenge, Sandbox, Toy Room, and one-device Co-op.

## 4. What changes, what stays

Changes: map layer (pads and polyline to nav grid and flow field), towers (1 to 4 roles x branches), enemies (1 to 4 archetypes plus boss), art (flat palette to theme packs), HUD (abstract lives to a Castle with health), modes, ambient motion, result screens, layouts for tablet and Fold.

Stays: wave count (10 in campaign), the gold economy shape and upgrade price curve, the three difficulty tiers and their multipliers, the stats key format `mapId:DIFFICULTY`, pause semantics, `matchOver` guard pattern, deterministic fixed-step `tick(dt)` with dt clamp 0.05.

## 5. Style-system fit

Follows `docs/design/style-packs/ARCHITECTURE.md` and the Gem Cascade pilot pattern: engine and style separated, engine emits events, the animator reads `EffectiveStyle`.

| Style surface | Tower Defence use |
|---|---|
| BOARD_SURFACE | The field: terrain, road, buildable mask overlay, grid dots in build phase, cached once per map and theme. |
| PIECE | Tower sprites (role x level x branch x state), enemy sprites (archetype x state), castle states (intact, cracked 50%, cracked 25%, fallen). |
| TILE | Not used. |
| BACKGROUND | Sky, far, mid layers with parallax and the ambient layers in `LIVING-WORLD.md`. |
| CHROME | HUD, tray, upgrade panel, next-wave card, result screens (GameChrome v2). |
| CELEBRATION | Kill pops, level-up burst, victory fireworks, boss banner. `ParticleBudget` applies. |
| SOUND_PALETTE | `td_*` ids per theme (see `SCRIPT.md`). |
| HAPTIC_PROFILE | Per-event, per motion level (see `docs/design/haptics-cue-sheet.md` vocabulary). |

Art style ladder: Flat (vector paths, no gradients beyond one lit side), Soft 2.5D (baked gradient sprites, cached shadows, 6 to 10 degree ground tilt on BOARD_SURFACE only), Full 3D (baked turntable sprites of the same assets). Falls back 3D to 2.5D to Flat. Mockups show the Soft 2.5D look; Flat is the same drawing without shading, 3D is a bake of the same models and is subject to the existing owner decision on 3D (no engine, baked sprites only; Filament declined).

Motion levels: Calm / Standard / Lively and Reduced Motion are specified in `LIVING-WORLD.md` section 4. Quality tiers drive sprite resolution and ambient budget.

Hard rules inherited: original art only, no trademarks, no third-party IP, no engine. Cultural themes are inspired by history and folklore, with invented names (see `THEMES.md` naming rule).

## 6. Phasing (detail in `SCALE.md`)

| Phase | Content | Exit test |
|---|---|---|
| P0 | Fix whole-screen per-frame recomposition on the TD screen (prerequisite for anything Lively). Extract palette to a registry keyed by pack. | Frame time unchanged or better on the existing map. |
| P1 | Engine: nav grid, flow field, build validation, free placement, sell, move, targeting, castle health. Legacy three maps kept as "Classic" fixed-lane maps. | JVM tests for flow field, validation, determinism; Classic stats keys unchanged. |
| P2 | Castle Siege theme, 4 roles, 4 archetypes, boss waves, HUD v2, build tray, upgrade panel, next-wave card. | Device pass on tablet and Fold. |
| P3 | Themes: Space Station, Roman Frontier; ambient layers; Calm / Standard / Lively. | Perf budget in `SCALE.md`. |
| P4 | Remaining themes (Cherry Ridge, Sunstone Oasis, Frostfjord), modes hub, Daily, Endless. | |
| P5 | Kid themes (Toy Room, Coral Reef), Toy Room mode, Gentle tier, accessibility passes (TalkBack grid, one-switch). | design:accessibility-review pass. |
| P6 | Sandbox, one-device Co-op, results polish, stickers. | |

## 7. Out of scope

Online or Nearby multiplayer for Tower Defence (the game stays `SINGLE_PLAYER_VS_BOT`; Co-op is one device). User-generated maps. Real-time 3D engine. Paid cosmetic themes, ads, loot boxes. Procedurally generated maps (maps are authored, the Daily seed only picks map, theme and a modifier). Any change to `GameChrome` beyond what Gem Cascade already proposes.

## 8. Deliverables in this set

| File | Purpose |
|---|---|
| `SCOPE.md` | This file. |
| `MAP-MODEL.md` | Open placement, flow field, build validation, sell / move / targeting, migration. |
| `THEMES.md` | Eight themes with towers, enemies, base, palette, ambient, audio, haptics, kid variants, ratings. |
| `LIVING-WORLD.md` | Ambient motion spec, motion levels, quality tiers, per-frame budget. |
| `SCRIPT.md` | Storyboards for seven key moments, audio and haptic ids, copy EN/ES, staged plan. |
| `SCALE.md` | Asset counts, sizes, budgets, effort. |
| `ACCESSIBILITY.md` | Targets, colour-blind rules, one-switch, TalkBack grid. |
| `OPEN-QUESTIONS.md` | Owner decisions with recommended defaults. |
| `../canvas/TD*.dc.html` | Mockup boards (12) plus `RF-manifest-TD.json`. |

## 9. Boards

| Board | Size | Shows |
|---|---|---|
| TDOverview | 2150 x 920 | Today vs redesign, principles, what changes. |
| TDCastleSiege | 2150 x 1300 | Hero screen, 12 redlines, build / mid-wave / boss states. |
| TDOpenPlacement | 2150 x 1200 | Ghost valid and blocked, snap vs free, flow field, radial menu, targeting, rules, one-switch. |
| TDThemesA | 2150 x 920 | Castle Siege, Space Station, Roman Frontier, Cherry Ridge. |
| TDThemesB | 2150 x 920 | Sunstone Oasis, Frostfjord, Toy Room, Coral Reef. |
| TDTowerEnemyCodex | 2150 x 1300 | Tower trees with stats, enemy roster, wave mix chart, skin map for all themes. |
| TDLivingWorld | 2150 x 1400 | Layer stack, motion levels, frame budget, time of day and weather, ambient filmstrips per theme. |
| TDModes | 2150 x 920 | Modes hub, rules sheet, tier table, theme picker carousel, co-op layout. |
| TDPrePostMatch | 2150 x 920 | Setup, wave toast, victory, defeat, score card, stickers. |
| TDTabletFold | 2150 x 1230 | Tablet two-pane, Fold inner / tabletop / book / cover, phone scroll and redrawn portrait. |
| TDKidsAccess | 2150 x 1080 | Toy Room, colour-blind simulation, ring glyphs, large targets, Reduced Motion, TalkBack, one-switch. |
| TDStoryboards | 2150 x 1400 | Five-frame boards for seven moments (extra, backs `SCRIPT.md`). |
