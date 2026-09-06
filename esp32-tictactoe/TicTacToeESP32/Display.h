#pragma once
#include <TFT_eSPI.h>
#include "GameLogic.h"

// All drawing lives here -- GameLogic.h/.cpp knows nothing about TFT_eSPI, and
// TicTacToeESP32.ino only calls these functions plus GameLogic's, the same
// engine/screen split GameSuite's Android version uses throughout.

struct Layout {
    int16_t gridX, gridY, cellSize;   // 3x3 grid, top-left corner + one cell's side length
    int16_t statusY, statusH;         // status banner across the top
    // "Play Again" button, shown only once a round ends
    int16_t buttonX, buttonY, buttonW, buttonH;
};

// Computed once from the panel's rotated width/height (see Config.h) --
// SCREEN_WIDTH/SCREEN_HEIGHT there must match tft.setRotation()'s result.
Layout computeLayout();

// NOTE: the status bar + Home button used to be drawn/hit-tested here
// (drawStatus/hitTestHomeButton). Both moved to Chrome.h/.cpp so every game
// shares one implementation instead of each reimplementing its own -- call
// drawChromeBar(tft, text) in place of the old drawStatus(tft, layout, text).

void drawStaticChrome(TFT_eSPI &tft, const Layout &layout);
void drawCell(TFT_eSPI &tft, const Layout &layout, uint8_t cellIndex, uint8_t value);
void drawWinningLine(TFT_eSPI &tft, const Layout &layout, const int8_t line[3]);
void drawPlayAgainButton(TFT_eSPI &tft, const Layout &layout);
void hidePlayAgainButton(TFT_eSPI &tft, const Layout &layout);

// Hit-testing helpers -- return true and write the result (out param) when
// (touchX, touchY) in SCREEN coordinates (already rotated/calibrated by
// TFT_eSPI's getTouch) falls inside that element.
bool hitTestCell(const Layout &layout, int16_t touchX, int16_t touchY, uint8_t &outCell);
bool hitTestPlayAgainButton(const Layout &layout, int16_t touchX, int16_t touchY);
