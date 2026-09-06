#pragma once
#include <TFT_eSPI.h>
#include "UnoLogic.h"

// All UNO drawing + touch hit-testing lives here -- UnoLogic.h/.cpp knows
// nothing about TFT_eSPI, same engine/screen split as Display.h/Display.cpp
// use for Tic-Tac-Toe. Cards are drawn as colored rounded rects with a short
// text label (never a bitmap), matching this project's existing "shapes +
// text" rendering style throughout.

struct UnoLayout {
    int16_t statusY, statusH;              // status banner across the top (incl. home button, like Display.h's)

    int16_t aiHandY, aiHandH;              // AI's face-down hand strip (count only, never contents)

    int16_t cardW, cardH;                  // shared size for the discard pile / draw pile boxes
    int16_t discardX, discardY;            // discard pile (top card) box
    int16_t drawPileX, drawPileY;          // draw pile box -- tappable

    int16_t handY, handCardW, handCardH;   // human hand row -- see handCardLeft() in UnoDisplay.cpp for how
    int16_t handGap, handMarginX;          // up to ~20 cards are laid out (spread out, or overlapped if they
                                            // wouldn't otherwise fit within handMarginX of each screen edge)

    int16_t buttonX, buttonY, buttonW, buttonH; // "Play Again" button, shown only once the round ends
};

// Computed once from Config.h's SCREEN_WIDTH/SCREEN_HEIGHT, same contract as
// Display.h's computeLayout().
UnoLayout computeUnoLayout();

void drawUnoStaticChrome(TFT_eSPI &tft, const UnoLayout &layout);
void drawUnoStatus(TFT_eSPI &tft, const UnoLayout &layout, const char *text);

// Redraws the entire human hand row from scratch (clearing stale card
// positions first) -- call after every human or AI action that can change
// hand size or ordering, same "just redraw the whole thing, it's cheap on
// this panel" spirit as TicTacToe's drawCell calls.
void drawUnoHumanHand(TFT_eSPI &tft, const UnoLayout &layout, const UnoCardView *cards, uint8_t count);

// AI hand: face-down only -- draws `count` card-back rectangles plus a
// "AI: N cards" label. Never takes card contents (Display, like UnoLogic,
// has no way to see the AI's hand -- see UnoRound::aiHandCount()'s comment).
void drawUnoAiHand(TFT_eSPI &tft, const UnoLayout &layout, uint8_t count);

// `activeColor` is the color actually in effect (UnoRound::currentColor()),
// which can differ from topCard.color right after a Wild -- see
// UnoLogic.h's comment on that split. Drawn as a colored border around the
// (still neutral/quadrant-patterned) Wild card face.
void drawUnoDiscardPile(TFT_eSPI &tft, const UnoLayout &layout, UnoCardView topCard, UnoColor activeColor);

void drawUnoDrawPile(TFT_eSPI &tft, const UnoLayout &layout, uint8_t remainingCount);

void drawUnoPlayAgainButton(TFT_eSPI &tft, const UnoLayout &layout);
void hideUnoPlayAgainButton(TFT_eSPI &tft, const UnoLayout &layout);

// ---- Hit-testing -- pure math, no TFT_eSPI calls, same testable-on-a-
// desktop contract as Display.h's hitTestCell()/hitTestPlayAgainButton(). ----

// Which hand-card index (if any) covers (touchX, touchY) -- `handCount`
// must be the SAME count the hand was last drawn with (see
// drawUnoHumanHand()), since card positions depend on how many there are.
// When cards overlap (a full/large hand), the higher (further-right,
// visually topmost) index wins, matching draw order.
bool hitTestUnoHumanHandCard(const UnoLayout &layout, uint8_t handCount, int16_t touchX, int16_t touchY, uint8_t &outIndex);

bool hitTestUnoDrawPile(const UnoLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestUnoPlayAgainButton(const UnoLayout &layout, int16_t touchX, int16_t touchY);

// Same in-status-bar "back to arcade menu" affordance as Display.h's
// hitTestHomeButton() -- always present during a round, not just after it
// ends.
bool hitTestUnoHomeButton(const UnoLayout &layout, int16_t touchX, int16_t touchY);

// ---- Color-choice overlay ----
// A small reusable overlay shown after the HUMAN plays a Wild/Wild Draw Four
// (UnoRound::awaitingColorChoice()) -- genuinely a different interaction
// shape than a card row or a single button, so it gets its own layout
// struct rather than being bolted onto UnoLayout. The AI never needs this;
// it resolves its own color choice with no visible pause (see UnoLogic.h).
struct UnoColorOverlayLayout {
    int16_t panelX, panelY, panelW, panelH;
    int16_t swatchY, swatchSize, swatchGap;
    int16_t swatchX[4]; // left edge of each swatch, in a fixed RED,YELLOW,GREEN,BLUE order
};

UnoColorOverlayLayout computeUnoColorOverlayLayout();

void drawUnoColorOverlay(TFT_eSPI &tft, const UnoColorOverlayLayout &overlay);

// Erases just the overlay's own footprint back to the board background --
// the caller (the .ino's state machine) is responsible for redrawing
// whatever game element the overlay was sitting on top of afterward (the
// discard pile and status line, in this layout), same "caller repaints what
// it just covered" pattern as hidePlayAgainButton()'s callers already use.
void hideUnoColorOverlay(TFT_eSPI &tft, const UnoColorOverlayLayout &overlay);

// Which of the 4 color swatches (if any) covers (touchX, touchY).
bool hitTestUnoColorOverlay(const UnoColorOverlayLayout &overlay, int16_t touchX, int16_t touchY, UnoColor &outColor);
