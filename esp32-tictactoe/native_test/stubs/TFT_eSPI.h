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
#include <vector>

#define TFT_BLACK 0
#define TFT_WHITE 1
#define TFT_DARKGREY 2
#define TFT_CYAN 3
#define TFT_ORANGE 4
#define TFT_GREEN 5
#define TFT_NAVY 6
#define TFT_DARKGREEN 7
#define MC_DATUM 4
#define ML_DATUM 3
#define HIGH 1

// A single recorded drawLine() call -- see TFT_eSPI::capturedLines below.
// This is how a REAL bug was caught after the fact (Display.cpp's X mark
// drew the same "\" diagonal twice instead of a "\" and a "/", so only half
// an X ever rendered -- hit-test math alone could never have caught this,
// since it says nothing about what shape actually got drawn). Recording
// drawLine() calls lets native_test assert on the actual line segments a
// draw function requests, not just where taps resolve.
struct DrawLineCall {
    int32_t x0, y0, x1, y1;
    uint32_t color;
};

class TFT_eSPI {
public:
    // Test hook: every drawLine() call appends here. Tests that care should
    // clear this (capturedLines.clear()) immediately before the draw call
    // they want to inspect, then check its contents afterward.
    static std::vector<DrawLineCall> capturedLines;

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
    void drawLine(int32_t x0, int32_t y0, int32_t x1, int32_t y1, uint32_t color) {
        capturedLines.push_back({x0, y0, x1, y1, color});
    }
    void drawCircle(int32_t, int32_t, int32_t, uint32_t) {}
    void fillRoundRect(int32_t, int32_t, int32_t, int32_t, int32_t, uint32_t) {}
    void drawRoundRect(int32_t, int32_t, int32_t, int32_t, int32_t, uint32_t) {}
    // Theme.h's smooth-font loading -- no-ops here, same reasoning as every
    // other rendering method in this stub: this proves layout/hit-test MATH,
    // never actual pixels or fonts, which only the physical display can.
    void loadFont(const uint8_t *) {}
    void unloadFont() {}
};

inline std::vector<DrawLineCall> TFT_eSPI::capturedLines;
