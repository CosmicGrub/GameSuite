#pragma once
#include <Arduino.h>

// Pure chess rules engine + AI -- no display/touch code here at all, mirroring
// the same engine/screen split GameLogic.h/.cpp uses for Tic-Tac-Toe (and the
// same split GameSuite's Android app uses throughout): this file could be
// unit-tested on a desktop compiler with zero changes, same spirit as
// native_test/playtest.cpp already does for GameLogic.h/.cpp -- see
// native_test/chess_playtest.cpp for this game's equivalent.
//
// Names in this header are all "Chess"-prefixed (ChessBoard, ChessPiece,
// ChessColor, ...) rather than the short generic names GameLogic.h uses
// (Cell, EMPTY, HUMAN, AI, RoundResult) on purpose: another game (with its
// own Logic/Display pair) is being added to this same shared project at the
// same time, and a plain global `enum Piece` or `enum class RoundResult`
// here would collide with GameLogic.h's own globals -- or that other game's
// -- the moment TicTacToeESP32.ino ends up #include-ing everything together.
//
// Square indexing: 0..63, square = rank*8 + file, where rank 0 is White's
// home rank and file 0 is the a-file -- i.e. a1=0, h1=7, a8=56, h8=63. This
// is the same little-endian rank-file convention most chess engines use.
// ChessDisplay.cpp maps these to screen pixels (and flips the rank so White's
// home rank draws at the bottom of the screen, the way a human player expects
// to see their own side).

enum ChessPieceType : uint8_t { CP_NONE = 0, CP_PAWN, CP_KNIGHT, CP_BISHOP, CP_ROOK, CP_QUEEN, CP_KING };
enum ChessColor : uint8_t { CC_NONE = 0, CC_WHITE, CC_BLACK };

struct ChessPiece {
    uint8_t type;  // ChessPieceType
    uint8_t color; // ChessColor -- CC_NONE whenever type == CP_NONE
};

// Draw detection covers stalemate, insufficient material, and the 50-move
// (no-progress) rule -- see ChessLogic.cpp's top comment and
// isInsufficientMaterial() for exactly what each catches. Threefold
// repetition remains out of scope (it needs a running position history this
// engine doesn't keep -- see the same top comment). Each draw cause gets its
// own specifically-named enum variant rather than a generic DRAW so that any
// draw type NOT yet detected stays visible at every call site instead of
// silently reading as "draws are handled."
enum class ChessRoundResult : uint8_t { IN_PROGRESS, HUMAN_WINS, AI_WINS, DRAW_STALEMATE, DRAW_INSUFFICIENT_MATERIAL, DRAW_FIFTY_MOVE_RULE };

// Human always plays White and moves first, matching this project's "human
// goes first" convention from Tic-Tac-Toe; the AI always plays Black.
class ChessBoard {
public:
    void reset(); // standard starting position, White (human) to move, full castling rights

    // Attempts to play the human's (White) move from `from` to `to` (both
    // 0..63 square indices, see indexing note above). Returns false and
    // leaves the board completely unchanged if the move is illegal for ANY
    // reason -- wrong piece owner, not human's turn, game already over, not
    // among that piece's legal destinations, or it would leave the human's
    // own king in check (pins included) -- the caller should just ignore the
    // tap on false, same as GameSuite's own games do for any illegal tap.
    //
    // Promotion is always auto-queen: a pawn move that reaches the back rank
    // is legal exactly when moving a queen there would be, and always
    // arrives as a queen. There is no underpromotion path -- see the note in
    // ChessLogic.cpp about why a promotion-choice UI wasn't built.
    //
    // Castling is requested the standard way: move the KING two squares
    // toward the rook (e1->g1 for White kingside, e1->c1 for White
    // queenside, mirrored for Black) -- exactly the gesture a two-tap
    // "select piece, tap destination" touch UI already produces naturally,
    // no separate castle button needed. The rook is relocated automatically.
    bool playHuman(uint8_t from, uint8_t to);

    // Picks and plays the AI's (Black) move via minimax + alpha-beta search
    // (see ChessLogic.cpp for the fixed search depth and why). No-op if it
    // isn't currently the AI's turn, or the game is already over.
    void playAi();

    ChessPiece at(uint8_t square) const { return board[square]; }
    ChessRoundResult result() const;
    bool isHumanTurn() const { return sideToMove == CC_WHITE; }

    // True exactly when the side to move is currently in check. Purely
    // informational (for ChessDisplay's check highlight) -- legality is
    // already fully enforced elsewhere, so this never affects what
    // playHuman/playAi will accept.
    bool inCheck() const;

    // Fills outSquares (must have room for at least 32 -- the real maximum
    // for any single piece, a queen at the board's center, is 27) with the
    // legal destination squares for the piece sitting at `from`, for
    // ChessDisplay's "tap a piece, see its legal moves highlighted" step of
    // the two-tap interaction. Returns the count written (0 if `from` holds
    // no piece, holds a piece of the side NOT to move, or the game is over).
    uint8_t legalDestinations(uint8_t from, uint8_t outSquares[32]) const;

    // The (from, to) squares of the most recently played move this round, or
    // (-1, -1) before any move has been played -- ChessDisplay uses this to
    // highlight the last move, the same spirit as GameLogic.h's
    // winningLine().
    void lastMove(int8_t &outFrom, int8_t &outTo) const { outFrom = lastFrom; outTo = lastTo; }

    // True exactly when the most recently played move was a castle, in which
    // case it writes the ROOK's own (from, to) squares -- the king's own
    // (from, to) are already lastMove() above, since castling is requested
    // and reported as the king's own two-square move (see playHuman()'s
    // comment). A caller animating the move needs this because castling
    // relocates TWO pieces in one turn, not one -- see
    // ChessDisplay.h/animateChessMove(). False (params left unwritten) for
    // every other move, including before any move has been played.
    bool lastMoveWasCastle(uint8_t &outRookFrom, uint8_t &outRookTo) const {
        if (lastCastleRookFrom < 0) return false;
        outRookFrom = (uint8_t)lastCastleRookFrom;
        outRookTo = (uint8_t)lastCastleRookTo;
        return true;
    }

    // True exactly when the most recently played move captured en passant,
    // in which case it writes the captured pawn's own square -- which is NOT
    // lastMove()'s `to` square (en passant's victim sits beside the capturing
    // pawn's landing square, on the SAME rank it started from -- see
    // applyMove() in the .cpp) and so can't be derived geometrically the way
    // a checkers jump's capture square can (see CheckersDisplay.h's
    // checkersJumpMidpoint() for that contrast). A caller animating the move
    // needs this explicit square rather than an interpolated one -- see
    // ChessDisplay.h/animateChessMove(). False (param left unwritten) for
    // every other move, including before any move has been played.
    bool lastMoveWasEnPassant(uint8_t &outCapturedSquare) const {
        if (lastEnPassantCapturedSquare < 0) return false;
        outCapturedSquare = (uint8_t)lastEnPassantCapturedSquare;
        return true;
    }

    // ---- Test/verification seam (not part of the on-device UI flow) ----
    // playHuman()/playAi() are deliberately restricted to "whichever side
    // they say they are" -- exactly right for the real UI, but native_test's
    // perft-style move-count verification and random-vs-random self-play
    // (see native_test/chess_playtest.cpp) need to enumerate and play ANY
    // legal move for WHICHEVER side actually has the move, including
    // Black's, without going through the AI's search. These two functions
    // give tests that access through the exact same rules engine (they call
    // the same generateLegalMoves()/applyMove() playHuman() and playAi() use
    // internally) rather than a separate, potentially-diverging test-only
    // path -- playHuman() itself is now just playMove() plus the "it must be
    // White's turn" restriction (see ChessLogic.cpp).

    // Fills outFrom/outTo (parallel arrays, room for at least 218 -- the
    // maximum legal move count in any real chess position) with every legal
    // (from, to) pair for whichever side currently has the move. Returns the
    // count written.
    uint8_t legalMoveList(uint8_t outFrom[218], uint8_t outTo[218]) const;

    // Plays the (from, to) move for whichever side currently has the move,
    // with no restriction on which color that is. Returns false (board
    // unchanged) if it isn't among that side's legal moves right now, or the
    // game is already over.
    bool playMove(uint8_t from, uint8_t to);

    // Direct position construction, bypassing reset()'s standard setup and
    // playMove()'s legality checks entirely -- used ONLY by
    // native_test/chess_playtest.cpp to build exact positions (a specific
    // checkmate, stalemate, en-passant, promotion, or castling-rights
    // scenario) with certainty, instead of trusting a long hand-written
    // sequence of opening moves to reach one without a transcription
    // mistake. No real code path (reset()/playHuman()/playAi()) calls these
    // -- reset() remains the only way an actual game is ever initialized.
    void setupEmpty(); // clears every square, clears all castling rights, clears en passant, sets White to move
    void setPiece(uint8_t square, uint8_t type, uint8_t color); // ChessPieceType/ChessColor
    void setSideToMove(uint8_t color);
    void setCastlingRights(bool wk, bool wq, bool bk, bool bq);
    void setEnPassantSquare(int8_t square); // -1 for none

private:
    // Plainly-declared state (not hidden behind an opaque blob) -- same
    // spirit as TicTacToeBoard's own `uint8_t board[9]` sitting right here in
    // the header, just with the extra few fields real chess needs beyond a
    // flat array of cells.
    ChessPiece board[64];
    uint8_t sideToMove = CC_WHITE; // ChessColor
    bool castleWK = true, castleWQ = true, castleBK = true, castleBQ = true;
    int8_t epSquare = -1;   // en-passant target square, -1 if none currently available
    int8_t lastFrom = -1, lastTo = -1;
    // See lastMoveWasCastle()/lastMoveWasEnPassant()'s comments -- written
    // only by applyMove(), reusing the exact same rook-square/captured-square
    // values it already computes for the move itself so these can never
    // disagree with what applyMove() actually did. -1 (not that kind of
    // move) unless the move just played was a castle / en passant capture,
    // respectively.
    int8_t lastCastleRookFrom = -1, lastCastleRookTo = -1;
    int8_t lastEnPassantCapturedSquare = -1;
    // 50-move (no-progress) rule bookkeeping: consecutive half-moves since
    // the last pawn move or capture -- reset to 0 on either, incremented
    // otherwise, entirely inside applyMove(). Reaching 100 (50 full moves)
    // resolves to DRAW_FIFTY_MOVE_RULE in result(); see ChessLogic.cpp.
    uint8_t halfmoveClock = 0;

    // Internal move representation -- deliberately never exposed publicly:
    // every external caller only ever needs a plain (from, to) pair (auto-
    // queen promotion needs no extra input; castling is expressed as the
    // king's own two-square move; see playHuman's comment above).
    struct Move {
        uint8_t from, to;
        uint8_t promo;      // ChessPieceType -- CP_QUEEN on a promoting move, CP_NONE otherwise
        bool enPassant;     // true if this move captures en passant
        int8_t castle;      // 0 = not a castle, +1 = kingside, -1 = queenside
    };

    // Chess's real state (castling rights, en-passant target) is too rich
    // for GameLogic.cpp's "free function over a bare uint8_t[9] scratch
    // array" trick to fit naturally, so the search below instead works over
    // explicit ChessBoard-by-value copies via these private member
    // functions -- every recursive step operates on its own local copy and
    // never mutates the caller's board, which is the same "scratch copy,
    // caller's board never observed mid-search" guarantee GameLogic.cpp's
    // minimaxOn documents, just expressed with copies of `*this` instead of
    // a raw array.
    uint8_t generatePseudoMoves(uint8_t color, Move out[218]) const;
    uint8_t generateLegalMoves(uint8_t color, Move out[218]) const;
    // Generates legal moves for sideToMove AND reports whether sideToMove is
    // currently in check in one pass (both result() and search() need both
    // facts together, so this avoids scanning the board twice per call).
    uint8_t legalMovesAndCheck(Move out[218], bool &outInCheck) const;
    bool isSquareAttacked(uint8_t square, uint8_t byColor) const;
    uint8_t kingSquare(uint8_t color) const;
    // True when neither side retains enough force to construct a checkmate
    // no matter how play continues -- see ChessLogic.cpp for exactly which
    // endings this catches.
    bool isInsufficientMaterial() const;
    void applyMove(const Move &m);
    // `board` is passed explicitly (rather than reading `this->board`) so
    // this stays a plain, side-effect-free sort helper -- it never needs to
    // be anything but static.
    static void orderMoves(Move moves[], uint8_t count, const ChessPiece board[64]);
    static int32_t evaluate(const ChessBoard &b);
    int32_t search(uint8_t depthLeft, int32_t alpha, int32_t beta) const;
};
