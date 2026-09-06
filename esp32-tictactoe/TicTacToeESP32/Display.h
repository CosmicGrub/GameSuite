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

void drawStaticChrome(TFT_eSPI &tft, const Layout &layout);
void drawStatus(TFT_eSPI &tft, const Layout &layout, const char *text);
void drawCell(TFT_eSPI &tft, const Layout &layout, uint8_t cellIndex, uint8_t value);
void drawWinningLine(TFT_eSPI &tft, const Layout &layout, const int8_t line[3]);
void drawPlayAgainButton(TFT_eSPI &tft, const Layout &layout);
void hidePlayAgainButton(TFT_eSPI &tft, const Layout &layout);

// Hit-testing helpers -- return true and write the result (out param) when
// (touchX, touchY) in SCREEN coordinates (already rotated/calibrated by
// TFT_eSPI's getTouch) falls inside that element.
bool hitTestCell(const Layout &layout, int16_t touchX, int16_t touchY, uint8_t &outCell);
bool hitTestPlayAgainButton(const Layout &layout, int16_t touchX, int16_t touchY);

// A small "back to arcade menu" button drawn as part of the status bar (see
// drawStatus) -- always present during a round, not just after it ends, so a
// player isn't stuck in a game to see the menu again.
bool hitTestHomeButton(const Layout &layout, int16_t touchX, int16_t touchY);
