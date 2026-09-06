// Minimal stand-in for TFT_eSPI.h -- just enough surface (types, color
// constants, and no-op method bodies) for Display.cpp to compile natively.
// The rendering methods below are deliberately no-ops: this stub cannot
// verify a single pixel actually gets drawn correctly, only that the pure
// MATH in Display.cpp (computeLayout, hitTestCell, hitTestPlayAgainButton)
// behaves correctly -- which is exactly what this native test suite uses
// it for. Real rendering can only be confirmed on the physical display.
#pragma once
#include <cstdint>
#include <cstddef>

#define TFT_BLACK 0
#define TFT_WHITE 1
#define TFT_DARKGREY 2
#define TFT_CYAN 3
#define TFT_ORANGE 4
#define TFT_GREEN 5
#define TFT_NAVY 6
#define TFT_DARKGREEN 7
#define MC_DATUM 4
#define HIGH 1

class TFT_eSPI {
public:
    TFT_eSPI() {}
    void init() {}
    void setRotation(int) {}
    bool getTouch(uint16_t *, uint16_t *) { return false; }
    void setTouch(uint16_t *) {}
    void calibrateTouch(uint16_t *, uint32_t, uint32_t, uint8_t) {}
    void fillScreen(uint32_t) {}
    void fillRect(int32_t, int32_t, int32_t, int32_t, uint32_t) {}
    void drawFastVLine(int32_t, int32_t, int32_t, uint32_t) {}
    void drawFastHLine(int32_t, int32_t, int32_t, uint32_t) {}
    void setTextColor(uint32_t, uint32_t) {}
    void setTextDatum(uint8_t) {}
    void setTextSize(uint8_t) {}
    void drawString(const char *, int32_t, int32_t) {}
    void drawLine(int32_t, int32_t, int32_t, int32_t, uint32_t) {}
    void drawCircle(int32_t, int32_t, int32_t, uint32_t) {}
    void fillRoundRect(int32_t, int32_t, int32_t, int32_t, int32_t, uint32_t) {}
    void drawRoundRect(int32_t, int32_t, int32_t, int32_t, int32_t, uint32_t) {}
};
