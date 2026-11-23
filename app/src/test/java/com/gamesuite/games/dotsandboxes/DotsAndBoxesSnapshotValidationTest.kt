package com.gamesuite.games.dotsandboxes

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DotsAndBoxesSnapshotValidationTest {
    private val ids = listOf("p1", "p2")
    private val br = 3
    private val bc = 3
    private fun noH() = List((br + 1) * bc) { false }
    private fun noV() = List(br * (bc + 1)) { false }
    private fun owners() = MutableList<Int?>(br * bc) { null }

    /** Draws every edge of box (0,0): top, bottom, left, right. */
    private fun boxZeroDrawn(): Pair<MutableList<Boolean>, MutableList<Boolean>> {
        val h = noH().toMutableList()
        val v = noV().toMutableList()
        h[0] = true; h[bc] = true; v[0] = true; v[1] = true
        return h to v
    }

    private fun check(
        h: List<Boolean> = noH(), v: List<Boolean> = noV(), o: List<Int?> = owners(), scores: List<Int> = listOf(0, 0),
        over: Boolean = false, winner: String? = null, playerIds: List<String> = ids
    ) = DotsAndBoxesPositionCheck.isValid(br, bc, h, v, o, playerIds, scores, over, winner)

    @Test fun legalPositionsPass() {
        assertTrue(check())
        val (h, v) = boxZeroDrawn()
        assertTrue(check(h, v, owners().also { it[0] = 1 }, listOf(0, 1)))
        val allH = List((br + 1) * bc) { true }
        val allV = List(br * (bc + 1)) { true }
        val full = MutableList<Int?>(br * bc) { if (it < 5) 0 else 1 }
        assertTrue(check(allH, allV, full, listOf(5, 4), true, "p1"))
    }

    @Test fun atLeastFifteenCorruptPositionsAreRejected() {
        val allH = List((br + 1) * bc) { true }
        val allV = List(br * (bc + 1)) { true }
        val full54 = MutableList<Int?>(br * bc) { if (it < 5) 0 else 1 }
        val cases = linkedMapOf(
            "owner with no edges drawn" to check(o = owners().also { it[4] = 0 }, scores = listOf(1, 0)),
            "owner with only three edges" to boxZeroDrawn().let { (h, v) -> check(h.also { it[bc] = false }, v, owners().also { it[0] = 0 }, listOf(1, 0)) },
            "four edges drawn but unowned" to boxZeroDrawn().let { (h, v) -> check(h, v, owners(), listOf(0, 0)) },
            "score higher than owned boxes" to boxZeroDrawn().let { (h, v) -> check(h, v, owners().also { it[0] = 1 }, listOf(0, 2)) },
            "score lower than owned boxes" to boxZeroDrawn().let { (h, v) -> check(h, v, owners().also { it[0] = 1 }, listOf(0, 0)) },
            "score credited to the wrong player" to boxZeroDrawn().let { (h, v) -> check(h, v, owners().also { it[0] = 1 }, listOf(1, 0)) },
            "owner index out of range" to boxZeroDrawn().let { (h, v) -> check(h, v, owners().also { it[0] = 2 }, listOf(0, 0)) },
            "negative owner" to boxZeroDrawn().let { (h, v) -> check(h, v, owners().also { it[0] = -1 }, listOf(0, 0)) },
            "negative score" to check(scores = listOf(-1, 0)),
            "score list too short" to check(scores = listOf(0)),
            "board over while boxes are unclaimed" to boxZeroDrawn().let { (h, v) -> check(h, v, owners().also { it[0] = 0 }, listOf(1, 0), over = true, winner = "p1") },
            "all boxes owned but not over" to check(allH, allV, full54, listOf(5, 4), false, null),
            "winner named while in progress" to check(winner = "p1"),
            "winner is the loser" to check(allH, allV, full54, listOf(5, 4), true, "p2"),
            "winner id not a player" to check(allH, allV, full54, listOf(5, 4), true, "zed"),
            "decided board with no winner" to check(allH, allV, full54, listOf(5, 4), true, null),
            "edge list wrong size" to check(h = List(3) { false }),
            "owner list wrong size" to check(o = List(4) { null }),
            "no players" to check(scores = emptyList(), playerIds = emptyList())
        )
        assertTrue(cases.size >= 15)
        for ((name, accepted) in cases) assertFalse("accepted: $name", accepted)
    }

    // ---- through the real restore() ----

    private fun game(): DotsAndBoxesGame {
        val g = DotsAndBoxesGame()
        g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, listOf(PlayerInfo("p1", "P1"), PlayerInfo("p2", "P2")), 0, LocalPassAndPlayTransport()))
        g.startMatch()
        return g
    }

    private fun patch(json: String, vararg edits: Pair<String, JsonElement>): String {
        val m = (Json.parseToJsonElement(json) as JsonObject).toMutableMap()
        edits.forEach { (k, v) -> m[k] = v }
        return JsonObject(m).toString()
    }

    @Test fun restoreRejectsAnOwnedBoxWithNoEdgesAndAMismatchedScoreButAcceptsRealPlay() {
        val a = game()
        a.drawHorizontalEdge(0, 0)
        val snap = a.snapshot()!!
        assertTrue(game().restore(snap))
        val size = a.state.value!!.boxOwner.size
        val bogusOwner = JsonArray(List(size) { if (it == 3) JsonPrimitive(0) else JsonNull })
        assertFalse(game().restore(patch(snap, "boxOwner" to bogusOwner)))
        assertFalse(game().restore(patch(snap, "scores" to JsonArray(listOf(JsonPrimitive(3), JsonPrimitive(0))))))
        assertFalse(game().restore(patch(snap, "boardOver" to JsonPrimitive(true))))
    }
}
