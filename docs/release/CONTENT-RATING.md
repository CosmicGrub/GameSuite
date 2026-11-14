# Content rating questionnaire answers (IARC via Play Console), draft

Answers are given per category of game in the app, then rolled up: the questionnaire is answered for the app as a
whole, so the **strictest game decides** each answer. Catalog ids are from `GameHubSpecs`; kid visibility from
`core/GameAudience.kt` (`KID_BLOCKED_GAME_IDS`) and `core/GameAudienceTable.kt`.

## Per-category answers

| Category | Games (catalog ids) | Violence | Sexual | Language | Controlled substances | Simulated gambling | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Board strategy | chess, checkers, reversi, shogi, hex, gomoku, nine-mens-morris, chinese-checkers, mancala, dots-and-boxes, hare-and-hounds, ludo, chutes-race, connect-four, edge-match, backgammon | Abstract capture only | None | None | None | **Backgammon**: dice and a doubling cube, no stakes | Chess/shogi "capturing" is abstract |
| Cards | uno, war, sevens, speed, president, solitaire, spider-solitaire, mahjong-solitaire | None | None | None | None | None (no stakes) | |
| Casino-style cards and dice | **blackjack, texas-holdem, hanafuda, slot-cars, pig-dice** | None | None | None | None | **Yes, simulated gambling themes** (see below) | Hidden from child profiles; adult profiles see them |
| Dice scoring | yahtzee, pig-dice (above) | None | None | None | None | Dice only; pig-dice listed above | |
| Puzzles | sudoku, kenken, kakuro, nonogram, minesweeper, lights-out, flow-lines, sliding-puzzle, game-2048, mastermind, six-ball-puzzle, color-flood, snake, breakout | None (minesweeper uses mines as a puzzle device, no depiction) | None | None | None | None | |
| Word games | hangman, word-search, word-tiles, crossword, word guess | Hangman: abstract stick-figure drawing, no gore | None | Draws on raw dictionary words (see check below) | None | None | Adult-only in the audience table |
| Toy sports / arcade | air-hockey, bowling, mini-golf, darts, curling, carrom, billiards, fishing, toy-soccer, toy-tennis, toy-baseball, toy-boxing, shooting-gallery, tank-battle, tower-defence | Cartoon: toy boxing, tank battle, tower defence and shooting-gallery targets are fantasy/cartoon, no blood | None | None | None | Darts/billiards: none | Darts is a thrown-object game, fine |
| Party / shared screen | tap-race, tug-of-war, whack-a-mole, reaction-duel, sumo-push, paddle-duel, rps-showdown, light-trail, draw-guess, act-it-out, echo-pads, party-toolkit, bridge-builders, bump-run, find-it, stack-zoo, flick-rink, pie-detective, sweet-trail, trail-together, egg-garden | Cartoon slapstick (whack-a-mole, sumo-push) | None | Draw-guess and act-it-out use curated word and card lists (Act It Out has an adult "grown ups" band) | None | None | |
| Life / economy | life-road, property-tycoon, territory-wars | Territory wars: abstract conquest | None | None | None | Pretend money in a board game, no real-money link | |

## Rolled-up answers for the questionnaire

- Violence: **mild, cartoon/fantasy violence** (toy boxing, tank battle, tower defence, shooting-gallery). No
  realistic violence, no blood, no human or animal harm depicted.
- Sexual content / nudity: **none**.
- Profanity / crude humour: **none authored.** CHECK before submitting: word games use dictionary and clue banks;
  confirm the dictionaries are filtered. If any list contains profanity, answer "infrequent/mild profanity" and
  keep those games out of kid profiles (they already are: the audience table leaves them adult-only).
- Controlled substances: **none.**
- Simulated gambling: **YES.** Blackjack, Texas Hold'em, Hanafuda (a flower-card game; flagged as gambling-themed in docs/audit/03-safety-privacy.md section 8 and blocked for kids in code),
  Slot Cars and Pig Dice carry casino or wager-style themes. There is **no real money, no purchase, no
  prize, no way to buy credits**, and the app has no in-app purchases or ads. IARC's wording distinguishes
  "simulated gambling" (casino-style games that do not pay out real money) from real-money gambling; answer the
  former, not the latter. Expect an age rating around PEGI 12 / ESRB Teen / USK 12 for the rolled-up app because of
  this. If the owner wants a lower rating for the whole app (for example PEGI 3 / Everyone, needed for a
  kids-only listing), these five games would have to be removed from this app or split into a separate one.
- User interaction: **Yes: users can interact** (online rooms and Nearby show other players' display names and
  avatars; moves are exchanged). There is **no free-text chat** and no media sharing. Quick-chat phrases exist on
  the relay but the Android client does not expose them. Answer "no chat, no user-generated content shared" where
  the form offers it.
- Shares location: **No.** Digital purchases: **No.** Unrestricted web access: **No.**
- Kid-profile mode: describe it as a parental-control feature (hides gambling-themed and adult-tagged games, disables
  online and Nearby, daily limits).

## Which games are in kid profiles

A kid profile sees only games tagged KIDS or FAMILY whose `minAge` is within the age band's top age (4-6 -> 6,
7-9 -> 9, 10-12 -> 12), minus the five gambling-themed ids, minus any game the parent hid, and with no Nearby or
Online mode. Games absent from `GameAudienceTable` (hangman, word-search, word-tiles, crossword, word guess,
backgammon, kakuro, and all casino-style games) default to adults only. Games added by teams in the current
roadmap set their own `audience`/`minAge` on their spec.

Table of ids and minimum ages from the audience table: tap-race 4, tug-of-war 4, whack-a-mole 4, matching 4,
rps-showdown 4, fishing 4; reaction-duel 5, sumo-push 5, paddle-duel 5, tic-tac-toe 5, ludo 5, bowling 5,
mini-golf 5, toy-soccer 5; light-trail 6, connect-four 6, mancala 6, dots-and-boxes 6, hare-and-hounds 6,
sliding-puzzle 6, color-flood 6, flow-lines 6, snake 6, breakout 6, darts 6, air-hockey 6, toy-tennis 6,
toy-baseball 6, war 6, party-toolkit 6; checkers 7, shooting-gallery 7, chinese-checkers 7, dominoes 7, uno 7;
game-2048 8, chess 8, reversi 8, gomoku 8, hex 8, nine-mens-morris 8, sevens 8, speed 8, solitaire 8, yahtzee 8,
mahjong-solitaire 8, curling 8, carrom 8, toy-boxing 8, lights-out 8, mastermind 8, nonogram 8, edge-match 8;
spider-solitaire 10, president 10, billiards 10, six-ball-puzzle 10, tank-battle 10, tower-defence 10, shogi 10,
mini-shogi 10, sudoku 10, minesweeper 10, kenken 10.

Review point for the owner: **tank-battle** (10+) and **toy-boxing** (8+) are cartoon combat; confirm you are
comfortable with them in the youngest bands of a kids listing.
