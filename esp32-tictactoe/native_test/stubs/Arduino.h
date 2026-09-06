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
