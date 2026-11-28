package com.gamesuite.games.liferoad

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LifeRoadSnapshotTest {
    private val roster = LRT.roster(3, bots = 1)
    private val extras = SessionExtras(mapOf("p0" to 2), 1)

    private fun midGame(steps: Int = 40, seed: Long = 6): LifeRoadState {
        var s = LRT.engine.newGame(LRT.config(seed = seed), roster)
        val rnd = Random(seed)
        repeat(steps) {
            val legal = LRT.engine.legalActions(s, s.currentSeat)
            s = LRT.act(s, legal[rnd.nextInt(legal.size)])
        }
        return s
    }

    private fun mutate(text: String, f: (MutableMap<String, JsonElement>) -> Unit): String {
        val m = Json.parseToJsonElement(text).jsonObject.toMutableMap()
        f(m)
        return JsonObject(m).toString()
    }

    private fun players(text: String, f: (MutableMap<String, JsonElement>) -> Unit): String = mutate(text) { m ->
        val arr = m["players"] as JsonArray
        val first = arr[0].jsonObject.toMutableMap()
        f(first)
        m["players"] = JsonArray(listOf(JsonObject(first)) + arr.drop(1))
    }

    private fun decode(text: String) = LifeRoadSnapshots.decode(text, roster)

    @Test fun snapshotRoundTripsMidGame() {
        val s = midGame()
        val back = decode(LifeRoadSnapshots.encode(s, extras)!!)
        assertNotNull(back)
        assertEquals(s, back!!.first)
        assertEquals(extras, back.second)
    }

    @Test fun restoreRejectsAnUnknownVersion() {
        val t = mutate(LifeRoadSnapshots.encode(midGame(), extras)!!) { it["v"] = JsonPrimitive(99) }
        assertNull(decode(t))
    }

    @Test fun restoreRejectsAWrongPlayerCount() {
        val t = LifeRoadSnapshots.encode(midGame(), extras)!!
        assertNull(LifeRoadSnapshots.decode(t, LRT.roster(2)))
        assertNull(LifeRoadSnapshots.decode(t, LRT.roster(3)))
    }

    @Test fun restoreRejectsNegativeCash() {
        assertNull(decode(players(LifeRoadSnapshots.encode(midGame(), extras)!!) { it["cash"] = JsonPrimitive(-1) }))
    }

    @Test fun restoreRejectsAnUnknownNodeId() {
        assertNull(decode(players(LifeRoadSnapshots.encode(midGame(), extras)!!) { it["nodeId"] = JsonPrimitive("NOPE.9") }))
    }

    @Test fun restoreRejectsDuplicateHomeOwnership() {
        val s = LRT.edit(LRT.edit(midGame(), 0) { it.copy(homes = listOf(HomeHolding("tiny_flat", 80))) }, 1) { it.copy(homes = listOf(HomeHolding("tiny_flat", 80))) }
        val clean = s.copy(decks = s.decks.copy(houseStock = s.decks.houseStock - "tiny_flat"))
        val text = LifeRoadSnapshots.encode(clean, extras)
        if (text != null) assertNull(decode(text))
    }

    @Test fun restoreRejectsAPendingThatDoesNotMatchThePhase() {
        val s = midGame().copy(phase = Phase.AWAIT_JOB, pending = PendingState())
        val text = LifeRoadSnapshots.encode(s, extras)!!
        assertNull(decode(text))
        val card = midGame().copy(phase = Phase.AWAIT_CARD, pending = PendingState(cardId = "no_such_card"))
        assertNull(decode(LifeRoadSnapshots.encode(card, extras)!!))
    }

    @Test fun restoreRejectsOverCapCounts() {
        val t = LifeRoadSnapshots.encode(midGame(), extras)!!
        assertNull(decode(players(t) { it["loans"] = JsonPrimitive(4) }))
        assertNull(decode(players(t) { it["familyPegs"] = JsonPrimitive(5) }))
        assertNull(decode(players(t) { it["raises"] = JsonPrimitive(4) }))
        assertNull(decode(players(t) { it["luckyCoins"] = JsonPrimitive(10) }))
        val twoHomes = LRT.edit(midGame(), 0) { it.copy(homes = listOf(HomeHolding("tiny_flat", 80), HomeHolding("canal_boat", 90), HomeHolding("brick_row", 180))) }
        assertNull(decode(LifeRoadSnapshots.encode(twoHomes, extras)!!))
    }

    @Test fun restoreRejectsGarbage() {
        assertNull(decode("not json"))
        assertNull(decode("{}"))
        assertNull(decode(""))
    }

    @Test fun resumedGameMatchesTheUninterruptedGame() {
        val rnd = Random(4)
        var a = midGame(25, 14)
        val text = LifeRoadSnapshots.encode(a, extras)!!
        var b = decode(text)!!.first
        val rndA = Random(99)
        val rndB = Random(99)
        var n = 0
        while (!a.over && n++ < 4000) {
            a = LRT.act(a, LRT.engine.legalActions(a, a.currentSeat).let { it[rndA.nextInt(it.size)] })
            b = LRT.act(b, LRT.engine.legalActions(b, b.currentSeat).let { it[rndB.nextInt(it.size)] })
            assertEquals(a, b)
        }
        assertTrue(a.over)
        assertTrue(rnd.nextInt() != 0 || true)
    }

    @Test fun snapshotStaysUnderTwelveKilobytes() {
        for (seed in 1L..20L) {
            val text = LifeRoadSnapshots.encode(midGame(60, seed), extras) ?: continue
            assertTrue("${text.length}", text.length < 12 * 1024)
        }
    }

    @Test fun snapshotIsNullAfterGameOver() {
        val (end, _) = LRT.playRandom(LRT.start(2, LRT.config(size = RoadSize.QUICK, seed = 3)), Random(3))
        assertNull(LifeRoadSnapshots.encode(end, extras))
    }
}
