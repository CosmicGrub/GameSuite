package com.gamesuite.games.mahjong

/** The layouts a Hub launch can pick with `extras["layout"]`. */
enum class MahjongLayoutId(val key: String) {
    TURTLE("turtle"),
    PYRAMID("pyramid"),
    CROSS("cross");

    companion object {
        /** Unknown / missing keys fall back to the classic turtle. */
        fun fromKey(key: String?): MahjongLayoutId = values().firstOrNull { it.key == key } ?: TURTLE
    }
}

/** Coordinate lists (x, y, z on the half-tile grid) of every layout. */
object MahjongLayouts {
    private val turtle: List<MahjongPos> by lazy { buildTurtle() }
    private val pyramid: List<MahjongPos> by lazy { buildPyramid() }
    private val cross: List<MahjongPos> by lazy { buildCross() }
    private val geometries = HashMap<MahjongLayoutId, MahjongGeometry>()

    fun positions(id: MahjongLayoutId): List<MahjongPos> = when (id) {
        MahjongLayoutId.TURTLE -> turtle
        MahjongLayoutId.PYRAMID -> pyramid
        MahjongLayoutId.CROSS -> cross
    }

    @Synchronized
    fun geometry(id: MahjongLayoutId): MahjongGeometry =
        geometries.getOrPut(id) { MahjongGeometry(positions(id)) }

    private fun MutableList<MahjongPos>.row(y: Int, xFrom: Int, xTo: Int, z: Int) {
        var x = xFrom
        while (x <= xTo) {
            add(MahjongPos(x, y, z))
            x += 2
        }
    }

    /** The classic turtle: 87 + 36 + 16 + 4 + 1 = 144 tiles, with the three side caps. */
    private fun buildTurtle(): List<MahjongPos> {
        val out = ArrayList<MahjongPos>(144)
        out.row(0, 2, 24, 0)
        out.row(2, 6, 20, 0)
        out.row(4, 4, 22, 0)
        out.row(6, 2, 24, 0)
        out.row(8, 2, 24, 0)
        out.row(10, 4, 22, 0)
        out.row(12, 6, 20, 0)
        out.row(14, 2, 24, 0)
        out.add(MahjongPos(0, 7, 0))
        out.add(MahjongPos(26, 7, 0))
        out.add(MahjongPos(28, 7, 0))
        for (y in 2..12 step 2) out.row(y, 8, 18, 1)
        for (y in 4..10 step 2) out.row(y, 10, 16, 2)
        for (y in 6..8 step 2) out.row(y, 12, 14, 3)
        out.add(MahjongPos(13, 7, 4))
        return out
    }

    /** A stepped pyramid: 9 x 6 + 7 x 4 + 5 x 2 = 92 tiles. */
    private fun buildPyramid(): List<MahjongPos> {
        val out = ArrayList<MahjongPos>(92)
        for (y in 0..10 step 2) out.row(y, 2, 18, 0)
        for (y in 2..8 step 2) out.row(y, 4, 16, 1)
        for (y in 4..6 step 2) out.row(y, 6, 14, 2)
        return out
    }

    /** A plus-shaped cross: 48 + 8 + 4 = 60 tiles. */
    private fun buildCross(): List<MahjongPos> {
        val out = ArrayList<MahjongPos>(60)
        for (row in 0..7) {
            if (row in 2..5) out.row(row * 2, 0, 14, 0) else out.row(row * 2, 4, 10, 0)
        }
        for (row in 3..4) out.row(row * 2, 4, 10, 1)
        for (row in 3..4) out.row(row * 2, 6, 8, 2)
        return out
    }
}
