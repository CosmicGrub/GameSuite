# Bot Bastion: balance tables (ours)

Status: TUNED 2026-11-22 against the JVM balance simulation (`BbBalanceSimulationTest`; section 10 lists every change and the before/after outcomes). Every number here is OUR balance. Research (RESEARCH.md) gave only a few anchors: 4 and 5 damage per enemy hit by tier, +25% easy cash, about 20 weak enemies in the first level, a swarm every 5th level on Hard, a shield price that rises per purchase, a 50% heal, the specials list. Everything else is invented to match that structure and must be tuned with a JVM simulation before art lock. The same data feeds `docs/design/canvas/ZSWeaponsEnemies.dc.html`.

Units: W = arena width. Range is a fraction of W (0.20 W = 3.2 u on the 16 u wide arena). Speed is in W per second at pace 1.0 (maps then multiply by `pace`).

## 1. Bots

Level formulas: power(L) = power1 x growth^(L-1), growth 1.4 for every bot except the Lobber (1.12, section 10); range(L) = range1 x (1 + 0.08 (L-1)); rate(L) = rate1 x (1 + 0.10 (L-1)). Upgrade price from L to L+1 = cost x [0.7, 1.05, 1.6, 2.4], rounded to 5. Sell refund 75% of everything invested (100% for 3 s after placing).

### Picket (All-round gun), cost 60

Steady all-direction gun. The default first buy. single target, any direction.

| Lv | Power | Range (W) | Rate /s | DPS | Upgrade to next | Total invested |
|---|---|---|---|---|---|---|
| 1 | 12.0 | 20.0% | 2.0 | 24.0 | 40 | 60 |
| 2 | 16.8 | 21.6% | 2.2 | 37.0 | 65 | 100 |
| 3 | 23.5 | 23.2% | 2.4 | 56.4 | 95 | 165 |
| 4 | 32.9 | 24.8% | 2.6 | 85.6 | 145 | 260 |
| 5 | 46.1 | 26.4% | 2.8 | 129.1 | - | 405 |

### Lobber (Heavy artillery), cost 110

Long range, big hit, slow reload, cannot hit what is right beside it. splash 0.03 W (was 0.045); blind spot 0.08 W. Power grows x1.12 per level (not x1.4).

| Lv | Power | Range (W) | Rate /s | DPS | Upgrade to next | Total invested |
|---|---|---|---|---|---|---|
| 1 | 36.0 | 34.0% | 0.45 | 16.2 | 75 | 110 |
| 2 | 40.3 | 36.7% | 0.5 | 20.0 | 115 | 185 |
| 3 | 45.2 | 39.4% | 0.54 | 24.4 | 175 | 300 |
| 4 | 50.6 | 42.2% | 0.59 | 29.6 | 265 | 475 |
| 5 | 56.6 | 44.9% | 0.63 | 35.7 | - | 740 |

### Haze (Wide chip fire), cost 90

Huge reach and fast fire for wearing a lane down, weak as a main defence. very large range, low power.

| Lv | Power | Range (W) | Rate /s | DPS | Upgrade to next | Total invested |
|---|---|---|---|---|---|---|
| 1 | 3.5 | 42.0% | 6.0 | 21.0 | 65 | 90 |
| 2 | 4.9 | 45.4% | 6.6 | 32.3 | 95 | 155 |
| 3 | 6.9 | 48.7% | 7.2 | 49.4 | 145 | 250 |
| 4 | 9.6 | 52.1% | 7.8 | 74.9 | 215 | 395 |
| 5 | 13.4 | 55.4% | 8.4 | 112.9 | - | 610 |

### Skiff (Patrol craft), cost 170

Mobile hunter that patrols the edge of the arena. Strong, fast, expensive. flies the arena rim at 0.22 W/s, hunts.

| Lv | Power | Range (W) | Rate /s | DPS | Upgrade to next | Total invested |
|---|---|---|---|---|---|---|
| 1 | 25.0 | 16.0% | 3.0 | 75.0 | 120 | 170 |
| 2 | 35.0 | 17.3% | 3.3 | 115.5 | 180 | 290 |
| 3 | 49.0 | 18.6% | 3.6 | 176.4 | 270 | 470 |
| 4 | 68.6 | 19.8% | 3.9 | 267.5 | 410 | 740 |
| 5 | 96.0 | 21.1% | 4.2 | 403.4 | - | 1150 |

### Tender (Support), cost 80

Does no damage. Slows enemies and freezes them for a moment. slow 45% (L1) to 90% (L5); brief freeze.

| Lv | Slow | Range | Freeze every | Upgrade to next |
|---|---|---|---|---|
| 1 | 45% | 18.0% W | 4.2 s (0.5 s) | 55 |
| 2 | 57% | 19.4% W | 3.8 s (0.5 s) | 85 |
| 3 | 69% | 20.9% W | 3.4 s (0.5 s) | 130 |
| 4 | 80% | 22.3% W | 2.9 s (0.5 s) | 190 |
| 5 | 90% | 23.8% W | 2.5 s (0.5 s) | - |

### Aegis (Core shield), cost 100

Adds 40 shield points to the Core (spent before Core integrity, no regeneration). Price rises x1.25 on every purchase in a run: 100, 125, 156, 195, 244, 305, 381, 476. Matches the researched rising-price behaviour; the exact curve is ours.

Target priority per bot (default First): First, Last, Weakest, Strongest, Closest. Researched: a per-bot priority such as weakest or strongest exists in the source; First, Last and Closest are our additions.

Armour: Plater takes 25% less damage from Picket and Haze (not from Lobber splash or Skiff). Behemoth halves slow strength and ignores freeze.

## 2. Enemies

HP at wave w = base x growth(w) x tier hp multiplier, where growth(w) rises 15% per wave to wave 30, then 1% per wave to wave 40 (a plateau: the build is maxed by then), then 7% per wave (Endless). Count at wave w = min(90, 20 + 3 (w-1)); wave 1 = 20 weak enemies (anchor from sources). Every 5th wave adds 1 Courier; waves 10, 20, 30, 40 add a Behemoth boss.

| Enemy | HP | Speed W/s | Points | Scrap | Leak mult | Note |
|---|---|---|---|---|---|---|
| Drifter | 30 | 0.06 | 10 | 6 | 1 | Plain rock-ship. The bulk of every wave. |
| Darter | 18 | 0.125 | 14 | 7 | 1 | Fast and fragile. Punishes slow reloads. |
| Plater | 120 | 0.045 | 35 | 18 | 2 | Armoured: takes 25% less from Picket and Haze. |
| Splitter | 60 | 0.058 | 25 | 10 | 1 | Breaks into two Bits (hp 22) when killed. |
| Courier | 80 | 0.1 | 60 | 25 | 0 | Special carrier. Kill it before it escapes for a Special item. |
| Behemoth | 1500 | 0.03 | 600 | 250 | 5 | Boss on every 10th wave; shrugs off slows by half. |

Core damage per leak = tier leak damage x enemy leak multiplier (Drifter 4 on Cadet, 5 on Captain and Hero, 7 on Insanity; a leaked Behemoth costs 20, 25, 25 and 35). Core integrity 100.

## 3. Wave composition

| Waves | Drifter | Darter | Plater | Splitter |
|---|---|---|---|---|
| 1-2 | 100% | 0% | 0% | 0% |
| 3-5 | 80% | 20% | 0% | 0% |
| 6-9 | 55% | 20% | 15% | 10% |
| 10-19 | 40% | 22% | 20% | 18% |
| 20-end | 30% | 25% | 25% | 20% |

Sample waves (Captain):

| Wave | Count | HP x | Drifter hp | Plater hp | Clear bonus Scrap | Clear score |
|---|---|---|---|---|---|---|
| 1 | 20 | 1.00 | 30 | 120 | 25 | 110 |
| 2 | 23 | 1.15 | 34 | 138 | 30 | 120 |
| 5 | 32 | 1.75 | 52 | 210 | 45 | 150 |
| 10 | 47 | 3.52 | 106 | 422 | 70 | 200 |
| 15 | 62 | 7.08 | 212 | 849 | 95 | 250 |
| 20 | 77 | 14.23 | 427 | 1708 | 120 | 300 |
| 30 | 90 | 57.58 | 1727 | 6909 | 170 | 400 |
| 40 | 90 | 63.60 | 1908 | 7632 | 220 | 500 |

Swarm event (tiers with a swarm interval): after every Nth wave, 30 Darters at 1.4x speed arrive over 8 s with no Scrap reward but full points. Matches the researched fast-spawning special attack every 5 levels on Hard.

## 4. Economy

- Start Scrap 200 x tier cash multiplier (Cadet 250, Captain 200, Hero 200, Insanity 170).
- Kill Scrap per the enemy table, x Scrap Surge multiplier. Wave clear bonus = 20 + 5 x wave. Call-early bonus +30 Scrap.
- Insanity: bot and Aegis prices +10%.
- Expected: by wave 10 a Captain run has earned roughly 2,600 Scrap, enough for about 8 bots at L2 to L3. To be tuned in simulation.

## 5. Tiers

| Tier | Core dmg per leak | Cash | HP x | Speed x | Swarm every | Goal waves | Source |
|---|---|---|---|---|---|---|---|
| Cadet | 4 | x1.25 | x0.8 | x0.9 | - | 20 | anchored: 4 dmg, +25% cash |
| Captain | 5 | x1.0 | x1.0 | x1.0 | - | 30 | ours |
| Galactic Hero | 5 | x1.0 | x1.1 | x1.03 | 5 | 40 | anchored: 5 dmg, swarm every 5 |
| Insanity | 7 | x0.85 | x1.15 | x1.06 | 3 | 40 | name from remake; rules ours |
| Endless | 5 | x1.0 | x1.0 | x1.0 | 5 | endless | name from remake; rules ours |

## 6. Specials

One is awarded at the end of every 5th wave (70% common / 30% rare), plus one from each Courier kill. Max 3 held per kind.

| Special | Kind | Effect |
|---|---|---|
| Scrap Surge | common | x2 Scrap for 30 s, stacks to x4 |
| Score Boost | common | x2 Score for 30 s, stacks to x4 |
| Nova Pulse | rare | destroys every enemy on screen (boss takes 25%) |
| Repair Kit | rare | restores up to 50% of Core integrity |

## 7. Scoring

Score = sum(kill points x live score multiplier) + wave clear (100 + 10 x wave) + on win: Core-left bonus (0.5% of kill score per 1% Core) + time bonus (1 per second under par, par = 40 s x waves). Stars: 1 = win; 2 = win on Captain or above with Core above 50%; 3 = win on Galactic Hero, or with Core above 80%. Endless ranks by waves, then score.

## 8. Co-op scaling

Enemy HP x 1.0 / 1.2 / 1.4 / 1.6 for 1 to 4 players. Pilot Skiffs are free, cannot be sold, are downed at 0 of 60 health (only Behemoth shots hurt them) and respawn after 10 s. Each player earns Scrap from their own kills; gifting is a tap (min 10).

## 9. Tuning plan (done 2026-11-22)

The headless wave simulator exists (`BbBalanceSimulationTest`, test T9): scripted bots buy and upgrade on a map and the assertions are the targets below, with tolerances. Targets: Captain wave 30 winnable by a competent loadout with 60 to 75% Core left; spam-one-bot strategies lose between waves 15 and 22; Insanity unwinnable without all five bot types, and winnable but very hard with a strong mixed build. Hero (ours): the competent loadout wins with 35 to 60% Core left. Cadet: the competent loadout wins with the Core nearly untouched.

## 10. Tuned values

### 10.1 How "Core left" is measured

Core left is the Core integrity (0 to 100) when the run ends, not counting the Aegis shield (a shield still up at the end is reported separately). The reference "competent loadout" is the `ALL_ROUND` script: Picket, Picket, Tender, Lobber, Picket, Haze, Lobber, Skiff, Tender, Picket, Lobber, Skiff, Haze, Lobber, then upgrades to the cheapest bot, with Specials used (Repair Kit at 60% Core or below, Nova on a crowd or a boss, the two multipliers while a wave is on). It buys NO Aegis. `ALL_ROUND_AEGIS` is the same script that also keeps about 120 shield up; it is always at least as safe. In the sim the Core is only lost when a Behemoth leaks or the defence breaks, so the numbers move in steps of one Behemoth leak (20 / 25 / 25 / 35 Core on Cadet / Captain and Hero / Insanity).

### 10.2 What changed (OUR numbers)

| Area | Was | Now | Why |
|---|---|---|---|
| Enemy HP growth | x1.11 per wave, all the way | x1.15 per wave to 30, x1.01 to 40, x1.07 after | One exponential made Captain 30 and Hero 40 contradict each other: the build is maxed by about wave 18, so the curve must flatten after the Captain goal. The steeper start is what makes spam builds fail around wave 20. |
| Behemoth Core damage | leak mult 10 (50 Core on Captain) | leak mult 5 (25 Core on Captain) | A 50-point hit made the Core binary: 100% or dead. One leaked boss now costs a quarter of the Core, which is where the "60 to 75% left" target lives. |
| Lobber power | 60, x1.4 per level (L5 230.5) | 36, x1.12 per level (L5 56.6) | Spam Lobber won Captain with the Core untouched: splash on a dense lane out-scales everything. L1 stays strong enough to open with; the late scaling is what was cut. |
| Lobber splash radius | 0.045 W (0.72 u) | 0.03 W (0.48 u) | Same reason; also makes the blind spot and the fast Darters bite harder. |
| Tender slow | 30 / 38 / 46 / 53 / 60% | 45 / 57 / 69 / 80 / 90% | The one real force multiplier: every other bot's exposure time scales with it. All-round builds (two Tenders) need it to hold waves 20 to 29, spam builds have none. The Behemoth still halves it. |
| Tender freeze period | 6.0 / 5.4 / 4.8 / 4.1 / 3.5 s | 4.2 / 3.8 / 3.4 / 2.9 / 2.5 s | Same. Freeze length stays 0.5 s; the Behemoth still ignores it. |
| Skiff power | 20 (L5 76.8) | 25 (L5 96.0) | Skiff-only died at wave 7 on Hearth; only 3 Skiffs per player can be bought, so it is a 3-bot build and now falls around waves 9 to 14. |
| Hero HP / speed | x1.25 / x1.05 | x1.10 / x1.03 | With the plateau, x1.25 made the competent build bleed on Behemoth 30 plus 40 plus leaks (Hearth 5% Core). |
| Insanity leak / HP / speed | 8 / x1.6 / x1.15 | 7 / x1.15 / x1.06 | Needed to be winnable: the Behemoth leaks twice (30 and 40 = 70 Core) and Hearth is a knife edge. |
| Hearth pace | 1.55 (trav 30 s) | 1.40 (trav 33 s) | Hearth (one long lane, 14 nodes) was consistently the hardest launch map by 20 to 40 Core points. Twin and Pebble are untouched. |
| Unchanged | Picket, Haze, costs, upgrade prices, sell, Aegis, counts, composition, bonuses, kill Scrap, armour (25%), swarms | | The simulation did not need them. |

Code: `BbModel.kt` (BotType gains `powerGrowth`; the tables above), `BbRules.hpGrowth` (the only logic touched: a three-segment growth function instead of one power), `maps.json` (Hearth pace). Snapshots store state, not these numbers, so `BbSnapshot.VERSION` stays 1 and the corruption tests pass unchanged.

### 10.3 Results by tier, map and archetype (seed 7, Specials on; before -> after)

`L24` = lost at wave 24; `win c75` = won with 75% Core left. The same scripts run on the old and the new tables (the "before" column is the old tables under the new, wider script set; All-round with the shield is the old test's `ALL_ROUND`).

**Cadet (goal 20)**

| Archetype | Hearth | Twin | Pebble |
|---|---|---|---|
| Spam Picket | win c100 -> win c80 | win c100 -> win c72 | win c100 -> win c80 |
| Spam Lobber | win c100 -> win c20 | win c100 -> win c100 | win c100 -> win c100 |
| Spam Haze | win c100 -> win c100 | win c100 -> win c100 | win c100 -> win c100 |
| Spam Tender | L2 -> L2 | L2 -> L2 | L2 -> L2 |
| Skiff only | L11 -> L10 | L18 -> L16 | L11 -> L12 |
| All-round (no shield) | win c100 -> win c100 | win c100 -> win c100 | win c100 -> win c100 |
| All-round + Aegis | win c100 -> win c100 | win c100 -> win c100 | win c100 -> win c100 |
| Mixed rotation | win c100 -> win c100 | win c100 -> win c100 | win c100 -> win c100 |
| No upgrades | win c100 -> win c8 | win c100 -> win c72 | win c100 -> win c28 |
| Over-upgrade (4 bots) | win c100 -> win c80 | win c100 -> win c80 | win c100 -> win c80 |
| No Tender, no Skiff | win c100 -> win c100 | win c100 -> win c100 | win c100 -> win c100 |

**Captain (goal 30)**

| Archetype | Hearth | Twin | Pebble |
|---|---|---|---|
| Spam Picket | L24 -> L21 | L27 -> L20 | L27 -> L21 |
| Spam Lobber | win c100 -> L20 | win c100 -> L21 | win c100 -> L21 |
| Spam Haze | L27 -> L22 | L28 -> L21 | L29 -> L23 |
| Spam Tender | L1 -> L1 | L1 -> L1 | L1 -> L1 |
| Skiff only | L7 -> L14 | L12 -> L14 | L9 -> L9 |
| All-round (no shield) | win c100 -> win c75 | win c100 -> win c75 | win c100 -> win c75 |
| All-round + Aegis | win c100 -> win c100 | win c100 -> win c100 | win c100 -> win c100 |
| Mixed rotation | win c100 -> win c75 | win c100 -> win c75 | win c100 -> win c75 |
| No upgrades | L21 -> L19 | L23 -> L19 | L23 -> L19 |
| Over-upgrade (4 bots) | L23 -> L23 | L27 -> L27 | L29 -> L27 |
| No Tender, no Skiff | win c100 -> L22 | win c100 -> L22 | win c100 -> L22 |

**Galactic Hero (goal 40)**

| Archetype | Hearth | Twin | Pebble |
|---|---|---|---|
| Spam Picket | L22 -> L20 | L24 -> L20 | L24 -> L20 |
| Spam Lobber | L37 -> L19 | win c10 -> L20 | win c50 -> L20 |
| Spam Haze | L25 -> L21 | L25 -> L21 | L27 -> L22 |
| Spam Tender | L1 -> L1 | L1 -> L1 | L1 -> L1 |
| Skiff only | L5 -> L5 | L5 -> L5 | L10 -> L8 |
| All-round (no shield) | L37 -> win c50 | L40 -> win c50 | win c20 -> win c50 |
| All-round + Aegis | L40 -> win c100 | win c100 -> win c100 | win c100 -> win c100 |
| Mixed rotation | L37 -> win c50 | L38 -> win c40 | win c30 -> win c50 |
| No upgrades | L20 -> L18 | L22 -> L18 | L21 -> L18 |
| Over-upgrade (4 bots) | L21 -> L22 | L20 -> L25 | L25 -> L25 |
| No Tender, no Skiff | L30 -> L21 | L33 -> L21 | L33 -> L21 |

**Insanity (goal 40)**

| Archetype | Hearth | Twin | Pebble |
|---|---|---|---|
| Spam Picket | L20 -> L20 | L20 -> L20 | L20 -> L20 |
| Spam Lobber | L30 -> L17 | L37 -> L19 | L37 -> L18 |
| Spam Haze | L20 -> L21 | L20 -> L20 | L24 -> L21 |
| Spam Tender | L1 -> L1 | L1 -> L1 | L1 -> L1 |
| Skiff only | L1 -> L1 | L1 -> L1 | L1 -> L1 |
| All-round (no shield) | L32 -> win c9 | L30 -> win c30 | L35 -> win c30 |
| All-round + Aegis | L35 -> win c100 | L38 -> win c100 | L38 -> win c100 |
| Mixed rotation | L30 -> win c30 | L34 -> L40 | L37 -> win c30 |
| No upgrades | L16 -> L16 | L19 -> L17 | L18 -> L17 |
| Over-upgrade (4 bots) | L20 -> L20 | L20 -> L24 | L22 -> L24 |
| No Tender, no Skiff | L27 -> L20 | L29 -> L20 | L29 -> L20 |

**Endless**

| Archetype | Hearth | Twin | Pebble |
|---|---|---|---|
| Spam Picket | L24 -> L21 | L27 -> L20 | L27 -> L21 |
| Spam Lobber | L39 -> L20 | L43 -> L21 | L44 -> L21 |
| Spam Haze | L27 -> L22 | L28 -> L21 | L29 -> L23 |
| Spam Tender | L1 -> L1 | L1 -> L1 | L1 -> L1 |
| Skiff only | L5 -> L9 | L6 -> L5 | L10 -> L5 |
| All-round (no shield) | L40 -> L47 | L43 -> L50 | L43 -> L48 |
| All-round + Aegis | L42 -> L52 | L45 -> L52 | L45 -> L50 |
| Mixed rotation | L39 -> L49 | L41 -> L47 | L43 -> L50 |
| No upgrades | L21 -> L19 | L23 -> L19 | L23 -> L19 |
| Over-upgrade (4 bots) | L23 -> L23 | L27 -> L27 | L28 -> L27 |
| No Tender, no Skiff | L33 -> L22 | L35 -> L22 | L35 -> L22 |


### 10.4 Targets and tolerances (what the tests assert)

| Target | Source | Band asserted | Result (Hearth / Twin / Pebble) |
|---|---|---|---|
| Captain, competent loadout wins with 60 to 75% Core | this file | win, Core 55 to 80 (seed 7 with Specials, and seeds 1 to 3 with Specials off) | 75 / 75 / 75 |
| Captain, spam Picket / Lobber / Haze lose between waves 15 and 22 | this file | lose in waves 15 to 23 (+1 wave tolerance) | 21 / 20 / 21; 20 / 21 / 21; 22 / 21 / 23 |
| Captain, Skiff-only, no-upgrades and over-upgrade lose | design | Skiff 6 to 22, no-upgrades 12 to 22, over-upgrade 15 to 29 | 14 / 14 / 9; 19 / 19 / 19; 23 / 27 / 27 |
| Captain without Tender and Skiff loses | design | lose in waves 18 to 27 | 22 / 22 / 22 |
| No single exploit (spam any bot, no upgrades, 4 maxed bots) wins Captain, Hero or Insanity | design | all lose | all lose |
| Hero, competent loadout wins with 35 to 60% Core | ours | win, Core 30 to 60 (Specials off) and 30 to 65 (seed 7 with Specials) | 50 / 50 / 50 (Specials off: 35 / 50 / 50) |
| Hero, spam builds lose, no-Tender-no-Skiff loses | design | lose in waves 15 to 23 and 18 to 27 | 19 to 22; 21 / 21 / 21 |
| Insanity, strong mixed build (all five bot types) wins, very hard | this file and the owner brief | win, Core 5 to 35 (seed 7); also wins on seeds 1 to 3 | 9 / 30 / 30 |
| Insanity unwinnable without all five bot types | this file | no-Tender-no-Skiff loses in waves 14 to 27; no-upgrades loses before 25 | 20 / 20 / 20; 16 / 17 / 17 |
| Cadet, competent loadout wins with the Core nearly untouched | ours | win, Core 85 or more | 100 / 100 / 100 |
| Endless always ends | ours | the competent loadout ends between waves 41 and 60 | 47 / 50 / 48 |

### 10.5 Honest limits

- The results are one seed (7) of a deterministic script, and the Core is quantised: with Specials off Captain gives 75 on every map and seed because exactly one Behemoth leaks (wave 30) and nothing else does. With Specials on, a lucky Repair Kit can bring a Hero or Insanity run back up (Hero seeds 1 to 3: 90 to 100). Real players will land anywhere on the same ladder.
- Insanity on Hearth without Specials loses at wave 40 (Nova and Repair Kit are part of the intended answer). Insanity with the Aegis shield kept up wins with the Core at 100: the shield is the safety net that makes "very hard" fair.
- Skiff-only (3 bots) falls well before the spam builds on purpose: Skiffs are capped at 3 per player.
- The late multi-lane maps (Pinwheel, Tidal Lock) are harder than the launch maps for the naive scripts (all-round with the shield loses there at waves 25 (Pinwheel) and 27 (Tidal Lock) on Captain; it won before the retune). They unlock at 12 and 16 stars and were already the hardest at baseline (Pinwheel spam Picket fell at wave 4); the node ranking of the script is not lane-aware, so this is not tuned here.
- Co-op (HP x1.2 to x1.6 per extra player), the swarm size and the Courier are untuned: the sim is single player.
- `docs/design/canvas/ZSWeaponsEnemies.dc.html` still carries the old tables; regenerate it from `BbModel.kt`.

### 10.6 Tuning log

| # | Change | Effect observed (Captain unless noted) |
|---|---|---|
| 0 | Baseline | All-round wins with 100%; spam Lobber wins; spam Picket 24 to 27; spam Haze 27 to 29; Skiff-only 7 to 12; Insanity never won (best wave 38) |
| 1 | HP growth 1.11 -> 1.13 / 1.15 (flat to 30) | Spam falls at 20 to 24 but all-round dies at wave 30 on Hearth: the Behemoth soaks fire and the lane floods behind it (Core 100 -> 0 inside a 7% window) |
| 2 | Lobber power x0.7 down to x0.25 | Spam Lobber still won on Twin and Pebble until x0.35; x0.25 killed it on Hearth at wave 3 (early game too weak): power alone cannot separate early from late |
| 3 | Per-type power growth (Lobber 1.12), L1 power 36, splash 0.03 W | Spam Lobber falls at 20 to 21 on all three maps; early waves fine |
| 4 | Behemoth leak 10 -> 6 -> 5 | The Core stops being binary; one boss leak at wave 30 = Core 75 on every launch map |
| 5 | Tender slow x1.2 / x1.35 / x1.5 with freeze x0.85 / 0.8 / 0.7 | x1.2: all-round dies at 28 to 30; x1.35: dies at 30 on 2 of 3 maps; x1.5 and x0.7: wins with Core 75 on all three |
| 6 | Picket and Haze power x0.75, Plater armour 0.65 | Tried, then REVERTED: not needed once the HP growth (1.147, then 1.15) did the same job; keeps the old Picket and Haze tables |
| 7 | HP growth plateau (1.01 from 31 to 40, 1.07 after) | Hero all-round wins instead of dying at wave 30; Endless ends around 47 to 52 |
| 8 | Hero HP x1.25 -> 1.1, speed 1.05 -> 1.03 | Hero Hearth Core 5 -> 50 |
| 9 | Insanity HP 1.6 -> 1.15, speed 1.15 -> 1.06, leak 8 -> 7 | From never won (waves 30 to 35 with the all-round script) to a win with 9 / 30 / 30 |
| 10 | Hearth pace 1.55 -> 1.4 (1.35 and 1.3 tried) | Insanity Hearth no longer a coin flip; Captain and Hero unchanged |
| 11 | Skiff power 20 -> 25 | Skiff-only 7 / 12 / 9 -> 14 / 14 / 9; all-round unchanged |
