package com.gamesuite.games.botbastion

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Pt(val x: Double, val y: Double) {
    fun dist(o: Pt): Double = hypot(x - o.x, y - o.y)
}

data class Void(val cx: Double, val cy: Double, val rx: Double, val ry: Double) {
    fun contains(p: Pt): Boolean {
        val dx = (p.x - cx) / rx
        val dy = (p.y - cy) / ry
        return dx * dx + dy * dy <= 1.0
    }
}

/** One enemy corridor as a polyline in world units, with cumulative lengths for O(log n)-free walking. */
class Lane(val points: List<Pt>) {
    private val cum = DoubleArray(points.size)
    val length: Double

    init {
        for (i in 1 until points.size) cum[i] = cum[i - 1] + points[i - 1].dist(points[i])
        length = cum.last()
    }

    /** Position after walking [d] units from the entry (clamped to the ends). */
    fun pointAt(d: Double): Pt {
        if (d <= 0.0) return points.first()
        if (d >= length) return points.last()
        var i = 1
        while (i < points.size - 1 && cum[i] < d) i++
        val seg = cum[i] - cum[i - 1]
        val t = if (seg > 0.0) (d - cum[i - 1]) / seg else 0.0
        val a = points[i - 1]
        val b = points[i]
        return Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }

    fun distanceTo(p: Pt): Double {
        var best = Double.MAX_VALUE
        for (i in 1 until points.size) best = min(best, segDist(p, points[i - 1], points[i]))
        return best
    }

    companion object {
        fun segDist(p: Pt, a: Pt, b: Pt): Double {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
            return hypot(p.x - (a.x + dx * t), p.y - (a.y + dy * t))
        }
    }
}

/** A map in world units (the JSON stores 0..1 coordinates). */
class MapDef(
    val id: String,
    val sector: Int,
    val pace: Double,
    val unlockStars: Int,
    val core: Pt,
    val lanes: List<Lane>,
    val nodes: List<Pt>,
    val voids: List<Void>
) {
    /** Seconds a base Drifter at tier speed 1.0 needs from the entry to the Core on lane [i]. */
    fun traverseSeconds(i: Int): Double = lanes[i].length / (EnemyType.DRIFTER.speedU * pace)
}

/** The skiff patrol rim: a rounded rectangle inset 0.4 u from the arena edge. */
object BbRim {
    private const val INSET = 0.4
    private const val R = 1.6
    private val w = BbArena.W - 2 * INSET
    private val h = BbArena.H - 2 * INSET
    private val straightX = w - 2 * R
    private val straightY = h - 2 * R
    private val arc = PI / 2 * R

    val perimeter: Double = 2 * straightX + 2 * straightY + 4 * arc

    /** The point [s] units along the rim, clockwise from the top edge's left end. */
    fun at(sIn: Double): Pt {
        var s = sIn % perimeter
        if (s < 0) s += perimeter
        val x0 = INSET
        val y0 = INSET
        val x1 = BbArena.W - INSET
        val y1 = BbArena.H - INSET
        if (s < straightX) return Pt(x0 + R + s, y0)
        s -= straightX
        if (s < arc) return corner(x1 - R, y0 + R, -PI / 2 + s / R)
        s -= arc
        if (s < straightY) return Pt(x1, y0 + R + s)
        s -= straightY
        if (s < arc) return corner(x1 - R, y1 - R, s / R)
        s -= arc
        if (s < straightX) return Pt(x1 - R - s, y1)
        s -= straightX
        if (s < arc) return corner(x0 + R, y1 - R, PI / 2 + s / R)
        s -= arc
        if (s < straightY) return Pt(x0, y1 - R - s)
        s -= straightY
        return corner(x0 + R, y0 + R, PI + s / R)
    }

    private fun corner(cx: Double, cy: Double, a: Double) = Pt(cx + R * kotlin.math.cos(a), cy + R * kotlin.math.sin(a))

    private const val SAMPLES = 360
    private val samples = Array(SAMPLES) { at(it * perimeter / SAMPLES) }

    /** Rim arc position closest to [p] (sampled, deterministic). */
    fun nearest(p: Pt): Double {
        var bi = 0
        var bd = Double.MAX_VALUE
        for (i in 0 until SAMPLES) {
            val d = samples[i].dist(p)
            if (d < bd) { bd = d; bi = i }
        }
        return bi * perimeter / SAMPLES
    }
}

object BbMaps {
    const val ASSET = "bot_bastion/maps.json"
    const val MIN_NODES = 12
    const val MAX_NODES = 22
    const val MIN_LANE_GAP = 0.72
    const val MIN_CORE_GAP = 1.35
    const val MIN_NODE_GAP = 1.2
    /** Node coordinates are stored to 3 decimals of a 16 u arena; allow that much slack on the gap rules. */
    const val TOL = 0.02

    /** Parses the bundled JSON; malformed maps are skipped, a broken file yields no maps. */
    fun parse(text: String): List<MapDef> = try {
        val root = Json.parseToJsonElement(text).jsonObject
        root["maps"]!!.jsonArray.mapNotNull { runCatching { parseMap(it.jsonObject) }.getOrNull() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun pt(e: kotlinx.serialization.json.JsonElement): Pt {
        val a = e.jsonArray
        return Pt(a[0].jsonPrimitive.double * BbArena.W, a[1].jsonPrimitive.double * BbArena.H)
    }

    private fun parseMap(o: JsonObject): MapDef = MapDef(
        id = o["id"]!!.jsonPrimitive.content,
        sector = o["sector"]!!.jsonPrimitive.int,
        pace = o["pace"]!!.jsonPrimitive.double,
        unlockStars = o["unlockStars"]!!.jsonPrimitive.int,
        core = pt(o["core"]!!),
        lanes = o["lanes"]!!.jsonArray.map { l -> Lane(l.jsonArray.map { pt(it) }) },
        nodes = o["nodes"]!!.jsonArray.map { pt(it) },
        voids = (o["voids"] as? JsonArray)?.map {
            val a = it.jsonArray
            Void(a[0].jsonPrimitive.double * BbArena.W, a[1].jsonPrimitive.double * BbArena.H,
                a[2].jsonPrimitive.double * BbArena.W, a[3].jsonPrimitive.double * BbArena.H)
        } ?: emptyList()
    )

    /** T11: every rule from MAPS.md. An empty list means the map is playable. */
    fun problems(m: MapDef): List<String> {
        val out = ArrayList<String>()
        if (m.id.isBlank()) out += "blank id"
        if (m.pace <= 0.0 || m.pace > 5.0) out += "pace ${m.pace}"
        if (m.lanes.isEmpty() || m.lanes.size > 8) out += "lane count ${m.lanes.size}"
        if (m.nodes.size !in MIN_NODES..MAX_NODES) out += "node count ${m.nodes.size}"
        for ((i, l) in m.lanes.withIndex()) {
            if (l.points.size < 2 || l.length <= 0.0) { out += "lane $i too short"; continue }
            if (l.points.last().dist(m.core) > TOL) out += "lane $i does not end at the Core"
            val s = l.points.first()
            val onRim = s.x <= 0.0 || s.x >= BbArena.W || s.y <= 0.0 || s.y >= BbArena.H
            if (!onRim) out += "lane $i entry is inside the arena"
        }
        for ((i, n) in m.nodes.withIndex()) {
            if (n.x !in 0.0..BbArena.W || n.y !in 0.0..BbArena.H) out += "node $i outside the arena"
            for ((li, l) in m.lanes.withIndex()) if (l.distanceTo(n) < MIN_LANE_GAP - TOL) out += "node $i too close to lane $li"
            if (m.voids.any { it.contains(n) }) out += "node $i in a void"
            if (n.dist(m.core) < MIN_CORE_GAP - TOL) out += "node $i too close to the Core"
            for (j in 0 until i) if (n.dist(m.nodes[j]) < MIN_NODE_GAP - TOL) out += "nodes $j and $i too close"
        }
        return out
    }

    fun isPlayable(m: MapDef): Boolean = problems(m).isEmpty()

    /** Only the maps that pass [problems]. */
    fun playable(all: List<MapDef>): List<MapDef> = all.filter { isPlayable(it) }.distinctBy { it.id }
}
