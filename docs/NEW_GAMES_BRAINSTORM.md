# New Games Brainstorm & Scope Plan

**Status:** Active — Minesweeper shipped, rest of this list not yet started
**Date:** September 2026
**Deciders:** the project owner

## Context

The project owner shared 33 reference screenshots across three unrelated apps and asked
to "script, scope, scaffold and brainstorm games (the same games as well as additional
games that inspire other games from the photos)". Two scope decisions were made up front
and this document treats both as settled, not open questions:

1. **Where new games live**: "New game modules inside GameSuite" — not a separate app,
   not a new codebase. Every game below is a new `GameModule` (or, where noted, something
   architecturally different) inside this existing Android/Desktop KMP app, reusing its
   engine/menu/theming/stats/settings shell alongside the 13 games already there.
2. **Visual identity scope**: "New games only (Recommended to start)" — new games get
   their own warm, Chogan-inspired palette (see `MinesweeperScreen.kt`'s
   `minesweeperPalette()` for the reference implementation), isolated from the 13 existing
   games' established Material3 identity. This is safe specifically because gameplay
   colors in this app already never read `MaterialTheme.colorScheme` (see `AppTheme.kt`'s
   own KDoc) — every game's board colors are bespoke literals, so a new game choosing
   different literals than an old game is consistent with existing practice, not an
   exception to it. Existing games are **not** being re-themed as part of this effort.

**Platform/engine recommendation (not yet explicitly re-confirmed by the project owner,
but proceeded on since raised and not objected to)**: stay on Kotlin Multiplatform +
Jetpack Compose. Do **not** migrate to Unity, Unreal, or Godot for this work. None of the
reference material (turn-based board/puzzle games, simple 2D grids, dice/utility tools)
needs a real-time 3D engine's capabilities — the existing `docs/ENGINE_DECISION.md` ADR
already made this exact call for the PC/desktop port (KMP/Compose over a game-engine
rewrite) for reasons that apply identically here: a mainstream, deeply-documented,
headless-buildable toolchain the existing codebase and its build/test loop already fit,
versus a second codebase and skillset with no upside for this genre of game. If a future
game actually needs real-time 3D (none currently proposed do), that would be its own
separate ADR, not a blanket engine swap for this batch.

## Reference apps (source material)

- **Chogan** — a casual-games hub. Screenshots showed a home grid (Tower Defence,
  Sudoku, Minesweeper, Dots and Boxes) plus "coming soon" feature previews for Word
  Search, Color Puzzle, and Lights Out.
- **Boardgame Pal** — a board-game-night utility toolkit, not a games collection: Dice,
  Coin Toss, Random Letter, Scoreboard, Life Points, Hourglass, First Player, and Teams.
- **Tessel** — a tile edge-matching puzzle game with a rich generative "Custom game"
  builder (including Penrose tiling among its tiling options).

## Games directly shown in the reference photos

### Minesweeper — ✅ shipped
`games/minesweeper/MinesweeperGame.kt` + `ui/MinesweeperScreen.kt`. First-click-safe
mine placement, flood-fill reveal, flag toggle, EASY/MEDIUM/HARD tiers (9x9/10,
16x16/40, 16x30/99 — the standard Minesweeper tier sizes), daily-seed mode
(`minesweeper-daily` route), best-time persistence per tier
(`MinesweeperStatsStore`), live timer. 9/9 unit tests passing. Deliberately cut for
v1 (documented in the engine's own KDoc): no chord-tap (auto-reveal via satisfied
flag count) and no hint/solver. Not yet given the foldable-aware `AdaptiveTwoPane`
treatment `SlidingPuzzleScreen` has — a single-pane scrollable grid works fine at
all three tier sizes and was judged sufficient for a working v1.

### Sudoku — ✅ shipped
`games/sudoku/SudokuGame.kt` + `ui/SudokuScreen.kt`. Classic 9x9, three-region
(row/column/3x3 box) constraint puzzle. Select-then-enter input (tap a cell, then tap
a number-pad digit — the same two-step model Solitaire already uses, since this app
has no drag input), a notes-mode toggle for pencil marks (a per-cell `Set<Int>`,
auto-cleared from peers when a value is placed elsewhere), EASY/MEDIUM/HARD tiers by
target clue count (42/32/26) reusing `CpuDifficulty`, daily-seed mode
(`sudoku-daily` route), best-time-per-tier persistence (`SudokuStatsStore`), live
timer, mistake tracking (shown as feedback, not enforced as a fail condition).
14 unit tests passing.

**Generation is real, not approximated**: a complete valid grid is built via
randomized backtracking, then clues are removed one at a time, keeping each removal
only if the puzzle-so-far still has EXACTLY ONE solution — verified by actually
re-solving it, not assumed. This is the standard, well-established technique for a
uniquely, logically solvable puzzle.

**A real, serious bug found by a dedicated background adversarial-review workflow
before this ever shipped, not caught by the initial unit tests**: the first version
of the uniqueness-checking solver used a naive fixed left-to-right cell order with no
bound on search effort. Three independent reviewer agents examined the engine from
different angles (generator correctness, state-mutation correctness, robustness); the
robustness reviewer flagged that this solver could blow up combinatorially, and a
skeptical verifier agent didn't just take that claim on faith — it independently
reimplemented the exact algorithm from scratch and empirically swept seeds, finding
HARD-tier (26-clue) cases needing 7M+ recursive calls with no plateau across hundreds
of samples. Since generation ran fully synchronously with no background dispatch, and
a daily-seed puzzle is deterministic, a bad seed would have frozen the UI thread for
every player opening that day's HARD puzzle — a real ANR risk, not a hypothetical
one. **Fixed** by rewriting the solver with a minimum-remaining-values (MRV)
heuristic (branch on the emptiest-constrained cell first, not left-to-right) plus a
hard call-budget ceiling that treats "ran out of budget" identically to "not unique"
(puts the clue back rather than ever risk a false uniqueness claim) — this bounds
`carvePuzzle`'s total worst-case work regardless of how adversarial a seed's puzzle
geometry is, not just makes the common case faster. A second finding (`selectCell`
had no bounds check, so an out-of-range index would crash `setValue`/`clearValue`/
`toggleNote` instead of no-op'ing like every other invalid-state case) was also fixed,
though the reviewer confirmed the shipped UI's only call site can never trigger it.
Both fixes are covered by new tests, including a 300-seed HARD-tier sweep that
generates AND independently re-verifies uniqueness for every seed — the whole batch
runs in ~140ms, down from what would have been many individual seeds taking seconds
to tens of seconds each under the old solver.

### Dots and Boxes — recommended next
A grid of dots; players draw one edge per turn; completing a box's 4th edge scores it
and grants another turn; most boxes wins. This is meaningfully different from every
existing game in the catalog — it's edge-selection on a grid, not cell-selection — so
it earns its place rather than duplicating Tic-Tac-Toe's shape. Fits
`SINGLE_PLAYER_VS_BOT` (a real if simple bot: complete any free 3-edge box, else avoid
creating one, else random) and `SINGLE_DEVICE_PASS_AND_PLAY` equally well, matching
Checkers/Chess's own dual-mode pattern. `GameCategory.PUZZLE` or a case could be made
for `BOARD` — lean `BOARD` since it's a two-player territory-scoring game like Checkers,
not a solo puzzle like Minesweeper/Sudoku.

### Lights Out ("Brain Trainer" in Chogan's preview) — ✅ shipped
`games/lightsout/LightsOutGame.kt` + `ui/LightsOutScreen.kt`. An NxN grid of lit/unlit
cells; tapping a cell toggles it and its orthogonal neighbors; goal is all-off.
Genuinely the smallest engine in this batch — the entire rule set is one
XOR-neighbors operation — and mathematically guaranteed solvable from any scramble
reachable by legal toggles (scramble by replaying random legal toggles from the
all-off state, same solvability-by-construction idiom as `SlidingPuzzleGame`).
EASY/MEDIUM/HARD tiers by board size (3x3/5x5/7x7) reusing `CpuDifficulty`, a
`lights-out-daily` route, best-moves-and-best-time persistence (`LightsOutStatsStore`,
mirroring `SlidingPuzzleStatsStore`'s two-metric shape rather than Minesweeper/Sudoku's
time-only one — a scrambled board has a real minimum press count, so "fewest presses"
is an honest thing to chase here too). 13 unit tests passing, including a from-scratch
GF(2) linear-algebra solver (Gaussian elimination) that independently verifies every
scrambled board is genuinely solvable and can actually WIN real boards through the
public API — deliberately not a greedy "press the first lit cell" heuristic, since
that isn't provably guaranteed to converge for an arbitrary board and using it would
have risked a hanging test rather than a wrong one.

**Two real bugs found by a (proportionally scaled-down, single-dimension) background
adversarial-review workflow, one of which turned out to affect two ALREADY-SHIPPED
games too**:
- **HIGH: the solve timer kept running through `pause()`/`resume()`.** Both were empty
  no-ops while the timer was a raw wall-clock delta (`nowMillis() - start`), and
  `GameSessionManager.pause()/resume()` really do forward from
  `MainActivity.onPause()/onResume()` — not a theoretical path. Backgrounding the app
  mid-puzzle for any length of time silently added that entire duration to the
  recorded solve time (and therefore any "best time" record). Checking `MinesweeperGame`
  and `SudokuGame` confirmed they share the EXACT same pattern (both already shipped
  and committed) — fixed in all three by tracking paused duration explicitly
  (`pausedAtElapsedRealtime`/`totalPausedMillis`) and subtracting it out. `SlidingPuzzleGame`
  (the original template this pattern came from, predating this whole new-games effort)
  has the identical bug too; not fixed in this pass since it lacks the injectable-clock
  seam the other three have and needs a small retrofit first — flagged as a follow-up
  task rather than silently expanding scope further into unrelated legacy code.
- **MEDIUM: `matchOver` was only ever reset in `init()`.** `endMatch()` sets it true and
  nothing but a brand-new `init()` call ever cleared it, so `playAgain()`/`leaveSession()`
  (each guarded by `if (matchOver.value) return`) could get permanently stuck as
  no-ops after any `endMatch()` call, even though `startMatch()`/`press()` kept working
  normally. Doesn't manifest through the app's actual navigation flow today (leaving a
  game always tears down and recreates the module instance), but is a real API footgun
  reachable via the public `endMatch()` override directly. Fixed the same way in all
  three engines: `startMatch()` now also resets `matchOver.value = false`.

A third raised finding (`playAgain()` discards the active daily seed, switching to a
non-deterministic board) was investigated and correctly refuted — that's the same
intentional behavior `MinesweeperGame`/`SudokuGame` already have (a "New Board" after
solving today's daily gives a fresh extra puzzle, not a repeat of the shared daily
one), not a bug introduced here.

### Color Match ("Color Puzzle" in Chogan's preview) — needs a concrete rule set chosen
The reference material only showed a "coming soon" card, not actual gameplay, for this
one. Two well-known real games share the "Color Puzzle" name in the wild and either is a
reasonable, honest interpretation: **Color Flood** (a grid of colored cells, flood-fill
outward from one corner by repeatedly picking a color, win by making the whole grid one
color in as few/limited moves as possible) or a **match-3** (Bejeweled-style swap-
adjacent-to-match-3+ — a much larger build: swap-and-settle animation, cascade/combo
resolution, a real scoring loop). Recommend **Color Flood** as the concrete pick — it is
honestly scoped (a grid + a flood-fill, reusing the exact BFS shape
`MinesweeperGame.floodReveal` already implements) versus match-3's considerably larger
animation/cascade engine, and it's listed separately below anyway as an
"additional inspired-by" game, so building it once here and treating "Color Puzzle" and
"Color Flood" as the same game avoids building two near-duplicate grid-color games. If
the project owner specifically wants match-3 gameplay instead, that's a distinctly
larger, separate scope call — flagged, not assumed.

### Tessel-inspired tile edge-matching puzzle — large scope, defer past the first wave
Tessel's core loop (place tiles so touching edges match, e.g. by color/pattern) is a
reasonable, scoped-down build on its own (a fixed tile set + fixed board, not
procedural). Its **"Custom game" generative builder** (multiple tiling geometries
including Penrose tiling, adjustable difficulty/piece-count sliders, presumably a
constraint-solver-backed generator ensuring every custom puzzle is solvable) is a
substantially larger undertaking — real computational geometry for non-square tilings,
a generation+solvability-verification pipeline, and a much bigger settings surface than
any existing game in this catalog has. Recommend: build the **fixed-board square-grid
edge-matching version first** (own game, own scope, ships value immediately, same
"honest MVP cut" culture as Klondike dropping multi-card runs) and treat the full
generative Custom-game builder as an explicit future phase on top of it, not part of the
same initial build. Do not silently fold Penrose-tiling support into a "v1."

### Tower Defence — large scope, defer
Real-time enemy waves, pathing, tower placement/upgrades/targeting, and an economy loop
is a different genre of build than everything else in this catalog (which is turn-based
or simple-input-driven). It's the one item on this whole list that could plausibly want
more than Compose's declarative-recomposition model comfortably gives for a real-time
loop with many simultaneously-animating entities (Air Hockey's frame-stepped
`withFrameNanos` physics is the closest existing precedent, but Tower Defence has an
order of magnitude more moving parts — enemies, projectiles, towers, path-following —
at once). Recommend treating this as its own future ADR-scoped decision (worth
revisiting whether it's the one place a lighter-weight canvas/game-loop library earns
its way in) rather than queuing it as a same-shaped `GameModule` alongside the puzzle
games above. Not started, not scaffolded.

### Boardgame Pal's utility toolkit — architecturally distinct, recommend one bundled entry
Dice, Coin Toss, Random Letter, Scoreboard, Life Points, Hourglass, First Player, and
Teams are not games in the `GameModule` sense at all — none of them have a win
condition, most have no "match" with a start/end, and several (Scoreboard, Life Points)
are literally just persistent counters. Forcing each into `GameModule`'s
`startMatch()`/`endMatch(result: GameResult)` shape (which assumes winners/losers/scores
tied to a match) would be a bad architectural fit purely to reuse a shell built for a
different kind of thing. Two real findings from this:

- **`GameCategory` has no home for these today** — the enum is `BOARD, CARD, ARCADE,
  PUZZLE, WORD, OTHER`. `OTHER` is the closest fit but doesn't communicate "utility
  tool, not a game" to a player browsing the menu.
- **Recommendation**: build these as **one bundled "Party Toolkit" entry** — a single
  menu launch point with its own internal tab/page navigation between the 8 tools —
  rather than 8 separate catalog entries. This matches how Boardgame Pal itself
  presents them (one app, one bottom-nav, many tools) and avoids cluttering the main
  game grid with 8 entries that are each individually tiny. Each tool is a small,
  mostly-stateless Compose screen (a dice roller's "game state" is just "last roll(s)");
  Scoreboard/Life Points are the only two that need any persistence
  (`preferencesDataStore`, same pattern as every existing stats store), for remembering
  player names/running totals across a session.
- **Open architecture question, deferred to whenever this is actually built**: does
  "Party Toolkit" register as a token `GameModule` purely so it appears in the existing
  menu/nav plumbing (with a no-op `startMatch`/`endMatch` pair, since nothing about it
  is a "match"), or does it get its own top-level menu section outside the
  `GameModule`/`GameSection` system entirely? Recommend the former for menu-integration
  consistency, revisit if it feels forced once actually built.
- Not started, not scaffolded — flagged as a distinct effort from the puzzle-game batch
  above, worth its own pass rather than interleaving with Sudoku/Dots and
  Boxes/Lights Out.

## Additional games inspired by, but not directly shown in, the reference photos

These extend the same genres the reference apps established (grid puzzles, simple
abstract-strategy board games) without duplicating anything already in the 13-game
catalog or already listed above.

- **Connect Four** — drop-a-disc, 4-in-a-row, gravity-constrained columns. A genuinely
  different tactical shape from Tic-Tac-Toe/Checkers/Chess despite the superficial
  "grid + pieces" similarity (gravity + longer runs changes the solved-game structure
  entirely), and a well-known enough title that it's worth a real minimax-with-pruning
  bot ladder analogous to Tic-Tac-Toe's HARD tier, scaled by search depth per
  `CpuDifficulty` tier rather than full solve (Connect Four's full game tree is much
  larger than Tic-Tac-Toe's).
- **Color Flood** — see "Color Match" above; recommended as the same build, not a
  separate one.
- **Nonogram / Picross** — row/column numeric clues describing run-lengths of filled
  cells; fill the grid to satisfy both. A natural extension of the "grid logic puzzle"
  family Minesweeper/Sudoku/Lights Out are building out, with its own generator
  challenge (generating a puzzle with a *unique* solution is a real constraint-
  satisfaction problem, not a trivial scramble) — flag this honestly as the hardest
  generator problem in this whole list if picked up, likely wanting a real constraint
  solver rather than the "generate solved then reduce" idiom used elsewhere.
- **Breakout / Brick Breaker** — real-time paddle-and-ball arcade action. Reuses Air
  Hockey's existing continuous-physics/frame-stepped-loop infrastructure almost
  directly (circle-vs-AABB collision instead of circle-vs-circle, a static brick grid
  instead of a second paddle) — meaningfully lower-risk than Tower Defence's real-time
  ask specifically because it's built on a pattern this codebase has already shipped and
  verified once (Air Hockey), not a new one.
- **Kakuro / KenKen** — numeric grid puzzles in the Sudoku family (Kakuro: crossword-
  shaped sum clues; KenKen: irregular "cage" regions with an arithmetic-target clue).
  Lower priority than Nonogram/Sudoku — genuinely niche relative to the other picks here,
  worth mentioning for completeness rather than near-term scheduling.

## Cross-reference against existing scope decisions

README.md's Roadmap item 13 already explicitly scoped down a prior three-part ask
(Solitaire/Poker/Hearts, other Tic-Tac-Toe variants, picture puzzles) to one
representative each (Klondike Solitaire, Misère Tic-Tac-Toe, Sliding Puzzle) and
explicitly left Poker, Hearts, other Tic-Tac-Toe variants, and jigsaw puzzles
undone. Nothing in this document re-opens that decision — Poker/Hearts and jigsaw stay
out of scope unless the project owner separately revisits item 13 itself. None of the
games proposed above duplicate anything in the existing 13-game catalog.

## Suggested build order

1. ~~Minesweeper~~ — done.
2. ~~Sudoku~~ — done.
3. ~~Lights Out~~ — done.
4. Dots and Boxes — first two-player-shaped new game in this batch, exercises
   `SINGLE_DEVICE_PASS_AND_PLAY` + bot the same way Checkers/Chess do.
5. Color Flood ("Color Puzzle") — reuses `MinesweeperGame`'s own flood-fill BFS shape.
6. Connect Four — a second solid two-player abstract-strategy game with a real bot
   ladder.
7. Tessel-style fixed-board edge-matching puzzle (without the generative Custom-game
   builder — that stays a later phase).
8. Party Toolkit (Boardgame Pal set) — bundled single entry, own pass, architecturally
   distinct from everything else above.
9. Breakout — after Party Toolkit, reusing Air Hockey's physics infrastructure.
10. Nonogram / Kakuro / KenKen — later; Nonogram if picked up before the other two,
    given it's the more widely-recognized title of the three.
11. Tower Defence — own future ADR before any implementation starts; the one item here
    that may not fit this app's declarative-Compose model as comfortably as everything
    else on this list does.

**Standing process note for whatever game is picked up next**: run a background
adversarial-review workflow against any newly-written engine before calling it done,
scaled to the engine's actual algorithmic risk (Sudoku's uniqueness-guaranteeing
backtracking solver got a full 3-dimension review; Lights Out's much simpler XOR-toggle
engine got a single-dimension one) — both passes found real, confirmed bugs an
otherwise-thorough test suite missed on its own. Also worth an explicit check each
time: does this engine share the `pause()`/`resume()`-are-no-ops-with-a-wall-clock-timer
pattern that turned out to be latent in Minesweeper, Sudoku, Lights Out, AND
`SlidingPuzzleGame`? If so, fix it there too rather than assuming it's already handled.

This order is a recommendation, not a commitment — the project owner may reprioritize
at any point, same as every other roadmap item in this project.
