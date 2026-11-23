# Quality scorecard: synthesis of 81 games (2026-11-21)

Method: seven read-only reviews (G1 to G6) scored every game 0-3 on gameplay, feel, robustness, a11y/i18n, tests (max 15). Everything is by reading code and tests; nothing was run or seen on a device. Raw data: ALL-GAMES.csv and the G*.md files.

## Headline numbers
- Average total 11.7 / 15. By axis: gameplay 2.53, feel 2.35, robustness 2.27, a11y/i18n 2.02, tests 2.51.
- Games scoring 2 or lower per axis: gameplay 33, feel 49, robustness 50, a11y/i18n 62, tests 36. Sixteen games score 1 on a11y/i18n (English-only screens).
- Strongest: Blackjack 15, Spider Solitaire 15, Nine Men's Morris 15, Tank Battle 14, Hex 14, Darts 14, Carrom 14, Billiards 14, Mini Golf 14, Toy Soccer 14, Slot Cars 14, Fishing 14, Pig Dice 14, Hare and Hounds 14, Flow Lines 14, Texas Hold'em 14, War 14.
- Weakest 20 (<=10): Air Hockey 7; Crossword, Hangman, Paddle Duel, Party Toolkit, Solitaire 8; Minesweeper, Reaction Duel, Tower Defence, Whack-a-Mole 9; Color Flood, Connect Four, Dominoes, Dots and Boxes, KenKen, Light Trail, Nonogram, Reversi, RPS Showdown, Sliding Puzzle 10.

## Systemic findings (one fix lifts many games), ranked by reach
1. English-only screens (62 games <=2 on a11y/i18n). Fix: engines emit typed messages, screens use string resources, a lint test bans hard-coded English in ui/*.kt.
2. No overlay-open pause signal in GameChrome: real-time games keep running behind help, leave and menu dialogs (Air Hockey, Speed, Toy Boxing, Whack-a-Mole, Shooting Gallery, Chess clock, Edge Match, party games with empty pause()). Fix: one `overlayOpen` hook that pauses/resumes any game.
3. Per-frame whole-screen recomposition (Air Hockey, Tower Defence, Toy Soccer/Tennis, Slot Cars, Fishing, Six-Ball, UNO effect keys). Fix: a shared lifecycle-gated frame clock and state read only in draw lambdas.
4. Six separate card renderers, fixed-sp card text, four sound clips. Fix: one deck component (colour-blind suits, text-scale aware) and a sound-event facade with per-material sounds.
5. Daily puzzles are not stable: they change with difficulty (all but Word Guess), with the first click (Minesweeper) and use local date not shared UTC. Fix: shared DailySeed helper.
6. Puzzle generators run on the main thread (KenKen, Nonogram, Sudoku, Edge Match) and can throw: shared async-generation helper with deadline and bundled fallback.
7. Palm rejection only in 4 games; three untested rotation implementations; blank first frame in Reaction Duel, Paddle Duel, RPS, Tap Race.
8. Personal-best writes in cancellable LaunchedEffect (7 of 8 casual screens).
9. No score card / sticker moment outside the five newest games; no per-seat handicap in pure-arcade games; no "what this teaches" metadata.
10. fitBoard supports only square boards; no 48 dp hit-slop helper.

## Concrete defects worth fixing early
- Hangman and Word Search sample the raw 359k-word list: slurs/profanity can appear (kid safety). Fix: shared blocklist and curated kid lists.
- Word Guess: nine answers are not in the guess dictionary (detox, geeky, inbox, latte, penne, pesto, ramen, rehab, wimpy): rounds cannot be won.
- Crossword canPlace lets runs with no clue exist; zero generation tests.
- Minesweeper daily layout depends on the first reveal; no chording.
- Connect Four snapshot accepts floating discs; Dots and Boxes Hard has no chain logic; Dominoes Medium plays the first legal tile.
- Reversi (the pilot) ignores Reduced Motion, no bot-move sound, no landscape layout.
- Color Flood has no move limit or lose state; Sliding Puzzle ignores taps on non-adjacent tiles.
- Paddle Duel Easy is too hard; Reaction Duel false-starts on a resting palm; Whack-a-Mole pause() is empty.

## Proposed refinement program (all branches/sandbox; no installs)
Phase R1 shared platform: GameChrome v2 (overlay pause + typed messages), frame clock, DailySeed, async generation, deck component, sound/haptic facade, hit-slop and non-square fitBoard, string lint.
Phase R2 correctness and safety: Hangman/Word Search lists, Word Guess dictionary, Crossword, Minesweeper daily, snapshot validations, bots (Dominoes, Dots and Boxes), pause() implementations.
Phase R3 appearance: board materials, unified deck, motion/sound vocabulary, score card + sticker moment, family-by-family polish led by the weakest scorecards.
Phase R4 localisation sweep and a11y (text scale, TalkBack), then screenshot tests (needs an owner decision on a JVM screenshot library since device installs are off the table).
