# Quality scorecard G2: strategy and arcade games

Scope: `shogi`, `minishogi` (route `mini-shogi`), `backgammon`, `yahtzee`, `mastermind`, `towerdefence` (route `tower-defence`), `tankbattle` (route `tank-battle`), `airhockey` (route `air-hockey`), `breakout`, `edgematch` (route `edge-match`).
Tree: `Z:\GameSuite-wt-game-kit`, branch `session/wave-3`. Date 2026-11-21. Read-only audit: no Gradle, no device, no emulator. Nothing was run, so every claim is "by reading".
Bar: `docs/roadmap/QUALITY-BAR.md` (8 gates). Checklist of defect kinds: `docs/audit/06-new-games/*.md`. Context: `docs/audit/02-performance.md`, `docs/audit/04-accessibility-ux-i18n.md`.

Scoring: 0-3 per axis (3 = meets the bar, 0 = absent or broken). Evidence tags: **V** = verified by reading the cited line, **S** = suspected (inferred, needs a device or profiler to confirm).
Effort tags: S under 1 day, M 1-3 days, L a week or more.

## Summary

| game | gameplay | feel | robustness | a11y_i18n | tests | total /15 |
|---|---|---|---|---|---|---|
| tankbattle | 3 | 3 | 3 | 2 | 3 | **14** |
| shogi | 3 | 3 | 2 | 2 | 3 | **13** |
| minishogi | 3 | 3 | 2 | 2 | 3 | **13** |
| yahtzee | 3 | 2 | 2 | 3 | 3 | **13** |
| mastermind | 3 | 2 | 3 | 2 | 3 | **13** |
| backgammon | 3 | 2 | 3 | 2 | 2 | **12** |
| edgematch | 3 | 2 | 2 | 2 | 3 | **12** |
| breakout | 2 | 2 | 3 | 2 | 2 | **11** |
| towerdefence | 2 | 2 | 2 | 1 | 2 | **9** |
| airhockey | 2 | 3 | 1 | 0 | 1 | **7** |

Pattern: the turn-based board and puzzle engines are the strongest part (perft, fuzz, snapshot tamper tests). The real-time games lag on robustness and on Spanish and TalkBack. Four screens have 0 `R.string` references and are English-only in-game: Mastermind, Tower Defence, Air Hockey, Breakout, plus Edge Match (verified by grep count of `R.string` in each `ui/*Screen.kt`; Hub, coach and tip strings are localised separately).

Test counts (`@Test` grep): shogi 82, backgammon 96, yahtzee 70, mastermind 38, towerdefence 28, tankbattle 50, breakout 32, edgematch 65, airhockey 3 (shared Kotlin) + 3 (Rust, same three cases). mini-shogi shares the shogi suites (perft for MINI included).

None of the nine screens reads `GameThemes` accents; each draws its own palette (Shogi cream and wood, Tank sky art). That matches the "per-game material look" in gate 3 but not the "Midnight Club chrome plus per-game accent" target in `docs/design/canvas/Main.dc.html`. Only Territory Wars has Midnight Club tokens in code (`games/territorywars/TerritoryWarsPalette.kt:16`). This is a shared finding, not scored per game.

---

## shogi (13/15)

Engine: `games/shogi/ShogiEngine.kt` (622 lines), `ShogiGame.kt`, screen `ui/ShogiScreen.kt`, board `ui/ShogiBoardView.kt`.

- **GAMEPLAY 3.** Perft matches published values at depth 1-4 (30, 900, 25470, 719731) in `ShogiPerftTest.kt:9-18`; nifu, dead-piece drops, uchifuzume (`ShogiEngine.kt:357,388,426`), fourfold repetition with perpetual check (`ShogiGame.kt:217-235`), impasse declaration (`ShogiEngine.kt:445-470`), move limit. Three CPU levels differ in depth, time and noise (`ShogiGame.kt:50-61`: EASY depth 1 plus 140cp noise, MEDIUM 3 or 4, HARD 5 or 7 with 2.5-3 s caps). Search runs on `Dispatchers.Default` against a copied position and drops stale answers (`ShogiGame.kt:394-397`). Hint, undo, "why" message on refused drops (`ShogiScreen.kt:107`). Snapshot decode validates squares, captured codes, rep size (`ShogiGame.kt:449-515`). V.
- **FEEL 3.** Flight tween with overshoot scaled by game speed (`ShogiScreen.kt:248-270`), distinct haptic and sound for check, capture, quiet move, win, lose; petal celebration (`:827-839`); kanji or letter piece styles with wood and cream palette in light and dark (`ShogiBoardView.kt:71-85`); Reduced Motion snaps the flight (`:266`); the idle pulse is a `State` read only in a graphicsLayer (`:288-292`, `:577`). V.
- **ROBUSTNESS 2.**
  - `pause()`/`resume()` are empty (`ShogiGame.kt:184-185`); the CPU keeps its timer behind the help or leave dialog. V.
  - `LaunchedEffect(context)` re-runs `init + startMatch` if the context object is re-emitted (`ShogiScreen.kt:171-185`). Same family as slide-climb F14. S.
  - `flight.value` is passed as a parameter, so the screen recomposes each frame of the 280 ms flight (`ShogiScreen.kt:514`); `legal.filter{}.toSet()` is recomputed on every recomposition (`:302,307,351,601`). Already listed in `02-performance.md` 3.2. V.
  - The hint button launches a new search per tap with no in-flight guard (`:635`). V (low).
  - The CPU always declares impasse when allowed, regardless of level (`ShogiGame.kt:380`). V (design nit).
- **A11Y_I18N 2.** ES parity is full (106 string keys in values-es, 83 `R.string` uses in the screen), per-cell semantics and live regions exist (`ShogiBoardView.kt:349-350`, `ShogiScreen.kt:570,589`). Gaps: `LocalColorblindMode` is never read (0 hits in both files) while legal, select, hint, and check markers are separate hues (`ShogiBoardView.kt:75-76,84-85`); default cell minimum is 30 dp (`ShogiScreen.kt:75`), so a 9x9 board on a 360 dp phone is about 40 dp per cell, under 48 dp. V.
- **TESTS 3.** Perft, a random-play fuzz with invariants (`ShogiFuzzTest.kt:18`), snapshot and undo round-trip fuzz (`:49`), networked host and guest suites (15 tests), last-move validation, localization test. V.

Top 5 fixes
1. (S) Read `LocalColorblindMode` in `ShogiBoardView` and add a shape per marker (ring for select, dot for legal, cross-hatch for hint) alongside the hue.
2. (S) Hoist `flight` into the board `Canvas` draw lambda (pass the `Animatable`, read `.value` in draw) and wrap `legal` target sets in `remember(legal, sel)`.
3. (S) Guard the hint: `var hinting` flag or `Job` cancel in `ShogiScreen.kt:635`; also key `LaunchedEffect(context)` on a one-shot flag.
4. (S) Implement `pause()/resume()` as a `paused` flag that `playBotTurnAsync` checks after the search returns, and have it hold while GameChrome overlays are open (see shared finding 1).
5. (M) Raise tap targets: enlarge the hit area of a cell with an invisible 48 dp slop (`minimumInteractiveComponentSize`) or default to a pannable board on cells under 36 dp.

## minishogi (13/15)

Same engine and screen as shogi, `ShogiVariant.MINI` (5x5, promotion zone 1 row, no impasse). Everything above applies; scores are identical.

- **GAMEPLAY 3.** `ShogiPerftTest.kt:21-28` pins 14, 181, 2512, 35401; the test comment says the values are "self-consistent" and "match the published table", so the reference is a claim and not a cited source (V). Mini has its own search params (`ShogiGame.kt:59-61`).
- **FEEL 3, ROBUSTNESS 2, A11Y_I18N 2, TESTS 3.** Same evidence. Mini-specific: coach card and tips exist (`GameCoach.kt:178`, `GameTips.kt:209`, ES `coach_strings.xml:214`). With 5 columns the board cell is large, so the 48 dp target concern is much smaller than in standard shogi (S).

Top 5 fixes
1. (S) Colour-blind markers (as shogi item 1).
2. (S) Same composition-scope fixes (flight and `legal.filter`) as shogi item 2.
3. (S) Add a mini-specific scripted full game test (a known mate-in-N from a published mini-shogi problem) in addition to perft.
4. (S) Pause hook (shogi item 4).
5. (S) Hub: confirm mini-shogi's Hub spec exposes the same CPU level and first-mover choices as shogi (not verified).

## backgammon (12/15)

`games/backgammon/{BackgammonRules,BackgammonBot,BackgammonGame}.kt`, `ui/BackgammonScreen.kt`, `ui/BackgammonBoardView.kt`.

- **GAMEPLAY 3.** Forced larger die, both-dice rule, bar entry, doubles, gammon and backgammon scoring all have named tests (`BackgammonRulesTest`, `BackgammonGameTest`). CPU: EASY uniform random turn, MEDIUM static evaluator, HARD 2-ply expectimax with a time budget (`BackgammonBot.kt:139-178`, test "a search with no time left still returns a complete legal turn"). Async turn with pacing, dropped on session end. Hint, undo, snapshot tamper rejection. No doubling cube (optional rule, not a defect). V.
- **FEEL 2.** Checker flight and hit pulse, haptic tick and strong hit, Reduced Motion and game speed honoured (`BackgammonScreen.kt:96-97,143-154`, flight read inside Canvas draw at `BackgammonBoardView.kt:302`). Gaps: the only sound call is `sounds.playTap()` on a move (`BackgammonScreen.kt:154`); dice roll has a haptic only (`:157-158`) and no tumble animation; win and lose have a haptic only (`:160-164`), no sound or celebration beyond the panel. V.
- **ROBUSTNESS 3.** Board animation state read in draw, bot effect keyed on `botKey` (`:138-145`), roll guard tested ("a roll is ignored while the dice are in play"), snapshot validated. `pause()` is a no-op (`BackgammonGame.kt:195-196`) but turn-based, so low. V.
- **A11Y_I18N 2.** Full ES (66 keys), colour-blind palette using Okabe-Ito (`BackgammonScreen.kt:323-324`), per-point and dice descriptions (`BackgammonBoardView.kt:547`, `BackgammonScreen.kt:506`), live region (`:392`). Gap: `fitBoard` minimum is 28 dp per cell (`BackgammonBoardView.kt:229-232`), narrow points are hard to hit; no `stateDescription` on selectable checkers (S).
- **TESTS 2.** 96 tests, a two-CPU full game and tamper tests, but no random-legal-play fuzz asserting 15 checkers per side, bar plus board plus off conservation, and snapshot round-trip at each ply. V.

Top 5 fixes
1. (S) Add dice-roll SFX (`SfxKind.WHOOSH` or tick burst) and a win and lose chime in `BackgammonScreen.kt:157-164`.
2. (M) Dice tumble animation (reuse Yahtzee's rotate-and-flip in `YahtzeeScreen.kt:536-545`), gated on Reduced Motion and game speed.
3. (S) Add a fuzz test: random legal turns for 200 games, assert checker conservation and snapshot/restore equality each ply.
4. (S) Give narrow points a 48 dp hit slop or reuse the board `fitBoard` result with a larger minimum in portrait.
5. (M) Optional doubling cube (rules plus bot equity) after the above; also a win celebration (confetti reuse from `ui/effects/ParticleBurst.kt`).

## yahtzee (13/15)

`games/yahtzee/{YahtzeeGame,YahtzeeScoring,YahtzeeAdvisor}.kt`, `ui/YahtzeeScreen.kt`.

- **GAMEPLAY 3.** Scoring tests are exhaustive per category, joker rules (forced upper box, zero in Yahtzee box earns no bonus) and the 63 bonus boundary (`YahtzeeScoringTest`). Every CPU level plays complete legal games, seeded determinism, snapshot validation including impossible card scores (`YahtzeeGame.kt:488-503`), hint, undo of holds and category. V.
- **FEEL 2.** Dice tumble animation with Reduced Motion gate (`YahtzeeScreen.kt:105,536-545`), haptics for roll, score, bonus (`:211-217`). **No sound at all**: zero `sound`/`sfx`/`playTap` hits in the whole screen file (V by grep). Flat die faces, no per-game accent beyond palette (S).
- **ROBUSTNESS 2.** `pause()/resume()` empty (`YahtzeeGame.kt:176-177`). The HARD and MEDIUM advisors (`YahtzeeAdvisor(card, 1.0, 0.5, 2).plan(...)`) run synchronously inside `botStep()` (`YahtzeeGame.kt:401-414,432-434`) called from a main-thread `LaunchedEffect` (`YahtzeeScreen.kt:150-156`); memoised, but a depth-2 EV search over 252 multisets per hold may spend tens of ms on a low-end phone (S, needs a profile). V for the call path.
- **A11Y_I18N 3.** 78 ES keys, 57 `R.string` uses, 3 literals only, colour-blind hold marks (`:576`), live regions (`:198,394,403`), `Role.Checkbox` on dice (`:601`). V.
- **TESTS 3.** 70 tests incl. seed determinism, all levels full games, four-seat table, snapshot tamper. V.

Top 5 fixes
1. (S) Add SFX: dice roll (tick burst), hold click, score ding, Yahtzee fanfare via `rememberProceduralSfx()`.
2. (S) Move `botPlan` to `withContext(Dispatchers.Default)` (as Shogi and Tank do) and apply on the main thread.
3. (S) Add a celebratory visual for Yahtzee and bonus (particle burst plus pulse), Reduced Motion gated.
4. (S) Pause hook: stop the CPU step effect while GameChrome overlays are open.
5. (M) Add a random-play fuzz test (random holds and categories for 500 games, assert totals within `[0, MAX_SCORE]` and snapshot equality).

## mastermind (13/15)

`games/mastermind/MastermindGame.kt`, `ui/MastermindScreen.kt` (1270 lines). Solo puzzle, no CPU.

- **GAMEPLAY 3.** Feedback scoring is checked against an independent re-derivation over many random trials plus hand-verified repeated-colour cases (`MastermindGameTest`); tiers 4x4, 4x6, 5x8; daily seed; hint returns a code consistent with all feedback and costs a hint, assisted solves are marked; snapshot validation including wrong-size boards (`MastermindResumeHintTest`). V.
- **FEEL 2.** Newest guess row drops in with a reveal tween (`MastermindScreen.kt:1055-1071`), haptics (11) and sounds (9), Reduced Motion honoured; no game-speed hook (0 hits, acceptable for a puzzle). Pegs are painted circles with a colour-blind shape overlay (`:975-1000`); win and lose moment is a status block (S).
- **ROBUSTNESS 3.** Timer is pause-adjusted and persisted as active time (`MastermindGame.kt:373-400`, tests for double pause, resume). Session manager pauses on backgrounding (`GameSessionManager.kt:105-106`). Dialog open (help or leave) does not pause the clock (S, minor).
- **A11Y_I18N 2.** All strings are hard-coded English: `R.string` count in the screen is 0, e.g. `helpTitle = "How to Play Mastermind"` (`:335-336`), `"Slot ${index + 1} of $total, $state"` and `"Choose this slot"` (`:911-917`), `"Add to guess"` (`:949`), colour names (`:99`). The values-es keys that exist are only Hub and coach. Good: semantics on every slot, `Role.RadioButton` on tiers, colour-blind shape marks, swatches 48 dp (`PEG_SWATCH_DP`, `:85`). Code slots can shrink to `PEG_MIN_DP = 28` (`:81`) before the row scrolls. V.
- **TESTS 3.** 38 tests across game and resume or hint. V.

Top 5 fixes
1. (M) Extract the ~36 English literals (and the semantics strings) in `MastermindScreen.kt` to `values/` and `values-es/`, with plural-safe slot text; add the ratchet test (shared finding 5).
2. (S) Raise `PEG_MIN_DP` to 40 and scroll sooner, or add a 48 dp hit slop around slots.
3. (S) Pause the clock while GameChrome overlays are open (shared finding 1).
4. (S) Announce the feedback (black and white counts) as a polite live region after each guess if not already (3 of 4 live regions exist, check the guess-result one).
5. (M) Add a win celebration (peg wave plus chime sequence) and a lose reveal animation of the secret.

## towerdefence (9/15)

`games/towerdefence/TowerDefenceGame.kt` (621), `ui/TowerDefenceScreen.kt` (1066).

- **GAMEPLAY 2.** Deterministic sim with exact-formula tests, 10 waves, 3 hand-built levels, 3 difficulty tiers, pause allowed with placement. Depth is thin: one tower type, upgrade only (no sell, no tower variety, no enemy types, no fast-forward) (`TowerDefenceGame.kt:104-123,209-230`). No `ResumableGame` (`GameHubSpecs.kt:452` `resumable = false`), so a run is lost on process death. Single-player only. V.
- **FEEL 2.** Camera shake on life loss, kill particles, victory glow, chime and thud SFX (`TowerDefenceScreen.kt:232-290`), Reduced Motion honoured, colour-blind HP bar (`:517`). Towers and enemies are simple vector shapes with a numeral; no per-tower art states. V.
- **ROBUSTNESS 2.**
  - `val state by game.state` is read in composition (`:156`) and `tick()` assigns `state.value = s.copy(...)` every frame (`TowerDefenceGame.kt:590`), so the whole screen (including 20+ zone boxes with semantics) recomposes at frame rate. Same defect as perf audit 3.2/3.6. V. The 2026 on-device pilot measured 11.13 ms average, so it is not janking today (`02-performance.md:15`), but headroom is spent.
  - Per-tick allocation: `mutableListOf` x4, `.map`, `.copy` per entity (`TowerDefenceGame.kt:488-570`). V.
  - Good: lifecycle-gated loop (`repeatOnLifecycle`, `:219-229`), window-focus auto-pause (`:190-192`), the `extraItems` trick pauses while the menu is open (`:316`), distinct `pausedByLifecycle` ownership.
- **A11Y_I18N 1.** English-only: 0 `R.string`, 12 `Text("...")`, help and difficulty copy (`:665`), `onClickLabel = "Select tower spot"` (`:559`); fixed `13.sp` tower numeral inside Canvas (`:485`); the field itself has no per-enemy semantics beyond live regions on HUD (`:744`). Zone targets are 48 dp (`ZONE_TARGET`, `:646`), colour-blind HP bar exists. V.
- **TESTS 2.** 28 tests in one file: formulas, pause semantics, path validation, win and loss. No snapshot (none exists), no UI test, no fuzz or soak test with random placements. V.

Top 5 fixes
1. (M) Localise the screen: move `Text("...")`, help, `onClickLabel`, zone descriptions to resources, EN and ES.
2. (M) Stop whole-screen recomposition: expose `state` as `State` read inside the Canvas draw lambda and `derivedStateOf` for HUD values (copy the Breakout pattern, `BreakoutScreen.kt:172-174`).
3. (L) Add depth: second tower type (slow or splash), sell for 70%, a fast-forward 2x toggle, an enemy variant (fast, armoured); extend tests with the same formulas.
4. (M) Implement `ResumableGame` (towers, enemies, projectiles, wave state, RNG) with validation and flip `resumable = true`.
5. (S) Replace fixed `13.sp` with a size derived from cell size or `LocalDensity` and text scale; add a soak test running 1000 ticks with random placements, asserting no exceptions and gold non-negative.

## tankbattle (14/15)

`games/tankbattle/{TankRules,TankBot,TankGame,TankNetMessage}.kt`, `ui/TankBattleScreen.kt`, `ui/TankBattleArt.kt`.

- **GAMEPLAY 3.** Deterministic physics (`simulationIsDeterministicAndMatchesItsRecording`), wind, craters, bouncer weapon, ammo, mutual destruction draw; CPU EASY random-with-slip, MEDIUM coarse search with slip, HARD coarse search plus top-3 refinement (`TankBot.kt:76-105`), levels asserted ordered (`cpuLevelsAreClearlyOrderedButNotPerfect`, `easyMissesMoreThanHardAtTheSameShot`); planning off the main thread (`TankGame.kt:284,444`); hint, undo, snapshot with tamper test; fuzz test for invariants and round-trip. HARD has no time cap (about 1.5k simulations; S). V.
- **FEEL 3.** Shell flight, explosion, damage popups, camera shake for big craters, win hop, bursts, haptic variants by event, sky art cached (`TankBattleScreen.kt:171-255,500-535`), game speed and Reduced Motion honoured. Per perf audit the art cache is the model for others (`02-performance.md:84`). V.
- **ROBUSTNESS 3.** Clock held as a `State` read only in the field draw (`:187-190,500`); bot and hint off-thread; pause and resume implemented (`TankGame.kt:324-328`). Not verified on device. V.
- **A11Y_I18N 2.** ES present (12 keys, 49 `R.string` uses), controls 48 dp with `stateDescription` (`:713-723`). Gaps: 0 `liveRegion` (turn, damage, wind changes are not announced; S for what TalkBack reads, V for the count), `LocalColorblindMode` never read and seat identity uses `LudoSeatColors` (`:508-511`; names exist in the HUD, S), fixed `fontSize` in six places (`:553,563,617,715,725`) that ignore the in-app Text size slider; 4 English `Text("...")` literals remain. V for counts.
- **TESTS 3.** 50 tests including fuzz, determinism, networked suite, hub spec test. V.

Top 5 fixes
1. (S) Add a polite live region announcing "Seat 2 fired, hit, 30 damage, wind 5 left" after each shot, and assertive on win.
2. (S) Replace the fixed `sp` values with `MaterialTheme.typography` styles so Text size applies.
3. (S) Colour-blind safe seat marks: draw a numeral or shape on each tank and HP bar when `LocalColorblindMode` is on.
4. (S) Time-box HARD planning (as Shogi does with `timeMs`) and add a test that it returns within a budget.
5. (S) Remove the four remaining English `Text("...")` literals.

## airhockey (7/15)

`games/airhockey/AirHockeyRustGame.kt` (the engine the screen actually uses, via UniFFI) wrapping `rust/gamesuite-sim/src/lib.rs`; reference `shared/.../AirHockeyGame.kt`; screen `ui/AirHockeyScreen.kt`.

- **GAMEPLAY 2.** Three CPU speeds (0.55, 0.9, 1.3 in `AirHockeyGame.kt:520-523`) plus aim-noise differences, pass-and-play, first-to-N, forced reset on a stalled rally. Physics is variable-dt clamped to 50 ms, no sub-stepping, so tunnelling is possible at a hitch (`02-performance.md:101`). V. No hint or undo (not applicable to the genre).
- **FEEL 3.** Particle bursts, shockwave, camera shake on the Maximum tier, 3D card tilt, 25 sound hooks and 15 haptic hooks, Reduced Motion gate (`AirHockeyScreen.kt:123-127`), motion tiers; layout with `AdaptiveTwoPane` and a rotated table for the opposite player. V by grep and reading.
- **ROBUSTNESS 1.**
  - `val state by game.state` at the top of the screen (`:128`) plus `game.tick(dt)` assigning a fresh snapshot each frame (`AirHockeyRustGame.kt:88`, `:140-188` per audit): the whole screen recomposes at 60-120 Hz and a UniFFI `RustBuffer` is marshalled per frame (`02-performance.md` 3.6). V.
  - Per-frame list allocation: `.map { it.copy(ageMs = ...) }` (`:216`). V.
  - The frame loop is `LaunchedEffect(state.matchOver)` with no `repeatOnLifecycle` and no window-focus pause (`:284-293`), unlike Breakout and Tower Defence. V.
  - The pause trick: `extraItems` composes only while the dropdown is open, so `DisposableEffect { pause(); onDispose { resume() } }` (`:373-377`) resumes the puck as soon as the menu closes. Choosing **How to play** or **Back to menu** closes the menu and opens the dialog, so the match runs behind the dialog (V by reading `GameChrome.kt:131-134,146-190`).
  - `close()` is never called on the native sim (`AirHockeyRustGame.kt` KDoc admits it). V.
- **A11Y_I18N 0.** Zero `semantics`, zero `contentDescription`, zero `liveRegion` (grep); the table is one drag Canvas; no colour-blind read; no one-switch alternative; all copy in English (`"You win! ..."`, `"Back to menu"` `:305-312`, help `:353-359`, `"Motion: "` `:409`). V.
- **TESTS 1.** Three tests (`AirHockeyGameTest.kt`, mirrored in Rust). Nothing covers scoring and match end, the three CPU levels, pass-and-play, or the Kotlin wrapper (`AirHockeyRustGame`). One androidTest rotation test only. V.

Top 5 fixes
1. (M) Move the physics loop to a shared lifecycle-gated clock (`repeatOnLifecycle(RESUMED)`, window-focus pause, `dt` reset on resume) and hold the snapshot as a `State` read in draw (shared finding 3).
2. (S) Make GameChrome expose overlay open and close so help and leave dialogs also freeze the puck (shared finding 1).
3. (M) Localise all in-game copy (about 9 literals plus help text) and add TalkBack: a live region for score and serve, a description for the table and an alternative control (tap-to-aim or a slider) for switch users.
4. (M) Add engine tests: goal scoring both ends, win at target, each CPU level's reaction (sim-level), stalled rally reset, pass-and-play; run them in Rust and Kotlin.
5. (S) Sub-step the Rust tick at 1/120 s and drop the per-frame `ageMs` list allocation (ring buffer or a draw-time age calculation).

## breakout (11/15)

`games/breakout/BreakoutGame.kt`, `ui/BreakoutScreen.kt` (862).

- **GAMEPLAY 2.** Sound physics tests (wall and brick bounce, substep, tunnelling guard, paddle steering, anti-horizontal nudge). Depth is thin: bricks are boolean on or off (`BreakoutGame.kt:181`), no power-ups, no multi-hit bricks, no level layouts; difficulty only changes paddle width and ball speed (`:161`, KDoc `:46-57`). Launch angle uses non-injectable `Random` (`:313`), so it is untestable by seed. Not resumable (`GameHubSpecs.kt:451`). V.
- **FEEL 2.** SFX and haptics for brick, wall, paddle, life, level, win and lose (`BreakoutScreen.kt:215-248`), fade-in for the re-racked ball, result fade, Reduced Motion; bricks are rounded rects with optional colour-blind stripes (`:436-459`), no per-brick art or trail or particle on brick break (V by grep: no `ParticleBurst`/`cameraShake` use). V.
- **ROBUSTNESS 3.** The best of the real-time group: HUD through `derivedStateOf` (`:172-174`), lifecycle-gated loop with a paused flag (`:200-202`), window-focus pause (`:190-191`), menu pause via `extraItems` (`:291`) with a "Tap to resume" layer, `dt` clamp and substeps. Minor: the menu pause is not undone and the leave dialog is covered only by the same latch. V.
- **A11Y_I18N 2.** English-only: 0 `R.string`, `Text("Score: $score")`, `"Level $level"`, `"Tap to resume"`, `"Resume game"` (`:601-717`). Strengths: the play field has `contentDescription`, `stateDescription` and keyboard nudge (`:373-382`), colour-blind brick stripes, live regions. No `LocalGameSpeed` (not needed). V.
- **TESTS 2.** 32 tests, no fuzz or soak (random paddle input for N frames, assert ball in bounds and brick count monotonic), no UI test. V.

Top 5 fixes
1. (M) Localise `BreakoutScreen.kt` (about 23 literals) to EN and ES.
2. (M) Add one new mechanic pair: multi-hit bricks and 3 to 4 power-ups (wide paddle, multi-ball, slow ball), with `Random` injected for tests.
3. (S) Brick-break particles and a small camera shake using `ui/effects/ParticleBurst.kt`, Reduced Motion gated.
4. (S) Inject `Random` into `BreakoutGame` and add a seeded soak test (random paddle moves, 10k ticks, invariants).
5. (M) Implement `ResumableGame` (brick mask, ball, lives, level) and flip `resumable = true`.

## edgematch (12/15)

`games/edgematch/{EdgeMatchGame,EdgeMatchLayout,EdgeMatchStatsStore}.kt`, `ui/EdgeMatchScreen.kt` (1473).

- **GAMEPLAY 3.** Puzzle is solvable by construction (generated from a solved seam grid then scrambled by rotation), asserted at the bounds, hex geometry added with hit-test tests, custom builder, daily seed, hint (never points at border or already-correct tiles), undo, restart, snapshot with validation (`EdgeMatchGameTest`, `EdgeMatchLayoutTest`, `EdgeMatchResumeUndoHintTest`). V.
- **FEEL 2.** Rotation tween scaled by Reduced Motion, success chime plus celebration haptic (`EdgeMatchScreen.kt:104,252-256`), matching-edge beads, colour-blind shape marks. No game speed hook and no per-puzzle win animation beyond the chime (S, needs a visual check); tile art is flat colour wedges. V by reading the header KDoc `:147-170`.
- **ROBUSTNESS 2.**
  - Puzzle generation (`game.startMatch(...)`) runs inline from composition effects (`EdgeMatchScreen.kt:219,290`), already listed in `02-performance.md` 5.2. At 8x8 and hex custom sizes the loop retries on identical seams, so a low-end phone could drop frames (S).
  - Only the Custom dialog pauses the clock (`:248-249`); the help and leave dialogs do not. V.
  - `LaunchedEffect(context)` runs init and start (`:204-219`). S.
- **A11Y_I18N 2.** English-only (0 `R.string`, 33 literals per the audit matrix); good semantics (21 `semantics`, live regions at `:723,784`), `Role.RadioButton` on tiers, colour-blind marks, controls 48 dp; tiles can drop to `MIN_CELL_DP = 28` (`:92`) before scroll or pan, under 48 dp. V.
- **TESTS 3.** 65 tests (generation, hex hit-testing, undo, hint, snapshot). V.

Top 5 fixes
1. (M) Localise (about 33 literals) to EN and ES.
2. (S) Wrap generation in `withContext(Dispatchers.Default)` with a lightweight "Building puzzle" state, per perf audit 5.2.
3. (S) Pause the clock while Help and Leave dialogs are open (shared finding 1).
4. (S) Add a 48 dp hit slop for tiles or set the minimum cell to 36 dp and pan below that.
5. (M) Win moment: ripple of matched beads across the board plus a particle burst, Reduced Motion gated.

---

## Shared-component findings (fix once, lifts many games)

1. **GameChrome has no overlay hook, so real-time games hack pausing through `extraItems`** (`uikit/GameChrome.kt:131-134,146-190`). The menu, help and leave dialogs are three separate `showX` flags; `extraItems` is composed only while the dropdown is open. Air Hockey resumes the puck the moment the menu closes (`AirHockeyScreen.kt:373-377`), so help and leave dialogs run with a live puck (V). Breakout and Tower Defence latch a pause that is never undone by GameChrome (safe but by accident). Timer puzzles (Mastermind, Edge Match) never pause for dialogs (S). Fix: add `onOverlayChanged: (open: Boolean) -> Unit` computed from `showMenu || showHelp || showLeaveConfirm`, and call it from a `LaunchedEffect`; delete the `extraItems` pause hacks. Effort S, lifts every real-time and timed game.
2. **`fitBoard` is pure and well tested, but `meetsMinimum` is advisory only**, so each screen invents its own fallback: Shogi 30 dp (`ShogiScreen.kt:75`), Backgammon 28 (`BackgammonBoardView.kt:229`), Mastermind 28 (`:81`), Edge Match 28 (`:92`). Nothing enforces the 48 dp rule of gate 5. Fix: add `Modifier.boardCellHitSlop(cellDp)` (expands the pointer area to 48 dp without enlarging the drawing) and a `rememberScrollableBoard(fit)` helper that applies pan and scroll when `!meetsMinimum`. Effort M.
3. **No shared real-time clock.** Each arcade game hand-rolls its `withFrameNanos` loop: Tower Defence and Breakout gate on `repeatOnLifecycle` and focus, Air Hockey does not. Perf audit 3.5 already asks for `rememberGameClock(enabled)`. Fix: one helper that handles lifecycle, window focus, a `dt` reset on resume, a clamp and optional fixed-step accumulation; also an idiom for reading a `State` only in draw (the Air Hockey and Tower Defence screens read state in composition). Effort M.
4. **Per-frame `State` copying as the engine contract.** Tower Defence and Air Hockey publish a new immutable state each tick (allocation plus whole-screen recomposition). Fix: engines expose a mutable render snapshot (arrays) behind a `version: Int` state, and the Canvas reads the arrays. Effort M per engine; start with a documented pattern and a lint or test that the screen root never reads `.value` of a tick-rate state.
5. **No guard against hard-coded English in `ui/*.kt`.** `GameChrome` takes `helpTitle`/`helpText`/`leaveTitle` as plain strings, so callers pass literals (Mastermind `:335`, Air Hockey `:353`). Audit 04 H1 recommended a ratchet test; it still does not exist (not verified in `app/src/test`). Fix: a JVM test that scans `ui/*.kt` for `Text("`, `contentDescription = "`, `onClickLabel = "`, `helpTitle = "` with a checked-in allowlist that can only shrink. Effort S, forces the five English-only screens (Mastermind, Tower Defence, Air Hockey, Breakout, Edge Match) onto the list.
6. **Accessibility defaults are opt-in per screen.** Colour-blind mode, live regions and 48 dp are re-implemented by each game; Air Hockey and Tank have none or partial. Fix: a `Modifier.gameBoardSemantics(label, state)` and `Modifier.liveStatus(text)` helper plus a `Canvas` wrapper that requires a description argument, so a real-time game cannot ship with zero semantics. Effort M.
7. **Sound layer is better than the audit said, with two systems.** `ProceduralSfx` now uses one daemon scheduler thread and swallows track errors (`audio/ProceduralSfx.kt:175-230`), which resolves audit 8.1 in part (still MODE_STATIC with a per-sound `AudioTrack` build, S). Shogi mixes `CardSounds` and `rememberProceduralSfx`, both gated on `CardSounds.soundEnabled`. Gap: Yahtzee and Backgammon barely use it. Fix: a `GameSfx` facade with semantic events (`ROLL`, `PLACE`, `CAPTURE`, `WIN`, `LOSE`) so each game maps events and none is silent. Effort S.
8. **Theme: the Midnight Club tokens exist in design but not in shared code.** Nine in-game screens use their own palettes and none reads `GameThemes.accents` (`ui/GameTheme.kt`); only `TerritoryWarsPalette.kt:16` carries Midnight Club values. Fix: a `MidnightClubTokens` object in `uikit/` and let `GameChrome` button, result panels and status chips read it plus the game accent, leaving the board palette per game. Effort M.
9. **Empty `pause()`/`resume()` on turn-based games** (Shogi `ShogiGame.kt:184`, Backgammon `:195`, Yahtzee `:176`, Air Hockey shared `AirHockeyGame.kt:251`) means CPU timers run behind dialogs and in background until the next effect restart. Fix: put a default `paused` flag in a base class or helper (`BotScheduler`) that every bot-driven game uses (`delay` with pause-awareness). Effort S.
