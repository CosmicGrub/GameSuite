# Agent briefs (so any fresh Claude session can resume a worktree's job after a usage limit)

A running agent dies when its session hits a limit. Its commits survive on its branch. To resume: start a new build
agent in the worktree below with the COMMON RULES plus the scope line. First step for every agent:
`git merge session/wave-3` in its worktree, `git log --oneline session/wave-3..HEAD` to see what already exists,
then read its `integration-notes.md` / docs for done-vs-open and continue from there. Never restart finished work.

## COMMON RULES (every agent, every time)
- No APK/build installs on any device or emulator; never `connectedAndroidTest`.
- One Gradle build machine-wide: `until mkdir /tmp/gs-gradle.lock 2>/dev/null; do sleep 10; done; ./gradlew ...; rmdir /tmp/gs-gradle.lock`
  with `--build-cache --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g -Pkotlin.compiler.execution.strategy=in-process`.
  Targeted `--tests` only (touched packages + `EnglishLiteralRatchetTest` + string/coach/hub tests). Check the real test count in
  `app/build/test-results/testDebugUnitTest/*.xml`.
- `rust/target`: `cp -r` from `Z:\GameSuite-wt-game-kit\rust\target` if missing (robocopy fails silently). `df -h /z` must stay >4 GB.
- Preserve CRLF. Path-scoped commits only, small and frequent, no `git add -A`, trailer
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- **Tests that must survive a usage limit:** submit them to the detached queue instead of running gradle inline:
  `bash scripts/testq-submit.sh <worktree> <job-name> --tests 'com.gamesuite.games.foo.*'` (prints the result file to poll:
  `/z/GameSuite-testq/done/<id>.result`, log in `/z/GameSuite-testq/logs/`). The worker (`scripts/testq-worker.sh`, kept alive by the
  Windows task `GameSuite-TestQueueWorker`) runs jobs one at a time under the gradle lock and finishes them even if every Claude session is gone.
  A resumed agent reads the `.result` files instead of re-running tests. Device-driving jobs use `--device <worktree> <name> "<one command>"`
  and stay HELD until the owner creates `/z/GameSuite-testq/DEVICES_AUTHORIZED` (owner rule: no installs/driving of devices without an explicit yes).
- Plain `git push origin <own session/* branch>` whenever targeted tests are green and at each milestone. Never force, never main,
  never rewrite history. Never merge into session/wave-3 (the integrator does).
- Do not edit shared registration files (GameHubSpecs, GameCatalog, GameTheme, MainActivity, GameTips, GameCoach) except the
  minimal edits named in a brief; new games ship an `integration-notes.md` with the snippets instead.
- Original art/names only, no trademarks. EN+ES strings in NEW files, no hard-coded English (the ratchet must not grow).
- Keep the branch's `integration-notes.md` current: done / open / unverified (compiled-not-played).

## Briefs
| Worktree / branch | Scope |
|---|---|
| `ws-style` / `session/ws-style` | Art-style system Phase 1 + Gem Cascade pilot: StylePack model, LocalStylePack, registry with 3D->2.5D->flat fallback (RAM<3GB never 3D, kid profiles silent Eco), Look settings UI (Art style/Motion Calm-Standard/Quality), projection + picking math, sprite atlas cache (<=28 MB tablet/18 MB mid), Gem Cascade rendered in Flat/2.5D/baked-3D from the GemEvent log (atlas <=2 MB, Blender 5.1.2 headless or procedural), single Canvas + frame clock. Docs: docs/design/style-packs/*, docs/design/gem-cascade/*. Write style-packs/IMPLEMENTATION-STATUS.md and style/integration-notes.md. |
| `ws-bb2` / `session/ws-bb2` | Bot Bastion follow-ups: coach button (rememberClCoach), tabletop two-bar co-op layout, Scrap-gift button, per-sector backdrops + ambient (Calm/Standard), phone-portrait layout test, hub Resume, fix stale numbers in zacisa-mode/SPEC.md + ZSWeaponsEnemies board, restore `furnace` map if it can pass T11. |
| `ws-td2` / `session/ws-td2` | Tower Defence follow-ups: tips + coach copy (only the tower-defence entries in shared files), ResumableGame, Snap-off toggle, drag-from-tray placement, Burn ground fire patch, keyboard/D-pad, Fold/tablet/portrait layouts + minimap, Roman Frontier + Cherry Ridge themes, Endless + Daily (DailySeed) with local best. No Lively motion. |
| `ws-plat2` / `session/ws-plat2` | Platform leftovers: P6 hit-slop + non-square fitBoard, Air Hockey one-Canvas fix, Toy Soccer/Tennis/Slot Cars/Fishing on GameFrameClock + overlay pause, per-tier daily seed, shrink English-literal debt (regen baseline with ENGLISH_BASELINE_REGEN=1 when it shrinks). |
| `ws-bb3d` / `session/ws-bb3d` | Bot Bastion baked-isometric sprite pack (assets only): Blender headless, all bots (5 level variants), enemies/bosses, Core, FX, node markers, range rings, 4 sector sets, 8-facing atlases + JSON manifest in `app/src/main/assets/styles/bot-bastion/` (<=6 MB), generator in `scripts/`, manifest JVM test. Spec: docs/design/tower-defence/bot-bastion-3d/. |
| `ws-release` / `session/ws-release` | Release build: R8/proguard keep rules (serialization, DataStore, JNI Rust libs), signing from untracked local props (never commit keystore/passwords), `assembleRelease` + `bundleRelease` verified, targetSdk per docs/release/TARGET-SDK.md, update docs/release/*.md with verified facts + owner steps. |
| `ws-sweet-trail`, `ws-trail-together`, `ws-find-it` | Finish Sweet Trail, Trail Together, Find It Frenzy (scope docs in docs/design/boardgames|kids-games): engine, hub spec (unregistered), validated snapshot + corruption tests, ResumableGame/PausableGame, `LaunchPolicy.requestNewRound()` in playAgain, EN/ES strings, tips/coach strings, Compose screen (single Canvas, 48dp, TalkBack, Reduced Motion), integration-notes. One game fully green before the next. |
| `ws-pie-detective`, `ws-stack-zoo`, `ws-flick-rink` | Same as above for Pie Detective, Stack Zoo, Flick Rink (Stack Zoo and Flick Rink: deterministic fixed-timestep physics, JVM-testable). |
| `ws-kids`, `ws-arena` | ws-kids: `LaunchPolicy.requestNewRound()` in playAgain of the 11 new-game packages, P12 profile bootstrap blocking, P13 adult re-lock, other WIP kids items. ws-arena: Computer Arena ArenaHook for more games (Gem Cascade GemBot, Bot Bastion, Tower Defence if cheap), tests, contract = visible CPU-vs-CPU, not recorded; avoid editing GemCascadeScreen.kt (ws-style owns it). |

## Not agent work (owner-gated, never guess)
Trademark searches (Gem Cascade, Bot Bastion, Pie Detective, Life Road, Property Tycoon, Territory Wars, Flick Rink, Sweet Trail,
Bump and Run, Trail Together, Slide and Climb); native Spanish review; Gem Cascade scope picks; Bot Bastion 3D Phase 1 look sign-off
and the 3-day real-3D spike; relay host; kids-category listing; on-device testing; any device install.

## Queued after the above (not started)
Bot Bastion 3D Phase 1 renderer (needs ws-style infra + ws-bb3d atlases), Gem Cascade extras (personal best store, haptic cue sheet,
richer sound), remaining Tower Defence themes (5) and modes (Sandbox, Co-op), phase-3 kids games (Bump and Run, Egg Garden, Life Road,
Property Tycoon, Territory Wars), accessibility/Spanish sweep, online wave-5 client (blocked on relay host).
