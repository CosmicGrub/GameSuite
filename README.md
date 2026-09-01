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

Skeleton only — no Android Studio project exists yet. The `app/src/...`
folder here holds the core interfaces and the first proof-of-concept game
(Tic-Tac-Toe logic) as loose Kotlin files. Nothing has been opened in Android
Studio yet.

## Disk space setup (already done)

- Android Studio: `C:\Program Files\Android\Android Studio` (~3.3GB, left on C:)
- Android SDK: moved to **`Z:\Android\Sdk`** (was 15GB on C:, now freed)
- C: free space: ~3.2GB → ~18GB after the SDK move

**One remaining manual step:** point Android Studio at the new SDK location
(see Step 1 below) — this wasn't done automatically since it's a settings
change best done by hand in the GUI.

## Step 1 — point Android Studio at the moved SDK

1. Open **Android Studio**.
2. From the Welcome screen: **More Actions → SDK Manager**. (If a project is
   already open instead: **File → Settings → Languages & Frameworks →
   Android SDK**.)
3. At the top, find **Android SDK Location** and change it to:
   ```
   Z:\Android\Sdk
   ```
4. Click **Apply/OK**. Android Studio should recognize the existing SDK
   packages there immediately (nothing needs re-downloading).
5. (Optional but recommended) Set a persistent `ANDROID_HOME` environment
   variable to `Z:\Android\Sdk` so command-line Gradle builds find it too —
   Windows Settings → "Edit environment variables for your account" → New
   → Name: `ANDROID_HOME`, Value: `Z:\Android\Sdk`.

## Step 2 — create the project

1. Android Studio Welcome screen → **New Project**.
2. Template: **Empty Activity** (this is the Compose template in current
   Android Studio versions — confirm "Compose" is mentioned in its description).
3. Name: `GameSuite`. Package name: `com.gamesuite`. Save location:
   `Z:\GameSuite` — if Android Studio complains the folder isn't empty
   (it has this README + `app/` already), that's fine, let it initialize
   into the existing folder, or create at `Z:\GameSuiteTmp` and merge
   afterward — tell me which happened and I'll help reconcile.
4. Minimum SDK: **API 26 (Android 8.0)** is a safe floor for Compose;
   raise later if you end up needing newer window/foldable APIs that require
   higher.
5. Let Gradle sync finish (first sync can take a few minutes).

## Step 3 — bring in these Kotlin files

The files below are already written, under `Z:\GameSuite\app\src\main\java\com\gamesuite\...`.
Once Android Studio generates its own `app/` structure, make sure these land
in (or get moved into) the matching path in the generated project:

```
app/src/main/java/com/gamesuite/
  core/
    GameModule.kt             - contract every game implements, GameContext/GameResult
    GameSessionManager.kt     - ViewModel that launches games and tracks active context
  transport/
    MultiplayerTransport.kt   - abstract move/event channel
    LocalPassAndPlayTransport.kt - first implementation (single device)
  games/tictactoe/
    TicTacToeGame.kt          - first proof-of-concept game module
```

You'll also need these dependencies in `app/build.gradle.kts` (Android
Studio's Compose template includes most of this already):
```kotlin
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
implementation("androidx.navigation:navigation-compose:2.8.0")
```

## Step 4 — prove the skeleton works (next session)

1. Create a `MainMenuScreen` composable with a "Play Tic-Tac-Toe" button that
   calls `gameSessionManager.launchGame(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, players, 0)`.
2. Set up `NavHost` (navigation-compose) with two destinations: main menu and
   tic-tac-toe, navigating to the latter once `activeContext` becomes non-null.
3. Create a `TicTacToeScreen` composable: a 3x3 grid reading `TicTacToeGame.board`
   state, each cell calling `ticTacToeGame.cellClicked(index)` on tap; navigate
   back to main menu when `matchOver` becomes true.
4. Run on an emulator or your Z Fold/Tab S9. Two players should be able to
   take turns tapping cells, passing the device back and forth. That proves:
   shell → session manager → game module → transport all connect correctly.

Once that loop works, the pattern for every future game is the same: write a
class implementing `GameModule`, add a Compose screen for it, register it in
the library. No shell code changes needed.

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
- [ ] 9. Research + upgrade remaining games, one at a time in priority
      order (per user's explicit pacing choice — not batched): Tic-Tac-Toe,
      Dominoes, Mancala, Hangman, word games (Word Search/Crossword/Word
      Tiles), Air Hockey. Each pass: real research into rules/variants/
      difficulty scaling/player feedback, then implement + verify on-device,
      same rigor as UNO got.
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
- [ ] 12. `OnlineTransport` — start with Firebase Realtime Database/
      Firestore or a lightweight custom backend for rooms/matchmaking
- [ ] 13. Remaining catalog: standard 52-card games (Solitaire, Poker,
      Hearts — reusing `games/cards/`), Tic-Tac-Toe variants, picture
      puzzles (jigsaw/sliding tile)

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
