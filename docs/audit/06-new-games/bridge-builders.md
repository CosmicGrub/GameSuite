# Audit: Bridge Builders (`bridge-builders`)

Read-only audit, 2026-11-07. Nothing was built or run; every finding below comes from reading the code. "Verified" means the
code path was traced end to end and the behaviour follows from it. "Suspected" means it depends on something I could not
exercise or on code outside this game.

Files read: `games/bridgebuilders/{BridgeRules,BridgeEngine,BridgeBuildersGame,BridgeBuildersHubSpecs}.kt`,
`ui/BridgeBuildersScreen.kt`, the shared `TapTugPointerTracker`, `ContactSizes` and `TapTugBoard` it reuses, `GameChrome`,
`GameSessionManager` pause, resume and tryResume, `MainActivity` onPause and onResume, `res/values{,-es}/bridge_builders_*.xml`,
and the five test files under `app/src/test/.../bridgebuilders`.

## Verdict

The engine is solid. It is a deterministic fixed-step design with an injected clock and Random, the cooperation rule cannot
deadlock, a save is validated and never repaired, and the pure rules are well tested. I found no crash in the normal paths and
no scoring or tally bug.

The real problems sit at the screen and shell boundary, not in the engine. The water, the cart and the robots keep running
while a help, coach or leave dialog is open. Drowning during the walk-across is surprising and untested. The game cannot be
played by a TalkBack user, although it advertises double-tap support. The water drawing is allocation-heavy. A few guard
rails are missing for out-of-contract rosters. Ship after fixing F1, F2 and F3.

## Question asked: does the water fail to resume after a restore?

No. It is not a product defect in the engine.

- `BridgeEngine.restore` (BridgeEngine.kt:487-506) sets `water = save.water` and then calls `beginTry()`. `beginTry` does not
  touch `water`; only `start()` zeroes it (line 218). So the saved level is kept.
- The restored try begins in COUNTDOWN. `step()` only calls `riseWater` in BUILD and CROSS (lines 296-313). So the water is
  frozen for the 3 s countdown and rises again from the saved level on GO. That is the same as a fresh start, and it is
  documented in `integration-notes.md:96`.
- `halfAnnounced` and `highAnnounced` are recomputed from the restored level (lines 503-504), so the milestones do not
  re-fire.
- `toSave` clamps to 0.999 and `isValid` requires `water < 1.0`, so a snapshot of a nearly drowned bridge restores
  correctly.

If the snapshot water test in `BridgeBuildersGameTest` (lines 147-153) fails, suspect the test, not the product. After
`COUNTDOWN_MS + 2000` ms the water should be about `before + 0.0145` (relaxed Little band). The assertion is
`resumed.water > before.water * 0.5f`. That can only fail if the water was drained to about 0 before the snapshot or after
the restore. A HARD robot lane placing planks (ebb 0.02 x q per plank, floored at 0 by line 376) can do that, and so can
the early snapshot. The test comment already admits the robot ebb. Assert `>= 0` growth since GO, or run the restored game
with 0 robots, or compare against the water at the moment the phase turned to BUILD.

Related and minor (F11 below): a restored try replays a fresh 3 s countdown and resets `elapsedMs` and `misses`.

## Findings

### F1. Help, coach and leave dialogs do not pause the game (medium to high, verified)

- Where: `BridgeBuildersScreen.kt:167` (`val (_, openCoach) = rememberClCoach(...)` discards the "shown" flag) and `:159-166`
  (`GameChrome` has no pause hook). Nothing in this screen observes window focus. `Breakout` does this at
  `BreakoutScreen.kt:191` with `LaunchedEffect(windowFocused)`.
- Trigger: first run. `rememberClCoach` auto-shows the CoachCard on the first launch (ChineseLudoUi.kt:190-205) while the
  countdown runs. The water then rises, the cart slides and the helper robots keep building behind the card. Same for the
  "?" help dialog, the "Leave?" confirm, the notification shade and split-screen focus loss. Activity `onPause` is forwarded
  (MainActivity.kt:277), but a dialog or shade does not pause the Activity.
- Impact: a 5-year-old reading the coach card, or a parent opening help, loses a try. The Little band has 110 s of water and
  the Big band 70 s, so a long read matters.
- Fix: mirror Breakout.
  - `val focused = LocalWindowInfo.current.isWindowFocused`.
  - `LaunchedEffect(focused, coachShown) { if (!focused || coachShown) game.pause() else game.resume() }`.
  - Keep the coach flag (`val (coachShown, openCoach) = ...`).
  - Add a visible "Paused" scrim if you want symmetry with Breakout.

### F2. Water also drowns the bridge during the crossing (medium, verified; design decision plus test gap)

- Where: `BridgeEngine.crossStep` (lines 309-313) calls `riseWater` and can `finish(false)`. The `CROSS` event says "The bridge is
  finished! Everybody crosses." (status string) and is followed by "Try again together".
- Trigger: finish the last plank with water at roughly 0.94 or more. `crossMs` is 3.6 s to 6.75 s (Little 4500 x 0.8..1.5,
  Medium 4000, Big 3500) and the Medium water rate is 1/85 per second. Finishing the last plank at water 0.96 is a certain
  lose even though every plank is in.
- Impact: it feels unfair for the audience (age 5+), and the rule is not in the help text, the hub blurbs or the tips. The
  spec you were given says "everyone across before water", so this is probably intended, but it is not signalled.
- Test gap: no test makes the water cover the bridge during CROSS (`BridgeEngineTest` 208-236 only covers BUILD-phase
  drowning and a winning cross).
- Fix: either (a) stop the water during CROSS (one-line change: skip `riseWater` in `crossStep`) or (b) keep it and show
  the water line during the walk plus a line in help ("the water keeps rising while you cross"). Add a test either way.

### F3. TalkBack and switch users cannot play (medium, verified for the gap, suspected for severity)

- Where: `BridgeBuildersScreen.kt:179-189` (zone a11y) and `:188` (`onActivate = { game.tap(seat) }`).
- Trigger: the only skill is timing the tap to a visual cart position. TalkBack announces "Double-tap to place a plank when
  the cart is in the green spot" (`bridge_builders_zone_desc`), but there is no audio or haptic cue for where the cart is.
- Also: the double-tap path (`onActivate`) skips the SFX and haptics that `onPress` plays (lines 190-198), and the result
  (placed, missed, waiting) is not announced because the zone description is not a live region.
- Fix: when TalkBack is on, default Relaxed (every tap places a plank), or add a repeating tone whose pitch tracks the
  distance to the spot, or both. Return the `BridgeTapResult` from `onActivate` and announce it (for example through the
  status line or `announceForAccessibility`). Mention Relaxed in the zone description for a screen-reader user.

### F4. Per-frame native Path allocations in the river drawing (medium, verified)

- Where: `drawRiver` (BridgeBuildersScreen.kt:277-376), called from the Canvas at line 270.
- Trigger: every state change, about 40 per second (`delay(25)` at line 131 plus a state that changes each tick). Each draw
  creates the water polygon (about `w / (6 x density)` `lineTo` calls), about eight wavy-line Paths (about
  `w / (8 x density)` points each), a rope Path per neighbouring lane pair, and the knot diamonds. `Path()` is a native
  Skia path on Android. That is about 400 or more native allocations per second plus boxed `Offset`s and local closures
  (`yOf`, `frontier`).
- Reduced Motion: `amp = 0` makes the water a flat line, which is correct, but the code still builds the full polygon and
  every flat wavy line with hundreds of `lineTo`s. When reduced, a `drawRect` for the water and a `drawLine` per pattern
  line would do.
- Fix: keep remembered `Path`s and `rewind()` them; or only rebuild the wave when `(elapsedMs / 50)` changes; or when
  `reduced`, draw one rect plus a few lines. `drawLane` also allocates two to three Paths and a `listOf` per zone per frame
  (BridgeBuildersScreen.kt:459-466); a smaller win.

### F5. A static hub choice offers lanes that do not exist (low to medium, verified)

- Where: `BridgeBuildersHubSpecs.kt:38-48`. The handicap choices are a fixed list of "Lane 1".."Lane 4" in both modes.
  `BridgeHandicap.parse` returns all zeros for `p3` or `p4` when there are 2 or 3 seats (BridgeRules.kt:212), so the
  choice silently does nothing.
- Also: nothing on the screen says which lane is "Lane 3"; lanes are identified only by colour and glyph. The only
  on-screen sign of a widened lane is a 3 dp dot (BridgeBuildersScreen.kt:467) with no text or semantics.
- Fix: label the handicap choices by seat colour or name, or filter the choices by total seat count; show "(wider)" in
  the zone header, and put it in the zone a11y description.

### F6. Rosters outside 2 to 4 seats are not guarded (low, suspected)

- `BridgeEngine.setup` coerces `n` to 2..4 (line 195) but indexes `seats[it]` for `it < n` (line 236, and `state()` line 451).
  A roster of 1 gives n = 2 and `seats[1]` throws `IndexOutOfBoundsException`. A roster of 5 or 6 (the "same screen" mode
  allows humans 2-4 plus robots 0-2, so a hub that does not clamp the total could give 6) silently drops seats 5 and 6.
  `isValid` then rejects every snapshot because `seats.size !in 2..4`, so resume silently never works. `GameHubSpec` has
  `maxSeats = 4`, so the hub probably clamps; I could not confirm.
- Fix: in `setup`, if `roster.size !in 2..4` take `roster.take(4)` and pad or reject; in `BridgeBuildersGame.init` validate
  and throw a clear message. Add a test with 1 and 5 seats.

### F7. Leaving after a lost try records a loss for everyone (low, verified)

- `leaveSession` (BridgeBuildersGame.kt:91-97) with no bridge built gives every seat `isWinner = false`, bots included. In a
  game whose pitch is "no one loses alone" a failed session lands in stats as a loss. `leavingWithNoBridgeBuiltIsNobodysWin`
  pins this deliberately.
- Fix (product call): record as `wasAborted` when nothing was built so it is not counted, or score as a draw.

### F8. Marker the player sees is behind the marker that scores the tap (low, verified)

- `inputT()` (line 279) extrapolates up to 9 ms ahead of the last step, and `markerAt` (lines 339-343) uses it. The screen
  draws `triangle(cartPhase)` from the last step (state() line 453) and only refreshes every 25 ms plus a frame. So the
  scored position can lead the displayed one by about 25 to 50 ms. On the Big band (950 ms per pass, window 0.17) that is up
  to about 0.05 of the bar, or about 60 percent of the half-width. Taps feel slightly "early". It is consistent for all
  players, so it is a polish item.
- Fix: drive the ticker from `withFrameNanos` instead of `delay(25)` so display and engine are within one frame, or
  score against the displayed marker (`cartPhase`) instead of extrapolating.

### F9. Events can be coalesced inside one tick (low, verified)

- `setEvent` (line 252) overwrites one event slot. Several things can happen in one 25 ms tick (about 2 steps): a robot ROW
  and a human CROSS, or WATER_HIGH followed by LOSE. The screen keys sounds and the live region on the last one
  (BridgeBuildersScreen.kt:142-156, `bridgeStatusText` 218-227), so the earlier cue is lost. The only practical loss is a
  skipped ROW chime or WATER_HIGH haptic. Low.
- Fix: make `eventSerial` drive a small queue (list of pending events) or keep the highest-priority one.

### F10. Robot lanes are Buttons for TalkBack (low, verified)

- `TapTugBoard` gives every zone `role = Role.Button` with an `onClick` (PartyTapTugArt.kt:300-303). A robot lane has an
  `onActivate` that does nothing. TalkBack announces a button that does nothing. Shared code, so fix it in the board: skip
  the `onClick` and the role when `interactive[seat]` is false.

### F11. Resume replays a countdown, and a live resume has no grace (low, verified; product choice)

- A restored try starts from a 3 s countdown (documented). A live `resume()` after an app switch, however, restarts the cart
  and water immediately with no countdown (BridgeEngine.kt:247-250). A kid returning from a phone call finds the cart
  already sliding. Consider a short "3-2-1" after `resume()` when the pause was longer than a few seconds.

### F12. Waiting lanes can park the cart inside the green spot (low, verified; gameplay)

- `buildStep` only advances a lane whose status is MOVING (line 302). A lane that finished its plank and is WAITING keeps
  its cart frozen. Because the next window was drawn at random when the plank was placed (`pickCentre`, line 383), the
  cart is inside it about 24 percent of the time (Medium). When the rope loosens the player gets an instant free perfect
  plank. Not a bug, but it is exploitable and inconsistent between lanes. Option: keep the cart sliding while waiting and
  only block the tap (the status string already says "Waiting for friends").

### F13. Duplicated English text in three places (low, verified; maintainability)

- The three first-run tips exist as Kotlin literals in `BridgeBuildersHubSpecs.kt:89-95`, in `GameTips.kt:399`, and in
  `values/polish_strings.xml:106-108` (plus ES). The hub spec's `tips` and `accents` maps are now unused by the app
  (`GameTips` and `GameTheme.kt:141` carry their own copies), so they can drift. The coach strings file comment at line 3
  still says "move them to polish_strings.xml when GameTips is updated", which has been done. Remove the dead copies or add
  a test that the three agree.

## Items I checked and found correct (no defect)

- Fixed step and clock. `advanceTo` (lines 265-277) steps in exact 10 ms increments, keeps the sub-step remainder, caps at
  100 steps (1 s) per call, then drops the rest (`lastSim = now`). A long background gap therefore cannot cause a catch-up
  storm and cannot drown the player. The injected clock is monotonic in production (`System.nanoTime`). Pausing sets
  `lastSim = now` each tick, and `resume()` resets it, so water does not rise while paused. `onPause` and `onResume` are
  forwarded by `MainActivity` and `saveActiveProgress` runs on pause.
- Countdown restart on resume: `resume()` does not restart the countdown; only `restore` and `start` do.
- Hit logic at the boundary: engine miss is `dist > half` (line 365), so a tap exactly at the edge hits with quality 0.4
  (`EDGE_QUALITY`), and `hitQuality` agrees apart from a 1e-9 slack that never changes an outcome. Relaxed mode never
  misses, and quality is monotonic in distance.
- Handicap parsing (BridgeRules.kt:205-220): trims, lower-cases, length-capped at 40, "p1".."p4" validated against the seat
  count, comma list must have exactly one value per seat, NaN and Infinity rejected, values clamped to 0..1. Bad input
  returns all zeros and never throws. `MAX_WINDOW` caps the widened window, and `pickCentre` falls back to 0.5.
- Cooperation cap. `mustWait` (line 196) is `mine - min >= 2`. The slowest lane always has `mine - min = 0` so it is never
  WAITING, which makes deadlock impossible with 2 to 4 lanes. If the slowest lane is a robot it keeps tapping (its
  target `dist <= half*tol` is always reached as the cart sweeps the bar; the planned-miss branch targets
  `missAt > half` and `missAt <= 0.9 * farthest`, which the cart also reaches). A finished lane is DONE, not WAITING. If
  the slowest lane is a human who stops tapping, everyone waits and the water wins by design.
- Simultaneous taps. Taps are processed one after another on the main thread; `minPlanks()` is recomputed per tap, so the
  cap holds for any order. `BAD_SEAT`, `BOT_SEAT`, `NOT_BUILDING` guards are in order. Lose wins over win in the same step
  (water is raised first).
- Tally. `bridges` increments only in `finish(true)`, `tries` increments once in `beginTry`, and `restore` subtracts one so
  a resumed try is not double counted. `setup` resets the tally (called from `init`).
- Robot determinism. The RNG is drawn only when a robot arms (once per plank or after a stumble), in fixed-step order, so the
  same seed replays exactly (also tested).
- Snapshot validation (BridgeEngine.kt:94-113). Version, roster ids and bot flags, band, difficulty, handicap length, plank
  range per band, quality range per plank count, finite values, lead at most 2, water in [0, 1), non-negative counters. A
  rejected save leaves the engine untouched. `snapshot()` catches everything and returns null.
- Threading. Every mutator is `@Synchronized` or `synchronized(lock)`; the ticker, taps and `leaveSession` all run on the
  main thread. The only unsynchronised engine read is `leaveSession`'s `engine.state()`, also on main. No problem.
- Pointer handling and palm rejection. The shared `TapTugPointerTracker`, `ContactSizes` and `tapTugPointers` give per-id
  tracking, a 22 mm palm limit, per-pointer 90 ms and per-zone 40 ms anti-spam gaps, and release on cancel and on leaving
  composition. Rotated far zones keep unrotated hit rectangles (the rotation is a `graphicsLayer`), so a tap lands in the
  right lane. Robot zones use `TapTugRect.NONE`, so touches there are ignored.
- State reads and recomposition. `state` is a structural `mutableStateOf`, so an unchanged state after OVER does not
  invalidate; the early `return` at line 137 sits before all later `remember`s and is legal in Compose;
  `remember(seatCount, rotate, band)` keys are right.
- Reduced Motion. Water waves (amp 0), troll wave and confetti are all gated on `reduced`. The rising level is state, not
  decoration, and stays. Cart motion is the game itself.
- Accessibility basics. One polite live region carrying milestone text only; zones are half-screen sized (well over 56 dp);
  colour is never the only signal (glyph, pattern, wording, end markers, diamond, rope knot).
- Localisation. Every on-screen string is a resource. `bridge_builders_{,hub_,coach_}strings.xml` have exact key parity
  with `values-es`, and the printf argument lists match per key. There are no duplicate resource names across `values/` or
  `values-es/` (checked with `sort | uniq -d`). The hub file correctly does not redefine `hub_same_screen` or
  `hub_turn_the_far_lanes_around` (that one lives in `party_tap_tug_hub_strings.xml`). The hub specs and the screen
  strings carry no hard-coded English that bypasses the `hubKey` mechanism (the remaining English is in the spec literals
  that `hubKey` maps).

## Test gaps (rules not covered)

1. Drowning during CROSS (F2), and a win on the very step the water would reach 1.0.
2. `MAX_CATCHUP_STEPS` and a large single clock jump: no test advances the clock by more than 10 ms per `tick` (the rig's
   `adv` always steps 10 ms), so the 100-step cap and `lastSim = now` branch are untested. Add one `clock.advance(60_000)`
   then a single `tick()` and assert the water moved at most about 1 s worth.
3. Pause with a clock gap and no ticks in between, then `resume()`: the existing pause test ticks through the gap.
4. Tap exactly at the window edge (`dist == half`) and at `half + epsilon`; only offsets of 0.03 beyond or 0.98 of the
   half-width are tested.
5. Four lanes and three lanes with robots for the cap (only 2 and 3 human lanes are used), and the case where the
   slowest lane is a robot that is stumbling.
6. Several taps in the same tick from different lanes (including one that completes the bridge while another is a miss).
7. Roster guards: 1 seat and 5 or 6 seats (F6).
8. `BridgeHandicap.parse` with `NaN`, `Infinity`, `1e999`, `"0.5f"`, `p0`, upper-case `P2`, leading and trailing spaces.
9. Snapshot taken in CROSS (all lanes full) and restored: it should restart as BUILD then straight into CROSS.
10. `leaveSession` before `init`/`startMatch` (state is empty) and `restore` before `init` (`lateinit context` throws
    `UninitializedPropertyAccessException`, which `tryResume` catches, but a direct call would crash).
11. No UI or semantics test for the a11y zone descriptions, and none for the status text per event.

## Ranked top list

1. F1 Dialogs, coach card and focus loss do not pause: water, cart and robots keep running (first run is affected).
2. F2 Drowning during the crossing, unexplained and untested.
3. F3 TalkBack and switch users cannot find the green spot; the double-tap path gives no feedback.
4. F4 Native Path churn in `drawRiver` and `drawLane` at 40 Hz, including Reduced Motion.
5. F5 Handicap choices for lanes that do not exist; lane numbers are not visible on screen.
6. F6 Missing roster guards (1 seat crashes, more than 4 silently breaks resume).
7. F7 A failed session is recorded as a loss for everyone.
8. F8 Scoring marker leads the displayed marker by about one frame and a half.
9. The remaining low items: F9 event coalescing, F10 robot lane as a button, F11 no grace after live resume, F12 frozen
   cart inside the spot while waiting, F13 duplicated tips.

Water-after-restore: not a defect; adjust the test (see the section above).
