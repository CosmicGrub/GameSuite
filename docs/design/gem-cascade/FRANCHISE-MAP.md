# FRANCHISE-MAP: every mode and feature of the genre, mapped to an original Gem Cascade equivalent

Status: DESIGN DRAFT, 2026-11-22. Read-only research plus design; no source edited, nothing built. "Gem Cascade" (`gem-cascade`) is a placeholder name.
Siblings: `SCOPE.md`, `SCRIPT.md`, `SCALE.md`, `GAME-IN-STYLE-SYSTEM.md`; `EVENTS.md` is written by the engine team (not yet present). Boards: `GC*.dc.html` in the design project.

## 0. Legal rule and naming policy

Mechanics are free; names, art, sounds and UI are not. Everything in this plan is original. Names below are ours; the source names are used ONLY in this document, to show what we are matching, and must never appear in the app, store listing, strings, file names, code identifiers, sound names or art.

Do-not-use list (app side): Bejeweled and every mode, gem and badge name from it (Classic, Action, Endless, Puzzle, Lightning, Blitz, Zen, Quest, Twist, Poker, Ice Storm, Butterflies, Diamond Mine, Flame Gem, Star Gem, Hypercube, Supernova, Scrambler, Detonator, Blazing Steed, Elite, Vault ...), Candy Crush, Jewel Quest, Puzzle Quest, and any other trademarked match-3 name, mascot, UI layout or sound. Generic words that happen to be mode names in the franchise (Zen, Twist, Quest, Classic, Lightning, Blitz, Butterflies) are avoided too, so no name can be read as imitation. Plain words (match, cascade, chain, daily, level, star) are fine.

Recommended before launch (owner decision): a trademark and app-store name search on "Gem Cascade" itself; it is generic-sounding and may collide.

Art policy: original vector/baked art only, per `docs/design/style-packs/SCOPE.md` section 3.1. Seven gem silhouettes, four special overlays and all obstacle art are drawn in `GCGems`/`GCLevels`. Sounds: synthesised or recorded in-house, ledgered in the licence ledger (`SCRIPT.md` part C6 of the style packs).

## 1. Sources and confidence

I could reach search results (WebFetch was unavailable), so facts below are VERIFIED only where a result snippet states them; everything else is UNVERIFIED or taken from general genre knowledge and is marked so.

| Tag | Source | What it supports |
|---|---|---|
| S1 | https://bejeweled.wiki.gg/wiki/Bejeweled_3 and https://en.wikipedia.org/wiki/Bejeweled_3 | Bejeweled 3 has 8 modes: Classic, Lightning, Zen, Quest, plus Poker, Ice Storm, Butterflies, Diamond Mine |
| S2 | https://bejeweled.wiki.gg/wiki/Category:Secret_Modes | Poker, Ice Storm, Butterflies, Diamond Mine unlock after Quest objectives; each is an endless form of a Quest mini-quest; Diamond Mine unlocks after four Quest completions |
| S3 | https://bejeweled.wiki.gg/wiki/Butterflies_(Quest_Mode_Plus) | Butterfly gems rise from the bottom; match them with special gems before they reach the spider at the top; one passing the top ends the mini-quest |
| S4 | https://bejeweled.wiki.gg/wiki/Bejeweled_Twist and https://en.wikipedia.org/wiki/Bejeweled_Twist | Twist rotates a group of gems; 4 modes (5 on DS): Classic, Blitz, Zen, Challenge; Bomb gems, Doom gems, Coal, locked/chained gems; Disarm Spinner with skulls |
| S5 | https://bejeweled.wiki.gg/wiki/Blitz_mode_(Twist) | Twist Blitz is timed (5 minutes; 3 in the Flash version, sources conflict); bombs and coal appear, doom gems and locks do not |
| S6 | https://bejeweled.wiki.gg/wiki/Bejeweled_2 and https://en.wikipedia.org/wiki/Bejeweled_2 | Bejeweled 2 modes: Classic and Action (from the original), Endless (Classic progress bar plus Action-style board reset, so unlosable) and Puzzle (hand-made boards, missing on mobile); a fan wiki counts 9 modes including hidden ones (Original, Twilight, Hyper, Cognito, Finity): UNVERIFIED count |
| S7 | https://bejeweled.wiki.gg/wiki/How_to_Play and https://giantbomb.com/wiki/Games/Bejeweled_3 | Special gems: Flame (line of 4, blasts 3x3 on match), Star (T or L, lines in four directions), Hypercube (line of 5, clears one colour), Supernova (sources disagree: 6+ in a row, or a 6-8 match only possible via cascades); Flame+Star combo |
| S8 | https://bejeweled.wiki.gg/wiki/Bejeweled_Blitz and https://ea.com/games/bejeweled/bejeweled-blitz/news/discover-free-and-upgradeable-boosts | Blitz boosters (Scrambler, Detonator, Multiplier, Mystery Gem, Extra Time, Rarifier) and rare gems (several named; one puts out eight Flame gems at the end of a game) |
| S9 | https://bejeweled.wiki.gg/wiki/Quest_Mode_Plus | A regional re-release with Time Bomb and Match Bomb modes (bomb gems counting down in real time) |
| S10 | https://gamedeveloper.com/design/45-match-3-mechanics and https://medium.com/@ekinmelissezer/game-analysis-for-royal-match-and-toon-blast-9c4bff8ef48b | Genre: move-limited levels with obstacles on the grid; boosters that do not cost a move; special pieces from 4 or 5 matches; leftover moves converted into specials; 1 to 3 star ratings |
| S11 | https://en.wikipedia.org/wiki/Bejeweled_Stars | Page exists; I could not read its contents. Everything about Bejeweled Stars below is UNVERIFIED |

UNVERIFIED items (kept in the design only as "plausible genre feature", never as a claim about the source): Bejeweled Stars rules; the original 2001 game's mode list beyond Classic/Timed; Bejeweled 3 scoring numbers; multiplier-gem and Vault behaviour; Poker hand rules; exact bomb counter values; the Elite badge details; Bejeweled Classic (the 2000s web/mobile remake) differences.

## 2. Source modes mapped to Gem Cascade modes

Naming: each GC mode has one plain, original word. Fail state is always gentle (see SCOPE kid rules).

| # | Source feature (franchise) | Gem Cascade mode / feature (original) | Rule in one paragraph | Why it feels like the source | Release |
|---|---|---|---|---|---|
| 1 | Classic (B1, B2, B3): progress bar, levels, ends when no moves | **Ascent** | Fill the level bar to climb a level; levels raise the gem-kind count (6 kinds at levels 1-5, 7 from level 6) and the bar target. No clock. When no move is left the board reshuffles once for free; a second dead board ends the run. | Same relaxed, endless-until-stuck rhythm and level-up sparkle | MVP |
| 2 | Zen (B3, Twist), Endless (B2) | **Garden** | No clock, no move limit, no obstacles, never ends. Every 20 gems cleared grows a bloom; a dead board silently reshuffles. | Unlosable meditative play; kid default | MVP |
| 3 | Lightning (B3), Action (B1/B2), Blitz (Blitz, Twist) | **Sprint** | 60 s clock (max 90 s); clock gems add 3 s, chains of 3+ add 1 s each; a speed meter multiplies score (x1 to x8). A no-clock Practice variant is always offered. | Frantic score-chasing with time extension and speed reward | MVP |
| 4 | Quest (B3, about 40 challenges, UNVERIFIED count beyond reviews) | **Journey** | 150 levels in 10 worlds; goals (score, collect colour, clear obstacles, bring relics down, free fireflies, defuse bombs) within a move budget; 1 to 3 stars. See `SCRIPT.md` part B. | Curated challenge ladder mixing the other modes | MVP (30 levels), v1 (150) |
| 5 | Puzzle (B2) hand-crafted boards, clear the board | **Riddles** | Fixed hand-made boards with a move budget (clear all, or reach a shape). 60 riddles planned. | Brain-teaser variant | v1 (30), later (60) |
| 6 | Twist (rotate a group of gems) | **Swirl** | Rotate any 2x2 block a quarter turn instead of swapping; a turn that makes no match is allowed but costs a move. Bomb/coal-style obstacles appear in later levels. | The distinctive rotation mechanic | v1 |
| 7 | Twist Classic with Bomb and Doom gems, disarm spinner | **Fuse** (and Journey fuse levels) | Bomb gems carry a counter that drops each move; match the bomb to defuse it; at 0 the round ends. A "doom" variant is NOT copied; no skull spinner. Gentle: Calm mode shows count only. | Pressure from countdown bombs | v1 |
| 8 | Diamond Mine (B3 secret mode) | **Delve** | Lower rows are dirt hiding relics; matching next to dirt breaks it; relics that reach the bottom are collected; clearing a row scrolls the board deeper. | Dig-for-treasure with depth scoring | v1 |
| 9 | Butterflies (B3 secret mode, S3) | **Fireflies** | Fireflies rise one row every 3 moves; match the gem a firefly rides on to free it; a firefly reaching the web row ends the round. | Rising creatures racing a ceiling | v1 |
| 10 | Ice Storm (B3 secret mode) | **Thaw** | Frost columns rise from the bottom; matching on or above frost removes layers; filling the "warm" tank raises the multiplier and melts every column once. | Rising ice and a tank that raises the multiplier | later |
| 11 | Poker (B3 secret mode) | **Sigils** (pattern bonus; NOT cards, NOT gambling) | Each cleared colour fills a 5-slot tray; when full, a bonus is scored for the pattern (all one colour, all different, two pairs, three plus two, a run in spectrum order). No cards, no chips, no suits, no skull penalty, no payout look. Optional and can be cut. | The "make hands from what you clear" feel without gambling imagery | later (owner may veto) |
| 12 | Time Bomb / Match Bomb (S9) | Fuse variants in Journey (20 bombs to defuse) | Journey goal "Defuse 20" | Same countdown goal | v1 |
| 13 | Challenge (Twist) | Journey goal levels in World 8 (Spin Atelier) | Hand-set goals | Same | v1 |
| 14 | Daily challenge (genre, UNVERIFIED for this franchise) | **Daily** | One seeded board per UTC date, same for everyone on this device and any other; goal + move budget; streak counter that never punishes. Uses the shared `DailySeed` helper (scorecard finding 5). | Daily ritual | MVP |
| 15 | Stars (Bejeweled Stars, UNVERIFIED) / star ratings (S10) | **Stars and Constellations** | 3 stars per Journey level; stars fill constellations on the world map; every 25 stars lights a constellation (sticker). | Collection feel | v1 |
| 16 | Leaderboards (franchise online boards) | **Local board** | Best score per mode per local profile, a "family board" of local profiles only. No online board, no strangers (SCOPE). | Score pride without strangers | MVP (bests), v1 (family board) |
| 17 | Achievements / badges (B3 badges, Elite badge UNVERIFIED) | **Stickers** | 40 local stickers, real progress shown, no rarity, no duplicates, no random rewards. | Collectable recognition | v1 |

## 3. Special gems

| Source | Gem Cascade | Made by | Effect when matched | Release |
|---|---|---|---|---|
| Flame Gem (line of 4; 3x3 blast) | **Spark Bloom** | Line of 4 (the swapped gem becomes it) | Clears the 3x3 around it | MVP |
| Star Gem (T or L; lines in four directions) | **Cross Comet** | L, T or plus of 5 | Clears its whole row and column | MVP |
| Hypercube (line of 5; swap clears one colour) | **Chroma Core** | Line of 5 | Swap with any gem: clears every gem of that colour; alone it clears the colour it is next to when matched | MVP |
| Supernova (6+ in a line, UNVERIFIED rules) | **Halo Crown** | Line of 6+, or two crossing lines of 4 | Clears 3 rows and 3 columns centred on it | MVP (rule), v1 (art polish) |

Special + special combos (all deterministic, scores in `EVENTS.md`):

| Pair | Effect |
|---|---|
| Bloom + Bloom | 5x5 blast |
| Bloom + Comet | 3 rows and 3 columns through the swap |
| Bloom + Halo | 7x7 blast |
| Bloom + Core | Every gem of the Core's colour becomes a Bloom, then all fire |
| Comet + Comet | Both fire: 2 rows and 2 columns |
| Comet + Halo | Halo band plus both diagonals |
| Comet + Core | Every gem of that colour becomes a Comet, then all fire |
| Halo + Halo | 5 rows and 5 columns |
| Halo + Core | Clears two colours, then fires a Halo |
| Core + Core | Clean Sweep: clears the board, fixed bonus |

The franchise's Flame+Star combination is the closest source analogue (S7); every other combo is our design, tuned in play-testing (UNVERIFIED against the source).

## 4. Boosters, multipliers, rare gems

| Source feature | Gem Cascade equivalent | Rule | Notes |
|---|---|---|---|
| Scrambler (S8) | **Reshuffle** | Rearranges the board; guaranteed a valid move; free in Garden | Earned by play (one per 5 levels), never bought |
| Detonator (S8) | **Smash** | Tap a gem: clears it and its 4 neighbours | Costs no move (genre rule S10) |
| Extra Time (S8) | **Time Drop** (Sprint) / **+3 moves** (Journey) | +5 s or +3 moves | Earned; one per run |
| Mystery Gem (S8) | **Lantern** (hint) | Highlights the best valid swap | Always free; kid default on |
| Multiplier (S8) | **Echo gem** | A gem with an echo ring doubles the value of the match it is in | Appears in Sprint and Ascent |
| Rarifier + rare gems (S8): Blazing Steed puts out eight flame gems at the end of a game, others recolour nearby gems | **Charms** | Before a run, equip one earned Charm: Ember Seed (start with one Bloom), Frost Wick (first frost layer free), Lantern Oil (hint cost-free), Tide Pearl (+1 s per Time gem), Comet Thread (first L-match is a Comet), Slate Ring (slate takes one extra hit). Charms are unlocked by stickers; no store, no random draws. | Names are ours; UNVERIFIED how the source rare gems work beyond S8 snippets |
| Gem multiplier / cascade multiplier | **Chain** | Chain step n multiplies the step's points by n (cap x8) | Core genre rule |
| Level-end bonus (S10 "leftover moves become specials") | **Finale** | Each unused move becomes a random special that fires: +300 each | Journey only |
| Vault (UNVERIFIED) | none | not copied | |

## 5. Obstacles and goals (genre features)

| Genre feature (S10 and general knowledge, UNVERIFIED where noted) | Gem Cascade equivalent |
|---|---|
| Locked / chained gem (Twist locks, S4) | **Cage** |
| Ice layers (Ice Storm, genre) | **Frost**, 1 to 3 layers |
| Coal / unmatchable block (Twist Coal, S4) | **Slate** |
| Countdown bomb (S4, S9) | **Fuse bomb** |
| Butterfly climber (S3) | **Firefly** |
| Diamond / treasure under rock (Diamond Mine, S2) | **Relic** under **Dirt** |
| Drop-to-bottom items (genre, UNVERIFIED) | World 9 **Pearls** (same code path as relics) |
| Jelly under tiles, spreading blockers, conveyors, portals (genre, UNVERIFIED) | Later: **Glaze** (tile goal), **Moss** (spreads), **Current** (conveyor row); World 9 and 10 only if the owner wants |
| Move-limited levels, 1-3 stars, goal types (S10) | Journey (this plan) |
| Hints, auto-shuffle on dead board | Lantern, free reshuffle |
| Boosters that do not consume a move (S10) | All boosters |

## 6. Features deliberately NOT copied

| Genre feature | Decision | Reason |
|---|---|---|
| Lives / hearts / energy timers | NOT in Gem Cascade | SCOPE kid rules; user's instruction |
| Purchases, currency, gem packs, loot boxes, ads, timed offers | NOT | Same |
| Social: friends, invites, online boards, asking friends for lives | NOT | No strangers; local only |
| Spinner / reel bonuses, poker cards, chips | NOT (Sigils is a pattern tray) | Gambling look |
| Doom gems with an instant-lose and no counter-play (S4) | NOT | Frustration; Fuse always allows a match |
| Streak freeze purchases | NOT | Dark pattern; a missed day only resets quietly, best streak is kept |

## 7. What makes it "all of them combined" without being a clone

1. One engine, many rule layers: a mode is a `RuleSet` (goal, fail state, spawners, obstacle set) over the same matcher, so Garden, Sprint, Swirl, Delve, Fireflies and Fuse share 90 percent of the code.
2. One art interface, three styles (`GAME-IN-STYLE-SYSTEM.md`): gem, board, particles, sound and haptics from the StylePack; the engine never knows the style.
3. A coherent original identity: seven silhouettes in an Okabe-Ito palette, four specials with overlay glyphs, obstacle set with names from our own garden/mine/frost-hollow world map.
