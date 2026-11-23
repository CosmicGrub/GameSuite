# Sweet Trail (`sweet-trail`): scope, script, scale, scaffold

Status: design + compiling scaffold, nothing built or run (no Gradle, tests or emulator were used). Branch `session/wave-3`.
Package `com.gamesuite.games.sweettrail`; strings `res/values{,-es}/sweet_trail_strings.xml`; tests `app/src/test/java/com/gamesuite/games/sweettrail/`.
Simulation numbers below come from a throw-away Python prototype of these exact rules (20 000 boards per cell, scratchpad `st_sim.py`); the Kotlin property tests in the test plan must reproduce them within tolerance, and if they do not, the Kotlin result wins and this document is corrected.

## 0. Originality and legal rule

Original names, art and card text only. Generic mechanics are used (a colour-card path race, move-to-next-matching-colour, a few forward shortcuts, one lose-a-turn square). Nothing is taken from any commercial product.

| Area | What this game does | Deliberately avoided |
|---|---|---|
| Names | Sweet Trail, Party Table, Gooey Puddle, Jelly Bridge, Pretzel Ladder, Cloud Zipline, six landmark names (Cookie Hill ... Cake Gate) | Every place, character and title of the best-known candy-path games and of any commercial product |
| Characters | None. Players are shape + colour pieces (circle, square, triangle, diamond) | Any named or drawn character, king, queen, gingerbread person, mascot |
| Board | 60 squares in ten blocks, each block its own permutation of six fruit colours; shortcuts on squares 8, 24, 42; puddle on 33 | The classic fixed repeating colour order, its square count, landmark order and layout |
| Cards | 66 cards (48 single, 12 double, 6 landmark), own ids and composition | The retail deck composition and wording |
| Colours | Fruit-named colours, each with a pattern for colour-blind players | The retail colour names |
| Art | Drawn in Compose, original | Any traced or recalled artwork |

Release gate: the test `noProtectedNamesInStringsSourcesOrDocs` scans strings, sources and this document for the protected-term list kept in the test (the owner's list of protected game, company, place, character and card names). A trademark search on "Sweet Trail" before store release is an open question below.

---

# PART 1: SCOPE

## 1.1 Purpose
A first board game for the youngest players in the suite: no reading, no counting, no strategy, one tap per turn. It lets a three-year-old and a grown-up play on equal terms and gives the Kids shelf a game with a real "I won" moment. It is also the suite's reference for the kids theme (warm cream surfaces, Baloo type, 56 dp targets, shape + colour tokens).

## 1.2 Audience
- `GameAudience.KIDS`, `minAge = 3` (see open question Q1: the smallest kid age band today is 4-6).
- 2 to 4 seats, any mix of people and computers on one device.
- Adult + child: the game is pure luck, so a grown-up has no edge; fairness needs no handicap. The optional wish card gives one seat a single card swap (measured +3.6 points for a medium-skill holder in a two-player board, so it is a comfort, not a skill equaliser). Do not sell it as a handicap that evens out skill, because there is no skill.
- Siblings of different ages: same rules, same board; nothing to configure.

## 1.3 MVP, v1, later
| Tier | Content |
|---|---|
| MVP | Engine, 3 lengths, wish card, puddle toggle, computers (3 levels), pass and play, vs computers, resume, EN + ES, coach card, five UI states, TalkBack, reduced motion, play again with rotating starter, session wins, score card |
| v1 | "Everyone finishes" option (rank 1st to 4th instead of stopping at the first winner); voice narration of the card colour (recorded or TTS, EN + ES); sticker shelf per kid profile (cosmetic, no currency); a second trail layout chosen by seed |
| Later | Nearby / Online for adult profiles only ("grandparent plays from another city"); co-op mode (everyone vs the clock-less trail); themed trail skins |

## 1.4 Explicit out of scope
Real-money or play-chip economy of any kind; ads; analytics beyond the suite's local stats; chat or free text; backward moves; reading-dependent UI; timers or countdowns on a turn; leaderboards; Nearby / Online for kid profiles (the suite strips network modes from kid profiles by design, `withoutNetworkModes`).

## 1.5 Kid safety and age notes
- Play-chips: none. No currency, no purchases, no gambling look. The game is not in `KID_BLOCKED_GAME_IDS`.
- No pressure: no clocks, no losing animation, no "you lost" sound. The post-game panel says who won and shows everyone's trail progress.
- A stuck turn (Gooey Puddle) is explained by a picture and a gentle sound, never as a punishment; the option can be switched off for the youngest players.
- Names are typed by a grown-up in the ready screen (`cleanPlayerName`); no names leave the device.
- Photosensitivity: no flashing above 3 Hz; confetti and dances respect Reduced Motion.
- Volume: effects at kid-safe loudness, nothing sudden.

## 1.6 Accessibility requirements
- Targets 56 dp or larger (card pile 120 dp); the only required action is one tap on the pile (or on the Keep / Wish buttons when a wish is in play).
- Colour is never the only cue: every card and square has its colour AND a pattern (dots, stripes, rings, waves, checks, diamonds); pieces are colour AND shape.
- TalkBack: every square, piece, the pile and the buttons have labels (keys `sweet_trail_square_label`, `_pawn_label`, `_pile_label`, `_card_label`); every state change is announced from the event (keys `sweet_trail_ann_*`); a tap that does nothing says why (`sweet_trail_why_*`).
- Reduced Motion: card flip and hops become cross-fades; dances become a static pose. Game speed scales bot delays (`LocalGameSpeed`).
- Large fonts: the screen has almost no text; announcements wrap in a status line.
- Works in all rotations and on Fold inner / cover screens (board uses `fitBoard`).
- Left-handed and one-handed use: the pile sits at the bottom centre.

## 1.7 Dependencies on other work
- Profiles foundation (other agent): kid profile age bands, `visibleFor`, and the kids theme (Baloo font bundle, cream surfaces). Until it lands the screen uses the current theme.
- Hub: the integrator registers `SweetTrailHubSpecs.sweetTrail` in `GameHubSpecs.baseSpecs()`; nothing in `GameHubSpecs`, `GameCatalog`, `GameTheme`, `MainActivity`, `GameTips` or `GameCoach` was touched.
- Existing UI internals a future screen would reuse: seat colours and shapes, `Die3DView`-style animation helpers, `ClCoachButton` / `rememberClCoach`, `ClStatusLine`, `ClWhyLine`, `fitBoard`.
- Art: drawn in Compose; no external assets, no Figma / Adobe dependency.

## 1.8 Open questions (each with a recommended default)
| # | Question | Recommended default |
|---|---|---|
| Q1 | The smallest kid age band is 4-6, but the game says 3+. Add an age-3 band? | No. Keep `minAge = 3` (shown to all kid bands and adults); a three-year-old plays through a grown-up's profile or the 4-6 band. |
| Q2 | Does a stuck (puddle) turn need a tap? | No: it resolves by itself with a 1.2 s animation after the seat's turn begins, then the turn passes. |
| Q3 | Voice narration in the MVP? | No, v1. The MVP uses animation, patterns and short sounds; TalkBack carries the words. |
| Q4 | Default game length | Full trail (standard). Offer Short as the "tired toddler" option. |
| Q5 | Stop at the first winner or let everyone finish? | Stop at the first winner in the MVP; "everyone finishes" in v1. |
| Q6 | Baloo font licence and bundle | Baloo 2 is SIL OFL; bundle through the profiles/theme work, fall back to the system rounded font. |
| Q7 | Trademark search on the name "Sweet Trail" | Do it before store release; rename is a strings-only change (`sweet_trail_help_title`, `hub_sweet_trail`) plus the id stays internal. |
| Q8 | Two wishes instead of one for a stronger comfort | Not in the MVP; measure first (the prototype only measured one). |
| Q9 | Random first mover in pass and play (the first seat wins 54.2 % of two-player boards) | Keep the suite's Hub default ("Player 1 first") and rotate the starter on every Play Again, which evens it out over a session. |

---

# PART 2: SCRIPT

## 2.1 Rules (numbered)
1. Players: 2 to 4 seats. Each seat has one piece (shape + colour).
2. Setup: all pieces on the start square. Short trail: all pieces start on square 28. The deck (66 cards) is shuffled face down; the discard is empty. A wish seat holds one wish card.
3. The starter is seat 0 on the first board (or the Hub's chosen first mover); Play Again rotates the starter by one seat.
4. A turn: the seat on turn taps the card pile. The top card is turned face up (the "card in play").
5. SINGLE card: the piece moves to the next square AFTER its current square that has the card's colour. If no such square remains it moves to the Party Table (finish).
6. DOUBLE card: the piece moves to the SECOND next square of that colour. If fewer than two remain it moves to the Party Table.
7. LANDMARK card: if the landmark square is ahead of the piece, the piece jumps there. If it is the piece's own square or behind it, the piece stays put and does a happy dance. Nobody ever moves backwards.
8. After moving, if the piece stands on a shortcut start (shortcuts on), it immediately travels to that shortcut's end. A shortcut end is never a start, so there are no chains.
9. If the piece stands on a puddle (Gooey Puddle on), it is stuck: at the start of its owner's next turn the owner does not draw; the piece pulls free and the turn passes.
10. Landing on the same square as another piece is allowed (pieces share squares; nothing bumps).
11. The card goes to the discard. When the pile is empty at a draw, the discard is shuffled into a new pile first (announcement `sweet_trail_ann_reshuffle`).
12. The first piece to reach the Party Table wins; the board ends at once.
13. WISH (handicap): a seat holding a wish sees the drawn card and may keep it or spend the wish. Spending discards that card, draws a new one, and the new card must be kept. A seat holds at most one wish per board.
14. After a win the panel offers Play Again (starter rotates) or Back to Menu. Session wins are tallied per player across boards; leaving the session records them.
15. Length changes: SHORT starts on 28; LONG removes the shortcuts, turns the 12 double cards into 12 extra single cards (10 per colour) and adds a second puddle on 50.
16. Edge cases: a piece on 60 wins with any colour or double card; a landmark card with the piece already on the landmark square is a dance; a seat stuck on a puddle cannot use its wish (it draws no card that turn); two seats may be stuck at once; a wish is never offered when the seat holds none.

## 2.2 Turn state machine
States (engine): `AWAIT_DRAW` (pending = false), `AWAIT_DECISION` (pending = true), `OVER`. UI-only sub-states (animations) never change engine state.

| From | Trigger (who acts) | Guard | To | Effect |
|---|---|---|---|---|
| AWAIT_DRAW | draw (seat on turn, person by tap, computer by `playBotStep`) | seat not stuck, seat has no wish | AWAIT_DRAW (next seat) or OVER | card resolves, move, shortcut / puddle, win check, turn passes |
| AWAIT_DRAW | draw | seat has a wish | AWAIT_DECISION | event PENDING, card on the table |
| AWAIT_DRAW | draw | seat stuck | AWAIT_DRAW (next seat) | event STUCK, no card drawn, stuck = 0 |
| AWAIT_DECISION | keep (seat) | none | AWAIT_DRAW (next seat) or OVER | resolve as above |
| AWAIT_DECISION | wish (seat) | wishes > 0 | AWAIT_DRAW (next seat) or OVER | wishes = 0, old card to discard, new card drawn and resolved at once, eventWished = true |
| any | tap out of turn | n/a | unchanged | rejected with a reason (`NOT_YOUR_TURN`, `DECIDE_FIRST`, ...) |

Who acts: the seat on turn only; computers act through `playBotStep`; a person on a computer's turn gets `sweet_trail_why_cpu`.
Hidden information: only the order of the face-down pile. The card in play, the discard and every position are public. Nothing is hidden between seats, so pass-and-play needs no privacy screen.

## 2.3 Content tables

### Deck (66 cards, ids dense)
| Ids | Count | Standard / Short | Long |
|---|---|---|---|
| 0-47 | 48 | Single card, 8 per colour (colour = id / 8) | same |
| 48-59 | 12 | Double card, 2 per colour (colour = (id - 48) / 2) | Single card, 2 more per colour (10 per colour in total) |
| 60-65 | 6 | Landmark cards: Cookie Hill (11), Lemonade Lake (17), Cupcake Tower (29), Honeycomb Cave (35), Marshmallow Meadow (46), Cake Gate (57) | same |

Expected card distribution (standard): 72.7 % single, 18.2 % double, 9.1 % landmark. Every colour has probability 12.1 % (single) + 3.0 % (double).

### Colours and patterns
| Letter | Colour | Name key | Pattern |
|---|---|---|---|
| R | Cherry red | `sweet_trail_colour_cherry` | dots |
| O | Tangerine orange | `sweet_trail_colour_tangerine` | stripes |
| Y | Lemon yellow | `sweet_trail_colour_lemon` | rings |
| G | Lime green | `sweet_trail_colour_lime` | waves |
| B | Blueberry blue | `sweet_trail_colour_blueberry` | checks |
| P | Grape purple | `sweet_trail_colour_grape` | diamonds |

### Board definition (machine readable)
```
trail      : squares 1..60 coloured, 0 = Start, 61 = Party Table (FINISH)
sequence   : RBYPGO GROBPY YOPGRB BGRYOP PYBOGR OPGRYB RYBGPO GBOPYR YPROBG ORGYBP   (square 1 first; ten blocks of six; 10 of each colour; max run 2)
shortcuts  : 8 -> 20 (Jelly Bridge), 24 -> 37 (Pretzel Ladder), 42 -> 53 (Cloud Zipline)   [off in LONG]
puddles    : 33   [LONG: 33 and 50]   [off when Gooey Puddle is off]
landmarks  : 11 Cookie Hill, 17 Lemonade Lake, 29 Cupcake Tower, 35 Honeycomb Cave, 46 Marshmallow Meadow, 57 Cake Gate
start      : 0   [SHORT: 28]
layout     : snaking rows of 10 (screen concern): row 0 left to right, row 1 right to left, ...
validity   : shortcut ends are not starts; landmarks avoid shortcut starts/ends and puddles; every shortcut spans 11..13 squares
```

Square table (square, colour, features). Short trail starts on 28; Long has no shortcuts and a second puddle on 50.

| Square | Colour | Features |
|---|---|---|
| 0 | none | Start |
| 1 | Cherry red (R) | - |
| 2 | Blueberry blue (B) | - |
| 3 | Lemon yellow (Y) | - |
| 4 | Grape purple (P) | - |
| 5 | Lime green (G) | - |
| 6 | Tangerine orange (O) | - |
| 7 | Lime green (G) | - |
| 8 | Cherry red (R) | shortcut start: Jelly Bridge to 20 |
| 9 | Tangerine orange (O) | - |
| 10 | Blueberry blue (B) | - |
| 11 | Grape purple (P) | landmark Cookie Hill |
| 12 | Lemon yellow (Y) | - |
| 13 | Lemon yellow (Y) | - |
| 14 | Tangerine orange (O) | - |
| 15 | Grape purple (P) | - |
| 16 | Lime green (G) | - |
| 17 | Cherry red (R) | landmark Lemonade Lake |
| 18 | Blueberry blue (B) | - |
| 19 | Blueberry blue (B) | - |
| 20 | Lime green (G) | shortcut end |
| 21 | Cherry red (R) | - |
| 22 | Lemon yellow (Y) | - |
| 23 | Tangerine orange (O) | - |
| 24 | Grape purple (P) | shortcut start: Pretzel Ladder to 37 |
| 25 | Grape purple (P) | - |
| 26 | Lemon yellow (Y) | - |
| 27 | Blueberry blue (B) | - |
| 28 | Tangerine orange (O) | - |
| 29 | Lime green (G) | landmark Cupcake Tower |
| 30 | Cherry red (R) | - |
| 31 | Tangerine orange (O) | - |
| 32 | Grape purple (P) | - |
| 33 | Lime green (G) | Gooey Puddle |
| 34 | Cherry red (R) | - |
| 35 | Lemon yellow (Y) | landmark Honeycomb Cave |
| 36 | Blueberry blue (B) | - |
| 37 | Cherry red (R) | shortcut end |
| 38 | Lemon yellow (Y) | - |
| 39 | Blueberry blue (B) | - |
| 40 | Lime green (G) | - |
| 41 | Grape purple (P) | - |
| 42 | Tangerine orange (O) | shortcut start: Cloud Zipline to 53 |
| 43 | Lime green (G) | - |
| 44 | Blueberry blue (B) | - |
| 45 | Tangerine orange (O) | - |
| 46 | Grape purple (P) | landmark Marshmallow Meadow |
| 47 | Lemon yellow (Y) | - |
| 48 | Cherry red (R) | - |
| 49 | Lemon yellow (Y) | - |
| 50 | Grape purple (P) | second puddle (Long only) |
| 51 | Cherry red (R) | - |
| 52 | Tangerine orange (O) | - |
| 53 | Blueberry blue (B) | shortcut end |
| 54 | Lime green (G) | - |
| 55 | Tangerine orange (O) | - |
| 56 | Cherry red (R) | - |
| 57 | Lime green (G) | landmark Cake Gate |
| 58 | Lemon yellow (Y) | - |
| 59 | Blueberry blue (B) | - |
| 60 | Grape purple (P) | - |
| 61 | none | Party Table (finish) |

## 2.4 Bot behaviour (the only decision is whether to spend a wish)
Inputs: `gain` = squares the card would carry the piece (shortcut and puddle effects included), `landsInPuddle`, `wins`, `takesShortcut`. The coin is derived from the state (`Random(drawSerial * 31 + position + seat)`), never from the deck random. Draw delay: 600 to 900 ms, scaled by `LocalGameSpeed`; no search, so no background thread is needed.
| Level | Rule |
|---|---|
| Easy | If a wish is held: wish on a coin flip, except never when `wins` or `takesShortcut`. Plays like a child. |
| Medium | Wish when `gain <= 0` or `landsInPuddle`; never when `wins` or `takesShortcut`. |
| Hard | Wish when `gain < 4` or `landsInPuddle`; never when `wins` or `takesShortcut`. |
Without a wish the computer simply draws; there is no other choice in the game. The Hub hides "Computer level" until a wish is chosen.

## 2.5 Handicap rule
Wish card, Hub row "Wish card": `none` (default), `all`, or one seat `1..4`. Each chosen seat holds one wish per board (see rule 13). Measured in the prototype (standard trail, medium-skill holder): two players, holder moves first: 58.0 % vs 54.4 % without a wish (+3.6 points); easy holder +0.5; hard-policy holder +6.2; four players, last seat holds the wish: 23.7 % vs 21.7 % (+2.0). Test `handicapWinRateDeltaIsBounded` asserts the delta stays between 0 and 8 points.

## 2.6 UI states (mockup notes: warm cream, Baloo type, 56 dp+ targets, shape + colour tokens)
| State | What is on screen |
|---|---|
| 1 Setup (Hub panel) | Mode tiles, Trail length, Gooey Puddle toggle, Wish card switch row, people / computers steppers, computer level (only with a wish), Start |
| 2 Start | Board with all pieces on the start flag, the starter's token pulsing, the pile glowing with a hand icon; status line `sweet_trail_ann_start` |
| 3 Mid-game | Board, pieces, pile (cards left), last card face up, each seat's token strip (shape + colour + wish star), Keep / Wish round buttons only while pending, status line |
| 4 Post-game | Winner's piece on the Party Table with confetti (static when reduced motion), Play Again, Back to Menu, session tally |
| 5 Score card | Each seat's progress bar "square n of 61", turns taken, shortcuts found, puddle stops, session wins |

## 2.7 First-run tutorial script (coach card; every line under 120 characters)
| Step | EN line | Key |
|---|---|---|
| 1 | Tap the card pile. The top card shows a colour. | `coach_sweet_trail_s1` |
| 2 | Your piece hops to the next square with that colour. | `coach_sweet_trail_s2` |
| 3 | A double card hops to the second square of that colour. | `coach_sweet_trail_s3` |
| 4 | Bridges, ladders and ziplines are shortcuts. The Gooey Puddle makes you wait one turn. | `coach_sweet_trail_s4` |
| 5 | First to the Party Table wins. Computers can fill empty seats. | `coach_sweet_trail_s5` |
| fact | Colour-card trail games need no reading or counting, so even a three-year-old can play. | `coach_sweet_trail_fact` |
| tip 1 | Tap the card pile to draw. Your piece moves for you. | `tip_sweet_trail_1` |
| tip 2 | Look for shortcuts: bridges, ladders and ziplines! | `tip_sweet_trail_2` |
| tip 3 | Gooey Puddle: wait one turn. A wish lets you swap one card. | `tip_sweet_trail_3` |

Integration (for the coordinator, not done here): `GameCoach` motif `CoachMotif.DICE`-style entry reading `coach_sweet_trail_s1..s5` and `_fact`; `GameTips` entry `"sweet-trail"` with the three tips above (literals must equal the resources); do NOT add the id to `ownCoachInGame` if the screen reuses the Club card (same decision as Slide and Climb).

## 2.8 Exact EN strings the UI needs
All keys exist in both locales in `sweet_trail_strings.xml` (generated from one data source, so the lists cannot drift).

| Key | English | Group |
|---|---|---|
| `sweet_trail_help_title` | Sweet Trail | Screen |
| `sweet_trail_help_text` | Tap the card pile to draw a card. Your piece hops to the next square with the card's colour. A double card hops to the second square of that colour. A landmark card jumps you forward to that place. Reach the Party Table first to win! | Screen |
| `sweet_trail_tap_pile` | Tap the card pile | Screen |
| `sweet_trail_pile_label` | Card pile, %1$d cards left | Screen |
| `sweet_trail_last_card` | Last card: %1$s | Screen |
| `sweet_trail_keep` | Keep card | Screen |
| `sweet_trail_wish_button` | Use wish | Screen |
| `sweet_trail_wish_left` | %1$s has a wish left. | Screen |
| `sweet_trail_wish_none` | No wish left. | Screen |
| `sweet_trail_square_label` | Square %1$d, %2$s | Screen |
| `sweet_trail_card_label` | Card: %1$s | Screen |
| `sweet_trail_pawn_label` | %1$s, %2$s piece, on square %3$d | Screen |
| `sweet_trail_pawn_label_start` | %1$s, %2$s piece, at the start | Screen |
| `sweet_trail_leave_title` | Leave this game? | Screen |
| `sweet_trail_leave_body` | The game is still going. Leaving now does not count as a win or a loss. | Screen |
| `sweet_trail_colour_cherry` | Cherry red | Colour names |
| `sweet_trail_colour_tangerine` | Tangerine orange | Colour names |
| `sweet_trail_colour_lemon` | Lemon yellow | Colour names |
| `sweet_trail_colour_lime` | Lime green | Colour names |
| `sweet_trail_colour_blueberry` | Blueberry blue | Colour names |
| `sweet_trail_colour_grape` | Grape purple | Colour names |
| `sweet_trail_pattern_cherry` | dots | Colour patterns |
| `sweet_trail_pattern_tangerine` | stripes | Colour patterns |
| `sweet_trail_pattern_lemon` | rings | Colour patterns |
| `sweet_trail_pattern_lime` | waves | Colour patterns |
| `sweet_trail_pattern_blueberry` | checks | Colour patterns |
| `sweet_trail_pattern_grape` | diamonds | Colour patterns |
| `sweet_trail_shape_circle` | circle | Player shapes |
| `sweet_trail_shape_square` | square | Player shapes |
| `sweet_trail_shape_triangle` | triangle | Player shapes |
| `sweet_trail_shape_diamond` | diamond | Player shapes |
| `sweet_trail_card_double` | Double %1$s | Card names |
| `sweet_trail_landmark_cookie_hill` | Cookie Hill | Landmark names |
| `sweet_trail_landmark_lemonade_lake` | Lemonade Lake | Landmark names |
| `sweet_trail_landmark_cupcake_tower` | Cupcake Tower | Landmark names |
| `sweet_trail_landmark_honeycomb_cave` | Honeycomb Cave | Landmark names |
| `sweet_trail_landmark_marshmallow_meadow` | Marshmallow Meadow | Landmark names |
| `sweet_trail_landmark_cake_gate` | Cake Gate | Landmark names |
| `sweet_trail_jelly_bridge` | Jelly Bridge | Place names |
| `sweet_trail_pretzel_ladder` | Pretzel Ladder | Place names |
| `sweet_trail_cloud_zipline` | Cloud Zipline | Place names |
| `sweet_trail_puddle` | Gooey Puddle | Place names |
| `sweet_trail_place_start` | Start | Place names |
| `sweet_trail_place_finish` | Party Table | Place names |
| `sweet_trail_ann_start` | %1$s goes first. Tap the card pile. | Announcements |
| `sweet_trail_ann_start_you` | You go first. Tap the card pile. | Announcements |
| `sweet_trail_ann_move` | %1$s drew %2$s and hopped to square %3$d. | Announcements |
| `sweet_trail_ann_move_you` | You drew %1$s and hopped to square %2$d. | Announcements |
| `sweet_trail_ann_shortcut` | %1$s drew %2$s, found the shortcut %3$s and zoomed to square %4$d! | Announcements |
| `sweet_trail_ann_shortcut_you` | You drew %1$s, found the shortcut %2$s and zoomed to square %3$d! | Announcements |
| `sweet_trail_ann_puddle` | %1$s drew %2$s and fell into the Gooey Puddle. Waits one turn. | Announcements |
| `sweet_trail_ann_puddle_you` | You drew %1$s and fell into the Gooey Puddle. You wait one turn. | Announcements |
| `sweet_trail_ann_stuck` | %1$s pulls free of the Gooey Puddle. Next player! | Announcements |
| `sweet_trail_ann_stuck_you` | You pull free of the Gooey Puddle. Next player! | Announcements |
| `sweet_trail_ann_passed` | %1$s drew %2$s but is already past it. Happy dance! | Announcements |
| `sweet_trail_ann_passed_you` | You drew %1$s but you are already past it. Happy dance! | Announcements |
| `sweet_trail_ann_pending` | %1$s drew %2$s. Keep it or use the wish. | Announcements |
| `sweet_trail_ann_pending_you` | You drew %1$s. Keep it or use your wish. | Announcements |
| `sweet_trail_ann_wish_used` | Wish used. Drawing a new card. | Announcements |
| `sweet_trail_ann_reshuffle` | The pile is empty. Shuffling the cards. | Announcements |
| `sweet_trail_ann_win` | %1$s reaches the Party Table and wins! | Announcements |
| `sweet_trail_ann_win_you` | You reach the Party Table and win! | Announcements |
| `sweet_trail_why_wait` | It is not your turn yet. | Why lines |
| `sweet_trail_why_cpu` | The computer is taking its turn. | Why lines |
| `sweet_trail_why_decide` | Keep the card or use your wish first. | Why lines |
| `sweet_trail_why_over` | The game is over. | Why lines |
| `sweet_trail_result_win` | %1$s wins! | Post-game |
| `sweet_trail_result_win_you` | You win! | Post-game |
| `sweet_trail_play_again` | Play again | Post-game |
| `sweet_trail_back_to_menu` | Back to menu | Post-game |
| `sweet_trail_score_title` | Trail report | Score card |
| `sweet_trail_score_line` | %1$s: square %2$d of %3$d | Score card |
| `sweet_trail_score_turns` | Turns taken: %1$d | Score card |
| `sweet_trail_score_shortcuts` | Shortcuts found: %1$d | Score card |
| `sweet_trail_score_puddles` | Puddle stops: %1$d | Score card |
| `sweet_trail_score_session` | Session: %1$s | Score card |
| `coach_sweet_trail_s1` | Tap the card pile. The top card shows a colour. | Coach card |
| `coach_sweet_trail_s2` | Your piece hops to the next square with that colour. | Coach card |
| `coach_sweet_trail_s3` | A double card hops to the second square of that colour. | Coach card |
| `coach_sweet_trail_s4` | Bridges, ladders and ziplines are shortcuts. The Gooey Puddle makes you wait one turn. | Coach card |
| `coach_sweet_trail_s5` | First to the Party Table wins. Computers can fill empty seats. | Coach card |
| `coach_sweet_trail_fact` | Colour-card trail games need no reading or counting, so even a three-year-old can play. | Coach card |
| `tip_sweet_trail_1` | Tap the card pile to draw. Your piece moves for you. | Tips |
| `tip_sweet_trail_2` | Look for shortcuts: bridges, ladders and ziplines! | Tips |
| `tip_sweet_trail_3` | Gooey Puddle: wait one turn. A wish lets you swap one card. | Tips |

Game Hub texts (key = `hubKey(english)`):

| Key | English | Status |
|---|---|---|
| `hub_sweet_trail` | Sweet Trail | defined in this file |
| `hub_draw_a_colour_card_and_race_along_the_trail` | Draw a colour card and race along the trail. | defined in this file |
| `hub_trail_length` | Trail length | defined in this file |
| `hub_a_shorter_or_longer_race` | A shorter or longer race. | defined in this file |
| `hub_short_trail` | Short trail | defined in this file |
| `hub_start_part_way_along` | Start part-way along. | defined in this file |
| `hub_full_trail` | Full trail | defined in this file |
| `hub_the_whole_trail` | The whole trail. | defined in this file |
| `hub_long_trail` | Long trail | defined in this file |
| `hub_no_shortcuts_and_one_extra_puddle` | No shortcuts and one extra puddle. | defined in this file |
| `hub_gooey_puddle` | Gooey Puddle | defined in this file |
| `hub_on_landing_in_it_means_waiting_one_turn_off_it_i_71053c` | On: landing in it means waiting one turn. Off: it is a plain square. | defined in this file |
| `hub_wish_card` | Wish card | defined in this file |
| `hub_one_wish_per_game_lets_a_player_swap_a_card_they_95093e` | One wish per game lets a player swap a card they drew. | defined in this file |
| `hub_no_wish` | No wish | defined in this file |
| `hub_wish_for_everyone` | Wish for everyone | defined in this file |
| `hub_wish_player_1` | Wish: Player 1 | defined in this file |
| `hub_wish_player_2` | Wish: Player 2 | defined in this file |
| `hub_wish_player_3` | Wish: Player 3 | defined in this file |
| `hub_wish_player_4` | Wish: Player 4 | defined in this file |

---

# PART 3: SCALE

## 3.1 Game length by mode
Measured (prototype, all-seat turns including stuck waits; mean, 95th percentile):
| Length | Rule changes | 2 seats | 3 seats | 4 seats |
|---|---|---|---|---|
| Short | start on square 28 | 10.4 | 13.6 | 16.5 |
| Standard | none | 16.0 (24) | 20.8 (32) | 25.0 (39) |
| Long | no shortcuts, no doubles, second puddle | 21.3 | 26.8 | 31.6 |
At kid pace (10 to 15 s per turn including the cheering) a standard four-seat board is about 4 to 6 minutes, a short two-seat board about 2 minutes, a long four-seat board 5 to 8 minutes. Long is only about 30 % longer than standard because the trail is fixed; if a longer game is wanted later the lever is a longer trail, not more rules. Budget the test enforces (`lengthByModeStaysInBudget`): mean total turns within +-15 % of the table above.
No-sticky standard, three seats: 20.7 turns (the puddle costs about 0.1 turns per board, so it is a flavour rule, not a balance rule).

## 3.2 Players and CPU fill
2 to 4 seats; humans 1 to 4, computers 0 to 3, filled by the Hub (`buildHubLaunch`). Mixed tables allowed. Computers never block a human: their only decision is instant.

## 3.3 Deterministic seeds
One `nextLong()` from the injected `Random` per board becomes the board seed; the engine shuffles with `Random(seed)` and reshuffle n uses `Random(seed xor n * 0x9E3779B97F4A7C15)`. The snapshot stores the boardSeed AND the explicit pile and discard orders, so a restored game never depends on the Kotlin RNG algorithm. Tests that need a known order pass `fixedSeed` or a scripted pile and assert invariants, never specific shuffles.

## 3.4 Performance budget
| Item | Budget |
|---|---|
| Frame time | 16.6 ms at 60 Hz (8.3 ms at 120 Hz); board canvas under 4 ms; no allocation in `draw` |
| Engine `draw` / `decide` | under 0.1 ms (table lookups; `nextSquareOfColour` scans at most 60 squares) |
| Bot think time | none to compute; the 600 to 900 ms is a deliberate delay on the main dispatcher timer, never a blocking call |
| Snapshot size | under 4 KB (measured estimate ~1.3 KB: 66 card ids, 4 seats) |
| Memory | state is ~10 small lists; deck table built once |

## 3.5 Persistence and resume
`ResumableGame`: snapshot for vs-computer and pass-and-play only, null after the match is over. Restore validates the roster, array sizes, ranges, event name, card coverage and the invariants listed in the KDoc of `SweetTrailGame`; any failure returns false and the Hub starts fresh. Session wins and the starter index are saved. Corrupt, truncated and hostile JSON must never throw (test `hugeOrNastyInputNeverThrows`).

## 3.6 Multiplayer plan
Same screen now. Nearby / Online later, adult profiles only (kid profiles strip network modes).
| Message | Direction | Content |
|---|---|---|
| `RequestState` | guest to host | none |
| `DrawRequest` | guest to host | none (host draws; a guest never picks a card) |
| `WishDecision` | guest to host | `wish: Boolean` |
| `StateSync` | host to all | version, positions, stuck, wishes, seat on turn, card in play, discard, pile COUNT, event, serials |
| `PlayAgain` | guest to host | none |
Hidden information: the pile order only. The host holds it; guests get the count. A guest is not resumable; the host is. Identity: messages carry `fromPlayerId` and the host checks it equals the seat on turn (the suite's transport identity hardening). Effort: L, Phase 5.

## 3.7 Phased delivery
| Phase | Content | Effort | Proves |
|---|---|---|---|
| 0 Rules | Real `SweetTrailEngine`, board / deck / rules / property tests (un-ignore Board, Deck, Rules, Property) | S | Rules are right and deterministic; length and wish numbers match the prototype |
| 1 Module | Wrapper, bots, snapshot validation, hub spec registered by the coordinator (un-ignore Game, Bot, Snapshot, HubSpecs) | M | Resume is safe, Hub and localisation gates stay green |
| 2 Screen | Board canvas, pile, pieces, status line, a11y labels, five states | M | A child can play with one tap; playtest with real 3 to 5 year olds |
| 3 Polish | Sound, haptics, reduced motion, coach card, score card, patterns, dances | M | Meets the quality bar (feel, accessibility, EN + ES) |
| 4 v1 | Everyone-finishes mode, narration, stickers | S to M | Kids shelf retention |
| 5 Network | Nearby / Online for adults | L | Remote grandparent play |

## 3.8 Risk register
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Looks too close to a famous product | Medium | High | Original names, fruit colours with patterns, 60-square layout, no characters; legal-term scan test; trademark search (Q7) |
| Game too short or flat for adults helping | Medium | Low | It is for ages 3 to 5; Long length and v1 stickers; no attempt to add strategy |
| Three-year-olds cannot tell colours apart | Medium | Medium | Patterns on every colour; larger squares; voice narration in v1 |
| Puddle frustrates the youngest | Medium | Medium | Toggle off; no sad animation; auto-resolves |
| Age band 4-6 excludes true 3-year-olds | High | Low | Q1 default: grown-up profile |
| Snapshot corruption crashes resume | Low | High | Validation + tests; restore returns false on any doubt |
| Resource name collision with parallel agents | Low | High (build break) | Unique prefixes (`sweet_trail_`, `tip_sweet_trail_`, `coach_sweet_trail_`), unique hub texts, generator checked all names against the tree |
| Spanish text unreviewed | Medium | Low | Real Spanish by the author, flagged for native review in an XML comment |

## 3.9 Test plan
All of these exist as `@Ignore`d stubs with exactly these names (95 tests, 8 classes).

**SweetTrailBoardTest** (Phase 0, 12 tests): `colourSequenceHasSixtyCharacters`, `eachColourAppearsExactlyTenTimes`, `noColourRunIsLongerThanTwo`, `shortcutsAreForwardAndNeverChain`, `landmarksAvoidShortcutPuddleAndStartSquares`, `nextSquareOfColourIsStrictlyAhead`, `nextSquareOfColourBeyondTheLastIsTheFinish`, `secondNextSquareOfColourSkipsTheFirst`, `colourAtStartAndFinishIsNull`, `shortLengthStartsOnSquareTwentyEight`, `longLengthHasNoShortcutsAndTwoPuddles`, `stickyOffRemovesEveryPuddle`

**SweetTrailDeckTest** (Phase 0, 10 tests): `standardDeckHasSixtySixCards`, `singlesAreEightPerColourInStandard`, `doublesAreTwoPerColourInStandard`, `longDeckHasTenSinglesPerColourAndNoDoubles`, `landmarkCardsAreSixAndMatchTheLandmarks`, `cardIdsAreDenseAndUnique`, `shuffleIsDeterministicForASeed`, `shuffleIsAPermutationOfAllCards`, `differentSeedsGiveDifferentOrders`, `reshuffleKeepsEveryCardExactlyOnce`

**SweetTrailRulesTest** (Phase 0, 18 tests): `singleCardMovesToTheNextSquareOfItsColour`, `doubleCardMovesToTheSecondNextSquareOfItsColour`, `doubleCardWithOnlyOneSquareLeftReachesTheFinish`, `singleCardWithNoSquareAheadReachesTheFinish`, `landmarkAheadJumpsThere`, `landmarkBehindLeavesThePieceAndDances`, `landmarkOnTheCurrentSquareLeavesThePiece`, `landingOnAShortcutStartTakesTheShortcutOnce`, `shortcutEndNeverTriggersAnotherJump`, `shortcutsAreOffInLongLength`, `landingOnThePuddleSticksForOneTurn`, `aStuckSeatSkipsItsDrawThenIsFree`, `stickyOffMakesThePuddleAPlainSquare`, `noCardEverMovesAPieceBackwards`, `firstPieceOnTheFinishWinsAndTheBoardEnds`, `turnPassesToTheNextSeatAfterEveryMove`, `starterRotatesBetweenBoards`, `reshuffleHappensWhenThePileIsEmpty`

**SweetTrailGameTest** (Phase 1, 13 tests): `wishLetsTheSeatSwapTheDrawnCardOnce`, `wishCannotBeUsedTwice`, `wishedCardGoesToTheDiscardPile`, `aSeatWithoutAWishMovesAtOnce`, `afterAWishTheNewCardMustBeKept`, `chanceComesOnlyFromTheInjectedRandom`, `passAndPlayRotatesSeats`, `firstOpenerIsHonoured`, `playAgainRotatesTheStarter`, `leaveSessionRecordsTheBoardsWon`, `tapsOutOfTurnAreRejectedWithAReason`, `computerSeatNeverGetsAHumanTurn`, `sessionWinsAccumulateAcrossBoards`

**SweetTrailBotTest** (Phase 1, 8 tests): `easyBotNeverStallsTheTable`, `mediumBotWishesOnAZeroGainCard`, `mediumBotWishesOutOfThePuddle`, `hardBotWishesOnAGainUnderFour`, `noBotWishesAwayAWinOrAShortcut`, `botDecisionsAreDeterministicForAState`, `botNeverTouchesTheDeckRandom`, `botsPlayEveryLevelToTheEndOnAThousandSeeds`

**SweetTrailSnapshotTest** (Phase 1, 14 tests): `snapshotRoundTripRestoresEveryField`, `snapshotIsNullAfterTheMatchIsOver`, `corruptJsonIsRejected`, `truncatedJsonIsRejected`, `wrongRosterIsRejected`, `pileDiscardAndCardMustCoverAllSixtySixIds`, `positionOutOfRangeIsRejected`, `stuckSeatMustStandOnAPuddle`, `pendingMustMatchThePendingEvent`, `moveEventMustReplayThroughTheEngine`, `winnerMustBeTheOnlyPieceOnTheFinish`, `snapshotStaysUnderFourKilobytes`, `restoredBoardContinuesIdenticallyToTheOriginal`, `hugeOrNastyInputNeverThrows`

**SweetTrailPropertyTest** (Phase 0/1, 8 tests): `randomPlayAlwaysTerminatesOnTenThousandSeeds`, `positionsNeverDecrease`, `cardsAreConservedEveryTurn`, `exactlyOneWinnerPerBoard`, `lengthByModeStaysInBudget`, `handicapWinRateDeltaIsBounded`, `firstMoverAdvantageIsUnderSixPoints`, `noStateEverFailsValidate`

**SweetTrailHubSpecsTest** (Phase 1, 12 tests): `theSpecIsAKidsGameFromAgeThree`, `theSpecIsNotNetworked`, `everyHubTextHasAnEnglishAndASpanishString`, `spanishPlaceholdersMatchEnglish`, `optionKeysAndDefaultsMatchTheDoc`, `difficultyIsHiddenUntilAWishIsChosen`, `everySweetTrailKeyExistsInBothLocales`, `tutorialLinesAreUnderOneHundredTwentyCharacters`, `tipsMatchTheStringResources`, `noProtectedNamesInStringsSourcesOrDocs`, `kidProfileSeesTheGameAndNoNetworkMode`, `accentColoursPassContrast`


Property tests: `randomPlayAlwaysTerminatesOnTenThousandSeeds`, `positionsNeverDecrease`, `cardsAreConservedEveryTurn`, `exactlyOneWinnerPerBoard`, `noStateEverFailsValidate`. Bot determinism: same state gives same decision; bots never use the deck random. Snapshot corruption: garbage, truncation, wrong roster, bad card coverage, out-of-range values, impossible stuck / pending / winner. Localisation: every key in both locales, placeholder parity, tutorial lines at most 120 characters, tips equal the resources, protected-term scan.

---

# PART 4: SCAFFOLD

Compiling skeletons only (not compiled by the author). Package `com.gamesuite.games.sweettrail`:
| File | Content |
|---|---|
| `SweetTrailBoard.kt` | `TrailColour`, `TrailLandmark`, `TrailLength`, `TrailSquare`, `SweetTrailBoard` (constants, sequence, shortcuts, `colourAt`, `nextSquareOfColour`) |
| `SweetTrailCards.kt` | `TrailCardKind`, `TrailCard`, `SweetTrailDeck` (66 cards, ids as in 2.3) |
| `SweetTrailState.kt` | `SweetTrailEvent`, `SweetTrailReject`, `SweetTrailPlayer`, `SweetTrailRules`, `SweetTrailState` |
| `SweetTrailEngine.kt` | `SweetTrailEngine` interface, `StubSweetTrailEngine` (valid opening board, other methods return safe values), `SweetTrailBot.wantsWish` (returns false) |
| `SweetTrailGame.kt` | `SweetTrailGame : GameModule, ResumableGame`, snapshot DTOs, partial validation (rest listed in the KDoc), `parseSweetTrailWish` |
| `SweetTrailHubSpecs.kt` | `SweetTrailHubSpecs.sweetTrail` (KIDS, minAge 3), accent and glyph constants; NOT registered |
Strings: `res/values/sweet_trail_strings.xml`, `res/values-es/sweet_trail_strings.xml`. Tests: `app/src/test/java/com/gamesuite/games/sweettrail/*Test.kt`.

Integration checklist for the coordinator (none of it done):
1. `GameHubSpecs.baseSpecs()`: add `SweetTrailHubSpecs.sweetTrail` (BOARD section). Do not add `"sweet-trail"` to `networkedGames`.
2. `GameCatalog` glyph `"sweet-trail" -> "🍬"` (`SweetTrailHubSpecs.GLYPH`).
3. `GameThemes.accents["sweet-trail"] = GameAccent(0xFF9C1B52, 0xFFF08FB4)`; contrast against the reference surfaces computed as 7.6 / 6.0 (light) and 7.6 / 5.4 (dark), distinctness from the other accents NOT checked.
4. Route and screen (`sweet-trail`, Phase 2), `GameTips`, `GameCoach` as in 2.7.
5. `GameAudienceTable` needs no row (the spec's own tags win).
6. Gate: run `HubLocalizationTest`, `GameTipsTest`, `GameCoachTest`, `GameThemeTest`, `GameAudienceTest` after registration; then un-ignore the Phase 0 tests.
