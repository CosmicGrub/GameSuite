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

### Dots and Boxes — ✅ shipped
`games/dotsandboxes/DotsAndBoxesGame.kt` + `ui/DotsAndBoxesScreen.kt`. A 5x5-box grid
(the classic size); players draw one edge per turn; completing a box's 4th edge scores
it and grants another turn; most boxes wins. The first TWO-PLAYER game in this batch —
meaningfully different from every solo puzzle before it (Minesweeper/Sudoku/Lights
Out): edge-selection on a grid, not cell-selection, and `GameCategory.BOARD` (a
territory-scoring game like Checkers, not a solo puzzle). Board size is FIXED (not
scaled by difficulty, unlike the solo puzzles) — `difficulty` instead tunes the BOT,
same idiom as Checkers/Chess/Dominoes. Supports both `SINGLE_PLAYER_VS_BOT` and
`SINGLE_DEVICE_PASS_AND_PLAY` (both exposed as separate menu entries, matching
Tic-Tac-Toe/UNO's precedent), and reuses `TicTacToeGame`'s own "alternate who starts"
fairness idiom across Play Again rounds and `DominoGame`'s own per-player-state-list
and session-tally shape. Bot has three real tiers: EASY is uniform random with zero
strategy; MEDIUM and HARD both take any free box available and refuse to create a
3-edge box (the one real skill in this game — handing the opponent a free box) when a
safer move exists; HARD additionally picks the SMALLEST forced sacrifice via a
one-ply chain-reaction simulation when no safe move exists at all. The real "double-
cross" expert counter-strategy (deliberately leaving a chain's last 2 boxes to force
the opponent to open the next chain) is a documented, deliberate scope cut — a
genuinely more complex technique on top of what HARD already does, left for later.
Got its own bespoke ambient-music profile (`MusicProfiles.DOTS_AND_BOXES`), not an
alias to `PUZZLE_FOCUS` — matching how every other 2-player board game in this app
(Chess/Checkers/Mancala/Dominoes/Tic-Tac-Toe) gets its own profile, since that alias
is reserved for quiet solo puzzles. 14 unit tests passing.

**One real bug found by a 2-dimension background adversarial-review workflow
(proportionally scaled between Sudoku's 3-dimension pass and Lights Out's 1-dimension
pass, matching this engine's own moderate complexity — turn management plus a 3-tier
bot with a chain-simulation heuristic)**: the "complete a box → go again" rule was
implemented as `completedCount > 0 && !allClaimed`, but every edge on the board
borders at least one box, so the move that completes the LAST box on the board always
has `completedCount > 0` too — meaning that guard is unsatisfiable at exactly the
moment the board ends, and `currentPlayerIndex` in the terminal state always named the
player who did NOT just make the winning move as "current," rather than the actual
mover. No effect on scoring, the declared winner, or further play (the board is frozen
either way once over), but a real, always-reproducible data inconsistency in the
terminal state — the exact kind of thing a future UI element (or this screen's own
`lastAction` text, or a differently-written status display) could reasonably get
wrong by trusting it. **Fixed** by dropping the `!allClaimed` condition entirely
(`completedCount > 0` alone is both correct and simpler than what it replaced) and
covered by a new regression test that plays a full board to completion and asserts
the actual final mover stays "current."

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

### Color Match ("Color Puzzle" in Chogan's preview) — ✅ shipped as Color Flood
`games/colorflood/ColorFloodGame.kt` + `ui/ColorFloodScreen.kt`. The reference material
only showed a "coming soon" card, not actual gameplay, for this one — two well-known
real games share the "Color Puzzle" name in the wild (Color Flood vs. a Bejeweled-style
match-3), and Color Flood was the deliberate pick: a grid of colored cells, flood-fill
outward from the top-left corner by repeatedly picking a color, win by making the whole
grid one color. Honestly scoped (a grid + a flood-fill) versus match-3's considerably
larger swap-and-settle/cascade-combo animation engine, and this build also covers the
separately-listed "additional inspired-by" Color Flood pick below, so one game serves
both entries rather than building two near-duplicates. If the project owner specifically
wants match-3 gameplay later, that's a distinctly larger, separate scope call.

Reuses `MinesweeperGame`'s own iterative flood-fill BFS shape almost exactly, just
applied to color equality instead of "is a zero." EASY/MEDIUM/HARD scale BOTH board
size and color count (9x9/4 colors, 12x12/5, 16x16/6) — difficulty here comes from more
cells AND more colors to coordinate, not just a bigger board. Best-moves-and-best-time
stats (two-metric, like Lights Out — a real, honest minimum-moves skill metric exists).
**No move limit, no loss state** — a deliberate design decision, not a scope cut: every
random coloring is trivially solvable (repeatedly picking any color not yet in the
territory strictly grows it), so there's no "you lost" outcome to build; move count is
tracked purely as a personal-best stat. 13 unit tests passing, including an
independently-written BFS that re-verifies territory connectivity from scratch and a
deterministic "always pick a boundary color" solving strategy (proven to always make
progress) used to drive real boards to completion in tests, rather than a strategy that
could stall.

**Two real bugs found by a single-dimension background adversarial-review workflow**
(same proportional scaling as Lights Out's — this engine's flood-fill logic carries
similarly low algorithmic risk):
- **MEDIUM: the main gameplay method only checked the per-board `isOver` flag, not the
  session-level `matchOver` flag.** A `pick()` call after `leaveSession()` had already
  delivered the final `GameResult` could still mutate state — including setting
  `won = true` with no second result ever reported. Not reachable through this game's
  actual screen (`leaveSession()` is only ever wired to the finished-board panel's own
  button, which only renders once the board is already over), but a real gap worth
  closing regardless, the same spirit as Sudoku's own `selectCell` bounds-check fix.
  **Fixed**, and — since the identical `isOver`-but-not-`matchOver` gap turned out to
  exist in Minesweeper/Sudoku/Lights Out/Dots and Boxes too (same copy-pasted
  precedent) — spawned as a consolidated follow-up task to apply the same fix there.
- **LOW: `pause()` wasn't idempotent the way `resume()` already was.** A second
  `pause()` call with no `resume()` in between silently overwrote the pause anchor,
  dropping the interval between the two calls from the paused-time tracking (inflating
  the eventual recorded solve time). The verifier confirmed the arithmetic is real but
  also confirmed it isn't reachable today (Android's lifecycle guarantees
  onPause()/onResume() strictly alternate, and this app has exactly one call site of
  each) — still fixed defensively, and bundled into the same follow-up task above,
  since the identical asymmetry exists in all four sibling engines.

### Tessel-inspired tile edge-matching puzzle — fixed-board half ✅ shipped as Edge Match
`games/edgematch/EdgeMatchGame.kt` + `ui/EdgeMatchScreen.kt`. Tessel's core loop (place
tiles so touching edges match, e.g. by color/pattern) is a reasonable, scoped-down build
on its own (a fixed tile set + fixed board, not procedural). Its **"Custom game"
generative builder** (multiple tiling geometries including Penrose tiling, adjustable
difficulty/piece-count sliders, presumably a constraint-solver-backed generator ensuring
every custom puzzle is solvable) is a substantially larger undertaking — real
computational geometry for non-square tilings, a generation+solvability-verification
pipeline, and a much bigger settings surface than any existing game in this catalog has
— and stays exactly that: an explicit, NOT-YET-STARTED future phase. Do not silently
fold Penrose-tiling support into what's shipped. See README Roadmap item 20 for the full
build writeup (generation-by-construction rather than a solver, the select/rotate/swap
interaction, difficulty scaling, verification).

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
- ✅ **Shipped** as Party Toolkit (`games/partytoolkit/PartyToolkitGame.kt` +
  `ui/PartyToolkitScreen.kt`, README Roadmap item 21) — scoped through a real
  brainstorming round with the project owner first (`docs/PARTY_TOOLKIT_DESIGN.md`,
  approved), same discipline as Edge Match. Built as the recommended token
  `GameModule` with the new `GameCategory.UTILITY` value. Two deliberate revisions
  from this doc's own original call, made during the build and recorded in the design
  doc itself: tabs instead of a landing grid (the crowding concern below didn't hold
  up in practice), and independent per-tool player lists instead of one shared
  roster. See README item 21 for the full build writeup, including a second
  "approved design doc found after building started" process incident (this time a
  completeness gap against the Hourglass tool's spec, closed directly rather than
  needing the project owner's own call) and two real bugs caught and fixed during
  on-device verification.

## Additional games inspired by, but not directly shown in, the reference photos

These extend the same genres the reference apps established (grid puzzles, simple
abstract-strategy board games) without duplicating anything already in the 13-game
catalog or already listed above.

- **Connect Four** — ✅ shipped. `games/connectfour/ConnectFourGame.kt` +
  `ui/ConnectFourScreen.kt`. Drop-a-disc, 4-in-a-row, gravity-constrained columns — a
  genuinely different tactical shape from Tic-Tac-Toe/Checkers/Chess despite the
  superficial "grid + pieces" similarity (gravity + longer runs changes the
  solved-game structure entirely). Got the real minimax-with-pruning bot ladder this
  entry called for, analogous to Tic-Tac-Toe's HARD tier but scaled by search depth
  per `CpuDifficulty` tier rather than a full solve, since Connect Four's full game
  tree (~4.5 trillion legal positions) is far too large for that. See README Roadmap
  item 19 for the full build writeup, including the process note on this one landing
  as independently-authored parallel work from a concurrent session that this pass
  verified and built the UI/wiring layer on top of, rather than duplicating.
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
4. ~~Dots and Boxes~~ — done.
5. ~~Color Flood~~ — done.
6. ~~Connect Four~~ — done.
7. ~~Tessel-style fixed-board edge-matching puzzle~~ — done, shipped as Edge Match
   (the generative Custom-game builder, including Penrose tiling, remains its own
   later phase — not started).
8. ~~Party Toolkit (Boardgame Pal set)~~ — done, shipped as Party Toolkit.
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
