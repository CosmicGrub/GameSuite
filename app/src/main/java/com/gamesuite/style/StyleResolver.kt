package com.gamesuite.style

/** Asset id the baked-3D pack needs (the Gem Cascade pilot's sprite atlas). */
const val ASSET_GEM_ATLAS = "gem-cascade-atlas"

/** The built-in packs: Classic (flat, total), Soft 2.5D, and the baked-3D pack (sprites, needs assets). */
object StylePackRegistry {
    private fun s(t: ArtTier, ms: Float, mb: Float, assets: String? = null) = SurfaceSupport(t, SurfaceCost(ms, mb), needsAssets = assets)

    val classic: StylePack = DataStylePack(
        "classic", null,
        Surface.values().associateWith { listOf(s(ArtTier.FLAT, if (it == Surface.PIECE) 1.0f else if (it == Surface.BOARD_SURFACE) 0.1f else 0.1f, 0f)) }
    )

    val soft25d: StylePack = DataStylePack(
        "soft25d", "classic",
        mapOf(
            Surface.BOARD_SURFACE to listOf(s(ArtTier.SOFT_25D, 0.3f, 1f)),
            Surface.PIECE to listOf(s(ArtTier.SOFT_25D, 2.0f, 1f)),
            Surface.BACKGROUND to listOf(s(ArtTier.SOFT_25D, 0.5f, 1f)),
            Surface.CHROME to listOf(s(ArtTier.SOFT_25D, 0.1f, 0f)),
            Surface.CELEBRATION to listOf(s(ArtTier.SOFT_25D, 0.5f, 0f))
        )
    )

    /** Provides PIECE + BOARD_SURFACE + CELEBRATION at FULL_3D; BACKGROUND and CHROME fall back to 2.5D (mixing is allowed). */
    val baked3d: StylePack = DataStylePack(
        "baked3d", "soft25d",
        mapOf(
            Surface.BOARD_SURFACE to listOf(s(ArtTier.FULL_3D, 0.6f, 2f, ASSET_GEM_ATLAS)),
            Surface.PIECE to listOf(s(ArtTier.FULL_3D, 1.6f, 6f, ASSET_GEM_ATLAS)),
            Surface.CELEBRATION to listOf(s(ArtTier.FULL_3D, 1.0f, 0f))
        )
    )

    /** Resolution order: richest first, Classic last (it is total). */
    val packs: List<StylePack> = listOf(baked3d, soft25d, classic)
}

/** Which games implement which tiers, and which surfaces they use. Unregistered games are flat only. */
object GameStyleSupport {
    const val GEM_CASCADE = "gem-cascade"
    private val table: Map<String, StyleNeeds> = mapOf(
        GEM_CASCADE to StyleNeeds(
            setOf(Surface.BOARD_SURFACE, Surface.PIECE, Surface.BACKGROUND, Surface.CHROME, Surface.CELEBRATION, Surface.SOUND_PALETTE, Surface.HAPTIC_PROFILE),
            maxTier = ArtTier.FULL_3D
        )
    )
    fun needs(gameId: String): StyleNeeds = table[gameId] ?: StyleNeeds.DEFAULT
    fun tiersOffered(gameId: String): List<ArtTier> = ArtTier.values().filter { it <= needs(gameId).maxTier }
}

/** Everything the resolver needs besides the preferences. */
data class StyleContext(
    val caps: DeviceCaps = DeviceCaps.CAPABLE,
    val isKid: Boolean = false,
    /** Kid profile whose parent allowed 3D (default false: kids are capped at 2.5D). */
    val kidAllows3D: Boolean = false,
    val reducedMotion: Boolean = false,
    val colorblind: Boolean = false,
    val assetsReady: Set<String> = emptySet(),
    val health: StyleHealth = StyleHealth()
)

/** One-way, per-session demotion of a surface after a runtime failure (a decode error, a missing sprite). Never a crash. */
class StyleHealth {
    private val cap = HashMap<Surface, ArtTier>()
    val demotions: Map<Surface, ArtTier> get() = cap
    fun demote(surface: Surface, below: ArtTier) {
        val lower = ArtTier.values().getOrNull(below.ordinal - 1) ?: ArtTier.FLAT
        val cur = cap[surface]
        if (cur == null || lower < cur) cap[surface] = lower
    }
    fun maxTier(surface: Surface): ArtTier? = cap[surface]
    fun hasDemotions() = cap.isNotEmpty()
}

data class ResolvedSurface(val packId: String, val tier: ArtTier)

/** What a game reads. Built once per (preferences, caps) change. */
class ResolvedStyle(
    val requested: ArtTier,
    val art: Map<Surface, ResolvedSurface>,
    val motion: MotionLevel,
    val quality: QualityLevel,
    /** Tilt of the board plane in degrees (0 under Reduced Motion and in flat). */
    val tiltDegrees: Float,
    val particleBudget: Int,
    val reasons: List<StyleReason>,
    val reducedMotion: Boolean
) {
    fun tierOf(s: Surface): ArtTier = art[s]?.tier ?: ArtTier.FLAT
    /** The tier the piece renderer uses (also decides sprites vs vector). */
    val pieceTier: ArtTier get() = tierOf(Surface.PIECE)
    val boardTier: ArtTier get() = tierOf(Surface.BOARD_SURFACE)
    val celebrationTier: ArtTier get() = tierOf(Surface.CELEBRATION)
    val allFlat: Boolean get() = art.values.all { it.tier == ArtTier.FLAT }

    companion object {
        val CLASSIC = ResolvedStyle(ArtTier.FLAT, emptyMap(), MotionLevel.STANDARD, QualityLevel.BALANCED, 0f, 30, emptyList(), false)
    }
}

object StyleResolver {
    /** Style share of a frame (ms) by effective quality (SCALE.md section 1: 2.5 / 5 / 6.7 ms). */
    fun frameBudgetMs(q: QualityLevel): Float = when (q) {
        QualityLevel.BATTERY_SAVER -> 2.5f
        QualityLevel.BALANCED -> 5.0f
        else -> 6.7f
    }

    /** Auto -> a concrete level from the device. Never raised above what the device allows. */
    fun autoQuality(caps: DeviceCaps): QualityLevel = when {
        caps.batterySaver || caps.thermal == ThermalLevel.SEVERE -> QualityLevel.BATTERY_SAVER
        caps.ramClass == RamClass.LOW || caps.thermal == ThermalLevel.MODERATE -> QualityLevel.BALANCED
        caps.ramClass == RamClass.HIGH && caps.agsl -> QualityLevel.HIGH
        else -> QualityLevel.BALANCED
    }

    /** The cap the device puts on any request (OS battery saver and thermal win over an explicit High). */
    fun deviceCap(caps: DeviceCaps): QualityLevel = when {
        caps.batterySaver || caps.thermal == ThermalLevel.SEVERE -> QualityLevel.BATTERY_SAVER
        caps.thermal == ThermalLevel.MODERATE -> QualityLevel.BALANCED
        else -> QualityLevel.HIGH
    }

    fun effectiveQuality(request: QualityLevel, caps: DeviceCaps): QualityLevel {
        val wanted = if (request == QualityLevel.AUTO) autoQuality(caps) else request
        val cap = deviceCap(caps)
        return if (wanted.ordinal <= cap.ordinal) wanted else cap
    }

    /** Particles per burst before motion scaling (SCALE.md budgets), by tier and quality. */
    private fun baseParticles(tier: ArtTier, q: QualityLevel): Int {
        if (q == QualityLevel.BATTERY_SAVER) return 0
        val full = when (tier) { ArtTier.FLAT -> 30; ArtTier.SOFT_25D -> 80; ArtTier.FULL_3D -> 150 }
        return if (q == QualityLevel.BALANCED) full / 2 else full
    }

    fun resolve(prefs: StylePrefs, gameId: String, ctx: StyleContext, packs: List<StylePack> = StylePackRegistry.packs): ResolvedStyle {
        val reasons = LinkedHashSet<StyleReason>()
        val caps = ctx.caps
        val needs = GameStyleSupport.needs(gameId)

        // ---- motion -------------------------------------------------------------------------------------------
        val reduced = ctx.reducedMotion || caps.osAnimationsOff
        var motion = prefs.motion
        if (motion == MotionLevel.LIVELY && !StyleLimits.LIVELY_ENABLED) { motion = MotionLevel.STANDARD; reasons += StyleReason.LIVELY_UNAVAILABLE }
        if (ctx.isKid && motion == MotionLevel.LIVELY) motion = MotionLevel.STANDARD
        if (reduced) { motion = MotionLevel.CALM; reasons += StyleReason.REDUCED_MOTION }

        // ---- quality ------------------------------------------------------------------------------------------
        val quality = effectiveQuality(prefs.quality, caps)
        if (caps.batterySaver) reasons += if (ctx.isKid) StyleReason.ECO_MODE else StyleReason.BATTERY_SAVER
        if (caps.thermal >= ThermalLevel.MODERATE) reasons += if (ctx.isKid) StyleReason.ECO_MODE else StyleReason.THERMAL

        // ---- requested tier, lowered by game, kid, RAM, quality -------------------------------------------------
        val requested = prefs.artFor(gameId)
        var maxTier = requested
        if (maxTier > needs.maxTier) { maxTier = needs.maxTier; reasons += StyleReason.UNSUPPORTED_TIER }
        if (ctx.isKid && maxTier == ArtTier.FULL_3D && !ctx.kidAllows3D) { maxTier = ArtTier.SOFT_25D; reasons += StyleReason.KID_SAFE }
        if (maxTier == ArtTier.FULL_3D && caps.ramClass == RamClass.LOW) { maxTier = ArtTier.SOFT_25D; reasons += StyleReason.LOW_RAM }
        if (quality == QualityLevel.BATTERY_SAVER && maxTier > ArtTier.FLAT) {
            maxTier = ArtTier.FLAT
            reasons += when {
                ctx.isKid && (caps.batterySaver || caps.thermal >= ThermalLevel.SEVERE) -> StyleReason.ECO_MODE
                caps.batterySaver -> StyleReason.BATTERY_SAVER
                caps.thermal >= ThermalLevel.SEVERE -> StyleReason.THERMAL
                else -> StyleReason.QUALITY_BUDGET
            }
        }

        // ---- per-surface resolution with a running frame budget -----------------------------------------------
        val budget = frameBudgetMs(quality)
        var spent = 0f
        val out = LinkedHashMap<Surface, ResolvedSurface>()
        val order = needs.surfaces.sortedBy { priority(it) }
        for (surface in order) {
            val health = ctx.health.maxTier(surface)
            val ceiling = if (health != null && health < maxTier) { reasons += StyleReason.HEALTH_DEMOTED; health } else maxTier
            var chosen: Pair<StylePack, SurfaceSupport>? = null
            var tier = ceiling
            while (true) {
                val cand = pick(packs, surface, tier, ctx, reasons)
                if (cand != null && (tier == ArtTier.FLAT || spent + cand.second.cost.frameMs <= budget)) { chosen = cand; break }
                if (cand != null) reasons += StyleReason.QUALITY_BUDGET
                if (tier == ArtTier.FLAT) break
                tier = ArtTier.values()[tier.ordinal - 1]
            }
            // Classic FLAT is total for every surface, so reaching here without a choice is a bug (tested).
            val (pack, sup) = chosen ?: (StylePackRegistry.classic to StylePackRegistry.classic.support(surface).first())
            spent += sup.cost.frameMs
            out[surface] = ResolvedSurface(pack.id, sup.tier)
        }

        val boardTier = out[Surface.BOARD_SURFACE]?.tier ?: ArtTier.FLAT
        val celeb = out[Surface.CELEBRATION]?.tier ?: ArtTier.FLAT
        val motionScale = when {
            reduced -> 0f
            motion == MotionLevel.CALM -> 0.25f
            else -> 1f
        }
        val particles = (baseParticles(celeb, quality) * motionScale).toInt()
        val tilt = if (reduced || quality == QualityLevel.BATTERY_SAVER) 0f else when (boardTier) {
            ArtTier.FLAT -> 0f; ArtTier.SOFT_25D -> 14f; ArtTier.FULL_3D -> 24f
        }
        return ResolvedStyle(requested, out, motion, quality, tilt, particles, reasons.toList(), reduced)
    }

    /** Draw order of cost accounting: the pieces matter most, so they get budget first. */
    private fun priority(s: Surface): Int = when (s) {
        Surface.PIECE -> 0; Surface.BOARD_SURFACE -> 1; Surface.CELEBRATION -> 2; Surface.BACKGROUND -> 3; Surface.CHROME -> 4; else -> 5
    }

    private fun pick(packs: List<StylePack>, surface: Surface, tier: ArtTier, ctx: StyleContext, reasons: MutableSet<StyleReason>): Pair<StylePack, SurfaceSupport>? {
        for (p in packs) for (sup in p.support(surface)) {
            if (sup.tier != tier) continue
            if (sup.minApi > ctx.caps.apiLevel) continue
            if (sup.needsAgsl && !ctx.caps.agsl) { reasons += StyleReason.NO_AGSL; continue }
            if (ctx.isKid && !sup.kidSafe) { reasons += StyleReason.KID_SAFE; continue }
            if (ctx.colorblind && !sup.drawsColorblindMarks) { reasons += StyleReason.COLORBLIND; continue }
            if (sup.needsAssets != null && sup.needsAssets !in ctx.assetsReady) { reasons += StyleReason.ASSETS_MISSING; continue }
            return p to sup
        }
        return null
    }
}
