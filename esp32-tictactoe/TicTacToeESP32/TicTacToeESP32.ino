// GameSuite Arcade for the Hosyond 4.0" ESP32 Display Module (ST7796S,
// resistive touch) -- an "OS" shell (boot -> home menu -> pick a game) around
// what started as a single Tic-Tac-Toe sketch. The file/folder are still
// named TicTacToeESP32 because Arduino requires a sketch's main .ino to share
// its folder's name and native_test/ already depends on that path -- renaming
// either would break more than it's worth for what's still, under the hood,
// one small firmware image.
//
// This is a from-scratch firmware project, NOT a port of GameSuite's Android app --
// an ESP32 has no Android, no JVM, and a few hundred KB of RAM versus a phone's
// gigabytes, so none of that code can run here. What carries over is the game
// design: the same minimax AI (see GameLogic.cpp), the same engine/screen split
// GameSuite's own architecture uses everywhere, the same "human vs. an unbeatable
// bot" feel. See README.md for setup, wiring notes, and troubleshooting.
//
// Requires (Arduino IDE -> Tools -> Manage Libraries):
//   - "TFT_eSPI" by Bodmer
// ...with UserSetup/User_Setup.h from this project copied into that library's
// folder BEFORE compiling -- see README.md, step 3. Board: "ESP32 Dev Module"
// under the espressif esp32 board package.

#include <TFT_eSPI.h>
#include <Preferences.h>
#include "Config.h"
#include "GameLogic.h"
#include "Display.h"
#include "MenuScreen.h"

TFT_eSPI tft = TFT_eSPI();
Preferences prefs;

TicTacToeBoard board;
Layout layout;

// ---- Arcade home menu ----
// Adding a game means: write its GameLogic/Display pair, add one entry here,
// flip its `enabled` flag once it's built -- no other wiring needed. Mancala
// and Dominoes are listed disabled ("coming soon") because the README's own
// roadmap already named them as the next-best fits for this hardware; a
// player sees the real roadmap on the device itself instead of it being
// invisible until built.
struct MenuGame {
    const char *label;
    bool enabled;
};
static const MenuGame MENU_GAMES[] = {
    {"Tic-Tac-Toe", true},
    {"Mancala", false},
    {"Dominoes", false},
};
static const uint8_t MENU_GAME_COUNT = sizeof(MENU_GAMES) / sizeof(MENU_GAMES[0]);
static const uint8_t TICTACTOE_GAME_INDEX = 0;

enum class AppState { MENU, TICTACTOE };
AppState appState = AppState::MENU;
MenuLayout menuLayout;

// Drives the short pause between the human's move and the AI's reply, and
// between a finished round and the board accepting the next tap -- purely
// cosmetic (an instant AI reply reads as the game not having registered your
// tap at all), matching why GameSuite's Android bots use a similar delay.
static const unsigned long AI_MOVE_DELAY_MS = 450;
unsigned long aiMoveDueAt = 0;
bool aiMovePending = false;

bool roundOver = false;

void loadOrRunCalibration();
void startNewRound();
void refreshStatusText();
void handleTouch();
void checkForRoundEnd();

void enterMenu() {
    appState = AppState::MENU;
    menuLayout = computeMenuLayout(MENU_GAME_COUNT);
    drawMenuChrome(tft, menuLayout);
    for (uint8_t i = 0; i < MENU_GAME_COUNT; i++) {
        drawMenuTile(tft, menuLayout, i, MENU_GAMES[i].label, MENU_GAMES[i].enabled);
    }
    Serial.println("[ArcadeOS] at home menu");
}

void enterTicTacToe() {
    appState = AppState::TICTACTOE;
    layout = computeLayout();
    startNewRound();
    Serial.println("[ArcadeOS] entered Tic-Tac-Toe");
}

void setup() {
    Serial.begin(115200);
    delay(300); // let the USB-serial link settle before the first print
    Serial.println();
    Serial.println("[ArcadeOS] booting");

    tft.init();
    Serial.println("[ArcadeOS] tft.init() done");
    tft.setRotation(SCREEN_ROTATION);
    Serial.printf("[ArcadeOS] rotation set to %d (expect %dx%d landscape)\n",
                  SCREEN_ROTATION, SCREEN_WIDTH, SCREEN_HEIGHT);

    loadOrRunCalibration();
    Serial.println("[ArcadeOS] calibration ready");

    enterMenu();
    Serial.println("[ArcadeOS] setup complete, entering loop()");
}

void loop() {
    handleTouch();

    // aiMovePending only ever becomes true from within handleTouch()'s
    // TICTACTOE branch, so gating on appState here is belt-and-suspenders,
    // not load-bearing -- kept explicit so this block can never fire a stale
    // AI move into the menu screen if that invariant ever changes.
    if (appState == AppState::TICTACTOE && aiMovePending && millis() >= aiMoveDueAt) {
        aiMovePending = false;
        board.playAi();
        for (uint8_t i = 0; i < 9; i++) drawCell(tft, layout, i, board.at(i));
        checkForRoundEnd();
    }

    // A slow heartbeat so a remote/serial-only observer can tell the sketch is
    // still alive in loop() (vs. having crashed/reset) without flooding the
    // log the way printing every single loop() iteration would.
    static unsigned long lastHeartbeat = 0;
    if (millis() - lastHeartbeat > 3000) {
        lastHeartbeat = millis();
        Serial.printf("[ArcadeOS] alive, uptime=%lus, state=%s, humanTurn=%d, roundOver=%d\n",
                      millis() / 1000, appState == AppState::MENU ? "menu" : "tictactoe",
                      board.isHumanTurn(), roundOver);
    }
}

void checkForRoundEnd() {
    RoundResult r = board.result();
    if (r == RoundResult::IN_PROGRESS) {
        refreshStatusText();
        return;
    }
    roundOver = true;
    int8_t line[3];
    board.winningLine(line);
    drawWinningLine(tft, layout, line);

    switch (r) {
        case RoundResult::HUMAN_WINS: drawStatus(tft, layout, "You win!"); break;
        case RoundResult::AI_WINS:    drawStatus(tft, layout, "ESP32 wins!"); break;
        case RoundResult::DRAW:       drawStatus(tft, layout, "Draw!"); break;
        default: break;
    }
    drawPlayAgainButton(tft, layout);
}

void startNewRound() {
    board.reset();
    roundOver = false;
    aiMovePending = false;
    drawStaticChrome(tft, layout);
    for (uint8_t i = 0; i < 9; i++) drawCell(tft, layout, i, EMPTY);
    refreshStatusText();
}

void refreshStatusText() {
    drawStatus(tft, layout, board.isHumanTurn() ? "Your turn (X)" : "ESP32 thinking...");
}

void handleTouch() {
    // TFT_eSPI's own getTouch() reads the XPT2046 over the same/adjacent SPI bus
    // (see UserSetup/User_Setup.h) and applies the calibration data already
    // loaded via setTouch() in loadOrRunCalibration() -- no separate touch
    // library needed. Returns screen-space pixel coordinates directly.
    uint16_t tx, ty;
    if (!tft.getTouch(&tx, &ty)) return;
    Serial.printf("[ArcadeOS] touch at (%u,%u)\n", tx, ty);

    // A touch is a single edge-triggered event as far as this game cares (no
    // drag gestures) -- debounce by requiring the panel to report "not
    // touched" again before the next tap counts, so holding a finger down
    // doesn't re-fire the same tap dozens of times per second.
    static bool wasTouched = false;
    if (wasTouched) { wasTouched = tft.getTouch(&tx, &ty); return; }
    wasTouched = true;

    if (appState == AppState::MENU) {
        uint8_t idx;
        if (!hitTestMenuTile(menuLayout, MENU_GAME_COUNT, tx, ty, idx)) return;
        if (!MENU_GAMES[idx].enabled) return; // "coming soon" tile -- silently inert, same as any other illegal tap in this project
        if (idx == TICTACTOE_GAME_INDEX) enterTicTacToe();
        return;
    }

    // appState == TICTACTOE from here on.
    if (hitTestHomeButton(layout, tx, ty)) {
        enterMenu();
        return;
    }

    if (roundOver) {
        if (hitTestPlayAgainButton(layout, tx, ty)) {
            startNewRound();
        }
        return;
    }

    if (aiMovePending || !board.isHumanTurn()) return; // ignore taps while the AI is "thinking"

    uint8_t cell;
    if (!hitTestCell(layout, tx, ty, cell)) return;
    if (!board.playHuman(cell)) return; // illegal tap (cell taken) -- silently ignored, same as GameSuite's own games do

    drawCell(tft, layout, cell, HUMAN);
    RoundResult r = board.result();
    if (r != RoundResult::IN_PROGRESS) {
        checkForRoundEnd();
        return;
    }
    refreshStatusText();
    aiMovePending = true;
    aiMoveDueAt = millis() + AI_MOVE_DELAY_MS;
}

void loadOrRunCalibration() {
    prefs.begin(CALIBRATION_NAMESPACE, false);

    uint16_t calData[5];
    bool haveSavedCalibration = prefs.isKey(CALIBRATION_KEY) &&
        prefs.getBytesLength(CALIBRATION_KEY) == sizeof(calData);

    // Hold the very top-left corner of the screen down through power-on/reset
    // to force recalibration even if a (possibly stale, e.g. after swapping
    // panels) calibration is already saved.
    bool forceRecalibrate = false;
    if (haveSavedCalibration) {
        // Only counts as a deliberate recalibration request if the corner stays
        // held for the ENTIRE window -- a brief incidental touch while picking
        // the board up must not accidentally wipe a good calibration.
        unsigned long holdStart = millis();
        bool releasedEarly = false;
        while (millis() - holdStart < FORCE_RECALIBRATE_HOLD_MS) {
            uint16_t tx, ty;
            if (!tft.getTouch(&tx, &ty)) { releasedEarly = true; break; }
            delay(10);
        }
        forceRecalibrate = !releasedEarly;
    }

    if (haveSavedCalibration && !forceRecalibrate) {
        prefs.getBytes(CALIBRATION_KEY, calData, sizeof(calData));
        tft.setTouch(calData);
        prefs.end();
        Serial.println("[ArcadeOS] loaded saved touch calibration from flash");
        return;
    }

    Serial.println(haveSavedCalibration
        ? "[ArcadeOS] forced recalibration requested (corner held at boot)"
        : "[ArcadeOS] no saved calibration -- running first-time calibration");

    tft.fillScreen(TFT_BLACK);
    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    tft.drawString("Touch each corner", SCREEN_WIDTH / 2, SCREEN_HEIGHT / 2 - 20);
    tft.drawString("as it's marked", SCREEN_WIDTH / 2, SCREEN_HEIGHT / 2 + 10);
    delay(1200);

    // TFT_eSPI's own calibration routine: draws a target in each corner in
    // turn, waits for a touch, and fills calData with the raw ADC min/max
    // range this specific physical panel reports -- see this library's
    // "Touch_calibrate" example for the same call used the same way.
    tft.calibrateTouch(calData, TFT_WHITE, TFT_BLACK, 20);
    Serial.printf("[ArcadeOS] calibration captured: %u,%u,%u,%u,%u\n",
                  calData[0], calData[1], calData[2], calData[3], calData[4]);

    prefs.putBytes(CALIBRATION_KEY, calData, sizeof(calData));
    prefs.end();
}
