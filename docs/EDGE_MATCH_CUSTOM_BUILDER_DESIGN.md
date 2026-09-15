# Edge Match — Custom Game Builder (Phase 1) — Design

**Status:** Approved by the project owner via a direct scoping choice (see below) — implemented
**Date:** September 2026
**Deciders:** the project owner

## Context

The very last item left unstarted from the entire new-games effort (`docs/NEW_GAMES_BRAINSTORM.md`
and `docs/EDGE_MATCH_DESIGN.md` both name it explicitly) is Edge Match's own deferred "generative
Custom game" builder: multiple tiling geometries (including Penrose), adjustable piece-count
sliders, a constraint-solver-backed generator. Picked up here as the next thing to work on once
the original 25-item roadmap (Minesweeper through Tower Defence) fully shipped.

That original ask bundles three genuinely separate, wildly-different-cost pieces of work:

1. **Configurable parameters** on the existing square-grid mechanic (board size, color count) —
   the exact same generation algorithm already shipped, just player-driven instead of
   tier-driven. Low risk, ships in one sitting.
2. **A second tiling geometry** (e.g. hexagonal) — new adjacency math, new UI, still simple
   random generation. Meaningfully bigger, still tractable in one sitting.
3. **True Penrose/aperiodic tiling** with a constraint-solver-backed generator — a serious,
   open-ended algorithmic problem (aperiodic tiling generation with matching rules is genuinely
   hard), realistically its own ADR before any code, not a same-session build.

Presented to the project owner as a real fork (same category as Edge Match's own original
rotate-vs-swap fork — a decision with wildly different cost depending on the answer, not one to
guess at). **Decision: build (1) only, this pass.** (2) and (3) remain explicitly deferred,
un-promoted future phases — this doc does not re-open them.

## What "Custom Game" means, concretely

Everything about the core mechanic is **unchanged** from `docs/EDGE_MATCH_DESIGN.md`: square
grid, 4 colored edges per tile, tap-to-rotate-90°-clockwise, live match highlighting, solved-by-
construction generation with no uniqueness search. The only new thing is a fourth way to pick a
puzzle's size/color count, alongside the existing EASY/MEDIUM/HARD tiers:

- A **"Custom" tab** next to the difficulty tabs. Selecting it opens a small config panel with
  two integer sliders — **board size** and **color count** — instead of immediately generating a
  board.
- **Bounds**: size `2..10`, colors `2..8` (`EdgeMatchGame.MIN_SIZE`/`MAX_SIZE`/`MIN_COLORS`/
  `MAX_COLORS`). Size 1 is rejected (a 1×1 board has zero interior seams — it's not a puzzle, it's
  always "solved"). Colors below 2 are rejected for the same reason (with a single color every
  edge always matches). The upper bounds are deliberately generous relative to HARD's fixed
  8×8/6 — going past what the built-in tiers offer is the entire point of a "custom" mode — capped
  at 10/8 to keep tiles comfortably tappable on a phone screen and edge colors visually
  distinguishable (see **UI** below on why 8 needed a palette change).
- Values passed to `EdgeMatchGame.startCustomMatch(size, colorCount)` are **clamped**, not
  rejected, into those bounds — a slider UI can never produce an out-of-range value in the first
  place, so this is a defensive floor, not user-facing validation.
- Tapping a tier tab (EASY/MEDIUM/HARD) while a custom game is active switches back to that tier
  cleanly via `EdgeMatchGame.selectDifficultyTier(tier)`, which clears `customConfig` — without
  this, `startMatch()` would keep re-reading the stale custom config and silently ignore the tier
  tap (a real bug this design catches before it ships, not after).

## Generation

**No changes to the algorithm at all.** `generateSolvedGrid`/`scrambleRotations`/`generatePuzzle`
are already generic over `size`/`colorCount` — the tiered version was already, accidentally,
"custom-ready" under the hood. This is exactly why this phase is a same-session build: the actual
generation risk here is identical to what's already shipped and previously reviewed (a single-pass
seam-based construction plus a reject-and-retry "never hand out an already-solved board" guard,
no uniqueness search) — no new adversarial-review pass is warranted for this change, per the
batch's own standing process note to scale review effort to actual algorithmic risk.

## Stats

`EdgeMatchStatsStore` was keyed by `CpuDifficulty.name` (`"EASY"`/`"MEDIUM"`/`"HARD"`) — a closed,
fixed set. A custom game's (size, colorCount) pair is open-ended, so there's no fixed tier to key
a "best" against. Two options considered:

- **Don't track stats for custom games at all.** Simple, but throws away real, meaningful
  progress data — a player who always plays 7×7/5 custom games would get nothing to chase.
- **Key records by the exact configuration** (`"CUSTOM_7x5"`), generalizing the existing
  `Map<String, EdgeMatchRecord>` shape rather than replacing it. **Chosen** — it's a
  backward-compatible, additive change (existing `"EASY"`/`"MEDIUM"`/`"HARD"` keys are untouched
  and still work exactly as before), and it gives a real best-moves/best-time record to any custom
  configuration a player returns to, same as any tier. `EdgeMatchGame.statsKey()` computes the
  right key for whatever match is currently active; `EdgeMatchStatsStore.recordSolve()` now takes
  that string directly instead of a `CpuDifficulty`.

**A real pre-existing bug found and fixed while making this change**: `EdgeMatchScreen`'s
`reportedResult` (which gates the one-time `recordSolve()` call per puzzle) was `remember`'d keyed
on `(s.tiles.size, game.difficulty)` — both of which stay constant across every "New Puzzle" click
within the same tier. That meant only the FIRST solved puzzle per tier per screen visit ever
actually got recorded; every subsequent "New Puzzle" solve in that same tier silently skipped
`recordSolve()` entirely (and the finished panel kept showing the first solve's stale "new best"
flags). Introducing Custom mode's own key would have inherited the exact same gap, so it was
closed directly here: `reportedResult` is now keyed on `(game.statsKey(), s.tiles)` — the tile
list is unique per puzzle generation and only stops changing once a puzzle is solved, so the key
correctly resets on every new puzzle (or a `resetToInitial()`) and stays stable while a solved
board is on screen.

## UI

- **Palette**: `edgePatterns` (the per-color wedge colors) only had 6 entries, sized for HARD's
  own fixed max. Custom mode's `MAX_COLORS = 8` needed 2 more — added `teal` and `slate` to both
  the light and dark palettes, chosen to stay visually distinct from the existing 6 at a glance
  (the entire mechanic depends on that).
- **Config panel**: two `Slider`s (size, color count) with a live "`N×N, C colors`" label and a
  "Start Custom Game" button — deliberately not auto-starting on every slider drag (regenerating
  a full board on every intermediate drag value would be wasteful and janky); the player commits
  once with the button, same as picking a tier is a single deliberate tap.
- **Re-configuring**: tapping the "Custom" tab again while it's already the active tab reopens the
  same panel, pre-filled with the current size/color count — no separate "Edit" icon needed, since
  the tab itself is already the natural, discoverable target once a player wants to change a
  custom game's parameters.

## Deliberate scope cuts (honest MVP, same spirit as every other cut in this batch)

- **No new tiling geometry** (hex, triangular, etc.) — explicitly deferred to a possible future
  phase 2, not silently folded in here.
- **No Penrose/aperiodic tiling, no constraint-solver-backed generation** — explicitly deferred
  further out; genuinely hard, open-ended, and was the real reason this whole ask needed
  fork-resolution before any code was written at all.
- **No continuous/interpolated difficulty scoring** (e.g. a computed "this config is roughly as
  hard as HARD" label) — the config panel shows the raw numbers only; a player judges difficulty
  the same way they would for the fixed tiers, by trying it.
- **No per-custom-config leaderboard/browsing UI** — records are stored and shown only for
  whatever configuration is currently active, same as the tiered records already work; there's no
  new screen to browse "all custom configs I've ever played."

## Build order / next step

Single-pass build (engine + stats store + UI + tests together) — this is a small, additive change
to an already-shipped, already-reviewed engine, not a new module needing its own two-pass rhythm.
