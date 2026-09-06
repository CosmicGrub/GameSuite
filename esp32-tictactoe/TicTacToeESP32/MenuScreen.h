#pragma once
#include <TFT_eSPI.h>

// The arcade's home screen: a plain vertical list of game tiles, one per
// installed game. This module knows nothing about any specific game's rules
// or even how many games can exist beyond a count -- ArcadeOS.ino owns the
// actual game list and what happens when a tile is tapped, the same
// engine/screen split every other module in this project uses. Pure
// rendering + pure touch math, same as Display.h, so it can be exercised by
// native_test the same way.

struct MenuLayout {
    int16_t titleY, titleH;
    int16_t tileX, tileY, tileW, tileH, tileGap; // tile i occupies tileY + i*(tileH+tileGap)
};

// Computed from Config.h's SCREEN_WIDTH/SCREEN_HEIGHT and how many tiles need
// to fit -- callers should treat this as fixed once computed for a given
// gameCount, the same as Display.h's computeLayout().
MenuLayout computeMenuLayout(uint8_t gameCount);

void drawMenuChrome(TFT_eSPI &tft, const MenuLayout &layout);

// `enabled=false` draws the tile visibly dimmed with a "coming soon" label --
// used for games this project's README already earmarks (Mancala, Dominoes)
// but hasn't built yet, so the menu reads as a real roadmap instead of hiding
// them entirely.
void drawMenuTile(TFT_eSPI &tft, const MenuLayout &layout, uint8_t index, const char *label, bool enabled);

// Returns true and writes the tapped tile's index (0..gameCount-1) when
// (touchX, touchY) falls inside one of the gameCount tiles this layout was
// computed for -- including a disabled one, since ArcadeOS.ino (not this
// module) decides whether a disabled tile's tap does anything.
bool hitTestMenuTile(const MenuLayout &layout, uint8_t gameCount, int16_t touchX, int16_t touchY, uint8_t &outIndex);
