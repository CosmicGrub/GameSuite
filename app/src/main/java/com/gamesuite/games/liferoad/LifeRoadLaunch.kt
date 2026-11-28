package com.gamesuite.games.liferoad

import com.gamesuite.settings.CpuDifficulty

/** Turns the Hub's `LaunchOptions.extras` into the rules of one board (keys documented on [LifeRoadHubSpecs]). */
object LifeRoadLaunch {
    fun configFrom(extras: Map<String, String>, seats: Int, difficulty: CpuDifficulty): LifeRoadConfig {
        val variant = RulesVariant.fromId(extras["rules"])
        val size = if (extras["length"] == null && variant == RulesVariant.JUNIOR) RoadSize.QUICK else RoadSize.fromId(extras["length"])
        val head = HeadStart.fromId(extras["handicap"])
        val seatsWithBoost = if (head == HeadStart.NONE) emptySet() else LifeRoadHandicap.parseSeats(extras["handicapFor"] ?: "all", seats)
        return LifeRoadConfig(
            size = size, variant = variant, headStart = head, headStartSeats = seatsWithBoost,
            catchUp = extras["catchUp"] != "false", seed = 0L, difficulty = difficulty
        )
    }
}

/**
 * Board geometry for the screen, in abstract units (one unit = the gap between neighbouring squares). The road is wrapped
 * into rows like a snake; the two lanes of a fork sit one half-row above and below the centre line. Pure, so it is tested.
 */
object LifeRoadLayout {
    data class Pt(val x: Float, val y: Float)

    class Layout(val points: Map<String, Pt>, val width: Float, val height: Float)

    private val laneOf = mapOf(
        "LRN" to -1, "WRK" to 1, "RISK" to -1, "SAFE" to 1, "FAM" to -1, "FREE" to 1, "COZY" to -1, "GRAND" to 1
    )

    private val cache = HashMap<RoadSize, Layout>()

    fun of(size: RoadSize): Layout = synchronized(cache) { cache.getOrPut(size) { build(LifeRoadBoard.of(size)) } }

    fun columns(size: RoadSize): Int = when (size) {
        RoadSize.QUICK -> 8
        RoadSize.STANDARD -> 11
        RoadSize.LONG -> 13
    }

    fun build(g: RoadGraph): Layout {
        val depth = HashMap<String, Int>()
        // longest path from the start over the DAG (relaxation in a topological order from a DFS)
        val order = ArrayList<String>()
        val seen = HashSet<String>()
        fun dfs(id: String) {
            if (!seen.add(id)) return
            g.node(id)!!.next.forEach { dfs(it) }
            order += id
        }
        dfs(g.startId)
        order.reverse()
        depth[g.startId] = 0
        for (id in order) {
            val d = depth[id] ?: 0
            for (n in g.node(id)!!.next) depth[n] = maxOf(depth[n] ?: 0, d + 1)
        }
        val cols = columns(g.size)
        val maxDepth = depth.values.max()
        val rows = maxDepth / cols + 1
        val rowGap = 2.4f
        val pts = HashMap<String, Pt>()
        for ((id, d) in depth) {
            val row = d / cols
            val c = d % cols
            val col = if (row % 2 == 0) c else cols - 1 - c
            val lane = laneOf[g.node(id)!!.segment] ?: 0
            pts[id] = Pt(col.toFloat() + 0.5f, row * rowGap + 0.9f + lane * 0.7f)
        }
        return Layout(pts, cols.toFloat(), rows * rowGap + 0.2f)
    }
}

/** Read-only lookups into the content tables for the screen (the engine's own index is internal). */
object LifeRoadIndexView {
    fun job(id: String): JobDef? = LifeRoadIndex.job[id]
    fun house(id: String): HouseDef? = LifeRoadIndex.house[id]
    fun event(id: String): EventCardDef? = LifeRoadIndex.event[id]
    fun family(id: String): FamilyCardDef? = LifeRoadIndex.family[id]
}
