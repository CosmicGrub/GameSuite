# ADR-2: Rendering/Update Architecture for Tower Defence

**Status:** Accepted
**Date:** September 2026
**Deciders:** the project owner

## Context

Tower Defence is the one item on the whole new-games roadmap explicitly flagged — in
`docs/NEW_GAMES_BRAINSTORM.md`'s own entry, repeated in project memory — as needing "its own
future ADR-scoped decision" before implementation starts, rather than being scoped the same way
as every other game in this batch. The concern, verbatim from that entry: real-time enemy waves,
pathing, tower placement/targeting, and an economy loop are "a different genre of build than
everything else in this catalog," and Tower Defence specifically has "an order of magnitude more
moving parts — enemies, projectiles, towers, path-following — at once" than this app's existing
real-time precedent (`AirHockeyGame`'s frame-stepped physics), raising the question of whether
Jetpack Compose's declarative-recomposition model comfortably scales to that, or whether "the one
place a lighter-weight canvas/game-loop library earns its way in" is warranted instead.

This ADR settles that specific question — architecture only, not gameplay design (tower types,
wave balance, economy tuning, pathing algorithm choice are a separate, later design pass once
this is resolved) — the same way `docs/ENGINE_DECISION.md` settled the platform/engine question
for the PC port: by direct inspection of the real code and a real measurement, not by assumption.

## What direct inspection of the real code actually shows

`AirHockeyGame`/`AirHockeyScreen` and `BreakoutGame`/`BreakoutScreen` (this app's only two
existing real-time games) do **not** use the naive pattern the "declarative recomposition" concern
worries about — many individual composables, each with its own `mutableStateOf`, each recomposing
independently as its own entity moves. Both instead use one uniform shape:

- **One state object** (`AirHockeyState`/`BreakoutState`) holding every entity — puck, both
  paddles; or ball, paddle, and the full brick grid.
- **One `tick(dtSeconds)` function**, called once per frame from a `withFrameNanos` loop, that
  updates the whole state object in plain Kotlin — physics, collision, scoring, all of it.
- **One `Canvas`**, redrawn each time that one state object changes, with every entity drawn via
  plain imperative `drawCircle`/`drawPath`/etc. calls inside its `DrawScope` — not a tree of many
  separate composables.

This is architecturally much closer to what a dedicated lightweight canvas/game-loop library would
give you than to Compose's own normal UI-tree paradigm — `Canvas`'s `DrawScope` is genuinely
immediate-mode drawing (Skia-backed), and one state-object update triggers exactly one
recomposition of one `Canvas`, not N. `BreakoutGame` already runs this exact pattern against 40
simultaneously-tracked bricks, checked for collision every single frame, in shipped, on-device-
verified production code today.

## The real measurement

Rather than reason from the code shape alone, a temporary diagnostic pilot (`TowerDefenceStressTestScreen`,
removed after this measurement — see **Action Items**) was built and run on the connected physical
device (Galaxy Tab S9 FE, the same device `docs/ENGINE_DECISION.md`'s own verification used),
mirroring `AirHockeyGame`/`BreakoutGame`'s exact "one state blob, one `tick()`, one `Canvas`"
shape, deliberately stress-tested well beyond any realistic single-wave Tower Defence scenario:

- **100 simultaneously-moving enemies** (path position updated every frame)
- **30 towers**, each doing a **naive O(towers × enemies) nearest-in-range target scan every
  single frame** (no spatial partitioning, no caching — the worst-case-shaped approach, not an
  optimized one)
- **Up to 60 live projectiles** at once, each moving toward its target and checked for impact
  every frame
- All 130+ entities drawn via real `Canvas` `drawCircle` calls every frame

**Result, 599 real frames measured on-device:**

| Metric | Value |
|---|---|
| Average frame time | 11.13ms |
| p95 frame time | 11.11ms |
| Max frame time | 22.15ms (early JIT-warmup/composition-startup, not sustained) |
| Frames over the 60fps (16.67ms) budget | 3 / 599 (0.5%) |

Comfortably under budget on average, with the rare over-budget frame explained by startup
warmup rather than an ongoing cost that would compound at scale. This is already a materially
larger and more naively-implemented entity count than a real Tower Defence wave is likely to need
(genre-typical peak concurrent enemy counts on mobile are usually in the 20-50 range, well below
this pilot's 100, and a real implementation would add spatial partitioning for target acquisition
long before naive O(towers × enemies) became the bottleneck it already isn't here).

## Decision

**Stay on Jetpack Compose — `Canvas` + `withFrameNanos`, the same pattern `AirHockeyGame`/
`BreakoutGame` already use in production — for Tower Defence's real-time loop. No new
engine, rendering library, or game-loop dependency.**

The brainstorm doc's own concern was a reasonable one to raise and verify rather than dismiss
unread, but the premise it worried about — Compose's *declarative UI-tree* recomposition model
applied naively to many independent entities — does not describe how this app's own real-time
games are actually built, and the real on-device measurement confirms the pattern they do use
holds up well past the scale Tower Defence realistically needs.

## Options considered (briefly — the evidence above made this a short list, not five candidates)

| Option | Verdict |
|---|---|
| **Compose `Canvas` + `tick()`** (this decision) | Proven pattern, proven on-device headroom, zero new dependencies, full reuse of this app's existing shell/menu/settings/stats integration `docs/ENGINE_DECISION.md` already established as valuable to keep. |
| **A lightweight external canvas/game-loop library** | No evidence of a real bottleneck to justify it. Would add a new dependency, a new rendering integration surface with the rest of the app's Compose-based shell (menu, settings, `AdaptiveTwoPane`, theming), and — per `docs/ENGINE_DECISION.md`'s own KMP/CMP decision — a new thing to re-verify on the Desktop target too, all to solve a performance problem the measurement above shows doesn't exist at realistic scale. |
| **A full game engine (Unity/Godot)** | Already dispatched by `docs/ENGINE_DECISION.md`'s own reasoning for the exact same reasons that decision gave (discards this codebase's portable Kotlin logic layer, no Nearby/fold-layout equivalent) — nothing about Tower Defence's own entity count changes that calculus, and this measurement shows it isn't even needed to hit a comfortable frame budget. |

## Consequences

**Easier:**
- Tower Defence's gameplay design (the actual next step — tower types, wave/economy design,
  pathing algorithm) can proceed as an ordinary `GameModule`, the same shell integration every
  other game in this batch gets, with no separate rendering-stack onboarding cost.
- The existing `AirHockeyGame`/`BreakoutGame` tick-loop idiom (state object + `tick(dtSeconds)` +
  `Canvas`) is directly reusable as the concrete pattern to build against, not just an analogy.

**Worth naming honestly:**
- This ADR validates the *rendering/update* architecture, not gameplay performance under the
  actual final design — a real pathfinding algorithm (if enemies need to navigate a player-
  editable maze rather than a fixed path) adds real per-enemy cost this pilot's simple lerp-along-
  a-fixed-path movement didn't exercise, and should get its own lighter validation once that
  design question is settled, rather than assuming this measurement covers it too.
- The stress test's 60 towers all had a live target and fired on cooldown continuously — a real
  game's early waves (fewer, cheaper towers) will be well under this pilot's load; only a very
  late, heavily-upgraded board would approach it.

## Action items

1. **Done as part of this ADR**: the diagnostic pilot (`TowerDefenceStressTestScreen`, wired
   temporarily as the app's own start destination for one measurement run) has been removed —
   `git status` confirms zero trace in the working tree, `MainActivity.kt` reverted to its exact
   prior state. This was a throwaway architecture-validation artifact, not a piece of the eventual
   game.
2. **Next step**: a separate gameplay-design brainstorming pass (tower types/upgrades, wave and
   economy design, the pathing approach — fixed path vs. player-editable maze — and difficulty
   tiers), producing its own `docs/TOWER_DEFENCE_DESIGN.md` the same way Edge Match/Nonogram/
   Kakuro each got, now that the architecture question this doc exists to answer is settled.
