// Diagnostic-only sketch: NOT part of the game. Fills the ENTIRE screen pure
// white (unmistakable if the panel ever actually lights up, unlike trying to
// read small calibration text through a maybe-dark backlight) and then holds
// exactly ONE candidate backlight GPIO active at a time -- advanced only when
// I send a serial command, based on what you report seeing, never on a timer
// you have to watch and count against. No touch, no calibration, nothing
// that can block waiting for a tap you can't see to make.
//
// Serial protocol (single characters, no newline needed):
//   n -- "no glow" -- advance: try the opposite polarity on this same pin
//        first, then move to the next candidate pin once both polarities
//        of the current pin have been tried
//   r -- repeat/reprint the current candidate's state
//
// Each step prints exactly what it just changed, e.g.:
//   [BacklightProbe] now driving GPIO21 HIGH (candidate 1/8, polarity 1/2)

#include <Arduino.h>
#include <SPI.h>

const int CANDIDATES[] = {21, 4, 16, 17, 22, 23, 26, 27}; // GPIO21 first: today's User_Setup.h config
const int NUM_CANDIDATES = sizeof(CANDIDATES) / sizeof(CANDIDATES[0]);

int candidateIdx = 0;
int polarityIdx = 0; // 0 = HIGH, 1 = LOW

void allCandidatesOff() {
    for (int i = 0; i < NUM_CANDIDATES; i++) {
        pinMode(CANDIDATES[i], OUTPUT);
        digitalWrite(CANDIDATES[i], LOW);
    }
}

void applyCurrent() {
    allCandidatesOff();
    int pin = CANDIDATES[candidateIdx];
    int level = (polarityIdx == 0) ? HIGH : LOW;
    pinMode(pin, OUTPUT);
    digitalWrite(pin, level);
    Serial.printf("[BacklightProbe] now driving GPIO%d %s (candidate %d/%d, polarity %d/2) -- look at the screen NOW\n",
                  pin, level == HIGH ? "HIGH" : "LOW", candidateIdx + 1, NUM_CANDIDATES, polarityIdx + 1);
}

void advance() {
    if (polarityIdx == 0) {
        polarityIdx = 1; // try the same pin the other way before giving up on it
    } else {
        polarityIdx = 0;
        candidateIdx = (candidateIdx + 1) % NUM_CANDIDATES;
        if (candidateIdx == 0) {
            Serial.println("[BacklightProbe] completed a full lap of every candidate/polarity with no confirmed hit yet -- looping again");
        }
    }
    applyCurrent();
}

void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println();
    Serial.println("[BacklightProbe] starting -- fills the screen white and tests ONE GPIO/polarity at a time");
    Serial.println("[BacklightProbe] send 'n' for no glow (advance), 'r' to repeat the current one");

    // No TFT_eSPI/User_Setup.h dependency here on purpose -- talk to the
    // ILI9341 directly over a hardcoded SPI config matching this project's
    // confirmed-correct display pins, so this sketch can't be broken by
    // anything backlight-unrelated. Keeping it dead simple: just enough raw
    // command bytes to fill the screen white, no library needed.
    pinMode(15, OUTPUT); digitalWrite(15, HIGH); // TFT_CS idle high
    pinMode(2, OUTPUT);                          // TFT_DC
    pinMode(14, OUTPUT);                         // TFT_SCLK
    pinMode(13, OUTPUT);                         // TFT_MOSI

    SPI.begin(14, -1, 13, 15);
    SPI.beginTransaction(SPISettings(20000000, MSBFIRST, SPI_MODE0));

    auto sendCmd = [](uint8_t cmd) {
        digitalWrite(2, LOW);
        digitalWrite(15, LOW);
        SPI.transfer(cmd);
        digitalWrite(15, HIGH);
    };
    auto sendData = [](uint8_t d) {
        digitalWrite(2, HIGH);
        digitalWrite(15, LOW);
        SPI.transfer(d);
        digitalWrite(15, HIGH);
    };

    // Minimal ILI9341 bring-up: software reset, sleep out, display on, then
    // fill the whole 240x320 panel (native orientation, no rotation needed
    // for a solid-color fill) with pure white.
    sendCmd(0x01); delay(150);      // SWRESET
    sendCmd(0x11); delay(150);      // SLPOUT
    sendCmd(0x3A); sendData(0x55);  // COLMOD: 16-bit/pixel
    sendCmd(0x29); delay(50);       // DISPON

    sendCmd(0x2A); sendData(0); sendData(0); sendData(0); sendData(239);   // CASET: x 0..239
    sendCmd(0x2B); sendData(0); sendData(0); sendData(1); sendData(63);    // RASET: y 0..319
    sendCmd(0x2C);                                                        // RAMWR
    digitalWrite(2, HIGH);
    digitalWrite(15, LOW);
    for (long i = 0; i < 240L * 320L; i++) { SPI.transfer(0xFF); SPI.transfer(0xFF); } // white = 0xFFFF
    digitalWrite(15, HIGH);

    Serial.println("[BacklightProbe] screen fill command sent (whether or not it's visible depends on the backlight test below)");

    applyCurrent();
}

void loop() {
    if (Serial.available()) {
        char c = Serial.read();
        if (c == 'n') advance();
        else if (c == 'r') applyCurrent();
    }
}
