# Tower Defence: scale (assets, budgets, effort)

Status: DESIGN DRAFT, 2026-11-22. All figures are planning estimates, not measurements. Reads with `docs/design/style-packs/SCALE.md` for the app-wide budgets and phases.

## 1. Asset counts

Per theme (full set). "Sprite" = one drawn asset at 1 resolution; states multiply it.

| Group | Items | States each | Sprites |
|---|---|---|---|
| Towers | 4 roles x (L1, L2, L3, A4, A5, B4, B5) = 28 visuals | idle, firing, selected glow handled in code | 28 x 2 = 56 |
| Enemies | 4 archetypes | walk x4 frames, hit, die x3 frames | 4 x 8 = 32 |
| Base object | 1 | intact, cracked 50%, cracked 25%, fallen, hit flash handled in code | 4 |
| Projectiles and fx | 4 projectiles, 6 fx (poof, dust, spark, level-up, coin, heal) | 1 to 4 frames | about 20 |
| Terrain | ground tile set (4 tints), road piece set (straight, bend, fork, end), deco props (10 to 14), water or hazard (2) | 1 | about 40 |
| Parallax strips | sky, far, mid | 1 each | 3 |
| Ambient sprites | 6 to 10 | 1 to 4 frames | about 20 |
| Icons | 4 role icons, 4 archetype icons, 4 targeting icons | 1 | 12 |
| **Total per theme** | | | **about 190 sprites** |

Eight themes: about 1,500 sprites at Flat and Soft 2.5D; Full 3D bakes add 8 views for towers and enemies (about 8 x 88 = 700 per theme, only if 3D is approved). The board art is vector, so Flat packs are derived straight from the SVG masters; Soft 2.5D adds baked gradients and cached shadows; 3D uses turntable bakes (Blender pipeline already drafted in `style-packs/scripts/`).

## 2. Sizes

| Asset | Size at 1x (dp) | Atlas pixel size at Medium (xxhdpi 3x) | Notes |
|---|---|---|---|
| Tower sprite | 56 x 56 | 168 x 168 | footprint is 1 tile, art may overhang above |
| Enemy sprite | 44 x 44 (boss 84 x 84) | 132 / 252 | 4 walk frames |
| Base object | 160 x 120 | 480 x 360 | the largest single sprite |
| Parallax strip | 2 x screen width x 120 dp tall | about 2,800 x 360 | 1 draw each |
| Ground cache | the whole field | 2,400 x 1,400 | drawn once |
| Icons | 24 and 48 | | `ImageVector` from SVG, no bitmap |

Format: WebP lossless for pixel-exact sprites, WebP lossy q85 for parallax strips, `ImageVector` for icons and UI. Atlases are per theme per tier so only the active theme loads.

## 3. Memory and size budgets

| Item | Budget | Basis |
|---|---|---|
| Decoded atlas in RAM (Medium) | <= 14 MB for the active theme | 190 sprites averaging 70 KB decoded plus strips; High up to 22 MB |
| Package size per theme (Flat + Soft 2.5D, WebP) | <= 1.6 MB | bundled in APK for Castle Siege, Space Station, Toy Room; others delivered by Play Asset Delivery install-time or on-demand |
| All 8 themes | <= 12 MB | meets the app-wide asset plan in `style-packs/SCALE.md` |
| Audio per theme | <= 600 KB | short synthesised samples, 16-bit mono 22 kHz; loops under 30 s |
| Ground cache + tower sprites hot set | <= 24 MB total GPU | |

## 4. Runtime budgets

| Metric | Target |
|---|---|
| Frame time at Standard (60 fps) | <= 11 ms CPU main thread, <= 8 ms GPU on the Fold 5 class device |
| Ambient | <= 1.0 ms Standard, <= 0.4 ms Calm, <= 1.8 ms Lively |
| Dijkstra recompute | <= 1 ms for 1344 cells; <= 30 Hz while dragging |
| Placement validation latency (drag to red or green) | <= 66 ms |
| Active enemies | up to 60 simultaneously (wave 10 Hard) |
| Towers | up to 40 |
| Projectiles | up to 120 |
| Particles | cap by tier: Low 15, Medium 40, High 80 |
| Draw calls for sprites | batched atlas draws; target <= 150 per frame |
| Startup of theme switch | <= 400 ms with a cached atlas, <= 1.5 s cold |
| Battery | no ambient loop when not visible; ambient frozen under Reduced Motion |

## 5. Effort (S = about 1 to 3 days, M = about 1 to 2 weeks, L = 2 to 4 weeks; one engineer or artist)

| Work package | Size | Notes |
|---|---|---|
| Perf fix of TD screen composition | S to M | prerequisite |
| Nav grid, flow field, validation, tests | M | pure Kotlin |
| Roles, branches, sell, move, targeting, castle, boss rules in engine | M | |
| HUD v2, tray, panels, radial menu, next-wave card | M | Compose Canvas and semantics |
| Theme pack authoring, first theme (Castle Siege) including pipeline | L | derive from the SVG masters in this set |
| Each further theme | M each (about 5 to 8 working days) | Roman, Cherry Ridge, Sunstone Oasis, Frostfjord |
| Kid themes (Toy Room, Coral Reef) with kid copy and voice prompts | M each | extra review |
| Space Station (flat and glow) | M | |
| Ambient director plus layer system | M | |
| Modes hub, Endless, Daily, Gentle tier | M | |
| Sandbox, Co-op one device | M | co-op ownership rules and dual trays |
| Results screens, score card, stickers | S to M | reuse Score Card v2 |
| Accessibility: TalkBack grid, one-switch, large-target scaling | M | with `design:accessibility-review` |
| Tablet and Fold layouts (two-pane, tabletop, cover, book) | M | book posture untested on hardware |
| Audio: 8 theme palettes | M | synthesised |
| Localisation EN and ES | S | |
| Full 3D bakes (optional) | L | gated on the 3D decision |
| QA and device passes | M | tablet, Fold inner and cover, a low-end phone |

Rough totals: engine and UI core (P0 to P2) 8 to 12 person-weeks; each additional theme 1 to 1.5 weeks; modes and accessibility 5 to 7 weeks. A credible first release is Castle Siege plus Space Station plus Toy Room on the open-field engine, with Classic maps retained (P0 to P3 and a slice of P5).

## 6. Risks

| Risk | Mitigation |
|---|---|
| Art volume (about 1,500 sprites) | Ship themes in waves; shared geometry means a theme is 190 drawings, vector masters exist; Flat first |
| Per-frame recomposition keeps TD janky | P0 gate before anything Lively |
| Play Asset Delivery complexity | Bundle three themes; deliver the rest on demand behind a size gate |
| Balance across 4 roles x branches | Simulate in JVM against wave tables before any art |
| Kid safety review per theme | Review checklist in `THEMES.md` section 5, parent settings unchanged |
| Book posture and tabletop untested | Self-run device kit; user's standing rule: no APK installs by Claude |
