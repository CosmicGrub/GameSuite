# GameSuite Arcade for the Hosyond 4.0" ESP32 Display Module

A standalone firmware project for the Hosyond 4.0" ESP32 display (ST7796S
driver, 320x480, resistive touch via a shared-bus XPT2046, acrylic case) —
starting with the same Tic-Tac-Toe GameSuite's Android app ships, rebuilt
from scratch for a microcontroller, now grown into a small "arcade OS" with
a home menu and room for more games.

**Every hardware fact below is now confirmed against this exact board's own
silkscreen and real vendor documentation, cross-checked by live testing on
the physical unit** — see [HARDWARE.md](HARDWARE.md) for the full pinout
reference and how each value was found (useful for any future project on
this same board, not just this one).

## Why this isn't "a build of GameSuite"

GameSuite is Kotlin + Jetpack Compose, running on Android's OS and JVM. The
ESP32 in your display module is a microcontroller: no Android, no JVM,
typically a few hundred KB of RAM against a phone's gigabytes. None of
GameSuite's actual code — Compose, the game engines, DataStore, the
networking — can run on it. This project shares the *design* (the same
minimax AI, the same clean split between game logic and rendering
GameSuite's own architecture uses everywhere) as new C++ written for Arduino.

**Verified**: this exact code compiled cleanly (0 errors, 0 warnings) against
the `esp32:esp32` Arduino core using `arduino-cli`, targeting the "ESP32 Dev
Module" board — 394KB flash (30% of the default partition) and 23KB RAM (7%)
used. (Measured 2026-09-06, after Checkers/Chess/UNO were added.) **Stale as
of the 2026-09-12 Premium 2026 Vision quick-win pass** (Tic-Tac-Toe's stamp-in
placement + drop-shadow marks, Checkers' promotion pop + capture hit-stop,
Chess's checkmate string/highlight + drop-shadow pieces) — that pass added
real code to Display.cpp/CheckersDisplay.cpp/ChessDisplay.cpp/the .ino, was
verified against [native_test/](native_test/)'s stubbed build (0 compile
errors, all existing checks still pass, one new rendering check added for the
checkmate highlight), but was **not** re-verified against the real
`arduino-cli`/ESP32 toolchain, since that toolchain isn't available in the
environment this pass was done in — no arduino-cli install, no physical
board. Both the compile-against-real-hardware-headers claim and the flash/RAM
figures above need a fresh `arduino-cli compile` pass (and, ideally, an actual
flash-and-play check) before being trusted again; re-measure again once
Solitaire/Mahjong land too, since each adds meaningfully to both figures. On
top of that, [native_test/](native_test/) compiles and runs the
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

- Hosyond 4.0" ESP32 Display Resistive Touchscreen (ST7796S driver, 320x480,
  acrylic case) — an ESP32-32E-based board (dual-core, 240MHz, 520KB SRAM,
  4MB flash, no PSRAM), confirmed by this exact unit's own PCB silkscreen.
- USB-C cable for programming/power.

### About the pin mapping

`UserSetup/User_Setup.h` in this project defines which ESP32 GPIO pins talk
to the display and touch controller. Earlier revisions of this project
guessed those pins from a similar-but-different reference board (NerdMiner_v2's
"CYD" ESP32-2432S028R) and got several wrong — reset pin, backlight
pin+polarity, the display driver/resolution, and the touch bus wiring all
differ on this actual board. Every value now in `User_Setup.h` is confirmed
either directly off this board's own PCB silkscreen, from its real vendor
documentation (lcdwiki's E32R40T/E32N40T product page, found by searching
the silkscreen text verbatim), or by live hardware testing — see
[HARDWARE.md](HARDWARE.md) for the complete pinout table and the story of
how each fix was found. If you're working from a board that turns out to
differ even from this, the same diagnostic sketches under `diagnostics/`
(a one-GPIO-at-a-time backlight probe, a raw XPT2046 touch probe) are
reusable starting points. See **Troubleshooting** below.

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
   build flags, which is how PlatformIO projects handle this, but that's
   more setup than a first Arduino IDE project needs.)

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

If you're on the exact same board this project targets, `User_Setup.h` is
already correct (see [HARDWARE.md](HARDWARE.md)) — these are for a different
unit, or a similar-looking clone that turns out to differ:

- **Blank/dark screen, backlight also dark**: check `TFT_BL` and
  `TFT_BACKLIGHT_ON` first — some boards wire the backlight active-low
  instead of active-high, and some route it to a completely different GPIO.
  `diagnostics/BacklightSweep/` is a reusable one-GPIO-at-a-time probe: it
  holds exactly one candidate pin (each polarity) so you can confirm by eye
  which one actually lights the panel, rather than guessing. Watch out for a
  separate onboard status LED answering "yes" to a fast blind sweep instead
  of the real screen — confirm it's the panel itself lighting up, not a
  small indicator light elsewhere on the board.
- **Screen lights up but stays blank/uninitialized**: check `TFT_RST` — many
  of these boards tie display reset to the shared EN/reset circuit (no
  distinct GPIO, so `TFT_RST` should be `-1`), not a dedicated pin.
- **Text/image is mirrored or backward**: wrong driver-variant MADCTL
  mapping for your panel — if using `ILI9341_2_DRIVER`, try the plain
  `ILI9341_DRIVER` (or vice versa); some ILI9341 clone panels need the other
  of TFT_eSPI's two init sequences.
- **Image renders correctly but part of the screen stays an unaddressed
  blank strip**: your `TFT_WIDTH`/`TFT_HEIGHT` (and driver) likely don't
  match the panel's real native resolution — double-check the actual driver
  IC and resolution against your board's own documentation or silkscreen
  rather than assuming a same-inch-size board shares them.
- **Screen lights up but shows garbage/noise**: usually `TFT_MOSI`/`TFT_SCLK`
  swapped, or the SPI frequency too high for your specific panel — try
  lowering `SPI_FREQUENCY` in `User_Setup.h` (e.g. to `20000000`) first.
- **Touch doesn't respond at all**: don't assume touch is on a separate
  physical SPI bus — many of these boards (this one included) share the
  display's own CLK/MOSI/MISO for touch, with only `TOUCH_CS` genuinely
  separate. `diagnostics/TouchProbe/` reads the XPT2046 controller directly
  (bypassing TFT_eSPI) so you can confirm real signal on whatever pins you
  try, and a second variant checks the ESP32's own floating ADC-capable
  pins in case a cheaper clone wires the resistive panel directly with no
  separate touch-controller chip at all.
- **Touch works but taps land in the wrong place**: recalibrate (hold the
  top-left corner through a power cycle, per above).
- **Upload fails / port not found**: install the correct USB-to-serial
  driver for your board's chip (commonly CP2102 or CH340), and hold the
  board's BOOT button while upload starts if it doesn't auto-reset into
  bootloader mode.

The single most reliable way to identify one of these interchangeable-
looking clone boards is a close-up photo of the board's own silkscreen text,
searched verbatim — a generic product-line spec sheet can (and did, for this
exact project) describe the wrong variant.

## What's next

Tic-Tac-Toe was the first proof-of-concept for the display/touch/input
pipeline; Checkers, Chess, and UNO have since shipped on top of the same
GameLogic/Display split and the home menu that came with it — see
`MENU_GAMES` near the top of `TicTacToeESP32.ino`, where each entry's
`enabled` flag reflects what's actually built and reachable from the menu
today, not just planned. Mancala and Dominoes are the next-best fits (small
state, no big dictionary) — currently present in that same array as
`false` — and follow the same pattern: a new GameLogic.h/.cpp pair, a new
Display.h/.cpp pair, then flipping the menu entry to `true`.

Not attempted here, and not realistic on this hardware without much more
work: Solitaire (many cards and a larger tableau to render than UNO's
hand-plus-discard-pile), Air Hockey (real-time physics + simultaneous
multi-touch), and the dictionary-backed word games (Word Search/Crossword/
Word Tiles rely on a 3.6MB word list — this ESP32-32E variant has no PSRAM
and only 4MB of flash total).
