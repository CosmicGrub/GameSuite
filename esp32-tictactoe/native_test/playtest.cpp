// Native playtest driver for the ESP32 Tic-Tac-Toe project's GameLogic.h/.cpp
// AND Display.cpp's pure hit-testing math -- compiles and runs the EXACT,
// unmodified files shipped in esp32-tictactoe/TicTacToeESP32/ on a desktop
// (against a no-op TFT_eSPI stub for the parts that need one), so the
// rules/AI/touch-math can be exercised thousands of times in a second
// instead of only via slow manual taps on real hardware. This does NOT and
// CANNOT verify actual pixel rendering, touch calibration, or any real
// SPI/TFT_eSPI behavior -- see the accompanying summary for exactly what
// this does and doesn't prove.

#include <cstdio>
#include <vector>
// Includes the REAL, shipped files from ../TicTacToeESP32/ directly (never a
// duplicated copy) so this test always exercises exactly what gets flashed
// to the board -- see this folder's README.md for how to build/run it.
#include "../TicTacToeESP32/GameLogic.h"
#include "../TicTacToeESP32/Display.h"
#include "../TicTacToeESP32/Chrome.h"
#include "../TicTacToeESP32/MenuScreen.h"
#include "../TicTacToeESP32/Config.h"

static char glyph(uint8_t v) {
    if (v == HUMAN) return 'X';
    if (v == AI) return 'O';
    return '.';
}

static void printBoard(const TicTacToeBoard &b) {
    for (int row = 0; row < 3; row++) {
        printf("  %c %c %c\n", glyph(b.at(row * 3 + 0)), glyph(b.at(row * 3 + 1)), glyph(b.at(row * 3 + 2)));
    }
}

static const char *resultName(RoundResult r) {
    switch (r) {
        case RoundResult::IN_PROGRESS: return "in progress";
        case RoundResult::HUMAN_WINS: return "HUMAN WINS";
        case RoundResult::AI_WINS: return "AI WINS";
        case RoundResult::DRAW: return "DRAW";
    }
    return "?";
}

// ---------------------------------------------------------------------------
// 1) A watchable playthrough: human plays a deliberately mediocre game (center
//    first, then just whatever's open) so we get to actually see the AI adapt
//    move by move, the way a real playtest session would.
// ---------------------------------------------------------------------------
static void watchOneGame() {
    printf("=== Playthrough: human plays center, then first-available ===\n\n");
    TicTacToeBoard board;
    board.reset();

    int humanMoveOrder[9] = {4, 0, 1, 2, 3, 5, 6, 7, 8}; // center first, then reading order
    int nextHumanIdx = 0;

    int moveNum = 1;
    while (board.result() == RoundResult::IN_PROGRESS) {
        if (board.isHumanTurn()) {
            // pick the next legal move from our fixed preference order
            uint8_t cell = 9;
            while (nextHumanIdx < 9) {
                uint8_t candidate = humanMoveOrder[nextHumanIdx++];
                if (board.at(candidate) == EMPTY) { cell = candidate; break; }
            }
            bool ok = board.playHuman(cell);
            printf("Move %d: human plays cell %d (%s)\n", moveNum++, cell, ok ? "ok" : "REJECTED -- BUG");
        } else {
            board.playAi();
            printf("Move %d: AI plays\n", moveNum++);
        }
        printBoard(board);
        printf("\n");
    }
    printf("Result: %s\n\n", resultName(board.result()));
}

// ---------------------------------------------------------------------------
// 2) Exhaustive adversarial test: try EVERY possible sequence of human moves
//    (branching at every human turn, not just one fixed strategy) and confirm
//    the AI -- moving second, per this game's fixed design -- never loses a
//    single line of play. This is the same property GameSuite's Android
//    TicTacToeGameTest.kt checks for its own HARD-tier minimax bot, just
//    exercised exhaustively here instead of via a few hand-picked positions.
// ---------------------------------------------------------------------------
static long gamesPlayed = 0;
static long aiWins = 0, draws = 0, humanWins = 0;

static void explore(TicTacToeBoard board) {
    RoundResult r = board.result();
    if (r != RoundResult::IN_PROGRESS) {
        gamesPlayed++;
        if (r == RoundResult::AI_WINS) aiWins++;
        else if (r == RoundResult::DRAW) draws++;
        else if (r == RoundResult::HUMAN_WINS) {
            humanWins++;
            printf("!! FOUND A HUMAN WIN -- AI IS BEATABLE. Board:\n");
            printBoard(board);
        }
        return;
    }

    if (board.isHumanTurn()) {
        // Branch over every legal human move -- this is what makes it exhaustive
        // rather than just testing one human "style" of play.
        for (uint8_t cell = 0; cell < 9; cell++) {
            if (board.at(cell) != EMPTY) continue;
            TicTacToeBoard next = board;
            next.playHuman(cell);
            explore(next);
        }
    } else {
        // The AI is deterministic (always picks its single best-scoring move),
        // so there's exactly one branch here, not nine.
        TicTacToeBoard next = board;
        next.playAi();
        explore(next);
    }
}

static void exhaustiveAdversarialTest() {
    printf("=== Exhaustive test: every possible human move sequence ===\n");
    TicTacToeBoard start;
    start.reset();
    explore(start);
    printf("Games explored: %ld\n", gamesPlayed);
    printf("  AI wins:    %ld\n", aiWins);
    printf("  Draws:      %ld\n", draws);
    printf("  Human wins: %ld  <-- must be 0 for the AI to be genuinely unbeatable\n\n", humanWins);
}

// ---------------------------------------------------------------------------
// 3) Rule-correctness spot checks: illegal moves are rejected, the AI's
//    winning-line detection matches the actual three cells that won, and a
//    played cell can never be played again.
// ---------------------------------------------------------------------------
static int checksRun = 0, checksFailed = 0;
static void check(const char *label, bool condition) {
    checksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) checksFailed++;
}

static void ruleCorrectnessChecks() {
    printf("=== Rule-correctness checks ===\n");

    TicTacToeBoard b;
    b.reset();
    check("fresh board: result is IN_PROGRESS", b.result() == RoundResult::IN_PROGRESS);
    check("fresh board: it's human's turn", b.isHumanTurn());

    check("human can play an empty cell", b.playHuman(0));
    check("turn passes to AI after a legal human move", !b.isHumanTurn());
    check("human cannot play again out of turn", !b.playHuman(1));

    b.playAi();
    check("turn returns to human after the AI moves", b.isHumanTurn());
    check("the AI actually filled some cell (board isn't still all-human/empty)",
          b.at(0) == HUMAN && (b.at(1) == AI || b.at(2) == AI || b.at(3) == AI || b.at(4) == AI ||
                                b.at(5) == AI || b.at(6) == AI || b.at(7) == AI || b.at(8) == AI));

    check("cannot play an already-occupied cell", !b.playHuman(0));

    // Force a specific human win to check winningLine() reports the real line:
    // X at 0, O elsewhere, X completes the top row 0-1-2.
    TicTacToeBoard win;
    win.reset();
    // 0:X 3:O 1:X 4:O 2:X (top row) -- drive it manually via direct cell writes
    // isn't exposed on purpose (see GameLogic.h), so play it out for real,
    // accepting whatever the AI actually does at moves 4 (O@3) and 4 (O@4)
    // might not happen since the AI plays optimally and would block -- this
    // check instead verifies winningLine() is internally consistent whenever
    // *any* game (from the exhaustive test above) actually ends in a win,
    // which is checked inline below instead of forcing an artificial one.
    (void)win;

    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);
}

// Re-runs the exhaustive explore(), but this time also validates that
// whenever a game ends in a win, winningLine() names 3 real, in-bounds cells
// that actually hold the same, winning symbol.
static long lineChecked = 0, lineFailed = 0;
static void exploreAndCheckWinningLine(TicTacToeBoard board) {
    RoundResult r = board.result();
    if (r == RoundResult::HUMAN_WINS || r == RoundResult::AI_WINS) {
        int8_t line[3];
        board.winningLine(line);
        lineChecked++;
        uint8_t expect = (r == RoundResult::HUMAN_WINS) ? HUMAN : AI;
        bool ok = line[0] >= 0 && line[1] >= 0 && line[2] >= 0 &&
                  board.at(line[0]) == expect && board.at(line[1]) == expect && board.at(line[2]) == expect;
        if (!ok) {
            lineFailed++;
            printf("!! winningLine() mismatch on a %s board:\n", r == RoundResult::HUMAN_WINS ? "HUMAN_WINS" : "AI_WINS");
            printBoard(board);
        }
        return;
    }
    if (r != RoundResult::IN_PROGRESS) return;

    if (board.isHumanTurn()) {
        for (uint8_t cell = 0; cell < 9; cell++) {
            if (board.at(cell) != EMPTY) continue;
            TicTacToeBoard next = board;
            next.playHuman(cell);
            exploreAndCheckWinningLine(next);
        }
    } else {
        TicTacToeBoard next = board;
        next.playAi();
        exploreAndCheckWinningLine(next);
    }
}

// ---------------------------------------------------------------------------
// 4) Touch hit-testing: Display.cpp's computeLayout()/hitTestCell()/
//    hitTestPlayAgainButton() are pure math with no TFT_eSPI calls -- real
//    rendering can't be checked without the physical panel, but "does a tap
//    at pixel (x,y) resolve to the cell/button a human would expect" can be,
//    against the exact same layout math the real firmware uses.
// ---------------------------------------------------------------------------
static int layoutChecksRun = 0, layoutChecksFailed = 0;
static void checkLayout(const char *label, bool condition) {
    layoutChecksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) layoutChecksFailed++;
}

static void touchHitTestChecks() {
    printf("=== Touch hit-testing checks (Display.cpp, against Config.h's real screen size) ===\n");
    Layout l = computeLayout();
    int16_t gridSize = l.cellSize * 3;

    printf("  layout: grid at (%d,%d), cell=%dpx, button at (%d,%d) %dx%d\n",
           l.gridX, l.gridY, l.cellSize, l.buttonX, l.buttonY, l.buttonW, l.buttonH);

    checkLayout("grid fits within the screen width", l.gridX >= 0 && l.gridX + gridSize <= SCREEN_WIDTH);
    checkLayout("grid fits within the screen height (below the status bar)",
                l.gridY >= l.statusY + l.statusH && l.gridY + gridSize <= SCREEN_HEIGHT);
    checkLayout("play-again button fits within the screen", l.buttonX >= 0 && l.buttonX + l.buttonW <= SCREEN_WIDTH &&
                l.buttonY >= 0 && l.buttonY + l.buttonH <= SCREEN_HEIGHT);

    // Every cell's own center must resolve back to that same cell index --
    // this is the single most important property, since it's what an actual
    // fingertip tap near the middle of a cell will do.
    bool allCentersOk = true;
    for (uint8_t cell = 0; cell < 9; cell++) {
        uint8_t col = cell % 3, row = cell / 3;
        int16_t cx = l.gridX + col * l.cellSize + l.cellSize / 2;
        int16_t cy = l.gridY + row * l.cellSize + l.cellSize / 2;
        uint8_t hit;
        bool ok = hitTestCell(l, cx, cy, hit) && hit == cell;
        if (!ok) {
            allCentersOk = false;
            printf("    cell %d center (%d,%d) resolved to hit=%d (expected %d)\n", cell, cx, cy, hit, cell);
        }
    }
    checkLayout("every cell's own center taps that same cell", allCentersOk);

    // Sweep every pixel inside the grid and confirm no gaps (every in-bounds
    // pixel hits SOME cell) and no overlaps (the row/col math never produces
    // an out-of-range 0..8 index) -- a full pixel sweep is cheap for a 192x192
    // grid and catches any off-by-one in the row/col division far more
    // reliably than spot-checking a few points would.
    bool allInBoundsPixelsResolve = true;
    for (int16_t x = l.gridX; x < l.gridX + gridSize; x++) {
        for (int16_t y = l.gridY; y < l.gridY + gridSize; y++) {
            uint8_t hit;
            if (!hitTestCell(l, x, y, hit) || hit > 8) { allInBoundsPixelsResolve = false; }
        }
    }
    checkLayout("every in-bounds grid pixel resolves to a valid 0-8 cell (no gaps/overlaps)", allInBoundsPixelsResolve);

    // Points just outside each edge of the grid must NOT be claimed by it.
    uint8_t discard;
    checkLayout("1px left of the grid is not a hit", !hitTestCell(l, l.gridX - 1, l.gridY + 1, discard));
    checkLayout("1px above the grid is not a hit", !hitTestCell(l, l.gridX + 1, l.gridY - 1, discard));
    checkLayout("1px right of the grid is not a hit", !hitTestCell(l, l.gridX + gridSize, l.gridY + 1, discard));
    checkLayout("1px below the grid is not a hit", !hitTestCell(l, l.gridX + 1, l.gridY + gridSize, discard));

    // Play-again button: its own center must hit, and its four corners-plus-one
    // (just outside each edge) must not.
    int16_t bcx = l.buttonX + l.buttonW / 2, bcy = l.buttonY + l.buttonH / 2;
    checkLayout("play-again button's own center is a hit", hitTestPlayAgainButton(l, bcx, bcy));
    checkLayout("1px left of the button is not a hit", !hitTestPlayAgainButton(l, l.buttonX - 1, bcy));
    checkLayout("1px above the button is not a hit", !hitTestPlayAgainButton(l, bcx, l.buttonY - 1));
    checkLayout("1px right of the button is not a hit", !hitTestPlayAgainButton(l, l.buttonX + l.buttonW, bcy));
    checkLayout("1px below the button is not a hit", !hitTestPlayAgainButton(l, bcx, l.buttonY + l.buttonH));

    // The shared "back to menu" Home button (Chrome.h/.cpp, used by every
    // game, not just Tic-Tac-Toe) -- its own footprint must be a hit, and a
    // point just outside it on the touch side that matters most (dead center
    // of the game grid, where a real finger spends most of its time) must
    // not be. Fixed geometry, no Layout param needed.
    checkLayout("chrome home button's own center is a hit", hitTestChromeHome(15, l.statusY + l.statusH / 2));
    checkLayout("chrome home button does not swallow taps in the middle of the status bar",
                !hitTestChromeHome(SCREEN_WIDTH / 2, l.statusY + l.statusH / 2));
    checkLayout("chrome home button does not swallow taps inside the game grid",
                !hitTestChromeHome(l.gridX + 10, l.gridY + 10));

    printf("Layout checks run: %d, failed: %d\n\n", layoutChecksRun, layoutChecksFailed);
}

// ---------------------------------------------------------------------------
// 5) Arcade home menu: MenuScreen.cpp's own pure touch math, checked the same
//    way as Display.cpp's above -- every tile's center resolves to that
//    tile's index, the gaps between tiles resolve to nothing, and neither
//    the title bar nor space below the last tile is mistaken for one.
// ---------------------------------------------------------------------------
static void menuHitTestChecks() {
    printf("=== Arcade menu touch-hitting checks (MenuScreen.cpp) ===\n");
    const uint8_t GAME_COUNT = 3; // mirrors ArcadeOS.ino's real MENU_GAME_COUNT
    MenuLayout ml = computeMenuLayout(GAME_COUNT);

    printf("  menu layout: title h=%d, first tile y=%d, tile %dx%d, gap=%d\n",
           ml.titleH, ml.tileY, ml.tileW, ml.tileH, ml.tileGap);

    bool allTilesOk = true;
    for (uint8_t i = 0; i < GAME_COUNT; i++) {
        int16_t cx = ml.tileX + ml.tileW / 2;
        int16_t cy = ml.tileY + i * (ml.tileH + ml.tileGap) + ml.tileH / 2;
        uint8_t hit;
        bool ok = hitTestMenuTile(ml, GAME_COUNT, cx, cy, hit) && hit == i;
        if (!ok) {
            allTilesOk = false;
            printf("    tile %d center (%d,%d) resolved to hit=%d\n", i, cx, cy, hit);
        }
    }
    checkLayout("every tile's own center taps that same tile index", allTilesOk);

    uint8_t discard;
    // The gap between tile 0 and tile 1 must not resolve to either.
    int16_t gapY = ml.tileY + ml.tileH + ml.tileGap / 2;
    checkLayout("the gap between tiles is not a hit", !hitTestMenuTile(ml, GAME_COUNT, ml.tileX + 10, gapY, discard));
    checkLayout("the title bar is not a hit", !hitTestMenuTile(ml, GAME_COUNT, SCREEN_WIDTH / 2, ml.titleY + ml.titleH / 2, discard));
    checkLayout("1px left of a tile is not a hit", !hitTestMenuTile(ml, GAME_COUNT, ml.tileX - 1, ml.tileY + ml.tileH / 2, discard));
    checkLayout("1px right of a tile is not a hit", !hitTestMenuTile(ml, GAME_COUNT, ml.tileX + ml.tileW, ml.tileY + ml.tileH / 2, discard));
    // A touch below the very last tile (as if the menu had fewer games than
    // this layout has room for) must not resolve to a phantom tile.
    int16_t belowLastTileY = ml.tileY + GAME_COUNT * (ml.tileH + ml.tileGap) + 20;
    checkLayout("space below the last tile is not a hit", !hitTestMenuTile(ml, GAME_COUNT, ml.tileX + 10, belowLastTileY, discard));

    // The "Sleep" button in the title bar's top-right corner -- its own
    // footprint is a hit, and it must not be confused with the title text's
    // own area (center of the title bar) or the tile list below it.
    checkLayout("sleep button's own center is a hit", hitTestSleepButton(ml, SCREEN_WIDTH - 4 - 35, ml.titleY + ml.titleH / 2));
    checkLayout("sleep button does not swallow taps at the title bar's center",
                !hitTestSleepButton(ml, SCREEN_WIDTH / 2, ml.titleY + ml.titleH / 2));
    checkLayout("sleep button does not swallow taps in the tile list",
                !hitTestSleepButton(ml, ml.tileX + 10, ml.tileY + 10));

    printf("\n");
}

int main() {
    watchOneGame();
    exhaustiveAdversarialTest();
    ruleCorrectnessChecks();

    printf("=== winningLine() correctness (checked across every AI-win game from the exhaustive test) ===\n");
    TicTacToeBoard start;
    start.reset();
    exploreAndCheckWinningLine(start);
    printf("Winning-line checks: %ld, mismatches: %ld\n\n", lineChecked, lineFailed);

    touchHitTestChecks();
    menuHitTestChecks();

    bool allGood = (humanWins == 0) && (checksFailed == 0) && (lineFailed == 0) && (layoutChecksFailed == 0);
    printf("=== OVERALL: %s ===\n", allGood ? "ALL PLAYTEST CHECKS PASSED" : "FAILURES FOUND -- SEE ABOVE");
    return allGood ? 0 : 1;
}
