# Bot Bastion: balance tables (ours)

Status: DESIGN DRAFT, 2026-11-22. Every number here is OUR balance. Research (RESEARCH.md) gave only a few anchors: 4 and 5 damage per enemy hit by tier, +25% easy cash, about 20 weak enemies in the first level, a swarm every 5th level on Hard, a shield price that rises per purchase, a 50% heal, the specials list. Everything else is invented to match that structure and must be tuned with a JVM simulation before art lock. The same data feeds `docs/design/canvas/ZSWeaponsEnemies.dc.html`.

Units: W = arena width. Range is a fraction of W (0.20 W = 3.2 u on the 16 u wide arena). Speed is in W per second at pace 1.0 (maps then multiply by `pace`).

## 1. Bots

Level formulas: power(L) = power1 x 1.4^(L-1); range(L) = range1 x (1 + 0.08 (L-1)); rate(L) = rate1 x (1 + 0.10 (L-1)). Upgrade price from L to L+1 = cost x [0.7, 1.05, 1.6, 2.4], rounded to 5. Sell refund 75% of everything invested (100% for 3 s after placing).

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

Long range, big hit, slow reload, cannot hit what is right beside it. splash 0.045 W; blind spot 0.08 W.

| Lv | Power | Range (W) | Rate /s | DPS | Upgrade to next | Total invested |
|---|---|---|---|---|---|---|
| 1 | 60.0 | 34.0% | 0.45 | 27.0 | 75 | 110 |
| 2 | 84.0 | 36.7% | 0.5 | 41.6 | 115 | 185 |
| 3 | 117.6 | 39.4% | 0.54 | 63.5 | 175 | 300 |
| 4 | 164.6 | 42.2% | 0.59 | 96.3 | 265 | 475 |
| 5 | 230.5 | 44.9% | 0.63 | 145.2 | - | 740 |

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
| 1 | 20.0 | 16.0% | 3.0 | 60.0 | 120 | 170 |
| 2 | 28.0 | 17.3% | 3.3 | 92.4 | 180 | 290 |
| 3 | 39.2 | 18.6% | 3.6 | 141.1 | 270 | 470 |
| 4 | 54.9 | 19.8% | 3.9 | 214.0 | 410 | 740 |
| 5 | 76.8 | 21.1% | 4.2 | 322.7 | - | 1150 |

### Tender (Support), cost 80

Does no damage. Slows enemies and freezes them for a moment. slow 30% (L1) to 60% (L5); brief freeze.

| Lv | Slow | Range | Freeze every | Upgrade to next |
|---|---|---|---|---|
| 1 | 30% | 18.0% W | 6.0 s (0.5 s) | 55 |
| 2 | 38% | 19.4% W | 5.4 s (0.5 s) | 85 |
| 3 | 46% | 20.9% W | 4.8 s (0.5 s) | 130 |
| 4 | 53% | 22.3% W | 4.1 s (0.5 s) | 190 |
| 5 | 60% | 23.8% W | 3.5 s (0.5 s) | - |

### Aegis (Core shield), cost 100

Adds 40 shield points to the Core (spent before Core integrity, no regeneration). Price rises x1.25 on every purchase in a run: 100, 125, 156, 195, 244, 305, 381, 476. Matches the researched rising-price behaviour; the exact curve is ours.

Target priority per bot (default First): First, Last, Weakest, Strongest, Closest. Researched: a per-bot priority such as weakest or strongest exists in the source; First, Last and Closest are our additions.

Armour: Plater takes 25% less damage from Picket and Haze (not from Lobber splash or Skiff). Behemoth halves slow strength and ignores freeze.

## 2. Enemies

HP at wave w = base x 1.11^(w-1) x tier hp multiplier. Count at wave w = min(90, 20 + 3 (w-1)); wave 1 = 20 weak enemies (anchor from sources). Every 5th wave adds 1 Courier; waves 10, 20, 30, 40 add a Behemoth boss.

| Enemy | HP | Speed W/s | Points | Scrap | Leak mult | Note |
|---|---|---|---|---|---|---|
| Drifter | 30 | 0.06 | 10 | 6 | 1 | Plain rock-ship. The bulk of every wave. |
| Darter | 18 | 0.125 | 14 | 7 | 1 | Fast and fragile. Punishes slow reloads. |
| Plater | 120 | 0.045 | 35 | 18 | 2 | Armoured: takes 25% less from Picket and Haze. |
| Splitter | 60 | 0.058 | 25 | 10 | 1 | Breaks into two Bits (hp 22) when killed. |
| Courier | 80 | 0.1 | 60 | 25 | 0 | Special carrier. Kill it before it escapes for a Special item. |
| Behemoth | 1500 | 0.03 | 600 | 250 | 10 | Boss on every 10th wave; shrugs off slows by half. |

Core damage per leak = tier leak damage x enemy leak multiplier (Drifter 4 on Cadet, 5 on Captain and Hero, 8 on Insanity). Core integrity 100.

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
| 2 | 23 | 1.11 | 33 | 133 | 30 | 120 |
| 5 | 32 | 1.52 | 46 | 182 | 45 | 150 |
| 10 | 47 | 2.56 | 77 | 307 | 70 | 200 |
| 15 | 62 | 4.31 | 129 | 517 | 95 | 250 |
| 20 | 77 | 7.26 | 218 | 872 | 120 | 300 |
| 30 | 90 | 20.62 | 619 | 2475 | 170 | 400 |
| 40 | 90 | 58.56 | 1757 | 7027 | 220 | 500 |

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
| Galactic Hero | 5 | x1.0 | x1.25 | x1.05 | 5 | 40 | anchored: 5 dmg, swarm every 5 |
| Insanity | 8 | x0.85 | x1.6 | x1.15 | 3 | 40 | name from remake; rules ours |
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

## 9. Tuning plan

Build a headless wave simulator in the engine test source set (SCOPE-SCALE-SCRIPT.md, test T9): bots auto-placed by scripted strategies (spam Picket, Lobber at the merge, Haze + Tender lane, Skiff only). Targets: Captain wave 30 winnable by a competent loadout with 60 to 75% Core left; spam-one-bot strategies lose between waves 15 and 22; Insanity unwinnable without all five bot types.

