package com.gamesuite.games.wordgames.tiles

enum class SquareType { NORMAL, DOUBLE_LETTER, TRIPLE_LETTER, DOUBLE_WORD, TRIPLE_WORD, CENTER }

const val BOARD_SIZE = 15
const val CENTER = BOARD_SIZE / 2

/** Standard 15x15 word-tile board premium-square layout (symmetric in all four quadrants). */
object TileBoardLayout {
    private val tripleWord = listOf(0 to 0, 0 to 7, 0 to 14, 7 to 0, 7 to 14, 14 to 0, 14 to 7, 14 to 14)
    private val doubleWord = listOf(
        1 to 1, 2 to 2, 3 to 3, 4 to 4, 10 to 10, 11 to 11, 12 to 12, 13 to 13,
        1 to 13, 2 to 12, 3 to 11, 4 to 10, 10 to 4, 11 to 3, 12 to 2, 13 to 1
    )
    private val tripleLetter = listOf(
        1 to 5, 1 to 9, 5 to 1, 5 to 5, 5 to 9, 5 to 13,
        9 to 1, 9 to 5, 9 to 9, 9 to 13, 13 to 5, 13 to 9
    )
    private val doubleLetter = listOf(
        0 to 3, 0 to 11, 2 to 6, 2 to 8, 3 to 0, 3 to 7, 3 to 14,
        6 to 2, 6 to 6, 6 to 8, 6 to 12, 7 to 3, 7 to 11,
        8 to 2, 8 to 6, 8 to 8, 8 to 12, 11 to 0, 11 to 7, 11 to 14,
        12 to 6, 12 to 8, 14 to 3, 14 to 11
    )

    fun typeAt(row: Int, col: Int): SquareType = when {
        row == CENTER && col == CENTER -> SquareType.CENTER
        (row to col) in tripleWord -> SquareType.TRIPLE_WORD
        (row to col) in doubleWord -> SquareType.DOUBLE_WORD
        (row to col) in tripleLetter -> SquareType.TRIPLE_LETTER
        (row to col) in doubleLetter -> SquareType.DOUBLE_LETTER
        else -> SquareType.NORMAL
    }
}

data class RackTile(val letter: Char, val isBlank: Boolean, val instanceId: Int)

/** Standard English letter distribution and point values (100-tile bag, 2 blanks). */
object TileBag {
    private val distribution = mapOf(
        'E' to 12, 'A' to 9, 'I' to 9, 'O' to 8, 'N' to 6, 'R' to 6, 'T' to 6,
        'L' to 4, 'S' to 4, 'U' to 4, 'D' to 4, 'G' to 3,
        'B' to 2, 'C' to 2, 'M' to 2, 'P' to 2, 'F' to 2, 'H' to 2, 'V' to 2, 'W' to 2, 'Y' to 2,
        'K' to 1, 'J' to 1, 'X' to 1, 'Q' to 1, 'Z' to 1
    )
    val letterValues = mapOf(
        'A' to 1, 'B' to 3, 'C' to 3, 'D' to 2, 'E' to 1, 'F' to 4, 'G' to 2, 'H' to 4, 'I' to 1,
        'J' to 8, 'K' to 5, 'L' to 1, 'M' to 3, 'N' to 1, 'O' to 1, 'P' to 3, 'Q' to 10, 'R' to 1,
        'S' to 1, 'T' to 1, 'U' to 1, 'V' to 4, 'W' to 4, 'X' to 8, 'Y' to 4, 'Z' to 10
    )
    const val blankCount = 2

    fun freshBag(): MutableList<RackTile> {
        var id = 0
        val bag = mutableListOf<RackTile>()
        distribution.forEach { (letter, count) -> repeat(count) { bag.add(RackTile(letter, false, id++)) } }
        repeat(blankCount) { bag.add(RackTile(' ', true, id++)) }
        bag.shuffle()
        return bag
    }

    fun valueOf(tile: RackTile): Int = if (tile.isBlank) 0 else (letterValues[tile.letter] ?: 0)
}
