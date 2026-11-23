# Bot Bastion 3D: recommendation

Status: DESIGN DRAFT, 2026-11-22. Planning estimates, not measurements. Nothing built or committed. Evidence: `RESEARCH.md`, the proof renders in `renders/`, boards `docs/design/canvas/B3*.dc.html`.

## 1. Recommendation in three sentences

1. **Build the 3D look first as baked-isometric 2.5D (Phase 1)**: original low-poly models authored in Blender, baked to sprites, drawn on the existing Compose Canvas through a small pure-Kotlin projection layer, selectable as the "3D" Look inside the style system. It reads as 3D on every device we own, costs about 4 to 6 MB, keeps TalkBack and JVM tests intact, and needs no engine.
2. **Treat real-time 3D (Phase 2) as an optional, flag-gated, downloadable pack** built on Filament directly (not SceneView), reusing the exact same glTF models, behind the same renderer interface, only after a 3-day spike on the Fold 5 and a mid-range phone clears numeric gates (section 4).
3. **Do not reopen the app-wide engine decision.** Phase 2, if ever funded, is scoped to Bot Bastion only and ships as an on-demand pack; the base app stays engine-free. This is consistent with the standing "not this round" answer: nothing in Phase 1 adds an engine, and Phase 2 is a separately approved, separately delivered pack.

## 2. Why this path

| Question | Answer |
|---|---|
| Is a genuine 3D Bot Bastion worth it? | Not as a first move. The game is a fixed-node diorama; a real 3D scene adds a free camera we do not need, at the price of an engine, a surface that TalkBack cannot see, no pixel testing on the JVM and a thermal budget we have not measured. The visible benefit over a good baked-isometric scene is mostly dynamic light, parallax on camera move and a "wow" factor. |
| What does Phase 1 already deliver? | Real lit 3D art (see `B3InGame`, `B3Sectors`, proof renders), tilt and zoom feel via projection, ghost placement with ray-picked nodes, ambient living-world layers and a Motion-level system. On the Tab S9 FE the Canvas path has measured headroom (11.1 ms at 100 enemies and 60 projectiles). |
| What does Phase 2 add? | Free orbit, dynamic lights (muzzle flashes, Core glow bleeding onto lanes), soft real shadows, parallax and depth of field, boss scale moments, 4 or more simultaneous point lights, camera moves in win/lose cutscenes. Nice, not necessary. |
| Why Filament over SceneView for Phase 2? | SceneView 4.70.0 requires compileSdk 37, AGP 9.4+, Kotlin 2.3+ and JVM 21; 4.18.0 still needs compileSdk 36. Bending the whole build for a one-maintainer wrapper is the wrong trade. Filament is Google's engine (Apache-2.0 project, min API 21, GLES 3.0/Vulkan, glTF, 1.81.0) and a ~600 line wrapper over `AndroidExternalSurface` is ours to own. |
| Why not libGDX / jME / Godot? | libGDX is lighter but Java-first, no PBR or glTF without add-ons; jME is desktop-first; Godot cannot embed cleanly in Compose and doubles input, audio, lifecycle and a11y. The spike may still compare libGDX if Filament's size misses the gate. |
| Consistency | Same decisions as `docs/design/style-packs/`: 3D -> 2.5D -> flat fallback, original assets only, device-free tests, on-demand pack delivery. |

## 3. Phasing

| Phase | What ships | Gate to start | Gate to finish |
|---|---|---|---|
| **P0 prerequisites** (not owned here) | GameChrome v2 and per-frame recomposition fixes already named as P0 in `zacisa-mode/SPEC.md` section 13 and `style-packs/SCALE.md`; Bot Bastion BB-1 arena renderer (2D) built by the other session | 2D arena renderer merged | Lively motion permitted by the perf fix |
| **P1 Baked-isometric 3D look (Look = "3D")** | Projection and picking library; sprite atlases for 5 bots, 7 enemies, 2 bosses, Core states, props for sector 1, then sectors 2 to 4; ghost placement with ray-pick, range rings on terrain, depth sort, baked shadows, camera tilt/zoom (Compose-only), Plan view; Calm/Standard/Lively ambient; settings and fallback chip | Owner approves the look (boards, proof renders) and the pack budget | Golden-image and math tests green; tablet, Fold and phone self-run kit pass; p95 frame within budget on Tab S9 FE |
| **P2-spike** (3 days, optional) | Throwaway module: Filament + our 5 GLBs + Hearth Orbit; measure APK delta per ABI, cold-start, memory, a 20 min Fold 5 thermal soak, a mid-range phone run, `AndroidExternalSurface` lifecycle on Fold fold/unfold | Owner funds the spike and names a reason (section 7) | Numeric gates in section 4 pass or the idea stops |
| **P2 True 3D pack (optional)** | On-demand pack (Play feature delivery): Filament renderer module implementing the same `ArenaRenderer` interface, toon + rim + outline material, real shadows, point lights, particles, skinned animation, Quality tiers, thermal governor, parallel semantics layer | P2-spike passed; owner approves delivery size | Device passes, a11y review, thermal soak on Fold 5, kid-profile rules verified |

Rules across phases: the renderer is chosen by `StylePack` capability detection and the Look setting; the simulation, event log, input mapping, HUD and tests are shared by every renderer (`SPEC.md` section 2). Nothing in P1 is thrown away by P2: models, animations, palettes, camera and picking maths, settings and tests carry over.

## 4. Gates for Phase 2 (numeric, to be measured in the spike)

| Gate | Pass condition |
|---|---|
| Pack size | Feature pack (native lib for arm64-v8a, models, textures) <= 30 MB compressed download; base APK delta 0 MB. If `libfilament-jni` + gltfio + utils alone exceed 14 MB compressed for arm64, re-run the comparison with libGDX before continuing (EST threshold) |
| Frame time, Fold 5 inner (2176 x 1812), Quality High | p95 <= 11.1 ms (90 Hz) at render scale 0.75, 60 enemies + 22 bots + 120 projectiles + Lively ambient |
| Frame time, reference mid-range phone (Adreno 610 or Mali-G57 class, 6 GB), Quality Medium | p95 <= 16.7 ms at render scale 0.6 to 0.67 |
| Thermal | 20 min soak on Fold 5 at Medium: thermal headroom stays < 0.85 (the "light throttling" heuristic in the Android guide) or the governor demotes without a visible hitch; no demotion below Medium inside 20 min |
| Memory | Peak graphics+native memory of the 3D scene <= 220 MB on High (EST), <= 150 MB on Medium; no leak across 10 fold/unfold and 10 app switches |
| Lifecycle | Surface destroyed and recreated on fold, rotate, split-screen, multi-window: scene resumes within 500 ms without state loss |
| Battery | 3D Medium no more than 1.35 x the energy per 10 min of the Phase 1 sprite renderer on the same device (EST target, needs a power rail or battery stats method) |
| Accessibility | Every node, bot and enemy summary reachable by TalkBack and one-switch with the parallel semantics layer; contrast checks pass in all four sector looks |
| Tests | All sim, projection, picking, fallback and settings tests run on the JVM with no GL; golden images generated by a headless Filament or Blender path are optional, never required for CI |

If any gate fails, the decision is "stay at Phase 1". That is a successful outcome of the spike.

## 5. Estimate table (ideal engineer-days; planning only, +/- 30 percent unless noted)

| Work | Phase | Days | Confidence |
|---|---|---|---|
| Blender bake pipeline hardening (scripts exist as proof, add manifest, packer, WebP, size gate) | P1 | 4 | high |
| Projection, inverse projection, depth sort, picking, occlusion rules (pure Kotlin) + tests | P1 | 5 | high |
| Sprite arena renderer: layers, cached terrain, sprite batches, shadows, rings, ghost, events to effects | P1 | 9 | medium |
| Models and bakes: 5 bots (+5 level looks as overlays), 7 enemies, 2 bosses, Core x4 states, projectiles/FX | P1 | 14 | medium |
| Sector art: 4 terrains, 12 props per sector, ambient loops | P1 | 12 | medium |
| Camera tilt/zoom/peek, Plan view for tabletop, settings (Look/Quality/Motion) + fallback chip | P1 | 5 | high |
| Accessibility semantics layer and colour-blind pass | P1 | 3 | high |
| Tests: golden images (Roborazzi style), math, fallback, event-to-effect | P1 | 5 | medium |
| Device passes and fixes (tablet, Fold, phone; self-run kit) + 20 percent buffer | P1 | 10 | low |
| **Phase 1 total** | | **67** | |
| Spike (Filament + 5 GLBs, measurements) | P2-spike | 3 | high |
| Filament wrapper, lifecycle, `AndroidExternalSurface`, glTF load, instancing | P2 | 12 | medium |
| Custom toon/rim/outline materials (matc), lights, shadows | P2 | 8 | low |
| Skinned animation for rigs (idle/attack/hit/die) and FX particles | P2 | 10 | low |
| Camera, picking bridge, parallel semantics, input parity | P2 | 6 | medium |
| Quality tiers, thermal governor, render scale, fallback and pack delivery | P2 | 8 | low |
| Texture/mesh optimisation (meshopt, KTX2), asset gate | P2 | 5 | medium |
| Tests, device passes, thermal soak, fixes + 25 percent buffer | P2 | 20 | low |
| **Phase 2 total (excl. spike)** | | **69** | |

Phase 1 is roughly 13 weeks solo or 7 with two (one on code, one on art). Phase 2 is roughly 14 weeks solo. These sit on top of the 84 ideal days in `zacisa-mode/SCOPE-SCALE-SCRIPT.md` for the 2D mode (which Phase 1 reuses: engine, HUD, menus, balance, audio are shared). Phase 1 art days overlap with the 2D "arena renderer and sprites" line (6 days) only for the 2D side.

## 6. Budgets

### APK / pack size (compressed download; EST)

| Item | Phase | Budget |
|---|---|---|
| Projection/picking/renderer code | P1 | < 0.3 MB (in base) |
| 3D baked pack: bots (5 types x base + 16 head facings), enemies (7 x 8 facings x 4 frames), bosses (2 x 8 x 6), Core (4 states), projectiles/FX | P1 | 2.0 to 2.6 MB |
| Sector terrains, props, backdrops (4 sectors) | P1 | 1.6 to 2.4 MB |
| Look pack total | P1 | **<= 6 MB**, install-time or fast-follow; flat 2D and 2.5D stay <= 2 MB (existing budget) |
| Filament native libs (arm64-v8a only) | P2 | 8 to 14 MB (EST, spike measures; one treemap showed 30.7 MB uncompressed for an unspecified build) |
| GLB models (meshopt) + KTX2 textures + materials | P2 | 3 to 8 MB |
| 3D feature pack total | P2 | **<= 30 MB**, on-demand, Wi-Fi prompt, parent-approved on kid profiles |
| Base APK delta | both | 0 MB for P2 (engine only in the pack); +0.3 MB code for P1 |

The proof models (19 to 57 KB per GLB, 188 to 752 triangles) show the model payload is negligible next to the engine.

### Memory (decoded; EST)

| Tier | P1 sprites alive | P2 3D scene |
|---|---|---|
| High (tablet, Fold inner, >= 8 GB) | <= 28 MB (192 px bots, 128 px enemies, only the map's roster loaded) | <= 220 MB |
| Medium (>= 4 GB) | <= 18 MB (128/96 px) | <= 150 MB |
| Low (< 3 GB or low-RAM flag) | 3D look unavailable; 2.5D <= 8 MB | unavailable |

### Frame time and battery (see gates in section 4)

| Tier | P1 sprite renderer | P2 3D |
|---|---|---|
| Battery saver | 30 fps ambient, no shadows, Plan view allowed | not offered |
| Balanced (Medium) | 60 fps, soft shadows, 1 ambient layer set | 60 fps, scale 0.6 to 0.67, no real shadows, baked blob |
| High | match display up to 90 fps, full ambient | 60 to 90 fps, scale 0.75, one shadow map, point lights |
| Idle build phase | redraw only on change plus 30 fps ambient (Calm: 15) | cap 30 fps, no post |

Visible triangle budget (P2): 60k High, 35k Medium (the measured Hearth Orbit scene is about 35k including 40 props). Draw calls: <= 250. Particles: 600 High, 250 Medium, 80 Low by Motion level.

Battery (EST): P1 is the baseline; P2 Medium is targeted at <= 1.35 x P1 energy per 10 minutes; P2 High at <= 1.7 x. Both unmeasured; the spike must measure with battery stats on the Fold 5.

## 7. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Sprite pack grows (8 facings x many frames) | pack > 6 MB, memory > budget | Head sprites separate from bases; 96 px on phones; roster-only loading; size gate in CI (`asset_size_gate.py`) |
| Baked look looks flat next to real 3D | owner disappointment | Proof shows the baked look; ship Plan/Tilt toggle and a 3D pack only if Phase 2 gates pass |
| Engine adds a permanent maintenance cost (Filament updates, 16 KB page alignment, GLES driver bugs on mid-range) | high | Pack-only, pinned version, feature flag, remote kill-switch to fall back to P1 |
| TalkBack cannot see the 3D surface | blocks a11y gate | Parallel invisible semantics layer built in P1 and reused in P2 |
| Thermal throttling on Fold 5 (single flagship with a thin chassis) | p95 frames, hot device | Governor with headroom polling (API 30+) and frame-time fallback; render-scale first, effects second, fps last; 20 min soak gate |
| No JVM pixel tests for P2 | regressions slip | Keep all logic in pure Kotlin; golden images for P1 only; P2 verified with self-run kits and an optional offline Filament-on-desktop golden job (not required) |
| Art consistency across 45 or more models | uneven quality | One Blender template, shared material set and lighting rig (`make_models.py`); asset bible palette lock |
| Toolchain drift if SceneView is chosen later | blocks builds | Decision recorded: Filament direct; SceneView only if the owner raises compileSdk/AGP/Kotlin for other reasons |

## 8. Fallback rules

Chain: **3D (P2 real) -> 3D (P1 baked) -> 2.5D (flat sprites + tilt) -> Flat 2D (existing vector arena)**. Each step is automatic and visible as a "fallback chip" in the HUD ("Using 3D Lite, battery saver").

| Trigger | Action |
|---|---|
| Pack missing, hash mismatch or not yet downloaded | Use the next lower Look; never block play |
| Quality = Auto and 2 bad frame windows (p95 > budget) | Demote one tier (shadow -> blob -> no ambient -> fewer particles -> render scale -> next Look); never promote mid-run |
| Thermal headroom >= 0.85 (API 30+) or two consecutive slow windows on API 26 to 29 | Demote one tier; toast "Cooling down: effects reduced" |
| Battery saver on or battery < 15 percent unplugged | Plan view, 30 fps ambient, no particles |
| Reduced Motion (OS) | Always wins: no shake, no parallax, no trails, single frame flashes; camera tilt and zoom are user actions and stay |
| Low-RAM device, or < 3 GB | 3D look not offered; 2.5D or Flat |
| Phone portrait | rotate prompt as in the 2D mode |
| Co-op tabletop, or 3 or 4 local players | Plan view forced (see `RESEARCH.md` section 2) |
| Surface lost or GL error (P2) | Fall to P1 sprites in the same frame; log a `StyleHealth` event |

## 9. Kids and low-end device rules

- Kid profiles default to the Look the parent last chose, but the 3D pack download requires the existing parent gate. Cadet tier, calm Motion, gentle copy are unchanged. No blood or gore in any look: Drifters pop into sparks and rock dust (see `ASSET-BIBLE.md`).
- Kid profile shows no thermal toast text; it silently demotes quality and shows a friendly "Eco mode" chip.
- Low-end devices never see 3D (RAM gate) and never receive the 3D pack download prompt. They keep Flat and 2.5D (and Plan view) at the existing budgets.
- Touch targets stay 56 dp (nodes use the enlarged Voronoi region). Text scales to 200 percent; the HUD is Compose, not part of the 3D surface.
- Photosensitivity: no flashes above 3 per second; Nova Pulse is a single-frame flash plus a ring in all Looks.

## 10. What the owner must decide

1. **Reopen real 3D at all?** Recommended answer: not now; approve Phase 1 (the baked-isometric "3D" Look) and decide on Phase 2 after Phase 1 ships. A named reason that would justify Phase 2: dynamic light and free orbit as a premium selling point of Bot Bastion.
2. Approve the Phase 1 look (boards `B3InGame`, `B3Sectors`, `B3Assets`; renders in `renders/`), including the palette and silhouettes in `ASSET-BIBLE.md`.
3. Approve a 6 MB Look pack for Bot Bastion (install-time vs fast-follow) and the 30 MB ceiling for a later 3D pack.
4. Fund (or decline) the 3-day Phase 2 spike, including a 20 minute thermal soak on the owner's Fold 5. The owner does the soak or runs the self-run kit; Claude does not install APKs.
5. Default Look for new installs: Flat 2D, 2.5D or 3D Lite (recommended: 2.5D default; 3D opt-in).
6. Tabletop co-op rule: force Plan view for 3 or more local players (recommended) or allow tilt with a rotate-camera button.
7. Who authors future models and enemies: keep the Blender-script pipeline (recommended) or commission an artist. All assets stay original; no stock packs without a licence review.
8. Whether a Bot Bastion sibling Look should ever extend to other Tower Defence themes (Castle, Reef...) once the pipeline exists (recommended: later).
