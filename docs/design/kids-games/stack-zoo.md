# Stack Zoo (`stack-zoo`)

Status 2026-11-07, branch `session/wave-3`. Scope, script, scale and scaffold package. **Nothing in this package has been built, compiled or run by Gradle, an emulator or a device.** The only things executed were throwaway Python scripts (outside the repo) that generated the animal tables below (convex hulls, areas, centres of mass) and checked that every part is convex and counter-clockwise. The physics numbers in 2.4 are design values taken from closed-form estimates (spring period, damping ratio); they have NOT been simulated, and milestone M0 exists to tune them (risk K1).

Legal rule (hard): every name, shape, art, rule text and layout here is original. Only the mechanics of the genre are reused (take turns placing animal-shaped pieces on a precarious base without toppling). No name, artwork, rulebook wording or component shape of Animal Upon Animal or any publisher's product is used, copied or traced. The 20 animals are low-poly geometric compositions that we authored as point lists; species names are common words and are not protectable (open question Q2 asks legal to confirm the roster).

---

## 1. SCOPE

### 1.1 Purpose
A calm, wobbly dexterity game that turns the best-loved tabletop family (stacking and balancing, the biggest family in the research list: 7 of the top 25 games) into a touch game. Players drag an animal from the shelf onto a rocking log and let go. A small physics model decides whether the tower stands. Three ways to play with the same 20 animals:
- **Co-op Tower** (1 to 4 seats): everyone shares a goal flag. Pick one of three animals each turn and stack up to the flag together. "We did it!" or "Try again together".
- **Take Turns** (2 to 4 seats, helpers fill): each player has a hand of animals. Place one per turn. If animals fall, you take them back. First to place all of theirs wins; fewest animals left is the rank.
- **Solo Balance** (1 seat): stack as many animals as you can with three tries.

The long tail matters (the research notes this family is still played at 9 and by adults): Take Turns on Many animals with Hard helpers is a real challenge for a grown-up.

### 1.2 Audience and adult + child fairness
- Ages 4+ (`audience = KIDS`, `minAge = 4`), 1 to 4 players, same screen. No reading needed.
- Fairness levers (same wording as the other kids games):
  1. **Bigger snap help for** (handicap): none / all players / First to Fourth player. The chosen seat's drop is nudged toward a steadier spot and its animal turns to lie flat (2.6). A grown-up plays without it, a child with it.
  2. **Tower goal** in Co-op (Little tower 4 u and 12 animals, Medium 6 u and 16, Tall 8 u and 20).
  3. **Helper level** (Easy helpers blunder about one turn in five, so a child wins about half the games).
  4. **Relaxed wobble**: steadier log, grippier animals, fallen animals float back for free.
  5. Take Turns caps the penalty at 3 animals so one bad drop never ends a child's game.

### 1.3 MVP vs v1 vs later
| Stage | Content |
|---|---|
| MVP (M0 to M3) | Physics core (deterministic, compound convex polygons), 12 animals, Solo Balance and Co-op Tower, same screen, snap assist, Relaxed, five UI states, tutorial, EN + ES, resume, `@Ignore`d test plan un-ignored for determinism, geometry, physics and rules |
| v1 (M4 to M6) | All 20 animals, Take Turns with helpers at three levels, one-switch placing, TalkBack announcements, parent-dashboard line, personal best for Solo |
| Later | Nearby lockstep, new bases (curved boat, two logs), wind or weather modifiers, themed animal packs, photo-free "draw an animal" packs (needs a safety review) |

### 1.4 Out of scope
- Online play, chat, accounts, ads, in-app purchases, analytics.
- Real rigid-body engine libraries (JBox2D and friends): we write a small purpose-built solver so that determinism and allocation are under our control (2.4).
- Camera or photo input, tilt-the-phone controls (the whole screen is the table; shaking is not an input).
- Real-time Nearby in this round (needs the lockstep design in 3.6).
- Freehand rotation by 360 degree gestures on tiny phones: rotation is by two fingers or by two big buttons (2.5).

### 1.5 Safety and age notes
- No flashing; the log's wobble is the game and is slow (about 1.6 s period). With Reduced Motion the drawn wobble is shown at half amplitude and the camera never shakes (the physics is unchanged, so results are the same).
- Falling animals are never hurt: they splash into a pond and pop back up as a balloon that floats to the shelf; sounds are soft "boing" and "plop", never a crash or a buzzer.
- No loser language: "Oops! One fell", "Try again together"; Take Turns ranks by animals left and shows everyone's count.
- Multi-touch safety: only the active seat's finger drags; the other seats' touches are ignored during a turn; palm rejection reuses `ContactSizes` and `TapTugPointerTracker.maxContactPx` (palm-size contacts ignored for good).
- Name entry reuses `PlayerNamesDialog` and the shell filter; no personal data stored by this game.

### 1.6 Accessibility
- Targets: shelf animals are at least 72 dp tall; the Turn left and Turn right buttons are 56 dp; the drag handle follows the finger with the animal drawn 56 dp above it so the finger never hides it.
- Shape + colour: each animal has a unique silhouette and a body colour token; seat identity is shape + colour (circle, square, triangle, star); the wobble alert is a triangle marker plus a colour change on the log's pivot (never colour only).
- TalkBack: each shelf animal is a button "<name>, double tap to pick"; the log announces its tilt in degrees on change (throttled to once per second); results are polite live regions ("Mia placed the animal: turtle.", "2 animals fell."). Dragging by touch-explore is replaced by the **one-switch flow** below, which also serves TalkBack users who cannot drag.
- **One-switch placing (v1)**: after choosing (the choice also scans the 3 shelf animals, 1.5 s each), the animal slides left and right across the log by itself (ping-pong at 0.5 u per second); press to stop it at that x; it then turns through its flat angles (a step every 0.8 s); press again to stop; one last press lets go. Three presses per turn, with ticks and the strings `stack_zoo_switch_*`. Snap help is on by default in one-switch games.
- **Relaxed variant**: the log is stiffer (spring x2, damping ratio 0.6, keel 2.5), animal friction +0.2, a fallen animal returns for free (no lives, no take-back penalty, the bag refills), and settling is shorter (about 1 s).
- Reduced Motion, Game Speed and a slow-motion toggle (time scale 0.6: only the number of physics steps per second changes, never the step) are respected.
- Large text: digits scale; no text is needed to play.

### 1.7 What it teaches (one line for the parent dashboard)
`Teaches balance, fine-motor control and planning ahead.` (string `stack_zoo_teaches`.)

### 1.8 Dependencies
- Shell: `GameModule`, `ResumableGame`, `GameHubSpec` (KIDS, minAge 4), `HubOptions`, `PlayerNamesDialog`; the profiles foundation (being written by another agent) for the KID age-band filter; no direct reads of profiles here.
- Settings: Reduced Motion, Game Speed, Colour-blind mode, Text size, haptics and sound toggles (read by the later screen).
- UI helpers for the later screen: `ContactSizes`, `TapTugPointerTracker`, `FoldAwareLayout`.
- `kotlinx-serialization-json` (present) for snapshot and replay. No physics library.
- Shared files the coordinator must edit (4.3): `GameHubSpecs`, `GameCatalog`, `GameTheme`, `MainActivity`, `GameTips`, `GameCoach`.

### 1.9 Open questions (each with a recommended default)
| # | Question | Recommended default |
|---|---|---|
| Q1 | "Take Turns (last to topple wins, fewest pieces left)": read as: whoever avoids toppling and empties their hand first wins; fallen animals return to the causer (max 3); rank is fewest animals left. Correct? | Yes (2.7). The alternative reading, "last player standing after eliminations", is rough for children and is not built. |
| Q2 | Legal check of the animal roster (crocodile, hippo, elephant, giraffe, snake and frog are common in the genre)? Species are not protectable and the shapes are ours. | Keep. If legal prefers, six spare animals (kangaroo, flamingo, walrus, llama, hedgehog, koala) can be authored at no engine cost. |
| Q3 | Co-op result for stats: house convention (Bridge Builders) marks everyone a winner once something was stacked. Keep? | Keep, so a KID profile never shows a "loss"; revisit with the profiles owner. |
| Q4 | The 8 u Tall goal and the keel constant are guesses until the M0 simulation. Acceptable to ship MVP with Little and Medium only if Tall is not tunable in time? | Yes. Hide Tall until a 20-game bot playtest reaches it in 40 to 70 percent of games. |
| Q5 | Should the pieces be shown with faces and soft shading? | Flat vector, thick outline, a simple face (two dots), no shading, so the silhouette reads for the physics. |
| Q6 | Allow rotation freedom (any angle) or only snapped angles? | Any angle by two-finger twist and the two buttons (step 15 degrees); snap assist for flat sides. |
| Q7 | Take Turns bag: 20 animals x 2 copies (40 pieces) so 4 players with 7 each (28) work? | Yes, at most 2 copies of each animal. |
| Q8 | Glyph and accent for the menu card | Glyph U+1F992 (giraffe, Android 8+, unused today); accent by the coordinator with `GameThemeTest` contrast checks. |
| Q9 | Spanish title | "Zoo Equilibrio" (native review before release; strings carry a `TODO translate` review comment). |
| Q10 | Nearby (later): lockstep or host-authoritative for physics? | Deterministic lockstep (3.6): turn-based, so only releases cross the wire. |

---

## 2. SCRIPT

### 2.1 Components and look (original)
- Kids theme: warm cream background, Baloo-style rounded digits, thick rounded outlines, soft shadows, 56 dp minimum targets.
- **The log**: a cheerful wooden plank balanced on a round log pivot in the middle of the screen; it rocks (2.4). A small triangle marker and a colour change show "wobbly" (alert).
- **The shelf**: along the bottom (or the side in landscape) shows the animals to pick from (the offer of 3 in Co-op and Solo, the whole hand in Take Turns), each at least 72 dp tall.
- **The flag** (Co-op): a goal flag on a pole at the goal height; the camera zooms out as the tower grows (view height = max(10 u, tower height + 5 u)).
- **The pond**: fallen animals plop into it and bounce back as balloons.
- Helpers are friendly zoo keepers shown as seat badges (computer seats are called "CPU" by the shell, shown with a keeper icon).

### 2.2 The animals (20, data)
Mass is authored (heavier is bigger and denser); the centre of mass and the moment of inertia are derived from the parts and scaled to that mass at load. Friction is per animal (the contact friction is the geometric mean of the two bodies; the log is 0.8). Tier is the placement difficulty used by helpers and the tutorial order: E easy (wide, low, flat), M medium, H hard (tall, narrow, lopsided).
| # | id | EN | ES | mass | friction | tier | parts | area (u^2) | width | height | centre of mass |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `turtle` | turtle | tortuga | 1.6 | 0.80 | E | 2 | 2.47 | 3.05 | 1.10 | (0.22, 0.46) |
| 2 | `hippo` | hippo | hipopótamo | 2.4 | 0.70 | E | 2 | 3.59 | 3.50 | 1.10 | (0.28, 0.59) |
| 3 | `elephant` | elephant | elefante | 3.0 | 0.60 | M | 4 | 4.16 | 3.25 | 1.90 | (0.13, 1.07) |
| 4 | `giraffe` | giraffe | jirafa | 2.2 | 0.50 | H | 4 | 2.56 | 2.20 | 3.40 | (0.19, 1.56) |
| 5 | `crocodile` | crocodile | cocodrilo | 1.8 | 0.70 | E | 3 | 2.86 | 5.50 | 0.60 | (-0.15, 0.26) |
| 6 | `snail` | snail | caracol | 1.0 | 0.60 | M | 3 | 3.96 | 3.40 | 1.90 | (0.17, 0.71) |
| 7 | `penguin` | penguin | pingüino | 1.2 | 0.55 | M | 2 | 2.70 | 1.80 | 1.90 | (0.00, 0.84) |
| 8 | `rabbit` | rabbit | conejo | 0.9 | 0.50 | H | 4 | 2.30 | 1.80 | 2.50 | (0.27, 0.84) |
| 9 | `fox` | fox | zorro | 1.1 | 0.50 | M | 3 | 3.19 | 3.90 | 1.50 | (-0.31, 0.59) |
| 10 | `bear` | bear | oso | 2.0 | 0.60 | E | 2 | 4.40 | 2.40 | 2.40 | (0.00, 1.05) |
| 11 | `duck` | duck | pato | 0.8 | 0.45 | M | 4 | 2.56 | 3.45 | 1.80 | (0.11, 0.77) |
| 12 | `frog` | frog | rana | 1.0 | 0.40 | E | 3 | 2.67 | 2.40 | 1.50 | (0.00, 0.64) |
| 13 | `owl` | owl | búho | 1.3 | 0.55 | H | 3 | 3.41 | 1.80 | 2.50 | (0.00, 1.03) |
| 14 | `monkey` | monkey | mono | 1.0 | 0.50 | H | 4 | 3.77 | 3.70 | 2.10 | (0.01, 0.88) |
| 15 | `dino` | dinosaur | dinosaurio | 2.1 | 0.60 | M | 4 | 4.01 | 3.30 | 2.00 | (0.07, 0.93) |
| 16 | `whale` | whale | ballena | 2.2 | 0.45 | M | 3 | 3.87 | 3.90 | 1.70 | (-0.08, 0.68) |
| 17 | `camel` | camel | camello | 2.4 | 0.55 | H | 4 | 3.86 | 3.10 | 2.50 | (0.15, 1.36) |
| 18 | `cat` | cat | gato | 1.0 | 0.50 | M | 3 | 3.61 | 2.50 | 2.30 | (0.34, 0.97) |
| 19 | `snake` | snake | serpiente | 1.1 | 0.35 | E | 3 | 2.38 | 3.00 | 1.70 | (0.12, 0.55) |
| 20 | `pig` | pig | cerdo | 1.4 | 0.55 | E | 4 | 2.71 | 2.70 | 1.30 | (0.19, 0.68) |

Every animal is 1 to 4 convex parts (their union is the silhouette; 18 of the 20 silhouettes are concave by a raster check, fill below 97 percent of the convex hull: legs, tails, ears, necks; only the hippo and the crocodile are almost convex). A script verified that every part is convex and counter-clockwise and that the parts of each animal touch or overlap. Each part is the counter-clockwise convex hull of a point list. Vertex lists (these are exactly `ZooAnimals.all`; units u, origin at the animal's bottom, x centred roughly on the body):
- **turtle** (`turtle`): part 1: (-1.2,0) (1.2,0) (1,0.7) (0.4,1.1) (-0.4,1.1) (-1,0.7); part 2: (1.1,0.05) (1.8,0.1) (1.85,0.55) (1.15,0.65)
- **hippo** (`hippo`): part 1: (-1.4,0.35) (-1.1,0) (1.1,0) (1.4,0.35) (1.4,1.1) (-1.4,1.1); part 2: (1.2,0.3) (2,0.35) (2.1,1) (1.2,1.1)
- **elephant** (`elephant`): part 1: (-1.4,1.4) (-1.3,0.8) (1.2,0.8) (1.3,1.5) (0.6,1.9) (-0.9,1.9); part 2: (-0.9,0) (-0.3,0) (-0.3,0.9) (-0.9,0.9); part 3: (0.3,0) (0.9,0) (0.9,0.9) (0.3,0.9); part 4: (1.3,0.7) (1.75,0.65) (1.85,1.6) (1.3,1.5)
- **giraffe** (`giraffe`): part 1: (-0.9,1.3) (0.9,1.3) (0.9,2) (-0.9,2); part 2: (-0.8,0) (-0.5,0) (-0.5,1.3) (-0.8,1.3); part 3: (0.5,0) (0.8,0) (0.8,1.3) (0.5,1.3); part 4: (0.5,2) (0.9,2) (1.3,3.4) (0.95,3.4)
- **crocodile** (`crocodile`): part 1: (-2,0) (1.6,0) (1.6,0.5) (-2,0.6); part 2: (1.5,0) (2.6,0.05) (2.6,0.4) (1.5,0.55); part 3: (-2.9,0) (-2,0) (-2,0.6) (-2.9,0.25)
- **snail** (`snail`): part 1: (-0.9,1) (-0.64,0.36) (0,0.1) (0.64,0.36) (0.9,1) (0.64,1.64) (0,1.9) (-0.64,1.64); part 2: (-1.5,0) (1.4,0) (1.7,0.35) (1,0.5) (-1.5,0.35); part 3: (1.2,0.3) (1.8,0.3) (1.9,1) (1.5,1)
- **penguin** (`penguin`): part 1: (-0.8,1) (-0.6,0.1) (0.6,0.1) (0.8,1) (0.4,1.9) (-0.4,1.9); part 2: (-0.9,0) (0.9,0) (0.9,0.2) (-0.9,0.2)
- **rabbit** (`rabbit`): part 1: (-0.8,0) (0.9,0) (0.8,0.9) (-0.5,1.1); part 2: (0.3,0.9) (1,0.9) (1,1.5) (0.3,1.6); part 3: (0.4,1.5) (0.6,1.5) (0.55,2.5) (0.4,2.5); part 4: (0.8,1.5) (1,1.5) (0.95,2.4) (0.8,2.4)
- **fox** (`fox`): part 1: (-1.2,0) (0.9,0) (1,0.8) (-1,1); part 2: (0.8,0.6) (1.8,0.65) (1.3,1.5); part 3: (-2.1,0.5) (-1,0.15) (-0.9,0.9) (-2,1.3)
- **bear** (`bear`): part 1: (-1.2,0.9) (-1,0) (1,0) (1.2,0.9) (0.8,1.6) (-0.8,1.6); part 2: (-0.7,2.1) (-0.5,1.5) (0.5,1.5) (0.7,2.1) (0.3,2.4) (-0.3,2.4)
- **duck** (`duck`): part 1: (-1.2,0.6) (-0.9,0.1) (0.8,0.1) (1.2,0.6) (0.6,1) (-0.8,1); part 2: (0.4,1.6) (0.5,1) (1.1,1) (1.2,1.5) (0.8,1.9); part 3: (1.15,1.2) (1.75,1.2) (1.2,1.5); part 4: (-1.7,1.2) (-1.2,0.6) (-0.9,0.9)
- **frog** (`frog`): part 1: (-1.2,0.6) (-1,0) (1,0) (1.2,0.6) (0.6,1.1) (-0.6,1.1); part 2: (-0.8,1) (-0.3,1) (-0.35,1.5) (-0.75,1.5); part 3: (0.3,1) (0.8,1) (0.75,1.5) (0.35,1.5)
- **owl** (`owl`): part 1: (-0.9,1.3) (-0.8,0) (0.8,0) (0.9,1.3) (0.6,2) (-0.6,2); part 2: (-0.6,2) (-0.3,2) (-0.5,2.5); part 3: (0.3,2) (0.6,2) (0.5,2.5)
- **monkey** (`monkey`): part 1: (-0.7,0) (0.7,0) (0.8,1) (-0.6,1.2); part 2: (-0.6,1.8) (-0.5,1.1) (0.6,1.1) (0.7,1.8) (0,2.1); part 3: (0.7,0.6) (1.9,0.9) (1.9,1.3) (0.7,1.1); part 4: (-1.8,0.4) (-0.6,0.15) (-0.55,0.7) (-1.8,0.9)
- **dinosaur** (`dino`): part 1: (-1.4,0.5) (1,0.5) (1.9,0.9) (1.9,1.4) (-1.3,1.4); part 2: (-1.1,0) (-0.5,0) (-0.5,0.6) (-1.1,0.6); part 3: (0.2,0) (0.8,0) (0.8,0.6) (0.2,0.6); part 4: (-1,1.35) (0.6,1.28) (-0.2,2)
- **whale** (`whale`): part 1: (-1.5,0.5) (-1.3,0) (1,0) (1.7,0.5) (1.5,1.1) (0.5,1.3) (-1.1,1); part 2: (-2.2,1.5) (-1.4,0.6) (-1.2,0.9) (-1.6,1.55); part 3: (-0.3,1.05) (0.3,1.2) (-0.1,1.7)
- **camel** (`camel`): part 1: (-1.3,1) (1.3,1) (1.3,1.5) (0.3,2.2) (-0.7,2.2) (-1.3,1.5); part 2: (-1,0) (-0.6,0) (-0.6,1) (-1,1); part 3: (0.6,0) (1,0) (1,1) (0.6,1); part 4: (0.9,1.4) (1.4,1.4) (1.8,2.5) (1.4,2.5)
- **cat** (`cat`): part 1: (-0.7,0) (0.8,0) (0.7,1.1) (-0.5,1.3); part 2: (-0.55,2) (-0.5,1.2) (0.6,1.2) (0.65,2) (0.35,2.3) (-0.35,2.3); part 3: (0.7,0.1) (1.7,0.1) (1.8,1.3) (1.5,1.3)
- **snake** (`snake`): part 1: (-1.5,0.4) (-1.3,0) (1.3,0) (1.5,0.4); part 2: (-0.9,0.4) (0.9,0.4) (0.9,0.8) (-0.9,0.8); part 3: (0.1,1.2) (0.2,0.8) (0.8,0.8) (0.9,1.5) (0.4,1.7)
- **pig** (`pig`): part 1: (-1.1,0.3) (0.9,0.3) (1.1,1.1) (-1.1,1.2); part 2: (-0.9,0) (-0.4,0) (-0.4,0.4) (-0.9,0.4); part 3: (0.4,0) (0.9,0) (0.9,0.4) (0.4,0.4); part 4: (0.9,0.5) (1.6,0.5) (1.6,1.2) (0.9,1.3)

### 2.3 World, units and what "stable" means
- Units: 1 u is about one animal body height. The log is 6.0 u wide, 0.4 u thick; its top surface is the line y = 0 when level. Gravity 9.81 u/s^2. Animals are 0.6 to 3.4 u tall and 1.8 to 5.5 u wide (the crocodile is 5.5 wide: it nearly fills the log, which is intended).
- **Fixed step**: 120 Hz (`DT = 1/120 s`). The engine never sees wall-clock time: the wrapper converts frame time to whole steps (at most 5 per frame; a 60 Hz display runs 2 steps per frame, a 120 Hz display runs 1).
- **Rest (sleep) threshold**: a body is at rest when its speed is below 0.05 u/s and its spin below 0.10 rad/s for 60 steps in a row (0.5 s). Resting bodies are not simulated until something touches them.
- **Settled**: a turn is settled when every body is asleep and the log's spin has stayed below 0.02 rad/s for 60 steps; or after 960 steps (8 s), whichever comes first (the turn is then accepted as it stands).
- **Wobble alert** (UI and haptic only, no rule effect): log angle above 60 percent of the stop (0.21 rad) or any animal faster than 0.5 u/s while settling.
- **Toppled (fallen)**: an animal has fallen the moment its centre of mass is below y = -1.2 u (below the underside of the log and its stand). It is flagged fallen at that step and removed from the simulation below y = -6 u. A piece that merely hangs over the edge with its centre above -1.2 has not fallen.
- **Tower height** (Co-op): the highest vertex of any resting, non-fallen animal, measured in the log's own frame, above the log's top surface.

### 2.4 Physics model and step algorithm
**Bodies**: a dynamic animal has position (x, y) of its centre of mass, angle, linear velocity and angular velocity (all `Double`). Its shape is its parts, each a convex polygon of 3 to 8 vertices stored in the body frame (origin at the centre of mass after loading). Mass is authored; inertia = authored mass x (sum over parts of polygon second moments, parallel-axis about the combined centre) / (sum of part areas). Restitution is 0 (no bounce: calm and stable).

**The log (the rocking base)**: a plank that can only turn about its own centre (the pivot at (0, -0.2)); it has infinite linear mass and finite angular inertia `I = 30`. Contact impulses from animals therefore change only its spin. Each step it also feels:
```
S      = sum over non-fallen animals of  mass * max(0, y_com - pivotY)     // load sum: weight times height
torque = -( k + KEEL * g * S ) * theta  -  ( c + c1 * S ) * omega
omega += torque / I * DT
```
with `k = 460` (natural period of the empty log 2*pi/sqrt(k/I) = 1.60 s), `c = 52` (damping ratio about 0.22: visibly wobbly, decays in about 4 s), `KEEL = 1.5` ("ballast keel": gravity pulls a tall load over, `g * S * theta`; the keel pushes back 1.5 times harder, so a tall stack never pulls the log over by itself but off-centre weight still tips it), `c1 = 1.2`. Relaxed: `k = 920, c = 199` (ratio about 0.6), `KEEL = 2.5`, `c1 = 3.0`. Hard stops at +-0.35 rad (20 degrees), inelastic. Friction of the log surface 0.8.

**One physics step** (`advance(1)`), in this exact order (arrays in animal-id order; no hash-map iteration anywhere):
1. If every body and the log are asleep: return (cost close to zero).
2. Integrate velocities of awake animals: `vy -= g * DT`; the log's spin from the formula above.
3. Broadphase: an axis-aligned box per animal (union of its parts, padded by 0.05) and one for the log; insertion sort by (minX, id) on a persistent order; sweep to list overlapping pairs, sorted by (idA, idB). Cost is linear for a mostly sorted stack.
4. Narrowphase per pair and part pair: convex-convex separating-axis test; the reference edge is the one with the least penetration; clip the incident edge to produce 1 or 2 contact points with a feature id. At most 128 contact points in total (extra points are dropped farthest-first).
5. Build constraints: friction = sqrt(muA * muB); restitution 0; effective masses from inverse mass and inverse inertia (log: inverse mass 0, finite inverse inertia).
6. Warm start: look up each contact by key (idA, partA, idB, partB, feature) in the previous step's cache and apply its accumulated normal and tangent impulses. Unmatched cache entries are dropped.
7. Velocity iterations x 10: for each constraint in order, solve friction (clamp the accumulated tangent impulse to +-mu * normal impulse) then the normal (accumulated normal impulse never negative).
8. Log stops: if |theta| is at the stop and the spin pushes outward, spin = 0.
9. Integrate positions: `x += vx * DT`, `y += vy * DT`, `angle += omega * DT`, `theta += omega_log * DT` clamped to +-0.35.
10. Position iterations x 4: for each contact with penetration above 0.005, move the animal (the log never translates) by `0.2 * (penetration - 0.005)`, at most 0.2 per iteration.
11. Sleep bookkeeping: per-body rest counter; bodies touching an awake body or a moving log (spin above 0.02) are awake.
12. Fall check: flag animals with `y_com < -1.2`; drop them from the simulation below -6.
13. Update the running state hash (2.9).

**Determinism rules** (enforced by test SZ-P4 text scan): `Double` everywhere (no `Float`); `DT` is a constant; sine and cosine only through `StrictMath` (the `Math` versions may differ between devices); no `Math.fma`; no threads; no `Random` except `ZooRng` with the given seed; iteration order is array order; contact cache is an array sorted by key, not a hash map; no wall clock.

### 2.5 Placement: drag, release, sweep-down
1. CHOOSING: the active seat taps a shelf animal (or the one-switch scan picks it). It becomes the ghost (not a physics body yet).
2. POSITIONING: the ghost follows the finger horizontally and vertically (drawn 56 dp above the touch point). Rotation: two-finger twist, or the Turn left and Turn right buttons (step 15 degrees = 0.2618 rad). The ghost has no effect on the tower. Dragging it back to the shelf puts it back (no turn used).
3. Release: the wrapper sends `release(xQ, angleQ)`: x in 1/1024 u clamped to +-4 u, angle in 1/1024 rad (both Ints, so replays are exact).
4. Assist (2.6) may adjust x and angle.
5. **Sweep-down**: the animal is placed at the assisted x and angle just above the tower and lowered: start at y = (highest vertex of any body and the log) + 3 u; step down 0.1 u at a time with an overlap test against every non-fallen body and the log; at the first overlap, bisect the last free and first overlapping heights to 0.01 u; the final y is that free height + 0.04 u (the placing gap). The animal rests where a hand would have set it down, so the player controls x and angle only, never the height (this removes fiddly vertical aiming at age 4).
6. The animal becomes dynamic with zero velocity, and the log and any touching animals wake. Phase SETTLING.

### 2.6 Snap assist (the handicap)
Applied to the placement of an assisted seat only; the same function powers the halo preview (`assisted(xQ, angleQ)`).
| Level | Flat snap | Balance pull | Extra |
|---|---|---|---|
| None | none | none | none |
| Small (reserved for a later Hub choice) | within 12 degrees | radius 0.6 u, pull 0.5 | none |
| Large ("Bigger snap help for") | within 25 degrees | radius 1.4 u, pull 0.85 | animal friction +0.2 (capped at 1.0) |
1. **Flat snap**: take the animal's outer hull; for every hull edge at least 0.6 u long compute the angle that makes that edge lie flat on the bottom; if the nearest such angle is within the snap range of the player's angle, use it.
2. **Balance pull**: target `xt = 0.5 * x_top + 0.5 * x_counter`, where `x_top` is the x of the centre of mass of the highest standing animal (0 if none) and `x_counter = clamp( -sum(mass_i * x_i) / mass_new, -2.5, 2.5 )` over standing animals (the spot that evens out the log). If `|x - xt|` is within the radius, `x += pull * (xt - x)`.
3. A soft green halo shows the assisted spot while dragging (a ring shape, not colour only).

### 2.7 Rules as a numbered script
**Common**
- C1. Setup (Hub): mode, goal or animals each, helpers, helper level, snap help, Relaxed, one-switch. The wrapper draws the seed once (clock allowed there only) and hands it to the engine.
- C2. READY: "Ready?" and a big start button; the shelf and log are shown. Anyone presses it.
- C3. The bag: 40 animals (20 types, at most 2 copies each) for Take Turns; the Co-op bag is the first `bag` animals of a seeded shuffle of the 40 (12, 16 or 20); the Solo bag is the 20 distinct animals shuffled. Piece ids are 0 up in bag order.
- C4. Turn: CHOOSING, POSITIONING, SETTLING, TURN_RESULT (about 1.2 s of cheering or "Oops!"), then the next seat. Pause freezes everything.

**Co-op Tower**
- T1. Seats take turns in order 0 to n-1 (helpers included). The offer is the first 3 animals of the bag; the active seat picks one; the others stay at the front of the bag.
- T2. After SETTLING, `fell` is the count of animals fallen this turn. They are lost (they float away in balloons). Relaxed: they return to the bag.
- T3. The tower height is measured when settled. When it is at or above the goal height and stays there for 180 steps (1.5 s) the team wins: "We did it!" with stars: 1 star always; 2 stars if at least 25 percent of the bag is left; 3 stars if at least 50 percent is left.
- T4. If the bag is empty and the goal has not been reached: "Try again together" (same seed again or a new one: two buttons). It is not a loss for anyone.
- T5. Goals: Little 4.0 u with 12 animals; Medium 6.0 u with 16; Tall 8.0 u with 20.

**Take Turns**
- K1. Deal: every seat gets `handSize` animals from the bag round robin (Quick 4, Normal 5, Long 7); hands are face up. Order: seat 0 first (or the Hub first-mover rule).
- K2. The active seat picks any animal from their own hand and places it.
- K3. After SETTLING, every fallen animal (whoever's it was) goes to the active seat's hand, at most 3 (the extras go back to the box). "<name> takes the fallen animals back".
- K4. If the active seat's hand is empty after the settle (their last animal stood), they win immediately. Everyone else is ranked by animals left (fewest first); equal counts share a rank.
- K5. Turn cap: after 3 x handSize turns per seat with no winner, the game ends; fewest animals left wins; ties share.
- K6. Score: `PlayerScore(score = handSize - left, isWinner = left == fewest)`.

**Solo Balance**
- B1. The offer is the first 3 animals of the shuffled bag (all 20). The player picks one per turn.
- B2. A placement after which at least one animal fell costs 1 try (3 tries). Fallen animals are gone. Relaxed: no tries, fallen animals return to the bag.
- B3. The run ends when the tries are used up or the bag is empty. Score = animals standing; `isWinner = score >= 1`. A personal best shows "New best!" (best kept by the shell stats).

### 2.8 State machine
```
SETUP (Hub, not in the engine)
  -> READY          (bag dealt, seed fixed)
READY --begin()--> CHOOSING
CHOOSING --choose(pieceId)--> POSITIONING          (helper seats do this by themselves after >= 800 ms)
POSITIONING --release(xQ, angleQ)--> SETTLING      (assist, sweep-down, piece becomes dynamic)
POSITIONING --drag back to shelf--> CHOOSING       (no turn used)
SETTLING --settled (all asleep 60 steps or 960 steps)--> TURN_RESULT (1.2 s)
TURN_RESULT --> CHOOSING (next seat) | OVER (goal held, bag empty, hand empty, tries used, turn cap)
OVER --Play again--> READY (derived seed) ; OVER --Done--> shell result screen
PAUSE: a flag at any phase (steps stop; helpers and timers freeze)
```
Allowed inputs: READY accepts `begin`; CHOOSING accepts `choose`; POSITIONING accepts `hover`, `release` and back-to-shelf; SETTLING and TURN_RESULT accept `advance` only (a release then returns `NOT_POSITIONING`); OVER accepts `advance` only.

### 2.9 Scoring, results and the state hash
- Co-op: stars (T3); no per-seat score except animals placed (flourish); everyone `isWinner = true` once at least one animal stood (house convention, Q3).
- Take Turns: K6. Solo: B3.
- Badges on the score card (no loser badge): **Steady hands** (most placements without any fall), **Tallest tower** (Co-op: the seat whose placement first reached the highest height). At least one badge per player.
- State hash: FNV-1a 64-bit over the quantised pose of every non-fallen body in id order (x, y, angle each as `round(v * 1024)` Int, plus the log angle and spin) and the step index; used by the golden test and by Nearby desync detection.

### 2.10 Helper (computer seat) behaviour per level
Helpers use the public API (`choose`, `release`); they drag visibly: think time at least 800 ms plus 900 ms of an eased drag with a small overshoot, so they feel like a friend and not a machine.
| | Easy | Medium | Hard |
|---|---|---|---|
| Piece choice | random | prefers tier E when the tower is above 3 u | lowest tier that has a flat edge of at least 1.2 u; heavy and wide first while the tower is below 2 u |
| Candidates | random x within +-3.5 | 11 x positions (-2.5 to 2.5 step 0.5) x the animal's flat angles | same, then a rollout of the best 8 (150 steps each, off the main thread) |
| Score terms | none | 3 x flatness + 2 x support + 1.5 x balance - 1 x overhang | the same, then the rollout's smallest peak displacement without a fall |
| Noise (x sd u, angle sd degrees) | 0.60 / 25 | 0.25 / 8 | 0.08 / 2 |
| Blunder chance per turn (random x and angle) | 20 percent | 8 percent | 3 percent |
Definitions: flatness = length of the lowest hull edge divided by the animal's width; support = overlap of that edge's x-range with the x-range of the highest standing animal's top (0 to 1, 1 on an empty log); balance = 1 - |net log torque after placing| / 90; overhang = how far the centre of mass is outside the support range, in u. Weights are starting values, tuned in M3 against the 200-game bot test (SZ-B1). Helper choices come from a stream of `ZooRng` seeded by (seed, turn, seat), so a game replays exactly.

### 2.11 Handicap rule
**Bigger snap help for** none / all players / First to Fourth player (hub key `assist`: `none`, `all`, `p1` to `p4`), as in 2.6; in Solo it is Snap help on or off. The assist moves the drop and turns the animal flat; it never changes the log, the mass or the bag.

### 2.12 First-run tutorial script (English lines, each under 120 characters)
Shown once per profile on first launch; runs a scripted Solo round with fixed seed and animals (turtle, penguin, bear), no tries, Relaxed physics, Snap help on, and a pointing hand that follows the right drag (a static arrow under Reduced Motion). "Skip" is always visible.
| Step | Screen | EN line | Advances when |
|---|---|---|---|
| 1 | Shelf with the turtle, hand points at it | Pick an animal and drag it onto the wobbly log. | player drags the turtle onto the log |
| 2 | Turtle lands, log rocks | Let go to set it down. The log will rock! | the turtle settles |
| 3 | Penguin on the shelf, rotate buttons glow | Turn the animal so its flat side is down. | player taps a turn button or twists |
| 4 | Bear on the shelf, halo shows the middle | Put heavy animals low and in the middle. | player places the bear |
| 5 | The scripted "Oops": an easy piece falls into the pond and floats back | If animals fall, do not worry. Try again together! | tap Next |
| 6 | Calm log | Wait until the tower is still, then the next turn starts. | tap Next |
| 7 | Done | Let us play! | tap the button |

### 2.13 The five UI states
| State | What the table shows |
|---|---|
| Setup | Hub: mode tiles, then options (goal or animals each, helpers, snap help, Relaxed, one-switch); big pictures on rows; TalkBack text on every control |
| Start | READY: the log, the shelf face down, seat badges, a big "Ready?" button, the tutorial replay button |
| Mid-game | The log with the tower, the shelf (offer or hand), the seat badge of the active player highlighted, the flag (Co-op), animals left or tries, the wobble marker, Turn left and Turn right buttons while dragging |
| Post-game | TURN_RESULT cheer or "Oops! One fell" with the balloon animation; then the OVER banner "We did it!" / "Try again together" / "<name> wins!" / "It is a tie!" |
| Score card | Stars (Co-op) or animals placed and left per player (Take Turns), personal best (Solo), badges, "Play again" and "Done" |

### 2.14 Exact English strings
Resource names are `stack_zoo_<suffix>` in `res/values/stack_zoo_strings.xml` (Spanish in `values-es`, real Spanish values, review comment only). Animal names are `stack_zoo_animal_<id>` (table in 2.2). Hub texts use `hubKey(text)` names; the new ones are listed after the UI table.

| key | EN |
|---|---|
| `stack_zoo_title` | Stack Zoo |
| `stack_zoo_help_title` | Stack Zoo |
| `stack_zoo_help_text` | Drag an animal onto the wobbly log and let go. Keep the tower standing. Co-op Tower: build up to the flag together. Take Turns: if animals fall, take them back; the first to place all of theirs wins. Solo Balance: stack as many as you can. |
| `stack_zoo_mode_coop` | Co-op Tower |
| `stack_zoo_mode_turns` | Take Turns |
| `stack_zoo_mode_solo` | Solo Balance |
| `stack_zoo_ready` | Ready? |
| `stack_zoo_tap_to_start` | Tap to start |
| `stack_zoo_paused` | Paused |
| `stack_zoo_resume` | Keep playing |
| `stack_zoo_pick_animal` | Pick an animal |
| `stack_zoo_drag_it` | Drag it onto the log |
| `stack_zoo_let_go` | Let go to place it |
| `stack_zoo_turn_of` | %1$s, your turn |
| `stack_zoo_hold_still` | Wait for the tower to settle |
| `stack_zoo_rotate_left` | Turn left |
| `stack_zoo_rotate_right` | Turn right |
| `stack_zoo_nice` | Nice! |
| `stack_zoo_wobbly` | Wobbly! |
| `stack_zoo_oops_one` | Oops! One fell |
| `stack_zoo_oops_many` | Oops! %1$d fell |
| `stack_zoo_take_back` | %1$s takes the fallen animals back |
| `stack_zoo_goal_reached` | You reached the flag! |
| `stack_zoo_goal_hold` | Hold it steady |
| `stack_zoo_we_did_it` | We did it! |
| `stack_zoo_try_again_together` | Try again together |
| `stack_zoo_play_again` | Play again |
| `stack_zoo_done` | Done |
| `stack_zoo_stars_n` | %1$d of 3 stars |
| `stack_zoo_animals_left` | Animals left: %1$d |
| `stack_zoo_tries_left` | Tries left: %1$d |
| `stack_zoo_stacked_n` | Stacked: %1$d |
| `stack_zoo_best` | Best: %1$d |
| `stack_zoo_new_best` | New best! |
| `stack_zoo_winner` | %1$s wins! |
| `stack_zoo_tie` | It is a tie! |
| `stack_zoo_badge_steady` | Steady hands |
| `stack_zoo_badge_tall` | Tallest tower |
| `stack_zoo_cd_animal_pick` | %1$s, double tap to pick |
| `stack_zoo_cd_log` | Wobbly log, tilted %1$d degrees |
| `stack_zoo_cd_flag` | Goal flag, height %1$d |
| `stack_zoo_cd_tower` | Tower with %1$d animals |
| `stack_zoo_cd_ghost` | %1$s, being placed |
| `stack_zoo_ann_placed` | %1$s placed the animal: %2$s. |
| `stack_zoo_ann_fell_one` | An animal fell. |
| `stack_zoo_ann_fell_many` | %1$d animals fell. |
| `stack_zoo_ann_settled` | The tower is steady. |
| `stack_zoo_switch_stop_x` | Press your switch to stop the animal here |
| `stack_zoo_switch_stop_turn` | Press again to stop turning |
| `stack_zoo_switch_drop` | Press once more to let go |
| `stack_zoo_teaches` | Teaches balance, fine-motor control and planning ahead. |
| `stack_zoo_tut_1` | Pick an animal and drag it onto the wobbly log. |
| `stack_zoo_tut_2` | Let go to set it down. The log will rock! |
| `stack_zoo_tut_3` | Turn the animal so its flat side is down. |
| `stack_zoo_tut_4` | Put heavy animals low and in the middle. |
| `stack_zoo_tut_5` | If animals fall, do not worry. Try again together! |
| `stack_zoo_tut_6` | Wait until the tower is still, then the next turn starts. |
| `stack_zoo_tut_skip` | Skip |
| `stack_zoo_tut_next` | Next |
| `stack_zoo_tut_start` | Let us play! |

Hub texts added by this game (name = `hubKey(text)`; texts that already exist elsewhere are reused and not listed):
| name | EN |
|---|---|
| `hub_tower_goal` | Tower goal |
| `hub_how_high_the_flag_is_and_how_many_animals_the_te_5d25f6` | How high the flag is and how many animals the team has. |
| `hub_little_tower` | Little tower |
| `hub_a_low_flag_and_12_animals_great_for_the_youngest` | A low flag and 12 animals: great for the youngest. |
| `hub_medium_tower` | Medium tower |
| `hub_a_medium_flag_and_16_animals_a_good_mix_for_most_b147db` | A medium flag and 16 animals: a good mix for most families. |
| `hub_tall_tower` | Tall tower |
| `hub_a_high_flag_and_20_animals_for_steady_hands` | A high flag and 20 animals for steady hands. |
| `hub_animals_each` | Animals each |
| `hub_how_many_animals_every_player_has_to_place` | How many animals every player has to place. |
| `hub_few_animals` | Few animals |
| `hub_4_animals_each_about_3_minutes` | 4 animals each: about 3 minutes. |
| `hub_some_animals` | Some animals |
| `hub_5_animals_each_about_5_minutes` | 5 animals each: about 5 minutes. |
| `hub_many_animals` | Many animals |
| `hub_7_animals_each_about_8_minutes` | 7 animals each: about 8 minutes. |
| `hub_bigger_snap_help_for` | Bigger snap help for |
| `hub_gently_guides_one_player_s_animal_toward_a_stead_c5d49d` | Gently guides one player's animal toward a steadier spot, so different ages can play together. |
| `hub_all_players` | All players |
| `hub_first_player` | First player |
| `hub_second_player` | Second player |
| `hub_third_player` | Third player |
| `hub_fourth_player` | Fourth player |
| `hub_snap_help` | Snap help |
| `hub_gently_guides_your_animal_toward_a_steadier_spot` | Gently guides your animal toward a steadier spot. |
| `hub_no_help` | No help |
| `hub_help_on` | Help on |
| `hub_relaxed_wobble` | Relaxed wobble |
| `hub_a_steadier_log_grippier_animals_and_animals_that_56333d` | A steadier log, grippier animals, and animals that fall float back for free. |
| `hub_one_switch_placing` | One-switch placing |
| `hub_use_a_single_button_the_animal_slides_and_turns_28f40f` | Use a single button: the animal slides and turns by itself and you press to stop it. |
| `hub_stack_zoo` | Stack Zoo |
| `hub_balance_animals_on_the_wobbly_log` | Balance animals on the wobbly log. |
| `hub_co_op_tower` | Co-op Tower |
| `hub_build_one_tall_tower_together_everyone_picks_an_7504e2` | Build one tall tower together. Everyone picks an animal and places it. |
| `hub_take_turns_with_helpers` | Take turns with helpers |
| `hub_you_and_1_to_3_friendly_helper_animals_take_turn_d5b4b6` | You and 1 to 3 friendly helper animals take turns. If some fall, take them back. |
| `hub_place_animals_one_by_one_if_some_fall_you_take_t_7d33d2` | Place animals one by one. If some fall, you take them back. First to place all wins. |
| `hub_solo_balance` | Solo Balance |
| `hub_stack_as_many_animals_as_you_can_on_the_wobbly_l_096a40` | Stack as many animals as you can on the wobbly log. |
| `hub_zoo_helpers` | Zoo helpers |
| `hub_friendly_helpers_take_turns_too_so_a_small_group_06d323` | Friendly helpers take turns too, so a small group always has a full table. |
| `hub_zoo_helper_level` | Zoo helper level |

---

## 3. SCALE

### 3.1 Length by mode (target 3 to 10 minutes)
| Mode | Setting | Turns | Typical length |
|---|---|---|---|
| Co-op Tower | Little / Medium / Tall | 6 to 12 / 9 to 16 / 12 to 20 placements | 2.5 / 4 / 6 min at 12 s per turn (drag 6 s, settle 3 s, cheer 1.2 s, thinking) |
| Take Turns | 4 / 5 / 7 animals each | 2 players: 8 to 12 / 10 to 15 / 14 to 20 placements; 4 players: 16 to 24 / 20 to 30 / 28 to 40 | 2 players 2.5 / 3.5 / 5 min; 4 players 4.5 / 6 / 9 min |
| Solo Balance | 20 animals, 3 tries | 6 to 20 placements | 2 to 5 min |
The hub text promises "about 3, 5 or 8 minutes" for Take Turns (3 to 4 players). Playtest target: 90 percent of games inside 2 to 10 minutes.

### 3.2 Player counts
1 to 4 seats. Co-op and Solo run with 1. Take Turns needs at least 2 (helpers fill). Layout: portrait phone and tablet put the log in the centre, the shelf at the bottom (the active seat's side in Take Turns on a flat tablet); a Fold inner screen uses the tablet layout; landscape moves the shelf to the left edge.

### 3.3 Deterministic seeds and replay
- The engine takes `ZooConfig.seed` (the wrapper draws one at start). The bag shuffle, every helper decision and every assist is a function of the seed and the inputs; there is no other randomness.
- The simulation advances in whole steps of 1/120 s; the unit of time is the step index, never milliseconds.
- Replay = `ZooReplay` (physics version, seed, config ids, seat count, and every release as `(stepIndex, pieceId, xQ, angleQ)`): feeding it to a fresh engine reproduces every body pose bit for bit (test SZ-P1: raw `Double` bits after 20,000 steps; SZ-P2: golden hash of a scripted ten-animal tower). Changing any physics constant bumps `ZooPhysics.PHYSICS_VERSION`, which is stored in every replay and snapshot so old files are rejected, not mis-replayed.
- Helper "think time" is wall time, but the release they produce is applied at a step index, so replays stay exact.

### 3.4 Performance budget (design estimates, to be measured in M0 on the reference device)
| Item | Budget |
|---|---|
| Reference device | a mid phone (about 2021 Snapdragon 7-series or Dimensity class) in release mode; the Fold and tablet are faster |
| Bodies | at most 24 animals plus the log; 4 convex parts each at most (the elephant, giraffe and several others use 4); at most 128 contact points |
| One step with 24 bodies | estimate 70 to 150 microseconds (about 640 constraint solves at 10 iterations plus about 30 convex pair tests); budget: p95 under 0.15 ms |
| 60 Hz display | 2 steps per frame: under 0.3 ms of the 16.6 ms frame; 120 Hz display: 1 step per frame under 0.15 ms of the 8.3 ms frame |
| Worst frame | 5 steps maximum (a stalled frame drops time instead of spiralling); under 0.75 ms |
| Sleeping | a settled tower costs about nothing (early exit in step 1) |
| Placement sweep-down | one-off per turn: about 80 overlap tests against up to 24 bodies, under 0.5 ms |
| Hard helper rollout | at most 8 candidates x 150 steps x 0.1 ms = 120 ms on a background thread, never on the main thread |
| Allocation | zero objects inside `advance` after warm-up (state in preallocated `DoubleArray`s: 24 bodies x 9 values; contact and cache arrays of 128 entries). The wrapper builds one immutable `ZooState` per frame, and only when something moved (not while everything sleeps); at most one small allocation per frame for the body view list (test SZ-F2, SZ-F3) |
| Memory | under 1 MB for the world, under 2 MB for the 20 animal paths |
| Drawing | one `Path` per animal part, cached; the log and shelf are a handful of shapes; no perpetual particle loop when idle (audit 02 finding 3.4); no allocation in draw blocks (finding 3.7) |
| Battery | when everything is asleep and nothing animates, the frame loop pauses |

### 3.5 Resume and persistence
`ResumableGame` via `ZooSave` (JSON, `StackZooSaveCodec`): config ids, seed, RNG call count, step index, log angle and spin, every body (`pieceId, animalIndex, owner, x, y, angle, vx, vy, omega, restSteps, fallen`), piece to animal map, hands, bag, active seat, turn number, tries, goal hold counter, seat ids, physics version. Doubles round-trip exactly through JSON. The snapshot is taken at any moment except while an animal is being dragged (null then); a restore is state-exact, but the contact cache is not saved, so a restored run matches an uninterrupted one only within 1e-6 (test SZ-P6; replay equality applies to uninterrupted sessions). `restore()` validates everything before touching state (finite numbers, ranges, unique ids, seat ids; rules in the `ZooSave` KDoc); any failure returns false and the shell starts a fresh game.

### 3.6 Multiplayer plan
- **Now: same screen only.** No Nearby or Online listing in the Hub spec.
- **Later (Nearby): deterministic lockstep.** Turn-based play means only releases cross the wire. Each peer runs the same engine from the same seed. Messages: `Join(physicsVersion, animalTableHash)`, `ChoosePiece(seat, pieceId)`, `Ghost(seat, xQ, angleQ)` (cosmetic, 20 Hz, unreliable), `Release(seat, xQ, angleQ, applyAtStep)`, `Hash(step, value)` every 120 steps, `Resync(ZooSave)` from the host on a mismatch. `applyAtStep = currentStep + 18` (150 ms input delay) so every peer applies the release on the same step. The host owns the seed, the bag, helper decisions and the turn order; identity comes from the transport's `fromPlayerId`, never the payload (lesson of PR #9). Peers with a different physics version or animal table cannot join (the lobby refuses them).
- Cross-device float determinism is the main risk (K2): `Double` only, `StrictMath` trig, no FMA, fixed order; verified by a cross-device hash test on the Fold and the tablet before Nearby is enabled.

### 3.7 Phased delivery (S small under a day, M one to three days, L a week or more)
| Milestone | Content | Effort |
|---|---|---|
| M0 Physics core | Convex-compound bodies, SAT and clipping, sequential impulses with warm start, the rocking log with keel, sleeping, fall flag, state hash, sweep-down; tune k, c, KEEL against SZ-S*, SZ-P*; benchmark | L |
| M1 Rules and helpers | Bag, offers, Co-op, Solo, Take Turns, assist, helpers at three levels, save codec; un-ignore SZ-R*, SZ-B*, SZ-V* | M |
| M2 Screen and art (12 animals) | Same-screen UI, drag with ghost, two-finger twist and buttons, shelf, log, pond and balloons, five states, sounds and haptics, Reduced Motion | L |
| M3 Hub, shell, tutorial | Register spec, glyph, accent, route, tips, coach, ES review, tutorial, tuning with real children | M |
| M4 All 20 animals and Take Turns UI | 8 more animals, hands, penalty animation | M |
| M5 One-switch and TalkBack | Placing scan, announcements, snap help default in switch games | M |
| M6 Stats line | Solo personal best, dashboard line | S |
| M7 Nearby lockstep (later) | The messages in 3.6 and the cross-device hash test | L |

### 3.8 Risk register
| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| K1 | The physics numbers (keel, damping, friction) are untested guesses: the log is too wobbly or too stiff, towers collapse for no reason | High | High | M0 exists to tune against 50 scripted drops; every constant in `ZooPhysics`; Relaxed profile as fallback; benchmark and bot playtest before UI work |
| K2 | Floating-point results differ across devices, breaking replay and lockstep | Medium | High | `Double` only, `StrictMath`, fixed order, no FMA, golden hash test, cross-device test; same-screen play does not depend on it |
| K3 | Compound-convex stacking jitters or sinks (soft stacks) | Medium | High | warm starting, 10 + 4 iterations, linear slop 0.005, sleeping; test SZ-S5 (stack stays 60 s), SZ-S9 (penetration) |
| K4 | Look-alike claim against a published stacking game | Low | High | Mechanic only; original shapes authored as point lists, no tracing; roster reviewed (Q2); spare animals ready |
| K5 | Small hands cannot place accurately; frustration | High | Medium | Sweep-down removes vertical aiming; ghost 56 dp above the finger; snap help; Relaxed; Tiny goal |
| K6 | Settling takes too long and the game drags | Medium | Medium | Turn accepted after 8 s; Relaxed 1 s; damping tuned for about 4 s |
| K7 | Helpers feel robotic or too good | Medium | Medium | Visible eased drag, think time at least 800 ms, blunders at three levels, tuned in M3 |
| K8 | A fallen animal that is also the cause of other falls confuses who pays | Low | Low | Take Turns charges the active seat for every fall that turn, capped at 3 |
| K9 | A Fold tabletop posture or a 4-seat layout hides the shelf | Medium | Medium | Active seat's shelf always visible; layout test at M3 on the Fold and tablet |
| K10 | Snapshots from an older physics version replay differently | Low | Low | `physicsVersion` is stored and checked; mismatch means a fresh game |
| K11 | CPU cost on old phones | Low | Medium | Sleeping, 24-body cap, 5-step frame cap, frame loop pauses when idle |

### 3.9 Test plan (each row is an `@Ignore`d stub in `app/src/test/java/com/gamesuite/games/stackzoo/`; function name = lower-case ID + "_" + test name)
**`StackZooDeterminismTest`**

| ID | Test | What it proves |
|---|---|---|
| SZ-P1 | `same_seed_and_release_log_gives_bit_identical_body_poses_after_20000_steps` | compare raw Double bits of every body |
| SZ-P2 | `state_hash_matches_golden_value_for_scripted_ten_piece_tower` | golden FNV-1a hash, change requires a deliberate update |
| SZ-P3 | `advance_in_one_call_equals_advance_in_single_steps` | chunking does not matter |
| SZ-P4 | `engine_sources_use_no_wall_clock_default_random_or_fused_math` | text scan: no System.nanoTime, Random.Default, Math.fma, Math.sin or cos |
| SZ-P5 | `quantised_release_inputs_round_trip_through_json_exactly` | Int x and angle survive encode and decode |
| SZ-P6 | `restore_then_replay_matches_uninterrupted_run_within_tolerance` | warm start cache is cleared on restore, poses agree within 1e-6 |
| SZ-P7 | `step_order_independent_of_hash_map_iteration` | bodies and contacts use arrays sorted by id |

**`StackZooAnimalsTest`**

| ID | Test | What it proves |
|---|---|---|
| SZ-A1 | `twenty_animals_have_unique_ids_and_en_es_names` | resource lookup for every id |
| SZ-A2 | `every_part_is_convex_counter_clockwise_with_3_to_8_vertices` | cross product sign test |
| SZ-A3 | `every_animal_has_one_to_four_parts_and_parts_touch_or_overlap` | connected union |
| SZ-A4 | `mass_inertia_and_centroid_are_finite_and_positive` | derived inertia scaled to the authored mass |
| SZ-A5 | `width_and_height_fit_the_base_and_the_camera` | width at most 6.0 base, height at most 3.5 |
| SZ-A6 | `at_least_twelve_animals_have_concave_silhouettes` | union of parts is not convex |
| SZ-A7 | `friction_and_mass_within_table_ranges` | 0.30 to 0.90, 0.8 to 3.0 |

**`StackZooPhysicsTest`**

| ID | Test | What it proves |
|---|---|---|
| SZ-S1 | `flat_wide_animal_dropped_on_centre_of_base_rests_and_sleeps` | turtle at x 0, asleep within 3.5 s |
| SZ-S2 | `piece_released_beyond_base_edge_falls_and_is_flagged_fallen` | x 4.2 on a base 6 wide |
| SZ-S3 | `base_rocks_back_to_rest_with_damping_within_settle_window` | period near 1.6 s, rest within 6 s |
| SZ-S4 | `base_never_exceeds_hard_stop_angle` | 0.35 rad plus epsilon under a heavy edge load |
| SZ-S5 | `stack_of_five_flat_animals_stays_standing_for_sixty_seconds` | no fallen flags |
| SZ-S6 | `slippery_snake_slides_on_tilt_but_turtle_holds` | friction table sanity at 20 degrees |
| SZ-S7 | `sleeping_bodies_wake_when_hit_and_sleep_again` | sleep timer 60 steps |
| SZ-S8 | `sweep_down_placement_never_leaves_overlap_and_gap_is_at_most_point_one` | 800 random releases |
| SZ-S9 | `penetration_stays_below_point_zero_five_with_ten_stacked_bodies` | contact solver quality |
| SZ-S10 | `release_overlapping_other_body_lifts_out_deterministically` | no pop to the wrong side |
| SZ-S11 | `no_body_gains_energy_from_contacts_in_a_closed_drop_test` | restitution 0, energy never increases beyond epsilon |
| SZ-S12 | `relaxed_base_is_stiffer_and_settles_faster_than_normal` | k times 2, zeta 0.6 |
| SZ-S13 | `tall_heavy_stack_never_pulls_the_log_over_without_an_offset_load` | ballast keel: 14 animals centred, base angle stays under 0.05 rad |

**`StackZooRulesTest`**

| ID | Test | What it proves |
|---|---|---|
| SZ-R1 | `coop_goal_height_reached_and_held_for_1500ms_wins_with_we_did_it` | height measured from the base top |
| SZ-R2 | `coop_fallen_pieces_are_lost_and_empty_bag_means_try_again_together` | no player is marked as a loser |
| SZ-R3 | `coop_star_thresholds_are_zero_25_and_50_percent_of_bag_left` | 1, 2 and 3 stars |
| SZ-R4 | `take_turns_fallen_pieces_return_to_causer_capped_at_three` | extras go back to the box |
| SZ-R5 | `take_turns_first_empty_hand_wins_and_others_ranked_by_pieces_left` | ties share the rank |
| SZ-R6 | `take_turns_round_cap_ends_game_with_fewest_pieces_winning` | cap is three times the hand size |
| SZ-R7 | `solo_balance_three_lives_end_the_run_and_relaxed_has_none` | score is animals standing |
| SZ-R8 | `bag_deal_is_deterministic_for_seed_and_uses_at_most_two_copies` | 40 piece bag |
| SZ-R9 | `coop_turn_order_rotates_through_all_seats_without_skipping` | bots included |
| SZ-R10 | `large_assist_moves_release_toward_balance_spot_only_for_the_handicap_seat` | radius 1.4, pull 0.85 |
| SZ-R11 | `snap_rotation_assist_aligns_lowest_edge_within_25_degrees` | flat side down |
| SZ-R12 | `pick_of_three_offers_three_distinct_pieces_and_returns_the_others` | co-op and solo |
| SZ-R13 | `release_while_not_dragging_or_during_settle_is_ignored` | state machine guard |

**`StackZooBotTest`**

| ID | Test | What it proves |
|---|---|---|
| SZ-B1 | `easy_bot_blunder_rate_is_about_twenty_percent_and_hard_about_three_over_200_games` | tolerance 4 points |
| SZ-B2 | `bot_placement_is_deterministic_for_seed_and_state` | same inputs same release |
| SZ-B3 | `hard_bot_rollout_stays_within_time_budget` | at most 12 candidates, 150 steps each, off the main thread |
| SZ-B4 | `bot_think_time_is_at_least_800ms_and_drag_is_animated` | no instant drops |

**`StackZooSaveTest`**

| ID | Test | What it proves |
|---|---|---|
| SZ-V1 | `snapshot_restore_round_trips_all_body_state_at_turn_boundary` | poses, velocities, bag, hands, turn |
| SZ-V2 | `snapshot_is_null_while_dragging_or_match_over_or_before_start` | ResumableGame contract |
| SZ-V3 | `corrupt_snapshot_with_nan_infinity_or_out_of_range_values_is_rejected` | NaN, Infinity, 1e9, negative counts |
| SZ-V4 | `snapshot_with_unknown_animal_id_or_duplicate_piece_id_is_rejected` | validation |

**`StackZooGameTest`**

| ID | Test | What it proves |
|---|---|---|
| SZ-G1 | `game_lifecycle_init_start_release_end_reports_scores` | GameModule wrapper with a fake context |
| SZ-G2 | `leave_session_in_coop_reports_all_players_as_winners_with_the_team` | house convention |
| SZ-G3 | `release_after_match_over_returns_not_playing` | no crash |
| SZ-G4 | `game_exposes_id_stack_zoo_players_1_to_4_and_local_modes_only` | metadata |

**`StackZooHubSpecsTest`**

| ID | Test | What it proves |
|---|---|---|
| SZ-H1 | `hub_spec_is_kids_min_age_4_and_not_networked` | audience, minAge, no NEARBY or ONLINE mode |
| SZ-H2 | `every_hub_text_has_en_and_es_resource_under_hub_key` | same rule as HubLocalizationTest |
| SZ-H3 | `option_defaults_resolve_and_assist_choices_cover_four_seats` | none, all, p1..p4 |
| SZ-H4 | `strings_have_es_parity_and_no_todo_translate_in_values` | values and values-es key sets are equal |
| SZ-H5 | `launch_options_reach_extras_for_goal_length_assist_relaxed_and_switch` | buildHubLaunch round trip |

**`StackZooPerformanceTest`**

| ID | Test | What it proves |
|---|---|---|
| SZ-F1 | `step_with_24_bodies_stays_under_budget_benchmark` | manual benchmark, ignored in CI: p95 under 0.15 ms per step on the reference phone |
| SZ-F2 | `step_allocates_zero_objects_after_warmup` | allocation counter over 20000 steps |
| SZ-F3 | `world_view_publish_reuses_buffers` | at most one small allocation per frame |


Property and fuzz notes: SZ-P1 and SZ-P2 are the determinism proof; SZ-S8 is a 800-release property test (placement never leaves overlap, gap at most 0.1); SZ-B1 plays 200 seeded helper games per level; fuzz: random legal and illegal calls in random phases never throw, never produce NaN or infinity in any body, and never grow the body count past 24.

---

## 4. SCAFFOLD

### 4.1 What was created (compiling skeletons only; nothing compiled by the author)
Main package `app/src/main/java/com/gamesuite/games/stackzoo/`:
| File | Contents |
|---|---|
| `StackZooModels.kt` | Enums (`ZooMode`, `ZooPhase`, `ZooAssist`, `ZooGoal`, `ZooLength`, `ZooTier`, `ZooReleaseResult`), geometry (`ZooVertex`, `ZooPart`, `AnimalSpec`), `ZooSeat`, `ZooConfig`, `ZooState`, `ZooBodyView`, serialisable `ZooPlacement`, `ZooReleaseEvent`, `ZooReplay`, `ZooBodySave`, `ZooSave` (validation TODOs in KDoc) |
| `StackZooAnimals.kt` | The 20 animals as data (matches 2.2) |
| `StackZooPhysics.kt` | `ZooPhysics` and `ZooRules` constants (every number of 2.4 to 2.7) |
| `StackZooRng.kt` | SplitMix64 (`ZooRng`, real, deterministic) |
| `StackZooEngine.kt` | `StackZooEngine` interface and `StubStackZooEngine` returning safe values |
| `StackZooGame.kt` | `StackZooGame` (`GameModule`, `ResumableGame`, id `stack-zoo`, 1 to 4 players, local modes; frame time to fixed steps) and `StackZooSaveCodec` |
| `StackZooHubSpecs.kt` | `StackZooHubSpecs.spec`: `audience = GameAudience.KIDS`, `minAge = 4`, 4 modes, NOT registered anywhere |
Strings: `res/values/stack_zoo_strings.xml` and `res/values-es/stack_zoo_strings.xml` (UI, 20 animal names, new hub texts; real Spanish, review comment only).
Tests: 9 classes in `app/src/test/java/com/gamesuite/games/stackzoo/`, every test `@Ignore`d with the ID and a reason.

### 4.2 Snapshot DTO (`ZooSave`)
Fields: `version, physicsVersion, mode, goal, length, assist, relaxed, oneSwitch, difficulty, seed, rngCalls, stepIndex, baseAngle, baseOmega, bodies (ZooBodySave), pieceAnimals, hands, bag, activeSeat, turnNumber, lives, holdSteps, playerIds`. Validation rules are in the class KDoc (`TODO(stack-zoo)` in `StackZooSaveCodec.validate`, currently only versions and seat count).

### 4.3 Integration checklist for the coordinator (NOT done here; those files belong to others)
1. `core/GameHubSpecs.kt`: add `StackZooHubSpecs.spec` to `baseSpecs()` (ARCADE_PUZZLE section). Do NOT add `stack-zoo` to `networkedGames`.
2. `core/GameCatalog.kt`: glyph `"stack-zoo" -> "🦒"` (`StackZooHubSpecs.GLYPH`).
3. `ui/GameTheme.kt`: an accent pair for `stack-zoo` (contrast-checked by `GameThemeTest`).
4. `MainActivity.kt`: route `stack-zoo` with `rememberActiveModule(sessionManager, "stack-zoo") { StackZooGame() }` and a screen (not written in this round).
5. `ui/GameTips.kt` and `core/GameCoach.kt`: three tips and a coach card (tutorial lines in 2.12 can be reused); decide `ownCoachInGame`.
6. `core/GameAudienceTable.kt`: no row needed.
7. Gate: `./gradlew :app:testDebugUnitTest --tests "com.gamesuite.games.stackzoo.*"`, then the whole suite (the registry tests iterate every registered spec).
8. `stack_zoo_strings.xml` defines the hub texts; `hub_*` names reused from other files are listed in the KDoc of `StackZooHubSpecs` and in 2.14.

### 4.4 Statement
Nothing in this package was built, compiled, linted or run. No Gradle task, emulator or device was used. The Kotlin was written to the house conventions, but it has never seen a compiler. The physics constants are design values that have never been simulated.
