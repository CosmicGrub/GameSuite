# Tower Defence — Gameplay Design

**Status:** Approved by the project owner via brainstorming, ready for an implementation plan
**Date:** September 2026
**Deciders:** the project owner

## Context

Last item on the original new-games roadmap (`docs/NEW_GAMES_BRAINSTORM.md`), after Minesweeper
through Kakuro. This doc settles GAMEPLAY only — the rendering/update architecture question was
already settled in `docs/TOWER_DEFENCE_ADR.md` (stay on Compose `Canvas` + `withFrameNanos`, the
same tick-loop pattern `AirHockeyGame`/`BreakoutGame` already use in production, validated by a
real on-device stress test at 130+ entities). That ADR's own "Next step" explicitly pointed to
this doc. Settled through a round of brainstorming with the project owner — every decision below
was a real fork with trade-offs, matching the same depth every other game in this batch got.

## Pathing & levels

- **Fixed path, not a player-editable maze.** Enemies follow a single predetermined route across
  the board (the classic Kingdom Rush/Bloons TD shape); towers are placed in designated zones
  adjacent to the path, never blocking it. No real-time pathfinding is needed at all — enemy
  movement is the same "lerp along a known path" shape `docs/TOWER_DEFENCE_ADR.md`'s own stress
  test already validated on-device. A player-editable maze (towers as walls, real BFS/A*
  pathfinding re-run on every placement, map-never-fully-sealed validation) was explicitly
  considered and rejected as a genuinely bigger, separately-risky technical lift the ADR's own
  measurement didn't cover — not a lesser subgenre, a different and heavier scope.
- **3-5 hand-designed fixed level layouts** (path shape, terrain, tower-placement zones) — no
  procedural level generation. The same "fixed template, don't attempt full procedural
  generation" idiom `docs/KAKURO_DESIGN.md`'s own run-topology decision just established for an
  analogous "this half of generation is a real design skill, not an algorithm" problem. Replay
  value comes from wave composition and difficulty, not from a different map shape every time.

## Difficulty

EASY/MEDIUM/HARD scales enemy HP/speed/count and starting gold — applied to whichever level the
player picks. **Starting lives stay CONSTANT across all three tiers** — only enemy stats and
economy scale; difficulty makes the waves themselves harder to survive, not the margin for
error before losing narrower on top of that. **Level selection is a separate choice from
difficulty, not conflated with it** — a
deliberate departure from this batch's usual "difficulty picks a specific board size" idiom
(Minesweeper/Nonogram/Kakuro all scale board size per tier), since Tower Defence's levels are
fixed, hand-designed maps rather than procedurally-sized boards; scaling wave stats/economy is
the genre-standard "difficulty mode" real Tower Defence games actually use, and it composes with
level choice rather than replacing it (any level can be played at any difficulty).

## Towers & economy

- **Exactly 1 tower type for this first version**, upgradeable — spending gold on a placed tower
  boosts its damage/range. Real strategic depth even at minimum variety: build wide (more towers)
  vs. upgrade what's already placed vs. save for later. **Explicitly flagged for revisit, not a
  permanent ceiling**: expand to 3 tower types (e.g. a splash/AOE tower and a slow/debuff tower
  alongside the base single-target one) once this first version ships and is played — the
  project owner's own call to validate the core loop lean before adding variety, the same
  "ship the minimal real loop, decide whether to expand from evidence" instinct this whole
  project already applies elsewhere (e.g. Party Toolkit's Dice staying d6-only, Breakout's
  power-ups deferred pending the rest of wave 2's own appetite).
- **Gold earned per enemy kill**, not per wave — standard genre convention, immediate per-action
  feedback rather than a lump sum at wave end.
- No branching upgrade trees, no multiple upgrade tiers per tower — one upgrade action per
  placed tower (cost → stronger stats), matching the "exactly 1 tower type" scope's own
  proportionate depth.

## Win/loss & session shape

- **Lives**: how many enemies have reached the end of the path without being killed. Survive a
  fixed number of waves with lives still above 0 → the level is won. Lives reach 0 → the run
  ends.
- **A real in-game Pause**, not Air Hockey/Breakout's empty no-op `pause()`/`resume()` (which
  only ever handle app-backgrounding, since neither of those games has a genre expectation of
  pausing mid-play). Tower Defence's own strategic, think-under-pressure pacing makes an actual
  "freeze the simulation, review the board, place/upgrade towers" pause a real genre expectation,
  not a nicety. This needs real engine support: a `paused: Boolean` in engine state that `tick()`
  checks and returns early on (distinct from `matchOver`, which ends the whole session, and
  distinct from a per-run "lost/won" flag, which ends just that run) — a genuinely different
  shape from every other real-time game's own `pause()` in this app, not a reuse of the existing
  idiom.
- **Stats**: furthest wave survived, tracked per (level, difficulty) combination —
  `TowerDefenceStatsStore`, the same "arcade high score" shape `BreakoutStatsStore` already
  established for this batch's other non-puzzle real-time game, adapted from points to waves
  since Tower Defence's own skill signal is "how far did you get," not a point total. Updates on
  EVERY run regardless of win or loss — same "best attempt ever, not just best completed run"
  idiom `BreakoutStatsStore`'s own best-score already follows (a run that loses on wave 6 after a
  previous best of wave 4 is still a new best, even though it wasn't a win).
- Standard solo-puzzle `GameModule` shape otherwise: `SINGLE_PLAYER_VS_BOT` only — there's no bot
  to speak of here either (the "opponent" is the wave design, not an AI player), same idiom every
  other solo game in this app already follows. `matchOver` guard on the gameplay-mutating
  methods, built in from the start — the same now-well-known bug pattern every prior engine in
  this batch has needed, applied proactively this time rather than found after the fact.

## Deliberate scope cuts (honest MVP, same spirit as every other game's own documented cuts)

- **1 tower type, not 3** — see **Towers & economy** above; the project owner's own explicit
  "start lean, decide from evidence" call, not an oversight.
- **No branching upgrade trees** — one upgrade action per tower, not multiple tiers or paths.
- **No player-editable maze / pathfinding** — see **Pathing & levels** above; a real, separately-
  scoped future direction if ever wanted, not assumed or silently folded in.
- **No procedural level generation** — see **Pathing & levels** above.
- **No enemy variety beyond stat scaling** — waves get harder via difficulty's own HP/speed/count
  scaling (see **Difficulty**), not via genuinely distinct enemy TYPES (e.g. flying enemies,
  armored enemies immune to certain damage types) — a real, separate depth axis left for later,
  proportionate to the same "1 tower type first" lean-scope decision.

## Build order / next step

Real-time gameplay logic (wave spawning/timing, per-enemy path-progress + HP, per-tower
range/cooldown/targeting, projectile flight, the pause-aware `tick()`) is genuinely new code, not
reused from `AirHockeyGame`/`BreakoutGame` beyond the proven tick-loop SHAPE — the implementation
plan should budget real unit-test coverage of the wave/economy math and a real on-device
playtest (this batch's own established "install and actually play it" verification standard,
not just unit tests) before calling this done, same rigor every other game here has gotten.

Next step: hand this doc (plus `docs/TOWER_DEFENCE_ADR.md` for the architecture context) to
`writing-plans` (or equivalent) to produce a concrete implementation plan — the 3-5 hand-authored
level layouts (path + terrain + tower zones), the `TowerDefenceGame`/`TowerDefenceState` engine
(wave spawning, tick loop, tower/enemy/projectile logic, the pause flag), `TowerDefenceStatsStore`,
unit tests, then the UI screen (Canvas-drawn path/towers/enemies/projectiles, a level-select
screen, the pause control) + menu/route/strings wiring.
