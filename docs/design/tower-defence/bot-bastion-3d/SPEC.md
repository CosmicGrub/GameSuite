# Bot Bastion 3D: renderer spec

Status: DESIGN DRAFT, 2026-11-22. Nothing built. Applies to both renderers described in `RECOMMENDATION.md`: R1 = baked-isometric sprite renderer on Compose Canvas (Phase 1), R2 = Filament renderer in an optional pack (Phase 2). Everything outside the renderer (sim, HUD, menus, balance, audio, stats) is the Bot Bastion engine and is unchanged.
Reads with `docs/design/tower-defence/zacisa-mode/SPEC.md`, `MAPS.md`, `BALANCE.md`; style system `docs/design/style-packs/ARCHITECTURE.md`.
Mockups: `B3InGame`, `B3Camera`, `B3Assets`, `B3Sectors`, `B3Motion`, `B3Settings`, `B3TabletPhone`, `B3Overview` (`docs/design/canvas/`). Proof art: `renders/`.

## 1. Scope

In: how a 3D renderer plugs into the deterministic engine; camera and input; asset list with budgets; animations; lighting and time of day; settings; tests.
Out: engine rules (unchanged), HUD layout (Compose, unchanged), the Filament build setup (decided only if the Phase 2 spike passes).

## 2. How the renderer plugs into the engine (no sim changes)

```
TowerDefenceGame (sim, fixed step, pure Kotlin)
   |  state: ArenaSnapshot (immutable per tick)      events: ArenaEvent (append-only log, seq)
   v
ArenaRenderer (interface)  <-- chosen by StylePack: FlatRenderer | SpriteIsoRenderer (R1) | FilamentRenderer (R2)
   |  consumes: snapshot(t0), snapshot(t1), alpha, events since lastSeq, camera, quality, motion
   v
pixels                              input path:  touch -> CameraProjection.pick() -> PickResult -> existing intents (placeBot(nodeId), select(botId), ...)
```

Rules:

1. **The renderer never mutates sim state and never feeds back into it.** Pause, speed (1x to 3x), Reduced Motion and lifecycle pause stay in the sim and HUD. A renderer that is paused or detached changes nothing about the match.
2. **Interpolation**: the renderer draws at display rate between two consecutive sim snapshots using `alpha = accumulator / dt`. Positions of enemies, projectiles and the Skiff are interpolated; discrete things (placement, level) pop on the tick they appear.
3. **Event log first, snapshot diff as the fallback.** The Bot Bastion engine already plans "projectile and death-event plumbing" (`zacisa-mode/SPEC.md` section 4.2); the existing code has only `TowerDefenceEnemyDeathEvent(seq, position)` (one last-event slot). If the sim exposes a list of events with a monotonically increasing `seq`, the renderer keeps `lastSeenSeq`. If it does not, a pure-Kotlin `SnapshotDiffer(prev, next) -> List<ArenaEvent>` derives the same events from snapshot pairs (new bot id = `BotPlaced`, hp drop = `EnemyHit`, id gone with hp <= 0 = `EnemyKilled`, id gone at the Core = `EnemyLeaked`, level change = `BotUpgraded`, new projectile id = `ShotFired`). The differ is JVM-testable and means **zero sim changes are required**.
4. **Determinism of visuals**: visual randomness (particle jitter, rock tumble, sparks) uses a `VisualRng` seeded from `(matchSeed, event.seq)`, so the same match renders the same effects. Visual RNG never touches the sim RNG.
5. **Frame ownership**: R1 draws in a Compose `Canvas` (single frame clock, `withFrameNanos`, no composition-scope reads of animated values; static terrain cached with `drawWithCache`). R2 owns a Filament render loop driven by `Choreographer`, with all Filament calls on one thread; it reads the same snapshot object by a volatile reference.

### 2.1 ArenaSnapshot (read-only view the renderer needs)

| Field | Content |
|---|---|
| `tick`, `dt`, `speed` | fixed-step clock |
| `map` | `MapDef` id, lanes (world polylines), nodes (id, x, y), voids, core position, sector id |
| `bots` | id, type (`PICKET, LOBBER, HAZE, SKIFF, TENDER`), nodeId or rim position, level 1..5, ownerId, aim angle, target id, cooldown fraction, selected flag |
| `enemies` | id, type, lane, position (x, y), heading, hp fraction, status flags (slowed, frozen), isBoss, flying |
| `projectiles` | id, kind, from, to or position, progress |
| `core` | hp fraction, shield fraction, state band (intact, 50, 25, fallen) |
| `wave` | index, phase (build, running, boss), banner |
| `players` | ids, colours, pilot Skiff positions |
| `ui` | selectedNodeId, ghostType, ghostNodeId, showRangeFor (botId or ghost) |

World units are the engine's arena units (16 x 9 u). Renderers convert; the sim stays 2D.

### 2.2 ArenaEvent (render triggers)

`BotPlaced(botId, nodeId, type)`, `BotUpgraded(botId, level)`, `BotSold(botId)`, `ShotFired(botId, kind, targetId?)`, `ProjectileLanded(id, kind, pos)`, `EnemySpawned(id, type, lane)`, `EnemyHit(id, dmg, crit?)`, `EnemyStatus(id, flag)`, `EnemyKilled(id, pos, type)`, `EnemyLeaked(id)`, `CoreHit(amount, shieldAbsorbed)`, `ShieldBroke`, `ShieldGained`, `WaveStarted(i)`, `WaveCleared(i)`, `BossArrived(id)`, `SpecialUsed(kind)`, `NovaPulse`, `CourierEscaped`, `PilotDown(playerId)`, `MatchWon`, `MatchLost`.
Each event maps to (a) a visual cue in the renderer, (b) the existing sound id in `zacisa-mode/SPEC.md` section 10, (c) the existing haptic. Audio and haptics stay in the shared event handler; the renderer only draws.

## 3. Camera and projection

### 3.1 Default view

Fixed tilt camera: elevation e = 50 degrees (range 40 to 70), yaw 0 (arena long axis horizontal), orthographic for R1; R2 may use perspective with vertical FOV 28 degrees and the same framing. Zoom 1.0 fits the whole 16 x 9 arena plus a 0.6 u margin in the arena viewport.

R1 projection (ground-plane y away from the viewer, z up, `s` = pixels per arena unit at zoom 1, `k` = zoom):

```
sx = cx + s*k*(x - tx)
sy = cy - s*k*((y - ty)*sin(e) + z*cos(e))
inverse on plane z = h:  x = tx + (sx - cx)/(s*k)
                         y = ty + ((cy - sy)/(s*k) - h*cos(e)) / sin(e)
```
Orbit yaw psi rotates `(x - tx, y - ty)` by psi before projection. Orbit is limited to +/- 25 degrees ("peek"), springs back on release or via a reset button.

Circles on the ground project to ellipses with horizontal radius `r*s*k` and vertical radius `r*s*k*sin(e)`. At e = 50 degrees the ratio is 0.77, at 70 degrees 0.94, at 90 degrees 1 (Plan view).

### 3.2 Camera modes (mock: `B3Camera`)

| Mode | e | Yaw | Use |
|---|---|---|---|
| Default tilt | 50 | 0 | Standard play |
| Zoom | 50 | 0 | pinch 0.8x to 1.4x, double-tap toggles 1.0 / 1.4 centred on the tapped node, pan limited to arena bounds + margin |
| Peek orbit | 50 | +/- 25 | two-finger twist while placing, returns on release (R2 only free; R1 re-projects sprites by rotating facing index, so it is limited to +/- 22.5 degrees or one facing step) |
| Plan view | 90 | 0 | tabletop, 3 or more local players, battery saver, phone landscape default, accessibility "simple view"; uses the existing 2D top-down art in R1 |
| Cinematic | 35 to 60 | 0 to 15 | boss intro, win/lose; Reduced Motion disables the move, uses a cut |

Camera state is renderer-local view state, saved per profile as a preference (zoom, last mode), never in the match.

### 3.3 Input mapping (all pure Kotlin)

| Input | Result |
|---|---|
| Tap on empty node | select node, open build menu; ghost preview of the armed bot; haptic tick on snap |
| Tap on a bot | select bot, show its range ring, open upgrade panel |
| Drag from weapon card onto an empty node | ghost follows the finger snapped to the nearest valid node within the pick radius, ring shows range, release places |
| Two-finger pinch / twist | zoom / peek (disabled while a drag is armed) |
| Tap on lane or void | nothing; message "Bots go on the hex pads" (TalkBack: polite announce) |
| Gamepad | D-pad cycles nodes in lane order; A selects; matches the one-switch order |

Picking algorithm (`CameraProjection.pick(sx, sy, mode)`):
1. Unproject to the ground plane at z = 0, get world point P.
2. Candidate bots: ray against each bot's bounding cylinder (`r = 0.45 u`, `h = modelHeight`), pick the nearest in depth. Bots win over nodes.
3. Else nearest free or occupied node by ground distance, accepted if `d <= max(0.55 u, 28 dp / (s*k))`. Ties broken by screen-space distance, then node id (stable).
4. Else `None`.
A 56 dp Voronoi region replaces per-node 56 dp boxes where nodes are closer than 56 dp (nodes are 0.72 u apart, which is 40 dp on a phone). All of this is deterministic and JVM-testable.

### 3.4 Readability overlays (mock: `B3Camera`)

- **Range ring**: only for the selected bot, the placement ghost and (optionally) all bots while the "Show ranges" chip is held. Ellipse on the ground plane, 2 dp constant stroke, 14 percent fill; solid for single-target, dashed for splash (Lobber) with an inner blind-spot ring, dotted for chip fire (Haze), double line for Tender. Label chip "r 3.2 u" on the near edge.
- **Occlusion helpers**: enemies behind a bot or the Core draw an outline silhouette at 45 percent alpha (R1: a pre-baked outline sprite; R2: second pass with depth test off and a stencil rim). Prop placement is validated at build time: no prop's screen footprint may overlap a lane strip or node pad dilated by 0.25 u (JVM test, section 9).
- **Height caps**: bot model height <= 1.2 x footprint width, Core <= 2.2 x its footprint, so a bot hides at most one lane tile behind it at e = 50 degrees.
- **Lane clarity**: lane surface is the lowest-detail, highest-contrast band (contrast ratio >= 3:1 against the sector ground), animated chevrons at 0.5 Hz (static in Reduced Motion), entry gate glyph in addition to colour.
- **Colour is never the only signal**: ring style, silhouette and glyph carry meaning, per the Bot Bastion accessibility rules.

## 4. Asset list with budgets

Triangle numbers are visible-instance budgets per model; proof values in brackets are measured on the original Blender models in `renders/model_stats.json`. Texture budget: R1 sprites 96 to 192 px, WebP lossy with alpha; R2 textures are optional (flat colour materials) and 256 px KTX2 where used.

| Asset | Count | Tri budget (proof) | Sprite set (R1) | Notes |
|---|---|---|---|---|
| Picket | 1 base + 5 level looks | <= 450 (288) | base 1 + head x 16 facings, 5 level overlays (pips, plating) | Hex base, dome, barrel, 6 rim LEDs |
| Lobber | 1 + 5 | <= 500 (296) | base 1 + tube x 16 facings | 55 degree mortar tube |
| Haze | 1 + 5 | <= 450 | base 1 + fan head x 16 | Three arc fins, wide dish |
| Skiff (flying) | 1 + 5 | <= 300 (188) | 16 facings, hover shadow separate | Hover bob in code |
| Tender | 1 + 5 | <= 450 | base 1 + rotor x 8 | Petal ring, pulse |
| Aegis generator | 1 | <= 400 | 1 + 3 shield-state glows | Placed beside the Core |
| Core | 4 states | <= 1200 (752) | 4 states | intact, 50, 25, fallen; orb glow additive sprite |
| Drifter | 1 | <= 350 (220) | 8 facings x 4 tumble frames | Rock-ship with ember eyes |
| Darter | 1 | <= 250 | 8 x 4 | Shard shape, thruster flame |
| Plater | 1 | <= 400 | 8 x 4 | Plated rock |
| Splitter + Bit | 2 | <= 400 / <= 150 | 8 x 4 each | Cracks glow before splitting |
| Courier | 1 | <= 300 | 8 x 4 | Gold-trimmed |
| Behemoth (boss) | 1 | <= 1800 | 8 x 6 | Scale 3x, slow-resist plating |
| Boss 2 (waves 20, 30, 40 skin variants) | 3 | <= 1800 | recolour/overlay of Behemoth | |
| Projectiles | 5 kinds | <= 40 | sprites + additive glow | pew bolt, shell, hiss spray, skiff bolt, freeze pulse |
| Props per sector | 12 per sector, 48 total | <= 250 each | 1 sprite each (static) | rocks, crystals, vents, lanterns, derelicts |
| Terrain per map | 12 | plate + lane + nodes | 1 baked background per map (R1), 1 mesh set (R2) | Hex pads instanced |
| Backdrops / parallax | 4 sectors x 3 layers | n/a | parallax strips | cheap |
| FX | explosion, pop, dust, spark, freeze, nova ring, repair, courier gift | sprite-based | 8 to 12 frame strips, 64 to 128 px | additive |

Totals R1: about 440 sprites, 4 to 6 MB WebP (EST). R2 (visible, one map): ~35 000 triangles measured in the proof scene (22 bots, 60 enemies and Core, 40 props); 60k High cap, 35k Medium cap.

## 5. Animation list

R1 plays pre-baked frame strips; R2 plays glTF clips. Both use the same state names so the sim-facing animator is shared.

| Unit | Clips | Frames (R1) | Notes |
|---|---|---|---|
| Bots | `build` (rise + settle 0.4 s), `idle` (LED pulse 0.5 Hz), `fire` (recoil 0.12 s, muzzle flash), `hit` (not damageable; none), `upgrade` (pulse ring + glow 0.5 s), `sell` (collapse 0.3 s), `select` (ring in) | strip 8 | Aim handled by facing, not a clip |
| Skiff | `hover` loop, `bank` L/R, `fire`, `down`, `respawn` | 6 | |
| Enemies | `move` (tumble/wobble 4 frames), `hit` (flash 2 frames), `die` (pop into sparks 6 frames, no blood), `leak` (flash into Core) | 4 + 2 + 6 | Splitter crack tell |
| Boss | `move`, `roar` (arrival banner sync), `hit`, `die` (staged 10 frames) | 6 + 10 | |
| Core | `idle` (orb breathe 0.25 Hz), `hit` (flinch + shake), `shield` (dome ripple), `critical` (flicker), `fallen` | 8 | shake off in Reduced Motion |
| Projectiles | `fly`, `impact` | 4 | |
| Ambient living world (per sector) | see below | n/a | Calm 30 fps, Standard 60, Lively 60 |

Ambient per sector (original; see `ASSET-BIBLE.md`):

| Sector | Ambient loops |
|---|---|
| Cradle Reach | drifting dust motes, station beacon pulse 0.5 Hz, a far shuttle crossing every 40 s, rim lights chasing |
| Glass Belt | crystal shimmer, slow glint sweeps across shards, floating ice shards, auroral bands |
| Ember Deep | vent glow breathing 0.3 Hz, embers rising, heat-haze band, magma flicker |
| Hush Verge | lantern bob and sway, mist drift 6 px/s, soft violet motes, a quiet pulsing beacon |

Calm halves counts and holds loops at 30 fps; Lively adds 50 percent and light flicker. Reduced Motion freezes ambient. Gameplay motion (enemies, projectiles) is never frozen.

## 6. Accessibility (3D-specific)

- **Semantics layer**: a Compose tree of transparent nodes positioned by the projection over the render surface, one node per map node and per bot and a live-region summary. TalkBack announcements follow `zacisa-mode/SPEC.md` section 12 ("Node 7 of 18, empty, covers lane 2"). In R2 the surface contributes no semantics, so this layer is mandatory, not optional.
- Order for TalkBack and one-switch: nodes in lane order, then bots, then Core, then specials. Focus ring drawn in the scene by the renderer from the same `focusedId`.
- Colour-blind: the existing CVD filter tooling checks all four sector looks; ring style and silhouettes carry meaning.
- Contrast: lane strip vs ground >= 3:1; bot silhouette vs ground >= 3:1; enemy rim light >= 3:1 against bots.
- Reduced Motion removes shake, trails, parallax, camera moves; Nova stays a single-frame flash.
- Large text: HUD is Compose, scales to 200 percent, the arena viewport shrinks, zoom 1.0 re-fits.

## 7. Lighting and time of day

| Light | R1 (baked + tint) | R2 (Filament) |
|---|---|---|
| Key (warm sun/station lamp), rim (cool), fill | Baked into sprites with the sector's key/rim colours; light direction fixed from upper-left | Directional lights, same colours; one shadow map on High |
| Glow (muzzle, Core orb, ember vents) | Additive glow sprites, 3 sizes | Point lights (max 4 active on High, 2 Medium), emissive materials + bloom-lite |
| Time of day (build phase to late waves) | 3 colour grades (dusk, night, alarm) via a `ColorFilter`/AGSL tint on the cached terrain and sprite layer; boss waves use alarm grade | Light colour/intensity ramp, same grades |
| Shadows | Baked blob under each sprite (ellipse, 35 percent) | Soft shadow map High, blob Medium |
| Sector looks | Palettes in `ASSET-BIBLE.md`: Cradle (blue-gold), Glass (ice cyan), Ember (red-amber), Hush (violet-rose) | same |

All lighting is cosmetic. Nothing in gameplay depends on light (no visibility rules).

## 8. Settings

| Setting | Values | Notes |
|---|---|---|
| **Art style (Look)** | Flat 2D, 2.5D, 3D (default 2.5D for new installs; owner decision) | Per profile; per-game override for Bot Bastion only; live preview cards (mock `B3Settings`); shows download size when a pack is missing |
| **Quality** | Auto, Battery saver, Balanced, High | Auto resolves to one of the others, demotes on two bad windows, never promotes mid-run |
| **Motion** | Calm, Standard, Lively | Plus OS Reduced Motion which always wins; Lively requires the P0 perf fix |
| Camera | Default tilt, Plan view | Remembered; Plan view forced in tabletop/3+ players |
| Camera zoom reset | button | |
| Eco toast | on/off | Kid profiles: silent "Eco mode" chip |

Surfaces: the fallback chip in the HUD, the Settings sheet (`B3Settings`), the pre-match setup card, and the results screen (shows which Look and Quality the run used so leaderboards can be compared fairly; scores are Look-independent).

## 9. Tests (JVM first; device-free)

| Id | Test | Asserts |
|---|---|---|
| R1 | `CameraProjectionTest` | project then unproject on planes z = 0 and z = h round-trips within 1e-4 at all modes and zooms; circle projects to ellipse with ratio sin(e); orbit symmetric |
| R2 | `PickingTest` | nearest-node rule, pick radius `max(0.55 u, 28 dp)`, bot cylinder beats node, ties stable, picks on lane/void return None; table of crafted screen points per map |
| R3 | `OcclusionRulesTest` | every map's props clear lane strips and node pads (dilated 0.25 u) in screen space at e = 50 and e = 90; height caps hold for all model specs |
| R4 | `DepthSortTest` | sort key (ground y + height bias) orders bots, enemies, flyers, projectiles deterministically; stable for equal keys |
| R5 | `SnapshotDifferTest` | pairs of snapshots yield the exact event lists above; no events when nothing changes; ids reused after a kill are not confused |
| R6 | `EventToEffectTest` | each `ArenaEvent` maps to the expected effect descriptor (sprite strip, count by Motion level, duration), including Reduced Motion removing shake/trails |
| R7 | `StyleFallbackTest` | chain 3D(R2) -> 3D(R1) -> 2.5D -> Flat for each trigger in `RECOMMENDATION.md` section 8; Auto demotion hysteresis; never promotes mid-run; Reduced Motion wins |
| R8 | `QualityBudgetTest` | caps (particles, shadows, ambient fps, render scale, sprite resolution) per Quality x Motion x device class table |
| R9 | `AssetManifestTest` | every sprite listed, hashes verified, sizes under the budget, poly counts under caps (from the Blender export stats) |
| R10 | `SemanticsLayerTest` | Compose tests: node count, order, labels, 56 dp hit regions (Voronoi), TalkBack announcement strings |
| R11 | `VisualDeterminismTest` | `VisualRng(matchSeed, seq)` yields identical effect descriptors on replay |
| R12 | Golden images (R1 only) | Roborazzi-style or an owner-approved JVM screenshot library renders fixed snapshots (empty map, mid-wave, boss, Core 25 percent) per sector and Look; non-shader paths only (AGSL golden support UNVERIFIED) |
| R13 | Self-run kit (devices) | Scripted pass on tablet, Fold inner, cover, tabletop, phone landscape with p95 frame time log and thermal status log; Claude does not install APKs |
| R14 | R2 only: lifecycle soak | rotate, fold/unfold, split-screen, 10 app switches, surface loss; thermal soak 20 min on Fold 5 |

## 10. Build and asset pipeline (summary)

1. Models: Blender Python from a shared template (proof: `renders/make_models.py`), original low-poly, flat shading + bevel, exported as GLB (`renders/gltf/`).
2. Bake: `scene_pass.py` style orthographic camera at elevation 50 degrees, transparent background, 256 px, 8 yaw facings per bot head and enemy, additional frame strips for animation; shadow-less (shadows added in-game).
3. Pack: crop to alpha bounds, record anchor and footprint, atlas by sector, lossy WebP, `manifest.json` with SHA-256 (per `style-packs/ARCHITECTURE.md`).
4. Gate: `asset_size_gate.py` fails the build over budget; `AssetManifestTest` verifies.
5. R2 only: `gltfpack`/meshopt compression, KTX2 for textured assets, `matc` for the toon/rim/outline material.
