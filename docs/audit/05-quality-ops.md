# Audit 05: quality, testing, data durability, operations

Date 2026-11-07. Read-only audit of Z:\GameSuite-wt-game-kit (branch session/wave-3). Nothing was built or run; test counts come from the existing XML under app/build/test-results/testDebugUnitTest (written 2026-11-07T11:38Z). Every item is tagged V (verified by reading the code/file named) or G (guessed / inferred, needs a check).

## 0. Snapshot

| Fact | Value | Basis |
| --- | --- | --- |
| Kotlin main / test / androidTest files | 498 / 361 / 10 (app); 178,808 main lines in app | V (find/wc) |
| Last unit run | 5,201 tests, 0 failures, 0 errors, 0 skipped across 331 result files | V (XML sums); source has 5,204 `@Test` so about 3 parameterised/uncounted | 
| `@Ignore` anywhere | 0 | V |
| Compose UI tests in unit tests (Robolectric/createComposeRule) | 0 | V (grep only hits androidTest) |
| androidTest | 7 rotation tests + LAN bot match + audio device test | V |
| Screens (`ui/*Screen.kt`) | 88 | V |
| Rust tests | 44 in gamesuite-audio, 3 in gamesuite-sim | V |
| Relay tests | 7 node scripts (smoke, identity, seat-ranges, room-meta, ratelimit, quickchat, queue) | V (package.json) |
| Git state | 245 uncommitted paths (168 untracked, 77 modified); HEAD fb05659 is 1 commit ahead of origin/main | V (git status) |

## 1. Test coverage map

Package-level counts (main files/lines vs test files/lines), from `app/src/{main,test}/java/com/gamesuite/games/<g>`. Games whose engine lives in `shared/` (checkers, chess, mancala, tictactoe, airhockey) show tiny `main` here; their tests are in app plus shared commonTest/jvmTest.

Thin or odd relative to size (V):
- **cards** (7 files, 1,014 lines): zero tests. Shared deck/card-art infra used by ~15 card games. Medium.
- **airhockey**: no app-side test; only `shared/.../AirHockeyGameTest.kt` plus Rust sim tests (3 tests in `rust/gamesuite-sim/src/lib.rs`). Physics lives in Rust and is covered thinly. Medium.
- Test lines under 55% of main lines: curling 527/1108, minigolf 615/1125, toyboxing 984/1482 (ok), shootinggallery 530/914, lighttrail 532/866, sumopush 538/876, toybaseball 549/1076, tugofwar 517/771, matching 705/1287, taprace 541/800, war 528/702, sevens 641/851. Low.
- Cross-cutting packages with no tests at all (V): `foldable` (4 files), `haptics`, `settings` (10 files incl. AppSettings/SettingsRepository), `theme`, `uikit`. `stats` has one file (`MatchRecordingTest`) and `StatsRepository` itself (the DataStore layer) is untested. High for settings/stats persistence (see section 3).
- `ui/`: 143 main files, 14 test files (pure-logic tests like `UnoTableLayoutTest`, `SolitaireLayoutTest`). No test renders any Composable on the JVM.

UI / instrumented coverage (V):
- `androidTest` rotates these screens only: AirHockey, SlidingPuzzle, Chess, Mancala, Dominoes, Solitaire, WordTiles, TicTacToe, Checkers, UNO, WordGames (via `NavRotationTestSupport`). That is about 12 of 88 screens. The other ~75 screens (all of wave 1-4b: Gomoku, Hex, Shogi, Ludo, Holdem, Blackjack, Spider, the 10 toy sports, the party pack, Hub, Settings, Stats, Club, music) are never rendered by any automated test. High.
- None of these run in CI (see section 8), so even the existing ones only run when a human has an emulator. The handoff notes say emulator sweeps are manual (`Z:\Dev\gs-sweep\tools`, outside the repo; V in PAUSED doc and QUALITY-BAR.md).

Property / fuzz / determinism (V by grep + file names, not read in depth):
- Files mentioning fuzz/property/random seeds: 99 of the test files; determinism-named tests exist for colorflood, darts, dots, fishing, flowlines, mahjong, morris, nonogram, pigdice, reversi, shootinggallery, slotcars, PadSynth signal quality. Reasonable for puzzle/board games.
- QUALITY-BAR.md gate 1 requires fuzz/property tests and an adversarial pass per game, but there is no property-testing library (only JUnit 4.13.2 + mockwebserver in `app/build.gradle.kts`), so fuzz means hand-rolled seed loops. Low/Med: no shrinking, no mutation testing. G for how many games actually have a random-legal-play-never-throws test.
- CPU determinism across devices: toysoccer/toytennis/slotcars/carrom etc. were switched to StrictMath (verified fixed, see section 2 item 17). No cross-ABI test (arm64 vs x86_64 host) compares sim output; the Rust sim is compiled with LTO and floats, and the JVM tests only run the host build. Med (G: host vs ARM float divergence is plausible for snapshots/online play where host sim and guest replay must agree).

## 2. Status of the 30 findings in docs/roadmap/CODE-REVIEW-WAVES-1-4.md

Checked against current code on session/wave-3 (V unless noted). The doc itself still reads as all-open; UNBUILT-ITEMS.md says "most fixed" without listing which.

Fixed (V):
- 1 Online callbacks on OkHttp thread: `OnlineTransport.kt:65` now has a delivery executor, default `MAIN_THREAD` (`:467-471`).
- 2 Two monitors: every listed game now `private val lock: Any = this` (fishing:115, matching:160, slotcars:105, speed:178, toysoccer:175, toytennis:114).
- 3 Shogi lastTo: `lastMoveValid` at `ShogiGame.kt:477-482`, used for snapshot (`:461`) and net state (`:657`).
- 4 Ludo eventTo: `eventRangeValid` at `LudoGame.kt:295` and `:646`.
- 5 Spider: `ensureActive()` at `SpiderGame.kt:206`; UNBUILT-ITEMS still notes the 12 s wall-clock cap is device-speed dependent (open, Low/Med).
- 6 Matching guest drop: guest leaving converts to bot (`MatchingGame.kt:683-687`). Host leaving sets `opponentLeft` (acceptable).
- 7 Session epoch: `core/SyncEpoch.kt`. Idle-turn timer still not built (UNBUILT-ITEMS A). Open, Med.
- 8 Morris fallback: `quickBest` depth-1 fallback (`MorrisEngine.kt:234-241`).
- 12 Payload cap: OnlineTransport caps at 96 KiB base64 (`:438,:465`), relay caps 64 KiB (`server/index.js:106`). I did not confirm Nearby/LAN transports cap (G).
- 14 Blackjack skip results: now host/seat check but `NextRoundRequest` still honoured from any seated guest (`BlackjackGame.kt:899-900`). Partly open, Low.
- 17 StrictMath: SoccerWorld/TennisSim/SlotCarsTrack now use `StrictMath.hypot`. The leftover `import kotlin.math.hypot/atan2` lines (e.g. `SlotCarsTrack.kt:5,8`) are unused imports; check atan2/sin/cos in SlotCarsTrack are Strict (G).
- 18 Blackjack leave score: `netOf` adds on-the-felt chips (`BlackjackGame.kt:199-205`).
- 20 Gomoku unmarked five: now asserted (`GomokuGame.kt:77-80`).
- 22 Json unknown keys: snapshots use `ignoreUnknownKeys` (hanafuda:77, blackjack:40, war:61); net messages go via shared `NetJson`.
- 29 Club profile: setters are now separate single-field `edit` calls (`ClubProfileStore.kt:31-38`), so no half-saved triple.
- 10 Per-frame recomposition: Tank and Darts now read the animated value as a `State` (`TankBattleScreen.kt:188`, `DartsScreen.kt:193`); Gomoku `:457` still has an `animateFloat` (G whether it is deferred to draw).

Still open (V):
- 11 Tennis rewind not rate-limited: no cooldown found around `SwingRequest` handling (`ToyTennisGame.kt:564`). Low.
- 16 Intent amplification (fishing Aim, slotcars Throttle etc.). Low. (G, not re-read.)
- 21 Morris repetition rule: only `QUIET_LIMIT = 100` plies (`MorrisEngine.kt:135,270`). Low.
- 23 Listeners never unregistered: `MultiplayerTransport` still has no unregister (grep found none). Low/Med.
- 24 Bot-seat guards at engine level for Shogi/Morris/Hounds. Low (G).
- 25 Dead `lastAction` English strings in Morris and Hounds: `MorrisGame.kt:22,61,216`. Low, and they travel over the wire.
- 26 Dead enum values: `BjReject.NOT_BROKE` (`BlackjackState.kt:46`) and `BjEvent.DEALER_TURN` (`:35,41`) still exist. Low.
- 27 Duplicated helpers: `gauss()` has 8 copies (billiards, minigolf, taprace, toysoccer, toytennis, darts, reactionduel, whackamole). 31 `*Palette` classes across ui (e.g. `PartyPalette` in `PartyUiKit.kt:83` AND `PartyUiPalette` in `PartyHostUi.kt:47`: the "two palettes" from the PAUSED doc, still both present). `Json {` appears 76 times; `sendToHost` helper copied in 37 files. Med for maintainability.
- 28 Card art allocations in draw lambdas: not re-checked (G).
- 30 Forgeable gallery/sixball logs: by design. Low.

Net: 18 of 30 verified fixed or mitigated, about 10 still open and all Low or Low/Med, 2 unverified. None are release blockers. Update the review doc with a status column so it stops reading as open.

## 3. Persistence, migration, corruption

Verified strengths: `SavedGameStore` writes temp + atomic move (`core/SavedGameStore.kt:99-118`), deletes unreadable/expired/mismatched files instead of throwing, `ignoreUnknownKeys`, and has `SavedGameStoreTest`. `savedGameStore` lives in `filesDir/saved_games` (`MainActivity.kt:276`).

Findings:
1. **Stats history can be silently wiped by one bad decode. High (V).** `StatsRepository.kt:111,115` return `emptyMap()`/`ProgressState()` on any decode failure, and `recordMatch` (`:64-80`) then re-encodes from that empty value, overwriting the whole stored blob on the next match. There is no schema version, no backup copy and no DataStore `corruptionHandler`. A future field-type change, a truncated write, or a restored-from-newer-build blob loses every game's record and all achievements. Fix: add `{"v":N}`, on decode failure keep the raw string under a `*_corrupt_<ts>` key and refuse to overwrite; add `ReplaceFileCorruptionHandler`. Effort S-M (a day), test via `StatsRepository` which has no tests today.
2. **No DataStore corruption handling across all 30 stores. Med (V).** `grep preferencesDataStore(name` gives 30 definitions and no `corruptionHandler` anywhere; none of the `.data` flows apply `.catch`. A corrupt `.preferences_pb` makes the first collecting flow throw `CorruptionException` in the UI scope (crash loop at start, since Settings/Stats are read at launch). Fix: one shared helper `gameDataStore(name)` with `ReplaceFileCorruptionHandler { emptyPreferences() }`. Effort S. 30 sites to change, mechanical.
3. **allowBackup=true with no `dataExtractionRules`/`fullBackupContent`. Med (V, `AndroidManifest.xml:69`).** On Android 12+ lint flags this; everything (30 DataStores, `saved_games`, stats) is auto-backed-up and restored onto fresh installs, including onto newer/older versions. The privacy policy does disclose allowBackup. Decision needed: keep stats, exclude `saved_games` and anything device-specific (relay address, music folder URIs, which are not valid after restore). Effort S.
4. **No downgrade / future-version guard on any store (G).** `SavedGame` and game snapshots have no version field (grep for SNAPSHOT_VERSION/schemaVersion hit one file). Each game's `restore()` validates its own snapshot and returns null, so a bad snapshot becomes "no save", which is safe, but a new build that changes a snapshot layout silently discards in-progress games. Med/Low: acceptable now, add a `v` to `SavedGame` before the first public release.
5. **No fsync before rename in SavedGameStore (`:103-110`). Low.** Atomic move without `FileOutputStream.fd.sync()` can still leave a zero-length target after power loss on some filesystems; read path then deletes it, so impact is losing one save.
6. **No export/import or reset-all for user data except `resetAll()` (`StatsRepository.kt:105`). Low.** Brain Trainer shipped export; GameSuite has none. Nice-to-have, S-M.
7. 29 personal-best stores for the newer games (Snake, Yahtzee, 2048, Flow Lines, Mahjong, Breakout...) each have their own DataStore (PAUSED doc risk 4). Fragmented but works. Low.

## 4. Crash handling, reporting, logging

- **No crash handling at all. High for any public release (V).** No `UncaughtExceptionHandler`, no Crashlytics/Sentry/ACRA, no `Log.e/w/d` calls in `app/src/main` (grep counts 0). The privacy policy promises "no analytics or crash-reporting SDK". Players of a game app that hits a rare engine bug see a silent process death and the developer sees nothing except Play vitals (once on Play). Options that stay within the policy: a local-only handler that writes the stack trace to `filesDir/crashes/` and offers "Share crash report" on next launch (Effort S), or Play Console vitals only (free, zero code, needs a Play listing).
- The app contains 93 `!!` operators in main code (V grep), concentrated in net/state paths (e.g. `MatchingGame.toNetState`: `state.value!!`). Not wrong by itself but each is a crash on a corrupt/hostile state. Low/Med.
- Logging: server logs via its own `log()`; Android side logs nothing, so field diagnosis of online issues (reconnect, relay errors) is impossible. Low/Med: add a small ring-buffer logger surfaced in Settings > About.

## 5. Release readiness

| Item | State | Rating | Evidence |
| --- | --- | --- | --- |
| Signing config | None. No `signingConfigs`, no keystore reference in any gradle file | High (V) | grep over app/build.gradle.kts, build.gradle.kts, gradle.properties returned nothing |
| Minify / R8 | `isMinifyEnabled = false`, `proguard-rules.pro` present but never exercised | High (V) | `app/build.gradle.kts:21` |
| ProGuard keep rules for JNA / UniFFI | Absent. UniFFI Kotlin bindings use JNA with reflection (Structure subclasses, Library interfaces, Callback). Rules cover kotlinx.serialization, OkHttp and Nearby only | High when R8 is turned on (G: need a release build to prove; the missing `-keep class com.sun.jna.** { *; }` and `-keep class uniffi.** { *; }` is the classic failure) | `proguard-rules.pro` grep for jna/uniffi returned nothing |
| versionCode / versionName | 7 / "2.0.0" | Info (V) | `app/build.gradle.kts:15-16` |
| applicationId | `com.gamesuite` (generic; a Play Store collision is possible) | Med (G) | `:12` |
| targetSdk / compileSdk | 34 / 34 | High (V): Play requires new apps and updates to target API 35 (since Aug 2025) and 36 from Aug 2026; 34 will be rejected | `:9,13` |
| Release NDK path | Falls back to `C:\Users\Obliv\android-sdk\ndk\...` | Med (V) | `:150`; only CI/other machines work through env var |
| `.aab` pipeline | None | High (V) | no bundle task, no workflow |
| Play listing assets, screenshots, feature graphic, store description, content rating questionnaire | No files in repo (no `fastlane/`, no `metadata/`, no store copy docs) | High (V) | find at repo root and docs/ |
| Privacy policy | `PRIVACY_POLICY.md` in repo root; reasonably accurate. Gaps: does not mention the Club profile (display name + avatar + favourite), the music library folder permission, quick-chat over the relay, or the relay's per-IP rate limiting (which implies IP processing). No hosted URL (Play needs a public URL) | Med (V) | file read |
| Data-safety form inputs | Not prepared (relay sees display names, room codes, IPs) | Med (G) | |
| App name / icons | `android:label="GameSuite"` hard-coded (not a string resource) and `app_name` missing from ES list; adaptive icon exists (`mipmap-anydpi-v26`) | Low (V) | manifest `:71`; ES diff includes `app_name` |
| Content rating | Not evidenced. Contains card/casino-style games (Holdem, Blackjack with chips) so the IARC questionnaire needs the simulated gambling answer | Med (G) | |
| Debug-only cleartext | Debug override exists and release forbids cleartext | OK (V) | network_security_config.xml |
| 16 KB page size | NDK 28 builds .so with 16 KB alignment by default for r28+, but JNA 5.14's bundled libjnidispatch is not 16 KB aligned; Play requires it for API 35+ targets (since Nov 2025) | Med (G) | JNA 5.14.0 in `app/build.gradle.kts` |

Release effort estimate: signing + AAB + R8 pass + targetSdk 35/36 bump + crash handler + store assets is about 3-5 days of focused work, mostly spent on R8-induced breakage and the targetSdk behaviour changes (edge-to-edge enforcement on 35, foreground-service and Bluetooth rules on 34/35).

## 6. Dependencies

Verified versions and risk (V from build files; vulnerability status is G because no scanner was run):
- Android Gradle Plugin 8.6.0 (`build.gradle.kts:2`), Kotlin 2.0.21, Compose Multiplatform 1.7.3 pinned with a comment (do not bump). Behind current (AGP 8.9+/9, Kotlin 2.2). targetSdk 35/36 requires AGP 8.6+ (ok) but compileSdk 35/36 needs AGP 8.6-8.9 and a newer build-tools. Med.
- Compose BOM 2024.09.00, material3-adaptive 1.0.0, navigation-compose 2.8.5, lifecycle 2.8.7, datastore 1.1.7, activity-compose 1.9.3. About 14 months old. Low.
- OkHttp 4.12.0 (2023), no known open CVE to my knowledge; 5.x exists. Low.
- JNA 5.14.0 (2024): no known critical CVE; see 16 KB point. Low/Med.
- play-services-nearby 18.7.0. Low.
- Rust: `rust/Cargo.lock` uniffi 0.32.1, rand 0.8.8. `cargo audit`/`cargo deny` not run. CI uses `dtolnay/rust-toolchain@stable` (floating) while building with `--locked`. Low.
- Server: `ws ^8.18.0`, lock resolves 8.21.3, only one runtime dependency, Node 20 in Docker and CI (Node 20 reached EOL April 2026; move to 22). Low/Med. No `npm audit` step in CI.
- No Dependabot/Renovate config, no `libs.versions.toml`: versions are string literals duplicated between `app/build.gradle.kts` and `shared/build.gradle.kts` (serialization 1.7.3 appears in both, comments warn to keep in sync). Low/Med.
- GitHub Actions pins are floating major tags (checkout@v7/v4, setup-java@v6) and relay workflow uses checkout@v4 and setup-node@v4 while android.yml uses newer majors; inconsistent. Low (G whether those majors exist; they were pushed and CI reportedly green earlier).

## 7. CI

What runs (V, `.github/workflows/android.yml`, `relay-server.yml`):
- Rust: `cargo test --workspace --locked`.
- Android: cargo-ndk build for 3 ABIs, `:app:testDebugUnitTest :shared:allTests :app:assembleDebug`, uploads the debug APK.
- Relay: `npm ci && npm test` (all 7 scripts) on server/** and the HubSpecs files.

What does not run (V, header comment of android.yml says it is deliberate):
- androidTest/instrumented tests (no emulator job). High: the only UI-level safety net is manual.
- Android lint (not even `lintDebug`), detekt, ktlint, cargo fmt, clippy. Med. Lint would catch the missing backup rules, targetSdk and hard-coded strings.
- Release/R8 build, so the proguard file is untested. High as soon as release is planned.
- Docker build of the relay (the Dockerfile has never been built in CI; UNBUILT-ITEMS F says "Docker image ... never run"). Med.
- `esp32-tictactoe/native_test` host-compiled playtests for the ESP32 game ports (checkers, chess, dominoes, mancala, solitaire, spider, uno). Low/Med.
- `npm audit`, `cargo audit`, dependency review.
- Workflow path filters mean docs-only or `esp32-tictactoe/` changes never trigger anything, which is fine, but `docs/` changes to `GameHubSpecs` generated seat ranges are covered only by relay workflow. OK.
- CI has not run on the current tree: HEAD fb05659 plus 245 uncommitted paths, "CI not run on any of it" (`UNBUILT-ITEMS.md` F, still true because nothing is pushed). Highest operational risk, see top-15.
- Android CI timeouts 45 min with a cold 3-ABI Rust build; fine.

## 8. Docs staleness (V by comparing files)

- `CLAUDE.md` commands and gotchas match `android.yml`. It does not mention the Wave-5 relay specifics, the 7 relay test scripts, or `docs/audit/`. Low.
- `docs/handoff/PAUSED-2026-10-18.md` says tests had 2,660 unit tests and HEAD baa2049; reality is 5,201 tests and HEAD fb05659, and its "Then" steps (merge origin/main, first build) are done. Stale, should be archived or updated. Med.
- `UNBUILT-ITEMS.md` (dated 2026-11-07, current) says "Everything since fb05659 is uncommitted" while HEAD already is fb05659 with 245 further uncommitted paths: consistent but means the wave-5 server/lobby work, review fixes and party pack exist only on disk.
- `CODE-REVIEW-WAVES-1-4.md` has no status column (see section 2).
- `docs/PARALLEL_SESSIONS.md`, `ENGINE_DECISION.md`, `RUST_AUDIO_CORE_PLAN.md` not re-read (G for staleness).
- `server/README.md` says run `npm install`, documents Render/Fly/VPS; `server/CHANGELOG-wave5.md` is a loose changelog inside the server folder. Low.
- `esp32-tictactoe/README.md` still says the firmware toolchain "isn't available" in the author's environment (line 36) and asks the user to flash and report: unverified firmware claims. Low.

## 9. Repo hygiene

- 245 uncommitted paths in the worktree, including 77 modified and 168 untracked (V). No single commit boundary for wave-5. High.
- Largest files (V): `ui/UnoScreen.kt` 3,067 lines, `TileGameScreen.kt` 2,123, `ChessScreen.kt` 1,935, `CheckersScreen.kt` 1,755, `SolitaireScreen.kt` 1,694, `DominoesScreen.kt` 1,684, `PartyToolkitScreen.kt` 1,637. 24 screens exceed 1,400 lines. Low/Med (slow compile, hard review).
- Duplication (V): 31 Palette classes, two party palettes, 8 `gauss()` copies, 76 `Json {` configs, 37 `sendToHost` copies, copy-pasted spectator/broadcast code in holdem/president/sevens. Med. Fix: `ui/GamePalette` base + shared `Gauss`, `NetCodec` in core. Effort M-L (touches many games; do via mechanical refactor with the existing test net).
- 98 per-feature string files each in `values` and `values-es` (e.g. `party1_strings.xml`, `loc_fix_strings.xml`, `polish_strings.xml`): names carry wave/session history rather than game names, and `hub_same_screen` is defined in just one party file (UNBUILT-ITEMS C). Low/Med.
- Localisation gap (V by script): 4,761 EN keys vs 4,732 ES keys; the 29 missing include `app_name` and 28 `game_*` titles (breakout, color_flood, connect_four, dots, edge_match, kakuro, kenken, lights_out...). The documented check `HubLocalizationTest` evidently tolerates it. Low/Med; UNBUILT-ITEMS D also lists game screens as partial Spanish.
- Build artefacts on disk: `rust/target` 1.1 GB, `app/build` 395 MB, jniLibs for 3 ABIs, all gitignored (V `.gitignore:47-48`). OK.
- `local.properties` and Windows NDK path leak the maintainer's username into build.gradle.kts (`:150`). Low.
- `docs/audit/` was empty before this report.

## 10. Server operations (`server/`)

Verified good: `/health` and `/healthz` return JSON with uptime, room and queue counts (`index.js:431-440`); Docker HEALTHCHECK, non-root `USER node`, `npm ci --omit=dev`, `.dockerignore` strips tests; SIGTERM/SIGINT graceful close (`:995-997`); ws heartbeat 30 s (`:113`); caps: 500 rooms, 8 per room, 64 KiB frames, per-IP room cap (`config.js:42`), rate limiting module, seat tokens, reconnect grace, idle sweep; no secrets or env credentials required.

Findings:
1. **Never deployed. High (V).** UNBUILT-ITEMS A: always-on hosting (O1) not chosen; Docker image never built; no production URL baked into the app (online address is a user setting). Without a host the Online mode is unusable for normal players, and Play listing would advertise it. Effort: pick Fly.io/Render ($0-5/mo), 0.5 day.
2. **No metrics/alerting. Med (V).** Only `/health`; no Prometheus counters, no error-rate or connection-count export, no log shipping. `log()` goes to stdout. An uptime pinger is enough to start. Effort S.
3. **Single process, in-memory state, no horizontal scale. Med (V by design).** Rooms and matchmaking live in memory (Dockerfile comment); a restart drops every live match. Limit is about 500 rooms and the per-IP limits assume `TRUST_PROXY=1` behind a proxy; misconfiguring that makes all clients share one IP bucket (G). Acceptable for friends-scale; document the ceiling.
4. **TLS not handled in-process (V).** README relies on the platform or Caddy; the Android release config forbids cleartext, so a relay without wss:// is unreachable from release builds. Fine, but document in the runbook.
5. **Test debt (V).** `npm test` is 7 scripts with a hand-rolled `test-lib.js` (no coverage tool); no load or soak test (the 500-room cap and the matchmaker 2,000-ticket cap are untested at scale). Low/Med. Node 20 base image EOL (see section 6).
6. No origin check on the WebSocket upgrade and no auth beyond room code and seat token (G, did not read the upgrade handler). Low for a hobby relay.

## 11. ESP32 cabinet (dangling items)

- Firmware is a separate Arduino tree (`esp32-tictactoe/`), not built by Gradle or CI. Host-compiled `native_test/*_playtest.cpp` cover game logic for 7 games but are not in CI (V). Med/Low.
- The Android app has no bridge to the cabinet; the only references in Kotlin are comments (`ChessScreen.kt:143-161`, `AppSettings.kt:15` MIDNIGHT_ARCADE theme port) and `shared/` Checkers/Chess logic shared with desktop (V). So "cabinet integration" is parity-of-rules only.
- Memory notes and UNBUILT-ITEMS B say the hardware items (audio, addressable LED, DMA, flash headroom work) were never built (V from UNBUILT-ITEMS).
- README line 36 and "flash it and tell me what happens" mean the firmware has not been validated by the author on hardware in this environment (G for current state).
- `TicTacToeESP32/` still named for the first game while holding Checkers/Chess display code: naming drift. Low.

## 12. Ranked top 15

1. **Nothing is committed or pushed since fb05659 (245 uncommitted paths); CI has never run on the current tree.** High (V). Fix: path-scoped commits in logical groups, push the branch, open a PR, let CI go green. Effort 0.5 day; risk of losing weeks of work to a disk issue is the single biggest exposure.
2. **No signing config, no AAB, no release build ever produced; minify off.** High (V). Create keystore (stored outside repo), `signingConfigs` driven by env/properties, bundleRelease, CI release job. Effort 1 day.
3. **targetSdk 34 will be rejected by Play (needs 35, then 36).** High (V). Bump, fix edge-to-edge and Bluetooth/FGS behaviour changes, re-run device passes. Effort 1-2 days.
4. **R8 rules missing for JNA/UniFFI generated bindings; release build never exercised.** High (G until run). Add `-keep class com.sun.jna.** { *; }`, `-keep class uniffi.** { *; }`, JNA `-dontwarn`; enable minify on a staging type and run the instrumented rotation sweep on it. Effort 1 day.
5. **Stats and achievements can be wiped by a single failed decode; no corruption handler on any of 30 DataStores.** High (V). Versioned envelope plus quarantine plus `ReplaceFileCorruptionHandler` helper. Effort 1-2 days; add a `StatsRepository` test (none exists).
6. **No crash reporting or logging of any kind.** High for launch (V). Local crash file + share-on-next-launch, or Play vitals. Effort 0.5-1 day.
7. **75 of 88 screens are never rendered by any automated test, and instrumented tests are not in CI.** High (V). Add a Compose smoke test that opens each Hub spec's screen (Robolectric or a Gradle managed device), run `connectedCheck` nightly. Effort 2-3 days for a first screenshot-free smoke pass.
8. **Relay never deployed; Docker image never built in CI; no monitoring.** High (V). Add a `docker build` step to relay-server.yml, deploy to a free host, add an uptime check. Effort 0.5-1 day.
9. **Play-store package missing: listing copy, screenshots, graphic, content rating, hosted privacy-policy URL, data-safety answers, privacy policy gaps (Club profile, music permission, relay IPs).** Med-High (V). Effort 1-2 days of content work.
10. **allowBackup=true with no extraction rules; saved games, relay address and music URIs restored onto other devices/versions.** Med (V). Add `dataExtractionRules` and `fullBackupContent` exclusions. Effort 0.25 day.
11. **No Android lint, detekt, cargo clippy/fmt in CI; deliberately off.** Med (V). Enable `lintDebug` with a baseline first, then ratchet. Effort 0.5 day for lint, more for detekt.
12. **Idle-turn timer and listener unregister never built (findings 7, 23); host leaving ends table; Tennis rewind unthrottled (11).** Med (V). A stalled seat blocks a table forever online. Effort 1-2 days.
13. **Duplicated infrastructure (31 palettes, 8 `gauss`, 37 `sendToHost`, 76 `Json` configs) and 24 screens over 1,400 lines.** Med (V). Mechanical consolidation guarded by the 5,201 tests. Effort 3-5 days; defer until after release.
14. **Thin or absent tests for `cards`, `airhockey` (app-side), `settings`, `foldable`, `haptics`, `theme`, `uikit`, and cross-ABI float determinism of Rust sim and physics games.** Med (V coverage, G on divergence). Effort 2 days.
15. **Stale and misleading docs (PAUSED handoff says 2,660 tests, review doc has no status column, ESP32 README claims unverified, `app_name`/28 game titles missing in ES, party strings file naming).** Low/Med (V). Effort 0.5 day.

## 13. What I did not verify

- Whether Nearby and LAN transports cap payload size (finding 12).
- Contents of 67 `fun snapshot()` implementations for version fields, beyond a grep.
- Actual vulnerability status of any dependency (no scanner run, no network lookups).
- Lint results, R8 behaviour, 16 KB alignment, and Docker build: all need a build I was told not to run.
- Whether the 99 fuzz/property-matching test files all test random legal play; matching was by keyword grep only.
