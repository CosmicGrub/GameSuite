package com.gamesuite.games.slideclimb

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Audit leftovers F10 (bounce then rope/slide), F11 (Easy bot and a winning roll), F14 (same table), snapshot version. */
class SlideClimbSchemaAndBounceTest {
    private val alwaysTrue = object : Random() {
        override fun nextBits(bitCount: Int): Int = -1
    }

    // ---- F11 ----

    @Test fun easyNeverThrowsAwayAWinningRollEvenWhenTheCoinSaysSo() {
        val layout = SlideClimbLayout.DEFAULT
        assertTrue(alwaysTrue.nextBoolean())
        assertFalse(SlideClimbBot.wantsSpareRoll(99, 1, layout, false, CpuDifficulty.EASY, alwaysTrue))
        assertFalse(SlideClimbBot.wantsSpareRoll(97, 3, layout, true, CpuDifficulty.EASY, alwaysTrue))
        // away from the finish Easy still follows the coin
        assertTrue(SlideClimbBot.wantsSpareRoll(14, 1, layout, false, CpuDifficulty.EASY, alwaysTrue))
        assertFalse(SlideClimbBot.wantsSpareRoll(14, 1, layout, false, CpuDifficulty.EASY, Random(0).let { object : Random() { override fun nextBits(bitCount: Int) = 0 } }))
    }

    // ---- F10 ----

    private fun bounceOntoSlide(): SlideClimbGame {
        val g = scNewGame(scHumans(2), SlideClimbScriptedRandom(5))
        g.forcePositions(listOf(99, 0))
        assertTrue(g.roll())               // 99 + 5 = 104 -> bounces to 96, the head of a slide to 73
        return g
    }

    @Test fun aBounceThatEndsOnASlideIsRecordedAsBounced() {
        val s = bounceOntoSlide().state.value!!
        assertEquals(SlideClimbEvent.SLIDE, s.event)
        assertEquals(96, s.eventLanded); assertEquals(73, s.eventTo)
        assertTrue(s.eventBounced)
        assertTrue(s.bouncedIntoJump)
    }

    @Test fun aPlainSlideOrAPlainBounceIsNotABouncedJump() {
        val slide = scNewGame(scHumans(2), SlideClimbScriptedRandom(4)).also { it.forcePositions(listOf(14, 0)); it.roll() }.state.value!!
        assertEquals(SlideClimbEvent.SLIDE, slide.event)
        assertFalse(slide.eventBounced); assertFalse(slide.bouncedIntoJump)
        val bounce = scNewGame(scHumans(2), SlideClimbScriptedRandom(5)).also { it.forcePositions(listOf(98, 0)); it.roll() }.state.value!!
        assertEquals(SlideClimbEvent.BOUNCE, bounce.event)
        assertTrue(bounce.eventBounced); assertFalse(bounce.bouncedIntoJump)   // already worded as a bounce
    }

    @Test fun theBouncedFlagSurvivesASnapshotRestore() {
        val g = bounceOntoSlide()
        val twin = scNewGame(scHumans(2), start = false)
        assertTrue(twin.restore(g.snapshot()!!))
        assertEquals(g.state.value, twin.state.value)
        assertTrue(twin.state.value!!.bouncedIntoJump)
    }

    // ---- snapshot version ----

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun with(text: String, key: String, v: JsonElement) = JsonObject(obj(text) + (key to v)).toString()
    private fun without(text: String, vararg keys: String) = JsonObject(obj(text) - keys.toSet()).toString()
    private fun fresh(n: Int = 2) = scNewGame(scHumans(n), start = false)

    @Test fun snapshotsCarryTheCurrentVersion() {
        val text = scNewGame().snapshot()!!
        assertEquals(2, obj(text)["version"]!!.jsonPrimitive.int)
        assertEquals(SLIDE_CLIMB_SNAPSHOT_VERSION, 2)
    }

    @Test fun aSnapshotFromAFutureVersionIsRefusedAndLeavesTheGameUntouched() {
        val text = scNewGame().snapshot()!!
        for (v in listOf(3, 99, Int.MAX_VALUE, 0, -1)) {
            val twin = fresh()
            assertFalse("version $v accepted", twin.restore(with(text, "version", JsonPrimitive(v))))
            assertNull(twin.state.value)
        }
        assertTrue(fresh().restore(with(text, "version", JsonPrimitive(2))))
    }

    @Test fun aVersionOneSaveWithNoVersionFieldStillRestores() {
        val g = scNewGame(scHumans(2), SlideClimbScriptedRandom(3, 4))
        g.roll()
        val old = without(g.snapshot()!!, "version", "eventBounced")
        val twin = fresh()
        assertTrue(twin.restore(old))
        assertEquals(g.state.value, twin.state.value)
    }

    @Test fun aVersionOneBounceOntoASlideRestoresWithTheBounceDerivedFromTheRoll() {
        val g = bounceOntoSlide()
        val old = without(g.snapshot()!!, "version", "eventBounced")
        val twin = fresh()
        assertTrue(twin.restore(old))
        assertTrue(twin.state.value!!.eventBounced)
        assertEquals(g.state.value, twin.state.value)
    }

    @Test fun aVersionTwoSaveMustAgreeWithItsRollAboutTheBounce() {
        val g = bounceOntoSlide()
        val good = g.snapshot()!!
        assertFalse(fresh().restore(with(good, "eventBounced", JsonPrimitive(false))))
        val plain = scNewGame(scHumans(2), SlideClimbScriptedRandom(3)).also { it.roll() }.snapshot()!!
        assertFalse(fresh().restore(with(plain, "eventBounced", JsonPrimitive(true))))
    }

    @Test fun aBounceFlagOnAStartOrPendingRollEventIsRefused() {
        val start = scNewGame().snapshot()!!
        assertFalse(fresh().restore(with(start, "eventBounced", JsonPrimitive(true))))
        val pending = scNewGame(scHumans(2), SlideClimbScriptedRandom(4)) { handicapSeats = setOf(0) }.also { it.roll() }.snapshot()!!
        assertTrue(fresh().restore(pending))
        assertFalse(fresh().restore(with(pending, "eventBounced", JsonPrimitive(true))))
    }

    // ---- F14 ----

    @Test fun anEqualContextIsTheSameTableButAChangedOneIsNot() {
        fun ctx(players: List<PlayerInfo>, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) =
            GameContext(mode, players, 0, LocalPassAndPlayTransport())
        val a = ctx(scHumans(2))
        assertTrue(slideClimbSameTable(a, ctx(scHumans(2))))      // new transport object, same table
        assertFalse(slideClimbSameTable(a, ctx(scHumans(3))))
        assertFalse(slideClimbSameTable(a, ctx(scHumans(2).map { it.copy(isBot = true) })))
        assertFalse(slideClimbSameTable(a, ctx(scHumans(2), PlayMode.SINGLE_PLAYER_VS_BOT)))
        assertFalse(slideClimbSameTable(null, a))
        assertFalse(slideClimbSameTable(a, null))
    }
}
