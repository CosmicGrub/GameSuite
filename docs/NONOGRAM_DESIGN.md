# Nonogram — Design

**Status:** Approved by the project owner via brainstorming, ready for an implementation plan
**Date:** September 2026
**Deciders:** the project owner

## Context

Second pick of wave 2 (immediately after Breakout, README item 22). Per
`docs/NEW_GAMES_BRAINSTORM.md`'s own "Nonogram / Picross" entry: row/column numeric clues
describing run-lengths of filled cells; fill the grid to satisfy both. A natural extension of the
"grid logic puzzle" family Minesweeper/Sudoku/Lights Out/Edge Match already established in this
app — but that entry explicitly flags this as **"the hardest generator problem in this whole
list"**: generating a puzzle with a unique solution is a real constraint-satisfaction problem, not
a trivial scramble, and predicts it would "likely want a real constraint solver rather than the
'generate solved then reduce' idiom used elsewhere." This doc settles exactly what that solver
does and how it's used, through a round of brainstorming with the project owner — this was the
single highest-leverage decision in scoping this game, the same way "rotate-only, not placement"
was for Edge Match.

## Generation & the solver

Unlike Sudoku (which carves clues OUT of a solved grid one at a time, re-verifying uniqueness
after each removal), a nonogram's clues are never partial — every row and column clue is always
fully shown to the player, by definition of the puzzle format. So generation here is: build a
random candidate grid, derive its clues directly, then verify what THOSE clues alone actually
prove.

**The line-solver** is the real engine: for every row and column, enumerate every placement of
its own run-lengths consistent with what's currently known about that line (filled/empty/
undetermined), and intersect them — any cell that's the same across every still-possible
placement gets filled in as newly known. Repeated across every row and column to a fixed point
(no more cells can be newly determined), same "propagate until stable" shape
`SudokuGame.generateSolvedGrid`'s own constraint masks use for a different purpose (legality
checks, not iterative narrowing) — the closest existing precedent in this codebase, though this
is a materially different algorithm from it.

- **EASY and MEDIUM**: a generated candidate is only ACCEPTED if the line-solver alone fully
  determines every cell — this simultaneously proves the puzzle has a unique solution AND that a
  human can solve it through pure logic, with zero guessing required. A candidate the line-solver
  can't fully resolve is discarded and regenerated, the same "reject and retry" idiom
  `LightsOutGame.scramble`/`EdgeMatchGame.generatePuzzle` already use for their own "don't ship a
  degenerate result" guards.
- **HARD**: if line-solving stalls with cells still undetermined, escalate to a BOUNDED
  backtracking search (same "hard call-budget ceiling, treat budget-exhausted identically to
  'not verified, don't risk it'" principle `SudokuGame.countSolutions` already uses for its own
  uniqueness proof) to confirm the clues still have EXACTLY one solution. A candidate is accepted
  only if backtracking confirms true uniqueness within budget; inconclusive or non-unique results
  are discarded and retried, never guessed past. This is what gives HARD real teeth — some cells
  genuinely require a guess-and-verify step to place, a real skill-ceiling raise over EASY/MEDIUM,
  not just a bigger grid with the same solving technique.

All three tiers share one engine: only the ACCEPTANCE criterion differs (EASY/MEDIUM require the
line-solver alone to finish it; HARD accepts confirmed-unique-via-backtracking too). The
line-solver itself is also what powers the live clue-strikethrough feedback during actual play
(see **Feedback**) — re-run against the player's OWN current fill state, not just used once at
generation time.

**A named implementation risk, not just a vague "could be slow" caveat**: EASY/MEDIUM's
"reject and retry until the line-solver alone fully resolves it" approach depends on random grids
actually being fully line-solvable often enough to not stall generation — this ACCEPTANCE RATE is
genuinely unknown ahead of implementation (unlike, say, Lights Out's scramble, which is guaranteed
solvable by construction with no rejection loop at all) and could be low enough at 10×10 to need a
generation-attempt cap with graceful fallback (e.g. widening to allow one bounded backtracking
step even at MEDIUM after N failed attempts), same "never spin forever, have an honest fallback"
discipline `SudokuGame.carvePuzzle`'s own "stops at a floor rather than guaranteeing the nominal
target" already demonstrates. The implementation plan should measure this empirically early
rather than assume either a low or high accept rate.

## Board sizes

| Tier | Size |
|---|---|
| EASY | 5×5 |
| MEDIUM | 10×10 |
| HARD | 15×15 |

Classic nonogram-book size progression — 5×5 is a genuinely gentle intro (few enough cells that
pure logic resolves quickly), 10×10 is the most common "standard" size in real nonogram puzzle
collections, 15×15 is a substantial HARD tier that stays line-solver-friendly rather than
becoming an unreasonably large search space for the generator's own backtracking fallback.

## Input model

Tapping a cell cycles **empty → filled → X-marked → empty**. The X-mark is never checked against
the solution — it's a pure player scratchpad annotation ("I've logically ruled this cell out"),
the same reasoning tool real nonogram solving actually uses, not an optional nicety layered on
top of a simpler fill-only toggle. Leaving it out would make this a stripped-down variant, not
just a simplified one.

## Feedback

A row or column's own clue numbers strike through once that line's CURRENT fill pattern already
satisfies them — recomputed live via the same line-solver logic (not a separate "is this line
correct" check) as the player fills/unfills cells. No per-cell right/wrong flag, and no mistake
counter: unlike Sudoku (where a placed digit is immediately, unambiguously right or wrong against
the known solution), a nonogram cell's "correctness" only really resolves at the LINE level, and
a wrong fill can make a clue's count look satisfied and then later look broken again as more
cells go in — an instant per-cell flag doesn't map cleanly onto how nonogram clues actually
resolve, so this doesn't force Sudoku's model onto a genre where it doesn't fit.

## Stats, daily seed, session shape

- **Time-only stats** (`NonogramStatsStore`, matching `MinesweeperStatsStore`/`SudokuStatsStore`'s
  shape), not the two-metric best-moves/best-time shape Lights Out/Edge Match use — unlike those
  two (where a real, meaningful MINIMUM move count exists to chase), there's no well-defined
  optimal "move count" here: every solution cell needs at least one fill regardless of order, and
  X-marks are a pure aid with no canonical minimum count either. Time is the only honest skill
  metric this puzzle actually has.
- **Daily-seed mode** (`nonogram-daily` route), matching every other solo puzzle in this app.
- **Standard solo-puzzle `GameModule` shape**: `SINGLE_PLAYER_VS_BOT` only, no real bot — same
  idiom as every other solo puzzle here. `matchOver` / `timerStartElapsedRealtime` /
  `pausedAtElapsedRealtime` / `totalPausedMillis` fields all mirror the established pattern.
- **Both now-well-known bug patterns built in from the start**: the gameplay-mutating method
  (cell tap) checks `matchOver.value`, not just the per-puzzle `solved` flag; `pause()` is
  idempotent (`pausedAtElapsedRealtime == null` guard) from day one — the same consolidated fix
  every engine in this batch has needed, applied proactively this time rather than found after
  the fact.

## Deliberate scope cuts (honest MVP, same spirit as every other game's own documented cuts)

- **No hint/solver exposed to the player** — the line-solver is real, working infrastructure
  already built for generation/feedback, but surfacing it as an in-game "give me a hint" feature
  is a distinct UX decision (how much of a hint, does it cost anything, does it undermine the
  point of X-marks as a personal reasoning aid) left for a later pass, same reasoning every other
  puzzle in this batch gives for skipping a hint feature despite having the underlying solving
  logic available.
- **No curated/hand-authored puzzle bank** (e.g. nonograms that resolve into recognizable pixel-
  art pictures) — procedural generation from random grids won't reliably produce a "looks like
  something" picture; that's a real, different generation approach (start from a curated image,
  derive clues from IT specifically) explicitly not what this design does. A future, separate
  feature, not a gap in this one.
- **No color/multi-color nonograms** — every cell is binary (filled or not), not multi-color
  nonograms (where each filled run also carries a specific color) — a real, separate genre
  variant, not attempted here.

## Build order / next step

This has genuinely the highest algorithmic risk of any engine in this batch, per the brainstorm
doc's own "hardest generator problem" framing — the implementation plan should budget a real
adversarial-review pass on the line-solver/backtracking-escalation logic specifically (the same
class of risk Sudoku's own uniqueness solver turned out to have — a naive or unbounded search
could blow up combinatorially at HARD's 15×15 size), not skip or lightly scale one down the way
e.g. Party Toolkit reasonably could.

Next step: hand this doc to `writing-plans` (or equivalent) to produce a concrete implementation
plan — the `NonogramGame`/`NonogramState` engine (grid generation, the line-solver, the bounded
backtracking escalation, clue-derivation), `NonogramStatsStore`, unit tests (including an
independently-reimplemented uniqueness verifier in the test file itself, matching
`SudokuGameTest`'s/`EdgeMatchGameTest`'s own "don't trust the engine's own solver to check
itself" precedent), then the UI screen + menu/route/strings wiring.
