package com.gamesuite.games.liferoad

/** One road segment: its id, whether it is the bold Cliff Path, and its square pattern per [RoadSize] (section 2.16 legend). */
data class SegmentSpec(val id: String, val bold: Boolean, val quick: String, val standard: String, val long: String) {
    fun pattern(size: RoadSize): String = when (size) {
        RoadSize.QUICK -> quick
        RoadSize.STANDARD -> standard
        RoadSize.LONG -> long
    }
}

/**
 * The board as data (generated from section 2.16). A node id is `<segment>.<index>` for segment squares and one of
 * S0 / F2 / F3 / F4 / RC / RG for the single squares. [EDGES] use `SEG:0` (first square) and `SEG:end` (last square).
 */
object LifeRoadBoardSpec {
    const val START_ID: String = "S0"
    const val COZY_RETIRE_ID: String = "RC"
    const val GRAND_RETIRE_ID: String = "RG"

    val FORK_IDS: List<String> = listOf("S0", "F2", "F3", "F4")

    val SEGMENTS: List<SegmentSpec> = listOf(
        SegmentSpec("LRN", false, "C.ELG", "C.E.CL.G", "C.E.CL.E.G"),
        SegmentSpec("WRK", false, "A.\$R", "A.\$ER.", "A.\$E.R\$."),
        SegmentSpec("TA", false, "\$.EJ.I", "\$.E.JI.M\$.EB", "\$.E.JI.M\$.E.B.L\$.EM."),
        SegmentSpec("RISK", true, "L\$EL", "L\$E.L\$E", "L\$E.L\$E.L\$"),
        SegmentSpec("SAFE", false, ".E\$.M", ".E.\$.F.M.", ".E.\$.F.M.E.\$.F"),
        SegmentSpec("TB", false, "\$.HE.K", "\$.H.E.F\$.K.M", "\$.H.E.F\$.K.M\$.E.I.EL"),
        SegmentSpec("FAM", false, "F.F\$E", "F.F\$E.FM", "F.F\$E.FMF.\$E"),
        SegmentSpec("FREE", false, "L.M\$K", "L.M\$K.LM", "L.M\$K.LM.\$EM"),
        SegmentSpec("TC", false, "\$.E.IB", "\$.E.I.J\$.B.E", "\$.E.I.J\$.B.E\$.M.E.LK"),
        SegmentSpec("COZY", false, "M.\$", "E.M.\$", "E.M.E.M\$"),
        SegmentSpec("GRAND", false, "KL\$", "K.L\$M", "K.L\$.EM\$"),
    )

    val EDGES: List<Pair<String, String>> = listOf(
        "S0" to "LRN:0",
        "S0" to "WRK:0",
        "LRN:end" to "TA:0",
        "WRK:end" to "TA:0",
        "TA:end" to "F2",
        "F2" to "RISK:0",
        "F2" to "SAFE:0",
        "RISK:end" to "TB:0",
        "SAFE:end" to "TB:0",
        "TB:end" to "F3",
        "F3" to "FAM:0",
        "F3" to "FREE:0",
        "FAM:end" to "TC:0",
        "FREE:end" to "TC:0",
        "TC:end" to "F4",
        "F4" to "COZY:0",
        "F4" to "GRAND:0",
        "COZY:end" to "RC",
        "GRAND:end" to "RG",
    )
}

object LifeRoadBoard {
    private val cache: Map<RoadSize, RoadGraph> by lazy { RoadSize.values().associateWith { build(it) } }

    /** The shared immutable board of [size] (built once). */
    fun of(size: RoadSize): RoadGraph = cache.getValue(size)

    /** The board of [size] built from [LifeRoadBoardSpec] (algorithm in section 2.16). */
    fun build(size: RoadSize): RoadGraph {
        val nodes = LinkedHashMap<String, PathNode>()
        val nexts = HashMap<String, MutableList<String>>()
        val last = HashMap<String, String>()
        fun add(id: String, kind: NodeKind, seg: String, idx: Int, bold: Boolean) {
            nodes[id] = PathNode(id, kind, seg, idx, bold, emptyList())
            nexts[id] = ArrayList()
        }
        add(LifeRoadBoardSpec.START_ID, NodeKind.START, "S0", 0, false)
        for (f in listOf("F2", "F3", "F4")) add(f, NodeKind.FORK, f, 0, false)
        add(LifeRoadBoardSpec.COZY_RETIRE_ID, NodeKind.RETIRE, "RC", 0, false)
        add(LifeRoadBoardSpec.GRAND_RETIRE_ID, NodeKind.RETIRE, "RG", 0, false)
        for (seg in LifeRoadBoardSpec.SEGMENTS) {
            val pat = seg.pattern(size)
            for (i in pat.indices) {
                val kind = NodeKind.fromCode(pat[i]) ?: error("bad kind ${pat[i]}")
                add("${seg.id}.$i", kind, seg.id, i, seg.bold)
                if (i > 0) nexts.getValue("${seg.id}.${i - 1}").add("${seg.id}.$i")
            }
            last[seg.id] = "${seg.id}.${pat.length - 1}"
        }
        fun resolve(ref: String): String {
            val k = ref.indexOf(':')
            if (k < 0) return ref
            val seg = ref.substring(0, k)
            return if (ref.substring(k + 1) == "end") last.getValue(seg) else "$seg.${ref.substring(k + 1)}"
        }
        for ((a, b) in LifeRoadBoardSpec.EDGES) nexts.getValue(resolve(a)).add(resolve(b))
        return RoadGraph(size, nodes.values.map { it.copy(next = nexts.getValue(it.id)) })
    }
}
