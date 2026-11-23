package com.gamesuite.style

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpriteAtlasTest {
    private val dir = File("src/main/assets/styles/gem-cascade")

    @Test fun manifestParsing() {
        val m = AtlasManifest.parse("# c\nsize 32\na 0 0 32 32\nb 32 0 32 32\r\n")!!
        assertEquals(32, m.size); assertEquals(64, m.atlasWidth); assertEquals(32, m.atlasHeight)
        assertEquals(64L * 32 * 4, m.decodedBytes); assertEquals(32, m["b"]!!.x)
        assertNull(AtlasManifest.parse("size 32\na 0 0 0 32"))
        assertNull(AtlasManifest.parse("size x"))
        assertNull(AtlasManifest.parse("a 0 0 1 1"))
        assertNull(AtlasManifest.parse(""))
    }

    @Test fun lruEvictsLeastRecentlyUsedAndRespectsBudget() {
        val c = BudgetLru<String, Long>(100) { it }
        assertTrue(c.put("a", 40)); assertTrue(c.put("b", 40))
        c["a"]                                   // touch a, so b is the eviction candidate
        assertTrue(c.put("c", 40))
        assertEquals(listOf("a", "c"), c.keys()); assertEquals(80L, c.usedBytes)
        assertFalse(c.put("huge", 101))
        assertTrue(c.put("a", 10)); assertEquals(50L, c.usedBytes)
        c.clear(); assertEquals(0L, c.usedBytes)
    }

    @Test fun sizeAndBudgetByRamClass() {
        assertEquals(160, AtlasBudget.atlasSizeFor(RamClass.HIGH)); assertEquals(96, AtlasBudget.atlasSizeFor(RamClass.MID))
        assertNull(AtlasBudget.atlasSizeFor(RamClass.LOW))
        assertEquals(28L * 1024 * 1024, AtlasBudget.maxDecodedBytes(RamClass.HIGH)); assertEquals(18L * 1024 * 1024, AtlasBudget.maxDecodedBytes(RamClass.MID))
        assertEquals(0L, AtlasBudget.maxDecodedBytes(RamClass.LOW))
    }

    @Test fun shippedAtlasesFitTheBudgetsAndMatchTheirManifests() {
        var packBytes = 0L
        for (ram in listOf(RamClass.HIGH, RamClass.MID)) {
            val size = AtlasBudget.atlasSizeFor(ram)!!
            val manifest = AtlasManifest.parse(File(dir, "atlas_$size.txt").readText())
            assertNotNull("manifest $size", manifest)
            val png = File(dir, "atlas_$size.png")
            val img = ImageIO.read(png)
            assertEquals(manifest!!.atlasWidth, img.width); assertEquals(manifest.atlasHeight, img.height)
            assertTrue("decoded ${manifest.decodedBytes} over budget for $ram", manifest.decodedBytes <= AtlasBudget.maxDecodedBytes(ram))
            for (name in listOf("gem0", "gem1", "gem2", "gem3", "gem4", "gem5", "gem6", "prism", "flare_row", "flare_col", "burst", "nova", "cage", "stone1", "stone2", "stone3", "spider", "butterfly", "treasure")) {
                assertNotNull("sprite $name in atlas $size", manifest[name])
            }
            packBytes += png.length() + File(dir, "atlas_$size.txt").length()
        }
        assertTrue("pilot pack is $packBytes bytes", packBytes <= AtlasBudget.PILOT_PACK_MAX_BYTES)
    }

    @Test fun drawBudgetGuardStopsEmbellishmentsFirst() {
        val b = DrawCommandBudget(10)
        assertTrue(b.tryUse(6)); assertTrue(b.tryUse(4)); assertFalse(b.tryUse(1))
        assertEquals(1, b.skipped); b.force(3); assertTrue(b.exceeded)
        b.reset(); assertEquals(0, b.used); assertFalse(b.exceeded)
        assertTrue(DrawCommandBudget.limitFor(QualityLevel.BATTERY_SAVER) < DrawCommandBudget.limitFor(QualityLevel.BALANCED))
        assertTrue(DrawCommandBudget.limitFor(QualityLevel.BALANCED) < DrawCommandBudget.limitFor(QualityLevel.HIGH))
    }
}
