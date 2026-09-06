# Hardware reference: Hosyond 4.0" ESP32-32E Display Module

Confirmed, tested pinout and configuration for this exact physical board, for
reuse in any future project targeting the same hardware (not specific to the
Tic-Tac-Toe game in this repo). Every value below is either read directly off
the board's own silkscreen, taken from that board's actual vendor
documentation (once correctly identified), or confirmed by live testing on
the physical unit -- none of it is guessed. Where a fact came from testing
rather than documentation, that's noted, since testing is the higher-confidence
source when the two ever disagree.

## Identity

Confirmed by a direct close-up photo of the board's silkscreen (not a guess,
not a spec sheet):

- **Silkscreen text**: `4.0" LCD Display` / `ESP32-32E 320x480` / `Resistance Touch`
- **This is the LCDWIKI E32R40T / E32N40T** product family -- see
  [lcdwiki.com/4.0inch_ESP32-32E_Display](https://www.lcdwiki.com/4.0inch_ESP32-32E_Display).
  (A page with this exact URL was fetched once earlier in this project and
  wrongly set aside as "a different Hosyond product," based on an unverified
  third-party spec sheet claiming ILI9341/240x320. That dismissal was the
  actual mistake -- once the board's own silkscreen was read directly, this
  page turned out to be the correct reference all along. Lesson: a spec
  sheet is a claim; a photo of the part in hand is evidence.)
- **MCU**: ESP32-32E module (ESP32-D0WD-V3 silicon, dual-core Xtensa LX6 @
  240MHz, 520KB SRAM, 4MB flash, no PSRAM, Wi-Fi 2.4GHz b/g/n + Bluetooth 4.2)
- **Display driver IC**: **ST7796S** (not ILI9341, not ILI9488 -- both were
  tried first based on inference before the real chip was confirmed; see
  "How this was found" below)
- **Resolution**: 320x480 native (portrait), RGB666/262K colors
- **Touch**: resistive, **XPT2046** controller chip, sharing the display's
  own SPI bus (not a second physical bus)
- **USB-serial chip**: CH340
- **Other on-board peripherals** (visible on the PCB, not used by this
  project but worth knowing for future projects on the same board): micro
  SD card slot, speaker output pads, battery+charge circuit (`BAT` 4-pin
  connector), `UART` breakout, `I2C` breakout, `SPI` breakout, `BOOT` and
  `RESET` buttons, an onboard RGB status LED (see Gotchas below).

## Confirmed pinout

| Function | GPIO | Source |
|---|---|---|
| TFT MOSI | 13 | lcdwiki doc + working |
| TFT MISO | 12 | lcdwiki doc (shared with touch MISO -- see Gotchas) |
| TFT SCLK | 14 | lcdwiki doc + working |
| TFT CS | 15 | lcdwiki doc + working |
| TFT DC | 2 | lcdwiki doc + working |
| TFT RST | *(none -- tied to EN)* | lcdwiki doc, confirmed by live testing |
| TFT Backlight | 27, **active-HIGH** | lcdwiki doc **and independently** a live one-GPIO-at-a-time hardware probe (see Gotchas) -- the two agreed exactly |
| Touch CS | 33 | lcdwiki doc + working |
| Touch CLK | 14 *(shared with TFT SCLK)* | lcdwiki doc + working |
| Touch MOSI | 13 *(shared with TFT MOSI)* | lcdwiki doc + working |
| Touch MISO | 12 *(shared with TFT MISO)* | lcdwiki doc + working |
| Touch IRQ | 36 | lcdwiki doc (not strictly required by TFT_eSPI's polling `getTouch()`) |

## Ready-to-use TFT_eSPI `User_Setup.h`

```cpp
#define ST7796_DRIVER

#define TFT_WIDTH  320
#define TFT_HEIGHT 480

#define TFT_MOSI 13
#define TFT_MISO 12
#define TFT_SCLK 14
#define TFT_CS   15
#define TFT_DC    2
#define TFT_RST  -1
#define TFT_BL   27
#define TFT_BACKLIGHT_ON HIGH

#define TOUCH_CS   33
#define TOUCH_IRQ  36

#define SPI_FREQUENCY       27000000
#define SPI_READ_FREQUENCY  20000000
#define SPI_TOUCH_FREQUENCY 2500000
```

Rotation: `0`/`2` = portrait (320x480), `1`/`3` = landscape (480x320).

The full, actively-maintained copy of this file (with more context/history
in its comments) lives at
[UserSetup/User_Setup.h](UserSetup/User_Setup.h) in this repo.

## How this was found

Every fact above that isn't a straight silkscreen read went through the same
loop: flash a minimal, purpose-built diagnostic sketch, observe the real
board, adjust, repeat -- never inferred from a reference board alone. In
order:

1. **TFT_RST**: original config guessed GPIO 12 based on a similar-but-not-
   identical reference board (NerdMiner_v2's "CYD" ESP32-2432S028R). Real
   symptom: `tft.init()` completed without error, but the screen stayed
   completely dark/uninitialized. Fixed to `-1` (tied to EN) once the
   lcdwiki doc confirmed it directly.
2. **Backlight pin+polarity**: original guess (GPIO 21, active-HIGH, again
   from the CYD reference) produced zero response. Built a diagnostic sketch
   (`diagnostics/BacklightSweep/`) that raw-SPI-fills the screen white, then
   holds exactly one candidate GPIO at a time (both polarities) so a human
   observer can give an unambiguous yes/no per step, rather than trying to
   count position in a fast automatic sweep (which was tried first and
   failed -- a person can't reliably correlate a fast cycling sweep to what
   they saw). Two false leads (GPIO 4 and GPIO 16 both drive a separate
   onboard RGB status LED, not the screen) before GPIO 27/active-HIGH was
   confirmed by watching the actual panel light up.
3. **Display driver + resolution**: original guess (ILI9341, 240x320) came
   from a third-party spec sheet, never verified against the unit. Real
   symptom once RST+backlight were fixed: garbled/mirrored/cut-off image.
   Tried ILI9341_2_DRIVER -> ILI9341_DRIVER (fixed mirroring, not the cutoff)
   -> inferred ILI9488 480x320 from "4.0\" panels are usually ILI9488, not
   ILI9341" (fixed the cutoff, screen filled correctly) -> a photo of the
   board's own silkscreen ("ESP32-32E 320x480") led to finding the actual
   lcdwiki documentation, which named the real chip: ST7796S. Switched to
   the correct native driver rather than staying on the "close enough"
   ILI9488 one.
4. **Touch pins**: a direct XPT2046 SPI probe (`diagnostics/TouchProbe/`) on
   the CYD reference's assumed separate-bus pins (CLK 25 / MOSI 32 / MISO 39)
   found nothing at all -- no IRQ activity, constant zero reads. The lcdwiki
   doc explained why: this board's touch controller shares the display's own
   SPI bus (CLK 14 / MOSI 13 / MISO 12), with only CS (33) actually separate.
   The old config's `TFT_MISO -1` ("not connected") was itself wrong for the
   same reason -- MISO 12 is real and needed by both the display and touch.

## Gotchas for future projects on this board

- **Reset is not a GPIO.** Don't add a `TFT_RST` pin thinking a blank screen
  means it's unwired -- it's tied to the shared EN/reset circuit, same as the
  physical RESET button. Setting `TFT_RST` to any real GPIO number will
  silently do nothing to the panel.
- **Backlight is active-HIGH on GPIO 27**, not GPIO 21 -- despite this board
  being electrically similar in other respects to the common "CYD"
  ESP32-2432S028R reference board, which uses GPIO 21.
- **There's a separate onboard RGB status LED on GPIO 4 and GPIO 16** (and
  possibly others not tested) -- easy to mistake for the display backlight
  during a blind pin sweep since it's genuinely a light turning on in
  response to the same kind of GPIO toggle. If a backlight test shows "a
  small glow" rather than "the whole panel lighting up," check whether it's
  this LED, not the screen.
- **The display driver is ST7796S, not ILI9341 or ILI9488** -- all three are
  common in this general product class, and TFT_eSPI supports all three, but
  they're genuinely different command sets. ILI9488 happened to render
  correctly enough for text/fills to look right, which could have looked
  like "solved" -- the real driver still mattered enough to be worth
  confirming and switching to.
- **Touch shares the display's SPI bus.** Don't assume a second physical bus
  the way some similar-looking reference designs use -- only `TOUCH_CS` (33)
  and optionally `TOUCH_IRQ` (36) are genuinely separate pins.
- **The most reliable way to identify one of these interchangeable-looking
  clone boards is the board's own silkscreen text**, searched verbatim, not
  a generic product-line spec sheet -- this project hit the same
  "same-brand, different actual product" trap twice (once with a mismatched
  lcdwiki page for a different Hosyond product early in this project, once
  by initially dismissing what turned out to be the *correct* lcdwiki page
  for *this* product) before the silkscreen photo settled it conclusively.
