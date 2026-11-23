package com.gamesuite.ui

import com.gamesuite.games.botbastion.BbArena
import com.gamesuite.games.botbastion.BbMaps
import com.gamesuite.games.botbastion.BbRules
import com.gamesuite.games.botbastion.MapDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Bot Bastion layout rules as numbers: phone portrait at 360 dp, the tabletop split, node touch boxes. */
class BotBastionLayoutTest {

    private fun maps(): List<MapDef> {
        val f = listOf(File("src/main/assets/bot_bastion/maps.json"), File("app/src/main/assets/bot_bastion/maps.json")).first { it.exists() }
        return BbMaps.playable(BbMaps.parse(f.readText()))
    }

    @Test fun phonesAndTabletsPickTheRightMode() {
        assertEquals(BbLayoutMode.PHONE_PORTRAIT, BbLayout.mode(360f, 780f, 1, false))
        assertEquals(BbLayoutMode.PHONE_PORTRAIT, BbLayout.mode(360f, 780f, 2, false)) // phones cap the crew at 2 and stay stacked
        assertEquals(BbLayoutMode.WIDE, BbLayout.mode(800f, 360f, 1, false))
        assertEquals(BbLayoutMode.WIDE, BbLayout.mode(1280f, 800f, 1, false))
        assertEquals(BbLayoutMode.TABLETOP, BbLayout.mode(1280f, 800f, 2, false))
        assertEquals(BbLayoutMode.TABLETOP, BbLayout.mode(800f, 1280f, 4, false))
        assertEquals(BbLayoutMode.TABLETOP, BbLayout.mode(884f, 700f, 1, true)) // a half-folded Fold in tabletop posture, even solo
    }

    @Test fun sidesSplitTheSeats() {
        assertEquals(listOf(0), BbLayout.nearPlayers(1))
        assertEquals(emptyList<Int>(), BbLayout.farPlayers(1))
        assertEquals(listOf(0), BbLayout.nearPlayers(2))
        assertEquals(listOf(1), BbLayout.farPlayers(2))
        assertEquals(listOf(0, 2), BbLayout.nearPlayers(4))
        assertEquals(listOf(1, 3), BbLayout.farPlayers(4))
        assertEquals(0, BbLayout.firstOf(emptyList()))
    }

    @Test fun at360dpTheStackedArenaFitsTheWidthWithItsPadding() {
        val w = BbLayout.portraitArenaWidth(360f)
        assertEquals(344f, w, 0.001f)
        assertTrue(w + 2 * BbLayout.PAD <= 360f)
        // a 640 dp tall phone keeps at least 240 dp under the arena for the HUD and build panel
        assertTrue(640f - 2 * BbLayout.PAD - w / BbLayout.RATIO >= 240f)
        assertEquals(0f, BbLayout.portraitArenaWidth(10f), 0.001f)
    }

    @Test fun theTabletopArenaAndBothBarsFitEveryTabletSize() {
        for ((w, h) in listOf(1280f to 800f, 800f to 1280f, 884f to 1104f, 600f to 960f, 960f to 600f)) {
            for (bars in 1..2) {
                val aw = BbLayout.tabletopArenaWidth(w, h, bars)
                assertTrue("$w x $h arena fits the width", aw <= w - 2 * BbLayout.PAD + 0.001f)
                val used = 2 * BbLayout.PAD + BbLayout.TABLETOP_STRIP + aw / BbLayout.RATIO + bars * BbLayout.MIN_BAR
                assertTrue("$w x $h $bars bar(s) fit the height ($used)", used <= h + 0.001f)
                assertTrue("$w x $h arena is not tiny", aw >= 320f)
            }
        }
    }

    @Test fun nodeBoxesStayInsideTheArenaAndNeverCoverAnotherNode() {
        for (w in listOf(344f, 520f, 900f)) {
            val h = w / BbLayout.RATIO
            for (m in maps()) {
                val boxes = BbLayout.nodeBoxes(m.nodes, w)
                assertEquals(m.nodes.size, boxes.size)
                val scale = w / BbArena.W.toFloat()
                for ((i, b) in boxes.withIndex()) {
                    val where = "${m.id} node $i at $w dp"
                    assertTrue("$where: left/top inside", b.left >= 0f && b.top >= 0f)
                    assertTrue("$where: right/bottom inside", b.left + b.size <= w + 0.01f && b.top + b.size <= h + 0.01f)
                    assertTrue("$where: size ${b.size}", b.size <= BbLayout.MIN_TARGET)
                    for ((j, o) in m.nodes.withIndex()) if (j != i) {
                        val ox = (o.x * scale).toFloat()
                        val oy = (o.y * scale).toFloat()
                        val covered = ox > b.left && ox < b.left + b.size && oy > b.top && oy < b.top + b.size
                        assertFalse("$where covers node $j", covered)
                    }
                }
            }
        }
    }

    @Test fun at360dpEveryNodeBoxIsAtLeast24dpAndBigScreensGetFullSizeTargets() {
        for (m in maps()) {
            assertTrue(m.id, BbLayout.nodeBoxes(m.nodes, 344f).all { it.size >= 24f })
            assertTrue(m.id, BbLayout.nodeBoxes(m.nodes, 1100f).all { it.size == BbLayout.MIN_TARGET })
        }
    }

    @Test fun giftAmountClearsTheEnginesFloor() {
        assertTrue(BbLayout.GIFT_AMOUNT >= BbRules.MIN_GIFT)
    }
}
