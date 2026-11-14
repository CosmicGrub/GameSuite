package com.gamesuite.games.shogi

/**
 * Pure data behind the original piece art: how each piece moves (drawn as arrows in the "Symbols" style, so
 * nobody needs to read kanji) and its traditional character. Vectors are (rowDelta, colDelta) in the owner's own
 * frame, where row -1 is "forward". A test keeps these in step with the real rules.
 */
object ShogiPieceArt {
    class Moves(val steps: List<Pair<Int, Int>>, val slides: List<Pair<Int, Int>>, val knight: Boolean)

    private val diag = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
    private val orth = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
    private val goldSteps = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to 0)

    fun moves(type: Int): Moves = when (type) {
        ShogiPiece.PAWN -> Moves(listOf(-1 to 0), emptyList(), false)
        ShogiPiece.LANCE -> Moves(emptyList(), listOf(-1 to 0), false)
        ShogiPiece.KNIGHT -> Moves(emptyList(), emptyList(), true)
        ShogiPiece.SILVER -> Moves(listOf(-1 to -1, -1 to 0, -1 to 1, 1 to -1, 1 to 1), emptyList(), false)
        ShogiPiece.GOLD, 9, 10, 11, 12 -> Moves(goldSteps, emptyList(), false)
        ShogiPiece.BISHOP -> Moves(emptyList(), diag, false)
        ShogiPiece.ROOK -> Moves(emptyList(), orth, false)
        ShogiPiece.HORSE -> Moves(orth, diag, false)
        ShogiPiece.DRAGON -> Moves(diag, orth, false)
        else -> Moves(diag + orth, emptyList(), false)
    }

    /** Western letter used under the arrows in the Symbols style (promoted pieces get a leading plus at draw time). */
    fun letter(type: Int): String = when (ShogiPiece.base(type)) {
        ShogiPiece.PAWN -> "P"
        ShogiPiece.LANCE -> "L"
        ShogiPiece.KNIGHT -> "N"
        ShogiPiece.SILVER -> "S"
        ShogiPiece.GOLD -> "G"
        ShogiPiece.BISHOP -> "B"
        ShogiPiece.ROOK -> "R"
        else -> "K"
    }

    /** The traditional character. Sente's king is written with 玉 and Gote's with 王 (or the reverse; only convention). */
    fun kanji(type: Int, side: Int): String = when (type) {
        ShogiPiece.PAWN -> "歩"
        ShogiPiece.LANCE -> "香"
        ShogiPiece.KNIGHT -> "桂"
        ShogiPiece.SILVER -> "銀"
        ShogiPiece.GOLD -> "金"
        ShogiPiece.BISHOP -> "角"
        ShogiPiece.ROOK -> "飛"
        ShogiPiece.KING -> if (side == 0) "玉" else "王"
        9 -> "と"
        10 -> "杏"
        11 -> "圭"
        12 -> "全"
        ShogiPiece.HORSE -> "馬"
        ShogiPiece.DRAGON -> "龍"
        else -> "?"
    }
}
