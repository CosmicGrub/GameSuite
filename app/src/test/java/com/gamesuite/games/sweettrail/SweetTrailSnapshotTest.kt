package com.gamesuite.games.sweettrail

import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Save and resume: the round trip and every way a saved board can be wrong (docs/design/boardgames/sweet-trail.md, 3.5). */
class SweetTrailSnapshotTest {
    private val cherry = single(TrailColour.CHERRY)
    private val lime = single(TrailColour.LIME)

    private fun SweetTrailGame.s() = state.value!!

    private fun game(roster: Int = 3, configure: SweetTrailGame.() -> Unit = {}) =
        stNewGame(stRoster(roster), random = Random(1)) { fixedSeed = 11; configure() }

    private fun fresh(roster: Int = 3) = stNewGame(stRoster(roster), start = false)

    /** Seat 0 holds a wish and has drawn cherry red: a card is waiting for its decision. */
    private fun pendingGame(): SweetTrailGame {
        val g = game { wishSeats = setOf(0) }
        g.state.value = g.s().withTop(cherry)
        check(g.draw())
        check(g.s().pending)
        return g
    }

    /** Seat 0 has drawn cherry red and hopped to square 1 (seat 1 is on turn). */
    private fun movedGame(): SweetTrailGame {
        val g = game()
        g.state.value = g.s().withTop(cherry)
        check(g.draw())
        return g
    }

    /** Seat 0 stands on the Gooey Puddle (stuck), seat 1 on turn. */
    private fun puddleGame(): SweetTrailGame {
        val g = game()
        g.state.value = g.s().at(29, 0, 0).withTop(lime)
        check(g.draw())
        check(g.s().stuck[0] == 1)
        return g
    }

    private fun wonGame(): SweetTrailGame {
        val g = game()
        g.state.value = g.s().at(57, 0, 0).withTop(cherry)
        check(g.draw())
        check(g.s().over)
        return g
    }

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun edit(text: String, vararg edits: Pair<String, JsonElement?>): String {
        val m = obj(text).toMutableMap()
        for ((k, v) in edits) if (v == null) m.remove(k) else m[k] = v
        return JsonObject(m).toString()
    }

    private fun ints(vararg v: Int) = JsonArray(v.map { JsonPrimitive(it) })
    private fun int(v: Int) = JsonPrimitive(v)
    private fun str(v: String) = JsonPrimitive(v)
    private fun bool(v: Boolean) = JsonPrimitive(v)
    private fun players(ids: List<String>, bots: List<Boolean> = ids.map { false }) = JsonArray(
        ids.indices.map { JsonObject(mapOf("playerId" to str(ids[it]), "displayName" to str("Player ${it + 1}"), "isBot" to bool(bots[it]))) }
    )

    private fun rejected(label: String, text: String, roster: Int = 3) {
        val g = fresh(roster)
        assertFalse("accepted: $label", g.restore(text))
        assertNull("a rejected restore must leave the game untouched ($label)", g.state.value)
    }

    // ---- the round trip ----

    @Test fun snapshotRoundTripRestoresEveryField() {
        val g = game { length = TrailLength.LONG; sticky = false; difficulty = CpuDifficulty.HARD; wishSeats = setOf(0, 2) }
        repeat(7) { if (!g.s().over) { if (g.s().pending) g.keepCard() else g.draw() } }
        val text = g.snapshot()!!
        val twin = fresh()
        assertTrue(twin.restore(text))
        assertEquals(g.s(), twin.s())
        assertEquals(g.sessionWins.value, twin.sessionWins.value)
        assertEquals(TrailLength.LONG, twin.length)
        assertFalse(twin.sticky)
        assertEquals(CpuDifficulty.HARD, twin.difficulty)
        assertEquals(text, twin.snapshot())
        assertFalse(twin.matchOver.value)
    }

    @Test fun everyKindOfTableRoundTrips() {
        for (g in listOf(game(), pendingGame(), movedGame(), puddleGame(), wonGame())) {
            val twin = fresh()
            assertTrue(twin.restore(g.snapshot()!!))
            assertEquals(g.s(), twin.s())
            assertTrue(stEngine.validate(twin.s()).isEmpty())
        }
    }

    @Test fun aFinishedBoardRestoresAsFinished() {
        val g = wonGame()
        val twin = fresh()
        assertTrue(twin.restore(g.snapshot()!!))
        assertTrue(twin.s().over)
        assertEquals(mapOf("p1" to 1, "p2" to 0, "p3" to 0), twin.sessionWins.value)
        assertEquals(SweetTrailReject.OVER, twin.explainDraw())
    }

    @Test fun snapshotIsNullAfterTheMatchIsOver() {
        assertNull(stNewGame(start = false).snapshot())
        val g = game()
        assertNotNull(g.snapshot())
        g.leaveSession()
        assertNull(g.snapshot())
    }

    @Test fun snapshotStaysUnderFourKilobytes() {
        val names = List(4) { "A very long player name $it ${"x".repeat(24)}" }
        val roster = names.mapIndexed { i, n -> com.gamesuite.core.PlayerInfo("p${i + 1}", n) }
        for (seed in 0L until 60L) {
            val g = stNewGame(roster, random = Random(seed)) { wishSeats = setOf(0, 1, 2, 3) }
            repeat((seed % 40).toInt()) { if (!g.s().over) { if (g.s().pending) g.keepCard() else g.draw() } }
            val text = g.snapshot()!!
            assertTrue("${text.length} bytes", text.length < 4096)
        }
    }

    @Test fun restoredBoardContinuesIdenticallyToTheOriginal() {
        for (seed in 0L until 40L) {
            val bots = stRoster(3, setOf(0, 1, 2))
            val a = stNewGame(bots, random = Random(seed)) { wishSeats = setOf(0, 1, 2); difficulty = CpuDifficulty.HARD }
            repeat(((seed * 3) % 17).toInt()) { if (a.botToMove()) a.playBotStep() }
            val b = stNewGame(bots, start = false)
            assertTrue(b.restore(a.snapshot()!!))
            var steps = 0
            while (!a.s().over) {
                a.playBotStep(); b.playBotStep()
                assertEquals("seed $seed step $steps", a.s(), b.s())
                check(++steps < 2_000)
            }
            assertEquals(a.sessionWins.value, b.sessionWins.value)
        }
    }

    @Test fun aRestoredGameKeepsTheLiveNamesOfTheRoster() {
        val g = game()
        val renamed = stRoster(3).mapIndexed { i, p -> p.copy(displayName = if (i == 0) "Zoe" else p.displayName) }
        val twin = stNewGame(renamed, start = false)
        assertTrue(twin.restore(g.snapshot()!!))
        assertEquals("Zoe", twin.s().players[0].displayName)
    }

    // ---- rejections ----

    @Test fun corruptJsonIsRejected() {
        val good = game().snapshot()!!
        for (bad in listOf("", "   ", "not json", "{", "}", "[]", "null", "0", "\"x\"", "{}", "{\"version\":1}", good + "}", "x" + good)) {
            rejected("json '${bad.take(20)}'", bad)
        }
    }

    @Test fun truncatedJsonIsRejected() {
        val good = pendingGame().snapshot()!!
        for (cut in listOf(1, 10, good.length / 4, good.length / 2, good.length - 20, good.length - 2, good.length - 1)) {
            rejected("cut at $cut", good.substring(0, cut))
        }
    }

    @Test fun wrongRosterIsRejected() {
        val good = game().snapshot()!!
        assertFalse(fresh(2).restore(good))
        assertFalse(fresh(4).restore(good))
        assertFalse(stNewGame(listOf(stRoster(3)[0], stRoster(3)[1], com.gamesuite.core.PlayerInfo("zed", "Zed")), start = false).restore(good))
        assertFalse(stNewGame(stRoster(3, setOf(1)), start = false).restore(good)) // a person became a computer
        rejected("ids swapped", edit(good, "players" to players(listOf("p2", "p1", "p3"))))
        rejected("a seat is a computer", edit(good, "players" to players(listOf("p1", "p2", "p3"), listOf(false, true, false))))
        rejected("no players", edit(good, "players" to JsonArray(emptyList())))
        rejected("two players", edit(good, "players" to players(listOf("p1", "p2"))))
    }

    @Test fun pileDiscardAndCardMustCoverAllSixtySixIds() {
        val good = movedGame().snapshot()!!
        val o = obj(good)
        val pile = o["pile"]!!.jsonArray.map { it.toString().toInt() }
        val discard = o["discard"]!!.jsonArray.map { it.toString().toInt() }
        fun arr(l: List<Int>) = ints(*l.toIntArray())
        rejected("duplicate", edit(good, "pile" to arr(listOf(pile[1]) + pile.drop(1))))
        rejected("missing", edit(good, "pile" to arr(pile.dropLast(1))))
        rejected("extra", edit(good, "pile" to arr(pile + 66)))
        rejected("negative", edit(good, "pile" to arr(listOf(-1) + pile.drop(1))))
        rejected("in both piles", edit(good, "discard" to arr(discard + pile.last())))
        rejected("empty everything", edit(good, "pile" to ints(), "discard" to ints()))
        rejected("a card that is not pending", edit(good, "card" to int(pile.last())))
    }

    @Test fun positionOutOfRangeIsRejected() {
        val good = movedGame().snapshot()!!
        rejected("negative", edit(good, "positions" to ints(-1, 0, 0)))
        rejected("beyond the table", edit(good, "positions" to ints(1, 62, 0)))
        rejected("far beyond", edit(good, "positions" to ints(1, 0, Int.MAX_VALUE)))
        rejected("size", edit(good, "positions" to ints(1, 0)))
        rejected("wrong type", edit(good, "positions" to str("1,0,0")))
        val short = game { length = TrailLength.SHORT }.snapshot()!!
        rejected("below the short start", edit(short, "positions" to ints(27, 28, 28)))
        assertTrue(fresh().restore(short))
    }

    @Test fun stuckSeatMustStandOnAPuddle() {
        val good = puddleGame().snapshot()!!
        assertTrue(fresh().restore(good))
        rejected("stuck off the puddle", edit(good, "positions" to ints(30, 0, 0), "eventTo" to int(30)))
        rejected("stuck value 2", edit(good, "stuck" to ints(2, 0, 0)))
        rejected("stuck but the puddle is off", edit(good, "sticky" to bool(false)))
        rejected("a puddle event without the stop", edit(good, "stuck" to ints(0, 0, 0)))
        val moved = movedGame().snapshot()!!
        rejected("a stuck seat on a plain square", edit(moved, "stuck" to ints(1, 0, 0)))
    }

    @Test fun pendingMustMatchThePendingEvent() {
        val good = pendingGame().snapshot()!!
        assertTrue(fresh().restore(good))
        rejected("pending without the event", edit(good, "pending" to bool(false)))
        rejected("the event without pending", edit(good, "event" to str("move")))
        rejected("pending without a card", edit(good, "card" to JsonNull))
        rejected("pending without a wish", edit(good, "wishes" to ints(0, 0, 0)))
        rejected("pending for the wrong seat", edit(good, "currentPlayerIndex" to int(1)))
        rejected("card is not the event card", edit(good, "eventCard" to int(5)))
        rejected("event card missing", edit(good, "eventCard" to JsonNull))
        rejected("a wish already used", edit(good, "eventWished" to bool(true)))
        rejected("pending while over", edit(good, "over" to bool(true), "winnerPlayerId" to str("p1")))
    }

    @Test fun moveEventMustReplayThroughTheEngine() {
        val good = movedGame().snapshot()!!
        assertTrue(fresh().restore(good))
        rejected("wrong destination", edit(good, "eventTo" to int(2), "positions" to ints(2, 0, 0)))
        rejected("destination without the piece", edit(good, "eventTo" to int(2)))
        rejected("piece without the destination", edit(good, "positions" to ints(4, 0, 0)))
        rejected("wrong event kind", edit(good, "event" to str("puddle")))
        rejected("shortcut event on a plain move", edit(good, "event" to str("shortcut")))
        rejected("wrong origin", edit(good, "eventFrom" to int(5)))
        rejected("wrong card", edit(good, "eventCard" to int(single(TrailColour.GRAPE))))
        rejected("no card", edit(good, "eventCard" to JsonNull))
        rejected("no seat", edit(good, "eventSeat" to JsonNull))
        rejected("unknown seat", edit(good, "eventSeat" to int(7)))
        rejected("the turn did not pass", edit(good, "currentPlayerIndex" to int(0)))
        rejected("the turn skipped a seat", edit(good, "currentPlayerIndex" to int(2)))
        rejected("a move before any move was counted", edit(good, "moveSerial" to int(0), "turnsTaken" to int(0)))
        rejected("an unknown event", edit(good, "event" to str("teleport")))
    }

    @Test fun winnerMustBeTheOnlyPieceOnTheFinish() {
        val good = wonGame().snapshot()!!
        assertTrue(fresh().restore(good))
        rejected("another seat won", edit(good, "winnerPlayerId" to str("p2")))
        rejected("no winner id", edit(good, "winnerPlayerId" to JsonNull))
        rejected("unknown winner", edit(good, "winnerPlayerId" to str("ghost")))
        rejected("two pieces on the finish", edit(good, "positions" to ints(61, 61, 0)))
        rejected("not over but a piece finished", edit(good, "over" to bool(false), "winnerPlayerId" to JsonNull))
        rejected("over but nobody finished", edit(good, "positions" to ints(57, 0, 0)))
        rejected("over with the wrong turn", edit(good, "currentPlayerIndex" to int(1)))
    }

    @Test fun counterAndSessionFieldsAreBounded() {
        val good = movedGame().snapshot()!!
        rejected("negative draw serial", edit(good, "drawSerial" to int(-1)))
        rejected("huge draw serial", edit(good, "drawSerial" to int(Int.MAX_VALUE)))
        rejected("huge move serial", edit(good, "moveSerial" to int(Int.MAX_VALUE), "turnsTaken" to int(Int.MAX_VALUE)))
        rejected("turns differ from the move serial", edit(good, "turnsTaken" to int(5)))
        rejected("negative shortcuts", edit(good, "shortcutsFound" to ints(-1, 0, 0)))
        rejected("more shortcuts than turns", edit(good, "shortcutsFound" to ints(9, 9, 9)))
        rejected("stats of the wrong size", edit(good, "puddleStops" to ints(0)))
        rejected("negative reshuffles", edit(good, "reshuffles" to int(-1)))
        rejected("reshuffle before the start", edit(good, "reshuffles" to int(Int.MAX_VALUE)))
        rejected("unknown length", edit(good, "length" to str("epic")))
        rejected("future version", edit(good, "version" to int(2)))
        rejected("unknown computer level", edit(good, "difficulty" to str("EXPERT")))
        rejected("negative session wins", edit(good, "sessionWins" to JsonObject(mapOf("p1" to int(-1)))))
        rejected("session wins of a stranger", edit(good, "sessionWins" to JsonObject(mapOf("ghost" to int(1)))))
        rejected("huge session wins", edit(good, "sessionWins" to JsonObject(mapOf("p1" to int(2_000_000)))))
        rejected("starter out of range", edit(good, "nextStartingPlayerIndex" to int(7)))
        rejected("seat out of range", edit(good, "currentPlayerIndex" to int(-1)))
        rejected("wish count 2", edit(good, "wishes" to ints(2, 0, 0)))
        rejected("wishes of the wrong size", edit(good, "wishes" to ints(1)))
        rejected("a missing field", edit(good, "positions" to null))
        rejected("another missing field", edit(good, "pending" to null))
        rejected("a start event after moves", edit(good, "event" to str("start")))
    }

    @Test fun hugeOrNastyInputNeverThrows() {
        val g = fresh()
        val nasty = listOf(
            "", "\u0000", "null", "[]", "{}", "[null,null]", "1e999", "\"" + "x".repeat(2_000_000) + "\"",
            "x".repeat(5_000_000), "[".repeat(10_000), "{\"a\":".repeat(5_000),
            "{\"positions\":" + (0 until 100_000).joinToString(",", "[", "]"), "🍬".repeat(50_000)
        )
        for (n in nasty) {
            assertFalse(g.restore(n))
        }
        // a valid snapshot padded to a hostile size is refused before it is parsed
        val good = game().snapshot()!!
        assertFalse(g.restore(good + " ".repeat(40_000)))
        assertNull(g.state.value)
        // random garbage and random damage to a real snapshot: never an exception, and never a table that fails validation
        val rnd = Random(2026)
        val alphabet = "{}[]\":,0123456789truefalsn -."
        repeat(1_500) {
            val junk = String(CharArray(rnd.nextInt(1, 200)) { alphabet[rnd.nextInt(alphabet.length)] })
            g.restore(junk)
        }
        val base = pendingGame().snapshot()!!
        var accepted = 0
        repeat(4_000) {
            val chars = base.toCharArray()
            repeat(rnd.nextInt(1, 4)) { chars[rnd.nextInt(chars.size)] = alphabet[rnd.nextInt(alphabet.length)] }
            val twin = fresh()
            if (twin.restore(String(chars))) {
                accepted++
                assertTrue("an accepted damaged snapshot must still be a valid table", stEngine.validate(twin.s()).isEmpty())
            } else {
                assertNull(twin.state.value)
            }
        }
        println("damaged snapshots accepted (harmless damage to a name or a seed): $accepted of 4000")
    }

    @Test fun anAcceptedRestoreReplacesWhateverWasThere() {
        val twin = game { wishSeats = setOf(1) }
        val before = twin.s()
        assertTrue(twin.restore(wonGame().snapshot()!!))
        assertTrue(twin.s() !== before)
        assertTrue(twin.s().over)
        assertEquals(1, twin.sessionWins.value["p1"])
    }

    @Test fun theSnapshotNeverNamesTheLengthWrong() {
        for (length in TrailLength.values()) {
            val g = game { this.length = length }
            val o = obj(g.snapshot()!!)
            assertEquals(length.id, o["length"]!!.toString().trim('"'))
        }
    }
}
