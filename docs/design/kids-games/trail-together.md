# Trail Together (`trail-together`)

Status 2026-11-07, branch `session/wave-3`. Scope, script, scale and scaffold package. **Nothing in this package has been built, compiled or run by Gradle.** The only thing executed is a throwaway Python balance simulation (section 3.10) that lives outside the repo.

Legal rule (hard): everything below is original (names, art, card text, layouts, wording). Only the mechanics of the genre are reused: draw or hold cards, extend or move along a path, collectively beat a clock-like opponent. No name, rulebook wording or layout of any publisher's product is used.

---

## 1. SCOPE

### 1.1 Purpose
A short cooperative path game for the youngest players. A small team of hedgehog pals must hop home along a trail of coloured stones to their burrow before the Fog rolls in. Players hold cards that either **hop** a pal along the trail or **light** a new stone at the end of the trail. Everybody wins or tries again together. It is the first cooperative "planning ahead" game in the Kids shelf and the mechanical sibling of `bridge-builders` (same cooperative wording, same same-screen conventions) but turn-based and calm, with no reflex pressure.

### 1.2 Audience and adult + child fairness
- Ages 4+ (`minAge = 4`), 1 to 4 players, plus optional helper pals (computer seats). A 4 to 6 year old can play it unaided; a grown-up can play at their side without the game being trivial.
- Fairness levers (all visible in the setup screen, same wording everywhere):
  1. **Extra card for** (handicap row): one chosen seat holds 4 cards instead of 3 (more choice). Use it for the youngest child or for the player who should feel most helpful.
  2. **Show helper glow** (hint toggle): a soft glow on a best-value card, default ON for the Little band and OFF for Big. A grown-up can turn it off for themselves by leaving it off and letting the child use the "Help me choose" button.
  3. **Open hands**: all hands are face up on the table, so talking is the game. The tutorial and the first-run tip tell grown-ups to ask "What could we do?" before suggesting a card; the final choice always belongs to the card's owner.
  4. **Difficulty band** (Little / Medium / Big) is chosen for the whole team, never per person.
- No score is compared between people. There are no individual wins or losses.

### 1.3 MVP vs v1 vs later
| Tier | Content |
|---|---|
| MVP (phase 0-3) | Same-screen play for 1-4 people, helper pals as computer seats, bands Little/Medium/Big, three card kinds + Fog, handicap row, hint glow, first-run tutorial, resume, score card, `We did it!` / `Try again together`, EN + ES strings, TalkBack labels, Reduced Motion alternative |
| v1 | Trail number (replayable seed code shown on the score card), 2 extra trail themes (art only, same rules), per-profile handicap list through `extras["handicap"]`, sound + haptic pack, parent-dashboard line, weekly "Trail of the day" fixed seed |
| Later | Nearby multiplayer (hidden hands, each child on own device), "Two ways home" forked trail variant, story mode with 5 linked trails, custom-trail builder for parents |

### 1.4 Out of scope
Online play, accounts, chat, any timer a child can fail on, reading of any text during play, competitive modes, in-app purchases, ads, third-party SDKs, user-generated names beyond the existing profile player names.

### 1.5 Safety and age notes
- No fail state a child sees as personal: loss text is `Try again together`, the Fog is a gentle grey cloud (no scary face, no jump scare, no loud sound).
- No data leaves the device. Only the existing profile name is shown. No new permissions.
- Session length is naturally 8-12 minutes; the existing break reminder (profiles foundation) is not touched by this game.
- Kid profile visibility goes through `GameAudience.KIDS` + `minAge = 4` on the spec; not listed in `KID_BLOCKED_GAME_IDS`.
- Flashing: no element flashes more than 2 times per second; the Fog advance is a slow 600 ms slide, replaced by an instant move under Reduced Motion.

### 1.6 Accessibility
- Colour never carries meaning alone: every stone colour has a fixed shape (circle, triangle, square, diamond, star, hexagon) drawn on the stone, the card and the hop button.
- Touch targets 56dp minimum (cards 72x96dp, pals 64dp, spots 56dp, Swap and Help buttons 56dp high).
- TalkBack: every card, pal, spot and the fog track has a `contentDescription` (strings in section 2.11); double-tap equals tap; actions are announced as polite live-region text ("Nia's pal hops to the berry stone").
- Reduced Motion: hops are instant with a 150 ms highlight, no confetti, fog slides instantly.
- Text size: all game text scales with the system font scale up to 200 percent without clipping (cards use icon + short label; the label may wrap, the icon never shrinks).
- No audio-only information: every sound has a matching visual and haptic cue; the game is fully playable on mute.
- One-handed and switch access: no drag is required (tap card, tap pal); drag-to-pal is an optional shortcut.
- Kids theme: warm cream background, Baloo type for the few labels, shape + colour tokens as above.

### 1.7 What it teaches (one line for the parent dashboard)
EN: "Planning ahead, matching colours and shapes, and taking turns as a team."
ES: "Planificar, emparejar colores y formas, y turnarse en equipo."
(String key `trail_together_teaches`.)

### 1.8 Dependencies
- Core: `GameModule`, `ResumableGame`, `GameResult`/`PlayerScore`, `GameAudience.KIDS`, `GameHubSpec.minAge`, `HubOptions`, `CpuDifficulty`, `abortMatch()` (all exist on this branch).
- Shared files to be edited later by the coordinator, NOT by this package: `GameHubSpecs`, `GameCatalog` (glyph), `GameTheme` (accent), `MainActivity` (route), `GameTips`, `GameCoach` (section 4.3).
- A new screen `ui/TrailTogetherScreen.kt` (phase 1; not part of this scaffold, outside the allowed file set).
- Optional: profiles foundation (`Profile.kind`, per-player handicap) for the v1 per-profile handicap list.
- Shared UI helpers reused by the screen: card and pawn composables should be built fresh in the screen (no `TapTug*` dependency; this game is not multi-touch).

### 1.9 Open questions (each with a recommended default)
| # | Question | Recommended default |
|---|---|---|
| O1 | Pals = players (2 to 4) or fixed? | Pals = max(2, seats) up to 4; solo uses 2 pals and one 3-card hand (simulated: 0.89 / 0.83 / 0.72 win) |
| O2 | Hidden hands for same-screen? | No. Open hands face up; hidden hands come with Nearby later |
| O3 | Should a "lost" try end the session? | No. Show `Try again together`, offer Play again with a new seed; session tally keeps going |
| O4 | Is the helper glow a spoiler for adults? | Default ON in Little only; off in Medium and Big; "Help me choose" button always available |
| O5 | Little 2-player length is about 8.5 minutes at 18 s per play; lengthen? | Keep; revisit after real playtests (raising Little trail to 18 would cut the win rate to about 0.85) |
| O6 | Family (FAMILY) vs KIDS audience tag? | KIDS, minAge 4 |
| O7 | HubFamily for the card? | `HubFamily.BOARD` (path game); no new enum value |
| O8 | Theme name check | Run a trademark and app-store name search for "Trail Together", "Fog Bank" and the pal names before art is commissioned; fall back names in 2.1 |
| O9 | Game id `trail-together` in `GameAudienceTable`? | No; the spec carries `audience` and `minAge` itself (house rule for new games) |

---

## 2. SCRIPT

### 2.1 Story and cast (original)
- The pals: **Nutmeg**, **Pip**, **Clover** and **Burr**, four young hedgehogs with a lantern each. Pal colours are pure identity (seat badge), not trail colours: Nutmeg = orange square badge, Pip = blue circle, Clover = green triangle, Burr = purple star.
- The goal: the **Burrow**, a round door with a lit window at the end of the trail.
- The opponent: the **Fog**, a soft grey cloud that creeps along a fog track above the trail. Fallback names if O8 fails: pals Tansy/Moss/Bramble/Hazel, opponent "the Mist".
- Tone: calm, cosy, no villains.

### 2.2 Components
- **Trail:** `length + 1` spots, index 0 = Start, index `length` = Burrow. Spots `0 .. frontier-1` are lit (built, with a colour); the rest are dim outlines. The Burrow is "open" once the spot `length - 1` is lit.
- **Stone colours** (colour + shape, fixed pairing):

| id | Name (EN) | Shape | Token colour (light theme) | Pattern fill (for extra redundancy) |
|---|---|---|---|---|
| berry | Berry | circle | 0xFFC8324B | dots |
| sun | Sun | triangle | 0xFFE9A800 | rays |
| leaf | Leaf | square | 0xFF3E8E41 | stripes |
| sky | Sky | diamond | 0xFF2D7DD2 | waves |
| plum | Plum | star | 0xFF7B3FA0 | crosses |
| peach | Peach | hexagon | 0xFFE9772E | checks |

  Little uses the first 4, Medium the first 5, Big all 6.
- **Cards** (hand size 3, or 4 for the handicap seat):
  - **Hop card (colour c)**: move one chosen pal forward to the nearest lit spot **of colour c** ahead of it. The open Burrow counts as every colour.
  - **Stone card (colour c)**: light the next dim spot (the frontier) with a stone of colour c.
  - **Lantern card**: choose ONE: light the frontier spot with a stone of ANY colour, or hop one chosen pal exactly one spot forward onto a lit spot (or onto the open Burrow).
  - **Fog card**: never held. It lives in the draw pile; when drawn it is revealed, the Fog advances one step and a replacement card is drawn.
- **Fog track:** `fogCards` steps (see 2.5). Fog reaching the last step ends the try.
- **Draw pile / discard pile.** No reshuffle (invariant: the pile cannot run out before the last Fog card is drawn, which ends the try).

### 2.3 Rules as a numbered script
1. Choose a band, the helper pals and optional handicap, then Start. Seats: 1 to 4 (people plus helper pals). Pals on the trail: `max(2, seats)`, at most 4. Pal i belongs (for colour/badge only) to seat `i mod seats`; ANY seat may move ANY pal.
2. Build the trail: lay the Start and the first `builtAtStart - 1` spots, each colour drawn from the band's colours with the rule "never the same colour as the spot before it". Put all pals on Start (index 0).
3. Build the deck from the table in 2.5 (stones, hops, lanterns), shuffle with the game seed, and deal each seat 3 cards (4 for the handicap seat) from it. Fog cards are NOT in the deal.
4. Shuffle the Fog cards into the remaining draw pile with the same seed stream. Fog marker = 0.
5. The first seat is seat 0 (the Hub's first-mover rule applies if `firstMoverIndex` is provided; otherwise seat 0).
6. On your turn you do exactly ONE of these:
   a. **Play a hop card**: pick the card, then tap a pal that is not home. The pal hops to the nearest lit spot of that colour ahead of it (pals may share a spot). If no such spot exists for that pal, that pal is not a legal target (it is shown dimmed).
   b. **Play a stone card**: pick the card; the frontier spot lights up in the card's colour. (Tapping the glowing spot is an optional shortcut; picking the card is enough.) Not legal once the Burrow is open.
   c. **Play a lantern card**: pick the card, then pick "Light a stone" and one colour, or pick "Hop one" and a pal.
   d. **Swap**: discard one card of your choice face up. (Always legal; use it when nothing helps.)
7. If after your play every pal is on the Burrow, the team wins immediately (no draw).
8. Otherwise draw cards until you again hold your hand size of non-Fog cards. Every Fog card drawn is revealed first: the Fog moves one step, the card goes to the discard, and you draw again. If the Fog reaches its last step, the try is lost immediately (even mid-draw).
9. Pass the turn to the next seat (clockwise). Helper pals take their turn automatically after a 1.2 s pause (2.7).
10. **We did it!** = all pals on the Burrow. **Try again together** = Fog at its last step. Both show the score card (2.12); Play again starts a new seed and keeps the session tally.
11. At any moment a person can press "Help me choose": the game glows the best legal play according to the hint rule (2.8). Using it has no cost and is not recorded.
12. Leaving before the first try ends is an abort (no stats). Leaving after at least one finished try ends the session; the result marks every person as a winner only if at least one try was won (house pattern from Bridge Builders).

### 2.4 State machine
| State | Entered when | Allowed inputs | Next |
|---|---|---|---|
| `DEAL` | start of a try | none (animation) | `PICK_CARD` of seat 0 |
| `PICK_CARD` | a seat's turn begins | pick hand card (hop, stone, lantern), Swap, Help me choose | `PICK_TARGET` (hop, lantern) or `RESOLVE` (stone, swap) |
| `PICK_TARGET` | hop or lantern chosen | tap legal pal, choose lantern mode/colour, cancel | `RESOLVE` or back to `PICK_CARD` |
| `RESOLVE` | a legal play chosen | none | `WON` if all pals home else `DRAW` |
| `DRAW` | after RESOLVE | none (fog reveals animate) | `LOST` if fog full else `PICK_CARD` of next seat |
| `WON` | all pals home | score card actions | new try (`DEAL`) or exit |
| `LOST` | fog full | score card actions | new try (`DEAL`) or exit |
Helper seats auto-run `PICK_CARD -> (PICK_TARGET) -> RESOLVE -> DRAW`.
Screen-level wrapper states (see 2.12): SETUP (Hub) -> START (ready + tutorial) -> MID_GAME (the machine above) -> POST_GAME (`WON`/`LOST` banner) -> SCORE_CARD.
`pause()` freezes helper autoplay and the fog animation; `resume()` continues.

### 2.5 Content tables as data

**Band parameters**
| Band | Colours | Trail length (hops spots after Start) | Lit at start (incl. Start) | Stones to light | Columns (serpentine layout) | Assumed seconds per play |
|---|---|---|---|---|---|---|
| little | 4 | 16 | 4 | 12 | 5 | 18 |
| medium | 5 | 20 | 4 | 16 | 5 | 14 |
| big | 6 | 24 | 3 | 21 | 5 | 14 |

(Spot count = length + 1: 17 / 21 / 25 spots, so 4 / 5 / 5 rows of 5 on the serpentine layout.)

**Deck composition (cards per colour; lanterns and fog are totals)**
Stones per colour is 4 in every band. Totals shown for the non-Fog cards.
| Band | Pals (= seats, min 2) | Stone cards (per colour) | Hop cards (per colour) | Lanterns | Fog cards (= fog track length) | Total cards in deck |
|---|---|---|---|---|---|---|
| little | 2 | 4 (16) | 5 (20) | 2 | 10 | 48 |
| little | 3 | 4 (16) | 7 (28) | 3 | 12 | 59 |
| little | 4 | 4 (16) | 10 (40) | 4 | 12 | 72 |
| medium | 2 | 4 (20) | 4 (20) | 2 | 10 | 52 |
| medium | 3 | 4 (20) | 6 (30) | 3 | 10 | 63 |
| medium | 4 | 4 (20) | 8 (40) | 4 | 10 | 74 |
| big | 2 | 4 (24) | 4 (24) | 2 | 10 | 60 |
| big | 3 | 4 (24) | 6 (36) | 3 | 10 | 73 |
| big | 4 | 4 (24) | 8 (48) | 4 | 10 | 86 |
Solo (1 seat) uses the Pals = 2 row with a single hand of 3.

**Machine-readable spec (`trail-spec` v1; the engine constants must equal this, test T01-T03)**
```json
{
  "version": 1,
  "handSize": 3, "handicapHandSize": 4, "minSeats": 1, "maxSeats": 4, "minPals": 2, "maxPals": 4,
  "colours": [
    {"id":"berry","shape":"circle"}, {"id":"sun","shape":"triangle"}, {"id":"leaf","shape":"square"},
    {"id":"sky","shape":"diamond"}, {"id":"plum","shape":"star"}, {"id":"peach","shape":"hexagon"}
  ],
  "indexing": {"start": 0, "burrow": "length", "frontierStart": "builtAtStart"},
  "rules": {"adjacentSameColourAtStart": false, "burrowMatchesEveryColour": true, "burrowOpensWhenLitThrough": "length-1",
            "palsMayShareSpot": true, "reshuffle": false, "swapAlwaysLegal": true},
  "bands": {
    "little": {"colours": 4, "length": 16, "builtAtStart": 4, "columns": 5, "stonesPerColour": 4,
               "byPals": {"2": {"hopsPerColour": 5,  "lanterns": 2, "fog": 10},
                          "3": {"hopsPerColour": 7,  "lanterns": 3, "fog": 12},
                          "4": {"hopsPerColour": 10, "lanterns": 4, "fog": 12}}},
    "medium": {"colours": 5, "length": 20, "builtAtStart": 4, "columns": 5, "stonesPerColour": 4,
               "byPals": {"2": {"hopsPerColour": 4, "lanterns": 2, "fog": 10},
                          "3": {"hopsPerColour": 6, "lanterns": 3, "fog": 10},
                          "4": {"hopsPerColour": 8, "lanterns": 4, "fog": 10}}},
    "big": {"colours": 6, "length": 24, "builtAtStart": 3, "columns": 5, "stonesPerColour": 4,
            "byPals": {"2": {"hopsPerColour": 4, "lanterns": 2, "fog": 10},
                       "3": {"hopsPerColour": 6, "lanterns": 3, "fog": 10},
                       "4": {"hopsPerColour": 8, "lanterns": 4, "fog": 10}}}
  }
}
```
Serpentine layout rule for the screen: spot i is in row `i / columns`; even rows run left to right, odd rows right to left; Start at row 0 column 0; Burrow at the last index.

### 2.6 Win / lose conditions and difficulty bands
- Win: every pal's position equals `length` (Burrow).
- Lose: fog marker equals the band's `fog` count for the current pal count.
- Difficulty bands change only the trail, the colour count and the Fog/hop ratios (table above). They never change the rules.

| Band | Aimed at | Win rate of the helper policy "smart" (3000 seeds, section 3.10) | Win rate of the "random" policy | Expected plays | Expected minutes (incl. 1 min intro and celebration) |
|---|---|---|---|---|---|
| Little | ages 4-5 | 0.91 (2 pals), 0.91 (3), 0.92 (4) | 0.76 / 0.63 / 0.61 | 25 / 30 / 37 | 8.5 / 10 / 12 |
| Medium | ages 5-7 | 0.81 / 0.81 / 0.81 | 0.65 / 0.52 / 0.41 | 30 / 36 / 43 | 8 / 9.5 / 11 |
| Big | ages 6+, grown-ups | 0.70 / 0.66 / 0.69 | 0.56 / 0.42 / 0.35 | 39 / 47 / 55 | 10 / 12 / 14 |

Acceptance for the real engine (test T22): over 2000 seeds each, the smart policy's win rate must fall within +/- 0.06 of the table value for every band and pal count.

### 2.7 Helper pal (computer seat) script
Helper pals are the existing computer seats (`bot1`..) with display tag "(Helper)". Level = `CpuDifficulty`:
- EASY: `random` policy (uniform among legal (card, target) pairs); 15 percent of the time Swap even when a play exists.
- MEDIUM: `smart` policy with 25 percent random choice.
- HARD: `smart` policy.

`smart` policy, evaluated on its own hand, in this order (first rule that yields a play wins; ties by lowest hand index):
1. Score every legal hop (card, pal): `score = 1.0 + 1.0 (if jump >= 3) + 0.3 * jump - 0.05 * palPosition` where jump = new index - old index. Rear pals score slightly higher.
2. Score every legal stone play (stone card, or lantern as stone): `score = (1.5 if frontier - maxPalPosition <= 4 else 0.5) + (0.2 if it is a stone card else -0.5)`.
3. Score every legal lantern hop (card, pal): same as rule 1 with its jump (always 1) minus 0.5.
4. Highest score wins. If no legal play exists: Swap, discarding the card whose colour appears least among lit spots ahead of every pal, else the lowest hand index.
Timing: 1.2 s thinking pause (0.6 s under Reduced Motion), then the play is animated like a person's play. The helper never blocks: if the app is paused it waits.

### 2.8 Hint rule ("Help me choose" and the optional glow)
Run the `smart` policy for the current seat and glow the chosen card; after a tap on "Help me choose" also pulse the chosen pal. The hint never suggests an illegal play (test T21). Never auto-plays.

### 2.9 Handicap rule
- Setup row "Extra card for": none | Player 1 | Player 2 | Player 3 | Player 4 (and helper seats are listed after the people). Key `handicap`, values `none` or `p1`..`p4`.
- Effect: that seat's hand size is 4 for the whole game (dealt 4, refilled to 4). Everyone else holds 3.
- Optional future per-profile form (same style as Bridge Builders): a comma list of hand sizes per seat, `"3,4,3"`, each clamped to 3..4; any unknown string means no handicap.

### 2.10 First-run tutorial script (English lines, each under 120 characters)
Shown on the START screen the first time (and via the coach button), as 8 big picture cards with the line beneath; a grown-up can tap Next, a child can ignore them. Keys `trail_together_tutorial_1`..`_8`.
1. "These little hedgehogs want to get home to their cosy burrow."
2. "The trail is made of coloured stones. Each colour has its own shape too."
3. "A hop card sends a pal to the next stone of the same colour."
4. "A stone card lights a new stone at the end of the trail."
5. "A lantern card is a helper: light any stone, or hop one step."
6. "When the fog cards come, the fog rolls closer. Watch the fog track!"
7. "Talk together. Which card helps a friend the most?"
8. "When every pal is home, we all win. Ready?"

### 2.11 Exact English strings (keys in `res/values/trail_together_strings.xml`; Spanish in `values-es`)
| Key | English |
|---|---|
| `trail_together_title` | Trail Together |
| `trail_together_tagline` | Light the trail and get home before the fog! |
| `trail_together_teaches` | Planning ahead, matching colours and shapes, and taking turns as a team. |
| `trail_together_help_title` | Trail Together |
| `trail_together_help_text` | Everybody helps the hedgehog pals hop home along the trail. On your turn play one card: a hop card sends a pal to the next stone of that colour, a stone card lights a new stone at the end of the trail, and a lantern card does either one. When you have no good card, swap one. Fog cards make the fog roll closer, so get every pal to the burrow before it arrives. We win together or we try again together. |
| `trail_together_seat_helper_tag` | (Helper) |
| `trail_together_band_little` | Little trail |
| `trail_together_band_medium` | Medium trail |
| `trail_together_band_big` | Big trail |
| `trail_together_pal_name_1` .. `_4` | Nutmeg, Pip, Clover, Burr (same in Spanish) |
| `trail_together_status_ready` | Everybody ready? Let's light the trail! |
| `trail_together_status_your_turn` | %1$s, pick a card |
| `trail_together_status_pick_pal` | Tap a pal to hop |
| `trail_together_status_stone` | The glowing spot lights up |
| `trail_together_status_lantern` | Lantern: light any stone, or hop one step |
| `trail_together_status_no_hop` | That card has no stone to hop to. Pick another or swap one. |
| `trail_together_status_swap` | Swap a card for a new one |
| `trail_together_status_helper_turn` | %1$s is choosing |
| `trail_together_status_fog_rolls` | The fog rolls closer! |
| `trail_together_status_fog_near` | The fog is very close! |
| `trail_together_status_burrow_open` | The burrow door is open! Everybody hop home! |
| `trail_together_status_win` | We did it! |
| `trail_together_status_win_sub` | Everyone is home, cosy and safe. |
| `trail_together_status_lose` | Try again together |
| `trail_together_status_lose_sub` | The fog got there first. Let's make a new trail. |
| `trail_together_action_swap` | Swap |
| `trail_together_action_hint` | Help me choose |
| `trail_together_action_light` | Light a stone |
| `trail_together_action_hop_one` | Hop one |
| `trail_together_action_play_again` | Play again |
| `trail_together_action_finish` | Finished |
| `trail_together_action_next` | Next |
| `trail_together_card_hop_desc` | %1$s hop card: a pal hops to the next %1$s stone |
| `trail_together_card_stone_desc` | %1$s stone card: light a %1$s stone at the end of the trail |
| `trail_together_card_lantern_desc` | Lantern card: light any stone, or hop one step |
| `trail_together_colour_berry` | Berry |
| `trail_together_colour_sun` | Sun |
| `trail_together_colour_leaf` | Leaf |
| `trail_together_colour_sky` | Sky |
| `trail_together_colour_plum` | Plum |
| `trail_together_colour_peach` | Peach |
| `trail_together_shape_circle` | circle |
| `trail_together_shape_triangle` | triangle |
| `trail_together_shape_square` | square |
| `trail_together_shape_diamond` | diamond |
| `trail_together_shape_star` | star |
| `trail_together_shape_hexagon` | hexagon |
| `trail_together_pal_desc` | %1$s's pal is on stone %2$d of %3$d |
| `trail_together_pal_home` | %1$s's pal is home |
| `trail_together_spot_desc` | Stone %1$d: %2$s %3$s |
| `trail_together_spot_dim` | Stone %1$d: not lit yet |
| `trail_together_fog_desc` | Fog: step %1$d of %2$d |
| `trail_together_trail_desc` | Trail: %1$d of %2$d stones lit |
| `trail_together_hand_desc` | %1$s holds %2$d cards |
| `trail_together_score_title` | Trail report |
| `trail_together_score_tries` | Tries together: %1$d |
| `trail_together_score_homes` | Trails finished: %1$d |
| `trail_together_score_turns` | Plays this try: %1$d |
| `trail_together_score_pals_home` | %1$d of %2$d pals home |
| `trail_together_tutorial_1` | These little hedgehogs want to get home to their cosy burrow. |
| `trail_together_tutorial_2` | The trail is made of coloured stones. Each colour has its own shape too. |
| `trail_together_tutorial_3` | A hop card sends a pal to the next stone of the same colour. |
| `trail_together_tutorial_4` | A stone card lights a new stone at the end of the trail. |
| `trail_together_tutorial_5` | A lantern card is a helper: light any stone, or hop one step. |
| `trail_together_tutorial_6` | When the fog cards come, the fog rolls closer. Watch the fog track! |
| `trail_together_tutorial_7` | Talk together. Which card helps a friend the most? |
| `trail_together_tutorial_8` | When every pal is home, we all win. Ready? |

Wording rule (test T34): no string contains "winner", "loser", "you lose", "you lost", "game over" or "wrong".

### 2.12 The five UI states
1. **Setup** (Game Hub): band choice, helper pals stepper, helper level, handicap row, hint toggle, rotate toggle; Play button 56dp.
2. **Start**: ready screen (same-screen) with player badges, then the 8-card tutorial strip (first run), then a 3-second "Light the trail!" countdown with the deal animation.
3. **Mid-game**: top: fog track with cloud; middle: serpentine trail with pals; bottom (and far side, rotated, when `rotate` is on): each seat's open hand, current seat glowing, Swap and Help buttons.
4. **Post-game**: full-width banner `We did it!` or `Try again together` with the sub line; pals home count; Play again and Finished buttons.
5. **Score card**: Trail report: tries together, trails finished, plays in the last try, pals home; a one-line "what we practised" (the parent line, 1.7); stars are not used in this game (no ranking).

---

## 3. SCALE

### 3.1 Length by mode
Target 8-15 minutes including intro; measured as plays x seconds per play + 1 minute (section 2.6 table: 8 to 14 minutes across bands and pal counts). The Little band is the shortest; a four-person Big game is the longest. A try is capped by design (the Fog); there is no timer a child sees.

### 3.2 Player counts
1 person (2 pals, one hand), 2, 3, 4 people; helper pals fill empty seats up to 4. Pals = max(2, seats).

### 3.3 Deterministic seeds
- `seed: Long` drawn once from the game's `Random` at `startMatch()`; every shuffle and trail-colour choice uses `kotlin.random.Random(seed)` in a fixed order: (1) trail colours, (2) deck shuffle for the deal, (3) shuffle of the remaining pile with the Fog cards. Same seed + same band + same seats = identical try (test T07).
- `playAgain()` draws a new seed from the session `Random`.
- v1: the seed is shown on the score card as a "trail number" and can be passed back through `extras["seed"]`.
- Bots use `Random(seed xor 0x5EED)` stepped per decision so replays of a recorded try are identical.

### 3.4 Performance budget
- Turn-based: no per-frame game loop. The only timers are the helper pause and animations.
- State is at most 86 cards, 25 spots, 4 pals: under 4 KB per commit; a `commit()` allocates one `TrailState`. Budget: engine step < 1 ms, one recomposition per play, 60 fps (16 ms frames) on the Fold 5 and the reference tablet, no jank above 32 ms.
- All art is vector (Compose `Canvas`/shapes); no bitmaps; extra memory under 10 MB.
- Snapshot JSON under 4 KB (pile stored as short codes).

### 3.5 Resume and persistence
- `ResumableGame` per the house pattern: snapshot is JSON of `TrailSave` (section 4.2). Saved at every turn boundary (state `PICK_CARD`); a half-made play (`PICK_TARGET`) is not saved, resume returns to `PICK_CARD` of the same seat.
- Snapshot is null before start, after the match ends, and in any mode other than `SINGLE_PLAYER_VS_BOT` or `SINGLE_DEVICE_PASS_AND_PLAY`.
- `restore` validates everything and rejects, never repairs (wrong version, roster mismatch, card counts that do not match the deck table for the band and pals, pal index out of range, fog above its length, hand size wrong, duplicate or impossible trail colours, unknown card codes). On `false` the game stays untouched.
- The session tally (tries, finished trails) is stored in the save; it is not written to the profile (no per-profile data in this scaffold).

### 3.6 Multiplayer plan
- Now: same-screen only; `PlayMode.SINGLE_DEVICE_PASS_AND_PLAY` and `SINGLE_PLAYER_VS_BOT`. Not listed in `GameHubSpecs.networkedGames`. The spec has no Nearby/Online mode.
- Later (v2, Nearby): host-authoritative. Host holds the seed, deck order and all hands; clients send `PlayCard(seat, handIndex, target)` and `Swap(seat, handIndex)`; host validates (turn owner, legal target) and broadcasts `StateSync`. Hands become hidden (each child sees only their own cards, others see backs). The transport identity hardening rules from the 2026-09-28 fix apply (never trust a message's claimed seat; derive from `fromPlayerId`). Plan: one `TrailNet` message file, no engine changes (the engine already takes a seat in every call).
- Online relay: not planned.

### 3.7 Phased delivery
| Phase | Content | Effort |
|---|---|---|
| 0 | Engine (deck, trail, rules, fog), `TrailRules` tables, seeded sim harness, unit tests T01-T24 | M |
| 1 | `TrailTogetherScreen` same-screen: trail, hands, pals, fog, Swap, Help, ready screen, post-game, score card; helper pals; hub registration (coordinator) | L |
| 2 | First-run tutorial strip, coach card, tips, TalkBack labels, Reduced Motion variants, EN/ES review | M |
| 3 | Resume snapshot + validation tests T25-T31, score card polish, parent-dashboard line | S |
| 4 | Sound + haptics pack (hop tick, stone chime, soft fog rumble), art polish, device pass on Fold 5 cover/inner/tabletop | M |
| 5 (later) | Nearby hidden-hand mode | L |

### 3.8 Risk register
| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Balance drifts from the simulation (Kotlin engine differs in a rule detail) | M | M | Test T22 asserts the win-rate bands per band and pal count; the Python sim is the spec reference |
| R2 | Little game too long for age 4 attention | M | M | 18 s/play assumption; hint glow on; fall back to length 14 if playtests exceed 10 minutes |
| R3 | Adults dominate decisions | H | M | Open hands + "card owner decides" tutorial line + handicap row + helper glow default off for Big |
| R4 | Colour-blind children cannot tell stones apart | L | H | Shapes and patterns on every stone/card/button; test T06 asserts unique shape per colour |
| R5 | Theme/name resembles a publisher product | L | H | Original pals and Fog; no owl/ogre/postmouse/treasure/ship; O8 name search before art |
| R6 | Resume corrupts state (bad snapshot) | L | M | Strict validation, reject-never-repair, tests T25-T31 |
| R7 | Fog card reveal on the draw frightens a child | L | M | Soft grey cloud, 600 ms slide, no sound spike (peak under -18 dBFS), Reduced Motion instant |
| R8 | 4-pal games exceed 14 minutes | M | L | Deck table is data; reduce `hopsPerColour` or length per band without code change |
| R9 | HubLocalizationTest fails on registration because hub texts are English literals in the spec | H | L | Hub strings file (`trail_together_hub_strings.xml`, hubKey style) is owed in the registration step (4.3 item 9) |
| R10 | Parallel sessions touching shared files | M | M | This package touches none; registration checklist is for the coordinator |

### 3.9 Test plan (every item exists as an `@Ignore`d stub in the scaffold; ID = test name prefix in the table)
Files under `app/src/test/java/com/gamesuite/games/trailtogether/`.

`TrailTogetherRulesTest`
| ID | Test name | Checks |
|---|---|---|
| T01 | `deckCompositionMatchesTableForEveryBandAndPalCount` | stones/hops/lanterns/fog per row of 2.5 |
| T02 | `fogCardsEqualFogTrackLengthForEveryBand` | fog count = track length |
| T03 | `palsAreAtLeastTwoAndEqualSeatsUpToFour` | `palsFor(1..4)` = 2,2,3,4 |
| T04 | `handicapParseAcceptsNoneAndP1ToP4AndRejectsGarbage` | hand sizes list |
| T05 | `bandParamsAreMonotonicLittleToBig` | colours, length, stones grow |
| T06 | `colourShapePairsAreUniquePerColour` | shapes distinct |

`TrailTogetherEngineTest`
| ID | Test name | Checks |
|---|---|---|
| T07 | `sameSeedGivesIdenticalDeckAndTrail` | determinism |
| T08 | `initialTrailHasNoTwoAdjacentSameColour` | start trail rule |
| T09 | `dealGivesHandSizeFromNonFogCardsOnly` | no fog in hands |
| T10 | `hopCardMovesPalToNextLitSpotOfThatColour` | hop |
| T11 | `hopCardWithNoMatchingSpotIsRejectedAndHandUnchanged` | illegal |
| T12 | `stoneCardLightsFrontierSpotWithCardColour` | stone |
| T13 | `lanternCanLightAnyColourStoneOrHopOneSpot` | lantern both modes |
| T14 | `burrowOpensOnlyWhenTrailIsLitThroughAndAcceptsAnyHop` | burrow rule |
| T15 | `swapDiscardsDrawsAndStillPassesTheTurn` | swap |
| T16 | `fogCardAdvancesFogAndDrawsReplacement` | fog draw |
| T17 | `fogReachingTrackEndLosesTheTry` | lose |
| T18 | `allPalsHomeWinsBeforeAnyFurtherDraw` | win |
| T19 | `handicapSeatGetsFourCardHand` | handicap |
| T20 | `turnOrderRotatesAcrossSeatsIncludingHelpers` | order |
| T21 | `hintNeverSuggestsAnIllegalMove` | hint |
| T22 | `smartPolicyWinRateStaysInsideTargetBandPerBandAndPalCount` | 2000 seeds each, +/- 0.06 |
| T23 | `soloUsesTwoPalsAndOneHand` | solo |
| T24 | `playAgainStartsFreshSeedAndKeepsSessionTally` | replay |

`TrailTogetherGameTest`
| ID | Test name | Checks |
|---|---|---|
| T25 | `snapshotIsNullBeforeStartAndAfterMatchOver` | null cases |
| T26 | `snapshotRoundTripRestoresIdenticalState` | round trip |
| T27 | `restoreRejectsWrongVersion` | version |
| T28 | `restoreRejectsRosterMismatch` | roster |
| T29 | `restoreRejectsOutOfRangePalPositionAndFogOverflow` | ranges |
| T30 | `restoreRejectsDeckThatDoesNotMatchTable` | card counts |
| T31 | `restoreFailureLeavesGameUntouched` | no side effects |
| T32 | `leavingBeforeAnyTryEndsUsesAbortPath` | abort |
| T33 | `resultMarksEveryoneWinnerOnlyWhenAtLeastOneTryWon` | result |
| T34 | `resultAndStatusTextUseCooperativeWordingOnly` | wording rule |
| T35 | `pauseStopsHelperAutoplayAndResumeContinues` | pause |

`TrailTogetherHubSpecsTest`
| ID | Test name | Checks |
|---|---|---|
| T36 | `specIsKidsAudienceWithMinAgeFour` | audience |
| T37 | `specIsSameScreenOnlyAndNotNetworked` | modes |
| T38 | `modeSeatBoundsMatchEngineLimits` | 1..4 |
| T39 | `optionKeysAreBandHandicapHintsRotateAndHelperLevel` | extras keys |
| T40 | `englishAndSpanishStringFilesDefineTheSameKeys` | locales |
| T41 | `noStringValueContainsTodoMarker` | `TODO translate` only in comments |

Beyond unit tests (phase 1+): Compose UI tests for the five states, a TalkBack content-description audit, a device pass (Fold 5 cover, inner, tabletop; reference tablet), and a human playtest with at least 3 children aged 4, 5 and 6 plus a grown-up per band before the Little numbers are final.

### 3.10 Simulation evidence (throwaway, outside the repo)
Python model of exactly the rules in 2.3 (hand 3, open Burrow, lantern both modes, swap = discard random card), 3000 seeds per cell, policies `smart` (2.7) and `random`:
- Little: win 0.91 (2 pals, 25.0 plays), 0.91 (3 pals, 30.1), 0.92 (4 pals, 36.7); random 0.76 / 0.63 / 0.61.
- Medium: 0.81 (30.1), 0.81 (36.4), 0.81 (42.8); random 0.65 / 0.52 / 0.41.
- Big: 0.70 (38.7), 0.66 (47.0), 0.69 (54.9); random 0.56 / 0.42 / 0.35.
- Solo, one hand of 3, 2 pals: Little 0.89, Medium 0.83, Big 0.72.
These are starting values, not final tuning; the table in 2.5 is data so it can be edited without code changes.

---

## 4. SCAFFOLD

### 4.1 What was created (compiling skeletons only; nothing compiled by the author)
`app/src/main/java/com/gamesuite/games/trailtogether/`
- `TrailTogetherModels.kt`: `TrailBand`, `TrailColour`, `TrailCardKind`, `TrailCard`, `TrailPhase`, `LanternMode`, `TrailSeat`, `TrailSpot`, `TrailPal`, `TrailEvent`, `TrailState`, `TrailTarget`, `TrailMoveResult`, `TrailHint`.
- `TrailTogetherRules.kt`: `TrailRules` (constants, `palsFor`, `band`, `deck`), `TrailBandParams`, `TrailDeckSpec`, `TrailHandicap`.
- `TrailTogetherEngine.kt`: `TrailEngine` interface and `StubTrailEngine` (safe values, no state).
- `TrailTogetherSave.kt`: `TrailSave` DTOs and `TrailSaveCodec` (encode real, validation `TODO` in KDoc, decode rejects until implemented).
- `TrailTogetherGame.kt`: `TrailTogetherGame` (`GameModule`, `ResumableGame`) per the Bridge Builders pattern.
- `TrailTogetherHubSpecs.kt`: `TrailTogetherHubSpecs` (audience KIDS, minAge 4, NOT registered anywhere).

Resources: `res/values/trail_together_strings.xml`, `res/values-es/trail_together_strings.xml` (keys of 2.11; Spanish values are real; `TODO translate` appears only in XML comments for strings that need a native review).
Tests: four `@Ignore`d classes listed in 3.9.

### 4.2 Snapshot DTO (`TrailSave`)
Fields: `version`, `players` (id + isBot), `band`, `handicap`, `difficulty`, `seed`, `trail` (list of colour ids for lit spots, in order), `pals` (positions), `hands` (card codes per seat), `pile` (remaining card codes in draw order), `discard` (card codes), `fog`, `currentSeat`, `plays`, `tries`, `wins`, `eventSerial`. Card code: `S:berry` hop, `T:sun` stone, `L` lantern, `F` fog.
Validation is a TODO in the codec KDoc; rules listed in 3.5.

### 4.3 Integration checklist for the coordinator (NOT done here; files owned by others)
1. `GameHubSpecs.baseSpecs()`: add `TrailTogetherHubSpecs.spec`. Do NOT add `trail-together` to `networkedGames`.
2. `GameCatalog` glyph for `trail-together`: hedgehog (U+1F994).
3. `GameTheme.accents`: light 0xFF2E6B3F, dark 0xFF8FD6A0 (hand contrast: about 6.3:1 on the light surface and about 10:1 on the dark one; the spec test must confirm uniqueness against existing accents).
4. `MainActivity`: route `trail-together` with `rememberActiveModule(sessionManager, "trail-together") { TrailTogetherGame() }` and the new `TrailTogetherScreen` (phase 1).
5. `GameTips`: three first-run tips (reuse tutorial lines 3, 4 and 7).
6. `GameCoach`: motif `CoachMotif.PATH`, entry from `trail_together_tutorial_1..5` and a fact line; do NOT add to `ownCoachInGame`.
7. `GameAudienceTable`: nothing (the spec carries `audience` and `minAge`).
8. The parent dashboard line is `trail_together_teaches`.
9. Hub strings: the spec uses English literals (as Bridge Builders did before its hub strings file); add `res/values{,-es}/trail_together_hub_strings.xml` with `hubKey(text)` names when registering, or `HubLocalizationTest` will fail.
10. Enable the 41 `@Ignore`d tests one by one as the engine lands.
