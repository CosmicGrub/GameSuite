# Style system: implementation status (Phase 1, Gem Cascade pilot)

Branch `session/ws-style` (from `session/wave-3` 336ee77). Date 2026-11-22. This file says what is BUILT, what is a STUB and
what is UNVERIFIED. Integration steps for the coordinator are in `app/src/main/java/com/gamesuite/style/integration-notes.md`.

## Decisions followed
3D = software projection + baked sprite packs, no engine. Fallback chain 3D -> 2.5D -> flat. Three axes: Art style (Flat 2D / 2.5D / 3D),
Motion (Calm / Standard; Lively is modelled but NOT offered: `StyleLimits.LIVELY_ENABLED = false`), Quality (Auto / High / Balanced / Battery saver).
Default look for a new install is 2.5D with 3D opt-in. Low-RAM (< 3 GB or the low-RAM flag) devices never get 3D. Kid profiles are capped at 2.5D and
get a silent "Eco mode" reason instead of battery or thermal text. The renderer only reads the engine's event log; the sim is untouched.

## Built (JVM-tested unless noted)

| Piece | Where | Notes |
|---|---|---|
| Model: tiers, motion, quality, ten surfaces, reasons, `StylePack`, `SurfaceSupport` (cost, assets, API, AGSL, kid-safe, colour-blind marks) | `style/StyleModel.kt` | pure |
| Device capability detection (`RawDeviceFacts` -> `DeviceCaps`) | `style/DeviceCaps.kt` | pure; Android probe + receivers in `style/ui/StyleRuntime.kt` (compiled only) |
| Registry (classic, soft25d, baked3d) + `GameStyleSupport` + `StyleResolver` + `StyleHealth` | `style/StyleResolver.kt` | per-surface fallback, running frame-budget, precedence: kid/RAM/quality, then accessibility |
| Preferences (global art/motion/quality + per-game override) | `style/StylePrefs.kt` | DataStore `style_prefs`, created with `gameDataStore`, so it is PER PROFILE (kids included) |
| Projection: `BoardCamera` (tilt + perspective + exact inverse), `IsoProjection` (picking inverse, depth key) | `style/Projection.kt` | round-trip tests |
| Sprite atlas manifest, `BudgetLru`, `AtlasBudget` (28 / 18 MB), `DrawCommandBudget` | `style/SpriteAtlas.kt` | test reads the shipped PNGs and checks budgets |
| Event log -> timeline (`GemTimeline`), particles (`GemParticles`) | `style/gem/` | style independent; Reduced Motion = zero durations, no bursts |
| Gem Cascade painter: Flat (original look), 2.5D (tilted plate, soft shadow, layered bevel gems, parallax specular), 3D (slab + baked sprites + shards) | `style/gem/GemBoardPainter.kt` | COMPILED, NOT PLAYED |
| Atlas: 19 original sprites (7 gems, prism, 4 special overlays, cage, 3 stones, spider, butterfly, treasure) at 160 and 96 px | `app/src/main/assets/styles/gem-cascade/`, generator `scripts/gen_gem_cascade_atlas.py` (Blender 5.1 headless) | visually checked on a contact sheet; about 0.45 MB |
| Settings "Look and motion" section (art cards with live previews, motion, quality, reasons, reset) | `style/ui/LookSettings.kt`, 1 insertion in `ui/SettingsScreen.kt` | COMPILED, NOT PLAYED |
| Gem Cascade wiring: style resolution, timeline playback, bursts, tilt-aware picking, tilt-aware TalkBack cell boxes, Look item in the chrome menu | `ui/GemCascadeScreen.kt` | COMPILED, NOT PLAYED |
| EN + ES strings | `res/values*/style_strings.xml` | parity test |

## Stubs / not built
- Renderer interfaces for CARD, DIE, TILE, BACKGROUND, CHROME, SOUND_PALETTE, HAPTIC_PROFILE are empty marker interfaces. Gem Cascade BACKGROUND and CHROME have no separate 2.5D/3D look yet (they resolve, but nothing draws them differently).
- No Play Asset Delivery: the pilot atlas is in base assets (about 0.45 MB). `AssetPackSource` / download UI not built.
- No `LocalStylePack` provider in MainActivity (not edited): the Gem screen resolves its own style with `rememberGameStyle`. `LocalStylePack` exists (default Classic) for later.
- Frame-health demotion by measured jank, thermal hysteresis, parent-gated kid picker, `KidControls` policy keys, per-game "Look" row in game setup, coach tip, battery toast: not built. Kid profile age band is not consulted (no age API on `ProfileScope`), so kids get the same Standard motion unless Reduced Motion is on.
- Sound palette and haptic profile per style: cues are shared across styles (timeline has the cue sheet).
- Colour-blind mode: shapes are baked into all three looks, so no extra marks are drawn; not verified with a simulation.
- No golden-image tests (no JVM screenshot library approved).

## Unverified (nothing was run on a device or emulator)
All drawing code (painter, previews, Settings section, screen wiring) compiled and unit-tested only for its pure parts. Visual quality of 2.5D, 3D board slab, particles,
tilt, and the picking/TalkBack alignment under tilt need a self-run device pass. The frame budgets (costs in `StylePackRegistry`) are estimates from `SCALE.md`, not measurements.
