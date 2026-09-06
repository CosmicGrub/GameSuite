// TFT_eSPI configuration for the Hosyond 4.0" ESP32 Display Module (ST7796S,
// 320x480, resistive touch via a shared-bus XPT2046).
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
// WHERE THESE VALUES COME FROM: every value in this file is now CONFIRMED --
// either read directly off this exact board's own PCB silkscreen ("ESP32-32E
// 320x480 Resistance Touch"), taken from that board's real vendor
// documentation once correctly identified (lcdwiki's E32R40T/E32N40T
// product), or verified by live testing on the physical unit. None of it is
// a cross-reference guess anymore -- earlier revisions of this file guessed
// from a similar-but-different reference board (NerdMiner_v2's "CYD"
// ESP32-2432S028R) and got several things wrong (reset pin, backlight
// pin+polarity, display driver/resolution, touch bus wiring); see the
// per-value comments below and ../HARDWARE.md for the full history of what
// was wrong and how each fix was found. Reading that file first will save
// you from re-deriving any of this for a future project on the same board.

#define USER_SETUP_ID 100

// ---- Display driver ----
// CONFIRMED (ground truth, not inference): the physical board's own PCB
// silkscreen reads "ESP32-32E 320x480 Resistance Touch" -- a live-hardware
// photo led to searching for that exact text, which turned up lcdwiki's
// E32R40T/E32N40T product page and its documented pinout. That page had
// actually been fetched once before, earlier in this project, and set aside
// as "a different Hosyond product" -- that dismissal was the real mistake:
// it was the right reference all along, just discounted based on an
// unverified spec sheet instead of the board itself. Its documented pinout
// exactly matches every value this project already confirmed by direct
// hardware testing (backlight GPIO27, reset tied to EN) and supplies the
// one thing testing alone couldn't easily reach: the real touch wiring below.
#define ST7796_DRIVER

#define TFT_WIDTH  320
#define TFT_HEIGHT 480

// ---- SPI pins (TFT) ----
// All confirmed against lcdwiki's E32R40T/E32N40T documentation.
#define TFT_MOSI 13
#define TFT_MISO 12   // shared with the touch controller's MISO below -- see the touch section
#define TFT_SCLK 14
#define TFT_CS   15
#define TFT_DC    2
#define TFT_RST  -1   // ties to EN (shared reset circuit), not a distinct GPIO -- confirmed by lcdwiki, matching this project's own earlier hardware-tested fix
#define TFT_BL   27   // confirmed by BOTH lcdwiki's documented pinout AND this project's own one-GPIO-at-a-time hardware probe (diagnostics/BacklightSweep/) -- independent agreement
#define TFT_BACKLIGHT_ON HIGH

// ---- Resistive touch (XPT2046) ----
// CORRECTED (lcdwiki documentation, not the NerdMiner/CYD cross-reference):
// touch is NOT on a separate physical SPI bus on this board -- it shares the
// display's own CLK (14) and MOSI (13), and needs the display's MISO (12,
// which the old config left at -1 as "not connected"). Only TOUCH_CS is
// genuinely separate. This is exactly why the live touch probe found nothing
// on GPIO 25/32/39 (diagnostics/TouchProbe/): those pins were never
// connected to anything at all.
#define TOUCH_CS   33
#define TOUCH_IRQ  36

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
