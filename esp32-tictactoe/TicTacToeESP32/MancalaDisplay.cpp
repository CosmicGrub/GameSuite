#include "MancalaDisplay.h"
#include "Config.h"
#include "Theme.h"
#include <cstdio> // snprintf -- stone-count text

// Colors go through Theme.h's shared palette so the whole arcade reads as
// one consistent product -- human pits/store teal-bordered, AI amber-
// bordered, exactly like every other game's human/AI pair.
static const uint16_t COLOR_BG           = THEME_BG;
static const uint16_t COLOR_PIT_FILL     = THEME_SURFACE;
static const uint16_t COLOR_PIT_TEXT     = THEME_TEXT;
static const uint16_t COLOR_HUMAN_ACCENT = THEME_HUMAN;
static const uint16_t COLOR_AI_ACCENT    = THEME_AI;
static const uint16_t COLOR_HIGHLIGHT    = THEME_SUCCESS;
static const uint16_t COLOR_DANGER       = THEME_DANGER;
static const uint16_t COLOR_STATUS_BG    = THEME_SURFACE;
static const uint16_t COLOR_STATUS_TEXT  = THEME_TEXT;
static const uint16_t COLOR_BUTTON_BG    = THEME_SURFACE_ALT;
static const uint16_t COLOR_BUTTON_TEXT  = THEME_TEXT;

// Same fixed "back to menu" button every other game's Display module draws,
// sized off the status bar's own height rather than a magic screen-relative
// position.
static const int16_t HOME_BTN_SIZE = 30;
static const int16_t HOME_BTN_MARGIN = 2;

static int16_t homeButtonTop(const MancalaLayout &layout) {
    return layout.statusY + (layout.statusH - HOME_BTN_SIZE) / 2;
}

MancalaLayout computeMancalaLayout() {
    MancalaLayout l{};
    l.statusY = 0;
    l.statusH = 36;

    int16_t availH = SCREEN_HEIGHT - l.statusH;

    l.storeW = 70;
    int16_t pitGridW = SCREEN_WIDTH - 2 * l.storeW;
    l.colStride = pitGridW / 6;
    l.rowStride = availH / 2;
    int16_t cell = (l.colStride < l.rowStride) ? l.colStride : l.rowStride;
    l.pitSize = (int16_t)(cell * 0.72f); // circle diameter, leaving a visible gap between pits

    l.boardX = l.storeW;
    l.boardY = l.statusY + l.statusH;

    l.storeY = l.boardY;
    l.storeH = l.rowStride * 2;
    l.aiStoreX = 0;
    l.humanStoreX = l.boardX + l.colStride * 6;

    l.buttonW = 160;
    l.buttonH = 44;
    l.buttonX = (SCREEN_WIDTH - l.buttonW) / 2;
    l.buttonY = l.boardY + (l.storeH - l.buttonH) / 2;

    l.difficultyW = 92;
    l.difficultyH = 26;
    l.difficultyX = SCREEN_WIDTH - l.difficultyW - 6;
    l.difficultyY = l.statusY + (l.statusH - l.difficultyH) / 2;
    return l;
}

// Center pixel of playable pit `pitIndex` (0-5 or 7-12 -- NOT a store,
// callers branch on that separately since a store's own geometry comes
// from layout.humanStoreX/aiStoreX/storeY/storeW/storeH instead). Column c
// (0..5) holds human pit `c` directly below AI pit `12-c` -- see
// MancalaDisplay.h's header comment for why this specific mapping keeps
// each column an "opposite pit" pair.
static void pitCenter(const MancalaLayout &l, uint8_t pitIndex, int16_t &cx, int16_t &cy) {
    uint8_t col = (pitIndex <= 5) ? pitIndex : (uint8_t)(12 - pitIndex);
    bool humanRow = pitIndex <= 5;
    cx = l.boardX + col * l.colStride + l.colStride / 2;
    cy = humanRow ? (l.boardY + l.rowStride + l.rowStride / 2) : (l.boardY + l.rowStride / 2);
}

void drawMancalaStaticChrome(TFT_eSPI &tft, const MancalaLayout &layout) {
    tft.fillScreen(COLOR_BG);
}

void drawMancalaStatus(TFT_eSPI &tft, const MancalaLayout &layout, const char *text, CpuDifficulty difficulty) {
    tft.fillRect(0, layout.statusY, SCREEN_WIDTH, layout.statusH, COLOR_STATUS_BG);

    // The home button and difficulty chip are drawn as part of every status
    // update (rather than once at round start) because the fillRect above
    // would otherwise erase them on the very next status update -- same
    // reasoning as CheckersDisplay.cpp's drawCheckersStatus.
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

void drawMancalaPit(TFT_eSPI &tft, const MancalaLayout &layout, uint8_t pitIndex, uint8_t stoneCount) {
    char buf[4];
    snprintf(buf, sizeof(buf), "%u", (unsigned)stoneCount);

    if (pitIndex == MANCALA_HUMAN_STORE || pitIndex == MANCALA_AI_STORE) {
        bool human = (pitIndex == MANCALA_HUMAN_STORE);
        int16_t x = human ? layout.humanStoreX : layout.aiStoreX;
        uint16_t borderColor = human ? COLOR_HUMAN_ACCENT : COLOR_AI_ACCENT;
        int16_t pad = 6;
        tft.fillRect(x, layout.storeY, layout.storeW, layout.storeH, COLOR_BG);
        tft.fillRoundRect(x + pad, layout.storeY + pad, layout.storeW - 2 * pad, layout.storeH - 2 * pad, 10, COLOR_PIT_FILL);
        tft.drawRoundRect(x + pad, layout.storeY + pad, layout.storeW - 2 * pad, layout.storeH - 2 * pad, 10, borderColor);
        tft.setTextColor(COLOR_PIT_TEXT, COLOR_PIT_FILL);
        tft.setTextDatum(MC_DATUM);
        tft.setTextSize(1);
        tft.drawString(buf, x + layout.storeW / 2, layout.storeY + layout.storeH / 2);
        return;
    }

    int16_t cx, cy;
    pitCenter(layout, pitIndex, cx, cy);
    bool human = pitIndex <= 5;
    uint16_t borderColor = human ? COLOR_HUMAN_ACCENT : COLOR_AI_ACCENT;
    int16_t r = layout.pitSize / 2;

    // Erase this pit's own whole cell (its column/row pitch, not just the
    // circle) before redrawing -- same always-repaint-fresh convention
    // drawCheckersSquare uses, wide enough that a highlight ring from
    // animateMancalaSow() never leaves a visible remnant at the cell's
    // corners once this runs.
    tft.fillRect(cx - layout.colStride / 2, cy - layout.rowStride / 2, layout.colStride, layout.rowStride, COLOR_BG);
    tft.fillCircle(cx, cy, r, COLOR_PIT_FILL);
    tft.drawCircle(cx, cy, r, borderColor);
    tft.setTextColor(COLOR_PIT_TEXT, COLOR_PIT_FILL);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(buf, cx, cy);
}

void drawMancalaBoard(TFT_eSPI &tft, const MancalaLayout &layout, const MancalaBoard &board) {
    for (uint8_t i = 0; i < MANCALA_PIT_COUNT; i++) {
        drawMancalaPit(tft, layout, i, board.stonesAt(i));
    }
}

void drawMancalaPlayAgainButton(TFT_eSPI &tft, const MancalaLayout &layout) {
    tft.fillRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, COLOR_BUTTON_BG);
    tft.drawRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, TFT_WHITE);
    tft.setTextColor(COLOR_BUTTON_TEXT, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString("Play Again", layout.buttonX + layout.buttonW / 2, layout.buttonY + layout.buttonH / 2);
}

void hideMancalaPlayAgainButton(TFT_eSPI &tft, const MancalaLayout &layout) {
    tft.fillRect(layout.buttonX - 2, layout.buttonY - 2, layout.buttonW + 4, layout.buttonH + 4, COLOR_BG);
}

void animateMancalaSow(TFT_eSPI &tft, const MancalaLayout &layout, const MancalaBoard &board) {
    uint8_t n = board.lastSowPathLength();
    if (n < 2) return; // defensive -- a real sow always visits at least the source pit plus one landing pit

    // A highlight sweep through every pit the sow actually visited, not a
    // stone-by-stone counter animation: `board` already holds the sow's
    // FINAL settled counts by the time this runs (see this function's own
    // header comment for why), so drawMancalaPit() below always shows the
    // real final number for whichever pit the cascade is currently on,
    // slightly ahead of a literal "one stone lands at a time" replay for
    // any pit later in the path -- a deliberate simplification rather than
    // reconstructing every intermediate board state. Element 0 is the pit
    // stones were picked up FROM (already empty), so the visible cascade
    // starts at element 1, the first pit a stone actually landed in.
    static const uint16_t STEP_MS = 70;
    for (uint8_t i = 1; i < n; i++) {
        uint8_t pit = board.lastSowPathPit(i);
        int16_t cx, cy, r;
        if (pit == MANCALA_HUMAN_STORE || pit == MANCALA_AI_STORE) {
            bool human = (pit == MANCALA_HUMAN_STORE);
            cx = (human ? layout.humanStoreX : layout.aiStoreX) + layout.storeW / 2;
            cy = layout.storeY + layout.storeH / 2;
            r = layout.storeW / 2 - 4;
        } else {
            pitCenter(layout, pit, cx, cy);
            r = layout.pitSize / 2 + 3;
        }
        tft.drawCircle(cx, cy, r, COLOR_HIGHLIGHT);
        tft.drawCircle(cx, cy, r - 1, COLOR_HIGHLIGHT);
        delay(STEP_MS);
        drawMancalaPit(tft, layout, pit, board.stonesAt(pit));
    }

    if (board.lastMoveCaptured()) {
        // A brief hold + highlight flash on the two swept pits before a
        // caller's own final drawMancalaBoard() shows them empty -- the
        // ESP32-honest analog of Checkers' capture hit-stop (a plain
        // delay(), not a shader), same reasoning as
        // CheckersDisplay.cpp/animateCheckersMove()'s own CAPTURE_HITSTOP_MS.
        static const uint16_t CAPTURE_HITSTOP_MS = 120;
        int16_t cx1, cy1, cx2, cy2;
        pitCenter(layout, board.lastCaptureLandingPit(), cx1, cy1);
        pitCenter(layout, board.lastCaptureOppositePit(), cx2, cy2);
        int16_t r = layout.pitSize / 2 + 3;
        tft.drawCircle(cx1, cy1, r, COLOR_DANGER);
        tft.drawCircle(cx2, cy2, r, COLOR_DANGER);
        delay(CAPTURE_HITSTOP_MS);
    }
}

bool hitTestMancalaPit(const MancalaLayout &layout, int16_t touchX, int16_t touchY, uint8_t &outPitIndex) {
    int16_t gridW = layout.colStride * 6;
    int16_t gridH = layout.rowStride * 2;
    if (touchX < layout.boardX || touchX >= layout.boardX + gridW) return false;
    if (touchY < layout.boardY || touchY >= layout.boardY + gridH) return false;

    uint8_t col = (uint8_t)((touchX - layout.boardX) / layout.colStride);
    uint8_t row = (uint8_t)((touchY - layout.boardY) / layout.rowStride);
    if (col > 5 || row > 1) return false; // defensive -- integer division should already keep these in range

    outPitIndex = (row == 0) ? (uint8_t)(12 - col) : col; // row 0 (top) = AI pits, row 1 (bottom) = human pits
    return true;
}

bool hitTestMancalaPlayAgainButton(const MancalaLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.buttonX && touchX < layout.buttonX + layout.buttonW &&
           touchY >= layout.buttonY && touchY < layout.buttonY + layout.buttonH;
}

bool hitTestMancalaHomeButton(const MancalaLayout &layout, int16_t touchX, int16_t touchY) {
    int16_t by = homeButtonTop(layout);
    return touchX >= 0 && touchX < HOME_BTN_MARGIN + HOME_BTN_SIZE &&
           touchY >= by && touchY < by + HOME_BTN_SIZE;
}

bool hitTestMancalaDifficultyButton(const MancalaLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.difficultyX && touchX < layout.difficultyX + layout.difficultyW &&
           touchY >= layout.difficultyY && touchY < layout.difficultyY + layout.difficultyH;
}
