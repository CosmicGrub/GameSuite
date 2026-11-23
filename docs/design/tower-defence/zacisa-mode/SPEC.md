# Bot Bastion: spec (a Tower Defence mode modelled on a researched space bot-defence game)

Status: DESIGN DRAFT, 2026-11-22. Nothing here is built. No Kotlin was edited, no build was run, nothing was committed or installed.
Reads with: `RESEARCH.md` (sources, confidence), `BALANCE.md`, `MAPS.md`, `SCOPE-SCALE-SCRIPT.md`, `OPEN-QUESTIONS.md`, and the existing redesign set `docs/design/tower-defence/` (`SCOPE.md`, `MAP-MODEL.md`, `THEMES.md`, `OPEN-QUESTIONS.md`).
Mockups: `docs/design/canvas/ZS*.dc.html` (8 boards, manifest `RF-manifest-ZS.json`).

## 1. Owner request and rules of engagement

Owner: a new mode of Tower Defence modelled on the Wii U game "ZaciSa's Last Stand" (later "ZaciSa: Defense of the Crayon Dimension"), as close to its gameplay as possible, then expand.

Hard limits (project rule, owner-agreed): ORIGINAL art, names, sounds, music, text and map layouts only. Game rules and mechanics may be matched faithfully. This spec therefore matches the researched STRUCTURE (economy, five bot roles plus shield, upgradable stats, per-bot target priority, four specials, difficulty tiers, swarm events, progressive map unlock, co-op with pilot bots, local leaderboards) and uses our own names ("Bot Bastion", Scrap, Picket, Lobber, Haze, Skiff, Tender, Aegis, Drifter and so on), our own art and our own balance.

Working name: **Bot Bastion**. Needs a trademark and app-store name search before any public use (open question 1). Source-game names are never used in the UI, strings, asset ids, file names or store text; this document names the source game only as research context and should not ship in user-facing text.

## 2. The mode in one paragraph

You hold a drifting relay station, the Core, at the centre of a top-down space arena. Rock-ships called Drifters stream in along glowing lanes from the arena rim. You spend Scrap, earned from kills and wave bonuses, on bots placed on build nodes: Picket (all-round gun), Lobber (long-range splash artillery with a blind spot), Haze (huge range, low power), Skiff (a patrol craft that flies the arena rim and hunts), Tender (no damage; slows and freezes). Every bot upgrades five levels in power, range and rate, and has a target priority. Aegis buys Core shield at a rising price. Four Specials (Scrap Surge, Score Boost, Nova Pulse, Repair Kit) come from waves and Couriers. Waves grow in size and toughness; Hard tiers add swarm events; every tenth wave is a boss. Up to four players can share one device: each flies a pilot Skiff, has their own Scrap and shares the Core. Scores go on local leaderboards per map and tier.

## 3. Mapping from the source game to ours

| Source mechanic (confidence) | Bot Bastion | Notes |
|---|---|---|
| Defend a central base from waves of ships (H) | Core in an arena, Drifters etc. | Core replaces `lives` |
| One currency; kills plus round bonus (H) | Scrap; per-kill plus `20 + 5w` wave bonus | |
| Five bots + shield (M-H) | Picket, Lobber, Haze, Skiff, Tender, Aegis | Roles match; stats ours |
| Upgrade power / range / speed (M) | Power, Range, Rate, 5 levels; Tender: slow, range, freeze | |
| Per-bot target priority (M) | First (default), Last, Weakest, Strongest, Closest | |
| Shield price rises per buy (M) | Aegis x1.25 each purchase | |
| Four specials (M) | Scrap Surge, Score Boost (stack), Nova Pulse, Repair Kit (50%) | |
| 4/5 damage per hit by tier, +25% cash on Easy, swarm every 5 on Hard (M) | Cadet, Hero anchored; Captain, Insanity, Endless ours | |
| Levels start ~20 weak enemies then grow (H) | `20 + 3(w-1)`, hp x1.11 per wave | |
| Six maps, 20+ in remake, progressive unlock (H/M) | 12 maps in 4 sectors, unlock by stars | Fewer maps at launch, more in later waves |
| Limited build spots (M) | 12 to 22 fixed nodes per map | Sandbox allows open placement later |
| Drone patrols the edges (M) | Skiff rim patrol | |
| Co-op up to five, extra players pilot a bot (M) | Up to four on one device | Five is phone-unfriendly; see Q9 |
| Score and leaderboards (M) | Local top 10 per map and tier | Online later |
| Touch UI with speed/pause/menus (M) | Same functions, fixed for the reviewer complaints | |
| Endless mode (H) | Endless tier | |

Unknowns (path shape, boss, upgrade count, numbers) were filled with our own design and are flagged in `RESEARCH.md` section 5.

## 4. How it rides the existing Tower Defence engine

Existing engine (`games/towerdefence/TowerDefenceGame.kt`, 622 lines; `ui/TowerDefenceScreen.kt`): fixed-step `tick(dt)` with dt clamp 0.05; `TowerDefenceLevel(id, displayName, path, towerZones)` normalised coordinates; one tower type, 5 upgrade levels; `placeTower(zoneIndex)`; 10 waves, 20 lives; `DifficultyConfig` EASY / MEDIUM / HARD; stats store keyed `"$levelId:${difficulty.name}"`; manual and lifecycle pause share one flag, building while paused allowed.

The existing redesign (`docs/design/tower-defence/`) proposes an open-field flow-field model (`MAP-MODEL.md`), four tower roles and four enemy archetypes, a Castle with health, modes and co-op on one device. Bot Bastion is a **ruleset** on that same engine, not a fork.

### 4.1 Fixed nodes versus open placement

The source game has limited build spots (M). That is exactly the existing `FIXED_LANE` / `towerZones` model scaled up: nodes are `towerZones` (12 to 22 per map, up from 8), the path becomes `lanes`. Decision: **Bot Bastion v1 uses fixed nodes** (zero dependency on the flow-field work, matches the source). Open placement is a Sandbox option once `MAP-MODEL.md` option C lands; the lane data then becomes a road-cost layer. This keeps Bot Bastion shippable ahead of, and independent from, the open-placement phase P1 of the redesign.

### 4.2 What is reused unchanged
Fixed-step tick and dt clamp, wave scheduler shape, gold economy (renamed Scrap in the mode's strings), difficulty hook `settings.defaultCpuDifficulty`, pause semantics, `matchOver` guard, projectile and death-event plumbing, stats store (extended with mode prefix), style system (BOARD_SURFACE, PIECE, BACKGROUND, CHROME, CELEBRATION, SOUND_PALETTE, HAPTIC_PROFILE) and Motion levels from `SCOPE.md` section 5.

### 4.3 New engine work (all pure Kotlin, JVM-testable)

| Piece | Change |
|---|---|
| Ruleset | `TowerDefenceRuleset { CLASSIC, BOT_BASTION }`; `TowerDefenceLevel` gains `lanes: List<List<Offset>>`, `core: Offset`, `voids`, `nodes`, `pace`; Classic keeps `path`/`towerZones` |
| Multi-lane | `TowerDefenceEnemy.laneIndex`, `positionAlongLane`; spawner assigns lanes round-robin or weighted |
| Bot catalogue | `BotType` data (cost, power, range, rate, extras) and level formulas from `BALANCE.md`; `TowerDefenceTower` gains `botType`, `level`, `target`, `invested`, `ownerId` |
| Damage model | Single target, splash (Lobber) with min range, chip fire (Haze), slow and freeze status (Tender), armour factor (Plater), boss slow resistance |
| Targeting | First, Last, Weakest, Strongest, Closest comparators |
| Patrol unit | `Skiff` is a mobile bot: moves along the rim loop at 0.22 W/s, chases the nearest enemy in range, returns; deterministic |
| Core and shield | `coreHp`, `shield`, leak damage by tier and enemy multiplier, Aegis price curve |
| Waves | Generator from `BALANCE.md` (count, hp growth, mix, couriers, bosses, swarm events) rather than hand tables; deterministic given map, tier, players |
| Specials | Inventory, timed multipliers (stack to x4), Nova Pulse, Repair Kit, Courier drops |
| Scoring | Kill points x live multiplier, wave clear, win bonuses; star rules |
| Tiers | Add `CADET, CAPTAIN, HERO, INSANITY, ENDLESS` to the mode; map to existing EASY / MEDIUM / HARD for Classic migration |
| Co-op | `Player(id, colour, scrap, pilot)`; ownership on towers; pilot Skiff control input; scrap gifting; hp scaling |
| Stats | Keys `bb/$mapId:$tier` (best wave, best score, stars); top-10 lists per key; Classic keys untouched |
| Save | None for runs (same as today); stats only |

Not new engine work: flow field, open placement (Sandbox, later), online boards.

## 5. Rules in detail

1. Start: Scrap 200 x tier cash multiplier; Core 100; first wave begins after a build phase with a Start / Call early button (+30 Scrap).
2. Wave: spawner emits `count` enemies over about 20 s per lane share; wave ends when all enemies are dead or leaked; bonus Scrap and clear score; next-wave card previews composition; the player may keep building during waves.
3. Build: tap a node, choose a bot (cost shown, dimmed if unaffordable), place. One bot per node. Sell refund 75% (100% for 3 s). Move is not allowed (matches fixed-spot genre); Sandbox may allow it.
4. Upgrade: levels 1 to 5, price from `BALANCE.md`; target priority chips on the panel.
5. Leak: an enemy reaching the Core removes `tier leak x enemy leak multiplier`; Aegis shield absorbs first. Core 0 = defeat.
6. Specials: tap to use; Nova Pulse kills all enemies on screen (boss takes 25% of max hp); Repair Kit restores up to 50% of Core; multipliers last 30 s and stack up to x4.
7. Victory: clear the goal wave (20 / 30 / 40) on the tier, or Endless runs until defeat.
8. Pause is instant; building, upgrading, selling and using Specials are allowed while paused (unchanged engine behaviour).
9. Speed 1x / 2x / 3x; lifecycle pause reuses the existing flag.

## 6. Co-op on one device

- 1 to 4 players. Player 1 uses the normal touch UI. Players 2 to 4 each get a pilot Skiff (free, 60 health, auto-fires; the player drags to steer within the arena) plus their own Scrap counter. Input options: gamepad (up to 3 more), second touch region (tabletop), or a split screen strip. On a phone, co-op is limited to 2 pilots (open question 9).
- Shared Core and shared wave. Kills pay the player whose bot or pilot made the last hit; a tap gifts Scrap.
- Build rights: any player may build on any free node; the placing player's colour ring shows on the bot; others can upgrade for a fee equal to the upgrade price (the owner receives 10% rebate). Sell only by owner.
- Enemy hp scales x1.0 / 1.2 / 1.4 / 1.6. Pilots down at 0 health and respawn after 10 s; only boss shots hurt them.
- Tabletop posture on the Fold: two weapon bars, one rotated 180 degrees, arena in the middle (see `ZSTabletPhone`).
- No online or Nearby co-op (existing scope decision for Tower Defence).

## 7. Screens and flow

Hub (Campaign, Endless, Co-op Defence, Leaderboards, How to play) then Campaign map select (4 sectors x 3 maps, stars, locks) then Setup (tier, players, placement mode, rules summary) then Play (HUD per `ZSInGame`) then Results (Victory / Defeat / Endless end, score card, leaderboard entry) with Retry, Next map and Boards. How to play is a six-card sheet; a first-run overlay highlights nodes, the bar and the speed controls once. See `ZSModesMenu`, `ZSResults`, `ZSInGame`.

HUD content (every element named in `ZSInGame` redlines): wave and goal, Scrap, Score with live multiplier chip, Core integrity with shield segment, speed and pause, weapon bar with costs, Aegis, Specials, Sell, upgrade panel with before and after values and target chips, next-wave card, boss bar and banner, co-op chips.

## 8. Campaign structure

12 maps in 4 sectors (Cradle Reach, Glass Belt, Ember Deep, Hush Verge), 3 per sector, 3 stars each (36 total). Map unlock by total stars (0, 0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 21) so a player can always reach the next map after a good run, never via money. Stars: 1 win; 2 win on Captain or above with Core above 50%; 3 win on Hero or with Core above 80%. Tiers are chosen per run, not unlocked. Endless is available on every unlocked map. Future waves add more maps (the source remake has 20+); the data format is one `MapDef` per map so they are content-only additions.

## 9. Leaderboards

Local: top 10 per `(mapId, tier)`: pilot initials (3 letters), score, wave reached, time, Core left, date, player count. Stored in DataStore next to the existing stats; capped list; no personal data beyond initials the player types. Online boards: later, behind a parent-controlled toggle and the existing relay server; out of scope here (SCOPE-SCALE-SCRIPT.md section 6).

## 10. Audio and haptics

Synthesised palette (no recordings, in line with the existing decision for Tower Defence themes). Sound ids use a `bb_` prefix. Haptics follow the existing vocabulary in `docs/design/haptics-cue-sheet.md`; all cues degrade with Motion level and the system haptic setting.

| Event | Sound id | Haptic |
|---|---|---|
| Place bot | `bb_place` | tick |
| Upgrade | `bb_upgrade` (rising pair, pitch by level) | double tick |
| Sell | `bb_sell` | soft tick |
| Picket / Haze fire | `bb_pew`, `bb_hiss` (throttled to 6 per second total) | none |
| Lobber fire / blast | `bb_thump`, `bb_boom` | light, throttled |
| Tender freeze | `bb_chime` | none |
| Enemy killed | `bb_pop` (pitch by size) | none (throttled tick for bosses) |
| Courier killed | `bb_gift` | double tick |
| Leak, Core hit | `bb_hit`, `bb_hit_big` | medium; shield hit lighter |
| Wave start | `bb_wave` banner swoosh | tick |
| Wave clear | `bb_clear` | rising double |
| Boss arrival | `bb_boss` low horn | heavy rumble 400 ms |
| Nova Pulse | `bb_nova` | strong then fade |
| Victory / Defeat | `bb_win` 2.5 s, `bb_lose` 1.8 s | rising double / one long soft |

Music: one looping synthesised track per sector, under 30 s each, ducked for banners; off under silent mode.

## 11. Kids rating and safety

Stylised sci-fi, abstract rock-ships and robot bots, no blood, no humans, no gore, cartoon sparks and pops. Proposed rating note E10+ (cartoon violence) matching the redesign's other themes; Cadet is suitable for younger kids. Kid profile (existing parental profile system): Cadet default, gentle defeat copy, no leaderboards upload (local only anyway), co-op unaffected. No ads, no purchases, no loot boxes (existing scope out-of-list). Source game was criticised for no rating info; we record ours in the Play listing and in `THEMES.md`-style rating line.

## 12. Accessibility

Targets: all interactive controls 56 dp minimum (weapon bar cards 86 dp wide on the board); colour is never the only signal: bots differ by silhouette, range rings are solid versus dashed, enemy types differ by shape, status by glyph; red entry rings carry a ring glyph; Core integrity shows number and bar; shield shows number. Colour-blind safe palette check using the CVD filters from the existing TD tooling (to run at build time). Reduced Motion removes shake, trails and flashes (single frame flash for Nova). TalkBack: nodes announced as strings like "Node 7 of 18, empty, covers lane 2"; bot panel reads stats; wave start and Core damage announced politely. One-switch: auto-advance focus cycles nodes, then bots; hold to confirm; pause on focus. Text sizes follow system font scale to 200% with the bar reflowing to two rows. Haptics optional.

## 13. Performance

Budgets from `docs/design/tower-defence/SCALE.md` apply: 60 enemies on screen (cap 90 per wave but spawn over time), 40 bots (nodes cap 22), 120 projectiles, particle cap by quality tier. Arena rendering is cached (backdrop, lanes, nodes) and only dynamic layers redraw, which requires the P0 per-frame recomposition fix noted in `SCOPE.md` section 6 before Lively motion is allowed.

## 14. Layout across devices
See `ZSTabletPhone`: tablet (bar below, upgrade panel right), Fold inner (arena on top, controls pane below), Fold tabletop (co-op with two bars), phone landscape (slim rail). Phone portrait shows a rotate prompt. Fold book posture remains the app-wide untested posture.

## 15. Risks

| Risk | Mitigation |
|---|---|
| Closeness to one particular game reads as a clone | Structure only; original names, art, maps, balance; trademark search; no source names in the product |
| Fixed nodes feel restrictive (reviewer complaint) | 12 to 22 nodes, Sandbox open placement later |
| Co-op input clutter on phones | 2 pilots on phones; tabletop for 4 |
| Balance (numbers are invented) | Headless simulator test (T9) before art lock |
| Speed of waves versus long levels | 3x speed, call early, 20-wave Cadet |
| Dependency on P0 recomposition fix | Ship Calm / Standard first; Lively after P0 |
| Rating: shooting and explosions | Cartoon abstraction, rating note |
