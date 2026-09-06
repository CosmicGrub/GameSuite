#include "Theme.h"
#include <Arduino.h> // for PROGMEM, which the font headers below use directly -- never rely on it arriving transitively

// The raw font arrays are only ever touched here -- every other file goes
// through themeLoadFontSmall()/themeLoadFontLarge() instead, so nothing else
// needs to #include these (each is a `const` array with internal linkage per
// translation unit; keeping them to one .cpp avoids duplicating ~13KB/~53KB
// of flash per file that would otherwise include them directly).
#include "NotoSansBold15.h"
#include "NotoSansBold36.h"

void themeLoadFontSmall(TFT_eSPI &tft) {
    tft.loadFont(NotoSansBold15);
}

void themeLoadFontLarge(TFT_eSPI &tft) {
    tft.loadFont(NotoSansBold36);
}

void themeUnloadFont(TFT_eSPI &tft) {
    tft.unloadFont();
}
