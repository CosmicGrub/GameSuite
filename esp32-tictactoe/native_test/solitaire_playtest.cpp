// Native playtest driver for the ESP32 Solitaire (Klondike) game's
// SolitaireLogic.h/.cpp AND SolitaireDisplay.cpp's pure hit-testing math --
// compiles and runs the EXACT, unmodified files shipped in
// esp32-tictactoe/TicTacToeESP32/ on a desktop (against the project's shared
// no-op TFT_eSPI/Arduino stubs), the same spirit as every other game's own
// native_test driver here. This does NOT and CANNOT verify actual pixel
// rendering, touch calibration, or any real SPI/TFT_eSPI behavior -- only
// the physical board can confirm that half.
//
// Unlike this arcade's turn-based games, Solitaire is a solo puzzle with no
// opponent and no fixed win/loss cadence a "random self-play" simulation can
// lean on the way Checkers/Mancala/Dominoes do -- a random tap sequence can
// wander indefinitely without ever solving or getting stuck (drawing from
// the stock is (almost) always available as a fallback). So this file
// instead runs a bounded "monkey test": many random legal-or-illegal taps in
// a row, checking the one hard invariant that must never break regardless
// of how nonsensical the tap sequence is -- every one of the 52 cards is
// accounted for, exactly once, across every pile at all times (using the
// test-only stockIdForTest()/wasteIdForTest()/columnFaceDownIdForTest()
// seam -- see SolitaireLogic.h's own comment on why that seam exists).
// Real rule correctness is covered instead by hand-built positions, mirroring
// every other game's own playtest structure.

#include <cstdio>
#include <cstdlib>
#include <vector>
#include "../TicTacToeESP32/SolitaireLogic.h"
#include "../TicTacToeESP32/SolitaireDisplay.h"
#include "../TicTacToeESP32/Config.h"

static int checksRun = 0, checksFailed = 0;
static void check(const char *label, bool condition) {
    checksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) checksFailed++;
}

static uint8_t idOfCard(SolitaireSuit suit, SolitaireRank rank) {
    return (uint8_t)suit * SOLITAIRE_RANK_COUNT + ((uint8_t)rank - 1);
}

// Every one of the 52 cards is accounted for exactly once, across the
// tableau (both face-down and face-up), the stock, the waste, and the
// foundations (whose own content is always exactly suit x 1..count -- see
// SolitaireLogic.h's foundationCount() comment, so no id lookup is needed
// for those, just marking off however many of that suit's 13 slots its
// count covers).
static bool cardsConserved(const SolitaireBoard &b) {
    bool seen[SOLITAIRE_DECK_SIZE] = { false };
    uint16_t total = 0;
    auto mark = [&](uint8_t id) -> bool {
        if (id >= SOLITAIRE_DECK_SIZE || seen[id]) return false;
        seen[id] = true;
        total++;
        return true;
    };

    for (uint8_t c = 0; c < SOLITAIRE_COLUMN_COUNT; c++) {
        for (uint8_t i = 0; i < b.columnFaceDownCount(c); i++) {
            if (!mark(b.columnFaceDownIdForTest(c, i))) return false;
        }
        for (uint8_t i = 0; i < b.columnFaceUpCount(c); i++) {
            if (!mark(idOfCard(b.columnFaceUpCardAt(c, i).suit, b.columnFaceUpCardAt(c, i).rank))) return false;
        }
    }
    for (uint8_t i = 0; i < b.stockCount(); i++) {
        if (!mark(b.stockIdForTest(i))) return false;
    }
    for (uint8_t i = 0; i < b.wasteCount(); i++) {
        if (!mark(b.wasteIdForTest(i))) return false;
    }
    for (uint8_t s = 0; s < SOLITAIRE_SUIT_COUNT; s++) {
        uint8_t count = b.foundationCount((SolitaireSuit)s);
        for (uint8_t r = 1; r <= count; r++) {
            if (!mark(idOfCard((SolitaireSuit)s, (SolitaireRank)r))) return false;
        }
    }
    return total == SOLITAIRE_DECK_SIZE;
}

// ---------------------------------------------------------------------------
// 1) Deal correctness -- a known, non-shuffled card order fed through the
//    real dealing algorithm, checking exact structure.
// ---------------------------------------------------------------------------
static void dealChecks() {
    printf("--- Deal correctness (dealFromOrderForTest with a known card order) ---\n");
    SolitaireBoard b;
    uint8_t order[SOLITAIRE_DECK_SIZE];
    for (uint8_t i = 0; i < SOLITAIRE_DECK_SIZE; i++) order[i] = i; // id order: every Club A..K, then Diamond A..K, Heart A..K, Spade A..K
    b.dealFromOrderForTest(order);

    check("all 52 cards accounted for right after a fresh deal", cardsConserved(b));
    check("stock holds the remaining 24 (52 - 28 dealt)", b.stockCount() == 24);
    check("waste starts empty", b.wasteCount() == 0);
    for (uint8_t s = 0; s < SOLITAIRE_SUIT_COUNT; s++) check("every foundation starts empty", b.foundationCount((SolitaireSuit)s) == 0);

    bool everyColumnShapeRight = true;
    for (uint8_t c = 0; c < SOLITAIRE_COLUMN_COUNT; c++) {
        if (b.columnFaceDownCount(c) != c || b.columnFaceUpCount(c) != 1) everyColumnShapeRight = false;
    }
    check("column c (0-indexed) has exactly c face-down cards and 1 face-up card", everyColumnShapeRight);

    // Column 0's own face-up card is order[0] (the very first card dealt);
    // column 1's is order[2]; column 2's is order[5] -- the running cursor
    // after dealing columns 0..c is (c+1)(c+2)/2 cards, so column c's own
    // face-up card sits at cursor index (c+1)(c+2)/2 - 1.
    check("column 0's face-up card is the very first card dealt (order[0])",
          idOfCard(b.columnFaceUpCardAt(0, 0).suit, b.columnFaceUpCardAt(0, 0).rank) == order[0]);
    check("column 1's face-up card is order[2]", idOfCard(b.columnFaceUpCardAt(1, 0).suit, b.columnFaceUpCardAt(1, 0).rank) == order[2]);
    check("column 2's face-up card is order[5]", idOfCard(b.columnFaceUpCardAt(2, 0).suit, b.columnFaceUpCardAt(2, 0).rank) == order[5]);
    check("column 6's face-up card is order[27] (the 28th and last dealt card)",
          idOfCard(b.columnFaceUpCardAt(6, 0).suit, b.columnFaceUpCardAt(6, 0).rank) == order[27]);
    check("the stock's own top (last-drawn-first) card is the deck's last card, order[51]",
          b.stockIdForTest(b.stockCount() - 1) == order[51]);
    printf("\n");
}

// ---------------------------------------------------------------------------
// 2) Hand-built rule-correctness scenarios.
// ---------------------------------------------------------------------------
static void tableauLegalityChecks() {
    printf("--- canPlaceOnTableau(): descending rank, alternating color, King-only-on-empty ---\n");
    SolitaireBoard b;
    uint8_t fd0[1]; uint8_t fu0[1] = { idOfCard(SolitaireSuit::HEARTS, SolitaireRank::SEVEN) };
    b.setColumnForTest(0, fd0, 0, fu0, 1); // column 0's exposed top: 7 of Hearts (red)

    check("a black 6 can go on a red 7", b.canPlaceOnTableau({ SolitaireSuit::SPADES, SolitaireRank::SIX }, 0));
    check("a red 6 CANNOT go on a red 7 (same color)", !b.canPlaceOnTableau({ SolitaireSuit::DIAMONDS, SolitaireRank::SIX }, 0));
    check("a black 5 CANNOT go on a red 7 (wrong rank)", !b.canPlaceOnTableau({ SolitaireSuit::CLUBS, SolitaireRank::FIVE }, 0));
    check("a black 8 CANNOT go on a red 7 (wrong direction)", !b.canPlaceOnTableau({ SolitaireSuit::CLUBS, SolitaireRank::EIGHT }, 0));

    b.setColumnForTest(1, fd0, 0, nullptr, 0); // column 1 is empty
    check("only a King may start an empty column -- a Queen is rejected", !b.canPlaceOnTableau({ SolitaireSuit::CLUBS, SolitaireRank::QUEEN }, 1));
    check("a King is accepted on an empty column", b.canPlaceOnTableau({ SolitaireSuit::CLUBS, SolitaireRank::KING }, 1));
    printf("\n");
}

static void foundationLegalityChecks() {
    printf("--- canPlaceOnFoundation(): same suit only, Ace-first, then strictly ascending ---\n");
    SolitaireBoard b;
    check("an empty foundation only accepts that suit's Ace", !b.canPlaceOnFoundation({ SolitaireSuit::HEARTS, SolitaireRank::TWO }, SolitaireSuit::HEARTS));
    check("an empty foundation DOES accept that suit's Ace", b.canPlaceOnFoundation({ SolitaireSuit::HEARTS, SolitaireRank::ACE }, SolitaireSuit::HEARTS));
    check("the right rank but wrong suit is rejected", !b.canPlaceOnFoundation({ SolitaireSuit::SPADES, SolitaireRank::ACE }, SolitaireSuit::HEARTS));

    b.setFoundationForTest(SolitaireSuit::HEARTS, 5); // Hearts foundation holds A-5
    check("the next sequential rank (6) is accepted", b.canPlaceOnFoundation({ SolitaireSuit::HEARTS, SolitaireRank::SIX }, SolitaireSuit::HEARTS));
    check("skipping ahead (7, over 6) is rejected", !b.canPlaceOnFoundation({ SolitaireSuit::HEARTS, SolitaireRank::SEVEN }, SolitaireSuit::HEARTS));
    check("a rank already on the pile (5 again) is rejected", !b.canPlaceOnFoundation({ SolitaireSuit::HEARTS, SolitaireRank::FIVE }, SolitaireSuit::HEARTS));
    printf("\n");
}

static void selectDeselectAndMoveChecks() {
    printf("--- Hand-built scenario: select a waste card, move it onto a legal tableau column ---\n");
    SolitaireBoard b;
    uint8_t wasteIds[1] = { idOfCard(SolitaireSuit::CLUBS, SolitaireRank::SIX) };
    b.setWasteForTest(wasteIds, 1);
    uint8_t fd[1]; uint8_t fu[1] = { idOfCard(SolitaireSuit::DIAMONDS, SolitaireRank::SEVEN) };
    b.setColumnForTest(0, fd, 0, fu, 1);

    b.tapWaste();
    check("waste's top card is now selected", b.selectionSource() == SolitaireSelectionSource::WASTE);
    b.tapWaste();
    check("tapping the same waste card again deselects it", b.selectionSource() == SolitaireSelectionSource::NONE);

    b.tapWaste();
    b.tapTableau(0);
    check("the move succeeded -- waste is now empty", b.wasteCount() == 0);
    check("column 0 gained the card on top", b.columnFaceUpCount(0) == 2);
    check("selection cleared after a successful move", b.selectionSource() == SolitaireSelectionSource::NONE);
    check("the moved card (6 of clubs) really is column 0's new top card",
          b.columnFaceUpCardAt(0, 1).suit == SolitaireSuit::CLUBS && b.columnFaceUpCardAt(0, 1).rank == SolitaireRank::SIX);
    // cardsConserved() checks a FULL 52-card deal -- not meaningful here,
    // since this hand-built scenario deliberately places only these 2
    // cards on the board at all; dealChecks()/monkeyTest() below are what
    // actually exercise that invariant, against a real full deal.
    printf("\n");
}

static void illegalMoveRejectedChecks() {
    printf("--- Hand-built scenario: an illegal tableau move is rejected and re-targets the selection instead ---\n");
    SolitaireBoard b;
    uint8_t fd[1];
    uint8_t fu0[1] = { idOfCard(SolitaireSuit::CLUBS, SolitaireRank::TWO) }; // won't fit column 1 at all
    uint8_t fu1[1] = { idOfCard(SolitaireSuit::HEARTS, SolitaireRank::NINE) };
    b.setColumnForTest(0, fd, 0, fu0, 1);
    b.setColumnForTest(1, fd, 0, fu1, 1);

    b.tapTableau(0); // select column 0's 2 of clubs
    check("column 0 selected", b.selectionSource() == SolitaireSelectionSource::TABLEAU && b.selectionColumn() == 0);
    b.tapTableau(1); // illegal destination -- re-targets to column 1 instead of moving
    check("selection re-targeted to column 1 rather than performing an illegal move", b.selectionColumn() == 1);
    check("column 0 is untouched (no illegal move went through)", b.columnFaceUpCount(0) == 1);
    printf("\n");
}

static void autoFlipChecks() {
    printf("--- Hand-built scenario: emptying a column's face-up card auto-flips the one beneath ---\n");
    SolitaireBoard b;
    uint8_t fd[1] = { idOfCard(SolitaireSuit::SPADES, SolitaireRank::FOUR) };
    uint8_t fu[1] = { idOfCard(SolitaireSuit::HEARTS, SolitaireRank::TEN) };
    b.setColumnForTest(0, fd, 1, fu, 1);
    uint8_t fu1[1] = { idOfCard(SolitaireSuit::CLUBS, SolitaireRank::JACK) };
    b.setColumnForTest(1, nullptr, 0, fu1, 1); // black Jack -- legal destination for the red 10

    b.tapTableau(0);
    b.tapTableau(1);
    check("column 0's face-down card auto-flipped face-up", b.columnFaceUpCount(0) == 1 && b.columnFaceDownCount(0) == 0);
    check("the newly-flipped card is the 4 of spades", b.columnFaceUpCardAt(0, 0).suit == SolitaireSuit::SPADES && b.columnFaceUpCardAt(0, 0).rank == SolitaireRank::FOUR);
    printf("\n");
}

static void winDetectionChecks() {
    printf("--- Hand-built scenario: playing the last four Kings wins the deal ---\n");
    SolitaireBoard b;
    SolitaireSuit suits[4] = { SolitaireSuit::CLUBS, SolitaireSuit::DIAMONDS, SolitaireSuit::HEARTS, SolitaireSuit::SPADES };
    for (uint8_t s = 0; s < 4; s++) {
        b.setFoundationForTest(suits[s], 12); // every foundation holds A-Q already
        uint8_t fu[1] = { idOfCard(suits[s], SolitaireRank::KING) };
        b.setColumnForTest(s, nullptr, 0, fu, 1);
    }

    for (uint8_t s = 0; s < 4; s++) {
        b.tapTableau(s);
        b.tapFoundation(suits[s]);
        if (s < 3) check("not won yet -- only some Kings placed", !b.isWon());
    }
    check("the deal is now won (all 4 foundations complete)", b.isWon());
    check("gamesWonThisSession() incremented by exactly 1", b.gamesWonThisSession() == 1);
    for (uint8_t s = 0; s < 4; s++) check("every foundation now holds all 13 ranks", b.foundationCount(suits[s]) == 13);

    // Regression: undo() must refuse to undo the winning move itself --
    // without this guard, undoing it (restoring won=false) and replaying
    // the identical move would re-trigger the "just won" branch and
    // double-count the same solved hand (a real bug found via adversarial
    // review of this exact scenario).
    check("undo() is refused once the deal is won", b.canUndo()); // the winning move itself still left an undo entry...
    b.undo();
    check("...but undo() left the win intact rather than rolling it back", b.isWon());
    check("gamesWonThisSession() is still exactly 1 (not rolled back, and not re-earnable)", b.gamesWonThisSession() == 1);
    printf("\n");
}

static void stockDrawAndRecycleChecks() {
    printf("--- Hand-built scenario: drawing from the stock, then recycling once it's empty ---\n");
    SolitaireBoard b;
    uint8_t stockIds[3] = {
        idOfCard(SolitaireSuit::CLUBS, SolitaireRank::TWO),
        idOfCard(SolitaireSuit::CLUBS, SolitaireRank::THREE),
        idOfCard(SolitaireSuit::CLUBS, SolitaireRank::FOUR), // this is the "top" (drawn first) -- see stockIdForTest()'s own ordering
    };
    b.setStockForTest(stockIds, 3);

    b.tapStock();
    check("one card moved from stock to waste", b.stockCount() == 2 && b.wasteCount() == 1);
    check("the drawn card is the stock's own top (4 of clubs)", b.wasteTopCard().rank == SolitaireRank::FOUR);
    b.tapStock();
    b.tapStock();
    check("stock is now empty, all 3 drawn to waste", b.stockCount() == 0 && b.wasteCount() == 3);

    b.tapStock(); // recycle
    check("recycle moved every waste card back to the stock", b.stockCount() == 3 && b.wasteCount() == 0);
    check("recycling reproduces the exact same draw order (top is 4 of clubs again)",
          solitaireCardById(b.stockIdForTest(b.stockCount() - 1)).rank == SolitaireRank::FOUR);
    printf("\n");
}

static void undoChecks() {
    printf("--- Hand-built scenario: undo restores the exact pre-move state ---\n");
    SolitaireBoard b;
    uint8_t stockIds[1] = { idOfCard(SolitaireSuit::SPADES, SolitaireRank::NINE) };
    b.setStockForTest(stockIds, 1);
    check("nothing to undo yet", !b.canUndo());

    b.tapStock();
    check("the draw happened", b.stockCount() == 0 && b.wasteCount() == 1);
    check("undo is now available", b.canUndo());
    b.undo();
    check("undo restored the stock/waste to before the draw", b.stockCount() == 1 && b.wasteCount() == 0);
    check("undo consumed its own history entry", !b.canUndo());

    // A tableau move round-trips through undo just as cleanly.
    uint8_t wasteIds[1] = { idOfCard(SolitaireSuit::CLUBS, SolitaireRank::SIX) };
    b.setWasteForTest(wasteIds, 1);
    uint8_t fd[1]; uint8_t fu[1] = { idOfCard(SolitaireSuit::DIAMONDS, SolitaireRank::SEVEN) };
    b.setColumnForTest(0, fd, 0, fu, 1);
    b.tapWaste();
    b.tapTableau(0);
    check("the move happened", b.wasteCount() == 0 && b.columnFaceUpCount(0) == 2);
    b.undo();
    check("undo restored the waste and the column", b.wasteCount() == 1 && b.columnFaceUpCount(0) == 1);
    printf("\n");
}

static void autoCompleteChecks() {
    printf("--- Hand-built scenario: auto-complete solves a fully-exposed, nearly-finished hand on its own ---\n");
    SolitaireBoard b;
    // Every foundation already holds A-Q; the only 4 cards left anywhere
    // are the 4 Kings, one exposed per column, no stock and no face-down
    // cards left -- autoCompleteAvailable() is true by construction, and
    // the straightforward foundation-first heuristic alone (findFoundationMove())
    // is enough to walk this all the way to a real win, mirroring
    // winDetectionChecks()'s own manual-tap version of the exact same
    // position but driven by auto-complete instead.
    SolitaireSuit suits[4] = { SolitaireSuit::CLUBS, SolitaireSuit::DIAMONDS, SolitaireSuit::HEARTS, SolitaireSuit::SPADES };
    uint8_t fdEmpty[1];
    for (uint8_t s = 0; s < 4; s++) {
        b.setFoundationForTest(suits[s], 12);
        uint8_t fu[1] = { idOfCard(suits[s], SolitaireRank::KING) };
        b.setColumnForTest(s, fdEmpty, 0, fu, 1);
    }
    for (uint8_t c = 4; c < SOLITAIRE_COLUMN_COUNT; c++) b.setColumnForTest(c, fdEmpty, 0, fdEmpty, 0);
    b.setStockForTest(nullptr, 0);

    check("autoCompleteAvailable() is true (no stock, no face-down cards anywhere)", b.autoCompleteAvailable());
    b.startAutoComplete();
    check("isAutoCompleting() is now true", b.isAutoCompleting());

    int guard = 0;
    while (b.isAutoCompleting() && guard++ < 20) b.autoCompleteStep();
    check("auto-complete finished within a small number of steps", guard < 20);
    check("the hand is won", b.isWon());
    check("isAutoCompleting() turned itself back off", !b.isAutoCompleting());
    printf("\n");
}

static void autoCompleteUnblockingMoveChecks() {
    printf("--- Hand-built scenario: auto-complete's 'unblocking' move relocates a blocker to expose a foundation-ready card ---\n");
    SolitaireBoard b;
    // Column 0's top card (5 of clubs) has nowhere to go on any foundation
    // yet, but the card it's sitting on (6 of hearts) IS foundation-ready
    // right now -- and column 1 offers a legal tableau destination for the
    // 5 of clubs (the 6 of diamonds, opposite color, one rank up). No
    // foundation-ready card is exposed anywhere on its own top, so
    // findFoundationMove() alone can't make progress; only the
    // "unblocking" branch can.
    uint8_t fdEmpty[1];
    uint8_t fu0[2] = { idOfCard(SolitaireSuit::HEARTS, SolitaireRank::SIX), idOfCard(SolitaireSuit::CLUBS, SolitaireRank::FIVE) };
    uint8_t fu1[1] = { idOfCard(SolitaireSuit::DIAMONDS, SolitaireRank::SIX) };
    b.setColumnForTest(0, fdEmpty, 0, fu0, 2);
    b.setColumnForTest(1, fdEmpty, 0, fu1, 1);
    for (uint8_t c = 2; c < SOLITAIRE_COLUMN_COUNT; c++) b.setColumnForTest(c, fdEmpty, 0, fdEmpty, 0);
    b.setFoundationForTest(SolitaireSuit::HEARTS, 5); // Hearts foundation holds A-5, so 6 is next
    b.setStockForTest(nullptr, 0);

    check("autoCompleteAvailable() is true", b.autoCompleteAvailable());
    b.startAutoComplete();
    b.autoCompleteStep();

    check("the blocking 5 of clubs relocated onto the 6 of diamonds", b.columnFaceUpCount(1) == 2);
    check("column 0's 6 of hearts is now exposed on top",
          b.columnFaceUpCount(0) == 1 && b.columnFaceUpCardAt(0, 0).suit == SolitaireSuit::HEARTS &&
          b.columnFaceUpCardAt(0, 0).rank == SolitaireRank::SIX);
    printf("\n");
}

// ---------------------------------------------------------------------------
// 3) Monkey test -- many random legal-or-illegal taps, checking the one hard
//    invariant that must never break (see cardsConserved()'s own comment).
// ---------------------------------------------------------------------------
static long monkeyFailures = 0;

static void monkeyTest(int numGames, int stepsPerGame) {
    printf("=== Monkey test (%d games, %d random taps each) ===\n", numGames, stepsPerGame);
    long totalSteps = 0, gamesSolved = 0;
    for (int g = 0; g < numGames; g++) {
        SolitaireBoard b;
        b.reset();
        for (int step = 0; step < stepsPerGame; step++) {
            if (!cardsConserved(b)) { monkeyFailures++; break; }
            switch (rand() % 5) {
                case 0: b.tapStock(); break;
                case 1: b.tapWaste(); break;
                case 2: b.tapTableau((uint8_t)(rand() % SOLITAIRE_COLUMN_COUNT)); break;
                case 3: b.tapTableau((uint8_t)(rand() % SOLITAIRE_COLUMN_COUNT)); break; // weighted toward tableau taps -- the richest source of both selects and moves
                case 4: b.tapFoundation((SolitaireSuit)(rand() % SOLITAIRE_SUIT_COUNT)); break;
            }
            totalSteps++;
            if (b.isWon()) { gamesSolved++; break; }
        }
        if (!cardsConserved(b)) monkeyFailures++;
    }
    printf("Games: %d, total taps: %ld, solved by pure random taps: %ld\n", numGames, totalSteps, gamesSolved);
    printf("  Card-conservation failures (should be 0): %ld\n", monkeyFailures);
    printf("  Note: solving a deal via uniformly random taps is rare by design (real strategy is\n");
    printf("  required) -- a low (or zero) solved count here is expected, not a failure; what matters\n");
    printf("  is that the conservation invariant above never breaks no matter how the taps land.\n\n");
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
    printf("=== Touch hit-testing checks (SolitaireDisplay.cpp, against Config.h's real screen size) ===\n");
    SolitaireLayout l = computeSolitaireLayout();

    printf("  layout: card %dx%d, tableau y=%d h=%d, colStride=%d\n", l.cardW, l.cardH, l.tableauY, l.tableauH, l.colStride);

    checkLayout("stock and waste piles fit on-screen and don't overlap",
                l.stockX >= 0 && l.stockX + l.cardW <= l.wasteX &&
                l.wasteX + l.cardW <= SCREEN_WIDTH && l.stockY >= 0 && l.stockY + l.cardH <= SCREEN_HEIGHT);
    checkLayout("all 4 foundation piles fit on-screen without overlapping each other",
                l.foundationX[0] + l.cardW <= l.foundationX[1] && l.foundationX[1] + l.cardW <= l.foundationX[2] &&
                l.foundationX[2] + l.cardW <= l.foundationX[3] && l.foundationX[3] + l.cardW <= SCREEN_WIDTH);
    checkLayout("waste and the first foundation pile don't overlap", l.wasteX + l.cardW <= l.foundationX[0]);
    checkLayout("tableau fits within the screen height", l.tableauY >= 0 && l.tableauY + l.cardH <= SCREEN_HEIGHT);
    checkLayout("all 7 tableau columns fit on-screen without overlapping", l.colX[6] + l.cardW <= SCREEN_WIDTH);
    checkLayout("New/Undo/Auto buttons fit on-screen without overlapping each other",
                l.newX + l.newW <= l.undoX && l.undoX + l.undoW <= l.autoX && l.autoX + l.autoW <= SCREEN_WIDTH);

    int16_t scx = l.stockX + l.cardW / 2, scy = l.stockY + l.cardH / 2;
    checkLayout("stock's own center is a hit", hitTestSolitaireStock(l, scx, scy));
    checkLayout("waste's own center is NOT a stock hit", !hitTestSolitaireStock(l, l.wasteX + l.cardW / 2, l.wasteY + l.cardH / 2));

    SolitaireSuit hitSuit;
    checkLayout("each foundation pile's own center resolves to its own suit",
                hitTestSolitaireFoundation(l, l.foundationX[0] + l.cardW / 2, l.foundationY + l.cardH / 2, hitSuit) && hitSuit == SolitaireSuit::CLUBS &&
                hitTestSolitaireFoundation(l, l.foundationX[3] + l.cardW / 2, l.foundationY + l.cardH / 2, hitSuit) && hitSuit == SolitaireSuit::SPADES);

    checkLayout("home button's own center is a hit", hitTestSolitaireHomeButton(l, 15, l.statusY + l.statusH / 2));
    checkLayout("New button's own center is a hit", hitTestSolitaireNewButton(l, l.newX + l.newW / 2, l.newY + l.newH / 2));
    checkLayout("Undo button's own center is a hit", hitTestSolitaireUndoButton(l, l.undoX + l.undoW / 2, l.undoY + l.undoH / 2));
    checkLayout("Auto button's own center is a hit", hitTestSolitaireAutoButton(l, l.autoX + l.autoW / 2, l.autoY + l.autoH / 2));
    checkLayout("New and Undo buttons don't hit-test as each other",
                !hitTestSolitaireUndoButton(l, l.newX + l.newW / 2, l.newY + l.newH / 2));

    // Tableau hit-testing against a real dealt board -- every column's own
    // full cascade resolves to that column, and nothing below its own
    // cascade height (whatever solitaireFanOffset() computed) does.
    SolitaireBoard b;
    b.reset();
    bool everyColumnResolves = true;
    for (uint8_t c = 0; c < SOLITAIRE_COLUMN_COUNT; c++) {
        uint8_t total = (uint8_t)(b.columnFaceDownCount(c) + b.columnFaceUpCount(c));
        int16_t offset = solitaireFanOffset(l, total);
        int16_t topCardY = l.tableauY + (int16_t)(total - 1) * offset;
        uint8_t hitCol;
        if (!hitTestSolitaireTableau(l, b, l.colX[c] + l.cardW / 2, topCardY + l.cardH / 2, hitCol) || hitCol != c) {
            everyColumnResolves = false;
        }
    }
    checkLayout("every dealt column's own topmost card resolves back to that column", everyColumnResolves);

    uint8_t discard;
    checkLayout("a tap well above the tableau is not a hit", !hitTestSolitaireTableau(l, b, l.colX[0] + 5, l.tableauY - 5, discard));
    checkLayout("a tap past the last column's right edge is not a hit", !hitTestSolitaireTableau(l, b, SCREEN_WIDTH + 5, l.tableauY + 5, discard));

    printf("Layout checks run: %d, failed: %d\n\n", layoutChecksRun, layoutChecksFailed);
}

// ---------------------------------------------------------------------------
// 5) Rendering smoke test.
// ---------------------------------------------------------------------------
static void renderingSmokeTest() {
    printf("=== Rendering smoke test (stubbed TFT_eSPI -- confirms no crash, NOT actual pixels) ===\n");
    TFT_eSPI tft;
    SolitaireLayout l = computeSolitaireLayout();
    SolitaireBoard b;
    b.reset();
    drawSolitaireStaticChrome(tft, l);
    drawSolitaireStatus(tft, l, "Your turn", b.gamesWonThisSession());
    drawSolitaireButtons(tft, l, b.autoCompleteAvailable());
    drawSolitaireStock(tft, l, b.stockCount());
    drawSolitaireTableauAndWaste(tft, l, b);
    for (uint8_t s = 0; s < SOLITAIRE_SUIT_COUNT; s++) drawSolitaireFoundation(tft, l, b, (SolitaireSuit)s);
    b.tapStock();
    drawSolitaireStock(tft, l, b.stockCount());
    drawSolitaireWaste(tft, l, b);
    printf("  completed with no crash\n\n");
}

int main() {
    srand(12345); // fixed seed -- reproducible test runs
    seedSolitaireRandom(12345);

    renderingSmokeTest();

    printf("=== Rule-correctness checks (hand-built positions) ===\n");
    dealChecks();
    tableauLegalityChecks();
    foundationLegalityChecks();
    selectDeselectAndMoveChecks();
    illegalMoveRejectedChecks();
    autoFlipChecks();
    winDetectionChecks();
    stockDrawAndRecycleChecks();
    undoChecks();
    autoCompleteChecks();
    autoCompleteUnblockingMoveChecks();
    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);

    monkeyTest(500, 400);
    touchHitTestChecks();

    bool allGood = (checksFailed == 0) && (monkeyFailures == 0) && (layoutChecksFailed == 0);
    printf("=== OVERALL: %s ===\n", allGood ? "ALL PLAYTEST CHECKS PASSED" : "FAILURES FOUND -- SEE ABOVE");
    return allGood ? 0 : 1;
}
