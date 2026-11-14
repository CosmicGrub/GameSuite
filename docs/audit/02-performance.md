# 02 - General performance audit (Android app)

Scope: `app/` (Kotlin/Compose, ~70 games), `shared/` engines (chess, checkers, mancala), `rust/`, `server/` relay only where it touches the client. Worktree `Z:\GameSuite-wt-game-kit`, 2026-11-07.

## How to read this

- READ-ONLY, static audit. **Nothing was profiled, built or run.** Every finding below is "inferred from reading" unless it is explicitly marked MEASURED, and every MEASURED item is a number the repo's own docs/comments record (with the device or host noted), not something I produced.
- Severity: HIGH = likely user-visible stall, crash risk or large battery cost on a mid/low-end phone; MED = measurable waste or a risk on low-end hardware; LOW = hygiene.
- Paths are relative to `app/src/main/java/com/gamesuite/` unless they start with `shared/`, `app/`, `rust/` or `docs/`.

## 1. What is already measured (and what is not)

| Item | Where recorded | Device / host | Value | Caveat |
|---|---|---|---|---|
| Compose Canvas stress pilot (100 enemies, 30 towers, 60 projectiles) | `docs/TOWER_DEFENCE_ADR.md` lines 55-70, README ~1554 | Galaxy Tab S9 FE, on-device, 599 frames | avg 11.13 ms, p95 11.11 ms, max 22.15 ms, 3/599 over 16.67 ms | MEASURED. avg == p95 == 11.1 ms is a 90 Hz vsync interval, so this proves "no jank on a flagship tablet", not CPU headroom. Pilot was deleted; real Tower Defence (`ui/TowerDefenceScreen.kt`) was never re-measured. |
| Chess HARD (depth 4) | `shared/.../chess/ChessGame.kt` ~1893-1911 | desktop JVM, 15 self-play games | ~50 ms avg per move, none near 1 s | MEASURED on desktop JVM only. Authors state phone ART not measured; depth 5 "attempted, not completed". |
| Checkers HARD depth 8/9/10 | `shared/.../checkers/CheckersGame.kt` 1220-1243 | desktop JVM unit test, 60 turns | d8 avg 29.6 / worst 163 ms; d9 (shipped) avg 49.5 / worst 323 ms; d10 avg 277 / worst 1865 ms | MEASURED on desktop. Worst case at d10 is 5.8x the d9 worst, so d9 is near a cliff on slower CPUs. |
| Nonogram generation, early version | `games/nonogram/NonogramGame.kt` ~840 comment | unspecified | "up to ~2.5 s" for 15x15 before propagation was added | MEASURED historically; current cost unknown. |
| Chess/Mancala/Billiards/etc. rotation + jank capture | `app/src/androidTest/.../NavRotationTestSupport.kt` 74-93 (`dumpsys gfxinfo` to logcat "GFXINFO[label]") | real devices | numbers are only written to logcat | No stored baseline anywhere in `docs/`. Nothing asserts on them. |
| Cold start, memory, battery, APK size, 120 Hz behaviour | nowhere | - | - | **No measurement exists.** No Macrobenchmark module, no baseline profile, no `StrictMode`, no perf CI. |
| Known perf-ish defect | `docs/roadmap/UNBUILT-ITEMS.md` section C | - | "Spider daily deal depends on device speed (12 s wall-clock cap)"; "Reaction Duel / Paddle Duel occasional blank first frame on cold start (cause unknown)" | The blank first frame may be the AppSettings-default flip in 2.1 below. |
| Prior static review, same theme | `docs/roadmap/CODE-REVIEW-WAVES-1-4.md` finding 8, 10 | read-only | Per-frame recomposition (Gomoku, Tank, Darts, Chinese Checkers) | Verified FIXED in Gomoku (`ui/GomokuScreen.kt:454`), Tank (`:187`), Darts (`:193`), ChineseCheckers (`:541`). The same defect is still present in other screens, see 3.2. |

## 2. Startup path

### 2.1 [MED] First frame renders with default settings, then flips
`MainActivity.kt:290` collects `settingsViewModel.settings`, whose `stateIn(... initialValue = AppSettings())` (`settings/SettingsViewModel.kt:22-26`) emits defaults until DataStore's first read returns. Theme mode, dynamic colour, named theme, text scale, reduced-motion, card 3D, enhanced-animations and game speed are all applied with defaults first, then the whole tree recomposes with real values (theme flash; for a reduced-motion user, one frame of full-motion code paths, including the mesh-gradient shader below). Also there is no splash-screen API use (`installSplashScreen` absent).
Why: visible flash + a full-tree recomposition at the most expensive moment (cold start); plausible cause of the "blank first frame" defect in UNBUILT-ITEMS.
Fix: block the splash (`installSplashScreen` + `setKeepOnScreenCondition`) until the first settings emission, or seed `AppSettings` from a tiny SharedPreferences mirror read synchronously in `Application`/`onCreate` (one small file, not DataStore).

### 2.2 [LOW] Startup work is otherwise lean
No `Application` subclass, no `runBlocking` anywhere (grep). `onCreate` (`MainActivity.kt:272-287`) only builds a `SavedGameStore` (path only) and registers a lifecycle observer. `MusicManager.get()` (`audio/music/MusicManager.kt:53-92`) loads its library on `Dispatchers.IO`. Home reads saves via `produceState` on `Dispatchers.IO` (`ui/GameCards.kt` rememberSavedGames). One residual: `ui/GameHubScreen.kt:153-155` calls `savedGames.all()` (lists the dir and parses every save JSON) synchronously on the main thread inside `remember`, every time a Hub opens or resumes. Fix: use the same `rememberSavedGames` off-thread helper.

### 2.3 [MED] No baseline profile, release build not minified
- `app/build.gradle.kts:24` `isMinifyEnabled = false`; no `shrinkResources`; no `profileinstaller`/baseline-prof file anywhere. Compose's library profiles are present transitively, but none of the app's ~97k lines (498 Kotlin files) are profiled.
- Debug APK on disk (`app/build/outputs/apk/debug/app-debug.apk`, Nov 7): **39 MB compressed, 64 MB uncompressed, 26 dex files** (classes.dex 18 MB, classes24 13.6 MB, classes26 11 MB). MEASURED (file listing, debug build only; no release APK exists to inspect).
Why: unminified multidex + no profile means slow first launch on API 26-29 devices (more interpreted/JIT time; dex2oat at install is long), and a large install.
Fix: enable R8 (`proguard-rules.pro` already exists and has kotlinx.serialization/OkHttp/Nearby rules; test the release build on a real device because UniFFI/JNA need `-keep` for generated bindings and JNA), add `shrinkResources`, add a Macrobenchmark + baseline-profile module that walks Home, Games, Hub, Chess, Air Hockey, Uno.

### 2.4 [LOW] Home screen is a non-lazy Column
`ui/MainMenuScreen.kt:~100-200`: `verticalScroll(Column)` with every section (Continue, Favourites, Recent, Dailies, Club, tiles) composed eagerly. With ~70 specs the Dailies/Favourites rows are small, so cost is moderate; the Games tab is correctly a `LazyColumn` (`ui/GamesScreen.kt`). Only worth fixing if profiling shows Home TTFD is high.

## 3. Compose rendering and recomposition

### 3.1 [HIGH] Perpetual full-screen shader on Home
`ui/effects/PremiumShaders.kt:280-306` + `ui/MainMenuScreen.kt:~112` (`meshGradientBackground(enabled = !reducedMotion, ...)`). On API 33+ the whole Home tab runs an AGSL `RuntimeShader` full-screen via `drawWithContent`, driven by an infinite 14 s transition, for as long as Home is visible (and Home is the landing screen, so most sessions start here). Each frame also allocates a `ShaderBrush`. The only gate is Reduced Motion; there is no battery-saver / power-save / `isLowRamDevice` check, and no idle pause.
Why: continuous GPU + wakeups on the most-viewed screen; battery and thermal cost. Inferred (not profiled).
Fix: gate on `PowerManager.isPowerSaveMode`, pause after N seconds of no touch, or render the gradient once into a static bitmap and drift it with a cheap transform.

### 3.2 [HIGH] Infinite-transition value read in composition scope (whole-screen recomposition every frame)
The repo already knows the fix (see comments at `ui/UnoScreen.kt:283-298`, `ui/GomokuScreen.kt:454`, `ui/TankBattleScreen.kt:187`: hold the `State<Float>` and read `.value` only inside draw/graphicsLayer lambdas). It is not applied everywhere. Still reading `.value`/`by` in composition scope, so the enclosing composable recomposes every vsync while on screen, even when idle:
- `ui/ShootingGalleryScreen.kt:200-203` (`seconds`, read in the 500+ line screen body, then passed as a parameter to `RangeSurface` at `:536`): a real-time game recomposing its whole HUD every frame. Worst offender.
- `ui/ShogiScreen.kt:286-291`: `pulseAnim by` then `pulse` passed into the board; the same screen recomputes legal-move sets (`legal.filter{...}.toSet()` at ~`:297`) per recomposition.
- `ui/HexScreen.kt:542-546` (exactly the pre-fix Gomoku pattern; 121 cells), `ui/NineMensMorrisScreen.kt:133,797-800`, `ui/HareAndHoundsScreen.kt:552-553`.
- Board-canvas scoped (smaller blast radius, still continuous): `ui/LudoScreen.kt:605`, `ui/SevensScreen.kt:548`, `ui/SixBallScreen.kt:746`, `ui/HoldemScreen.kt:1003` (only when turn/winner), `ui/CarromScreen.kt:602` and `ui/CurlingScreen.kt:642` (only while aiming).
- Also `ui/UnoScreen.kt:311` `animatedTension by animateFloatAsState` recomposes the screen for 600 ms on each tension change (minor).
Why: for quiet board games the pulse is the only thing animating, so these screens burn CPU/battery continuously; on 120 Hz that is 120 recompositions/s. Inferred.
Fix: mechanical; convert each to `State<Float>` and read inside `Canvas`/`graphicsLayer`/`drawBehind`. Add a lint-ish grep test (`rememberInfiniteTransition(...).animateFloat(...).value` or `val x by ...animateFloat` outside draw lambdas).

### 3.3 [HIGH] `specularSweep` re-creates a RenderEffect every frame and reads time in composition
`ui/effects/PremiumShaders.kt:168-190`: `val time by infiniteTransition.animateFloat(...)` is read in composition (recomposes the host each frame), and the `graphicsLayer { }` block builds `RenderEffect.createRuntimeShaderEffect(...).asComposeRenderEffect()` on every invalidation, which also forces the layer into an offscreen render pass. Uses: Air Hockey puck is **unconditional** (`ui/AirHockeyScreen.kt:639`, `enabled = true`, 1400 ms period; ignores `enhanced`, `card3D` and Reduced Motion), Chess board (`ui/ChessScreen.kt:737`, only at MAXIMUM tier), Checkers (`:813`), Dominoes (`:767`), Crossword (`:520`), Hangman (`:430`).
Why: allocation + native effect rebuild per frame on top of a real-time game; the puck sweep runs on the most frame-sensitive screen. Inferred.
Fix: create the `RenderEffect` once, update uniforms on the same shader via `RenderEffect` reuse is not possible, so prefer drawing the sheen in `drawWithContent` using a `ShaderBrush` (read time from the State inside the draw lambda) instead of a layer render effect; gate the puck sweep on `enhanced && !reducedMotion`.

### 3.4 [MED] 37 screens run a perpetual `withFrameNanos` particle loop even when idle
`ui/effects/ParticleBurst.kt:137-148` `rememberParticleBurst()` starts `while(true) withFrameNanos { burst.tick(dt) }` for the screen's whole lifetime, and `tick` (`:89`) does `particles.value = particles.value.mapNotNull{...}` every frame even when empty. Used by 37 screens (grep), including turn-based Blackjack, War, Holdem, Ludo, Sevens, President, Speed, Matching. The authors already fixed it locally in `ui/ColorFloodScreen.kt:748-760`, `ui/DotsAndBoxesScreen.kt`, `ui/ConnectFourScreen.kt:~328` (loop only while particles are alive).
Why: a Choreographer wakeup every vsync on card/board games doing nothing; battery on idle screens and possibly keeps the display at a high refresh tier. Inferred.
Fix: make `rememberParticleBurst` run its loop only while `particles` is non-empty (`snapshotFlow` as in ColorFlood), one change that fixes all 37.

### 3.5 [MED] Other always-on per-frame loops
- `ui/ReactionDuelScreen.kt:184-187` writes `frame.value = it` every frame forever (state write each vsync, readers recompose) plus a second idle loop at `:220`.
- `ui/FishingScreen.kt:185`, `ui/SlotCarsScreen.kt:159`, `ui/LightTrailScreen.kt:147`, `ui/PaddleDuelScreen.kt:194`: `while(true) withFrameNanos/Millis { game.advance()/tick() }` with no lifecycle gating and no stop when paused/over (the games guard internally with `paused`, but the loop keeps waking). Contrast `ui/BreakoutScreen.kt:199-212` which uses `repeatOnLifecycle(RESUMED)` and only runs while simulating.
- `ui/AirHockeyScreen.kt:210-222` shockwave loop and `:283-292` physics loop have no `repeatOnLifecycle`; they rely on the frame clock stopping when stopped. After resume the first `dt` is not zeroed (it is clamped to 50 ms inside Rust `tick_impl` at `rust/gamesuite-sim/src/lib.rs:469`, so safe).
Fix: copy Breakout's pattern into a shared `rememberGameClock(enabled)` helper.

### 3.6 [MED] Air Hockey: full snapshot marshalled across JNA every frame
`games/airhockey/AirHockeyRustGame.kt:140-188`: each frame calls `sim.tick(dt)` then `sim.snapshot().toAirHockeyState()` (UniFFI record serialised through a RustBuffer, then converted into new Kotlin objects) and writes `state.value`, and the screen reads `val state by game.state` at the top (`ui/AirHockeyScreen.kt:128`), so the whole screen recomposes at display rate. The puck and paddles are positioned with `Modifier.offset(x = ...dp)` (`:632`, composition-time, forces a layout pass) instead of `offset { }` / `graphicsLayer`. `shockwaveRings` rebuilds a list with `.map{copy}.filter` per frame.
Why: the "Rust for GC-free physics" goal is partly undone by per-frame allocation on the Kotlin side. Inferred; physics itself is fine (clamped dt, fixed work).
Fix: keep snapshot, but expose positions as separate `State`/`MutableFloatState` read inside draw/`offset{}` lambdas; reuse particle arrays.

### 3.7 [MED] Allocation inside draw blocks in animated games
Counts (grep, whole `ui/`): 141 `Path()` call sites, 290 Canvas/draw sites, only 14 `drawWithCache`. Hot examples drawn every frame during animation:
- `ui/BilliardsScreen.kt:805-828` `drawPoolBall`: per ball per frame, a `Path().apply{addOval}` clip plus 2-3 `Brush.radialGradient(listOf(...))` (16 balls). Table (`:757-800`) rebuilds 3+ gradient brushes each frame though static.
- `ui/ToyBoxingArt.kt:87-125`, `ui/ShootingGalleryArt.kt:32-72,131-183`, `ui/WhackAMoleArt.kt`, `ui/RpsShowdownArt.kt`, `ui/FishingArt.kt`: static backgrounds (sky/mat/counter gradients, ridge paths) rebuilt every frame. Good counter-example already in repo: `ui/TankBattleArt.kt:56-110` caches terrain in `TankArtCache`.
- `ui/ChineseCheckersScreen.kt` (per review: 121 radial brushes per pulse frame; pulse now deferred but brushes still per draw).
Fix: split static layers into `drawWithCache`/`remember`ed `Brush`/`Path` keyed on size; keep only moving sprites in the per-frame lambda.

### 3.8 [LOW] Composable-per-cell grids
Only 14 Lazy* usages; Sudoku (81), KenKen, Kakuro, Crossword (up to 225 cells, each with `animateColorAsState` + several `Animatable`s at `ui/CrosswordScreen.kt:625-635,792-797`), Mahjong (144 tiles) are Box-per-cell. Acceptable for static puzzles but Crossword's per-cell animation state is heavy on low-end devices at first composition. `items(('A'..'Z').toList())` without keys at `ui/HangmanScreen.kt:875`, `ui/TileGameScreen.kt:1783` (static list, harmless).

### 3.9 [LOW] Text scale and card-size sliders write DataStore on every tick
`ui/SettingsScreen.kt:251-267` and `ui/MusicSettingsScreen.kt:205-240` pass `onValueChange = viewModel::setX` directly (a DataStore `edit` per drag callback). Text scale additionally re-densities the entire app per tick via `AppTheme(textScale=...)` (`MainActivity.kt:335`). Fix: keep a local drag state, commit on `onValueChangeFinished`.

### 3.10 [LOW] `HubText.resolve` uses `Resources.getIdentifier` per string
`ui/HubText.kt:20-30` is called for every card title/tagline; `ui/GamesScreen.kt:129-140` calls it twice per spec (~140 lookups) on every search keystroke; `ui/MainMenuScreen.kt` does it for all specs once per locale change. `getIdentifier` is a name lookup (slow relative to `getString(id)`). Fix: build a `Map<String,String>` once per locale and reuse.

## 4. Real-time game loops

Good news (inferred from reading, consistent with docs): the physics/sim games use accumulator-based fixed timesteps with a clamped catch-up, so they are frame-rate independent and OK at 90/120 Hz: Fishing (`games/fishing/FishingGame.kt:222-234`, `elapsed.coerceIn(0,250)`), SlotCars (`games/slotcars/SlotCarsGame.kt:216-228`), LightTrail (`games/lighttrail/LightTrailGame.kt:226-232`), ToySoccer (fixed 120 Hz step, `games/toysoccer/ToySoccerGame.kt:126`), Air Hockey (dt clamped 50 ms in Rust), Breakout/Mancala (`coerceIn(0, 0.05f)`). Networked real-time games rate-limit their host broadcasts (`FishingGame.kt:503`, `SlotCarsGame.kt:526` every N ticks), not per frame. No game found that scales speed with refresh rate. No explicit `preferredRefreshRate`/`setFrameRate` anywhere (fine; default is adaptive).
Risks:
- **[LOW]** Air Hockey physics is variable-dt (not fixed-step) clamped to 50 ms: at a 50 ms hitch the puck can tunnel through a paddle/wall (`rust/gamesuite-sim/src/lib.rs:465-470`). Consider sub-stepping at 1/120 s inside `tick_impl`.
- **[LOW]** Billiards/Carrom/Golf/Bowling resolve a whole shot synchronously up front (`games/billiards/BilliardsPhysics.kt:111-149`, MAX_STEPS 10,800; Carrom 5,760; Golf 5,280) and replay recorded frames (`ui/BilliardsGolfUiHelpers.kt:18-25`). The shot sim runs on the caller thread (main) for human shots (`BilliardsGame.kt:224`); bot sampling is on `Dispatchers.Default`. Cost is probably a few tens of ms worst case on a phone; measure (section 9).
- **[LOW]** Per-frame `LaunchedEffect(myHand.map { it.instanceId }, ...)` style keys (e.g. `ui/UnoScreen.kt:~320`) allocate a list on every recomposition; with 3.2 fixed it stops mattering.

## 5. AI / CPU search and cancellation

Off the main thread (verified by reading call sites): Chess (`ui/ChessScreen.kt:279`), Checkers (`ui/CheckersScreen.kt:310,640`), Mancala (`ui/MancalaScreen.kt:205,501`), Tile bot (`ui/TileGameScreen.kt:269`), plus Backgammon, Billiards, Bowling, Carrom, Chinese Checkers, Connect Four, Curling, Darts, Gomoku, Hanafuda, Hare and Hounds, Hex, Holdem, Ludo, Matching, MiniGolf, Morris (`games/*/…Game.kt` `withContext(Dispatchers.Default)`). Spider's dealer/solver takes a cooperative `isCancelled` (`games/spider/SpiderSolver.kt:233-246`). Mancala, Tic-Tac-Toe, Flow Lines, Mahjong, Gomoku, Hex, Morris, Backgammon, Connect Four use node budgets or deadlines.

### 5.1 [HIGH] Chess HARD search: no time budget, not cancellable, allocation-heavy, leaf nodes do full move generation
`shared/src/commonMain/kotlin/com/gamesuite/games/chess/ChessGame.kt`:
- `search()` (`:710-740`) calls `legalMovesAndCheck()` BEFORE the `depthLeft == 0` test, so every leaf node generates all legal moves (to detect mate/stalemate) before evaluating. `generateLegalMoves` (`:564-571`) filters pseudo-moves by calling `applyMove` (`:587`, `board.toMutableList()` copy of a 64-slot `List<Piece?>`) for each, then the search calls `applyMove` again for each surviving move and `sortedByDescending` allocates a sorted list per node. No transposition table, no quiescence, no iterative deepening, no node/time cap (`HARD_SEARCH_DEPTH = 4`, `:1913`).
- Cancellation: `async(Dispatchers.Default){ searchBotMove }` in the screen is cancelled by structured concurrency, but the search body never checks `isActive`, so a cancelled/undone/left game keeps burning a core until depth 4 finishes (up to several seconds on a weak CPU) and the result is discarded. Same for Checkers (`minimax`, `shared/.../CheckersGame.kt:1161-1190`, depth 9) which also has no deadline.
- Measured on desktop only (section 1). On a 2-3x slower in-order phone core the worst case can plausibly exceed the 700 ms think delay, but the UI only waits (`chessPause`), so impact is a delayed bot reply and heat, not an ANR. Inferred.
Fix (in order of payoff): (1) check the clock + `ensureActive` every N nodes and return the best completed iteration (iterative deepening 1..4 with a ~1.5 s cap, as Gomoku/Hex/Morris already do); (2) test `depthLeft == 0` before generating moves, and only detect mate at depth 0 via a cheap in-check + any-legal-move early-out; (3) use make/unmake on a mutable `IntArray`/`ByteArray` board instead of copying `List<Piece?>`; (4) add MVV-LVA already exists, add a small TT. Checkers: add a deadline and move-ordering/killer; keep d9 but fall back to d8 past the cap.

### 5.2 [MED] Puzzle generation runs synchronously on the main thread for most puzzle games
`startMatch()` (which generates the puzzle) is called inline from composition effects for Sudoku (`ui/SudokuScreen.kt:262,321`), Nonogram (`ui/NonogramScreen.kt:247,313`), KenKen (`ui/KenKenScreen.kt:273,340`), Minesweeper (`:242,343`), Edge Match (`:219,290`), Flow Lines (`ui/FlowLinesScreen.kt:225,231,305` -> `FlowGenerator.generate` with a 2,000,000-node solver cap, `games/flowlines/FlowLinesEngine.kt:377`), Lights Out, Colour Flood, Sliding Puzzle. Only Kakuro (`ui/KakuroScreen.kt:229,269`) and Word Search (`ui/WordSearchScreen.kt:229-236`) hop to `Dispatchers.Default`. Each generator is bounded by a call budget (Sudoku `SOLVE_CALL_BUDGET = 200_000` per uniqueness check, repeated for each clue removal; KenKen 200k per attempt x up to `MAX_GENERATION_ATTEMPTS = 500`, `games/kenken/KenKenGame.kt:1021-1022`), but a budget is a step count, not a time bound: worst case is millions of steps on a low-end core, i.e. a frozen "loading" frame or ANR. Authors' own comments call these "real ANR risk" (SudokuGame ~95, NonogramGame ~831, KenKenGame ~942, EdgeMatchGame ~871).
Fix: reuse the Kakuro/WordSearch pattern (placeholder + `withContext(Default)` + apply on main) behind one shared `rememberAsyncPuzzle` helper; add a wall-clock deadline to each generator.

### 5.3 [HIGH] 359k-word dictionary parsed on the main thread
`games/wordgames/WordDictionary.kt:23-39` `ensureLoaded` reads `assets/words.txt` (4,024,720 bytes, ~359k lines) into a `LinkedHashSet<String>` then `groupBy { it.length }` (a second 359k-entry structure), synchronized, retained for the process lifetime. Hangman (`ui/HangmanScreen.kt:158-162`) and Word Tiles (`ui/TileGameScreen.kt:219-224`) call `loadDictionary` from a `LaunchedEffect` (main dispatcher) so the first entry to either game blocks the UI; Word Search correctly loads off-thread. Heap: roughly 359k Strings + set entries + list refs, order of tens of MB retained forever (inferred estimate), which matters on 2-3 GB devices. Parse time on a low-end device likely 0.5-3 s (inferred).
Fix: load in `withContext(Dispatchers.IO)` everywhere (Hangman and Tiles) behind a "Preparing" state; longer term, store the words as a sorted, length-bucketed blob and use binary search (or a trie/`FST`) instead of a `HashSet<String>`; `isValidWord` only needs membership. Hangman only needs ~60 words per tier, so do not load all 359k for it.

### 5.4 [LOW] Tile bot is brute-force permutations
`games/wordgames/tiles/TileBot.kt:100-215` enumerates rack permutations with string building per candidate (`HARD_ANCHOR_BUDGET = 40`, `HARD_OPENING_WORD_BUDGET = 20_000`). Already off-main and budgeted; allocation-heavy but bounded.

## 6. Memory and long-lived state

- **[HIGH, tied to 5.3]** `WordDictionary` object holds the full word set + length index process-wide (never released).
- **[MED]** `audio/ProceduralSfx.kt`: see 8.1; each call allocates a PCM `ShortArray` plus a stereo copy (`panToStereo`, line ~125) and an `AudioTrack` with a `stereo.size * 2` byte static buffer.
- **[LOW]** `MusicManager` (`audio/music/MusicManager.kt:56-91`) is a process-wide singleton with an application-lifetime `CoroutineScope(SupervisorJob()+Dispatchers.Main)` and a permanent `SettingsRepository.settings.collect`. Intentional and small; it can never be cancelled. `OnlineTransport` has its own `reconnectScope` (`transport/OnlineTransport.kt:95`) that is cancelled on disconnect (`:158`).
- **[LOW]** `PartySession` (`ui/party/PartyUiKit.kt:66-81`) is a process-wide `object` of Compose state (dealt hands, pick history, clock). Small, no leak risk beyond holding last session's data.
- **[LOW]** `AirHockeyRustGame.close()` is never called (KDoc admits it, `games/airhockey/AirHockeyRustGame.kt:150-156`); the native sim object lives until the UniFFI Cleaner runs. Negligible (one small object).
- **[LOW]** `CardSounds`/`AirHockeySounds` `SoundPool`s are singletons, loaded once (`games/cards/CardSounds.kt`); fine.
- No bitmap caches found: art is vector/Canvas drawn, so no large-bitmap OOM risk. Textures: none. `res/` is 1.4 MB; raw audio 44 KB.

## 7. Persistence cost

- **[MED]** Saved-game writes happen on the main thread in `onPause`: `GameSessionManager.pauseActiveGame()` (`core/GameSessionManager.kt:94-97`) -> `saveActiveProgress()` (`:136`) which calls `currentSnapshot()` (serialises the whole game state) and the file write+atomic move (`core/SavedGameStore.kt:96-112`). `canSaveActiveProgress()` (`:126-128`) calls `currentSnapshot()` too, so `ProgressSaver` callers serialise twice. Backgrounding with a large Solitaire/Uno/Mahjong state therefore does JSON encode + fsync-ish IO on the UI thread inside `onPause` (a delayed pause transition; ANR only on pathological storage). Fix: snapshot on main, write on an IO executor (the process is not killed instantly after onPause; use `onStop` or a short-lived `goAsync`-style executor), and avoid the double snapshot.
- **[LOW]** `StatsRepository` (`stats/StatsRepository.kt:55-105`) stores ALL games' stats and progress as two JSON blobs under single DataStore keys; every finished match decodes + re-encodes the whole map (70 games, bounded fields; no unbounded history lists found in `GameStats`). Fine today; would need splitting if per-match history is added. Any DataStore write re-emits and re-decodes for all collectors (`allStats`, `progress`).
- **[LOW]** `SavedGameStore.all()` re-parses every save on each Home/Games tab show; ok off-thread, see 2.2 for the one main-thread caller. `has()` parses the whole file just to answer a boolean (`:core/SavedGameStore.kt` `has`).
- 12 call sites use `getSharedPreferences` (favourites, tips, hub choices, player names, party roster, per-game best-scores): all `apply()`-style small files; some are read in composition (`ui/GameCards.kt` `FavoritesState`, Hub `store.load` in `remember`) = first-read disk IO on main, low cost after the first.

## 8. Audio, battery, networking

### 8.1 [HIGH] Procedural SFX: synth + AudioTrack construction + a new thread, per sound, no failure handling
`audio/ProceduralSfx.kt:74-82` `playProceduralSfx` -> `renderPcm(kind)` (re-synthesises the waveform with `exp/sin` per sample every call, e.g. 0.2-0.6 s at 44.1 kHz) -> `playPcmOneShot` (`:145-180`): builds a new `AudioTrack` (`MODE_STATIC`), writes the buffer, plays, and spawns a daemon `thread` that `Thread.sleep(duration + 150 ms)` then `stop()/release()`. 182 call sites (grep) across the games, fired on nearly every move. Called from composition effects, i.e. the main thread. There is no try/catch around `AudioTrack.Builder().build()`/`write`/`play` (unlike `Haptics.kt`, which wraps `vibrate` in `runCatching`), no cache of rendered PCM, and no cap on concurrent tracks.
Why: Breakout (a brick per hit), rapid taps, Solitaire drag-drops can create dozens of tracks/threads per second; track creation is tens of ms on low-end HALs and Android limits concurrent tracks per process, so this is both a jank source and a latent crash/silence source (an `IllegalStateException`/`UnsupportedOperationException` from the builder is uncaught). Inferred, not reproduced.
Fix: render each `(kind, pan-bucket)` once into a cached `ShortArray` (a lazy map), play through a `SoundPool` loaded from the cached WAV bytes (the app already ships one in `CardSounds`), or at minimum wrap in `runCatching`, move synthesis off main, and release tracks via `AudioTrack.setNotificationMarkerPosition`/`OnPlaybackPositionUpdateListener` instead of a thread per sound.

### 8.2 [MED] Ambient music engine: Kotlin synth shipped, Rust synth unused
`audio/AmbientMusicEngine.kt:463-478` runs `PadSynthState` (Kotlin, `Math.pow`/trig per sample, 50 ms chunks, `WRITE_BLOCKING` on a daemon thread) for every game screen that calls `rememberAmbientMusic` (many; each screen start/stops its own track via `DisposableEffect`, `:766-770`). The Rust port `RustPadSynth` is referenced only from unit tests (`app/src/test/.../RustPadSynth*Test.kt`; grep shows no production call), yet `libgamesuite_audio.so` (496 KB arm64, 377-487 KB per ABI, 3 ABIs) ships in the APK. Buffer is 6 chunks (~300 ms) so GC/scheduler hiccups are covered. The thread has no audio priority (`Process.setThreadPriority(THREAD_PRIORITY_AUDIO)` absent) and the track is not `PERFORMANCE_MODE_POWER_SAVING`.
Fix: either wire `RustPadSynth` into `runGeneratorLoop` (the ADR's stated purpose) or drop the lib from the APK; set `THREAD_PRIORITY_URGENT_AUDIO` and `setPerformanceMode(PERFORMANCE_MODE_POWER_SAVING)` for ambient pads; keep one engine across game-to-game navigation instead of stop/start per screen.

### 8.3 [LOW] Battery items that are fine
No `WakeLock`; `keepScreenOn` only while the Party clock/timer runs (`ui/party/PartyGameClockTool.kt:105-108`, `ui/PartyToolkitScreen.kt:186`). Haptics are `runCatching`-guarded one-shot effects (`haptics/Haptics.kt:91-110`; system-service lookup per call is cheap). Library music pauses when the app is backgrounded (`MainActivity.kt:278-286`). `ui/party/PartyGameClockTool.kt:87` loop is a 1 s-ish clock.

### 8.4 [LOW] Networking
`transport/OnlineTransport.kt`: OkHttp ping every 20 s (`:70`), bounded reconnect backoff 1/2/4/8/8 s (~23 s total, `:254-259`), single guarded retry job, scopes cancelled on disconnect. Full-state broadcasts per move (UNO sends a redacted state per recipient, `games/uno/UnoGame.kt:817-833`; JSON per seat) are bandwidth-light for turn games; real-time games throttle (section 4). No unbounded reconnect loop found. The relay (`server/`) was not audited here.

## 9. Size

- `app/build.gradle.kts:24` minify off (see 2.3). No ABI splits or App Bundle config: the APK ships `arm64-v8a`, `armeabi-v7a` and `x86_64` native libs (`libgamesuite_sim.so` 552/417/540 KB, `libgamesuite_audio.so` 496/377/487 KB) plus JNA's own per-ABI natives (JNA 5.14 `@aar` includes `libjnidispatch.so` for 5+ ABIs). Ship an AAB (Play does ABI splits) or `abiFilters "arm64-v8a","armeabi-v7a"` for release (drop x86_64, emulator-only).
- `assets/words.txt` 4.0 MB (stored compressed by aapt; ok) + `word_guess_answers.txt` 16 KB. `resources.arsc` 2.9 MB (≈1.3 MB of string XML incl. a full Spanish locale, fine).
- `rust/Cargo.toml` release profile: `lto = true`, `codegen-units = 1` only. Adding `opt-level = "s"` (or "z"), `panic = "abort"`, `strip = true` would shrink the .so files and is safe for these crates (no unwinding across FFI is relied on, but confirm UniFFI's panic-to-error mapping before `panic = "abort"`).
- 26 debug dex files = 64 MB uncompressed, so a release build without R8 will also be large. Expect a big win from 2.3.

## 10. Low-end device (minSdk 26), foldables, refresh rate, Reduced Motion

- minSdk 26 / targetSdk 34 (`app/build.gradle.kts`). API 33+ features degrade gracefully: AGSL shaders return `this` below 33 (`PremiumShaders.kt:274`), blur is a no-op below 31. So API 26-32 devices skip the shader costs but also run the older, unminified dex path (2.3).
- `android:configChanges` handles fold/rotation/dark-mode without recreation (`AndroidManifest.xml:82`), which is good for state and avoids re-initialising games on posture changes. `rememberFoldState` uses `WindowInfoTracker` (`foldable/FoldState.kt:51-58`), cheap.
- Expanded width uses a NavigationRail (`MainActivity.kt` ~420) and `AdaptiveTwoPane`; no obvious per-posture re-measure storms. The Fold 5 inner screen has 7.6 in at 2176x1812: Canvas games redraw more pixels, so 3.3-3.7 are amplified there; physical-device check needed (section 11). "Fold inner screen and half-open (book) posture passes" is itself listed as unbuilt (UNBUILT-ITEMS E).
- 60/120 Hz: no code sets a frame rate; game logic is time-based (section 4). Fine functionally; power at 120 Hz is amplified by 3.2/3.4 since those loops tick at display rate.
- Reduced Motion: honoured widely (`LocalReducedMotion` read in ~89 places; 75 UI files animate and only 6 do not mention it: `ui/effects/CameraShake.kt`, `PremiumShaders.kt`, `ReversiScreen.kt`, `ShogiBoardView.kt`, `games/taprace/TapRaceGame.kt`, `games/wordgames/wordsearch/WordSearchGame.kt`; callers of the effects gate them). Gaps that matter for performance as well as accessibility: Air Hockey puck sweep ignores it (`ui/AirHockeyScreen.kt:639`), the shared `rememberParticleBurst` loop does not stop under Reduced Motion (3.4), `Petals`/confetti loops (`ui/ShogiScreen.kt:822`, `ui/HanafudaScreen.kt:978`, `ui/SpiderScreen.kt:908`) are only on win screens and gated by callers (not verified for all).
- Kid-oriented real-time/party games: multi-touch is handled with raw `awaitPointerEvent` loops with `PointerEventPass.Initial/Main` and `pointerInteropFilter` (`ui/PartyTapTugArt.kt:216-289`, `ui/ReactionWhackStage.kt:58-68`, `ui/PaddleDuelScreen.kt:313-317`), which is the efficient approach; no per-pointer allocation problem spotted. The cost there is the always-on frame loops (3.5) and art allocated per frame (3.7).

## 11. Measurements to take on real devices (none exist today)

Devices: (a) a low-end API 26-29 phone, 2-3 GB RAM, Cortex-A53 class (the real stress case); (b) a mid-range 2022 phone; (c) the Fold 5 (cover, inner, half-open) and Tab S9 FE already used by the team. Always use a **release-like** build (R8 on, `debuggable false`, `profileable` in the manifest) since debug Compose is 2-5x slower and the debug APK is what exists.

| # | Measure | How | What to look for |
|---|---|---|---|
| 1 | Cold/warm start TTID/TTFD | Macrobenchmark `StartupTimingMetric`, `CompilationMode.None()` vs `Partial()` vs baseline profile; `adb shell am start -W`; `reportFullyDrawn` after settings loaded | Before/after 2.1 and 2.3; settings-default flash visible in a screen recording |
| 2 | Frame timing per game | Macrobenchmark `FrameTimingMetric` while a scripted run plays: Home idle 30 s, Games tab scroll, Shogi/Hex/Ludo idle 30 s, Shooting Gallery, Air Hockey (vs CPU, 60 s), Breakout, Tower Defence level 3, Chess animations, Uno hand with 25 cards; also `adb shell dumpsys gfxinfo com.gamesuite framestats` (the existing `NavRotationTestSupport.captureJankAround` already does this; persist the output) | p50/p90/p99 frame duration + overrun counts at 60 and 120 Hz; compare idle board games vs 3.2 fixes |
| 3 | Recomposition counts | Layout Inspector recomposition counts, or `Modifier.recomposeHighlighter` in a debug build; Compose compiler metrics report | Idle Shogi/Hex/Ludo/ShootingGallery should be ~0 per second |
| 4 | Idle CPU / wakeups / battery | Perfetto trace (Frames, `sched`, `power rails`), `adb shell dumpsys batterystats --reset` then 10 min on Home, 10 min in Blackjack idle, 10 min in Air Hockey, 10 min with ambient music; `adb shell top -H -p <pid>` | CPU % and mAh/min per scenario; confirm 3.1/3.4 cost and that the AudioTrack thread is light |
| 5 | AI search time (the adult-heavy games) | Instrumented test or `adb shell am instrument` timing of `searchBotMove` for Chess HARD from 20 fixed positions (opening, midgame with checks, endgame) on each device; same for Checkers d9 with multi-jumps; Morris/Gomoku/Hex at deadline; Backgammon; Billiards/Carrom bot sampling | p50/p95/max ms vs the 700 ms think delay; confirm whether cancellation leaves a core spinning (watch `top -H`) |
| 6 | Puzzle generation on main thread | Add `Trace.beginSection` or `measureTimeMillis` around `startMatch()` for Sudoku HARD, Nonogram 15x15, KenKen 6x6+, Flow Lines largest tier, Edge Match, Minesweeper large; `StrictMode.ThreadPolicy` detectAll with `penaltyLog`; ANR watch via `adb logcat | grep -i "Skipped .* frames"` | worst-case ms; anything >100 ms must move off main |
| 7 | Dictionary load + heap | `Debug.getNativeHeapSize/Runtime.totalMemory` before/after first Hangman/Word Tiles open; `dumpsys meminfo com.gamesuite`; Android Studio heap dump | load ms on main thread; retained MB of `WordDictionary` |
| 8 | Memory over a long session | Navigate through 30 games and back; `dumpsys meminfo` and `adb shell dumpsys activity`; leak check with LeakCanary in a debug variant; PSS after 1 h | Growth beyond baseline; confirms singletons/scopes in section 6 |
| 9 | Audio latency and underruns | `adb shell dumpsys media.audio_flinger` (underrun counters), a rapid-tap Breakout run, measure time inside `playPcmOneShot`; `AudioTrack.getUnderrunCount()` for the ambient track while a heavy game (Air Hockey) runs | Track creation ms, underruns, any `IllegalStateException`s in logcat when 30+ SFX fire in a second |
| 10 | Persistence cost | `Trace` around `pauseActiveGame`; backgrounding Uno/Solitaire/Mahjong with large states; `StrictMode` disk-write on main | ms in onPause; snapshot size in bytes (log `SavedGame.snapshot.length`) |
| 11 | APK/install size and class load | `bundletool` size report, `apkanalyzer`, release build with and without R8/ABI filter; install time `adb install -t` | MB download/installed; confirms 2.3/9 |
| 12 | Foldable/tablet | Fold 5 inner at 120 Hz: Home, Games, Chess, Air Hockey, Uno; half-open posture; GPU overdraw (`debug.hwui.overdraw show`) and `gfxinfo` GPU percentiles | GPU time scales with pixels; shader/offscreen layer cost (3.1, 3.3) |
| 13 | Network under churn | Airplane-mode toggle during an Online Chess and an Online Air Hockey/Fishing match; capture relay messages/s with the server's log | Reconnect time, message rate, battery of radio wakeups |
| 14 | Low-end thermal throttle | 15 min continuous Air Hockey/Breakout on the low-end phone, record frame times in 5 min windows | Whether frame time degrades (the combined 3.3/3.4/3.6 cost) |

## 12. Prioritised fix list

1. 8.1 Procedural SFX (cache, background synthesis, error handling, no thread per sound).
2. 5.3 Dictionary off main + lighter structure; 5.2 shared async puzzle generation.
3. 3.2 + 3.4 + 3.3: the three mechanical "deferred read / idle loop / RenderEffect" fixes remove most idle battery cost across ~40 screens.
4. 5.1 Chess/Checkers search budgets, ensureActive, leaf move-gen fix.
5. 2.3/9: enable R8 + shrinkResources + baseline profile + ABI filter; wire or drop the Rust audio lib.
6. 2.1 settings-before-first-frame; 7 main-thread save in onPause; 3.9 slider commits; 3.10 `getIdentifier` cache.
7. Add a Macrobenchmark module and persist `gfxinfo` baselines so the next audit has numbers.
