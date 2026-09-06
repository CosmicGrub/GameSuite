#include "Chrome.h"
#include "Config.h"

static const uint16_t COLOR_BAR_BG   = TFT_NAVY;
static const uint16_t COLOR_BAR_TEXT = TFT_WHITE;

const int16_t CHROME_BAR_HEIGHT = 36;

// Fixed-size Home button in the bar's left corner, sized off the bar's own
// height rather than a magic screen-relative position, so it stays put if
// CHROME_BAR_HEIGHT ever changes.
static const int16_t HOME_BTN_SIZE = 30;
static const int16_t HOME_BTN_MARGIN = 2;

static int16_t homeButtonTop() {
    return (CHROME_BAR_HEIGHT - HOME_BTN_SIZE) / 2;
}

void drawChromeBar(TFT_eSPI &tft, const char *statusText) {
    tft.fillRect(0, 0, SCREEN_WIDTH, CHROME_BAR_HEIGHT, COLOR_BAR_BG);

    int16_t by = homeButtonTop();
    tft.drawRoundRect(HOME_BTN_MARGIN, by, HOME_BTN_SIZE, HOME_BTN_SIZE, 4, TFT_WHITE);
    tft.setTextColor(TFT_WHITE, COLOR_BAR_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString("<", HOME_BTN_MARGIN + HOME_BTN_SIZE / 2, by + HOME_BTN_SIZE / 2);

    // Status text is centered in the remaining width to the right of the
    // Home button, not the full screen width, so it never overlaps it.
    int16_t textAreaX0 = HOME_BTN_MARGIN * 2 + HOME_BTN_SIZE;
    tft.setTextColor(COLOR_BAR_TEXT, COLOR_BAR_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.drawString(statusText, textAreaX0 + (SCREEN_WIDTH - textAreaX0) / 2, CHROME_BAR_HEIGHT / 2);
}

bool hitTestChromeHome(int16_t touchX, int16_t touchY) {
    int16_t by = homeButtonTop();
    return touchX >= 0 && touchX < HOME_BTN_MARGIN + HOME_BTN_SIZE &&
           touchY >= by && touchY < by + HOME_BTN_SIZE;
}
