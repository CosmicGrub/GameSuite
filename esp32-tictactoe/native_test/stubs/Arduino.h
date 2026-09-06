// Minimal stand-in for Arduino.h so the EXACT, unmodified GameLogic.h/.cpp
// shipped in esp32-tictactoe/TicTacToeESP32/ can be compiled and run natively
// here for a real logic playtest, without needing the ESP32 toolchain
// (GameLogic itself never calls a real Arduino API -- it only needs the
// fixed-width integer types every embedded C++ project uses).
#pragma once
#include <cstdint>

// Real Arduino.h defines PROGMEM as an attribute that places const data in
// flash instead of RAM -- meaningless on a desktop compiler (everything's
// just RAM here), so it's defined to nothing. Needed so Theme.cpp's
// NotoSansBold15.h/36.h font-array headers (which use PROGMEM directly,
// without going through the real TFT_eSPI.h) compile unchanged natively.
#define PROGMEM

// Real timing stand-ins for code that paces itself against wall-clock time
// (e.g. CheckersDisplay.cpp's animateCheckersMove() frame pacing) -- native_
// test doesn't care about real elapsed time, only that the code path runs
// without hanging, so millis() always reads 0 and delay() is a no-op. Safe:
// nothing in this project loops UNTIL a time condition is met using these:
// animateCheckersMove()'s loop is bounded by a fixed step count regardless
// of what millis()/delay() do.
inline unsigned long millis() { return 0; }
inline void delay(unsigned long) {}
