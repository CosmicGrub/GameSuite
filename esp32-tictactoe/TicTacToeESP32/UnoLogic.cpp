#include "UnoLogic.h"

// ---------------------------------------------------------------------------
// Shuffle RNG -- a small self-contained xorshift32, deliberately NOT
// Arduino's own random()/randomSeed() so this file compiles unchanged
// against native_test's no-op Arduino.h stub (see UnoLogic.h's top comment).
// Fixed default seed so both native_test and a board that never calls
// seedUnoRandom() behave deterministically/reproducibly.
// ---------------------------------------------------------------------------
static uint32_t g_unoRngState = 0x9E3779B9u;

void seedUnoRandom(uint32_t seed) {
    g_unoRngState = seed ? seed : 0x9E3779B9u; // xorshift32 must never be seeded with 0
}

static uint32_t unoNextRandom() {
    uint32_t x = g_unoRngState;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    g_unoRngState = x;
    return x;
}

// Fisher-Yates over arr[0..n).
static void shuffleArray(uint8_t *arr, uint8_t n) {
    for (uint8_t i = n; i > 1; i--) {
        uint8_t j = unoNextRandom() % i;
        uint8_t tmp = arr[i - 1];
        arr[i - 1] = arr[j];
        arr[j] = tmp;
    }
}

static void removeAt(uint8_t *arr, uint8_t &count, uint8_t idx) {
    for (uint8_t i = idx; i + 1 < count; i++) arr[i] = arr[i + 1];
    count--;
}

// ---------------------------------------------------------------------------
// Deck encoding -- see UnoLogic.h's declaration for the layout this mirrors
// (UnoDeck.standardDeck()'s exact construction order).
// ---------------------------------------------------------------------------
UnoCardView unoCardById(uint8_t id) {
    if (id >= 104) return { UnoColor::WILD, UnoRank::WILD_DRAW_FOUR, id }; // 104-107
    if (id >= 100) return { UnoColor::WILD, UnoRank::WILD, id };           // 100-103

    uint8_t colorBlock = id / 25; // 0..3 -> RED,YELLOW,GREEN,BLUE (same order as the enum)
    uint8_t within = id % 25;     // 0..24
    UnoColor color = static_cast<UnoColor>(colorBlock);

    if (within == 0) return { color, UnoRank::ZERO, id };

    static const UnoRank SEQ[12] = {
        UnoRank::ONE, UnoRank::TWO, UnoRank::THREE, UnoRank::FOUR, UnoRank::FIVE,
        UnoRank::SIX, UnoRank::SEVEN, UnoRank::EIGHT, UnoRank::NINE,
        UnoRank::SKIP, UnoRank::REVERSE, UnoRank::DRAW_TWO
    };
    uint8_t rankIdx = (within - 1) / 2; // each of the 12 ranks above appears twice consecutively
    return { color, SEQ[rankIdx], id };
}

static bool isLegalPlay(const UnoCardView &card, UnoColor currentColor, const UnoCardView &top) {
    if (card.isWild()) return true;
    if (card.color == currentColor) return true;
    if (card.rank == top.rank) return true;
    return false;
}

// AI scoring heuristic, ported from UnoCard.kt's scoreValue: numbers score
// their own face value, Skip/Reverse/Draw Two score 20, either Wild scores
// 50 -- UnoBot.kt's MEDIUM tier picks the highest of these among its
// preferred pool (see chooseAiCardIndex()).
static uint8_t scoreValue(UnoRank rank) {
    if (rank <= UnoRank::NINE) return static_cast<uint8_t>(rank);
    if (rank == UnoRank::WILD || rank == UnoRank::WILD_DRAW_FOUR) return 50;
    return 20; // SKIP, REVERSE, DRAW_TWO
}

// ---------------------------------------------------------------------------
// UnoRound
// ---------------------------------------------------------------------------

void UnoRound::reset() {
    for (uint8_t i = 0; i < UNO_DECK_SIZE; i++) drawPile_[i] = i;
    drawCount_ = UNO_DECK_SIZE;
    shuffleDrawPile();

    discardCount_ = 0;
    humanCount_ = 0;
    aiCount_ = 0;
    drawInto(humanHand_, humanCount_, 7);
    drawInto(aiHand_, aiCount_, 7);

    result_ = UnoRoundResult::IN_PROGRESS;
    awaitingColorChoice_ = false;
    awaitingDrawDecision_ = false;
    pendingWildDrawFour_ = false;
    humanTurn = true; // human is always "player 0" -- matches ArcadeOS's human-goes-first convention

    // Flip the first discard, redrawing away a Wild Draw Four flip (official rule).
    uint8_t firstId = drawPile_[--drawCount_];
    while (unoCardById(firstId).rank == UnoRank::WILD_DRAW_FOUR) {
        drawPile_[drawCount_++] = firstId;
        shuffleDrawPile();
        firstId = drawPile_[--drawCount_];
    }
    discardPile_[discardCount_++] = firstId;

    UnoCardView first = unoCardById(firstId);
    // A plain Wild opener has no player to prompt for a color (nobody
    // "played" it) -- default straight to RED and start play normally,
    // rather than reproducing what looks like a gap in the Kotlin source
    // (dealNewRound() sets awaitingColorChoice=true for this case, but
    // applyOpeningEffect() has no branch that ever resolves it -- see
    // UnoLogic.h's top comment).
    currentColor_ = first.isWild() ? UnoColor::RED : first.color;

    applyOpeningEffect(first.rank);
}

void UnoRound::applyOpeningEffect(UnoRank rank) {
    switch (rank) {
        case UnoRank::SKIP:
        case UnoRank::REVERSE:
            // 2-player table: an opening Skip skips the first player per the
            // official rule ("the player to the dealer's left is skipped");
            // we apply the same treatment to an opening Reverse for
            // consistency with the mid-round 2-player Reverse-as-Skip rule
            // (see applyPlayedCardEffect()) rather than the Kotlin source's
            // opening-flip handler, which only special-cases Skip/Draw Two
            // and leaves an opening Reverse's currentPlayerIndex untouched.
            humanTurn = false;
            break;
        case UnoRank::DRAW_TWO:
            // Official rule: the first player must draw two and forfeit their turn.
            drawInto(humanHand_, humanCount_, 2);
            humanTurn = false;
            break;
        default:
            break; // number card, or a Wild (color already defaulted above): human goes first normally
    }
}

bool UnoRound::playHumanCard(uint8_t handIndex) {
    if (result_ != UnoRoundResult::IN_PROGRESS) return false;
    if (!humanTurn || awaitingColorChoice_) return false;
    if (handIndex >= humanCount_) return false;

    UnoCardView card = unoCardById(humanHand_[handIndex]);
    if (!isLegalPlay(card, currentColor_, topDiscard())) return false;

    uint8_t instId = humanHand_[handIndex];
    removeAt(humanHand_, humanCount_, handIndex);
    discardPile_[discardCount_++] = instId;
    awaitingDrawDecision_ = false;

    if (humanCount_ == 0) {
        // A winning play is immediate and final even if it's a Wild -- the
        // round doesn't pause for a color choice nobody would ever see
        // (see UnoLogic.h's top comment on this deviation from the Kotlin
        // source's own edge-case behavior here).
        result_ = UnoRoundResult::HUMAN_WINS;
        return true;
    }

    applyPlayedCardEffect(card.rank, card.color, /*byHuman=*/true);
    return true;
}

bool UnoRound::drawHumanCard() {
    if (result_ != UnoRoundResult::IN_PROGRESS) return false;
    if (!humanTurn || awaitingColorChoice_ || awaitingDrawDecision_) return false;

    uint8_t drawn = drawInto(humanHand_, humanCount_, 1);
    if (drawn == 0) {
        // Nothing left anywhere to draw (see drawInto()'s own comment) --
        // the turn simply passes with nothing gained.
        humanTurn = false;
        return true;
    }

    UnoCardView newCard = unoCardById(humanHand_[humanCount_ - 1]);
    bool stillLegal = isLegalPlay(newCard, currentColor_, topDiscard());
    awaitingDrawDecision_ = stillLegal;
    if (!stillLegal) humanTurn = false;
    return true;
}

bool UnoRound::keepHumanDrawnCard() {
    if (result_ != UnoRoundResult::IN_PROGRESS) return false;
    if (!humanTurn || !awaitingDrawDecision_) return false;
    awaitingDrawDecision_ = false;
    humanTurn = false;
    return true;
}

bool UnoRound::chooseHumanColor(UnoColor color) {
    if (result_ != UnoRoundResult::IN_PROGRESS) return false;
    if (!humanTurn || !awaitingColorChoice_) return false;
    if (color == UnoColor::WILD) return false; // must choose one of the 4 real colors
    resolveColorChoice(color);
    return true;
}

void UnoRound::playAiTurn() {
    if (result_ != UnoRoundResult::IN_PROGRESS) return;
    if (humanTurn) return;
    if (awaitingColorChoice_) return; // defensive -- the AI always resolves its own before returning

    int idx = chooseAiCardIndex();
    if (idx >= 0) {
        playAiCardAt(static_cast<uint8_t>(idx));
        return;
    }

    // No legal card -- draw one, and play it immediately if it turns out to
    // be legal (mirrors UnoGame.playBotTurn()'s own self-recursion for
    // exactly this case).
    uint8_t drawn = drawInto(aiHand_, aiCount_, 1);
    if (drawn == 0) {
        humanTurn = true; // nothing left anywhere to draw -- pass
        return;
    }
    UnoCardView newCard = unoCardById(aiHand_[aiCount_ - 1]);
    if (isLegalPlay(newCard, currentColor_, topDiscard())) {
        playAiCardAt(aiCount_ - 1);
    } else {
        humanTurn = true;
    }
}

void UnoRound::playAiCardAt(uint8_t idx) {
    UnoCardView card = unoCardById(aiHand_[idx]);
    uint8_t instId = aiHand_[idx];
    removeAt(aiHand_, aiCount_, idx);
    discardPile_[discardCount_++] = instId;

    if (aiCount_ == 0) {
        result_ = UnoRoundResult::AI_WINS;
        return;
    }

    applyPlayedCardEffect(card.rank, card.color, /*byHuman=*/false);
    if (awaitingColorChoice_) {
        // UnoBot.chooseColor(): the color the AI holds the most of (ties
        // broken RED,YELLOW,GREEN,BLUE; falls back to RED if the AI's
        // remaining hand is all Wilds, same as the Kotlin fallback).
        uint16_t counts[4] = { 0, 0, 0, 0 };
        for (uint8_t i = 0; i < aiCount_; i++) {
            UnoCardView c = unoCardById(aiHand_[i]);
            if (c.color != UnoColor::WILD) counts[static_cast<uint8_t>(c.color)]++;
        }
        uint8_t best = 0;
        for (uint8_t c = 1; c < 4; c++) if (counts[c] > counts[best]) best = c;
        resolveColorChoice(static_cast<UnoColor>(best));
    }
}

int UnoRound::chooseAiCardIndex() const {
    uint8_t legal[UNO_MAX_HAND];
    uint8_t legalN = 0;
    for (uint8_t i = 0; i < aiCount_; i++) {
        if (isLegalPlay(unoCardById(aiHand_[i]), currentColor_, topDiscard())) legal[legalN++] = i;
    }
    if (legalN == 0) return -1;

    // UnoBot.mediumChoice(): prefer action (non-number) cards; if none of
    // the legal cards are action cards, fall back to every legal card.
    uint8_t pool[UNO_MAX_HAND];
    uint8_t poolN = 0;
    for (uint8_t i = 0; i < legalN; i++) {
        if (!unoCardById(aiHand_[legal[i]]).isNumber()) pool[poolN++] = legal[i];
    }
    if (poolN == 0) {
        for (uint8_t i = 0; i < legalN; i++) pool[i] = legal[i];
        poolN = legalN;
    }

    // Highest scoreValue in the pool; first occurrence wins a tie (matches
    // Kotlin's maxByOrNull, which keeps the first max element it sees).
    int best = pool[0];
    uint8_t bestScore = scoreValue(unoCardById(aiHand_[pool[0]]).rank);
    for (uint8_t i = 1; i < poolN; i++) {
        uint8_t s = scoreValue(unoCardById(aiHand_[pool[i]]).rank);
        if (s > bestScore) {
            bestScore = s;
            best = pool[i];
        }
    }
    return best;
}

void UnoRound::applyPlayedCardEffect(UnoRank rank, UnoColor color, bool byHuman) {
    if (rank == UnoRank::WILD || rank == UnoRank::WILD_DRAW_FOUR) {
        awaitingColorChoice_ = true;
        pendingWildDrawFour_ = (rank == UnoRank::WILD_DRAW_FOUR);
        return; // currentColor_ is set once resolveColorChoice() runs
    }

    currentColor_ = color;
    switch (rank) {
        case UnoRank::SKIP:
        case UnoRank::REVERSE:
            // With only one opponent, skipping/reversing them both just
            // hand the turn straight back to whoever played the card --
            // ported from UnoGame.kt's own 2-player special case on a
            // played Reverse (`if (s.players.size == 2) nextIndex =
            // advance(...)`, which lands back on the same player exactly
            // like its Skip branch does). humanTurn is left unchanged.
            break;
        case UnoRank::DRAW_TWO: {
            uint8_t *victimHand = byHuman ? aiHand_ : humanHand_;
            uint8_t &victimCount = byHuman ? aiCount_ : humanCount_;
            drawInto(victimHand, victimCount, 2);
            break; // victim is skipped after drawing -- humanTurn unchanged
        }
        default:
            humanTurn = !humanTurn; // plain number card: normal turn pass
            break;
    }
}

void UnoRound::resolveColorChoice(UnoColor color) {
    currentColor_ = color;
    awaitingColorChoice_ = false;
    bool byHuman = humanTurn; // still the same player who played the wild

    if (pendingWildDrawFour_) {
        uint8_t *victimHand = byHuman ? aiHand_ : humanHand_;
        uint8_t &victimCount = byHuman ? aiCount_ : humanCount_;
        drawInto(victimHand, victimCount, 4);
        pendingWildDrawFour_ = false;
        // victim is skipped after drawing -- humanTurn unchanged
    } else {
        humanTurn = !humanTurn; // plain Wild: normal turn pass
    }
}

void UnoRound::shuffleDrawPile() {
    shuffleArray(drawPile_, drawCount_);
}

void UnoRound::reshuffleFromDiscard() {
    if (discardCount_ <= 1) return; // nothing to reshuffle besides the required top card
    uint8_t top = discardPile_[discardCount_ - 1];
    for (uint8_t i = 0; i + 1 < discardCount_; i++) drawPile_[drawCount_++] = discardPile_[i];
    discardCount_ = 1;
    discardPile_[0] = top;
    shuffleDrawPile();
}

bool UnoRound::debugCardsConserved() const {
    bool seen[UNO_DECK_SIZE];
    for (uint8_t i = 0; i < UNO_DECK_SIZE; i++) seen[i] = false;
    uint16_t total = 0;

    for (uint8_t i = 0; i < drawCount_; i++) {
        uint8_t id = drawPile_[i];
        if (id >= UNO_DECK_SIZE || seen[id]) return false;
        seen[id] = true;
        total++;
    }
    for (uint8_t i = 0; i < discardCount_; i++) {
        uint8_t id = discardPile_[i];
        if (id >= UNO_DECK_SIZE || seen[id]) return false;
        seen[id] = true;
        total++;
    }
    for (uint8_t i = 0; i < humanCount_; i++) {
        uint8_t id = humanHand_[i];
        if (id >= UNO_DECK_SIZE || seen[id]) return false;
        seen[id] = true;
        total++;
    }
    for (uint8_t i = 0; i < aiCount_; i++) {
        uint8_t id = aiHand_[i];
        if (id >= UNO_DECK_SIZE || seen[id]) return false;
        seen[id] = true;
        total++;
    }
    if (total != UNO_DECK_SIZE) return false;
    for (uint8_t i = 0; i < UNO_DECK_SIZE; i++) {
        if (!seen[i]) return false;
    }
    return true;
}

uint8_t UnoRound::drawInto(uint8_t *hand, uint8_t &handCount, uint8_t n) {
    uint8_t drawn = 0;
    for (uint8_t i = 0; i < n; i++) {
        if (drawCount_ == 0) reshuffleFromDiscard();
        // Pathological corner (see UnoLogic.h's top comment): every other
        // card is jammed into both hands and the discard pile has nothing
        // left beyond its required top card -- there is truly nowhere left
        // to draw from. Stop early rather than looping or crashing; the
        // caller sees drawn < n and treats it as "as many as exist".
        if (drawCount_ == 0) break;
        hand[handCount++] = drawPile_[--drawCount_];
        drawn++;
    }
    return drawn;
}
