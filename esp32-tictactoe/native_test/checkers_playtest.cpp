// Native playtest driver for the ESP32 Checkers game's CheckersLogic.h/.cpp
// AND CheckersDisplay.cpp's pure hit-testing math -- compiles and runs the
// EXACT, unmodified files shipped in esp32-tictactoe/TicTacToeESP32/ on a
// desktop (against the project's shared no-op TFT_eSPI/Arduino stubs), the
// same spirit as native_test/playtest.cpp for Tic-Tac-Toe. This does NOT and
// CANNOT verify actual pixel rendering, touch calibration, or any real
// SPI/TFT_eSPI behavior -- only the physical board can confirm that half.
//
// Checkers' state space is far too large to search exhaustively the way
// Tic-Tac-Toe's playtest does (a full game tree from the start position),
// so this file mixes THREE kinds of verification instead, each covering
// what it's actually good at:
//   1) Hand-built positions for the specific rules that are easy to get
//      wrong (mandatory capture, forced multi-jump chains, king-only
//      backward moves/captures, promotion ending a chain mid-jump) --
//      exact, deterministic, and exhaustively checks every legal move
//      available in each hand-built position, not just one example move.
//   2) A bounded exhaustive tree walk from the real starting position --
//      every legal line of play up to a few plies deep, capped at a node
//      budget for runtime safety, checking board-integrity invariants at
//      every single node reached.
//   3) Large random-vs-random (and separately, random-vs-the-real-AI)
//      self-play simulations, many thousand hops each, checking the game
//      always reaches a legal decisive result with no crashes, no
//      out-of-bounds access, and no board corruption.

#include <cstdio>
#include <cstdlib>
#include <chrono>
#include <vector>
#include "../TicTacToeESP32/CheckersLogic.h"
#include "../TicTacToeESP32/CheckersDisplay.h"
#include "../TicTacToeESP32/Config.h"

// ---------------------------------------------------------------------------
// Small shared helpers
// ---------------------------------------------------------------------------
static char glyph(const CheckersBoard &b, uint8_t row, uint8_t col) {
    if ((row + col) % 2 == 0) return ' '; // light square -- always EMPTY, never rendered as a piece
    switch (b.at(row, col)) {
        case CheckersPiece::HUMAN_MAN:  return 'h';
        case CheckersPiece::HUMAN_KING: return 'H';
        case CheckersPiece::AI_MAN:     return 'a';
        case CheckersPiece::AI_KING:    return 'A';
        default:                        return '.';
    }
}

static void printBoard(const CheckersBoard &b) {
    for (uint8_t row = 0; row < 8; row++) {
        printf("  ");
        for (uint8_t col = 0; col < 8; col++) printf("%c ", glyph(b, row, col));
        printf("\n");
    }
}

static const char *resultName(CheckersResult r) {
    switch (r) {
        case CheckersResult::IN_PROGRESS: return "in progress";
        case CheckersResult::HUMAN_WINS:  return "HUMAN WINS";
        case CheckersResult::AI_WINS:     return "AI WINS";
    }
    return "?";
}

static int checksRun = 0, checksFailed = 0;
static void check(const char *label, bool condition) {
    checksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) checksFailed++;
}

// Collects every legal (fromRow,fromCol,toRow,toCol) for whichever side is
// currently to move, using ONLY CheckersBoard's public API -- exactly what a
// real integrator (or this test's random-move pickers) has to work with.
struct Candidate { uint8_t fromRow, fromCol, toRow, toCol; };
static std::vector<Candidate> collectLegalMoves(const CheckersBoard &b) {
    uint8_t fr[CHECKERS_MAX_MOVES], fc[CHECKERS_MAX_MOVES], tr[CHECKERS_MAX_MOVES], tc[CHECKERS_MAX_MOVES];
    uint8_t n = b.allLegalMoves(fr, fc, tr, tc);
    std::vector<Candidate> out;
    out.reserve(n);
    for (uint8_t i = 0; i < n; i++) out.push_back({fr[i], fc[i], tr[i], tc[i]});
    return out;
}

static bool lightSquaresAlwaysEmpty(const CheckersBoard &b) {
    for (uint8_t r = 0; r < 8; r++)
        for (uint8_t c = 0; c < 8; c++)
            if ((r + c) % 2 == 0 && b.at(r, c) != CheckersPiece::EMPTY) return false;
    return true;
}

// ---------------------------------------------------------------------------
// 1) A watchable playthrough: a random-legal human against the REAL minimax
//    AI, printed move by move -- the same "see it actually play" sanity
//    check playtest.cpp's watchOneGame() gives Tic-Tac-Toe.
// ---------------------------------------------------------------------------
static void watchOneGame() {
    printf("=== Playthrough: random-legal human vs. the real minimax AI ===\n\n");
    CheckersBoard board;
    board.reset();
    int hop = 1;
    const int MAX_HOPS = 400;
    while (board.result() == CheckersResult::IN_PROGRESS && hop <= MAX_HOPS) {
        if (board.isHumanTurn()) {
            auto moves = collectLegalMoves(board);
            if (moves.empty()) {
                printf("!! no legal moves found for human but result() says IN_PROGRESS -- BUG\n");
                break;
            }
            Candidate m = moves[rand() % moves.size()];
            bool ok = board.playHuman(m.fromRow, m.fromCol, m.toRow, m.toCol);
            printf("Hop %d: human (%d,%d)->(%d,%d) %s\n", hop++, m.fromRow, m.fromCol, m.toRow, m.toCol,
                   ok ? "ok" : "REJECTED -- BUG");
        } else {
            board.playAi();
            printf("Hop %d: AI moves\n", hop++);
        }
    }
    printBoard(board);
    printf("Result after %d hops: %s\n\n", hop - 1, resultName(board.result()));
}

// ---------------------------------------------------------------------------
// 2) Hand-built rule-correctness scenarios
// ---------------------------------------------------------------------------
static void startingPositionChecks() {
    printf("--- Starting position ---\n");
    CheckersBoard b;
    b.reset();
    check("fresh board: result is IN_PROGRESS", b.result() == CheckersResult::IN_PROGRESS);
    check("fresh board: it's human's turn", b.isHumanTurn());
    check("human starts with 12 men", b.pieceCount(true) == 12);
    check("AI starts with 12 men", b.pieceCount(false) == 12);
    check("light squares hold nothing at the start", lightSquaresAlwaysEmpty(b));

    auto moves = collectLegalMoves(b);
    // Classic checkers trivia, used here as a strong exact-count assertion:
    // the first player has exactly 7 legal opening moves from the standard
    // start (only the 4 men on the row closest to the empty middle can move
    // at all, and the 2 nearest either board edge have only 1 diagonal open
    // instead of 2).
    check("exactly 7 legal opening moves from the standard start", moves.size() == 7);

    bool allSourcesAreHumanMen = true, allDestsEmptyBefore = true;
    for (auto &m : moves) {
        if (b.at(m.fromRow, m.fromCol) != CheckersPiece::HUMAN_MAN) allSourcesAreHumanMen = false;
        if (b.at(m.toRow, m.toCol) != CheckersPiece::EMPTY) allDestsEmptyBefore = false;
    }
    check("every opening move's source is a HUMAN_MAN", allSourcesAreHumanMen);
    check("every opening move's destination starts empty", allDestsEmptyBefore);
    printf("\n");
}

static void forcedMultiJumpChecks() {
    printf("--- Hand-built scenario: forced human double-jump ---\n");
    CheckersBoard b;
    b.resetEmptyForTest();
    b.setSquareForTest(5, 2, CheckersPiece::HUMAN_MAN);
    b.setSquareForTest(4, 3, CheckersPiece::AI_MAN);
    b.setSquareForTest(2, 5, CheckersPiece::AI_MAN);
    b.setSquareForTest(7, 0, CheckersPiece::HUMAN_MAN); // filler: no capture of its own, must be blocked by mandatory capture
    b.setSquareForTest(0, 1, CheckersPiece::AI_MAN);     // filler: keeps the AI from a premature "0 pieces" win
    b.setTurnForTest(true);

    check("mandatory capture blocks the filler piece's simple move", !b.hasLegalMoveFrom(7, 0));
    check("the jumping man is a legal source", b.hasLegalMoveFrom(5, 2));

    uint8_t destRows[CHECKERS_MAX_MOVES], destCols[CHECKERS_MAX_MOVES];
    uint8_t n = b.legalDestinationsFrom(5, 2, destRows, destCols);
    check("exactly one first-hop destination is offered (the immediate landing square, not the whole chain)",
          n == 1 && destRows[0] == 3 && destCols[0] == 4);

    check("first hop plays", b.playHuman(5, 2, 3, 4));
    check("captured piece is removed", b.at(4, 3) == CheckersPiece::EMPTY);
    check("source square is vacated", b.at(5, 2) == CheckersPiece::EMPTY);
    check("piece landed at the intermediate square", b.at(3, 4) == CheckersPiece::HUMAN_MAN);

    uint8_t fr, fc;
    bool forced = b.inForcedContinuation(fr, fc);
    check("board reports a forced continuation after the first hop", forced && fr == 3 && fc == 4);
    check("turn has NOT passed to the AI mid-chain", b.isHumanTurn());
    check("the filler piece still cannot move (forced continuation restricts to the jumping piece)",
          !b.hasLegalMoveFrom(7, 0));

    n = b.legalDestinationsFrom(3, 4, destRows, destCols);
    check("exactly one second-hop destination is offered", n == 1 && destRows[0] == 1 && destCols[0] == 6);

    check("second hop plays", b.playHuman(3, 4, 1, 6));
    check("second captured piece is removed", b.at(2, 5) == CheckersPiece::EMPTY);
    check("piece landed at the chain's final square, still a man (row 1 is not the promotion row)",
          b.at(1, 6) == CheckersPiece::HUMAN_MAN);

    bool stillForced = b.inForcedContinuation(fr, fc);
    check("no further forced continuation once the chain runs out", !stillForced);
    check("turn passes to the AI once the chain ends", !b.isHumanTurn());
    check("AI lost exactly the 2 jumped men (3 -> 1)", b.pieceCount(false) == 1);
    printf("\n");
}

static void kingMoveAndCaptureChecks() {
    printf("--- Hand-built scenario: king move + backward capture ---\n");

    // Part 1: an isolated king can step to all 4 diagonal neighbors; a man
    // in the same isolated spot can only step to its 2 FORWARD ones.
    {
        CheckersBoard b;
        b.resetEmptyForTest();
        b.setSquareForTest(4, 4, CheckersPiece::HUMAN_KING);
        b.setTurnForTest(true);
        uint8_t destRows[CHECKERS_MAX_MOVES], destCols[CHECKERS_MAX_MOVES];
        uint8_t n = b.legalDestinationsFrom(4, 4, destRows, destCols);
        check("an isolated king has all 4 diagonal simple moves available", n == 4);
    }
    {
        CheckersBoard b;
        b.resetEmptyForTest();
        b.setSquareForTest(4, 4, CheckersPiece::HUMAN_MAN);
        b.setTurnForTest(true);
        uint8_t destRows[CHECKERS_MAX_MOVES], destCols[CHECKERS_MAX_MOVES];
        uint8_t n = b.legalDestinationsFrom(4, 4, destRows, destCols);
        check("an isolated MAN only has its 2 forward diagonal simple moves", n == 2);
    }

    // Part 2: a king can capture BACKWARD (toward increasing row, for a
    // human piece) -- a direction no man is ever allowed to jump in.
    CheckersBoard b;
    b.resetEmptyForTest();
    b.setSquareForTest(3, 3, CheckersPiece::HUMAN_KING);
    b.setSquareForTest(4, 4, CheckersPiece::AI_MAN);
    b.setSquareForTest(7, 0, CheckersPiece::HUMAN_MAN); // filler, no capture available
    b.setSquareForTest(0, 1, CheckersPiece::AI_MAN);     // filler
    b.setTurnForTest(true);

    check("mandatory capture blocks the filler piece", !b.hasLegalMoveFrom(7, 0));
    uint8_t destRows[CHECKERS_MAX_MOVES], destCols[CHECKERS_MAX_MOVES];
    uint8_t n = b.legalDestinationsFrom(3, 3, destRows, destCols);
    check("the king has exactly one legal move: the backward capture", n == 1 && destRows[0] == 5 && destCols[0] == 5);
    check("a same-direction NON-capturing step would be illegal here (mandatory capture)", !b.isLegalMove(3, 3, 2, 2));

    check("the backward capture plays", b.playHuman(3, 3, 5, 5));
    check("the jumped man is removed", b.at(4, 4) == CheckersPiece::EMPTY);
    check("the king landed at the far square (still a king)", b.at(5, 5) == CheckersPiece::HUMAN_KING);
    printf("\n");
}

static void promotionEndsChainChecks() {
    printf("--- Hand-built scenario: promotion stops a chain even if a further jump exists ---\n");
    CheckersBoard b;
    b.resetEmptyForTest();
    b.setSquareForTest(2, 5, CheckersPiece::HUMAN_MAN);
    b.setSquareForTest(1, 4, CheckersPiece::AI_MAN);    // captured by the promoting jump
    b.setSquareForTest(1, 2, CheckersPiece::AI_MAN);    // NOT captured -- sits where a further king-jump would be geometrically available
    b.setSquareForTest(7, 0, CheckersPiece::HUMAN_MAN); // filler
    b.setSquareForTest(0, 7, CheckersPiece::AI_MAN);    // filler
    b.setTurnForTest(true);

    auto moves = collectLegalMoves(b);
    check("exactly one legal move available (the single mandatory capture)", moves.size() == 1);

    check("the promoting jump plays", b.playHuman(2, 5, 0, 3));
    check("the jumped man is removed", b.at(1, 4) == CheckersPiece::EMPTY);
    check("the man promoted to a king on reaching row 0", b.at(0, 3) == CheckersPiece::HUMAN_KING);
    check("the untouched AI man is still right where a further jump would need it", b.at(1, 2) == CheckersPiece::AI_MAN);

    uint8_t fr, fc;
    check("no forced continuation -- promoting mid-chain ends the turn even though a further jump exists",
          !b.inForcedContinuation(fr, fc));
    check("turn has passed to the AI", !b.isHumanTurn());
    check("AI lost exactly 1 man (3 -> 2)", b.pieceCount(false) == 2);
    printf("\n");
}

// ---------------------------------------------------------------------------
// 3) Bounded exhaustive tree walk from the real starting position -- every
//    legal line of play up to a few plies deep (capped at a node budget for
//    runtime safety), checking board integrity at every node reached.
// ---------------------------------------------------------------------------
static long exhaustiveNodesVisited = 0;
static long exhaustiveFailures = 0;
static const long EXHAUSTIVE_NODE_CAP = 400000;
static const int EXHAUSTIVE_DEPTH_PLIES = 5;

static void exhaustiveWalk(const CheckersBoard &board, int depthRemaining) {
    if (exhaustiveNodesVisited >= EXHAUSTIVE_NODE_CAP) return;
    exhaustiveNodesVisited++;
    if (!lightSquaresAlwaysEmpty(board)) exhaustiveFailures++;

    if (depthRemaining == 0) return;
    if (board.result() != CheckersResult::IN_PROGRESS) return;

    auto moves = collectLegalMoves(board);
    if (moves.empty()) { exhaustiveFailures++; return; } // result() said IN_PROGRESS but no moves exist -- contradiction
    for (auto &m : moves) {
        if (exhaustiveNodesVisited >= EXHAUSTIVE_NODE_CAP) return;
        CheckersBoard next = board;
        bool ok = next.playMoveForTest(m.fromRow, m.fromCol, m.toRow, m.toCol);
        if (!ok) { exhaustiveFailures++; continue; } // a move collectLegalMoves offered was itself rejected -- bug
        exhaustiveWalk(next, depthRemaining - 1);
    }
}

static void exhaustiveTreeWalkCheck() {
    printf("=== Bounded exhaustive walk from the starting position (depth %d plies, capped at %ld nodes) ===\n",
           EXHAUSTIVE_DEPTH_PLIES, EXHAUSTIVE_NODE_CAP);
    CheckersBoard start;
    start.reset();
    exhaustiveNodesVisited = 0;
    exhaustiveFailures = 0;
    exhaustiveWalk(start, EXHAUSTIVE_DEPTH_PLIES);
    printf("Nodes visited: %ld, invariant failures: %ld\n\n", exhaustiveNodesVisited, exhaustiveFailures);
}

// ---------------------------------------------------------------------------
// 4) Large random-vs-random self-play: many thousand hops checking the game
//    always terminates in a legal decisive state, with no crashes, no
//    out-of-bounds access, and no board corruption (light squares never
//    gain a piece; the total piece count never increases).
// ---------------------------------------------------------------------------
static long randomSimFailures = 0;

static void randomSelfPlaySimulation(int numGames, int maxHopsPerGame) {
    printf("=== Random-vs-random self-play simulation (%d games, %d-hop safety cap each) ===\n", numGames, maxHopsPerGame);
    long totalHops = 0, humanWins = 0, aiWins = 0, hitCap = 0;
    long illegalRejections = 0, invariantFailures = 0, pieceCountIncreases = 0, turnConsistencyFailures = 0;

    for (int g = 0; g < numGames; g++) {
        CheckersBoard b;
        b.reset();
        int hop = 0;
        int prevTotalPieces = (int)b.pieceCount(true) + (int)b.pieceCount(false);
        while (b.result() == CheckersResult::IN_PROGRESS && hop < maxHopsPerGame) {
            if (!lightSquaresAlwaysEmpty(b)) invariantFailures++;
            auto moves = collectLegalMoves(b);
            if (moves.empty()) { invariantFailures++; break; } // result() said IN_PROGRESS but no moves exist
            Candidate m = moves[rand() % moves.size()];
            if (!b.playMoveForTest(m.fromRow, m.fromCol, m.toRow, m.toCol)) { illegalRejections++; break; }
            int newTotal = (int)b.pieceCount(true) + (int)b.pieceCount(false);
            if (newTotal > prevTotalPieces) pieceCountIncreases++; // pieces can only ever be removed, never added
            prevTotalPieces = newTotal;
            hop++;
        }
        totalHops += hop;

        CheckersResult r = b.result();
        if (r == CheckersResult::HUMAN_WINS) {
            humanWins++;
            if (b.isHumanTurn()) turnConsistencyFailures++; // AI should have been the stuck side
        } else if (r == CheckersResult::AI_WINS) {
            aiWins++;
            if (!b.isHumanTurn()) turnConsistencyFailures++; // human should have been the stuck side
        } else {
            hitCap++;
        }
    }

    printf("Games: %d, total hops: %ld (avg %.1f/game)\n", numGames, totalHops, (double)totalHops / numGames);
    printf("  Human wins: %ld, AI wins: %ld, hit the %d-hop safety cap without concluding: %ld\n",
           humanWins, aiWins, maxHopsPerGame, hitCap);
    printf("  Illegal-move rejections mid-game (should be 0): %ld\n", illegalRejections);
    printf("  Board-invariant failures (should be 0): %ld\n", invariantFailures);
    printf("  Total-piece-count increases (should be 0): %ld\n", pieceCountIncreases);
    printf("  Winner/whose-turn-it-was inconsistencies (should be 0): %ld\n", turnConsistencyFailures);
    printf("  Note: hitting the safety cap is an EXPECTED, non-failure outcome for pure random play --\n");
    printf("  this game's rules (matching the task's specified scope) implement no draw-by-repetition or\n");
    printf("  no-progress rule, so two randomly-shuffling lone kings can legitimately run very long; the\n");
    printf("  cap above is a TEST-HARNESS safety valve only, not a rule the shipped game enforces.\n\n");

    randomSimFailures = illegalRejections + invariantFailures + pieceCountIncreases + turnConsistencyFailures;
}

// ---------------------------------------------------------------------------
// 5) Random-legal human vs. the REAL minimax AI -- exercises playAi() (full
//    turns, multi-jump chains included) at scale, and measures its
//    wall-clock cost on this dev machine as input to the AI_SEARCH_DEPTH
//    choice documented in CheckersLogic.cpp.
// ---------------------------------------------------------------------------
static long aiSimFailures = 0;

static void randomHumanVsRealAiSimulation(int numGames, int maxHopsPerGame) {
    printf("=== Random-legal human vs. the REAL minimax AI (%d games, %d-hop safety cap each) ===\n",
           numGames, maxHopsPerGame);
    long totalHops = 0, humanWins = 0, aiWins = 0, hitCap = 0;
    long illegalRejections = 0, invariantFailures = 0;
    long aiMoveCalls = 0;
    double aiTimeTotalMs = 0.0, aiTimeMaxMs = 0.0;

    for (int g = 0; g < numGames; g++) {
        CheckersBoard b;
        b.reset();
        int hop = 0;
        while (b.result() == CheckersResult::IN_PROGRESS && hop < maxHopsPerGame) {
            if (!lightSquaresAlwaysEmpty(b)) invariantFailures++;
            if (b.isHumanTurn()) {
                auto moves = collectLegalMoves(b);
                if (moves.empty()) { invariantFailures++; break; }
                Candidate m = moves[rand() % moves.size()];
                if (!b.playHuman(m.fromRow, m.fromCol, m.toRow, m.toCol)) { illegalRejections++; break; }
            } else {
                auto t0 = std::chrono::steady_clock::now();
                b.playAi();
                auto t1 = std::chrono::steady_clock::now();
                double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
                aiMoveCalls++;
                aiTimeTotalMs += ms;
                if (ms > aiTimeMaxMs) aiTimeMaxMs = ms;
            }
            hop++;
        }
        totalHops += hop;
        CheckersResult r = b.result();
        if (r == CheckersResult::HUMAN_WINS) humanWins++;
        else if (r == CheckersResult::AI_WINS) aiWins++;
        else hitCap++;
    }

    printf("Games: %d, total hops: %ld\n", numGames, totalHops);
    printf("  Human (random) wins: %ld, AI wins: %ld, hit hop cap: %ld\n", humanWins, aiWins, hitCap);
    printf("  Illegal-move rejections (should be 0): %ld\n", illegalRejections);
    printf("  Board-invariant failures (should be 0): %ld\n", invariantFailures);
    printf("  playAi() calls (one per FULL AI turn, multi-jumps included): %ld\n", aiMoveCalls);
    printf("  total %.1f ms, avg %.3f ms/call, max %.3f ms -- on THIS DEV MACHINE, not the ESP32;\n",
           aiTimeTotalMs, aiMoveCalls ? aiTimeTotalMs / aiMoveCalls : 0.0, aiTimeMaxMs);
    printf("  see CheckersLogic.cpp's AI_SEARCH_DEPTH comment for how this maps to the real board's budget.\n\n");

    aiSimFailures = illegalRejections + invariantFailures;
}

// ---------------------------------------------------------------------------
// 6) Touch hit-testing: CheckersDisplay.cpp's computeCheckersLayout()/
//    hitTestSquare()/hitTestCheckersPlayAgainButton()/
//    hitTestCheckersHomeButton() are pure math with no TFT_eSPI calls --
//    checked the same way Display.cpp's are for Tic-Tac-Toe.
// ---------------------------------------------------------------------------
static int layoutChecksRun = 0, layoutChecksFailed = 0;
static void checkLayout(const char *label, bool condition) {
    layoutChecksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) layoutChecksFailed++;
}

static void touchHitTestChecks() {
    printf("=== Touch hit-testing checks (CheckersDisplay.cpp, against Config.h's real screen size) ===\n");
    CheckersLayout l = computeCheckersLayout();
    int16_t boardSize = l.cellSize * 8;

    printf("  layout: board at (%d,%d), cell=%dpx, button at (%d,%d) %dx%d\n",
           l.boardX, l.boardY, l.cellSize, l.buttonX, l.buttonY, l.buttonW, l.buttonH);

    checkLayout("board fits within the screen width", l.boardX >= 0 && l.boardX + boardSize <= SCREEN_WIDTH);
    checkLayout("board fits within the screen height (below the status bar)",
                l.boardY >= l.statusY + l.statusH && l.boardY + boardSize <= SCREEN_HEIGHT);
    checkLayout("play-again button fits within the screen",
                l.buttonX >= 0 && l.buttonX + l.buttonW <= SCREEN_WIDTH &&
                l.buttonY >= 0 && l.buttonY + l.buttonH <= SCREEN_HEIGHT);

    // Every square's own center must resolve back to that same (row,col) --
    // the property that matters most for an actual fingertip tap.
    bool allCentersOk = true;
    for (uint8_t row = 0; row < 8; row++) {
        for (uint8_t col = 0; col < 8; col++) {
            int16_t cx = l.boardX + col * l.cellSize + l.cellSize / 2;
            int16_t cy = l.boardY + row * l.cellSize + l.cellSize / 2;
            uint8_t hr, hc;
            if (!(hitTestSquare(l, cx, cy, hr, hc) && hr == row && hc == col)) allCentersOk = false;
        }
    }
    checkLayout("every square's own center taps that same (row,col)", allCentersOk);

    // Sweep every pixel inside the board -- no gaps, no out-of-range results.
    bool allInBoundsPixelsResolve = true;
    for (int16_t x = l.boardX; x < l.boardX + boardSize; x++) {
        for (int16_t y = l.boardY; y < l.boardY + boardSize; y++) {
            uint8_t hr, hc;
            if (!hitTestSquare(l, x, y, hr, hc) || hr > 7 || hc > 7) allInBoundsPixelsResolve = false;
        }
    }
    checkLayout("every in-bounds board pixel resolves to a valid 0-7 row/col (no gaps/overlaps)",
                allInBoundsPixelsResolve);

    uint8_t discard;
    checkLayout("1px left of the board is not a hit", !hitTestSquare(l, l.boardX - 1, l.boardY + 1, discard, discard));
    checkLayout("1px above the board is not a hit", !hitTestSquare(l, l.boardX + 1, l.boardY - 1, discard, discard));
    checkLayout("1px right of the board is not a hit",
                !hitTestSquare(l, l.boardX + boardSize, l.boardY + 1, discard, discard));
    checkLayout("1px below the board is not a hit",
                !hitTestSquare(l, l.boardX + 1, l.boardY + boardSize, discard, discard));

    int16_t bcx = l.buttonX + l.buttonW / 2, bcy = l.buttonY + l.buttonH / 2;
    checkLayout("play-again button's own center is a hit", hitTestCheckersPlayAgainButton(l, bcx, bcy));
    checkLayout("1px left of the button is not a hit", !hitTestCheckersPlayAgainButton(l, l.buttonX - 1, bcy));
    checkLayout("1px above the button is not a hit", !hitTestCheckersPlayAgainButton(l, bcx, l.buttonY - 1));
    checkLayout("1px right of the button is not a hit",
                !hitTestCheckersPlayAgainButton(l, l.buttonX + l.buttonW, bcy));
    checkLayout("1px below the button is not a hit", !hitTestCheckersPlayAgainButton(l, bcx, l.buttonY + l.buttonH));

    checkLayout("home button's own center is a hit", hitTestCheckersHomeButton(l, 15, l.statusY + l.statusH / 2));
    checkLayout("home button does not swallow taps in the middle of the status bar",
                !hitTestCheckersHomeButton(l, SCREEN_WIDTH / 2, l.statusY + l.statusH / 2));
    checkLayout("home button does not swallow taps inside the board",
                !hitTestCheckersHomeButton(l, l.boardX + 10, l.boardY + 10));

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
    CheckersLayout l = computeCheckersLayout();
    CheckersBoard b;
    b.reset();
    drawCheckersStaticChrome(tft, l);
    drawCheckersBoard(tft, l, b);
    drawCheckersStatus(tft, l, "Your turn");
    drawCheckersSquareHighlight(tft, l, 5, 2, b.at(5, 2), true);
    drawCheckersSquareHighlight(tft, l, 5, 2, b.at(5, 2), false);
    drawCheckersPlayAgainButton(tft, l);
    hideCheckersPlayAgainButton(tft, l);
    printf("  completed with no crash\n\n");
}

int main() {
    srand(12345); // fixed seed -- reproducible test runs

    renderingSmokeTest();
    watchOneGame();

    printf("=== Rule-correctness checks (hand-built positions) ===\n");
    startingPositionChecks();
    forcedMultiJumpChecks();
    kingMoveAndCaptureChecks();
    promotionEndsChainChecks();
    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);

    exhaustiveTreeWalkCheck();
    randomSelfPlaySimulation(5000, 1000);
    randomHumanVsRealAiSimulation(150, 500);
    touchHitTestChecks();

    bool allGood = (checksFailed == 0) &&
                   (exhaustiveFailures == 0) &&
                   (randomSimFailures == 0) &&
                   (aiSimFailures == 0) &&
                   (layoutChecksFailed == 0);
    printf("=== OVERALL: %s ===\n", allGood ? "ALL PLAYTEST CHECKS PASSED" : "FAILURES FOUND -- SEE ABOVE");
    return allGood ? 0 : 1;
}
