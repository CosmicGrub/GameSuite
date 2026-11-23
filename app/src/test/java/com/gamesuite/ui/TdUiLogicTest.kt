package com.gamesuite.ui

import com.gamesuite.games.towerdefence.TdBoardMath
import com.gamesuite.games.towerdefence.TdEnemyKind
import com.gamesuite.games.towerdefence.TdGrid
import com.gamesuite.games.towerdefence.TdRole
import com.gamesuite.games.towerdefence.TdScan
import com.gamesuite.games.towerdefence.TowerDefenceGame
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Pure parts of the Tower Defence UI: skins, ambient plan, board geometry, one-switch scan, strings. */
class TdUiLogicTest {

    // ---- themes ---------------------------------------------------------------------------------

    @Test
    fun fiveSkinsShipAndEveryRoleAndEnemyHasAName() {
        assertEquals(listOf(TdThemeId.CASTLE, TdThemeId.SPACE, TdThemeId.TOY, TdThemeId.ROMAN, TdThemeId.CHERRY), TdSkins.ALL.map { it.id })
        for (s in TdSkins.ALL) {
            for (r in TdRole.entries) { assertTrue(s.towerNames.getValue(r) != 0); assertNotNull(s.roleColor[r]) }
            for (k in TdEnemyKind.entries) { assertTrue(s.enemyNames.getValue(k) != 0); assertNotNull(s.enemyColor[k]) }
        }
        assertEquals(TdThemeId.CASTLE, TdThemeId.fromKey(null))
        assertEquals(TdThemeId.CASTLE, TdThemeId.fromKey("nonsense"))
        assertEquals(TdThemeId.TOY, TdThemeId.fromKey("toy"))
        assertEquals(TdThemeId.ROMAN, TdThemeId.fromKey("roman"))
        assertEquals(TdThemeId.CHERRY, TdThemeId.fromKey("cherry"))
        assertEquals(TdSkins.ALL.size, TdSkins.ALL.map { it.id.key }.toSet().size)
        for (id in TdThemeId.entries) assertEquals(id, TdSkins.of(id).id)
    }

    @Test
    fun colourBlindPaletteIsDistinctPerRoleAndPerEnemy() {
        assertEquals(TdRole.entries.size, TdSkins.CB_ROLE.values.toSet().size)
        assertEquals(TdEnemyKind.entries.size, TdSkins.CB_ENEMY.values.toSet().size)
    }

    // ---- ambient --------------------------------------------------------------------------------

    @Test
    fun ambientPlanIsDeterministicAndWithinTheDrawCeiling() {
        for (kind in TdAmbientKind.entries) for (m in TdMotion.entries) {
            val a = TdAmbient.plan(kind, m, seed = 5L)
            val b = TdAmbient.plan(kind, m, seed = 5L)
            assertEquals(a, b)
            assertTrue("$kind/$m has ${a.size} elements", a.size <= TdAmbient.MAX_ELEMENTS)
            assertTrue(TdAmbient.drawCalls(a) <= 3 * TdAmbient.MAX_ELEMENTS + 8)
        }
    }

    @Test
    fun calmHasFewerElementsThanStandard() {
        for (kind in TdAmbientKind.entries) {
            val calm = TdAmbient.plan(kind, TdMotion.CALM).size
            val std = TdAmbient.plan(kind, TdMotion.STANDARD).size
            assertTrue("$kind calm $calm < standard $std", calm < std)
        }
    }

    @Test
    fun calmDropsBirdsCometAndBall() {
        val calm = TdAmbient.plan(TdAmbientKind.MEADOW, TdMotion.CALM).map { it.type }
        assertFalse(TdAmbientType.BIRDS in calm)
        assertFalse(TdAmbientType.COMET in TdAmbient.plan(TdAmbientKind.STARFIELD, TdMotion.CALM).map { it.type })
        assertFalse(TdAmbientType.BALL in TdAmbient.plan(TdAmbientKind.NURSERY, TdMotion.CALM).map { it.type })
        assertTrue(TdAmbientType.BIRDS in TdAmbient.plan(TdAmbientKind.MEADOW, TdMotion.STANDARD).map { it.type })
    }

    @Test
    fun ambientLoopsStayInBoundsAndCrossOnSchedule() {
        val cloud = TdAmbient.plan(TdAmbientKind.MEADOW, TdMotion.STANDARD).first { it.type == TdAmbientType.CLOUD }
        for (t in 0..600 step 7) { val x = TdAmbient.cloudX(cloud, t.toFloat()); assertTrue(x >= -0.21f && x <= 1.21f) }
        // A crossing every 40 s lasting 9 s: active at 1 s, idle at 20 s.
        assertNotNull(TdAmbient.crossing(1f, 40f, 9f, 0f))
        assertEquals(null, TdAmbient.crossing(20f, 40f, 9f, 0f))
        for (t in 0..200) { val f = TdAmbient.flicker(t / 10f, 0.3f); assertTrue(f in 0.6f..1.05f) }
        assertEquals(0f, TdAmbient.flagRipple(5f, 0f, TdMotion.OFF, 10f), 0f)
    }

    // ---- board geometry --------------------------------------------------------------------------

    @Test
    fun anchorsSnapToTilesAndStayOnTheGrid() {
        val w = 960f
        val h = 560f
        assertEquals(0 to 0, TdBoardMath.anchorFor(1f, 1f, w, h))
        // Tile (5, 3) starts at x=200, y=120.
        assertEquals(10 to 6, TdBoardMath.anchorFor(210f, 130f, w, h))
        val far = TdBoardMath.anchorFor(w + 500f, h + 500f, w, h)
        assertEquals(TdGrid.COLS - 2, far.first)
        assertEquals(TdGrid.ROWS - 2, far.second)
        val neg = TdBoardMath.anchorFor(-50f, -50f, w, h, snap = false)
        assertEquals(0 to 0, neg)
    }

    @Test
    fun towerHitTestPicksTheNearestWithinTheHalo() {
        val a = TowerDefenceGame.TowerDefenceTower(1, -1, 1, 0f, cx = 10, cy = 10)
        val b = TowerDefenceGame.TowerDefenceTower(2, -1, 1, 0f, cx = 14, cy = 10)
        val w = 960f
        val h = 560f
        val ca = TdGrid.towerCentre(10, 10)
        assertEquals(1, TdBoardMath.towerAt(listOf(a, b), ca.x * 40f, ca.y * 40f, w, h, 0f))
        assertEquals(null, TdBoardMath.towerAt(listOf(a, b), 20f, 20f, w, h, 0.2f))
        // Just outside the footprint but inside a 0.3 tile halo.
        assertEquals(1, TdBoardMath.towerAt(listOf(a, b), (ca.x + 0.7f) * 40f, ca.y * 40f, w, h, 0.3f))
    }

    @Test
    fun a11yGridMapsCellsToAnchorsAndBack() {
        for (col in 0 until TdBoardMath.GRID_COLS) for (row in 0 until TdBoardMath.GRID_ROWS) {
            val (ax, ay) = TdBoardMath.anchorOfA11yCell(col, row)
            assertTrue(ax in 0..TdGrid.COLS - 2 && ay in 0..TdGrid.ROWS - 2)
            val (c2, r2) = TdBoardMath.a11yCellOf(ax, ay)
            assertTrue(kotlin.math.abs(c2 - col) <= 1 && kotlin.math.abs(r2 - row) <= 1)
        }
        assertEquals('A', TdBoardMath.columnLetter(0))
        assertEquals('N', TdBoardMath.columnLetter(13))
    }

    // ---- one-switch scan -------------------------------------------------------------------------

    @Test
    fun oneSwitchScanLocksColumnThenRowThenRole() {
        var scan = TdScan()
        assertEquals(TdScan.Phase.COLUMN, scan.phase)
        repeat(3) { scan = scan.step() }
        val afterColumn = scan.lock() as TdScan.Outcome.Continue
        assertEquals(TdScan.Phase.ROW, afterColumn.scan.phase)
        assertEquals(3, afterColumn.scan.col)
        scan = afterColumn.scan
        repeat(5) { scan = scan.step() }
        val afterRow = scan.lock() as TdScan.Outcome.Continue
        assertEquals(TdScan.Phase.ROLE, afterRow.scan.phase)
        assertEquals(5, afterRow.scan.row)
        scan = afterRow.scan.step()
        val built = scan.lock() as TdScan.Outcome.Build
        assertEquals(TdRole.entries[1], built.role)
        assertEquals(3, built.col)
        assertEquals(5, built.row)
    }

    @Test
    fun oneSwitchScanWrapsAround() {
        var scan = TdScan()
        repeat(TdBoardMath.GRID_COLS) { scan = scan.step() }
        assertEquals(0, scan.index)
        scan = TdScan(TdScan.Phase.ROLE, 0, 1, 1)
        repeat(TdRole.entries.size) { scan = scan.step() }
        assertEquals(0, scan.index)
    }

    // ---- strings ---------------------------------------------------------------------------------

    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        val fromRoot = File("app/src/main/$relative")
        if (fromRoot.exists()) return fromRoot
        error("Cannot find $relative")
    }

    private fun parse(relative: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moduleFile(relative))
        val out = linkedMapOf<String, String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName == "string") out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    private fun placeholders(s: String): List<String> = Regex("%\\d+\\$[sd]").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun everyTowerDefenceStringExistsInSpanishWithTheSamePlaceholders() {
        for (file in listOf("tower_defence_strings.xml", "tower_defence_hub_strings.xml", "tower_defence_more_strings.xml")) {
            val en = parse("res/values/$file")
            val es = parse("res/values-es/$file")
            assertTrue(en.isNotEmpty())
            assertEquals("$file keys", en.keys, es.keys)
            for ((k, v) in en) {
                assertTrue("$k blank in en", v.isNotBlank())
                assertTrue("$k blank in es", es.getValue(k).isNotBlank())
                assertEquals("$k placeholders", placeholders(v), placeholders(es.getValue(k)))
            }
        }
    }

    @Test
    fun tipsMentionedInTheNotesExistInBothLanguages() {
        for (dir in listOf("values", "values-es")) {
            val m = parse("res/$dir/tower_defence_strings.xml")
            for (k in listOf("td_tip_1", "td_tip_2", "td_tip_3", "td_help_text", "td_theme_castle", "td_theme_space", "td_theme_toy")) assertTrue("$dir $k", m.containsKey(k))
        }
    }

    @Test
    fun petalAmbientForCherryRidgeIsBoundedAndHasPetals() {
        val plan = TdAmbient.plan(TdAmbientKind.PETALS, TdMotion.STANDARD)
        assertTrue(plan.any { it.type == TdAmbientType.PETAL })
        assertTrue(plan.size <= TdAmbient.MAX_ELEMENTS)
        assertEquals(TdAmbientKind.PETALS, TdSkins.CHERRY.ambient)
        assertEquals(TdAmbientKind.MEADOW, TdSkins.ROMAN.ambient)
    }

    @Test
    fun newThemesUseDistinctPalettesAndAreNotKidFirst() {
        assertEquals(TdSkins.ALL.size, TdSkins.ALL.map { it.panel }.toSet().size)
        assertFalse(TdSkins.ROMAN.kidFirst)
        assertFalse(TdSkins.CHERRY.kidFirst)
    }

    @Test
    fun tipsAndCoachCopyDescribeCastleSiege() {
        for (dir in listOf("values", "values-es")) {
            val coach = parse("res/$dir/coach_strings.xml")
            for (k in listOf("coach_tower_defence_s1", "coach_tower_defence_s2", "coach_tower_defence_s3", "coach_tower_defence_s4")) {
                assertFalse("$dir $k still describes a path", coach.getValue(k).contains("camino por el") || coach.getValue(k).contains("along a path"))
            }
            assertTrue(parse("res/$dir/polish_strings.xml").containsKey("tip_tower_defence_3"))
        }
    }
}
