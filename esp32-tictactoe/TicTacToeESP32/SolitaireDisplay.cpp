#include "SolitaireDisplay.h"
#include "Config.h"
#include "Theme.h"
#include <cstdio>

static const uint16_t COLOR_BG           = THEME_BG;
static const uint16_t COLOR_CARD_FILL    = THEME_SURFACE;
static const uint16_t COLOR_CARD_SEL     = THEME_SURFACE_ALT;
static const uint16_t COLOR_CARD_BACK    = THEME_SURFACE_ALT;
static const uint16_t COLOR_BORDER       = THEME_TEXT;
static const uint16_t COLOR_BORDER_DIM   = THEME_TEXT_DIM;
static const uint16_t COLOR_BLACK_SUIT   = THEME_TEXT;
static const uint16_t COLOR_RED_SUIT     = THEME_DANGER;
static const uint16_t COLOR_SELECTED_RING = THEME_HUMAN;
static const uint16_t COLOR_STATUS_BG    = THEME_SURFACE;
static const uint16_t COLOR_STATUS_TEXT  = THEME_TEXT;
static const uint16_t COLOR_BUTTON_BG    = THEME_SURFACE_ALT;
static const uint16_t COLOR_BUTTON_TEXT  = THEME_TEXT;
static const uint16_t COLOR_BUTTON_DIM   = THEME_TEXT_DIM;

static const int16_t HOME_BTN_SIZE = 30;
static const int16_t HOME_BTN_MARGIN = 2;

static int16_t homeButtonTop(const SolitaireLayout &layout) {
    return layout.statusY + (layout.statusH - HOME_BTN_SIZE) / 2;
}

SolitaireLayout computeSolitaireLayout() {
    SolitaireLayout l{};
    l.statusY = 0;
    l.statusH = 36;

    l.wonChipW = 76;
    l.wonChipH = 26;
    l.wonChipX = SCREEN_WIDTH - l.wonChipW - 6;
    l.wonChipY = l.statusY + (l.statusH - l.wonChipH) / 2;

    int16_t actionY = l.statusH;
    int16_t actionH = 30;
    l.newW = 60; l.newH = 24; l.newX = 8; l.newY = actionY + (actionH - l.newH) / 2;
    l.undoW = 60; l.undoH = 24; l.undoX = l.newX + l.newW + 8; l.undoY = l.newY;
    l.autoW = 60; l.autoH = 24; l.autoX = l.undoX + l.undoW + 8; l.autoY = l.newY;

    l.cardW = 38;
    l.cardH = 54;

    int16_t pilesY = actionY + actionH + 4;
    l.stockX = 10; l.stockY = pilesY + 4;
    l.wasteX = l.stockX + l.cardW + 10; l.wasteY = l.stockY;

    int16_t foundGap = 8;
    int16_t foundTotalW = SOLITAIRE_SUIT_COUNT * l.cardW + (SOLITAIRE_SUIT_COUNT - 1) * foundGap;
    int16_t foundStartX = SCREEN_WIDTH - foundTotalW - 10;
    for (uint8_t i = 0; i < SOLITAIRE_SUIT_COUNT; i++) l.foundationX[i] = foundStartX + i * (l.cardW + foundGap);
    l.foundationY = l.stockY;

    l.tableauY = pilesY + l.cardH + 8 + 4;
    l.tableauH = SCREEN_HEIGHT - l.tableauY;

    int16_t margin = 6;
    l.colStride = (SCREEN_WIDTH - 2 * margin) / SOLITAIRE_COLUMN_COUNT;
    for (uint8_t c = 0; c < SOLITAIRE_COLUMN_COUNT; c++) l.colX[c] = margin + c * l.colStride;

    return l;
}

static const int16_t SOLITAIRE_FAN_BASE_OFFSET = 18;
static const int16_t SOLITAIRE_FAN_MIN_OFFSET = 3;

int16_t solitaireFanOffset(const SolitaireLayout &l, uint8_t totalCardsInColumn) {
    if (totalCardsInColumn <= 1) return SOLITAIRE_FAN_BASE_OFFSET;
    int16_t avail = l.tableauH - l.cardH;
    int16_t natural = SOLITAIRE_FAN_BASE_OFFSET * (int16_t)(totalCardsInColumn - 1);
    if (natural <= avail) return SOLITAIRE_FAN_BASE_OFFSET;
    int16_t compressed = avail / (totalCardsInColumn - 1);
    return compressed < SOLITAIRE_FAN_MIN_OFFSET ? SOLITAIRE_FAN_MIN_OFFSET : compressed;
}

// ---------------------------------------------------------------------------
// Card rendering -- a short rank+suit-letter label (e.g. "10H", "AS", "KD"),
// never a drawn pip/glyph. Suit letters: C/D/H/S.
// ---------------------------------------------------------------------------
static void cardLabel(SolitaireCard c, char *buf, size_t bufSize) {
    char rankPart[3];
    switch (c.rank) {
        case SolitaireRank::ACE:   snprintf(rankPart, sizeof(rankPart), "A"); break;
        case SolitaireRank::TEN:   snprintf(rankPart, sizeof(rankPart), "10"); break;
        case SolitaireRank::JACK:  snprintf(rankPart, sizeof(rankPart), "J"); break;
        case SolitaireRank::QUEEN: snprintf(rankPart, sizeof(rankPart), "Q"); break;
        case SolitaireRank::KING:  snprintf(rankPart, sizeof(rankPart), "K"); break;
        default: snprintf(rankPart, sizeof(rankPart), "%u", (unsigned)c.rank); break;
    }
    char suitChar = 'C';
    switch (c.suit) {
        case SolitaireSuit::DIAMONDS: suitChar = 'D'; break;
        case SolitaireSuit::HEARTS:   suitChar = 'H'; break;
        case SolitaireSuit::SPADES:   suitChar = 'S'; break;
        default: suitChar = 'C'; break;
    }
    snprintf(buf, bufSize, "%s%c", rankPart, suitChar);
}

static void drawCardBack(TFT_eSPI &tft, int16_t x, int16_t y, int16_t w, int16_t h) {
    tft.fillRoundRect(x, y, w, h, 4, COLOR_CARD_BACK);
    tft.drawRoundRect(x, y, w, h, 4, COLOR_BORDER_DIM);
}

static void drawCardFace(TFT_eSPI &tft, int16_t x, int16_t y, int16_t w, int16_t h, SolitaireCard card, bool selected) {
    char label[4];
    cardLabel(card, label, sizeof(label));
    uint16_t fill = selected ? COLOR_CARD_SEL : COLOR_CARD_FILL;
    uint16_t border = selected ? COLOR_SELECTED_RING : COLOR_BORDER;
    uint16_t textColor = card.isRed() ? COLOR_RED_SUIT : COLOR_BLACK_SUIT;

    tft.fillRoundRect(x, y, w, h, 4, fill);
    tft.drawRoundRect(x, y, w, h, 4, border);
    if (selected) tft.drawRoundRect(x - 1, y - 1, w + 2, h + 2, 5, COLOR_SELECTED_RING);

    // Centered near the top-left corner rather than via TL_DATUM (every
    // other Display module in this project only ever uses MC_DATUM/
    // ML_DATUM -- see Theme.h's own top comment -- so this stays
    // consistent rather than introducing a new datum constant).
    tft.setTextColor(textColor, fill);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(label, x + 12, y + 9);
}

static void drawEmptyPileOutline(TFT_eSPI &tft, int16_t x, int16_t y, int16_t w, int16_t h, const char *hint) {
    tft.drawRoundRect(x, y, w, h, 4, COLOR_BORDER_DIM);
    if (hint) {
        tft.setTextColor(COLOR_BORDER_DIM, COLOR_BG);
        tft.setTextDatum(MC_DATUM);
        tft.setTextSize(1);
        tft.drawString(hint, x + w / 2, y + h / 2);
    }
}

// ---------------------------------------------------------------------------
// Piles.
// ---------------------------------------------------------------------------
void drawSolitaireStock(TFT_eSPI &tft, const SolitaireLayout &layout, uint8_t remainingCount) {
    tft.fillRect(layout.stockX - 2, layout.stockY - 2, layout.cardW + 4, layout.cardH + 4, COLOR_BG);
    if (remainingCount == 0) {
        drawEmptyPileOutline(tft, layout.stockX, layout.stockY, layout.cardW, layout.cardH, "recycle");
        return;
    }
    drawCardBack(tft, layout.stockX, layout.stockY, layout.cardW, layout.cardH);
}

void drawSolitaireWaste(TFT_eSPI &tft, const SolitaireLayout &layout, const SolitaireBoard &board) {
    tft.fillRect(layout.wasteX - 2, layout.wasteY - 2, layout.cardW + 4, layout.cardH + 4, COLOR_BG);
    if (board.wasteCount() == 0) {
        drawEmptyPileOutline(tft, layout.wasteX, layout.wasteY, layout.cardW, layout.cardH, nullptr);
        return;
    }
    bool selected = (board.selectionSource() == SolitaireSelectionSource::WASTE);
    drawCardFace(tft, layout.wasteX, layout.wasteY, layout.cardW, layout.cardH, board.wasteTopCard(), selected);
}

void drawSolitaireFoundation(TFT_eSPI &tft, const SolitaireLayout &layout, const SolitaireBoard &board, SolitaireSuit suit) {
    int16_t x = layout.foundationX[(uint8_t)suit];
    int16_t y = layout.foundationY;
    tft.fillRect(x - 2, y - 2, layout.cardW + 4, layout.cardH + 4, COLOR_BG);
    uint8_t count = board.foundationCount(suit);
    if (count == 0) {
        static const char *HINTS[SOLITAIRE_SUIT_COUNT] = { "C", "D", "H", "S" };
        drawEmptyPileOutline(tft, x, y, layout.cardW, layout.cardH, HINTS[(uint8_t)suit]);
        return;
    }
    // The foundation's own top card is always exactly (suit, rank=count) --
    // see SolitaireLogic.h's foundationCount()'s own comment.
    SolitaireCard top{ suit, (SolitaireRank)count };
    drawCardFace(tft, x, y, layout.cardW, layout.cardH, top, false);
}

void drawSolitaireColumn(TFT_eSPI &tft, const SolitaireLayout &layout, const SolitaireBoard &board, uint8_t col) {
    tft.fillRect(layout.colX[col], layout.tableauY, layout.cardW, layout.tableauH, COLOR_BG);

    uint8_t fd = board.columnFaceDownCount(col);
    uint8_t fu = board.columnFaceUpCount(col);
    uint8_t total = (uint8_t)(fd + fu);
    if (total == 0) {
        drawEmptyPileOutline(tft, layout.colX[col], layout.tableauY, layout.cardW, layout.cardH, "K");
        return;
    }

    int16_t offset = solitaireFanOffset(layout, total);
    int16_t y = layout.tableauY;
    for (uint8_t i = 0; i < fd; i++) {
        drawCardBack(tft, layout.colX[col], y, layout.cardW, layout.cardH);
        y += offset;
    }
    bool colSelected = (board.selectionSource() == SolitaireSelectionSource::TABLEAU && board.selectionColumn() == col);
    for (uint8_t i = 0; i < fu; i++) {
        SolitaireCard c = board.columnFaceUpCardAt(col, i);
        bool isTopSelected = colSelected && (i == fu - 1);
        drawCardFace(tft, layout.colX[col], y, layout.cardW, layout.cardH, c, isTopSelected);
        y += offset;
    }
}

void drawSolitaireTableauAndWaste(TFT_eSPI &tft, const SolitaireLayout &layout, const SolitaireBoard &board) {
    for (uint8_t c = 0; c < SOLITAIRE_COLUMN_COUNT; c++) drawSolitaireColumn(tft, layout, board, c);
    drawSolitaireWaste(tft, layout, board);
}

// ---------------------------------------------------------------------------
// Status bar / buttons / chrome.
// ---------------------------------------------------------------------------
void drawSolitaireStaticChrome(TFT_eSPI &tft, const SolitaireLayout &layout) {
    tft.fillScreen(COLOR_BG);
}

void drawSolitaireStatus(TFT_eSPI &tft, const SolitaireLayout &layout, const char *text, uint16_t gamesWon) {
    tft.fillRect(0, layout.statusY, SCREEN_WIDTH, layout.statusH, COLOR_STATUS_BG);

    int16_t by = homeButtonTop(layout);
    tft.drawRoundRect(HOME_BTN_MARGIN, by, HOME_BTN_SIZE, HOME_BTN_SIZE, 4, TFT_WHITE);
    tft.setTextColor(TFT_WHITE, COLOR_STATUS_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString("<", HOME_BTN_MARGIN + HOME_BTN_SIZE / 2, by + HOME_BTN_SIZE / 2);

    char chip[16];
    snprintf(chip, sizeof(chip), "Won: %u", (unsigned)gamesWon);
    tft.fillRoundRect(layout.wonChipX, layout.wonChipY, layout.wonChipW, layout.wonChipH, 6, COLOR_BUTTON_BG);
    tft.drawRoundRect(layout.wonChipX, layout.wonChipY, layout.wonChipW, layout.wonChipH, 6, TFT_WHITE);
    tft.setTextColor(COLOR_BUTTON_TEXT, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(chip, layout.wonChipX + layout.wonChipW / 2, layout.wonChipY + layout.wonChipH / 2);

    int16_t textAreaX0 = HOME_BTN_MARGIN * 2 + HOME_BTN_SIZE;
    int16_t textAreaX1 = layout.wonChipX - 4;
    tft.setTextColor(COLOR_STATUS_TEXT, COLOR_STATUS_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(text, textAreaX0 + (textAreaX1 - textAreaX0) / 2, layout.statusY + layout.statusH / 2);
}

static void drawSmallButton(TFT_eSPI &tft, int16_t x, int16_t y, int16_t w, int16_t h, const char *label, bool enabled) {
    uint16_t textColor = enabled ? COLOR_BUTTON_TEXT : COLOR_BUTTON_DIM;
    tft.fillRoundRect(x, y, w, h, 5, COLOR_BUTTON_BG);
    tft.drawRoundRect(x, y, w, h, 5, enabled ? TFT_WHITE : COLOR_BUTTON_DIM);
    tft.setTextColor(textColor, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(label, x + w / 2, y + h / 2);
}

void drawSolitaireButtons(TFT_eSPI &tft, const SolitaireLayout &layout, bool autoAvailable) {
    tft.fillRect(0, layout.newY - 2, SCREEN_WIDTH, layout.newH + 4, COLOR_BG);
    drawSmallButton(tft, layout.newX, layout.newY, layout.newW, layout.newH, "New", true);
    drawSmallButton(tft, layout.undoX, layout.undoY, layout.undoW, layout.undoH, "Undo", true);
    drawSmallButton(tft, layout.autoX, layout.autoY, layout.autoW, layout.autoH, "Auto", autoAvailable);
}

bool hitTestSolitaireStock(const SolitaireLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.stockX && touchX < layout.stockX + layout.cardW &&
           touchY >= layout.stockY && touchY < layout.stockY + layout.cardH;
}

bool hitTestSolitaireWaste(const SolitaireLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.wasteX && touchX < layout.wasteX + layout.cardW &&
           touchY >= layout.wasteY && touchY < layout.wasteY + layout.cardH;
}

bool hitTestSolitaireFoundation(const SolitaireLayout &layout, int16_t touchX, int16_t touchY, SolitaireSuit &outSuit) {
    if (touchY < layout.foundationY || touchY >= layout.foundationY + layout.cardH) return false;
    for (uint8_t i = 0; i < SOLITAIRE_SUIT_COUNT; i++) {
        if (touchX >= layout.foundationX[i] && touchX < layout.foundationX[i] + layout.cardW) {
            outSuit = (SolitaireSuit)i;
            return true;
        }
    }
    return false;
}

bool hitTestSolitaireTableau(const SolitaireLayout &layout, const SolitaireBoard &board, int16_t touchX, int16_t touchY, uint8_t &outCol) {
    if (touchY < layout.tableauY) return false;
    if (touchX < layout.colX[0]) return false;

    for (uint8_t c = 0; c < SOLITAIRE_COLUMN_COUNT; c++) {
        int16_t left = layout.colX[c];
        int16_t right = (c + 1 < SOLITAIRE_COLUMN_COUNT) ? layout.colX[c + 1] : (left + layout.colStride);
        if (touchX < left || touchX >= right) continue;
        // Only the card's own drawn width (not the full column stride,
        // which includes the gap to the next column) counts as a hit --
        // matches drawSolitaireColumn()'s own geometry exactly.
        if (touchX >= left + layout.cardW) return false;

        uint8_t total = (uint8_t)(board.columnFaceDownCount(c) + board.columnFaceUpCount(c));
        if (total == 0) {
            if (touchY < layout.tableauY + layout.cardH) { outCol = c; return true; }
            return false;
        }
        int16_t offset = solitaireFanOffset(layout, total);
        int16_t cascadeHeight = (int16_t)(total - 1) * offset + layout.cardH;
        if (touchY < layout.tableauY + cascadeHeight) { outCol = c; return true; }
        return false;
    }
    return false;
}

bool hitTestSolitaireNewButton(const SolitaireLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.newX && touchX < layout.newX + layout.newW &&
           touchY >= layout.newY && touchY < layout.newY + layout.newH;
}

bool hitTestSolitaireUndoButton(const SolitaireLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.undoX && touchX < layout.undoX + layout.undoW &&
           touchY >= layout.undoY && touchY < layout.undoY + layout.undoH;
}

bool hitTestSolitaireAutoButton(const SolitaireLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.autoX && touchX < layout.autoX + layout.autoW &&
           touchY >= layout.autoY && touchY < layout.autoY + layout.autoH;
}

bool hitTestSolitaireHomeButton(const SolitaireLayout &layout, int16_t touchX, int16_t touchY) {
    int16_t by = homeButtonTop(layout);
    return touchX >= 0 && touchX < HOME_BTN_MARGIN + HOME_BTN_SIZE &&
           touchY >= by && touchY < by + HOME_BTN_SIZE;
}
