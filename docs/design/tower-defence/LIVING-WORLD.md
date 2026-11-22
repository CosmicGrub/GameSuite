# Tower Defence: living world (ambient motion spec)

Status: DESIGN DRAFT, 2026-11-22. Board: `TDLivingWorld.dc.html` (layer stack, motion levels, frame budget, time of day and weather, filmstrips for all eight themes). Storyboards for gameplay moments are in `SCRIPT.md` and `TDStoryboards.dc.html`.

## 1. Principle

The stage is mostly **static art** (cheap, crisp, cached). On top sits a short list of **ambient loops** that make the place feel alive. Ambient never changes gameplay information, never covers the field, and never costs frames the simulation needs. Everything respects Calm / Standard / Lively, Reduced Motion, battery saver and thermal state from the style system.

## 2. Layer stack

Back to front. Parallax speed is the camera-relative drift of the layer's bitmap strip; static layers cost nothing per frame once cached.

| # | Layer | Content (Castle Siege example) | Update | Cost |
|---|---|---|---|---|
| 0 | Sky | Gradient re-tinted by time of day, sun or moon disc | none (retint on time change) | 0 |
| 1 | Cloud band | 2 to 4 sprites, wrap-around drift 3 to 5 px/s | per frame, 1 draw each | tiny |
| 2 | Far layer | Mountains, skyline, dunes, reef silhouette; one 2x-wide bitmap strip, 1.5 px/s | scroll offset | 1 draw |
| 3 | Mid layer | Hills with village, aqueduct, torii, pyramids; 4 px/s | scroll offset | 1 draw |
| 4 | Ground, road, props | Baked once per map and theme into a cache layer; build-phase grid dots and hatching overlay on top | none | 1 draw |
| 5 | Units | Towers, enemies, castle, projectiles (the simulation) | every frame | dominant cost |
| 6 | Foreground fx | Grass blow, birds, petals, snow, bubbles, dust motes; 8 to 14 px/s | per frame, capped count | particle budget |

The camera on narrow screens scrolls layers 2 and 3 at 25 percent and 40 percent of the map scroll for parallax depth; they stay static in Calm.

## 3. Ambient list per theme

Loop length 12 s (filmstrip frames at 0, 4 and 8 s). Counts are Standard; Calm halves counts, Lively adds 50 percent.

| Theme | Ambient loops |
|---|---|
| Castle Siege | Clouds drift 4 px/s; castle flags ripple at 1.2 Hz; grass tufts sway 0.5 Hz; crow flock crosses every 40 s; village chimney smoke; torches flicker at dusk and night |
| Space Station | Star twinkle (8 random stars per second); comet every 30 s; station beacons pulse 0.5 Hz; nebula breathes over 20 s; planet ring glint |
| Roman Frontier | Banner ripple 1 Hz; brazier smoke loop 3 s; swallows cross every 25 s; cypress tops sway 0.3 Hz |
| Cherry Ridge | 12 to 24 petals fall on a diagonal with spin; lantern flicker 4 Hz at low amplitude; mist drifts 6 px/s; koi ripple every 5 s |
| Sunstone Oasis | Heat shimmer sine band; sand streaks at 30 px/s; falcon circles a 150 px ellipse every 20 s; palm sway |
| Frostfjord | Snowfall (20 to 60 flakes); aurora ribbon phase over 14 s; longhall chimney smoke; torch flicker 5 Hz |
| Toy Room | Rubber ball bounces across every 15 s; dust motes in a sunbeam; night-light glow 0.4 Hz; mobile blocks spin |
| Coral Reef | Bubbles rise (12 to 30); caustic light rays sway 0.2 Hz; fish school crosses every 20 s; kelp sway |

All ambient elements are original sprites or vector shapes; none are interactive. Tapping them does nothing, so a child tapping the sky never misplaces a tower.

## 4. Motion levels

Maps onto the style system's Motion level axis (Calm / Standard / Lively) plus the OS Reduced Motion flag, which always wins.

| Feature | Calm | Standard | Lively | Reduced Motion |
|---|---|---|---|---|
| Parallax drift | off (static bands) | slow drift | slow drift plus subtle depth wobble on camera move | off |
| Clouds, birds | clouds only | clouds and birds | flocks, cloud shadows on ground | static clouds |
| Flags, grass, banners | slow, 3 flags | 1 Hz ripple | gusts, more flags | static frame |
| Torches, lanterns | steady glow | low flicker | flicker and sparks | steady glow |
| Water, snow, bubbles | ripples only | ripples and light weather | full weather | none |
| Weather particles | 0 to 15 | 20 to 40 | 40 to 80 | 0 |
| Attack effects | muzzle and hit pop | plus dust and coin pops | plus trails and screen pulse | hit pop only |
| Camera shake | off | boss and castle hit | plus big blasts | off |
| Frame cap for ambient | 30 fps | 60 fps | 60 fps | frozen |
| Ambient budget | <= 0.4 ms | <= 1.0 ms | <= 1.8 ms | 0 ms |

Rules:
- Enemy and projectile movement are gameplay and never frozen by Reduced Motion; only decoration stops. Enemy walk cycles use the existing animation-reduction rule (single frame, no bobbing).
- Pause freezes ambient too, except the pause scrim's own fade.
- Lively is not offered on the TD screen until the whole-screen per-frame recomposition is fixed (scorecard systemic item 3; the style matrix says not to pilot Lively on Tower Defence before that fix).

## 5. Quality tiers and battery

| Tier | Sprite scale | Ambient layers | Particle cap | Parallax | Notes |
|---|---|---|---|---|---|
| Low | 0.75 x | layers 1 and 6 only, 30 fps | 15 | off | Default on devices below the perf class floor |
| Medium | 1.0 x | all, 60 fps | 40 | on | Default |
| High | 1.25 x | all plus soft shadows and glow | 80 | on plus depth wobble | Flat shaded AGSL gradients only here |

Auto-downgrade (never upgrades without the user): battery saver on, battery under 15 percent, or thermal status moderate or worse steps down one tier per 30 s of sustained dropped frames, in this order: Lively to Standard, Standard to Calm, 60 to 30 fps ambient, then freeze ambient. The simulation is never touched. A toast says "Visuals simplified to save battery" once per session.

## 6. Frame budget (60 fps = 16.6 ms, mid-range phone, Lively)

Targets from the board: simulation and AI 1.6 ms, sprites and HUD 5.2 ms, ambient 1.8 ms, particles 1.6 ms, compose and GPU 3.6 ms, headroom 2.8 ms. Standard cuts ambient to 1.0 ms. The cache layer (layer 4) is drawn once; only layer 5 and the capped ambient draws change. Hit-testing for placement uses the nav grid, not the sprite tree.

Measurement plan: macrobenchmark on the Castle Siege hero state at 3 motion levels and 3 tiers on the tablet and Fold; gate in `SCALE.md`.

## 7. Time of day and weather

Same map, tint and overlay only, no new art (board row "Time of day and weather"):

| Variant | Treatment |
|---|---|
| Dawn | pink tint, long soft shadows |
| Day | default |
| Dusk | orange wash on sky band, torches and lanterns on |
| Night | dark blue overlay 55 percent, moon, window and torch glows, stars |
| Rain | slanted streaks, 28 percent darkening, wet sheen on road |
| Snow | flakes, no gameplay change |

Rules: Daily Challenge may set time of day and weather as the day's twist (cosmetic only, never changes stats). Contrast checks: HUD and range rings must keep contrast at Night and Rain (HUD sits on opaque glass pills; rings gain an inner dark stroke at Night).

## 8. Interaction with gameplay readability

- Ambient elements are never red or amber near the route; ring colours stay unique.
- Birds and petals stay in the upper third or above ground sprites; they do not pass over tower sprites.
- No ambient element resembles an enemy silhouette (flyers read as flyers by shape and by a ground shadow, not by being "the bird").
- Screen flash and shake are limited to the documented events; none exceed 3 flashes per second (photosensitivity).

## 9. Implementation notes (sketch, not source)

- One `AmbientDirector` owned by the screen, driven by the shared frame clock and `LocalStylePack` motion level, emitting a small list of draw commands for layers 1 to 3 and 6. It owns a seeded random so the filmstrip in the board is reproducible in tests.
- Layers 2 and 3 are `ImageBitmap` strips with `drawImage` offsets; layer 4 is a `GraphicsLayer` or `ImageBitmap` cache invalidated only by map or theme change and by build-phase overlay toggles.
- No per-frame allocation in draw lambdas (the existing scorecard finding for Bowling, Billiards and Mini Golf); precompute `Path` and `Brush` objects.
- Ambient pauses when the composable is not visible (no always-on loops, the `rememberParticleBurst` problem).
