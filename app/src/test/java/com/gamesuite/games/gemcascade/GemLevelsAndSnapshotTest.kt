package com.gamesuite.games.gemcascade

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun gemModuleFile(relative: String): File {
    val here = File("src/main/$relative")
    if (here.exists()) return here
    return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
}

internal fun bundledLevels(): List<GemLevel> = GemLevels.parse(gemModuleFile("assets/gem_cascade/levels.json").readText())

class GemLevelsTest {
    @Test fun thirtyLevelsInOrderWithValidData() {
        val levels = bundledLevels()
        assertEquals(30, levels.size)
        assertEquals((1..30).toList(), levels.map { it.id })
        assertEquals(30, levels.map { it.seed }.toSet().size)
        for (lv in levels) assertEquals("level ${lv.id}", emptyList<String>(), GemLevels.problems(lv))
    }

    @Test fun everyLevelDealsASettledBoardWithALegalMove() {
        for (lv in bundledLevels()) {
            val e = GemEngine.forLevel(lv)
            assertSettled(e.board)
            assertTrue("level ${lv.id}", e.legalActions().isNotEmpty())
            assertTrue(e.goals.isNotEmpty() && e.goals.all { it.target > 0 && !it.done })
            assertEquals(lv.moves, e.movesLeft)
        }
    }

    @Test fun everyLevelIsSolvableByTheSolverWithinItsMoveLimit() {
        val failed = ArrayList<Int>()
        for (lv in bundledLevels()) {
            val path = GemSolver.solveWithin(lv, seed = lv.id.toLong())
            if (path == null) { failed += lv.id; continue }
            val e = GemReplay.run(GemSetup(GemMode.QUEST, level = lv), path)
            if (e == null || e.status != GemStatus.WON || e.movesMade > lv.moves) failed += lv.id
        }
        assertEquals("levels the seeded search could not solve", emptyList<Int>(), failed)
    }

    @Test fun theBundledJsonHasNoTrailingSurprises() {
        val text = gemModuleFile("assets/gem_cascade/levels.json").readText()
        assertTrue(text.contains("\"levels\""))
        assertEquals(30, Regex("\"seed\"").findAll(text).count())
    }

    @Test fun someDailyLevelsAreSolvable() {
        for (day in 20_000L..20_004L) {
            val lv = GemLevels.daily(day)
            assertNotNull("day $day", GemSolver.solveWithin(lv, seed = day))
        }
    }
}

class GemSnapshotTest {
    private fun play(mode: GemMode, n: Int, seed: Long = 7): GemEngine {
        val e = GemEngine.start(mode, 8, 8, seed)
        val bot = GemBot(BotLevel.MEDIUM, 2)
        repeat(n) {
            if (e.status == GemStatus.PLAYING) bot.choose(e)?.let { a -> e.apply(a); if (mode == GemMode.LIGHTNING) e.apply(GemAction.Elapse(600)) }
        }
        return e
    }

    private fun questEngine(): GemEngine {
        val lv = bundledLevels()[11]
        val e = GemEngine.forLevel(lv)
        val bot = GemBot(BotLevel.MEDIUM, 1)
        repeat(3) { bot.choose(e)?.let { a -> e.apply(a) } }
        return e
    }

    @Test fun everyModeRoundTrips() {
        for (mode in GemMode.values()) {
            val e = if (mode == GemMode.QUEST) questEngine() else play(mode, 12)
            val text = GemSnapshot.encode(e, mode, 7)
            assertNotNull("$mode", text)
            val (m2, r) = GemSnapshot.decode(text!!)!!
            assertEquals(mode, m2)
            assertEquals(e.board.encode(), r.board.encode())
            assertEquals(e.rng.state, r.rng.state)
            assertEquals(e.score, r.score)
            assertEquals(e.movesLeft, r.movesLeft)
            assertEquals(e.timeLeftMs, r.timeLeftMs)
            assertEquals(e.goals, r.goals)
            assertEquals(e.actionLog.toList(), r.actionLog.toList())
            // the restored game continues exactly like the original
            val a = e.legalActions().first()
            assertEquals(e.apply(a).events, r.apply(a).events)
            assertEquals(e.board.encode(), r.board.encode())
        }
    }

    @Test fun aFinishedGameHasNoSnapshot() {
        val e = GemEngine.start(GemMode.LIGHTNING, 8, 8, 1)
        e.elapse(80_000)
        assertNull(GemSnapshot.encode(e, GemMode.LIGHTNING, 1))
    }

    @Test fun theGameModuleResumesWhatItSaved() {
        val g = GemCascadeGame { 42L }
        g.mode = GemMode.ZEN
        g.startMatch()
        val g2 = GemCascadeGame { 1L }
        assertTrue(g2.restore(g.snapshot()!!))
        assertEquals(g.view.value!!.board.encode(), g2.view.value!!.board.encode())
        assertEquals(GemMode.ZEN, g2.mode)
        assertTrue(!g2.restore("nonsense"))
    }

    // ---- corruption ----

    private val json = Json { ignoreUnknownKeys = true }

    private fun edit(text: String, change: (MutableMap<String, JsonElement>) -> Unit): String {
        val m = (json.parseToJsonElement(text) as JsonObject).toMutableMap()
        change(m)
        return JsonObject(m).toString()
    }

    private fun str(m: Map<String, JsonElement>, k: String) = (m[k] as JsonPrimitive).content

    private fun editBoard(m: MutableMap<String, JsonElement>, f: (MutableList<MutableList<String>>) -> Unit) {
        val grid = str(m, "board").split(';').map { it.split(' ').toMutableList() }.toMutableList()
        f(grid)
        m["board"] = JsonPrimitive(grid.joinToString(";") { it.joinToString(" ") })
    }

    private fun num(v: Long) = JsonPrimitive(v)
    private fun nums(vararg v: Int) = JsonArray(v.map { JsonPrimitive(it) })

    private val classic: String by lazy { GemSnapshot.encode(play(GemMode.CLASSIC, 6), GemMode.CLASSIC, 1)!! }
    private val lightning: String by lazy { GemSnapshot.encode(play(GemMode.LIGHTNING, 6), GemMode.LIGHTNING, 1)!! }
    private val quest: String by lazy { GemSnapshot.encode(questEngine(), GemMode.QUEST, 1)!! }

    @Test fun theUntouchedSnapshotsAreValid() {
        assertNotNull(GemSnapshot.decode(classic)); assertNotNull(GemSnapshot.decode(lightning)); assertNotNull(GemSnapshot.decode(quest))
    }

    @Test fun thirtyFiveCorruptSnapshotsAreRejected() {
        val bad = LinkedHashMap<String, String>()
        bad["garbage"] = "not json at all"
        bad["empty"] = ""
        bad["version 0"] = edit(classic) { it["version"] = num(0) }
        bad["version 9"] = edit(classic) { it["version"] = num(9) }
        bad["unknown mode"] = edit(classic) { it["mode"] = JsonPrimitive("bogus") }
        bad["rows 5"] = edit(classic) { it["rows"] = num(5) }
        bad["rows 11"] = edit(classic) { it["rows"] = num(11) }
        bad["cols 5"] = edit(classic) { it["cols"] = num(5) }
        bad["kinds 2"] = edit(classic) { it["kinds"] = num(2) }
        bad["kinds 8"] = edit(classic) { it["kinds"] = num(8) }
        bad["board missing"] = edit(classic) { it.remove("board") }
        bad["rng missing"] = edit(classic) { it.remove("rng") }
        bad["board short a row"] = edit(classic) { m -> m["board"] = JsonPrimitive(str(m, "board").substringBeforeLast(';')) }
        bad["malformed token"] = edit(classic) { m -> editBoard(m) { it[0][0] = "zzzzzzzz" } }
        bad["gem kind out of range"] = edit(classic) { m -> editBoard(m) { it[0][0] = "g9-.0000" } }
        bad["empty cell left"] = edit(classic) { m -> editBoard(m) { it[3][3] = "e0-.0000" } }
        bad["pending match"] = edit(classic) { m -> editBoard(m) { g -> for (c in 0..2) g[0][c] = "g1-.0000" } }
        bad["special on a stone"] = edit(classic) { m -> editBoard(m) { it[2][2] = "x0r.0010" } }
        bad["cage on a spider"] = edit(classic) { m -> editBoard(m) { it[2][2] = "s0-L0010" } }
        bad["stone hp 0"] = edit(classic) { m -> editBoard(m) { it[2][2] = "x0-.0000" } }
        bad["stone hp 4"] = edit(classic) { m -> editBoard(m) { it[2][2] = "x0-.0040" } }
        bad["ice 4"] = edit(classic) { m -> editBoard(m) { it[2][2] = "g1-.0004" } }
        bad["timer 99"] = edit(classic) { m -> editBoard(m) { it[2][2] = "g1-.9900" } }
        bad["treasure on the bottom row"] = edit(classic) { m -> editBoard(m) { it[7][7] = "t0-.0000" } }
        bad["butterfly on the top row"] = edit(classic) { m -> editBoard(m) { it[0][0] = "b0-.0000" } }
        bad["negative score"] = edit(classic) { it["score"] = num(-5) }
        bad["huge score"] = edit(classic) { it["score"] = num(2_100_000_000) }
        bad["negative serial"] = edit(classic) { it["serial"] = num(-1) }
        bad["serial too big"] = edit(classic) { it["serial"] = num(2_000_000) }
        bad["moves made over serial"] = edit(classic) { it["movesMade"] = num(999) }
        bad["moves left in an unlimited mode"] = edit(classic) { it["movesLeft"] = num(5) }
        bad["time left in an untimed mode"] = edit(classic) { it["timeLeftMs"] = num(5) }
        bad["classic level 0"] = edit(classic) { it["classicLevel"] = num(0) }
        bad["classic level wrong for the score"] = edit(classic) { it["classicLevel"] = num(9) }
        bad["counters wrong size"] = edit(classic) { it["counters"] = nums(1, 2) }
        bad["negative counter"] = edit(classic) { it["counters"] = nums(-1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) }
        bad["cleared by kind does not add up"] = edit(classic) { it["clearedByKind"] = nums(777, 777, 777, 777, 777, 777, 777) }
        bad["booster over its cap"] = edit(classic) { it["boosters"] = nums(99, 0, 0, 0, 0) }
        bad["boosters wrong size"] = edit(classic) { it["boosters"] = nums(0) }
        bad["actions garbage"] = edit(classic) { it["actions"] = JsonPrimitive("Z9") }
        bad["actions short"] = edit(classic) { it["actions"] = JsonPrimitive("S1,2") }
        bad["goals in a goalless mode"] = edit(classic) { it["goals"] = JsonArray(listOf(nums(0, -1, 100, 0))) }
        bad["stars in a non-quest mode"] = edit(classic) { it["stars"] = nums(1, 2, 3) }
        bad["level id in a non-quest mode"] = edit(classic) { it["levelId"] = num(5) }
        bad["lightning meter 100"] = edit(lightning) { it["speedMeter"] = num(100) }
        bad["lightning no time left"] = edit(lightning) { it["timeLeftMs"] = num(0) }
        bad["lightning negative elapsed"] = edit(lightning) { it["elapsedMs"] = num(-1) }
        bad["quest without goals"] = edit(quest) { it["goals"] = JsonArray(emptyList()) }
        bad["quest goal over target"] = edit(quest) { m -> m["goals"] = JsonArray(listOf(nums(0, -1, 100, 500))) }
        bad["quest goal type 99"] = edit(quest) { m -> m["goals"] = JsonArray(listOf(nums(99, -1, 100, 0))) }
        bad["quest all goals done"] = edit(quest) { m -> m["goals"] = JsonArray(listOf(nums(0, -1, 100, 100))) }
        bad["quest goal counter mismatch"] = edit(quest) { m -> m["goals"] = JsonArray(listOf(nums(0, -1, 100000, 77))) }
        bad["quest bad stars"] = edit(quest) { it["stars"] = nums(300, 200, 100) }
        bad["quest move limit 2"] = edit(quest) { it["moveLimit"] = num(2) }
        bad["quest level id 0"] = edit(quest) { it["levelId"] = num(0) }
        assertTrue(bad.size >= 25)
        for ((name, text) in bad) assertNull("accepted a corrupt snapshot: $name", GemSnapshot.decode(text))
    }

    @Test fun aBoardWithNoLegalMoveIsRefused() {
        val e = engineOn(deadBoard(), GemMode.ZEN)
        assertNull(GemSnapshot.decode(GemSnapshot.encode(e, GemMode.ZEN, 1)!!.replace("\"rows\":8", "\"rows\":6").replace("\"cols\":8", "\"cols\":6")))
    }

    @Test fun unknownFieldsFromANewerBuildAreIgnoredButNotTheVersion() {
        val withExtra = edit(classic) { it["somethingNew"] = JsonPrimitive("x") }
        assertNotNull(GemSnapshot.decode(withExtra))
    }
}
