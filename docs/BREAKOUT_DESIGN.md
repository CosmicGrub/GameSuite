# Breakout — Design

**Status:** Approved by the project owner via brainstorming — implemented and shipped as
`games/breakout/BreakoutGame.kt` + `ui/BreakoutScreen.kt` (README Roadmap item 22), with four
revisions from this doc's original call (single-hit bricks, speed-only level progression,
point-in-time collision, no daily route — see **Bricks & scoring**/**Controls & physics** below).
Two further scope cuts (power-ups, motion-tier juice) are explicitly flagged for revisit once the
rest of wave 2 is scoped — not permanent decisions, just not part of this first version.
**Date:** September 2026
**Deciders:** the project owner

## Context

First pick of "wave 2" (the new-games effort's second batch), immediately after wave 1
(Minesweeper through Party Toolkit, README items 14-21) closed out. Per
`docs/NEW_GAMES_BRAINSTORM.md`'s own "Breakout / Brick Breaker" entry: real-time paddle-and-ball
arcade action, reusing `AirHockeyGame`'s existing continuous-physics/frame-stepped-loop
infrastructure and PATTERN (not literal code — the actual collision shapes differ: circle-vs-AABB
against bricks/walls here, vs. `AirHockeyGame`'s own circle-vs-circle against a second paddle).
This is meaningfully lower-risk than Tower Defence's own real-time ask specifically because it's
built on a pattern this codebase has already shipped and verified once (Air Hockey), not a new
one. This doc settles the actual game design (Air Hockey has no genre-equivalent to borrow rules
from — a paddle-and-ball opponent match is a different game entirely from a solo brick-clearing
run) through a round of brainstorming with the project owner.

## Core loop & difficulty

- **Multi-level progression, not a single fixed board**: clearing a level's bricks loads the
  next, harder level (see **Level progression** below) — a "how far can you get" arcade run, not
  a bounded one-board-per-round puzzle the way every solo puzzle in this app's wave 1 works.
- **3 lives, shared across the whole run** (not reset per level) — running out ends the match.
  The genre-standard baseline; ball falling past the paddle costs one life and re-serves (see
  **Ball launch** below), not an instant game over.
- **Difficulty (EASY/MEDIUM/HARD) sets STARTING parameters**, not a bot (there's no opponent AI
  in this genre) — paddle width and ball speed at level 1. Every tier still ramps up level over
  level from its own starting point; the tiers differ in where they start, not whether they
  climb. Same "tune starting parameters, no bot to tune instead" idiom Minesweeper/Sudoku/Color
  Flood already use for their own solo-puzzle difficulty.
- **Level progression — revised to a single lever (ball speed only), not three.** The brick grid
  stays a FIXED 5×8 layout every level (a fresh full grid regenerates on clear); only ball speed
  scales up per level cleared (linear growth, capped so it never becomes literally unreactable).
  Breakout's difficulty is about real-time reflexes rather than reasoning over a bigger state
  space, so a fixed grid + scaling speed was judged sufficient depth for a first version, closer
  to the genre's own classic shape than a growing/varying grid.

## Bricks & scoring

- **Revised to single-hit bricks, not multi-hit.** Every brick breaks in exactly one hit — no HP
  tiers, no color-coded damage state. A real, obvious future addition (per-brick HP + a distinct
  color per remaining hit), not built for this first version — same "honest MVP, add depth later"
  spirit as Party Toolkit's Dice staying d6-only.
- Since there's no HP variance to price, points per brick scale with ROW instead — the top row is
  worth the most (classic Arkanoid/Breakout convention), giving a concrete reason to prefer
  clearing upward rather than picking off the easiest bricks.
- **Stats**: a plain best score (not best score + furthest level) via `BreakoutStatsStore` — this
  genre's "arcade high score" shape, same as Air Hockey's own precedent, rather than the
  two-metric best-moves/best-time shape this app's solo PUZZLE games use.
- **No daily-challenge route** — every other solo game in this batch seeds something genuinely
  random per day (Minesweeper's mines, Sudoku's clue removal, Edge Match's seam colors) so
  "today's puzzle" is a distinct instance worth comparing scores on. Breakout's brick grid is a
  fixed, deterministic layout with nothing to seed — a `-daily` route here would be cosmetic, not
  a real feature, so it's skipped rather than added just for consistency with the other games.

## Controls & physics (reusing Air Hockey's pattern, not its code)

- **Paddle**: drag-controlled, horizontal-only movement confined near the bottom of the play
  area — same `movePlayerPaddle`-style idiom `AirHockeyGame` already uses (finger position maps
  directly to paddle position, clamped to valid range).
- **Ball launch**: the ball rests on the paddle at the start of each life; a tap launches it
  (a fixed-ish upward angle with slight randomization, not a dead-straight launch every time) —
  gives the player a deliberate beat of control before each life starts, standard genre
  convention, rather than the ball just appearing already in motion.
- Same normalized `0f..1f` coordinate system and `tick(dtSeconds)` game loop stepped every frame
  via `withFrameNanos`, matching `AirHockeyGame`'s own shape exactly.
- **Collision — revised to a plain point-in-time circle-vs-rect test, not swept.** Unlike
  `AirHockeyGame.resolvePaddleCollision`'s own tunneling fix (testing against the paddle's whole
  frame-to-frame travel segment), Breakout tests the ball's position directly against each rect
  once per frame — a real, deliberately-examined trade-off, not an oversight: a missed BRICK hit
  here just leaves that brick standing for a later pass, a far lower-stakes failure than Air
  Hockey's own case (a missed PADDLE block there is a directly conceded goal). The tunneling
  window only opens at the `dtSeconds` clamp's 0.05s worst case combined with this game's fastest
  reachable ball speed — ordinary 60fps frames (~0.017s) are nowhere near it, and the speed cap
  was chosen partly to keep that worst case rare, not merely to bound top speed.
- **Same "guard the known degenerate case from the start" discipline, different mechanism than
  Air Hockey's own time-based stall-timeout.** `AirHockeyGame`'s own adversarial review found a
  real degenerate case — a ball settling into a perfectly horizontal bounce loop that never
  naturally resolves. Breakout can hit the identical failure mode (zero vertical velocity,
  bouncing wall-to-wall, touching neither paddle nor any brick), so this is guarded proactively
  too — but via a per-frame floor on the ball's vertical speed fraction (nudging velocity back
  toward vertical the instant a bounce would drop below it) rather than a multi-second timeout.
  Arguably a tighter fix (corrects the condition immediately rather than waiting out a timer), not
  a lesser one — same underlying discipline (a known simplified-physics failure mode gets a named,
  built-in guard, not a rediscovery), a different concrete technique.
- **Monotonic-seq events** for the UI to react to with haptics/sound — `BrickBrokenEvent`,
  `PaddleBounceEvent`, `WallBounceEvent`, `LifeLostEvent` (carries its own `gameOver: Boolean`
  rather than a separate terminal event), `LevelClearedEvent` — same event-driven UI-reaction
  idiom `AirHockeyGame`'s own `PaddleImpactEvent`/`WallBounceEvent`/`GoalEvent`/
  `StaleRallyResetEvent` set already establishes.

## Deliberate scope cuts — flagged for revisit, not permanent

Both cuts below were made to keep this FIRST version scoped, not because either is a bad idea —
explicitly worth reconsidering once the rest of wave 2 (and how much appetite remains for
layering more onto Breakout specifically) is clearer:

- **No power-ups** (wider paddle, multi-ball, slow-ball, extra life, etc.) — a real, well-loved
  part of the genre's identity, but a genuinely separate scope: falling-item physics, effect-
  duration/stacking rules, and new visual/audio feedback all need building, not just one more
  `if` branch. A real candidate for the "revisit" pass this doc's own header flags, especially
  alongside multi-hit bricks — the two would compound well (a power-up worth targeting a specific
  tough brick for, say) if both get picked up together later.
- **No `STANDARD`/`MAXIMUM` motion-tier juice system** (the extra trail/camera-shake/particle-
  burst layer `AirHockeyGame` has) — that game's own KDoc frames the tier split as a deliberate
  exception for that specific game ("most other games in this pass deliberately don't [have
  this]"), not a template every real-time game should replicate. Breakout follows the majority
  precedent (no tiered juice system) for its first version instead.

`GameCategory.ARCADE` — already exists (`AirHockeyGame`'s own category) — no new enum value
needed, unlike Party Toolkit's `GameCategory.UTILITY` addition.

## Build order

Real-time physics with a genuinely new collision shape (circle-vs-AABB, not reused from Air
Hockey directly) was real algorithmic risk, closer to Sudoku's or Connect Four's own nontrivial
engines than to a straightforward state-mutation puzzle. Caught and fixed via real on-device
testing (not just unit tests): an input bug where `changedToDown()` proved unreliable on this
touch path.
