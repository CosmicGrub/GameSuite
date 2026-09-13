#pragma once
#include <TFT_eSPI.h>
#include "DominoesLogic.h"

// All drawing + touch hit-testing lives here -- DominoesLogic.h/.cpp knows
// nothing about TFT_eSPI, same engine/screen split every other game in this
// arcade uses. A tile is drawn as a small rounded rect split by a divider
// into two pip-dot halves (real 0-6 dot patterns, like a physical domino or
// die face -- not digits), matching this arcade's existing "shapes, never
// image assets" rendering style while still looking authentically like a
// domino rather than a labeled card.
//
// Because two adjacent chain tiles always share a matching touching value
// (see DominoesBoard::applyPlay()'s own comment -- attaching a tile always
// sets its OWN facing value equal to whatever end it's extending), simply
// drawing each chain tile's two pip-halves side by side, in chain order,
// with no gap between tiles, reproduces a real physical chain's look for
// free -- no rotation/"spinner" layout logic needed (this project's chain,
// like the Kotlin source it's ported from, is always a single straight
// line, never a branching board).
//
// The chain can hold up to 28 tiles -- far more than one screen width can
// show at a reasonable pip size -- so it's rendered as a horizontally
// SCROLLABLE strip: only a window of `dominoesChainVisibleTileCount()`
// tiles is drawn at once, starting at a scrollOffset the .ino owns (same
// "the .ino tracks scroll position, Display just renders whatever window
// it's told to" split MenuScreen.h's own menuScrollOffset uses). The two
// chevrons at the track's ends serve DOUBLE DUTY, exactly mirroring how a
// physical touchscreen app reuses one control for context-dependent
// actions: with no tile selected (or a selected tile that's only legal on
// one end, which plays immediately with no further tap needed), they
// scroll the visible window; once a selected hand tile is legal on BOTH
// exposed ends (a real, common ambiguity -- see DominoesBoard::
// canAttachLeft()/canAttachRight()), they become "play here" drop zones
// instead (drawSelectionDropZones() below draws them filled/highlighted
// rather than as plain outline arrows) -- the SAME two fixed rectangles
// either way, so there's exactly one pair of hit-test functions for both
// meanings; the .ino's own selection state is what decides which meaning
// applies to a given tap.

struct DominoesLayout {
    int16_t statusY, statusH;                 // status bar: home button (left), difficulty chip (right), status text (center)
    int16_t difficultyX, difficultyY, difficultyW, difficultyH;

    int16_t aiHandY, aiHandH;                 // AI's face-down hand strip (count only, never contents)

    int16_t chainY, chainH;                   // the scrollable chain track
    int16_t chevronW;                         // width of each end chevron/drop-zone (chainX/chainW below is what's left between them)
    int16_t chainX, chainW;                   // the tile-drawing area BETWEEN the two chevrons

    int16_t boneyardX, boneyardY, boneyardW, boneyardH; // draw pile -- tappable
    int16_t passX, passY, passW, passH;                 // "Pass" button -- always shown on the human's turn; tapping it while illegal is a harmless no-op, same convention as everywhere else in this project

    int16_t handY, handH;                     // human hand row
    int16_t tileW, tileH;                     // shared tile size for hand AND chain tiles (see this file's own header comment on why they match)
    int16_t handGap, handMarginX;             // hand tiles spread out, or overlap if they wouldn't otherwise fit -- same idea as UnoLayout's own handGap/handMarginX

    int16_t buttonX, buttonY, buttonW, buttonH; // "Play Again" -- shown only once the hand ends, drawn centered over the chain track (nothing there is interactive anymore once the hand is over)
};

// Computed once from Config.h's SCREEN_WIDTH/SCREEN_HEIGHT, same contract as
// every other game's own computeXLayout().
DominoesLayout computeDominoesLayout();

// How many chain tiles actually fit in the visible track at once -- the
// .ino uses this to bound/clamp its own scrollOffset; kept here (not
// re-derived at each call site) so the "how many fit" formula lives in
// exactly one place.
uint8_t dominoesChainVisibleTileCount(const DominoesLayout &layout);

void drawDominoesStaticChrome(TFT_eSPI &tft, const DominoesLayout &layout);
void drawDominoesStatus(TFT_eSPI &tft, const DominoesLayout &layout, const char *text, CpuDifficulty difficulty);

// AI hand: face-down only -- draws `count` tile-back rectangles plus an
// "AI: N tiles" label. Never takes tile contents (Display, like
// DominoesLogic, has no way to see the AI's hand).
void drawDominoesAiHand(TFT_eSPI &tft, const DominoesLayout &layout, uint8_t count);

// Redraws the entire human hand row from scratch -- call after every human
// or AI action that can change hand size, same "just redraw the whole
// thing" spirit as every other game's own hand/board redraw. `playableMask`
// bit i set means humanHandTileIsPlayable(i) was true when this was called
// -- an unplayable tile is drawn dimmed, the same kind of at-a-glance
// legality cue Checkers' legal-destination highlighting gives for free.
void drawDominoesHumanHand(TFT_eSPI &tft, const DominoesLayout &layout, const DominoTileView *tiles, uint8_t count,
                            uint32_t playableMask, int8_t selectedIndex);

// Draws the chain's visible window starting at `scrollOffset` (a tile
// index, clamped by the caller to [0, chainLength - visibleCount]), plus
// "N hidden" overflow badges on either chevron side when tiles exist
// beyond the visible window in that direction. `board` supplies the tiles
// themselves (chainTileAt/chainTileFlippedAt) and its own leftEndValue()/
// rightEndValue() are what the chevrons compare against when deciding
// whether a selected tile could legally drop at that end (see
// drawSelectionDropZones() below, called separately since which meaning
// applies is the .ino's own selection-state decision, not something this
// function can know on its own).
void drawDominoesChain(TFT_eSPI &tft, const DominoesLayout &layout, const DominoesBoard &board, uint8_t scrollOffset);

// Redraws just the two end chevrons as plain scroll arrows (the "no
// ambiguous selection" state) -- `canScrollLeft`/`canScrollRight` dim
// whichever direction has nothing further to reveal, same enabled/disabled
// visual convention MenuScreen.cpp's own scroll arrows use.
void drawDominoesScrollChevrons(TFT_eSPI &tft, const DominoesLayout &layout, bool canScrollLeft, bool canScrollRight);

// Redraws just the two end chevrons as filled "play here" drop zones
// instead -- `legalOnLeft`/`legalOnRight` say which of the two the
// currently-selected hand tile could actually legally drop into (both true
// is the whole reason this overlay exists at all; the .ino never calls
// this when only one side is legal, since that case plays immediately with
// no further tap).
void drawDominoesSelectionDropZones(TFT_eSPI &tft, const DominoesLayout &layout, bool legalOnLeft, bool legalOnRight);

void drawDominoesBoneyard(TFT_eSPI &tft, const DominoesLayout &layout, uint8_t remainingCount);
void drawDominoesPassButton(TFT_eSPI &tft, const DominoesLayout &layout);

void drawDominoesPlayAgainButton(TFT_eSPI &tft, const DominoesLayout &layout);
void hideDominoesPlayAgainButton(TFT_eSPI &tft, const DominoesLayout &layout);

// ---- Hit-testing -- pure math, no TFT_eSPI calls, so it's testable on a
// desktop with zero hardware (see native_test/dominoes_playtest.cpp). ----

// Which hand-tile index (if any) covers (touchX, touchY) -- `handCount`
// must be the SAME count the hand was last drawn with (see
// drawDominoesHumanHand()), same contract as UnoDisplay.h's own
// hitTestUnoHumanHandCard(). When tiles overlap (a large hand), the
// higher (further-right, visually topmost) index wins.
bool hitTestDominoesHumanHandTile(const DominoesLayout &layout, uint8_t handCount, int16_t touchX, int16_t touchY, uint8_t &outIndex);

bool hitTestDominoesBoneyard(const DominoesLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestDominoesPassButton(const DominoesLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestDominoesPlayAgainButton(const DominoesLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestDominoesHomeButton(const DominoesLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestDominoesDifficultyButton(const DominoesLayout &layout, int16_t touchX, int16_t touchY);

// The two chevron/drop-zone rectangles at the chain track's ends -- ONE
// pair of hit-tests regardless of which of the two meanings (scroll vs.
// drop-zone) currently applies, per this file's own header comment.
bool hitTestDominoesLeftChevron(const DominoesLayout &layout, int16_t touchX, int16_t touchY);
bool hitTestDominoesRightChevron(const DominoesLayout &layout, int16_t touchX, int16_t touchY);
