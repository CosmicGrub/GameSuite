package com.gamesuite.games.wordgames.tiles

import com.gamesuite.games.wordgames.WordDictionary

/**
 * Bounded heuristic bot — NOT a full Scrabble move-generator. Opening move:
 * brute-forces rack permutations (feasible at rack size 7) for a valid word
 * through center. Later moves: tries appending/prepending rack tiles
 * directly onto an existing word at a random anchor. Returns null (bot
 * passes) if nothing is found within its search bounds — a real optimal
 * bot would exhaustively search all anchors/directions/cross-checks, which
 * is out of scope here.
 */
object TileBot {

    fun findMove(s: TileGameState, player: TilePlayerState): List<PendingPlacementSpec>? {
        val boardEmpty = s.board.all { row -> row.all { it.tile == null } }
        return if (boardEmpty) openingMove(player) else attachMove(s, player)
    }

    data class PendingPlacementSpec(val row: Int, val col: Int, val tile: RackTile, val letter: Char)

    private fun openingMove(player: TilePlayerState): List<PendingPlacementSpec>? {
        val rack = player.rack
        val bestWord = bestWordFromRack(rack, maxLen = rack.size) ?: return null
        val startCol = CENTER - bestWord.length / 2
        return assignRackToWord(bestWord, rack).mapIndexed { i, tile ->
            PendingPlacementSpec(CENTER, startCol + i, tile, bestWord[i])
        }
    }

    private fun attachMove(s: TileGameState, player: TilePlayerState): List<PendingPlacementSpec>? {
        val anchors = mutableListOf<Pair<Int, Int>>()
        for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) {
            if (s.board[r][c].tile != null) anchors.add(r to c)
        }
        if (anchors.isEmpty()) return null

        for (anchor in anchors.shuffled().take(20)) {
            val move = tryAttachAt(s, player, anchor.first, anchor.second)
            if (move != null) return move
        }
        return null
    }

    private fun tryAttachAt(s: TileGameState, player: TilePlayerState, row: Int, col: Int): List<PendingPlacementSpec>? {
        val letter = s.board[row][col].effectiveLetter ?: return null
        val rack = player.rack

        // Try extending rightward from this anchor letter using 1..rack.size tiles.
        for (len in minOf(rack.size, 6) downTo 1) {
            for (perm in permutations(rack, len)) {
                val candidateCells = (1..len).map { i -> row to (col + i) }
                if (candidateCells.any { (r, c) -> c >= BOARD_SIZE || s.board[r][c].tile != null }) continue
                for (letters in blankLetterCombinations(perm)) {
                    val word = letter.toString() + letters.joinToString("")
                    if (WordDictionary.isValidWord(word)) {
                        return perm.mapIndexed { i, tile ->
                            PendingPlacementSpec(row, col + i + 1, tile, letters[i])
                        }
                    }
                }
            }
        }

        // Try extending downward similarly.
        for (len in minOf(rack.size, 6) downTo 1) {
            for (perm in permutations(rack, len)) {
                val candidateCells = (1..len).map { i -> (row + i) to col }
                if (candidateCells.any { (r, c) -> r >= BOARD_SIZE || s.board[r][c].tile != null }) continue
                for (letters in blankLetterCombinations(perm)) {
                    val word = letter.toString() + letters.joinToString("")
                    if (WordDictionary.isValidWord(word)) {
                        return perm.mapIndexed { i, tile ->
                            PendingPlacementSpec(row + i + 1, col, tile, letters[i])
                        }
                    }
                }
            }
        }

        return null
    }

    /** Finds the longest word (up to maxLen) formable from some subset of the rack. */
    private fun bestWordFromRack(rack: List<RackTile>, maxLen: Int): String? {
        for (len in minOf(maxLen, rack.size) downTo 2) {
            for (perm in permutations(rack, len)) {
                for (letters in blankLetterCombinations(perm)) {
                    val word = letters.joinToString("")
                    if (WordDictionary.isValidWord(word)) return word
                }
            }
        }
        return null
    }

    /**
     * All ways to assign concrete letters to a rack-tile permutation: non-blank tiles
     * keep their fixed letter, and each blank tile is tried against every letter A-Z
     * (rather than being hard-coded to 'A'), so the bot can use a blank to stand in for
     * whatever letter a candidate word actually needs. Bounded fan-out: the bag only
     * has 2 blanks, so at most 26*26 combinations per permutation.
     */
    private fun blankLetterCombinations(perm: List<RackTile>): Sequence<List<Char>> = sequence {
        val blankIndices = perm.indices.filter { perm[it].isBlank }
        val base = perm.map { it.letter }
        if (blankIndices.isEmpty()) {
            yield(base)
            return@sequence
        }
        fun expand(remaining: List<Int>, current: List<Char>): Sequence<List<Char>> = sequence {
            if (remaining.isEmpty()) {
                yield(current)
                return@sequence
            }
            val idx = remaining.first()
            for (c in 'A'..'Z') {
                yieldAll(expand(remaining.drop(1), current.toMutableList().also { it[idx] = c }))
            }
        }
        yieldAll(expand(blankIndices, base))
    }

    private fun assignRackToWord(word: String, rack: List<RackTile>): List<RackTile> {
        val available = rack.toMutableList()
        return word.map { ch ->
            val match = available.firstOrNull { !it.isBlank && it.letter == ch }
                ?: available.first { it.isBlank }
            available.remove(match)
            match
        }
    }

    /** Bounded permutation generator — rack sizes are small (<=7) so this stays cheap. */
    private fun permutations(rack: List<RackTile>, length: Int): Sequence<List<RackTile>> = sequence {
        if (length > rack.size) return@sequence
        fun helper(chosen: List<RackTile>, remaining: List<RackTile>): Sequence<List<RackTile>> = sequence {
            if (chosen.size == length) {
                yield(chosen)
                return@sequence
            }
            for (i in remaining.indices) {
                yieldAll(helper(chosen + remaining[i], remaining.filterIndexed { idx, _ -> idx != i }))
            }
        }
        yieldAll(helper(emptyList(), rack))
    }
}
