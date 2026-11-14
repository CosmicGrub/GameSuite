package com.gamesuite.games.flickrink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the level lists: 14 fish-run rinks ([FlickRinkLevelData.LEVELS_JSONL]) and 6 bullseye boards
 * ([FlickRinkLevelData.BOARDS_JSONL]), one JSON object per line (schema in docs/design/kids-games/flick-rink.md).
 * A bad line is skipped, never thrown; a line that parses but breaks the structural rules of [problems] is skipped too.
 */
object FlickRinkLevels {
    private val rinks: List<RinkLevel> by lazy { parse(FlickRinkLevelData.LEVELS_JSONL).filter { !it.isBoard && problems(it).isEmpty() } }
    private val boards: List<RinkLevel> by lazy { parse(FlickRinkLevelData.BOARDS_JSONL).filter { it.isBoard && problems(it).isEmpty() } }

    /** The 14 fish-run rinks in order. */
    fun all(): List<RinkLevel> = rinks

    /** The 6 bullseye boards in order. */
    fun boards(): List<RinkLevel> = boards

    /** Rinks then boards: the default pool of the engine (it picks the kind it needs). */
    fun everything(): List<RinkLevel> = rinks + boards

    fun byIndex(oneBased: Int): RinkLevel? = rinks.getOrNull(oneBased - 1)

    /** The 1-based rink numbers a match plays, in order: the band's rinks from its first one, [FlickLength.rinks] of them. */
    fun matchRinks(band: FlickBand, length: FlickLength): List<Int> =
        (0 until length.rinks).map { band.firstRink + it }

    /** Parses JSONL text into levels, skipping every line that is not a well-formed level. */
    fun parse(text: String): List<RinkLevel> =
        text.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.mapNotNull { parseLine(it) }.toList()

    fun parseLine(line: String): RinkLevel? = try {
        val o = Json.parseToJsonElement(line).jsonObject
        val band = FlickBand.entries.firstOrNull { it.id == o.str("band") }
        val id = o.str("id")
        val name = o.str("name")
        if (band == null || id == null || name == null) {
            null
        } else {
            RinkLevel(
                id = id,
                name = name,
                band = band,
                par = o.num("par"),
                seed = o.num("seed"),
                fish = o.arr("fish").map { RinkPoint(it.jsonArray.at(0), it.jsonArray.at(1)) },
                walls = o.arr("walls").map { it.jsonArray.seg() },
                posts = o.arr("posts").map { it.jsonArray.disc() },
                gates = o.arr("gates").map { g ->
                    val go = g.jsonObject
                    RinkGate(go.str("id") ?: error("gate id"), go.arr("seg").seg(), go.arr("pad").disc())
                },
                sliders = o.arr("sliders").map { s ->
                    val so = s.jsonObject
                    RinkSlider(so.str("id") ?: error("slider id"), so.arr("a").seg(), so.arr("b").seg())
                },
                slow = o.arr("slow").map { val a = it.jsonArray; RinkRect(a.at(0), a.at(1), a.at(2), a.at(3)) },
                puddles = o.arr("puddles").map { it.jsonArray.disc() },
                target = (o["target"] as? JsonArray)?.let { RinkPoint(it.at(0), it.at(1)) }
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(k: String): Int = getValue(k).jsonPrimitive.int
    private fun JsonObject.arr(k: String): JsonArray = getValue(k).jsonArray
    private fun JsonArray.at(i: Int): Int = this[i].jsonPrimitive.int
    private fun JsonArray.seg(): RinkSegment = RinkSegment(at(0), at(1), at(2), at(3))
    private fun JsonArray.disc(): RinkDisc = RinkDisc(at(0), at(1), at(2))

    /**
     * Structural problems of a level (empty list = fine): ids, three fish (or a target), everything inside the rink,
     * sane radii and counts. Reachability and clearances are checked by the level tests, which flood-fill the rink.
     */
    fun problems(l: RinkLevel): List<String> {
        val out = ArrayList<String>()
        val w = FlickRinkRules.RINK_W
        val h = FlickRinkRules.RINK_H
        fun inside(x: Int, y: Int) = x in 0..w && y in 0..h
        fun seg(s: RinkSegment, what: String) {
            if (!inside(s.x1, s.y1) || !inside(s.x2, s.y2)) out += "$what outside the rink"
        }
        fun disc(d: RinkDisc, what: String) {
            if (d.r <= 0 || d.x - d.r < 0 || d.x + d.r > w || d.y - d.r < 0 || d.y + d.r > h) out += "$what outside the rink"
        }
        val t = l.target
        if (t != null) {
            if (!Regex("tgt\\d\\d").matches(l.id)) out += "board id ${l.id}"
            if (l.fish.isNotEmpty()) out += "a board has no fish"
            if (!inside(t.x, t.y)) out += "target outside the rink"
        } else {
            if (!Regex("rink\\d\\d").matches(l.id)) out += "rink id ${l.id}"
            if (l.fish.size != FlickRinkRules.FISH_PER_RINK) out += "needs exactly three fish"
            l.fish.forEach { if (!inside(it.x, it.y)) out += "fish outside the rink" }
        }
        l.walls.forEach { seg(it, "wall") }
        l.posts.forEach { disc(it, "post") }
        l.puddles.forEach { disc(it, "puddle") }
        l.gates.forEach { seg(it.seg, "gate ${it.id}"); disc(it.pad, "pad ${it.id}") }
        l.sliders.forEach { seg(it.a, "slider ${it.id} A"); seg(it.b, "slider ${it.id} B") }
        l.slow.forEach { if (it.w <= 0 || it.h <= 0 || it.x < 0 || it.y < 0 || it.x + it.w > w || it.y + it.h > h) out += "drift outside the rink" }
        if (l.gates.size > 4 || l.sliders.size > 2 || l.posts.size > 4 || l.puddles.size > 4 || l.walls.size > 24) out += "too many hazards"
        if (l.par < 0) out += "negative par"
        return out
    }
}
