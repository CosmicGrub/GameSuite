#pragma once
#include <Arduino.h>

// Pure game logic for UNO -- no TFT_eSPI here at all, same engine/screen
// split as GameLogic.h/.cpp. This is a PORT of GameSuite's Android UNO
// engine (UnoCard.kt / UnoState.kt / UnoRules.kt / UnoGame.kt / UnoBot.kt),
// cut down from that engine's general N-player, networked, house-ruled,
// multi-round-scored design to exactly what this board needs: 2 players
// (human vs. AI), local only, one round, classic rules. Concretely, ported
// faithfully vs. deliberately dropped:
//
// KEPT (byte-for-byte the same rule, just re-expressed in C++):
//   - The real 108-card deck (UnoDeck.standardDeck()) and its exact
//     composition -- see unoCardById()'s comment for the encoding.
//   - Legal-play checking (UnoGame.isLegalPlay / UnoBot.isLegalPlay): match
//     color, match rank (which unifies "same number" and "same symbol",
//     exactly like the Kotlin rank enum does), or any Wild.
//   - Skip / Draw Two / Wild / Wild Draw Four effects, and Reverse's real
//     2-player special case (UnoGame.kt's own `if (s.players.size==2)`
//     branch on a played Reverse): with only one opponent, Skip and Reverse
//     both just hand the turn straight back to whoever played the card.
//   - The official "playing a card you just drew is optional" rule
//     (UnoRules.forcePlayDrawnCard defaults to false) -- see
//     awaitingHumanDrawDecision().
//   - UnoBot.kt's real MEDIUM card-selection heuristic (its own comment
//     calls this "the original heuristic": prefer action cards to disrupt
//     the opponent, else shed the highest-value legal card) and its real
//     chooseColor() heuristic (the color you hold the most of).
//   - Draw-pile-exhausted reshuffles from the discard pile minus its top
//     card, same as UnoGame.kt's ensureDrawPile().
//
// DROPPED for this scope (a deliberate, pragmatic cut for an embedded
// single-round 2-player port, not an oversight):
//   - Everything about >2 players, teams, and networking (UnoNetMessage.kt
//     entirely, host/guest state sync, intents) -- this board only ever
//     plays one human against one local AI.
//   - Every togglable house rule (UnoRules: stackDraw,
//     stackDrawFourOnDrawTwo, sevenZero, jumpIn, teamPlay) -- all off,
//     which is exactly UnoRules()'s own all-false "classic mode" default.
//   - The official Wild Draw Four "Challenge" bluff sub-flow
//     (UnoGame.resolveChallenge) -- playing a Wild Draw Four here always
//     just makes the opponent draw 4 and lose their turn, no
//     accept-or-challenge prompt. This is genuinely how most people play
//     UNO casually, and it keeps the touchscreen interaction shape simple
//     (no extra "accept / challenge" overlay to design and hit-test).
//   - The "UNO!" call-and-catch penalty (UnoGame.callUno/catchUnoFailure)
//     -- pure flavor/penalty, doesn't affect legality or who wins.
//   - Cumulative match scoring / playing to 500 points across many rounds
//     (UnoGame.checkWinAfterPlay's scoring, startNextRound) -- single
//     round, first to empty their hand wins, same shape as this project's
//     existing Tic-Tac-Toe "Play Again" pattern.
//   - UnoBot's EASY (random) and HARD (reserve Wild+4, target the weakest
//     opponent) difficulty tiers -- there's no difficulty selector here,
//     just one fixed AI, matching TicTacToeBoard's own single fixed
//     (minimax) opponent.
//
// TWO SMALL DELIBERATE DEVIATIONS from what the Kotlin source literally
// does, both explained in UnoLogic.cpp next to where they're implemented:
// applyOpeningEffect() treats an opening Reverse exactly like an opening
// Skip (for consistency with the mid-round 2-player rule above, since the
// Kotlin source's own opening-flip handler doesn't special-case player
// count the way its mid-round handler does), and a round always ends
// immediately when a hand empties -- even via a Wild -- rather than
// pausing for a color choice nobody would ever be prompted to make.

enum class UnoColor : uint8_t { RED, YELLOW, GREEN, BLUE, WILD };
enum class UnoRank : uint8_t {
    ZERO, ONE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE,
    SKIP, REVERSE, DRAW_TWO,
    WILD, WILD_DRAW_FOUR
};

// One card the way GameLogic/Display need to see it: color + rank, plus a
// stable per-card instanceId (0-107) that disambiguates the deck's
// duplicate color+rank pairs -- same role as UnoCard.kt's own instanceId.
struct UnoCardView {
    UnoColor color;
    UnoRank rank;
    uint8_t instanceId;

    bool isWild() const { return rank == UnoRank::WILD || rank == UnoRank::WILD_DRAW_FOUR; }
    bool isNumber() const { return static_cast<uint8_t>(rank) <= static_cast<uint8_t>(UnoRank::NINE); }
};

// Maps a deck instanceId (0-107) to the card it names -- a pure arithmetic
// encoding of UnoDeck.standardDeck()'s exact construction order, so no
// 108-entry table needs to be stored anywhere: ids 0-99 are the 4 colors
// (RED,YELLOW,GREEN,BLUE -- same order as the enum, 25 cards per color: one
// ZERO, then ONE..NINE/SKIP/REVERSE/DRAW_TWO each appearing twice
// consecutively, exactly the order UnoDeck.kt builds them in), ids
// 100-103 are the 4 Wilds, ids 104-107 are the 4 Wild Draw Fours. Exposed
// publicly (not just used internally by UnoRound) so native_test can
// exhaustively verify the deck's composition/uniqueness on its own.
UnoCardView unoCardById(uint8_t instanceId);

enum class UnoRoundResult : uint8_t { IN_PROGRESS, HUMAN_WINS, AI_WINS };

static const uint8_t UNO_DECK_SIZE = 108;
static const uint8_t UNO_MAX_HAND = 108; // a hand can never hold more than every card in play

// Seeds the shuffle RNG (a small self-contained xorshift32 -- see .cpp; kept
// out of Arduino's own random()/randomSeed() so this file compiles unchanged
// against native_test's no-op Arduino.h stub, same reasoning as GameLogic.h
// only ever using Arduino.h for its integer types). Call this once from
// setup() with real entropy (e.g. analogRead() on a floating pin, or
// esp_random()) before the first UnoRound::reset() -- without a call, every
// cold boot (and every native_test run) shuffles identically from a fixed
// default seed, which is deliberate for reproducible tests but not what you
// want from a real board across power cycles.
void seedUnoRandom(uint32_t seed);

class UnoRound {
public:
    // Deals a fresh round: shuffles a new 108-card deck, deals 7 cards to
    // each side, flips the first discard (re-shuffling away a Wild Draw
    // Four flip per the official rule), and applies that opener's own
    // effect. Human always goes first unless the opener skips them.
    void reset();

    UnoRoundResult result() const { return result_; }

    // Whose turn it is to act next -- see the *ByHuman naming below for
    // exactly what "act" can mean while an overlay is pending.
    bool isHumanTurn() const { return humanTurn; }

    // True right after a Wild/Wild Draw Four was legally played by whoever
    // acts next (per isHumanTurn()) and before a color has been chosen for
    // it -- Display should show the color-choice overlay while this is
    // true. Only ever observed for the human: playAiTurn() always resolves
    // its own wild's color choice internally before returning.
    bool awaitingColorChoice() const { return awaitingColorChoice_; }

    // True right after the human drew a card that turned out to be legally
    // playable -- official UNO makes playing it their OPTION, not
    // mandatory (UnoRules.forcePlayDrawnCard defaults false). While this is
    // true, both playHumanCard() (naming the drawn card's now-last hand
    // index) and keepHumanDrawnCard() are valid next calls. The AI always
    // plays a still-playable card it just drew, so this is never observed
    // for the AI's side.
    bool awaitingHumanDrawDecision() const { return awaitingDrawDecision_ && humanTurn; }

    // ---- Human actions. Each returns false and changes nothing on an
    // illegal call (wrong turn, bad index, an overlay pending that must be
    // resolved first, round already over) -- same "ignore the tap" contract
    // as TicTacToeBoard::playHuman(). ----

    // Plays the card at humanHandCard(handIndex). If it's a Wild, this only
    // moves it to the discard pile and raises awaitingColorChoice() --
    // follow up with chooseHumanColor() to actually finish the turn.
    bool playHumanCard(uint8_t handIndex);

    // Draws one card into the human's hand (reshuffling the discard pile
    // into the draw pile first if needed). If the drawn card turns out to
    // be playable, the turn stays with the human and
    // awaitingHumanDrawDecision() becomes true; otherwise the turn passes
    // to the AI immediately.
    bool drawHumanCard();

    // Ends the human's turn without playing the card just drawn -- only
    // valid while awaitingHumanDrawDecision() is true.
    bool keepHumanDrawnCard();

    // Resolves a pending awaitingColorChoice() raised by the human's own
    // Wild play. `color` must be one of RED/YELLOW/GREEN/BLUE.
    bool chooseHumanColor(UnoColor color);

    // Plays the AI's entire turn in one call: one card (its own color
    // choice, if any, resolved internally, exactly like UnoBot.kt's
    // heuristic feeding straight into UnoGame.chooseColor()), or a draw
    // (playing the drawn card immediately if it turns out to be legal,
    // mirroring UnoGame.playBotTurn()'s own self-recursion for that case).
    // No-op if it isn't currently the AI's turn or the round is over.
    void playAiTurn();

    // ---- Read-only views for Display ----
    uint8_t humanHandCount() const { return humanCount_; }
    UnoCardView humanHandCard(uint8_t index) const { return unoCardById(humanHand_[index]); }
    // The AI's hand contents are never exposed on purpose -- Display can
    // only ever draw its card COUNT (face-down), never what's in it.
    uint8_t aiHandCount() const { return aiCount_; }
    UnoCardView topDiscard() const { return unoCardById(discardPile_[discardCount_ - 1]); }
    // The color actually in effect -- differs from topDiscard().color right
    // after a Wild (which keeps reporting WILD/neutral) until/unless you
    // read this instead, same split UnoState.currentColor keeps in Kotlin.
    UnoColor currentColor() const { return currentColor_; }
    uint8_t drawPileCount() const { return drawCount_; }
    uint8_t discardPileCount() const { return discardCount_; }

    // Diagnostic-only: true iff every one of the 108 possible instanceIds
    // appears EXACTLY once across the draw pile, discard pile, and both
    // hands combined right now -- i.e. nothing has been duplicated or lost.
    // Cheap (one 108-bit sweep) and safe to call any time. Exposed publicly
    // -- rather than kept private -- specifically so a test harness can
    // verify this end-to-end property without any other way to see hidden
    // hand contents (the AI's hand and the draw pile's contents are
    // otherwise never exposed at all -- see aiHandCount()'s comment).
    // native_test's random self-play simulation asserts this after every
    // single turn.
    bool debugCardsConserved() const;

private:
    uint8_t drawPile_[UNO_DECK_SIZE];
    uint8_t drawCount_ = 0;
    uint8_t discardPile_[UNO_DECK_SIZE];
    uint8_t discardCount_ = 0;
    uint8_t humanHand_[UNO_MAX_HAND];
    uint8_t humanCount_ = 0;
    uint8_t aiHand_[UNO_MAX_HAND];
    uint8_t aiCount_ = 0;

    bool humanTurn = true;
    UnoColor currentColor_ = UnoColor::RED;
    UnoRoundResult result_ = UnoRoundResult::IN_PROGRESS;
    bool awaitingColorChoice_ = false;
    bool awaitingDrawDecision_ = false;
    bool pendingWildDrawFour_ = false; // only meaningful while awaitingColorChoice_ is true

    void shuffleDrawPile();
    void reshuffleFromDiscard();
    // Draws up to n cards into *hand (reshuffling as needed); returns how
    // many were actually drawn, which can be less than n (even 0) only in
    // the pathological case where every other card is jammed into both
    // hands and the discard pile has nothing left to reshuffle beyond its
    // required top card -- see UnoLogic.cpp. Never crashes or loses count.
    uint8_t drawInto(uint8_t *hand, uint8_t &handCount, uint8_t n);

    void applyOpeningEffect(UnoRank rank);
    // Resolves everything about a just-played, just-removed-from-hand card
    // except a Wild's color choice (which only sets awaitingColorChoice_/
    // pendingWildDrawFour_ and returns, for the caller to finish via
    // resolveColorChoice() once a color is known). `byHuman` says who just
    // played it, i.e. whose hand is NOT the one that might have to draw.
    void applyPlayedCardEffect(UnoRank rank, UnoColor color, bool byHuman);
    void resolveColorChoice(UnoColor color);

    // AI heuristics (ported from UnoBot.kt) -- see .cpp.
    int chooseAiCardIndex() const; // -1 if the AI has no legal card
    void playAiCardAt(uint8_t idx);
};
