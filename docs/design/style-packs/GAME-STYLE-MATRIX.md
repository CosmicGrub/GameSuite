# Game-by-game style matrix: 81 games x (Classic flat 2D, Soft 2.5D, Full 3D) x (Calm, Standard, Lively)

Status: PLANNING DRAFT, 2026-11-21. Read-only audit: no source edited, no Gradle, no device, no emulator, nothing run, nothing committed.
Tree: `Z:\GameSuite-wt-game-kit`, branch `session/wave-3` (HEAD 63ec7c3 plus the uncommitted wave-3 working tree). Line numbers below are from that tree and will drift.
Companion data: `game-style-matrix.csv` (same folder, one row per game with every field in full, plus feel score, surface tags and source files). Siblings: `README.md`, `SCOPE.md`, `ARCHITECTURE.md`, `SCALE.md`.

Every claim about how something is drawn today is tagged with a `file:line` (paths relative to `app/src/main/java/com/gamesuite/`). Cost grades come from reading the draw code, not from profiling; treat them as ordering, not milliseconds.

## 0. Legend

- **Style packs (2D)**: PC = Paper craft, NA = Neon arcade, SK = Storybook kids, WF = Wood and felt, HC = High contrast. A `*` means the game already leans that way, so the pack is nearly a palette and shape swap.
- **Art levels**: Classic flat 2D (pack swaps palette, material, glyph shapes); Soft 2.5D (perspective tilt, layered shadows, parallax, thickness, no mesh); Full 3D (extruded or modelled objects: software projection or pre-rendered turntable sprites first, a real engine only if the owner re-opens that bet).
- **Animation levels**: Calm, Standard, Lively. Always below Reduced Motion, which wins everywhere (`settings/LocalReducedMotion.kt`).
- **est_draw_cost**: low = a few primitives or turn-based composables; med = many primitives or one animating Canvas per frame; high = per-frame allocations in the draw lambda, whole-screen recomposition per frame, or a physics loop plus heavy art.
- **3d_value**: how much a 3D presentation would add to how the game plays and reads, not how hard it is. **3d_risk** is performance and build complexity. **Priority**: P1 = pilot candidate (high reach or high payoff and low risk), P2 = follows once shared surfaces exist, P3 = low payoff or blocked.

## 1. What the audit found (read this first)

1. **Only two games contain real 3D today**, and it is one object: the software-projected die `drawDie3D`/`Die3DView` (`ui/PigDiceArt.kt:98,:189`, used by Pig Dice `ui/PigScreen.kt:413` and Chutes Race `ui/SlideClimbScreen.kt:610`). It is a hand-written 3x3 rotation + painter-sorted lit faces on a Canvas, no engine. Everything else called "3D" is a `graphicsLayer` transform.
2. **"3D Perspective Mode" is a single boolean** (`settings/LocalCard3DMode.kt:22`, default false) read by 12 screens. It drives: whole-board `tablePerspectiveTilt` in 8 games (AirHockey, Checkers, Chess, Dominoes, Mancala, SlidingPuzzle, Solitaire, UNO), the AGSL `specularSweep` sheen in 11, and `card3DFlip`/`card3DToss` in 5. Twelve games flip cards or tiles with their own `rotationY`/`rotationX` (Matching `ui/MatchingScreen.kt:522`, Blackjack/War `games/blackjack/CasinoCards.kt:318`, Hold'em `games/holdem/HoldemCardArt.kt:138`, Speed `ui/SpeedScreen.kt:614`, Word Guess `ui/WordGuessScreen.kt:1064`, Six-Ball tray lean `ui/SixBallScreen.kt:426`). This is the seed of 2.5D and should be subsumed, not duplicated.
3. **There is one animation-level precedent**: `CheckersMotionTier { OFF, STANDARD, MAXIMUM }` (`games/checkers/CheckersMotionPrefs.kt:41`), where MAXIMUM adds the sheen and camera shake. Around it sit `LocalEnhancedAnimations` (default true), `LocalGameSpeed` and `LocalReducedMotion`. Calm / Standard / Lively should generalise Checkers' tier.
4. **There are eight separate card renderers for 11 games** (section 3, surfaces CARD_*), not six as the scorecard says (it did not count Matching's and Hanafuda's own art). Only Solitaire and UNO share a component (`games/cards/PlayingCardView.kt:59`).
5. **There are five dice implementations for six dice games**: one 3D (`PigDiceArt.kt`) and four independent flat pip dice (Yahtzee `ui/YahtzeeScreen.kt:522`, Ludo `ui/LudoScreen.kt:562`, Backgammon `ui/BackgammonScreen.kt:498`, Party Toolkit `ui/PartyToolkitScreen.kt:569`). A single die surface would reach all six and the 3D version already exists.
6. **Palettes are private per screen**: 29 games define their own `*Palette` data class inside the screen file (e.g. `ui/ReversiScreen.kt:388`, `ui/ConnectFourScreen.kt:536`, `ui/BackgammonBoardView.kt:58`). `NamedTheme.FELT_TABLE` is a stub that falls back to Classic (`theme/AppTheme.kt:66-68`). A Classic-2D pack system has to lift these into a registry; that is the cheapest way to reach 29 games.
7. **Per-frame cost is dominated by a few patterns**, and a higher art or animation level multiplies them: `rememberParticleBurst()` starts a loop that never stops (`ui/effects/ParticleBurst.kt:137-148`) and 38 screens use it (4 more, Connect Four, Dots and Boxes, Sudoku and Color Flood, build their own demand-driven loop); `specularSweep` re-creates a `RenderEffect` inside `graphicsLayer` every frame (`ui/effects/PremiumShaders.kt:186-197`) and Word Tiles applies it per tile; Bowling, Billiards and Mini Golf allocate Paths and Brushes inside the draw lambda each frame; Air Hockey and Tower Defence recompose the whole screen each frame (scorecard systemic #3). These must be fixed before Lively or Full 3D ships on those screens.
8. **Reversi, 2048 and Kakuro are pure-composable, turn-based screens with no Canvas**, so they are the cheapest places to prove a skin (Kakuro and Reversi also have the least motion today).
9. **11 engine-only wave-3 games have no UI yet** (section 8). Style hooks can be designed in before their screens are written.
10. **Reduced Motion is read by 79 of 81 screens** (grep of `LocalReducedMotion`); Reversi and Draw and Guess are the two that do not reference it, so a style/animation level must be applied as `level.coerceAtMost(CALM) if reducedMotion` at the provider, not by trusting each screen.

Counts: families BOARD 21, CARD 11, WORD 5, ARCADE_PUZZLE 43, TOOLKIT 1. Priority: P1 12, P2 24, P3 45. 3D-value "none": 13 games; "low" or "low-med": 29 games.

## 2. The matrix

Compact view. The full row for each game (surfaces, animation inventory, cost notes with allocations, all three alternatives, risk, notes, source files) is in `game-style-matrix.csv`. Feel is the scorecard score 0-3 (`docs/audit/08-quality-scorecard/ALL-GAMES.csv`), then total /15.

### Board and dice games (21)

| Game | Feel / total | Render tech today | 3D today | Draw cost | 2D packs | 3D value | 3D risk | Kids | Prio |
|---|---|---|---|---|---|---|---|---|---|
| chess | 3 / 11 | Composables + Canvas Path pieces | tilt + flip (gated) | med | WF*, HC, PC (paper-cut silhouettes), NA (glow outlines) | high | med | FAMILY 8+ | P1 |
| checkers | 3 / 13 | Composables + cached wood bitmap | tilt + flip (gated) | low-med | WF*, HC, PC, SK (chunky counters) | high | low-med | KIDS 7+ | P1 |
| reversi | 1 / 10 | Composables only | no | low | all five (SK*, NA*, PC*); skin is a palette+shape swap | med-high | low | FAMILY 8+ | P1 |
| mancala | 2 / 11 | Composables + 2D seed physics overlay | tilt (gated) | med | WF*, SK, PC, HC | med-high | med | KIDS 6+ | P2 |
| connect-four | 2 / 10 | Canvas | no | low | SK*, NA, PC, WF, HC | med | low-med | KIDS 6+ | P2 |
| dots-and-boxes | 2 / 10 | Canvas | no | low | PC* (pencil on graph paper), SK, NA, HC | low | low | KIDS 6+ | P3 |
| tic-tac-toe | 2 / 12 | Canvas | no | low | SK*, PC*, NA, HC | low | low | KIDS 5+ | P2 |
| dominoes | 3 / 10 | Composables + Canvas pips | tilt + flip (gated) | med | WF*, PC, HC, NA | med-high | low-med | FAMILY 7+ | P2 |
| word-tiles | 3 / 13 | Composables (Box+Text) | sheen only (:1359, TILE_GLOS | med | WF*, PC, HC | low-med | med | adults | P3 |
| yahtzee | 2 / 13 | Composables + Canvas pips | no | low | PC*, WF, NA, HC | high | low-med | FAMILY 8+ | P1 |
| backgammon | 2 / 12 | Canvas | no | med | WF*, HC, PC | high | med | adults | P2 |
| gomoku | 3 / 12 | Canvas | no | low-med | WF*, HC, PC, NA | low-med | low | FAMILY 8+ | P3 |
| hex | 3 / 14 | Canvas | no | low-med | HC*, NA, PC, WF | low-med | low-med | FAMILY 8+ | P3 |
| nine-mens-morris | 3 / 15 | Canvas | no | low | WF*, PC, SK, HC | low-med | low | FAMILY 8+ | P3 |
| hare-and-hounds | 3 / 14 | Canvas | no | low | SK*, PC, WF, HC | med | med | KIDS 6+ | P3 |
| chinese-checkers | 3 / 13 | Canvas | no | low-med | SK*, NA, PC, HC | med | low-med | FAMILY 7+ | P2 |
| ludo | 3 / 13 | Canvas | no | med | SK*, PC, NA, HC | high | med | KIDS 5+ | P2 |
| shogi | 3 / 13 | Canvas (vector wedges) | no | med | WF*, HC, PC | med-high | med | FAMILY 10+ | P2 |
| mini-shogi | 3 / 13 | Canvas (vector wedges) | no | med | as Shogi | med-high | med | FAMILY 10+ | P2 |
| pig-dice | 2 / 14 | Canvas, software 3D die | YES (real 3D die) | med | PC, SK, HC; needs a flat pip-die for Classic | high | low | blocked for kids | P1 |
| chutes-race | 2 / 13 | Canvas + software 3D die | YES (real 3D die) | med | SK*, PC, NA | med-high | med | KIDS 4+ | P2 |

### Card games (11)

| Game | Feel / total | Render tech today | 3D today | Draw cost | 2D packs | 3D value | 3D risk | Kids | Prio |
|---|---|---|---|---|---|---|---|---|---|
| uno | 3 / 10 | Composables + Canvas + AGSL | tilt + flip (gated) | high | PC*, NA, SK*, HC | med | high | FAMILY 7+ | P2 |
| solitaire | 2 / 8 | Composables (shared PlayingCardView) | tilt + flip (gated) | med | WF*, PC, HC, SK | low-med | med | FAMILY 8+ | P1 |
| blackjack | 3 / 15 | Composables + Canvas pips | flip transform | low-med | WF*, HC, NA, PC | med | low-med | blocked for kids | P1 |
| war | 2 / 14 | Composables + Canvas pips | flip transform | low | WF*, SK, PC, HC | low-med | low | FAMILY 6+ | P2 |
| texas-holdem | 3 / 14 | Canvas | flip transform | med | WF*, NA, HC | med-high | med | blocked for kids | P2 |
| president | 2 / 13 | Composables + Canvas | no | low-med | WF*, PC, HC | low | low | FAMILY 10+ | P3 |
| sevens | 2 / 12 | Composables + Canvas | no | low | WF*, PC, SK, HC | low | low | FAMILY 8+ | P3 |
| speed | 2 / 10 | Composables + Canvas | flip transform | med | NA*, PC, HC | low-med | med | FAMILY 8+ | P3 |
| matching | 2 / 12 | Canvas procedural art | flip transform | med | SK*, PC*, HC | med | low-med | KIDS 4+ | P1 |
| spider-solitaire | 3 / 15 | Composables (Text glyphs) + Canvas back | no | med | WF*, PC, HC | low | low | FAMILY 10+ | P3 |
| hanafuda | 2 / 13 | Canvas procedural art | no | med | PC*, WF, HC | low | low | blocked for kids | P3 |

### Word games (5)

| Game | Feel / total | Render tech today | 3D today | Draw cost | 2D packs | 3D value | 3D risk | Kids | Prio |
|---|---|---|---|---|---|---|---|---|---|
| hangman | 2 / 8 | Canvas (chalk) | sheen only | low-med | PC*, HC, SK | none | low | adults | P3 |
| word-search | 2 / 10 | Canvas (one surface) | no | med | PC*, HC, SK | none | low | adults | P3 |
| crossword | 2 / 8 | Canvas | no | low-med | PC*, HC | none | low | adults | P3 |
| word-guess | 2 / 10 | Canvas + composables | flip transform | low | SK*, NA, PC, HC (green/yellow/grey needs shape cues) | none | low | adults | P3 |
| draw-guess | 1 / 11 | Canvas | no | med | PC*, SK* | none | low | FAMILY 6+ | P3 |

### Puzzles, arcade, sports and party games (43)

| Game | Feel / total | Render tech today | 3D today | Draw cost | 2D packs | 3D value | 3D risk | Kids | Prio |
|---|---|---|---|---|---|---|---|---|---|
| sudoku | 2 / 10 | Canvas | no | low-med | PC*, HC*, NA | none | low | FAMILY 10+ | P3 |
| kenken | 2 / 10 | Canvas | no | low-med | PC*, HC | none | low | FAMILY 10+ | P3 |
| kakuro | 2 / 11 | Composables (Box+Text) | no | low | PC*, HC | none | low | adults | P3 |
| nonogram | 2 / 10 | Canvas | no | low-med | PC*, SK, HC | none | low | FAMILY 8+ | P3 |
| minesweeper | 2 / 9 | Canvas | no | med | HC*, NA, PC | low-med | med | FAMILY 10+ | P3 |
| lights-out | 2 / 11 | Composables | no | low | NA*, SK, HC | low-med | low | FAMILY 8+ | P3 |
| sliding-puzzle | 2 / 10 | Composables (Box+Text) | tablePerspectiveTilt only (g | low | SK*, PC, HC | low-med | low | KIDS 6+ | P2 |
| color-flood | 3 / 10 | Canvas | no | low-med | SK*, NA, PC, HC | none | low | KIDS 6+ | P3 |
| edge-match | 2 / 12 | Canvas (Paths) | no | med | SK, PC*, NA, HC | none | low | FAMILY 8+ | P3 |
| mastermind | 2 / 13 | Canvas | no | low | SK, PC, NA, WF | low-med | low | FAMILY 8+ | P3 |
| breakout | 2 / 11 | Canvas sim | no | low-med | NA*, SK, HC | med | low-med | KIDS 6+ | P3 |
| tower-defence | 2 / 9 | Canvas sim | no | high | NA*, SK, PC, HC | med | high | FAMILY 10+ | P3 |
| air-hockey | 3 / 7 | Canvas + physics sim | tilt (gated) | high today | NA*, HC, PC | high | high until GameChrome v2/frame clock land | KIDS 6+ | P2 |
| game-2048 | 3 / 13 | Composables only | no | low | NA*, SK, PC, HC | low-med | low | KIDS 8+ | P3 |
| snake | 2 / 12 | Canvas | no | low-med | NA*, SK (caterpillar), PC, HC | low-med | low-med | KIDS 6+ | P3 |
| flow-lines | 2 / 14 | Canvas (Paths) | no | med | NA*, SK, PC, HC | none | low | KIDS 6+ | P3 |
| mahjong-solitaire | 1 / 11 | Composables (tiles + glyph text) | fake depth | med-high | WF*, PC, HC | high | med | FAMILY 8+ | P2 |
| darts | 3 / 14 | Canvas | no | med | HC*, NA, PC, WF | high | med | KIDS 6+ | P1 |
| bowling | 3 / 13 | Canvas, software perspective camera | software perspective | high | WF*, HC, NA, PC | high | high | KIDS 5+ | P1 |
| carrom | 3 / 14 | Canvas + physics sim | no | med | WF*, HC, PC | med-high | med | FAMILY 8+ | P2 |
| curling | 3 / 13 | Canvas + physics sim | no | med | HC*, NA, PC | med-high | med | FAMILY 8+ | P2 |
| billiards | 3 / 14 | Canvas + physics sim | no | high | WF*, HC, NA | high | high | FAMILY 10+ | P1 |
| mini-golf | 3 / 14 | Canvas + physics sim | no | high | WF*, SK, PC, HC | high | high | KIDS 5+ | P2 |
| toy-soccer | 3 / 14 | Canvas sprites + physics | no | med | SK*, NA, PC, WF | med | med | KIDS 5+ | P2 |
| toy-tennis | 2 / 13 | Canvas sprites | no | med | SK*, NA, PC, HC | med | med | KIDS 6+ | P3 |
| toy-boxing | 3 / 12 | Canvas sprites | no | high | SK*, NA, PC | med | high | FAMILY 8+ | P3 |
| toy-baseball | 2 / 11 | Canvas | no | med | SK*, NA, PC | low-med | med | KIDS 6+ | P3 |
| slot-cars | 3 / 14 | Canvas sprites | no | med | NA*, SK, HC | med-high | med | blocked for kids | P3 |
| fishing | 3 / 14 | Canvas sprites | no | med | SK*, PC, HC | med | med | KIDS 4+ | P2 |
| tank-battle | 3 / 14 | Canvas + cached terrain | no | med | NA*, PC, SK | med | high | FAMILY 10+ | P3 |
| shooting-gallery | 3 / 12 | Canvas sprites | no | med | SK*, NA, PC | low-med | med | KIDS 7+ | P3 |
| six-ball-puzzle | 3 / 13 | Canvas + tray lean | tilt (gated) | med | WF*, SK, PC | med-high | med | FAMILY 10+ | P2 |
| tap-race | 2 / 10 | Canvas (party zones) | no | med | SK*, NA*, HC | low | low | KIDS 4+ | P3 |
| tug-of-war | 2 / 10 | Canvas (party zones) | no | med | SK*, NA, HC | low | low | KIDS 4+ | P3 |
| reaction-duel | 2 / 9 | Canvas (party zones) | no | low-med | SK*, HC*, NA | low | low | KIDS 5+ | P3 |
| whack-a-mole | 2 / 9 | Canvas sprites | no | med | SK*, PC, NA | med-high | low-med | KIDS 4+ | P1 |
| light-trail | 2 / 10 | Canvas (party zones) | no | med | NA*, HC | med | med | KIDS 6+ | P2 |
| sumo-push | 2 / 10 | Canvas (party zones) | no | med | SK*, NA, PC | low-med | low | KIDS 5+ | P3 |
| paddle-duel | 2 / 8 | Canvas sim | no | med | NA*, HC | low-med | low | KIDS 5+ | P3 |
| rps-showdown | 2 / 10 | Canvas Path hands | no | med | SK*, PC*, NA | low | low | KIDS 4+ | P3 |
| echo-pads | 2 / 13 | Canvas | no | low | SK*, NA*, HC | low | low | KIDS 4+ | P3 |
| act-it-out | 2 / 12 | Canvas + glyph cards | no | low | SK*, PC* | none | low | FAMILY 4+ | P3 |
| bridge-builders | 2 / 13 | Canvas | no | med | SK*, PC, WF | low-med | med | KIDS 5+ | P3 |

### Toolkit (1)

| Game | Feel / total | Render tech today | 3D today | Draw cost | 2D packs | 3D value | 3D risk | Kids | Prio |
|---|---|---|---|---|---|---|---|---|---|
| party-toolkit | 1 / 8 | Canvas + Text | no | med | PC*, SK, NA, HC | med | low | FAMILY 6+ | P2 |

### P1 detail cards

The 12 P1 games, with the per-game claim behind each alternative. Full text for every other game is in the CSV.

#### chess (BOARD, feel 3/3, FAMILY 8+)

- **Drawn today**: Compose Box grid with Canvas overlays; pieces are vector Path silhouettes (6 types x CLASSIC/MINIMALIST) inside round tokens (ChessScreen.kt:1349-1380, silhouette builder :1413); wood-grain board is a cached ImageBitmap (KDoc at :1413); hint arrow and mate-line are extra Canvases (:930,:968)
- **Surfaces**: board, pieces, hud, celebration, captured-tray
- **3D today**: perspective tilt (:735) + specularSweep (:738) + promotion card3DFlip (:881), all behind LocalCard3DMode; no real 3D
- **Animation**: move tween (lift/carry/set-down when enhanced), capture fade-out, promotion flip, shader sheen, victoryGlow (:1301), hint arrow
- **Draw cost**: med: silhouette Path rebuilt per piece per draw on purpose (:1413 KDoc); hint/mate Canvases allocate a Path per arrow per frame while visible; wood bitmap cached
- **2D packs**: WF*, HC, PC (paper-cut silhouettes), NA (glow outlines)
- **2.5D**: layered contact shadows, bevelled squares, existing tablePerspectiveTilt plus parallax lift on the selected piece
- **3D**: extruded Staunton pieces on a tilted board: extrude the 6 existing silhouettes (painter-sorted flat shading) or ship pre-rendered turntable sprites
- **3D value / risk**: high: pieces are the game's identity and the vector silhouettes already exist; turn-based so frame budget is plentiful / med: 6 types x 2 colours x 2 styles of assets; hit-testing stays 2D; low perf risk if sprites, med if live software projection
- **Notes**: Chess gains the biggest identity jump from 3D. victoryGlow and sheen already wired to the shared shaders. feel 3 so baseline is solid.

#### checkers (BOARD, feel 3/3, KIDS 7+)

- **Drawn today**: Compose Box/graphicsLayer grid; squares are a cached wood-grain ImageBitmap tile (CheckersScreen.kt:1593-1596); pieces are Box discs with ring + vector crown glyph (:1327, :1500)
- **Surfaces**: board, pieces, hud, captured-tray, celebration
- **3D today**: perspective tilt (:790) + specularSweep (:814) + promotion card3DFlip (:1450), all gated; no real 3D
- **Animation**: move tween (Animatable moveProgress), capture fade, promotion flip, camera shake (11 refs), shader sheen, 3-tier CheckersMotionTier OFF/STANDARD/MAXIMUM (games/checkers/CheckersMotionPrefs.kt:41)
- **Draw cost**: low-med: wood tile cached (:1593); Path 2 and Brush 2 only in crown/sheen
- **2D packs**: WF*, HC, PC, SK (chunky counters)
- **2.5D**: stacked-disc thickness (2-3 layered ellipses), contact shadows, tilt already present
- **3D**: thick cylinder discs, stacked kings, 3D board; a cylinder is the cheapest 3D piece in the app
- **3D value / risk**: high: simplest 3D piece set, large visible change, reuses chess board plumbing / low-med
- **Notes**: Best existing 2.5D baseline. CheckersMotionTier is the only per-game animation-level precedent and maps directly to Calm/Standard/Lively.

#### reversi (BOARD, feel 1/3, FAMILY 8+)

- **Drawn today**: Pure Compose: Box grid, discs are Box+CircleShape+border (ReversiScreen.kt:549-600), no Canvas, no shader, no particles
- **Surfaces**: board, pieces, hud
- **3D today**: no
- **Animation**: disc colour cross-fade 350ms (animateColorAsState, :586) as the capture flip; nothing else; ignores Reduced Motion (scorecard)
- **Draw cost**: low: no Canvas, no per-frame work
- **2D packs**: all five (SK*, NA*, PC*); skin is a palette+shape swap
- **2.5D**: disc rim gradient + highlight, flip as rotationX squash with shadow
- **3D**: two-tone 3D disc that flips about its edge (the signature Reversi motion); rotationX flip via graphicsLayer is the 2.5D version
- **3D value / risk**: med-high: the flip is the feel; but board is plain / low
- **Notes**: Pilot of GameChrome/fitBoard and feel 1/3: lowest-effort game with the most visible lift. Zero Canvas means a skin renderer is trivially testable.

#### solitaire (CARD, feel 2/3, FAMILY 8+)

- **Drawn today**: PlayingCardView FLAT + a hand-drawn corner-index Text overlay (SolitaireScreen.kt:1000-1030); felt; foil-rank sheen (:876); fan flourish is bespoke
- **Surfaces**: cards, table, hud, celebration
- **3D today**: tilt (:550) + toss (:986) + sheen (:876,:1130,:1198), gated; no real 3D
- **Animation**: drag, auto-move flights, card toss, foundation shimmer, bespoke CardBackFanFlourish
- **Draw cost**: med: up to 52 card Boxes each with Text + shadow; shader per shimmering card
- **2D packs**: WF*, PC, HC, SK
- **2.5D**: card thickness, deeper shadow on drag
- **3D**: 3D deck pile with real thickness; flip as 3D
- **3D value / risk**: low-med / med
- **Notes**: Lowest-scoring card game (8/15): the best target for the unified deck component. Tap/drag model unchanged.

#### yahtzee (BOARD, feel 2/3, FAMILY 8+)

- **Drawn today**: Compose Box dice + Canvas pip face (YahtzeeScreen.kt:522, pips :611-614); held die lifted + outlined
- **Surfaces**: dice, scorecard, hud
- **3D today**: no (flat pips on tumbling Box)
- **Animation**: tumble through random faces then settle (:536-545), hold lift, hint dashed outline; no sound at all (G2)
- **Draw cost**: low: 5 Boxes, pips from a table
- **2D packs**: PC*, WF, NA, HC
- **2.5D**: face bevel and drop shadow on a felt strip
- **3D**: reuse Die3DView/drawDie3D (PigDiceArt.kt:98,:189) for 5 dice
- **3D value / risk**: high: dice ARE the game; the 3D die already exists in-repo / low-med: drawDie3D allocates FloatArray + Path per face and a 14-step ring Path per pip per frame (PigDiceArt.kt:150-168); 5 dice during tumble = bounded
- **Notes**: One die component for Yahtzee, Pig, Chutes, Ludo, Backgammon, Toolkit would reach 6 games (Surface DIE).

#### blackjack (CARD, feel 3/3, Blocked for kid profiles (gambling-themed list, core/GameAudience.kt:13))

- **Drawn today**: CasinoCards.kt Canvas pips + Text (games/blackjack/CasinoCards.kt:176,:264,:303), flip via graphicsLayer rotationY=rotation (:318); chips; felt table; Canvas (BlackjackScreen.kt:530)
- **Surfaces**: cards, chips, table, hud, particles, celebration
- **3D today**: rotationY card flip (cameraDistance 14f, :318): a 3D-style transform, no real 3D
- **Animation**: deal slide, flip, chip toss, particle burst
- **Draw cost**: low-med: 2 cards/hand; pips from a table (pipLayout CasinoCards.kt:~112); Path none
- **2D packs**: WF*, HC, NA, PC
- **2.5D**: card thickness, table shadow
- **3D**: 3D chips and card flip on a felt table
- **3D value / risk**: med: chip stacks and flips read well / low-med
- **Notes**: Highest-scoring game (15/15) so a clean pilot for the card + felt surface. Blocked for kid profiles.

#### matching (CARD, feel 2/3, KIDS 4+)

- **Drawn today**: MatchingArt Canvas procedural art in 3 themes: critters, symbols, cards (games/matching/MatchingArt.kt:59,:83,:184); flip rotationY 180 (MatchingScreen.kt:522)
- **Surfaces**: cards, grid, hud, particles, celebration
- **3D today**: rotationY flip with cameraDistance 14f (:522)
- **Animation**: deal-in stagger, flip, bounce on match, particles
- **Draw cost**: med: a grid of flipping cards; critter Paths per draw (alloc :97,:101,:131,:161,:193)
- **2D packs**: SK*, PC*, HC
- **2.5D**: card thickness, flip shadow
- **3D**: 3D flipping cards, 3D animal tokens
- **3D value / risk**: med / low-med
- **Notes**: KIDS 4+. Theme selector is an existing per-game art switch: first game to prove StylePack-vs-in-game-theme layering.

#### darts (ARCADE_PUZZLE, feel 3/3, KIDS 6+)

- **Drawn today**: Canvas regulation board (DartsScreen.kt:462,:713; drawBoard fns :733,:788,:813; Path/Brush/measure alloc :739,:781,:783,:799); wooden surround
- **Surfaces**: board, darts, reticle, hud, particles
- **3D today**: no
- **Animation**: reticle sway, dart flight scale, hit particles
- **Draw cost**: med: sector Paths + text built per draw
- **2D packs**: HC*, NA, PC, WF
- **2.5D**: board bevel, dart shadow
- **3D**: perspective board with 3D dart flight and wobble
- **3D value / risk**: high: darts are about the throw / med
- **Notes**: KIDS 6+. Scoring rings already outlined for colour-blind.

#### bowling (ARCADE_PUZZLE, feel 3/3, KIDS 5+)

- **Drawn today**: Software perspective camera on Canvas: LaneCamera.p() projection (BowlingScreen.kt:700-706), drawLane (:709) as gradient quads, pins as discs with collision physics
- **Surfaces**: lane, pins, ball, hud, particles
- **3D today**: pseudo-3D projection already (software camera, not graphicsLayer)
- **Animation**: camera follows the ball, pin topple, aim line, particles
- **Draw cost**: high: 11 Path/Brush allocs per frame in drawLane (:715-760): 39 board lines + 7 arrows + quads
- **2D packs**: WF*, HC, NA, PC
- **2.5D**: cast shadows, ball gloss, pin highlights
- **3D**: true 3D pins + ball + lane (the projection maths is already there)
- **3D value / risk**: high: lane perspective, pin topple / high: topple of 10 pins live; allocs must be cached first
- **Notes**: KIDS 5+. Already the app's best 2.5D: pilot for Soft 2.5D.

#### billiards (ARCADE_PUZZLE, feel 3/3, FAMILY 10+)

- **Drawn today**: Canvas table with wooden rails, cloth, pockets (BilliardsScreen.kt:569,:688,:714; drawBilliardsTable :757); glossy numbered balls via radial Brush; spin pad Canvas (:738)
- **Surfaces**: table, balls, cue, spin pad, hud, particles
- **3D today**: no (stripes/numbers are 2D shading)
- **Animation**: cue pull-back, aim ghost ball, replay, particles
- **Draw cost**: high: wood grain + cloth weave loops redrawn every frame (BilliardsScreen.kt:766-790); 11 Path/Brush alloc sites (:742-872)
- **2D packs**: WF*, HC, NA
- **2.5D**: ball shadows, cloth nap
- **3D**: true 3D balls + cue on a perspective table
- **3D value / risk**: high: the most 3D-native sport game / high: 16 balls with specular and number textures; allocs must be cached; tight frame budget
- **Notes**: Family 10+. Replay of the recorded shot makes a 3D renderer separable from physics (good).

#### pig-dice (BOARD, feel 2/3, Blocked for kid profiles (gambling-themed list, core/GameAudience.kt:13))

- **Drawn today**: Die3DView: a software-projected, lit, tumbling cube on Canvas (PigDiceArt.kt:23-29 doc, :98 drawDie3D, :189 Die3DView; used at PigScreen.kt:413); felt table; Canvas 431,541
- **Surfaces**: dice, table, hud, particles, celebration
- **3D today**: YES: the only real 3D object in the app (3x3 rotation, painter-sorted faces, perspective), no engine
- **Animation**: tumble + settle + hop, bust tint on a 1, particle burst
- **Draw cost**: med: per frame it builds FloatArray rotation, a Path per visible face, a 14-point ring Path per pip, sorts 6 faces (PigDiceArt.kt:131-168)
- **2D packs**: PC, SK, HC; needs a flat pip-die for Classic
- **2.5D**: the 3D die exists; 2.5D = same die with a fixed iso tilt and no tumble
- **3D**: extend: 3D table, dice cup, shadow plane
- **3D value / risk**: high (extension of existing) / low
- **Notes**: Reference implementation of a 3D asset. Blocked for kid profiles (KID_BLOCKED_GAME_IDS, core/GameAudience.kt:13).

#### whack-a-mole (ARCADE_PUZZLE, feel 2/3, KIDS 4+)

- **Drawn today**: WhackAMoleArt Canvas holes + mole/gopher/Prickle (WhackAMoleArt.kt:32,:66,:78; alloc 7); Canvases (WhackAMoleScreen.kt:220,:344,:431)
- **Surfaces**: holes, critters, hud, particles
- **3D today**: no
- **Animation**: pop-up, whack squash, streak, particles
- **Draw cost**: med
- **2D packs**: SK*, PC, NA
- **2.5D**: hole depth (inner shadow, occluding rim)
- **3D**: 3D holes with popping critters
- **3D value / risk**: med-high: the hole occlusion reads as true depth / low-med
- **Notes**: KIDS 4+. Strong Storybook-kids candidate (and a good 2.5D one).

## 3. Surface catalogue: the renderers that style packs should target (not games)

One row per distinct reusable renderer. "Games" is the number of the 81 games that draw through it; names are in the cell. Counts come from the tags in the CSV `surface_tags` column. Two platform surfaces reach all 81 and are listed after the table.

| Surface | Today (cites) | Games | Reached | Note |
|---|---|---|---|---|
| **Card face + back: shared `PlayingCardView`**<br>`CARD_PCV` | games/cards/PlayingCardView.kt:59 (FLAT tile via Box+Text; UNO printed style :215; optional edge thickness :73-95). Solitaire adds its own corner-index Text overlay (ui/SolitaireScreen.kt:1000-1030). | 2 | solitaire, uno | Only shared deck component. Target for the unified deck (scorecard R3). |
| **Card face + back: Casino cards**<br>`CARD_CASINO` | games/blackjack/CasinoCards.kt:176 (face), :264 (back), :303 (CasinoCard with rotationY flip :318); Canvas pips from a layout table. | 2 | blackjack, war | Second-cleanest deck: Canvas pips, flip built in. |
| **Card face + back: President/Sevens**<br>`CARD_PS` | games/president/PsCardUi.kt:173 (PsCardFace), :196 (back); Canvas lattice + gem medallion. | 2 | president, sevens | Own back design, 1.4x aspect. |
| **Card face + back: Speed**<br>`CARD_SPEED` | games/speed/CardArt.kt:98 (face), :148 (back), :160 (drawCardBack); Matching reuses SpeedCardBack (ui/MatchingScreen.kt:528). | 2 | speed, matching | Slap rotation in SpeedScreen.kt:614. |
| **Card face + back: Hold'em**<br>`CARD_HOLDEM` | games/holdem/HoldemCardArt.kt:150 (back), :183 (face), flip rotationY :138; 7 Canvases. | 1 | texas-holdem | Own four-colour option. |
| **Card face + back: Spider (in-file)**<br>`CARD_SPIDER` | ui/SpiderScreen.kt:736 (SpiderFace Box+Text glyph), :764 (SpiderBack Canvas lattice drawn with a line loop per card). | 1 | spider-solitaire | 104 cards on screen at once. |
| **Card art: Matching themes**<br>`CARD_MATCH` | games/matching/MatchingArt.kt:59-190: critters, symbols, standard-card pair, all procedural Canvas; flip ui/MatchingScreen.kt:522. | 1 | matching | The only game with a built-in art-theme switch (MatchingTheme, games/matching/MatchingRules.kt:14). |
| **Card art: Hanafuda**<br>`CARD_HANA` | ui/HanafudaArt.kt:73 (face), :95 (back); procedural flowers, specials, ribbons :150-436. | 1 | hanafuda | Richest procedural art in the app. |
| **Die: software-3D cube**<br>`DIE_3D` | ui/PigDiceArt.kt:23-29 (doc), :98 drawDie3D, :189 Die3DView; per frame: 3x3 rotation, 6 faces sorted, a Path per visible face, a 14-point ring Path per pip (:131-168). | 2 | pig-dice, chutes-race | The only real 3D object. Needs a flat-pip twin so Classic can opt out. |
| **Die: flat pip dice (four copies)**<br>`DIE_2D` | ui/YahtzeeScreen.kt:522-:614, ui/LudoScreen.kt:562-:590, ui/BackgammonScreen.kt:498, ui/PartyToolkitScreen.kt:569 (+ d4-d20 chips :547). | 4 | yahtzee, ludo, backgammon, party-toolkit | Four implementations of the same pips table. |
| **Grid/point board frame**<br>`BOARD_GRID` | Per-game Canvas or Box grids: Chess/Checkers/Reversi/TTT/C4/Dots/Gomoku/Hex/Morris/Hare/ChineseCheckers/Ludo/Backgammon/Shogi/Mancala/WordTiles (see CSV source files). 29 private palettes sit on top. | 17 | tic-tac-toe, connect-four, dots-and-boxes, reversi, checkers, chess, gomoku, hex, nine-mens-morris, hare-and-hounds, chinese-checkers, ludo, backgammon, shogi, mini-shogi, mancala, word-tiles | Board frame, lines, squares, coordinates, last-move highlight, hint arrows. |
| **Wood material**<br>`WOOD` | Cached grain bitmap in Checkers (ui/CheckersScreen.kt:1593-1596) and Chess (generateWoodGrainBitmap); mancalaWoodSurface (MancalaScreen.kt:1345-1420); line-loop grain in Billiards (ui/BilliardsScreen.kt:766-790); wood palettes elsewhere. | 13 | checkers, chess, mancala, backgammon, shogi, mini-shogi, gomoku, nine-mens-morris, chinese-checkers, word-tiles, six-ball-puzzle, mini-golf, billiards | Three different implementations of one material. |
| **Round game piece (disc, stone, marble, peg, seed, token, ball)**<br>`PIECE_ROUND` | Reversi Box+CircleShape (ui/ReversiScreen.kt:549); Gomoku glossy spheres (GomokuScreen.kt:549,:561); Morris carved stone (NineMensMorrisScreen.kt:109); Mancala seeds (MancalaScreen.kt:1246); Billiards balls (BilliardsScreen.kt:742); etc. | 16 | reversi, connect-four, checkers, gomoku, hex, nine-mens-morris, chinese-checkers, ludo, backgammon, mancala, mastermind, six-ball-puzzle, chutes-race, carrom, billiards, curling | Largest group of look-alike pieces: one shaded-disc renderer with a material parameter would cover them. |
| **Sculpted piece (silhouette)**<br>`PIECE_SCULPT` | Chess Path silhouettes (ui/ChessScreen.kt:1413), Shogi pentagon wedge + kanji (ui/ShogiBoardView.kt:89, games/shogi/ShogiPieceArt.kt), Hare and Hounds figures (ui/HareAndHoundsScreen.kt:114,:144). | 4 | chess, shogi, mini-shogi, hare-and-hounds | Where 3D extrusion pays most per piece. |
| **Procedural vector character / vehicle**<br>`CHAR_SPRITE` | Boxers (ui/ToyBoxingArt.kt:84-278), soccer/tennis players (ui/ToySoccerTennisArt.kt:37,:73), moles (ui/WhackAMoleArt.kt:32-125), RPS hands (ui/RpsShowdownArt.kt:26,:108), cars (ui/SlotCarsArt.kt:82-186), fish (ui/FishingArt.kt:51-161), tanks (ui/TankBattleArt.kt:113-276), targets (ui/ShootingGalleryArt.kt:28-221), sumo/bikes (ui/TrailSumoUi.kt:171-325), party pads (ui/PartyTapTugArt.kt:58-150). | 21 | toy-boxing, toy-soccer, toy-tennis, toy-baseball, whack-a-mole, shooting-gallery, rps-showdown, sumo-push, light-trail, tank-battle, slot-cars, fishing, tap-race, tug-of-war, bridge-builders, reaction-duel, matching, act-it-out, draw-guess, echo-pads, chutes-race | Each is bespoke Path code; no shared character system. |
| **Card/tile table backdrop**<br>`FELT` | UNO felt grain AGSL brush (ui/UnoScreen.kt:2704, effects/PremiumShaders.kt FELT_GRAIN_AGSL); slate/felt tables in Dominoes (DominoesScreen.kt dominoSlateTable); solid/gradient felts elsewhere. | 12 | solitaire, spider-solitaire, uno, blackjack, war, texas-holdem, president, sevens, speed, hanafuda, dominoes, pig-dice | The shader exists but only UNO calls it. |
| **Puzzle cell grid (numerals/letters/clues)**<br>`PUZZLE_GRID` | Sudoku (ui/SudokuScreen.kt:1086), KenKen cages (:1083), Kakuro (KakuroScreen.kt), Nonogram (NonogramScreen.kt:1285), Crossword (:1043), Word Search (:866), Minesweeper (:1028-1266), Lights Out, Color Flood (:837), Edge Match (:1170), Flow Lines (:773), 2048, Sliding Puzzle. | 13 | sudoku, kenken, kakuro, nonogram, crossword, word-search, minesweeper, lights-out, color-flood, edge-match, flow-lines, game-2048, sliding-puzzle | Highest-count surface where 3D is irrelevant but 2D packs and High Contrast matter most. |
| **Chunky tile (face + side thickness)**<br>`CHUNKY_TILE` | Mahjong tile Box with drop shadow + jade side (ui/MahjongScreen.kt), Domino tile (DominoesScreen.kt:1504), Word Tiles tile (TileGameScreen.kt:1326), 2048 and Sliding Puzzle tiles, Word Guess tiles. | 6 | mahjong-solitaire, dominoes, word-tiles, game-2048, sliding-puzzle, word-guess | Thickness, bevel and gloss are the 2.5D upgrade. |
| **On-screen letter keyboard**<br>`KEYBOARD` | Hangman keys (ui/HangmanScreen.kt) and Word Guess QWERTY (ui/WordGuessScreen.kt:~1088). | 2 | hangman, word-guess | Colour-coded key states. |
| **Paper/chalk/graph-paper background**<br>`PAPER_CHALK` | Hangman chalkboard (HangmanScreen.kt:626), graph paper (WordSearchScreen.kt), newsprint (CrosswordScreen.kt:416), Nonogram, Draw and Guess paper (ui/DrawGuessArt.kt:82), Act It Out. | 6 | crossword, word-search, nonogram, hangman, draw-guess, act-it-out | Natural fit for the Paper craft pack. |
| **Sports/arcade arena backdrop (table, court, lane, sheet, terrain)**<br>`ARENA` | Billiards table (ui/BilliardsScreen.kt:757), Air Hockey ice (AirHockeyScreen.kt:500), Bowling lane (BowlingScreen.kt:709), Darts board (DartsScreen.kt:733), Curling sheet, Carrom, Mini Golf turf (MiniGolfScreen.kt:731,:840), pitch/court (ToySoccerTennisArt.kt), Tank terrain (TankBattleArt.kt:129-141), Tower Defence map (TowerDefenceScreen.kt:483), Breakout, Snake and others. | 22 | billiards, air-hockey, carrom, curling, mini-golf, toy-soccer, toy-tennis, toy-baseball, bowling, darts, shooting-gallery, tank-battle, slot-cars, fishing, breakout, tower-defence, bridge-builders, sumo-push, light-trail, paddle-duel, six-ball-puzzle, snake | Bespoke per game, so little to share; the best 2.5D win is parallax layers per arena. |
| **Rotated multi-seat touch zones (party pack)**<br>`PARTY_ZONES` | Zones turned half a turn per seat, shared party art/UI: ui/PartyTapTugArt.kt, ui/TrailSumoUi.kt, ui/PaddleRpsPartyUi.kt, ui/ReactionWhackStage.kt. | 9 | tap-race, tug-of-war, reaction-duel, whack-a-mole, light-trail, sumo-push, paddle-duel, rps-showdown, bridge-builders | One shared set of pads, lanes and stages. |
| **Particle burst**<br>`PARTICLE` | ui/effects/ParticleBurst.kt:137 (always-on loop via rememberParticleBurst; 38 screens) plus 4 demand-driven users. | 42 | air-hockey, billiards, blackjack, bowling, bridge-builders, carrom, chinese-checkers, curling, darts, fishing, texas-holdem, light-trail, ludo, matching, mini-golf, paddle-duel, party-toolkit, pig-dice, president, reaction-duel, rps-showdown, sevens, shooting-gallery, six-ball-puzzle, chutes-race, slot-cars, speed, sumo-push, tank-battle, tap-race, tower-defence, toy-baseball, toy-boxing, toy-soccer, toy-tennis, tug-of-war, war, whack-a-mole, connect-four, dots-and-boxes, sudoku, color-flood | The single most shared animation primitive. |
| **Camera shake**<br>`SHAKE` | ui/effects/CameraShake.kt:94; UNO uses its own Animatables (UnoScreen.kt:472). | 10 | air-hockey, checkers, color-flood, connect-four, dots-and-boxes, minesweeper, party-toolkit, sudoku, tower-defence, uno | Lively-only candidate. |
| **Specular sweep (AGSL, API 33)**<br>`SPEC` | ui/effects/PremiumShaders.kt:171-197; RenderEffect re-created per frame in graphicsLayer. | 11 | chess, checkers, mancala, dominoes, word-tiles, uno, solitaire, hangman, word-search, crossword, air-hockey | Gated by LocalCard3DMode; Word Tiles uses it per tile. |
| **Victory glow (AGSL)**<br>`VGLOW` | ui/effects/PremiumShaders.kt:225-262; used by Chess, UNO, Word Guess, KenKen, Mastermind, Tower Defence. | 6 | chess, uno, word-guess, kenken, mastermind, tower-defence | One-shot, cheap. |
| **Table perspective tilt**<br>`TILT` | ui/TablePerspective.kt:31 (graphicsLayer rotationX 6deg); applied in 8 games. | 8 | air-hockey, checkers, chess, dominoes, mancala, sliding-puzzle, solitaire, uno | The existing 2.5D whole-surface transform. |
| **Card/tile flip and toss transforms**<br>`FLIP_3DXFORM` | games/cards/Card3D.kt:49-75 (card3DFlip, card3DToss) plus bespoke rotationY/rotationX flips in 7 more games. | 12 | checkers, chess, dominoes, solitaire, uno, matching, blackjack, war, texas-holdem, speed, word-guess, six-ball-puzzle | The existing 2.5D per-object transform. |
| **Per-screen private palette**<br>`PALETTE` | 29 `private data class *Palette` inside the screen files (e.g. ui/ReversiScreen.kt:388). | 29 | backgammon, breakout, color-flood, connect-four, crossword, dots-and-boxes, edge-match, fishing, flow-lines, gomoku, hare-and-hounds, hex, kakuro, kenken, lights-out, mahjong-solitaire, mastermind, minesweeper, nine-mens-morris, nonogram, party-toolkit, reversi, shogi, mini-shogi, slot-cars, snake, sudoku, tank-battle, tower-defence | The 2D-pack mechanism: lift into a registry keyed by pack. |
| **Win celebration (burst, glow, own confetti)**<br>`CELEBRATE` | ParticleBurst (42) + victoryGlow (6) + bespoke confetti (UNO ConfettiOverlay, Hangman nativeCanvas confetti HangmanScreen.kt:640-660, Gomoku, Hex, Shogi petals ShogiScreen.kt:827-839, etc.). Result cue vocabulary: ui/ResultFeedback.kt. | 53 | chess, connect-four, dots-and-boxes, uno, hangman, word-guess, sudoku, kenken, color-flood, mastermind, tower-defence, air-hockey, party-toolkit, gomoku, hex, chinese-checkers, ludo, shogi, mini-shogi, blackjack, war, texas-holdem, president, sevens, speed, matching, spider-solitaire, darts, bowling, carrom, curling, billiards, mini-golf, toy-soccer, toy-tennis, toy-boxing, toy-baseball, slot-cars, fishing, tank-battle, shooting-gallery, six-ball-puzzle, pig-dice, tap-race, tug-of-war, reaction-duel, whack-a-mole, light-trail, sumo-push, paddle-duel, rps-showdown, chutes-race, bridge-builders | Union of 53 games; the Lively level lives here. |

Aggregates (not extra renderers): any card face = 11 games (uno, solitaire, blackjack, war, texas-holdem, president, sevens, speed, matching, spider-solitaire, hanafuda); any die = 6 games (party-toolkit, yahtzee, backgammon, ludo, pig-dice, chutes-race); board-game material set (BOARD_GRID or WOOD or PIECE_ROUND or PIECE_SCULPT) = 24 games.

**Platform surfaces that reach all 81 games**

- **HUD and chrome**: the shared `uikit/GameChrome.kt` corner menu, status rows, result panels and `ResultFeedback`. Skin at the chrome, not per game.
- **Hub card icon and accent**: emoji glyph per game (`core/GameCatalog.kt:76-134`, `gameGlyph`) and one accent colour pair (`ui/GameTheme.kt`, 81 entries). A pack can ship its own icon art and accent set for the Hub in one change.

## 4. Top 10 surfaces by games reached (best ROI)

| # | Surface | Games | Shared implementation today? | Pack leverage | ROI call |
|---|---|---|---|---|---|
| 1 | Win celebration (`CELEBRATE`) | 53 | Partly: ParticleBurst + victoryGlow, but five bespoke confetti | 2D recolour, 2.5D depth, Lively adds more | High: one celebration spec per pack and level, and it is the visible face of Lively |
| 2 | Particle burst (`PARTICLE`) | 42 | Yes: `ParticleBurst` (one class) | Colour/shape per pack; Calm turns it off | Highest: one class, 42 games. Fix the always-on loop first (ParticleBurst.kt:137) |
| 3 | Per-screen palette (`PALETTE`) | 29 | No: 29 private data classes | This IS the 2D pack mechanism | Highest for Classic 2D: lift to a registry, then each game reads its palette from the pack |
| 4 | Arena backdrop (`ARENA`) | 22 | No: bespoke per game | 2.5D parallax layers, 3D only for Billiards/Bowling/Darts | Medium: many games, little shared code; do per pilot, not as a surface |
| 5 | Vector characters (`CHAR_SPRITE`) | 21 | No: bespoke Path code | SK/PC re-art; 3D rarely worth it | Medium-low: reach is wide but each is separate art work |
| 6 | Board frame (`BOARD_GRID`) | 17 | No: but 17 games share the shape | Material + bevel + tilt | High with PIECE_ROUND: one board+piece skin covers 24 games |
| 7 | Round piece (`PIECE_ROUND`) | 16 | No: 16 look-alikes | Shading, thickness, 3D cylinder/sphere | High: cheapest 3D piece (cylinder/sphere) and 16 games |
| 8 | Puzzle grid (`PUZZLE_GRID`) | 13 | No | 2D only (HC, PC, NA); 3D irrelevant | Medium: huge reach for High Contrast and Paper craft, zero for 3D |
| 9 | Wood material (`WOOD`) | 13 | Three implementations | Texture bitmap + bevel | High: cache one grain bitmap per pack (already done for Checkers/Chess) |
| 10 | Felt table (`FELT`) | 12 | Shader exists, one caller | Felt grain + vignette + card shadow | High: 12 games; pairs with the deck unification |

Runners-up at 11-12 games: flip/toss transforms (12), felt-table card decks as a whole (any card face, 11), specular sweep (11). Smaller but valuable: dice (6), a single shared die would give 3D dice to Yahtzee, Ludo, Backgammon and the Toolkit using the component that already exists.

Overlap note: BOARD_GRID, PIECE_ROUND, PIECE_SCULPT and WOOD overlap heavily; together they touch 24 games, so "board skin" is one workstream, not four.

## 5. Animation inventory and how Calm / Standard / Lively map onto it

| Technique | Games | Where / cost |
|---|---|---|
| Particle burst | 42 | `rememberParticleBurst()` loop is always on (`ui/effects/ParticleBurst.kt:137-148`), 38 screens; 4 demand-driven (Connect Four `ConnectFourScreen.kt:332`, Dots and Boxes `:221`, Sudoku `:984`, Color Flood `:755`) |
| Camera shake | 10 | `ui/effects/CameraShake.kt:94`; UNO has its own (`UnoScreen.kt:472`) |
| Specular sweep AGSL | 11 | `PremiumShaders.kt:171-197`, API 33+, falls back silently |
| Victory glow AGSL | 6 | `PremiumShaders.kt:225-262` |
| Table tilt | 8 | `ui/TablePerspective.kt:31`, 6 degrees, gated |
| Flip / toss | 12 | `games/cards/Card3D.kt:49-75` + bespoke `rotationY` flips |
| Own frame loops (`withFrameNanos`) | 40 call sites | real-time games, particle loops, physics overlays |
| Physics sims on screen | 18+ | Air Hockey, Billiards, Bowling, Carrom, Curling, Mini Golf, Toy sports, Slot Cars, Fishing, Tank Battle, Six-Ball, Breakout, Tower Defence, Mancala seeds, party sims |
| Mesh gradient background | Home only | `ui/MainMenuScreen.kt:106` |
| Felt grain shader | UNO only | `UnoScreen.kt:2704` |

Existing knobs a level must compose with: `LocalReducedMotion` (accessibility, always wins), `LocalEnhancedAnimations` (default true, weighted lift/carry/capture fade; `settings/LocalEnhancedAnimations.kt:26`), `LocalGameSpeed` (`settings/GameSpeed.kt:52`, CPU pause scaling, not animation), `LocalCard3DMode` (rendering style, default false), `CheckersMotionTier` (per-game tier).

| Level | Proposed meaning | Maps to today | Per-family effect |
|---|---|---|---|
| Calm | tweens at most 200 ms, no idle loops, no particles, no shake, no shader sweep, no celebration beyond a static result card | Checkers tier OFF; `LocalEnhancedAnimations=false` plus particles off | Cards: slide only. Boards: snap/short slide. Real-time: unchanged physics, no decoration |
| Standard | current default behaviour | `LocalEnhancedAnimations=true`, particles on, shaders only if 3D Perspective Mode | No change: protects existing tests and feel |
| Lively | adds idle sheen loops, camera shake, squash/stretch, richer bursts, parallax drift, bigger celebration | Checkers tier MAXIMUM generalised | Cards: foil + toss. Boards: capture shake + glow. Real-time: only decoration, never extra physics cost; auto-drop to Standard on battery saver (`rememberPowerSaveMode`, `PremiumShaders.kt:327`) or thermal throttle |

Pilots for the animation axis: **Checkers** (it already has OFF/STANDARD/MAXIMUM, so Calm/Standard/Lively is a rename plus two more consumers) and **Connect Four** (already has particles, shake and a demand-driven loop that stops when idle: the model for how a Lively effect should cost nothing at rest). Do not pilot Lively on Air Hockey, Tower Defence or Billiards until their per-frame fixes land.

## 6. Where 3D adds the most value, and where it does not

### Top 10 games for 3D (value first, then feasibility)

| # | Game | Why 3D pays | What it would be | Main risk |
|---|---|---|---|---|
| 1 | billiards | Balls, cue, rails and cloth are the genre; physics is already 2D top-down and a recorded-shot replay exists, so a 3D renderer is purely presentational | Perspective table, 3D balls with specular + number textures, cue in 3D | Per-frame wood/cloth loops and 11 alloc sites must be cached first; 16 balls at 60 fps |
| 2 | bowling | A software perspective camera already follows the ball (`BowlingScreen.kt:700-706`); pins toppling is the payoff | True 3D pins, ball and lane using the existing projection | Draw allocations (`:715-760`); live topple of 10 pins |
| 3 | chess | Piece identity; six silhouettes already exist and the game is turn-based | Extruded Staunton pieces on a tilted board, or turntable sprites | Asset volume (6 x 2 x 2); hit-testing stays 2D |
| 4 | yahtzee | Dice are the game; `drawDie3D` already exists | 5 reusable 3D dice with hold lift | Per-frame Path allocations x5 during tumble (bounded) |
| 5 | darts | The throw is the game | Perspective board, 3D dart flight with wobble | Board sector Paths + text measured per draw (`DartsScreen.kt:733-813`) |
| 6 | checkers | Cheapest 3D piece (cylinder), big visible change | Thick discs, stacked kings, tilted board | Low |
| 7 | air-hockey | Puck and mallets with height read as 3D immediately | 3D table, puck, mallets over the existing 2D physics | Fix per-frame recomposition first (scorecard 7/15) |
| 8 | backgammon | Dice plus stacked checkers are the whole board | 3D dice (shared Die3D) + cylinder checker stacks | Wide 2:1 board; stacks of 15 |
| 9 | mini-golf | Elevation, rails and the cup | Authored 3D course with a 3D ball over the 2D simulation | Authored geometry per course; water/belt animation |
| 10 | ludo | Dice and pawns | Shared Die3D + 3D pawns and a raised board | Four token sets; hop animation |

Rank 11+ and why not top 10: Mahjong Solitaire (value high, but 144 tiles need baked sprites), Pig Dice (already 3D, extend with a table), Mancala, Carrom, Curling, Shogi, Mini Shogi, Chutes Race, Hold'em, Slot Cars, Six-Ball (med-high).

### Poor fit for 3D (do 2D packs only)

- **None (13)**: hangman, word-search, crossword, word-guess, sudoku, kenken, kakuro, nonogram, color-flood, edge-match, flow-lines, draw-guess, act-it-out. These are word, number and drawing puzzles: legibility and contrast are the product, and depth hurts text-scale and screen-reader layouts.
- **Low or low-med (29)**: dots-and-boxes, tic-tac-toe, word-tiles, solitaire, minesweeper, lights-out, sliding-puzzle, mastermind, game-2048, snake, gomoku, hex, nine-mens-morris, war, president, sevens, speed, spider-solitaire, hanafuda, toy-baseball, shooting-gallery, tap-race, tug-of-war, reaction-duel, sumo-push, paddle-duel, rps-showdown, echo-pads, bridge-builders. Depth is decoration only; give them Classic 2D packs and, at most, 2.5D bevel/shadow.
- **Rule of thumb**: if the player reads symbols (letters, digits, clues) 3D is out; if the player aims, throws, flicks or rolls, 3D is in.

## 7. Recommended pilots

Pilot selection criteria: high-reach surface, low risk, strong baseline (so differences are style, not bug fixes), and kid/accessibility rules testable.

| Level | Pilot 1 | Pilot 2 | Why these | Not yet |
|---|---|---|---|---|
| **Classic flat 2D** (packs) | **Reversi** | **Blackjack** | Reversi is composable-only (`ReversiScreen.kt:549`), feel 1/3, already the GameChrome pilot, and exercises the palette registry + disc + board surfaces (reach: 16 round-piece games). Blackjack is the top scorecard game (15/15) and exercises the card + felt surface (reach: 11 card games), so any visual difference is the pack. Alternate if a kid proof is wanted: Matching (SK, `MatchingTheme` already exists). | Hanafuda (own art), UNO (heavy) |
| **Soft 2.5D** | **Checkers** | **Whack-a-Mole** | Checkers already has tilt (`CheckersScreen.kt:790`), sheen (`:814`), promotion flip (`:1450`), wood bitmap and a motion tier: 2.5D is a refactor onto the shared pack, with layered shadows and disc thickness added. Whack-a-Mole is a kid game whose depth is the hole rim occluding the critter (inner shadow + parallax) with only simple Path art (`WhackAMoleArt.kt:32-125`). | Bowling and Air Hockey (per-frame fixes first) |
| **Full 3D** | **Yahtzee** | **Chess** | Yahtzee reuses `drawDie3D` (`PigDiceArt.kt:98`) for 5 dice: no new dependency, bounded cost, and it proves a shared die for 6 games. Chess extrudes six existing silhouettes (`ChessScreen.kt:1413`) or ships turntable sprites: turn-based so the frame budget is generous, and it validates the piece pipeline Checkers and Shogi then inherit. | Billiards/Bowling as the stretch pilots once draw allocations are cached |
| **Animation levels** | **Checkers** | **Connect Four** | Checkers has OFF/STANDARD/MAXIMUM today; Connect Four is the model of effects that cost nothing at rest. | Real-time games for Lively |

Order of work implied by reach: palette registry (29 games) and one shared die (6) and the particle loop fix (42) are prerequisites that make the pilots cheap; unified deck (11) and board+piece skin (24) are the two big shared workstreams that follow.

## 8. Appendix

### 8.1 Engine-only wave-3 games with no UI yet (not in the 81)

`GameHubSpecs.all` lists 81 games. Eleven more packages exist under `games/` with an engine and a `*HubSpecs.kt` but no screen in `ui/` and no registration in `core/` (verified by grep): `bumprun`, `egggarden`, `findit`, `flickrink`, `liferoad`, `piedetective`, `propertytycoon`, `stackzoo`, `sweettrail`, `territorywars`, `trailtogether`. Most are KIDS/FAMILY by their own spec (`games/*/*HubSpecs.kt`; Property Tycoon and Territory Wars are ADULTS). Design their screens against the surface catalogue (board, round piece, tile, dice, felt, arena) instead of adding private palettes.

### 8.2 Per-game kid rules to keep when styling

- Blocked for kid profiles regardless of style: Blackjack, Hold'em, Slot Cars, Hanafuda, Pig Dice (`core/GameAudience.kt:13`). No style pack should be used to make them look kid-friendly.
- KIDS-tagged games (age 4-9) are where Storybook kids belongs: Tap Race, Tug of War, Whack-a-Mole, Matching, RPS, Reaction Duel, Sumo Push, Paddle Duel, Light Trail, Tic-Tac-Toe, Connect Four, Mancala, Dots and Boxes, Checkers, Hare and Hounds, Ludo, Sliding Puzzle, Color Flood, Flow Lines, Snake, Breakout, Darts, Bowling, Mini Golf, Toy Soccer, Fishing, Chutes Race, Echo Pads, Bridge Builders (see `core/GameAudienceTable.kt` and the spec `audience` fields).
- Colour is never the only cue today (shapes/glyphs/patterns exist in most real-time and puzzle games); every pack must keep those cues, and High Contrast must pass the existing WCAG gate (`ui/GameTheme.kt` `Wcag`, 3:1 accent, 4.5:1 text).

### 8.3 Method and limits

- Read: scorecard (`ALL-GAMES.csv`, SUMMARY and G1-G6), `core/GameCatalog.kt`, `core/GameHubSpecs.kt` and the per-game spec files, `ui/GameTheme.kt`, `ui/effects/*`, `ui/TablePerspective.kt`, `games/cards/*`, `settings/Local*.kt`, every `ui/*Screen.kt` header, and the art/board/card files named in the CSV. Mechanical counts (Canvas, Path, Brush, effect usage, `LocalReducedMotion`) came from a regex scan of each game's screen and art files; the families and counts in section 3 were then checked by hand against the cited lines.
- Not done: no profiling, no device, no screenshots. Cost grades are reading-based. The 79/81 Reduced-Motion figure is a grep, not a behaviour test.
- Surfaces are assigned per game by hand in `surface_tags`; a game appears in a surface only if its own code draws through that renderer (for example Matching appears in CARD_SPEED because it reuses `SpeedCardBack`, `ui/MatchingScreen.kt:528`).
- If a style system lands, regenerate this matrix from the code rather than editing it: the surface tags are the contract.
