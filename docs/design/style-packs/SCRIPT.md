# SCRIPT: the staged plan

Roles: **OWNER** (decisions, art approval, device passes), **ENG** (Kotlin/Compose agents), **ART** (vector/Blender/sound work, agent or human),
**QA** (adversarial/accessibility passes, owner device kit). Process rules inherited from the repo: own worktree, path-scoped commits only when asked,
no bare stash, one Gradle build per checkout, no APK installs by agents.

## Part A. Numbered stages

| # | Phase | Deliverables | Acceptance tests | Who |
|---|---|---|---|---|
| 0 | Decisions | Owner answers D1-D8 (end of this file); R-A/R-B read and mapped to pack briefs | Decision log committed in this folder | OWNER |
| 1 | Prerequisites (scorecard R1) | GameChrome v2 `overlayOpen`, shared lifecycle frame clock, recomposition fixes in worst 8 screens, `drawWithCache` for static toy-sport art, hit-slop helper | existing tests green; idle recomposition counts ~0 on Shogi/Hex/Ludo/Shooting Gallery (owner kit measure) | ENG |
| 2 | Core (pure Kotlin) | `style/` types, `StyleResolver`, `StyleRegistry`, `DeviceCaps` + `detect()`, `resolveQuality()`, migration mapper, `StyleNeeds` | JVM: precedence table tests; fallback totality for all Surface x Tier x caps; property tests (never throws, never above request, `reducedMotion => no tilt/parallax/shake`); migration tests | ENG |
| 3 | Wiring + Classic | `LocalStylePack` at MainActivity beside existing locals; derived old locals; Classic pack adapters for current renderers; per-profile prefs keys | zero visual diff (screenshot goldens if D6, otherwise manual owner kit comparison on 3 games); all existing tests green; first-frame has no flip | ENG |
| 4 | Settings UI | Style screen (3 pickers, preview, reasons), strings EN/ES, a11y (TalkBack labels, 48 dp, large font), parent-gated for kid profiles | UI logic unit tests; `design:accessibility-review` pass; lint test: no hard-coded English in the new screens; ES strings complete | ENG, QA |
| 5 | Shared kit: Soft 2.5D | unified `StandardCardFace` + `CardRenderer`, board materials, dice via `DieRenderer` (wrap `drawDie3D`), chrome, celebration via shared clock | card unification: all six renderers' suits/ranks identical in goldens or owner review; colour-blind shapes present in every style; budgets met (Balanced 5 ms) in owner kit | ENG, ART |
| 6 | Pilots | Reversi (board+pieces), Solitaire (cards), Pig Dice (dice), Mancala, one kids game on Soft 2.5D | per-game quality gate (Part D) | ENG, QA |
| 7 | Per-game override + kid rules | Hub "Look" row, `KidControls` style fields, ParentGate wiring, Storybook pack v1 | kid filter tests; parent gate needed to change/download; kid-safety review signed; Calm default for 4-6 | ENG, OWNER |
| 8 | Full 3D sprite path | Blender turntable pipeline, optimised sprite strips, `:style_full3d` PAD pack, dice + cards + 6 piece sets, `PackStore` + states | size gate green; manifest/hash tests; fallback verified when pack absent/FAILED; offline works once READY; High/Balanced/Battery saver tier resolutions tested | ART, ENG |
| 9 | Family rollout | G3 cards, G1 board, G6 party/kids, G4, G5, G2 in that order (SCALE section 6) as parallel agent batches, one family = one PR | per-game gate; scorecard re-score for feel/a11y axes; no family merges with red tests | ENG, QA |
| 10 | Hardening | measurement report, `style-budgets.json` calibrated, debug HUD, self-run device kit, release notes | numbers against SCALE budgets; owner device pass (tablet + Fold) | OWNER, QA |
| 11 | (optional) GLB/engine | go/no-go on D3, spike behind the same `DieRenderer`/`PieceRenderer` interfaces | spike must beat sprite path on quality at equal size and frame cost, or stays out | OWNER, ENG |

Parallelism: stages 2 and 5/8 art work can run together; stage 9 batches run in parallel agents after stage 6 proves the pattern (same approach as the Reversi pilot rollout).

## Part B. Settings UI script (EN / ES)

Entry: Settings -> "Look and feel" (new section, above "Accessibility"); also reachable from a game's Hub "Look" row and from GameChrome's menu. Kid profile: whole section behind ParentGate.

### Screen B1: Look and feel (global)

| Element | EN | ES |
|---|---|---|
| Title | Look and feel | Aspecto y animaciones |
| Intro | Choose how your games look and move. You can change this any time. | Elige cómo se ven y se mueven tus juegos. Puedes cambiarlo cuando quieras. |
| Section: Art style | Art style | Estilo artístico |
| Option Classic | Classic: clean and flat | Clásico: limpio y plano |
| Option Soft 2.5D | Soft 2.5D: gentle depth and shadows | Suave 2.5D: profundidad y sombras suaves |
| Option Full 3D | Full 3D: shiny solid pieces and dice | 3D completo: piezas y dados sólidos y brillantes |
| Option Storybook | Storybook: friendly, colourful, for kids | Cuento: amigable y colorido, para niños |
| Section: Motion | Motion | Movimiento |
| Calm | Calm: minimal movement | Tranquilo: movimiento mínimo |
| Standard | Standard: smooth, weighted moves | Normal: movimientos suaves y con peso |
| Lively | Lively: bouncy, with extra flair | Animado: con rebotes y más efectos |
| Section: Quality | Quality | Calidad |
| Auto | Auto: best for this device | Automática: la mejor para este dispositivo |
| High | High: best looks, uses more battery | Alta: mejor aspecto, usa más batería |
| Balanced | Balanced: smooth and efficient | Equilibrada: fluida y eficiente |
| Battery saver | Battery saver: simplest visuals | Ahorro de batería: gráficos más simples |
| Reason chip: Reduced Motion | Calm motion is on because Reduced Motion is on. | El movimiento tranquilo está activo porque el movimiento reducido está activado. |
| Reason chip: battery saver | Using simpler visuals because battery saver is on. | Se usan gráficos más simples porque el ahorro de batería está activado. |
| Reason chip: thermal | Using simpler visuals while your device cools down. | Se usan gráficos más simples mientras el dispositivo se enfría. |
| Reason chip: pack missing | Download needed for Full 3D. Using Soft 2.5D for now. | Se necesita una descarga para 3D completo. Por ahora se usa Suave 2.5D. |
| Reason chip: colour-blind | Shape markers are always shown in colour-blind mode. | Las marcas de forma siempre se muestran en el modo para daltónicos. |
| Preview label | Preview | Vista previa |
| Preview a11y | Live preview of a board, a card and a die in the chosen style. | Vista previa en vivo de un tablero, una carta y un dado en el estilo elegido. |
| Button: per-game | Choose a look for each game | Elegir un aspecto para cada juego |
| Button: reset | Reset to defaults | Restablecer valores predeterminados |

### Screen B2: Download (only when a pack is needed)

| Element | EN | ES |
|---|---|---|
| Title | Get Full 3D? | ¿Descargar 3D completo? |
| Body | This adds shiny 3D dice and pieces. Download size: %1$s. It works offline afterwards and you can remove it any time. | Esto añade dados y piezas 3D brillantes. Tamaño de descarga: %1$s. Funciona sin conexión después y puedes quitarlo cuando quieras. |
| Wi-Fi note | Downloads over Wi-Fi only unless you choose otherwise. | Solo se descarga con Wi-Fi, salvo que elijas otra cosa. |
| Buttons | Download / Not now | Descargar / Ahora no |
| Progress | Downloading… %1$d% | Descargando… %1$d% |
| Failed | Couldn't download. Your games still look great. Try again later. | No se pudo descargar. Tus juegos se siguen viendo bien. Inténtalo más tarde. |
| Remove | Remove Full 3D (frees %1$s) | Quitar 3D completo (libera %1$s) |

### Screen B3: Per-game look (Hub row "Look")

| Element | EN | ES |
|---|---|---|
| Row title | Look | Aspecto |
| Default | Use app default | Usar el valor de la app |
| Row value | %1$s, %2$s | %1$s, %2$s |
| Not available | Not available for this game | No disponible para este juego |

### Screen B4: Parent controls (kid profile)

| Element | EN | ES |
|---|---|---|
| Section | Looks for %1$s | Aspectos para %1$s |
| Lock | Don't let %1$s change the look | No permitir que %1$s cambie el aspecto |
| Allowed | Allowed looks | Aspectos permitidos |
| Downloads | Allow downloads for %1$s | Permitir descargas para %1$s |
| Kid note | Looks marked "kid-safe" only. | Solo aspectos marcados como "aptos para niños". |

Copy rules: no jargon ("AGSL", "shader") on screen; every preference has a visible effect; every automatic downgrade shows a reason chip; strings in `values/` and `values-es/` only (a lint test bans hard-coded English per scorecard finding 1). ES copy above is a first draft for a native-speaker review.

## Part C. Asset pipeline script

Goal: repeatable, size-gated production of original assets. All scripts in `scripts/` are DRAFTS, commented, never executed from this work.

### C1. 2D vector: SVG -> Compose
1. Author in an editor that exports clean SVG (paths only; no filters, masks, blend modes; viewBox 0 0 24..512 integer grid).
2. Convert to Compose `ImageVector` (Kotlin code, resolution independent, tintable) or Android Vector Drawable XML (`res/drawable`) for simple icons. Rules and the tool options (Android Studio Vector Asset import, third-party svg-to-compose converters: UNVERIFIED versions) are in `scripts/svg_to_imagevector.md`.
3. Prefer drawing in Compose `Canvas` (as the repo does today) for pieces with state-dependent shapes; use ImageVector for static ornament, icons, suit glyphs.
4. Gate: no raster inside vectors, path count and file size budgets, colours as theme tokens not literals (so High Contrast works), shapes carry colour-blind variants.

### C2. 3D: Blender -> glTF -> optimised GLB (only needed for stage 11 or to feed sprite rendering)
1. Model low-poly (targets: die < 2k tris, piece < 3k, card quad), single UV set, PBR metal-rough, textures <= 512 px for props, <= 1024 px for hero boards.
2. Export glTF binary (GLB) with Blender's glTF exporter (Apply Modifiers, +Y up). Reference: https://docs.blender.org/manual/en/latest/addons/import_export/scene_gltf2.html (not fetched, UNVERIFIED current URL).
3. Optimise with `scripts/optimize_glb.sh`: mesh quantisation/Draco or meshopt, texture compression to KTX2/Basis or WebP, dedupe, prune. Tool versions UNVERIFIED (gltf-transform, gltfpack, toktx): record versions in the pack manifest when run.
4. Validate with the Khronos glTF validator before packing.

### C3. 3D -> sprite strips (the default Full 3D delivery; no engine)
1. `scripts/blender_render_turntable.py` renders a model to N frames (orbit or settle/tumble) with a transparent background, fixed lighting rig, orthographic or low-FOV camera, per colour variant.
2. Pack frames into a strip/atlas, encode WebP lossless-alpha (or lossy with alpha) at 2-3 density buckets (mdpi-xhdpi-xxhdpi equivalents) to keep size under budget; optional KTX2 only if a decoder exists (UNVERIFIED, avoid for v1).
3. Each strip has a manifest entry: frame count, fps, anchor, shadow offset, colour-blind shape overlay position (the overlay is drawn by Compose on top, never baked in, so it stays crisp and tintable).
4. Compose side: pick frame by progress (`spin` 0..1 in `DieRenderer`), draw with `drawImage` from a cached `ImageBitmap`.

### C4. Texture compression
- Photographic/gradient textures: WebP (lossy with alpha) for Compose bitmaps (universal decode, API 26+ safe for lossless/lossy+alpha: WebP lossy-with-alpha support from API 18). GPU-compressed formats (ETC2/ASTC/KTX2) only if an engine path exists.
- Palette art: PNG-quantised or vector. Noise/grain tiles: 128-256 px tileable, 8-bit.
- Always generate the multi-density set from one master at build time; masters live outside the APK (art repo or `art-src/`, not under `src/main`).

### C5. Size gate in CI
- `scripts/asset_size_gate.py` reads `style-budgets.json` and each pack's `manifest.json` plus the actual file tree; fails on: pack over budget, base growth over budget, unlisted files, missing hashes, missing licence entry, texture over max dimension, wrong format.
- Wire as a CI step before the Gradle build (the existing workflow runs Kotlin tests, Rust tests, relay tests: this is a fourth, fast Python step). Python only stdlib so no setup.
- Reports a markdown table into the CI summary (pack, MB, budget, status).

### C6. Licence ledger
`docs/design/style-packs/LICENSES.md` (to be created in phase 0): one row per asset or tool: origin (own work / CC0 / other), licence, author, URL, date, who approved. No row, no merge (the gate script checks).

## Part D. Quality gate for ANY new style pack (checklist)

A pack is mergeable only when every box is ticked, with evidence linked in the PR.

1. Identity and originality
   - [ ] Original art; no third-party IP likeness (QUALITY-BAR art direction); licence entries for every asset, font, model, sound.
   - [ ] Pack id, audience (ALL_AGES or TEEN_ADULT), parent pack for fallback declared.
2. Reduced Motion mapping
   - [ ] With `LocalReducedMotion` true: no tilt, parallax, shake, loops, flip rotation (cross-fade/snap instead); essential state changes still visible without motion.
   - [ ] OS animation scale 0 resolves the same way. Test: resolver property test + goldens in Calm.
3. Colour-blind safety
   - [ ] Every colour-coded piece/card/tile draws a shape or pattern cue when colour-blind mode is on, at every tier (3D included).
   - [ ] Simulated protan/deutan/tritan views reviewed; no information carried by red/green alone.
4. Text-scale safety
   - [ ] Text in packs scales with `textScale`/system font scale; goldens at 1.0x, 1.3x, 1.5x show no clipping/overlap; card ranks do not use fixed sp.
   - [ ] 48 dp minimum targets preserved; decorative art never shrinks hit areas.
5. Contrast
   - [ ] Palette tokens pass the repo `Wcag` checks (>= 3:1 for pieces/boundaries, >= 4.5:1 text) in light, dark and High Contrast; pieces distinguishable on every board material.
6. Screen reader
   - [ ] Every renderer sets semantics (content descriptions) identically across tiers; announcements unchanged.
7. Kid-safety review (required for ALL_AGES; also advisable for all)
   - [ ] No frightening, violent, gambling-like, or suggestive imagery; no real-brand likenesses; no text needing reading for ages 4-6; no flashing above 3 flashes/s; celebrations bounded.
   - [ ] Downloads gated by parent gate and Wi-Fi default; no network calls, no accounts, no analytics beyond Play delivery.
   - [ ] Reviewer name and date recorded.
8. Performance and size budget
   - [ ] Per-surface cost declared and within the budget for its level (SCALE sections 1-4); zero per-frame allocations in draw path; static layers cached.
   - [ ] Pack size under budget in `style-budgets.json`; size gate green; decoded memory within SCALE section 2.
   - [ ] Idle test: no loop runs when nothing animates; overlay open pauses it.
9. Robustness
   - [ ] Missing/FAILED asset pack falls back flat with a reason; corrupt asset/hash mismatch demotes the surface without a crash.
   - [ ] Works offline once installed; removing the pack mid-session demotes cleanly.
   - [ ] Fold inner/cover, portrait/landscape, dark mode screenshots reviewed.
10. Localisation
   - [ ] Pack name/description in EN and ES; no hard-coded English.
11. Tests
   - [ ] `StyleNeeds`/registry consistency test; fallback totality including this pack; golden images (if D6) at flat/2.5D/3D as applicable.
   - [ ] Code review clean of P1/P2.

## Part E. Owner decisions needed

| # | Decision | Recommended | Needed by |
|---|---|---|---|
| D1 | Approve the three-axis model (Art style, Motion, Quality) and Classic default for adults | Yes, Classic default | Phase 0 |
| D2 | Subsume `card3DEnabled` / `enhancedAnimationsEnabled` into the style system (old keys migrated, then retired) | Yes | Phase 2 |
| D3 | Real 3D engine (Filament/SceneView/GLB) now, later, or never. Prior answer to Filament was "Not this round" | Not now: ship Full 3D as software projection + sprite strips; reassess after v1 | Phase 8 (go/no-go for phase 11) |
| D4 | What `enhancedAnimationsEnabled=false` users migrate to | Motion Standard with halved particles; Calm only if Reduced Motion was on | Phase 2 |
| D5 | Pack download policy: on-demand only, Wi-Fi default, no fast-follow, size cap per pack 100 MB until Play limits re-verified | Yes | Phase 8 |
| D6 | JVM screenshot-test library approval (needed since device installs are off the table) and which to use | Approve one (evaluate Paparazzi vs Roborazzi at decision time) | Phase 3 |
| D7 | Kid-safe pack count and who signs the kid-safety review; may kids ever see a download prompt (parent-approved only) | One pack (Storybook), owner signs, parent-gated | Phase 7 |
| D8 | Art direction for each pack, from R-A/R-B (illustration style, palette, sound palette, who makes assets: agents, owner, or licensed) | Decide after reading R-A/R-B | Phase 0 |
| D9 | Do Arena runs force Calm + flat | Yes | Phase 3 |
| D10 | Should themed seasonal packs and a pack store ever exist | Not planned | Later |
| D11 | Pilot games for Soft 2.5D (proposed: Reversi, Solitaire, Pig Dice, Mancala, one kids game) | Approve | Phase 6 |
| D12 | Device kit: who runs the measurement scenes on tablet + Fold and returns numbers (standing no-install rule) | Owner | Phase 10 |
