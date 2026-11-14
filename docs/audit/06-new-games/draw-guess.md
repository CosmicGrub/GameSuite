# Audit: Draw and Guess (`draw-guess`)

Read-only static audit, 2026-11-07. Scope: `games/drawguess/*` (Board, Game, Words, Glyphs, HubSpecs),
`ui/DrawGuessScreen.kt`, `ui/DrawGuessArt.kt`, `res/values{,-es}/draw_guess_*.xml`, tests in
`app/src/test/.../drawguess`. No Gradle, adb or emulator was run, so every finding is from reading code.
"Verified" means the failure follows from the code as written. "Suspected" needs a device or test to confirm.
Line numbers refer to the files as read today. Paths are relative to `app/src/main/java/com/gamesuite/`.

Kotlin 2.0.21 compose plugin, so strong skipping is on by default (matters for F2). Compose BOM 2024.09.

## Ranked findings

### F1. HIGH, verified: a turn that times out cannot be resumed (the save is silently destroyed)
- `games/drawguess/DrawGuessGame.kt:256-263` (clock), `:269-276` (tick), `:491` (restore check).
- Trigger: the timer runs out via `tick()`. The screen calls it every 250 ms, so the clock overshoots the limit by
  0 to 250 ms or more. `endRound` -> `stopClock()` stores that overshoot in `accumulatedMs`. The turn is now
  REVEAL with `elapsedMs()` of, say, 60 140 ms.
- The player backgrounds the app on the REVEAL screen. `pauseActiveGame` -> `saveActiveProgress` writes a snapshot
  with `elapsedMs = 60140`. Next launch, "Resume" -> `restore()` hits `cfg.limitMs > 0 && snap.elapsedMs > cfg.limitMs`
  and returns false.
- `GameSessionManager.tryResume` then deletes the save (`GameSessionManager.kt:210`) and the screen starts a
  fresh match. The whole game is lost with no message.
- The same happens in DRAW: backgrounding in the up to 250 ms window after the limit but before the next tick
  gives `elapsed > limit`.
- Not covered by tests. `theTimeIsUpExactlyAtTheLimit` advances the fake clock to exactly the limit, and
  `midDrawing()` snapshots at 5000 ms.
- Fix: clamp in `elapsedMs()` (`if (limitMs > 0) min(sum, limitMs)`), and also accept `elapsedMs <= limitMs + 5_000`
  in restore. Add a test: tick at limit+137 ms in a timed game, snapshot, restore must be true.

### F2. HIGH, suspected (cheap to confirm on a device): Undo and Clear may not repaint the paper
- `ui/DrawGuessArt.kt:226` reads `boardVersion` in composition. `:229` syncs the path cache in composition.
  `:279-281` is the draw lambda, which reads `tick` and `cache`, but never `boardVersion`.
- Undo and Clear (`DrawGuessGame.kt:248-250`) only bump `boardVersion`. Nothing writes `tick`.
- `DrawingCanvas` recomposes and rebuilds `cache`. With strong skipping the draw lambda captures only remembered
  instances (`cache`, `board`, `activePath`, `inkColors`, `paper`, the `tick` state holder), so it is memoized.
  The `drawBehind` node is then not updated and no draw invalidation occurs.
- Likely symptom: tap Undo and the stroke stays visible until the next touch. The stroke description count
  changes (semantics only).
- Finishing a stroke is fine because the gesture does `tick++`.
- Fix: read the version inside the draw scope (`val v = game.boardVersion.value` in the lambda), or
  `LaunchedEffect(version) { tick++ }`.
- Verify on a device: draw 2 strokes, press Undo, press Clear.

### F3. MED, verified: cancelling a guess plays the "wrong guess" buzz and announces "X sits this one out"
- `ui/DrawGuessScreen.kt:161-186`. The effect is keyed on `(phase, turn, lockedOut.size)`.
- `cancelGuess()` flips GUESS -> DRAW with `lockedOut` unchanged and non-empty (an earlier wrong guesser exists).
- `phase` changed, so the effect re-runs. It takes the `DRAW && lockedOut.isNotEmpty()` branch: FAILURE haptic,
  INVALID_BUZZ sound, and TalkBack says "Not quite. <previous wrong player> sits this one out".
- The same fires after process restore into DRAW with locked players.
- Fix: trigger on `lockedOut.size` increases only (remember the previous size, compare), not on phase.

### F4. MED, verified: no palm rejection, and extra pointers are ignored for the whole gesture
- `ui/DrawGuessArt.kt:248-272`. The first pointer down becomes the stroke (`down.id`). Pointer type and pressure
  are never checked, so a resting palm that lands first owns the stroke.
- Other pointers are not consumed or used. When the first pointer lifts the stroke ends, and
  `awaitEachGesture` will not start a new gesture until every pointer is up. A real finger that landed second
  draws nothing, then the line stops mid-stroke when the palm lifts.
- Common for kids resting a hand on a tablet or Fold inner screen.
- Fix: on `PointerType.Touch`, when a second pointer joins and a stroke is under 3 points or very short,
  restart with the newest pointer. Or prefer `Stylus` pointers. Or ignore a pointer whose contact is large
  (`event.changes` has no size on all devices, so best effort).

### F5. MED, suspected: landscape phones, canvas is squashed and cropped
- `ui/DrawGuessScreen.kt:410-419`. `wide = maxWidth >= 600.dp && maxWidth > maxHeight`. The canvas column is not
  scrollable. The canvas is `fillMaxWidth().aspectRatio(4/3)` (`DrawGuessArt.kt:238-240`).
- At 800x360 dp, the paper column width is about 440 dp, so the 4:3 height is about 330 dp. The column has about
  290 dp after the header. `fillMaxWidth` pins min=max width, so `aspectRatio` cannot hold 4:3 and the height gets
  clamped.
- The paper's bottom strip is then unreachable (input maps y by width, so y beyond about 0.6 is clipped), and
  strokes drawn near the bottom are cut off.
- Fix: size the canvas from `min(availableWidth, availableHeight * 4/3)` in the wide branch, or make the left
  column scroll.

### F6. MED, verified: typed-guess matching is too narrow for plurals, gerunds and Latin-American Spanish, and has one false positive
- `games/drawguess/DrawGuessWords.kt:269-276`. Only `+s` and `-s` are accepted.
- Rejected correct answers:
  - EN plurals: "foxes", "buses", "sandwiches", "tomatoes", "potatoes", "cherries", "strawberries",
    "butterflies", "mice".
  - EN actions (the drawer draws a verb): "jumping", "swimming", "dancing", "running".
  - ES plurals: "peces", "ratones", "leones".
  - ES actions: "riendo", "sentar" for "sentarse", "columpiar".
  - Latin-American variants: "auto", "carro", "papa", "torta", "tarta", "frutilla", "lentes", "hongo". ES text
    is Spain-leaning ("seta", "patata", "piruleta", "rosquilla", "cubo", "bolso", "cometa").
- False positive: `t == a.removeSuffix("s")` with `t.length >= 2` means "bu" is accepted for `bus`, "glasse" for
  `glasses`, "autobu" for `autobús`, "arcoiri" for `arcoíris`.
- A wrong typed guess costs the player their only go for the turn, so this is felt.
- Fix: per-word alias list (RAW row with a fifth column), generic stems (-es, -ies, -ing), and apply
  `removeSuffix("s")` only when the stripped result is at least 3 characters.
- Test gap: `DrawGuessWordBankTest` has no ES plural or alias cases.

### F7. MED, verified (design flaw): TalkBack speaks the secret word aloud at the table
- `ui/DrawGuessScreen.kt:285-292`. When the word is shown, the content has a polite live region plus
  `contentDescription = word.text(lang)`. With a screen reader the activate-to-toggle path
  (`toggled`, `:280-281`) keeps the word shown and spoken, and it stays toggled for the whole DRAW turn unless
  the player toggles it off.
- In a pass-and-play game with guessers next to the drawer, speech leaks the word.
- Fix:
  - Drop the live region on the word.
  - Auto-hide `toggled` after about 5 s, or when the stroke starts.
  - Add a one-line hint: "Use headphones".
- Related, by design: during DRAW any guesser can press the same hold button (`DrawControls`, `:493`). It is an
  honour system, so it is documented here, not a bug.

### F8. MED, verified: stroke caps truncate silently, and the eraser spends the budget
- `games/drawguess/DrawGuessBoard.kt:85`. At 400 points per stroke `extend` just returns false. The finger keeps
  moving and nothing is drawn or announced. `canvasFull` is only raised on finger-down
  (`DrawGuessArt.kt:254`).
- Scribble-colouring hits 400 points in roughly 1.6 canvas widths of path (`MIN_STEP` of 3 units keeps up to
  about 1 point per 3 to 4 units).
- 6000 total points is about 15 full strokes, then `begin` refuses everything, the eraser included
  (`isFull`, `:69`, `:73`). Eraser strokes are committed strokes that count toward 300 / 6000, so fixing a
  mistake with the eraser fills the paper faster.
- Fix:
  - Raise the per-stroke cap, or auto-split into a new stroke at the cap.
  - Show "paper full" as soon as a cap is hit mid-stroke.
  - Allow the eraser (or at least undo) with a bounded overflow.

### F9. LOW-MED, verified: pausing mid-stroke leaves the stroke open
- `games/drawguess/DrawGuessGame.kt:278`. `pause()` stops the clock but not `board`. `interactive` turns false,
  so the pointer coroutine is cancelled before `endStroke`. `board.drawing` stays true.
- Effects: Undo is a silent no-op (`DrawGuessBoard.kt:113`, "ignored while a finger is down") although the
  button looks enabled. The partial stroke is committed only on the next `begin`. `snapshot()` omits the
  stroke being drawn.
- Fix: in `pause()` (and when `buzz`/`endRound` cannot) call `board.end()` and bump `boardVersion`.

### F10. LOW-MED, verified: the whole play panel recomposes 4 times a second
- `ui/DrawGuessScreen.kt:215` passes `clockTick` as a parameter to `PlayStep`. The KDoc at `:99` claims "the clock
  redraws only its own text". It does not: `PlayStep`, `DrawControls` (unstable `DrawState` / `List` params),
  swatches, `FlowRow` layout and all lambdas rerun every 250 ms while drawing.
- It also hides a latent bug: Undo/Clear `enabled` (`:487-488`) reads a plain `ArrayList` that is not observable.
  It is only refreshed because the 250 ms tick happens to recompose it.
- Fix: read `clockTick` inside `TurnHeader` only (pass a `State<Int>` or `() -> Int`), and read `boardVersion`
  where `strokes.isNotEmpty()` is used.

### F11. LOW-MED, verified: canvas cost with long drawings
- `ui/DrawGuessArt.kt:283-299`. Every pointer event redraws all committed paths (up to 300 paths, 6000
  quadratic segments, round caps and joins) plus the active one.
- It also allocates a new `Stroke(...)` per path per frame: 300 or more small objects per frame while the finger
  moves.
- The per-move work is otherwise good: incremental `activePath`, `historical` consumed, no list copies.
- Fix:
  - Hoist 3 `Stroke` instances per brush.
  - Cache committed strokes into a `graphicsLayer` or `ImageBitmap` and redraw only the active stroke per move.
  - Rebuild the bitmap only on undo, clear or size change.

### F12. LOW-MED, verified: tool state is invisible to TalkBack
- `ui/DrawGuessArt.kt:368-392`. `ToolTextButton(selected=...)` is only a colour and border (the Eraser toggle).
  The swatches and brushes use `Role.RadioButton` plus a "selected" string hack (`:329`, `:359`) but no
  `selected` semantics, so TalkBack cannot announce the radio state.
- Fix: `Modifier.selectable(selected, role = RadioButton)` / `toggleable` for the Eraser.
- Related: the blind-drawer case is documented as unsupported (help text, KDoc `:93-98`). The canvas description
  is fine.

### F13. LOW, verified: snapshot validation holes (24 corrupt cases covered; these are not)
`DrawGuessGame.kt:448-515`. Covered by tests: version, timer, rounds, lang, turn, phase, word, roster, locked,
guesser, elapsed, stroke coords and caps, scores. Not covered or checked:
- `used.size` is only bounded above (`<= turn+1`). It should equal `turn` (CHOOSE) or `turn+1` (others). A
  save can claim no used words and still pass.
- `offered` may overlap `used` (CHOOSE), and in READY and later phases it may have any size or none. Only
  `word in offered` is checked when it is non-empty.
- `eliminated` has no duplicate check and no relation to `locked`. In pick mode `eliminated.size` should equal
  `locked.size`. In typed mode `eliminated` must be empty.
- DRAW restored with every eligible guesser locked is accepted. That state cannot occur in play (the round
  would have ended), but restore allows it. The Skip button is the only way out.
- REVEAL `result`: `gp`/`dp` have no upper bound (scores do, at 100 000), and `timedOut && solved` is allowed.
  `result.guesser` is checked against `eligible`, which is correct, but the scores are not reconciled.
- `scores.size == roster.size` is checked, but there is no plausibility check against turn and max points per
  turn.
- Payload size is unchecked before `decodeFromString`: a very large string with huge `p` arrays decodes into
  boxed `List<Int>` before `validate` (memory only, local file, low risk). Add a length cap (about 200 KB).
- The word is plaintext in the save file. Not guesser-readable in app, but not private.
- Coordinate ranges (0..1000 x, 0..750 y) are covered. A stroke with 1 point is allowed and handled.
  The `p` list of a valid game is at most 6000 ints.

### F14. LOW, suspected: `LaunchedEffect(context)` can start a new match over a live one
- `ui/DrawGuessScreen.kt:116-127`. If `activeContext` re-emits during a game (a new instance), the effect
  re-runs `init` and then `tryResume` (false, since `pendingResume` is consumed) -> `startMatch()`.
  Normally the context is stable.
- Also `lang = remember { ... }` (`:113`) is not refreshed on a runtime locale change (the manifest
  `configChanges` includes `locale`, so no recreation).
- Fix: guard with `if (game.state.value == null)`, and key `lang` on the locale.

### F15. LOW, verified: smaller state-machine and rule notes
- `startMatch` / `nextTurn`: seat 0 always draws first. `LaunchOptions.firstMoverIndex` is ignored
  (`DrawGuessGame.kt:208`).
- Buzz then Cancel is a free, unlimited clock pause (`buzz`, `cancelGuess`), and GUESS has no think limit. This
  is by design ("clock stops while guessing"), but exploitable.
- Engine `guessCard` is accepted even when the game is configured to type (`:305`); the UI never shows it.
- A snapshot taken on the OVER screen returns null and does not delete the earlier save of the last REVEAL
  (`GameSessionManager.saveActiveProgress` comment). Resuming it replays the final reveal.
- `init` with fewer than 2 human seats leaves `state` null forever (the screen shows "Getting the paper
  ready..." until the user leaves).
- Matches exact-case-insensitive typed text only; an empty or all-punctuation answer still costs the guesser
  their turn (the UI requires at least 2 characters, but punctuation passes).

### F16. LOW, verified: banned-word list
`DrawGuessWords.kt:281-294`. The list is only enforced by tests, not at runtime.
- Comment says fragments are 6+ letters, but `arma` (4) is listed. It would reject future words such as
  "armario", "alarma", "farmacia" (and `asesin`, `elecci`, `religi`, `cigarr` are fine).
- Gaps worth adding for a kids game:
  - EN: beer, wine, drug, naked, sex, toilet, poop, butt, bum, breast, grave, coffin, hell, hate, gamble,
    casino, smoke, gun-adjacent "shoot", "hang".
  - ES: droga, cerveza, vino, desnudo, culo, caca, tumba, infierno, odio, apuesta, humo, disparar.
- `party` / `fiesta` and `pie` are banned although harmless, which is harmless to keep.
- The current 185 words pass visually (no scary, brand or body-part entries found).

## Checked and OK (no defect found)
- Multiple choice (`multipleChoice`, `:243-254`): the answer is always present (appended before the shuffle),
  entries are distinct (others exclude the answer; `same` and `rest` are disjoint), 3 same-category when the
  bank has them, and size 6 holds for the whole bank (smallest category PLACE has 14 words). `choices` cannot
  hit an empty `from` (pool is at least 6).
- Scoring: `speedBonus` handles `elapsed > limit`, zero and no-timer. No division by zero (`limit > 0` guards in
  `TurnHeader`, `normalise` guards `width <= 0`). The double-scoring paths are closed: after `endRound` the
  phase is REVEAL and `guessCard`, `guessTyped`, `giveUp`, `buzz`, `tick` all check phase.
- Timer: the clock is stopped in GUESS, and resumes on cancel or wrong guess with no gap or double count.
  `pause` and `resume` are phase-gated. `nowMillis` going backwards is coerced.
- Secret word: not in semantics, announcements (CHOOSE and DRAW announcements name players only), logs, or
  guesser panels. The word appears only in REVEAL. Rotation is handled (`configChanges`, so no recreate).
  Process restore into CHOOSE re-shows the curtain (`shown` is keyed on turn and not restored), and READY /
  DRAW restores keep the word hidden. No `FLAG_SECURE`, but the word is not on screen unless held.
- Turn order with 2 to 8 players, teams alternate and teammates guess only (`init`, `eligibleGuessers`,
  `winners`, `standings`). The `teamMode` guard needs even seats, at least 4, and an equal split.
- Crash risks: no `!!`; `byKey ?: return` guards; `restore` catches exceptions (also before `init`, via
  `UninitializedPropertyAccessException`); `buildStrokePath` only sees validated strokes.
- Coroutines and threading: everything is on the main thread (`pauseActiveGame` runs from `onStop`).
- Localisation: ES key set identical (82 / 19 / 10 strings), placeholder counts match, no hard-coded UI English
  except `displayName` (save label). Picture names follow the word `text(lang)`.
- Reduced Motion: no animation in the screen, nothing to gate.
- Eraser paints paper colour; fine because the paper is always white.

## Test gaps
- No Compose or instrumented test (`androidTest` has nothing for draw-guess), so F2, F4 and F5 are untested.
- No snapshot test after a tick-driven timeout (F1) or restore in DRAW at `elapsed > limit`.
- No ES plural, gerund or alias cases in `DrawGuessWordBankTest` (F6); no `bus` / `bu` case.
- No test that cancelGuess leaves the failure effects alone (F3 is UI, so needs a screen test).

## Verdict
Shippable for a first release only after F1 (a silent save loss on the most common ending of a timed turn)
and F2 (confirm on a device; if Undo and Clear do not repaint, that is a showstopper for the main control)
are fixed. F3, F4, F5, F6 and F7 are quick, real UX or integrity issues worth fixing in the same pass.
The engine is otherwise well bounded: secret-word handling, multiple-choice generation, scoring and turn
order held up under review. The rest are low.

## Ranked top list
1. F1 timed-out turn cannot be restored; the save is deleted (verified)
2. F2 Undo and Clear may not repaint (suspected, verify on a device)
3. F3 cancel-guess plays the wrong-guess buzz and announcement (verified)
4. F4 no palm rejection, extra pointers ignored (verified)
5. F5 landscape phone canvas squashed or cropped (suspected)
6. F6 typed matching: plurals, verbs, LatAm Spanish, and "bu" accepted for bus (verified)
7. F7 TalkBack speaks the secret word aloud, toggle never auto-hides (verified)
8. F8 silent stroke-cap truncation, eraser uses the budget (verified)
9. F9 pause mid-stroke leaves the stroke open, Undo no-op (verified)
10. F10 and F11 whole panel recomposes at 4 Hz, full-path redraw per move (verified)
