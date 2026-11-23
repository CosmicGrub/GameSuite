package com.gamesuite.games.spider

/**
 * Pure Spider Solitaire rules: no Compose, no Android, no randomness. A card is its Int id 0..103:
 * `id / 13` is its block of thirteen (Ace..King) and `id % 13 + 1` its rank (1 = Ace, 13 = King).
 * The suit of a block depends on how many suits the game uses ([suitOf]): one suit means all eight
 * blocks are spades, two suits alternate spades and hearts, four suits cycle spades, hearts, clubs,
 * diamonds twice. That is the standard 104-card Spider deck for every difficulty.
 */
const val SPIDER_COLUMNS = 10
const val SPIDER_DECK_SIZE = 104
const val SPIDER_RUN_LENGTH = 13
const val SPIDER_RUNS_TO_WIN = SPIDER_DECK_SIZE / SPIDER_RUN_LENGTH
const val SPIDER_START_SCORE = 500
const val SPIDER_RUN_BONUS = 100

/** Suit indices as used by [SpiderRules.suitOf]. */
const val SPIDER_SPADES = 0
const val SPIDER_HEARTS = 1
const val SPIDER_CLUBS = 2
const val SPIDER_DIAMONDS = 3

val SPIDER_VALID_SUIT_COUNTS: List<Int> = listOf(1, 2, 4)

/**
 * The whole table. [columns] are bottom-to-top; the first [faceDown] cards of each column are face
 * down, so the top card of a non-empty column is always face up. [stock] holds the undealt rows
 * (ten cards each; the next row is the LAST ten). [completed] is the flat list of every card removed
 * in finished King-to-Ace runs, thirteen at a time in the order they sat in the column.
 */
data class SpiderState(
    val suits: Int,
    val columns: List<List<Int>>,
    val faceDown: List<Int>,
    val stock: List<Int>,
    val completed: List<Int> = emptyList(),
    val moves: Int = 0,
    val score: Int = SPIDER_START_SCORE,
    val elapsedSeconds: Int = 0
) {
    val completedRuns: Int get() = completed.size / SPIDER_RUN_LENGTH
    val dealsLeft: Int get() = stock.size / SPIDER_COLUMNS
    val won: Boolean get() = completedRuns == SPIDER_RUNS_TO_WIN

    fun faceUpCount(col: Int): Int = columns[col].size - faceDown[col]
    fun isFaceUp(col: Int, index: Int): Boolean = index >= faceDown[col]
    fun top(col: Int): Int? = columns[col].lastOrNull()
}

/** "Take the run starting at [index] of column [from] and put it on column [to]." */
data class SpiderMove(val from: Int, val index: Int, val to: Int)

/** A suggested next step: move a run, or deal the next row from the stock. */
sealed class SpiderHint {
    data class Move(val move: SpiderMove) : SpiderHint()
    data object Deal : SpiderHint()
}

object SpiderRules {
    fun rankOf(card: Int): Int = card % SPIDER_RUN_LENGTH + 1

    fun suitOf(card: Int, suits: Int): Int = (card / SPIDER_RUN_LENGTH) % suits

    /** The ten-column opening for an already shuffled [deck] of the 104 ids: 6,6,6,6,5,5,5,5,5,5 cards, only the top one face up; the other 50 become the stock. */
    fun openingState(deck: List<Int>, suits: Int): SpiderState {
        require(suits in SPIDER_VALID_SUIT_COUNTS) { "suits must be 1, 2 or 4" }
        require(deck.size == SPIDER_DECK_SIZE && deck.toSet().size == SPIDER_DECK_SIZE && deck.all { it in 0 until SPIDER_DECK_SIZE }) {
            "deck must be a permutation of 0..103"
        }
        var cursor = 0
        val columns = List(SPIDER_COLUMNS) { c ->
            val n = if (c < 4) 6 else 5
            deck.subList(cursor, cursor + n).toList().also { cursor += n }
        }
        return SpiderState(
            suits = suits,
            columns = columns,
            faceDown = columns.map { it.size - 1 },
            stock = deck.subList(cursor, deck.size).toList()
        )
    }

    /** True when cards [index]..top of [col] are face up and form a same-suit, one-step-descending run. */
    fun canPickUp(s: SpiderState, col: Int, index: Int): Boolean {
        if (col !in 0 until SPIDER_COLUMNS) return false
        val cards = s.columns[col]
        if (index < 0 || index >= cards.size || index < s.faceDown[col]) return false
        for (i in index until cards.size - 1) {
            val a = cards[i]
            val b = cards[i + 1]
            if (rankOf(a) != rankOf(b) + 1 || suitOf(a, s.suits) != suitOf(b, s.suits)) return false
        }
        return true
    }

    /** Index of the first card of the longest movable run on top of [col], or -1 for an empty column. */
    fun runStart(s: SpiderState, col: Int): Int {
        val cards = s.columns[col]
        if (cards.isEmpty()) return -1
        var i = cards.size - 1
        while (i > s.faceDown[col] && canPickUp(s, col, i - 1)) i--
        return i
    }

    /** Length of the suited descending chain ending at the top of [cards] (no face-down awareness; used for destinations). */
    fun suitedChainAtTop(cards: List<Int>, suits: Int, floor: Int = 0): Int {
        if (cards.isEmpty()) return 0
        var n = 1
        var i = cards.size - 1
        while (i - 1 >= floor && rankOf(cards[i - 1]) == rankOf(cards[i]) + 1 && suitOf(cards[i - 1], suits) == suitOf(cards[i], suits)) {
            n++
            i--
        }
        return n
    }

    fun canDrop(s: SpiderState, from: Int, index: Int, to: Int): Boolean {
        if (to !in 0 until SPIDER_COLUMNS || to == from) return false
        if (!canPickUp(s, from, index)) return false
        val destTop = s.columns[to].lastOrNull() ?: return true
        return rankOf(destTop) == rankOf(s.columns[from][index]) + 1
    }

    /** Every rule-legal move (including pointless ones like shifting a whole column to an empty column). */
    fun legalMoves(s: SpiderState): List<SpiderMove> {
        val out = ArrayList<SpiderMove>()
        for (from in 0 until SPIDER_COLUMNS) {
            val cards = s.columns[from]
            if (cards.isEmpty()) continue
            val start = runStart(s, from)
            for (index in start until cards.size) {
                for (to in 0 until SPIDER_COLUMNS) {
                    if (canDrop(s, from, index, to)) out += SpiderMove(from, index, to)
                }
            }
        }
        return out
    }

    /** A row may be dealt only while the stock lasts and no column is empty. */
    fun canDeal(s: SpiderState): Boolean = s.stock.size >= SPIDER_COLUMNS && s.columns.none { it.isEmpty() }

    /** Result of [applyMove] / [applyDeal]: the new table plus what happened, for animation and announcements. */
    class Outcome(val state: SpiderState, val flipped: Boolean, val completedSuits: List<Int>)

    fun applyMove(s: SpiderState, move: SpiderMove): Outcome? {
        if (!canDrop(s, move.from, move.index, move.to)) return null
        val cols = s.columns.map { it.toMutableList() }
        val down = s.faceDown.toMutableList()
        val moving = cols[move.from].subList(move.index, cols[move.from].size).toList()
        repeat(moving.size) { cols[move.from].removeAt(cols[move.from].size - 1) }
        cols[move.to].addAll(moving)
        val flipped = flipTop(cols, down, move.from)
        val completedCards = ArrayList<Int>()
        val completedSuits = ArrayList<Int>()
        // Only the destination can have formed a King-to-Ace run.
        if (removeRunIfComplete(cols, down, move.to, s.suits, completedCards, completedSuits)) {
            flipTop(cols, down, move.to)
        }
        return Outcome(
            state = finish(s, cols, down, s.stock, completedCards, completedSuits, moveCost = 1),
            flipped = flipped,
            completedSuits = completedSuits
        )
    }

    fun applyDeal(s: SpiderState): Outcome? {
        if (!canDeal(s)) return null
        val cols = s.columns.map { it.toMutableList() }
        val down = s.faceDown.toMutableList()
        val row = s.stock.subList(s.stock.size - SPIDER_COLUMNS, s.stock.size)
        for (c in 0 until SPIDER_COLUMNS) cols[c].add(row[c])
        val completedCards = ArrayList<Int>()
        val completedSuits = ArrayList<Int>()
        for (c in 0 until SPIDER_COLUMNS) {
            if (removeRunIfComplete(cols, down, c, s.suits, completedCards, completedSuits)) flipTop(cols, down, c)
        }
        return Outcome(
            state = finish(s, cols, down, s.stock.subList(0, s.stock.size - SPIDER_COLUMNS).toList(), completedCards, completedSuits, moveCost = 1),
            flipped = false,
            completedSuits = completedSuits
        )
    }

    private fun finish(
        s: SpiderState,
        cols: List<MutableList<Int>>,
        down: List<Int>,
        stock: List<Int>,
        completedCards: List<Int>,
        completedSuits: List<Int>,
        moveCost: Int
    ): SpiderState = s.copy(
        columns = cols.map { it.toList() },
        faceDown = down.toList(),
        stock = stock,
        completed = s.completed + completedCards,
        moves = s.moves + 1,
        score = (s.score - moveCost).coerceAtLeast(0) + SPIDER_RUN_BONUS * completedSuits.size
    )

    /** Turns the top card up when only face-down cards remain in [col]. */
    private fun flipTop(cols: List<MutableList<Int>>, down: MutableList<Int>, col: Int): Boolean {
        if (cols[col].isNotEmpty() && down[col] >= cols[col].size) {
            down[col] = cols[col].size - 1
            return true
        }
        if (cols[col].isEmpty()) down[col] = 0
        return false
    }

    private fun removeRunIfComplete(
        cols: List<MutableList<Int>>,
        down: MutableList<Int>,
        col: Int,
        suits: Int,
        outCards: MutableList<Int>,
        outSuits: MutableList<Int>
    ): Boolean {
        val cards = cols[col]
        if (cards.size - down[col] < SPIDER_RUN_LENGTH) return false
        val base = cards.size - SPIDER_RUN_LENGTH
        val suit = suitOf(cards[base], suits)
        for (k in 0 until SPIDER_RUN_LENGTH) {
            val c = cards[base + k]
            if (rankOf(c) != SPIDER_RUN_LENGTH - k || suitOf(c, suits) != suit) return false
        }
        outCards.addAll(cards.subList(base, cards.size))
        outSuits += suit
        repeat(SPIDER_RUN_LENGTH) { cards.removeAt(cards.size - 1) }
        if (down[col] > cards.size) down[col] = cards.size
        return true
    }

    /** True while any move or a deal remains. */
    fun hasAnyMove(s: SpiderState): Boolean = !s.won && (canDeal(s) || legalMoves(s).isNotEmpty())

    /**
     * Structural validity of a state: every card exactly once across columns, stock and completed;
     * face-down counts in range with a face-up top; stock a multiple of ten; completed made of whole
     * King-to-Ace suited runs. Used to validate a snapshot and as a fuzz-test invariant.
     */
    fun isConsistent(s: SpiderState): Boolean {
        if (s.suits !in SPIDER_VALID_SUIT_COUNTS) return false
        if (s.columns.size != SPIDER_COLUMNS || s.faceDown.size != SPIDER_COLUMNS) return false
        val all = s.columns.flatten() + s.stock + s.completed
        if (all.size != SPIDER_DECK_SIZE || all.toSet().size != SPIDER_DECK_SIZE || all.any { it !in 0 until SPIDER_DECK_SIZE }) return false
        for (c in 0 until SPIDER_COLUMNS) {
            val n = s.columns[c].size
            val d = s.faceDown[c]
            if (d < 0) return false
            if (n == 0 && d != 0) return false
            if (n > 0 && d > n - 1) return false
        }
        if (s.stock.size % SPIDER_COLUMNS != 0 || s.stock.size > 50) return false
        if (s.completed.size % SPIDER_RUN_LENGTH != 0) return false
        for (r in 0 until s.completedRuns) {
            val suit = suitOf(s.completed[r * SPIDER_RUN_LENGTH], s.suits)
            for (k in 0 until SPIDER_RUN_LENGTH) {
                val c = s.completed[r * SPIDER_RUN_LENGTH + k]
                if (rankOf(c) != SPIDER_RUN_LENGTH - k || suitOf(c, s.suits) != suit) return false
            }
        }
        return s.moves >= 0 && s.score >= 0 && s.elapsedSeconds >= 0
    }
}
