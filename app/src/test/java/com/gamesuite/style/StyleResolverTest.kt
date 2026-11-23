package com.gamesuite.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleResolverTest {
    private val gem = GameStyleSupport.GEM_CASCADE
    private val atlas = setOf(ASSET_GEM_ATLAS)
    private fun resolve(
        art: ArtTier = ArtTier.SOFT_25D, motion: MotionLevel = MotionLevel.STANDARD, quality: QualityLevel = QualityLevel.AUTO,
        caps: DeviceCaps = DeviceCaps.CAPABLE, ctx: StyleContext = StyleContext(caps, assetsReady = atlas), game: String = gem
    ) = StyleResolver.resolve(StylePrefs(art, motion, quality), game, ctx)

    @Test fun defaultsForANewInstallAreSoft25dStandardAuto() {
        val p = StylePrefs()
        assertEquals(ArtTier.SOFT_25D, p.art); assertEquals(MotionLevel.STANDARD, p.motion); assertEquals(QualityLevel.AUTO, p.quality)
        assertEquals(ArtTier.SOFT_25D, resolve(art = p.art).pieceTier)
    }

    @Test fun threeDResolvesToBakedPackWhenAssetsAreReady() {
        val r = resolve(art = ArtTier.FULL_3D)
        assertEquals(ArtTier.FULL_3D, r.pieceTier); assertEquals(ArtTier.FULL_3D, r.boardTier)
        assertEquals("baked3d", r.art[Surface.PIECE]!!.packId)
        // BACKGROUND and CHROME are not baked: mixing falls back to 2.5D per surface
        assertEquals(ArtTier.SOFT_25D, r.tierOf(Surface.BACKGROUND)); assertEquals(ArtTier.SOFT_25D, r.tierOf(Surface.CHROME))
    }

    @Test fun missingAssetsFallBackTo25dWithAReason() {
        val r = resolve(art = ArtTier.FULL_3D, ctx = StyleContext(DeviceCaps.CAPABLE, assetsReady = emptySet()))
        assertEquals(ArtTier.SOFT_25D, r.pieceTier)
        assertTrue(StyleReason.ASSETS_MISSING in r.reasons)
    }

    @Test fun lowRamNeverGets3D() {
        val caps = DeviceCaps.CAPABLE.copy(ramClass = RamClass.LOW)
        val r = resolve(art = ArtTier.FULL_3D, ctx = StyleContext(caps, assetsReady = atlas))
        assertEquals(ArtTier.SOFT_25D, r.pieceTier); assertTrue(StyleReason.LOW_RAM in r.reasons)
    }

    @Test fun osBatterySaverForcesFlatEvenOnExplicitHigh() {
        val caps = DeviceCaps.CAPABLE.copy(batterySaver = true)
        val r = resolve(art = ArtTier.FULL_3D, quality = QualityLevel.HIGH, ctx = StyleContext(caps, assetsReady = atlas))
        assertTrue(r.allFlat); assertEquals(QualityLevel.BATTERY_SAVER, r.quality); assertEquals(0, r.particleBudget)
        assertTrue(StyleReason.BATTERY_SAVER in r.reasons)
    }

    @Test fun explicitBatterySaverQualityIsFlat() {
        assertTrue(resolve(art = ArtTier.FULL_3D, quality = QualityLevel.BATTERY_SAVER).allFlat)
    }

    @Test fun thermalLowersQuality() {
        val mod = resolve(ctx = StyleContext(DeviceCaps.CAPABLE.copy(thermal = ThermalLevel.MODERATE), assetsReady = atlas), quality = QualityLevel.HIGH)
        assertEquals(QualityLevel.BALANCED, mod.quality)
        val sev = resolve(ctx = StyleContext(DeviceCaps.CAPABLE.copy(thermal = ThermalLevel.SEVERE), assetsReady = atlas), quality = QualityLevel.HIGH)
        assertEquals(QualityLevel.BATTERY_SAVER, sev.quality); assertTrue(StyleReason.THERMAL in sev.reasons)
    }

    @Test fun autoNeverExceedsTheDevice() {
        assertEquals(QualityLevel.HIGH, StyleResolver.autoQuality(DeviceCaps.CAPABLE))
        assertEquals(QualityLevel.BALANCED, StyleResolver.autoQuality(DeviceCaps.CAPABLE.copy(ramClass = RamClass.MID)))
        assertEquals(QualityLevel.BALANCED, StyleResolver.autoQuality(DeviceCaps.CAPABLE.copy(apiLevel = 30, agsl = false)))
        assertEquals(QualityLevel.BATTERY_SAVER, StyleResolver.autoQuality(DeviceCaps.CAPABLE.copy(batterySaver = true)))
    }

    @Test fun reducedMotionForcesCalmNoTiltNoBursts() {
        val r = resolve(art = ArtTier.FULL_3D, ctx = StyleContext(DeviceCaps.CAPABLE, reducedMotion = true, assetsReady = atlas))
        assertEquals(MotionLevel.CALM, r.motion); assertEquals(0f, r.tiltDegrees); assertEquals(0, r.particleBudget)
        assertEquals(ArtTier.FULL_3D, r.pieceTier)        // 3D art stays, only the motion goes
        assertTrue(StyleReason.REDUCED_MOTION in r.reasons)
    }

    @Test fun osAnimationsOffCountsAsReducedMotion() {
        val r = resolve(ctx = StyleContext(DeviceCaps.CAPABLE.copy(osAnimationsOff = true), assetsReady = atlas))
        assertEquals(MotionLevel.CALM, r.motion); assertEquals(0, r.particleBudget)
    }

    @Test fun livelyIsNotAvailableYet() {
        val r = resolve(motion = MotionLevel.LIVELY)
        assertEquals(MotionLevel.STANDARD, r.motion); assertTrue(StyleReason.LIVELY_UNAVAILABLE in r.reasons)
    }

    @Test fun calmHasFewerParticlesThanStandard() {
        assertTrue(resolve(motion = MotionLevel.CALM).particleBudget < resolve(motion = MotionLevel.STANDARD).particleBudget)
        assertTrue(resolve(motion = MotionLevel.CALM).particleBudget > 0)
    }

    @Test fun kidsAreCappedAt25dAndGetSilentEco() {
        val kid = resolve(art = ArtTier.FULL_3D, ctx = StyleContext(DeviceCaps.CAPABLE.copy(batterySaver = true), isKid = true, assetsReady = atlas))
        assertTrue(StyleReason.ECO_MODE in kid.reasons); assertFalse(StyleReason.BATTERY_SAVER in kid.reasons); assertFalse(StyleReason.THERMAL in kid.reasons)
        val kid3d = resolve(art = ArtTier.FULL_3D, ctx = StyleContext(DeviceCaps.CAPABLE, isKid = true, assetsReady = atlas))
        assertEquals(ArtTier.SOFT_25D, kid3d.pieceTier); assertTrue(StyleReason.KID_SAFE in kid3d.reasons)
        assertEquals(ArtTier.FULL_3D, resolve(art = ArtTier.FULL_3D, ctx = StyleContext(DeviceCaps.CAPABLE, isKid = true, kidAllows3D = true, assetsReady = atlas)).pieceTier)
    }

    @Test fun unmigratedGamesAreFlatOnly() {
        val r = resolve(art = ArtTier.FULL_3D, game = "some-other-game")
        assertTrue(r.allFlat); assertTrue(StyleReason.UNSUPPORTED_TIER in r.reasons)
    }

    @Test fun perGameOverrideBeatsTheGlobalChoice() {
        val prefs = StylePrefs(art = ArtTier.SOFT_25D, overrides = mapOf(gem to ArtTier.FLAT))
        assertTrue(StyleResolver.resolve(prefs, gem, StyleContext(assetsReady = atlas)).allFlat)
        assertEquals(ArtTier.SOFT_25D, prefs.artFor("other"))
    }

    @Test fun runtimeHealthDemotionIsOneWayAndPerSurface() {
        val health = StyleHealth()
        health.demote(Surface.PIECE, ArtTier.FULL_3D)
        val r = resolve(art = ArtTier.FULL_3D, ctx = StyleContext(DeviceCaps.CAPABLE, assetsReady = atlas, health = health))
        assertEquals(ArtTier.SOFT_25D, r.pieceTier); assertEquals(ArtTier.FULL_3D, r.boardTier)
        assertTrue(StyleReason.HEALTH_DEMOTED in r.reasons)
        health.demote(Surface.PIECE, ArtTier.SOFT_25D)
        assertEquals(ArtTier.FLAT, health.maxTier(Surface.PIECE))
        health.demote(Surface.PIECE, ArtTier.FULL_3D)       // never promotes
        assertEquals(ArtTier.FLAT, health.maxTier(Surface.PIECE))
    }

    @Test fun colourBlindFallsBackWhenAPackCannotDrawMarks() {
        val noMarks = DataStylePack("nomarks", "classic", mapOf(Surface.PIECE to listOf(SurfaceSupport(ArtTier.SOFT_25D, SurfaceCost(1f, 0f), drawsColorblindMarks = false))))
        val packs = listOf(noMarks, StylePackRegistry.classic)
        val r = StyleResolver.resolve(StylePrefs(ArtTier.SOFT_25D), gem, StyleContext(colorblind = true), packs)
        assertEquals(ArtTier.FLAT, r.pieceTier); assertTrue(StyleReason.COLORBLIND in r.reasons)
        assertEquals(ArtTier.SOFT_25D, StyleResolver.resolve(StylePrefs(ArtTier.SOFT_25D), gem, StyleContext(colorblind = false), packs).pieceTier)
    }

    @Test fun tierNeedingAgslIsSkippedBelowApi33() {
        val agsl = DataStylePack("agsl", "classic", mapOf(Surface.PIECE to listOf(SurfaceSupport(ArtTier.SOFT_25D, SurfaceCost(1f, 0f), needsAgsl = true))))
        val packs = listOf(agsl, StylePackRegistry.classic)
        val low = StyleResolver.resolve(StylePrefs(ArtTier.SOFT_25D), gem, StyleContext(DeviceCaps.CAPABLE.copy(apiLevel = 30, agsl = false)), packs)
        assertEquals(ArtTier.FLAT, low.pieceTier); assertTrue(StyleReason.NO_AGSL in low.reasons)
    }

    @Test fun frameBudgetDemotesWhenTheRunningCostIsTooHigh() {
        val heavy = DataStylePack("heavy", "classic", mapOf(Surface.PIECE to listOf(SurfaceSupport(ArtTier.SOFT_25D, SurfaceCost(99f, 0f)))))
        val r = StyleResolver.resolve(StylePrefs(ArtTier.SOFT_25D, quality = QualityLevel.HIGH), gem, StyleContext(), listOf(heavy, StylePackRegistry.classic))
        assertEquals(ArtTier.FLAT, r.pieceTier); assertTrue(StyleReason.QUALITY_BUDGET in r.reasons)
    }

    /** Totality + invariants over the whole input matrix: never throws, never above the request, every used surface resolves. */
    @Test fun fullMatrixIsTotalAndRespectsInvariants() {
        var n = 0
        for (art in ArtTier.values()) for (motion in MotionLevel.values()) for (q in QualityLevel.values())
        for (ram in RamClass.values()) for (saver in listOf(false, true)) for (thermal in ThermalLevel.values())
        for (kid in listOf(false, true)) for (reduced in listOf(false, true)) for (cb in listOf(false, true)) for (assets in listOf(false, true)) {
            val caps = DeviceCaps(if (ram == RamClass.HIGH) 34 else 28, ram == RamClass.HIGH, ram, saver, thermal, false)
            val ctx = StyleContext(caps, isKid = kid, reducedMotion = reduced, colorblind = cb, assetsReady = if (assets) atlas else emptySet())
            val r = StyleResolver.resolve(StylePrefs(art, motion, q), gem, ctx)
            n++
            for (s in GameStyleSupport.needs(gem).surfaces) {
                val rs = r.art[s]
                assertTrue("surface $s unresolved", rs != null)
                assertTrue("above request", rs!!.tier <= art)
            }
            if (ram == RamClass.LOW) assertTrue(r.art.values.none { it.tier == ArtTier.FULL_3D })
            if (kid) assertTrue(r.art.values.none { it.tier == ArtTier.FULL_3D })
            if (!assets) assertTrue(r.art.values.none { it.tier == ArtTier.FULL_3D })
            if (saver) assertTrue(r.allFlat)
            if (reduced) { assertEquals(MotionLevel.CALM, r.motion); assertEquals(0f, r.tiltDegrees); assertEquals(0, r.particleBudget) }
            if (r.quality == QualityLevel.BATTERY_SAVER) { assertTrue(r.allFlat); assertEquals(0, r.particleBudget) }
            assertTrue(r.motion != MotionLevel.LIVELY)
        }
        assertEquals(3 * 3 * 4 * 3 * 2 * 4 * 2 * 2 * 2 * 2, n)
    }

    @Test fun classicIsTotalForEverySurface() {
        for (s in Surface.values()) assertTrue("classic missing $s", StylePackRegistry.classic.support(s).any { it.tier == ArtTier.FLAT })
    }

    @Test fun deviceCapsDetection() {
        val gb = 1024L * 1024 * 1024
        fun raw(api: Int = 34, ram: Long = 8 * gb, low: Boolean = false, saver: Boolean = false, th: Int = 0, anim: Float = 1f) = RawDeviceFacts(api, ram, low, saver, th, anim)
        assertEquals(RamClass.HIGH, DeviceCaps.detect(raw()).ramClass)
        assertEquals(RamClass.MID, DeviceCaps.detect(raw(ram = 4 * gb)).ramClass)
        assertEquals(RamClass.LOW, DeviceCaps.detect(raw(ram = 2 * gb)).ramClass)
        assertEquals(RamClass.LOW, DeviceCaps.detect(raw(low = true)).ramClass)
        assertTrue(DeviceCaps.detect(raw(api = 33)).agsl); assertFalse(DeviceCaps.detect(raw(api = 32)).agsl)
        assertEquals(ThermalLevel.MODERATE, DeviceCaps.detect(raw(th = 2)).thermal)
        assertEquals(ThermalLevel.SEVERE, DeviceCaps.detect(raw(th = 4)).thermal)
        assertTrue(DeviceCaps.detect(raw(anim = 0f)).osAnimationsOff)
    }
}
