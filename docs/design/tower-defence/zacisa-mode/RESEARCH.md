# Research: ZaciSa's Last Stand (Wii U, 2014) and ZaciSa: Defense of the Crayon Dimension! (PC, 2023)

Status: RESEARCH NOTES, 2026-11-22. Design and research only: no Kotlin, no build, nothing installed. Author: research and design agent.

Rules applied: game RULES and MECHANICS are recorded so they can be matched in structure; art, names, logos, audio, music, text and map layouts are NOT recorded or to be reproduced. Sources are summarised, not quoted. Anything numeric that is not stated by a source is marked as unknown; our own numbers live in `BALANCE.md`.

## 1. What the game is

A small 2D tower defence set in space. The player defends a central base (one review calls it a nucleus) against waves of enemy ships by placing automated "Bot" weapons. Made by ZeNfA Productions (Greg Wiggleton); the Wii U original was a Nintendo Web Framework title released on the US eShop on 3 July 2014 at 2.99 USD (EU later; the later retitle and Wii U update are described in Nintendo Life headlines). A Steam remake, "ZaciSa: Defense of the Crayon Dimension!", released 1 August 2023 with hand-crayon art, new weapons, new maps, a new menu system and quality-of-life changes. A separate idle spin-off exists (out of scope).

Owner brief said "Flash-origin". We found NO source for a Flash original (searches returned only an unrelated Flash game, "The Last Stand"). The Nintendo Web Framework (HTML5 / JavaScript) is documented as the origin of the Wii U version. We treat the Flash claim as UNVERIFIED and it does not affect the design.

## 2. Sourced mechanics reconstruction

Confidence key: H = two or more independent sources agree; M = one clear source; L = inferred or only a search-engine summary of a page we could not fetch.

### 2.1 Core loop
- Enemy ships arrive in waves the game calls "levels" and head for the base; the player spends money on Bots and upgrades to destroy them first. (H: Nintendo World Report, Family Friendly Gaming, Wiisworld, Pure Nintendo, Steam.)
- Early levels start with about twenty weak enemies; each survived level brings more enemies and they grow stronger (more health and more speed). (H: Wiisworld listing, Nintendo World Report.)
- Waves ("levels") are continuous; players described levels as long and said it was sometimes unclear whether they had won or lost. (M: Family Friendly Gaming; M-L: Nintendo Life per search summary.)

### 2.2 Economy
- One currency, money. Earned by destroying enemies, plus a bonus at the end of each round; also points per ship. (H: Wiisworld, Pure Nintendo.) The Steam remake names the currency "ZaCoins" and awards them per kill. (M: Steam page.) Our version: "Scrap".
- Money buys new Bots and upgrades. (H.)
- Easy difficulty gives 25% more starting building cash. (M: Pure Nintendo.)

### 2.3 Weapons ("Resources" / "Bots")
Five Bots plus a shield purchase (M, H on the five names: Pure Nintendo review and a Wii U database summary agree on the names Sentry, Bomber, Cloud, Drone, Helper). Roles only; no figures were published:
1. Sentry: stationary, standard speed, range and power, fires in any direction within range. (M)
2. Bomber: stationary, long range, hits hard, fires slowly, has a blind spot beside itself. (M)
3. Cloud: stationary, very large range, rapid fire, low power; a wearing-down weapon, not a main defence. (M)
4. Drone: mobile, patrols the edges of the map quickly hunting enemies; strong, fast, expensive. (M) A second player can control a Drone. (L-M: search summary; Steam remake also lists Drone and Railgun bots for co-op, M.)
5. Helper: support, no damage, slows enemies and briefly freezes them. (M)
6. Shield: adds to or increases the base shield; its price rises every time it is bought. (M: two sources)
- All Bots can be upgraded for power, range, speed and other stats. (M: Pure Nintendo.) Number of upgrade levels: UNKNOWN. Upgrading is the main way to grow stronger because build spots are limited. (M: Family Friendly Gaming)
- Each Bot has a settable target priority (for example weakest or strongest enemy). (M: Pure Nintendo.)
- Placement: simple; limited number of spots to place defences. (M: Family Friendly Gaming.) Whether spots are fixed pads or a free grid is UNKNOWN. Drone "patrols the edges of the map" suggests a bounded arena. Enemy path shape (lanes versus free approach) UNKNOWN.

### 2.4 Specials / power-ups
Four items, earned after each level or by destroying a special enemy (M: Pure Nintendo; Time Extension summary agrees on the count):
- Two common: a money multiplier and a score multiplier; they can stack.
- Two rare: a clear-the-screen bomb (all enemies destroyed) and a health pack (restores up to 50% of base health; one source words it as increasing the health of each base).
Drop rates, durations and stack caps UNKNOWN. A "special enemy" exists (carrier-like). (M)

### 2.5 Enemies
- Described as asteroid-style ships that attack the base. (M: Pure Nintendo.) They get tougher: more health, higher speed as levels progress. (H.) Enemy type roster, special behaviours, bosses: UNKNOWN. A "special attack" every five levels on Hard exists (see 2.6).

### 2.6 Difficulty
- Original: Easy, Hard, Endless. (H: Family Friendly Gaming, Nintendo World Report mentions an endless mode.)
- Hard: enemies deal 5 damage per hit and every five levels a fast-spawning special attack occurs. Easy: enemies deal 4 damage per hit and the player starts with 25% more building cash. (M: Pure Nintendo.) Base health total: UNKNOWN.
- Remake: five modes, Cadet, Captain, Galactic Hero, Insanity, Endless. (H: Steam, Keymailer.)

### 2.7 Maps and campaign structure
- Original: six maps ("levels" in the reviews), each needing a different strategy. (H: three reviews.)
- Remake: over 20 custom maps (Steam), a demo with 8 of 24 per the developer (L: search summary of a ModDB post, page blocked), "indie crossover" maps themed on other indie games, maps and concept-art pages unlocked by progress, achievements. (M)
- Is there a fixed story campaign order? UNKNOWN; unlock-by-progress is stated.

### 2.8 Co-op
- Original: co-op for up to five players (M: Nintendo World Report), via Wii Remotes; one source says each extra player controls their own Drone or Sentry. One 2014 database listing still says 1 player, so co-op may have arrived with the later update (Nintendo Life headline: a new update adds loads of features; L). (M overall, timing L.)
- Remake: local co-op, multiple players controlling Drone and Railgun bots; shared / split-screen and Remote Play Together tags. (M: Steam.)
- Rules for shared money, friendly fire, revive: UNKNOWN.

### 2.9 Controls and UI
- Wii U: touch screen handles game speed (including pausing), weapon use and navigating the purchase and upgrade menus. A how-to-play help is reachable from the main screen, top right. (M: NWR, FFG.)
- Layout specifics, hotkeys, Wii Remote aiming: UNKNOWN. Remake supports controller (Steam tag).

### 2.10 Scoring and leaderboards
- Points per ship plus a points multiplier special. In the remake the score depends on how long you survive; leaderboards and achievements exist. (M: Steam.) Original local/online leaderboards: owner says yes; we found no source (UNKNOWN). Score formula UNKNOWN.

### 2.11 Presentation (recorded for contrast only; none of it is reproduced)
Doodle / hand-drawn look; techno-inspired soundtrack praised. The remake draws everything with real crayons. We do the opposite: clean neon vector art, our own synthesised audio.

## 3. What reviewers criticised (design inputs)

| Criticism | Source | Our response |
|---|---|---|
| Touch controls unresponsive, several taps; lost health because the player could not pause fast enough to reach the upgrade menu | Nintendo World Report | Instant pause, 56 dp targets, build/upgrade while paused, 1x/2x/3x speed |
| Graphics basic | NWR, FFG | Full original art pass (boards) |
| Only six levels for the price; light content | FFG | 12 maps, 5 tiers, Endless, co-op, leaderboards |
| Limited placement spots | FFG | 12 to 22 nodes per map; Sandbox open placement |
| Levels can be long; unclear if you won or lost | FFG | Explicit Victory / Defeat banners, wave goal visible, call-early, 3x speed |
| Unoriginal, lacks variety versus modern TD | Nintendo Life (via search summary) | 6 enemy types, boss every 10th wave, 12 different lane shapes, Specials |
| No content rating despite shooting and explosions | FFG | Stylised sci-fi, no gore; rating note in SPEC; kids-safe copy |
| Positive: charming, thoughtful, fine for short sessions (7/10); music good | Pure Nintendo, NWR | Keep short-session pacing (Cadet 20 waves) |

Scores seen: Pure Nintendo 7/10, Nintendo World Report 5.5/10, Nintendo Life 4/10 (via search summary).

## 4. Confidence table

| Item | Confidence | Basis |
|---|---|---|
| Single currency from kills + round bonus | H | Wiisworld, Pure Nintendo, Steam |
| Five bot roles (names/roles) | M-H | Pure Nintendo; database listing |
| Shield price rises per purchase | M | Pure Nintendo, Time Extension listing |
| Four specials and their effects | M | Pure Nintendo |
| 4/5 damage per hit, 25% cash, swarm every 5 (Hard) | M | Pure Nintendo only |
| Waves start ~20 weak enemies, grow and strengthen | H | Wiisworld, NWR |
| Per-bot target priority | M | Pure Nintendo |
| Six original maps, 20+ in remake | H | reviews, Steam |
| Five remake modes | H | Steam, Keymailer |
| Limited build spots | M | FFG |
| Touch UI for speed/pause/menus | M | NWR |
| Co-op to five, extra players pilot a bot | M | NWR + summary |
| Co-op arrived via later update | L | headline only |
| Leaderboards in remake | M | Steam |
| Score formula, base HP, upgrade levels, enemy roster, boss, path shape | none | UNKNOWN |
| Flash original | none | UNVERIFIED |

## 5. Unknowns (to close if the owner wants closer fidelity)

1. Exact numbers: bot costs, damage, ranges, health, wave sizes, upgrade counts and prices, base health, drop rates.
2. Are build spots fixed pads or a free grid? Do enemies follow lanes or fly free?
3. Full enemy roster and any bosses.
4. Co-op specifics: shared money? Pilot bot controls? Wii Remote aiming model?
5. Score formula and leaderboard shape (local/online).
6. Whether maps are strictly ordered; what the remake unlock thresholds are.
7. Wii U GamePad layout and whether the TV shows only the arena.
8. Railgun and the other remake weapons (only Drone and Railgun are named).

Recommended way to close them without breaching the originality rule: the owner (or a tester) plays the game and writes a short mechanics checklist (numbers and behaviours only, no screenshots committed). We have not watched gameplay footage frames; video pages could not be read.

## 6. Source list

- Nintendo World Report review (5.5), https://nintendoworldreport.com/review/37990/zacisas-last-stand-review (fetched)
- Family Friendly Gaming review, https://www.familyfriendlygaming.com/Reviews/2014/ZaciSas%20Last%20Stand.html (fetched)
- Pure Nintendo review (7/10), https://purenintendo.com/review-zacisas-last-stand/ (fetched)
- Wiisworld eShop listing, https://www.wiisworld.com/wii-u-eshop-games/ZaciSa's-Last-Stand.html (fetched)
- Steam store page for the remake, https://store.steampowered.com/app/2462890 (fetched)
- Nintendo Life review (4/10) and news items, https://www.nintendolife.com/reviews/wiiu-eshop/zacisas_last_stand , https://nintendolife.com/news/2015/10/zacisas_last_stand_is_getting_a_new_update_that_adds_in_loads_of_new_features (blocked, 403; used only via search-result summaries)
- Time Extension listing, https://www.timeextension.com/games/wiiu-eshop/zacisas_last_stand (blocked; search summary only)
- Keymailer entry (modes, achievements), https://keymailer.co/g/games/57d5224e (search summary only)
- ModDB developer posts (demo, reveal), https://www.moddb.com/news/tower-defense-demo-now-available (blocked; search summary only)
- Co-Optimus listing, https://www.co-optimus.com/game/8267/nintendo-wii-u/zacisa-s-last-stand.html (blocked)
- Nintendo Everything footage post, https://nintendoeverything.com/more-zacisas-last-stand-footage/ (page had no body text)
- Wayback Machine, Metacritic user pages: not retrievable by the fetch tool. YouTube trailer pages: titles only.
