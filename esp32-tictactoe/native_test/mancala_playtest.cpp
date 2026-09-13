// Native playtest driver for the ESP32 Mancala game's MancalaLogic.h/.cpp
// AND MancalaDisplay.cpp's pure hit-testing math -- compiles and runs the
// EXACT, unmodified files shipped in esp32-tictactoe/TicTacToeESP32/ on a
// desktop (against the project's shared no-op TFT_eSPI/Arduino stubs), the
// same spirit as native_test/checkers_playtest.cpp for Checkers. This does
// NOT and CANNOT verify actual pixel rendering, touch calibration, or any
// real SPI/TFT_eSPI behavior -- only the physical board can confirm that
// half.
//
// Like Checkers, this mixes several kinds of verification, each covering
// what it's actually good at:
//   1) Hand-built positions for the specific rules that are easy to get
//      wrong (a capture, an extra turn, the round-ending sweep including a
//      tie) -- exact and deterministic.
//   2) A bounded exhaustive tree walk from the real starting position,
//      checking a hard invariant (the board's total stone count never
//      changes -- stones only ever move between pits/stores, never
//      created or destroyed) at every single node reached.
//   3) Large random-vs-random (and separately, random-vs-the-real-AI)
//      self-play simulations, checking the game always reaches a legal
//      decisive result with no crashes and no invariant violations.
//   4) Difficulty-tier spot checks: EASY always picks a legal pit, MEDIUM
//      prefers an extra-turn pit when one exists, HARD picks the
//      objectively better of two hand-built options.

#include <cstdio>
#include <cstdlib>
#include <chrono>
#include <vector>
#include "../TicTacToeESP32/MancalaLogic.h"
#include "../TicTacToeESP32/MancalaDisplay.h"
#include "../TicTacToeESP32/Config.h"

static int checksRun = 0, checksFailed = 0;
static void check(const char *label, bool condition) {
    checksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) checksFailed++;
}

static const char *resultName(MancalaResult r) {
    switch (r) {
        case MancalaResult::IN_PROGRESS: return "in progress";
        case MancalaResult::HUMAN_WINS:  return "HUMAN WINS";
        case MancalaResult::AI_WINS:     return "AI WINS";
        case MancalaResult::DRAW:        return "DRAW";
    }
    return "?";
}

static void printPits(const MancalaBoard &b) {
    printf("    AI  : [13]=%2u  ", b.stonesAt(13));
    for (int8_t i = 12; i >= 7; i--) printf("%2u ", b.stonesAt(i));
    printf("\n");
    printf("    HUM :        ");
    for (uint8_t i = 0; i <= 5; i++) printf("%2u ", b.stonesAt(i));
    printf(" [6]=%2u\n", b.stonesAt(6));
}

static uint8_t totalStones(const MancalaBoard &b) {
    uint16_t total = 0;
    for (uint8_t i = 0; i < MANCALA_PIT_COUNT; i++) total += b.stonesAt(i);
    return (uint8_t)total;
}

static std::vector<uint8_t> collectLegalMoves(const MancalaBoard &b) {
    uint8_t moves[MANCALA_MAX_LEGAL_MOVES];
    uint8_t n = b.legalMoves(moves);
    return std::vector<uint8_t>(moves, moves + n);
}

// ---------------------------------------------------------------------------
// 1) A watchable playthrough: a random-legal human against the REAL HARD-
//    tier minimax AI, printed move by move.
// ---------------------------------------------------------------------------
static void watchOneGame() {
    printf("=== Playthrough: random-legal human vs. the real HARD-tier AI ===\n\n");
    MancalaBoard board;
    board.reset();
    board.setDifficulty(CpuDifficulty::HARD);
    int turn = 1;
    const int MAX_TURNS = 400;
    while (board.result() == MancalaResult::IN_PROGRESS && turn <= MAX_TURNS) {
        if (board.isHumanTurn()) {
            auto moves = collectLegalMoves(board);
            if (moves.empty()) {
                printf("!! no legal moves found for human but result() says IN_PROGRESS -- BUG\n");
                break;
            }
            uint8_t pit = moves[rand() % moves.size()];
            bool ok = board.playHuman(pit);
            printf("Turn %d: human sows pit %u %s%s\n", turn++, pit, ok ? "ok" : "REJECTED -- BUG",
                   board.lastMoveEarnedExtraTurn() ? " (extra turn!)" : "");
        } else {
            board.playAi();
            printf("Turn %d: AI sows pit %u%s\n", turn++, board.lastSowPathPit(0),
                   board.lastMoveEarnedExtraTurn() ? " (extra turn!)" : "");
        }
    }
    printPits(board);
    printf("Result after %d turns: %s (store: human=%u ai=%u)\n\n", turn - 1, resultName(board.result()),
           board.stonesAt(MANCALA_HUMAN_STORE), board.stonesAt(MANCALA_AI_STORE));
}

// ---------------------------------------------------------------------------
// 2) Hand-built rule-correctness scenarios
// ---------------------------------------------------------------------------
static void startingPositionChecks() {
    printf("--- Starting position ---\n");
    MancalaBoard b;
    b.reset();
    check("fresh board: result is IN_PROGRESS", b.result() == MancalaResult::IN_PROGRESS);
    check("fresh board: it's the human's turn", b.isHumanTurn());
    check("fresh board holds 48 stones total (4 x 12 pits)", totalStones(b) == 48);
    for (uint8_t i = 0; i <= 5; i++) check("human pit starts with 4 stones", b.stonesAt(i) == 4);
    for (uint8_t i = 7; i <= 12; i++) check("AI pit starts with 4 stones", b.stonesAt(i) == 4);
    check("both stores start empty", b.stonesAt(MANCALA_HUMAN_STORE) == 0 && b.stonesAt(MANCALA_AI_STORE) == 0);

    auto moves = collectLegalMoves(b);
    check("exactly 6 legal opening moves (one per human pit)", moves.size() == 6);
    printf("\n");
}

static void captureChecks() {
    printf("--- Hand-built scenario: landing in an empty own pit captures it + the opposite pit ---\n");
    MancalaBoard b;
    uint8_t setup[MANCALA_PIT_COUNT] = {
        /*0*/ 0, /*1*/ 1, /*2*/ 0, /*3*/ 2, /*4*/ 2, /*5*/ 2, /*store6*/ 0,
        /*7*/ 2, /*8*/ 2, /*9*/ 2, /*10*/ 3, /*11*/ 2, /*12*/ 2, /*store13*/ 0,
    };
    b.setPitsForTest(setup);
    b.setTurnForTest(true);

    check("sowing the single stone from pit 1 plays", b.playHuman(1));
    check("pit 1 is now empty", b.stonesAt(1) == 0);
    check("the landing pit (2) is empty (captured)", b.stonesAt(2) == 0);
    check("the opposite pit (10) is empty (captured)", b.stonesAt(10) == 0);
    check("human's store holds the swept total (1 + 3 = 4)", b.stonesAt(MANCALA_HUMAN_STORE) == 4);
    check("lastMoveCaptured() reports the capture", b.lastMoveCaptured());
    check("lastCaptureLandingPit() is 2", b.lastCaptureLandingPit() == 2);
    check("lastCaptureOppositePit() is 10", b.lastCaptureOppositePit() == 10);
    check("lastCaptureTotalSwept() is 4", b.lastCaptureTotalSwept() == 4);
    check("no extra turn from a capture", !b.lastMoveEarnedExtraTurn());
    check("turn passes to the AI", !b.isHumanTurn());
    printf("\n");
}

static void noCaptureWhenOppositeIsEmptyChecks() {
    printf("--- Hand-built scenario: landing in an empty own pit whose OPPOSITE is also empty does not capture ---\n");
    MancalaBoard b;
    uint8_t setup[MANCALA_PIT_COUNT] = {
        0, 1, 0, 2, 2, 2, 0,
        2, 2, 2, 0, 2, 2, 0, // pit 10 (opposite of pit 2) starts empty
    };
    b.setPitsForTest(setup);
    b.setTurnForTest(true);

    check("the sow plays", b.playHuman(1));
    check("the landing pit keeps its own single stone (no capture fired)", b.stonesAt(2) == 1);
    check("the (already-empty) opposite pit is still empty", b.stonesAt(10) == 0);
    check("human's store gained nothing", b.stonesAt(MANCALA_HUMAN_STORE) == 0);
    check("lastMoveCaptured() is false", !b.lastMoveCaptured());
    printf("\n");
}

static void extraTurnChecks() {
    printf("--- Hand-built scenario: landing in your own store earns an extra turn ---\n");
    MancalaBoard b;
    uint8_t setup[MANCALA_PIT_COUNT] = {
        2, 2, 2, 2, 2, 1, 3, // pit 5 holds exactly 1 stone -- lands exactly in the human store
        2, 2, 2, 2, 2, 2, 5,
    };
    b.setPitsForTest(setup);
    b.setTurnForTest(true);

    check("the sow plays", b.playHuman(5));
    check("pit 5 is now empty", b.stonesAt(5) == 0);
    check("human's store gained exactly 1", b.stonesAt(MANCALA_HUMAN_STORE) == 4);
    check("lastMoveEarnedExtraTurn() reports the extra turn", b.lastMoveEarnedExtraTurn());
    check("lastMoveCaptured() is false", !b.lastMoveCaptured());
    check("it's STILL the human's turn", b.isHumanTurn());
    check("result() is still IN_PROGRESS", b.result() == MancalaResult::IN_PROGRESS);
    printf("\n");
}

static void roundEndsWithAiWinChecks() {
    printf("--- Hand-built scenario: emptying your own side ends the round, sweeping the other side's stones ---\n");
    MancalaBoard b;
    uint8_t setup[MANCALA_PIT_COUNT] = {
        0, 0, 0, 0, 0, 1, 5, // only pit 5 has a stone left; it lands in the human's own store
        1, 2, 0, 0, 0, 3, 2,
    };
    b.setPitsForTest(setup);
    b.setTurnForTest(true);
    // This is a hand-built (not dealt-from-reset) position, so there's no
    // reason its own total has to be 48 -- only that whatever it starts as
    // is CONSERVED by the move (stones only ever move between pits/stores,
    // never created or destroyed), which is the actual invariant this
    // scenario means to check.
    uint8_t expectedTotal = totalStones(b);

    check("the sow plays", b.playHuman(5));
    check("every human pit is empty", b.stonesAt(0) == 0 && b.stonesAt(1) == 0 && b.stonesAt(2) == 0 &&
                                        b.stonesAt(3) == 0 && b.stonesAt(4) == 0 && b.stonesAt(5) == 0);
    check("every AI pit was swept to empty too", b.stonesAt(7) == 0 && b.stonesAt(8) == 0 && b.stonesAt(9) == 0 &&
                                                   b.stonesAt(10) == 0 && b.stonesAt(11) == 0 && b.stonesAt(12) == 0);
    check("human's store: 5 + 1 (this sow) = 6", b.stonesAt(MANCALA_HUMAN_STORE) == 6);
    check("AI's store: 2 + swept (1+2+0+0+0+3=6) = 8", b.stonesAt(MANCALA_AI_STORE) == 8);
    check("stone total is conserved even after the sweep", totalStones(b) == expectedTotal);
    check("result() is AI_WINS (8 > 6)", b.result() == MancalaResult::AI_WINS);
    check("no legal moves remain for either side", collectLegalMoves(b).empty());
    printf("\n");
}

static void roundEndsInDrawChecks() {
    printf("--- Hand-built scenario: the round-ending sweep can end in a tie ---\n");
    MancalaBoard b;
    uint8_t setup[MANCALA_PIT_COUNT] = {
        0, 0, 0, 0, 0, 1, 7,
        0, 0, 0, 0, 0, 1, 7,
    };
    b.setPitsForTest(setup);
    b.setTurnForTest(true);

    check("the sow plays", b.playHuman(5));
    check("human's store: 7 + 1 = 8", b.stonesAt(MANCALA_HUMAN_STORE) == 8);
    check("AI's store: 7 + swept 1 (pit 12) = 8", b.stonesAt(MANCALA_AI_STORE) == 8);
    check("result() is DRAW", b.result() == MancalaResult::DRAW);
    printf("\n");
}

static void playMoveForTestDrivesBothSidesChecks() {
    printf("--- playMoveForTest() drives whichever side is actually to move ---\n");
    MancalaBoard b;
    b.reset();
    b.setTurnForTest(false);
    // Pit 7 from a fresh reset (4 stones) sows into pits 8-11, landing in a
    // non-empty AI pit -- no capture, no extra turn, a clean "turn passes"
    // case (unlike pit 9, whose 4 stones land exactly on the AI's own store
    // at index 13 and would earn an extra turn instead).
    check("AI pit is a legal move for playMoveForTest() when it's the AI's turn", b.playMoveForTest(7));
    check("turn passed to the human afterward (no extra turn, no capture)", b.isHumanTurn());
    check("playMoveForTest() rejects a pit that isn't the current mover's own", !b.playMoveForTest(7)); // now human's turn; pit 7 is an AI pit
    printf("\n");
}

// ---------------------------------------------------------------------------
// 3) Difficulty-tier spot checks
// ---------------------------------------------------------------------------
static void easyTierChecks() {
    printf("--- EASY tier: always a legal move, over many trials ---\n");
    seedMancalaRandom(777);
    bool allLegal = true;
    for (int trial = 0; trial < 500; trial++) {
        MancalaBoard b;
        b.reset();
        // Play a few random human turns first so the AI doesn't always face
        // the identical starting position.
        for (int i = 0; i < 3 && b.isHumanTurn() && b.result() == MancalaResult::IN_PROGRESS; i++) {
            auto moves = collectLegalMoves(b);
            if (moves.empty()) break;
            b.playHuman(moves[rand() % moves.size()]);
        }
        if (b.result() != MancalaResult::IN_PROGRESS || b.isHumanTurn()) continue;
        b.setDifficulty(CpuDifficulty::EASY);
        auto legalBefore = collectLegalMoves(b);
        b.playAi();
        uint8_t chosen = b.lastSowPathPit(0);
        bool wasLegal = false;
        for (uint8_t m : legalBefore) if (m == chosen) wasLegal = true;
        if (!wasLegal) allLegal = false;
    }
    check("EASY always sows from a pit that was actually legal", allLegal);
    printf("\n");
}

static void mediumTierPrefersExtraTurnChecks() {
    printf("--- MEDIUM tier: prefers a pit landing exactly in its own store ---\n");
    MancalaBoard b;
    uint8_t setup[MANCALA_PIT_COUNT] = {
        4, 4, 4, 4, 4, 4, 0,
        3, 4, 2, 4, 4, 1, 0, // pit 12 holds exactly 1 stone -- lands exactly in the AI's own store (13);
                             // pit 9 is deliberately NOT 4 stones here (4 would ALSO land exactly on
                             // the store, at index 13, and -- checked earlier in iteration order -- MEDIUM
                             // would correctly prefer IT instead, which isn't the case this test means to cover)
    };
    b.setPitsForTest(setup);
    b.setTurnForTest(false);
    b.setDifficulty(CpuDifficulty::MEDIUM);
    b.playAi();
    check("MEDIUM chose the extra-turn pit (12) over the first non-empty pit (7)", b.lastSowPathPit(0) == 12);
    check("MEDIUM's choice actually earned the extra turn", b.lastMoveEarnedExtraTurn());
    printf("\n");
}

static void hardTierPrefersCaptureChecks() {
    printf("--- HARD tier: prefers an immediate capture over a move that gives nothing back ---\n");
    MancalaBoard b;
    // AI pit 7 sows into pit 8 only (no capture, no extra turn, hands the
    // human a normal turn back). AI pit 11 sows its single stone into pit
    // 12 -- empty beforehand, opposite pit 0 loaded with 6 -- capturing 7
    // stones into the AI's own store outright. Any reasonable search
    // (HARD's depth-6 minimax included) should prefer the immediate,
    // uncontested material swing.
    uint8_t setup[MANCALA_PIT_COUNT] = {
        6, 0, 0, 0, 0, 0, 0,
        1, 0, 0, 0, 1, 0, 0,
    };
    b.setPitsForTest(setup);
    b.setTurnForTest(false);
    b.setDifficulty(CpuDifficulty::HARD);
    b.playAi();
    check("HARD chose the capturing pit (11)", b.lastSowPathPit(0) == 11);
    check("HARD's choice actually captured", b.lastMoveCaptured());
    check("HARD's capture swept the expected 7 stones (6 + 1)", b.lastCaptureTotalSwept() == 7);
    printf("\n");
}

// ---------------------------------------------------------------------------
// 4) Bounded exhaustive tree walk from the real starting position -- every
//    legal line of play up to a few plies deep, checking the stone-
//    conservation invariant at every node reached.
// ---------------------------------------------------------------------------
static long exhaustiveNodesVisited = 0;
static long exhaustiveFailures = 0;
static const long EXHAUSTIVE_NODE_CAP = 300000;
static const int EXHAUSTIVE_DEPTH_PLIES = 6;

static void exhaustiveWalk(const MancalaBoard &board, int depthRemaining) {
    if (exhaustiveNodesVisited >= EXHAUSTIVE_NODE_CAP) return;
    exhaustiveNodesVisited++;
    if (totalStones(board) != 48) exhaustiveFailures++;

    if (depthRemaining == 0) return;
    if (board.result() != MancalaResult::IN_PROGRESS) return;

    auto moves = collectLegalMoves(board);
    if (moves.empty()) { exhaustiveFailures++; return; } // result() said IN_PROGRESS but no moves exist -- contradiction
    for (uint8_t m : moves) {
        if (exhaustiveNodesVisited >= EXHAUSTIVE_NODE_CAP) return;
        MancalaBoard next = board;
        bool ok = next.playMoveForTest(m);
        if (!ok) { exhaustiveFailures++; continue; } // a move collectLegalMoves offered was itself rejected -- bug
        exhaustiveWalk(next, depthRemaining - 1);
    }
}

static void exhaustiveTreeWalkCheck() {
    printf("=== Bounded exhaustive walk from the starting position (depth %d plies, capped at %ld nodes) ===\n",
           EXHAUSTIVE_DEPTH_PLIES, EXHAUSTIVE_NODE_CAP);
    MancalaBoard start;
    start.reset();
    exhaustiveNodesVisited = 0;
    exhaustiveFailures = 0;
    exhaustiveWalk(start, EXHAUSTIVE_DEPTH_PLIES);
    printf("Nodes visited: %ld, invariant failures: %ld\n\n", exhaustiveNodesVisited, exhaustiveFailures);
}

// ---------------------------------------------------------------------------
// 5) Large random-vs-random self-play, and random-legal human vs. the real
//    HARD-tier AI (also timing playAi() the way
//    checkers_playtest.cpp/randomHumanVsRealAiSimulation times playAi()).
// ---------------------------------------------------------------------------
static long randomSimFailures = 0;

static void randomSelfPlaySimulation(int numGames, int maxMovesPerGame) {
    printf("=== Random-vs-random self-play simulation (%d games, %d-move safety cap each) ===\n", numGames, maxMovesPerGame);
    long totalMoves = 0, humanWins = 0, aiWins = 0, draws = 0, hitCap = 0;
    long illegalRejections = 0, stoneConservationFailures = 0;

    for (int g = 0; g < numGames; g++) {
        MancalaBoard b;
        b.reset();
        int moveCount = 0;
        while (b.result() == MancalaResult::IN_PROGRESS && moveCount < maxMovesPerGame) {
            if (totalStones(b) != 48) stoneConservationFailures++;
            auto moves = collectLegalMoves(b);
            if (moves.empty()) { stoneConservationFailures++; break; } // result() said IN_PROGRESS but no moves exist
            uint8_t m = moves[rand() % moves.size()];
            if (!b.playMoveForTest(m)) { illegalRejections++; break; }
            moveCount++;
        }
        totalMoves += moveCount;

        MancalaResult r = b.result();
        if (r == MancalaResult::HUMAN_WINS) humanWins++;
        else if (r == MancalaResult::AI_WINS) aiWins++;
        else if (r == MancalaResult::DRAW) draws++;
        else hitCap++;
    }

    printf("Games: %d, total moves: %ld (avg %.1f/game)\n", numGames, totalMoves, (double)totalMoves / numGames);
    printf("  Human wins: %ld, AI wins: %ld, draws: %ld, hit the %d-move safety cap: %ld\n",
           humanWins, aiWins, draws, maxMovesPerGame, hitCap);
    printf("  Illegal-move rejections mid-game (should be 0): %ld\n", illegalRejections);
    printf("  Stone-conservation failures, i.e. total != 48 (should be 0): %ld\n", stoneConservationFailures);
    printf("  Note: unlike Checkers, Mancala as implemented here always concludes on its own (every sow strictly\n");
    printf("  reduces the stones remaining in play outside the stores, so the game cannot run forever) -- hitting\n");
    printf("  the safety cap above would itself indicate a bug, not an expected outcome.\n\n");

    randomSimFailures = illegalRejections + stoneConservationFailures + hitCap;
}

static long aiSimFailures = 0;

static void randomHumanVsRealAiSimulation(int numGames, int maxMovesPerGame) {
    printf("=== Random-legal human vs. the REAL HARD-tier minimax AI (%d games, %d-move safety cap each) ===\n",
           numGames, maxMovesPerGame);
    long totalMoves = 0, humanWins = 0, aiWins = 0, draws = 0, hitCap = 0;
    long illegalRejections = 0, stoneConservationFailures = 0;
    long aiMoveCalls = 0;
    double aiTimeTotalMs = 0.0, aiTimeMaxMs = 0.0;

    for (int g = 0; g < numGames; g++) {
        MancalaBoard b;
        b.reset();
        b.setDifficulty(CpuDifficulty::HARD);
        int moveCount = 0;
        while (b.result() == MancalaResult::IN_PROGRESS && moveCount < maxMovesPerGame) {
            if (totalStones(b) != 48) stoneConservationFailures++;
            if (b.isHumanTurn()) {
                auto moves = collectLegalMoves(b);
                if (moves.empty()) { stoneConservationFailures++; break; }
                if (!b.playHuman(moves[rand() % moves.size()])) { illegalRejections++; break; }
            } else {
                auto t0 = std::chrono::steady_clock::now();
                b.playAi();
                auto t1 = std::chrono::steady_clock::now();
                double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
                aiMoveCalls++;
                aiTimeTotalMs += ms;
                if (ms > aiTimeMaxMs) aiTimeMaxMs = ms;
            }
            moveCount++;
        }
        totalMoves += moveCount;
        MancalaResult r = b.result();
        if (r == MancalaResult::HUMAN_WINS) humanWins++;
        else if (r == MancalaResult::AI_WINS) aiWins++;
        else if (r == MancalaResult::DRAW) draws++;
        else hitCap++;
    }

    printf("Games: %d, total moves: %ld\n", numGames, totalMoves);
    printf("  Human (random) wins: %ld, AI wins: %ld, draws: %ld, hit move cap: %ld\n", humanWins, aiWins, draws, hitCap);
    printf("  Illegal-move rejections (should be 0): %ld\n", illegalRejections);
    printf("  Stone-conservation failures (should be 0): %ld\n", stoneConservationFailures);
    printf("  playAi() calls (one per SOW, not per turn -- an extra turn is a separate call): %ld\n", aiMoveCalls);
    printf("  total %.1f ms, avg %.3f ms/call, max %.3f ms -- on THIS DEV MACHINE, not the ESP32;\n",
           aiTimeTotalMs, aiMoveCalls ? aiTimeTotalMs / aiMoveCalls : 0.0, aiTimeMaxMs);
    printf("  see MancalaLogic.cpp's AI_SEARCH_DEPTH comment for how this maps to the real board's budget.\n\n");

    aiSimFailures = illegalRejections + stoneConservationFailures + hitCap;
}

// ---------------------------------------------------------------------------
// 6) Touch hit-testing: MancalaDisplay.cpp's computeMancalaLayout()/
//    hitTestMancalaPit()/hitTestMancalaPlayAgainButton()/
//    hitTestMancalaHomeButton()/hitTestMancalaDifficultyButton() are pure
//    math with no TFT_eSPI calls -- checked the same way CheckersDisplay's
//    own hit-testing is.
// ---------------------------------------------------------------------------
static int layoutChecksRun = 0, layoutChecksFailed = 0;
static void checkLayout(const char *label, bool condition) {
    layoutChecksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) layoutChecksFailed++;
}

static void touchHitTestChecks() {
    printf("=== Touch hit-testing checks (MancalaDisplay.cpp, against Config.h's real screen size) ===\n");
    MancalaLayout l = computeMancalaLayout();
    int16_t gridW = l.colStride * 6;
    int16_t gridH = l.rowStride * 2;

    printf("  layout: grid at (%d,%d) %dx%d, human store x=%d, ai store x=%d, storeW=%d\n",
           l.boardX, l.boardY, gridW, gridH, l.humanStoreX, l.aiStoreX, l.storeW);

    checkLayout("pit grid fits within the screen width", l.boardX >= 0 && l.boardX + gridW <= SCREEN_WIDTH);
    checkLayout("pit grid fits within the screen height (below the status bar)",
                l.boardY >= l.statusY + l.statusH && l.boardY + gridH <= SCREEN_HEIGHT);
    checkLayout("AI store sits left of the grid, human store right of it, both on-screen",
                l.aiStoreX >= 0 && l.aiStoreX + l.storeW <= l.boardX &&
                l.humanStoreX >= l.boardX + gridW && l.humanStoreX + l.storeW <= SCREEN_WIDTH);
    checkLayout("play-again button fits within the screen",
                l.buttonX >= 0 && l.buttonX + l.buttonW <= SCREEN_WIDTH &&
                l.buttonY >= 0 && l.buttonY + l.buttonH <= SCREEN_HEIGHT);
    checkLayout("difficulty chip fits within the screen, inside the status bar",
                l.difficultyX >= 0 && l.difficultyX + l.difficultyW <= SCREEN_WIDTH &&
                l.difficultyY >= l.statusY && l.difficultyY + l.difficultyH <= l.statusY + l.statusH);

    // Every playable pit's own center must resolve back to that same index --
    // the property that matters most for an actual fingertip tap.
    bool allCentersOk = true;
    uint8_t testPits[12] = {0, 1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12};
    for (uint8_t pit : testPits) {
        uint8_t col = (pit <= 5) ? pit : (uint8_t)(12 - pit);
        bool humanRow = pit <= 5;
        int16_t cx = l.boardX + col * l.colStride + l.colStride / 2;
        int16_t cy = humanRow ? (l.boardY + l.rowStride + l.rowStride / 2) : (l.boardY + l.rowStride / 2);
        uint8_t hit;
        if (!(hitTestMancalaPit(l, cx, cy, hit) && hit == pit)) allCentersOk = false;
    }
    checkLayout("every playable pit's own center taps that same pit index", allCentersOk);

    // Sweep every pixel inside the grid -- no gaps, no out-of-range results.
    bool allInBoundsPixelsResolve = true;
    for (int16_t x = l.boardX; x < l.boardX + gridW; x++) {
        for (int16_t y = l.boardY; y < l.boardY + gridH; y++) {
            uint8_t hit;
            if (!hitTestMancalaPit(l, x, y, hit)) { allInBoundsPixelsResolve = false; continue; }
            bool validIndex = (hit <= 5) || (hit >= 7 && hit <= 12);
            if (!validIndex) allInBoundsPixelsResolve = false;
        }
    }
    checkLayout("every in-bounds grid pixel resolves to a valid playable pit index (no gaps/overlaps)",
                allInBoundsPixelsResolve);

    uint8_t discard;
    checkLayout("1px left of the grid is not a hit", !hitTestMancalaPit(l, l.boardX - 1, l.boardY + 1, discard));
    checkLayout("1px above the grid is not a hit", !hitTestMancalaPit(l, l.boardX + 1, l.boardY - 1, discard));
    checkLayout("1px right of the grid is not a hit", !hitTestMancalaPit(l, l.boardX + gridW, l.boardY + 1, discard));
    checkLayout("1px below the grid is not a hit", !hitTestMancalaPit(l, l.boardX + 1, l.boardY + gridH, discard));

    int16_t bcx = l.buttonX + l.buttonW / 2, bcy = l.buttonY + l.buttonH / 2;
    checkLayout("play-again button's own center is a hit", hitTestMancalaPlayAgainButton(l, bcx, bcy));
    checkLayout("1px left of the button is not a hit", !hitTestMancalaPlayAgainButton(l, l.buttonX - 1, bcy));
    checkLayout("1px right of the button is not a hit", !hitTestMancalaPlayAgainButton(l, l.buttonX + l.buttonW, bcy));

    checkLayout("home button's own center is a hit", hitTestMancalaHomeButton(l, 15, l.statusY + l.statusH / 2));
    checkLayout("home button does not swallow taps in the middle of the status bar",
                !hitTestMancalaHomeButton(l, SCREEN_WIDTH / 2, l.statusY + l.statusH / 2));

    int16_t dcx = l.difficultyX + l.difficultyW / 2, dcy = l.difficultyY + l.difficultyH / 2;
    checkLayout("difficulty chip's own center is a hit", hitTestMancalaDifficultyButton(l, dcx, dcy));
    checkLayout("difficulty chip does not swallow taps in the middle of the status bar",
                !hitTestMancalaDifficultyButton(l, SCREEN_WIDTH / 2, l.statusY + l.statusH / 2));
    checkLayout("difficulty chip does not swallow taps on the home button",
                !hitTestMancalaDifficultyButton(l, 15, l.statusY + l.statusH / 2));

    printf("Layout checks run: %d, failed: %d\n\n", layoutChecksRun, layoutChecksFailed);
}

// ---------------------------------------------------------------------------
// 7) Rendering smoke test: calls every drawing function once against the
//    stubbed TFT_eSPI -- confirms nothing crashes or reads out of bounds.
//    Cannot verify a single actual pixel (see this file's top comment).
// ---------------------------------------------------------------------------
static void renderingSmokeTest() {
    printf("=== Rendering smoke test (stubbed TFT_eSPI -- confirms no crash, NOT actual pixels) ===\n");
    TFT_eSPI tft;
    MancalaLayout l = computeMancalaLayout();
    MancalaBoard b;
    b.reset();
    drawMancalaStaticChrome(tft, l);
    drawMancalaBoard(tft, l, b);
    drawMancalaStatus(tft, l, "Your turn", CpuDifficulty::MEDIUM);
    b.playHuman(1);
    animateMancalaSow(tft, l, b);
    drawMancalaBoard(tft, l, b);
    drawMancalaPlayAgainButton(tft, l);
    hideMancalaPlayAgainButton(tft, l);
    printf("  completed with no crash\n\n");
}

int main() {
    srand(12345); // fixed seed -- reproducible test runs
    seedMancalaRandom(12345);

    renderingSmokeTest();
    watchOneGame();

    printf("=== Rule-correctness checks (hand-built positions) ===\n");
    startingPositionChecks();
    captureChecks();
    noCaptureWhenOppositeIsEmptyChecks();
    extraTurnChecks();
    roundEndsWithAiWinChecks();
    roundEndsInDrawChecks();
    playMoveForTestDrivesBothSidesChecks();
    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);

    easyTierChecks();
    mediumTierPrefersExtraTurnChecks();
    hardTierPrefersCaptureChecks();

    exhaustiveTreeWalkCheck();
    randomSelfPlaySimulation(3000, 300);
    randomHumanVsRealAiSimulation(150, 200);
    touchHitTestChecks();

    bool allGood = (checksFailed == 0) &&
                   (exhaustiveFailures == 0) &&
                   (randomSimFailures == 0) &&
                   (aiSimFailures == 0) &&
                   (layoutChecksFailed == 0);
    printf("=== OVERALL: %s ===\n", allGood ? "ALL PLAYTEST CHECKS PASSED" : "FAILURES FOUND -- SEE ABOVE");
    return allGood ? 0 : 1;
}
