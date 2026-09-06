// Minimal stand-in for Arduino.h so the EXACT, unmodified GameLogic.h/.cpp
// shipped in esp32-tictactoe/TicTacToeESP32/ can be compiled and run natively
// here for a real logic playtest, without needing the ESP32 toolchain
// (GameLogic itself never calls a real Arduino API -- it only needs the
// fixed-width integer types every embedded C++ project uses).
#pragma once
#include <cstdint>
