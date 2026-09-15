# Edge Match — Custom Game Builder — Design

**Status:** Phase 1 (configurable square grid) approved and implemented; Phase 2 (hex geometry)
approved and implemented — see that section below for its own scoping.
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

---

## Phase 2: Hex geometry

**Status:** Approved by the project owner ("yes, go ahead with the second geometry", after Phase
1's own scoping question named this as the recommended next step) — implemented.

### Scope

The second of the three pieces Phase 1's own Context section identified: a hex-grid variant of
the same rotate-in-place edge-matching mechanic, reachable **only through Custom mode** — the
fixed EASY/MEDIUM/HARD tiers stay square-grid-only, untouched. A geometry toggle (Square/Hex)
joins the two existing sliders in the Custom panel. Same bounds apply to both geometries (size
`2..10`, colors `2..8`) — no new reason to give hex its own range.

**Everything else about the mechanic is unchanged**: tap-to-rotate in place, live match
highlighting, solved-by-construction generation with no uniqueness search, border-facing edges
unconstrained/cosmetic. Only the tile SHAPE and neighbor topology differ.

### Board shape: rhombus, not a "hexagon of hexagons"

A true hexagonal-OVERALL-shape board (the classic "big hexagon made of little hexagons") needs a
different tile count per row — row lengths grow then shrink — which would break the simple `size
× size` slider semantics and the flat 2D array every other geometry in this app uses. Instead:
a **rhombus (parallelogram) grid using axial coordinates** `(q, r)`, both ranging over `0 until
size` — exactly `size × size` tiles, same as square, same flat row-major array, same slider
meaning. This is the same "honest MVP" trade Edge Match's own rotate-vs-swap and Tower Defence's
fixed-paths cuts made: the visually "cleaner" hexagonal overall shape is real, avoidable
complexity that buys nothing for the puzzle's actual difficulty or fairness.

### Tile shape and rotation

A hex tile has **6 edges** (not 4), so `EdgeMatchTile` was generalized from a hardcoded `% 4` to
`% canonicalEdges.size` throughout (`currentEdge`/`rotatedClockwise`) — a fully backward-compatible
change, since a 4-edge square tile's own modulus is still 4. `rotation` for a hex tile ranges
`0..5` (60° steps) instead of `0..3` (90° steps); `tapTile()` and the move-counting logic are
completely unchanged, since they only ever call `rotatedClockwise()` and never hardcode "4."

### Neighbor topology

Axial-coordinate hexes have 6 neighbors at fixed offsets, named here to match compass-ish
intuition (not geographic accuracy): `E=(+1,0)`, `NE=(+1,-1)`, `NW=(0,-1)`, `W=(-1,0)`,
`SW=(-1,+1)`, `SE=(0,+1)` — indices 0-5 in that order. Each direction's opposite is `(d+3) % 6`
(E↔W, NE↔SW, NW↔SE) — the same "opposite pairs are half a turn apart" shape square's TOP↔BOTTOM/
RIGHT↔LEFT (`(d+2) % 4`) already has, just for a hexagon's 3 opposite pairs instead of a square's
2. A neighbor is only real if both its `q` and `r` land inside `0 until size` — exactly the same
bounds check as square's row/col, just against 2 coordinates instead of row-only or col-only.

### Generation: one seam-assignment pass, not two arrays

Square's generator uses two explicit seam arrays (`horizontalSeam`/`verticalSeam`) because a
square tile's 4 neighbors split cleanly into "the one above" and "the one to the left" when
walking row-major. A hex tile's 6 neighbors don't split as cleanly, so the generator instead walks
every tile once and, for exactly 3 of its 6 directions (`E`/`NE`/`NW` — one from each opposite
pair), either reads that neighbor's already-assigned color or assigns a fresh one and mirrors it
into the neighbor's own opposite-direction slot. Because every direction's opposite is in the
complementary 3-direction set (`W`/`SW`/`SE`), this single pass — walking only `E`/`NE`/`NW` per
tile — visits every interior seam in the whole grid exactly once, from exactly one side, with the
other side's slot filled in as a side effect. A final pass fills any slot still empty after that
(a tile with no real neighbor in that direction) with an independent random color, exactly like
square's own border-edge fill. Verified in tests the same way square's generator is: an
independent, from-scratch re-derivation of "is every tile's rotation-0 arrangement actually
solved," never trusting the engine's own solved-check to grade itself.

### Rendering

Hex tiles render as true 6-sided polygons (pointy-top orientation — vertex at top/bottom, flat
sides facing E/NE/NW/W/SW/SE), each split into 6 triangular wedges from center to each edge's pair
of corners, the same "wedge = one edge's color, meeting at center" idiom the square tile view
already uses, just with 6 wedges instead of 4. Board layout uses the standard axial→pixel
formula (`x = size × √3 × (q + r/2)`, `y = size × 1.5 × r`) positioned via absolute per-tile
offsets in a `Box`, rather than square's `Row`/`Column` nesting — a staggered hex grid doesn't fit
a simple row/column layout the way an unstaggered square one does. The existing 8-color
`edgePatterns` palette (already widened for Phase 1's `MAX_COLORS = 8`) is reused as-is; no new
colors needed since the ceiling didn't change.

### Stats

Hex custom games get their own key prefix, `"CUSTOM_HEX_{size}x{colorCount}"`, distinct from
square custom's existing `"CUSTOM_{size}x{colorCount}"` — a 6×5 hex board and a 6×5 square board
are genuinely different puzzles (different neighbor count, different generation), so sharing one
key would silently blend two unrelated difficulty curves into one "best." This keeps every
existing key (`"EASY"`/`"MEDIUM"`/`"HARD"`/`"CUSTOM_{size}x{colorCount}"`) working exactly as
before — purely additive, same as Phase 1's own stats generalization was relative to the original
tier-only version.

### Deliberate scope cuts

- **Hex is Custom-only** — no EASY/MEDIUM/HARD hex tiers. The fixed tiers exist to hand a new
  player a zero-decision default; a second geometry is an opt-in exploration, not a new default
  path, so it lives entirely behind the same slider panel that already gates every other
  non-default choice.
- **No hex daily-challenge route** — daily challenges pin one deterministic seed per game per day
  app-wide; Custom mode (both geometries) already has no daily route of its own from Phase 1, and
  hex doesn't reopen that.
- **Still no third geometry, still no Penrose tiling** — this phase closes out item 2 of Phase 1's
  three-way fork; item 3 (true aperiodic tiling, its own ADR) remains exactly as deferred as
  before.
