#include "UnoDisplay.h"
#include "Config.h"
#include <cstdio>

// native_test's TFT_eSPI.h stub (which this project's shared conventions
// say never to edit) only defines the handful of color constants Display.cpp
// happens to use -- TFT_RED/TFT_YELLOW/TFT_BLUE aren't among them. Guarded
// with the real library's own standard RGB565 values, so on real hardware
// (where TFT_eSPI.h already #defines all of these) this is a no-op and the
// real driver's own values win; only the native stub build ever sees these.
#ifndef TFT_RED
#define TFT_RED 0xF800
#endif
#ifndef TFT_YELLOW
#define TFT_YELLOW 0xFFE0
#endif
#ifndef TFT_BLUE
#define TFT_BLUE 0x001F
#endif

static const uint16_t COLOR_BG          = TFT_BLACK;
static const uint16_t COLOR_STATUS_BG   = TFT_NAVY;
static const uint16_t COLOR_STATUS_TEXT = TFT_WHITE;
static const uint16_t COLOR_BUTTON_BG   = TFT_DARKGREEN;
static const uint16_t COLOR_BUTTON_TEXT = TFT_WHITE;
static const uint16_t COLOR_CARD_BACK   = TFT_DARKGREY;
static const uint16_t COLOR_OVERLAY_BG  = TFT_NAVY;

static const int16_t HOME_BTN_SIZE = 30;
static const int16_t HOME_BTN_MARGIN = 2;

static int16_t homeButtonTop(const UnoLayout &layout) {
    return layout.statusY + (layout.statusH - HOME_BTN_SIZE) / 2;
}

UnoLayout computeUnoLayout() {
    UnoLayout l;
    l.statusY = 0;
    l.statusH = 32;

    l.aiHandY = l.statusY + l.statusH + 4;
    l.aiHandH = 32;

    l.cardW = 60;
    l.cardH = 90;
    int16_t centerTop = l.aiHandY + l.aiHandH + 4;
    l.handMarginX = 10;
    l.handCardW = 46;
    l.handCardH = 64;
    l.handGap = 8;
    l.handY = SCREEN_HEIGHT - l.handCardH - 10;

    int16_t centerBandH = l.handY - 6 - centerTop;
    l.discardY = l.drawPileY = centerTop + (centerBandH - l.cardH) / 2;

    int16_t pileGap = 30;
    int16_t pairWidth = l.cardW * 2 + pileGap;
    l.discardX = (SCREEN_WIDTH - pairWidth) / 2;
    l.drawPileX = l.discardX + l.cardW + pileGap;

    l.buttonW = 160;
    l.buttonH = 44;
    l.buttonX = (SCREEN_WIDTH - l.buttonW) / 2;
    l.buttonY = l.discardY + (l.cardH - l.buttonH) / 2;

    return l;
}

// ---------------------------------------------------------------------------
// Card visuals -- color + a short text label, never a bitmap. A Wild
// (unresolved color) gets a 4-quadrant RGYB patch instead of a solid fill so
// it reads as genuinely different from a colored card at a glance, with the
// label on a small black badge in the middle (rather than TFT_eSPI's 2-color
// setTextColor(fg,bg), which has no single "background" to match a
// quadrant fill).
// ---------------------------------------------------------------------------

static uint16_t colorFor(UnoColor c) {
    switch (c) {
        case UnoColor::RED: return TFT_RED;
        case UnoColor::YELLOW: return TFT_YELLOW;
        case UnoColor::GREEN: return TFT_GREEN;
        case UnoColor::BLUE: return TFT_BLUE;
        default: return TFT_DARKGREY;
    }
}

static void cardLabel(UnoCardView c, char *buf, size_t bufSize) {
    switch (c.rank) {
        case UnoRank::SKIP: snprintf(buf, bufSize, "SK"); break;
        case UnoRank::REVERSE: snprintf(buf, bufSize, "RV"); break;
        case UnoRank::DRAW_TWO: snprintf(buf, bufSize, "+2"); break;
        case UnoRank::WILD: snprintf(buf, bufSize, "W"); break;
        case UnoRank::WILD_DRAW_FOUR: snprintf(buf, bufSize, "W4"); break;
        default: snprintf(buf, bufSize, "%d", static_cast<int>(c.rank)); break; // ZERO..NINE ordinal == face value
    }
}

static void drawCardFace(TFT_eSPI &tft, int16_t x, int16_t y, int16_t w, int16_t h, UnoCardView card) {
    char label[4];
    cardLabel(card, label, sizeof(label));
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);

    if (card.isWild()) {
        int16_t hw = w / 2, hh = h / 2;
        tft.fillRect(x, y, hw, hh, TFT_RED);
        tft.fillRect(x + hw, y, w - hw, hh, TFT_YELLOW);
        tft.fillRect(x, y + hh, hw, h - hh, TFT_GREEN);
        tft.fillRect(x + hw, y + hh, w - hw, h - hh, TFT_BLUE);
        tft.drawRoundRect(x, y, w, h, 6, TFT_WHITE);

        int16_t badgeW = (w * 3) / 5, badgeH = (h * 2) / 5;
        int16_t bx = x + (w - badgeW) / 2, by = y + (h - badgeH) / 2;
        tft.fillRoundRect(bx, by, badgeW, badgeH, 4, TFT_BLACK);
        tft.setTextColor(TFT_WHITE, TFT_BLACK);
        tft.drawString(label, x + w / 2, y + h / 2);
    } else {
        uint16_t bg = colorFor(card.color);
        tft.fillRoundRect(x, y, w, h, 6, bg);
        tft.drawRoundRect(x, y, w, h, 6, TFT_WHITE);
        // Number/action cards on a light background (YELLOW) read poorly in
        // white -- use black text there, white everywhere else.
        uint16_t fg = (card.color == UnoColor::YELLOW) ? TFT_BLACK : TFT_WHITE;
        tft.setTextColor(fg, bg);
        tft.drawString(label, x + w / 2, y + h / 2);
    }
}

// ---------------------------------------------------------------------------
// Human hand layout -- shared by draw + hit-test so they can never disagree.
// Cards are laid out left-to-right, centered as a group; if the natural
// (non-overlapping) width would run past handMarginX of either screen edge,
// cards overlap just enough to still fit. Higher indices sit further right
// and are drawn last (i.e. on top), so hit-testing checks high-to-low.
// ---------------------------------------------------------------------------

static int16_t handAdvance(const UnoLayout &l, uint8_t count) {
    int16_t natural = l.handCardW + l.handGap;
    if (count <= 1) return natural;
    int16_t maxRowWidth = SCREEN_WIDTH - 2 * l.handMarginX;
    int16_t neededWidth = natural * (count - 1) + l.handCardW;
    if (neededWidth <= maxRowWidth) return natural;
    int16_t compressed = (maxRowWidth - l.handCardW) / (count - 1);
    return compressed < 4 ? 4 : compressed; // never compress into a fully unusable sliver
}

static int16_t handRowLeft(const UnoLayout &l, uint8_t count, int16_t advance) {
    if (count == 0) return SCREEN_WIDTH / 2;
    int16_t totalWidth = advance * (count - 1) + l.handCardW;
    return (SCREEN_WIDTH - totalWidth) / 2;
}

void drawUnoHumanHand(TFT_eSPI &tft, const UnoLayout &layout, const UnoCardView *cards, uint8_t count) {
    // Clear the whole row first -- a shrinking hand (a card just played)
    // must not leave stale card art from a previous, wider layout.
    tft.fillRect(0, layout.handY - 2, SCREEN_WIDTH, layout.handCardH + 4, COLOR_BG);

    int16_t advance = handAdvance(layout, count);
    int16_t left = handRowLeft(layout, count, advance);
    for (uint8_t i = 0; i < count; i++) {
        drawCardFace(tft, left + i * advance, layout.handY, layout.handCardW, layout.handCardH, cards[i]);
    }
}

bool hitTestUnoHumanHandCard(const UnoLayout &layout, uint8_t handCount, int16_t touchX, int16_t touchY, uint8_t &outIndex) {
    if (handCount == 0) return false;
    if (touchY < layout.handY || touchY >= layout.handY + layout.handCardH) return false;

    int16_t advance = handAdvance(layout, handCount);
    int16_t left = handRowLeft(layout, handCount, advance);
    for (int16_t i = static_cast<int16_t>(handCount) - 1; i >= 0; i--) {
        int16_t cardLeft = left + i * advance;
        if (touchX >= cardLeft && touchX < cardLeft + layout.handCardW) {
            outIndex = static_cast<uint8_t>(i);
            return true;
        }
    }
    return false;
}

// ---------------------------------------------------------------------------
// AI hand -- face-down count only.
// ---------------------------------------------------------------------------

void drawUnoAiHand(TFT_eSPI &tft, const UnoLayout &layout, uint8_t count) {
    tft.fillRect(0, layout.aiHandY - 2, SCREEN_WIDTH, layout.aiHandH + 4, COLOR_BG);

    char label[24];
    snprintf(label, sizeof(label), "AI hand: %d card%s", count, count == 1 ? "" : "s");
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.setTextColor(TFT_WHITE, COLOR_BG);
    tft.drawString(label, SCREEN_WIDTH / 2, layout.aiHandY + layout.aiHandH / 2);

    // A capped row of small card-back rectangles alongside the text purely
    // as a visual flourish -- never more than 10 drawn regardless of the
    // real count, so a large penalty-inflated AI hand can't run off-screen.
    uint8_t shown = count > 10 ? 10 : count;
    int16_t backW = 14, backH = 20, gap = 3;
    int16_t totalW = shown * backW + (shown > 0 ? (shown - 1) * gap : 0);
    int16_t startX = SCREEN_WIDTH / 2 - totalW - 90; // to the left of the count label
    for (uint8_t i = 0; i < shown; i++) {
        tft.fillRoundRect(startX + i * (backW + gap), layout.aiHandY + (layout.aiHandH - backH) / 2, backW, backH, 2, COLOR_CARD_BACK);
    }
}

// ---------------------------------------------------------------------------
// Discard / draw piles.
// ---------------------------------------------------------------------------

void drawUnoDiscardPile(TFT_eSPI &tft, const UnoLayout &layout, UnoCardView topCard, UnoColor activeColor) {
    tft.fillRect(layout.discardX - 3, layout.discardY - 3, layout.cardW + 6, layout.cardH + 6, COLOR_BG);
    drawCardFace(tft, layout.discardX, layout.discardY, layout.cardW, layout.cardH, topCard);
    if (topCard.isWild()) {
        // The card face itself stays neutral/quadrant-patterned -- an extra
        // colored ring shows which color is actually in effect right now.
        tft.drawRoundRect(layout.discardX - 2, layout.discardY - 2, layout.cardW + 4, layout.cardH + 4, 7, colorFor(activeColor));
    }
}

void drawUnoDrawPile(TFT_eSPI &tft, const UnoLayout &layout, uint8_t remainingCount) {
    tft.fillRect(layout.drawPileX - 3, layout.drawPileY - 3, layout.cardW + 6, layout.cardH + 6, COLOR_BG);
    tft.fillRoundRect(layout.drawPileX, layout.drawPileY, layout.cardW, layout.cardH, 6, COLOR_CARD_BACK);
    tft.drawRoundRect(layout.drawPileX, layout.drawPileY, layout.cardW, layout.cardH, 6, TFT_WHITE);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.setTextColor(TFT_WHITE, COLOR_CARD_BACK);
    tft.drawString("DRAW", layout.drawPileX + layout.cardW / 2, layout.drawPileY + layout.cardH / 2 - 10);
    char count[8];
    snprintf(count, sizeof(count), "%d", remainingCount);
    tft.drawString(count, layout.drawPileX + layout.cardW / 2, layout.drawPileY + layout.cardH / 2 + 14);
}

// ---------------------------------------------------------------------------
// Status bar / chrome / Play Again -- same shapes as Display.cpp's.
// ---------------------------------------------------------------------------

void drawUnoStaticChrome(TFT_eSPI &tft, const UnoLayout &layout) {
    tft.fillScreen(COLOR_BG);
    tft.fillRect(0, layout.statusY, SCREEN_WIDTH, layout.statusH, COLOR_STATUS_BG);
}

void drawUnoStatus(TFT_eSPI &tft, const UnoLayout &layout, const char *text) {
    tft.fillRect(0, layout.statusY, SCREEN_WIDTH, layout.statusH, COLOR_STATUS_BG);

    int16_t by = homeButtonTop(layout);
    tft.drawRoundRect(HOME_BTN_MARGIN, by, HOME_BTN_SIZE, HOME_BTN_SIZE, 4, TFT_WHITE);
    tft.setTextColor(TFT_WHITE, COLOR_STATUS_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString("<", HOME_BTN_MARGIN + HOME_BTN_SIZE / 2, by + HOME_BTN_SIZE / 2);

    int16_t textAreaX0 = HOME_BTN_MARGIN * 2 + HOME_BTN_SIZE;
    tft.setTextColor(COLOR_STATUS_TEXT, COLOR_STATUS_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.drawString(text, textAreaX0 + (SCREEN_WIDTH - textAreaX0) / 2, layout.statusY + layout.statusH / 2);
}

void drawUnoPlayAgainButton(TFT_eSPI &tft, const UnoLayout &layout) {
    tft.fillRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, COLOR_BUTTON_BG);
    tft.drawRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, TFT_WHITE);
    tft.setTextColor(COLOR_BUTTON_TEXT, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.drawString("Play Again", layout.buttonX + layout.buttonW / 2, layout.buttonY + layout.buttonH / 2);
}

void hideUnoPlayAgainButton(TFT_eSPI &tft, const UnoLayout &layout) {
    tft.fillRect(layout.buttonX - 2, layout.buttonY - 2, layout.buttonW + 4, layout.buttonH + 4, COLOR_BG);
}

bool hitTestUnoDrawPile(const UnoLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.drawPileX && touchX < layout.drawPileX + layout.cardW &&
           touchY >= layout.drawPileY && touchY < layout.drawPileY + layout.cardH;
}

bool hitTestUnoPlayAgainButton(const UnoLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.buttonX && touchX < layout.buttonX + layout.buttonW &&
           touchY >= layout.buttonY && touchY < layout.buttonY + layout.buttonH;
}

bool hitTestUnoHomeButton(const UnoLayout &layout, int16_t touchX, int16_t touchY) {
    int16_t by = homeButtonTop(layout);
    return touchX >= 0 && touchX < HOME_BTN_MARGIN + HOME_BTN_SIZE &&
           touchY >= by && touchY < by + HOME_BTN_SIZE;
}

// ---------------------------------------------------------------------------
// Color-choice overlay.
// ---------------------------------------------------------------------------

UnoColorOverlayLayout computeUnoColorOverlayLayout() {
    UnoColorOverlayLayout ol;
    ol.panelW = 280;
    ol.panelH = 100;
    ol.panelX = (SCREEN_WIDTH - ol.panelW) / 2;
    ol.panelY = (SCREEN_HEIGHT - ol.panelH) / 2;

    ol.swatchSize = 56;
    ol.swatchGap = 12;
    ol.swatchY = ol.panelY + (ol.panelH - ol.swatchSize) / 2;

    int16_t totalW = 4 * ol.swatchSize + 3 * ol.swatchGap;
    int16_t firstX = ol.panelX + (ol.panelW - totalW) / 2;
    for (uint8_t i = 0; i < 4; i++) ol.swatchX[i] = firstX + i * (ol.swatchSize + ol.swatchGap);

    return ol;
}

void drawUnoColorOverlay(TFT_eSPI &tft, const UnoColorOverlayLayout &overlay) {
    tft.fillRoundRect(overlay.panelX, overlay.panelY, overlay.panelW, overlay.panelH, 10, COLOR_OVERLAY_BG);
    tft.drawRoundRect(overlay.panelX, overlay.panelY, overlay.panelW, overlay.panelH, 10, TFT_WHITE);

    static const UnoColor ORDER[4] = { UnoColor::RED, UnoColor::YELLOW, UnoColor::GREEN, UnoColor::BLUE };
    for (uint8_t i = 0; i < 4; i++) {
        tft.fillRoundRect(overlay.swatchX[i], overlay.swatchY, overlay.swatchSize, overlay.swatchSize, 6, colorFor(ORDER[i]));
        tft.drawRoundRect(overlay.swatchX[i], overlay.swatchY, overlay.swatchSize, overlay.swatchSize, 6, TFT_WHITE);
    }
}

void hideUnoColorOverlay(TFT_eSPI &tft, const UnoColorOverlayLayout &overlay) {
    tft.fillRect(overlay.panelX - 2, overlay.panelY - 2, overlay.panelW + 4, overlay.panelH + 4, COLOR_BG);
}

bool hitTestUnoColorOverlay(const UnoColorOverlayLayout &overlay, int16_t touchX, int16_t touchY, UnoColor &outColor) {
    if (touchY < overlay.swatchY || touchY >= overlay.swatchY + overlay.swatchSize) return false;
    static const UnoColor ORDER[4] = { UnoColor::RED, UnoColor::YELLOW, UnoColor::GREEN, UnoColor::BLUE };
    for (uint8_t i = 0; i < 4; i++) {
        if (touchX >= overlay.swatchX[i] && touchX < overlay.swatchX[i] + overlay.swatchSize) {
            outColor = ORDER[i];
            return true;
        }
    }
    return false;
}
