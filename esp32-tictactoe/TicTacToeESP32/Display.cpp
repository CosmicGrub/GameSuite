#include "Display.h"
#include "Config.h"
#include "Chrome.h"

// A few tasteful literals rather than TFT_eSPI's named constants for the two
// marks, so X and O read as clearly distinct even to a color-blind player
// (shape already disambiguates them regardless -- see GameSuite's own
// audited finding about needing non-color cues, which applies here too).
static const uint16_t COLOR_BG     = TFT_BLACK;
static const uint16_t COLOR_GRID   = TFT_DARKGREY;
static const uint16_t COLOR_X      = TFT_CYAN;
static const uint16_t COLOR_O      = TFT_ORANGE;
static const uint16_t COLOR_WIN    = TFT_GREEN;
static const uint16_t COLOR_BUTTON_BG = TFT_DARKGREEN;
static const uint16_t COLOR_BUTTON_TEXT = TFT_WHITE;

Layout computeLayout() {
    Layout l;
    l.statusY = 0;
    l.statusH = CHROME_BAR_HEIGHT; // grid sits below the shared chrome bar -- see Chrome.h

    l.cellSize = 64;
    int16_t gridSize = l.cellSize * 3;
    l.gridX = (SCREEN_WIDTH - gridSize) / 2;
    l.gridY = l.statusY + l.statusH + (SCREEN_HEIGHT - l.statusY - l.statusH - gridSize) / 2;

    l.buttonW = 140;
    l.buttonH = 44;
    l.buttonX = (SCREEN_WIDTH - l.buttonW) / 2;
    l.buttonY = l.gridY + (gridSize - l.buttonH) / 2;
    return l;
}

static void cellCenter(const Layout &l, uint8_t cell, int16_t &cx, int16_t &cy) {
    uint8_t col = cell % 3, row = cell / 3;
    cx = l.gridX + col * l.cellSize + l.cellSize / 2;
    cy = l.gridY + row * l.cellSize + l.cellSize / 2;
}

void drawStaticChrome(TFT_eSPI &tft, const Layout &layout) {
    tft.fillScreen(COLOR_BG);
    // The status-bar region (layout.statusY/statusH) is intentionally left
    // alone here -- drawChromeBar() (Chrome.h) owns and fills that whole
    // area, including the Home button, and is always called right after
    // this function whenever a round starts.

    int16_t gridSize = layout.cellSize * 3;
    // Two vertical + two horizontal interior lines -- a 3x3 grid needs no
    // outer border, just the dividers between cells.
    for (uint8_t i = 1; i < 3; i++) {
        int16_t x = layout.gridX + i * layout.cellSize;
        tft.drawFastVLine(x, layout.gridY, gridSize, COLOR_GRID);
        int16_t y = layout.gridY + i * layout.cellSize;
        tft.drawFastHLine(layout.gridX, y, gridSize, COLOR_GRID);
    }
}

void drawCell(TFT_eSPI &tft, const Layout &layout, uint8_t cellIndex, uint8_t value) {
    uint8_t col = cellIndex % 3, row = cellIndex / 3;
    int16_t x = layout.gridX + col * layout.cellSize;
    int16_t y = layout.gridY + row * layout.cellSize;
    // Inset by 1px so redrawing a cell never paints over the grid divider
    // lines either side of it.
    tft.fillRect(x + 1, y + 1, layout.cellSize - 2, layout.cellSize - 2, COLOR_BG);

    int16_t cx, cy;
    cellCenter(layout, cellIndex, cx, cy);
    int16_t pad = layout.cellSize / 4;      // how far the mark sits from the cell edge
    int16_t half = layout.cellSize / 2 - pad;

    if (value == HUMAN) {
        // X: two diagonals, drawn 3px thick (as three parallel lines) so it
        // reads clearly at this size instead of a hairline. BUG FIX: the
        // second line used to repeat the SAME "\" (top-left-to-bottom-right)
        // diagonal as the first, just thickened along the other axis --
        // meaning no "/" diagonal was ever drawn, so only half an X ever
        // rendered (confirmed by a real hardware photo showing a single
        // stroke, not a full X). The second line now correctly runs the
        // other way, top-right to bottom-left.
        for (int8_t t = -1; t <= 1; t++) {
            tft.drawLine(cx - half + t, cy - half, cx + half + t, cy + half, COLOR_X); // "\"
            tft.drawLine(cx + half + t, cy - half, cx - half + t, cy + half, COLOR_X); // "/"
        }
    } else if (value == AI) {
        int16_t r = half;
        tft.drawCircle(cx, cy, r, COLOR_O);
        tft.drawCircle(cx, cy, r - 1, COLOR_O);
        tft.drawCircle(cx, cy, r - 2, COLOR_O);
    }
}

void drawWinningLine(TFT_eSPI &tft, const Layout &layout, const int8_t line[3]) {
    if (line[0] < 0) return;
    int16_t x0, y0, x2, y2;
    cellCenter(layout, line[0], x0, y0);
    cellCenter(layout, line[2], x2, y2);

    // Draw-on animation (animation/physics pitch): grow the line from the
    // first winning cell's center to the last winning cell's center instead
    // of painting the full line in one shot. The board underneath is already
    // final/static by the time this runs, so there's nothing to erase --
    // each frame just redraws the segment a little longer than the frame
    // before, reusing the exact fixed-frame-budget manual loop
    // CheckersDisplay.cpp's animateCheckersMove() uses for its piece slide
    // (a millis()-timed per-frame budget, delay()-padded so fast SPI writes
    // don't make the whole thing flash by in a few milliseconds).
    static const uint16_t DURATION_MS = 180;
    static const uint16_t TARGET_FRAME_MS = 20; // ~50fps target, same budget animateCheckersMove() uses
    uint16_t steps = DURATION_MS / TARGET_FRAME_MS;

    for (uint16_t i = 1; i <= steps; i++) {
        unsigned long frameStart = millis();

        float t = (float)i / (float)steps;
        int16_t cx = x0 + (int16_t)((x2 - x0) * t);
        int16_t cy = y0 + (int16_t)((y2 - y0) * t);

        for (int8_t d = -2; d <= 2; d++) {
            tft.drawLine(x0, y0 + d, cx, cy + d, COLOR_WIN);
            tft.drawLine(x0 + d, y0, cx + d, cy, COLOR_WIN);
        }

        unsigned long elapsed = millis() - frameStart;
        if (elapsed < TARGET_FRAME_MS) delay(TARGET_FRAME_MS - elapsed);
    }
}

void drawPlayAgainButton(TFT_eSPI &tft, const Layout &layout) {
    tft.fillRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, COLOR_BUTTON_BG);
    tft.drawRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, TFT_WHITE);
    tft.setTextColor(COLOR_BUTTON_TEXT, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.drawString("Play Again", layout.buttonX + layout.buttonW / 2, layout.buttonY + layout.buttonH / 2);
}

void hidePlayAgainButton(TFT_eSPI &tft, const Layout &layout) {
    tft.fillRect(layout.buttonX - 2, layout.buttonY - 2, layout.buttonW + 4, layout.buttonH + 4, COLOR_BG);
}

bool hitTestCell(const Layout &layout, int16_t touchX, int16_t touchY, uint8_t &outCell) {
    int16_t gridSize = layout.cellSize * 3;
    if (touchX < layout.gridX || touchX >= layout.gridX + gridSize) return false;
    if (touchY < layout.gridY || touchY >= layout.gridY + gridSize) return false;
    uint8_t col = (touchX - layout.gridX) / layout.cellSize;
    uint8_t row = (touchY - layout.gridY) / layout.cellSize;
    outCell = row * 3 + col;
    return true;
}

bool hitTestPlayAgainButton(const Layout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.buttonX && touchX < layout.buttonX + layout.buttonW &&
           touchY >= layout.buttonY && touchY < layout.buttonY + layout.buttonH;
}
