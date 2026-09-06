# Native playtest

Compiles and runs `TicTacToeESP32/GameLogic.h/.cpp` (the whole rules engine
and minimax AI) and `TicTacToeESP32/Display.h/.cpp`'s pure touch hit-testing
math **directly on your desktop** — the real, unmodified files that get
flashed to the board, not a copy — against small no-op stand-ins for
`Arduino.h`/`TFT_eSPI.h` (`stubs/`). This runs the whole game logic and
touch-math thousands of times in under a second, instead of one slow manual
tap at a time on real hardware.

**What this proves**: the AI is genuinely unbeatable (an exhaustive search of
every possible game finds zero human wins), turn order and illegal-move
rejection are correct, the reported winning line always matches three real
cells holding the same symbol, and every pixel inside the touch grid/button
resolves to the right cell with no gaps, overlaps, or off-by-one edges.

**What this can't prove** — nothing about actual pixel rendering, real touch
calibration, real SPI timing, or your board's specific pin wiring. Only the
physical board can confirm those; see the top-level README's Troubleshooting
section for that half of verification.

## Build and run

Needs a plain C++17 desktop compiler — MSVC, g++, or clang all work, since
none of this code touches anything ESP32-specific.

**Windows (MSVC / Visual Studio Build Tools)**, from a Developer Command
Prompt (or `vcvarsall.bat x64` first):
```
cl /nologo /EHsc /std:c++17 /I stubs /Fe:playtest.exe playtest.cpp ..\TicTacToeESP32\GameLogic.cpp ..\TicTacToeESP32\Display.cpp
.\playtest.exe
```

**macOS / Linux (g++ or clang)**:
```
g++ -std=c++17 -I stubs -o playtest playtest.cpp ../TicTacToeESP32/GameLogic.cpp ../TicTacToeESP32/Display.cpp
./playtest
```

Exit code is `0` if every check passed, `1` otherwise — safe to wire into a
CI job later if this project grows more games, the same spirit as
GameSuite's own JUnit tests and the relay server's `smoke-test.js`.
