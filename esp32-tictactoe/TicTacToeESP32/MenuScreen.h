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

// A "Sleep" button in the top-right of the title bar (drawn automatically as
// part of drawMenuChrome, no separate draw call needed) -- this board only
// has BOOT and RESET buttons, neither meant for putting the device to sleep,
// so this is the only way to do it deliberately. Offered ONLY from the home
// menu, never mid-game, so it can't be triggered by an accidental tap while
// actually playing -- ArcadeOS.ino is responsible for the actual sleep/wake
// behavior (ESP32-specific, not something this pure-rendering module does).
bool hitTestSleepButton(const MenuLayout &layout, int16_t touchX, int16_t touchY);
