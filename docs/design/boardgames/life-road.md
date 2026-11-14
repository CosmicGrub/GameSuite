# Life Road (`life-road`): Scope, Script, Scale, Scaffold

Status: design plus compiling scaffold, written 2026-11-07 on branch `session/wave-3`. **Nothing was built, compiled or run by the author** (no Gradle, no tests, no emulator). The coordinator's single gate is the first compile.

Life Road is a family life-path board game for 2 to 6 players (ages 8+, with a gentle Junior rule set for ages 6+): spin a 1 to 10 spinner, walk one road through study or work, jobs, homes, family and milestones, and retire with the most. Everything is play money ("coins"). All names, card text, art direction and the board layout are original.

How to read this file:

- **Part 1 SCOPE**: what and for whom, MVP / v1 / later, out of scope, safety, accessibility, dependencies, open questions (each with a recommended default).
- **Part 2 SCRIPT**: the complete rules as a numbered script plus every data table, the board graph, the state machine, bots, Junior, handicap, the tutorial and the exact EN strings.
- **Part 3 SCALE**: lengths, player counts, determinism, balance simulation, performance, persistence, multiplayer, phases, risks, test plan.
- **Part 4 SCAFFOLD**: the files created, how they map to this doc, and the integration list for the coordinator.

Numbers in this document that also exist in code live in `LifeRoadRules.kt` and `LifeRoadContent.kt`. Both are generated from the same tables as this file, so they cannot drift at the time of writing. When balance changes, change the tables, regenerate or hand-edit both, and keep the `LifeRoadContentTest` counts in step.

---

# PART 1: SCOPE

## 1.1 Purpose

The suite has dice races (Ludo, Slide and Climb) and strategy boards, but no game that tells a small story about a whole life. Life Road fills the "family game night, everybody can play, nobody is eliminated" slot: luck drives roughly two thirds of the outcome, choices drive the rest, and a game ends with a score card the table can read out loud. It also gives the Hub its first FAMILY board game for 6 players and its first game with a built-in handicap and catch-up row.

Success looks like: a 9 year old and a 40 year old finish within about 15 percent of each other in score most games (see the skill window in 3.4), a 6 year old can play the Junior set alone with a parent nearby, and nobody ever sits out.

## 1.2 Audience and adult plus child fairness

| Audience | What they get |
|---|---|
| Families, ages 8+ (Standard rules) | Full game: loans, cover, fresh starts, three road lengths |
| Ages 6+ (Junior rules) | No loans, no cover, no fresh starts, never lose value on a home sale, quick road, bigger targets |
| Adults with children | Hint button, handicap row, catch-up boost, no elimination |
| Adult-only game night | Long road, Hard CPUs, no handicap |

Fairness tools, all on by choice and all visible to every player (nothing is secret):

1. **Starting boost (handicap)**: per seat, none / small (1 lucky coin) / big (2 lucky coins and 50 coins). Section 2.19.
2. **Catch-up boost**: the player who is far behind spins twice and keeps the higher. Default on. Section 2.19.
3. **Hint button**: shows what the Medium CPU would choose and one plain sentence why (`lr_hint_*`). Free, unlimited. It only reveals a recommendation, never hidden information, because there is none.
4. **No elimination**: a "fresh start" (Standard rules) waives debt for a 40 point cost instead of ending your game; Junior cannot go below zero at all.
5. **Luck window**: the balance harness measures that Hard beats Easy only 58 to 70 percent head to head, and that a small boost on Easy brings that to 45 to 55 percent (3.4). Skill matters, but a child with a boost and a sensible hint can win.
6. **Reading load**: every choice has an icon and a short label; long text appears only on cards and can be read aloud by a parent. Read-aloud (TTS) for Junior is v1.

## 1.3 MVP, v1, later

| Item | MVP (first release) | v1 | Later |
|---|---|---|---|
| Rules | Standard rules complete | Junior rules | Seasons / themed boards |
| Roads | Standard length | Quick and Long | Custom lengths |
| Players | 2 to 6 on one device (people and CPUs) | Same | Nearby and Online (P8) |
| CPU | Easy and Medium | Hard, with Monte Carlo tuned numbers | Personalities |
| Content | 33 jobs, 12 homes, 64 event cards, 16 family cards, 12 milestones | More cards via data only | Seasonal card packs |
| UI states | Setup, start, mid-game, post-game, score card | Zoom and pan board with mini-map | Spectator view |
| Teaching | Tutorial (16 lines) plus hint | Junior tutorial (12 lines) | Read-aloud |
| Persistence | Resume mid-board | Session win tally | Lifetime life stats |
| Languages | EN and ES | Native-speaker ES review | More |
| Feel | Spin and hop animation, haptics, shared SFX | Per-square juice, confetti on score card | AGSL shader accents |

## 1.4 Explicitly out of scope

- **No real-money feel and no gambling.** Currency is "coins" and is never shown with a currency symbol, a real brand, a stock ticker, a casino chip, or a payment sheet. There are no purchases, no loot boxes, no ads, no timers that push people to keep playing. The lucky wheel and the market roll are party-game dice, not wagering: nothing can be staked, doubled or lost by choice.
- Life Road is not added to `KID_BLOCKED_GAME_IDS` because it contains none of the simulated-gambling themes of audit 03 section 8 (no cards for money, no betting, no chips). If the owner disagrees, the one-line fix is to add `"life-road"` there.
- No free-text chat, no user-generated content shown to others, no voice (Voice chat is the last item on the roadmap).
- No online play in the first release (section 3.7).
- No trademarked names, wording, characters, art or layouts from The Game of Life, Monopoly, Candy Land, Sorry!, Risk or any Hasbro or Mattel product. The forbidden vocabulary list (2.1) is enforced by `LifeRoadStringsTest`. No mechanics are copied beyond generic ones (spinner, path, careers, homes, pieces).
- No marriage or birth "plot" beyond the generic family cards: no weddings, no "get married", no gendered pegs. Family members include pets and grandparents.
- No death, illness beyond a sprained ankle or the flu, no lawsuits, no violence, no real-world politics, no alcohol, no tobacco.

## 1.5 Kid safety and age notes

- Ages: Standard 8+ (`minAge = 8`, `GameAudience.FAMILY`). Junior 6+ is a rule set inside the same spec (a separate spec is open question OQ3).
- All card text was written fresh, is neutral, never mocks a job, a body, a family shape or a place, and uses "you" and "your".
- Money setbacks are small (largest single card loss 35 coins, 55 on the bold Cliff Path) and are never about poverty. Debt in Standard rules is a game mechanic with a gentle exit (fresh start), and Junior removes it.
- A KID profile never sees Nearby or Online modes (`visibleFor` strips them), and the first release has none anyway.
- Player names are the only free text a person can type; they come from the shared names dialog and stay on the device in this release.
- Stats: tied co-winners record a draw, not a win for each (OQ10).

## 1.6 Accessibility

- Touch targets: 48 dp minimum everywhere, **56 dp minimum for every control in Junior**; Spin is at least 72 dp tall. Targets never sit in the screen corner where `GameChrome` already puts its 48 dp button.
- TalkBack: every square has a `contentDescription` (`lr_a11y_node_fmt` with the kind name) and `stateDescription` for pieces on it (`lr_a11y_piece_fmt`); the turn banner and the phase prompt are polite live regions; every `LifeRoadEvent` has an announcement string (`lr_ann_*`); the board has a linear "route list" alternative so a screen reader user does not have to explore a 2D canvas.
- Colour is never the only signal: six seat colours **and** six shapes (circle, square, triangle, diamond, star, plus), square kinds have icon glyphs plus text, bold squares have a hatched edge, setbacks and good news have different icons.
- Large font safe to 1.6x: the Hub, setup and score card scroll; card text wraps; no fixed-height text.
- Reduced Motion: hops become a fade, the spinner result appears without the spin, the score card has no confetti. Game speed setting scales every animation and the CPU think time.
- One-switch friendly: every decision is a short list of big buttons with a visible default; "Pick for me" exists on every fork.
- Contrast: all text and icons meet 4.5:1, non-text 3:1 on both Midnight Club surfaces (tokens in 2.20).
- Length: Quick road exists so short attention spans and short evenings are served.

## 1.7 Dependencies

| Needs | From | Status |
|---|---|---|
| `GameModule`, `ResumableGame`, `GameContext`, `PlayerInfo`, `GameResult` | `shared` module | exists |
| `GameHubSpec`, `HubOption`, `HubOptions`, `GameAudience` | `core/GameHub.kt`, `core/GameAudience.kt` | exists |
| `CpuDifficulty` | `shared` `settings/CpuDifficulty.kt` | exists |
| `GameChrome` (abort and back), `abortMatch` | shared UI | exists |
| Names dialog, ReducedMotion, GameSpeed, colour-blind mode | shared UI and settings | exists |
| SFX and haptics vocabulary | audio / haptics | exists; new coin and spinner SFX are polish |
| Coach card and tip entries | `GameCoach`, `GameTips` (shared files, integrator edits) | integrator |
| Catalog glyph, accent colour, nav route | `GameCatalog`, `GameTheme`, `MainActivity` (integrator) | integrator |
| `SavedGameStore` resume | core | exists |
| Nearby / Online (P8 only) | wave-5 transports | later |

No new third-party libraries.

## 1.8 Open questions (each with a recommended default)

| # | Question | Recommended default |
|---|---|---|
| OQ1 | Is "Life Road" clear to ship as a name (the word "Life" is common in game titles)? | Keep it, run a quick trademark search before a store listing, fallback names ready: "Long Road Home", "Roadmap of Days". |
| OQ2 | Currency name | "coins" (play money, no symbol). |
| OQ3 | Show Junior to the 4-6 age band (their top age is 6, the spec says minAge 8)? | MVP: Junior is a rule choice inside the FAMILY spec. v1: add a second spec `life-road-junior` (KIDS-friendly, minAge 6, same route and engine). |
| OQ4 | Cap at 6 players on a phone? | Yes, 6 (the task brief); the board and HUD collapse to a seat strip at 5 to 6 players. |
| OQ5 | Catch-up boost default | On. It is visible and can be switched off in setup. |
| OQ6 | Final numbers (retirement bonus, Grand Lake fee and shift, cover price 20, tuition 80) | Ship the tables here; the Monte Carlo harness (3.4) must pass before P5 closes, then adjust one lever at a time. |
| OQ7 | Is the turn cap ("last light") acceptable? | Yes, at twice the average game length so it almost never fires (target under 1 percent of games). |
| OQ8 | Read-aloud (TTS) for cards | v1, Junior first. |
| OQ9 | Piece look | Shapes plus colours, no avatars. |
| OQ10 | How to record a tied win in stats (`LocalOutcome` has DRAW) | Record DRAW for shared wins; do not repeat the known "WIN for each tied winner" defect. |
| OQ11 | Networked play | After P7 and only through the wave-5 transports with a host that runs the engine (3.7). |
| OQ12 | Spanish review | Ship the ES in the strings file now, schedule a native-speaker pass with the quick-chat review (O13). |
| OQ13 | Card art | MVP: tone icon plus text only; no illustrations. |
| OQ14 | Sound | Reuse the shared SFX bank; add spinner tick and coin clink as polish. |
| OQ15 | Achievements / lifetime stats | Later; record win and score card only for now. |
| OQ16 | Should Hint be disabled in a "serious" game? | Hint on by default, one switch in setup is not worth a row; hide it in Hard-only tables by a Settings option later. |
| OQ17 | Starting boost wording ("head start" is used by another new game's Hub strings) | Use "Starting boost" in this game so no `hub_` key is shared (done). |

---

# PART 2: SCRIPT

## 2.1 Components, vocabulary, legal rule

Components: one spinner (1 to 10), one road graph (squares), one piece per player (colour plus shape), play money in coins, five job piles (by tier), one event deck (64), one family deck (16), twelve homes, twelve milestones, three cover kinds, lucky coins (spare spins), loan blocks.

Original vocabulary used: pay stop, Start Gate, Learn Road, Work Road, Town Road, Cliff Path, Meadow Path, Family Road, Free Road, Cozy Hill, Grand Lake, job fair, home market, market day, market roll, cover shop, bank, lucky wheel, campus, milestone, memory, family member, lucky coin, fresh start, last light, starting boost, catch-up boost.

Forbidden vocabulary (never in strings, names, art, comments of shipped assets): "the game of life", "hasbro", "mattel", "monopoly", "candy land", "sorry!", "payday", "pay day", "millionaire estates", "countryside acres", "life tiles", "milton bradley", "parker brothers". "Payday" in particular is a commercial game name: this game says "pay stop" everywhere. The generic words "risk" and "life" may appear in ordinary sentences, but never as a board name; the bold route is called the Cliff Path and its flag is `bold`.

## 2.2 Setup

1. 2 to 6 seats. Each seat is a person or a CPU. A seat's piece has a colour and a shape fixed by seat number.
2. Every player begins with **100 coins** (Junior: 150), no job, no degree, no home, no family, no memory, no cover, no loans, 0 lucky coins, standing on the Start Gate `S0`.
3. Starting boost (2.19) is applied: a small boost gives that seat 1 lucky coin; a big boost gives 2 lucky coins and 50 coins. In Junior every seat also starts with 1 lucky coin.
4. Decks are shuffled from the DECK random stream: five job piles (tier 1 to 5, 6, 8, 7, 6 and 6 cards), the event deck (64), the family deck (16), the home stock (12). Unchosen cards always go to the bottom of their pile.
5. The opening seat is the Hub's choice (first mover: you, opponent or random), otherwise seat 1.
6. **Start fork**: starting with the opener and going round the table, each seat chooses **Learn Road** or **Work Road** (visible to all). Then the opener spins.

## 2.3 Turn script

On your turn, in order:

1. If you stand on a fork square (you stopped exactly on it last turn), choose a branch now.
2. **Catch-up**: if the catch-up boost is on, it is not an extra turn, and your squares walked is at least the size gap (Quick 6, Standard 12, Long 18) behind the leader among players still travelling, you spin twice and keep the higher spin (announced).
3. **Spin** the 1 to 10 spinner.
4. If you hold a lucky coin and have not used one this turn you may spend it to spin again (once per turn); the new spin must be kept.
5. **Move** your piece that many squares forward along the road. Rules while moving (2.4): pay stops pay you when passed or landed on; gate squares act when passed; you pause at a fork to choose a branch; entering a retirement square stops you.
6. **Land**: resolve the landing square (2.5). Cards you draw by landing never move you onto another square that draws a card (the chain rule, 2.12).
7. Bank, cover shop and market day decisions can be repeated until you say Done.
8. If an event gave you an extra turn, repeat from step 2 (without catch-up). Otherwise pass the spinner clockwise to the next player who has not retired.
9. When you enter a retirement square, your homes sell (2.13) and you take no more turns.
10. The game ends when every player has retired, or when last light fires (2.13).

## 2.4 Spinner, movement, forks, pay stops

- The spinner shows 1 to 10 with equal chances. Spin results are drawn from the SPIN stream (3.3).
- The piece moves one square at a time along `next` links. A fork square counts as one square. If movement has steps left when it reaches a fork, it pauses and you choose a branch (phase `AWAIT_FORK`, steps kept). If you land exactly on a fork, the choice happens at the start of your next turn (step 1 above).
- **Pay stops** pay when you pass them or land on them, once per square per move, whether or not you have a job. Landing also adds the landing bonus (10 coins).
- **Gate squares** act when passed or landed on: first job (`A`), graduation (`G`), and every pay stop. Movement pauses for the decision, then resumes with the remaining steps (`pending.stepsLeft`). All other squares act only when you land.
- **Retire squares** stop movement when entered; spare steps are wasted.
- **Bold squares** (every square of the Cliff Path): any coins you gain or lose there (pay stop salary and landing bonus, event coins, lucky wheel) count 1.5 times, rounded to the nearest 5 with halves up. Coins from cash that moves between players and bonus/penalty scoring lines are not scaled. Bold squares draw from the same decks.
- Moving back (cards) stops at the first square of the current segment and never crosses a fork or a merge.

## 2.5 Square kinds

| Code | Kind | Name key | Landing effect (EN help) |
|---|---|---|---|
| `S` | START | lr_node_start | Every traveller begins here and chooses Learn or Work. |
| `Y` | FORK | lr_node_fork | Choose which road to take. |
| `.` | REST | lr_node_rest | A quiet square. Collect 5 coins. |
| `$` | PAY | lr_node_pay | Pass or land to collect your salary. Landing adds 10. |
| `E` | EVENT | lr_node_event | Draw an event card. |
| `F` | FAMILY | lr_node_family | Draw a family card. Your family may grow. |
| `J` | JOB_FAIR | lr_node_jobfair | See two job offers. Swap jobs or keep yours. |
| `R` | RAISE | lr_node_raise | Your pay goes up by 10 for good. |
| `H` | HOUSE_MARKET | lr_node_house | Look at three homes and buy one if you like. |
| `K` | MARKET_DAY | lr_node_marketday | Sell a home now with a market roll. |
| `M` | MILESTONE | lr_node_milestone | A life milestone: take a memory. |
| `I` | COVER_SHOP | lr_node_cover | Buy cover that cancels one kind of setback. |
| `B` | BANK | lr_node_bank | Borrow 50 coins or repay a loan. |
| `L` | LUCKY_WHEEL | lr_node_wheel | Spin for coins: 10, 20, 40 or a jackpot of 80. |
| `C` | CAMPUS | lr_node_campus | Part-time work between lessons. Collect 8 coins. |
| `G` | GRADUATION | lr_node_graduation | Pay 80 for tuition, earn a degree and pick a top job. |
| `A` | FIRST_JOB | lr_node_firstjob | Pick one of two starter jobs. |
| `X` | RETIRE | lr_node_retire | Stop here. Homes sell and the totals count. |

Landing details:

- **Rest** (`.`): +5 coins.
- **Pay stop** (`$`): collect (2.7); landing adds +10.
- **Campus** (`C`, Learn Road only): +8 coins (part-time work between lessons).
- **First job** (`A`, first square of the Work Road): you must pick one of two starter offers (2.6).
- **Graduation** (`G`, last square of the Learn Road): pay tuition 80 (payment rules, 2.11; Junior free), set `degree`, then pick one of three tier 4 offers; you must pick one.
- **Job fair** (`J`): see two offers and swap, or keep your job (2.6).
- **Raise** (`R`): +10 coins on every pay from now on, at most 3 raises in total (cards that give a raise count the same).
- **Home market** (`H`): look at three homes, buy one or none (2.8).
- **Market day** (`K`): if you own a home, you may sell one now with a market roll (2.8); otherwise nothing happens.
- **Milestone** (`M`): take an unclaimed milestone (2.9).
- **Cover shop** (`I`): buy cover (2.10). Junior: a gift of 10 coins instead.
- **Bank** (`B`): borrow or repay (2.11). Junior: a gift of 10 coins instead.
- **Lucky wheel** (`L`): spin once and collect by band (table below, bold squares scale it).
- **Event** (`E`): draw an event card (2.12). **Family** (`F`): draw a family card (2.9).

| Spin | Coins |
|---|---|
| 1-3 | 10 |
| 4-7 | 20 |
| 8-9 | 40 |
| 10 | 80 |

## 2.6 Jobs

Rules:

1. A player has at most one job. No job pays only the odd-jobs allowance (10) at a pay stop.
2. Tier 1 to 3 jobs are open to everyone. **Tier 4 and 5 need a degree** (Learn Road graduation). The job tier cap is 3 without a degree and 5 with one.
3. **Offers.** First job: the top card of the tier 1 pile and the top card of the tier 2 pile (pick one, mandatory). Graduation: the top three cards of the tier 4 pile (pick one, mandatory). Job fair or the "job offer" card: let `t` be your job's tier (1 if none): the top card of pile `t` and of pile `min(t + 1, cap)` (two cards from the same pile if they are the same). You may take one or keep your job.
4. Swapping returns your old job to the bottom of its pile. Unchosen offers go to the bottom of their pile.
5. Salary is per pay stop. A raise adds 10 each (at most 3). TIPS jobs add 2 coins per spin point at every pay (spin from the SPIN stream).
6. Tier bands: tier 1 pays 20 to 26, tier 2 30 to 40, tier 3 45 to 58, tier 4 70 to 88, tier 5 90 to 115. Pay stops on a standard route: 9 to 11.

| id | Job (EN) | Tier | Pay per pay stop | Career | Pay rule | Degree |
|---|---|---|---|---|---|---|
| bike_courier | Bike Courier | 1 | 20 | Service | Fixed | no |
| bakery_helper | Bakery Helper | 1 | 22 | Service | Fixed | no |
| garden_hand | Garden Hand | 1 | 22 | Outdoor | Fixed | no |
| library_helper | Library Helper | 1 | 24 | Care | Fixed | no |
| boat_deckhand | Boat Deckhand | 1 | 24 | Outdoor | Fixed | no |
| toy_shop_clerk | Toy Shop Clerk | 1 | 26 | Create | Fixed | no |
| tram_driver | Tram Driver | 2 | 32 | Service | Fixed | no |
| care_aide | Care Aide | 2 | 32 | Care | Fixed | no |
| greenhouse_grower | Greenhouse Grower | 2 | 34 | Outdoor | Fixed | no |
| baker | Baker | 2 | 34 | Service | Fixed | no |
| carpenter | Carpenter | 2 | 36 | Build | Fixed | no |
| puppet_maker | Puppet Maker | 2 | 36 | Create | Fixed | no |
| mural_painter | Mural Painter | 2 | 30 | Create | Fixed + tips (2 x spin) | no |
| trail_ranger | Trail Ranger | 2 | 40 | Outdoor | Fixed | no |
| beekeeper | Beekeeper | 3 | 45 | Outdoor | Fixed | no |
| clock_repairer | Clock Repairer | 3 | 46 | Build | Fixed | no |
| kite_designer | Kite Designer | 3 | 48 | Create | Fixed | no |
| chef | Chef | 3 | 50 | Service | Fixed + tips (2 x spin) | no |
| ferry_pilot | Ferry Pilot | 3 | 52 | Outdoor | Fixed | no |
| electrician | Electrician | 3 | 54 | Build | Fixed | no |
| robot_mechanic | Robot Mechanic | 3 | 58 | Tech | Fixed | no |
| teacher | Teacher | 4 | 70 | Care | Fixed | yes |
| weather_forecaster | Weather Forecaster | 4 | 74 | Tech | Fixed | yes |
| clinic_nurse | Clinic Nurse | 4 | 78 | Care | Fixed | yes |
| app_maker | App Maker | 4 | 84 | Tech | Fixed | yes |
| bridge_engineer | Bridge Engineer | 4 | 86 | Build | Fixed | yes |
| architect | Architect | 4 | 88 | Build | Fixed | yes |
| concert_conductor | Concert Conductor | 5 | 90 | Create | Fixed + tips (2 x spin) | yes |
| harbor_master | Harbor Master | 5 | 98 | Outdoor | Fixed | yes |
| game_designer | Game Designer | 5 | 104 | Create | Fixed | yes |
| research_scientist | Research Scientist | 5 | 108 | Tech | Fixed | yes |
| family_doctor | Family Doctor | 5 | 112 | Care | Fixed | yes |
| space_engineer | Space Station Engineer | 5 | 115 | Tech | Fixed | yes |

Career families (6) are for icons and a later "career sets" bonus; they have no rules in the first release: `lr_career_care` = Care, `lr_career_build` = Build, `lr_career_create` = Create, `lr_career_tech` = Tech, `lr_career_outdoor` = Outdoors, `lr_career_service` = Service.

## 2.7 Pay logic (pay stops)

When a piece passes or lands on a pay stop:

1. `base` = job salary (or 10 with no job) + 10 x raises.
2. If the job is a TIPS job, add 2 x a fresh spin.
3. Apply the player's `nextPay` flag, then clear it: SKIP gives 0, HALVE gives half rounded down, DOUBLE gives twice. A newer flag replaces an older one. Cover (2.10) cancels SKIP and HALVE from a card.
4. On a bold square scale the result 1.5 times (rounded to the nearest 5, halves up).
5. If the piece **landed** on the pay stop, add the landing bonus (10; scaled on bold squares). The flag does not touch the bonus.
6. Pay it from the bank (nothing is taken from other players). Announce the amount.
7. If one move passes two pay stops, both pay; the flag is used up by the first.

## 2.8 Homes

- 12 homes, each exists once (the stock). A player owns **at most 2**.
- **Home market** (`H`): the top three homes in stock are offered; buy one or none. Price is paid using the payment rules (loans allowed in Standard, 2.11). A home cannot be offered to a player already at the cap (the square then does nothing). Unchosen homes return to the bottom.
- A purchased home leaves the stock.
- **Market roll**: spin 1 to 10; the home sells for `floor(price x multiplier)`:

| Spin | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| Standard multiplier | x0.7 | x0.8 | x0.9 | x1.0 | x1.1 | x1.2 | x1.2 | x1.3 | x1.4 | x1.6 |
| Junior multiplier | x1.0 | x1.0 | x1.0 | x1.1 | x1.1 | x1.2 | x1.2 | x1.3 | x1.4 | x1.6 |

- Mean multiplier (Standard) is 1.12: a home is a small, risky gain plus 15 coins of comfort, which is why holding homes is good but not free of risk.
- **Market day** (`K`): sell one of your homes now with a roll; it leaves your holdings (and returns to the stock bottom) and the sale price is paid at once. It loses its 15 comfort points.
- **Retirement**: every home you still hold sells with its own spin, one after another (2.13).
- **Quick sale** (forced, 2.11) sells at 80 percent of price with no spin.

| id | Home (EN) | Price | Sells for (spin 1 / 5 / 10) | Standard range |
|---|---|---|---|---|
| tiny_flat | Tiny Tower Flat | 80 | 56 / 88 / 128 | x0.7 to x1.6 |
| canal_boat | Canal Boat Home | 90 | 63 / 99 / 144 | x0.7 to x1.6 |
| garden_cottage | Garden Cottage | 120 | 84 / 132 / 192 | x0.7 to x1.6 |
| treehouse_loft | Treehouse Loft | 140 | 98 / 154 / 224 | x0.7 to x1.6 |
| brick_row | Brick Row House | 180 | 126 / 198 / 288 | x0.7 to x1.6 |
| seaside_bungalow | Seaside Bungalow | 210 | 147 / 231 / 336 | x0.7 to x1.6 |
| hill_farmhouse | Hilltop Farmhouse | 240 | 168 / 264 / 384 | x0.7 to x1.6 |
| windmill_house | Windmill House | 260 | 182 / 286 / 416 | x0.7 to x1.6 |
| lakeside_lodge | Lakeside Lodge | 320 | 224 / 352 / 512 | x0.7 to x1.6 |
| clocktower_loft | Clocktower Penthouse | 380 | 266 / 418 / 608 | x0.7 to x1.6 |
| garden_manor | Garden Manor | 440 | 308 / 484 / 704 | x0.7 to x1.6 |
| glass_dome_villa | Glass Dome Villa | 520 | 364 / 572 / 832 | x0.7 to x1.6 |

## 2.9 Family, pets and milestones

- A **family member** is a person or a pet. A player has at most **4**. Family cards with the "grows by one" effect are redrawn if you are at the cap.
- **Family square** (`F`): draw the top family card, apply it, put it at the bottom.
- Each family member is worth **30** at scoring. There is no upkeep cost.
- **Milestone square** (`M`): pick an unclaimed milestone at random for you (from the DECK stream), claim it (one memory), then spin: a spin of 6 or more also pays 10 coins. If you have claimed all 12, the square pays 10 coins.
- A **memory** is worth **20** at scoring, from milestones and from cards. At most **6** memories count.
- Home comfort: **15** per home held at retirement.

| # | id | Effects | Card text (EN) |
|---|---|---|---|
| 1 | partner | PEG | A partner moves in. Your family grows by one. |
| 2 | baby | PEG | A baby arrives. Your family grows by one. |
| 3 | adopted | PEG | You adopt a child. Your family grows by one. |
| 4 | puppy | PEG | You adopt a puppy. Your family grows by one. |
| 5 | kitten | PEG | You adopt a kitten. Your family grows by one. |
| 6 | grandparent | PEG | A grandparent moves in. Your family grows by one. |
| 7 | picnic | C+10 | A family picnic. Collect 10. |
| 8 | school_play | LUCK1 | You cheer at the school play. Take a lucky coin. |
| 9 | road_trip | S-3,MEM1 | A family road trip! Spin and pay 3 for each point. Take a memory. |
| 10 | birthday | EO5 | It is your birthday! Each other player gives you 5. |
| 11 | homework | MF2 | You help with homework. Move forward 2. |
| 12 | movie_night | C-10,MEM1 | Movie night at home. Pay 10 and take a memory. |
| 13 | garden_day | C+15 | A family garden day. Collect 15. |
| 14 | big_dinner | C-20,LUCK1 | A big family dinner. Pay 20 and take a lucky coin. |
| 15 | reunion | ALL5 | A family reunion! Every player collects 5. |
| 16 | bedtime | C+5,MEM1 | A bedtime story night. Collect 5 and take a memory. |

| id | Milestone (EN) |
|---|---|
| swim | Learn to Swim |
| garden | Grow a Garden |
| fun_run | Finish a Fun Run |
| treehouse | Build a Treehouse |
| sail | Sail a Small Boat |
| song | Write a Song |
| tree | Plant a Tree |
| far_town | Visit a Faraway Town |
| ribbon | Win a Fair Ribbon |
| help_street | Help the Whole Street |
| kite_fest | Fly at the Kite Festival |
| meteors | Watch a Meteor Shower |

## 2.10 Cover (insurance)

- Three cover kinds: **home**, **care**, **income**. Each costs **20** once and lasts all game. At a cover shop you may buy any kind you do not own and repeat until you say Done.
- A cover **cancels** the loss of event cards tagged with its kind: the coin loss, the skipped pay or the halved pay. It never cancels losses of untagged cards or lost steps, and it never pays out. The cancellation is announced (`lr_ann_cover_saved_fmt`).
- Standard rules only. In Junior the cover shop is a 10 coin gift.

## 2.11 Loans, debt and fresh starts

1. At a bank square you may **borrow 50** (you owe 60) up to **3 loans** open at once, and **repay** one loan for 60 at any bank square. Both are repeatable until Done.
2. **Payment rules** (every time you must pay coins: tuition, a home, an event, a cover, the Grand Lake fee, your share when you "treat friends"):
   1. Pay from cash.
   2. If short, borrow the fewest 50-coin blocks needed, up to the loan limit (each adds 60 owed).
   3. If still short, **quick sale**: sell your most expensive home at 80 percent of its price (no spin), then repeat from the top.
   4. If still short, take a **fresh start**: pay what cash you have, clear all loans, your freshStarts goes up by one (40 point penalty at scoring), and the bank gives you a 20 coin helping hand.
3. Payments to other players ("each other player gives you", "pay each other player") never force the **receiver's** debt: a giver pays only from cash and any shortfall is waived. The player paying "each other player" uses the full payment rules.
4. Unpaid loans cost 60 each at scoring. There is no other interest.
5. **Junior**: no loans, no quick sale, no fresh start. A payment takes what the player has (cash never goes below 0).

## 2.12 Events

- **Event square** (`E`): draw the top event card, show it to everyone, apply its effects in listed order, put it at the bottom. When the deck is exhausted it is reshuffled (DECK stream), though a game almost never gets through 64 draws.
- **Effects glossary** (`C+n` coins, `S+n` spin x n coins, `EOn` each other pays you, `PEn` you pay each other, `ALLn` everyone collects, `MFn` `MBn` move, `SKIP` `HALF` `DBL` next pay, `MEMn`, `LUCKn`, `EXTRA`, `RAISE`, `JOBFAIR`, `PEG`):
  - COINS: gain or pay (payment rules for losses). SPIN_COINS: spin and gain or pay that many times n (S+5 with a 7 gives 35).
  - EACH_OTHER_PAYS: every other player still travelling pays you n from cash only. PAY_EACH_OTHER: you pay n to each other player still travelling.
  - ALL_COLLECT: every player still travelling collects n.
  - MOVE_FORWARD: walk n squares (pay stops on the way pay; a fork pauses for a choice); **the chain rule**: the square you reach does not resolve (no second card, no purchase), except that a retirement square still retires you.
  - MOVE_BACK: walk back n squares, stopping at the start of the current segment.
  - SKIP / HALVE / DOUBLE: set `nextPay`.
  - MEMORY: +n memories. LUCKY_COIN: +n lucky coins (at most 9 held). EXTRA_TURN: take another turn after this one, without catch-up. RAISE: +10 per pay (at most 3 raises). JOB_FAIR: as a job fair. FAMILY_PEG: +1 family member (not above 4).
- **Cover** (2.10) cancels the loss of tagged cards.
- Junior softens setbacks (2.18).
- Deck facts: 64 cards: 16 windfalls, 16 setbacks (4 home, 4 care, 3 income, 5 untagged), 12 fun and movement, 12 community and work, 8 weather (one of them a home-tagged flood). Across the deck 5 cards are home-tagged, 4 care-tagged and 3 income-tagged. The mean coin value of a draw is about +0.7 and the biggest single swing is 50 (the raffle at a spin of 10).

| # | id | Tone | Cover | Effects | Card text (EN) |
|---|---|---|---|---|---|
| 1 | coin_jar | good | - | C+20 | You find a jar of old coins under the porch. Collect 20. |
| 2 | bake_sale | good | - | C+25 | Your bake sale sells out. Collect 25. |
| 3 | raffle | good | - | S+5 | Your raffle ticket wins. Spin and collect 5 for each point. |
| 4 | big_tip | good | - | C+15 | A happy customer leaves a big tip. Collect 15. |
| 5 | fair_ribbon | good | - | C+30 | Your tomatoes win a ribbon at the fair. Collect 30. |
| 6 | old_bike | good | - | C+20 | You sell your old bike. Collect 20. |
| 7 | shop_refund | good | - | C+10 | The shop refunds a mistake. Collect 10. |
| 8 | busker | good | - | S+3 | You play music in the square. Spin and collect 3 for each point. |
| 9 | boss_notice | good | - | C+35 | Your boss loves your work. Collect 35. |
| 10 | busy_season | good | - | DBL | Busy season! Your next pay is doubled. |
| 11 | shop_voucher | good | - | C+25 | A friend gives you a shop voucher. Collect 25. |
| 12 | scrap_art | good | - | C+20 | Your recycled art sells at the market. Collect 20. |
| 13 | swap_meet | good | - | C+15 | You swap a few things and come out ahead. Collect 15. |
| 14 | cookbook | good | - | C+30 | Your recipe is printed in a book. Collect 30. |
| 15 | small_rebate | good | - | C+20 | A small rebate arrives in the mail. Collect 20. |
| 16 | lucky_penny | good | - | LUCK1 | You find a lucky penny. Take a lucky coin. |
| 17 | leaky_roof | setback | home | C-30 | Your roof leaks in the rain. Pay 30. |
| 18 | storm_fence | setback | home | C-25 | A storm knocks down the fence. Pay 25. |
| 19 | broken_pipe | setback | home | C-35 | A pipe bursts at home. Pay 35. |
| 20 | jammed_door | setback | home | C-15 | The shed door jams and needs a repair. Pay 15. |
| 21 | sprained_ankle | setback | care | C-20 | You sprain your ankle. Pay 20. |
| 22 | flu_season | setback | care | C-25 | You catch the flu and see the doctor. Pay 25. |
| 23 | sore_tooth | setback | care | C-30 | A sore tooth needs fixing. Pay 30. |
| 24 | new_glasses | setback | care | C-20 | You need new glasses. Pay 20. |
| 25 | quiet_season | setback | income | HALF | A quiet season: your next pay is halved. |
| 26 | workshop_closed | setback | income | SKIP | The workshop closes for repairs. Skip your next pay. |
| 27 | cancelled_orders | setback | income | C-20 | Customers cancel some orders. Pay 20. |
| 28 | lost_wallet | setback | - | C-15 | You lose your wallet. Pay 15. |
| 29 | parking_fine | setback | - | C-10 | A parking fine arrives. Pay 10. |
| 30 | dropped_phone | setback | - | C-20 | You drop your phone. Pay 20. |
| 31 | bad_gadget | setback | - | C-25 | You buy a gadget that never works. Pay 25. |
| 32 | flat_tire | setback | - | C-15,MB1 | A flat tire! Pay 15 and move back 1. |
| 33 | tailwind | fun | - | MF3 | A friendly tailwind pushes you forward 3. |
| 34 | shortcut | fun | - | MF2 | You find a shortcut. Move forward 2. |
| 35 | scenic_view | fun | - | MB2,MEM1 | You stop for the view. Move back 2 and take a memory. |
| 36 | extra_spin | fun | - | EXTRA | Lucky day! Take another turn right away. |
| 37 | park_picnic | fun | - | C+10,MF1 | A park picnic with friends. Collect 10 and move forward 1. |
| 38 | parade | fun | - | EO5 | A parade passes and everyone cheers. Each other player gives you 5. |
| 39 | street_dance | fun | - | S+2 | A street dance! Spin and collect 2 for each point. |
| 40 | stargazing | fun | - | MEM1 | You watch the stars together. Take a memory. |
| 41 | museum_day | fun | - | MEM1,C-5 | A museum day teaches you something new. Take a memory and pay 5. |
| 42 | swim_day | fun | - | MF1,C+5 | You swim in the lake. Move forward 1 and collect 5. |
| 43 | fireworks | fun | - | LUCK1 | Fireworks night! Take a lucky coin. |
| 44 | busy_bridge | fun | - | MB1 | Traffic on the bridge. Move back 1. |
| 45 | charity_run | fun | - | C-10,MEM1 | A charity run! Pay 10 and take a memory. |
| 46 | neighbour_help | good | - | C+15 | You help a neighbour move house. Collect 15. |
| 47 | treat_friends | fun | - | PE5 | You treat your friends to dinner. Pay 5 to each other player. |
| 48 | raise_chance | good | - | RAISE | Your hard work pays off. Take a raise of 10 per pay stop. |
| 49 | skills_class | good | - | C-15,RAISE | A skills class pays off later. Pay 15 and take a raise. |
| 50 | job_offer | good | - | JOBFAIR | A new job offer arrives. You may swap jobs. |
| 51 | helping_hand | good | - | C+10 | You help a stranger, and they help you back. Collect 10. |
| 52 | library_fine | setback | - | C-5 | A very overdue library book! Pay 5. |
| 53 | mentor | good | - | MF2,C+10 | A mentor guides you. Move forward 2 and collect 10. |
| 54 | town_cup | good | - | C+20 | Your team wins the town cup. Collect 20. |
| 55 | bridge_toll | setback | - | C-10 | A bridge toll. Pay 10. |
| 56 | attic_sale | good | - | C+25 | You tidy the attic and sell what you find. Collect 25. |
| 57 | sunny_day | good | - | ALL5 | A sunny day lifts everyone. Every player collects 5. |
| 58 | rainbow | fun | - | MEM1 | A rainbow stretches over the hills. Take a memory. |
| 59 | snow_day | fun | - | MB2,C+10 | Snow day! Move back 2 and collect 10 selling hot cocoa. |
| 60 | heatwave | setback | - | C-10 | A heatwave: you buy cold drinks. Pay 10. |
| 61 | strong_gust | fun | - | MF4 | A strong gust pushes you forward 4. |
| 62 | thick_fog | setback | - | MB2 | Thick fog slows you down. Move back 2. |
| 63 | cellar_flood | setback | home | C-30 | A flood warning: your cellar floods. Pay 30. |
| 64 | harvest_moon | good | - | LUCK1,C+10 | A harvest moon brings luck. Take a lucky coin and collect 10. |

## 2.13 Retirement and last light

1. At the last fork `F4` choose a lane: **Cozy Hill** (free; at retirement take 1 memory) or **Grand Lake** (needs at least one home and costs **40**; at retirement every home's market roll gets **+2**, capped at 10).
2. Entering `RC` (Cozy) or `RG` (Grand) retires you. You get the next retirement order number: 1st, 2nd, 3rd... The retirement bonus is paid at scoring (below).
3. **Settling**: each home you hold is sold with its own spin, one at a time (the player taps Spin for each, CPUs auto), at the standard or Junior table, plus the Grand shift. The sale price is added to the score card line "Homes sold" and the home is marked sold.
4. After settling you are retired: you take no more turns, you are skipped, you are not charged by "each other pays" cards, and you do not collect "all collect" cards.
5. **Last light**: when every player still travelling has taken the size's turn cap (Quick 14, Standard 26, Long 38 turns), every one of them retires on the spot with lane LATE: no retirement bonus, no Cozy memory, homes sold with automatic standard rolls. The game then ends.
6. When the last player has retired (or last light has fired), the game ends and the score card opens.

| Road | Turn cap per player | Catch-up gap (squares) | Approx minutes |
|---|---|---|---|
| quick | 14 | 6 | 15 |
| standard | 26 | 12 | 40 |
| long | 38 | 18 | 60 |

## 2.14 Scoring and tie-breaks

Score = Coins + Homes sold + Retirement bonus + Memories x 20 + Family members x 30 + Home comfort (15 per home held at retirement) - Loans x 60 - Fresh starts x 40.

| Arrival order | 1st | 2nd | 3rd | 4th | 5th | 6th |
|---|---|---|---|---|---|---|
| Retirement bonus | 80 | 50 | 30 | 20 | 10 | 10 |

- Late retirees (last light) get a bonus of 0.
- **Life points** = memories x 20 + family members x 30.
- The score card shows each line with its amount and the lines sum exactly to the total (`lr_sc_*`); there is no hidden term.
- Tie-break order: 1) higher total; 2) more life points; 3) fewer loans plus fresh starts; 4) earlier retirement (a late retiree loses to anyone who retired); 5) still tied: a shared win. Co-winners all get `isWinner = true` in `GameResult`, and stats record a draw (OQ10).
- Place numbers use competition ranking (1, 1, 3).

## 2.15 Turn state machine

Phases (`Phase` enum). **MOVING and TURN_END are transient**: they exist inside one `apply` call and are never saved or shown as a pending question.

| Phase | Who acts | Legal actions | Next |
|---|---|---|---|
| START_FORK | each seat in order from the opener | ChooseBranch(LRN.0 or WRK.0) | next seat, then AWAIT_SPIN for the opener |
| AWAIT_FORK | seat on turn | ChooseBranch(either exit) | MOVING if steps left, else AWAIT_SPIN |
| AWAIT_SPIN | seat on turn | Spin | AWAIT_RESPIN if a lucky coin is held and unused, else MOVING |
| AWAIT_RESPIN | seat on turn | UseLuckyCoin, KeepSpin | MOVING |
| MOVING | engine | none (internal) | AWAIT_FORK, AWAIT_JOB (gate), landing phase, or SETTLING (retire) |
| AWAIT_JOB | seat on turn | PickJob(id) or PickJob(null) when not mandatory | resume MOVING if steps left, else TURN_END |
| AWAIT_HOUSE | seat on turn | BuyHouse(id), DeclineHouse | TURN_END |
| AWAIT_COVER | seat on turn | BuyCover(kind), DeclineCover (stay until none left or declined) | TURN_END |
| AWAIT_BANK | seat on turn | TakeLoan, RepayLoan, Done | TURN_END |
| AWAIT_MARKET | seat on turn | SellHome(id), DeclineSale | TURN_END |
| AWAIT_CARD | seat on turn | Acknowledge, or Spin when `needsSpin` | effects applied, then TURN_END, AWAIT_JOB, AWAIT_FORK, MOVING or AWAIT_SPIN (extra turn) |
| SETTLING | seat that retired | Spin (once per home) | next seat's turn |
| TURN_END | engine | none (internal) | AWAIT_FORK / AWAIT_SPIN of the next seat, or GAME_OVER |
| GAME_OVER | nobody | none | terminal |

Invariants (checked by `LifeRoadFuzzTest`): cash is at least 0 after every action; loans are 0 to 3 (0 in Junior); homes 0 to 2; family 0 to 4; memories counted to 6; raises 0 to 3; lucky coins 0 to 9; the seat on turn is never retired; `legalActions` is never empty before GAME_OVER (the table can never stall); `explain` and `legalActions` always agree.

Which squares ask a question (and so create a phase): `A`, `G`, `J`, `H`, `K` (only with a home), `I`, `B`, `E`, `F`, `M`, `L`, and forks. `.`, `C`, `R`, `$` resolve at once.

## 2.16 The board as a path graph

Squares come from segments (patterns of kind characters) joined by forks. `S0` is both the Start Gate and the first fork (Learn or Work). `F2` `F3` `F4` are single fork squares. `RC` `RG` are the retirement squares. A node id is `<segment>.<index>` (0-based) or one of `S0 F2 F3 F4 RC RG`. The route is always forward (a DAG) with 16 possible routes (four forks, two choices each).

Kind legend (pattern character):

| Code | Kind | Name key | Landing effect (EN help) |
|---|---|---|---|
| `S` | START | lr_node_start | Every traveller begins here and chooses Learn or Work. |
| `Y` | FORK | lr_node_fork | Choose which road to take. |
| `.` | REST | lr_node_rest | A quiet square. Collect 5 coins. |
| `$` | PAY | lr_node_pay | Pass or land to collect your salary. Landing adds 10. |
| `E` | EVENT | lr_node_event | Draw an event card. |
| `F` | FAMILY | lr_node_family | Draw a family card. Your family may grow. |
| `J` | JOB_FAIR | lr_node_jobfair | See two job offers. Swap jobs or keep yours. |
| `R` | RAISE | lr_node_raise | Your pay goes up by 10 for good. |
| `H` | HOUSE_MARKET | lr_node_house | Look at three homes and buy one if you like. |
| `K` | MARKET_DAY | lr_node_marketday | Sell a home now with a market roll. |
| `M` | MILESTONE | lr_node_milestone | A life milestone: take a memory. |
| `I` | COVER_SHOP | lr_node_cover | Buy cover that cancels one kind of setback. |
| `B` | BANK | lr_node_bank | Borrow 50 coins or repay a loan. |
| `L` | LUCKY_WHEEL | lr_node_wheel | Spin for coins: 10, 20, 40 or a jackpot of 80. |
| `C` | CAMPUS | lr_node_campus | Part-time work between lessons. Collect 8 coins. |
| `G` | GRADUATION | lr_node_graduation | Pay 80 for tuition, earn a degree and pick a top job. |
| `A` | FIRST_JOB | lr_node_firstjob | Pick one of two starter jobs. |
| `X` | RETIRE | lr_node_retire | Stop here. Homes sell and the totals count. |

Segment patterns by road size:

| Segment | Meaning | Bold | Quick (Q) | Standard (S) | Long (L) |
|---|---|---|---|---|---|
| LRN | Learn Road (college) | no | `C.ELG` | `C.E.CL.G` | `C.E.CL.E.G` |
| WRK | Work Road (career start) | no | `A.$R` | `A.$ER.` | `A.$E.R$.` |
| TA | Town Road 1 | no | `$.EJ.I` | `$.E.JI.M$.EB` | `$.E.JI.M$.E.B.L$.EM.` |
| RISK | Cliff Path (bold) | yes | `L$EL` | `L$E.L$E` | `L$E.L$E.L$` |
| SAFE | Meadow Path | no | `.E$.M` | `.E.$.F.M.` | `.E.$.F.M.E.$.F` |
| TB | Town Road 2 | no | `$.HE.K` | `$.H.E.F$.K.M` | `$.H.E.F$.K.M$.E.I.EL` |
| FAM | Family Road | no | `F.F$E` | `F.F$E.FM` | `F.F$E.FMF.$E` |
| FREE | Free Road | no | `L.M$K` | `L.M$K.LM` | `L.M$K.LM.$EM` |
| TC | Town Road 3 | no | `$.E.IB` | `$.E.I.J$.B.E` | `$.E.I.J$.B.E$.M.E.LK` |
| COZY | Cozy Hill lane | no | `M.$` | `E.M.$` | `E.M.E.M$` |
| GRAND | Grand Lake lane | no | `KL$` | `K.L$M` | `K.L$.EM$` |

Graph facts (verified by the generator that wrote this file):

| Size | Squares in the graph | Squares on one route (min / max / avg) | Pay stops on a route (min / max / avg) |
|---|---|---|---|
| Quick | 58 | 39 / 41 / 40 | 6 / 7 / 6.5 |
| Standard | 98 | 67 / 71 / 69 | 9 / 11 / 10.0 |
| Long | 148 | 103 / 109 / 106 | 14 / 18 / 16.0 |

Edges: `S0` goes to `LRN.0` and `WRK.0`; `LRN.end` and `WRK.end` go to `TA.0`; `TA.end` goes to `F2`; `F2` goes to `RISK.0` (Cliff Path) and `SAFE.0` (Meadow Path); `RISK.end` and `SAFE.end` go to `TB.0`; `TB.end` goes to `F3`; `F3` goes to `FAM.0` and `FREE.0`; `FAM.end` and `FREE.end` go to `TC.0`; `TC.end` goes to `F4`; `F4` goes to `COZY.0` and `GRAND.0`; `COZY.end` goes to `RC`; `GRAND.end` goes to `RG`.

Build algorithm (`LifeRoadBoard.build(size)`): for each segment, create one node per character of the pattern for that size column (`Q`/`S`/`L`) with id `<segment>.<index>`, kind from the legend, `bold = segment is RISK`, and link index i to i+1; add the six single nodes (`S0` kind START, `F2 F3 F4` kind FORK, `RC RG` kind RETIRE); then add every edge, resolving `X:0` to the first and `X:end` to the last node of segment X. Reject any graph where a non-retire node has no exit, a fork does not have exactly two exits, a node is unreachable from `S0`, or a cycle exists.

Machine-readable spec (the same data as `LifeRoadBoardSpec` in code):

```json
{
 "version": 1,
 "start": "S0",
 "retire": [
  "RC",
  "RG"
 ],
 "sizes": [
  "Q",
  "S",
  "L"
 ],
 "segments": [
  {
   "id": "LRN",
   "bold": false,
   "pattern": {
    "Q": "C.ELG",
    "S": "C.E.CL.G",
    "L": "C.E.CL.E.G"
   }
  },
  {
   "id": "WRK",
   "bold": false,
   "pattern": {
    "Q": "A.$R",
    "S": "A.$ER.",
    "L": "A.$E.R$."
   }
  },
  {
   "id": "TA",
   "bold": false,
   "pattern": {
    "Q": "$.EJ.I",
    "S": "$.E.JI.M$.EB",
    "L": "$.E.JI.M$.E.B.L$.EM."
   }
  },
  {
   "id": "RISK",
   "bold": true,
   "pattern": {
    "Q": "L$EL",
    "S": "L$E.L$E",
    "L": "L$E.L$E.L$"
   }
  },
  {
   "id": "SAFE",
   "bold": false,
   "pattern": {
    "Q": ".E$.M",
    "S": ".E.$.F.M.",
    "L": ".E.$.F.M.E.$.F"
   }
  },
  {
   "id": "TB",
   "bold": false,
   "pattern": {
    "Q": "$.HE.K",
    "S": "$.H.E.F$.K.M",
    "L": "$.H.E.F$.K.M$.E.I.EL"
   }
  },
  {
   "id": "FAM",
   "bold": false,
   "pattern": {
    "Q": "F.F$E",
    "S": "F.F$E.FM",
    "L": "F.F$E.FMF.$E"
   }
  },
  {
   "id": "FREE",
   "bold": false,
   "pattern": {
    "Q": "L.M$K",
    "S": "L.M$K.LM",
    "L": "L.M$K.LM.$EM"
   }
  },
  {
   "id": "TC",
   "bold": false,
   "pattern": {
    "Q": "$.E.IB",
    "S": "$.E.I.J$.B.E",
    "L": "$.E.I.J$.B.E$.M.E.LK"
   }
  },
  {
   "id": "COZY",
   "bold": false,
   "pattern": {
    "Q": "M.$",
    "S": "E.M.$",
    "L": "E.M.E.M$"
   }
  },
  {
   "id": "GRAND",
   "bold": false,
   "pattern": {
    "Q": "KL$",
    "S": "K.L$M",
    "L": "K.L$.EM$"
   }
  }
 ],
 "singles": {
  "S0": "START (also fork F1)",
  "F2": "FORK",
  "F3": "FORK",
  "F4": "FORK (retirement lanes)",
  "RC": "RETIRE cozy",
  "RG": "RETIRE grand"
 },
 "edges": [
  [
   "S0",
   "LRN:0"
  ],
  [
   "S0",
   "WRK:0"
  ],
  [
   "LRN:end",
   "TA:0"
  ],
  [
   "WRK:end",
   "TA:0"
  ],
  [
   "TA:end",
   "F2"
  ],
  [
   "F2",
   "RISK:0"
  ],
  [
   "F2",
   "SAFE:0"
  ],
  [
   "RISK:end",
   "TB:0"
  ],
  [
   "SAFE:end",
   "TB:0"
  ],
  [
   "TB:end",
   "F3"
  ],
  [
   "F3",
   "FAM:0"
  ],
  [
   "F3",
   "FREE:0"
  ],
  [
   "FAM:end",
   "TC:0"
  ],
  [
   "FREE:end",
   "TC:0"
  ],
  [
   "TC:end",
   "F4"
  ],
  [
   "F4",
   "COZY:0"
  ],
  [
   "F4",
   "GRAND:0"
  ],
  [
   "COZY:end",
   "RC"
  ],
  [
   "GRAND:end",
   "RG"
  ]
 ],
 "forks": [
  {
   "at": "S0",
   "a": "LRN",
   "b": "WRK",
   "labelKeys": [
    "lr_fork_learn",
    "lr_fork_work"
   ]
  },
  {
   "at": "F2",
   "a": "RISK",
   "b": "SAFE",
   "labelKeys": [
    "lr_fork_cliff",
    "lr_fork_meadow"
   ]
  },
  {
   "at": "F3",
   "a": "FAM",
   "b": "FREE",
   "labelKeys": [
    "lr_fork_family",
    "lr_fork_free"
   ]
  },
  {
   "at": "F4",
   "a": "COZY",
   "b": "GRAND",
   "labelKeys": [
    "lr_lane_cozy",
    "lr_lane_grand"
   ]
  }
 ]
}
```

Rendering note: nodes are laid out along one winding path of fixed anchor points per size (a layout table is a P3 task); forks draw as two lanes that rejoin; the Cliff Path is drawn with a hatched edge. The layout is original: a river-bend road through a village, hills and a lake, not any commercial board's silhouette.

## 2.17 CPU behaviour

All CPUs follow the same rules and see the same public information. Decisions are made through `LifeRoadEngine.botAction`, which must always return a legal action. A CPU waits `BOT_THINK_MIN_MS..BOT_THINK_MAX_MS` (600 to 1200 ms, scaled by Game Speed) before each action so turns look natural, and never blocks the UI thread. Randomness for CPU choices uses the BOT stream only, so the spin sequence never depends on how CPUs think.

| Decision | Easy (human-feeling, beatable) | Medium (sensible) | Hard (strong) |
|---|---|---|---|
| Start fork | 50% Learn | Learn if estimated turns left at least 9 (Standard and Long), Work on Quick | Compare the route value table (expected coins of each route, built once per game from the content tables) with a 0.15 x spread penalty when leading, bonus when trailing |
| Cliff or Meadow | 50 / 50 | Cliff only when below the table's median score, else Meadow | Route value, risk posture from rank (trailing takes bold) |
| Family or Free road | 50 / 50 | Family while fewer than 2 family members, else Free | Route value |
| Lane (F4) | Cozy, unless a home and 100 coins and a 30% coin flip | Grand if at least one home and cash at least 60 | Grand if home value x 0.17 (the +2 shift) exceeds 40, or trailing with a home |
| Lucky coin | 25% when the spin is 3 or less | Use when the spin is 3 or less | Use when the value of the landing is below the average landing value by more than 5 coins |
| Job offers | random | highest salary (tips count 11); at a fair swap if salary at least +10 | highest salary over remaining pay stops, accounts for degree cap |
| Home | buys with 50% when cash covers it, random home | buys the cheapest home of at least 100 if cash after at least 40 (second home: cash after at least 100); never borrows except the first home when short by at most 50 | buys while owning fewer than 2 if cash after at least 20; borrows at most one loan for a home |
| Cover | 30% to buy the first affordable | home with a home, care with a family member, income at tier 3 or above; only when cash after at least 40 | buys only when expected avoided loss at least the price (rarely) |
| Bank | repays at 160 coins with 50%; borrows only when forced | repays at cash at least 160; borrows only for a first home | repays when cash exceeds the highest likely bill plus 60; no borrowing unless it buys a home |
| Market day | never sells | holds | sells when leading by at least 150 to lock the lead, or trailing never |
| Settling | automatic | automatic | automatic |

Hard budget: no rollouts on the UI thread; route values are precomputed; at most 8 short rollouts for the lucky coin and market day decisions; total 25 ms at the 95th percentile (checked by `hardDecisionFitsTheTimeBudget`). Easy never uses information beyond its own panel. In Junior every CPU uses the Easy script plus: never takes the Cliff Path and never uses a lucky coin.

## 2.18 Junior variant (ages 6+)

Same board, same spinner, same decks, with these changes:

1. Start with 150 coins and 1 lucky coin. Default road length Quick (Hub choice `applies`).
2. Tuition is free ("scholarship").
3. No loans, no cover, no quick sale, no fresh start, no negative cash. A payment takes what the player has. The bank and cover shop squares are 10 coin gifts.
4. Setback cards soften: coin losses are halved (rounded up to a multiple of 5, at least 5); SKIP and HALVE become "pay 10". Movement-back cards are unchanged.
5. The market roll never loses value: multiplier table floor is 1.0 (second row of the table in 2.8).
6. The Grand Lake lane is free (still needs a home).
7. The score card has no negative lines.
8. All controls are at least 56 dp, body text at least 18 sp, buttons at least 22 sp; confirmation dialogs are replaced by a single large Undo-free choice with a visible default; "Pick for me" on every fork.
9. CPUs use the Easy script described above.
10. Tutorial: lines 1, 2, 3, 4, 5, 6, 7, 10, 11, 12, 14, 16 (skips the loan, cover and market-roll steps, which Junior does not have in the same form).

## 2.19 Starting boost (handicap) and catch-up

- **Starting boost** (setup row "Starting boost" and "Starting boost for"): None, **Small** (+1 lucky coin) or **Big** (+2 lucky coins and +50 coins). "Starting boost for" picks `all` or one seat (1 to 6). Same keys in the Hub: `handicap` and `handicapFor`. A lucky coin lets a player replace a poor spin once (2.3 step 4).
- **Catch-up boost** (toggle, default on): described in 2.3 step 2. The gap is the size gap (Quick 6, Standard 12, Long 18 squares walked). It never applies on an extra turn and never to the leader.
- Both are announced and visible to all players. Neither changes any score formula.

## 2.20 The five UI states

Theme: dark "Midnight Club". Tokens: ink 900 `#0B0D1A` (background), ink 800 `#141829` (surface), ink 700 `#1E2340` (raised), ivory `#F4EFE3` (text), ivory dim `#B9B4A6`, **saffron `#FFB547` (primary)**, ember `#FF7A59` (warm accent: bold route, setbacks), meadow `#6BCB9A` (good news), lake `#6FB6FF` (family, info). Seat colours (with shapes): saffron circle, ember square, meadow triangle, lake diamond, lilac `#C7A6FF` star, rose `#FF8FB1` plus. Hub identity accent (light / dark): `#971A0C` / `#DE867C` (light contrast vs FFFBFE / E6E0E9 = 8.3 / 6.56; dark contrast vs 1C1B1F / 36343B = 6.37 / 4.56; nearest existing accent is 26.0 RGB units away).

1. **Setup** (`lr_setup_*`): scrolling page. Player rows (name field, person or CPU toggle), Road length (3 chips), Rules (2 chips), Starting boost (3 chips) and "for" (chips, only when a boost is chosen), Catch-up boost (toggle), CPU level (shown when a CPU is seated), primary button "Start the road" (saffron, pinned bottom, 56 dp). The Hub already offers most of these; this page is the Pass & Play ready screen with names.
2. **Start** (`lr_start_*`): "Choose your first road" with two large cards (Learn Road, Work Road), each with icon, one sentence and "Pick for me"; seats choose in order, the previous choices shown as small chips. The same card layout is reused for every later fork.
3. **Mid-game**: top strip of seat chips (shape, colour, coins, job), board canvas in the middle (zoom to the active piece, pinch to look around, a linear route list under TalkBack), bottom panel with the phase prompt (live region), the spinner and one row of big buttons for the open question, and a collapsible "my panel" (job, homes, family, memories, loans, cover, lucky coins). The event card slides over the board; Hint, Menu (GameChrome) and Undo-free rule hint always visible.
4. **Post-game** (`lr_post_*`): "Everyone has retired", winner line (or the tie line), a podium row, "Play again", "Back to menu", "Score card".
5. **Score card** (`lr_sc_*`): one column per player, rows: coins, homes sold, retirement bonus, memories, family, home comfort, loans owed, fresh starts, total, place. "Show the math" expands the lines with icons. Large-font and landscape layouts scroll horizontally per player.

## 2.21 Tutorial script

Sixteen steps, each one line under 120 characters, each pinned to a trigger and a highlight, each skippable, each shown once (a seen-flag per step) and replayable from the help sheet. Junior shows only the subset listed in 2.18.

| # | Step | Trigger | Highlights | String key | Line (EN) |
|---|---|---|---|---|---|
| 1 | welcome | first launch | board | lr_tut_01 | Welcome to Life Road. Travel one road from start to retirement. |
| 2 | spin | first AWAIT_SPIN | spin button | lr_tut_02 | Tap Spin. The spinner shows 1 to 10, and your piece walks that far. |
| 3 | fork | first fork shown | fork cards | lr_tut_03 | At a fork, choose your road. Learn costs coins now and pays more later. |
| 4 | pay | first pay stop passed | pay stop node | lr_tut_04 | Pay stops pay your salary when you pass or land on them. |
| 5 | job | first job offer | offer cards | lr_tut_05 | A job sets your pay. Higher tiers need a degree. |
| 6 | house | first home market | home cards | lr_tut_06 | Homes cost coins and sell later with a market roll. Owning one adds comfort. |
| 7 | family | first family card | family badge | lr_tut_07 | Family squares can grow your household. Each family member is worth 30 coins. |
| 8 | event | first event card | event card | lr_tut_08 | Event cards bring luck or small setbacks. Cover shops can protect you. |
| 9 | bank | first bank or short of coins | bank buttons | lr_tut_09 | Short of coins? Borrow 50 at the bank, but repay 60. A fresh start costs 40. |
| 10 | memory | first milestone | memory badge | lr_tut_10 | Memories come from milestones and are worth 20 coins each. |
| 11 | lucky | first lucky coin held at a spin | lucky coin button | lr_tut_11 | A lucky coin lets you spin again once. Use it when the spin is poor. |
| 12 | retire | first retirement fork | lane cards | lr_tut_12 | Reach a retirement square to stop. Earlier retirees get a bonus. |
| 13 | settle | first market roll at retirement | market roll | lr_tut_13 | At retirement your homes sell with a market roll. Then the totals count. |
| 14 | win | first score card | score card | lr_tut_14 | Most coins, homes and memories wins. Ties go to the fuller life. |
| 15 | cpu | first CPU turn | turn banner | lr_tut_15 | CPU players follow the same rules. Everyone sees every card. |
| 16 | ready | end of tutorial | none | lr_tut_16 | You are ready. Spin and enjoy the road! |

## 2.22 Strings the UI needs (exact English, with keys)

401 keys in total, in `res/values/life_road_strings.xml`, with Spanish in `res/values-es/life_road_strings.xml`. Keys are listed here by group.

**UI strings**

| Key | English |
|---|---|
| lr_title | Life Road |
| lr_help_title | Life Road |
| lr_help_text | Spin the 1 to 10 spinner and walk one road of life: study or work, buy homes, grow a family, collect memories and retire. The most coins, homes and memories wins. |
| lr_junior_badge | Junior |
| lr_coins_fmt | %1$d coins |
| lr_per_pay_fmt | %1$d per pay stop |
| lr_bot_name_fmt | CPU %1$d |
| lr_seat_fmt | Seat %1$d |
| lr_setup_title | Set up your road |
| lr_setup_players | Players |
| lr_setup_name_hint | Name |
| lr_setup_cpu | CPU player |
| lr_setup_length | Road length |
| lr_len_quick | Quick road (about 15 min) |
| lr_len_standard | Standard road (about 40 min) |
| lr_len_long | Long road (about 60 min) |
| lr_setup_rules | Rules |
| lr_rules_standard | Standard (ages 8+) |
| lr_rules_junior | Junior (ages 6+) |
| lr_setup_headstart | Starting boost |
| lr_head_none | No boost |
| lr_head_small | Small: 1 lucky coin |
| lr_head_big | Big: 2 lucky coins and 50 coins |
| lr_setup_catchup | Catch-up boost |
| lr_catchup_help | The player far behind spins twice and keeps the higher spin. |
| lr_setup_start | Start the road |
| lr_start_title | Choose your first road |
| lr_start_prompt_fmt | %1$s, pick your road. |
| lr_fork_learn | Learn Road |
| lr_fork_learn_desc | Study first. Pay 80 for tuition, then land a high-paid job. |
| lr_fork_work | Work Road |
| lr_fork_work_desc | Start earning now. No debt, lower pay at first. |
| lr_fork_cliff | Cliff Path |
| lr_fork_cliff_desc | Shorter and bold. Coins count 1.5 times, up and down. |
| lr_fork_meadow | Meadow Path |
| lr_fork_meadow_desc | Longer and calm. Coins count normally. |
| lr_fork_family | Family Road |
| lr_fork_family_desc | Family squares help your household grow. |
| lr_fork_free | Free Road |
| lr_fork_free_desc | Lucky wheels, milestones and market days. |
| lr_lane_cozy | Cozy Hill |
| lr_lane_cozy_desc | A safe finish. Take 1 memory. |
| lr_lane_grand | Grand Lake |
| lr_lane_grand_desc | Pay 40 to enter. Homes sell for more. Needs a home. |
| lr_fork_pick_for_me | Pick for me |
| lr_turn_fmt | %1$s's turn |
| lr_turn_you | Your turn |
| lr_btn_spin | Spin |
| lr_btn_lucky_fmt | Use lucky coin (%1$d) |
| lr_btn_keep_spin | Keep this spin |
| lr_btn_continue | Continue |
| lr_btn_choose | Choose |
| lr_btn_keep_job | Keep my job |
| lr_btn_buy | Buy |
| lr_btn_no_thanks | No thanks |
| lr_btn_sell_now | Sell now |
| lr_btn_hold | Keep it |
| lr_btn_borrow | Borrow 50 |
| lr_btn_repay | Repay 60 |
| lr_btn_done | Done |
| lr_btn_cover_fmt | Buy %1$s cover for 20 |
| lr_btn_market_roll | Market roll |
| lr_btn_hint | Hint |
| lr_btn_menu | Menu |
| lr_hud_cash | Coins |
| lr_hud_job | Job |
| lr_hud_nojob | No job yet |
| lr_hud_homes | Homes |
| lr_hud_family | Family |
| lr_hud_memories | Memories |
| lr_hud_loans | Loans |
| lr_hud_cover | Cover |
| lr_hud_lucky | Lucky coins |
| lr_hud_degree | Degree |
| lr_hud_tier_fmt | Tier %1$d |
| lr_hud_needs_degree | Needs a degree |
| lr_hud_pay_tips | Pay plus tips |
| lr_hud_price_fmt | Price %1$d |
| lr_hud_needs_loan | Needs a loan |
| lr_hud_place_fmt | Place %1$d |
| lr_hud_bold_note | Bold square: coins count 1.5 times. |
| lr_prompt_start_fork | Choose Learn or Work. |
| lr_prompt_spin | Tap Spin. |
| lr_prompt_respin | Keep the spin or use a lucky coin. |
| lr_prompt_fork | Choose a road. |
| lr_prompt_job | Pick a job offer. |
| lr_prompt_house | Buy a home? |
| lr_prompt_cover | Buy cover? |
| lr_prompt_bank | Borrow or repay? |
| lr_prompt_market | Sell a home now? |
| lr_prompt_card | Read the card. |
| lr_prompt_card_spin | Spin for the card. |
| lr_prompt_settle | Spin to sell your next home. |
| lr_prompt_cpu | The CPU is thinking. |
| lr_ann_spin_fmt | %1$s spun %2$d. |
| lr_ann_spin_you | You spun %1$d. |
| lr_ann_catchup_fmt | %1$s gets a catch-up spin. |
| lr_ann_lucky_fmt | %1$s used a lucky coin and spun %2$d. |
| lr_ann_pay_fmt | %1$s collected %2$d coins at a pay stop. |
| lr_ann_land_fmt | %1$s landed on %2$s. |
| lr_ann_job_fmt | %1$s is now a %2$s. |
| lr_ann_degree_fmt | %1$s earned a degree. |
| lr_ann_raise_fmt | %1$s got a raise. |
| lr_ann_house_fmt | %1$s bought %2$s for %3$d. |
| lr_ann_sold_fmt | %1$s sold %2$s for %3$d. |
| lr_ann_cover_fmt | %1$s bought %2$s cover. |
| lr_ann_loan_fmt | %1$s borrowed 50 coins. |
| lr_ann_repay_fmt | %1$s repaid a loan. |
| lr_ann_card_fmt | %1$s drew: %2$s |
| lr_ann_memory_fmt | %1$s made a memory: %2$s. |
| lr_ann_family_fmt | %1$s's family grew to %2$d. |
| lr_ann_fresh_fmt | %1$s took a fresh start. |
| lr_ann_cover_saved_fmt | %1$s's cover cancelled the setback. |
| lr_ann_retire_fmt | %1$s retired in place %2$d. |
| lr_ann_late_fmt | %1$s retires at the last light, with no bonus. |
| lr_ann_wheel_fmt | Lucky wheel: spin %1$d pays %2$d coins. |
| lr_ann_market_fmt | Market roll %1$d: %2$s sells for %3$d. |
| lr_ann_game_over | Everyone has retired. See the score card. |
| lr_why_not_turn | Wait for your turn. |
| lr_why_phase | That is not possible right now. |
| lr_why_coins | Not enough coins. |
| lr_why_no_lucky | You have no lucky coins. |
| lr_why_loan_limit | You already have 3 loans. |
| lr_why_no_loan | You have no loans to repay. |
| lr_why_house_limit | You can own 2 homes at most. |
| lr_why_house_gone | That home is taken. |
| lr_why_need_house | Grand Lake needs a home. |
| lr_why_junior | Junior rules have no loans. |
| lr_why_over | The game is over. |
| lr_post_title | Everyone has retired |
| lr_post_winner_fmt | %1$s retires with the most! |
| lr_post_tie_fmt | A tie: %1$s share the win. |
| lr_post_play_again | Play again |
| lr_post_leave | Back to menu |
| lr_post_scorecard | Score card |
| lr_sc_total | Total |
| lr_sc_cash | Coins |
| lr_sc_homes | Homes sold |
| lr_sc_retire | Retirement bonus |
| lr_sc_memories | Memories |
| lr_sc_family | Family |
| lr_sc_comfort | Home comfort |
| lr_sc_loans | Loans owed |
| lr_sc_fresh | Fresh starts |
| lr_sc_place_fmt | Place %1$d |
| lr_sc_show_math | Show the math |
| lr_sc_hide_math | Hide the math |
| lr_market_title | Market roll |
| lr_wheel_title | Lucky wheel |
| lr_card_event | Event card |
| lr_card_family | Family card |
| lr_card_milestone | Milestone |
| lr_tone_good | Good news |
| lr_tone_bad | Setback |
| lr_tone_fun | Fun |
| lr_cover_home | home |
| lr_cover_care | care |
| lr_cover_income | income |
| lr_a11y_board | Life Road board |
| lr_a11y_node_fmt | %1$s, square %2$d of %3$d |
| lr_a11y_piece_fmt | %1$s's piece, %2$s |
| lr_a11y_spinner | Spinner, shows 1 to 10 |
| lr_a11y_spinner_result_fmt | Spinner shows %1$d |
| lr_a11y_player_fmt | %1$s: %2$d coins, %3$s |
| lr_shape_circle | circle |
| lr_shape_square | square |
| lr_shape_triangle | triangle |
| lr_shape_diamond | diamond |
| lr_shape_star | star |
| lr_shape_plus | plus |
| lr_hint_fork_learn | A degree pays more for the rest of the road. |
| lr_hint_fork_work | You earn right away and owe nothing. |
| lr_hint_job_best | This job pays the most. |
| lr_hint_house | A home can sell for more than you paid. |
| lr_hint_cover | Cover cancels one kind of setback. |
| lr_hint_repay | Repaying early keeps your score high. |
| lr_hint_respin | That spin is low. A lucky coin could help. |
| lr_hint_none | Any choice is fine here. |

**Tutorial lines** (listed in 2.21)

| Key | English |
|---|---|
| lr_tut_01 | Welcome to Life Road. Travel one road from start to retirement. |
| lr_tut_02 | Tap Spin. The spinner shows 1 to 10, and your piece walks that far. |
| lr_tut_03 | At a fork, choose your road. Learn costs coins now and pays more later. |
| lr_tut_04 | Pay stops pay your salary when you pass or land on them. |
| lr_tut_05 | A job sets your pay. Higher tiers need a degree. |
| lr_tut_06 | Homes cost coins and sell later with a market roll. Owning one adds comfort. |
| lr_tut_07 | Family squares can grow your household. Each family member is worth 30 coins. |
| lr_tut_08 | Event cards bring luck or small setbacks. Cover shops can protect you. |
| lr_tut_09 | Short of coins? Borrow 50 at the bank, but repay 60. A fresh start costs 40. |
| lr_tut_10 | Memories come from milestones and are worth 20 coins each. |
| lr_tut_11 | A lucky coin lets you spin again once. Use it when the spin is poor. |
| lr_tut_12 | Reach a retirement square to stop. Earlier retirees get a bonus. |
| lr_tut_13 | At retirement your homes sell with a market roll. Then the totals count. |
| lr_tut_14 | Most coins, homes and memories wins. Ties go to the fuller life. |
| lr_tut_15 | CPU players follow the same rules. Everyone sees every card. |
| lr_tut_16 | You are ready. Spin and enjoy the road! |

**Square kinds** (name and help)

| Key | English name | Key | English help |
|---|---|---|---|
| lr_node_start | Start Gate | lr_nodehelp_start | Every traveller begins here and chooses Learn or Work. |
| lr_node_fork | Fork | lr_nodehelp_fork | Choose which road to take. |
| lr_node_rest | Rest stop | lr_nodehelp_rest | A quiet square. Collect 5 coins. |
| lr_node_pay | Pay stop | lr_nodehelp_pay | Pass or land to collect your salary. Landing adds 10. |
| lr_node_event | Event | lr_nodehelp_event | Draw an event card. |
| lr_node_family | Family square | lr_nodehelp_family | Draw a family card. Your family may grow. |
| lr_node_jobfair | Job fair | lr_nodehelp_jobfair | See two job offers. Swap jobs or keep yours. |
| lr_node_raise | Raise | lr_nodehelp_raise | Your pay goes up by 10 for good. |
| lr_node_house | Home market | lr_nodehelp_house | Look at three homes and buy one if you like. |
| lr_node_marketday | Market day | lr_nodehelp_marketday | Sell a home now with a market roll. |
| lr_node_milestone | Milestone | lr_nodehelp_milestone | A life milestone: take a memory. |
| lr_node_cover | Cover shop | lr_nodehelp_cover | Buy cover that cancels one kind of setback. |
| lr_node_bank | Bank | lr_nodehelp_bank | Borrow 50 coins or repay a loan. |
| lr_node_wheel | Lucky wheel | lr_nodehelp_wheel | Spin for coins: 10, 20, 40 or a jackpot of 80. |
| lr_node_campus | Campus | lr_nodehelp_campus | Part-time work between lessons. Collect 8 coins. |
| lr_node_graduation | Graduation | lr_nodehelp_graduation | Pay 80 for tuition, earn a degree and pick a top job. |
| lr_node_firstjob | First job | lr_nodehelp_firstjob | Pick one of two starter jobs. |
| lr_node_retire | Retirement | lr_nodehelp_retire | Stop here. Homes sell and the totals count. |

**Career names**: `lr_career_care` = Care, `lr_career_build` = Build, `lr_career_create` = Create, `lr_career_tech` = Tech, `lr_career_outdoor` = Outdoors, `lr_career_service` = Service.

**Content names** (one string per id; text is in the tables above): `lr_job_<id>` (33), `lr_house_<id>` (12), `lr_ms_<id>` (12), `lr_event_<id>` (64), `lr_family_<id>` (16).

**Hub texts** (resource name = `hubKey` of the English text; the shared "vs Computers", "Pass & Play", "The computer", "Player 1" and "Player 2" already exist in strings.xml)

| Key (hubKey of the English text) | English |
|---|---|
| hub_life_road | Life Road |
| hub_spin_travel_the_road_retire_with_the_most | Spin, travel the road, retire with the most. |
| hub_travel_the_road_against_1_to_5_cpu_players | Travel the road against 1 to 5 CPU players. |
| hub_share_the_device_cpus_can_fill_empty_seats | Share the device. CPUs can fill empty seats. |
| hub_life_road_rules | Life Road rules |
| hub_standard_rules_ages_8 | Standard rules (ages 8+) |
| hub_loans_cover_and_fresh_starts | Loans, cover and fresh starts. |
| hub_junior_rules_ages_6 | Junior rules (ages 6+) |
| hub_no_loans_no_bad_surprises_quick_road | No loans, no bad surprises. Quick road. |
| hub_road_length | Road length |
| hub_quick_road_about_15_min | Quick road (about 15 min) |
| hub_standard_road_about_40_min | Standard road (about 40 min) |
| hub_long_road_about_60_min | Long road (about 60 min) |
| hub_starting_boost | Starting boost |
| hub_lucky_coins_and_savings_for_newer_players | Lucky coins and savings for newer players. |
| hub_no_boost | No boost |
| hub_small_1_lucky_coin | Small: 1 lucky coin |
| hub_big_2_lucky_coins_and_50_coins | Big: 2 lucky coins and 50 coins |
| hub_starting_boost_for | Starting boost for |
| hub_every_player | Every player |
| hub_only_seat_1 | Only seat 1 |
| hub_only_seat_2 | Only seat 2 |
| hub_only_seat_3 | Only seat 3 |
| hub_only_seat_4 | Only seat 4 |
| hub_only_seat_5 | Only seat 5 |
| hub_only_seat_6 | Only seat 6 |
| hub_catch_up_boost | Catch-up boost |
| hub_whoever_is_far_behind_spins_twice_keeps_the_best | Whoever is far behind spins twice, keeps the best. |

---

# PART 3: SCALE

## 3.1 Game length by mode

Squares on one route: Quick 39-41, Standard 67-71, Long 103-109 (see the size table in 2.16). An average spin is 5.5, so a player needs about 7.5 turns on Quick, 13 on Standard and 19.5 on Long (card moves and extra turns included). Turn time budget: 45 seconds for a person at a family table (spin, hop, read, decide, hand over), 12 seconds for a CPU.

| Mode | Target | Squares | Turn cap per player |
|---|---|---|---|
| Quick | about 15 min (3 seats) | about 40 | 14 |
| Standard | about 40 min (4 seats) | about 69 | 26 |
| Long | about 60 min (4 seats) | about 106 | 38 |

Estimated minutes by seat mix (turns x seat times, plus nothing for setup; add about 3 minutes for setup and score card):

| Road | 1P + 1 CPU | 2P | 2P + 2 CPU | 3P + 1 CPU | 4P | 6P |
|---|---|---|---|---|---|---|
| Quick | 7 | 11 | 14 | 18 | 22 | 34 |
| Standard | 12 | 20 | 25 | 32 | 39 | 58 |
| Long | 19 | 29 | 37 | 48 | 58 | 88 |

If a table of 5 to 6 people on Standard would run over an hour, the setup screen shows a one-line hint suggesting Quick (no rule change).

## 3.2 Player counts and CPU fill

2 to 6 seats. Vs Computers: 1 person plus 1 to 5 CPUs. Pass and Play: 2 to 6 people plus 0 to 4 CPUs, never more than 6 seats in total (the Hub clamps). Empty seats are filled by CPUs of the chosen level. CPU names are "CPU n" (`lr_bot_name_fmt`). Seat 1 is the opener unless the Hub says otherwise; the opener rotates each new board in a session.

## 3.3 Determinism and seeds

- Every random draw comes from `LifeRoadRng` (SplitMix64, counter based): draw n of stream S is a pure function of (seed, S, n).
- Three streams: SPIN (spinner, tips, wheel, milestone and market spins), DECK (shuffles, milestone pick), BOT (CPU randomness). The state stores the seed (in the config) and three cursors, so a save is replayable exactly.
- A board's seed comes from the wrapper (`seedSource`) or a fixed test seed. The Hub never exposes seeds; a hidden "daily table" (same seed for everyone) is a later idea.
- The engine reads no clock and no global state. `apply` is a pure function of (state, seat, action).
- Replay property: a game replayed from its seed and its action log produces an identical final state (`resumedGameMatchesTheUninterruptedGame`).

## 3.4 Balance simulation plan

A Monte Carlo harness (`LifeRoadSimHarness`, test source set, not shipped) plays whole games with the real engine and CPU policies. Grid: 3 sizes x seat counts 2 to 6 x CPU mixes (all Easy, all Medium, all Hard, one Hard against Easy) x 2 rule sets. Full run: 20,000 games per cell on a developer machine; the CI run uses 2,000 games per cell with doubled tolerances.

Measured per cell and reported as CSV:

| Metric | Why | Target |
|---|---|---|
| Fresh starts per player-game (the "bankruptcy" rate) | the debt rule must be rare | Standard Medium at most 8%, Hard at most 5%, Easy at most 15%, Junior 0 |
| Winner share by seat | seat order must not decide games | every seat within 1.5 points of 1/n at 20k games (binomial sigma about 0.3 points) |
| Average turns per player and minutes by the 45 s / 12 s model | length promise | near 7, 13, 19.5 turns |
| Games ending by last light | the cap must be a safety net | under 1% |
| Score mean and standard deviation per player | luck window | winner margin median under 25% of the mean |
| Learn versus Work winners | degree must not dominate or be useless | each between 45% and 55% |
| Cliff versus Meadow winners | route balance | each between 45% and 55% |
| Grand versus Cozy lane winners | late choice balance | Grand 40% to 55% of legal choices |
| Cover purchase win rate | cover must not be a dominant strategy | buying all three at most 55% against buying none |
| Home ownership versus win | homes should help, not decide | winners own on average 0.8 to 1.4 homes |
| Family and memories share of scores | the "life" half of the game should matter | 20% to 35% of a winner's total |
| Retirement bonus swing | bonus must not decide games alone | decides the winner in under 20% of games |
| Skill window | Hard against Easy (2 seats) | Hard wins 58% to 70%; Easy with a small boost wins 45% to 55% |
| Handicap effect | boost does what it says | small about +5 points, big about +12 points of win rate |
| Longest game in engine actions | no infinite loops | below 4,000 actions in all 1.8 million games |
| Throughput | CI viability | 20,000 games in under 90 s |

Tuning levers, one at a time, in this order: tier 4 and 5 salaries; tuition; the Learn Road squares; retirement bonus; Grand fee and shift; cover price; family and memory values; wheel bands; bold multiplier. After each change re-run only the affected rows, then the full grid before merging. Record every change and the before/after table in the PR.

## 3.5 Performance budget

| Item | Budget |
|---|---|
| `apply` (one action) | under 1 ms on a mid-range phone, no allocations in hot loops beyond the new immutable state |
| Hard decision | 25 ms at the 95th percentile, never on the UI thread |
| Board draw | under 16 ms per frame, one `Canvas`, at most 148 nodes (Long), nodes cached as paths; no per-frame object creation |
| Snapshot | under 12 KB JSON, built in under 5 ms |
| Memory | under 20 MB for the whole game screen |
| Start-up | board graph built once per size and cached |
| Simulation | 20,000 games under 90 s on the CI JVM |
| Frame pacing | Spin and hop animations obey `LocalGameSpeed` and `LocalReducedMotion` |

## 3.6 Resume and persistence

- Life Road is a `ResumableGame`: `snapshot()` is called by the shell after accepted actions in a stable phase (never in MOVING or TURN_END, which do not outlive an `apply` call). It returns null when there is no board or the board is over.
- Format: one JSON object (`LifeRoadSnapshot`, version 1) holding the config, seed and three cursors, every player, the pending question, the decks, and the session tally. Enums are saved by name. Unknown fields are ignored. An unknown version, enum name or out-of-range value makes `restore` return false (fail closed): the Hub then offers a new board.
- Validation (written as TODO(P4) in the `LifeRoadSnapshots.decode` KDoc, each with a test): every node id exists in the board for the saved size; all caps (loans 3, homes 2, family 4, memories 6, raises 3, lucky coins 9); every content id exists; no home owned twice or also in the stock; no duplicate card in a deck; phase and pending agree; retired counts and retire order consistent; the seat on turn is not retired; turn counts within the cap; roster identical (ids and bot flags).
- The wrapper restores the session tally and the next opener so "Play again" continues the session.
- Back-confirm uses `abortMatch` (no stats). A finished board shows the score card; closing it ends the match with a scored `GameResult`.

## 3.7 Multiplayer plan

- **First release**: same screen only (pass and play plus CPUs). Not listed in `networkedGames`.
- **Later (P8)**: Nearby and Online with an **authoritative host** that runs the engine. Clients send an intent `(seat, action)`; the host checks the sender's transport identity (never a payload id), calls `explain`, applies, and broadcasts the new state plus the events for animation. The host is the only place the seed, the cursors and the deck order exist.
- **Hidden information: none.** Every card is shown to everyone when drawn, money and homes are public. The only secrets are future randomness: client snapshots omit the seed and the cursors and carry the decks as counts only. A pending event card is public to the table as soon as it is drawn.
- Reconnect: the host re-sends the full redacted state. Turn timers: none in the first network release (idle-turn timer is an open item of the wave-5 plan).
- Nothing in the engine assumes local play: actions and events are plain data, which is why `LifeRoadAction` and `LifeRoadEvent` are data classes.

## 3.8 Phased delivery

| Phase | Deliverable | Effort |
|---|---|---|
| P0 | This doc and the scaffold (done, uncompiled) | S |
| P1 | Engine core: board build, RNG, spin, movement, pay stops, jobs, payment rules and loans, scoring, state machine, fuzz; enable the P1 tests | L |
| P2 | Content in play: events, family, milestones, homes and market roll, cover, lucky wheel; Easy and Medium CPUs; handicap | L |
| P3 | UI: five states, board canvas and layouts for three sizes, cards, accessibility, animation, haptics, SFX | L |
| P4 | Integration: game wrapper wired to screens, resume, Hub registration, coach and tips, route, ES review, strings and Hub tests | M |
| P5 | Hard CPU, simulation harness, tuning to the targets in 3.4 | M |
| P6 | Junior rule set, Junior tutorial subset, separate Hub card if OQ3 is yes | M |
| P7 | Polish: per-square juice, confetti, score card reveal, read-aloud | M |
| P8 | Nearby and Online (host authority) | L |
| P9 | Later: seasonal card packs, career sets bonus, daily table, lifetime stats | M |

Critical path: P1, P2, P3, P4 (the MVP ships at the end of P4 with Standard rules, Easy and Medium). P5 gates the claim "balanced".

## 3.9 Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | A choice dominates (degree, Grand Lake, cover) | medium | high | Monte Carlo targets in 3.4; data-only levers |
| R2 | Games run too long for families | medium | medium | Quick road, turn cap, minutes hint in setup |
| R3 | Reading load too high for 6 to 8 year olds | medium | medium | Short labels, icons, hint, read-aloud in v1 |
| R4 | Resemblance to a commercial game | low | high | Original names, board and cards; forbidden list test; legal read before listing; OQ1 |
| R5 | Board unreadable on a phone (148 squares on Long) | medium | high | Zoom to active piece, route list, seat strip, landscape layout, tablet pass |
| R6 | TalkBack cannot follow a 2D board | medium | high | Linear route list, live region prompts, announcement strings for every event |
| R7 | CPU stalls the table | low | high | `botAction` must be legal for every phase; fuzz and a "bot step always changes the state" test |
| R8 | Save format drift breaks resume | medium | medium | Version gate, fail closed, round-trip tests |
| R9 | Stats double-count tied winners | medium | low | Draw for shared wins (OQ10) |
| R10 | Spanish text overflows buttons | medium | low | Large-font screenshot pass; short labels |
| R11 | Debt feels punishing to a child | medium | medium | Junior has none; Standard has fresh start; losses are small |
| R12 | Coordinator collisions on shared files | medium | medium | Part 4 integration list; no shared file touched by this scaffold |
| R13 | Float or platform differences change a replay | low | medium | Integer maths only; own RNG; no `kotlin.random` |
| R14 | Hub string key collisions with other new games | medium | high (build failure) | Unique wording ("Starting boost", "Road length"); `HubLocalizationTest`; the gate catches duplicates |
| R15 | Scope creep (seasons, jobs editor) | high | medium | MVP fence in 1.3 |

## 3.10 Test plan

159 tests, all in `app/src/test/java/com/gamesuite/games/liferoad/`, all `@Ignore`d in the scaffold so the gate stays green. Each phase removes `@Ignore` from the tests of that phase in the same change that makes them pass. Quality-bar mapping (docs/roadmap/QUALITY-BAR.md): gate 1 rules (movement, money, houses, cards, scoring, state machine, fuzz, adversarial pass with the `adversarial-verification` skill at P5); gate 2 CPU (bot tests, simulation); gate 3 feel (manual with screenshots, `LocalReducedMotion` checks); gate 4 teaching (tutorial and hint, manual); gate 5 accessibility (manual TalkBack pass, Compose accessibility checks at P3); gate 6 multiplayer (P8); gate 7 localization (strings tests); gate 8 evidence (device pass on tablet and Fold before merging a wave).

| # | Class | Test | Proves | Phase |
|---|---|---|---|---|
| 1 | LifeRoadRngTest | sameSeedProducesSameSpinSequence | Two engines with one seed spin identical sequences | P1 |
| 2 | LifeRoadRngTest | differentSeedsProduceDifferentSequences | Different seeds diverge within 20 spins | P1 |
| 3 | LifeRoadRngTest | spinsAreAlwaysBetweenOneAndTen | 100k draws all fall in 1..10 | P1 |
| 4 | LifeRoadRngTest | spinDistributionIsRoughlyUniform | Each face is within 1 percentage point of 10% over 200k draws | P1 |
| 5 | LifeRoadRngTest | streamsAreIndependent | Drawing deck or bot numbers never moves the spin cursor | P1 |
| 6 | LifeRoadRngTest | restoreFromCursorsContinuesTheSequence | A state saved at cursor N continues exactly like the uninterrupted run | P1 |
| 7 | LifeRoadBoardTest | everySizeBuildsAConnectedGraph | Quick, Standard and Long graphs reach both retire nodes from the start | P1 |
| 8 | LifeRoadBoardTest | segmentPatternLengthsMatchTheSpec | Every pattern string has the length in the segment table | P1 |
| 9 | LifeRoadBoardTest | nodeCountsMatchTheSpec | Total, shortest-route and longest-route square counts match section 2.16 | P1 |
| 10 | LifeRoadBoardTest | everyNodeIsReachableAndHasNoDeadEnd | No orphan nodes; every non-retire node has an exit | P1 |
| 11 | LifeRoadBoardTest | forksHaveExactlyTwoBranches | S0, F2, F3 and F4 each have two exits | P1 |
| 12 | LifeRoadBoardTest | graphIsAcyclic | The road only moves forward | P1 |
| 13 | LifeRoadBoardTest | onlyCliffNodesAreBold | The bold flag is set on RISK nodes and nowhere else | P1 |
| 14 | LifeRoadBoardTest | everyKindCodeParses | All pattern characters map to a NodeKind | P1 |
| 15 | LifeRoadBoardTest | payStopCountsStayInTargetBands | Each route has the pay stops listed in the scale table | P1 |
| 16 | LifeRoadBoardTest | retireNodesAreTerminal | RC and RG have no exits | P1 |
| 17 | LifeRoadBoardTest | graphProblemsIsEmptyForAllSizes | RoadGraph.problems() reports nothing for the shipped boards | P1 |
| 18 | LifeRoadMovementTest | spinMovesExactlyThatManySquares | A spin of n walks n nodes along the chosen branches | P1 |
| 19 | LifeRoadMovementTest | passingAPayStopCollectsOnce | Passing collects salary once, never twice in one move | P1 |
| 20 | LifeRoadMovementTest | landingOnAPayStopAddsTheBonus | Landing pays salary plus 10 | P1 |
| 21 | LifeRoadMovementTest | passingAForkMidMoveAsksForAChoice | Movement pauses with stepsLeft kept | P1 |
| 22 | LifeRoadMovementTest | landingOnAForkDefersTheChoiceToNextTurn | A piece that stops on a fork chooses before the next spin | P1 |
| 23 | LifeRoadMovementTest | enteringARetireNodeStopsMovement | Spare steps are wasted at the retire node | P1 |
| 24 | LifeRoadMovementTest | luckyCoinRespinsOnlyOncePerTurn | Second use in the same turn is rejected | P1 |
| 25 | LifeRoadMovementTest | luckyCoinOnlyBeforeTheMove | No respin once the piece has moved | P1 |
| 26 | LifeRoadMovementTest | catchUpSpinOnlyWhenFarBehind | Double spin triggers at the size gap and not before | P1 |
| 27 | LifeRoadMovementTest | cardMovementNeverTriggersASecondCard | Move effects collect pay stops but skip landing effects | P1 |
| 28 | LifeRoadMovementTest | moveBackStopsAtTheStartOfTheCurrentSegment | Never crosses a fork or a merge | P1 |
| 29 | LifeRoadMovementTest | extraTurnCardGrantsAnotherSpin | EXTRA_TURN keeps the same seat on turn | P1 |
| 30 | LifeRoadJobsTest | firstJobOffersTwoStarterJobs | A square offers two tier 1 or 2 jobs and forces a pick | P2 |
| 31 | LifeRoadJobsTest | graduationOffersThreeDegreeJobs | Three tier 4 jobs, tuition paid, degree set | P2 |
| 32 | LifeRoadJobsTest | jobFairRespectsTheDegreeCap | No tier 4 or 5 offers without a degree | P2 |
| 33 | LifeRoadJobsTest | unchosenOffersReturnToTheDeck | The deck never runs out or duplicates a job | P2 |
| 34 | LifeRoadJobsTest | raiseIsCappedAtThree | The fourth raise changes nothing | P2 |
| 35 | LifeRoadJobsTest | payStopPaysSalaryPlusRaise | Fixed pay equals salary plus raises | P2 |
| 36 | LifeRoadJobsTest | tipsJobsAddTwoPerSpinPoint | Tips use the spin stream and add 2 x spin | P2 |
| 37 | LifeRoadJobsTest | noJobPaysOddJobsAllowance | Without a job a pay stop pays 10 | P2 |
| 38 | LifeRoadJobsTest | skipHalveAndDoubleNextPayApplyInOrder | A newer flag replaces an older one; flags clear after one pay | P2 |
| 39 | LifeRoadJobsTest | everyJobHasPositiveSalaryInItsTierBand | The 33 jobs obey section 2.6 tier bands | P2 |
| 40 | LifeRoadMoneyTest | loanBorrowsFiftyAndOwesSixty | One loan block adds 50 cash and 60 owed | P1 |
| 41 | LifeRoadMoneyTest | loansAreCappedAtThree | A fourth loan is rejected with LOAN_LIMIT | P1 |
| 42 | LifeRoadMoneyTest | forcedPaymentAutoBorrows | A bill above cash borrows the fewest blocks | P1 |
| 43 | LifeRoadMoneyTest | quickSaleHappensBeforeFreshStart | Homes sell at 80% of price before a fresh start | P1 |
| 44 | LifeRoadMoneyTest | freshStartWaivesDebtAndCostsFortyPoints | Loans cleared, 20 coins helping hand, 40 point penalty | P1 |
| 45 | LifeRoadMoneyTest | cashNeverEndsBelowZero | After every action cash is at least 0 | P1 |
| 46 | LifeRoadMoneyTest | repayOnlyWhenCashCoversIt | Repay needs 60 cash | P1 |
| 47 | LifeRoadMoneyTest | boldSquaresScaleCoinsByOnePointFive | Gains and losses on bold nodes round to the nearest 5, halves up | P1 |
| 48 | LifeRoadMoneyTest | tuitionUsesTheSamePaymentRules | Graduation with 30 coins borrows instead of failing | P1 |
| 49 | LifeRoadMoneyTest | juniorHasNoLoansAndCashFloorsAtZero | Junior pays what it can and never borrows | P1 |
| 50 | LifeRoadHousesTest | houseMarketOffersThreeAvailableHomes | Offers exclude owned homes | P2 |
| 51 | LifeRoadHousesTest | playerCanOwnTwoHomesAtMost | A third purchase is rejected with HOUSE_LIMIT | P2 |
| 52 | LifeRoadHousesTest | purchaseRemovesTheHomeFromStock | Nobody else can be offered it | P2 |
| 53 | LifeRoadHousesTest | marketRollTableMatchesTheSpec | Spin 1..10 maps to the multipliers in section 2.8 | P2 |
| 54 | LifeRoadHousesTest | marketDaySellsOneHomeWithARoll | Seller receives price x multiplier | P2 |
| 55 | LifeRoadHousesTest | retirementSellsEveryHomeWithItsOwnRoll | One spin per home | P2 |
| 56 | LifeRoadHousesTest | grandLakeShiftsTheMarketSpinByTwoCappedAtTen | Shift applies to every home at retirement | P2 |
| 57 | LifeRoadHousesTest | homeSaleNeverPaysLessThanSixtyPercent | Floor of the table is x0.7 standard, x1.0 junior | P2 |
| 58 | LifeRoadHousesTest | homeComfortCountsOncePerOwnedHome | Comfort is 15 per home kept to the end, sold or not | P2 |
| 59 | LifeRoadCardsTest | eventDeckHasSixtyFourUniqueCards | 64 ids, no duplicates | P2 |
| 60 | LifeRoadCardsTest | familyDeckHasSixteenUniqueCards | 16 ids, no duplicates | P2 |
| 61 | LifeRoadCardsTest | milestoneListHasTwelveEntries | 12 ids, no duplicates | P2 |
| 62 | LifeRoadCardsTest | everyEffectTokenParses | The effect DSL round-trips | P2 |
| 63 | LifeRoadCardsTest | coverCancelsOnlyItsOwnCategory | Home cover ignores care setbacks | P2 |
| 64 | LifeRoadCardsTest | coverIsPermanentAndBoughtOnce | A second purchase of the same cover is rejected | P2 |
| 65 | LifeRoadCardsTest | familyIsCappedAtFourIncludingPets | Peg cards redraw once the cap is reached | P2 |
| 66 | LifeRoadCardsTest | memoriesAreCappedAtSix | The seventh memory scores nothing | P2 |
| 67 | LifeRoadCardsTest | milestoneNeverRepeatsForAPlayer | Unclaimed list shrinks; empty list pays 10 coins | P2 |
| 68 | LifeRoadCardsTest | eachOtherPaysSkipsRetiredPlayers | Only active players are charged | P2 |
| 69 | LifeRoadCardsTest | juniorSoftensBadCards | Losses halve to a minimum of 5; skip and halve become pay 10 | P2 |
| 70 | LifeRoadCardsTest | luckyWheelPaysByTheSpinBands | 1-3: 10, 4-7: 20, 8-9: 40, 10: 80 | P2 |
| 71 | LifeRoadScoringTest | scoreAddsEveryComponent | Cash, homes, bonus, memories, family and comfort are summed | P1 |
| 72 | LifeRoadScoringTest | loansAndFreshStartsSubtract | 60 per loan, 40 per fresh start | P1 |
| 73 | LifeRoadScoringTest | retireBonusFollowsArrivalOrder | 80, 50, 30, 20, 10, 10 | P1 |
| 74 | LifeRoadScoringTest | lateRetirementGetsNoBonus | Turn cap retirees score without the bonus | P1 |
| 75 | LifeRoadScoringTest | tieBreakOrderIsLifePointsThenFewerDebtsThenEarlierRetire | Section 2.14 order | P1 |
| 76 | LifeRoadScoringTest | exactTieBecomesASharedWin | All tied winners have isWinner true | P1 |
| 77 | LifeRoadScoringTest | scoreCardLinesSumToTheTotal | Displayed lines add up with no hidden term | P1 |
| 78 | LifeRoadScoringTest | gameResultListsEverySeatOnce | GameResult has one PlayerScore per player | P1 |
| 79 | LifeRoadStateMachineTest | everyPhaseTransitionFollowsTheTable | Random play only visits edges listed in section 2.15 | P1 |
| 80 | LifeRoadStateMachineTest | illegalActionsAreRejectedWithAReason | explain() returns a specific LifeRoadReject | P1 |
| 81 | LifeRoadStateMachineTest | onlyTheSeatOnTurnMayAct | Other seats get NOT_YOUR_TURN | P1 |
| 82 | LifeRoadStateMachineTest | retiredSeatsAreSkipped | Turn order skips retired players | P1 |
| 83 | LifeRoadStateMachineTest | turnCapEndsTheGame | Last light fires at the size cap | P1 |
| 84 | LifeRoadStateMachineTest | gameOverIsTerminal | No action is legal after GAME_OVER | P1 |
| 85 | LifeRoadStateMachineTest | everyPhaseHasAtLeastOneLegalAction | No state can stall the table | P1 |
| 86 | LifeRoadStateMachineTest | legalActionsMatchWhatApplyAccepts | legalActions() and apply() agree on 5000 random states | P1 |
| 87 | LifeRoadStateMachineTest | startForkIsAskedInSeatOrder | Every seat chooses Learn or Work before the first spin | P1 |
| 88 | LifeRoadBotTest | easyBotNeverStalls | Easy always returns a legal action | P2/P5 |
| 89 | LifeRoadBotTest | mediumBotNeverStalls | Medium always returns a legal action | P2/P5 |
| 90 | LifeRoadBotTest | hardBotNeverStalls | Hard always returns a legal action | P2/P5 |
| 91 | LifeRoadBotTest | botActionIsAlwaysLegal | Across 1000 games every bot action passes explain() | P2/P5 |
| 92 | LifeRoadBotTest | botsAreDeterministicForASeed | Same seed and level give the same game | P2/P5 |
| 93 | LifeRoadBotTest | easyBuysFewerCoversThanMedium | Behaviour script check over 500 games | P2/P5 |
| 94 | LifeRoadBotTest | hardUsesLuckyCoinsOnlyWhenItHelps | Respin only on spins below the expected value | P2/P5 |
| 95 | LifeRoadBotTest | hardDecisionFitsTheTimeBudget | 95th percentile under 25 ms on the JVM harness | P2/P5 |
| 96 | LifeRoadBotTest | botsFinishThousandGamesWithoutException | Smoke run, all sizes and seat counts | P2/P5 |
| 97 | LifeRoadJuniorTest | juniorStartsWithOneHundredFiftyCoins | Section 2.18 start cash | P6 |
| 98 | LifeRoadJuniorTest | juniorTuitionIsFree | Graduation costs 0 and needs no loan | P6 |
| 99 | LifeRoadJuniorTest | juniorCoverAndBankSquaresPayTenCoins | I and B squares become gifts | P6 |
| 100 | LifeRoadJuniorTest | juniorMarketRollNeverLosesValue | Junior multiplier table floor is x1.0 | P6 |
| 101 | LifeRoadJuniorTest | juniorEveryoneStartsWithOneLuckyCoin | Before handicap is added | P6 |
| 102 | LifeRoadJuniorTest | juniorDefaultsToTheQuickRoad | applies map of the Hub choice | P6 |
| 103 | LifeRoadHandicapTest | smallHeadStartGivesOneLuckyCoin | Plus nothing else | P2 |
| 104 | LifeRoadHandicapTest | bigHeadStartGivesTwoLuckyCoinsAndFiftyCoins | Section 2.19 | P2 |
| 105 | LifeRoadHandicapTest | headStartSeatParsingHandlesAllNoneAndNumbers | parseLifeRoadHandicapSeats | P2 |
| 106 | LifeRoadHandicapTest | headStartNeverAppliesToOutOfRangeSeats | Seat 6 in a 3 seat game is ignored | P2 |
| 107 | LifeRoadSnapshotTest | snapshotRoundTripsMidGame | snapshot then restore equals the original state | P4 |
| 108 | LifeRoadSnapshotTest | restoreRejectsAnUnknownVersion | Version field gate | P4 |
| 109 | LifeRoadSnapshotTest | restoreRejectsAWrongPlayerCount | Roster must match the saved seats | P4 |
| 110 | LifeRoadSnapshotTest | restoreRejectsNegativeCash | Range checks | P4 |
| 111 | LifeRoadSnapshotTest | restoreRejectsAnUnknownNodeId | Node id must exist in the board for the saved size | P4 |
| 112 | LifeRoadSnapshotTest | restoreRejectsDuplicateHomeOwnership | A home id may be owned once | P4 |
| 113 | LifeRoadSnapshotTest | restoreRejectsAPendingThatDoesNotMatchThePhase | Phase and pending must agree | P4 |
| 114 | LifeRoadSnapshotTest | restoreRejectsOverCapCounts | Loans, homes, family and memories above their caps | P4 |
| 115 | LifeRoadSnapshotTest | resumedGameMatchesTheUninterruptedGame | Save at a random step, restore, play on: identical result | P4 |
| 116 | LifeRoadSnapshotTest | snapshotStaysUnderTwelveKilobytes | Size budget | P4 |
| 117 | LifeRoadSnapshotTest | snapshotIsNullAfterGameOver | Finished games are not resumable | P4 |
| 118 | LifeRoadGameTest | initRequiresTwoToSixPlayers | Rejects one and seven | P4 |
| 119 | LifeRoadGameTest | startMatchCreatesAStartForkState | Phase START_FORK, every seat at S0 | P4 |
| 120 | LifeRoadGameTest | abortMatchReportsNoScores | wasAborted true and empty scores | P4 |
| 121 | LifeRoadGameTest | endMatchReportsScoresAndWinners | GameResult matches the score card | P4 |
| 122 | LifeRoadGameTest | botStepAlwaysChangesTheState | A CPU seat can never stall the table | P4 |
| 123 | LifeRoadGameTest | playAgainKeepsSessionWins | Session wins survive a new board | P4 |
| 124 | LifeRoadGameTest | moduleMetadataMatchesTheDoc | id life-road, 2..6 players, BOARD, modes | P4 |
| 125 | LifeRoadHubSpecsTest | audienceIsFamilyAndMinAgeIsEight | Spec tags | P4 |
| 126 | LifeRoadHubSpecsTest | noNetworkModesInTheFirstRelease | Only VS_CPU and PASS_AND_PLAY | P4 |
| 127 | LifeRoadHubSpecsTest | seatLimitsAreTwoToSix | minSeats 2, maxSeats 6 | P4 |
| 128 | LifeRoadHubSpecsTest | optionKeysAndDefaultsAreValid | Defaults appear in their own choice lists | P4 |
| 129 | LifeRoadHubSpecsTest | juniorChoiceAppliesTheQuickLength | HubChoice.applies | P4 |
| 130 | LifeRoadHubSpecsTest | everyHubTextHasEnglishAndSpanishStrings | hubKey names resolve in both locales | P4 |
| 131 | LifeRoadHubSpecsTest | accentColoursMeetContrast | 3.0 non-text and 4.5 text on both surfaces | P4 |
| 132 | LifeRoadHubSpecsTest | idIsUniqueAcrossTheCatalog | No other spec uses life-road | P4 |
| 133 | LifeRoadStringsTest | everyEnglishKeyHasASpanishValue | Key sets of values and values-es match | P4 |
| 134 | LifeRoadStringsTest | formatPlaceholdersMatchBetweenLocales | Same positional specifiers in EN and ES | P4 |
| 135 | LifeRoadStringsTest | tutorialLinesAreUnderOneHundredTwentyCharacters | Both locales | P4 |
| 136 | LifeRoadStringsTest | everyContentIdHasANameString | 33 jobs, 12 homes, 64+16 cards, 12 milestones | P4 |
| 137 | LifeRoadStringsTest | noForbiddenTrademarkWordsAppear | Scans EN and ES for the forbidden vocabulary list | P4 |
| 138 | LifeRoadStringsTest | noRealMoneyWordsAppear | No dollar sign or currency names; coins only | P4 |
| 139 | LifeRoadStringsTest | noPercentSignsInFormatStrings | Avoids unescaped percent in getString calls | P4 |
| 140 | LifeRoadContentTest | thereAreThirtyThreeJobsInFiveTiers | 6, 8, 7, 6, 6 | P2 |
| 141 | LifeRoadContentTest | salariesStayInTheirTierBands | Tier 1 20-26 up to tier 5 90-115 | P2 |
| 142 | LifeRoadContentTest | homePricesAscendAndIdsAreUnique | 12 homes from 80 to 520 | P2 |
| 143 | LifeRoadContentTest | everyCoverCategoryHasSetbackCards | Home 5, care 4, income 3 | P2 |
| 144 | LifeRoadContentTest | noCardSwingsMoreThanFiftyCoins | Largest single swing is 50 (the raffle at a spin of 10) | P2 |
| 145 | LifeRoadContentTest | deckExpectedCoinValueIsSlightlyPositive | Mean coin delta of the 64 cards between 0 and +8 | P2 |
| 146 | LifeRoadSimulationTest | freshStartRateStaysInTarget | Standard Medium at most 8% of player-games | P5 |
| 147 | LifeRoadSimulationTest | winnerBySeatStaysWithinTolerance | Each seat within 1.5 points of 1/n at 20k games | P5 |
| 148 | LifeRoadSimulationTest | averageLengthMatchesEachMode | Turns per player near 7, 13 and 19 | P5 |
| 149 | LifeRoadSimulationTest | learnVersusWorkWinRateIsBalanced | Each between 45% and 55% | P5 |
| 150 | LifeRoadSimulationTest | coldBoldVersusMeadowIsBalanced | Cliff and Meadow each between 45% and 55% | P5 |
| 151 | LifeRoadSimulationTest | grandVersusCozyIsBalanced | Grand between 40% and 55% of the lane choices that are legal | P5 |
| 152 | LifeRoadSimulationTest | coverIsNotADominantStrategy | Buying all three covers wins at most 55% against not buying | P5 |
| 153 | LifeRoadSimulationTest | hardBeatsEasyInTheSkillWindow | Hard versus Easy head to head between 58% and 70% | P5 |
| 154 | LifeRoadSimulationTest | handicapMovesWinRateByTheTargetAmount | Small about +5 points, big about +12 | P5 |
| 155 | LifeRoadSimulationTest | noGameExceedsTheActionCap | Every game ends below 4000 engine actions | P5 |
| 156 | LifeRoadSimulationTest | twentyThousandGamesRunUnderNinetySeconds | Throughput gate for CI | P5 |
| 157 | LifeRoadFuzzTest | randomLegalPlayNeverThrows | 10k games with random legal actions | P2 |
| 158 | LifeRoadFuzzTest | invariantsHoldAfterEveryStep | Cash at least 0, loans at most 3, homes at most 2, family at most 4, memories at most 6 | P2 |
| 159 | LifeRoadFuzzTest | snapshotRestoreInTheMiddleOfFuzzGames | Restore at random steps and keep going | P2 |

---

# PART 4: SCAFFOLD

Created by this task (all new, nothing existing edited):

| File | What it is |
|---|---|
| `docs/design/boardgames/life-road.md` | This document |
| `app/src/main/java/com/gamesuite/games/liferoad/LifeRoadModel.kt` | Enums, data classes, actions, events, state (hand written) |
| `.../LifeRoadRules.kt` | Every tunable number plus `marketValue`, `wheelCoins`, `boldCoins` (generated from the doc tables) |
| `.../LifeRoadContent.kt` | 33 jobs, 12 homes, 12 milestones, 64 events, 16 family cards as data (generated) |
| `.../LifeRoadBoard.kt` | `LifeRoadBoardSpec` (segment patterns and edges as data, generated) and a stub `LifeRoadBoard.build` |
| `.../LifeRoadRng.kt` | Counter-based SplitMix64 streams (real, small) |
| `.../LifeRoadEngine.kt` | `LifeRoadEngine` interface, `LifeRoadScoring` (real formula), `LifeRoadHandicap.parseSeats` (real), `StubLifeRoadEngine` (refuses every action) |
| `.../LifeRoadSnapshot.kt` | Private snapshot DTOs, `encode`, structural `decode` with validation TODOs in KDoc |
| `.../LifeRoadGame.kt` | `GameModule` and `ResumableGame` wrapper (final shape; engine is the stub) |
| `.../LifeRoadHubSpecs.kt` | Hub spec with `audience = GameAudience.FAMILY`, `minAge = 8`; **not registered anywhere** |
| `app/src/main/res/values/life_road_strings.xml`, `values-es/life_road_strings.xml` | 401 strings each; Spanish values are real (review is OQ12) |
| `app/src/test/java/com/gamesuite/games/liferoad/*Test.kt` (19 classes) | 159 `@Ignore`d stubs, names identical to section 3.10 |

What is real versus stub:

- Real: all data tables, the constants, the RNG, the score formula, the handicap parser, the snapshot encode and structural decode, the wrapper, the Hub spec, the strings.
- Stub: `LifeRoadBoard.build` (two nodes), `StubLifeRoadEngine` (`newGame` builds a start table with unshuffled decks; `apply` refuses with `NOT_IMPLEMENTED`; `legalActions` is empty; `botAction` returns Acknowledge; `scoreCards` ranks by total only).

Integration list for the coordinator (the owner of this scaffold was told not to touch these shared files, so none is edited):

1. `core/GameHubSpecs.kt`: add `LifeRoadHubSpecs.lifeRoad` to `baseSpecs()` in the BOARD section (next to Ludo). Do **not** add `"life-road"` to `networkedGames`.
2. `core/GameCatalog.kt`: glyph for `"life-road"` is `LifeRoadHubSpecs.GLYPH` (the motorway emoji, unused elsewhere).
3. `ui/GameTheme.kt`: `GameThemes.accents["life-road"] = GameAccent(0xFF971A0C, 0xFFDE867C)` (light contrast vs FFFBFE / E6E0E9 = 8.3 / 6.56; dark contrast vs 1C1B1F / 36343B = 6.37 / 4.56; nearest existing accent is 26.0 RGB units away). The scan was by RGB distance only; a designer may prefer another warm tone.
4. `MainActivity` / navigation: route `"life-road"` to the future screen (P3 and P4). Until then do not register the game.
5. `GameCoach` and `GameTips`: coach card and tip lines (use the tutorial strings as the source).
6. `GameAudienceTable` needs no row (the spec's own audience and minAge win).
7. Strings: `hub_*` names are generated by `hubKey`; a duplicate of another new game's key fails the build. The generator checked every existing `res/values*/*.xml` at writing time (28 keys defined here, none already present). If another concurrent game adds the same English text, rename one.
8. Tests: after the first compile run, expect the 19 Life Road test classes to report all tests as skipped.

Nothing in this change was built, compiled, linted or run.
