# SCALE: budgets, costs, phasing, risks

All numeric budgets are PROPOSALS to be calibrated by measurement (perf audit 02: "No measurement exists" for cold start, memory, battery, APK size,
120 Hz). The only measured data point in the repo is the Tower Defence Canvas pilot: avg 11.1 ms/frame (90 Hz vsync) on a Galaxy Tab S9 FE,
which shows no jank on a flagship, not headroom. Treat everything below as targets for the measurement plan in section 8.

## 1. Frame-time budgets per level

Frame budget: 60 Hz = 16.7 ms; 90 Hz = 11.1 ms; 120 Hz = 8.3 ms. Rule: style rendering may use at most the share below of the frame for ONE active game screen (the rest is game logic, input, system).

| Quality | Target refresh | Style share of frame | p95 frame (style on) | Notes |
|---|---|---|---|---|
| Battery saver | up to 60 fps-class (animations at 30 where invisible) | 15% (2.5 ms @60) | < 16.7 ms | no shaders, no offscreen layers, no idle loops |
| Balanced | 60 fps, 90/120 Hz displays allowed but effects capped at 60 | 30% (5 ms @60) | < 16.7 ms | no per-frame RenderEffect creation; static layers cached |
| High | match display (90/120 Hz) | 45% @60, 60% of a 90 Hz frame (6.7 ms) | < 11.1 ms @90, < 8.3 ms @120 on HIGH-RAM devices only | AGSL, offscreen allowed with caching |
| Auto | resolves to one of the above | n/a | demotes on 2 bad windows | hysteresis, never promotes mid-game |

Hard rules (derived from perf audit 3.x): no composition-scope reads of animated values; no `RenderEffect` allocation per frame; static layers in `drawWithCache`; one shared frame clock; no loop runs when nothing animates.

## 2. Memory budgets

| Item | Budget (proposal) |
|---|---|
| Decoded style bitmaps alive per game screen (Soft 2.5D) | <= 8 MB |
| Full 3D sprite strips decoded alive (one surface set, e.g. dice) | <= 24 MB on HIGH ram, <= 12 MB on MID, none on LOW (falls back) |
| Whole style layer, all surfaces, resident worst case | <= 48 MB HIGH, <= 24 MB MID, <= 8 MB LOW (flat only) |
| Decode policy | decode at display size (not source size); RGB_565 for opaque backgrounds where banding is acceptable; `onTrimMemory` clears LRU |
| Per-screen allocation | zero per-frame allocations in the draw path (reuse Paths/Brushes via `drawWithCache`) |

LOW = `isLowRamDevice` or < 3 GB (calibrate). Fold inner screens render more pixels: budgets scale with the window pixel count, not just RAM class.

## 3. APK/AAB growth and delivery per pack

Proposals (compressed download size). Install-time budget is the strict one because it applies to everyone.

| Pack | Content | Target size | Delivery |
|---|---|---|---|
| Classic (code) | wraps existing drawing | +0 MB | base |
| Soft 2.5D (code + tiny assets) | gradients, shadow nine-patches, noise tile | <= 1.5 MB | base |
| Style framework | registry, resolver, interfaces | < 0.5 MB code | base |
| Full 3D: dice + cards sprite strips | 6 die faces x 24-32 frames x 3 palettes, card flip strip, WebP/KTX2 | <= 25 MB | on-demand asset pack |
| Full 3D: board pieces (chess, checkers, ludo, Mancala seeds) | turntable/settle strips per piece kind | <= 40 MB | on-demand, split per game family |
| Storybook kid pack | illustrated backgrounds + piece set + stickers | <= 30 MB | on-demand (kid-approved download) |
| GLB path (later) | optimised GLB, textures KTX2/WebP | <= 60 MB total + engine library size (UNVERIFIED, depends on engine) | on-demand |
| Sound palettes | per-pack OGG, short | <= 3 MB per pack | with the pack |

Budget rules: base app growth from the style system must stay <= +3 MB total; no single on-demand pack > 100 MB until Play limits are re-verified (documented numbers conflict: UNVERIFIED, see ARCHITECTURE section 7); total optional packs < 150 MB per device by default with a "remove packs" control. Size is enforced by `scripts/asset_size_gate.py` in CI against `style-budgets.json`. Install-time vs on-demand: only code and <1.5 MB of assets install-time; everything 3D on-demand; fast-follow unused (silent downloads conflict with kid/parent consent rules).

## 4. Per-surface cost table (relative, to be measured)

Cost units are the resolver's currency (`SurfaceCost`): CPU/GPU frame ms and resident MB at a typical 1080x2400 phone, estimated.

| Surface | FLAT | SOFT_25D | FULL_3D (projected / sprite) | Main risk |
|---|---|---|---|---|
| BOARD_SURFACE | 0.1 ms, 0 MB | 0.3 ms, 1-2 MB (cached gradient + grain tile) | 0.6 ms, 6-12 MB (baked material + baked AO) | felt grain via AGSL (API 33) gets rebuilt; cache it |
| PIECE (per piece) | 0.02 ms | 0.05 ms (soft shadow cached) | 0.12 ms sprite / 0.4 ms projected solid | 100+ pieces (Chinese Checkers 121, Ludo, Mahjong 144): batch via a single `drawWithCache` layer, animate only moving pieces |
| CARD (per card on screen) | 0.03 ms | 0.08 ms (graphicsLayer tilt, offscreen only during flip) | 0.2 ms strip frame | FannedHand with 25 cards: pre-rasterise faces; offscreen compositing only on the moving card |
| DIE | 0.05 ms | 0.1 ms | 0.3 ms projected (`drawDie3D` today) / 0.1 ms sprite | few instances; cheap |
| TILE | 0.03 ms | 0.06 ms | 0.15 ms | 15x15 Word Tiles board = 225 cells: cache static grid |
| BACKGROUND | 0.1 ms | 0.5 ms parallax (2-3 layers, cached) | 1.5 ms (AGSL / mesh) | the Home mesh gradient today has only the Reduced Motion gate (perf 3.1); must gain battery-saver/idle gating |
| CHROME | 0.05 ms | 0.1 ms | 0.2 ms | cheap; keep semantics |
| CELEBRATION (burst) | 0.2 ms (30 particles) | 0.5 ms (80) | 1.0 ms (150, High only) | burst only while particles alive; shared clock |
| SOUND_PALETTE | 0 | 0 | 0 | SoundPool pre-load; AudioTrack-per-sound issue in perf 8.1 must be fixed before more sounds are added |
| HAPTIC_PROFILE | 0 | 0 | 0 | gate by `LocalHapticsEnabled`; no cost |

Budget check: a Standard 3D chess screen = board 0.6 + 32 pieces (idle, cached = 0.6) + moving piece 0.4 + celebration 1.0 ~= 2.6 ms: fits Balanced 5 ms; Ludo/Chinese Checkers must use the cached-layer approach to fit.

## 5. Thermal and battery rules

1. Battery saver (OS) on: Auto resolves to BATTERY_SAVER; explicit High is downgraded to Balanced with a visible reason (user can override in app).
2. Thermal status (API 29+ `getCurrentThermalStatus`): >= MODERATE drop one quality step and halve particle budget; >= SEVERE flat art only, no loops. Re-check on `addThermalStatusListener`, apply hysteresis (>= 60 s stable before promoting) and never promote mid-animation.
3. Battery < 15% and not charging: cap at Balanced in Auto.
4. Idle rule: a screen with no player input and no game animation for > 10 s stops style loops (shimmer, parallax); resumes on input. (Today's Home mesh gradient and 37 particle loops violate this.)
5. Overlay rule: any open overlay (menu/help/picker) pauses style clocks (GameChrome v2).
6. Lifecycle rule: below RESUMED, nothing ticks (`repeatOnLifecycle`).
7. 120 Hz: logic is time-based so safe; the style layer does not request a higher refresh rate; effects cap at 60 under Balanced.
8. Long sessions: kid profiles with play-time limits (`PlayTimeLimiter`) get a one-step lower cap on thermal MODERATE.
9. Debug HUD (debug builds): shows resolved tier, reasons, jank %, thermal, so field tuning is possible without devices from this side (owner runs the kit).

## 6. Phased delivery and effort (S = <= 2 days, M = 3-8 days, L = 2-4 weeks; one engineer-equivalent, includes tests)

Prerequisites (from scorecard R1; not part of this system but blocking High/Lively): P0a GameChrome v2 overlay pause (M), P0b shared lifecycle frame clock (M), P0c fix per-frame recomposition in the worst 8 screens (M), P0d `drawWithCache` for static art in toy sports (M), P0e hit-slop + non-square fitBoard (S).

| Phase | Deliverable | Effort |
|---|---|---|
| S0 Spike/decisions | Owner decisions D1-D8, measurement of Home mesh + one board game on the owner's device kit | S |
| S1 Core (JVM only) | types, resolver, registry, capability mapper, migration mapper, tests | M |
| S2 Wiring + Classic | `LocalStylePack`, Classic pack adapters (zero visual diff), derived old locals, per-profile storage | M |
| S3 Settings UI | 3 pickers + preview + reasons, EN/ES, accessibility | M |
| S4 Soft 2.5D v1 | shared card face + board materials + dice + chrome; Reversi pilot, Mancala, Solitaire, Pig Dice | L |
| S5 Per-game override + kid rules + parent controls + Storybook | override row in Hub, parent gate wiring, one kid-safe pack | L |
| S6 Full 3D (sprite-based) | Blender turntable pipeline, PAD pack, dice + cards + 6 board piece sets | L |
| S7 Family rollout | migrate families (below) | L per family bundle, in parallel agent batches |
| S8 Hardening | JVM screenshot goldens (if D6), size gate in CI, measurement report, device self-test kit | M |
| S9 (optional) GLB/engine | only if D3 approved | L+ |

### Effort per game family (migration to surfaces; 81 games, scorecard groups)

Effort = work to make the family consume `LocalStylePack` for its surfaces at Classic + Soft 2.5D, plus Full 3D for the surfaces named. Assumes the shared kit exists.

| Family | Games | Surfaces mostly used | Soft 2.5D | Full 3D | Notes |
|---|---|---|---|---|---|
| G1 Board classics | 14 | BOARD, PIECE, CELEBRATION | M (pieces + board material shared; each game adds a spec) | L (piece strips: chess, checkers, Ludo, Mancala seeds, Backgammon) | pilot Reversi; fitBoard dependency |
| G2 Strategy/arcade | 10 | BOARD, PIECE, BACKGROUND, CELEBRATION | M | M (selective) | real-time ones need P0b/P0c first (Tower Defence, Air Hockey, Breakout) |
| G3 Cards | 12 | CARD, BOARD(felt), CELEBRATION, SOUND | L (unified deck is the big item: six renderers) | M once deck exists (flip/deal strips) | highest visible gain; scorecard S1 |
| G4a Word/logic | 9 | TILE, BOARD, CHROME | M | S (tiles only, optional) | text-scale and colour-blind constraints dominate |
| G4b Puzzle/casual | 8 | TILE, PIECE, CELEBRATION | S-M | S | generators on main thread are separate risk (R2) |
| G5 Toy sports | 14 | BACKGROUND, PIECE, CELEBRATION (+physics) | L (art caches per game, Billiards/Fishing/Toy* rebuild gradients per frame today: perf 3.7) | L (balls, dice, props) | perf-first, High tier gated carefully |
| G6 Party/kids | 14 | DIE, BACKGROUND, CHROME, CELEBRATION | M | M (dice, props), Storybook primary here | kids rules; pause() gaps; Party Toolkit dice (die-type picker) benefits most from Full 3D dice |

Rough total: Core+wiring+UI (S1-S3) ~ 3 M; Soft 2.5D shared kit + pilots (S4) ~ 1 L; kids/override (S5) ~ 1 L; Full 3D pipeline + dice/cards/6 sets (S6) ~ 1 L + 1 M; rollout (S7) ~ 7 family bundles of M to L.

## 7. Risk register

| ID | Risk | L | I | Mitigation | Owner |
|---|---|---|---|---|---|
| R1 | Higher tiers multiply today's per-frame loops/battery cost (perf 3.1-3.5) | H | H | P0 prerequisites; idle/overlay/lifecycle rules; do not ship High before measurement | eng |
| R2 | Full 3D size blows base APK / hits Play limits | M | H | on-demand packs, budgets in CI, stay < 100 MB/pack; verify Play limits (UNVERIFIED numbers) | eng |
| R3 | No device testing from these sessions; regressions invisible | H | M | JVM resolver tests, screenshot goldens (D6), self-run kit, debug HUD | eng+owner |
| R4 | 3D art quality below Classic polish ("worse than flat") | M | H | art director gate, R-A/R-B direction, ship per-surface only when it beats Classic in review; never default | owner |
| R5 | Accessibility regressions (colour-blind cues lost in 3D, text scale, TalkBack) | M | H | renderers must draw shape cue + semantics by contract; checklist gate; accessibility tests | eng |
| R6 | Kid exposure to non-kid-safe visuals / unexpected downloads | L | H | kid filter in resolver (hard), parent gate, no fast-follow, Wi-Fi only, kid-safety review per pack | eng+owner |
| R7 | Photosensitivity (flashing bursts, high-contrast strobing) | L | H | cap flash rate (< 3 flashes/s, WCAG 2.3.1-style), Calm default for 4-6, Lively opt-in | eng |
| R8 | Engine dependency (Filament etc.) adds size, complexity; previously declined | M | M | not in MVP/v1; separate go/no-go D3 | owner |
| R9 | Asset licence problems (3D models, textures, sounds) | M | H | original/own-made or CC0 with licence entry per asset, gate in CI, no Nintendo-like likenesses | eng+owner |
| R10 | Preference migration bugs (old `card3DEnabled` users) | M | L | pure migration mapper + tests, keep old keys one release | eng |
| R11 | Scope creep from 81 games x 10 surfaces | H | M | surface-by-family rollout, flat fallback ensures partial migration is shippable | eng |
| R12 | Auto tier flapping (hysteresis bugs) | M | M | pure function tests, promote only between games | eng |
| R13 | Multiplayer fairness: one player's heavy style slows the UI only locally; no protocol impact | L | L | style stays local; verified by not touching transport | eng |
| R14 | First-frame setting flip (perf 2.1) shows wrong style | M | L | synchronous first read / splash; style default never changes on frame 2 | eng |
| R15 | GPU/driver differences make AGSL/offscreen glitches | M | M | demote-on-failure per surface (`StyleHealth`), AGSL only API 33+, flat fallback | eng |

## 8. Measurement plan (feeds budgets)

Use the perf audit's own test table: Macrobenchmark `FrameTimingMetric` on 4 reference scenes (Home idle, Chess animation, Solitaire deal, Air Hockey) x {Classic, Soft 2.5D, Full 3D} x {Balanced, High}; `dumpsys gfxinfo`; Perfetto idle CPU; `dumpsys meminfo` per style; APK analyzer sizes. Run by the owner's kit or on the owner's devices (not by agents from here). Output feeds `style-budgets.json`.
