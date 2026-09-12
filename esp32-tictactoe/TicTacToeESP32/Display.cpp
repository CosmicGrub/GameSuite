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

// A solid, uniformly-darker copy of an RGB565 color -- ported verbatim from
// CheckersDisplay.cpp's own darkenColor565() (halves each of the 5/6/5-bit
// R/G/B channels with plain integer shifts, no float/blend math) so the
// drop-shadow trick below stays a couple of cheap extra strokes rather than
// a new drawing subsystem.
static uint16_t darkenColor565(uint16_t color) {
    uint16_t r = (color >> 11) & 0x1F;
    uint16_t g = (color >> 5) & 0x3F;
    uint16_t b = color & 0x1F;
    return ((r / 2) << 11) | ((g / 2) << 5) | (b / 2);
}
static const int16_t MARK_SHADOW_OFFSET = 2; // matches CheckersDisplay.cpp's own PIECE_SHADOW_OFFSET

// Draws one mark centered at (cx, cy) at an arbitrary scale of its normal
// half-size -- shared by the resting draw (scale 1.0) and drawCell()'s own
// stamp-in growth animation below, so an in-progress mark is pixel-identical
// in shape to a settled one. Also carries the Checkers-style drop-shadow: a
// darker, down-right-offset copy of the same strokes drawn first, so only the
// sliver peeking past the offset remains visible once the real-color strokes
// draw on top -- reads as the mark sitting slightly proud of the cell instead
// of flat on it, the same depth cue CheckersDisplay.cpp's pieces already use.
static void drawMarkAtScale(TFT_eSPI &tft, int16_t cx, int16_t cy, int16_t half, uint8_t value, float scale) {
    int16_t h = (int16_t)(half * scale);
    if (h < 1) h = 1;
    int16_t scx = cx + MARK_SHADOW_OFFSET, scy = cy + MARK_SHADOW_OFFSET;

    if (value == HUMAN) {
        uint16_t shadow = darkenColor565(COLOR_X);
        for (int8_t t = -1; t <= 1; t++) {
            tft.drawLine(scx - h + t, scy - h, scx + h + t, scy + h, shadow);
            tft.drawLine(scx + h + t, scy - h, scx - h + t, scy + h, shadow);
        }
        for (int8_t t = -1; t <= 1; t++) {
            tft.drawLine(cx - h + t, cy - h, cx + h + t, cy + h, COLOR_X);
            tft.drawLine(cx + h + t, cy - h, cx - h + t, cy + h, COLOR_X);
        }
    } else if (value == AI) {
        uint16_t shadow = darkenColor565(COLOR_O);
        tft.drawCircle(scx, scy, h, shadow);
        tft.drawCircle(scx, scy, h - 1, shadow);
        tft.drawCircle(scx, scy, h - 2, shadow);
        tft.drawCircle(cx, cy, h, COLOR_O);
        tft.drawCircle(cx, cy, h - 1, COLOR_O);
        tft.drawCircle(cx, cy, h - 2, COLOR_O);
    }
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

    if (value == EMPTY) return; // clearing a cell -- nothing further to draw, no stamp-in for a blank

    int16_t cx, cy;
    cellCenter(layout, cellIndex, cx, cy);
    int16_t pad = layout.cellSize / 4;      // how far the mark sits from the cell edge
    int16_t half = layout.cellSize / 2 - pad;
    // BUG FIX (kept from the original single-shot version): X's two
    // diagonals are drawn 3px thick (as three parallel lines) so each reads
    // clearly at this size instead of a hairline -- the second line used to
    // repeat the SAME "\" (top-left-to-bottom-right) diagonal as the first,
    // just thickened along the other axis, meaning no "/" diagonal ever
    // rendered (confirmed by a real hardware photo showing a single stroke,
    // not a full X). drawMarkAtScale() above is where that fix now lives.

    // Stamp-in placement (Premium 2026 Vision pitch, ESP32 Tic-Tac-Toe
    // section): grow the mark from the cell's center instead of popping in
    // at full size in one shot, reusing the exact manual millis()-timed
    // per-frame-budget loop drawWinningLine()/CheckersDisplay.cpp's
    // animateCheckersMove() already prove out on this hardware, rather than
    // a third bespoke pacing scheme. Rises past full size before settling
    // back down, for a snappier "stamp" read than a plain linear grow.
    static const uint16_t DURATION_MS = 140;
    static const uint16_t TARGET_FRAME_MS = 20; // ~50fps target, same budget as the rest of this project
    static const float OVERSHOOT_SCALE = 1.12f;
    uint16_t steps = DURATION_MS / TARGET_FRAME_MS;

    for (uint16_t i = 1; i <= steps; i++) {
        unsigned long frameStart = millis();

        float t = (float)i / (float)steps;
        // Rises 0 -> OVERSHOOT_SCALE over the first 70% of the animation,
        // then settles OVERSHOOT_SCALE -> 1.0 over the remaining 30% --
        // plain piecewise arithmetic, no separate spring/easing curve needed
        // for a handful of frames.
        float scale = (t < 0.7f)
            ? (t / 0.7f) * OVERSHOOT_SCALE
            : OVERSHOOT_SCALE - (t - 0.7f) / 0.3f * (OVERSHOOT_SCALE - 1.0f);

        tft.fillRect(x + 1, y + 1, layout.cellSize - 2, layout.cellSize - 2, COLOR_BG);
        drawMarkAtScale(tft, cx, cy, half, value, scale);

        unsigned long elapsed = millis() - frameStart;
        if (elapsed < TARGET_FRAME_MS) delay(TARGET_FRAME_MS - elapsed);
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
