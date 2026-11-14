package com.gamesuite.games.backgammon

import kotlin.random.Random

/**
 * Fixture helpers shared by the Backgammon tests. EVERY coordinate here is a WHITE point number
 * (1..24, White moves 24 -> 1 and bears off past 1): the black map is converted for you, so a
 * fixture reads like a diagram drawn from White's side of the board.
 */
internal fun bgPos(
    white: Map<Int, Int> = emptyMap(),
    black: Map<Int, Int> = emptyMap(),
    whiteBar: Int = 0,
    blackBar: Int = 0,
    whiteOff: Int = 0,
    blackOff: Int = 0
): BackgammonBoard =
    // Black's own number for White's point p is 25 - p.
    BackgammonBoard.custom(white, black.mapKeys { 25 - it.key }, whiteBar, blackBar, whiteOff, blackOff)

/** The absolute board index of White's point number [p]. */
internal fun bgIx(p: Int): Int = p - 1

/** A move between two White point numbers. */
internal fun bgMove(fromPoint: Int, toPoint: Int, die: Int, hit: Boolean = false): BackgammonMove =
    BackgammonMove(bgIx(fromPoint), bgIx(toPoint), die, hit)

/** The dice of a roll as the engine uses them: a double is four copies. */
internal fun bgDice(a: Int, b: Int): List<Int> = if (a == b) List(4) { a } else listOf(a, b)

/**
 * True when [moves] is a complete, legal turn: each move is among the legal next moves at its
 * moment, and once played nothing more can be played with the dice left over.
 */
internal fun bgReplayIsLegal(board: BackgammonBoard, side: Int, remaining: List<Int>, moves: List<BackgammonMove>): Boolean {
    var b = board
    var rem = remaining
    for (m in moves) {
        if (m !in BackgammonRules.legalMoves(b, side, rem)) return false
        b = b.apply(side, m)
        val left = rem.toMutableList()
        left.remove(m.die)
        rem = left
    }
    return BackgammonRules.legalMoves(b, side, rem).isEmpty()
}

/** Positions reached by random legal play from the opening, as (board, side to move). */
internal fun bgRandomPositions(seed: Int, plies: Int, every: Int = 1): List<Pair<BackgammonBoard, Int>> {
    val rnd = Random(seed)
    var board = BackgammonBoard.initial()
    var side = rnd.nextInt(2)
    val out = ArrayList<Pair<BackgammonBoard, Int>>()
    for (ply in 0 until plies) {
        if (BackgammonRules.gameOver(board)) break
        if (ply % every == 0) out.add(board to side)
        val dice = bgDice(rnd.nextInt(1, 7), rnd.nextInt(1, 7))
        val turns = BackgammonRules.generateTurns(board, side, dice)
        if (turns.isNotEmpty()) board = turns[rnd.nextInt(turns.size)].result
        side = 1 - side
    }
    return out
}

/** A Random whose dice come from a script (nextInt(from, until)); everything else, and the script's end, falls back to a seeded Random. */
internal class BgScriptedRandom(private val script: List<Int>, seed: Int = 7) : Random() {
    private var next = 0
    private val fallback = Random(seed)
    override fun nextBits(bitCount: Int): Int = fallback.nextBits(bitCount)
    override fun nextInt(until: Int): Int = fallback.nextInt(until)
    override fun nextInt(from: Int, until: Int): Int = if (next < script.size) script[next++] else fallback.nextInt(from, until)
}
