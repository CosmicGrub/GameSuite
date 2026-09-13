#pragma once
#include <Arduino.h>
#include "Difficulty.h"

// Pure game logic for Dominoes -- no display/touch code here at all,
// mirroring GameLogic.h/CheckersLogic.h/MancalaLogic.h's engine/screen
// split so this file can be unit-tested on a desktop compiler with zero
// changes (see native_test/dominoes_playtest.cpp). Ported from GameSuite's
// existing Android engine
// (app/src/main/java/com/gamesuite/games/dominoes/DominoGame.kt), cut down
// from that engine's general 2-4-player, session-scored design to exactly
// what this board needs -- 2 players (human vs. AI), local only, one hand
// at a time -- the same kind of deliberate, documented scope cut
// UnoLogic.h's own top comment makes for UNO. Concretely, kept vs. dropped:
//
// KEPT (byte-for-byte the same rule, just re-expressed in C++):
//   - Standard double-six set (28 tiles, 0-6 pips per end), 7-tile hands,
//     14 tiles left in the boneyard for a 2-player game.
//   - The real match/attach/auto-flip rule (DominoGame.canPlace/wouldFlip)
//     and the linear (non-branching -- no "spinner" doubles) chain layout.
//   - Draw-if-you-can't-play, pass-only-once-the-boneyard-is-empty-and-you-
//     still-can't-play, and "blocked" (both sides pass in a row) ending in
//     whoever holds fewer pips winning -- a genuine tie is a real DRAW.
//   - The opening-player rule (highest double, or highest single tile if
//     nobody has a double) and all three EASY/MEDIUM/HARD tiers verbatim
//     (see DominoGame.kt's chooseOpeningPlay/chooseBotPlay KDoc).
// DROPPED for this scope (a deliberate, pragmatic cut, not an oversight):
//   - 3-4 player / team play -- this board only ever plays one human
//     against one local AI, same as every other game here.
//   - Cross-hand pip scoring (DominoGame.sessionScores/awardHandPoints) --
//     this project's other ports (Checkers/Chess/UNO) don't carry a
//     persistent scoreboard across "Play Again" either; each hand is its
//     own self-contained result, exactly like theirs.
//
// Board/chain model: the chain is a single ordered list of placed tiles,
// left-to-right exactly as a physical board reads -- index 0 is the
// leftmost tile, the last index is the rightmost. A tile's own `flipped`
// bit says whether it reads b-then-a (true) or a-then-b (false) at that
// position; see leftEndValue()/rightEndValue() for how the two exposed
// ends are derived from it. The AI's own hand contents are never exposed
// (only its tile COUNT) -- same hidden-hand convention as UnoRound's
// aiHandCount()/humanHandCard() split.

// One physical tile: two pip values (0-6 each) plus a stable per-tile
// instanceId (0-27) that disambiguates the set's duplicate-looking tiles
// (there's only one 3-3, but the id still matters for hand/chain
// bookkeeping) -- same role as UnoCardView's own instanceId.
struct DominoTileView {
    uint8_t a, b, instanceId;
    bool isDouble() const { return a == b; }
    uint8_t pipTotal() const { return (uint8_t)(a + b); }
};

// Maps a tile instanceId (0-27) to the tile it names -- a small fixed
// table (not worth arithmetic-encoding the way UnoLogic.h's 108-card
// unoCardById() does; 28 entries is nothing) built in the exact same
// (a, then b from a..6) order DominoGame.kt's startMatch() enumerates the
// set in, so an id here always names the same tile the Kotlin source
// would generate at that position.
DominoTileView dominoTileById(uint8_t instanceId);

enum class DominoesResult : uint8_t { IN_PROGRESS, HUMAN_WINS, AI_WINS, DRAW };

static const uint8_t DOMINOES_SET_SIZE = 28;   // 0-6 double-six set: 7+6+5+4+3+2+1
static const uint8_t DOMINOES_HAND_SIZE = 7;   // 2-player deal, per DominoGame.kt's own `if (players.size <= 2) 7`
static const uint8_t DOMINOES_MAX_HAND = 28;   // a hand can never hold more than every tile in play (mirrors UNO_MAX_HAND)
static const uint8_t DOMINOES_MAX_CHAIN = 28;  // the chain can never hold more than every tile in play

// One legal (handIndex, attachToLeft) pair for whichever side is CURRENTLY
// to move -- see legalPlays() below. A tile that matches BOTH of the
// chain's exposed ends appears TWICE (once per end), same duplication
// DominoGame.kt's own `legalLeft + legalRight` list has -- deliberately
// preserved rather than deduplicated, since MEDIUM's "try the left end
// first" tier depends on that exact ordering.
struct DominoesLegalPlay {
    uint8_t handIndex;
    bool attachToLeft;
};
static const uint8_t DOMINOES_MAX_LEGAL_PLAYS = DOMINOES_MAX_HAND * 2;

// Seeds the EASY tier's "pick a random legal tile" RNG (a small self-
// contained xorshift32 -- see .cpp; kept out of Arduino's own
// random()/randomSeed() so this file compiles unchanged against
// native_test's no-op Arduino.h stub), exactly UnoLogic.h's/MancalaLogic.h's
// own seed*Random()'s reasoning and role. Call this once from setup() with
// real entropy (e.g. esp_random()) before the first real hand is dealt.
void seedDominoesRandom(uint32_t seed);

class DominoesBoard {
public:
    // Shuffles a fresh 28-tile set, deals 7 tiles to each side, and picks
    // the opening player (highest double, ties/no-doubles broken by
    // highest single tile, ties there favoring the human -- see .cpp).
    void reset();

    DominoesResult result() const { return result_; }
    bool isHumanTurn() const { return humanTurn; }

    void setDifficulty(CpuDifficulty d) { cpuDifficulty = d; }
    CpuDifficulty getDifficulty() const { return cpuDifficulty; }

    uint8_t humanHandCount() const { return humanCount; }
    DominoTileView humanHandTile(uint8_t index) const { return dominoTileById(humanHand[index]); }
    // The AI's hand contents are never exposed on purpose -- Display can
    // only ever draw its tile COUNT (face-down), never what's in it, same
    // convention as UnoRound::aiHandCount().
    uint8_t aiHandCount() const { return aiCount; }

    uint8_t boneyardCount() const { return boneyardCount_; }

    uint8_t chainLength() const { return chainCount; }
    DominoTileView chainTileAt(uint8_t index) const { return dominoTileById(chainInstanceId[index]); }
    bool chainTileFlippedAt(uint8_t index) const { return chainFlipped[index]; }
    bool chainEmpty() const { return chainCount == 0; }
    // Undefined (not called) when chainEmpty() -- an empty chain has no
    // "ends" yet, matching DominoState.leftEnd/rightEnd both being null in
    // the Kotlin source at that point.
    uint8_t leftEndValue() const;
    uint8_t rightEndValue() const;

    // Pure queries -- never mutate the board, safe to call on every touch
    // event. Mirrors DominoGame.canPlace()'s own pure, tile-value-only
    // signature exactly (not tied to whose hand the tile is in), so a
    // caller can ask "would MY selected tile fit here?" without needing to
    // know or care which side actually holds it.
    bool canAttachLeft(DominoTileView tile) const;
    bool canAttachRight(DominoTileView tile) const;

    // True if the tile at `handIndex` in the given side's own hand could
    // legally attach to EITHER end right now -- a convenience for Display
    // to dim/highlight which hand tiles are currently playable (dominoes'
    // matching rule isn't always obvious at a glance, unlike Checkers'
    // destination highlighting, which this mirrors the spirit of).
    bool humanHandTileIsPlayable(uint8_t handIndex) const;

    // True if whichever side is CURRENTLY to move has at least one legal
    // play anywhere in their hand right now -- mirrors DominoGame.canPlay()
    // exactly (answers for whoever's actually to move, not gated to the
    // human), used both to decide whether drawing/passing is even legal and
    // by Display to choose "your turn" vs. "you must draw"/"you must pass"
    // status text.
    bool currentSideHasLegalPlay() const;

    // Fills outPlays[] (capacity DOMINOES_MAX_LEGAL_PLAYS) with every legal
    // (handIndex, attachToLeft) pair for whichever side is CURRENTLY to
    // move, and returns how many were written -- mirrors
    // CheckersBoard::allLegalMoves()/MancalaBoard::legalMoves()'s role: a
    // convenience for a random-move test harness and for the AI's own
    // move selection (see .cpp), which goes through this SAME list rather
    // than a separate parallel scan, so the two can never disagree about
    // what's legal.
    uint8_t legalPlays(DominoesLegalPlay outPlays[]) const;

    // Plays the human's own hand tile at `handIndex`, attaching it to the
    // left end (attachToLeft=true) or right end (false) of the chain --
    // ignored (attachToLeft is irrelevant either way) when the chain is
    // still empty, exactly like DominoGame.playDomino()'s own
    // `if (s.chain.isEmpty())` branch. Returns false (no state change) if
    // illegal -- caller should ignore the tap rather than mutate anything,
    // same convention as every other game's playHuman()-style method here.
    bool playHumanTile(uint8_t handIndex, bool attachToLeft);

    // Draws one tile from the boneyard into the human's hand. Illegal (and
    // a no-op) unless it's genuinely the human's turn, the boneyard has a
    // tile left, AND the human has no legal play already in hand -- exact
    // same guard as DominoGame.drawFromBoneyard()'s "must play a legal
    // tile instead of drawing" rule. The turn does NOT pass -- a human who
    // draws an unplayable tile keeps drawing (tap again) or, once the
    // boneyard empties, passes instead; this mirrors the real Kotlin
    // source, where only the AI's own playAi()-equivalent auto-continues
    // through several draws in a row on its own.
    bool drawHumanTile();

    // Passes the human's turn. Illegal (and a no-op) unless the boneyard is
    // empty AND the human genuinely has no legal play -- same guard as
    // DominoGame.pass(). Ends the hand (see result()) once both sides have
    // passed in a row.
    bool passHuman();

    // Plays the AI's ENTIRE turn in one call -- unlike Mancala's
    // deliberately one-sow-per-call playAi() (where each extra turn is a
    // fresh, independent decision), a Dominoes turn's "draw again if you
    // still can't play" loop is all ONE continuous turn from an outside
    // caller's perspective, exactly the way UnoRound::playAiTurn() resolves
    // its own single extra draw-then-maybe-play step internally -- just
    // generalized here to loop until either a legal tile is found or the
    // boneyard runs out (mirroring DominoGame.playBotTurn()'s own
    // self-recursion for exactly this case, implemented as a plain loop
    // instead of real recursion). No-op if it isn't currently the AI's
    // turn, or the hand is already decided.
    void playAi();

    // ---- Test/setup hooks --------------------------------------------------
    // Mirrors CheckersLogic.h's/MancalaLogic.h's own test-only public seam
    // (see CheckersLogic.h's comment for why this is a normal public method
    // rather than a friend-class/#ifdef backdoor): native_test/
    // dominoes_playtest.cpp uses these to build custom mid-hand positions
    // and to drive both sides through one entry point for a random-vs-
    // random self-play simulation. The shipped .ino never calls any of
    // these -- only reset()/playHumanTile()/drawHumanTile()/passHuman()/
    // playAi().
    void setHandsForTest(const uint8_t humanIds[], uint8_t humanN, const uint8_t aiIds[], uint8_t aiN);
    void setChainForTest(const uint8_t instanceIds[], const bool flipped[], uint8_t n);
    void setBoneyardForTest(const uint8_t ids[], uint8_t n);
    void setTurnForTest(bool humanSide);
    // The AI's own hand contents, otherwise never exposed (see
    // aiHandCount()'s comment) -- exists purely so native_test can verify
    // the EASY/MEDIUM/HARD tiers actually choose the tile the rules say
    // they should (e.g. MEDIUM's "left end tried before right" ordering),
    // the same reasoning CheckersLogic.h's own test-only seam gives. The
    // shipped .ino has no reason to call this.
    DominoTileView aiHandTileForTest(uint8_t index) const { return dominoTileById(aiHand[index]); }
    // Plays one tile for WHICHEVER side is currently to move, drawing/
    // passing for whichever side is currently to move under the exact same
    // rules as the human-only methods above -- used so a random-vs-random
    // simulation can drive both sides through one entry point each. Return
    // convention matches playHumanTile()/drawHumanTile()/passHuman().
    bool playMoveForTest(uint8_t handIndex, bool attachToLeft);
    bool drawForTest();
    bool passForTest();

private:
    uint8_t humanHand[DOMINOES_MAX_HAND];
    uint8_t humanCount = 0;
    uint8_t aiHand[DOMINOES_MAX_HAND];
    uint8_t aiCount = 0;
    uint8_t boneyard_[DOMINOES_SET_SIZE];
    uint8_t boneyardCount_ = 0;

    uint8_t chainInstanceId[DOMINOES_MAX_CHAIN];
    bool chainFlipped[DOMINOES_MAX_CHAIN] = {false};
    uint8_t chainCount = 0;

    bool humanTurn = true;
    uint8_t consecutivePasses = 0;
    DominoesResult result_ = DominoesResult::IN_PROGRESS;
    CpuDifficulty cpuDifficulty = CpuDifficulty::MEDIUM;

    void shuffleBoneyard();
    // Draws up to n tiles into *hand (bounded by what's left in the
    // boneyard); returns how many were actually drawn. No reshuffle
    // concept here at all (unlike UnoRound::drawInto()'s discard-pile
    // recycling) -- dominoes are never "discarded," so once the boneyard
    // empties it just stays empty, matching DominoGame.draw()'s own
    // `minOf(count, boneyard.size)`.
    uint8_t drawInto(uint8_t *hand, uint8_t &handCount, uint8_t n);

    bool sideHasLegalPlay(bool humanSide) const;
    bool isPlayableFromHand(bool humanSide, uint8_t handIndex) const;

    // Applies one already-verified-legal play: updates the chain (inserting
    // at the front for a left attach, appending for a right attach, with
    // the correct flip -- see .cpp), removes the tile from `hand`, resets
    // consecutivePasses, and either ends the hand (byHuman decides who just
    // won) or passes the turn. Never validates the play itself -- callers
    // (playHumanTile/playAi/playMoveForTest) already checked
    // canAttachLeft()/canAttachRight() first, same "never re-validates"
    // convention as CheckersBoard::applyMove().
    void applyPlay(uint8_t *hand, uint8_t &handCount, uint8_t handIndex, bool attachToLeft, bool byHuman);
    void finishBlocked();
    uint16_t handPipTotal(const uint8_t *hand, uint8_t count) const;

    // AI tiers -- EASY (uniform random) / MEDIUM (highest-value opener,
    // else first legal option -- left end tried before right) / HARD
    // (heaviest tile first, doubles weighted extra) -- identical logic to
    // DominoGame.kt's chooseOpeningPlay()/chooseBotPlay(), just adapted to
    // this project's fixed AI-hand convention.
    int chooseOpeningIndex() const; // index into aiHand, or -1 if aiCount == 0 (defensive; never actually happens)
    int chooseBotPlayIndex(const DominoesLegalPlay options[], uint8_t n) const; // index into `options`, or -1 if n == 0
};
