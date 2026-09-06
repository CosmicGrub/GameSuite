// Tic-Tac-Toe for the Hosyond 4.0" ESP32 Display Module (ILI9341, resistive touch).
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

TFT_eSPI tft = TFT_eSPI();
Preferences prefs;

TicTacToeBoard board;
Layout layout;

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

void setup() {
    Serial.begin(115200);

    tft.init();
    tft.setRotation(SCREEN_ROTATION);

    loadOrRunCalibration();

    layout = computeLayout();
    startNewRound();
}

void loop() {
    handleTouch();

    if (aiMovePending && millis() >= aiMoveDueAt) {
        aiMovePending = false;
        board.playAi();
        for (uint8_t i = 0; i < 9; i++) drawCell(tft, layout, i, board.at(i));
        checkForRoundEnd();
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

    // A touch is a single edge-triggered event as far as this game cares (no
    // drag gestures) -- debounce by requiring the panel to report "not
    // touched" again before the next tap counts, so holding a finger down
    // doesn't re-fire the same tap dozens of times per second.
    static bool wasTouched = false;
    if (wasTouched) { wasTouched = tft.getTouch(&tx, &ty); return; }
    wasTouched = true;

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
        return;
    }

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

    prefs.putBytes(CALIBRATION_KEY, calData, sizeof(calData));
    prefs.end();
}
