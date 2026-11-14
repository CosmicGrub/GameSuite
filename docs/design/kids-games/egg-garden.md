# Egg Garden (`egg-garden`): SCOPE, SCRIPT, SCALE, SCAFFOLD

Status 2026-11-07, branch `session/wave-3`. Design only plus a compiling scaffold. **Nothing in this package has been built, compiled or run** (no Gradle, no tests, no emulator). Numbers marked "sample" come from a throwaway Python simulation of the rules below (80 games per competitive line, 40 per co-op line), which was run outside the repo and is not part of the build; they carry a sampling error of about 5 percentage points and must be re-measured by the Kotlin tests (EG-B3, EG-S7) once the engine exists.

**Legal rule (hard).** Mechanics are free to use; names, art, rules wording and layouts are not. Egg Garden uses the genre mechanics (draw a two-square terrain tile, attach it to your own growing grid so matching terrains touch, collect eggs shown on tiles, bonus for the biggest connected region) and nothing else from any existing product. We do not use the names, art or components of Dragomino, Kingdomino, Carcassonne, ICECOOL, Coconuts or any publisher's product. Our own: five terrains (Meadow, Pond, Woods, Blossom, Hill), "nest" in the middle (not a castle or a dragon), "patch", "peek turn", "free spot", a 5 x 5 garden, 60 tiles generated for this app, our own scoring (eggs plus one patch bonus, no multipliers, no crowns, no kings, no dragons, no meeples). Art checklist in section 4.5.

**Inputs read.** `docs/research/kids-5yo-games-research.md` (lessons: cooperative first, no reading, 5 to 15 minutes, a little choice, fairness across ages, parent value) and `docs/research/bgg-geeklist-307608-page1.md` (tile-placement "first" games show kids can handle placement with scoring kept simple; cooperative games are about a quarter of the list). House pattern from `games/bridgebuilders` and `games/slideclimb`, and `docs/audit/01, 02, 04`.

## 0. Decisions at a glance

| Topic | Decision |
|---|---|
| Id, title | `egg-garden`, "Egg Garden" (ES "Jardín de Huevos") |
| Audience | `GameAudience.KIDS`, `minAge = 5`, 2 to 4 players |
| Modes | vs Computers, Same screen (competitive); "Build one garden together" (co-op) |
| Board | 5 x 5 garden per player (nest in the middle, 12 tiles fill it); co-op: one shared 7 x 7 (24 tiles) |
| Tiles | 60 two-square tiles, 5 terrains, 0 to 2 eggs per square, max 3 per tile |
| Turn | draw 1 tile, place it (one of 4 orientations); handicap "peek turn": draw 2, keep 1 |
| Placement | touch the garden AND touch a matching terrain (nest is wild); free-spot fallback so nobody is ever stuck |
| Scoring | 1 point per egg + 4 patch bonus for the biggest connected patch (ties share, at least 3 squares) |
| Co-op | goal tables with three stars, no losing |
| Multiplayer | same screen now; Nearby later, host-authoritative (deck stays hidden on the host) |
| Not registered | `EggGardenHubSpecs.spec` is not in `GameHubSpecs` |

## 1. SCOPE

### 1.1 Purpose
A calm, colourful placing game: draw a tile with two squares, find where it fits in your garden, count the eggs. It is the "first" tile-placement game for a five-year-old (matching colours, planning a grid, counting to 3) that an adult also enjoys because the patch bonus rewards planning. It gives the program its tile-placement title and a second co-op game.

### 1.2 Audience and fairness (adult plus child)
- Target: age 5+ with an adult nearby; stays interesting to 9+ and adults (the placement choice and the patch bonus reward planning, but luck of the draw dominates: sample results show Hard beats Easy only 70 percent of the time).
- Fairness levers, all in setup:
  1. **Peek turns** for chosen seats (handicap, "extra tile draw"): every third turn (turns 1, 4, 7, 10 of 12) draw two tiles, keep one, the other goes to the bottom of the deck. Sample: in Medium vs Medium the peeking seat wins 62 percent (every turn: 88, every fourth: 59).
  2. **Free placing** (table-wide): only the "touch the garden" rule applies, matching is not needed.
  3. **Short garden** (8 tiles instead of 12).
  4. **Co-op**: everyone builds one garden; nobody wins against anybody.
- The bonus is small (4 points on about 16 eggs) so a lucky child can beat a planner.
- Ties are shared ("Everybody wins this one!"), never "you lose".

### 1.3 MVP, v1, later
| Stage | Content |
|---|---|
| **MVP** | Competitive 2 to 4 seats (people and computer gardeners), Full and Short garden, peek-turn handicap, Free placing, co-op "Build one garden together" with three goal bands, tutorial, five UI states, resume, bots Easy/Medium/Hard, EN + ES |
| **v1** | Per-profile garden gallery (last garden thumbnails, local only), haptics and sound pack, one-switch polish, daily seeded garden |
| **Later** | Nearby host-authoritative, extra terrain pack (a sixth terrain), seasonal tile art, a solo "fill the garden" puzzle mode |

### 1.4 Out of scope
Crowns, multipliers, kings, special action tiles, trading, take-that effects, online play in this version, sharing pictures, ads, timers that pressure a child.

### 1.5 Safety and age notes
No personal data, no links out, no chat. Soft shapes and gentle motion; no flashing. Small pieces are purely digital (no choking issue). Tiles are dragged with one finger and snap to the grid; mistakes are never final until "Place it" is tapped. The deck is never shown to a child as numbers beyond "tiles left".

### 1.6 Accessibility
- **Targets**: grid cells are at least 56 dp on phones in the 5 x 5 competitive garden (a 360 dp phone gives 64 dp cells); the co-op 7 x 7 is 44 to 48 dp per cell on a phone, so co-op uses two-step placement (tap the cell, then "Place it", 56 dp) and a zoom of the garden, and on tablets cells reach 56 dp.
- **Colour AND shape**: five terrains, each with a unique colour and a unique corner glyph (grass tuft, ripples, tree, flower, peak); eggs are drawn as white ovals with a number badge when 2.
- **Aim-assist equivalents**: legal spots glow with a green ring and a check mark (shape + colour); the "hint" button highlights one best spot (Medium bot heuristic) and costs nothing.
- **One-switch**: a scan highlights legal placements one by one (row-major, 1 s each, wrapping); tap once to choose a spot, tap again on "Place it" (auto-scanning buttons). Turning the tile is a scan stop as well.
- **Relaxed variants**: Free placing; Short garden; peek turns for the younger player; co-op.
- **Tap-instead-of-drag**: tap a highlighted cell to put the tile there (EG-A1); dragging is optional.
- **TalkBack**: each cell has a description ("Row 2, column 3: Pond, eggs: 1.", a label form that needs no plural), the drawn tile is described ("Pond and Meadow tile, eggs: 2."), the number of legal places is announced ("Places for this tile: 5."); score changes are polite live regions.
- **Reduced Motion**: tile snaps without flight, no sparkle on eggs.
- Large font: labels sit in 56 dp pills; numbers are the only text on the board.

### 1.7 What it teaches (one line for the parent dashboard)
Matching colours and shapes, counting, and planning a layout on a grid.

### 1.8 Dependencies
Existing: `GameModule`, `ResumableGame`, `GameHubSpec`/`HubOptions`, `GameAudience`, kotlinx.serialization, Compose (screen later). Shared files for the integrator (not touched here): `GameHubSpecs`, `GameCatalog`, `GameTheme`, `MainActivity`, `GameTips`, `GameCoach`; see 4.4. Soft: `LocalReducedMotion`, colour-blind mode, profiles foundation. No new libraries, no network.

### 1.9 Open questions (each with a recommended default)
| # | Question | Recommended default |
|---|---|---|
| Q1 | Is the 4-point patch bonus right? | Yes for MVP; re-measure with the real bots (EG-B3) and adjust to 3 or 5 if it decides more than a third of games |
| Q2 | Peek handicap frequency? | Every third turn (62 percent in the sample); the Hub help text says so |
| Q3 | Should co-op be 5 x 5 for two players? | No: one 7 x 7 for all counts (24 tiles, about 5 to 8 minutes) |
| Q4 | Show remaining deck count? | Show "tiles left" as a pip row, not a number |
| Q5 | Free-spot rule: automatic or a button? | Automatic (the engine reports `OK_FREE_SPOT` and the screen says "Free spot!") |
| Q6 | Hard bot is only on par with Medium in the sample (48 percent). Tune? | Yes in phase 3: add one-ply lookahead on holes and patch growth; target Hard at least 60 percent against Medium |
| Q7 | Hidden `style` hub option (one route, two modes)? | Acceptable; fall back to two routes if the Hub drops hidden defaults (EG-H9) |
| Q8 | Terrain naming and art for ES | Pradera, Estanque, Bosque, Flores, Colina (strings file); art reviewed for the legal checklist |
| Q9 | Discard rule when nothing fits | Discard silently with a friendly line, no penalty (turn is spent) |
| Q10 | A solo mode? | Later (v1+): "fill the garden" to beat your star count |

## 2. SCRIPT

### 2.1 Vocabulary
**Garden**: the grid (5 x 5 per player; 7 x 7 shared in co-op), coordinates (row, col), row 0 on top. **Nest**: the wild middle square, never covered. **Tile**: two orthogonally joined squares, A and B, each with a terrain and 0 to 2 eggs. **Patch**: an orthogonally connected group of squares of one terrain (the nest is in no patch, diagonals never join). **Peek turn**: draw two tiles, keep one. **Free spot**: a placement that connects but does not match.

### 2.2 Rules script (competitive)
**Setup**
1. 2 to 4 gardeners (people and computer gardeners). Choose Garden size (Short 8 tiles, Full 12), Peek turns for (nobody, all, or one seat), Free placing.
2. Each gardener gets a 5 x 5 garden with the nest on (2,2). Every gardener places exactly 8 (Short) or 12 (Full) tiles, so everyone has equal turns (Full fills the garden: 24 squares plus the nest).
3. The deck is the first `12 x players + 4` (or `8 x players + 4`) tiles of a seeded shuffle of the 60-tile set (section 2.4). Four spare tiles mean the deck never runs dry even if tiles are discarded or peeked.
4. The first mover is chosen by the Hub; turns go round in seat order.

**A turn**
5. Tap the pile to **draw** the top tile. (Peek turn: draw the top two tiles, tap the one to keep; the other goes to the bottom of the deck.)
6. **Place** the tile: drag it (or tap a glowing cell) so both of its squares lie on empty garden squares; tap "Turn tile" to cycle the four ways to lay the pair down (and swap which square is which); tap "Place it" to confirm. "Pick up" returns it before confirming.
7. **Legal placement** (all must hold):
   a. both squares are inside the garden and empty (the nest is never empty);
   b. at least one of the tile's squares touches (orthogonally) an occupied square or the nest (CONNECT);
   c. at least one of the tile's squares touches (orthogonally) a square of the same terrain, or the nest (MATCH). The two squares of one tile never count for each other.
8. **Free spot**: if no placement satisfies MATCH (or Free placing is on), any placement that satisfies a and b is legal and is announced as "Free spot!".
9. **No room**: if no placement satisfies even a and b, the tile is discarded, the turn is spent, there is no penalty ("No room for this tile. On to the next!").
10. A rejected placement changes nothing.
11. The turn ends; the next gardener draws. The game ends when every gardener has taken their last turn.

**Scoring (no multipliers)**
12. **Eggs**: 1 point per egg on any square of your garden (a tile shows 0 to 3 eggs in all).
13. **Patch bonus**: find the biggest patch in every garden. Every gardener whose biggest patch is the largest of all gardens (ties share) and has at least 3 squares scores +4. If the largest is under 3, nobody gets it.
14. Total = eggs + patch bonus.
15. **Winner**: highest total. **Tie-breaks** in order: (1) more eggs, (2) bigger biggest patch, (3) more squares filled, (4) a shared win ("Everybody wins this one!").

**Co-op: "Build one garden together"**
16. One shared garden, 7 x 7, nest on (3,3). The team places 24 tiles in turn order (24 turns), every turn is a peek turn (draw two, talk, keep one). The deck is 28 tiles.
17. Placement, free spot and no-room rules as above. The shared garden is scored once.
18. **Stars** from the goal table in 2.5 (three tiers per band: Seedling, Sprout, Bloom). Stars = the highest tier whose every condition holds. One star or more shows "We did it!"; zero stars shows "Try again together" with the same seed offered again or a new one. Nobody loses.

### 2.3 State machine
| State | Entered when | Screen | Leaves on |
|---|---|---|---|
| SETUP | Hub launch | Setup summary (handicap row: peek turns, free placing) | Start |
| START | roster fixed | First-run tutorial (once per profile) or "Ready, %1 first!" with seat badges | Play tap |
| DRAW | a turn begins | Mid-game: pile glows, "Tap the pile to draw a tile." | draw |
| PEEK | peek turn, tiles drawn | two tiles side by side, "Keep this one" | keep |
| PLACE | a tile is held | garden with glowing legal cells, Turn tile, Place it, Pick up | place or no room |
| (turn end) | placed or discarded | egg count pips tick up; next seat DRAW | last turn, then MATCH_DONE |
| MATCH_DONE | all turns taken or Leave | Score card: eggs, patch bonus, total per garden, winner or stars | Play again / All done |

Five UI states the screen must show: setup, start, mid-game (DRAW/PEEK/PLACE), post-game, score card. Transitions: SETUP -> START -> DRAW -> (PEEK) -> PLACE -> DRAW (next seat) ... -> MATCH_DONE.

### 2.4 Tile set (60 tiles), deck composition, adjacency and scoring data
Five terrains: Meadow (M, grass tuft, leaf green 7CB342), Pond (P, ripples, blue 1E88E5), Woods (W, tree, bark brown 6D4C41), Blossom (B, flower, pink EC407A), Hill (H, peak, grey 9E9E9E).

Composition (the generator builds exactly this, checked once outside the build; EG-T1 to EG-T3 re-check it):
| Kind | Pairs | Tiles each | Tiles |
|---|---|---|---|
| Same terrain | Blossom, Hill, Meadow, Pond, Woods | 3 | 15 |
| Mixed, neighbours on the ring Meadow-Pond-Woods-Blossom-Hill | Blossom+Hill, Blossom+Woods, Hill+Meadow, Meadow+Pond, Pond+Woods | 5 | 25 |
| Mixed, two apart on the ring | Blossom+Meadow, Blossom+Pond, Hill+Pond, Hill+Woods, Meadow+Woods | 4 | 20 |
| **Total** | 15 pairs | | **60** |

| Terrain | Squares | Eggs | Squares with 0 / 1 / 2 eggs |
|---|---|---|---|
| Meadow | 24 | 17 | 10 / 11 / 3 |
| Pond | 24 | 17 | 10 / 11 / 3 |
| Woods | 24 | 17 | 10 / 11 / 3 |
| Blossom | 24 | 17 | 10 / 11 / 3 |
| Hill | 24 | 17 | 10 / 11 / 3 |

Eggs in the whole set: 85 on 120 squares (0.71 per square). Tiles by total eggs: 0 eggs 7, 1 egg 30, 2 eggs 14, 3 eggs 9. Tiles with a double-egg square: 15. No tile shows more than 3 eggs.

| Game | Gardeners | Tiles each | Turns | Deck (turns + 4) |
|---|---|---|---|---|
| Full | 2 | 12 | 24 | 28 |
| Full | 3 | 12 | 36 | 40 |
| Full | 4 | 12 | 48 | 52 |
| Short | 2 | 8 | 16 | 20 |
| Short | 3 | 8 | 24 | 28 |
| Short | 4 | 8 | 32 | 36 |
| Co-op | 2 to 4 (one garden) | team of 24 tiles | 24 | 28 |

Deck for a game: shuffle the 60 tiles with the seeded stream (Fisher-Yates on a SplitMix64 stream) and take the first `turns + 4`: 12 x players + 4 (Full), 8 x players + 4 (Short), 28 (co-op). Peeked-and-passed tiles go to the bottom, so the deck never shrinks except by one tile per turn.

Adjacency examples on a 5 x 5 garden (N = nest at (2,2), letters = terrain, `.` = empty). The tile in the examples is "Pond on square A, Meadow on square B".
```
 Garden: nest only                Garden: nest plus a Woods tile
   . . . . .                        . . . . .
   . . . . .                        . . . . .
   . . N . .                        . . N W W
   . . . . .                        . . . . .
   . . . . .                        . . . . .
```
- Nest only. A at (1,2), B at (0,2): A touches the nest from above. CONNECT holds and the nest matches anything, so MATCH holds: **legal**.
- Nest only. A at (0,0), B at (0,1): touches nothing occupied: **NOT_CONNECTED**.
- Nest plus Woods. Hill-Hill tile at (1,3),(1,4): it touches Woods at (2,3) and (2,4) but not the nest and has no Woods square: CONNECT holds, MATCH fails: **NO_MATCH**, legal only as a **free spot** (when no matching placement exists anywhere, or Free placing is on).
- Nest plus Woods. Woods-Pond tile with Woods on (1,4) and Pond on (0,4): the Woods square touches the Woods at (2,4): **legal**.
- Same-terrain tile (Meadow-Meadow) laid next to nothing but itself never matches itself.

Scoring example: garden eggs 15 and biggest patch 6 (Woods) while the other garden has eggs 17 and biggest patch 5: totals 15 + 4 = 19 against 17 + 0 = 17.

Full tile table (also in `EggGardenTileData.TILES`; "A" and "B" are the two squares):

| id | square A | eggs A | square B | eggs B | total |
|---|---|---|---|---|---|
| t01 | Meadow | 0 | Meadow | 1 | 1 |
| t02 | Meadow | 2 | Meadow | 1 | 3 |
| t03 | Meadow | 0 | Meadow | 1 | 1 |
| t04 | Pond | 0 | Pond | 0 | 0 |
| t05 | Pond | 1 | Pond | 1 | 2 |
| t06 | Pond | 1 | Pond | 0 | 1 |
| t07 | Woods | 1 | Woods | 0 | 1 |
| t08 | Woods | 1 | Woods | 0 | 1 |
| t09 | Woods | 2 | Woods | 1 | 3 |
| t10 | Blossom | 0 | Blossom | 1 | 1 |
| t11 | Blossom | 2 | Blossom | 1 | 3 |
| t12 | Blossom | 2 | Blossom | 0 | 2 |
| t13 | Hill | 0 | Hill | 1 | 1 |
| t14 | Hill | 0 | Hill | 1 | 1 |
| t15 | Hill | 1 | Hill | 0 | 1 |
| t16 | Meadow | 2 | Pond | 1 | 3 |
| t17 | Meadow | 2 | Pond | 1 | 3 |
| t18 | Meadow | 0 | Pond | 1 | 1 |
| t19 | Meadow | 1 | Pond | 1 | 2 |
| t20 | Meadow | 1 | Pond | 0 | 1 |
| t21 | Pond | 0 | Woods | 2 | 2 |
| t22 | Pond | 0 | Woods | 2 | 2 |
| t23 | Pond | 0 | Woods | 0 | 0 |
| t24 | Pond | 0 | Woods | 1 | 1 |
| t25 | Pond | 2 | Woods | 1 | 3 |
| t26 | Woods | 0 | Blossom | 1 | 1 |
| t27 | Woods | 0 | Blossom | 0 | 0 |
| t28 | Woods | 0 | Blossom | 0 | 0 |
| t29 | Woods | 1 | Blossom | 0 | 1 |
| t30 | Woods | 1 | Blossom | 0 | 1 |
| t31 | Blossom | 1 | Hill | 0 | 1 |
| t32 | Blossom | 0 | Hill | 1 | 1 |
| t33 | Blossom | 1 | Hill | 0 | 1 |
| t34 | Blossom | 1 | Hill | 0 | 1 |
| t35 | Blossom | 1 | Hill | 1 | 2 |
| t36 | Hill | 0 | Meadow | 1 | 1 |
| t37 | Hill | 1 | Meadow | 0 | 1 |
| t38 | Hill | 1 | Meadow | 0 | 1 |
| t39 | Hill | 0 | Meadow | 0 | 0 |
| t40 | Hill | 2 | Meadow | 1 | 3 |
| t41 | Meadow | 0 | Woods | 1 | 1 |
| t42 | Meadow | 1 | Woods | 0 | 1 |
| t43 | Meadow | 0 | Woods | 0 | 0 |
| t44 | Meadow | 0 | Woods | 1 | 1 |
| t45 | Pond | 1 | Blossom | 2 | 3 |
| t46 | Pond | 1 | Blossom | 0 | 1 |
| t47 | Pond | 2 | Blossom | 0 | 2 |
| t48 | Pond | 0 | Blossom | 0 | 0 |
| t49 | Woods | 0 | Hill | 2 | 2 |
| t50 | Woods | 1 | Hill | 0 | 1 |
| t51 | Woods | 0 | Hill | 2 | 2 |
| t52 | Woods | 1 | Hill | 0 | 1 |
| t53 | Blossom | 1 | Meadow | 1 | 2 |
| t54 | Blossom | 1 | Meadow | 1 | 2 |
| t55 | Blossom | 1 | Meadow | 0 | 1 |
| t56 | Blossom | 1 | Meadow | 1 | 2 |
| t57 | Hill | 1 | Pond | 1 | 2 |
| t58 | Hill | 1 | Pond | 2 | 3 |
| t59 | Hill | 1 | Pond | 1 | 2 |
| t60 | Hill | 1 | Pond | 0 | 1 |

### 2.5 Co-op goal tables
Every tier lists ALL conditions that must hold. "Terrain patches" = number of the five terrains that have a patch of 3 or more squares.

| Band | 1 star | 2 stars | 3 stars |
|---|---|---|---|
| Seedling goals (little ones) | 26 eggs | 31 eggs and a patch of 7 | 35 eggs, a patch of 9 and 3 terrain patches |
| Sprout goals | 30 eggs | 34 eggs and a patch of 9 | 38 eggs, a patch of 11 and 4 terrain patches |
| Bloom goals | 33 eggs | 37 eggs and a patch of 11 | 40 eggs, a patch of 13 and 5 terrain patches |

Calibration (sample, 40 co-op gardens per bot level, draw-two-keep-one, 7 x 7, 24 tiles; coordinator should re-run with at least 2000 in EG-S7): Hard bot eggs P10 33, P25 35, P50 36, P75 39, P90 40; biggest patch P10 9, P50 10, P90 13; terrains with a patch of 3 or more: 4.7 on average. Reading those percentiles roughly (assuming independence, so only indicative): Sprout 1 star is reached by about 99 percent of good play, Sprout 2 stars by about 3 in 4, Sprout 3 stars by about 1 in 9. Seedling 1 star (26 eggs) sits far below the sample's P10 on purpose, Seedling 3 stars is reached by about 2 in 3 good gardens; Bloom 1 star is about 9 in 10, Bloom 2 stars about 1 in 5, Bloom 3 stars about 1 in 30. Children will score lower than the bot, so Seedling is the default.

### 2.6 Bot behaviour per level
The computer gardener runs on a worker thread, never blocks the UI, and "thinks" for at least the listed time. It sees only what a player sees (the drawn tile and the gardens; the deck is hidden). Noise uses a seeded stream `SplitMix64(seed xor (turnNumber * 0x9E3779B97F4A7C15))`.
Placement value (per legal placement, computed on a copy of the garden): `value = 2.0 x contacts + 3.0 x (growth of the biggest patch) - 4.0 x holes`, where contacts = same-terrain edge touches (nest touch counts 0.5) and holes = empty squares left with no empty neighbour (they can never be filled). On a peek turn the bot values each of the two tiles as `best placement value + 3 x eggs - 5 if free-spot only` and keeps the higher one.

| Level | Placement choice | Noise | Peek choice | Thinks at least | Sample result (2 players, Full) |
|---|---|---|---|---|---|
| Easy | uniform random among legal placements | none (random is the noise) | first tile | 0.6 s | mean score 18.2, 1.6 tiles discarded per game |
| Medium | argmax of value + Gaussian noise, sigma 2.5 | sigma 2.5 | by value | 1.0 s | mean 18.9, beats Easy 65 percent |
| Hard | argmax of value, no noise | none | by value | 1.2 s | mean 19.3, beats Easy 70 percent, Medium about even (Q6) |

Helper pals in co-op use the Hard rule and are always the one who picks when it is their turn. A hint button for people uses the Medium value without noise.
Budget: under 20 ms per decision on the 7 x 7 garden (EG-B4): at most 2 x 2 x 6 x 7 = 168 placements per tile, 2 tiles, one flood fill each.

### 2.7 Handicap rule
**Peek turns** (extra tile draw): setup option `peekFor` = nobody, all gardeners, or Gardener 1 to 4. On every third turn of a chosen seat (its turns 1, 4, 7 and 10 of 12; 1, 4 and 7 of 8) it draws two tiles, keeps one, and the other goes to the bottom of the deck. Co-op always peeks. Recommended for a 5-year-old next to an adult: kid seat peeks. Also available: **Free placing** (table-wide, matching not required) and **Short garden**.

### 2.8 First-run tutorial script
Seven pictorial cards (picture first, line second, 56 dp Next, Skip always visible). All lines under 120 characters (EG-H8). Card 6 depends on the mode.
1. `egg_garden_tut_1` "Your garden starts with a nest in the middle."
2. `egg_garden_tut_2` "Tap the pile to draw a tile with two squares."
3. `egg_garden_tut_3` "Drag it next to your garden. Matching colours must touch!"
4. `egg_garden_tut_4` "Tap the turn button to spin the tile."
5. `egg_garden_tut_5` "Every egg in your garden is a point."
6. `egg_garden_tut_6_compete` "The biggest patch of one colour wins a bonus!" or `egg_garden_tut_6_coop` "Build one garden together and reach for the stars!"
7. `egg_garden_tut_7` "You are ready! Tap Play."
The first turn of a first-run match shows the glowing legal cells with a pulsing finger and forces a Free-placing hint line only if no match exists.

### 2.9 Exact EN strings (keys)
All keys are in `res/values/egg_garden_strings.xml` (EN) and `res/values-es/egg_garden_strings.xml` (real Spanish values). Placeholders: `%1$s` name, `%1$d` numbers.

| Key | EN text |
|---|---|
| `egg_garden_title` | Egg Garden |
| `egg_garden_help_title` | How to play Egg Garden |
| `egg_garden_help_text` | Your garden starts with a nest in the middle. On your turn, draw a tile with two squares and place it next to your garden so that at least one square touches the same colour (the nest matches anything). Eggs on the tiles are points. The biggest patch of one colour earns a bonus. Fill your garden to finish. |
| `egg_garden_help_text_coop` | Everyone builds one big garden together. Draw two tiles, talk about which fits best and place one. Collect eggs and grow big patches to earn up to three stars. |
| `egg_garden_tut_1` | Your garden starts with a nest in the middle. |
| `egg_garden_tut_2` | Tap the pile to draw a tile with two squares. |
| `egg_garden_tut_3` | Drag it next to your garden. Matching colours must touch! |
| `egg_garden_tut_4` | Tap the turn button to spin the tile. |
| `egg_garden_tut_5` | Every egg in your garden is a point. |
| `egg_garden_tut_6_compete` | The biggest patch of one colour wins a bonus! |
| `egg_garden_tut_6_coop` | Build one garden together and reach for the stars! |
| `egg_garden_tut_7` | You are ready! Tap Play. |
| `egg_garden_terrain_meadow` | Meadow |
| `egg_garden_terrain_pond` | Pond |
| `egg_garden_terrain_woods` | Woods |
| `egg_garden_terrain_blossom` | Blossom |
| `egg_garden_terrain_hill` | Hill |
| `egg_garden_terrain_nest` | Nest |
| `egg_garden_status_your_turn` | %1$s, your turn! |
| `egg_garden_status_draw` | Tap the pile to draw a tile. |
| `egg_garden_status_place` | Drag the tile into your garden. |
| `egg_garden_status_peek` | Peek turn: keep one of the two tiles. |
| `egg_garden_status_free_spot` | Free spot! Any place next to your garden works. |
| `egg_garden_status_no_room` | No room for this tile. On to the next! |
| `egg_garden_status_eggs` | Eggs: %1$d |
| `egg_garden_status_patch` | Biggest patch: %1$d |
| `egg_garden_status_patch_bonus` | Patch bonus! +%1$d |
| `egg_garden_status_turn_of` | Turn %1$d of %2$d |
| `egg_garden_status_coop_win` | We did it! |
| `egg_garden_status_coop_lose` | Try again together |
| `egg_garden_status_stars` | %1$d of 3 stars |
| `egg_garden_btn_draw` | Draw a tile |
| `egg_garden_btn_rotate` | Turn tile |
| `egg_garden_btn_place` | Place it |
| `egg_garden_btn_pick_up` | Pick up |
| `egg_garden_btn_keep` | Keep this one |
| `egg_garden_btn_play_again` | Play again |
| `egg_garden_btn_try_again` | Try again together |
| `egg_garden_btn_done` | All done |
| `egg_garden_card_title` | Score card |
| `egg_garden_card_eggs` | Eggs |
| `egg_garden_card_patch` | Biggest patch |
| `egg_garden_card_bonus` | Patch bonus |
| `egg_garden_card_total` | Total |
| `egg_garden_card_winner` | %1$s wins! |
| `egg_garden_card_tie` | Everybody wins this one! |
| `egg_garden_card_goal` | Goal: %1$d eggs and a patch of %2$d |
| `egg_garden_a11y_tile` | %1$s and %2$s tile, eggs: %3$d. |
| `egg_garden_a11y_cell` | Row %1$d, column %2$d: %3$s, eggs: %4$d. |
| `egg_garden_a11y_cell_empty` | Row %1$d, column %2$d: empty. |
| `egg_garden_a11y_legal_spots` | Places for this tile: %1$d. |

Hub texts (key computed by `hubKey(text)`; defined in the same file unless already present elsewhere):

| Hub key | EN text |
|---|---|
| `hub_egg_garden` | Egg Garden |
| `hub_build_a_garden_and_collect_the_eggs` | Build a garden and collect the eggs. |
| `hub_grow_gardens_against_1_to_3_computer_gardeners` | Grow gardens against 1 to 3 computer gardeners. |
| `hub_take_turns_on_one_screen_computer_gardeners_can_511981` | Take turns on one screen. Computer gardeners can join in. |
| `hub_build_one_garden_together` | Build one garden together |
| `hub_everyone_adds_tiles_to_one_big_garden_reach_for_14c945` | Everyone adds tiles to one big garden. Reach for the stars! |
| `hub_garden_size` | Garden size |
| `hub_how_many_tiles_each_gardener_places` | How many tiles each gardener places. |
| `hub_short_garden_8_tiles` | Short garden: 8 tiles |
| `hub_full_garden_12_tiles` | Full garden: 12 tiles |
| `hub_peek_turns_for` | Peek turns for |
| `hub_every_third_turn_draw_two_tiles_and_keep_the_one_d4c497` | Every third turn, draw two tiles and keep the one you like. |
| `hub_nobody` | Nobody |
| `hub_all_gardeners` | All gardeners |
| `hub_gardener_1` | Gardener 1 |
| `hub_gardener_2` | Gardener 2 |
| `hub_gardener_3` | Gardener 3 |
| `hub_gardener_4` | Gardener 4 |
| `hub_free_placing` | Free placing |
| `hub_tiles_may_go_anywhere_next_to_your_garden_matchi_302ef0` | Tiles may go anywhere next to your garden, matching or not. |
| `hub_computer_gardener_level` | Computer gardener level |
| `hub_team_goals` | Team goals |
| `hub_how_many_eggs_and_how_big_a_patch_earn_the_stars` | How many eggs and how big a patch earn the stars. |
| `hub_seedling_goals` | Seedling goals |
| `hub_sprout_goals` | Sprout goals |
| `hub_bloom_goals` | Bloom goals |
| `hub_garden_style` | Garden style |
| `hub_building_together` | Building together |

## 3. SCALE

### 3.1 Length by mode (target 5 to 12 minutes)
Assumption (estimate, unmeasured): 12 s a turn (draw, look, drag, confirm, hand-over; bots about 2 s).

| Mode | Players | Short (8 tiles each) | Full (12 tiles each) |
|---|---|---|---|
| Competitive | 2 | 3.2 min | 4.8 min plus tutorial and score card, about 6 min |
| Competitive | 3 | 4.8 min | 7.2 min |
| Competitive | 4 | 6.4 min | 9.6 min |
| Co-op (24 tiles) | 2 to 4 | not applicable | 5 min plus talking, 6 to 9 min |
With 2 people the Full game is just inside the target once the score card is counted; Short is the 3 to 6 minute option for the youngest. The hard ceiling is 4 players Full: 9.6 minutes.

### 3.2 Player counts
2 to 4 (people and computer gardeners); co-op 2 to 4. One shared device. A hand-over screen ("Ready, %1 first!") appears at the start of each human turn on pass-and-play only when a computer turn came in between.

### 3.3 Deterministic seeds and replay
One 64-bit `seed` from the injected `Random`; a `SplitMix64` stream inside the engine drives the shuffle and the bot noise (not `kotlin.random`). Rules have no other randomness. A **replay** is `(config, seed, turn log)` where each turn is `(seat, peekKeep, r1, c1, r2, c2, flipped, discarded)`; the state hash after every turn must match (EG-R1). State hash: FNV-1a 64 over every garden cell (terrain, eggs), the deck order, the turn counter and the RNG state.

### 3.4 Performance budget
| Item | Budget |
|---|---|
| Engine work per move | negligible: under 1 ms to enumerate placements (at most 168 per tile on 7 x 7) |
| Bot decision | under 20 ms Hard on 7 x 7 (EG-B4), run off the main thread, with a minimum on-screen think time |
| Allocation | placement lists and flood fills reuse preallocated IntArrays per engine; no allocation in the draw loop of the UI beyond Compose's own |
| Rendering | static grid, tile flight animation 200 ms (skipped in Reduced Motion) |
| Memory | under 200 KB engine state; art is vector Canvas, no bitmaps required for MVP |

### 3.5 Resume and persistence
Snapshot = `EggGardenSave` JSON: settings, seed, roster ids and the turn log (at most 28 entries, under 1.5 KB). `restore` replays from the seed, compares the state hash and returns false (engine untouched) on any mismatch or invalid field (list in the KDoc of `EggGardenSaveCodec.isValid`). `snapshot()` is null before start, after the match and in non-resumable modes. A tile that is drawn but not yet placed resumes in PLACE with the same tile (it is a pure function of the log and the seed). Garden gallery and co-op stars per profile belong to the profiles foundation.

### 3.6 Multiplayer plan
- **Now: same screen only** (`SINGLE_DEVICE_PASS_AND_PLAY`, `SINGLE_PLAYER_VS_BOT`); no Nearby or Online mode in the spec; do not list in `networkedGames`.
- **Later: host-authoritative.** The deck order never leaves the host (hidden information). Guests send `{seat, placement, turnNumber}`; the host validates phase, seat (from the transport's authenticated `fromPlayerId`, never an id in the payload), bounds and the legality verdict, then broadcasts the drawn tile (only to everyone as it is drawn, the next tiles stay secret), the accepted placement and the state hash. Peek tiles are shown to all (no secret) so a co-op team can discuss. Messages are tiny and turn-based, so latency is irrelevant. Lockstep is unnecessary because there is no real-time physics.

### 3.7 Phased delivery
| Phase | Content | Effort |
|---|---|---|
| 0 | Tile data checks promoted to tests (EG-T1 to T6), SplitMix64, deck builder | S |
| 1 | Engine: garden, placement rules and verdicts, scoring, tie-breaks, peek, free spot, hash, save/restore, replay; tests EG-P, EG-S, EG-H1 to H3, EG-R | M |
| 2 | Screen: garden Canvas, tile art (5 terrains, eggs), drag and tap placement, five UI states, tutorial, EN + ES wiring | M |
| 3 | Bots with the heuristic above; Hard lookahead tuning (Q6); tests EG-B | S |
| 4 | Hub and shell integration (4.4), profiles hooks, accessibility review (one-switch scan, TalkBack) | S |
| 5 | Co-op: 7 x 7 garden zoom, goal tables, star card, calibration re-run (EG-S7) | M |
| 6 | Sound, haptics, polish, device pass | S |
| Later | Nearby host-authoritative; garden gallery; extra terrain pack | L |

### 3.8 Risk register
| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | Luck dominates and adults feel no skill | boredom for adults | patch bonus rewards planning, Hard bot, peek turns for the child only; Q1 re-measure |
| R2 | MATCH rule is too strict late in the garden | frustration | free-spot fallback, discard without penalty, Free placing option |
| R3 | 7 x 7 co-op cells too small on phones | missed taps | two-step placement, zoom, tablet cells 56 dp |
| R4 | Look-alike of an existing product | legal | own terrain set, nest instead of castle, own scoring, own art; 4.5 checklist; legal review of final art |
| R5 | Deck runs dry | crash | 4 spare tiles, test EG-T6 over every configuration |
| R6 | Hard bot no better than Medium | weak CPU levels (QUALITY-BAR gate 2) | Q6 lookahead in phase 3 |
| R7 | String key clashes with parallel work | duplicate-resource build break | all keys prefixed `egg_garden_`; hub keys checked against every other file by EG-H7 |
| R8 | Hub drops hidden `style` default | wrong mode | EG-H9; fallback to two routes |
| R9 | Colour-only distinction of terrains | accessibility fail | unique glyph per terrain (EG-A3) |
| R10 | Save from older rules version | wrong state | replay log plus hash, version gate |

### 3.9 Test plan
All 42 tests exist as `@Ignore` stubs under `app/src/test/java/com/gamesuite/games/egggarden/` (8 classes) so the gate stays green; each is enabled when its phase lands.

Tile and deck properties: 60 unique tiles, equal squares (24) and eggs (17) per terrain, no tile above 3 eggs, deck = top of a seeded shuffle, same seed same deck over 200 seeds, deck never runs out over every player count, length and handicap.
Placement properties: bounds, empty cells, CONNECT, MATCH, free spot only when no matching placement exists, discard spends the turn, every orientation listed exactly once, a same-terrain tile cannot match itself, rejected moves leave the state hash unchanged.
Scoring properties: hand-built gardens with known totals, flood-fill patches without diagonals, bonus ties and the under-3 case, each tie-break rung, order independence of the score, fuzz of 10 000 random legal games (never throws, scores non-negative), co-op star boundaries.

- `EG-T1` (EggGardenTilesTest) `tileSetHasSixtyUniqueTiles`: ids t01..t60, no duplicates.
- `EG-T2` (EggGardenTilesTest) `everyTerrainHasTwentyFourSquaresAndSeventeenEggs`: five terrains, equal squares and eggs.
- `EG-T3` (EggGardenTilesTest) `noTileHasMoreThanThreeEggs`: a tile never shows 2 and 2.
- `EG-T4` (EggGardenTilesTest) `deckIsTheTopOfASeededShuffle`: 12 x players + 4 tiles for competitive, 28 for co-op.
- `EG-T5` (EggGardenTilesTest) `sameSeedGivesTheSameDeckAndADifferentSeedDiffers`: property over 200 seeds.
- `EG-T6` (EggGardenTilesTest) `deckNeverRunsOutBeforeTheLastTurn`: property over players 2..4, both lengths, every handicap.
- `EG-P1` (EggGardenPlacementTest) `theNestIsWildAndTheCentreIsNeverCovered`: nest cell stays occupied by the nest.
- `EG-P2` (EggGardenPlacementTest) `aPlacementMustBeInsideTheGridOnEmptyCells`: OUT_OF_BOUNDS and CELL_TAKEN verdicts.
- `EG-P3` (EggGardenPlacementTest) `aPlacementMustTouchAnOccupiedSquare`: NOT_CONNECTED verdict.
- `EG-P4` (EggGardenPlacementTest) `aPlacementMustMatchATerrainOrTouchTheNest`: NO_MATCH verdict.
- `EG-P5` (EggGardenPlacementTest) `theFreeSpotRuleAppliesOnlyWhenNoMatchingPlacementExists`: OK_FREE_SPOT only in that case.
- `EG-P6` (EggGardenPlacementTest) `anUnplaceableTileIsDiscardedAndTheTurnIsSpent`: no penalty, same number of turns.
- `EG-P7` (EggGardenPlacementTest) `everyOrientationIsListedExactlyOnce`: no duplicate and no missing legal placement.
- `EG-P8` (EggGardenPlacementTest) `adjacencyInsideASameTerrainTileDoesNotCountAsAMatch`: a tile cannot match itself.
- `EG-P9` (EggGardenPlacementTest) `aRejectedPlacementChangesNothing`: state hash identical before and after.
- `EG-S1` (EggGardenScoringTest) `eggScoreIsTheSumOfEggsOnPlacedSquares`: hand-built gardens with known totals.
- `EG-S2` (EggGardenScoringTest) `patchSizeIsAnOrthogonalFloodFillExcludingTheNest`: diagonals never join a patch.
- `EG-S3` (EggGardenScoringTest) `theBonusGoesToEveryTiedLargestPatchOfAtLeastThree`: 4 points; none when the largest is under 3.
- `EG-S4` (EggGardenScoringTest) `tieBreakOrderIsEggsThenPatchThenSquaresThenShared`: each rung decided by a crafted pair of gardens.
- `EG-S5` (EggGardenScoringTest) `scoreDoesNotDependOnPlacementOrder`: property: shuffled placement order, same score.
- `EG-S6` (EggGardenScoringTest) `randomLegalPlayNeverThrowsAndScoresAreNonNegative`: fuzz 10 000 games.
- `EG-S7` (EggGardenScoringTest) `coopStarsFollowTheGoalTable`: every band and star tier boundary.
- `EG-H1` (EggGardenTurnsTest) `peekTurnsAreEveryThirdTurnForTheChosenSeatsOnly`: turns 1, 4, 7, 10 of 12.
- `EG-H2` (EggGardenTurnsTest) `aPeekKeepsOneTileAndSendsTheOtherToTheBottom`: deck order afterwards.
- `EG-H3` (EggGardenTurnsTest) `turnOrderFollowsTheFirstMoverAndEveryoneGetsEqualTurns`: 2 to 4 players.
- `EG-B1` (EggGardenBotTest) `botIsDeterministicForTheSameSeed`: same state and seed give the same placement.
- `EG-B2` (EggGardenBotTest) `botAlwaysFindsALegalPlacementWhenOneExists`: property over random gardens.
- `EG-B3` (EggGardenBotTest) `botLevelsAreOrderedByWinRate`: medium beats easy at least 60 percent over 500 seeds.
- `EG-B4` (EggGardenBotTest) `botDecisionTakesUnderTwentyMilliseconds`: 7x7 co-op garden, Hard.
- `EG-R1` (EggGardenPersistenceTest) `snapshotRestoreRoundTripsTheStateHash`: restore then hash equals hash before snapshot.
- `EG-R2` (EggGardenPersistenceTest) `restoreRejectsAWrongRosterOrInvalidGarden`: returns false and leaves the engine untouched.
- `EG-R3` (EggGardenPersistenceTest) `aCorruptSnapshotNeverThrows`: random bytes and truncated JSON return false.
- `EG-R4` (EggGardenPersistenceTest) `snapshotIsNullOnceTheMatchIsOver`: also null before start.
- `EG-H4` (EggGardenHubSpecsTest) `hubSpecIsKidsAudienceMinAgeFiveAndNotNetworked`: audience KIDS, minAge 5, no NEARBY or ONLINE mode.
- `EG-H5` (EggGardenHubSpecsTest) `everyHubTextHasAnEnglishAndASpanishString`: hubKey(text) exists in both locales.
- `EG-H6` (EggGardenHubSpecsTest) `ourStringsMatchPlaceholdersInBothLocales`: the same numbered placeholders in EN and ES.
- `EG-H7` (EggGardenHubSpecsTest) `noStringKeyClashesWithAnotherFile`: our keys are defined only in egg_garden_strings.xml.
- `EG-H8` (EggGardenHubSpecsTest) `tutorialLinesAreUnderOneHundredTwentyCharacters`: every egg_garden_tut_* in EN and ES.
- `EG-H9` (EggGardenHubSpecsTest) `hubLaunchOptionsReachTheGameAsExtras`: style, length, peek, free and band arrive in LaunchOptions.extras.
- `EG-A1` (EggGardenAccessibilityTest) `aTileCanBePlacedByTappingACellInsteadOfDragging`: tap a highlighted cell then Place it.
- `EG-A2` (EggGardenAccessibilityTest) `oneSwitchCyclesTheLegalSpotsThenConfirms`: scan order is row-major, wraps around.
- `EG-A3` (EggGardenAccessibilityTest) `everyTerrainHasADistinctShapeAndColour`: five unique glyphs and five unique colours.

## 4. SCAFFOLD

### 4.1 Files created (all new, nothing shared touched)
| Path | What |
|---|---|
| `app/src/main/java/com/gamesuite/games/egggarden/EggGardenModels.kt` | `Terrain`, `EggTile`, style/length/goal band enums, `Placement`, `PlaceVerdict`, garden view and score, phase, config, state, summary |
| `.../EggGardenRules.kt` | constants of this doc and pure helpers: grid sizes, turns and deck size, peek turns, co-op goals and stars, patch bonus |
| `.../EggGardenEngine.kt` | `EggGardenEngine` interface (full contract in KDoc), `StubEggGardenEngine`, `EggGardenBot`, `StubEggGardenBot` |
| `.../EggGardenSave.kt` | snapshot DTOs (`EggGardenSave`, `EggLogEntry`, `GardenSeatSave`), `EggGardenSaveCodec` with the validation TODO list in KDoc |
| `.../EggGardenGame.kt` | `GameModule` + `ResumableGame` wrapper in the BridgeBuildersGame style, engine injected |
| `.../EggGardenHubSpecs.kt` | the Hub spec: `audience = GameAudience.KIDS`, `minAge = 5`, three modes; NOT registered |
| `.../EggGardenTileData.kt` | the 60 tiles as Kotlin data |
| `app/src/main/res/values/egg_garden_strings.xml`, `values-es/egg_garden_strings.xml` | 51 screen strings + hub texts, EN and real ES |
| `app/src/test/java/com/gamesuite/games/egggarden/*.kt` | 8 classes, 42 `@Ignore` stubs |

### 4.2 What is real and what is stubbed
Real: enums, data classes, constants, pure helpers (grid size, turn and deck size, peek rule, goal tables, stars, patch bonus), save codec encode/decode with version and roster-size gates, hub spec, tile data, strings. Stubbed: the engine (`StubEggGardenEngine` returns safe empty values, `configure` returns false) and the bot (keeps tile 0, discards). Tests are all ignored.

### 4.3 Key decisions
1. A fixed 5 x 5 grid (not a sliding bounding window): simpler for a child, and 12 tiles fill it exactly.
2. Draw, not pick from a row (the brief), with the choice supplied by orientation and place; peek turns are the handicap.
3. MATCH rule with an automatic free-spot fallback so nobody is ever stuck.
4. One bonus, no multipliers.
5. Co-op on one shared 7 x 7 garden with a goal table.
6. Saves are replay logs with a hash; the deck stays host-side in future multiplayer.

### 4.4 Integration notes for the coordinator (not done here)
- `GameHubSpecs.baseSpecs()`: add `EggGardenHubSpecs.spec` (family BOARD). Do not add to `networkedGames`.
- `GameCatalog`: glyph `"egg-garden" -> "🥚"` (`EggGardenHubSpecs.GLYPH`).
- `GameTheme` accents: `"egg-garden" to GameAccent(0xFF2E6B1F, 0xFF9AD68A)` (proposed; contrast not yet verified, GameThemeTest decides).
- `MainActivity`: route `egg-garden` with `rememberActiveModule(sessionManager, "egg-garden") { EggGardenGame() }` and a screen that reads `LaunchOptions.extras` (keys in the KDoc of `EggGardenHubSpecs`).
- `GameTips` and `GameCoach`: three tips and a five-step coach card (tutorial lines of 2.8 can be reused).
- `GameAudienceTable`: no row needed; add `put("egg-garden", k(5))` only if `GameAudienceTest` insists.
- Gate: `./gradlew :app:testDebugUnitTest` (HubLocalizationTest, GameThemeTest and the audience tests iterate over every spec); the duplicate-resource check matters because other agents add strings in parallel.

### 4.5 Original-art checklist
Terrain squares: flat colour plus a corner glyph (grass tuft, ripples, tree, flower, peak), soft rounded corners, no textures taken from any product. Eggs: white ovals with a faint speckle. Nest: a round twig nest with one egg, not a castle or a keep. Tile back: a leafy pattern. No dragons, kings, crowns, knights, meeples or castles anywhere; no names or phrases from other products appear in any string.
