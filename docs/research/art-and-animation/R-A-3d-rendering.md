# R-A-3d-rendering: an option ladder for user-selectable art/animation styles incl. 3D

Date: 2026-11-21. Status: research only, nothing built. Scope: Android (Kotlin, Compose, minSdk 26).
Confidence tags: VERIFIED = seen in repo or in a cited search result; UNVERIFIED = not confirmed by any source I could reach (WebFetch was unavailable, search snippets only); EST = my estimate.

## 0. Framing

On 2026-09-07 and again 2026-09-12 the owner declined a Filament/SceneView true-3D bet (docs/ENGINE_DECISION.md, addendum at line 621). That addendum says a third raising should come from "a specific, named reason". User-selectable art styles can be such a reason, but the cost is then "a style switch the user opts into", not "3D for a few rare moments". So 3D is presented as a ladder, each rung independently shippable, with the cheap rungs doing most of the work.

## 1. What the repo already has (VERIFIED by grep/read)

| Asset | Where | Meaning for this decision |
|---|---|---|
| Software 3D die | `app/.../ui/PigDiceArt.kt` (`drawDie3D`, `Die3DView`; used by Pig, SlideClimb) | Hand-rolled 3x3 rotation + perspective + lamp shading on Compose `Canvas`, no image files. Already a "real geometry, CPU-projected" renderer for convex solids. A d6 needs no engine. |
| `card3DFlip` | `games/cards/Card3D.kt` (79 lines) | `graphicsLayer { rotationY; cameraDistance }`. Level 2 already shipping. Gated by `LocalCard3DMode` + `LocalReducedMotion`. |
| Settings plumbing | `settings/LocalCard3DMode.kt`, `LocalEnhancedAnimations.kt`, `LocalReducedMotion.kt`, `GameSpeed.kt` | A style selector can hang off the same CompositionLocal pattern; "reduced motion wins" is already documented. |
| AGSL | `ui/effects/PremiumShaders.kt` (371 lines): SPECULAR_SWEEP, VICTORY_GLOW, FELT_GRAIN, MESH_GRADIENT; every entry gated `SDK_INT >= 33`, wrapped in `runCatching` | Level 1 exists, but only API 33+ gets it. Both owner tablets (Android 14+) do. |
| Known cost problems | `docs/audit/02-performance.md` 3.1, 3.3, 3.4 | Perpetual full-screen shader on Home (HIGH); `specularSweep` rebuilds a `RenderEffect` every frame, Air Hockey puck unconditional (HIGH); 37 screens run an idle particle loop (MED). Any new style layer inherits these failure modes unless fixed first. |
| Baseline numbers | audit 02, first table row | Canvas stress pilot (100 enemies/30 towers/60 projectiles) on Tab S9 FE: avg 11.1 ms at a 90 Hz vsync, 3/599 frames over 16.7 ms. Phone numbers: none. APK size, battery, thermal, 120 Hz: "No measurement exists". |
| No GL/Filament code | grep for GLSurfaceView / filament / sceneview / Vulkan / libgdx | Greenfield. Only a TextureView mention in `sweettrail/SweetTrailCards.kt` and prose in ENGINE_DECISION.md. `res/raw` is 45 KB of wav; app ships essentially zero bitmap art (ENGINE_DECISION line 245). |
| Native build | `rust/gamesuite-sim`, `gamesuite-audio` via cargo-ndk (arm64-v8a, armeabi-v7a, x86_64, NDK 28.2) | The app already ships per-ABI native libs, so ABI filtering and 16 KB alignment are live concerns anyway. |
| Test stack | JUnit4, mockwebserver, JNA; androidTest: espresso, ui-test-junit4, uiautomator; compose-bom 2024.09.00, compileSdk 34 | No Paparazzi/Roborazzi/Robolectric today; no JVM screenshot infrastructure to extend. |

## 2. The ladder

### Level 0 - flat 2D (today)
Vector Canvas drawing, zero bitmaps. Default and fallback for every higher level.

### Level 1 - enhanced 2D (AGSL, particles, shadows)
Exists for API 33+ (AGSL `RuntimeShader` is API 33+; https://developer.android.com/develop/ui/views/graphics/agsl). Remaining work is hygiene: fix audit 3.1/3.3/3.4, add a power-save/low-RAM gate, create one `ArtStyle` object that owns all effect toggles.
- Per-frame: fragment-bound; a full-screen AGSL pass on a 2560x1600 tablet at 90-120 Hz is not free (unprofiled). Rule: shader surfaces are small (piece, die, puck) or idle-paused.
- Idle battery: ~0 for static-until-interaction effects; the perpetual ones are the cost.
- Effort: S-M, 4-6 person-days (EST), mostly the audit fixes.

### Level 2 - 2.5D (graphicsLayer tilt, parallax, billboards, turntable frames, baked shadows)
No engine. Techniques and asset math (EST unless noted):

1. graphicsLayer perspective (`rotationX/Y`, `cameraDistance`): shipping for cards; zero assets. Keep simultaneous tilted layers modest (about 30 on phone, EST, no source).
2. Pre-rendered turntable sprite sheets from Blender (a Blender MCP exists in this environment, so the pipeline can run locally). Chess: 6 piece types x 36 yaw frames x 160x160 px, grayscale albedo + alpha, tinted per side at draw time. Lossy WebP ~4-8 KB/frame gives 6x36x6 KB = ~1.3 MB. Decoded 160*160*4 = 100 KB/frame, so the full set is ~21 MB decoded; load one piece type at a time or use 12 yaw frames (0.45 MB, 7 MB decoded). A d20 turntable at 64 frames x 128 px is ~0.4 MB.
3. Normal-mapped lighting in AGSL: one 256x256 normal map per piece type as a `BitmapShader` child of a `RuntimeShader`, light vector as uniform (touch or gyro). ~60-120 KB per map, ~1 MB for 12 sets; API 33+ only. Best "looks 3D, is 2D" trick: one draw per piece, continuous light motion, no frame set.
4. Baked/fake shadows: pre-blurred radial-gradient bitmap or gradient oval under each piece (~0 bytes). Avoid `RenderEffect` blur (GPU-only; per-frame rebuild is already an audit finding).
5. Parallax layers: 3-4 background layers offset by drag/scroll; no extra cost beyond the layers.
6. Gyro parallax/tilt: rotation-vector or gravity sensor, low-pass filtered, drives layer offsets and the normal-map light vector. Register only while a board is RESUMED; off under Reduced Motion (vestibular). On a tabletop-posture Fold 5 the gyro adds nothing, so touch-driven light is the fallback.

- Per-frame: Canvas class, same as the measured 11 ms Tab S9 FE pilot. Idle battery ~0 if nothing loops while idle.
- Test: plain Compose, so Compose tests work; JVM screenshots only for non-shader paths (section 5).
- Effort: M, 10-15 person-days (EST) for a shared `ArtStyle` layer plus a piece renderer used by chess/checkers/backgammon; plus 5-8 days of Blender asset work per set.

### Level 3 - real-time 3D for selected surfaces (dice, cards, pieces, board)
Needs a real renderer (section 3). Two architectures:
- A. One shared 3D scene per screen (one surface, one camera, all pieces as nodes). Cheap in GPU/RAM, but the surface sits behind/in front of Compose UI and complicates hit-testing, overlays and Fold layouts.
- B. Many small 3D views (one per die/piece). Easy in Compose but N surfaces = N compositor layers/contexts; avoid beyond 2-3 instances. Better variant: use the engine only as a build-time sprite generator, which is Level 2 item 2.
- Effort: L, 25-40 person-days (EST) for one surface class including lifecycle, tests, asset pipeline and fallback.

### Level 4 - full 3D game scenes (Air Hockey, Tower Defence, Bowling)
An engine decision, not a feature. Rewrites per-frame loops currently Compose-Canvas driven by the Rust sim (audit 3.6). Effort XL, 60-120+ person-days per game (EST). Not recommended.

## 3. Engine comparison (for a Compose app)

Numbers marked UNVERIFIED must be measured with APK Analyzer in a spike before any commitment.

| Option | Compose integration | Size | Notes / sources |
|---|---|---|---|
| Google Filament (`filament-android`, `gltfio-android`, `filament-utils-android`) | Renders to SurfaceView/TextureView/SurfaceTexture via `UiHelper`; embed with `AndroidExternalSurface` (SurfaceView-backed, system compositor, "can bypass the GPU") or `AndroidEmbeddedExternalSurface` (in-GPU composition, more power/memory) | `libfilament-jni.so` ~30.7 MB uncompressed in one treemap (version/ABI not stated; UNVERIFIED for current release). Per-APK delta not found. arm64-v8a is the primary optimized target; dropping armeabi-v7a and x86_64 removes most of it. | Backends: OpenGL ES 3.0+ and Vulkan 1.0 on Android; release notes say min API 21. README points to filament-android 1.79.0 (docs page shows 1.74.0): project active in 2026. https://github.com/google/filament/blob/main/RELEASE_NOTES.md , https://google.github.io/filament/dup/building.html , https://prideout.net/webtreemap/demo.html , https://developer.android.com/reference/kotlin/androidx/compose/foundation/AndroidExternalSurface.composable |
| SceneView (`io.github.sceneview:sceneview`) | Compose-first: `SceneView { }` with composable nodes (`ModelNode`, `CubeNode`, `LightNode`), `rememberEngine()`, `rememberModelLoader()`. README labels Android/Filament/Compose "Stable", shows 4.31.0 (Maven pages seen up to 4.21.2; true latest UNVERIFIED). Pulls filament-android + gltfio + filament-utils (1.71.x on 4.15-4.19 pages). | Filament plus a thin wrapper; no figure found (UNVERIFIED). | Community project, not Google: the risk is the wrapper. May 2026 changelog added 16 KB ELF alignment for Filament's prebuilt .so. https://github.com/sceneview/sceneview , https://sceneview.github.io/docs/changelog/ , https://central.sonatype.com/artifact/io.github.sceneview/sceneview/4.19.0 |
| AGSL RuntimeShader pseudo-3D | Native Compose, already in repo | ~0 KB code; assets only | API 33+ only. Best cost/benefit (Level 2). |
| Raw GLSurfaceView / OpenGL ES / Vulkan | `GLSurfaceView` in `AndroidView` has reported sync/threading issues in Compose; community moved to GL + `AndroidExternalSurface` | no library, but you write renderer, glTF loader, PBR, shadows | Only for a tiny custom lit cube; `drawDie3D` on Canvas is cheaper to maintain. https://slack-chats.kotlinlang.org/t/26866620/hello-we-are-rendering-a-glsurfaceview-in-an-androidview-to- |
| libGDX | Own Activity/view; not Compose-native | UNVERIFIED | Full game framework overlapping Compose. Reject. |
| jMonkeyEngine | Niche Android support | UNVERIFIED | Reject. |
| Godot as library | No source found for embedding in a Compose app (UNVERIFIED). Export docs: default APK carries ARMv7+ARMv8, custom export template shrinks it. | Community ~19 MB standard, ~13 MB trimmed (Godot 4, version unstated) | https://developer.android.com/games/engines/godot/godot-export , https://forum.godotengine.org/t/how-to-reduce-android-apk-size-reduce-startup-time-too/25779 . ENGINE_DECISION already flags GDScript risk. |
| Unity as a Library | Separate player Activity; heavy lifecycle | Empty IL2CPP arm64 project: 31.8 MB APK, libil2cpp.so 55.4 MB unpacked (forum report, standalone build, not UaaL) | https://discussions.unity.com/t/libil2cpp-so-is-too-big/1603857 . Reject (already rejected in ENGINE_DECISION). |
| Kotlin Multiplatform angle | Repo has `shared/` (desktop). Only Canvas-based 3D (the `drawDie3D` kind) can live in common code; Filament has no equivalent desktop/iOS path in the same API. | - | Put pure pose/projection math in the shared module. |

### Asset formats
- glTF/GLB: right container for Level 3 (Filament `gltfio`). Not needed for Levels 0-2.
- KTX2 + Basis Universal: ETC1S smallest/lower quality; UASTC higher quality (~8 bpp, Zstd by default). Single-asset example (DamagedHelmet): 5.03 MB source, 2.65 MB ETC1S, 13.44 MB UASTC. KTX is transcoded to the GPU format at load. https://www.khronos.org/developers/linkto/compressed-texture-transmission-ktx2-basis-universal , https://github.com/KhronosGroup/3D-Formats-Guidelines/blob/main/KTXArtistGuide.md . Whether Filament Android loads KTX2 directly: UNVERIFIED (its ktxreader historically handled KTX1; check before relying).
- Draco/meshopt: board-game meshes are low-poly (a pawn ~1-3k tris, ~30-100 KB raw, EST), so mesh compression saves under 1 MB across a set; skip. Textures dominate. gltfpack/meshopt compatibility with Filament: UNVERIFIED.

## 4. Cross-cutting evaluation

| Concern | Levels 1-2 (Compose/AGSL/sprites) | Level 3 Filament/SceneView |
|---|---|---|
| Per-frame, mid-range phone | Canvas class; no phone baseline exists (audit 02). Needs one first. | UNVERIFIED. Scales with resolution x lights x shadow passes. Use one light, no SSAO, render at ~0.75 scale (EST). |
| Tab S9 FE / Fold 5 | Pilot: 11.1 ms avg at 90 Hz on Tab S9 FE. Fold 5 inner display is 120 Hz (8.3 ms budget). | Large panels multiply fragment cost; use `surfaceSize` downscale. |
| Idle battery | ~0 if nothing loops; fix audit 3.1/3.4 first. | Must render on demand (only while a die rolls or piece moves). A continuous loop is the dominant risk and Filament does not stop it for you. Thermal under sustained load: UNVERIFIED. |
| Many instances | Free (draw calls) | N surfaces = N layers/contexts. Share one `Engine` (https://sceneview.github.io/docs/troubleshooting/); prefer one scene. Filament's samples show one scene with multiple Views. |
| Accessibility | Respect `LocalReducedMotion`; Canvas nodes already carry `semantics { contentDescription }` (`Die3DView`). | A SurfaceView has no semantics; TalkBack stays unaffected only if an overlay Compose node mirrors state with a `contentDescription`. Reduced Motion: snap to final pose, no tumble. |
| Compose interop | native | SurfaceView is a separate layer: z-order, rounded clipping, alpha, parent graphicsLayer transforms behave differently (External vs EmbeddedExternal trade-off). Surfaces are destroyed/recreated on resize/fold (https://source.android.com/docs/core/graphics/arch-sv-glsv). |
| Play policy | n/a | 16 KB page size: Filament's prebuilt .so must be aligned. The official guide I found says Play will block updates from Feb 1, 2027 if unsupported (third-party sources cite Nov 2025; re-check Play Console). https://developer.android.com/guide/practices/page-sizes . Your own Rust `.so` files need this regardless. |
| Maintenance | Own code only | Filament active but minors shift APIs; SceneView adds a second moving dependency; Blender -> glTF -> KTX2 is a new permanent pipeline. |

## 5. Testing without a device (honest limits)

- Roborazzi (Robolectric Native Graphics, `@GraphicsMode(NATIVE)`) screenshots Compose on the JVM; FAQ suggests API 28+; RNG has no CPU path for GPU-only effects (e.g. blur). Whether AGSL `RuntimeShader` (API 33+) renders under RNG: UNVERIFIED, no source says it works. https://github.com/takahirom/roborazzi/ , https://takahirom.github.io/roborazzi/faq.html , https://github.com/robolectric/robolectric/issues/8955
- Paparazzi (layoutlib) is documented as incompatible with Robolectric; behaviour with SurfaceView/GL/AGSL: UNVERIFIED.
- SurfaceView/TextureView/Filament: no JVM tool gives a real GL context. Treat pixels as untestable on the JVM. Testable: pose/projection math, style/fallback selection, render-on-demand gating, and semantics.
- Plain `Canvas`/`graphicsLayer` (Levels 1-2 non-shader parts) is Roborazzi/Compose-test friendly. `drawDie3D`'s math can be unit-tested for rotation/projection now.
- Owner rule: Claude does not install APKs. Device validation therefore means self-run kits plus Windows/web proxies; this favours sprite pipelines.
- Keep pose/physics in Rust sim or pure Kotlin so Level 3 adds only a thin view.

## 6. Recommendation per surface

| Surface | Level | Why |
|---|---|---|
| Dice | Level 2 now, no Level 3 | `drawDie3D` is already software 3D. Add d4/d8/d10/d12/d20 as convex-polyhedron data in the same projector (EST 3-5 days) plus shading. Real-time 3D is overkill for a 1 s animation. |
| Cards | Level 2 | `card3DFlip` ships; add tilt-to-light specular (AGSL, 33+) and baked shadow. |
| Board pieces (chess, checkers, backgammon, ludo) | Level 2 sprites + Level 1 lighting | Turntable 12-36 yaw frames (~0.5-1.3 MB per set) or normal-mapped sprites, tinted per side. Level 3 only if a spike proves free orbit is required. |
| Board surfaces (felt, wood) | Level 1 | `FELT_GRAIN`, `MESH_GRADIENT`, after fixing the perpetual-shader findings. |
| Whole-scene games (Air Hockey, Tower Defence, Bowling) | Defer Level 4; Level 2 look only | Rust-sim-to-Compose plumbing is already audit-flagged; an engine would reimplement HUD and input. Use table tilt via `graphicsLayer.rotationX` + baked shadows. |

## 7. Build vs defer verdict and budgets

| Level | Verdict | Effort (EST person-days) | Notes |
|---|---|---|---|
| 0 | Keep as default and fallback | 0 | |
| 1 | BUILD first | 4-6 | Fix audit 3.1/3.3/3.4; add `ArtStyle` + battery gate. |
| 2 | BUILD second | 10-15 code + 5-8 per art set | This is where "selectable styles" really lives. |
| 3 | DEFER; only after a 3-day measurement spike | 25-40 | Spike: one Filament/SceneView die + one piece in a throwaway module; measure APK delta (APK Analyzer), idle power, 16 KB alignment, Tab S9 FE frame times. Run only if the owner names a reason. |
| 4 | DEFER indefinitely | 60-120+ per game | Not recommended. |

Proposed acceptance budgets (EST):
- APK: Levels 1-2 <= 3 MB assets total. Level 3 <= 25 MB per ABI, arm64-only release, ideally in a Play Asset Delivery / feature pack so default installs pay nothing (feasibility UNVERIFIED for this bundle).
- Memory: <= 30 MB decoded art per screen on phone; lazy-load per piece type.
- Frames: Canvas screens p95 <= 11 ms on Tab S9 FE (existing baseline), <= 16.6 ms on a mid phone; no idle frame callbacks.
- Battery: zero wakeups while a board is idle (Levels 1-2); Level 3 loop stops within 200 ms of settling.

## 8. Risks
1. Levels 1-2 inherit existing audit defects; styles amplify them (shader/particle loops on 37 screens).
2. API 33+ gating hides the premium look on API 26-32; needs a defined sprite fallback tier.
3. Filament size/thermal numbers are unmeasured; do not promise them.
4. SceneView is a community wrapper over fast-moving Filament; version coupling.
5. No JVM way to verify 3D pixels; regressions rely on self-run kits.
6. Style matrix explosion (styles x games x devices x Reduced Motion x Card3D mode): cap at three global presets (Classic / Enhanced / Deluxe 2.5D), per-game override only where justified.
7. Asset provenance/licensing for Blender models must be settled before shipping.

## 9. Decisions the owner must make
1. Is there a "specific, named reason" to reopen real 3D, or is Level 2 "3D feel" sufficient? Recommendation: sufficient.
2. Approve Level 1 hygiene (audit 3.1/3.3/3.4) as a prerequisite.
3. Style model: global preset vs per-game; which is default.
4. Which art sets first (suggested: dice polyhedra, chess, checkers).
5. Asset pipeline: Blender-rendered sprites (suggested); who authors models; licensing for any stock.
6. Fund the 3-day Level 3 spike, and set the APK ceiling (suggested 25 MB per ABI, arm64-only, delivery pack)?
7. Minimum device tier for Deluxe: API 33+ only, or sprite fallback down to 26.
8. Reduced Motion scope: pose animation only (suggested) or lighting too.

## Sources used
- https://github.com/google/filament/blob/main/RELEASE_NOTES.md
- https://google.github.io/filament/dup/building.html
- https://google.github.io/filament/dup/android_samples.html
- https://prideout.net/webtreemap/demo.html
- https://github.com/sceneview/sceneview
- https://sceneview.github.io/docs/changelog/
- https://sceneview.github.io/docs/troubleshooting/
- https://central.sonatype.com/artifact/io.github.sceneview/sceneview/4.19.0
- https://developer.android.com/reference/kotlin/androidx/compose/foundation/AndroidExternalSurface.composable
- https://source.android.com/docs/core/graphics/arch-sv-glsv
- https://developer.android.com/develop/ui/views/graphics/agsl
- https://github.com/takahirom/roborazzi/ , https://takahirom.github.io/roborazzi/faq.html , https://github.com/robolectric/robolectric/issues/8955
- https://developer.android.com/games/engines/godot/godot-export , https://forum.godotengine.org/t/how-to-reduce-android-apk-size-reduce-startup-time-too/25779
- https://discussions.unity.com/t/libil2cpp-so-is-too-big/1603857
- https://developer.android.com/guide/practices/page-sizes
- https://www.khronos.org/developers/linkto/compressed-texture-transmission-ktx2-basis-universal , https://github.com/KhronosGroup/3D-Formats-Guidelines/blob/main/KTXArtistGuide.md
