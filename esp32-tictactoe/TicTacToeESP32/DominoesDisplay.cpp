#include "DominoesDisplay.h"
#include "Config.h"
#include "Theme.h"
#include <cstdio>

// Colors go through Theme.h's shared palette so the whole arcade reads as
// one consistent product.
static const uint16_t COLOR_BG            = THEME_BG;
static const uint16_t COLOR_TILE_FILL     = THEME_SURFACE;
static const uint16_t COLOR_TILE_SELECTED = THEME_SURFACE_ALT;
static const uint16_t COLOR_TILE_BACK     = THEME_SURFACE_ALT;
static const uint16_t COLOR_BORDER        = THEME_TEXT;
static const uint16_t COLOR_BORDER_DIM    = THEME_TEXT_DIM;
static const uint16_t COLOR_PIP           = THEME_TEXT;
static const uint16_t COLOR_PIP_DIM       = THEME_TEXT_DIM;
static const uint16_t COLOR_SELECTED_RING = THEME_HUMAN;
static const uint16_t COLOR_STATUS_BG     = THEME_SURFACE;
static const uint16_t COLOR_STATUS_TEXT   = THEME_TEXT;
static const uint16_t COLOR_BUTTON_BG     = THEME_SURFACE_ALT;
static const uint16_t COLOR_BUTTON_TEXT   = THEME_TEXT;
static const uint16_t COLOR_DROPZONE      = THEME_SUCCESS;
static const uint16_t COLOR_CHEVRON       = THEME_TEXT_DIM;

static const int16_t HOME_BTN_SIZE = 30;
static const int16_t HOME_BTN_MARGIN = 2;

static int16_t homeButtonTop(const DominoesLayout &layout) {
    return layout.statusY + (layout.statusH - HOME_BTN_SIZE) / 2;
}

DominoesLayout computeDominoesLayout() {
    DominoesLayout l{};
    l.statusY = 0;
    l.statusH = 36;

    l.difficultyW = 92;
    l.difficultyH = 26;
    l.difficultyX = SCREEN_WIDTH - l.difficultyW - 6;
    l.difficultyY = l.statusY + (l.statusH - l.difficultyH) / 2;

    l.aiHandY = l.statusY + l.statusH + 4;
    l.aiHandH = 32;

    l.chainY = l.aiHandY + l.aiHandH + 4;
    l.chainH = 90;
    l.chevronW = 36;
    l.chainX = l.chevronW;
    l.chainW = SCREEN_WIDTH - 2 * l.chevronW;

    int16_t actionY = l.chainY + l.chainH + 4;
    int16_t actionH = 40;
    l.boneyardW = 90; l.boneyardH = 36;
    l.passW = 90; l.passH = 36;
    int16_t gap = 20;
    int16_t totalW = l.boneyardW + gap + l.passW;
    int16_t startX = (SCREEN_WIDTH - totalW) / 2;
    l.boneyardX = startX;
    l.boneyardY = actionY + (actionH - l.boneyardH) / 2;
    l.passX = startX + l.boneyardW + gap;
    l.passY = actionY + (actionH - l.passH) / 2;

    l.handY = actionY + actionH + 6;
    l.handH = SCREEN_HEIGHT - l.handY;

    l.tileW = 44;
    l.tileH = 64;
    l.handGap = 6;
    l.handMarginX = 10;

    l.buttonW = 160;
    l.buttonH = 44;
    l.buttonX = (SCREEN_WIDTH - l.buttonW) / 2;
    l.buttonY = l.chainY + (l.chainH - l.buttonH) / 2;

    return l;
}

uint8_t dominoesChainVisibleTileCount(const DominoesLayout &layout) {
    uint8_t n = (uint8_t)(layout.chainW / layout.tileW);
    return n < 1 ? 1 : n;
}

// ---------------------------------------------------------------------------
// Pip-dot rendering -- a real 0-6 dot pattern, like a physical domino/die
// face, over a 3x3 grid of possible positions (TL,TM,TR / ML,MM,MR /
// BL,BM,BR) -- the same fixed arrangement every real domino uses.
// ---------------------------------------------------------------------------
static const bool PIP_LAYOUTS[7][9] = {
    { false, false, false, false, false, false, false, false, false }, // 0
    { false, false, false, false, true,  false, false, false, false }, // 1
    { true,  false, false, false, false, false, false, false, true  }, // 2
    { true,  false, false, false, true,  false, false, false, true  }, // 3
    { true,  false, true,  false, false, false, true,  false, true  }, // 4
    { true,  false, true,  false, true,  false, true,  false, true  }, // 5
    { true,  false, true,  true,  false, true,  true,  false, true  }, // 6
};

static void drawPipFace(TFT_eSPI &tft, int16_t x, int16_t y, int16_t w, int16_t h, uint8_t value, uint16_t dotColor) {
    if (value > 6) value = 6; // defensive -- a real tile never carries more than 6 pips per half
    int16_t dotR = (w < h ? w : h) / 7;
    if (dotR < 2) dotR = 2;
    int16_t colX[3] = { (int16_t)(x + w / 4), (int16_t)(x + w / 2), (int16_t)(x + 3 * w / 4) };
    int16_t rowY[3] = { (int16_t)(y + h / 4), (int16_t)(y + h / 2), (int16_t)(y + 3 * h / 4) };
    const bool *layout = PIP_LAYOUTS[value];
    for (uint8_t r = 0; r < 3; r++) {
        for (uint8_t c = 0; c < 3; c++) {
            if (layout[r * 3 + c]) tft.fillCircle(colX[c], rowY[r], dotR, dotColor);
        }
    }
}

// A landscape tile split by a vertical divider into two pip-halves --
// leftPip on the left half, rightPip on the right half. Two chain tiles
// drawn edge-to-edge always show a matching pip count at their shared seam
// (see DominoesDisplay.h's own header comment for why that's guaranteed by
// the rules, not just a rendering coincidence), which is what makes a
// scrolled chain window read as one continuous physical line.
static void drawTileFace(TFT_eSPI &tft, int16_t x, int16_t y, int16_t w, int16_t h,
                          uint8_t leftPip, uint8_t rightPip, uint16_t fillColor, uint16_t borderColor, uint16_t pipColor) {
    tft.fillRoundRect(x, y, w, h, 5, fillColor);
    tft.drawRoundRect(x, y, w, h, 5, borderColor);
    tft.drawFastVLine(x + w / 2, y + 3, h - 6, borderColor);
    drawPipFace(tft, x, y, w / 2, h, leftPip, pipColor);
    drawPipFace(tft, x + w / 2, y, w / 2, h, rightPip, pipColor);
}

// ---------------------------------------------------------------------------
// Human hand layout -- shared by draw + hit-test, same "tiles overlap if
// they wouldn't otherwise fit" idiom as UnoDisplay.cpp's own
// handAdvance()/handRowLeft() (reused here nearly verbatim, just against
// this file's own tileW/handGap/handMarginX fields).
// ---------------------------------------------------------------------------
static int16_t handAdvance(const DominoesLayout &l, uint8_t count) {
    int16_t natural = l.tileW + l.handGap;
    if (count <= 1) return natural;
    int16_t maxRowWidth = SCREEN_WIDTH - 2 * l.handMarginX;
    int16_t neededWidth = natural * (count - 1) + l.tileW;
    if (neededWidth <= maxRowWidth) return natural;
    int16_t compressed = (maxRowWidth - l.tileW) / (count - 1);
    return compressed < 4 ? 4 : compressed;
}

static int16_t handRowLeft(const DominoesLayout &l, uint8_t count, int16_t advance) {
    if (count == 0) return SCREEN_WIDTH / 2;
    int16_t totalWidth = advance * (count - 1) + l.tileW;
    return (SCREEN_WIDTH - totalWidth) / 2;
}

void drawDominoesHumanHand(TFT_eSPI &tft, const DominoesLayout &layout, const DominoTileView *tiles, uint8_t count,
                            uint32_t playableMask, int8_t selectedIndex) {
    tft.fillRect(0, layout.handY - 2, SCREEN_WIDTH, layout.handH, COLOR_BG);

    int16_t advance = handAdvance(layout, count);
    int16_t left = handRowLeft(layout, count, advance);
    for (uint8_t i = 0; i < count; i++) {
        int16_t x = left + i * advance;
        bool playable = (playableMask >> i) & 1u;
        bool selected = (i == (uint8_t)selectedIndex);
        uint16_t fill = selected ? COLOR_TILE_SELECTED : COLOR_TILE_FILL;
        uint16_t border = selected ? COLOR_SELECTED_RING : (playable ? COLOR_BORDER : COLOR_BORDER_DIM);
        uint16_t pip = playable ? COLOR_PIP : COLOR_PIP_DIM;
        drawTileFace(tft, x, layout.handY, layout.tileW, layout.tileH, tiles[i].a, tiles[i].b, fill, border, pip);
        if (selected) {
            tft.drawRoundRect(x - 2, layout.handY - 2, layout.tileW + 4, layout.tileH + 4, 6, COLOR_SELECTED_RING);
        }
    }
}

bool hitTestDominoesHumanHandTile(const DominoesLayout &layout, uint8_t handCount, int16_t touchX, int16_t touchY, uint8_t &outIndex) {
    if (handCount == 0) return false;
    if (touchY < layout.handY || touchY >= layout.handY + layout.tileH) return false;

    int16_t advance = handAdvance(layout, handCount);
    int16_t left = handRowLeft(layout, handCount, advance);
    for (int16_t i = (int16_t)handCount - 1; i >= 0; i--) {
        int16_t tileLeft = left + i * advance;
        if (touchX >= tileLeft && touchX < tileLeft + layout.tileW) {
            outIndex = (uint8_t)i;
            return true;
        }
    }
    return false;
}

// ---------------------------------------------------------------------------
// AI hand -- face-down count only, mirroring UnoDisplay.cpp's own
// drawUnoAiHand().
// ---------------------------------------------------------------------------
void drawDominoesAiHand(TFT_eSPI &tft, const DominoesLayout &layout, uint8_t count) {
    tft.fillRect(0, layout.aiHandY - 2, SCREEN_WIDTH, layout.aiHandH + 4, COLOR_BG);

    char label[24];
    snprintf(label, sizeof(label), "AI: %u tile%s", (unsigned)count, count == 1 ? "" : "s");
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.setTextColor(THEME_AI, COLOR_BG);
    tft.drawString(label, SCREEN_WIDTH / 2, layout.aiHandY + layout.aiHandH / 2);

    uint8_t shown = count > 12 ? 12 : count;
    int16_t backW = 14, backH = 22, gap = 3;
    int16_t totalW = shown * backW + (shown > 0 ? (shown - 1) * gap : 0);
    int16_t startX = SCREEN_WIDTH / 2 - totalW - 70;
    for (uint8_t i = 0; i < shown; i++) {
        tft.fillRoundRect(startX + i * (backW + gap), layout.aiHandY + (layout.aiHandH - backH) / 2, backW, backH, 2, COLOR_TILE_BACK);
    }
}

// ---------------------------------------------------------------------------
// Chain track (scrollable) + its dual-purpose end chevrons.
// ---------------------------------------------------------------------------
void drawDominoesChain(TFT_eSPI &tft, const DominoesLayout &layout, const DominoesBoard &board, uint8_t scrollOffset) {
    tft.fillRect(layout.chainX, layout.chainY, layout.chainW, layout.chainH, COLOR_BG);

    uint8_t total = board.chainLength();
    uint8_t visible = dominoesChainVisibleTileCount(layout);
    uint8_t shown = (uint8_t)((total - scrollOffset) < visible ? (total - scrollOffset) : visible);
    int16_t tileY = layout.chainY + (layout.chainH - layout.tileH) / 2;

    for (uint8_t i = 0; i < shown; i++) {
        uint8_t chainIdx = scrollOffset + i;
        DominoTileView t = board.chainTileAt(chainIdx);
        bool flipped = board.chainTileFlippedAt(chainIdx);
        uint8_t leftPip = flipped ? t.b : t.a;
        uint8_t rightPip = flipped ? t.a : t.b;
        int16_t x = layout.chainX + i * layout.tileW;
        drawTileFace(tft, x, tileY, layout.tileW, layout.tileH, leftPip, rightPip, COLOR_TILE_FILL, COLOR_BORDER, COLOR_PIP);
    }
}

void drawDominoesScrollChevrons(TFT_eSPI &tft, const DominoesLayout &layout, bool canScrollLeft, bool canScrollRight) {
    tft.fillRect(0, layout.chainY, layout.chevronW, layout.chainH, COLOR_BG);
    tft.fillRect(SCREEN_WIDTH - layout.chevronW, layout.chainY, layout.chevronW, layout.chainH, COLOR_BG);

    int16_t midY = layout.chainY + layout.chainH / 2;
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.setTextColor(canScrollLeft ? COLOR_CHEVRON : COLOR_BORDER_DIM, COLOR_BG);
    tft.drawString("<", layout.chevronW / 2, midY);
    tft.setTextColor(canScrollRight ? COLOR_CHEVRON : COLOR_BORDER_DIM, COLOR_BG);
    tft.drawString(">", SCREEN_WIDTH - layout.chevronW / 2, midY);
}

void drawDominoesSelectionDropZones(TFT_eSPI &tft, const DominoesLayout &layout, bool legalOnLeft, bool legalOnRight) {
    int16_t midY = layout.chainY + layout.chainH / 2;

    tft.fillRect(0, layout.chainY, layout.chevronW, layout.chainH, COLOR_BG);
    if (legalOnLeft) {
        tft.fillRoundRect(2, layout.chainY + 6, layout.chevronW - 4, layout.chainH - 12, 5, COLOR_DROPZONE);
        tft.setTextColor(COLOR_BG, COLOR_DROPZONE);
    } else {
        tft.setTextColor(COLOR_BORDER_DIM, COLOR_BG);
    }
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.drawString("<", layout.chevronW / 2, midY);

    tft.fillRect(SCREEN_WIDTH - layout.chevronW, layout.chainY, layout.chevronW, layout.chainH, COLOR_BG);
    if (legalOnRight) {
        tft.fillRoundRect(SCREEN_WIDTH - layout.chevronW + 2, layout.chainY + 6, layout.chevronW - 4, layout.chainH - 12, 5, COLOR_DROPZONE);
        tft.setTextColor(COLOR_BG, COLOR_DROPZONE);
    } else {
        tft.setTextColor(COLOR_BORDER_DIM, COLOR_BG);
    }
    tft.drawString(">", SCREEN_WIDTH - layout.chevronW / 2, midY);
}

// ---------------------------------------------------------------------------
// Boneyard / Pass / status / chrome / Play Again.
// ---------------------------------------------------------------------------
void drawDominoesBoneyard(TFT_eSPI &tft, const DominoesLayout &layout, uint8_t remainingCount) {
    tft.fillRoundRect(layout.boneyardX, layout.boneyardY, layout.boneyardW, layout.boneyardH, 6, COLOR_TILE_BACK);
    tft.drawRoundRect(layout.boneyardX, layout.boneyardY, layout.boneyardW, layout.boneyardH, 6, TFT_WHITE);
    char label[16];
    snprintf(label, sizeof(label), "Draw: %u", (unsigned)remainingCount);
    tft.setTextColor(COLOR_STATUS_TEXT, COLOR_TILE_BACK);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(label, layout.boneyardX + layout.boneyardW / 2, layout.boneyardY + layout.boneyardH / 2);
}

void drawDominoesPassButton(TFT_eSPI &tft, const DominoesLayout &layout) {
    tft.fillRoundRect(layout.passX, layout.passY, layout.passW, layout.passH, 6, COLOR_BUTTON_BG);
    tft.drawRoundRect(layout.passX, layout.passY, layout.passW, layout.passH, 6, TFT_WHITE);
    tft.setTextColor(COLOR_BUTTON_TEXT, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString("Pass", layout.passX + layout.passW / 2, layout.passY + layout.passH / 2);
}

void drawDominoesStaticChrome(TFT_eSPI &tft, const DominoesLayout &layout) {
    tft.fillScreen(COLOR_BG);
}

void drawDominoesStatus(TFT_eSPI &tft, const DominoesLayout &layout, const char *text, CpuDifficulty difficulty) {
    tft.fillRect(0, layout.statusY, SCREEN_WIDTH, layout.statusH, COLOR_STATUS_BG);

    int16_t by = homeButtonTop(layout);
    tft.drawRoundRect(HOME_BTN_MARGIN, by, HOME_BTN_SIZE, HOME_BTN_SIZE, 4, TFT_WHITE);
    tft.setTextColor(TFT_WHITE, COLOR_STATUS_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString("<", HOME_BTN_MARGIN + HOME_BTN_SIZE / 2, by + HOME_BTN_SIZE / 2);

    const char *diffLabel = "AI: MED";
    if (difficulty == CpuDifficulty::EASY) diffLabel = "AI: EASY";
    else if (difficulty == CpuDifficulty::HARD) diffLabel = "AI: HARD";
    tft.fillRoundRect(layout.difficultyX, layout.difficultyY, layout.difficultyW, layout.difficultyH, 6, COLOR_BUTTON_BG);
    tft.drawRoundRect(layout.difficultyX, layout.difficultyY, layout.difficultyW, layout.difficultyH, 6, TFT_WHITE);
    tft.setTextColor(COLOR_BUTTON_TEXT, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(diffLabel, layout.difficultyX + layout.difficultyW / 2, layout.difficultyY + layout.difficultyH / 2);

    int16_t textAreaX0 = HOME_BTN_MARGIN * 2 + HOME_BTN_SIZE;
    int16_t textAreaX1 = layout.difficultyX - 4;
    tft.setTextColor(COLOR_STATUS_TEXT, COLOR_STATUS_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(text, textAreaX0 + (textAreaX1 - textAreaX0) / 2, layout.statusY + layout.statusH / 2);
}

void drawDominoesPlayAgainButton(TFT_eSPI &tft, const DominoesLayout &layout) {
    tft.fillRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, COLOR_BUTTON_BG);
    tft.drawRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, TFT_WHITE);
    tft.setTextColor(COLOR_BUTTON_TEXT, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString("Play Again", layout.buttonX + layout.buttonW / 2, layout.buttonY + layout.buttonH / 2);
}

void hideDominoesPlayAgainButton(TFT_eSPI &tft, const DominoesLayout &layout) {
    tft.fillRect(layout.buttonX - 2, layout.buttonY - 2, layout.buttonW + 4, layout.buttonH + 4, COLOR_BG);
}

bool hitTestDominoesBoneyard(const DominoesLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.boneyardX && touchX < layout.boneyardX + layout.boneyardW &&
           touchY >= layout.boneyardY && touchY < layout.boneyardY + layout.boneyardH;
}

bool hitTestDominoesPassButton(const DominoesLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.passX && touchX < layout.passX + layout.passW &&
           touchY >= layout.passY && touchY < layout.passY + layout.passH;
}

bool hitTestDominoesPlayAgainButton(const DominoesLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.buttonX && touchX < layout.buttonX + layout.buttonW &&
           touchY >= layout.buttonY && touchY < layout.buttonY + layout.buttonH;
}

bool hitTestDominoesHomeButton(const DominoesLayout &layout, int16_t touchX, int16_t touchY) {
    int16_t by = homeButtonTop(layout);
    return touchX >= 0 && touchX < HOME_BTN_MARGIN + HOME_BTN_SIZE &&
           touchY >= by && touchY < by + HOME_BTN_SIZE;
}

bool hitTestDominoesDifficultyButton(const DominoesLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.difficultyX && touchX < layout.difficultyX + layout.difficultyW &&
           touchY >= layout.difficultyY && touchY < layout.difficultyY + layout.difficultyH;
}

bool hitTestDominoesLeftChevron(const DominoesLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= 0 && touchX < layout.chevronW &&
           touchY >= layout.chainY && touchY < layout.chainY + layout.chainH;
}

bool hitTestDominoesRightChevron(const DominoesLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= SCREEN_WIDTH - layout.chevronW && touchX < SCREEN_WIDTH &&
           touchY >= layout.chainY && touchY < layout.chainY + layout.chainH;
}
