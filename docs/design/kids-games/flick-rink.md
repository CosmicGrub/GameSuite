# Flick Rink (`flick-rink`): SCOPE, SCRIPT, SCALE, SCAFFOLD

Status 2026-11-07, branch `session/wave-3`. Design only plus a compiling scaffold. **Nothing in this package has been built, compiled or run** (no Gradle, no tests, no emulator). Every number marked "estimate" is unmeasured on a device.

**Legal rule (hard).** Mechanics are free to use; names, art, rules wording and layouts are not. Flick Rink uses the genre mechanics (flick a character through gaps and around obstacles to collect an item and come home; flick discs at targets) and nothing else from any existing product. We do not use the names, art or components of ICECOOL, Coconuts, Dragomino, Kingdomino, Carcassonne or any publisher's product. Original names here: "puck pal" (our penguin-ish blob), "igloo ring" (home zone), "snow drift", "puddle", "gate and pad", "sliding wall", "Bullseye". All rink layouts below were drawn for this app. Art checklist in section 4.5.

**Inputs read.** `docs/research/kids-5yo-games-research.md` (lessons 1 to 7: cooperative first, no reading, 5 to 15 minutes, a little choice, fairness across ages, physical feel, one-line parent value) and `docs/research/bgg-geeklist-307608-page1.md` (dexterity is 7 of the first 25 items and 9 of the top 20 slots: "the largest unbuilt opportunity"; flick games rank 9 and 16). House pattern from `games/bridgebuilders` and `games/slideclimb` (GameModule + ResumableGame wrapper, injected engine, HubSpecs next to the game, KIDS audience), physics approach from `games/curling/CurlingPhysics.kt` (fixed tick, whole delivery simulated to rest, recorded frames, pure functions, no clock) and `docs/audit/01, 02, 04`.

## 0. Decisions at a glance

| Topic | Decision |
|---|---|
| Id, title | `flick-rink`, "Flick Rink" (ES "Pista Deslizante") |
| Audience | `GameAudience.KIDS`, `minAge = 5`, 1 to 4 players, adults welcome |
| Modes | Turns (vs computers or same screen), Co-op Relay, Solo Practice; games "Fish run" and "Bullseye" |
| Input | Drag back and let go (slingshot), drag anywhere by default; one-switch and button aiming alternatives |
| Physics | Own 2D circle-vs-segment engine, fixed 120 Hz step, Double math, no pow/trig in the step loop, a whole flick is simulated to rest in one call and then played back |
| Determinism | Level + seed + flick log = identical result; state hash; saves are the log |
| Levels | 14 fish-run rinks in 3 bands + 6 bullseye boards, JSONL format |
| Multiplayer | Same screen now. Nearby later: host-authoritative, hash-checked (not lockstep) |
| Not registered | `FlickRinkHubSpecs.spec` is not in `GameHubSpecs`; nothing else shared is touched |

## 1. SCOPE

### 1.1 Purpose
A short, physical, laughing game for a five-year-old and the grown-up next to them: pull back, let go, watch the little penguin-ish blob slide, bounce and (maybe) land on the fish. It fills the biggest gap in the program (dexterity), is playable by one finger, and has a gentle co-op mode where "we" win or "we" try again.

### 1.2 Audience and fairness (adult plus child)
- Target: age 5+ with an adult nearby; the long tail to 9+ and adults (the research notes the favourites at age 5 are still played at 9). Pre-readers can play everything: every state is a picture, a colour plus a shape badge and a sound.
- Skill gap between a five-year-old and an adult is large for any flick game. Four levers, all in setup, all per seat:
  1. **Aim helper** for chosen seats: guide line of 160, 320 or 560 units (rink is 960 tall). Grown-ups play with none.
  2. **Relaxed flicking** (table-wide): bigger igloo ring (140 vs 110) and fish pick-up (87 vs 58), softer wall bounce (0.60 vs 0.80), no flick limit.
  3. **Co-op Relay**: no one beats anyone; the team budget is par x 2.0 (little), x 1.6 (medium), x 1.3 (big).
  4. **Luck-forward scoring**: banking the fish needs the fish and a stop in the ring; bumps are free and fun, a lucky bounce helps a child as much as an adult.
- Never punish: a puddle sends you back to where you started the turn, nothing is lost; bumping friends is a feature.

### 1.3 MVP, v1, later
| Stage | Content |
|---|---|
| **MVP** | Fish run in Turns (2 to 4 seats, human or computer pal), Co-op Relay, Solo Practice with stars; rinks 1 to 14; Bullseye in Turns and Solo (boards 1 to 6); handicap row (aim helper); Relaxed; drag-anywhere; button aiming; five UI states; first-run tutorial; resume; bots Easy/Medium/Hard; EN + ES |
| **v1** | One-switch sweep aiming polish; co-op Bullseye; haptics and sound pack; per-profile stars in the parent dashboard; rink thumbnails in solo picker; "Skip the slide" Reduced-Motion path tuned |
| **Later** | Nearby (host-authoritative); rink builder (kids place walls on snap points, saved per profile); seasonal rink skins; more fish types; fixed-point physics for cross-device lockstep |

### 1.4 Out of scope
Real-money anything, chat, online play in this version, user-generated sharing, ads, a tutorial narrator voice (text and pictures only; narration is a shared-shell feature), spin/curve on the flick, power-ups, timers that pressure a child, elimination.

### 1.5 Safety and age notes
- No collection of personal data; names come from the roster. No links out. Leave dialog is the shared guarded one.
- Slow, rounded visuals; no flashing above 3 flashes per second (splash and banking sparkles are one-shots); Reduced Motion removes shake and sparkle.
- Four people around one phone: turns are strictly sequential so there are never two touches at once; the next player's name and colour/shape badge fill the top bar so the device is handed over knowingly.
- Fish and puddles are cartoon; nothing is "drowned" or "eaten".
- Age band: `minAge = 5` (a KID profile with band 4 to 6 sees it).

### 1.6 Accessibility
- **Aim assist** (above) plus **drag anywhere**: the flick starts anywhere on the ice, the pal need not be hit exactly (the pal is 68 units across, about 37 dp on a 360 dp phone; grab radius is irrelevant when drag-anywhere is on, and 56 dp when off).
- **One-switch / Switch aiming** (hub toggle, auto-on when a switch or TalkBack scanning is detected, open question Q9): tap 1 starts the aim needle sweeping 0 to 360 degrees at 1 revolution per 3.0 s (Relaxed 4.5 s); tap 2 locks the angle and starts the power bar (0 to 100 to 0 in 1.6 s, Relaxed 2.4 s); tap 3 flicks. Prompts: "Tap to lock the aim", "Tap to lock the power", "Tap to flick!" Any hardware switch maps to a tap on the whole screen.
- **Button aiming** (no dragging at all, also the TalkBack path): Turn left / Turn right (5 degree steps, hold repeats), Stronger / Softer (10 point steps), Flick. All 56 dp or larger, labelled, with the current state spoken ("Aim 135 degrees, power 60 percent").
- **Relaxed variant**: see 1.2. Also slows the sweep and removes puddles' splash-back (pal just stops in the puddle edge and is lifted out).
- **TalkBack**: turn banner is a polite live region; fish direction is spoken in 8 compass-like words ("The fish is up and to the left"); pal descriptions "Blue puck pal, carrying a fish"; results announced after each slide.
- **Colour blindness**: four pal colours from the Okabe-Ito palette (blue 0072B2, orange E69F00, green 009E73, pink CC79A7) AND four belly badges (circle, triangle, square, star); never colour alone. Gates and pads share a number glyph.
- **Reduced Motion** (`LocalReducedMotion`): no camera shake, no sparkle; the slide plays at 1.5x or can be skipped with "Skip the slide" (outcome identical: the sim already ran).
- Large font: all words are short and sit in 56 dp pills; pictures carry the meaning.

### 1.7 What it teaches (one line for the parent dashboard)
Fine-motor control and judging force and direction (cause and effect, early physics sense), plus turn-taking and teamwork.

### 1.8 Dependencies
Existing: `GameModule`, `ResumableGame`, `GameHubSpec`/`HubOptions`, `GameAudience`, kotlinx.serialization (already used), Compose Canvas for the screen (later). Shared files the integrator touches later (not now): `GameHubSpecs`, `GameCatalog` (glyph), `GameTheme` (accent), `MainActivity` (route), `GameTips`, `GameCoach`; see section 4.4. Soft: `LocalReducedMotion`, `LocalGameSpeed`, colour-blind mode, the profiles foundation (per-profile stars, KIDS gating). No new libraries, no network.

### 1.9 Open questions (each with a recommended default)
| # | Question | Recommended default |
|---|---|---|
| Q1 | Trademark check on "Flick Rink" and "puck pal" before a store listing? | Proceed; do a quick search before release; names are generic descriptive words |
| Q2 | Is Bullseye MVP or v1? | MVP for Turns and Solo; co-op Bullseye in v1 |
| Q3 | Drag-anywhere default? | On for everyone (it removes the small-target problem for age 5) |
| Q4 | Par values are designer estimates. Tighten them with a solver? | Yes, phase 1: a test solves each rink with beam search over (angle, power) and fails if par is below solver best or above 1.6x it |
| Q5 | Doubles with StrictMath or fixed-point? | Doubles for same-screen; host-authoritative for Nearby; fixed-point (Q16.16) only if lockstep is ever wanted |
| Q6 | May bumps knock a pal into a puddle? | Yes, it is funny and costs nothing |
| Q7 | Sound and haptics? | Emit events only (section 2.4); wire to the shared audio core later |
| Q8 | Lock rinks in Solo until earlier ones are cleared? | No locks; stars saved per profile |
| Q9 | Auto-enable Switch aiming when a switch device or TalkBack is on? | Yes when `isTouchExplorationEnabled`; the toggle can override |
| Q10 | Is the hidden `style` hub option (one route, three modes) acceptable to the Hub integrator? | Yes; fall back to three routes if the Hub drops hidden-option defaults from `extras` (test FR-H6 checks) |
| Q11 | Tablet landscape and Fold? | Rink stays portrait inside a landscape layout with the seat badges on the sides; no rotation of the play surface |
| Q12 | Cap the match at 12 minutes? | Yes: rink flick caps (24 flicks total in Turns) bound a Standard match at about 9.6 minutes worst case |

## 2. SCRIPT

### 2.1 Vocabulary
**Puck pal**: the round penguin-ish blob you flick (radius 34 units). **Igloo ring**: the home circle at the bottom (centre (300,850), radius 110). **Fish**: three tokens per rink (radius 18). **Bank** a fish: stop inside the igloo ring while carrying it. **Gate and pad**: a closed gate (wall piece) that a floor pad flips. **Sliding wall**: a wall that swaps between two positions each round. **Snow drift**: slows you (3x damping). **Puddle**: splashes you back to the start of the flick. **Bumper post**: round post, bounces with 0.90.
Rink: 600 wide, 960 tall units; x right, y down; angles in tenths of a degree clockwise from +x (straight up = 2700). Walls are 14 thick.

### 2.2 Rules script (Fish run)
**Setup**
1. Choose a mode. Turns: 2 to 4 seats (people and computer pals). Co-op Relay: 2 to 4 seats, all one team. Solo: 1 seat.
2. Choose Rink size (band little/medium/big), Match length (Quick 2, Standard 3, Long 5 rinks), optional Aim helper (who, how long), Relaxed, Drag anywhere, Switch aiming, and (co-op) Team flicks.
3. A match plays rinks in order: little = rinks 1 to 5, medium = 5 to 9, big = 10 to 14, first N of them. Solo: you pick the rink number.
4. Each seat gets a puck pal with a colour and a belly badge. Pals start inside the igloo ring at the four start spots (240,810), (360,810), (240,890), (360,890) in seat order.
5. All three fish are on the rink at their spots. Gates are closed, sliders in pose A.
6. The first mover is chosen by the Hub (You, the computer or random). Turn order is seat order.

**A turn (Turns and Co-op)**
7. The active player drags back and lets go (or uses the buttons or the switch). The flick goes the opposite way to the drag. A drag shorter than 16 units cancels with no flick used. Power = (drag length - 16) / (200 - 16), clamped, times 100, rounded.
8. The engine simulates the whole slide (section 2.4). All pals can move and bump; fish, pads and puddles react as in 9 to 14.
9. **Pick up**: a pal that is not carrying a fish picks up an uncarried fish when its centre comes within 58 units (87 Relaxed) of it, at any speed, even passing through. A pal carries at most one fish. A carried fish rides on the pal's head.
10. **Bank**: a carrying pal that comes to rest with its centre inside the igloo ring banks the fish: the fish disappears, +1 fish banked for that seat. (Relaxed: banks as soon as it enters the ring slower than 300 units per second.) Moving through the ring does not bank.
11. **Splash**: if a pal's centre enters a puddle (radius + 12) it stops dead; when the slide ends it is placed back at the spot where it stood when the slide started, still carrying whatever it carried. No penalty. (Relaxed: it simply stops at the puddle edge.)
12. **Pad**: when a pal's centre enters a pad disc, the linked gate flips (closed to open or open to closed). It flips once per entry; staying on the pad does nothing.
13. **Relay hand-off (Co-op only)**: if a carrying pal and a non-carrying pal collide at 30 units per second or faster, the fish hops to the pal that was hit.
14. **Rest**: the slide ends when every pal has been slower than 8 units per second for 12 consecutive steps (0.1 s), or after 900 steps (7.5 s), whichever is first; then all speeds are set to 0.
15. **End of round**: after the last seat has flicked, every sliding wall swaps pose A and B (if a pal is in the way it is nudged out sideways by the shortest push). Solo: after every flick.
16. **End of rink**: three fish banked, or the flick cap is used. Caps per seat: 12 (2 players), 8 (3), 6 (4); co-op uses the team budget (below); Solo and Relaxed have no cap (a safety valve ends the rink after 60 flicks per seat).
17. **Turns scoring**: fish banked per seat add up over the rinks. Most fish wins the match. Tie: fewest flicks used in total. Still tied: everyone wins ("Everybody wins this one!").
18. **Co-op**: the team must bank all three fish within the budget = ceil(par x band multiplier x feel) + retry bonus, where the multiplier is 2.0 / 1.6 / 1.3 and feel is Plenty 1.25, Normal 1.0, Tight 0.8. If the budget runs out the rink is "Try again together": same rink, fish reset, +2 flicks per failed try (cap +6). Clear every rink of the match to see "We did it!". Stars per rink: 1 for clearing, 2 with 25 percent of the budget left, 3 with 50 percent left; match stars = lowest rink stars. No one loses.
19. **Solo**: clear the rink; stars by flicks used: 3 at ceil(par x 1.25) or fewer, 2 at ceil(par x 1.75) or fewer, else 1.

**Bullseye** (Turns, Solo; co-op in v1)
20. Boards `tgt01` to `tgt06` (section 2.6). The target sits near the top. Each seat has 3 discs per board (radius 28).
21. Throw order: seat 1, 2, 3 ... then again, until everyone has thrown 3. A disc is flicked from the launch line y = 860; before flicking the player may tap the line to move the launch point to any x from 80 to 520 (default 300).
22. Discs stay where they stop and can be knocked by later discs. Walls, posts, drifts, gates and sliding walls work as above. Sliders swap after every full round of throws. No fish, no puddles.
23. When the last disc stops, each disc scores by the distance of its centre to the target centre: 5 points within 40 units, 3 within 90, 1 within 150, otherwise 0. Ring radii are scaled by band: little x1.3, medium x1.0, big x0.8.
24. Board score = sum of a seat's discs. Match = sum over boards (Quick 2, Standard 3, Long 5). Winner: most points; tie: most bullseyes (5s), then the single disc closest to the centre, then shared.
25. Solo stars per board: 3 at 11 points or more, 2 at 7 or more, else 1. Match stars = lowest.
26. Mapping a Solo "rink number" 1 to 14 to a board: `1 + (n - 1) * 6 / 14` (integer division).

### 2.3 State machine
| State | Entered when | Screen | Leaves on |
|---|---|---|---|
| SETUP | Hub launch (extras parsed) | Setup summary (handicap row shown here too when launched from the Ready screen) | Start |
| START | roster fixed | First-run tutorial (once per profile) or "Ready, %1 first!" card with seat badges | Play tap |
| AIMING | a turn begins | Mid-game: drag, buttons or switch; aim helper line if the seat has it | valid flick, or leave |
| SLIDING | flick accepted | Playback of the recorded frames (skippable in Reduced Motion) | playback done (the sim already finished) |
| RESOLVING | slide at rest | 0.6 s: banners ("Got the fish!", "Splash!"), score pips; sliders move at round end | next seat AIMING, or RINK_DONE |
| RINK_DONE | 3 fish banked / cap / budget out | Rink card: fish per seat, stars (co-op/solo), "Next rink" or "Try again together" | next rink, retry, or MATCH_DONE |
| MATCH_DONE | last rink done, or Leave | Post-game score card: winner or "We did it!", fish and flicks, stars | Play again / All done |

Transitions: SETUP -> START -> AIMING -> SLIDING -> RESOLVING -> (AIMING | RINK_DONE) ; RINK_DONE -> (AIMING of next rink | AIMING of the same rink after retry | MATCH_DONE). The five UI states the screen must show: setup, start, mid-game, post-game, score card.

### 2.4 Physics constants (`PHYSICS_VERSION = 1`)
| Constant | Value | Notes |
|---|---|---|
| Sim step | 1/120 s | fixed; display rate never feeds the sim |
| Max steps per flick | 900 (7.5 s) | safety stop; open ice from power 100 rests in 398 steps |
| Frame record | every 2nd step (60 per s) | for playback only; excluded from the state hash |
| Pal radius / fish radius | 34 / 18 | |
| Wall thickness (half) | 14 (7) | |
| Damping | v = v * (1 - 1.10 * dt) per step | exponential, per second 1.10 |
| Coulomb decel | 20 units/s^2 | guarantees a finite stop |
| Snow drift | damping x3.0 | rest distance about a third |
| Stop threshold | 8 units/s for 12 consecutive steps | then speed := 0 |
| Wall restitution | 0.80 (Relaxed 0.60) | normal component only, tangent kept, no spin |
| Post restitution | 0.90 | |
| Pal-pal restitution | 0.92 | equal masses, impulse j = -(1 + e) * vn / 2 |
| Relay bump threshold | 30 units/s | co-op hand-off |
| Flick speed | v0(p) = 140 + 760 * p^2 / 10000, p = 0..100 integer | no pow(); table below |
| Drag mapping | dead zone 16, full power at 200 | rink units |
| Angle input | 0..3599 tenths of a degree; direction from StrictMath.cos/sin of the quantised angle | the only trig, outside the step loop |
| Step order | 1 integrate (damping, coulomb, position) 2 pal vs segments then posts 3 pal vs pal in index pairs 4 puddles, pads, pick-up 5 rest check | fixed order = determinism |
| Tunnelling | max move per step at 900 units/s is 7.5, below pal radius 34 | no swept tests needed |

Open-ice slide for one pal (exact integrator, no walls; drift column is the same flick in a snow drift):

| Power | Speed (units/s) | Distance | Time | Distance in drift |
|---|---|---|---|---|
| 0 | 140 | 90 | 1.7 s | 35 |
| 10 | 147.6 | 96 | 1.8 s | 37 |
| 20 | 170.4 | 114 | 1.9 s | 44 |
| 30 | 208.4 | 146 | 2.1 s | 55 |
| 40 | 261.6 | 190 | 2.2 s | 70 |
| 50 | 330 | 248 | 2.4 s | 90 |
| 60 | 413.6 | 320 | 2.6 s | 114 |
| 70 | 512.4 | 405 | 2.8 s | 143 |
| 80 | 626.4 | 505 | 3.0 s | 176 |
| 90 | 755.6 | 618 | 3.2 s | 214 |
| 100 | 900 | 746 | 3.3 s | 256 |

(The quadratic curve gives fine control at low power, where children aim short flicks, and reaches 78 percent of the rink height at full power.)

Events emitted per flick (for sound, haptics, words): WallBump, PalBump, FishCollected, FishBanked, RelayPass, Splash, GateFlipped, DiscScored. Wall bumps are rate-limited by the screen to 8 per second.

### 2.5 Level format (machine readable)
One JSON object per line (JSONL), UTF-8, ids `rink01`..`rink14`, `tgt01`..`tgt06`. Fields (all integers, rink units):
```
id, name, band ("little"|"medium"|"big"), par, seed,
fish:    [[x,y] x3]                       // fish-run rinks
target:  [x,y]                            // bullseye boards only (launchY is always 860)
walls:   [[x1,y1,x2,y2], ...]             // static segments, 14 thick
posts:   [[x,y,r], ...]                   // bumper posts
gates:   [{"id","seg":[x1,y1,x2,y2],"pad":[x,y,r]}, ...]   // closed until the pad flips them
sliders: [{"id","a":[x1,y1,x2,y2],"b":[x1,y1,x2,y2]}, ...] // swap A/B each round
slow:    [[x,y,w,h], ...]                 // snow drifts (rectangles)
puddles: [[x,y,r], ...]
```
Rules for a valid level (enforced by test FR-L1 to FR-L5): everything inside 600 x 960; every fish, pad and bullseye target reachable from the igloo ring in every pose (flood fill with the pal radius plus wall half-thickness as clearance, gates counted as open); corridors at least 80 units wide; no fish within radius + 20 of a puddle or post; the four start spots free.
`par` is the designer's estimate of flicks for all three fish (bank shots allowed); Q4 tightens it with a solver.

**Fish-run rinks (14).** Difficulty rises: obstacles added one family at a time (posts, wall, drifts, zigzag, puddles, gate, slider, maze, two gates, two sliders, everything, bank-shot-only, champion).

| # | id | name | band | par | fish (x,y) | walls | posts | gates (pad) | sliders | drifts | puddles |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | rink01 | Open Ice | little | 6 | (300,420) (150,300) (450,250) | 0 | 0 | 0 | 0 | 0 | 0 |
| 2 | rink02 | Post Parade | little | 8 | (300,200) (100,420) (500,420) | 0 | 3 | 0 | 0 | 0 | 0 |
| 3 | rink03 | Side Door | little | 9 | (100,380) (300,200) (500,300) | 1 | 0 | 0 | 0 | 0 | 0 |
| 4 | rink04 | Snow Drifts | little | 9 | (150,440) (450,270) (300,140) | 0 | 1 | 0 | 0 | 2 | 0 |
| 5 | rink05 | Zigzag | medium | 11 | (100,300) (500,510) (300,160) | 2 | 0 | 0 | 0 | 0 | 0 |
| 6 | rink06 | Puddle Path | medium | 12 | (300,180) (110,300) (490,300) | 0 | 1 | 0 | 0 | 0 | 3 |
| 7 | rink07 | The Gate | medium | 12 | (300,300) (120,200) (480,200) | 2 | 0 | 1 | 0 | 0 | 0 |
| 8 | rink08 | Slide Door | medium | 12 | (150,300) (450,300) (300,160) | 0 | 0 | 0 | 1 | 0 | 0 |
| 9 | rink09 | Pond Maze | medium | 14 | (300,540) (100,380) (500,200) | 3 | 0 | 0 | 0 | 0 | 2 |
| 10 | rink10 | Two Gates | big | 15 | (300,460) (100,260) (500,180) | 4 | 0 | 2 | 0 | 1 | 0 |
| 11 | rink11 | Windmill Walls | big | 15 | (150,480) (450,480) (300,170) | 0 | 3 | 0 | 2 | 0 | 0 |
| 12 | rink12 | Grand Rink | big | 16 | (300,540) (100,330) (500,330) | 2 | 2 | 1 | 1 | 2 | 1 |
| 13 | rink13 | Bank Shot | big | 16 | (300,380) (300,230) (300,130) | 1 | 2 | 0 | 0 | 0 | 0 |
| 14 | rink14 | Champion Rink | big | 18 | (120,560) (500,360) (300,120) | 3 | 2 | 1 | 2 | 1 | 2 |

Level data (the same text is in `FlickRinkLevelData.LEVELS_JSONL`):

```
{"id":"rink01","name":"Open Ice","band":"little","par":6,"seed":1001,"fish":[[300,420],[150,300],[450,250]],"walls":[],"posts":[],"gates":[],"sliders":[],"slow":[],"puddles":[]}
{"id":"rink02","name":"Post Parade","band":"little","par":8,"seed":1002,"fish":[[300,200],[100,420],[500,420]],"walls":[],"posts":[[170,620,26],[430,620,26],[210,330,26]],"gates":[],"sliders":[],"slow":[],"puddles":[]}
{"id":"rink03","name":"Side Door","band":"little","par":9,"seed":1003,"fish":[[100,380],[300,200],[500,300]],"walls":[[0,520,330,520]],"posts":[],"gates":[],"sliders":[],"slow":[],"puddles":[]}
{"id":"rink04","name":"Snow Drifts","band":"little","par":9,"seed":1004,"fish":[[150,440],[450,270],[300,140]],"walls":[],"posts":[[300,580,28]],"gates":[],"sliders":[],"slow":[[40,380,220,140],[340,200,220,140]],"puddles":[]}
{"id":"rink05","name":"Zigzag","band":"medium","par":11,"seed":1005,"fish":[[100,300],[500,510],[300,160]],"walls":[[0,610,400,610],[200,430,600,430]],"posts":[],"gates":[],"sliders":[],"slow":[],"puddles":[]}
{"id":"rink06","name":"Puddle Path","band":"medium","par":12,"seed":1006,"fish":[[300,180],[110,300],[490,300]],"walls":[],"posts":[[300,540,24]],"gates":[],"sliders":[],"slow":[],"puddles":[[300,300,45],[180,500,40],[420,500,40]]}
{"id":"rink07","name":"The Gate","band":"medium","par":12,"seed":1007,"fish":[[300,300],[120,200],[480,200]],"walls":[[0,480,240,480],[360,480,600,480]],"posts":[],"gates":[{"id":"g1","seg":[240,480,360,480],"pad":[110,640,36]}],"sliders":[],"slow":[],"puddles":[]}
{"id":"rink08","name":"Slide Door","band":"medium","par":12,"seed":1008,"fish":[[150,300],[450,300],[300,160]],"walls":[],"posts":[],"gates":[],"sliders":[{"id":"s1","a":[0,520,300,520],"b":[300,520,600,520]}],"slow":[],"puddles":[]}
{"id":"rink09","name":"Pond Maze","band":"medium","par":14,"seed":1009,"fish":[[300,540],[100,380],[500,200]],"walls":[[200,640,600,640],[0,470,400,470],[200,300,600,300]],"posts":[],"gates":[],"sliders":[],"slow":[],"puddles":[[300,340,26],[520,555,34]]}
{"id":"rink10","name":"Two Gates","band":"big","par":15,"seed":1010,"fish":[[300,460],[100,260],[500,180]],"walls":[[0,560,250,560],[350,560,600,560],[0,360,250,360],[350,360,600,360]],"posts":[],"gates":[{"id":"g1","seg":[250,560,350,560],"pad":[80,660,34]},{"id":"g2","seg":[250,360,350,360],"pad":[520,460,34]}],"sliders":[],"slow":[[40,160,180,100]],"puddles":[]}
{"id":"rink11","name":"Windmill Walls","band":"big","par":15,"seed":1011,"fish":[[150,480],[450,480],[300,170]],"walls":[],"posts":[[300,490,28],[150,250,26],[450,250,26]],"gates":[],"sliders":[{"id":"s1","a":[100,630,300,630],"b":[300,630,500,630]},{"id":"s2","a":[300,370,500,370],"b":[100,370,300,370]}],"slow":[],"puddles":[]}
{"id":"rink12","name":"Grand Rink","band":"big","par":16,"seed":1012,"fish":[[300,540],[100,330],[500,330]],"walls":[[0,640,230,640],[370,640,600,640]],"posts":[[150,540,24],[450,540,24]],"gates":[{"id":"g1","seg":[230,640,370,640],"pad":[520,700,34]}],"sliders":[{"id":"s1","a":[0,440,300,440],"b":[300,440,600,440]}],"slow":[[40,160,200,100],[360,160,200,100]],"puddles":[[300,300,40]]}
{"id":"rink13","name":"Bank Shot","band":"big","par":16,"seed":1013,"fish":[[300,380],[300,230],[300,130]],"walls":[[110,500,490,500]],"posts":[[110,300,24],[490,300,24]],"gates":[],"sliders":[],"slow":[],"puddles":[]}
{"id":"rink14","name":"Champion Rink","band":"big","par":18,"seed":1014,"fish":[[120,560],[500,360],[300,120]],"walls":[[0,650,230,650],[370,650,600,650],[200,230,600,230]],"posts":[[300,540,26],[120,380,24]],"gates":[{"id":"g1","seg":[230,650,370,650],"pad":[530,740,32]}],"sliders":[{"id":"s1","a":[0,450,300,450],"b":[300,450,600,450]},{"id":"s2","a":[300,330,450,330],"b":[150,330,300,330]}],"slow":[[300,380,160,60]],"puddles":[[480,560,36],[130,250,34]]}
```

**Bullseye boards (6).** Same schema; target near the top, launch line at y = 860.

```
{"id":"tgt01","name":"Wide Open","band":"little","par":0,"seed":2001,"target":[300,240],"walls":[],"posts":[],"gates":[],"sliders":[],"slow":[],"puddles":[]}
{"id":"tgt02","name":"Two Posts","band":"little","par":0,"seed":2002,"target":[300,240],"walls":[],"posts":[[210,520,26],[390,520,26]],"gates":[],"sliders":[],"slow":[],"puddles":[]}
{"id":"tgt03","name":"Side Wall","band":"medium","par":0,"seed":2003,"target":[300,240],"walls":[[0,560,360,560]],"posts":[],"gates":[],"sliders":[],"slow":[],"puddles":[]}
{"id":"tgt04","name":"Snow Lane","band":"medium","par":0,"seed":2004,"target":[300,220],"walls":[],"posts":[],"gates":[],"sliders":[],"slow":[[40,420,520,120]],"puddles":[]}
{"id":"tgt05","name":"Sliding Door","band":"big","par":0,"seed":2005,"target":[300,240],"walls":[],"posts":[],"gates":[],"sliders":[{"id":"s1","a":[0,540,300,540],"b":[300,540,600,540]}],"slow":[],"puddles":[]}
{"id":"tgt06","name":"Gate Keeper","band":"big","par":0,"seed":2006,"target":[300,240],"walls":[[0,540,240,540],[360,540,600,540]],"posts":[],"gates":[{"id":"g1","seg":[240,540,360,540],"pad":[110,700,34]}],"sliders":[],"slow":[],"puddles":[]}
```
(Bullseye boards are not yet in the Kotlin data file; phase 5 adds them to a second constant next to `LEVELS_JSONL`.)

### 2.6 Bot behaviour per level
The computer pal runs on a worker thread, never blocks the UI, and shows a "thinking" pose for at least the listed time. It sees the true state (no hidden information in this game). Noise comes from a seeded stream `SplitMix64(seed xor (turnNumber * 0x9E3779B97F4A7C15))`, so a replay plays the same match.

Target choice (Fish run): if carrying, the igloo ring centre; else the nearest uncarried fish by straight distance, preferring one it has a clear line to. Co-op helper pals also: when the human carrier is farther from home than the bot is, aim to bump the carrier (relay hand-off), otherwise as above. Closed gate in the way: go to its pad first (Medium and Hard).
Power choice: invert the open-ice distance table: power = 100 * sqrt((d * 1.10 - 140) / 760), clamped 0..100, where d is the wanted rest distance (to the fish plus 90 for pick-ups when passing through is fine, exactly to the ring centre when banking).
Candidates are evaluated with the real simulation (`record = false`): value = +1000 bank, +400 pick up, +300 on pad that opens a needed gate, -200 splash, -60 resting in a drift, minus distance from rest to the goal.

| Level | Candidates | Angle noise (sigma) | Power noise (sigma) | Bank shots | Gates and pads | Thinks at least | Step budget |
|---|---|---|---|---|---|---|---|
| Easy | 1 direct line; 15 percent of turns flick toward a random point in the fish's half-plane | 9.0 degrees | 14 points | no | no (tries the direct line even if a gate is shut) | 0.8 s | 0 |
| Medium | direct + 24 bounce candidates (offsets of 10, 20, 30, 45, 60, 90 degrees each side, 2 powers) | 4.0 degrees | 7 points | one bounce | yes | 1.2 s | 40 000 |
| Hard | 160: 36 angles x 4 powers + 16 targeted refinements | 1.5 degrees | 3 points | yes | yes, plans pad then fish | 1.6 s | 150 000 |

Bullseye bots: target = target centre; candidates scored by (own points - best rival points) after simulating the throw over the discs already down; Hard may knock a rival disc out of the rings; same noise table; the launch x is chosen from {120, 300, 480} (Easy always 300).
Every returned input is clamped to angle 0..3599 and power 0..100 (test FR-B5).

### 2.7 Handicap rule
Per-seat **aim helper**: `assistFor` (none, all, p1 to p4) and `assist` length short 160, medium 320, long 560 units. The helper draws a dashed guide line from the pal in the flick direction, never longer than the length and never longer than the slide would actually travel at the chosen power; the long helper adds one wall bounce. The line is drawn while dragging and in button/switch aiming. It is purely visual: the physics is unchanged, so it can never be accused of cheating. Default none; the Hub remembers the last choice. Recommended for a 5-year-old next to an adult: kid seat Medium, adult none. There is no "extra tile draw" in this game (that is Egg Garden's handicap).

### 2.8 First-run tutorial script
Shown once per profile as 7 pictorial cards (picture first, line second, 56 dp Next button, Skip always visible). All lines under 120 characters (test FR-H5).
1. `flick_rink_tut_1` "Meet your puck pal! Drag back, then let go to flick it." (shows a pal and a finger dragging back)
2. `flick_rink_tut_2` "The farther you drag, the harder the flick." (short and long drags)
3. `flick_rink_tut_3` "Slide over the fish to pick it up." (the pal rides onto the fish)
4. `flick_rink_tut_4` "Now flick home and stop inside the igloo ring." (ring highlights)
5. `flick_rink_tut_5` "Walls bounce you. Puddles splash you back. Bumping friends is fine!" (wall, puddle, two pals)
6. `flick_rink_tut_6_turns` "Take turns. Bring three fish home to win the rink!" or `flick_rink_tut_6_coop` "Bring three fish home together before the flicks run out!"
7. `flick_rink_tut_7` "You are ready! Tap Play."
The first rink of a first-run match is forced to rink 1 with the aim helper on Long for the first two flicks (adult can dismiss).

### 2.9 Exact EN strings (keys)
All keys are in `res/values/flick_rink_strings.xml` (EN) and `res/values-es/flick_rink_strings.xml` (real Spanish values). Placeholders: `%1$s` name, `%1$d`/`%2$d` numbers.

| Key | EN text |
|---|---|
| `flick_rink_title` | Flick Rink |
| `flick_rink_help_title` | How to play Flick Rink |
| `flick_rink_help_text` | Drag back and let go to flick your puck pal across the ice. Slide over the fish to pick it up, then flick home and stop inside the igloo ring to bank it. Walls bounce you, puddles splash you back to where you started, and bumping friends is fine. Take turns until three fish are home. |
| `flick_rink_tut_1` | Meet your puck pal! Drag back, then let go to flick it. |
| `flick_rink_tut_2` | The farther you drag, the harder the flick. |
| `flick_rink_tut_3` | Slide over the fish to pick it up. |
| `flick_rink_tut_4` | Now flick home and stop inside the igloo ring. |
| `flick_rink_tut_5` | Walls bounce you. Puddles splash you back. Bumping friends is fine! |
| `flick_rink_tut_6_turns` | Take turns. Bring three fish home to win the rink! |
| `flick_rink_tut_6_coop` | Bring three fish home together before the flicks run out! |
| `flick_rink_tut_7` | You are ready! Tap Play. |
| `flick_rink_status_your_turn` | %1$s, your turn! |
| `flick_rink_status_drag_hint` | Drag back, then let go. |
| `flick_rink_status_sliding` | Sliding... |
| `flick_rink_status_got_fish` | %1$s got the fish! |
| `flick_rink_status_banked` | %1$s brought the fish home! |
| `flick_rink_status_splash` | Splash! Back to the start. |
| `flick_rink_status_bump` | Bump! |
| `flick_rink_status_gate_open` | A gate opened! |
| `flick_rink_status_gate_closed` | A gate closed! |
| `flick_rink_status_walls_moved` | The walls moved! |
| `flick_rink_status_relay_pass` | The fish hopped to %1$s! |
| `flick_rink_status_fish_count` | Fish %1$d of %2$d |
| `flick_rink_status_flicks_left` | Flicks left: %1$d |
| `flick_rink_status_rink_of` | Rink %1$d of %2$d |
| `flick_rink_status_rink_done` | Rink done! |
| `flick_rink_status_coop_win` | We did it! |
| `flick_rink_status_coop_lose` | Try again together |
| `flick_rink_status_bonus_flicks` | Bonus flicks: %1$d |
| `flick_rink_card_title` | Score card |
| `flick_rink_card_fish` | Fish |
| `flick_rink_card_points` | Points |
| `flick_rink_card_stars` | Stars: %1$d of 3 |
| `flick_rink_card_flicks` | Flicks used: %1$d |
| `flick_rink_card_winner` | %1$s wins! |
| `flick_rink_card_tie` | Everybody wins this one! |
| `flick_rink_btn_play_again` | Play again |
| `flick_rink_btn_next_rink` | Next rink |
| `flick_rink_btn_try_again` | Try again together |
| `flick_rink_btn_done` | All done |
| `flick_rink_btn_flick` | Flick! |
| `flick_rink_btn_skip` | Skip the slide |
| `flick_rink_btn_left` | Turn left |
| `flick_rink_btn_right` | Turn right |
| `flick_rink_btn_stronger` | Stronger |
| `flick_rink_btn_softer` | Softer |
| `flick_rink_switch_lock_aim` | Tap to lock the aim |
| `flick_rink_switch_lock_power` | Tap to lock the power |
| `flick_rink_switch_flick` | Tap to flick! |
| `flick_rink_a11y_pal` | %1$s puck pal, %2$s. |
| `flick_rink_a11y_where_home` | at home |
| `flick_rink_a11y_where_ice` | on the ice |
| `flick_rink_a11y_where_carry` | carrying a fish |
| `flick_rink_a11y_fish_dir` | The fish is %1$s. |
| `flick_rink_dir_up` | straight up |
| `flick_rink_dir_up_left` | up and to the left |
| `flick_rink_dir_up_right` | up and to the right |
| `flick_rink_dir_left` | to the left |
| `flick_rink_dir_right` | to the right |
| `flick_rink_dir_down` | straight down |
| `flick_rink_dir_down_left` | down and to the left |
| `flick_rink_dir_down_right` | down and to the right |
| `flick_rink_a11y_aim_state` | Aim %1$d degrees, power %2$d percent. |
| `flick_rink_bullseye_title` | Bullseye |
| `flick_rink_bullseye_board` | Board %1$d of %2$d |
| `flick_rink_bullseye_disc` | Disc %1$d of %2$d |
| `flick_rink_bullseye_points` | Points: %1$d |
| `flick_rink_bullseye_bull` | Bullseye! |
| `flick_rink_bullseye_miss` | Not on the target |

Hub texts (key computed by `hubKey(text)`; defined in the same file unless already present elsewhere):

| Hub key | EN text |
|---|---|
| `hub_flick_rink` | Flick Rink |
| `hub_flick_your_puck_pal_across_the_ice_for_fish` | Flick your puck pal across the ice for fish. |
| `hub_race_1_to_3_computer_pals_to_bring_home_the_fish` | Race 1 to 3 computer pals to bring home the fish. |
| `hub_take_turns_on_one_screen_computer_pals_can_join_a42dbc` | Take turns on one screen. Computer pals can join in. |
| `hub_co_op_relay` | Co-op Relay |
| `hub_work_as_one_team_to_bring_home_every_fish` | Work as one team to bring home every fish. |
| `hub_solo_practice` | Solo Practice |
| `hub_flick_at_your_own_speed_and_earn_stars` | Flick at your own speed and earn stars. |
| `hub_fish_run` | Fish run |
| `hub_collect_fish_and_bring_them_home` | Collect fish and bring them home. |
| `hub_bullseye` | Bullseye |
| `hub_flick_discs_onto_the_target` | Flick discs onto the target. |
| `hub_rink_size` | Rink size |
| `hub_how_tricky_the_rinks_are` | How tricky the rinks are. |
| `hub_little_rinks` | Little rinks |
| `hub_few_obstacles_and_wide_friendly_spaces` | Few obstacles and wide, friendly spaces. |
| `hub_medium_rinks` | Medium rinks |
| `hub_big_rinks` | Big rinks |
| `hub_gates_sliding_walls_and_puddles_for_practised_fl_755bbc` | Gates, sliding walls and puddles for practised flickers. |
| `hub_quick_2_rinks` | Quick: 2 rinks |
| `hub_standard_3_rinks` | Standard: 3 rinks |
| `hub_long_5_rinks` | Long: 5 rinks |
| `hub_aim_helper_for` | Aim helper for |
| `hub_give_some_players_a_guide_line_so_different_ages_5fa4b7` | Give some players a guide line so different ages can play together. |
| `hub_no_one` | No one |
| `hub_guide_line_length` | Guide line length |
| `hub_short` | Short |
| `hub_long` | Long |
| `hub_relaxed_flicking` | Relaxed flicking |
| `hub_bigger_igloo_and_fish_gentler_bounces_and_no_fli_45a02b` | Bigger igloo and fish, gentler bounces and no flick limit. |
| `hub_drag_anywhere` | Drag anywhere |
| `hub_pull_back_from_anywhere_on_the_ice_not_only_from_4e81f5` | Pull back from anywhere on the ice, not only from your pal. |
| `hub_switch_aiming` | Switch aiming |
| `hub_one_tap_locks_the_aim_one_locks_the_power_one_fl_979b0f` | One tap locks the aim, one locks the power, one flicks. |
| `hub_computer_pal_level` | Computer pal level |
| `hub_team_flicks` | Team flicks |
| `hub_how_many_flicks_the_team_shares_on_each_rink` | How many flicks the team shares on each rink. |
| `hub_plenty` | Plenty |
| `hub_rink_number` | Rink number |
| `hub_pick_a_rink_from_1_to_14` | Pick a rink from 1 to 14. |
| `hub_play_style` | Play style |
| `hub_taking_turns` | Taking turns |
| `hub_playing_together` | Playing together |
| `hub_practising` | Practising |

## 3. SCALE

### 3.1 Length by mode (target 5 to 12 minutes)
Assumption (estimate, unmeasured): a flick takes 8 s on average (3 s aim, 3 s slide, 2 s banners and hand-over). A rink takes 14 flicks in total for 2 players and about 18 for 4 players.

| Mode | Players | Quick (2) | Standard (3) | Long (5) |
|---|---|---|---|---|
| Turns, Fish run | 2 | about 4 min | 6 to 7 min | 10 to 11 min |
| Turns, Fish run | 4 | about 5.5 min | 8 to 9 min | 12 min |
| Co-op Relay | 2 to 4 | 4 to 5 min | 6 to 8 min | 10 to 12 min |
| Solo Practice (one rink) | 1 | about 2 min per rink | | |
| Bullseye (Turns) | 2 to 4 | 3 to 4 min | 5 to 6 min | 9 to 10 min |
Hard ceiling in Turns: 3 rinks x 24 flicks x 8 s = 9.6 min.

### 3.2 Player counts
Turns 2 to 4 seats (people and computer pals, Hub steppers); Co-op 2 to 4 seats; Solo 1. A seat is one pal; max 4 pals plus 12 discs in Bullseye.

### 3.3 Deterministic seeds and replay
- One 64-bit `seed` per match (from the injected `Random`), a `SplitMix64` stream inside the engine (not `kotlin.random`, whose algorithm is not contractually frozen). Streams: bot noise, tie-breaks, none in the physics (the physics has no randomness: same inputs, same outputs).
- A flick is fully described by `(rink, seat, angleDeci, power)`. A **replay** is `(config, seed, flick log)`; the engine can rebuild any turn, and the state hash after each turn must match (test FR-D3).
- State hash: FNV-1a 64 over positions rounded to 1/16 unit, fish and carrier, gate and slider poses, per-seat scores and flick counts, retry count, RNG state. Frame buffers are excluded (FR-D6).
- Float determinism: Double arithmetic only, no `Math.fma`, no `pow`/`exp` in the loop (the power curve is integer math), angle trig through `StrictMath` before the loop. JVM 17 doubles are strict IEEE; ART does not fuse multiply-adds in Java code. Residual risk R1.

### 3.4 Performance budget
| Item | Budget |
|---|---|
| Simulation | fixed 120 Hz, run ahead of time: one flick = up to 900 steps in one call |
| Cost per flick | about 900 steps x (12 bodies x 24 segments + 66 pair tests) = 0.4 M tests, target under 8 ms on a mid phone (estimate); runs off the UI thread for bots |
| Rendering | 60 or 120 Hz display interpolates the recorded 60 Hz frames; no physics in the draw loop, so display refresh cannot change an outcome (FR-D2 proves this with a live-step harness) |
| Bodies | up to 4 pals + 12 discs dynamic (never both); static: at most 24 segments, 4 posts, 4 pads, 4 puddles, 2 sliders |
| Allocation | zero inside the step loop (primitive DoubleArrays preallocated per match, FR-D5); outcome frames at most 450 frames x 24 floats = 43 KB, allocated once per flick only when `record` is true |
| Bot | Hard at most 150 000 steps (about 120 ms estimate), Medium 40 000, Easy 0; always off the main thread |
| Memory | under 1 MB for the engine; art is vector Canvas, no bitmaps required for MVP |

### 3.5 Resume and persistence
Snapshot = `FlickSave` JSON: settings, seed, roster ids, retry count and the flick log (a whole match is under 2 KB, at most 400 entries); `physicsVersion` and `stateHash` guard against drift. `restore` replays the log from the seed, compares the hash and returns false (engine untouched) on any mismatch, wrong roster or invalid field (list in the KDoc of `FlickSaveCodec.isValid`). `snapshot()` returns null before start, after the match, and in non-resumable modes. Per-profile solo stars are stored by the profiles foundation (not in this package). Resume lands at the start of the interrupted turn in AIMING (a flick in progress is never saved).

### 3.6 Multiplayer plan
- **Now: same screen only.** `PlayMode.SINGLE_DEVICE_PASS_AND_PLAY` and `SINGLE_PLAYER_VS_BOT`; the spec has no Nearby or Online mode and must not be listed in `networkedGames`.
- **Later (Nearby, then Online relay): host-authoritative.** A guest sends `{seat, angleDeci, power, turnNumber}`; the host checks phase, seat (using the transport's authenticated `fromPlayerId`, never an id in the payload: the identity-hardening lesson of PR #9), range and turn number, simulates, then broadcasts `{input, stateHash}`. Guests re-simulate for the visuals and compare the hash; on mismatch they request a resync (final positions) and the screen shows a polite "catching up" pulse. Messages are about 16 bytes and about 100 per match, so latency does not matter (turn-based).
- **Why not lockstep first:** it needs bit-identical floats on every SoC; the host-authoritative design tolerates a tiny divergence. If lockstep is ever wanted, port the step loop to Q16.16 fixed point behind `PHYSICS_VERSION = 2`.
- Co-op over the network works the same (all seats one team).

### 3.7 Phased delivery
| Phase | Content | Effort |
|---|---|---|
| 0 | Level parser + validator + the generator check promoted to a unit test; solver for pars (Q4) | S |
| 1 | Engine: physics, rules, state hash, save/restore, replay; tests FR-D, FR-P, FR-R, FR-S | M |
| 2 | Screen: rink Canvas, puck pal and fish art, drag/button/switch input, five UI states, tutorial, EN + ES wiring | M |
| 3 | Bots (3 levels) + Hard budget tuning; tests FR-B | M |
| 4 | Hub and shell integration (section 4.4), profiles stars, accessibility review pass | S |
| 5 | Bullseye boards + co-op Bullseye + balance pass | M |
| 6 | Sound, haptics, polish, TalkBack live pass on device | S |
| Later | Nearby host-authoritative; rink builder; fixed-point physics | L |

### 3.8 Risk register
| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | Float results differ between devices | desync in Nearby, save mismatch | StrictMath/no fma, hash-checked host-authoritative, physicsVersion gate |
| R2 | A 5-year-old cannot aim | frustration, uninstalls | drag-anywhere, aim helper, Relaxed, big pick-up radius, co-op, button aiming |
| R3 | Pals end overlapping or wedged | stuck rink | push-out correction in step 2 and 3, 900 step cap, safety valve at 60 flicks |
| R4 | Par values wrong | unfair stars and budgets | Q4 solver test; budgets use generous multipliers |
| R5 | Look-alike of an existing product | legal | original art checklist (4.5), no hall-pass or school motif, our own names; legal review of final art |
| R6 | Bot cost on slow phones | jank | worker thread, step budgets, Easy needs none |
| R7 | Many bounces make slides chaotic | feels random | 0.80 restitution, 1.10 damping, 3.3 s typical slide; helper line |
| R8 | String keys clash with parallel work | build break (duplicate resources) | all keys prefixed `flick_rink_`; hub keys checked against every other file by FR-H4; gate runs the whole test suite |
| R9 | Hub drops hidden `style` default | wrong mode | FR-H6; fallback to three routes |
| R10 | Scope creep into a physics sandbox | schedule | the out-of-scope list in 1.4 |

### 3.9 Test plan
All 50 tests exist as `@Ignore` stubs under `app/src/test/java/com/gamesuite/games/flickrink/` (8 classes) so the gate stays green; each is enabled when its phase lands.

Physics determinism and replay (property style): same inputs same trajectory over 500 random flicks per level; live-step harness at 60 and 120 Hz accumulators ends in the same hash; replay of 200 random matches reproduces every per-turn hash; no allocation after warm-up.
Physics properties: energy never grows without a bump; every flick of 14 levels x 72 angles x 6 powers settles; no resting overlap with walls; no tunnelling at max power; restitution and momentum checks; drift distance; puddle, pad and slider rules.
Rules: pick-up and bank thresholds, rink end, co-op budget and retry cap, relay hand-off, solo stars, bullseye rings and tie-breaks, turn tie-breaks, helper only on chosen seats.
Level validation: parse, reachability in every pose, difficulty monotone by band, hazards, corridor width.

- `FR-D1` (FlickRinkDeterminismTest) `samePhysicsInputsGiveByteIdenticalTrajectories`: same level, seed and flick log produce identical frames on two engine instances.
- `FR-D2` (FlickRinkDeterminismTest) `simulatedStepsDoNotDependOnDisplayRefreshRate`: driving the sim from a 60 Hz and a 120 Hz render loop ends in the same state hash.
- `FR-D3` (FlickRinkDeterminismTest) `replayingTheFlickLogReproducesTheStateHash`: recorded flicks replayed from the seed give the same hash at every turn.
- `FR-D4` (FlickRinkDeterminismTest) `botNoiseIsSeededSoTheSameSeedPlaysTheSameMatch`: same seed means the same bot flicks; a different seed differs.
- `FR-D5` (FlickRinkDeterminismTest) `stepLoopAllocatesNothingAfterWarmup`: allocation counter stays flat across 10 000 sim steps.
- `FR-D6` (FlickRinkDeterminismTest) `stateHashIgnoresTheRenderFrameBuffer`: recording frames on or off never changes the hash.
- `FR-P1` (FlickRinkPhysicsTest) `speedNeverGrowsWithoutAWallPostOrPalBump`: a lone pal on open ice only ever loses speed.
- `FR-P2` (FlickRinkPhysicsTest) `everyFlickSettlesWithinMaxSteps`: all 14 levels x 72 angles x 6 powers settle within 900 steps.
- `FR-P3` (FlickRinkPhysicsTest) `palNeverEndsInsideAWallOrPost`: no resting pal overlaps static geometry at max power.
- `FR-P4` (FlickRinkPhysicsTest) `maxSpeedNeverTunnelsThroughThinWalls`: speed 900 units per second cannot cross a 14 unit wall.
- `FR-P5` (FlickRinkPhysicsTest) `powerCurveIsMonotoneAndHitsBothEndpoints`: speedForPower(0)=140, speedForPower(100)=900, strictly rising.
- `FR-P6` (FlickRinkPhysicsTest) `wallBounceUsesTheWallRestitution`: normal speed after a wall hit is 0.80 of the incoming normal speed.
- `FR-P7` (FlickRinkPhysicsTest) `palPalCollisionConservesMomentumWithRestitution`: two equal pals: momentum kept, relative speed scaled by 0.92.
- `FR-P8` (FlickRinkPhysicsTest) `snowDriftStopsAPalSoonerThanOpenIce`: same flick travels at most half as far in a drift.
- `FR-P9` (FlickRinkPhysicsTest) `puddleSendsThePalBackToTurnStartAndKeepsTheFish`: splash event, pal at its turn-start spot, carrying unchanged.
- `FR-P10` (FlickRinkPhysicsTest) `gatePadTogglesItsGateOncePerEntry`: staying on the pad does not flip the gate again.
- `FR-P11` (FlickRinkPhysicsTest) `slidersSwapPoseOnlyAtTheEndOfARound`: mid-round slides never move a slider.
- `FR-R1` (FlickRinkRulesTest) `fishIsPickedUpWhenTheCentreIsInsidePickupRadius`: 58 units normal, 87 relaxed.
- `FR-R2` (FlickRinkRulesTest) `bankingNeedsAStopInsideTheHomeRingWhileCarrying`: moving through the ring does not bank; stopping inside does.
- `FR-R3` (FlickRinkRulesTest) `aRinkEndsAtThreeFishOrWhenTheFlickCapIsUsed`: cap is 12, 8 and 6 flicks each for 2, 3 and 4 players.
- `FR-R4` (FlickRinkRulesTest) `coopBudgetIsParTimesTheBandMultiplierRoundedUp`: 2.0 little, 1.6 medium, 1.3 big.
- `FR-R5` (FlickRinkRulesTest) `coopRetryAddsTwoFlicksCappedAtSix`: third failed try gives +6, a fourth stays at +6.
- `FR-R6` (FlickRinkRulesTest) `relayBumpHandsTheFishToTheBumpedPal`: fish moves to the pal that was hit; only in co-op.
- `FR-R7` (FlickRinkRulesTest) `soloStarsFollowTheParThresholds`: 3 stars at ceil(par*1.25), 2 stars at ceil(par*1.75).
- `FR-R8` (FlickRinkRulesTest) `bullseyeRingPointsAndTieBreaks`: 5, 3, 1, 0 by centre distance; ties by bullseyes then closest disc then shared.
- `FR-R9` (FlickRinkRulesTest) `turnsWinnerAndTieAreSharedFairly`: most fish, then fewest flicks, then shared win.
- `FR-R10` (FlickRinkRulesTest) `aimAssistOnlyAffectsTheChosenSeats`: preview line length is 0 for seats that did not get the helper.
- `FR-L1` (FlickRinkLevelsTest) `allFourteenLevelsParseAndValidate`: JSONL parses, ids rink01..rink14, three fish each.
- `FR-L2` (FlickRinkLevelsTest) `everyFishAndPadIsReachableInEveryGateAndSliderState`: grid flood fill from home in every pose.
- `FR-L3` (FlickRinkLevelsTest) `difficultyRisesWithBandAndPar`: par is non-decreasing across the band boundaries.
- `FR-L4` (FlickRinkLevelsTest) `noFishOrStartOverlapsAHazard`: no fish within radius + 20 of a puddle or post.
- `FR-L5` (FlickRinkLevelsTest) `corridorsAreAtLeastEightyUnitsWide`: pal diameter 68 plus a 12 unit margin everywhere on the route.
- `FR-B1` (FlickRinkBotTest) `botIsDeterministicForTheSameSeed`: same state and seed give the same input.
- `FR-B2` (FlickRinkBotTest) `easyBotAimErrorMatchesTheNoiseTable`: measured angle error std dev within 15 percent of 9 degrees.
- `FR-B3` (FlickRinkBotTest) `hardBotClearsEveryLevelWithinTwiceParInFiftySeeds`: success rate at least 90 percent.
- `FR-B4` (FlickRinkBotTest) `botDecisionStaysInsideItsStepBudget`: at most 150 000 sim steps for Hard.
- `FR-B5` (FlickRinkBotTest) `botNeverReturnsOutOfRangeInput`: angle 0..3599 and power 0..100 always.
- `FR-S1` (FlickRinkPersistenceTest) `snapshotRestoreRoundTripsTheStateHash`: restore then hash equals hash before snapshot.
- `FR-S2` (FlickRinkPersistenceTest) `restoreRejectsAWrongRosterOrPhysicsVersion`: returns false and leaves the engine untouched.
- `FR-S3` (FlickRinkPersistenceTest) `aCorruptSnapshotNeverThrows`: random bytes and truncated JSON return false.
- `FR-S4` (FlickRinkPersistenceTest) `snapshotIsNullOnceTheMatchIsOver`: also null before start and in a non-resumable mode.
- `FR-H1` (FlickRinkHubSpecsTest) `hubSpecIsKidsAudienceMinAgeFiveAndNotNetworked`: audience KIDS, minAge 5, no NEARBY or ONLINE mode.
- `FR-H2` (FlickRinkHubSpecsTest) `everyHubTextHasAnEnglishAndASpanishString`: hubKey(text) exists in both locales.
- `FR-H3` (FlickRinkHubSpecsTest) `ourStringsMatchPlaceholdersInBothLocales`: the same numbered placeholders in EN and ES.
- `FR-H4` (FlickRinkHubSpecsTest) `noStringKeyClashesWithAnotherFile`: our keys are defined only in flick_rink_strings.xml.
- `FR-H5` (FlickRinkHubSpecsTest) `tutorialLinesAreUnderOneHundredTwentyCharacters`: every flick_rink_tut_* in EN and ES.
- `FR-H6` (FlickRinkHubSpecsTest) `hubLaunchOptionsReachTheGameAsExtras`: style, game, band, length, assist and relaxed arrive in LaunchOptions.extras.
- `FR-A1` (FlickRinkAccessibilityTest) `oneSwitchSweepLocksAimThenPowerThenFlicks`: three taps produce one legal flick.
- `FR-A2` (FlickRinkAccessibilityTest) `buttonAimReachesEveryAngleAndPower`: turn buttons step 5 degrees, power buttons step 10 percent.
- `FR-A3` (FlickRinkAccessibilityTest) `reducedMotionSkipsPlaybackWithTheSameOutcome`: skipping the slide gives the identical state hash.

## 4. SCAFFOLD

### 4.1 Files created (all new, nothing shared touched)
| Path | What |
|---|---|
| `app/src/main/java/com/gamesuite/games/flickrink/FlickRinkModels.kt` | enums (style, game kind, band, length, aim assist, phase), config, seat, level format data classes, state, events, outcome |
| `.../FlickRinkRules.kt` | every constant of 2.4 plus pure helpers: power curve, drag to power, caps, co-op budget, retry bonus, solo stars, bullseye points, helper seats |
| `.../FlickRinkEngine.kt` | `FlickRinkLevels` (stub parser), `FlickRinkEngine` interface (full contract in KDoc), `StubFlickRinkEngine`, `FlickRinkBot`, `StubFlickRinkBot` |
| `.../FlickRinkSave.kt` | snapshot DTOs (`FlickSave`, `FlickLogEntry`, `FlickSeatSave`), `FlickSaveCodec` with the validation TODO list in KDoc |
| `.../FlickRinkGame.kt` | `GameModule` + `ResumableGame` wrapper in the BridgeBuildersGame style, engine injected |
| `.../FlickRinkHubSpecs.kt` | the Hub spec: `audience = GameAudience.KIDS`, `minAge = 5`, four modes, options; NOT registered |
| `.../FlickRinkLevelData.kt` | the 14 rinks as JSONL |
| `app/src/main/res/values/flick_rink_strings.xml`, `values-es/flick_rink_strings.xml` | 69 screen strings + hub texts, EN and real ES |
| `app/src/test/java/com/gamesuite/games/flickrink/*.kt` | 8 classes, 50 `@Ignore` stubs |

### 4.2 What is real and what is stubbed
Real: enums, data classes, constants, pure helpers (power curve, caps, budget, stars, bullseye points), save codec encode/decode with version gates, hub spec, level data, strings. Stubbed: the engine (`StubFlickRinkEngine` returns safe empty values, `configure` returns false), the bot (straight up at 50), the level parser (empty list). Tests are all ignored.

### 4.3 Key decisions
1. Whole-flick precompute instead of live stepping: bots, skip, replay and tests all use one code path; the display rate cannot influence outcomes.
2. Own tiny physics (circles vs segments) instead of a library: needed for determinism and to match the repo's curling/carrom style.
3. Saves are replay logs with a hash.
4. Hidden `style` hub option selects Turns/Co-op/Solo on one route (Q10).
5. Each seat's helper is visual only.
6. Co-op uses retry bonuses, never a "game over".

### 4.4 Integration notes for the coordinator (not done here)
- `GameHubSpecs.baseSpecs()`: add `FlickRinkHubSpecs.spec` (family ARCADE_PUZZLE). Do not add to `networkedGames`.
- `GameCatalog`: glyph `"flick-rink" -> "🐧"` (`FlickRinkHubSpecs.GLYPH`).
- `GameTheme` accents: `"flick-rink" to GameAccent(0xFF00607A, 0xFF7CD4EC)` (proposed; contrast not yet verified, GameThemeTest decides).
- `MainActivity`: route `flick-rink` with `rememberActiveModule(sessionManager, "flick-rink") { FlickRinkGame() }` and a screen that reads `LaunchOptions.extras` (keys listed in the KDoc of `FlickRinkHubSpecs`).
- `GameTips` and `GameCoach`: three tips and a five-step coach card (the tutorial lines of 2.8 can be reused); the tip literals must equal resource strings.
- `GameAudienceTable`: no row needed (the spec's own tags win); add `put("flick-rink", k(5))` only if `GameAudienceTest` insists.
- Gate: `./gradlew :app:testDebugUnitTest` (HubLocalizationTest, GameThemeTest and the audience tests iterate over every spec); the duplicate-resource check matters because other agents are adding strings in parallel.

### 4.5 Original-art checklist
Puck pal: round blob body, one big belly badge (circle/triangle/square/star) in the player colour, two tiny feet, no tuxedo split, no beak shape taken from any product; igloo ring: dashed circle with a small snow-block igloo drawn in the corner; fish: simple teardrop with a stripe; walls: blue-white ice blocks; gates: a striped bar with a numbered pad; sliders: arrows on a rail; puddle: dark-blue ellipse with ripple lines; background: warm cream (Baloo type, the kids theme) with a pale-blue ice rink inset. No school or hallway motif. No names or phrases from other products appear in any string.
