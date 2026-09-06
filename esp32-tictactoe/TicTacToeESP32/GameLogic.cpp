#include "GameLogic.h"

// Same 8 winning lines as GameSuite's Android TicTacToeGame.kt's WIN_LINES.
static const uint8_t WIN_LINES[8][3] = {
    {0, 1, 2}, {3, 4, 5}, {6, 7, 8}, // rows
    {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, // columns
    {0, 4, 8}, {2, 4, 6}            // diagonals
};

static uint8_t winnerOf(const uint8_t b[9]) {
    for (auto &line : WIN_LINES) {
        uint8_t a = b[line[0]], c = b[line[1]], d = b[line[2]];
        if (a != EMPTY && a == c && c == d) return a;
    }
    return EMPTY;
}

static bool isFull(const uint8_t b[9]) {
    for (uint8_t i = 0; i < 9; i++) if (b[i] == EMPTY) return false;
    return true;
}

void TicTacToeBoard::reset() {
    for (uint8_t i = 0; i < 9; i++) board[i] = EMPTY;
    humanTurn = true;
}

bool TicTacToeBoard::playHuman(uint8_t cell) {
    if (cell >= 9 || board[cell] != EMPTY) return false;
    if (!humanTurn || result() != RoundResult::IN_PROGRESS) return false;
    board[cell] = HUMAN;
    humanTurn = false;
    return true;
}

void TicTacToeBoard::playAi() {
    if (humanTurn || result() != RoundResult::IN_PROGRESS) return;
    uint8_t move = findBestAiMove();
    if (move < 9) board[move] = AI;
    humanTurn = true;
}

RoundResult TicTacToeBoard::result() const {
    uint8_t w = winnerOf(board);
    if (w == HUMAN) return RoundResult::HUMAN_WINS;
    if (w == AI) return RoundResult::AI_WINS;
    if (isFull(board)) return RoundResult::DRAW;
    return RoundResult::IN_PROGRESS;
}

void TicTacToeBoard::winningLine(int8_t outLine[3]) const {
    for (auto &line : WIN_LINES) {
        uint8_t a = board[line[0]], c = board[line[1]], d = board[line[2]];
        if (a != EMPTY && a == c && c == d) {
            outLine[0] = line[0];
            outLine[1] = line[1];
            outLine[2] = line[2];
            return;
        }
    }
    outLine[0] = outLine[1] = outLine[2] = -1;
}

// Depth-scored exactly like TicTacToeGame.kt's minimax: 10-depth for a faster
// AI win, depth-10 for a slower AI loss, so the AI prefers to win quickly and
// lose slowly rather than "eventually" in a way that looks careless when two
// lines lead to the same outcome. AI is the maximizer, human the minimizer.
//
// Free-function minimax operating on an explicit scratch board, so it never
// touches TicTacToeBoard::board directly (keeping `at()`/`result()` callers
// safe from ever observing a mid-search board) and needs no const_cast.
static int minimaxOn(uint8_t b[9], uint8_t depth, bool maximizing) {
    uint8_t w = winnerOf(b);
    if (w == AI) return 10 - depth;
    if (w == HUMAN) return depth - 10;
    if (isFull(b)) return 0;

    if (maximizing) {
        int best = -1000;
        for (uint8_t i = 0; i < 9; i++) {
            if (b[i] != EMPTY) continue;
            b[i] = AI;
            int score = minimaxOn(b, depth + 1, false);
            b[i] = EMPTY;
            if (score > best) best = score;
        }
        return best;
    } else {
        int best = 1000;
        for (uint8_t i = 0; i < 9; i++) {
            if (b[i] != EMPTY) continue;
            b[i] = HUMAN;
            int score = minimaxOn(b, depth + 1, true);
            b[i] = EMPTY;
            if (score < best) best = score;
        }
        return best;
    }
}

uint8_t TicTacToeBoard::findBestAiMove() const {
    uint8_t scratch[9];
    for (uint8_t i = 0; i < 9; i++) scratch[i] = board[i];

    int bestScore = -1000;
    uint8_t bestMove = 9; // sentinel: "no legal move" (shouldn't happen -- caller already checked result())
    for (uint8_t i = 0; i < 9; i++) {
        if (scratch[i] != EMPTY) continue;
        scratch[i] = AI;
        int score = minimaxOn(scratch, 1, false);
        scratch[i] = EMPTY;
        if (score > bestScore) {
            bestScore = score;
            bestMove = i;
        }
    }
    return bestMove;
}
