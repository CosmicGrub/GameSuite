# Kakuro — Design

**Status:** Approved by the project owner via brainstorming — implemented and shipped as
`games/kakuro/KakuroGame.kt` + `ui/KakuroScreen.kt` (README Roadmap item 24), with one real
finding from implementation revising this doc's own template-authoring guidance — see
**Topology & generation**'s own update below.
**Date:** September 2026
**Deciders:** the project owner

## Context

Third pick of wave 2 (after Breakout, Nonogram). Per `docs/NEW_GAMES_BRAINSTORM.md`'s own
"Kakuro / KenKen" entry: a numeric grid puzzle in the Sudoku family, flagged there as lower
priority/genuinely niche relative to the rest of this batch, but not scoped in any real detail.
This doc settles it properly, through a round of brainstorming with the project owner — the same
depth every other game in this batch got regardless of its own priority ranking.

Kakuro: black cells carry a down-sum and/or across-sum clue; white cells fill with digits 1-9
such that every contiguous RUN of white cells (bounded by black cells or the board edge) sums to
its own clue AND contains no repeated digit within that run.

## Topology & generation (the crux)

Unlike every prior grid puzzle in this batch (Minesweeper/Sudoku/Lights Out/Edge Match/Nonogram
all use a plain, uniform N×N grid), a real Kakuro board's run TOPOLOGY — which cells are black
vs. white, and how the white cells group into horizontal/vertical runs — is itself an irregular,
crossword-like shape. Generating that topology procedurally is a genuinely hard, SEPARATE
combinatorial problem from generating/verifying digit values — arguably the single largest
undertaking in this whole batch if attempted in full generality (valid run-shape generation,
avoiding degenerate single-cell "runs," ensuring every white cell participates sensibly).

This design deliberately cuts that harder half, the same "honest MVP" pattern Edge Match's own
rotate-only decision established for an analogous two-part generation problem:

- **A small set of fixed, hand-designed run-topology templates** — 3-4 per difficulty tier. The
  black/white layout for a given template NEVER changes at runtime. Every white cell in a
  template must belong to a run of length ≥2 in at least one direction (a length-1 "run" is
  degenerate — its own sum clue trivially equals that one cell's value, no real deduction
  involved) and no run may exceed length 9 (mathematically impossible to fill with 9 distinct
  1-9 digits and no repeat past that) — both are hard constraints template authoring must respect,
  not just style guidance; templates should be authored/vetted by hand for both properties once,
  not generated.
  **Revised during implementation, a real empirical finding, not an assumption**: "run length ≥2"
  turned out to be a necessary but nowhere-NEAR-sufficient bound for `≥1`-attempt generation
  success. The first templates authored to this literal rule (runs of 4-5+ cells, otherwise
  satisfying every stated constraint) had a near-zero real-world chance of ever producing a
  uniquely-solvable puzzle — a random `AllDifferent` fill's derived sum clues almost never pin
  down that exact fill uniquely once a run gets that long, since there are simply too many other
  valid digit-sets summing to the same target. Sudoku's own "carve a known-valid grid" technique
  doesn't transfer here the way first assumed: Sudoku's given DIGITS are a direct, strong signal;
  Kakuro's derived SUMS are a much weaker one. Diagnosed by actually running the generator (every
  tier exhausted its attempt budget outright), not just by compiling. **Fixed by re-authoring every
  actual shipped template so every run is length EXACTLY 2** (the tightest possible run shape,
  not just "≥2") — empirically measured to raise the per-attempt uniqueness hit rate to roughly
  0.2%-2% across all three tiers (compensated by raising the generation-attempt ceiling well past
  what Sudoku/Nonogram need — see README item 24 for the exact number), rather than the
  near-zero rate longer runs produced. The ≥2/≤9 rule above stays correct as a hard *validity*
  constraint (every shipped template still satisfies it), it just isn't the real *generation
  success* lever — length-exactly-2 is.
- **Digit generation**: backtracking-fill the CHOSEN template's white cells with 1-9 per cell such
  that every run (horizontal and vertical) has no repeated digit — the same technique
  `SudokuGame.generateSolvedGrid` already uses for its own row/column/box `AllDifferent`
  constraints, applied here to per-run `AllDifferent` instead.
- Each run's sum clue is derived directly from the filled solution once generation completes —
  always fully shown to the player, the same "clues are never partial, nothing to carve" shape
  Nonogram's own clues already have (unlike Sudoku's carved-down givens).
- **Uniqueness is still verified, not assumed** — a valid `AllDifferent` fill doesn't guarantee
  its DERIVED sum clues alone pin down that exact fill uniquely (a different valid digit
  assignment could coincidentally produce the same per-run sums). A real Kakuro solver
  (backtracking with sum-target + distinct-digit pruning, the same shape
  `SudokuGame.countSolutions`/`NonogramGame`'s own `countSolutions` already use) checks the
  derived clues describe EXACTLY ONE valid grid. Not unique (or inconclusive under a hard call
  budget, same "never guess past an unverified result" principle `SudokuGame`'s own solver
  follows) → discard this digit fill and try a fresh one against the SAME template, same
  reject-and-retry idiom every generator in this batch uses.
- Which of the tier's 3-4 templates is used for a given game is itself chosen randomly (from the
  daily/session seed, same as every other random choice in generation) — picking just one
  canonical template per tier forever was explicitly rejected as too visually repetitive, given
  the shape never varies procedurally the way this batch's other puzzles' own boards do.

## Board sizes

| Tier | Bounding box |
|---|---|
| EASY | ~6×6 |
| MEDIUM | ~9×9 |
| HARD | ~12×12 |

Actual playable (white) cell count is smaller than the bounding box once each template's own
black cells are subtracted out. Roughly mirrors Nonogram's own 5/10/15 size progression in
spirit — small enough at EASY to hand-author and verify a few clean templates quickly, substantial
at HARD (dozens of white cells, real run-interdependency to reason through) without becoming an
unreasonable hand-authoring lift or a cramped fit on a phone screen.

## Input model

Select-then-act, mirroring `SudokuGame` exactly: tap a white cell to select it, then either enter
a digit (1-9) or toggle a pencil-mark note on that same selected cell. Kakuro is genuinely played
this way in practice — tracking "could be 3 or 7 here" across intersecting runs is core technique,
the same reasoning Sudoku's own notes already serve — so reusing that already-built, already-
tested two-step interaction model is both cheap and genre-authentic, not a compromise.

## Mistakes & feedback

Same model as Sudoku, not Nonogram's clue-strikethrough: a wrong digit is accepted (not blocked),
compared directly against the cell's own known-solution value, and counted in a running mistake
total that never decrements (`SudokuState.mistakes`'s own "session stat, not a live enforced
limit" idiom). No live per-run sum/duplicate-violation detection re-derived independently — same
reasoning Sudoku's own KDoc gives for its equivalent choice: comparing against the already-known
solution is simple and always correct, and re-deriving exactly which peers/runs a wrong digit
conflicts with is a real, separate feature (arguably more natural as a future hint feature) left
for later.

## Stats, daily seed, session shape

- **Time-only stats** (`KakuroStatsStore`, matching `SudokuStatsStore`'s shape) — Kakuro is a
  "fill correctly" puzzle like Sudoku, not a "chase the minimum move count" puzzle like Lights
  Out/Edge Match, so time is the honest skill metric here, same reasoning Sudoku/Minesweeper/
  Nonogram already settled on for themselves.
- **Daily-seed mode** (`kakuro-daily` route), matching every other solo puzzle in this app —
  reproducible via the same `Random(dailySeed)` idiom, applied to BOTH which template is chosen
  and how it's digit-filled.
- **Standard solo-puzzle `GameModule` shape**: `SINGLE_PLAYER_VS_BOT` only, no real bot, same
  idiom as every other solo puzzle here.
- **Both now-well-known bug patterns built in from the start**: the gameplay-mutating methods
  (set digit / toggle note) check `matchOver.value`, not just the per-puzzle `won`/`isOver` flag;
  `pause()` is idempotent (`pausedAtElapsedRealtime == null` guard) from day one — the same
  consolidated fix every engine in this batch has needed, applied proactively this time.

## Deliberate scope cuts (honest MVP, same spirit as every other game's own documented cuts)

- **No procedural topology generation** — see **Topology & generation** above; an explicit,
  substantially larger future phase, not silently folded into this build, same framing
  `docs/NEW_GAMES_BRAINSTORM.md` itself already gives the deferred Tessel Custom-game builder.
- **No hint/solver exposed to the player** — the generator's own solver is real, working
  infrastructure, but surfacing it as an in-game hint is a distinct UX/scope decision left for
  later, same reasoning every other puzzle in this batch gives for the identical cut.
- **No live per-run conflict highlighting** — see **Mistakes & feedback** above.
- **KenKen is explicitly NOT covered by this doc** — `docs/NEW_GAMES_BRAINSTORM.md` groups
  Kakuro and KenKen together as one bullet, but they're genuinely different puzzles (KenKen's
  irregular "cage" regions carry an arithmetic-TARGET clue with an operator — sum/product/
  difference/quotient — over cells that need NOT form a contiguous row/column run the way a
  Kakuro run does). If picked up, KenKen gets its own scoping pass, not an assumed clone of this
  one.

## Build order / next step

Real algorithmic risk here, comparable to Sudoku's/Nonogram's own — the implementation plan
should budget a real review pass on the digit-fill backtracking AND the uniqueness solver
specifically (both are genuinely new code, not reused from either sibling engine, even though
they're structurally similar in shape), and should measure generation timing empirically early
per template/tier the same way Nonogram's own accept-rate risk was measured rather than assumed.

Next step: hand this doc to `writing-plans` (or equivalent) to produce a concrete implementation
plan — the hand-authored template data (3-4 per tier: which cells are black/white, how white
cells group into runs), the `KakuroGame`/`KakuroState` engine (digit-fill backtracking, the
uniqueness solver, clue derivation), `KakuroStatsStore`, unit tests (including an independently-
reimplemented uniqueness verifier in the test file itself, matching `SudokuGameTest`'s/
`NonogramGameTest`'s own "don't trust the engine's own solver to check itself" precedent), then
the UI screen + menu/route/strings wiring.
