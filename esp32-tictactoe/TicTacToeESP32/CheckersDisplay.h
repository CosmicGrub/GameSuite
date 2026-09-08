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
// simplicity tradeoff Tic-Tac-Toe's own small grid makes. Also redraws the
// small per-side captured-piece tray in the board's idle side margins (see
// drawCheckersCapturedTray() in the .cpp), so every existing call site picks
// it up automatically.
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

// Derives a checkers jump's own capture square the same way this file's
// implementation does internally, exposed so a caller (see the .ino's human
// move handling) can snapshot a hop's captured piece from the board BEFORE
// mutating it, without duplicating this arithmetic at the call site. Returns
// false (outMidRow/outMidCol unwritten) when fromRow/toRow aren't a 2-row
// diagonal apart -- i.e. this hop isn't a jump at all. Checkers' own capture
// square is always this exact geometric midpoint, unlike chess en passant
// (see ChessDisplay.h/animateChessMove()), so no per-hop override is needed.
bool checkersJumpMidpoint(uint8_t fromRow, uint8_t fromCol, uint8_t toRow, uint8_t toCol,
                           uint8_t &outMidRow, uint8_t &outMidCol);

// Animates `piece` sliding through an ordered chain of one or more hops
// instead of vanishing from its start square and instantly appearing at its
// end. waypointRows[i]/waypointCols[i] for i in 0..hopCount (inclusive, so
// hopCount+1 entries) are the squares the piece visits in order: waypoint 0
// is where it starts, waypoint hopCount is where it ends up, and hop `i`
// (0..hopCount-1) is the timeline segment sliding from waypoint i to
// waypoint i+1. hopCount == 1 is an ordinary single move or single jump --
// exactly what this function used to accept directly as a lone
// fromRow/fromCol/toRow/toCol pair -- and hopCount > 1 is a forced
// multi-jump chain, each hop animated as its own segment in turn, its own
// captured piece (if any) erased the instant that hop's own slide finishes
// rather than all at once up front.
//
// capturedPieces[i] (0..hopCount-1) is the piece hop i's own jump captured,
// exactly as it stood immediately before that hop removed it -- pass
// CheckersPiece::EMPTY for a non-capturing hop (only possible when
// hopCount == 1: a forced continuation only ever continues after an actual
// capture). This can't be derived from `board` inside this function: by the
// time ANY caller gets here, every hop up to and including the one currently
// animating may already be mutated into the board (see the AI bullet
// below), so the capture square would read EMPTY regardless of which hop is
// on screen. checkersJumpMidpoint() above locates each hop's own capture
// square (always the exact geometric midpoint for checkers) for a caller
// that needs to read it from a not-yet-mutated board itself.
//
// `board` supplies everything else on screen during the slide (every square
// other than the waypoints and the current hop's own capture square, which
// this function always draws empty/correct for itself) -- so this works
// whether called:
//   - BEFORE mutating the board for a human move (hopCount always 1: a
//     human plays one hop per tap, so there's only ever the one segment),
//     passing board.at(waypointRows[0], waypointCols[0]) as `piece`; or
//   - AFTER CheckersBoard::playAi() has already applied the AI's whole turn,
//     every hop included (every waypoint square already reflects the FINAL
//     state), passing board.at(waypointRows[hopCount], waypointCols[hopCount])
//     as `piece`, CheckersBoard::lastAiWaypointRow()/lastAiWaypointCol() as
//     the waypoint arrays, and CheckersBoard::lastAiHopCapturedPiece() as
//     capturedPieces.
// Either way, call drawCheckersBoard() once more after this returns to show
// the real, final state (promotion/kinging in particular) -- this function
// only animates the slide itself and never modifies `board`.
void animateCheckersMove(TFT_eSPI &tft, const CheckersLayout &layout, const CheckersBoard &board,
                          const uint8_t waypointRows[], const uint8_t waypointCols[],
                          const CheckersPiece capturedPieces[], uint8_t hopCount, CheckersPiece piece);
