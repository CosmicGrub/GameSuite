# G4b Quality scorecard: puzzle and casual games (8)

Scope: `minesweeper`, `lights-out`, `sliding-puzzle`, `color-flood`, `game-2048`, `snake`, `flow-lines`, `six-ball-puzzle` in `Z:\GameSuite-wt-game-kit` (branch `session/wave-3`, package `com.gamesuite`).
Date: 2026-11-21. Read-only: no Gradle, no adb, no emulator, nothing run. Every claim is **V** (verified by reading the cited file) or **S** (suspected, needs a device or a test run).
Bar used: `docs/roadmap/QUALITY-BAR.md` gates 1, 3, 4, 5, 7 and 8. Defect classes taken from `docs/audit/06-new-games/*.md` (version-signature collisions, animated state read in composition, best-score write cancelled by an effect, resume/pause gaps, small targets, silent no-ops, EN-only text).
Scale 0-3 per axis, 3 = meets the bar. Total is out of 15.

Convention: "game" files are under `app/src/main/java/com/gamesuite/games/<pkg>/`, "screen" files under `app/src/main/java/com/gamesuite/ui/`.

## Summary table

| game | gameplay | feel | robustness | a11y_i18n | tests | total /15 |
|---|---|---|---|---|---|---|
| minesweeper | 2 | 2 | 2 | 1 | 2 | 9 |
| lights-out | 3 | 2 | 3 | 1 | 2 | 11 |
| sliding-puzzle | 2 | 2 | 3 | 1 | 2 | 10 |
| color-flood | 1 | 3 | 3 | 1 | 2 | 10 |
| game-2048 | 3 | 3 | 2 | 3 | 2 | 13 |
| snake | 3 | 2 | 2 | 2 | 3 | 12 |
| flow-lines | 3 | 2 | 3 | 3 | 3 | 14 |
| six-ball-puzzle | 3 | 3 | 2 | 2 | 3 | 13 |

Ranking, weakest first: minesweeper 9, sliding-puzzle 10, color-flood 10, lights-out 11, snake 12, game-2048 13, six-ball-puzzle 13, flow-lines 14.

Evidence snapshot (counts from the screens, V):

| screen | `stringResource` | prose `Text("...")` literals | LocalReducedMotion | LocalGameSpeed | LocalColorblindMode | idle infinite animation |
|---|---|---|---|---|---|---|
| Minesweeper | 0 | 40 | yes | no | no | no |
| LightsOut | 0 | 11 (+ more in descriptions) | yes | no | no (shape/lum cues) | no |
| SlidingPuzzle | 0 | 8 (+ chip labels) | yes | no | no | no |
| ColorFlood | 0 | 10 | yes | no | yes (shape marks) | no |
| G2048 | 39 | 0 | yes | yes | yes | no |
| Snake | 33 | 2 | yes | no (documented) | yes | no |
| FlowLines | 34 | 0 | yes | no | yes (palette + shapes) | no |
| SixBall | 61 | 3 | yes | yes | yes | **yes** (`SixBallScreen.kt:746-749`) |

EN/ES key parity (V, `<string>` counts): g2048 60/60, snake 53/53, flowlines 50/50, sixball_pig 132/132. No `values-es` file exists for Minesweeper, Lights Out, Sliding Puzzle or Color Flood in-game text, because those screens contain no `R.string` references at all (only the coach cards and `game_*` titles are translated).

---

## 1. Minesweeper (`minesweeper`) 9/15

Files: `games/minesweeper/MinesweeperGame.kt`, `MinesweeperStatsStore.kt`, `ui/MinesweeperScreen.kt`, tests `MinesweeperGameTest.kt` (27 `@Test` across 2 files).

### Strengths (V)
- First-click safety is real: `placeMines` runs on the first reveal and excludes the clicked cell and all 8 neighbours (`MinesweeperGame.kt:683-692`), and there is a test over many trials and difficulties (`MinesweeperGameTest.kt:61`).
- Flood-fill is iterative (BFS, no recursion), leaves flags alone (`floodReveal`). Win is `revealed == rows*cols - mines`, loss reveals mines and keeps correct flags.
- Hint is a real constraint solver (subset reduction), distinguishes a proven-safe reveal from a risk-ranked guess, counts the hint and forbids a best time afterwards (`MinesweeperGame.kt:463`, screen `:331-336`).
- Snapshot is validated hard (roster, size, mine count only once placed, flag count, exploded/won consistency, `MinesweeperGame.kt:368-410`), timer is pause-aware.
- Long-press flag plus a 48dp Reveal/Flag mode toggle (alternate non-gesture path), bevel look, loss shake and flag pop honour Reduced Motion (`MinesweeperScreen.kt:815-818,1025`), haptic strength scales with cascade size (`:311`).

### Findings below 3
- **Gameplay 2**
  - Daily board is not the same board for everyone (V, medium-high). Mines are placed on the *first reveal*, with the seeded `Random` shuffling "all cells except the first-click safe zone" (`MinesweeperGame.kt:590-592`, `:683-692`). Two players who open different cells get different mine layouts. The Hub promises "The same puzzle for everyone today" (`GameHubSpecs.kt:138-139`). The test only reveals cell 0 in both games (`MinesweeperGameTest.kt:168-181`), so it cannot see this.
  - No chording (V, documented scope cut, `MinesweeperScreen.kt:157`). Tapping a satisfied number does nothing, so Hard (16x30, 99 mines) is a long tap marathon. Standard Minesweeper players expect chord.
  - No "no-guess" generation, so some boards force a coin flip, and the hint then offers a "?" guess (`MinesweeperGame.kt` hint plan, `isGuess`). Acceptable for classic, but the Daily should be solvable without guessing.
  - Snapshot does not check `adjacentMines` against the mine layout, only ranges (`:383-384`). A tampered save can show wrong numbers. Low.
- **Feel 2**
  - Hard board min cell is 28dp (`MinesweeperScreen.kt:93`), so the 16x30 grid is 840dp wide and pans on a phone (V). Targets are below 48dp and there is no zoom. Pan plus tap plus long-press on small cells is the biggest ergonomic risk (S on real feel).
  - No win cascade or sound beyond chime and celebration haptic. No per-reveal sound variation. Good enough but not "Clubhouse".
- **Robustness 2**
  - Best-time write runs inside `LaunchedEffect(s.isOver, board)` after `endHandledBoard = board` is set (`MinesweeperScreen.kt:269-277`). If the effect is cancelled mid-`edit` (new board, navigate away) the time is never retried (S; same class as Echo Pads F5).
  - Hint planner is O(c^2) per pass over all frontier constraints on the main thread, inside `hint()` (`MinesweeperGame.kt:84-128`). On a late Hard board with hundreds of constraints this can take tens of ms (S).
  - Pause/resume timer correct; resume of a not-yet-started board correct. Process-death resume exists and is tested.
- **A11y_I18N 1**
  - Entire screen is English-only: 0 `stringResource`, about 40 prose literals (`MinesweeperScreen.kt:636-638` tier names, `:1327` Hint, `:1383-1417` result panel, `:1458`, help text `:376`). Cell descriptions ("Hidden, row 3, column 4") and click labels are English strings built in code (`:974`). QUALITY-BAR gate 7 fails. This game is on the audit's EN-only list (04-accessibility H1).
  - Positive: every cell is one button with state + row + column, polite live region for mines left (`:730`), 48dp for non-grid controls (`:678,713,1246`).
  - Number colours: digit is always drawn, so colour is not the only cue (OK). `LocalColorblindMode` is ignored (acceptable here).
- **Tests 2**: 27 tests. Good coverage of first click, flood, daily reproducibility (weak), resume, hint, clock. No fuzz (random legal play never throws, snapshot round trip at random mid-game states) and no test that two different first clicks on the same daily seed agree.

### Top 5 fixes (ranked by value for effort)
1. **(M) Make the Daily layout independent of the first click.** Generate the daily mine layout from the seed alone at `startMatch(dailySeed)`; on first reveal, if the clicked cell is a mine or has a mine neighbour, move those mines to the first free cells in a fixed seed-derived order (the classic "relocate" rule). Add a test: two games, same seed, different first cells, assert identical layouts outside the relocated cells.
2. **(L) Extract all strings to `res/values/minesweeper_strings.xml` + `values-es`** (tiers, Hint/New Board/Back, result panel, cell descriptions with `%1$d` row/col, help text). Add `pluralStringResource` for "n of m safe squares". Add the ratchet test suggested by audit 04 M5.
3. **(S) Add chording**: tap a revealed number whose flag count equals its value to reveal the other neighbours; wrong flag loses. Keep it behind the existing tap path (`MinesweeperScreen.kt:290-316`) and a Hub option "Chord" default on. Add engine tests.
4. **(S) Move the stats write out of the cancellable effect**: write from `MinesweeperGame` win callback or `rememberCoroutineScope` + `NonCancellable`, and rethrow `CancellationException`. Same for the loss counter.
5. **(M) Pan/zoom ergonomics for Hard**: pinch zoom or a "Large cells" toggle that locks 40dp cells with two-axis pan, plus a long-press haptic confirmation tick; test on a 360dp phone and the Fold cover screen.

---

## 2. Lights Out (`lights-out`) 11/15

Files: `games/lightsout/LightsOutGame.kt`, `LightsOutStatsStore.kt`, `ui/LightsOutScreen.kt`, tests `LightsOutGameTest.kt`, `LightsOutResumeUndoHintTest.kt` (35 tests).

### Strengths (V)
- Solvable by construction: the board is made by pressing random cells from the solved state (`LightsOutGame.kt:511-520`), re-rolled if all dark. The snapshot decoder re-checks solvability with a GF(2) Gaussian elimination (`:180-200`, `solveBoard`).
- Hint is the minimum-press solution of the *current* board (`:268-280`, enumerates free columns up to 12), so it stays correct after player mistakes. Undo is an exact snapshot stack, hints only mark the board assisted.
- Daily is deterministic and independent of player input (seed fully determines the scramble, `startMatch(dailySeed)`).
- Cell glow is read in `drawBehind` (draw phase), not in composition (`LightsOutScreen.kt:790-836`). Reduced Motion snaps (`:792`). Hint ring is a shape, not a colour.

### Findings below 3
- **Feel 2**: cells animate on/off with a fade and the result is held (`RESULT_HOLD_MS`), but there is no ripple-through of the neighbour toggles, no per-press sound variation and no cell-pressed motion beyond a white overlay (`:828-836`). The Brush.radialGradient is allocated per lit cell per frame during a fade (S, minor). No `LocalGameSpeed` read (V, acceptable for a puzzle).
- **A11y_I18N 1**
  - EN-only: 0 `stringResource`; descriptions "Light on/off, row r, column c" (`LightsOutScreen.kt:786-789`), "Flip this light and its neighbors" (`:802`), "Undo"/"Hint" (`:617,631`), result panel (`:896-937`), tiers (`:471-473`) are literals.
  - Min cell is 28dp (`:77`), on a 7x7 Hard board in a short landscape slot cells fall below 48dp and the board pans (V by constant, S for real device).
  - No `stateDescription` on cells (state is inside `contentDescription`), no `collectionInfo`. Lit/unlit is a luminance + edge difference, so colour-blind-safe (OK).
- **Tests 2**: 35 tests including resume/undo/hint. No property test that `scramble` always yields a solvable board across many seeds and sizes (it is true by construction, but unguarded), no test that `hint()` presses always strictly reduce the minimal solution size, no test that Daily seeds equal across two instances for all three sizes.
- **Gameplay note (not scored down)**: difficulty is board size only. `scramble` uses `size*size*3` random presses with repeats (`LightsOutGame.kt:512`), so a 3x3 "Easy" can be a 1-2 press board. Add a min-press floor.

### Top 5 fixes
1. **(L) Extract strings to resources + ES** (about 15 literals in the screen plus cell/undo/hint descriptions with row/col placeholders).
2. **(S) Enforce a minimum solution length per tier**: after `scramble`, call `solveBoard` and re-roll if `presses.size < floor` (Easy 3, Medium 8, Hard 14). One test across 200 seeds.
3. **(S) Cell touch target and stateDescription**: add `stateDescription = if (lit) on else off` and `collectionItemInfo` (row/col) instead of baking state into the label, and floor cell size at 40dp with pan (matches Minesweeper plan).
4. **(S) Property tests**: for 500 seeds x 3 sizes assert `solveBoard(...) != null`, `solutionPresses` solves the board when applied, undo after N random presses restores exactly.
5. **(M) Feel pass**: neighbour-ripple stagger (30ms) on press, a distinct "off" tick vs "on" tick, soft sweep on win. Keep it behind `reducedMotion`.

---

## 3. Sliding Puzzle (`sliding-puzzle`) 10/15

Files: `games/slidingpuzzle/SlidingPuzzleGame.kt`, `SlidingPuzzleStatsStore.kt`, `ui/SlidingPuzzleScreen.kt`, tests `SlidingPuzzleGameTest.kt`, `SlidingPuzzleResumeUndoHintTest.kt`, plus `androidTest/.../AirHockeySlidingPuzzleRotationTest.kt` (35 unit tests + 1 rotation UI test).

### Strengths (V)
- Solvable by construction (scramble = random legal moves from solved, no immediate backtrack, re-roll if solved; `SlidingPuzzleGame.kt` `scramble`/`generatePuzzle`). Daily is seeded, deterministic.
- Snapshot validates permutations, solved flag vs tiles, roster, size per tier (`decodeSnapshot`); initial arrangement is saved, so "Reset puzzle" survives resume.
- Image modes (numbers, built-in Sunset and Koi, player photo) with per-tile bitmaps re-sliced only on source/size change; tile position is an `Animatable` applied through the lambda `Modifier.offset { }` (layout phase, no per-frame recomposition) (`SlidingPuzzleScreen.kt:668-698`).
- Hint is a 5-ply Manhattan lookahead (`SlidingPuzzleGame.kt:405,432`).

### Findings below 3
- **Gameplay 2**
  - Only a tile *adjacent to the blank* moves (`tapTile`, `SlidingPuzzleGame.kt:478-490`). Tapping a tile in the same row/column as the blank (the normal way to slide two or three tiles at once in every commercial version) is ignored, so a 5x5 needs many more taps. V.
  - The hint is greedy with a 5-ply Manhattan lookahead, not an optimal solver, so it can oscillate (S). It is labelled "assisted" correctly.
  - No par or optimal move count is shown; best moves can be compared with nothing.
- **Feel 2**
  - A tap on a non-adjacent tile does nothing and says nothing (V, `SlidingPuzzleScreen.kt:501-510`: feedback only `if moveCount != movesBefore`). No shake, no buzz, no "why". Gate 4 asks for a "why" message on illegal taps.
  - No swipe or drag to slide. Solve sound is `sounds.playShuffle()` (`:279`), a mismatch.
- **Robustness 3**: nothing blocking. (Stats write in `LaunchedEffect(s.solved, puzzlesSolved)`, `:272-295`, is the shared cancel-risk below.) Minor: `undo` takes `maxOf` for hints (fine); `resetToInitial` keeps `hintsUsed`, so a reset after a hint stays assisted (V, intentional but undocumented).
- **A11y_I18N 1**
  - EN-only: 0 `stringResource`. Literals at `SlidingPuzzleScreen.kt:316,328` (dialogs), `:357,388,390` (status/best), `:371-384` (image chips "Numbers", "Sunset", "Koi", "Photo…"), `:810,819` (Undo/Hint), `:857-895` (result panel), `:473` ("Blank space, row r column c"), `:705` ("Slide tile n").
  - Tiles are 48dp+ on 5x5 only if the slot allows (S). `LocalColorblindMode` unused (numbers always drawn, OK; photo mode has no number overlay, so photo tiles carry no label other than the click label, S).
- **Tests 2**: 35 + one rotation UI test (good). No property test over seeds that every generated board is solvable and that applying the hint repeatedly terminates; no test of multi-tile slide (feature absent).

### Top 5 fixes
1. **(M) Multi-tile slide**: in `tapTile`, if the tapped tile shares the blank's row/column, shift all tiles between (one move counted per tile or per slide, pick one and document). Keep the single-tile path. Add engine tests plus snapshot/undo coverage.
2. **(S) Feedback on illegal taps**: light buzz + 6dp wiggle on the tapped tile + a polite "That tile can't move" status (Reduced Motion: haptic + text only).
3. **(L) Extract strings + ES**, including per-tile descriptions "Tile 5, row 2, column 3" so TalkBack hears position (today only the click label says "Slide tile 5").
4. **(M) Swipe/drag input**: drag a tile toward the blank, or swipe anywhere to slide the neighbour into the blank, as an alternative to taps (also helps motor-limited users with imprecise taps).
5. **(S) Replace `playShuffle` on solve with the shared success chime and add a par line** (A* or IDA* on 3x3 only, Manhattan+linear conflict, off main thread) so "New best" has a reference.

---

## 4. Color Flood (`color-flood`) 10/15

Files: `games/colorflood/ColorFloodGame.kt`, `ColorFloodStatsStore.kt`, `ui/ColorFloodScreen.kt`, tests `ColorFloodGameTest.kt`, `ColorFloodResumeUndoHintTest.kt` (33 tests).

### Strengths (V)
- Flood rules are right (BFS from the corner; picking the current colour is rejected). Snapshot checks `won` against the recomputed territory (`ColorFloodGame.kt` `decodeSnapshot`). Daily deterministic.
- Strong juice: result held until the celebration lands, 14dp camera shake and 32-particle burst only when Reduced Motion is off, particle frame loop runs only while particles are alive (the screen's own KDoc calls out the shared always-on loop as the thing it avoids), single `Canvas` board, `fitBoard`, cross-fade per pick (`ColorFloodScreen.kt:131-170,709,750-770`).
- Colour-blind mode adds shape marks to every cell and swatch.

### Findings below 3
- **Gameplay 1**: there is **no move limit and no par**. `ColorFloodState` has no budget field (`ColorFloodGame.kt:48-56`) and `pick` can never lose (`:410-427`). The classic game (and every clone) gives N moves and fails the board when they run out; here the only score is "fewer moves is better" with no target, so there is no tension and no lose moment. The generator is a uniform random fill (`startMatch`), which also yields boards of wildly different difficulty. The hint is greedy "colour with the largest immediate absorb" (`:291-308`), not a lookahead or optimal. (V)
- **Feel 3**: no issues beyond the missing lose state.
- **Robustness 3**: nothing blocking (shared stats-write note applies, `ColorFloodScreen.kt:255-272`).
- **A11y_I18N 1**
  - EN-only: 0 `stringResource`. Tier labels `:569-571`, Undo/Hint `:1056,1064`, result panel `:1205-1245`, "`N of M cells flooded`" board description `:653`, help text `:333`.
  - Board is one `Canvas` with one description, so TalkBack gets the count but cannot inspect cells or learn which colour a swatch is except through the swatch label (S on swatch labels, not read).
  - Colour-blind shapes are only drawn under `LocalColorblindMode` (V `:95`), not always on; the always-on default relies on hue alone for 6 colours on Hard.
- **Tests 2**: 33 tests with resume/undo/hint. No property test (random picks always terminate in a win; greedy hint always strictly increases territory).

### Top 5 fixes
1. **(M) Add a move budget and a lose state**: budget = `ceil(c * (size + colors)/ k)` or, better, `optimalApprox + slack` from a greedy/beam solver run once at deal time; show "Moves 7/22", fail with a shake and offer "Try again" / "New board". Persist it in the snapshot (schema check). Daily gets a fixed budget so scores compare.
2. **(L) Extract strings + ES** (about 15 literals, board description with `%d of %d`).
3. **(S) Generator quality gate**: reject boards that a greedy solver wins in fewer than a tier floor, or whose start region already covers more than 10 percent; store `par` in the state and show "Par 18".
4. **(S) Always-on shape marks on Hard** (6 colours), and swatch buttons announce colour name + "absorbs N cells" (data already available through `absorbCount`).
5. **(S) Guard the stats write** (same pattern as other games, see shared findings).

---

## 5. 2048 (`game-2048`) 13/15

Files: `games/g2048/G2048Game.kt`, `G2048StatsStore.kt`, `ui/G2048Screen.kt`, test `G2048GameTest.kt` (54 tests, 711 lines).

### Strengths (V)
- Rules: single merge per tile per move, merge order along the swipe direction (`G2048Rules.move`, `lastMerged` flag), spawn 2 (90%) / 4 (10%) in a random empty cell, game over from `canMove`, "keep going" after the target (512 for 3x3, 2048 for 4x4 and 5x5). Motions include every tile so the slide animation is exact.
- Snapshot validation is thorough (tile values are powers of two within `maxTile`, `won`/`keepGoing` consistency, roster) (`G2048Game.kt:439-465`). Undo is a snapshot stack and marks the game assisted so it cannot set a best (`StatsStore` doc + `G2048Screen.kt:263-267`).
- Best-score write is event-driven on every score change (self-healing if one write is cancelled, `G2048Screen.kt:263-267`), not once at game end.
- Input: swipe with 28dp threshold, 4 labelled 48dp+ buttons, arrow keys, and 4 `CustomAccessibilityAction`s on the board node (`:716-720`); one semantics node reads the whole grid with polite live region; animations scale with `LocalGameSpeed` and obey Reduced Motion (`:235-237,371-372`). ES parity 60/60.

### Findings below 3
- **Robustness 2**
  - Animation state is read in composition: `slide.value` and `pop.value` drive `lerpDp(...)` and tile `scale` arguments in the board body (`G2048Screen.kt:783-809`), so every animation frame recomposes the whole tile layer (up to 25 `G2048Tile` composables) for `slideMs + popMs`. Cheap on 4x4, noticeable on 5x5 on a mid-range phone (V for the read, S for the cost). Fix by passing `() -> Float` into `graphicsLayer`/`offset { }` lambdas.
  - Spawn RNG is not part of the snapshot (`G2048Game.kt:243`), so Undo then replay re-rolls the spawn. It is flagged assisted, so it does not break records; it only breaks "same move, same result" expectations (V, low).
  - Process-death: resume exists and is tested; a won-and-waiting-to-continue board resumes (OK).
- **Tests 2**: 54 tests are good for rules and snapshots. No property test (random move sequences never throw, tiles sum/2 conservation per move, game over implies no legal direction), and no test that `bestDirection` never returns an unmoved direction on random boards (S, it checks `moved`, so likely true).
- **Gameplay note (not scored down)**: the hint is a 1-ply heuristic (`G2048Rules.evaluate`, `:158`), adequate but weaker than a 2-ply expectimax. There is no Daily (acceptable for this game).

### Top 5 fixes
1. **(S) Move `slide`/`pop` reads into the draw/layout phase** (`Modifier.graphicsLayer { translationX = ... }`, `offset { }`) so the board does not recompose per frame.
2. **(S) Property test**: 1000 random move sequences on each size; assert cell sum grows by spawn values only, `isOver` iff `legalDirections` empty, snapshot round trip at every step.
3. **(M) 2-ply expectimax hint** (with node budget) on a background dispatcher; keep the greedy as the fallback.
4. **(S) Persist the RNG state or seed into the snapshot** so Undo is replay-stable, or at least document and test the behaviour.
5. **(S) Add a "highest tile" stat** next to best score (the store keys by size only, `G2048StatsStore.kt`), and a distinct 2048-reached moment that survives "keep going".

---

## 6. Snake (`snake`) 12/15

Files: `games/snake/SnakeEngine.kt`, `SnakeGame.kt`, `SnakeStatsStore.kt`, `ui/SnakeScreen.kt`, tests `SnakeEngineTest.kt` (425 lines), `SnakeGameTest.kt` (50 tests).

### Strengths (V)
- Engine: pure and seedable, solid walls or wrap, queued turns (max 2) with reversal blocked against the last *queued* direction, tail-vacating rule handled correctly (`next == tail && !eating` is legal), uniform food placement among free cells, `CLEARED` when the board fills. Speed curve: base 230/165/115 ms times 0.93 per level (5 foods per level), floor 65 ms (`SnakeEngine.kt:259-262`).
- Loop discipline is the best of the 8: one `LaunchedEffect` keyed on lifecycle/live/paused, `repeatOnLifecycle(RESUMED)`, pause on window focus loss, ON_PAUSE, orientation change, corner menu open (`SnakeScreen.kt:176-213`). Resume is the player's call with a scrim.
- Input: swipe with multiple turns per continuous drag (22dp threshold, accumulator reset), 56dp D-pad, arrow keys/WASD, and 4 `CustomAccessibilityAction`s on the board (`:507-510`). Head, body and food differ by shape, colour-blind mode adds dots/cross. Strings 33 `stringResource`, ES parity 53/53.

### Findings below 3
- **Feel 2**
  - The snake teleports one whole cell per tick with no interpolation (`drawSnakeBoard` draws state at tick boundaries, `SnakeScreen.kt:~585-640`; state only changes in `game.step()`), which at 230 ms/cell on a 12x12 board reads as stepping rather than sliding. Common for Snake but not "console polish".
  - Tick is `delay(game.tickMillis())` then `step()` (`:195-212`), so every tick is `tickMillis + compose/step time`; the speed drifts slower than nominal and jitters with GC. Use a fixed-step accumulator on `withFrameNanos` (or `delay` until an absolute deadline).
  - No death animation beyond a cross-eyed head and a fade, no screen shake. LocalGameSpeed not applied (documented as deliberate, `SnakeScreen.kt:112-114`); a "Relaxed" 230 ms is the only slow option.
- **Robustness 2**
  - **Not resumable**: `SnakeGame` is not a `ResumableGame` (`SnakeGame.kt:52`) and the Hub spec says `resumable = false` (`GameHubSpecs.kt:535`). Process death mid-run loses the run. Acceptable for an arcade run, but it is the only game of the 8 puzzle/casual set that silently loses state.
  - Best score is written inside `LaunchedEffect(phase, s.runSeq)` (`SnakeScreen.kt:231-241`), and `reportedNewBest` is only set after the suspend returns, so a cancel mid-write retries only if the effect re-keys (S).
  - `SnakeEngine.snake` / `pendingTurns` copy lists on every access and `publish()` builds a new `SnakeState` per tick (`SnakeGame.kt`), plus `body.contains(next)` is O(n) per step. Fine on 20x20 (400 cells); no problem in practice (S).
- **A11y_I18N 2**: There is a D-pad and TalkBack actions, but a real-time game still has no screen-reader mode: no `isTouchExplorationEnabled` branch (audit 04 H2), and the slowest tick is 230 ms at level 1, shrinking to 65 ms. Add a "Turn-based" option (move only when a direction is chosen) like Toy Sports' assist (`ToySportsHubSpecs.kt`). Two prose literals remain (V by count).
- **Tests 3**: strong engine tests; no seeded random-play property test (random steering never throws, `length == 3 + foods` while RUNNING, no duplicate cells) (S, may exist inside the 425 lines but no "fuzz" or "property" name found).

### Top 5 fixes
1. **(M) Fixed-timestep loop with absolute deadlines** (`withFrameNanos`, accumulator, max 3 steps per frame) plus render interpolation between the previous and current head position for smooth movement (draw only; collision stays on the grid).
2. **(M) Turn-based/accessible mode**: Hub option "Step mode" (snake advances one cell per tap on any D-pad button or per swipe) so TalkBack and switch users can finish a run; read "ate food, length N" through a polite live region.
3. **(M) Make Snake resumable**: serialise cells, direction, queue, food, score, config and RNG seed into a snapshot validated like the others; set `resumable = true` in the Hub. Resume into the paused scrim.
4. **(S) Death and eat juice**: 120 ms head-bump shake (Reduced Motion off), tail flash on eat, camera nudge in the direction of travel.
5. **(S) Property test**: 500 seeded runs with random steering on each grid/wall combination; assert invariants each step and termination.

---

## 7. Flow Lines (`flow-lines`) 14/15

Files: `games/flowlines/FlowLinesEngine.kt`, `FlowLinesGame.kt`, `FlowLinesStatsStore.kt`, `ui/FlowLinesScreen.kt`, tests `FlowLinesEngineTest.kt` (355), `FlowLinesGameTest.kt` (385) = 53 tests.

### Strengths (V)
- Level validity: the generator builds a random Hamiltonian path (backbite moves over a boustrophedon snake) and cuts it into `flows` pieces of at least 3 cells, so every board is solvable with 100 percent coverage by construction (`FlowGenerator.generate`, `FlowLinesEngine.kt:306-370`). `FlowPuzzle.isValid()` re-validates adjacency, unique dots, full coverage. There is also a DFS `FlowSolver` used by tests to cross-check 5x5 to 7x7 boards (`FlowLinesEngineTest.kt:101,141`).
- Snapshot embeds the puzzle itself and re-validates both the puzzle and the player paths (`FlowLinesGame.kt:117-130`), so a corrupt or foreign save is rejected. Daily seed is `dailySeed(epochDay, tier)`, deterministic across devices (tested at `FlowLinesEngineTest.kt:89`).
- Input: drag draws with `stepToward` (cells are filled one at a time, cut and retrace rules), **plus a non-drag path** `tapCell` for accessibility services (`FlowLinesGame.kt:250`), per-cell semantics with colour names, colour-blind palette plus shape per colour, polite status line. Strings 34/ES parity 50/50.

### Findings below 3
- **Feel 2** (S on the unread parts): haptics on connect and on win, a 400 ms tween on the win fade (`FlowLinesScreen.kt:637`), result hold off under Reduced Motion. I did not find a path-extend animation, a "pipe flowing" fill, or a per-colour connect chime (only haptic `SUCCESS` at `:243-247`), so the connect moment is silent. No `LocalGameSpeed` read.
- **Gameplay note (not scored down)**: puzzles are not guaranteed to have a *unique* solution and the generator may produce long lazy "snake" paths for the early colours; `isSolved` accepts any full cover, so this only affects perceived puzzle quality, not correctness.
- **Robustness 3**: no blocking issue. Suspected: every stroke pushes a JSON snapshot that contains the whole puzzle (solution included), so a 9x9 session with 200 strokes holds 200 copies of the same puzzle in `SnapshotHistory` (`FlowLinesGame.kt:96-110,213-250`) (S, only matters on long sessions). Generation (5x5 to 9x9, `n*40` backbite moves) runs on the main thread in `startMatch`; it is a few ms, no problem (V by arithmetic).
- **Tests 3**: good. Missing: a property test that random drags never produce an invalid `paths` per `validatePaths`.

### Top 5 fixes
1. **(S) Connect sound and path-fill animation**: a rising chime per connected colour (pitch by connected count), 120 ms stroke "flow" along the finished path; both obey Reduced Motion.
2. **(S) Strip the puzzle from per-stroke history snapshots** (store only paths + moves; keep the puzzle in the game) to cut memory on long sessions.
3. **(M) Uniqueness filter or difficulty gate**: use `FlowSolver(maxSolutions = 2, maxNodes = bound)` on a background dispatcher; re-roll generation until unique for the Daily. Measure solve time to cap difficulty.
4. **(S) Fuzz test** for random drag sequences and `tapCell` chains against `validatePaths`.
5. **(S) Daily completion marker** (see shared findings) so "Today's board" shows a tick once solved.

---

## 8. Six-Ball Puzzle (`six-ball-puzzle`) 13/15

Files: `games/sixball/SixBallRules.kt`, `SixBallLevels.kt`, `SixBallGame.kt`, `SixBallProgressStore.kt`, `SixBallPigHubSpecs.kt`, `SixBallNetMessage.kt`, `ui/SixBallScreen.kt`, tests (6 files, 58 tests: rules, levels, game, networked, hub specs, progress).

### Strengths (V)
- BFS solver gives a **true shortest par** (rules KDoc, `SixBallRules.explore`), the generator finds a start state, explores to depth `par`, and picks a target at exactly `par` where at least 4 balls moved (`SixBallLevels.kt` `generate`), so the star thresholds are honest. 48 campaign levels with deterministic seeds, a weekday-scaled Daily, structural validation (`isWellFormed`), progress pruning (60 dailies).
- Hint is the first move of the exact shortest solution from the current position, computed on `Dispatchers.Default` (`SixBallGame.kt:304-318`), with a dead-end warning; level lookup also on Default (`SixBallScreen.kt:211,526`). Undo is unlimited because the position is the replay of the move list.
- Input: swipe on release (22dp), a 4-button D-pad with 56dp height and "Suggested" semantic (`:536-552`), ball identity is symbol + colour. 61 `stringResource`, ES parity 132/132, LocalGameSpeed honoured, ball roll and land animations use `Animatable` state.

### Findings below 3
- **Robustness 2**
  - **Idle animation read in composition** (V): `rememberInfiniteTransition(...).animateFloat(...).value` at `SixBallScreen.kt:746-749` is read in the board composable body (the Canvas captures `pulse`), so with Reduced Motion off the board recomposes every frame (~60/s) for as long as the screen is open, solved or not. This is the audit 02 "idle animation cost" pattern (Slide and Climb F1). Fix: read the transition state in the Canvas draw lambda (pass `State<Float>`), and stop it when there is no hint.
  - `store.recordSolve(...)` is wrapped in `try { ... } catch (_: Exception) { false }` inside `LaunchedEffect(board?.serial, board?.solved)` (`:262-264`), which swallows `CancellationException` and sets `recordedSerial` before the write (V), so a cancelled effect loses the best/stars (S for frequency).
  - `pause()`/`resume()` are no-ops, which is correct for a untimed game (V).
- **A11y_I18N 2**: the tray is one semantics node with one description string (`:757`), not per ball or per cell; there is no `stateDescription`, no `collectionInfo`, and no live announcement of the result of a tilt (S, I did not read the description builder). D-pad buttons are the right non-swipe path. Colour-blind mode raises glyph opacity only (V `:751`), which is adequate because every ball has a symbol.
- **Tests 3**: strong rules/levels/networked coverage; missing a generator property test across many seeds (every campaign level solvable at exactly `par`, `isWellFormed`), unless `SixBallLevelsTest` (84 lines) already does it (S).

### Top 5 fixes
1. **(S) Draw-phase pulse**: move the infinite transition read into the `Canvas` lambda, and skip creating it when `hint == null`.
2. **(S) Rethrow `CancellationException` and write progress from `SixBallGame`/an app scope** (do not set `recordedSerial` until after a successful write).
3. **(M) Tilt announcement and per-ball semantics**: after each tilt, a polite live region "Tilted left, 2 balls home, 7 moves, par 6", and `collectionInfo` + per-ball `stateDescription` so TalkBack can inspect the tray.
4. **(S) Level generator property test**: all 48 campaign levels + 30 daily epochs: `isWellFormed`, BFS distance from start to target equals `par`, unique balls at start.
5. **(S) Cache warm-up**: pre-generate the next campaign level on `Dispatchers.Default` while the player solves (`SixBallLevels.cache` is already concurrent).

---

## Shared-component findings (affect more than one of the 8)

1. **EN-only in-game text in 4 of 8 games** (V): Minesweeper, Lights Out, Sliding Puzzle and Color Flood have 0 `stringResource` and 8 to 40 prose literals each; 2048, Snake, Flow Lines and Six-Ball are fully localised with exact EN/ES parity. Same defect as audit 04 H1. One agent batch can fix all four with a shared `puzzle_common_strings.xml` (Undo, Hint, New board, Back to Menu, Easy/Medium/Hard, "Time: %s", "Moves: %d", "New best!", "Assisted boards do not set records") because the four screens duplicate the same result-panel, tier-chip and stat-line code (`LightsOutScreen.kt:896-937`, `ColorFloodScreen.kt:1205-1245`, `MinesweeperScreen.kt:1383-1417`).
2. **Duplicated "puzzle screen" scaffolding**: tier chips (`LightsOutScreen.kt:471`, `ColorFloodScreen.kt:569`, `MinesweeperScreen.kt:636`), result panel, clock effect (`MinesweeperScreen.kt:755-775`, `LightsOutScreen.kt:646`, `ColorFloodScreen.kt:672`), `fitBoard` slot with the same 28dp minimum (`MinesweeperScreen.kt:93`, `LightsOutScreen.kt:77`, `ColorFloodScreen.kt` `MIN_CELL_DP`) are copied four times. Extract `PuzzleTierChips`, `PuzzleResultPanel`, `PuzzleClock` into `uikit/` once. The same extraction is where i18n and a 40dp minimum belong.
3. **Personal-best write inside a cancellable `LaunchedEffect`** (V pattern in 7 of 8: `MinesweeperScreen.kt:269-277`, `LightsOutScreen.kt:215-228`, `SlidingPuzzleScreen.kt:272-295`, `ColorFloodScreen.kt:255-272`, `SnakeScreen.kt:231-241`, `FlowLinesScreen.kt:249-258`, `SixBallScreen.kt:257-266`); only 2048 re-writes on every score change. The marker (`endHandledBoard`/`recordedSerial`/`reportedNewBest`) is set before the suspend write, so a cancelled effect never retries. Add one helper `recordOnce(scope = appScope, key) { store.recordX() }` or let each `*Game` own a callback fired from the engine. Also the eight `*StatsStore` classes are nearly identical (same `decode` with `runCatching`, same `edit` shape): one generic `BestStore<K, V>` would remove the copies.
4. **Daily mode weaknesses** (V): (a) `dailySeed = java.time.LocalDate.now().toEpochDay()` is evaluated at composition time in `MainActivity.kt:784-1395` (local timezone, fixed for the life of the route), so two players in different timezones, or one player over midnight, get different "today" boards although the Hub says "The same puzzle for everyone today" (`GameHubSpecs.kt:138-139`). Use UTC (or one app-wide `DailyClock`) and re-key on date change. (b) No daily completion/streak marker exists anywhere (grep for streak/daily stores found none in the games); a solved Daily is recorded into the same best-record as free play. (c) Minesweeper's first-click dependence (see section 1).
5. **No generator/engine fuzz or property tests in any of the 8 games** (V: no "fuzz"/"property" test names found under their test packages). QUALITY-BAR gate 1 asks for "random legal play never throws, snapshot/restore round-trips, undo restores exact state". Add one `PuzzleInvariantTest` per game; the Lights Out, Sliding and Flow generators make this especially cheap.
6. **No screen-reader or one-switch path for the real-time and gesture games** (Snake, partly Minesweeper pan, 2048 swipe, Six-Ball swipe): D-pad and custom actions exist for Snake, 2048 and Six-Ball, but none reads `isTouchExplorationEnabled` or offers a turn-based mode (audit 04 H2). Snake is the only truly real-time one in this set.
7. **`LocalGameSpeed` is ignored by 6 of 8** (only 2048 and Six-Ball read it). For puzzle games with fixed-duration feedback (glow fade, shake, flood fade, result hold) it should scale those. Snake documents its choice.
8. **Cell minimum 28dp on dense boards** (V: `MinesweeperScreen.kt:93`, `LightsOutScreen.kt:77`): below the 48dp gate-5 target; all three relied on pan, which is a worse trade on a phone than a zoom. Decide one rule for all puzzle boards (40dp floor plus pinch zoom) in `fitBoard`'s caller.
9. **Always-on idle recomposition**: only Six-Ball has it among these (`SixBallScreen.kt:746-749`); the rest read animation state in draw/layout lambdas (Lights Out glow, Sliding offset lambda, Color Flood canvas), except 2048's per-move slide/pop (`G2048Screen.kt:783-809`, transient). Good baseline, keep it enforced with a lint or review rule.
10. **Hint semantics are consistent and honest across the set** (V): every game marks a hinted board "assisted" and refuses a record afterwards. Keep this as the shared contract and add it to the `UndoableGame`/hint interface docs.

## Verdict

None of the eight has a rules-breaking defect: the generators are solvable by construction in Lights Out, Sliding Puzzle, Flow Lines and Six-Ball (with BFS-exact par), and 2048 and Snake have correct merge, collision and game-over logic. The weakest parts are product gaps, not bugs: Color Flood has no losing condition, Minesweeper's Daily layout depends on the first click and it has no chord, Sliding Puzzle lacks multi-tile slides and illegal-tap feedback, four games are English-only, and Snake is not resumable and not usable with a screen reader. Six-Ball's idle pulse is the one performance defect.
