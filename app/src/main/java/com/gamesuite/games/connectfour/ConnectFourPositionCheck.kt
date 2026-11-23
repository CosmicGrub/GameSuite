package com.gamesuite.games.connectfour

/**
 * Pure legality check for a Connect Four position that arrives from outside (a saved game or a
 * host's state sync). Rejects what real play can never produce: floating discs, disc counts that
 * disagree with whose turn it is, a "winner" with no line (or a line nobody is credited for),
 * both players holding a line, a full board that is not over, and inconsistent winner fields.
 * The shape checks (cell count, owner range) are the callers' own and run first.
 */
internal object ConnectFourPositionCheck {
    fun isValid(
        cells: List<Int?>,
        rows: Int,
        cols: Int,
        winLength: Int,
        playerIds: List<String>,
        currentPlayerIndex: Int,
        boardOver: Boolean,
        winnerPlayerId: String?,
        winningLine: List<Int>?
    ): Boolean {
        val n = playerIds.size
        if (n != 2 || cells.size != rows * cols) return false
        if (cells.any { it != null && it !in 0 until n }) return false
        if (currentPlayerIndex !in 0 until n) return false

        // Gravity: scanning a column bottom-up, no disc may sit above an empty cell.
        for (c in 0 until cols) {
            var gap = false
            for (r in rows - 1 downTo 0) {
                val filled = cells[r * cols + c] != null
                if (!filled) gap = true else if (gap) return false
            }
        }

        val count = IntArray(n)
        cells.forEach { if (it != null) count[it]++ }
        if (kotlin.math.abs(count[0] - count[1]) > 1) return false

        val lines = BooleanArray(n) { hasLine(cells, rows, cols, winLength, it) }
        if (lines[0] && lines[1]) return false
        val full = cells.none { it == null }

        if (!boardOver) {
            if (lines[0] || lines[1] || full) return false
            if (winnerPlayerId != null || winningLine != null) return false
            // The player with fewer discs moves next; with equal counts either may (the opener alternates).
            if (count[0] == count[1] + 1 && currentPlayerIndex != 1) return false
            if (count[1] == count[0] + 1 && currentPlayerIndex != 0) return false
            return true
        }

        val winner = lines.indexOfFirst { it }
        if (winner < 0) {
            // A draw: the board is full and nobody is credited.
            return full && winnerPlayerId == null && winningLine == null
        }
        if (winnerPlayerId != playerIds[winner] || currentPlayerIndex != winner) return false
        // The winner just moved: they hold the same number of discs as the other, or one more.
        if (count[winner] != count[1 - winner] && count[winner] != count[1 - winner] + 1) return false
        val line = winningLine ?: return false
        if (line.size < winLength || line.distinct().size != line.size) return false
        return line.all { it in cells.indices && cells[it] == winner }
    }

    private fun hasLine(cells: List<Int?>, rows: Int, cols: Int, winLength: Int, player: Int): Boolean {
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for (r in 0 until rows) for (c in 0 until cols) for ((dr, dc) in dirs) {
            var k = 0
            while (k < winLength) {
                val rr = r + dr * k
                val cc = c + dc * k
                if (rr !in 0 until rows || cc !in 0 until cols || cells[rr * cols + cc] != player) break
                k++
            }
            if (k == winLength) return true
        }
        return false
    }
}
