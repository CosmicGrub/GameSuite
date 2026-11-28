package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadBoardTest {
    private fun routes(g: RoadGraph): List<List<String>> {
        val out = ArrayList<List<String>>()
        fun walk(id: String, path: List<String>) {
            val n = g.node(id)!!
            val p = path + id
            if (n.next.isEmpty()) out += p else n.next.forEach { walk(it, p) }
        }
        walk(g.startId, emptyList())
        return out
    }

    @Test fun everySizeBuildsAConnectedGraph() {
        for (size in RoadSize.values()) {
            val g = LifeRoadBoard.build(size)
            assertEquals(setOf("RC", "RG"), routes(g).map { it.last() }.toSet())
        }
    }

    @Test fun segmentPatternLengthsMatchTheSpec() {
        val expect = mapOf(
            "LRN" to listOf(5, 8, 10), "WRK" to listOf(4, 6, 8), "TA" to listOf(6, 12, 20), "RISK" to listOf(4, 7, 10),
            "SAFE" to listOf(5, 9, 15), "TB" to listOf(6, 12, 22), "FAM" to listOf(5, 8, 13), "FREE" to listOf(5, 8, 13),
            "TC" to listOf(5, 12, 22), "COZY" to listOf(3, 5, 8), "GRAND" to listOf(3, 5, 8)
        )
        for (seg in LifeRoadBoardSpec.SEGMENTS) {
            val e = expect.getValue(seg.id)
            assertEquals(seg.id, e[0], seg.quick.length)
            assertEquals(seg.id, e[1], seg.standard.length)
            assertEquals(seg.id, e[2], seg.long.length)
        }
    }

    @Test fun nodeCountsMatchTheSpec() {
        assertEquals(58, LifeRoadBoard.build(RoadSize.QUICK).nodes.size)
        assertEquals(98, LifeRoadBoard.build(RoadSize.STANDARD).nodes.size)
        assertEquals(148, LifeRoadBoard.build(RoadSize.LONG).nodes.size)
        val std = routes(LifeRoadBoard.build(RoadSize.STANDARD)).map { it.size }
        assertEquals(16, std.size)
        assertTrue("min ${std.min()} max ${std.max()}", std.min() >= 66 && std.max() <= 72)
    }

    @Test fun everyNodeIsReachableAndHasNoDeadEnd() {
        for (size in RoadSize.values()) {
            val g = LifeRoadBoard.build(size)
            assertEquals(g.nodes.size, routes(g).flatten().toSet().size)
            assertTrue(g.nodes.filter { it.kind != NodeKind.RETIRE }.all { it.next.isNotEmpty() })
        }
    }

    @Test fun forksHaveExactlyTwoBranches() {
        val g = LifeRoadBoard.build(RoadSize.STANDARD)
        for (id in LifeRoadBoardSpec.FORK_IDS) assertEquals(id, 2, g.node(id)!!.next.size)
        assertEquals(listOf("LRN.0", "WRK.0"), g.node("S0")!!.next)
        assertEquals(listOf("COZY.0", "GRAND.0"), g.node("F4")!!.next)
    }

    @Test fun graphIsAcyclic() {
        for (size in RoadSize.values()) assertTrue(LifeRoadBoard.build(size).problems().none { it.startsWith("cycle") })
    }

    @Test fun onlyCliffNodesAreBold() {
        for (n in LifeRoadBoard.build(RoadSize.LONG).nodes) assertEquals(n.id, n.segment == "RISK", n.bold)
    }

    @Test fun everyKindCodeParses() {
        for (seg in LifeRoadBoardSpec.SEGMENTS) for (size in RoadSize.values()) for (c in seg.pattern(size)) assertTrue("$c", NodeKind.fromCode(c) != null)
    }

    @Test fun payStopCountsStayInTargetBands() {
        val bands = mapOf(RoadSize.QUICK to (5..8), RoadSize.STANDARD to (8..12), RoadSize.LONG to (13..19))
        for ((size, band) in bands) {
            val g = LifeRoadBoard.build(size)
            for (r in routes(g)) {
                val pays = r.count { g.node(it)!!.kind == NodeKind.PAY }
                assertTrue("$size $pays", pays in band)
            }
        }
    }

    @Test fun retireNodesAreTerminal() {
        val g = LifeRoadBoard.build(RoadSize.QUICK)
        assertTrue(g.node("RC")!!.next.isEmpty() && g.node("RG")!!.next.isEmpty())
    }

    @Test fun graphProblemsIsEmptyForAllSizes() {
        for (size in RoadSize.values()) assertEquals(emptyList<String>(), LifeRoadBoard.build(size).problems())
    }

    @Test fun problemsReportsABrokenGraph() {
        val bad = RoadGraph(RoadSize.QUICK, listOf(PathNode("S0", NodeKind.START, "S0", 0, false, listOf("S0"))))
        assertTrue(bad.problems().isNotEmpty())
    }
}
