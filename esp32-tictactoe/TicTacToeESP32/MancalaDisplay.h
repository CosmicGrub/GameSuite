#pragma once
#include <TFT_eSPI.h>
#include "MancalaLogic.h"

// All drawing lives here -- MancalaLogic.h/.cpp knows nothing about
// TFT_eSPI, and the .ino only calls these functions plus MancalaLogic's,
// the same engine/screen split every other game in this arcade uses. Every
// shape is a TFT_eSPI primitive (fillCircle/fillRoundRect/drawString) -- no
// image assets, same as the rest of this project.
//
// Board layout, top-down as actually drawn (matches MancalaLogic.h's own
// index layout exactly -- see that file's header comment):
//
//   [AI store 13] [pit12][pit11][pit10][pit9][pit8][pit7]
//   [   (tall)   ] [pit0 ][pit1 ][pit2 ][pit3][pit4][pit5]  [Human store 6]
//
// Column c (0..5) holds human pit `c` on the bottom row directly below AI
// pit `12-c` on the top row -- deliberately aligned so each column's two
// pits are MancalaLogic.h's own "opposite pit" pair (opposite of cursor is
// always 12-cursor), the same way a physical Mancala board's columns do.
struct MancalaLayout {
    int16_t statusY, statusH;

    int16_t boardX, boardY;       // top-left of the 6-column PIT GRID (excludes both end stores)
    int16_t colStride, rowStride; // column/row pitch, center-to-center
    int16_t pitSize;              // a single pit's own drawn diameter (<= min(colStride, rowStride))

    int16_t storeY, storeW, storeH; // both stores share one Y/size, one at each end of the pit grid
    int16_t aiStoreX, humanStoreX;

    int16_t buttonX, buttonY, buttonW, buttonH; // "Play Again" -- shown only once a round ends, drawn centered over the board (nothing beneath it is interactive anymore once the round is over, same convention CheckersDisplay/ChessDisplay already use for their own Play Again buttons)

    int16_t difficultyX, difficultyY, difficultyW, difficultyH; // status-bar "AI: MED" chip -- always visible during play, tap to cycle EASY/MEDIUM/HARD
};

// Computed once from the panel's rotated width/height (see Config.h) --
// SCREEN_WIDTH/SCREEN_HEIGHT there must match tft.setRotation()'s result.
MancalaLayout computeMancalaLayout();

void drawMancalaStaticChrome(TFT_eSPI &tft, const MancalaLayout &layout);

// Draws the status bar's text plus its two persistent chips -- the shared
// Home button (left) and the difficulty toggle (right, see
// hitTestMancalaDifficultyButton below); `difficulty` is drawn as
// "AI: EASY"/"AI: MED"/"AI: HARD" so the current tier is always visible,
// not just changeable.
void drawMancalaStatus(TFT_eSPI &tft, const MancalaLayout &layout, const char *text, CpuDifficulty difficulty);

// Draws every one of the 14 pits (12 playable pits + 2 stores) fresh from
// `board` -- cheap enough to call after every sow instead of tracking
// exactly which pits changed, same simplicity tradeoff Checkers' own
// drawCheckersBoard makes for its 64 squares.
void drawMancalaBoard(TFT_eSPI &tft, const MancalaLayout &layout, const MancalaBoard &board);

// Redraws just one pit (0-13, store indices included) -- an optional
// finer-grained alternative to drawMancalaBoard for callers that only need
// to refresh the handful of pits one sow actually touched.
void drawMancalaPit(TFT_eSPI &tft, const MancalaLayout &layout, uint8_t pitIndex, uint8_t stoneCount);

void drawMancalaPlayAgainButton(TFT_eSPI &tft, const MancalaLayout &layout);
void hideMancalaPlayAgainButton(TFT_eSPI &tft, const MancalaLayout &layout);

// Seed-hop cascade (Premium 2026 Vision pitch's own animation vocabulary,
// carried over from GameSuite's Compose implementation's lastSowPath --
// see MancalaLogic.h's KDoc): briefly highlights each pit `board`'s most
// recently played sow actually visited, in order, before a caller's own
// final drawMancalaBoard() redraw shows the settled counts. Reads the sow's
// path/capture info straight off `board` (MancalaBoard::lastSowPathLength()/
// lastSowPathPit()/lastMoveCaptured()) rather than taking them as
// parameters, since by the time any caller gets here the sow has already
// been applied -- there is nothing further for a caller to snapshot first
// (unlike Checkers' captured-piece snapshot, Mancala's capture destination
// is always the mover's own store, never a square this animation would
// otherwise draw wrong).
void animateMancalaSow(TFT_eSPI &tft, const MancalaLayout &layout, const MancalaBoard &board);

// Hit-testing helpers -- pure math, no TFT_eSPI calls, so they're testable
// on a desktop with zero hardware (see native_test/mancala_playtest.cpp).
// Return true and write the result (out params) when (touchX, touchY) in
// SCREEN coordinates falls inside that element.

// Unlike Checkers/Chess, Mancala needs no two-step "select then destination"
// flow -- a single tap on a legal pit sows immediately. This hit-test finds
// ANY of the 12 playable pits (0-5, 7-12) a tap landed in, regardless of
// whose turn it actually is or whether that pit currently has any stones --
// MancalaLogic.h's own isLegalMove() is what the .ino should check next,
// exactly the same "pure geometry here, rules over there" split
// hitTestSquare() uses for Checkers.
bool hitTestMancalaPit(const MancalaLayout &layout, int16_t touchX, int16_t touchY, uint8_t &outPitIndex);

bool hitTestMancalaPlayAgainButton(const MancalaLayout &layout, int16_t touchX, int16_t touchY);

// A small "back to arcade menu" button drawn as part of the status bar (see
// drawMancalaStatus) -- always present during a round, matching
// CheckersDisplay.h's hitTestCheckersHomeButton.
bool hitTestMancalaHomeButton(const MancalaLayout &layout, int16_t touchX, int16_t touchY);

// The status bar's difficulty chip (see drawMancalaStatus) -- tapping it
// cycles EASY -> MEDIUM -> HARD -> EASY. Always live during a round (unlike
// the Play Again button, changing difficulty mid-game is harmless: it only
// affects the AI's NEXT move, same as changing a setting between turns in
// any other game).
bool hitTestMancalaDifficultyButton(const MancalaLayout &layout, int16_t touchX, int16_t touchY);
