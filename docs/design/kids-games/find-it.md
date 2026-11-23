# Find It Frenzy (`find-it`)

Status 2026-11-07, branch `session/wave-3`. Scope, script, scale and scaffold package. **Nothing in this package has been built, compiled or run by Gradle, an emulator or a device.** The only things executed were throwaway Python scripts (outside the repo) that generated the tables below and checked the deck construction for orders 3, 5 and 7 (exhaustive pair test, see 2.3).

Legal rule (hard): every name, picture, shape, card layout and line of text here is original. Only the mechanics of the genre are reused (two cards that always share exactly one picture; a list of hidden items to find). No name, artwork, rulebook wording, picture list or component shape of Spot it!, Dobble, Eye Found It or any publisher's product is used, copied or traced. The picture list in 2.2 was chosen from scratch; common nouns (a bell, a cloud) are not protectable, but each icon is drawn by us from the primitive recipe in the table.

---

## 1. SCOPE

### 1.1 Purpose
A fast, friendly visual-matching and search game for the whole family. Three ways to play with one picture set:
- **Match Race** (1 to 4 seats, helpers fill empty seats): the middle card and your own card always share exactly one picture. Tap it on your card first and you take the middle card. Most cards wins.
- **Group Hunt** (1 to 4 seats, cooperative): a cheerful scene is full of little pictures. A list of 4 to 6 pictures shows what to find. The team finds them all before the sun sets: "We did it!" or "Try again together".
- **Solo Dash** (1 seat): match as many cards as you can before a minute is up (a Relaxed variant has no clock).

It is the cheapest game of the kids wave to build and the third most popular family in the research list (docs/research/bgg-geeklist-307608-page1.md: matching and speed is the third biggest family; research row "Find It Frenzy" is P1). The long tail matters: the genre is still played at 9 and by adults, so Match Race has a grown-up difficulty (8 pictures per card, helpers on Hard).

### 1.2 Audience and adult + child fairness
- Ages 4+ (`audience = KIDS`, `minAge = 4`), 1 to 4 players, same screen. No reading needed: pictures, colour + shape and sound carry every state.
- A 4 year old plays Tiny cards (4 pictures) or Little cards (6, the default); a grown-up can play the same table on Big cards (8) only in Solo Dash or against helpers, because on one table every seat sees the same card size (mixed card sizes would break the "exactly one shared picture" guarantee: see 2.3, edge cases).
- Fairness levers, all in the setup row with the same wording as the other kids games:
  1. **Hint glow for** (handicap, Match Race and Dash): none / everyone / Player 1 to 4. The chosen seat gets a gentle glow that first narrows the search to 3 pictures (after 5 s) and then pulses the answer (after 9 s). A grown-up plays without the glow, a child with it.
  2. **Card size**: table-wide "smaller deck" (Tiny 4 or Little 6 pictures per card).
  3. **Helper level**: Easy helpers are slow and make mistakes (see 2.8), so a child wins about half the rounds.
  4. **Relaxed play**: no wrong-tap wait, no clock, hints come sooner (3 s / 6 s).
  5. Exact ties rotate between seats (2.5, rule R5), so nobody has a permanent advantage.
- Group Hunt is cooperative, so a per-seat handicap makes no sense; the same setup row becomes **Hint speed** (no hints / slow / quick) for the whole team.

### 1.3 MVP vs v1 vs later
| Stage | Content |
|---|---|
| MVP (milestones M0 to M3) | Match Race and Solo Dash, same screen, Tiny and Little decks (13 and 31 pictures), helper seats at 3 levels, hint glow handicap, Relaxed, 5 UI states, first-run tutorial, EN + ES, resume, `@Ignore`d test plan un-ignored for deck, engine, bots, replay, save |
| v1 (M4 to M6) | Group Hunt with scene generator, Big deck (57 pictures, 8 per card), one-switch scanning, TalkBack announcements, parent-dashboard line, personal-best card for Dash |
| Later | Nearby (host-authoritative), daily seed challenge, custom picture packs from the kid's own drawings (needs a safety review), picture-theme packs, a "Kim's game" memory mode |

### 1.4 Out of scope
- Online play, free-text chat, accounts, ads, in-app purchases, analytics.
- Any camera or photo input (custom packs are "later" and need a separate privacy design).
- Voice recognition ("say the picture"): unreliable at age 4 and a privacy topic.
- Real-time Nearby racing in this round (latency fairness needs the host-authoritative design in 3.6).
- Reading-based modes (word matching). Spanish and English both use pictures.

### 1.5 Safety and age notes
- No flashing faster than 3 times a second anywhere (pulses are 1 Hz, win bursts are a single ring). Hint glow is a slow 1 Hz brightness swell; with Reduced Motion it becomes a static bright outline.
- Sound: short, soft, pitched, no sirens; the wrong-tap sound is a gentle low "bonk", never a buzzer. Haptics: one light tick per correct tap, none for wrong taps (no punishment feel).
- No loser language. Match Race ends with everyone's card count and a "star" badge for each player (2.7). Co-op wording: "We did it!" and "Try again together".
- Pointer safety on a shared screen: up to 4 children's hands, wrists and sleeves touch the glass. Reuse the palm rejection of the party games (`ContactSizes` in `ui/PartyTapTugArt.kt` plus `TapTugPointerTracker.maxContactPx`): a contact bigger than the palm limit is ignored for good, and taps on the gap between pictures are never a wrong tap (2.4).
- Name entry reuses `PlayerNamesDialog` and the shell name filter (`cleanPlayerName`); this game stores no personal data of its own.
- The picture set contains no weapons, brands, people or text.

### 1.6 Accessibility
- Targets: every picture cell is at least 56 dp across (see the card geometry in 2.4: minimum card diameters 136 dp Tiny, 168 dp Little, 187 dp Big). If four same-screen seats do not fit, the card size drops one step and a quiet notice is shown (never smaller targets).
- Shape + colour: every picture has its own silhouette and a colour token from an 8-colour palette, so colour is never the only cue. The 3 candidate pictures of a narrowing hint are marked with a ring, and the pulse is a ring plus a swell (not colour only). Colour-blind mode (existing setting) adds a pattern fill to the ring.
- TalkBack: each picture is a button named by `find_it_sym_<id>`, each card is a group named "<player> card"; a polite live region announces round results ("Mia found it and takes the middle card."). Wrong taps are announced to the tapper only.
- **One-switch (v1)**: card scan. A highlight ring visits the pictures of the player's own card one at a time (default 1.2 s each, 0.8 to 2.5 s in Settings), a short tick marks each step; the switch press selects the highlighted picture. Group Hunt scan: row, then column, then nearest picture (row-column scanning, 3 presses per pick). Nobody is penalised for scan speed: in one-switch games the wrong-tap wait is 0, the stall clue appears after 10 s, and a helper never beats a switch user to a round by more than the Easy schedule.
- **Relaxed variant**: no wrong-tap wait, no clock (Dash counts up and stops at 12 cards, Hunt has no sunset), hints at 3 s and 6 s, helpers one level slower. Chosen in setup, remembered per profile.
- Reduced Motion: no shake on wrong taps (a static outline flash instead), no confetti (a still star), card swaps are a quick cross-fade. Game Speed setting scales helper reaction times and hint timers (never the human lockout).
- Large text: no text is needed to play; score digits scale with the text setting; layout was designed to survive 1.6x.

### 1.7 What it teaches (one line for the parent dashboard)
`Builds quick visual matching, focus and taking turns.` (string `find_it_teaches`.)

### 1.8 Dependencies
- Shell: `GameModule`, `ResumableGame`, `GameHubSpec` (audience KIDS, minAge 4), `HubOptions`, `GameAudienceTable` (no row needed: the spec's own tags win), `PlayerNamesDialog`.
- Profiles foundation (being written by another agent): the KID profile age band filters this game in via `minAge`; profile-scoped data store for the Dash personal best. Nothing here reads profiles directly.
- Settings: Reduced Motion, Game Speed, Colour-blind mode, Text size, haptics and sound toggles (read by the screen later, not by the engine).
- Existing UI helpers for the later screen: `ContactSizes` and `TapTugPointerTracker` (palm rejection), `FoldAwareLayout`.
- No new libraries: `kotlinx-serialization-json` (already a dependency) for the snapshot.
- Shared files the coordinator must edit (listed in 4.3): `GameHubSpecs`, `GameCatalog`, `GameTheme`, `MainActivity`, `GameTips`, `GameCoach`.

### 1.9 Open questions (each with a recommended default)
| # | Question | Recommended default |
|---|---|---|
| Q1 | The brief says an "8-symbols-per-card variant for little kids at 6 per card". Read as: Big = 8 per card (57 pictures), Little = 6 per card (31 pictures), and a Tiny 4-per-card tier for the youngest. Correct? | Yes. Default card size is **Little (6)**; Big (8) is for older kids and grown-ups. |
| Q2 | Picture art: draw all 57 icons in code (Compose Path, recipes in 2.2) or commission vector art? | Code-drawn from the recipes (consistent look, tiny APK, no licensing). Budget L for the art pass; ship the first 31 first. |
| Q3 | Should Match Race let a player tap the picture on the middle card instead of on their own card? | No: own card only. On a shared screen the middle card sits between players, and tapping it would let one player reach over another. |
| Q4 | Wrong-tap wait: 1.5 s lock-out, or no punishment at all by default? | 1.5 s for 5+ (stops button mashing), automatically 0 on Tiny cards and in Relaxed. Playtest with real 4 year olds before locking. |
| Q5 | Should Group Hunt count points per player? | No. Cooperative: no per-player score. The result card shows "found by" stars only as a flourish. |
| Q6 | Co-op result for stats (house convention in Bridge Builders: everyone is a winner once progress was made). Keep for Group Hunt? | Keep, so a KID profile never shows a "loss". Revisit with the profiles owner if adult stats need honesty. |
| Q7 | Nearby (later): is host-authoritative fair enough for a reflex game? | Yes with RTT compensation and an 80 ms tie window (3.6). If playtests disagree, ship Nearby only for Group Hunt. |
| Q8 | Do we allow two helpers plus one child on the same table in Match Race? | Yes (min 2 seats): a lone child always has an opponent. |
| Q9 | Glyph and accent colour for the menu card (coordinator picks; both are in shared files) | Glyph U+1F440 (eyes), not used by any game today; accent chosen by the coordinator with `GameThemeTest` contrast checks. |
| Q10 | Spanish title | "Busca Ya" (native speaker review before release; strings carry a `TODO translate` review comment). |

---

## 2. SCRIPT

### 2.1 Components and look (original)
- Kids theme: warm cream background, Baloo-style rounded type for the few digits, thick rounded outlines, soft drop shadows, 56 dp minimum targets, 16 dp gutters. Each picture is a flat vector with one colour token and a darker outline.
- **Card**: a round disc with a cream face and a coloured rim in the seat colour (shape + colour tokens: circle, square, triangle, star for seats 1 to 4; the same four tokens the party games use).
- **Middle card**: slightly larger, with a gentle bobbing shadow.
- **Scene** (Group Hunt): a hand-drawn-style meadow, beach or attic backdrop (3 vector backdrops, one per scene) behind the scattered pictures; a "sun" progress bar at the top that sets slowly.
- **Hunt strip**: a row of 4 to 6 target icons with an empty tick circle under each; found ones get a filled check and a star shape (shape + colour).
- Helpers are small friendly critters (fox, owl, hedgehog) shown as a seat badge; they are computer seats called "CPU" by the shell, shown with a critter icon.

### 2.2 The picture set (57 original icons, data)
`FindItSymbols.all` in code is this table, in this order. Colour token = one of 8 (coral, sun, leaf, sky, grape, berry, teal, clay). Deck column: the first 13 pictures are the Tiny deck, the first 31 the Little deck, all 57 the Big deck.
| # | id | EN | ES | colour | in decks | drawing recipe |
|---|---|---|---|---|---|---|
| 1 | `sunflower` | sunflower | girasol | sun | Tiny, Little, Big | brown disc, ten petal ellipses, short stem |
| 2 | `butterfly` | butterfly | mariposa | grape | Tiny, Little, Big | two rounded wing pairs, thin body, two antennae |
| 3 | `goldfish` | goldfish | pez dorado | coral | Tiny, Little, Big | teardrop body, fan tail, round eye |
| 4 | `mushroom` | mushroom | seta | berry | Tiny, Little, Big | half-disc cap with dots, rounded stalk |
| 5 | `umbrella` | umbrella | paraguas | sky | Tiny, Little, Big | scalloped half-disc, hooked handle |
| 6 | `kite` | kite | cometa | coral | Tiny, Little, Big | diamond, cross spars, wavy tail with bows |
| 7 | `teapot` | teapot | tetera | teal | Tiny, Little, Big | squat body, spout, handle loop, lid knob |
| 8 | `cupcake` | cupcake | magdalena | berry | Tiny, Little, Big | pleated trapezoid cup, swirl top, cherry |
| 9 | `sailboat` | sailboat | velero | sky | Tiny, Little, Big | hull trapezoid, two triangle sails, mast |
| 10 | `rocket` | rocket | cohete | coral | Tiny, Little, Big | capsule body, nose cone, two fins, window |
| 11 | `acorn` | acorn | bellota | clay | Tiny, Little, Big | oval nut, cross-hatched cap, tiny stem |
| 12 | `cherries` | cherries | cerezas | berry | Tiny, Little, Big | two discs on curved stems, one leaf |
| 13 | `bee` | bee | abeja | sun | Tiny, Little, Big | striped ellipse body, two wing ovals, stinger |
| 14 | `balloon` | hot-air balloon | globo aerostático | grape | Little, Big | teardrop envelope with stripes, basket, ropes |
| 15 | `icecream` | ice-cream cone | helado | sun | Little, Big | waffle triangle cone, two scoops |
| 16 | `windmill` | windmill | molino | clay | Little, Big | tapered tower, four sail blades on hub |
| 17 | `crab` | crab | cangrejo | coral | Little, Big | flat oval body, two claws, six leg strokes |
| 18 | `hedgehog` | hedgehog | erizo | clay | Little, Big | dome of spikes (triangle fringe), snout, eye |
| 19 | `rainbow` | rainbow | arcoíris | sky | Little, Big | four concentric half-rings, two cloud ends |
| 20 | `lighthouse` | lighthouse | faro | coral | Little, Big | striped tapered tower, lamp room, light beams |
| 21 | `bicycle` | bicycle | bicicleta | teal | Little, Big | two rings, triangle frame, handlebar, saddle |
| 22 | `seashell` | seashell | concha | berry | Little, Big | fan with radial ridges, scalloped edge |
| 23 | `pear` | pear | pera | leaf | Little, Big | two stacked ovals, stem, leaf |
| 24 | `drum` | drum | tambor | coral | Little, Big | cylinder, zigzag tension cords, two sticks |
| 25 | `octopus` | octopus | pulpo | grape | Little, Big | round head, eight curled arm strokes, eyes |
| 26 | `lantern` | paper lantern | farolillo | sun | Little, Big | ribbed ellipse, top and bottom caps, tassel |
| 27 | `pinecone` | pinecone | piña de pino | clay | Little, Big | oval of overlapping scale arcs, stalk |
| 28 | `mitten` | mitten | manopla | sky | Little, Big | rounded hand, thumb, striped cuff |
| 29 | `crown` | crown | corona | sun | Little, Big | band with three points, three gems |
| 30 | `cheese` | cheese wedge | queso | sun | Little, Big | triangle prism side, round holes |
| 31 | `starfish` | starfish | estrella de mar | coral | Little, Big | five-arm star with dotted ridges |
| 32 | `trumpet` | trumpet | trompeta | sun | Big | tube loop, flared bell, three valves |
| 33 | `volcano` | volcano | volcán | coral | Big | cone, crater, lava drips, smoke puffs |
| 34 | `paintbrush` | paintbrush | pincel | teal | Big | slanted handle, ferrule band, tapered bristles |
| 35 | `boot` | rain boot | bota de agua | clay | Big | L-shaped upper, sole, pull tab |
| 36 | `parrot` | parrot | loro | leaf | Big | curved beak, round head, long tail feathers |
| 37 | `pretzel` | pretzel | pretzel | clay | Big | looped rope shape with salt dots |
| 38 | `compass` | compass | brújula | teal | Big | ring, four-point needle star, N tick |
| 39 | `hourglass` | hourglass | reloj de arena | sun | Big | two triangles, frame caps, falling sand line |
| 40 | `gem` | gem | gema | sky | Big | faceted hexagon with inner triangles |
| 41 | `bell` | bell | campana | sun | Big | flared dome, clapper, top loop |
| 42 | `castle` | castle | castillo | grape | Big | block wall, two towers, arch door, flags |
| 43 | `seahorse` | seahorse | caballito de mar | teal | Big | curled tail spiral, snout, belly ridges |
| 44 | `caterpillar` | caterpillar | oruga | leaf | Big | five overlapping discs, face, two feelers |
| 45 | `jellyfish` | jellyfish | medusa | grape | Big | dome, wavy tentacle strokes |
| 46 | `feather` | feather | pluma | sky | Big | leaf-shaped vane, centre quill, barbs |
| 47 | `banana` | banana | plátano | sun | Big | crescent with two tips |
| 48 | `pizza` | pizza slice | porción de pizza | coral | Big | triangle slice, crust arc, pepperoni discs |
| 49 | `lollipop` | lollipop | piruleta | berry | Big | swirled disc on a stick |
| 50 | `cloud` | cloud | nube | sky | Big | three overlapping discs on flat base |
| 51 | `mountain` | mountain | montaña | grape | Big | two triangles, snow caps |
| 52 | `wave` | wave | ola | teal | Big | curling crest with foam dots |
| 53 | `spiral` | spiral | espiral | berry | Big | thick Archimedean spiral stroke |
| 54 | `ladder` | ladder | escalera | clay | Big | two rails, five rungs |
| 55 | `friedegg` | fried egg | huevo frito | sun | Big | wobbly white blob, round yolk |
| 56 | `flamingo` | flamingo | flamenco | berry | Big | S-curve neck, bent beak, one long leg |
| 57 | `wateringcan` | watering can | regadera | leaf | Big | body, long spout with rose, handle |

Selection rules used: no text, no people, no brands, no weapons; every silhouette distinct at 56 dp; at most 7 pictures per colour token; the first 31 are the most easily named by a 4 year old (the Little deck), the next 26 add shapes that need a little more looking. Names are common words; the drawings are ours.

### 2.3 Deck generation (guaranteed one match)

**Why a projective plane.** We need N cards, each with k pictures, so that any two different cards share exactly one picture. A finite projective plane of order n gives exactly that with N = n^2 + n + 1 cards, n^2 + n + 1 pictures and k = n + 1 pictures per card. With n prime the construction needs only arithmetic modulo n.

| Card size | n | pictures per card | cards | pictures used | min card diameter for 56 dp cells |
|---|---|---|---|---|---|
| Tiny | 3 | 4 | 13 | first 13 | 136 dp |
| Little | 5 | 6 | 31 | first 31 | 168 dp |
| Big | 7 | 8 | 57 | all 57 | 187 dp |

**Algorithm** (n prime; picture indexes 0 until n^2 + n + 1):
```
aff(x, y)  = x * n + y            // the n*n affine points
slope(m)   = n * n + m            // one point at infinity per slope m in 0 until n
vert       = n * n + n            // the point at infinity of the vertical lines
cards = []
for m in 0 until n:               // n*n sloped lines  y = m*x + b (mod n)
  for b in 0 until n:
    cards += [ aff(x, (m*x + b) mod n) for x in 0 until n ] + [ slope(m) ]
for c in 0 until n:               // n vertical lines  x = c
  cards += [ aff(c, y) for y in 0 until n ] + [ vert ]
cards += [ slope(0), ..., slope(n-1), vert ]      // the line at infinity
```
Each card has exactly n + 1 entries. A seeded deck order is `FindItRng(seed).shuffle(0 until N)` (Fisher-Yates from the top index down). The reference Python implementation was run for n = 3, 5, 7: all pairs share exactly one picture, every picture is on exactly n + 1 cards, all cards have n + 1 distinct pictures.

**Proof sketch** (two distinct cards are two distinct lines; they meet in exactly one point):
1. Two sloped lines y = m x + b and y = m' x + b' with m != m': the affine intersection solves (m - m') x = b' - b, which has exactly one solution x mod n because m - m' is invertible mod n (n prime). Their infinity points slope(m) and slope(m') differ, so they share exactly 1 picture.
2. Two sloped lines with the same slope and b != b': no affine solution (parallel), but both contain slope(m): exactly 1 shared picture.
3. A sloped line and a vertical line x = c: exactly one affine point (c, m c + b); their infinity points differ (slope(m) vs vert): exactly 1.
4. Two vertical lines: no common affine point (different c), both contain vert: exactly 1.
5. The line at infinity meets a sloped line only in slope(m), and a vertical line only in vert: exactly 1.
Counting: n^2 + n + 1 lines, each with n + 1 points; the dual argument (two points lie on exactly one line) gives that every picture is on exactly n + 1 cards.

**Edge cases and rules**
- Order must be 3, 5 or 7. Orders 4 and 8 (prime powers) need finite-field arithmetic and are not offered; any other order returns an empty deck (never throws).
- Any subset of the deck keeps the property (a subset of lines still pairwise meets in one point), so a game never needs all the cards.
- One card size per table. Mixing a 6-picture card with an 8-picture card cannot guarantee exactly one shared picture, which is why "smaller deck" is table-wide and the per-seat handicap is a hint glow instead.
- A centre card must never equal a card held by any seat (an identical card would share all its pictures). Cards are dealt from a shuffled pile of distinct cards; when the pile runs out it is reshuffled from the discards, **excluding every held card**, and the new top is never the same card as the previous centre.
- Rounds are not limited by deck size: with the Tiny deck (13) and 4 seats the pile holds 8 cards and is reshuffled as needed.
- Picture identity is by index, never by size or rotation: the same picture is drawn at different sizes and angles on different cards (layout 2.4).
- The tutorial uses the Tiny deck with a fixed seed and hand-picked cards.

### 2.4 Card layout, hit testing and the Group Hunt scene
**Card layout** (`ProjectiveDeck.layoutCard(card, seed, cardIndex)`, deterministic): slots in the unit disc.
| Card size | Slots | Ring radius | Scale range | Rotation |
|---|---|---|---|---|
| Tiny | ring of 4 | 0.586 | 0.90 to 1.00 | within 45 degrees |
| Little | ring of 6 | 0.667 | 0.80 to 1.00 | within 45 degrees |
| Big | centre (scale 0.8) + ring of 7 | 0.697 | 0.70 to 1.00 | 0 to 359 |
Jitter of at most 0.03 is added per slot; the picture-to-slot assignment is a seeded permutation (`FindItRng(seed xor cardIndex*0x9E37)`). Cell radius is the largest circle that fits: Tiny 0.414, Little 0.333, Big min 0.30 (centre 0.40). Minimum card diameters for 56 dp cells are in the table in 2.3.

**Hit testing**: a tap goes to the nearest slot centre if it lies within 1.15 times that slot's cell radius; otherwise it is ignored (a tap in a gap is never a wrong tap). Targets are the cell circles, so the touch area is larger than the drawn icon.

**Group Hunt scene** (`ProjectiveDeck.buildScene(targets, itemCount, pictureCount, seed)`), virtual canvas x 0..1, y 0..1.333 (3:4), letterboxed to the screen:
1. Targets: `targetCount` pictures chosen by a seeded shuffle of the active pictures (Tiny 4, Little 5, Big 6).
2. Items: all targets once, then decoys drawn from the remaining pictures, each decoy at most twice, until `itemCount` (Tiny 12, Little 16, Big 22).
3. Placement (best candidate, 30 candidates per item): item radius 0.085 x scale (scale 0.90 to 1.10), rotation within 35 degrees; each candidate is rejected if its centre is closer than 1.15 x (r_a + r_b) to a placed item or its disc leaves the canvas; among accepted candidates keep the one farthest from all placed items. Targets are placed first so they are never squeezed out.
4. Capacity fallback: if an item has no accepted candidate, its scale lower bound drops by 0.05 (down to 0.80) and the item is retried once; if still impossible the item is skipped (itemCount - 1). Targets are never skipped (the fallback only ever trims decoys).
5. Target radius 0.085 on a 336 dp wide canvas is 28 dp (56 dp across): meets the target size.

### 2.5 Rules as a numbered script
**Common**
- C1. Setup (Hub): mode, card size, game length, handicap (hint glow) or hint speed, relaxed, one-switch, people, helpers, helper level. The shell builds the roster; the seed is drawn once by the game wrapper (`System.nanoTime` is allowed there only) and handed to the engine, which never reads a clock or `Random.Default`; `playAgain` derives the next seed from the previous one with SplitMix64.
- C2. READY screen: the table shows "Ready?" and a large Start button (Tap to start). Anyone presses it; helpers wait. Then the cards flip with a short 3-2-1 (no 3-2-1 under Reduced Motion: just "Go!"). The engine clock starts at GO.
- C3. Time is engine time in milliseconds advanced only by `advance(dtMs)`; the screen sends its frame time. Pause freezes it.

**Match Race (and Solo Dash uses the same round)**
- R1. Deal: seeded shuffle of the deck. Each seat gets one card face up in its own area; one card goes face up in the middle.
- R2. Every card shares exactly one picture with the middle card. Each seat taps that picture on its OWN card.
- R3. First correct tap (lowest engine timestamp) wins the round: the seat's card is replaced by the old middle card (it "takes the middle card"), the seat scores 1, the old own card is discarded, a new middle card is dealt (a 0.9 s celebration for the winner, no shake, a ring burst).
- R4. A wrong tap (a picture that is on the seat's card but not on the middle card): that seat is locked for 1,500 ms (a grey cloud over its card; taps ignored), the seat's miss count rises, a soft bonk plays. Relaxed or Tiny cards or one-switch: no lock.
- R5. Simultaneous taps: the lower engine timestamp wins; equal timestamps (same millisecond) go to the seat with the lower (seat index + round index) mod seat count, so the advantage rotates.
- R6. If nobody has tapped correctly after 20 s (30 s on Little cards, 10 s with one-switch), "Here is a clue": the shared picture pulses on every card for 2 s; the first correct tap still wins.
- R7. Hint glow handicap: a seat chosen in setup (or all seats) sees 3 candidate pictures ringed after 5 s of the round (3 s Relaxed) and the answer pulsing after 9 s (6 s Relaxed). The ring's three candidates are the answer plus two other pictures of that card chosen by a seeded draw, so the answer is not always the same one of the three.
- R8. End: when the round count is reached (Quick 15, Normal 24, Long 36) the game is over. Most cards wins; equal top scores share the win. A tie-break within a round (R5) never decides the game.
- R9. Helpers act as in 2.8. A helper's tap goes through exactly the same `tap()` as a person's.

**Solo Dash**
- D1. One seat; the same deal. The clock length depends on the card size (D6).
- D2. A correct tap scores 1, replaces the player's card with the middle card and deals a new middle card.
- D3. A wrong tap costs 2 s off the clock (no lock). Relaxed: no clock, no penalty, and the game ends after 12 cards.
- D4. The game ends at zero. Score = cards matched; personal best is kept by the shell stats (score in `PlayerScore`); a new best shows "New best!".
- D5. Hint glow ("Hint glow", on or off): same timings as R7.
- D6. Clock length by card size: Tiny 90 s, Little 90 s, Big 60 s (bigger cards take longer to scan, so Big is the "grown-up" Dash).

**Group Hunt**
- H1. The team (1 to 4 seats; at most 1 helper) sees a scene and the hunt strip of targets.
- H2. Anyone taps any picture in the scene. A tap on an unfound target finds it: the picture pops, the strip fills, a soft chime, the announcement "Found <name>. N to go."
- H3. A tap on anything else is a miss: a soft bonk, the sunset advances by 2 s (Relaxed: no penalty). No seat is locked out in Group Hunt.
- H4. The sun sets over 90 s (Little 120 s) per scene (Relaxed and one-switch: no sunset). If all targets are found, the scene clears (1.5 s celebration) and the next scene starts.
- H5. Hints: after 20 s without a find (slow) or 10 s (quick) the strip icon of the next unfound target glows; 10 s after that a soft spotlight ring (radius shrinking from 0.45 to 0.15 canvas units over 4 s) circles the picture. Relaxed halves both delays.
- H6. Scenes per game: Quick 2, Normal 3, Long 4. Clearing all scenes shows "We did it!". If the sun sets in a scene, "The sun has set. Try again together": the same scene (same seed, so the same picture positions) restarts with found items reset, and it is not a loss; the session tally counts it as a try.
- H7. A helper (computer seat) finds one random unfound target every 12 s (Easy), 8 s (Medium) or 5 s (Hard), with 25 percent timing jitter, and never takes the final target while a person is seated.

### 2.6 State machine
```
SETUP (Hub, not in the engine)
  -> READY            (cards or scene dealt, "Ready?" visible; seeds fixed)
READY --go()--> PLAYING
PLAYING --correct tap (Race, Dash)--> ROUND_WON (900 ms) --> PLAYING (next round) | OVER (rounds done or Dash clock 0)
PLAYING --last target found (Hunt)--> SCENE_CLEAR (1,500 ms) --> PLAYING (next scene) | OVER (all scenes cleared: "We did it!")
PLAYING --sunset reaches zero (Hunt)--> SCENE_TIMEOUT --tap Try again together--> READY (same scene)
OVER --Play again--> READY (fresh seed) ; OVER --Done--> shell result screen
PAUSE: a flag at any phase (clock, helpers, hints and lockouts all freeze)
```
Allowed inputs per phase: READY accepts `go`; PLAYING accepts `tap` and `advance`; ROUND_WON, SCENE_CLEAR and OVER accept `advance` only (taps return `NOT_PLAYING`); SCENE_TIMEOUT accepts `go` (retry).

### 2.7 Scoring and results
- Match Race: score = cards won. `PlayerScore(score = cards, isWinner = score == top && top > 0)`. Ties share the win.
- Solo Dash: score = cards matched; `isWinner = true` when at least 1 card was matched (a kid never "loses" a solo run).
- Group Hunt: no per-player score; every seat gets `score = found-by-seat count` for the flourish only and `isWinner = true` when at least one scene was cleared or one target found (house co-op convention, Q6).
- Badges on the score card (no loser badge): **Fastest finder** (lowest median round time), **Sharp eyes** (fewest wrong taps, at least 3 rounds played), **Card collector** (most cards). Several players can hold badges; at least one badge is shown per player (the first available from the list in that order, else Sharp eyes).
- Score card: five columns at most, large digits, a star shape per badge, "Play again" and "Done" buttons (two large buttons, no scrolling).

### 2.8 Helper (computer seat) behaviour per level
Bots use only the public API: they read the state and call `tap()`.

| | Easy | Medium | Hard |
|---|---|---|---|
| Round reaction (ms, mean / sd) | 7,000 / 2,000 | 4,000 / 1,200 | 2,200 / 700 |
| Wrong-tap chance per round | 15 percent | 7 percent | 2 percent |
| Reaction multiplier by card size | Tiny 0.7, Little 1.0, Big 1.3 | same | same |
| After a lock-out | waits the lock, then re-samples 1,500 + 500 | same | same |
| Hunt find interval (s) | 12 | 8 | 5 |

- The reaction time is drawn once per round per helper from a normal distribution (Box-Muller from the engine RNG, clipped to 0.5 x mean .. 2.5 x mean) and never below 1,200 ms (a helper is never inhumanly instant). The schedule is derived from (seed, round, seat), so the whole game is deterministic.
- A wrong-tap round: the helper taps a random other picture on its card at its reaction time, is locked, then retries.
- The Easy helper is tuned so a 4 to 6 year old on Tiny or Little cards wins about half the rounds against one helper (a playtest target, risk K4).
- Helper level follows the shell rule: "Settings default / Easy / Medium / Hard" (`HubOptions.difficulty()`); Game Speed scales helper times.

### 2.9 Handicap rule
- Match Race and Dash: **Hint glow for** none / every player / Player 1 to 4 (hub key `handicap`: `none`, `all`, `p1` to `p4`). Stage 1 (5 s): the 3 candidate ring; stage 2 (9 s): the answer pulse. Relaxed: 3 s and 6 s. The glow does not stop the seat from tapping other pictures.
- Smaller deck: table-wide **Card size** (Tiny 4 or Little 6 per card).
- Group Hunt: **Hint speed** for the team.

### 2.10 First-run tutorial script (English lines, each under 120 characters)
Shown once per profile on first launch of the game (coach card keeps a "How to play" button). It runs a scripted Tiny-deck round with no helpers and no clock. A pointing-hand sprite moves to the target (a static arrow under Reduced Motion). "Skip" is always visible.
| Step | Screen | EN line | Advances when |
|---|---|---|---|
| 1 | Two cards, no interaction | Every card has pictures. Any two cards share exactly one. | tap Next |
| 2 | Hand points at both cards | Look at your card and the middle card. | tap Next |
| 3 | The shared picture pulses | Tap the picture that is on both cards. | player taps it (a wrong tap just shows "Oops! Look again") |
| 4 | Burst, card slides over | Be first and you take the middle card! | tap Next |
| 5 | Grey cloud demo on the card | Wrong tap? Wait a moment and look again. | tap Next |
| 6 | Tiny hunt scene preview | In Group Hunt, find every picture on the list together. | tap Next |
| 7 | One free practice round | Your turn. Find the shared picture. | player taps it |
| 8 | Done | Let us play! | tap the button |

### 2.11 The five UI states
| State | What the table shows |
|---|---|
| Setup | Hub: mode tiles, then options (card size, length, helpers, hint glow, Relaxed, one-switch). Big pictures on the option rows; all text also read by TalkBack |
| Start | READY: dealt cards face down, names or critter badges, a big "Ready?" start button, the tutorial replay button |
| Mid-game | Race/Dash: the middle card, each seat's card in its quarter (rotated for opposite seats), score pips, lock clouds, hint rings, the Dash clock bar. Hunt: the scene, the hunt strip, the sun bar |
| Post-game | ROUND_WON celebration (winner's rim lights) or SCENE_CLEAR; then the OVER banner "<name> wins!" / "It is a tie!" / "We did it!" / "Try again together" |
| Score card | Cards per player (large), badges, personal best for Dash, "Play again" and "Done" |

### 2.12 Exact English strings
Resource names are `find_it_<suffix>` in `res/values/find_it_strings.xml` (Spanish in `values-es`, real Spanish values, a review comment only). Picture names are `find_it_sym_<id>` (table in 2.2). Hub texts use `hubKey(text)` names; the new ones are listed after the UI table.

| key | EN |
|---|---|
| `find_it_title` | Find It Frenzy |
| `find_it_help_title` | Find It Frenzy |
| `find_it_help_text` | Match Race: every card shares exactly one picture with the middle card. Tap it on your card before the others do. Group Hunt: find every picture on the list together. Solo Dash: match as many cards as you can before the clock runs out. |
| `find_it_mode_race` | Match Race |
| `find_it_mode_hunt` | Group Hunt |
| `find_it_mode_dash` | Solo Dash |
| `find_it_ready` | Ready? |
| `find_it_tap_to_start` | Tap to start |
| `find_it_go` | Go! |
| `find_it_paused` | Paused |
| `find_it_resume` | Keep playing |
| `find_it_prompt_race` | Find the picture on both cards |
| `find_it_prompt_hunt` | Find every picture on the list |
| `find_it_prompt_dash` | Match the cards before time runs out |
| `find_it_you_got_it` | You got it! |
| `find_it_got_it` | %1$s got it! |
| `find_it_oops` | Oops! Look again |
| `find_it_wait_a_moment` | Wait a moment |
| `find_it_clue` | Here is a clue |
| `find_it_cards_count` | Cards: %1$d |
| `find_it_winner` | %1$s wins! |
| `find_it_tie` | It is a tie! |
| `find_it_we_did_it` | We did it! |
| `find_it_try_again_together` | Try again together |
| `find_it_play_again` | Play again |
| `find_it_done` | Done |
| `find_it_best` | Best: %1$d |
| `find_it_new_best` | New best! |
| `find_it_badge_fastest` | Fastest finder |
| `find_it_badge_sharp` | Sharp eyes |
| `find_it_badge_collector` | Card collector |
| `find_it_scene_n` | Scene %1$d of %2$d |
| `find_it_found_n` | Found %1$d of %2$d |
| `find_it_time_left` | Time left |
| `find_it_scene_timeout` | The sun has set. Try again together |
| `find_it_cd_card` | %1$s card |
| `find_it_cd_centre` | Middle card |
| `find_it_cd_symbol_tap` | %1$s. Tap if it is on both cards |
| `find_it_cd_hunt_item` | %1$s in the scene |
| `find_it_cd_target` | Look for %1$s |
| `find_it_cd_target_found` | %1$s, found |
| `find_it_cd_hint_glow` | Hint glowing |
| `find_it_cd_locked` | %1$s must wait |
| `find_it_ann_round_won` | %1$s found it and takes the middle card. |
| `find_it_ann_wrong` | %1$s tapped the wrong picture. |
| `find_it_ann_found` | Found %1$s. %2$d to go. |
| `find_it_ann_scene_clear` | Scene cleared! |
| `find_it_ann_new_card` | New middle card. |
| `find_it_switch_press` | Press your switch to choose the glowing picture |
| `find_it_switch_row` | Pick a row |
| `find_it_switch_column` | Pick a column |
| `find_it_teaches` | Teaches quick visual matching, focus and taking turns. |
| `find_it_tut_1` | Every card has pictures. Any two cards share exactly one. |
| `find_it_tut_2` | Look at your card and the middle card. |
| `find_it_tut_3` | Tap the picture that is on both cards. |
| `find_it_tut_4` | Be first and you take the middle card! |
| `find_it_tut_5` | Wrong tap? Wait a moment and look again. |
| `find_it_tut_6` | In Group Hunt, find every picture on the list together. |
| `find_it_tut_try` | Your turn. Find the shared picture. |
| `find_it_tut_skip` | Skip |
| `find_it_tut_next` | Next |
| `find_it_tut_start` | Let us play! |

Hub texts added by this game (name = `hubKey(text)`; texts that already exist elsewhere are reused and not listed):
| name | EN |
|---|---|
| `hub_picture_count` | Picture count |
| `hub_fewer_pictures_on_a_card_make_the_match_easier_t_e5c68c` | Fewer pictures on a card make the match easier to spot. |
| `hub_tiny_cards` | Tiny cards |
| `hub_4_pictures_on_each_card_the_easiest_match` | 4 pictures on each card: the easiest match. |
| `hub_little_cards` | Little cards |
| `hub_6_pictures_on_each_card_great_for_ages_4_to_6` | 6 pictures on each card: great for ages 4 to 6. |
| `hub_big_cards` | Big cards |
| `hub_8_pictures_on_each_card_a_faster_game_for_practi_a951c1` | 8 pictures on each card: a faster game for practised eyes. |
| `hub_about_3_4_or_6_minutes` | About 3, 4 or 6 minutes. |
| `hub_about_3_minutes` | About 3 minutes. |
| `hub_normal_round` | Normal round |
| `hub_about_4_minutes` | About 4 minutes. |
| `hub_long_round` | Long round |
| `hub_about_6_minutes` | About 6 minutes. |
| `hub_hint_glow_for` | Hint glow for |
| `hub_a_gentle_glow_narrows_the_search_for_one_player_f73b11` | A gentle glow narrows the search for one player, so different ages can play together. |
| `hub_player_3` | Player 3 |
| `hub_player_4` | Player 4 |
| `hub_hint_glow` | Hint glow |
| `hub_a_glow_narrows_the_search_while_you_play_on_your_429203` | A glow narrows the search while you play on your own. |
| `hub_no_hints` | No hints |
| `hub_hints_on` | Hints on |
| `hub_relaxed_play` | Relaxed play |
| `hub_no_waiting_after_a_wrong_tap_no_clock_and_hints_016899` | No waiting after a wrong tap, no clock, and hints come sooner. |
| `hub_use_a_single_button_a_highlight_scans_the_pictur_121f08` | Use a single button: a highlight scans the pictures and you press to choose. |
| `hub_hint_speed` | Hint speed |
| `hub_a_glow_points_to_a_picture_you_have_not_found_ye_b3138b` | A glow points to a picture you have not found yet. |
| `hub_slow_hints` | Slow hints |
| `hub_quick_hints` | Quick hints |
| `hub_race_the_helpers` | Race the helpers |
| `hub_you_and_1_to_3_friendly_helpers_race_to_find_the_d17514` | You and 1 to 3 friendly helpers race to find the picture on both cards. |
| `hub_match_race` | Match Race |
| `hub_everyone_shares_the_screen_tap_the_picture_that_a9a580` | Everyone shares the screen. Tap the picture that is on your card and the middle card. |
| `hub_group_hunt` | Group Hunt |
| `hub_search_together_and_find_every_picture_on_the_li_1cb762` | Search together and find every picture on the list before the sun sets. |
| `hub_solo_dash` | Solo Dash |
| `hub_match_as_many_cards_as_you_can_before_the_clock_20082a` | Match as many cards as you can before the clock runs out. |
| `hub_helpers` | Helpers |
| `hub_friendly_helpers_play_too_so_a_small_group_alway_123cb8` | Friendly helpers play too, so a small group always has a race. |
| `hub_find_it_frenzy` | Find It Frenzy |
| `hub_spot_the_matching_picture_before_anyone_else` | Spot the matching picture before anyone else. |
| `hub_helper_level` | Helper level |

---

## 3. SCALE

### 3.1 Length by mode (target 3 to 10 minutes)
| Mode | Setting | Rounds or scenes | Typical length |
|---|---|---|---|
| Match Race | Quick / Normal / Long | 15 / 24 / 36 rounds | 2.5 / 4 / 6 min with 9.5 s mean round (kids, Little cards); 1.5 to 3.5 min with adults and Hard helpers (mean round 5 s) |
| Group Hunt | Quick / Normal / Long | 2 / 3 / 4 scenes | 2.5 / 4 / 5.5 min (about 75 s per scene) |
| Solo Dash | Tiny, Little 90 s; Big 60 s | one clock | 1.5 min plus results; Relaxed 12 cards about 2 min |
The hub text promises "About 3, 4 or 6 minutes". Playtest target: 90 percent of games inside 2 to 8 minutes.

### 3.2 Player counts
1 to 4 seats. Match Race needs at least 2 seats (helpers fill); Group Hunt and Dash run with 1. Layout: portrait phone and tablet: seats at the four sides of the table, opposite seats' cards rotated 180 degrees; a Fold inner screen and a tablet use the same layout. If the minimum card diameter does not fit, the card size drops one step with a quiet notice.

### 3.3 Deterministic seeds and replay
- The engine takes `FindItConfig.seed` (the wrapper draws one at start and stores it in the snapshot). All shuffles, layouts, scenes, helper schedules and hint choices come from `FindItRng` (SplitMix64) streams derived from that seed: deck order stream, layout stream per card index, scene stream per scene index, helper stream per (round, seat). `Random.Default` and the wall clock are banned from the engine (a source scan test).
- Replay = `FindItReplay` (seed, config ids, seats, every tap as `(atMs, seat, symbol)`). Feeding it to a fresh engine with the same `advance` steps reproduces scores and cards exactly (test FI-R1).
- Hint and clue timings are pure functions of engine time, so replays include them for free.

### 3.4 Performance budget
- Engine: all state lives in small arrays; `advance` and `tap` allocate nothing after warm-up except the immutable `FindItState` snapshot built once per frame by the wrapper (one object graph of at most ~40 small objects; reuse lists via `List` views is a later optimisation).
- Deck generation (order 7, 57 cards) and a 22-item scene: under 1 ms on a mid phone, done once per game or scene, never on the draw path (test FI-P2: under 5 ms).
- Drawing: a card is 6 to 8 vector icons; a scene is at most 22 icons plus backdrop. Pre-build each icon as a cached `Path` (57 paths, created lazily). Draw budget: under 4 ms per frame on a mid phone at 60 Hz; at 120 Hz use the same scene (no extra work per frame). No per-frame allocation in draw blocks (audit 02, finding 3.7): pre-allocated `Paint` and transform reuse.
- No perpetual particle loop when idle (audit 02, finding 3.4): the only animations are the bob of the middle card (a single `animateFloat`) and the 1 Hz hint swell; both stop when Reduced Motion is on or the game is paused.
- Memory: the 57 icon paths, 3 backdrops and one 4-seat layout: under 2 MB.

### 3.5 Resume and persistence
`ResumableGame` via `FindItSave` (JSON, `FindItSaveCodec`): config ids, seed, RNG call count, deck order, pile index, round or scene index, scores, misses, each seat's own card, the middle card, elapsed ms, seat ids. `snapshot()` returns null before start, after OVER and in any non-local mode. A mid-round restore re-deals the same cards and restarts the round clock (the lost seconds favour nobody). `restore()` validates before touching state (rules in the `FindItSave` KDoc: permutation check, ranges, seat id match); any failure returns false and the shell starts a fresh game. The Dash personal best is the shell's job through `PlayerScore.score`.

### 3.6 Multiplayer plan
- **Now: same screen only.** No Nearby or Online listing in the Hub spec (`networkedGames` untouched).
- **Later (Nearby): host-authoritative.** The host owns the seed, deck and clock. Messages: `RoundStart(round, cardIdsPerSeat, centreCard)`, `TapClaim(symbol, localElapsedMs)`, `RoundResult(winnerSeat, atMs)`, `GameOver(scores)`. Guests send claims only; the host orders them by claim time minus half the measured round-trip time of that guest; claims within 80 ms of each other are a tie resolved by the rotating seat rule (R5). Identity comes from the transport's `fromPlayerId`, never from the payload (the lesson of the 2026-09-28 identity hardening, PR #9). A guest that goes silent for 5 s is replaced by a helper at Easy level. Hint timers run on the host.
- Reflex fairness risk is the reason Nearby is "later"; Group Hunt (cooperative, no reflex race) is the first candidate for it.

### 3.7 Phased delivery (S small under a day, M one to three days, L a week or more)
| Milestone | Content | Effort |
|---|---|---|
| M0 Engine core | `ProjectiveDeck.generate`, shuffle, layout, scene builder, `FindItEngine` for Match Race and Dash, helpers, save codec; un-ignore FI-D*, FI-E1 to E7, FI-E11 to E13, FI-B*, FI-R* | M |
| M1 Screen and art (31 pictures) | Same-screen UI for 2 to 4 seats, palm rejection, five states, 31 icons, sounds, haptics, Reduced Motion | L |
| M2 Hub and shell | Register spec, glyph, accent, route, tips, coach, ES strings review, tutorial | S |
| M3 Polish and device pass | Fold inner and cover, tablet, 4-seat reach test, helper tuning with real children | M |
| M4 Group Hunt | Scene generator, hunt UI, 3 backdrops, helper | M |
| M5 Big deck and one-switch | 26 more icons, row-column and card scan, TalkBack announcements | M |
| M6 Parent line and stats | Dash personal best, dashboard line | S |
| M7 Nearby (later) | Host-authoritative mode | M |

### 3.8 Risk register
| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| K1 | Look-alike claim against a published matching game | Low | High | Mechanic only; original names, 57 self-drawn pictures chosen from scratch, own layout and wording, legal sign-off item before ship |
| K2 | Picture art takes longer than the code (57 icons) | High | Medium | Ship the 31-picture Little deck first; icons are primitive recipes; artist iterates in one `FindItArt.kt` |
| K3 | Four children's palms and sleeves cause false taps | Medium | High | `ContactSizes` palm rejection, gap taps ignored, 56 dp cells, real-hand test at M3 |
| K4 | Easy helpers still too fast or too slow for 4 year olds | Medium | Medium | Numbers in one table; mean 7 s; playtest with 4 to 6 year olds; Game Speed scales helpers |
| K5 | Lock-out feels punishing and ends in tears | Medium | Medium | Off on Tiny and in Relaxed; cloud is gentle; 1.5 s; no points lost |
| K6 | Same-screen reach: seats on the far side cannot reach the middle | Low | Low | Players tap only their own card; the middle card is read-only |
| K7 | Timestamps from the UI thread are coarse for tie resolution | Low | Low | Engine time comes from the frame clock; rotation rule decides exact ties |
| K8 | Screen aspect ratios differ, so a scene could look different per device | Medium | Medium | Virtual 3:4 canvas, letterboxed; one seed gives one scene on every device |
| K9 | Snapshot of a game from an older app version fails to restore | Low | Low | Version field, strict validation, fall back to a fresh game |
| K10 | Spanish text length breaks layout | Low | Low | No text needed to play; labels wrap; 1.6x text check at M3 |

### 3.9 Test plan (each row is an `@Ignore`d stub in `app/src/test/java/com/gamesuite/games/findit/`; function name = lower-case ID + "_" + test name)
**`FindItDeckPropertyTest`**

| ID | Test | What it proves |
|---|---|---|
| FI-D1 | `every_pair_of_cards_shares_exactly_one_symbol_for_orders_3_5_7` | exhaustive over all pairs of the 13, 31 and 57 card decks |
| FI-D2 | `every_card_has_order_plus_one_distinct_symbols` | 4, 6 and 8 distinct symbol indexes per card, all in range |
| FI-D3 | `every_symbol_appears_on_exactly_order_plus_one_cards` | dual property: each symbol on 4, 6 or 8 cards |
| FI-D4 | `card_count_and_symbol_count_equal_order_squared_plus_order_plus_one` | 13, 31, 57 |
| FI-D5 | `unsupported_or_non_prime_order_returns_empty_deck` | orders 0, 1, 2, 4, 6, 8, 9 and negatives are rejected without throwing |
| FI-D6 | `any_subset_of_the_deck_keeps_the_one_shared_symbol_property` | random subsets of 5 to N cards, 200 seeds |
| FI-D7 | `same_seed_gives_identical_shuffle_and_different_seed_differs` | SplitMix64 Fisher-Yates determinism, golden first 8 card ids for seed 42 |
| FI-D8 | `card_layout_is_deterministic_has_no_overlap_and_meets_minimum_cell` | slots per card, cell diameter at least 56dp at the minimum card size, same seed same layout |
| FI-D9 | `symbol_catalog_has_57_unique_ids_with_names_and_nested_prefixes` | ids unique, 13 and 31 prefixes, each has EN and ES string resources |
| FI-D10 | `next_card_never_equals_current_and_shares_exactly_one_symbol_over_1000_seeds` | property test for the reshuffle edge case in Solo Dash |

**`FindItEngineTest`**

| ID | Test | What it proves |
|---|---|---|
| FI-E1 | `match_race_first_correct_tap_wins_centre_card_and_scores_one` | winner takes the centre card as its own card, draws a new centre |
| FI-E2 | `wrong_tap_locks_seat_for_1500ms_and_relaxed_does_not_lock` | locked seat taps are ignored until the clock passes the lock |
| FI-E3 | `simultaneous_taps_resolve_by_timestamp_then_rotated_seat_order` | same atMs resolves by (seat + round) mod seats |
| FI-E4 | `reshuffle_never_deals_a_centre_card_equal_to_any_held_card` | tiny deck, 4 seats, 36 rounds: the pile is reshuffled from the discards |
| FI-E5 | `stall_help_pulses_the_answer_after_stall_time_and_tap_still_counts` | 20 s (30 s little) pulse on every card |
| FI-E6 | `hint_levels_narrow_to_three_then_pulse_for_handicap_seat_only` | 5 s narrow, 9 s pulse; other seats unaffected |
| FI-E7 | `match_over_ranks_winner_ties_shared_and_badges_awarded` | fastest, sharp eyes, collector badges, joint winners |
| FI-E8 | `group_hunt_scene_has_each_target_exactly_once_and_min_separation` | targets appear once, decoys at most twice, no overlaps |
| FI-E9 | `group_hunt_all_targets_found_clears_scene_and_all_scenes_win` | we did it result after the last scene |
| FI-E10 | `group_hunt_sunset_timeout_offers_retry_of_the_same_scene` | try again together, same seed gives same scene |
| FI-E11 | `solo_dash_counts_cards_applies_two_second_penalty_and_ends_at_zero` | relaxed has no clock and no penalty |
| FI-E12 | `pause_stops_the_clock_and_resume_continues` | advance while paused changes nothing |
| FI-E13 | `taps_before_go_or_after_over_are_ignored` | NOT_PLAYING result and no state change |
| FI-E14 | `helper_never_takes_final_hunt_target_when_a_human_is_present` | the last find always goes to a person |
| FI-E15 | `tap_with_out_of_range_seat_or_symbol_is_rejected` | negative, too large, and symbol not on the seat card |

**`FindItBotTest`**

| ID | Test | What it proves |
|---|---|---|
| FI-B1 | `bot_reaction_means_match_level_table_within_tolerance_over_1000_rounds` | easy 7000, medium 4000, hard 2200 ms mean times 0.7 tiny, 1.0 little, 1.3 standard |
| FI-B2 | `bot_error_rate_matches_level_table` | easy 15 percent, medium 7 percent, hard 2 percent |
| FI-B3 | `bot_schedule_is_deterministic_for_seed_round_and_seat` | same inputs give the same tap time and the same wrong or right choice |
| FI-B4 | `helper_hunt_find_interval_matches_level_table` | 12 s, 8 s, 5 s |

**`FindItReplaySaveTest`**

| ID | Test | What it proves |
|---|---|---|
| FI-R1 | `replay_of_seed_config_and_tap_log_reproduces_final_scores_exactly` | scripted 3 seat race, 14 rounds |
| FI-R2 | `snapshot_restore_round_trips_state_between_rounds` | scores, hands, pile index, rng position |
| FI-R3 | `snapshot_mid_round_restores_same_cards_and_restarts_round_clock` | the round is replayed fresh with the same cards |
| FI-R4 | `corrupt_or_foreign_snapshot_is_rejected` | garbage, empty, other game json, wrong version |
| FI-R5 | `snapshot_with_out_of_range_indices_or_scores_is_rejected` | card index beyond deck, negative score, seat count mismatch |
| FI-R6 | `snapshot_is_null_before_start_after_over_and_for_nearby_modes` | ResumableGame contract |

**`FindItAccessibilityTest`**

| ID | Test | What it proves |
|---|---|---|
| FI-A1 | `one_switch_scan_order_visits_every_symbol_once_and_wraps` | scan order is stable per card |
| FI-A2 | `hunt_row_column_scan_reaches_every_scene_cell` | row then column then nearest item |
| FI-A3 | `relaxed_variant_removes_lockout_clock_and_penalty` | relaxed flag effects in one assertion table |

**`FindItGameTest`**

| ID | Test | What it proves |
|---|---|---|
| FI-G1 | `game_lifecycle_init_start_tap_end_reports_scores` | GameModule wrapper end to end with a fake context |
| FI-G2 | `leave_session_in_hunt_reports_all_players_as_winners_with_the_team` | house convention for co-op results |
| FI-G3 | `tap_after_match_over_returns_not_playing` | no crash, no state change |
| FI-G4 | `game_exposes_id_find_it_players_1_to_4_and_local_modes_only` | metadata |

**`FindItHubSpecsTest`**

| ID | Test | What it proves |
|---|---|---|
| FI-H1 | `hub_spec_is_kids_min_age_4_and_not_networked` | audience, minAge, no NEARBY or ONLINE mode |
| FI-H2 | `every_hub_text_has_en_and_es_resource_under_hub_key` | same rule as HubLocalizationTest, applied to our spec |
| FI-H3 | `option_defaults_resolve_and_handicap_choices_cover_four_seats` | none, all, p1..p4 |
| FI-H4 | `strings_have_es_parity_and_no_todo_translate_in_values` | values and values-es key sets are equal |
| FI-H5 | `launch_options_reach_extras_for_card_size_length_handicap_relaxed_and_switch` | buildHubLaunch round trip |

**`FindItPerformanceTest`**

| ID | Test | What it proves |
|---|---|---|
| FI-P1 | `engine_advance_allocates_nothing_after_warmup` | allocation counter, 10000 advance calls |
| FI-P2 | `deck_generation_and_scene_generation_finish_under_5ms` | order 7 deck plus 40 item scene, off the main thread |


Property and fuzz notes: FI-D1 is exhaustive (all pairs for 3 orders); FI-D6 and FI-D10 are seeded property tests (200 and 1,000 seeds); FI-R1 plays 200 seeded random 3-seat games with random tap logs and compares replay output to the live run; fuzz: random taps never throw and never change state in non-PLAYING phases.

---

## 4. SCAFFOLD

### 4.1 What was created (compiling skeletons only; nothing compiled by the author)
Main package `app/src/main/java/com/gamesuite/games/findit/`:
| File | Contents |
|---|---|
| `FindItModels.kt` | Enums (`FindItMode`, `FindItPhase`, `FindItCardSize`, `FindItLength`, `FindItHint`, `FindItTapResult`, `FindItColour`), data classes (`FindItSymbol`, `FindItSeat`, `FindItConfig`, `FindItCardSlot`, `FindItSceneItem`, `FindItScene`, `FindItSeatState`, `FindItState`), `FindItRules` constants, serialisable `FindItTapEvent`, `FindItReplay`, `FindItSave` (validation TODOs in KDoc) |
| `FindItSymbols.kt` | The 57 pictures as data (matches 2.2) |
| `FindItDeck.kt` | `ProjectiveDeck` with KDoc of the algorithm; `isSupportedOrder` and `cardCount` real, generators are stubs returning empty values |
| `FindItRng.kt` | SplitMix64 (real, 6 lines, deterministic) |
| `FindItEngine.kt` | `FindItEngine` interface and `StubFindItEngine` returning safe values |
| `FindItGame.kt` | `FindItGame` (`GameModule`, `ResumableGame`, id `find-it`, 1 to 4 players, local modes) and `FindItSaveCodec` |
| `FindItHubSpecs.kt` | `FindItHubSpecs.spec`: `audience = GameAudience.KIDS`, `minAge = 4`, 4 modes, NOT registered anywhere |
Strings: `res/values/find_it_strings.xml` and `res/values-es/find_it_strings.xml` (UI, 57 picture names, new hub texts; real Spanish, review comment only).
Tests: 8 classes in `app/src/test/java/com/gamesuite/games/findit/`, every test `@Ignore`d with the ID and a reason.

### 4.2 Snapshot DTO (`FindItSave`)
Fields: `version, mode, cardSize, length, relaxed, handicap, huntHint, oneSwitch, difficulty, seed, rngCalls, deckOrder, pileIndex, round, sceneIndex, scores, misses, ownCards, centreCard, elapsedMs, playerIds`. Validation rules are in the class KDoc (`TODO(find-it)` in `FindItSaveCodec.validate`, currently only version and seat count).

### 4.3 Integration checklist for the coordinator (NOT done here; those files belong to others)
1. `core/GameHubSpecs.kt`: add `FindItHubSpecs.spec` to `baseSpecs()` (ARCADE_PUZZLE section). Do NOT add `find-it` to `networkedGames`.
2. `core/GameCatalog.kt`: glyph `"find-it" -> "👀"` (`FindItHubSpecs.GLYPH`).
3. `ui/GameTheme.kt`: an accent pair for `find-it` (pick with the contrast checks of `GameThemeTest`; the palette is crowded).
4. `MainActivity.kt`: route `find-it` with `rememberActiveModule(sessionManager, "find-it") { FindItGame() }` and a screen (not written in this round).
5. `ui/GameTips.kt` and `core/GameCoach.kt`: three tips and a coach card (the tutorial lines in 2.10 can be reused); decide `ownCoachInGame` (recommended: the screen reuses the Club coach card, like Slide and Climb).
6. `core/GameAudienceTable.kt`: no row needed (the spec tags win).
7. Gate: `./gradlew :app:testDebugUnitTest --tests "com.gamesuite.games.findit.*"`, then the whole suite (`HubLocalizationTest`, `GameTipsTest`, `GameCoachTest`, `GameThemeTest`, `GameAudienceTest` iterate over every spec; the new spec must be registered before they see it).
8. `find_it_strings.xml` defines the hub texts; `hub_*` names reused from other files are listed in the KDoc of `FindItHubSpecs` and in 2.12.

### 4.4 Statement
Nothing in this package was built, compiled, linted or run. No Gradle task, emulator or device was used. The Kotlin was written to the house conventions and read back, but it has never seen a compiler.
