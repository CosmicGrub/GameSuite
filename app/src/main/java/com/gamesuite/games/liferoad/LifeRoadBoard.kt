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
    /**
     * The board of [size] built from [LifeRoadBoardSpec] (algorithm in section 2.16).
     * TODO(P1): the scaffold returns only a Start Gate joined to a retire square so callers compile and tests can be enabled one by one.
     */
    fun build(size: RoadSize): RoadGraph = RoadGraph(
        size,
        listOf(
            PathNode(LifeRoadBoardSpec.START_ID, NodeKind.START, "S0", 0, false, listOf(LifeRoadBoardSpec.COZY_RETIRE_ID)),
            PathNode(LifeRoadBoardSpec.COZY_RETIRE_ID, NodeKind.RETIRE, "RC", 0, false, emptyList())
        )
    )
}
