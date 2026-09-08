// GameSuite Arcade for the Hosyond 4.0" ESP32 Display Module (ST7796S,
// resistive touch) -- an "OS" shell (boot -> home menu -> pick a game) around
// what started as a single Tic-Tac-Toe sketch. The file/folder are still
// named TicTacToeESP32 because Arduino requires a sketch's main .ino to share
// its folder's name and native_test/ already depends on that path -- renaming
// either would break more than it's worth for what's still, under the hood,
// one small firmware image.
//
// This is a from-scratch firmware project, NOT a port of GameSuite's Android app --
// an ESP32 has no Android, no JVM, and a few hundred KB of RAM versus a phone's
// gigabytes, so none of that code can run here. What carries over is the game
// design: the same minimax AI (see GameLogic.cpp), the same engine/screen split
// GameSuite's own architecture uses everywhere, the same "human vs. an unbeatable
// bot" feel. See README.md for setup, wiring notes, and troubleshooting.
//
// Requires (Arduino IDE -> Tools -> Manage Libraries):
//   - "TFT_eSPI" by Bodmer
// ...with UserSetup/User_Setup.h from this project copied into that library's
// folder BEFORE compiling -- see README.md, step 3. Board: "ESP32 Dev Module"
// under the espressif esp32 board package.

#include <TFT_eSPI.h>
#include <Preferences.h>
#include <esp_sleep.h>
#include <esp_random.h>
#include "Config.h"
#include "GameLogic.h"
#include "Display.h"
#include "Chrome.h"
#include "MenuScreen.h"
#include "Theme.h"
#include "CheckersLogic.h"
#include "CheckersDisplay.h"
#include "ChessLogic.h"
#include "ChessDisplay.h"
#include "UnoLogic.h"
#include "UnoDisplay.h"

TFT_eSPI tft = TFT_eSPI();
Preferences prefs;

TicTacToeBoard board;
Layout layout;

CheckersBoard checkersBoard;
CheckersLayout checkersLayout;
uint8_t checkersSelRow = 0, checkersSelCol = 0;
bool checkersHaveSelection = false;
bool checkersRoundOver = false;
unsigned long checkersAiMoveDueAt = 0;
bool checkersAiMovePending = false;

ChessBoard chessBoard;
ChessLayout chessLayout;
int8_t chessSelectedSquare = -1;
bool chessRoundOver = false;
unsigned long chessAiMoveDueAt = 0;
bool chessAiMovePending = false;

UnoRound unoRound;
UnoLayout unoLayout;
UnoColorOverlayLayout unoColorOverlayLayout;
bool unoRoundOver = false;
unsigned long unoAiMoveDueAt = 0;
bool unoAiMovePending = false;

// ---- Arcade home menu ----
// Adding a game means: write its GameLogic/Display pair, add one entry here,
// flip its `enabled` flag once it's built -- no other wiring needed. Chess is
// listed disabled ("coming soon") because it's still being built; Mancala and
// Dominoes are listed disabled because the README's own roadmap already
// named them as the next-best fits for this hardware -- a player sees the
// real roadmap on the device itself instead of it being invisible until built.
struct MenuGame {
    const char *label;
    bool enabled;
};
static const MenuGame MENU_GAMES[] = {
    {"Tic-Tac-Toe", true},
    {"Checkers", true},
    {"Chess", true},
    {"UNO", true},
    {"Mancala", false},
    {"Dominoes", false},
};
static const uint8_t MENU_GAME_COUNT = sizeof(MENU_GAMES) / sizeof(MENU_GAMES[0]);
static const uint8_t TICTACTOE_GAME_INDEX = 0;
static const uint8_t CHECKERS_GAME_INDEX = 1;
static const uint8_t CHESS_GAME_INDEX = 2;
static const uint8_t UNO_GAME_INDEX = 3;

enum class AppState { MENU, TICTACTOE, CHECKERS, CHESS, UNO };
AppState appState = AppState::MENU;
MenuLayout menuLayout;
// Index of the first game currently shown in the (possibly scrolled) menu --
// see MenuScreen.h's "slot vs. absolute index" comment. Reset to 0 whenever
// the menu is (re)entered, so re-opening it is always predictable rather
// than remembering wherever it was left scrolled to.
uint8_t menuScrollOffset = 0;

// Drives the short pause between the human's move and the AI's reply, and
// between a finished round and the board accepting the next tap -- purely
// cosmetic (an instant AI reply reads as the game not having registered your
// tap at all), matching why GameSuite's Android bots use a similar delay.
static const unsigned long AI_MOVE_DELAY_MS = 450;
unsigned long aiMoveDueAt = 0;
bool aiMovePending = false;

bool roundOver = false;

void loadOrRunCalibration();
void startNewRound();
void refreshStatusText();
void handleTouch();
void checkForRoundEnd();
void enterLightSleep();
void startNewCheckersRound();
void refreshCheckersStatusText();
void checkForCheckersRoundEnd();
void startNewChessRound();
void refreshChessStatusText();
void checkForChessRoundEnd();
void startNewUnoRound();
void afterUnoStateChange();
void checkForUnoRoundEnd();
void redrawMenu();

void enterMenu() {
    appState = AppState::MENU;
    menuScrollOffset = 0;
    menuLayout = computeMenuLayout(MENU_GAME_COUNT);
    redrawMenu();
    Serial.println("[ArcadeOS] at home menu");
}

// Redraws the chrome (title, Sleep button, scroll arrows sized to the
// current scroll position) plus whichever games/count fit given
// menuScrollOffset -- called on every entry to the menu AND every scroll tap,
// since which slot maps to which game shifts as the offset changes.
void redrawMenu() {
    bool canScrollUp = menuScrollOffset > 0;
    bool canScrollDown = (menuScrollOffset + menuLayout.maxVisibleTiles) < MENU_GAME_COUNT;
    drawMenuChrome(tft, menuLayout, canScrollUp, canScrollDown);

    uint8_t remaining = MENU_GAME_COUNT - menuScrollOffset;
    uint8_t visibleCount = (remaining < menuLayout.maxVisibleTiles) ? remaining : menuLayout.maxVisibleTiles;
    for (uint8_t slot = 0; slot < visibleCount; slot++) {
        uint8_t idx = menuScrollOffset + slot;
        drawMenuTile(tft, menuLayout, slot, MENU_GAMES[idx].label, MENU_GAMES[idx].enabled);
    }
}

// Puts the ESP32 into light sleep (NOT deep sleep) with the touch
// controller's own IRQ line as the wake source -- this board has only BOOT
// and RESET buttons, neither meant for this, so a touch anywhere on the
// screen is the only "intuitive, no physical button" way to wake it back up.
// Light sleep (unlike deep sleep) keeps RAM powered and simply pauses/resumes
// right where execution left off -- no reboot, no re-running setup(), no
// lost arcade state -- and the display panel itself isn't reset either (only
// its backlight is switched off here), so the menu is still sitting in the
// panel's own memory and doesn't need redrawing on wake, though enterMenu()
// is still called afterward as a cheap defensive redraw.
//
// Not testable by native_test: esp_light_sleep_start()/EXT0 wakeup are
// ESP32-specific hardware calls with nothing to stub on a desktop compiler --
// same category as real touch calibration or real SPI timing, only the
// physical board can confirm this actually works.
void enterLightSleep() {
    Serial.println("[ArcadeOS] entering light sleep -- touch the screen to wake");
    Serial.flush(); // make sure the message actually gets out over USB-serial before sleeping

    // ext0 wakeup is LEVEL-triggered, not edge-triggered: it wakes for as
    // long as the pin reads the target level, not just on the transition to
    // it. The finger that just tapped "Sleep" is still physically down at
    // this point -- without waiting here first, esp_light_sleep_start()
    // below would see IRQ already LOW and return almost immediately,
    // making sleep look like it never actually happened (a rapid
    // sleep/wake flicker instead of the screen actually going dark).
    uint16_t tx, ty;
    while (tft.getTouch(&tx, &ty)) delay(10);

    digitalWrite(TFT_BL, LOW); // backlight off (active-HIGH, confirmed -- see UserSetup/User_Setup.h)

    // TOUCH_IRQ (defined in User_Setup.h, visible here via <TFT_eSPI.h>) is
    // the XPT2046's PENIRQ line -- it idles HIGH and is pulled LOW by the
    // touch controller itself whenever the panel is physically pressed,
    // independent of any SPI polling, which is exactly what makes it usable
    // as a wake source while the CPU is asleep and not polling anything.
    pinMode(TOUCH_IRQ, INPUT);
    esp_sleep_enable_ext0_wakeup((gpio_num_t)TOUCH_IRQ, 0); // wake when it goes LOW
    esp_light_sleep_start(); // blocks here until the wake source fires

    // Execution resumes right here once woken -- everything above (globals,
    // the call stack) is exactly as it was before sleeping.
    digitalWrite(TFT_BL, HIGH);
    Serial.println("[ArcadeOS] woke from light sleep");

    // The same physical touch that just woke the device is still down at
    // this point -- wait for it to actually release before treating any
    // touch as a real tap, otherwise this one touch could immediately also
    // register as a menu-tile tap underneath it the instant loop() resumes.
    while (tft.getTouch(&tx, &ty)) delay(10);

    enterMenu(); // cheap defensive redraw; sleep can only ever be entered from the menu
}

void enterTicTacToe() {
    appState = AppState::TICTACTOE;
    layout = computeLayout();
    startNewRound();
    Serial.println("[ArcadeOS] entered Tic-Tac-Toe");
}

void enterCheckers() {
    appState = AppState::CHECKERS;
    checkersLayout = computeCheckersLayout();
    startNewCheckersRound();
    Serial.println("[ArcadeOS] entered Checkers");
}

void enterChess() {
    appState = AppState::CHESS;
    chessLayout = computeChessLayout();
    startNewChessRound();
    Serial.println("[ArcadeOS] entered Chess");
}

void enterUno() {
    appState = AppState::UNO;
    unoLayout = computeUnoLayout();
    unoColorOverlayLayout = computeUnoColorOverlayLayout();
    startNewUnoRound();
    Serial.println("[ArcadeOS] entered UNO");
}

void setup() {
    Serial.begin(115200);
    delay(300); // let the USB-serial link settle before the first print
    Serial.println();
    Serial.println("[ArcadeOS] booting");

    tft.init();
    Serial.println("[ArcadeOS] tft.init() done");
    tft.setRotation(SCREEN_ROTATION);
    Serial.printf("[ArcadeOS] rotation set to %d (expect %dx%d landscape)\n",
                  SCREEN_ROTATION, SCREEN_WIDTH, SCREEN_HEIGHT);

    // The small smooth font is this arcade's default, left loaded for the
    // rest of the program's life -- see Theme.h. Loaded before calibration
    // so even the first-boot calibration screen gets it, not just the menu
    // and games. Only the menu's title switches to the large font, and
    // switches straight back afterward.
    themeLoadFontSmall(tft);

    loadOrRunCalibration();
    Serial.println("[ArcadeOS] calibration ready");

    // esp_random() is the ESP32's real hardware RNG (uses the RF subsystem's
    // thermal noise, not a software PRNG seeded from a guessable value like
    // millis()) -- UnoLogic.h keeps its own tiny xorshift32 out of Arduino's
    // random()/randomSeed() so it compiles unchanged against native_test's
    // stub, but a real board should still seed it with real entropy once,
    // here, before the first shuffle.
    seedUnoRandom(esp_random());

    enterMenu();
    Serial.println("[ArcadeOS] setup complete, entering loop()");
}

void loop() {
    handleTouch();

    // Each *MovePending flag only ever becomes true from within handleTouch()'s
    // own matching branch, so gating on appState here is belt-and-suspenders,
    // not load-bearing -- kept explicit so no stale AI move can fire into the
    // wrong screen if that invariant ever changes.
    if (appState == AppState::TICTACTOE && aiMovePending && millis() >= aiMoveDueAt) {
        aiMovePending = false;
        board.playAi();
        for (uint8_t i = 0; i < 9; i++) drawCell(tft, layout, i, board.at(i));
        checkForRoundEnd();
    }

    if (appState == AppState::CHECKERS && checkersAiMovePending && millis() >= checkersAiMoveDueAt) {
        checkersAiMovePending = false;
        checkersBoard.playAi(); // plays the AI's whole turn, every hop of a forced chain included

        // Walk the whole chain's own per-hop trace (see CheckersLogic.h) into
        // plain arrays animateCheckersMove() can take -- this is what lets a
        // real multi-jump chain animate each hop as its own timeline segment
        // instead of one straight slide with every capture already invisible.
        uint8_t hopCount = checkersBoard.lastAiHopCount();
        uint8_t waypointRows[CHECKERS_MAX_CHAIN_HOPS + 1], waypointCols[CHECKERS_MAX_CHAIN_HOPS + 1];
        CheckersPiece capturedPieces[CHECKERS_MAX_CHAIN_HOPS];
        for (uint8_t i = 0; i <= hopCount; i++) {
            waypointRows[i] = checkersBoard.lastAiWaypointRow(i);
            waypointCols[i] = checkersBoard.lastAiWaypointCol(i);
        }
        for (uint8_t i = 0; i < hopCount; i++) {
            capturedPieces[i] = checkersBoard.lastAiHopCapturedPiece(i);
        }
        animateCheckersMove(tft, checkersLayout, checkersBoard, waypointRows, waypointCols, capturedPieces, hopCount,
                             checkersBoard.at(waypointRows[hopCount], waypointCols[hopCount])); // board is already post-move -- see animateCheckersMove's header comment
        drawCheckersBoard(tft, checkersLayout, checkersBoard);
        checkForCheckersRoundEnd();
    }

    if (appState == AppState::CHESS && chessAiMovePending && millis() >= chessAiMoveDueAt) {
        chessAiMovePending = false;
        ChessBoard chessBoardBeforeMove = chessBoard; // snapshot BEFORE playAi() below mutates -- see animateChessMove's header comment
        chessBoard.playAi();
        animateChessMove(tft, chessLayout, chessBoard, chessBoardBeforeMove);
        drawChessBoard(tft, chessLayout, chessBoard, chessSelectedSquare); // chessSelectedSquare is already -1 (cleared before the AI was scheduled)
        checkForChessRoundEnd();
    }

    if (appState == AppState::UNO && unoAiMovePending && millis() >= unoAiMoveDueAt) {
        unoAiMovePending = false;
        unoRound.playAiTurn(); // plays the AI's whole turn, including its own color choice if any
        afterUnoStateChange();
    }

    // A slow heartbeat so a remote/serial-only observer can tell the sketch is
    // still alive in loop() (vs. having crashed/reset) without flooding the
    // log the way printing every single loop() iteration would.
    static unsigned long lastHeartbeat = 0;
    if (millis() - lastHeartbeat > 3000) {
        lastHeartbeat = millis();
        const char *stateName = "menu";
        switch (appState) {
            case AppState::TICTACTOE: stateName = "tictactoe"; break;
            case AppState::CHECKERS:  stateName = "checkers"; break;
            case AppState::CHESS:     stateName = "chess"; break;
            case AppState::UNO:       stateName = "uno"; break;
            default: break;
        }
        Serial.printf("[ArcadeOS] alive, uptime=%lus, state=%s\n", millis() / 1000, stateName);
    }
}

void checkForRoundEnd() {
    RoundResult r = board.result();
    if (r == RoundResult::IN_PROGRESS) {
        refreshStatusText();
        return;
    }
    roundOver = true;
    int8_t line[3];
    board.winningLine(line);
    drawWinningLine(tft, layout, line);

    switch (r) {
        case RoundResult::HUMAN_WINS: drawChromeBar(tft, "You win!"); break;
        case RoundResult::AI_WINS:    drawChromeBar(tft, "ESP32 wins!"); break;
        case RoundResult::DRAW:       drawChromeBar(tft, "Draw!"); break;
        default: break;
    }
    drawPlayAgainButton(tft, layout);
}

void startNewRound() {
    board.reset();
    roundOver = false;
    aiMovePending = false;
    drawStaticChrome(tft, layout);
    for (uint8_t i = 0; i < 9; i++) drawCell(tft, layout, i, EMPTY);
    refreshStatusText();
}

void refreshStatusText() {
    drawChromeBar(tft, board.isHumanTurn() ? "Your turn (X)" : "ESP32 thinking...");
}

void checkForCheckersRoundEnd() {
    CheckersResult r = checkersBoard.result();
    if (r == CheckersResult::IN_PROGRESS) {
        refreshCheckersStatusText();
        return;
    }
    checkersRoundOver = true;
    switch (r) {
        case CheckersResult::HUMAN_WINS: drawChromeBar(tft, "You win!"); break;
        case CheckersResult::AI_WINS:    drawChromeBar(tft, "ESP32 wins!"); break;
        default: break;
    }
    drawCheckersPlayAgainButton(tft, checkersLayout);
}

void startNewCheckersRound() {
    checkersBoard.reset();
    checkersRoundOver = false;
    checkersAiMovePending = false;
    checkersHaveSelection = false;
    drawCheckersStaticChrome(tft, checkersLayout);
    drawCheckersBoard(tft, checkersLayout, checkersBoard);
    refreshCheckersStatusText();
}

void refreshCheckersStatusText() {
    drawChromeBar(tft, checkersBoard.isHumanTurn() ? "Your turn" : "ESP32 thinking...");
}

void checkForChessRoundEnd() {
    ChessRoundResult r = chessBoard.result();
    if (r == ChessRoundResult::IN_PROGRESS) {
        refreshChessStatusText();
        return;
    }
    chessRoundOver = true;
    switch (r) {
        case ChessRoundResult::HUMAN_WINS:     drawChromeBar(tft, "You win!"); break;
        case ChessRoundResult::AI_WINS:        drawChromeBar(tft, "ESP32 wins!"); break;
        case ChessRoundResult::DRAW_STALEMATE: drawChromeBar(tft, "Stalemate!"); break;
        default: break;
    }
    drawChessPlayAgainButton(tft, chessLayout);
}

void startNewChessRound() {
    chessBoard.reset();
    chessRoundOver = false;
    chessAiMovePending = false;
    chessSelectedSquare = -1;
    drawChessStaticChrome(tft, chessLayout);
    drawChessBoard(tft, chessLayout, chessBoard, chessSelectedSquare);
    refreshChessStatusText();
}

void refreshChessStatusText() {
    if (!chessBoard.isHumanTurn()) {
        drawChromeBar(tft, "ESP32 thinking...");
    } else {
        drawChromeBar(tft, chessBoard.inCheck() ? "Your turn -- CHECK!" : "Your turn");
    }
}

void checkForUnoRoundEnd() {
    UnoRoundResult r = unoRound.result();
    if (r == UnoRoundResult::IN_PROGRESS) return; // afterUnoStateChange() already refreshed the status text
    unoRoundOver = true;
    switch (r) {
        case UnoRoundResult::HUMAN_WINS: drawChromeBar(tft, "You win!"); break;
        case UnoRoundResult::AI_WINS:    drawChromeBar(tft, "ESP32 wins!"); break;
        default: break;
    }
    drawUnoPlayAgainButton(tft, unoLayout);
}

void startNewUnoRound() {
    unoRound.reset();
    unoRoundOver = false;
    unoAiMovePending = false;
    drawUnoStaticChrome(tft, unoLayout);
    afterUnoStateChange();
}

// The single place every UNO action (a human play/draw/color-choice, or the
// AI's whole turn) funnels through afterward: redraw everything that could
// have changed, check for a round end, show the color-choice overlay if the
// human's own play just raised one, and schedule the AI's move if it's now
// their turn -- matching this project's "one funnel, not one copy of this
// logic per call site" approach elsewhere (e.g. checkForRoundEnd()).
void afterUnoStateChange() {
    drawUnoAiHand(tft, unoLayout, unoRound.aiHandCount());
    drawUnoDiscardPile(tft, unoLayout, unoRound.topDiscard(), unoRound.currentColor());
    drawUnoDrawPile(tft, unoLayout, unoRound.drawPileCount());

    // UNO_MAX_HAND (108) sized so this never overflows even in a pathological
    // "everything ended up in one hand" state -- see UnoLogic.h.
    static UnoCardView handBuf[UNO_MAX_HAND];
    uint8_t n = unoRound.humanHandCount();
    for (uint8_t i = 0; i < n; i++) handBuf[i] = unoRound.humanHandCard(i);
    drawUnoHumanHand(tft, unoLayout, handBuf, n);

    drawChromeBar(tft, unoRound.isHumanTurn() ? "Your turn" : "ESP32 thinking...");

    if (unoRound.result() != UnoRoundResult::IN_PROGRESS) {
        checkForUnoRoundEnd();
        return;
    }
    if (unoRound.awaitingColorChoice()) {
        drawUnoColorOverlay(tft, unoColorOverlayLayout);
        return;
    }
    if (!unoRound.isHumanTurn() && !unoAiMovePending) {
        unoAiMovePending = true;
        unoAiMoveDueAt = millis() + AI_MOVE_DELAY_MS;
    }
}

// Highlights `row,col` as the current checkers selection, plus every square
// it can actually move/jump to -- so the player sees not just "this piece is
// selected" but "here's exactly where it's allowed to go", which the game
// didn't do before (CheckersDisplay.h's legalDestinationsFrom() existed for
// exactly this from the start, just was never actually wired up). Used both
// when a piece is first selected and at each hop of a forced multi-jump.
void highlightCheckersSelection(uint8_t row, uint8_t col) {
    drawCheckersSquareHighlight(tft, checkersLayout, row, col, checkersBoard.at(row, col), true);

    uint8_t destRows[CHECKERS_MAX_MOVES], destCols[CHECKERS_MAX_MOVES];
    uint8_t destCount = checkersBoard.legalDestinationsFrom(row, col, destRows, destCols);
    for (uint8_t i = 0; i < destCount; i++) {
        drawCheckersSquareHighlight(tft, checkersLayout, destRows[i], destCols[i],
                                     checkersBoard.at(destRows[i], destCols[i]), true);
    }
}

void handleTouch() {
    // TFT_eSPI's own getTouch() reads the XPT2046 over the same/adjacent SPI bus
    // (see UserSetup/User_Setup.h) and applies the calibration data already
    // loaded via setTouch() in loadOrRunCalibration() -- no separate touch
    // library needed. Returns screen-space pixel coordinates directly.
    uint16_t tx, ty;
    if (!tft.getTouch(&tx, &ty)) return;
    Serial.printf("[ArcadeOS] touch at (%u,%u)\n", tx, ty);

    // A touch is a single edge-triggered event as far as this game cares (no
    // drag gestures) -- debounce by requiring the panel to report "not
    // touched" again before the next tap counts, so holding a finger down
    // doesn't re-fire the same tap dozens of times per second.
    static bool wasTouched = false;
    if (wasTouched) { wasTouched = tft.getTouch(&tx, &ty); return; }
    wasTouched = true;

    if (appState == AppState::MENU) {
        if (hitTestSleepButton(menuLayout, tx, ty)) {
            enterLightSleep();
            return;
        }
        if (hitTestScrollUp(menuLayout, tx, ty)) {
            if (menuScrollOffset > 0) { menuScrollOffset--; redrawMenu(); }
            return;
        }
        if (hitTestScrollDown(menuLayout, tx, ty)) {
            if (menuScrollOffset + menuLayout.maxVisibleTiles < MENU_GAME_COUNT) { menuScrollOffset++; redrawMenu(); }
            return;
        }

        uint8_t remaining = MENU_GAME_COUNT - menuScrollOffset;
        uint8_t visibleCount = (remaining < menuLayout.maxVisibleTiles) ? remaining : menuLayout.maxVisibleTiles;
        uint8_t slot;
        if (!hitTestMenuTile(menuLayout, visibleCount, tx, ty, slot)) return;
        uint8_t idx = menuScrollOffset + slot; // slot is the tile's position on screen; idx is its real place in MENU_GAMES
        if (!MENU_GAMES[idx].enabled) return; // "coming soon" tile -- silently inert, same as any other illegal tap in this project
        if (idx == TICTACTOE_GAME_INDEX) enterTicTacToe();
        else if (idx == CHECKERS_GAME_INDEX) enterCheckers();
        else if (idx == CHESS_GAME_INDEX) enterChess();
        else if (idx == UNO_GAME_INDEX) enterUno();
        return;
    }

    // From here on, appState is some game -- the shared Home button (part of
    // every game's chrome bar, see Chrome.h) is checked once, up front, so
    // no per-game branch below needs its own copy of this check.
    if (hitTestChromeHome(tx, ty)) {
        enterMenu();
        return;
    }

    if (appState == AppState::TICTACTOE) {
        if (roundOver) {
            if (hitTestPlayAgainButton(layout, tx, ty)) {
                startNewRound();
            }
            return;
        }

        if (aiMovePending || !board.isHumanTurn()) return; // ignore taps while the AI is "thinking"

        uint8_t cell;
        if (!hitTestCell(layout, tx, ty, cell)) return;
        if (!board.playHuman(cell)) return; // illegal tap (cell taken) -- silently ignored, same as GameSuite's own games do

        drawCell(tft, layout, cell, HUMAN);
        RoundResult r = board.result();
        if (r != RoundResult::IN_PROGRESS) {
            checkForRoundEnd();
            return;
        }
        refreshStatusText();
        aiMovePending = true;
        aiMoveDueAt = millis() + AI_MOVE_DELAY_MS;
        return;
    }

    if (appState == AppState::CHECKERS) {
        if (checkersRoundOver) {
            if (hitTestCheckersPlayAgainButton(checkersLayout, tx, ty)) {
                startNewCheckersRound();
            }
            return;
        }

        if (checkersAiMovePending || !checkersBoard.isHumanTurn()) return; // ignore taps while the AI is "thinking"

        uint8_t row, col;
        if (!hitTestSquare(checkersLayout, tx, ty, row, col)) return;

        // Two-step "tap source, tap destination" flow -- CheckersLogic.h's
        // own hasLegalMoveFrom()/isLegalMove() already carry every rule this
        // needs (mandatory capture, forced continuation, direction/king
        // rules), so this has zero checkers-rules knowledge of its own; see
        // CheckersDisplay.h's hitTestSquare() comment for the worked example
        // this mirrors almost verbatim.
        if (!checkersHaveSelection) {
            if (checkersBoard.hasLegalMoveFrom(row, col)) {
                checkersSelRow = row;
                checkersSelCol = col;
                checkersHaveSelection = true;
                highlightCheckersSelection(row, col);
            }
            return;
        }

        if (checkersBoard.isLegalMove(checkersSelRow, checkersSelCol, row, col)) {
            uint8_t fromRow = checkersSelRow, fromCol = checkersSelCol;
            CheckersPiece movingPiece = checkersBoard.at(fromRow, fromCol); // captured BEFORE playHuman() mutates the board -- see animateCheckersMove's header comment

            // A human plays exactly one hop per tap, so this is always a
            // single-segment "chain" -- see animateCheckersMove()'s header
            // comment. If this hop is a jump, its capture square's piece
            // must likewise be read now, before playHuman() below mutates it.
            uint8_t waypointRows[2] = { fromRow, row };
            uint8_t waypointCols[2] = { fromCol, col };
            CheckersPiece capturedPieces[1] = { CheckersPiece::EMPTY };
            uint8_t midRow, midCol;
            if (checkersJumpMidpoint(fromRow, fromCol, row, col, midRow, midCol)) {
                capturedPieces[0] = checkersBoard.at(midRow, midCol);
            }
            animateCheckersMove(tft, checkersLayout, checkersBoard, waypointRows, waypointCols, capturedPieces, 1, movingPiece);
            checkersBoard.playHuman(fromRow, fromCol, row, col);
            drawCheckersBoard(tft, checkersLayout, checkersBoard); // final correct redraw -- catches up captures/promotion/kinging the slide itself doesn't know about

            uint8_t contRow, contCol;
            if (checkersBoard.inForcedContinuation(contRow, contCol)) {
                // Same piece must jump again -- keep the selection (now at
                // its landing square) instead of handing off to the AI.
                checkersSelRow = contRow;
                checkersSelCol = contCol;
                highlightCheckersSelection(contRow, contCol);
                return;
            }

            checkersHaveSelection = false;
            CheckersResult r = checkersBoard.result();
            if (r != CheckersResult::IN_PROGRESS) {
                checkForCheckersRoundEnd();
                return;
            }
            refreshCheckersStatusText();
            checkersAiMovePending = true;
            checkersAiMoveDueAt = millis() + AI_MOVE_DELAY_MS;
        } else {
            // Tapped somewhere that isn't a legal destination for the
            // current selection -- drop it, same as any other illegal tap
            // in this project (silently ignored, not an error).
            checkersHaveSelection = false;
            drawCheckersBoard(tft, checkersLayout, checkersBoard); // clears the highlight
        }
        return;
    }

    if (appState == AppState::CHESS) {
        if (chessRoundOver) {
            if (hitTestChessPlayAgainButton(chessLayout, tx, ty)) {
                startNewChessRound();
            }
            return;
        }

        if (chessAiMovePending || !chessBoard.isHumanTurn()) return; // ignore taps while the AI is "thinking"

        uint8_t row, col;
        if (!hitTestSquare(chessLayout, tx, ty, row, col)) return;
        uint8_t sq = row * 8 + col;
        uint8_t dests[32];
        bool moved = false;

        // Two-step "tap source, tap destination" flow -- mirrors
        // ChessDisplay.h's own hitTestSquare() comment almost verbatim.
        // legalDestinations()/playHuman() carry every rule this needs (pins,
        // check, castling, en passant, promotion), so this has zero chess-
        // rules knowledge of its own -- selection is purely a UI convenience
        // for what to highlight, never itself trusted as a legality decision.
        if (chessSelectedSquare < 0) {
            if (chessBoard.legalDestinations(sq, dests) > 0) chessSelectedSquare = sq;
        } else if (sq == (uint8_t)chessSelectedSquare) {
            chessSelectedSquare = -1; // tapping the selected piece again deselects it
        } else {
            // Snapshot BEFORE playHuman() below might mutate the board --
            // see animateChessMove()'s header comment for why it needs a
            // pre-move copy (a captured piece's own identity, castling's
            // rook included, is otherwise already gone by the time this
            // animates).
            ChessBoard chessBoardBeforeMove = chessBoard;
            if (chessBoard.playHuman((uint8_t)chessSelectedSquare, sq)) {
                chessSelectedSquare = -1;
                moved = true;
                animateChessMove(tft, chessLayout, chessBoard, chessBoardBeforeMove);
            } else {
                // Tapped another of your own pieces (reselect) or an illegal
                // square (drop the selection) -- either way, no rules
                // knowledge needed here since legalDestinations() decides
                // which it was.
                chessSelectedSquare = (chessBoard.legalDestinations(sq, dests) > 0) ? (int8_t)sq : -1;
            }
        }

        drawChessBoard(tft, chessLayout, chessBoard, chessSelectedSquare);

        if (moved) {
            ChessRoundResult r = chessBoard.result();
            if (r != ChessRoundResult::IN_PROGRESS) {
                checkForChessRoundEnd();
            } else {
                refreshChessStatusText();
                chessAiMovePending = true;
                chessAiMoveDueAt = millis() + AI_MOVE_DELAY_MS;
            }
        }
        return;
    }

    if (appState == AppState::UNO) {
        if (unoRoundOver) {
            if (hitTestUnoPlayAgainButton(unoLayout, tx, ty)) {
                startNewUnoRound();
            }
            return;
        }

        if (unoRound.awaitingColorChoice()) {
            UnoColor chosen;
            if (hitTestUnoColorOverlay(unoColorOverlayLayout, tx, ty, chosen)) {
                if (unoRound.chooseHumanColor(chosen)) {
                    hideUnoColorOverlay(tft, unoColorOverlayLayout);
                    afterUnoStateChange();
                }
            }
            return;
        }

        if (unoAiMovePending || !unoRound.isHumanTurn()) return; // ignore taps while the AI is "thinking"

        uint8_t idx;
        if (hitTestUnoHumanHandCard(unoLayout, unoRound.humanHandCount(), tx, ty, idx)) {
            if (unoRound.playHumanCard(idx)) afterUnoStateChange(); // illegal card -- silently ignored, same as any other illegal tap in this project
            return;
        }

        if (hitTestUnoDrawPile(unoLayout, tx, ty)) {
            bool ok = unoRound.awaitingHumanDrawDecision() ? unoRound.keepHumanDrawnCard() : unoRound.drawHumanCard();
            if (ok) afterUnoStateChange();
            return;
        }
    }
}

void loadOrRunCalibration() {
    prefs.begin(CALIBRATION_NAMESPACE, false);

    uint16_t calData[5];
    bool haveSavedCalibration = prefs.isKey(CALIBRATION_KEY) &&
        prefs.getBytesLength(CALIBRATION_KEY) == sizeof(calData);

    // Hold the very top-left corner of the screen down through power-on/reset
    // to force recalibration even if a (possibly stale, e.g. after swapping
    // panels) calibration is already saved.
    bool forceRecalibrate = false;
    if (haveSavedCalibration) {
        // Only counts as a deliberate recalibration request if the corner stays
        // held for the ENTIRE window -- a brief incidental touch while picking
        // the board up must not accidentally wipe a good calibration.
        unsigned long holdStart = millis();
        bool releasedEarly = false;
        while (millis() - holdStart < FORCE_RECALIBRATE_HOLD_MS) {
            uint16_t tx, ty;
            if (!tft.getTouch(&tx, &ty)) { releasedEarly = true; break; }
            delay(10);
        }
        forceRecalibrate = !releasedEarly;
    }

    if (haveSavedCalibration && !forceRecalibrate) {
        prefs.getBytes(CALIBRATION_KEY, calData, sizeof(calData));
        tft.setTouch(calData);
        prefs.end();
        Serial.println("[ArcadeOS] loaded saved touch calibration from flash");
        return;
    }

    Serial.println(haveSavedCalibration
        ? "[ArcadeOS] forced recalibration requested (corner held at boot)"
        : "[ArcadeOS] no saved calibration -- running first-time calibration");

    tft.fillScreen(THEME_BG);
    tft.setTextColor(THEME_TEXT, THEME_BG);
    tft.setTextDatum(MC_DATUM);
    tft.drawString("Touch each corner", SCREEN_WIDTH / 2, SCREEN_HEIGHT / 2 - 20);
    tft.drawString("as it's marked", SCREEN_WIDTH / 2, SCREEN_HEIGHT / 2 + 10);
    delay(1200);

    // TFT_eSPI's own calibration routine: draws a target in each corner in
    // turn, waits for a touch, and fills calData with the raw ADC min/max
    // range this specific physical panel reports -- see this library's
    // "Touch_calibrate" example for the same call used the same way.
    tft.calibrateTouch(calData, THEME_TEXT, THEME_BG, 20);
    Serial.printf("[ArcadeOS] calibration captured: %u,%u,%u,%u,%u\n",
                  calData[0], calData[1], calData[2], calData[3], calData[4]);

    prefs.putBytes(CALIBRATION_KEY, calData, sizeof(calData));
    prefs.end();
}
