package com.gamesuite.games.dotsandboxes

/**
 * Pure consistency check for a Dots and Boxes position that arrives from outside (a save or a
 * host's snapshot). A box is owned exactly when all four of its edges are drawn, every player's
 * score equals the boxes they own, the board is over exactly when every box is owned, and the
 * winner is the strict leader (null for a tie). Anything else cannot come from real play.
 * List-size checks are repeated here so the function is safe on its own.
 */
internal object DotsAndBoxesPositionCheck {
    fun isValid(
        boxRows: Int,
        boxCols: Int,
        horizontal: List<Boolean>,
        vertical: List<Boolean>,
        boxOwner: List<Int?>,
        playerIds: List<String>,
        scores: List<Int>,
        boardOver: Boolean,
        winnerPlayerId: String?
    ): Boolean {
        val n = playerIds.size
        if (n < 1 || scores.size != n) return false
        if (horizontal.size != (boxRows + 1) * boxCols || vertical.size != boxRows * (boxCols + 1)) return false
        if (boxOwner.size != boxRows * boxCols) return false
        if (boxOwner.any { it != null && it !in 0 until n }) return false

        val owned = IntArray(n)
        for (r in 0 until boxRows) for (c in 0 until boxCols) {
            val drawn = listOf(
                horizontal[r * boxCols + c],
                horizontal[(r + 1) * boxCols + c],
                vertical[r * (boxCols + 1) + c],
                vertical[r * (boxCols + 1) + c + 1]
            ).count { it }
            val owner = boxOwner[r * boxCols + c]
            if ((drawn == 4) != (owner != null)) return false
            if (owner != null) owned[owner]++
        }
        for (i in 0 until n) if (scores[i] != owned[i]) return false

        val allOwned = boxOwner.all { it != null }
        if (boardOver != allOwned) return false
        if (!boardOver) return winnerPlayerId == null
        val best = scores.max()
        val leaders = (0 until n).filter { scores[it] == best }
        return if (leaders.size == 1) winnerPlayerId == playerIds[leaders[0]] else winnerPlayerId == null
    }
}
