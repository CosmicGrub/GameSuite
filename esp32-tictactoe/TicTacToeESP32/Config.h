#pragma once
// ---------------------------------------------------------------------------
// Board configuration for the Hosyond 4.0" ESP32 Display Module (ST7796S,
// 320x480, resistive touch via XPT2046, acrylic case).
// ---------------------------------------------------------------------------
// This file only holds constants that vary by BOARD, not by GAME -- the
// actual TFT_eSPI pin wiring lives in UserSetup/User_Setup.h (see this
// project's README for why TFT_eSPI needs its pins set that way instead of
// here) and is included automatically once you've copied that file into
// your Arduino libraries folder. This file is for everything else that's
// specific to the physical board: the ESP32-32E chip identity, and how the
// panel is oriented/sized once TFT_eSPI has it working.
//
// PROVENANCE: every hardware fact this project uses (this file's geometry,
// User_Setup.h's pins) is now confirmed against this exact board's own PCB
// silkscreen and its real vendor documentation (lcdwiki's E32R40T/E32N40T
// product), cross-checked by live hardware testing -- not guessed from a
// similar-looking reference board. See ../HARDWARE.md for the full reference
// (pinout table, how each value was found, gotchas) if you're starting a new
// project on this same board.

// ---- Panel geometry ----
// This board's real panel/driver is ST7796S (320x480 native) -- 0/2 are
// portrait (320x480), 1/3 are landscape (480x320). Landscape reads more
// naturally for a 3x3 touch grid.
#define SCREEN_ROTATION 1
#define SCREEN_WIDTH  480
#define SCREEN_HEIGHT 320

// ---- Touch calibration ----
// XPT2046 resistive touch panels vary unit-to-unit (manufacturing tolerance
// in the resistive film), so there's no universal calibration constant --
// every physical panel needs its own. TicTacToeESP32.ino runs TFT_eSPI's
// built-in interactive calibration (touch the corners) on first boot and
// saves the result into the ESP32's NVS flash (via the Preferences library,
// part of the standard ESP32 Arduino core) under this namespace/key, so it
// only ever needs to happen once per board. Hold the top-left corner of the
// screen at power-on to force it to run again if touch ever feels offset.
#define CALIBRATION_NAMESPACE "tictactoe"
#define CALIBRATION_KEY "tftcal"
#define FORCE_RECALIBRATE_HOLD_MS 1500

// ---- Arcade shell ----
// This firmware boots to a home menu (MenuScreen.h/.cpp) rather than
// straight into one game -- the same "OS + launcher" shape the README's
// "What's next" section already called for, just built now instead of left
// as a future step. One entry point, add a game by adding one more line
// below plus a GameLogic/Display pair, same as this project has always
// intended.
#define ARCADE_TITLE "GameSuite Arcade"
