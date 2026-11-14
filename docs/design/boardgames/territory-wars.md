# Territory Wars (`territory-wars`): scope, script, scale, scaffold

Status: DESIGN + COMPILING SCAFFOLD, 2026-11-07. Branch `session/wave-3`. **Nothing in this package has been compiled, built or run**: no Gradle, no tests, no emulator. Every Kotlin file was written against the existing sources by reading them (`GameModule`, `ResumableGame`, `GameHubSpec`, `HubOptions`, `CpuLevel`, `SlideClimbGame` as the house pattern) and must go through the coordinator's single gate.

A map-conquest strategy game for 2 to 6 players, ages 10+, in the generic style of "reinforce, attack your neighbours with dice, conquer the map" games: territories, armies, dice battles, cards that form sets, bonus regions. **The world, names, map, card symbols, set values and all text are original** (section 1.7). Audience `ADULTS`, `minAge = 10`.

Contents: 1 SCOPE, 2 SCRIPT, 3 SCALE, 4 SCAFFOLD.

---

# 1. SCOPE

## 1.1 Purpose

The Hub has no big-table strategy game: Chess, Checkers, Reversi and Shogi are 2-seat abstract duels, Ludo and UNO are luck-led, Chinese Checkers is a race. Territory Wars adds the genre that fills an evening: 3 to 6 people (or one person and computers), negotiation by threat, comebacks, tension in every roll. It must also work for one person on a phone in a 15-minute Blitz.

Success looks like: a first-time adult finishes the tutorial and a Blitz game in 20 minutes; a Standard game with 4 seats ends in about 45; no seat has an unfair win rate; a dense 36-territory map is readable and operable with one thumb on a 6-inch phone.

## 1.2 Audience, age and safety

- `audience = GameAudience.ADULTS`, `minAge = 10`, as the brief requires. Consequence worth knowing: `kidMaySee` needs `audience != ADULTS`, so a KID profile never sees this game, whatever its age band; only ADULT profiles do, and "10+" is guidance printed in the Hub/coach text for the adult who sets it up for a family. If the owner wants 10-12 kid profiles to see it, change the one line to `FAMILY` (open question Q4).
- Theme is abstract: armies are neutral round tokens with a number, never soldiers; no weapons art, no blood, no real countries, flags, cities or religions; the world is invented. Language is "armies", "territories", "attack", "conquer", "fortify". Never "kill", "die", "destroy", "massacre"; a battle reads "Attacker lost 2, defender lost 0".
- Dice are a rules device, not gambling: no stakes, no currency, no purchases. (Not on `KID_BLOCKED_GAME_IDS`; it is adult-only anyway.)
- No free text anywhere. Names are the profile display names the Hub already sanitises (`cleanPlayerName`). Online (later) uses fixed quick-chat phrases only, per the Wave 5 design.
- Nothing is stored beyond the saved game and the existing win/loss stats.

## 1.3 MVP, v1, later

| Tier | Contents |
|---|---|
| **MVP** | Setup via Hub; auto-deal; opening placement (or Auto-place); reinforce with the region and set formulas; attack with 1 to 3 dice vs 1 to 2; Attack-until-done; conquest with mandatory move-in; fortify (connected chain or next door); cards, sets, escalation, forced turn-in; elimination with card capture; wins by total conquest, capture target and round cap with tie-break; stalemate guard; vs Computers (Easy, Medium, Hard) and Pass and Play with a hand-over curtain; Blitz (half map), Standard, Long; handicap; tutorial; resume; stats; EN + ES; accessibility set in 1.5; five UI states. |
| **v1** | Secret **missions** (original mission cards, section 2.4) as an alternative win rule; **fog options** (hide enemy army counts beyond one border, or "scout" counts as ranges); **teams** (2v2 and 3v3, shared win, no attacking teammates; the Hub already has `HubKeys.TEAMS`); drafted deal ("take turns claiming territories"); neutral garrison for 2 seats; defender-chooses-dice option; more maps. |
| **Later** | **Online**: Nearby and Online relay, host-authoritative, hidden hands, asynchronous turn-based (section 3.7); spectators; rating; daily scenario ("beat this fixed position"). |

## 1.4 Explicitly out of scope

Real-time play; free text chat; voice; accounts; leaderboards; purchases and ads; real-world maps; custom map editor (an authoring tool is a v2 idea); AI that learns; Expert level (the model has `CpuLevel.EXPERT` but the Hub deliberately does not offer it until an engine searches deeper, see `HubOptions.difficulty`); any use of another product's names, art, map, card text or wording (1.7).

## 1.5 Accessibility for a dense map on a phone

The map is the risk: 36 polygons on roughly 360 x 640 dp is about 55 x 55 dp per territory at fit-zoom, with sea links and army chips on top. Requirements (each is a gate item, none is optional):

1. **Pinch zoom and pan**: 1x to 4x, double-tap zooms to the tapped region, a "Fit map" button resets. Zoom is a graphics-layer transform (no recomposition per frame). Two-finger gestures only; one finger selects.
2. **Territory list sheet** ("Territory list" button, always visible): a bottom sheet listing every territory with owner, armies and region, sortable by owner or by region, each row 56 dp tall, tap selects exactly like tapping the map. Everything the game asks can be done from the list alone, which is also the **TalkBack path and the one-switch path** (next / select / confirm).
3. **Not colour only**: each seat has a colour AND a fill pattern (solid, stripes, dots, crosshatch, bars, checks) AND its initial letter on the army chip. Colours are the colour-blind-safe Okabe-Ito set. `LocalColorblindMode` strengthens patterns (thicker lines) rather than being a separate mode.
4. **Touch targets**: tap resolves by point-in-polygon, then falls back to the nearest centroid within 24 dp so a 40 dp polygon is still easy to hit; the selected territory enlarges its chip to 48 dp; action buttons are 48 dp minimum.
5. **TalkBack**: the map is one semantics container with a summary (`tw_a11y_map_fmt`); each territory is a node: "Ashgate, Cinderwake region, owned by Player 2, 5 armies", plus "selected" state and its neighbours on demand; every phase change and battle result is a live-region announcement ("Player 2 conquered Kilnmoor"). Custom actions on a territory node: Select, Attack from here, Fortify from here.
6. **Large fonts**: army chips scale with font scale up to 1.6x and then clamp (the number must always fit); sheets and HUD scroll; the Start button must be reachable in landscape at 1.6x (a known Hub issue in `UNBUILT-ITEMS.md` C, check it first).
7. **Reduced Motion and Game Speed**: dice and move animations respect `LocalReducedMotion` (instant results) and `LocalGameSpeed`; bot turns have a "Skip animations" speed.
8. **Contrast**: ink text on all six seat colours is at least 5.0:1; the slate-blue accent is 5.77:1 on the dark card surface and 7.83:1 on the light one (computed 2026-11-07); ivory text on ink is 16.6:1.
9. **Hand-over curtain** in Pass and Play: a full-screen "Pass the device to X / I am ready" between human turns hides cards and the map's army counts from the previous player's view.

## 1.6 Look and the five UI states

Theme: dark **Midnight Club**. Ink `#0B0D1A` page and `#141829` raised surfaces; ivory text `#F2EEDF`; saffron `#FFB547` for primary actions, the active seat ring and highlights; **slate-blue accent** `#7F95CC` (dark) / `#3B4D8A` (light) for map furniture: borders, sea links, selection halos, region outlines. The sea is ink; territories are filled with the owner's colour at 85% over a darkened base so the board stays dark; region borders are drawn 2x thicker than territory borders; armies are round chips (ink ring, ivory number, owner initial in the corner).

The five states:

1. **Setup**: the Hub for `territory-wars` (mode, seats, War length, Fortify reach, Starting handicap, Auto-place, level, who first); in Pass and Play the Ready screen with names. Nothing is rolled yet.
2. **Start**: "Dealing territories" (a 1.2 s deal sweep, instant under Reduced Motion), then the opening placement HUD: banner "Place your armies", pool counter, tap territories to add, "Place for me" button. Blitz and Auto-place skip straight to mid-game.
3. **Mid-game**: the map fills the screen; top HUD: round `Round 4 of 18`, seat strip (colour + pattern + letter + territory count + army total + card count, active seat ringed in saffron); bottom action bar changes by phase (Reinforce: pool counter and "Turn in set"; Attack: from/to prompts, "Attack (n dice)", "Keep attacking", "End attack"; Move in: stepper; Fortify: from/to/stepper, "Skip fortify"); a collapsible log of the last 5 events; Territory list button; Cards button.
4. **Post-game**: winner banner (`%1$s wins the war!` plus the reason line: total conquest, capture target or points), a 2 s victory flourish, Play again, War summary, Leave.
5. **Score card**: the war summary: one row per seat (territories, armies, cards, eliminated in round N), rounds, battles, conquests, and the seed (so a war can be replayed or shared as text). Reachable from post-game and from the menu while playing.

## 1.7 Legal and originality

HARD RULE: original names, art, map and card text only. How this design stays clear:

- **Map**: an invented world, "Maravel": 36 territories (not the reference genre's 42) in 6 regions with invented names, authored here as a jittered lattice of quads plus three sea links. Not a real-world projection; no territory is positioned or shaped to echo a known board.
- **Names**: every territory, region, card symbol and phrase is original. The unit test `noTerritoryNameIsOnTheTrademarkBlocklist` compares against a blocklist kept only in the test source (the reference games' territory, continent and card names, plus the words of the relevant brands), so the list itself never ships.
- **Mechanics** are generic and numerically our own: base reinforcement `max(3, territories / 3)` (the genre staple) but our own region bonuses (2, 3, 3, 3, 4, 5), our own set values (3, 5, 7, 9, 12, 15, 18, then +4), our own symbols (Torch, Compass, Anchor, Wild) and deck size (38), our own starting armies, **no pictured-territory bonus on cards** (considered and rejected: it is the signature rule of the reference game), plus our own win rules (capture target, round cap, stalemate guard).
- **Text and art**: all strings written fresh; board, chips, cards and dice drawn in code (vector polygons, Compose canvas); no imported art.
- **Process**: the owner skims the 36 + 6 names and the rules text once before release (open question Q9). If any doubt, rename: ids are content and frozen after release, display names live in strings (`tw_terr_*`) and can change without a save migration.

## 1.8 Dependencies

Existing (read, not changed): `core.GameHub` (`GameHubSpec`, `HubOptions`, `HubMode`), `core.CpuLevel`, `GameModule` / `ResumableGame` from `shared`, `SavedGameStore` via `GameSessionManager`, `LocalOutcomes` / stats from `GameResult`, `uikit.GameChrome`, `LocalReducedMotion`, `LocalGameSpeed`, `LocalColorblindMode`, `ui.Die3DView` (dice, as Slide and Climb uses it), `Haptics`, procedural SFX, kotlinx-serialization, coroutines.

New shared-file touches, all deferred to the coordinator (section 4.2): `GameHubSpecs.baseSpecs()`, `GameCatalog` glyph, `GameTheme` accent, `MainActivity` route, `GameTips`, `GameCoach`.

Not needed: no new libraries, no network in the MVP, no new permissions.

## 1.9 Open questions (each with a recommended default)

| # | Question | Recommended default |
|---|---|---|
| Q1 | Blitz with 5 or 6 seats cannot use an 18-territory half map. | Play the **full 36 map with Blitz caps** (capture 24, 10 rounds, auto-place). Alternative: cap Blitz at 4 seats in the Hub (needs a per-option seat clamp the Hub model lacks). |
| Q2 | Does the defender choose 1 or 2 dice? | **No: defender always rolls the maximum** (less UI, faster, standard). Option in v1. |
| Q3 | 2-seat games: add a neutral garrison? | **No** in MVP (18 + 18 split, 30 armies each); v1 option. |
| Q4 | ADULTS blocks kid profiles from seeing it. Is that intended for 10 to 12? | **Keep ADULTS** per the brief; revisit after a family playtest. |
| Q5 | Win rule defaults. | Blitz and Standard **capture target** (12 of 18, 26 of 36) with a round cap; Long **total conquest**. |
| Q6 | Hand-over curtain in Pass and Play. | **On**, not optional (hidden cards are the point). |
| Q7 | Undo? | Undo **placements only** (before the first attack of the turn); never after any dice (chance, and it would allow re-rolls). |
| Q8 | Quick match online (later). | Allow only the `"std"` option set; 2 to 4 seats first; 5 to 6 later. |
| Q9 | Trademark review of names and text. | Owner skims once; blocklist test runs in CI. |
| Q10 | Localise the invented proper nouns? | **Keep identical in ES** (proper nouns); strings exist so they can change later. |
| Q11 | Mission win rule (v1): secret or public? | **Secret**, revealed at the end, with a "peek" button in Pass and Play disabled. |
| Q12 | Stalemate guard threshold. | **5 rounds** with no territory changing hands ends the war on points; confirm with the simulation (3.4). |

---

# 2. SCRIPT

## 2.1 The rules, numbered

Terms: a **seat** is a player (person or computer). A **round** is one turn of every living seat in seat order. "Own" means held by the seat on turn.

### Setup

1. **Seats.** 2 to 6 seats. Turn order is seat order 0..n-1. The Hub's "who moves first" picks the first seat; order then continues round the table.
2. **Map and rules.** The war's `TerritoryWarsConfig` (length, map, fortify reach, auto-place, handicap, win rule, capture target, round cap, seed) is fixed at the start and saved. Map: Standard and Long use `maravel-36`. Blitz with up to 4 seats uses a half map (`maravel-west-18` for an even seed, `maravel-east-18` for an odd one); Blitz with 5 or 6 seats uses the full map (Q1).
3. **Deal.** Shuffle the territories with the seeded deal stream and deal them one at a time round the table starting from a seat chosen by the same stream. Seats differ by at most one territory (36 / 5 gives one seat 8). Every dealt territory holds 1 army.
4. **Starting armies.** Each seat has a fixed total, territories included:

   | Map | 2 seats | 3 | 4 | 5 | 6 |
   |---|---|---|---|---|---|
   | 36 territories | 30 | 26 | 22 | 18 | 16 |
   | 18 territories (Blitz) | 16 | 14 | 12 | - | - |

   The seat's **opening pool** is its total minus the territories it was dealt, plus its **handicap** (+6 for a seat the handicap names, section 2.7).
5. **Opening placement.** In rounds, seat by seat, each seat places up to **3** armies from its pool (any split over its own territories); a seat with an empty pool is skipped; placement ends when every pool is empty. **Auto-place** (always on in Blitz) places every pool at random with the seeded stream instead, and bots always place automatically with their level's placement script.
6. **First turn.** The first seat begins its turn in step 7 with round = 1.

### A turn

7. **Reinforce.** At the start of its turn a seat receives `max(3, territories / 3)` (integer division) plus the bonus of every region it holds completely. Table: Cinderwake 2, Glasswater 3, Mistwood 3, Stormcrown 3, Dunescar 4, Heartmark 5. Any set turned in this turn (rules 15 and 16) adds its value. The seat places all of them, in any split, on its own territories before it may attack. (A Blitz half map pays only the regions it contains.)
8. **Attack legality.** An attack names an own territory (the source) with at least **2** armies and an adjacent territory held by another seat (the target). Adjacent means sharing a border or a sea link. Attacks are optional; a seat may attack any number of times.
9. **Dice.** The attacker chooses **1 to 3** dice but at most `source armies - 1` (one army must stay home). The defender rolls `min(2, target armies)` dice, automatically.
10. **Comparing.** Sort each side's dice high to low. Compare pairwise, highest with highest, second with second, for `min(attacker dice, defender dice)` pairs. For each pair the **higher die wins and the loser removes one army**. **Ties go to the defender.** (Reference odds, section 3.4.)
11. **Repeating.** The attacker may roll again from the same source against the same target, or choose a new attack, or stop. **Attack until done** repeats with the maximum dice until the target falls or the source is down to 1 army.
12. **Conquest and move-in.** When the target has 0 armies the attacker takes it and **must move in** at least as many armies as the dice rolled in the conquering round, and at most `source armies - 1`. The attacker may move in more. No other action is possible until the move-in is made. If this was the seat's first conquest this turn, it will draw a card at the end of the turn (rule 13).
13. **Drawing a card.** At the end of its turn a seat that conquered **at least one** territory this turn draws **exactly one** card from the top of the deck (if the deck is empty, shuffle the discard pile into a new deck first).
14. **The deck.** 36 territory cards (one per territory, symbol Torch, Compass or Anchor, 12 of each) plus **2 Wild** cards = 38 (section 2.4). A Blitz half map uses its 18 territory cards plus 1 Wild. Cards are hidden information.
15. **Sets.** Three distinct cards form a set if they are all one symbol, or one of each of the three symbols, or contain a Wild (a Wild stands for any single symbol, so Wild + any two cards is a set). A seat may turn in **one set at a time** at the start of its turn (before placing) for armies; it may repeat.
16. **Set value.** The war has one shared counter of sets turned in. The next set is worth **3, 5, 7, 9, 12, 15, 18** armies for the 1st to 7th set, and then each further set is worth **4 more than the previous** (22, 26, 30...). Turned-in cards go to the discard pile.
17. **Forced turn-in.** A seat that starts its turn holding **5 or more** cards must turn in sets until it holds **4 or fewer**, before placing (any 5 cards contain a set, so this is always possible). A seat that takes cards by elimination (rule 18) and now holds 5 or more must do the same immediately, then continues its attack.
18. **Elimination.** A seat that loses its last territory is eliminated: it takes no more turns, and the seat that conquered its last territory **takes all of its cards** (rule 17 may then force a turn-in). An eliminated seat's remaining state is dropped.
19. **Fortify.** After attacking, a seat may make **one** fortify move: move any number of armies from one own territory to another own territory, leaving at least 1 behind. Reach is the Hub's "Fortify reach": **connected** (default) allows any chain of own territories (sea links included), **adjacent** allows only a neighbour. Fortify may be skipped.
20. **End of turn.** Fortify (or skipping it) ends the turn: rule 13 card draw, then win checks (rules 21 to 24), then the turn passes to the next living seat; when the turn passes from the last living seat back to the first, the round counter increases.

### Winning

21. **Total conquest.** If only one seat is left, it wins ("By total conquest").
22. **Capture target.** In Blitz and Standard a seat wins **the moment** a conquest brings it to the capture target (12 of 18, 26 of 36, 24 of 36 for Blitz with 5 or 6 seats). Long has no capture target.
23. **Round cap.** When the last living seat finishes the last round of the cap (Blitz 10, Standard 18, Long 40), the war ends on points. Tie-break chain: most territories, then most armies, then most cards held, then the tied seats **share the win**.
24. **Stalemate guard.** If **5 consecutive rounds** pass in which no territory changed hands, the war ends on points as in rule 23 (reason "Stalemate").

## 2.2 The turn state machine

States are `TurnPhase`: `DEAL`, `PLACE_INITIAL`, `CARDS_FORCED`, `REINFORCE`, `ATTACK`, `MOVE_IN`, `FORTIFY`, `GAME_OVER`. One action is applied at a time by the engine; every transition is a pure function of (state, action, dice).

| From | Action (accepted) | To | Notes |
|---|---|---|---|
| `DEAL` | (engine, at `newGame`) | `PLACE_INITIAL`, or `REINFORCE` when Auto-place | Event `dealt` |
| `PLACE_INITIAL` | `PlaceArmies`, `AutoPlaceRemaining` | `PLACE_INITIAL` until all pools empty, then `REINFORCE` (first seat) | Rule 5; event `placed` |
| turn start | (engine) | `CARDS_FORCED` if hand >= 5, else `REINFORCE` | Rule 17; event `turn_started` |
| `CARDS_FORCED` | `TurnInCards` | `CARDS_FORCED` while hand >= 5, else `REINFORCE` | Event `set_turned_in`; value added to pool |
| `REINFORCE` | `TurnInCards` | `REINFORCE` | Optional sets (rule 15) |
| `REINFORCE` | `PlaceArmies`, `AutoPlaceRemaining` | `REINFORCE`; pool 0 enables `Attack` / `EndAttack` | Rejected `PLACE_FIRST` for an attack with armies unplaced |
| `REINFORCE` (pool 0) | first `Attack` / `AttackUntilDone` | `ATTACK` | |
| `ATTACK` | `Attack`, `AttackUntilDone` | `ATTACK`, or `MOVE_IN` on conquest, or `CARDS_FORCED` after an elimination capture leaves hand >= 5 (resume `ATTACK` after) | Events `battle`, `conquered`, `region_completed`, `eliminated`; win check (rules 21, 22) may go to `GAME_OVER` |
| `MOVE_IN` | `MoveIn(count)` | `ATTACK` | Event `moved_in` |
| `ATTACK` / `REINFORCE` (pool 0) | `EndAttack` | `FORTIFY` | |
| `FORTIFY` | `Fortify`, `SkipFortify` | next seat's turn start (or `GAME_OVER`) | Event `fortified`, then `card_drawn`, `turn_started` for the next seat |
| any | rule 23 or 24 or 21 satisfied | `GAME_OVER` | Event `war_won` |

Rejections (`RejectReason`, with the "why" string key): `NOT_YOUR_TURN`, `WRONG_PHASE`, `NOT_YOURS`, `OWN_TARGET`, `NOT_ADJACENT`, `NEED_TWO_ARMIES`, `NONE_LEFT`, `PLACE_FIRST`, `TURN_IN_FIRST`, `NOT_A_SET`, `NO_PATH`, `LEAVE_ONE`, `ALREADY_FORTIFIED`, `MOVE_IN_FIRST`, `BAD_COUNT`, `BAD_DICE`, `GAME_OVER`. A rejected action changes nothing.

Event payloads (`TerritoryWarsEvent(kind, seat, from, to, amount, extra)`): `placed` (to, amount); `set_turned_in` (amount = armies earned, extra = set number); `battle` (from, to, amount = attacker losses, extra = defender losses); `conquered` (from, to, amount = dice rolled); `moved_in` (from, to, amount); `region_completed` (extra = bonus); `eliminated` (to = victim seat in `amount`); `fortified` (from, to, amount); `card_drawn`; `turn_started` (amount = reinforcement); `war_won`.

## 2.3 Dice, chance and replay

All chance is injected: battles draw from a `DiceSource`; the deal and deck shuffle draw from streams derived from the config seed. The three streams (deal, deck, dice) are independent (`DICE_STREAM_SALT`), so a bot that thinks longer never changes the dice. A saved game stores the seed and the number of dice drawn so a restore fast-forwards the stream.

## 2.4 Content tables

### Regions and bonuses

| Region id | Name | Territories | Bonus | Border territories | Outside neighbours |
|---|---|---|---|---|---|
| `cinderwake` | Cinderwake (north-west) | 6 | 2 | 4 | 5 |
| `glasswater` | Glasswater (north-east) | 6 | 3 | 5 | 6 |
| `heartmark` | Heartmark (centre) | 6 | 5 | 5 | 9 |
| `dunescar` | Dunescar (east) | 6 | 4 | 5 | 8 |
| `mistwood` | Mistwood (south-west) | 6 | 3 | 5 | 7 |
| `stormcrown` | Stormcrown (south-east) | 6 | 3 | 5 | 6 |

Bonus rule of thumb used to set them: 2 + (outside neighbours - 5) / 1.5, rounded (the more neighbours to defend, the more it pays). The simulation (3.4) may retune; the numbers live in `TerritoryWarsMaps.REGIONS`. The Blitz halves are West = Cinderwake + Heartmark + Mistwood, East = Glasswater + Dunescar + Stormcrown.

### Card deck (38 cards)

Symbols cycle Torch, Compass, Anchor over each region's territories in table order, so every region holds two of each symbol; overall 12 Torch, 12 Compass, 12 Anchor, plus `card-wild-1` and `card-wild-2` (a Blitz half map: its 18 territory cards and `card-wild-1`).

| Card id | Symbol | Territory |
|---|---|---|
| `card-ashgate` | Torch | Ashgate |
| `card-kilnmoor` | Compass | Kilnmoor |
| `card-cinder-ridge` | Anchor | Cinder Ridge |
| `card-furnace-vale` | Torch | Furnace Vale |
| `card-smolder-bay` | Compass | Smolder Bay |
| `card-charwick` | Anchor | Charwick |
| `card-frostmere` | Torch | Frostmere |
| `card-mirrorlake` | Compass | Mirrorlake |
| `card-saltspire` | Anchor | Saltspire |
| `card-tidewhistle` | Torch | Tidewhistle |
| `card-kelp-harbour` | Compass | Kelp Harbour |
| `card-brinehollow` | Anchor | Brinehollow |
| `card-crownfield` | Torch | Crownfield |
| `card-millstead` | Compass | Millstead |
| `card-goldhaven` | Anchor | Goldhaven |
| `card-rivermeet` | Torch | Rivermeet |
| `card-thornwick` | Compass | Thornwick |
| `card-barleyford` | Anchor | Barleyford |
| `card-sunspire` | Torch | Sunspire |
| `card-duskreach` | Compass | Duskreach |
| `card-sandpass` | Anchor | Sandpass |
| `card-mirage-flats` | Torch | Mirage Flats |
| `card-oasis-gate` | Compass | Oasis Gate |
| `card-scorchwind` | Anchor | Scorchwind |
| `card-fernhollow` | Torch | Fernhollow |
| `card-mossgate` | Compass | Mossgate |
| `card-hushpine` | Anchor | Hushpine |
| `card-lanternfen` | Torch | Lanternfen |
| `card-owlmere` | Compass | Owlmere |
| `card-dewcrest` | Anchor | Dewcrest |
| `card-gale-point` | Torch | Gale Point |
| `card-thunderlip` | Compass | Thunderlip |
| `card-coral-sound` | Anchor | Coral Sound |
| `card-reefhaven` | Torch | Reefhaven |
| `card-squallrock` | Compass | Squallrock |
| `card-pearl-strand` | Anchor | Pearl Strand |
| `card-wild-1` | Wild | - |
| `card-wild-2` | Wild | - |

### Set values

| Sets already turned in | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | n >= 6 |
|---|---|---|---|---|---|---|---|---|---|---|
| Next set pays | 3 | 5 | 7 | 9 | 12 | 15 | 18 | 22 | 26 | 18 + 4 (n - 6) |

### Starting armies and handicap

See rule 4; handicap +6 (section 2.7). Reinforcement formula: rule 7.

### Missions (v1, original text)

Reserved: 12 mission cards ("Hold 3 whole regions", "Hold 20 territories with 2+ armies each", "Eliminate the seat on your left, or hold 24 territories if they fall first", "Hold Heartmark and any two other regions" ...). Written in the v1 phase; not in the MVP.

## 2.5 The map as a machine-readable spec

Canvas 1200 x 780 map units. The lattice is 7 x 7 vertices (row-major, index = row * 7 + col); a territory polygon is its four lattice vertex indices (top-left, top-right, bottom-right, bottom-left); the centroid is the vertex average, used for the army chip, label and sea-link endpoints. Sea links (also listed as normal neighbours): Ashgate - Saltspire "Northern Strait", Fernhollow - Oasis Gate "Middle Sound", Owlmere - Pearl Strand "Southern Passage". The spec below is exactly `TerritoryWarsMaps.STANDARD` (the Kotlin was generated from the same data and is frozen).

### Territory table

| # | id | Name | Region | Neighbours (ids) | Centroid (x,y) |
|---|----|------|--------|------------------|----------------|
| 1 | `ashgate` | Ashgate | cinderwake | kilnmoor, saltspire, furnace-vale | 131,96 |
| 2 | `kilnmoor` | Kilnmoor | cinderwake | ashgate, cinder-ridge, smolder-bay | 330,95 |
| 3 | `cinder-ridge` | Cinder Ridge | cinderwake | kilnmoor, frostmere, crownfield | 515,94 |
| 4 | `frostmere` | Frostmere | glasswater | cinder-ridge, mirrorlake, tidewhistle | 693,93 |
| 5 | `mirrorlake` | Mirrorlake | glasswater | frostmere, saltspire, kelp-harbour | 876,94 |
| 6 | `saltspire` | Saltspire | glasswater | ashgate, mirrorlake, brinehollow | 1069,98 |
| 7 | `furnace-vale` | Furnace Vale | cinderwake | ashgate, smolder-bay, charwick | 132,208 |
| 8 | `smolder-bay` | Smolder Bay | cinderwake | kilnmoor, furnace-vale, crownfield, millstead | 328,213 |
| 9 | `crownfield` | Crownfield | heartmark | cinder-ridge, smolder-bay, tidewhistle, goldhaven | 511,218 |
| 10 | `tidewhistle` | Tidewhistle | glasswater | frostmere, crownfield, kelp-harbour, rivermeet | 692,218 |
| 11 | `kelp-harbour` | Kelp Harbour | glasswater | mirrorlake, tidewhistle, brinehollow, sunspire | 886,214 |
| 12 | `brinehollow` | Brinehollow | glasswater | saltspire, kelp-harbour, duskreach | 1079,216 |
| 13 | `charwick` | Charwick | cinderwake | furnace-vale, millstead, fernhollow | 121,325 |
| 14 | `millstead` | Millstead | heartmark | smolder-bay, charwick, goldhaven, thornwick | 309,330 |
| 15 | `goldhaven` | Goldhaven | heartmark | crownfield, millstead, rivermeet, barleyford | 500,332 |
| 16 | `rivermeet` | Rivermeet | heartmark | tidewhistle, goldhaven, sunspire, sandpass | 686,336 |
| 17 | `sunspire` | Sunspire | dunescar | kelp-harbour, rivermeet, duskreach, mirage-flats | 880,325 |
| 18 | `duskreach` | Duskreach | dunescar | brinehollow, sunspire, oasis-gate | 1076,328 |
| 19 | `fernhollow` | Fernhollow | mistwood | charwick, thornwick, oasis-gate, mossgate | 124,452 |
| 20 | `thornwick` | Thornwick | heartmark | millstead, fernhollow, barleyford, hushpine | 312,450 |
| 21 | `barleyford` | Barleyford | heartmark | goldhaven, thornwick, sandpass, lanternfen | 505,442 |
| 22 | `sandpass` | Sandpass | dunescar | rivermeet, barleyford, mirage-flats, gale-point | 697,445 |
| 23 | `mirage-flats` | Mirage Flats | dunescar | sunspire, sandpass, oasis-gate, scorchwind | 880,442 |
| 24 | `oasis-gate` | Oasis Gate | dunescar | duskreach, fernhollow, mirage-flats, thunderlip | 1069,448 |
| 25 | `mossgate` | Mossgate | mistwood | fernhollow, hushpine, owlmere | 122,567 |
| 26 | `hushpine` | Hushpine | mistwood | thornwick, mossgate, lanternfen, dewcrest | 310,563 |
| 27 | `lanternfen` | Lanternfen | mistwood | barleyford, hushpine, gale-point, coral-sound | 506,560 |
| 28 | `gale-point` | Gale Point | stormcrown | sandpass, lanternfen, scorchwind, reefhaven | 700,567 |
| 29 | `scorchwind` | Scorchwind | dunescar | mirage-flats, gale-point, thunderlip, squallrock | 882,573 |
| 30 | `thunderlip` | Thunderlip | stormcrown | oasis-gate, scorchwind, pearl-strand | 1070,576 |
| 31 | `owlmere` | Owlmere | mistwood | mossgate, dewcrest, pearl-strand | 123,688 |
| 32 | `dewcrest` | Dewcrest | mistwood | hushpine, owlmere, coral-sound | 312,684 |
| 33 | `coral-sound` | Coral Sound | stormcrown | lanternfen, dewcrest, reefhaven | 503,686 |
| 34 | `reefhaven` | Reefhaven | stormcrown | gale-point, coral-sound, squallrock | 699,692 |
| 35 | `squallrock` | Squallrock | stormcrown | scorchwind, reefhaven, pearl-strand | 888,696 |
| 36 | `pearl-strand` | Pearl Strand | stormcrown | thunderlip, owlmere, squallrock | 1073,697 |

### JSON

```json
{
  "mapId": "maravel-36", "width": 1200, "height": 780,
  "vertices": [[30,30],[219,30],[433,30],[594,30],[768,30],[969,30],[1170,30],[30,160],[246,163],[422,157],[611,160],[799,151],[968,165],[1170,165],[30,254],[223,254],[422,279],[588,278],[770,282],[1007,257],[1170,278],[30,387],[201,406],[391,379],[597,394],[787,388],[958,374],[1170,401],[30,491],[233,525],[423,492],[609,502],[796,497],[977,510],[1170,507],[30,635],[195,616],[389,618],[602,628],[794,641],[962,643],[1170,646],[30,750],[237,750],[429,750],[592,750],[807,750],[990,750],[1170,750]],
  "territories": [
    {"id":"ashgate","name":"Ashgate","region":"cinderwake","centroid":[131,96],"polygon":[0,1,8,7],"neighbours":["kilnmoor","saltspire","furnace-vale"]},
    {"id":"kilnmoor","name":"Kilnmoor","region":"cinderwake","centroid":[330,95],"polygon":[1,2,9,8],"neighbours":["ashgate","cinder-ridge","smolder-bay"]},
    {"id":"cinder-ridge","name":"Cinder Ridge","region":"cinderwake","centroid":[515,94],"polygon":[2,3,10,9],"neighbours":["kilnmoor","frostmere","crownfield"]},
    {"id":"frostmere","name":"Frostmere","region":"glasswater","centroid":[693,93],"polygon":[3,4,11,10],"neighbours":["cinder-ridge","mirrorlake","tidewhistle"]},
    {"id":"mirrorlake","name":"Mirrorlake","region":"glasswater","centroid":[876,94],"polygon":[4,5,12,11],"neighbours":["frostmere","saltspire","kelp-harbour"]},
    {"id":"saltspire","name":"Saltspire","region":"glasswater","centroid":[1069,98],"polygon":[5,6,13,12],"neighbours":["ashgate","mirrorlake","brinehollow"]},
    {"id":"furnace-vale","name":"Furnace Vale","region":"cinderwake","centroid":[132,208],"polygon":[7,8,15,14],"neighbours":["ashgate","smolder-bay","charwick"]},
    {"id":"smolder-bay","name":"Smolder Bay","region":"cinderwake","centroid":[328,213],"polygon":[8,9,16,15],"neighbours":["kilnmoor","furnace-vale","crownfield","millstead"]},
    {"id":"crownfield","name":"Crownfield","region":"heartmark","centroid":[511,218],"polygon":[9,10,17,16],"neighbours":["cinder-ridge","smolder-bay","tidewhistle","goldhaven"]},
    {"id":"tidewhistle","name":"Tidewhistle","region":"glasswater","centroid":[692,218],"polygon":[10,11,18,17],"neighbours":["frostmere","crownfield","kelp-harbour","rivermeet"]},
    {"id":"kelp-harbour","name":"Kelp Harbour","region":"glasswater","centroid":[886,214],"polygon":[11,12,19,18],"neighbours":["mirrorlake","tidewhistle","brinehollow","sunspire"]},
    {"id":"brinehollow","name":"Brinehollow","region":"glasswater","centroid":[1079,216],"polygon":[12,13,20,19],"neighbours":["saltspire","kelp-harbour","duskreach"]},
    {"id":"charwick","name":"Charwick","region":"cinderwake","centroid":[121,325],"polygon":[14,15,22,21],"neighbours":["furnace-vale","millstead","fernhollow"]},
    {"id":"millstead","name":"Millstead","region":"heartmark","centroid":[309,330],"polygon":[15,16,23,22],"neighbours":["smolder-bay","charwick","goldhaven","thornwick"]},
    {"id":"goldhaven","name":"Goldhaven","region":"heartmark","centroid":[500,332],"polygon":[16,17,24,23],"neighbours":["crownfield","millstead","rivermeet","barleyford"]},
    {"id":"rivermeet","name":"Rivermeet","region":"heartmark","centroid":[686,336],"polygon":[17,18,25,24],"neighbours":["tidewhistle","goldhaven","sunspire","sandpass"]},
    {"id":"sunspire","name":"Sunspire","region":"dunescar","centroid":[880,325],"polygon":[18,19,26,25],"neighbours":["kelp-harbour","rivermeet","duskreach","mirage-flats"]},
    {"id":"duskreach","name":"Duskreach","region":"dunescar","centroid":[1076,328],"polygon":[19,20,27,26],"neighbours":["brinehollow","sunspire","oasis-gate"]},
    {"id":"fernhollow","name":"Fernhollow","region":"mistwood","centroid":[124,452],"polygon":[21,22,29,28],"neighbours":["charwick","thornwick","oasis-gate","mossgate"]},
    {"id":"thornwick","name":"Thornwick","region":"heartmark","centroid":[312,450],"polygon":[22,23,30,29],"neighbours":["millstead","fernhollow","barleyford","hushpine"]},
    {"id":"barleyford","name":"Barleyford","region":"heartmark","centroid":[505,442],"polygon":[23,24,31,30],"neighbours":["goldhaven","thornwick","sandpass","lanternfen"]},
    {"id":"sandpass","name":"Sandpass","region":"dunescar","centroid":[697,445],"polygon":[24,25,32,31],"neighbours":["rivermeet","barleyford","mirage-flats","gale-point"]},
    {"id":"mirage-flats","name":"Mirage Flats","region":"dunescar","centroid":[880,442],"polygon":[25,26,33,32],"neighbours":["sunspire","sandpass","oasis-gate","scorchwind"]},
    {"id":"oasis-gate","name":"Oasis Gate","region":"dunescar","centroid":[1069,448],"polygon":[26,27,34,33],"neighbours":["duskreach","fernhollow","mirage-flats","thunderlip"]},
    {"id":"mossgate","name":"Mossgate","region":"mistwood","centroid":[122,567],"polygon":[28,29,36,35],"neighbours":["fernhollow","hushpine","owlmere"]},
    {"id":"hushpine","name":"Hushpine","region":"mistwood","centroid":[310,563],"polygon":[29,30,37,36],"neighbours":["thornwick","mossgate","lanternfen","dewcrest"]},
    {"id":"lanternfen","name":"Lanternfen","region":"mistwood","centroid":[506,560],"polygon":[30,31,38,37],"neighbours":["barleyford","hushpine","gale-point","coral-sound"]},
    {"id":"gale-point","name":"Gale Point","region":"stormcrown","centroid":[700,567],"polygon":[31,32,39,38],"neighbours":["sandpass","lanternfen","scorchwind","reefhaven"]},
    {"id":"scorchwind","name":"Scorchwind","region":"dunescar","centroid":[882,573],"polygon":[32,33,40,39],"neighbours":["mirage-flats","gale-point","thunderlip","squallrock"]},
    {"id":"thunderlip","name":"Thunderlip","region":"stormcrown","centroid":[1070,576],"polygon":[33,34,41,40],"neighbours":["oasis-gate","scorchwind","pearl-strand"]},
    {"id":"owlmere","name":"Owlmere","region":"mistwood","centroid":[123,688],"polygon":[35,36,43,42],"neighbours":["mossgate","dewcrest","pearl-strand"]},
    {"id":"dewcrest","name":"Dewcrest","region":"mistwood","centroid":[312,684],"polygon":[36,37,44,43],"neighbours":["hushpine","owlmere","coral-sound"]},
    {"id":"coral-sound","name":"Coral Sound","region":"stormcrown","centroid":[503,686],"polygon":[37,38,45,44],"neighbours":["lanternfen","dewcrest","reefhaven"]},
    {"id":"reefhaven","name":"Reefhaven","region":"stormcrown","centroid":[699,692],"polygon":[38,39,46,45],"neighbours":["gale-point","coral-sound","squallrock"]},
    {"id":"squallrock","name":"Squallrock","region":"stormcrown","centroid":[888,696],"polygon":[39,40,47,46],"neighbours":["scorchwind","reefhaven","pearl-strand"]},
    {"id":"pearl-strand","name":"Pearl Strand","region":"stormcrown","centroid":[1073,697],"polygon":[40,41,48,47],"neighbours":["thunderlip","owlmere","squallrock"]}
  ],
  "regions": [
    {"id":"cinderwake","name":"Cinderwake","bonus":2,"territories":["ashgate","kilnmoor","cinder-ridge","furnace-vale","smolder-bay","charwick"]},
    {"id":"glasswater","name":"Glasswater","bonus":3,"territories":["frostmere","mirrorlake","saltspire","tidewhistle","kelp-harbour","brinehollow"]},
    {"id":"heartmark","name":"Heartmark","bonus":5,"territories":["crownfield","millstead","goldhaven","rivermeet","thornwick","barleyford"]},
    {"id":"dunescar","name":"Dunescar","bonus":4,"territories":["sunspire","duskreach","sandpass","mirage-flats","oasis-gate","scorchwind"]},
    {"id":"mistwood","name":"Mistwood","bonus":3,"territories":["fernhollow","mossgate","hushpine","lanternfen","owlmere","dewcrest"]},
    {"id":"stormcrown","name":"Stormcrown","bonus":3,"territories":["gale-point","thunderlip","coral-sound","reefhaven","squallrock","pearl-strand"]}
  ],
  "seaLinks": [["ashgate","saltspire","Northern Strait"],["fernhollow","oasis-gate","Middle Sound"],["owlmere","pearl-strand","Southern Passage"]]
}
```

Validation (unit test `mapProblemsIsEmptyForAllThreeMaps`): ids unique, neighbours symmetric, no self borders, each region 6 connected territories, regions partition the map, centroids inside polygon bounds, every polygon 4+ points.

## 2.6 Bot behaviour scripts

All bots implement `TerritoryWarsBot.chooseAction(state, level, budget, random)`: **pure, deterministic for a given state and `random`, run off the main thread, honour the budget and cancellation**, return the next single action or null. The game calls it repeatedly through a turn; every decision is re-derived from the state (bots keep no memory, so resume mid-turn is free).

### Shared helpers

- `P(a, d)`: probability the attacker conquers when it has `a` attacking armies (source armies - 1) against `d` defenders, always rolling maximum dice. Exact DP over single rounds using the section 3.4 round table; precomputed once per process for `a, d` in 0..60 (3,721 entries, a few ms). `L(a, d)`: expected attacker armies lost in that attack, from the same DP.
- Border territory: an own territory with at least one enemy neighbour. Interior: none.
- `Threat(t)` = sum of armies of enemy neighbours of `t`.
- `value(target)` (Medium and Hard) = `1 + 2 * completesRegion + 0.6 * blocksRegion + 3 * eliminates`, where `completesRegion` is 1 if taking it gives the bot all of the region, `blocksRegion` 1 if the current holder owns every other territory of that region, `eliminates` 1 if it is the holder's last territory. (Hard scales these by region bonus, below.)
- Move-in: move `max` if the source has no enemy neighbours left afterwards, otherwise split the stack in proportion to the two territories' threats, clamped to the legal range.

### Easy: random legal

- Cards: turn in only when forced (first valid triple in hand order).
- Reinforce: place armies one at a time, each on a uniformly random own border territory (any own territory if there is none).
- Attack: at most **3** attack decisions per turn; before each, continue with probability **0.7**. Candidates: every legal (source with >= 3 armies, adjacent enemy); pick **uniformly**. Use maximum dice; with probability 0.5 use Attack until done, else one round. Move-in: the minimum.
- Fortify: with probability **0.25** move half the armies of a random interior territory with >= 2 armies to a random border territory it can reach; otherwise skip.
- Budget: compute <= **20 ms** per decision; visible delay **450 ms** (`CpuLevel.EASY.minThinkMillis`; placements batch at 150 ms each). Never plays the 5-card hold deliberately; never counts odds, so it will make 1-in-4 attacks.

### Medium: greedy expected value

- Cards: turn in as soon as a set exists, preferring sets without a Wild.
- Reinforce: place one army at a time on the own border territory with the best **marginal gain** `gain(t) = value(e*) * (P(a_t + 1, d_e*) - P(a_t, d_e*))`, where `e*` is the best adjacent enemy target of `t` by `value(e) * P(a_t, d_e)`. Repeat until the pool is empty (<= 60 iterations).
- Attack: candidates are legal pairs with source >= 3 armies. `score = P * value(target) - 0.08 * L(a, d)`; accept the best-scoring candidate if `P >= 0.60`, or if it completes a region and `P >= 0.45`. Use Attack until done. Stop when no candidate qualifies or after **12** attack decisions. If no conquest yet this turn, one extra attack with `P >= 0.5` on the weakest adjacent enemy is allowed (card insurance).
- Fortify: if there is an interior own territory with >= 2 armies, move `armies - 1` to the reachable border territory with the highest `Threat`; else skip.
- Budget: <= **80 ms** per decision; visible delay **600 ms**.

### Hard: region-focused with threat scoring

Stateless; recomputed at every decision:

1. **Target region.** For each region `r`: `RV(r) = bonus(r) / (1 + E(r))`, `E(r)` = enemy armies in `r` + 0.5 * (enemy territories in `r`); `f(r) = (mine(r) + 1) / (size(r) + 1)`. `R*` = region maximising `RV * f` among regions where the bot owns a territory or borders one (ties: lowest id).
2. **Threat deficit.** For own territory `t`: `w(e) = 1.5` if enemy neighbour `e`'s holder could complete a region by taking `t`, else `1.0`; `T(t) = sum w(e) * armies(e)`; `deficit(t) = 0.6 * T(t) - armies(t)`.
3. **Cards.** Turn in when a set exists and (hand >= 4, or the set value >= 7, or forced); otherwise hold up to 4 cards for the larger escalated value.
4. **Reinforce.** **60%** of the pool (rounded up) goes to one **staging** territory: an own territory in or adjacent to `R*` with the best attack target in `R*` (all on one stack so 3-dice attacks stay available); **40%** one at a time to the border territory with the largest positive `deficit`; if none has a deficit, everything goes to staging.
5. **Attack.** Score `= P * (1 + 0.5 * RVfactor + completes * (2 + bonus / 2) + blocks * (1 + bonus / 3) + eliminates * 3) - 0.12 * L / max(1, a)` where `RVfactor` is 1 if the target is in `R*`, else 0. Evaluate the top **12** pairs by a quick score, then the full score. Accept if `P >= 0.75`, or `P >= 0.55` and (completes or blocks or eliminates), or **elimination boost**: an opponent with <= 3 territories and >= 3 cards is attacked at `P >= 0.50` with `eliminates` weight x 2.5. **Exposure check**: after the expected losses `L`, the source keeps `armies - 1 - L >= 0.5 * Threat(source)` unless the source would have no other enemy neighbour; otherwise skip that candidate. **Card insurance**: if no conquest yet, allow one attack with `P >= 0.55` on the weakest adjacent enemy that passes the exposure check. Stop after **16** decisions or when none qualifies.
6. **Fortify.** One move: from the interior (or lowest-deficit) own territory with a surplus to the border territory with the largest positive deficit, along a connected chain; else skip.
7. Budget: <= **250 ms** per decision, **2.5 s** for a whole turn's compute; visible delay **800 ms** (`CpuLevel.HARD.minThinkMillis`). Cancellation is checked between candidates.

`CpuLevel.EXPERT` plays as Hard until a deeper search exists.

## 2.7 Handicap rule

A seat the handicap names starts with **+6 armies** in its opening pool (so it also places them in the opening placement). Hub presets (vs Computers only): "No handicap", "I start with 6 extra armies" (seat 0), "Computers start with 6 extra armies" (every bot seat). The constant is `TerritoryWarsRules.HANDICAP_ARMIES`. Pass and Play offers none: friends agree their own house handicap. The balance simulation checks a +6 seat's win rate rises by roughly 8 to 15 points in a 4-seat game (target; retune the constant if it is outside).

## 2.8 Tutorial script

A guided first war on `maravel-36` against one Easy bot with scripted dice for the first battle. Each step highlights one thing and waits for the matching tap. Lines (EN, all under 120 characters; keys `tw_tut_01` to `tw_tut_14`, plus `tw_btn_next` and `tw_btn_skip_tutorial`):

1. Welcome, commander. Your goal is to conquer the whole map.
2. The map is split into territories. Each one holds your armies or an enemy's.
3. Every turn has three steps: reinforce, attack, then fortify.
4. You get armies for territories held, plus a bonus for whole regions.
5. Tap one of your territories to place an army there.
6. Now attack: pick a territory with 2 or more armies, then a neighbour.
7. Dice decide each fight. The highest dice are compared and ties go to the defender.
8. If the defender runs out of armies, you take the territory and move in.
9. Conquer at least one territory in a turn to earn a card.
10. Three matching or three different cards make a set. Turn it in for armies.
11. Finish by fortifying: move armies between two connected territories.
12. Pinch the map to zoom, or open the territory list to pick one.
13. Hold every territory of a region to earn its bonus each turn.
14. The last commander standing wins. Good luck!

The tutorial opens through the Club coach card like other games (`rememberClCoach`), runs once, and is replayable from the Hub.

## 2.9 Strings

Files: `app/src/main/res/values/territory_wars_strings.xml` and `values-es/territory_wars_strings.xml` (same keys, real Spanish; the only `TODO translate` is an XML comment about the optional localisation of proper nouns). Placeholders are positional (`%1$s`, `%1$d`) and identical in both languages. Territory and region names are strings too (`tw_terr_<id with underscores>`, `tw_region_<id>`), identical in ES.

| Key | EN | ES |
|-----|----|----|
| `tw_title` | Territory Wars | Guerra de Territorios |
| `tw_phase_deal` | Dealing territories | Repartiendo territorios |
| `tw_phase_place` | Place your armies | Coloca tus ejércitos |
| `tw_phase_cards` | Turn in cards | Canjea cartas |
| `tw_phase_reinforce` | Reinforce | Refuerzo |
| `tw_phase_attack` | Attack | Ataque |
| `tw_phase_move_in` | Move in | Avanzar |
| `tw_phase_fortify` | Fortify | Fortificar |
| `tw_phase_gameover` | The war is over | La guerra terminó |
| `tw_round_cap_fmt` | Round %1$d of %2$d | Ronda %1$d de %2$d |
| `tw_round_nocap_fmt` | Round %1$d | Ronda %1$d |
| `tw_turn_you` | Your turn | Tu turno |
| `tw_turn_other_fmt` | %1$s is playing | Juega %1$s |
| `tw_armies_to_place_fmt` | %1$d armies to place | %1$d ejércitos por colocar |
| `tw_btn_end_attack` | End attack | Terminar ataque |
| `tw_btn_end_turn` | End turn | Terminar turno |
| `tw_btn_skip_fortify` | Skip fortify | Omitir fortificación |
| `tw_btn_attack_one` | Attack (1 die) | Atacar (1 dado) |
| `tw_btn_attack_dice_fmt` | Attack (%1$d dice) | Atacar (%1$d dados) |
| `tw_btn_keep_attacking` | Keep attacking | Seguir atacando |
| `tw_btn_stop` | Stop | Parar |
| `tw_btn_confirm` | Confirm | Confirmar |
| `tw_btn_cancel` | Cancel | Cancelar |
| `tw_btn_undo_placement` | Undo placement | Deshacer colocación |
| `tw_btn_turn_in` | Turn in set | Canjear juego |
| `tw_btn_territory_list` | Territory list | Lista de territorios |
| `tw_btn_fit_map` | Fit map | Ajustar mapa |
| `tw_btn_ready` | I am ready | Estoy listo |
| `tw_btn_play_again` | Play again | Jugar otra vez |
| `tw_btn_score_card` | War summary | Resumen de la guerra |
| `tw_btn_leave` | Leave | Salir |
| `tw_btn_next` | Next | Siguiente |
| `tw_btn_skip_tutorial` | Skip tutorial | Omitir tutorial |
| `tw_btn_start` | Start the war | Empezar la guerra |
| `tw_prompt_place` | Tap one of your territories to add an army | Toca un territorio tuyo para añadir un ejército |
| `tw_prompt_attack_from` | Choose the territory to attack from | Elige el territorio desde el que atacar |
| `tw_prompt_attack_to` | Choose a neighbouring enemy territory | Elige un territorio enemigo vecino |
| `tw_prompt_move_in_fmt` | Move armies into %1$s (at least %2$d) | Mueve ejércitos a %1$s (mínimo %2$d) |
| `tw_prompt_fortify_from` | Choose a territory to move armies from | Elige un territorio de origen |
| `tw_prompt_fortify_to` | Now choose where they go | Ahora elige el destino |
| `tw_prompt_fortify_amount_fmt` | Move %1$d armies | Mover %1$d ejércitos |
| `tw_handover_fmt` | Pass the device to %1$s | Pasa el dispositivo a %1$s |
| `tw_handover_hint` | Cards stay hidden until the next player taps I am ready. | Las cartas siguen ocultas hasta que el siguiente jugador toque Estoy listo. |
| `tw_battle_title` | Battle | Batalla |
| `tw_battle_result_fmt` | Attacker lost %1$d, defender lost %2$d | El atacante perdió %1$d, el defensor perdió %2$d |
| `tw_battle_conquered_fmt` | %1$s conquered %2$s | %1$s conquistó %2$s |
| `tw_battle_held_fmt` | %1$s held %2$s | %1$s defendió %2$s |
| `tw_event_eliminated_fmt` | %1$s has been eliminated | %1$s fue eliminado |
| `tw_event_region_fmt` | %1$s now holds all of %2$s (+%3$d) | %1$s controla toda la región %2$s (+%3$d) |
| `tw_event_reinforce_fmt` | %1$s receives %2$d armies | %1$s recibe %2$d ejércitos |
| `tw_event_reinforce_breakdown_fmt` | Territories %1$d, regions %2$d, cards %3$d | Territorios %1$d, regiones %2$d, cartas %3$d |
| `tw_event_round_cap` | Round limit reached | Se alcanzó el límite de rondas |
| `tw_event_target_fmt` | %1$s reached the capture target | %1$s alcanzó el objetivo de conquista |
| `tw_event_stalemate` | No territory changed hands for five rounds | Ningún territorio cambió de manos en cinco rondas |
| `tw_cards_title` | Your cards | Tus cartas |
| `tw_cards_count_fmt` | %1$d cards | %1$d cartas |
| `tw_card_torch` | Torch | Antorcha |
| `tw_card_compass` | Compass | Brújula |
| `tw_card_anchor` | Anchor | Ancla |
| `tw_card_wild` | Wild | Comodín |
| `tw_card_drawn_fmt` | New card: %1$s | Carta nueva: %1$s |
| `tw_cards_forced` | You hold 5 or more cards: turn in a set to continue | Tienes 5 o más cartas: canjea un juego para continuar |
| `tw_cards_set_value_fmt` | This set is worth %1$d armies | Este juego vale %1$d ejércitos |
| `tw_cards_not_a_set` | These cards do not make a set | Estas cartas no forman un juego |
| `tw_cards_hidden_fmt` | %1$s holds %2$d cards | %1$s tiene %2$d cartas |
| `tw_why_not_your_turn` | It is not your turn | No es tu turno |
| `tw_why_not_yours` | That territory is not yours | Ese territorio no es tuyo |
| `tw_why_own_target` | You cannot attack your own territory | No puedes atacar tu propio territorio |
| `tw_why_not_adjacent` | Those territories do not touch | Esos territorios no son vecinos |
| `tw_why_need_two` | You need at least 2 armies to attack | Necesitas al menos 2 ejércitos para atacar |
| `tw_why_none_left` | No armies left to place | No quedan ejércitos por colocar |
| `tw_why_place_first` | Place all your armies before attacking | Coloca todos tus ejércitos antes de atacar |
| `tw_why_turn_in_first` | Turn in a set of cards before continuing | Canjea un juego de cartas antes de continuar |
| `tw_why_no_path` | No chain of your territories connects them | Ninguna cadena de tus territorios los conecta |
| `tw_why_leave_one` | Leave at least 1 army behind | Deja al menos 1 ejército atrás |
| `tw_why_already_fortified` | You already fortified this turn | Ya fortificaste en este turno |
| `tw_why_move_in_first` | Move armies into the conquered territory first | Primero mueve ejércitos al territorio conquistado |
| `tw_why_over` | The war is over | La guerra terminó |
| `tw_setup_title` | New war | Nueva guerra |
| `tw_setup_blurb` | Territories are dealt at random. Plan your opening. | Los territorios se reparten al azar. Planifica tu apertura. |
| `tw_win_title_fmt` | %1$s wins the war! | ¡%1$s gana la guerra! |
| `tw_win_domination` | By total conquest | Por conquista total |
| `tw_win_target` | By reaching the capture target | Al alcanzar el objetivo de conquista |
| `tw_win_round_cap` | On points when the round limit ended | Por puntos al terminar el límite de rondas |
| `tw_score_title` | War summary | Resumen de la guerra |
| `tw_score_row_fmt` | %1$s: %2$d territories, %3$d armies | %1$s: %2$d territorios, %3$d ejércitos |
| `tw_score_rounds_fmt` | Rounds played: %1$d | Rondas jugadas: %1$d |
| `tw_score_battles_fmt` | Battles fought: %1$d | Batallas libradas: %1$d |
| `tw_score_conquests_fmt` | Territories conquered: %1$d | Territorios conquistados: %1$d |
| `tw_score_seed_fmt` | Seed: %1$s | Semilla: %1$s |
| `tw_a11y_map_fmt` | Map of %1$d territories. Pinch to zoom or open the territory list. | Mapa de %1$d territorios. Pellizca para ampliar o abre la lista de territorios. |
| `tw_a11y_territory_fmt` | %1$s, %2$s region, owned by %3$s, %4$d armies | %1$s, región %2$s, de %3$s, %4$d ejércitos |
| `tw_a11y_selected` | selected | seleccionado |
| `tw_a11y_neighbours_fmt` | Neighbours: %1$s | Vecinos: %1$s |
| `tw_a11y_owner_pattern_fmt` | %1$s, pattern %2$s | %1$s, patrón %2$s |
| `tw_pattern_solid` | solid | liso |
| `tw_pattern_stripes` | stripes | rayas |
| `tw_pattern_dots` | dots | puntos |
| `tw_pattern_crosshatch` | crosshatch | cuadrícula |
| `tw_pattern_bars` | bars | barras |
| `tw_pattern_checks` | checks | damero |
| `tw_list_sort_owner` | By owner | Por dueño |
| `tw_list_sort_region` | By region | Por región |
| `tw_tut_01` | Welcome, commander. Your goal is to conquer the whole map. | Bienvenido, comandante. Tu meta es conquistar todo el mapa. |
| `tw_tut_02` | The map is split into territories. Each one holds your armies or an enemy's. | El mapa se divide en territorios. Cada uno guarda tus ejércitos o los del enemigo. |
| `tw_tut_03` | Every turn has three steps: reinforce, attack, then fortify. | Cada turno tiene tres pasos: reforzar, atacar y fortificar. |
| `tw_tut_04` | You get armies for territories held, plus a bonus for whole regions. | Recibes ejércitos por tus territorios, más un bono por regiones completas. |
| `tw_tut_05` | Tap one of your territories to place an army there. | Toca un territorio tuyo para colocar un ejército allí. |
| `tw_tut_06` | Now attack: pick a territory with 2 or more armies, then a neighbour. | Ahora ataca: elige un territorio con 2 o más ejércitos y luego un vecino. |
| `tw_tut_07` | Dice decide each fight. The highest dice are compared and ties go to the defender. | Los dados deciden cada combate. Se comparan los más altos y los empates favorecen al defensor. |
| `tw_tut_08` | If the defender runs out of armies, you take the territory and move in. | Si el defensor se queda sin ejércitos, tomas el territorio y avanzas. |
| `tw_tut_09` | Conquer at least one territory in a turn to earn a card. | Conquista al menos un territorio en un turno para ganar una carta. |
| `tw_tut_10` | Three matching or three different cards make a set. Turn it in for armies. | Tres cartas iguales o tres distintas forman un juego. Canjéalo por ejércitos. |
| `tw_tut_11` | Finish by fortifying: move armies between two connected territories. | Termina fortificando: mueve ejércitos entre dos territorios conectados. |
| `tw_tut_12` | Pinch the map to zoom, or open the territory list to pick one. | Pellizca el mapa para ampliar, o abre la lista para elegir un territorio. |
| `tw_tut_13` | Hold every territory of a region to earn its bonus each turn. | Controla todos los territorios de una región para ganar su bono cada turno. |
| `tw_tut_14` | The last commander standing wins. Good luck! | Gana el último comandante en pie. ¡Buena suerte! |
| `tw_region_<id>` (6) | Cinderwake, Glasswater, Heartmark, Dunescar, Mistwood, Stormcrown | same (proper nouns) |
| `tw_terr_<id with _>` (36) | the 36 names in the territory table | same (proper nouns) |

### Game Hub texts

Named by `hubKey(text)`; shared texts that already exist in `strings.xml` (vs Computers, Pass & Play, the Pass and Play blurb, You, the computer-level and first-mover texts) are reused and **not** redefined.

| hubKey resource name | EN | ES |
|----|----|----|
| `hub_territory_wars` | Territory Wars | Guerra de Territorios |
| `hub_reinforce_roll_the_dice_and_conquer_the_map` | Reinforce, roll the dice and conquer the map. | Refuerza, tira los dados y conquista el mapa. |
| `hub_conquer_the_map_against_1_to_5_computers` | Conquer the map against 1 to 5 computers. | Conquista el mapa contra 1 a 5 computadoras. |
| `hub_war_length` | War length | Duración de la guerra |
| `hub_blitz_plays_half_the_map_long_plays_to_total_con_efe024` | Blitz plays half the map. Long plays to total conquest. | Blitz usa medio mapa. Larga se juega hasta la conquista total. |
| `hub_blitz_about_15_min` | Blitz (about 15 min) | Blitz (unos 15 min) |
| `hub_standard_about_45_min` | Standard (about 45 min) | Estándar (unos 45 min) |
| `hub_long_about_90_min` | Long (about 90 min) | Larga (unos 90 min) |
| `hub_fortify_reach` | Fortify reach | Alcance de fortificación |
| `hub_move_armies_along_any_chain_of_your_territories_089e46` | Move armies along any chain of your territories, or only next door. | Mueve ejércitos por cualquier cadena de tus territorios, o solo al vecino. |
| `hub_any_connected_chain` | Any connected chain | Cualquier cadena conectada |
| `hub_next_door_only` | Next door only | Solo al vecino |
| `hub_starting_handicap` | Starting handicap | Ventaja inicial |
| `hub_extra_armies_at_the_start_to_even_out_a_mixed_ta_ae629c` | Extra armies at the start to even out a mixed table. | Ejércitos extra al inicio para igualar una mesa mixta. |
| `hub_no_handicap` | No handicap | Sin ventaja |
| `hub_i_start_with_6_extra_armies` | I start with 6 extra armies | Empiezo con 6 ejércitos extra |
| `hub_computers_start_with_6_extra_armies` | Computers start with 6 extra armies | Las computadoras empiezan con 6 ejércitos extra |
| `hub_auto_place_armies` | Auto-place armies | Colocar ejércitos automáticamente |
| `hub_skips_the_opening_placement_round` | Skips the opening placement round. | Omite la ronda de colocación inicial. |

---

# 3. SCALE

## 3.1 Game length by mode

| Mode | Map | Seats | Setup | Ends when | Round cap | Target time |
|---|---|---|---|---|---|---|
| **Blitz** | half map, 18 territories (full map when 5 to 6 seats, Q1) | 2 to 4 (5 to 6 on the full map) | auto-deal, auto-place | capture 12 of 18 (24 of 36), or cap | 10 | 15 min |
| **Standard** | 36 | 2 to 6 | deal, opening placement (Auto-place optional) | capture 26 of 36, or cap | 18 | 45 min |
| **Long** | 36 | 2 to 6 | as Standard | total conquest, or cap, or stalemate | 40 | 90 min |

Planning arithmetic (to be confirmed by playtest timing and the simulation's round counts): a human turn averages 60 to 90 s, a bot turn 8 s (Easy), 12 s (Medium), 18 s (Hard) at 1x speed with animations, half that at 2x. Blitz with 1 person + 3 bots: 10 rounds x (45 + 36) s = 13.5 min plus 1 min setup. Standard 4 seats: about 16 rounds x 2 min = 32 min plus 4 min setup, leaving margin for slow turns. Long: up to 40 rounds, resumable across sessions (3.6). A "Skip animations" speed shortens bot turns to about 3 s.

## 3.2 Players and CPU fill

2 to 6 seats. Vs Computers: 1 person plus 1 to 5 computers (default 2). Pass and Play: 2 to 6 people with 0 to 4 computers filling empty seats (the Hub's pad/trim rules apply; a team table is v1). Computers share one level in the MVP (a per-seat level is a v1 idea). Seat colour and pattern are fixed by seat index so a returning player is always the same look.

## 3.3 Deterministic seeds and dice injection

- `TerritoryWarsConfig.seed` fixes the deal, the deck shuffle and, through `TerritoryWarsGame`, the dice stream (`SeededDice(seed xor DICE_STREAM_SALT)`). A war is replayable from `(seed, action list)`.
- The engine never touches `Random` for dice. `BattleResolver.resolve(attackerDice, defenderDice, dice)` takes a `DiceSource`; tests pass `ScriptedDice` for exact battles, simulations pass `SeededDice`, a network host owns the stream.
- Bots receive `Random(seed * 31 + round * 131 + eventSerial)`, so a bot's choice is a pure function of the table.
- The score card shows the seed; a "Replay seed" Hub debug entry lets testers re-run a war.

## 3.4 Balance simulation plan

**Harness**: a headless JVM test source (`TerritoryWarsSimulationTest`, manual / nightly, not in the unit gate) that runs the real engine and bots with no UI: `repeat(N) { play(seats, levels, config, seed = i) }` with seat order rotated each war so every bot meets every seat.

**Runs** (N = 10,000 wars per row unless noted):

| Run | Setup | Measure | Gate |
|---|---|---|---|
| Seat fairness | 2, 3, 4, 5, 6 bot seats, all Medium, Standard | winner share by seat | each within 2 points of 1/n (standard error is about 0.4 points at n = 10,000) |
| Skill ladder | 1 Hard + (n-1) Medium; 1 Medium + (n-1) Easy; n = 3, 4 | winner share of the strong seat | Hard >= 1.5x fair share vs Medium; Medium >= 2x vs Easy |
| Length | all modes, 2 to 6 seats, Medium | rounds to finish: P10, P50, P90 | Standard P50 <= 14, P90 <= 18; Blitz P90 <= 10; Long P90 <= 40 |
| Endings | all modes | share ending by domination, capture target, cap, stalemate | Standard stalemate+cap <= 25%, stalemate alone <= 3%; Blitz cap <= 10% |
| Cards | with and without cards (ablation) | winner-share change, length change | cards change the winner in <= 50% of wars; they shorten wars by 10 to 30% |
| Handicap | 4 seats, one seat +6 | its win share | +8 to +15 points |
| Region bonuses | log which region the winner held longest | region ownership at win | no region held by the winner in > 60% of wars (retune bonuses if so) |
| Dice fairness | 1,000,000 dice | chi-square per face | p > 0.001 |

**Reference values** the engine tests must reproduce exactly (round outcome, `(attacker losses, defender losses)`; ties to the defender):

| Dice (A v D) | Outcomes as (attacker losses, defender losses) |
|---|---|
| 3 v 2 | (0,2) 1445/3888 = 37.17%; (1,1) 2611/7776 = 33.58%; (2,0) 2275/7776 = 29.26% |
| 2 v 2 | (0,2) 295/1296 = 22.76%; (1,1) 35/108 = 32.41%; (2,0) 581/1296 = 44.83% |
| 1 v 2 | (0,1) 55/216 = 25.46%; (1,0) 161/216 = 74.54% |
| 3 v 1 | (0,1) 95/144 = 65.97%; (1,0) 49/144 = 34.03% |
| 2 v 1 | (0,1) 125/216 = 57.87%; (1,0) 91/216 = 42.13% |
| 1 v 1 | (0,1) 5/12 = 41.67%; (1,0) 7/12 = 58.33% |

These were computed by full enumeration of all dice outcomes on 2026-11-07 (ties to the defender); the `...MatchesExactProbabilities` tests re-enumerate and must agree.

Conquest probability `P(a, d)` (attacker armies available to attack `a` = source armies - 1, defender armies `d`, maximum dice): P(2,1) = 0.754, P(3,2) = 0.656, P(4,1) = 0.972, P(5,3) = 0.769, P(5,5) = 0.506, P(6,2) = 0.934, P(8,4) = 0.888, P(10,5) = 0.916, P(10,10) = 0.568, P(20,10) = 0.975.

**Tuning knobs** (in order of preference): region bonuses, set values and escalation, starting armies, capture targets, round caps, stalemate rounds, handicap constant. Bots are never retuned to hide a rules problem; fix rules first.

**Throughput target**: 10,000 wars in under 10 minutes on a developer machine (the engine allocates per action, a 36-element copy; fine). The run produces a CSV and a one-page summary stored with the design doc.

## 3.5 Performance budget

| Item | Budget |
|---|---|
| Draw 36 polygons + 36 chips + sea links | <= 6 ms main-thread per frame at 4x zoom on a mid-range phone (Snapdragon 6-class), 60 fps pan/zoom; no per-frame allocation |
| Static layer | territory `Path`s built once per map (`remember(map)`) in map units; owner fills and pattern shaders cached; region borders pre-merged; redrawn only when ownership changes |
| Dynamic layer | selection halo, highlighted targets, chips; army numbers measured once per value (`TextMeasurer` cache) |
| Zoom / pan | `graphicsLayer` / canvas transform; hit-test in map space: point-in-polygon over 36 polygons plus a centroid snap radius (microseconds) |
| Bot compute | off the main thread (`withContext(Dispatchers.Default)`), cancellable, budgets 20 / 80 / 250 ms per decision, 2.5 s per Hard turn |
| State snapshots | state is immutable; a move copies 36-element lists; the saved JSON is about 2 to 4 KB; writes are coalesced to turn boundaries and human action batches and written off the main thread (audit 02 flags main-thread saves in `onPause`) |
| Dice animation | 600 ms (instant under Reduced Motion); reuses `Die3DView` |
| Memory | one state + one undo stack of at most 40 placement snapshots per turn; no bitmaps beyond the cached pattern tiles |

Verification (not done yet): a Macrobenchmark or at least `dumpsys gfxinfo` pass of a 4x pan/zoom on a tablet and the Fold, and a StrictMode run for main-thread disk writes.

## 3.6 Persistence and resume

- `TerritoryWarsGame` implements `ResumableGame`. `snapshot()` returns JSON (schema version 1: config and seed, players, owners and armies by territory index, hands, deck and discard as card ids, sets turned in, phase, pools, pending move-in, round counters, dice drawn, session wins, level) or null when not started, over or unencodable. It is cheap, read-only and safe during a bot turn.
- Saved: after every human action batch and at every turn start; one save slot per mode route like every other game.
- `restore()` must validate every item listed in the KDoc of `TerritoryWarsSnapshot` (map and sizes, roster match, card conservation, phase consistency, bounds on everything) and returns false untouched on any failure. In the scaffold it always returns false (a fresh war starts); implementing it is part of phase P6.
- A resumed bot turn simply re-plans from the table (bots are stateless). The hand-over curtain shows again on resume in Pass and Play (derived state, not saved).
- Versioning: unknown newer `schemaVersion` is refused; older versions get a migration function per bump; ids of territories, regions and cards are frozen forever.

## 3.7 Multiplayer plan (later, designed now)

- **Authority**: host-authoritative like every networked game here: the host owns the engine, the dice stream and the deck; guests send **intents** (`PlaceArmies`, `TurnInCards`, `Attack`, `AttackUntilDone`, `MoveIn`, `EndAttack`, `Fortify`, `SkipFortify`) that the host validates with the same `apply`; invalid intents get a rejection. Identity comes from the transport (the identity-hardening of PR #9 applies: ignore any `fromPlayerId` in the payload).
- **Hidden information = card hands.** The host sends each guest its own state through `TerritoryWarsState.redactedFor(seat)`: other hands become that many face-down placeholders, the deck order is hidden. Army counts stay public in the MVP network mode (fog is a v1 rule, and then the redaction grows to cover it).
- **Message budget**: a state sync is 2 to 4 KB, far under the relay's 64 KiB payload cap; per-action deltas are tiny.
- **Seats**: relay rooms allow 8, so 2 to 6 fits.
- **Async fit**: turn-based with long idle gaps is the natural shape for the relay's quick-match turn-based list (`HubMode.quickMatch`): quick match offers only the `"std"` option set (Standard, connected fortify, no handicap), 2 to 4 seats first. A dropped guest is replaced by a bot at its seat after the reconnect window (the engine is seat-agnostic: `isBot` flips). Resume uses the host snapshot.
- Prerequisites not built yet: `TerritoryWarsNetMessage`, host/guest classes, lobby wiring, `networkedGames` registration, RTT is irrelevant (turn-based). Not part of the MVP; the scaffold keeps `redactedFor` and intent-shaped actions so nothing needs to change in the engine for it.

## 3.8 Phased delivery with effort

| Phase | Content | Effort |
|---|---|---|
| P1 Engine | map validation, deal, placement, reinforcement, battle, conquest, cards, elimination, fortify, wins, snapshots; scripted tests, fuzz | **L** |
| P2 Easy + Medium bots, simulation harness, balance pass 1 | `TerritoryWarsBot` levels 1 and 2, headless sim, seat fairness and length | **M** |
| P3 Map view | polygon canvas, patterns, chips, zoom, hit-testing, territory list sheet, TalkBack nodes | **L** |
| P4 Turn UI and states | setup, deal, placement, attack with dice, move-in, fortify, cards, hand-over curtain, post-game, score card, haptics and sound | **L** |
| P5 Hard bot, tutorial, balance pass 2 | region and threat scoring, coach, skill ladder gates | **M** |
| P6 Persistence, polish, verification | restore with validation, stats hooks, strings complete, accessibility device pass (TalkBack, 1.6x font, Fold), performance pass, code review | **M** |
| P7 v1 rules | missions, fog, teams, drafted deal, neutral garrison | **L** |
| P8 Online | host/guest, redaction, quick match, async | **L** |

P1 to P6 deliver the MVP (about 2 L + 3 M + 1 L).

## 3.9 Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | A 36-polygon map is hard to read or tap on a phone | High | High | zoom + list sheet + chip snap + patterns (1.5); phone usability test in P3 before anything else is built on it |
| R2 | The lattice map looks like a grid, not a world | Medium | Medium | polygons are replaceable art (ids and adjacency frozen); stronger jitter and coastlines in a polish pass |
| R3 | Games run long (the genre's classic problem) | High | High | capture target, round cap, stalemate guard, Blitz; simulation gates on length |
| R4 | Bots feel dumb or play slowly | Medium | High | three levels with exact scripts; budgets; skip-animations speed |
| R5 | A seat or the first mover has an unfair edge | Medium | Medium | rotated-seat simulation; handicap |
| R6 | Name or mechanic too close to another product | Low | High | original names, own numbers, blocklist test, owner skim (1.7) |
| R7 | Save/restore bugs lose long games | Medium | High | strict validation, round-trip tests for every phase, card conservation property test |
| R8 | Pass and Play leaks hidden cards | Medium | Medium | mandatory curtain; cards never drawn behind it |
| R9 | Accessibility regressions on the dense map | Medium | High | list-sheet path is complete on its own; device TalkBack pass in P6 |
| R10 | String or resource-name clashes with parallel work | Medium | Low | unique `tw_` prefix; hub texts reuse shared keys; gate scans |
| R11 | Frame drops at zoom on weak phones | Low | Medium | static layer caching, budget measured in P6 |
| R12 | Scope creep into v1 rules | Medium | Medium | MVP list frozen in 1.3 |

## 3.10 Test plan

All stubs exist as `@Ignore`d tests (the gate stays green) in `app/src/test/java/com/gamesuite/games/territorywars/`; un-ignore as each phase lands. Tiers: unit (rules, cards, map, hub, strings), property/fuzz (random legal wars never throw, always terminate, conserve armies and cards, snapshot round-trips), simulation (3.4, manual/nightly), UI (Compose semantics and accessibility checks in P3 and P6), device (tablet, Fold, TalkBack, 1.6x font, one real play-through per mode).


**TerritoryWarsMapTest** - Map content: the invented world is sound (doc 3.4)

- `standardMapHasThirtySixTerritoriesInSixRegionsOfSix`: 36 territories, 6 regions of exactly 6
- `everyBorderIsSymmetricAndNoTerritoryBordersItself`: adjacency symmetric, no self border, no duplicate neighbour
- `mapProblemsIsEmptyForAllThreeMaps`: WarMap.problems() is empty for STANDARD, BLITZ_WEST, BLITZ_EAST
- `everyRegionIsInternallyConnected`: each region is one connected component through own borders
- `wholeMapIsConnectedIncludingSeaLinks`: the 36 map is one connected graph
- `blitzHalvesHaveEighteenTerritoriesAndStayConnected`: west and east halves: 18 each, connected, sea links to the other half dropped
- `territoryAndRegionIdsAreUniqueAndKebabCase`: ids unique, [a-z-]+ only (saves and network use them)
- `polygonsAreSimpleAndCentroidsLieInsideBounds`: each polygon has 4+ points, non-zero area, centroid inside its bounding box
- `noTerritoryNameIsOnTheTrademarkBlocklist`: names, regions and card names avoid the blocklist in doc 2.7
- `regionBonusesMatchTheDocTable`: bonuses 2,3,5,4,3,3 as in doc 3.4

**TerritoryWarsBattleTest** - Battle resolution known values (doc 3.1 rules 8-10 and 4.4 reference table)

- `threeDiceVsTwoDiceMatchesExactProbabilities`: enumerate all 7776 outcomes: 1445/3888, 2611/7776, 2275/7776
- `twoDiceVsTwoDiceMatchesExactProbabilities`: enumerate 1296 outcomes: 295/1296, 35/108, 581/1296
- `oneDieVsTwoDiceMatchesExactProbabilities`: 161/216 attacker loses nothing, 55/216 loses one
- `threeDiceVsOneDieMatchesExactProbabilities`: 95/144 and 49/144
- `tieGoesToTheDefender`: scripted 4 vs 4 removes one attacker army
- `highestDiceAreComparedFirstThenSecondHighest`: scripted unsorted rolls are sorted high to low before pairing
- `dicePairsEqualMinOfBothSides`: 3 v 1 has one pair, 1 v 2 has one pair
- `diceAreDrawnAttackerFirstThenDefender`: ScriptedDice order contract
- `totalLossesPerRoundEqualPairsCompared`: attackerLosses + defenderLosses == pairs, always
- `conquestWinProbabilityTableMatchesReferenceValues`: DP table: 2 vs 1 = 0.754, 5 vs 5 = 0.506, 10 vs 5 = 0.916 (doc 4.4)
- `seededDiceReplayTheSameSequence`: same seed, same dice; rolled counter matches draws
- `diceAreFairOverOneMillionRolls`: chi-square on faces within bounds

**TerritoryWarsRulesTest** - Engine rules, scripted full turns (doc 3.1 rules 1-24 and the state machine 3.3)

- `dealGivesEveryTerritoryOneOwnerAndOneArmy`: after deal: owners set, armies == 1 everywhere
- `dealSharesTerritoriesWithinOneForEverySeatCount`: 2..6 seats on 36 and 2..4 on 18: max - min <= 1
- `startingArmiesFollowTheTable`: 30/26/22/18/16 and 16/14/12 totals, handicap added on top
- `openingPlacementGoesInBatchesOfThreeInSeatOrder`: PLACE_INITIAL cycles seats, 3 armies per visit
- `autoPlaceFillsEveryPoolAndStartsTheFirstTurn`: Auto-place and Blitz skip PLACE_INITIAL
- `reinforcementIsMaxOfThreeAndTerritoriesOverThree`: 8 -> 3, 12 -> 4, 36 -> 12
- `reinforcementAddsEveryHeldRegionBonus`: holding Heartmark adds 5
- `cannotAttackBeforeAllArmiesArePlaced`: PLACE_FIRST rejection
- `attackNeedsTwoArmiesAnAdjacentEnemyAndYourOwnSource`: NEED_TWO_ARMIES, NOT_ADJACENT, NOT_YOURS, OWN_TARGET
- `attackerDiceAreCappedAtThreeAndAtArmiesMinusOne`: BAD_DICE for 4 dice or dice >= armies
- `defenderAlwaysRollsTheMaximumDice`: min(2, defender armies)
- `conquestMovesInAtLeastTheDiceRolledAndLeavesOneBehind`: min = last dice, max = armies - 1
- `conquestWithoutMoveInBlocksEveryOtherAction`: MOVE_IN_FIRST
- `attackUntilDoneStopsOnConquestOrAtOneArmy`: blitz action with scripted dice
- `firstConquestOfTheTurnEarnsExactlyOneCard`: two conquests, one card
- `noConquestEarnsNoCard`: no card at turn end
- `fortifyConnectedMovesAlongAnyOwnChainIncludingSeaLinks`: path rule, BFS through own territories
- `fortifyAdjacentOnlyAllowsANeighbour`: FortifyReach.ADJACENT
- `fortifyLeavesAtLeastOneArmyAndHappensOncePerTurn`: LEAVE_ONE, ALREADY_FORTIFIED
- `skipFortifyEndsTheTurnAndPassesToTheNextLivingSeat`: eliminated seats are skipped
- `roundCounterAdvancesAfterTheLastLivingSeat`: round increments once per full cycle
- `eliminationTransfersEveryCardToTheEliminator`: hands merge, eliminated seat out
- `eliminatorOverFourCardsMustTurnInBeforeAnyFurtherAttack`: CARDS_FORCED mid-attack, attack resumes
- `holdingFiveCardsForcesATurnInAtTurnStart`: CARDS_FORCED before REINFORCE
- `lastPlayerStandingWinsByDomination`: winReason DOMINATION
- `captureTargetWinsTheMomentTheTerritoryCountIsReached`: 26 of 36, 12 of 18, 24 of 36 in Blitz with 5+ seats
- `roundCapEndsOnPointsWithTheTieBreakChain`: territories, armies, cards, then shared win
- `fiveRoundsWithoutAConquestEndTheWarAsAStalemate`: STALEMATE_ROUNDS rule
- `rejectedActionLeavesTheStateUnchanged`: structural equality before and after, for every RejectReason
- `actionsAfterGameOverAreRejected`: GAME_OVER
- `sameSeedAndSameActionsGiveTheSameWar`: determinism: replay a recorded action list
- `randomLegalPlayNeverThrowsAndAlwaysTerminates`: fuzz: 2,000 random-legal wars, bounded length
- `armyAndCardConservationHoldsAfterEveryAction`: armies on map + pools consistent; cards in hands+deck+discard == deck size
- `setValueFormulaMatchesTheEscalationTable`: TerritoryWarsRules.setValue and baseReinforcement known values

**TerritoryWarsCardsTest** - Deck composition and sets (doc 3.1 rules 13-17, 3.4)

- `fullDeckHasThirtySixTerritoryCardsAndTwoWilds`: 38 cards, 12 per symbol, ids unique
- `blitzDeckHasEighteenTerritoryCardsAndOneWild`: 19 cards
- `everyRegionHoldsTwoOfEachSymbol`: symbol cycling check
- `threeOfOneSymbolIsASet`: isSet true
- `oneOfEachSymbolIsASet`: isSet true
- `aWildWithAnyTwoCardsIsASet`: isSet true for all pairs
- `twoOfOneSymbolAndOneOtherIsNotASet`: isSet false
- `duplicateOrWrongSizedSelectionsAreNotASet`: same card twice, 2 or 4 cards
- `anyFiveCardsContainASet`: hasSet exhaustive over all 5-card hands of the 38 deck (pigeonhole)
- `setValuesEscalateThreeFiveSevenNineTwelveFifteenEighteenThenPlusFour`: setValue(0..10)
- `setCounterIsSharedByAllPlayers`: second player's first turn-in pays the 2nd value
- `emptyDeckReshufflesTheDiscardPile`: draw with empty deck

**TerritoryWarsBotTest** - Bot behaviour per level (doc 3.6)

- `everyBotActionIsLegalForTheSeatOnTurn`: fuzz all levels: chooseAction in legalActions
- `easyNeverAttacksMoreThanThreeTimesPerTurn`: cap
- `easyFortifiesAtMostOneTurnInFour`: 25 percent rule within sampling bounds
- `mediumSkipsAttacksBelowSixtyPercentConquestChance`: threshold rule
- `mediumFinishesARegionWhenOneTerritoryIsMissingAndOddsAreSeventyFive`: completion bonus rule
- `hardPicksTheTargetRegionWithTheBestBonusPerEnemyArmy`: region scoring formula
- `hardSplitsReinforcementsSixtyForty`: 60 staging / 40 defence
- `hardKeepsAReserveGarrisonNextToStrongEnemies`: exposure check
- `hardGoesForAnEliminationWhenTheVictimIsWeakAndHoldsCards`: elimination rule
- `botsRespectTheComputeBudgetOnASlowClock`: fake clock, budgets 20/80/250 ms
- `botDecisionIsIdenticalForTheSameStateAndRandom`: determinism
- `botStopsWhenCoroutineIsCancelled`: ensureActive check
- `botNeverStallsATableWhenItHasNothingToDo`: null -> phase advances

**TerritoryWarsGameTest** - GameModule wrapper, snapshot, resume, threading (doc 4.5 to 4.7)

- `initRejectsFewerThanTwoOrMoreThanSixSeats`: IllegalArgumentException
- `startMatchBuildsAConfigFromHubOptions`: length, fortify, handicap, autoPlace parse
- `blitzWithFiveSeatsPlaysTheFullMap`: mapIdFor rule
- `blitzWithFewerSeatsPicksAHalfFromTheSeed`: even seed west, odd seed east
- `snapshotRoundTripsEveryPhase`: snapshot -> restore -> structurally equal state for each TurnPhase
- `snapshotIsNullBeforeStartAndAfterTheWarEnds`: contract
- `restoreRejectsCorruptStaleAndForeignSnapshots`: each validation item in the DTO KDoc has a case
- `restoreLeavesTheGameUntouchedOnFailure`: returns false, state unchanged
- `restoreFastForwardsTheDiceStream`: next die after restore equals next die of uninterrupted war
- `redactedStateNeverContainsAnotherSeatsCards`: TerritoryWarsState.redactedFor
- `leaveSessionRecordsWinsAndALoneLeaderWins`: slide-and-climb parity
- `abortMatchEndsWithoutScores`: GameModule.abortMatch default
- `botStepIsAppliedOnlyIfTheTableHasNotMoved`: identity guard
- `savingDuringABotTurnResumesWithTheBotReplanning`: bot turn is stateless
- `handOverCurtainShowsAgainAfterResumeInPassAndPlay`: UI state derived, not saved

**TerritoryWarsHubSpecsTest** - Hub spec and strings (doc 3.8 and 3.9)

- `specIsAdultsWithMinAgeTen`: audience ADULTS, minAge 10
- `specIsNotNetworkedInTheMvp`: no NEARBY / ONLINE mode
- `seatRangeIsTwoToSix`: minSeats 2, maxSeats 6 in both modes
- `everyHubTextHasAResourceInBothLocales`: hubKey(text) exists in values and values-es, or in strings.xml
- `noHubOrTwStringNameClashesWithAnotherFile`: unique across res/values
- `spanishPlaceholdersMatchEnglishOnes`: %1 and %2 placeholder parity
- `englishAndSpanishFilesHaveTheSameKeys`: key parity for territory_wars_strings.xml
- `tutorialLinesAreUnderOneHundredTwentyCharacters`: EN and ES
- `accentColoursMeetContrastMinimums`: 3.0 non-text, 4.5 label text, both themes
- `seatColoursAndPatternsAreDistinctForSixSeats`: six distinct colours and six distinct patterns
- `everyRejectReasonKeyExistsAsAString`: whyKey resources exist

**TerritoryWarsSimulationTest** - Monte Carlo balance harness, headless (doc 4.4)

- `winnerBySeatIsWithinTwoPointsOfFairForTwoToSixBotSeats`: 10,000 wars per seat count, rotated seats
- `warLengthInRoundsMatchesTheModeTargets`: P50/P90 per mode vs doc table
- `stalemateRateIsUnderThreePercentForStandard`: cap/stalemate endings
- `hardBeatsMediumAndMediumBeatsEasyInMixedTables`: skill ladder gates
- `cardsDoNotDecideMoreThanHalfOfAllWars`: ablation: with and without cards
- `blitzFinishesInsideTheRoundCapInNinetyPercentOfWars`: Blitz target
- `simulationIsDeterministicForAFixedSeedList`: same seeds, same results
- `tenThousandWarsFinishInUnderTenMinutesOnADevMachine`: throughput guard (manual gate, not CI)

---

# 4. SCAFFOLD

Compiling Kotlin skeletons only, written without building. Package `com.gamesuite.games.territorywars` under `app/src/main/java/`; tests under `app/src/test/java/`; strings under `app/src/main/res/`. **Nothing is registered anywhere.**

## 4.1 Files

| File | Contents |
|---|---|
| `docs/design/boardgames/territory-wars.md` | this document |
| `TerritoryWarsModel.kt` | `MapPoint`, `Territory`, `Region`, `SeaLink`, `WarMap` (+ `subMap`, `problems`), `CardSymbol`, `WarCard`, `TerritoryWarsPlayer`, `WarLength`, `FortifyReach`, `WinRule`, `WinReason`, `HandicapPreset`, `TerritoryWarsConfig`, `TurnPhase`, `PendingMoveIn`, `TerritoryWarsEventKind`, `TerritoryWarsEvent`, `TerritoryWarsState` (+ `blank`, `redactedFor`), `TerritoryWarsAction` |
| `TerritoryWarsMaps.kt` | the frozen world: `STANDARD`, `BLITZ_WEST`, `BLITZ_EAST`, `REGIONS`, `SEA_LINKS` |
| `TerritoryWarsRules.kt` | constants and pure formulas (`setValue`, `baseReinforcement`, `startingArmies`, `configFor`, `handicapArmies`) and `TerritoryWarsCards` (`buildDeck`, `isSet`, `hasSet`) |
| `TerritoryWarsDice.kt` | `DiceSource`, `SeededDice`, `ScriptedDice`, `BattleOutcome`, `BattleResolver`, `StubBattleResolver` |
| `TerritoryWarsEngine.kt` | `RejectReason`, `ActionResult`, `Reinforcement`, `TerritoryWarsEngine`, `StubTerritoryWarsEngine` (safe values) |
| `TerritoryWarsBot.kt` | `ThinkBudget`, `ThinkBudgets`, `TerritoryWarsBot`, `StubTerritoryWarsBot` (always null) |
| `TerritoryWarsPalette.kt` | `OwnerPattern`, `TerritoryWarsPalette` |
| `TerritoryWarsGame.kt` | `TerritoryWarsOptions`, snapshot DTOs (validation TODOs in KDoc), `TerritoryWarsGame : GameModule, ResumableGame` |
| `TerritoryWarsHubSpecs.kt` | `GameHubSpec` with `audience = ADULTS`, `minAge = 10`, option keys, accent colours, glyph |
| `res/values/territory_wars_strings.xml`, `res/values-es/...` | all strings of 2.9 |
| 8 test files, 115 `@Ignore`d tests | section 3.10 |

Not created (by instruction): any screen (`ui/TerritoryWarsScreen.kt`), art file, or edit to a shared file.

## 4.2 What the coordinator must integrate (later, not now)

1. **Hub spec** (`core/GameHubSpecs.kt`): add `TerritoryWarsHubSpecs.territoryWars` to `baseSpecs()` in the BOARD section. It already sets `audience = ADULTS, minAge = 10`. Do **not** add `"territory-wars"` to `networkedGames`.
2. **Glyph** (`core/GameCatalog.kt`): `"territory-wars" -> "🗺"` (also `TerritoryWarsHubSpecs.GLYPH`; world-map emoji, checked unused).
3. **Accent** (`ui/GameTheme.kt`, `GameThemes.accents`): `"territory-wars" to GameAccent(0xFF3B4D8A, 0xFF7F95CC)`; contrast (computed): light accent 7.83 and 6.20 on the two light surfaces (white label 8.03), dark accent 5.77 and 4.13 on the dark surfaces (ink label 6.39); all above the 3.0 and 4.5 minimums. Distinctness from the other accents was NOT checked against the current palette.
4. **Route** (`MainActivity.kt`): `composable("territory-wars") { val g = rememberActiveModule(sessionManager, "territory-wars") { TerritoryWarsGame(<real engine factory>, <real bot>) }; TerritoryWarsScreen(...) }` once the screen exists.
5. **Tips and coach** (`ui/GameTips.kt`, `core/GameCoach.kt`): tips from the tutorial lines (1, 6, 10); a coach motif (`CoachMotif.DICE` is the closest) and entry using `tw_tut_*`.
6. **Audience table**: none needed (the spec's own tags win; ADULTS is the default anyway).
7. **Gate**: `HubLocalizationTest`, `GameTipsTest`, `GameCoachTest`, `GameThemeTest`, `GameAudienceTest` iterate every registered spec; run them after registering. Run `:app:testDebugUnitTest --tests "com.gamesuite.games.territorywars.*"` (all ignored today).
8. Resource-name scan: the `tw_*` and the 19 hub names were checked against `res/values*` on 2026-11-07 (no clashes); concurrent wave-3 work may add some, so the gate's duplicate-resource error is the final check.

## 4.3 Consistency checklist

- Option keys, handicap ids, Hub texts: doc 2.7 and 2.9 = `TerritoryWarsHubSpecs` = `TerritoryWarsOptions.fromExtras`.
- Rules numbers (4, 7, 16, 19), constants and tables: doc 2.1 and 2.4 = `TerritoryWarsRules`.
- Map: doc 2.5 = `TerritoryWarsMaps` (generated from one source; region bonuses 2, 3, 5, 4, 3, 3).
- Strings: doc 2.9 = both XML files (117 UI strings, 6 region names, 36 territory names and 19 hub texts).
- Tests: doc 3.10 = the 115 stubs, same names.
- Known gaps by design: engine, bots, restore, screen, art are stubs or absent; `redactedFor` is written but untested; the card-symbol cycle uses table order of each region.
