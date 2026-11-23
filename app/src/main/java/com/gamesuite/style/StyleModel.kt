package com.gamesuite.style

/**
 * The style system's vocabulary (docs/design/style-packs/ARCHITECTURE.md). Everything in `com.gamesuite.style`
 * that is not under `ui/` or `gem/`'s drawing files is plain Kotlin and runs in JVM unit tests.
 */

/** User-facing "Art style": Flat 2D, 2.5D, 3D (baked sprites). Ordering is cost ordering; fallback goes DOWN. */
enum class ArtTier { FLAT, SOFT_25D, FULL_3D }

/** "Motion level". LIVELY exists in the model but is not offered yet (see [StyleLimits.LIVELY_ENABLED]). */
enum class MotionLevel { CALM, STANDARD, LIVELY }

/** "Quality". Order matters: lower ordinal = cheaper. AUTO is resolved from the device, never above the request. */
enum class QualityLevel { BATTERY_SAVER, BALANCED, HIGH, AUTO }

/** What a pack can draw. A game declares which of these it uses. */
enum class Surface {
    BOARD_SURFACE, PIECE, CARD, DIE, TILE, BACKGROUND, CHROME, CELEBRATION, SOUND_PALETTE, HAPTIC_PROFILE
}

/** Why a request was lowered. The Settings UI shows these; nothing disappears silently (except for kids: [ECO_MODE]). */
enum class StyleReason {
    ASSETS_MISSING, LOW_RAM, BATTERY_SAVER, THERMAL, NO_AGSL, REDUCED_MOTION, KID_SAFE, COLORBLIND,
    UNSUPPORTED_TIER, LIVELY_UNAVAILABLE, ECO_MODE, HEALTH_DEMOTED, QUALITY_BUDGET
}

enum class RamClass { LOW, MID, HIGH }
enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE }

object StyleLimits {
    /** Lively is only allowed where a perf budget is proven. Not proven yet, so it resolves to STANDARD. */
    const val LIVELY_ENABLED = false
    /** Defaults for a new install: 2.5D with 3D opt-in. */
    val DEFAULT_ART = ArtTier.SOFT_25D
    val DEFAULT_MOTION = MotionLevel.STANDARD
    val DEFAULT_QUALITY = QualityLevel.AUTO
    /** Devices under this much RAM (or flagged low-RAM by Android) never get 3D. */
    const val LOW_RAM_BYTES = 3L * 1024 * 1024 * 1024
    const val HIGH_RAM_BYTES = 6L * 1024 * 1024 * 1024
}

/** Estimated cost of one surface at one tier for a whole screen (EST, to be measured; SCALE.md section 4). */
data class SurfaceCost(val frameMs: Float, val residentMb: Float)

/** What a pack offers for one surface at one tier. [needsAssets] null = pure code, always bundled. */
data class SurfaceSupport(
    val tier: ArtTier,
    val cost: SurfaceCost,
    val needsAssets: String? = null,
    val minApi: Int = 26,
    val needsAgsl: Boolean = false,
    val kidSafe: Boolean = true,
    /** The renderer draws the colour-blind shape cue (all Gem Cascade tiers do: shapes are baked into the art). */
    val drawsColorblindMarks: Boolean = true
)

interface StylePack {
    val id: String
    val parentId: String?
    /** One entry per tier this pack implements for [surface]; empty = not implemented (falls back). */
    fun support(surface: Surface): List<SurfaceSupport>
}

/** A pack described by data. */
class DataStylePack(
    override val id: String,
    override val parentId: String?,
    private val table: Map<Surface, List<SurfaceSupport>>
) : StylePack {
    override fun support(surface: Surface): List<SurfaceSupport> = table[surface].orEmpty()
}

/** Marker for per-surface renderers. Only the Gem Cascade piece/board/celebration renderers exist today; the rest are stubs. */
sealed interface SurfaceRenderer
interface CardRenderer : SurfaceRenderer      // stub: not built in Phase 1
interface DieRenderer : SurfaceRenderer       // stub
interface TileRenderer : SurfaceRenderer      // stub
interface BackgroundRenderer : SurfaceRenderer // stub
interface ChromeRenderer : SurfaceRenderer    // stub
interface SoundPalette : SurfaceRenderer      // stub
interface HapticProfile : SurfaceRenderer     // stub
/** Implemented by com.gamesuite.style.gem for Gem Cascade. */
interface PieceRenderer : SurfaceRenderer
interface BoardSurfaceRenderer : SurfaceRenderer
interface CelebrationRenderer : SurfaceRenderer

/** What a game uses. Default for an unmigrated game: flat only. */
data class StyleNeeds(val surfaces: Set<Surface>, val maxTier: ArtTier = ArtTier.FLAT) {
    companion object {
        val DEFAULT = StyleNeeds(setOf(Surface.BOARD_SURFACE, Surface.BACKGROUND, Surface.CHROME, Surface.CELEBRATION, Surface.SOUND_PALETTE, Surface.HAPTIC_PROFILE))
    }
}
