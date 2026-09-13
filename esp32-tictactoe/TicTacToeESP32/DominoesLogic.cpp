#include "DominoesLogic.h"

// ---------------------------------------------------------------------------
// EASY-tier RNG -- a small self-contained xorshift32, deliberately NOT
// Arduino's own random()/randomSeed() so this file compiles unchanged
// against native_test's no-op Arduino.h stub, exactly UnoLogic.cpp's/
// MancalaLogic.cpp's own identical RNG. Fixed default seed so both
// native_test and a board that never calls seedDominoesRandom() behave
// deterministically/reproducibly.
// ---------------------------------------------------------------------------
static uint32_t g_dominoesRngState = 0x85EBCA6Bu;

void seedDominoesRandom(uint32_t seed) {
    g_dominoesRngState = seed ? seed : 0x85EBCA6Bu; // xorshift32 must never be seeded with 0
}

static uint32_t dominoesNextRandom() {
    uint32_t x = g_dominoesRngState;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    g_dominoesRngState = x;
    return x;
}

// Fisher-Yates over arr[0..n) -- same idiom as UnoLogic.cpp's own shuffleArray.
static void shuffleArray(uint8_t *arr, uint8_t n) {
    for (uint8_t i = n; i > 1; i--) {
        uint8_t j = (uint8_t)(dominoesNextRandom() % i);
        uint8_t tmp = arr[i - 1];
        arr[i - 1] = arr[j];
        arr[j] = tmp;
    }
}

// ---------------------------------------------------------------------------
// Tile-id encoding -- see DominoesLogic.h's declaration for what this
// mirrors (DominoGame.kt's startMatch() own `for (a in 0..6) for (b in
// a..6)` construction order).
// ---------------------------------------------------------------------------
struct DominoTileInfo { uint8_t a, b; };
static const DominoTileInfo DOMINOES_TILES[DOMINOES_SET_SIZE] = {
    {0, 0}, {0, 1}, {0, 2}, {0, 3}, {0, 4}, {0, 5}, {0, 6},
    {1, 1}, {1, 2}, {1, 3}, {1, 4}, {1, 5}, {1, 6},
    {2, 2}, {2, 3}, {2, 4}, {2, 5}, {2, 6},
    {3, 3}, {3, 4}, {3, 5}, {3, 6},
    {4, 4}, {4, 5}, {4, 6},
    {5, 5}, {5, 6},
    {6, 6},
};

DominoTileView dominoTileById(uint8_t instanceId) {
    DominoTileInfo info = DOMINOES_TILES[instanceId];
    return { info.a, info.b, instanceId };
}

// ---------------------------------------------------------------------------
// DominoesBoard
// ---------------------------------------------------------------------------
void DominoesBoard::reset() {
    for (uint8_t i = 0; i < DOMINOES_SET_SIZE; i++) boneyard_[i] = i;
    boneyardCount_ = DOMINOES_SET_SIZE;
    shuffleBoneyard();

    humanCount = 0;
    aiCount = 0;
    chainCount = 0;
    consecutivePasses = 0;
    result_ = DominoesResult::IN_PROGRESS;

    drawInto(humanHand, humanCount, DOMINOES_HAND_SIZE);
    drawInto(aiHand, aiCount, DOMINOES_HAND_SIZE);

    // Standard opening rule, ported from DominoGame.kt's startMatch():
    // whoever holds the highest double goes first; if NEITHER side has any
    // double, whoever holds the single highest-pip tile goes first. Ties
    // favor the human (index 0), matching the Kotlin source's own
    // maxByOrNull tie-break (it keeps the first maximal element it sees,
    // and the human is always index 0 in this project's fixed convention).
    int humanBestDouble = -1, aiBestDouble = -1;
    for (uint8_t i = 0; i < humanCount; i++) {
        DominoTileView t = dominoTileById(humanHand[i]);
        if (t.isDouble() && (int)t.a > humanBestDouble) humanBestDouble = t.a;
    }
    for (uint8_t i = 0; i < aiCount; i++) {
        DominoTileView t = dominoTileById(aiHand[i]);
        if (t.isDouble() && (int)t.a > aiBestDouble) aiBestDouble = t.a;
    }

    if (humanBestDouble >= 0 || aiBestDouble >= 0) {
        humanTurn = (humanBestDouble >= aiBestDouble);
    } else {
        int humanBestPip = -1, aiBestPip = -1;
        for (uint8_t i = 0; i < humanCount; i++) {
            DominoTileView t = dominoTileById(humanHand[i]);
            int s = (int)t.a + (int)t.b;
            if (s > humanBestPip) humanBestPip = s;
        }
        for (uint8_t i = 0; i < aiCount; i++) {
            DominoTileView t = dominoTileById(aiHand[i]);
            int s = (int)t.a + (int)t.b;
            if (s > aiBestPip) aiBestPip = s;
        }
        humanTurn = (humanBestPip >= aiBestPip);
    }
}

void DominoesBoard::shuffleBoneyard() {
    shuffleArray(boneyard_, boneyardCount_);
}

uint8_t DominoesBoard::drawInto(uint8_t *hand, uint8_t &handCount, uint8_t n) {
    uint8_t drawn = 0;
    for (uint8_t i = 0; i < n && boneyardCount_ > 0; i++) {
        hand[handCount++] = boneyard_[--boneyardCount_];
        drawn++;
    }
    return drawn;
}

uint8_t DominoesBoard::leftEndValue() const {
    DominoTileView t = chainTileAt(0);
    return chainFlipped[0] ? t.b : t.a;
}

uint8_t DominoesBoard::rightEndValue() const {
    DominoTileView t = chainTileAt(chainCount - 1);
    return chainFlipped[chainCount - 1] ? t.a : t.b;
}

bool DominoesBoard::canAttachLeft(DominoTileView tile) const {
    if (chainCount == 0) return true;
    uint8_t end = leftEndValue();
    return tile.a == end || tile.b == end;
}

bool DominoesBoard::canAttachRight(DominoTileView tile) const {
    if (chainCount == 0) return true;
    uint8_t end = rightEndValue();
    return tile.a == end || tile.b == end;
}

bool DominoesBoard::isPlayableFromHand(bool humanSide, uint8_t handIndex) const {
    const uint8_t *hand = humanSide ? humanHand : aiHand;
    uint8_t count = humanSide ? humanCount : aiCount;
    if (handIndex >= count) return false;
    DominoTileView t = dominoTileById(hand[handIndex]);
    return canAttachLeft(t) || canAttachRight(t);
}

bool DominoesBoard::humanHandTileIsPlayable(uint8_t handIndex) const {
    return isPlayableFromHand(true, handIndex);
}

bool DominoesBoard::sideHasLegalPlay(bool humanSide) const {
    if (chainCount == 0) return (humanSide ? humanCount : aiCount) > 0; // matches DominoGame.canPlay()'s own empty-chain branch
    const uint8_t *hand = humanSide ? humanHand : aiHand;
    uint8_t count = humanSide ? humanCount : aiCount;
    uint8_t left = leftEndValue(), right = rightEndValue();
    for (uint8_t i = 0; i < count; i++) {
        DominoTileView t = dominoTileById(hand[i]);
        if (t.a == left || t.b == left || t.a == right || t.b == right) return true;
    }
    return false;
}

bool DominoesBoard::currentSideHasLegalPlay() const {
    return sideHasLegalPlay(humanTurn);
}

uint8_t DominoesBoard::legalPlays(DominoesLegalPlay outPlays[]) const {
    uint8_t n = 0;
    const uint8_t *hand = humanTurn ? humanHand : aiHand;
    uint8_t count = humanTurn ? humanCount : aiCount;

    if (chainCount == 0) {
        // No exposed ends yet -- every tile in hand is a legal opener,
        // recorded as a single (handIndex, attachToLeft=true) entry each
        // (attachToLeft is irrelevant against an empty chain; see
        // applyPlay()), matching DominoGame.canPlace()'s own "chain empty
        // -> anything goes" rule without the left/right duplication below.
        for (uint8_t i = 0; i < count; i++) outPlays[n++] = { i, true };
        return n;
    }

    uint8_t left = leftEndValue(), right = rightEndValue();
    // Left-end matches first, then right-end matches -- same order as
    // DominoGame.kt's own `legalLeft + legalRight`, which MEDIUM's "first
    // legal option" tier depends on (see chooseBotPlayIndex()). A tile
    // matching both ends is deliberately listed twice.
    for (uint8_t i = 0; i < count; i++) {
        DominoTileView t = dominoTileById(hand[i]);
        if (t.a == left || t.b == left) outPlays[n++] = { i, true };
    }
    for (uint8_t i = 0; i < count; i++) {
        DominoTileView t = dominoTileById(hand[i]);
        if (t.a == right || t.b == right) outPlays[n++] = { i, false };
    }
    return n;
}

void DominoesBoard::applyPlay(uint8_t *hand, uint8_t &handCount, uint8_t handIndex, bool attachToLeft, bool byHuman) {
    uint8_t instId = hand[handIndex];
    DominoTileView t = dominoTileById(instId);

    if (chainCount == 0) {
        chainInstanceId[0] = instId;
        chainFlipped[0] = false;
        chainCount = 1;
    } else if (attachToLeft) {
        // wouldFlip(), ported: attaching to the left, the new tile's own
        // RIGHT side is what touches the chain's old left end -- flip iff
        // that isn't already `b` (see DominoGame.kt's wouldFlip() KDoc).
        uint8_t end = leftEndValue();
        bool flipped = (t.b != end);
        for (uint8_t i = chainCount; i > 0; i--) {
            chainInstanceId[i] = chainInstanceId[i - 1];
            chainFlipped[i] = chainFlipped[i - 1];
        }
        chainInstanceId[0] = instId;
        chainFlipped[0] = flipped;
        chainCount++;
    } else {
        // Attaching to the right, the new tile's own LEFT side touches the
        // chain's old right end -- flip iff that isn't already `a`.
        uint8_t end = rightEndValue();
        bool flipped = (t.a != end);
        chainInstanceId[chainCount] = instId;
        chainFlipped[chainCount] = flipped;
        chainCount++;
    }

    for (uint8_t i = handIndex; i + 1 < handCount; i++) hand[i] = hand[i + 1];
    handCount--;
    consecutivePasses = 0;

    if (handCount == 0) {
        result_ = byHuman ? DominoesResult::HUMAN_WINS : DominoesResult::AI_WINS;
        return;
    }
    humanTurn = !humanTurn;
}

uint16_t DominoesBoard::handPipTotal(const uint8_t *hand, uint8_t count) const {
    uint16_t total = 0;
    for (uint8_t i = 0; i < count; i++) total += dominoTileById(hand[i]).pipTotal();
    return total;
}

void DominoesBoard::finishBlocked() {
    uint16_t humanPips = handPipTotal(humanHand, humanCount);
    uint16_t aiPips = handPipTotal(aiHand, aiCount);
    if (humanPips < aiPips) result_ = DominoesResult::HUMAN_WINS;
    else if (aiPips < humanPips) result_ = DominoesResult::AI_WINS;
    else result_ = DominoesResult::DRAW;
}

bool DominoesBoard::playHumanTile(uint8_t handIndex, bool attachToLeft) {
    if (!humanTurn) return false;
    if (result_ != DominoesResult::IN_PROGRESS) return false;
    if (handIndex >= humanCount) return false;
    DominoTileView t = dominoTileById(humanHand[handIndex]);
    bool legal = attachToLeft ? canAttachLeft(t) : canAttachRight(t);
    if (!legal) return false;
    applyPlay(humanHand, humanCount, handIndex, attachToLeft, /*byHuman=*/true);
    return true;
}

bool DominoesBoard::drawHumanTile() {
    if (!humanTurn) return false;
    if (result_ != DominoesResult::IN_PROGRESS) return false;
    if (boneyardCount_ == 0) return false;
    if (sideHasLegalPlay(true)) return false; // must play a legal tile instead of drawing -- mirrors DominoGame.drawFromBoneyard()'s exact guard
    drawInto(humanHand, humanCount, 1);
    return true;
}

bool DominoesBoard::passHuman() {
    if (!humanTurn) return false;
    if (result_ != DominoesResult::IN_PROGRESS) return false;
    if (boneyardCount_ != 0) return false;       // must draw instead while the boneyard still has tiles
    if (sideHasLegalPlay(true)) return false;    // must play instead
    consecutivePasses++;
    humanTurn = false;
    if (consecutivePasses >= 2) finishBlocked();
    return true;
}

int DominoesBoard::chooseOpeningIndex() const {
    if (aiCount == 0) return -1; // defensive -- never actually happens: the chain is only ever empty right after reset(), which always deals a full hand first
    switch (cpuDifficulty) {
        case CpuDifficulty::EASY:
            return (int)(dominoesNextRandom() % aiCount);
        case CpuDifficulty::MEDIUM:
        case CpuDifficulty::HARD:
        default: {
            // hand.maxByOrNull { if (it.isDouble) it.a + 10 else it.a + it.b } --
            // strict '>' below keeps the FIRST maximal element on a tie,
            // matching Kotlin's maxByOrNull.
            int best = 0;
            DominoTileView bt = dominoTileById(aiHand[0]);
            int bestScore = bt.isDouble() ? (int)bt.a + 10 : (int)bt.pipTotal();
            for (uint8_t i = 1; i < aiCount; i++) {
                DominoTileView t = dominoTileById(aiHand[i]);
                int score = t.isDouble() ? (int)t.a + 10 : (int)t.pipTotal();
                if (score > bestScore) { bestScore = score; best = i; }
            }
            return best;
        }
    }
}

int DominoesBoard::chooseBotPlayIndex(const DominoesLegalPlay options[], uint8_t n) const {
    if (n == 0) return -1;
    switch (cpuDifficulty) {
        case CpuDifficulty::EASY:
            return (int)(dominoesNextRandom() % n);
        case CpuDifficulty::MEDIUM:
            return 0; // options.first() -- left-end matches are listed before right-end ones, see legalPlays()
        case CpuDifficulty::HARD:
        default: {
            // options.maxByOrNull { (d, _) -> (if (d.isDouble) 100 else 0) + d.a + d.b }
            int best = 0;
            DominoTileView bt = dominoTileById(aiHand[options[0].handIndex]);
            int bestScore = (bt.isDouble() ? 100 : 0) + (int)bt.pipTotal();
            for (uint8_t i = 1; i < n; i++) {
                DominoTileView t = dominoTileById(aiHand[options[i].handIndex]);
                int score = (t.isDouble() ? 100 : 0) + (int)t.pipTotal();
                if (score > bestScore) { bestScore = score; best = i; }
            }
            return best;
        }
    }
}

void DominoesBoard::playAi() {
    if (humanTurn) return;
    if (result_ != DominoesResult::IN_PROGRESS) return;

    if (chainCount == 0) {
        int idx = chooseOpeningIndex();
        if (idx < 0) return; // defensive -- see chooseOpeningIndex()'s own comment
        applyPlay(aiHand, aiCount, (uint8_t)idx, /*attachToLeft=*/true, /*byHuman=*/false);
        return;
    }

    // Draw-until-playable-or-empty, all as one continuous turn -- see
    // DominoesLogic.h's own playAi() comment for why this loops here
    // instead of the per-call design Mancala's playAi() uses.
    while (true) {
        DominoesLegalPlay options[DOMINOES_MAX_LEGAL_PLAYS];
        uint8_t n = legalPlays(options);
        int choice = chooseBotPlayIndex(options, n);
        if (choice >= 0) {
            applyPlay(aiHand, aiCount, options[choice].handIndex, options[choice].attachToLeft, /*byHuman=*/false);
            return;
        }
        if (boneyardCount_ == 0) {
            consecutivePasses++;
            humanTurn = true;
            if (consecutivePasses >= 2) finishBlocked();
            return;
        }
        drawInto(aiHand, aiCount, 1); // drew one -- loop back and re-check with the newly drawn tile
    }
}

void DominoesBoard::setHandsForTest(const uint8_t humanIds[], uint8_t humanN, const uint8_t aiIds[], uint8_t aiN) {
    for (uint8_t i = 0; i < humanN; i++) humanHand[i] = humanIds[i];
    humanCount = humanN;
    for (uint8_t i = 0; i < aiN; i++) aiHand[i] = aiIds[i];
    aiCount = aiN;
}

void DominoesBoard::setChainForTest(const uint8_t instanceIds[], const bool flipped[], uint8_t n) {
    for (uint8_t i = 0; i < n; i++) {
        chainInstanceId[i] = instanceIds[i];
        chainFlipped[i] = flipped[i];
    }
    chainCount = n;
}

void DominoesBoard::setBoneyardForTest(const uint8_t ids[], uint8_t n) {
    for (uint8_t i = 0; i < n; i++) boneyard_[i] = ids[i];
    boneyardCount_ = n;
}

void DominoesBoard::setTurnForTest(bool humanSide) {
    humanTurn = humanSide;
    consecutivePasses = 0;
    result_ = DominoesResult::IN_PROGRESS;
}

bool DominoesBoard::playMoveForTest(uint8_t handIndex, bool attachToLeft) {
    if (result_ != DominoesResult::IN_PROGRESS) return false;
    bool bySideHuman = humanTurn;
    uint8_t *hand = bySideHuman ? humanHand : aiHand;
    uint8_t &count = bySideHuman ? humanCount : aiCount;
    if (handIndex >= count) return false;
    DominoTileView t = dominoTileById(hand[handIndex]);
    bool legal = attachToLeft ? canAttachLeft(t) : canAttachRight(t);
    if (!legal) return false;
    applyPlay(hand, count, handIndex, attachToLeft, bySideHuman);
    return true;
}

bool DominoesBoard::drawForTest() {
    if (result_ != DominoesResult::IN_PROGRESS) return false;
    if (boneyardCount_ == 0) return false;
    if (sideHasLegalPlay(humanTurn)) return false;
    uint8_t *hand = humanTurn ? humanHand : aiHand;
    uint8_t &count = humanTurn ? humanCount : aiCount;
    drawInto(hand, count, 1);
    return true;
}

bool DominoesBoard::passForTest() {
    if (result_ != DominoesResult::IN_PROGRESS) return false;
    if (boneyardCount_ != 0) return false;
    if (sideHasLegalPlay(humanTurn)) return false;
    consecutivePasses++;
    humanTurn = !humanTurn;
    if (consecutivePasses >= 2) finishBlocked();
    return true;
}
