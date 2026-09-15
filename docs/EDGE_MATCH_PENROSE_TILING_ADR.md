# ADR-3: Penrose/Aperiodic Tiling Feasibility for Edge Match's Custom Game Builder

**Status:** Rejected — the project owner reviewed this ADR's 3-way fork and chose to retire this
item. No build. Two tiling geometries (square, hex) are the Custom Game Builder's final scope.
**Date:** September 2026
**Deciders:** the project owner

## Context

Item 3 of the 3-way fork `docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md`'s own Context section split
the original "generative Custom game builder" ask into: (1) configurable square parameters
(shipped, README item 26), (2) a second tiling geometry (shipped as hex, README item 27), and
(3) true Penrose/aperiodic tiling with "a constraint-solver-backed generator" — flagged from the
very start as needing its own ADR before any code, the same way Tower Defence's real-time
architecture question got `docs/TOWER_DEFENCE_ADR.md` before its own gameplay design. This ADR is
that analysis for item (3).

Per this project's own standing discipline (Kakuro's run-length-2 finding, KenKen's bent-cage
finding, Tower Defence's own stress-test measurement) — **investigate the real thing before
assuming**, not just reason abstractly from the brainstorm doc's own guess about difficulty. That
guess turns out to have been importantly wrong in one direction and right in another: the actual
tiling-generation problem is much more tractable than "constraint-solver-backed" implies, but a
different, previously-unnoticed problem — whether this app's own core MECHANIC (tap-to-rotate a
tile in place) is even geometrically compatible with Penrose's own tile shapes — turns out to be
the real question this ADR needed to answer.

## What direct geometric analysis actually shows

### Real Penrose tiling uses one of two tile-shape systems — and they are NOT interchangeable for this mechanic

- **P2 ("kite and dart")**: two deltoid (kite-shaped) quadrilaterals — one convex ("kite"), one
  non-convex ("dart"). Both are proper deltoids: two pairs of adjacent equal-length sides, one
  mirror line along the long axis, but their diagonals do **not** bisect each other (they are not
  parallelograms). Consequence: **a kite or dart has no non-trivial rotational symmetry at all** —
  rotating one by any angle other than a full 360° turn does not map it back onto the same
  physical footprint it started in. There is nothing to "rotate to" — the mechanic this whole app
  is built around (tap a tile, it turns some fixed amount, it still fits its slot) has no
  meaningful action to perform on a kite/dart tile. **P2 is a non-starter for this game.**
- **P3 ("rhombus")**: two rhombi — thin (36°/144° corners) and thick (72°/108° corners). A rhombus
  **is** a parallelogram (all sides equal length, opposite sides parallel), and every parallelogram
  has exact 180° point symmetry about its center (its diagonals bisect each other) — this is basic
  Euclidean geometry, true of any parallelogram, not something specific to Penrose rhombi. Neither
  Penrose rhombus has any *additional* rotational symmetry beyond that 180° (that only happens at
  90° corners, i.e., a square) — so **a rhombus tile has exactly ONE non-trivial in-place rotation
  (180°), not three (90°/180°/270°) the way a square does, and not five the way a regular hexagon
  does.**

This is the load-bearing finding of this whole ADR: **P3 rhombi are the only geometrically viable
choice, and even then the mechanic degrades from square's 4-state / hex's 6-state rotation down to
a 2-state (correct / flipped) toggle per tile** — closer in texture to Lights Out's binary toggle
than to Edge Match's own established rotation puzzle. See **Decision** for why this needs the
project owner's own call rather than being decided here.

### The "constraint-solver-backed generator" premise was likely overstated

The brainstorm doc's fear was reasonable to raise (Penrose tiling has a real reputation for being
mathematically exotic) but doesn't hold up under direct analysis: **generating a valid, correct
Penrose P3 patch is a solved, deterministic, decades-old problem, not an open constraint-
satisfaction search.** The standard technique (substitution/"deflation", going back to Penrose's
and Robinson's own original 1970s constructions, and implemented in essentially every Penrose-
tiling demo that exists) is:

1. Start from one or a few seed rhombi arranged around a point (e.g., a "sun" or "star"
   configuration — well-documented starting patterns).
2. Repeatedly apply fixed **substitution rules** — each thick rhombus subdivides into one thick +
   one thin smaller rhombus; each thin rhombus subdivides into one thin + one thick — scaled by the
   golden ratio φ each step.
3. After N substitution ("inflation") steps, the result is a **guaranteed-valid, guaranteed-
   aperiodic** patch of the desired rough size, with **zero backtracking, zero constraint solving,
   and zero risk of a "dead end"** — unlike ad-hoc local placement with matching rules (the actual
   open/hard version of this problem, which this technique sidesteps entirely by construction).

This is directly analogous to Kakuro's own "procedural run-topology generation cut as a separate,
harder problem — fixed hand-authored templates instead" call, one level more automated: instead of
hand-authoring templates, a **one-time (build-time or first-run) deterministic generation pass**
produces a small fixed set of vetted patches, exactly the same "the hard generation problem is
solved once, not per-player-session" shape Kakuro's own templates already use in this codebase.

### What real, new engineering this would actually need

Smaller than originally feared, but real:

1. **The deflation algorithm itself** — genuinely new code (nothing in this codebase does anything
   like it today), but a well-understood, bounded, one-time task, not an open research problem.
2. **`EdgeMatchTile`'s rotation model needs one more small generalization.** Good news: the fix is
   smaller than it first appears. `currentEdge`'s existing formula
   (`canonicalEdges[((direction - rotation) % sides + sides) % sides]`) already works unchanged for
   a rhombus, since a rhombus's own 4 `canonicalEdges` slots are unaffected — only the **allowed
   values of `rotation`** need restricting to `{0, 2}` instead of `{0, 1, 2, 3}` (180° is exactly 2
   of 4 edge-index steps in the same indexing scheme square/hex already use). Concretely, this
   means adding a per-tile rotation INCREMENT (default 1, square/hex; 2, Penrose rhombi) that
   `rotatedClockwise()` advances by — not a wholesale new tile model.
3. **New rendering** — thin/thick rhombus wedge shapes (4 wedges each, same "meet at center"
   idiom `EdgeMatchTileView`/`HexEdgeMatchTileView` already use) at each shape's own real angles,
   positioned via **absolute per-tile pixel offsets already established as this app's own pattern
   for a non-Row/Column-friendly layout** (`HexEdgeMatchBoard`'s own approach, directly reusable —
   this is not new architecture, just new geometry data feeding the same positioning idiom).
4. **Neighbor adjacency computed once from real geometry, not a formula.** Unlike square's row/col
   arithmetic or hex's fixed axial deltas, a Penrose patch's adjacency (which tile touches which,
   on which of its 4 sides) has no closed-form formula — it has to be computed once from the
   deflation algorithm's own output coordinates (e.g., by finding coincident edges) and baked into
   each precomputed patch as a fixed lookup table. More DATA per patch, not more runtime
   complexity — the actual gameplay-time code (seam-color generation, scramble, `matchingDirections`,
   `isBoardSolved`) is the same generic per-tile/per-direction logic already generalized across
   square and hex, just fed a third kind of topology table.
5. **Discrete patch sizes, not a continuous slider.** Each inflation step multiplies tile count by
   a fixed factor (very roughly ×2.6 per step) — there is no way to ask for "an 7×7-equivalent"
   Penrose patch the way square/hex's sliders do. A handful of named/discrete sizes (e.g., 3
   precomputed patches at increasing inflation levels, Kakuro's own EASY/MEDIUM/HARD-template shape
   more than Custom mode's continuous slider shape) is the natural fit — this is a genuine, visible
   UX departure from Custom mode's existing slider pattern, not just an implementation detail.

None of this is small, but none of it is the open-ended research problem originally feared either
— it's a bounded, one-time generation task plus new rendering/data, reusing this codebase's
existing generalized generation-and-matching machinery for the parts that already generalize
cleanly (seam coloring, scrambling, live match highlighting, win detection).

## Decision

**Retired — no build.** This ADR settled the technical feasibility question (P3 rhombi via
deflation, not P2 kite/dart, not a constraint solver) and surfaced a genuine PRODUCT question this
project's own precedent says needs the project owner's direct call, not an engineering guess. Put
to the project owner directly as a 3-way fork; their answer was **option 3: retire this item.**
Two tiling geometries (square, hex, both shipped in README items 26-27) are the Custom Game
Builder's final scope — the 2-state-rotation finding below was judged a real enough downgrade in
puzzle richness that a third geometry isn't a clear improvement over what's already shipped. This
closes out `docs/NEW_GAMES_BRAINSTORM.md`'s Edge Match Custom Game Builder line item entirely; no
further engineering happens against this ADR.

The fork as originally presented, preserved for the record below:

Is a Penrose variant still worth building given it would play closer to a 2-state toggle (per-tile,
correct-or-flipped) than to square's 4-state or hex's 6-state rotation puzzle — the exotic Penrose
*look* is real and achievable, but the underlying *puzzle feel* would be meaningfully simpler than
either existing geometry, not an escalation past hex the way "third geometry" might imply. Three
real, substantively different paths forward, not one:

1. **Build it anyway** — the visual novelty (a genuinely aperiodic board, a real mathematical
   curiosity as a puzzle skin) may be the actual appeal regardless of per-tile puzzle depth; discrete
   patch sizes and 2-state rotation are honest, documented trade-offs, not blockers.
2. **Build a simpler stand-in instead**: a **non-aperiodic rhombille tiling** (one rhombus shape,
   e.g. a 60°/120° rhombus, in a simple *periodic* pattern — NOT true Penrose/aperiodic, but still
   a genuinely new non-square-non-hex tile shape) — most of this ADR's engineering findings
   (rhombus-only-2-state-rotation, absolute-offset rendering) still apply, but the layout would be
   a simple repeating lattice rather than requiring the deflation algorithm at all, meaningfully
   cheaper to build than true Penrose. Delivers "a third tile shape" without delivering
   "aperiodicity" specifically — worth naming plainly since it may or may not be what "Penrose
   tiling" was actually valued for.
3. **Don't build a third geometry at all** — retire this item. Two geometries (square, hex) may
   already be enough breadth for the Custom Game Builder; the 2-state-rotation finding is a real
   argument that a third geometry isn't a clear improvement over what's already shipped.

This ADR does not pick between these three — that's the project owner's call, the same way Phase
1's own 3-way fork and Edge Match's original rotate-vs-swap fork were put to them directly rather
than guessed at.

## Options considered

| Option | Verdict |
|---|---|
| **P2 kite/dart Penrose tiling** | Ruled out — no non-trivial rotational symmetry, nothing for the core mechanic to do. |
| **P3 rhombus Penrose tiling via deflation/substitution** (this ADR's technical recommendation, if built) | Feasible. Deterministic, well-understood generation; genuine but bounded new engineering (rendering, adjacency data, discrete sizes); real mechanic downgrade to 2-state rotation. |
| **P3 rhombus Penrose tiling via local constraint-solving** (the brainstorm doc's original assumption) | Not needed — deflation produces guaranteed-valid patches with no search, backtracking, or solver required. Would be strictly more implementation risk for no benefit over deflation. |
| **Non-aperiodic single-rhombus rhombille tiling** (Decision option 2) | Cheaper than true Penrose (no deflation algorithm needed, simple repeating lattice), but does not deliver actual aperiodicity — a real "is this what was wanted" question for the project owner. |
| **Fixed-frame "fake" rotation** (tile art rotates in a square bounding frame regardless of whether the underlying rhombus would really tile at that angle) | Considered and not recommended — would restore a richer rotation count, but breaks visual authenticity (tiles would visibly not really "fit" at every shown rotation), undermining the entire reason to want a Penrose *look* in the first place. |

## Consequences

**Easier than originally feared:**
- No constraint solver, no backtracking-based tiling generator — a bounded, well-documented
  deterministic algorithm.
- Most of the generic per-tile matching/scrambling/win-detection/rendering-positioning machinery
  already generalizes cleanly from square → hex → (potentially) Penrose, per this codebase's own
  `docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md` Phase 2 work — this would be a third data source into
  the same pipeline, not a fourth rewrite.

**Worth naming honestly:**
- The puzzle-feel finding (2-state rotation) is the real news this ADR surfaces — it changes
  whether this item is actually worth doing at all, which is exactly why this is an ADR (a real
  decision point) and not a straight scoping doc that just proceeds to a build plan.
- Discrete patch sizes are a genuine UX departure from Custom mode's existing continuous-slider
  pattern; whoever eventually designs the UI for this needs to treat it as its own decision, not
  assume the slider idiom just carries over.
- This project's own "measure it, don't assume it" discipline (Kakuro/KenKen's empirical
  generation findings) should still apply once real code starts: the deflation algorithm's
  correctness and the reject-and-reroll scramble loop's real-world retry behavior at only 2 states
  per tile are asserted here from established geometry/mathematics, not empirically verified in
  this codebase yet — a real Phase 3 build's own first step should still prototype and measure
  rather than trust this document's math alone, the same way Tower Defence's own ADR trusted a real
  measurement over reasoning from the code shape alone.

## Action items

1. **Done as part of this ADR**: the geometric feasibility question is answered (P3 rhombi only,
   2-state rotation, deflation not constraint-solving) and corrected against the brainstorm doc's
   original, partly-mistaken assumption.
2. **Done**: the three-way fork was brought to the project owner directly, who chose to retire
   this item. `docs/NEW_GAMES_BRAINSTORM.md` updated to close it out. No further action — this ADR
   is a closed record of why Penrose tiling was investigated and declined, not a pending task.
