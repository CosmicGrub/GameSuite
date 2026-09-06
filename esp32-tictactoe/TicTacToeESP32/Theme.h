#pragma once
#include <TFT_eSPI.h>

// A shared visual identity for every screen in this arcade -- a deliberately
// chosen color palette (instead of TFT_eSPI's raw named constants like
// TFT_CYAN/TFT_ORANGE/TFT_NAVY, which read as harsh, fully-saturated library-
// demo colors) plus real anti-aliased fonts (instead of the default 5x7 GLCD
// bitmap font every screen used until now). Every Display module in this
// project should draw through THEME_* colors and themeLoadFont()/
// themeUnloadFont() rather than picking its own colors or using the default
// font, so the whole arcade reads as one designed product instead of a
// collection of separately-styled screens.
//
// Fonts: Google's Noto Sans Bold, already converted to TFT_eSPI's anti-
// aliased "smooth font" flash-array format and bundled with the library's
// own examples (Smooth Fonts/FLASH_Array/*) -- copied into this project as
// NotoSansBold15.h/NotoSansBold36.h rather than hand-converted, since no
// external font-conversion tool is available in this environment and these
// are already exactly the right format (no filesystem/SPIFFS needed, just
// #include the header and tft.loadFont() the array). Noto Sans is released
// under the SIL Open Font License, which permits embedding like this.
// Costs ~13KB (15pt) + ~53KB (36pt) of flash once compiled -- trivial against
// this board's 4MB.

// ---- Palette ----
// A cool charcoal/slate base (not pure black) with a teal accent for the
// human's side and a warm amber accent for the AI's side -- chosen so every
// game's "you" vs "opponent" marks (X/O, checkers men, chess pieces, card
// piles) share one consistent color language across the whole arcade,
// instead of each game picking its own arbitrary pair.
constexpr uint16_t themeRGB(uint8_t r, uint8_t g, uint8_t b) {
    return ((uint16_t)(r & 0xF8) << 8) | ((uint16_t)(g & 0xFC) << 3) | (b >> 3);
}

static const uint16_t THEME_BG          = themeRGB(16, 20, 28);  // deep slate -- main background, not flat black
static const uint16_t THEME_SURFACE     = themeRGB(30, 38, 52);  // panels/bars/tiles sitting on THEME_BG
static const uint16_t THEME_SURFACE_ALT = themeRGB(44, 54, 72);  // a step lighter -- hover/selected surfaces
static const uint16_t THEME_BORDER      = themeRGB(96, 108, 128); // subtle outline, not pure white
static const uint16_t THEME_TEXT        = themeRGB(236, 240, 245); // near-white, not harsh pure white
static const uint16_t THEME_TEXT_DIM    = themeRGB(150, 160, 175); // secondary text / disabled labels

static const uint16_t THEME_HUMAN       = themeRGB(64, 200, 190);  // teal -- the human's side everywhere (X, human pieces, "you")
static const uint16_t THEME_AI          = themeRGB(235, 150, 70);  // warm amber -- the AI's side everywhere
static const uint16_t THEME_SUCCESS     = themeRGB(110, 200, 110); // wins / legal / positive
static const uint16_t THEME_DANGER      = themeRGB(220, 90, 90);   // losses / illegal / negative
static const uint16_t THEME_WARNING     = themeRGB(230, 195, 90);  // check / caution states

// Checkerboard-style boards (Checkers, Chess) get their own light/dark square
// pair, related to but distinct from the general surface colors so the board
// itself reads as a physical game board, not just another panel.
static const uint16_t THEME_BOARD_LIGHT = themeRGB(58, 68, 88);
static const uint16_t THEME_BOARD_DARK  = themeRGB(34, 40, 54);

// ---- Fonts ----
// Two sizes covers this project's real needs: SMALL for body/status/button
// text and board pieces, LARGE for hero moments (the arcade title, a big
// win/lose banner). Load the one you need, draw, and prefer leaving SMALL
// loaded as the default between screens rather than unloading fully, since
// almost everything uses it.
void themeLoadFontSmall(TFT_eSPI &tft);
void themeLoadFontLarge(TFT_eSPI &tft);
void themeUnloadFont(TFT_eSPI &tft);
