package com.gamesuite.games.chess

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Migrated from app/src/test/java/com/gamesuite/games/chess/ChessGameTest.kt for
 * docs/ENGINE_DECISION.md Action Item 4's follow-up pilot port -- the regression-safety
 * half of the claim, run against both the android and desktop targets from this one shared
 * source file, same as TicTacToeGameTest.kt/AirHockeyGameTest.kt before it.
 *
 * The only changes from the original: org.junit.Test/Assert.* (JVM-only) became
 * kotlin.test.Test/assertEquals/assertTrue/assertFalse/assertNull (real Kotlin Multiplatform
 * artifacts) -- and every kotlin.test assertion takes its message LAST (actual, message), the
 * reverse of JUnit's message-first overloads, the same parameter-order swap already called out
 * migrating the two earlier pilots' tests. assertNotNull was imported but never actually called
 * anywhere in the original file, so it's dropped here rather than carried over unused. Backtick
 * test-function names are a Kotlin language feature, not a JUnit one, so every `` `like this` ``
 * name below is unchanged.
 *
 * Pure JUnit coverage (no Robolectric/Compose test rule), same shape as
 * MancalaGameTest. `state` is public `mutableStateOf<ChessState?>`, so
 * fixtures that need a specific position (pin, castling-rights edge case, en
 * passant, stalemate) seed it directly -- the same seam MancalaGameTest uses
 * to seed MancalaState -- rather than needing a separate test-only
 * "setupEmpty/setPiece/..." API on the production class the way the C++
 * engine has one. Every bot-move assertion reads [ChessState.lastFrom]/
 * [ChessState.lastTo] directly (both plain Ints) instead of Mancala's
 * fragile lastAction-string regex parsing.
 */
class ChessGameTest {

    private fun newGame(whiteIsBot: Boolean = false, blackIsBot: Boolean = false, difficulty: CpuDifficulty = CpuDifficulty.HARD): ChessGame {
        val game = ChessGame()
        val players = listOf(
            PlayerInfo(playerId = "white", displayName = "White", isBot = whiteIsBot),
            PlayerInfo(playerId = "black", displayName = "Black", isBot = blackIsBot)
        )
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = players,
                localPlayerIndex = if (whiteIsBot) 1 else 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = difficulty
        return game
    }

    private fun sq(file: Char, rank: Int): Int = (rank - 1) * 8 + (file - 'a')

    private fun emptyBoard(): MutableList<Piece?> = MutableList(64) { null }

    /**
     * Plays [plies] uniformly-random legal moves from the current position. HARD's own move
     * selection is fully deterministic (see [ChessGame]'s minimaxBestMove -- a faithful port
     * of the C++ engine's own first-strictly-better-score tie-breaking, with no randomness),
     * so two HARD-vs-HARD games from the same starting position would otherwise be the exact
     * same game replayed -- this diversifies each self-play game's starting point so the
     * legality checks below actually exercise different positions, not one position 8 times.
     */
    private fun playRandomOpening(game: ChessGame, plies: Int, random: Random) {
        repeat(plies) {
            val s = game.state.value ?: return
            if (s.roundOver) return
            val legal = game.allLegalMoves()
            if (legal.isEmpty()) return
            val (from, to) = legal[random.nextInt(legal.size)]
            game.playMove(playerIndexForColor(s.sideToMove), from, to)
        }
    }

    // ---- Move generation correctness: perft ------------------------------

    /**
     * Re-runs the exact verification the ESP32 C++ engine's own native_test used: from the
     * standard starting position, perft(1)/perft(2)/perft(3) must equal the known-correct
     * values 20/400/8902. A mismatch here means this Kotlin port's move generation (pins,
     * castling, en passant, promotion, or basic piece movement) diverged from the original
     * during the port -- the single strongest correctness signal for a chess move generator.
     */
    @Test
    fun `perft from the starting position matches known-correct values`() {
        val game = newGame()
        game.startMatch()

        assertEquals(20L, game.perft(1))
        assertEquals(400L, game.perft(2))
        assertEquals(8902L, game.perft(3))
    }

    // ---- Legal move acceptance / rejection --------------------------------

    @Test
    fun `playMove accepts a legal opening pawn push and rejects an illegal one`() {
        val game = newGame()
        game.startMatch()
        val before = game.state.value!!

        // Illegal: e2 to e5 is not a legal first move for a pawn (too far).
        game.playMove(0, sq('e', 2), sq('e', 5))
        assertEquals(before, game.state.value, "illegal move must leave state completely unchanged")

        // Legal: e2-e4.
        game.playMove(0, sq('e', 2), sq('e', 4))
        val after = game.state.value!!
        assertEquals(sq('e', 2), after.lastFrom)
        assertEquals(sq('e', 4), after.lastTo)
        assertEquals(PieceColor.BLACK, after.sideToMove)
    }

    @Test
    fun `playMove rejects a move made with the wrong player index`() {
        val game = newGame()
        game.startMatch()
        val before = game.state.value!!

        // It's White's move, but this is submitted as player 1 (Black).
        game.playMove(1, sq('e', 2), sq('e', 4))
        assertEquals(before, game.state.value)
    }

    @Test
    fun `playMove rejects moving into a pin that would expose the king`() {
        val game = newGame()
        val board = emptyBoard()
        board[sq('e', 1)] = Piece(PieceType.KING, PieceColor.WHITE)
        board[sq('e', 4)] = Piece(PieceType.ROOK, PieceColor.WHITE)
        board[sq('e', 8)] = Piece(PieceType.ROOK, PieceColor.BLACK)
        board[sq('a', 8)] = Piece(PieceType.KING, PieceColor.BLACK)
        game.state.value = ChessState(
            board = board,
            sideToMove = PieceColor.WHITE,
            castleWK = false, castleWQ = false, castleBK = false, castleBQ = false,
            epSquare = null,
            lastAction = "fixture"
        )
        val before = game.state.value!!

        // Illegal: stepping the pinned rook off the e-file would expose the White king to
        // the Black rook on e8.
        game.playMove(0, sq('e', 4), sq('d', 4))
        assertEquals(before, game.state.value)

        // Legal: sliding along the pin line (still blocks the check) is fine.
        game.playMove(0, sq('e', 4), sq('e', 5))
        assertEquals(sq('e', 5), game.state.value!!.lastTo)
    }

    @Test
    fun `legalDestinationsFor a pinned piece stays on the pin line`() {
        val game = newGame()
        val board = emptyBoard()
        board[sq('e', 1)] = Piece(PieceType.KING, PieceColor.WHITE)
        board[sq('e', 4)] = Piece(PieceType.ROOK, PieceColor.WHITE)
        board[sq('e', 8)] = Piece(PieceType.ROOK, PieceColor.BLACK)
        board[sq('a', 8)] = Piece(PieceType.KING, PieceColor.BLACK)
        game.state.value = ChessState(
            board = board,
            sideToMove = PieceColor.WHITE,
            castleWK = false, castleWQ = false, castleBK = false, castleBQ = false,
            epSquare = null,
            lastAction = "fixture"
        )

        val dests = game.legalDestinationsFor(sq('e', 4))
        assertTrue(dests.isNotEmpty(), "pinned rook must have some legal moves (along the pin line)")
        assertTrue(dests.all { it % 8 == sq('e', 1) % 8 }, "every legal destination must stay on the e-file")
        assertFalse(sq('d', 4) in dests)
        assertFalse(sq('f', 4) in dests)
    }

    // ---- Castling ----------------------------------------------------------

    @Test
    fun `castling is unavailable when the rights flag is set but the rook is not actually there`() {
        val game = newGame()
        val board = emptyBoard()
        board[sq('e', 1)] = Piece(PieceType.KING, PieceColor.WHITE)
        board[sq('e', 8)] = Piece(PieceType.KING, PieceColor.BLACK)
        // No rook on h1 at all, but the rights flag is (incorrectly) still true --
        // generatePseudoMoves must still refuse to offer kingside castling.
        game.state.value = ChessState(
            board = board,
            sideToMove = PieceColor.WHITE,
            castleWK = true, castleWQ = false, castleBK = false, castleBQ = false,
            epSquare = null,
            lastAction = "fixture"
        )

        val dests = game.legalDestinationsFor(sq('e', 1))
        assertFalse(sq('g', 1) in dests, "king must not be able to castle without its rook actually present")
    }

    @Test
    fun `castling rights are lost the moment the rook is captured, not just when it moves`() {
        val game = newGame()
        val board = emptyBoard()
        board[sq('e', 1)] = Piece(PieceType.KING, PieceColor.WHITE)
        board[sq('h', 1)] = Piece(PieceType.ROOK, PieceColor.WHITE)
        board[sq('h', 5)] = Piece(PieceType.ROOK, PieceColor.BLACK)
        board[sq('a', 8)] = Piece(PieceType.KING, PieceColor.BLACK)
        game.state.value = ChessState(
            board = board,
            sideToMove = PieceColor.BLACK,
            castleWK = true, castleWQ = false, castleBK = false, castleBQ = false,
            epSquare = null,
            lastAction = "fixture"
        )

        // Black captures the White rook on h1 outright.
        game.playMove(1, sq('h', 5), sq('h', 1))
        val after = game.state.value!!
        assertEquals(sq('h', 1), after.lastTo)
        assertFalse(after.castleWK, "White's kingside castling right must be gone once its rook is captured")
    }

    // ---- En passant ----------------------------------------------------------

    @Test
    fun `en passant capture is legal and removes the captured pawn`() {
        val game = newGame()
        val board = emptyBoard()
        board[sq('e', 1)] = Piece(PieceType.KING, PieceColor.WHITE)
        board[sq('e', 8)] = Piece(PieceType.KING, PieceColor.BLACK)
        board[sq('e', 5)] = Piece(PieceType.PAWN, PieceColor.WHITE)
        board[sq('d', 5)] = Piece(PieceType.PAWN, PieceColor.BLACK) // just double-pushed d7-d5
        game.state.value = ChessState(
            board = board,
            sideToMove = PieceColor.WHITE,
            castleWK = false, castleWQ = false, castleBK = false, castleBQ = false,
            epSquare = sq('d', 6),
            lastAction = "fixture"
        )

        game.playMove(0, sq('e', 5), sq('d', 6))
        val after = game.state.value!!
        assertEquals(sq('d', 6), after.lastTo)
        assertEquals(Piece(PieceType.PAWN, PieceColor.WHITE), after.board[sq('d', 6)])
        assertNull(after.board[sq('d', 5)], "the captured black pawn's original square must now be empty")
        assertNull(after.board[sq('e', 5)])
    }

    // ---- Checkmate / stalemate ----------------------------------------------

    /** Fool's Mate, played entirely through the public playMove() API from the standard
     *  starting position -- exercises the full pipeline (generation, application, and
     *  checkmate detection) end to end, not just a hand-seeded position. */
    @Test
    fun `checkmate via Fool's Mate is detected and ends the game`() {
        val game = newGame()
        game.startMatch()

        game.playMove(0, sq('f', 2), sq('f', 3)) // 1. f3
        game.playMove(1, sq('e', 7), sq('e', 5)) // 1... e5
        game.playMove(0, sq('g', 2), sq('g', 4)) // 2. g4
        game.playMove(1, sq('d', 8), sq('h', 4)) // 2... Qh4#

        val s = game.state.value!!
        assertTrue(s.roundOver)
        assertEquals(ChessResult.BLACK_WINS, s.result)
        assertEquals("black", s.winnerPlayerId)
        assertEquals(1, game.scoreP2.value)
        assertEquals(0, game.scoreP1.value)

        // The game is over -- any further move attempt must be a no-op.
        val frozen = s
        game.playMove(0, sq('e', 1), sq('e', 2))
        assertEquals(frozen, game.state.value)
    }

    @Test
    fun `stalemate is recognized with zero legal moves and no check`() {
        val game = newGame()
        val board = emptyBoard()
        board[sq('a', 1)] = Piece(PieceType.KING, PieceColor.WHITE)
        board[sq('c', 2)] = Piece(PieceType.KING, PieceColor.BLACK)
        board[sq('b', 3)] = Piece(PieceType.QUEEN, PieceColor.BLACK)
        game.state.value = ChessState(
            board = board,
            sideToMove = PieceColor.WHITE,
            castleWK = false, castleWQ = false, castleBK = false, castleBQ = false,
            epSquare = null,
            lastAction = "fixture"
        )

        assertEquals(ChessResult.DRAW_STALEMATE, game.currentResult())
        assertTrue(game.allLegalMoves().isEmpty())
    }

    // ---- Threefold repetition -------------------------------------------------

    /**
     * Real, previously-possible-forever-repeating-game bug fix. Two bare kings shuffle back
     * and forth (White a1<->a2, Black h8<->h7) with nothing else on the board to ever force a
     * different move -- every 4-ply cycle returns to the exact same position with White to
     * move again. Confirms the game is still IN_PROGRESS after two full round trips (this
     * mechanism only counts positions actually reached via a real move, so a hand-seeded
     * fixture's own starting position is never itself recorded -- see
     * recordPositionAndCheckRepetition's KDoc -- meaning the 3rd real return here is this
     * signature's 3rd recorded occurrence), then DRAW_REPETITION exactly on the third return,
     * with the draw counted in [ChessGame.draws].
     */
    @Test
    fun `three-fold repetition of the same position is declared a draw`() {
        val game = newGame()
        val board = emptyBoard()
        board[sq('a', 1)] = Piece(PieceType.KING, PieceColor.WHITE)
        board[sq('h', 8)] = Piece(PieceType.KING, PieceColor.BLACK)
        game.state.value = ChessState(
            board = board,
            sideToMove = PieceColor.WHITE,
            castleWK = false, castleWQ = false, castleBK = false, castleBQ = false,
            epSquare = null,
            lastAction = "fixture"
        )

        fun shuffleOnce() {
            game.playMove(0, sq('a', 1), sq('a', 2))
            game.playMove(1, sq('h', 8), sq('h', 7))
            game.playMove(0, sq('a', 2), sq('a', 1))
            game.playMove(1, sq('h', 7), sq('h', 8))
        }

        shuffleOnce() // 1st return to the starting position
        shuffleOnce() // 2nd return -- still just a repeat, not yet a THREEfold repetition
        assertEquals(ChessResult.IN_PROGRESS, game.state.value!!.result)
        assertFalse(game.state.value!!.roundOver)

        shuffleOnce() // 3rd return -- now a real threefold repetition

        val s = game.state.value!!
        assertTrue(s.roundOver)
        assertEquals(ChessResult.DRAW_REPETITION, s.result)
        assertEquals(1, game.draws.value)
    }

    // ---- HARD bot never plays an illegal move -------------------------------

    /**
     * Plays many full self-play games with the HARD bot seated as BOTH colors across
     * different games (never assuming "the bot is always Black", per this file's
     * playerIndexForColor generalization) and asserts, after every single playBotTurn()
     * call, that the move it just made (read directly off [ChessState.lastFrom]/[lastTo])
     * was among the legal moves available in the position immediately beforehand. Mirrors
     * MancalaGameTest's regression-guard style, and the spirit (if not the literal game
     * count) of the C++ engine's own thousands-of-games random self-play verification.
     * Games are capped at [MAX_PLIES] since insufficient-material draws are explicitly out
     * of scope (see ChessGame.kt's top comment) -- a bare-kings-style endgame would
     * otherwise never naturally terminate.
     */
    @Test
    fun `HARD bot never makes an illegal move across many self-play games`() {
        val random = Random(42)
        repeat(GAMES_TO_PLAY) { gameIndex ->
            val game = newGame(whiteIsBot = true, blackIsBot = true, difficulty = CpuDifficulty.HARD)
            game.startMatch()
            playRandomOpening(game, plies = 6, random)

            var plies = 0
            while (plies < MAX_PLIES) {
                val s = game.state.value!!
                if (s.roundOver) break

                val legalBefore = game.allLegalMoves().toSet()
                assertTrue(legalBefore.isNotEmpty(), "game $gameIndex, ply $plies: side to move must have at least one legal move here")

                game.playBotTurn()
                val after = game.state.value!!
                val played = after.lastFrom!! to after.lastTo!!

                assertTrue(
                    played in legalBefore,
                    "game $gameIndex, ply $plies: HARD bot played $played, which was not in the legal move set $legalBefore"
                )
                plies++
            }
        }
    }

    @Test
    fun `EASY and MEDIUM bots also never make an illegal move`() {
        val random = Random(7)
        for (difficulty in listOf(CpuDifficulty.EASY, CpuDifficulty.MEDIUM)) {
            val game = newGame(whiteIsBot = true, blackIsBot = true, difficulty = difficulty)
            game.startMatch()
            playRandomOpening(game, plies = 6, random)

            var plies = 0
            while (plies < MAX_PLIES) {
                val s = game.state.value!!
                if (s.roundOver) break
                val legalBefore = game.allLegalMoves().toSet()
                game.playBotTurn()
                val after = game.state.value!!
                val played = after.lastFrom!! to after.lastTo!!
                assertTrue(played in legalBefore, "difficulty=$difficulty ply $plies: illegal move $played, legal set was $legalBefore")
                plies++
            }
        }
    }

    companion object {
        private const val GAMES_TO_PLAY = 15
        private const val MAX_PLIES = 60
    }
}
