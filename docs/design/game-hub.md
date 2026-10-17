# Game Hub (design contract)

Worktree `Z:/GameSuite-wt-game-kit`, branch `session/game-kit`. This is the contract every agent
working on the Hub, the new menu, UNO and the per-game wiring follows.

## Flow

Bottom bar (Home | Games | Stats | Settings; a side rail at EXPANDED width) -> **game card** ->
**Game Hub** (`hub/{gameId}`) -> the game, or a lobby.

- A game's card opens its Hub. A game whose `GameHubSpec.skipsHub` is true (Breakout, Tower Defence,
  Party Toolkit) launches directly from its card.
- Hub, top to bottom: header (title, tagline) -> **Resume** card(s) for saved games of this game ->
  a large **Play** button that repeats the last mode with its last options -> **mode tiles** ->
  the selected mode's **options panel** with an explicit **Start** button.
- **Play**: IMMEDIATE modes (solo, vs CPU, daily) launch at once. READY_SCREEN modes (pass and play)
  select that mode and open its panel (names + Start; the device is about to be handed over).
  LOBBY modes (Nearby, Online) open the lobby.
- **Start** (the panel button) always launches the mode with the options shown.
- Daily is a mode tile (kind DAILY) plus a "Daily" badge on the card. There is no Daily section.

## Model (already built, `app/src/main/java/com/gamesuite/core/`)

- `GameHub.kt`: `GameHubSpec`, `HubMode`, `HubOption` (Choice / Toggle / Stepper), `HubChoice.applies`
  (a preset sets other keys), `HubChoices`, `HubStart`, `HubModeKind`, `HubKeys`, `HubOptions`,
  `LaunchOptions{cpuDifficulty, firstMoverIndex, extras}`, `MatchLaunch`, `HubLaunch`, and
  `buildHubLaunch(spec, mode, choices, names, random)`.
- `GameHubSpecs.kt`: the table of 29 games. `GameHubSpecs.all/get/byId/byFamily`. Nearby/Online modes
  appear only for ids in `GameHubSpecs.networkedGames` (initially only "uno").
- `HubChoicesStore`: remembered last mode + per-mode choices (`lastModeId`, `load`, `save`).
- `SavedGame` records `launch: SavedLaunch` and `summary`; `GameSessionManager.resumeSavedGame(saved)`
  re-creates the launch and arms `pendingResume`; navigate to `saved.navRoute` afterwards.
- `GameSessionManager.launchGame(mode, players, localPlayerIndex, options: LaunchOptions)` and
  `launchOptions` / `cpuDifficultyFor(default)`: screens read per-launch choices from
  `sessionManager.launchOptions`. `LaunchOptions` is reset by every launch.
- `settings/GameSpeed.kt` + `LocalGameSpeed`: `speed.scale(baseMs)` multiplies any bot/beat delay.
- `PlayerNameStore` (slots 0..3 for pass-and-play seat names), `cleanPlayerName`.

## Per-launch options a game screen must honour

A screen reads them ONCE when it starts (inside its `LaunchedEffect(context)` start-up, before
`tryResume` / `startMatch`):

- `sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)` instead of
  `settings.defaultCpuDifficulty` when setting `game.difficulty` (CPU-opponent games only).
- `launchOptions.firstMoverIndex`: the roster seat that takes the first turn. Roster order never
  changes (humans first, then CPUs); a game whose first mover is fixed by its rules makes the
  chosen seat the one that plays first (chess: seat 0 is White, so a CPU-first game gives the
  CPU... see each game's brief). A resumed game ignores it (the snapshot already knows).
- `launchOptions.extras`: game-specific choices by option key (UNO rules, Tic-Tac-Toe variant).
- Bot "thinking" and beat delays are multiplied by `LocalGameSpeed.current.scale(...)`.

## Networking (Nearby / Online)

Lobbies are generic (`gameRoute` / `gameDisplayName` parameters) but MainActivity hard-codes UNO;
the Hub work parameterizes the routes as `nearby-entry/{gameId}`, `nearby-host-lobby/{gameId}`,
`online-entry/{gameId}`, `online-host-lobby/{gameId}`. A networked engine supports
`PlayMode.LOCAL_AD_HOC` and `PlayMode.ONLINE`: host-authoritative, the host validates and
broadcasts state, guests send move requests (see UnoGame and UnoNetMessage; Chess and Tic-Tac-Toe
have partial StateSync code). A guest game is not resumable.

## Rules for all agents

- Edit only the files your brief lists. No git writes, gradle, adb, cargo or npm; you cannot compile,
  so grep every symbol and signature you use. Write Kotlin only with the Edit and Write tools.
- Unique game-prefixed names for new private top-level types.
- Keep every existing public composable signature unless your brief says otherwise.
- Existing tests must still compile and pass.
