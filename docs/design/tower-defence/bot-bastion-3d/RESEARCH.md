# Bot Bastion 3D: options research

Status: RESEARCH DRAFT, 2026-11-22. Nothing here is built. No Kotlin was edited, no Gradle build was run, nothing was installed on a device, nothing was committed.
Owner request: "lets look into a 3D version of this Bot Bastion as well."
Reads with: `RECOMMENDATION.md` (the path), `SPEC.md` (how a renderer plugs in), `ASSET-BIBLE.md` (the look), mockups `docs/design/canvas/B3*.dc.html`, the Bot Bastion set `docs/design/tower-defence/zacisa-mode/`, the style system `docs/design/style-packs/`, and the earlier ladder `docs/research/art-and-animation/R-A-3d-rendering.md`.

Confidence tags: VERIFIED = fetched or seen in this session (source linked); EST = my estimate, no source; UNVERIFIED = looked for, not confirmed. The searches in this session were thin on exact size and API numbers, so every size below that is not VERIFIED must be measured in a spike before anyone relies on it.

## 0. Context that frames the question

- The owner declined a Filament/SceneView true-3D bet twice ("Not this round", 2026-09-07 and 2026-09-12). The current style-system plan says 3D = software projection + baked sprite packs, with the fallback chain 3D -> 2.5D -> flat, original assets only. This document does not override that. It asks whether a genuine 3D Bot Bastion is worth reopening and shows the cheapest honest path to each look.
- Bot Bastion is a fixed-node tower defence: 12 to 22 build nodes per map, 1 to 6 lanes, a Core, at most 60 enemies, 40 bots and 120 projectiles on screen (`SPEC.md` section 13). The camera never needs to be free. That is the single most important fact for the 3D decision: **the game is a diorama, not a world.**
- The simulation is pure Kotlin, deterministic, fixed-step, JVM-tested. A renderer must be a pure consumer of its state and events (see `SPEC.md` section 2).
- Measured baseline in the repo (audit 02): a Compose Canvas stress pilot with 100 enemies, 30 towers and 60 projectiles ran at an average of 11.1 ms per frame on a Galaxy Tab S9 FE at 90 Hz, 3 of 599 frames over 16.7 ms. This is the only measured number we own. It says a Canvas renderer has headroom for a sprite-based pseudo-3D arena on a flagship; it says nothing about phones.
- Environment rules that bind the options: minSdk 26, targetSdk/compileSdk 34, JDK 17, three ABIs already shipped for the Rust libraries, Claude does not install APKs (device testing is self-run kits), no GPU context on the JVM test host.
- A triangle count check done for this study (see `renders/model_stats.json`): the five proof models cost 188 to 752 triangles each. A full Hearth Orbit scene (22 bots, 60 enemies, Core, 40 props) is about 35 000 triangles. **Geometry is not the cost of a 3D Bot Bastion. The costs are the engine library, the Compose interop, lifecycle, accessibility, testing and thermals.**

## 1. Option survey

### 1a. Software-projected pseudo-3D on Compose Canvas with baked sprites

What it is: a fixed orthographic tilt camera (for example 50 degrees elevation), a ground-plane projection written in pure Kotlin (`screen = project(world)`), sprites baked from 3D models (see 1c) placed by projection, painter's-algorithm depth sort by ground y, baked soft shadows, range rings drawn as projected ellipses on the terrain. The existing `drawDie3D` and `tablePerspectiveTilt` are the same family of technique (VERIFIED in R-A section 1).

| Criterion | Finding |
|---|---|
| APK size delta | 0 for code. Sprite pack roughly 4 to 6 MB lossy WebP for the whole game (EST, see `RECOMMENDATION.md` section 5). Base-bundle or install-time. |
| Min API | None beyond the app. Works on minSdk 26 with no AGSL; AGSL polish only on API 33+ (VERIFIED in R-A: RuntimeShader API 33+). |
| GPU / thermal | Same class as the measured Canvas pilot (11.1 ms on Tab S9 FE). Hardware-accelerated Canvas sprite draws are cheap; cost scales with overdraw and draw count, not triangles. No second render surface. Battery is the lowest of all options (EST). |
| Fold 5 and mid-range | Fold 5 (Snapdragon 8 Gen 2) has headroom; mid-range phones need sprite sizes by density and a 30 fps ambient layer (EST). No per-pixel lighting. |
| Compose interop | Native. It is Compose, so hit-testing, semantics (TalkBack), animation, Fold layouts and recomposition rules all work as for the 2D mode. |
| Accessibility | Best of all options. Nodes and bots are ordinary semantics nodes laid over projected rectangles. |
| License | Our own code. Sprites are our own renders. |
| Build complexity | Low to medium. New: projection and depth-sort library (pure Kotlin), sprite atlas loader, bake pipeline. |
| JVM testability | High. Projection, inverse projection (picking), depth sort and occlusion rules are pure math. Roborazzi-style screenshot tests are feasible for the non-shader paths (R-A section 5, UNVERIFIED for AGSL). |
| Limits | No free camera. Fixed yaw means each bot needs 8 to 16 baked facings. No real-time dynamic lights (glow is faked with additive sprites). Looks 3D, is a diorama. |

### 1b. Real-time 3D engines on Android

Common Compose interop facts (VERIFIED, `AndroidExternalSurface` reference): Compose provides `AndroidExternalSurface` (default z-order Behind the window, composited by the system compositor, "can bypass the GPU", recommended) and `AndroidEmbeddedExternalSurface` (composited inside the Compose GPU pass, more cost). The surface can be rendered from any thread. `surfaceSize` lets you render at a lower resolution than the layout and have it stretched, which is the cheapest thermal lever (render scale). Source: https://developer.android.com/reference/kotlin/androidx/compose/foundation/AndroidExternalSurface.composable . Consequence for us: Compose UI (HUD, build menu) draws above a `SurfaceView`-class surface only with `zOrder = OnTop` handling or by placing the surface behind a transparent Compose layer; content drawn by the engine is **not in the Compose semantics tree**, so TalkBack and one-switch access need a parallel invisible semantics layer (see `SPEC.md` section 6).

| Engine | License | Min API / toolchain | Size (EST unless marked) | Compose interop | Notes |
|---|---|---|---|---|---|
| **Filament** (Google) | Apache-2.0 (the README says "see LICENSE"; Apache-2.0 is the project's published license, UNVERIFIED in this fetch) | Min API 21 since 1.28 / 1.50.5 notes (VERIFIED, release notes); GLES 3.0+ and Vulkan 1.0 backends; current 1.81.0 (VERIFIED, README) | `libfilament-jni.so` ~30.7 MB uncompressed in one treemap, ABI and version not stated (VERIFIED as a single data point only, https://prideout.net/webtreemap/demo.html). The AAR bundles four ABIs unless filtered with `abiFilters` (VERIFIED, https://google.github.io/filament/dup/building.html). Per-ABI release size: UNVERIFIED, expect single digits to low tens of MB compressed (EST). | SurfaceView/TextureView via `UiHelper`, or `AndroidExternalSurface`. We would own a ~600 line wrapper. | PBR, cascaded and PCF/PCSS shadows, MSAA, FXAA, TAA, dynamic resolution (incl. FSR), SSAO, glTF 2.0 via gltfio with skinning, morph, meshopt, basisu (VERIFIED, README). No built-in toon style: we would write a custom material (`matc`). Instancing: not stated in README (UNVERIFIED), renderables can be instanced by entity; plan for batching by hand. |
| **SceneView** (`io.github.sceneview`) | Apache-2.0 (VERIFIED, README) | Library minSdk 24 (VERIFIED). **Latest 4.70.0 needs compileSdk 37, AGP 9.4+, Kotlin 2.3+, JVM 21; the README recommends 4.18.0 for AGP 8 / compileSdk 36 / Kotlin 2.2** (VERIFIED). We are on compileSdk 34 / JDK 17: even the 4.18.0 line needs a compileSdk bump (compileSdk can rise while targetSdk stays 34, but it is a build change). | Filament plus a thin wrapper; no figure (UNVERIFIED). Same engine cost as Filament. | Compose-first: `SceneView { }` with node composables, reactive scene graph, `rememberModelInstance` async loading (VERIFIED). Filament calls must be on the main thread (VERIFIED). Orbit camera and per-node gestures built in. Hit testing: `CollisionSystem`, `Ray`, `Box`, `Sphere` in `sceneview-core`, but no complete tap-to-node example in the README (VERIFIED). | One maintainer, ~1.4k stars, 136 open issues, no support contract (VERIFIED). Android marked Stable. The version-coupling risk named in R-A is confirmed and larger than expected. |
| **libGDX** | Apache-2.0 (EST from general knowledge; my fetch of the libGDX pages returned nothing usable) | UNVERIFIED in this session; historically Android API 14+/21+ range, GLES 2.0/3.0 | Native `.so` per ABI is small relative to Filament (EST 1 to 3 MB per ABI); Java/Kotlin jars add a few MB before shrinking (EST). | Embeds via `AndroidFragmentApplication` or a `GLSurfaceView`-based `AndroidApplication`; Compose interop needs an `AndroidView` wrapper (EST, UNVERIFIED). | Scene graph, `ModelBatch`, g3d, built-in shaders, glTF only via third-party loader (gdx-gltf). Basic lighting; no PBR/shadows out of the box. Mature, game-oriented, lowest runtime weight of the real engines. Not Kotlin-first. |
| **jMonkeyEngine** | New BSD (VERIFIED, Maven metadata) | `jme3-android` 3.9.0-stable published 2026-01-20; 3.8.0-stable 2025-05-01 (VERIFIED, Maven listing). The official Android wiki seen is the 3.4 page and warns Android 3D support is weaker than desktop (VERIFIED, https://wiki.jmonkeyengine.org/docs/3.4/sdk/android.html ). | UNVERIFIED. Java engine plus LWJGL-style abstraction; likely heavier on dex than libGDX (EST). | `AndroidHarness` activity or a GLSurfaceView; Compose interop is DIY (EST). | Desktop-first community, thin mobile ecosystem. Rated last of the engines for our case. |
| **Godot (embedded or separate)** | MIT (EST, general knowledge) | Android export documented; the stable docs page does not state min API, renderers or architectures (VERIFIED as absent). A forum thread reports Godot 4.2 shipping minSdk 21 (single user report). Embedding in an existing native app is not documented on the pages fetched (UNVERIFIED). | Default export templates carry multiple ABIs; Godot's own docs point to custom templates to cut size (VERIFIED, https://docs.godotengine.org/en/stable/tutorials/export/exporting_for_android.html ). Realistic: 20 to 40 MB per ABI for a standard template (EST). | Separate runtime with its own Activity; no clean Compose embedding. Our Compose HUD would be re-done in Godot, or Godot must be a standalone game inside the suite. | Best tooling for a 3D game and the strongest "make it look finished" workflow, but it is a second app inside our app: separate input, audio, lifecycle, save, accessibility and localisation. Not recommended as an embed. |
| **Raw OpenGL ES / Vulkan** | Own code | GLES 3.0+ (API 18+); Vulkan 1.0 on API 24+ devices (EST) | Smallest. | `GLSurfaceView` or `AndroidExternalSurface` | We would write the renderer, glTF loading, skinning, shadows and material system. 40 to 80 ideal days before anything beyond cubes (EST). Rejected unless Filament fails a spike. |

### Thermal and battery facts (all options with a live 3D surface)

- The Android Thermal API: `getThermalHeadroom` (API 30), NDK variant API 31; listener-based status exists earlier (the style-pack doc cites API 29 for status). Headroom ranges from 0.0 to 1.0 (severe throttling); call it no more than once per 10 s or it returns NaN; some devices report `THERMAL_STATUS_NONE` regardless, so validate against headroom (VERIFIED, https://developer.android.com/games/optimize/adpf/thermal ). Our minSdk 26 means the API must be capability-gated, with a frame-time fallback signal on API 26 to 29.
- Google's Netmarble case study reports ADPF-based throttling avoidance lowering average thermal headroom from 1.04 to 0.92 and stabilising a 40 to 56 fps swing (single vendor, device not stated; VERIFIED search snippet, https://developer.android.com/stories/games/netmarble-got-adpf ). Use as a pattern, not as a promise.
- Galaxy Z Fold5: Samsung markets cooling; independent sustained-load data for 3D games was not found in this session (UNVERIFIED). A search surfaced an Android Authority piece on foldable overheating in benchmarks; the Fold3 era review cited heat-management weakness (search snippet only). We have a Fold 5 owner device: **a 20-minute thermal soak test on it is the first deliverable of any Phase 2 spike.**
- The thermal cost that matters is GPU fill and post effects on a 3.9 Mpx Fold inner display (2176 x 1812) or a 2304 x 1440 tablet, not triangles. Render scale 0.67 to 0.75 via `surfaceSize` and dynamic resolution are the main levers (EST).

### 1c. Hybrid: Blender-baked sprites in a fixed-camera isometric view

What it is: author original low-poly models in Blender (glTF), render them with a fixed camera into transparent PNG sprites (8 yaw facings for bots and enemies, animation frames for walk/attack), pack into atlases, draw them through the 1a projection layer. The 3D model is the source asset; the shipped product is bitmaps.

| Criterion | Finding |
|---|---|
| Fits the existing plan | Exactly the style-pack "3D = baked sprite pack" rule and the R-A level 2 item 2 pipeline. Pipeline scripts already drafted in `docs/design/style-packs/scripts/` (`blender_render_turntable.py`, `optimize_glb.sh`, `asset_size_gate.py`, never executed). |
| What this study proved | Five original models were modelled, lit, rendered (EEVEE, headless Blender 5.1.2) and baked as 8 transparent yaw sprites each, plus a full Hearth Orbit scene in four sector looks and four camera views. See `renders/` and the B3 boards. Blender MCP was not reachable (connection to localhost:9876 refused), so the installed Blender 5.1 was run headless from the command line; nothing was installed. Scripts: `renders/make_models.py`, `renders/scene_pass.py`. |
| Cost per asset | Model 0.3 to 1.0 ideal day each for the low-poly style (EST), bake is scripted and automatic. |
| Size | Bots: base plus 16-facing head sprites. Enemies: 8 facings x 4 walk frames. About 440 sprites at 96 to 192 px; 4 to 6 MB lossy WebP (EST). |
| Quality | Real lighting baked in, so it looks as 3D as the models are. Cannot react to dynamic lights; fake with additive glow sprites and a tint pass. |
| Dynamic lighting trick | Bake a flat albedo plus a normal map and light in AGSL on API 33+ (R-A item 3); falls back to baked light on API 26 to 32. Optional. |
| Reuse | The same glTF models later feed Filament (option 1b) in Phase 2. No art is thrown away. |

### 1d. Asset pipeline for a real 3D path (also the source for 1c)

- **Models**: original low-poly, 150 to 800 triangles per unit (proof: 188 to 752), flat shading with a bevel. Author in Blender, export glTF/GLB (`export_apply`, +Y up). Proof GLBs: 19 to 57 KB each.
- **Optimisation**: `meshopt` compression (supported by gltfio, VERIFIED), KTX2/Basis textures (`KHR_texture_basisu`, VERIFIED in the README's extension list). The proof models need no textures at all (flat colour materials plus emissive accents): this is the cheapest possible art style for a real-time path and the easiest to keep consistent.
- **Instancing and batching**: a few hundred draw calls at most. 60 enemies of 7 types become 7 instanced draws if the engine supports it; otherwise 60 draws, still fine (EST). Merge static props per sector into one mesh.
- **LODs**: not needed for the camera range (fixed tilt, zoom 0.8x to 1.4x). One mesh per unit; a 60 percent decimated mesh for Low quality (EST).
- **Shaders**: toon-ish ramp plus rim light and a screen-space outline are the signature (see `ASSET-BIBLE.md`). In Filament this is a custom `.mat` (matc). In sprites it is baked (Blender Freestyle or inverted-hull outline).
- **Shadows**: Medium/High: one directional shadow map, 1024, PCF, single cascade over the arena. Low: blob shadow sprites. For sprites: baked blob (zero cost).
- **Particles**: sprite-quad particles, additive, capped by Motion/Quality (see budgets). No volumetrics.
- **Camera**: orthographic or low-FOV perspective at fixed tilt (50 degrees default), pinch zoom 0.8x to 1.4x, limited orbit of plus or minus 25 degrees ("peek"), reset button, plan view (top-down) for tabletop. Perspective (FOV 25 to 35 degrees, "tilt-shift diorama" look) is allowed in real 3D; the sprite path uses orthographic only.
- **Touch picking**: nodes are fixed points on a ground plane, so picking is ray vs plane plus nearest-node within a pick radius. No mesh raycasts. Bots add a ray vs bounding-cylinder test with depth ordering. All pure Kotlin math, JVM-testable (`SPEC.md` section 5).
- **60 fps budget** (EST, to be measured): see `RECOMMENDATION.md` section 5.

## 2. 3D-specific gameplay implications

| Issue | Why it matters in Bot Bastion | Mitigation (details in `SPEC.md`) |
|---|---|---|
| Camera occlusion | A tall bot or the Core hides lane tiles and nodes behind it when the camera is tilted. A Lobber tube is the tallest bot. | Default elevation 50 degrees or more; model height cap 1.2 x footprint; enemies get an outline pass visible through bots; prop placement rule keeps props outside lane and node screen footprints (a build-time test); Core drawn with a see-through fade for enemies behind it. |
| Readability of range rings | A circle becomes an ellipse with minor axis sin(elevation) x major; rings of 6 bots overlap into mush; the Lobber blind spot is an inner ring. | Rings drawn on the terrain plane as projected ellipses, only for the selected bot and the placement ghost; solid vs dashed per bot type (the existing accessibility rule, not colour alone); ring edge is a constant 2 dp stroke; label chip on the near edge. |
| Node picking | Nodes are 0.8 u wide, which is about 40 dp on a phone. Perspective foreshortening shrinks rear nodes. | Nearest-node picking with a pick radius of max(0.55 u, 28 dp); a 56 dp enlarged hit region by Voronoi cell; ghost preview and haptic tick on snap; magnifier is not needed with fixed nodes. |
| Lane clarity | In 3D the lanes can disappear into terrain detail and enemies bunch up behind bots. | Lane strip is the brightest, lowest-detail band; animated chevrons; entry gates glyph-coded; props pushed to the arena rim; enemy shadows and a rim light on enemies only. |
| Co-op on one device with a single camera | The spec offers tabletop posture with two players across the table, and up to four players. A tilted camera has a "front" and a "back"; the player opposite sees the diorama upside down. | One camera, one pick pipeline. In tabletop or 3 or more local players, force Plan view (elevation 90 degrees, orthographic, no tilt); sprites for plan view reuse the existing 2D top-down art (no new bake). Two players side by side on a tablet (same side of the table) can keep the tilt. |
| Depth of enemy-versus-bot overlap | Painter's algorithm sorts by ground y; flying Skiff and projectiles need height-aware sort keys. | Sort key = ground y plus a per-sprite height bias; flying units on their own layer above ground units. |
| Time to read at a glance on a phone | A 3D scene is busier than the flat arena. | Phone landscape defaults to Plan view with 3D ambience on the rim, or flat 2.5D (rule in `RECOMMENDATION.md` section 6). |

## 3. How the options compare for Bot Bastion

| | 1a sprites on Canvas (pseudo-3D) | 1c Blender-baked sprites | 1b Filament direct | 1b SceneView | 1b libGDX | 1b jME | 1b Godot | raw GL |
|---|---|---|---|---|---|---|---|---|
| Looks 3D | yes (diorama) | yes, best baked quality | true 3D, free camera | true 3D | true 3D | true 3D | true 3D | true 3D |
| APK delta | ~0 + 4 to 6 MB art | same as 1a | +15 to 30 MB (EST) | same + wrapper | +3 to 8 MB (EST) | UNVERIFIED | +20 to 40 MB (EST) | small |
| Compose + TalkBack | native | native | needs parallel semantics layer | same | same | same | no embedding | same |
| JVM-testable | high | high | low (math only) | low | low | low | very low | low |
| Fits toolchain (compileSdk 34, JDK 17) | yes | yes | yes (AAR) | **no, needs a compileSdk/AGP/Kotlin bump** | yes | yes | n/a | yes |
| Thermal | lowest | lowest | medium-high | medium-high | medium | medium | medium-high | tunable |
| Effort | M | M-L (art-heavy) | L-XL | L | L | L | XL | XL |

Reading the table: 1a plus 1c is one option (renderer plus asset source). Filament direct beats SceneView for us on toolchain fit (we would not bend our build to a community wrapper's AGP/Kotlin/compileSdk requirements), but both carry the same engine cost. libGDX is lighter but gives up PBR and glTF out of the box and is Java-first. Godot and raw GL are rejected for cost.

## 4. Findings that change earlier assumptions

1. **SceneView's current line does not build with our toolchain** (compileSdk 37 / AGP 9.4 / Kotlin 2.3 / JDK 21 for 4.70.0; even 4.18.0 needs compileSdk 36). R-A recorded only "version coupling risk"; this makes it concrete.
2. **Triangles are irrelevant for this game**; the 3D question is about engine weight, lifecycle, accessibility and testability.
3. **The thermal-headroom API is API 30**, so on our API 26 to 29 devices a 3D mode must use a frame-time signal instead of the platform signal.
4. **Proof outcome**: a consistent 5-model original set, scripted from ~300 lines of Blender Python, produced baked sprites and full-scene renders that already look finished at tablet size. The art direction is cheap to reproduce; this lowers the risk of Phase 1 considerably.

## Sources

- https://developer.android.com/reference/kotlin/androidx/compose/foundation/AndroidExternalSurface.composable (Compose surface interop, z-order, surfaceSize)
- https://developer.android.com/games/optimize/adpf/thermal (Thermal API levels, headroom, NaN rule, THERMAL_STATUS_NONE caveat)
- https://developer.android.com/stories/games/netmarble-got-adpf (ADPF case study, search snippet)
- https://github.com/google/filament and its RELEASE_NOTES.md (backends, features, glTF extensions, min API 21, version 1.81.0)
- https://google.github.io/filament/dup/building.html (ABIs, abiFilters)
- https://prideout.net/webtreemap/demo.html (libfilament-jni.so 30.7 MB uncompressed, single data point)
- https://github.com/sceneview/sceneview (license, minSdk, 4.70.0 toolchain requirements, Compose API, maturity)
- https://wiki.jmonkeyengine.org/docs/3.4/sdk/android.html , https://central.sonatype.com/artifact/org.jmonkeyengine/jme3-android/3.9.0-stable (jME Android status, license)
- https://docs.godotengine.org/en/stable/tutorials/export/exporting_for_android.html , https://developer.android.com/games/engines/godot/godot-export (Godot export and size advice)
- Repo: `docs/research/art-and-animation/R-A-3d-rendering.md`, `docs/design/style-packs/*`, `docs/design/tower-defence/*`, `docs/design/tower-defence/zacisa-mode/*`
- Unverified in this session (searched, not confirmed): libGDX minimum API and APK overhead, jME mobile size, Godot embedding, Fold 5 sustained-load figures, Filament per-ABI release size.
