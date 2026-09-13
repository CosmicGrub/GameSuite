// Native playtest driver for the ESP32 Spider Solitaire game's
// SpiderLogic.h/.cpp AND SpiderDisplay.cpp's pure hit-testing math --
// compiles and runs the EXACT, unmodified files shipped in
// esp32-tictactoe/TicTacToeESP32/ on a desktop (against the project's shared
// no-op TFT_eSPI/Arduino stubs), the same spirit as every other game's own
// native_test driver here. This does NOT and CANNOT verify actual pixel
// rendering, touch calibration, or any real SPI/TFT_eSPI behavior -- only
// the physical board can confirm that half.
//
// Like solitaire_playtest.cpp's own Klondike coverage, Spider is a solo
// puzzle with no opponent and no fixed win/loss cadence a "random self-play"
// simulation can lean on -- so this runs the same bounded "monkey test"
// idea instead: many random legal-or-illegal taps in a row, checking the
// one hard invariant that must never break regardless of how nonsensical
// the tap sequence is -- all 104 cards (note: Spider genuinely has TWO
// copies of every suit+rank pair, so this checks unique instanceIds, not
// unique (suit, rank) identities -- see SpiderLogic.h's own
// columnCardIdForTest()/stockIdForTest() comment) are accounted for exactly
// once, across every pile, at all times. Real rule correctness is covered
// instead by hand-built positions.

#include <cstdio>
#include <cstdlib>
#include "../TicTacToeESP32/SpiderLogic.h"
#include "../TicTacToeESP32/SpiderDisplay.h"
#include "../TicTacToeESP32/Config.h"

static int checksRun = 0, checksFailed = 0;
static void check(const char *label, bool condition) {
    checksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) checksFailed++;
}

static uint8_t idOfCard(SpiderSuit suit, SpiderRank rank) {
    return (uint8_t)suit * SPIDER_RANK_COUNT + ((uint8_t)rank - 1);
}

// Every one of the 104 raw instanceIds is accounted for exactly once,
// across the tableau and the stock -- see this file's own top comment on
// why raw ids (not decoded cards) are what must be checked here.
static bool cardsConserved(const SpiderBoard &b) {
    bool seen[SPIDER_DECK_SIZE] = { false };
    uint16_t total = 0;
    auto mark = [&](uint8_t id) -> bool {
        if (id >= SPIDER_DECK_SIZE || seen[id]) return false;
        seen[id] = true;
        total++;
        return true;
    };
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) {
        for (uint8_t i = 0; i < b.columnCount(c); i++) {
            if (!mark(b.columnCardIdForTest(c, i))) return false;
        }
    }
    for (uint8_t i = 0; i < b.stockCount(); i++) {
        if (!mark(b.stockIdForTest(i))) return false;
    }
    // Completed sequences remove cards from the tableau entirely (they're
    // never placed anywhere native_test can see again) -- account for
    // SPIDER_SEQUENCE_LENGTH cards "gone but accounted for" per completed
    // sequence rather than tracking their exact ids (which cards ended up
    // in a given sequence isn't otherwise observable, nor does it need to
    // be -- see completedSequences()'s own role).
    total = (uint16_t)(total + (uint16_t)b.completedSequences() * SPIDER_SEQUENCE_LENGTH);
    return total == SPIDER_DECK_SIZE;
}

// ---------------------------------------------------------------------------
// 1) Deal correctness.
// ---------------------------------------------------------------------------
static void dealChecks() {
    printf("--- Deal correctness (dealFromOrderForTest with a known card order) ---\n");
    SpiderBoard b;
    uint8_t order[SPIDER_DECK_SIZE];
    for (uint8_t i = 0; i < SPIDER_DECK_SIZE; i++) order[i] = i;
    b.dealFromOrderForTest(order);

    check("all 104 cards accounted for right after a fresh deal", cardsConserved(b));
    check("stock holds the remaining 50 (104 - 54 dealt)", b.stockCount() == 50);

    bool shapeRight = true;
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) {
        uint8_t expectedTotal = (c < 4) ? 6 : 5;
        uint8_t expectedFaceDown = (uint8_t)(expectedTotal - 1);
        if (b.columnCount(c) != expectedTotal || b.columnFaceDownCount(c) != expectedFaceDown) shapeRight = false;
    }
    check("columns 0-3 hold 6 cards (5 face-down), columns 4-9 hold 5 (4 face-down)", shapeRight);

    check("column 0's own top card is order[5] (the 6th card dealt)", b.columnCardIdForTest(0, 5) == order[5]);
    check("column 3's own top card is order[23]", b.columnCardIdForTest(3, 5) == order[23]);
    check("column 4's own top card is order[28] (first 5-card column)", b.columnCardIdForTest(4, 4) == order[28]);
    check("column 9's own top card is order[53] (the 54th and last dealt card)", b.columnCardIdForTest(9, 4) == order[53]);
    check("the stock's own top (next to deal) card is the deck's last card, order[103]",
          b.stockIdForTest(b.stockCount() - 1) == order[103]);
    printf("\n");
}

// ---------------------------------------------------------------------------
// 2) Hand-built rule-correctness scenarios.
// ---------------------------------------------------------------------------
static void placementLegalityChecks() {
    printf("--- canPlaceOn(): exactly one rank higher, ANY suit (looser than Klondike); empty column takes anything ---\n");
    SpiderBoard b;
    uint8_t col0[1] = { idOfCard(SpiderSuit::HEARTS, SpiderRank::SEVEN) };
    b.setColumnForTest(0, col0, 1, 0);

    check("a black 6 (different suit) can go on a red 7 -- suit doesn't matter here", b.canPlaceOn({ SpiderSuit::SPADES, SpiderRank::SIX }, 0));
    check("a red 6 of the SAME suit can also go on a red 7", b.canPlaceOn({ SpiderSuit::HEARTS, SpiderRank::SIX }, 0));
    check("a 5 (wrong rank) is rejected", !b.canPlaceOn({ SpiderSuit::CLUBS, SpiderRank::FIVE }, 0));
    check("an 8 (wrong direction) is rejected", !b.canPlaceOn({ SpiderSuit::CLUBS, SpiderRank::EIGHT }, 0));

    b.setColumnForTest(1, nullptr, 0, 0); // empty column
    check("an empty column accepts a Queen (no King-only restriction, unlike Klondike)", b.canPlaceOn({ SpiderSuit::CLUBS, SpiderRank::QUEEN }, 1));
    check("an empty column also accepts a King", b.canPlaceOn({ SpiderSuit::CLUBS, SpiderRank::KING }, 1));
    printf("\n");
}

static void selectDeselectAndMoveChecks() {
    printf("--- Hand-built scenario: select a column's top card, move it onto a legal destination ---\n");
    SpiderBoard b;
    uint8_t col0[1] = { idOfCard(SpiderSuit::CLUBS, SpiderRank::SIX) };
    uint8_t col1[1] = { idOfCard(SpiderSuit::DIAMONDS, SpiderRank::SEVEN) };
    b.setColumnForTest(0, col0, 1, 0);
    b.setColumnForTest(1, col1, 1, 0);

    b.tapColumn(0);
    check("column 0 is now selected", b.selectedColumn() == 0);
    b.tapColumn(0);
    check("tapping the same column again deselects it", b.selectedColumn() == -1);

    b.tapColumn(0);
    b.tapColumn(1);
    check("the move succeeded -- column 0 is now empty", b.columnCount(0) == 0);
    check("column 1 gained the card on top", b.columnCount(1) == 2);
    check("the moved card really is column 1's new top", b.columnCardIdForTest(1, 1) == col0[0]);
    check("selection cleared after a successful move", b.selectedColumn() == -1);
    printf("\n");
}

static void illegalMoveRetargetsChecks() {
    printf("--- Hand-built scenario: an illegal move re-targets the selection instead ---\n");
    SpiderBoard b;
    uint8_t col0[1] = { idOfCard(SpiderSuit::CLUBS, SpiderRank::TWO) }; // won't fit column 1 at all
    uint8_t col1[1] = { idOfCard(SpiderSuit::HEARTS, SpiderRank::NINE) };
    b.setColumnForTest(0, col0, 1, 0);
    b.setColumnForTest(1, col1, 1, 0);

    b.tapColumn(0);
    b.tapColumn(1);
    check("selection re-targeted to column 1 rather than performing an illegal move", b.selectedColumn() == 1);
    check("column 0 is untouched", b.columnCount(0) == 1);
    printf("\n");
}

static void autoFlipChecks() {
    printf("--- Hand-built scenario: emptying a column's face-up cards auto-flips the one beneath ---\n");
    SpiderBoard b;
    uint8_t col0[2] = { idOfCard(SpiderSuit::SPADES, SpiderRank::FOUR), idOfCard(SpiderSuit::HEARTS, SpiderRank::TEN) };
    b.setColumnForTest(0, col0, 2, 1); // 1 face-down (the 4 of spades), 1 face-up (the 10 of hearts) on top
    uint8_t col1[1] = { idOfCard(SpiderSuit::CLUBS, SpiderRank::JACK) };
    b.setColumnForTest(1, col1, 1, 0); // Jack -- legal destination for the 10 (any suit)

    b.tapColumn(0);
    b.tapColumn(1);
    check("column 0's face-down card auto-flipped face-up", b.columnFaceDownCount(0) == 0 && b.columnCount(0) == 1);
    check("the newly-flipped card is the 4 of spades", b.columnCardIdForTest(0, 0) == col0[0]);
    printf("\n");
}

static void completedSequenceChecks() {
    printf("--- Hand-built scenario: completing a same-suit King-to-Ace run sweeps it off the tableau ---\n");
    SpiderBoard b;
    // Column 0 already holds King down to 2 of hearts (12 cards, all
    // face-up); column 1 holds just the Ace of hearts. Moving the Ace onto
    // column 0 (legal: 2's rank minus 1 == Ace) completes the run.
    uint8_t col0[12];
    for (uint8_t i = 0; i < 12; i++) col0[i] = idOfCard(SpiderSuit::HEARTS, (SpiderRank)(13 - i)); // King, Queen, ..., Two
    b.setColumnForTest(0, col0, 12, 0);
    uint8_t col1[1] = { idOfCard(SpiderSuit::HEARTS, SpiderRank::ACE) };
    b.setColumnForTest(1, col1, 1, 0);

    check("completedSequences() starts at 0", b.completedSequences() == 0);
    b.tapColumn(1);
    b.tapColumn(0);
    check("column 0 is now completely empty -- the whole 13-card run swept away", b.columnCount(0) == 0);
    check("completedSequences() is now 1", b.completedSequences() == 1);
    check("column 1 (the move's own source) is empty too", b.columnCount(1) == 0);
    printf("\n");
}

static void winDetectionChecks() {
    printf("--- Hand-built scenario: completing the 8th sequence wins the hand ---\n");
    SpiderBoard b;
    b.setCompletedSequencesForTest(7);
    uint8_t col0[12];
    for (uint8_t i = 0; i < 12; i++) col0[i] = idOfCard(SpiderSuit::SPADES, (SpiderRank)(13 - i));
    b.setColumnForTest(0, col0, 12, 0);
    uint8_t col1[1] = { idOfCard(SpiderSuit::SPADES, SpiderRank::ACE) };
    b.setColumnForTest(1, col1, 1, 0);

    check("not won yet -- only 7 of 8 sequences complete", !b.isWon());
    b.tapColumn(1);
    b.tapColumn(0);
    check("the 8th sequence completed", b.completedSequences() == 8);
    check("the hand is now won", b.isWon());
    check("gamesWonThisSession() incremented by exactly 1", b.gamesWonThisSession() == 1);
    printf("\n");
}

static void stockDealActuallyWorksChecks() {
    printf("--- Hand-built scenario: dealing from the stock is blocked while any column is empty, and a legal deal adds one card to every column, in order ---\n");
    SpiderBoard b;
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) {
        uint8_t card[1] = { idOfCard((SpiderSuit)(c % 4), (SpiderRank)((c % 13) + 1)) };
        b.setColumnForTest(c, card, 1, 0);
    }
    uint8_t someStock[5] = { 0, 1, 2, 3, 4 };
    b.setStockForTest(someStock, 5);
    check("canDealFromStock() is true -- the stock is nonempty and every column has a card", b.canDealFromStock());

    // Now empty one column out and confirm the deal is refused.
    uint8_t emptyIds[1];
    b.setColumnForTest(9, emptyIds, 0, 0);
    check("canDealFromStock() is false once a column is empty", !b.canDealFromStock());
    uint8_t stockIdsForEmptyCheck[10];
    for (uint8_t i = 0; i < 10; i++) stockIdsForEmptyCheck[i] = i;
    b.setStockForTest(stockIdsForEmptyCheck, 10);
    b.tapStock();
    check("tapStock() is a no-op while a column is empty", b.stockCount() == 10);
    printf("\n");
}

static void stockDealAddsOneCardPerColumnChecks() {
    printf("--- Hand-built scenario: a legal stock deal adds one card to every column, in order ---\n");
    SpiderBoard b;
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) {
        uint8_t card[1] = { idOfCard(SpiderSuit::CLUBS, SpiderRank::TWO) };
        b.setColumnForTest(c, card, 1, 0);
    }
    uint8_t stockIds[10];
    for (uint8_t i = 0; i < 10; i++) stockIds[i] = i; // ids 0-9, distinct
    b.setStockForTest(stockIds, 10);

    b.tapStock();
    check("stock is now empty", b.stockCount() == 0);
    bool everyColumnGrewByOne = true;
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) {
        if (b.columnCount(c) != 2) everyColumnGrewByOne = false;
    }
    check("every column gained exactly one card", everyColumnGrewByOne);
    check("column 0 received the stock's own top card (id 9)", b.columnCardIdForTest(0, 1) == 9);
    check("column 9 received the stock's own last-dealt card (id 0)", b.columnCardIdForTest(9, 1) == 0);

    b.tapStock(); // stock is now empty
    check("tapping an empty stock changes nothing further", b.stockCount() == 0);
    printf("\n");
}

static void undoChecks() {
    printf("--- Hand-built scenario: undo restores the exact pre-move state ---\n");
    SpiderBoard b;
    uint8_t col0[1] = { idOfCard(SpiderSuit::CLUBS, SpiderRank::SIX) };
    uint8_t col1[1] = { idOfCard(SpiderSuit::DIAMONDS, SpiderRank::SEVEN) };
    b.setColumnForTest(0, col0, 1, 0);
    b.setColumnForTest(1, col1, 1, 0);
    check("nothing to undo yet", !b.canUndo());

    b.tapColumn(0);
    b.tapColumn(1);
    check("the move happened", b.columnCount(0) == 0 && b.columnCount(1) == 2);
    b.undo();
    check("undo restored both columns", b.columnCount(0) == 1 && b.columnCount(1) == 1);
    check("undo consumed its own history entry", !b.canUndo());

    // Regression: undo() must refuse to undo a hand's own winning sequence
    // completion -- see SolitaireLogic.cpp's own identical guard and KDoc
    // (a real bug found via adversarial review of the Klondike port, built
    // into Spider from the start instead of waiting to rediscover it).
    b.setCompletedSequencesForTest(7);
    uint8_t win0[12];
    for (uint8_t i = 0; i < 12; i++) win0[i] = idOfCard(SpiderSuit::DIAMONDS, (SpiderRank)(13 - i));
    b.setColumnForTest(0, win0, 12, 0);
    uint8_t win1[1] = { idOfCard(SpiderSuit::DIAMONDS, SpiderRank::ACE) };
    b.setColumnForTest(1, win1, 1, 0);
    b.tapColumn(1);
    b.tapColumn(0);
    check("the deal is won", b.isWon());
    check("an undo entry exists from the winning move itself", b.canUndo());
    b.undo();
    check("undo refused to roll back the win", b.isWon());
    check("gamesWonThisSession() is still exactly 1 (not rolled back, and not re-earnable)", b.gamesWonThisSession() == 1);
    printf("\n");
}

// ---------------------------------------------------------------------------
// 3) Monkey test.
// ---------------------------------------------------------------------------
static long monkeyFailures = 0;

static void monkeyTest(int numGames, int stepsPerGame) {
    printf("=== Monkey test (%d games, %d random taps each) ===\n", numGames, stepsPerGame);
    long totalSteps = 0, gamesSolved = 0;
    for (int g = 0; g < numGames; g++) {
        SpiderBoard b;
        b.reset();
        for (int step = 0; step < stepsPerGame; step++) {
            if (!cardsConserved(b)) { monkeyFailures++; break; }
            if (rand() % 6 == 0) {
                b.tapStock();
            } else {
                b.tapColumn((uint8_t)(rand() % SPIDER_COLUMN_COUNT));
            }
            totalSteps++;
            if (b.isWon()) { gamesSolved++; break; }
        }
        if (!cardsConserved(b)) monkeyFailures++;
    }
    printf("Games: %d, total taps: %ld, solved by pure random taps: %ld\n", numGames, totalSteps, gamesSolved);
    printf("  Card-conservation failures (should be 0): %ld\n", monkeyFailures);
    printf("  Note: solving a deal via uniformly random taps is essentially impossible by design (real\n");
    printf("  strategy is required, especially with no multi-card group moves) -- a zero solved count\n");
    printf("  here is expected, not a failure; what matters is the conservation invariant never breaks.\n\n");
}

// ---------------------------------------------------------------------------
// 4) Touch hit-testing.
// ---------------------------------------------------------------------------
static int layoutChecksRun = 0, layoutChecksFailed = 0;
static void checkLayout(const char *label, bool condition) {
    layoutChecksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) layoutChecksFailed++;
}

static void touchHitTestChecks() {
    printf("=== Touch hit-testing checks (SpiderDisplay.cpp, against Config.h's real screen size) ===\n");
    SpiderLayout l = computeSpiderLayout();
    printf("  layout: card %dx%d, tableau y=%d h=%d, colStride=%d\n", l.cardW, l.cardH, l.tableauY, l.tableauH, l.colStride);

    checkLayout("all 10 tableau columns fit on-screen without overlapping", l.colX[9] + l.cardW <= SCREEN_WIDTH);
    checkLayout("tableau fits within the screen height", l.tableauY >= 0 && l.tableauY + l.cardH <= SCREEN_HEIGHT);
    checkLayout("New/Undo buttons and the stock button fit on-screen without overlapping",
                l.newX + l.newW <= l.undoX && l.undoX + l.undoW <= l.stockX && l.stockX + l.stockW <= SCREEN_WIDTH);
    checkLayout("sequence chip fits within the status bar",
                l.seqChipX >= 0 && l.seqChipX + l.seqChipW <= SCREEN_WIDTH &&
                l.seqChipY >= l.statusY && l.seqChipY + l.seqChipH <= l.statusY + l.statusH);

    checkLayout("home button's own center is a hit", hitTestSpiderHomeButton(l, 15, l.statusY + l.statusH / 2));
    checkLayout("New button's own center is a hit", hitTestSpiderNewButton(l, l.newX + l.newW / 2, l.newY + l.newH / 2));
    checkLayout("Undo button's own center is a hit", hitTestSpiderUndoButton(l, l.undoX + l.undoW / 2, l.undoY + l.undoH / 2));
    checkLayout("Stock button's own center is a hit", hitTestSpiderStock(l, l.stockX + l.stockW / 2, l.stockY + l.stockH / 2));
    checkLayout("New and Undo buttons don't hit-test as each other", !hitTestSpiderUndoButton(l, l.newX + l.newW / 2, l.newY + l.newH / 2));

    SpiderBoard b;
    b.reset();
    bool everyColumnResolves = true;
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) {
        uint8_t total = b.columnCount(c);
        int16_t offset = spiderFanOffset(l, total);
        int16_t topCardY = l.tableauY + (int16_t)(total - 1) * offset;
        uint8_t hitCol;
        if (!hitTestSpiderTableau(l, b, l.colX[c] + l.cardW / 2, topCardY + l.cardH / 2, hitCol) || hitCol != c) {
            everyColumnResolves = false;
        }
    }
    checkLayout("every dealt column's own topmost card resolves back to that column", everyColumnResolves);

    uint8_t discard;
    checkLayout("a tap well above the tableau is not a hit", !hitTestSpiderTableau(l, b, l.colX[0] + 5, l.tableauY - 5, discard));
    checkLayout("a tap past the last column's right edge is not a hit", !hitTestSpiderTableau(l, b, SCREEN_WIDTH + 5, l.tableauY + 5, discard));

    printf("Layout checks run: %d, failed: %d\n\n", layoutChecksRun, layoutChecksFailed);
}

// ---------------------------------------------------------------------------
// 5) Rendering smoke test.
// ---------------------------------------------------------------------------
static void renderingSmokeTest() {
    printf("=== Rendering smoke test (stubbed TFT_eSPI -- confirms no crash, NOT actual pixels) ===\n");
    TFT_eSPI tft;
    SpiderLayout l = computeSpiderLayout();
    SpiderBoard b;
    b.reset();
    drawSpiderStaticChrome(tft, l);
    drawSpiderStatus(tft, l, "Your turn", b.completedSequences());
    drawSpiderButtons(tft, l);
    drawSpiderStock(tft, l, b.stockCount(), b.canDealFromStock());
    drawSpiderTableau(tft, l, b);
    b.tapColumn(0);
    drawSpiderTableau(tft, l, b);
    printf("  completed with no crash\n\n");
}

int main() {
    srand(12345); // fixed seed -- reproducible test runs
    seedSpiderRandom(12345);

    renderingSmokeTest();

    printf("=== Rule-correctness checks (hand-built positions) ===\n");
    dealChecks();
    placementLegalityChecks();
    selectDeselectAndMoveChecks();
    illegalMoveRetargetsChecks();
    autoFlipChecks();
    completedSequenceChecks();
    winDetectionChecks();
    stockDealActuallyWorksChecks();
    stockDealAddsOneCardPerColumnChecks();
    undoChecks();
    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);

    monkeyTest(500, 400);
    touchHitTestChecks();

    bool allGood = (checksFailed == 0) && (monkeyFailures == 0) && (layoutChecksFailed == 0);
    printf("=== OVERALL: %s ===\n", allGood ? "ALL PLAYTEST CHECKS PASSED" : "FAILURES FOUND -- SEE ABOVE");
    return allGood ? 0 : 1;
}
