# Audit 01: Gaps in features and activities (adults and kids)

Scope: read-only audit of Z:\GameSuite-wt-game-kit (branch session/wave-3 working tree), date 2026-11-07.
Method: read docs/roadmap/*.md, docs/roadmap/UNBUILT-ITEMS.md, core/GameHubSpecs.kt, core/GameCatalog.kt, core/Achievements.kt,
core/ClubProfile.kt, core/Streaks.kt, core/CpuLevel.kt, settings/AppSettings.kt, stats/GameStats.kt, ui/MainMenuScreen.kt,
ui/StatsScreen.kt, ui/party/*, per-game *HubSpecs.kt, res/values/*.xml, plus targeted greps. Nothing was built, run, or edited.

Legend. "V" = verified by reading code/docs. "G" = guess/inference, not proven. Severity H/M/L. Effort S (<2 days), M (~1 week), L (>1 week).
Paths are relative to app/src/main/java/com/gamesuite unless noted.

---------------------------------------------------------------------------------------------------

## 1. Verified facts (what exists)

### 1.1 Game inventory (V: counted from `gameGlyph()` in core/GameCatalog.kt, 76 ids, cross-checked against *HubSpecs.kt)
76 ids = 75 games + 1 toolkit (`party-toolkit`). Hub families (`HubFamily`, core/GameHub.kt:17; display order `catalogFamilyOrder`, GameCatalog.kt:47):

| Family (as shipped) | Count | Game ids |
|---|---|---|
| BOARD | 20 | chess, checkers, reversi, mancala, connect-four, dots-and-boxes, tic-tac-toe (+misere, wild routes), dominoes, word-tiles, yahtzee, backgammon, gomoku, hex, nine-mens-morris, hare-and-hounds, shogi, mini-shogi, chinese-checkers, ludo, pig-dice |
| CARD | 11 | uno, solitaire, blackjack, war, texas-holdem, president, sevens, speed, matching, spider-solitaire, hanafuda |
| WORD | 4 | hangman, word-search, crossword, word-guess |
| ARCADE_PUZZLE | 40 | logic puzzles (17): sudoku, kenken, kakuro, nonogram, minesweeper, lights-out, sliding-puzzle, color-flood, edge-match, mastermind, flow-lines, mahjong-solitaire, game-2048, snake, breakout, tower-defence, air-hockey. Toy sports (15): darts, bowling, carrom, curling, billiards, mini-golf, toy-soccer, toy-tennis, toy-boxing, toy-baseball, slot-cars, fishing, tank-battle, shooting-gallery, six-ball-puzzle. Party pack (8): tap-race, tug-of-war, reaction-duel, whack-a-mole, light-trail, sumo-push, paddle-duel, rps-showdown |
| TOOLKIT | 1 | party-toolkit (dice, spinners, timers, score keeper, team splitter, first-player, card dealer, turn order, round tracker: ui/party/*Tool.kt) |

Observations (V):
- Only 5 families. ARCADE_PUZZLE is a 40-game bucket mixing logic puzzles, sports sims, action and party games. There is no Puzzle / Sports / Party / Kids / Quick / Solo filter (`filterGames` filters only by text and family, GameCatalog.kt:21).
- Count vs benchmark: Clubhouse Games 51 Worldwide Classics ships 51 "games" (it counts variants, e.g. chess/shogi/mini-shogi split). GameSuite lists 75 titles, so raw breadth beats it. Per category: board 20 (Clubhouse ~20 board/dice), card 11 (Clubhouse ~12), toy sports 15 (Clubhouse ~9 arcade/sports). The gap to Clubhouse is NOT count; it is depth/polish/meta-layer (see sections 3 and 4). CLUBHOUSE-PARITY.md itself states the count goal (51+) is met.
- Networked (Nearby/Online) games: `GameHubSpecs.networkedGames` (core/GameHubSpecs.kt:44) = 42 ids. NOT networked: yahtzee, backgammon, game-2048, snake, all solo puzzles, solitaire, spider-solitaire (listed under spider hub but see list: spider+hanafuda; spider is not in networkedGames), hangman, word-search, crossword, word-guess, the 8 party-pack games, air-hockey, breakout, tower-defence, mahjong, party-toolkit (UNBUILT-ITEMS.md section B confirms party pack is same-screen only).
- Spectating: only UNO (`spectatorGames`, GameHubSpecs.kt:36).
- Daily modes (`HubModeKind.DAILY`): 15 games: word-search, word-guess, sudoku, kenken, kakuro, nonogram, minesweeper, lights-out, sliding-puzzle, color-flood, edge-match, mastermind, flow-lines (all via `puzzle()` helper, GameHubSpecs.kt:116), plus six-ball-puzzle (SixBallPigHubSpecs.kt:46) and spider-solitaire (SpiderHanafudaHubSpecs.kt:49). No daily for crossword, hangman, solitaire, 2048, mahjong, matching, any board game.

### 1.2 Progression / stats / competitive layer (V)
- Achievements: exactly 10 (core/Achievements.kt:45-59): first_win, wins_10, wins_100, streak_5, hard_win, daily, all_families, online_win, days_7, games_10. All are count thresholds. No per-game, per-difficulty, skill, or collection achievements. The file itself notes "beat the CPU in under N moves" is absent because the store keeps no move counts.
- Stats model (stats/GameStats.kt): per game only matchesPlayed, wins, losses, draws, lastPlayed, winStreak, bestWinStreak, winsOnHard, onlineWins, dailiesSolved. No best score, best time, average, per-difficulty split, match history, time played, rating. (`GameStats` KDoc defers per-game bests to each game's own store; only Sliding Puzzle has one.)
- Stats screen (ui/StatsScreen.kt): sort chips (Recent / Most played / A-Z), summary tiles, streak header, achievements list. No charts, no filters, no per-game drill-down.
- Day streak (core/Streaks.kt): consecutive-days streak with "at risk" nudge (`atRiskOn`), shown on Home (`HomeProgressNotes`, MainMenuScreen.kt:273). One global streak; no per-game streaks beyond win streak.
- CPU levels: UI offers Settings default / Easy / Medium / Hard (`HubOptions.difficulty()`, core/GameHub.kt:306). `CpuLevel.EXPERT` exists but is deliberately NOT offered because no engine reads it (comment at GameHub.kt:313). So "strong CPU" ceiling is Hard everywhere.
- Ranked/competitive: none. Online matches record only `onlineWins`. `OnlineRatingStore` / `QuickMatchScreen` (S8) are listed as unbuilt (UNBUILT-ITEMS.md A). No Elo, no leaderboards, no tournaments or brackets (greps for tournament/leaderboard/ladder in app code found only unrelated engine uses of "bracket"; not in docs/roadmap).
- Unlockables: CLUBHOUSE-PARITY.md Wave 2 promises "unlockables (card decks / board themes via achievements)". Grep for unlock logic found nothing (only lobby "Start unlocks" and RPS lock/unlock). `NamedTheme.FELT_TABLE` is an enum slot with no palette (settings/AppSettings.kt:23). So rewards for achievements are text-only. (V)
- Club layer: 16 original critter avatars x 8 Okabe-Ito colours x 4 frames (core/ClubProfile.kt), display name, favourite game, per-game coach card auto-shown once (`shouldAutoCoach`). Avatars are never a reward; all free from the start.
- Home screen (ui/MainMenuScreen.kt): rows Today (daily), Continue, Favourites, Recent, Browse Games; a one-time onboarding banner (`onboarding_title/body`, res/values/strings.xml:20-21) which is a single paragraph of text, dismissible.

### 1.3 Session length / pacing options (V)
- Chess clock (3+0, 5+0, 10+0, 15+10): only chess (GameHubSpecs.kt:263). Turn timer promised for UNO/Word Tiles/Dominoes in docs/SETTINGS_THEMING_ACCESSIBILITY.md; no hub option found for them (G: not exhaustively grepped per screen).
- Size/length choices exist per game via Hub options (e.g. Matching 12/20/30/40 cards, Mancala stones/pits, Connect Four size, Gomoku/Hex board size, UNO target score and hand size, Mahjong layouts, 2048 grid size, Snake options). Good adult-facing variety.
- Global `GameSpeed` SLOW/RELAXED/NORMAL/QUICK (settings/GameSpeed.kt) scales CPU think time. Labels are English-only (UNBUILT-ITEMS.md C).
- Save/resume exists for most games (`resumable = true` default; Breakout, Tower Defence, Air Hockey, Snake, Slot-style action games are `resumable = false`).

### 1.4 Accessibility signals relevant to older users (V)
- Settings -> Accessibility: Reduced motion, Colorblind-safe mode, High Contrast palette, Text size slider (`textScale`), Card size slider (`cardSizePreference`), Game speed, Haptics toggle (res/values/settings_stats_strings.xml). Per-game colour+shape cues (colorblind code in ~6 game packages by grep count).
- Quality bar mandates 48dp targets, TalkBack labels, large-font safety (docs/roadmap/QUALITY-BAR.md gate 5), but UNBUILT-ITEMS.md G says live TalkBack, reduced motion on party games, and one-switch/relaxed modes were only code-read, never device-verified; Hub setup Start button unreachable in landscape at 1.6x font (UNBUILT C).
- No text-to-speech / read-aloud of rules or clues (grep for TextToSpeech found none). No dyslexia font option (it is only a suggestion in docs/SETTINGS_THEMING_ACCESSIBILITY.md section 4).

### 1.5 Kid-specific features (V: absence)
Grepped java + res for kid / child / parental / parent-control / COPPA / toddler / bedtime / screen-time / play-time-limit / age group. Only hits were code variables (e.g. `parent[]` in graph search, Hanafuda "parent" dealer). Therefore there is NO:
- kids mode, age profile, or age-gated game list;
- parent gate, PIN, play-time limit, bedtime, session reminder;
- reading-free hub (every card shows title + tagline text; `HubFamily` labels are text; tips are text);
- sticker/reward collection, kid-oriented rewards;
- drawing, colouring, creative, or music-making activity;
- math-practice, spelling-practice, early-reading, shapes/colours/counting games (Sudoku is 9x9 only: `snap.cells.size != 81` in games/sudoku/SudokuGame.kt:219; KenKen/Kakuro are adult logic puzzles; no 4x4/6x6 kid sudoku seen);
- kid-curated content: Hangman draws raw `WordDictionary` words (EASY = 4-5 letters sampled from the full dictionary, games/hangman/HangmanGame.kt:156) with no categories or picture hints and no profanity/age filter visible; Crossword clue bank is adult style (CrosswordClueBank.kt).
Privacy/safety signals (V): manifest requests INTERNET, Nearby/Bluetooth/Wi-Fi permissions. No ad or analytics SDK found by grep of Gradle files (no admob / firebase / billing matched), which is a genuine "no ads" fact, but it is not stated anywhere in-app. Online play needs a user-entered relay URL (`onlineServerUrl`, empty by default) so online is effectively off until configured. Quick-chat (server built, client unbuilt) is a preset-phrase design (UNBUILT A), not free text: good for kids, but not yet wired. Free-text chat: none found. Player names are free text typed by the user (PlayerNameStore, ui/PlayerNamesDialog.kt), which is the only user-text vector shown to other players in Nearby/Online lobbies (G: verify filtering).

### 1.6 Family / same-screen experience (V)
- Pass & Play exists on essentially every multi-seat game (`HubModeKind.PASS_AND_PLAY`, `HubStart.READY_SCREEN` hand-off screen). UNO supports up to 6 humans + 8 CPUs, teams mode.
- Party Toolkit (dice, spinner, timers, score keeper, team splitter, card dealer, turn order, first player, round tracker) and 8 party-pack games for 2-4 players on one device.
- Fold/tablet layout and tabletop posture work were done (memory notes), but "tablet-sized layout pass for 3-4 player party games" is still open (UNBUILT E).
- Co-op (all players on one team vs the game): essentially absent. Tower Defence, Breakout, Fishing, Mini Golf are solo or competitive. Only UNO Teams and team splitter are team features (V by specs; G that no other co-op exists).

---------------------------------------------------------------------------------------------------

## 2. Per-category suitability matrix

Age bands: A = adults (13+/strategy), K2 = kids 8-12, K1 = kids ~4-7. Rating: strong / ok / weak / no. Reading load: text needed to play (L low, M medium, H heavy). "Kid-ready?" is judgement (G) based on rules complexity and UI text, not on playtests.

| Category / games | Adults | K2 (8-12) | K1 (4-7) | Reading | Notes and gaps |
|---|---|---|---|---|---|
| Classic 2p abstract: chess, checkers, reversi, shogi, mini-shogi, backgammon | strong | ok (checkers, reversi, connect-four easy) | weak (checkers maybe) | L | Strong adult depth; no puzzles/lessons/openings trainer; CPU ceiling Hard; no ratings |
| Simple 2p: tic-tac-toe, connect-four, dots-and-boxes, gomoku, mancala, hex, nine-mens-morris, hare-and-hounds | ok | strong | tic-tac-toe, connect-four ok | L | Best kid base. No "kid-easy" CPU (Easy is "human-feeling", not "lets the child win") |
| Family race/dice: ludo, chinese-checkers, pig-dice, yahtzee | ok | strong | ludo/pig ok | L (yahtzee M) | Ludo is the best K1 game; Yahtzee needs scoring literacy |
| Card party: uno, war, sevens, speed, president, matching | ok | strong | war, matching ok | L | Matching 12-card minimum, critters theme (games/speed/SpeedMatchingHubSpecs.kt) is the only memory game |
| Card gambling: blackjack, texas-holdem | strong | no | no | M | Adult only, no age gate; fake chips; hold'em has no tournament mode (grep) |
| Solitaires: solitaire, spider-solitaire, mahjong-solitaire, hanafuda | strong | weak | no | M | Hanafuda needs heavy culture/rules reading |
| Word: hangman, word-search, word-guess, crossword, word-tiles | strong | ok | no | H | No kid word lists, no picture words, no sight-word mode, English-first; Word Guess uses the real NYT-style answer list (adult) |
| Logic puzzles: sudoku, kenken, kakuro, nonogram, minesweeper, lights-out, sliding-puzzle, color-flood, edge-match, mastermind, flow-lines, six-ball | strong | ok (lights-out, sliding, color-flood, flow-lines, nonogram small) | weak | L-M | 13 dailies; no tiny-grid or picture-based kid sizes; no explained-hint tutor |
| Arcade solo: 2048, snake, breakout, tower-defence | ok | ok | weak | L | Not resumable (breakout/snake/TD), no high-score tables (stats keep none) |
| Toy sports: darts, bowling, carrom, curling, billiards, mini-golf, toy-soccer, toy-tennis, toy-boxing, toy-baseball, slot-cars, fishing, tank-battle, shooting-gallery | ok | strong | ok (bowling, fishing, mini-golf) | L | Strong family/same-screen; shooting/tank/boxing themes (G: check tone) |
| Party pack: tap-race, tug-of-war, reaction-duel, whack-a-mole, light-trail, sumo-push, paddle-duel, rps-showdown | ok | strong | strong (tap-race, whack-a-mole, tug-of-war) | L | Same-screen only; no phone-as-controller; CPU levels lopsided (UNBUILT C); touch holes ~26dp in landscape |
| Toolkit: party-toolkit | strong (game night) | ok | ok | L | No kid mode; has no "game night" planner |
| Creative / educational (drawing, colouring, music, math, spelling, geography, trivia) | no | no | no | - | NONE shipped (V) |

Counts of games that are plausibly K1-suitable today (G): about 12 (ludo, tic-tac-toe, connect-four, war, matching, pig-dice, tap-race, tug-of-war, whack-a-mole, bowling, fishing, mini-golf). K2-suitable: about 45.

---------------------------------------------------------------------------------------------------

## 3. Gap list: ADULTS

A-1. No ranked / competitive ladder
- Missing: ratings (Elo/Glicko), leaderboards, seasons, matchmaking (S8 Quick match unbuilt, UNBUILT A); online result is just `onlineWins`. Chess has a clock but no rating, no puzzles, no opening trainer, no analysis/review.
- Who: adult. Severity: H. Effort: L (rating store M, quick match M, leaderboard needs server L).
- Fix: build `OnlineRatingStore` + `QuickMatchScreen` per the Wave 5 design (docs/roadmap/WAVE-5-MULTIPLAYER-DESIGN.md), add local per-game "rating vs CPU level" (Easy..Hard ladder) as an offline stand-in; add chess puzzles and post-game review.

A-2. CPU ceiling and variety is shallow
- Missing: Expert level never exposed (`HubOptions.difficulty()`, GameHub.kt:313 comment; `CpuLevel.EXPERT` unused); CPU personalities/opponent characters (Clubhouse has none, but adults like "styles"); handicap/odds options; adaptive difficulty ("beat me at my level").
- Who: adult. Severity: H for chess/shogi/checkers/backgammon. Effort: M per engine (deeper search, opening books).
- Fix: wire `isExpert` into chess, shogi, checkers, reversi, backgammon, then re-add the Expert choice; add "Adaptive" level using win-streak per game (data exists: `GameStats.winStreak`).

A-3. Statistics are thin
- Missing: best scores/times, per-difficulty record, win rate trend, match history, time played, favourite game by hours, per-game stats drill-down, export. Solo games (2048, snake, sudoku, mahjong, minesweeper, breakout, tower-defence, darts, bowling) have no persistent high score visible in the shared stats (V by GameStats; G for individual stores).
- Who: adult (and kids' motivation). Severity: H. Effort: M.
- Fix: extend `GameStats` with `bestScore/bestTimeMs/lastN results/timePlayed/perDifficulty` (additive serialization defaults are already the pattern), add per-game detail sheet and a Home "records" card.

A-4. Progression is a flat 10-achievement list with no reward
- Missing: unlockables (decks, boards, avatars, themes) promised in CLUBHOUSE-PARITY.md Wave 2 and never built (grep V); XP/level; per-game and per-family achievements; "mastery" tiers; `FELT_TABLE` theme still a stub.
- Who: both. Severity: H. Effort: M.
- Fix: add reward slots (board themes, card backs, avatar props gated by achievement ids), 40-80 achievements (per-game "win on Hard", "daily x7", "perfect game"), a Collection screen.

A-5. Daily challenges are siloed and partial
- Missing: only 15 of 75 games have a daily; no combined "Daily Challenge" playlist or calendar; no per-game daily streak; no shareable result card (Wordle-style) except implicit; crossword, hangman, matching, solitaire, 2048, chess puzzles have none. `dailiesSolved` is only a counter.
- Who: adult. Severity: M. Effort: S-M.
- Fix: "Today" tab listing all dailies with done/not done, a combined daily bundle (3 puzzles) with its own streak and badge, share-result text, dailies for solitaire (seeded deal), crossword, 2048, matching.

A-6. No tournaments or structured multi-game play
- Missing: tournament brackets, round-robin, "best of N" series, multi-game match nights, score-across-games leaderboards for a group. Hold'em has no tournament/blind-increase structure (G; grep for tournament in holdem hit only generic code).
- Who: adult/both. Severity: M. Effort: M.
- Fix: "Game Night" mode: pick players (Party roster, `PartyRoster.kt`), pick N games, auto-run, aggregate points, podium. Reuse `PartyScoreKeeperTool` and `PartyTeamSplitLogic`.

A-7. Game-night hosting is a toolkit, not an experience
- Missing: persistent rosters across games exists (`PartyRoster`) but no cross-game scoreboard; no host dashboard; no game recommender by headcount/time ("we are 4 people, 30 minutes"); party pack can't be networked; no phone-as-controller; no TV/cast mode.
- Who: adult/family. Severity: H. Effort: M-L.
- Fix: "Plan a game night" wizard (players, time, age mix) -> suggested games from specs (`minSeats/maxSeats`, add `typicalMinutes`, `ageMin` fields); networked party pack (UNBUILT B); phone-as-controller for tablet-as-table.

A-8. Online relay and social layer incomplete
- Missing: relay not deployed (owner must choose host, UNBUILT A); room code share/copy and deep link; quick chat client; ready check; remove player via kick frame; friends list; spectators for all but UNO; no voice (owner: last). Yahtzee and Backgammon have no networking at all; TicTacToe/Chess guest sync gaps.
- Who: adult. Severity: H. Effort: L.
- Fix: complete S1-S5 in Wave 5 doc, deploy relay, then Yahtzee/Backgammon netcode.

A-9. Strategy depth tooling absent
- Missing: hints explain nothing (GameChrome `gc_hint` is a menu item only); no "why was that a mistake" feedback; no replay/move list/export (PGN/SGF), no analysis board; no puzzles-per-game training (chess tactics, checkers, mancala, reversi, backgammon pip count).
- Who: adult. Severity: M. Effort: M-L.
- Fix: expose engine eval as optional post-game "blunder summary"; PGN export for chess; tactics puzzle mode.

A-10. Accessibility for older users unproven and incomplete
- Missing: TTS read-aloud of rules/clues; dyslexia-friendly font; one-tap "Large mode" preset (big text + big cards + slow speed + high contrast + no timers); verified TalkBack; turn timers off is implicit but no global "never time me" switch; Hub Start button unreachable at 1.6x font landscape (UNBUILT C); Fold book posture unverified (UNBUILT E, G).
- Who: adult 65+. Severity: H. Effort: M.
- Fix: "Easy Reading" preset in Settings -> Accessibility; fix Hub bottom bar insets; device TalkBack pass; font option.

A-11. Session length is player-managed only
- Missing: no "quick game" tag, no per-game estimated minutes, no "short/medium/long" length option for most boards (Chess clock only, Matching sizes, Mancala variants), no auto-pause/auto-save prompt for non-resumable action games (Breakout, Snake, Tower Defence), no "play for 5 minutes" session mode.
- Who: adult. Severity: M. Effort: S.
- Fix: add `minutes` to `GameHubSpec` and show on cards; add "Quick/Standard/Long" presets to `HubOptions`; make action games resumable or warn on exit.

A-12. Solo-vs-CPU variety within the CPU slot
- Missing: campaign / challenge ladders ("beat 5 opponents to unlock"), scenario puzzles (checkmate in 2, mancala endgames), handicap modes. Solo play is only free-play + dailies.
- Who: adult. Severity: M. Effort: M-L.
- Fix: per-game "Challenges" mode (10-20 scripted positions) built from engine test fixtures.

A-13. Localization limits reach
- Missing: Spanish incomplete for many in-game screens (UNBUILT D); GameSpeed labels English; lobby problem messages English; no other locale. Only values-es exists (res/values-es, 96 files vs 97 in values/).
- Who: both. Severity: M. Effort: M.

---------------------------------------------------------------------------------------------------

## 4. Gap list: KIDS (K1 ~4-7, K2 ~8-12)

K-1. No Kids Mode at all
- Missing: a kids profile/age setting that (a) filters the library to age-appropriate games, (b) simplifies the hub, (c) turns off gambling-themed games (blackjack, Texas Hold'em), (d) disables online/Nearby free-text, (e) hides stats pressure (streak "at risk" nudge). Nothing exists (V).
- Who: kid (and parents). Severity: H. Effort: M.
- Fix: add `AgeBand` to `ClubProfile` (core/ClubProfile.kt) and `ageMin`/`ageMax` + `kidSafe` to `GameHubSpec`; a "Kids" shelf in `catalogFamilyOrder`; `filterGames` gains an age predicate.

K-2. No parental controls or play-time limits
- Missing: parent gate (PIN/math gate), daily time limit, bedtime window, per-game allow-list, online on/off lock, "ask before network". Settings only has global toggles anyone can flip, including `onlineServerUrl`.
- Who: kid/parent. Severity: H. Effort: M.
- Fix: Settings -> Parents section behind a gate; session timer service with gentle wind-down; lock Nearby/Online/Online server field when kid profile active.

K-3. No reading-free UI for K1
- Missing: icon-only game picker, voice prompts (no TTS), large pictorial menu, no text on win/lose (ResultFeedback strings), onboarding is a paragraph (strings.xml:21). Game cards show emoji glyphs (`gameGlyph`) which helps, but modes/options are text sliders and chips.
- Who: K1. Severity: H. Effort: M-L.
- Fix: Kids Home with big picture tiles (reuse glyphs and critter avatars), TTS for rules and results, option pickers collapsed into 1-2 big buttons with defaults.

K-4. No kid-appropriate learning/educational games
- Missing: math (addition/subtraction/multiplication, number bonds), early reading/spelling, shapes/colours/counting, memory beyond one Matching game, logic for 4-7, geography/trivia, typing. Nearest items: Matching (critters), Mastermind, Lights Out, Sliding Puzzle, Color Flood, Hangman (raw dictionary), KenKen (arithmetic cages, adult-hard).
- Who: both kid bands (K1 highest). Severity: H. Effort: L (several new modules; follow house pattern in CLUBHOUSE-PARITY.md rules: GameModule + ResumableGame + hint + tests).
- Fix (ordered by ROI): (1) Kid Sudoku 4x4/6x6 with pictures, (2) Number-bond/maths facts game with level ladder, (3) Spelling Bee/Word Builder with picture words, (4) Memory game variants (6/8 cards, sequence memory "Simon"), (5) Shape/colour sorter, (6) Simple maze, (7) Trivia packs, (8) Connect-the-dots and Jigsaw.

K-5. No creative activities
- Missing: drawing/colouring, sticker book, music maker, build/design. Nonogram reveals a picture; Party Toolkit is utility. Nothing creative.
- Who: kid. Severity: M. Effort: M.
- Fix: Colouring book (flood-fill, uses `ColorFloodScreen` components?), Sticker Book as the reward target for K-6.

K-6. No kid reward system
- Missing: stickers, trophies, collectible critters, "star per game", gentle encouragement. Achievements are text lines with numeric goals (10 total) and `home_new_unlocked` snackbar is text.
- Who: kid. Severity: H. Effort: M.
- Fix: Sticker Book earned per game (first play, first win, try a new category), no loss penalties; avatar props/frames as rewards (avatar parts already modelled: `AvatarProp`, `AvatarFrame`).

K-7. Difficulty is not child-calibrated
- Missing: "Kid" CPU level that visibly lets the child win or plays randomly 50%; hint button that actually teaches; undo everywhere; no timers/penalties for K1; Easy in some games is still hard (Paddle Duel Easy too hard, UNBUILT C; Toy Tennis lopsided).
- Who: kid. Severity: M. Effort: S-M.
- Fix: a `CpuLevel.KID` (random-with-bias) the Kids profile applies automatically; fix Paddle Duel/Toy Tennis tuning.

K-8. Content safety assumptions not surfaced
- Missing: in-app statement of "no ads, no tracking, no purchases" (true by Gradle grep, G for transitive deps), data page, parent info, privacy policy link in About; Nearby/Online lobbies show free-text player names and (when wired) quick chat; need name filtering and "friends-only/room-code-only for kids" copy. Hangman/WordSearch use a full dictionary with no content filter or kid list (V for Hangman).
- Who: kid/parent. Severity: H (store family policies). Effort: S-M.
- Fix: Settings -> About -> Privacy and Safety page; name profanity filter in `cleanPlayerName` (core/PlayerNameStore.kt path); kid word list for Hangman/Word Search; disable Online/Nearby in Kids profile.

K-9. Co-play parent+child is only same-device pass and play
- Missing: co-op games (team vs game), handicap/assist toggles for the adult (play with a "helper" level), "teach me" mode that narrates the rules, split-screen real-time (only party pack). Coach cards (`CoachCard`) are first-run text, once.
- Who: both. Severity: M. Effort: M.
- Fix: per-game "Learn with me" tour using coach text + TTS; co-op modes for Tower Defence, Mini Golf, Fishing; handicap options in two-player boards.

K-10. Kid-hostile themes or mechanics (G, needs human review)
- Missing/uncertain: shooting-gallery, tank-battle, toy-boxing, light-trail, sumo-push framed as toys; blackjack and hold'em are gambling-simulating with fake chips (V: games exist and are un-gated). Streak "at risk" nudge on Home (V) can pressure kids.
- Who: kid. Severity: M. Effort: S.
- Fix: gate blackjack/hold'em behind adult profile; kid profile hides streak-at-risk and daily nudges; rate each game (`ageMin`).

K-11. Replay value for kids is low
- Missing: new-content cadence (seasonal events, themed critter packs), unlock-driven goals, collection (see K-6), "try something new" suggestions by category. Hub shows Today/Continue/Favourites/Recent only.
- Who: kid. Severity: M. Effort: M.

---------------------------------------------------------------------------------------------------

## 5. Cross-cutting gaps (both audiences)

X-1. Navigation by purpose is weak: no filters for Solo / 2-player / Party / Quick / Brain / Sports; only text search and 5 families (V). Effort S-M. Severity M. Fix: tag system on `GameHubSpec` (`tags`, `minutes`, `ageMin`), chips on GamesScreen.

X-2. Onboarding is a single text banner (V: MainMenuScreen.kt:357). No interactive tour, no "pick your profile (adult/kid/older)" first run, no "who is playing?" setup, no suggestion of first games. Severity M. Effort S-M. Fix: 3-step first-run (name+avatar+age band+comfort preset) feeding Kids/Large presets.

X-3. Multi-profile / household: single `ClubProfile` (core/ClubProfileStore.kt) and single global stats; two siblings or parent+child share one record and streak. Severity H for family use. Effort M. Fix: multiple profiles with their own stats, avatar, achievements, favourites; profile switcher on Home.

X-4. Unverified-on-device risk: UNBUILT G lists Nearby on any hardware, real tablet/Fold, TalkBack, reduced motion on party games as never verified. Severity H for confidence. Effort M (testing). Not a feature gap but limits how much of the above can be promised.

X-5. Content parity vs Clubhouse: its strengths are (a) coherent per-game rules coach, (b) global online with wifi/friends, (c) polish. Ours has coach cards for most games (V) and 75 titles, but online relay is not deployed (UNBUILT A), so Clubhouse's headline advantage (online play with anyone) is unmatched in practice. Missing Clubhouse-style titles/variants (G, from public knowledge): Go, Xiangqi, Battleship, Quoridor, Hearts, Spades, Cribbage, Gin Rummy, Canasta, Backgammon variants, Sudoku variants beyond daily. The roadmap lists Go, Xiangqi, Battleship, Quoridor, Hearts, Spades as candidates the owner has not chosen (UNBUILT B).

---------------------------------------------------------------------------------------------------

## 6. Content breadth versus Clubhouse Games 51 (benchmark, G for the Nintendo side)

| Area | Clubhouse (public knowledge) | GameSuite shipped (V) | Verdict |
|---|---|---|---|
| Total | 51 | 75 titles | ahead on count |
| Board/classic | about 20 incl. Go, Xiangqi, Dice games, Backgammon | 20 incl. Shogi, Mini Shogi, Hex, Ludo | similar, missing Go/Xiangqi/Battleship/Hearts/Spades |
| Card | about 12 (Hearts, Spades, Gin, etc.) | 11 | similar, missing trick-taking staples |
| Toy sports | about 8 | 15 | ahead |
| Party/same-screen | few | 8 + toolkit | ahead |
| Solo logic | few (Sudoku-like not central) | 17 | ahead |
| Online play with strangers | yes | relay undeployed, no matchmaking | far behind |
| Per-game ranking/progression | stamps, points | 10 achievements, no ranking, no unlocks | far behind |
| Kid-friendly framing | Yes (mild, cartoon) | partial (critter avatars; no kid mode) | behind |

---------------------------------------------------------------------------------------------------

## 7. Guesses and things not verified

- G: exact per-game presence of Undo/Hint (GameChrome supports them as optional; each screen opts in).
- G: whether any game has a turn timer beyond chess clock (SETTINGS doc suggests it; not located in specs).
- G: whether transitive dependencies add trackers (only top-level Gradle grep was done).
- G: tone of shooting/tank/boxing art for a young audience; needs visual review.
- G: K1 suitability ranking is judgement, not playtests.
- G: Clubhouse comparison relies on public knowledge, not this repo.
- Not read in depth: individual game screens' text density, hint behaviour, per-game strings, Spanish completeness per game.

---------------------------------------------------------------------------------------------------

## 8. Ranked top 15 (value x feasibility)

| # | Gap | Audience | Sev | Effort | One-line fix |
|---|---|---|---|---|---|
| 1 | K-1 / K-2 Kids profile + parent gate + time limits | kid | H | M | `AgeBand` in `ClubProfile`, `ageMin/kidSafe` on `GameHubSpec`, parental section with PIN, timer service |
| 2 | X-3 Multiple profiles (separate stats/streaks/avatars) | both | H | M | profile list in `ClubProfileStore`, per-profile `StatsRepository` key |
| 3 | A-3 Richer stats (best score/time, per-difficulty, history, time played) | adult/kid | H | M | extend `GameStats`, add detail sheet, Home records card |
| 4 | A-4 / K-6 Unlockables and sticker/collection rewards | both | H | M | gate themes/decks/avatar props on achievement ids; Sticker Book for kids; expand to 50+ achievements |
| 5 | K-4 Educational mini-games (kid Sudoku 4x4, maths facts, picture spelling, 6-card memory) | kid | H | L | 4 new modules using house pattern |
| 6 | A-2 Expose Expert CPU + adaptive level | adult | H | M | wire `isExpert` in chess/shogi/checkers/reversi/backgammon; re-add choice |
| 7 | K-3 Reading-free kids home + TTS | K1 | H | M-L | picture tiles, `TextToSpeech` for rules/results |
| 8 | A-8 Finish online (relay deploy, room codes, quick chat, Quick match) | adult | H | L | Wave 5 S1-S8 + host decision O1 |
| 9 | A-7 Game Night mode (headcount/time/age recommender, multi-game scoreboard) | both/family | H | M | wizard from `minSeats/maxSeats` + new `minutes/ageMin`; aggregate scores |
| 10 | A-10 Easy-reading accessibility preset + TTS + font + landscape Start fix | adult 65+ | H | M | one-tap preset; fix Hub inset; TalkBack device pass |
| 11 | K-8 In-app safety/privacy page, name filter, kid word lists | kid/parent | H | S-M | About page, filter in `cleanPlayerName`, curated Hangman/Word Search lists |
| 12 | A-5 Unified Daily Challenge hub (all dailies, bundle streak, share) | adult | M | S-M | Today tab + new dailies for solitaire/crossword/2048/matching |
| 13 | A-1 Ranked ladder/Elo + chess puzzles | adult | H | L | `OnlineRatingStore`, offline ladder vs CPU levels, tactics puzzles |
| 14 | X-1 / A-11 Tags, minutes, filters, quick/standard/long presets | both | M | S | add `tags/minutes/ageMin` to spec, chips on GamesScreen |
| 15 | K-7 Kid CPU level + tuning fixes (Paddle Duel Easy, Toy Tennis) | kid | M | S-M | `CpuLevel.KID`, retune engines per UNBUILT C |

Honorable mentions: A-6 tournaments (M), K-5 creative activities (M), K-9 co-op/teach mode (M), A-9 analysis/replay tooling (L), A-12 challenge ladders (M), A-13 more locales (M), candidate games Go/Xiangqi/Battleship/Quoridor/Hearts/Spades (each M).
