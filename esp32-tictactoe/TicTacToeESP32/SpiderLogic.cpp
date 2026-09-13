#include "SpiderLogic.h"
#include <cstdio>
#include <cstdarg>

// ---------------------------------------------------------------------------
// Shuffle RNG -- a small self-contained xorshift32, deliberately NOT
// Arduino's own random()/randomSeed() so this file compiles unchanged
// against native_test's no-op Arduino.h stub, exactly every other game's
// own seed*Random()'s identical reasoning.
// ---------------------------------------------------------------------------
static uint32_t g_spiderRngState = 0x9E3779B1u;

void seedSpiderRandom(uint32_t seed) {
    g_spiderRngState = seed ? seed : 0x9E3779B1u; // xorshift32 must never be seeded with 0
}

static uint32_t spiderNextRandom() {
    uint32_t x = g_spiderRngState;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    g_spiderRngState = x;
    return x;
}

static void shuffleArray(uint8_t *arr, uint8_t n) {
    for (uint8_t i = n; i > 1; i--) {
        uint8_t j = (uint8_t)(spiderNextRandom() % i);
        uint8_t tmp = arr[i - 1];
        arr[i - 1] = arr[j];
        arr[j] = tmp;
    }
}

// Two identical 52-card decks back to back -- see SpiderLogic.h's own
// declaration for why which "half" an id falls in has no game meaning.
SpiderCardView spiderCardById(uint8_t id) {
    uint8_t base = id % 52;
    SpiderSuit suit = (SpiderSuit)(base / SPIDER_RANK_COUNT);
    SpiderRank rank = (SpiderRank)((base % SPIDER_RANK_COUNT) + 1);
    return { suit, rank };
}

// ---------------------------------------------------------------------------
// SpiderBoard
// ---------------------------------------------------------------------------
void SpiderBoard::setLastAction(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    vsnprintf(lastActionText, LAST_ACTION_BUF_SIZE, fmt, args);
    va_end(args);
}

void SpiderBoard::dealFrom(const uint8_t order[SPIDER_DECK_SIZE]) {
    uint8_t cursor = 0;
    for (uint8_t col = 0; col < SPIDER_COLUMN_COUNT; col++) {
        // Columns 0-3 get 6 cards (5 face-down, 1 face-up); columns 4-9
        // get 5 cards (4 face-down, 1 face-up) -- 24 + 30 = 54 dealt, 50
        // left for the stock.
        uint8_t total = (col < 4) ? 6 : 5;
        Column &c = columns[col];
        c.count = 0;
        for (uint8_t i = 0; i < total; i++) c.cards[c.count++] = order[cursor++];
        c.faceDownCount = (uint8_t)(total - 1);
    }

    stockCount_ = 0;
    while (cursor < SPIDER_DECK_SIZE) stock[stockCount_++] = order[cursor++];

    completedSequences_ = 0;
    clearSelection();
    undoCount = 0;
    setLastAction("New deal");
}

void SpiderBoard::reset() {
    uint8_t order[SPIDER_DECK_SIZE];
    for (uint8_t i = 0; i < SPIDER_DECK_SIZE; i++) order[i] = i;
    shuffleArray(order, SPIDER_DECK_SIZE);
    dealFrom(order);
}

void SpiderBoard::resetSession() {
    gamesWon = 0;
    reset();
}

void SpiderBoard::dealFromOrderForTest(const uint8_t order[SPIDER_DECK_SIZE]) {
    dealFrom(order);
}

bool SpiderBoard::canDealFromStock() const {
    if (stockCount_ == 0) return false;
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) {
        if (columns[c].count == 0) return false;
    }
    return true;
}

bool SpiderBoard::canPlaceOn(SpiderCardView card, uint8_t destCol) const {
    const Column &c = columns[destCol];
    if (c.count == 0) return true; // any card is accepted on an empty column
    SpiderCardView top = spiderCardById(c.cards[c.count - 1]);
    return (uint8_t)card.rank == (uint8_t)top.rank - 1; // exactly one rank higher on the destination, any suit
}

void SpiderBoard::checkForCompletedSequence(uint8_t col) {
    Column &c = columns[col];
    if (c.count < SPIDER_SEQUENCE_LENGTH) return;
    uint8_t start = (uint8_t)(c.count - SPIDER_SEQUENCE_LENGTH);
    if (start < c.faceDownCount) return; // the run would dip into face-down cards -- not really all exposed

    SpiderCardView first = spiderCardById(c.cards[start]);
    if (first.rank != SpiderRank::KING) return; // a complete run always starts at the King
    SpiderSuit suit = first.suit;
    for (uint8_t i = 1; i < SPIDER_SEQUENCE_LENGTH; i++) {
        SpiderCardView card = spiderCardById(c.cards[start + i]);
        if (card.suit != suit) return;
        if ((uint8_t)card.rank != (uint8_t)first.rank - i) return; // must descend by exactly 1 each step
    }

    // A real, complete King-to-Ace same-suit run -- sweep it off.
    c.count = start;
    completedSequences_++;
    if (c.count == c.faceDownCount && c.faceDownCount > 0) c.faceDownCount--; // auto-flip the newly-exposed top card
    if (isWon()) gamesWon++;
}

void SpiderBoard::tapStock() {
    if (isWon()) return;
    if (stockCount_ == 0) {
        setLastAction("Stock is empty");
        return;
    }
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) {
        if (columns[c].count == 0) {
            setLastAction("Fill empty columns first");
            return;
        }
    }

    pushUndo();
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) {
        columns[c].cards[columns[c].count++] = stock[--stockCount_];
    }
    clearSelection();
    setLastAction("Dealt a new row");
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) checkForCompletedSequence(c);
}

void SpiderBoard::tapColumn(uint8_t col) {
    if (isWon()) return;

    if (selColumn == (int8_t)col) {
        clearSelection();
        setLastAction("Deselected");
        return;
    }

    bool haveSelection = (selColumn >= 0);
    if (haveSelection) {
        Column &src = columns[(uint8_t)selColumn];
        if (src.count > 0) {
            SpiderCardView moving = spiderCardById(src.cards[src.count - 1]);
            if (canPlaceOn(moving, col)) {
                pushUndo();
                Column &dest = columns[col];
                dest.cards[dest.count++] = src.cards[--src.count];
                if (src.count == src.faceDownCount && src.faceDownCount > 0) src.faceDownCount--; // auto-flip the source's new top card
                clearSelection();
                setLastAction("Moved to column %u", (unsigned)(col + 1));
                checkForCompletedSequence(col);
                return;
            }
        }
    }

    if (columns[col].count > columns[col].faceDownCount) {
        selColumn = (int8_t)col;
        setLastAction("Selected card");
    } else {
        setLastAction(haveSelection ? "Can't place there" : "Empty column");
    }
}

void SpiderBoard::pushUndo() {
    if (undoCount == SPIDER_MAX_UNDO) {
        // Ring full -- drop the oldest by shifting everything down one
        // slot, same fixed-capacity-history idiom SolitaireLogic.cpp's own
        // pushUndo() uses.
        for (uint8_t i = 0; i + 1 < SPIDER_MAX_UNDO; i++) undoStack[i] = undoStack[i + 1];
        undoCount--;
    }
    Snapshot &slot = undoStack[undoCount];
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) slot.columns[c] = columns[c];
    for (uint8_t i = 0; i < stockCount_; i++) slot.stock[i] = stock[i];
    slot.stockCount = stockCount_;
    slot.completedSequences = completedSequences_;
    undoCount++;
}

void SpiderBoard::undo() {
    // Refuses to undo a hand that's already won -- same guard, for the
    // same reason, as SolitaireBoard::undo(): undoing the winning sequence
    // sweep and then replaying it would re-trigger the "just won" branch
    // and double-count gamesWonThisSession() for a hand solved only once
    // (a real bug found via adversarial review of Klondike's own port,
    // fixed there and built in here from the start instead of waiting to
    // rediscover it).
    if (isWon()) return;
    if (undoCount == 0) return;
    undoCount--;
    Snapshot &slot = undoStack[undoCount];
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) columns[c] = slot.columns[c];
    for (uint8_t i = 0; i < slot.stockCount; i++) stock[i] = slot.stock[i];
    stockCount_ = slot.stockCount;
    completedSequences_ = slot.completedSequences;
    clearSelection();
    setLastAction("Undid last move");
}

void SpiderBoard::setColumnForTest(uint8_t col, const uint8_t cardIds[], uint8_t count, uint8_t faceDownCount) {
    Column &c = columns[col];
    for (uint8_t i = 0; i < count; i++) c.cards[i] = cardIds[i];
    c.count = count;
    c.faceDownCount = faceDownCount;
}

void SpiderBoard::setStockForTest(const uint8_t ids[], uint8_t n) {
    for (uint8_t i = 0; i < n; i++) stock[i] = ids[i];
    stockCount_ = n;
}

void SpiderBoard::setSelectionForTest(int8_t col) {
    selColumn = col;
}
