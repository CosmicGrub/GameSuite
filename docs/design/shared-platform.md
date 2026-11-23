# Shared platform APIs (Team P)

How a game adopts the shared pieces added on `session/ws-platform`. All of them are pure-logic first
(JVM-tested) with a thin Compose wrapper.

## 1. Overlay-open pause (`uikit/OverlayPause.kt`, `GameChrome`)

While ANY GameChrome overlay is open (corner menu, How to Play, leave confirm) the active game is
paused, and resumed when the LAST overlay closes. Nothing to do for the common case: a screen that
uses `GameChrome` and whose `GameModule.pause()/resume()` really stop the game gets it for free.

- The shell provides `LocalOverlayPauseHost` (MainActivity: `currentModule.pause()/resume()`).
- `OverlayPauseController` is the state machine: per-key open set, pause on first open, resume on
  last close, idempotent per key, and it never pauses or resumes a game that is already paused
  (it asks `PausableGame.isPaused` from `shared/.../core/PausableGame.kt`, optional on the module).
- A screen that pauses state the module cannot see passes `overlayPause = OverlayPauseHook(onPause,
  onResume, isPaused)` to `GameChrome`.
- A screen with its own dialog or coach card calls `OverlayPauseEffect("my-dialog", open = showDialog)`
  anywhere inside the `GameChrome` content; it uses the same counting.
- Confirming Leave calls `controller.abandon()` so an ending match is never resumed.
- Remove old `extraItems = { DisposableEffect(Unit) { game.pause(); onDispose { game.resume() } } }`
  tricks (done for Air Hockey, Toy Tennis, Fishing). Games whose `pause()` is an empty body still need
  a real pause: implement it, or pass an `overlayPause` hook.

## 2. Shared frame clock (`uikit/GameFrameClock.kt`)

```kotlin
rememberGameFrameClock(running = !state.matchOver) { dt -> game.tick(dt) }
```
Lifecycle-gated (`repeatOnLifecycle(STARTED)`: stops on ON_STOP, restarts with a fresh baseline),
stops when `running` is false, dt is 0 on the first frame and clamped (default 0.1 s). The callback
form changes no Compose state. For a purely visual per-frame value use `rememberGameFrameTick(running)`
and read `.value` ONLY inside draw / graphicsLayer lambdas. Pure parts: `FrameStepper`, `runFrameLoop`,
`FrameSource` (fake it in tests). Adopted: Air Hockey, Tower Defence. Six-Ball's pulse is now read in
draw only. Remaining per-frame screens (Toy Soccer, Toy Tennis, Slot Cars, Fishing) still use
`withFrameNanos { game.advance() }` and can switch to the callback form.

Known limit: a screen that does `val state by game.state` and reads it in the body still recomposes
each time the engine publishes a new state object; the clock removes loop duplication and lifecycle
bugs, not that. Narrow it with `derivedStateOf` / read inside `Canvas` lambdas when converting a screen.

## 3. Daily seeds (`core/DailySeed.kt`)

`DailySeed.forToday(gameId, tier = 0)` returns a stable Long from the UTC date, the game id and a
tier slot; it ignores the player's difficulty setting and time zone. `DailySeed.todayEpochDay()` is the
UTC day number for games that derive their own seed (Flow Lines, Spider, Six-Ball). MainActivity's
`*-daily` routes now use it; saved-game daily expiry uses the same UTC day. New dailies: never call
`LocalDate.now()` for a seed.

## 4. Hard-coded English ratchet (`EnglishLiteralRatchetTest`)

Counts string literals with letters in `Text("...")`, `contentDescription`, `label`, `onClickLabel`,
`title`, `placeholder` per file of `app/src/main/java/com/gamesuite/ui/*.kt`, against
`app/src/test/resources/english-literal-baseline.txt`. The test fails if a count rises, a new file
appears with a literal, or a count drops without the baseline being regenerated. After moving strings
to `strings.xml` (EN + ES): `scripts/regen-english-baseline.sh` (or
`ENGLISH_BASELINE_REGEN=1 ./gradlew :app:testDebugUnitTest --tests '*EnglishLiteralRatchetTest*'`).

## 5. Palm rejection (`uikit/PalmRejection.kt`)

See the file: `PalmGate` (pure), `ContactSizes`, `Modifier.palmRejecting()` (consumes palm-sized downs
before descendants such as `clickable` see them).
