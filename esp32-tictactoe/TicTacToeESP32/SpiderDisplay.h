#pragma once
#include <TFT_eSPI.h>
#include "SpiderLogic.h"

// All drawing + touch hit-testing lives here -- SpiderLogic.h/.cpp knows
// nothing about TFT_eSPI, same engine/screen split every other game in this
// arcade uses. Cards render the same way SolitaireDisplay.cpp's Klondike
// cards do (a short rank+suit-letter label like "10H"/"AS", red for
// diamonds/hearts, the normal text color for clubs/spades) -- duplicated
// here rather than shared, since every other game's own Display module is
// already fully self-contained with no cross-game Display code sharing
// anywhere in this project.
//
// A column's card-to-card vertical spacing dynamically compresses to fit,
// mirroring SolitaireDisplay.h's own solitaireFanOffset() (itself mirroring
// UnoDisplay.cpp's hand-row compression) -- but Spider's own columns can
// grow considerably deeper than Klondike's ever do (see SpiderLogic.h's own
// top comment on why a column's size is only loosely bounded), so this is
// an HONEST, ACCEPTED limitation worth stating plainly: an extremely
// deep column (well beyond what real play typically produces) can still run
// past the bottom of the screen once the spacing has already compressed to
// its own floor -- there is no tableau scrolling here the way Dominoes'
// chain track has. Ordinary games are nowhere close to deep enough to hit
// this.
struct SpiderLayout {
    int16_t statusY, statusH;
    int16_t seqChipX, seqChipY, seqChipW, seqChipH; // "Seq: X/8" -- status-bar chip, mirrors every other game's own status-bar chip position

    int16_t newX, newY, newW, newH;   // "New"
    int16_t undoX, undoY, undoW, undoH; // "Undo"
    int16_t stockX, stockY, stockW, stockH; // tappable -- deals one card onto every column at once

    int16_t cardW, cardH;

    int16_t tableauY, tableauH;
    int16_t colX[SPIDER_COLUMN_COUNT];
    int16_t colStride;
};

SpiderLayout computeSpiderLayout();

// How far apart (vertically) consecutive cards in a column of
// `totalCardsInColumn` should be drawn -- see this file's own top comment.
int16_t spiderFanOffset(const SpiderLayout &layout, uint8_t totalCardsInColumn);

void drawSpiderStaticChrome(TFT_eSPI &tft, const SpiderLayout &layout);
void drawSpiderStatus(TFT_eSPI &tft, const SpiderLayout &layout, const char *text, uint8_t completedSequences);
void drawSpiderButtons(TFT_eSPI &tft, const SpiderLayout &layout);
void drawSpiderStock(TFT_eSPI &tft, const SpiderLayout &layout, uint8_t remainingCount, bool dealAvailable);

// Redraws one whole tableau column fresh -- cheap enough to call after
// every move instead of tracking exactly which cards changed. The column's
// selected card (if this IS the selected column) is drawn highlighted,
// read straight off `board` rather than taken as a parameter.
void drawSpiderColumn(TFT_eSPI &tft, const SpiderLayout &layout, const SpiderBoard &board, uint8_t col);
void drawSpiderTableau(TFT_eSPI &tft, const SpiderLayout &layout, const SpiderBoard &board);

// ---- Hit-testing -- pure math, no TFT_eSPI calls, so it's testable on a
// desktop with zero hardware (see native_test/spider_playtest.cpp). ----
bool hitTestSpiderNewButton(const SpiderLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestSpiderUndoButton(const SpiderLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestSpiderStock(const SpiderLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestSpiderHomeButton(const SpiderLayout &layout, int16_t touchX, int16_t touchY);

// Which tableau column (if any) covers (touchX, touchY) -- accounts for
// that column's own current fan height, same "each column's own tap target
// stops where its own cards actually stop" contract
// hitTestSolitaireTableau() gives for Klondike.
bool hitTestSpiderTableau(const SpiderLayout &layout, const SpiderBoard &board, int16_t touchX, int16_t touchY, uint8_t &outCol);
