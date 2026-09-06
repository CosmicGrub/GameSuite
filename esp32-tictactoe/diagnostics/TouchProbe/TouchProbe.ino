// Diagnostic-only sketch: NOT part of the game. The XPT2046-over-SPI probe
// (CS=33/CLK=25/MOSI=32/MISO=39) showed zero signal even under a firm press
// -- IRQ never went low, X/Y always read exactly 0. Rather than guess at SPI
// pins blind, this checks the other hypothesis directly: some cheap clone
// boards wire the resistive panel straight to 4 ESP32 ADC-capable pins with
// NO separate touch controller chip at all. ESP32's only input-only,
// no-pull ADC pins are 34, 35, 36, 39 -- this board's config already uses 36
// (TOUCH_IRQ) and 39 (TOUCH_MISO) for the XPT2046 theory; this probe reads
// all four continuously so a real press shows up as a clear jump on
// whichever pin(s) are actually wired to the panel, either theory included.

#include <Arduino.h>

const int CANDIDATES[] = {34, 35, 36, 39};
const int NUM_CANDIDATES = sizeof(CANDIDATES) / sizeof(CANDIDATES[0]);

void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println();
    Serial.println("[AnalogTouchProbe] starting -- press and hold the screen firmly, watching GPIO34/35/36/39");
    for (int i = 0; i < NUM_CANDIDATES; i++) pinMode(CANDIDATES[i], INPUT);
}

void loop() {
    static unsigned long last = 0;
    if (millis() - last > 200) {
        last = millis();
        Serial.printf("[AnalogTouchProbe] GPIO34=%4d  GPIO35=%4d  GPIO36=%4d  GPIO39=%4d\n",
                      analogRead(34), analogRead(35), analogRead(36), analogRead(39));
    }
}
