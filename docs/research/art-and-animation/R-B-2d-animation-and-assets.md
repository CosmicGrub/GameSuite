# R-B: 2D animation technology and art/asset sources for GameSuite

Date: 2026-11-21. Research only; no source was edited. Web facts come from WebSearch snippets (WebFetch was unavailable), so anything not directly seen in a snippet is marked UNVERIFIED. Re-read the licence on the source page for each asset before shipping. Nothing here is legal advice.

Owner constraints on record: original art preferred and drawn in code; no Nintendo/Mario or other trademarked assets; art must not copy trademarked games; kids' content must be safe (Families policy). Third-party free assets are an OPEN QUESTION (section 4).

---

## 1. What exists in the repo today (scanned)

| Area | Finding |
|---|---|
| Dependencies | `app/build.gradle.kts`: Compose BOM `2024.09.00`, material3, ui-graphics, activity-compose, navigation-compose, DataStore, okhttp. NO Lottie, Rive, Coil, graphics-shapes or animation-graphics. minSdk 26, compile/target 34. |
| Art pipeline | Essentially none. `res/drawable` holds only launcher icon XMLs and two PNGs (`puzzle_pic_koi.png`, `puzzle_pic_sunset.png`). `res/raw` is 45 KB (4 WAVs + 1 xml). `assets/` is 3.9 MB, all word lists. `res` total 1.9 MB. No `res/font` (system fonts; only `FontFamily.Serif` is used, on card faces). No SVG/Lottie/Rive files. |
| Art in code | 16 `ui/*Art.kt` files (e.g. `ToyBoxingArt`, `ShootingGalleryArt`, `TankBattleArt`, `FishingArt`) plus per-screen Canvas drawing. This is the "drawn in code" approach the owner prefers. |
| Animation primitives | 76 files use `animate*AsState`/`Animatable`; 22 files run `withFrameNanos` loops; `rememberInfiniteTransition` for pulses. |
| Particles | `ui/effects/ParticleBurst.kt`: shared velocity/gravity/fade burst, referenced by ~45 files (audit counted 37 at audit time). UNO confetti and Solitaire fan flourish are separate bespoke effects. `CameraShake.kt` also exists. |
| Shaders | `ui/effects/PremiumShaders.kt` (AGSL `RuntimeShader`, API 33+, each entry gated by `SDK_INT` and `runCatching`): `SPECULAR_SWEEP_AGSL`, `VICTORY_GLOW_AGSL`, `FELT_GRAIN_AGSL`, `MESH_GRADIENT_AGSL`, plus `DialogBackdropBlur` and a power-save receiver. Below API 33 callers silently get the unshaded chain. |
| Motion settings | `LocalReducedMotion` (referenced in ~96 files), `LocalEnhancedAnimations` (21 files), `GameSpeed` (arena models, casino cards, MainActivity), `LocalHapticsEnabled`. `haptics/Haptics.kt` has a `HapticSignal` vocabulary mapped to `VibrationEffect` waveforms (works at minSdk 26). |
| Theming | `docs/SETTINGS_THEMING_ACCESSIBILITY.md`: AppTheme with ThemeMode + dynamic colour + `NamedTheme` (Classic, High Contrast, Dark, Midnight Arcade, Felt Table), `LocalGameAccent` CompositionLocal pattern; rule that game-critical colours (UNO suits etc.) are never `MaterialTheme.colorScheme` roles. Per-game skins are already listed (Tic-Tac-Toe X/O styles, Mancala wood/stone/seed, Dominoes felt, Air Hockey classic vs neon, Word Tiles tile style). |
| Performance baseline | `docs/audit/02-performance.md`: perpetual full-screen mesh-gradient shader on Home (3.1 HIGH); infinite-transition values read in composition (3.2 HIGH); `specularSweep` rebuilds a RenderEffect every frame and is unconditional on the Air Hockey puck (3.3 HIGH); `rememberParticleBurst` loops forever even when idle (3.4); allocation in draw blocks, only 14 `drawWithCache` vs 141 `Path()` sites (3.7). Measured Tower Defence Canvas pilot: avg 11.1 ms on a 90 Hz flagship tablet (vsync-bound, so not a headroom proof). |
| Credits / licences | No in-app Credits screen found by grep. No NOTICE file at repo root (root has LICENSE, PRIVACY_POLICY.md, README.md). |

Implication: the app is Canvas/AGSL-first with almost no asset files. Style-pack work should extend that (token-driven procedural drawing) before adding a second animation runtime. The perf debts (3.1-3.4, 3.7) should be paid before more per-frame visual layers are added, or style packs multiply them.

---

## 2. Technology comparison

Facts are from search snippets unless marked.

| Technology | Good for here | Size / perf | Licence / cost | Verdict |
|---|---|---|---|---|
| Compose Canvas procedural (today) | Boards, pieces, cards, dice, particles, paper/felt textures from tokens. Fully themeable, zero asset files, honours reduced motion and GameSpeed trivially. | 0 KB assets. Cost is CPU/GPU per frame; fixable with `drawWithCache`, hoisted Brushes/Paths, State read in draw lambdas. | Own code. | PRIMARY for all in-game art and motion. |
| Lottie (`lottie-compose`, Airbnb) | Pre-authored vector animations: win trophy, mascot sting, onboarding, empty states, result celebrations. | Changelog lists releases up to 6.4.0 (6.4.1+ adds `.tgs`); a Jan 2026 LottieFiles guide uses a 6.x dependency. Exact latest version and AAR/APK size: UNVERIFIED (check Maven Central, measure). Lottie docs: masks and mattes are the biggest Android perf cost; hardware acceleration helps. Support for expressions/merge paths: UNVERIFIED. dotLottie (zip with JSON + images) is parsed by Lottie Android since 3.4.x. | Library licence not confirmed in snippets (believed Apache 2.0: UNVERIFIED). The per-animation licence is the real issue (section 3). | USE SPARINGLY for hero moments, authored in-house, <=1 on screen, <=30 KB each, static poster under reduced motion. Not a skinning mechanism. |
| dotLottie Android player (LottieFiles, Rust, JitPack) | State machines/themes in one file. | Docs show versions 0.4.1 and 0.5.0 (inconsistent): pre-1.0, immature. | UNVERIFIED. | Do not adopt now. |
| Rive (`app.rive:rive-android`) | Interactive characters with state machines (a kids' mascot reacting to win/lose). | Maven shows 11.x (11.10.0, 11.12.1 seen); runtime listed MIT. Native size and Compose API maturity: UNVERIFIED (not found; Rive ships native code, so measure). | Editor free to create, but since 2025-10-20 exporting to ship needs a paid plan ("Free to create. $9/month to ship"); free plan = 3 files. Plan names and prices are inconsistent across Rive pages: UNVERIFIED, check rive.app/pricing. Marketplace files are CC BY. | DO NOT ADOPT this round: recurring SaaS cost, native size, artist-tool dependency, little gain over Lottie + Canvas. Revisit only for an approved interactive mascot. |
| AnimatedVectorDrawable / `animatedVectorResource` | Small icon morphs (play/pause, check, X). | Tiny XML. Compose bridge (`androidx.compose.animation.graphics`, `rememberAnimatedVectorPainter`) is opt-in `ExperimentalAnimationGraphicsApi` in doc samples. Specific AVD limits: UNVERIFIED. Android docs list alternatives: AVD, `ImageVector` + Compose animation APIs, Lottie. | AndroidX/platform (Apache 2.0, general knowledge). | Low priority; `Animatable` + `ImageVector`/Canvas does the same and is already used. |
| Sprite sheet / frame animation | Painted or pixel-art characters, dice roll sequences. | e.g. 8 frames at 256x256 is roughly 100-400 KB as WebP (estimate, UNVERIFIED); decoded memory scales with RGBA size. Stepping via `withFrameNanos` or `Animatable<Int>` is trivial. | Depends on source art. | Only where Canvas cannot do it (a pixel/painted pack). One WebP atlas per pack, lazy-loaded per game. |
| `androidx.graphics:graphics-shapes` (RoundedPolygon, Morph) | Shape-morphing buttons/chips/pieces, wobbly storybook blobs, pill-to-circle transitions, star/cookie shapes. | Stable 1.1.0 (2025-10-22) was the newest stable seen; none newer on the release page (last updated May 2026). Pure Kotlin geometry, small. | AndroidX Apache 2.0 (general knowledge). | ADOPT (S). Drive `Morph` progress with `Animatable`; avoid per-frame allocation. |
| "Ink" API (Android 14+) | Stylus drawing. | No drawing-game use case; above minSdk 26. Status/version: UNVERIFIED. | n/a | SKIP. |
| Particle systems | Confetti, sparks, dust, capture bursts. | Existing `ParticleBurst` is already physics-integrated. Fix the idle loop (audit 3.4) first. | Own code. | KEEP and extend (S); no library needed. |
| AGSL (RuntimeShader) | Specular sheen, felt grain, glow, scanlines, mesh gradient, vignette: the "material" layer of a pack. | API 33+ only. Official docs give no complexity limits; one third-party blog quotes ~3-5% GPU for one shader on a Pixel 6 (anecdotal). The repo's own audit shows the real danger is per-frame RenderEffect rebuild, not shader cost. | Platform. | KEEP as the "premium" tier of each pack, always with a flat fallback; fix 3.1/3.3 first. |
| Spring/motion system | Personality of each pack. | Compose springs are cheap. `MotionScheme.standard()/expressive()` exist in material3 (default/fast/slow, spatial vs effects specs), experimental opt-in; the Androidify post used a 1.4.0-alpha artifact. Repo BOM 2024.09.00 (material3 ~1.3) very likely predates `MotionScheme` (UNVERIFIED: test by bumping the BOM on a branch). | AndroidX Apache 2.0. | Do NOT wait for Material motion tokens. Define an app-owned `MotionSpec` (dampingRatio, stiffness, duration, easing, overshoot) per pack, exposed via `LocalMotionSpec` next to `LocalReducedMotion`. |
| Reduced motion | Existing `LocalReducedMotion`, `LocalEnhancedAnimations`, `GameSpeed`. | Cheap. | n/a | Extend: every pack declares a reduced variant (no wobble, parallax or shimmer; single static glow; transitions become crossfades <=150 ms). Lottie/sprite hero animations render a static poster/last frame when reduced. The settings doc already plans to follow the system animation setting by default. |

Bottom line: Canvas + AGSL + `MotionSpec` + `ParticleBurst` + graphics-shapes covers every pack in section 6 with near-zero asset bytes. Lottie is the only extra runtime worth considering, for authored hero/result animations.

---

## 3. Licence matrix

"Commercial" = may ship in a paid/ads app. "SA" = share-alike/copyleft on derivatives. "Kids risk" = concern for Play Families/children's content. UNVERIFIED means no authoritative snippet was seen.

| Source | Licence | Commercial | Attribution | SA | Kids risk | Notes |
|---|---|---|---|---|---|---|
| Own code-drawn art / own SVG / own Lottie | Owner copyright | Yes | No | No | None (we control content) | Preferred. Keep a provenance line in each pack manifest. |
| Kenney (kenney.nl) | CC0 per Kenney's support page (verified); crediting optional; Kenney logo reserved | Yes | No (optional) | No | Low | Mostly 2D sprites/UI/audio. Pixel/cartoon look may clash with tabletop packs. Re-check each pack page. |
| Quaternius | CC0 per third-party coverage (his own site UNVERIFIED) | Yes | No | No | Low | Mostly 3D low-poly; little 2D use. Skip unless a 3D decision is made. |
| Poly Haven | CC0 per official FAQ/licence page (verified); cannot claim authorship or re-license | Yes | No (courtesy) | No | Low | HDRIs/textures/3D. Wood/fabric textures could feed Wood and felt, but 2K+ textures are large: downscale to WebP or prefer procedural grain. |
| OpenGameArt | Per asset: CC0, OGA-BY, CC-BY, CC-BY-SA, GPL/LGPL (site FAQ + forum; partly verified; accepted-licence list on Wikipedia may be outdated) | Yes if terms met | Yes except CC0 | CC-BY-SA, GPL: yes | Medium | Mixed provenance; uploader labels can be wrong. Accept only CC0 / CC-BY 4.0 / OGA-BY. Reject GPL, CC-BY-SA, NC, ND. |
| itch.io packs | Per pack, chosen by seller (UNVERIFIED in this pass) | Varies | Varies | Varies | Medium | Many "free" packs are personal-use only or forbid redistributing source art inside an app. Save the licence text/screenshot in `docs/licences/`. |
| LottieFiles free (Lottie Simple License) | Help centre: free public animations usable commercially incl. apps; attribution not required but encouraged and must be visible if given; some creators set their own CC terms. One third-party article claims attribution required and edits stay under the same licence (UNVERIFIED; conflicts with the official statement). Full licence text not retrieved. | Yes (per official help) | Encouraged / creator-specific | Possibly for edits (UNVERIFIED) | Medium | Licence is per animation. Paid animations follow plan terms. The owner's "original art" rule argues against using these for core art. |
| Rive Marketplace | CC BY (Rive docs, verified) | Yes | Yes | No | Medium | Plus a paid Rive plan to export (section 2). |
| Material Symbols / Icons | Apache 2.0 (Google developer guide, verified); attribution welcomed, not required | Yes | No (NOTICE entry recommended) | No | Low | No third-party logos included. Safe default for UI icons; ship only needed vectors, not the icon font. |
| Fluent UI System Icons | MIT (listings; official LICENSE not opened: UNVERIFIED) | Yes | Keep MIT notice | No | Low | Put copyright notice in NOTICE. |
| Phosphor Icons | MIT, (c) Phosphor Icons (listings; official file not opened: UNVERIFIED) | Yes | Keep MIT notice | No | Low | Six weights; good duotone/Storybook look. |
| Google Fonts (OFL) incl. Baloo 2, Sora, Inter | SIL OFL 1.1 for OFL families. Google FAQ: commercial use allowed, including within a product that is sold; the font cannot be sold standalone; derivatives stay OFL; modified fonts must be renamed where a Reserved Font Name applies | Yes | Ship the OFL.txt/notice with the font | Only for modified fonts | Low | That Baloo 2, Sora and Inter are OFL is general knowledge, not confirmed in snippets: UNVERIFIED, read each `OFL.txt`. Bundle static Latin subsets (size ~30-90 KB per weight is an estimate, UNVERIFIED). Alternative: Downloadable Fonts (needs offline fallback). |
| Freesound | Per sound: CC0, CC BY, CC BY-NC; legacy Sampling Plus (verified) | CC0 and CC BY only | CC BY yes | No | Low | Never accept CC BY-NC. Record sound id, author, URL. |
| Commissioned art | Contract (work-for-hire or exclusive licence) | Yes if contract says so | No | No | None if brief controlled | Preferred third-party route. Contract: full commercial rights, no undisclosed AI content, no trademark look-alikes, source files delivered. |
| AI-generated art / 3D | Tool terms vary (UNVERIFIED) | Tool-dependent | Tool-dependent | n/a | Medium-high | US Copyright Office Part 2 (Jan 2025, via law-firm summaries): purely AI-generated output is not copyrightable, prompts alone are insufficient, human selection/modification may be protected case by case. So we may be unable to stop copying, and training-data provenance and look-alike risk remain. Google Play's AI-Generated Content policy targets safety of generated output and in-app reporting for apps that generate content; no copyright rule seen. |

### Policy overlay (Google Play Families)

- Families policy applies when children are a target audience; non-compliance can mean removal or suspension; child-audience apps must use certified ads SDKs, no personalised ads, no ads causing accidental clicks (support.google.com Families pages, snippet level; the live 2026 text was not retrieved, so re-read before release).
- For art: bundle every asset locally. Forbid runtime-loaded animations from any CDN (for example LottieFiles URLs). Lottie/Rive are local renderers but a remote fetch would be third-party data flow in a kids' app.
- Add a merge checklist item: "does this resemble a trademarked character or game?"

### AI-generated art (cautious summary)

Do not ship raw AI-generated art as game assets. If used at all, keep it to private concept sketches that are redrawn in code or by a human, record tool and terms, and never prompt with trademarked names or styles. Owner decision D5.

---

## 4. Third-party free assets: the open question

- Option A (recommended default): zero third-party art. All art drawn in code or in-house vector; only icons (Material Symbols, Apache 2.0) and fonts (OFL) come from outside. Cost: more developer time; attribution burden is a short NOTICE.
- Option B: allow CC0 only (Kenney, Poly Haven, CC0 OpenGameArt/Freesound). No attribution duty or copyleft; consequences: provenance logging, visual inconsistency, recognisable stock look, small risk of mislabelled uploads. Credit voluntarily anyway.
- Option C: allow CC0 + CC-BY (OpenGameArt CC-BY/OGA-BY, Freesound CC BY, Rive Marketplace, LottieFiles with credit). Consequences: mandatory Credits screen with per-asset author/title/source/licence, an ongoing liability if a source disappears or changes terms, and extra scrutiny in a kids' app.
- Never allowed under any option: NC, ND, SA/GPL, "personal use only", fan art or ripped assets, any Nintendo or other trademarked IP.

---

## 5. Attribution plan

1. Credits screen (Settings > About > Credits), generated from `assets/credits.json` (name, author, source URL, licence short name, licence URL, files used, date added). Groups: Fonts, Icons, Sounds, Art, Libraries. Plain text, TalkBack-readable, links open externally. Required even under Option A (fonts, icons, libraries).
2. `NOTICE` at repo root plus `docs/licences/` holding each licence text and a saved copy of the source page at download time. Entries: OFL.txt per bundled font, MIT notices for Phosphor/Fluent, Apache 2.0 notices for Material Symbols and AndroidX/Lottie/graphics-shapes.
3. Library licences generated from the Gradle dependency report (or the Google OSS Licenses plugin; current status UNVERIFIED).
4. Per-pack manifest `provenance` listing every non-original asset. A CI test fails if a file under `res/` or `assets/` has no manifest row.
5. Sound credits table; confirm provenance of the existing 4 WAVs and the music library (UNVERIFIED in this pass).
6. Review gate for any asset: licence screenshot, "no trademarked likeness" tick, "kids-safe" tick.

---

## 6. Proposed 2D style packs

Common architecture:

- `StylePack` = data (palette tokens, shape tokens, texture recipe ids, `MotionSpec`, sound palette id, haptics intensity) + small draw interfaces (`BoardSurface`, `PieceFace`, `CardFace`, `DieFace`, `BackgroundLayer`, `ButtonChrome`, `ChipChrome`, `ResultBanner`).
- Selected by a global Settings > Look switch with optional per-game override; provided via `LocalStylePack` next to `LocalGameAccent`.
- Game-critical colours (UNO suits, domino pips, chess sides) are never replaced by a pack; packs may add shapes/outlines/textures around them and must keep contrast >= 3:1.
- Tier 0 (flat; API 26+, power-save, low-RAM, reduced motion), Tier 1 (gradients/shadows), Tier 2 (AGSL on API 33+).
- A reduced-motion variant is mandatory per pack.

| Pack | Palette tokens | Shapes | Textures | Motion personality | Sound palette | Asset budget |
|---|---|---|---|---|---|---|
| Classic flat (today) | Current Classic/Dark Material 3 schemes + felt-green accent | Rounded rects 12-16 dp, circles | None | Standard: tween 150-250 ms, FastOutSlowIn, no overshoot; springs ~0.8 damping | Current WAV set | 0 KB new |
| Paper craft | Off-white `#F6EFE3`, kraft `#C9A66B`, 4-5 saturated paper accents; shadow ink 18-28% | Die-cut rounded polygons (graphics-shapes), 2-3 stacked layers | Procedural paper fibre noise, fold creases, layered shadows at 2/6/12 dp | "Slide and settle": spring damping ~0.65, low-medium stiffness, 8-10% overshoot, shadow grows on lift | Soft paper rustle/tap (pitch-shifted existing card sounds) | 0-60 KB |
| Neon arcade | `#0B0F1A` base, cyan `#22E6FF`, magenta `#FF3DB8`, amber `#FFC83D`; glow = hue at 35% alpha, 8-16 dp blur | 2 dp outlines, chamfered corners | Scanlines (AGSL or tiled 1 px lines), layered-stroke glow (Tier 1) or shader (Tier 2), vignette | Snappy: tween 90-140 ms, hit-pause 40-80 ms, flicker on win; fully off under reduced motion | Synth blips | 0-20 KB |
| Storybook kids | Cream `#FFF4DD`, sunny `#FFC94A`, sky `#6EC6FF`, leaf `#7ED957`, berry `#FF6F91`; deep-brown text; Baloo 2 (verify OFL) | Blobby wobbly polygons (graphics-shapes Morph), 28 dp radii, hit targets >= 56 dp | Hand-drawn wobble: static-seed low-frequency path jitter, crayon grain tile | Bouncy: damping ~0.5, ~15% overshoot, idle breathing +-2% (off under reduced motion), nothing flashing > 3 Hz | Marimba/pops, no startling peaks, no fail buzzer | 60-120 KB |
| Wood and felt | Felt `#1F6B45`, burgundy `#6D1F2F`, walnut `#5A3A24`, brass `#B08D3C`, cream pieces | Bevelled rounded rects, circular pits, inset rims | Existing `FELT_GRAIN_AGSL` (Tier 2) with flat fallback; banded-noise wood grain; inner shadows; brass highlights | Weighty: damping 0.9, tiny overshoot, 220-320 ms settle, "thunk" haptic | Wood clacks, felt thuds, card slaps (extend current WAVs) | 0-150 KB |
| High contrast | Black/white/yellow or AAA 7:1 pairs; state conveyed by shape + text, not colour alone | 3-4 dp borders, simple geometry, shape badges (square/circle/diamond/triangle per settings doc) | None; solid fills, optional hatch for zones | Minimal: <=120 ms, no wobble/overshoot/parallax, no particles or shaders | Clear distinct tones; haptics emphasised | 0 KB |

Surfaces each pack must restyle:

| Family | Surfaces |
|---|---|
| Board games (checkers, chess, mancala, go...) | board, grid lines, pieces/seeds, legal-move highlights, last-move marker, captured trays |
| Cards (UNO, solitaire, hearts...) | card frame, card back, deck/discard, hand fan, suit badges (never recolour suits) |
| Dice/party (Party Toolkit) | die body, pips/faces, roll tray, roll animation |
| Word/puzzle (word tiles, crossword, nonogram, sudoku) | tile, cell, selection, found/solved states |
| Arcade/real-time (air hockey, breakout, tower defence) | playfield, entities, effects, trails |
| Chrome (all) | background, buttons, chips, dialogs, top bars, result/victory screens, Home tiles |

The per-game skins already planned in the settings doc generalise into this one switch with per-game override.

---

## 7. Recommendation (which tech for which job)

| Job | Technology |
|---|---|
| Boards, pieces, cards, dice, backgrounds, buttons | Compose Canvas procedural, token-driven by `StylePack` |
| Materials (felt grain, specular, glow, scanlines, vignette) | AGSL Tier 2 with flat Tier 0/1 fallback |
| Shape personality (wobble, morph chips, pill-to-circle) | `graphics-shapes` 1.1.0 |
| Motion personality | App-owned `MotionSpec` + Compose springs (align with `MotionScheme` later, optional) |
| Confetti/sparks/capture | Existing `ParticleBurst` (fix idle loop first) |
| Hero result animations | Lottie, only if owner approves; in-house authored; <=30 KB each; local only; static poster under reduced motion |
| Icon micro-morphs | Compose `Animatable`; AVD only if an artist supplies them |
| Interactive character | Rive: not now |
| Pixel/painted styles | Sprite-sheet atlas, only if such a pack is greenlit |
| Fonts | OFL families as static Latin subsets (Baloo 2 for Storybook; Inter/Sora for chrome) after reading each OFL.txt |
| Icons | Material Symbols (Apache 2.0); optionally Phosphor (MIT) for Storybook |

Prerequisites from `docs/audit/02-performance.md`: 3.1 (gate the Home mesh gradient; add power-save/low-RAM checks), 3.2 (State reads in draw), 3.3 (`specularSweep` re-creation; Air Hockey puck unconditional), 3.4 (`ParticleBurst` idle loop), 3.7 (hoist brushes/paths into `drawWithCache`).

## 8. Effort estimates

S = up to ~2 days, M = ~1-2 weeks, L = over 2 weeks (rough, not calibrated to team velocity: UNVERIFIED).

| Item | Effort | Notes |
|---|---|---|
| Perf prerequisites (3.1-3.4, 3.7) | M | Mechanical but wide |
| `StylePack` core + `LocalStylePack` + `MotionSpec` + Settings > Look + persistence + reduced variants | M | Foundation |
| Classic flat (extract today's look into tokens) | M | Touches many screens/tests |
| High contrast pack | M | Overlaps the existing High Contrast named theme; shape badges across games |
| Paper craft | M | |
| Neon arcade | M | |
| Wood and felt | M | Builds on Felt Table + `FELT_GRAIN_AGSL` |
| Storybook kids | L | Wobble art, font, kid-safe sounds, big targets, review |
| graphics-shapes adoption | S | |
| Lottie adoption (dependency, wrapper, reduced gate, 2-3 animations) | S-M | Plus authoring per animation |
| Rive adoption | L | Not recommended |
| AVD / Compose vector animation | S | Low value |
| Credits screen + NOTICE + CI provenance check | S-M | Do first regardless of option |
| Sprite-sheet infrastructure | M | Only for pixel/painted packs |

Across ~80 games the main cost is moving each surface to read from `LocalStylePack`; roll out family by family with a screenshot test per pack per family.

## 9. Size and performance budgets (proposed, not yet measured)

- Per-pack assets <=150 KB; all packs together <=500 KB; Lottie total <=150 KB; fonts <=300 KB.
- Whole programme without Lottie/Rive should add < 1 MB (today: `res` 1.9 MB, `assets` 3.9 MB).
- Frame budget: 8.3 ms at 120 Hz, 11.1 ms at 90 Hz. Pack decoration may use <= 25% of it. Baseline via `dumpsys gfxinfo` as in `NavRotationTestSupport`. Idle board screens do zero per-frame work.
- At most one full-screen AGSL layer per screen; none on real-time game screens unless measured.
- Reduced motion, power-save and `isLowRamDevice` force Tier 0.
- No decoded bitmap > 1 MB per surface; atlases lazy-loaded per game.

## 10. Risks

1. Visual regression explosion (6 packs x ~80 games): mitigate with token-driven surfaces and per-family screenshot tests.
2. Multiplying existing perf debt.
3. API 33 gating: below 33 the premium look degrades; each pack must look good at Tier 0.
4. Licence drift of third-party assets and OFL notices: provenance CI check.
5. Trademark look-alikes (cards, UNO-like, classic board looks): reviewer checklist.
6. Kids' safety: no runtime-fetched content, no flashing, no scary sounds, targets >= 48 dp (56 dp for Storybook).
7. Accessibility regressions (contrast, colour-only state): contrast unit test on tokens plus the High contrast pack.
8. Rive/Lottie lock-in and cost (Rive export now paid; plan names/prices unclear).
9. Settings sprawl: one global Look switch plus per-game override only where it matters.
10. Material motion tokens are experimental and probably need a BOM upgrade; do not couple to them.

## 11. Decisions the owner must make

- D1. Third-party assets: Option A (none), B (CC0 only) or C (CC0 + CC-BY with Credits)?
- D2. Which packs, in what order? Suggested: Classic flat extraction, Wood and felt, High contrast, Neon arcade, Paper craft, Storybook.
- D3. Approve Lottie for in-house hero animations, or stay Canvas-only? Confirm Rive "not now".
- D4. Fonts: bundle Baloo 2/Inter/Sora (OFL) or stay on system fonts?
- D5. AI-generated art: ban for shipped assets, or allow only as redrawn reference?
- D6. Commission an artist for Storybook? Listing prices seen (self-published, not a verified market average, UNVERIFIED): about $2-10 for tiny sprites/tiles, ~$10-30 for icons or single characters with commercial rights, UI design $100-1,200 per page by seniority, hourly $30-100+. Require commercial rights, source files, no-AI clause, no-lookalike clause.
- D7. Are paid pack unlocks part of the plan? It touches Families monetisation rules, so decide before design.
- D8. Minimum visual tier on API 26-32 and low-RAM devices: Tier 0 only, or Tier 1 allowed?
- D9. Sound palette: reuse existing WAVs, create original audio in the Rust audio core, or source CC0/CC BY?

---

## Sources used

- Lottie Android changelog: https://github.com/airbnb/lottie-android/blob/master/CHANGELOG_COMPOSE.md
- Lottie Android performance: https://airbnb.gitbook.io/lottie/android/performance
- dotLottie Android: https://developers.lottiefiles.com/docs/dotlottie-player/dotlottie-android/ and https://lottiefiles.com/blog/working-with-lottie-animations/getting-started-with-dotlottie-player-android
- Rive pricing/blog: https://rive.app/blog/rive-s-new-9-mo-plan , https://rive.app/pricing , https://rive.app/docs/account-admin/pricing.md
- Rive Android on Maven: https://central.sonatype.com/artifact/app.rive/rive-android/11.12.1
- Rive Marketplace licence: https://www.rive.app/docs/community/marketplace-overview
- graphics-shapes: https://developer.android.com/jetpack/androidx/releases/graphics and https://developer.android.com/develop/ui/compose/graphics/draw/shapes
- Animated vectors in Compose: https://developer.android.com/develop/ui/compose/animation/vectors
- MotionScheme: https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme and https://android-developers.googleblog.com/2025/05/androidify-building-delightful-ui-with-compose.html
- AGSL: https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl
- LottieFiles licensing: https://help.lottiefiles.com/hc/en-us/articles/900002438343-Can-I-use-a-free-animation-on-Lottiefiles-for-commercial-business-use , https://help.lottiefiles.com/commercial-use-guide , https://lottiefiles.com/free-animations/licence
- Kenney: https://kenney.nl/support
- OpenGameArt FAQ: https://opengameart.org/content/faq
- Poly Haven: https://www.polyhaven.com/license and https://docs.polyhaven.com/en/faq
- Quaternius (third party): https://gamefromscratch.com/quaternius-free-3d-assets/
- Freesound: https://freesound.org/help/faq and https://creativecommons.org/2011/09/12/celebrating-freesound-2-0-retiring-sampling-licenses/
- Material Symbols: https://developers.google.com/fonts/docs/material_symbols and https://github.com/google/material-design-icons
- Phosphor / Fluent listings: https://raw.githubusercontent.com/phosphor-icons/core/main/README.md , https://composables.com/icons/icon-libraries/fluentui-system-icons
- Google Fonts FAQ / OFL: https://developers.google.com/fonts/faq , https://openfontlicense.org/ofl-fonts/
- Google Play Families: https://support.google.com/googleplay/android-developer/answer/9893335 , https://support.google.com/googleplay/android-developer/answer/12918983
- Google Play AI-Generated Content: https://support.google.com/googleplay/android-developer/answer/14094294
- US Copyright Office AI Part 2 summaries: https://www.jonesday.com/en/insights/2025/02/copyrightability-of-ai-outputs-us-copyright-office-analyzes-human-authorship-requirement , https://btlaw.com/en/insights/alerts/2025/copyright-office-says-ai-generated-works-based-on-text-prompts-are-not-protected
- Art outsourcing prices: https://rocketbrush.com/blog/game-art-outsourcing-prices-complete-pricing-breakdown , https://rocketbrush.com/blog/2d-character-art-prices-and-tips-to-cut-costs
- Repo: `app/build.gradle.kts`, `app/src/main/java/com/gamesuite/ui/effects/{PremiumShaders,ParticleBurst,CameraShake}.kt`, `settings/LocalReducedMotion.kt`, `settings/LocalEnhancedAnimations.kt`, `haptics/Haptics.kt`, `docs/SETTINGS_THEMING_ACCESSIBILITY.md`, `docs/audit/02-performance.md`.
