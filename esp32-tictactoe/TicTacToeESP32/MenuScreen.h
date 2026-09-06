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
    int16_t tileX, tileY, tileW, tileH, tileGap; // slot i occupies tileY + i*(tileH+tileGap)
    // How many tiles actually fit in the vertical space at once -- with more
    // games than this, the list scrolls (see the up/down arrows below)
    // rather than running off the bottom of the screen.
    uint8_t maxVisibleTiles;
};

// Computed from Config.h's SCREEN_WIDTH/SCREEN_HEIGHT and how many tiles need
// to fit -- callers should treat this as fixed once computed for a given
// gameCount, the same as Display.h's computeLayout().
MenuLayout computeMenuLayout(uint8_t gameCount);

// canScrollUp/canScrollDown control whether the up/down arrow buttons (see
// below) draw active or dimmed -- ArcadeOS.ino tracks the actual scroll
// offset (this module has no memory of its own) and passes those in from
// offset>0 / offset+maxVisibleTiles<gameCount.
void drawMenuChrome(TFT_eSPI &tft, const MenuLayout &layout, bool canScrollUp, bool canScrollDown);

// `slot` is the tile's position WITHIN the currently visible window (0 = the
// topmost visible tile), NOT a game's absolute index in the full list --
// ArcadeOS.ino maps between the two via its own scroll offset
// (slot = gameIndex - scrollOffset), the same way it already tracks which
// round/state is active elsewhere in this project. `enabled=false` draws the
// tile visibly dimmed with a "coming soon" label -- used for games this
// project's README already earmarks (Mancala, Dominoes) but hasn't built
// yet, so the menu reads as a real roadmap instead of hiding them entirely.
void drawMenuTile(TFT_eSPI &tft, const MenuLayout &layout, uint8_t slot, const char *label, bool enabled);

// Returns true and writes the tapped tile's SLOT (0..visibleCount-1, see
// drawMenuTile's comment on slot vs. absolute index) when (touchX, touchY)
// falls inside one of the `visibleCount` currently-shown tiles -- including a
// disabled one, since ArcadeOS.ino (not this module) decides whether a
// disabled tile's tap does anything.
bool hitTestMenuTile(const MenuLayout &layout, uint8_t visibleCount, int16_t touchX, int16_t touchY, uint8_t &outSlot);

// Up/down scroll buttons in a column to the right of the tile list -- tap
// targets, not a drag/swipe gesture, matching this project's existing
// discrete-tap interaction style everywhere else (Home, Play Again, Sleep)
// rather than something less reliable on resistive touch. ArcadeOS.ino
// decides whether tapping one actually does anything (i.e. whether the
// requested scroll is in bounds) -- these are pure hit-tests only.
bool hitTestScrollUp(const MenuLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestScrollDown(const MenuLayout &layout, int16_t touchX, int16_t touchY);

// A "Sleep" button in the top-right of the title bar (drawn automatically as
// part of drawMenuChrome, no separate draw call needed) -- this board only
// has BOOT and RESET buttons, neither meant for putting the device to sleep,
// so this is the only way to do it deliberately. Offered ONLY from the home
// menu, never mid-game, so it can't be triggered by an accidental tap while
// actually playing -- ArcadeOS.ino is responsible for the actual sleep/wake
// behavior (ESP32-specific, not something this pure-rendering module does).
bool hitTestSleepButton(const MenuLayout &layout, int16_t touchX, int16_t touchY);
