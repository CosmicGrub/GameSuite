# GameSuite

A shared Android game platform: one shell (menu, lobby, multiplayer) hosting
many small games — board, cards, arcade, puzzles, word games. Games plug into
the shell through a common contract so local pass-and-play, dual-screen
(Galaxy Z Fold 5 / Tab S9), same-room ad-hoc, and online multiplayer are built
**once** and every game gets them for free.

**Stack: Kotlin + Jetpack Compose (native Android).** Chosen over Unity for
disk-space reasons, and because it gives direct access to Jetpack
WindowManager — the actual API Samsung/Google foldables are built against —
which matters for the dual-screen requirement.

## Status

**v1.0.0**, plus three follow-up passes on top. This is a working Android
Studio / Gradle project, not a skeleton — 13 games shipped and each verified
on-device at least once (Tab S9 and/or Z Fold 5): Tic-Tac-Toe (plus Misère
and Wild variants), UNO, Hangman, Word Search, Crossword, Word Tiles,
Dominoes, Mancala, Air Hockey, Klondike Solitaire, and Sliding Puzzle. The
shell-level features every game gets for free are also built: local
pass-and-play, same-room ad-hoc multiplayer (Nearby Connections), online
multiplayer via a self-hosted relay (`server/`), foldable/dual-screen-aware
layout, a persistent per-game stats layer, and a settings/theming/
accessibility foundation (see
[docs/SETTINGS_THEMING_ACCESSIBILITY.md](docs/SETTINGS_THEMING_ACCESSIBILITY.md)).
See the [Roadmap](#roadmap) below for the full build history,
[Fixes and hardening](#fixes-and-hardening) for a bugfix/hardening pass
across the relay server, UNO, Word Tiles, Dominoes, Mancala, Solitaire, and
Settings plus a new JVM unit-test suite, and
[Depth, accessibility, and gameplay pass](#depth-accessibility-and-gameplay-pass)
for the persistent stats layer, the menu redesign, screen-reader semantics
across every game, and a set of targeted gameplay additions, and
[Online reconnect, spectator mode, daily challenge, and store readiness](#online-reconnect-spectator-mode-daily-challenge-and-store-readiness)
for the newest pass.

**Monetization: none, by design.** GameSuite has no ads SDK, no in-app
purchases, and no account system anywhere in the codebase — this is a
deliberate, stated decision, not an unmade one. See
[PRIVACY_POLICY.md](PRIVACY_POLICY.md) for exactly what data the app and its
optional online relay do and don't touch.

**Store readiness**, tracked honestly rather than silently: a privacy
policy now exists (linked above) and the app has a real LICENSE. Still
missing before a real store listing: a custom launcher icon (the project
still uses Android Studio's default template icon) and store assets
(screenshots, a feature graphic) — both are asset-creation work, not code,
and are the next concrete step toward a listing.

## Building and running

1. Clone the repo:
   ```
   git clone <repo-url>
   cd GameSuite
   ```
2. Open in Android Studio (**File → Open**, select the cloned folder) and let
   Gradle sync finish, **or** build from the command line:
   ```
   ./gradlew assembleDebug
   ```
   (Windows: `gradlew.bat assembleDebug`.)
3. Install on a device or emulator:
   - From Android Studio: press **Run ▶** with a device/emulator selected.
   - From the command line: `./gradlew installDebug`, or
     `adb install app/build/outputs/apk/debug/app-debug.apk`.

Minimum SDK 26 (Android 8.0), compiled against SDK 34. No manual SDK-path or
project-creation steps are needed — this is a normal Gradle Android project
and opens/builds the same way any other one does.

### Running the relay server (for online play)

Online multiplayer (`OnlineTransport`) talks to a small Node.js WebSocket
relay in `server/`, not a hosted service — see
[server/README.md](server/README.md) for how to run it locally
(`npm ci && npm start`) and run its regression suite (`npm test`), and how to
point the app's Settings → Online multiplayer server address at it
(`ws://<host>:8080` for a local run; a real deployment needs `wss://`, which
`server/README.md` covers).

## Roadmap

- [x] 0. Architecture + skeleton
- [x] 1. Android Studio project created, SDK re-pointed to Z:\Android\Sdk
- [x] 2. MainMenu shell + game library UI (Compose)
- [x] 3. Tic-Tac-Toe wired end-to-end, single device — verified on Tab S9 + Z Fold 5
- [x] 4. UNO built: standard 108-card deck, classic rules + house rules
      (stacking, 7-0, force-play-drawn-card toggle), CPU bots, team-play
      support in the engine (`UnoRules.teamPlay`, not yet exposed in menu UI).
      See `games/uno/`. 2-10 players.
- [x] 4b. Hangman added — proves a very different input shape (letter grid,
      not a board) reuses the same shell cleanly. See `games/hangman/`.
- [x] 5b. Word games: Word Search, Crossword (curated clue bank + greedy
      intersection generator), Word Tiles (Scrabble/WWF-style: 15x15 premium
      board, standard letter distribution/values, dictionary-validated
      scoring, bounded-heuristic CPU bot). All share one offline dictionary
      (`assets/words.txt`, ~359k words, public domain).
- [x] 5c. Shared card-fidelity engine (`games/cards/`): CardVisual +
      PlayingCardView (one rendering used by every card game), FannedHand
      (real overlapping fan layout with drag-up-to-play gesture + snap-back,
      tap fallback), CardSounds (procedurally generated place/draw/shuffle
      SFX, no external audio assets), haptics on play/draw. UNO now runs on
      this layer. Any future standard-deck card game reuses it directly.
- [x] 6a. Tic-Tac-Toe fidelity: bouncy scale-in placement animation, tap
      sound, haptic feedback. Verified on Tab S9 + Z Fold 5.
- [x] 6b. Word Tiles: drag gesture added (pick up a rack tile, drag over the
      board, drop on a cell — root-coordinate hit-testing against every
      board cell, floating overlay so the dragged tile isn't clipped by
      list bounds) alongside the original tap-to-select fallback. Installed
      on both devices — build success confirmed, but the drag *feel*
      (thresholds, hit-test accuracy) has NOT been hands-on tested; expect
      to need on-device tuning.
- [x] 6c. Dominoes + Mancala: new games built, tap-based placement +
      animation/haptic/sound (same pattern as Tic-Tac-Toe, not full drag —
      dominoes' growing chain and mancala's capture rules made drag lower
      priority than getting the games playable). Installed on both devices.
- [x] 6d. Air Hockey: real continuous 2D physics (velocity, wall/paddle
      circle-circle collision with momentum transfer, goal detection),
      frame-stepped game loop (`withFrameNanos`), fully drag-controlled
      paddle (finger position directly drives paddle position, not discrete
      taps), basic CPU tracking AI. Installed on both devices — build and
      install confirmed, but actual physics *feel* (speed balance, AI
      difficulty) is unverified hands-on and will likely need tuning.
      Hockey (ice) reuses the same engine with a reskin later if wanted.
- [x] 7. Foldable support, phase 1: `androidx.window:window:1.3.0` +
      `com/gamesuite/foldable/` — `FoldState`/`rememberFoldState` observes
      Jetpack WindowManager's `WindowInfoTracker` for live fold posture
      (book = vertical hinge, tabletop = horizontal hinge, or neither on a
      non-foldable device), provided app-wide via `LocalFoldState`.
      `FoldAwareTwoPane` splits a screen's content across the hinge when
      separating, falling back to the original single-pane layout
      automatically otherwise — no per-device branching needed elsewhere.
      Applied to UNO, Word Tiles, and Dominoes (table/board on one side of
      the hinge, hand/rack on the other — the natural "sit at a table"
      split for card/tile games). UNO's build was installed on both
      devices before they left; the Word Tiles + Dominoes split is
      on-device check of hinge-relative sizing and drag hit-testing across
      the split.
      **On-device verification (this session)**: found and fixed a real bug
      via hands-on testing that pure code review missed — `FoldAwareTwoPane`'s
      non-separating fallback branch only rendered `primary`, silently
      dropping `secondary` (UNO's hand, Word Tiles' rack, Dominoes' hand
      were all invisible on a flat/non-foldable screen — the game was
      unplayable past the opening draw). Root cause: a plain (non-weighted)
      Column child that itself calls `fillMaxSize()` greedily claims all
      available height before a sibling weighted child is measured. Fixed
      in `FoldAwareLayout.kt` (see the comment there) + changed every
      screen's primary/secondary root Column from `fillMaxSize()` to
      `fillMaxWidth()`. Verified working end-to-end afterward: drag-to-play,
      Wild color picker, CPU auto-play, turn rotation all confirmed live on
      the Tab via adb screenshots + programmatic pixel-coordinate targeting
      (manual coordinate estimation from scaled screenshots was unreliable —
      cost real time before switching to pixel-detection).
      NOT yet done: activity-embedding / true separate-window dual-screen
      (Tab S9 multi-window, Fold cover-screen-to-main-screen handoff),
      applying the same table/hand split to remaining games (Mancala, Word
      Search, Crossword, Air Hockey, Tic-Tac-Toe, Hangman).
- [x] 8. UNO research pass + engine upgrades — see sources in-conversation
      (officialgamerules.org, Mattel's UNO scoring/house-rules wiki,
      letsplayuno.com app docs, Reader's Digest on the Draw Four challenge).
      Added: **Wild Draw Four Challenge** (official rule — accuse the
      previous player of an illegal play; wrong guess costs you 2 extra
      cards), **multi-round scoring to 500** (official UNO is rounds-until-
      500, not single-hand-and-done — `UnoGame.startNextRound()`,
      cumulative scores shown between rounds), **jump-in** (engine-complete,
      `UnoRules.jumpIn`; no UI trigger yet since it's off by default), **2v2
      team mode menu entry** ("Play UNO (2v2 teams)" — engine already had
      team-play support, this exposes it). All four verified installed +
      launching without crashing on-device (Challenge dialog itself not yet
      hands-on-triggered — needs a live Wild Draw Four to appear in play).
- [x] 9. Research + upgrade remaining games, one at a time in priority
      order (per user's explicit pacing choice — not batched): Tic-Tac-Toe,
      Dominoes, Mancala, Hangman, word games (Word Search/Crossword/Word
      Tiles), Air Hockey. Each pass: real research into rules/variants/
      difficulty scaling/player feedback, then implement + verify on-device,
      same rigor as UNO got. **Complete** — every game in this list has its
      own sub-item below (9a Tic-Tac-Toe, 9f Hangman, 9g Air Hockey, 9h
      Dominoes, 9i Mancala, 9j Word Tiles, 9k Word Search, 9l Crossword).
- [x] 9a. Tic-Tac-Toe research + upgrade pass, the first game through item
      9's list. Tic-Tac-Toe itself is a solved game (forced draw with
      optimal play), so "rules research" here meant grounding the upgrade in
      that game-theoretic result rather than inventing board-size/Ultimate-
      Tic-Tac-Toe variants nobody asked for. Shipped, in
      `games/tictactoe/TicTacToeGame.kt` and `ui/TicTacToeScreen.kt`:
      1. **A real difficulty ladder, replacing the single fixed heuristic
         bot** — HARD is full minimax (depth-scored so it prefers a faster
         win / slower loss among equal outcomes), genuinely unbeatable;
         MEDIUM keeps the original one-ply heuristic (win/block/positional)
         as-is, still beatable with a deliberate fork; EASY plays mostly
         random, only reaching for the heuristic move ~35% of the time.
         `TicTacToeScreen` is also the **first screen in the suite to
         actually read `settings.defaultCpuDifficulty`** — every other
         game still just stores that setting unused (see `AppSettings.kt`'s
         own KDoc). A new "Play Tic-Tac-Toe (vs CPU)" menu button exposes
         `SINGLE_PLAYER_VS_BOT`, which the engine already declared
         supported but no button had ever launched.
      2. **Winning-line highlight** — the 3 completing cells turn gold
         instead of the match just silently ending.
      3. **A round-over panel with session score tracking** — previously a
         win/draw immediately kicked the player back to the main menu with
         no result screen at all. Now: "You win!" / "CPU wins!" / "It's a
         draw!" plus a running `You: 2 · CPU: 1 · Draws: 1` score line,
         **Play Again** (resets the board, keeps the score, alternates who
         opens the next round for fairness) and **Back to Menu** (ends the
         session, reporting cumulative score as the match result).
      **Verified on-device (Tab S9)**: played a full HARD game through
      real tactical decisions (blocked a column threat, then a diagonal
      threat) to a forced draw — confirms the minimax is actually
      unbeatable, not just claimed to be; confirmed EASY's opening move
      differs from HARD/MEDIUM's (center) proving it isn't silently
      running the same logic; confirmed Play Again resets the board while
      keeping the score and correctly alternates the starting player
      (CPU opened round 2); confirmed the CPU-difficulty label updates
      live when changed in Settings between matches. **Not confirmed
      on-device this pass**: the winning-line gold highlight and the
      pass-and-play (2-human) path specifically — both are small,
      low-risk composable/data changes reusing already-verified
      `winningLine` computation, reviewed in code rather than screenshotted,
      because the Tab S9 went into use by someone else mid-verification
      (see item 10c's on-device-testing notes for the established pattern)
      and the Z Fold 5 dropped its adb connection at the same time. Also
      fixed in passing: the turn/round-over text read "You's turn" for the
      human player — grammatically wrong — before a `possessive()` helper
      special-cased "You" → "Your".
- [x] 9f. Hangman research + upgrade pass. A solo word-guessing puzzle has
      no opponent to make smarter/dumber, so "difficulty scaling" here
      honestly means the word pool, not a bot — see `HangmanGame.kt`'s
      KDoc. Shipped: three curated word pools (EASY: short everyday words
      like APPLE/HOUSE/WATER; MEDIUM: longer but still common, e.g.
      ELEPHANT/MOUNTAIN/CHEMISTRY; HARD: long and/or specialized, e.g.
      XYLOPHONE/QUARANTINE/BUREAUCRACY — the guess limit stays a fixed 6
      across all three deliberately, so the word pool is the only lever
      that changed, not two levers muddying which one made a round
      harder), selected from Settings' "Default CPU difficulty" the same
      way item 9a wired Tic-Tac-Toe; a running session score (wins/losses)
      and a **New Word** button, replacing the old dead end where any
      single win or loss left "Back to menu" as the only way forward.
      Fixed in passing: `endMatch()`'s result was never actually reaching
      `sessionManager` before — the old "Back to menu" button called
      `onMatchEnded` directly, skipping the module's own end-of-match
      reporting entirely; the new `leaveSession()` goes through
      `endMatch()` properly, matching every other game.
      **Verified on-device (Tab S9)**: a fresh match starts with the
      word-length and "Word difficulty: Easy" label matching the EASY pool,
      and wins/losses correctly start at 0. **Not verified this pass**: an
      actual win, loss, or New Word round-trip — the Tab S9 switched to a
      different app mid-verification for a second time this session (see
      item 9a's notes; this time to a photo picker in an unrelated
      coloring-book app), so the rest was reviewed in code, directly
      reusing the same round-over/session-score pattern already proven
      working end-to-end for Tic-Tac-Toe in 9a.
- [x] 9g. Air Hockey research + upgrade pass. The CPU paddle was a single
      fixed-speed, zero-error tracker (`cpuSpeed = 0.9f`, always aims
      exactly at the ball's current x) — replaced with a real 3-tier
      ladder in `AirHockeyGame.kt`'s `cpuSpeedFor`/`chooseCpuTargetX`:
      EASY is slower and re-rolls a wide random aim offset every frame
      (reads as a genuinely sloppy, wobbly paddle, not just a slow one);
      MEDIUM is the original untouched behavior, kept as the middle tier
      rather than silently changed; HARD is faster and aims slightly
      *ahead* of the ball along its current velocity instead of at its
      exact current position, so it starts closing on fast shots before
      they arrive. Wired to Settings' "Default CPU difficulty", same
      pattern as 9a/9f, with a difficulty label on-screen.
      **Not verified on-device this pass** — the same app-switch
      interruption noted in 9f happened before Air Hockey could be
      launched at all. Lower-risk than usual to ship unverified: it's a
      single parameter substitution behind an existing, already-shipped
      physics loop, not new game logic, and the three tiers' speed/error/
      lookahead values are reviewable in code without needing a play
      session to confirm they compile and wire correctly — but real-time
      "does Hard actually feel hard" playtesting specifically still needs
      a hands-on pass before calling this fully done.
- [x] 9h. Dominoes research + upgrade pass. The bot always played the first
      legal tile it found (preferring doubles only for the opening move),
      else drew, else passed — a fixed, unranked choice among whatever
      happened to be legal. Replaced *which* legal tile it picks with a
      real 3-tier ladder in `DominoGame.kt`'s `chooseBotPlay`/
      `chooseOpeningPlay`: EASY picks uniformly at random among every
      legal (tile, side) option, no double or pip preference at all —
      genuinely weaker, not just relabeled; MEDIUM is the original
      behavior byte-for-byte (try the left end first, first match wins);
      HARD sheds its heaviest tiles first (highest pip total, doubles
      tie-broken above), real basic dominoes strategy given only what's
      visible without peeking at opponent hands (unlike Mancala below,
      dominoes hands are genuinely hidden information, so a search-based
      bot isn't the honest option here — see DominoGame's KDoc). The
      play/draw/pass sequencing itself is unchanged at every tier — that's
      the rules, not a skill lever. Wired to Settings' "Default CPU
      difficulty" like every other pass in this list.
      **Not verified on-device this pass** — both physical test devices
      were unavailable for the rest of this session (Tab S9 still in
      active use by someone else, Z Fold 5 dropped its adb connection and
      wouldn't reconnect). Lower risk than most unverified changes: no
      recursion, no search, just a `filter`/`maxByOrNull`/`random` over a
      list of at most ~7 legal plays — the same shape already proven safe
      in Air Hockey's (9g) and Tic-Tac-Toe's (9a) EASY tiers.
- [x] 9i. Mancala research + upgrade pass. Unlike Dominoes, Mancala has
      **zero hidden information** — both players' pits are always fully
      visible — so a real look-ahead search is the honest choice for HARD,
      not a heuristic guess: `MancalaGame.kt`'s `minimaxBestMove` runs a
      genuine depth-limited (8 plies) minimax with alpha-beta pruning over
      a pure, side-effect-free copy of the sow rules (`simulateSow`,
      correctly modeling extra turns as an *additional* ply for the same
      player rather than alternating, and captures/end-of-game sweeps the
      same way the real `sow()` does), maximizing final store-difference.
      EASY moves uniformly at random among legal pits — not even taking
      the free-extra-turn bonus MEDIUM already knew about, genuinely
      weaker. MEDIUM is the original bot, untouched: prefer a move landing
      exactly in the store, else the first non-empty pit. Wired to
      Settings' "Default CPU difficulty" like the rest of this list.
      **Verified on-device (Z Fold 5)**: played several real HARD-tier
      moves — confirmed the difficulty label reads "Hard", and critically,
      confirmed the minimax search itself completes correctly and quickly
      with no hang/freeze/ANR and always returns a legal move ("CPU sowed
      from pit 11", board state updated consistently) — the thing most
      worth confirming hands-on here, since a search-depth or pruning bug
      would be far more likely to manifest as a stall than as a visibly
      wrong move. Not separately re-verified: EASY/MEDIUM tiers (unchanged
      or trivial relative to what was already exercised) and a full match
      to completion.
- [x] 9j. Word Tiles research + upgrade pass — the last game in the suite
      with a real bot opponent. `TileBot` was a single fixed search: 20
      random anchors, longest word first, first valid hit played. Now a
      3-tier ladder (`TileBot.findMove(difficulty)`): EASY looks at only 5
      anchors and takes the *shortest* valid word at each, so it plays weak
      2-3 letter words and passes far more often; MEDIUM is the original
      behavior byte-for-byte; HARD searches 40 anchors, never stops at the
      first hit, collects every valid candidate and plays the one with the
      highest total tile value — a deliberate proxy for real score (it
      ignores premium squares, which would mean pulling `TileGame`'s
      private scoring into the bot; tile value alone already separates a
      Q/Z/X play from a pile of 1-point vowels, which is most of what a
      stronger opponent feels like). The candidate machinery (permutations,
      blank expansion, dictionary checks) is shared by all three — only
      how much board it looks at and which found word it plays differ.
      Wired to Settings' "Default CPU difficulty" like the rest of item 9.
      **Real finding from code review, fixed before shipping**: the first
      draft of HARD scanned *every* anchor on the board. `playBotTurn()`
      runs synchronously on the UI thread from the bot-turn
      `LaunchedEffect`, and each anchor is up to ~17k dictionary lookups
      (×26 per blank tile) with no short-circuit — an unbounded scan of a
      busy late-game board would have been a multi-second freeze or an
      ANR. The original bot's 20-anchor cap existed for exactly this
      reason; HARD is now capped at 40 (`HARD_ANCHOR_BUDGET`), ~2× the
      original worst case, which was already tuned to feel instant.
      **Verified on-device (Z Fold 5)**: the build installs and launches
      to the main menu without crashing. **Not verified**: the difficulty
      label and any of the three tiers' actual play — the Fold 5 switched
      to someone else's app (a fitness tracker, mid-use) the moment Word
      Tiles was tapped, and the Tab S9 was already in use by someone else,
      so both devices were off-limits for the rest of the pass. The change
      is the same shape as 9h (Dominoes): pure selection logic over
      candidates the existing, already-shipped validator still gates —
      an invalid bot proposal already falls through to `pass()` safely.
      **Still open under item 9**: Word Search and Crossword. Both are
      solo puzzles with no bot, so their pass is a different shape
      (puzzle-generation difficulty, not an opponent) — same reasoning as
      Hangman (9f) — and wasn't started this pass.
- [x] 9k. Word Search research + upgrade pass — the second-to-last game
      under item 9. A solo puzzle with no opponent, so the honest
      difficulty lever is generation, same reasoning as Hangman/Crossword.
      EASY is a smaller grid with fewer, shorter words *and forward-only
      placements* (no reversed or diagonal words) — the single biggest
      felt difference in a word search is whether you ever have to read
      backwards or on a diagonal, not just grid size. MEDIUM reproduces
      the original single-tier generator byte-for-byte (12x12, 8 words,
      lengths 4-9, all 8 directions). HARD is a larger grid with more,
      longer words, still using every direction. Session-tally New
      Puzzle/Back to Menu flow added, matching the Hangman pattern —
      solving no longer calls `endMatch()` directly.
      **Lost once to a concurrency bug, then redone**: an earlier attempt
      at this same pass was built and reviewed clean, but two workflows
      running in this session both did temp-edit-then-revert cycles on
      `MainActivity.kt`/`MainMenuScreen.kt` at the same time to verify
      their own changes compiled, and one's `git checkout` wiped the
      other's uncommitted Word Search work before it could be committed.
      Redone from scratch once the collision was understood; see the
      note on item 12/13's development process below for what changed
      about how concurrent work is run after this.
- [x] 9l. Crossword research + upgrade pass — the last game under item 9,
      closing it out. EASY reveals the first letter of every entry at
      generation time (a standard, real "easy mode" crossword convention);
      MEDIUM reproduces the original bank/placement path byte-for-byte
      (the `gamesAndTechTheme` bank, greedy longest-first-at-center
      placement); HARD draws from a new, hand-authored general-knowledge
      clue bank (science/geography/literature) instead — checked
      clue-by-clue for factual accuracy and answer uniqueness by an
      adversarial reviewer before shipping, not just written and trusted.
      Session-tally New Puzzle/Back to Menu flow added, same as 9k.
- [x] 9b. **Full audit-verify-fix pass** across all 9 games + shared infra —
      run as a 30-agent pipelined workflow (audit → adversarially verify →
      fix, per game), then build + install + on-device spot-check by hand.
      **35 confirmed, fixed issues**, including several critical soft-locks:
      bots freezing forever after a bonus turn (Mancala) or a forced-play
      draw (UNO), a bot stuck after drawing from the boneyard (Dominoes), an
      unfinishable Word Search match (duplicate-word placement bug), and a
      Word Tiles exploit letting a player dump unvalidated tiles onto the
      board for free. Also fixed: a critical transport bug where
      `LocalPassAndPlayTransport.send()` reported the *destination* as the
      message sender (backwards — `MultiplayerTransport.send()` now takes an
      explicit `fromPlayerId`), `FannedHand`/board grids overflowing on
      narrow screens with no scroll fallback (multiple games), silent
      submit-failures in Word Tiles/Crossword, and dozens more — full
      findings + fix summaries are in the workflow transcript (not
      re-duplicated here). On-device verified: Mancala's bot bonus-turn
      fix (played multiple rounds, CPU store climbed 0→2, no freeze),
      Dominoes' turn flow + Draw/Pass legality gating, Word Tiles' new Swap
      button. Not yet hands-on verified: UNO's forced-draw bot fix, Word
      Search's fixed duplicate-word bug, Air Hockey's goal/collision-order
      and square-canvas fixes, Crossword/Hangman/Tic-Tac-Toe's individual
      fixes — all build-verified (full project compiles clean) but not each
      individually played through.
- [x] 9c. Device-specific research: verdicts on true dual-screen/
      ActivityEmbedding (build, don't — the existing in-app `FoldAwareLayout`
      hinge split is architecturally correct for a single-Activity Compose
      app; ActivityEmbedding would be a regression), Nearby Connections
      (build — see item 11), adaptive per-device-class layout (build — see
      item 10), Tab S9 DeX/S-Pen (skip, already free via Compose/manifest
      defaults)/mouse-keyboard-hover (small, worth building). Full plan:
      [docs/DEVICE_SPECIFIC_PLAN.md](docs/DEVICE_SPECIFIC_PLAN.md).
- [x] 9d. Settings/theming/accessibility scoping — full per-game settings,
      accessibility gaps, and an app-wide theming architecture (DataStore +
      parameterized `AppTheme`, 3-5 named themes). Biggest concrete gap:
      `UnoRules.kt`'s house rules have zero settings UI. Full plan:
      [docs/SETTINGS_THEMING_ACCESSIBILITY.md](docs/SETTINGS_THEMING_ACCESSIBILITY.md).
- [x] 9e. **Settings/theming foundation** (Phase 0 from
      SETTINGS_THEMING_ACCESSIBILITY.md) — built and fully verified on-device
      (live theme switching, DataStore persistence survives a process kill,
      Reset all settings). New: `settings/` package (`AppSettings`,
      `SettingsRepository` — DataStore Preferences, `SettingsViewModel` —
      app-scoped StateFlow), `theme/` package (`AppTheme` — parameterized
      light/dark/system + optional Material You dynamic color + named-theme
      palette, `Color.kt` — Classic + High Contrast `ColorScheme`s), and
      `ui/SettingsScreen.kt` (theme mode, dynamic color, named theme, master
      sound/haptics, reduced motion, colorblind-safe mode, text size,
      default CPU difficulty, reset) wired into MainActivity's NavHost via a
      new ⚙ Settings button on the home screen. `CardSounds` now respects
      the master sound toggle (one static flag, synced from settings at the
      root — see its KDoc). NOT yet wired: haptics toggle (still fires
      unconditionally at every call site — many touch points, deferred),
      reduced motion / colorblind mode / text scale / default CPU difficulty
      (stored and toggleable, but no game screen consumes them yet — that's
      Phase 1 per-game work, see SETTINGS_THEMING_ACCESSIBILITY.md §6).
      Midnight Arcade / Felt Table named themes are reserved enum values
      with no palette yet (fall back to Classic).
- [x] 10a. Adaptive per-device-class layout, phase 1: `foldable/DeviceClass.kt`
      (`rememberAdaptiveLayoutMode` — `currentWindowAdaptiveInfo()`-based
      Compact/Medium/Expanded width class, layered so an active fold-posture
      split always wins, size class is only a tiebreaker) + `foldable/
      AdaptiveTwoPane.kt` (new: a real wide-screen side-panel layout —
      capped/centered primary + fixed sidebar — for Expanded-width windows;
      delegates straight to the existing, already-verified
      `FoldAwareTwoPane` for every other case). Wired into UNO, Word Tiles,
      Dominoes (the three games already using `FoldAwareTwoPane`) —
      Crossword intentionally excluded, same reasoning as its existing
      documented FoldAwareTwoPane opt-out (unbounded secondary content).
      **Fully on-device verified on the Tab S9**: portrait correctly stays
      single-column (its ~823dp width sits just under the 840dp Expanded
      cutoff, exactly as the breakpoint-math predicted); landscape (~1520dp)
      correctly triggers the new side-panel split. Two real bugs were found
      via direct screenshot (not visible from code alone) and both are now
      fixed AND re-verified on-device: (1) primary content hugged the left
      edge with a dead gap before the sidebar — `weight(1f)` forces the
      wrapper to its full slot regardless of `widthIn(max)`, fixed with
      `contentAlignment = Alignment.TopCenter`; (2) even after that fix,
      each screen's own player/opponents summary row (added during the
      compatibility audit as a `fillMaxWidth().horizontalScroll(...)` +
      plain `Arrangement.spacedBy()`) still hugged the left independently,
      since plain `spacedBy` never centers leftover space — fixed in both
      UnoScreen.kt and DominoesScreen.kt with `Arrangement.spacedBy(16.dp,
      Alignment.CenterHorizontally)` (TileGameScreen's header already used
      `SpaceEvenly`, unaffected). Final screenshot confirms a fully centered,
      coherent tablet layout — opponents row, discard pile, and Draw button
      all align on the same axis. Device state cleaned up: Tab S9
      auto-rotate restored to normal.
      **Not yet done** (at the time of 10a): applying this to the remaining
      games (Mancala, Word Search, Crossword, Air Hockey, Tic-Tac-Toe,
      Hangman all still single-pane-only — though Mancala already has its
      own board-scaling response to wide screens from the earlier audit, a
      different but reasonable treatment), and the Cover-screen (Fold
      folded) bespoke layout from DEVICE_SPECIFIC_PLAN.md §3 (narrow widths
      currently fall back to the existing responsive single-column
      treatment from the compatibility audit, which works but isn't the
      dedicated bottom-sheet-style cover layout the plan describes).
- [x] 10b. Adaptive layout, phase 2: `AdaptiveTwoPane` (primary-only, no
      `secondary`) wired into all five remaining single-pane games —
      Tic-Tac-Toe, Hangman, Word Search, Crossword, Air Hockey (Mancala
      deliberately left on its own existing `BoxWithConstraints` scaling
      treatment, still a reasonable alternative). Build-verified, then
      on-device verified on the Z Fold 5 (portrait/book posture, ~690dp
      width — Medium size class, so this exercises the plain
      `FoldAwareTwoPane` fallback path, not the TABLET-mode cap): all five
      screens render correctly with no regressions.
      Found and fixed one real, severe, pre-existing bug while doing this
      (unrelated to the layout wrapping itself — present before and after):
      Hangman's A–Z letter buttons rendered as completely blank circles,
      confirmed both visually (zoomed screenshot) and via the accessibility
      tree (every letter button reported empty text — only 2 non-empty text
      nodes existed on the whole screen). Root cause: Hangman was the only
      screen in the app using a real Material3 `Button` for tiny grid-cell
      buttons (every other letter/tile picker uses `Box + clickable +
      Text`, sidestepping this entirely) — `Button`'s default content
      padding (24dp horizontal) exceeds the button's own ~44–48dp width once
      squeezed into an adaptive grid cell, leaving zero room for the label.
      Fixed with an explicit small `contentPadding` and a slightly larger
      `minSize` floor (48dp → 56dp) on the grid cells; re-verified on both
      devices post-fix — all 26 letters now clearly legible.
- [x] 10c. Root-caused and fixed the Tab S9 FE TABLET-mode capping bug
      flagged (but not yet diagnosed) at the end of 10b. Confirmed on-device
      with temporary debug instrumentation (a colored border on the capping
      `Box`(es) plus an on-screen `MODE=...` label showing the resolved
      `AdaptiveLayoutMode`) that in landscape (~1316dp, solidly
      Expanded/TABLET) `MODE=TABLET` *was* resolving correctly, but the
      "capped" `Box`'s own border spanned the full screen width — the
      `widthIn(max = 840.dp)` cap was a complete no-op, in **both**
      `AdaptiveTwoPane` branches (not just the never-before-tested
      primary-only one; a live UNO re-check showed the same symptom, so
      10a's "on-device verified" claim did not hold up under this closer
      look). Root cause: `widthIn(max)` chained on the *same* modifier as an
      upstream exact-fill constraint (`fillMaxSize()`, or `weight(1f)` in a
      `Row`) is neutralized by that exact-fill — the cap only works when
      `widthIn(max)` is the *only* sizing modifier on its own element, with
      no competing `fillMax*`/`weight` call in the same chain. Fixed in both
      branches by splitting into an outer `Box` that fills the available
      space and centers its content (`contentAlignment = Alignment.TopCenter`)
      and a separate inner `Box` that carries `widthIn(max)` alone. Re-verified
      with the same debug border: Hangman's cap now measures ~834dp (target
      840dp) and sits centered; UNO's primary content (via precise
      accessibility-tree bounds, not eyeballing) centers within its weighted
      slot to within a couple of dp. Debug instrumentation fully removed
      after confirming the fix; Dominoes/Word Tiles (same shared branch)
      re-screenshotted and unaffected.
      While re-verifying, also found and fixed a related but distinct
      cosmetic gap it exposed: `DominoesScreen`'s primary content, unlike
      `UnoScreen`'s, was never given the per-section `CenterHorizontally`
      treatment beyond its opponents row — invisible before (nothing was
      centered, so nothing looked asymmetric) but glaring once the capping
      fix actually produced a centered, empty-margined column around it
      (Boneyard/Chain/Draw-Pass hugging the container's left edge). Fixed by
      adding `horizontalAlignment = Alignment.CenterHorizontally` to
      `DominoesScreen`'s primary `Column`, matching `UnoScreen`'s pattern;
      re-verified. Word Search, Crossword, Word Tiles, and Air Hockey were
      each checked for the same class of issue and found already correct —
      their primary content (a wide grid/board/canvas) naturally fills most
      of the 840dp cap, so there's no empty-margin asymmetry to expose.
      Tab S9 auto-rotate (forced off for landscape testing) restored to
      normal afterward.
- [x] 11. `NearbyConnectionsTransport` — same-room ad-hoc multiplayer via
      Google's Nearby Connections API (`play-services-nearby:18.7.0` —
      19.5.0 is current upstream but ships Kotlin metadata newer than this
      project's Kotlin 2.0.21 toolchain can read, a real build-breaking
      incompatibility found immediately on first compile; 18.7.0 predates it
      and the Connections API surface itself has been stable since ~17.x/18.x
      per research, so no functionality lost). `Strategy.P2P_CLUSTER`, Bytes
      payloads. UNO is the one game wired in for this pass (`supportedModes`
      now includes `LOCAL_AD_HOC`); other games can follow the same pattern.
      **Fully built and on-device verified end-to-end between the real Fold 5
      and Tab S9** — permission grant, Bluetooth/Wi-Fi radio-enable prompts,
      advertising, discovery, connection, lobby roster sync, live match:
      dealing, ordinary plays, guest-initiated plays (intent → host → 
      rebroadcast), and a Wild card's color-choice flow all confirmed
      correctly synced across both physical devices.
      **Architecture: host-authoritative, full-state broadcast, not a
      replicated simulation.** `dealNewRound()` shuffles with local RNG — if
      every device ran the rules engine independently, each would deal
      itself a different random hand for "the same" game, an instant desync.
      Instead only the host (`localPlayerIndex == 0`, a convention the lobby
      enforces) ever runs `UnoGame`'s real logic; every mutation funnels
      through a new `commitState()` that also broadcasts the resulting
      `UnoState` (JSON via `kotlinx.serialization`, wrapped in a
      versioned `StateSync` so a receiver can drop stale/out-of-order
      messages); a non-host device's taps turn into an `Intent` sent to the
      host instead of mutating anything locally. A `RequestState` message
      lets a newly-navigated guest ask the host to resend current state
      directly, covering the startup race where the guest's listener
      registers after the host's first broadcast already went out.
      Nearby endpoint ids are per-observer (not the same string on both
      sides of one connection), so they can't double as a cross-device
      player id — `NearbyLobbyProtocol.kt`'s `JoinRequest`/`GameStart`
      handshake has each guest mint its own id and the host echo it back in
      the roster, sidestepping that asymmetry entirely.
      **Real bugs found and fixed via on-device testing, not code review**:
      (1) `WifiManager.isWifiEnabled()` — a plain platform API this app
      calls directly for its own radio precheck, unrelated to Nearby's own
      internal discovery — crashed with `SecurityException` on a real API 34
      device, because the manifest capped `ACCESS_WIFI_STATE` at
      `maxSdkVersion="31"` (copied from Google's own Nearby sample, which
      assumes `NEARBY_WIFI_DEVICES` fully replaces it past API 31 — true for
      Nearby's own calls, not for this unrelated one); removed the cap.
      (2) `UnoScreen.kt`'s `humanIndex()` was hardcoded to "the first
      non-bot player," a comment literally reading "the human player is
      always index 0" — harmless for vs-bot (one human seat) but means
      guest devices in Nearby play showed the *host's* hand and turn status,
      not their own; worse, the same bug likely already affected
      multi-human `SINGLE_DEVICE_PASS_AND_PLAY` (4-player, 2v2) beyond
      player 1's turn, just never noticed because a full multi-player
      pass-and-play match apparently was never played out to a later
      player's turn during earlier testing. Fixed by making it mode-aware:
      `LOCAL_AD_HOC` uses `context.localPlayerIndex`, pass-and-play uses
      `s.currentPlayerIndex` (the device is handed around — "my seat" is
      whoever's turn it is), vs-bot keeps the original first-non-bot logic.
      **Not yet exercised over the network**: multi-round matches (score
      carrying to next round), a match actually ending, the Challenge flow,
      `catchUnoFailure`, more than 2 devices in one lobby, and
      disconnect/reconnect mid-match — the underlying `commitState`/intent
      plumbing treats all of these uniformly with what *was* tested, so
      there's no specific reason to expect them broken, but none were
      individually driven to completion on real hardware this pass.
      Team play (2v2) is also unreached — Nearby's lobby has no team
      assignment UI yet, and `NearbyHostLobbyScreen` always builds an
      un-teamed roster.
- [x] 11a. UNO visual/UX overhaul, phase 1 — modeled on a specific reference
      (a 2006 Xbox 360 UNO longplay, LongplayArchive; full design memo
      published as an artifact, "Reshuffling UNO," with an 11-item prioritized
      build plan). Items 1–5 of that plan shipped this pass, all in
      `UnoScreen.kt`, and on-device verified on both the Tab S9 and Z Fold 5:
      1. **Icon-badge player identity** — every seat (including your own, in
         the opponents summary) now shows a small gradient-tile badge with a
         wild-pinwheel icon instead of plain name text, cycled by seat index.
         Deliberately no avatars/portraits — matches the reference exactly.
      2. **Card-back fans for opponents** — replaces "N cards" text with an
         actual fan of face-down `PlayingCardView`s, capped past 8 cards.
      3. **Permanent direction arrows** — two curved arrows flank the discard
         pile always, not just during a Reverse animation; `UnoState.direction`
         existed already but had zero visual before this. Mirrored via a
         single `scale(scaleX = -1f)` on the whole pair so reversing can't
         desync the two arrows from each other regardless of curve geometry.
      4. **Seat-anchored turn tag** — a green pill rides whichever seat is
         acting ("Play a card" / "Choose a color" / "Draw or stack" / "Accept
         or challenge" by state) instead of one fixed corner label. Matters
         more now that item 11's `humanIndex()` fix makes pass-and-play/Nearby
         turns actually rotate correctly. Skipped for the local player's own
         opponents-row entry specifically to avoid showing the same tag twice
         (it already rides the hand side too) — found via on-device screenshot,
         not obvious from the code alone.
      5. **One-card hand collapse** — at `hand.size == 1` the fan swaps for one
         large card instead of a counter; the hand's own shape is the tell,
         same as the reference.
      Found and fixed one real layout bug via on-device testing: the direction
      arrows' `Row` initially used `fillMaxWidth()` + `SpaceBetween`, which
      pushed them to the screen edges instead of flanking the pile — capped to
      a fixed 160dp width instead.
      **Not yet built** (items 6–11 of the plan): the Wild Draw Four Challenge
      dramatic sequence (a "CHALLENGE!" flash + hand-reveal — `resolveChallenge()`
      already computes the right outcome, presentation is the gap), a "UNO!"
      call-out text effect, the wild color-choice screen flash, a UNO-scoped
      ambient background, a Standard/Partner/House-Rules mode-select screen, and
      card-toss-to-pile motion. See the artifact for the full plan and the
      exact "Command Cards" rule text pulled from the reference.
- [x] 11b. UNO visual/UX overhaul, phase 2 — the user re-scoped this to
      "model the game completely (or as close as possible) to this version
      and this version ONLY," i.e. the Longplay reference exclusively, not
      the mobile/general-gameplay videos from phase 1. Shipped, all in
      `UnoScreen.kt`, on-device verified on both devices:
      6. **The Challenge sequence** — a full-bleed dimmed overlay flashes a
         huge diagonal green "CHALLENGE!" (confirmed on-device, matches the
         reference's look closely), then reveals the accused player's hand
         face-up, before `resolveChallenge(accept = false)` actually runs.
         Purely presentational — transient `ChallengeRevealSnapshot` state in
         `UnoScreen`, not game state; `resolveChallenge()`'s already-correct
         legal/illegal computation (traced in phase 1) is unchanged, just
         called ~2s later than before so the animation has room to play.
         Verified via a temporary debug trigger (forced both new overlays
         onto screen automatically, since naturally drawing into an
         unstacked-Wild-Draw-Four-targeting-the-human state took too long to
         rely on for a screenshot) — confirmed the "CHALLENGE!" flash frame
         directly; the hand-reveal phase reuses the same `PlayingCardView`
         path proven everywhere else in this file, not independently
         re-confirmed on screen given its ~1.3s window. Debug trigger removed
         after verification, not shipped.
      7. **"UNO!" call-out** — a bold italic gold "UNO!" pops over the table
         when the button is tapped, confirmed on-device. Approximated with
         italic+bold rather than a true script face, since this project has
         no custom font set up yet — noted as a known gap from the reference,
         not a hidden one.
      9. **UNO-scoped ambient background** — a purple-to-near-black radial
         glow behind the whole screen (`UnoBackground`, wraps gameplay and
         both end screens), matching the reference's lighting closely on both
         devices. Scoped to this screen only; doesn't touch `colorFor()`.
      Also restyled every dialog/end-screen (`ColorPickerDialog`,
      `ChallengeDialog`, `RoundOverContent`, `MatchOverContent`) onto one
      shared `UnoPanel`/`PanelTitle` — the same dark violet-bordered panel
      and gold title language the reference's own "Command Cards" screen
      established — since the CHALLENGE!/ambient-background/badge work made
      the previous plain-white Material dialogs look like they belonged to a
      different, uglier app by comparison. Not itself one of the plan's
      numbered items, but a direct, necessary consequence of shipping them.
      **Still not built**: item 8 (wild color-flash) was dropped rather than
      built — it was sourced from the mobile reference in phase 1, not
      directly observed in the Longplay, and the "this version ONLY"
      re-scope means it no longer has a source to build from; item 10
      (Standard/Partner/House-Rules mode-select) remains deprioritized since
      GameSuite's main menu already exposes the same choices as separate
      buttons and a literal duplicate screen would be redundant UX, not
      better fidelity; item 11 (card-toss animation) remains unbuilt, same
      "general feel, not video-3-sourced" reasoning as phase 1. A
      round-end/scoreboard screen and the exact wild-color-choice UI were
      each searched for in the Longplay specifically for this pass and not
      found in the ~15 minutes of footage sampled — `RoundOverContent`/
      `ColorPickerDialog` are styled to be consistent with everything that
      *was* confirmed, not claimed as directly-observed fidelity.
- [x] 11c. Card-size customization — a Settings slider (0f..1f portable
      preference, `AppSettings.cardSizePreference`, DataStore-backed like
      `textScale`) that resizes cards across every card game, with a live
      preview card in Settings that grows/shrinks as the slider moves.
      `games/cards/CardScale.kt`'s `rememberCardScaleMultiplier()` maps the
      preference to an actual multiplier computed per-device: width capped
      at 840dp (matching `AdaptiveTwoPane`'s own TABLET-mode cap, not the
      device's raw width), floor 0.65x (~42dp cards, still comfortably
      tappable), ceiling 1.15x–1.75x depending on device width. Provided
      once at the root via `LocalCardScale` (`MainActivity`) alongside
      `LocalFoldState`.
      **Deliberate architecture**: `PlayingCardView` itself never reads
      `LocalCardScale` — every call site (discard pile and opponent fans in
      `UnoScreen`, `FannedHand`, the Settings preview) reads it and applies
      it explicitly to width/height before any size-dependent layout math
      (fan overlap, offsets). Scaling a second time inside `PlayingCardView`
      would desync that math from the sizes it computed against. Card label
      font size is instead derived from the card's own rendered `width`
      (`PlayingCardView`, proportional + clamped), so it stays legible and
      self-consistent regardless of which caller-applied scale produced
      that width — no separate scale input needed there.
      **Found and fixed via on-device testing**: the Settings preview card
      initially didn't apply the scale at all (missed the same
      read-`LocalCardScale`-explicitly step every other call site needed) —
      caught by dragging the slider and seeing the preview not move; fixed
      by adding the same pattern used everywhere else.
      **Verified on both the Tab S9 and Z Fold 5** (Fold 5 needed a fresh
      debug-APK install mid-verification — it was still on a build from
      before this feature): live preview tracks the slider in real time;
      persists and applies uniformly across the discard pile, own hand, and
      opponent fans while `PlayerBadge`/`DirectionArrows`/`TurnTag` stay
      fixed-size (not "cards"); card labels stay legible at the smallest
      setting; illegal plays are still correctly rejected at the smallest
      setting. Confirmed legal plays also work correctly at the smallest
      setting — this needed `input swipe` (a real drag), not `input tap`;
      an initial tap-only attempt looked like a stuck/broken hand at first,
      but was just not satisfying `FannedHand`'s drag-to-play gesture (the
      screen's own "drag a card up to play it" hint was the tell) — not a
      bug. `playThreshold` (the drag distance to trigger a play) is left
      unscaled by card size, a deliberate simplification not yet revisited.
- [x] 12. `OnlineTransport` — went with the "lightweight custom backend"
      half of this item's original either/or, not Firebase: a Firebase
      project is an external account this assistant can't create on the
      user's behalf, while a small self-hostable relay is something that
      can be fully built, run, and tested locally with no account at all
      — see `server/`'s own README for exactly that tradeoff.
      `server/index.js`: a Node/`ws` WebSocket relay implementing a
      room-code host/join/message/leave protocol — a *relay*, not a game
      server, mirroring `MultiplayerTransport`'s own
      `send()`/`onMessageReceived(fromPlayerId, payload)` contract
      exactly; it never parses or validates game state, so every game
      stays exactly as host-authoritative over this transport as it
      already is over Nearby. Has its own protocol regression test
      (`server/smoke-test.js`, 7/7 passing: host/join/direct-message/
      broadcast/disconnect/bad-room-code).
      `OnlineTransport.kt`: the Android-side `MultiplayerTransport`
      implementation, same two-layer shape as `NearbyConnectionsTransport`
      (lobby layer: `hostRoom`/`joinRoom`/`roomCode`/`connectedPlayers`;
      game layer: `send`/`onMessageReceived`/`onPlayerJoined`/
      `onPlayerLeft`) but simpler — the relay already deals in the
      stable, client-chosen `playerId` a `GameModule` needs directly, so
      unlike Nearby's endpoint-id translation layer
      (`setPlayerIdMapping`), none is needed here. `OnlineLobbyMessage.
      GameStart` plays the same role as `NearbyLobbyMessage.GameStart`.
      New `OnlineEntryScreen`/`OnlineHostLobbyScreen`/
      `OnlineJoinLobbyScreen` mirror Nearby's lobby shape, with a typed
      4-character room code standing in for Nearby's Bluetooth/Wi-Fi
      discovery (nothing to discover over the internet). Settings gained
      an "Online multiplayer" section (a `ws://`/`wss://` server address
      field); the entry screen gates Host/Join behind it being set, same
      spirit as Nearby's radio-enabled gate. `GameSessionManager` got a
      `pendingOnlineTransport` field (mirrors `pendingNearbyTransport`)
      and an explicit, documented-as-intentionally-dead `PlayMode.ONLINE`
      branch in `createTransport` (Online, like Nearby, always launches
      through the already-connected-transport `launchGame()` overload
      once the lobby finishes, never the zero-arg one). `UnoGame` now
      lists `PlayMode.ONLINE` in `supportedModes` — the same
      host-authoritative `UnoNetMessage` flow already proven over
      Nearby works unmodified over any `MultiplayerTransport`, no UNO
      code changes needed.
      **Real bug found and fixed via on-device testing, not code
      review**: Android has blocked plain-`ws://` (cleartext) traffic by
      default since API 28. The first live test against a real emulator
      failed outright — "CLEARTEXT communication ... not permitted by
      network security policy" — before a network security config
      existed. Fixed properly rather than papered over: a release-build
      config (`app/src/main/res/xml/network_security_config.xml`) that
      keeps cleartext disabled (a real deployment must use `wss://`
      regardless), plus a debug-build-only override
      (`app/src/debug/res/xml/...`) that permits it — Gradle's resource
      merging applies the override only to debug builds, which is what
      this whole feature is actually tested through; a release build
      would stay strict.
      **Verified live end-to-end on an Android emulator** against a
      locally-run `server/` instance: configured `ws://10.0.2.2:8080`
      (the emulator's alias for the host machine) in Settings, tapped
      Host, and received back a real server-issued room code ("VPC6") —
      proving the whole chain (OkHttp WebSocket client → relay server →
      lobby UI) actually works end-to-end, not just compiles. Also
      verified the Join error path: a nonexistent room code surfaced the
      server's real "Room not found" response directly in the UI.
      **Not verified this pass**: a full two-player match played to
      completion (would need a second device/emulator running
      simultaneously, none was available in this session), and any real
      `wss://` (TLS) deployment — `server/README.md` documents the
      deployment step plainly as the user's own choice of hosting
      (Render/Fly/Railway/a VPS), since creating that account isn't
      something this assistant does on anyone's behalf.
- [x] 13. Remaining catalog — scoped down from the original three-part
      bullet (Solitaire/Poker/Hearts, Tic-Tac-Toe variants, picture
      puzzles) to one solid representative of each, rather than
      attempting all of Poker and Hearts' considerably larger multi-round
      betting/scoring rulesets in the same pass:
      - **13a. Klondike Solitaire** (`games/solitaire/SolitaireGame.kt`)
        — standard draw-1 rules, tap-select-then-tap-destination (no drag
        input model exists in this app), single-card moves only (an
        honest MVP simplification over multi-card run moves). Backed by
        a new shared standard-deck model, `games/cards/Card.kt`
        (`Rank`/`Suit`/`Card`/`Deck`) — nothing like it existed before;
        `CardVisual` was purely presentational and `UnoCard` is a
        deliberately custom-suit deck, neither reusable here. Reuses
        `PlayingCardView`/`CardScale`/`CardSounds` as-is. Session-tally
        New Game/Back to Menu flow. No CPU-difficulty lever — a
        solved-vs-not solitaire deal has no honest one.
        **Real bug found by adversarial review, fixed before shipping**:
        `canPlaceOnFoundation`/`canPlaceOnTableau` compared cards using
        `Card.kt`'s shared `Rank.value`, which is documented as the
        *high-Ace* ranking (Ace=14) for trick-taking/poker code — but
        Klondike needs low-Ace sequencing (Ace=1). This meant no
        foundation could ever accept a second card once an Ace landed
        (`top.value + 1 = 15`, no rank has that value), so the win
        condition was unreachable in *every* deal — a bug this session's
        own read-through review of the code missed, caught only by a
        dedicated adversarial reviewer hand-tracing an actual A→2
        foundation move. Fixed with a local `lowAceValue()` helper.
      - **13b. Sliding Puzzle** (`games/slidingpuzzle/SlidingPuzzleGame.kt`)
        — the sliding-tile half of "picture puzzles (jigsaw/sliding
        tile)"; jigsaw needs image-slicing/bitmap asset infra this
        project doesn't have, so this is the honest, tractable MVP for
        that bullet. Classic 15-puzzle mechanics generalized to NxN.
        Solvability is guaranteed *by construction* — the scramble is a
        random walk of legal single-tile moves starting from the solved
        board, never a shuffle-then-check-parity (a plain shuffle is
        unsolvable exactly half the time). Difficulty scales grid size
        and scramble depth (EASY 3x3, MEDIUM 4x4, HARD 5x5) via
        Settings' "Default CPU difficulty", same pattern as every
        solo-puzzle pass under item 9. Tiles are colored by number
        (HSV-spaced hues) so a solved board reassembles a simple color
        mosaic — earns the "picture" framing honestly without an
        image-loading dependency.
      - **13c. Tic-Tac-Toe Misère variant** (`games/tictactoe/
        TicTacToeGame.kt`) — identical rules except completing
        3-in-a-row *loses* for whoever completed it. A `misere: Boolean`
        flag (default `false`, zero behavior change to the existing
        standard-mode routes) flips the win/loss meaning everywhere the
        standard rules treat "line completed" as "mover wins": the
        human-move scoring, the minimax terminal evaluation (same
        faster-win/slower-loss depth preference either way, just with
        which side "winning" maps to inverted), and the MEDIUM/EASY
        heuristic's naive win-seeking (replaced under misère by a
        dedicated heuristic that avoids self-completing lines and
        prefers moves that minimize the opponent's safe replies). Board
        stays 3x3 — generalizing board size is a separate, larger lift
        or out of scope here (the unpruned minimax only works at this
        size). New "Play Tic-Tac-Toe (Misère vs CPU)" menu button.
      **A process note, not a feature**: 13a/13b/13c and 9k were all
      built the same session via two workflows running at the same
      time, both of which needed brief windows editing
      `MainActivity.kt`/`MainMenuScreen.kt` to verify their own changes
      compiled before reverting. Running truly concurrently (not
      staggered, no worktree isolation) let one workflow's revert wipe
      out the other's still-uncommitted work mid-flight — 9k's Word
      Search pass was lost this way and had to be rebuilt from scratch
      once discovered (see 9k's own note above). Everything landed
      correctly in the end, but the lesson is to stagger workflows that
      share the same "temporarily edit, verify, revert" touchpoints, or
      give them worktree isolation, rather than run them fully parallel.

## Fixes and hardening

A follow-up pass across the existing v1.0.0 codebase, run as several
parallel, file-scoped lanes plus a set of UNO fixes done directly. Organized
by area; each item names the files it touched. Nothing below is a new
Roadmap item — it's bugfixes and hardening on top of what's already listed
above.

### Server (`server/`)
- Fixed a crash where any client sending the JSON literal `"null"` (or any
  other non-object JSON value) reached an unguarded `switch` on `msg.type`
  and threw, taking down that connection's handling.
- Fixed a room-membership leak: re-hosting or re-joining without leaving
  first orphaned the socket's prior room. Note: the fix calls the
  leave/cleanup path unconditionally at the top of both the host and join
  handlers, so a socket whose *new* host/join attempt then fails (e.g. a bad
  room code) still loses its old room membership rather than staying in it —
  flagged as a behavioral nuance worth a product decision, not obviously a
  bug.
- Added proportionate hardening: a 64KB WebSocket `maxPayload`, a 500-room
  cap with a bounded (50-attempt) room-code allocator, a 30s ping/pong
  heartbeat that terminates unresponsive sockets, a 5-minute sweep that reaps
  rooms idle 30+ minutes, per-socket join-failure rate limiting (disconnect
  after 20 failures), 40-character `displayName` truncation, and timestamped
  connect/host/join/leave/error logging.
- Expanded `server/smoke-test.js` into a self-contained regression suite (it
  spawns and kills its own `node index.js`, on a random high port by
  default) covering the crash/leak fixes above plus duplicate-playerId
  rejection, room-full rejection, and the last-player-leaves-deletes-room
  path — 17 checks, all passing. Added `.github/workflows/relay-server.yml`
  to run `npm ci && npm test` in `server/` on any push/PR touching
  `server/**`. Documented all of the above in `server/README.md`.

### UNO (`games/uno/`, `ui/UnoScreen.kt`, `ui/UnoHouseRulesScreen.kt`)
- Fixed `PlayMode.ONLINE` never actually going networked — `UnoGame`'s
  `isNetworked` check and its listener registration in `init()` previously
  only matched `LOCAL_AD_HOC`.
- Fixed a sender-spoofing gap: the host now validates that a network
  `Intent`'s claimed acting seat actually belongs to the real sender (per
  `MultiplayerTransport`) before applying it, instead of trusting a
  client-declared player index.
- Fixed `UnoRules.forcePlayDrawnCard`'s default (`true` → `false`, matching
  the class's own "classic = every flag false" doc) and implemented real
  optional play: drawing a still-playable card now leaves the turn open long
  enough to act, with a new "Keep card" action for when the house rule
  doesn't force a play.
- Closed a privacy leak: every networked state broadcast previously sent
  every seat's real hand to every peer. The host now sends a per-recipient
  copy with every other seat's hand replaced by same-length placeholder
  cards, so hand counts stay accurate but contents are never exposed over
  the wire.
- Gave `UnoBot` a real EASY/MEDIUM/HARD ladder wired to Settings' "Default
  CPU difficulty" — UNO was the last bot in the suite ignoring that setting.
- Added a UNO House Rules pre-game screen (`UnoHouseRulesScreen.kt`, route
  `uno-house-rules` → `uno-custom`) exposing the already-implemented
  stacking/7-0/jump-in/force-play toggles for local vs-bot and pass-and-play
  modes. **Not yet extended to Nearby/Online lobbies**, which still launch
  UNO with classic defaults.
- Added a text label and a screen-reader `contentDescription` to each of the
  Wild-card color picker's four swatches (previously zero non-color
  differentiation).
- Wired Settings' Reduced Motion into UNO's discard-pile transition and the
  shared `FannedHand` fan-lift animation via a new `LocalReducedMotion`
  CompositionLocal (`settings/LocalReducedMotion.kt`, mirrors the existing
  `LocalCardScale` pattern), provided once at `MainActivity`'s root. Because
  `FannedHand` is shared, Mancala's capture animation picks up Reduced Motion
  through the same CompositionLocal without UNO-specific code.

### Word Tiles (`ui/TileGameScreen.kt`, `games/wordgames/tiles/TileBot.kt`)
- Fixed the "human player" index being computed as a fixed "first non-bot
  player," which broke local pass-and-play for every seat after the first —
  the same bug already found and fixed in `UnoScreen.kt`. Ported that
  screen's mode-aware `humanIndex()` verbatim (LOCAL_AD_HOC uses
  `context.localPlayerIndex`; pass-and-play uses
  `TileGameState.currentPlayerIndex`; vs-CPU keeps "first non-bot player").
  Word Tiles doesn't currently declare `LOCAL_AD_HOC` support, so that branch
  is unreachable today but kept for parity and readiness.
- Fixed a potential multi-second UI freeze/ANR in `TileBot`'s HARD-difficulty
  opening move: it exhaustively evaluated every rack permutation × every
  blank-letter combination with no bound (up to ~9.3M dictionary lookups for
  a rack holding both blanks). Added a fixed `HARD_OPENING_WORD_BUDGET =
  20_000` lookup budget with an early-exit counter, the same pattern the file
  already used for HARD's attach-move anchor search — comfortably above the
  ~13.7k lookups a blank-free rack needs, so the common case is unaffected.

### Dominoes and Mancala (`ui/DominoesScreen.kt`, `games/dominoes/DominoGame.kt`, `ui/MancalaScreen.kt`, `games/mancala/MancalaGame.kt`)
- Fixed a Dominoes UI bug where "Play on Left"/"Play on Right" stayed enabled
  and fired success feedback even when the selected domino didn't match that
  end — each button now checks its own end's pip value and only fires
  feedback on an actual successful play.
- Corrected a stale `DominoGame` class KDoc claiming a "5-6 for 3-4 players"
  hand size; the code has always dealt 5.
- Added a Play Again + running session score to both Dominoes and Mancala on
  match-over (previously "Back to menu" was the only option), mirroring
  Tic-Tac-Toe's `roundOver`/`matchOver` + `playAgain()`/`leaveSession()`
  split. Dominoes scores real "Draw Dominoes" rules (hand winner scores the
  pip total left in every other hand); Mancala uses a simple win/loss/draw
  tally. This required renaming `DominoState.matchOver` → `handOver` and
  `MancalaState.matchOver` → `roundOver` (per-hand/per-game end) and adding a
  new session-level `matchOver` on both game classes — which also fixes a
  latent bug where `GameSessionManager.endActiveGame()` fired the instant a
  single hand/game ended, rather than when the player actually left.
- Added `MancalaGame.captureCandidates()` (reusing the existing
  `legalMoves()`/`simulateSow()` pair, which grew a `landingCursor` field to
  support it) so `MancalaScreen` can outline, before the player taps, which
  legal pit would land in an empty pit of theirs (a capture) — read-only,
  doesn't touch `sow()`'s real rules.
- Gated Mancala's pit capture-scale animation behind Settings' Reduced
  Motion, the same `LocalReducedMotion` + `snap()` technique already used in
  `FannedHand`.

### Solitaire (`games/solitaire/SolitaireGame.kt`, `ui/SolitaireScreen.kt`)
- Added a bounded (5-move) undo history: a private snapshot stack pushed
  before each mutating move (stock draw/recycle, or a completed tableau/
  foundation move), capped by dropping the oldest entry, cleared on
  `startMatch()`. A new `undo()` restores the last snapshot; a new
  `canUndo` flag (mirroring the existing `gamesWon`/`matchOver` pattern)
  drives a new "Undo" button next to "Back to Menu" in `SolitaireScreen.kt`.
  Selecting/deselecting a card does not push a snapshot — undo reverses
  moves, not selection taps. Known edge case: undoing the winning move
  correctly reverts `won` to `false`, but the `gamesWon` session tally
  (already a monotonic "solved deals this session" counter) is not
  decremented.

### Settings (`theme/AppTheme.kt`, `ui/SettingsScreen.kt`, `MainActivity.kt`)
- Wired Settings' "Text size" slider through to actual rendering: `AppTheme`
  now takes a `textScale: Float`, builds a scaled `Typography` (multiplying
  fontSize/lineHeight on all 15 Material3 text styles, skipping Em-based or
  unspecified units to avoid double-scaling), and `MainActivity` passes
  `textScale = settings.textScale` — every screen now reflects the slider,
  not just the settings screen's own preview label.
- Added a confirmation `AlertDialog` ("Reset all settings? This can't be
  undone.") before "Reset all settings" actually calls `resetAll()`,
  matching the app's convention of confirming irreversible actions.

### Tests (new `app/src/test/java/com/gamesuite/` JUnit source set)
Five pure-logic JUnit test files (no Robolectric/Compose UI), driving each
game through its public API:
- `TicTacToeGameTest` — the HARD misère minimax bot never voluntarily
  completes its own line when a safe move exists.
- `SolitaireGameTest` — an Ace banks to an empty foundation, a Two banks
  onto an Ace, a King is rejected onto an exposed Ace (regression coverage
  for the documented low-Ace/high-Ace bug fixed in Roadmap item 13a).
- `MancalaGameTest` — the HARD minimax selector always sows from a pit that
  had stones beforehand (parsed from the public `lastAction` status string,
  since the chosen pit isn't otherwise observable).
- `SlidingPuzzleGameTest` — an independent inversion-parity + blank-row
  solvability check against 200 scrambles per difficulty tier.
- `UnoBotTest` — `UnoBot.chooseMove` across open-turn, stacked-+2,
  stacked-+4-on-+2, stacked-Wild-Draw-Four, no-legal-card, and a
  HARD-reserve-filtering case, for all three difficulty tiers.

One pre-existing, out-of-scope issue was flagged (not fixed) while writing
these tests: `MancalaGame.kt`'s `sow()`/`playBotTurn()` still referenced the
old `matchOver` field name after a concurrent rename to `roundOver`, which
would not compile until the Dominoes/Mancala lane above finished — resolved
by that lane, not by the test files themselves.

## Depth, accessibility, and gameplay pass

A second follow-up pass: a persistent stats layer and a menu redesign done
directly, plus nine parallel, file-scoped lanes covering screen-reader
accessibility (the app had none anywhere before this) and a set of targeted
gameplay pitches from the audit. Organized by area.

### Persistent stats (`stats/`, `core/GameModule.kt`, `core/GameSessionManager.kt`)
- Every game already computed a correct result at match end; nothing kept it
  past the current session. `GameSessionManager` now resolves each finished
  match to a `MatchOutcome` (which game, and this device's own win/loss/draw)
  the moment it still has both the `GameContext` and `GameResult` available,
  and exposes it as `lastMatchOutcome` — the shell (not any game) computes
  this, so no game needs to know about stats and the stats layer needs no
  game-specific rules.
- Added `StatsRepository`/`StatsViewModel` (DataStore-backed, mirroring
  `SettingsRepository`/`SettingsViewModel`'s exact shape: one JSON blob under
  one key, decoded defensively) and a new "My Stats" screen showing
  matches/wins/losses/draws per game, with a reset action.

### Main menu (`ui/MainMenuScreen.kt`, `MainActivity.kt`)
- Replaced the flat 20-button scrolling list with categorized sections
  (Board games / Cards / Word games / Arcade & puzzles), each in its own
  card. Every individual button's launch behavior is unchanged from before —
  only the layout is new.
- Added a "Continue playing" row sourced from real match history (the three
  most recently played games, each re-launching that game's own default
  configuration) and a dismiss-once first-run welcome banner (a new
  `hasSeenOnboarding` setting) — the audited "flat wall of unlabeled buttons
  with zero onboarding" finding.
- Added "My Stats" and kept "Settings" in the top bar.

### Accessibility (every game screen)
Added `Modifier.semantics { contentDescription = ... }` (and, for UNO, a
`liveRegion` announcement on turn/status text) across every game that had
none: Tic-Tac-Toe's 9 cells, Mancala's 14 pits, Dominoes' hand/chain tiles,
Solitaire's tableau/waste/foundation cards, Word Tiles' rack/board tiles,
UNO's hand/discard/opponent-fan cards, Word Search's grid cells, and
Crossword's grid cells plus Hangman's letter buttons. `FannedHand` (UNO's
hand renderer) gained an optional `descriptionOf` parameter so this needed
no breaking change to its one call site.

### Tic-Tac-Toe (`games/tictactoe/TicTacToeGame.kt`, `ui/TicTacToeScreen.kt`)
- Added a Wild variant (`wild: Boolean`, mirroring the existing `misere`
  flag): each mover chooses X or O before placing; the win-check already read
  cell content rather than player identity, so it needed no change. Composes
  with `misere`. New route `tic-tac-toe-wild`.
- Wild doubles minimax's branching factor (a mover also picks a symbol), so
  `minimax`/`minimaxBestMove` gained alpha-beta pruning — provably identical
  results to unpruned search, just faster.

### Air Hockey (`games/airhockey/AirHockeyGame.kt`, `ui/AirHockeyScreen.kt`)
- The CPU paddle now moves in real 2D instead of a fixed horizontal rail: it
  retreats toward its goal when the ball is deep in the player's half or just
  conceded, and pushes forward at difficulty-scaled depth once the ball
  crosses center. Existing speed-cap/collision tuning untouched.
- Added local pass-and-play (`PlayMode.SINGLE_DEVICE_PASS_AND_PLAY`): a
  second independent drag zone for the top half, routed via one
  `awaitPointerEventScope` loop instead of a single-pointer handler. Also
  fixed `GameResult` only ever scoring `context.localPlayerIndex` — the
  second local player is now scored too, so pass-and-play results (and the
  new stats layer) reflect both players.

### Solitaire (`games/solitaire/SolitaireGame.kt`, `ui/SolitaireScreen.kt`)
- Added one-tap Auto-complete once no card is face-down anywhere (the
  standard "the rest is forced" Klondike condition): plays one legal move
  every ~400ms via the existing single-card-move path. A bounded heuristic,
  not a general solver — on deals needing more than one blocking card moved
  out of the way, it stops a few cards short and silently hands control back
  rather than getting stuck. Auto-played moves go through the same undo
  history as manual moves.

### Word Tiles (`games/wordgames/tiles/TileGame.kt`, `ui/TileGameScreen.kt`)
- `submitMove()` already validates every word before committing, so a
  challenge that could overturn a play isn't meaningful here. Added a
  lower-stakes "word info" affordance instead: tapping the most recently
  played word(s) confirms they're valid dictionary words — addresses the
  audited "can't tell if the bot's odd-looking word is legit" finding without
  a scoring/penalty mechanic the engine can't honestly support.

### Word Search and Sliding Puzzle (daily-challenge groundwork)
- Both `WordSearchGame.startMatch(seed: Long?)` and
  `SlidingPuzzleGame.startMatch(dailySeed: Long?)` now accept an optional
  seed that, when set, replaces the unseeded scramble/placement RNG — the
  actual "read today's date" logic stays out of these files by design.
  Neither is wired to a menu entry yet; that's a small, well-defined
  follow-up (compute an epoch-day seed at a new "Daily Challenge" call site).
  Sliding Puzzle separately gained a live move counter/stopwatch, a
  best-move/best-time record per difficulty (its own self-contained
  DataStore, independent of the shared stats layer), and its first-ever
  sound/haptic feedback.

### Crossword and Hangman (`games/wordgames/crossword/`, `games/hangman/HangmanGame.kt`)
- Crossword no longer always seeds a puzzle on the single longest word in the
  pool (identical anchor word every "New Puzzle" tap on Medium/Hard) — it now
  picks among the longest word and any within 2 letters of it, further
  avoiding the last 5 anchors used this session.
- Hangman excludes the last 5 words played from the next draw (falling back
  to a repeat only once the pool is smaller than that window).
- Replaced Crossword's "reveal every entry's first letter for free" hint with
  a per-entry hint (reveal one more letter of the selected clue), capped at 3
  uses per puzzle.

## Online reconnect, spectator mode, daily challenge, and store readiness

A third follow-up pass, focused on the online-multiplayer reliability gap
and the remaining roadmap items from the audit: reconnect/resume, a
Daily Challenge menu, store-readiness documentation, and a localization
starter.

### Reconnect & Resume (`server/index.js`, `transport/OnlineTransport.kt`, `transport/MultiplayerTransport.kt`, `games/uno/UnoGame.kt`)
- The relay now holds a dropped connection's seat open for 30 seconds
  (`RECONNECT_GRACE_MS`) instead of immediately treating it as a departure —
  other room members are told `playerDisconnected`, not `playerLeft`. A
  `join` with the same room code and playerId within that window is treated
  as a resume (`reconnected`, with the existing roster) rather than
  rejected as a duplicate; past the window, or an explicit `leave`, the seat
  actually vacates.
- `OnlineTransport` implements the client half: an unexpected disconnect
  triggers five retry attempts with backoff (~23 seconds total, under the
  relay's grace window) before surfacing `connectionError`. A successful
  resume fires a new `MultiplayerTransport.onReconnected` hook (default
  no-op; only `OnlineTransport` implements it — Nearby has no reconnect
  story yet, a known remaining gap) so the game layer re-requests full
  state exactly like a fresh join does, since broadcasts may have been
  missed while disconnected.
- `UnoGame` wires this: a guest re-requests state on reconnect; the host
  proactively re-broadcasts (covers the case where a guest's own connection
  to the relay never dropped, only the host's did, so no guest would ever
  think to ask). Also wired the previously-dead
  `MultiplayerTransport.onPlayerLeft` hook into the host's status line
  (`s.lastAction`), so a permanently-departed opponent is now visible.
- `smoke-test.js` gained coverage for the full reconnect lifecycle
  (disconnect → reconnect within the grace window → the seat actually
  vacating once the window expires), using an overridable grace period
  (`RECONNECT_GRACE_MS_OVERRIDE`) so the test suite doesn't need to wait 30
  real seconds — 25 checks total, all passing.

### Spectator mode — server groundwork (`server/index.js`, `transport/OnlineTransport.kt`)
- `join` with `"spectator":true` gets a read-only seat: excluded from
  `MAX_PLAYERS_PER_ROOM` and from the roster a `GameModule` builds its
  player list from, but included in every broadcast — a spectator's client
  renders the live game exactly like any non-owned seat already does,
  through the same per-recipient hand redaction UNO already applies.
  `OnlineTransport.joinRoom(..., spectator = true)` is the client entry
  point. **Not yet wired to any UI** — no lobby-screen "Watch" button exists;
  that's the one remaining piece, not a server or transport limitation.

### Daily Challenge (`ui/MainMenuScreen.kt`, `MainActivity.kt`)
- Wired the daily-seed groundwork from the previous pass into two real menu
  entries: "Word Search — today's puzzle" and "Sliding Puzzle — today's
  scramble," each deriving a stable seed from `LocalDate.now().toEpochDay()`
  at the navigation call site (per those games' own KDocs, the games
  themselves never touch a clock/calendar API).

### Store readiness (`PRIVACY_POLICY.md`, `README.md`)
- Added a privacy policy accurately describing what GameSuite does and
  doesn't collect (nothing analytics/ads/account-related; local-only
  settings and stats; an honest description of what the optional
  self-hosted online relay does and doesn't see).
- Stated the monetization stance explicitly: none, by design — not a
  silent gap. Still missing before a real store listing: a custom launcher
  icon and store assets (screenshots, feature graphic) — asset-creation
  work, not code.

### Localization starter (`res/values/strings.xml`, `res/values-es/strings.xml`, `ui/MainMenuScreen.kt`)
- `MainMenuScreen` — the first screen every player sees — is now fully
  extracted to string resources, with a real Spanish translation
  (`values-es/strings.xml`) proving the pipeline actually switches locales,
  not just that a strings.xml file exists. The other ~190 `Text()` call
  sites across the 11 game screens and Settings/Stats are **not** yet
  extracted; there's no infrastructure blocker left, only the repetitive
  work of applying this same pattern screen by screen.

## Design notes worth remembering

- **A game never touches networking or dual-screen code directly** — only
  `MultiplayerTransport` and `GameContext`. This is what makes "works
  online" or "works dual-screen" a platform-level upgrade instead of a
  per-game rewrite.
- CPU bots for a game (e.g. UNO) should be implemented as a "fake player"
  that responds to the same `MultiplayerTransport` messages a human would —
  keeps bot logic decoupled from UI.
- Team play (UNO teams, etc.) is modeled via `PlayerInfo.teamId`, not a
  separate code path — game rules just group players by team id.
- Native Android is Android-only long-term. If iOS/desktop ever becomes a
  real goal, that's a second codebase later (likely Flutter or Compose
  Multiplatform) sharing game *rules* logic where practical, not a rewrite
  of this decision now.
