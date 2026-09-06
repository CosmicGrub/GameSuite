#include "MenuScreen.h"
#include "Config.h"
#include "Theme.h"

// Fixed-size "Sleep" button in the title bar's top-right corner.
static const int16_t SLEEP_BTN_W = 70;
static const int16_t SLEEP_BTN_H = 30;
static const int16_t SLEEP_BTN_MARGIN = 4;

static int16_t sleepButtonX() {
    return SCREEN_WIDTH - SLEEP_BTN_MARGIN - SLEEP_BTN_W;
}

static int16_t sleepButtonY(const MenuLayout &l) {
    return l.titleY + (l.titleH - SLEEP_BTN_H) / 2;
}

// A narrow column reserved to the right of the tile list for the scroll
// arrows -- tiles are narrower than the full width to make room for it.
static const int16_t ARROW_SIZE = 40;
static const int16_t ARROW_MARGIN = 10;

static int16_t arrowX() {
    return SCREEN_WIDTH - ARROW_MARGIN - ARROW_SIZE;
}

static int16_t upArrowY(const MenuLayout &l) {
    return l.tileY;
}

static int16_t downArrowY(const MenuLayout &l) {
    int16_t listH = l.maxVisibleTiles * l.tileH + (l.maxVisibleTiles - 1) * l.tileGap;
    return l.tileY + listH - ARROW_SIZE;
}

// "SOON" badge shown on a disabled tile -- see drawMenuTile's comment on why
// this replaced a cramped second line of text.
static const int16_t BADGE_W = 60;
static const int16_t BADGE_H = 22;

MenuLayout computeMenuLayout(uint8_t gameCount) {
    MenuLayout l;
    l.titleY = 0;
    l.titleH = 56; // tall enough for the large hero font's own metrics -- see drawMenuChrome
    l.tileGap = 10;
    l.tileX = 20;
    l.tileH = 44;
    l.tileY = l.titleY + l.titleH + 14;
    l.tileW = SCREEN_WIDTH - l.tileX - ARROW_MARGIN - ARROW_SIZE - ARROW_MARGIN;

    int16_t availableH = SCREEN_HEIGHT - l.tileY;
    uint8_t fits = (availableH + l.tileGap) / (l.tileH + l.tileGap);
    if (fits < 1) fits = 1;
    l.maxVisibleTiles = (fits < gameCount) ? fits : gameCount; // never reserve more room than there are games to show
    return l;
}

static int16_t tileTop(const MenuLayout &l, uint8_t slot) {
    return l.tileY + slot * (l.tileH + l.tileGap);
}

static void drawArrowButton(TFT_eSPI &tft, int16_t x, int16_t y, const char *glyph, bool enabled) {
    uint16_t bg = enabled ? THEME_SURFACE_ALT : THEME_SURFACE;
    uint16_t fg = enabled ? THEME_TEXT : THEME_TEXT_DIM;
    tft.fillRoundRect(x, y, ARROW_SIZE, ARROW_SIZE, 6, bg);
    tft.drawRoundRect(x, y, ARROW_SIZE, ARROW_SIZE, 6, THEME_BORDER);
    tft.setTextColor(fg, bg);
    tft.setTextDatum(MC_DATUM);
    tft.drawString(glyph, x + ARROW_SIZE / 2, y + ARROW_SIZE / 2);
}

void drawMenuChrome(TFT_eSPI &tft, const MenuLayout &layout, bool canScrollUp, bool canScrollDown) {
    tft.fillScreen(THEME_BG);
    tft.fillRect(0, layout.titleY, SCREEN_WIDTH, layout.titleH, THEME_SURFACE);

    // The one "hero" moment that gets the large font -- everywhere else in
    // this arcade uses the small font left loaded by setup() as the default,
    // so switch to large and immediately back rather than leaving it loaded.
    themeLoadFontLarge(tft);
    tft.setTextColor(THEME_TEXT, THEME_SURFACE);
    tft.setTextDatum(MC_DATUM);
    tft.drawString(ARCADE_TITLE, SCREEN_WIDTH / 2, layout.titleY + layout.titleH / 2);
    themeLoadFontSmall(tft);

    // "Sleep" button, top-right of the title bar -- see MenuScreen.h for why
    // this exists and why it's menu-only.
    int16_t sx = sleepButtonX(), sy = sleepButtonY(layout);
    tft.fillRoundRect(sx, sy, SLEEP_BTN_W, SLEEP_BTN_H, 6, THEME_SURFACE_ALT);
    tft.drawRoundRect(sx, sy, SLEEP_BTN_W, SLEEP_BTN_H, 6, THEME_BORDER);
    tft.setTextColor(THEME_TEXT, THEME_SURFACE_ALT);
    tft.setTextDatum(MC_DATUM);
    tft.drawString("Sleep", sx + SLEEP_BTN_W / 2, sy + SLEEP_BTN_H / 2);

    // Scroll arrows -- dimmed (same treatment as a "coming soon" tile) when
    // there's nothing further in that direction, rather than hidden, so the
    // list's total extent stays visually obvious even at either end.
    drawArrowButton(tft, arrowX(), upArrowY(layout), "^", canScrollUp);
    drawArrowButton(tft, arrowX(), downArrowY(layout), "v", canScrollDown);
}

void drawMenuTile(TFT_eSPI &tft, const MenuLayout &layout, uint8_t slot, const char *label, bool enabled) {
    int16_t y = tileTop(layout, slot);
    uint16_t bg = enabled ? THEME_SURFACE_ALT : THEME_SURFACE;
    uint16_t fg = enabled ? THEME_TEXT : THEME_TEXT_DIM;

    tft.fillRoundRect(layout.tileX, y, layout.tileW, layout.tileH, 8, bg);
    tft.drawRoundRect(layout.tileX, y, layout.tileW, layout.tileH, 8, THEME_BORDER);
    tft.setTextColor(fg, bg);
    // Left-aligned reads as a real menu list (with room for the "SOON" badge
    // on the right of a disabled tile) rather than centered, which is why
    // this differs from the center-everything convention used elsewhere.
    tft.setTextDatum(ML_DATUM);
    tft.drawString(label, layout.tileX + 16, y + layout.tileH / 2);

    if (!enabled) {
        // A small badge instead of a cramped second line of text -- the
        // smooth font's one small size doesn't leave room for two stacked
        // lines within a 44px-tall tile the way the old tiny bitmap font did.
        int16_t bx = layout.tileX + layout.tileW - BADGE_W - 10;
        int16_t by = y + (layout.tileH - BADGE_H) / 2;
        tft.fillRoundRect(bx, by, BADGE_W, BADGE_H, 6, THEME_BG);
        tft.drawRoundRect(bx, by, BADGE_W, BADGE_H, 6, THEME_TEXT_DIM);
        tft.setTextColor(THEME_TEXT_DIM, THEME_BG);
        tft.setTextDatum(MC_DATUM);
        tft.drawString("SOON", bx + BADGE_W / 2, by + BADGE_H / 2);
    }
}

bool hitTestMenuTile(const MenuLayout &layout, uint8_t visibleCount, int16_t touchX, int16_t touchY, uint8_t &outSlot) {
    if (touchX < layout.tileX || touchX >= layout.tileX + layout.tileW) return false;
    for (uint8_t slot = 0; slot < visibleCount; slot++) {
        int16_t y = tileTop(layout, slot);
        if (touchY >= y && touchY < y + layout.tileH) {
            outSlot = slot;
            return true;
        }
    }
    return false; // landed in a gap between tiles, or below the last one
}

bool hitTestSleepButton(const MenuLayout &layout, int16_t touchX, int16_t touchY) {
    int16_t sx = sleepButtonX(), sy = sleepButtonY(layout);
    return touchX >= sx && touchX < sx + SLEEP_BTN_W &&
           touchY >= sy && touchY < sy + SLEEP_BTN_H;
}

bool hitTestScrollUp(const MenuLayout &layout, int16_t touchX, int16_t touchY) {
    int16_t x = arrowX(), y = upArrowY(layout);
    return touchX >= x && touchX < x + ARROW_SIZE && touchY >= y && touchY < y + ARROW_SIZE;
}

bool hitTestScrollDown(const MenuLayout &layout, int16_t touchX, int16_t touchY) {
    int16_t x = arrowX(), y = downArrowY(layout);
    return touchX >= x && touchX < x + ARROW_SIZE && touchY >= y && touchY < y + ARROW_SIZE;
}
