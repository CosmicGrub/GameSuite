#include "CheckersDisplay.h"
#include "Config.h"
#include "Theme.h"

// Colors go through Theme.h's shared palette so the whole arcade reads as
// one consistent product -- human pieces teal, AI pieces amber, exactly
// like every other game's human/AI pair.
static const uint16_t COLOR_BG            = THEME_BG;
static const uint16_t COLOR_LIGHT_SQUARE  = THEME_BOARD_LIGHT;
static const uint16_t COLOR_DARK_SQUARE   = THEME_BOARD_DARK;
static const uint16_t COLOR_HUMAN_PIECE   = THEME_HUMAN;
static const uint16_t COLOR_AI_PIECE      = THEME_AI;
static const uint16_t COLOR_PIECE_BORDER  = THEME_TEXT;
static const uint16_t COLOR_KING_TEXT     = THEME_BG;
static const uint16_t COLOR_HIGHLIGHT     = THEME_SUCCESS;
static const uint16_t COLOR_STATUS_BG     = THEME_SURFACE;
static const uint16_t COLOR_STATUS_TEXT   = THEME_TEXT;
static const uint16_t COLOR_BUTTON_BG     = THEME_SURFACE_ALT;
static const uint16_t COLOR_BUTTON_TEXT   = THEME_TEXT;

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

// Draws a piece centered at an arbitrary pixel point rather than a specific
// (row, col) -- the shared implementation both the normal square-anchored
// draw below AND animateCheckersMove()'s in-flight interpolated position use,
// so a moving piece looks pixel-identical to a resting one.
static void drawPieceGlyphAtCenter(TFT_eSPI &tft, int16_t cx, int16_t cy, int16_t cellSize, CheckersPiece piece) {
    if (piece == CheckersPiece::EMPTY) return;

    int16_t pad = cellSize / 6;
    int16_t psize = cellSize - 2 * pad;
    int16_t px = cx - psize / 2, py = cy - psize / 2;
    int16_t radius = psize / 4;

    bool human = (piece == CheckersPiece::HUMAN_MAN || piece == CheckersPiece::HUMAN_KING);
    uint16_t color = human ? COLOR_HUMAN_PIECE : COLOR_AI_PIECE;

    tft.fillRoundRect(px, py, psize, psize, radius, color);
    tft.drawRoundRect(px, py, psize, psize, radius, COLOR_PIECE_BORDER);

    bool king = (piece == CheckersPiece::HUMAN_KING || piece == CheckersPiece::AI_KING);
    if (king) {
        tft.setTextColor(COLOR_KING_TEXT, color);
        tft.setTextDatum(MC_DATUM);
        tft.drawString("K", cx, cy);
    }
}

static void drawPieceGlyph(TFT_eSPI &tft, const CheckersLayout &l, uint8_t row, uint8_t col, CheckersPiece piece) {
    int16_t x, y;
    squareOrigin(l, row, col, x, y);
    drawPieceGlyphAtCenter(tft, x + l.cellSize / 2, y + l.cellSize / 2, l.cellSize, piece);
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
    tft.setTextSize(1);
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
    tft.setTextSize(1);
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

void animateCheckersMove(TFT_eSPI &tft, const CheckersLayout &layout, const CheckersBoard &board,
                          uint8_t fromRow, uint8_t fromCol, uint8_t toRow, uint8_t toCol, CheckersPiece piece) {
    if (piece == CheckersPiece::EMPTY) return; // defensive -- a real caller always passes the actual piece that moved

    int16_t fx, fy, tx, ty;
    squareOrigin(layout, fromRow, fromCol, fx, fy);
    squareOrigin(layout, toRow, toCol, tx, ty);
    int16_t cx0 = fx + layout.cellSize / 2, cy0 = fy + layout.cellSize / 2;
    int16_t cx1 = tx + layout.cellSize / 2, cy1 = ty + layout.cellSize / 2;

    // A jump (2-square diagonal hop) has an intermediate square that may
    // hold the piece being captured -- it must stay visible, faithfully
    // redrawn from `board`, until the real move actually applies after this
    // returns (the capture itself isn't this function's concern). A simple
    // 1-square move has no such square. Both endpoints, and this square if
    // present, are drawn ONCE up front and never touched again during the
    // slide -- the moving piece itself is erased/redrawn via its own exact
    // bounding box each frame instead (see below), not by repainting squares.
    int rowDelta = (int)toRow - (int)fromRow;
    bool isJump = (rowDelta == 2 || rowDelta == -2);
    uint8_t midRow = (fromRow + toRow) / 2, midCol = (fromCol + toCol) / 2;

    // One full-board redraw HERE, before the per-frame loop -- a one-time
    // cost, not a per-frame one -- clears any leftover UI state from before
    // the slide starts, most importantly the legal-destination highlights
    // (see highlightCheckersSelection() in the .ino) on squares OTHER than
    // the one just tapped, which this function otherwise never touches and
    // would otherwise persist, visible, for the whole animation.
    drawCheckersBoard(tft, layout, board);
    drawCheckersSquare(tft, layout, fromRow, fromCol, CheckersPiece::EMPTY);
    if (isJump) drawCheckersSquare(tft, layout, midRow, midCol, board.at(midRow, midCol));
    drawCheckersSquare(tft, layout, toRow, toCol, CheckersPiece::EMPTY);

    // The piece glyph's own bounding box (see drawPieceGlyphAtCenter) --
    // erasing exactly this box at its PREVIOUS frame position, rather than
    // an entire square, is what actually fixes ghosting: a square-shaped
    // box sliding along a 45-degree diagonal spills a few pixels into the
    // two OFF-PATH squares that share the same corner (neither the source,
    // intermediate, nor destination square) at points along the slide --
    // squares this function never otherwise redraws, so any pixels left
    // there from an earlier frame never got cleared (confirmed as the exact
    // cause of the "afterimage" artifacts seen on real hardware). Erasing by
    // exact previous pixel position sidesteps square boundaries entirely.
    int16_t pad = layout.cellSize / 6;
    int16_t psize = layout.cellSize - 2 * pad;
    bool havePrev = false;
    int16_t prevPx = 0, prevPy = 0;

    static const uint16_t DURATION_MS = 350;
    static const uint16_t TARGET_FRAME_MS = 20; // ~50fps target
    uint16_t steps = DURATION_MS / TARGET_FRAME_MS;

    for (uint16_t i = 1; i <= steps; i++) {
        unsigned long frameStart = millis();

        float t = (float)i / (float)steps;
        float eased = 1.0f - (1.0f - t) * (1.0f - t); // ease-out: quick start, gentle settle -- reads more natural than constant-speed motion for a short slide
        int16_t cx = cx0 + (int16_t)((cx1 - cx0) * eased);
        int16_t cy = cy0 + (int16_t)((cy1 - cy0) * eased);
        int16_t px = cx - psize / 2, py = cy - psize / 2;

        // Checkers pieces only ever occupy dark squares, and a diagonal
        // slide stays on dark squares the entire way (see CheckersLogic.h),
        // so COLOR_DARK_SQUARE is always the correct background to erase to
        // here, everywhere along the path -- no per-frame square-color
        // lookup needed.
        if (havePrev) tft.fillRect(prevPx, prevPy, psize, psize, COLOR_DARK_SQUARE);

        // On a capturing jump, the moving piece slides directly OVER the
        // captured piece sitting on the intermediate square -- the erase
        // above can clip into its glyph if the moving piece's previous-frame
        // bounding box happened to overlap it, leaving a visible "hole"
        // (confirmed on real hardware). Cheaply re-assert that one square
        // correct every frame, before drawing the moving piece on top of it
        // again -- which is the visually correct outcome anyway while the
        // piece is actually passing over it.
        if (isJump) drawCheckersSquare(tft, layout, midRow, midCol, board.at(midRow, midCol));

        drawPieceGlyphAtCenter(tft, cx, cy, layout.cellSize, piece);
        prevPx = px; prevPy = py;
        havePrev = true;

        // Pad out to TARGET_FRAME_MS if the actual SPI drawing above finished
        // faster -- without this, fast enough drawing makes the whole
        // animation complete in a handful of milliseconds, effectively
        // invisible (confirmed on real hardware with an earlier version of
        // this function, before this rewrite).
        unsigned long elapsed = millis() - frameStart;
        if (elapsed < TARGET_FRAME_MS) delay(TARGET_FRAME_MS - elapsed);
    }
}
