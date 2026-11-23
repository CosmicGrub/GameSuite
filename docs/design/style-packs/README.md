# Style system (StylePack): planning set

Status: PLANNING DRAFT, 2026-11-21. Nothing here is built. No app source was edited, nothing was run, nothing was committed.
Branch context: worktree `Z:\GameSuite-wt-game-kit`, scorecard of 81 games (`docs/audit/08-quality-scorecard/SUMMARY.md`).

## What this is

The owner asked for 2D and 3D animations and assets that players can alternate between. This set scopes, scales and scripts a
user-selectable **Style system**: three independent preference axes (Art style, Motion level, Quality) resolved into one
`StylePack` that every game reads through a single `LocalStylePack`, with accessibility always winning.

| File | Purpose |
|---|---|
| `SCOPE.md` | User stories, the three axes, how they combine with Reduced Motion / Colour-blind, per-game override, kid rules, MVP / v1 / later, out of scope, open questions with recommended defaults |
| `ARCHITECTURE.md` | `StylePack` interfaces per surface, fallback chain 3D -> 2.5D -> flat, CompositionLocal wiring, registry + capability detection, asset delivery (bundled vs Play Asset Delivery), per-profile storage, GameChrome v2 and frame-clock interplay, device-free testing, migration of the six card renderers |
| `SCALE.md` | Frame/memory/size budgets per level, thermal/battery rules, per-surface cost table, phased delivery with S/M/L effort per phase and per game family, risk register |
| `SCRIPT.md` | Numbered staged plan (phases, deliverables, acceptance tests, who), settings UI script with EN/ES copy, asset pipeline script, new-pack quality gate checklist, owner decisions |
| `scripts/` | DRAFT pipeline scripts (never executed): `blender_render_turntable.py`, `optimize_glb.sh`, `svg_to_imagevector.md`, `asset_size_gate.py` |

## Sources read (repo)

`docs/SETTINGS_THEMING_ACCESSIBILITY.md`, `docs/design/game-hub.md`, `docs/roadmap/QUALITY-BAR.md`,
`docs/audit/08-quality-scorecard/SUMMARY.md` + `G3-cards.md` + `G6-party-kids.md` + `ALL-GAMES.csv`, `docs/audit/02-performance.md`;
code: `settings/{AppSettings,GameSpeed,LocalReducedMotion,LocalEnhancedAnimations,LocalCard3DMode,LocalColorblindMode}.kt`,
`ui/GameTheme.kt`, `ui/effects/PremiumShaders.kt`, `ui/TablePerspective.kt`, `games/cards/Card3D.kt`, `ui/PigDiceArt.kt` (`Die3DView`),
`uikit/GameChrome.kt`, `arena/ArenaHost.kt`, `profiles/Profile.kt`, `MainActivity.kt` (provider block ~L453-467), `core/GameDataStore.kt` (ProfileScope).

R-A / R-B (`docs/research/art-and-animation/`): NOT PRESENT when this was written (only `bgg-geeklist-307608-page1.md` and
`kids-5yo-games-research.md` exist in `docs/research/`). This set refers to "see R-A/R-B" for art direction and animation technique
choices and is designed so either outcome slots in: R-A/R-B decide WHAT the packs look like; this set decides the MECHANISM.
Where a choice depends on them it is tagged `[R-A/R-B]`.

## Key findings that shaped the design

1. A partial style system already exists as four unrelated booleans: `LocalCard3DMode` (default false), `LocalEnhancedAnimations`
   (default true), `LocalReducedMotion`, `LocalColorblindMode`, plus `GameSpeed`. The StylePack should SUBSUME the first two, not sit beside them.
2. "3D" today is Canvas/graphicsLayer perspective (`Card3D.kt`, `tablePerspectiveTilt`) and a software-projected die (`drawDie3D` in `PigDiceArt.kt`).
   No 3D engine is in the app. A GLB/Filament path would be a new dependency; the project previously declined a Filament bet ("Not this round",
   per the Fold 5 deployment notes). So the plan ships "Full 3D" first WITHOUT an engine (software projection + pre-rendered turntable sprite strips)
   and treats a real engine as an optional, owner-gated later phase.
3. Six card renderers + 14 board games with per-game ad-hoc materials: the scorecard's own R1/R3 program (deck component, materials) is the natural
   first consumer of the surface interfaces. Style system and R3 should be one program, not two.
4. Perf audit: always-on per-frame loops (37 screens run a `withFrameNanos` particle loop; `specularSweep` rebuilds a RenderEffect per frame; the Home mesh
   gradient is gated only by Reduced Motion, not battery saver). Any higher style level multiplies these costs, so the frame-clock / overlay-pause fixes
   (GameChrome v2) are a PREREQUISITE for the Lively and High tiers, not a parallel nicety.
5. Device testing is a standing limitation (no APK installs by Claude): the plan leans on pure JVM tests for registry/fallback logic and an owner-approved
   JVM screenshot library (decision D6), plus self-run test kits for real devices.
6. Play facts verified by search: asset-pack delivery modes install-time / fast-follow / on-demand exist; Play states a 100-asset-pack cap per bundle and compressed-size-based limits
   (https://support.google.com/googleplay/android-developer/answer/9859372). Per-pack size numbers conflict across sources: UNVERIFIED, check before sizing packs near limits.
   Thermal API (`PowerManager.getCurrentThermalStatus` / `addThermalStatusListener`, API 29) confirmed at
   https://developer.android.com/reference/kotlin/android/os/PowerManager; NONE does not prove no throttling per https://developer.android.com/games/optimize/adpf/thermal.
