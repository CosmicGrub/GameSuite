# Audit 04: Accessibility, UX and Localization

Scope: `Z:\GameSuite-wt-game-kit` (branch `session/wave-3`, 76 games in `GameThemes.accents`, 122 files in `ui/`).
Mode: read-only (grep plus file reads). No builds, no emulator, no TalkBack run. Date: 2026-11-07.

Legend: **V** = verified from a file or grep in this audit. **G** = guessed or inferred, needs a device or a deeper read.
Severity: HIGH / MED / LOW. Effort: S (under 1 day), M (1-3 days), L (a week or more).

## 0. Summary

The shell is in good shape. Hub, Games, Settings, Stats, Music, chrome, coach cards and tips are fully EN+ES, have semantics and use 48dp minimums.
Most of the gap sits in the in-game screens.

1. **Spanish stops at the game door for 27 of 76 games** (V). About 985 English prose literals remain in `ui/*.kt`.
   This includes every how-to-play dialog for the original waves (Chess, Checkers, Sudoku, ...). QUALITY-BAR gate 7 fails.
2. **Real-time games are effectively invisible to TalkBack and have no one-switch path** (V for counts, G for end-to-end).
   Air Hockey has zero semantics. 21 of 76 screens have no `liveRegion`. Nothing reads `isTouchExplorationEnabled`.
3. **Colorblind mode is one boolean wired into 54 of 76 screens** (V). The 22 that ignore it include several where color carries state.
4. **In-app "Text size" only reaches Material typography** (V). 71 hard-coded `fontSize = N.sp` in 24 files ignore the 0.85-1.5x slider.
   The roadmap already lists 1.6x landscape defects (V, docs).
5. **No kid or pre-reader design** (V). No kids mode, no narration, text-only options in the Hub, text-only coach steps.
6. Strengths (V): chrome back guard on every game, 3 CPU levels plus a Settings default, game-speed control, relaxed and assist
   toggles in the party pack and sports games, a coach card and tips for all 76 games (unit-tested), reduced-motion read by 75 of 76 screens,
   `values-es` at 99.4% key parity, WCAG-tested accent colors.

## 1. How the numbers were derived

- Game list: `ui/GameTheme.kt` `GameThemes.accents` (76 ids). Screen files: the `composable("<route>")` blocks in `MainActivity.kt`.
  Helper files with the same name prefix in `ui/` were added (for example `ShogiBoardView.kt`, `ChineseLudoUi.kt`).
- Scripts (in the session scratchpad, not in the repo) stripped comments, then counted per file:
  `contentDescription`, `semantics`/`clearAndSetSemantics`, `liveRegion`, `stateDescription`, `LocalColorblindMode`, `LocalReducedMotion`,
  `LocalGameSpeed`, `stringResource`, and hint/undo hooks (`onUndo`, `onHint`, `canUndo`, `R.string.*undo/hint`).
- "ES strings" column: count of English prose literals (two or more words, capitalised) that are not in `R.string`, in the screen
  and helper files. Excludes `*HubSpecs.kt`, `GameTips.kt`, `GameHub*.kt`, because those are English-by-design and resolved through
  `HubText` (`hub_<slug>` keys, covered by `HubLocalizationTest`).
  `full` = 3 or fewer literals. `part` = more than 8 `stringResource` calls but still more than 3 literals. `EN-only` = the rest.
- "CPU levels": Hub spec check. A game has a VS_CPU mode with `HubOptions.difficulty()` (Settings default / Easy / Medium / Hard).
  Derived by grepping every `*HubSpecs.kt` plus `GameHubSpecs.kt`. `-` means no CPU mode.
- Caveats: counts show presence, not quality. A screen with 2 `contentDescription` sites may label 64 cells through one helper.
  `hint` and `undo` can be a Hub or chrome menu entry or an in-screen button. Both count as Y.

## 2. Findings

### HIGH

**H1. In-game Spanish missing for the first 27 games plus shared lobby screens** (V; effort L; fix: extract and translate)
- Evidence: 27 screens marked EN-only in the matrix. Largest: `PartyToolkitScreen.kt` 56, `CheckersScreen.kt` 51, `SudokuScreen.kt` 48,
  `ChessScreen.kt` 44, `DominoesScreen.kt` 42, `KenKenScreen.kt` 41, `MinesweeperScreen.kt` 40, `SolitaireScreen.kt` 39, `KakuroScreen.kt` 38,
  `TowerDefenceScreen.kt` 38, `NonogramScreen.kt` 37.
  Examples: `Text("Back to Menu")`, `Text("Puzzle solved!")`, `helpTitle = "How to Play Chess"`, snackbar `"... is a valid dictionary word."`.
- Shared screens also English: `NearbyEntryScreen.kt` (11 literals, 0 `stringResource`), `OnlineEntryScreen.kt` (8), `PlayerNamesDialog.kt` (5),
  `UnoHouseRulesScreen.kt` (5), `NearbyJoinLobbyScreen.kt` (4). These sit in front of every networked game.
- Engine-side English reaches the UI: `games/solitaire/SolitaireGame.kt` (15), `TileGame.kt` (13), `UnoGame.kt` ("Color changed to $color"),
  `ReversiGame.kt`/`MorrisGame.kt` ("Opponent disconnected"). Roadmap `UNBUILT-ITEMS.md` section D and A also list this (V).
- Fix: move literals to `res/values/<game>_strings.xml` plus `values-es`, following the Yahtzee-onward pattern. Do it in the same agent batches as
  the wave rollout. Add a ratchet test that fails when `ui/*.kt` gains a prose literal (a regex scan is enough; see M5).
- Why high: the wave 1-2 games are the best known ones (Chess, UNO, Sudoku).

**H2. TalkBack: gaps in announcements, real-time games, and live verification** (V counts, G behaviour; effort M-L)
- `AirHockeyScreen.kt`: 0 `semantics`, 0 `contentDescription`. The whole play surface is one drag canvas (V).
  `TapRaceScreen.kt` and `TugOfWarScreen.kt`: 0 `contentDescription` (V).
- No `liveRegion` in 21 screens (V): reversi, air-hockey, speed, matching, darts, bowling, carrom, curling, mini-golf, toy-boxing,
  toy-baseball, slot-cars, fishing, tank-battle, shooting-gallery, pig-dice, tap-race, tug-of-war, reaction-duel, whack-a-mole, rps-showdown.
  Reversi is the "pilot" for the chrome rollout and still has no move or score announcements, no `stateDescription` and no reduced-motion read (V).
- `stateDescription` is absent from 50 of 76 screens (V). Cells that are selected, legal, flagged or filled are then only described by one
  merged `contentDescription` at best (G).
- No code path reads `isTouchExplorationEnabled` or `AccessibilityManager` (V grep over `app/src/main`). Timed or reflex games
  (Reaction Duel, Whack-a-Mole, Tap Race, Tug of War, Air Hockey, Breakout, Snake, Tower Defence) have no screen-reader mode (V for absence).
  Party-pack "Relaxed" and "Hold to tap" toggles exist (V, `PartyTapTugHubSpecs.kt:45-53`) and help motor limits but are not offered when
  touch exploration is on (G).
- Docs promised per-cell `collectionInfo` for grid games (`SETTINGS_THEMING_ACCESSIBILITY.md` section 4). 0 uses of `collectionInfo` or
  `CollectionItemInfo` in the repo (V).
- `UNBUILT-ITEMS.md` G: "Live TalkBack end to end" never run (V, doc). Everything in this section is code-read only.
- Good: Chess squares carry `contentDescription` plus `stateDescription` (`ChessScreen.kt:798-800`), turn banner is a polite live region (`:454`),
  `GameChrome` button has a description and click label and hides the glyph (`GameChrome.kt`), Spider has `CustomAccessibilityAction` (`SpiderScreen.kt:685`),
  Breakout/Snake/2048 have custom actions.
- Fix: (a) add a `Modifier.a11yStatus(text)` live-region helper and call it for score and turn text in the 21 screens (S each, M total);
  (b) Air Hockey: semantic labels plus a "turn-based assist" like Toy Sports has (`assist` option in `ToySportsHubSpecs.kt`) (M);
  (c) a TalkBack pass on a device per family (M).

**H3. Colorblind coverage partial and binary** (V; effort M)
- `LocalColorblindMode` read by 54 screens, ignored by 22: reversi, minesweeper, lights-out, sliding-puzzle, air-hockey, party-toolkit, shogi, mini-shogi,
  hanafuda, bowling, slot-cars, fishing, tank-battle, pig-dice, tap-race, tug-of-war, reaction-duel, whack-a-mole, light-trail, sumo-push,
  paddle-duel, rps-showdown (V).
- Likely color-only cues among them (G, read each before fixing): Tap Race traffic light (`TapRaceScreen.kt:419-420` GREEN/AMBER hard-coded colors),
  per-player lane and trail colors in Light Trail, Sumo Push, Tug of War, Paddle Duel; Tank Battle teams; Slot Cars lanes;
  Minesweeper number colors; Lights Out on/off.
  Many may be safe because of icons or labels (G).
- Setting is a single `colorblindMode: Boolean` (`AppSettings.kt`). The design doc promised Off / Deuteranopia / Protanopia / Tritanopia (V, doc).
  Today the mode adds shapes (UNO: circle, triangle, square, diamond), which is the right approach, but players who need a different palette get nothing.
- Fix: audit the 22 screens for color-only meaning and add shape or label cues behind the flag (S-M each, M overall).
  Consider a CVD-safe palette preset in `NamedTheme` (S). Prefer always-on shape cues where they cost nothing.

**H4. Large text: partial** (V; effort M)
- The in-app slider is 0.85-1.5x (`SettingsScreen.kt:254`) and scales only `MaterialTheme.typography` (`AppTheme.kt`).
  71 `fontSize = N.sp` literals across 24 files bypass it (V): `UnoScreen.kt` 11, `ToyBoxingScreen.kt` 8, `ToyBaseballScreen.kt` 7,
  `ShootingGalleryScreen.kt` 6, `TankBattleScreen.kt` 5, `HoldemScreen.kt` 5, `PsCardUi.kt` 5, `WhackAMoleScreen.kt` 3, `GameCards.kt` 3.
  They still follow OS font scale, so only the in-app multiplier is lost (a card with a 14.sp rank stays small for a player who set 150% in-app).
- `AppBottomBar.kt` caps nav labels at 1.3x (`NAV_MAX_FONT_SCALE`) (V). This is common practice, but it is a low-vision regression at 1.6-2.0x.
- Known defects at 1.6x (V, `UNBUILT-ITEMS.md` C): Hub setup Start button unreachable in landscape, host lobby leaves "a sliver" for the table,
  "Got it" below the fold on some sheets, Whack-a-Mole holes about 26dp in phone landscape.
- Only Hanafuda (`:815`, `:837`) and Hangman (`:733`) branch on `fontScale` in game screens (V). No evidence of a systematic 2.0x sweep (G).
- Fix: give hard-coded sizes a helper `scaledSp(n)` that applies the in-app multiplier (S per file, M total). Run a 1.6x and 2.0x emulator sweep
  with the existing `Z:\Dev\gs-sweep` tooling (M). Allow a 2.0x slider stop (S). Replace Hub/setup bottom bars with scrolling content.

**H5. Pre-readers and small hands not served** (V absence; effort M-L)
- No kids mode, no simplified shell, no narration or TTS, no parental or time controls in code (V: grep for kid, child, pre-reader found only unrelated hits).
- Hub per-game card does show an emoji glyph (`gameGlyph` in `GameCatalog.kt`), which helps a pre-reader find games (V). After that, modes
  ("vs Computer", "Pass & Play"), options and coach steps are text only (V). Coach cards have an animated motif, no read-aloud (V).
- In-game commands live in the `⋮` overflow (`GameChrome.kt`): How to Play, Undo, Hint, Back to Menu are text items in a drop-down. A pre-reader cannot
  find Undo or Hint, and the 48dp corner button sits where small hands rest (G).
- Fix: (a) a "Little players" setting that swaps `⋮` items for large icon buttons (undo arrow, light-bulb, home) with the same semantics (M);
  (b) read-aloud for coach steps using `TextToSpeech` with the current locale (M);
  (c) icon on each Hub mode (S);
  (d) a short curated "easy first" list for kids: Tic-Tac-Toe, Connect Four, Matching, Mancala, Whack-a-Mole, Tap Race, Memory-type games with Relaxed on (S).

### MED

**M1. Plurals and number formatting** (V; effort S-M)
- 49 `if (n == 1) "x" else "xs"` sites in `ui`/`games` (V, e.g. `HangmanScreen.kt:817`, `MastermindScreen.kt:1196`, `PartyToolkitScreen.kt:975`).
  Fine for EN, ES breaks for any language with other plural rules. Only 9 `pluralStringResource` calls exist against 25 `<plurals>` (V).
- `"%d:%02d".format(...)` with the default locale is copy-pasted in about 25 files (V: `SudokuScreen.kt:681`, `ColorFloodScreen.kt:487`, ...).
  One shared `formatClock()` would also be the place to handle non-Latin digits (S).
- Chess material line is English and uses `%.1f` (`ChessScreen.kt:1559-1560`) (V).
- ES plurals include the CLDR `many` category correctly (`polish_result_count`, `ls_status_need_players`) (V). Good.

**M2. No per-app language entry point and only 2 locales** (V; effort S for picker, M per language)
- No `localeConfig` in the manifest and no `res/xml/locales_config.xml` (V). Android 13+ shows no per-app language entry for GameSuite.
  `compileSdk` and `targetSdk` are 34, so the attribute is available. No in-app language chooser either (V).
- `values/` and `values-es/` only (V). Spanish is region-less. Wording leans Latin American ("computadora" in `ls_status_need_players_or_computers`) (V).
  `es-ES` would show the same text. Acceptable, but call it out in store listing.
- Word games rely on English assets: `assets/words.txt` (about 359k words), `assets/word_guess_answers.txt`, `CrosswordClueBank.kt` (222 English clues) (V).
  In Spanish these games stay English-only. Hangman, Word Search, Word Guess, Crossword, Word Tiles are 5 of 76 games (V).
- Fix: add `locales_config.xml` listing en, es (and future) and reference it (S). Mark word games "English words" in ES until word lists exist (S).

**M3. 29 keys in `values/strings.xml` have no ES copy, and they look dead** (V; effort S)
- Missing in `values-es`: `game_breakout`, `game_color_flood`(+daily), `game_connect_four_pass_play`, `game_connect_four_vs_cpu`, `game_dots_and_boxes_pass_play`,
  `game_dots_and_boxes_vs_cpu`, `game_edge_match`(+daily), `game_kakuro`(+daily), `game_kenken`(+daily), `game_lights_out`(+daily), `game_mastermind`(+daily),
  `game_minesweeper`(+daily), `game_nonogram`(+daily), `game_party_toolkit`, `game_reversi_vs_cpu`, `game_sudoku`(+daily), `game_tower_defence`, `game_word_guess`(+daily),
  and `app_name` (intentionally not translated).
- `R.string.game_*` is referenced 0 times in Kotlin and no `getIdentifier("game_...")` exists (V grep), so these are dead keys left from before `HubText`.
  Delete them, or the fallback-to-English risk returns if someone starts using them. The Hub equivalents (`hub_<slug>`) do have Spanish.
- Other ES checks (V): 4761 EN entries vs 4732 ES, 0 extra ES keys, 25/25 plurals, placeholder counts match except 3 plural entries where ES repeats `%1$d` per
  quantity (expected). 12 long identical strings are all pure format strings (for example `%1$s %2$d, %3$s %4$d`). `themes.xml` has no ES copy, which is correct.
  Spanish copy that I spot-read is natural (for example "Añade 1 jugador más o una computadora"), but there has been no native review (V, O13 covers only quick chat).

**M4. Undo and Hint gaps** (V grep; effort S-M each)
- Undo is absent (no hook found) in: word-tiles, uno, hangman, word-search, crossword, word-guess, minesweeper, mastermind, breakout, tower-defence, air-hockey,
  party-toolkit, snake, blackjack, war, speed, all real-time sports/party games. Card-draw, reflex and real-time games do not need it, but
  Minesweeper (a mis-tap on a mine ends the game, no "undo last reveal"), Mastermind (peg removal exists?), Word Tiles and Crossword users expect it (G).
- Hint absent in: dominoes, word-tiles, breakout, tower-defence, air-hockey, party-toolkit, snake, and most party-pack games (V; party-pack matches in the matrix are weak substring hits, treat as G).
  Gate 4 of QUALITY-BAR requires hint and undo for the games it applies to.
- Fix: Minesweeper "safe first reveal" already exists (V `MinesweeperScreen.kt:117`); add confirm-on-mine or one undo for kids mode (M).
  Dominoes hint (engine knows legal tiles) (S).

**M5. No automated a11y or localization ratchets** (V; effort S-M)
- Tests that touch strings: `GameCoachTest`, `HubLocalizationTest`, `LobbyStringsTest`, `ShogiLocalizationTest`, `HoldemResourceTest`, a few Hub spec tests (V).
  Nothing diffs `values` against `values-es` globally, and nothing scans `ui/` for literals.
- No `enableAccessibilityChecks()` or ATF in `androidTest` (V grep). `androidTest` has only LAN bot, nav rotation and audio tests.
  `GameThemeTest` covers accent contrast only (3:1 vs two card surfaces per theme) (V).
- Fix: (a) JVM test parsing every `values*/*.xml`: key parity, placeholder parity, plural quantities (S, my scripts show it already passes today);
  (b) JVM test failing on new prose literals in `ui/` (allow-list the 27 legacy files, shrinking) (S);
  (c) Compose UI test per game family with `enableAccessibilityChecks()` for touch target and missing label (M);
  (d) turn lint `MissingTranslation` and `HardcodedText` to error (S).

**M6. Touch targets: only the shared chrome is proven** (G; effort M)
- `GameChrome` corner button is 48dp (V `GameChrome.kt`), `AppBottomBar` items `heightIn(min = 48.dp)` (V). Nothing proves the other controls.
- Static grep cannot map sizes to clickables (the attempt produced only spacers). Known concrete miss: Whack-a-Mole holes about 26dp in phone
  landscape (V, `UNBUILT-ITEMS.md` C).
- Dense boards at risk (G): Word Tiles 15x15, Nonogram, Kakuro, Mahjong tiles, Minesweeper, Hex, Gomoku 15x15. Tap-to-place fallbacks help; a zoom mode
  was planned (`SETTINGS_THEMING_ACCESSIBILITY.md` section 4) and `TileGameScreen.kt` has 0 mentions of zoom (V).
- Fix: measure on the emulator at 360dp width with the ATF check from M5 (M).

**M7. Reduced motion, flash and haptics** (V; effort S)
- 75 of 76 screens read `LocalReducedMotion`. Exception: **Reversi** (V, 0 hits). Shared `CameraShake` has no internal gate; callers must
  check (`AirHockeyScreen.kt:123` does) (V/G).
- No flash or strobe audit exists (no `flash`-rate cap found) (G). Candidates: UNO "CHALLENGE!" flashes, Air Hockey goal flash, Dots and Boxes sweep, win bursts.
  Add a "no more than 3 flashes per second" rule and test in the effects package.
- Haptics: central `Haptics.kt` reads `LocalHapticsEnabled` (V), so the toggle works app-wide. Only `DominoesScreen.kt` reads the local itself.
  No separate "haptic strength" control (G).
- Audio: Sound, Music and Haptics are separate toggles in Settings (V). No in-game quick mute, no captions for sound cues (G).
- Fix: add `LocalReducedMotion` to Reversi (S). Gate `CameraShake` internally (S).

**M8. Difficulty, speed and assist coverage** (V; effort M)
- 55 games have a CPU mode with Settings default / Easy / Medium / Hard. No Expert: `CpuLevel.EXPERT` exists but the Hub hides it because no engine reads it
  (V, `GameHub.kt:313`). Meets gate 2 (three or more levels).
- Known imbalance: Toy Tennis and Paddle Duel CPU levels, Paddle Duel Easy too hard (V, `UNBUILT-ITEMS.md` C).
- `GameSpeed` (Slow 2.4x to Quick 0.55x) is read by 54 screens (V), but it is cosmetic and does not touch real-time physics. Air Hockey, Breakout, Tower Defence,
  Reaction/Whack have no speed control or assist; Snake has its own speed (V `SnakeScreen.kt:441`). Doc section 3 planned puck-speed slider and larger paddle for Air Hockey
  (V doc; I found neither in `AirHockeyPrefsStore.kt`, only `motion_tier`).
- Puzzle and word games (Hangman, Crossword, Word Search, Solitaire) show no Hub difficulty option (V: `solo(...)` has no options). The screens may have
  in-game tiers (G, Sudoku does: "Switch to $tierName?").

**M9. Back and accidental exit** (V; effort none)
- All 76 screens reference `GameChrome` or `BackHandler`. Back asks for confirmation while a match is live and saves progress when resumable (V).
  Mid-match leave is an abort and never scored (V). Double-tap on Leave is guarded (V).
- Gaps: Breakout, Tower Defence, Air Hockey are `resumable = false` (V `GameHubSpecs.kt:442-446`), so Leave loses the run (the dialog warns). Fine for kids? Consider autosave for Tower Defence (M).
- "Play again" and "New puzzle" buttons are one tap with no confirm in most screens (G). Sudoku asks "Deal a new puzzle?" (V).

**M10. Orientation, tablet, foldable** (V partial; effort M)
- No orientation lock in manifest or code (V). `configChanges` handles rotation, fold, font scale and locale (V). `supportsRtl="true"` (V).
- `foldable/` has `AdaptiveTwoPane`, `FoldAwareLayout`, `DeviceClass` (V) but only about 19 screens reference any adaptive API (V grep `orient`).
  Fold inner screen and book posture passes, and tablet layout for 3-4 player party games, are open (V, `UNBUILT-ITEMS.md` E).
- Fix: sweep landscape and tablet for the five largest wave-3 screens; ride the existing `gs-sweep` harness (M).

### LOW

- **L1. RTL readiness** (V/G): `supportsRtl=true`, but 0 `AutoMirrored` icon uses, about 40 `offset(x=...)` or left/right paddings, and the `⋮`
  corner button is fixed `TopEnd` (correct under RTL). Boards should not mirror. No RTL language ships, so this blocks only Arabic/Hebrew (S-M, later).
- **L2. Contrast per game** (V/G): accent pairs are tested (3:1 and button text 4.5:1, light and dark). Board art, piece colors and Midnight Arcade or High Contrast
  schemes are not tested (V). `ensureContrast` nudges accents at runtime (V comment in `GameTheme.kt`). `FELT_TABLE` named theme is still a reserved slot (V `AppSettings.kt`).
- **L3. Empty and error states** (G): lobby problems and relay errors are English or raw (`lobbyGameProblem`, host lobby raw relay text, `UNBUILT-ITEMS.md` A and C). Stats, Hub resume and saved-games
  empty states have strings (V in `StatsScreen`, `GameHubScreen`).
- **L4. Hub and onboarding** (V): first-run welcome banner (`hasSeenOnboarding`), coach card auto-opens in the Hub for 68 games and each of the 8 `ownCoachInGame` games shows its own
  card (V `GameCoach.kt:49-58`). Coach text has 3-6 steps plus a trivia fact per game (V `GameCoachTest` keeps EN+ES complete).
- **L5. Emoji glyphs** (V): menu glyphs are decorative and hidden from TalkBack (`clearAndSetSemantics`). Good. Device emoji fonts may differ (a missing glyph shows tofu on very old devices) (G).
- **L6. Nav label cap at 1.3x** (V): see H4.
- **L7. `hub_same_screen` string only defined in one party strings file** (V, from `UNBUILT-ITEMS.md` C): a hidden resource dependency that can break a single-file edit.

## 3. Next languages (recommendation, G based on market and catalog)

1. **Portuguese (pt-BR)**: large Android market, close to Spanish for reuse of phrasing, no RTL, plural rules similar to ES. Highest value per string.
2. **French (fr)**: broad reach (FR, CA, Africa), same script, 2 plural forms plus `many`.
3. **German (de)**: strong paid-app market, long compound words will stress the 1.6x layouts, so translate and run the font sweep together.
4. **Japanese (ja)**: fits the catalog (Shogi, Mini-Shogi, Hanafuda, Mahjong Solitaire, Hare and Hounds). Needs a CJK font check; piece names exist in `ShogiLocalizationTest` scope.
5. **Hindi (hi)** and **Indonesian (id)**: large Android installed base. Hindi needs Devanagari digit and font review of `"%d:%02d"` helpers (see M1).
6. **Arabic (ar)**: only after the RTL pass (L1) and digit handling.
Do M5 (parity and literal tests) and H1 first. Each new language multiplies the 4,761 strings, the 222 crossword clues and the word lists.

## 4. Per-game matrix

Columns, all grep-derived per section 1: **TalkBack** = `contentDescription` sites / `semantics` sites / `liveRegion` sites in the screen plus helpers;
**colorblind** = reads `LocalColorblindMode`; **reduced-motion** = reads `LocalReducedMotion`; **ES strings** = full, part(N), or EN-only(N) with N hard-coded English prose literals;
**coach** = Hub coach card (all 76 via `GameCoach`), "+own" when the screen has a first-run card of its own; **hint / undo** = hook present; **CPU levels** = 3 means Settings default plus Easy, Medium, Hard.
`mini-shogi` shares `ShogiScreen.kt` with `shogi`, so their numbers are identical.

| game | TalkBack cd/sem/live | colorblind | reduced-motion | ES strings (hard-coded EN literals) | coach | hint | undo | CPU levels | screen file |
|---|---|---|---|---|---|---|---|---|---|
| chess | 4/22/6 | Y | Y | part(44) | Hub | Y | Y | 3 | ChessScreen.kt |
| checkers | 4/24/10 | Y | Y | EN-only(51) | Hub | Y | Y | 3 | CheckersScreen.kt |
| reversi | 2/6/0 | N | N | EN-only(24) | Hub | Y | Y | 3 | ReversiScreen.kt |
| mancala | 4/20/8 | Y | Y | EN-only(34) | Hub | Y | Y | 3 | MancalaScreen.kt |
| connect-four | 2/12/6 | Y | Y | EN-only(20) | Hub | Y | Y | 3 | ConnectFourScreen.kt |
| dots-and-boxes | 5/15/6 | Y | Y | EN-only(29) | Hub | Y | Y | 3 | DotsAndBoxesScreen.kt |
| tic-tac-toe | 3/14/8 | Y | Y | EN-only(33) | Hub | Y | Y | 3 | TicTacToeScreen.kt |
| dominoes | 5/18/8 | Y | Y | part(42) | Hub | - | Y | 3 | DominoesScreen.kt |
| word-tiles | 4/26/14 | Y | Y | EN-only(26) | Hub | - | - | 3 | TileGameScreen.kt |
| uno | 6/15/8 | Y | Y | EN-only(42) | Hub | Y | - | 3 | UnoHouseRulesScreen.kt |
| solitaire | 4/22/6 | Y | Y | EN-only(39) | Hub | Y | Y | - | SolitaireScreen.kt |
| hangman | 5/15/8 | Y | Y | EN-only(20) | Hub | Y | - | - | HangmanScreen.kt |
| word-search | 4/16/6 | Y | Y | EN-only(32) | Hub | Y | - | - | WordSearchScreen.kt |
| crossword | 5/20/6 | Y | Y | EN-only(21) | Hub | Y | - | - | CrosswordScreen.kt |
| word-guess | 7/22/8 | Y | Y | EN-only(31) | Hub | Y | - | - | WordGuessScreen.kt |
| sudoku | 8/24/8 | Y | Y | EN-only(48) | Hub | Y | Y | - | SudokuScreen.kt |
| kenken | 9/25/8 | Y | Y | EN-only(41) | Hub | Y | Y | - | KenKenScreen.kt |
| kakuro | 4/26/12 | Y | Y | EN-only(38) | Hub | Y | Y | - | KakuroScreen.kt |
| nonogram | 6/16/6 | Y | Y | EN-only(37) | Hub | Y | Y | - | NonogramScreen.kt |
| minesweeper | 3/16/8 | N | Y | EN-only(40) | Hub | Y | - | - | MinesweeperScreen.kt |
| lights-out | 3/16/6 | N | Y | EN-only(26) | Hub | Y | Y | - | LightsOutScreen.kt |
| sliding-puzzle | 5/18/6 | N | Y | EN-only(23) | Hub | Y | Y | - | SlidingPuzzleScreen.kt |
| color-flood | 5/15/6 | Y | Y | EN-only(27) | Hub | Y | Y | - | ColorFloodScreen.kt |
| edge-match | 7/19/8 | Y | Y | EN-only(33) | Hub | Y | Y | - | EdgeMatchScreen.kt |
| mastermind | 7/16/8 | Y | Y | EN-only(36) | Hub | Y | - | - | MastermindScreen.kt |
| breakout | 2/18/8 | Y | Y | EN-only(23) | Hub | - | - | - | BreakoutScreen.kt |
| tower-defence | 6/18/10 | Y | Y | EN-only(38) | Hub | - | - | - | TowerDefenceScreen.kt |
| air-hockey | 0/0/0 | N | Y | EN-only(9) | Hub | - | - | 3 | AirHockeyScreen.kt |
| party-toolkit | 14/36/18 | N | Y | EN-only(56) | Hub | - | - | - | PartyToolkitScreen.kt |
| yahtzee | 4/24/8 | Y | Y | full | Hub | Y | Y | 3 | YahtzeeScreen.kt |
| backgammon | 4/16/4 | Y | Y | full | Hub | Y | Y | 3 | BackgammonBoardView.kt |
| game-2048 | 3/17/4 | Y | Y | full | Hub | Y | Y | - | G2048Screen.kt |
| snake | 2/18/8 | Y | Y | full | Hub | Y | - | - | SnakeScreen.kt |
| flow-lines | 5/22/6 | Y | Y | full | Hub | Y | Y | - | FlowLinesScreen.kt |
| mahjong-solitaire | 2/15/8 | Y | Y | full | Hub | Y | Y | - | MahjongScreen.kt |
| gomoku | 3/10/4 | Y | Y | full | Hub+own | Y | Y | 3 | GomokuScreen.kt |
| hex | 3/10/4 | Y | Y | full | Hub+own | Y | Y | 3 | HexScreen.kt |
| nine-mens-morris | 2/10/6 | Y | Y | full | Hub+own | Y | Y | 3 | NineMensMorrisScreen.kt |
| hare-and-hounds | 1/8/4 | Y | Y | full | Hub+own | Y | Y | 3 | HareAndHoundsScreen.kt |
| chinese-checkers | 2/14/8 | Y | Y | full | Hub | Y | Y | 3 | ChineseCheckersScreen.kt |
| ludo | 4/16/8 | Y | Y | full | Hub | Y | Y | 3 | ChineseLudoUi.kt |
| shogi | 3/15/6 | N | Y | full | Hub+own | Y | Y | 3 | ShogiBoardView.kt |
| mini-shogi | 3/15/6 | N | Y | full | Hub+own | Y | Y | 3 | ShogiBoardView.kt |
| blackjack | 2/19/11 | Y | Y | full | Hub | Y | - | 3 | BlackjackScreen.kt |
| war | 3/21/11 | Y | Y | full | Hub | Y | - | 3 | WarScreen.kt |
| texas-holdem | 9/31/8 | Y | Y | full | Hub | Y | Y | 3 | HoldemScreen.kt |
| president | 1/8/8 | Y | Y | full | Hub | Y | Y | 3 | PresidentScreen.kt |
| sevens | 3/14/6 | Y | Y | full | Hub | Y | Y | 3 | SevensScreen.kt |
| speed | 7/10/0 | Y | Y | full | Hub | Y | - | 3 | SpeedScreen.kt |
| matching | 2/6/0 | Y | Y | full | Hub | Y | Y | 3 | MatchingScreen.kt |
| spider-solitaire | 4/21/10 | Y | Y | full | Hub | Y | Y | - | SpiderScreen.kt |
| hanafuda | 6/21/8 | N | Y | full | Hub | Y | Y | 3 | HanafudaArt.kt |
| darts | 5/9/0 | Y | Y | full | Hub | Y | - | 3 | DartsScreen.kt |
| bowling | 5/11/0 | N | Y | full | Hub | Y | - | 3 | BowlingScreen.kt |
| carrom | 3/8/0 | Y | Y | full | Hub | Y | Y | 3 | CarromScreen.kt |
| curling | 4/13/0 | Y | Y | full | Hub | Y | Y | 3 | CurlingScreen.kt |
| billiards | 4/14/2 | Y | Y | full | Hub | Y | Y | 3 | BilliardsGolfUiHelpers.kt |
| mini-golf | 4/14/0 | Y | Y | full | Hub | Y | Y | 3 | BilliardsGolfUiHelpers.kt |
| toy-soccer | 3/9/4 | Y | Y | full | Hub | Y | - | 3 | ToySoccerScreen.kt |
| toy-tennis | 2/7/2 | Y | Y | full | Hub | Y | - | 3 | ToySoccerTennisArt.kt |
| toy-boxing | 3/11/0 | Y | Y | full | Hub | Y | - | 3 | ToyBoxingArt.kt |
| toy-baseball | 6/16/0 | Y | Y | full | Hub | Y | - | 3 | ToyBaseballScreen.kt |
| slot-cars | 4/10/0 | N | Y | full | Hub | Y | - | 3 | SlotCarsArt.kt |
| fishing | 5/11/0 | N | Y | full | Hub | Y | - | 3 | FishingArt.kt |
| tank-battle | 7/14/0 | N | Y | full | Hub | Y | Y | 3 | TankBattleArt.kt |
| shooting-gallery | 3/8/0 | Y | Y | full | Hub | Y | - | 3 | ShootingGalleryArt.kt |
| six-ball-puzzle | 6/18/6 | Y | Y | full | Hub | Y | Y | 3 | PigDiceArt.kt |
| pig-dice | 5/12/0 | N | Y | full | Hub | Y | - | 3 | PigDiceArt.kt |
| tap-race | 1/10/0 | N | Y | full | Hub | - | - | 3 | PartyTapTugArt.kt |
| tug-of-war | 1/10/0 | N | Y | full | Hub | Y | - | 3 | PartyTapTugArt.kt |
| reaction-duel | 2/9/0 | N | Y | full | Hub | Y | - | 3 | ReactionDuelArt.kt |
| whack-a-mole | 2/9/0 | N | Y | full | Hub | - | - | 3 | ReactionWhackStage.kt |
| light-trail | 3/16/4 | N | Y | full | Hub | - | - | 3 | LightTrailScreen.kt |
| sumo-push | 5/21/4 | N | Y | full | Hub | - | - | 3 | SumoPushScreen.kt |
| paddle-duel | 3/14/2 | N | Y | full | Hub+own | - | - | 3 | PaddleDuelScreen.kt |
| rps-showdown | 5/18/0 | N | Y | full | Hub+own | - | Y | 3 | PaddleRpsPartyUi.kt |

## 5. Verified vs guessed

**Verified (read in files or counted by grep):**
- Key parity EN vs ES (4761 vs 4732, 29 missing, all `game_*`/`app_name`), plural parity, placeholder parity, only `values` and `values-es` exist.
- 985 English prose literals in `ui/`, per-file counts, 27 EN-only game screens, English in shared lobby screens, English engine messages.
- Colorblind (54 of 76), reduced-motion (75 of 76; Reversi missing), game-speed (54), live-region (55 of 76), `stateDescription` (26 of 76) usage per screen.
- 71 fixed-sp literals in 24 files; slider 0.85-1.5x; nav cap 1.3x; no `localeConfig`; no orientation lock; `supportsRtl=true`; no `isTouchExploration` use;
  no `collectionInfo`; no a11y instrumentation tests; no kids mode; Hub catalog covered by `HubLocalizationTest`; coach for all 76 games covered by `GameCoachTest`.
- Back guard and abort-not-scored behaviour in `GameChrome.kt`; CPU levels (3) from Hub specs; Expert hidden on purpose.

**Guessed (needs a device, a TalkBack pass, or a closer file read):**
- Which of the 22 non-colorblind screens really rely on color alone.
- Whether single-site `contentDescription` helpers label every cell; real TalkBack focus order and announcements.
- Actual touch target sizes on dense boards at 360dp, and behaviour at 2.0x text.
- Flash rate of celebration effects; haptic strength; emoji glyph rendering on old devices.
- Whether any in-game tier replaces the missing Hub difficulty for Hangman, Crossword, Word Search, Solitaire.
- Hint and undo rows for party-pack games (substring matches in the matrix).

## 6. Suggested order

1. S: Reversi reduced-motion plus live regions; delete dead `game_*` keys; `locales_config.xml`; parity and literal-ratchet JVM tests (M5 a, b).
2. M: Air Hockey semantics and assist; `a11yStatus` helper for the 21 silent screens; scaled-sp helper for 24 files; colorblind pass on the 22 screens.
3. L: Spanish for the 27 EN-only screens and the shared lobby screens (batch by family, same as the wave rollout), then pt-BR.
4. M-L: "Little players" mode (icon chrome, read-aloud coach), TalkBack and 2.0x font sweeps on real devices, Compose accessibility checks in CI.
