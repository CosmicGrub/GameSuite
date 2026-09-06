#pragma once
#include <TFT_eSPI.h>
#include "ChessLogic.h"

// All drawing lives here -- ChessLogic.h/.cpp knows nothing about TFT_eSPI,
// and the .ino only calls these functions plus ChessLogic's, the same
// engine/screen split Display.h uses for Tic-Tac-Toe. Every piece is a
// single bold letter (P/N/B/R/Q/K -- the SAME letters for both sides, told
// apart by color, not case) on a plain two-tone checkerboard -- no image
// assets anywhere, same as the rest of this project. Colors are restricted
// to the same palette Display.cpp/CheckersDisplay.cpp already use (TFT_eSPI's
// own named constants: cyan for the human's side, orange for the AI's,
// etc.) so the whole arcade reads as one consistent product.
//
// The shared "OS chrome" (status bar + Home button) lives in Chrome.h, NOT
// here -- see that header's own top comment, which explicitly names Chess as
// an intended consumer. Call drawChromeBar(tft, text) for status text and
// hitTestChromeHome(touchX, touchY) for the Home button (checked once by the
// .ino before dispatching to whichever game is active, per Chrome.h); this
// module owns only the chess board itself, matching how Display.h/.cpp were
// updated to drop their own copies of both once Chrome.h existed.
//
// Board orientation: the human always plays White, so White's home rank
// (ChessLogic.h square-index row 0) is drawn at the BOTTOM of the screen and
// the a-file on the LEFT -- the same orientation a human sees sitting across
// a real board from an opponent playing Black.

struct ChessLayout {
    int16_t boardX, boardY, squareSize; // 8x8 board, top-left corner + one square's side length
    int16_t statusY, statusH;           // reserved chrome-bar space above the board (see Chrome.h) -- this module never draws into it
    int16_t buttonX, buttonY, buttonW, buttonH; // "Play Again", shown only once a round ends
};

// Computed once from the panel's rotated width/height (Config.h) and
// Chrome.h's fixed CHROME_BAR_HEIGHT -- SCREEN_WIDTH/SCREEN_HEIGHT there
// must match tft.setRotation()'s result.
ChessLayout computeChessLayout();

// One-time background fill for a fresh round -- the board's own squares are
// (re)drawn by drawChessBoard below, and the status bar is Chrome.h's job,
// so this only needs to cover the margin around the board.
void drawChessStaticChrome(TFT_eSPI &tft, const ChessLayout &layout);

// Redraws the entire 8x8 board in one pass: every square's checker color,
// whatever piece sits there, and three highlight layers on top --
// the last move played (if any, from board.lastMove()), a check highlight
// on whichever king is currently attacked (if any, from board.inCheck()),
// and, when `selectedSquare` >= 0 (the human's first tap of the two-tap
// interaction below), a highlight on that square plus a marker on each of
// its legal destinations (a small dot for a quiet move, a ring for a
// capture, so a capturable enemy piece's own letter stays visible under it).
// Pass selectedSquare = -1 when nothing is currently selected.
//
// Redrawing all 64 squares every time is cheap enough at this board size to
// call after every move AND after every selection change instead of
// tracking exactly which squares changed -- the same simplicity tradeoff
// CheckersDisplay.h's drawCheckersBoard makes for the same 8x8 size.
void drawChessBoard(TFT_eSPI &tft, const ChessLayout &layout, const ChessBoard &board, int8_t selectedSquare);

void drawChessPlayAgainButton(TFT_eSPI &tft, const ChessLayout &layout);
void hideChessPlayAgainButton(TFT_eSPI &tft, const ChessLayout &layout);

// Pure hit-test, no TFT_eSPI calls, so it's testable on a desktop with zero
// hardware (see native_test/chess_playtest.cpp). Returns true and writes the
// tapped square's (row, col) -- ChessLogic.h's own 0..63 square index is
// row*8+col -- when (touchX, touchY) in SCREEN coordinates (already
// rotated/calibrated by TFT_eSPI's getTouch) falls inside the board.
//
// Chess needs the same two-step interaction as Checkers (tap a source
// square, then tap a destination square) rather than Tic-Tac-Toe's
// one-tap-per-move, and both taps land on the same 8x8 grid -- so there is
// exactly ONE hit-test function for squares here too, used for both steps,
// matching CheckersDisplay.h's own hitTestSquare shape so both games'
// integration code reads the same way:
//
//   static int8_t selectedSquare = -1;
//   ...on a tap that resolves to (row, col) via hitTestSquare...
//   uint8_t sq = row * 8 + col;
//   uint8_t dests[32];
//   if (selectedSquare < 0) {
//       if (board.legalDestinations(sq, dests) > 0) selectedSquare = sq; // else: tapped an empty/opponent square with no selection -- ignored
//   } else if (sq == selectedSquare) {
//       selectedSquare = -1; // tapping the selected piece again deselects it
//   } else if (board.playHuman((uint8_t)selectedSquare, sq)) {
//       selectedSquare = -1; // move made -- AI's turn next
//   } else {
//       selectedSquare = (board.legalDestinations(sq, dests) > 0) ? (int8_t)sq : -1; // tapped another of your own pieces, or an illegal square
//   }
//   drawChessBoard(tft, layout, board, selectedSquare);
//
// playHuman() re-validates the move fully on its own (see ChessLogic.h), so
// this selection state is purely a UI convenience for what to highlight --
// it is never itself trusted as a legality decision.
bool hitTestSquare(const ChessLayout &layout, int16_t touchX, int16_t touchY, uint8_t &outRow, uint8_t &outCol);
bool hitTestChessPlayAgainButton(const ChessLayout &layout, int16_t touchX, int16_t touchY);
