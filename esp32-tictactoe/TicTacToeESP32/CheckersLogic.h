#pragma once
#include <Arduino.h>

// Pure game logic for Checkers (American/English draughts) -- no
// display/touch code here at all, mirroring GameLogic.h's split for
// Tic-Tac-Toe so this file can be unit-tested on a desktop compiler with
// zero changes (see native_test/checkers_playtest.cpp).
//
// Board coordinates are row/col, each 0..7. Only the 32 DARK squares
// (where (row + col) is odd) are ever occupied -- the 32 light squares are
// always CheckersPiece::EMPTY and simply never touched by move generation.
// Row 0 is the AI's home row (top of the board on screen) and its
// promotion row is 7; row 7 is the human's home row and its promotion row
// is 0 -- i.e. the two sides advance toward each other and each kings up on
// the far side, same as a real board.
//
// A "piece enum" here uses `enum class` (not a plain enum like GameLogic.h's
// Cell) specifically so its enumerators (EMPTY, HUMAN_MAN, ...) live in
// their own scope and can never collide with another game's same-named
// global constants when every game's headers end up included from the same
// .ino -- this project is being extended with several games at once.
enum class CheckersPiece : uint8_t { EMPTY = 0, HUMAN_MAN, HUMAN_KING, AI_MAN, AI_KING };

enum class CheckersResult : uint8_t { IN_PROGRESS, HUMAN_WINS, AI_WINS };

// A generous fixed upper bound on how many legal one-hop moves either side
// can ever have at once (12 pieces x 4 diagonal directions each, an upper
// bound no real position gets remotely close to) -- sized this way so move
// lists live on the stack with no dynamic allocation anywhere in this file.
static const uint8_t CHECKERS_MAX_MOVES = 48;

// One "hop": either a single non-capturing step, or a single jump that is
// one link of a (possibly multi-jump) capture sequence. A whole human turn
// is played by calling CheckersBoard::playHuman() once per hop -- again for
// each forced continuation the board reports -- and a whole AI turn is one
// CheckersBoard::playAi() call, which internally loops over every hop of
// its own turn, forced multi-jumps included.
struct CheckersMove {
    uint8_t fromRow, fromCol, toRow, toCol;
    bool isCapture;
    uint8_t capRow, capCol; // only meaningful when isCapture is true
};

class CheckersBoard {
public:
    void reset();

    CheckersPiece at(uint8_t row, uint8_t col) const { return board[row][col]; }
    bool isHumanTurn() const { return humanTurn; }
    CheckersResult result() const;

    uint8_t pieceCount(bool humanSide) const;

    // True (and writes the square) exactly while the side to move is in the
    // middle of a mandatory multi-jump: the SAME piece must jump again
    // before the turn can pass. While this is true, the only square with any
    // legal move is the one written here -- hasLegalMoveFrom()/isLegalMove()
    // already account for this on their own, so callers don't need to
    // special-case it, but the .ino needs this to know which square to keep
    // highlighted/forced between hops.
    bool inForcedContinuation(uint8_t &outRow, uint8_t &outCol) const;

    // Pure queries -- never mutate the board, safe to call on every touch
    // event. Both answer for whichever side is ACTUALLY to move right now
    // (so during the AI's turn they describe the AI's own options, not the
    // human's -- the .ino has no reason to call them then, since it already
    // ignores taps while it isn't the human's turn, same as Tic-Tac-Toe's
    // .ino does), and both already fold in the mandatory-capture rule and
    // any forced continuation above, so the .ino's two-step "tap source, tap
    // destination" flow needs no rules knowledge of its own:
    //   1) tap a square; if hasLegalMoveFrom() is true, treat it as the
    //      newly selected source (optionally highlight it, and light up its
    //      destinations via legalDestinationsFrom() below).
    //   2) tap another square; if isLegalMove(selected, tapped) is true,
    //      call playHuman(selected, tapped).
    bool isLegalMove(uint8_t fromRow, uint8_t fromCol, uint8_t toRow, uint8_t toCol) const;
    bool hasLegalMoveFrom(uint8_t row, uint8_t col) const;

    // Fills outRows[]/outCols[] (each must have room for CHECKERS_MAX_MOVES
    // entries -- see that constant) with every square (row,col) can legally
    // move or jump to right now, and returns how many were written. Purely a
    // convenience for highlighting legal destinations after a source square
    // is selected (see the "nice to have" note in Display) -- optional to
    // use at all.
    uint8_t legalDestinationsFrom(uint8_t row, uint8_t col, uint8_t outRows[], uint8_t outCols[]) const;

    // Returns EVERY legal one-hop move for whichever side is currently to
    // move in one call, as four parallel arrays (each must have room for
    // CHECKERS_MAX_MOVES entries). A single O(board size) query, unlike
    // scanning all 64 squares via hasLegalMoveFrom()/legalDestinationsFrom()
    // one at a time -- useful for anything that wants the WHOLE move list at
    // once (a random-move test harness, a future "hint" feature, ...)
    // rather than one square's worth.
    uint8_t allLegalMoves(uint8_t outFromRows[], uint8_t outFromCols[], uint8_t outToRows[], uint8_t outToCols[]) const;

    // Plays one hop for the human side. Returns false (no state change) if
    // illegal -- caller should ignore the tap rather than mutate anything,
    // same convention as GameLogic.h's playHuman(). On a capturing hop that
    // leaves the same piece in a forced continuation, isHumanTurn() stays
    // true and inForcedContinuation() reports the landing square instead of
    // handing off to the AI -- the .ino should keep prompting for another
    // destination tap rather than calling playAi().
    bool playHuman(uint8_t fromRow, uint8_t fromCol, uint8_t toRow, uint8_t toCol);

    // Plays the AI's entire turn -- every hop of a forced multi-jump chain
    // included -- via minimax with alpha-beta pruning (see .cpp for the
    // search depth and the reasoning behind it). No-op if it isn't currently
    // the AI's turn, or the round is already decided.
    void playAi();

    // The overall (first hop's source) -> (last hop's destination) of the
    // most recently completed playAi() call -- lets a caller animate the
    // piece sliding from where it started to where it ended, even though
    // playAi() itself already applied every hop of a multi-jump chain
    // internally before returning. This is an overall start/end, not a
    // per-hop trace: a multi-jump that zigzags across directions will
    // animate as one straight slide rather than visiting each intermediate
    // landing square -- a deliberate simplification, not a bug. Undefined
    // (zeroed) until the first playAi() call.
    void lastAiMove(uint8_t &outFromRow, uint8_t &outFromCol, uint8_t &outToRow, uint8_t &outToCol) const {
        outFromRow = lastAiFromRow; outFromCol = lastAiFromCol;
        outToRow = lastAiToRow; outToCol = lastAiToCol;
    }

    // ---- Test/setup hooks -----------------------------------------------
    // Everything below exists so native_test/checkers_playtest.cpp can build
    // hand-crafted mid-game positions (a forced multi-jump, a king capture,
    // ...) and drive a true random-vs-random self-play simulation, entirely
    // through public API rather than a friend-class or #ifdef backdoor into
    // this file. The shipped .ino has no reason to call any of these -- it
    // only ever starts a round from reset() and advances it via
    // playHuman()/playAi() -- but a hidden test-only seam would be worse for
    // a codebase other people extend than an ordinary, clearly-labeled
    // public method, so they're kept as normal members.

    // Clears every square to EMPTY (not the standard starting position --
    // see reset() for that), humanTurn=true, no forced continuation. Pairs
    // with setSquareForTest() to build a from-scratch custom position.
    void resetEmptyForTest();
    void setSquareForTest(uint8_t row, uint8_t col, CheckersPiece piece);
    // Also clears any forced-continuation bookkeeping, since that state
    // belongs to whichever side WAS to move and arbitrarily changing whose
    // turn it is would otherwise leave it dangling.
    void setTurnForTest(bool humanSide);

    // Plays one hop for WHICHEVER side is currently to move, under the exact
    // same legality/mandatory-capture/forced-continuation rules as
    // playHuman() -- unlike playHuman(), this does not require it to be the
    // human's turn. Used so a random-vs-random simulation can drive both
    // sides through one entry point; returns false (no state change) if
    // illegal, same convention as playHuman().
    bool playMoveForTest(uint8_t fromRow, uint8_t fromCol, uint8_t toRow, uint8_t toCol);

private:
    CheckersPiece board[8][8];
    bool humanTurn = true;
    bool hasForcedContinuation = false;
    uint8_t forcedRow = 0, forcedCol = 0;
    // See lastAiMove()'s comment -- written only by playAi().
    uint8_t lastAiFromRow = 0, lastAiFromCol = 0, lastAiToRow = 0, lastAiToCol = 0;

    // Appends every legal one-hop move for `humanSide` to out[] (capacity
    // CHECKERS_MAX_MOVES) and returns how many were written. Already
    // resolves the mandatory-capture rule (if ANY of this side's pieces can
    // jump, only jumps are returned, from every piece that has one -- not
    // just one arbitrary piece) and any forced continuation (if one is
    // active for this exact side-to-move, only that one piece's jumps are
    // returned). This is the single place all of that logic lives --
    // isLegalMove/hasLegalMoveFrom/legalDestinationsFrom/playHuman/playAi
    // and the AI search all go through it, so it can never disagree with
    // itself about what's legal.
    uint8_t generateMoves(bool humanSide, CheckersMove out[]) const;
    void appendJumpsFromSquare(uint8_t row, uint8_t col, CheckersMove out[], uint8_t &count) const;
    void appendSimpleMovesFromSquare(uint8_t row, uint8_t col, CheckersMove out[], uint8_t &count) const;

    // Does the piece sitting at (row,col) right now have at least one legal
    // jump from there, in any direction that piece type is allowed to jump?
    // Used both to decide mandatory capture (per side) and, after a jump, to
    // decide whether the same piece must continue (per square).
    bool squareHasJump(uint8_t row, uint8_t col) const;
    bool sideHasAnyCapture(bool humanSide) const;

    // Mutates the board to reflect one hop: moves the piece, clears a
    // captured piece if any, promotes on reaching the far row, and updates
    // humanTurn/hasForcedContinuation -- a piece that reaches its king row
    // stops there for the turn even mid multi-jump-chain (see .cpp), which
    // is the simplification this project takes on an otherwise
    // rules-debated edge case. Never validates `m` -- only ever called with
    // a move this same board's own generateMoves() just produced.
    void applyMove(const CheckersMove &m);

    // Minimax search rooted at *this* position (whichever side's turn it
    // actually is here), alpha-beta pruned. See .cpp for the score
    // convention (positive favors the AI) and the depth/heuristic choices.
    int minimaxSearch(int depthRemaining, int plyFromRoot, int alpha, int beta) const;
    CheckersMove findBestAiMove() const;
    static int evaluateHeuristic(const CheckersBoard &b, bool sideToMoveIsHuman, uint8_t sideToMoveMobility);
};
