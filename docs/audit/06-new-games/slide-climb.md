# Audit: Slide and Climb (`chutes-race`)

Read-only audit by reading source. Nothing was compiled or run (no Gradle/adb). Every finding is
"verified by reading" (V) unless marked "suspected" (S).
Files: `games/slideclimb/{SlideClimbRules,SlideClimbGame,SlideClimbLayout,SlideClimbHubSpecs}.kt`,
`ui/SlideClimbScreen.kt`, `ui/SlideClimbArt.kt`, `res/values{,-es}/slide_climb_*.xml`, tests under
`app/src/test/.../slideclimb`.

## Summary

The engine is in good shape: pure rules, one `nextInt(1,7)` per roll, a roll log, strict snapshot
validation (it re-derives the last move from the roll), and a thorough test suite. I found no rule
bug in step/bounce/exact-finish/win/turn-order/handicap logic. The real defects are in the screen
layer: a per-frame recomposition storm that also defeats the cached board layer, the result being
announced and the "Play again" panel shown before the animation finishes, a one-frame double-tap
hole, some accessibility semantics, and a Hub option that silently does nothing. No crash risks
found (all indexes are guarded by roster-size validation).

---

## Findings

### F1 (MED, V) Whole screen recomposes every frame; the cached static board layer is rebuilt each time
- `SlideClimbScreen.kt:269-272` reads `rememberInfiniteTransition(...).animateFloat(...).value`
  directly in the screen body (not in a draw lambda). While idle with Reduced Motion off, the whole
  composable re-executes ~60 times a second. Same at `:455` (`1f - tumble.value` read in
  composition during the tumble).
- Each pass re-runs `boardDescription` (`:355-369`: sorts and formats 16 jumps plus pieces via
  `getString`), rebuilds chip descriptions (`:405-409`), calls `slideClimbTheme(dark)` (`:124`, a
  new `SlideClimbTheme` each time, not remembered), the FlowRow and all lambdas.
- Worse: `SlideClimbBoardView` (`SlideClimbArt.kt:178`) uses `drawWithCache` capturing `theme`
  (new identity each pass), so the lambda changes every recomposition and the cache is invalidated:
  100 text measures, 100 rects, 8 ropes and 8 slides are redone every frame. The "cached static
  layer" comment is not true in practice. (Exact compiler memoisation of the lambda: S; a new
  `theme` instance per pass guarantees the capture changes.)
- Trigger: any idle game, Reduced Motion off. Battery and jank on mid-range devices.
- Fix: `val theme = remember(dark) { slideClimbTheme(dark) }`; pass `pulse` and `spin` as
  `() -> Float` or `State<Float>` and read them inside draw/graphicsLayer lambdas; wrap
  `boardDescription` in `remember(s.positions, s.layout)`; make the `drawWithCache` lambda stable.

### F2 (MED, V) Result is announced and the win / Play-again panel shown before the animation ends
- `SlideClimbScreen.kt:301` says "while a move is still animating, the previous result stays", but
  `ann` (`:302-315`) is computed from `s` (already final when `applyMove` ran), with no `busy` gate.
  TalkBack (polite live region in `ClStatusLine`) speaks "X rolled 5 and climbed to 44" while the
  piece is still hopping; the next human hears "tap the dice" while the dice are locked and taps are
  silently ignored (`:340,345,350` return with no feedback).
- `:434-447`: `if (s.over)` shows `ClFinishedPanel` at once, mid-hop, with live Play again / Leave
  buttons while `busy` is true. `onPlayAgain = { game.playAgain() }` is not gated on `settled`.
  Tapping it mid-animation cancels the effect; the old effect's `finally` (`:235-239`) writes
  `shownPos = cur.positions` from the stale `cur`, racing the new effect's reset branch
  (`:160-168`) (S on ordering; likely benign but fragile).
- The winner celebration (`:284`) waits for `!busy` but the panel does not.
- Fix: keep a `shownState` (the state last fully animated) and derive `ann`, `turn`, the finished
  panel and chip descriptions from it; or gate on `settled`. Disable Play again / Leave while busy.

### F3 (MED-LOW, V) Double-tap race: `busy` is set one composition late
- `settled = !busy` (`:277`); `busy = true` is set inside the `LaunchedEffect` (`:172`), which runs
  after the recomposition that follows the state change. For one frame `canRoll` is true for the
  NEXT seat. Pass and Play with two humans (no handicap): two touches in the same input batch (two
  fingers, TalkBack double activation) roll for the next human as well, skipping their turn.
  `tapRoll` (`:339-343`) re-checks `game.explainRoll()` which says NONE for the next human.
- Fix: gate on state, not the effect. In `tapRoll/tapKeep/tapSpare` return when
  `state.rollSerial != lastRollSerial || state.moveSerial != lastMoveSerial` (an animation is owed),
  or set `busy = true` synchronously right before calling the game.

### F4 (MED, V) Hub handicap choice for a seat that does not exist is silently ignored
- `SlideClimbHubSpecs.kt:44-49` always offers "Player 1..4 only". `parseSlideClimbHandicap`
  (`SlideClimbGame.kt:475`) filters out-of-range seats, so "Player 4 only" in a 2-seat game gives an
  empty set: nobody gets a spare roll, the CPU level stays visible (`visibleWhen` is true for "4"),
  and nothing says the choice was ignored. Same for "Player 3 only" with 1 human + 1 CPU.
- Fix: hide or disable choices beyond the seat count (visibleWhen on the choice or option), and/or
  clamp. Add a Hub test for 2 seats + "4".

### F5 (LOW-MED, V) Status live region re-reads the old result on every change
- `statusText = ann + " " + turn` (`:323`) is one polite live region. `turn` flips between "X, tap
  the dice" and "X is rolling..." (`botThinking`, `:243-253`) on every bot step, so TalkBack re-speaks
  the whole previous result each time. With 3 bots this is a stream of repeats. "is rolling..." is
  also shown while the bot is deciding about a spare (`cur.pending`).
- Fix: separate `ann` (live) from `turn` (not live), or only make the node live when `ann` changes;
  use "is thinking..." while pending.

### F6 (LOW-MED, V) Dice button advertises "Roll the dice" when it cannot be rolled
- `SlideClimbDice` (`:529-550`) always has `clickable(onClickLabel = "Roll the dice", role = Button)`.
  When `canRoll` is false (bot turn, animating, decision pending) TalkBack says "double tap to Roll
  the dice" while the description reads "The dice shows 4". No `disabled()` or `stateDescription`.
- Fix: `semantics { if (!active) disabled() }` (keep the click so the refusal sound still plays), or
  pass a null `onClickLabel` when inactive.

### F7 (LOW, V) Round buttons double-speak
- `SlideClimbRoundButton` (`:554-563`) sets both `onClickLabel = description` and
  `contentDescription = description`: "Keep the roll, button, double tap to Keep the roll". Drop the
  `onClickLabel`.

### F8 (LOW, V) Chip and board descriptions use final positions, not shown ones
- `:407` uses `s.positions[i]` while the chip text uses `shownPos` (`:399`); board description
  (`:359-360`) also final. Mid-hop, touch exploration hears squares ahead of the drawing. Same fix as F2.

### F9 (LOW, V) Un-rolled die shows a "5"
- `:546` renders face 5 when `value == 0` (before the first roll), at 80% alpha. Render a blank face.

### F10 (LOW, V) A bounce that ends on a rope or slide loses the bounce in the words
- `SlideClimbEvent.of` (`SlideClimbGame.kt:33-40`) checks jump before bounce, so 99 + 5 -> 96 ->
  slide to 73 is announced "slid down to 73" (the hop animation does bounce). Intended per the rules
  test `aBounceCanLandOnASlide`, but the words hide why the piece passed the end. Carry a `bounced`
  flag in state and prefix "went past the end".

### F11 (LOW, V) Easy computer can throw away a winning roll
- `SlideClimbRules.kt:82`: EASY is `coin.nextBoolean()` even when `step.won`. Maybe friendly for
  kids; if not intended use `!step.won && coin.nextBoolean()`. Test
  `neitherMediumNorHardThrowsAwayAWin` does not cover Easy.

### F12 (LOW, V) Snapshot validation gaps (cannot crash; can desync or mis-announce)
`decodeSnapshot` (`SlideClimbGame.kt:382-433`) is otherwise strong. Gaps:
- The turn pointer is not tied to the last event: after a MOVES event `currentPlayerIndex` must be
  `(eventSeat + 1) % n` (or `eventSeat` when won). A crafted or old snapshot can give the turn to
  the wrong seat (`:398`, `:415-425`).
- For `ROLL` events `eventFrom/Landed/To` are not required to equal the seat's position
  (`:410-414`); display-only impact (S).
- `rollSerial`/`moveSerial` are only checked `>= 0` (`:404`). With `Int.MAX_VALUE`, `+ 1`
  (`:291`, `:315`) overflows negative; the screen treats that as a reset (`SlideClimbScreen.kt:160`)
  and skips animations for the rest of that board. Bound both.
- `sessionWins` values unbounded (`+ 1` at `:308` can overflow); `displayName` is taken from the
  snapshot rather than the roster (`:449`), so renamed seats keep stale or oversized names. Use
  `roster[i].displayName`.
- No schema version field. A snapshot from a future/older shape that fails validation is silently
  deleted (`GameSessionManager.tryResume`). Acceptable now; add `version` before the next change.

### F13 (LOW, S) `pause()`/`resume()` are empty; the bot keeps timing behind dialogs / in background
- `SlideClimbGame.kt:212-213`. The bot effect (`:243-253`) uses `delay`, which keeps counting with
  the Leave/Help dialog open or the app backgrounded, so a bot can move (even end the game) behind a
  dialog. Gate the bot effect on a paused flag or lifecycle RESUMED.

### F14 (LOW, S) `LaunchedEffect(context)` re-runs `init + startMatch` if the context is re-emitted
- `SlideClimbScreen.kt:130-143`. Config changes are handled (`configChanges` in the manifest) so
  rotation/fold is safe, but an equal-but-new `GameContext` emitted mid-game would reset
  `sessionWins` and replace the board (the resume token is already consumed). Same pattern as other
  games; low risk.

### F15 (LOW, V) Smaller items
- `layoutSeed` is never set by the screen or Hub (`SlideClimbGame.kt:140`), so `generate(seed)` is
  test-only in the product. Fine, note for reviewers.
- `rememberAmbientMusic(profile = MusicProfiles.REVERSI)` (`:120`) reuses Reversi's profile.
- Hop squares use `slideClimbCellCenter` (`:209`) while resting uses `slideClimbPiecePos` with crowd
  offset, so a piece nudges when landing on a shared square.
- No tap-to-skip for animations; at Slow (2.4x) a 7-square bounce is about 2.9s plus a 1.5s slide with
  every tap ignored.
- Round-button colours (`Color(0xFF1B6B36)` etc.) bypass the theme; contrast looks fine.
- `displayName = "Slide and Climb"` hard-coded (same as other games); `"⚙"` bot glyph sits inside a
  merged node with an explicit description, so it is not read twice.

---

## Checked and found correct
- Rules: raw<=100 walk, bounce `200 - raw`, exact-finish stuck, jump taken once, win on `to == 100`
  (jumps can neither start at nor end on 100), bounce onto a slide foot (96 -> 73, tested); `hopPath`
  matches `step` for bounce and stuck.
- Turn order: a win keeps the turn on the winner and `over` blocks roll/keep/spare;
  `nextStartingPlayerIndex` rotates once per `startMatch`; `firstOpenerIndex` is validated.
- Handicap: one spare max, the reroll must be kept, `rerolls` reset per board, bots never stall
  (`playBotStep` always changes state when `botToMove`); restore rejects a pending seat with 0 rerolls.
- Determinism: only `random.nextInt(1,7)`; bot coin derived from state; roll log saved and capped.
- Layout: generation is bounded (400 x 2000); `problems()` range-checks before any `row()` use and
  catches `abs` overflow as a bad span.
- Resources: every `R.string` used by the screen exists; EN and ES key sets match for the strings,
  hub strings and coach strings; no plurals needed (the `%d` is a square number); registered in
  `MainActivity`, `GameCatalog`, `GameHubSpecs`, `GameCoach`, `GameTips`.
- Compose: fixed-size seat lists (no keyed-list problem), hop leg read inside the draw scope (`:224`),
  Animatables remembered, ticker job cancelled, effect `finally` resets state.
- Reduced Motion removes tumble, hops, confetti and pulse; game speed scales every delay with a floor.
- Touch targets: die 128dp, round buttons 72dp, chips `heightIn(min = 56.dp)`.

## Test gaps
1. No UI/screen tests (animation state machine, `busy`; F2/F3 are untestable as written). Extract the
   effect logic into a pure reducer or add a Compose test with a test clock.
2. No test that the snapshot turn pointer follows the last event (F12).
3. No test for `Int.MAX_VALUE` serials/wins or oversized `displayName`.
4. No test for a bounce that lands on a ROPE (event CLIMB, bounced true) or `hopPath` for exact-finish
   `stuck` returning empty.
5. No Hub test for a handicap choice naming a non-existent seat (F4).
6. EASY bot on a winning roll untested (F11).
7. Nothing asserts that the next board after a restore keeps handicap (relies on `launchOptions.extras`
   being persisted, which `GameSessionManager` does around line 156).

## Ranked top list
1. F1 recomposition storm plus defeated board cache.
2. F2 result announced and Play-again panel shown before the animation ends.
3. F4 Hub handicap choice for a missing seat silently ignored.
4. F3 one-frame double-tap hole in pass and play.
5. F5 live region re-reads old results every bot step.
6. F6/F7 dice and round-button semantics.
7. F12 snapshot gaps (turn pointer, serial overflow, stale names).
8. F13 empty pause/resume.
9. F8-F11 polish.

## Verdict
The rules engine and persistence are solid and well tested; I could not find an input that breaks
win detection, bounce, exact finish, handicap rerolls or turn order, or that crashes restore. Not
ship-blocking. The screen layer, though, was written without a pass for performance and
accessibility timing: the infinite pulse read in composition makes the whole screen (and the
supposedly cached board layer) redo its work every frame, results are spoken and the winner panel
shown before the pieces finish moving, and a one-frame window lets two quick taps skip a human turn.
Fix F1 to F4 (mostly moving state reads into draw scopes and adding a `shownState`), tighten the
snapshot turn-pointer check, and this is a clean kids' game.
