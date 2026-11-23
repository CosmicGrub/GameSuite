package com.gamesuite.games.gemcascade

/**
 * Gem Cascade: the pure data model (no Compose, no Android): positions, pieces, the board and the injected
 * SplitMix64 random source. Everything in this package that is not under `ui/` runs in plain JVM unit tests.
 */

/**
 * SplitMix64. The ONLY source of randomness the engine uses, so a seed plus a move list reproduces a game exactly.
 * [state] is the whole generator, so it is saved in a snapshot and a resumed game continues the same sequence.
 */
class GemRng(seed: Long) {
    var state: Long = seed
        private set

    fun nextLong(): Long {
        state += GOLDEN
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** A value in `0 until bound` (bound must be positive). */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return ((nextLong() ushr 1) % bound.toLong()).toInt()
    }

    fun copy(): GemRng = GemRng(state)

    private companion object {
        val GOLDEN: Long = 0x9E3779B97F4A7C15UL.toLong()
        val MIX1: Long = 0xBF58476D1CE4E5B9UL.toLong()
        val MIX2: Long = 0x94D049BB133111EBUL.toLong()
    }
}

/** A board cell address: [r] counts from the TOP row (0), [c] from the left column (0). */
data class Pos(val r: Int, val c: Int) : Comparable<Pos> {
    override fun compareTo(other: Pos): Int = if (r != other.r) r.compareTo(other.r) else c.compareTo(other.c)
    override fun toString(): String = "($r,$c)"
}

/** The seven gem kinds are told apart by shape AND colour (colour-blind safe); renderers map kind ids to art. */
enum class GemShape { CIRCLE, SQUARE, TRIANGLE, DIAMOND, STAR, HEXAGON, CROSS }

/** Default identity of each gem kind id (0..6): the shape and an Okabe-Ito based colour. Styles may restyle both. */
object GemKinds {
    const val COUNT = 7
    val shapes: List<GemShape> = GemShape.values().toList()

    /** Default colours (ARGB): sky, amber, green, vermilion, pink, yellow, violet. Each shape is unique too. */
    val argb: List<Long> = listOf(0xFF56B4E9, 0xFFE69F00, 0xFF00B386, 0xFFF0663A, 0xFFD98BB8, 0xFFF0E442, 0xFF8B7CFF)
}

/** What occupies a cell. Only [GEM] takes part in matches; the others are obstacles or passengers. */
enum class PieceType {
    EMPTY,
    GEM,

    /** Rises one row per move and escapes through the top (a goal in Quest and Butterflies). Immune to clears. */
    BUTTERFLY,

    /** A blocker with [Cell.hp] 1. Clears by an adjacent match. In Butterflies it rises and the game ends if it reaches the top. */
    SPIDER,

    /** Falls like a gem and is collected when it rests on the bottom row. Immune to clears. */
    TREASURE,

    /** Coal, stone or dirt: fixed in place, [Cell.hp] 1..3, clears by adjacent matches or blasts. */
    STONE
}

/**
 * The special gems. Names are enum ids; display names live in strings (`gem_cascade_special_*`).
 *  - FLARE_ROW / FLARE_COL: a 4-run makes one. A horizontal 4-run makes a column flare, a vertical one a row flare.
 *  - PRISM: a 5-run. Colourless: it never matches, swap it with any neighbour to clear that neighbour's colour.
 *  - BURST: L, T and cross shapes. Clears the 3 x 3 block around it.
 *  - NOVA: two 4+ runs in one match group. Clears its row, its column and the 3 x 3 block.
 */
enum class SpecialKind(val code: Char) {
    NONE('-'), FLARE_ROW('r'), FLARE_COL('c'), PRISM('p'), BURST('b'), NOVA('n');

    companion object {
        fun fromCode(c: Char): SpecialKind? = values().firstOrNull { it.code == c }
    }
}

/** The special classes the combo table is written over (the two flare axes are one class). */
enum class SpecialClass { FLARE, PRISM, BURST, NOVA }

val SpecialKind.specialClass: SpecialClass?
    get() = when (this) {
        SpecialKind.NONE -> null
        SpecialKind.FLARE_ROW, SpecialKind.FLARE_COL -> SpecialClass.FLARE
        SpecialKind.PRISM -> SpecialClass.PRISM
        SpecialKind.BURST -> SpecialClass.BURST
        SpecialKind.NOVA -> SpecialClass.NOVA
    }

/**
 * One cell's piece. [kind] is the gem colour id (0..6) and is meaningful for gems only. [locked] (a cage) and
 * [timer] (a time bomb, 0 = none) belong to a gem and travel with it; [hp] belongs to stones and spiders.
 * The ice layer under a cell is NOT part of the piece: it stays put when the gem on it falls (see [GemBoard.ice]).
 */
data class Cell(
    val type: PieceType,
    val kind: Int = 0,
    val special: SpecialKind = SpecialKind.NONE,
    val locked: Boolean = false,
    val timer: Int = 0,
    val hp: Int = 0
) {
    val isGem: Boolean get() = type == PieceType.GEM

    /** The player may swap or rotate this piece. */
    val isMovable: Boolean get() = type == PieceType.GEM && !locked

    /** The colour used for matching, or -1 for anything that never matches (non-gems and the colourless prism). */
    val matchKind: Int get() = if (type == PieceType.GEM && special != SpecialKind.PRISM) kind else -1

    companion object {
        val EMPTY = Cell(PieceType.EMPTY)
        fun gem(kind: Int) = Cell(PieceType.GEM, kind)
    }
}

/** The grid: [rows] x [cols], row 0 at the top. [ice] is the floor layer (0..3 layers) under every cell. */
class GemBoard(val rows: Int, val cols: Int) {
    val cells: Array<Cell> = Array(rows * cols) { Cell.EMPTY }
    val ice: IntArray = IntArray(rows * cols)

    fun idx(r: Int, c: Int): Int = r * cols + c
    fun idx(p: Pos): Int = p.r * cols + p.c
    fun inside(r: Int, c: Int): Boolean = r in 0 until rows && c in 0 until cols
    fun inside(p: Pos): Boolean = inside(p.r, p.c)

    operator fun get(r: Int, c: Int): Cell = cells[r * cols + c]
    operator fun get(p: Pos): Cell = cells[p.r * cols + p.c]
    operator fun set(p: Pos, cell: Cell) {
        cells[p.r * cols + p.c] = cell
    }

    operator fun set(r: Int, c: Int, cell: Cell) {
        cells[r * cols + c] = cell
    }

    fun iceAt(p: Pos): Int = ice[idx(p)]

    fun copy(): GemBoard {
        val b = GemBoard(rows, cols)
        System.arraycopy(cells, 0, b.cells, 0, cells.size)
        System.arraycopy(ice, 0, b.ice, 0, ice.size)
        return b
    }

    fun allPositions(): List<Pos> {
        val out = ArrayList<Pos>(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) out.add(Pos(r, c))
        return out
    }

    fun positionsOf(type: PieceType): List<Pos> = allPositions().filter { this[it].type == type }

    fun sameAs(other: GemBoard): Boolean =
        rows == other.rows && cols == other.cols && cells.contentEquals(other.cells) && ice.contentEquals(other.ice)

    fun swapCells(a: Pos, b: Pos) {
        val t = this[a]
        this[a] = this[b]
        this[b] = t
    }

    /** A compact text form used by tests and the snapshot: one token per cell, `;` separated rows. */
    fun encode(): String = buildString {
        for (r in 0 until rows) {
            if (r > 0) append(';')
            for (c in 0 until cols) {
                if (c > 0) append(' ')
                append(encodeCell(this@GemBoard[r, c], ice[idx(r, c)]))
            }
        }
    }

    companion object {
        /**
         * Token = type letter (e empty, g gem, b butterfly, s spider, t treasure, x stone) + kind digit +
         * special code + lock flag ('L' or '.') + timer (2 digits) + hp digit + ice digit. Always 8 characters.
         */
        fun encodeCell(cell: Cell, ice: Int): String {
            val t = when (cell.type) {
                PieceType.EMPTY -> 'e'
                PieceType.GEM -> 'g'
                PieceType.BUTTERFLY -> 'b'
                PieceType.SPIDER -> 's'
                PieceType.TREASURE -> 't'
                PieceType.STONE -> 'x'
            }
            val timer = cell.timer.coerceIn(0, 99)
            return "" + t + cell.kind.coerceIn(0, 9) + cell.special.code + (if (cell.locked) 'L' else '.') +
                (timer / 10) + (timer % 10) + cell.hp.coerceIn(0, 9) + ice.coerceIn(0, 9)
        }

        /** Inverse of [encodeCell]; null when the token is malformed. Range checks are the snapshot validator's job. */
        fun decodeCell(token: String): Pair<Cell, Int>? {
            if (token.length != 8) return null
            val type = when (token[0]) {
                'e' -> PieceType.EMPTY
                'g' -> PieceType.GEM
                'b' -> PieceType.BUTTERFLY
                's' -> PieceType.SPIDER
                't' -> PieceType.TREASURE
                'x' -> PieceType.STONE
                else -> return null
            }
            val kind = token[1].digitToIntOrNull() ?: return null
            val special = SpecialKind.fromCode(token[2]) ?: return null
            val locked = when (token[3]) {
                'L' -> true
                '.' -> false
                else -> return null
            }
            val t1 = token[4].digitToIntOrNull() ?: return null
            val t2 = token[5].digitToIntOrNull() ?: return null
            val hp = token[6].digitToIntOrNull() ?: return null
            val ice = token[7].digitToIntOrNull() ?: return null
            return Cell(type, kind, special, locked, t1 * 10 + t2, hp) to ice
        }

        /** Parses [encode] output for a board of the given size; null when anything is malformed. */
        fun decode(text: String, rows: Int, cols: Int): GemBoard? {
            val lines = text.split(';')
            if (lines.size != rows) return null
            val board = GemBoard(rows, cols)
            for (r in 0 until rows) {
                val tokens = lines[r].split(' ')
                if (tokens.size != cols) return null
                for (c in 0 until cols) {
                    val (cell, ice) = decodeCell(tokens[c]) ?: return null
                    board[r, c] = cell
                    board.ice[board.idx(r, c)] = ice
                }
            }
            return board
        }
    }
}
