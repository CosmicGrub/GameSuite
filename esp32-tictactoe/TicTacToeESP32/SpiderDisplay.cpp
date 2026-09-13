#include "SpiderDisplay.h"
#include "Config.h"
#include "Theme.h"
#include <cstdio>

static const uint16_t COLOR_BG            = THEME_BG;
static const uint16_t COLOR_CARD_FILL     = THEME_SURFACE;
static const uint16_t COLOR_CARD_SEL      = THEME_SURFACE_ALT;
static const uint16_t COLOR_CARD_BACK     = THEME_SURFACE_ALT;
static const uint16_t COLOR_BORDER        = THEME_TEXT;
static const uint16_t COLOR_BORDER_DIM    = THEME_TEXT_DIM;
static const uint16_t COLOR_BLACK_SUIT    = THEME_TEXT;
static const uint16_t COLOR_RED_SUIT      = THEME_DANGER;
static const uint16_t COLOR_SELECTED_RING = THEME_HUMAN;
static const uint16_t COLOR_STATUS_BG     = THEME_SURFACE;
static const uint16_t COLOR_STATUS_TEXT   = THEME_TEXT;
static const uint16_t COLOR_BUTTON_BG     = THEME_SURFACE_ALT;
static const uint16_t COLOR_BUTTON_TEXT   = THEME_TEXT;
static const uint16_t COLOR_BUTTON_DIM    = THEME_TEXT_DIM;

static const int16_t HOME_BTN_SIZE = 30;
static const int16_t HOME_BTN_MARGIN = 2;

static int16_t homeButtonTop(const SpiderLayout &layout) {
    return layout.statusY + (layout.statusH - HOME_BTN_SIZE) / 2;
}

SpiderLayout computeSpiderLayout() {
    SpiderLayout l{};
    l.statusY = 0;
    l.statusH = 36;

    l.seqChipW = 76;
    l.seqChipH = 26;
    l.seqChipX = SCREEN_WIDTH - l.seqChipW - 6;
    l.seqChipY = l.statusY + (l.statusH - l.seqChipH) / 2;

    int16_t actionY = l.statusH;
    int16_t actionH = 30;
    l.newW = 56; l.newH = 24; l.newX = 8; l.newY = actionY + (actionH - l.newH) / 2;
    l.undoW = 56; l.undoH = 24; l.undoX = l.newX + l.newW + 8; l.undoY = l.newY;
    l.stockW = 92; l.stockH = 24; l.stockX = SCREEN_WIDTH - l.stockW - 8; l.stockY = l.newY;

    l.cardW = 36;
    l.cardH = 50;

    l.tableauY = actionY + actionH + 6;
    l.tableauH = SCREEN_HEIGHT - l.tableauY;

    int16_t margin = 6;
    l.colStride = (SCREEN_WIDTH - 2 * margin) / SPIDER_COLUMN_COUNT;
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) l.colX[c] = margin + c * l.colStride;

    return l;
}

static const int16_t SPIDER_FAN_BASE_OFFSET = 15;
static const int16_t SPIDER_FAN_MIN_OFFSET = 2;

int16_t spiderFanOffset(const SpiderLayout &l, uint8_t totalCardsInColumn) {
    if (totalCardsInColumn <= 1) return SPIDER_FAN_BASE_OFFSET;
    int16_t avail = l.tableauH - l.cardH;
    int16_t natural = SPIDER_FAN_BASE_OFFSET * (int16_t)(totalCardsInColumn - 1);
    if (natural <= avail) return SPIDER_FAN_BASE_OFFSET;
    int16_t compressed = avail / (totalCardsInColumn - 1);
    return compressed < SPIDER_FAN_MIN_OFFSET ? SPIDER_FAN_MIN_OFFSET : compressed;
}

// ---------------------------------------------------------------------------
// Card rendering -- same rank+suit-letter label convention as
// SolitaireDisplay.cpp's Klondike cards (see that file's own comment).
// ---------------------------------------------------------------------------
static void cardLabel(SpiderCardView c, char *buf, size_t bufSize) {
    char rankPart[3];
    switch (c.rank) {
        case SpiderRank::ACE:   snprintf(rankPart, sizeof(rankPart), "A"); break;
        case SpiderRank::TEN:   snprintf(rankPart, sizeof(rankPart), "10"); break;
        case SpiderRank::JACK:  snprintf(rankPart, sizeof(rankPart), "J"); break;
        case SpiderRank::QUEEN: snprintf(rankPart, sizeof(rankPart), "Q"); break;
        case SpiderRank::KING:  snprintf(rankPart, sizeof(rankPart), "K"); break;
        default: snprintf(rankPart, sizeof(rankPart), "%u", (unsigned)c.rank); break;
    }
    char suitChar = 'C';
    switch (c.suit) {
        case SpiderSuit::DIAMONDS: suitChar = 'D'; break;
        case SpiderSuit::HEARTS:   suitChar = 'H'; break;
        case SpiderSuit::SPADES:   suitChar = 'S'; break;
        default: suitChar = 'C'; break;
    }
    snprintf(buf, bufSize, "%s%c", rankPart, suitChar);
}

static bool isRedSuit(SpiderSuit s) { return s == SpiderSuit::DIAMONDS || s == SpiderSuit::HEARTS; }

static void drawCardBack(TFT_eSPI &tft, int16_t x, int16_t y, int16_t w, int16_t h) {
    tft.fillRoundRect(x, y, w, h, 4, COLOR_CARD_BACK);
    tft.drawRoundRect(x, y, w, h, 4, COLOR_BORDER_DIM);
}

static void drawCardFace(TFT_eSPI &tft, int16_t x, int16_t y, int16_t w, int16_t h, SpiderCardView card, bool selected) {
    char label[4];
    cardLabel(card, label, sizeof(label));
    uint16_t fill = selected ? COLOR_CARD_SEL : COLOR_CARD_FILL;
    uint16_t border = selected ? COLOR_SELECTED_RING : COLOR_BORDER;
    uint16_t textColor = isRedSuit(card.suit) ? COLOR_RED_SUIT : COLOR_BLACK_SUIT;

    tft.fillRoundRect(x, y, w, h, 4, fill);
    tft.drawRoundRect(x, y, w, h, 4, border);
    if (selected) tft.drawRoundRect(x - 1, y - 1, w + 2, h + 2, 5, COLOR_SELECTED_RING);

    tft.setTextColor(textColor, fill);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(label, x + 11, y + 8); // centered near the top-left corner -- see SolitaireDisplay.cpp's own identical comment on why not TL_DATUM
}

static void drawEmptyColumnOutline(TFT_eSPI &tft, int16_t x, int16_t y, int16_t w, int16_t h) {
    tft.drawRoundRect(x, y, w, h, 4, COLOR_BORDER_DIM);
}

void drawSpiderColumn(TFT_eSPI &tft, const SpiderLayout &layout, const SpiderBoard &board, uint8_t col) {
    tft.fillRect(layout.colX[col], layout.tableauY, layout.cardW, layout.tableauH, COLOR_BG);

    uint8_t total = board.columnCount(col);
    if (total == 0) {
        drawEmptyColumnOutline(tft, layout.colX[col], layout.tableauY, layout.cardW, layout.cardH);
        return;
    }

    uint8_t faceDown = board.columnFaceDownCount(col);
    int16_t offset = spiderFanOffset(layout, total);
    int16_t y = layout.tableauY;
    bool colSelected = (board.selectedColumn() >= 0 && (uint8_t)board.selectedColumn() == col);

    for (uint8_t i = 0; i < total; i++) {
        bool isTop = (i == total - 1);
        if (i < faceDown) {
            drawCardBack(tft, layout.colX[col], y, layout.cardW, layout.cardH);
        } else {
            SpiderCardView c = board.columnCardAt(col, i);
            drawCardFace(tft, layout.colX[col], y, layout.cardW, layout.cardH, c, colSelected && isTop);
        }
        y += offset;
    }
}

void drawSpiderTableau(TFT_eSPI &tft, const SpiderLayout &layout, const SpiderBoard &board) {
    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) drawSpiderColumn(tft, layout, board, c);
}

// ---------------------------------------------------------------------------
// Status bar / buttons / chrome.
// ---------------------------------------------------------------------------
void drawSpiderStaticChrome(TFT_eSPI &tft, const SpiderLayout &layout) {
    tft.fillScreen(COLOR_BG);
}

void drawSpiderStatus(TFT_eSPI &tft, const SpiderLayout &layout, const char *text, uint8_t completedSequences) {
    tft.fillRect(0, layout.statusY, SCREEN_WIDTH, layout.statusH, COLOR_STATUS_BG);

    int16_t by = homeButtonTop(layout);
    tft.drawRoundRect(HOME_BTN_MARGIN, by, HOME_BTN_SIZE, HOME_BTN_SIZE, 4, TFT_WHITE);
    tft.setTextColor(TFT_WHITE, COLOR_STATUS_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString("<", HOME_BTN_MARGIN + HOME_BTN_SIZE / 2, by + HOME_BTN_SIZE / 2);

    char chip[16];
    snprintf(chip, sizeof(chip), "Seq: %u/%u", (unsigned)completedSequences, (unsigned)SPIDER_TOTAL_SEQUENCES);
    tft.fillRoundRect(layout.seqChipX, layout.seqChipY, layout.seqChipW, layout.seqChipH, 6, COLOR_BUTTON_BG);
    tft.drawRoundRect(layout.seqChipX, layout.seqChipY, layout.seqChipW, layout.seqChipH, 6, TFT_WHITE);
    tft.setTextColor(COLOR_BUTTON_TEXT, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(chip, layout.seqChipX + layout.seqChipW / 2, layout.seqChipY + layout.seqChipH / 2);

    int16_t textAreaX0 = HOME_BTN_MARGIN * 2 + HOME_BTN_SIZE;
    int16_t textAreaX1 = layout.seqChipX - 4;
    tft.setTextColor(COLOR_STATUS_TEXT, COLOR_STATUS_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(text, textAreaX0 + (textAreaX1 - textAreaX0) / 2, layout.statusY + layout.statusH / 2);
}

static void drawSmallButton(TFT_eSPI &tft, int16_t x, int16_t y, int16_t w, int16_t h, const char *label) {
    tft.fillRoundRect(x, y, w, h, 5, COLOR_BUTTON_BG);
    tft.drawRoundRect(x, y, w, h, 5, TFT_WHITE);
    tft.setTextColor(COLOR_BUTTON_TEXT, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(label, x + w / 2, y + h / 2);
}

void drawSpiderButtons(TFT_eSPI &tft, const SpiderLayout &layout) {
    tft.fillRect(0, layout.newY - 2, SCREEN_WIDTH, layout.newH + 4, COLOR_BG);
    drawSmallButton(tft, layout.newX, layout.newY, layout.newW, layout.newH, "New");
    drawSmallButton(tft, layout.undoX, layout.undoY, layout.undoW, layout.undoH, "Undo");
}

void drawSpiderStock(TFT_eSPI &tft, const SpiderLayout &layout, uint8_t remainingCount, bool dealAvailable) {
    uint16_t border = dealAvailable ? TFT_WHITE : COLOR_BUTTON_DIM;
    uint16_t textColor = dealAvailable ? COLOR_BUTTON_TEXT : COLOR_BUTTON_DIM;
    tft.fillRoundRect(layout.stockX, layout.stockY, layout.stockW, layout.stockH, 5, COLOR_BUTTON_BG);
    tft.drawRoundRect(layout.stockX, layout.stockY, layout.stockW, layout.stockH, 5, border);
    char label[16];
    snprintf(label, sizeof(label), "Stock: %u", (unsigned)remainingCount);
    tft.setTextColor(textColor, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(label, layout.stockX + layout.stockW / 2, layout.stockY + layout.stockH / 2);
}

bool hitTestSpiderNewButton(const SpiderLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.newX && touchX < layout.newX + layout.newW &&
           touchY >= layout.newY && touchY < layout.newY + layout.newH;
}

bool hitTestSpiderUndoButton(const SpiderLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.undoX && touchX < layout.undoX + layout.undoW &&
           touchY >= layout.undoY && touchY < layout.undoY + layout.undoH;
}

bool hitTestSpiderStock(const SpiderLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.stockX && touchX < layout.stockX + layout.stockW &&
           touchY >= layout.stockY && touchY < layout.stockY + layout.stockH;
}

bool hitTestSpiderHomeButton(const SpiderLayout &layout, int16_t touchX, int16_t touchY) {
    int16_t by = homeButtonTop(layout);
    return touchX >= 0 && touchX < HOME_BTN_MARGIN + HOME_BTN_SIZE &&
           touchY >= by && touchY < by + HOME_BTN_SIZE;
}

bool hitTestSpiderTableau(const SpiderLayout &layout, const SpiderBoard &board, int16_t touchX, int16_t touchY, uint8_t &outCol) {
    if (touchY < layout.tableauY) return false;
    if (touchX < layout.colX[0]) return false;

    for (uint8_t c = 0; c < SPIDER_COLUMN_COUNT; c++) {
        int16_t left = layout.colX[c];
        int16_t right = (c + 1 < SPIDER_COLUMN_COUNT) ? layout.colX[c + 1] : (left + layout.colStride);
        if (touchX < left || touchX >= right) continue;
        if (touchX >= left + layout.cardW) return false; // only the card's own drawn width counts, not the full column stride

        uint8_t total = board.columnCount(c);
        if (total == 0) {
            if (touchY < layout.tableauY + layout.cardH) { outCol = c; return true; }
            return false;
        }
        int16_t offset = spiderFanOffset(layout, total);
        int16_t cascadeHeight = (int16_t)(total - 1) * offset + layout.cardH;
        if (touchY < layout.tableauY + cascadeHeight) { outCol = c; return true; }
        return false;
    }
    return false;
}
