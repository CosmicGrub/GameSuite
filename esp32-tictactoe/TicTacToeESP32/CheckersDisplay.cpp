#include "CheckersDisplay.h"
#include "Config.h"

// Colors are restricted to the palette Display.cpp already uses for
// Tic-Tac-Toe (TFT_eSPI's own named constants) so the whole arcade reads as
// one consistent product rather than each game inventing its own -- human
// pieces cyan, AI pieces orange, exactly like X and O.
static const uint16_t COLOR_BG            = TFT_BLACK;
static const uint16_t COLOR_LIGHT_SQUARE  = TFT_WHITE;
static const uint16_t COLOR_DARK_SQUARE   = TFT_DARKGREEN;
static const uint16_t COLOR_HUMAN_PIECE   = TFT_CYAN;
static const uint16_t COLOR_AI_PIECE      = TFT_ORANGE;
static const uint16_t COLOR_PIECE_BORDER  = TFT_WHITE;
static const uint16_t COLOR_KING_TEXT     = TFT_BLACK;
static const uint16_t COLOR_HIGHLIGHT     = TFT_GREEN;
static const uint16_t COLOR_STATUS_BG     = TFT_NAVY;
static const uint16_t COLOR_STATUS_TEXT   = TFT_WHITE;
static const uint16_t COLOR_BUTTON_BG     = TFT_DARKGREEN;
static const uint16_t COLOR_BUTTON_TEXT   = TFT_WHITE;

// Same fixed "back to menu" button Display.cpp draws for Tic-Tac-Toe, sized
// off the status bar's own height rather than a magic screen-relative
// position.
static const int16_t HOME_BTN_SIZE = 30;
static const int16_t HOME_BTN_MARGIN = 2;

static int16_t homeButtonTop(const CheckersLayout &layout) {
    return layout.statusY + (layout.statusH - HOME_BTN_SIZE) / 2;
}

CheckersLayout computeCheckersLayout() {
    CheckersLayout l;
    l.statusY = 0;
    l.statusH = 36;

    int16_t availW = SCREEN_WIDTH;
    int16_t availH = SCREEN_HEIGHT - l.statusH;
    l.cellSize = (availW < availH ? availW : availH) / 8;
    int16_t boardSize = l.cellSize * 8;
    l.boardX = (SCREEN_WIDTH - boardSize) / 2;
    l.boardY = l.statusY + l.statusH + (availH - boardSize) / 2;

    l.buttonW = 140;
    l.buttonH = 44;
    l.buttonX = (SCREEN_WIDTH - l.buttonW) / 2;
    l.buttonY = l.boardY + (boardSize - l.buttonH) / 2;
    return l;
}

static void squareOrigin(const CheckersLayout &l, uint8_t row, uint8_t col, int16_t &x, int16_t &y) {
    x = l.boardX + col * l.cellSize;
    y = l.boardY + row * l.cellSize;
}

static void drawSquareBase(TFT_eSPI &tft, const CheckersLayout &l, uint8_t row, uint8_t col) {
    int16_t x, y;
    squareOrigin(l, row, col, x, y);
    uint16_t color = ((row + col) % 2 == 1) ? COLOR_DARK_SQUARE : COLOR_LIGHT_SQUARE;
    tft.fillRect(x, y, l.cellSize, l.cellSize, color);
}

static void drawPieceGlyph(TFT_eSPI &tft, const CheckersLayout &l, uint8_t row, uint8_t col, CheckersPiece piece) {
    if (piece == CheckersPiece::EMPTY) return;
    int16_t x, y;
    squareOrigin(l, row, col, x, y);

    int16_t pad = l.cellSize / 6;
    int16_t px = x + pad, py = y + pad;
    int16_t psize = l.cellSize - 2 * pad;
    int16_t radius = psize / 4;

    bool human = (piece == CheckersPiece::HUMAN_MAN || piece == CheckersPiece::HUMAN_KING);
    uint16_t color = human ? COLOR_HUMAN_PIECE : COLOR_AI_PIECE;

    tft.fillRoundRect(px, py, psize, psize, radius, color);
    tft.drawRoundRect(px, py, psize, psize, radius, COLOR_PIECE_BORDER);

    bool king = (piece == CheckersPiece::HUMAN_KING || piece == CheckersPiece::AI_KING);
    if (king) {
        tft.setTextColor(COLOR_KING_TEXT, color);
        tft.setTextDatum(MC_DATUM);
        tft.setTextSize(1);
        tft.drawString("K", x + l.cellSize / 2, y + l.cellSize / 2);
    }
}

void drawCheckersStaticChrome(TFT_eSPI &tft, const CheckersLayout &layout) {
    tft.fillScreen(COLOR_BG);
    tft.fillRect(0, layout.statusY, SCREEN_WIDTH, layout.statusH, COLOR_STATUS_BG);
    for (uint8_t row = 0; row < 8; row++) {
        for (uint8_t col = 0; col < 8; col++) {
            drawSquareBase(tft, layout, row, col);
        }
    }
}

void drawCheckersStatus(TFT_eSPI &tft, const CheckersLayout &layout, const char *text) {
    tft.fillRect(0, layout.statusY, SCREEN_WIDTH, layout.statusH, COLOR_STATUS_BG);

    // The home button is drawn as part of every status-bar repaint (rather
    // than once at round start) because the fillRect above would otherwise
    // erase it on the very next status update -- same reasoning as
    // Display.cpp's drawStatus for Tic-Tac-Toe.
    int16_t by = homeButtonTop(layout);
    tft.drawRoundRect(HOME_BTN_MARGIN, by, HOME_BTN_SIZE, HOME_BTN_SIZE, 4, TFT_WHITE);
    tft.setTextColor(TFT_WHITE, COLOR_STATUS_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString("<", HOME_BTN_MARGIN + HOME_BTN_SIZE / 2, by + HOME_BTN_SIZE / 2);

    int16_t textAreaX0 = HOME_BTN_MARGIN * 2 + HOME_BTN_SIZE;
    tft.setTextColor(COLOR_STATUS_TEXT, COLOR_STATUS_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.drawString(text, textAreaX0 + (SCREEN_WIDTH - textAreaX0) / 2, layout.statusY + layout.statusH / 2);
}

void drawCheckersSquare(TFT_eSPI &tft, const CheckersLayout &layout, uint8_t row, uint8_t col, CheckersPiece piece) {
    drawSquareBase(tft, layout, row, col);
    drawPieceGlyph(tft, layout, row, col, piece);
}

void drawCheckersBoard(TFT_eSPI &tft, const CheckersLayout &layout, const CheckersBoard &board) {
    for (uint8_t row = 0; row < 8; row++) {
        for (uint8_t col = 0; col < 8; col++) {
            drawCheckersSquare(tft, layout, row, col, board.at(row, col));
        }
    }
}

void drawCheckersSquareHighlight(TFT_eSPI &tft, const CheckersLayout &layout, uint8_t row, uint8_t col,
                                  CheckersPiece piece, bool highlighted) {
    drawCheckersSquare(tft, layout, row, col, piece);
    if (!highlighted) return;

    int16_t x, y;
    squareOrigin(layout, row, col, x, y);
    // A 2px-thick border drawn with the same fast-line primitives Display.cpp
    // uses for its grid -- no drawRect exists in this project's TFT_eSPI
    // surface, so a border is 4 lines rather than 1 call.
    for (int16_t t = 0; t < 2; t++) {
        tft.drawFastHLine(x, y + t, layout.cellSize, COLOR_HIGHLIGHT);
        tft.drawFastHLine(x, y + layout.cellSize - 1 - t, layout.cellSize, COLOR_HIGHLIGHT);
        tft.drawFastVLine(x + t, y, layout.cellSize, COLOR_HIGHLIGHT);
        tft.drawFastVLine(x + layout.cellSize - 1 - t, y, layout.cellSize, COLOR_HIGHLIGHT);
    }
}

void drawCheckersPlayAgainButton(TFT_eSPI &tft, const CheckersLayout &layout) {
    tft.fillRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, COLOR_BUTTON_BG);
    tft.drawRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, TFT_WHITE);
    tft.setTextColor(COLOR_BUTTON_TEXT, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.drawString("Play Again", layout.buttonX + layout.buttonW / 2, layout.buttonY + layout.buttonH / 2);
}

void hideCheckersPlayAgainButton(TFT_eSPI &tft, const CheckersLayout &layout) {
    tft.fillRect(layout.buttonX - 2, layout.buttonY - 2, layout.buttonW + 4, layout.buttonH + 4, COLOR_BG);
}

bool hitTestSquare(const CheckersLayout &layout, int16_t touchX, int16_t touchY, uint8_t &outRow, uint8_t &outCol) {
    int16_t boardSize = layout.cellSize * 8;
    if (touchX < layout.boardX || touchX >= layout.boardX + boardSize) return false;
    if (touchY < layout.boardY || touchY >= layout.boardY + boardSize) return false;
    outCol = (touchX - layout.boardX) / layout.cellSize;
    outRow = (touchY - layout.boardY) / layout.cellSize;
    return true;
}

bool hitTestCheckersPlayAgainButton(const CheckersLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.buttonX && touchX < layout.buttonX + layout.buttonW &&
           touchY >= layout.buttonY && touchY < layout.buttonY + layout.buttonH;
}

bool hitTestCheckersHomeButton(const CheckersLayout &layout, int16_t touchX, int16_t touchY) {
    int16_t by = homeButtonTop(layout);
    return touchX >= 0 && touchX < HOME_BTN_MARGIN + HOME_BTN_SIZE &&
           touchY >= by && touchY < by + HOME_BTN_SIZE;
}
