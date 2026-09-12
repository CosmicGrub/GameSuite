#include "CheckersDisplay.h"
#include "Config.h"
#include "Theme.h"
#include <cstdio> // snprintf -- drawCapturedSideTray()'s "xN" count text

// Colors go through Theme.h's shared palette so the whole arcade reads as
// one consistent product -- human pieces teal, AI pieces amber, exactly
// like every other game's human/AI pair.
static const uint16_t COLOR_BG            = THEME_BG;
static const uint16_t COLOR_LIGHT_SQUARE  = THEME_BOARD_LIGHT;
static const uint16_t COLOR_DARK_SQUARE   = THEME_BOARD_DARK;
static const uint16_t COLOR_HUMAN_PIECE   = THEME_HUMAN;
static const uint16_t COLOR_AI_PIECE      = THEME_AI;
static const uint16_t COLOR_PIECE_BORDER  = THEME_TEXT;
static const uint16_t COLOR_KING_TEXT     = THEME_BG;
static const uint16_t COLOR_HIGHLIGHT     = THEME_SUCCESS;
static const uint16_t COLOR_STATUS_BG     = THEME_SURFACE;
static const uint16_t COLOR_STATUS_TEXT   = THEME_TEXT;
static const uint16_t COLOR_BUTTON_BG     = THEME_SURFACE_ALT;
static const uint16_t COLOR_BUTTON_TEXT   = THEME_TEXT;

// Same fixed "back to menu" button Display.cpp draws for Tic-Tac-Toe, sized
// off the status bar's own height rather than a magic screen-relative
// position.
static const int16_t HOME_BTN_SIZE = 30;
static const int16_t HOME_BTN_MARGIN = 2;

// Quick-win captured-piece tray (see drawCheckersCapturedTray() below): 12
// men per side at CheckersBoard::reset(), same convention as CheckersLogic.h's
// own CHECKERS_MAX_MOVES-style named constants rather than a bare literal.
static const uint8_t CHECKERS_STARTING_PIECES = 12;

// Quick-win drop-shadow depth cue (see drawPieceGlyphAtCenter() below): a
// few px, not a fraction of cellSize -- this project ships one fixed
// SCREEN_WIDTH/SCREEN_HEIGHT (Config.h), so computeCheckersLayout() always
// yields the same cellSize/pad here, and a flat 3px sits safely inside that
// pad (see drawPieceGlyphAtCenter) with room to spare.
static const int16_t PIECE_SHADOW_OFFSET = 3;

static int16_t homeButtonTop(const CheckersLayout &layout) {
    return layout.statusY + (layout.statusH - HOME_BTN_SIZE) / 2;
}

CheckersLayout computeCheckersLayout() {
    CheckersLayout l;
    l.statusY = 0;
    l.statusH = 36;

    int16_t availW = SCREEN_WIDTH;
    int16_t availH = SCREEN_HEIGHT - l.statusH;
    l.cellSize = (availW < availH ? availW : availH) / 8;
    int16_t boardSize = l.cellSize * 8;
    l.boardX = (SCREEN_WIDTH - boardSize) / 2;
    l.boardY = l.statusY + l.statusH + (availH - boardSize) / 2;

    l.buttonW = 140;
    l.buttonH = 44;
    l.buttonX = (SCREEN_WIDTH - l.buttonW) / 2;
    l.buttonY = l.boardY + (boardSize - l.buttonH) / 2;
    return l;
}

static void squareOrigin(const CheckersLayout &l, uint8_t row, uint8_t col, int16_t &x, int16_t &y) {
    x = l.boardX + col * l.cellSize;
    y = l.boardY + row * l.cellSize;
}

static void drawSquareBase(TFT_eSPI &tft, const CheckersLayout &l, uint8_t row, uint8_t col) {
    int16_t x, y;
    squareOrigin(l, row, col, x, y);
    uint16_t color = ((row + col) % 2 == 1) ? COLOR_DARK_SQUARE : COLOR_LIGHT_SQUARE;
    tft.fillRect(x, y, l.cellSize, l.cellSize, color);
}

// A solid, uniformly-darker copy of an RGB565 color -- halves each of the
// 5/6/5-bit R/G/B channels in place with plain integer shifts, no float/blend
// math, so the drop-shadow below stays a couple of cheap extra fillRoundRect
// calls rather than a new drawing subsystem.
static uint16_t darkenColor565(uint16_t color) {
    uint16_t r = (color >> 11) & 0x1F;
    uint16_t g = (color >> 5) & 0x3F;
    uint16_t b = color & 0x1F;
    return ((r / 2) << 11) | ((g / 2) << 5) | (b / 2);
}

// Draws a piece centered at an arbitrary pixel point rather than a specific
// (row, col) -- the shared implementation both the normal square-anchored
// draw below AND animateCheckersMove()'s in-flight interpolated position use,
// so a moving piece looks pixel-identical to a resting one (the drop-shadow
// added here included -- animateCheckersMove() widens its own per-frame
// erase box by PIECE_SHADOW_OFFSET to match, see that function).
//
// `scale` (default 1.0, every existing call site unaffected) multiplies the
// glyph's own size around the same (cx, cy) center -- animateCheckersPromotion()
// below is the one caller that passes something else, for its brief
// oversized "pop" on the square a piece just kinged.
static void drawPieceGlyphAtCenter(TFT_eSPI &tft, int16_t cx, int16_t cy, int16_t cellSize, CheckersPiece piece, float scale = 1.0f) {
    if (piece == CheckersPiece::EMPTY) return;

    int16_t pad = cellSize / 6;
    int16_t psize = (int16_t)((cellSize - 2 * pad) * scale);
    int16_t px = cx - psize / 2, py = cy - psize / 2;
    int16_t radius = psize / 4;

    bool human = (piece == CheckersPiece::HUMAN_MAN || piece == CheckersPiece::HUMAN_KING);
    uint16_t color = human ? COLOR_HUMAN_PIECE : COLOR_AI_PIECE;

    // Cheap depth cue: a solid darker copy of the same rounded-square glyph,
    // offset a few px down-right, drawn BEFORE the real piece so the piece's
    // own fill/border paint over all of it except the sliver that peeks out
    // past the offset -- reads as the piece sitting slightly proud of the
    // board instead of flat on it.
    tft.fillRoundRect(px + PIECE_SHADOW_OFFSET, py + PIECE_SHADOW_OFFSET, psize, psize, radius, darkenColor565(color));

    tft.fillRoundRect(px, py, psize, psize, radius, color);
    tft.drawRoundRect(px, py, psize, psize, radius, COLOR_PIECE_BORDER);

    bool king = (piece == CheckersPiece::HUMAN_KING || piece == CheckersPiece::AI_KING);
    if (king) {
        tft.setTextColor(COLOR_KING_TEXT, color);
        tft.setTextDatum(MC_DATUM);
        tft.drawString("K", cx, cy);
    }
}

static void drawPieceGlyph(TFT_eSPI &tft, const CheckersLayout &l, uint8_t row, uint8_t col, CheckersPiece piece) {
    int16_t x, y;
    squareOrigin(l, row, col, x, y);
    drawPieceGlyphAtCenter(tft, x + l.cellSize / 2, y + l.cellSize / 2, l.cellSize, piece);
}

void drawCheckersStaticChrome(TFT_eSPI &tft, const CheckersLayout &layout) {
    tft.fillScreen(COLOR_BG);
    tft.fillRect(0, layout.statusY, SCREEN_WIDTH, layout.statusH, COLOR_STATUS_BG);
    for (uint8_t row = 0; row < 8; row++) {
        for (uint8_t col = 0; col < 8; col++) {
            drawSquareBase(tft, layout, row, col);
        }
    }
}

void drawCheckersStatus(TFT_eSPI &tft, const CheckersLayout &layout, const char *text) {
    tft.fillRect(0, layout.statusY, SCREEN_WIDTH, layout.statusH, COLOR_STATUS_BG);

    // The home button is drawn as part of every status-bar repaint (rather
    // than once at round start) because the fillRect above would otherwise
    // erase it on the very next status update -- same reasoning as
    // Display.cpp's drawStatus for Tic-Tac-Toe.
    int16_t by = homeButtonTop(layout);
    tft.drawRoundRect(HOME_BTN_MARGIN, by, HOME_BTN_SIZE, HOME_BTN_SIZE, 4, TFT_WHITE);
    tft.setTextColor(TFT_WHITE, COLOR_STATUS_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString("<", HOME_BTN_MARGIN + HOME_BTN_SIZE / 2, by + HOME_BTN_SIZE / 2);

    int16_t textAreaX0 = HOME_BTN_MARGIN * 2 + HOME_BTN_SIZE;
    tft.setTextColor(COLOR_STATUS_TEXT, COLOR_STATUS_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(text, textAreaX0 + (SCREEN_WIDTH - textAreaX0) / 2, layout.statusY + layout.statusH / 2);
}

void drawCheckersSquare(TFT_eSPI &tft, const CheckersLayout &layout, uint8_t row, uint8_t col, CheckersPiece piece) {
    drawSquareBase(tft, layout, row, col);
    drawPieceGlyph(tft, layout, row, col, piece);
}

// Promotion "crowning" flourish (Premium 2026 Vision pitch, ESP32 Checkers
// section) -- `piece` (already a *_KING; the caller is the one place that
// knows this exact move was the promotion, not merely that the landed piece
// happens to already be a king) gets a brief hold on its landing square,
// then a 2-3 frame oversized redraw before settling back to normal size,
// rather than drawPieceGlyphAtCenter() rendering the "K" identically whether
// it just kinged or has been a king for ten turns. Reuses 100% of existing
// drawing primitives (drawSquareBase + the now-scale-aware
// drawPieceGlyphAtCenter) plus the same delay()-based pacing this file's own
// animateCheckersMove() already relies on -- no new drawing subsystem.
void animateCheckersPromotion(TFT_eSPI &tft, const CheckersLayout &layout, uint8_t row, uint8_t col, CheckersPiece piece) {
    if (piece != CheckersPiece::HUMAN_KING && piece != CheckersPiece::AI_KING) return; // defensive -- callers only pass an actual king

    int16_t x, y;
    squareOrigin(layout, row, col, x, y);
    int16_t cx = x + layout.cellSize / 2, cy = y + layout.cellSize / 2;

    static const uint16_t HOLD_MS = 90;   // a beat on the landing square before the pop, so the moment reads as earned rather than instant
    static const uint16_t POP_MS = 90;    // how long the oversized frame holds before settling
    static const float POP_SCALE = 1.18f; // ~115-120% oversized, per the pitch's own sizing

    delay(HOLD_MS);

    drawSquareBase(tft, layout, row, col);
    drawPieceGlyphAtCenter(tft, cx, cy, layout.cellSize, piece, POP_SCALE);
    delay(POP_MS);

    drawSquareBase(tft, layout, row, col);
    drawPieceGlyphAtCenter(tft, cx, cy, layout.cellSize, piece); // settle back to normal size (scale defaults to 1.0)
}

// One side's captured-piece indicator: a small team-colored swatch (same
// glyph shape drawPieceGlyphAtCenter uses, just fixed-size rather than
// cellSize-derived) plus an "xN" count underneath, centered in a
// `marginW`-wide column starting at `marginX`. Skips drawing entirely if
// that column is too narrow to hold anything legible -- degrades gracefully
// rather than clipping -- so this stays safe if a future layout ever leaves
// less idle space than this project's own fixed 480x320 panel does.
static void drawCapturedSideTray(TFT_eSPI &tft, int16_t marginX, int16_t marginY, int16_t marginW,
                                  uint16_t pieceColor, uint8_t capturedCount) {
    static const int16_t SWATCH_SIZE = 16;
    static const int16_t TRAY_H = 40; // swatch + a line of text beneath it
    if (marginW < SWATCH_SIZE + 6) return;

    int16_t cx = marginX + marginW / 2;
    int16_t swY = marginY;

    // Full erase-then-redraw of this tray's own small footprint, same
    // always-repaint-fresh convention drawCheckersSquare/drawCheckersStatus
    // already use elsewhere in this file, rather than diffing what changed.
    tft.fillRect(marginX, marginY, marginW, TRAY_H, COLOR_BG);
    tft.fillRoundRect(cx - SWATCH_SIZE / 2, swY, SWATCH_SIZE, SWATCH_SIZE, 3, pieceColor);
    tft.drawRoundRect(cx - SWATCH_SIZE / 2, swY, SWATCH_SIZE, SWATCH_SIZE, 3, COLOR_PIECE_BORDER);

    char buf[8];
    snprintf(buf, sizeof(buf), "x%u", (unsigned)capturedCount);
    tft.setTextColor(COLOR_STATUS_TEXT, COLOR_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString(buf, cx, swY + SWATCH_SIZE + 10);
}

// Quick-win captured-piece tray, one per side. computeCheckersLayout() sizes
// the board off whichever of SCREEN_WIDTH/(SCREEN_HEIGHT-statusH) is
// smaller (see that function) -- on this project's actual fixed 480x320
// panel that's always the height, which the board already fills almost
// exactly, so the idle space it leaves is the two side margins flanking the
// board (left: [0, layout.boardX), right: [boardX+boardSize, SCREEN_WIDTH)),
// not a gap above/below it. CheckersBoard::pieceCount() already exists
// (CheckersLogic.h) -- no rules/logic-side change needed to derive a
// captured count from it.
static void drawCheckersCapturedTray(TFT_eSPI &tft, const CheckersLayout &layout, const CheckersBoard &board) {
    int16_t boardSize = layout.cellSize * 8;
    int16_t rightMarginX = layout.boardX + boardSize;
    int16_t rightMarginW = SCREEN_WIDTH - rightMarginX;

    uint8_t humanCaptured = CHECKERS_STARTING_PIECES - board.pieceCount(true);
    uint8_t aiCaptured = CHECKERS_STARTING_PIECES - board.pieceCount(false);

    drawCapturedSideTray(tft, 0, layout.boardY, layout.boardX, COLOR_HUMAN_PIECE, humanCaptured);
    drawCapturedSideTray(tft, rightMarginX, layout.boardY, rightMarginW, COLOR_AI_PIECE, aiCaptured);
}

// Redraws the whole 8x8 board fresh, then the captured-piece tray beside it
// (see drawCheckersCapturedTray) -- folded in here, rather than a separate
// call the .ino would need to remember to make, so every existing
// drawCheckersBoard() call site (round start, after every hop, the
// highlight-clearing redraw, animateCheckersMove()'s pre-slide redraw, ...)
// picks the tray up for free and it always reflects the SAME `board` the
// squares just did.
void drawCheckersBoard(TFT_eSPI &tft, const CheckersLayout &layout, const CheckersBoard &board) {
    for (uint8_t row = 0; row < 8; row++) {
        for (uint8_t col = 0; col < 8; col++) {
            drawCheckersSquare(tft, layout, row, col, board.at(row, col));
        }
    }
    drawCheckersCapturedTray(tft, layout, board);
}

void drawCheckersSquareHighlight(TFT_eSPI &tft, const CheckersLayout &layout, uint8_t row, uint8_t col,
                                  CheckersPiece piece, bool highlighted) {
    drawCheckersSquare(tft, layout, row, col, piece);
    if (!highlighted) return;

    int16_t x, y;
    squareOrigin(layout, row, col, x, y);
    // A 2px-thick border drawn with the same fast-line primitives Display.cpp
    // uses for its grid -- no drawRect exists in this project's TFT_eSPI
    // surface, so a border is 4 lines rather than 1 call.
    for (int16_t t = 0; t < 2; t++) {
        tft.drawFastHLine(x, y + t, layout.cellSize, COLOR_HIGHLIGHT);
        tft.drawFastHLine(x, y + layout.cellSize - 1 - t, layout.cellSize, COLOR_HIGHLIGHT);
        tft.drawFastVLine(x + t, y, layout.cellSize, COLOR_HIGHLIGHT);
        tft.drawFastVLine(x + layout.cellSize - 1 - t, y, layout.cellSize, COLOR_HIGHLIGHT);
    }
}

void drawCheckersPlayAgainButton(TFT_eSPI &tft, const CheckersLayout &layout) {
    tft.fillRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, COLOR_BUTTON_BG);
    tft.drawRoundRect(layout.buttonX, layout.buttonY, layout.buttonW, layout.buttonH, 8, TFT_WHITE);
    tft.setTextColor(COLOR_BUTTON_TEXT, COLOR_BUTTON_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);
    tft.drawString("Play Again", layout.buttonX + layout.buttonW / 2, layout.buttonY + layout.buttonH / 2);
}

void hideCheckersPlayAgainButton(TFT_eSPI &tft, const CheckersLayout &layout) {
    tft.fillRect(layout.buttonX - 2, layout.buttonY - 2, layout.buttonW + 4, layout.buttonH + 4, COLOR_BG);
}

bool hitTestSquare(const CheckersLayout &layout, int16_t touchX, int16_t touchY, uint8_t &outRow, uint8_t &outCol) {
    int16_t boardSize = layout.cellSize * 8;
    if (touchX < layout.boardX || touchX >= layout.boardX + boardSize) return false;
    if (touchY < layout.boardY || touchY >= layout.boardY + boardSize) return false;
    outCol = (touchX - layout.boardX) / layout.cellSize;
    outRow = (touchY - layout.boardY) / layout.cellSize;
    return true;
}

bool hitTestCheckersPlayAgainButton(const CheckersLayout &layout, int16_t touchX, int16_t touchY) {
    return touchX >= layout.buttonX && touchX < layout.buttonX + layout.buttonW &&
           touchY >= layout.buttonY && touchY < layout.buttonY + layout.buttonH;
}

bool hitTestCheckersHomeButton(const CheckersLayout &layout, int16_t touchX, int16_t touchY) {
    int16_t by = homeButtonTop(layout);
    return touchX >= 0 && touchX < HOME_BTN_MARGIN + HOME_BTN_SIZE &&
           touchY >= by && touchY < by + HOME_BTN_SIZE;
}

bool checkersJumpMidpoint(uint8_t fromRow, uint8_t fromCol, uint8_t toRow, uint8_t toCol,
                           uint8_t &outMidRow, uint8_t &outMidCol) {
    int rowDelta = (int)toRow - (int)fromRow;
    if (rowDelta != 2 && rowDelta != -2) return false;
    outMidRow = (fromRow + toRow) / 2;
    outMidCol = (fromCol + toCol) / 2;
    return true;
}

void animateCheckersMove(TFT_eSPI &tft, const CheckersLayout &layout, const CheckersBoard &board,
                          const uint8_t waypointRows[], const uint8_t waypointCols[],
                          const CheckersPiece capturedPieces[], uint8_t hopCount, CheckersPiece piece) {
    if (piece == CheckersPiece::EMPTY || hopCount == 0) return; // defensive -- a real caller always passes the actual piece that moved, and at least one hop

    // One full-board redraw HERE, before any per-frame loop -- a one-time
    // cost, not a per-frame one -- clears any leftover UI state from before
    // the slide starts, most importantly the legal-destination highlights
    // (see highlightCheckersSelection() in the .ino) on squares OTHER than
    // the one just tapped, which this function otherwise never touches and
    // would otherwise persist, visible, for the whole animation. Every
    // waypoint the piece will visit -- its start, every intermediate landing
    // square of a multi-jump chain, and its final destination -- is then
    // drawn empty up front: the sliding glyph, drawn fresh every frame
    // below, is the only thing that ever represents the piece itself for
    // the rest of this function.
    drawCheckersBoard(tft, layout, board);
    for (uint8_t i = 0; i <= hopCount; i++) {
        drawCheckersSquare(tft, layout, waypointRows[i], waypointCols[i], CheckersPiece::EMPTY);
    }

    // The piece glyph's own bounding box (see drawPieceGlyphAtCenter) --
    // erasing exactly this box at its PREVIOUS frame position, rather than
    // an entire square, is what actually fixes ghosting: a square-shaped
    // box sliding along a 45-degree diagonal spills a few pixels into the
    // two OFF-PATH squares that share the same corner (neither the source,
    // intermediate, nor destination square) at points along the slide --
    // squares this function never otherwise redraws, so any pixels left
    // there from an earlier frame never got cleared (confirmed as the exact
    // cause of the "afterimage" artifacts seen on real hardware). Erasing by
    // exact previous pixel position sidesteps square boundaries entirely.
    // Deliberately declared OUTSIDE the per-hop loop below: a chain's hops
    // connect end-to-end (hop i's landing square IS hop i+1's start), so the
    // piece's on-screen position is continuous across a hop boundary and the
    // erase-by-previous-position bookkeeping should carry straight through
    // rather than resetting.
    int16_t pad = layout.cellSize / 6;
    int16_t psize = layout.cellSize - 2 * pad;
    bool havePrev = false;
    int16_t prevPx = 0, prevPy = 0;

    static const uint16_t DURATION_MS = 350;    // per hop -- the original single-hop slide's own duration, now applied per timeline segment
    static const uint16_t TARGET_FRAME_MS = 20; // ~50fps target
    uint16_t stepsPerHop = DURATION_MS / TARGET_FRAME_MS;

    for (uint8_t hop = 0; hop < hopCount; hop++) {
        uint8_t fromRow = waypointRows[hop], fromCol = waypointCols[hop];
        uint8_t toRow = waypointRows[hop + 1], toCol = waypointCols[hop + 1];

        int16_t fx, fy, tx, ty;
        squareOrigin(layout, fromRow, fromCol, fx, fy);
        squareOrigin(layout, toRow, toCol, tx, ty);
        int16_t cx0 = fx + layout.cellSize / 2, cy0 = fy + layout.cellSize / 2;
        int16_t cx1 = tx + layout.cellSize / 2, cy1 = ty + layout.cellSize / 2;

        // A jump (2-square diagonal hop) has an intermediate square holding
        // the piece THIS hop captures -- it must stay visible, faithfully
        // redrawn from capturedPieces[hop] (never from `board.at()` -- see
        // this function's header comment for why the real board can't be
        // trusted here), until this hop's own slide actually reaches it. A
        // simple 1-square move has no such square.
        uint8_t midRow = 0, midCol = 0;
        bool isJump = checkersJumpMidpoint(fromRow, fromCol, toRow, toCol, midRow, midCol);
        CheckersPiece capturedPiece = isJump ? capturedPieces[hop] : CheckersPiece::EMPTY;
        if (isJump) drawCheckersSquare(tft, layout, midRow, midCol, capturedPiece);

        for (uint16_t i = 1; i <= stepsPerHop; i++) {
            unsigned long frameStart = millis();

            float t = (float)i / (float)stepsPerHop;
            float eased = 1.0f - (1.0f - t) * (1.0f - t); // ease-out: quick start, gentle settle -- reads more natural than constant-speed motion for a short slide
            int16_t cx = cx0 + (int16_t)((cx1 - cx0) * eased);
            int16_t cy = cy0 + (int16_t)((cy1 - cy0) * eased);
            int16_t px = cx - psize / 2, py = cy - psize / 2;

            // Checkers pieces only ever occupy dark squares, and a diagonal
            // slide stays on dark squares the entire way (see CheckersLogic.h),
            // so COLOR_DARK_SQUARE is always the correct background to erase to
            // here, everywhere along the path -- no per-frame square-color
            // lookup needed. Widened by PIECE_SHADOW_OFFSET on both dimensions
            // (not shifted -- the shadow only ever peeks out past the piece's
            // own bottom-right, per drawPieceGlyphAtCenter) so the previous
            // frame's drop-shadow is fully erased too, not just the piece glyph
            // itself -- otherwise it would trail behind the slide as ghosting,
            // the exact artifact this erase-by-exact-bounding-box approach was
            // built to avoid (see this function's own comment above).
            if (havePrev) {
                tft.fillRect(prevPx, prevPy, psize + PIECE_SHADOW_OFFSET, psize + PIECE_SHADOW_OFFSET, COLOR_DARK_SQUARE);
            }

            // On a capturing jump, the moving piece slides directly OVER the
            // captured piece sitting on the intermediate square -- the erase
            // above can clip into its glyph if the moving piece's previous-frame
            // bounding box happened to overlap it, leaving a visible "hole"
            // (confirmed on real hardware). Cheaply re-assert that one square
            // correct every frame, before drawing the moving piece on top of it
            // again -- which is the visually correct outcome anyway while the
            // piece is actually passing over it.
            if (isJump) drawCheckersSquare(tft, layout, midRow, midCol, capturedPiece);

            drawPieceGlyphAtCenter(tft, cx, cy, layout.cellSize, piece);
            prevPx = px; prevPy = py;
            havePrev = true;

            // Pad out to TARGET_FRAME_MS if the actual SPI drawing above finished
            // faster -- without this, fast enough drawing makes the whole
            // animation complete in a handful of milliseconds, effectively
            // invisible (confirmed on real hardware with an earlier version of
            // this function, before this rewrite).
            unsigned long elapsed = millis() - frameStart;
            if (elapsed < TARGET_FRAME_MS) delay(TARGET_FRAME_MS - elapsed);
        }

        // Capture hit-stop (Premium 2026 Vision pitch, ESP32 Checkers
        // section): a genuine ~90ms pause right as the slide reaches the
        // captured square, immediately before it visibly disappears below --
        // the ESP32-honest analog of hit-stop on this hardware (a plain
        // delay(), not a shader/camera-shake, which would need a whole-board
        // redraw every frame this blocking, non-DMA SPI path can't afford
        // for free -- see the pitch's own audit).
        static const uint16_t CAPTURE_HITSTOP_MS = 90;
        if (isJump) delay(CAPTURE_HITSTOP_MS);

        // This hop's own slide just finished -- if it captured a piece, that
        // piece is gone for good starting now. Erase it explicitly rather
        // than relying on a caller's own later full-board redraw: that only
        // happens once, after the WHOLE chain finishes, which would leave
        // this hop's capture visibly lingering all the way through every
        // LATER hop's slide on a real multi-jump chain -- exactly the bug
        // this generalized version fixes.
        if (isJump) drawCheckersSquare(tft, layout, midRow, midCol, CheckersPiece::EMPTY);
    }
}
