# Audit: Echo Pads (`echo-pads`, `echo-pads-duel`)

Scope: `games/echopads/*`, `ui/EchoPadsScreen.kt`, `ui/EchoPadsArt.kt`, `res/values{,-es}/echo_pads_*.xml`, tests under `app/src/test/.../echopads`. Read-only; nothing built or run. `EchoPadsRulesTest` pitch ratios skipped as instructed.
"Verified" = confirmed by reading code and, where noted, by arithmetic. "Suspected" = needs a device or test run.

## Findings

### F1 (HIGH, verified by arithmetic) Version-bump signature collides, so the UI freezes with no Ready button
- File: `EchoPadsGame.kt:159-160` (`signature`), `:206-210` (`afterChange`). Consumer: `EchoPadsScreen.kt:169,230`.
- `signature = eventSerial*31 + phase.ordinal*7 + progress*3 + currentPlayer`. `tick()` returns false and does not bump `version` when the signature is unchanged. The screen only recomposes on `version`. `frame` is read only in draw lambdas, so nothing else recomposes the status text, the Ready button or the chips.
- Collision 1, turn handover after a copy (OK) in Pass and play: before = `31s + 3*7 + 3*len + cur`. After `TURN_READY` = `31(s+1) + 0 + 0 + next`. The two are equal when `3*len = 10 + (next-cur)`. Example: round 4, players 0 and 2 alive (1 eliminated), player 0 finishes, next is 2. The signatures are equal (`31s+33` both).
- Collision 2, after a miss in Pass and play: the miss emits `OUT` and `TURN_READY` (+62). The sum is zero when `3*progress = 41 + dPlayer`. Example: a miss at pad 14 with the next player index +1.
- Collision 3, new round: `3*len = 41 + (first - last)`, for example len 13 with dP -2.
- Effect: the turn is in READY awaiting hand-off (`deadline == NEVER`), but the screen still shows the old CHECKING state. There is no Ready button, so the match is stuck until something else bumps the version (the Leave button still works). Solo and duel self-heal, because their lead-in ends in a phase change on a later tick.
- Fix: bump on every `emit`, or make the signature collision-free: `eventSerial.toLong() shl 8 or phase.ordinal`. Simplest is to compare a `data class Sig(serial, phase, progress, player)`. Add a test that scans a long PnP match with eliminations and asserts the version changes at every phase change.
- Test gap: `theMatchRunsOnTheGamesClockAndBumpsTheVersionOnlyWhenSomethingChanged` only covers a short run.

### F2 (MED, verified) Resume (and the pause shift) during SHOWING silently drops part of the pattern
- File: `EchoPadsEngine.kt:302-306` (`shiftTime`), `EchoPadsGame.kt:123-131`.
- Backgrounding mid-pattern shifts the clock, so the pattern carries on from where it was. The player never saw the lights that played while away. In Pass and play this can eliminate someone unfairly.
- Fix: in `Game.resume()`, if `phase == SHOWING`, call a new `engine.restartShowing(now)` (startShowing plus a fresh lead-in). Test: pause during show, resume, assert `shown == 0`.
- Also: `pause()` overwrites `pausedAt` if called twice without `resume()` (`:124`). Guard with `if (pausedAt < 0)`, as the other games' pause does.

### F3 (MED, verified by arithmetic) Duel pads are below 72dp on landscape phones and on the fold cover screen in landscape
- File: `EchoPadsScreen.kt:294-307`, `ReactionWhackZones.layout` (n==2 always stacks halves vertically at full width).
- At 360dp window height: about 308dp after the status row, so about 154dp per half. Minus 24dp of padding, a 36dp header and a 6dp spacer, the board is about 88dp tall. Each pad is about 42dp tall, even though it is wide. The 72dp and 48dp touch-target minimums are missed.
- Pass and play on a short landscape screen: the board side is `min(w,h)` after the chips FlowRow (up to 3 rows with 6 players), the Ready button (56dp) and 24dp of padding, so pads drop to about 60dp (suspected, needs device).
- Fix: in landscape, put the duel halves side by side (a new `layout` variant), or arrange pads 1x4 in a half, or force portrait for the duel. For Pass and play, move the chips into a horizontal strip or scroll them.

### F4 (LOW-MED, verified) The board resizes when phases change
- File: `EchoPadsScreen.kt:310-329`. The board is `weight(1f)`, and the "x of y" Text appears only in WAITING (the Ready Button only appears during hand-off). Entering WAITING shrinks the square board by about 24dp and every pad rect shifts under the finger as copying starts.
- Fix: reserve the line (alpha 0 or a fixed `heightIn`) in every phase.

### F5 (MED, verified) The solo best-score write can be cancelled or lost
- File: `EchoPadsScreen.kt:230-248`.
- `recordScore` runs inside `LaunchedEffect(engine, version)`. A `version` bump or Play Again (new engine) cancels the effect mid `edit`, and navigating away disposes it. `recorded.value = true` is already set, so the score is never retried.
- Also `catch (_: Exception)` swallows `CancellationException`.
- Fix: write from `EchoPadsGame.afterChange`, or from an app-scoped coroutine or `rememberCoroutineScope` launched at OVER. Rethrow `CancellationException`.
- Profile scoping (verified OK): `ProfileScopedDataStore.updateData` resolves `ProfileScope.current()` at write time, and `bests` follows `activeId` through `flatMapLatest`, so reads and writes agree. The record is per profile, not per seat. A profile switch between the effect and the write would misattribute the score (theoretical).

### F6 (LOW-MED, verified) Per-tap AudioTrack construction and no low-latency flag
- File: `EchoPadsTones.kt:79-103`. Every tone builds, writes and plays a fresh AudioTrack, then releases it 470ms later. Typically 5-30ms, and more on first use, with no `setPerformanceMode(PERFORMANCE_MODE_LOW_LATENCY)`. The PCM is rendered lazily on the first tap (`getOrPut`). The first tap is therefore late and tone onset jitters against the lit pad, which matters in a rhythm-like memory game.
- Fix: pre-render the four buffers in an `init`/`warmUp()` called from the screen, build with `PERFORMANCE_MODE_LOW_LATENCY`, and consider four long-lived STATIC tracks (stop, `reloadStaticData`, play). Overlap is only needed when pad N repeats within 320ms, and with the minimum step of 260ms that overlap is about 60ms.
- Lifecycle review (no leak found): release is scheduled on the same single-thread executor. Every failure path releases the track and decrements `inFlight`. `MAX_CONCURRENT=6` and `MAX_QUEUED=12` bound the load. There is no explicit `shutdown`, but the thread is a daemon.
- Concurrency with ProceduralSfx: separate tracks that mix, which is fine. Mute is read at `play()` time only (`CardSounds.soundEnabled`), so already queued tones still sound for up to a few ms. Harmless.
- No SFX volume setting exists in the app (grep), so no volume parity issue.

### F7 (LOW, verified) Backgrounding or save/restore hands out free retries and undoes misses
- `snapshot()` and `restore()` replay the turn: `retryUsed` resets (`beginTurn`), and a miss in CHECKING that was not yet resolved is not recorded. Pause during the miss feedback, then resume, gives a new try. Pause on a sure miss in Pass and play is a cheap way to avoid elimination.
- Fix: snapshot should treat `outcome == MISS` as resolved (apply the elimination first) and persist `retryUsed`.

### F8 (LOW, verified) An always-on frame loop and redraw
- File: `EchoPadsScreen.kt:194-200,258-261`. `withFrameMillis` loops forever, including in OVER and in hand-off READY. `litProvider` reads `frame.value` in draw, so the canvases redraw every frame even when idle (battery on a kids' game left on the screen).
- Fix: only invalidate while `phase in SHOWING..CHECKING` or while a flash is pending; stop the loop in OVER and awaiting-ready.

### F9 (LOW, verified) Pad contrast on light theme
- File: `EchoPadsScreen.kt:97-103,474-490`. The yellow pad `0xF0E442` on the light card or background (`E8E4F3`, `F6F3FB`) is about 1.2:1. In the can-tap state the outline is white at 0.6 alpha, invisible on a light background, so the pad boundary is not 3:1 (WCAG 1.4.11). Shape glyphs still identify it.
- Fix: a dark outline `outline.copy(alpha=.6)` around every pad, or a darker yellow when `!dark`.

### F10 (LOW, verified) Smaller items
- Free retry and miss use the same buzz (`EchoPadsScreen.kt:236`). Audio-only players cannot tell "try again" from "out". Use a softer sound for RETRY.
- `EchoPadsGame.config()` coerces seat count to 6, but `context.players` can be longer. If the roster is over 6, indexes such as `context.players[w]` and `seatName` mismatch the engine. Reject or truncate the roster in `init`.
- `EchoPadsStatsStore.decode` drops the whole table on a single bad entry (a throw yields `emptyMap`), and the next write overwrites it. Decode entry by entry for forward compatibility.
- The finished panel in the duel is upright for one player only (`:363-387`). Players on the far side read it upside down. Acceptable, but consider a rotated copy for the loser.
- Duel feedback: taps by the non-current player are ignored with no feedback (by design, but kids may think the game is frozen).
- Gap between pads (4%) is a dead zone with no tolerance (`EchoPadGrid.padAt`). Fast playing next to the gap is lost. Consider nearest-pad snapping within the gap.
- `contentDescription` for each pad reads `litProvider` (a frame-state read) inside the semantics block, so the semantics node is invalidated every frame once an accessibility service is on. Not a liveRegion, so no spoken spam. Prefer an `onClick`-only label and a polite live region for "lit" only if wanted.

## Checked and found OK
- State machine: `tap` is accepted only in WAITING. Taps during READY, SHOWING, CHECKING and OVER return IGNORED (tests cover these). `tap` sets CHECKING before deciding, so double taps in one frame cannot double-count; multi-touch is serialised through the `@Synchronized` Game methods. `seq[progress]` is always in range (progress < size in WAITING). `litStep` can never divide by zero (stepMs >= 260).
- Forgiving: `retryUsed` is reset only in `beginTurn`, so one free try per turn (a re-show does not renew it). Tested.
- Elimination and tie: `alive` is recomputed each round, so order follows ascending index and skips out players; all-left-miss gives a tie among `order`; one left wins; atCap ties the survivors; solo cap is a win. Six-player run is tested.
- Duel: first player alternates by round parity; a lone miss means the other wins after both have played; both missing ties; both copying grows the pattern. Hit-testing: `RwZoneBox` rotates with `graphicsLayer`, so pointer input is correctly transformed. `Game.tap` rejects the non-current seat.
- Timing: injected clock in tests; the production clock is monotonic `nanoTime`. Speed times game speed is clamped (0.25..4), floors at 170ms lit and 90ms gap. `shiftTime` covers show start and the deadline.
- Rotation: the manifest handles `configChanges`, so the screen is not recreated.
- Snapshot validation is thorough (`isValidFor` checks lengths, ranges, uniqueness and turn order per mode). A failed restore leaves the engine untouched. There are no `!!` in the game files.
- i18n: ES and EN key sets are identical in `echo_pads_strings`, `echo_pads_hub_strings` and `echo_pads_coach_strings`. Strings used by the screen go through `stringResource`; only separators ("·") and "x of y" templates are literal.
- Colour-blind: Okabe-Ito palette plus distinct shapes plus distinct pitches. Reduced Motion removes swell and halo, and brightness still changes. Sound-off is visual only; haptics still tick.
- Status line is a Polite live region that changes once per phase (not per frame), so no TalkBack spam.

## Test gaps
1. A long Pass and play run that asserts `version` changes at every phase change (F1).
2. Pause during SHOWING, then resume (F2).
3. Snapshot taken during a MISS CHECKING and during a RETRY (F7).
4. No test of `EchoPadTones` beyond `renderPcm`, and no concurrency or mute test.
5. No Compose or UI test: pad size minimum on small or landscape windows (F3), hit-test through a rotated zone.
6. `recordScore` cancellation, and profile-scoped best (F5).
7. Roster larger than 6 (F10).

## Ranked top list
1. F1 signature collision freezes Pass and play at hand-off (HIGH).
2. F3 duel pads about 42dp tall in landscape (MED).
3. F2 pattern skipped when backgrounded mid-show (MED).
4. F5 best-score write can be cancelled (MED).
5. F6 AudioTrack per tap, lazy PCM, no low-latency flag (LOW-MED).
6. F4 board resizes in WAITING, F7 free retries on resume (LOW-MED).
7. F8 always-on redraw, F9 yellow contrast, F10 smaller items (LOW).

## Verdict
The rules engine is solid and well tested. Ship-blocking: F1 (a rare but real soft-lock in the main party mode). Fix F1, F2, F3 and F5 before release; the rest can follow.
