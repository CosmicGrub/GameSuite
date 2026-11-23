# Tower Defence: map model proposal

Status: DESIGN DRAFT, 2026-11-22. Proposal only; no source edited. Companion board: `TDOpenPlacement.dc.html`.

## 1. The decision in one paragraph

Replace "fixed polyline plus 8 fixed pads" with an **authored open field on a fine navigation grid, with enemies following a flow field and a build-block validation rule**. The player places towers wherever the terrain allows; the only hard rule is that every spawn can still reach the castle gate. A soft rule (route length cap) keeps pure mazing from becoming the only strategy. The three legacy maps stay available unchanged as "Classic" fixed-lane maps so no existing stats or muscle memory is lost.

## 2. Options considered

| Option | Placement | Enemy path | For | Against | Verdict |
|---|---|---|---|---|---|
| A. Keep fixed lanes, add more pads | 8 to 20 pads | authored polyline | Trivial; zero migration; deterministic | Fails the request ("open placement as I choose"); pads feel like pre-printed answers | Keep only for Classic maps |
| B. Continuous placement beside fixed lanes | anywhere off the road | authored polyline (road is not buildable) | Simple, readable, no path validation | Still one lane; placement is open but strategy is shallow; road occupies map width | Fallback if C slips |
| C. Open field, flow field, build validation (recommended) | anywhere buildable, including the road | Dijkstra flow field from the gate | Real choices; towers shape the route; fits "more fluid"; works with flyers | Needs validation UI, tuning of anti-maze cap, more tests | Recommended |
| D. Pure maze (no cap) | anywhere | shortest path | Genre classic | Degenerates into one meta; hard to balance; hostile to kids and one-switch | Reject; use a cap |

## 3. Coordinate system and the grid

- Field is 24 x 14 **tiles**, aspect 12:7 (close to 16:9). One tile is the footprint of one tower. Authored once; the screen letterboxes or scrolls on other aspects (`TDTabletFold`).
- Navigation grid: 2 x 2 nav cells per tile, so 48 x 28 = 1344 cells. Small enough to recompute on every drag frame.
- Engine units stay "normalised" so the existing converters keep working: x in 0..1 of width, y in 0..1 of height; tiles are a view concept. Today's range constants map as follows (`BASE_TOWER_RANGE` 0.22 of width = 5.3 tiles; `RANGE_PER_UPGRADE` 0.02 = 0.48 tile).
- Terrain layers per map (data, not code):

| Layer | Content |
|---|---|
| `buildable` | bit per nav cell; false for water, cliffs, deco rocks, castle footprint, spawn gate apron |
| `cost` | float per cell; road 1.0, open ground 1.25, rough (bushes, mud) 3.0; blocked is infinity |
| `spawns` | 1 to 3 spawn points on the west edge (ordered; each has a lane weight) |
| `gate` | the castle's front cells on the east edge |
| `art` | theme pack reference (parallax layers, ground tint, deco placements) |

- The road is a **cost bias**, not a wall: enemies prefer it (cost 1.0 vs 1.25) but will leave it when towers block it. That is what makes the road look "defined" yet the field open.

## 4. Flow field and enemy movement

1. After any change to blocked cells (tower placed, sold, moved), recompute a distance-to-gate field with Dijkstra from the gate over 8-neighbour cells (diagonal cost 1.414 x cell cost, no corner cutting through blocked cells).
2. Each ground enemy samples the field, steers toward the lowest-cost neighbour, bilinear-interpolating the direction between cells so movement is smooth rather than grid-snapped. Enemy radius 0.35 tile; no inter-enemy collision (they overlap like today).
3. Flyers ignore the field and move in a straight line to the gate; Blast towers cannot target them. This also removes the "air units fall through mazes" problem.
4. Speed is expressed in tiles per second; retune so the median first-wave route takes about 33 s, as today (about 3.0 normalised units at 0.09 per second).
5. Determinism: fixed-step tick (existing dt clamp 0.05), stable tie-breaking in the priority queue (cost, then cell index), no randomness in routing. Daily seeds and JVM tests stay reproducible.
6. Cost: 1344 cells; a Dijkstra pass is well under 1 ms on a mid-range phone. Recompute only when blocked cells change, never per frame. During a drag the validation pass runs at most 30 Hz.

## 5. Build-block validation (the one hard rule)

Placement is a function `tryPlace(cell, role) -> PlaceResult`.

```
PlaceResult = Ok(detourTiles) | Rejected(reason)
reason in { UNBUILDABLE, OVERLAP, SPACING, NO_GOLD, BLOCKS_ROUTE, TOO_LONG, OWNED_BY_OTHER (co-op) }
```

Steps (pure Kotlin, no Compose, JVM-tested):
1. Footprint cells inside `buildable` and not occupied: else UNBUILDABLE or OVERLAP.
2. Spacing: centre distance to every other tower at least 0.8 tile (Snap off). With Snap on, cell occupancy already guarantees it.
3. Gold at least the tower cost: else NO_GOLD (ghost still shows, tinted, with the cost chip in red plus a coin-X glyph).
4. Tentatively block the footprint, run Dijkstra from every spawn. If any spawn cannot reach the gate: BLOCKS_ROUTE.
5. Compare route length with the open-field baseline for that map (no towers). If it exceeds 2.5 x baseline: TOO_LONG. Value is per-map data (Sandbox can raise or disable it).
6. Else Ok with `detourTiles = newLength - oldLength` shown as "Route open +2 tiles".

Every rejection returns a reason string for the toast and for TalkBack. A silent "no" never happens.

Sell and move use the same function in reverse: removing a tower can only lengthen options, so sell never fails; move is sell plus place with the 20 percent fee waived in the build phase.

## 6. Interaction model

| Action | Gesture | Notes |
|---|---|---|
| Choose a tower | Tap a tray card | Card lifts; `Ok`/Rejected ghost appears on the field |
| Place | Drag from the card, or tap the card then tap the field | Ghost offset 56 dp above the finger so it is visible; release to build; drag back to the tray to cancel |
| Snap | Toggle in the tray (default on) | On: ghost centres on the nav cell, haptic tick per cell; Off: ghost follows the finger, spacing rule applies |
| Select | Tap a tower | Solid ring, upgrade panel, tower hints stay faint dashed rings |
| Radial menu | Long-press a tower (0.35 s), slide to a spoke, release | Spokes: Upgrade, Info, Target, Move, Sell; 64 dp each |
| Upgrade | Panel button, or radial spoke | Levels 1 to 5; branch chooser appears at level 3 (A or B), irreversible, free respec in Sandbox |
| Sell | Panel or radial | 75 percent refund; 100 percent for 3 s after placing (undo) |
| Move | Radial Move, then drag | Costs 20 percent of total invested, free in the build phase |
| Targeting | Panel chips: First, Last, Strong, Close | Per tower, remembered across waves; default First (matches shipped behaviour) |
| Range ring | Selected: solid; others: dashed; enemy reach: dotted with a triangle glyph | Colour plus dash plus glyph, see `ACCESSIBILITY.md` |
| Route preview | Build phase and while dragging | Chevron line; recomputed live; hidden in Calm on request |
| While paused | Place, move, upgrade, sell all allowed | Matches the shipped engine and is the reason to pause |

## 7. Left to right siege layout

- Spawn gates on the west edge (1 to 3), castle on the east edge, centred vertically between 55 and 80 percent of the field height so its health bar and gate sit clear of the HUD.
- The castle is a game object: `Castle(health, maxHealth, gateCells)`. It replaces the abstract 20 lives. Default 20 health keeps the economy identical; each leaked enemy costs 1, a boss costs 5. Damage stages draw cracks at 50 percent and 25 percent.
- Camera: the full field fits tablet and Fold inner. On narrow screens the camera scrolls horizontally and parks on the castle in build phase and on the first enemy during a wave (`TDTabletFold`). Vertical scroll is never needed.
- Non-castle themes keep the same layout: station core, legion fort, keep, temple, longhall, block fortress, coral citadel (`THEMES.md`).

## 8. Targeting rules

| Mode | Picks | Why it exists |
|---|---|---|
| First (default) | furthest along the field (lowest remaining distance to the gate) | Identical to today's rule, which a prior test pass chose over nearest-in-range |
| Last | newest in range | Kills fresh runners before they spread |
| Strong | highest current hp | Focus brutes and bosses |
| Close | nearest to the tower | Predictable for kids, cheapest to compute |

"Remaining distance to the gate" replaces `distanceTraveled` and is just the flow-field value of the enemy's cell.

## 9. Tower roles (balance lives in the role, not the theme)

Detail with numbers is in `TDTowerEnemyCodex.dc.html`. Summary: Bolt (fast single target, air and ground, base cost 50), Blast (splash, ground only, 90), Control (slow or brief stun, 80), Support (aura for rate, range or kill gold, 110). Branch A or B at level 3. Costs scale by the shipped curve `40 x 1.6^(level-1)` multiplied by a role factor (Bolt 1.0, Blast 1.8, Control 1.6, Support 2.2).

## 10. Migration

What persists across app updates today: only best-wave stats per `"$levelId:${difficulty.name}"` (DataStore). In-progress runs are not persisted, so there is no run-save migration.

| Item | Plan |
|---|---|
| Existing maps | `switchback`, `spiral`, `gauntlet` stay shippable as `MapKind.FIXED_LANE` ("Classic" tab): same polyline, same 8 pads, one flat theme or Castle Siege art on top, 1 tower role (Bolt) so numbers match. Zero change to their stats. |
| New open maps | `MapKind.OPEN_FIELD`; ids like `castle-pass`, `river-bend`. Stats key format unchanged: `"$mapId:${difficulty.name}"`. |
| Modes | Campaign uses the plain key. Other modes use a prefix so keys never collide: `endless/`, `daily/` (date), no stats for Sandbox. |
| Theme | Not part of the key. A map's best wave is the same whichever theme is shown. |
| Re-authoring legacy maps for open play | Their polylines become the road cost layer (cost 1.0) and their 8 pads become optional "suggested spots" shown as faint markers in the tutorial only. Opt-in, labelled "Open version". |
| Engine API | `placeTower(zoneIndex)` is kept for FIXED_LANE; new `placeTower(cell, role): PlaceResult`. `TowerDefenceTower.zoneIndex` becomes `cell` plus `role` plus `branch` plus `targeting` plus `invested`. `TowerDefenceLevel.path` / `towerZones` stay for Classic; Open maps carry `terrain` arrays instead. |
| UI | `selectedZone` becomes `selectedTowerId`; the 8-zone content descriptions in `towerDefenceZoneDescription` become the TalkBack cell descriptions in `ACCESSIBILITY.md`. |
| Default difficulty hook | `game.difficulty = settings.defaultCpuDifficulty` unchanged; Gentle is added as a fourth tier only for kid profiles and Toy Room. |

## 11. Tests to write first (device-free, JVM)

1. Flow field: reaches the gate on all authored maps; every non-blocked cell has a finite cost; diagonal rule honoured.
2. Property test: random legal placement sequences never leave a spawn unable to reach the gate and never exceed the 2.5 x cap.
3. Rejection reasons: each reason produced by a crafted case.
4. Determinism: same seed, same inputs produce identical positions after N ticks (guards the Daily mode).
5. Classic parity: Switchback with one Bolt role yields the same enemy timing and damage numbers as the shipped engine for a fixed script.
6. Sell, move, undo: gold round-trips.
7. Flyers: straight line, ignore towers, not targetable by Blast.

## 12. Risks

| Risk | Mitigation |
|---|---|
| Mazing dominates | 2.5 x route cap, brutes armoured against Bolt, flyers ignore the maze, boss waves |
| Dragging looks laggy | Throttled validation, cached field, ghost drawn by the cheap canvas layer |
| Touch precision on phones | Snap default on; ghost offset; large-target setting (`ACCESSIBILITY.md`) |
| Balance doubled by roles x branches x themes | One balance sheet keyed by role; themes are skins only |
| Existing players dislike losing fixed pads | Classic tab keeps them |
