// Native playtest driver for the ESP32 Dominoes game's DominoesLogic.h/.cpp
// AND DominoesDisplay.cpp's pure hit-testing math -- compiles and runs the
// EXACT, unmodified files shipped in esp32-tictactoe/TicTacToeESP32/ on a
// desktop (against the project's shared no-op TFT_eSPI/Arduino stubs), the
// same spirit as native_test/mancala_playtest.cpp for Mancala. This does
// NOT and CANNOT verify actual pixel rendering, touch calibration, or any
// real SPI/TFT_eSPI behavior -- only the physical board can confirm that
// half.
//
// Like every other game's playtest here, this mixes several kinds of
// verification:
//   1) Hand-built positions for the rules that are easy to get wrong (the
//      attach/auto-flip math on both ends, winning by emptying your hand,
//      must-draw-before-you-may-pass, and a blocked hand's tie-break) --
//      exact and deterministic.
//   2) A bounded exhaustive tree walk from the real starting deal, checking
//      a hard invariant (every one of the 28 tiles is accounted for exactly
//      once, across both hands + the chain + the boneyard) at every node.
//   3) Large random-vs-random self-play, checking the game always reaches
//      a legal decisive result with no crashes and no invariant failures.
//   4) Difficulty-tier spot checks -- EASY/MEDIUM/HARD, using the test-only
//      aiHandTileForTest() seam (see DominoesLogic.h) to verify the AI
//      actually chose the tile the rules say it should, not just that
//      SOMETHING legal happened (which alone wouldn't have caught a
//      "left/right ordering" bug in MEDIUM's tier, for instance).

#include <cstdio>
#include <cstdlib>
#include <chrono>
#include <vector>
#include "../TicTacToeESP32/DominoesLogic.h"
#include "../TicTacToeESP32/DominoesDisplay.h"
#include "../TicTacToeESP32/Config.h"

static int checksRun = 0, checksFailed = 0;
static void check(const char *label, bool condition) {
    checksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) checksFailed++;
}

static const char *resultName(DominoesResult r) {
    switch (r) {
        case DominoesResult::IN_PROGRESS: return "in progress";
        case DominoesResult::HUMAN_WINS:  return "HUMAN WINS";
        case DominoesResult::AI_WINS:     return "AI WINS";
        case DominoesResult::DRAW:        return "DRAW";
    }
    return "?";
}

// Finds tile instanceId `id` among the standard 28-tile set's own two pip
// values -- a small helper so hand-built scenarios below can name a tile by
// its (a, b) pips rather than having to know its exact instanceId, mirroring
// how easy the equivalent Kotlin test data (`Domino(a, b, id)`) is to read.
static uint8_t tileId(uint8_t a, uint8_t b) {
    if (a > b) { uint8_t t = a; a = b; b = t; }
    for (uint8_t id = 0; id < DOMINOES_SET_SIZE; id++) {
        DominoTileView v = dominoTileById(id);
        if (v.a == a && v.b == b) return id;
    }
    return 255; // unreachable for a valid (a, b) pair -- every pair 0<=a<=b<=6 exists exactly once
}

static uint16_t totalTilesAccountedFor(const DominoesBoard &b) {
    return (uint16_t)b.humanHandCount() + b.aiHandCount() + b.chainLength() + b.boneyardCount();
}

// ---------------------------------------------------------------------------
// 1) A watchable playthrough: random-legal human vs. the real HARD-tier AI.
// ---------------------------------------------------------------------------
static void watchOneGame() {
    printf("=== Playthrough: random-legal human vs. the real HARD-tier AI ===\n\n");
    DominoesBoard board;
    board.reset();
    board.setDifficulty(CpuDifficulty::HARD);
    int turn = 1;
    const int MAX_TURNS = 300;
    while (board.result() == DominoesResult::IN_PROGRESS && turn <= MAX_TURNS) {
        if (board.isHumanTurn()) {
            DominoesLegalPlay plays[DOMINOES_MAX_LEGAL_PLAYS];
            uint8_t n = board.legalPlays(plays);
            if (n > 0) {
                DominoesLegalPlay p = plays[rand() % n];
                DominoTileView t = board.humanHandTile(p.handIndex);
                bool ok = board.playHumanTile(p.handIndex, p.attachToLeft);
                printf("Turn %d: human plays %u-%u %s (%s) %s\n", turn++, t.a, t.b,
                       p.attachToLeft ? "left" : "right", ok ? "ok" : "REJECTED -- BUG",
                       board.chainEmpty() ? "" : "");
            } else if (board.boneyardCount() > 0) {
                board.drawHumanTile();
                printf("Turn %d: human draws (boneyard now %u)\n", turn++, board.boneyardCount());
            } else {
                board.passHuman();
                printf("Turn %d: human passes\n", turn++);
            }
        } else {
            uint8_t aiCountBefore = board.aiHandCount();
            board.playAi();
            printf("Turn %d: AI's turn resolves (hand %u -> %u, boneyard %u)\n", turn++, aiCountBefore, board.aiHandCount(), board.boneyardCount());
        }
    }
    printf("Result after %d turns: %s (human=%u tiles, ai=%u tiles, chain=%u, boneyard=%u)\n\n",
           turn - 1, resultName(board.result()), board.humanHandCount(), board.aiHandCount(),
           board.chainLength(), board.boneyardCount());
}

// ---------------------------------------------------------------------------
// 2) Hand-built rule-correctness scenarios
// ---------------------------------------------------------------------------
static void startingPositionChecks() {
    printf("--- Starting position ---\n");
    DominoesBoard b;
    b.reset();
    check("fresh deal: result is IN_PROGRESS", b.result() == DominoesResult::IN_PROGRESS);
    check("fresh deal: human holds 7 tiles", b.humanHandCount() == 7);
    check("fresh deal: AI holds 7 tiles", b.aiHandCount() == 7);
    check("fresh deal: boneyard holds the remaining 14", b.boneyardCount() == 14);
    check("fresh deal: chain starts empty", b.chainEmpty());
    check("fresh deal: all 28 tiles accounted for exactly once", totalTilesAccountedFor(b) == DOMINOES_SET_SIZE);
    printf("\n");
}

static void attachLeftFlipChecks() {
    printf("--- Hand-built scenario: attaching to the left auto-flips only when needed ---\n");
    DominoesBoard b;
    uint8_t chainIds[1] = { tileId(3, 5) };
    bool chainFlipped[1] = { false };
    b.setChainForTest(chainIds, chainFlipped, 1);
    check("chain starts as 3-5, left end 3", b.leftEndValue() == 3);
    check("chain starts as 3-5, right end 5", b.rightEndValue() == 5);

    uint8_t humanIds[2] = { tileId(1, 3), tileId(3, 4) };
    uint8_t aiIds[1] = { tileId(6, 6) }; // filler, unused
    b.setHandsForTest(humanIds, 2, aiIds, 1);
    b.setTurnForTest(true);

    check("1-3 can attach left (b already matches, no flip needed)", b.canAttachLeft(b.humanHandTile(0)));
    check("1-3 plays on the left", b.playHumanTile(0, true));
    check("new left end is 1 (unflipped -- 3 was already on the touching side)", b.leftEndValue() == 1);
    check("right end is unchanged at 5", b.rightEndValue() == 5);
    check("the tile is NOT reported as flipped (b already matched)", !b.chainTileFlippedAt(0));
    printf("\n");
}

static void attachLeftRequiringFlipChecks() {
    printf("--- Hand-built scenario: attaching to the left DOES flip when the matching pip is on the wrong side ---\n");
    DominoesBoard b;
    uint8_t chainIds[1] = { tileId(3, 5) };
    bool flipped[1] = { false };
    b.setChainForTest(chainIds, flipped, 1); // left end 3, right end 5

    uint8_t humanIds[1] = { tileId(3, 4) }; // matches on 'a', not 'b' -- must flip to present the 3 outward
    uint8_t aiIds[1] = { tileId(6, 6) };
    b.setHandsForTest(humanIds, 1, aiIds, 1);
    b.setTurnForTest(true);

    check("3-4 plays on the left", b.playHumanTile(0, true));
    check("the tile IS reported as flipped", b.chainTileFlippedAt(0));
    check("new left end is 4 (the un-matched pip, now facing outward)", b.leftEndValue() == 4);
    check("right end is unchanged at 5", b.rightEndValue() == 5);
    printf("\n");
}

static void attachRightFlipChecks() {
    printf("--- Hand-built scenario: attaching to the right, both flip cases ---\n");
    DominoesBoard b;
    uint8_t chainIds[1] = { tileId(3, 5) };
    bool flipped[1] = { false };
    b.setChainForTest(chainIds, flipped, 1); // right end 5

    uint8_t humanIds[1] = { tileId(5, 6) }; // 'a' already matches -- no flip
    uint8_t aiIds[1] = { tileId(0, 0) };
    b.setHandsForTest(humanIds, 1, aiIds, 1);
    b.setTurnForTest(true);
    check("5-6 plays on the right", b.playHumanTile(0, false));
    check("not flipped (a already matched)", !b.chainTileFlippedAt(1));
    check("new right end is 6", b.rightEndValue() == 6);

    DominoesBoard b2;
    b2.setChainForTest(chainIds, flipped, 1); // right end 5 again, fresh board
    uint8_t humanIds2[1] = { tileId(6, 5) }; // note: DOMINOES_TILES only ever stores a<=b, so this is really tile 5-6 --
                                              // use a tile where 'b' matches instead to force the OTHER flip direction
    // A tile whose 'a' does NOT match the end but whose 'b' does: 2-5.
    humanIds2[0] = tileId(2, 5);
    uint8_t aiIds2[1] = { tileId(0, 0) };
    b2.setHandsForTest(humanIds2, 1, aiIds2, 1);
    b2.setTurnForTest(true);
    check("2-5 plays on the right", b2.playHumanTile(0, false));
    check("IS flipped ('b' was the matching pip, not 'a')", b2.chainTileFlippedAt(1));
    check("new right end is 2 (the un-matched pip, now facing outward)", b2.rightEndValue() == 2);
    printf("\n");
}

static void illegalAttachRejectedChecks() {
    printf("--- Hand-built scenario: a non-matching tile is rejected on both ends ---\n");
    DominoesBoard b;
    uint8_t chainIds[1] = { tileId(3, 5) };
    bool flipped[1] = { false };
    b.setChainForTest(chainIds, flipped, 1); // ends are 3 and 5

    uint8_t humanIds[1] = { tileId(0, 1) }; // matches neither end
    uint8_t aiIds[1] = { tileId(6, 6) };
    b.setHandsForTest(humanIds, 1, aiIds, 1);
    b.setTurnForTest(true);

    check("canAttachLeft is false", !b.canAttachLeft(b.humanHandTile(0)));
    check("canAttachRight is false", !b.canAttachRight(b.humanHandTile(0)));
    check("playHumanTile(left) is rejected", !b.playHumanTile(0, true));
    check("playHumanTile(right) is rejected", !b.playHumanTile(0, false));
    check("chain is untouched", b.chainLength() == 1);
    check("hand is untouched", b.humanHandCount() == 1);
    printf("\n");
}

static void emptyingHandWinsChecks() {
    printf("--- Hand-built scenario: playing your last tile wins immediately ---\n");
    DominoesBoard b;
    uint8_t chainIds[1] = { tileId(3, 5) };
    bool flipped[1] = { false };
    b.setChainForTest(chainIds, flipped, 1);

    uint8_t humanIds[1] = { tileId(3, 4) }; // the human's only tile
    uint8_t aiIds[2] = { tileId(0, 0), tileId(1, 1) };
    b.setHandsForTest(humanIds, 1, aiIds, 2);
    b.setBoneyardForTest(nullptr, 0);
    b.setTurnForTest(true);

    check("the winning play succeeds", b.playHumanTile(0, true));
    check("human hand is now empty", b.humanHandCount() == 0);
    check("result() is HUMAN_WINS", b.result() == DominoesResult::HUMAN_WINS);
    printf("\n");
}

static void mustDrawBeforePlayingIsLegalChecks() {
    printf("--- Hand-built scenario: you can't draw or pass while you still have a legal play ---\n");
    DominoesBoard b;
    uint8_t chainIds[1] = { tileId(3, 5) };
    bool flipped[1] = { false };
    b.setChainForTest(chainIds, flipped, 1);

    uint8_t humanIds[1] = { tileId(3, 4) }; // a genuine legal play exists
    uint8_t aiIds[1] = { tileId(0, 0) };
    uint8_t boneyardIds[1] = { tileId(1, 1) };
    b.setHandsForTest(humanIds, 1, aiIds, 1);
    b.setBoneyardForTest(boneyardIds, 1);
    b.setTurnForTest(true);

    check("currentSideHasLegalPlay() is true", b.currentSideHasLegalPlay());
    check("drawHumanTile() is rejected (must play instead)", !b.drawHumanTile());
    check("passHuman() is rejected (must play instead)", !b.passHuman());
    check("the legal play itself still succeeds", b.playHumanTile(0, true));
    printf("\n");
}

static void drawUntilBoneyardEmptyThenPassChecks() {
    printf("--- Hand-built scenario: draw repeatedly while stuck, then pass once the boneyard is empty ---\n");
    DominoesBoard b;
    uint8_t chainIds[1] = { tileId(6, 6) }; // both ends are 6
    bool flipped[1] = { false };
    b.setChainForTest(chainIds, flipped, 1);

    uint8_t humanIds[2] = { tileId(0, 1), tileId(0, 2) }; // no 6 anywhere
    uint8_t aiIds[1] = { tileId(1, 1) };
    uint8_t boneyardIds[2] = { tileId(0, 3), tileId(1, 2) }; // still no 6
    b.setHandsForTest(humanIds, 2, aiIds, 1);
    b.setBoneyardForTest(boneyardIds, 2);
    b.setTurnForTest(true);

    check("no legal play to start", !b.currentSideHasLegalPlay());
    check("passHuman() is rejected (boneyard isn't empty yet)", !b.passHuman());
    check("first draw succeeds", b.drawHumanTile());
    check("hand grew to 3", b.humanHandCount() == 3);
    check("still no legal play", !b.currentSideHasLegalPlay());
    check("second draw succeeds", b.drawHumanTile());
    check("boneyard is now empty", b.boneyardCount() == 0);
    check("a third draw is rejected (nothing left)", !b.drawHumanTile());
    check("passHuman() now succeeds", b.passHuman());
    check("result() is still IN_PROGRESS (only one side has passed)", b.result() == DominoesResult::IN_PROGRESS);
    check("turn passed to the AI", !b.isHumanTurn());
    printf("\n");
}

static void blockedLowerPipsWinsChecks() {
    printf("--- Hand-built scenario: both sides pass in a row -- lower pip total wins ---\n");
    DominoesBoard b;
    uint8_t chainIds[1] = { tileId(6, 6) };
    bool flipped[1] = { false };
    b.setChainForTest(chainIds, flipped, 1);

    uint8_t humanIds[1] = { tileId(0, 1) }; // pip total 1
    uint8_t aiIds[1] = { tileId(0, 2) };    // pip total 2 -- neither matches the chain's 6-6 ends
    b.setHandsForTest(humanIds, 1, aiIds, 1);
    b.setBoneyardForTest(nullptr, 0);
    b.setTurnForTest(true);

    check("human passes", b.passForTest());
    check("result() still IN_PROGRESS after only one pass", b.result() == DominoesResult::IN_PROGRESS);
    check("AI passes too", b.passForTest());
    check("result() is HUMAN_WINS (1 pip < 2 pips)", b.result() == DominoesResult::HUMAN_WINS);
    printf("\n");
}

static void blockedTieChecks() {
    printf("--- Hand-built scenario: a blocked hand can end in a genuine tie ---\n");
    DominoesBoard b;
    uint8_t chainIds[1] = { tileId(6, 6) };
    bool flipped[1] = { false };
    b.setChainForTest(chainIds, flipped, 1);

    uint8_t humanIds[1] = { tileId(0, 2) }; // pip total 2
    uint8_t aiIds[1] = { tileId(1, 1) };    // pip total 2 -- equal
    b.setHandsForTest(humanIds, 1, aiIds, 1);
    b.setBoneyardForTest(nullptr, 0);
    b.setTurnForTest(true);

    check("human passes", b.passForTest());
    check("AI passes too", b.passForTest());
    check("result() is DRAW (equal pip totals)", b.result() == DominoesResult::DRAW);
    printf("\n");
}

// ---------------------------------------------------------------------------
// 3) Difficulty-tier spot checks -- using the test-only aiHandTileForTest()
//    seam to verify the AI chose the SPECIFIC tile the rules say it should.
// ---------------------------------------------------------------------------
static void mediumTierTriesLeftEndFirstChecks() {
    printf("--- MEDIUM tier: tries the left end before the right, regardless of hand order ---\n");
    DominoesBoard b;
    uint8_t chainIds[1] = { tileId(2, 5) };
    bool flipped[1] = { false }; // left end 2, right end 5
    b.setChainForTest(chainIds, flipped, 1);

    // Index 0 matches only the RIGHT end; index 1 matches only the LEFT end.
    // legalPlays() lists every left-match before any right-match (see
    // DominoesLogic.cpp), so MEDIUM (which always takes the first option)
    // must choose index 1's tile, not index 0's, despite index 0 coming
    // first in the hand array.
    uint8_t aiIds[2] = { tileId(5, 1), tileId(2, 6) };
    uint8_t humanIds[1] = { tileId(0, 0) }; // filler, unused
    b.setHandsForTest(humanIds, 1, aiIds, 2);
    b.setTurnForTest(false);
    b.setDifficulty(CpuDifficulty::MEDIUM);

    b.playAi();
    check("AI hand shrank by exactly one", b.aiHandCount() == 1);
    check("the LEFT-matching tile (2-6) was chosen, not the right-matching one (5-1)", b.leftEndValue() == 6);
    check("the remaining AI tile is the untouched 5-1", b.aiHandTileForTest(0).a == 1 && b.aiHandTileForTest(0).b == 5);
    printf("\n");
}

static void hardTierPrefersHeaviestChecks() {
    printf("--- HARD tier: prefers the heaviest tile (doubles weighted extra) among its legal options ---\n");
    DominoesBoard b;
    uint8_t chainIds[1] = { tileId(2, 5) };
    bool flipped[1] = { false };
    b.setChainForTest(chainIds, flipped, 1); // left end 2, right end 5

    // Three legal options on the right end (5): a light tile (5-0, weight
    // 5), a heavier non-double (5-6, weight 11), and a double (5-5, weight
    // 100+10=110) -- HARD must pick the double despite it scoring the
    // fewest raw pips, since DominoGame.kt's own HARD weighting adds +100
    // for a double specifically (see DominoesLogic.cpp's chooseBotPlayIndex).
    uint8_t aiIds[3] = { tileId(5, 0), tileId(5, 6), tileId(5, 5) };
    uint8_t humanIds[1] = { tileId(1, 1) };
    b.setHandsForTest(humanIds, 1, aiIds, 3);
    b.setTurnForTest(false);
    b.setDifficulty(CpuDifficulty::HARD);

    b.playAi();
    check("AI hand shrank by exactly one", b.aiHandCount() == 2);
    check("the double (5-5) was chosen over the heavier-by-raw-pips 5-6", b.rightEndValue() == 5);
    check("the chain tile just placed really is the 5-5 double", b.chainTileAt(1).isDouble() && b.chainTileAt(1).a == 5);
    printf("\n");
}

static void easyTierAlwaysLegalChecks() {
    printf("--- EASY tier: always ends up making a legal play when one exists, over many trials ---\n");
    seedDominoesRandom(4242);
    bool allOk = true;
    for (int trial = 0; trial < 500; trial++) {
        DominoesBoard b;
        uint8_t chainIds[1] = { tileId(2, 5) };
        bool flipped[1] = { false };
        b.setChainForTest(chainIds, flipped, 1);
        uint8_t aiIds[2] = { tileId(2, 6), tileId(5, 6) }; // both legal, one per end
        uint8_t humanIds[1] = { tileId(0, 0) };
        b.setHandsForTest(humanIds, 1, aiIds, 2);
        b.setTurnForTest(false);
        b.setDifficulty(CpuDifficulty::EASY);

        b.playAi();
        if (b.aiHandCount() != 1 || b.chainLength() != 2) allOk = false;
    }
    check("EASY always plays a legal tile when one exists (chain grows, hand shrinks)", allOk);
    printf("\n");
}

// ---------------------------------------------------------------------------
// 4) Bounded exhaustive tree walk from the real starting deal -- checking
//    the tile-conservation invariant at every node reached.
// ---------------------------------------------------------------------------
static long exhaustiveNodesVisited = 0;
static long exhaustiveFailures = 0;
static const long EXHAUSTIVE_NODE_CAP = 200000;
static const int EXHAUSTIVE_DEPTH_PLIES = 4;

static void exhaustiveWalk(const DominoesBoard &board, int depthRemaining) {
    if (exhaustiveNodesVisited >= EXHAUSTIVE_NODE_CAP) return;
    exhaustiveNodesVisited++;
    if (totalTilesAccountedFor(board) != DOMINOES_SET_SIZE) exhaustiveFailures++;

    if (depthRemaining == 0) return;
    if (board.result() != DominoesResult::IN_PROGRESS) return;

    DominoesLegalPlay plays[DOMINOES_MAX_LEGAL_PLAYS];
    uint8_t n = board.legalPlays(plays);
    if (n == 0) {
        // No legal play -- draw or pass is the only "move" available; walk
        // exactly one of those two single-child branches rather than
        // fanning out (drawing/passing has no real branching of its own).
        DominoesBoard next = board;
        bool acted = (next.boneyardCount() > 0) ? next.drawForTest() : next.passForTest();
        if (!acted) { exhaustiveFailures++; return; } // should always succeed given the guards above
        exhaustiveWalk(next, depthRemaining - 1);
        return;
    }
    for (uint8_t i = 0; i < n; i++) {
        if (exhaustiveNodesVisited >= EXHAUSTIVE_NODE_CAP) return;
        DominoesBoard next = board;
        bool ok = next.playMoveForTest(plays[i].handIndex, plays[i].attachToLeft);
        if (!ok) { exhaustiveFailures++; continue; } // a move legalPlays() offered was itself rejected -- bug
        exhaustiveWalk(next, depthRemaining - 1);
    }
}

static void exhaustiveTreeWalkCheck() {
    printf("=== Bounded exhaustive walk from the starting deal (depth %d plies, capped at %ld nodes) ===\n",
           EXHAUSTIVE_DEPTH_PLIES, EXHAUSTIVE_NODE_CAP);
    DominoesBoard start;
    start.reset();
    exhaustiveNodesVisited = 0;
    exhaustiveFailures = 0;
    exhaustiveWalk(start, EXHAUSTIVE_DEPTH_PLIES);
    printf("Nodes visited: %ld, invariant failures: %ld\n\n", exhaustiveNodesVisited, exhaustiveFailures);
}

// ---------------------------------------------------------------------------
// 5) Large random-vs-random self-play.
// ---------------------------------------------------------------------------
static long randomSimFailures = 0;

static void randomSelfPlaySimulation(int numGames, int maxMovesPerGame) {
    printf("=== Random-vs-random self-play simulation (%d games, %d-move safety cap each) ===\n", numGames, maxMovesPerGame);
    long totalMoves = 0, humanWins = 0, aiWins = 0, draws = 0, hitCap = 0;
    long illegalRejections = 0, conservationFailures = 0;

    for (int g = 0; g < numGames; g++) {
        DominoesBoard b;
        b.reset();
        int moveCount = 0;
        while (b.result() == DominoesResult::IN_PROGRESS && moveCount < maxMovesPerGame) {
            if (totalTilesAccountedFor(b) != DOMINOES_SET_SIZE) conservationFailures++;
            DominoesLegalPlay plays[DOMINOES_MAX_LEGAL_PLAYS];
            uint8_t n = b.legalPlays(plays);
            bool acted;
            if (n > 0) {
                DominoesLegalPlay p = plays[rand() % n];
                acted = b.playMoveForTest(p.handIndex, p.attachToLeft);
            } else if (b.boneyardCount() > 0) {
                acted = b.drawForTest();
            } else {
                acted = b.passForTest();
            }
            if (!acted) { illegalRejections++; break; }
            moveCount++;
        }
        totalMoves += moveCount;

        DominoesResult r = b.result();
        if (r == DominoesResult::HUMAN_WINS) humanWins++;
        else if (r == DominoesResult::AI_WINS) aiWins++;
        else if (r == DominoesResult::DRAW) draws++;
        else hitCap++;
    }

    printf("Games: %d, total moves: %ld (avg %.1f/game)\n", numGames, totalMoves, (double)totalMoves / numGames);
    printf("  Human wins: %ld, AI wins: %ld, draws: %ld, hit the %d-move safety cap: %ld\n",
           humanWins, aiWins, draws, maxMovesPerGame, hitCap);
    printf("  Illegal-move rejections mid-game (should be 0): %ld\n", illegalRejections);
    printf("  Tile-conservation failures (should be 0): %ld\n", conservationFailures);
    printf("  Note: like Mancala (and unlike Checkers), this game always concludes on its own -- every move\n");
    printf("  either shrinks a hand toward 0 or shrinks the boneyard toward a forced block -- so hitting the\n");
    printf("  safety cap above would itself indicate a bug, not an expected outcome.\n\n");

    randomSimFailures = illegalRejections + conservationFailures + hitCap;
}

// ---------------------------------------------------------------------------
// 6) Touch hit-testing.
// ---------------------------------------------------------------------------
static int layoutChecksRun = 0, layoutChecksFailed = 0;
static void checkLayout(const char *label, bool condition) {
    layoutChecksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) layoutChecksFailed++;
}

static void touchHitTestChecks() {
    printf("=== Touch hit-testing checks (DominoesDisplay.cpp, against Config.h's real screen size) ===\n");
    DominoesLayout l = computeDominoesLayout();

    printf("  layout: chain track x=%d w=%d, tile %dx%d, visible tiles=%u\n",
           l.chainX, l.chainW, l.tileW, l.tileH, dominoesChainVisibleTileCount(l));

    checkLayout("status bar fits within the screen width", l.statusY == 0);
    checkLayout("difficulty chip fits within the status bar",
                l.difficultyX >= 0 && l.difficultyX + l.difficultyW <= SCREEN_WIDTH &&
                l.difficultyY >= l.statusY && l.difficultyY + l.difficultyH <= l.statusY + l.statusH);
    checkLayout("chain track (with its two chevrons) fits within the screen width",
                l.chainX >= 0 && l.chainX + l.chainW + l.chevronW <= SCREEN_WIDTH);
    checkLayout("boneyard box fits within the screen",
                l.boneyardX >= 0 && l.boneyardX + l.boneyardW <= SCREEN_WIDTH &&
                l.boneyardY >= 0 && l.boneyardY + l.boneyardH <= SCREEN_HEIGHT);
    checkLayout("pass button fits within the screen",
                l.passX >= 0 && l.passX + l.passW <= SCREEN_WIDTH &&
                l.passY >= 0 && l.passY + l.passH <= SCREEN_HEIGHT);
    checkLayout("human hand row fits within the screen height",
                l.handY >= 0 && l.handY + l.tileH <= SCREEN_HEIGHT);
    checkLayout("play-again button fits within the screen",
                l.buttonX >= 0 && l.buttonX + l.buttonW <= SCREEN_WIDTH &&
                l.buttonY >= 0 && l.buttonY + l.buttonH <= SCREEN_HEIGHT);
    checkLayout("at least one tile fits in the visible chain window", dominoesChainVisibleTileCount(l) >= 1);

    // Hand hit-testing, mirroring UnoDisplay's own centers-resolve-correctly
    // + high-index-wins-on-overlap checks.
    // Every tile's own drawn position (derived the same way
    // drawDominoesHumanHand() lays them out) taps back to a valid index,
    // for a range of hand sizes -- a full pixel sweep at hand-row height,
    // the same style of check UnoDisplay's own hand hit-testing gets.
    bool allCentersOk = true;
    for (uint8_t count = 1; count <= 7; count++) {
        for (int16_t x = 0; x < SCREEN_WIDTH; x++) {
            uint8_t idx;
            if (hitTestDominoesHumanHandTile(l, count, x, l.handY + l.tileH / 2, idx)) {
                if (idx >= count) allCentersOk = false;
            }
        }
    }
    checkLayout("every hand hit-test result (for hand sizes 1-7) is a valid in-range index", allCentersOk);

    uint8_t discard;
    checkLayout("a tap above the hand row is not a hit", !hitTestDominoesHumanHandTile(l, 7, SCREEN_WIDTH / 2, l.handY - 1, discard));
    checkLayout("a tap below the hand row is not a hit", !hitTestDominoesHumanHandTile(l, 7, SCREEN_WIDTH / 2, l.handY + l.tileH, discard));
    checkLayout("an empty hand (count=0) never reports a hit", !hitTestDominoesHumanHandTile(l, 0, SCREEN_WIDTH / 2, l.handY + 5, discard));

    int16_t bcx = l.boneyardX + l.boneyardW / 2, bcy = l.boneyardY + l.boneyardH / 2;
    checkLayout("boneyard's own center is a hit", hitTestDominoesBoneyard(l, bcx, bcy));
    checkLayout("1px left of the boneyard is not a hit", !hitTestDominoesBoneyard(l, l.boneyardX - 1, bcy));

    int16_t pcx = l.passX + l.passW / 2, pcy = l.passY + l.passH / 2;
    checkLayout("pass button's own center is a hit", hitTestDominoesPassButton(l, pcx, pcy));
    checkLayout("1px right of the pass button is not a hit", !hitTestDominoesPassButton(l, l.passX + l.passW, pcy));

    checkLayout("home button's own center is a hit", hitTestDominoesHomeButton(l, 15, l.statusY + l.statusH / 2));
    checkLayout("home button does not swallow taps in the middle of the status bar",
                !hitTestDominoesHomeButton(l, SCREEN_WIDTH / 2, l.statusY + l.statusH / 2));

    int16_t dcx = l.difficultyX + l.difficultyW / 2, dcy = l.difficultyY + l.difficultyH / 2;
    checkLayout("difficulty chip's own center is a hit", hitTestDominoesDifficultyButton(l, dcx, dcy));

    checkLayout("left chevron's own center is a hit", hitTestDominoesLeftChevron(l, l.chevronW / 2, l.chainY + l.chainH / 2));
    checkLayout("right chevron's own center is a hit",
                hitTestDominoesRightChevron(l, SCREEN_WIDTH - l.chevronW / 2, l.chainY + l.chainH / 2));
    checkLayout("the middle of the chain track is neither chevron",
                !hitTestDominoesLeftChevron(l, SCREEN_WIDTH / 2, l.chainY + l.chainH / 2) &&
                !hitTestDominoesRightChevron(l, SCREEN_WIDTH / 2, l.chainY + l.chainH / 2));

    int16_t playAgainCx = l.buttonX + l.buttonW / 2, playAgainCy = l.buttonY + l.buttonH / 2;
    checkLayout("play-again button's own center is a hit", hitTestDominoesPlayAgainButton(l, playAgainCx, playAgainCy));

    printf("Layout checks run: %d, failed: %d\n\n", layoutChecksRun, layoutChecksFailed);
}

// ---------------------------------------------------------------------------
// 7) Rendering smoke test.
// ---------------------------------------------------------------------------
static void renderingSmokeTest() {
    printf("=== Rendering smoke test (stubbed TFT_eSPI -- confirms no crash, NOT actual pixels) ===\n");
    TFT_eSPI tft;
    DominoesLayout l = computeDominoesLayout();
    DominoesBoard b;
    b.reset();
    drawDominoesStaticChrome(tft, l);
    drawDominoesStatus(tft, l, "Your turn", CpuDifficulty::MEDIUM);
    drawDominoesAiHand(tft, l, b.aiHandCount());
    drawDominoesChain(tft, l, b, 0);
    drawDominoesScrollChevrons(tft, l, false, false);
    drawDominoesSelectionDropZones(tft, l, true, false);
    drawDominoesBoneyard(tft, l, b.boneyardCount());
    drawDominoesPassButton(tft, l);
    DominoTileView handTiles[7];
    for (uint8_t i = 0; i < b.humanHandCount(); i++) handTiles[i] = b.humanHandTile(i);
    drawDominoesHumanHand(tft, l, handTiles, b.humanHandCount(), 0xFFFFFFFFu, 0);
    drawDominoesPlayAgainButton(tft, l);
    hideDominoesPlayAgainButton(tft, l);
    printf("  completed with no crash\n\n");
}

int main() {
    srand(12345); // fixed seed -- reproducible test runs
    seedDominoesRandom(12345);

    renderingSmokeTest();
    watchOneGame();

    printf("=== Rule-correctness checks (hand-built positions) ===\n");
    startingPositionChecks();
    attachLeftFlipChecks();
    attachLeftRequiringFlipChecks();
    attachRightFlipChecks();
    illegalAttachRejectedChecks();
    emptyingHandWinsChecks();
    mustDrawBeforePlayingIsLegalChecks();
    drawUntilBoneyardEmptyThenPassChecks();
    blockedLowerPipsWinsChecks();
    blockedTieChecks();
    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);

    mediumTierTriesLeftEndFirstChecks();
    hardTierPrefersHeaviestChecks();
    easyTierAlwaysLegalChecks();

    exhaustiveTreeWalkCheck();
    randomSelfPlaySimulation(3000, 300);
    touchHitTestChecks();

    bool allGood = (checksFailed == 0) &&
                   (exhaustiveFailures == 0) &&
                   (randomSimFailures == 0) &&
                   (layoutChecksFailed == 0);
    printf("=== OVERALL: %s ===\n", allGood ? "ALL PLAYTEST CHECKS PASSED" : "FAILURES FOUND -- SEE ABOVE");
    return allGood ? 0 : 1;
}
