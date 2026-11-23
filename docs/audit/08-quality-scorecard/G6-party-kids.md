# G6 Quality scorecard: party and kids games (14 items)

Read-only audit of branch `session/wave-3` (2026-11-21). Nothing was built or run: no Gradle, adb or emulator.
Method: read `docs/roadmap/QUALITY-BAR.md`, the five new-game audits in `docs/audit/06-new-games/`, audits 01 and 04,
the design canvas (`TableParty`, `KidsGame`, `Main`, `NG*` storyboards), then each game's package, screen, hub specs, strings and tests.
Tags: **[V]** verified by reading code, **[S]** suspected (depends on runtime behaviour I could not exercise).
Paths are relative to `app/src/main/java/com/gamesuite/` unless they start with `app/src/test`.
Score 0-3 per axis (3 = meets the Quality Bar, 0 = absent or broken). Chutes Race is the id of the `slideclimb` package.

## Summary table

| game | gameplay | feel | robustness | a11y_i18n | tests | total /15 |
|---|---|---|---|---|---|---|
| taprace | 2 | 2 | 2 | 2 | 2 | 10 |
| tugofwar | 2 | 2 | 2 | 2 | 2 | 10 |
| reactionduel | 2 | 2 | 1 | 2 | 2 | 9 |
| whackamole | 2 | 2 | 1 | 2 | 2 | 9 |
| lighttrail | 2 | 2 | 2 | 2 | 2 | 10 |
| sumopush | 2 | 2 | 2 | 2 | 2 | 10 |
| paddleduel | 1 | 2 | 1 | 2 | 2 | 8 |
| rpsshowdown | 2 | 2 | 2 | 2 | 2 | 10 |
| chutesrace | 3 | 2 | 3 | 2 | 3 | 13 |
| drawguess | 3 | 1 | 2 | 2 | 3 | 11 |
| echopads | 3 | 2 | 2 | 3 | 3 | 13 |
| actitout | 3 | 2 | 2 | 2 | 3 | 12 |
| bridgebuilders | 3 | 2 | 3 | 2 | 3 | 13 |
| partytoolkit | 2 | 1 | 2 | 1 | 2 | 8 |

Kids suitability and parent value (0-3, not part of the total):

| game | kids | parent value | one-line reason |
|---|---|---|---|
| taprace | 3 | 1 | no reading, min age 4; no "what it teaches", no cooperative mode |
| tugofwar | 3 | 2 | teams / lone-vs-team layouts give a cooperative frame |
| reactionduel | 2 | 1 | a false start loses the round, which is harsh for age 5; palm = false start |
| whackamole | 3 | 1 | reading-free, 60-120 s rounds; no per-seat help |
| lighttrail | 2 | 1 | min age 6, fast steering; Relaxed + one-switch exist |
| sumopush | 3 | 1 | cartoon pushing, Relaxed rope grip |
| paddleduel | 2 | 1 | Easy CPU unbeatable-feeling for a small child, no per-seat help |
| rpsshowdown | 3 | 2 | pattern and probability talking points, but HARD CPU reads a child's habits |
| chutesrace | 3 | 3 | luck-based, handicap rerolls, counting; the best kid fit |
| drawguess | 2 | 3 | needs reading (typed guess) but teaches words and drawing; secret word handled |
| echopads | 3 | 3 | memory, forgiving mode, sound-off; solo / turns / duel |
| actitout | 3 | 3 | 61 pictogram-only difficulty-1 cards, little-kids bonus |
| bridgebuilders | 3 | 3 | the only fully cooperative game; helper robot for a lone builder |
| partytoolkit | 1 | 2 | utility belt, English-only legacy tools, no kid framing |

Nothing here has a "what it teaches" tag, a skill label or a parent-facing summary: `GameAudienceTable.kt` carries only audience and min age
(`core/GameAudienceTable.kt:17-36`) and `GameHubSpec` has no teaches field (grep of `core/GameHubSpecs.kt`, no hit). This is a catalogue gap for parent value on every game.

## What the five newest audits left open (status check, not repeated findings)

Fixed since the audits (verified by reading): Act It Out F1 keyed rows (`ui/ActItOutScreen.kt:443,458,482`), F2 expiry-frame bump
(`games/actitout/ActItOutGame.kt:141,149`), F4 save discard on play again (`:116-120`), F6 string keep file (`res/raw/act_it_out_keep.xml`), F7 pause converts a turn to the curtain
(`games/actitout/ActItOutEngine.kt:196-204`); Echo Pads F1 collision-free `Sig` (`games/echopads/EchoPadsGame.kt:177-179`), F2 `restartShowing` (`:141`), F6 `warmUp` + low-latency track
(`games/echopads/EchoPadsTones.kt:55,104`), F5 cancellation handling (`ui/EchoPadsScreen.kt:264-266`), F8 frame loop stops when idle (`:202-211`); Bridge Builders F1 pause on dialogs and focus loss
(`ui/BridgeBuildersScreen.kt:115-156`), F4 reusable `Path`s (`:308-309`), F6 roster guard (`games/bridgebuilders/BridgeBuildersGame.kt:70,160`), F5 hub lanes `visibleWhen` (`games/bridgebuilders/BridgeBuildersHubSpecs.kt:51`);
Draw Guess F4 palm rejection (`ui/DrawGuessArt.kt:238-253,273`), F6 matching rules (`games/drawguess/DrawGuessWords.kt:293-332`), F7 word shown only while held or for 5 s
(`ui/DrawGuessScreen.kt:276`), F9 stroke closed on pause (`games/drawguess/DrawGuessGame.kt:291-296`), F2 board version read in draw scope (`ui/DrawGuessScreen.kt:307-310`); Chutes Race F1/F2/F3 `shownState`, `busy`, pulse as a `State`
(`ui/SlideClimbScreen.kt:161-199,298-303`), F13 pause (`games/slideclimb/SlideClimbGame.kt:239`).

Still open: Echo Pads F3 duel halves are still stacked at full width in landscape (`games/partypack/ReactionWhackZones.kt:52-60` via `ui/EchoPadsScreen.kt:323`; a `compact` flag softens it, the 72 dp target is not guaranteed [S]);
Echo Pads F9 yellow pad on light theme contrast [S, not re-checked]; Act It Out has no `FLAG_SECURE` / Recents protection (grep of `app/src/main`: no hit), so the card can show in Recents [V];
Draw Guess F5 (landscape phone canvas is `fillMaxWidth().aspectRatio(4/3)`, `ui/DrawGuessArt.kt:244-246`; a `wide` branch exists at `ui/DrawGuessScreen.kt:431`, phone landscape still [S]).

## Per-game sections

### 1. taprace (Tap Race) total 10
Files: `games/taprace/TapRaceGame.kt` (675), `TapRaceRules.kt`, `ui/TapRaceScreen.kt`, shared `ui/PartyTapTugArt.kt`, `games/partypack/TapTug*.kt`.
- GAMEPLAY 2. Three variants (sprint, rhythm, brake), 1/3/5 rounds, Relaxed, Hold-to-tap, deterministic 25 ms step with catch-up cap and a gap drop (`TapRaceGame.kt:293-304`), three CPU levels (`TapRaceRules.kt:121-123`), tally and tie logic tested. Below 3: no per-seat handicap, only a global Relaxed (`PartyTapTugHubSpecs.kt:44-47`), so a 5-year-old against a parent cannot be evened out; restore is round-boundary only, a half-played round restarts (`TapRaceGame.kt:102-103,648-669`); no Expert / Kid level.
- FEEL 2. Haptics, sound, burst, Reduced Motion and game speed all wired (`TapRaceScreen.kt:146-171`), far lanes rotated. Below 3: finish is the generic Material `ClFinishedPanel` (`ui/ChineseLudoUi.kt:251-283`), upright for one side only, no score card or sticker moment as in `NGEchoPads`/`NGActItOut`; the panel is not mirrored for the far seats [V].
- ROBUSTNESS 2. Palm rejection yes (`PartyTapTugArt.kt:170-210,281-290`). Below 3: `pause()` and `resume()` are empty (`TapRaceGame.kt:217-218`); the 25 ms `delay` loop keeps running in the background (`TapRaceScreen.kt:128-133`), only the gap-drop saves the race [V]; help, leave and the first-run coach do not pause (`GameChrome.kt:94-260`, `ChineseLudoUi.kt:186-205`), so the countdown runs under the card [V]; `val s = state ?: return` composes nothing for the first frame (`TapRaceScreen.kt:138`), so no GameChrome or back handling and a blank frame [V]; a new `TapRaceState` is committed every tick (`TapRaceGame.kt:286-291`) [V].
- A11Y_I18N 2. One tap action per zone for TalkBack and switch (`PartyTapTugArt.kt:300-310`), Relaxed and Hold options, ES parity (42/42 strings, hub 40/40). Below 3: seat colour set `TapTugSeatColors` colour-blindness not verified [S]; no TalkBack text for the traffic-light state beyond the status line [S].
- TESTS 2. 38 tests in `TapRaceGameTest.kt` plus 55 shared TapTug tests (pointer tracker, zone layout, tally). Below 3: no fuzz of random taps, no snapshot-corruption cases, no UI test.
- Kids 3 / Parent 1. Top 5 fixes:
  1. S: init the game synchronously (`remember(context) { game.init(ctx) }`) and draw GameChrome with a coloured box while `state == null`; removes the blank first frame.
  2. M: pause on help / leave / coach: add `onDialogOpen: (Boolean) -> Unit` to `GameChrome`, call `game.pause()`, and make `pause()` freeze `lastSim`.
  3. M: a per-seat "bigger target" handicap choice (tap target or distance to win), as Bridge Builders does (`BridgeBuildersHubSpecs.kt:47-54`).
  4. M: replace `ClFinishedPanel` with a shared party score card (stars, rounds, sticker slot, mirrored for the far side).
  5. S: stop the `delay(25)` loop when the window is stopped (`repeatOnLifecycle(STARTED)`).

### 2. tugofwar (Tug of War) total 10
Files: `games/tugofwar/TugOfWarGame.kt` (631), `TugRules.kt`, `ui/TugOfWarScreen.kt`.
- GAMEPLAY 2. Layouts teams, lone-vs-team, free-for-all (`PartyTapTugHubSpecs.kt:78-84`), bot profiles per level (`TugRules.kt:136-138`), tally tested. Below 3: no per-seat handicap beyond a team layout; Easy has `0.0` mistakes and distraction (`TugRules.kt:136`), so Easy is a steady puller, not "human-feeling" [V]; round-boundary resume only.
- FEEL 2. Same toolkit as Tap Race (`TugOfWarScreen.kt:388`). Below 3: no score card (`ClFinishedPanel` at `TugOfWarScreen.kt` finish), star row is a literal "★" (`:411`).
- ROBUSTNESS 2. Same as Tap Race: empty pause (`TugOfWarGame.kt:235-236`), blank first frame (`TugOfWarScreen.kt:137`), 25 ms loop (`:131`), coach/help do not pause; palm rejection yes.
- A11Y_I18N 2. Per-zone actions, Relaxed/Hold, ES 28/28. Below 3: the "★".repeat string is not a localisable count and is not described for TalkBack (`:411`) [V].
- TESTS 2. 37 tests, no fuzz or corrupt-snapshot suite [V by listing].
- Kids 3 / Parent 2 (team framing). Top 5 fixes:
  1. S: same synchronous-init fix as Tap Race.
  2. M: shared pause-on-dialog hook (see shared findings).
  3. S: give Easy a real stumble (`TugBotProfile` third and fifth fields > 0) so a child can win by steady tapping.
  4. M: shared score card.
  5. S: replace "★" text with a drawn star row plus a spoken count.

### 3. reactionduel (Reaction Duel) total 9
Files: `games/reactionduel/*` (871), `ui/ReactionDuelScreen.kt` (626), `ui/ReactionWhackStage.kt`, `games/partypack/ReactionWhackZones.kt`.
- GAMEPLAY 2. Classic and echo variants, best-of 3/5/7, reaction measured from touch time not event time (`ReactionWhackStage.kt:73`, a fair-seat design), CPU profile by level (`ReactionDuelRules.kt:115-117`). Below 3: no per-seat handicap or head-start for a child; Easy mean 520 ms is still faster than most 5-year-olds [S]; false start loses the round (harsh, `ReactionDuelRules`), no "no-penalty" mode other than Relaxed.
- FEEL 2. GO pulse, wobble on a foul, confetti, three sound cues, haptics (`ReactionDuelScreen.kt:222-263`). Below 3: generic finish panel; no score card.
- ROBUSTNESS 1. Known blank first frame confirmed: the engine is built in a `LaunchedEffect` after first composition (`ReactionDuelScreen.kt:164-176`) and the `engine == null` branch draws an empty box (`:194-201`) [V]. `pause()` empty (`ReactionDuelGame.kt:122-123`) while the engine runs on the absolute clock (`ReactionDuelEngine.kt:279-294`), so backgrounding or opening help mid-round lets the deadline fire [V]. No palm or contact-size rejection in `RwTouchSurface` (`ReactionWhackStage.kt:50-82`): a resting palm is a "press", i.e. a false start [V]. Coach card opens over the countdown.
- A11Y_I18N 2. Shapes and words never colour alone (header comment, `:106-110`), symbol descriptions (`:123-125`), ES 52/52. Below 3: touch-exploration path for the zones not verified [S]; no one-switch beyond `oneSwitch` flag exercised in a UI test.
- TESTS 2. 58 tests (engine and game) + 22 zone tests. No UI test, no palm test, no pause test (pause is empty).
- Kids 2 / Parent 1. Top 5 fixes:
  1. S: build the engine in `remember` (or `produceState` with an initial frame) so frame 1 shows the real zones.
  2. M: implement `pause()/resume()` by shifting engine time (the Echo Pads `shiftTime` pattern).
  3. M: feed `ContactSizes` into `RwTouchSurface` and ignore contacts over 22 mm (shared with Whack).
  4. S: "Friendly" option: a false start only repeats the signal, no point lost.
  5. M: handicap: a head-start of N ms per seat.

### 4. whackamole (Whack-a-Mole) total 9
Files: `games/whackamole/*` (569), `ui/WhackAMoleScreen.kt` (475), `ui/WhackAMoleArt.kt`.
- GAMEPLAY 2. 60/90/120 s, 3 or 4 holes per zone, one-switch sweep cursor, scoring by `act` log, CPU with notice and mistake rates (`WhackRules.kt:102-104`), grid shrinks when it cannot fit (`WhackAMoleScreen.kt:240-253`). Below 3: no per-seat handicap; seats share one act list so fairness is by design but a child cannot be helped [V].
- FEEL 2. Popups, burst, timer bar by shape, Reduced Motion and speed honoured (`:157-163`). Below 3: no score card; the `WmPopup` score text colours are literals (`:462`).
- ROBUSTNESS 1. `pause()` empty (`WhackAMoleGame.kt:114-115`) while `WhackEngine.tick(now)` plays out absolute-time acts (`WhackEngine.kt:131-144`), so a 90 s round continues behind help, coach or the home button [V]. No palm rejection (`ReactionWhackStage.kt`). The engine is created inside layout after measuring (`WhackAMoleScreen.kt:253`), a frame with chrome but no board [V].
- A11Y_I18N 2. Zone semantics (9 hits), one-switch, Relaxed; ES 24/24. Below 3: "holes" labels per hole not checked [S].
- TESTS 2. 37 tests, none on pause, palm or UI.
- Kids 3 / Parent 1. Top 5 fixes: 1. M pause by shifting `playStartAt` and `nextAct`; 2. M palm rejection; 3. S coach card must not auto-open once the round has started (see shared); 4. M per-seat bigger holes handicap; 5. M shared score card.

### 5. lighttrail (Light-Trail Duel) total 10
Files: `games/lighttrail/*` (866), `ui/LightTrailScreen.kt` (493), `games/partypack/TrailSumo*.kt`.
- GAMEPLAY 2. Deterministic grid step, power-ups, Relaxed (slower, wider, auto-avoid with a cooldown, `LightTrailRules.kt:30-37`), arena sizes, three CPU levels with a flood fill / Voronoi hard bot (`LightTrailCpu.kt:3-14`), draws replay up to `rounds + 2`. Below 3: no per-seat handicap; Easy "wanders" but a 6-year-old still has to react at 135 ms per cell [V].
- FEEL 2. Countdown 96 sp, crash FX that honour Reduced Motion (`LightTrailScreen.kt:219-227`), confetti at match win. Below 3: no score card; the coach card auto-opens under the countdown (`:242`).
- ROBUSTNESS 2. A real Pause button and overlay (`:354-389`), `pause()` sets `paused` (`LightTrailGame.kt:179`), router cancelled on pause (`:182-184`). Below 3: no palm rejection in `TsPointerRouter` (grep: no contact handling); focus loss and dialogs rely on `MainActivity.onPause` only [S]; `resume()` empty so unpausing needs the overlay button [V]; first-frame behaviour [S].
- A11Y_I18N 2. One-switch control (`LtTurn.SWITCH`), pads for left/right. Below 3: only 3 semantics nodes in the screen (`LightTrailScreen.kt`), the arena itself has no description of rider positions [V]; the 96 sp countdown is fixed [fine].
- TESTS 2. 35 tests, CPU vs wall behaviour covered, no fuzz of random commands [S], no UI test.
- Kids 2 (min age 6) / Parent 1. Top 5 fixes: 1. M palm rejection in `TsPointerRouter`; 2. S coach card should pause (pass `coachShown` like Slide Climb, `SlideClimbScreen.kt:430`); 3. M "Training" mode, rider speed per seat; 4. M TalkBack summary of rider heading and distance to wall; 5. M score card.

### 6. sumopush (Sumo Push) total 10
Files: `games/sumopush/*` (876), `ui/SumoPushScreen.kt` (558).
- GAMEPLAY 2. Ring shrinks, charge and dash, rope grip when Relaxed (`PartyTrailSumoHubSpecs.kt:99`), CPU levels with wobble and think time per level (`SumoPushCpu.kt:12-14,25-27,93`). Below 3: no per-seat handicap (a heavier or lighter sumo would be the natural one); `!!` on `maxByOrNull` (`SumoPushCpu.kt:80`) is guarded by non-empty rivals [V].
- FEEL 2. Same as Light Trail. Below 3: no score card, coach over the countdown (`SumoPushScreen.kt:252`).
- ROBUSTNESS 2. Pause button (`:353-377`), pause in game (`SumoPushGame.kt:182`). Below 3: no palm rejection, `resume()` empty (`:183`).
- A11Y_I18N 2. Stick or one-button control, ES 20/20 (+23 shared, +31 hub). Below 3: 9 semantics nodes but the ring state is visual only [S].
- TESTS 2. 37 tests, no UI test.
- Kids 3 / Parent 1. Top 5 fixes: 1. M palm rejection; 2. S coach pause; 3. M per-seat weight handicap; 4. S `resume()` should clear `paused` when focus returns and the overlay is not shown; 5. M score card.

### 7. paddleduel (Paddle Duel) total 8
Files: `games/partypack/PaddleDuel*.kt` (1,081), `ui/PaddleDuelScreen.kt` (571), `ui/PaddleRpsPartyUi.kt`.
- GAMEPLAY 1. Easy is still too hard (docs/audit/01:199, 04:187, UNBUILT-ITEMS C) and the profile was not retuned: `PdBotProfiles.easy = (think 0.40, error 0.12, speed 1.0, distract 0.20, lapse 0.05, no prediction)` (`PaddleDuelEngine.kt:119`) against a ball that speeds up by 7% per rally to 3.2x (`:73-74`) [V]; the only difficulty test is bot vs bot (`app/src/test/.../PaddleDuelEngineTest.kt:257-295`), so "beatable by a slow human" is unproven [V]. Also no per-seat handicap (paddle length for one seat). Rules, lives/points formats, power-ups and fuzz are otherwise tested.
- FEEL 2. Spin, rings, power-ups, bands rotated per seat, haptics. Below 3: no score card; seat band uses `if (se.isHorizontal && facing) quarter else if (facing) quarter else 0` (both branches equal, `PaddleDuelScreen.kt:359`), a smell showing rotation logic was not simplified.
- ROBUSTNESS 1. Known blank first frame confirmed: `if (context == null || engine == null) return` right after the init effect (`PaddleDuelScreen.kt:155-174`) [V]. Pause is real (`PaddleDuelGame.kt:111-113`) and a stalled frame cannot teleport the ball (test `aStalledFrameCannotTeleportTheBall`), but no palm rejection in `PdPointerRouter` [V] and help/coach do not pause.
- A11Y_I18N 2. Arrow buttons that act as clicks for TalkBack (`PaddleRpsPartyUi.kt:68-110`), one-switch, Relaxed, ES 26/26. Below 3: playing needs a thumb on a moving paddle, so no "watch only" for low-vision users [S].
- TESTS 2. 45 tests across engine, game, input; replay determinism and fuzz exist. Missing: Easy vs a modelled slow human; Compose rotation (no test for `ppRotateLayout`).
- Kids 2 / Parent 1. Top 5 fixes:
  1. S: retune Easy (speed 0.7, think 0.55, lapse 0.20, ball cap 1.8x on Easy) and add a test that a 400 ms-reaction model wins at least 40% of rallies.
  2. S: fix first-frame blank (same as Reaction Duel).
  3. M: palm rejection in `PdPointerRouter`.
  4. M: per-seat paddle-length handicap.
  5. S: coach and help must pause (`game.pause()`).

### 8. rpsshowdown (RPS Showdown) total 10
Files: `games/partypack/Rps*.kt` (731), `ui/RpsShowdownScreen.kt` (562).
- GAMEPLAY 2. Classic and extended moves, best-of formats, pick timer, 2 to 4 players, CPU predictor by level (`RpsCpu.kt:85-101`), engine tests (24). Below 3: HARD "reads the whole pattern history of every opponent" is unsuitable for a child opponent (win-stay/lose-shift is how small children play), and there is no "go easy" signal beyond EASY near random [V].
- FEEL 2. Count-down, reveal and rotated zones (`RpsShowdownScreen.kt:277-343`). Below 3: no score card.
- ROBUSTNESS 2. Pause exists (`RpsGame.kt:113-114`). Below 3: first composition `return`s before any UI (`RpsShowdownScreen.kt:225`) [V]; the hand-over is a one-screen reveal so a neighbour can read a pick on the shared screen [S].
- A11Y_I18N 2. Per-zone descriptions (8 hits), ES 57/57. Below 3: pick buttons rely on emoji-like glyphs with a description [S].
- TESTS 2. 31 tests (engine 24, cpu 7). No fuzz [V by listing].
- Kids 3 / Parent 2. Top 5 fixes: 1. S first-frame fix; 2. S cap HARD memory for kid profiles; 3. M cover/peek protection: "pass and tap" cover while a seat picks; 4. M score card; 5. S coach pause.

### 9. chutesrace (Slide and Climb) total 13
Files: `games/slideclimb/*`, `ui/SlideClimbScreen.kt` (+Art), hub specs, 125 tests.
- GAMEPLAY 3. Pure rules, per-seat handicap (`SlideClimbHubSpecs.kt`), strict snapshot validation that re-derives the last move, 125 tests incl. layout and hub specs.
- FEEL 2. Dice roll, token move, result held until the animation ends (F2 fixed), designed board. Below 3: the finish is `ClFinishedPanel` (`SlideClimbScreen.kt`), not the mockup score card (`NGSlideClimb.dc.html`); no sticker moment [V].
- ROBUSTNESS 3. F1-F3 fixed, pause real (`SlideClimbGame.kt:239`), coach passes `coachShown` (`SlideClimbScreen.kt:430`).
- A11Y_I18N 2. Dice and round-button semantics were audited (F5-F7), status live region, ES 44/44. Below 3: those lower findings (F5, F6, F7, F8) were not re-verified here [S]; board cell descriptions use final positions [S].
- TESTS 3.
- Kids 3 / Parent 3. Top 5 fixes: 1. M shared score card; 2. S re-verify F5-F8 semantics fixes; 3. S Easy CPU can throw away a winning roll (F11) is a feature for kids: keep, document; 4. S sticker slot; 5. M per-seat "friendly dice" option documented in hub text.

### 10. drawguess (Draw and Guess) total 11
Files: `games/drawguess/*` (1,403), `ui/DrawGuessScreen.kt` (661), `ui/DrawGuessArt.kt` (423), 102 tests.
- GAMEPLAY 3. Word bank with difficulty, matching with plurals, accents, LatAm synonyms (`DrawGuessWords.kt:293-332`), timed-out restore handled (`DrawGuessGame.kt` restore), 24+ corrupt snapshot cases.
- FEEL 1. No animation or motion call in either screen file and no use of `LocalReducedMotion` / `LocalGameSpeed` (grep: 0 hits in `DrawGuessScreen.kt`, `DrawGuessArt.kt`); the win moment is a text headline (`DrawGuessScreen.kt:639`); no celebration, no sound-and-haptic win/lose beyond haptics (21 haptic calls, few sounds), no score card [V]. A designed look exists (glyph tiles, paper frame, 3 dp outlines).
- ROBUSTNESS 2. Palm rejection (`DrawGuessArt.kt:238-273`), pause closes the stroke, board version read in draw scope. Below 3: whole play panel recomposes 4x/s on `delay(250)` (`DrawGuessScreen.kt:147`, F10) [S]; landscape phone canvas (F5) [S]; `LaunchedEffect(context)` restart (F14) [S].
- A11Y_I18N 2. Hold-to-reveal with a 5 s auto-hide (`:276`), tool state announced. Below 3: drawing itself needs a finger (inherent); typed guess needs reading, so under 6 a "point to guess" mode is missing [V].
- TESTS 3.
- Kids 2 / Parent 3. Top 5 fixes: 1. S round-win animation and chime respecting Reduced Motion (reuse `rememberParticleBurst`); 2. M score card with the drawing saved as the "sticker"; 3. S "guess by voice at the table" mode (no typing) for ages 4-6; 4. S confirm F10/F5 on a device and fix; 5. S add drawing-pad width limits for tablet.

### 11. echopads (Echo Pads) total 13
Files: `games/echopads/*` (1,237), `ui/EchoPadsScreen.kt` (616), `ui/EchoPadsArt.kt`, 115 tests.
- GAMEPLAY 3. Solo, take turns, duel; one free retry; validated snapshots; collision-free signature (F1 fixed); resume restarts the pattern (F2 fixed).
- FEEL 2. Distinct pitches, pre-rendered low-latency tones, Reduced Motion drops swell and halo, sound-off is flashes only. Below 3: `NGEchoPads` score card with stickers is not built (finish uses `ClFinishedPanel`, `EchoPadsScreen.kt` two uses); the board resizes between phases (F4) [S].
- ROBUSTNESS 2. Frame loop stops when idle (`:202-211`). Below 3: duel pads in landscape can be about 42 dp tall (F3, `ReactionWhackZones.kt:52-60`) [S]; no palm rejection on pads (`EchoPadsScreen.kt` uses pad hit-test only) [V]; free retries on resume (F7) [S].
- A11Y_I18N 3. Okabe-Ito palette, shapes, pitches, ES 45/45 + hub + coach all matching.
- TESTS 3.
- Kids 3 / Parent 3. Top 5 fixes: 1. S duel side by side in landscape (new layout variant); 2. M score card with longest-pattern sticker; 3. S contrast outline on yellow pad (F9); 4. S reserve the "x of y" line (F4); 5. S palm rejection on the pad grid.

### 12. actitout (Act It Out) total 12
Files: `games/actitout/*` (1,633), `ui/ActItOutScreen.kt` (818), 200-card deck, 84 tests.
- GAMEPLAY 3. Strict restore, curtain secrecy, team spread, little-kids bonus, 200 cards with banned-word test.
- FEEL 2. Hold-to-see pad, ring timer, buzzer ticks. Below 3: no `LocalGameSpeed` use (timer is real-time, fine) and the end screen is a scoreboard card, not the mockup's "Winners + Share card" (`NGActItOut.dc.html`); the card art is 80 lines (`ActItOutArt.kt`) so the 200 pictogram glyphs are simple [V].
- ROBUSTNESS 2. Pause turns the turn into a curtain (`ActItOutEngine.kt:196-204`). Below 3: no `FLAG_SECURE` or Recents hiding (grep none), so the card shows in Recents [V]; `AiHoldPad` min height 180 dp and 96 dp ring still clip at 2x font (`ActItOutScreen.kt:561`) [S]; no Compose tests.
- A11Y_I18N 2. 52 `stringResource` uses, ES 266/266, keep file. Below 3: TalkBack reads the secret card aloud to the room (F7, inherent) [V].
- TESTS 3.
- Kids 3 / Parent 3. Top 5 fixes: 1. S `window.setFlags(FLAG_SECURE)` or `setRecentsScreenshotEnabled(false)` while PLAYING; 2. M winners card with "fastest guess" awards and share; 3. S headphone note in help text for TalkBack; 4. S ring size from the text; 5. M one Compose test per stage.

### 13. bridgebuilders (Bridge Builders) total 13
Files: `games/bridgebuilders/*` (1,032), `ui/BridgeBuildersScreen.kt` (562), 88 tests.
- GAMEPLAY 3. Fixed-step deterministic engine, cooperation rule cannot deadlock, helper robot for a lone builder, handicap per lane, validated saves.
- FEEL 2. River water, cart, robots, confetti, Reduced Motion and speed honoured (`:140-156`). Below 3: win/lose uses `ClFinishedPanel` and the drowning during CROSS is unexplained (audit F2) [V]; no score card.
- ROBUSTNESS 3. Pause on dialogs and focus loss, `Path` reuse, roster guards.
- A11Y_I18N 2. `TapTugBoard` zone actions, lane state description (`:470`). Below 3: TalkBack cannot find the green spot (audit F3, partly mitigated by `stateDescription`) [S]; one fixed 24 sp label (`:509`).
- TESTS 3.
- Kids 3 / Parent 3 (the only cooperative game). Top 5 fixes: 1. S explain "the water rose" on a lost try; 2. M score card with a "bridge built together" sticker; 3. M audible green-spot cue for TalkBack; 4. S palm rejection already shared, keep; 5. S Leave after a lost try should not record a loss for everyone (F7).

### 14. partytoolkit (Party Toolkit) total 8
Files: `games/partytoolkit/*` (271), `ui/PartyToolkitScreen.kt` (1,637), `ui/party/*` (15 files), `core/PartyRoster.kt`, 21 + 3 logic test files.
- GAMEPLAY 2. Dice (die-type picker, history), coin flip, letter, hourglass, first player, teams, score keeper, team splitter, picker, turn order, game clock, round tracker, card dealer; logic is pure and tested (`ui/party/PartyKitLogicTest.kt` and friends). Below 3: eight legacy tools overlap the newer ones (Scoreboard vs Score Keeper, Teams vs Team Splitter, First Player vs Who Starts, `PartyToolkitScreen.kt:326-343`) [V]; roster shared only with the newer set [S].
- FEEL 1. Plain Material with emoji tab icons (`:326-343`), no designed look, no five-state flow (not applicable), no sound hooks beyond haptics (47 haptic calls), dice and coin animation only check `LocalReducedMotion` twice [V].
- ROBUSTNESS 2. `pause()`/`resume()` empty (`PartyToolkitGame.kt:58-59`), Hourglass uses its own timer state and chip (`PartyToolkitScreen.kt:358-370`). Below 3: one 1,637-line file with 34 `Text("...")` literals; no process-death test of the timer [V].
- A11Y_I18N 1. The eight legacy tools are English only: `Text("Die type")`, "Roll how many dice?", "Flip a coin", "Life Points", "Reset Scores" and so on (`PartyToolkitScreen.kt:464,492,606,710,786,926,1143,1255,1388,1623`), tab titles are literals (`:326-333`), TalkBack descriptions are English strings ("Coin landed on heads", `:627`), and none of them exist in `values-es` (grep for "Die type" in `res`: no hit) [V]. The 8 newer tools do have ES.
- TESTS 2. Timer and logic tests, no screen or ES-parity test.
- Kids 1 / Parent 2. Top 5 fixes:
  1. M move the eight legacy tools' text to `party1_strings.xml` / `values-es` (about 60 strings).
  2. S delete the duplicates (Scoreboard, Teams, First Player) or redirect their tabs to the newer tools.
  3. M "Dice for little ones": bigger die faces, "roll for me" with sound and no text.
  4. M split the file per tool (the `ui/party/` pattern).
  5. S pause the hourglass while backgrounded or keep wall-clock and show a "time's up" notification (the chip already exists).

## Shared-component findings

1. **GameChrome has no pause hook** (`uikit/GameChrome.kt:94-260`). The corner menu, the help dialog and the leave dialog open over a live game; only Bridge Builders (`ui/BridgeBuildersScreen.kt:115-156`) and Slide Climb (`SlideClimbScreen.kt:430`) work around it locally. Taps, Whack, Reaction, Light Trail, Sumo, Paddle, RPS, Echo, Draw do not. [V] Fix: `onOverlayChanged: (Boolean) -> Unit` called when `showMenu || showHelp || showLeaveConfirm`, and a `LocalGamePaused` the screens can read. Size M, unlocks 9 games.
2. **Club coach card auto-opens over a running match** (`ui/ChineseLudoUi.kt:186-205`; `rememberClCoach(..., ready = true)` in 12 party screens). First launch covers the countdown. [V] Fix: `ready = false` until the game is idle or add `onShown/onDismiss` to pause. Size S.
3. **Pause is a no-op in four engines**: Tap Race, Tug of War, Reaction Duel, Whack-a-Mole (`TapRaceGame.kt:217`, `TugOfWarGame.kt:235`, `ReactionDuelGame.kt:122`, `WhackAMoleGame.kt:114`). Reaction and Whack run on an absolute clock so backgrounding lets the round end. [V] Fix: copy the Echo Pads `pausedAt` + `shiftTime` pattern. Size M each.
4. **Blank first frame class**: the engine is created in `LaunchedEffect(context)` after the first composition and the screen then `return`s with nothing (`TapRaceScreen.kt:138`, `TugOfWarScreen.kt:137`, `PaddleDuelScreen.kt:174`, `RpsShowdownScreen.kt:225`) or draws an empty box (`ReactionDuelScreen.kt:194-201`). GameChrome and BackHandler are also missing for that frame. [V] Fix: init in `remember` before the first composition, or render GameChrome plus a themed background while loading. Size S per game.
5. **ContactSizes / palm rejection is used by only four screens** (`TapTugBoard` for Tap Race, Tug, Bridge Builders, and Draw Guess). Reaction Duel, Whack-a-Mole (`ReactionWhackStage.kt:50-82`), Echo Pads, Paddle Duel (`PdPointerRouter`), Light Trail, Sumo (`TsPointerRouter`) and RPS ignore touch size, so a resting palm triggers presses (false start in Reaction Duel). [V] `ContactSizes` is internal to `PartyTapTugArt.kt:170` with a unit test (`app/src/test/.../ui/ContactSizesTest.kt`). Fix: promote to `uikit` and add a `Modifier.rejectPalms(maxPx)`; size M.
6. **ppRotateLayout** (`ui/PaddleRpsPartyUi.kt:50-64`) swaps constraints and rotates with `placeWithLayer`; hit testing relies on Compose inverse layer transforms [S]. There is no unit or instrumented test of it (grep of `app/src/test` finds only `ContactSizesTest`); `RwZoneBox` and `TapTugBoard` use their own separate rotation code (`ReactionWhackStage.kt:96-108`, `PartyTapTugArt.kt:297`) so there are three rotation implementations. Fix: one shared `FacingZone` composable with a layout test. Size M.
7. **No shared party score card**: `ClFinishedPanel` (`ui/ChineseLudoUi.kt:251-283`) is a Material card with 48 dp buttons (kid targets want 56 dp), one orientation, no mirrored copy for the far seats and no sticker/share slot, versus the five-state storyboards (`NGEchoPads`, `NGActItOut`). [V] Fix: one `PartyScoreCard` with rotated copies per seat, 56 dp buttons and a sticker slot (Sticker Book exists, `StickerBook.dc.html`). Size M, benefits all 13 games.
8. **Party zone layouts**: `ReactionWhackZones.layout` stacks n == 2 halves at full width (`games/partypack/ReactionWhackZones.kt:52-60`), which gives about 42 dp pads for Echo Pads duel in landscape; Whack shrinks the grid to fit (`WhackAMoleScreen.kt:240-253`) but Echo cannot. [V/S]
9. **No per-seat handicap in any pure-arcade party game** (Tap Race, Tug, Reaction, Whack, Light Trail, Sumo, Paddle, RPS, Echo). Only Bridge Builders (`BridgeBuildersHubSpecs.kt:47-54`), Slide Climb, Act It Out (little-kids bonus) offer it, so mixed-age families only have the global Relaxed. [V]
10. **No `CpuLevel.KID` or Expert, no human-model CPU tests** (`core/CpuLevel.kt:11-15` has EASY..EXPERT but none of the 9 arcade games read `isExpert`, and the tests only compare bots to bots, e.g. `PaddleDuelEngineTest.kt:257-295`). [V]
11. **Reduced Motion and Game Speed**: honoured in every party screen except Draw Guess (no use) and Act It Out (speed not used). Tone-on-first-use and animation allocations not measured. [V]
12. **Parent value metadata is missing**: no "what it teaches", no skill tags, no minutes-played summary hook per game in the hub. Audience and min age are set (`GameAudienceTable.kt`, per-spec for the new games). [V]
