package com.gamesuite.games.reversi

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Differential + bot-quality tests: the real ReversiGame engine is compared, ply by ply, against the
 * independent reference object below on full random games, hand-assigned mid-game and arbitrary
 * dense boards, perft trees, and exact endgame solves for the minimax bot.
 *
 * CHARACTERIZATION NOTE: the rules half of the reference is plain Othello (validated against the
 * published perft numbers). The BOT half deliberately mirrors the engine's DOCUMENTED heuristic --
 * its weight table, mobility weight, per-tier depths (2/4/6), the pass-costs-a-ply search rule and
 * the corner-first move order -- so the bot-differential tests are characterization tests: when
 * those numbers are intentionally retuned, the reference constants (and the tie-break pin test and
 * the corner-weight pin test) must be updated with them. The exact-endgame tests and the strength test do not depend on the
 * constants and should survive a retune unchanged.
 */
class ReversiReferenceEngineTest {

    // ------------------------------------------------------------------ board helpers

    private fun List<Int?>.toRef(): IntArray = IntArray(size) { this[it] ?: EMPTY }
    private fun IntArray.toEngine(): List<Int?> = map { if (it == EMPTY) null else it }

    /** Symmetry t in 0..7: t%4 quarter-turns then an optional left-right mirror (t>=4). */
    private fun transformIndex(t: Int, index: Int): Int {
        var r = index / 8
        var c = index % 8
        repeat(t % 4) { val nr = c; val nc = 7 - r; r = nr; c = nc }
        if (t >= 4) c = 7 - c
        return r * 8 + c
    }

    private fun transformCells(t: Int, cells: IntArray): IntArray {
        val out = IntArray(64) { EMPTY }
        for (i in 0 until 64) out[transformIndex(t, i)] = cells[i]
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // INDEPENDENT REFERENCE OTHELLO
    //
    // Deliberately NOT structured like ReversiGame: no while-loop ray walk, no DIRECTIONS list, no
    // per-cell "flanksInDirection". Instead every ray is a precomputed explicit list of flat cell
    // indices built from coordinate math filtered to the board; a ray's capture is decided by looking at
    // the list of values on it ("first non-opponent element, and at least one opponent before it").
    // A second, structurally different formulation (expand from every OWN disc outward) is used to
    // cross-check the first one, and the whole reference is validated against the published Othello
    // perft numbers so a wrong reference cannot silently bless a wrong engine.
    // ---------------------------------------------------------------------------------------------

    private object Ref {
        /** The eight unit steps, generated as "every (dr,dc) in {-1,0,1}^2 except (0,0)". */
        private val steps: List<Pair<Int, Int>> =
            (-1..1).flatMap { dr -> (-1..1).map { dc -> dr to dc } }.filter { it != (0 to 0) }

        /** rays[index] = for each step, the flat indices of every cell along that ray, nearest first. */
        val rays: List<List<List<Int>>> = (0 until 64).map { index ->
            val r0 = index / 8
            val c0 = index % 8
            steps.map { (dr, dc) ->
                generateSequence(1) { it + 1 }
                    .map { k -> (r0 + dr * k) to (c0 + dc * k) }
                    .takeWhile { (r, c) -> r in 0 until 8 && c in 0 until 8 }
                    .map { (r, c) -> r * 8 + c }
                    .toList()
            }
        }

        /** Flat indices the [player] would flip by placing on [index] (empty list if none / occupied). */
        fun flips(cells: IntArray, index: Int, player: Int): List<Int> {
            if (cells[index] != EMPTY) return emptyList()
            val opp = 1 - player
            val out = mutableListOf<Int>()
            for (ray in rays[index]) {
                val values = ray.map { cells[it] }
                val firstNonOpp = values.indexOfFirst { it != opp }
                if (firstNonOpp >= 1 && values[firstNonOpp] == player) out += ray.subList(0, firstNonOpp)
            }
            return out
        }

        /** Allocation-free legality: some ray from [index] is "one or more opponent cells, then own disc". */
        fun isLegal(cells: IntArray, index: Int, player: Int): Boolean {
            if (cells[index] != EMPTY) return false
            val opp = 1 - player
            for (ray in rays[index]) {
                var k = 0
                while (k < ray.size && cells[ray[k]] == opp) k++
                if (k >= 1 && k < ray.size && cells[ray[k]] == player) return true
            }
            return false
        }

        fun legalMoves(cells: IntArray, player: Int): Set<Int> =
            (0 until 64).filter { isLegal(cells, it, player) }.toSet()

        // ---- independent re-derivation of the documented bot: heuristic + plain (unpruned) minimax ----

        private fun isCornerCell(r: Int, c: Int) = (r == 0 || r == 7) && (c == 0 || c == 7)

        /** Positional weight per the engine's documented table, derived from coordinates. */
        fun weight(cells: IntArray, index: Int, cornerWeight: Int = 120): Int {
            val r = index / 8
            val c = index % 8
            if (isCornerCell(r, c)) return cornerWeight
            if ((r == 1 || r == 6) && (c == 1 || c == 6)) return -20
            val onEdge = r == 0 || r == 7 || c == 0 || c == 7
            if (onEdge) {
                val along = if (r == 0 || r == 7) c else r
                if (along == 1 || along == 6) { // C-square: look at the adjacent corner
                    val cr = if (r == 0 || r == 7) r else if (along == 1) 0 else 7
                    val cc = if (r == 0 || r == 7) (if (along == 1) 0 else 7) else c
                    return if (cells[cr * 8 + cc] == EMPTY) -40 else 10
                }
                return if (along == 2 || along == 5) 10 else 6
            }
            return if (r == 1 || r == 6 || c == 1 || c == 6) 1 else 3
        }

        /** True iff a pass node occurs within the next [plies] plies of the exact tree below (cells, toMove). */
        fun passWithin(cells: IntArray, toMove: Int, plies: Int): Boolean {
            if (plies == 0) return false
            val moves = legalMoves(cells, toMove)
            if (moves.isEmpty()) return legalMoves(cells, 1 - toMove).isNotEmpty()
            return moves.any { passWithin(apply(cells, it, toMove), 1 - toMove, plies - 1) }
        }

        /** The documented root move order: corners first, then other edge cells, then interior, then X-/C-squares last. */
        fun orderPriority(index: Int): Int {
            val r = index / 8
            val c = index % 8
            if (isCornerCell(r, c)) return 3
            if ((r == 1 || r == 6) && (c == 1 || c == 6)) return 0
            val onEdge = r == 0 || r == 7 || c == 0 || c == 7
            if (onEdge) {
                val along = if (r == 0 || r == 7) c else r
                return if (along == 1 || along == 6) 0 else 2
            }
            return 1
        }

        fun heuristic(cells: IntArray, bot: Int, cornerWeight: Int = 120): Int {
            var total = 0
            for (i in 0 until 64) {
                if (cells[i] == EMPTY) continue
                total += if (cells[i] == bot) weight(cells, i, cornerWeight) else -weight(cells, i, cornerWeight)
            }
            total += (legalMoves(cells, bot).size - legalMoves(cells, 1 - bot).size) * 12
            return total
        }

        /**
         * Bot-perspective minimax value; game over is the +-1_000_000 disc-diff score. A pass costs one
         * ply (the engine's documented rule) unless [passCostsPly] is false, which recurses at the SAME
         * depth and exists only so a test can build positions where that rule is decisive. [cornerWeight]
         * likewise exists only so a test can find positions where a +-1 nudge of that constant is decisive.
         */
        fun botValue(
            cells: IntArray, toMove: Int, depth: Int, bot: Int,
            passCostsPly: Boolean = true, cornerWeight: Int = 120
        ): Int {
            val moves = legalMoves(cells, toMove)
            if (moves.isEmpty()) {
                if (legalMoves(cells, 1 - toMove).isEmpty()) {
                    val c = counts(cells)
                    val diff = c[bot] - c[1 - bot]
                    return if (diff > 0) 1_000_000 + diff else if (diff < 0) -1_000_000 + diff else 0
                }
                if (depth == 0) return heuristic(cells, bot, cornerWeight)
                return botValue(cells, 1 - toMove, if (passCostsPly) depth - 1 else depth, bot, passCostsPly, cornerWeight)
            }
            if (depth == 0) return heuristic(cells, bot, cornerWeight)
            val values = moves.map { botValue(apply(cells, it, toMove), 1 - toMove, depth - 1, bot, passCostsPly, cornerWeight) }
            return if (toMove == bot) values.max() else values.min()
        }

        /** Every root move whose exact depth-limited minimax value is maximal (ties allowed). */
        fun botBestMoves(
            cells: IntArray, bot: Int, plies: Int, passCostsPly: Boolean = true, cornerWeight: Int = 120
        ): Set<Int> {
            val values = legalMoves(cells, bot).associateWith {
                botValue(apply(cells, it, bot), 1 - bot, plies - 1, bot, passCostsPly, cornerWeight)
            }
            val best = values.values.max()
            return values.filterValues { it == best }.keys
        }

        /** Second formulation: start from each of [player]'s OWN discs and look outward for a run of
         *  opponent discs ended by an empty cell; that empty cell is a legal move. */
        fun legalMovesViaOwnDiscs(cells: IntArray, player: Int): Set<Int> {
            val opp = 1 - player
            val out = mutableSetOf<Int>()
            for (d in 0 until 64) {
                if (cells[d] != player) continue
                for (ray in rays[d]) {
                    val values = ray.map { cells[it] }
                    val run = values.indexOfFirst { it != opp }.let { if (it == -1) values.size else it }
                    if (run >= 1 && run < values.size && values[run] == EMPTY) out += ray[run]
                }
            }
            return out
        }

        fun apply(cells: IntArray, index: Int, player: Int): IntArray {
            val next = cells.copyOf()
            val f = flips(cells, index, player)
            require(f.isNotEmpty()) { "illegal reference move $index for $player" }
            next[index] = player
            f.forEach { next[it] = player }
            return next
        }

        fun counts(cells: IntArray): IntArray = intArrayOf(cells.count { it == 0 }, cells.count { it == 1 })

        fun emptiesOf(cells: IntArray): Int = cells.count { it == EMPTY }

        /** Who moves after [justMoved] placed: the opponent if they can, else the mover again (a pass),
         *  else -1 for game over. */
        fun nextMover(cells: IntArray, justMoved: Int): Int = when {
            legalMoves(cells, 1 - justMoved).isNotEmpty() -> 1 - justMoved
            legalMoves(cells, justMoved).isNotEmpty() -> justMoved
            else -> -1
        }

        /** Exact final disc difference (toMove minus opponent) under perfect play by both, with passes. */
        fun solve(cells: IntArray, toMove: Int): Int {
            val moves = legalMoves(cells, toMove)
            if (moves.isEmpty()) {
                val opp = 1 - toMove
                if (legalMoves(cells, opp).isEmpty()) {
                    val c = counts(cells)
                    return c[toMove] - c[opp]
                }
                return -solve(cells, opp)
            }
            return moves.maxOf { -solve(apply(cells, it, toMove), 1 - toMove) }
        }

        /** True iff some node in the exact game tree below (cells, toMove) is a pass. */
        fun treeHasPass(cells: IntArray, toMove: Int): Boolean {
            val moves = legalMoves(cells, toMove)
            if (moves.isEmpty()) {
                return legalMoves(cells, 1 - toMove).isNotEmpty()
            }
            return moves.any { treeHasPass(apply(cells, it, toMove), 1 - toMove) }
        }

        /**
         * True iff a depth-limited search that spends [remaining] plies (a pass costs a ply, matching the
         * engine's documented rule) reaches a genuine game-over on EVERY path, i.e. never has to fall
         * back to the heuristic at its horizon. Only such positions are used to demand exact play.
         */
        fun horizonExact(cells: IntArray, toMove: Int, remaining: Int): Boolean {
            val moves = legalMoves(cells, toMove)
            if (moves.isEmpty()) {
                if (legalMoves(cells, 1 - toMove).isEmpty()) return true
                if (remaining == 0) return false
                return horizonExact(cells, 1 - toMove, remaining - 1)
            }
            if (remaining == 0) return false
            return moves.all { horizonExact(apply(cells, it, toMove), 1 - toMove, remaining - 1) }
        }

        /** Placement-count perft with the engine's turn resolution (opponent, else mover again, else over). */
        fun perft(cells: IntArray, mover: Int, depth: Int): Long {
            if (depth == 0) return 1
            val moves = legalMoves(cells, mover)
            if (moves.isEmpty()) return 1
            var total = 0L
            for (m in moves) {
                val nc = apply(cells, m, mover)
                val next = nextMover(nc, mover)
                total += if (next < 0) 1 else perft(nc, next, depth - 1)
            }
            return total
        }

        /** The standard opening for a given starting player (matches ReversiGame.startMatch's layout). */
        fun opening(starting: Int): IntArray {
            val c = IntArray(64) { EMPTY }
            c[27] = 1 - starting; c[36] = 1 - starting
            c[28] = starting; c[35] = starting
            return c
        }
    }

    // ------------------------------------------------------------------ harness

    private fun newTwoHumanGame(): ReversiGame {
        val game = ReversiGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = listOf(
                    PlayerInfo(playerId = "p1", displayName = "Player 1"),
                    PlayerInfo(playerId = "p2", displayName = "Player 2")
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    /** Human = index 0, bot = index 1. */
    private fun newVsBotGame(difficulty: CpuDifficulty): ReversiGame {
        val game = ReversiGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = "you", displayName = "You"),
                    PlayerInfo(playerId = "cpu", displayName = "CPU", isBot = true)
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = difficulty
        return game
    }

    /** Overwrites the live state with [cells] / [current]; legalMoves defaults to the reference's.
     *  Requires startMatch() to have run so the players list exists. */
    private fun ReversiGame.setPosition(cells: IntArray, current: Int, legal: Set<Int>? = null) {
        val old = state.value!!
        val c = Ref.counts(cells)
        state.value = old.copy(
            cells = cells.toEngine(),
            currentPlayerIndex = current,
            legalMoves = legal ?: Ref.legalMoves(cells, current),
            scores = listOf(c[0], c[1]),
            lastAction = "test position",
            justPassed = false,
            boardOver = false,
            winnerPlayerId = null
        )
    }

    private fun sortedLegal(game: ReversiGame): List<Int> = game.state.value!!.legalMoves.sorted()

    private class Events {
        var plies = 0
        var passPlies = 0
        var gamesWithPass = 0
        var boardsOver = 0
        var endedWithEmpties = 0
        var wipeouts = 0
        var draws = 0
        var p0Wins = 0
        var p1Wins = 0
    }

    private class PlyRecord(val before: IntArray, val mover: Int, val index: Int, val after: IntArray, val flips: Int)

    /**
     * Places [index] through the real engine and asserts EVERY observable consequence against the
     * reference. Returns the record of the ply. [label] identifies the scenario in failure messages.
     */
    private fun checkedPlace(game: ReversiGame, index: Int, label: String, events: Events?): PlyRecord {
        val s = game.state.value!!
        val mover = s.currentPlayerIndex
        val opp = 1 - mover
        val before = s.cells.toRef()
        assertTrue("$label: index $index must be reference-legal", index in Ref.legalMoves(before, mover))

        game.placeDisc(index)
        val ns = game.state.value!!
        val expected = Ref.apply(before, index, mover)
        val flips = Ref.flips(before, index, mover).size
        assertEquals("$label: cells after placing $index as $mover", expected.toList(), ns.cells.toRef().toList())
        val c = Ref.counts(expected)
        assertEquals("$label: scores must equal the disc counts", listOf(c[0], c[1]), ns.scores)

        val oppMoves = Ref.legalMoves(expected, opp)
        val moverMoves = Ref.legalMoves(expected, mover)
        val names = s.players.map { it.displayName }
        when {
            oppMoves.isNotEmpty() -> {
                assertFalse("$label: no pass when the opponent can move", ns.justPassed)
                assertFalse("$label: game must not be over", ns.boardOver)
                assertEquals("$label: turn goes to the opponent", opp, ns.currentPlayerIndex)
                assertEquals("$label: legalMoves for the new mover", oppMoves, ns.legalMoves)
                assertEquals("${names[mover]} places a disc, flipping $flips", ns.lastAction)
                assertNull(ns.winnerPlayerId)
            }
            moverMoves.isNotEmpty() -> {
                assertTrue("$label: justPassed exactly when opponent is stuck but mover is not", ns.justPassed)
                assertFalse("$label: game must not be over on a pass", ns.boardOver)
                assertEquals("$label: turn returns to the mover", mover, ns.currentPlayerIndex)
                assertEquals("$label: legalMoves for the passing-back mover", moverMoves, ns.legalMoves)
                assertEquals("${names[opp]} has no legal move and passes", ns.lastAction)
                assertNull(ns.winnerPlayerId)
                events?.let { it.passPlies++ }
            }
            else -> {
                assertTrue("$label: board over exactly when neither side can move", ns.boardOver)
                assertFalse("$label: an over board is not a pass", ns.justPassed)
                assertTrue("$label: no legal moves when over", ns.legalMoves.isEmpty())
                val expectedWinner = when {
                    c[0] > c[1] -> s.players[0].playerId
                    c[1] > c[0] -> s.players[1].playerId
                    else -> null
                }
                assertEquals("$label: winner", expectedWinner, ns.winnerPlayerId)
                val expectedText = if (expectedWinner != null) {
                    "${s.players.first { it.playerId == expectedWinner }.displayName} wins ${c[0]}-${c[1]}!"
                } else "It's a tie, ${c[0]}-${c[1]}!"
                assertEquals("$label: final message", expectedText, ns.lastAction)
                events?.let {
                    it.boardsOver++
                    if (Ref.emptiesOf(expected) > 0) it.endedWithEmpties++
                    if (c[0] == 0 || c[1] == 0) it.wipeouts++
                    when {
                        c[0] > c[1] -> it.p0Wins++
                        c[1] > c[0] -> it.p1Wins++
                        else -> it.draws++
                    }
                }
            }
        }
        // A finished board is inert: every further placeDisc (any index) must leave the very same state object.
        if (ns.boardOver) {
            for (i in listOf(-1, 0, index, 27, 63, 64)) {
                game.placeDisc(i)
                assertSame("$label: placeDisc on an over board must be a no-op", ns, game.state.value)
            }
        }
        events?.let { it.plies++ }
        return PlyRecord(before, mover, index, expected, flips)
    }

    /** Verifies the live state's legalMoves against the reference for the player to move. */
    private fun assertLegalMatchesRef(game: ReversiGame, label: String) {
        val s = game.state.value!!
        if (s.boardOver) return
        assertEquals("$label: legalMoves", Ref.legalMoves(s.cells.toRef(), s.currentPlayerIndex), s.legalMoves)
        assertTrue("$label: a live board must offer the mover a move", s.legalMoves.isNotEmpty())
    }

    private class Corpus(val records: List<PlyRecord>, val events: Events)

    companion object {
        private const val EMPTY = -1
        private const val CORPUS_SEEDS = 140

        /** Shared by the full-game differential and the per-ply invariants test so the games run once. */
        private val corpus: Corpus by lazy { ReversiReferenceEngineTest().playCorpus() }

        private val endgamePositions: List<EndPos> by lazy { ReversiReferenceEngineTest().buildEndgamePositions() }

        private val easyCases: List<RefCase> by lazy {
            ReversiReferenceEngineTest().let { it.refCases(it.midgamePositions(300, 606, 6, 56), 2) }
        }
        private val mediumCases: List<RefCase> by lazy {
            ReversiReferenceEngineTest().let { it.refCases(it.midgamePositions(40, 707, 8, 48), 4) }
        }
        private val hardLateCases: List<RefCase> by lazy {
            ReversiReferenceEngineTest().let { it.refCases(it.midgamePositions(30, 808, 8, 12), 6) }
        }
        private val hardPassCases: List<RefCase> by lazy {
            ReversiReferenceEngineTest().let { it.refCases(it.passRichPositions(), 6) }
        }
    }

    /**
     * Each seed drives ONE session of two boards (startMatch then playAgain, so both starting
     * players are exercised) purely via placeDisc with seeded random choices among sorted legalMoves.
     */
    private fun playCorpus(): Corpus {
        val events = Events()
        val records = mutableListOf<PlyRecord>()
        for (seed in 0 until CORPUS_SEEDS) {
            val rnd = java.util.Random(1000L + seed)
            val game = newTwoHumanGame()
            var expectedWins = intArrayOf(0, 0)
            var expectedDraws = 0
            var sawPass = false
            for (board in 0 until 2) {
                if (board == 0) game.startMatch() else game.playAgain()
                val starting = board // player 0 opens board 0, player 1 opens board 1
                val s0 = game.state.value!!
                assertEquals("seed $seed board $board: alternating starter", starting, s0.currentPlayerIndex)
                assertEquals(Ref.opening(starting).toList(), s0.cells.toRef().toList())
                assertEquals(Ref.legalMoves(Ref.opening(starting), starting), s0.legalMoves)
                var placements = 0
                while (!game.state.value!!.boardOver) {
                    assertLegalMatchesRef(game, "seed $seed board $board ply $placements")
                    val moves = sortedLegal(game)
                    val choice = moves[rnd.nextInt(moves.size)]
                    val rec = checkedPlace(game, choice, "seed $seed board $board ply $placements", events)
                    records += rec
                    placements++
                    val ns = game.state.value!!
                    assertEquals("discs on board == 4 + placements", 4 + placements, ns.scores.sum())
                    if (ns.justPassed) sawPass = true
                }
                val fin = game.state.value!!
                when (fin.winnerPlayerId) {
                    "p1" -> expectedWins[0]++
                    "p2" -> expectedWins[1]++
                    else -> expectedDraws++
                }
                // Session tallies must track the reference winner across boards.
                assertEquals(mapOf("p1" to expectedWins[0], "p2" to expectedWins[1]), game.sessionWins.value)
                assertEquals(expectedDraws, game.sessionDraws.value)
            }
            if (sawPass) events.gamesWithPass++
        }
        return Corpus(records, events)
    }

    // ------------------------------------------------------------------ the reference itself

    /**
     * Guards against a wrong REFERENCE blessing a wrong engine: perft counts are the published
     * Othello numbers, so any error in ray/flip logic in the reference changes them.
     */
    @Test
    fun `reference engine reproduces the published Othello perft numbers`() {
        val expected = listOf(4L, 12L, 56L, 244L, 1396L, 8200L, 55092L)
        val start = Ref.opening(0)
        expected.forEachIndexed { i, n -> assertEquals("perft(${i + 1})", n, Ref.perft(start, 0, i + 1)) }
    }

    /**
     * The two structurally different legal-move formulations in the reference must agree on
     * arbitrary boards; disagreement would mean one of them (and so the differential) is unsound.
     */
    @Test
    fun `reference legal-move formulations agree on arbitrary boards`() {
        val rnd = Random(77)
        var nonEmptyChecks = 0
        repeat(1500) {
            val density = 0.15 + rnd.nextDouble() * 0.85
            val cells = IntArray(64) { if (rnd.nextDouble() < density) rnd.nextInt(2) else EMPTY }
            for (p in 0..1) {
                val a = Ref.legalMoves(cells, p)
                assertEquals(a, Ref.legalMovesViaOwnDiscs(cells, p))
                if (a.isNotEmpty()) nonEmptyChecks++
            }
        }
        assertTrue("the agreement check must mostly exercise non-trivial boards", nonEmptyChecks > 1000)
    }

    // ------------------------------------------------------------------ full-game differential

    /**
     * Regression guarded: ANY divergence between the engine's move generation, flipping, scoring,
     * pass/game-over resolution, winner or messaging and an independent Othello implementation over
     * whole games -- e.g. row-wrapping ray walks, single-disc flips, wrong pass rules. The event
     * counters keep the corpus from silently degrading into games that never reach passes or
     * early game-overs.
     */
    @Test
    fun `engine matches the reference at every ply of many full random games`() {
        val e = corpus.events
        assertEquals("both boards of every session are played out", CORPUS_SEEDS * 2, e.boardsOver)
        assertTrue("corpus must include forced passes (got ${e.passPlies})", e.passPlies > 0 && e.gamesWithPass > 0)
        assertTrue("corpus must include boards that end with empty squares (got ${e.endedWithEmpties})", e.endedWithEmpties > 0)
        assertTrue("both players must win some boards (p0=${e.p0Wins}, p1=${e.p1Wins}, draws=${e.draws}, wipeouts=${e.wipeouts})", e.p0Wins > 0 && e.p1Wins > 0)
        assertTrue("plies checked: ${e.plies}", e.plies >= CORPUS_SEEDS * 2 * 50)
    }

    // ------------------------------------------------------------------ per-ply invariants

    /**
     * Regression guarded: flipping bugs that the winner/score check alone could mask -- a mover's
     * count must rise by exactly 1 + flips, the opponent's fall by exactly flips, every ply flips at
     * least one disc, only opponent discs change colour, and nothing else on the board moves.
     */
    @Test
    fun `every ply raises the mover by one plus flips and lowers the opponent by exactly the flips`() {
        for ((n, r) in corpus.records.withIndex()) {
            val opp = 1 - r.mover
            val bc = Ref.counts(r.before)
            val ac = Ref.counts(r.after)
            assertTrue("ply $n flips >= 1", r.flips >= 1)
            assertEquals("ply $n mover count", bc[r.mover] + 1 + r.flips, ac[r.mover])
            assertEquals("ply $n opponent count", bc[opp] - r.flips, ac[opp])
            assertEquals(EMPTY, r.before[r.index])
            assertEquals(r.mover, r.after[r.index])
            var changed = 0
            for (i in 0 until 64) {
                if (i == r.index) continue
                if (r.before[i] != r.after[i]) {
                    changed++
                    assertEquals("ply $n: only opponent discs may change", opp, r.before[i])
                    assertEquals("ply $n: they change to the mover", r.mover, r.after[i])
                }
                if (r.before[i] != EMPTY) assertTrue("ply $n: a disc never disappears", r.after[i] != EMPTY)
            }
            assertEquals("ply $n changed-cell count", r.flips, changed)
        }
    }

    // ------------------------------------------------------------------ mid-game / arbitrary positions

    /**
     * Regression guarded: engine correctness from positions that were NOT produced by its own
     * startMatch. Play k random plies, then overwrite state so the BOT index (1) is to move with a
     * reference-computed legalMoves, and continue to the end through placeDisc.
     */
    @Test
    fun `engine matches the reference when play continues from hand-assigned mid-game positions`() {
        val rnd = Random(4242)
        var continued = 0
        var passes = 0
        repeat(80) { seed ->
            val game = newTwoHumanGame()
            game.startMatch()
            val k = 5 + rnd.nextInt(45)
            var n = 0
            while (n < k && !game.state.value!!.boardOver) {
                val moves = sortedLegal(game)
                checkedPlace(game, moves[rnd.nextInt(moves.size)], "seed $seed warmup", null)
                n++
            }
            if (game.state.value!!.boardOver) return@repeat
            val cells = game.state.value!!.cells.toRef()
            if (Ref.legalMoves(cells, 1).isEmpty()) return@repeat
            game.setPosition(cells, current = 1)
            continued++
            val ev = Events()
            var guard = 0
            while (!game.state.value!!.boardOver) {
                assertLegalMatchesRef(game, "seed $seed continue")
                val moves = sortedLegal(game)
                checkedPlace(game, moves[rnd.nextInt(moves.size)], "seed $seed continue", ev)
                guard++
            }
            passes += ev.passPlies
            assertTrue(guard <= 60)
        }
        assertTrue("most seeds must yield a continuable position, got $continued (passes seen: $passes)", continued > 60)
    }

    /**
     * Regression guarded: turn resolution (normal / pass / game over), winner, wipeouts and the
     * inert-after-over rule on arbitrary, mostly UNREACHABLE dense boards -- the only place random
     * play reaches wipeouts and passes in bulk. Also that any index outside the mover's legal set
     * (in range or not) is a strict no-op that leaves an equal state.
     */
    @Test
    fun `engine matches the reference on arbitrary dense boards including passes wipeouts and game over`() {
        val rnd = Random(9001)
        val game = newTwoHumanGame()
        game.startMatch()
        val ev = Events()
        var checked = 0
        var gateChecks = 0
        var attempts = 0
        while (checked < 2500 && attempts < 20000) {
            attempts++
            val density = 0.1 + rnd.nextDouble() * 0.9
            val cells = IntArray(64) { if (rnd.nextDouble() < density) rnd.nextInt(2) else EMPTY }
            val mover = rnd.nextInt(2)
            val legal = Ref.legalMoves(cells, mover)
            if (legal.isEmpty()) continue
            game.setPosition(cells, mover)
            if (gateChecks < 300) {
                val before = game.state.value!!
                // In-range indices first: an EMPTY cell that flanks nothing must not be placeable, and an
                // occupied cell must not be overwritten. Under a removed legality gate this fails as an
                // ordinary state-changed assertion, not as an exception.
                for (i in 0 until 64) {
                    if (i in legal) continue
                    game.placeDisc(i)
                    assertEquals("in-range illegal index $i must leave the state unchanged", before, game.state.value)
                }
                // Out-of-range indices in their own loop, so a broken gate reports which index misbehaved.
                for (i in listOf(-3, -2, -1, 64, 65, 66)) {
                    val outcome = runCatching { game.placeDisc(i) }
                    assertTrue("out-of-range index $i must be ignored, but placeDisc threw ${outcome.exceptionOrNull()}", outcome.isSuccess)
                    assertEquals("out-of-range index $i must leave the state unchanged", before, game.state.value)
                }
                gateChecks++
            }
            val moves = legal.sorted()
            checkedPlace(game, moves[rnd.nextInt(moves.size)], "dense #$checked", ev)
            checked++
        }
        assertEquals(2500, checked)
        assertTrue("passes must occur on dense boards (got ${ev.passPlies})", ev.passPlies > 10)
        assertTrue("game-overs must occur (got ${ev.boardsOver})", ev.boardsOver > 50)
        assertTrue("wipeouts must occur (got ${ev.wipeouts})", ev.wipeouts > 0)
        assertTrue("draws should occur (got ${ev.draws})", ev.draws > 0)
        assertTrue("both players win some (p0=${ev.p0Wins}, p1=${ev.p1Wins}, wipeouts=${ev.wipeouts}, draws=${ev.draws})", ev.p0Wins > 0 && ev.p1Wins > 0)
    }

    // ------------------------------------------------------------------ perft

    private fun enginePerft(game: ReversiGame, s: ReversiState, depth: Int): Long {
        if (depth == 0 || s.boardOver) return 1
        var total = 0L
        for (m in s.legalMoves.sorted()) {
            game.state.value = s
            game.placeDisc(m)
            total += enginePerft(game, game.state.value!!, depth - 1)
        }
        return total
    }

    /**
     * Regression guarded: move generation / flipping that is only wrong on some rare branch. The
     * published perft numbers (also asserted for the reference) are matched by the ENGINE's own
     * state machine through depth 6.
     */
    @Test
    fun `engine state machine reproduces the published Othello perft numbers`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val root = game.state.value!!
        val expected = listOf(4L, 12L, 56L, 244L, 1396L, 8200L)
        expected.forEachIndexed { i, n -> assertEquals("engine perft(${i + 1})", n, enginePerft(game, root, i + 1)) }
    }

    /**
     * Regression guarded: pass and game-over resolution INSIDE trees. From late-midgame positions
     * (6..10 empties) the engine's placement-tree size at depth 4 must equal the reference's, which
     * counts a pass as free and a game over as a single leaf -- any mismatch in when a turn is
     * skipped or a board ends changes the count.
     */
    @Test
    fun `engine placement trees from late positions match reference perft including passes and game over`() {
        val rnd = Random(31337)
        val game = newTwoHumanGame()
        game.startMatch()
        var compared = 0
        var treesWithPass = 0
        var attempts = 0
        while (compared < 40 && attempts < 400) {
            attempts++
            // random reference game down to 6..10 empties
            var cells = Ref.opening(0)
            var mover = 0
            val target = 6 + rnd.nextInt(5)
            while (Ref.emptiesOf(cells) > target && mover >= 0) {
                val moves = Ref.legalMoves(cells, mover).sorted()
                val m = moves[rnd.nextInt(moves.size)]
                cells = Ref.apply(cells, m, mover)
                mover = Ref.nextMover(cells, mover)
            }
            if (mover < 0) continue
            game.setPosition(cells, mover)
            val engineCount = enginePerft(game, game.state.value!!, 4)
            assertEquals("perft(4) at attempt $attempts", Ref.perft(cells, mover, 4), engineCount)
            if (Ref.treeHasPass(cells, mover)) treesWithPass++
            compared++
        }
        assertEquals(40, compared)
        assertTrue("some compared trees must contain a pass (got $treesWithPass)", treesWithPass > 0)
    }

    // ------------------------------------------------------------------ bot endgame perfection

    private class EndPos(val cells: IntArray, val legal: Set<Int>, val fromGame: Boolean)

    /** Bot (index 1) is always the side to move: positions are colour-mirrored when needed. */
    private fun buildEndgamePositions(): List<EndPos> {
        val out = mutableListOf<EndPos>()
        val rnd = Random(2718)
        // (a) reachable: random reference games, snapshot every ply with 2..5 empties and >= 2 moves
        repeat(320) {
            var cells = Ref.opening(0)
            var mover = 0
            while (mover >= 0) {
                val moves = Ref.legalMoves(cells, mover)
                val e = Ref.emptiesOf(cells)
                if (e in 2..5 && moves.size >= 2) {
                    val c = if (mover == 1) cells else IntArray(64) { i -> if (cells[i] == EMPTY) EMPTY else 1 - cells[i] }
                    out += EndPos(c, Ref.legalMoves(c, 1), true)
                }
                val sorted = moves.sorted()
                val m = sorted[rnd.nextInt(sorted.size)]
                cells = Ref.apply(cells, m, mover)
                mover = Ref.nextMover(cells, mover)
            }
        }
        // (b) unreachable-but-legal: nearly full random boards with 2..5 empties, which pass a lot
        var made = 0
        while (made < 260) {
            val e = 2 + rnd.nextInt(4)
            val cells = IntArray(64) { rnd.nextInt(2) }
            val holes = (0 until 64).shuffled(kotlin.random.Random(rnd.nextLong())).take(e)
            holes.forEach { cells[it] = EMPTY }
            val legal = Ref.legalMoves(cells, 1)
            if (legal.size >= 2) { out += EndPos(cells, legal, false); made++ }
        }
        return out
    }

    /** Value (bot's final disc difference under perfect play) of each legal bot move in [p]. */
    private fun moveValues(p: EndPos): Map<Int, Int> =
        p.legal.associateWith { -Ref.solve(Ref.apply(p.cells, it, 1), 0) }

    /** Runs the bot on [p] and returns the index it played, identified from the resulting cells. */
    private fun botChoice(game: ReversiGame, p: EndPos): Int {
        game.setPosition(p.cells, current = 1, legal = p.legal)
        val before = game.state.value!!
        game.playBotTurn()
        val after = game.state.value!!
        assertTrue("the bot must have moved", before !== after)
        val played = p.legal.filter { Ref.apply(p.cells, it, 1).toList() == after.cells.toRef().toList() }
        assertEquals("the resulting board must equal applying exactly one legal move", 1, played.size)
        return played.single()
    }

    private fun exactPositions(difficulty: CpuDifficulty, maxEmpties: Int, depth: Int): List<EndPos> =
        endgamePositions.filter { p ->
            Ref.emptiesOf(p.cells) <= maxEmpties &&
                p.legal.all { Ref.horizonExact(Ref.apply(p.cells, it, 1), 0, depth - 1) }
        }

    /**
     * Regression guarded: the HARD (depth 6) search must play PERFECTLY in <= 3-empty endgames. Every
     * root move's tree is fully inside the horizon (asserted, not assumed), and terminalScore is
     * strictly monotone in the final disc difference, so the chosen move must attain the solver's
     * optimum. Catches sign errors in the terminal/evaluation perspective, wrong pass handling in
     * the search (the position set includes trees with passes), bad alpha-beta cutoffs, and
     * choosing the worst move.
     */
    @Test
    fun `HARD bot plays the exactly optimal move in endgames of at most 3 empties`() {
        val small = endgamePositions.filter { Ref.emptiesOf(it.cells) <= 3 }
        val exact = exactPositions(CpuDifficulty.HARD, 3, 6)
        assertEquals("all <=3-empty positions are inside HARD's horizon", small.size, exact.size)
        val game = newVsBotGame(CpuDifficulty.HARD)
        game.startMatch()
        var withPass = 0
        var discriminating = 0
        var greedyWrong = 0
        for ((n, p) in small.withIndex()) {
            val values = moveValues(p)
            val best = values.values.max()
            val chosen = botChoice(game, p)
            assertEquals("position #$n (empties=${Ref.emptiesOf(p.cells)}): chosen $chosen value ${values[chosen]} vs best $best; all=$values",
                best, values.getValue(chosen))
            if (Ref.treeHasPass(p.cells, 1)) withPass++
            if (values.values.min() < best) discriminating++
            val gain = p.legal.associateWith { Ref.flips(p.cells, it, 1).size }
            val maxGain = gain.values.max()
            if (p.legal.filter { gain[it] == maxGain }.none { values[it] == best }) greedyWrong++
        }
        assertTrue("positions: ${small.size}", small.size >= 150)
        assertTrue("pass-containing exact trees must be exercised (got $withPass)", withPass > 0)
        assertTrue("positions where a wrong move exists must be common (got $discriminating)", discriminating >= 30)
        assertTrue("positions where greedy flipping is wrong must exist (got $greedyWrong)", greedyWrong > 0)
    }

    /** As above for HARD but on the wider 4..5-empty positions where the horizon check still holds. */
    @Test
    fun `HARD bot is exactly optimal on 4 and 5 empties whenever the search horizon covers the tree`() {
        val exact = exactPositions(CpuDifficulty.HARD, 5, 6).filter { Ref.emptiesOf(it.cells) >= 4 }
        val game = newVsBotGame(CpuDifficulty.HARD)
        game.startMatch()
        for ((n, p) in exact.withIndex()) {
            val values = moveValues(p)
            val chosen = botChoice(game, p)
            assertEquals("wide position #$n all=$values chosen=$chosen", values.values.max(), values.getValue(chosen))
        }
        assertTrue("need a meaningful sample (got ${exact.size})", exact.size >= 100)
    }

    /**
     * Regression guarded: MEDIUM (depth 4) is exact with <= 2 empties, EASY (depth 2) is exact only
     * on the positions whose 2-ply horizon covers the whole tree (a pass would cost the ply the
     * heuristic needs), so the position set is filtered by the reference's horizon check.
     */
    @Test
    fun `MEDIUM is exactly optimal on 2 empties and EASY on the positions its 2-ply horizon fully covers`() {
        val medium = exactPositions(CpuDifficulty.MEDIUM, 2, 4)
        assertEquals("every <=2-empty position is inside MEDIUM's horizon",
            endgamePositions.count { Ref.emptiesOf(it.cells) <= 2 }, medium.size)
        val mGame = newVsBotGame(CpuDifficulty.MEDIUM)
        mGame.startMatch()
        var mDisc = 0
        for ((n, p) in medium.withIndex()) {
            val values = moveValues(p)
            val chosen = botChoice(mGame, p)
            assertEquals("MEDIUM position #$n all=$values chosen=$chosen", values.values.max(), values.getValue(chosen))
            if (values.values.min() < values.values.max()) mDisc++
        }
        assertTrue("MEDIUM sample ${medium.size}", medium.size >= 80)
        assertTrue("MEDIUM discriminating positions: $mDisc", mDisc >= 15)

        val easy = exactPositions(CpuDifficulty.EASY, 3, 2)
        val eGame = newVsBotGame(CpuDifficulty.EASY)
        eGame.startMatch()
        var eDisc = 0
        for ((n, p) in easy.withIndex()) {
            val values = moveValues(p)
            val chosen = botChoice(eGame, p)
            assertEquals("EASY position #$n all=$values chosen=$chosen", values.values.max(), values.getValue(chosen))
            if (values.values.min() < values.values.max()) eDisc++
        }
        assertTrue("EASY sample ${easy.size}", easy.size >= 20)
        assertTrue("EASY discriminating positions: $eDisc", eDisc >= 5)
    }

    /**
     * Regression guarded: difficulty tiers must really search to different depths. On the 3-empty
     * corpus HARD is perfect, and the 2-ply EASY tier must go wrong on at least some positions
     * (it cannot see past two plies); if every tier used the same depth this would stop holding.
     */
    @Test
    fun `EASY errs on some 3-empty endgames that HARD solves exactly, so tiers really differ in depth`() {
        val small = endgamePositions.filter { Ref.emptiesOf(it.cells) == 3 }
        val easy = newVsBotGame(CpuDifficulty.EASY).also { it.startMatch() }
        val hard = newVsBotGame(CpuDifficulty.HARD).also { it.startMatch() }
        var easyWrong = 0
        for (p in small) {
            val values = moveValues(p)
            val best = values.values.max()
            assertEquals(best, values.getValue(botChoice(hard, p)))
            if (values.getValue(botChoice(easy, p)) < best) easyWrong++
        }
        assertTrue("EASY must be suboptimal on some 3-empty positions (got $easyWrong of ${small.size})", easyWrong > 0)
    }

    // ------------------------------------------------------------------ heuristic

    private fun rc(row: Int, col: Int) = row * 8 + col

    /** Parses an 8-line diagram: '.' empty, '0' human (P0), '1' bot (P1). */
    private fun board(vararg rows: String): IntArray {
        require(rows.size == 8 && rows.all { it.length == 8 }) { "diagram must be 8 rows of 8" }
        return IntArray(64) { i ->
            when (rows[i / 8][i % 8]) {
                '0' -> 0
                '1' -> 1
                else -> EMPTY
            }
        }
    }

    /**
     * Human-only "tempo" pairs (bot = P1, human = P0) that keep every search non-terminal without
     * ever giving the bot a legal move at the root:
     *   (7,2)=P0,(6,2)=P1   (7,4)=P0,(6,4)=P1   (4,7)=P0,(4,6)=P1
     * Each is a P0 disc on the board edge with a P1 disc next to it. P0 can flank the P1 disc from
     * the open side ((5,2), (5,4), (4,5)); P1 cannot flank the P0 disc because the far side of it is
     * off the board, and no ray from any empty cell runs "P0 run, then P1" through them (every ray
     * over a P0 edge disc either ends off-board or on an empty cell). So they add human moves only.
     */
    private fun IntArray.withHumanTempos(): IntArray {
        this[rc(7, 2)] = 0; this[rc(6, 2)] = 1
        this[rc(7, 4)] = 0; this[rc(6, 4)] = 1
        this[rc(4, 7)] = 0; this[rc(4, 6)] = 1
        return this
    }

    /**
     * One two-move tactical position (bot = P1 to move, exactly [good] and [risky] legal).
     * [riskySortsFirst] records whether the engine's move-order tie-break (priority desc, index asc)
     * would examine [risky] BEFORE [good]: when true, a bot whose heuristic were flattened to "all
     * moves equal" would play the risky move, so the position cannot be passed by move order alone.
     */
    private class Tactic(val name: String, val cells: IntArray, val good: Int, val risky: Int, val riskySortsFirst: Boolean)

    private fun sortsBefore(a: Int, b: Int): Boolean =
        Ref.orderPriority(a) > Ref.orderPriority(b) || (Ref.orderPriority(a) == Ref.orderPriority(b) && a < b)

    /**
     * Position CORNER_VS_C. Plus the tempo pairs above:
     *   (1,0)=P0,(2,0)=P1   and   (0,5)=P0,(0,4)=P1,   and one extra bot disc (5,6)=P1.
     * Bot legal moves DERIVED by hand: a move needs a run of P0 discs closed by a P1 disc. From
     * (0,0) going south: (1,0)=P0 then (2,0)=P1 -> legal. From (0,6) going west: (0,5)=P0 then
     * (0,4)=P1 -> legal. Every other ray over (1,0) or (0,5) leaves the board or ends on an empty
     * cell (both discs sit on the top/left border, so nothing lies beyond them on the far side), the
     * tempo pairs contribute nothing (see [withHumanTempos]) and (5,6)=P1 anchors nothing (the only P0
     * disc it touches, (4,7), has the board edge behind it), so legalMoves = { (0,0) corner, (0,6)
     * C-square beside corner (0,7) }. Taking the corner is permanent (+120); the C-square is -40
     * while its corner is open and lets P0 attack (0,7).
     * The extra disc (5,6) matters at the HARD tier: without it the human can strip the bot of
     * every disc within six plies after EITHER move, both root values become the same forced loss
     * and only move order decides. With it every root value is an ordinary heuristic score.
     */
    private fun cornerVsCSquare(): Tactic {
        val c = IntArray(64) { EMPTY }
        c[rc(1, 0)] = 0; c[rc(2, 0)] = 1
        c[rc(0, 5)] = 0; c[rc(0, 4)] = 1
        c[rc(5, 6)] = 1
        return Tactic("corner vs C-square", c.withHumanTempos(), rc(0, 0), rc(0, 6), riskySortsFirst = false)
    }

    /**
     * Position CORNER_GIVEAWAY: the bot must NOT hand over a corner. Plus tempo pairs:
     *   (1,6)=P0,(2,6)=P1   (0,5)=P0   and a bot-only safe move (4,1)=P0,(4,0)=P1.
     * Bot legal moves DERIVED by hand: (0,6) going south hits (1,6)=P0 then (2,6)=P1 -> legal;
     * (4,2) going west hits (4,1)=P0 then (4,0)=P1 -> legal. The other P0 discs (0,5), (1,6) have no
     * P1 disc beyond them on any ray starting from an empty cell ((0,5): row neighbours (0,4)/(0,6)
     * are empty, the diagonals leave the board; (1,6): row 1 and both diagonals end on empty cells or
     * off-board), so legalMoves = { (0,6) = C-square, (4,2) = quiet central-ish cell }. The tactic:
     * after the bot plays (0,6) the P0 disc at (0,5) lets P0 answer (0,7): west from (0,7) hits
     * (0,6)=P1 then (0,5)=P0 -> P0 takes the corner. Every tier searches at least 2 plies, so every
     * tier sees that reply and must prefer (4,2).
     */
    private fun avoidGivingCorner(): Tactic {
        val c = IntArray(64) { EMPTY }
        c[rc(1, 6)] = 0; c[rc(2, 6)] = 1
        c[rc(0, 5)] = 0
        c[rc(4, 1)] = 0; c[rc(4, 0)] = 1
        return Tactic("avoid giving a corner", c.withHumanTempos(), rc(4, 2), rc(0, 6), riskySortsFirst = false)
    }

    /**
     * Position EDGE_GIVEAWAY -- the risky move sorts FIRST in the engine's tie-break. Plus tempo pairs:
     *   (0,1)=P1  (0,3)=P0  (1,2)=P0  (2,2)=P1  (2,3)=P1   and the bot-only move (4,1)=P0,(4,0)=P1.
     * Bot legal moves DERIVED by hand: (0,2) going south hits (1,2)=P0 then (2,2)=P1 -> legal;
     * (4,2) going west hits (4,1)=P0 then (4,0)=P1 -> legal. (2,3)=P1 is there only to occupy the
     * cell that would otherwise be a third bot move (from (2,3) north-west: (1,2)=P0 then (0,1)=P1).
     * (0,2) is an EDGE cell (move-order priority 2, ahead of the interior (4,2) with priority 1) and
     * looks fine statically (+10), but it turns row 0 into P1 P1 P0: P0 answers (0,0) flanking
     * east, (0,1)=P1,(0,2)=P1 closed by (0,3)=P0, and owns the corner. A flattened heuristic sees two
     * equal moves and takes the first in order, the risky one.
     */
    private fun edgeGiveaway(): Tactic {
        val c = board(
            ".1.0....",
            "..0.....",
            "..11....",
            "........",
            "10......",
            "........",
            "........",
            "........"
        )
        return Tactic("edge move that hands over a corner", c.withHumanTempos(), rc(4, 2), rc(0, 2), riskySortsFirst = true)
    }

    /**
     * Three further two-move positions found by an offline seeded search over positions of real
     * random games (kept only if the reference finds the good move the strict unique best at EVERY
     * tier, with no forced-terminal values). They are listed as literal diagrams (row 0 on top; '0'
     * human, '1' bot). Each one is decisive for one heuristic term, i.e. a bot with that term zeroed
     * out picks the risky move at every tier: two positions for the mobility term (in both the risky
     * move also sorts first) and one for the open-corner C-square penalty. The legal sets are
     * derived by hand below and the test cross-checks them against the reference and the engine.
     */
    private fun minedTactics(): List<Tactic> = listOf(
        /*
         * Bot (2,4): south run (3,4),(4,4)=P0 closed by (5,4)=P1. Bot (3,2): south-east run (4,3)=P0
         * closed by (5,4)=P1. Nothing else flanks.
         */
        Tactic(
            "mobility decides (risky move sorts first)",
            board(
                "........",
                ".0......",
                "..0.....",
                "...000..",
                "..000...",
                "....1...",
                "....11..",
                "....1..."
            ),
            good = rc(3, 2), risky = rc(2, 4), riskySortsFirst = true
        ),
        /*
         * Bot (4,7): west run (4,6),(4,5)=P0 closed by (4,4)=P1 (an EDGE move, ahead of the interior
         * (5,6) in move order). Bot (5,6): north-west run (4,5)=P0 closed by (3,4)=P1.
         */
        Tactic(
            "mobility decides (risky edge move sorts first)",
            board(
                "........",
                "........",
                "...111..",
                "...11...",
                "...1100.",
                ".....0..",
                "....0.1.",
                "........"
            ),
            good = rc(5, 6), risky = rc(4, 7), riskySortsFirst = true
        ),
        /*
         * Bot (6,0), a C-square beside the still-empty corner (7,0): north-east run (5,1),(4,2)=P0
         * closed by (3,3)=P1. Bot (3,1): south-east run (4,2)=P0 closed by (5,3)=P1. The C-square
         * costs the bot 40 while its corner is open; without that penalty it looks better.
         */
        Tactic(
            "C-square penalty decides",
            board(
                "...1....",
                "...1....",
                "...1....",
                "...11...",
                "00011...",
                ".0.11...",
                "..1.....",
                "........"
            ),
            good = rc(3, 1), risky = rc(6, 0), riskySortsFirst = false
        )
    )

    private fun tactics(): List<Tactic> =
        listOf(cornerVsCSquare(), avoidGivingCorner(), edgeGiveaway()) + minedTactics()

    private val depthOf = mapOf(CpuDifficulty.EASY to 2, CpuDifficulty.MEDIUM to 4, CpuDifficulty.HARD to 6)

    /**
     * Regression guarded: the positional heuristic must actually drive the choice. With exactly two
     * legal moves that differ in positional value, every tier must pick the strong one in every one
     * of the 8 board symmetries (a wrong constant/set for ONE corner, a sign error in evaluate or a
     * negative corner weight breaks at least one). Non-vacuity guards, checked at EVERY tier with the
     * independent reference: (1) neither root move has a forced-terminal value, so a depth-limited
     * heuristic score decides; (2) the strong move is the STRICT unique best of the reference search,
     * so the decision cannot come from the engine's move-order tie-break; and the positions flagged
     * [Tactic.riskySortsFirst] have the weak move ahead of the strong one in that order, so a
     * flattened heuristic (all moves equal) would pick the weak one.
     */
    @Test
    fun `at every difficulty the bot prefers the strong move in the hand-built tactical positions across all 8 symmetries`() {
        for (tac in tactics()) {
            val good = tac.good
            val risky = tac.risky
            assertEquals("${tac.name}: derived legal set", setOf(good, risky), Ref.legalMoves(tac.cells, 1))
            assertEquals("${tac.name}: move-order position of the risky move", tac.riskySortsFirst, sortsBefore(risky, good))
            for (difficulty in CpuDifficulty.entries) {
                val d = depthOf.getValue(difficulty)
                val values = setOf(good, risky).associateWith { Ref.botValue(Ref.apply(tac.cells, it, 1), 0, d - 1, 1) }
                assertTrue("${tac.name}/$difficulty: the decision must come from heuristic scores, not a forced result: $values",
                    values.values.all { abs(it) < 500_000 })
                assertEquals("${tac.name}/$difficulty: the good move must be the STRICT unique reference best (values $values)",
                    setOf(good), Ref.botBestMoves(tac.cells, 1, d))
                for (t in 0 until 8) {
                    val tGood = transformIndex(t, good)
                    val tRisky = transformIndex(t, risky)
                    val game = newVsBotGame(difficulty)
                    game.startMatch()
                    game.setPosition(transformCells(t, tac.cells), current = 1)
                    assertEquals("${tac.name} t=$t legal", setOf(tGood, tRisky), game.state.value!!.legalMoves)
                    game.playBotTurn()
                    val after = game.state.value!!.cells
                    assertEquals("${tac.name} $difficulty t=$t: bot must play $tGood", 1, after[tGood])
                    assertNull("${tac.name} $difficulty t=$t: bot must not play $tRisky", after[tRisky])
                }
            }
        }
    }

    // ------------------------------------------------------------------ reference bot differential

    /** Reachable midgame positions from seeded random reference games, mirrored so the side to
     *  move is player 1 (the bot's index), with at least 2 legal moves. */
    private fun midgamePositions(count: Int, seed: Long, minEmpties: Int, maxEmpties: Int): List<IntArray> {
        val rnd = Random(seed)
        val out = mutableListOf<IntArray>()
        while (out.size < count) {
            var cells = Ref.opening(0)
            var mover = 0
            val target = minEmpties + rnd.nextInt(maxEmpties - minEmpties + 1)
            while (mover >= 0 && Ref.emptiesOf(cells) > target) {
                val moves = Ref.legalMoves(cells, mover).sorted()
                cells = Ref.apply(cells, moves[rnd.nextInt(moves.size)], mover)
                mover = Ref.nextMover(cells, mover)
            }
            if (mover < 0 || Ref.legalMoves(cells, mover).size < 2) continue
            out += if (mover == 1) cells else IntArray(64) { if (cells[it] == EMPTY) EMPTY else 1 - cells[it] }
        }
        return out
    }

    /** Unreachable near-full boards (8..11 empties, random colours) that are dense in passes. */
    private fun passRichPositions(): List<IntArray> {
        val rnd = Random(909)
        val positions = mutableListOf<IntArray>()
        while (positions.size < 40) {
            val empties = 8 + rnd.nextInt(4)
            val cells = IntArray(64) { rnd.nextInt(2) }
            (0 until 64).shuffled(kotlin.random.Random(rnd.nextLong())).take(empties).forEach { cells[it] = EMPTY }
            if (Ref.legalMoves(cells, 1).size >= 2) positions += cells
        }
        return positions
    }

    /** A position plus the reference search's full argmax set (computed once, shared by the tests below). */
    private class RefCase(val cells: IntArray, val best: Set<Int>)

    private fun refCases(positions: List<IntArray>, plies: Int): List<RefCase> =
        positions.map { RefCase(it, Ref.botBestMoves(it, 1, plies)) }

    private fun assertBotChoosesArgmax(difficulty: CpuDifficulty, cases: List<RefCase>): Int {
        val game = newVsBotGame(difficulty)
        game.startMatch()
        var decisive = 0
        for ((n, c) in cases.withIndex()) {
            val chosen = botChoice(game, EndPos(c.cells, Ref.legalMoves(c.cells, 1), true))
            assertTrue("$difficulty position #$n: bot chose $chosen but the reference search's best set is ${c.best}", chosen in c.best)
            if (c.best.size == 1) decisive++
        }
        return decisive
    }

    /**
     * Regression guarded: the bot's numbers, not just its endgame skill. An independent
     * re-derivation of the DOCUMENTED evaluation (corner 120, X -20, open/taken C -40/+10, edge
     * 10/6, ring 1, core 3, mobility x12, +-1,000,000 disc-diff terminals, a pass costing a ply)
     * with plain unpruned minimax must accept the engine's pick as one of its exact argmax moves on
     * midgame positions. Catches a wrong entry in any weight table (one corner, one X-square, C-square
     * conditions, every +1 nudge of a small constant), reversed mobility, wrong tier depth, and
     * alpha-beta that changes the result. This check is deliberately robust to move ordering; the
     * ordering itself is pinned separately by the tie-break test.
     */
    @Test
    fun `EASY and MEDIUM bots always choose a move from an independent reference search's argmax set on midgame positions`() {
        val easyDecisive = assertBotChoosesArgmax(CpuDifficulty.EASY, easyCases)
        assertTrue("most EASY reference decisions must be unique argmaxes (got $easyDecisive of ${easyCases.size})",
            easyDecisive * 10 >= easyCases.size * 6)
        val mediumDecisive = assertBotChoosesArgmax(CpuDifficulty.MEDIUM, mediumCases)
        assertTrue("most MEDIUM reference decisions must be unique argmaxes (got $mediumDecisive of ${mediumCases.size})",
            mediumDecisive * 2 >= mediumCases.size)
    }

    /** As above for the deepest tier (6 plies), on late positions (8..12 empties) where an unpruned
     *  reference search is affordable and passes/game-overs enter the tree. */
    @Test
    fun `HARD bot chooses a move from an independent 6-ply reference search's argmax set on late positions`() {
        assertBotChoosesArgmax(CpuDifficulty.HARD, hardLateCases)
    }

    /**
     * Regression guarded: the search's PASS semantics (a pass never places a disc; game over needs
     * BOTH sides stuck). Unreachable near-full boards (8..11 empties) are dense in passes, so the
     * 6-ply reference tree meets them constantly. Asserted non-vacuous: many of these positions
     * really contain a pass within the horizon.
     */
    @Test
    fun `HARD bot chooses from the 6-ply reference argmax set on pass-rich unreachable near-full boards`() {
        val withPass = hardPassCases.count { c ->
            Ref.legalMoves(c.cells, 1).any { Ref.passWithin(Ref.apply(c.cells, it, 1), 0, 5) }
        }
        assertTrue("pass-rich corpus must really contain passes within the horizon (got $withPass of ${hardPassCases.size})",
            withPass >= 15)
        assertBotChoosesArgmax(CpuDifficulty.HARD, hardPassCases)
    }

    /**
     * TIE-BREAK PIN (a characterization of a documented but arbitrary rule, not a correctness
     * property): when several root moves share the best value the engine plays the FIRST of them in
     * (move-order priority descending, index ascending) order -- corners, then other edge cells,
     * then interior, then X-/C-squares. If that ordering is deliberately changed this test (and only
     * this one) should be updated; the argmax-set tests above stay valid.
     */
    @Test
    fun `tie-break pin - among equally valued best moves the bot plays the first in corner-first ascending-index order`() {
        val plan = listOf(
            CpuDifficulty.EASY to easyCases,
            CpuDifficulty.MEDIUM to mediumCases,
            CpuDifficulty.HARD to hardLateCases,
            CpuDifficulty.HARD to hardPassCases
        )
        var ties = 0
        for ((difficulty, cases) in plan) {
            val game = newVsBotGame(difficulty)
            game.startMatch()
            for ((n, c) in cases.withIndex()) {
                val chosen = botChoice(game, EndPos(c.cells, Ref.legalMoves(c.cells, 1), true))
                val firstBest = c.best.sortedWith(compareByDescending<Int> { Ref.orderPriority(it) }.thenBy { it }).first()
                assertEquals("$difficulty position #$n: tie-break among ${c.best}", firstBest, chosen)
                if (c.best.size > 1) ties++
            }
        }
        assertTrue("the corpora must contain real ties for this pin to bite (got $ties)", ties >= 3)
    }

    /**
     * Regression guarded: the documented rule that a forced PASS costs one ply of search depth. The
     * positions are mined from seeded unreachable boards where the human has only 2..6 discs (so a
     * bot move that leaves the human without a reply is common); we keep those where the 2-ply
     * reference search that charges a ply per pass and the one that passes for free choose entirely
     * DISJOINT argmax sets, so an engine that stops charging the ply provably picks a different move.
     * It is a characterization of the engine's own documented simplification, not of Othello.
     */
    @Test
    fun `EASY bot charges a search ply for a pass, on positions where a free pass would change the choice`() {
        val rnd = Random(4711)
        val kept = mutableListOf<RefCase>()
        var attempts = 0
        while (kept.size < 6 && attempts < 20_000) {
            attempts++
            val empties = 8 + rnd.nextInt(33)
            val humanDiscs = 2 + rnd.nextInt(5)
            val order = (0 until 64).shuffled(kotlin.random.Random(rnd.nextLong())).take(64 - empties)
            val cells = IntArray(64) { EMPTY }
            order.forEachIndexed { k, index -> cells[index] = if (k < humanDiscs) 0 else 1 }
            val legal = Ref.legalMoves(cells, 1)
            if (legal.size < 2) continue
            // cheap pre-screen: the pass rule can only matter if some bot move leaves the human stuck
            if (legal.none { Ref.legalMoves(Ref.apply(cells, it, 1), 0).isEmpty() }) continue
            val charged = Ref.botBestMoves(cells, 1, 2)
            val free = Ref.botBestMoves(cells, 1, 2, passCostsPly = false)
            if (charged.intersect(free).isEmpty()) kept += RefCase(cells, charged)
        }
        assertTrue("need at least 4 pass-rule-decisive positions (got ${kept.size} after $attempts attempts)", kept.size >= 4)
        assertBotChoosesArgmax(CpuDifficulty.EASY, kept)
    }

    /** The move the engine's documented tie-break plays among [set]: highest move-order priority, then lowest index. */
    private fun tieBreakFirst(set: Set<Int>): Int =
        set.sortedWith(compareByDescending<Int> { Ref.orderPriority(it) }.thenBy { it }).first()

    /**
     * Regression guarded: the CORNER weight to the exact point. A +-1 nudge only changes the played move
     * when the top two root values are within a point of each other, which random positions rarely
     * produce (about 1 in 150 even among positions where a corner is reachable), so the corpus is mined:
     * seeded random midgame positions (bot to move) are scanned at EASY depth and kept only if a
     * reference search with the corner weight nudged to 119 (resp. 121) would play a move that is NOT in
     * the documented weight's argmax set. On those positions an engine whose corner weight is not
     * exactly the documented one plays a provably wrong move, whatever the move order. Characterization
     * test: update the constant here together with the engine's CORNER_WEIGHT.
     */
    @Test
    fun `EASY bot pins the corner weight exactly on mined positions where a one point nudge changes the choice`() {
        val depth = 2
        val corners = setOf(0, 7, 56, 63)
        val wanted = 2
        val found = mutableMapOf(-1 to mutableListOf<RefCase>(), 1 to mutableListOf<RefCase>())
        var scanned = 0
        val rnd = Random(120_121)
        while (scanned < 8000 && found.values.any { it.size < wanted }) {
            val cells = midgamePositions(1, rnd.nextLong(), 4, 44).single()
            scanned++
            val legal = Ref.legalMoves(cells, 1)
            // a corner can only change a search value if it is takeable within the two plies
            val cornerReachable = legal.any { it in corners } ||
                legal.any { m -> Ref.legalMoves(Ref.apply(cells, m, 1), 0).any { it in corners } }
            if (!cornerReachable) continue
            val base = Ref.botBestMoves(cells, 1, depth)
            for ((nudge, list) in found) {
                if (list.size >= wanted) continue
                val nudged = Ref.botBestMoves(cells, 1, depth, cornerWeight = 120 + nudge)
                if (tieBreakFirst(nudged) !in base) list += RefCase(cells, base)
            }
        }
        assertTrue("need $wanted positions decisive for each of a -1 and a +1 nudge (got ${found.mapValues { it.value.size }} after $scanned scanned)",
            found.values.all { it.size >= wanted })
        assertBotChoosesArgmax(CpuDifficulty.EASY, found.values.flatten())
    }

    // ------------------------------------------------------------------ determinism, purity, legality

    /**
     * Regression guarded: the bot must be a pure function of (position, difficulty) -- a repeated
     * call on the same instance and a call on a fresh instance give the same move, with no hidden
     * state. The executed-iteration count is asserted so skipped positions cannot make it vacuous.
     */
    @Test
    fun `bot move is deterministic across repeated calls and fresh instances`() {
        val rnd = Random(555)
        for (difficulty in CpuDifficulty.entries) {
            var executed = 0
            repeat(12) {
                // a random midgame position from the reference, bot to move
                var cells = Ref.opening(0)
                var mover = 0
                repeat(6 + rnd.nextInt(24)) {
                    if (mover < 0) return@repeat
                    val m = Ref.legalMoves(cells, mover).sorted().let { s -> s[rnd.nextInt(s.size)] }
                    cells = Ref.apply(cells, m, mover)
                    mover = Ref.nextMover(cells, mover)
                }
                if (mover < 0) return@repeat
                val a = newVsBotGame(difficulty).also { it.startMatch() }
                a.setPosition(cells, current = 1, legal = Ref.legalMoves(cells, 1).ifEmpty { return@repeat })
                val snapshot = a.state.value!!
                val results = mutableListOf<List<Int?>>()
                a.playBotTurn()
                results += a.state.value!!.cells
                a.state.value = snapshot // same instance, same position, one more call
                a.playBotTurn()
                results += a.state.value!!.cells
                val b = newVsBotGame(difficulty).also { it.startMatch() } // fresh instance
                b.state.value = snapshot
                b.playBotTurn()
                results += b.state.value!!.cells
                assertEquals("difficulty $difficulty", 1, results.toSet().size)
                executed++
            }
            assertTrue("$difficulty: only $executed of 12 determinism iterations actually ran", executed >= 8)
        }
    }

    /**
     * Regression guarded: playBotTurn's documented "safe to call speculatively" contract -- it must
     * leave the exact same state object when it is a human's turn, when the board is over, when the
     * session has ended, and when no match has started.
     *
     * Only the "no match" and "human's turn" branches are individually guarded here. The board-over
     * and session-over branches are masked defence-in-depth: an over board carries an empty
     * legalMoves set, so chooseBotMove returns null anyway, and placeDisc re-checks matchOver itself.
     * This test therefore pins the observable no-op contract for those two cases, not the presence
     * of playBotTurn's own boardOver/matchOver guards.
     */
    @Test
    fun `playBotTurn does nothing when it is not the bots turn or the board or session is over`() {
        for (difficulty in CpuDifficulty.entries) {
            val game = newVsBotGame(difficulty)
            game.playBotTurn() // no match started: must not crash
            assertNull(game.state.value)

            game.startMatch() // human (index 0) to move
            val humanTurn = game.state.value!!
            game.playBotTurn()
            assertSame(humanTurn, game.state.value)

            // bot to move but the board is over
            val cells = IntArray(64) { EMPTY }
            cells[0] = 1; cells[1] = 1
            game.setPosition(cells, current = 1)
            val over = game.state.value!!.copy(boardOver = true, legalMoves = emptySet())
            game.state.value = over
            game.playBotTurn()
            assertSame(over, game.state.value)

            // bot to move on a live board, but the session has ended
            game.startMatch() // second board of the session: the bot (index 1) opens
            assertEquals(1, game.state.value!!.currentPlayerIndex)
            val live = game.state.value!!
            game.leaveSession()
            assertTrue(game.matchOver.value)
            game.playBotTurn()
            assertSame(live, game.state.value)
        }
    }

    /**
     * Regression guarded: from the real opening (bot to move, obtained via the second board so the
     * bot opens) every tier plays a legal opening move that the reference confirms, flipping exactly
     * one disc, and the resulting state is a well-formed hand-over to the human.
     */
    @Test
    fun `a bot move from the opening is legal at every difficulty`() {
        for (difficulty in CpuDifficulty.entries) {
            val game = newVsBotGame(difficulty)
            game.startMatch()
            game.startMatch()
            val s = game.state.value!!
            assertEquals(1, s.currentPlayerIndex)
            val open = s.cells.toRef()
            assertEquals(Ref.legalMoves(open, 1), s.legalMoves)
            game.playBotTurn()
            val ns = game.state.value!!
            val candidates = s.legalMoves.filter { Ref.apply(open, it, 1).toList() == ns.cells.toRef().toList() }
            assertEquals("$difficulty: exactly one legal opening move explains the new board", 1, candidates.size)
            assertEquals(0, ns.currentPlayerIndex)
            assertEquals(listOf(1, 4), ns.scores)
            assertEquals(Ref.legalMoves(ns.cells.toRef(), 0), ns.legalMoves)
        }
    }

    // ------------------------------------------------------------------ bot quality in real games

    private fun botVsRandom(difficulty: CpuDifficulty, seed: Int): Int {
        val rnd = Random(seed.toLong() * 7919 + difficulty.ordinal)
        val game = newVsBotGame(difficulty)
        game.startMatch()
        if (seed % 2 == 1) game.startMatch() // bot opens on odd seeds
        var guard = 0
        while (!game.state.value!!.boardOver) {
            check(guard++ < 200) { "runaway game" }
            val s = game.state.value!!
            if (s.currentPlayerIndex == 1) {
                game.playBotTurn()
                val ns = game.state.value!!
                assertTrue("bot must change the state", ns !== s)
                val cells = s.cells.toRef()
                assertTrue("bot played a reference-legal move",
                    Ref.legalMoves(cells, 1).any { Ref.apply(cells, it, 1).toList() == ns.cells.toRef().toList() })
            } else {
                val moves = s.legalMoves.sorted()
                game.placeDisc(moves[rnd.nextInt(moves.size)])
            }
        }
        val sc = game.state.value!!.scores
        return sc[1] - sc[0]
    }

    /**
     * Regression guarded: overall bot strength and legality over real, pass-including games. A
     * flipped evaluation sign, a wrong corner weight or a worst-move pick makes the bot lose to a
     * uniformly random opponent; every bot ply is also confirmed reference-legal. The win counts are
     * the load-bearing assertion; the disc margin is only required to be positive so a legitimate
     * evaluation retune does not shift the trajectories past a calibrated threshold.
     */
    @Test
    fun `bots at all difficulties beat a random player overwhelmingly and always play legal moves`() {
        val plan = listOf(CpuDifficulty.EASY to 16, CpuDifficulty.MEDIUM to 10, CpuDifficulty.HARD to 4)
        for ((difficulty, games) in plan) {
            var wins = 0
            var margin = 0
            for (seed in 0 until games) {
                val diff = botVsRandom(difficulty, seed)
                if (diff > 0) wins++
                margin += diff
            }
            val need = if (difficulty == CpuDifficulty.HARD) games else (games * 0.85).toInt()
            assertTrue("$difficulty won only $wins of $games vs random (margin $margin)", wins >= need)
            assertTrue("$difficulty total disc margin must be positive ($margin over $games games, $wins wins)", margin > 0)
        }
    }

    // ------------------------------------------------------------------ hand-built scenarios

    /**
     * Regression guarded: a complete wipeout (opponent left with zero discs) is a game over with the
     * mover as winner -- not a pass -- and updates the session tally; a tie leaves winner null,
     * bumps sessionDraws and uses the tie message.
     */
    @Test
    fun `a wipeout ends the board with a win and a tie is recorded as a draw`() {
        val game = newTwoHumanGame()
        game.startMatch()
        // Wipeout: P1 plays (0,2) flipping (0,1) anchored by its own (0,0); P0 is left with nothing.
        val wipe = IntArray(64) { EMPTY }
        wipe[0] = 1; wipe[1] = 0
        game.setPosition(wipe, current = 1)
        game.placeDisc(2)
        val s = game.state.value!!
        assertTrue(s.boardOver)
        assertFalse(s.justPassed)
        assertEquals("p2", s.winnerPlayerId)
        assertEquals(listOf(0, 3), s.scores)
        assertEquals("Player 2 wins 0-3!", s.lastAction)
        assertEquals(mapOf("p1" to 0, "p2" to 1), game.sessionWins.value)
        assertEquals(0, game.sessionDraws.value)

        // Tie: P0 plays (0,2) flipping (0,1) against (0,0); P1's three discs on row 7 are unreachable.
        val tie = IntArray(64) { EMPTY }
        tie[0] = 0; tie[1] = 1; tie[60] = 1; tie[61] = 1; tie[62] = 1
        game.setPosition(tie, current = 0)
        game.placeDisc(2)
        val t = game.state.value!!
        assertTrue(t.boardOver)
        assertNull(t.winnerPlayerId)
        assertEquals(listOf(3, 3), t.scores)
        assertEquals("It's a tie, 3-3!", t.lastAction)
        assertEquals(1, game.sessionDraws.value)
        assertEquals(mapOf("p1" to 0, "p2" to 1), game.sessionWins.value)
    }

    /**
     * Regression guarded: a pass chain. P0 plays (0,2) (flipping (0,1)); P1's only disc (3,6) cannot
     * produce a move so P1 passes and P0 moves again ((3,5) flips (3,6) against (3,7)), which then
     * wipes P1 out and ends the board. justPassed must be set on the pass ply and cleared after.
     */
    @Test
    fun `a pass hands the turn straight back and justPassed is cleared by the next move`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val c = IntArray(64) { EMPTY }
        c[0] = 0; c[1] = 1; c[3 * 8 + 6] = 1; c[3 * 8 + 7] = 0
        game.setPosition(c, current = 0)
        assertEquals(setOf(2, 3 * 8 + 5), game.state.value!!.legalMoves)
        game.placeDisc(2)
        val p = game.state.value!!
        assertTrue(p.justPassed)
        assertEquals(0, p.currentPlayerIndex)
        assertEquals(setOf(3 * 8 + 5), p.legalMoves)
        assertEquals("Player 2 has no legal move and passes", p.lastAction)
        game.placeDisc(3 * 8 + 5)
        val f = game.state.value!!
        assertFalse(f.justPassed)
        assertTrue(f.boardOver)
        assertEquals("p1", f.winnerPlayerId)
        assertEquals(listOf(6, 0), f.scores)
    }
}
