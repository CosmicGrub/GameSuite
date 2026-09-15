# KenKen — Design (retroactive)

**Status:** Implemented and shipped as `games/kenken/KenKenGame.kt` + `ui/KenKenScreen.kt`, then
documented here after the fact — see **Process note** below for why this doc's own timeline is
unusual.
**Date:** September 2026

## Process note — how this doc came to exist

Unlike every other game in this batch, KenKen was built **without going through this project's
own brainstorming-then-approval process first**. `docs/KAKURO_DESIGN.md` (the doc that WAS
properly brainstormed and approved) explicitly says: *"KenKen is explicitly NOT covered by this
doc... gets its own scoping pass if picked up."* A background workflow appears to have read
`docs/NEW_GAMES_BRAINSTORM.md`'s own original "Kakuro / KenKen" bullet directly — which groups
the two together — rather than the scoping decision that later split them apart, and dispatched
a build for both.

When this was discovered (fully built, tested, and already merged into `main`), the project
owner's own call was: **keep it** — it's genuinely well-scoped on its own terms (see below) and
working, not worth reverting — and document it properly after the fact rather than pretend it
went through the normal process. This doc is that documentation: an accurate record of the real
decisions the implementation actually made, not a fresh brainstorm, and not a claim that this was
approved in advance the way Edge Match/Nonogram/Kakuro/Tower Defence were.

## What KenKen actually is, and why it's not "Kakuro with different cages"

KenKen (aka KenDoku/Calcudoku): an N×N grid that must form a complete **Latin square** (digit
1..N exactly once per row AND per column — no Sudoku-style box constraint), simultaneously
partitioned into irregular **cages**, each carrying an operator+target arithmetic clue its cells
must combine to reach.

Despite both being "numeric grid puzzles in the Sudoku family," KenKen and Kakuro are
mechanically different in ways that matter for a generator, not just cosmetically:

| | Kakuro | KenKen |
|---|---|---|
| Board shape | Irregular (black/white cells) | Plain N×N square |
| Where clues live | In BLACK cells, outside the runs they constrain | Inside the cage's own top-left-most cell |
| Region shape | Straight, axis-aligned row/column segments ("runs") | Free-form orthogonally-connected polyominoes (L-shapes, zigzags, 2×2 blocks) |
| Per-region AllDifferent | Yes, independently, within each run | **No** — only a consequence of the Latin-square rule when two cage cells happen to share a row/column; two cells in the same cage CAN legally hold the same digit if they're in different rows AND columns |
| Length/size-1 region | Degenerate, explicitly avoided | Normal, common, load-bearing (reveals one cell's value outright) |

Getting any of these backwards would be a real correctness bug, not a stylistic difference — the
implementation's own KDoc calls this out explicitly as something checked, not assumed.

## Cage topology — same fixed-template idiom as Kakuro, same reasoning

Procedurally generating valid cage partitions (every cage connected, sizes reasonable, full
coverage with no gaps/overlaps) is a genuinely hard, separate combinatorial problem from
digit-fill generation — structurally the same class of problem Kakuro's own run-topology faced.
KenKen makes the identical choice: **3 hand-authored fixed cage-partition templates per
difficulty tier**, validated by test for full partition coverage, per-cage connectivity, and a
sane max cage size (4 cells).

**A real empirical finding during implementation, the same shape as Kakuro's own "run length ≥2
wasn't the real lever" lesson**: the first MEDIUM/HARD templates were axis-aligned strips
(horizontal/vertical dominoes and trominoes) — visually reasonable, and they passed every
structural validation check — but measured **0% uniqueness at 6×6** across every one of them. The
reason: an ADD or MUL clue over cells confined to one row or column is a mathematical no-op (the
sum/product of *any* permutation of 1..N is the same fixed constant), leaving far too much of the
Latin square's enormous solution space (812 million grids at 6×6; ~5.5×10²⁷ at 9×9 — nothing like
Sudoku's boxed-down 9×9 space) simultaneously satisfiable. Confirmed by an empirical sweep
(hundreds of derive-then-solve trials per template) before locking in the fix: cages that are
**bent/branching, not confined to one row or column**, genuinely interlock the row and column
constraints together, and a meaningfully higher proportion of single-cell cages (roughly a
quarter of the board) gives enough direct pins for the rest to resolve uniquely. Every shipped
MEDIUM/HARD template follows this rule; EASY's 4×4 didn't need it (4×4's tiny 576-grid solution
space tolerates even a fully-regular partition).

## Board sizes

EASY 4×4, MEDIUM 6×6, HARD 9×9 — the genre-standard real-KenKen range. A bigger grid is both a
strictly larger Latin-square deduction problem and allows longer/more varied cages, a genuine
technique-level jump per tier. 9×9 was picked over a smaller HARD ceiling because the generator's
own MRV + call-budget technique is already proven at exactly that size by `SudokuGame`'s own 9×9
solver — no new performance risk taken on.

## Operator selection

Given a cage's cells and their already-decided solution values:
1. **1-cell cage**: no operator — the clue is just that cell's value, revealed outright.
2. **2-cell cage**: SUB and DIV are the more genre-authentic clues (they constrain a
   difference/ratio, not a commutative combination), but both are degenerate when the two values
   are equal. Only offered when non-degenerate (differ, for SUB; differ and divide evenly, for
   DIV); picked uniformly at random between whichever qualify, falling back to a random ADD/MUL
   pick (always valid) when neither does.
3. **3+-cell cage**: ADD or MUL only, chosen uniformly at random — a real, well-established
   KenKen convention, not a simplification invented here: SUB/DIV are only well-defined on an
   *ordered pair* of exactly two values.

## Generation & uniqueness discipline

Same non-negotiable discipline every generator in this batch follows: fill a complete Latin
square via randomized backtracking (`generateLatinSquare` — the exact `SudokuGame.generateSolvedGrid`
technique, minus its box constraint), pick a cage template, derive every cage's clue from that one
solution (never the other way around), then **actually re-solve** the candidate puzzle from
scratch (`countKenKenSolutions` — Latin-square masks plus per-cage arithmetic pruning, checked the
instant a cage's last cell fills) under a hard call-budget ceiling to confirm exactly one valid
grid exists. Not unique, or inconclusive (budget exhausted) → discard and retry, up to a fixed
attempt cap; total failure raises loudly rather than ever shipping an unverified board.

## Input model, mistakes, stats, session shape

All mirror `SudokuGame`'s own choices exactly — select-then-act digit entry plus notes, mistakes
counted the Sudoku way (a wrong digit is accepted, not blocked, compared against the known
solution, tallied as a non-decrementing running count), time-only stats (`KenKenStatsStore`),
`startMatch(dailySeed)` daily-seed support, the standard solo-puzzle `GameModule` shape
(`matchOver` guard checked first on every mutating method, idempotent `pause()`, both built in
from the start). Nothing about KenKen's actual mechanics demands anything different from Sudoku
here, so nothing was invented.

**No given cells at all** — unlike Sudoku, KenKen's board starts completely blank. A single-cell
cage reveals its answer as a clue label, but the player still has to write that digit into the
cell themselves; real KenKen never pre-fills a cell the way Sudoku pre-fills givens.

## Deliberate scope cuts

- No hint/solver, no real-time row/column/cage conflict highlighting, no undo stack — the same
  three cuts `SudokuGame`'s own design documents, for the same reasons.
- `countKenKenSolutions` only checks a cage's arithmetic once every one of its cells is filled,
  not via incremental partial-sum/product bound pruning — a real potential optimization left out
  because Latin-square masking + MRV ordering + complete-cage pruning already keeps generation
  comfortably fast at every tier this game ships (9×9 and below).
- No procedural cage-topology generation — see **Cage topology** above; the identical trade-off
  Kakuro's own doc makes for its run shapes.

## Naming

**"KenKen"** (also known as KenDoku/Calcudoku) — the commonly recognized name for this puzzle in
English-language puzzle apps and publications.
