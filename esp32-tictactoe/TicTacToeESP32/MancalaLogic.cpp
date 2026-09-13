#include "MancalaLogic.h"

// ---------------------------------------------------------------------------
// EASY-tier RNG -- a small self-contained xorshift32, deliberately NOT
// Arduino's own random()/randomSeed() so this file compiles unchanged
// against native_test's no-op Arduino.h stub, exactly UnoLogic.cpp's own
// reasoning (see that file's identical RNG). Fixed default seed so both
// native_test and a board that never calls seedMancalaRandom() behave
// deterministically/reproducibly.
// ---------------------------------------------------------------------------
static uint32_t g_mancalaRngState = 0xC2B2AE35u;

void seedMancalaRandom(uint32_t seed) {
    g_mancalaRngState = seed ? seed : 0xC2B2AE35u; // xorshift32 must never be seeded with 0
}

static uint32_t mancalaNextRandom() {
    uint32_t x = g_mancalaRngState;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    g_mancalaRngState = x;
    return x;
}

// ---------------------------------------------------------------------------
// Small shared helpers -- pure functions over a plain pits[] array so both
// the real board (which just wraps `this->pits`) and the HARD tier's search
// (which explores thousands of hypothetical, throwaway copies) go through
// the exact same rules with zero duplication.
// ---------------------------------------------------------------------------
static bool ownsPit(bool humanSide, uint8_t pitIndex) {
    return humanSide ? (pitIndex <= 5) : (pitIndex >= 7 && pitIndex <= 12);
}

static uint8_t legalMovesFor(const uint8_t pits[MANCALA_PIT_COUNT], bool humanSide, uint8_t outPits[]) {
    uint8_t count = 0;
    uint8_t lo = humanSide ? 0 : 7;
    uint8_t hi = humanSide ? 5 : 12;
    for (uint8_t i = lo; i <= hi; i++) {
        if (pits[i] > 0) outPits[count++] = i;
    }
    return count;
}

static bool sideEmpty(const uint8_t pits[MANCALA_PIT_COUNT], bool humanSide) {
    uint8_t lo = humanSide ? 0 : 7;
    uint8_t hi = humanSide ? 5 : 12;
    for (uint8_t i = lo; i <= hi; i++) {
        if (pits[i] != 0) return false;
    }
    return true;
}

static bool isTerminal(const uint8_t pits[MANCALA_PIT_COUNT]) {
    return sideEmpty(pits, true) || sideEmpty(pits, false);
}

// A pure copy of applySow()'s rules with none of MancalaBoard's own
// instrumentation (lastSowPath/capture bookkeeping) and, unlike applySow(),
// no end-of-round sweep -- the HARD tier's search always checks
// isTerminal() itself before ever calling this again, and only ever scores
// a terminal (or depth-limit) leaf through finalScoreFor() below, which
// does its own sweep purely for scoring. Mirrors simulateSow()'s exact role
// in the Kotlin source this was ported from: explores thousands of
// hypothetical sows per move without ever touching (or risking corrupting)
// the real game state.
static void simulateSow(uint8_t pits[MANCALA_PIT_COUNT], bool humanSide, uint8_t pitIndex, bool &outExtraTurn) {
    uint8_t ownStore = humanSide ? MANCALA_HUMAN_STORE : MANCALA_AI_STORE;
    uint8_t opponentStore = humanSide ? MANCALA_AI_STORE : MANCALA_HUMAN_STORE;

    uint8_t stones = pits[pitIndex];
    pits[pitIndex] = 0;
    uint8_t cursor = pitIndex;
    while (stones > 0) {
        cursor = (cursor + 1) % MANCALA_PIT_COUNT;
        if (cursor == opponentStore) continue;
        pits[cursor]++;
        stones--;
    }

    outExtraTurn = false;
    if (ownsPit(humanSide, cursor) && pits[cursor] == 1) {
        uint8_t oppositeIndex = 12 - cursor;
        if (pits[oppositeIndex] > 0) {
            uint8_t captured = pits[oppositeIndex] + pits[cursor];
            pits[oppositeIndex] = 0;
            pits[cursor] = 0;
            pits[ownStore] += captured;
        }
    } else if (cursor == ownStore) {
        outExtraTurn = true;
    }
}

// Same end-of-game sweep as applySow()'s round-over branch, applied to a
// (throwaway, already-copied) hypothetical board purely for scoring -- a
// no-op sweep-wise when called on a non-terminal position (neither side is
// actually empty), which is exactly what happens when the HARD search's
// depth limit is hit before either side runs out. Score convention mirrors
// CheckersLogic.cpp's minimax exactly: positive favors the AI, negative
// favors the human. Mancala's own store COUNT already IS both the win
// condition and the natural evaluation (unlike Checkers, where a separate
// material/position heuristic is needed), so no extra weighting is needed
// at all here -- matching the Kotlin source's own finalScoreFor(), just
// hardcoded to the AI as the fixed maximizer (see MancalaLogic.h's header
// comment on why this board drops the Kotlin version's generic
// "maximizer" parameter).
static int finalScoreForAi(uint8_t pits[MANCALA_PIT_COUNT]) {
    if (sideEmpty(pits, true)) { for (uint8_t i = 7; i <= 12; i++) { pits[MANCALA_AI_STORE] += pits[i]; pits[i] = 0; } }
    if (sideEmpty(pits, false)) { for (uint8_t i = 0; i <= 5; i++) { pits[MANCALA_HUMAN_STORE] += pits[i]; pits[i] = 0; } }
    return (int)pits[MANCALA_AI_STORE] - (int)pits[MANCALA_HUMAN_STORE];
}

// Depth-limited minimax with alpha-beta pruning, AI-to-move maximizes,
// human-to-move minimizes -- structurally identical to
// CheckersBoard::minimaxSearch(), just operating on plain pits[] copies
// instead of a CheckersBoard value type (Mancala's whole state is 14
// bytes, cheaper to copy as a raw array than to wrap in a class for this).
static const int SCORE_INF = 2000000;

static int minimaxSearch(uint8_t pits[MANCALA_PIT_COUNT], bool humanToMove, int depthRemaining, int alpha, int beta) {
    if (depthRemaining == 0 || isTerminal(pits)) {
        return finalScoreForAi(pits);
    }
    uint8_t moves[MANCALA_MAX_LEGAL_MOVES];
    uint8_t n = legalMovesFor(pits, humanToMove, moves);
    if (n == 0) return finalScoreForAi(pits); // defensive -- isTerminal() above should already rule this out

    if (!humanToMove) { // AI to move -> maximize
        int best = -SCORE_INF;
        for (uint8_t i = 0; i < n; i++) {
            uint8_t next[MANCALA_PIT_COUNT];
            for (uint8_t k = 0; k < MANCALA_PIT_COUNT; k++) next[k] = pits[k];
            bool extraTurn;
            simulateSow(next, false, moves[i], extraTurn);
            bool nextHuman = !extraTurn;
            int score = minimaxSearch(next, nextHuman, depthRemaining - 1, alpha, beta);
            if (score > best) best = score;
            if (best > alpha) alpha = best;
            if (alpha >= beta) break; // beta cutoff
        }
        return best;
    } else { // human to move -> minimize
        int best = SCORE_INF;
        for (uint8_t i = 0; i < n; i++) {
            uint8_t next[MANCALA_PIT_COUNT];
            for (uint8_t k = 0; k < MANCALA_PIT_COUNT; k++) next[k] = pits[k];
            bool extraTurn;
            simulateSow(next, true, moves[i], extraTurn);
            bool nextHuman = extraTurn;
            int score = minimaxSearch(next, nextHuman, depthRemaining - 1, alpha, beta);
            if (score < best) best = score;
            if (best < beta) beta = best;
            if (alpha >= beta) break; // alpha cutoff
        }
        return best;
    }
}

// 6 plies deep -- the conservative end of what the Kotlin source's own
// depth-8 search assumed was "comfortably fast enough to run synchronously"
// (see MancalaGame.kt's HARD_SEARCH_DEPTH comment), which was reasoning
// about a phone-class ARM CPU, not this board's ESP32-32E at 240MHz.
// Deliberately matched to CheckersLogic.cpp's own AI_SEARCH_DEPTH=6 choice
// and its exact reasoning: native_test/mancala_playtest.cpp's own timing
// simulation measures playAi() at this depth on THIS DEV MACHINE (see that
// file), and depth 6 is what's actually shipped pending a real on-device
// measurement -- see CheckersLogic.cpp's own comment for why a desktop
// timing proxy is treated as a starting point, not a guarantee, for a
// two-orders-of-magnitude-slower target CPU.
static const int AI_SEARCH_DEPTH = 6;

// ---------------------------------------------------------------------------
// MancalaBoard
// ---------------------------------------------------------------------------
void MancalaBoard::reset() {
    for (uint8_t i = 0; i <= 5; i++) pits[i] = 4;
    pits[MANCALA_HUMAN_STORE] = 0;
    for (uint8_t i = 7; i <= 12; i++) pits[i] = 4;
    pits[MANCALA_AI_STORE] = 0;
    humanTurn = true;
    lastSowPathLen = 0;
    lastCaptureHappened = false;
    lastCaptureLanding = lastCaptureOpposite = lastCaptureSwept = 0;
    lastExtraTurn = false;
}

MancalaResult MancalaBoard::result() const {
    bool humanEmpty = sideEmpty(pits, true);
    bool aiEmpty = sideEmpty(pits, false);
    if (!humanEmpty && !aiEmpty) return MancalaResult::IN_PROGRESS;

    // Round concluded -- applySow()'s own end-of-round sweep always empties
    // BOTH sides' pits together (the side that ran out, plus whichever
    // pits the other side still held, swept into that other side's own
    // store), so seeing either side empty here means the round is over and
    // the stores already hold their final tallies.
    if (pits[MANCALA_HUMAN_STORE] > pits[MANCALA_AI_STORE]) return MancalaResult::HUMAN_WINS;
    if (pits[MANCALA_AI_STORE] > pits[MANCALA_HUMAN_STORE]) return MancalaResult::AI_WINS;
    return MancalaResult::DRAW;
}

bool MancalaBoard::isLegalMove(uint8_t pitIndex) const {
    if (pitIndex >= MANCALA_PIT_COUNT) return false;
    if (!ownsPit(humanTurn, pitIndex)) return false;
    return pits[pitIndex] > 0;
}

uint8_t MancalaBoard::legalMoves(uint8_t outPits[]) const {
    return legalMovesFor(pits, humanTurn, outPits);
}

void MancalaBoard::applySow(bool humanSide, uint8_t pitIndex) {
    uint8_t ownStore = humanSide ? MANCALA_HUMAN_STORE : MANCALA_AI_STORE;
    uint8_t opponentStore = humanSide ? MANCALA_AI_STORE : MANCALA_HUMAN_STORE;

    uint8_t stones = pits[pitIndex];
    pits[pitIndex] = 0;
    uint8_t cursor = pitIndex;

    // sowPath: pure instrumentation for a caller's own seed-hop cascade
    // animation (see lastSowPathLength()/lastSowPathPit()'s KDoc) -- the
    // loop below is otherwise identical to simulateSow()'s, it just also
    // records each cursor it already visits. Element 0 is the pit the
    // stones were picked up from, captured before the loop starts.
    lastSowPathLen = 0;
    lastSowPath[lastSowPathLen++] = pitIndex;

    while (stones > 0) {
        cursor = (cursor + 1) % MANCALA_PIT_COUNT;
        if (cursor == opponentStore) continue; // skip opponent's store
        pits[cursor]++;
        stones--;
        if (lastSowPathLen < MANCALA_MAX_SOW_PATH) lastSowPath[lastSowPathLen++] = cursor;
    }

    lastExtraTurn = false;
    lastCaptureHappened = false;
    lastCaptureLanding = lastCaptureOpposite = lastCaptureSwept = 0;

    // Landed in own empty pit (was 0 before this sow, now 1) -> capture.
    if (ownsPit(humanSide, cursor) && pits[cursor] == 1) {
        uint8_t oppositeIndex = 12 - cursor;
        if (pits[oppositeIndex] > 0) {
            uint8_t captured = pits[oppositeIndex] + pits[cursor];
            pits[oppositeIndex] = 0;
            pits[cursor] = 0;
            pits[ownStore] += captured;
            lastCaptureHappened = true;
            lastCaptureLanding = cursor;
            lastCaptureOpposite = oppositeIndex;
            lastCaptureSwept = captured;
        }
    } else if (cursor == ownStore) {
        lastExtraTurn = true;
    }

    bool humanEmpty = sideEmpty(pits, true);
    bool aiEmpty = sideEmpty(pits, false);
    if (humanEmpty || aiEmpty) {
        if (humanEmpty) { for (uint8_t i = 7; i <= 12; i++) { pits[MANCALA_AI_STORE] += pits[i]; pits[i] = 0; } }
        if (aiEmpty) { for (uint8_t i = 0; i <= 5; i++) { pits[MANCALA_HUMAN_STORE] += pits[i]; pits[i] = 0; } }
        // humanTurn deliberately left unchanged -- the round is over, so
        // isLegalMove()/legalMoves() already read as "no legal moves" for
        // either side regardless of whose turn this technically still says
        // it is (every pit is now empty).
        return;
    }

    humanTurn = lastExtraTurn ? humanSide : !humanSide;
}

bool MancalaBoard::playHuman(uint8_t pitIndex) {
    if (!humanTurn) return false;
    if (result() != MancalaResult::IN_PROGRESS) return false;
    if (!isLegalMove(pitIndex)) return false;
    applySow(true, pitIndex);
    return true;
}

bool MancalaBoard::playMoveForTest(uint8_t pitIndex) {
    if (result() != MancalaResult::IN_PROGRESS) return false;
    if (!isLegalMove(pitIndex)) return false;
    applySow(humanTurn, pitIndex);
    return true;
}

uint8_t MancalaBoard::pickAiMove() const {
    uint8_t moves[MANCALA_MAX_LEGAL_MOVES];
    uint8_t n = legalMoves(moves);
    // n is always >= 1 here -- playAi() already checked result() ==
    // IN_PROGRESS, which (see result()'s own comment) guarantees the AI's
    // own pits aren't all empty.
    switch (cpuDifficulty) {
        case CpuDifficulty::EASY:
            return moves[mancalaNextRandom() % n];

        case CpuDifficulty::MEDIUM: {
            // Prefer a move landing exactly in the AI's own store (extra
            // turn); else the first non-empty pit -- identical tier to the
            // Kotlin source's own MEDIUM, unchanged since it shipped there.
            for (uint8_t i = 0; i < n; i++) {
                uint8_t pit = moves[i];
                if ((uint16_t)(pit + pits[pit]) % MANCALA_PIT_COUNT == MANCALA_AI_STORE) return pit;
            }
            return moves[0];
        }

        case CpuDifficulty::HARD:
        default: {
            uint8_t best = moves[0];
            int bestScore = -SCORE_INF;
            for (uint8_t i = 0; i < n; i++) {
                uint8_t next[MANCALA_PIT_COUNT];
                for (uint8_t k = 0; k < MANCALA_PIT_COUNT; k++) next[k] = pits[k];
                bool extraTurn;
                simulateSow(next, false, moves[i], extraTurn);
                bool nextHuman = !extraTurn;
                int score = minimaxSearch(next, nextHuman, AI_SEARCH_DEPTH - 1, -SCORE_INF, SCORE_INF);
                if (score > bestScore) {
                    bestScore = score;
                    best = moves[i];
                }
            }
            return best;
        }
    }
}

void MancalaBoard::playAi() {
    if (humanTurn) return;
    if (result() != MancalaResult::IN_PROGRESS) return;
    uint8_t pit = pickAiMove();
    applySow(false, pit);
}

void MancalaBoard::setPitsForTest(const uint8_t values[MANCALA_PIT_COUNT]) {
    for (uint8_t i = 0; i < MANCALA_PIT_COUNT; i++) pits[i] = values[i];
    lastSowPathLen = 0;
    lastCaptureHappened = false;
    lastExtraTurn = false;
}

void MancalaBoard::setTurnForTest(bool humanSide) {
    humanTurn = humanSide;
}
