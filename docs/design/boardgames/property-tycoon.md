# Property Tycoon (`property-tycoon`): scope, script, scale, scaffold

Status: design + compile-only scaffold, 2026-11-07, branch `session/wave-3`. Nothing in the scaffold was built, compiled or run by its author (no Gradle, no emulator, no tests). Scaffold code lives in `app/src/main/java/com/gamesuite/games/propertytycoon/`; if the code and this document disagree, this document wins.

A buy-rent-build loop-board game for 2 to 6 seats on one device, in the generic style of "buy properties, collect rent, build, bankrupt your rivals" games. Play money only. Theme: an invented archipelago, all names, art, card text and numbers original.

Contents: 1 SCOPE, 2 SCRIPT, 3 SCALE, 4 SCAFFOLD.

---

# 1. SCOPE

## 1.1 Purpose
Give the Hub an adult-friendly long-form economic board game with strategy that is mostly negotiation and risk management, not reflex. It fills the gap between the short games and Backgammon/Chess: one session is 20 minutes (Quick) to 90 minutes (Standard), and it is the only game in the suite whose core loop is ownership, rent and trading.

## 1.2 Audience, age and kid safety
- Hub spec: `audience = GameAudience.ADULTS`, `minAge = 8`. `kidMaySee` therefore returns false for every kid age band (ADULTS is never shown to a KID profile), so buying, auctions, rent collection and bankruptcy never reach a child profile. No `GameAudienceTable` row is added and the id is not added to `KID_BLOCKED_GAME_IDS` (it is not gambling: no chance-for-stakes, pearls cannot be bought or cashed out).
- `minAge = 8` records the age an adult might teach it at and is what a future variant inherits.
- Later option (not MVP): a gentler "Junior" variant, `FAMILY`, minAge 8, same board with no bankruptcy (negative cash simply pledges tiles automatically, nobody is eliminated, a game ends at a fixed round with a "most tiles and sets" score, no auctions, no trading, rents capped at base x 3). It is a separate hub spec id (`property-tycoon-junior`) sharing the engine with a `junior` rules flag. It needs its own owner review of the "no one loses" framing.
- No online features in MVP, so no chat, no strangers. When Nearby/Online arrive they follow the existing kid rule (network modes are stripped for KID profiles by `visibleFor`).

## 1.3 Play-money-only statement
Currency is "pearls", an in-game token. No purchase, ad, reward or cash-out path exists or is planned; there is no way to buy pearls. Rebuy does not exist (a bankrupt player is out). Store copy and the start card both say "Pearls are play money only" (`pt_start_ready`). Same convention as Blackjack and Hold'em play chips (docs/audit/03-safety-privacy.md section 8), but with no casino chrome and no wagering.

## 1.4 Originality and legal guardrails (hard rule)
Generic mechanics are used freely: a ring of tiles, two dice, doubles, buy-or-auction, colour groups, rent that grows with buildings, mortgage-style pledging, even building, trading, bankruptcy. Everything expressive is authored fresh:
- Names: "Property Tycoon" (the requested id and title), pearls, Lantern Quay, the Sandbar, Free Anchorage, Riptide, Tide cards, Guild cards, cabins, resorts, ferries, and all 22 property names are invented. No token, board name, card sentence or colour-set name from any Hasbro, Mattel or other commercial product is used.
- Structure differences deliberately introduced (not just renames): colour groups have sizes 2, 2, 2, 3, 3, 3, 3, 4 (one group of four); the eight colours are Clay, Mist, Reed, Lagoon, Plum, Coral, Gold, Opal; ferries sit on tiles 6, 16, 26, 36 and card tiles on 3, 8, 17, 19, 32, 34 (not the positions classic boards use); utilities multiply the dice by 5 and 12; the building supply is 36 cabins and 14 resorts; stipend 160, fine 100, start cash 1,600; price, rent and build ladders are generated from this game's own formula (rent base = price/14, multiples 1, 5, 14, 38, 55, 72), not copied; Quick, Standard and Marathon length presets and a "Rising Tide" rent escalator are new.
- The four corners at 0, 10, 20, 30 are a property of any square ring. Card effects are the common categories (gain, pay, move, go to timeout, free pass, pay or collect each rival, repairs) because those are generic mechanics; every sentence is new.
- Process guard: test `noTileNameOrCardTextMatchesAnyTrademarkedReferenceList` (a deny-list file the owner or counsel maintains). Recommendation: one human legal read of the final names and art before any store listing. This document is not legal advice.
- Art: vector drawn in Compose, original, per docs/roadmap/QUALITY-BAR.md.

## 1.5 MVP vs v1 vs later

| Capability | MVP | v1 | Later |
|---|---|---|---|
| 40-tile ring, two dice, doubles, three-doubles timeout | yes | | |
| Buy unowned tile, rent, utilities and ferries rent | yes | | |
| Tax tiles, Tide and Guild decks (16 + 16) | yes | | |
| Timeout (the Sandbar): roll doubles, pay fine, free-pass card, forced fine on turn 3 | yes | | |
| Colour sets, rent doubling, cabins and resorts, even-build rule | yes | | |
| Debt flow by selling buildings, then bankruptcy, winner rule | yes | | |
| Quick, Standard, Marathon, handicap start cash | yes | | |
| 2 to 6 seats, CPU fill, Easy/Medium/Hard (buy/build/timeout policy, no trading) | yes | | |
| Save and resume, deterministic seeds, five UI states | yes | | |
| Auctions (declined tile, bankruptcy-to-bank tiles) | | yes | |
| Pledging (mortgages), redeem, 10 percent rule | | yes | |
| Trading UI + trade data model in engine, bot trade policy | | yes | |
| House-rule toggles: jackpot, double stipend, rent-free Sandbar | partly (jackpot, double stipend, rent-free are cheap: MVP) | trades/auctions toggles | |
| Rising Tide escalator | yes | | |
| Heat-aware bot valuation from simulation | | yes | |
| Nearby / Online host-authoritative play | | | yes |
| Junior variant (FAMILY) | | | yes |
| Shorter board (24-tile ring) | | | yes |
| Achievements, stats (rent collected, comebacks) | | | yes |

## 1.6 Explicitly out of scope for MVP
Auctions, pledging and the whole trading UI (gated by `PtRelease.AUCTIONS_ENABLED`, `PLEDGING_ENABLED`, `TRADES_ENABLED`, all false); Nearby/Online; Junior; chat; custom boards; any real-money feature; undo (dice are chance, same as Slide and Climb). Without pledging, a player short of cash after selling every building is bankrupt, which makes MVP games somewhat shorter and harsher; the Hub copy does not promise otherwise.

## 1.7 Accessibility for a dense board on a phone
- Ring is drawn in a zoomable container (pinch, double-tap, +/- buttons, "Show whole board" button `pt_btn_fit_board`). Default zoom frames the current player's token and the next 12 tiles. Landscape and Fold inner screen show the whole ring with the centre used for the current-tile card and the log; portrait phone shows a 3x3 tile window plus a bottom sheet.
- A current-tile card (`pt_a11y_current_tile`) always shows the tile the active token is on: name, owner, price, full rent table, pledge value, large text, so the small tiles never need to be read.
- 48 dp targets; tiles are never the only way to act (every action is a labelled button in the action bar).
- Colour is never the only cue: each colour group has a shape (triangle, circle, diamond, square, star, cross, hexagon, drop) drawn on tiles and badges; owners have a seat number and shape on their ownership flag.
- TalkBack: every tile has a label "`name, tile N, owned by X | unowned`", tokens are announced on move, dice announce their values, the event log is a polite live region. Large font: card sheets scroll; Start button reachable at 1.6x in landscape (known Hub defect list item, test it).
- Reduced motion (`LocalReducedMotion`): tokens jump with a short fade; dice show the result; `LocalGameSpeed` scales bot delays.
- Left/right-handed: action bar mirrors with the layout direction; no gesture-only action.

## 1.8 Dependencies
- `core`: `GameModule`, `ResumableGame`, `GameContext`, `GameResult`, `GameHubSpec` and friends, `GameAudience`, `HubOptions`, `HubChoices`. `settings.CpuDifficulty`. kotlinx.serialization (already used by Slide and Climb). Compose runtime state.
- Coordinator-owned shared files that must change later (not touched by this work): `GameHubSpecs` (register spec), `GameCatalog` (glyph), `GameTheme` (accent), `MainActivity` (route and `rememberActiveModule`), `GameTips` (3 tips), `GameCoach` (coach card, 5 steps + fact). The string keys for tips and coach already exist (`tip_property_tycoon_1..3`, `coach_property_tycoon_s1..s5`, `coach_property_tycoon_fact`).
- No new Gradle dependency, no assets, no Rust.
- Screen (`ui/PropertyTycoonScreen.kt`) is NOT in this scaffold (only the listed packages may be created); phase P1 adds it.

## 1.9 Open questions for the owner (each with a recommended default)

| # | Question | Recommended default |
|---|---|---|
| Q1 | Ship audience ADULTS only, or tag FAMILY 10+ since there is no gambling? | ADULTS now (as instructed); revisit when a Junior variant exists. |
| Q2 | Show a legal read of names/art before store listing? | Yes, one human read; budget half a day. |
| Q3 | Currency word: "pearls". Keep or change? | Keep; it is thematic and neutral. |
| Q4 | MVP without pledging means harsher games. Ship MVP that way, or pull pledging into MVP? | Ship MVP without; pledging is M effort and lands in v1 (P3). |
| Q5 | Standard mode ends by Rising Tide (rents +5 percent per round after round 80, capped +150 percent) and hard cap round 200. Accept? | Yes; tune from the simulation. |
| Q6 | Quick = doubled rents, same board (cheap), or a shorter 24-tile board? | Doubled rents; short board later. |
| Q7 | Should a human be allowed to propose trades to bots at all in v1? | Yes (Medium/Hard accept clearly favourable ones; Easy always declines with a toast). |
| Q8 | Free Anchorage jackpot default? | Off (it inflates cash and lengthens games); toggle available. |
| Q9 | Do bots ever refuse to bankrupt a human (rubber banding)? | No. No hidden catch-up; the handicap option is the explicit tool. |
| Q10 | Pass-and-play privacy: all information is public, so no hand-over screen between turns. Agree? | Agree: only the READY_SCREEN at start. |
| Q11 | Accent: emerald (light #0A6B4B, dark #4FD6A0) and island glyph. Approve, after a contrast check? | Approve; contrast unverified, coordinator to run the check. |
| Q12 | Sound/haptic palette (coin clink on rent, wood knock on build, low horn on bankruptcy)? | Yes, via existing audio core; assets are a P5 task. |

---

# 2. SCRIPT

## 2.1 Rules, numbered

**Setup**
1. 2 to 6 seats, any mix of people and computers. Seats are numbered 1..6 in order; token shape and colour are both fixed per seat.
2. Each seat starts with the game length's pearls (Quick 1,200, Standard 1,600, Marathon 2,500) unless the Hub sets 1,000, 1,600 or 2,500 explicitly. The handicap seat gets +25, +50 or +100 percent of that amount on top.
3. All tokens start on tile 0 (Lantern Quay). The first player is drawn from the seed unless the Hub's "who moves first" says otherwise. Both decks are shuffled from the seed.
4. Supply: 36 cabins and 14 resorts for everyone.

**A turn**
5. Roll two six-sided dice. The token moves clockwise by the total.
6. Doubles: after resolving the tile, the player rolls again. The third doubles in a row sends the player straight to the Sandbar (no move, no stipend). Leaving the Sandbar by doubles moves but does not grant the extra roll.
7. Passing or landing on tile 0 forward pays the stipend of 160 pearls (house rule "double stipend" pays 320 when landing exactly). Moving backward (card) never pays.
8. Before rolling and after resolving the roll, the player may build, sell buildings, pledge or redeem (v1), and propose trades (v1). Ending the turn passes the dice to the next active seat.

**Tiles**
9. Unowned property, ferry or mill: the player may buy it at its price. If the player declines (or cannot or will not pay), the tile is auctioned to every active seat including the decliner (v1; in MVP it simply stays unowned).
10. Owned by another player, not pledged: pay rent (rule 11 to 14) to the owner immediately. Rent is also due while the owner is in the Sandbar unless "rent-free Sandbar" is on. Owner pledged: no rent. Own tile: nothing.
11. Property rent: base rent by buildings (table). If the owner holds every tile of the colour and none has buildings, rent is base x 2. Rent is then multiplied by the mode's rent percent (Quick 200, Standard 100, Marathon 75) and the Rising Tide factor (rule 31).
12. Ferry rent: 20, 45, 90, 180 for 1, 2, 3, 4 ferries owned by that owner (pledged ferries do not count).
13. Mill rent: 5 times the dice total if the owner has one mill, 12 times with both. A card-moved visitor rolls fresh dice and pays 10 times that roll.
14. Rent is rounded to a whole pearl after all multipliers, minimum 1.
15. Tax tiles: Harbour Dues (tile 4) 120, Lighthouse Levy (tile 28) 180, paid to the bank (or added to the jackpot when that rule is on).
16. Tide Cards (tiles 3, 19, 34) and Guild Cards (tiles 8, 17, 32): draw the top card of that deck, read it aloud (the card sheet), apply the effect, then place it on the discard pile (a free-pass card is held instead). When the draw pile is empty, shuffle the discard from the seed's stream and continue.
17. The Sandbar (tile 10) is a visit tile when merely landed on. Free Anchorage (tile 20) does nothing, unless the jackpot house rule is on: dues, taxes and Sandbar fines accumulate and the next player to land there takes them all. Riptide (tile 30) sends the player to the Sandbar (no stipend).

**Timeout (the Sandbar)**
18. A player enters the Sandbar by Riptide, a go-to-timeout card, or a third doubles. The turn ends immediately (except that nothing is rolled again).
19. At the start of each of their turns in the Sandbar the player chooses: roll for doubles; pay 100 pearls and then roll and move normally (no extra roll for doubles that turn); or use a free-pass card and then roll and move.
20. Rolling doubles frees the player at once; the token moves the doubles total, no extra roll. A failed roll counts one timeout turn. After the third failed turn the player must pay 100 pearls, then moves that roll's total. If they cannot pay, debt flow applies (rule 27).
21. While in the Sandbar a player may still collect rent, build, trade and pledge.

**Buildings**
22. Building needs: the player owns every tile of the colour, none of them is pledged, enough cash, and supply. A cabin costs the colour's build cost. Even-build: after the build, no tile in the colour may differ from another by more than one level (cabins 1 to 4, resort = 5).
23. A resort replaces four cabins on a tile, costs one more build cost, and requires every tile of the colour to be at 4 cabins or already a resort. Four cabins return to supply.
24. Selling: a building sells for half its cost, evenly (sell from the tallest tile first). A resort sells back as four cabins (if supply has them) for half a resort's cost, or directly for cash if supply is short.
25. When supply of cabins runs out, nobody can build more cabins; this is a deliberate strategic squeeze. No auctions of supply.

**Pledging (v1)**
26. A player may pledge an unbuilt tile (no buildings anywhere in its colour) for half its price. A pledged tile collects no rent and counts for nothing in a set. To redeem, pay the pledge value plus 10 percent (rounded up). Pledged ferries and mills likewise.

**Debt and bankruptcy**
27. A payment the payer cannot cover opens a DEBT interrupt: the debtor may sell buildings, pledge (v1), trade (v1) until cash covers the amount, at which point the debt settles automatically; the debtor may also declare bankruptcy.
28. If the debtor's liquidation value (cash + buildings sold at half + unpledged tiles pledged at half) is less than the debt, the only legal command is declare bankruptcy.
29. Bankruptcy to a player: all their tiles pass to the creditor (pledged tiles stay pledged; the creditor pays the 10 percent interest at once or redeems later at their choice; if they cannot afford it, that tile goes to auction); buildings are sold to supply at half value with the cash going to the creditor; remaining cash and free-pass cards go to the creditor. Bankruptcy to the bank (tax, card, fine): tiles return unowned with buildings returned to supply and are auctioned one by one in index order (v1; MVP leaves them unowned).
30. The bankrupt player leaves the turn order permanently and keeps a final place in the score card.

**Ending**
31. Rising Tide: in Standard, from round 81, and in Marathon from round 151, every full round adds 5 percent to all rents, capped at +150 percent total.
32. Winner: the last non-bankrupt player (Standard, Marathon). Quick, or any mode that hits its round cap (Quick 20, Standard 200, Marathon 400), ends after the last seat of that round and the player with the highest net worth wins (ties: more cash, then more tiles, then lower seat). Net worth = cash + each tile at price (pledged at half price) + each building at its cost (resort = 5 build costs).

## 2.2 Board spec (human table)

| # | Tile | Type | Group | Price | Build | Rent: base / 1 / 2 / 3 / 4 cabins / resort | Pledge |
|---|---|---|---|---|---|---|---|
| 0 | Lantern Quay | START | - | - | - | - | - |
| 1 | Driftwood Landing | PROPERTY | CLAY | 60 | 40 | 4 / 20 / 55 / 150 / 220 / 290 | 30 |
| 2 | Barnacle Steps | PROPERTY | CLAY | 70 | 40 | 5 / 25 / 70 / 190 / 275 / 360 | 35 |
| 3 | Tide Cards | TIDE_CARD | - | - | - | - | - |
| 4 | Harbour Dues | TAX | - | - | - | pay 120 | - |
| 5 | Fogbell Lane | PROPERTY | MIST | 90 | 50 | 6 / 30 / 85 / 230 / 330 / 430 | 45 |
| 6 | Northreach Ferry | TRANSIT | - | 180 | - | 20 / 45 / 90 / 180 by ferries owned | 90 |
| 7 | Gull Perch | PROPERTY | MIST | 100 | 50 | 7 / 35 / 100 / 265 / 385 / 505 | 50 |
| 8 | Guild Cards | GUILD_CARD | - | - | - | - | - |
| 9 | Mossgate | PROPERTY | REED | 130 | 80 | 9 / 45 / 125 / 340 / 495 / 650 | 65 |
| 10 | The Sandbar | TIMEOUT | - | - | - | - | - |
| 11 | Fennel Dock | PROPERTY | REED | 140 | 80 | 10 / 50 / 140 / 380 / 550 / 720 | 70 |
| 12 | Tidemill | UTILITY | - | 150 | - | 5x dice (one owned), 12x dice (both) | 75 |
| 13 | Glasswater Cove | PROPERTY | LAGOON | 160 | 100 | 11 / 55 / 155 / 420 / 605 / 790 | 80 |
| 14 | Minnow Bay | PROPERTY | LAGOON | 170 | 100 | 12 / 60 / 170 / 455 / 660 / 865 | 85 |
| 15 | Skylark Inlet | PROPERTY | LAGOON | 190 | 100 | 14 / 70 / 195 / 530 / 770 / 1010 | 95 |
| 16 | Eastwind Skiff | TRANSIT | - | 180 | - | 20 / 45 / 90 / 180 by ferries owned | 90 |
| 17 | Guild Cards | GUILD_CARD | - | - | - | - | - |
| 18 | Plumtree Terrace | PROPERTY | PLUM | 210 | 120 | 15 / 75 / 210 / 570 / 825 / 1080 | 105 |
| 19 | Tide Cards | TIDE_CARD | - | - | - | - | - |
| 20 | Free Anchorage | FREE_ANCHORAGE | - | - | - | - | - |
| 21 | Damson Court | PROPERTY | PLUM | 220 | 120 | 16 / 80 / 225 / 610 / 880 / 1150 | 110 |
| 22 | Sloe Gardens | PROPERTY | PLUM | 240 | 120 | 17 / 85 / 240 / 645 / 935 / 1225 | 120 |
| 23 | Kelp Pumphouse | UTILITY | - | 150 | - | 5x dice (one owned), 12x dice (both) | 75 |
| 24 | Anemone Walk | PROPERTY | CORAL | 250 | 150 | 18 / 90 / 250 / 685 / 990 / 1295 | 125 |
| 25 | Brightfin Market | PROPERTY | CORAL | 270 | 150 | 19 / 95 / 265 / 720 / 1045 / 1370 | 135 |
| 26 | Southgate Packet | TRANSIT | - | 180 | - | 20 / 45 / 90 / 180 by ferries owned | 90 |
| 27 | Sunreef Promenade | PROPERTY | CORAL | 290 | 150 | 21 / 105 / 295 / 800 / 1155 / 1510 | 145 |
| 28 | Lighthouse Levy | TAX | - | - | - | pay 180 | - |
| 29 | Beacon Hill | PROPERTY | GOLD | 310 | 180 | 22 / 110 / 310 / 835 / 1210 / 1585 | 155 |
| 30 | Riptide | GO_TO_TIMEOUT | - | - | - | - | - |
| 31 | Sundial Square | PROPERTY | GOLD | 330 | 180 | 24 / 120 / 335 / 910 / 1320 / 1730 | 165 |
| 32 | Guild Cards | GUILD_CARD | - | - | - | - | - |
| 33 | Goldfinch Rise | PROPERTY | GOLD | 360 | 180 | 26 / 130 / 365 / 990 / 1430 / 1870 | 180 |
| 34 | Tide Cards | TIDE_CARD | - | - | - | - | - |
| 35 | Moonglass Pier | PROPERTY | OPAL | 380 | 220 | 27 / 135 / 380 / 1025 / 1485 / 1945 | 190 |
| 36 | Westmark Cutter | TRANSIT | - | 180 | - | 20 / 45 / 90 / 180 by ferries owned | 90 |
| 37 | Nacre Gardens | PROPERTY | OPAL | 400 | 220 | 29 / 145 / 405 / 1100 / 1595 / 2090 | 200 |
| 38 | Starshell Court | PROPERTY | OPAL | 420 | 220 | 30 / 150 / 420 / 1140 / 1650 / 2160 | 210 |
| 39 | Crown of Opals | PROPERTY | OPAL | 460 | 220 | 33 / 165 / 460 / 1255 / 1815 / 2375 | 230 |

Colour groups: CLAY (triangle, 2 tiles), MIST (circle, 2), REED (diamond, 2), LAGOON (square, 3), PLUM (star, 3), CORAL (cross, 3), GOLD (hexagon, 3), OPAL (drop, 4) = 22 properties. Ferries: 6, 16, 26, 36. Mills: 12, 23. Taxes: 4, 28. Card tiles: Tide 3, 19, 34; Guild 8, 17, 32. Corners: 0 start, 10 timeout, 20 free anchorage, 30 go to timeout. Total 40.

Rent rule of thumb used to generate the ladder: base = round(price / 14); multiples 1, 5, 14, 38, 55, 72 rounded to 5 (v0 values, tuned by the simulation in SCALE).

## 2.3 Board spec (machine-readable)

```json
[
  {"i":0,"type":"START","nameKey":"pt_tile_00"},
  {"i":1,"type":"PROPERTY","nameKey":"pt_tile_01","group":"CLAY","price":60,"build":40,"rents":[4,20,55,150,220,290]},
  {"i":2,"type":"PROPERTY","nameKey":"pt_tile_02","group":"CLAY","price":70,"build":40,"rents":[5,25,70,190,275,360]},
  {"i":3,"type":"TIDE_CARD","nameKey":"pt_tile_03"},
  {"i":4,"type":"TAX","nameKey":"pt_tile_04","amount":120},
  {"i":5,"type":"PROPERTY","nameKey":"pt_tile_05","group":"MIST","price":90,"build":50,"rents":[6,30,85,230,330,430]},
  {"i":6,"type":"TRANSIT","nameKey":"pt_tile_06","price":180},
  {"i":7,"type":"PROPERTY","nameKey":"pt_tile_07","group":"MIST","price":100,"build":50,"rents":[7,35,100,265,385,505]},
  {"i":8,"type":"GUILD_CARD","nameKey":"pt_tile_08"},
  {"i":9,"type":"PROPERTY","nameKey":"pt_tile_09","group":"REED","price":130,"build":80,"rents":[9,45,125,340,495,650]},
  {"i":10,"type":"TIMEOUT","nameKey":"pt_tile_10"},
  {"i":11,"type":"PROPERTY","nameKey":"pt_tile_11","group":"REED","price":140,"build":80,"rents":[10,50,140,380,550,720]},
  {"i":12,"type":"UTILITY","nameKey":"pt_tile_12","price":150},
  {"i":13,"type":"PROPERTY","nameKey":"pt_tile_13","group":"LAGOON","price":160,"build":100,"rents":[11,55,155,420,605,790]},
  {"i":14,"type":"PROPERTY","nameKey":"pt_tile_14","group":"LAGOON","price":170,"build":100,"rents":[12,60,170,455,660,865]},
  {"i":15,"type":"PROPERTY","nameKey":"pt_tile_15","group":"LAGOON","price":190,"build":100,"rents":[14,70,195,530,770,1010]},
  {"i":16,"type":"TRANSIT","nameKey":"pt_tile_16","price":180},
  {"i":17,"type":"GUILD_CARD","nameKey":"pt_tile_17"},
  {"i":18,"type":"PROPERTY","nameKey":"pt_tile_18","group":"PLUM","price":210,"build":120,"rents":[15,75,210,570,825,1080]},
  {"i":19,"type":"TIDE_CARD","nameKey":"pt_tile_19"},
  {"i":20,"type":"FREE_ANCHORAGE","nameKey":"pt_tile_20"},
  {"i":21,"type":"PROPERTY","nameKey":"pt_tile_21","group":"PLUM","price":220,"build":120,"rents":[16,80,225,610,880,1150]},
  {"i":22,"type":"PROPERTY","nameKey":"pt_tile_22","group":"PLUM","price":240,"build":120,"rents":[17,85,240,645,935,1225]},
  {"i":23,"type":"UTILITY","nameKey":"pt_tile_23","price":150},
  {"i":24,"type":"PROPERTY","nameKey":"pt_tile_24","group":"CORAL","price":250,"build":150,"rents":[18,90,250,685,990,1295]},
  {"i":25,"type":"PROPERTY","nameKey":"pt_tile_25","group":"CORAL","price":270,"build":150,"rents":[19,95,265,720,1045,1370]},
  {"i":26,"type":"TRANSIT","nameKey":"pt_tile_26","price":180},
  {"i":27,"type":"PROPERTY","nameKey":"pt_tile_27","group":"CORAL","price":290,"build":150,"rents":[21,105,295,800,1155,1510]},
  {"i":28,"type":"TAX","nameKey":"pt_tile_28","amount":180},
  {"i":29,"type":"PROPERTY","nameKey":"pt_tile_29","group":"GOLD","price":310,"build":180,"rents":[22,110,310,835,1210,1585]},
  {"i":30,"type":"GO_TO_TIMEOUT","nameKey":"pt_tile_30"},
  {"i":31,"type":"PROPERTY","nameKey":"pt_tile_31","group":"GOLD","price":330,"build":180,"rents":[24,120,335,910,1320,1730]},
  {"i":32,"type":"GUILD_CARD","nameKey":"pt_tile_32"},
  {"i":33,"type":"PROPERTY","nameKey":"pt_tile_33","group":"GOLD","price":360,"build":180,"rents":[26,130,365,990,1430,1870]},
  {"i":34,"type":"TIDE_CARD","nameKey":"pt_tile_34"},
  {"i":35,"type":"PROPERTY","nameKey":"pt_tile_35","group":"OPAL","price":380,"build":220,"rents":[27,135,380,1025,1485,1945]},
  {"i":36,"type":"TRANSIT","nameKey":"pt_tile_36","price":180},
  {"i":37,"type":"PROPERTY","nameKey":"pt_tile_37","group":"OPAL","price":400,"build":220,"rents":[29,145,405,1100,1595,2090]},
  {"i":38,"type":"PROPERTY","nameKey":"pt_tile_38","group":"OPAL","price":420,"build":220,"rents":[30,150,420,1140,1650,2160]},
  {"i":39,"type":"PROPERTY","nameKey":"pt_tile_39","group":"OPAL","price":460,"build":220,"rents":[33,165,460,1255,1815,2375]}
]
```
Constants: `{"tiles":40,"stipend":160,"fine":100,"maxTimeoutTurns":3,"maxDoubles":3,"cabinSupply":36,"resortSupply":14,"minBidStep":10,"pledgeInterestPercent":10,"transitRents":[20,45,90,180],"utilityMultipliers":[5,12],"transitPrice":180,"utilityPrice":150}`. The same data lives in `PtBoard` (generated from one script) and `PtRules`.

## 2.4 Card tables (data; text keys `pt_card_tide_NN` and `pt_card_guild_NN`)

Tide deck (movement-heavy):

| Id | Effect | Text (EN) |
|---|---|---|
| t01 | `MoveTo(0, true)` | A fair wind fills your sails. Sail to Lantern Quay and collect the stipend. |
| t02 | `MoveToNearest(TileType.TRANSIT, 2, 0)` | The ferry bell rings. Ride to the nearest ferry. If it is owned, pay its owner double the fare. |
| t03 | `MoveToNearest(TileType.UTILITY, 1, 10)` | The wheels are turning. Advance to the nearest mill or pumphouse. If it is owned, roll and pay ten times the dice. |
| t04 | `GoToTimeout` | A rip current drags you to the Sandbar. Go there now without collecting the stipend. |
| t05 | `GetOutFree` | A calm morning on the water. Keep this card to leave the Sandbar for free. |
| t06 | `MoveBy(-3)` | The tide turns. Drift back three tiles and follow the new tile. |
| t07 | `MoveTo(39, true)` | Chart the opal coast. Advance to Crown of Opals. If you pass the Quay, collect the stipend. |
| t08 | `Gain(100)` | A fine fishing season. Collect 100 pearls. |
| t09 | `Pay(60)` | Your lantern-oil bill is due. Pay 60 pearls. |
| t10 | `Repairs(35, 110)` | Storm damage. Pay 35 pearls per cabin and 110 per resort you own. |
| t11 | `PayEachRival(25)` | A festival on the water. Pay each rival 25 pearls. |
| t12 | `CollectFromEachRival(20)` | You win the regatta. Collect 20 pearls from each rival. |
| t13 | `MoveTo(5, true)` | A fog lifts. Advance to Fogbell Lane. If you pass the Quay, collect the stipend. |
| t14 | `Gain(150)` | Treasure washes ashore. Collect 150 pearls. |
| t15 | `MoveTo(31, true)` | A gust carries you to Sundial Square. If you pass the Quay, collect the stipend. |
| t16 | `MoveBy(2)` | A sudden gust shoves you forward two tiles. |

Guild deck (money-heavy):

| Id | Effect | Text (EN) |
|---|---|---|
| g01 | `Gain(120)` | Guild dividend day. Collect 120 pearls. |
| g02 | `Gain(45)` | The harbour master refunds a fee. Collect 45 pearls. |
| g03 | `CollectFromEachRival(15)` | A wedding on the shore. Collect 15 pearls from each rival as gifts. |
| g04 | `Pay(70)` | Pay the guild physician 70 pearls. |
| g05 | `Repairs(30, 95)` | Roof repairs on your holdings. Pay 30 pearls per cabin and 95 per resort. |
| g06 | `GetOutFree` | Keep this card. It frees you from the Sandbar once. |
| g07 | `GoToTimeout` | A summons from the harbour court. Go to the Sandbar without collecting the stipend. |
| g08 | `Gain(60)` | You sell old fishing nets. Collect 60 pearls. |
| g09 | `Pay(25)` | Library fine for overdue charts. Pay 25 pearls. |
| g10 | `Gain(180)` | A grant for builders arrives. Collect 180 pearls. |
| g11 | `Pay(140)` | Fees for the lighthouse school. Pay 140 pearls. |
| g12 | `MoveTo(0, true)` | Return to Lantern Quay and collect the stipend. |
| g13 | `Gain(100)` | A distant aunt leaves you a legacy. Collect 100 pearls. |
| g14 | `PayEachRival(20)` | You host the community feast. Pay each rival 20 pearls. |
| g15 | `Gain(30)` | You win a ropemaking contest. Collect 30 pearls. |
| g16 | `Gain(80)` | A tax rebate is posted. Collect 80 pearls. |

Effect semantics: `MoveTo(tile, collect)` moves forward (wrapping) and pays the stipend if it passes or lands on tile 0; the new tile is then resolved (rent due, purchase offered, and so on). `MoveBy(n)` moves n tiles (negative = back, no stipend) and resolves the tile. `MoveToNearest(type, mult, dice)` moves forward to the nearest tile of that type; ferry owned: rent x mult; mill owned: roll and pay dice x total; unowned: purchase offered at normal price. `Repairs(c, r)` pays c per cabin and r per resort you own. A free-pass card is returned to the bottom of its deck when used. A hand estimate of expected net cash per draw at 4 seats is roughly +20 pearls for Guild and +10 for Tide before repairs (slightly generous; the jackpot house rule drains some back); the simulation reports the true figure and the card amounts are retuned from it.

## 2.5 Turn state machine

The engine resolves everything between two waits atomically and returns `PtEvent`s for animation. A "phase" is where the table WAITS.

| Phase | Who acts | Legal commands | Next |
|---|---|---|---|
| AWAIT_ROLL | seat on turn | Roll; Build, SellBuilding, Pledge, Redeem, ProposeTrade (post-MVP items gated) | after Roll: resolves tile, then one of AWAIT_BUY, AWAIT_CARD_ACK, AWAIT_DEBT, AWAIT_POST_ROLL, AWAIT_ROLL (doubles) |
| AWAIT_TIMEOUT_CHOICE | seat on turn in Sandbar | Roll, PayFine, UseFreePassCard (+ management commands) | AWAIT_POST_ROLL, or AWAIT_ROLL if freed by paying/card (then rolls) |
| AWAIT_BUY | seat on turn | Buy, DeclineBuy | Buy: AWAIT_POST_ROLL (or AWAIT_ROLL on doubles). DeclineBuy: AWAIT_AUCTION (v1) or AWAIT_POST_ROLL |
| AWAIT_CARD_ACK | seat on turn | AckCard (bots auto-ack) | effect applied then as above; a move effect may chain into AWAIT_BUY / AWAIT_DEBT |
| AWAIT_POST_ROLL | seat on turn | EndTurn, Build, SellBuilding, Pledge, Redeem, ProposeTrade | EndTurn: next active seat's AWAIT_ROLL (or AWAIT_TIMEOUT_CHOICE) |
| AWAIT_DEBT (interrupt) | debtor | SellBuilding, Pledge, ProposeTrade, DeclareBankruptcy; auto-settles when cash >= amount | pops the phase it interrupted; or GAME_OVER |
| AWAIT_AUCTION (interrupt) | `auction.actor` | Bid(amount), PassAuction | next bidder, then pops |
| AWAIT_TRADE_REPLY (interrupt) | trade recipient | RespondTrade(accept), CounterTrade, (proposer: CancelTrade) | pops |
| GAME_OVER | none | none | none |

Interrupt rules: an interrupt pushes the interrupted phase on `phaseStack` and pops it when done; at most one debt, one auction and one open trade at a time; a bankruptcy to the bank queues its tiles in `auctionQueue` and each opens its own auction in turn. A trade offer may only be proposed in AWAIT_ROLL or AWAIT_POST_ROLL by the seat on turn (v1 default), so proposals never interleave with each other. Debt always wins over a pending trade (the trade is cancelled).

## 2.6 Auctions (v1)
Eligible: every active seat (the decliner too). Opening bid minimum 10; each bid at least 10 above the high bid; bids rotate clockwise starting after the decliner; a seat may bid only up to its cash; passing is final for that auction; the auction ends when one seat remains (it pays its high bid) or everyone passed with no bid (tile stays unowned and can be bought again by the next lander). Data: `AuctionState(tile, eligible, passed, highBid, highSeat, actor)`.

## 2.7 Pledging (v1)
See rule 26. Commands `Pledge(tile)` and `Redeem(tile)`. Refused with `HAS_BUILDINGS` while any tile of the colour has buildings, `PLEDGED` when already pledged.

## 2.8 Trades (v1) and the data model
- `Trade(id, fromSeat, toSeat, give: TradeSide, get: TradeSide, status, parentId, createdRound)`; `TradeSide(cash, tiles, freePassCards)`.
- Valid when: both seats active; not both sides empty; cash and free-pass cards held; each tile owned by the stated side and no tile of its colour has any building; the recipient of a pledged tile pays 10 percent interest at once (and may redeem later); no tile is traded to a seat in debt.
- Flow: the seat on turn sends `ProposeTrade` (status PROPOSED, phase AWAIT_TRADE_REPLY). The recipient may `RespondTrade(accept=true)` (ACCEPTED, atomic swap), `RespondTrade(false)` (DECLINED), or `CounterTrade` (parent COUNTERED, a new trade with roles swapped and `parentId` set; depth limit 3, after which only accept or decline). The proposer may `CancelTrade` while pending (CANCELLED). A trade expires (EXPIRED) when the proposer's turn ends or a debt interrupts.
- Swaps are atomic: either every asset moves or none; the engine re-validates at acceptance time.
- Network later: a trade is three messages (propose, respond, counter), host validates and broadcasts the settled state.

## 2.9 Bot behaviour scripts

**Valuation heuristics (MEDIUM and HARD; EASY uses plain price).**
- `value(tile) = price x (1 + setBonus + blockBonus) x heat`, with `heat = 1.0` in v0 (v1 replaces it with the simulated landing-frequency table).
- setBonus for a property: +0.15 for each other tile of the colour the bot already owns; +0.60 if buying completes the colour. HARD adds a further +0.20 per owned tile in a colour whose remaining tiles are all still unowned.
- blockBonus: +0.50 if a single rival owns every other tile of that colour (buying denies the set); MEDIUM counts it at half.
- Ferries: price x (1 + 0.25 x ferries already owned). Mills: price x 0.8, x 1.1 if the bot owns the other mill.
- Cash reserve R: EASY 50; MEDIUM 150 + 10 percent of the largest rent among rival-owned tiles in the next 12 tiles; HARD 0.6 x the largest rent exposure in the next 12 tiles (computed from actual rents given buildings), minimum 100.

**EASY**: buy any tile it can afford while keeping 50 pearls; bid up to 70 percent of price; builds with probability 30 percent per turn when it can, ignoring return; in the Sandbar pays the fine after one failed roll; in debt sells the most expensive building first, then declares bankruptcy; NEVER proposes a trade and ALWAYS declines incoming trades (shows the decline in the log). Adds a small seeded randomness so it is beatable and human-feeling.

**MEDIUM**: buys when `value >= price` and cash after purchase >= R, or when the purchase completes a colour (ignores R down to 0); bids up to `value`; builds on the tile with the best `(rent[k+1] - rent[k]) / cost` while keeping cash >= R after building, evenly, preferring to reach 3 cabins on a colour before 4 or a resort; in the Sandbar: pays the fine immediately while unowned tiles remain (>= 6), otherwise stays and rolls for doubles; in debt liquidates by smallest value lost per pearl raised (cabins on low-return colours first, then pledge the lowest-value tiles); never proposes; accepts a trade only if `tradeScore >= max(60, 20 percent of what it gives)` and the trade does not complete a colour for a rival unless the score is also >= 2x that rival's estimated gain.

**HARD**: all of MEDIUM, plus set-completion and blocking valuation as above; reserve R from real exposure; stays in the Sandbar when rivals have built colours it must pass and it has none (to avoid rent), pays the fine when it has built colours to collect on; auction bidding includes denial (will overbid to value x 1.25 to stop a rival completing a colour); builds more aggressively once a rival is also at 3 cabins; trade policy below.

**Trade policy.**
- EASY: never proposes, always declines.
- MEDIUM: never proposes. Accepts "clearly favourable" trades only: `tradeScore >= max(60, 20 percent of value given)` where `tradeScore = Σ value(received) - Σ value(given) + cash received - cash given`, never when it hands a rival a whole colour unless compensated at least 2x the rival's gain; counters never.
- HARD: proposes set-completing swaps. Once per own turn, in AWAIT_POST_ROLL, for each rival (in order of fewest completed colours): find a tile T that the rival owns which completes a colour for the bot; find an offered tile U (or cash) such that the swap gives the rival `tradeScore_for_rival >= +10 percent` of their loss, does not complete a colour for the rival (unless U's value is >= 2x T's), and keeps the bot's score >= +10 percent; send at most one proposal per rival per round, with a cooldown of 3 rounds after a decline; accepts incoming trades when its own `tradeScore >= 0` and the rival-completion rule above holds; counters once per incoming trade (adds or removes cash in 10-pearl steps to push its score to +10 percent) before declining.
- All levels: the bot never trades tiles that carry buildings in their colour (illegal anyway), never trades its last free-pass card to a leader, and never proposes in a debt interrupt.

## 2.10 House-rule toggles

| Key | Default | Meaning | Release |
|---|---|---|---|
| `auctions` | on | declined tiles are auctioned | v1 |
| `trades` | on | trading allowed | v1 |
| `jackpot` | off | taxes, dues and fines pile up at Free Anchorage | MVP |
| `doubleStipend` | off | landing exactly on tile 0 pays 320 | MVP |
| `rentFreeSandbar` | off | no rent from a player in the Sandbar | MVP |

Pledging is always on from v1 (not a toggle). Even-build is always on.

## 2.11 Handicap rule
One seat gets +25, +50 or +100 percent of the starting pearls (Hub options `handicap` = none or seat 1..6, `handicapBoost`). The start card names the seat and the amount (`pt_start_handicap`). Intended for a beginner or a child-with-adult mixed table; the handicap is shown on the score card so a result is never presented as an equal contest.

## 2.12 Tutorial script (EN, all lines under 120 characters)

| # | Line | Chars |
|---|---|---|
| 1 | Welcome to the archipelago. Buy tiles, collect rent, and outlast your rivals. | 77 |
| 2 | Roll two dice and your token sails that many tiles around the ring. | 67 |
| 3 | Land on an unowned tile and you may buy it. Pearls are play money only. | 71 |
| 4 | Land on a rival tile and you pay rent. Own a whole colour and its rent doubles. | 79 |
| 5 | With a whole colour you can build cabins. Build evenly across the colour. | 73 |
| 6 | Four cabins can become a resort, the biggest rent of all. | 57 |
| 7 | Doubles let you roll again. Three doubles in a row sweep you to the Sandbar. | 76 |
| 8 | In the Sandbar, roll doubles, pay the fine or use a free-pass card to leave. | 76 |
| 9 | Tide and Guild cards bring surprises: gifts, bills and sudden moves. | 68 |
| 10 | Short of pearls? Sell buildings or pledge tiles. Pledged tiles collect no rent. | 79 |
| 11 | If you cannot pay your debt, you go bankrupt and your tiles pass to who you owe. | 80 |
| 12 | The last player standing wins. Quick games end after 20 rounds: richest wins. | 77 |

Delivery: a coach card (5 steps + fact, keys `coach_property_tycoon_*`) the first time the game opens, and this 12-line tutorial as a skippable guided first turn pointing at the dice, the current-tile card, the Buy button, the build controls and the Sandbar. Lines 1-4 are shown in the first turn; 5-12 appear the first time each situation occurs (first build, first doubles, first Sandbar, first card, first debt, first bankruptcy, game end).

## 2.13 Hub text keys (keys come from `hubKey(text)`; all slugs are within 48 characters, so no hash suffix)

| Hub text (EN) | Key |
|---|---|
| Property Tycoon | `hub_property_tycoon` |
| Buy islands, collect rent, outlast your rivals. | `hub_buy_islands_collect_rent_outlast_your_rivals` |
| Build your empire against 1 to 5 computers. | `hub_build_your_empire_against_1_to_5_computers` |
| Game length | `hub_game_length` |
| Quick: 20 rounds | `hub_quick_20_rounds` |
| Doubled rents. The richest player wins. | `hub_doubled_rents_the_richest_player_wins` |
| Standard: last one standing | `hub_standard_last_one_standing` |
| Rents rise late so a game always ends. | `hub_rents_rise_late_so_a_game_always_ends` |
| Marathon: very long | `hub_marathon_very_long` |
| More pearls and gentler rents. | `hub_more_pearls_and_gentler_rents` |
| Starting pearls | `hub_starting_pearls` |
| Play money only. Everyone starts equal. | `hub_play_money_only_everyone_starts_equal` |
| Set by game length | `hub_set_by_game_length` |
| 1,000 pearls | `hub_1_000_pearls` |
| 1,600 pearls | `hub_1_600_pearls` |
| 2,500 pearls | `hub_2_500_pearls` |
| Head start | `hub_head_start` |
| Give one player extra pearls to even things out. | `hub_give_one_player_extra_pearls_to_even_things_out` |
| No head start | `hub_no_head_start` |
| Player 1 gets extra | `hub_player_1_gets_extra` |
| Player 2 gets extra | `hub_player_2_gets_extra` |
| Player 3 gets extra | `hub_player_3_gets_extra` |
| Player 4 gets extra | `hub_player_4_gets_extra` |
| Player 5 gets extra | `hub_player_5_gets_extra` |
| Player 6 gets extra | `hub_player_6_gets_extra` |
| Head start size | `hub_head_start_size` |
| Extra pearls on top of the starting amount. | `hub_extra_pearls_on_top_of_the_starting_amount` |
| Plus 25 percent | `hub_plus_25_percent` |
| Plus 50 percent | `hub_plus_50_percent` |
| Plus 100 percent | `hub_plus_100_percent` |
| Auction unbought tiles | `hub_auction_unbought_tiles` |
| A tile nobody buys goes to the highest bidder. | `hub_a_tile_nobody_buys_goes_to_the_highest_bidder` |
| Free Anchorage jackpot | `hub_free_anchorage_jackpot` |
| Dues and fines pile up for whoever lands there. | `hub_dues_and_fines_pile_up_for_whoever_lands_there` |
| Double stipend on the Quay | `hub_double_stipend_on_the_quay` |
| Landing exactly on Lantern Quay pays twice. | `hub_landing_exactly_on_lantern_quay_pays_twice` |
| Rent-free Sandbar | `hub_rent_free_sandbar` |
| No rent is collected from a player stuck there. | `hub_no_rent_is_collected_from_a_player_stuck_there` |
| Allow trades | `hub_allow_trades` |
| Players may swap tiles and pearls. | `hub_players_may_swap_tiles_and_pearls` |

Reused existing texts (not redefined): vs Computers, Pass & Play, "Friends share this device. Computers can fill empty seats.", People playing, Computer players, Computer level, Settings default, Easy, Medium, Hard, Who moves first, You, Random, The computer, Player 1, Player 2.

## 2.14 Exact EN strings list with keys
Tile names (`pt_tile_00..39`), colour group names (`pt_group_*`), and the 32 card texts (`pt_card_tide_01..16`, `pt_card_guild_01..16`) are in the tables above and in `res/values/property_tycoon_strings.xml`. All other strings (289 resources in total including those; Spanish has the same keys with real Spanish values):

| Key | English |
|---|---|
| `pt_group_clay` | Clay |
| `pt_group_mist` | Mist |
| `pt_group_reed` | Reed |
| `pt_group_lagoon` | Lagoon |
| `pt_group_plum` | Plum |
| `pt_group_coral` | Coral |
| `pt_group_gold` | Gold |
| `pt_group_opal` | Opal |
| `pt_title` | Property Tycoon |
| `pt_setup_title` | Set up your table |
| `pt_setup_players` | Players |
| `pt_setup_length` | Game length |
| `pt_setup_rules` | House rules |
| `pt_mode_quick` | Quick |
| `pt_mode_quick_help` | 20 rounds, doubled rents, richest player wins. |
| `pt_mode_standard` | Standard |
| `pt_mode_standard_help` | Last player standing. Rents rise late so games end. |
| `pt_mode_marathon` | Marathon |
| `pt_mode_marathon_help` | More pearls, gentler rents, a very long game. |
| `pt_btn_start` | Start game |
| `pt_btn_resume` | Resume game |
| `pt_start_ready` | Everyone starts with %1$d pearls. Pearls are play money only. |
| `pt_start_first` | %1$s rolls first. |
| `pt_start_handicap` | %1$s starts with a head start of %2$d pearls. |
| `pt_pass_ready` | Pass the device to %1$s. |
| `pt_cash` | Cash |
| `pt_pearls` | %1$d pearls |
| `pt_round` | Round %1$d |
| `pt_round_of` | Round %1$d of %2$d |
| `pt_turn_of` | Turn: %1$s |
| `pt_btn_roll` | Roll dice |
| `pt_btn_buy` | Buy for %1$d |
| `pt_btn_auction` | Auction it |
| `pt_btn_end_turn` | End turn |
| `pt_btn_build` | Build cabin |
| `pt_btn_build_resort` | Build resort |
| `pt_btn_sell_building` | Sell building |
| `pt_btn_pledge` | Pledge tile |
| `pt_btn_redeem` | Redeem for %1$d |
| `pt_btn_trade` | Trade |
| `pt_btn_pay_fine` | Pay %1$d fine |
| `pt_btn_use_card` | Use free-pass card |
| `pt_btn_bid` | Bid %1$d |
| `pt_btn_pass_bid` | Pass |
| `pt_btn_accept` | Accept |
| `pt_btn_decline` | Decline |
| `pt_btn_counter` | Counter offer |
| `pt_btn_cancel` | Cancel |
| `pt_btn_give_up` | Give up |
| `pt_btn_declare_bankrupt` | Declare bankruptcy |
| `pt_btn_zoom_in` | Zoom in on the board |
| `pt_btn_zoom_out` | Zoom out |
| `pt_btn_fit_board` | Show whole board |
| `pt_btn_card_ok` | Got it |
| `pt_log_rolled` | %1$s rolled %2$d and %3$d. |
| `pt_log_doubles` | Doubles! %1$s rolls again. |
| `pt_log_third_doubles` | Third doubles in a row. %1$s is swept to the Sandbar. |
| `pt_log_landed` | %1$s landed on %2$s. |
| `pt_log_stipend` | %1$s collected %2$d pearls at Lantern Quay. |
| `pt_log_bought` | %1$s bought %2$s for %3$d pearls. |
| `pt_log_rent` | %1$s paid %2$d pearls of rent to %3$s. |
| `pt_log_tax` | %1$s paid %2$d pearls in dues. |
| `pt_log_card` | %1$s drew: %2$s |
| `pt_log_built` | %1$s built on %2$s. |
| `pt_log_sold_building` | %1$s sold a building on %2$s. |
| `pt_log_pledged` | %1$s pledged %2$s for %3$d pearls. |
| `pt_log_redeemed` | %1$s redeemed %2$s. |
| `pt_log_timeout_in` | %1$s is stuck on the Sandbar. |
| `pt_log_timeout_out` | %1$s left the Sandbar. |
| `pt_log_jackpot` | %1$s took the %2$d pearls waiting at Free Anchorage. |
| `pt_log_auction_open` | Auction for %1$s. Bidding opens at %2$d pearls. |
| `pt_log_auction_won` | %1$s won %2$s for %3$d pearls. |
| `pt_log_auction_none` | Nobody bid. %1$s stays unowned. |
| `pt_log_trade_offer` | %1$s offers %2$s a trade. |
| `pt_log_trade_done` | %1$s and %2$s agreed a trade. |
| `pt_log_trade_declined` | %1$s declined the trade. |
| `pt_log_debt` | %1$s owes %2$d pearls and must raise the money. |
| `pt_log_bankrupt` | %1$s is bankrupt and leaves the game. |
| `pt_log_rising_tide` | The tide is rising. Rents grow by %1$d percent. |
| `pt_log_round_cap` | Round limit reached. The richest player wins. |
| `pt_log_winner` | %1$s wins the game! |
| `pt_debt_title` | You owe %1$d pearls |
| `pt_debt_hint` | Sell buildings or pledge tiles to raise the money. You hold %1$d pearls. |
| `pt_debt_creditor` | Owed to %1$s |
| `pt_debt_bank` | Owed to the bank |
| `pt_why_not_your_turn` | It is not your turn. |
| `pt_why_cannot_afford` | You do not have enough pearls. |
| `pt_why_set_incomplete` | You need every tile of this colour first. |
| `pt_why_even_build` | Build evenly: add to your shortest tile of this colour first. |
| `pt_why_even_sell` | Sell evenly: sell from your tallest tile of this colour first. |
| `pt_why_supply_empty` | No buildings are left in the supply. |
| `pt_why_has_buildings` | Sell the buildings on this colour first. |
| `pt_why_pledged` | This tile is pledged. |
| `pt_why_in_debt` | Settle your debt first. |
| `pt_why_trade_invalid` | That trade is not allowed. |
| `pt_why_need_roll` | Roll the dice first. |
| `pt_why_decide_first` | Buy this tile or send it to auction first. |
| `pt_card_unowned` | For sale: %1$d pearls |
| `pt_card_owner` | Owner: %1$s |
| `pt_card_pledged` | Pledged |
| `pt_card_rent` | Rent |
| `pt_card_rent_set` | Whole colour, no buildings |
| `pt_card_rent_cabins` | With %1$d cabins |
| `pt_card_rent_one_cabin` | With 1 cabin |
| `pt_card_rent_resort` | With a resort |
| `pt_card_build_cost` | Each cabin costs %1$d pearls |
| `pt_card_pledge_value` | Pledge value %1$d pearls |
| `pt_card_transit_rent` | Rent by ferries owned: %1$d, %2$d, %3$d, %4$d |
| `pt_card_utility_rent` | Rent: %1$d times the dice, or %2$d times with both |
| `pt_card_tax` | Pay %1$d pearls |
| `pt_card_start` | Collect %1$d pearls each time you pass |
| `pt_card_timeout` | Just visiting, or stuck here |
| `pt_card_free` | A quiet berth. Nothing happens. |
| `pt_card_jackpot` | Waiting here: %1$d pearls |
| `pt_card_go_timeout` | Go straight to the Sandbar |
| `pt_card_draw` | Draw a card |
| `pt_trade_title` | Trade with %1$s |
| `pt_trade_you_give` | You give |
| `pt_trade_you_get` | You get |
| `pt_trade_cash` | Pearls |
| `pt_trade_free_cards` | Free-pass cards |
| `pt_trade_send` | Send offer |
| `pt_trade_incoming` | %1$s offers you a trade |
| `pt_trade_pledge_fee` | Pledged tiles cost the new owner 10 percent at once. |
| `pt_post_title` | Game over |
| `pt_post_winner` | %1$s wins with %2$d pearls of net worth. |
| `pt_post_last_standing` | %1$s is the last player standing. |
| `pt_post_play_again` | Play again |
| `pt_post_back` | Back to Game Hub |
| `pt_post_show_score` | Score card |
| `pt_score_title` | Score card |
| `pt_score_rank` | Place |
| `pt_score_net_worth` | Net worth |
| `pt_score_tiles` | Tiles owned |
| `pt_score_sets` | Whole colours |
| `pt_score_rent_in` | Rent collected |
| `pt_score_rent_out` | Rent paid |
| `pt_score_rounds` | Rounds played |
| `pt_score_bankrupt_round` | Bankrupt in round %1$d |
| `pt_score_seed` | Game code %1$s |
| `pt_a11y_ring` | Game board, a ring of 40 tiles |
| `pt_a11y_tile` | %1$s, tile %2$d, %3$s |
| `pt_a11y_tile_owned` | owned by %1$s |
| `pt_a11y_tile_free` | unowned |
| `pt_a11y_token` | %1$s token on %2$s |
| `pt_a11y_current_tile` | Current tile card |
| `pt_a11y_dice` | Dice show %1$d and %2$d |
| `pt_a11y_group_shape` | %1$s colour, shape %2$s |
| `pt_shape_triangle` | triangle |
| `pt_shape_circle` | circle |
| `pt_shape_diamond` | diamond |
| `pt_shape_square` | square |
| `pt_shape_star` | star |
| `pt_shape_cross` | cross |
| `pt_shape_hexagon` | hexagon |
| `pt_shape_drop` | drop |
| `pt_tut_01` | Welcome to the archipelago. Buy tiles, collect rent, and outlast your rivals. |
| `pt_tut_02` | Roll two dice and your token sails that many tiles around the ring. |
| `pt_tut_03` | Land on an unowned tile and you may buy it. Pearls are play money only. |
| `pt_tut_04` | Land on a rival tile and you pay rent. Own a whole colour and its rent doubles. |
| `pt_tut_05` | With a whole colour you can build cabins. Build evenly across the colour. |
| `pt_tut_06` | Four cabins can become a resort, the biggest rent of all. |
| `pt_tut_07` | Doubles let you roll again. Three doubles in a row sweep you to the Sandbar. |
| `pt_tut_08` | In the Sandbar, roll doubles, pay the fine or use a free-pass card to leave. |
| `pt_tut_09` | Tide and Guild cards bring surprises: gifts, bills and sudden moves. |
| `pt_tut_10` | Short of pearls? Sell buildings or pledge tiles. Pledged tiles collect no rent. |
| `pt_tut_11` | If you cannot pay your debt, you go bankrupt and your tiles pass to who you owe. |
| `pt_tut_12` | The last player standing wins. Quick games end after 20 rounds: richest wins. |
| `coach_property_tycoon_s1` | Roll two dice and move your token around the ring of 40 tiles. |
| `coach_property_tycoon_s2` | Buy the tiles you land on, or send them to auction so a rival pays more. |
| `coach_property_tycoon_s3` | Own every tile of one colour to double its rent and start building cabins. |
| `coach_property_tycoon_s4` | Pledge tiles or sell buildings if you owe more pearls than you hold. |
| `coach_property_tycoon_s5` | Be the last player standing, or the richest when the round limit arrives. |
| `coach_property_tycoon_fact` | Games like this began a century ago as a protest board that taught how rent can pile up in few hands. |
| `tip_property_tycoon_1` | Whole colours matter more than single tiles. Trade toward a set. |
| `tip_property_tycoon_2` | Keep a cash cushion. One resort rent can end your game. |
| `tip_property_tycoon_3` | Three cabins is the sweet spot: the rent jumps most for the price. |
| `hub_property_tycoon` | Property Tycoon |
| `hub_buy_islands_collect_rent_outlast_your_rivals` | Buy islands, collect rent, outlast your rivals. |
| `hub_build_your_empire_against_1_to_5_computers` | Build your empire against 1 to 5 computers. |
| `hub_game_length` | Game length |
| `hub_quick_20_rounds` | Quick: 20 rounds |
| `hub_doubled_rents_the_richest_player_wins` | Doubled rents. The richest player wins. |
| `hub_standard_last_one_standing` | Standard: last one standing |
| `hub_rents_rise_late_so_a_game_always_ends` | Rents rise late so a game always ends. |
| `hub_marathon_very_long` | Marathon: very long |
| `hub_more_pearls_and_gentler_rents` | More pearls and gentler rents. |
| `hub_starting_pearls` | Starting pearls |
| `hub_play_money_only_everyone_starts_equal` | Play money only. Everyone starts equal. |
| `hub_set_by_game_length` | Set by game length |
| `hub_1_000_pearls` | 1,000 pearls |
| `hub_1_600_pearls` | 1,600 pearls |
| `hub_2_500_pearls` | 2,500 pearls |
| `hub_head_start` | Head start |
| `hub_give_one_player_extra_pearls_to_even_things_out` | Give one player extra pearls to even things out. |
| `hub_no_head_start` | No head start |
| `hub_player_1_gets_extra` | Player 1 gets extra |
| `hub_player_2_gets_extra` | Player 2 gets extra |
| `hub_player_3_gets_extra` | Player 3 gets extra |
| `hub_player_4_gets_extra` | Player 4 gets extra |
| `hub_player_5_gets_extra` | Player 5 gets extra |
| `hub_player_6_gets_extra` | Player 6 gets extra |
| `hub_head_start_size` | Head start size |
| `hub_extra_pearls_on_top_of_the_starting_amount` | Extra pearls on top of the starting amount. |
| `hub_plus_25_percent` | Plus 25 percent |
| `hub_plus_50_percent` | Plus 50 percent |
| `hub_plus_100_percent` | Plus 100 percent |
| `hub_auction_unbought_tiles` | Auction unbought tiles |
| `hub_a_tile_nobody_buys_goes_to_the_highest_bidder` | A tile nobody buys goes to the highest bidder. |
| `hub_free_anchorage_jackpot` | Free Anchorage jackpot |
| `hub_dues_and_fines_pile_up_for_whoever_lands_there` | Dues and fines pile up for whoever lands there. |
| `hub_double_stipend_on_the_quay` | Double stipend on the Quay |
| `hub_landing_exactly_on_lantern_quay_pays_twice` | Landing exactly on Lantern Quay pays twice. |
| `hub_rent_free_sandbar` | Rent-free Sandbar |
| `hub_no_rent_is_collected_from_a_player_stuck_there` | No rent is collected from a player stuck there. |
| `hub_allow_trades` | Allow trades |
| `hub_players_may_swap_tiles_and_pearls` | Players may swap tiles and pearls. |

---

# 3. SCALE

## 3.1 Game length by mode

| | Quick | Standard | Marathon |
|---|---|---|---|
| Start pearls | 1,200 | 1,600 | 2,500 |
| Rent percent | 200 | 100 | 75 |
| Ends | after round 20 (20 turns each): richest net worth wins | last player standing | last player standing |
| Rising Tide from round | none | 81 | 151 |
| Hard round cap (net worth wins) | 20 | 200 | 400 |
| Target length | 15 to 25 min | 45 to 90 min | 2 hours or more |

Quick uses doubled rents (not a shorter board) so it needs no second board; a 24-tile ring is a Later item.

## 3.2 Players, CPU fill, seeds
2 to 6 seats. Hub: vs Computers = 1 person + 1 to 5 CPUs; Pass and Play = 2 to 6 people + 0 to 4 CPUs (total capped at 6). A table of one person and five computers is allowed. Every random draw (dice, deck shuffles, first player, bot tie-breaks) comes from one SplitMix64 stream whose state is stored in `TycoonState.rngState`; the seed is `TycoonConfig.seed` (random per game unless a "game code" is entered later; the score card shows `pt_score_seed`). Same seed + same commands = same game; save/restore continues the same stream.

## 3.3 Balance simulation plan
A JVM Monte Carlo harness (`PropertyTycoonSimulationTest`, @Ignore by default, runnable by hand) plays bot vs bot with no UI:
- Configurations: 2 to 6 seats x {Quick, Standard, Marathon} x levels {all Easy, all Medium, all Hard, mixed} x house rules on/off; 10,000 seeded games per cell.
- Measure: average and p95 length in rounds; share of games ending by cap, by escalator, by elimination; winner share by seat (first-mover advantage; target within 4 points of even at equal skill); bankruptcy round distribution (no spike before round 12); first-set-completion round; value of owning each colour group (win rate given ownership, average rent income per round per colour, ROI of each building level) to retune prices and rents; average cash per draw of each deck; pearls in circulation over time (inflation check); Sandbar visits per game; Hard-vs-Medium and Medium-vs-Easy win rates (target Hard beats Medium 60 to 70 percent and Medium beats Easy 70 to 80 percent in 2 seat games); landing-frequency heat table (feeds bot valuation); auction frequency and average premium over price (v1); trade acceptance rates and trade effect on length (v1).
- Targets: Standard average length 60 to 140 rounds, no game beyond the hard cap; Quick has a clear (non-tie) winner in 90 percent of games; no colour group with a win-correlation more than 2x the median (tune ladder otherwise). All v0 prices, rents and card amounts are starting values, expected to change after the first simulation pass.
- Output: a CSV per cell plus one markdown summary checked into `docs/design/boardgames/` with the seed list, so a retune is reproducible.

## 3.4 Performance budget
- Bot think time: engine decisions are synchronous and cheap. EASY and MEDIUM under 5 ms, HARD under 20 ms per decision on a mid-range 2022 phone; the Hard trade search is bounded to rivals x owned tiles squared (about 5 x 22 x 22 evaluations). The UI imposes a natural delay of at least 400 ms per bot action (QUALITY-BAR), scaled by `LocalGameSpeed`, with animated token movement; the engine never blocks the UI thread for more than one command.
- State size: 40 holdings x ~5 numbers, 6 players, two decks of 16 ids, up to 6 trades: under 8 KB as JSON; with the last 50 log events under 16 KB. A running game allocates one new immutable state per command (cheap at these sizes).
- Simulation speed target: 1,000 Standard games in under 60 s on a laptop JVM so 10,000-game cells are practical.
- Render: ring of 40 tiles drawn with one Canvas; tokens animated with a single transition; no per-frame allocation in the board draw.

## 3.5 Persistence and resume of long games
`snapshot()` is called after every completed command and whenever an interrupt opens (cheap, under 16 KB), returns null after the game ends (house pattern); `restore()` validates (list in `PropertyTycoonSnapshotCodec` KDoc) and returns false on any inconsistency so a bad save never crashes or cheats. The save carries the RNG state, decks, supply, jackpot, open auction, debt and trades, session wins and the computer level. A pass-and-play game resumes at the READY_SCREEN for the seat to act. Marathon games are expected to be resumed several times; the Hub's Resume card shows round and net-worth leader.

## 3.6 Multiplayer plan
- Same-screen first (this scaffold). 
- Nearby / Online (Later): host-authoritative, host runs the engine and broadcasts the full `TycoonState` after each accepted command (state is small), guests send `PtCommand`s. There is no hidden information in the game (all cash, tiles, buildings and decks' contents-as-played are public; deck ORDER is hidden but it is not game knowledge, only the host holds it), so no redaction layer is needed beyond not sending `rngState` or the draw piles to guests (guests receive only the top-of-discard). Messages: Command(seat, PtCommand), StateSync(state without rng/draw piles, eventSerial), and trade negotiation as ordinary commands (Propose, Respond, Counter, Cancel) validated by the host. Identity: the host trusts only the transport-verified sender, never a `fromSeat` field in the message (lesson of PR #9 transport identity hardening). Reconnect: guest asks StateSync; idle-turn timer is a general Wave 5 item and is needed here (a stuck human on a long game); a disconnected seat is taken over by a Medium bot until it returns (host option).
- Kids: Nearby/Online are already stripped for KID profiles; this game is ADULTS anyway.

## 3.7 Phased delivery

| Phase | Content | Effort |
|---|---|---|
| P0 Engine core | board, dice, movement, buy, rent, taxes, decks, timeout, building with even rule, debt by selling, bankruptcy to player and bank, winner, net worth, rent percent, Rising Tide, snapshot + validation, unit + fuzz tests | L |
| P1 MVP screen | `PropertyTycoonScreen`: setup, start, mid-game ring + current-tile card + action bar, post-game, score card; five UI states; accessibility labels; Hub registration by the coordinator | L |
| P2 Bots | Easy/Medium/Hard buy/build/timeout/debt policies, valuation, deterministic bot tests, think-time pacing | M |
| P3 v1 rules | auctions, pledging, trading data model and UI, bot trade policy (Medium accepts, Hard proposes) | L |
| P4 Balance | simulation harness, retune prices/rents/cards/escalator, heat table into valuation | M |
| P5 Polish | art (tiles, tokens, dice), sound and haptics, tutorial and coach, tips, achievements hooks, large-font and Fold passes, TalkBack pass | M |
| P6 Network | Nearby then Online, host-authoritative, idle timer, bot takeover | L |
| P7 Junior | FAMILY variant (no bankruptcy), separate hub spec | M |

## 3.8 Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Rule disputes (players expect another game's house rules, e.g. a free-parking payout) | High | Med | Rules sheet in the tutorial, house-rule toggles with help text, defaults chosen and documented, "why" messages for every refusal |
| Games run too long | High | High | Quick mode, Rising Tide, hard round cap, simulation targets, resume support |
| Endless stalemate (nobody can build, no one goes bankrupt) | Med | Med | Rising Tide escalator guarantees a decisive end; round cap picks a net-worth winner |
| AI trading exploits (human fleeces Medium, or bot hands over a set) | Med | Med | Medium never proposes and demands clearly favourable score; the rival-completion rule; Hard counters but with 10 percent margin; simulate trade bots against each other |
| First-mover advantage | Med | Low | Measure by seat; seed-chosen first player; handicap tool; adjust first-lap stipend if skew over 4 points |
| Legal similarity to a famous trademarked game | Low | High | Section 1.4, deny-list test, human legal read before store listing, no similar names or art |
| Dense board unreadable on phones | High | Med | Zoomable ring, current-tile card, bottom sheet, 48 dp targets, large-font test |
| Save corruption in long games | Low | High | Validation of every invariant in restore, snapshot after every command, fuzz round-trip test |
| Bot stalls the table | Low | High | Bots only choose legal commands; test that every phase has a legal command for the seat to act |
| Kid exposure to money mechanics | Low | High | ADULTS audience tag; test that kidMaySee is false for all bands |
| Play-money perception (gambling-like) | Low | Med | No wagering, no cash-out, "play money only" copy, no casino chrome |
| Content inflation (cards print too much money) | Med | Med | Simulation measures average cash per draw and pearls in circulation; retune card amounts |

## 3.9 Test plan
102 test stubs, all @Ignore until their phase lands, one file per class in `app/src/test/java/com/gamesuite/games/propertytycoon/`. Order of activation: Board, Rules, Card, Debt (P0); Snapshot, Game, HubSpecs (P0/P1); Bot (P2); Auction, Trade (P3); Simulation, Fuzz (P4, Fuzz also guards P0). Gate rules: Fuzz test asserts total pearls plus tile ownership conservation after every random legal command; an adversarial pass (anthropic-skills:adversarial-verification) before the wave merges; known-value tests use hand-built states with fixed seeds.

| Test class | Test | Checks |
|---|---|---|
| PropertyTycoonBoardTest | `boardHasFortyTilesWithContiguousIndexes` | indexes 0..39 once each, ring order |
| PropertyTycoonBoardTest | `boardHasTwentyTwoPropertiesInEightGroupsOfTwoTwoTwoThreeThreeThreeThreeFour` | group sizes match the table |
| PropertyTycoonBoardTest | `boardHasFourTransitTwoUtilityTwoTaxSixCardTilesAndFourCorners` | tile type counts |
| PropertyTycoonBoardTest | `everyTileNameAndCardTextKeyExistsInEnglishAndSpanishResources` | no missing keys in either locale |
| PropertyTycoonBoardTest | `rentsGrowWithEveryBuildingLevelAndWholeColourDoublesBaseRent` | rent ladder is strictly increasing |
| PropertyTycoonBoardTest | `priceAndBuildCostRiseFromClayToOpal` | group ordering sanity |
| PropertyTycoonBoardTest | `noTileNameOrCardTextMatchesAnyTrademarkedReferenceList` | legal guard: compares with a deny-list file |
| PropertyTycoonBoardTest | `heatTableSumsToOneAndIsSymmetricAroundTimeoutCorners` | landing-frequency data used by bots |
| PropertyTycoonRulesTest | `diceAreTwoSixSidedDrawnFromTheSeededStreamInOrder` | same seed gives same rolls |
| PropertyTycoonRulesTest | `passingLanternQuayPaysTheStipendOnceAndLandingPaysTwiceOnlyWithTheHouseRule` | salary rules |
| PropertyTycoonRulesTest | `landingOnUnownedPropertyOffersBuyOrAuction` | buy decision phase |
| PropertyTycoonRulesTest | `rentIsBaseTimesTwoForWholeColourWithoutBuildings` | set bonus |
| PropertyTycoonRulesTest | `rentFollowsBuildingLevelAndRentPercentScaling` | mode scaling |
| PropertyTycoonRulesTest | `transitRentFollowsFerriesOwned` | 20/45/90/180 |
| PropertyTycoonRulesTest | `utilityRentIsFiveOrTwelveTimesTheDice` | 5x and 12x |
| PropertyTycoonRulesTest | `taxTilesTakeTheirFlatAmountAndFeedTheJackpotWhenEnabled` | tax + jackpot |
| PropertyTycoonRulesTest | `pledgedTileCollectsNoRent` | mortgage rule |
| PropertyTycoonRulesTest | `threeDoublesInARowSendsThePlayerToTheSandbar` | speeding rule |
| PropertyTycoonRulesTest | `doublesGiveAnExtraRollButNotWhenLeavingTheSandbar` | doubles in timeout |
| PropertyTycoonRulesTest | `sandbarOffersRollFinePayAndFreePassCardAndForcesFineOnThirdTurn` | timeout choices |
| PropertyTycoonRulesTest | `goToTimeoutTileAndCardsSendToSandbarWithoutStipend` | timeout entries |
| PropertyTycoonRulesTest | `evenBuildRuleRejectsUnevenBuildingAndSelling` | even build |
| PropertyTycoonRulesTest | `buildingNeedsWholeColourAndNoPledgedTileInTheColour` | build preconditions |
| PropertyTycoonRulesTest | `resortNeedsFourCabinsOnEveryTileOfTheColour` | resort rule |
| PropertyTycoonRulesTest | `buildingSupplyIsLimitedToThirtySixCabinsAndFourteenResorts` | supply |
| PropertyTycoonRulesTest | `rentPercentEscalatorStartsAfterTheModeRoundAndGrowsEachRound` | rising tide |
| PropertyTycoonRulesTest | `quickModeEndsAfterTwentyRoundsAndRichestNetWorthWins` | round cap |
| PropertyTycoonRulesTest | `netWorthCountsCashTilesAtPriceBuildingsAtCostAndPledgedAtPledgeValue` | definition |
| PropertyTycoonRulesTest | `handicapAddsStartingPearlsToTheChosenSeatOnly` | handicap |
| PropertyTycoonCardTest | `eachDeckHasSixteenUniqueCardsAndEveryCardHasTextInBothLocales` | deck integrity |
| PropertyTycoonCardTest | `decksShuffleDeterministicallyFromTheSeedAndCycleWithoutLosingCards` | cycling |
| PropertyTycoonCardTest | `getOutFreeCardLeavesTheDeckUntilUsedThenReturnsToTheBottom` | held cards |
| PropertyTycoonCardTest | `moveCardsCollectTheStipendOnlyWhenPassingTheQuayForward` | MoveTo semantics |
| PropertyTycoonCardTest | `moveToNearestResolvesFerryDoubleFareAndUtilityTenTimesDice` | nearest effects |
| PropertyTycoonCardTest | `repairCardsChargeByBuildingCountAndRaiseDebtWhenShort` | repairs |
| PropertyTycoonCardTest | `payAndCollectEachRivalCardsSkipBankruptPlayers` | each rival |
| PropertyTycoonAuctionTest | `declinedPropertyStartsAuctionWithEveryActivePlayerEligible` | auction start (v1) |
| PropertyTycoonAuctionTest | `bidsRiseByAtLeastTenAndPassingIsFinalForThatAuction` | bid rules |
| PropertyTycoonAuctionTest | `highestBidderPaysAndReceivesTheTile` | settlement |
| PropertyTycoonAuctionTest | `auctionWithNoBidsLeavesTheTileUnowned` | no bids |
| PropertyTycoonAuctionTest | `bidderCannotBidMoreThanTheirCash` | affordability |
| PropertyTycoonAuctionTest | `auctionInterruptReturnsToThePhaseItInterrupted` | stack discipline |
| PropertyTycoonAuctionTest | `bankruptcyToBankAuctionsEveryReturnedTileInTurn` | bank bankruptcy |
| PropertyTycoonTradeTest | `tradeProposalMovesTilesCashAndFreeCardsOnlyAfterAcceptance` | atomic swap (v1) |
| PropertyTycoonTradeTest | `tradeRejectsTilesInAColourThatHasAnyBuilding` | building lock |
| PropertyTycoonTradeTest | `tradeOfPledgedTileChargesTheNewOwnerTenPercentAtOnce` | pledge fee |
| PropertyTycoonTradeTest | `tradeRejectsMoreCashThanThePayerHolds` | affordability |
| PropertyTycoonTradeTest | `counterOfferSwapsRolesLinksParentAndStopsAtDepthThree` | counter chain |
| PropertyTycoonTradeTest | `tradeExpiresWhenTheProposersTurnEnds` | expiry |
| PropertyTycoonTradeTest | `tradeIsRejectedWhenEitherSideIsBankrupt` | validity |
| PropertyTycoonTradeTest | `easyBotNeverProposesOrAcceptsATrade` | bot policy |
| PropertyTycoonTradeTest | `mediumBotAcceptsClearlyFavourableTradesOnly` | bot policy |
| PropertyTycoonTradeTest | `hardBotProposesSetCompletingSwapsAndRespectsCooldownAfterDecline` | bot policy |
| PropertyTycoonTradeTest | `noBotHandsARivalAWholeColourWithoutLargeCompensation` | bot safety |
| PropertyTycoonDebtBankruptcyTest | `cashShortfallOpensDebtInterruptWithCreditorOrBank` | debt entry |
| PropertyTycoonDebtBankruptcyTest | `sellingBuildingsPaysHalfCostAndKeepsEvenRule` | liquidation |
| PropertyTycoonDebtBankruptcyTest | `pledgingPaysHalfPriceAndBlocksRent` | liquidation |
| PropertyTycoonDebtBankruptcyTest | `debtAutoSettlesTheMomentCashCoversIt` | auto settle |
| PropertyTycoonDebtBankruptcyTest | `playerWhoCannotCoverDebtAfterFullLiquidationMayOnlyDeclareBankruptcy` | bankrupt gate |
| PropertyTycoonDebtBankruptcyTest | `bankruptcyToPlayerTransfersTilesAndCashAndChargesPledgeInterest` | player creditor |
| PropertyTycoonDebtBankruptcyTest | `bankruptcyToBankReturnsTilesAndBuildingsToSupply` | bank creditor |
| PropertyTycoonDebtBankruptcyTest | `lastPlayerStandingWinsAndGameEnds` | winner rule |
| PropertyTycoonDebtBankruptcyTest | `bankruptPlayerIsSkippedAndTheirFreeCardsReturnToDecks` | turn order |
| PropertyTycoonBotTest | `everyBotLevelAlwaysReturnsALegalCommandInEveryPhase` | never stalls |
| PropertyTycoonBotTest | `easyBotBuysWithSmallReserveAndBidsUpToSeventyPercentOfPrice` | easy policy |
| PropertyTycoonBotTest | `mediumBotKeepsReserveAndBuildsHighestReturnEvenly` | medium policy |
| PropertyTycoonBotTest | `hardBotValuesSetCompletionAndBlocking` | hard valuation |
| PropertyTycoonBotTest | `botsPayFineEarlyAndStayInTheSandbarLate` | timeout policy |
| PropertyTycoonBotTest | `botLiquidationOrderLosesLeastValueFirst` | debt policy |
| PropertyTycoonBotTest | `botThinkBudgetStaysUnderTheLimitOnAMidRangeProfile` | perf |
| PropertyTycoonBotTest | `botDecisionsAreDeterministicForAGivenStateAndSeed` | determinism |
| PropertyTycoonSnapshotTest | `snapshotRoundTripsEveryPhaseIncludingInterrupts` | resume |
| PropertyTycoonSnapshotTest | `restoreRejectsWrongRosterOrSeatCount` | validation |
| PropertyTycoonSnapshotTest | `restoreRejectsImpossibleCashPositionsAndHoldings` | validation |
| PropertyTycoonSnapshotTest | `restoreRejectsBuildingCountsThatBreakEvenOrSupplyRules` | validation |
| PropertyTycoonSnapshotTest | `restoreRejectsDecksThatDropOrDuplicateCards` | validation |
| PropertyTycoonSnapshotTest | `restoreRejectsDanglingAuctionTradeOrDebtState` | validation |
| PropertyTycoonSnapshotTest | `restoreContinuesTheSameDiceStreamAsAnUninterruptedGame` | determinism |
| PropertyTycoonSnapshotTest | `snapshotStaysUnderSixteenKilobytesForAWorstCaseSixPlayerGame` | size budget |
| PropertyTycoonSnapshotTest | `snapshotIsNullAfterTheMatchEnds` | house pattern |
| PropertyTycoonHubSpecsTest | `specIsAdultsAudienceWithMinAgeEightSoKidProfilesNeverSeeIt` | kid filter |
| PropertyTycoonHubSpecsTest | `kidMaySeeIsFalseForEveryAgeBand` | kid filter |
| PropertyTycoonHubSpecsTest | `specIsNotRegisteredInGameHubSpecsUntilTheCoordinatorAddsIt` | registry guard |
| PropertyTycoonHubSpecsTest | `everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey` | HubLocalizationTest rules |
| PropertyTycoonHubSpecsTest | `launchesProduceTwoToSixSeatsForCpuAndPassAndPlayModes` | buildHubLaunch |
| PropertyTycoonHubSpecsTest | `handicapOptionsOnlyShowWhenAPlayerIsChosen` | visibleWhen |
| PropertyTycoonSimulationTest | `botVsBotHarnessRunsTenThousandSeededGamesWithoutException` | harness smoke (manual) |
| PropertyTycoonSimulationTest | `averageStandardGameLengthIsBetweenSixtyAndOneHundredFortyRounds` | balance target |
| PropertyTycoonSimulationTest | `winnerDistributionBySeatStaysWithinFourPointsOfEven` | balance target |
| PropertyTycoonSimulationTest | `noGameExceedsTheHardRoundCap` | termination |
| PropertyTycoonSimulationTest | `quickModeFinishesInTwentyRoundsAndHasAClearWinnerInNinetyPercentOfGames` | balance target |
| PropertyTycoonSimulationTest | `wholeColourOwnershipCorrelatesWithWinningForEveryGroup` | set value report |
| PropertyTycoonSimulationTest | `bankruptcyTurnDistributionHasNoSpikeBeforeRoundTwelve` | balance target |
| PropertyTycoonFuzzTest | `randomLegalCommandsNeverThrowAndAlwaysConserveTotalPearlsPlusTiles` | invariants |
| PropertyTycoonFuzzTest | `anySnapshotTakenMidFuzzRestoresToAnEqualState` | round trip |
| PropertyTycoonFuzzTest | `tileOwnershipBuildingSupplyAndDecksStayConsistentAfterEveryCommand` | invariants |
| PropertyTycoonGameTest | `gameModuleDeclaresIdPropertyTycoonBoardCategoryTwoToSixPlayers` | metadata |
| PropertyTycoonGameTest | `initRejectsRostersOutsideTwoToSixSeats` | init guard |
| PropertyTycoonGameTest | `startMatchDealsStartingPearlsAndPicksTheFirstPlayerFromTheSeed` | start |
| PropertyTycoonGameTest | `abortMatchEndsWithWasAbortedAndNoScores` | house pattern |
| PropertyTycoonGameTest | `endMatchReportsRankedScoresWithASingleWinner` | result |
| PropertyTycoonGameTest | `resumeAfterRestoreKeepsTheSameCurrentPlayerAndPhase` | resume |

---

# 4. SCAFFOLD

New package `app/src/main/java/com/gamesuite/games/propertytycoon/`, compile-only skeletons:

| File | Contents |
|---|---|
| `PropertyTycoonModels.kt` | `PtRelease` feature gates, `PtRules` constants, enums (`TileType`, `ColorGroup`, `DeckKind`, `GameLength`, `Phase`, `DebtReason`, `TradeStatus`, `PtReject`), `Tile`, `CardEffect`, `Card`, `HouseRules`, `TycoonConfig`, `PlayerState`, `Holding`, `DebtState`, `AuctionState`, `TradeSide`, `Trade`, `TycoonState`, `PtCommand`, `PtEvent`, `PtResult` |
| `PropertyTycoonBoard.kt` | `PtBoard`: the 40 tiles as data (generated from the table above) |
| `PropertyTycoonCards.kt` | `PtCards`: both 16-card decks as data |
| `PropertyTycoonEngine.kt` | `PtRng` (real SplitMix64), `PropertyTycoonEngine` interface, `StubPropertyTycoonEngine` (deals a valid opening table, refuses every command with `NOT_IMPLEMENTED`) |
| `PropertyTycoonBot.kt` | `PropertyTycoonBot` interface and `StubPropertyTycoonBot` |
| `PropertyTycoonSnapshot.kt` | `PropertyTycoonSnapshot` DTO, codec, `validate` stub with the full validation TODO list in KDoc |
| `PropertyTycoonGame.kt` | `PropertyTycoonGame : GameModule, ResumableGame` wrapper (id `property-tycoon`, BOARD, 2 to 6 players) |
| `PropertyTycoonHubSpecs.kt` | `GameHubSpec` with `audience = GameAudience.ADULTS`, `minAge = 8`, parse helpers; NOT registered anywhere |

Also created: `res/values/property_tycoon_strings.xml` and `res/values-es/property_tycoon_strings.xml` (identical key sets, real Spanish values), and the test stubs listed in section 3.9. No shared file was touched.

## Integration notes for the coordinator (later, not part of this scaffold)
1. `GameHubSpecs.baseSpecs()`: add `PropertyTycoonHubSpecs.propertyTycoon` in the BOARD section; do NOT add the id to `networkedGames`; no `GameAudienceTable` row.
2. `GameCatalog`: glyph `PropertyTycoonHubSpecs.GLYPH`. `GameTheme`: accents `ACCENT_LIGHT`/`ACCENT_DARK` (run the contrast check).
3. `MainActivity`: route `property-tycoon` with `rememberActiveModule(sessionManager, "property-tycoon") { PropertyTycoonGame(<real engine>, <real bot>) }` once P0 to P2 exist; the screen is P1.
4. `GameTips`: three tips with literals equal to `tip_property_tycoon_1..3`. `GameCoach`: add `property-tycoon` with `coach_property_tycoon_s1..s5`, `_fact`, motif `DICE`.
5. Duplicate-resource watch: all `pt_*`, `coach_property_tycoon_*`, `tip_property_tycoon_*` keys are unique; the `hub_*` keys are derived from text, so if another new game defines the same hub text the build reports a duplicate. Remove the duplicate from one file.
6. Gate: the stubs are @Ignore'd and the engine is a stub, so the unit gate stays green. If `HubLocalizationTest` is extended to unregistered specs, all hub texts here are covered.
