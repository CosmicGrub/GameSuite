# ARCHITECTURE: StylePack

All code below is a DESIGN SKETCH in docs, not source. Packages are proposed: `com.gamesuite.style` (pure Kotlin where possible so it
runs in JVM unit tests; Compose-dependent parts split into `com.gamesuite.style.ui`). Target: Kotlin, Compose, minSdk 26, compileSdk 34
(per CLAUDE.md).

## 1. Principles

1. One resolved object, one CompositionLocal: games read `LocalStylePack.current`, never six booleans.
2. Surfaces, not games: a pack implements SURFACES (board, piece, card, die, tile, background, chrome, celebration, sound, haptics). Games declare which surfaces they use.
3. Every surface has a flat fallback that always exists (Classic). Fallback is per surface and per device.
4. Decision logic is pure and testable on the JVM; drawing code is thin.
5. Accessibility is part of resolution, not a per-call-site afterthought (precedence in SCOPE section 4).
6. Cost is declared (a number), so the resolver can reason about budgets rather than guess.

## 2. Core types (sketch)

```kotlin
enum class ArtTier { FLAT, SOFT_25D, FULL_3D }          // ordering = cost ordering; fallback goes down
enum class MotionLevel { CALM, STANDARD, LIVELY }
enum class QualityLevel { AUTO, HIGH, BALANCED, BATTERY_SAVER }

enum class Surface {
    BOARD_SURFACE,   // table/board material (felt, wood, enamel), backdrop of the play area
    PIECE,           // pawns, discs, stones, checkers, chess pieces, seeds
    CARD,            // card face/back/deck, FannedHand rendering
    DIE,             // d6 / custom dice, tumbling + settled
    TILE,            // letter/domino/mahjong/number tiles
    BACKGROUND,      // screen backdrop (mesh gradient, parallax layers)
    CHROME,          // buttons, chips, panels, dialogs framing
    CELEBRATION,     // confetti, win glow, sticker moment, particles
    SOUND_PALETTE,   // event -> sound mapping (CardSounds / Rust audio core)
    HAPTIC_PROFILE   // event -> HapticSignal mapping (haptics/Haptics.kt)
}

/** What a pack can do for one surface at one tier, and what that costs. */
data class SurfaceSupport(
    val tier: ArtTier,
    val cost: SurfaceCost,          // see SCALE.md section 4
    val needsAssets: AssetPackId?,  // null = pure code, always bundled
    val minApi: Int = 26,
    val needsAgsl: Boolean = false, // API 33+
    val kidSafe: Boolean = true
)

interface StylePack {
    val id: StylePackId                  // "classic", "soft25d", "storybook", ...
    val displayNameRes: Int
    val audience: Audience               // ALL_AGES | TEEN_ADULT
    val suggestedPaletteHint: PaletteHint? // advisory only, never overrides NamedTheme
    /** What this pack can provide. Surfaces absent here fall back to the next pack in the chain. */
    fun support(surface: Surface): List<SurfaceSupport>   // one entry per tier it implements
    fun <T : SurfaceRenderer> renderer(surface: Surface, tier: ArtTier): T?
    val licenseEntry: LicenseRef         // gate: no licence entry, no pack
}

/** Marker; each surface has its own sub-interface below. */
sealed interface SurfaceRenderer
```

### Per-surface renderer interfaces (all `@Composable`-based, state-free; games pass data, never styles)

```kotlin
interface BoardSurfaceRenderer : SurfaceRenderer {
    @Composable fun Draw(spec: BoardSurfaceSpec, modifier: Modifier)         // rows/cols/shape, light+dark tone, lastMove hint
}
interface PieceRenderer : SurfaceRenderer {
    @Composable fun Piece(piece: PieceSpec, state: PieceVisualState, modifier: Modifier)
    // PieceSpec carries: kind (disc/pawn/king...), ownerIndex, a11y shape id (for colour-blind), label
}
interface CardRenderer : SurfaceRenderer {
    @Composable fun Face(card: CardSpec, modifier: Modifier)
    @Composable fun Back(deck: DeckBackSpec, modifier: Modifier)
    val flip: CardFlipSpec                                  // how a flip animates at this tier
}
interface DieRenderer : SurfaceRenderer {
    @Composable fun Die(value: Int, roll: DieRollState, modifier: Modifier)  // spin, hop, bust as in Die3DView today
}
interface TileRenderer : SurfaceRenderer {
    @Composable fun Tile(tile: TileSpec, modifier: Modifier)
}
interface BackgroundRenderer : SurfaceRenderer {
    @Composable fun Draw(spec: BackgroundSpec, modifier: Modifier)           // gradient / parallax; must be idle-free when not visible
}
interface ChromeRenderer : SurfaceRenderer {
    @Composable fun Button(spec: ChipSpec, ...) ; @Composable fun Panel(...)
}
interface CelebrationRenderer : SurfaceRenderer {
    fun burst(kind: CelebrationKind, budget: ParticleBudget): CelebrationPlan  // data, drawn by the shared frame clock
}
interface SoundPalette : SurfaceRenderer { fun soundFor(event: GameEvent): SoundRef? }
interface HapticProfile : SurfaceRenderer { fun signalFor(event: GameEvent): HapticSignal? }
```

Rules for implementations:
- Read time-varying state only inside draw/`graphicsLayer` lambdas (perf audit 3.2). No `rememberInfiniteTransition` values read in composition.
- No self-owned `while(true) withFrameNanos` loops: ask the shared frame clock (section 8).
- Every renderer receives `PieceSpec`/`CardSpec` with its colour-blind shape id and a content description string; the renderer MUST draw the shape cue when `LocalColorblindMode` is true and MUST set semantics (renderers cannot opt out of a11y).
- Text uses scalable `sp` and respects `LocalCardScale`/`textScale` (scorecard finding 4: fixed-sp card text).

## 3. How a game declares which surfaces it uses

```kotlin
data class StyleNeeds(val surfaces: Set<Surface>, val require: Map<Surface, SurfaceSemantics> = emptyMap())
// Added to GameModule (or the GameHubSpec table in core/GameHubSpecs.kt, which already holds per-game metadata):
//   Reversi:  StyleNeeds(setOf(BOARD_SURFACE, PIECE, CELEBRATION, SOUND_PALETTE, HAPTIC_PROFILE))
//   Solitaire: setOf(BOARD_SURFACE, CARD, CELEBRATION, ...)
//   Pig Dice:  setOf(BOARD_SURFACE, DIE, CELEBRATION, ...)
```

- Default = `{BOARD_SURFACE, BACKGROUND, CHROME, CELEBRATION, SOUND_PALETTE, HAPTIC_PROFILE}` so an unmigrated game still gets chrome and sounds.
- `GameHubSpecs` is the natural home (29 specs today; the scorecard lists 81 games, so the remaining 52 need specs or a default entry: tracked as a migration item).
- Needs drive: (a) which styles the picker offers for that game, (b) which assets the on-demand pack manager prefetches, (c) the family-by-family migration order (SCALE section 6).
- A game must NOT call a renderer for a surface it did not declare (debug-build assertion; the `StyleNeedsTest` verifies declared == used by grep-style test or an annotation processor-free registry).

## 4. Fallback chain (3D -> 2.5D -> flat), per surface and per device

```
resolve(surface, request: EffectiveRequest, caps: DeviceCaps, pack chain) :
  tiers = [requestedTier, ...lower tiers]             // FULL_3D -> SOFT_25D -> FLAT
  for tier in tiers:
     for pack in [selectedPack, fallbackPack(s), ClassicPack]:
        for s in pack.support(surface).filter{ it.tier == tier }:
           if usable(s, caps, quality, assetsReady, accessibility):
               return Resolved(pack, tier, reason)
  // ClassicPack FLAT is total; reaching here is a bug (tested)
```

`usable` is false when: `s.minApi > Build.VERSION.SDK_INT`; `needsAgsl` and API < 33 (see `PremiumShaders.isSupported`); `s.cost > budget(quality)`; `needsAssets` is not installed (fall back immediately, queue download only if user approved); accessibility excludes it (Reduced Motion removes tilt-class tiers' motion but the static 2.5D look may stay); kid profile and `!s.kidSafe`.

Fallback is data, not exceptions: the resolver returns a `Resolved` and a `reason` enum (`ASSETS_MISSING`, `LOW_RAM`, `BATTERY_SAVER`, `THERMAL`, `NO_AGSL`, `REDUCED_MOTION`, `KID_SAFE`, `COLORBLIND`) the settings UI can show. Runtime failure (shader compile error, asset decode error) triggers a one-way downgrade for that surface for the session (`StyleHealth.demote(surface)`), logged, never a crash (matches the existing contract in `PremiumShaders`: "must never be able to crash a screen").

Fallback pack chain: `selected pack -> its declared parent (e.g. storybook -> classic) -> classic`.

## 5. CompositionLocal wiring and `LocalStylePack`

Today `MainActivity` provides ~10 locals at L453-467 from `settings` (`LocalReducedMotion`, `LocalCard3DMode`, `LocalEnhancedAnimations`, `LocalColorblindMode`, `LocalGameSpeed`, ...). Plan:

```kotlin
@Immutable class ResolvedStyle(      // what games actually read
    val pack: StylePack,
    val art: Map<Surface, ResolvedSurface>,   // pre-resolved per surface, stable instance per (settings,caps) change
    val motion: MotionLevel, val quality: QualityLevel,
    val particles: ParticleBudget, val reasons: List<StyleReason>
) { fun <T : SurfaceRenderer> renderer(s: Surface): T }

val LocalStylePack = staticCompositionLocalOf<ResolvedStyle> { ClassicResolved }   // never null; safe default for previews/tests
```

- Provided ONCE in `MainActivity` next to the existing locals, wrapped by a per-game `GameStyleScope(gameId) { ... }` that applies the per-game override and the game's `StyleNeeds` (shrinks the set of surfaces resolved).
- `staticCompositionLocalOf` because it changes rarely and every composable reads it; avoid recomposing the whole tree on read.
- The old locals are KEPT as derived values for backward compatibility during migration: `LocalCard3DMode provides (resolved.art[CARD] != FLAT && ...)`, `LocalEnhancedAnimations provides (motion != CALM)`, `LocalReducedMotion` unchanged (still the accessibility source). Call sites migrate family by family; the old locals are deleted last. No call site changes in the first phase.
- Settings first-frame problem (perf audit 2.1: first frame renders with default `AppSettings()`): resolve from the persisted style key synchronously via the splash/`produceState` guard that item 2.1 already proposes; the style default must not flip Calm->Standard on frame 2.

## 6. Registry and capability detection

```kotlin
object StyleRegistry { val packs: Map<StylePackId, StylePack>; fun chain(id: StylePackId): List<StylePack> }

data class DeviceCaps(
    val apiLevel: Int, val agsl: Boolean,            // apiLevel >= 33 (PremiumShaders.isSupported)
    val ramClass: RamClass,                          // from ActivityManager.isLowRamDevice + memoryClass + MemoryInfo.totalMem
    val maxRefreshHz: Float,                         // Display.supportedModes / preferred
    val batterySaver: Boolean,                       // PowerManager.isPowerSaveMode (already read in rememberPowerSaveMode)
    val thermal: ThermalLevel,                       // PowerManager.getCurrentThermalStatus + addThermalStatusListener (API 29+)
    val osAnimationsOff: Boolean,                    // Settings.Global ANIMATOR_DURATION_SCALE == 0
    val gpuTier: GpuTier?                            // UNVERIFIED approach: see below
)
```

Detection (pure mapping function `detect(rawFacts): DeviceCaps` on the JVM; thin Android adapter reads raw facts):
- RAM: `ActivityManager.isLowRamDevice()`, `memoryClass`, total RAM; thresholds (proposed): LOW < 3 GB or low-ram flag, MID 3-6 GB, HIGH > 6 GB. Numbers are PROPOSALS to calibrate.
- Refresh: highest `Display.Mode` refresh; used only to scale particle budgets, not to change logic (all sim code is time-based per perf audit section 4).
- Battery saver: `PowerManager.isPowerSaveMode` + `ACTION_POWER_SAVE_MODE_CHANGED` (the app already has `rememberPowerSaveMode`; hoist it out of `PremiumShaders` into `style/`).
- Thermal: `getCurrentThermalStatus()` / `addThermalStatusListener` (API 29+; verified at developer.android.com/reference/kotlin/android/os/PowerManager). Status NONE is not proof of no throttling (https://developer.android.com/games/optimize/adpf/thermal), so thermal is a signal, not the only one.
- GPU tier: there is no clean official GPU-tier API. Proposal: do NOT guess from GPU strings (UNVERIFIED reliability). Use RAM class + API level + a rolling frame-health measurement (below) instead.
- Frame health: sample `FrameMetrics`/Choreographer jank rate over a window while a style is active; two bad windows in a row demote Auto by one step, with hysteresis (promote only after N good windows and never mid-game). Auto only ever lowers from the user's request.
- Auto resolution: `AUTO -> f(caps)`: LOW RAM or batterySaver -> BATTERY_SAVER/BALANCED; thermal >= MODERATE -> BALANCED, >= SEVERE -> BATTERY_SAVER; HIGH ram, API 33+, no saver, thermal none -> HIGH.
- The rules are a pure function `resolveQuality(request, caps, frameHealth)`; 100% unit-testable.

## 7. Asset pack delivery

Tiers of assets:
| Class | Contents | Delivery |
|---|---|---|
| Code-only (Classic, Soft 2.5D) | Compose vectors, gradients, AGSL strings | In the base APK/AAB (no assets) |
| Small raster/vector assets | ImageVectors, small WebPs, sounds | Bundled in base `res/`/`assets/` |
| Sprite strips / GLBs / large textures (Full 3D, Storybook illustrations) | WebP/KTX2 strips, optimised GLB | Play Asset Delivery asset packs |

Play Asset Delivery facts (searched 2026-11-21): delivery modes install-time, fast-follow, on-demand exist
(https://android-developers.googleblog.com/2020/06/introducing-google-play-asset-delivery.html). Play Console Help documents size limits based on compressed
download size and a cap of 100 asset packs per bundle (https://support.google.com/googleplay/android-developer/answer/9859372). A 200 MB base-module compressed cap is reported
by secondary sources, not confirmed on the official page I could read: UNVERIFIED. Per-pack limits (512 MB vs 1 GB vs 1.5 GB) conflict across sources: UNVERIFIED, so design each pack to stay well under 150 MB and re-check before any pack grows.

Plan:
- Gradle: one `:style_full3d` asset-pack module (delivery `on-demand`) and optional per-family packs (`:style_cards3d`, `:style_dice3d`, `:style_storybook`). Install-time only for tiny, mandatory assets (none needed for MVP). Fast-follow is NOT used (it downloads without user consent, conflicting with kid/parent rules P2).
- Runtime: `AssetPackManager` wrapper (`PackStore`) behind an interface `AssetPackSource { state(packId): Flow<PackState>; request(packId); remove(packId) }` with a fake for tests. Real impl uses the Play Core / Play Asset Delivery library (library version: check current at implementation time, UNVERIFIED here).
- State machine: `NOT_INSTALLED -> REQUESTED -> DOWNLOADING(progress) -> READY | FAILED | NEEDS_WIFI | NEEDS_USER_CONFIRMATION`; the resolver treats anything but READY as "assets missing" and falls back immediately, so the UI never blocks.
- Cache: Play stores packs; on top, the app keeps a decoded-bitmap LRU cache (budget in SCALE section 3), keyed by (packId, asset, size bucket), cleared on `onTrimMemory`.
- Integrity: Play verifies delivery; the app additionally checks a `manifest.json` per pack (schema version, asset list, SHA-256 per asset, `minAppVersion`) at first use; mismatch or missing file = surface demotion + `StyleHealth` event; never decode an asset not listed in the manifest.
- Offline: a READY pack works with no network; if not READY, flat fallback. Kid profiles: download request requires `ParentGate` approval and Wi-Fi by default.
- Sideloaded/debug builds without Play: packs are included by the debug flavour (`bundleAllPacksInDebug`) so JVM/tooling never depend on Play.
- No remote code: packs contain DATA only (images, models, JSON). No dex, no scripts, no AGSL downloaded (AGSL stays in the APK), which keeps review and kid-safety tractable.

## 8. Interaction with GameChrome v2 (overlay pause) and the frame clock

The scorecard's systemic findings #2 (no overlay pause) and #3 (per-frame recomposition) are prerequisites:
- `overlayOpen` hook: when the game menu, help, leave dialog, or the style picker is open, GameChrome v2 pauses the game AND suspends the style frame clock (no particles, idle loops or shaders running behind a dialog).
- One lifecycle-gated shared frame clock (`StyleFrameClock`): surfaces register "I need ticks while visible" (celebration, idle shimmer, parallax); it stops when no consumers, the lifecycle is below RESUMED, an overlay is open, quality is BATTERY_SAVER, or Reduced Motion forbids it. This replaces the 37 perpetual `rememberParticleBurst()` loops (perf 3.4).
- Style change from inside a game: the picker lives in GameChrome's menu; opening it pauses via overlayOpen, changing it re-resolves `ResolvedStyle`, and surfaces recompose (no activity restart). Assets that are not READY keep the previous renderer until ready.
- Game speed: unchanged and orthogonal; `GameSpeed.scale()` multiplies waits, `MotionLevel` multiplies expressive durations. Arena speeds (ARENA_X4, ARENA_MAX) force CALM and flat at the `ArenaHost` boundary so animations never cap throughput.

## 9. Storage of preferences (per profile)

- Keys go in the existing `app_settings` DataStore, which is created through `gameDataStore("app_settings")` (`core/GameDataStore.kt`) and is therefore PER PROFILE via `ProfileScope` unless named in `deviceLevelStores`. So each profile (adult, each kid) naturally gets its own style prefs: no new storage mechanism.
- Keys (proposed): `style.art` (pack id), `style.motion`, `style.quality`, `style.override.<gameId>` (JSON of art/motion), `style.migrated_v1` (boolean).
- Kid-profile policy keys sit with parent controls (`ControlsState`/`KidControls`): `allowedPacks`, `lockStylePicker`, `allowDownloads`.
- Device-level (NOT per profile): downloaded pack state and decoded-asset cache, and `DeviceCaps` snapshot.
- Defaults are read synchronously for the first frame (see section 5). Unknown/removed pack ids resolve to Classic silently.
- Migration of existing values: `card3DEnabled=true` -> art `soft_25d`; `enhancedAnimationsEnabled=false` -> motion Calm-equivalent only if `reducedMotion` was true else Standard with reduced particle budget (decision D4); `reducedMotion` stays as its own accessibility setting (never folded in). Old keys are kept readable for one release for rollback.

## 10. Testing without devices

Constraint: no APK installs/emulators from these sessions (standing owner rule); `CLAUDE.md` runs `:app:testDebugUnitTest` and `:shared:allTests` on CI.
1. Pure unit tests (JVM, no Android): `StyleResolver` precedence table tests (every combination of Reduced Motion x Colour-blind x Quality x kid x pack support), fallback chain totality (every surface always resolves), `detect(rawFacts)` thresholds, `resolveQuality` hysteresis, migration mapper, manifest/hash validator, kid filter, `StyleNeeds` vs registry consistency, size budgets read from a checked-in `style-budgets.json`.
2. Property tests: random (settings, caps, pack-support) never throws and never resolves above the request; accessibility invariants hold (`reducedMotion => no tilt/parallax/shake`).
3. Contrast tests: extend the existing `Wcag` checks (GameThemeTest) to each pack's palette tokens in light, dark and High Contrast.
4. JVM screenshot tests: IF the owner approves a library (decision D6; candidates to evaluate: Paparazzi, Roborazzi: UNVERIFIED current status, check at decision time), render each renderer at flat/2.5D/3D x light/dark x large font to golden PNGs on the JVM with no device.
5. Asset gates in CI (script drafts in `scripts/`): size gate, manifest, texture dimension and format checks.
6. Real-device passes: self-run kit only (owner runs and reports), as per the standing limitation.

## 11. Migration from today's six card renderers

Today (G3 S1): Solitaire uses `PlayingCardView` (flat white tile), Hold'em has `HoldemCardArt.kt` (four-colour option), Spider has `SpiderCard`, Hanafuda `HanafudaArt.kt`, UNO its own, War/Blackjack variants. `games/cards/` already holds `Card.kt`, `Card3D.kt`, `CardScale.kt`, `CardSounds.kt`, `FannedHand.kt`, `PlayingCardView.kt`.
1. Define `CardSpec` (suit/rank or UNO colour/value or hanafuda month, plus colour-blind shape id and description string) and `CardRenderer`.
2. Implement Classic `StandardCardFace` by lifting the best existing face (scorecard says make Solitaire consume it, matching Hold'em/War), then the colour-blind four-colour deck as an attribute not a fork.
3. Wrap each existing renderer as a `CardRenderer` adapter, unchanged visually (zero-diff screenshot goldens), registered under pack `classic` with a `legacyOf = "uno"` tag.
4. Move one game at a time to the shared face; delete the adapter when its game is on the shared component. Order: Solitaire, War, Blackjack, Hold'em, Spider, UNO (UNO last: most state, networking, and a redesign already shipped).
5. Soft 2.5D cards = shared face + `Card3D` flip/toss + soft shadow; Full 3D cards = sprite-strip flip (or projected quad) behind the same interface.
6. Deck back and table felt become `BOARD_SURFACE`/`CARD` options in packs, replacing per-game one-off choices (e.g. UNO's card back/table felt in SETTINGS_THEMING_ACCESSIBILITY section 3).
