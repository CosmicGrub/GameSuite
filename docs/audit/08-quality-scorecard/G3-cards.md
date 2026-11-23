# G3 Quality scorecard: 12 card/tile games (branch session/wave-3)

Read-only static audit, 2026-11-21. No Gradle, adb or emulator run; nothing was executed, so nothing here is device-verified.
Scale 0-3 (3 = meets docs/roadmap/QUALITY-BAR.md). "V" = verified by reading the cited code. "S" = suspected, needs a device/test.
Paths are relative to `app/src/main/java/com/gamesuite/` unless they start with `test/`.
Depth caveat: ~35k lines were in scope. Engines, bots, snapshot/redaction code, test inventories and grep-derived counts were read; the large
screens were sampled (animation, semantics, i18n, lifecycle hooks), not read end to end. A 3 means "no defect found in what was read", not "perfect".
Test counts are `@Test` counts per file (grep). The audit docs in docs/audit/06-new-games were used as a checklist (stale-state-in-draw-lambda,
effect keyed on phase, timer overshoot on resume, allocations in effect keys, EN-only strings).

## Summary table

| game | gameplay | feel | robustness | a11y_i18n | tests | total /15 |
|---|---|---|---|---|---|---|
| uno | 2 | 3 | 2 | 1 | 2 | 10 |
| solitaire | 2 | 2 | 2 | 1 | 1 | 8 |
| blackjack | 3 | 3 | 3 | 3 | 3 | 15 |
| war | 3 | 2 | 3 | 3 | 3 | 14 |
| texasholdem | 3 | 3 | 3 | 2 | 3 | 14 |
| president | 3 | 2 | 3 | 2 | 3 | 13 |
| sevens | 3 | 2 | 3 | 2 | 2 | 12 |
| speed | 3 | 2 | 1 | 2 | 2 | 10 |
| matching | 3 | 2 | 3 | 2 | 2 | 12 |
| spidersolitaire | 3 | 3 | 3 | 3 | 3 | 15 |
| hanafuda | 3 | 2 | 3 | 2 | 3 | 13 |
| mahjongsolitaire | 3 | 1 | 2 | 2 | 3 | 11 |

Weakest five: solitaire (8), uno (10), speed (10), mahjongsolitaire (11), sevens/matching (12).
Strongest: blackjack and spider (15, still unverified on device), war and holdem (14).

## Shared-component findings (fix once, lift many games)

S1 (V, M) Six unrelated card renderers, no consistent deck look. Solitaire uses shared `PlayingCardView` FLAT style, i.e. a white tile with a
"7♠" text label and no pips or court art (`games/cards/PlayingCardView.kt:115-156`, `ui/SolitaireScreen.kt:1016-1040`); Blackjack/War use
`CasinoCards.kt`; Hold'em `HoldemCardArt.kt`; President/Sevens `PsCardUi.kt` (`PsCardFace`); Speed/Matching `speed/CardArt.kt`; Spider has its own
`SpiderCard`. Same suits and ranks look different per game; the QUALITY-BAR "consistent per-game material" is met per game but not per deck.
Fix: promote the best face (Hold'em/Casino) into `games/cards` as `StandardCardFace` (pips, court art, 4-colour option), have Solitaire, Spider,
Speed, President, Sevens, War, Blackjack call it; keep UNO and Hanafuda custom.

S2 (V, S) `Card.toVisual()` gives no semantic name. `Card.label` is "7♠" (`games/cards/Card.kt:30`), and every game re-implements a spoken name
(`solitaireCardName`, `SpiderCardNamer`, `PsLabels`, Hold'em/Casino descriptions, `unoCardDescription` `ui/UnoScreen.kt:1461-1478`). Add
`Card.spokenName(context)` using string resources (ES parity once) and a `CardVisual.contentDescription` field that `PlayingCardView` applies.
`PlayingCardView` itself exposes no semantics (`PlayingCardView.kt:59-119`), and `FannedHand` only applies a description if the caller passes one (`FannedHand.kt:56,316`).

S3 (V, S) Fixed `sp` inside cards ignores the in-app text scale. `PlayingCardView` flat face uses width-derived `.sp` (`PlayingCardView.kt:128`), UNO face
uses `fixedSp` (`:171`, deliberate, card-relative). Outside cards: `UnoScreen.kt` 14 literals (e.g. `:900,:1146,:1531,:1727,:2220`), `PsCardUi.kt:473-499`
(11-15sp), `HoldemScreen.kt:802,:858,:1276`. docs/audit/04 already lists this (71 literals). A `scaledSp()` helper, applied to these three files, is S each.

S4 (V, S) `CardSounds` is thin and has thread-safety gaps (`games/cards/CardSounds.kt`). Only 4 clips (place, draw, shuffle, tap): no flip, deal, win or lose
cue, so screens fall back to `rememberProceduralSfx` (docs/audit/02 section 8.1: AudioTrack+thread per sound). `loadedIds`/`pendingPlays` are plain
collections touched from the load-complete callback thread and the UI thread (`:30-31,:36-43,:57-63`): suspected race. `get()` is unsynchronised
(`:94-96`) and `release()` (`:77`) leaves a dead singleton (a later `get()` returns a released pool). Fix: `@Synchronized`/`ConcurrentHashMap`, null the
instance on release, add flip/deal/win/lose clips routed through the same pool (M).

S5 (V, S) Reduced Motion and game speed are honoured everywhere except Solitaire and Mahjong for game speed (`LocalGameSpeed` 0 hits in both screens);
Solitaire's durations are hard-coded constants (`SOLITAIRE_*_MS`). Mahjong has no tile-removal animation at all (3 animate sites, all hint pulse).
Fix: route durations through `LocalGameSpeed.scale()` (S).

S6 (V, M) `GameChrome` (`uikit/GameChrome.kt`) is good (back intercept, abort, double-tap guard `leaving`, help, undo/hint menu entries) but
the help and leave strings are caller-supplied, which is why UNO and Solitaire shipped English-only help (`ui/UnoScreen.kt:1392,1404`,
`ui/SolitaireScreen.kt:465-481`). A lint-style JVM test that fails when a screen passes a non-`stringResource` `helpTitle` would stop the pattern (S).
GameChrome has no pause hook: games with a live clock (Speed) get no pause on menu/dialog/background (see Speed).

S7 (V, S) `fitBoard` is used by Mahjong only (3 hits); the other 11 screens size cards ad hoc. Solitaire has its own `SolitaireLayoutTest`; the rest
rely on ad-hoc `BoxWithConstraints`. Suspected: card overflow on Fold cover screen and 1.6x+ font for the non-fitBoard screens. Needs an emulator sweep.

S8 (V, S) Per-frame allocation in effect keys: `LaunchedEffect(myTurn, myHand.map { it.instanceId }, ...)` allocates a list every recomposition
(`ui/UnoScreen.kt:323,:614`, also `:372,:562` keyed on `s.players`). Same item as docs/audit/02 section 3 LOW. Fix with `derivedStateOf`/a hand version counter.

S9 (V, S) Accessibility shape is uneven: Matching has 4 semantics sites (`ui/MatchingScreen.kt:368,:525`), Speed and Matching have 0 live regions, Hanafuda
never reads `LocalColorblindMode`. All shared card games would benefit from one `a11yStatus` live-region helper (docs/audit/04 H2).

## Per-game sections

### uno (10/15)
Evidence: UnoGame 1145 lines, UnoBot, UnoRedaction, `ui/UnoScreen.kt` 3067 lines. ~190 tests (Reshuffle coverage 65, networked host 25, draw decision 24, hub options 18).
- GAMEPLAY 2. Three bot tiers are really different (`games/uno/UnoBot.kt:25-60`, random / action-first / WD4 reserve + leader targeting), snapshot is all-or-nothing with
  validation (`UnoGame.kt:156-171`), spectator and per-seat hand redaction exist and are unit-tested (`UnoRedaction.kt`), reshuffle edge cases covered. Down: no Hint (the only
  `hint` hit in `UnoScreen.kt` is the spectate string, `:1238`), Easy/Hard use unseeded `legal.random()` (`UnoBot.kt:28`) so bot games are not reproducible in tests, UnoBotTest has 6 tests, no seeded full-round self-play. (V)
- FEEL 3. Flying card, draw flight, camera punch/shake, discard impact, UNO callout, danger pulse, all gated on `reducedMotion`/`enhanced` and scaled by `LocalGameSpeed`
  (`UnoScreen.kt:149-165,:212,:428,:495`), printed-card art with colour-blind glyph chip (`PlayingCardView.kt:215-290`), 3D thickness option. Best card feel in the suite. (V)
- ROBUSTNESS 2. Bot driven from composition effects with `currentSpeed.scale(450L)` min delay (`:205-212`), good. Issues: 3067-line screen; list allocation in effect keys (S8);
  `delay((25L..60L).random())` unseeded per-frame random in effects (`:195,:2445`); 30 haptic and 24 sound sites inline, none behind one cue table (S).
- A11Y_I18N 1. Whole screen is English-only: 4 `stringResource` vs 22 hard-coded literals, e.g. `"Draw ${s.pendingDraw} cards"` (`:870`), `"Keep card"` (`:1098`),
  `"Your hand"` (`:1226`), help (`:1392-1404`), `"Choose a color"` (`:1506`), `"Next round"` (`:1609`), status line (`:3063-3066`); engine `lastAction` strings are English (`UnoGame.kt:243,303,379...`).
  `UnoHouseRulesScreen.kt` has 0 `stringResource`. No ES parity beyond 4 spectate strings. 14 fixed `.sp` literals. Card TalkBack names are good (`unoCardDescription`, `:1461-1500`) but English. (V)
- TESTS 2. Deep on reshuffle/draw/network, shallow on rules (UnoGameTest 9) and bot (6); no fuzz/self-play of random legal play with invariants (card conservation).

Top 5 fixes:
1. (M) Extract all UNO screen strings + engine `lastAction` to resources with ES (`UnoScreen.kt` 22 sites, `UnoHouseRulesScreen.kt`, `UnoGame.kt` lastAction -> typed event codes localised in UI). Add parity test.
2. (S) Add a seeded-Random parameter to `UnoBot` (like Holdem/War) and a self-play fuzz test: N seeds x 2-4 seats x all difficulties, assert card conservation (108), no throw, game ends.
3. (S) Fix effect keys `myHand.map{}` (`:323,:614`) with a derived hand-version int.
4. (M) Add a Hint (reuse HARD `chooseMove` on the local hand, highlight the card) and wire `GameChrome onHint`.
5. (S) Apply `scaledSp()` to the 14 `.sp` literals; move haptic/sound pairs into one cue helper to cut the inline 54 call sites.

### solitaire (8/15)
Evidence: SolitaireGame 802 lines, SolitaireScreen 1694, 33 tests.
- GAMEPLAY 2. Draw-1/draw-3, undo via JSON history (`SolitaireGame.kt:363-370`), hint with priorities (`:381-420`), autocomplete step, snapshot validation including
  duplicated/missing cards (test `SolitaireResumeUndoHintTest.kt:172`). Gaps: deal is `Deck.shuffled52()` unseeded (`:435`) so no daily deal, no replay seed, no winnable-deal option (Spider has all three);
  no Vegas/scoring variants beyond +10 (`:650`); tests do not cover tableau-run moves, win detection, autocomplete, foundation-to-tableau, or hint validity; no fuzz. (V)
- FEEL 2. Staggered deal flights, reveal flip, fly-to-destination, finale cascade, shake, shimmer, all gated by reducedMotion (`SolitaireScreen.kt:158-165,:285-299,:1348`). Cards are the flat white
  "7♠" tile with an overlay corner index (`:1016-1040`), no pips/court art (S1). Tap-to-select only: `clickable` at `:1116,:1192,:1273`, no drag-and-drop (Spider has drag). Ignores `LocalGameSpeed` (0 hits). (V)
- ROBUSTNESS 2. Auto-complete guarded by `isAutoCompleting`; undo/hint refuse during it. Haptics are chosen by parsing the English `lastAction` text:
  `startsWith("Selected")`, `startsWith("Moved") && "foundation" in ...` (`:403-405`), so any string edit or localisation silently kills haptics (V). No pause handling (acceptable, untimed).
  Undo re-decodes JSON snapshots (history), fine at this size.
- A11Y_I18N 1. 0 `stringResource` in the 1694-line screen, 12 English literals incl. all `onClickLabel`s (`:1083,:1117,:1193`), stateDescription (`:1123,:1276,:1468,:1478`), help text (`:465-481`),
  "Solved!"/"Play again" (`:1539-1556`); engine `lastAction` English is also the live region. No ES at all (no solitaire resource file in values or values-es). Good: 20 semantics sites, 48dp buttons (`:1466-1555`). (V)
- TESTS 1. 33 tests: draw rules + resume/undo/hint-basic. No win/autocomplete/chain-move/foundation-back/hint-validity/fuzz.

Top 5 fixes:
1. (M) Localise the screen and engine messages (EN+ES), replace `lastAction` string parsing with an event enum for haptics/sound (`:403-405`).
2. (M) Seeded deals: add `seed` + daily deal + optional "winnable only" using a Solitaire solver (Spider's `SpiderDealer` is the template). Also gives deterministic tests.
3. (M) Fuzz + property tests: random legal moves never throw, undo restores exact state, 52-card conservation, autocomplete only when winnable, hint always legal.
4. (L) Drag-and-drop for cards and runs (reuse Spider's drag and a11y custom actions) while keeping tap-select.
5. (M) Replace flat face with shared `StandardCardFace` (S1) and honour `LocalGameSpeed` in the `SOLITAIRE_*_MS` constants.

### blackjack (15/15, unverified on device)
Evidence: BlackjackGame 942, Rules 213, Bot 54, CasinoCards 457; 84 tests incl. seeded fuzz (`BlackjackGameTest.kt:572`).
- GAMEPLAY 3. Split (max hands), double, late surrender, insurance, dealer peek, split-aces, "21 after split is not a blackjack" are all in rules (`BlackjackRules.kt:46-110`, `BlackjackGame.kt:155-175,:416-450,:504-518`); basic-strategy bot with
  per-level error chance (`BlackjackBot.kt:13-60`) run via `withContext(Dispatchers.Default)` (`BlackjackGame.kt:671`); hole card redacted for non-host peers (`BlackjackState.kt:96`); snapshot validated (`:764`); seeded Random injected. (V)
- FEEL 3. Shoe flight, hole-card flip, chip stacks, outcome banner spring (`BlackjackScreen.kt:200,:281`), Reduced Motion turns flights into cuts (KDoc `:145`), hatch marks for colour-blind. (V by reading, S visually)
- ROBUSTNESS 3. Event-serial keyed effects, bot off main thread, commit/resume paths. No defect found. (S: not device-tested)
- A11Y_I18N 3. 90 `stringResource`, ES count 215/215 + coach 11/11, per-event announcements (`bj_ann_*`, `:363-396`), `clearAndSetSemantics` on cards (`CasinoCards.kt:319,:388,:412`), 0 fixed sp. (V)
- TESTS 3. 84 tests across rules, game, network, hub; fuzz for chip/card conservation.

Top 5 fixes:
1. (S) Add the missing common rule options to the hub and tests (resplit aces, DAS toggle, dealer S17/H17 is present: confirm hub surfacing).
2. (S) Offer a basic-strategy hint button using `BlackjackBot` logic (hint=18 mentions exist; verify it is surfaced as a visible button).
3. (M) Consume shared `StandardCardFace` once S1 lands so the deck matches Hold'em/War.
4. (S) Add a rule-reference sheet (payouts, when dealer hits) to the coach; ES present already.
5. (S) Run TalkBack and 2.0x font sweeps (never done per docs/audit/04) and record screenshots.

### war (14/15)
Evidence: WarGame 515, WarRules 137, 33 tests incl. fuzz and redaction.
- GAMEPLAY 3. Choose-3 variant, 1 vs 3 face-down wars, short-pile rules, pot-sharing, round cap, seeded shuffles, conservation test, async bot, redacted per-seat piles, snapshot validated (`WarGame.kt:365-394`; tests listed). (V)
- FEEL 2. Uses `CasinoCard`; reduced-motion + game-speed read (`WarScreen` spd=2). Flip/slide animations not read in depth. (S) Win/lose moment and sounds present by grep (15 sound, 14 haptic sites), quality unseen.
- ROBUSTNESS 3. Serial-keyed state, async bot (`asyncBotPickRunsOffTheMainThreadAndAppliesOnce`). (V by test names)
- A11Y_I18N 3. 46 `stringResource`, 0 literals, ES 215/215 shared file, 18 semantics sites. (V)
- TESTS 3. 33 tests with fuzz of every seat count/variant/level.

Top 5 fixes:
1. (S) Device-verify the war reveal animation timing at game speed 0.5x/2x (feel score is unverified).
2. (S) Add a TalkBack announcement of each war round outcome with pot size if missing (check `WarScreen` live regions: 5).
3. (M) Share `StandardCardFace` (S1).
4. (S) Add a "fast-forward auto-play" for the long deterministic game (war is long; cap exists, `maxRounds`).
5. (S) Add a round-by-round recap list for accessibility (hint exists).

### texasholdem (14/15)
Evidence: HoldemEngine 317, Rules 214, Bot 261, Equity 128, 8 test files ~117 tests (HoldemFuzzTest: random tournaments conserve chips, illegal actions never change state, snapshot round-trip at every decision).
- GAMEPLAY 3. Side pots for any number of all-ins, uncalled bet returned, short all-in does not reopen betting, min-raise equal to last full raise, odd chip rules (`HoldemEngine.kt:18-19`, `HoldemRules.kt:39-90`); Monte-Carlo equity bot with per-level iteration counts and styles (`HoldemBot.kt:61-92`); hint with pot odds (`:235-259`); hole cards redacted except revealed (`HoldemRedaction.kt:13-19`). (V)
- FEEL 3. Own vector card art (`HoldemCardArt.kt`, four-colour deck option `:49-54`), deal/flip animation gated by `reduced` (`HoldemScreen.kt:341-425,:870`), burst confetti, thinking indicator. (V)
- ROBUSTNESS 3. No defect found; equity runs in the bot path (not confirmed off main: `HoldemGame.kt` has no Dispatchers hit, S, verify that `HoldemEquity` iterations do not run on the UI thread at HARD).
- A11Y_I18N 2. 115 `stringResource`, ES 140/140 + coach, 29 semantics sites, but fixed sp at `HoldemScreen.kt:802,:858,:1276` and 5 sp literals total; 11-12sp chip text is small. (V)
- TESTS 3.

Top 5 fixes:
1. (S) Confirm/make `HoldemEquity` Monte-Carlo run on `Dispatchers.Default` (no Dispatchers import in `HoldemGame.kt`/`HoldemBot.kt`, grep) with a time budget.
2. (S) `scaledSp()` on 3 literals (`HoldemScreen.kt:802,858,1276`).
3. (S) Add a hand-ranking cheat sheet to the coach (6 coach strings exist).
4. (M) Tournament blind-increase structure and rebuy test coverage check (not read).
5. (S) Show equity/hint only at Easy/Medium; gate at Hard to keep Hard honest (design call).

### president (13/15)
Evidence: PresidentGame 521, Rules 155, Flow 244, Bot 151, PsCardUi 508; ~95 tests incl. adversarial.
- GAMEPLAY 3. Revolution, ties, card exchange between rounds, trick-killer, must-include (`PresidentRules.kt:25-139`), async bot (`PresidentGame.kt:309`), undo, hint, validator, snapshot (`PresidentGameTest`). (V)
- FEEL 2. `PsCardFace` + `PsFelt` themed felt, own cards (S1). Animations not read in depth (S). 17 haptic, 21 sound sites.
- ROBUSTNESS 3. Validator separate from engine; adversarial test (4). (V by structure)
- A11Y_I18N 2. 51 `stringResource`, ES 158/158 shared with Sevens, live regions (`PresidentScreen.kt:574,:690,:785`), but only 2 `contentDescription` sites in the screen (`PsLabels` helper likely carries the rest, S), fixed sp 11-15 in `PsCardUi.kt:473-499`.
- TESTS 3. Rules 17, flow 26, game 21, net 15.

Top 5 fixes:
1. (S) `scaledSp()` on `PsCardUi.kt:473-499`.
2. (S) Verify every hand card has a spoken name via `PsLabels` and add a Compose semantics test.
3. (M) Share `StandardCardFace` (S1).
4. (S) Add fuzz/self-play test (random legal play 3-6 seats, all rule toggles) beyond the 4 adversarial tests.
5. (S) Add explanation toast for "why can't I play" using `explainPlay` reject codes (the API exists, `PresidentGame.kt:132`).

### sevens (12/15)
Evidence: SevensGame 443, Rules 80, Bot 96, Flow 76; 35 tests.
- GAMEPLAY 3. Pass only when no legal card (`SevensGame.kt:125`), optional pass penalty (`SevensRules.kt:12`), async bot and hint (`:247,:279`), undo, snapshot. (V)
- FEEL 2. Shares `PsCardFace`/`PsFelt` with President (S1); animation depth not read (S).
- ROBUSTNESS 3. Same pattern as President.
- A11Y_I18N 2. 41 `stringResource`, ES shared, 3 contentDescription sites (`SevensScreen.kt:417,:484`), shares fixed sp in `PsCardUi`.
- TESTS 2. 35 tests, adversarial 4; no seeded multi-seat self-play with score-conservation invariant.

Top 5 fixes:
1. (S) Self-play fuzz test (3-8 seats, penalty on/off): every hand ends, 52 cards conserved.
2. (S) `scaledSp()` in `PsCardUi.kt`.
3. (S) Highlight playable cards and dim the rest on your turn (verify; hint count 16 suggests present).
4. (M) Shared `StandardCardFace` (S1).
5. (S) Add per-card TalkBack names for the four suit rows (board `contentDescription` at `:417` is one string for the whole board, suspected).

### speed (10/15)
Evidence: SpeedGame 921, Rules 85, CardArt 207, 50 tests.
- GAMEPLAY 3. Clock injected (`clock: () -> Long`), seeded Random, deadlock/auto-flip handling (`SpeedGame.kt:450,:503-530`), CPU profiles clearly distinct (reaction 1800/1000/450ms, miss 25%/8%/0, `SpeedRules.kt:73-76`), `@Synchronized` lifecycle, network with host authority, turn-based assist mode. (V)
- FEEL 2. Own `SpeedCardFace/Back` shared with Matching; reaction-time bots feel real; animation depth not read (S).
- ROBUSTNESS 1. `pause()` and `resume()` are empty (`SpeedGame.kt:259-260`) and the clock loop is `LaunchedEffect(game) { while(true){ game.tick(); delay(40) } }` (`SpeedScreen.kt:157-161`) with no pause on the GameChrome menu, help or leave dialogs or on background (0 pause mentions in the screen). The CPU keeps playing and the auto-flip timer keeps running while a dialog or another app is in front, so a player can come back to a lost game. The tick loop also runs at 25 Hz forever (S: battery). (V)
- A11Y_I18N 2. 55 `stringResource`, ES 126/126, 8 semantics, but 0 live regions for a real-time game (docs/audit/04 H2 same class), card sizes fixed.
- TESTS 2. 50 tests incl. net; no soak/fuzz with the fake clock across levels.

Top 5 fixes:
1. (M) Implement real pause: gate `tick()` on `paused` set from lifecycle and from GameChrome dialogs; shift `goAt/stuckSince/readyAt` by paused duration; implement `pause()/resume()`.
2. (S) Add live-region announcements for opponent plays and flips (and a polite "pile changed" summary).
3. (S) Fuzz test with fake clock: all levels, both modes, run to completion, assert 52-card conservation.
4. (S) Stop the tick loop when match over / screen not RESUMED (`collectAsStateWithLifecycle` is already imported).
5. (M) Shared card face (S1).

### matching (12/15)
Evidence: MatchingGame 794, Bot 49, Art 240, Rules 77; 35 tests.
- GAMEPLAY 3. Memory-model bot (remember/forget by level, `MatchingBot.kt:17-41`) off main via `withContext` (`MatchingGame.kt:14-15`), hint, undo, records store, guest-leaves handling, snapshot. (V)
- FEEL 2. Reuses `SpeedCardBack` and `CardArtColors` (a different game's art module); flip animation and speed honoured (`reduced`, `gameSpeed.delayScale`, `MatchingScreen.kt:413`). Art quality not seen (S).
- ROBUSTNESS 3. No defect found.
- A11Y_I18N 2. 43 `stringResource`, ES shared 126/126, colour-blind read; only 4 semantics sites and 0 live regions (`MatchingScreen.kt:368,:525`), so flip results and match/mismatch are probably silent for TalkBack (S).
- TESTS 2. 35 tests in 4 files; `MatchingRecordsTest` 3, guest-leaves 2; no fuzz/self-play.

Top 5 fixes:
1. (S) Add live-region announcements for flipped card, match, mismatch, turn change.
2. (S) Cell `contentDescription` and `stateDescription` (face-down/up/matched/position) for every card (verify `:525` covers cells).
3. (S) Self-play fuzz: bot vs bot at all levels, 2-4 seats, assert pairs conserved and game ends.
4. (M) Own art module separate from Speed's (or shared `StandardCardFace` back).
5. (S) Add card-size scaling for large boards on Fold cover screen (`fitBoard`, 0 hits).

### spidersolitaire (15/15, unverified on device)
Evidence: SpiderGame 504, Rules 266, Solver 412, Layout 141; ~91 tests.
- GAMEPLAY 3. 1/2/4 suits, daily deal determined by seed, provably-winnable deal search off main thread with a budgeted solver and fallback (`SpiderGame.kt:91-94`, `SpiderSolver.kt:83-128`), hint, undo, snapshot with seed. (V)
- FEEL 3. Drag with lift, per-card animated offset and flip squash, zIndex stack, landed deal-in (`SpiderScreen.kt:612-690`), reduced-motion `snap()`. (V)
- ROBUSTNESS 3. Solver has stop polling (`SpiderSolver.kt:94`), bounded search test (`SpiderSearchBoundsTest`). (V)
- A11Y_I18N 3. Per-card descriptions with column/run/selected/suggested plus `CustomAccessibilityAction` "Move to column N" (`SpiderScreen.kt:641-690`), plurals, ES 183/183 shared with Hanafuda. (V)
- TESTS 3. Rules 24, game 26, dealer/layout 20, bounds 4.

Top 5 fixes:
1. (S) TalkBack pass on a device; the custom actions are the best in the suite but untested live.
2. (S) Per-frame `animateOffsetAsState` x104 cards: verify jank with gfxinfo (S).
3. (M) Share `StandardCardFace` (S1) with a spider-specific back.
4. (S) Add scoring/leaderboard stats per suit count if missing.
5. (S) Run 1.6x/2.0x font and Fold cover sweeps (no `fitBoard`).

### hanafuda (13/15)
Evidence: HanafudaGame 719, KoiRules 294, KoiBot 146, Art 457; ~142 tests (Yaku 30, Rules 39, Game 31, Net 18).
- GAMEPLAY 3. Yaku and koi-koi rules, 3/6/12 months, difficulty ladder in bot (Easy blunders 35%, Hard weighs opponent's unseen cards, `KoiBot.kt:21-103`), hint via seeded HARD bot (`:144`), undo, snapshot, network. (V)
- FEEL 2. Procedural card art (`HanafudaArt.kt` 8 Canvas sites), capture and deck animation not read in depth; reduced/speed read. (S)
- ROBUSTNESS 3. Async work via `withContext`. No defect found.
- A11Y_I18N 2. 79 `stringResource`, ES shared 183/183 + tips, 19 semantics, names for every card (`names.card(id)`, `:1026`), but `LocalColorblindMode` is never read (0 hits in screen and art): suit/month identity must rely on art colour (S, art uses flowers so partially OK).
- TESTS 3.

Top 5 fixes:
1. (S) Read `LocalColorblindMode` and add month number/ribbon-type markers on card faces.
2. (S) Add a yaku progress panel with TalkBack summary (hint=35 mentions; check it is exposed).
3. (S) Live-region announcements for captures and koi-koi decisions (4 live sites, verify content).
4. (M) Art pass: unify card back/face material with the felt/tatami look; verify the canvases do not allocate per frame (S).
5. (S) Add self-play fuzz (bot vs bot all levels all month counts).

### mahjongsolitaire (11/15)
Evidence: MahjongGame 469, Dealer 217, Geometry 102, Layouts 80, Tiles 99, Screen 1145; 84 tests.
- GAMEPLAY 3. Deals are constructed from a solution (always solvable, test proof `dealSolution`, `MahjongGame.kt:57,:143-145`), seedable, shuffle when stuck (`:256-264`), hint prefers winnable pairs, records count only unassisted solves (`:99-100`), undo, snapshot validated. (V)
- FEEL 1. No sound hooks via `CardSounds` (0), 7 sound sites; tiles are drawn as text glyphs in boxes (`MahjongScreen.kt:880-960`), not tile art (no pips/bamboo/characters); no removal/match animation (the only `animateFloat` is the hint pulse, `:822`); no game speed (0 hits). Layout fitting via `fitBoard` is good. (V)
- ROBUSTNESS 2. Clock pauses/resumes correctly (`MahjongGame.kt:308-318`, no overshoot-on-resume class bug seen). Clock text updates via effect (`:701-723`) rather than draw scope: acceptable. No pause concern. 2 because tiles use text sizes via `fontScale` and 144 tile boxes with per-tile semantics (S: recomposition cost on every selection).
- A11Y_I18N 2. 51 `stringResource`, ES 80/80, colour-blind marker size branch (`:927`), 905 per-tile `contentDescription` + `selected`. Fixed tile text via `fontScale(tileW)` ignores in-app scale (by design). No live region count issue (4).
- TESTS 3. Engine 40 + game 44.

Top 5 fixes:
1. (M) Real tile art (dots circles, bamboo sticks, character hanzi numerals, winds/dragons/flowers) as Canvas drawing, shared per tile type; cached in `drawWithCache`.
2. (S) Pair-match animation (scale+fade) and tile-lift on select, gated by reducedMotion and `LocalGameSpeed`.
3. (S) Sound and haptic on select/match/no-match/stuck/win through shared card sound plus new "clack" clip.
4. (S) Fuzz test: random free-pair removal on every layout never leaves an unsolvable board when started from `newDeal`.
5. (S) Add a "no free pairs" announcement and a one-tap Shuffle suggestion in the live region.
