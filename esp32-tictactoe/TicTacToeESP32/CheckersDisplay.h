#pragma once
#include <TFT_eSPI.h>
#include "CheckersLogic.h"

// All drawing lives here -- CheckersLogic.h/.cpp knows nothing about
// TFT_eSPI, and the .ino only calls these functions plus CheckersLogic's,
// the same engine/screen split Display.h uses for Tic-Tac-Toe. Every shape
// is a TFT_eSPI primitive (fillRect/fillRoundRect/drawRoundRect/drawLine/
// drawString) -- no image assets, same as the rest of this project. A piece
// is a team-colored rounded square; a king is the same square with a bold
// "K" drawn on top of it, so kings need no extra color.

struct CheckersLayout {
    int16_t boardX, boardY, cellSize; // 8x8 board, top-left corner + one cell's side length
    int16_t statusY, statusH;         // status banner across the top
    int16_t buttonX, buttonY, buttonW, buttonH; // "Play Again", shown only once a round ends
};

// Computed once from the panel's rotated width/height (see Config.h) --
// SCREEN_WIDTH/SCREEN_HEIGHT there must match tft.setRotation()'s result.
CheckersLayout computeCheckersLayout();

void drawCheckersStaticChrome(TFT_eSPI &tft, const CheckersLayout &layout);
void drawCheckersStatus(TFT_eSPI &tft, const CheckersLayout &layout, const char *text);

// Draws every one of the 64 squares (checkerboard coloring plus whatever
// piece, if any, currently sits there) fresh -- cheap enough to call after
// every hop instead of tracking exactly which squares changed, same
// simplicity tradeoff Tic-Tac-Toe's own small grid makes.
void drawCheckersBoard(TFT_eSPI &tft, const CheckersLayout &layout, const CheckersBoard &board);

// Redraws just one square (its checkerboard base color, then its piece if
// any) -- an optional finer-grained alternative to drawCheckersBoard for
// callers that only need to refresh the handful of squares one hop actually
// touched (source, destination, and a captured square).
void drawCheckersSquare(TFT_eSPI &tft, const CheckersLayout &layout, uint8_t row, uint8_t col, CheckersPiece piece);

// Redraws one square exactly like drawCheckersSquare, then optionally frames
// it with a highlight border -- pass the square's own current piece (from
// CheckersBoard::at()) either way, and highlighted=true for e.g. the
// currently-selected source square or one of its legal destinations, false
// to clear a highlight that was there before. Nice-to-have, not required by
// the two-step tap flow below (that flow works fine with zero visual
// feedback beyond the piece actually moving), but cheap and worth using.
void drawCheckersSquareHighlight(TFT_eSPI &tft, const CheckersLayout &layout, uint8_t row, uint8_t col,
                                  CheckersPiece piece, bool highlighted);

void drawCheckersPlayAgainButton(TFT_eSPI &tft, const CheckersLayout &layout);
void hideCheckersPlayAgainButton(TFT_eSPI &tft, const CheckersLayout &layout);

// Hit-testing helpers -- pure math, no TFT_eSPI calls, so they're testable
// on a desktop with zero hardware (see native_test/checkers_playtest.cpp).
// Return true and write the result (out params) when (touchX, touchY) in
// SCREEN coordinates (already rotated/calibrated by TFT_eSPI's getTouch)
// falls inside that element.
//
// Checkers needs a two-step interaction (tap a source square, then tap a
// destination square) rather than Tic-Tac-Toe's one-tap-per-move, but both
// taps land on the same 8x8 grid -- so there's exactly ONE hit-test function
// for squares, used for both steps. The .ino tells the two steps apart with
// its own small piece of selection state (e.g. "do I currently have a
// selected square?"), not with anything from this module:
//
//   uint8_t selRow, selCol; bool haveSelection = false;
//   ...on a tap that resolves to (row, col) via hitTestSquare...
//   if (!haveSelection) {
//       if (board.hasLegalMoveFrom(row, col)) { selRow=row; selCol=col; haveSelection=true; }
//   } else if (board.isLegalMove(selRow, selCol, row, col)) {
//       board.playHuman(selRow, selCol, row, col);
//       haveSelection = board.inForcedContinuation(selRow, selCol); // same piece must jump again?
//   } else {
//       haveSelection = false; // tapped somewhere else illegal -- drop the selection, same as any other illegal tap in this project
//   }
//
// CheckersLogic.h's isLegalMove()/hasLegalMoveFrom()/legalDestinationsFrom()
// already carry all the rules knowledge (mandatory capture, forced
// continuation, direction/king rules) this flow needs -- nothing further is
// required from this module to build it.
bool hitTestSquare(const CheckersLayout &layout, int16_t touchX, int16_t touchY, uint8_t &outRow, uint8_t &outCol);
bool hitTestCheckersPlayAgainButton(const CheckersLayout &layout, int16_t touchX, int16_t touchY);

// A small "back to arcade menu" button drawn as part of the status bar (see
// drawCheckersStatus) -- always present during a round, matching Display.h's
// hitTestHomeButton for Tic-Tac-Toe.
bool hitTestCheckersHomeButton(const CheckersLayout &layout, int16_t touchX, int16_t touchY);
