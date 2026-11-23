# Quality scorecard G1: board classics

Scope: `chess`, `checkers`, `reversi`, `mancala`, `connectfour`, `dotsandboxes`, `tictactoe`, `dominoes`, `gomoku`, `hex`, `ninemensmorris` (route and engine package `morris`), `hareandhounds`, `chinesecheckers`, `ludo`.
Tree: `Z:\GameSuite-wt-game-kit`, branch `session/wave-3`. Date 2026-11-21. Read-only audit: no Gradle, no device, no emulator, no TalkBack run. Every claim is "by reading" or a grep count.
Bar: `docs/roadmap/QUALITY-BAR.md` (8 gates). Defect checklist: `docs/audit/06-new-games/*.md`. Context: `docs/audit/04-accessibility-ux-i18n.md`. Target look: `docs/design/canvas/Main.dc.html` (Midnight Club).

Scoring: 0-3 per axis (3 = meets the bar, 0 = absent or broken). Evidence tags: **V** = verified by reading the cited line, **S** = suspected (inferred, needs a device or profiler to confirm).
Effort tags: S under 1 day, M 1-3 days, L a week or more. Paths below are relative to `app/src/main/java/com/gamesuite/` unless they start with `shared/`, `app/src/test/` or `res/`. Chess, Checkers, Mancala and Tic-tac-toe engines live in `shared/src/commonMain/kotlin/com/gamesuite/games/<pkg>/`.

A 3 only says "no defect found by reading". The device gates (screenshots in portrait/landscape/large font/dark, TalkBack, Fold, tablet) are not part of the 5 axes and were not checked.

## Summary

| game | gameplay | feel | robustness | a11y_i18n | tests | total /15 |
|---|---|---|---|---|---|---|
| morris (nine-mens-morris) | 3 | 3 | 3 | 3 | 3 | **15** |
| hex | 3 | 3 | 3 | 2 | 3 | **14** |
| hareandhounds | 3 | 3 | 3 | 3 | 2 | **14** |
| checkers | 3 | 3 | 3 | 1 | 3 | **13** |
| chinesecheckers | 2 | 3 | 3 | 2 | 3 | **13** |
| ludo | 3 | 3 | 2 | 2 | 3 | **13** |
| gomoku | 2 | 3 | 2 | 2 | 3 | **12** |
| tictactoe | 3 | 2 | 3 | 1 | 3 | **12** |
| chess | 2 | 3 | 2 | 2 | 2 | **11** |
| mancala | 3 | 2 | 3 | 1 | 2 | **11** |
| reversi | 3 | 1 | 2 | 1 | 3 | **10** |
| connectfour | 2 | 2 | 3 | 1 | 2 | **10** |
| dotsandboxes | 2 | 2 | 3 | 1 | 2 | **10** |
| dominoes | 2 | 3 | 2 | 1 | 2 | **10** |

Pattern. The six wave-3 games (gomoku, hex, morris, hareandhounds, chinesecheckers, ludo) are clearly ahead: they use `fitBoard` with a scroll fallback, read pulse animations as a `State` inside draw lambdas, resolve every user-facing string through `stringResource` with ES parity, and have fuzz and "hard beats easy" tests. The seven first-wave games (chess to dominoes) have good engines and tests but still ship English in the game screen, with 1 to 13 `R.string` keys each (V: `res/values/{checkers,connect4,dots,dominoes,chess}_strings.xml`; `reversi`, `tictactoe` have 1). Nobody applies the Midnight Club tokens inside a game (V: `MidnightClubTheme` only in `ui/profiles/*`). Nobody has a Compose UI or accessibility test (V: `app/src/androidTest` holds three rotation tests only).

Weakest five: reversi, dominoes, dotsandboxes, connectfour (all 10), then chess (11, with four axes below 3).

## Verified-by-reading facts used across games

- Bot searches run off the main thread in all 14 (V: `Dispatchers.Default` in Chess/Checkers/Mancala/Dots/TicTacToe screens, `playBotTurnAsync` in Reversi/ConnectFour/Gomoku/Hex/Morris/Hare/CC/Ludo). Only Dominoes calls `game.playBotTurn()` on the main thread (`ui/DominoesScreen.kt:237`); its bot is O(hand) so this is cheap.
- The shell forwards `onPause` to the active module and writes the save (V: `MainActivity.kt:275-278`, `core/GameSessionManager.kt:105-108`), and all 14 engines override `pause()`. Process-death resume therefore depends on the engines' `restore()`; all 14 validate roster and shape. Depth of validation differs (see per game).
- Match-end win/lose/draw sound and haptic is global (V: `MainActivity.kt:359-365`, `ui/ResultFeedback.kt`), so every game gets a result cue.
- `gameAccent(` is used only by Hub/menu/coach (V: `ui/GameCards.kt`, `GameHubScreen.kt`, `MainMenuScreen.kt`, `club/CoachCard.kt`). Inside the 14 screens the accent is a private palette per game.

---

## chess (11/15)

Files: `shared/.../games/chess/ChessGame.kt` (2023 lines), `ui/ChessScreen.kt` (1936), tests: `app/src/test/.../chess/*` (94 tests), `shared/src/commonTest/.../chess/*` (17), `shared/src/jvmTest/.../ChessLanMultiplayerTest.kt` (6), `androidTest/.../ChessRotationTest.kt` (2).

| axis | score | evidence |
|---|---|---|
| gameplay | 2 | V: promotion is auto-queen only, a deliberate omission (`ChessGame.kt:45-48`, `:372-375`, net request rejects other pieces `:1924`). Draws are complete (3-fold, 50-move clock, insufficient material, stalemate; `ChessDrawsAndClockTest`), HARD is iterative deepening with a wall-clock budget that discards the unfinished depth (`:794-840`, `:1569-1580`), clocks exist, hint and undo exist. V: `decodeSnapshot` checks king counts and field ranges but not legality (side not to move in check, pawns on rank 1/8, castling rights vs rook/king placement; `:1144-1169`). |
| feel | 3 | V: wood bitmaps (`ChessScreen.kt:775-778`), promotion flip (`:626-631`), staggered move/capture sounds (`:639-644`), 6 haptic signals, motion tiers, ambient music, idle pulse read in the draw phase (`:812`), `LocalReducedMotion` and `LocalGameSpeed` read. |
| robustness | 2 | V: search is cancellable and re-validated (`ChessScreen.kt:280-286`); guest sync retried (`:290-296`). Gap: the clock keeps running while the chrome menu, How to Play or Leave dialog is open because `GameChrome` has no pause hook and the screen never calls `game.pause()` itself (`ChessScreen.kt:996`, `uikit/GameChrome.kt:80-94`); only app-level `onPause` stops it (V). 1936-line screen with 5 `BoxWithConstraints`: S for recomposition cost, not measured. |
| a11y_i18n | 2 | Good: square label plus `stateDescription` (`:779-800`), polite live region (`:455`, `:1309`), colorblind read, 48dp chrome. Gaps (V): `helpTitle = "How to Play Chess"` (`:300`, `:996`), `"Opponent disconnected"` (`:448`), buttons `Text("Undo")` `Text("Hint")` (`:1256`, `:1263`), square labels built in English `White`/`Black` (`:1702-1706`), material line English with `%.1f` (`:1559-1564`). 32 English prose literals; 13 ES keys in `chess_strings.xml`. |
| tests | 2 | 117 tests, self-play never illegal (`ChessGameTest.kt:366-395`), clocks, LAN, networked. Gap (V): perft only from the start position to depth 3 (`ChessGameTest.kt:93-99`), no Kiwipete or promotion/en-passant perft, no snapshot fuzz, no check that HARD beats EASY. |

Top 5 fixes
1. **Promotion choice (M).** Add `promotion: PieceType` to the request and `ChessMove` selection, show a 4-piece picker in `ChessScreen`, accept N/B/R/Q in the net handler (`ChessGame.kt:372-375`, `:405`, `:419`, `:1924`); update the captured-material maths that assumes auto-queen (`:873-887`).
2. **Perft and legality suite (S).** Add Kiwipete, positions 3-5 and a promotion position at depth 3-4 to `ChessGameTest.kt:93`, and add the three legality checks to `decodeSnapshot` (`:1144`).
3. **Pause clock while any chrome dialog or menu is open (S, shared).** Give `GameChrome` an `onBlockingUiChanged(Boolean)` callback and call `game.pause()/resume()` from it; wire Chess first.
4. **ES extraction (M).** Move the 32 literals and `squareDescription` / `pieceTypeName` into `res/values{,-es}/chess_strings.xml` (`ChessScreen.kt:300,448,996,1256,1263,1559,1702`); use `pluralStringResource` for the material line.
5. **Distinct capture / check / castle cues (M).** Replace layered `playTap`/`playPlace` calls (`ChessScreen.kt:639-644`) with new `SfxKind`s (see shared findings).

## checkers (13/15)

Files: `shared/.../games/checkers/CheckersGame.kt` (1312), `ui/CheckersScreen.kt` (1756). Tests (excluding Chinese Checkers): about 115 in `app/src/test/.../checkers` and `shared/src/{common,jvm}Test`.

| axis | score | evidence |
|---|---|---|
| gameplay | 3 | V: mandatory capture option, multi-jump forced piece, draw by no-progress and repetition, three levels (random / greedy / iterative minimax depth 9 under a time budget) (`CheckersGame.kt:1100-1110`, `:1176-1230`, budget notes `:1264-1277`). V: strongest snapshot validation of the set (cells parity, forced piece has a jump, terminal flag agrees with position, draw reason agrees with counters; `:580-644`). |
| feel | 3 | V: bitmap-textured board (9 bitmap/shader refs), gradients, 5 particle/shake refs, 4 sound kinds, 6 haptic signals, reduced motion and speed read. |
| robustness | 3 | V: bot plan computed on `Dispatchers.Default`, re-checked on apply (`CheckersScreen.kt:311-318`); match-over guard tests (`CheckersMatchOverGuardTest`). No defect found by reading. |
| a11y_i18n | 1 | V: only 4 keys in `checkers_strings.xml`; 34 English prose literals: `"That move could not be sent."`, `"You must jump. Pick a piece that can capture."` (reject messages), `helpTitle = "How to Play Checkers"` (`:1016`), `Text("Undo")` (`:1156`), `Text("Back to Menu")` (`:1235`), `"Opponent disconnected"` (`:604`), `contentDescription = "$label captured: $count"` (`:1728`). Fixed `fontSize = 9.sp` ignores the text-size setting (`:1185`). Semantics themselves are good (24 semantics sites, 5 live regions). |
| tests | 3 | V: draw/options, match-over guard, resume/undo/hint, networked (29), opening seat, search budget and timing. Perft-style known-value counts for draughts would be a bonus, not a gap in the audit sense. |

Top 5 fixes
1. **ES extraction (M).** The 34 literals above, the engine reject reasons, the `"Waiting for the host"` strings.
2. **Replace `fontSize = 9.sp` (S)** at `CheckersScreen.kt:1185` with a `MaterialTheme.typography.labelSmall` style or a density-scaled size.
3. **Known-value perft for 8x8 American draughts (S):** 7, 49, 302, 1469, 7361 at depths 1-5, plus a quiescence-free reference game; put in `shared/src/commonTest/.../CheckersGameTest.kt`.
4. **Compose a11y test (M):** `enableAccessibilityChecks()` on a 64-square board at 360dp width (touch target, missing label); none exists in `androidTest`.
5. **Variants (L, optional):** International (10x10, flying kings) or Russian; only if the Hub wants more breadth.

## reversi (10/15)

Files: `games/reversi/ReversiGame.kt` (1035), `ui/ReversiScreen.kt` (695). Tests: 164 across 8 files including a 1584-line reference-engine test and a 1250-line edge-case test.

| axis | score | evidence |
|---|---|---|
| gameplay | 3 | V: reference-engine and perft-style tests (`ReversiReferenceEngineTest`), board sizes 6/8/10, depths 2/4/6 per tier (`ReversiGame.kt:59-75`), iterative hint under a 400 ms budget (`:627-635`), pass handling, snapshot requires a legal move when not over (`:529-551`). |
| feel | 1 | V: no `LocalReducedMotion` read at all (0 hits in `ReversiScreen.kt`). V: discs are a 350 ms colour cross-fade (`ReversiScreen.kt:585-594`), no flip, no placement drop, no particles, no Canvas, no gradient. V: sound only on the human's tap (`:346`, `sounds.playTap()`), none for the bot's move, flips, pass or game over; haptics only at board end and pass (`:195-199`). V: portrait-only `Column` (`:244-252`), no landscape or Fold branch. Private cream palette, no per-game accent. |
| robustness | 2 | V: bot search async and dropped on stale position (`:148-170`), taps gated on legality. S: the guest "Waiting for the host" screen (`:170-190`) has no re-request or timeout, unlike Chess (`ChessScreen.kt:290-296`). Reversi is the `GameChrome` pilot and has no 400 ms think floor beyond `gameSpeed.scale(800)` which drops to 440 ms at Quick (fine). |
| a11y_i18n | 1 | V: no `liveRegion` and no `stateDescription` (0 hits); cell labels English `"Dark disc"`, `"Legal move"`, `"Place disc"` (`:561-577`); `"Hint"`/`"Undo"` (`:533-543`); help text and turn lines English (`:178`, `:222`, `:250-278`); 19 literals, 1 string key. Colourblind-safe by luminance (documented `:76-78`). |
| tests | 3 | V: strongest suite of the older games (fuzz 39 hits, perft 18, snapshot, undo, networked 26, board size, hub options). |

Top 5 fixes
1. **Feel pass (M):** flip animation (`graphicsLayer rotationY` with the `gameSpeed` duration, instant under Reduced Motion), placement drop, and `LocalReducedMotion` read; today the 350 ms tween always runs (`ReversiScreen.kt:589`).
2. **Sound for every move (S):** play a placement sound keyed on `s.lastMove` and a flip tick keyed on flipped count so the bot, pass and game-over are audible (`:346` is the only call).
3. **A11y + ES (M):** polite live region on `StatusRow`, announce "X placed at row r column c, flipped n", `stateDescription` for legal/hint, extract the 19 literals and the 6 cell strings.
4. **Landscape / Fold layout (M):** reuse `AdaptiveTwoPane`/`FoldAware` as Chess and Checkers do; the vertical status stack (`:251-300`) leaves a small board in landscape and falls back to scroll.
5. **Designed board (L):** felt-and-wood canvas with a real disc render and the Reversi accent, replacing 64-100 `Box` cells with a single `Canvas` plus semantics overlay.

## mancala (11/15)

Files: `shared/.../games/mancala/MancalaGame.kt` (1015), `ui/MancalaScreen.kt` (1484). Tests: 108 (`MancalaVariantsTest` 24, networked 40, resume/undo 23, guard 6, opening seat 8, shared 7).

| axis | score | evidence |
|---|---|---|
| gameplay | 3 | V: three tiers (random / greedy / iterative alpha-beta with a node budget `MANCALA_NODE_BUDGET`, `MancalaGame.kt:213-322`), variants, snapshot checks stone conservation and last-sow path (`:641-664`), hint uses the HARD search off-thread. |
| feel | 2 | V: sowing cascade animation, 9 animation sites, gradients, 11 haptics. Gap: sounds are only `CardSounds.playTap()/playPlace()` per sown stone with an explicit note that no per-call pitch exists (`MancalaScreen.kt:372-373`, `:434-440`); no particle/celebration beyond the global result cue and one chime (`:443`); capture and extra-turn have no distinct cue. |
| robustness | 3 | V: 17 in-flight guard references, bot and hint on `Dispatchers.Default` (`:205`, `:501`), plan is re-validated (`applyBotMove(plan, move)`). |
| a11y_i18n | 1 | V: `helpTitle = "How to Play Mancala"` and 3 multi-line English rules (`:513-516`), `"Opponent disconnected"` (`:849`), `Text("Play Again")`/`Text("Back to Menu")` (`:869`, `:1005`, `:1016`), store label built in English (`:1467`). 4 ES keys, 23 literals. Semantics are good (16 semantics, 7 stateDescription/liveRegion). |
| tests | 2 | V: broad but little rules fuzz: `MancalaGameTest.kt` has 5 tests, 1 random-use hit in all Mancala tests, no self-play, no snapshot fuzz, no HARD-beats-EASY test. |

Top 5 fixes
1. **ES extraction (M):** the literals above plus `stoneCountLabel` plurals via `pluralStringResource`.
2. **Sowing sound with pitch ramp (S):** add `rate` to the pit-drop sound so a sow climbs in pitch; add a capture thunk and an extra-turn chime (`MancalaScreen.kt:372`).
3. **Fuzz and self-play (S):** random legal play to termination, snapshot round-trip at every ply, HARD-vs-EASY series in `MancalaGameTest.kt`; copy the shape of `MorrisGameTest.kt` fuzz.
4. **Celebration (S):** `ParticleBurst` on capture and on a win (used by ConnectFour and Dots, not here).
5. **Tablet layout check (M):** `fitBoard` is used once; verify a 6x2 board on tablet and Fold inner screen and add a rotation case to `MancalaDominoesRotationTest`.

## connectfour (10/15)

Files: `games/connectfour/ConnectFourGame.kt` (1005), `ui/ConnectFourScreen.kt` (1049). Tests: 97 (board variants 22, game 22, hub 6, networked 28, resume/undo/hint 19).

| axis | score | evidence |
|---|---|---|
| gameplay | 2 | V: alpha-beta at depth 2/4/6, iterative deepening with a time budget on bigger boards (`ConnectFourGame.kt:728-790`), hint, undo, draws. Gap (V): `decodeSnapshot` checks sizes, roster and indices but not gravity (no floating discs), not disc counts versus turn, not the winning line versus the cells (`:280-297`). No fuzz tests (0 random hits), 1 self-play hit. |
| feel | 2 | V: drop animation with `Animatable` (`ConnectFourScreen.kt:909`), 11 particle/shake refs, celebration. Gap: bot and human moves share one `playTap` (`:208`, `:424`), no landing thunk; chime only on a win (`:301`); no loss cue beyond the global one; no gradients, shadows or bitmaps (flat discs; S for how they look, V for the counts). |
| robustness | 3 | V: bot async via `playBotTurnAsync` (`:207`); 4 in-flight guards; no defect found by reading. |
| a11y_i18n | 1 | V: `helpTitle = "How to Play Connect Four"` plus a one-line English help (`:223`, `:347`), `"Opponent disconnected"` (`:236`, `:263`), `Text("Undo")` (`:752`); 15 literals, 2 ES keys (`connect4_strings.xml`). Per-column semantics, 3 live regions, colour-plus-shape read. |
| tests | 2 | V: good board-variant and networked coverage; missing fuzz, snapshot tamper tests and strength ordering. |

Top 5 fixes
1. **Snapshot consistency (S):** reject floating discs, count mismatches (`|red - yellow| <= 1` relative to `currentPlayerIndex`) and a `winningLine` that is not four equal cells; add tamper tests (`ConnectFourGame.kt:280`).
2. **ES extraction (M):** 15 literals and the help text.
3. **Landing sound and loss cue (S):** `SOLID_THUNK` on disc landing keyed on the drop `Animatable` end; reuse for the bot.
4. **Fuzz and strength tests (S):** random legal play to completion on all board specs, HARD beats EASY over a series, snapshot round-trip each ply.
5. **Designed discs (M):** gradient rim and highlight, board frame with the Connect Four accent instead of flat circles.

## dotsandboxes (10/15)

Files: `games/dotsandboxes/DotsAndBoxesGame.kt` (951), `ui/DotsAndBoxesScreen.kt` (1301). Tests: 99 (grid sizes 27, game 25, hub 9, networked 18, resume/undo/hint 20).

| axis | score | evidence |
|---|---|---|
| gameplay | 2 | V: three tiers exist but HARD is not strong: it takes every completing edge greedily, then a random safe edge, then the smallest-opening sacrifice (`DotsAndBoxesGame.kt:807-822`); no double-cross / chain-control logic, so a person who knows the long-chain rule beats it. V: `decodeSnapshot` checks lengths and roster but not box owners against drawn edges or scores against owners (`:390-417`). |
| feel | 2 | V: sound for box claim, bad edge and win (`DotsAndBoxesScreen.kt:350-371`), 14 haptic refs, 12 particle refs, draw cache. S: edge-draw animation is minimal (2 `Animatable` sites); no wood/ink material look beyond the accent (3 accent refs). |
| robustness | 3 | V: hint and bot computed on `Dispatchers.Default` (`:197`, `:266`), chained extra turns handled in one effect (`:249-266`). No defect found. |
| a11y_i18n | 1 | V: `helpTitle = "How to Play Dots and Boxes"` (`:283`, `:400`), `"Opponent disconnected"` (`:293`, `:323`), `Text("Back to Menu")` (`:456`); 19 literals, 1 ES key (`dots_strings.xml`). Edge semantics present (13 semantics, 5 contentDescription). |
| tests | 2 | V: strong on grid sizes and draw/tie (205 draw-related mentions) but 0 fuzz and 1 self-play; no HARD-beats-MEDIUM test; no chain-rule test. |

Top 5 fixes
1. **Real HARD (M):** count long chains and loops, apply the "keep control / double-deal" rule (decline the last two boxes of a chain when it flips the parity); validate with a HARD-beats-MEDIUM series (`DotsAndBoxesGame.kt:807`).
2. **ES extraction (M):** 19 literals and the help text.
3. **Snapshot consistency (S):** every owned box must have all four edges drawn, `scores` must equal owner counts (`:390`).
4. **Fuzz tests (S):** random legal play to full board with box-sum invariants, snapshot round-trip each ply.
5. **Edge-ink animation (S):** animate the line draw and the box fill with `gameSpeed` and Reduced Motion.

## tictactoe (12/15)

Files: `shared/.../games/tictactoe/TicTacToeGame.kt` (1212), `ui/TicTacToeScreen.kt` (1142). Tests: 132 (board size 32, host 22, guest 13, e2e 10, resume 21, lifecycle 5, hub 7, UI logic 12, LAN 6, shared 4).

| axis | score | evidence |
|---|---|---|
| gameplay | 3 | V: perfect minimax on 3x3 for every variant, budgeted iterative alpha-beta on 4x4/5x5 that discards unfinished depths (`TicTacToeGame.kt:396`, `:1081-1117`), misere and wild, hint, undo; snapshot checks size, variant flags, cell codes, winning-line shape (`:262-283`). |
| feel | 2 | V: 3 sound kinds including a bot-placement tick (`TicTacToeScreen.kt:395-400`), 5 haptic kinds, mark glyph animation. Gap: 0 particle/shake refs, 0 gradients, shadows or bitmaps (flat canvas marks, S for how it looks); no per-game accent inside the screen. |
| robustness | 3 | V: hint and bot on `Dispatchers.Default` (`:340`, `:393`), 6 in-flight guards; no defect found. |
| a11y_i18n | 1 | V: `"Opponent disconnected"` (`:436`), `Text("Undo")`, `Text("Hint")`, `Text("Play Again")`, `Text("Back to Menu")` (`:1088-1138`), help title/text built in English (`:874-900`), `"Misere mode: completing a line loses!"`; 24 literals, 1 ES key. Semantics good (15 semantics sites, 4 live regions). |
| tests | 3 | V: widest of the older set, including networked host/guest/e2e and a UI-logic unit test. |

Top 5 fixes
1. **ES extraction (M):** 24 literals incl. `helpTitleFor`/`helpTextFor`/`roundResultText` (`TicTacToeScreen.kt:833-900`); `possessive()` (`:795`) is English-only grammar and must become a format string.
2. **Win-line celebration (S):** `ParticleBurst` plus a draw-the-line animation (Dots and C4 already have the effect).
3. **Material look (M):** chalk/ink or wood marks with a gradient, per-game accent for the grid.
4. **Snapshot count check (S):** X and O counts must match `currentPlayer` and `startingPlayer` (`TicTacToeGame.kt:262`).
5. **Compose test (M):** 5x5 grid at 360dp, touch target and label check.

## dominoes (10/15)

Files: `games/dominoes/DominoGame.kt` (967), `ui/DominoesScreen.kt` (1684). Tests: 89 (rules 17, game 17, networked 32, resume/undo 16, hub 7).

| axis | score | evidence |
|---|---|---|
| gameplay | 2 | V: rules are correct (blocked-hand scoring, tie, double-six/nine, whole-set accounting in `decodeSnapshot`, `DominoGame.kt:270-306`). Gaps: CPU levels are thin: MEDIUM plays the first legal option, HARD plays the heaviest tile (`:699-706`), EASY is random; none tracks the boneyard, the passes or the ends it leaves. No Hint button by design (`DominoesScreen.kt:1187`), though the engine has all legal plays. |
| feel | 3 | V: deal-in reveal, flying tile with hit-stop, toppling cascade on a human win, 25 sound and 22 haptic refs, doubles get a heavier sound (`DominoesScreen.kt:122-157`, `:309`, `:502-544`), reduced motion and speed read (`dominoPause`). |
| robustness | 2 | V: logic coupled to English display text: `s.lastAction == "Game started"` decides the deal-in reveal (`DominoesScreen.kt:493`, set at `DominoGame.kt:424`); it breaks if that string is ever localised. V: bot runs on the main thread (`:237`, cheap). 13 in-flight guards otherwise. |
| a11y_i18n | 1 | V: `helpTitle` and one-line help (`:251`, `:627`), `Text("Undo")`, `Text("Play Again")`, `Text("Back to Menu")` (`:1197`, `:1440`, `:1450`), `"Left end, exposed pip value"` (`:829-834`), `"1 pip"`/`"$pips pips"` hand-rolled plural (`:1311`), engine `lastAction` strings; 29 literals, 11 ES keys. |
| tests | 2 | V: strong rules and network suites but 0 fuzz and 0 self-play, no bot-strength test, only 8 difficulty mentions. |

Top 5 fixes
1. **Smarter bot (M):** HARD tracks pips played and passes, prefers plays that leave the opponent's ends dry, keeps a balanced suit mix; MEDIUM sheds high tiles (`DominoGame.kt:699-706`); test HARD beats MEDIUM beats EASY.
2. **Hint (S):** engine already knows `legalPlays`; add `requestHint()` and the chrome `onHint` (`DominoesScreen.kt:1187` says it is omitted by choice).
3. **Decouple logic from text (S):** add a `dealSerial` or `handNumber` to `DominoState` and drive the deal-in off it (`DominoesScreen.kt:493`); move engine `lastAction` to a typed event.
4. **ES extraction (M):** 29 literals, plural for pips, tile and end descriptions.
5. **Fuzz tests (S):** random legal play over double-six and double-nine to a finished hand, tile conservation each ply, snapshot round-trip.

## gomoku (12/15)

Files: `games/gomoku/{GomokuEngine,GomokuGame}.kt`, `ui/GomokuScreen.kt` (691). Tests: 53 (engine 15, game 22, networked 11, remote validation 3).

| axis | score | evidence |
|---|---|---|
| gameplay | 2 | V: free-style and exact-five options, three levels distinct (depth 1/2/4, widths 8/10/9, `GomokuEngine.kt:219-225`), EASY misses threats on purpose, snapshot re-derives win line and last move (`GomokuGame.kt:212-231`). Gaps: no opening restriction (swap/Swap2/Renju forbidden moves) for the first-player advantage on 15x15 (V: grep finds only `exactFive`). V: the deadline is checked inside `negamax` and returns a static eval mid-tree (`GomokuEngine.kt:161-164`), so a cut-off search mixes deep and shallow scores; Chess, Checkers, Morris discard the unfinished depth. |
| feel | 3 | V: wood grain, gradient frame, stones with overshoot drop (`GomokuScreen.kt:506-523`), pulse read as a `State` inside draw/graphicsLayer (`:455-457`), celebration burst, 4 sound kinds, reduced motion and speed read. |
| robustness | 2 | V: `maxOf(fit.cellPx, GOMOKU_MIN_CELL_DP)` re-introduces the floor-above-ceiling bug that `fitBoard`'s own KDoc describes (`GomokuScreen.kt:271`, `shared/.../BoardFit.kt:6-19`); S whether it clips or scrolls on a Fold cover screen. 225 cells each with a `clickable`, `semantics` and a per-stone `Animatable` (S: allocation on resize). |
| a11y_i18n | 2 | V: fully localised (44 `stringResource`, 52 ES keys), `onClickLabel` duplicates the content description so TalkBack reads each cell twice (`:515-516`), 22dp minimum cell (`:80`), mitigated by tap-then-confirm (`:274`) which touch exploration bypasses. 225 individual TalkBack stops, no `collectionInfo`. |
| tests | 3 | V: fuzz (15 hits), hard-beats-easy both colours (`GomokuEngineTest.kt:177`), easy-misses-threat, remote validation, snapshot. |

Top 5 fixes
1. **Discard unfinished depth (S):** make `negamax` throw/flag on deadline and keep the previous complete root result, as `ChessGame.kt:794-840` does (`GomokuEngine.kt:161`, `:185-215`).
2. **Opening rule (M):** add a "Swap" (pie) or Swap2 option in the Hub like Hex has; keep free-style as default.
3. **Honour `fitBoard` (S):** replace `maxOf(...)` with `fit.meetsMinimum` and a scroll container (the pattern in `ReversiScreen.kt:306-318`) (`GomokuScreen.kt:271`).
4. **Single board node for TalkBack (M):** replace 225 stops with one board node plus `collectionInfo` and custom actions "go to row/column"; drop the duplicated `onClickLabel` (`:515`).
5. **Zoom mode (M):** pinch/zoom so a 15x15 board can use 40dp+ cells on a phone.

## hex (14/15)

Files: `games/hex/{HexEngine,HexGame}.kt`, `ui/HexScreen.kt` (716). Tests: 52 (engine 17, game 24, networked 11).

| axis | score | evidence |
|---|---|---|
| gameplay | 3 | V: pie rule with swap evaluation (`HexEngine.kt:211-225`), shortest-path evaluator via 0-1 BFS, sizes, HARD beats EASY (`HexEngineTest.kt:208`), snapshot checks cells, turn and the pie state (`HexGame.kt:216-237`). Note: same mid-tree deadline pattern as Gomoku (`HexEngine.kt:162-163`), a minor quality risk rather than a rules fault. |
| feel | 3 | V: tile drop with overshoot (`HexScreen.kt:551`), pulse read in draw (`:544-545`), gradients, 4 sound kinds, celebration, rotated board view. |
| robustness | 3 | V: no `fitBoard` call, but the own fit math scrolls below the minimum (`:276-290`); a `confirmNeeded` path for small hexes (`:272`). No defect found by reading. |
| a11y_i18n | 2 | V: fully localised (47 `stringResource`, 53 ES keys), per-hex `onClick` semantics (`:633`), live region (`:323`). Gap: minimum hex width is 20dp (`HEX_MIN_HALF_WIDTH_DP = 10f`, `:77`) so targets are far below 48dp; single-tap confirm mitigates. No `collectionInfo`. |
| tests | 3 | V: engine and game fuzz (15 hits), snapshot, network, pie. |

Top 5 fixes
1. **Discard unfinished depth (S):** same change as Gomoku (`HexEngine.kt:162`).
2. **Use `fitBoard` or a hex variant of it (M, shared):** add `fitHexBoard` to `shared/.../uikit/BoardFit.kt` with a test so Hex stops re-deriving the maths (`HexScreen.kt:276-283`).
3. **TalkBack row/column navigation (M):** group cells by row with `collectionInfo` and announce edge ownership ("top-left edge, red connects top to bottom").
4. **Hint explanation (S):** show why (shortest path length before/after) in the hint toast.
5. **Compose a11y test (M):** 11x11 at 360dp.

## ninemensmorris (15/15)

Files: `games/morris/{MorrisEngine,MorrisGame}.kt`, `ui/NineMensMorrisScreen.kt` (930). Tests: 63 (engine 23, game 22, hub 6, networked 12) with fuzz of 29 hits.

| axis | score | evidence |
|---|---|---|
| gameplay | 3 | V: 3/6/9/12 men, flying option, mill protection, no-legal-move loss, quiet-plies draw (`MorrisEngine.kt:127-135`), HARD is iterative depth 6 that falls back to the best one-ply move when time runs out (`:219-243`), snapshot rebuilds and validates the position (`MorrisGame.kt:314-337`). |
| feel | 3 | V: move and capture animation with ease (`NineMensMorrisScreen.kt:102-110`), mill glow (`:806-812`), pulse as a `State` read in Canvas lambdas (`:797-801`), whoosh and thunk and chime, accent used, reduced motion and speed read. |
| robustness | 3 | V: bot async and stale answers dropped (`:373-388`, test `playBotTurnAsync plays once and a stale answer is dropped`), `fitBoard` with 48dp floor and scroll (`:658-662`), hint off the main thread. |
| a11y_i18n | 3 | V: fully localised (67 `stringResource`, 76 ES keys), per-point `clickable` with description (`:871-872`), live regions (`:177`, `:619`), 48dp floor. |
| tests | 3 | V: includes `fuzz - snapshots round-trip and undo restores at every ply`, `hard beats easy over a short series`, `restore rejects corrupt, foreign and inconsistent snapshots`. |

No axis below 3. Polish only (all S): the idle "thinking" alpha is read in composition (`NineMensMorrisScreen.kt:133-134`, one small composable); no draw by threefold repetition (only the quiet-plies limit; `MorrisEngine.kt:135`); one Compose a11y test.

Top 5 fixes
1. **Repetition draw (S):** add a position-key counter and a 3-fold rule beside `QUIET_LIMIT`.
2. **Read `ThinkingLine` alpha in a graphicsLayer lambda (S):** `NineMensMorrisScreen.kt:133`.
3. **Compose a11y test (M):** 7x7 at 360dp and 1.5x font.
4. **Capture-selection hint (S):** highlight removable men during the remove step for colour-blind users (shape plus label).
5. **Use this screen as the template (S):** extract its `BotTurnEffect` and per-point node into shared helpers (see shared findings).

## hareandhounds (14/15)

Files: `games/hareandhounds/{HareHoundsEngine,HareHoundsGame}.kt`, `ui/HareAndHoundsScreen.kt` (639). Tests: `HoundsEngineTest` 15, `HoundsGameTest` 18, `HoundsNetworkedTest` 8, plus hub spec (6) = 47.

| axis | score | evidence |
|---|---|---|
| gameplay | 3 | V: hounds cannot retreat, hare escape or 20 hound moves (`HareHoundsEngine.kt:39`, `:95`), EASY 40% random, MEDIUM depth 4 with noise, HARD solves the position (`:212-225`), snapshot validated through `validPosition` (`HareHoundsGame.kt:266-287`). |
| feel | 3 | V: 6 gradient refs, 7 accent refs, pulse in draw (`HareAndHoundsScreen.kt:553-554`), sounds, haptics, reduced motion and speed. |
| robustness | 3 | V: `fitBoard` 5x3 with 48dp floor (`:80`, `:430-434`), 10 in-flight guards, bot async. |
| a11y_i18n | 3 | V: fully localised (46 `stringResource`, 27 ES keys), per-point description (`:604-605`), live region (`:386`). Only 2 `contentDescription` sites and no `stateDescription` (S: richer cell state would help). |
| tests | 2 | V: 47 tests but no HARD-beats-EASY test, no snapshot-tamper test for the engine, 0 draw/edge-case mentions; smallest suite in the wave-3 set. |

Top 5 fixes
1. **Strength-ordering test (S):** HARD hounds beat EASY hare and vice versa over a series.
2. **Snapshot tamper tests (S):** mirror `MorrisGameTest` `restore rejects corrupt...` for `validPosition`.
3. **Per-cell `stateDescription` (S):** hare / hound / legal / hint / last move as a state, not part of the label.
4. **Fuzz (S):** random legal play to termination under both role assignments with the `MAX_HOUND_MOVES` cap.
5. **Role-swap explanation (S):** the first-run coach should say the hounds side is solved in the hounds' favour on Hard; set expectations on Easy hare.

## chinesecheckers (13/15)

Files: `games/chinesecheckers/{ChineseCheckersRules,ChineseCheckersGame}.kt`, `ui/ChineseCheckersScreen.kt` (719), `ui/ChineseLudoUi.kt` (shared). Tests: 65 (rules 23, game 26, networked 13, remote validation 3).

| axis | score | evidence |
|---|---|---|
| gameplay | 2 | V: 2-6 seats, short and standard piece counts, move-limit and stuck endings, snapshot validated deeply including piece counts per seat and "current player has a move" (`ChineseCheckersGame.kt:276-306`). Gap: HARD is a one-ply scorer plus the best follow-up hop for the mover (`ChineseCheckersRules.kt:262-277`), no opponent modelling, no blocking, so it plays a fair race but not a strong one. EASY/MEDIUM/HARD are distinct and `hardBeatsEasyOverSeveralGamesFromBothSides` passes (`ChineseCheckersRulesTest.kt:279`). |
| feel | 3 | V: marble rendering (`ChineseLudoUi.kt:136`), multi-hop flight animation (`ChineseCheckersScreen.kt:210-216`), burst (`ChineseLudoUi.kt:158`), pulse as a `State` read in draw (`:541-545`), reduced motion and speed read. |
| robustness | 3 | V: bot async, `fitPitch` with scroll fallback (`:415-418`). S: `while (flight != null) delay(40)` busy-poll (`:240`) would stall the bot if a cancelled animation ever left `flight` set. |
| a11y_i18n | 2 | V: fully localised (34 `stringResource`), `AccessibleMovePanel` only when touch exploration is on (`:454-459`), colour plus 6 seat glyph shapes. Gaps: the board is one canvas with one description (`:541-560`), 20dp pitch (`CC_MIN_PITCH_DP`, `:100`) so direct taps are small; `isYouName` compares to the literal `"You"` (`ChineseLudoUi.kt:301-304`), a fragile localisation hack. |
| tests | 3 | V: rules, game, networked and remote validation, fuzz 14 hits, hard-beats-easy. |

Top 5 fixes
1. **Real lookahead (M):** 2-ply with a distance-sum + straggler + blocking evaluator (opponent ladder squares), budgeted like Morris (`ChineseCheckersRules.kt:262`).
2. **Replace the busy-poll (S):** await a `CompletableDeferred`/`snapshotFlow { flight == null }` instead of `while (flight != null) delay(40)` (`ChineseCheckersScreen.kt:240`).
3. **Name handling (S):** carry a `isLocalYou` flag in `PlayerInfo` instead of string equality (`ChineseLudoUi.kt:301`).
4. **Pinch-zoom (M):** let the 121-hole board go to 40dp pitch without leaving the screen.
5. **Compose a11y test (M):** accessible panel visible under touch exploration, 1.5x font.

## ludo (13/15)

Files: `games/ludo/{LudoRules,LudoGame,ChineseLudoHubSpecs}.kt`, `ui/LudoScreen.kt` (769), `ui/ChineseLudoUi.kt`. Tests: 78 (rules 21, game 29, networked 11, remote validation 6, hub 11).

| axis | score | evidence |
|---|---|---|
| gameplay | 3 | V: need-six and exact-finish options, three sixes forfeit, captures and safe squares, a roll log in the snapshot and an injectable `Random` for replays (`LudoGame.kt:90-101`), `LudoRules.isConsistent` validates positions on restore (`LudoRules.kt:160`), hint computed from a seeded HARD choice (`LudoGame.kt:367-368`). |
| feel | 3 | V: die spin tween (`LudoScreen.kt:205`), token flight with return animation, landing pulse, 14 sound refs, 11 haptics, reduced motion and speed read. |
| robustness | 2 | V: the idle pulse `.value` is read in composition at the top of `LudoBoardCanvas` (`LudoScreen.kt:605-608`), so the whole board composable recomposes every frame, unlike the five sibling screens that pass a `State` into draw lambdas (e.g. `GomokuScreen.kt:455-457`). S: same busy-poll as CC (`:283`, `while (flight != null || rolling) delay(40)`). 5 in-flight guards. |
| a11y_i18n | 2 | V: fully localised (40 `stringResource`, 34 ES keys), token buttons give a screen-reader path (`:496-509`), die description (`:477-480`). Gaps: board canvas has one description (`:567`, `:619`); `fontSize` badge built from the board unit ignores text-size (`:711`); `isYouName` string hack (shared). Live regions only through `ClStatusLine`. |
| tests | 3 | V: rules and game with seeded random (57 hits), remote validation, networked, hub specs. No CPU-strength test (S). |

Top 5 fixes
1. **Pass `pulse` as `State<Float>` (S):** `LudoScreen.kt:605-608`, read it inside the Canvas lambdas.
2. **Replace the busy-poll (S):** `LudoScreen.kt:283`.
3. **Roll announcements (S):** make the die result a live region ("Red rolled 4; tokens 1 and 3 can move") instead of only the static die description.
4. **Strength test and tuning (S):** HARD beats EASY over seeded series; assert MEDIUM captures when available.
5. **Text-size for the token number badge (S):** `LudoScreen.kt:711` scale by `LocalDensity.fontScale`.

---

## Shared-component findings (fix once, lifts many games)

Ordered by value for effort.

1. **Localised engine messages (M, lifts 8+ games).** Engines emit English display strings: `lastAction = "Game started"` (`DominoGame.kt:424`, `ChessGame.kt:1306`), `"Opponent disconnected"`, `"X wins!"` and similar (12 `lastAction = "..."` sites in `games/{reversi,connectfour,dotsandboxes,dominoes}`, 5 more in shared Chess and Mancala), and screens even branch on them (`DominoesScreen.kt:493`). Fix: engines publish a typed `GameEvent`/message key plus args; one `GameMessages` mapper in `ui` turns it into `stringResource`. Removes the logic-on-text coupling and unlocks ES for all first-wave games.
2. **No shared "ES gate" for the first wave (M).** Eight screens still pass English `helpTitle`/`helpText` into `GameChrome` (V: Chess `:300`, Checkers `:1016`, Reversi `:178`, Mancala `:513`, C4 `:223`, Dots `:283`, Dominoes `:251`, TTT `:874-900`), although the Hub coach and `core/GameCoach` strings are already EN+ES. Fix: let `GameChrome` take `gameId` and resolve help from `GameCoach` (the wave-3 games already use `rememberClCoach`, `ChineseLudoUi.kt:186`). Add the audit-04 ratchet test (fail on new prose literals in `ui/`).
3. **`GameChrome` has no "blocking UI" signal (S).** Menu, How to Play and Leave dialogs do not pause the game (V: `uikit/GameChrome.kt:88-110`; Chess clock keeps running). Add `onBlockingUiChanged: (Boolean) -> Unit` and call `GameModule.pause()/resume()`. Also: `leaving` is set to true and never reset (`GameChrome.kt:97-103`); if `onAbort` does not navigate away (S), Back is dead for the rest of the screen's life.
4. **`GameChrome` is minimal (S).** No in-game quick mute for sound/haptics, no restart or "new game", and the speed entry is a separate optional `GameSpeedMenuItem`. The chrome's `⋮` button uses `MaterialTheme` colours unless each game passes its palette, so the 14 games do not share a look (Midnight Club tokens are not used here).
5. **`fitBoard` covers square grids only (S-M).** `BoardFit.kt` takes `columns`/`rows`; Hex and Chinese Checkers have 0 `fitBoard` calls and re-derive their own fit (`HexScreen.kt:276-283`, `ChineseCheckersScreen.kt:415-418`), and Gomoku re-applies `maxOf(fit.cellPx, MIN)` (`GomokuScreen.kt:271`), the exact bug the KDoc warns about. Fix: add `fitHexBoard` and `fitRadial`, and a `ScrollableBoardFit` composable that wraps the "meetsMinimum else scroll" idiom now copy-pasted in Reversi (`:306-318`), Morris (`:658-662`), Hare (`:430-434`), Hex and CC.
6. **Sound vocabulary is too small and material-blind (M).** Board games use `CardSounds` clips (`card_place`, tap, draw, shuffle; no per-call pitch, noted in `MancalaScreen.kt:372` and `ChessScreen.kt:639-644`) plus 5 `SfxKind`s (`audio/ProceduralSfx.kt:47-59`). There is no wood clack, stone click, marble, seed rattle, capture, check, king, mill or flip cue, and result cues reuse a pitched `card_place` (`ui/ResultFeedback.kt:20-45`). Add `SfxKind`s with a `rate`/`pan` parameter (pan already exists) and an `SfxKind.CAPTURE`, `PIECE_WOOD`, `PIECE_STONE`, `FLIP`, `CHECK`.
7. **One bot-turn effect copied 14 times (S-M).** Each screen has its own `LaunchedEffect(state, ...)` with `delay(gameSpeed.scale(N))`, a `botThinking` flag and a try/finally. Think times differ (Chess 700, Reversi 800, Morris 750, Gomoku 750 with a 400 floor, CC 650 floor 400). Extract `BotTurnEffect(state, isBotTurn, minThinkMs, run)` with the 400 ms floor (QUALITY-BAR gate 2), cancellation, and the single in-flight rule. Reversi, C4, Dots, Morris, Hare have no explicit floor; at Quick speed they reach about 410-440 ms (V arithmetic), which just clears it today.
8. **Search budget is implemented four times (S).** `SearchBudget` in Chess (`ChessGame.kt:707-732`), `CheckersSearchBudget`, ConnectFour's own `SearchBudget`, Mancala's node budget, Morris' `Ctx`; Gomoku and Hex use an unguarded `System.nanoTime() > deadline` that returns static eval mid-tree. Move one `SearchBudget` (clock, cancel flag, check-every-N-nodes) to `shared/commonMain`, make all engines discard unfinished depths, and reuse `ChessSearchBudgetTest`.
9. **Snapshot validation boilerplate (S).** The roster check (`players.size == roster.size && ids and isBot match`) is repeated in all 14 `decodeSnapshot` functions with small drifts (C4 omits `sessionWins` checks, Dominoes omits `nextStartingPlayerIndex`). Add `validateRoster(snap.players, context.players)` and `validateSessionTally(...)` to `core/`, plus a generic `snapshotFuzz(game)` test helper (random play, round-trip each ply) that Morris already has in `MorrisGameTest` and the first-wave games lack.
10. **Accessibility helpers (M).** 0 `collectionInfo` anywhere; 225 separate TalkBack stops on Gomoku; per-cell `onClickLabel` duplicating `contentDescription` (Gomoku `:515`). Add `Modifier.boardCell(row, col, label, state, onClick)` and `Modifier.boardSemantics(rows, cols)` that sets `collectionInfo`/`collectionItemInfo` and puts selected/legal/hint in `stateDescription`; a `Modifier.a11yStatus(text)` live-region helper (Reversi has none; wave-3 games use `ClStatusLine`). Also an `enableAccessibilityChecks()` Compose test per family: no UI test exists for any of the 14 (V: `androidTest` has `ChessRotationTest`, `TicTacToeCheckersRotationTest`, `MancalaDominoesRotationTest` only).
11. **Per-frame hygiene is a copy-paste idiom (S).** Five wave-3 screens pass the infinite-transition `State<Float>` into draw lambdas; Ludo reads `.value` in composition (`LudoScreen.kt:605-608`), Morris's `ThinkingLine` too (`:133-134`). Provide `rememberPulse(reduced, min, max, ms): State<Float>` and a `Modifier.pulseAlpha(state)`.
12. **Per-game accent and Midnight Club are not applied in-game (M).** `GameThemes.accents` (contrast-tested by `GameThemeTest`) is used only by Hub/menu; each screen has a private palette with its own hex literals (7-23 `Color(0x...)` per screen) and `GameChrome` takes `buttonFill`/`buttonContent` per call. Provide a `GameStage(gameId)` wrapper that supplies `LocalGameAccent`, the ink-dark surface and ivory text tokens, and has `GameChrome` read them.
13. **Text-size (S).** Only Material typography follows the in-app text-size setting; the fixed `.sp` sites in these games are `CheckersScreen.kt:1185` and `LudoScreen.kt:711`. Everything else in these 14 uses typography styles, so this is small here.
14. **Pause is app-level only (S).** `onPause` pauses the module (V) but nothing pauses the game on in-app dialogs (finding 3), Fold posture change recreate is not distinguished, and no screen reads `ON_STOP` itself. Fine for untimed board games; Chess is the one with a clock.
15. **Compose and snapshot tests for screens (M).** The only UI-layer unit test is `TicTacToeScreenLogicTest`; extract pure view-model helpers (like `roundResultText`, `localSeatFor`) from the other screens so they can be JVM-tested.
