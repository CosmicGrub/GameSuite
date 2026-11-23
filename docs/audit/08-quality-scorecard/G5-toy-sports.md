# G5 quality scorecard: toy sports and physics games (14 games)

Scope: darts, bowling, carrom, curling, billiards, minigolf, toysoccer, toytennis, toyboxing, toybaseball, slotcars, fishing, shootinggallery, pigdice.
Branch session/wave-3, read-only. Nothing was built or run (no Gradle, adb, emulator), so every claim is from reading code. **V** = verified by reading the code path; **S** = suspected, needs a device or a profile.
Paths are relative to `app/src/main/java/com/gamesuite/` (UI = `ui/`, engines = `games/<pkg>/`) unless they start with `app/src/test`.
Rubric: 0-3 per axis (3 = meets `docs/roadmap/QUALITY-BAR.md`), total /15. Every score under 3 carries file:line evidence. The Rust sim crate (`rust/gamesuite-sim`) is not used by any of these 14 games: all physics is pure Kotlin.

## Summary

| game | gameplay | feel | robustness | a11y_i18n | tests | total /15 |
|---|---|---|---|---|---|---|
| darts | 3 | 3 | 2 | 3 | 3 | 14 |
| bowling | 3 | 3 | 2 | 2 | 3 | 13 |
| carrom | 3 | 3 | 2 | 3 | 3 | 14 |
| curling | 3 | 3 | 2 | 3 | 2 | 13 |
| billiards | 3 | 3 | 2 | 3 | 3 | 14 |
| minigolf | 3 | 3 | 2 | 3 | 3 | 14 |
| toysoccer | 3 | 3 | 2 | 3 | 3 | 14 |
| toytennis | 3 | 2 | 2 | 3 | 3 | 13 |
| toyboxing | 3 | 3 | 1 | 2 | 3 | 12 |
| toybaseball | 2 | 2 | 3 | 2 | 2 | 11 |
| slotcars | 3 | 3 | 2 | 3 | 3 | 14 |
| fishing | 3 | 3 | 2 | 3 | 3 | 14 |
| shootinggallery | 3 | 3 | 2 | 2 | 2 | 12 |
| pigdice | 3 | 2 | 3 | 3 | 3 | 14 |

Weakest five: toybaseball (11), toyboxing (12), shootinggallery (12), then bowling, curling and toytennis (13 each).

## What is strong across the group (checked, no defect)

- **Test posture is the best in the app.** 29 to 84 tests per game. Nearly all have: seeded determinism (`theSameSeedReplaysTheSameMatchExactly`, `landingIsDeterministicForTheSameSeedAndSerial`), fuzz or random-legal-play with invariants, snapshot round-trip plus rejection of garbage, forged/stale StateSync tests, and bot-vs-bot level ordering with win-rate bands (`app/src/test/java/com/gamesuite/games/*`). Darts also has `noHardCodedUserFacingTextInTheScreens`.
- **CPU levels are distinct and asserted.** Toy Tennis is no longer lopsided: profiles are 31/26/21 reaction ticks and 6.8/7.0/7.3 m/s (`games/toytennis/ToyTennisGame.kt:43-46`), and `ToyTennisGameTest.hardBeatsMediumBeatsEasyButNeverAlways` asserts hard>easy in [0.8,..], medium>easy <=0.95, hard>medium <=0.92 (comment says the levels "were once 100 percent apart"). Caveat: this is bot vs bot; no test measures a human against Easy.
- **Snapshot/guest validation is thorough** in the physics games (`CarromGame.kt:134-158`, `CurlingGame.kt:128-157`). Code-review findings 2, 11, 17, 19 are mostly closed: `lock = this` in Soccer/Tennis/SlotCars/Fishing (`ToySoccerGame.kt:175`, `ToyTennisGame.kt:114`, `SlotCarsGame.kt:105`, `FishingGame.kt:115`), StrictMath in Soccer/Tennis/SlotCars, swing cooldown before rewind (`ToyTennisGame.kt:368-372`), networked-mode guard in restore for darts/bowling/billiards/minigolf/gallery/pig.
- **Status is spoken.** Audit 04 line 62 ("no liveRegion in 21 screens") is stale for these games: every screen now routes status through `ClStatusLine` (`ui/ChineseLudoUi.kt:215-217`, `liveRegion = Polite`) and adds `ClWhyLine` for refused taps.
- **Aim-free alternatives exist** for every aim game: Darts nudge buttons (`DartsScreen.kt:128,687`), Bowling/Carrom/Curling sliders, Billiards/Golf aim-at-ball and nudge buttons, Curling auto-sweep switch (`CurlingScreen.kt:537`), Soccer/Tennis/SlotCars/Fishing screen-reader modes (`rememberClTouchExploration`).
- **EN/ES parity**: every `values/*_strings.xml` for these games has the same string count in `values-es` (checked all 26 files). Hub texts use the `hubKey(text)` lookup, proved by the `*HubSpecsTest` files.
- Multi-touch is handled by pointer id in Soccer (`ToySoccerScreen.kt:480-490`), Tennis (`ToyTennisScreen.kt:438-452`) and Darts (`DartsScreen.kt:466-492`).

## Shared-component findings

| # | finding | evidence | games | effort |
|---|---|---|---|---|
| SH1 | **GameChrome help and leave-confirm dialogs never pause a real-time game.** The `extraItems` trick (`DisposableEffect { game.pause(); onDispose { game.resume() } }`) only runs while the DropdownMenu is composed (`uikit/GameChrome.kt:164-176`). Tapping "How to play" closes the menu (`showMenu=false`), which resumes the game, then shows the dialog (`:212-218`) over a live clock. Same for the leave confirm (`:183-`). V | `ToySoccerScreen.kt:402-405`, `ToyTennisScreen.kt:369-372`, `SlotCarsScreen.kt:280-283`, `FishingScreen.kt:319` | soccer, tennis, slotcars, fishing | S: add a `paused: Boolean` effect in GameChrome (`showHelp || showLeaveConfirm || showMenu`) that calls the active module's pause/resume, delete the per-game trick |
| SH2 | **Boxing and Gallery have no pause path at all.** `ToyBoxingGame.pause()/resume()` are empty (`games/toyboxing/ToyBoxingGame.kt:294-295`) and the 35 ms tick loop runs regardless (`ToyBoxingScreen.kt:190-196`); the Gallery run clock is screen-local and only pauses on `ON_PAUSE` (`ShootingGalleryScreen.kt:312-342`), with no menu/dialog hook. V | | boxing, gallery | S each |
| SH3 | **Per-frame state objects + whole-screen recomposition in the four real-time games.** `publish()` builds a new state (and a new `List<SoccerBody>`) every frame (`ToySoccerGame.kt:555-577`; Tennis `ToyTennisGame.kt:425-431` plus `history.addLast(ts.copy())` every 120 Hz step at `:271`; SlotCars `SlotCarsGame.kt:384`; Fishing `FishingGame.kt:364`). The screens read it in the composable body (`val state by game.state`: `ToySoccerScreen.kt:176`, `ToyTennisScreen.kt:173`, `SlotCarsScreen.kt:127`, `FishingScreen.kt:154`) so the whole HUD/footer recomposes at frame rate. Read verified, cost S. | | soccer, tennis, slotcars, fishing | M: split into a `derivedStateOf` HUD state (score, phase, event) and a draw-only `State<Frame>` read inside the Canvas lambda |
| SH4 | **Frame loops are not lifecycle-gated and keep waking when idle.** `while(true) withFrameNanos` at `ToySoccerScreen.kt:209-212`, `ToyTennisScreen.kt:204-207`, `SlotCarsScreen.kt:159`, `FishingScreen.kt:185`, `MiniGolfScreen.kt:274-275` (infinite `timeSec` redraw even while only aiming), `ToyBoxingScreen.kt:114-118` (runs after the bout is over). Contrast `BreakoutScreen` `repeatOnLifecycle(RESUMED)` (audit 02 line 72). V | | 6 games | S: wrap in `repeatOnLifecycle(RESUMED)` and break when `paused || matchOver` |
| SH5 | **No static-layer caching anywhere.** `drawWithCache` count is 0 in all 14 screens and 5 art files. Gradients/paths are rebuilt per draw: `BilliardsScreen.kt:805-828` (`drawPoolBall`, 16 balls), `drawPitch` with net line loops (`ToySoccerScreen.kt:684-728`), `BowlingScreen` (8 gradients, 3 Paths), `ToyBoxingArt.kt`, `FishingArt.kt`, `ShootingGalleryArt.kt` (audit 02 line 84). Good example to copy: `TankBattleArt.kt:56-110`. V (count) / S (cost) | | all | M: shared `rememberStaticLayer(size, theme)` helper |
| SH6 | **ToySports art is one disc.** `ui/ToySoccerTennisArt.kt:34-67` draws every figure as a top-down striped disc with a crest, shared by Soccer and Tennis; Tennis adds a racket stub and ball trail (`ToyTennisScreen.kt:516-540`) but court and net are flat rects and lines (`:617-647`). It meets the "no Nintendo assets / vector" rule but is the plainest look in the group. V | | soccer, tennis | M-L: per-sport figure art (arms, racket, goalie gloves), turf texture, court surface noise |
| SH7 | **Literal `fontSize = N.sp` bypasses the type scale and creates sub-12sp text**: Boxing 8 literals (10-11sp at `ToyBoxingScreen.kt:539-543,582,732`), Baseball 7 (10-13sp at `ToyBaseballScreen.kt:480-500,662,706`), Gallery 6, Bowling 3, Soccer/Tennis/SlotCars banners. V | | boxing, baseball, gallery, bowling | S: map to `MaterialTheme.typography` with a 12sp floor |
| SH8 | **Shot/run sims run synchronously on the caller thread.** `BilliardsGame.applyShot` calls `BilliardsPhysics.simulate` (up to 10,800 steps, O(n^2) per step) on the thread that handles the tap (`games/billiards/BilliardsGame.kt:224`); same shape for Carrom/Curling/Golf/Bowling (audit 02 line 102). S | | physics board games | S-M: `withContext(Dispatchers.Default)` for human shots, as the bots already do |
| SH9 | **`restore()` has no networked-mode guard** in carrom, curling, boxing, baseball, soccer, tennis, slotcars, fishing (`CarromGame.kt:203`, `CurlingGame.kt:205`, `ToyBoxingGame.kt:1018`, `ToyBaseballGame.kt:731`, ...). `snapshot()` returns null for networked modes so it is unreachable today; darts (`DartsGame.kt:281`) shows the one-line guard. V | | 8 games | S |
| SH10 | **Non-Strict math in seeded streams**: `BowlingScatter` uses `ln`/`cos` (`BowlingPhysics.kt:290-294`) while the file header claims only `+ - * /` and `sqrt` (`:101`); Fishing `rollFish` uses `Double.pow(2.2)` (`FishingSim.kt:261`). Only matters if a guest/another device re-derives the roll, which the host-authority model avoids; so S. | | bowling, fishing | S: StrictMath |
| SH11 | **Per-game duplicates** (review finding 27): `gauss()` copies in `BilliardsBot.kt:212`, `GolfBot.kt:148`, `SoccerCpu.kt:43`, `ToyTennisGame.kt:306`, `DartsRules.kt:276`; Bowling uses a different Box-Muller. Move to one `core/Gauss.kt`. V | | 6 games | S |

## Per-game sections

### darts: 14/15 (G3 F3 R2 A3 T3)
- GAMEPLAY 3: seeded scatter stream (`theScatterIsASeededStreamSoReplayAndResumeAgree`), 501/Cricket rules tests (19), bot ordering test, flick velocity uses historical samples (`DartsScreen.kt:486-487`), adjustable "Your hand" assist.
- ROBUSTNESS 2: the sway `rememberInfiniteTransition` runs whenever Reduced Motion is off, including while a bot throws (`DartsScreen.kt:193`), so the board canvas redraws at 60 Hz idle; board rings/numbers are repainted rather than cached (SH5). No lifecycle gating of the infinite transition. V (read) / S (cost).
- A11Y 3: nudge buttons 48dp (`:687`), scoreboard chips are non-interactive 40dp (`:433`), `ClStatusLine`, color-blind board. One literal 34sp (`:540`).
- Top 5: (1) S gate sway on `canAim`; (2) M `drawWithCache` for board; (3) S replace 34sp literal; (4) M compose UI test for flick vs nudge parity (no screen test exists); (5) S default "steady hand" assist when `rememberClTouchExploration()`.

### bowling: 13/15 (G3 F3 R2 A2 T3)
- GAMEPLAY 3: official scoring, bumpers, seeded scatter, 56 tests incl. fuzz and replay.
- ROBUSTNESS 2: lane perspective painted with 8 gradients + 3 `Path()` per draw and no cache (BowlingScreen, SH5); scatter uses non-Strict `ln`/`cos` against its own header (`BowlingPhysics.kt:101,294`, S).
- A11Y 2: `LocalColorblindMode` is never read in `BowlingScreen.kt` (count 0; every other aim game reads it), so pin/ball/lane cues rely on hue (S: needs a look at the art); literal 36/13/14sp (`:501,683,687`); sliders and `ClStatusLine` are fine.
- Top 5: (1) S consume `LocalColorblindMode` (pin ids, ball pattern); (2) S typography instead of 13/14/36sp; (3) M cache lane layer; (4) S StrictMath in scatter; (5) M human roll sim off main thread (SH8).

### carrom: 14/15 (G3 F3 R2 A3 T3)
- GAMEPLAY 3: `validNet` is strict (`CarromGame.kt:134-158`: queen state, shot range, win flags, session wins); striker pulse and ghost-line hint.
- ROBUSTNESS 2: `pulse` is read in composition (`CarromScreen.kt:602`, used at `:776`) so the board recomposes every frame while aiming (audit 02 line 55); `withFrameNanos` replay loop at `:240`; board/frame gradients not cached; `restore()` has no mode guard (`:203`).
- A11Y 3: sliders, `stateDescription` (`:585`), color-blind coin marks (9 refs).
- TESTS 3: 50 tests, but no dedicated `CarromNetworkedTest`; networking is only covered by the generic adapter in `NetworkedSeatAuditTest.kt:91`.
- Top 5: (1) S read `pulse` inside the draw lambda; (2) M `drawWithCache` for board; (3) M `CarromNetworkedTest` (forged FlickRequest, stale sync); (4) S networked-mode guard in restore; (5) S human flick sim off main thread.

### curling: 13/15 (G3 F3 R2 A3 T2)
- GAMEPLAY 3: hammer, extra ends, `validNet` bounds on stones and end scores (`CurlingGame.kt:128-157`); sweeping replayed exactly (`sweepsInAHumanThrowAreReplayedExactly`); auto-sweep switch is the aim-free path.
- ROBUSTNESS 2: `pulse` read in composition (`CurlingScreen.kt:642`); `@Suppress val tickRead = liveTick` forces the controls column to recompose every frame of a slide (`:519`); two `withFrameNanos` loops (`:234,345`) not lifecycle-gated.
- TESTS 2: 36 tests (lowest of the physics board games), no networked test file, no bot test file; physics 13, rules 9. Strong fuzz present.
- A11Y 3 (note: hammer flag glyph is decorative with empty description at `:490`, the chip's merged description includes the word, so fine).
- Top 5: (1) S read `pulse` in draw; (2) M split the sliding-stone HUD so only the sweep button reads `liveTick`; (3) M `CurlingNetworkedTest`; (4) M `CurlingBotTest` (levels exist only in `hardPlaysBetterThanMediumThanEasyOnTheSameIce`); (5) S restore guard.

### billiards: 14/15 (G3 F3 R2 A3 T3)
- GAMEPLAY 3: 84 tests incl. conservation of balls, seeded racks, 30 rules tests, bot level ordering, hub specs.
- ROBUSTNESS 2: `drawPoolBall` allocates a clip `Path` and 2-3 radial brushes per ball per frame, table gradients rebuilt each frame (`BilliardsScreen.kt:805-828`, audit 02 line 83); the human shot is simulated synchronously on the tap thread (`BilliardsGame.kt:224`, `MAX_STEPS = 240*45` at `BilliardsPhysics.kt:106`).
- A11Y 3: aim-at-ball buttons, nudge, spin actions, liveRegion (`BilliardsScreen.kt:201`).
- Top 5: (1) S pre-render 16 ball bitmaps/brushes with `remember`; (2) M cache table layer; (3) S/M sim off main thread; (4) S `BilliardsBotTest` has 5 tests, add a think-time cap test; (5) S shared `Gauss`.

### minigolf: 14/15 (G3 F3 R2 A3 T3)
- GAMEPLAY 3: `everyHoleIsSolvableByTheHardComputer`, `theSameStrokeReplaysBitIdentically`, level ordering over the whole course; about 10 holes on a single course (`GolfCourse.kt:77`).
- ROBUSTNESS 2: `timeSec` is written every frame by an unconditional loop (`MiniGolfScreen.kt:274-275`), so the canvas redraws at 60 Hz even in AIM; 5 `Path()` and 6 gradients per draw; sim on caller thread (SH8).
- Top 5: (1) S stop the `timeSec` loop unless the hole has animated hazards (or tick at 30 Hz); (2) M cache hole static layer; (3) S off-main human putt sim; (4) S add a second course (content depth); (5) S shared `Gauss`.

### toysoccer: 14/15 (G3 F3 R2 A3 T3)
- GAMEPLAY 3: fixed 120 Hz step with `acc` clamp (`ToySoccerGame.kt:316-328`), per-figure flick ownership by `controllerSeat`, goalie logic tested, level win-rate tests, sudden death.
- FEEL 3: goal banner (72sp), impact shake, wood-framed pitch, crest + pattern per team (colour-blind); art is shared discs (SH6) but reads well.
- ROBUSTNESS 2: SH1, SH3, SH4; `drawPitch` rebuilds net lines each frame (`ToySoccerScreen.kt:709-717`). Pause correctness inside the game is good (`paused` + `lastMs=-1`, `ToySoccerGame.kt:256-259`).
- Top 5: (1) S GameChrome-level pause (SH1); (2) M HUD/draw state split (SH3); (3) M cache pitch layer; (4) S lifecycle-gate frame loop; (5) M richer figure art.

### toytennis: 13/15 (G3 F2 R2 A3 T3)
- GAMEPLAY 3: levels fixed and band-tested, rewind with cooldown, TennisScore tests (13), sim tests (19), replay test.
- FEEL 2: figures are the shared disc (`ToySoccerTennisArt.kt:34`); racket is a line + circle (`ToyTennisScreen.kt:516-527`); court is flat rect/lines (`:617-630`); the net is the nicest part. Swing animation, trail, haptics are present.
- ROBUSTNESS 2: SH1/SH3/SH4; `history.addLast(ts.copy())` at 120 Hz (`ToyTennisGame.kt:271`); `starts` map entries are only removed on `!pressed` (`ToyTennisScreen.kt:439,455`), so a cancelled pointer leaves a stale swipe (S).
- Top 5: (1) S GameChrome pause; (2) M figure/racket art; (3) M ring-buffer for history; (4) S remove `starts` entries on cancel; (5) M HUD state split.

### toyboxing: 12/15 (G3 F3 R1 A2 T3)
- GAMEPLAY 3: stamina anti-mash, tells, knockdown count, `hardStillLosesToAPlayerWhoReadsTheTell`.
- ROBUSTNESS 1: **no pause**. `pause()` and `resume()` are empty (`ToyBoxingGame.kt:294-295`), the tick loop is a plain `delay(35)` (`ToyBoxingScreen.kt:190-196`), and `GameChrome` does not pause for help/leave dialogs. The CPU keeps punching while the player reads "How to play" or the app is backgrounded (the sim drops only the excess beyond `MAX_CATCHUP_STEPS`, `ToyBoxingGame.kt:466-473`). V. Also `rememberFrameMs` loops forever, even after the bout (`ToyBoxingScreen.kt:114-118`).
- A11Y 2: the real-time pads are the only default mode; assist (turn-based) is only enabled by launch option (`ToyBoxingScreen.kt:163`), not auto-offered for TalkBack (no `rememberClTouchExploration` in this file); 8 literal sp, several 10-11sp (`:539-543,582,732`). Pads are 88dp tall and labelled (good, `:693`).
- TESTS 3: 56 tests, but no `ToyBoxingGameTest` (bout flow is tested through rules and net files).
- Top 5: (1) S implement `pause()/resume()` (freeze `lastSim`, CPU timer) and hook GameChrome (SH1/SH2); (2) S auto-enable assist mode when touch exploration is on; (3) S typography instead of 10-11sp; (4) S stop `rememberFrameMs` when over or paused; (5) M a Game-level test for pause, resume and snapshot.

### toybaseball: 11/15 (G2 F2 R3 A2 T2)
- GAMEPLAY 2: the design is a simultaneous-guess model (`BaseballRules.kt:1-60`: pitch type, 5x5 spot, timing guess, power or contact). It is deterministic and bot-habit aware (`pitcherHabits`, `ToyBaseballGame.kt:557`), but the evidence is thin: no Game-level test (only `ToyBaseballRulesTest` 22 and `ToyBaseballNetworkedTest` 7), no hint/resume/pass-and-play-hand-over test, levels are tested only by `harderComputersWinMoreOverManySeededGames`. There is no skill-based input (everything is a choice), which is a design limit against the "feels like a real game" bar.
- FEEL 2: no art file; the screen is cards plus a 52dp cell grid (`ToyBaseballScreen.kt:690-710`), 1 `animateTo`/tween total. There is no ball flight or field view beyond small canvas glyphs (`:672-682`). Sound, haptics and a burst exist.
- ROBUSTNESS 3: turn-based, no clock, pause no-op is correct, snapshot validation is strong (`ToyBaseballGame.kt:731-740`).
- A11Y 2: grid cells are labelled buttons with `selected` (`:699-702`, good) but scoreboard and cell text is 10-13sp literals (`:480-500,662,706`), and `LocalColorblindMode` use is limited (2 refs).
- TESTS 2: 29 tests (lowest), no screen or Game test.
- Top 5: (1) M add `ToyBaseballGameTest` (hint, resume, hot-seat hand-over, forced game end); (2) M field view with animated ball flight, runner moves and a crowd cue for hits; (3) S typography instead of 10-13sp; (4) M a timing-based batter mode (tap on the pitch) as an optional action layer, keeping the guess mode as the aim-free path; (5) S a CPU level test against a scripted human.

### slotcars: 14/15 (G3 F3 R2 A3 T3)
- GAMEPLAY 3: 3 tracks, seeded race + input replay tests, "hard is not perfect" test, de-slot rules; StrictMath in the track (`SlotCarsTrack.kt:76-140`).
- ROBUSTNESS 2: SH1, SH3, SH4 (`SlotCarsScreen.kt:159`, `SlotCarsGame.kt:384`). Pause inside the game is correct (`SlotCarsGame.kt:178-179`).
- A11Y 3: pads become on/off toggles with screen reader or step assist, roof pattern and number identify cars.
- Top 5: (1) S GameChrome pause; (2) M HUD/draw split; (3) S lifecycle-gate loop; (4) M cache track layer (`SlotCarsArt.kt`); (5) S add a fourth track.

### fishing: 14/15 (G3 F3 R2 A3 T3)
- GAMEPLAY 3: 3 lakes, species tables, replay tests, hooked state not leaked.
- ROBUSTNESS 2: SH1, SH3, SH4 (`FishingScreen.kt:185`); `drift` infinite transition declared inside `Scene` (`:380-383`), reading it outside a draw lambda would recompose the scene each frame (S); static sky/ridge art rebuilt per frame (`FishingArt.kt`, audit 02 line 84); `pow` non-Strict (`FishingSim.kt:261`, S).
- A11Y 3: hold becomes a switch with a screen reader (`FishingScreen.kt:136`); aim arrows 48dp (`:504`).
- Top 5: (1) S GameChrome pause; (2) M cache lake backdrop; (3) S `drift` read in draw; (4) M HUD/draw split; (5) S StrictMath for `pow`.

### shootinggallery: 12/15 (G3 F3 R2 A2 T2)
- GAMEPLAY 3: log-based scoring with `theLiveRunAndTheReplayAgreeOnAnyLog`, deterministic bot logs, seed shared by host. Known inherent: logs can be forged to a perfect run (review finding 30, `GalleryRules.kt:136-154`).
- ROBUSTNESS 2: the HUD recompose bug is fixed (the `seconds` state is read inside the draw lambda, `ShootingGalleryScreen.kt:200-203,702`), but the range clock is screen-local (`:312-337`) and pauses only on `ON_PAUSE` (`:339-343`); opening the menu or help keeps the clock running (SH2). A run in progress is not part of the snapshot, so process death during a run loses it (S). dt is clamped to 100 ms per frame (`:319`), so a long frame stretches the run (fair, but is not time-accurate).
- A11Y 2: the only aim-free path, "shoot nearest", is shown only with touch exploration or the relaxed mode (`:404,605`), so a sighted player with motor limits has no access unless they pick relaxed; fixed sp at `:128,541-599`.
- TESTS 2: 33 tests, no networked test, no screen test; `SixBallPigHubSpecsTest` lives in this folder but is not Gallery.
- Top 5: (1) S pause the clock on menu/dialog (SH1/SH2); (2) S always offer shoot-nearest as an option in Settings; (3) M carry the range clock and shot feed in the snapshot; (4) S min human reaction floor in `validLog` (finding 30); (5) M `GalleryNetworkedTest`.

### pigdice: 14/15 (G3 F2 R3 A3 T3)
- GAMEPLAY 3: 6 variants, Hog dice count, optimal-policy test, snapshot round-trip at every step.
- FEEL 2: `PigDiceArt.kt` is 200 lines of flat vector dice with no gradients (count 0); animation is a tumble and shake with ticks, good audio (15 sfx calls) but no material look compared with the premium targets.
- ROBUSTNESS 3: no frame loops, no heavy canvas; pause no-op is correct.
- A11Y 3: +/- dice buttons are 48dp (`PigScreen.kt:458,468`), Okabe-Ito seats plus number glyphs.
- Top 5: (1) M give the dice a moulded material (highlight, pip depth, felt tray); (2) S a score-race progress arc per seat for glanceability; (3) S UI test for `PigScreen` (only `PigDieGeometryTest` exists); (4) S use `rememberClTouchExploration` to enlarge Roll/Hold; (5) S shared `Gauss`.

## Method notes and limits
- Counted `@Test` per game with `grep -c`; counts are ranges of coverage, not mutation scores. No test was run.
- "Idle animation cost" claims (darts sway, minigolf `timeSec`, boxing frame loop) are read from loops that run unconditionally; actual jank needs a gfxinfo capture (audit 02 section 9).
- Scores for FEEL cannot be confirmed without screenshots against `docs/design/canvas/*.dc.html`; `Main.dc.html` is a 7.8 KB shell with no sport-specific frames, so look is judged against the QUALITY-BAR "consistent material look, not flat debug shapes" line only.
