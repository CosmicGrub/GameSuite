#include "ChessLogic.h"

// ---------------------------------------------------------------------------
// SCOPE -- what this engine does and does not implement (read this before
// changing anything below; matches this project's existing habit of naming
// what's NOT attempted rather than silently skipping it):
//
// IMPLEMENTED: full legal move generation for all six piece types, including
// pins (a move is only legal if it doesn't leave the mover's own king in
// check -- enforced uniformly by generateLegalMoves() actually making the
// move on a scratch copy and checking king safety, rather than a separate
// pin-detection pass); castling both sides with real rights tracking (lost
// on the king's own move, lost per-side when that side's rook moves from or
// is captured on its original corner square) and the standard legality
// checks (king not currently in check, not passing through or landing on an
// attacked square, all squares between king and rook empty); en passant;
// checkmate and stalemate detection.
//
// DRAW DETECTION: stalemate, insufficient material, and the 50-move
// (no-progress) rule are all implemented (see result() below). Insufficient
// material is deliberately limited to endings that are drawn IN PRINCIPLE --
// checkmate is literally impossible, not just impractical -- rather than a
// general "is this drawn with best play" solver: K vs K, K+(one lone minor
// piece) vs K, and same-colored-bishop-only endings (K+B vs K+B where both
// remaining bishops sit on the same square color, since neither bishop can
// ever contest the other's color complex) -- see isInsufficientMaterial()
// below for the exact rule. The 50-move rule counts consecutive half-moves
// since the last pawn move or capture (halfmoveClock in ChessLogic.h,
// maintained in applyMove() below) and resolves to a draw once it reaches
// 100 (50 full moves).
//
// NOT IMPLEMENTED (deliberately): threefold repetition remains out of
// scope. Detecting it needs a running history of every prior position (or
// at minimum a hash per position) so a recurrence can be recognized, and
// this engine keeps no such history -- ChessBoard only ever holds the
// CURRENT position, and the search below works by copying that single
// struct wholesale (see the "Chess's real state..." comment on ChessBoard's
// private search helpers in ChessLogic.h), so adding repetition tracking
// would mean threading a growing history through every one of those copies,
// not just adding one more field the way the 50-move counter above did.
// That's real, separable work, not a quick addition, so it's left as the
// one remaining gap here. Practically, this means a game that repeats the
// same position three times without ever tripping insufficient-material or
// the 50-move rule still sits at IN_PROGRESS -- possible in principle, but
// now the rare case rather than the common one in random self-play; see
// chess_playtest.cpp's random self-play section for the actual numbers.
//
// Pawn promotion always auto-promotes to a queen. There is no
// underpromotion path anywhere in this engine (playHuman() only ever
// recognizes the auto-queen destination as legal) -- a resistive touchscreen
// with only two taps per move ("pick up piece", "put it down") has no cheap
// place to also ask "which piece?" without a third interaction step, and
// auto-queen is strictly at least as good as any underpromotion in over
// 99% of real positions, so this project scopes out from the promotion-
// choice UI entirely rather than half-build it.
//
// AI: minimax with alpha-beta pruning, fixed search depth of 3 plies (the
// AI's move, the human's best reply, the AI's move again, then a static
// evaluation -- see AI_SEARCH_DEPTH below). Chess's branching factor is
// roughly 35 legal moves in a typical middlegame position, so an
// unpruned 3-ply search is on the order of 35^3 (~43,000) leaf
// evaluations; alpha-beta plus the cheap capture-first move ordering in
// orderMoves() cuts real node counts well below that in practice (a
// well-ordered alpha-beta search's best case approaches the square root of
// the unpruned count). native_test/chess_playtest.cpp reports the actual
// node count and wall-clock time for several real positions on the desktop
// compiler this was verified with; scaling that down to a 240MHz single-
// core Xtensa ESP32 (no branch prediction, no last-level cache the way a
// desktop x86 chip has, so each node costs meaningfully more clock cycles
// there, not just fewer clocks/second) is an estimate, not a hardware
// measurement -- this project has no ESP32 in the loop for this task, only
// the native desktop harness. If real hardware ends up too slow, dropping
// AI_SEARCH_DEPTH to 2 is a one-line change with no other code affected.
//
// EVALUATION: material using standard piece values, plus three cheap O(1)-
// per-piece positional terms -- center-square occupancy, minor-piece
// development off the back rank, and a small king-safety nudge (bonus for
// having castled, penalty for a king that's wandered off its own back
// rank). All three are pure coordinate checks with no extra move
// generation, so evaluate()'s cost stays flat no matter how many leaf nodes
// the search visits. Mobility (counting each side's actual legal moves) was
// deliberately left out even though it's a common positional term -- it
// would need a full generateLegalMoves() call per side per leaf, which
// would roughly triple the search's per-leaf cost, and this evaluation
// doesn't need to be strong, just good enough that the AI never hangs a
// piece for free (which capture-aware alpha-beta search already handles
// far more reliably than any positional term could).
// ---------------------------------------------------------------------------

static inline uint8_t sqRow(uint8_t sq) { return sq / 8; }
static inline uint8_t sqCol(uint8_t sq) { return sq % 8; }
static inline uint8_t makeSquare(uint8_t row, uint8_t col) { return row * 8 + col; }
static inline bool onBoard(int8_t row, int8_t col) { return row >= 0 && row < 8 && col >= 0 && col < 8; }
static inline uint8_t opponent(uint8_t color) { return color == CC_WHITE ? CC_BLACK : CC_WHITE; }

// Standard material values, indexed by ChessPieceType. King is 0 -- kings
// are never captured (the legal-move filter guarantees that), so they
// contribute nothing to material and this array is never even read for one.
static const int32_t PIECE_VALUE[7] = { 0, 100, 320, 330, 500, 900, 0 };

static const int32_t INF = 2000000000;       // comfortably above any real evaluate() or mate score
static const int32_t MATE_SCORE = 1000000;   // dwarfs any possible material score (max material both sides combined is well under 8000)
static const uint8_t AI_SEARCH_DEPTH = 3;    // see top-of-file comment

void ChessBoard::reset() {
    for (uint8_t i = 0; i < 64; i++) board[i] = ChessPiece{ CP_NONE, CC_NONE };

    static const uint8_t backRank[8] = { CP_ROOK, CP_KNIGHT, CP_BISHOP, CP_QUEEN, CP_KING, CP_BISHOP, CP_KNIGHT, CP_ROOK };
    for (uint8_t c = 0; c < 8; c++) {
        board[makeSquare(0, c)] = ChessPiece{ backRank[c], CC_WHITE };
        board[makeSquare(1, c)] = ChessPiece{ CP_PAWN, CC_WHITE };
        board[makeSquare(6, c)] = ChessPiece{ CP_PAWN, CC_BLACK };
        board[makeSquare(7, c)] = ChessPiece{ backRank[c], CC_BLACK };
    }

    sideToMove = CC_WHITE;
    castleWK = castleWQ = castleBK = castleBQ = true;
    epSquare = -1;
    lastFrom = lastTo = -1;
    lastCastleRookFrom = lastCastleRookTo = -1;
    lastEnPassantCapturedSquare = -1;
    halfmoveClock = 0;
}

// Pseudo-legal moves for `color`: every move that piece's normal movement
// rules allow given the current board occupancy, WITHOUT checking whether
// it leaves that color's own king in check (generateLegalMoves() below
// filters for that afterward, uniformly, for every move type). Castling is
// the one exception drawn into "pseudo" generation here anyway -- its own
// specific legality checks (king not in/through/into check, squares empty)
// are cheap and specific to castling alone, so they're applied inline
// rather than relying solely on the general post-hoc filter (which also
// still runs over these moves afterward regardless, as a second, redundant
// safety net).
uint8_t ChessBoard::generatePseudoMoves(uint8_t color, Move out[218]) const {
    uint8_t n = 0;
    uint8_t opp = opponent(color);

    auto push = [&](uint8_t from, uint8_t to, uint8_t promo, bool ep, int8_t castle) {
        out[n].from = from;
        out[n].to = to;
        out[n].promo = promo;
        out[n].enPassant = ep;
        out[n].castle = castle;
        n++;
    };
    auto pushPawn = [&](uint8_t from, uint8_t to) {
        uint8_t toRow = sqRow(to);
        bool promotes = (color == CC_WHITE && toRow == 7) || (color == CC_BLACK && toRow == 0);
        push(from, to, promotes ? (uint8_t)CP_QUEEN : (uint8_t)CP_NONE, false, 0);
    };
    auto slide = [&](uint8_t from, int8_t fr, int8_t fc, int8_t dr, int8_t dc) {
        int8_t rr = fr + dr, cc = fc + dc;
        while (onBoard(rr, cc)) {
            uint8_t to = makeSquare((uint8_t)rr, (uint8_t)cc);
            if (board[to].type == CP_NONE) {
                push(from, to, CP_NONE, false, 0);
            } else {
                if (board[to].color == opp) push(from, to, CP_NONE, false, 0);
                break; // blocked either way -- own piece or a captured enemy, can't slide past it
            }
            rr += dr; cc += dc;
        }
    };

    int8_t forward = (color == CC_WHITE) ? 1 : -1;
    uint8_t startRow = (color == CC_WHITE) ? 1 : 6;

    for (uint8_t from = 0; from < 64; from++) {
        ChessPiece p = board[from];
        if (p.color != color) continue;
        int8_t fr = sqRow(from), fc = sqCol(from);

        if (p.type == CP_PAWN) {
            int8_t r1 = fr + forward;
            if (onBoard(r1, fc) && board[makeSquare((uint8_t)r1, fc)].type == CP_NONE) {
                pushPawn(from, makeSquare((uint8_t)r1, fc));
                if (fr == startRow) {
                    int8_t r2 = fr + 2 * forward;
                    if (board[makeSquare((uint8_t)r2, fc)].type == CP_NONE) {
                        push(from, makeSquare((uint8_t)r2, fc), CP_NONE, false, 0);
                    }
                }
            }
            for (int8_t dc = -1; dc <= 1; dc += 2) {
                int8_t cr = fr + forward, cc = fc + dc;
                if (!onBoard(cr, cc)) continue;
                uint8_t to = makeSquare((uint8_t)cr, (uint8_t)cc);
                if (board[to].color == opp) {
                    pushPawn(from, to);
                } else if (epSquare >= 0 && (int8_t)to == epSquare) {
                    push(from, to, CP_NONE, true, 0); // en passant never promotes -- it always lands on rank 3 or 6
                }
            }
        } else if (p.type == CP_KNIGHT) {
            static const int8_t offs[8][2] = { {1,2},{2,1},{2,-1},{1,-2},{-1,-2},{-2,-1},{-2,1},{-1,2} };
            for (auto &o : offs) {
                int8_t rr = fr + o[0], cc = fc + o[1];
                if (!onBoard(rr, cc)) continue;
                uint8_t to = makeSquare((uint8_t)rr, (uint8_t)cc);
                if (board[to].color != color) push(from, to, CP_NONE, false, 0);
            }
        } else if (p.type == CP_BISHOP || p.type == CP_ROOK || p.type == CP_QUEEN) {
            static const int8_t rookDirs[4][2] = { {1,0},{-1,0},{0,1},{0,-1} };
            static const int8_t bishopDirs[4][2] = { {1,1},{1,-1},{-1,1},{-1,-1} };
            if (p.type == CP_ROOK || p.type == CP_QUEEN) {
                for (auto &d : rookDirs) slide(from, fr, fc, d[0], d[1]);
            }
            if (p.type == CP_BISHOP || p.type == CP_QUEEN) {
                for (auto &d : bishopDirs) slide(from, fr, fc, d[0], d[1]);
            }
        } else if (p.type == CP_KING) {
            for (int8_t dr = -1; dr <= 1; dr++) {
                for (int8_t dc = -1; dc <= 1; dc++) {
                    if (dr == 0 && dc == 0) continue;
                    int8_t rr = fr + dr, cc = fc + dc;
                    if (!onBoard(rr, cc)) continue;
                    uint8_t to = makeSquare((uint8_t)rr, (uint8_t)cc);
                    if (board[to].color != color) push(from, to, CP_NONE, false, 0);
                }
            }
            // Castling -- both the rights flag AND the rook actually still
            // sitting on its original corner are checked (the latter is
            // defensive: rights bookkeeping in applyMove() should already
            // guarantee it, but a "castle into an empty corner" bug would be
            // an unusually bad one to ship silently).
            if (color == CC_WHITE && from == 4) {
                if (castleWK && board[5].type == CP_NONE && board[6].type == CP_NONE &&
                    board[7].type == CP_ROOK && board[7].color == CC_WHITE &&
                    !isSquareAttacked(4, CC_BLACK) && !isSquareAttacked(5, CC_BLACK) && !isSquareAttacked(6, CC_BLACK)) {
                    push(4, 6, CP_NONE, false, 1);
                }
                if (castleWQ && board[3].type == CP_NONE && board[2].type == CP_NONE && board[1].type == CP_NONE &&
                    board[0].type == CP_ROOK && board[0].color == CC_WHITE &&
                    !isSquareAttacked(4, CC_BLACK) && !isSquareAttacked(3, CC_BLACK) && !isSquareAttacked(2, CC_BLACK)) {
                    push(4, 2, CP_NONE, false, -1);
                }
            } else if (color == CC_BLACK && from == 60) {
                if (castleBK && board[61].type == CP_NONE && board[62].type == CP_NONE &&
                    board[63].type == CP_ROOK && board[63].color == CC_BLACK &&
                    !isSquareAttacked(60, CC_WHITE) && !isSquareAttacked(61, CC_WHITE) && !isSquareAttacked(62, CC_WHITE)) {
                    push(60, 62, CP_NONE, false, 1);
                }
                if (castleBQ && board[59].type == CP_NONE && board[58].type == CP_NONE && board[57].type == CP_NONE &&
                    board[56].type == CP_ROOK && board[56].color == CC_BLACK &&
                    !isSquareAttacked(60, CC_WHITE) && !isSquareAttacked(59, CC_WHITE) && !isSquareAttacked(58, CC_WHITE)) {
                    push(60, 58, CP_NONE, false, -1);
                }
            }
        }
    }
    return n;
}

bool ChessBoard::isSquareAttacked(uint8_t square, uint8_t byColor) const {
    int8_t r = sqRow(square), c = sqCol(square);

    // Pawns: a byColor pawn attacks diagonally one rank in its own forward
    // direction, so `square` is attacked by one exactly when a byColor pawn
    // sits one rank BEHIND it (from byColor's own forward direction) and one
    // file to either side.
    int8_t pawnForward = (byColor == CC_WHITE) ? 1 : -1;
    int8_t pr = r - pawnForward;
    for (int8_t dc = -1; dc <= 1; dc += 2) {
        int8_t pc = c + dc;
        if (onBoard(pr, pc)) {
            ChessPiece p = board[makeSquare((uint8_t)pr, (uint8_t)pc)];
            if (p.type == CP_PAWN && p.color == byColor) return true;
        }
    }

    static const int8_t knightOffs[8][2] = { {1,2},{2,1},{2,-1},{1,-2},{-1,-2},{-2,-1},{-2,1},{-1,2} };
    for (auto &o : knightOffs) {
        int8_t rr = r + o[0], cc = c + o[1];
        if (onBoard(rr, cc)) {
            ChessPiece p = board[makeSquare((uint8_t)rr, (uint8_t)cc)];
            if (p.type == CP_KNIGHT && p.color == byColor) return true;
        }
    }

    for (int8_t dr = -1; dr <= 1; dr++) {
        for (int8_t dc = -1; dc <= 1; dc++) {
            if (dr == 0 && dc == 0) continue;
            int8_t rr = r + dr, cc = c + dc;
            if (onBoard(rr, cc)) {
                ChessPiece p = board[makeSquare((uint8_t)rr, (uint8_t)cc)];
                if (p.type == CP_KING && p.color == byColor) return true;
            }
        }
    }

    static const int8_t rookDirs[4][2] = { {1,0},{-1,0},{0,1},{0,-1} };
    for (auto &d : rookDirs) {
        int8_t rr = r + d[0], cc = c + d[1];
        while (onBoard(rr, cc)) {
            ChessPiece p = board[makeSquare((uint8_t)rr, (uint8_t)cc)];
            if (p.type != CP_NONE) {
                if (p.color == byColor && (p.type == CP_ROOK || p.type == CP_QUEEN)) return true;
                break;
            }
            rr += d[0]; cc += d[1];
        }
    }
    static const int8_t bishopDirs[4][2] = { {1,1},{1,-1},{-1,1},{-1,-1} };
    for (auto &d : bishopDirs) {
        int8_t rr = r + d[0], cc = c + d[1];
        while (onBoard(rr, cc)) {
            ChessPiece p = board[makeSquare((uint8_t)rr, (uint8_t)cc)];
            if (p.type != CP_NONE) {
                if (p.color == byColor && (p.type == CP_BISHOP || p.type == CP_QUEEN)) return true;
                break;
            }
            rr += d[0]; cc += d[1];
        }
    }

    return false;
}

// The single place pins/checks are actually enforced: try every pseudo-legal
// move on a scratch copy of the WHOLE board (not just the piece array -- a
// castling move needs the rook relocated too before this check means
// anything) and keep it only if the mover's own king ends up safe. Brute
// force, but correct for every move type uniformly with no special-cased pin
// detection to get subtly wrong, and cheap enough at this board size.
uint8_t ChessBoard::generateLegalMoves(uint8_t color, Move out[218]) const {
    Move pseudo[218];
    uint8_t pn = generatePseudoMoves(color, pseudo);
    uint8_t opp = opponent(color);
    uint8_t n = 0;
    for (uint8_t i = 0; i < pn; i++) {
        ChessBoard copy = *this;
        copy.applyMove(pseudo[i]);
        if (!copy.isSquareAttacked(copy.kingSquare(color), opp)) {
            out[n++] = pseudo[i];
        }
    }
    return n;
}

uint8_t ChessBoard::legalMovesAndCheck(Move out[218], bool &outInCheck) const {
    outInCheck = isSquareAttacked(kingSquare(sideToMove), opponent(sideToMove));
    return generateLegalMoves(sideToMove, out);
}

uint8_t ChessBoard::kingSquare(uint8_t color) const {
    for (uint8_t i = 0; i < 64; i++) {
        if (board[i].type == CP_KING && board[i].color == color) return i;
    }
    return 0; // unreachable in any position reached through playHuman/playAi/reset -- kings are never a legal capture target
}

void ChessBoard::applyMove(const Move &m) {
    ChessPiece moving = board[m.from];
    uint8_t color = moving.color;

    // Captured here means "captured by this move," read BEFORE any square
    // gets overwritten below -- a normal capture lands directly on m.to (so
    // it's occupied by an enemy piece right now), while en passant's capture
    // is flagged separately since its victim sits beside m.to, not on it
    // (see the comment right below).
    bool isCapture = m.enPassant || board[m.to].type != CP_NONE;

    // En passant's captured pawn sits beside the mover, not on the
    // destination square -- that's exactly why the destination is empty
    // even though this is a capture. Recorded for lastMoveWasEnPassant()
    // before the square is cleared, so a caller animating this move can
    // find it later without re-deriving it (it isn't the geometric midpoint
    // of anything -- see that function's comment).
    lastEnPassantCapturedSquare = -1;
    if (m.enPassant) {
        uint8_t capturedSq = makeSquare(sqRow(m.from), sqCol(m.to));
        board[capturedSq] = ChessPiece{ CP_NONE, CC_NONE };
        lastEnPassantCapturedSquare = (int8_t)capturedSq;
    }

    board[m.to] = moving;
    board[m.from] = ChessPiece{ CP_NONE, CC_NONE };
    if (m.promo != CP_NONE) board[m.to].type = m.promo;

    // Castling: the king's own move was just applied above via the generic
    // from/to copy; relocate the matching rook to complete it. Recorded for
    // lastMoveWasCastle() -- reusing these exact values rather than having
    // that accessor re-derive them from color/direction, so the two can
    // never disagree about which rook actually moved.
    lastCastleRookFrom = lastCastleRookTo = -1;
    if (m.castle == 1) {
        uint8_t rookFrom = (color == CC_WHITE) ? 7 : 63;
        uint8_t rookTo   = (color == CC_WHITE) ? 5 : 61;
        board[rookTo] = board[rookFrom];
        board[rookFrom] = ChessPiece{ CP_NONE, CC_NONE };
        lastCastleRookFrom = (int8_t)rookFrom;
        lastCastleRookTo = (int8_t)rookTo;
    } else if (m.castle == -1) {
        uint8_t rookFrom = (color == CC_WHITE) ? 0 : 56;
        uint8_t rookTo   = (color == CC_WHITE) ? 3 : 59;
        board[rookTo] = board[rookFrom];
        board[rookFrom] = ChessPiece{ CP_NONE, CC_NONE };
        lastCastleRookFrom = (int8_t)rookFrom;
        lastCastleRookTo = (int8_t)rookTo;
    }

    if (moving.type == CP_KING) {
        if (color == CC_WHITE) { castleWK = false; castleWQ = false; }
        else                   { castleBK = false; castleBQ = false; }
    }
    // Corner-square checks, independent of what piece is involved -- this
    // one uniform rule covers both "that side's rook just moved away" and
    // "that rook just got captured by anything, from anywhere."
    if (m.from == 0  || m.to == 0)  castleWQ = false; // a1
    if (m.from == 7  || m.to == 7)  castleWK = false; // h1
    if (m.from == 56 || m.to == 56) castleBQ = false; // a8
    if (m.from == 63 || m.to == 63) castleBK = false; // h8

    epSquare = -1;
    if (moving.type == CP_PAWN) {
        int8_t fr = sqRow(m.from), tr = sqRow(m.to);
        int8_t diff = tr - fr;
        if (diff == 2 || diff == -2) {
            epSquare = makeSquare((uint8_t)((fr + tr) / 2), sqCol(m.from));
        }
    }

    // 50-move (no-progress) rule bookkeeping: a pawn move or a capture
    // resets the clock (either is "progress" toward eventually forcing a
    // result), anything else -- including castling and quiet king/piece
    // moves -- just increments it. Checked here, the one place every move of
    // every kind already funnels through, rather than duplicated at each
    // call site; see result() for where this actually resolves to a draw.
    if (moving.type == CP_PAWN || isCapture) halfmoveClock = 0;
    else halfmoveClock++;

    lastFrom = m.from;
    lastTo = m.to;
    sideToMove = opponent(color);
}

// Insufficient material: a position where checkmate is IMPOSSIBLE for either
// side no matter how play continues, because neither side retains enough
// force to construct one. Deliberately limited to the well-known endings
// that are drawn IN PRINCIPLE -- K vs K, K+(one lone knight or bishop) vs K,
// and same-colored-bishop-only endings (K+B vs K+B where both bishops sit on
// the same square color, since same-colored bishops can never contest each
// other's color complex) -- rather than a general "is this drawn with best
// play" solver. Anything with a pawn, a rook, a queen, or more than one
// minor piece on the stronger side is left as sufficient material even in
// endings that are drawn in PRACTICE (K+B+N vs K, say, or opposite-colored
// bishops with no other pieces) -- this only catches endings where a mate is
// flatly impossible to construct, matching exactly the four cases named in
// this feature's own scope.
bool ChessBoard::isInsufficientMaterial() const {
    uint8_t whiteCount = 0, blackCount = 0;
    uint8_t whiteType = CP_NONE, blackType = CP_NONE;
    uint8_t whiteSquare = 0, blackSquare = 0;
    for (uint8_t sq = 0; sq < 64; sq++) {
        ChessPiece p = board[sq];
        if (p.type == CP_NONE || p.type == CP_KING) continue;
        if (p.color == CC_WHITE) { whiteCount++; whiteType = p.type; whiteSquare = sq; }
        else                     { blackCount++; blackType = p.type; blackSquare = sq; }
    }

    if (whiteCount == 0 && blackCount == 0) return true; // K vs K

    if (blackCount == 0 && whiteCount == 1 && (whiteType == CP_KNIGHT || whiteType == CP_BISHOP)) return true; // K+N/B vs K
    if (whiteCount == 0 && blackCount == 1 && (blackType == CP_KNIGHT || blackType == CP_BISHOP)) return true; // K vs K+N/B

    if (whiteCount == 1 && blackCount == 1 && whiteType == CP_BISHOP && blackType == CP_BISHOP) {
        bool whiteLight = (bool)((sqRow(whiteSquare) + sqCol(whiteSquare)) & 1);
        bool blackLight = (bool)((sqRow(blackSquare) + sqCol(blackSquare)) & 1);
        if (whiteLight == blackLight) return true; // K+B vs K+B, same-colored bishops
    }

    return false;
}

ChessRoundResult ChessBoard::result() const {
    Move moves[218];
    bool inCheckNow;
    uint8_t n = legalMovesAndCheck(moves, inCheckNow);
    if (n > 0) {
        // Both draw checks below only apply while the game would otherwise
        // still be going -- a side with no legal moves is checkmated or
        // stalemated regardless of material or move-count, handled further
        // down.
        if (isInsufficientMaterial()) return ChessRoundResult::DRAW_INSUFFICIENT_MATERIAL;
        if (halfmoveClock >= 100) return ChessRoundResult::DRAW_FIFTY_MOVE_RULE;
        return ChessRoundResult::IN_PROGRESS;
    }
    if (!inCheckNow) return ChessRoundResult::DRAW_STALEMATE;
    // sideToMove has no legal moves and is in check -- checkmated.
    return (sideToMove == CC_WHITE) ? ChessRoundResult::AI_WINS : ChessRoundResult::HUMAN_WINS;
}

bool ChessBoard::inCheck() const {
    return isSquareAttacked(kingSquare(sideToMove), opponent(sideToMove));
}

uint8_t ChessBoard::legalDestinations(uint8_t from, uint8_t outSquares[32]) const {
    if (from >= 64 || board[from].type == CP_NONE || board[from].color != sideToMove) return 0;
    if (result() != ChessRoundResult::IN_PROGRESS) return 0;
    Move moves[218];
    uint8_t n = generateLegalMoves(sideToMove, moves);
    uint8_t count = 0;
    for (uint8_t i = 0; i < n; i++) {
        if (moves[i].from == from) outSquares[count++] = moves[i].to;
    }
    return count;
}

bool ChessBoard::playHuman(uint8_t from, uint8_t to) {
    if (sideToMove != CC_WHITE) return false;
    return playMove(from, to); // not among CC_WHITE's legal moves right now -- silently rejected, same as any illegal tap elsewhere in this project
}

uint8_t ChessBoard::legalMoveList(uint8_t outFrom[218], uint8_t outTo[218]) const {
    Move moves[218];
    uint8_t n = generateLegalMoves(sideToMove, moves);
    for (uint8_t i = 0; i < n; i++) {
        outFrom[i] = moves[i].from;
        outTo[i] = moves[i].to;
    }
    return n;
}

bool ChessBoard::playMove(uint8_t from, uint8_t to) {
    if (from >= 64 || to >= 64) return false;
    if (result() != ChessRoundResult::IN_PROGRESS) return false;

    Move moves[218];
    uint8_t n = generateLegalMoves(sideToMove, moves);
    for (uint8_t i = 0; i < n; i++) {
        if (moves[i].from == from && moves[i].to == to) {
            applyMove(moves[i]);
            return true;
        }
    }
    return false;
}

void ChessBoard::setupEmpty() {
    for (uint8_t i = 0; i < 64; i++) board[i] = ChessPiece{ CP_NONE, CC_NONE };
    sideToMove = CC_WHITE;
    castleWK = castleWQ = castleBK = castleBQ = false;
    epSquare = -1;
    lastFrom = lastTo = -1;
    lastCastleRookFrom = lastCastleRookTo = -1;
    lastEnPassantCapturedSquare = -1;
    halfmoveClock = 0;
}

void ChessBoard::setPiece(uint8_t square, uint8_t type, uint8_t color) {
    if (square >= 64) return;
    board[square] = ChessPiece{ type, color };
}

void ChessBoard::setSideToMove(uint8_t color) { sideToMove = color; }

void ChessBoard::setCastlingRights(bool wk, bool wq, bool bk, bool bq) {
    castleWK = wk; castleWQ = wq; castleBK = bk; castleBQ = bq;
}

void ChessBoard::setEnPassantSquare(int8_t square) { epSquare = square; }

void ChessBoard::playAi() {
    if (sideToMove != CC_BLACK) return;
    if (result() != ChessRoundResult::IN_PROGRESS) return;

    Move moves[218];
    uint8_t n = generateLegalMoves(CC_BLACK, moves);
    if (n == 0) return; // unreachable given the result() check above -- kept defensive rather than assumed
    orderMoves(moves, n, board);

    int32_t alpha = -INF, beta = INF;
    int32_t bestScore = -INF;
    uint8_t bestIndex = 0;
    for (uint8_t i = 0; i < n; i++) {
        ChessBoard copy = *this;
        copy.applyMove(moves[i]);
        int32_t score = copy.search(AI_SEARCH_DEPTH - 1, alpha, beta);
        if (score > bestScore) { bestScore = score; bestIndex = i; }
        if (bestScore > alpha) alpha = bestScore;
    }
    applyMove(moves[bestIndex]);
}

// Cheap MVV-style move ordering: score a move by whatever it captures (0 for
// a quiet move) plus a flat bonus for promotions, then sort descending --
// enough to make alpha-beta prune far more effectively than the raw
// generation order would, without the extra bookkeeping a full MVV-LVA
// (which also weighs the CAPTURING piece) would need.
void ChessBoard::orderMoves(Move moves[], uint8_t count, const ChessPiece board[64]) {
    int16_t scores[218];
    for (uint8_t i = 0; i < count; i++) {
        int16_t s = 0;
        ChessPiece captured = board[moves[i].to];
        if (captured.type != CP_NONE) s += (int16_t)PIECE_VALUE[captured.type];
        else if (moves[i].enPassant) s += (int16_t)PIECE_VALUE[CP_PAWN];
        if (moves[i].promo != CP_NONE) s += 800;
        scores[i] = s;
    }
    // Insertion sort, descending -- count is at most 218 and this runs once
    // per search node, so plain O(n^2) here isn't worth replacing.
    for (uint8_t i = 1; i < count; i++) {
        Move mv = moves[i];
        int16_t sv = scores[i];
        int16_t j = (int16_t)i - 1;
        while (j >= 0 && scores[j] < sv) {
            moves[j + 1] = moves[j];
            scores[j + 1] = scores[j];
            j--;
        }
        moves[j + 1] = mv;
        scores[j + 1] = sv;
    }
}

int32_t ChessBoard::evaluate(const ChessBoard &b) {
    int32_t score = 0;
    for (uint8_t sq = 0; sq < 64; sq++) {
        ChessPiece p = b.board[sq];
        if (p.type == CP_NONE) continue;
        // Positive favors the AI (Black), matching GameLogic.cpp's own
        // "AI is the maximizer" convention exactly.
        int8_t sign = (p.color == CC_BLACK) ? 1 : -1;
        score += sign * PIECE_VALUE[p.type];

        int8_t r = sqRow(sq), c = sqCol(sq);
        bool centerSquare = (r == 3 || r == 4) && (c == 3 || c == 4); // d4,d5,e4,e5
        if (centerSquare && (p.type == CP_PAWN || p.type == CP_KNIGHT || p.type == CP_BISHOP)) {
            score += sign * 15;
        }

        // Development: a knight/bishop off its own back rank is doing
        // something, nudging the AI away from shuffling pawns forever.
        if (p.type == CP_KNIGHT || p.type == CP_BISHOP) {
            uint8_t homeRow = (p.color == CC_WHITE) ? 0 : 7;
            if (r != homeRow) score += sign * 10;
        }

        // King safety: reward having castled, penalize a king that has
        // wandered off its own back rank into the middle of the board.
        if (p.type == CP_KING) {
            uint8_t homeRow = (p.color == CC_WHITE) ? 0 : 7;
            bool castledSquare = (r == homeRow) && (c == 6 || c == 2);
            bool wanderedOut = (r != homeRow);
            if (castledSquare) score += sign * 20;
            if (wanderedOut) score += sign * -25;
        }
    }
    return score;
}

int32_t ChessBoard::search(uint8_t depthLeft, int32_t alpha, int32_t beta) const {
    Move moves[218];
    bool inCheckNow;
    uint8_t n = legalMovesAndCheck(moves, inCheckNow);

    if (n == 0) {
        if (!inCheckNow) return 0; // stalemate
        // sideToMove is checkmated here -- bad for that color. The
        // +/-depthLeft term prefers a mate found with MORE depth remaining
        // (i.e. sooner from the root) when it's good, and prefers to delay
        // one found sooner when it's bad -- the exact same "win fast, lose
        // slow" shape as GameLogic.cpp's minimaxOn, just keyed off
        // depth-remaining instead of depth-used.
        return (sideToMove == CC_BLACK) ? (-MATE_SCORE - depthLeft) : (MATE_SCORE + depthLeft);
    }
    if (depthLeft == 0) return evaluate(*this);

    orderMoves(moves, n, board);
    bool maximizing = (sideToMove == CC_BLACK); // AI maximizes, human minimizes
    int32_t best = maximizing ? -INF : INF;
    for (uint8_t i = 0; i < n; i++) {
        ChessBoard copy = *this;
        copy.applyMove(moves[i]);
        int32_t score = copy.search(depthLeft - 1, alpha, beta);
        if (maximizing) {
            if (score > best) best = score;
            if (best > alpha) alpha = best;
        } else {
            if (score < best) best = score;
            if (best < beta) beta = best;
        }
        if (beta <= alpha) break; // alpha-beta cutoff
    }
    return best;
}
