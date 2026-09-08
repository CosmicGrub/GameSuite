#include "ChessDisplay.h"
#include "Config.h"
#include "Chrome.h"

// Palette restricted to TFT_eSPI's own named constants Display.cpp/
// CheckersDisplay.cpp already use, so the whole arcade reads as one
// consistent product rather than each game inventing its own colors.
static const uint16_t COLOR_BG           = TFT_BLACK;
static const uint16_t COLOR_LIGHT_SQ     = TFT_WHITE;
static const uint16_t COLOR_DARK_SQ      = TFT_DARKGREEN;
static const uint16_t COLOR_WHITE_PIECE  = TFT_CYAN;   // human
static const uint16_t COLOR_BLACK_PIECE  = TFT_ORANGE; // AI
static const uint16_t COLOR_SELECTED     = TFT_CYAN;   // matches the human's own piece color
static const uint16_t COLOR_LEGAL_MARK   = TFT_GREEN;
static const uint16_t COLOR_LAST_MOVE    = TFT_DARKGREY;
static const uint16_t COLOR_CHECK        = TFT_ORANGE;
static const uint16_t COLOR_BUTTON_BG    = TFT_DARKGREEN;
static const uint16_t COLOR_BUTTON_TEXT  = TFT_WHITE;

static inline uint8_t rowOf(uint8_t sq) { return sq / 8; }
static inline uint8_t colOf(uint8_t sq) { return sq % 8; }

// Screen orientation: White's home rank (row 0) at the bottom, a-file (col 0)
// on the left -- see ChessDisplay.h's top comment.
static void squareToScreen(const ChessLayout &l, uint8_t row, uint8_t col, int16_t &x, int16_t &y) {
    x = l.boardX + col * l.squareSize;
    y = l.boardY + (7 - row) * l.squareSize;
}

static char pieceChar(uint8_t type) {
    switch (type) {
        case CP_PAWN:   return 'P';
        case CP_KNIGHT: return 'N';
        case CP_BISHOP: return 'B';
        case CP_ROOK:   return 'R';
        case CP_QUEEN:  return 'Q';
        case CP_KING:   return 'K';
        default:        return ' ';
    }
}

// A plain rectangular outline (drawRoundRect with radius 0 -- TFT_eSPI treats
// that as an ordinary rect, and it's the only rectangle-outline primitive
// this project's native_test stub defines), drawn 2px thick so it reads
// clearly against the checkerboard instead of as a hairline.
static void drawSquareBorder(TFT_eSPI &tft, const ChessLayout &l, uint8_t row, uint8_t col, uint16_t color) {
    int16_t x, y;
    squareToScreen(l, row, col, x, y);
    tft.drawRoundRect(x, y, l.squareSize, l.squareSize, 0, color);
    tft.drawRoundRect(x + 1, y + 1, l.squareSize - 2, l.squareSize - 2, 0, color);
}

ChessLayout computeChessLayout() {
    ChessLayout l;
    l.statusY = 0;
    l.statusH = CHROME_BAR_HEIGHT; // grid sits below the shared chrome bar -- see Chrome.h

    l.squareSize = 34;
    int16_t boardSize = l.squareSize * 8;
    l.boardX = (SCREEN_WIDTH - boardSize) / 2;
    l.boardY = l.statusY + l.statusH + (SCREEN_HEIGHT - l.statusY - l.statusH - boardSize) / 2;

    l.buttonW = 160;
    l.buttonH = 44;
    l.buttonX = (SCREEN_WIDTH - l.buttonW) / 2;
    l.buttonY = l.boardY + (boardSize - l.buttonH) / 2;
    return l;
}

void drawChessStaticChrome(TFT_eSPI &tft, const ChessLayout &layout) {
    tft.fillScreen(COLOR_BG);
    (void)layout; // nothing else is "static" here -- drawChessBoard draws every square fresh, and Chrome.h owns the status bar
}

// One square's own checkerboard-colored background, no piece -- the shared
// piece of drawSquareAndPiece() below that animateChessMove() also uses on
// its own for a square it's deliberately leaving blank (one of the moving
// piece(s)' own squares, for the duration of the slide).
static uint16_t squareBackgroundColor(uint8_t row, uint8_t col) {
    // a1 (row0,col0) is a dark square in real chess, matching this parity.
    return ((row + col) % 2 == 0) ? COLOR_DARK_SQ : COLOR_LIGHT_SQ;
}

static void drawSquareBackground(TFT_eSPI &tft, const ChessLayout &l, uint8_t row, uint8_t col) {
    int16_t x, y;
    squareToScreen(l, row, col, x, y);
    tft.fillRect(x, y, l.squareSize, l.squareSize, squareBackgroundColor(row, col));
}

// One square's background plus whatever piece (if any) `board` currently
// says sits there -- the per-square body drawChessBoard()'s main loop used
// to inline directly; factored out so animateChessMove() below can redraw
// the same thing, frame after frame, for every square it ISN'T currently
// animating.
static void drawSquareAndPiece(TFT_eSPI &tft, const ChessLayout &l, uint8_t row, uint8_t col, const ChessBoard &board) {
    int16_t x, y;
    squareToScreen(l, row, col, x, y);
    uint16_t squareColor = squareBackgroundColor(row, col);
    tft.fillRect(x, y, l.squareSize, l.squareSize, squareColor);

    ChessPiece p = board.at(row * 8 + col);
    if (p.type != CP_NONE) {
        char buf[2] = { pieceChar(p.type), '\0' };
        uint16_t fg = (p.color == CC_WHITE) ? COLOR_WHITE_PIECE : COLOR_BLACK_PIECE;
        tft.setTextColor(fg, squareColor);
        tft.setTextDatum(MC_DATUM);
        tft.setTextSize(2);
        tft.drawString(buf, x + l.squareSize / 2, y + l.squareSize / 2);
    }
}

// The checkerboard color that belongs under an arbitrary PIXEL point (rather
// than a specific row/col) -- the inverse of squareToScreen(), clamped
// defensively to stay on-board. animateChessMove()'s interpolated glyph
// position needs this: `drawString`'s own opaque background fill has to
// match whatever square color is actually beneath it at that instant, which
// (unlike Checkers' own always-one-fixed-color diagonal path) can be either
// checkerboard color depending on where along the slide the piece currently
// sits.
static uint16_t squareColorUnderPoint(const ChessLayout &l, int16_t px, int16_t py) {
    int16_t boardSize = l.squareSize * 8;
    int16_t x = px - l.boardX; if (x < 0) x = 0; if (x >= boardSize) x = boardSize - 1;
    int16_t y = py - l.boardY; if (y < 0) y = 0; if (y >= boardSize) y = boardSize - 1;
    uint8_t col = (uint8_t)(x / l.squareSize);
    uint8_t screenRow = (uint8_t)(y / l.squareSize);
    uint8_t row = 7 - screenRow; // undo squareToScreen's bottom-up flip
    return squareBackgroundColor(row, col);
}

// Draws a piece centered at an arbitrary pixel point rather than a specific
// (row, col) -- animateChessMove()'s in-flight interpolated position uses
// this, the same "shared center-anchored glyph helper" role
// CheckersDisplay.cpp's own drawPieceGlyphAtCenter() plays for that file.
// `bg` is the caller's responsibility to get right (see
// squareColorUnderPoint() above for the moving-glyph case, or
// squareBackgroundColor() directly for a glyph fixed at a known square) --
// this function only ever draws the letter itself.
static void drawPieceGlyphAtCenter(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t bg, const ChessPiece &p) {
    if (p.type == CP_NONE) return;
    char buf[2] = { pieceChar(p.type), '\0' };
    uint16_t fg = (p.color == CC_WHITE) ? COLOR_WHITE_PIECE : COLOR_BLACK_PIECE;
    tft.setTextColor(fg, bg);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.drawString(buf, cx, cy);
}

void drawChessBoard(TFT_eSPI &tft, const ChessLayout &layout, const ChessBoard &board, int8_t selectedSquare) {
    bool sideInCheck = board.inCheck();
    uint8_t sideToMoveColor = board.isHumanTurn() ? CC_WHITE : CC_BLACK;
    int8_t checkedKingSquare = -1;

    for (uint8_t row = 0; row < 8; row++) {
        for (uint8_t col = 0; col < 8; col++) {
            uint8_t sq = row * 8 + col;
            drawSquareAndPiece(tft, layout, row, col, board);
            ChessPiece p = board.at(sq);
            if (sideInCheck && p.type == CP_KING && p.color == sideToMoveColor) checkedKingSquare = sq;
        }
    }

    int8_t lastFrom, lastTo;
    board.lastMove(lastFrom, lastTo);
    if (lastFrom >= 0) {
        drawSquareBorder(tft, layout, rowOf((uint8_t)lastFrom), colOf((uint8_t)lastFrom), COLOR_LAST_MOVE);
        drawSquareBorder(tft, layout, rowOf((uint8_t)lastTo), colOf((uint8_t)lastTo), COLOR_LAST_MOVE);
    }

    if (checkedKingSquare >= 0) {
        drawSquareBorder(tft, layout, rowOf((uint8_t)checkedKingSquare), colOf((uint8_t)checkedKingSquare), COLOR_CHECK);
    }

    // Selection + legal destinations drawn last so they sit visibly on top
    // of the last-move/check borders whenever they land on the same square.
    if (selectedSquare >= 0) {
        drawSquareBorder(tft, layout, rowOf((uint8_t)selectedSquare), colOf((uint8_t)selectedSquare), COLOR_SELECTED);

        uint8_t dests[32];
        uint8_t n = board.legalDestinations((uint8_t)selectedSquare, dests);
        for (uint8_t i = 0; i < n; i++) {
            uint8_t dr = rowOf(dests[i]), dc = colOf(dests[i]);
            bool isCapture = board.at(dests[i]).type != CP_NONE;
            if (isCapture) {
                // A ring, not a filled dot, so the capturable piece's own
                // letter stays visible underneath it.
                drawSquareBorder(tft, layout, dr, dc, COLOR_LEGAL_MARK);
            } else {
                int16_t dx, dy;
                squareToScreen(layout, dr, dc, dx, dy);
                int16_t dotSize = layout.squareSize / 3;
                int16_t dotX = dx + (layout.squareSize - dotSize) / 2;
                int16_t dotY = dy + (layout.squareSize - dotSize) / 2;
                tft.fillRect(dotX, dotY, dotSize, dotSize, COLOR_LEGAL_MARK);
            }
        }
    }
}

void drawChessPlayAgainButton(TFT_eSPI &tft, const ChessLayout &layout) {
    tft.fillRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, COLOR_BUTTON_BG);
    tft.drawRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, TFT_WHITE);
    tft.setTextColor(COLOR_BUTTON_TEXT, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.drawString("Play Again", layout.buttonX + layout.buttonW / 2, layout.buttonY + layout.buttonH / 2);
}

void hideChessPlayAgainButton(TFT_eSPI &tft, const ChessLayout &layout) {
    tft.fillRect(layout.buttonX - 2, layout.buttonY - 2, layout.buttonW + 4, layout.buttonH + 4, COLOR_BG);
}

bool hitTestSquare(const ChessLayout &layout, int16_t touchX, int16_t touchY, uint8_t &outRow, uint8_t &outCol) {
    int16_t boardSize = layout.squareSize * 8;
    if (touchX < layout.boardX || touchX >= layout.boardX + boardSize) return false;
    if (touchY < layout.boardY || touchY >= layout.boardY + boardSize) return false;
    uint8_t col = (touchX - layout.boardX) / layout.squareSize;
    uint8_t screenRow = (touchY - layout.boardY) / layout.squareSize;
    outCol = col;
    outRow = 7 - screenRow; // undo the bottom-up flip squareToScreen applies
    return true;
}

bool hitTestChessPlayAgainButton(const ChessLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.buttonX && touchX < layout.buttonX + layout.buttonW &&
           touchY >= layout.buttonY && touchY < layout.buttonY + layout.buttonH;
}

void animateChessMove(TFT_eSPI &tft, const ChessLayout &layout, const ChessBoard &board, const ChessBoard &beforeBoard) {
    int8_t fromSq, toSq;
    board.lastMove(fromSq, toSq);
    if (fromSq < 0 || toSq < 0) return; // defensive -- nothing to animate before any move has been played

    uint8_t rookFrom = 0, rookTo = 0;
    bool isCastle = board.lastMoveWasCastle(rookFrom, rookTo);

    uint8_t epCapturedSquare = 0;
    bool isEnPassant = board.lastMoveWasEnPassant(epCapturedSquare);

    // An ordinary capture lands directly on `toSq`, so beforeBoard (a
    // snapshot taken just before the move) still shows it there; en
    // passant's victim sits on its own separate square instead -- see this
    // function's header comment and ChessBoard::lastMoveWasEnPassant().
    uint8_t capturedSquare = isEnPassant ? epCapturedSquare : (uint8_t)toSq;
    ChessPiece capturedPiece = beforeBoard.at(capturedSquare);
    bool hasCapture = capturedPiece.type != CP_NONE;

    ChessPiece movedPiece = board.at((uint8_t)toSq);
    ChessPiece rookPiece = isCastle ? board.at(rookTo) : ChessPiece{ CP_NONE, CC_NONE };

    // Every square this move touches needs to render as plain background
    // (no piece) in the per-frame whole-board redraw below -- the glyph(s)
    // drawn fresh on top each frame are the only things that ever represent
    // them for the rest of this function. Listed with room for every square
    // any one move could possibly touch (mover's start/end, a castle's
    // rook's start/end, a capture's own square) even though several of
    // these coincide in the common case (no castle, no en passant) --
    // harmless duplicates, the lookup below just needs one match.
    uint8_t exceptSquares[5] = { (uint8_t)fromSq, (uint8_t)toSq, rookFrom, rookTo, capturedSquare };
    uint8_t exceptCount = isCastle ? 4 : (hasCapture ? 3 : 2);
    // (fromSq, toSq) always first two; capturedSquare (if any) third;
    // rookFrom/rookTo (if a castle -- never both a castle AND a capture)
    // take slots 3 and 4 instead.
    if (isCastle) { exceptSquares[2] = rookFrom; exceptSquares[3] = rookTo; }

    int16_t fx0, fy0, fx1, fy1;
    squareToScreen(layout, rowOf((uint8_t)fromSq), colOf((uint8_t)fromSq), fx0, fy0);
    squareToScreen(layout, rowOf((uint8_t)toSq), colOf((uint8_t)toSq), fx1, fy1);
    int16_t moverCx0 = fx0 + layout.squareSize / 2, moverCy0 = fy0 + layout.squareSize / 2;
    int16_t moverCx1 = fx1 + layout.squareSize / 2, moverCy1 = fy1 + layout.squareSize / 2;

    int16_t rookCx0 = 0, rookCy0 = 0, rookCx1 = 0, rookCy1 = 0;
    if (isCastle) {
        int16_t rfx0, rfy0, rfx1, rfy1;
        squareToScreen(layout, rowOf(rookFrom), colOf(rookFrom), rfx0, rfy0);
        squareToScreen(layout, rowOf(rookTo), colOf(rookTo), rfx1, rfy1);
        rookCx0 = rfx0 + layout.squareSize / 2; rookCy0 = rfy0 + layout.squareSize / 2;
        rookCx1 = rfx1 + layout.squareSize / 2; rookCy1 = rfy1 + layout.squareSize / 2;
    }

    int16_t capCx = 0, capCy = 0;
    uint16_t capBg = COLOR_BG;
    if (hasCapture) {
        int16_t cx, cy;
        squareToScreen(layout, rowOf(capturedSquare), colOf(capturedSquare), cx, cy);
        capCx = cx + layout.squareSize / 2; capCy = cy + layout.squareSize / 2;
        capBg = squareBackgroundColor(rowOf(capturedSquare), colOf(capturedSquare));
    }

    static const uint16_t DURATION_MS = 300;    // matches ChessScreen.kt's own tween(300) for the same move
    static const uint16_t TARGET_FRAME_MS = 20; // ~50fps target, same pacing as CheckersDisplay.cpp
    uint16_t steps = DURATION_MS / TARGET_FRAME_MS;

    for (uint16_t i = 1; i <= steps; i++) {
        unsigned long frameStart = millis();

        float t = (float)i / (float)steps;
        float eased = 1.0f - (1.0f - t) * (1.0f - t); // ease-out: quick start, gentle settle -- same feel as CheckersDisplay.cpp's own slide

        // Full fresh redraw every frame -- see this function's header
        // comment (in ChessDisplay.h) for why this file takes that approach
        // instead of Checkers' selectively-erased-bounding-box trick.
        for (uint8_t row = 0; row < 8; row++) {
            for (uint8_t col = 0; col < 8; col++) {
                uint8_t sq = row * 8 + col;
                bool skip = false;
                for (uint8_t k = 0; k < exceptCount; k++) {
                    if (exceptSquares[k] == sq) { skip = true; break; }
                }
                if (skip) drawSquareBackground(tft, layout, row, col);
                else drawSquareAndPiece(tft, layout, row, col, board);
            }
        }

        // The captured piece (if any) stays visible, fixed at its own
        // square, for the whole slide -- it disappears only once the slide
        // finishes (the caller's subsequent drawChessBoard() call shows the
        // real, final, capture-free state), the same "stays until the
        // capture actually lands" feel CheckersDisplay.cpp's
        // animateCheckersMove() gives its own jump captures.
        if (hasCapture) drawPieceGlyphAtCenter(tft, capCx, capCy, capBg, capturedPiece);

        int16_t mcx = moverCx0 + (int16_t)((moverCx1 - moverCx0) * eased);
        int16_t mcy = moverCy0 + (int16_t)((moverCy1 - moverCy0) * eased);
        drawPieceGlyphAtCenter(tft, mcx, mcy, squareColorUnderPoint(layout, mcx, mcy), movedPiece);

        // Castling's second piece -- the rook -- slides at the same time as
        // the king, not after it; this is the "moves TWO pieces" wrinkle
        // Checkers never had to handle (a checkers jump only ever moves the
        // one jumping piece).
        if (isCastle) {
            int16_t rcx = rookCx0 + (int16_t)((rookCx1 - rookCx0) * eased);
            int16_t rcy = rookCy0 + (int16_t)((rookCy1 - rookCy0) * eased);
            drawPieceGlyphAtCenter(tft, rcx, rcy, squareColorUnderPoint(layout, rcx, rcy), rookPiece);
        }

        unsigned long elapsed = millis() - frameStart;
        if (elapsed < TARGET_FRAME_MS) delay(TARGET_FRAME_MS - elapsed);
    }
}
