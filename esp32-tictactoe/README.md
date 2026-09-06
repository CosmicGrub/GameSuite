# Tic-Tac-Toe for the Hosyond 4.0" ESP32 Display Module

A standalone firmware project for the Hosyond 4.0" ESP32 display (ILI9341
driver, 240x320, resistive touch, acrylic case) — the same game GameSuite's
Android app ships, rebuilt from scratch for a microcontroller.

## Why this isn't "a build of GameSuite"

GameSuite is Kotlin + Jetpack Compose, running on Android's OS and JVM. The
ESP32 in your display module is a microcontroller: no Android, no JVM,
typically a few hundred KB of RAM against a phone's gigabytes. None of
GameSuite's actual code — Compose, the game engines, DataStore, the
networking — can run on it. This project shares the *design* (the same
minimax AI, the same clean split between game logic and rendering
GameSuite's own architecture uses everywhere) as new C++ written for Arduino.

**Verified**: this exact code compiles cleanly (0 errors, 0 warnings) against
the `esp32:esp32` Arduino core using `arduino-cli`, targeting the "ESP32 Dev
Module" board — 318KB flash (24% of the default partition) and 22KB RAM (6%)
used. On top of that, [native_test/](native_test/) compiles and runs the
real, unmodified rules engine and touch hit-testing math on a desktop
(against no-op stand-ins for the ESP32/display-only parts) — an exhaustive
search of every possible game confirms the AI is genuinely unbeatable (0
human wins across 569 games), and every pixel in the touch grid resolves to
the correct cell with no gaps or overlaps. What none of that can confirm is
actual pixel rendering, real touch calibration, or your board's specific
pin wiring — only physical hardware can. I don't have the physical board, so
you'll need to flash it and tell me what happens, especially for the
pin/wiring question below.

## Hardware

- Hosyond 4.0" ESP32 Display Resistive Touchscreen (ILI9341 driver, 240x320,
  acrylic case) — an ESP32-32E-based board (dual-core, 240MHz, 520KB SRAM,
  4MB flash, no PSRAM), per the confirmed specs for this unit.
- USB-C cable for programming/power.

### About the pin mapping (please read before assuming something's wrong)

`UserSetup/User_Setup.h` in this project defines which ESP32 GPIO pins talk
to the display and touch controller. I could not find an official pinout
published for this *exact* product, but your board is confirmed compatible
with the open-source **NerdMiner_v2** project, and its real, shipped,
working firmware for the "CYD" ESP32-2432S028R board — same ESP32-32E-family
chip, same ILI9341 driver, same resistive touch, just a smaller 2.8" panel
on the same underlying design — uses exactly the pins in that file. That's a
genuine working reference, not a guess, but it's still a same-family
cross-reference, not a datasheet for your specific unit. If the pins are
off, it typically just means a blank/garbled screen or touch that doesn't
track — not damage. See **Troubleshooting** below.

## Software setup

1. **Arduino IDE** (2.x) — [download here](https://www.arduino.cc/en/software).
2. **ESP32 board support**: File → Preferences → "Additional Boards Manager
   URLs" → add:
   ```
   https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
   ```
   Then Tools → Board → Boards Manager → search "esp32" → install the
   Espressif package (this project was verified against core version 3.3.11).
3. **TFT_eSPI library**: Tools → Manage Libraries → search "TFT_eSPI" by
   Bodmer → install (verified against 2.5.43).
4. **Copy the pin configuration**: copy `UserSetup/User_Setup.h` from this
   project into your Arduino libraries folder, **replacing** the file
   already there:
   - Windows: `Documents\Arduino\libraries\TFT_eSPI\User_Setup.h`
   - macOS: `~/Documents/Arduino/libraries/TFT_eSPI/User_Setup.h`
   - Linux: `~/Arduino/libraries/TFT_eSPI/User_Setup.h`

   This is a library-wide setting, not per-sketch — if you have another
   TFT_eSPI project for different hardware, you'll need to swap this file
   back for that one. (TFT_eSPI does support per-project configuration via
   build flags, which is how PlatformIO projects like NerdMiner do it, but
   that's more setup than a first Arduino IDE project needs.)

## Build and flash

1. Open `TicTacToeESP32/TicTacToeESP32.ino` in Arduino IDE.
2. Tools → Board → esp32 → **ESP32 Dev Module**.
3. Tools → Port → select your board's USB-C port (install a CP210x/CH340
   driver first if it doesn't show up — check which USB-serial chip your
   board uses).
4. Click Upload.

## First boot: touch calibration

The first time it boots (and any time no calibration is saved), the screen
will ask you to touch three corners in turn — this maps your specific
panel's resistive-touch range to screen pixels, and only needs to happen
once; it's saved to the ESP32's internal flash (NVS) and reloaded on every
later boot. If touch ever feels consistently offset later, hold the
top-left corner of the screen down through a power-on or reset (for about
1.5 seconds) to force it to run again.

## Playing

You're X, the ESP32 is O, X always moves first. The AI is a full minimax
search — the same algorithm and the same "prefer a faster win, a slower
loss" depth-scoring as GameSuite's Android version's HARD tier — so, same as
that version, it cannot be beaten; the best you can do is force a draw.
Tic-Tac-Toe is small enough that the ESP32 solves the whole remaining game
tree on every move, in comfortably under a second, so there's no
difficulty tiering the way GameSuite's word-heavy games need.

## Troubleshooting

- **Blank/white screen, backlight on**: almost always `TFT_CS`, `TFT_DC`, or
  `TFT_RST` in `User_Setup.h` being wrong for your unit. Check your board's
  PCB silkscreen labels near the display header/ribbon if you can read
  them — that's the single most reliable source for your exact revision.
- **Screen lights up but shows garbage/noise**: usually `TFT_MOSI`/`TFT_SCLK`
  swapped, or the SPI frequency too high for your specific panel — try
  lowering `SPI_FREQUENCY` in `User_Setup.h` (e.g. to `20000000`) first.
- **Colors look inverted or wrong** (e.g. sky looks orange): comment out
  `#define ILI9341_2_DRIVER` and uncomment `#define ILI9341_DRIVER` instead
  — some ILI9341 clone panels need the other of TFT_eSPI's two init
  sequences.
- **No backlight at all**: check `TFT_BL` and `TFT_BACKLIGHT_ON` — some
  boards wire the backlight active-low instead of active-high.
- **Touch doesn't respond, or the calibration screen doesn't react to taps**:
  double-check `TOUCH_CS`/`TOUCH_CLK`/`TOUCH_MOSI`/`TOUCH_MISO` — this board
  family uses a separate physical SPI bus for touch, not shared with the
  display, per the NerdMiner reference this project is based on.
- **Touch works but taps land in the wrong place**: recalibrate (hold the
  top-left corner through a power cycle, per above).
- **Upload fails / port not found**: install the correct USB-to-serial
  driver for your board's chip (commonly CP2102 or CH340), and hold the
  board's BOOT button while upload starts if it doesn't auto-reset into
  bootloader mode.

If you hit one of these, tell me exactly what you see (or a photo) and
we'll adjust `User_Setup.h` together — I can't test on your physical unit,
so this loop is how we actually nail down your board's exact pins.

## What's next

This project is intentionally scoped to one game as a first proof-of-concept
for the display/touch/input pipeline, per how we agreed to approach this.
Once this is confirmed working on your actual board, the same
GameLogic/Display split makes adding another simple game (Mancala and
Dominoes are the next-best fits — small state, no big dictionary) mostly a
matter of writing a new GameLogic.h/.cpp pair and a new Display.h/.cpp pair,
then a small menu screen to choose between them, reusing this same
project's touch-calibration and main-loop structure.

Not attempted here, and not realistic on this hardware without much more
work: UNO/Solitaire (many cards to render), Air Hockey (real-time physics +
simultaneous multi-touch), and the dictionary-backed word games (Word
Search/Crossword/Word Tiles rely on a 3.6MB word list — this ESP32-32E
variant has no PSRAM and only 4MB of flash total).
