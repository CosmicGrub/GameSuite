#pragma once
// ---------------------------------------------------------------------------
// Board configuration for the Hosyond 4.0" ESP32 Display Module (ILI9341,
// 240x320, resistive touch, acrylic case).
// ---------------------------------------------------------------------------
// This file only holds constants that vary by BOARD, not by GAME -- the
// actual TFT_eSPI pin wiring lives in UserSetup/User_Setup.h (see this
// project's README for why TFT_eSPI needs its pins set that way instead of
// here) and is included automatically once you've copied that file into
// your Arduino libraries folder. This file is for everything else that's
// specific to the physical board: the ESP32-32E chip identity, and how the
// panel is oriented/sized once TFT_eSPI has it working.
//
// PROVENANCE OF THE PIN MAPPING (see User_Setup.h): this board's chip
// (ESP32-32E + ILI9341 240x320 + resistive touch) is confirmed compatible
// with the open-source NerdMiner_v2 project. That project's actual, shipped,
// working firmware for the electrically-identical "CYD" ESP32-2432S028R
// board (same ESP32-32E-family chip, same ILI9341 driver, same 240x320
// panel, same resistive touch -- the ONLY difference from your board is a
// bigger 4.0" physical panel glued to the same electronics) uses the exact
// pin numbers this project defaults to. That's a real, working reference,
// not a guess -- but it's still a cross-reference from a different-sized
// panel in the same product family, not a datasheet for your exact unit, so
// verify against the troubleshooting section in README.md if anything
// doesn't light up.

// ---- Panel geometry ----
// TFT_eSPI's rotation values for ILI9341: 0/2 are portrait (240x320),
// 1/3 are landscape (320x240). Landscape reads more naturally for a 3x3
// touch grid and is what almost every ILI9341 module ships oriented for.
#define SCREEN_ROTATION 1
#define SCREEN_WIDTH  320
#define SCREEN_HEIGHT 240

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
