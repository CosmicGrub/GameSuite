# G4a quality scorecard: word and logic-puzzle games

Scope: `wordtiles hangman wordsearch crossword wordguess sudoku kenken kakuro nonogram` (route ids are `word-tiles`, `word-search`, `word-guess`; the rest match). Branch `session/wave-3`. Read-only audit: nothing built, run or installed. Every claim is tagged **[V]** verified by reading code or assets (a few by shell counts over `assets/`), or **[S]** suspected (needs a device or a run).
Rubric: 0 absent/broken, 1 major gaps, 2 solid with gaps, 3 meets `docs/roadmap/QUALITY-BAR.md`. Gate 6 (multiplayer) and gate 8 (device evidence) are out of scope except Word Tiles netcode, which only has unit tests.
Note: `docs/design/canvas/Main.dc.html` is a 7.8 KB shell and contains no mockup for any of these nine games, so "designed look" is judged against the QUALITY-BAR gate 3 wording only.

## Summary

| game | gameplay | feel | robustness | a11y_i18n | tests | total /15 |
|---|---|---|---|---|---|---|
| wordtiles | 2 | 3 | 3 | 2 | 3 | 13 |
| kakuro | 2 | 2 | 2 | 2 | 3 | 11 |
| sudoku | 2 | 2 | 1 | 2 | 3 | 10 |
| kenken | 2 | 2 | 1 | 2 | 3 | 10 |
| nonogram | 2 | 2 | 1 | 2 | 3 | 10 |
| wordguess | 2 | 2 | 2 | 2 | 2 | 10 |
| wordsearch | 1 | 2 | 3 | 2 | 2 | 10 |
| hangman | 1 | 2 | 2 | 2 | 1 | 8 |
| crossword | 1 | 2 | 2 | 2 | 1 | 8 |

Headline findings (details below):
1. **Profanity and slurs reach kids** [V]. `assets/words.txt` (dwyl list) contains `cunt` (line 72255, 4 letters) and `faggot` (line 106612, 6 letters). Hangman EASY samples lengths 4..5 and MEDIUM 6..8 from it with no filter (`HangmanGame.kt:155-159,188-199`); Word Search places raw dictionary words on the board (`WordSearchGame.kt:333-336`).
2. **Word Guess has 9 unwinnable secrets** [V]. `detox geeky inbox latte penne pesto ramen rehab wimpy` are in `word_guess_answers.txt` but not in `words.txt`, and guesses are validated against `words.txt` (`WordGuessGame.kt:117,272`). The player can never type the answer. About 1 in 257 words, and a daily word can hit it.
3. **Crossword generator can create unclued runs** [V]. `CrosswordGame.kt:629-643` (`canPlace`) checks only letter agreement and one intersection, never side neighbours or collinear extension.
4. **Sudoku, KenKen and Nonogram generate on the main thread, and KenKen/Nonogram/Kakuro end in an uncaught `error()`** [V]. Screens call `startMatch` straight from a `LaunchedEffect` (`SudokuScreen.kt:262,321`, `KenKenScreen.kt:273,340`, `NonogramScreen.kt:247,313`); the generators' failure path is `error(...)` (`KenKenGame.kt:827`, `NonogramGame.kt:761`, `KakuroGame.kt:863`).
5. **No localisation in 8 of 9 screens** [V]. Zero `stringResource` in Hangman, Sudoku, KenKen, Kakuro, Nonogram, Word Guess, Word Search and Crossword (about 230 English prose literals). Word Tiles has 5 keys in `values/wordtiles_strings.xml` and `values-es/wordtiles_strings.xml`, all network or turn messages. Every word list is English.
6. **Daily puzzles are not the same for everyone** [V]. Every daily route passes `epochDay` as the seed but the generator also reads `settings.defaultCpuDifficulty` (`SudokuScreen.kt:251`, `KenKenScreen.kt:258`, `KakuroScreen.kt:214`, `NonogramScreen.kt:236`, `WordSearchScreen.kt:221`), so two players with different default difficulty get different "Today's" puzzles. Word Guess is the exception: the secret depends only on the seed, difficulty changes only the guess count (`WordGuessGame.kt:174-176,225`).
7. **No hardware-keyboard path anywhere** [V]: zero `onKeyEvent`/`onPreviewKeyEvent` in all nine screens (grep).
8. **`LocalGameSpeed` is read only by Word Tiles** [V] (2 hits; 0 in the other eight).

## Hangman

- **Gameplay 1.** `HangmanGame.kt:155-159`: difficulty is only word length (4-5, 6-8, 9-11). `:188-199` mixes a 15-word curated list per tier with up to 15 words per length straight from the 359k dictionary, filtered only by `isLetter`; no blocklist, no frequency signal. [V] `cunt` and `faggot` are present in the asset. Obscure words are as likely as `apple`, so Hard is unfair and Easy can be inappropriate for the kid audience. `:86-89` declares `SINGLE_DEVICE_PASS_AND_PLAY` but the screen has no word-entry field (0 `TextField` in `HangmanScreen.kt`) and the Hub spec exposes solo only (`GameHubSpecs.kt:434`), so a 2-player mode is dead surface. No daily, no categories, English only. Hints, resume and tally are good (`:268-275`, `:294-370`).
- **Feel 2.** Chalkboard look, flying glyphs, shake and flash, gallows stroke draw, hit-stop, haptics (12 sites) and sfx (19) [V]. Honours `LocalReducedMotion` (2 sites) but not `LocalGameSpeed` (0). No hardware keyboard. Dictionary load is correctly on IO (`HangmanScreen.kt:163`), so the older audit item 5.3 no longer applies to Hangman.
- **Robustness 2.** `startMatch` (called from the effect on the main dispatcher) runs `wordPoolFor`, which does `filter().shuffled()` over three lengths of the 359k list, tens of thousands of strings per "New Word" [S for the ms cost]. `pause()/resume()` are empty, fine for an untimed game.
- **A11y/i18n 2.** Good: key descriptions with status, live regions on word and counter, check/cross marks in colour-blind mode, luminance-based key text, 48dp keys (`HangmanScreen.kt:~880-925`). Gaps: 15 English prose literals and 0 `stringResource` (`:377-390,817-820,969-977`); two fixed `.sp` (`:643,650`); `if (n == 1) "hint" else "hints"` (`:817-818`); keyboard is `'A'..'Z'` only (`:875`), no Ñ and no Spanish list.
- **Tests 1.** One file, 15 tests, all resume/hint (`games/hangman/HangmanResumeHintTest.kt`). No test of `guessLetter`, win/loss thresholds, `pickWord` history, or pool contents (a pool test would have caught the profanity gap).

Top 5 fixes
1. **S** Add a shared `WordSafety` blocklist (profanity, slurs, sexual and drug terms) and filter `wordPoolFor`, Word Search placement and Tiles bot sampling; add a unit test that scans every generated pool against it.
2. **M** Replace raw-dictionary sampling with a bundled, tiered, frequency-ranked list (about 1,500 words per tier) plus categories (animals, food, places); keep curated words as the Easy anchor.
3. **S** Move `wordPoolFor` off the main thread (precompute pools once in the IO load step) and drop the per-call `shuffled()` of large lists.
4. **M** Extract the literals plus key descriptions into `hangman_strings.xml` with ES, use `pluralStringResource`, scale glyph sp with `LocalDensity`.
5. **S** Add tests (guess/win/lose transitions, hint on last letter, `pickWord` avoids history, pool never contains blocked words) and a hardware keyboard via `onPreviewKeyEvent`. Either delete `SINGLE_DEVICE_PASS_AND_PLAY` or add a "pick a word for your friend" entry (M).

## Sudoku

- **Gameplay 2.** Solid engine: randomized fill then carve with an MRV uniqueness check and a 200k call budget; an `INCONCLUSIVE` result reverts the removal, so a shipped puzzle is always provably unique (`SudokuGame.kt:666-679,710-790`) [V]. Gaps: the three levels differ only in clue count 42/32/26 (`:169-173`), with no technique-based grading [V]. Hint picks the selection or the fewest-candidate cell (`:345-372`) and fills it; it does not teach a technique. Notes with auto-clean of peers (`:500-505`), undo, mistake count and instant wrong-cell marking are present; there is no auto-check toggle. Daily seed is deterministic (`Random(seed)`, `:382`) but difficulty-coupled (`SudokuScreen.kt:251`).
- **Feel 2.** Camera shake on a mistake, win burst, chime and haptic (`SudokuScreen.kt:280-286,384`), side-by-side tablet layout at width >= 600dp (`:475`), colour-blind outlines and corner marks (`:1163-1195`). Gaps: no `LocalGameSpeed`; no digit-first highlight mode; no hardware digit keys; no drag-select for notes.
- **Robustness 1.** `game.startMatch(...)` runs from the effect and from `startRound` on the main dispatcher (`SudokuScreen.kt:262,321`); HARD removes up to 55 clues, each with a uniqueness check of up to 200k steps, so worst case is millions of steps on the UI thread [V for call sites, S for ms]. No cancellation. Resume and undo are well covered.
- **A11y/i18n 2.** Cell semantics with a description, `onClickLabel`, live region for mistakes (`:865-871`), colour-blind cues, 12 `48.dp` sites. Gaps: 40 English literals, 0 `stringResource` (`:844-871,1426,1556`); cell digit size is `(cellSize*0.5f).sp` (`:1214`), so system font scale can overflow a fixed-dp cell [S]; no `collectionInfo`; default-locale `"%d:%02d".format` clock (`:681`).
- **Tests 3.** 38 tests; uniqueness independently re-verified, seed sweeps for every tier, daily reproducibility, undo/hint/resume rules (`SudokuGameTest.kt`, `SudokuResumeUndoHintTest.kt`). Missing: a technique-level grader.

Top 5 fixes
1. **M** Run generation off the main thread via a shared `rememberAsyncPuzzle` (placeholder, `withContext(Default)`, cooperative cancel), plus a wall-clock deadline and a precomputed fallback puzzle per tier.
2. **S** Make the daily independent of the player's default: fixed tier per weekday (or one daily per tier) and show the tier in the tag.
3. **M** Grade difficulty by technique (singles only for Easy/Medium, pairs and pointing for Hard) with a human-style solver; make the hint explain the technique.
4. **L** Localise: move 40 literals plus cell/key descriptions to `sudoku_strings.xml` + ES; locale-aware clock formatter.
5. **S** Hardware keyboard (1-9, arrows, Backspace, N for notes) and a "highlight digit" tap on the number pad; use `LocalGameSpeed` for the win burst.

## KenKen

- **Gameplay 2.** Latin square plus hand-authored cage templates, three per tier, with uniqueness checked by a budgeted MRV solver and cage pruning (`KenKenGame.kt:925-1015`, templates from `:1100`) [V]. Gaps: only 3 cage layouts per tier (9 total), so shapes repeat quickly [V]; sizes 4/6/9 (`:356`), where 9x9 with a 200k budget per attempt over up to 500 attempts (`:1021-1022`) is heavy; on total failure the engine calls `error(...)` (`:827`) [V]. Hints, notes, undo, daily seed and wrong-cell marking are present. Daily difficulty-coupled (`KenKenScreen.kt:258`).
- **Feel 2.** Same bar as Sudoku (haptics 23 sites, sfx 8, reduced motion 4); side-by-side at 480dp (`KenKenScreen.kt:477`). Cage speech and borders are good. 9x9 cells on a 360dp phone are about 38dp, under the 48dp target [S]. No `LocalGameSpeed`.
- **Robustness 1.** Generation on the main thread (`KenKenScreen.kt:273,340`); the `error()` at `KenKenGame.kt:827` is uncaught inside an effect, so a failed HARD generation crashes the app [V reading; S that it is reachable].
- **A11y/i18n 2.** Per-cell description including cage speech (`:685,1259`), live regions (4), colour-blind cues; but 33 English literals with 0 `stringResource`, `"%d:%02d".format` (`:646`), duplicated digit pad (`:1570`).
- **Tests 3.** 52 tests incl. uniqueness re-verified across tiers and seeds, template partition and connectivity checks, undo/hint/resume.

Top 5 fixes
1. **M** Shared async generation + deadline + fallback (see S1); catch generation failure in the screen and fall back to a precomputed puzzle instead of crashing (S).
2. **M** Procedural cage topologies (random polyomino partitions limited to size 4) behind the same uniqueness check, replacing 3 templates per tier.
3. **S** Make the daily tier-independent.
4. **S** Enforce a minimum cell size for 9x9 (scroll or zoom below about 40dp) and verify on the cover screen.
5. **L** Localise and adopt the shared digit pad.

## Kakuro

- **Gameplay 2.** Hand-authored templates (3 per tier, shared bounding box per tier), digit fill and uniqueness verification, and a clue-progress model (`KakuroRuns.kt`) that flags repeats and completed runs [V]. Gaps: low template variety; the generator can run up to `MAX_GENERATION_ATTEMPTS = 20_000` (`KakuroGame.kt:1141`) before `error(...)` (`:863`); no technique grading; daily difficulty-coupled (`KakuroScreen.kt:214`). Hint picks the run with the fewest unknowns [V by test names].
- **Feel 2.** Loading placeholder, completed-clue highlight, sfx 8 / haptics 14, reduced motion 4, side-by-side layout (`KakuroScreen.kt:394`); no `LocalGameSpeed`, no hardware keys.
- **Robustness 2.** The only unbounded-generator puzzle that generates off-main with a placeholder and a `generating` guard (`KakuroScreen.kt:229,269-276`). Gaps: `repeat(MAX_GENERATION_ATTEMPTS)` has no `ensureActive`, so leaving the screen does not stop a long search (`KakuroGame.kt:839`) [V]; an exception from `generate` is uncaught in both effects [V].
- **A11y/i18n 2.** The richest semantics in the set (4 `stateDescription`, 6 live regions), but 39 English literals and 0 `stringResource`.
- **Tests 3.** 55 tests across engine, resume/undo/hint and `KakuroRunsTest`.

Top 5 fixes
1. **S** Add `ensureActive()` per attempt and a wall-clock deadline; catch failure and use a bundled fallback puzzle.
2. **M** Add more templates or a procedural black-cell layout; add a per-tier daily.
3. **S** Tier-independent daily.
4. **L** Localise 39 literals.
5. **S** Hardware keyboard (digits, arrows) and the shared number pad.

## Nonogram

- **Gameplay 2.** Random grids at `FILL_PROBABILITY = 0.45f` (`NonogramGame.kt:745,976`) accepted only if unique; Easy and Medium additionally need line-solvability (no guessing); Hard accepts any unique grid [V]. Problems: the grid is noise, not a picture, yet the Hub tagline says "Reveal a picture from number clues" (`GameHubSpecs.kt:444`) [V]; Hard 15x15 may require guessing. Drag-paint strokes, X marks, per-stroke undo, restart and hint are strong (test names). Daily difficulty-coupled (`NonogramScreen.kt:236`).
- **Feel 2.** Long-press drag painting with axis lock (`NonogramScreen.kt:1188-1230`), fill/cross tool toggle, sfx 10, haptics 10. No `LocalGameSpeed`.
- **Robustness 1.** `startMatch` on the main thread (`:247,313`); 15x15 may try up to 500 grids x 200k steps (`NonogramGame.kt:744-761,973-974`); `error()` on exhaustion [V]; the author's comment and `02-performance.md:18` cite a historic 2.5 s.
- **A11y/i18n 2.** One invisible clickable node per cell with row/column description, polite live region (`NonogramScreen.kt:1266-1314`), colour-blind marks; but 225 individually focusable nodes at 15x15 with no grouping, 30 English literals, 0 `stringResource`, 1 fixed `.sp` (`:938`).
- **Tests 3.** 48 tests across engine, paint strokes and resume; includes uniqueness and line-solvable checks.

Top 5 fixes
1. **M** Off-main generation + deadline + fallback via the shared helper.
2. **L** Generate from a bitmap bank (pixel-art icons at 5/10/15) filtered by the same uniqueness check, so completion reveals a picture and the tagline is true.
3. **S** Auto-mark completed rows/columns and grey finished clue numbers.
4. **S** Tier-independent daily.
5. **L** Localise; add row/column navigation semantics.

## Word Guess

- **Gameplay 2.** Good two-tier design (answer list vs guess dictionary), duplicate-letter scoring with hand-verified tests, deterministic and fair daily (secret depends only on the seed; difficulty only changes the guess count 8/6/5, `WordGuessGame.kt:174-176,225`) [V]. Defects: **9 answers are not valid guesses** (`detox geeky inbox latte penne pesto ramen rehab wimpy`, computed with `comm` over the two assets), so those rounds are unwinnable [V]; no Spanish list; no hard-mode rule, and the hint just names a letter at a position (`:495-515`).
- **Feel 2.** Tile flip with stagger and row shake (`WordGuessScreen.kt:138-139,213-221,1025-1064`), colour-blind corner marks, per-key best state, sfx/haptics, reduced-motion path. Gaps: on-screen QWERTY only (`:94`), no hardware keyboard, no `LocalGameSpeed`.
- **Robustness 2.** Dictionary and answer list loaded on IO (`WordGuessScreen.kt:180`). `WordGuessAnswerList.ensureLoaded` is not synchronized and `loaded` is not volatile (`WordGuessAnswerList.kt:23-37`), so concurrent calls can double-parse; `answers.random(random)` throws on an empty list if called before load [V]. Resume covered.
- **A11y/i18n 2.** Tile and key descriptions, announcements (`:1072,1170`), colour-blind marks; 28 English literals, 0 `stringResource`; English-only dictionary and answers.
- **Tests 2.** 40 tests, thorough on scoring, but no test that every answer is in the guess dictionary (which would have caught the 9), and `WordGuessAnswerList` is untested.

Top 5 fixes
1. **S** Add the 9 words to `words.txt` (or drop them from the answer list) and add a unit test that reads both assets and asserts `answers ⊆ dictionary`.
2. **M** Spanish mode: ES answer list and dictionary, accent and Ñ handling, chosen by app locale.
3. **S** Synchronize/volatile the answer list and fail soft when empty.
4. **S** Hardware keyboard support (letters, Enter, Backspace).
5. **M** Optional hard-mode rule, streak and shareable result grid.

## Word Search

- **Gameplay 1.** Seeded generation exists (`WordSearchGame.kt:324-366`, daily via file-order pool, `:309-315`). Problems [V]: words are raw dwyl entries, so obscure, profane or non-words appear on the board and in the find-list; no check that a word occurs twice or that a placed word contains or reverses into another; difficulty varies grid 8/12/15, word count and directions (`:143-148`), which is reasonable; hint reveals the first cell of an unfound word (`:272-283`); English only; daily difficulty-coupled (`WordSearchScreen.kt:221`) and tied to the order of lines in `words.txt` (any asset edit changes the daily).
- **Feel 2.** One canvas with tap-start/tap-end and drag, plus a long-press mode so a plain drag pans (`WordSearchScreen.kt:869-916`), found-word feedback, 20 sound sites, reduced motion read in 2 places; no `LocalGameSpeed`, no distinct completion moment [S].
- **Robustness 3.** Dictionary load and generation are off-main with a "Loading" placeholder (`:229-236`), generation bounded (`attempts < 500`), no `error()`, resume covered. Minor: no cancellation.
- **A11y/i18n 2.** Per-cell nodes (`:856`), found-state `stateDescription` on the word list (`:1071`), colour-blind support; 25 English literals, 0 `stringResource`.
- **Tests 2.** 24 tests (generation, selection, hint, resume) using fake word lists; no test with the real asset for profanity or non-letters.

Top 5 fixes
1. **S** Run words through `WordSafety` and a common-words list (about 5-8k words ranked by frequency) instead of raw `wordsOfLength`.
2. **S** Reject candidates that are substrings or reversals of an already placed word and verify each word appears exactly once with a final scan.
3. **S** Tier-independent daily and a bundled, versioned daily word pool (no dependency on `words.txt` order).
4. **L** Localise plus ES themed word lists.
5. **M** Themed puzzles (animals, food) and a completion moment that uses `LocalGameSpeed`.

## Crossword

- **Gameplay 1.** Greedy placement from a hand-authored bank of 229 clue entries across three themes (`CrosswordClueBank.kt`, `CrosswordGame.kt:190-193`) [V]. `canPlace` (`:629-643`) requires only that overlapping letters agree and at least one overlap, so parallel words can sit side by side forming runs that are not entries, and a word can extend or abut a collinear word [V by reading; S for how often]. Fixed 15x15 grid with sparse placement, no trimming; the pool is shuffled with the unseeded RNG, so there is no daily. Clues are English. Hints are budgeted per tier (`:197-209`); answers are entered a whole word at a time through a dialog text field (`CrosswordScreen.kt:982`).
- **Feel 2.** Paper look, per-cell ink wave and colour animation, sfx (11) and haptics (13); letters are not typed into the grid directly, which breaks the crossword flow; heavy per-cell `Animatable`s and `animateColorAsState` (`:625-635,797`) for up to 225 cells (`02-performance.md:89`); reduced motion read in 2 places; no `LocalGameSpeed`; no explicit landscape split beyond `weight` (`:358-364`) [S].
- **Robustness 2.** Generation is cheap and synchronous; if the seed word is too long it falls back to `emptyGrid()` with no message (`CrosswordGame.kt:~565`) [V]; per-cell animation cost is the main risk [S].
- **A11y/i18n 2.** Cell semantics (`CrosswordScreen.kt:691`), clue row spoken text (`:814`), "Letters so far" announcement (`:974`); 19 English literals and an English-only clue bank.
- **Tests 1.** 21 tests, all resume and hint (`CrosswordResumeHintTest.kt`). Nothing on `generate`, `canPlace`, `submitAnswer`, numbering or win detection.

Top 5 fixes
1. **M** Fix `canPlace`: reject placements whose side neighbours are occupied (except at the crossing), whose ends touch another letter, or that overlap collinear entries; add a validator that scans every run of length >= 2 and asserts it is an entry, with property tests over all seeds and tiers.
2. **M** In-grid typing (custom keyboard or IME bound to the selected word), auto-advance, arrow and hardware keys; keep the dialog as fallback.
3. **L** Grow the clue bank (target 1,000+, tiered, EN + ES), add a seeded daily, and trim and centre the grid.
4. **S** Replace per-cell `Animatable` with one draw-layer animation and cap composed animation state.
5. **M** Localise literals; add tests for submit, numbering and win.

## Word Tiles

- **Gameplay 2.** Rules fidelity is good [V]: standard 100-tile bag with 2 blanks (`TileBag` counts sum to 100), the standard premium-square layout (12 TL, 16 DW, 8 TW, 24 DL cells match the standard board, `TileBoard.kt:10-21`), premiums only on newly placed tiles (`TileGame.kt:1081-1107`), 50-point bingo for 7 tiles (`:514-515`), cross-word validation and first-move centre rule (`:482-499`), end-game rack adjustment only when a rack empties (`:1109-1140`), consecutive pass/swap ending (tests), three bot tiers. Deviations: validation uses the dwyl list, which accepts non-Scrabble entries such as `aaa` and `abc` (`words.txt` lines 2 and 234) and has no challenge rule; a swap needs only `bag.size >= n` rather than 7 tiles (`:635-637`) [V]; no Spanish tiles or list.
- **Feel 3.** Wood-rim board, word-played tiers (ordinary/multiplier/bingo flourish, `TileGame.kt:72`), 18 sfx and 21 haptic sites, the only screen that reads `LocalGameSpeed` and scales bot think time (`TileGameScreen.kt:~270-278`), reduced motion, `fitBoard` sizing (`:1083`), hand-over cover for pass-and-play.
- **Robustness 3.** Bot search on `Dispatchers.Default`, awaited under a think delay and re-validated before applying (`TileGameScreen.kt:~262-275`, `TileBotOffThreadTest`), dictionary loaded on IO (`:219-224`), resume tests, host-authoritative networking with redaction tests (`TileNetRedactionTest`, `TileNetworkedTest`).
- **A11y/i18n 2.** Per-cell and rack descriptions (4 sites), live regions (7), 48dp sites (10), colour-blind read in 3 places. Localisation covers only 5 strings (`wordtiles_strings.xml` EN and ES) while about 22 other prose literals and every engine error string ("Must connect to an existing word", `TileGame.kt:483-499`) are English and come from the engine.
- **Tests 3.** 62 tests across resume, swap/pass rule, match-over guard, hub options, bot off-thread, networking, redaction.

Top 5 fixes
1. **M** Replace the dwyl validator with a real Scrabble-style list (TWL/SOWPODS or a curated superset) and add a challenge or lenient option; keep dwyl for the bot.
2. **S** Enforce the standard swap rule (bag >= 7) behind the existing hub option, and add tests.
3. **M** Move engine error strings to resource ids (sealed result type) and localise ES; ES tile distribution and list.
4. **S** Hardware keyboard for staging letters and Enter/Backspace.
5. **S** Golden-score tests on a published example game and a random legal-play fuzz test (never throws, snapshot round-trip).

## Shared-component findings

| # | finding | evidence | lifts |
|---|---|---|---|
| S1 | A shared `rememberAsyncPuzzle` (placeholder, `withContext(Default)`, `ensureActive`, wall-clock deadline, bundled fallback puzzle, catch around `error()`) is missing. Kakuro and Word Search each hand-roll it; Sudoku, KenKen, Nonogram do not. | `KakuroScreen.kt:229,269`; `WordSearchScreen.kt:229-236`; `SudokuScreen.kt:262,321`; `KenKenScreen.kt:273,340`; `NonogramScreen.kt:247,313`; also Minesweeper, Edge Match, Flow Lines per `02-performance.md:117` | 6+ games, removes 3 crash paths |
| S2 | A shared word-source layer: blocklist, tiered/frequency lists, per-locale lists, one off-main loader. Today Hangman, Word Search, Crossword and Tiles each sample raw `WordDictionary`. | `WordDictionary.kt:41-73`; `words.txt:72255,106612` | 4 word games, kid safety, ES |
| S3 | Localisation scaffolding: 8 of 9 screens have 0 `stringResource`. Common chrome strings (Hint, Undo, Mistakes: n, tier names, "Daily puzzle", clock, how-to-play) repeat in each file. | grep counts above; `docs/audit/04-accessibility-ux-i18n.md:46-57` | 8 games |
| S4 | A shared puzzle number pad, clock, hint banner, tier chips and daily tag. `"Digit $digit, all placed"` is copy-pasted in Sudoku and KenKen; five files each define a default-locale `"%d:%02d".format` clock; `HINT_VISIBLE_MS` is re-implemented per screen. | `SudokuScreen.kt:681,1426`; `KenKenScreen.kt:646,1570`; `KakuroScreen.kt:676`; `NonogramScreen.kt:608`; `WordGuessScreen.kt:717` | Sudoku, KenKen, Kakuro + Minesweeper etc. |
| S5 | A grid-semantics helper (`collectionInfo`, row/column headers, move-by-row actions). Row/column appear only inside description text; no `CollectionInfo` usage exists (audit 04 line 71). | `SudokuScreen.kt:1204`, `NonogramScreen.kt:1314`, `WordSearchScreen.kt:856`, `CrosswordScreen.kt:691` | all grid games |
| S6 | A keyboard-input modifier (`letterKeys`, `digitKeys`, arrows, Backspace/Enter). Zero `onKeyEvent` in the nine screens. | grep over `ui/*.kt` | all nine |
| S7 | A shared daily contract `DailyPuzzle(epochDay, tier)`. Today `MainActivity.kt` passes `LocalDate.now().toEpochDay()` at composition and the screen overlays the player's default tier. Make the daily tier-fixed. | `MainActivity.kt:775-784,941-950,1323-1395`; screen lines in headline 6 | 8 daily routes |
| S8 | `LocalGameSpeed` is not threaded into puzzle UI (only Tiles). A shared `scaledDuration()` helper for flashes and bursts would apply to all. | grep `LocalGameSpeed` per screen | all |
| S9 | `fitBoard` and `GameChrome` are used by 8 of 9 screens (Hangman measures its own slot) and look healthy; `fitBoard` lives in `shared/.../uikit/BoardFit.kt:43`. Add a minimum-cell-size contract (return a scroll flag below about 40-48dp) for KenKen 9x9 and Nonogram 15x15 on phones. | `BoardFit.kt:43` | Sudoku-class grids |
| S10 | `WordDictionary` heap and thread safety: `allWords`/`byLength` are plain `var`s published through a volatile flag; `isValidWord` silently returns false before load; `randomWords` filters 359k strings per call. Convert to a sorted, length-bucketed blob with binary search and a `loaded` precondition. | `WordDictionary.kt:17-73` | all word games, memory |

Not verified: on-device layout (portrait/landscape/Fold), real TalkBack traversal, generation latencies, Spanish coverage beyond asset names (`assets/` holds only `words.txt` and `word_guess_answers.txt`). Test counts are `@Test` annotations per file.
