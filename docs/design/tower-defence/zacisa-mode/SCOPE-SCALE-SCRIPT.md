# Bot Bastion: scope, scale, script (effort, tests, tasks)

Status: DESIGN DRAFT, 2026-11-22. Planning estimates, not measurements. Nothing built. Reads with `SPEC.md`, `BALANCE.md`, `MAPS.md` and `docs/design/tower-defence/SCALE.md`.

## 1. Scope slices

| Slice | Content | Ships when |
|---|---|---|
| BB-0 Engine core | Ruleset flag, lanes, Core, bots (5), targeting, waves generator, tiers, scoring; no UI beyond a debug screen | Gate: all JVM tests green |
| BB-1 Playable single player | Arena renderer, HUD, weapon bar, upgrade panel, 3 maps (Hearth Orbit, Twin Gates, Pebble Ring), Cadet / Captain, results screen, stats | First device pass |
| BB-2 Full content | 12 maps, Hero / Insanity / Endless, Specials, Aegis, boss, swarm events, campaign menu with stars and unlocks, local leaderboards, audio and haptics | Beta |
| BB-3 Co-op | Players, pilot Skiffs, gifting, tabletop layout, lobby | After BB-2 |
| BB-4 Polish | Living-world ambient, motion levels, accessibility passes, kid profile, tablet and Fold layouts, score card share | Release |
| BB-5 Later | Sandbox open placement (needs `MAP-MODEL.md` option C), more maps, online boards, themes beyond space | Not scheduled |

## 2. Effort estimate (one engineer, ideal days; planning only)

| Work | Days |
|---|---|
| Engine (BB-0), including tests T1 to T10 | 9 |
| Arena renderer and sprites to Compose (cached layers) | 6 |
| HUD, weapon bar, upgrade panel, banners, next-wave card | 6 |
| Maps data and tooling (12 maps, node validation test) | 3 |
| Menus: hub, campaign, setup, help, leaderboards, results | 7 |
| Stats store, leaderboards, stars, unlocks | 3 |
| Specials, Aegis, boss, swarm | 4 |
| Audio palette (about 20 synthesised sounds, 4 tracks) and haptics wiring | 5 |
| Co-op (input, ownership, layouts) | 8 |
| Ambient motion, motion levels, perf pass | 5 |
| Accessibility passes (TalkBack, one-switch, colour-blind check) | 4 |
| Tablet / Fold / phone layouts | 4 |
| Balance simulator and tuning | 5 |
| QA, device passes, bug fixing buffer (25%) | 15 |
| **Total** | **about 84 days (17 weeks solo; about 9 weeks with two engineers)** |

Confidence: engine and menus are well understood from the existing codebase (+/- 20%); co-op input and layouts are the least certain (+/- 40%).

## 3. Asset scale

| Group | Count | Notes |
|---|---|---|
| Bot sprites | 5 bots x 5 levels (level pips and a glow are code, so 5 base sprites plus 5 upgrade overlays) | board art is vector; derive WebP and `ImageVector` |
| Enemy sprites | 7 (6 types plus Bit) x 4 walk frames | |
| Core states | 4 (intact, 50%, 25%, fallen) | |
| Projectiles and fx | about 14 | |
| Backdrops | 4 sector backdrops + stars/nebula layers | parallax strips |
| Icons | 5 bots, Aegis, 4 specials, 5 target, UI | `ImageVector` |
| Audio | about 20 sfx, 4 loops (sector music) | synthesised, under 600 KB total |
| Maps | 12 data files (lanes, nodes, voids, pace) | JSON or Kotlin objects, about 2 KB each |
| Package size | under 2.0 MB total at Flat + Soft 2.5D | within per-theme budget in `SCALE.md` |

## 4. Test plan: JVM-testable engine pieces (write first)

Pure Kotlin, no Compose, deterministic seeds, fixed-step ticks. Names are suggestions.

| Id | Test | Asserts |
|---|---|---|
| T1 | `BotStatsTest` | Level formulas reproduce the tables in `BALANCE.md` for all bots and levels; upgrade prices; sell refund 75% and 100% undo window |
| T2 | `LaneMovementTest` | Enemy position along multi-lane maps; reaches Core at expected time (`trav` per map within 5%); pace honoured; slow and freeze change timing |
| T3 | `TargetingTest` | First, Last, Weakest, Strongest, Closest pick the expected enemy in crafted layouts; ties stable |
| T4 | `DamageModelTest` | Splash radius and Lobber min range; Haze chip DPS; armour factor for Plater; boss slow resistance; Courier drop |
| T5 | `SkiffPatrolTest` | Rim loop length and lap time; chase and return; deterministic path |
| T6 | `CoreAndShieldTest` | Leak damage by tier and enemy multiplier; shield absorbs first; Aegis price curve; Repair Kit cap at 50% and max; defeat at 0 |
| T7 | `WaveGeneratorTest` | Count `min(90, 20 + 3(w-1))`; hp growth 1.11; composition table; Courier every 5th; boss on 10, 20, 30, 40; swarm on tier interval; same seed same wave |
| T8 | `SpecialsTest` | Multiplier stacking and expiry (30 s, x4 cap); Nova Pulse (boss 25%); inventory cap 3; score and scrap multipliers apply separately |
| T9 | `BalanceSimulationTest` | Headless bots placed by scripted strategies finish or fail in the ranges set in `BALANCE.md` section 9 (Captain wave 30 winnable with Core 60 to 75%; spam strategies fail 15 to 22; Insanity needs all five types). Tuned, not gated tightly (assert bands) |
| T10 | `ScoringAndStarsTest` | Score formula, time par, Core-left bonus, star rules, Endless ordering |
| T11 | `MapDataValidationTest` | For all 12 maps: nodes count 12 to 22, no node within 0.72 u of a lane, none in voids, none within 1.35 u of Core, node spacing at least 1.2 u, lanes end at Core, entries on or beyond the rim |
| T12 | `CoopRulesTest` | Ownership, build rights, upgrade fee and rebate, scrap gifting, hp scaling by player count, pilot downed and respawn |
| T13 | `StatsAndLeaderboardTest` | Top-10 insertion and ordering, key format `bb/$mapId:$tier`, Classic keys untouched, unlock by stars |
| T14 | `ClassicParityTest` | The existing Classic ruleset still passes its current tests unchanged when the new fields are present |
| T15 | `DeterminismTest` | Same seed and inputs produce identical state after N ticks, including co-op inputs |

Compose and device tests: screenshot or semantics tests for the HUD (TalkBack labels), node focus order, 200% font scale; device passes on tablet and Fold per standing rule (no APK installs by Claude: self-run kit or Windows/web verification).

## 5. Task breakdown for a build team

Dependencies in brackets.

1. BB-E1: data model (`TowerDefenceRuleset`, `BotType`, `MapDef`, tiers) and migration shims [none]. Tests T14.
2. BB-E2: lane movement and Core [E1]. T2, T6.
3. BB-E3: bots, targeting, damage, status effects [E2]. T1, T3, T4.
4. BB-E4: Skiff patrol [E3]. T5.
5. BB-E5: wave generator, swarm, boss, Courier [E2]. T7.
6. BB-E6: Specials and Aegis [E3, E5]. T8.
7. BB-E7: scoring, stars, stats store, leaderboards [E5]. T10, T13.
8. BB-E8: maps JSON for 12 maps plus validation tool [E1]. T11. Use `MAPS.md` data verbatim.
9. BB-E9: balance simulator [E3 to E7]. T9.
10. BB-U1: arena renderer with cached layers [E1, P0 recomposition fix].
11. BB-U2: HUD, weapon bar, upgrade panel, banners [U1, E3].
12. BB-U3: menus (hub, campaign, setup, help, leaderboards) [E7, E8].
13. BB-U4: results and score card [E7].
14. BB-U5: audio and haptics [E3, U2]; synthesised palette.
15. BB-E10: co-op rules [E3]. T12, T15.
16. BB-U6: co-op UI, lobby, tabletop layout [E10, U2].
17. BB-U7: ambient motion and motion levels [U1].
18. BB-U8: accessibility passes [U2, U3].
19. BB-U9: tablet, Fold, phone layouts [U2].
20. BB-Q: device passes and balance tuning [all].

Parallelisable: E8 and U1 early; U3 and U4 once E7 lands; audio any time after E3.

## 6. Out of scope (here)

Online or Nearby multiplayer, user-made maps, real-time 3D, paid content, replays, procedural maps, anything quoting the source game's art, names, audio or layouts.

## 7. Script (moments to storyboard)

Storyboards for three moments are on `ZSLivingWorld` (wave start, Nova Pulse, shield hit and Core damage). Others to add in BB-4: boss arrival (horn, banner, camera pull), Courier escape, win, defeat, co-op join. Copy deck (EN first, ES later): HUD labels in the boards; defeat tips per leaking enemy type (Drifter: "Add a Picket near the entry."; Darter: "Try a Skiff or a Haze for fast ones."; Plater: "Armoured. Use Lobber or Skiff."; Splitter: "Splash from a Lobber clears the Bits."; Courier: "It escaped. Put a Tender at the exit."; Behemoth: "Save Nova Pulse and stack Lobbers.").
