# SCOPE: the Style system

## 1. Goal in one sentence

Let a player (or a parent, for a child) choose how the whole suite LOOKS (art style), MOVES (motion level) and PERFORMS (quality),
change it at any time, override it per game, and never lose accessibility or battery to a pretty default.

## 2. User stories

### Adults
- A1. "I want calm flat visuals." Classic flat + Calm motion: no tilt, no shimmer, no confetti storms; the board is the point.
- A2. "I want shiny 3D dice." Full 3D art for the dice surface only (per-game override), everything else Classic. Works in Pig Dice, Yahtzee, Chutes Race, Backgammon, Party Toolkit dice.
- A3. "Battery saver on." Quality = Battery saver (or OS battery saver detected under Auto): no shaders, no idle loops, 30 fps-class effects, flat fallback everywhere heavy.
- A4. "My old phone stutters in Air Hockey." Auto picks Balanced after detecting dropped frames or low RAM; a one-line explanation is shown ("Using Balanced to keep gameplay smooth") with a way to override.
- A5. "I get motion sick." OS animation scale 0 or app Reduced Motion forces Calm, tilt off, no parallax, no camera shake, whatever the style says.
- A6. "I'm colour-blind." Colour-blind mode adds shape/pattern to every colour-coded piece in EVERY style, including 3D (a 3D red die must still carry its pip/shape cue).
- A7. "I want a wood-and-felt table for card games but flat chess." Per-game override of art style.
- A8. "I'm on a Fold." Style resolves per window size class; cover screen defaults to a cheaper tier when small (optional, v1).

### Kids (profile kind KID, age band 4-6 / 7-9 / 10-12)
- K1. "My child gets the storybook style." Kid profiles default to a kid-safe pack (rounded, large, bright, friendly, no dark/scary imagery) regardless of the adult's choice.
- K2. Kids cannot open the style picker (it sits behind the existing parent gate); they see at most a friendly 2-3 tile "pick a look" if the parent allows it.
- K3. Kids never see packs not flagged kid-safe, and never see downloadable packs unless the parent approved the download.
- K4. Calm motion is the default for age band 4-6 (no flashing, no sudden bursts); Lively is allowed 7+ only if photosensitivity checks pass.

### Parents
- P1. "I decide what my child sees." In Parent controls: allowed styles per kid, default style, lock the picker.
- P2. "I don't want a surprise 300 MB download." Downloadable packs ask once, show size, are Wi-Fi-only by default, and are never forced.
- P3. "Show me what it looks like first." Preview card with a 3-second live sample per style before applying.
- P4. "Battery and heat on my kid's tablet." A Kid profile on a low battery or throttled device quietly drops one tier.

## 3. The three preference axes

### 3.1 ART STYLE (what things look like)

| Id | Name (EN) | Technique | Needs |
|---|---|---|---|
| `classic_flat` | Classic | Today's vector Compose drawing, flat fills, minimal depth. Reference tier: every game MUST ship this. | nothing |
| `soft_25d` | Soft 2.5D | Layered vectors with soft shadows, bevel/inner-light gradients, small perspective tilt (`graphicsLayer`), parallax between layers, baked specular. No real 3D. | API 26+, cheap |
| `full_3d` | Full 3D | Real depth: software-projected solids (as `drawDie3D` does today) and/or pre-rendered turntable sprite strips from Blender; optional GLB through an engine in a later phase. | extra assets; GPU class |
| themed packs | e.g. "Storybook" (kids), "Wood and Felt", "Neon Arcade (Midnight)", "Paper Craft" | A theme = a set of surface implementations + palette + sound palette + haptic profile. A themed pack declares which of the three tiers it implements per surface and falls back downward. | per pack |

Rules:
- Classic is a complete fallback for everything. A pack may implement only some surfaces (e.g. a "Dice Deluxe" pack that only has full_3d dice); every other surface falls back (section 5 of ARCHITECTURE).
- Art style is independent of the existing `NamedTheme` (colour palette) and `ThemeMode` (light/dark). A pack may SUGGEST a palette, never force it; High Contrast always wins.
- Pack art is ORIGINAL (QUALITY-BAR: "vector drawn in Compose/AGSL, original; no Nintendo/Mario assets").

### 3.2 MOTION LEVEL (how things move)

| Level | Meaning | Maps from today |
|---|---|---|
| Calm | Reduced motion: fades and snaps, no tilt/parallax/shake/loops, short or no particles. Essential feedback only (highlight, state change). | `LocalReducedMotion = true` |
| Standard | Weighted move arcs, flips, capture fades, short particles, subtle idle. | `LocalEnhancedAnimations = true` (default today) |
| Lively | Standard plus anticipation/overshoot, richer particles, celebrations, idle motion, camera shake on big hits. | new |

Motion level is separate from `GameSpeed` (how fast bots act): GameSpeed scales WAITS, Motion level scales EXPRESSIVENESS. Both stay.

### 3.3 QUALITY (how much the device is asked to do)

| Level | Behaviour |
|---|---|
| Auto (default) | Resolved from capability detection + battery saver + thermal + measured frame health (ARCHITECTURE section 6). Never above the user's chosen art/motion; may only lower. |
| High | Allow the heaviest tier the style offers (AGSL shaders, offscreen effects, 120 Hz-class animation, full particle budget). Warns on low-RAM devices. |
| Balanced | Cap at 60 fps-class effects, no per-frame RenderEffect rebuilds, particle budget halved. |
| Battery saver | Flat or 2.5D static art only, no shaders, no idle loops, particles minimal, no 3D, animation at lowest cost. |

## 4. How the axes combine (precedence, accessibility wins)

Resolution order, strongest first. Each step can only REMOVE capability:

1. Hard safety: kid-safety filter (kid profile cannot resolve to a non-kid-safe pack) and photosensitivity cap.
2. Accessibility: Reduced Motion (app setting OR OS "remove animations") forces Motion = Calm and removes tilt, parallax, shake, looping shimmer, flashing; `LocalReducedMotion` always wins over `LocalCard3DMode`-style flags, exactly as the existing KDoc rules say ("let reduced motion win"). Colour-blind mode forces shape/pattern cues on every colour-coded surface in every style (a style that cannot provide them is not selectable for that game while the mode is on, falling back to Classic for that surface). High Contrast named theme forces opaque, high-contrast surfaces: shadows/gradients stay but text and piece/background contrast must meet the existing `Wcag` checks; decorative textures are dropped. Text scale never reduces below tap-target rules (48 dp).
3. Device/battery: Quality level and Auto detection (OS battery saver, thermal status, low RAM, no AGSL on < API 33).
4. Per-game override (only within what 1-3 allow).
5. User's global choice.

Result: `EffectiveStyle(artPerSurface, motion, quality, reasons)`. `reasons` is user-visible ("Calm motion: Reduced Motion is on") so nothing silently disappears.

Reduced Motion is NOT the same as Calm: Calm is a selectable preference; Reduced Motion is an accessibility force that ALSO strips things Calm keeps (e.g. subtle idle), and it cannot be raised by a per-game override.

## 5. Per-game override

- Each game's Hub (`hub/{gameId}`) options panel gets a "Look" row: "Use app default" (default) / Art style / Motion.
- Override stored per profile per game id (key `style.override.<gameId>`).
- Surfaces a game does not use (a puzzle with no dice) simply ignore irrelevant parts; the picker only offers styles that game actually has (a style exists for a game if its `StyleNeeds` are all satisfiable at some tier).
- Arena (`ArenaHost`) sessions force Calm + flat + lowest quality for speed unless the owner decides otherwise (arena runs at up to 4x/max).

## 6. Kid-profile rules

- Kid profile starts on `storybook` (kid-safe) or Classic; adults' choice is not inherited.
- Style picker, motion picker and downloads sit behind the existing `ParentGate`; per-kid allow-list lives in `KidControls`/Parent controls.
- Only packs with `audience = ALL_AGES` and a recorded kid-safety review are listed. No scary, gory, gambling-like (slot-machine confetti on cards), or dark-pattern visuals; no flashing above the photosensitivity budget.
- Age band 4-6: Calm default, large pieces, no text-dependent cues. 7-9: Standard. 10-12: may be allowed Lively.
- Wordlists/art for kid mode follow the scorecard defect list (kid-safe wordlists are a separate Wave R2 item; this system only governs visuals).
- No analytics or networking beyond the Play-managed pack download; no accounts.

## 7. MVP vs v1 vs later

| Release | Includes |
|---|---|
| **MVP (M1)** | `StylePack` interfaces + `LocalStylePack` + registry + fallback resolver + capability detection (pure Kotlin, JVM-tested). Two packs: Classic (wraps today) and Soft 2.5D for cards + board surface + dice (reusing `Card3D.kt`, `tablePerspectiveTilt`, `drawDie3D`). Settings screen: 3 pickers + live preview. Subsumes `card3DEnabled` and `enhancedAnimationsEnabled` (migration maps old booleans). Motion Calm/Standard/Lively for the shared kit (deck, FannedHand, particles). Quality Auto/High/Balanced/Battery saver (resolver + OS battery saver + low-RAM; thermal listener). No downloads. 4-6 pilot games (Reversi, one card game on the unified deck, Pig Dice, Mancala, one kids game). |
| **v1** | Per-game override, kid rules + parent controls, Storybook (kid-safe) pack, Full 3D dice + cards via software projection and sprite strips, unified deck component across all six card renderers, sound palette + haptic profile per pack, all 14 board games + 12 card games + dice games on surfaces, JVM screenshot tests (if approved), first on-demand asset pack (Full 3D sprite strips), size gate in CI. |
| **Later** | Themed pack store/seasonal packs, GLB + engine path for true 3D (owner decision D3), per-surface mixing UI ("custom look"), pack authoring guide for third parties (probably never), Fold/cover-screen auto tiers, user-imported packs (OUT unless owner asks). |

## 8. Out of scope

- Re-skinning game RULES, board size, layout logic (Edge Match builder etc. untouched).
- Replacing `NamedTheme`/`ThemeMode`/dynamic colour (they remain palette controls).
- A third-party/user-created pack format, a marketplace, paid packs (no IAP in this plan).
- A new game engine for gameplay; Arena logic; networking/multiplayer messaging. Note: style is LOCAL per device; in multiplayer each player sees their own style (nothing about it crosses the wire).
- AR/VR, ray tracing, user-generated art.
- iOS/web ports (the ESP32 cabinet keeps its own palette; `MidnightArcadeScheme` shared identity is unchanged).
- Fixing the scorecard defects themselves (they are prerequisites listed in SCALE; their own program is R1-R4).

## 9. Open questions with recommended defaults

| # | Question | Recommended default |
|---|---|---|
| Q1 | Default art style for new adult installs | Classic (no surprise; keeps today's look). Soft 2.5D offered during first-run as a visible choice. |
| Q2 | Default motion | Standard (matches today's `enhancedAnimationsEnabled = true`), Calm if OS animations off. |
| Q3 | Default quality | Auto. |
| Q4 | Do existing users with `card3DEnabled = true` keep a 3D look? | Yes: migrate to Soft 2.5D (cards + table tilt) not Full 3D, since today's 3D is perspective-only. |
| Q5 | Is a real 3D engine (Filament/SceneView) in scope? | Not in MVP/v1. Full 3D = software projection + sprite strips. Engine only if owner approves D3 after the v1 sprite path is evaluated. |
| Q6 | Where do downloadable packs live? | Play Asset Delivery on-demand packs, installed on explicit request; nothing sideloaded. |
| Q7 | Per-style sound/haptic? | Sound palette and haptic profile belong to the pack but default to the shared set; ship only when audio assets are licensed. |
| Q8 | Kid-safe pack count | One at v1 (Storybook), two at most. |
| Q9 | Does Lively exist on Battery saver? | No: resolver caps at Standard (Balanced) or Calm-equivalent (Battery saver). |
| Q10 | Should changing style apply instantly mid-game? | Yes for in-game open of the picker only after pause (GameChrome v2 overlay), apply at next frame via recomposition; no restart. |
