#include "CheckersLogic.h"

// ---------------------------------------------------------------------------
// Small piece-identity helpers
// ---------------------------------------------------------------------------
static bool pieceIsHuman(CheckersPiece p) {
    return p == CheckersPiece::HUMAN_MAN || p == CheckersPiece::HUMAN_KING;
}
static bool pieceIsAi(CheckersPiece p) {
    return p == CheckersPiece::AI_MAN || p == CheckersPiece::AI_KING;
}
static bool isOwn(CheckersPiece p, bool humanSide) {
    return humanSide ? pieceIsHuman(p) : pieceIsAi(p);
}
static bool isEnemy(CheckersPiece p, bool humanSide) {
    return humanSide ? pieceIsAi(p) : pieceIsHuman(p);
}

// Diagonal step directions {dRow, dCol} a piece is allowed to move/jump in.
// Men only ever move "forward" (toward their own promotion row); kings move
// any of the four. Writes a pointer to the right table plus its length --
// shared by squareHasJump()/appendJumpsFromSquare()/appendSimpleMovesFromSquare()
// so the direction rules only ever live in one place.
static void directionsFor(CheckersPiece p, const int8_t (*&dirs)[2], uint8_t &dirCount) {
    static const int8_t ALL_DIRS[4][2]       = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
    static const int8_t HUMAN_MAN_DIRS[2][2] = {{-1, -1}, {-1, 1}}; // human promotes at row 0
    static const int8_t AI_MAN_DIRS[2][2]    = {{1, -1}, {1, 1}};   // AI promotes at row 7

    if (p == CheckersPiece::HUMAN_KING || p == CheckersPiece::AI_KING) {
        dirs = ALL_DIRS;
        dirCount = 4;
    } else if (p == CheckersPiece::HUMAN_MAN) {
        dirs = HUMAN_MAN_DIRS;
        dirCount = 2;
    } else { // AI_MAN (EMPTY is never passed in -- every caller checks first)
        dirs = AI_MAN_DIRS;
        dirCount = 2;
    }
}

void CheckersBoard::reset() {
    for (uint8_t row = 0; row < 8; row++) {
        for (uint8_t col = 0; col < 8; col++) {
            board[row][col] = CheckersPiece::EMPTY;
        }
    }
    // Standard 8x8 setup: 12 men each, on the dark squares of the 3 rows
    // closest to each side, the middle 2 rows empty.
    for (uint8_t row = 0; row < 3; row++) {
        for (uint8_t col = 0; col < 8; col++) {
            if ((row + col) % 2 == 1) board[row][col] = CheckersPiece::AI_MAN;
        }
    }
    for (uint8_t row = 5; row < 8; row++) {
        for (uint8_t col = 0; col < 8; col++) {
            if ((row + col) % 2 == 1) board[row][col] = CheckersPiece::HUMAN_MAN;
        }
    }
    humanTurn = true;
    hasForcedContinuation = false;
    forcedRow = forcedCol = 0;
}

uint8_t CheckersBoard::pieceCount(bool humanSide) const {
    uint8_t n = 0;
    for (uint8_t row = 0; row < 8; row++)
        for (uint8_t col = 0; col < 8; col++)
            if (isOwn(board[row][col], humanSide)) n++;
    return n;
}

bool CheckersBoard::squareHasJump(uint8_t row, uint8_t col) const {
    CheckersPiece p = board[row][col];
    if (p == CheckersPiece::EMPTY) return false;
    bool human = pieceIsHuman(p);

    const int8_t (*dirs)[2];
    uint8_t dirCount;
    directionsFor(p, dirs, dirCount);

    for (uint8_t i = 0; i < dirCount; i++) {
        int dr = dirs[i][0], dc = dirs[i][1];
        int landR = (int)row + 2 * dr, landC = (int)col + 2 * dc;
        if (landR < 0 || landR > 7 || landC < 0 || landC > 7) continue;
        int midR = (int)row + dr, midC = (int)col + dc; // always in-bounds: it's the midpoint of two in-bounds squares
        if (!isEnemy(board[midR][midC], human)) continue;
        if (board[landR][landC] != CheckersPiece::EMPTY) continue;
        return true;
    }
    return false;
}

bool CheckersBoard::sideHasAnyCapture(bool humanSide) const {
    for (uint8_t row = 0; row < 8; row++)
        for (uint8_t col = 0; col < 8; col++)
            if (isOwn(board[row][col], humanSide) && squareHasJump(row, col)) return true;
    return false;
}

void CheckersBoard::appendJumpsFromSquare(uint8_t row, uint8_t col, CheckersMove out[], uint8_t &count) const {
    CheckersPiece p = board[row][col];
    if (p == CheckersPiece::EMPTY) return;
    bool human = pieceIsHuman(p);

    const int8_t (*dirs)[2];
    uint8_t dirCount;
    directionsFor(p, dirs, dirCount);

    for (uint8_t i = 0; i < dirCount; i++) {
        int dr = dirs[i][0], dc = dirs[i][1];
        int landR = (int)row + 2 * dr, landC = (int)col + 2 * dc;
        if (landR < 0 || landR > 7 || landC < 0 || landC > 7) continue;
        int midR = (int)row + dr, midC = (int)col + dc;
        if (!isEnemy(board[midR][midC], human)) continue;
        if (board[landR][landC] != CheckersPiece::EMPTY) continue;
        if (count >= CHECKERS_MAX_MOVES) return; // defensive; never hit given the bound's derivation
        out[count++] = CheckersMove{row, col, (uint8_t)landR, (uint8_t)landC, true, (uint8_t)midR, (uint8_t)midC};
    }
}

void CheckersBoard::appendSimpleMovesFromSquare(uint8_t row, uint8_t col, CheckersMove out[], uint8_t &count) const {
    CheckersPiece p = board[row][col];
    if (p == CheckersPiece::EMPTY) return;

    const int8_t (*dirs)[2];
    uint8_t dirCount;
    directionsFor(p, dirs, dirCount);

    for (uint8_t i = 0; i < dirCount; i++) {
        int dr = dirs[i][0], dc = dirs[i][1];
        int r = (int)row + dr, c = (int)col + dc;
        if (r < 0 || r > 7 || c < 0 || c > 7) continue;
        if (board[r][c] != CheckersPiece::EMPTY) continue;
        if (count >= CHECKERS_MAX_MOVES) return;
        out[count++] = CheckersMove{row, col, (uint8_t)r, (uint8_t)c, false, 0, 0};
    }
}

uint8_t CheckersBoard::generateMoves(bool humanSide, CheckersMove out[]) const {
    uint8_t count = 0;

    // Forced continuation only restricts move generation when it's actually
    // this exact side's turn -- a query about the OTHER side (e.g. the
    // evaluator's one-sided mobility term) should see that side's normal
    // options, not be starved by continuation state that belongs to its
    // opponent.
    if (hasForcedContinuation && humanTurn == humanSide) {
        appendJumpsFromSquare(forcedRow, forcedCol, out, count);
        return count;
    }

    bool anyCapture = sideHasAnyCapture(humanSide);
    for (uint8_t row = 0; row < 8; row++) {
        for (uint8_t col = 0; col < 8; col++) {
            if (!isOwn(board[row][col], humanSide)) continue;
            if (anyCapture) appendJumpsFromSquare(row, col, out, count);
            else appendSimpleMovesFromSquare(row, col, out, count);
        }
    }
    return count;
}

void CheckersBoard::applyMove(const CheckersMove &m) {
    CheckersPiece moving = board[m.fromRow][m.fromCol];
    board[m.fromRow][m.fromCol] = CheckersPiece::EMPTY;
    if (m.isCapture) board[m.capRow][m.capCol] = CheckersPiece::EMPTY;
    board[m.toRow][m.toCol] = moving;

    bool promoted = false;
    if (moving == CheckersPiece::HUMAN_MAN && m.toRow == 0) {
        board[m.toRow][m.toCol] = CheckersPiece::HUMAN_KING;
        promoted = true;
    } else if (moving == CheckersPiece::AI_MAN && m.toRow == 7) {
        board[m.toRow][m.toCol] = CheckersPiece::AI_KING;
        promoted = true;
    }

    // A piece that just kinged stops there for the turn even if, as a king,
    // it could immediately jump again -- this project takes the simpler,
    // commonly-taught ruling on that otherwise rules-debated edge case
    // rather than the "keeps jumping as a king" variant some tournament
    // rules use instead. Otherwise: any capture that leaves a further jump
    // available to the SAME piece keeps the turn with the same side.
    if (m.isCapture && !promoted && squareHasJump(m.toRow, m.toCol)) {
        hasForcedContinuation = true;
        forcedRow = m.toRow;
        forcedCol = m.toCol;
        return; // humanTurn deliberately left unchanged
    }

    hasForcedContinuation = false;
    humanTurn = !humanTurn;
}

CheckersResult CheckersBoard::result() const {
    bool sideIsHuman = humanTurn;
    if (pieceCount(sideIsHuman) == 0) {
        return sideIsHuman ? CheckersResult::AI_WINS : CheckersResult::HUMAN_WINS;
    }
    CheckersMove moves[CHECKERS_MAX_MOVES];
    if (generateMoves(sideIsHuman, moves) == 0) {
        return sideIsHuman ? CheckersResult::AI_WINS : CheckersResult::HUMAN_WINS;
    }
    return CheckersResult::IN_PROGRESS;
}

bool CheckersBoard::inForcedContinuation(uint8_t &outRow, uint8_t &outCol) const {
    if (!hasForcedContinuation) return false;
    outRow = forcedRow;
    outCol = forcedCol;
    return true;
}

bool CheckersBoard::isLegalMove(uint8_t fromRow, uint8_t fromCol, uint8_t toRow, uint8_t toCol) const {
    // Answers for whichever side is ACTUALLY to move right now (see
    // CheckersLogic.h) -- not gated to the human, since the random-vs-random
    // self-play simulation and playMoveForTest() below need this to work
    // for both sides.
    if (fromRow > 7 || fromCol > 7 || toRow > 7 || toCol > 7) return false;
    CheckersMove moves[CHECKERS_MAX_MOVES];
    uint8_t n = generateMoves(humanTurn, moves);
    for (uint8_t i = 0; i < n; i++) {
        if (moves[i].fromRow == fromRow && moves[i].fromCol == fromCol &&
            moves[i].toRow == toRow && moves[i].toCol == toCol) return true;
    }
    return false;
}

bool CheckersBoard::hasLegalMoveFrom(uint8_t row, uint8_t col) const {
    if (row > 7 || col > 7) return false;
    CheckersMove moves[CHECKERS_MAX_MOVES];
    uint8_t n = generateMoves(humanTurn, moves);
    for (uint8_t i = 0; i < n; i++) {
        if (moves[i].fromRow == row && moves[i].fromCol == col) return true;
    }
    return false;
}

uint8_t CheckersBoard::legalDestinationsFrom(uint8_t row, uint8_t col, uint8_t outRows[], uint8_t outCols[]) const {
    if (row > 7 || col > 7) return 0;
    CheckersMove moves[CHECKERS_MAX_MOVES];
    uint8_t n = generateMoves(humanTurn, moves);
    uint8_t found = 0;
    for (uint8_t i = 0; i < n; i++) {
        if (moves[i].fromRow == row && moves[i].fromCol == col) {
            outRows[found] = moves[i].toRow;
            outCols[found] = moves[i].toCol;
            found++;
        }
    }
    return found;
}

uint8_t CheckersBoard::allLegalMoves(uint8_t outFromRows[], uint8_t outFromCols[], uint8_t outToRows[], uint8_t outToCols[]) const {
    CheckersMove moves[CHECKERS_MAX_MOVES];
    uint8_t n = generateMoves(humanTurn, moves);
    for (uint8_t i = 0; i < n; i++) {
        outFromRows[i] = moves[i].fromRow;
        outFromCols[i] = moves[i].fromCol;
        outToRows[i] = moves[i].toRow;
        outToCols[i] = moves[i].toCol;
    }
    return n;
}

bool CheckersBoard::playHuman(uint8_t fromRow, uint8_t fromCol, uint8_t toRow, uint8_t toCol) {
    if (!humanTurn) return false;
    if (result() != CheckersResult::IN_PROGRESS) return false;
    if (fromRow > 7 || fromCol > 7 || toRow > 7 || toCol > 7) return false;

    CheckersMove moves[CHECKERS_MAX_MOVES];
    uint8_t n = generateMoves(true, moves);
    for (uint8_t i = 0; i < n; i++) {
        if (moves[i].fromRow == fromRow && moves[i].fromCol == fromCol &&
            moves[i].toRow == toRow && moves[i].toCol == toCol) {
            applyMove(moves[i]);
            return true;
        }
    }
    return false;
}

void CheckersBoard::resetEmptyForTest() {
    for (uint8_t row = 0; row < 8; row++)
        for (uint8_t col = 0; col < 8; col++)
            board[row][col] = CheckersPiece::EMPTY;
    humanTurn = true;
    hasForcedContinuation = false;
    forcedRow = forcedCol = 0;
}

void CheckersBoard::setSquareForTest(uint8_t row, uint8_t col, CheckersPiece piece) {
    if (row > 7 || col > 7) return;
    board[row][col] = piece;
}

void CheckersBoard::setTurnForTest(bool humanSide) {
    humanTurn = humanSide;
    hasForcedContinuation = false;
    forcedRow = forcedCol = 0;
}

bool CheckersBoard::playMoveForTest(uint8_t fromRow, uint8_t fromCol, uint8_t toRow, uint8_t toCol) {
    if (result() != CheckersResult::IN_PROGRESS) return false;
    if (fromRow > 7 || fromCol > 7 || toRow > 7 || toCol > 7) return false;

    CheckersMove moves[CHECKERS_MAX_MOVES];
    uint8_t n = generateMoves(humanTurn, moves);
    for (uint8_t i = 0; i < n; i++) {
        if (moves[i].fromRow == fromRow && moves[i].fromCol == fromCol &&
            moves[i].toRow == toRow && moves[i].toCol == toCol) {
            applyMove(moves[i]);
            return true;
        }
    }
    return false;
}

// ---------------------------------------------------------------------------
// AI: minimax with alpha-beta pruning.
//
// Score convention mirrors GameLogic.cpp's minimax exactly: positive favors
// the AI (the maximizer), negative favors the human (the minimizer), and a
// decisive result is scored as a huge magnitude ADJUSTED BY PLY-FROM-ROOT so
// that among equally-winning lines the AI prefers the one that wins soonest,
// and among equally-losing lines (an AI down bad enough that every line
// loses) it prefers the one that loses latest -- same "faster win, slower
// loss" spirit as the tic-tac-toe AI, just carried over to a game where
// depth-limited heuristic evaluation (rather than always searching to a true
// end-of-game) is what makes the search tractable at all.
//
// Search depth: 6 plies, where a "ply" is one HOP (a plain step, or one link
// of a capture chain) rather than one full turn -- a forced multi-jump chain
// therefore spends several plies of the same side's budget in a row rather
// than passing through an opponent reply each time, which is a deliberate
// simplification (see minimaxSearch below) that trades a little search depth
// during the (rare) long capture chains for a much simpler recursion.
//
// 6 was picked from the requested 6-8 range, at the conservative end, based
// on an actual measurement: native_test/checkers_playtest.cpp's
// randomHumanVsRealAiSimulation() times every real playAi() call (a whole AI
// turn, multi-jumps included) across ~3000 AI turns. On this project's dev
// machine (a modern x86-64 desktop, nowhere near as constrained as the
// target board) that came out to ~2.2ms average and ~31ms worst-case at
// depth 6, versus ~9.5ms average and a 281ms worst-case at depth 7 -- the
// depth-7 worst case already eats a meaningful slice of a 1-second budget
// on a machine roughly two orders of magnitude faster per instruction than
// an ESP32-32E at 240MHz, so depth 6 is the one actually shipped. This is a
// static, fixed-depth search with no iterative deepening or time-boxing --
// a real time-boxed search (return the best move found so far once a
// deadline hits) would adapt to the ESP32's actual per-move budget instead
// of guessing it from a desktop proxy, and would be the more robust
// production approach, but is out of scope here.
static const int AI_SEARCH_DEPTH = 6;

static const int WIN_SCORE = 1000000;   // far above any reachable heuristic sum -- see evaluateHeuristic
static const int SCORE_INF = 2000000;

// Heuristic terms: material dominates (a king is worth ~1.75 men, a common
// rule-of-thumb weighting -- kings are strictly more valuable since they
// can move/capture in all four diagonal directions instead of two), then a
// small per-row advancement bonus for men so the AI has a reason to push
// forward instead of shuffling, then a small bonus for keeping the two
// back-row squares of its own home row occupied (an empty back row lets the
// opponent walk a man straight through to king it), then a cheap one-sided
// mobility term (see below) if there's a tie to break. None of these
// weights were tuned by anything more rigorous than "plays sensibly in the
// playthrough transcript" -- a learned or exhaustively-tuned evaluation is
// out of scope here, matching this project's existing pragmatic approach to
// its tic-tac-toe heuristic (which needed none at all, since that game is
// small enough to solve exactly).
static const int MAN_VALUE = 100;
static const int KING_VALUE = 175;
static const int ADVANCEMENT_WEIGHT = 4;
static const int BACK_ROW_BONUS = 12;
static const int MOBILITY_WEIGHT = 2;

int CheckersBoard::evaluateHeuristic(const CheckersBoard &b, bool sideToMoveIsHuman, uint8_t sideToMoveMobility) {
    int score = 0;
    for (uint8_t row = 0; row < 8; row++) {
        for (uint8_t col = 0; col < 8; col++) {
            switch (b.board[row][col]) {
                case CheckersPiece::AI_MAN:
                    score += MAN_VALUE + ADVANCEMENT_WEIGHT * (7 - row); // AI promotes at row 0
                    if (row == 7) score += BACK_ROW_BONUS;               // AI's own home row
                    break;
                case CheckersPiece::AI_KING:
                    score += KING_VALUE;
                    break;
                case CheckersPiece::HUMAN_MAN:
                    score -= MAN_VALUE + ADVANCEMENT_WEIGHT * row; // human promotes at row 7
                    if (row == 0) score -= BACK_ROW_BONUS;          // human's own home row
                    break;
                case CheckersPiece::HUMAN_KING:
                    score -= KING_VALUE;
                    break;
                default:
                    break;
            }
        }
    }
    // Mobility, cheaply: only the side-to-move's own move count (already
    // computed by the caller as part of its terminal check, so this costs
    // nothing extra) rather than a full two-sided differential, which would
    // need a second generateMoves() call at every leaf node -- a real cost
    // multiplied over however many leaves alpha-beta actually visits. This
    // is a deliberately partial version of "mobility if you have time":
    // directionally useful (more options for whoever is about to move here
    // is mildly good for them), not a rigorous differential.
    int mobilityTerm = MOBILITY_WEIGHT * (int)sideToMoveMobility;
    score += sideToMoveIsHuman ? -mobilityTerm : mobilityTerm;
    return score;
}

int CheckersBoard::minimaxSearch(int depthRemaining, int plyFromRoot, int alpha, int beta) const {
    bool sideIsHuman = humanTurn;

    if (pieceCount(sideIsHuman) == 0) {
        // The side to move has nothing left to move -- it loses.
        return sideIsHuman ? (WIN_SCORE - plyFromRoot) : -(WIN_SCORE - plyFromRoot);
    }
    CheckersMove moves[CHECKERS_MAX_MOVES];
    uint8_t n = generateMoves(sideIsHuman, moves);
    if (n == 0) {
        // The side to move has pieces but nowhere to move them -- it loses.
        return sideIsHuman ? (WIN_SCORE - plyFromRoot) : -(WIN_SCORE - plyFromRoot);
    }
    if (depthRemaining == 0) {
        return evaluateHeuristic(*this, sideIsHuman, n);
    }

    bool aiToMove = !sideIsHuman;
    if (aiToMove) {
        int best = -SCORE_INF;
        for (uint8_t i = 0; i < n; i++) {
            CheckersBoard next = *this;
            next.applyMove(moves[i]);
            int score = next.minimaxSearch(depthRemaining - 1, plyFromRoot + 1, alpha, beta);
            if (score > best) best = score;
            if (best > alpha) alpha = best;
            if (alpha >= beta) break; // beta cutoff
        }
        return best;
    } else {
        int best = SCORE_INF;
        for (uint8_t i = 0; i < n; i++) {
            CheckersBoard next = *this;
            next.applyMove(moves[i]);
            int score = next.minimaxSearch(depthRemaining - 1, plyFromRoot + 1, alpha, beta);
            if (score < best) best = score;
            if (best < beta) beta = best;
            if (alpha >= beta) break; // alpha cutoff
        }
        return best;
    }
}

CheckersMove CheckersBoard::findBestAiMove() const {
    CheckersMove moves[CHECKERS_MAX_MOVES];
    uint8_t n = generateMoves(false, moves); // false = AI side

    CheckersMove best{};
    best.fromRow = 255; // sentinel: "no legal move" -- callers must already have checked result()
    int bestScore = -SCORE_INF;
    for (uint8_t i = 0; i < n; i++) {
        CheckersBoard next = *this;
        next.applyMove(moves[i]);
        int score = next.minimaxSearch(AI_SEARCH_DEPTH - 1, 1, -SCORE_INF, SCORE_INF);
        if (best.fromRow == 255 || score > bestScore) {
            bestScore = score;
            best = moves[i];
        }
    }
    return best;
}

void CheckersBoard::playAi() {
    if (humanTurn) return;
    if (result() != CheckersResult::IN_PROGRESS) return;
    bool firstHop = true;
    do {
        CheckersMove m = findBestAiMove();
        if (m.fromRow == 255) break; // defensive: result() above should already rule this out
        if (firstHop) {
            lastAiFromRow = m.fromRow;
            lastAiFromCol = m.fromCol;
            firstHop = false;
        }
        lastAiToRow = m.toRow;
        lastAiToCol = m.toCol;
        applyMove(m);
    } while (!humanTurn && result() == CheckersResult::IN_PROGRESS);
}
