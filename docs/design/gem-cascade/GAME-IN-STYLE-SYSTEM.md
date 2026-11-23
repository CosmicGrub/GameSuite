# GAME-IN-STYLE-SYSTEM: how Gem Cascade pilots the StylePack surfaces

Status: DESIGN DRAFT, 2026-11-22. Reads with `docs/design/style-packs/ARCHITECTURE.md`, `SCOPE.md`, `SCALE.md`. All code is a sketch, not source. The engine's event contract is `EVENTS.md` (engine team, not yet written).

## 1. Why this game is the pilot

Existing pilots (Reversi, Pig Dice, Mancala) exercise one or two surfaces. Gem Cascade exercises all of them at once: a PIECE with many states and specials, a BOARD_SURFACE that tilts, a BACKGROUND, particle-heavy CELEBRATION, a rich SOUND_PALETTE and HAPTIC_PROFILE, and three motion levels with chain pacing. If it fits here, every other game fits.

## 2. Separation of engine and style

```
GemCascadeEngine (pure Kotlin, JVM-tested)  --events-->  GemAnimator (per style)  --draw-->  Compose
  state: board, goals, score, seed                        EVENTS.md ids (swap.ok, match.clear, special.create,
  rules: RuleSet per mode                                 chain.step, obstacle.*, shuffle, level.complete ...)
```
- The engine resolves a whole move into an ordered event list immediately (deterministic); the animator replays it at the style's pace. Score, state and Daily seeds never depend on style or motion level.
- The animator reads the style (tier) and motion level from `EffectiveStyle`; the engine never sees them.
- Input (drag, tap-tap, one-switch scan) is processed in board coordinates after inverting the board layer's tilt transform.

## 3. Surface map

| Surface | Gem Cascade usage | Flat | Soft 2.5D | Full 3D | Cost note |
|---|---|---|---|---|---|
| BOARD_SURFACE | 8x8 cell grid, board plate, depth slab, obstacle layer (dirt, frost overlay) | cached cells, 0.1 ms | tilted layer 22 deg, grain tile cached, 0.3 ms | 36 deg camera, baked material + AO, slab thickness, 0.6 ms | one cached layer; tilt via `graphicsLayer` only on the board, never HUD |
| PIECE | gem kind x special x obstacle overlay x state (idle, selected, hint, matched, falling) | vector `Path` | gradient sprite + cached shadow | baked faceted sprite + turntable on hint/special | 64 pieces: cached atlas, draw only moved pieces |
| TILE | not used (gems are PIECEs); Sigils tray later uses TILE | | | | |
| BACKGROUND | world backdrop per Journey world, Garden meadow | gradient | 2-3 parallax layers (off in Calm) | + soft depth fog, AGSL gradient High only | idle-free when not visible |
| CHROME | HUD strip, goal bar, boosters, result card, mode cards | flat panels | soft shadows | slightly embossed | cheap; semantics always set |
| CELEBRATION | chain pop, special bursts, level complete stars, bloom grow | squares | blobs | shards | `ParticleBudget` per `SCALE.md` section 2 |
| SOUND_PALETTE | `gc_*` ids from `SCRIPT.md` D1, three variants (Flat chime, Soft warm, Faceted glass) | | | | pre-loaded in SoundPool (fix perf 8.1 first) |
| HAPTIC_PROFILE | per event, per motion level | | | | gated by `LocalHapticsEnabled` |

Declared needs: `StyleNeeds(setOf(BOARD_SURFACE, PIECE, BACKGROUND, CHROME, CELEBRATION, SOUND_PALETTE, HAPTIC_PROFILE))` in `GameHubSpecs`.

## 4. Renderer interfaces used (sketch)

```kotlin
data class GemSpec(
    val kind: GemKind,            // EMBER..ORCHID; carries a11y shape id and mark glyph
    val special: SpecialKind?,    // BLOOM, COMET, CORE, HALO
    val overlay: ObstacleOverlay?,// CAGE, FROST(n), BOMB(n), FIREFLY
    val state: GemVisualState,    // IDLE, SELECTED, HINT, MATCHED, FALLING, SETTLED
    val description: String,      // e.g. "Tide rhombus, row 3 column 4"
)
interface GemRenderer : PieceRenderer {
    @Composable fun Gem(spec: GemSpec, modifier: Modifier)          // static pose
    fun drawGem(scope: DrawScope, spec: GemSpec, anim: GemAnim)     // in the board's single Canvas pass
}
interface BoardSurfaceRenderer { /* existing: Draw(spec) with BoardSurfaceSpec(cols, rows, tone) */ }
interface GemCelebration : CelebrationRenderer { fun burst(kind: GemBurst, budget: ParticleBudget): CelebrationPlan }
```
GemBurst = CLEAR, SPECIAL_CREATE, SPECIAL_FIRE(kind), CHAIN(n), LEVEL_COMPLETE, SHUFFLE, RELIC_COLLECT, FIREFLY_FREE.
Rules from the architecture apply: state read only in draw lambdas; no self-owned loops (shared frame clock); the renderer MUST draw the mark glyph when `LocalColorblindMode`; semantics identical across tiers.

## 5. Fallback chain 3D -> 2.5D -> flat

Per surface and per device, exactly as ARCHITECTURE section 4:
1. Requested tier (user, per-game override) is lowered by: kid-safety filter, Reduced Motion (removes tilt/parallax, not the tier itself), Quality (Battery saver forces FLAT), device caps (LOW RAM: no FULL_3D; API < 33: no AGSL normal maps, 2.5D uses baked highlight), pack availability (on-demand 3D pack missing or FAILED -> 2.5D with a reason chip), measured frame health (2 bad windows demote one tier, never promote mid-game).
2. A pack may implement only some surfaces: the 3D baked pack provides PIECE + BOARD_SURFACE + CELEBRATION; BACKGROUND, CHROME fall back to 2.5D while the board is 3D (mixing is allowed per surface).
3. Missing gem sprite or hash mismatch demotes only that gem kind to the lower tier's renderer (never a blank cell); a debug assertion lists demotions.
4. Colour-blind mode: if a pack cannot draw marks, the surface falls back to Classic for that game while the mode is on.
Example resolutions: Pixel-class phone, Balanced, Standard -> PIECE 2.5D, BOARD 2.5D; Fold 5 inner, High -> PIECE 3D, BOARD 3D; Battery saver -> everything FLAT, particles 0; Reduced Motion on 3D request -> 3D art with tilt 0, Calm timings.

## 6. Motion and quality mapping

| Setting | Gem Cascade behaviour |
|---|---|
| Calm | cross-fades and snaps (`SCRIPT.md` D2 Calm column); no idle shimmer; chain label fades; shake 0 |
| Standard | default timings in `GCAnimation`; bounce 12 percent |
| Lively | anticipation, overshoot 18 percent, hit-stop 50 ms, shake 4 px on big chains, double particles; flashes capped |
| Quality Auto | resolves per `SCALE.md` section 1; never above choice |
| Per-game override | Hub "Look" row inside Gem Cascade; pickers list only styles this game implements |
| Kid profiles | Storybook pack, Calm for 4-6, parent-gated pickers |

## 7. What the pilot must prove (acceptance)

1. The same engine replay produces identical score and board hash in all 3 styles x 3 motion levels (test).
2. Switching style mid-level re-skins instantly without losing state (swap `LocalStylePack`; no board rebuild).
3. Fallback totality: every (Surface, Tier, caps) combination resolves to a drawable renderer.
4. Budgets in `SCALE.md` hold on the owner device kit (tablet, Fold, one mid-range phone).
5. Colour-blind marks present in all three styles; contrast >= 3:1 for pieces, >= 4.5:1 for text (repo `Wcag` checks).
6. Reduced Motion produces no tilt, parallax, shake or loops anywhere in the game.
7. 3D pack absent -> clean 2.5D with a reason chip; removing the pack mid-session demotes cleanly.

## 8. Shared kit pieces this game forces into existence

`BoardLayer` (cached static layer + tilt wrapper with inverse hit testing), `ParticleField` on the shared frame clock, `ChainPacer` (speed-up curve), `DailySeed`, `ScanController` (one-switch), `StickerStore` (local). Each is reusable by Reversi, Connect Four, Mahjong, Dots and Boxes and other board games.
