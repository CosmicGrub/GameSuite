# Pie Detective (`pie-detective`)

Status 2026-11-07, branch `session/wave-3`. Scope, script, scale and scaffold package. **Nothing in this package has been built, compiled or run by Gradle.** The only thing executed is a throwaway Python balance simulation (section 3.10) that lives outside the repo.

Legal rule (hard): everything below is original (names, art, card text, layouts, wording). Only the mechanics of the genre are reused: gather clues, eliminate suspects among a cast, catch the thief before it reaches an exit. No name, rulebook wording or layout of any publisher's product is used. Deliberate distance: the cast has no foxes, the thief runs a three-hop market lane (not a path of tiles), clues are chosen by the players from six pictured traits and revealed through a hold-to-peek lens (no decoder, no dice).

---

## 1. SCOPE

### 1.1 Purpose
A short cooperative whodunit for children who can look carefully but cannot yet read. Somebody took the pie from the pie stall and is sneaking along the market lane to the back gate. The team shares one cast of animals with visible accessories, takes turns choosing a clue to look for (a hat? glasses?), peeks at the answer through a magic lens, tells the others and rules animals out. Catch the right animal before the thief reaches the gate: **We did it!** Otherwise: **Try again together.** Mechanical sibling of the cooperative Kids shelf (`bridge-builders`, `trail-together`): same wording, same-screen, no reading, a clear team goal.

### 1.2 Audience and adult + child fairness
- Ages 5+ (`minAge = 5`), 2 to 4 players; a lone child plays with the Sidekick (a computer seat), so the table always has at least two seats.
- Fairness levers (visible in the setup screen, same wording everywhere):
  1. **Lucky peek for** (handicap row): one chosen seat gets one Lucky peek per case. A Lucky peek is a normal peek after which the thief does NOT sneak that turn.
  2. **Sidekick hints**: a soft glow on the most helpful clue (best split of the remaining animals). Default ON for Little and Medium, OFF for Big.
  3. **Sidekick flips for us** (auto-flip): the app flips the animals that do not match, so a child only has to choose, peek and tell. Default ON for Little, OFF for Medium and Big.
  4. **Peek, tell, show**: the child who peeks says the answer in their own words before showing everyone. Grown-ups must not blurt it; the tutorial line and a first-run tip say "Tell your friends what you saw".
  5. Band (Little / Medium / Big) is chosen for the whole team.
- No individual scores, no "best detective". Stars belong to the team.

### 1.3 MVP vs v1 vs later
| Tier | Content |
|---|---|
| MVP (phase 0-3) | Same-screen play for 2-4 (people plus Sidekick seats), the 12-animal cast with 6 traits, Case Book of 2-3 cases, peek lens, notebook strip, thief lane, Sneak deck, Catch, handicap row, hints, auto-flip, tutorial, resume, score card, EN + ES, TalkBack |
| v1 | Extra cast skins (art only, same trait table), case-of-the-day fixed seed, per-profile Lucky-peek list through `extras["handicap"]`, sound and haptic pack, parent-dashboard line |
| Later | Nearby mode with a REAL private peek (the answer is sent only to the asking device), a fifth clue type (species group), story case books, grown-up "detective school" with deeper logic |

### 1.4 Out of scope
Online play, accounts, chat, any reading of text during play, a timer a child can fail on, scary imagery, in-app purchases, ads, third-party SDKs, free-text names beyond the existing profile names.

### 1.5 Safety and age notes
- The thief is a cartoon animal who took a pie. No chase violence, no weapons, no jail: the caught thief returns the pie and says sorry (illustration only).
- Failure is the team's: `Try again together`; the thief simply "gets away with a tummy full of pie".
- No data leaves the device; no new permissions; no cameras (the "lens" is an on-screen circle).
- Pass-the-device screens never show the thief or any hidden information; the lens only shows the answer to a question the asker chose.
- Kid visibility through `GameAudience.KIDS` + `minAge = 5`.
- No flashing above 2 Hz; the lens glow is a slow pulse (1 s period); Reduced Motion removes the pulse.

### 1.6 Accessibility
- Every trait is shown by an icon AND a name label; every answer by shape + colour + icon: Yes = green circle with a tick, No = orange rounded square with a cross (never colour alone).
- Touch targets 56dp minimum (suspect cards at least 88x112dp in Little with 8 cards on 2 rows of 4, 72x96dp in Big with 12 cards on 3 rows of 4; trait buttons 72dp; Catch, Show, Hint 56dp high).
- TalkBack: every suspect has a description built from `pie_detective_suspect_desc` (name plus its accessories); the lens is a toggle button "Peek" that announces the answer as a polite live-region message (no press-and-hold needed); flip, catch and hint are plain buttons; the notebook is a list with `pie_detective_notebook_yes/no` items.
- Reduced Motion and "touch and hold" alternatives: the lens becomes a tap-to-show / tap-to-hide toggle; no pulse, no wobble; flips and sneaks are instant with a 150 ms highlight.
- Font scale up to 200 percent: names wrap under the card art; art never shrinks.
- Fully playable on mute: every sound has a visual and a haptic twin.
- One-handed and switch access: no drag required (the lens works by press-and-hold OR toggle).
- Kids theme: warm cream, Baloo type, shape + colour tokens.

### 1.7 What it teaches (one line for the parent dashboard)
EN: "Careful looking, yes and no reasoning, and ruling things out as a team."
ES: "Mirar con atención, razonar con sí y no, y descartar posibilidades en equipo."
(String key `pie_detective_teaches`.)

### 1.8 Dependencies
- Core: `GameModule`, `ResumableGame`, `GameResult`/`PlayerScore`, `GameAudience.KIDS`, `GameHubSpec.minAge`, `HubOptions`, `CpuDifficulty` (all exist on this branch), `abortMatch()`.
- Shared files for the coordinator (NOT edited here): `GameHubSpecs`, `GameCatalog`, `GameTheme`, `MainActivity`, `GameTips`, `GameCoach`.
- A new screen `ui/PieDetectiveScreen.kt` and vector art composables for the 12 animals (phase 1-2; outside this scaffold).
- Optional later: profiles foundation for per-profile Lucky peeks; Nearby transport for the private-peek mode.

### 1.9 Open questions (each with a recommended default)
| # | Question | Recommended default |
|---|---|---|
| O1 | The pie plot and the name "Pie Detective" sit near a published whodunit's theme (a stolen pie). Keep? | Keep the name the owner chose; the cast has no foxes, no 'plot twist' devices, and every other element is original; run a trademark and app-store search before art is commissioned; fallback name "Cake Detective" with the same rules |
| O2 | Does a lost case end the book? | No. The book always plays all its cases; the score card shows cases solved |
| O3 | Book win rule (for the stats result) | Finished book: team WIN when cases solved >= ceil(cases / 2) (Little 1 of 2, Medium and Big 2 of 3); left early: WIN when at least one case was solved |
| O4 | Wrong flips: forbid or allow? | Always forbidden: a tap on an animal that still matches bounces back with "Look again!" (no penalty); difficulty comes from thief speed and hints, not from traps |
| O5 | Should a person other than the active seat be allowed to Catch? | No: only the active seat; it is their turn's action instead of asking |
| O6 | Private peek on one shared screen is only a ritual (the answer is then shown to all anyway) | Keep as the Peek-Tell-Show ritual (language practice); real privacy comes with Nearby later |
| O7 | HubFamily for the card | `HubFamily.ARCADE_PUZZLE` (closest of the five values); no new enum value |
| O8 | Cast names and species trademark check | Names in 2.5 are generic and alliterative; confirm with a search; the animal set is free to change without touching rules because rules use only the trait table |
| O9 | Add `pie-detective` to `GameAudienceTable`? | No; the spec carries `audience` and `minAge` itself |

---

## 2. SCRIPT

### 2.1 Story and cast overview
Market day. The pie from the **Pie stall** is gone! Footprints lead along the **market lane** to the **Back gate**. The thief is one of twelve animal friends who were at the market. The team's helper is the **Sidekick**, a small round robot with a magnifying glass. Tone: cosy and silly. A caught thief gives the pie back.

### 2.2 Components
- **Cast:** 12 animals (2.5). Each case shows a **lineup** drawn from the cast: Little 8, Medium 10, Big 12 (seeded random subset, shuffled order so position gives nothing away).
- **Traits:** six visible accessories in fixed order: Hat, Glasses, Scarf, Bag, Boots, Bow tie. Each animal has each accessory or not. Little asks about the first 4, Medium the first 5, Big all 6.
- **Thief:** one lineup animal chosen by the seed. Hidden until the case ends.
- **Lane:** 4 places, 3 hops: Pie stall (0), Fountain (1), Bridge (2), Back gate (3). The thief token starts at 0. Reaching the Back gate loses the case.
- **Sneak deck:** 12 cards, `dash` cards move the thief one hop, `pause` cards do nothing. Dash count per band in 2.6. Drawn without replacement; when all 12 have been drawn, reshuffle (derived seed).
- **Notebook:** a strip of the clues found: trait icon + Yes/No stamp.
- **Peek lens:** a 120dp circle over a frosted panel (2.3 lens rules).
- **Case Book:** a session of 2 (Little) or 3 (Medium, Big) cases; each case = new lineup, new thief, new Sneak deck.

### 2.3 Rules as a numbered script
1. Setup: choose band, Sidekicks (0..3), handicap, hints, auto-flip, then Start. Seats are 2 to 4 (people plus Sidekicks); a lone child plays with 1 Sidekick.
2. A Case Book of `cases` cases begins. Each case, in order: draw the lineup (seeded, size by band), choose the thief (seeded), shuffle the Sneak deck, put the thief token on Pie stall (hop 0), clear the notebook, flip no animal.
3. Seats take turns clockwise, seat 0 first in case 1; in the next case the first seat is the next seat after the one who caught the thief (or after the last seat if the thief escaped).
4. In same-screen play, a human's turn starts with a **Pass** screen: "Pass to [badge]" and a big "I'm ready" button (skipped when the same human plays again, when only one human is playing, and for Sidekicks).
5. On your turn you do exactly ONE of:
   a. **Ask**: pick one trait button you have not asked yet this case (asked traits are greyed with their stamp). Then follow the **lens rules** (below). After the clue is shown and the non-matching animals are flipped, the thief may sneak (rule 7).
   b. **Catch!**: tap Catch!, then tap the animal you think is the thief.
6. **Lens rules (Peek, Tell, Show):**
   1. After choosing a trait the screen shows the frosted panel and the lens circle with the trait icon.
   2. Press and hold the lens for at least 400 ms: the answer (a big Yes tick-circle or No cross-square) is visible INSIDE the circle only; letting go hides it again. (Alternative for Reduced Motion and TalkBack: the lens is a toggle; one tap shows, one tap hides, and the answer is announced.)
   3. The asker tells the others in their own words ("Yes, it has a hat!"). Nothing is typed; the "Show everyone" button becomes active once the lens has been held or toggled once.
   4. "Show everyone" enlarges the answer over the whole panel for 2 seconds, stamps it in the notebook as trait icon + Yes/No, and moves on to flipping.
   5. If the app leaves the foreground during peek, the turn returns to the trait choice at no cost (the trait is not marked asked until "Show everyone").
7. **Flip:** the team taps every animal whose trait value does not match the answer; flipped animals are cleared (turned face-down, no longer suspects). Tapping an animal that still matches bounces back with "Look again! That one still matches." and changes nothing. With "Sidekick flips for us" ON the app flips them all (0.15 s apart).
8. **Sneak:** after the flips are all done, draw the top Sneak card: dash moves the thief one hop towards the gate (status line "The thief sneaks closer!"), pause does nothing ("The thief waits and listens."). If a seat used its **Lucky peek** this turn, the Sneak card is skipped.
9. If the thief token is on the Back gate, the case is lost (rule 12). Otherwise pass the turn.
10. **Catch!:** tap the animal. If it is the thief, the case is won (rule 11). If not, that animal is cleared (flipped), the thief jumps 2 hops (status "Not the thief! It sneaks away fast."), and if that reaches or passes the gate the case is lost; otherwise the turn passes with no Sneak draw. Catch! is always available, even with several animals left (a gamble). When exactly one animal is left, the Catch! button pulses.
11. Case won: reveal the thief with a sorry-pie animation; stars for the team: 3 if the thief was at hop 0 or 1 when caught, 2 if at hop 2. The status says "We did it!".
12. Case lost: reveal the thief at the gate with the pie; the status says "Try again together". The Case Book continues with the next case.
13. After the last case, the **score card** shows cases solved, stars and Lucky peeks used. Play again opens a new Case Book (new seed).
14. Sidekick seats take their turn automatically after a pause (2.7). "Sidekick hint" on a human's turn glows the best clue (2.8).
15. Leaving before the first case finishes is an abort (no stats); afterwards it ends the session with the result rule O3.

### 2.4 State machine
| State | Entered when | Inputs | Next |
|---|---|---|---|
| `PASS` | a human's turn begins (same-screen, different human) | I'm ready | `CHOOSE` |
| `CHOOSE` | turn begins (after PASS, or directly) | pick trait, Lucky peek toggle, Catch!, Sidekick hint | `PEEK` (trait) or `CATCH` |
| `PEEK` | a trait is chosen | hold or toggle lens, Show everyone (after one peek), back to choose | `FLIP` after Show everyone |
| `FLIP` | the clue is shown | tap animals (or auto) | `SNEAK` when all non-matching are flipped |
| `SNEAK` | flips complete | none (card reveal) | `CHOOSE` of next seat (via `PASS`), or `CASE_LOST` |
| `CATCH` | Catch! tapped | tap an animal, cancel | `CASE_WON`, or `CHOOSE` of the next seat, or `CASE_LOST` |
| `CASE_WON` / `CASE_LOST` | catch / thief at gate | Next case, Finished | next case's first state, or `BOOK_DONE` |
| `BOOK_DONE` | last case ended | Play again, Finished | new book or exit |
Sidekick seats auto-run `CHOOSE -> PEEK -> FLIP -> SNEAK`. `pause()` freezes Sidekick autoplay and animations.
Screen-level five UI states (2.12) wrap this machine.

### 2.5 Content tables as data

**The 12-suspect cast.** Order of the trait vector: Hat, Glasses, Scarf, Bag, Boots, Bow tie (1 = has it). The first four columns are unique across the cast (12 of the 16 possible patterns; the four unused patterns are 0000, 0111, 1110, 1111), so any lineup is solvable with the first four traits. Boots and Bow tie each split the cast 6 / 6.
| # | id | Name (EN) | Species | Fur colour (light theme) | Hat | Gls | Scf | Bag | Bts | Bow | Vector | Look of the accessories (art brief) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | mouse | Milo Mouse | mouse | 0xFFA6A6B0 | 0 | 0 | 0 | 1 | 1 | 0 | 000110 | red wellies, little picnic satchel |
| 2 | frog | Freddie Frog | frog | 0xFF7CC576 | 0 | 0 | 1 | 0 | 0 | 1 | 001001 | stripy scarf, spotty bow tie |
| 3 | otter | Olive Otter | otter | 0xFF9C6B45 | 0 | 0 | 1 | 1 | 1 | 1 | 001111 | knitted scarf, basket, yellow boots, bow tie |
| 4 | pig | Penny Pig | pig | 0xFFF2A7B8 | 0 | 1 | 0 | 0 | 0 | 0 | 010000 | round spectacles only |
| 5 | sheep | Wilma Woolly | sheep | 0xFFF4EBDD | 0 | 1 | 0 | 1 | 1 | 0 | 010110 | square glasses, shopping bag, green boots |
| 6 | cat | Clementine Cat | cat | 0xFFF0A04B | 0 | 1 | 1 | 0 | 0 | 1 | 011001 | cat-eye glasses, long scarf, bow tie |
| 7 | badger | Barnaby Badger | badger | 0xFF4A4A55 | 1 | 0 | 0 | 0 | 1 | 1 | 100011 | top hat, boots, bow tie |
| 8 | rabbit | Rosie Rabbit | rabbit | 0xFFE8D8C3 | 1 | 0 | 0 | 1 | 0 | 0 | 100100 | sun hat, carrot bag |
| 9 | penguin | Percy Penguin | penguin | 0xFF2B3A55 | 1 | 0 | 1 | 0 | 1 | 0 | 101010 | bobble hat, scarf, boots |
| 10 | goat | Gus Goat | goat | 0xFFD1B48C | 1 | 0 | 1 | 1 | 0 | 1 | 101101 | flat cap, scarf, backpack, bow tie |
| 11 | duck | Dilly Duck | duck | 0xFFF7D44A | 1 | 1 | 0 | 0 | 1 | 1 | 110011 | bonnet, round glasses, boots, bow tie |
| 12 | squirrel | Sunny Squirrel | squirrel | 0xFFB5532E | 1 | 1 | 0 | 1 | 0 | 0 | 110100 | cap, goggles, acorn bag |
Trait totals across the cast: Hat 6, Glasses 5, Scarf 5, Bag 6, Boots 6, Bow tie 6. Every animal has a unique silhouette (ears, beak, tail) so species alone is a safe visual anchor and the name is a text label for grown-ups.
Clue-lens rule for a trait t and the thief T: the answer is Yes when `T.has(t)` else No. The team flips every lineup animal X with `X.has(t) != answer`. Asked once per case per trait. Nothing else is ever revealed.

**Machine-readable spec (`pie-spec` v1; the engine constants must equal this, tests P01-P04)**
```json
{
  "version": 1,
  "traits": ["hat","glasses","scarf","bag","boots","bow"],
  "cast": [
    {"id":"mouse","species":"mouse","name":"Milo Mouse","v":"000110"},
    {"id":"frog","species":"frog","name":"Freddie Frog","v":"001001"},
    {"id":"otter","species":"otter","name":"Olive Otter","v":"001111"},
    {"id":"pig","species":"pig","name":"Penny Pig","v":"010000"},
    {"id":"sheep","species":"sheep","name":"Wilma Woolly","v":"010110"},
    {"id":"cat","species":"cat","name":"Clementine Cat","v":"011001"},
    {"id":"badger","species":"badger","name":"Barnaby Badger","v":"100011"},
    {"id":"rabbit","species":"rabbit","name":"Rosie Rabbit","v":"100100"},
    {"id":"penguin","species":"penguin","name":"Percy Penguin","v":"101010"},
    {"id":"goat","species":"goat","name":"Gus Goat","v":"101101"},
    {"id":"duck","species":"duck","name":"Dilly Duck","v":"110011"},
    {"id":"squirrel","species":"squirrel","name":"Sunny Squirrel","v":"110100"}
  ],
  "lane": {"places": ["stall","fountain","bridge","gate"], "hops": 3, "wrongCatchHops": 2, "thiefStart": 0},
  "sneakDeck": 12, "minSeats": 2, "maxSeats": 4, "luckyPeeksPerCase": 1, "peekHoldMs": 400, "showMs": 2000,
  "bands": {
    "little": {"lineup": 8,  "traits": 4, "dash": 4, "cases": 2, "hints": true,  "autoFlip": true,  "secondsPerTurn": 55},
    "medium": {"lineup": 10, "traits": 5, "dash": 5, "cases": 3, "hints": true,  "autoFlip": false, "secondsPerTurn": 45},
    "big":    {"lineup": 12, "traits": 6, "dash": 6, "cases": 3, "hints": false, "autoFlip": false, "secondsPerTurn": 50}
  }
}
```
Lineup selection: `Random(caseSeed).shuffled(cast).take(lineup)` then shuffled again for display order; thief = `random.nextInt(lineup)` of that order; Sneak deck = `dash` ones and `12 - dash` zeros shuffled with `Random(caseSeed xor 0x5EAC)`; `caseSeed = bookSeed * 31 + caseIndex`.

### 2.6 Win / lose conditions and difficulty bands
- Case won: Catch! on the thief. Case lost: thief reaches hop 3 (by dashes or a wrong Catch!).
- Book: cases are always all played. Team WIN for the stats result: finished book with cases solved >= ceil(cases / 2); left early with at least one case solved.
- Difficulty changes only the lineup size, the traits on offer, the dash density, the number of cases and the help defaults. The rules never change.

| Band | Aimed at | Lineup / traits | Dash cards of 12 | Cases | Case win rate: smart / random asker / eager guesser | Book win rate (random asker) | Turns per case (random asker) | Est. minutes per book |
|---|---|---|---|---|---|---|---|---|
| Little | 5, helped | 8 / 4 | 4 | 2 | 0.98 / 0.96 / 0.83 | 0.998 | 4.3 | about 9 |
| Medium | 6-7 | 10 / 5 | 5 | 3 | 0.92 / 0.86 / 0.72 | 0.947 | 4.6 | about 11.5 |
| Big | 7+ and grown-ups | 12 / 6 | 6 | 3 | 0.79 / 0.68 / 0.62 | 0.758 | 4.6 | about 12.5 |
Acceptance for the real engine (test P22): over 3000 seeded cases per band the smart asker's win rate must be within +/- 0.04 of the table, the random asker's within +/- 0.05.
Minutes = turns x seconds per turn x cases + 1 minute for intro and celebration.

### 2.7 Sidekick (computer seat) script
Sidekick seats are the existing computer seats (`bot1`..) displayed as "Sidekick" with tag "(Sidekick)". Level = `CpuDifficulty`.
Turn script:
1. If exactly one animal is uncleared: Catch! that animal (all levels).
2. Else pick a trait: HARD = the free trait with the smallest worst-case group (best split, ties by trait order); MEDIUM = best split 70 percent of the time, otherwise a random free trait; EASY = a random free trait.
3. Peek is automatic; the Sidekick says the answer (speech bubble with icon) for 1.2 s, then shows everyone.
4. Flip: the Sidekick flips the non-matching animals (0.15 s apart) regardless of the auto-flip option.
5. Never gambles with Catch! while two or more animals are left.
Timing: 1.2 s thinking pause (0.6 s under Reduced Motion); pauses when the app is paused.

### 2.8 Hint rule ("Sidekick hint" and the optional glow)
On a human's `CHOOSE`: glow the free trait with the smallest worst-case group (ties: earlier in the trait order). If one animal is left, pulse Catch! instead. Never suggests a trait already asked. Never plays for the child. No cost, not recorded. The Big band default has the glow off but the button is always there.

### 2.9 Handicap rule
- Setup row "Lucky peek for": none | Player 1 | Player 2 | Player 3 | Player 4. Key `handicap`, values `none` or `p1`..`p4`.
- Effect: that seat has ONE Lucky peek per case (a star toggle on its CHOOSE screen). Using it makes the Sneak step of that turn be skipped. Unused Lucky peeks do not carry over.
- Future profile form (same style as the other kids games): comma list of Lucky peeks per seat `"0,1,0"` each clamped to 0..2; unknown string means none.

### 2.10 First-run tutorial script (English lines, each under 120 characters)
Eight picture cards on the START screen the first time (and via the coach button). Keys `pie_detective_tutorial_1`..`_8`.
1. "Oh no! Someone took the pie and is sneaking to the back gate."
2. "The thief is one of these animals. We must find out who!"
3. "Choose a clue to look for: a hat, glasses, a scarf or a bag."
4. "Hold the magic lens to peek. Only the lens shows the answer."
5. "Tell your friends what you saw, then show everyone."
6. "Tap every animal that does not match. They are not the thief."
7. "After each peek, the thief may sneak a step closer to the gate."
8. "When only one animal is left, tap Catch! to grab the thief."

### 2.11 Exact English strings (keys in `res/values/pie_detective_strings.xml`; Spanish in `values-es`)
| Key | English |
|---|---|
| `pie_detective_title` | Pie Detective |
| `pie_detective_tagline` | Find the pie thief before it reaches the back gate! |
| `pie_detective_teaches` | Careful looking, yes and no reasoning, and ruling things out as a team. |
| `pie_detective_help_title` | Pie Detective |
| `pie_detective_help_text` | Someone took the pie and is sneaking to the back gate! Take turns choosing a clue to look for: a hat, glasses, a scarf, a bag and more. Hold the magic lens to peek at the answer, tell your friends, then show everyone. Tap every animal that does not match: they cannot be the thief. After each peek the thief may sneak a step closer. When only one animal is left, tap Catch! We catch the thief together or we try again together. |
| `pie_detective_seat_sidekick_tag` | (Sidekick) |
| `pie_detective_sidekick_name` | Sidekick |
| `pie_detective_band_little` | Little case |
| `pie_detective_band_medium` | Medium case |
| `pie_detective_band_big` | Big case |
| `pie_detective_case_title` | Case %1$d of %2$d |
| `pie_detective_status_pass_to` | Pass to %1$s |
| `pie_detective_status_choose` | %1$s, what shall we look for? |
| `pie_detective_status_hold_lens` | Hold the lens to peek |
| `pie_detective_status_tell` | Tell your friends what you saw |
| `pie_detective_status_show_hint` | Now show everyone |
| `pie_detective_status_flip` | Tap the animals that do not match |
| `pie_detective_status_flip_wrong` | Look again! That one still matches. |
| `pie_detective_status_sneak_dash` | The thief sneaks closer! |
| `pie_detective_status_sneak_pause` | The thief waits and listens. |
| `pie_detective_status_catch_pick` | Who is the thief? Tap to catch! |
| `pie_detective_status_catch_wrong` | Not the thief! It sneaks away fast. |
| `pie_detective_status_one_left` | Only one animal is left. Catch it! |
| `pie_detective_status_helper_turn` | %1$s is looking for a clue |
| `pie_detective_status_sidekick_says` | Sidekick says: try this clue |
| `pie_detective_status_case_won` | We did it! |
| `pie_detective_status_case_won_sub` | We caught the thief and saved the pie! |
| `pie_detective_status_case_lost` | Try again together |
| `pie_detective_status_case_lost_sub` | The thief reached the back gate. Next time! |
| `pie_detective_status_book_won` | We did it! |
| `pie_detective_status_book_won_sub` | Case book solved. Great detectives! |
| `pie_detective_status_book_lost` | Try again together |
| `pie_detective_status_book_lost_sub` | Let's open a new case book. |
| `pie_detective_action_ready` | I'm ready |
| `pie_detective_action_peek` | Peek |
| `pie_detective_action_show` | Show everyone |
| `pie_detective_action_catch` | Catch! |
| `pie_detective_action_hint` | Sidekick hint |
| `pie_detective_action_lucky` | Lucky peek |
| `pie_detective_action_next_case` | Next case |
| `pie_detective_action_play_again` | Play again |
| `pie_detective_action_finish` | Finished |
| `pie_detective_answer_yes` | Yes! |
| `pie_detective_answer_no` | No |
| `pie_detective_place_stall` | Pie stall |
| `pie_detective_place_fountain` | Fountain |
| `pie_detective_place_bridge` | Bridge |
| `pie_detective_place_gate` | Back gate |
| `pie_detective_lane_desc` | The thief is at the %1$s. %2$d hops from the back gate. |
| `pie_detective_trait_hat` | Hat |
| `pie_detective_trait_glasses` | Glasses |
| `pie_detective_trait_scarf` | Scarf |
| `pie_detective_trait_bag` | Bag |
| `pie_detective_trait_boots` | Boots |
| `pie_detective_trait_bow` | Bow tie |
| `pie_detective_clue_hat_yes` / `_no` | The thief has a hat / The thief has no hat |
| `pie_detective_clue_glasses_yes` / `_no` | The thief has glasses / The thief has no glasses |
| `pie_detective_clue_scarf_yes` / `_no` | The thief has a scarf / The thief has no scarf |
| `pie_detective_clue_bag_yes` / `_no` | The thief has a bag / The thief has no bag |
| `pie_detective_clue_boots_yes` / `_no` | The thief has boots / The thief has no boots |
| `pie_detective_clue_bow_yes` / `_no` | The thief has a bow tie / The thief has no bow tie |
| `pie_detective_suspect_mouse` .. `_squirrel` | the twelve names of 2.5 |
| `pie_detective_suspect_desc` | %1$s, with %2$s |
| `pie_detective_suspect_cleared` | %1$s is cleared |
| `pie_detective_notebook_desc` | Notebook: %1$d clues found |
| `pie_detective_notebook_yes` | %1$s: yes |
| `pie_detective_notebook_no` | %1$s: no |
| `pie_detective_score_title` | Case book report |
| `pie_detective_score_solved` | Cases solved: %1$d of %2$d |
| `pie_detective_score_stars` | Stars: %1$d |
| `pie_detective_score_lucky` | Lucky peeks used: %1$d |
| `pie_detective_score_hops` | Thief hops in the last case: %1$d |
| `pie_detective_tutorial_1` .. `_8` | the eight lines of 2.10 |

Wording rule (test P36): no string contains "winner", "loser", "you lose", "you lost", "game over" or "wrong" (the key `status_flip_wrong` is a key name only; its value is "Look again! That one still matches.").

### 2.12 The five UI states
1. **Setup** (Game Hub): band, Sidekicks stepper, Sidekick level, hints toggle, auto-flip toggle, Lucky peek row, rotate-the-lens toggle for the far seat; Play 56dp.
2. **Start**: names and ready screen (same-screen), 8-card tutorial strip (first run), then a 2-second "Case 1 of N" title card with the lineup sliding in.
3. **Mid-game**: top: the lane with the thief's shadow and the Sneak deck; middle: the lineup grid (2 x 4, 2 x 5 or 3 x 4); bottom: trait buttons (4 to 6), Catch!, Sidekick hint and the notebook strip; the lens and panel overlay while peeking; Pass screen between humans.
4. **Post-game** (per case): reveal banner `We did it!` or `Try again together`, thief shown, stars, Next case.
5. **Score card** (end of book): Case book report: cases solved, stars, Lucky peeks used, thief hops in the last case, and the one-line parent text (1.7); Play again and Finished.

---

## 3. SCALE

### 3.1 Length by mode
A case is about 4.3-4.6 turns for a random asker (about 4.0 to 4.4 for a smart one), 45-55 seconds per turn (pass, choose, peek, tell, flip, sneak). Book: Little 2 cases about 9 minutes, Medium 3 cases about 11.5, Big 3 cases about 12.5 (table 2.6). Target 8-15 minutes holds for every band and seat count (turn time grows with more seats because of more passes: add 5 seconds per seat above 2 and re-check in playtests).

### 3.2 Player counts
2 to 4 seats; a lone child has one Sidekick. Sidekicks may fill empty seats up to 4.

### 3.3 Deterministic seeds
- `bookSeed: Long` is drawn once from the game's `Random` at `startMatch()`; `caseSeed = bookSeed * 31 + caseIndex`. Order of use per case: lineup shuffle, display shuffle, thief index, Sneak deck shuffle (xor constant), Sidekick random choices (`Random(caseSeed xor 0x51DE)`). Same seed + band + seats = identical book (test P07).
- `playAgain()` draws a new bookSeed. v1: the seed is shown as a "case number" and accepted through `extras["seed"]`.

### 3.4 Performance budget
- Turn-based; no per-frame loop except short animations; the lens is a `Canvas` circle with `clipPath` (no AGSL, no bitmaps; it must work on minSdk 26).
- State: 12 suspects, 6 clues, a 12-card Sneak deck: under 2 KB per commit; engine step under 1 ms; one recomposition per action; 60 fps; no frame above 32 ms on the Fold 5 and the reference tablet.
- Art: 12 vector animals drawn with Compose paths and shared accessory paths (6 accessory layers x 12 anchors); extra memory under 10 MB.
- Snapshot JSON under 2 KB.

### 3.5 Resume and persistence
- `ResumableGame` per the house pattern. Saved at every turn boundary (state `CHOOSE`); PEEK, FLIP and CATCH in progress are not saved: resume returns to `CHOOSE` of the same seat and any not-yet-shown trait is free again.
- Save holds: band, hints, auto-flip, handicap, difficulty, bookSeed, caseIndex, lineup ids, thief id, flipped ids, asked clues (trait + answer), thief hops, Sneak position, current seat, Lucky peeks used, cases solved, stars, turns. The thief id is stored explicitly (not re-derived) so a later cast change cannot corrupt a save.
- Snapshot is null before start, after the book ends, and in any mode other than `SINGLE_PLAYER_VS_BOT` or `SINGLE_DEVICE_PASS_AND_PLAY`.
- `restore` validates and rejects, never repairs: version, roster, band and ids known, lineup size per band, lineup ids unique and in the cast, thief in lineup and NOT flipped, flipped subset of lineup, every asked clue's answer consistent with the thief, flipped set consistent with the asked clues (an animal is flipped only if some asked clue excludes it, unless it was a wrong catch), hops 0..2, Sneak index 0..11, seat in range, counts non-negative. On false the game stays untouched.

### 3.6 Multiplayer plan
- Now: same-screen only (`SINGLE_DEVICE_PASS_AND_PLAY`, `SINGLE_PLAYER_VS_BOT`). Not in `networkedGames`; the spec has no Nearby mode.
- Later (v2 Nearby): host-authoritative. The host alone knows the thief and the Sneak deck. `AskTrait(seat, trait)` goes to the host; the host replies `ClueAnswer(trait, yes)` ONLY to the asking device (a real private peek), then the asker's `ShowEveryone` makes the host broadcast `ClueShown(trait, yes)`; flips are derived by every device from `ClueShown`; `Catch(seat, suspectId)` is validated by the host. Apply the 2026-09-28 transport identity rules (derive the seat from `fromPlayerId`, never trust a claimed seat). The thief id is never sent until the case ends.
- Online relay: not planned.

### 3.7 Phased delivery
| Phase | Content | Effort |
|---|---|---|
| 0 | Engine (lineup, thief, lens state machine, flips, sneak, catch, book), cast and rules tables, sim harness, tests P01-P26 | M |
| 1 | `PieDetectiveScreen` same-screen: lane, lineup grid, trait buttons, lens, notebook, pass screen, post-game, score card; Sidekick; registration (coordinator) | L |
| 2 | Twelve vector animals and six accessory layers; tutorial strip; coach card and tips; TalkBack labels; Reduced Motion variants; EN/ES review | L |
| 3 | Resume snapshot + validation tests P27-P33, score card, parent-dashboard line | S |
| 4 | Sound + haptics (lens hum, stamp thunk, soft footsteps), device pass on Fold 5 (cover, inner, tabletop) and tablet, playtests with 3 children aged 5-7 | M |
| 5 (later) | Nearby private-peek mode | L |

### 3.8 Risk register
| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Theme or name resembles a published whodunit (stolen pie) | M | H | O1: original cast without foxes, original lane, lens and trait mechanics; name search before art; fallback name ready; art briefs in 2.5 are original |
| R2 | Press-and-hold lens fails for tremor, switch or TalkBack users | M | H | Toggle alternative from day one; announced answer; tested in the a11y audit |
| R3 | Adults blurt the answer or take over | H | M | Tell-then-show ritual, tutorial line, Lucky peek and hint options, auto-flip for Little |
| R4 | Balance drifts from the sim | M | M | Test P22 asserts bands; the Python sim is the reference |
| R5 | Case too short, feels thin | M | M | Case Book of 2-3 cases; 6 traits in Big; a fifth clue type is a v1 candidate |
| R6 | Pass-the-device on a tablet is awkward | M | M | Pass screen skipped for one human; rotate-the-lens for the far seat; same-screen layout rotates trait row |
| R7 | Resume leaks or corrupts the hidden thief | L | M | Thief stored in the save, never shown by the screen until the case ends; validation P29-P31 |
| R8 | Children flip the wrong animal repeatedly and get frustrated | M | M | Bounce-back with "Look again!", no penalty; auto-flip in Little; hint glow |
| R9 | HubLocalizationTest fails on registration (English literals in the spec) | H | L | Hub strings file owed at registration (4.3 item 9) |
| R10 | Colour-blind children cannot read Yes/No | L | H | Shape + colour + icon (circle-tick vs square-cross); test P38 |

### 3.9 Test plan (every item exists as an `@Ignore`d stub in the scaffold; ID = test name prefix in the table)
Files under `app/src/test/java/com/gamesuite/games/piedetective/`.

`PieDetectiveRulesTest`
| ID | Test name | Checks |
|---|---|---|
| P01 | `castHasTwelveSuspectsWithUniqueIdsAndNames` | cast data |
| P02 | `firstFourTraitsAreUniqueAcrossTheCast` | solvable lineups |
| P03 | `traitTotalsMatchTheDesignTable` | 6,5,5,6,6,6 |
| P04 | `bandParamsMatchTheDesignTable` | lineup, traits, dash, cases |
| P05 | `laneHasThreeHopsAndWrongCatchCostsTwo` | lane |
| P06 | `bookWinThresholdIsHalfRoundedUp` | 2->1, 3->2 |
| P07 | `handicapParseAcceptsNoneAndP1ToP4AndRejectsGarbage` | lucky list |

`PieDetectiveEngineTest`
| ID | Test name | Checks |
|---|---|---|
| P08 | `sameSeedGivesIdenticalLineupThiefAndSneakDeck` | determinism |
| P09 | `lineupSizeFollowsBandAndIsASubsetOfTheCast` | lineup |
| P10 | `thiefIsAlwaysInTheLineup` | thief |
| P11 | `askedTraitAnswerComesFromTheThiefVector` | lens rule |
| P12 | `sameTraitCannotBeAskedTwiceInACase` | once per trait |
| P13 | `traitIsOnlyMarkedAskedAfterShowEveryone` | peek cost |
| P14 | `flipOfAMatchingSuspectIsRejectedAndChangesNothing` | validation |
| P15 | `flipOfANonMatchingSuspectClearsIt` | flip |
| P16 | `autoFlipClearsExactlyTheNonMatchingSuspects` | auto-flip |
| P17 | `sneakCardIsDrawnAfterEveryAskButNotAfterACatch` | sneak timing |
| P18 | `dashMovesTheThiefOneHopAndThiefAtGateLosesTheCase` | lose |
| P19 | `correctCatchWinsTheCaseWithStarsByHops` | win |
| P20 | `wrongCatchClearsTheSuspectAndMovesTheThiefTwoHops` | wrong catch |
| P21 | `luckyPeekSkipsTheSneakOncePerCase` | handicap |
| P22 | `askerPolicyWinRatesStayInsideTargetBandsPerBand` | 3000 seeds |
| P23 | `sidekickCatchesOnlyWhenExactlyOneSuspectIsLeft` | sidekick |
| P24 | `hintPicksBestSplitAndNeverAnAskedTrait` | hint |
| P25 | `caseBookAdvancesThroughAllCasesAndEnds` | book |
| P26 | `peekLensStateMachineWalksChoosePeekShowFlipSneak` | states |

`PieDetectiveGameTest`
| ID | Test name | Checks |
|---|---|---|
| P27 | `snapshotIsNullBeforeStartAndAfterBookEnds` | null cases |
| P28 | `snapshotRoundTripRestoresIdenticalState` | round trip |
| P29 | `restoreRejectsWrongVersionAndRosterMismatch` | version, roster |
| P30 | `restoreRejectsThiefOutsideLineupOrFlipped` | hidden thief |
| P31 | `restoreRejectsClueAnswersInconsistentWithThief` | clues |
| P32 | `restoreFailureLeavesGameUntouched` | no side effects |
| P33 | `resumeAfterPeekReturnsToChooseWithTraitFree` | peek not saved |
| P34 | `leavingBeforeFirstCaseEndsUsesAbortPath` | abort |
| P35 | `resultFollowsBookWinRule` | result |
| P36 | `resultAndStatusTextUseCooperativeWordingOnly` | wording |
| P37 | `pauseStopsSidekickAutoplayAndResumeContinues` | pause |

`PieDetectiveHubSpecsTest`
| ID | Test name | Checks |
|---|---|---|
| P38 | `answerStampsUseShapeAndColourTokens` | a11y tokens |
| P39 | `specIsKidsAudienceWithMinAgeFive` | audience |
| P40 | `specIsSameScreenOnlyAndNotNetworked` | modes |
| P41 | `modeSeatBoundsAreTwoToFour` | seats |
| P42 | `optionKeysAreBandHintsAutoFlipHandicapRotateAndSidekickLevel` | extras |
| P43 | `englishAndSpanishStringFilesDefineTheSameKeys` | locales |
| P44 | `noStringValueContainsTodoMarker` | comments only |

Beyond unit tests: Compose UI tests for the five states, a TalkBack audit of the lens toggle and the 12 suspect descriptions, a device pass, and playtests with at least 3 children aged 5, 6 and 7 plus a grown-up per band before band numbers are final.

### 3.10 Simulation evidence (throwaway, outside the repo)
Python model of 2.3 (cast of 2.5, lineup 8/10/12 with 4/5/6 traits, Sneak deck of 12 with 4/5/6 dashes, lane of 3 hops, wrong Catch! = 2 hops), seeded cases, three askers:
- smart: best split, Catch! at one animal left; random: random free trait, Catch! at one left; eager: random trait, 60 percent chance to gamble a random Catch! at two left.
- Little (dash 4): win 0.98 / 0.96 / 0.83, turns 4.0 / 4.3 / 3.8.
- Medium (dash 5): 0.92 / 0.86 / 0.72, turns 4.3 / 4.6 / 4.1.
- Big (dash 6): 0.79 / 0.68 / 0.62, turns 4.4 / 4.6 / 4.2.
Without a tight lane (the first model tried a lane of 7-8 hops) the case was nearly always won, so the lane is 3 hops. Values are starting points; the table is data.

---

## 4. SCAFFOLD

### 4.1 What was created (compiling skeletons only; nothing compiled by the author)
`app/src/main/java/com/gamesuite/games/piedetective/`
- `PieDetectiveModels.kt`: `PieBand`, `PieTrait`, `PieSuspect`, `PiePhase`, `PieSeat`, `PieSuspectView`, `PieClue`, `PieEvent`, `PieState`, `PieSession`, `PieResult`, `PieHint`.
- `PieDetectiveCast.kt`: `PieCast` (the 12 suspects as data, vector strings from 2.5).
- `PieDetectiveRules.kt`: `PieRules` (constants, band table, lane, thresholds), `PieBandParams`, `PieHandicap`.
- `PieDetectiveEngine.kt`: `PieEngine` interface and `StubPieEngine` (safe values).
- `PieDetectiveSave.kt`: `PieSave` DTOs and `PieSaveCodec` (encode real, validation `TODO` in KDoc, decode rejects until implemented).
- `PieDetectiveGame.kt`: `PieDetectiveGame` (`GameModule`, `ResumableGame`) per the Bridge Builders pattern.
- `PieDetectiveHubSpecs.kt`: `PieDetectiveHubSpecs` (audience KIDS, minAge 5, NOT registered anywhere).

Resources: `res/values/pie_detective_strings.xml`, `res/values-es/pie_detective_strings.xml` (keys of 2.11; Spanish values real; `TODO translate` only in XML comments).
Tests: four `@Ignore`d classes listed in 3.9.

### 4.2 Snapshot DTO (`PieSave`)
Fields: `version`, `players`, `band`, `handicap`, `difficulty`, `hints`, `autoFlip`, `bookSeed`, `caseIndex`, `casesSolved`, `stars`, `lineup` (suspect ids), `thief` (id), `flipped` (ids), `asked` (strings `"hat:1"` / `"scarf:0"`), `hops`, `sneakIndex`, `currentSeat`, `luckyUsed` (booleans per seat), `turns`, `eventSerial`. Validation rules in 3.5 (TODO in codec KDoc).

### 4.3 Integration checklist for the coordinator (NOT done here; files owned by others)
1. `GameHubSpecs.baseSpecs()`: add `PieDetectiveHubSpecs.spec`. Do NOT add `pie-detective` to `networkedGames`.
2. `GameCatalog` glyph for `pie-detective`: pie (U+1F967).
3. `GameTheme.accents`: light 0xFF8A2F5B, dark 0xFFF2A6C8 (hand contrast: about 7.8:1 on the light surface and about 9:1 on the dark one; confirm uniqueness against existing accents in the spec test).
4. `MainActivity`: route `pie-detective` with `rememberActiveModule(sessionManager, "pie-detective") { PieDetectiveGame() }` and the new `PieDetectiveScreen`.
5. `GameTips`: three first-run tips (reuse tutorial lines 3, 5 and 6).
6. `GameCoach`: motif `CoachMotif.PATH` or the closest search motif, entry from `pie_detective_tutorial_1..5` and a fact line; do NOT add to `ownCoachInGame`.
7. `GameAudienceTable`: nothing.
8. Parent dashboard line: `pie_detective_teaches`.
9. Hub strings: the spec uses English literals; add `res/values{,-es}/pie_detective_hub_strings.xml` with `hubKey(text)` names at registration or `HubLocalizationTest` fails.
10. Enable the 44 `@Ignore`d tests one by one as the engine lands.
