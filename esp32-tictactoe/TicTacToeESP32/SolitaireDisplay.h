#pragma once
#include <TFT_eSPI.h>
#include "SolitaireLogic.h"

// All drawing + touch hit-testing lives here -- SolitaireLogic.h/.cpp knows
// nothing about TFT_eSPI, same engine/screen split every other game in this
// arcade uses. A card is a small rounded rect with a short rank+suit-letter
// text label (e.g. "10H", "AS", "KD") -- suit-letter text rather than a
// drawn pip/glyph, matching this project's general "shapes + text, never a
// bespoke new drawing subsystem" style (the same call UNO's own card labels
// already made) -- red for diamonds/hearts, the normal text color for
// clubs/spades (this arcade's dark theme has no true "black" ink color to
// reach for).
//
// A tableau column can hold more cards than one screen height shows at a
// fully-spread spacing, so each column's own card-to-card vertical offset
// is DYNAMICALLY compressed once needed -- solitaireFanOffset() below is
// the single place that math lives, shared by both drawing and hit-testing
// so they can never disagree about where a card actually is. Mirrors
// UnoDisplay.cpp's own handAdvance()'s "natural spacing unless it would
// overflow, then compress" idiom exactly, just applied vertically instead
// of horizontally.
struct SolitaireLayout {
    int16_t statusY, statusH;
    int16_t wonChipX, wonChipY, wonChipW, wonChipH; // "Won: N" -- status-bar chip, mirrors Mancala/Dominoes' own difficulty chip position

    int16_t newX, newY, newW, newH;     // "New" -- always available, unlike every other game's Play Again (a Klondike deal can be unwinnable; a player shouldn't have to wait for a win to restart)
    int16_t undoX, undoY, undoW, undoH; // "Undo"
    int16_t autoX, autoY, autoW, autoH; // "Auto" -- auto-complete, only meaningful once SolitaireBoard::autoCompleteAvailable() is true; tapping it otherwise is a harmless no-op

    int16_t cardW, cardH; // shared size for every card face/back everywhere on this screen

    int16_t stockX, stockY;
    int16_t wasteX, wasteY;
    int16_t foundationX[SOLITAIRE_SUIT_COUNT]; // indexed by (uint8_t)SolitaireSuit; one pile per suit, fixed left-to-right order
    int16_t foundationY;

    int16_t tableauY, tableauH;
    int16_t colX[SOLITAIRE_COLUMN_COUNT];
    int16_t colStride; // == colX[i+1] - colX[i]; also colX's own increment, kept explicit rather than re-derived
};

// Computed once from Config.h's SCREEN_WIDTH/SCREEN_HEIGHT, same contract as
// every other game's own computeXLayout().
SolitaireLayout computeSolitaireLayout();

// How far apart (vertically) consecutive cards in a column of
// `totalCardsInColumn` should be drawn -- a fixed, comfortably-readable
// spacing unless that many cards wouldn't fit in the tableau's own height,
// in which case it compresses just enough that they do (never below a
// hard floor, so an extreme column never collapses into an unreadable
// sliver). Exposed publicly so native_test can verify hit-testing/layout
// math against it directly.
int16_t solitaireFanOffset(const SolitaireLayout &layout, uint8_t totalCardsInColumn);

void drawSolitaireStaticChrome(TFT_eSPI &tft, const SolitaireLayout &layout);
void drawSolitaireStatus(TFT_eSPI &tft, const SolitaireLayout &layout, const char *text, uint16_t gamesWon);

void drawSolitaireButtons(TFT_eSPI &tft, const SolitaireLayout &layout, bool autoAvailable);

void drawSolitaireStock(TFT_eSPI &tft, const SolitaireLayout &layout, uint8_t remainingCount);
void drawSolitaireWaste(TFT_eSPI &tft, const SolitaireLayout &layout, const SolitaireBoard &board);
void drawSolitaireFoundation(TFT_eSPI &tft, const SolitaireLayout &layout, const SolitaireBoard &board, SolitaireSuit suit);

// Redraws one whole tableau column fresh (its own vertical strip only) --
// cheap enough to call after every move instead of tracking exactly which
// cards changed, same simplicity tradeoff every other game's own
// full-board redraw makes. The column's currently-selected card (if this IS
// the selected column) is drawn with a highlighted border, read straight
// off `board` (SolitaireBoard::selectionSource()/selectionColumn()) rather
// than taken as a parameter.
void drawSolitaireColumn(TFT_eSPI &tft, const SolitaireLayout &layout, const SolitaireBoard &board, uint8_t col);

// Convenience: drawSolitaireColumn() for every column, plus the waste (the
// one other pile whose OWN drawing depends on the current selection, since
// its top card can be the selected one too) -- called after any action
// that could have changed tableau contents or the selection itself.
void drawSolitaireTableauAndWaste(TFT_eSPI &tft, const SolitaireLayout &layout, const SolitaireBoard &board);

// ---- Hit-testing -- pure math, no TFT_eSPI calls, so it's testable on a
// desktop with zero hardware (see native_test/solitaire_playtest.cpp). ----
bool hitTestSolitaireStock(const SolitaireLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestSolitaireWaste(const SolitaireLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestSolitaireFoundation(const SolitaireLayout &layout, int16_t touchX, int16_t touchY, SolitaireSuit &outSuit);

// Which tableau column (if any) covers (touchX, touchY) -- accounts for
// that column's OWN current fan height (a short column's empty space below
// it is NOT part of its own tap target, and never overlaps a neighboring
// column either, since columns never share horizontal extent). An empty
// column still has a one-card-tall tap target at the top of the tableau
// area, so tapping "where a card would go" always works even with nothing
// there yet.
bool hitTestSolitaireTableau(const SolitaireLayout &layout, const SolitaireBoard &board, int16_t touchX, int16_t touchY, uint8_t &outCol);

bool hitTestSolitaireNewButton(const SolitaireLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestSolitaireUndoButton(const SolitaireLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestSolitaireAutoButton(const SolitaireLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestSolitaireHomeButton(const SolitaireLayout &layout, int16_t touchX, int16_t touchY);
