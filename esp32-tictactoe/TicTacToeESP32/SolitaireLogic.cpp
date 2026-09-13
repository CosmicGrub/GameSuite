#include "SolitaireLogic.h"
#include <cstdio>
#include <cstdarg>

// ---------------------------------------------------------------------------
// Shuffle RNG -- a small self-contained xorshift32, deliberately NOT
// Arduino's own random()/randomSeed() so this file compiles unchanged
// against native_test's no-op Arduino.h stub, exactly every other game's
// own seed*Random()'s identical reasoning.
// ---------------------------------------------------------------------------
static uint32_t g_solitaireRngState = 0x27D4EB2Fu;

void seedSolitaireRandom(uint32_t seed) {
    g_solitaireRngState = seed ? seed : 0x27D4EB2Fu; // xorshift32 must never be seeded with 0
}

static uint32_t solitaireNextRandom() {
    uint32_t x = g_solitaireRngState;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    g_solitaireRngState = x;
    return x;
}

static void shuffleArray(uint8_t *arr, uint8_t n) {
    for (uint8_t i = n; i > 1; i--) {
        uint8_t j = (uint8_t)(solitaireNextRandom() % i);
        uint8_t tmp = arr[i - 1];
        arr[i - 1] = arr[j];
        arr[j] = tmp;
    }
}

// id = suit*13 + (rank-1) -- see SolitaireLogic.h's own declaration.
SolitaireCard solitaireCardById(uint8_t id) {
    SolitaireSuit suit = (SolitaireSuit)(id / SOLITAIRE_RANK_COUNT);
    SolitaireRank rank = (SolitaireRank)((id % SOLITAIRE_RANK_COUNT) + 1);
    return { suit, rank };
}

static uint8_t idOf(SolitaireCard c) {
    return (uint8_t)c.suit * SOLITAIRE_RANK_COUNT + ((uint8_t)c.rank - 1);
}

// ---------------------------------------------------------------------------
// SolitaireBoard
// ---------------------------------------------------------------------------
void SolitaireBoard::setLastAction(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    vsnprintf(lastActionText, LAST_ACTION_BUF_SIZE, fmt, args);
    va_end(args);
}

void SolitaireBoard::dealFrom(const uint8_t order[SOLITAIRE_DECK_SIZE]) {
    for (uint8_t c = 0; c < SOLITAIRE_COLUMN_COUNT; c++) {
        columns[c].faceDownCount = 0;
        columns[c].faceUpCount = 0;
    }

    // Column N (1-indexed here as `size`) gets N cards: N-1 face-down, then
    // 1 face-up on top -- 1+2+...+7=28 dealt, 24 left for the stock. See
    // SolitaireLogic.h's own SOLITAIRE_MAX_COLUMN_FACEDOWN/FACEUP comment
    // for why this exact shape is also what bounds a column's own max size.
    uint8_t cursor = 0;
    for (uint8_t size = 1; size <= SOLITAIRE_COLUMN_COUNT; size++) {
        uint8_t col = size - 1;
        for (uint8_t i = 0; i + 1 < size; i++) columns[col].faceDown[columns[col].faceDownCount++] = order[cursor++];
        columns[col].faceUp[columns[col].faceUpCount++] = order[cursor++];
    }

    stockCount_ = 0;
    while (cursor < SOLITAIRE_DECK_SIZE) stock[stockCount_++] = order[cursor++];
    wasteCount_ = 0;
    for (uint8_t s = 0; s < SOLITAIRE_SUIT_COUNT; s++) foundations[s] = 0;

    clearSelection();
    score_ = 0;
    won = false;
    autoCompleting = false;
    undoCount = 0;
    setLastAction("New deal");
}

void SolitaireBoard::reset() {
    uint8_t order[SOLITAIRE_DECK_SIZE];
    for (uint8_t i = 0; i < SOLITAIRE_DECK_SIZE; i++) order[i] = i;
    shuffleArray(order, SOLITAIRE_DECK_SIZE);
    dealFrom(order);
}

void SolitaireBoard::resetSession() {
    gamesWon = 0;
    reset();
}

void SolitaireBoard::dealFromOrderForTest(const uint8_t order[SOLITAIRE_DECK_SIZE]) {
    dealFrom(order);
}

bool SolitaireBoard::selectedCard(SolitaireCard &out) const {
    switch (selSource) {
        case SolitaireSelectionSource::WASTE:
            if (wasteCount_ == 0) return false;
            out = wasteTopCard();
            return true;
        case SolitaireSelectionSource::TABLEAU: {
            const Column &c = columns[selColumn];
            if (c.faceUpCount == 0) return false;
            out = solitaireCardById(c.faceUp[c.faceUpCount - 1]);
            return true;
        }
        default:
            return false;
    }
}

bool SolitaireBoard::canPlaceOnTableau(SolitaireCard card, uint8_t destCol) const {
    const Column &c = columns[destCol];
    if (c.faceUpCount == 0) return card.rank == SolitaireRank::KING;
    SolitaireCard top = solitaireCardById(c.faceUp[c.faceUpCount - 1]);
    return (uint8_t)card.rank == (uint8_t)top.rank - 1 && card.isRed() != top.isRed();
}

bool SolitaireBoard::canPlaceOnFoundation(SolitaireCard card, SolitaireSuit pile) const {
    if (card.suit != pile) return false;
    uint8_t count = foundations[(uint8_t)pile];
    if (count == 0) return card.rank == SolitaireRank::ACE;
    return (uint8_t)card.rank == count + 1;
}

void SolitaireBoard::pushUndo() {
    if (undoCount == SOLITAIRE_MAX_UNDO) {
        // Ring full -- drop the oldest by shifting everything down one slot,
        // same "oldest dropped once MAX_UNDO is exceeded" behavior the
        // Kotlin source's own bounded ArrayDeque gives for free; MAX_UNDO
        // is small (5) so this shift is cheap.
        for (uint8_t i = 0; i + 1 < SOLITAIRE_MAX_UNDO; i++) undoStack[i] = undoStack[i + 1];
        undoCount--;
    }
    Snapshot &slot = undoStack[undoCount];
    for (uint8_t c = 0; c < SOLITAIRE_COLUMN_COUNT; c++) slot.columns[c] = columns[c];
    for (uint8_t i = 0; i < stockCount_; i++) slot.stock[i] = stock[i];
    slot.stockCount = stockCount_;
    for (uint8_t i = 0; i < wasteCount_; i++) slot.waste[i] = waste[i];
    slot.wasteCount = wasteCount_;
    for (uint8_t s = 0; s < SOLITAIRE_SUIT_COUNT; s++) slot.foundations[s] = foundations[s];
    slot.score = score_;
    slot.won = won;
    undoCount++;
}

void SolitaireBoard::undo() {
    if (autoCompleting) return;
    // Refusing to undo a deal that's already won is what keeps
    // gamesWonThisSession() honest: that counter deliberately isn't part
    // of a Snapshot (see this file's own header comment -- undoing into an
    // EARLIER deal should never erase that earlier deal's own tally), but
    // without this guard, undoing the winning move itself (restoring
    // won=false) and then replaying it would re-trigger
    // applyMoveToFoundation()'s "just became won" branch and double-count
    // the exact same solved hand. Once won, the only sensible next action
    // is a fresh deal (New), not undoing a completed one.
    if (won) return;
    if (undoCount == 0) return;
    undoCount--;
    Snapshot &slot = undoStack[undoCount];
    for (uint8_t c = 0; c < SOLITAIRE_COLUMN_COUNT; c++) columns[c] = slot.columns[c];
    for (uint8_t i = 0; i < slot.stockCount; i++) stock[i] = slot.stock[i];
    stockCount_ = slot.stockCount;
    for (uint8_t i = 0; i < slot.wasteCount; i++) waste[i] = slot.waste[i];
    wasteCount_ = slot.wasteCount;
    for (uint8_t s = 0; s < SOLITAIRE_SUIT_COUNT; s++) foundations[s] = slot.foundations[s];
    score_ = slot.score;
    won = slot.won;
    clearSelection();
    setLastAction("Undid last move");
}

void SolitaireBoard::removeSelected() {
    if (selSource == SolitaireSelectionSource::WASTE) {
        wasteCount_--;
    } else if (selSource == SolitaireSelectionSource::TABLEAU) {
        Column &c = columns[selColumn];
        c.faceUpCount--;
        if (c.faceUpCount == 0 && c.faceDownCount > 0) {
            // Auto-flip: the invariant this whole file relies on is that
            // faceUp is only ever empty when faceDown is too.
            c.faceUp[0] = c.faceDown[c.faceDownCount - 1];
            c.faceUpCount = 1;
            c.faceDownCount--;
        }
    }
}

void SolitaireBoard::applyMoveToTableau(uint8_t destCol, SolitaireCard card) {
    removeSelected();
    Column &dest = columns[destCol];
    dest.faceUp[dest.faceUpCount++] = idOf(card);
    clearSelection();
    setLastAction("Moved to column %u", (unsigned)(destCol + 1));
}

void SolitaireBoard::applyMoveToFoundation(SolitaireSuit pile, SolitaireCard card) {
    removeSelected();
    foundations[(uint8_t)pile]++;
    score_ += 10;
    clearSelection();

    bool allDone = true;
    for (uint8_t s = 0; s < SOLITAIRE_SUIT_COUNT; s++) {
        if (foundations[s] != SOLITAIRE_RANK_COUNT) { allDone = false; break; }
    }
    won = allDone;
    if (won) {
        gamesWon++;
        autoCompleting = false;
    }
    setLastAction("Moved to foundation");
}

void SolitaireBoard::tapStock() {
    if (won || autoCompleting) return;
    if (stockCount_ > 0) {
        pushUndo();
        waste[wasteCount_++] = stock[--stockCount_];
        clearSelection();
        setLastAction("Drew a card");
    } else if (wasteCount_ > 0) {
        pushUndo();
        // Recycle: reverse the waste back into the stock so the next pass
        // draws in the exact same order as this one did (waste is built by
        // appending each draw, so reversing it reproduces the original
        // stock order once popped from the same end again) -- the standard
        // "the deck cycles through the same sequence every pass" rule, not
        // a reshuffle.
        for (uint8_t i = 0; i < wasteCount_; i++) stock[i] = waste[wasteCount_ - 1 - i];
        stockCount_ = wasteCount_;
        wasteCount_ = 0;
        clearSelection();
        setLastAction("Recycled waste");
    } else {
        setLastAction("Stock is empty");
    }
}

void SolitaireBoard::tapWaste() {
    if (won || autoCompleting) return;
    if (selSource == SolitaireSelectionSource::WASTE) {
        clearSelection();
        setLastAction("Deselected");
        return;
    }
    if (wasteCount_ == 0) {
        setLastAction("Waste is empty");
        return;
    }
    selSource = SolitaireSelectionSource::WASTE;
    selColumn = 0;
    setLastAction("Selected card");
}

void SolitaireBoard::tapTableau(uint8_t col) {
    if (won || autoCompleting) return;

    if (selSource == SolitaireSelectionSource::TABLEAU && selColumn == col) {
        clearSelection();
        setLastAction("Deselected");
        return;
    }

    bool haveSelection = (selSource != SolitaireSelectionSource::NONE);
    if (haveSelection) {
        SolitaireCard moving;
        if (selectedCard(moving) && canPlaceOnTableau(moving, col)) {
            pushUndo();
            applyMoveToTableau(col, moving);
            return;
        }
    }

    if (columns[col].faceUpCount > 0) {
        selSource = SolitaireSelectionSource::TABLEAU;
        selColumn = col;
        setLastAction("Selected card");
    } else {
        setLastAction(haveSelection ? "Can't place there" : "Empty column");
    }
}

void SolitaireBoard::tapFoundation(SolitaireSuit pile) {
    if (won || autoCompleting) return;
    if (selSource == SolitaireSelectionSource::NONE) {
        setLastAction("Select a card first");
        return;
    }
    SolitaireCard moving;
    if (!selectedCard(moving)) {
        setLastAction("Select a card first");
        return;
    }
    if (!canPlaceOnFoundation(moving, pile)) {
        setLastAction("Can't place there");
        return;
    }
    pushUndo();
    applyMoveToFoundation(pile, moving);
}

bool SolitaireBoard::autoCompleteAvailable() const {
    if (won) return false;
    if (stockCount_ > 0) return false;
    for (uint8_t c = 0; c < SOLITAIRE_COLUMN_COUNT; c++) {
        if (columns[c].faceDownCount > 0) return false;
    }
    return true;
}

void SolitaireBoard::startAutoComplete() {
    if (!autoCompleteAvailable()) return;
    autoCompleting = true;
}

bool SolitaireBoard::findFoundationMove(SolitaireSelectionSource &outSource, uint8_t &outColumn, SolitaireSuit &outPile, SolitaireCard &outCard) const {
    if (wasteCount_ > 0) {
        SolitaireCard top = wasteTopCard();
        if (canPlaceOnFoundation(top, top.suit)) {
            outSource = SolitaireSelectionSource::WASTE;
            outColumn = 0;
            outPile = top.suit;
            outCard = top;
            return true;
        }
    }
    for (uint8_t c = 0; c < SOLITAIRE_COLUMN_COUNT; c++) {
        if (columns[c].faceUpCount == 0) continue;
        SolitaireCard top = solitaireCardById(columns[c].faceUp[columns[c].faceUpCount - 1]);
        if (canPlaceOnFoundation(top, top.suit)) {
            outSource = SolitaireSelectionSource::TABLEAU;
            outColumn = c;
            outPile = top.suit;
            outCard = top;
            return true;
        }
    }
    return false;
}

bool SolitaireBoard::findUnblockingTableauMove(uint8_t &outSrcCol, uint8_t &outDestCol) const {
    for (uint8_t src = 0; src < SOLITAIRE_COLUMN_COUNT; src++) {
        const Column &srcCol = columns[src];
        if (srcCol.faceUpCount < 2) continue; // no card exposed beneath the top one
        SolitaireCard top = solitaireCardById(srcCol.faceUp[srcCol.faceUpCount - 1]);
        SolitaireCard beneath = solitaireCardById(srcCol.faceUp[srcCol.faceUpCount - 2]);
        if (!canPlaceOnFoundation(beneath, beneath.suit)) continue;

        for (uint8_t dest = 0; dest < SOLITAIRE_COLUMN_COUNT; dest++) {
            if (dest == src) continue;
            // Deliberately ignores an empty destination column -- with no
            // lookahead to prove that's useful too, it's just as likely to
            // shuffle a King back and forth between empty columns forever
            // as it is to help (see SolitaireLogic.h's own top comment and
            // autoCompleteStep()'s KDoc on why every move this proposes
            // must provably make progress).
            if (columns[dest].faceUpCount == 0) continue;
            if (canPlaceOnTableau(top, dest)) {
                outSrcCol = src;
                outDestCol = dest;
                return true;
            }
        }
    }
    return false;
}

void SolitaireBoard::autoCompleteStep() {
    if (!autoCompleting) return;
    if (won) {
        autoCompleting = false;
        return;
    }

    SolitaireSelectionSource src;
    uint8_t col;
    SolitaireSuit pile;
    SolitaireCard card;
    if (findFoundationMove(src, col, pile, card)) {
        pushUndo();
        selSource = src;
        selColumn = col;
        applyMoveToFoundation(pile, card);
        return;
    }

    uint8_t srcCol, destCol;
    if (findUnblockingTableauMove(srcCol, destCol)) {
        SolitaireCard moving = solitaireCardById(columns[srcCol].faceUp[columns[srcCol].faceUpCount - 1]);
        pushUndo();
        selSource = SolitaireSelectionSource::TABLEAU;
        selColumn = srcCol;
        applyMoveToTableau(destCol, moving);
        return;
    }

    // Nothing this heuristic knows how to do is left -- see this function's
    // own header comment; an honest simplification, not a bug.
    autoCompleting = false;
}

void SolitaireBoard::setColumnForTest(uint8_t col, const uint8_t faceDownIds[], uint8_t faceDownN,
                                       const uint8_t faceUpIds[], uint8_t faceUpN) {
    Column &c = columns[col];
    for (uint8_t i = 0; i < faceDownN; i++) c.faceDown[i] = faceDownIds[i];
    c.faceDownCount = faceDownN;
    for (uint8_t i = 0; i < faceUpN; i++) c.faceUp[i] = faceUpIds[i];
    c.faceUpCount = faceUpN;
}

void SolitaireBoard::setStockForTest(const uint8_t ids[], uint8_t n) {
    for (uint8_t i = 0; i < n; i++) stock[i] = ids[i];
    stockCount_ = n;
}

void SolitaireBoard::setWasteForTest(const uint8_t ids[], uint8_t n) {
    for (uint8_t i = 0; i < n; i++) waste[i] = ids[i];
    wasteCount_ = n;
}

void SolitaireBoard::setFoundationForTest(SolitaireSuit suit, uint8_t count) {
    foundations[(uint8_t)suit] = count;
}

void SolitaireBoard::setSelectionForTest(SolitaireSelectionSource source, uint8_t column) {
    selSource = source;
    selColumn = column;
}
