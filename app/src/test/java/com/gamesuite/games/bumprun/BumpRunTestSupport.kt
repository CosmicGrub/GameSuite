package com.gamesuite.games.bumprun

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.random.Random

val bumpEngine = StandardBumpRunEngine()

fun brPlayers(n: Int, bots: Boolean = false) =
    List(n) { BumpRunPlayer("p${it + 1}", "Player ${it + 1}", bots) }

fun brBoard(
    seats: Int = 2, length: BumpLength = BumpLength.QUICK, pads: Boolean = false, teams: Boolean = false,
    seed: Long = 1, helpers: Set<Int> = emptySet(), starter: Int = 0, bots: Boolean = false
): BumpRunState = bumpEngine.newBoard(brPlayers(seats, bots), BumpRunRules(length, pads, true, teams), seed, helpers, starter)

/** The id of the first card of this kind and value. */
fun brCard(kind: BumpCardKind, value: Int = 0): Int = BumpRunDeck.cards().first { it.kind == kind && it.value == value }.id

/**
 * A table with [card] in play for the seat on turn. [pawns] overrides entries by pawn index (seat * pawnsPerSeat + k);
 * the other pawns stay in the Nest. Cards stay conserved (the rest are in the pile).
 */
fun brHold(
    base: BumpRunState, card: Int, pawns: Map<Int, Int> = emptyMap(), shields: Set<Int> = emptySet(), current: Int = base.currentPlayerIndex
): BumpRunState {
    val p = base.pawns.toMutableList()
    for ((i, g) in pawns) p[i] = g
    return base.copy(
        pawns = p, shields = List(p.size) { it in shields }, currentPlayerIndex = current, phase = BumpPhase.AWAIT_CHOICE,
        card = card, pile = (0 until BumpRunDeck.SIZE).filter { it != card }, discard = emptyList()
    )
}

/** Ring square of a pawn of [seat] at [progress] on a table with [seats] seats. */
fun brSquare(seats: Int, seat: Int, progress: Int): Int = BumpRunBoard.squareOf(BumpRunBoard.sidesFor(seats)[seat], progress)!!

/** Progress that puts a pawn of [seat] on ring [square]. */
fun brProgressAt(seats: Int, seat: Int, square: Int): Int = BumpRunBoard.progressOn(BumpRunBoard.sidesFor(seats)[seat], square)

fun brStep(s: BumpRunState, pawn: Int, to: Int) = bumpEngine.play(s, BumpMove(pawn, BumpMoveKind.STEP, to))

/** One computer action on a bare state (same decisions as [BumpRunGame.playBotStep]). */
fun brBotStep(s: BumpRunState, level: CpuDifficulty): BumpRunState {
    if (s.phase == BumpPhase.AWAIT_DRAW) return bumpEngine.draw(s)
    val rnd = Random(s.turnSerial * 31L + s.currentPlayerIndex)
    val legal = bumpEngine.legalMoves(s)
    if (s.doOvers[s.currentPlayerIndex] > 0 && BumpRunBot.wantsDoOver(s, legal, level, rnd)) return bumpEngine.doOver(s)
    val move = BumpRunBot.choose(s, legal, level, rnd) ?: BumpMove(-1, BumpMoveKind.PASS)
    return bumpEngine.play(s, move)
}

/** Plays a board to the end; [levels] per seat. Fails when it stalls. */
fun brPlayOut(start: BumpRunState, levels: List<CpuDifficulty>, limit: Int = 20_000, check: ((BumpRunState) -> Unit)? = null): BumpRunState {
    var s = start
    var steps = 0
    while (!s.over) {
        val next = brBotStep(s, levels[s.currentPlayerIndex])
        check(next !== s) { "the table stalled at step $steps" }
        check?.invoke(next)
        s = next
        check(++steps < limit) { "no winner after $limit steps" }
    }
    return s
}

fun brGame(
    players: List<PlayerInfo> = List(2) { PlayerInfo("p${it + 1}", "Player ${it + 1}") },
    random: Random = Random(1), start: Boolean = true, configure: BumpRunGame.() -> Unit = {}
): BumpRunGame {
    val g = BumpRunGame(random)
    g.configure()
    g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
    if (start) g.startMatch()
    return g
}

/** The same table with card [id] on top of the pile (still 54 cards in all). */
fun brTop(s: BumpRunState, id: Int): BumpRunState = s.copy(pile = s.pile.filter { it != id } + id, discard = s.discard.filter { it != id })
