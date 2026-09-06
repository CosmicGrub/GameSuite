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

void drawChessBoard(TFT_eSPI &tft, const ChessLayout &layout, const ChessBoard &board, int8_t selectedSquare) {
    bool sideInCheck = board.inCheck();
    uint8_t sideToMoveColor = board.isHumanTurn() ? CC_WHITE : CC_BLACK;
    int8_t checkedKingSquare = -1;

    for (uint8_t row = 0; row < 8; row++) {
        for (uint8_t col = 0; col < 8; col++) {
            uint8_t sq = row * 8 + col;
            int16_t x, y;
            squareToScreen(layout, row, col, x, y);
            // a1 (row0,col0) is a dark square in real chess, matching this parity.
            uint16_t squareColor = ((row + col) % 2 == 0) ? COLOR_DARK_SQ : COLOR_LIGHT_SQ;
            tft.fillRect(x, y, layout.squareSize, layout.squareSize, squareColor);

            ChessPiece p = board.at(sq);
            if (p.type != CP_NONE) {
                if (sideInCheck && p.type == CP_KING && p.color == sideToMoveColor) checkedKingSquare = sq;
                char buf[2] = { pieceChar(p.type), '\0' };
                uint16_t fg = (p.color == CC_WHITE) ? COLOR_WHITE_PIECE : COLOR_BLACK_PIECE;
                tft.setTextColor(fg, squareColor);
                tft.setTextDatum(MC_DATUM);
                tft.setTextSize(2);
                tft.drawString(buf, x + layout.squareSize / 2, y + layout.squareSize / 2);
            }
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
