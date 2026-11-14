package com.gamesuite.games.bridgebuilders

import com.gamesuite.ui.GameThemes
import com.gamesuite.ui.GameTips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** The audit leftovers F5 and F7 to F13: resume countdown, waiting carts, scored marker, event priority, copy agreement. */
class BridgeBuildersFixTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    // ---- F11: a "3-2-1" after a long live pause ----

    @Test fun aLongPauseDuringBuildingIsFollowedByAThreeTwoOneWithTheWaterFrozen() {
        val r = bridgeRig()
        r.live()
        r.adv(2000)
        val before = r.s
        r.e.pause()
        r.clock.advance(5000)
        r.e.resume()
        val s = r.s
        assertEquals("COUNTDOWN", s.phase)
        assertEquals(before.tries, s.tries) // not a new try
        assertEquals(before.water, s.water, 0f)
        assertTrue(s.countdownMs in 1..BridgeRules.RESUME_COUNTDOWN_MS.toInt())
        assertEquals(BridgeTapResult.NOT_BUILDING, r.e.tap(0))
        r.adv(BridgeRules.RESUME_COUNTDOWN_MS - 100)
        assertEquals("COUNTDOWN", r.s.phase)
        assertEquals(before.water, r.s.water, 0f)
        r.adv(200)
        assertEquals("BUILD", r.s.phase)
        assertEquals(BridgeEvent.GO, r.s.event)
    }

    @Test fun aShortPauseResumesStraightIntoBuilding() {
        val r = bridgeRig()
        r.live()
        r.e.pause()
        r.clock.advance(BridgeRules.RESUME_GRACE_AFTER_MS - 1)
        r.e.resume()
        assertEquals("BUILD", r.s.phase)
    }

    @Test fun repeatedPauseCallsKeepTheMomentThePauseBegan() {
        val r = bridgeRig()
        r.live()
        r.e.pause()
        r.clock.advance(2000)
        r.e.pause() // the screen re-asserts the pause every beat
        r.clock.advance(1500)
        r.e.resume()
        assertEquals("COUNTDOWN", r.s.phase)
    }

    @Test fun resumeWithoutAPauseOrDuringTheFirstCountdownChangesNothing() {
        val r = bridgeRig()
        r.e.resume()
        assertEquals("COUNTDOWN", r.s.phase)
        r.e.pause()
        r.clock.advance(10_000)
        r.e.resume()
        assertEquals("COUNTDOWN", r.s.phase)
        r.adv(BridgeRules.COUNTDOWN_MS + 3000)
        assertEquals("BUILD", r.s.phase)
        r.e.resume() // not paused: no countdown
        assertEquals("BUILD", r.s.phase)
    }

    @Test fun needsResumeCountdownThreshold() {
        assertFalse(BridgeRules.needsResumeCountdown(BridgeRules.RESUME_GRACE_AFTER_MS - 1))
        assertTrue(BridgeRules.needsResumeCountdown(BridgeRules.RESUME_GRACE_AFTER_MS))
    }

    // ---- F12: a waiting cart keeps sliding ----

    @Test fun aWaitingLanesCartKeepsSlidingButCannotBeTapped() {
        val r = bridgeRig()
        r.live()
        repeat(BridgeRules.MAX_LEAD) { assertTrue(r.place(0).placed) }
        assertEquals(BridgeLaneStatus.WAITING, r.s.lanes[0].status)
        val seen = HashSet<Int>()
        repeat(40) { r.adv(50); seen.add((r.s.lanes[0].marker * 1000).toInt()) }
        assertTrue("cart frozen while waiting", seen.size > 10)
        assertEquals(BridgeLaneStatus.WAITING, r.s.lanes[0].status)
        assertEquals(BridgeTapResult.WAITING, r.e.tap(0))
        assertEquals(BridgeRules.MAX_LEAD, r.s.lanes[0].planks)
    }

    // ---- F8: the scored marker is the drawn marker ----

    @Test fun aTapIsScoredAtTheMarkerTheScreenWasShowing() {
        val r = bridgeRig(seed = 7)
        r.live()
        var guard = 0
        while (abs(r.s.lanes[0].marker - r.s.lanes[0].windowCentre) > 0.03f && guard++ < 5000) r.adv(10)
        val shown = r.s.lanes[0]
        r.clock.advance(7) // time passes before the tap; the screen has not been refreshed
        val result = r.e.tap(0)
        assertTrue(result.placed)
        val expected = BridgeRules.hitQuality(abs(shown.marker - shown.windowCentre).toDouble(), shown.windowWidth.toDouble())
        assertEquals(expected, r.s.lanes[0].lastQuality.toDouble(), 1e-5)
    }

    // ---- F9: one milestone per beat, the most important ----

    @Test fun eventPriorityOrdersTheMilestones() {
        val order = listOf(
            BridgeEvent.START, BridgeEvent.GO, BridgeEvent.WATER_HALF, BridgeEvent.ROW, BridgeEvent.WATER_HIGH, BridgeEvent.CROSS
        )
        for (i in 0 until order.size - 1) assertTrue(BridgeRules.eventPriority(order[i]) < BridgeRules.eventPriority(order[i + 1]))
        assertTrue(BridgeRules.eventPriority(BridgeEvent.WIN) > BridgeRules.eventPriority(BridgeEvent.CROSS))
        assertEquals(BridgeRules.eventPriority(BridgeEvent.WIN), BridgeRules.eventPriority(BridgeEvent.LOSE))
    }

    @Test fun twoMilestonesInOneBeatLeaveTheMoreImportantOne() {
        val clock = BridgeClock()
        val seed = bridgeRig().e.toSave()!!.copy(water = 0.4999)
        val e = BridgeEngine(kotlin.random.Random(3), clock)
        e.setup(bridgeSeats(2, 0))
        assertTrue(e.restore(seed))
        val rig = BridgeRig(e, clock)
        rig.adv(BridgeRules.COUNTDOWN_MS - 50)
        val serial = rig.s.eventSerial
        clock.advance(200) // one beat: the countdown ends (GO) and the water crosses the half mark
        e.tick()
        assertEquals(BridgeEvent.WATER_HALF, rig.s.event)
        assertNotEquals(serial, rig.s.eventSerial)
    }

    // ---- F13: the copies of the first-run text and colour agree ----

    @Test fun hubSpecTipsAndAccentAgreeWithGameTipsAndGameThemes() {
        assertEquals(BridgeBuildersHubSpecs.tips["bridge-builders"], GameTips.tips["bridge-builders"])
        val a = GameThemes.accents["bridge-builders"]!!
        assertEquals(BridgeBuildersHubSpecs.accents["bridge-builders"], a.light to a.dark)
    }

    // ---- F5: the lane number string exists in both languages with the number placeholder ----

    @Test fun laneLabelExistsInBothLanguages() {
        for (d in listOf("values", "values-es")) {
            val xml = moduleFile("res/$d/bridge_builders_fix_strings.xml").readText()
            assertTrue(d, Regex("name=\"bridge_builders_lane_label\">[^<]*%1\\\$d[^<]*<").containsMatchIn(xml))
        }
    }
}
