# Native playtest

Compiles and runs each game's own rules-engine-and-AI `.h/.cpp` pair and its
Display `.h/.cpp` pure touch hit-testing math **directly on your desktop** —
the real, unmodified files that get flashed to the board, not a copy —
against small no-op stand-ins for `Arduino.h`/`TFT_eSPI.h` (`stubs/`). This
runs the whole game logic and touch-math thousands of times in under a
second, instead of one slow manual tap at a time on real hardware.

Five games have a native playtest driver today, one executable each:
`playtest.cpp` (Tic-Tac-Toe), `checkers_playtest.cpp`, `chess_playtest.cpp`,
`uno_playtest.cpp`, and `mancala_playtest.cpp` — see each file's own
top-of-file comment for what it specifically verifies (Tic-Tac-Toe's is an
exhaustive full-game-tree search; Checkers and Chess mix hand-built rules
positions with a bounded tree walk and, for Chess, perft move-count
verification; UNO covers rules/deck/AI-heuristic/touch-math; Mancala covers
capture/extra-turn/round-end-sweep rules, a stone-conservation invariant, and
its EASY/MEDIUM/HARD difficulty tiers). Dominoes doesn't have one yet — add
`dominoes_playtest.cpp` following the same pattern once its GameLogic/
Display pair exists.

**What this proves**: for Tic-Tac-Toe, the AI is genuinely unbeatable (an
exhaustive search of every possible game finds zero human wins); across all
five games, turn order and illegal-move rejection are correct, reported
win/capture/game-ending conditions match real board state, and every pixel
inside each game's touch grid/buttons resolves to the right cell with no
gaps, overlaps, or off-by-one edges.

**What this can't prove** — nothing about actual pixel rendering, real touch
calibration, real SPI timing, or your board's specific pin wiring. Only the
physical board can confirm those; see the top-level README's Troubleshooting
section for that half of verification.

## Build and run

Needs a plain C++17 desktop compiler — MSVC, g++, or clang all work, since
none of this code touches anything ESP32-specific. Each game is its own
self-contained executable — build whichever one(s) you need.

### Tic-Tac-Toe

`playtest.cpp` also exercises the shared arcade menu/chrome (touch hit-testing
for `MenuScreen.cpp`'s tiles/scroll arrows and `Chrome.cpp`'s home button),
so — despite the name — it needs those two plus `Theme.cpp` (their font/color
dependency) linked in alongside the Tic-Tac-Toe game files, not just
`GameLogic.cpp`/`Display.cpp`. This was missed in an earlier revision of this
doc; verified against a real build (0 link errors) as of this pass.

**Windows (MSVC / Visual Studio Build Tools)**, from a Developer Command
Prompt (or `vcvarsall.bat x64` first):
```
cl /nologo /EHsc /std:c++17 /I stubs /Fe:playtest.exe playtest.cpp ..\TicTacToeESP32\GameLogic.cpp ..\TicTacToeESP32\Display.cpp ..\TicTacToeESP32\Chrome.cpp ..\TicTacToeESP32\MenuScreen.cpp ..\TicTacToeESP32\Theme.cpp
.\playtest.exe
```

**macOS / Linux (g++ or clang)**:
```
g++ -std=c++17 -I stubs -o playtest playtest.cpp ../TicTacToeESP32/GameLogic.cpp ../TicTacToeESP32/Display.cpp ../TicTacToeESP32/Chrome.cpp ../TicTacToeESP32/MenuScreen.cpp ../TicTacToeESP32/Theme.cpp
./playtest
```

### Checkers

```
cl /nologo /EHsc /std:c++17 /I stubs /Fe:checkers_playtest.exe checkers_playtest.cpp ..\TicTacToeESP32\CheckersLogic.cpp ..\TicTacToeESP32\CheckersDisplay.cpp
.\checkers_playtest.exe
```
```
g++ -std=c++17 -I stubs -o checkers_playtest checkers_playtest.cpp ../TicTacToeESP32/CheckersLogic.cpp ../TicTacToeESP32/CheckersDisplay.cpp
./checkers_playtest
```

### Chess

Also needs `Chrome.cpp` (the shared UI-chrome module `ChessDisplay.cpp`
draws through):
```
cl /nologo /EHsc /std:c++17 /I stubs /Fe:chess_playtest.exe chess_playtest.cpp ..\TicTacToeESP32\ChessLogic.cpp ..\TicTacToeESP32\ChessDisplay.cpp ..\TicTacToeESP32\Chrome.cpp
.\chess_playtest.exe
```
```
g++ -std=c++17 -I stubs -o chess_playtest chess_playtest.cpp ../TicTacToeESP32/ChessLogic.cpp ../TicTacToeESP32/ChessDisplay.cpp ../TicTacToeESP32/Chrome.cpp
./chess_playtest
```

### UNO

```
cl /nologo /EHsc /std:c++17 /I stubs /Fe:uno_playtest.exe uno_playtest.cpp ..\TicTacToeESP32\UnoLogic.cpp ..\TicTacToeESP32\UnoDisplay.cpp
.\uno_playtest.exe
```
```
g++ -std=c++17 -I stubs -o uno_playtest uno_playtest.cpp ../TicTacToeESP32/UnoLogic.cpp ../TicTacToeESP32/UnoDisplay.cpp
./uno_playtest
```

### Mancala

```
cl /nologo /EHsc /std:c++17 /I stubs /Fe:mancala_playtest.exe mancala_playtest.cpp ..\TicTacToeESP32\MancalaLogic.cpp ..\TicTacToeESP32\MancalaDisplay.cpp
.\mancala_playtest.exe
```
```
g++ -std=c++17 -I stubs -o mancala_playtest mancala_playtest.cpp ../TicTacToeESP32/MancalaLogic.cpp ../TicTacToeESP32/MancalaDisplay.cpp
./mancala_playtest
```

Exit code is `0` if every check passed, `1` otherwise for all five — safe to
wire into a CI job, the same spirit as GameSuite's own JUnit tests and the
relay server's `smoke-test.js`.
