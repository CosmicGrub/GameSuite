package com.gamesuite.games.wordgames.tiles

import com.gamesuite.games.wordgames.WordDictionary
import com.gamesuite.settings.CpuDifficulty

/**
 * Bounded heuristic bot — NOT a full Scrabble move-generator. Opening move:
 * brute-forces rack permutations (feasible at rack size 7) for a valid word
 * through center. Later moves: tries appending/prepending rack tiles
 * directly onto an existing word at an anchor. Returns null (bot passes)
 * if nothing is found within its search bounds — a real optimal bot would
 * exhaustively search all anchors/directions/cross-checks, which is out of
 * scope here.
 *
 * Research pass (README item 9j) turned the single fixed search into a
 * 3-tier difficulty ladder — see [findMove]. The candidate-generation
 * machinery (permutations, blank expansion, dictionary checks) is shared
 * across all three; only *how much* of the board it looks at and *which*
 * of the words it finds it actually plays differ.
 */
object TileBot {

    /** See the HARD branch of [attachMove] for why this is bounded rather than "every anchor". */
    private const val HARD_ANCHOR_BUDGET = 40

    /** See [highestValueWordFromRack] for why this bounds total dictionary lookups rather
     *  than exhausting every blank-letter combination for every rack permutation. */
    private const val HARD_OPENING_WORD_BUDGET = 20_000

    data class PendingPlacementSpec(val row: Int, val col: Int, val tile: RackTile, val letter: Char)

    /**
     * EASY looks at only a handful of anchors and, at each, takes the *shortest*
     * valid word it can form — so it plays weak 2-3 letter words and passes far
     * more often than the original bot. MEDIUM is the original behavior exactly:
     * 20 random anchors, longest word first, first valid hit wins. HARD searches
     * twice as many anchors, collects every valid candidate rather than stopping
     * at the first, and plays the one with the highest total tile value (a
     * deliberate proxy for real score —
     * it ignores premium squares, which would need the game's private scoring
     * pulled into the bot, but tile value already separates a Q/Z/X play from a
     * pile of 1-point vowels, which is most of what a stronger opponent feels
     * like from across the table).
     */
    fun findMove(
        s: TileGameState,
        player: TilePlayerState,
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    ): List<PendingPlacementSpec>? {
        val boardEmpty = s.board.all { row -> row.all { it.tile == null } }
        return if (boardEmpty) openingMove(player, difficulty) else attachMove(s, player, difficulty)
    }

    private fun openingMove(player: TilePlayerState, difficulty: CpuDifficulty): List<PendingPlacementSpec>? {
        val rack = player.rack
        val bestWord = when (difficulty) {
            CpuDifficulty.EASY -> shortestWordFromRack(rack)
            CpuDifficulty.MEDIUM -> bestWordFromRack(rack, maxLen = rack.size)
            CpuDifficulty.HARD -> highestValueWordFromRack(rack)
        } ?: return null
        val startCol = CENTER - bestWord.length / 2
        return assignRackToWord(bestWord, rack).mapIndexed { i, tile ->
            PendingPlacementSpec(CENTER, startCol + i, tile, bestWord[i])
        }
    }

    private fun attachMove(s: TileGameState, player: TilePlayerState, difficulty: CpuDifficulty): List<PendingPlacementSpec>? {
        val anchors = mutableListOf<Pair<Int, Int>>()
        for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) {
            if (s.board[r][c].tile != null) anchors.add(r to c)
        }
        if (anchors.isEmpty()) return null

        return when (difficulty) {
            CpuDifficulty.EASY -> {
                for (anchor in anchors.shuffled().take(5)) {
                    val move = tryAttachAt(s, player, anchor.first, anchor.second, shortestFirst = true)
                    if (move != null) return move
                }
                null
            }
            CpuDifficulty.MEDIUM -> {
                for (anchor in anchors.shuffled().take(20)) {
                    val move = tryAttachAt(s, player, anchor.first, anchor.second, shortestFirst = false)
                    if (move != null) return move
                }
                null
            }
            CpuDifficulty.HARD -> {
                // Twice MEDIUM's anchor budget, and unlike MEDIUM it never stops at the
                // first hit — every candidate is collected, then the single highest-value
                // play wins. Deliberately NOT "every anchor on the board": this runs
                // synchronously on the UI thread from the bot-turn LaunchedEffect, and each
                // anchor is up to ~17k dictionary lookups (x26 per blank tile) with no
                // short-circuit, so an unbounded scan of a busy late-game board would be a
                // multi-second freeze or an ANR. 40 keeps the worst case at ~2x the original
                // bot's, which was already tuned to feel instant.
                anchors.shuffled().take(HARD_ANCHOR_BUDGET)
                    .mapNotNull { (r, c) -> tryAttachAt(s, player, r, c, shortestFirst = false) }
                    .maxByOrNull { move -> move.sumOf { TileBag.valueOf(it.tile) } }
            }
        }
    }

    private fun tryAttachAt(
        s: TileGameState,
        player: TilePlayerState,
        row: Int,
        col: Int,
        shortestFirst: Boolean
    ): List<PendingPlacementSpec>? {
        val letter = s.board[row][col].effectiveLetter ?: return null
        val rack = player.rack
        val lengths = if (shortestFirst) 1..minOf(rack.size, 6) else minOf(rack.size, 6) downTo 1

        // Try extending rightward from this anchor letter.
        for (len in lengths) {
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
        for (len in lengths) {
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

    /** EASY's opening: the *shortest* valid word (2 letters up), the weakest legal start. */
    private fun shortestWordFromRack(rack: List<RackTile>): String? {
        for (len in 2..rack.size) {
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
     * HARD's opening: every valid word the rack can form, then the one worth the
     * most in raw tile value. Same bounded permutation space as the others — the
     * only difference is it doesn't stop at the first hit.
     *
     * Bounded like the HARD branch of [attachMove] and for the same reason: this
     * runs synchronously on the UI thread from the bot-turn LaunchedEffect, once
     * per match on the very first bot turn. A rack holding both of the bag's
     * blanks fans out to up to 26*26=676 letter combinations per permutation, and
     * with ~13.7k rack permutations across lengths 2..7, that's up to ~9.3M
     * dictionary lookups for a single opening move — a multi-second freeze or an
     * ANR. HARD_OPENING_WORD_BUDGET caps the total number of lookups so the worst
     * case can no longer stall the UI thread, while a blank-free rack (the common
     * case, at most ~13.7k lookups) still gets examined in full.
     */
    private fun highestValueWordFromRack(rack: List<RackTile>): String? {
        var best: String? = null
        var bestValue = -1
        var lookups = 0
        outer@ for (len in 2..rack.size) {
            for (perm in permutations(rack, len)) {
                for (letters in blankLetterCombinations(perm)) {
                    if (lookups++ >= HARD_OPENING_WORD_BUDGET) break@outer
                    val word = letters.joinToString("")
                    if (!WordDictionary.isValidWord(word)) continue
                    val value = perm.sumOf { TileBag.valueOf(it) }
                    if (value > bestValue) {
                        bestValue = value
                        best = word
                    }
                }
            }
        }
        return best
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
