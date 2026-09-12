package com.gamesuite.games.chess

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ---------------------------------------------------------------------------
// Port of Z:\GameSuite\esp32-tictactoe\TicTacToeESP32\ChessLogic.h/.cpp -- that
// C++ engine's own move generation was verified with perft(1..3) exactly
// matching the known-correct values 20/400/8902 from the standard starting
// position (see ChessGameTest's `perft from the start position matches known
// values`, which re-runs that exact check against this Kotlin port). Read
// this section before changing anything below -- it documents scope exactly
// the way the C++ header/source's own top-of-file comments do.
//
// IMPLEMENTED (faithfully ported, not re-derived): full legal move generation
// for all six piece types, including pins (a move is legal only if it doesn't
// leave the mover's own king in check -- enforced uniformly by actually
// playing every pseudo-legal move on a scratch [Position] copy and checking
// king safety afterward, exactly like generateLegalMoves() in the C++);
// castling both sides with real rights tracking (lost on the king's own move;
// lost per-side the moment a move's `from` OR `to` touches that rook's home
// corner square -- capturing the rook counts even though nothing here ever
// re-derives rights from "is a rook still standing there," so a right once
// lost never reappears just because some other piece later occupies that
// corner); en passant; checkmate and stalemate detection; threefold
// repetition (see [ChessResult.DRAW_REPETITION] and [recordPositionAndCheckRepetition] --
// added after this port's own real self-play/HARD-vs-HARD testing surfaced a genuine,
// previously-possible-forever-stuck-game bug: with no repetition rule, a position both
// sides' bots kept independently re-choosing as best would just repeat indefinitely,
// never resolving to a result at all).
//
// NOT IMPLEMENTED (deliberately, matching the C++ engine's own documented
// scope): the 50-move rule and insufficient-material draws (K vs K, K+minor
// vs K, etc.) are still out of scope. A bare-kings-style endgame with no
// repeated position will still sit at IN_PROGRESS forever -- a known,
// carried-over gap, not something silently added or silently dropped in
// the port. (Threefold repetition, formerly listed here too, is now
// implemented -- see above.)
//
// Promotion always auto-promotes to a queen -- there is no underpromotion
// path, matching the C++ engine's reasoning: a two-tap "pick up piece, put it
// down" touch UI has no cheap third step to ask "which piece?", and
// auto-queen is at least as good as any underpromotion in the overwhelming
// majority of real positions.
//
// AI: minimax with alpha-beta pruning and cheap MVV-style move ordering
// (see [moveOrderScore]) over a pure, side-effect-free [Position] representation
// -- every recursive step works over its own local copy and never touches the
// real Compose [state], mirroring MancalaGame's minimax-over-plain-lists
// pattern exactly. Generalized beyond the C++ version's fixed "AI is always
// Black, human is always White": [evaluateForWhite] scores purely from
// White's perspective, and the search's `maximizerColor` parameter is
// whichever color the actual bot seat is playing this match (GameSuite's own
// architecture, unlike the ESP32 firmware, doesn't assume "the bot is always
// player 2" -- see MancalaScreen's note on this same point) -- player index 0
// is always White (and always moves first, same as every other GameSuite
// board game's player 0), player index 1 is always Black, and either index
// may be the bot.
//
// EVALUATION: material (standard piece values) plus the same three cheap
// O(1)-per-piece positional terms the C++ version used -- center-square
// occupancy, minor-piece development off the back rank, and a small king-
// safety nudge (bonus for a castled king, penalty for one that's wandered off
// its own back rank). Mobility was deliberately left out for the same reason
// the C++ header gives: a full legal-move count per side per leaf would
// roughly triple the search's per-leaf cost, and capture-aware alpha-beta
// already keeps the bot from hanging pieces far more reliably than a
// positional mobility term could.
//
// HARD_SEARCH_DEPTH: see the companion object below for the actual measured
// justification (phones are dramatically faster than the ESP32's 240MHz
// single-core Xtensa chip the C++ version targeted, so this searches several
// plies deeper than that engine's fixed depth of 3).
//
// PERSISTENCE: this file has none of its own. ChessPieceStyle/ChessMotionTier and their
// DataStore-backed ChessPrefsStore used to live directly in this file; they were split into
// the sibling ChessPrefsStore.kt (docs/ENGINE_DECISION.md Action Item 4, "ChessGame.kt
// persistence untangling") specifically so this file could go back to having zero
// Android/DataStore/coroutines imports -- everything below is plain Kotlin over
// androidx.compose.runtime.mutableStateOf, com.gamesuite.core, and CpuDifficulty, the same
// portability shape TicTacToeGame.kt and AirHockeyGame.kt already proved out as real KMP
// pilots. (Stale note, corrected 2026-09-12: this comment used to say the file had NOT yet
// been copied into shared/commonMain -- untangling was the whole scope of Action Item 4, not
// a port. It has since actually happened, which is exactly why you're reading this comment
// from shared/src/commonMain/kotlin/ -- Action Item 5 in docs/ENGINE_DECISION.md, ":app" now
// depends on ":shared" for this game and its own duplicate copy is gone.)
// ---------------------------------------------------------------------------

@Serializable
enum class PieceType { PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING }
@Serializable
enum class PieceColor { WHITE, BLACK }

@Serializable
data class Piece(val type: PieceType, val color: PieceColor)

/** Mirrors ChessRoundResult -- DRAW_STALEMATE and DRAW_REPETITION are named specifically
 *  (not a generic DRAW) so that the still-out-of-scope 50-move-rule/insufficient-material
 *  gap stays visible at every call site, exactly as the C++ header's own comment explains
 *  for its own enum. DRAW_REPETITION is a real, later addition -- see this file's top
 *  comment and [recordPositionAndCheckRepetition] for why it exists.
 *
 *  @Serializable (added alongside PieceType/PieceColor/Piece above for ChessNetMessage.kt's
 *  wire protocol -- see docs/ENGINE_DECISION.md Action Item 8's LAN-multiplayer follow-up)
 *  is a neutral kotlinx.serialization annotation, not a platform framework dependency --
 *  it doesn't reintroduce any of the Android/DataStore/coroutines coupling this file's own
 *  top comment describes removing. */
@Serializable
enum class ChessResult { IN_PROGRESS, WHITE_WINS, BLACK_WINS, DRAW_STALEMATE, DRAW_REPETITION }

/**
 * Immutable snapshot of a chess match, replaced wholesale via `.copy(...)`-
 * shaped construction on every move (never mutated in place), matching every
 * other GameModule's state shape in this codebase (see MancalaState). Square
 * indexing is 0..63, square = rank*8 + file, a1=0, h1=7, a8=56, h8=63 --
 * identical to the C++ engine's own convention, so a square number means the
 * same thing on both sides of this port.
 */
data class ChessState(
    val board: List<Piece?>,
    val sideToMove: PieceColor,
    val castleWK: Boolean,
    val castleWQ: Boolean,
    val castleBK: Boolean,
    val castleBQ: Boolean,
    /** En-passant target square, or null when none is currently available. */
    val epSquare: Int?,
    /** The most recently played move's squares, or null before any move this round --
     *  mirrors ChessLogic.h's lastMove() accessor. Doubles as this port's clean,
     *  directly-assertable "what move did the engine/bot just make" surface (see
     *  ChessGameTest) instead of Mancala's fragile lastAction-string regex approach. */
    val lastFrom: Int? = null,
    val lastTo: Int? = null,
    val lastAction: String = "Game started",
    val roundOver: Boolean = false,
    val result: ChessResult = ChessResult.IN_PROGRESS,
    /** True when [sideToMove] is currently in check. Purely informational (for the
     *  screen's check highlight) -- legality is already fully enforced elsewhere. */
    val inCheck: Boolean = false,
    val winnerPlayerId: String? = null
)

/** Player index 0 is always White (and always moves first, matching every other
 *  GameSuite board game's player-0-goes-first convention); player index 1 is always
 *  Black. Either index may be the bot -- see this file's top comment. */
fun playerIndexForColor(color: PieceColor): Int = if (color == PieceColor.WHITE) 0 else 1

// ---- Internal move representation & pure rules engine -------------------
// Deliberately never exposed publicly, same spirit as ChessLogic.h's private
// Move struct: every external caller only ever needs a plain (from, to) pair
// (auto-queen promotion needs no extra input; castling is just the king's own
// two-square move).

private data class ChessMove(
    val from: Int,
    val to: Int,
    val promotion: PieceType? = null,
    val enPassant: Boolean = false,
    /** 0 = not a castle, +1 = kingside, -1 = queenside. */
    val castle: Int = 0
)

/**
 * Minimal pure board snapshot used for move generation, legality checking,
 * and the HARD bot's minimax search -- mirrors MancalaGame's SowResult/pits
 * pattern: recursion works over this bare-bones representation (board +
 * castling rights + en-passant target only -- never the full [ChessState],
 * which also carries UI-only fields like lastAction/roundOver/winnerPlayerId)
 * so exploring thousands of hypothetical positions per turn never risks
 * touching the real Compose [ChessGame.state]. Side-to-move is threaded as an
 * explicit parameter through every function below rather than stored here,
 * the same way Mancala threads `playerIndex` explicitly instead of bundling
 * it into `SowResult`.
 */
private data class Position(
    val board: List<Piece?>,
    val castleWK: Boolean,
    val castleWQ: Boolean,
    val castleBK: Boolean,
    val castleBQ: Boolean,
    val epSquare: Int?
)

private fun positionOf(s: ChessState): Position =
    Position(s.board, s.castleWK, s.castleWQ, s.castleBK, s.castleBQ, s.epSquare)

private fun sqRow(sq: Int) = sq / 8
private fun sqCol(sq: Int) = sq % 8
private fun makeSquare(row: Int, col: Int) = row * 8 + col
private fun onBoard(row: Int, col: Int) = row in 0..7 && col in 0..7
private fun opponent(color: PieceColor) = if (color == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE

/** Indexed by [PieceType.ordinal] -- standard values, king contributes nothing
 *  (kings are never a legal capture target, so its value is never read for real). */
private val PIECE_VALUE = intArrayOf(100, 320, 330, 500, 900, 0) // PAWN,KNIGHT,BISHOP,ROOK,QUEEN,KING

private const val INF = 2_000_000_000
private const val MATE_SCORE = 1_000_000

private val KNIGHT_OFFSETS = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
private val ROOK_DIRS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
private val BISHOP_DIRS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

/**
 * Pseudo-legal moves for `color`: everything that piece's normal movement
 * rules allow given current occupancy, WITHOUT checking whether it leaves
 * `color`'s own king in check ([generateLegalMoves] filters for that
 * afterward, uniformly, for every move type). Castling's own specific
 * legality (king not in/through/into check, squares empty, rook still on its
 * corner) is applied inline here, exactly like the C++ version -- the general
 * post-hoc king-safety filter still runs over these afterward too, as a
 * second, redundant safety net.
 */
private fun generatePseudoMoves(pos: Position, color: PieceColor): List<ChessMove> {
    val moves = mutableListOf<ChessMove>()
    val board = pos.board
    val opp = opponent(color)
    val forward = if (color == PieceColor.WHITE) 1 else -1
    val startRow = if (color == PieceColor.WHITE) 1 else 6

    fun promoTypeIfNeeded(to: Int): PieceType? {
        val toRow = sqRow(to)
        val promotes = (color == PieceColor.WHITE && toRow == 7) || (color == PieceColor.BLACK && toRow == 0)
        return if (promotes) PieceType.QUEEN else null
    }

    fun slide(from: Int, fr: Int, fc: Int, dr: Int, dc: Int) {
        var rr = fr + dr
        var cc = fc + dc
        while (onBoard(rr, cc)) {
            val to = makeSquare(rr, cc)
            val occ = board[to]
            if (occ == null) {
                moves += ChessMove(from, to)
            } else {
                if (occ.color == opp) moves += ChessMove(from, to)
                break // blocked either way -- own piece or a captured enemy, can't slide past it
            }
            rr += dr; cc += dc
        }
    }

    for (from in 0 until 64) {
        val piece = board[from] ?: continue
        if (piece.color != color) continue
        val fr = sqRow(from)
        val fc = sqCol(from)

        when (piece.type) {
            PieceType.PAWN -> {
                val r1 = fr + forward
                if (onBoard(r1, fc) && board[makeSquare(r1, fc)] == null) {
                    val to1 = makeSquare(r1, fc)
                    moves += ChessMove(from, to1, promotion = promoTypeIfNeeded(to1))
                    if (fr == startRow) {
                        val r2 = fr + 2 * forward
                        val to2 = makeSquare(r2, fc)
                        if (board[to2] == null) moves += ChessMove(from, to2)
                    }
                }
                for (dc in intArrayOf(-1, 1)) {
                    val cr = fr + forward
                    val cc = fc + dc
                    if (!onBoard(cr, cc)) continue
                    val to = makeSquare(cr, cc)
                    val occ = board[to]
                    if (occ != null && occ.color == opp) {
                        moves += ChessMove(from, to, promotion = promoTypeIfNeeded(to))
                    } else if (pos.epSquare != null && pos.epSquare == to) {
                        // En passant never promotes -- it always lands on rank 3 or 6.
                        moves += ChessMove(from, to, enPassant = true)
                    }
                }
            }
            PieceType.KNIGHT -> {
                for ((dr, dc) in KNIGHT_OFFSETS) {
                    val rr = fr + dr
                    val cc = fc + dc
                    if (!onBoard(rr, cc)) continue
                    val to = makeSquare(rr, cc)
                    if (board[to]?.color != color) moves += ChessMove(from, to)
                }
            }
            PieceType.BISHOP -> for ((dr, dc) in BISHOP_DIRS) slide(from, fr, fc, dr, dc)
            PieceType.ROOK -> for ((dr, dc) in ROOK_DIRS) slide(from, fr, fc, dr, dc)
            PieceType.QUEEN -> {
                for ((dr, dc) in ROOK_DIRS) slide(from, fr, fc, dr, dc)
                for ((dr, dc) in BISHOP_DIRS) slide(from, fr, fc, dr, dc)
            }
            PieceType.KING -> {
                for (dr in -1..1) for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val rr = fr + dr
                    val cc = fc + dc
                    if (!onBoard(rr, cc)) continue
                    val to = makeSquare(rr, cc)
                    if (board[to]?.color != color) moves += ChessMove(from, to)
                }
                // Castling -- both the rights flag AND the rook actually still sitting on its
                // original corner are checked (see this file's top comment on why rights alone
                // aren't trusted to mean "the rook is really there").
                if (color == PieceColor.WHITE && from == 4) {
                    if (pos.castleWK && board[5] == null && board[6] == null &&
                        board[7]?.type == PieceType.ROOK && board[7]?.color == PieceColor.WHITE &&
                        !isSquareAttacked(board, 4, PieceColor.BLACK) &&
                        !isSquareAttacked(board, 5, PieceColor.BLACK) &&
                        !isSquareAttacked(board, 6, PieceColor.BLACK)
                    ) {
                        moves += ChessMove(4, 6, castle = 1)
                    }
                    if (pos.castleWQ && board[3] == null && board[2] == null && board[1] == null &&
                        board[0]?.type == PieceType.ROOK && board[0]?.color == PieceColor.WHITE &&
                        !isSquareAttacked(board, 4, PieceColor.BLACK) &&
                        !isSquareAttacked(board, 3, PieceColor.BLACK) &&
                        !isSquareAttacked(board, 2, PieceColor.BLACK)
                    ) {
                        moves += ChessMove(4, 2, castle = -1)
                    }
                } else if (color == PieceColor.BLACK && from == 60) {
                    if (pos.castleBK && board[61] == null && board[62] == null &&
                        board[63]?.type == PieceType.ROOK && board[63]?.color == PieceColor.BLACK &&
                        !isSquareAttacked(board, 60, PieceColor.WHITE) &&
                        !isSquareAttacked(board, 61, PieceColor.WHITE) &&
                        !isSquareAttacked(board, 62, PieceColor.WHITE)
                    ) {
                        moves += ChessMove(60, 62, castle = 1)
                    }
                    if (pos.castleBQ && board[59] == null && board[58] == null && board[57] == null &&
                        board[56]?.type == PieceType.ROOK && board[56]?.color == PieceColor.BLACK &&
                        !isSquareAttacked(board, 60, PieceColor.WHITE) &&
                        !isSquareAttacked(board, 59, PieceColor.WHITE) &&
                        !isSquareAttacked(board, 58, PieceColor.WHITE)
                    ) {
                        moves += ChessMove(60, 58, castle = -1)
                    }
                }
            }
        }
    }
    return moves
}

private fun isSquareAttacked(board: List<Piece?>, square: Int, byColor: PieceColor): Boolean {
    val r = sqRow(square)
    val c = sqCol(square)

    // Pawns: a byColor pawn attacks diagonally one rank in its own forward direction, so
    // `square` is attacked by one exactly when a byColor pawn sits one rank BEHIND it (from
    // byColor's own forward direction) and one file to either side.
    val pawnForward = if (byColor == PieceColor.WHITE) 1 else -1
    val pr = r - pawnForward
    for (dc in intArrayOf(-1, 1)) {
        val pc = c + dc
        if (onBoard(pr, pc)) {
            val p = board[makeSquare(pr, pc)]
            if (p?.type == PieceType.PAWN && p.color == byColor) return true
        }
    }

    for ((dr, dc) in KNIGHT_OFFSETS) {
        val rr = r + dr
        val cc = c + dc
        if (onBoard(rr, cc)) {
            val p = board[makeSquare(rr, cc)]
            if (p?.type == PieceType.KNIGHT && p.color == byColor) return true
        }
    }

    for (dr in -1..1) for (dc in -1..1) {
        if (dr == 0 && dc == 0) continue
        val rr = r + dr
        val cc = c + dc
        if (onBoard(rr, cc)) {
            val p = board[makeSquare(rr, cc)]
            if (p?.type == PieceType.KING && p.color == byColor) return true
        }
    }

    for ((dr, dc) in ROOK_DIRS) {
        var rr = r + dr
        var cc = c + dc
        while (onBoard(rr, cc)) {
            val p = board[makeSquare(rr, cc)]
            if (p != null) {
                if (p.color == byColor && (p.type == PieceType.ROOK || p.type == PieceType.QUEEN)) return true
                break
            }
            rr += dr; cc += dc
        }
    }
    for ((dr, dc) in BISHOP_DIRS) {
        var rr = r + dr
        var cc = c + dc
        while (onBoard(rr, cc)) {
            val p = board[makeSquare(rr, cc)]
            if (p != null) {
                if (p.color == byColor && (p.type == PieceType.BISHOP || p.type == PieceType.QUEEN)) return true
                break
            }
            rr += dr; cc += dc
        }
    }
    return false
}

/**
 * The single place pins/checks are actually enforced: try every pseudo-legal
 * move on a scratch [Position] copy and keep it only if the mover's own king
 * ends up safe. Brute force, but correct for every move type uniformly with
 * no special-cased pin detection to get subtly wrong -- exactly the C++
 * engine's own approach.
 */
private fun generateLegalMoves(pos: Position, color: PieceColor): List<ChessMove> {
    val pseudo = generatePseudoMoves(pos, color)
    val opp = opponent(color)
    return pseudo.filter { m ->
        val copy = applyMove(pos, m)
        !isSquareAttacked(copy.board, kingSquare(copy.board, color), opp)
    }
}

/** Generates legal moves for `color` AND reports whether `color` is currently in check in
 *  one pass, mirroring ChessBoard::legalMovesAndCheck (both game-status and the search need
 *  both facts together). */
private fun legalMovesAndCheck(pos: Position, color: PieceColor): Pair<List<ChessMove>, Boolean> {
    val inCheck = isSquareAttacked(pos.board, kingSquare(pos.board, color), opponent(color))
    return generateLegalMoves(pos, color) to inCheck
}

/** Unreachable in any position reached through real play (kings are never a legal
 *  capture target) -- falls back to square 0 defensively rather than crashing, same as
 *  the C++ version's own kingSquare(). */
private fun kingSquare(board: List<Piece?>, color: PieceColor): Int =
    board.indexOfFirst { it?.type == PieceType.KING && it.color == color }.let { if (it < 0) 0 else it }

private fun applyMove(pos: Position, m: ChessMove): Position {
    val board = pos.board.toMutableList()
    val moving = board[m.from]!!
    val color = moving.color

    // En passant's captured pawn sits beside the mover, not on the destination square --
    // that's exactly why the destination is empty even though this is a capture.
    if (m.enPassant) {
        board[makeSquare(sqRow(m.from), sqCol(m.to))] = null
    }

    board[m.to] = if (m.promotion != null) moving.copy(type = m.promotion) else moving
    board[m.from] = null

    // Castling: the king's own move was just applied above via the generic from/to write;
    // relocate the matching rook to complete it.
    if (m.castle == 1) {
        val rookFrom = if (color == PieceColor.WHITE) 7 else 63
        val rookTo = if (color == PieceColor.WHITE) 5 else 61
        board[rookTo] = board[rookFrom]
        board[rookFrom] = null
    } else if (m.castle == -1) {
        val rookFrom = if (color == PieceColor.WHITE) 0 else 56
        val rookTo = if (color == PieceColor.WHITE) 3 else 59
        board[rookTo] = board[rookFrom]
        board[rookFrom] = null
    }

    var castleWK = pos.castleWK
    var castleWQ = pos.castleWQ
    var castleBK = pos.castleBK
    var castleBQ = pos.castleBQ

    if (moving.type == PieceType.KING) {
        if (color == PieceColor.WHITE) { castleWK = false; castleWQ = false }
        else { castleBK = false; castleBQ = false }
    }
    // Corner-square checks, independent of what piece is involved -- this one uniform rule
    // covers both "that side's rook just moved away" and "that rook just got captured by
    // anything, from anywhere" -- and never looks at what's on the square NOW, only at
    // whether this move's from/to touched it, so a right once lost can't come back just
    // because some other piece later occupies that corner.
    if (m.from == 0 || m.to == 0) castleWQ = false   // a1
    if (m.from == 7 || m.to == 7) castleWK = false   // h1
    if (m.from == 56 || m.to == 56) castleBQ = false // a8
    if (m.from == 63 || m.to == 63) castleBK = false // h8

    var epSquare: Int? = null
    if (moving.type == PieceType.PAWN) {
        val fr = sqRow(m.from)
        val tr = sqRow(m.to)
        val diff = tr - fr
        if (diff == 2 || diff == -2) {
            epSquare = makeSquare((fr + tr) / 2, sqCol(m.from))
        }
    }

    return Position(board, castleWK, castleWQ, castleBK, castleBQ, epSquare)
}

/** IN_PROGRESS / checkmate / stalemate for the position where it is `sideToMove`'s turn,
 *  mirroring ChessBoard::result() exactly. */
private fun evaluateGameStatus(pos: Position, sideToMove: PieceColor): Pair<ChessResult, Boolean> {
    val (moves, inCheckNow) = legalMovesAndCheck(pos, sideToMove)
    val result = when {
        moves.isNotEmpty() -> ChessResult.IN_PROGRESS
        !inCheckNow -> ChessResult.DRAW_STALEMATE
        // sideToMove has no legal moves and is in check -- checkmated; the OTHER color wins.
        sideToMove == PieceColor.WHITE -> ChessResult.BLACK_WINS
        else -> ChessResult.WHITE_WINS
    }
    return result to inCheckNow
}

/** Cheap MVV-style move ordering: score by whatever a move captures (0 for a quiet move)
 *  plus a flat bonus for promotions -- enough to make alpha-beta prune far more effectively
 *  than raw generation order, without full MVV-LVA's extra bookkeeping. */
private fun moveOrderScore(move: ChessMove, board: List<Piece?>): Int {
    var score = 0
    val captured = board.getOrNull(move.to)
    if (captured != null) score += PIECE_VALUE[captured.type.ordinal]
    else if (move.enPassant) score += PIECE_VALUE[PieceType.PAWN.ordinal]
    if (move.promotion != null) score += 800
    return score
}

/** Material using standard piece values, plus three cheap O(1)-per-piece positional terms.
 *  Scored purely from WHITE's perspective (positive favors White) -- generalized from the
 *  C++ version's fixed "positive favors AI/Black" so [search] can flip the sign for
 *  whichever color is actually the bot seat this match (see this file's top comment). */
private fun evaluateForWhite(board: List<Piece?>): Int {
    var score = 0
    for (sq in 0 until 64) {
        val p = board[sq] ?: continue
        val sign = if (p.color == PieceColor.WHITE) 1 else -1
        score += sign * PIECE_VALUE[p.type.ordinal]

        val r = sqRow(sq)
        val c = sqCol(sq)
        val centerSquare = (r == 3 || r == 4) && (c == 3 || c == 4) // d4,d5,e4,e5
        if (centerSquare && (p.type == PieceType.PAWN || p.type == PieceType.KNIGHT || p.type == PieceType.BISHOP)) {
            score += sign * 15
        }
        // Development: a knight/bishop off its own back rank is doing something.
        if (p.type == PieceType.KNIGHT || p.type == PieceType.BISHOP) {
            val homeRow = if (p.color == PieceColor.WHITE) 0 else 7
            if (r != homeRow) score += sign * 10
        }
        // King safety: reward having castled, penalize a king wandered off its own back rank.
        if (p.type == PieceType.KING) {
            val homeRow = if (p.color == PieceColor.WHITE) 0 else 7
            val castledSquare = r == homeRow && (c == 6 || c == 2)
            val wanderedOut = r != homeRow
            if (castledSquare) score += sign * 20
            if (wanderedOut) score += sign * -25
        }
    }
    return score
}

/** Depth-limited minimax with alpha-beta pruning, scored relative to [maximizerColor] (the
 *  bot's own color this match -- see this file's top comment on why that isn't hardcoded to
 *  Black). Pure over [Position] copies; never touches real Compose state. */
private fun search(pos: Position, sideToMove: PieceColor, depthLeft: Int, alphaIn: Int, betaIn: Int, maximizerColor: PieceColor): Int {
    val (moves, inCheckNow) = legalMovesAndCheck(pos, sideToMove)
    if (moves.isEmpty()) {
        if (!inCheckNow) return 0 // stalemate
        // sideToMove is checkmated here -- bad for that color. The +/-depthLeft term prefers
        // a mate found with MORE depth remaining (sooner from the root) when it's good, and
        // prefers to delay one found sooner when it's bad -- "win fast, lose slow."
        return if (sideToMove == maximizerColor) -MATE_SCORE - depthLeft else MATE_SCORE + depthLeft
    }
    if (depthLeft == 0) {
        val whiteScore = evaluateForWhite(pos.board)
        return if (maximizerColor == PieceColor.WHITE) whiteScore else -whiteScore
    }

    val ordered = moves.sortedByDescending { moveOrderScore(it, pos.board) }
    val maximizing = sideToMove == maximizerColor
    var best = if (maximizing) -INF else INF
    var alpha = alphaIn
    var beta = betaIn
    for (m in ordered) {
        val next = applyMove(pos, m)
        val score = search(next, opponent(sideToMove), depthLeft - 1, alpha, beta, maximizerColor)
        if (maximizing) {
            if (score > best) best = score
            if (best > alpha) alpha = best
        } else {
            if (score < best) best = score
            if (best < beta) beta = best
        }
        if (beta <= alpha) break // alpha-beta cutoff
    }
    return best
}

private fun minimaxBestMove(pos: Position, color: PieceColor, depth: Int): ChessMove? {
    val moves = generateLegalMoves(pos, color).sortedByDescending { moveOrderScore(it, pos.board) }
    if (moves.isEmpty()) return null
    var alpha = -INF
    val beta = INF
    var bestScore = -INF
    var bestMove: ChessMove? = null
    for (m in moves) {
        val next = applyMove(pos, m)
        val score = search(next, opponent(color), depth - 1, alpha, beta, color)
        if (score > bestScore) {
            bestScore = score
            bestMove = m
        }
        if (bestScore > alpha) alpha = bestScore
    }
    return bestMove
}

/** MEDIUM's one-ply greedy heuristic: statically evaluate the position immediately after
 *  each legal move (no further look-ahead) and take the best-looking one for the mover's own
 *  color -- a "real" heuristic (it reasons about material/position, not just "first legal
 *  move"), but genuinely weaker than HARD's multi-ply search since it never considers the
 *  opponent's reply. */
private fun onePlyGreedyMove(pos: Position, color: PieceColor): ChessMove? {
    val moves = generateLegalMoves(pos, color)
    if (moves.isEmpty()) return null
    val sign = if (color == PieceColor.WHITE) 1 else -1
    return moves.maxByOrNull { m -> sign * evaluateForWhite(applyMove(pos, m).board) }
}

private fun squareName(sq: Int): String = "${'a' + sqCol(sq)}${sqRow(sq) + 1}"

/** Standard starting count per piece type (kings excluded -- never captured in a real game,
 *  and this engine's own [kingSquare] fallback means a board should never actually reach zero
 *  kings anyway). Feeds [capturedPiecesFor]'s pure board-diff below. */
private val STARTING_PIECE_COUNT: Map<PieceType, Int> = mapOf(
    PieceType.PAWN to 8, PieceType.KNIGHT to 2, PieceType.BISHOP to 2,
    PieceType.ROOK to 2, PieceType.QUEEN to 1
)

/**
 * Captured-piece breakdown for [color], derived purely from the CURRENT board's piece counts
 * vs. the standard starting counts -- deliberately zero new persisted game-state, the same
 * "recompute from the board, don't track history" shape [currentResult] already uses. Backs
 * ChessScreen's captured-piece tray and material-differential readout.
 *
 * Correctly accounts for this engine's auto-queen-only promotion rule (see this file's top
 * comment): any queen beyond the starting one can only be a promoted pawn, never a captured
 * enemy queen, so it's subtracted back out of the "missing pawns" count instead of being
 * misreported as a captured queen (which would also make the captured-pawn count wrong by one
 * in the other direction). This exact reasoning would NOT hold if underpromotion were ever
 * added later -- see this file's top comment on why it currently isn't.
 */
fun capturedPiecesFor(board: List<Piece?>, color: PieceColor): List<PieceType> {
    val onBoard = PieceType.entries.associateWith { type -> board.count { it?.color == color && it.type == type } }
    val promotions = ((onBoard[PieceType.QUEEN] ?: 0) - (STARTING_PIECE_COUNT[PieceType.QUEEN] ?: 0)).coerceAtLeast(0)
    val captured = mutableListOf<PieceType>()
    for ((type, startCount) in STARTING_PIECE_COUNT) {
        val stillOnBoard = onBoard[type] ?: 0
        val promotedAway = if (type == PieceType.PAWN) promotions else 0
        val missing = (startCount - stillOnBoard - promotedAway).coerceAtLeast(0)
        repeat(missing) { captured += type }
    }
    return captured.sortedByDescending { PIECE_VALUE[it.ordinal] }
}

/** White's material advantage in centipawn-style units (see [PIECE_VALUE] -- a pawn is 100),
 *  positive favors White. Pure sum over [capturedPiecesFor] both ways, so it always agrees
 *  exactly with whatever the captured-piece tray is showing. */
fun materialAdvantageForWhite(board: List<Piece?>): Int {
    val blackLost = capturedPiecesFor(board, PieceColor.BLACK).sumOf { PIECE_VALUE[it.ordinal] }
    val whiteLost = capturedPiecesFor(board, PieceColor.WHITE).sumOf { PIECE_VALUE[it.ordinal] }
    return blackLost - whiteLost
}

// ChessPieceStyle, ChessMotionTier, and their DataStore-backed persistence (ChessPrefsStore)
// used to live directly in this file -- moved to the sibling ChessPrefsStore.kt for
// docs/ENGINE_DECISION.md Action Item 4 ("ChessGame.kt persistence untangling"). Nothing
// below this point (or above, back to this file's own imports) touches
// Context/DataStore/coroutines Flow any more -- see ChessPrefsStore.kt's own top-of-file
// note for the full reasoning. ChessScreen.kt's existing `import
// com.gamesuite.games.chess.ChessPieceStyle` (etc.) needed no change: Kotlin resolves same-
// package symbols regardless of which file in the package declares them.

/**
 * Standard-issue chess engine, GameSuite-shaped: a plain Kotlin class holding
 * `mutableStateOf` fields (no ViewModel, no StateFlow for its own board
 * state), mutated only by replacing [state] wholesale via a freshly built
 * [ChessState] -- matching MancalaGame/TicTacToeGame exactly.
 */
class ChessGame : GameModule {
    override val gameId = "chess"
    override val displayName = "Chess"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        // Real networked play (docs/ENGINE_DECISION.md Action Item 8's follow-up on
        // extending the proven LAN-multiplayer pattern to a second game) -- mirrors
        // TicTacToeGame.kt's own isNetworked/isHost design almost exactly; see
        // ChessNetMessage.kt's KDoc for what's genuinely the same and the one real
        // wrinkle (playMove's own explicit playerIndex param, since -- unlike
        // TicTacToe's implicit-current-player cellClicked -- Chess's move entry point
        // already took an explicit player index for pass-and-play's sake).
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<ChessState?>(null)

    /** Running session score across games -- scoreP1 counts White wins (player index 0),
     *  scoreP2 counts Black wins (player index 1), mirroring MancalaGame/TicTacToeGame's
     *  scoreP1/scoreP2/draws exactly. */
    val scoreP1 = mutableStateOf(0)
    val scoreP2 = mutableStateOf(0)
    val draws = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via the round-over panel), not per-game. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** How many times each position (see [positionSignature]) has occurred so far this game --
     *  real threefold-repetition tracking, not re-derivable from the current board alone the
     *  way [currentResult]'s other checks are. See this file's top comment and
     *  [recordPositionAndCheckRepetition]. */
    private val positionHistory = mutableMapOf<String, Int>()

    /** True for real networked play (LAN or online) -- see ChessNetMessage.kt's own KDoc for
     *  the host-authoritative design this gates, mirroring TicTacToeGame.kt's own
     *  isNetworked exactly. False (the default) for SINGLE_DEVICE_PASS_AND_PLAY/
     *  SINGLE_PLAYER_VS_BOT, where every mutation below applies directly with no network
     *  involvement at all, exactly as it always has. */
    private val isNetworked: Boolean
        get() = context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE

    /** The host always sits at [GameContext.localPlayerIndex] == 0, by the same lobby
     *  convention TicTacToeGame.kt/UnoGame.kt's own isHost relies on. Meaningless (never
     *  read) unless [isNetworked]. */
    private val isHost: Boolean
        get() = context.localPlayerIndex == 0

    /** Host only: bumped on every broadcast [broadcastState] triggers -- see
     *  [ChessNetMessage.StateSync]'s own KDoc for why this exists. */
    private var stateVersion = 0

    /** Guest only: the last [ChessNetMessage.StateSync.version] actually applied, so a
     *  stray out-of-order delivery can never move state backward. */
    private var lastAppliedStateVersion = -1

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        scoreP1.value = 0
        scoreP2.value = 0
        draws.value = 0
        positionHistory.clear()
        stateVersion = 0
        lastAppliedStateVersion = -1

        if (isNetworked) {
            context.transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
            // Covers the same startup race TicTacToeGame.kt's own init() comment
            // documents: this guest's listener above might register after the host has
            // already broadcast (or will broadcast before this guest is ready to receive).
            if (!isHost) sendToHost(ChessNetMessage.RequestState)
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        positionHistory.clear()
        val board = MutableList<Piece?>(64) { null }
        val backRank = listOf(
            PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN,
            PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK
        )
        for (c in 0..7) {
            board[makeSquare(0, c)] = Piece(backRank[c], PieceColor.WHITE)
            board[makeSquare(1, c)] = Piece(PieceType.PAWN, PieceColor.WHITE)
            board[makeSquare(6, c)] = Piece(PieceType.PAWN, PieceColor.BLACK)
            board[makeSquare(7, c)] = Piece(backRank[c], PieceColor.BLACK)
        }
        // Record the starting position as this game's first occurrence -- rare in practice for
        // it to recur, but real threefold repetition counts every occurrence from move zero.
        recordPositionAndCheckRepetition(Position(board, true, true, true, true, null), PieceColor.WHITE)
        state.value = ChessState(
            board = board,
            sideToMove = PieceColor.WHITE,
            castleWK = true, castleWQ = true, castleBK = true, castleBQ = true,
            epSquare = null,
            lastFrom = null, lastTo = null,
            lastAction = "Game started"
        )
        // Networked: covers both the very first real-game-start broadcast AND, since
        // playAgain() below delegates straight to startMatch(), the play-again broadcast
        // too -- one call site rather than TicTacToeGame.kt's two, since Chess's own
        // playAgain() has no separate reset logic of its own to broadcast from.
        if (isNetworked && isHost) broadcastState()
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Called from the round-over panel's "Back to Menu" button -- ends the whole session
     *  (not just the current game), reporting the cumulative score. Mirrors
     *  MancalaGame.leaveSession()/TicTacToeGame.leaveSession(). */
    fun leaveSession() {
        if (matchOver.value) return
        val scores = mutableListOf<PlayerScore>()
        context.players.getOrNull(0)?.let {
            scores += PlayerScore(playerId = it.playerId, score = scoreP1.value, isWinner = scoreP1.value > scoreP2.value)
        }
        context.players.getOrNull(1)?.let {
            scores += PlayerScore(playerId = it.playerId, score = scoreP2.value, isWinner = scoreP2.value > scoreP1.value)
        }
        endMatch(GameResult(scores = scores))
    }

    /** Called from the round-over panel's "Play Again" button -- keeps the running score and
     *  deals a fresh board. Mirrors MancalaGame.playAgain()/TicTacToeGame.playAgain().
     *  Networked + not host: forwarded to the host as a [ChessIntentPayload.PlayAgain]
     *  intent instead of applied locally, same reasoning as [playMove]. */
    fun playAgain() {
        if (isNetworked && !isHost) {
            sendToHost(ChessIntentPayload.PlayAgain)
            return
        }
        applyPlayAgain()
    }

    private fun applyPlayAgain() {
        if (matchOver.value) return
        startMatch()
    }

    /**
     * The single public move-entry point, called directly from the screen's touch handling --
     * silently rejects any illegal call (wrong player's turn, game already over, or not among
     * the mover's actual legal moves right now), matching every other GameSuite game's public
     * move method. [playerIndex] must match whichever color currently has the move (see
     * [playerIndexForColor]) -- this is what stops a SINGLE_DEVICE_PASS_AND_PLAY tap meant for
     * one side from accidentally moving the other side's piece.
     */
    fun playMove(playerIndex: Int, from: Int, to: Int) {
        if (isNetworked && !isHost) {
            // A guest's own UI only ever lets the LOCAL player move their own pieces, so
            // playerIndex here should always already be context.localPlayerIndex -- this
            // check is defensive, not load-bearing (the host re-validates the sender's
            // actual turn independently in handleNetworkMessage regardless of what a
            // buggy/malicious caller passes here).
            if (playerIndex != context.localPlayerIndex) return
            sendToHost(ChessIntentPayload.PlayMove(from, to))
            return
        }
        applyPlayMove(playerIndex, from, to)
    }

    /** The real move logic -- called directly for local play (pass-and-play/vs-bot), by
     *  [playMove] when this instance IS the host (its own local tap), and by
     *  [handleNetworkMessage] when the host applies a validated guest intent. */
    private fun applyPlayMove(playerIndex: Int, from: Int, to: Int) {
        val s = state.value ?: return
        if (s.roundOver || matchOver.value) return
        if (playerIndex != playerIndexForColor(s.sideToMove)) return
        val pos = positionOf(s)
        val move = generateLegalMoves(pos, s.sideToMove).firstOrNull { it.from == from && it.to == to } ?: return
        applyPlayedMove(s, pos, move)
    }

    /**
     * Called from the screen when it becomes a bot's turn (see ChessScreen's LaunchedEffect,
     * mirroring MancalaScreen.playBotTurn()/TicTacToeGame.playBotTurn()). EASY plays a
     * genuinely weak, uniformly random legal move; MEDIUM plays a real one-ply greedy
     * heuristic ([onePlyGreedyMove]); HARD plays the actual ported minimax/alpha-beta search
     * ([minimaxBestMove]). No-op if it isn't actually a bot's turn right now.
     */
    fun playBotTurn() {
        val s = state.value ?: return
        if (s.roundOver || matchOver.value) return
        val botPlayerIndex = playerIndexForColor(s.sideToMove)
        if (context.players.getOrNull(botPlayerIndex)?.isBot != true) return

        val pos = positionOf(s)
        val move = when (difficulty) {
            CpuDifficulty.EASY -> generateLegalMoves(pos, s.sideToMove).randomOrNull()
            CpuDifficulty.MEDIUM -> onePlyGreedyMove(pos, s.sideToMove)
            CpuDifficulty.HARD -> minimaxBestMove(pos, s.sideToMove, HARD_SEARCH_DEPTH)
        }
        if (move != null) applyPlayedMove(s, pos, move)
    }

    /**
     * Legal destination squares for whatever's sitting on [square], for the screen's
     * "tap a piece, see its legal moves highlighted" step -- mirrors ChessLogic.h's
     * legalDestinations(). Returns empty for an empty square, a square not belonging to the
     * side to move, or once the game is over.
     */
    fun legalDestinationsFor(square: Int): Set<Int> {
        val s = state.value ?: return emptySet()
        if (s.roundOver) return emptySet()
        val piece = s.board.getOrNull(square) ?: return emptySet()
        if (piece.color != s.sideToMove) return emptySet()
        return generateLegalMoves(positionOf(s), s.sideToMove)
            .filter { it.from == square }
            .map { it.to }
            .toSet()
    }

    /** Every (from, to) legal move for whoever currently has the move -- used internally by
     *  the HARD bot via [minimaxBestMove]/[onePlyGreedyMove], and directly by ChessGameTest
     *  for perft-style and random-self-play verification (mirrors ChessLogic.h's test-only
     *  legalMoveList() seam, but reachable through the same public engine surface everyone
     *  else uses, rather than a separate test-only path). */
    fun allLegalMoves(): List<Pair<Int, Int>> {
        val s = state.value ?: return emptyList()
        return generateLegalMoves(positionOf(s), s.sideToMove).map { it.from to it.to }
    }

    /**
     * Read-only in-progress/checkmate/stalemate status of the CURRENT position, recomputed
     * from scratch rather than trusting `state.value?.result` -- lets a test seed an
     * arbitrary [ChessState] directly (state is public `mutableStateOf`, the same seam
     * MancalaGameTest already uses) and check whether the engine independently recognizes it
     * correctly, the same way ChessLogic.h's own result() is a pure query over the board.
     */
    fun currentResult(): ChessResult {
        val s = state.value ?: return ChessResult.IN_PROGRESS
        return evaluateGameStatus(positionOf(s), s.sideToMove).first
    }

    /**
     * "Position Balance" for ChessScreen's evaluation bar -- the SAME intentionally-simple
     * material+basic-positional heuristic [evaluateForWhite] already uses for the bot's leaf
     * evaluation, just exposed read-only for the UI. Positive favors White, in the same
     * centipawn-style units as [PIECE_VALUE] (a pawn is 100). Deliberately NOT a real engine
     * evaluation -- the screen must label this "Position Balance," never "Engine Evaluation" or
     * a raw centipawn number, since this heuristic skips mobility and any real tactical search.
     */
    fun positionBalance(): Int {
        val s = state.value ?: return 0
        return evaluateForWhite(s.board)
    }

    /**
     * Squares of every enemy piece currently attacking [ChessState.sideToMove]'s king, purely
     * for ChessScreen's checkmate "attack line" visual -- empty whenever the side to move isn't
     * in check. Recomputed fresh from the current board each call (no new persisted state,
     * same shape as [currentResult]/[positionBalance]). Reuses [generatePseudoMoves] rather than
     * duplicating [isSquareAttacked]'s per-piece-type logic: an enemy piece's pseudo-legal move
     * onto the king's own square is, by construction, exactly an attack on it -- pseudo
     * generation already allows "capturing" whatever occupies the destination regardless of its
     * type, kings included, precisely because real king-safety filtering happens elsewhere
     * ([generateLegalMoves]), never inside pseudo generation itself. Usually one square; two for
     * a discovered double check.
     */
    fun checkingPieceSquares(): Set<Int> {
        val s = state.value ?: return emptySet()
        if (!s.inCheck) return emptySet()
        val pos = positionOf(s)
        val kingSq = kingSquare(s.board, s.sideToMove)
        return generatePseudoMoves(pos, opponent(s.sideToMove))
            .filter { it.to == kingSq }
            .map { it.from }
            .toSet()
    }

    /**
     * Perft (performance test) node count: the number of leaf positions reachable by playing
     * every legal move sequence exactly [depth] plies deep from the current position. This is
     * the exact verification technique ChessLogic.cpp's own native_test used -- from the
     * standard starting position, perft(1)==20, perft(2)==400, perft(3)==8902 are known-correct
     * values (see ChessGameTest); a discrepancy here would mean this port's move generation
     * diverged from the original, not just "looks plausible."
     */
    fun perft(depth: Int): Long {
        val s = state.value ?: return 0
        return perft(positionOf(s), s.sideToMove, depth)
    }

    private fun perft(pos: Position, color: PieceColor, depth: Int): Long {
        if (depth == 0) return 1L
        val moves = generateLegalMoves(pos, color)
        if (depth == 1) return moves.size.toLong()
        var nodes = 0L
        for (m in moves) {
            nodes += perft(applyMove(pos, m), opponent(color), depth - 1)
        }
        return nodes
    }

    private fun applyPlayedMove(s: ChessState, pos: Position, move: ChessMove) {
        val mover = s.sideToMove
        val moverName = context.players.getOrNull(playerIndexForColor(mover))?.displayName ?: "Player"
        val capturedType = if (move.enPassant) {
            pos.board[makeSquare(sqRow(move.from), sqCol(move.to))]?.type
        } else {
            pos.board[move.to]?.type
        }

        val newPos = applyMove(pos, move)
        val newSide = opponent(mover)
        val (legalNext, inCheckNext) = legalMovesAndCheck(newPos, newSide)
        val statusResult = when {
            legalNext.isNotEmpty() -> ChessResult.IN_PROGRESS
            !inCheckNext -> ChessResult.DRAW_STALEMATE
            // newSide has no moves and is in check -- checkmated by `mover`.
            mover == PieceColor.WHITE -> ChessResult.WHITE_WINS
            else -> ChessResult.BLACK_WINS
        }
        // Repetition only meaningfully applies to a position the game is still actually
        // continuing from -- a checkmate/stalemate this same move already ends the game
        // outright and never reaches a real 3rd repetition (the game would have ended the
        // first time that exact mate arose), so this is only checked/recorded when the status
        // check above didn't already end things. See this file's top comment and
        // recordPositionAndCheckRepetition's own KDoc.
        val result = if (statusResult == ChessResult.IN_PROGRESS && recordPositionAndCheckRepetition(newPos, newSide)) {
            ChessResult.DRAW_REPETITION
        } else {
            statusResult
        }
        val winnerPlayerId = when (result) {
            ChessResult.WHITE_WINS -> context.players.getOrNull(0)?.playerId
            ChessResult.BLACK_WINS -> context.players.getOrNull(1)?.playerId
            else -> null
        }

        val captureNote = when {
            capturedType != null -> " and captured a ${capturedType.name.lowercase()}"
            else -> ""
        }
        val suffix = when {
            result == ChessResult.WHITE_WINS || result == ChessResult.BLACK_WINS -> " -- checkmate!"
            result == ChessResult.DRAW_STALEMATE -> " -- stalemate, draw!"
            result == ChessResult.DRAW_REPETITION -> " -- draw by repetition!"
            inCheckNext -> " -- check!"
            else -> ""
        }
        val lastAction = "$moverName moved ${squareName(move.from)} to ${squareName(move.to)}$captureNote$suffix"

        state.value = ChessState(
            board = newPos.board,
            sideToMove = newSide,
            castleWK = newPos.castleWK, castleWQ = newPos.castleWQ,
            castleBK = newPos.castleBK, castleBQ = newPos.castleBQ,
            epSquare = newPos.epSquare,
            lastFrom = move.from,
            lastTo = move.to,
            lastAction = lastAction,
            roundOver = result != ChessResult.IN_PROGRESS,
            result = result,
            inCheck = inCheckNext,
            winnerPlayerId = winnerPlayerId
        )

        if (result != ChessResult.IN_PROGRESS) {
            when (result) {
                ChessResult.WHITE_WINS -> scoreP1.value += 1
                ChessResult.BLACK_WINS -> scoreP2.value += 1
                ChessResult.DRAW_STALEMATE -> draws.value += 1
                ChessResult.DRAW_REPETITION -> draws.value += 1
                else -> {}
            }
        }
        // One broadcast site covers every outcome (in-progress/checkmate/stalemate/
        // repetition) since they all funnel through this single state.value assignment
        // above -- unlike TicTacToeGame.kt's applyCellClicked, which has three separate
        // early-return branches each needing their own broadcast call.
        if (isNetworked && isHost) broadcastState()
    }

    // ---- Networked play (see ChessNetMessage.kt's own KDoc for the host-authoritative
    // design) -- everything below this point is only ever exercised when [isNetworked]. ----

    /** Host only: bundles the current visible state into a [ChessNetMessage.StateSync]
     *  and broadcasts it -- called after every host-side mutation ([applyPlayedMove]) and
     *  once from [startMatch] (which also covers [applyPlayAgain], since that delegates
     *  straight to [startMatch]). */
    private fun broadcastState() {
        val s = state.value ?: return
        stateVersion++
        val snapshot = ChessNetState(
            board = s.board,
            sideToMove = s.sideToMove,
            castleWK = s.castleWK, castleWQ = s.castleWQ, castleBK = s.castleBK, castleBQ = s.castleBQ,
            epSquare = s.epSquare,
            lastFrom = s.lastFrom, lastTo = s.lastTo,
            lastAction = s.lastAction,
            roundOver = s.roundOver,
            result = s.result,
            inCheck = s.inCheck,
            winnerPlayerId = s.winnerPlayerId,
            scoreP1 = scoreP1.value,
            scoreP2 = scoreP2.value,
            draws = draws.value,
            matchOver = matchOver.value
        )
        sendMessage(ChessNetMessage.StateSync(stateVersion, snapshot), toPlayerId = null)
    }

    /** Guest only: replaces every visible field with the host's own values -- the guest
     *  never computes any of this itself (no local move generation, no local repetition
     *  tracking), only ever displays the last [StateSync][ChessNetMessage.StateSync] it
     *  has, same as TicTacToeGame's own non-host devices. */
    private fun applyNetState(netState: ChessNetState) {
        state.value = ChessState(
            board = netState.board,
            sideToMove = netState.sideToMove,
            castleWK = netState.castleWK, castleWQ = netState.castleWQ,
            castleBK = netState.castleBK, castleBQ = netState.castleBQ,
            epSquare = netState.epSquare,
            lastFrom = netState.lastFrom, lastTo = netState.lastTo,
            lastAction = netState.lastAction,
            roundOver = netState.roundOver,
            result = netState.result,
            inCheck = netState.inCheck,
            winnerPlayerId = netState.winnerPlayerId
        )
        scoreP1.value = netState.scoreP1
        scoreP2.value = netState.scoreP2
        draws.value = netState.draws
        matchOver.value = netState.matchOver
    }

    /** Non-host only: sends [intent] to whichever player is at [GameContext.players] index 0
     *  -- the host, by the same lobby convention [isHost] itself relies on. */
    private fun sendToHost(intent: ChessIntentPayload) {
        sendMessage(ChessNetMessage.Intent(intent), toPlayerId = context.players.getOrNull(0)?.playerId)
    }

    /** Non-host only: same as [sendToHost] but for [ChessNetMessage.RequestState], which
     *  isn't wrapped in a [ChessNetMessage.Intent] (it's a lobby/sync concern, not a game
     *  move) -- mirrors [ChessNetMessage.RequestState] itself being a top-level variant
     *  rather than an intent payload. */
    private fun sendToHost(message: ChessNetMessage) {
        sendMessage(message, toPlayerId = context.players.getOrNull(0)?.playerId)
    }

    /** [toPlayerId] null broadcasts to every other connected player (see
     *  [com.gamesuite.transport.MultiplayerTransport.send]'s own KDoc) -- correct either
     *  way for this game's fixed 2-player cap, where "everyone else" is exactly one
     *  recipient. */
    private fun sendMessage(message: ChessNetMessage, toPlayerId: String?) {
        val localPlayerId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(message).encodeToByteArray()
        context.transport.send(fromPlayerId = localPlayerId, toPlayerId = toPlayerId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = runCatching { Json.decodeFromString<ChessNetMessage>(payload.decodeToString()) }.getOrNull() ?: return
        when (message) {
            is ChessNetMessage.StateSync -> {
                // The host is always authoritative over its own state -- an inbound
                // StateSync would only ever arrive here due to a bug or a malicious peer,
                // never as part of this protocol's own intended flow.
                if (isHost) return
                if (message.version <= lastAppliedStateVersion) return
                lastAppliedStateVersion = message.version
                applyNetState(message.state)
            }
            is ChessNetMessage.Intent -> {
                if (!isHost) return // only the host ever applies a peer's intent
                val senderIndex = context.players.indexOfFirst { it.playerId == fromPlayerId }
                if (senderIndex == -1) return // unknown sender -- ignore rather than trust a bare claimed identity
                val s = state.value
                when (val intent = message.intent) {
                    is ChessIntentPayload.PlayMove -> {
                        // Not this sender's actual turn -- ignore rather than trust the
                        // intent's own from/to squares blindly. applyPlayMove's own
                        // roundOver/matchOver/legal-move guards still apply on top of
                        // this, exactly as they do for a local tap.
                        if (s == null || senderIndex != playerIndexForColor(s.sideToMove)) return
                        applyPlayMove(senderIndex, intent.from, intent.to)
                    }
                    ChessIntentPayload.PlayAgain -> applyPlayAgain()
                }
            }
            ChessNetMessage.RequestState -> {
                if (isHost) broadcastState()
            }
        }
    }

    /** Board+side-to-move+castling-rights+en-passant signature for real threefold-repetition
     *  detection (see [recordPositionAndCheckRepetition]) -- piece encodings use ordinals, not
     *  letters, so KNIGHT and KING (which would collide on their shared first letter 'K')
     *  can't be confused with each other. */
    private fun positionSignature(pos: Position, sideToMove: PieceColor): String {
        val boardPart = pos.board.joinToString(",") { p -> if (p == null) "." else "${p.color.ordinal}${p.type.ordinal}" }
        return "$boardPart|${sideToMove.ordinal}|${pos.castleWK}${pos.castleWQ}${pos.castleBK}${pos.castleBQ}|${pos.epSquare}"
    }

    /**
     * Records `pos`/`sideToMove` as having just occurred and reports whether this is now the
     * THIRD time this exact position (see [positionSignature]) has occurred this game -- the
     * standard chess threefold-repetition rule. Deliberately real, stateful history (backed by
     * [positionHistory], not re-derivable from the current board alone the way
     * [currentResult]'s other checks are) -- this file's top comment used to list repetition as
     * an out-of-scope gap; this closes it, after this port's own self-play testing showed a
     * shared-best-move cycle between two bots really can repeat a position forever with no
     * repetition rule to end it.
     */
    private fun recordPositionAndCheckRepetition(pos: Position, sideToMove: PieceColor): Boolean {
        val signature = positionSignature(pos, sideToMove)
        val count = (positionHistory[signature] ?: 0) + 1
        positionHistory[signature] = count
        return count >= 3
    }

    companion object {
        /**
         * 4 plies deep. Chess's branching factor is roughly 35 legal moves in a typical
         * middlegame position, so an unpruned 4-ply search is on the order of 35^4 (~1.5M)
         * leaf evaluations; alpha-beta plus the capture-first move ordering in
         * [moveOrderScore] cuts real node counts well below that in practice.
         *
         * MEASURED (not guessed): ChessGameTest's own "HARD bot never makes an illegal move
         * across many self-play games" test runs HARD-vs-HARD at this exact depth across 15
         * full games (random opening, then real minimax play through the middlegame into
         * the endgame, up to 60 plies each) on the JVM this was verified with -- 15 games
         * completed in ~31 seconds wall-clock, averaging roughly 50ms per real move-decision
         * across that whole opening-to-endgame mix, with no single move anywhere near a
         * second. That comfortable margin (tens of ms, not hundreds) is why depth 4 was kept
         * as the shipped default rather than pushed further: this dev machine's JVM and a
         * phone's ART runtime aren't identical, and pushing to depth 5+ without being able to
         * directly measure ITS worst-case timing here (attempted, but this was a live,
         * actively-being-edited shared repo during this port and a concurrently-running build
         * elsewhere in it made a dedicated deeper-depth timing run impossible to complete
         * reliably) would be exactly the kind of unverified guess this project's own
         * measure-don't-guess standard argues against. depth 4's large, actually-observed
         * safety margin over the ESP32 C++ engine's fixed depth of 3 (itself constrained by a
         * 240MHz single-core Xtensa chip with no branch prediction or last-level cache) is a
         * real, if conservative, improvement -- a good follow-up would be to re-measure with
         * a dedicated timing test once the shared dev environment is quiet, to see whether
         * depth 5 is safe to ship too.
         */
        const val HARD_SEARCH_DEPTH = 4
    }
}
