#pragma once
#include <Arduino.h>
#include "Difficulty.h"

// Pure game logic for Mancala (the Kalah variant, 4 stones/pit) -- no
// display/touch code here at all, mirroring GameLogic.h/CheckersLogic.h's
// engine/screen split so this file can be unit-tested on a desktop compiler
// with zero changes (see native_test/mancala_playtest.cpp). Ported from
// GameSuite's own KMP implementation
// (shared/src/commonMain/kotlin/com/gamesuite/games/mancala/MancalaGame.kt)
// -- same board layout, same sow/capture/extra-turn/round-end rules, same
// EASY/MEDIUM/HARD tiers -- adapted to this project's fixed "HUMAN vs AI"
// two-side convention (see CheckersLogic.h's own header comment) rather
// than GameSuite's newer generalized multi-player architecture. Unlike
// every other game in this arcade, Mancala ships WITH real difficulty tiers
// from day one (see Difficulty.h) rather than a single fixed-strength AI --
// there was no existing single-tier AI here to preserve compatibility with.
//
// Board layout (14 pits, index 0-13), unchanged from the Kotlin source:
//   0-5   = human's pits (left to right from the human's own seat)
//   6     = human's store
//   7-12  = AI's pits
//   13    = AI's store
// Sow counter-clockwise (increasing index, wrapping 13 -> 0), skipping the
// opponent's store; landing the last stone in your own empty pit captures
// that pit plus the opposite pit into your own store; landing in your own
// store earns an extra turn (same side sows again); the round ends the
// instant either side's 6 pits are all empty, sweeping the OTHER side's
// remaining stones into that other side's own store.
enum class MancalaResult : uint8_t { IN_PROGRESS, HUMAN_WINS, AI_WINS, DRAW };

static const uint8_t MANCALA_PIT_COUNT = 14;
static const uint8_t MANCALA_HUMAN_STORE = 6;
static const uint8_t MANCALA_AI_STORE = 13;

// A generous fixed upper bound on how many pits any one side can ever
// choose from in a single turn (each side owns exactly 6 pits) -- sized
// this way, same reasoning as CheckersLogic.h's own CHECKERS_MAX_MOVES, so
// a move list lives on the stack with no dynamic allocation.
static const uint8_t MANCALA_MAX_LEGAL_MOVES = 6;

// A generous fixed upper bound on how many pits a single sow can ever visit
// (the pit it started from, plus one entry per stone it distributes). The
// whole board holds a fixed 48 stones (4 per pit x 12 starting pits -- see
// reset()) and stones are only ever moved or captured into a store, never
// created, so no single pit can ever hold more than that -- sized this way
// so a sow's own animation path (see lastSowPathLength()/lastSowPathPit()
// below) lives on MancalaBoard itself with no dynamic allocation.
static const uint8_t MANCALA_MAX_SOW_PATH = 50;

// Seeds the EASY tier's shuffle-free "pick a random legal pit" RNG (a small
// self-contained xorshift32 -- see .cpp; kept out of Arduino's own
// random()/randomSeed() so this file compiles unchanged against
// native_test's no-op Arduino.h stub), exactly UnoLogic.h's own
// seedUnoRandom()'s reasoning and role. Call this once from setup() with
// real entropy (e.g. esp_random()) before the first EASY-tier AI move --
// without a call, every cold boot (and every native_test run) picks
// identically from a fixed default seed, which is deliberate for
// reproducible tests but not what you want from a real board across power
// cycles.
void seedMancalaRandom(uint32_t seed);

class MancalaBoard {
public:
    void reset();

    uint8_t stonesAt(uint8_t pitIndex) const { return pits[pitIndex]; }
    bool isHumanTurn() const { return humanTurn; }
    MancalaResult result() const;

    void setDifficulty(CpuDifficulty d) { cpuDifficulty = d; }
    CpuDifficulty getDifficulty() const { return cpuDifficulty; }

    // Pure query -- never mutates the board, safe to call on every touch
    // event: true if `pitIndex` is one of the CURRENT mover's own pits and
    // currently holds at least one stone. Answers for whichever side is
    // ACTUALLY to move right now (same convention as
    // CheckersBoard::isLegalMove()), not gated to the human, since a
    // random-vs-random self-play simulation and playMoveForTest() below
    // need this to work for both sides. The .ino's tap handling needs
    // nothing else: a single tap sows immediately -- unlike Checkers/Chess,
    // there's no two-step "select then destination" flow here, since a
    // Mancala move IS its own destination.
    bool isLegalMove(uint8_t pitIndex) const;

    // Fills outPits[] (capacity MANCALA_MAX_LEGAL_MOVES) with every pit
    // index the side actually to move right now could legally sow from,
    // and returns how many were written -- a convenience for a random-move
    // test harness and for the EASY tier alike, mirroring
    // CheckersBoard::allLegalMoves()'s role.
    uint8_t legalMoves(uint8_t outPits[]) const;

    // Sows from `pitIndex` for the human side. Returns false (no state
    // change) if illegal -- caller should ignore the tap rather than
    // mutate anything, same convention as CheckersBoard::playHuman(). On a
    // sow that lands in the human's own store (extra turn), isHumanTurn()
    // stays true and the .ino should simply let the next tap sow again --
    // no different, from the .ino's point of view, than any other of the
    // human's turns.
    bool playHuman(uint8_t pitIndex);

    // Plays exactly ONE sow for the AI side, whichever pit its current
    // CpuDifficulty tier picks -- deliberately NOT the AI's whole turn the
    // way CheckersBoard::playAi() plays an entire multi-jump chain in one
    // call, since a Mancala extra turn is a fresh, independent decision
    // (which pit to sow next) rather than an inherent continuation of the
    // same physical move a capture chain is. No-op if it isn't currently
    // the AI's turn, or the round is already decided. After calling this,
    // the .ino should check isHumanTurn(): if still false (the AI landed in
    // its own store and earned another turn), schedule another playAi()
    // call after the same "thinking" delay -- exactly mirroring how a human
    // player just keeps tapping through their own extra turns.
    void playAi();

    // ---- Last-move introspection, for animation ---------------------------
    // The most recently played sow's own path (mirrors
    // MancalaState.lastSowPath's KDoc in the Kotlin source this was ported
    // from): waypoint 0 is the pit the stones were picked up FROM, and every
    // waypoint after that is a pit a stone was actually dropped into, in
    // visiting order (the opponent's store is never a waypoint, since the
    // sow loop itself skips it). A caller animating a "seed hop cascade"
    // should walk this list in order rather than redrawing the whole board
    // at once. Undefined (zeroed length) until the first
    // playHuman()/playAi() call.
    uint8_t lastSowPathLength() const { return lastSowPathLen; }
    uint8_t lastSowPathPit(uint8_t i) const { return lastSowPath[i]; }

    // True exactly when the most recently played sow ended in a capture --
    // see MancalaCapture's own KDoc in the Kotlin source. landingPit/
    // oppositePit are BOTH already zeroed on the board by the time a caller
    // sees this (the same capture branch that reports them here is what
    // zeroed them) -- these exist purely so a caller can animate the sweep
    // into the store, not to read the pits' pre-sweep contents back out.
    bool lastMoveCaptured() const { return lastCaptureHappened; }
    uint8_t lastCaptureLandingPit() const { return lastCaptureLanding; }
    uint8_t lastCaptureOppositePit() const { return lastCaptureOpposite; }
    uint8_t lastCaptureTotalSwept() const { return lastCaptureSwept; }

    // True exactly when the most recently played sow landed in the mover's
    // own store, earning them another turn -- lets a caller show a distinct
    // "extra turn!" status beat instead of just "your/AI's turn" without
    // re-deriving the rule itself (isHumanTurn() alone can't tell "still
    // your turn because you just earned another one" from "still your turn
    // because nothing has been played yet this round").
    bool lastMoveEarnedExtraTurn() const { return lastExtraTurn; }

    // ---- Test/setup hooks --------------------------------------------------
    // Mirrors CheckersLogic.h's own test-only public seam (see that file's
    // comment for why this is a normal public method rather than a
    // friend-class/#ifdef backdoor): native_test/mancala_playtest.cpp uses
    // these to build custom mid-game positions and to drive both sides
    // through one entry point for a random-vs-random self-play simulation.
    // The shipped .ino never calls any of these -- only reset()/
    // playHuman()/playAi().
    void setPitsForTest(const uint8_t values[MANCALA_PIT_COUNT]);
    void setTurnForTest(bool humanSide);
    // Plays one sow for WHICHEVER side is currently to move, under the
    // exact same rules as playHuman() -- unlike playHuman(), this does not
    // require it to be the human's turn. Used so a random-vs-random
    // simulation can drive both sides through one entry point; returns
    // false (no state change) if illegal, same convention as playHuman().
    bool playMoveForTest(uint8_t pitIndex);

private:
    uint8_t pits[MANCALA_PIT_COUNT];
    bool humanTurn = true;
    CpuDifficulty cpuDifficulty = CpuDifficulty::MEDIUM;

    uint8_t lastSowPath[MANCALA_MAX_SOW_PATH] = {0};
    uint8_t lastSowPathLen = 0;
    bool lastCaptureHappened = false;
    uint8_t lastCaptureLanding = 0, lastCaptureOpposite = 0, lastCaptureSwept = 0;
    bool lastExtraTurn = false;

    // Applies one sow from `pitIndex` for whichever side actually owns it,
    // under the exact same rules for either side -- the single place the
    // sow/capture/extra-turn/round-end logic lives, so playHuman()/
    // playAi()/playMoveForTest() can never disagree with each other about
    // what a sow does. Never validates `pitIndex` -- callers must already
    // know it's legal (see isLegalMove()).
    void applySow(bool humanSide, uint8_t pitIndex);

    // ---- AI: EASY (uniform random among legal pits) / MEDIUM (prefer a
    // pit landing exactly in the store for an extra turn, else the first
    // non-empty pit) / HARD (depth-limited minimax, alpha-beta pruned) --
    // identical tiers/logic to the Kotlin source's playBotTurn(), adapted
    // to this project's fixed AI-is-always-the-maximizer convention (see
    // CheckersBoard's own minimax, which makes the same simplification)
    // rather than the Kotlin version's generic "maximizer" parameter, since
    // this board only ever computes moves for the fixed AI side.
    uint8_t pickAiMove() const;
};
