package com.gamesuite.games.bumprun

import com.gamesuite.core.PlayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BumpRunSnapshotTest {
    private fun bots(n: Int) = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }

    /** A mid-game table: some boards played by hand for a few turns. */
    private fun midGame(seats: Int = 3, seed: Long = 21, steps: Int = 40, configure: BumpRunGame.() -> Unit = {}): BumpRunGame {
        val g = brGame(players = bots(seats)) { fixedSeed = seed; configure() }
        repeat(steps) {
            val s = g.state.value!!
            if (s.over) return@repeat
            if (s.phase == BumpPhase.AWAIT_DRAW) g.draw() else g.legalMoves().firstOrNull()?.let { g.playMove(it) } ?: g.pass()
        }
        return g
    }

    private fun fresh(seats: Int = 3, configure: BumpRunGame.() -> Unit = {}): BumpRunGame =
        brGame(players = bots(seats), start = false, configure = configure)

    private fun tamper(json: String, edit: (MutableMap<String, Any?>) -> Unit): String {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        val m = el.toMutableMap<String, kotlinx.serialization.json.JsonElement>()
        val plain = HashMap<String, Any?>()
        edit(plain)
        for ((k, v) in plain) m[k] = when (v) {
            null -> kotlinx.serialization.json.JsonNull
            is Int -> kotlinx.serialization.json.JsonPrimitive(v)
            is String -> kotlinx.serialization.json.JsonPrimitive(v)
            is List<*> -> kotlinx.serialization.json.JsonArray(v.map { x ->
                when (x) { is Int -> kotlinx.serialization.json.JsonPrimitive(x); is Boolean -> kotlinx.serialization.json.JsonPrimitive(x); else -> kotlinx.serialization.json.JsonPrimitive(x.toString()) }
            })
            else -> error("unsupported")
        }
        return kotlinx.serialization.json.JsonObject(m).toString()
    }

    private fun restored(json: String, seats: Int = 3): Boolean = fresh(seats).restore(json)

    @Test fun snapshotRoundTripRestoresEveryField() {
        val g = midGame()
        val json = g.snapshot()!!
        val h = fresh()
        assertTrue(h.restore(json))
        assertEquals(g.state.value, h.state.value)
        assertEquals(g.sessionWins.value, h.sessionWins.value)
        assertEquals(json, h.snapshot())
    }

    @Test fun snapshotIsNullAfterTheMatchIsOver() {
        val g = midGame()
        g.leaveSession()
        assertNull(g.snapshot())
        assertNull(fresh().snapshot())
    }

    @Test fun corruptAndTruncatedJsonIsRejected() {
        assertFalse(restored("not json"))
        assertFalse(restored("{}"))
        assertFalse(restored(""))
        val json = midGame().snapshot()!!
        assertFalse(restored(json.substring(0, json.length / 2)))
    }

    @Test fun wrongRosterIsRejected() {
        val json = midGame(seats = 3).snapshot()!!
        assertFalse(restored(json, seats = 2))
        assertFalse(restored(json, seats = 4))
        val other = brGame(players = listOf(PlayerInfo("x", "X"), PlayerInfo("y", "Y"), PlayerInfo("z", "Z")), start = false)
        assertFalse(other.restore(json))
    }

    @Test fun newerSnapshotVersionIsRefused() {
        val json = midGame().snapshot()!!
        assertTrue(restored(json))
        assertFalse(restored(tamper(json) { it["version"] = 99 }))
    }

    @Test fun pileDiscardAndCardMustCoverAllFiftyFourIds() {
        val g = midGame()
        val s = g.state.value!!
        assertFalse(restored(tamper(g.snapshot()!!) { it["pile"] = s.pile.drop(1) }))
        assertFalse(restored(tamper(g.snapshot()!!) { it["pile"] = s.pile.drop(1) + s.pile.first() + 0 }))
    }

    @Test fun twoPawnsOnOneRingSquareIsRejected() {
        val g = midGame(steps = 0)
        val per = g.state.value!!.rules.pawnsPerSeat
        // seat 0 progress 0 is square 5; seat 1 (side 1, exit 14) progress 27 is square 5 too
        val pawns = MutableList(3 * per) { -1 }
        pawns[0] = 0
        pawns[per] = 27
        assertFalse(restored(tamper(g.snapshot()!!) { it["pawns"] = pawns }))
        pawns[per] = 26
        assertTrue(restored(tamper(g.snapshot()!!) { it["pawns"] = pawns }))
    }

    @Test fun shieldOnANonRingPawnIsRejected() {
        val g = midGame(steps = 0)
        val n = g.state.value!!.pawns.size
        assertFalse(restored(tamper(g.snapshot()!!) { it["shields"] = List(n) { i -> i == 0 } }))
    }

    @Test fun awaitChoiceNeedsACardInPlay() {
        val g = midGame(steps = 0)
        assertFalse(restored(tamper(g.snapshot()!!) { it["phase"] = "AWAIT_CHOICE" }))
    }

    @Test fun teamsWithoutFourSeatsAreRejected() {
        val json = midGame(seats = 3).snapshot()!!
        assertTrue(json.contains("\"teams\":false"))
        assertFalse(restored(json.replace("\"teams\":false", "\"teams\":true")))
    }

    @Test fun winnerMustMatchPawnsHome() {
        val g = midGame(steps = 0)
        assertFalse(restored(tamper(g.snapshot()!!) { it["phase"] = "OVER" }))
        assertFalse(restored(tamper(g.snapshot()!!) { it["winnerPlayerIds"] = listOf("p1") }))
    }

    @Test fun pawnProgressOutOfRangeIsRejected() {
        val g = midGame(steps = 0)
        val n = g.state.value!!.pawns.size
        assertFalse(restored(tamper(g.snapshot()!!) { it["pawns"] = List(n) { 40 } }))
        assertFalse(restored(tamper(g.snapshot()!!) { it["pawns"] = List(n) { -2 } }))
        assertFalse(restored(tamper(g.snapshot()!!) { it["doOvers"] = listOf(0, 9, 0) }))
    }

    @Test fun snapshotStaysUnderThreeKilobytes() {
        val g = midGame(seats = 4, steps = 80) { length = BumpLength.LONG }
        assertTrue(g.snapshot()!!.length < 3 * 1024)
    }

    @Test fun restoredBoardContinuesIdenticallyToTheOriginal() {
        val a = midGame(seats = 2, steps = 25)
        val b = fresh(2)
        assertTrue(b.restore(a.snapshot()!!))
        repeat(60) {
            if (a.state.value!!.over) return@repeat
            val s = a.state.value!!
            if (s.phase == BumpPhase.AWAIT_DRAW) { a.draw(); b.draw() } else {
                val m = a.legalMoves().firstOrNull()
                if (m != null) { a.playMove(m); b.playMove(m) } else { a.pass(); b.pass() }
            }
            assertEquals(a.state.value, b.state.value)
        }
    }

    @Test fun hugeOrNastyInputNeverThrows() {
        val json = midGame().snapshot()!!
        val nasty = listOf("[]", "null", "\"x\"", "{\"pawns\":[" + "1,".repeat(100000) + "1]}", json.replace("[", "{"), json.replace("1", "-9999999999"), json + json)
        for (t in nasty) assertFalse(restored(t))
        assertNotNull(json)
    }
}
