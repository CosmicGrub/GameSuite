# SCALE: budgets, assets, phasing, tests, risks for Gem Cascade

Status: DESIGN DRAFT, 2026-11-22. All numbers are PROPOSALS to be measured; the repo has no phone measurements (style-pack SCALE, perf audit 02). Reference devices: "mid-range phone" = 6 GB RAM, 60/90 Hz, 1080x2400 (Pixel 6a class, UNVERIFIED target); tablets = Galaxy Tab S9 FE (90 Hz) and Fold 5 inner (7.6 inch, 120 Hz). Style budgets inherit `docs/design/style-packs/SCALE.md`.

## 1. Frame budgets (one active 8x8 board)

| Style | Quality | Frame share | p95 frame (60 Hz phone) | Notes |
|---|---|---|---|---|
| 2D flat | all | 0.8 ms idle, 1.0 ms with a burst | < 16.7 ms | static board cached in `drawWithCache`; 64 gems drawn as cached vectors/bitmaps; only moving gems redrawn |
| 2.5D soft | Balanced | 2 ms | < 16.7 ms | one `graphicsLayer` for the tilted board; cached shadow ellipses; no per-frame blur; AGSL normal-map shading only High, API 33+ |
| 3D baked | Balanced | 5 ms | < 16.7 ms | pre-baked lit sprites, one tilt layer; shards as sprites; software-projected solids only for the 12 animating gems at most |
| 3D baked | High, 120 Hz | 6.7 ms of 8.3 ms | < 8.3 ms (HIGH-RAM devices only) | Fold 5 inner, tablets |
| any | Battery saver | flat only, 0.5 ms | < 16.7 ms | no shaders, no idle loops, particles 0 |
Tablets: board is the same cell count at larger cells; per-frame cost scales with pixels, not cells: budget 1.3x for 2560x1600.
Cascade worst case: 64 gems falling = 64 translated sprites: still one pass. Hard rules: no composition-scope reads of animated values, no `RenderEffect` allocation per frame, one shared frame clock, nothing runs when idle, overlay open pauses (`overlayOpen`, scorecard finding 2).

## 2. Particle budgets (per burst, max alive)

| Style | Calm | Standard | Lively | Max alive on screen |
|---|---|---|---|---|
| 2D flat | 0 | 24 (squares) | 48 | 120 |
| 2.5D soft | 0 | 24 (blobs, cached bitmap) | 48 | 120 |
| 3D baked | 0 | 18 (shard sprites) | 36 | 90 |
| Battery saver | 0 | 0 | 0 | 0 |
Chain cap: particles of steps beyond 4 are halved. Confetti at level complete: 6 / 10 / 14 (2D / 2.5D / 3D) at Standard, x3 at Lively; hard cap 150 High only.

## 3. Memory budgets

| Item | Flat | 2.5D | 3D baked |
|---|---|---|---|
| Gem bitmaps decoded (11 gem looks x 2 sizes) | 0 (vector) | 1.2 MB (128 px sprites, 7 gems + 4 specials) | 4.7 MB (11 looks x 12 turntable frames x 96 px) + 2.2 MB shards |
| Board surface + shadows | 0.3 MB | 1.5 MB | 4 MB |
| Particle atlas | 0.1 MB | 0.4 MB | 1.2 MB |
| Obstacle art | 0.2 MB | 0.6 MB | 1.6 MB |
| Resident total, worst case | ~1 MB | 3 to 6 MB | 10 to 14 MB |
LOW-RAM devices (< 3 GB) never load 3D; MID loads 3D without turntable frames (static sprite + shadow). `onTrimMemory` drops the turntable set first. Decode at display size; RGB_565 only for opaque backgrounds.

## 4. Asset sizes per style pack

| Pack | Content | Install size | Delivery |
|---|---|---|---|
| 2D vector gems | `Path` data for 7 gems, 4 specials, 7 obstacles, HUD icons (code) | +0.1 MB code, 0 assets | base APK |
| 2D themed packs (Paper craft, Neon arcade) | palettes, shadow bitmaps, glow tile | 0.3 to 1.0 MB each | base or on-demand |
| Storybook kids | rounded faces, backgrounds, bloom art | <= 8 MB | on-demand, parent-approved |
| 2.5D | 11 sprites of 128 px WebP (about 8 KB each) + normal maps 256 px (7 x 60 KB, API 33+) + shadow nine-patch + background layers | 0.6 MB (+0.5 MB normal maps) | base APK |
| 3D baked | Blender turntables: 11 looks x 12 frames x 96 px WebP (about 3 KB each = 0.4 MB) + 24 shard frames + 4 board materials | 4 MB | on-demand asset pack |
| 3D optional real-time later | GLB per gem (about 40 KB each, 11 gems) + textures; needs an engine (owner decision D3) | UNVERIFIED, depends on engine | on-demand |
| Sound palette | OGG, short, per style family | <= 3 MB | with the pack |
Base APK growth from Gem Cascade: <= 3 MB (code, flat + 2.5D sprites, base sounds). Obey the 100 MB per on-demand pack cap until Play limits are re-verified.

## 5. Battery rules

1. No loop runs when nothing animates; the board is event-driven (engine event stream) not poll-driven.
2. Idle hint pulse and shimmer are off in Calm, Battery saver and Reduced Motion; hint pulse stops after 3 cycles.
3. OS battery saver or thermal throttling drops one tier (3D -> 2.5D -> flat) with a visible reason chip.
4. Haptics only for events in the cue sheet; no continuous rumble.
5. Sprint and Fuse tickers use the shared frame clock at 30 Hz-class when the board is idle.
6. 120 Hz only in High, and only with 3D baked or flat; capped at 60 Hz-class effects in Balanced.
7. Arena or background simulation: not applicable to this game.

## 6. Phased delivery (S/M/L/XL)

| Phase | Deliverable | Acceptance | Effort |
|---|---|---|---|
| 0 | Owner decisions (end of this file); `EVENTS.md` by the engine team | decisions logged | S |
| 1 | Engine in pure Kotlin: board, swap, match, gravity, cascade, specials + combos, shuffle, hint, seed, event stream | JVM tests (section 7) green; deterministic replays | L |
| 2 | Flat renderer + HUD + Ascent/Garden/Sprint + local bests + EN/ES | playable on tablet via owner kit; zero idle recomposition | M |
| 3 | Style wiring: gem/board/particles/sound/haptic via `LocalStylePack`; Soft 2.5D | fallback totality tests include gem-cascade; budgets met on owner kit | M |
| 4 | Journey (30 levels), Daily, stickers (first 12), tutorial | level bot records; tutorial on 3 devices | M |
| 5 | Kid profile: Garden default, parent view, gate wiring, Storybook pack, accessibility pass (tap-tap, one-switch, 200 percent text) | kid-safety review signed; a11y review | M |
| 6 | Swirl, Delve, Fireflies, Fuse + worlds 3-10 (levels 31-150) | bot records per level, owner play pass | XL |
| 7 | 3D baked pack: Blender turntables, shards, board materials, on-demand pack | size gate green; fallback verified when absent | L |
| 8 | Hardening: measurement report vs budgets, battery test, Fold posture pass | numbers within section 1-5 | M |
| 9 | Later: Riddles 31-60, Thaw, Sigils, Charms, Duel, GLB spike | owner go/no-go | L |
Parallelism: phases 3 and 4 can overlap after 2; 7 art work can start after 3 settles the sprite sizes; phase 6 levels in parallel agent batches by world.

## 7. Test plan

| Layer | Tests |
|---|---|
| Engine (JVM) | match detection for every shape; special creation priority (6 beats 5 beats L/T beats 4); gravity and refill determinism for a seed; cascade chains; each combo; cage/frost/slate/bomb/firefly/dirt/relic rules; dead-board detection and reshuffle guarantee (property: after reshuffle, no match and at least one move, 10k seeds); Swirl rotation; scoring tables; Daily seed identical for the same UTC date; serialisation round-trip |
| Level bot | greedy bot and random bot per level (section A4 of SCRIPT); fails the build if a level is unsolvable or trivially easy |
| Event stream | golden event sequences per scripted board (the engine contract with the renderer) |
| Style system | fallback totality (Surface x Tier x caps) includes gem-cascade; `StyleNeeds` equals used surfaces; Reduced Motion resolver properties (no tilt, parallax, shake) |
| UI logic | tap-tap state machine; one-switch scan sequence; text-scale layout (200 percent) unit tests; ES strings complete; lint: no hard-coded English |
| Accessibility | contrast of gems vs board >= 3:1 (generator-checked); four colour-blind simulations reviewed; TalkBack labels; `design:accessibility-review` pass |
| Performance (owner kit, no APK installs by agents) | frame-time scene: 64-gem cascade on tablet, Fold, mid-range phone; idle recomposition count; 10-minute battery delta per style |
| Golden images (if D6 approved) | flat / 2.5D / 3D board states in Calm and Standard |

## 8. Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Name collision or imitation claim | M | H | original names/art only (FRANCHISE-MAP section 0), trademark search, no mode name from the franchise |
| R2 | 3D too heavy on mid-range phones | M | M | baked sprites, tier fallback, Auto demotes, battery saver forces flat |
| R3 | Tilted board breaks hit testing and tap-tap | M | H | inverse-transform input, cell hit slop, tests with rotated layers; Reduced Motion removes tilt |
| R4 | Cascades feel slow | M | M | chain speed-up x0.9 per step (min 0.6), input unlock after last drop, Lively only adds flair |
| R5 | Levels unwinnable or trivial | M | M | bot records in CI, owner playtests per world |
| R6 | Photosensitivity | L | H | flash cap 3/s, no full-screen white flashes, Calm for kids 4-6 |
| R7 | Kid-safety review slips | M | M | gate kid Journey and Storybook behind review; Garden alone ships first |
| R8 | Asset pack size creep | M | M | size gate in CI, `style-budgets.json` |
| R9 | Scope: XL level work | H | M | MVP 30 levels, worlds in batches, generator reuse |
| R10 | Colour-only cues sneaking in | L | M | greyscale/simulation screenshots in the pack quality gate |
| R11 | No device in agent sessions (standing no-APK rule) | H | M | self-run owner kit, Windows/JVM verification, goldens |
| R12 | Engine/renderer divergence | M | M | EVENTS.md is the only contract; golden event tests |

## 9. Owner decisions needed

| # | Decision | Recommended |
|---|---|---|
| GC1 | Approve "Gem Cascade" as a placeholder only and run a name search | Yes |
| GC2 | MVP scope: Ascent, Garden, Sprint, Daily, 30 Journey levels, flat + 2.5D | Yes |
| GC3 | 3D delivery: baked sprites first (no engine); real-time 3D remains owner decision D3 | Yes |
| GC4 | Kid profile default: Garden, hide Sprint/Fireflies/Fuse | Yes |
| GC5 | Sigils (pattern bonus): keep, cut, or later | Later; cut if unsure |
| GC6 | Level count at launch (30 or 150) | 30 in MVP, 150 in v1 |
| GC7 | Free "+5 moves" assist after two fails (parent can disable) | Yes |
| GC8 | Charms (earned pre-run perks) | v1.1 |
| GC9 | Family board: local profiles only, kids opt-in by parent | Yes |
| GC10 | Who signs the kid-safety review and who runs the device kit | Owner |
