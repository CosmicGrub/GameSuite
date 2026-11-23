# Haptics cue sheet

One place to read and tune every game's vibration. Nothing here was feel-tested on a device yet (no
install is allowed from the build sessions); every number is a first guess for the owner to adjust.

## How it is built

- `haptics/HapticPatterns.kt`: the **waveform table**. Each of the 10 signals is a plain
  `timings`/`amplitudes` pair (`VibrationEffect.createWaveform` format, amplitude 0..255). Edit the numbers
  to change how a signal feels.
- `haptics/HapticCue.kt`: `HapticCue(signal, delayMs)` (delay = offset from the START of the gesture) and
  `HapticSequence` (a list of cues; `spanMs` = how long the motor is busy, excluding an animation
  `leadInMs`). `HapticRules` holds the shared decisions (arena/all-bot gate, "lighter" mover, win/loss/draw
  from the local player's seat). `ChangeTracker` makes the first value seen a baseline so a restore never fires.
- `haptics/BoardHapticCues.kt`, `PuzzleHapticCues.kt`, `StrategyHapticCues.kt`: the **per-game cue maps**
  (pure functions, unit-tested in `app/src/test/.../haptics/`). The named constants at the top of each
  object are the tuning knobs.
- `Haptics.kt`: Android adapter (`performHaptic`), `rememberHaptics()` for single signals,
  `rememberHapticSequencer()` for sequences (cancels the previous sequence, cancels on leaving the screen,
  does nothing when the Haptics setting is off).

Rules every screen follows: fire from an effect keyed on a board change or serial (never every
recomposition); the first state seen (resume/restore) is a baseline and is silent; no haptics during a
Computer Arena series (`sessionManager.arenaActive`) or at an all-bot table; haptics obey only the Haptics
setting (`LocalHapticsEnabled`). A non-celebration gesture keeps the motor busy at most 450 ms (animation
lead-in excluded); a move that also passes or ends the board chains those cues after it.

## Signals (HapticPatterns.kt)

| Signal | Waveform (ms @ amplitude) | Total | Intended moment / feel |
|---|---|---|---|
| LIGHT_TICK | 12 @ 90 | 12 | selection, faint acknowledgement |
| NORMAL_ACTION | 25 @ 140 | 25 | an ordinary action completing |
| STRONG_ACTION | 20 @ 200, gap 40, 30 @ 220 | 90 | capture, big play: a double knock |
| ESCALATING | 15 @ 120, 15 @ 160, 20 @ 200 (60 ms gaps) | 170 | rising tension: three growing pulses |
| SUCCESS | 18 @ 150, gap 50, 28 @ 220 | 96 | a good outcome: short then long |
| FAILURE | 60 @ 180, gap 40, 60 @ 140 | 160 | a bad outcome: two heavy buzzes, falling |
| CELEBRATION | 25 @ 160, 25 @ 190, 25 @ 220, 60 @ 255 (40 ms gaps) | 255 | the biggest moment: four rising pulses |
| ROLL (new) | five taps 8-12 ms @ 110-170, 12-20 ms gaps | 118 | dice rattle |
| LANDING (new) | 40 @ 105 | 40 | soft thud of a piece landing; longer and gentler than NORMAL_ACTION |
| RIPPLE_STEP (new) | 8 @ 55 | 8 | faintest tick, one per cascade step |

## Per-game cues

"Human" = the person holding the phone. "Lighter" = a bot or the remote player in a networked game.
Delays are offsets from the start of the gesture.

### Reversi (`ReversiHapticCues`)

| Event | Signal(s) @ delay | Intended feel |
|---|---|---|
| Human places a disc | NORMAL_ACTION @0 | the tap |
| Flip cascade (n discs) | RIPPLE_STEP @60, 120, ... (max 6 ticks, 5 when a finisher follows) | a quick shiver sweeping across the board |
| Big flip (>= 5), or corner / edge | STRONG_ACTION @ (ticks x 60 + 40) | the cascade lands with weight (ends by ~430 ms) |
| Bot / remote move | LIGHT_TICK @0, up to 4 RIPPLE_STEP, NORMAL_ACTION finisher on big/corner/edge | the same shape, lighter, so bot moves are not silent |
| Forced pass | ESCALATING after the move gesture (+60 ms gap) | a rising "hold on" pulse with the pass banner |
| Board end | WIN CELEBRATION / LOSS FAILURE / DRAW SUCCESS, after the move gesture | |
| Tap on an illegal cell | none (only legal cells are tappable) | |

Constants: `RIPPLE_STAGGER_MS=60`, `MAX_RIPPLE_TICKS=6`, `MAX_RIPPLE_TICKS_WITH_FINISHER=5`,
`MAX_RIPPLE_TICKS_LIGHT=4`, `BIG_FLIP_COUNT=5`, `FINISHER_GAP_MS=40`, `FOLLOW_UP_GAP_MS=60`.

### Connect Four (`ConnectFourHapticCues`)

All offsets include the fall time `fallMs(row)` = 140 + 50 x (row+1) ms, max 350, 0 with reduced motion;
the screen's disc tween uses the same function, so buzz and picture stay in sync.

| Event | Signal(s) @ delay | Intended feel |
|---|---|---|
| Human disc lands | LANDING @fall, RIPPLE_STEP @fall+70 | a thud and a smaller bounce |
| Bot / remote disc lands | LIGHT_TICK @fall, RIPPLE_STEP @fall+70 | lighter |
| Creates a new open three (either side, board not over) | ESCALATING @fall+90 | "watch out" |
| Win (winning line) | SUCCESS @fall+90, CELEBRATION @fall+240 | line pulse, then the big one |
| Loss | FAILURE @fall+90 | |
| Draw | NORMAL_ACTION @fall+90 | |

Constants: `FALL_BASE_MS=140`, `FALL_PER_ROW_MS=50`, `FALL_MAX_MS=350`, `BOUNCE_DELAY_MS=70`,
`FOLLOW_UP_MS=90`, `CELEBRATION_AFTER_MS=150`. The old NORMAL_ACTION on the tap itself was removed (the buzz
now arrives with the landing, up to 350 ms after the tap): if that feels laggy, add a RIPPLE_STEP @0 in `forDrop`.

### Backgammon (`BackgammonHapticCues`)

| Event | Signal(s) @ delay | Intended feel |
|---|---|---|
| Dice thrown (opening roll, tie re-roll, each turn's roll) | ROLL @0, LIGHT_TICK @150 | rattle, then a tick as they settle (the screen has no dice animation, so 150 ms is the stand-in) |
| Checker move / bar entry | NORMAL_ACTION | |
| Hit a blot | STRONG_ACTION | |
| Bear off | LIGHT_TICK | |
| Game won / lost | CELEBRATION / FAILURE @60 | |

Constants: `SETTLE_DELAY_MS=150`, `END_GAP_MS=60`.

### Lights Out (`LightsOutHapticCues`)

| Event | Signal | Notes |
|---|---|---|
| Cell toggle | LIGHT_TICK | was NORMAL_ACTION |
| Solve | CELEBRATION | the solving tap plays no tick (hands over). A ripple over the cells was not added. |

### Sliding Puzzle (`SlidingPuzzleHapticCues`)

| Event | Signal | Notes |
|---|---|---|
| Tile slides | NORMAL_ACTION | |
| Tap on a tile that cannot move | LIGHT_TICK | the "blocked" cue; FAILURE would be too harsh |
| Solve | CELEBRATION | was SUCCESS; the solving slide plays no NORMAL tick |

### Hex (`HexHapticCues`)

| Event | Signal(s) @ delay | Notes |
|---|---|---|
| Stone placed / pie swap | NORMAL_ACTION | bot stones too (unchanged) |
| Chain connected, human wins | SUCCESS @50, CELEBRATION @190 | |
| Board lost | FAILURE @50 | |
| Rejected tap | FAILURE (unchanged), confirm-tap LIGHT_TICK (unchanged) | |

### Hare and Hounds (`HaHHapticCues`)

| Event | Signal(s) @ delay | Notes |
|---|---|---|
| Piece moved (either side) | NORMAL_ACTION | was LIGHT_TICK for the hare; no longer fires on resume |
| Hounds trap the hare, local wins | SUCCESS @0, CELEBRATION @140 | after the 450 ms announcement delay |
| Hare escapes, local wins | CELEBRATION | |
| Local loses | FAILURE | |
| Selecting a piece / illegal tap | LIGHT_TICK / FAILURE (unchanged) | |

### Snake (`SnakeHapticCues`)

| Event | Signal(s) @ delay | Notes |
|---|---|---|
| Food eaten | NORMAL_ACTION | was LIGHT_TICK |
| Length crosses a multiple of 10 | SUCCESS | replaces the old level-up SUCCESS (the level-up chime sound remains) |
| Death | FAILURE @0, STRONG_ACTION @170 | "strong" failure |
| New best / board cleared | CELEBRATION | unchanged |
| Steering | LIGHT_TICK | unchanged |

Constants: `MILESTONE_EVERY=10`, `DEATH_THUD_AFTER_MS=170`.

## Not done

- No on-device tuning, because installs are not allowed from these sessions.
- Match-end `ResultFeedback` (global, MainActivity) is untouched; it still fires when the whole session
  ends, after the per-board cues above.
- Lights Out solve ripple, a Connect Four tap-down tick, and bot-lighter variants for Backgammon were left out.
