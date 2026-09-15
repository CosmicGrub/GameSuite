# New Games Brainstorm — Wave 3

**Status:** Proposal for everything except Mastermind, which is now shipped (README item 28) —
the project owner's own "yes, go ahead with your top recommendation." Everything else here is
still just a proposal to react to and pick from.
**Date:** September 2026

## Context

Waves 1-2 (`docs/NEW_GAMES_BRAINSTORM.md`, README items 14-27) are completely shipped: 12 solo
puzzles, Tower Defence, Party Toolkit, and Edge Match's Custom Game Builder (square + hex, Penrose
formally investigated and retired). The catalog now covers 6 board games, 2 card games, 4 word
games, and 13 arcade/puzzle entries — real breadth, but with genuine gaps this wave picks up.

Every idea below is checked against the app's own three established `GameModule` shapes (see
`docs/ENGINE_DECISION.md`/README's own design notes) rather than proposed in the abstract:

1. **Solo puzzle** — the Minesweeper/Sudoku/Lights Out/.../KenKen shape: `matchOver` reset in both
   `init()`/`startMatch()`, injectable clock, pause/resume timer tracking, `startMatch(dailySeed)`
   support, own `PUZZLE_FOCUS`-aliased music.
2. **Two-player vs. a real bot** — the Dots and Boxes/Connect Four/Checkers/Chess shape: a
   `CpuDifficulty`-tiered bot (search depth or heuristic, scaled to the game's own tree size),
   session win/loss tallying, own bespoke music profile.
3. **Real-time tick-loop** — the Air Hockey/Breakout/Tower Defence shape: one state blob, one
   `tick(dtSeconds)`, one `Canvas`, `withFrameNanos` — validated by `docs/TOWER_DEFENCE_ADR.md`'s
   own real on-device measurement as comfortably performant well past what any of these games need.

Ideas that don't fit cleanly into one of these three get called out explicitly, not silently forced
into the nearest shape.

## Word / daily-challenge picks

### Word Guess (Wordle-style)
A hidden 5-letter word, 6 guesses, per-letter feedback (correct spot / wrong spot / not in word).
**Why now**: this genre didn't exist when wave 1 scoped Word Search/Crossword/Hangman/Word Tiles,
and it's become one of the most-played casual word-game shapes on mobile — a real, current gap,
not an oversight from before. **Architecture fit**: solo puzzle, and an unusually GOOD one — this
app already has a `startMatch(dailySeed)` convention on nearly every solo puzzle, and Word Guess's
own genre convention (one puzzle per day, everyone gets the same word) is a more natural fit for
that mechanism than almost anything else in the catalog; the daily seed IS the whole point here,
not an add-on. **Real design question**: an unlimited-replay mode (like every other solo puzzle's
"New Puzzle" button) sits oddly next to the genre's own "one guess-word per day" convention — worth
deciding whether non-daily play picks a fresh random word each time (this app's own established
idiom) or is deliberately left out in favor of daily-only, a genuine departure from every other
solo puzzle here. **Generation risk**: low — a curated common-word list (reusing Word Search's own
dictionary-curation precedent) plus a straightforward per-letter comparison; no uniqueness-proving
generator risk the way Sudoku/Nonogram/Kakuro had.

### ~~Mastermind~~ — shipped (README item 28)
A hidden sequence of 4-6 colored pegs; guess it within a limited number of tries, getting
black-peg (right color, right spot) / white-peg (right color, wrong spot) feedback per guess — no
letters, so it's a logic puzzle rather than a word game, but grouped here since it shares Word
Guess's exact feedback-driven-guessing shape. **Architecture fit**: solo puzzle, daily-seed-ready.
**Generation risk**: none — no uniqueness question at all (any random peg sequence is a valid,
fair puzzle; difficulty comes from tuning peg-color-count/sequence-length/guess-limit per tier, the
same "board size + parameter scaling" lever Edge Match/Color Flood already use), the lowest-risk
pick in this whole document. Built exactly as scoped, with EASY/MEDIUM/HARD scaling positions AND
colors together (4/4, 4/6, 5/8) rather than the loose "4-6" range floated here — see README item
28's own writeup for the real scoring-algorithm care (repeated-color double-counting) this still
needed despite the generator itself being risk-free.

## Board game picks

### Reversi / Othello
Flip-the-opponent's-discs-by-flanking on an 8×8 board. **Architecture fit**: two-player vs. bot —
fits directly alongside Connect Four's own precedent (a real minimax+alpha-beta bot scaled by
search depth per `CpuDifficulty` tier, since Othello's full game tree, while smaller than Connect
Four's, is still far too large to fully solve at shallow tiers). **Why it's a good next board
game**: distinct tactical shape from everything currently in the catalog (capture-by-flanking
rather than alignment-in-a-row like Tic-Tac-Toe/Connect Four, or area-claiming like Dots and Boxes)
— genuinely different reasoning, not a reskin. **Real design question**: corner/edge-weighted
positional heuristics are a well-known, important part of playing Othello well — worth deciding
whether the bot's evaluation function should encode that domain knowledge explicitly (more
authentic difficulty ladder) or rely on search depth alone (simpler, matches Connect Four's own
choice) before building.

### Yahtzee
Roll 5 dice (up to 2 rerolls per turn, choosing which dice to keep), fill a 13-category scorecard
over 13 turns. **Why it's a strong fit specifically for THIS app**: `PartyToolkitLogic.rollDice`
already exists and is exactly the dice-rolling primitive this needs — real code reuse, not just
thematic overlap. **Architecture fit**: an interesting real fork — solo-vs-your-own-scorecard (like
a solo puzzle, chasing a high score) or two-player-vs-bot (comparing final scorecards)? Genre
convention supports both; this needs the project owner's own call before scoping, the same way
Edge Match's rotate-vs-swap did. **New session shape either way**: this app has no existing
"category scorecard, turn-limited" idiom — closer to Tower Defence's own "genuinely new shape,
scope it fresh" experience than a drop-in fit to an existing pattern.

### Backgammon
Race both players' checkers around the board and bear them off, using dice rolls to move,
optionally with a doubling cube. **Why flagged but not recommended as a near-term pick**: real,
substantial engine complexity (legal-move generation with forced moves, bearing-off rules, the
doubling cube as a genuinely separate sub-system) — a bigger lift than anything else in this
document, closer to Chess's own scope than to Connect Four's. Worth having on the list, not worth
prioritizing ahead of smaller, equally-differentiated picks unless there's specific enthusiasm for
it.

## Arcade / constraint-puzzle picks

### 2048
Slide numbered tiles on a 4×4 grid in one of 4 directions; equal-value tiles merge and double;
reach 2048 (or keep going for a higher score). **Architecture fit**: solo puzzle, though the
"board state" shape (shift-and-merge in a direction, not tap-a-cell) is closer to Sliding Puzzle's
own move model than to Minesweeper/Sudoku's cell-based one — reuse that precedent, not a generic
one. **Generation risk**: essentially none (starts near-empty, new tiles spawn randomly each move —
no upfront puzzle-generation/uniqueness question at all, unlike Nonogram/Kakuro/KenKen). **Real
design question**: this app's own difficulty-tier convention (EASY/MEDIUM/HARD) doesn't map onto
2048's own genre shape naturally (it's normally one fixed ruleset played for a high score, not a
tiered puzzle) — worth deciding whether tiers vary board size (5×5/6×6 alternates) or whether this
is one of this app's first genuinely tier-less arcade high-score games (`BreakoutStatsStore`'s own
best-score-per-tier shape would need adapting, not reused as-is).

### Snake
Real-time: steer a growing snake around a grid, eating food, avoiding walls/its own tail.
**Architecture fit**: real-time tick-loop — directly reuses the Air Hockey/Breakout/Tower Defence
`tick(dtSeconds)` + `Canvas` pattern `docs/TOWER_DEFENCE_ADR.md` already validated as comfortably
performant, at a MUCH smaller entity count than that ADR's own stress test (a single snake body +
one food item vs. Tower Defence's 100+ simultaneous entities) — genuinely the lowest real-time-
performance-risk pick this app could make. **Why it's worth it despite being a very old, simple
genre**: this catalog's real-time games (Air Hockey, Breakout, Tower Defence) are all
physics/combat-flavored; Snake is a distinct "spatial planning under a growing constraint" texture
none of them share.

### Flow Free (pipe/path connection)
Connect matching-colored dot pairs on a grid with a single continuous path per color, with every
cell in the grid ultimately covered by some path. **Architecture fit**: solo puzzle. **Real
generation risk, the honest kind this document should flag up front rather than discover
mid-build**: proving a generated board has a UNIQUE solution (not just *a* solution) is a genuinely
harder constraint-satisfaction problem than any puzzle in this catalog except Sudoku/Nonogram's own
backtracking-verified generators — likely needs the same "generate via a known-solved construction,
scramble in a way that's guaranteed to preserve solvability" idiom those two puzzles already use
(build the solved path layout first, derive the puzzle FROM it, rather than generating blank grids
and searching for solutions after the fact) rather than being scoped as casually as e.g. 2048 or
Mastermind above. Worth an explicit note: if picked, this deserves the same adversarial-review
pass Sudoku/Nonogram's own generators got, not a lighter one.

### Mahjong Solitaire (tile-matching pairs)
Not real (4-player) Mahjong — the widely-known solo variant: tiles stacked in a themed layout,
remove matching pairs of currently-"free" tiles (unobstructed on at least one side, per the genre's
own accessibility rule) until the layout clears. **Architecture fit**: solo puzzle, daily-seed-
ready. **Real generation risk, same honesty as Flow Free above**: a randomly-shuffled tile layout
is NOT guaranteed to be solvable to completion (a real, well-known problem in this genre — you can
deal yourself into a dead end) — needs the same "build backward from a guaranteed-clearable
removal order, not forward from a random shuffle" construction idiom Edge Match/Lights Out/Color
Flood already use (scramble a known-good arrangement via a reversible operation) rather than a
naive random deal. Distinct visual/layout work too (tiles overlap in a themed 2.5D-looking stack,
not a flat grid) — a real, new rendering shape for this app, closer to Solitaire's own card-
overlap rendering than to any existing puzzle grid.

## Party Toolkit extensions (small utilities, not full games)

Smaller-scoped ideas in the Boardgame Pal spirit, sized like the die-type picker / roll history
additions rather than a new roadmap item on their own:

- **Trivia question deck** — a curated question bank with a "reveal answer" flip, same shape as
  this app's other curated-content tools (Word Search's dictionary, Crossword's clue bank).
- **Would You Rather / Charades prompt generator** — a curated prompt list with a "next prompt"
  button; near-zero engineering risk, almost entirely content-curation work.
- **Category timer** (e.g. "name 5 [category] before time runs out") — reuses Hourglass's own
  countdown-timer engine directly, just paired with a category-prompt picker.

None of these are individually worth their own roadmap item the way a full game is — bundle
whichever ones get picked into one Party Toolkit follow-up pass, the same way the die-type picker
and roll history did.

## Explicitly out of scope (already decided, not re-opened here)

README's own Roadmap item 13 already scoped down a three-part ask (Poker/Hearts and other card
games beyond UNO/Solitaire; other Tic-Tac-Toe variants beyond Misère/Wild; jigsaw/picture puzzles)
to one representative pick each, and explicitly left the rest undone. Nothing in this document
reopens that — if there's real interest in e.g. Poker or Hearts now, that's a fresh decision on
item 13 itself, not something this wave should quietly fold in.

## Suggested build order (a recommendation, not a commitment)

Sequenced by architecture risk and how directly each one reuses an already-validated pattern —
lowest-risk, most-code-reuse picks first, same logic wave 1's own build order used:

1. **Mastermind** — zero generation risk, simplest new engine in this whole document.
2. **2048** — zero generation risk, reuses Sliding Puzzle's own move-model precedent.
3. **Snake** — reuses the real-time tick-loop pattern at a smaller scale than anything else that's used it.
4. **Word Guess** — low generation risk, genuinely strong daily-seed fit; only real question is the non-daily-replay design fork.
5. **Reversi/Othello** — reuses Connect Four's own bot-ladder pattern directly; one real design question (positional heuristics) to settle first.
6. **Yahtzee** — real code reuse (`rollDice`), but needs its own session-shape design pass (solo-score vs. vs-bot) before scoping, closer to Tower Defence's own "settle the shape first" process.
7. **Flow Free** — real generation risk on par with Sudoku/Nonogram; scope with the same adversarial-review discipline those got.
8. **Mahjong Solitaire** — real generation risk (guaranteed-clearable construction) plus new rendering work (overlapping tile stacks).
9. **Backgammon** — flagged, not prioritized; substantial engine scope on par with Chess.
10. **Party Toolkit extensions** — bundle whichever of the three small ideas get picked into one follow-up pass, whenever convenient.

This order is a recommendation the project owner may reprioritize at any point, same as every
other roadmap item in this project — including skipping straight to whichever idea is most
appealing regardless of this list's own ordering.
