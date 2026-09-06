// TFT_eSPI configuration for the Hosyond 4.0" ESP32 Display Module (ILI9341,
// 240x320, resistive touch).
//
// HOW TO USE THIS FILE: copy it into your TFT_eSPI library folder, replacing
// the file already there, at:
//   Windows:  Documents\Arduino\libraries\TFT_eSPI\User_Setup.h
//   macOS:    ~/Documents/Arduino/libraries/TFT_eSPI/User_Setup.h
//   Linux:    ~/Arduino/libraries/TFT_eSPI/User_Setup.h
// TFT_eSPI is configured once, library-wide, this way -- see README.md step 3
// for why Arduino IDE (unlike PlatformIO) doesn't have a cleaner per-project
// way to do this, and what to do if you have OTHER TFT_eSPI-based sketches
// for a different board that would conflict with this file.
//
// WHERE THESE PIN NUMBERS COME FROM (read this before assuming a wrong pin is
// a mistake on my part rather than something to verify against your exact
// board): this board (ESP32-32E chip + ILI9341 240x320 driver + resistive
// touch) is confirmed compatible with the open-source NerdMiner_v2 project.
// That project's real, shipped, working firmware for the "CYD" ESP32-2432S028R
// board -- electrically the same design family, just a smaller 2.8" panel on
// the same electronics -- uses exactly the pin numbers below (its
// platformio.ini, `[env:ESP32-2432S028R]`). That's a genuine working
// reference, not a guess. It is still a cross-reference from a same-family,
// different-panel-size board, not your exact unit's datasheet, so if the
// screen stays blank or touch doesn't track correctly, see README.md's
// troubleshooting section before assuming the game code itself is at fault.

#define USER_SETUP_ID 100

// ---- Display driver ----
// ILI9341_2_DRIVER (not the plain ILI9341_DRIVER) is what NerdMiner uses for
// this exact board family -- a small number of ILI9341-chip panels from
// certain factories need this alternate init sequence TFT_eSPI ships to
// handle that variation. If colors look inverted or wrong once this is
// working, comment this out and uncomment the line below instead.
#define ILI9341_2_DRIVER
// #define ILI9341_DRIVER

#define TFT_WIDTH  240
#define TFT_HEIGHT 320

// ---- SPI pins (TFT) ----
#define TFT_MOSI 13
#define TFT_MISO -1   // not connected on this board family -- the display is write-only from the MCU's side
#define TFT_SCLK 14
#define TFT_CS   15
#define TFT_DC    2
#define TFT_RST  12   // NOT tied to EN on this board family (unlike some similar-looking boards) -- a real, confirmed quirk
#define TFT_BL   21
#define TFT_BACKLIGHT_ON HIGH

// ---- Resistive touch (XPT2046) ----
// A separate physical SPI bus from the display on this board family (not
// shared) -- confirmed by the same NerdMiner reference above.
#define TOUCH_CS   33
#define TOUCH_CLK  25
#define TOUCH_MOSI 32
#define TOUCH_MISO 39
#define TOUCH_IRQ  36 // TFT_eSPI's getTouch() works without wiring/using this, but it's here for completeness

// ---- Fonts ----
// The handful of built-in fonts this project actually uses (setTextSize()
// scales #1, GFX vector font not needed here) -- trimming unused fonts keeps
// flash usage down, which matters more on some ESP32-32E boards' 4MB flash
// than it would on a phone.
#define LOAD_GLCD
#define LOAD_FONT2
#define SMOOTH_FONT

// ---- SPI frequency ----
// NerdMiner's own working config runs the TFT at 55MHz; starting more
// conservatively here since that's tuned for the exact 2.8" panel it ships
// with, not necessarily every 4.0" panel in this family. Raise it once
// everything is confirmed working if you want faster redraws.
#define SPI_FREQUENCY       27000000
#define SPI_READ_FREQUENCY  20000000
#define SPI_TOUCH_FREQUENCY 2500000
