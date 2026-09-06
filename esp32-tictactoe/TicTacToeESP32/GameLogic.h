#pragma once
#include <Arduino.h>

// Pure game logic -- no display/touch code here at all, mirroring the same
// split GameSuite's Android version uses (TicTacToeGame.kt vs
// TicTacToeScreen.kt): this file could be unit-tested on a desktop compiler
// with zero changes, same spirit as the Android app's own JUnit tests for
// the identical algorithm.

enum Cell : uint8_t { EMPTY = 0, HUMAN = 1, AI = 2 };

enum class RoundResult : uint8_t { IN_PROGRESS, HUMAN_WINS, AI_WINS, DRAW };

class TicTacToeBoard {
public:
    void reset();

    // Returns false if the move was illegal (cell taken, or game already over) --
    // the caller should ignore the tap rather than mutate anything on false.
    bool playHuman(uint8_t cell);

    // Picks and plays the AI's move via full minimax (see .cpp -- exhaustively
    // solving 3x3 tic-tac-toe is a few hundred thousand nodes at most, which an
    // ESP32 at 240MHz finishes in low tens of milliseconds, so this needs no
    // pruning or difficulty tiers the way GameSuite's word-heavy games do).
    // No-op if it isn't currently the AI's turn to move.
    void playAi();

    uint8_t at(uint8_t cell) const { return board[cell]; }
    RoundResult result() const;

    // The 3 cell indices of the winning line, or {-1,-1,-1} if there isn't one yet
    // -- Display.cpp uses this to draw a strike-through line over the winner.
    void winningLine(int8_t outLine[3]) const;

    bool isHumanTurn() const { return humanTurn; }

private:
    uint8_t board[9] = {EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY};
    bool humanTurn = true;

    // The actual minimax search runs in a free function in GameLogic.cpp over an
    // explicit scratch copy of the board, not as a member touching `board`
    // directly -- see that file for why. This just kicks off that search over
    // every legal first move and keeps the best one.
    uint8_t findBestAiMove() const;
};
