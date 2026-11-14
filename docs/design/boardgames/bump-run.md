# Bump and Run (`bump-run`): scope, script, scale, scaffold

Status: design + compiling scaffold, nothing built or run (no Gradle, tests or emulator were used). Branch `session/wave-3`.
Package `com.gamesuite.games.bumprun`; strings `res/values{,-es}/bump_run_strings.xml`; tests `app/src/test/java/com/gamesuite/games/bumprun/`.
Simulation numbers come from a throw-away Python prototype of these exact rules (1 500 to 3 000 boards per cell, scratchpad `br_sim.py`, every seat playing the Medium-style greedy policy below); the Kotlin property tests must reproduce them within tolerance, and if they do not the Kotlin result wins and this document is corrected.

## 0. Originality and legal rule

Original names, art and card text only. Generic mechanics are used (draw a card, move a pawn, landing on a rival sends it home, swap places, exact count to finish, an optional team game). Nothing is taken from any commercial product.

| Area | What this game does | Deliberately avoided |
|---|---|---|
| Names | Bump and Run, Nest, Home, Lane, Spring pad, Hop, Swap Places, Chase, Free Exit, Again, Shield | The names, character names, card wordings and board layouts of the well-known "draw a card, bump or swap" and "pop the die" family games and of any commercial product |
| Board | A 36-square ring (the edge of a 10 x 10 grid), exits at 5, 14, 23, 32, three-square lanes, spring pads at 1, 10, 19, 28 | The retail perimeter size, corner start areas, coloured slide squares, safety-zone length |
| Deck | 54 cards with own composition: Hop 1,2,3,4,5,6,8,10, Swap, Chase, Free Exit, Again, Shield | The retail deck composition, values and effects (no split card, no backward card, no "apology" card, no "pop" die) |
| Pawns | 2, 3 or 4 per seat by mode | Fixed four pawns |
| Art | Drawn in Compose, original | Any traced or recalled artwork |

Release gate: the test `noProtectedNamesInStringsSourcesOrDocs` scans strings, sources and this document for the protected-term list kept in the test (the owner's list of protected game, company, place, character and card names). "Bump and Run" is also an American-football term; a trademark search before store release is an open question below.

---

# PART 1: SCOPE

## 1.1 Purpose
A family race game with a little mischief: luck of the draw, a real choice every turn (which pawn, whether to bump or to run), and the funny sting of being sent home. It fills the gap between Sweet Trail (no choices) and Ludo / Chinese Checkers (dice, no cards) and gives families a game where a child and an adult are close in strength, because the deck is luck and the choices are simple.

## 1.2 Audience
- `GameAudience.FAMILY`, `minAge = 6`; visible to kid profiles of age band 4-6 and up (6 <= 6) and to adults. Readers of numerals: card faces use numerals, icons and a star; reading of full sentences is optional (the card description is a tooltip / TalkBack text).
- 2 to 4 seats, any mix of people and computers on one device; optional 2 v 2 team play with four seats.
- Adult + child fairness: card luck dominates (measured: the first seat has no edge in two-player boards, 49.7 % vs 50.3 %), the Quick length (two pawns) keeps boards under about 15 minutes, "Skip obvious moves" removes pointless taps, and the Helper pack (three do-overs and one pawn already out) gives a younger player about +5 points against an equal opponent (+3 in a four-seat board). It is a comfort, not a skill equaliser.

## 1.3 MVP, v1, later
| Tier | Content |
|---|---|
| MVP | Engine (all 13 card types, bumps, swaps, shields, pads, exact finish), 3 lengths (2 / 3 / 4 pawns), 3 computer levels, pass and play, vs computers, Helper pack, Skip obvious moves, resume, EN + ES, coach card, five UI states, TalkBack, reduced motion, session wins, score card |
| v1 | Team play (2 v 2) with team moves, Cushion squares option (safe squares on the ring), Gentle bump option (a bumped pawn goes back 10 squares instead of to the Nest), hint button using the Hard bot, quick-chat preset phrases for the network phase |
| Later | Nearby / Online for adults, spectators, themed boards, a daily "puzzle position" |

## 1.4 Explicit out of scope
Play-chips, currency, purchases, ads, analytics beyond the suite's local stats; free-text chat; dice; backward moves; timers per turn; kid-profile network play (stripped by design); elimination of players (everyone plays to the end of the board).

## 1.5 Kid safety and age notes
- Play-chips: none. No wagering look. Not in `KID_BLOCKED_GAME_IDS`.
- Bumping is cartoon-soft: the bumped pawn hops back to its Nest with a boing; no "destroyed" language, no red flash. Strings say "bumped ... back to the Nest".
- No chat or free text; names are typed on the ready screen (`cleanPlayerName`) and stay on the device.
- No countdowns. Bots take 0.8 to 1.5 s per decision (scaled by game speed) so a child can follow what happened.
- The Gentle bump option (v1) is the answer to "my six-year-old cried when she was sent home".
- Photosensitivity: no flashing above 3 Hz; Reduced Motion replaces hops by fades.

## 1.6 Accessibility requirements
- Targets 56 dp or larger for pawns and the pile; legal destinations are lit with a ring AND an icon (not colour alone); pawns are colour AND shape (circle, square, triangle, diamond) AND carry a number 1 to 4.
- TalkBack: every square (`bump_run_square_label`, `_exit_label`, `_pad_label`), pawn (`bump_run_pawn_label` with state), nest, home and the pile has a label; every event is announced (`bump_run_ann_*`); a rejected tap says why (`bump_run_why_*`).
- Two-step moves are never required to be dragged: tap the pawn, then tap the lit square (or just tap the pawn when its move is unique).
- Reduced Motion, game speed, large fonts, all rotations and Fold inner / cover, left-handed use (pile bottom centre, the next pawn choice near the thumb).
- Colour-blind: seat colours chosen from the suite's colour-blind-safe set plus shapes.

## 1.7 Dependencies on other work
- Profiles foundation (other agent): kid profile bands, `visibleFor`, kids theme. FAMILY games appear in kid profiles automatically.
- Hub: integrator registers `BumpRunHubSpecs.bumpRun`. Team play uses the existing `HubKeys.TEAMS` plumbing in `buildHubLaunch` (even table of four, `PlayerInfo.teamId`).
- No change to `GameHubSpecs`, `GameCatalog`, `GameTheme`, `MainActivity`, `GameTips`, `GameCoach` was made.
- Reused UI internals for the screen: seat colours and shapes, `fitBoard`, `ClStatusLine`, `ClWhyLine`, `rememberClCoach`.

## 1.8 Open questions (each with a recommended default)
| # | Question | Recommended default |
|---|---|---|
| Q1 | Default length | Quick (2 pawns): four-seat Quick is ~127 turns (10 to 17 min), Classic ~212 (18 to 28 min). |
| Q2 | Visibility to 4-year-old kid profiles | FAMILY, minAge 6: a 4-6 band sees it (6 <= 6). Revisit after playtests; if too hard drop to bands 7+ by setting minAge 7. |
| Q3 | Is bumping too harsh for sensitive children? | Ship Nest bumps in the MVP, Gentle bump in v1; the Helper pack and Quick length soften the blow. |
| Q4 | Team play in the MVP? | v1. The engine and snapshot already carry `teamId`; tests are stubbed (`BumpRunTeamTest`). |
| Q5 | Autoplay of a single legal move | On by default, always with a 600 ms visible preview (never an instant jump), and can be turned off in setup. |
| Q6 | Trademark search for "Bump and Run" | Do it before store release; rename is strings-only (`bump_run_help_title`, `hub_bump_and_run`). |
| Q7 | Spring pads on or off by default | On (they cut about 6 % of the turns and add one surprise); the Hub toggle turns them off. |
| Q8 | Long four-seat boards are ~307 turns | Keep, but label the length choice by pawns, not minutes, and show "Quick race" first. |
| Q9 | Baloo font | Same as Sweet Trail Q6. |

---

# PART 2: SCRIPT

## 2.1 Rules (numbered)
1. Players: 2 to 4 seats, each with 2 (Quick), 3 (Classic) or 4 (Long) pawns of its shape and colour. Seats sit on board sides: 2 seats on opposite sides, 3 seats on sides 0 to 2, 4 seats on all four.
2. Setup: all pawns in their Nest. The 54-card deck is shuffled face down. Helper-pack seats start with pawn 0 on its exit square and 3 do-overs.
3. A pawn's journey: Nest, exit square, once around the 36-square ring, into its own 3-square lane, Home. Total progress 39 steps from the exit square (progress 0 = exit, 35 = the ring square before its lane, 36 to 38 = lane, 39 = Home).
4. Turn: the seat on turn taps the pile. The top card is turned face up.
5. HOP n (1, 2, 3, 4, 5, 6, 8, 10): move ONE pawn forward exactly n steps. Pawns may jump over other pawns. A pawn may not go past Home: it needs the exact count (a pawn 2 steps from Home can use a Hop 2 or Hop 1, not a Hop 3).
6. STAR cards (Hop 1, Hop 6, Free Exit; 14 of 54): may instead bring one pawn out of the Nest onto its exit square.
7. A pawn may not land on a square holding a pawn of its own side (or its team mate). Lane squares are private; Home holds any number.
8. BUMP: landing on a ring square holding a rival pawn sends that pawn back to its Nest. The rival's bumped count and the mover's bump count go up (score card).
9. SWAP PLACES: choose one of your pawns on the ring and one unshielded rival pawn on the ring; they exchange squares. Neither is bumped. Each pawn's progress is recalculated for its own side. Pawns in a Nest, lane or Home cannot be swapped. If there is no pair, the card gives no move (rule 17).
10. CHASE: choose one of your ring pawns; it leaps to the NEAREST rival pawn ahead of it on the ring within 12 squares and bumps it. Own pawns in between are jumped over. If the nearest rival is shielded, that pawn has no Chase move. If NO pawn of yours has a Chase move, the card plays as Hop 4.
11. FREE EXIT: bring one pawn out of the Nest onto its exit square, bumping a rival standing there. If no pawn can (none in the Nest, or the exit square is blocked by your own pawn or a shielded rival), the card plays as Hop 3.
12. AGAIN: nothing moves; the same seat draws another card and plays it too (this is not a new turn).
13. SHIELD: choose one of your ring pawns. Until the START of your next turn rivals cannot land on it, swap it or chase it. It does not restrict you. If you have no ring pawn, the card plays as Hop 2.
14. SPRING PADS (option, default on): a pawn that ends a HOP or CHASE on a pad square (ring squares 1, 10, 19, 28) immediately hops 3 more squares along its path. If the arrival square holds your own pawn or a shielded rival, it stays on the pad; a rival there is bumped. Pads do not trigger after Swap, after being bumped, or after leaving the Nest. Pad hops never chain and never cross a lane entry (the last pad position is 3 squares before it).
15. DO-OVER (Helper pack, 3 per board): after seeing a card, a seat with do-overs may discard it and draw again (the new card must be played; a do-over may be used again on the new card while any remain). Not available on Again (it plays at once).
16. Win: the first seat (or team) with ALL its pawns Home wins at once. Session wins are tallied across boards; Play Again rotates the starter.
17. NO MOVE: if the card has no legal play, the turn passes (announcement `bump_run_ann_no_move`). A seat with a do-over is offered Do-over or Pass instead.
18. Pile empty at a draw: the discards are shuffled into a new pile first (the card in play is never in the pile).
19. Teams (4 seats, option): seats 0 and 2 against 1 and 3 (partners sit opposite). On your turn you may move ANY pawn of your team, yours or your partner's; a partner's pawn keeps its own side's exit square and lane. Bump, Swap and Chase only target the other team; landing on a team mate's pawn is illegal; Shield may cover a team mate's pawn; Free Exit brings out any team pawn from its own Nest. The team wins when all its pawns (2 x pawns per seat) are Home; both members are winners.
20. Skip obvious moves (option): the engine is unchanged; the screen auto-plays a single legal move after a 600 ms preview.

### Edge cases at a glance
| Situation | Result |
|---|---|
| Hop would pass Home | That pawn has no move with this card (reason `EXACT_ROLL`) |
| Hop lands on own pawn | Illegal for that pawn (`OWN_PAWN`) |
| Hop lands on shielded rival | Illegal for that pawn (`SHIELDED`) |
| Hop lands in lane on own pawn | Illegal |
| Several pawns have moves | The player chooses; a unique move may be auto-played |
| Star card, pawn in Nest, exit blocked by own pawn | Exit not offered; other legal plays still are |
| Swap, no rival on the ring or no own ring pawn | No move; pass (or do-over) |
| Swap would put a pawn on a pad | No pad hop |
| Chase, nearest rival ahead is shielded | No Chase move for that pawn; if none, Hop 4 |
| Chase, no rival within 12 | Plays as Hop 4 (only if no pawn has a Chase move) |
| Exact finish: pawn on 38 | Only a Hop 1 can finish it |
| Again, then pile empty | Reshuffle, then draw |
| All pawns of a seat Home before the others (individual game) | The board ends at once with that seat the winner |
| Team: partner's pawn blocks the exit | Same as an own pawn |

## 2.2 Turn state machine
Engine phases: `AWAIT_DRAW`, `AWAIT_CHOICE`, `OVER`. A seat on turn acts; computers act through `playBotStep`.
| From | Trigger (who) | Guard | To | Effect |
|---|---|---|---|---|
| AWAIT_DRAW | draw (seat on turn) | none | AWAIT_CHOICE | card in play, event DRAW; reshuffle first if the pile was empty |
| AWAIT_DRAW | draw | card is Again | AWAIT_DRAW (same seat) | event AGAIN, card to discard |
| AWAIT_CHOICE | playMove (seat) | move in `legalMoves` | AWAIT_DRAW (next seat) or OVER | move, pad hop, bumps, shield expiry for the NEXT seat, win check |
| AWAIT_CHOICE | doOver (seat) | doOvers > 0 | AWAIT_CHOICE (new card) | doOvers - 1, old card to discard, event DO_OVER |
| AWAIT_CHOICE | pass (seat) | `legalMoves` empty | AWAIT_DRAW (next seat) | event NO_MOVE |
| AWAIT_DRAW | draw | card has no legal move, seat has no do-over | AWAIT_DRAW (next seat) | event NO_MOVE at once (the card is shown for 1 s by the screen) |
| any | wrong seat or phase | n/a | unchanged | rejected with a `BumpRunReject` reason |
Shield expiry: shields owned by a seat are cleared at the start of that seat's next turn (not after an Again).
Hidden information: only the order of the face-down pile. The card in play, the discards, every pawn, shield and do-over count are public. No hands exist, so pass-and-play needs no privacy screen. The Hard bot may count cards from public information only.

## 2.3 Content tables

### Deck (54 cards, ids dense, in composition order)
| Card | Copies | Ids | Star (may exit) | Effect |
|---|---|---|---|---|
| Hop 1 | 6 | 0-5 | yes | rule 5 / 6 |
| Hop 2 | 6 | 6-11 | no | rule 5 |
| Hop 3 | 5 | 12-16 | no | rule 5 |
| Hop 4 | 5 | 17-21 | no | rule 5 |
| Hop 5 | 5 | 22-26 | no | rule 5 |
| Hop 6 | 4 | 27-30 | yes | rule 5 / 6 |
| Hop 8 | 3 | 31-33 | no | rule 5 |
| Hop 10 | 3 | 34-36 | no | rule 5 |
| Swap Places | 4 | 37-40 | no | rule 9 |
| Chase | 3 | 41-43 | no | rule 10 (fallback Hop 4) |
| Free Exit | 4 | 44-47 | yes | rule 11 (fallback Hop 3) |
| Again | 3 | 48-50 | no | rule 12 |
| Shield | 3 | 51-53 | no | rule 13 (fallback Hop 2) |
Total 54. Hop cards 37 (sum of values 156, mean 4.2 per hop card); 14 cards can bring a pawn out (25.9 % per draw).

### Board definition (machine readable)
```
ring       : 36 squares, 0..35, clockwise from the top-left corner of a 10 x 10 grid (top row 0..9, right column 10..18, bottom row 19..27 right to left, left column 28..35 upward)
sides      : side 0 = top, 1 = right, 2 = bottom, 3 = left
exit       : [5, 14, 23, 32]          (progress 0 of each side)
laneEntry  : [4, 13, 22, 31]          (the ring square just before the exit square; progress 35)
lane       : 3 private squares per side (progress 36, 37, 38), then HOME (progress 39)
pads       : {1, 10, 19, 28}  hop +3, option
nest       : progress -1 (off board)
square(side, p) = (exit[side] + p) mod 36, for p in 0..35
progress(side, square) = (square - exit[side]) mod 36
seats      : 2 seats -> sides [0, 2]; 3 seats -> [0, 1, 2]; 4 seats -> [0, 1, 2, 3]; teams: seats 0 and 2 against 1 and 3
pads relative to a side: progress 5, 14, 23, 32 (hop lands on 8, 17, 26, 35: never past 35)
```
```
 . . . . . . . . . .      ring numbers, 10 x 10 grid
  0  1  2  3  4  5  6  7  8  9
 35                         10
 34                         11
 33                         12
 32                         13
 31                         14
 30                         15
 29                         16
 28                         17
 27 26 25 24 23 22 21 20 19 18
```
(`..` is the inside of the board: lanes and Homes are drawn there by the screen; the engine only knows progress 36 to 39.)

## 2.4 Bot behaviour
Decisions: draw (always), which legal move, whether to use a do-over. The Random is derived from the state (`Random(turnSerial * 31 + seat)`), never the deck random. Delay 0.8 to 1.5 s scaled by game speed. All levels only ever choose legal moves.
| Level | Move choice | Do-over |
|---|---|---|
| Easy | 35 % uniformly random legal move; otherwise the legal move with the largest progress gain (exit counts 25, Home counts 100). No sense of danger. | Never (human-feeling) |
| Medium | Highest score of: Home +100; bump +30 + victim progress; exit +25; entering the lane +15; plain step = progress gained; swap = own gain - 0.8 x victim gain + 10; shield = 5 + 0.2 x progress; MINUS exposure = sum over unshielded rival ring pawns that could reach the landing square next turn by a Hop of that exact distance (distance 1 to 10): (copies of that Hop value / 54) x (pawn progress + 10); a small random jitter below 1 breaks ties | When the best score is below 12 |
| Hard | Medium scores, but exposure uses the exact remaining-card distribution (54 minus the discards since the last reshuffle minus the card in play, all public), also counting Chase and Swap threats, plus one ply: after each candidate it also scores the best reply of the next seat | When the expected best score of a redraw (average over the remaining distribution) exceeds the current best by more than 3 |
Hard runs on a background dispatcher; if it exceeds 60 ms it falls back to Medium for that decision.

## 2.5 Handicap rule
Helper pack, Hub row "Helper pack": `none` (default), `all`, or one seat `1..4`. Each chosen seat starts with pawn 0 already on its exit square and holds 3 do-overs for the board. Measured in the prototype (equal Medium-style players): two seats, Classic, helper seat 56 % (+5.6 points); Quick 56.6 %; four seats Classic, one helper seat 28.4 % vs 25 % (+3.4). Do-overs alone are weak (2 do-overs +2 to 3 points, 4 do-overs +3.4, 8 do-overs +6.7), which is why the pack includes the head start. Test `helperPackWinRateDeltaIsBounded` asserts the two-seat delta is between 2 and 9 points.

## 2.6 UI states (mockup notes: warm cream, Baloo type, 56 dp+ targets, shape + colour tokens)
| State | What is on screen |
|---|---|
| 1 Setup (Hub panel) | Mode tiles, Race length, Spring pads, Skip obvious moves, Helper pack switch row, Team play (only at four seats), people / computers steppers, computer level, Start |
| 2 Start | Ring board, four nests with pawns, the starter's token pulsing, pile glowing; status `bump_run_ann_start` |
| 3 Mid-game | Board, pawns with numbers, shield rings, pile and last card, seat strips (shape + colour, pawns Home n of m, do-overs), legal destinations lit, Do-over / Pass buttons only when relevant, status line |
| 4 Post-game | Winner (or team) banner, Play Again, Back to Menu, session tally |
| 5 Score card | Per seat: pawns Home, bumps made / taken, turns taken, session wins |

## 2.7 First-run tutorial script (coach card; every line under 120 characters)
| Step | EN line | Key |
|---|---|---|
| 1 | Tap the card pile. Every card tells you how to move. | `coach_bump_run_s1` |
| 2 | Race your pawns around the loop, then up your lane to Home. | `coach_bump_run_s2` |
| 3 | Land on a rival pawn and you bump it back to its Nest. | `coach_bump_run_s3` |
| 4 | Stars on a card bring a pawn out. Swap, Chase and Shield change the race. | `coach_bump_run_s4` |
| 5 | You need the exact number to reach Home. Every pawn Home wins. | `coach_bump_run_s5` |
| fact | Race-and-bump games descend from Pachisi, an ancient Indian game still played today. | `coach_bump_run_fact` |
| tip 1 | Tap the card pile, then tap a pawn to move it. | `tip_bump_run_1` |
| tip 2 | Landing on a rival bumps it home. Watch for pawns just behind you! | `tip_bump_run_2` |
| tip 3 | A star on a card can bring a pawn out of the Nest. | `tip_bump_run_3` |
Integration (coordinator): `GameCoach` entry reading `coach_bump_run_s1..s5` and `_fact`; `GameTips` entry `"bump-run"` with the three tips (literals equal the resources).

## 2.8 Exact EN strings the UI needs
All keys exist in both locales in `bump_run_strings.xml` (generated from one data source).

| Key | English | Group |
|---|---|---|
| `bump_run_help_title` | Bump and Run | Screen |
| `bump_run_help_text` | Draw a card and move a pawn. Race all your pawns around the loop and up your lane to Home. Land on a rival and you bump it back to its Nest. Swap, Chase and Shield cards change the race. You need the exact number to reach Home. | Screen |
| `bump_run_tap_pile` | Tap the card pile | Screen |
| `bump_run_pile_label` | Card pile, %1$d cards left | Screen |
| `bump_run_last_card` | Last card: %1$s | Screen |
| `bump_run_card_label` | Card: %1$s. %2$s | Screen |
| `bump_run_do_over_button` | Do-over | Screen |
| `bump_run_do_overs_left` | Do-overs left: %1$d | Screen |
| `bump_run_pass_button` | Pass | Screen |
| `bump_run_pick_pawn` | Tap one of your pawns. | Screen |
| `bump_run_pick_target` | Tap the lit square. | Screen |
| `bump_run_pick_rival` | Tap the rival pawn to trade places with. | Screen |
| `bump_run_nest_label` | %1$s nest, %2$d pawns | Screen |
| `bump_run_home_label` | %1$s home, %2$d of %3$d pawns | Screen |
| `bump_run_pawn_label` | %1$s, pawn %2$d, %3$s | Screen |
| `bump_run_state_nest` | in the Nest | Screen |
| `bump_run_state_loop` | on square %1$d | Screen |
| `bump_run_state_lane` | in the lane, step %1$d of %2$d | Screen |
| `bump_run_state_home` | Home | Screen |
| `bump_run_state_shielded` | shielded | Screen |
| `bump_run_square_label` | Square %1$d | Screen |
| `bump_run_square_exit_label` | Square %1$d, exit square for %2$s | Screen |
| `bump_run_square_pad_label` | Square %1$d, spring pad | Screen |
| `bump_run_leave_title` | Leave this game? | Screen |
| `bump_run_leave_body` | The game is still going. Leaving now does not count as a win or a loss. | Screen |
| `bump_run_team_1` | Team 1 | Teams |
| `bump_run_team_2` | Team 2 | Teams |
| `bump_run_shape_circle` | circle | Player shapes |
| `bump_run_shape_square` | square | Player shapes |
| `bump_run_shape_triangle` | triangle | Player shapes |
| `bump_run_shape_diamond` | diamond | Player shapes |
| `bump_run_card_hop` | Hop %1$d | Card names |
| `bump_run_card_swap` | Swap Places | Card names |
| `bump_run_card_chase` | Chase | Card names |
| `bump_run_card_free_exit` | Free Exit | Card names |
| `bump_run_card_again` | Again | Card names |
| `bump_run_card_shield` | Shield | Card names |
| `bump_run_desc_hop` | Move one pawn forward %1$d squares. | Card descriptions |
| `bump_run_desc_hop_star` | Move one pawn forward %1$d squares, or bring a pawn out of the Nest. | Card descriptions |
| `bump_run_desc_swap` | Trade places with a rival pawn on the loop. | Card descriptions |
| `bump_run_desc_chase` | Leap to the next rival ahead and bump it. No rival ahead? Hop 4. | Card descriptions |
| `bump_run_desc_free_exit` | Bring a pawn out of the Nest. None in the Nest? Hop 3. | Card descriptions |
| `bump_run_desc_again` | Draw another card and play it too. | Card descriptions |
| `bump_run_desc_shield` | Shield a pawn on the loop until your next turn. None? Hop 2. | Card descriptions |
| `bump_run_ann_start` | %1$s goes first. Tap the card pile. | Announcements |
| `bump_run_ann_start_you` | You go first. Tap the card pile. | Announcements |
| `bump_run_ann_turn` | %1$s, draw a card. | Announcements |
| `bump_run_ann_turn_you` | Your turn. Tap the card pile. | Announcements |
| `bump_run_ann_draw` | %1$s drew %2$s. | Announcements |
| `bump_run_ann_draw_you` | You drew %1$s. | Announcements |
| `bump_run_ann_hop` | %1$s moved a pawn %2$d squares. | Announcements |
| `bump_run_ann_hop_you` | You moved a pawn %1$d squares. | Announcements |
| `bump_run_ann_exit` | %1$s brought a pawn out of the Nest. | Announcements |
| `bump_run_ann_exit_you` | You brought a pawn out of the Nest. | Announcements |
| `bump_run_ann_bump` | %1$s bumped a pawn of %2$s back to the Nest! | Announcements |
| `bump_run_ann_bump_you` | You bumped a pawn of %1$s back to the Nest! | Announcements |
| `bump_run_ann_bumped_you` | %1$s bumped your pawn back to the Nest. | Announcements |
| `bump_run_ann_swap` | %1$s swapped places with a pawn of %2$s. | Announcements |
| `bump_run_ann_swap_you` | You swapped places with a pawn of %1$s. | Announcements |
| `bump_run_ann_shield` | %1$s shielded a pawn until the next turn. | Announcements |
| `bump_run_ann_shield_you` | You shielded a pawn until your next turn. | Announcements |
| `bump_run_ann_pad` | A spring pad hops the pawn ahead 3 squares! | Announcements |
| `bump_run_ann_home` | %1$s got a pawn Home: %2$d of %3$d. | Announcements |
| `bump_run_ann_home_you` | You got a pawn Home: %1$d of %2$d. | Announcements |
| `bump_run_ann_again` | %1$s draws again. | Announcements |
| `bump_run_ann_again_you` | Again! Draw another card. | Announcements |
| `bump_run_ann_no_move` | %1$s has no move and passes. | Announcements |
| `bump_run_ann_no_move_you` | You have no move. Your turn passes. | Announcements |
| `bump_run_ann_do_over` | %1$s used a do-over. | Announcements |
| `bump_run_ann_do_over_you` | You used a do-over. Drawing again. | Announcements |
| `bump_run_ann_reshuffle` | The pile is empty. Shuffling the cards. | Announcements |
| `bump_run_ann_win` | %1$s wins! | Announcements |
| `bump_run_ann_win_you` | You win! | Announcements |
| `bump_run_ann_win_team` | %1$s win as a team! | Announcements |
| `bump_run_why_wait` | It is not your turn yet. | Why lines |
| `bump_run_why_cpu` | The computer is taking its turn. | Why lines |
| `bump_run_why_draw_first` | Draw a card first. | Why lines |
| `bump_run_why_cannot_move` | That pawn cannot move with this card. | Why lines |
| `bump_run_why_exact` | You need the exact number to reach Home. | Why lines |
| `bump_run_why_own` | Your own pawn is in the way. | Why lines |
| `bump_run_why_shield` | A shield protects that pawn. | Why lines |
| `bump_run_why_over` | The game is over. | Why lines |
| `bump_run_result_win` | %1$s wins! | Post-game |
| `bump_run_result_win_you` | You win! | Post-game |
| `bump_run_result_win_team` | %1$s win as a team! | Post-game |
| `bump_run_play_again` | Play again | Post-game |
| `bump_run_back_to_menu` | Back to menu | Post-game |
| `bump_run_score_title` | Race report | Score card |
| `bump_run_score_line` | %1$s: %2$d of %3$d pawns Home | Score card |
| `bump_run_score_bumps` | %1$s: bumped %2$d, got bumped %3$d | Score card |
| `bump_run_score_turns` | Turns taken: %1$d | Score card |
| `bump_run_score_session` | Session: %1$s | Score card |
| `coach_bump_run_s1` | Tap the card pile. Every card tells you how to move. | Coach card |
| `coach_bump_run_s2` | Race your pawns around the loop, then up your lane to Home. | Coach card |
| `coach_bump_run_s3` | Land on a rival pawn and you bump it back to its Nest. | Coach card |
| `coach_bump_run_s4` | Stars on a card bring a pawn out. Swap, Chase and Shield change the race. | Coach card |
| `coach_bump_run_s5` | You need the exact number to reach Home. Every pawn Home wins. | Coach card |
| `coach_bump_run_fact` | Race-and-bump games descend from Pachisi, an ancient Indian game still played today. | Coach card |
| `tip_bump_run_1` | Tap the card pile, then tap a pawn to move it. | Tips |
| `tip_bump_run_2` | Landing on a rival bumps it home. Watch for pawns just behind you! | Tips |
| `tip_bump_run_3` | A star on a card can bring a pawn out of the Nest. | Tips |

Game Hub texts (key = `hubKey(english)`):

| Key | English | Status |
|---|---|---|
| `hub_bump_and_run` | Bump and Run | defined in this file |
| `hub_draw_a_card_race_your_pawns_home_and_bump_your_r_86712b` | Draw a card, race your pawns home and bump your rivals back. | defined in this file |
| `hub_race_length` | Race length | defined in this file |
| `hub_more_pawns_each_makes_a_longer_race` | More pawns each makes a longer race. | defined in this file |
| `hub_quick_race` | Quick race | defined in this file |
| `hub_2_pawns_each` | 2 pawns each. | defined in this file |
| `hub_classic_race` | Classic race | defined in this file |
| `hub_3_pawns_each` | 3 pawns each. | defined in this file |
| `hub_long_race` | Long race | defined in this file |
| `hub_4_pawns_each` | 4 pawns each. | defined in this file |
| `hub_spring_pads` | Spring pads | defined in this file |
| `hub_on_landing_on_a_pad_hops_the_pawn_3_squares_ahea_79cf36` | On: landing on a pad hops the pawn 3 squares ahead. | defined in this file |
| `hub_skip_obvious_moves` | Skip obvious moves | defined in this file |
| `hub_on_when_only_one_move_is_possible_it_is_played_f_292999` | On: when only one move is possible, it is played for you. | defined in this file |
| `hub_helper_pack` | Helper pack | defined in this file |
| `hub_three_do_overs_and_one_pawn_already_out_of_the_n_dd5f4c` | Three do-overs and one pawn already out of the Nest. | defined in this file |
| `hub_no_helper_pack` | No helper pack | defined in this file |
| `hub_helper_pack_for_everyone` | Helper pack for everyone | defined in this file |
| `hub_helper_pack_player_1` | Helper pack: Player 1 | defined in this file |
| `hub_helper_pack_player_2` | Helper pack: Player 2 | defined in this file |
| `hub_helper_pack_player_3` | Helper pack: Player 3 | defined in this file |
| `hub_helper_pack_player_4` | Helper pack: Player 4 | defined in this file |

---

# PART 3: SCALE

## 3.1 Game length by mode
Measured (prototype, total turns of all seats, mean with 95th percentile in brackets, bumps per board):
| Length (pawns) | 2 seats | 3 seats | 4 seats |
|---|---|---|---|
| Quick (2) | 60 (92), 2.1 bumps | 92 (144), 5.6 | 127 (204), 10.5 |
| Classic (3) | 90 (131), 4.7 | 148 (219), 12.6 | 212 (324), 22.7 |
| Long (4) | 123 (175), 7.8 | 205 (295), 19.9 | 307 (449), 36.7 |
The winner's own turns are about 30 (Quick), 45 to 53 (Classic), 62 to 77 (Long). At roughly 5 s per computer turn and 8 s per human turn: Quick 2 seats 5 to 8 minutes, Quick 4 seats 10 to 17, Classic 4 seats 18 to 28, Long 4 seats 30 to 45. Spring pads save about 6 % (Classic four seats: 226 turns without, 212 with). A random-play policy needs about 5 % more turns than the greedy one (95 vs 90, two-seat Classic). Zero stalled boards in 500 four-seat Long boards. Team boards are expected within +-15 % of the four-seat individual figure with the same pawns per seat (to be measured in Phase 4). Budget the test enforces (`lengthByModeStaysInBudget`): mean total turns within +-15 % of the table.

## 3.2 Players and CPU fill
2 to 4 seats; humans 1 to 4, computers 0 to 3 (Hub `buildHubLaunch`); teams need exactly four seats (the Hub pads with a computer to an even table of four).

## 3.3 Deterministic seeds
One `nextLong()` from the injected `Random` per board becomes the board seed; shuffle with `Random(seed)`, reshuffle n with `Random(seed xor n * 0x9E3779B97F4A7C15)`. Snapshots store the seed AND the explicit pile / discard order, so restore never depends on the Kotlin RNG algorithm. Bots derive their Random from `(turnSerial, seat)`. A whole bot-only board is therefore a pure function of (seed, rules, roster, levels), which the tests exploit.

## 3.4 Performance budget
| Item | Budget |
|---|---|
| Frame time | 16.6 ms at 60 Hz (8.3 ms at 120 Hz); board canvas under 4 ms; pawn hops animated by transforms, no allocation per frame |
| `legalMoves` | under 0.2 ms (at most 4 seats x 4 pawns x a few targets; at most ~16 moves) |
| Medium bot decision | under 2 ms |
| Hard bot decision | typically under 25 ms on a Fold 5-class device, run on `Dispatchers.Default`, hard cap 60 ms then fall back to Medium; never on the main thread |
| Snapshot size | under 3 KB (estimate ~1.1 KB: 54 card ids, up to 16 pawns) |
| Allocation | under 50 small objects per move; state is immutable lists replaced per move |

## 3.5 Persistence and resume
`ResumableGame` for vs-computer and pass-and-play; null after the match ends. Restore validates the roster, sides, array sizes and ranges, phase and event names, card coverage (pile + discard + card = the 54 ids exactly once), and the KDoc list in `BumpRunGame` (no two pawns on one ring square, shields only on ring pawns, phase and card consistency, winner equals the side with all pawns Home, teams only with four seats, do-overs 0 to 3). Any failure returns false and the Hub starts a fresh board. Session wins, starter index and difficulty are saved.

## 3.6 Multiplayer plan
Same screen now; Nearby / Online later for adults only (kid profiles never see network modes), host-authoritative.
| Message | Direction | Content |
|---|---|---|
| `RequestState` | guest to host | none |
| `DrawRequest` | guest to host | none |
| `MoveRequest` | guest to host | `pawn`, `kind`, `to`, `target` (the host re-derives `legalMoves` and rejects anything else) |
| `DoOverRequest` / `PassRequest` | guest to host | none |
| `StateSync` | host to all | version, pawns, shields, doOvers, seat on turn, phase, card in play, discard, pile COUNT, event, serials, stats |
| `PlayAgain` | guest to host | none |
Hidden information: the pile order only; the host holds it and guests get the count. No hands to redact. A guest is not resumable. Every message is checked against `fromPlayerId` equals the seat on turn (transport identity hardening). Quick-chat presets only (no free text). Effort L, Phase 5.

## 3.7 Phased delivery
| Phase | Content | Effort | Proves |
|---|---|---|---|
| 0 Rules | Real `BumpRunEngine` (all cards, bumps, swaps, shields, pads, exact finish), board / deck / rules / property tests | M | Rules complete and deterministic; lengths and helper-pack numbers match the prototype |
| 1 Bots | Easy / Medium / Hard, determinism and strength ordering tests | M | Three distinguishable levels, no stalls, Hard under budget |
| 2 Module | Wrapper polish, snapshot validation, Hub registration by the coordinator | S | Resume safe, Hub / localisation gates green |
| 3 Screen | Ring canvas, pawns, pile, legal highlights, two-tap moves, a11y labels, five states | L | A family can play a board unaided; playtest with 6 to 9 year olds |
| 4 Polish + teams | Sound, haptics, boing / hop animation, coach card, score card, team mode | M | Meets the quality bar; team rules hold |
| 5 Network | Nearby / Online for adults, quick-chat | L | Remote play |

## 3.8 Risk register
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Too close to a famous product | Medium | High | Own deck, board, names and effects (no split card, no backward card, no apology card); legal-term scan test; trademark search (Q6) |
| Boards too long | Medium | Medium | Default Quick; Skip obvious moves; pads; length labelled by pawns; Long flagged |
| Bumping upsets young players | Medium | Medium | Soft animation and wording, Helper pack, v1 Gentle bump |
| Rules complexity (13 card types) | Medium | Medium | Card text on every card, tooltip / TalkBack descriptions, fallback rules remove dead cards, coach card |
| Bot strength unbalanced | Medium | Medium | Strength-ordering test over many boards; tune weights, not rules |
| Hard bot too slow | Low | Medium | Background dispatcher, 60 ms cap, fallback to Medium |
| Snapshot corruption | Low | High | Validation + tests, restore returns false on any doubt |
| Team mode edge cases | Medium | Medium | Dedicated `BumpRunTeamTest`, held to Phase 4 |
| Resource name collision with parallel agents | Low | High (build break) | Unique prefixes (`bump_run_`, `tip_bump_run_`, `coach_bump_run_`), unique hub texts (one clash with another game's "Game length" was found and avoided by using "Race length") |
| Spanish text unreviewed | Medium | Low | Real Spanish by the author, flagged for native review in an XML comment |

## 3.9 Test plan
All of these exist as `@Ignore`d stubs with exactly these names (126 tests, 9 classes).

**BumpRunBoardTest** (Phase 0, 9 tests): `ringHasThirtySixSquaresAndFourExits`, `exitAndLaneEntrySquaresAreAdjacent`, `padsNeverCoincideWithExitsOrLaneEntries`, `squareOfAndProgressOnAreInverse`, `progressAfterFullLapEndsOnTheLaneEntry`, `padHopNeverCrossesTheLaneEntry`, `sidesForTwoSeatsAreOpposite`, `sidesForThreeAndFourSeats`, `twoPlayerPartnersSitOppositeInTeamMode`

**BumpRunDeckTest** (Phase 0, 9 tests): `deckHasFiftyFourCards`, `compositionMatchesTheDocTable`, `starredCardsAreHopOneHopSixAndFreeExit`, `fourteenCardsCanBringAPawnOut`, `cardIdsAreDenseAndUnique`, `shuffleIsDeterministicForASeed`, `shuffleIsAPermutationOfAllCards`, `reshuffleKeepsEveryCardExactlyOnce`, `cardInPlayIsNeverInPileOrDiscard`

**BumpRunRulesTest** (Phase 0, 41 tests): `hopMovesOnePawnTheCardValue`, `hopOverPawnsIsAllowed`, `hopLandingOnAnOwnPawnIsIllegal`, `hopLandingOnARivalBumpsItToTheNest`, `hopLandingOnAShieldedRivalIsIllegal`, `hopPastHomeIsIllegalExactCountRequired`, `hopOntoHomeExactlyIsLegalForManyPawns`, `lanePawnsAreNeverBumped`, `twoOwnPawnsCannotShareALaneSquare`, `starCardsMayBringAPawnOutOfTheNest`, `exitBumpsARivalStandingOnTheExitSquare`, `exitIsBlockedByAnOwnPawnOnTheExitSquare`, `exitIsBlockedByAShieldedRivalOnTheExitSquare`, `freeExitWithNoPawnInTheNestPlaysAsHopThree`, `freeExitWithABlockedExitPlaysAsHopThree`, `swapExchangesSquaresWithARivalOnTheRing`, `swapRecomputesProgressForBothPawns`, `swapNeverTargetsNestLaneHomeOrShieldedPawns`, `swapWithNoLegalTargetIsNoMove`, `chaseBumpsTheNearestRivalAheadWithinTwelve`, `chasePassesOverOwnPawns`, `chaseBlockedByShieldedNearestRivalIsNotLegalForThatPawn`, `chaseWithNoRivalAheadPlaysAsHopFour`, `chaseNeverLooksIntoTheLane`, `shieldProtectsAPawnUntilItsOwnersNextTurn`, `shieldWithNoRingPawnPlaysAsHopTwo`, `againDrawsAnotherCardForTheSameSeat`, `againChainsAreFiniteBecauseTheDeckIs`, `padHopsThreeAndBumpsARivalOnArrival`, `padHopIsCancelledByAnOwnPawnOrShieldedRivalOnArrival`, `padDoesNotTriggerOnSwapBumpOrExit`, `padsOffMakesPadsPlainSquares`, `noLegalMoveMeansNoMoveAndTheTurnPasses`, `noLegalMoveWithADoOverOffersDoOverOrPass`, `doOverDiscardsTheCardAndDrawsAgain`, `doOverCountNeverGoesNegative`, `singleLegalMoveIsAutoPlayedWhenTheOptionIsOn`, `emptyPileReshufflesTheDiscards`, `allPawnsHomeEndsTheBoard`, `turnPassesClockwiseSkippingNothing`, `helperPackPutsOnePawnOnItsExitAndGivesThreeDoOvers`

**BumpRunTeamTest** (Phase 4, 9 tests): `teamsOnlyExistWithFourSeats`, `partnersAreSeatsZeroAndTwoAgainstOneAndThree`, `aSeatMayMoveAnyPawnOfItsTeam`, `landingOnAPartnersPawnIsIllegal`, `bumpAndSwapNeverTargetAPartner`, `shieldMayCoverAPartnersPawn`, `teamWinsWhenAllItsPawnsAreHome`, `bothTeamMatesAreMarkedWinners`, `freeExitBringsOutAnyTeamPawnFromItsOwnNest`

**BumpRunGameTest** (Phase 2, 10 tests): `drawThenPlayMovesTheChosenPawn`, `playingAnIllegalMoveIsRejectedWithAReason`, `drawingTwiceIsRejected`, `chanceComesOnlyFromTheInjectedRandom`, `firstOpenerIsHonoured`, `playAgainRotatesTheStarter`, `leaveSessionRecordsTheBoardsWon`, `computerSeatNeverGetsAHumanTurn`, `sessionWinsAccumulateAcrossBoards`, `passIsOnlyAllowedWithNoLegalMove`

**BumpRunBotTest** (Phase 1, 11 tests): `easyBotNeverStallsTheTable`, `easyBotIgnoresDangerButNeverPlaysIllegally`, `mediumBotPrefersHomeThenBumpThenExit`, `mediumBotAvoidsLandingJustAheadOfARival`, `mediumBotUsesADoOverOnlyBelowTheThreshold`, `hardBotCountsTheCardsLeftInTheDeck`, `hardBotChoiceIsStableForAState`, `hardBotStaysUnderThirtyMillisecondsPerDecision`, `botNeverTouchesTheDeckRandom`, `botsPlayEveryLevelToTheEndOnAThousandSeeds`, `hardBeatsMediumBeatsEasyOverManyBoards`

**BumpRunSnapshotTest** (Phase 2, 15 tests): `snapshotRoundTripRestoresEveryField`, `snapshotIsNullAfterTheMatchIsOver`, `corruptJsonIsRejected`, `truncatedJsonIsRejected`, `wrongRosterIsRejected`, `pileDiscardAndCardMustCoverAllFiftyFourIds`, `twoPawnsOnOneRingSquareIsRejected`, `shieldOnANonRingPawnIsRejected`, `awaitChoiceNeedsACardInPlay`, `teamsWithoutFourSeatsAreRejected`, `winnerMustMatchPawnsHome`, `pawnProgressOutOfRangeIsRejected`, `snapshotStaysUnderThreeKilobytes`, `restoredBoardContinuesIdenticallyToTheOriginal`, `hugeOrNastyInputNeverThrows`

**BumpRunPropertyTest** (Phase 0/1, 10 tests): `randomLegalPlayAlwaysTerminatesOnTenThousandSeeds`, `legalMovesNeverLeaveTwoPawnsOnOneSquare`, `cardsAreConservedEveryTurn`, `pawnProgressOnlyDecreasesByABump`, `exactlyOneWinningSideEverEnds`, `noStateEverFailsValidate`, `legalMovesAreEmptyOnlyWhenNoPawnCanUseTheCard`, `lengthByModeStaysInBudget`, `helperPackWinRateDeltaIsBounded`, `noStallsInThreeThousandBotBoards`

**BumpRunHubSpecsTest** (Phase 2, 12 tests): `theSpecIsAFamilyGameFromAgeSix`, `theSpecIsNotNetworked`, `everyHubTextHasAnEnglishAndASpanishString`, `spanishPlaceholdersMatchEnglish`, `optionKeysAndDefaultsMatchTheDoc`, `teamToggleIsHiddenBelowFourSeats`, `everyBumpRunKeyExistsInBothLocales`, `tutorialLinesAreUnderOneHundredTwentyCharacters`, `tipsMatchTheStringResources`, `noProtectedNamesInStringsSourcesOrDocs`, `kidProfileSeesTheGameAndNoNetworkMode`, `accentColoursPassContrast`


Property tests: random legal play terminates on 10 000 seeds; no two pawns ever share a ring square; cards are conserved every turn; progress only drops through a bump; `legalMoves` is empty only when no pawn can use the card (including the fallbacks); no state fails `validate`. Bot determinism: same state gives same decision; bots never use the deck random; Hard stays under budget. Snapshot corruption: garbage, truncation, wrong roster, bad card coverage, two pawns on a square, shield on a non-ring pawn, winner mismatch, out-of-range progress. Localisation: every key in both locales, placeholder parity, tutorial lines at most 120 characters, tips equal the resources, protected-term scan.

---

# PART 4: SCAFFOLD

Compiling skeletons only (not compiled by the author). Package `com.gamesuite.games.bumprun`:
| File | Content |
|---|---|
| `BumpRunBoard.kt` | `BumpLength`, `BumpRunBoard` (ring constants, exits, lane entries, pads, `sidesFor`, `squareOf`, `progressOn`) |
| `BumpRunCards.kt` | `BumpCardKind`, `BumpCard`, `BumpRunDeck` (54 cards, composition table) |
| `BumpRunState.kt` | `BumpRunEvent`, `BumpRunReject`, `BumpPhase`, `BumpMoveKind`, `BumpMove`, `BumpRunPlayer`, `BumpRunRules`, `BumpRunState` |
| `BumpRunEngine.kt` | `BumpRunEngine` interface, `StubBumpRunEngine` (valid opening board incl. helper pack, other methods safe values), `BumpRunBot` (first legal move, never a do-over) |
| `BumpRunGame.kt` | `BumpRunGame : GameModule, ResumableGame`, snapshot DTOs, partial validation (rest in the KDoc), `parseBumpRunHelper` |
| `BumpRunHubSpecs.kt` | `BumpRunHubSpecs.bumpRun` (FAMILY, minAge 6, team toggle), accent and glyph constants; NOT registered |
Strings: `res/values/bump_run_strings.xml`, `res/values-es/bump_run_strings.xml`. Tests: `app/src/test/java/com/gamesuite/games/bumprun/*Test.kt`.

Integration checklist for the coordinator (none of it done):
1. `GameHubSpecs.baseSpecs()`: add `BumpRunHubSpecs.bumpRun` (BOARD section). Do not add `"bump-run"` to `networkedGames`.
2. `GameCatalog` glyph `"bump-run" -> "🏃"` (`BumpRunHubSpecs.GLYPH`).
3. `GameThemes.accents["bump-run"] = GameAccent(0xFF0B5A6B, 0xFF6FD0DE)`; contrast computed 7.6 / 6.0 (light) and 9.6 / 6.9 (dark), distinctness from other accents NOT checked.
4. Route and screen (`bump-run`, Phase 3), `GameTips`, `GameCoach` as in 2.7.
5. `GameAudienceTable` needs no row (the spec's own tags win).
6. Gate: `HubLocalizationTest`, `GameTipsTest`, `GameCoachTest`, `GameThemeTest`, `GameAudienceTest` after registration; then un-ignore Phase 0 tests.
