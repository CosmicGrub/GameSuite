#include "MenuScreen.h"
#include "Config.h"

static const uint16_t COLOR_BG          = TFT_BLACK;
static const uint16_t COLOR_TITLE_BG    = TFT_NAVY;
static const uint16_t COLOR_TITLE_TEXT  = TFT_WHITE;
static const uint16_t COLOR_TILE_BG     = TFT_DARKGREEN;
static const uint16_t COLOR_TILE_TEXT   = TFT_WHITE;
static const uint16_t COLOR_TILE_SOON_BG   = TFT_DARKGREY;
static const uint16_t COLOR_TILE_SOON_TEXT = TFT_WHITE;
static const uint16_t COLOR_SLEEP_BG       = TFT_DARKGREY;

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

MenuLayout computeMenuLayout(uint8_t gameCount) {
    MenuLayout l;
    l.titleY = 0;
    l.titleH = 40;
    l.tileGap = 10;
    l.tileX = 20;
    l.tileW = SCREEN_WIDTH - 40;
    l.tileH = 44;
    l.tileY = l.titleY + l.titleH + 14;
    (void)gameCount; // tile geometry is fixed; only the vertical list length varies, and that's the caller's job to bound
    return l;
}

static int16_t tileTop(const MenuLayout &l, uint8_t index) {
    return l.tileY + index * (l.tileH + l.tileGap);
}

void drawMenuChrome(TFT_eSPI &tft, const MenuLayout &layout) {
    tft.fillScreen(COLOR_BG);
    tft.fillRect(0, layout.titleY, SCREEN_WIDTH, layout.titleH, COLOR_TITLE_BG);
    tft.setTextColor(COLOR_TITLE_TEXT, COLOR_TITLE_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.drawString(ARCADE_TITLE, SCREEN_WIDTH / 2, layout.titleY + layout.titleH / 2);

    // "Sleep" button, top-right of the title bar -- see MenuScreen.h for why
    // this exists and why it's menu-only.
    int16_t sx = sleepButtonX(), sy = sleepButtonY(layout);
    tft.fillRoundRect(sx, sy, SLEEP_BTN_W, SLEEP_BTN_H, 6, COLOR_SLEEP_BG);
    tft.drawRoundRect(sx, sy, SLEEP_BTN_W, SLEEP_BTN_H, 6, TFT_WHITE);
    tft.setTextColor(TFT_WHITE, COLOR_SLEEP_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString("Sleep", sx + SLEEP_BTN_W / 2, sy + SLEEP_BTN_H / 2);
}

void drawMenuTile(TFT_eSPI &tft, const MenuLayout &layout, uint8_t index, const char *label, bool enabled) {
    int16_t y = tileTop(layout, index);
    uint16_t bg = enabled ? COLOR_TILE_BG : COLOR_TILE_SOON_BG;
    uint16_t fg = enabled ? COLOR_TILE_TEXT : COLOR_TILE_SOON_TEXT;

    tft.fillRoundRect(layout.tileX, y, layout.tileW, layout.tileH, 8, bg);
    tft.drawRoundRect(layout.tileX, y, layout.tileW, layout.tileH, 8, TFT_WHITE);
    tft.setTextColor(fg, bg);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);

    if (enabled) {
        tft.drawString(label, layout.tileX + layout.tileW / 2, y + layout.tileH / 2);
    } else {
        // Two lines within the tile: the game's name, then a smaller
        // "coming soon" note -- this keeps a disabled tile legible as "not
        // yet, but planned" rather than looking broken or randomly greyed.
        tft.drawString(label, layout.tileX + layout.tileW / 2, y + layout.tileH / 2 - 8);
        tft.setTextSize(1);
        tft.drawString("coming soon", layout.tileX + layout.tileW / 2, y + layout.tileH / 2 + 12);
    }
}

bool hitTestMenuTile(const MenuLayout &layout, uint8_t gameCount, int16_t touchX, int16_t touchY, uint8_t &outIndex) {
    if (touchX < layout.tileX || touchX >= layout.tileX + layout.tileW) return false;
    for (uint8_t i = 0; i < gameCount; i++) {
        int16_t y = tileTop(layout, i);
        if (touchY >= y && touchY < y + layout.tileH) {
            outIndex = i;
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
