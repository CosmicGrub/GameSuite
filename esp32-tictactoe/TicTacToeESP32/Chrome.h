#pragma once
#include <TFT_eSPI.h>

// Shared "OS chrome" every game screen uses: a fixed status bar across the
// top of the screen with a built-in Home button on the left and whatever
// status text the current game supplies on the right. This used to be
// duplicated inside Tic-Tac-Toe's own Display.cpp -- pulled out here so
// every future game (Checkers, Chess, UNO, ...) gets an identical,
// always-in-the-same-place Home button for free, drawn and hit-tested in
// exactly one place, instead of each game's Display module reimplementing
// its own slightly-different version.
//
// Fixed geometry, not a per-game Layout struct: the bar always spans the
// full screen width starting at y=0, so any game can call these without
// needing to compute or pass its own layout for this part of the screen --
// it only needs to leave CHROME_BAR_HEIGHT px of room below it.

extern const int16_t CHROME_BAR_HEIGHT;

void drawChromeBar(TFT_eSPI &tft, const char *statusText);

// Pure hit-test, fixed geometry -- the .ino checks this ONCE, before
// dispatching a touch to whichever game is currently active, so no
// individual game's touch handler needs to know the Home button exists.
bool hitTestChromeHome(int16_t touchX, int16_t touchY);
