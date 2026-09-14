# Edge Match — Design

**Status:** Approved by the project owner via brainstorming, ready for an implementation plan
**Date:** September 2026
**Deciders:** the project owner

## Context

Seventh game in the new-games batch (`docs/NEW_GAMES_BRAINSTORM.md`), picking up its own
"Tessel-inspired tile edge-matching puzzle" entry. That entry explicitly recommends building
**"the fixed-board square-grid edge-matching version first... own scope, ships value
immediately"** and treating Tessel's full generative "Custom game" builder (multiple tiling
geometries including Penrose tiling, adjustable piece-count sliders, a constraint-solver-backed
generator) as an **explicit future phase**, not part of this build. This doc scopes that first
version, settled through a round of brainstorming with the project owner — every decision below
was a real fork with trade-offs, not assumed.

Real Tessel's actual mechanic is placing tiles from a shared pool (choosing both position AND
rotation). This design is a deliberately simpler sibling, not an attempt to clone that mechanic:
**every tile starts already in its correct grid cell; the only action is rotating a tile in
place.** This was the single highest-leverage decision in scoping this game — it turns an
open-ended placement/tiling problem (a much harder generation and uniqueness-verification
problem, closer to or beyond Sudoku's own uniqueness-guarantee work) into a puzzle that's
**solvable by construction**, the same idiom `LightsOutGame`/`ColorFloodGame` already use: scramble
a known-solved board via a reversible operation (there, toggling; here, rotating), and the puzzle
is trivially guaranteed solvable because undoing that exact operation always works.

## Core mechanic

- **Board**: an N×N grid of square tiles.
- **Each tile has 4 edges** (top/right/bottom/left), each carrying a color index (`0 until
  colorCount`).
- **A "match"** is: for every pair of adjacent tiles, the color on the touching side of one
  equals the color on the touching side of the other. Outer border-facing edges (the board's
  own outside boundary) are **unconstrained** — there's no neighbor there, so nothing to match;
  their color is cosmetic only. No "must show a neutral border color" rule — that's real
  edge-matching puzzles' (e.g. MacMahon Squares) own added constraint for guaranteeing
  uniqueness, which this design doesn't need (see **Generation** below for why).
- **Win condition**: every interior (non-border) touching edge pair currently matches. This is a
  weaker requirement than "reproduce the original pre-scramble arrangement" — any rotation state
  where everything happens to line up counts as solved, even if it's not bit-for-bit the
  original solved board. That's fine, even desirable: it's the same "any fully-consistent state
  wins" spirit `ColorFloodGame`'s own KDoc describes for its own win condition, and it's what
  makes the uniqueness question moot (see below).
- **Interaction**: tap a tile to rotate it 90° clockwise; repeated taps cycle through all 4
  orientations. This is the only sensible gesture given this app's established no-drag/
  no-long-press input model — same idiom as `LightsOutGame`'s own tap-to-toggle.
- **Live match feedback**: every currently-matching interior edge is highlighted in real time as
  the player rotates tiles, recomputed on every state change. Matches this app's established
  "full information, no hidden state" puzzle culture — Sudoku flags a wrong entry immediately,
  Lights Out/Color Flood show the whole board plainly. The challenge here is spatial/rotational
  reasoning, not memory of hidden state.

## Generation

The **solved** reference board is built directly from a seam-based representation, not by
placing tiles first and inferring seams:

1. For every INTERIOR seam — a shared edge between two horizontally or vertically adjacent
   tiles — assign one random color (`0 until colorCount`). This is exactly the same
   indexing shape `DotsAndBoxesGame` already uses for its own `horizontalEdges`/`verticalEdges`
   (`(rows-1) x cols` horizontal seams, `rows x (cols-1)` vertical seams for an N×N board), reused
   here for color assignment instead of a boolean "drawn" flag.
2. For every BORDER-facing side (a tile edge with no neighbor), assign an independent random
   color — cosmetic only, per the win-condition note above.
3. Each tile's own canonical 4-color record (top/right/bottom/left, in UN-rotated orientation)
   is read off directly from its 4 relevant seam/border assignments.
4. **Scramble**: give every tile an independent random rotation (`0..3`, in 90° steps). A
   rotation only permutes which of a tile's 4 FIXED colors sits on which side — it never changes
   which 4 colors that tile owns. So every scrambled tile is trivially guaranteed solvable:
   rotating it back to its original orientation restores every one of its seams to the solved
   assignment. No solvability proof needed beyond this construction, same as `LightsOutGame`'s
   own scramble.
5. **Reject a trivially-already-solved scramble**: after applying step 4's random rotations,
   check the real win condition (not just "did every tile land on rotation 0" — a tile with
   rotationally-symmetric colors, e.g. all 4 edges the same color, could land on a nonzero
   rotation and still happen to match everywhere). If the board is already fully matched, re-roll
   the scramble. Same "never hand the player an already-solved board" idiom `LightsOutGame.scramble`
   already follows.

**No uniqueness verification is needed** (unlike `SudokuGame`'s generator, which has to prove a
carved puzzle has EXACTLY one solution for the puzzle to be fair) — because the win condition
here only asks "is everything currently consistent," not "did you find the one true intended
arrangement." If some tile's color pattern happens to have a rotational symmetry, there may be
more than one rotation of it that keeps the board matched — that's a non-issue, not a fairness
bug, since ANY fully-matched state is a legitimate win by this design's own definition. This is
what makes generation for this variant fundamentally simpler than both Sudoku's and (deferred)
real Tessel's own placement-based generation problem.

## Difficulty

Both board size AND color count scale together, the same lever `ColorFloodGame` already uses
(more cells AND more colors to coordinate both make optimal play genuinely harder, not just
longer):

| Tier   | Board | Colors | Tiles |
|--------|-------|--------|-------|
| EASY   | 4×4   | 4      | 16    |
| MEDIUM | 6×6   | 5      | 36    |
| HARD   | 8×8   | 6      | 64    |

## Stats, daily seed, session shape

- **Two-metric stats** — best moves AND best time per tier (`EdgeMatchStatsStore`, mirroring
  `LightsOutStatsStore`'s exact shape, not the time-only shape Minesweeper/Sudoku use). A
  "move" is one tap-rotation, counted unconditionally on every valid tap — including a tap on a
  tile that's already correctly oriented, or one that rotates a tile a net-zero amount over
  several taps — same "every state-changing input counts as a move, no exemption for an
  unproductive one" idiom `LightsOutGame.press()` already follows. There's a real MINIMUM move
  count to reach a solved board (0–3 rotations needed per tile, 0 if it's already correctly
  oriented), which is the actual skill metric being chased — a player can always take more than
  that minimum, same as any other puzzle here.
- **Daily-seed mode** (`edge-match-daily` route), matching every other solo puzzle in this app.
- **Solo-puzzle `GameModule` shape**: `SINGLE_PLAYER_VS_BOT` only, no real bot — difficulty is
  the puzzle itself, same idiom as Minesweeper/Sudoku/Lights Out/Color Flood. `matchOver` /
  `puzzlesSolved` / `timerStartElapsedRealtime` / `pausedAtElapsedRealtime` /
  `totalPausedMillis` fields all mirror that same established pattern.
- **Both now-well-known bug patterns fixed in from the start**, not found after the fact this
  time (unlike Minesweeper/Sudoku/Lights Out, which shipped them first and needed a consolidated
  follow-up fix — see that fix's own commit): the gameplay-mutating method (`rotateTile`) checks
  `matchOver.value`, not just the per-board `isOver`; `pause()` is idempotent
  (`pausedAtElapsedRealtime == null` guard) from day one.
- **Music profile**: reuses `MusicProfiles.PUZZLE_FOCUS`, the shared "quiet solo puzzle" profile
  every other solo puzzle in this batch (Minesweeper/Sudoku/Lights Out/Color Flood) already
  aliases to — not a new bespoke profile. Bespoke profiles in this app are reserved for
  2-player games (Dots and Boxes, Connect Four, Checkers, Chess, Dominoes, Mancala); this isn't
  one.

## Naming

**"Edge Match"**, `gameId = "edge-match"`. Deliberately not named "Tessel" (the third-party
reference app's own name, not a generic genre term the way "Minesweeper"/"Sudoku" are) — matches
how `docs/NEW_GAMES_BRAINSTORM.md` itself already avoids that name, calling this a "Tessel-
inspired tile edge-matching puzzle" rather than just "Tessel."

## Deliberate scope cuts (honest MVP, same spirit as every other game's own documented cuts)

- **No tile placement or swapping** — every tile stays in its original grid cell for the whole
  game; only rotation is a player action. That's the entire reason this design is tractable as a
  first version; a placement/pool variant is real Tessel's own mechanic and a substantially
  larger, separate scope (see this doc's own intro).
- **No non-square tiling geometries, no Penrose tiling** — an explicit, named future phase in
  `docs/NEW_GAMES_BRAINSTORM.md` already, not silently folded into this build.
- **No hint/solver** — same reasoning every other puzzle in this batch gives for skipping one
  (a genuine hint needs real constraint-satisfaction reasoning over the current board state, a
  meaningfully separate feature from the board engine itself).
- **No "must show a neutral border color" rule** — border-facing edges are simply unconstrained;
  see **Core mechanic** above for why this doesn't cost anything (no uniqueness requirement to
  protect).
- **No mistake tracking** — unlike Sudoku, there's no "right vs. wrong entry" concept here (a
  rotation is never itself wrong, only incomplete), so there's nothing analogous to track.

## Build order / next step

Per this batch's own standing process note: run a background adversarial-review pass against
the freshly-written engine before calling it done, scaled to its actual algorithmic risk. This
design's generation algorithm is simpler than Sudoku's (no uniqueness search) and about on par
with Lights Out's/Color Flood's own (a single-pass construction + a reject-and-retry check), so a
similarly-scaled single-dimension review is the right default — but the implementation plan
should make that call explicitly rather than skip it.

Next step: hand this doc to `writing-plans` (or equivalent) to produce a concrete implementation
plan — engine (`EdgeMatchGame.kt`/`EdgeMatchState`), stats store, unit tests, then (as a likely
separate pass, matching this batch's own established two-pass rhythm) the UI screen and menu/
route/strings wiring.
