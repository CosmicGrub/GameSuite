// Native playtest driver for the ESP32 UNO port's UnoLogic.h/.cpp AND
// UnoDisplay.cpp's pure hit-testing math -- compiles and runs the EXACT,
// unmodified files shipped in esp32-tictactoe/TicTacToeESP32/ on a desktop
// (against native_test's existing no-op TFT_eSPI/Arduino.h stubs), same
// spirit and same "what this can/can't prove" caveat as playtest.cpp:
// this proves the rules/deck/AI-heuristic/touch-math, never real pixels,
// real touch calibration, or real SPI/TFT_eSPI behavior.
//
// Own self-contained main() (a second native_test executable, not merged
// into playtest.cpp) -- see native_test/README.md's build commands; add UNO's
// two .cpp files to the same style of `cl`/`g++` invocation, substituting
// this file for playtest.cpp.

#include <cstdio>
#include <cstdint>
#include "../TicTacToeESP32/UnoLogic.h"
#include "../TicTacToeESP32/UnoDisplay.h"
#include "../TicTacToeESP32/Config.h"

// ---------------------------------------------------------------------------
// Shared check() bookkeeping -- same "run/failed" tally pattern as
// playtest.cpp's check()/checkLayout(), unified into one counter here since
// this file's sections are more numerous.
// ---------------------------------------------------------------------------
static int g_checksRun = 0, g_checksFailed = 0;
static void check(const char *label, bool condition) {
    g_checksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) g_checksFailed++;
}

// A separate RNG from UnoLogic's own internal shuffle RNG (seeded via
// seedUnoRandom()) -- this one drives the TEST's decisions (which legal card
// to play, which color to pick, draw-vs-keep), so shuffle randomness and
// decision randomness stay independently reproducible from their own seeds.
static uint32_t g_testRng = 0xC0FFEEu;
static uint32_t testRandom() {
    uint32_t x = g_testRng;
    x ^= x << 13; x ^= x >> 17; x ^= x << 5;
    g_testRng = x;
    return x;
}

static const char *colorName(UnoColor c) {
    switch (c) {
        case UnoColor::RED: return "Red";
        case UnoColor::YELLOW: return "Yellow";
        case UnoColor::GREEN: return "Green";
        case UnoColor::BLUE: return "Blue";
        default: return "Wild";
    }
}
static const char *rankName(UnoRank r) {
    switch (r) {
        case UnoRank::SKIP: return "Skip";
        case UnoRank::REVERSE: return "Reverse";
        case UnoRank::DRAW_TWO: return "+2";
        case UnoRank::WILD: return "Wild";
        case UnoRank::WILD_DRAW_FOUR: return "Wild+4";
        default: return "#"; // number cards printed with their digit separately
    }
}
static void printCard(UnoCardView c) {
    if (c.isNumber()) printf("%s %d", colorName(c.color), static_cast<int>(c.rank));
    else printf("%s %s", colorName(c.color), rankName(c.rank));
}

// ---------------------------------------------------------------------------
// 1) Deck composition -- exhaustive, exact counts, no duplicates. Pure
//    function test: doesn't even need a UnoRound.
// ---------------------------------------------------------------------------
static void deckCompositionTest() {
    printf("=== Deck composition (unoCardById(0..107), exhaustive) ===\n");

    int colorCount[5] = {0, 0, 0, 0, 0}; // RED,YELLOW,GREEN,BLUE,WILD
    int rankCount[15] = {0};             // ZERO..WILD_DRAW_FOUR
    bool seenId[UNO_DECK_SIZE] = {false};
    bool idsOk = true;

    for (int id = 0; id < UNO_DECK_SIZE; id++) {
        UnoCardView c = unoCardById(static_cast<uint8_t>(id));
        colorCount[static_cast<int>(c.color)]++;
        rankCount[static_cast<int>(c.rank)]++;
        if (c.instanceId != id || seenId[c.instanceId]) idsOk = false;
        seenId[c.instanceId] = true;
    }

    check("every instanceId 0..107 maps back to itself, no duplicates", idsOk);
    check("25 RED cards", colorCount[static_cast<int>(UnoColor::RED)] == 25);
    check("25 YELLOW cards", colorCount[static_cast<int>(UnoColor::YELLOW)] == 25);
    check("25 GREEN cards", colorCount[static_cast<int>(UnoColor::GREEN)] == 25);
    check("25 BLUE cards", colorCount[static_cast<int>(UnoColor::BLUE)] == 25);
    check("8 WILD-colored cards (4 Wild + 4 Wild Draw Four)", colorCount[static_cast<int>(UnoColor::WILD)] == 8);

    check("ZERO appears 4 times (one per color)", rankCount[static_cast<int>(UnoRank::ZERO)] == 4);
    const UnoRank numberRanks[9] = {
        UnoRank::ONE, UnoRank::TWO, UnoRank::THREE, UnoRank::FOUR, UnoRank::FIVE,
        UnoRank::SIX, UnoRank::SEVEN, UnoRank::EIGHT, UnoRank::NINE
    };
    bool allEightEach = true;
    for (UnoRank r : numberRanks) if (rankCount[static_cast<int>(r)] != 8) allEightEach = false;
    check("ONE..NINE each appear 8 times (two per color x4 colors)", allEightEach);
    check("SKIP appears 8 times", rankCount[static_cast<int>(UnoRank::SKIP)] == 8);
    check("REVERSE appears 8 times", rankCount[static_cast<int>(UnoRank::REVERSE)] == 8);
    check("DRAW_TWO appears 8 times", rankCount[static_cast<int>(UnoRank::DRAW_TWO)] == 8);
    check("WILD appears 4 times", rankCount[static_cast<int>(UnoRank::WILD)] == 4);
    check("WILD_DRAW_FOUR appears 4 times", rankCount[static_cast<int>(UnoRank::WILD_DRAW_FOUR)] == 4);

    int total = 0;
    for (int i = 0; i < 15; i++) total += rankCount[i];
    check("108 cards total", total == 108);
    printf("\n");
}

// ---------------------------------------------------------------------------
// 2) Fresh-round integrity + basic rule-correctness / guard-rejection checks.
// ---------------------------------------------------------------------------
static void freshRoundAndGuardChecks() {
    printf("=== Fresh round integrity + guard-rejection checks ===\n");
    seedUnoRandom(42);
    UnoRound r;
    r.reset();

    check("fresh round: result is IN_PROGRESS", r.result() == UnoRoundResult::IN_PROGRESS);
    check("fresh round: human hand has 7 cards (or 9 if the opener was a Draw Two, which forces 2 extra)",
          r.humanHandCount() == 7 || r.humanHandCount() == 9);
    check("fresh round: AI hand has 7 cards", r.aiHandCount() == 7);
    check("fresh round: discard pile has exactly 1 card", r.discardPileCount() == 1);
    check("fresh round: draw+discard+both hands == 108",
          r.drawPileCount() + r.discardPileCount() + r.humanHandCount() + r.aiHandCount() == UNO_DECK_SIZE);
    check("fresh round: all 108 cards accounted for exactly once", r.debugCardsConserved());

    check("playHumanCard rejects an out-of-range index", !r.playHumanCard(200));
    check("keepHumanDrawnCard rejects when nothing was just drawn", !r.keepHumanDrawnCard());
    check("chooseHumanColor rejects when no color choice is pending", !r.chooseHumanColor(UnoColor::RED));
    // chooseHumanColor rejecting UnoColor::WILD itself while a choice IS
    // pending is checked for real in scenarioWild() below, where a choice
    // actually is pending.

    if (!r.isHumanTurn()) {
        check("playHumanCard rejects a play when it isn't the human's turn", !r.playHumanCard(0));
        check("drawHumanCard rejects a draw when it isn't the human's turn", !r.drawHumanCard());
    }
    printf("\n");
}

// ---------------------------------------------------------------------------
// 3) Special-card-effect scenarios -- one per effect, each engineered by
//    seeding the shuffle until the human's OPENING hand actually contains a
//    legal card of the target rank (fully deterministic and reproducible
//    given a fixed seed search, same as hand-building a scenario, just
//    arrived at by search instead of manual placement since UnoRound's
//    hand contents aren't otherwise settable from outside the class).
// ---------------------------------------------------------------------------
static int findLegalHumanIndexOfRank(const UnoRound &r, UnoRank rank) {
    UnoCardView top = r.topDiscard();
    UnoColor cur = r.currentColor();
    for (uint8_t i = 0; i < r.humanHandCount(); i++) {
        UnoCardView c = r.humanHandCard(i);
        if (c.rank != rank) continue;
        bool legal = c.isWild() || c.color == cur || c.rank == top.rank;
        if (legal) return i;
    }
    return -1;
}

static bool setupScenario(UnoRound &r, UnoRank rank, uint32_t maxSeed, int &outIndex) {
    for (uint32_t seed = 1; seed <= maxSeed; seed++) {
        seedUnoRandom(seed);
        r.reset();
        if (!r.isHumanTurn()) continue; // need the human's genuine opening turn, hand still a known 7 cards
        int idx = findLegalHumanIndexOfRank(r, rank);
        if (idx >= 0) { outIndex = idx; return true; }
    }
    return false;
}

static void scenarioSkip() {
    printf("=== Scenario: SKIP played by the human ===\n");
    UnoRound r;
    int idx;
    bool found = setupScenario(r, UnoRank::SKIP, 20000, idx);
    check("found a reachable opening deal with a legal SKIP in the human's hand", found);
    if (!found) { printf("\n"); return; }

    uint8_t aiBefore = r.aiHandCount(), humanBefore = r.humanHandCount();
    bool ok = r.playHumanCard(static_cast<uint8_t>(idx));
    check("SKIP: play is accepted", ok);
    check("SKIP: human hand shrank by exactly 1", r.humanHandCount() == humanBefore - 1);
    check("SKIP: AI hand is untouched (Skip draws nothing)", r.aiHandCount() == aiBefore);
    check("SKIP: turn stays with the human (the AI, the only opponent, is skipped)", r.isHumanTurn());
    check("SKIP: no color-choice overlay raised", !r.awaitingColorChoice());
    check("SKIP: cards still conserved", r.debugCardsConserved());
    printf("\n");
}

static void scenarioReverseActsAsSkip() {
    printf("=== Scenario: REVERSE played by the human (2-player table -> acts as Skip) ===\n");
    UnoRound r;
    int idx;
    bool found = setupScenario(r, UnoRank::REVERSE, 20000, idx);
    check("found a reachable opening deal with a legal REVERSE in the human's hand", found);
    if (!found) { printf("\n"); return; }

    uint8_t aiBefore = r.aiHandCount(), humanBefore = r.humanHandCount();
    bool ok = r.playHumanCard(static_cast<uint8_t>(idx));
    check("REVERSE: play is accepted", ok);
    check("REVERSE: human hand shrank by exactly 1", r.humanHandCount() == humanBefore - 1);
    check("REVERSE: AI hand is untouched", r.aiHandCount() == aiBefore);
    check("REVERSE: turn stays with the human -- identical effect to Skip with only one opponent",
          r.isHumanTurn());
    check("REVERSE: cards still conserved", r.debugCardsConserved());
    printf("\n");
}

static void scenarioDrawTwo() {
    printf("=== Scenario: DRAW_TWO played by the human ===\n");
    UnoRound r;
    int idx;
    bool found = setupScenario(r, UnoRank::DRAW_TWO, 20000, idx);
    check("found a reachable opening deal with a legal DRAW_TWO in the human's hand", found);
    if (!found) { printf("\n"); return; }

    uint8_t aiBefore = r.aiHandCount(), humanBefore = r.humanHandCount();
    bool ok = r.playHumanCard(static_cast<uint8_t>(idx));
    check("DRAW_TWO: play is accepted", ok);
    check("DRAW_TWO: human hand shrank by exactly 1", r.humanHandCount() == humanBefore - 1);
    check("DRAW_TWO: AI hand grew by exactly 2", r.aiHandCount() == aiBefore + 2);
    check("DRAW_TWO: turn stays with the human (AI is skipped after drawing)", r.isHumanTurn());
    check("DRAW_TWO: cards still conserved", r.debugCardsConserved());
    printf("\n");
}

static void scenarioWild() {
    printf("=== Scenario: WILD played by the human ===\n");
    UnoRound r;
    int idx;
    bool found = setupScenario(r, UnoRank::WILD, 20000, idx);
    check("found a reachable opening deal with a WILD in the human's hand", found);
    if (!found) { printf("\n"); return; }

    uint8_t aiBefore = r.aiHandCount(), humanBefore = r.humanHandCount();
    bool ok = r.playHumanCard(static_cast<uint8_t>(idx));
    check("WILD: play is accepted (a Wild is always legal)", ok);
    check("WILD: human hand shrank by exactly 1", r.humanHandCount() == humanBefore - 1);
    check("WILD: raises the color-choice overlay", r.awaitingColorChoice());
    check("WILD: turn nominally still belongs to the human until a color is chosen", r.isHumanTurn());
    check("WILD: AI hand untouched so far", r.aiHandCount() == aiBefore);

    check("chooseHumanColor rejects UnoColor::WILD itself", !r.chooseHumanColor(UnoColor::WILD));
    check("...and the overlay is still pending after that rejection", r.awaitingColorChoice());

    bool chose = r.chooseHumanColor(UnoColor::GREEN);
    check("WILD: choosing GREEN is accepted", chose);
    check("WILD: currentColor() is now GREEN", r.currentColor() == UnoColor::GREEN);
    check("WILD: overlay cleared", !r.awaitingColorChoice());
    check("WILD: turn now passes to the AI (a plain Wild has no skip effect)", !r.isHumanTurn());
    check("WILD: AI hand still untouched (a plain Wild draws nothing)", r.aiHandCount() == aiBefore);
    check("WILD: cards still conserved", r.debugCardsConserved());
    printf("\n");
}

static void scenarioWildDrawFour() {
    printf("=== Scenario: WILD_DRAW_FOUR played by the human ===\n");
    UnoRound r;
    int idx;
    bool found = setupScenario(r, UnoRank::WILD_DRAW_FOUR, 20000, idx);
    check("found a reachable opening deal with a WILD_DRAW_FOUR in the human's hand", found);
    if (!found) { printf("\n"); return; }

    uint8_t aiBefore = r.aiHandCount(), humanBefore = r.humanHandCount();
    bool ok = r.playHumanCard(static_cast<uint8_t>(idx));
    check("WILD_DRAW_FOUR: play is accepted (always legal)", ok);
    check("WILD_DRAW_FOUR: human hand shrank by exactly 1", r.humanHandCount() == humanBefore - 1);
    check("WILD_DRAW_FOUR: raises the color-choice overlay", r.awaitingColorChoice());
    check("WILD_DRAW_FOUR: AI hand untouched until the color is chosen", r.aiHandCount() == aiBefore);

    bool chose = r.chooseHumanColor(UnoColor::BLUE);
    check("WILD_DRAW_FOUR: choosing BLUE is accepted", chose);
    check("WILD_DRAW_FOUR: currentColor() is now BLUE", r.currentColor() == UnoColor::BLUE);
    check("WILD_DRAW_FOUR: AI hand grew by exactly 4", r.aiHandCount() == aiBefore + 4);
    check("WILD_DRAW_FOUR: turn stays with the human (AI is skipped after drawing)", r.isHumanTurn());
    check("WILD_DRAW_FOUR: cards still conserved", r.debugCardsConserved());
    printf("\n");
}

// ---------------------------------------------------------------------------
// 4) Many-games random-vs-real-AI simulation. The human side (the only hand
//    this engine ever exposes) makes uniformly random LEGAL choices every
//    turn; the AI side always runs its real, shipped heuristic
//    (playAiTurn()) -- there's no way to drive the AI with independent
//    randomness without seeing its hand, which UnoRound deliberately never
//    exposes (see aiHandCount()'s comment). Every human turn's legality is
//    cross-checked against an independent recomputation of the documented
//    rule (color match / rank match / Wild) for EVERY card in hand, both
//    that a predicted-legal card is accepted and that every predicted-
//    illegal one is rejected -- this is what actually verifies "legal-play
//    checking matches every card-matching rule" here, exercised on every
//    human turn of every game below rather than a few hand-picked cases.
//    debugCardsConserved() and the 108-count are asserted after every
//    single turn of every game.
// ---------------------------------------------------------------------------
static long g_legalityChecks = 0, g_legalityMismatches = 0;
static long g_conservationFailures = 0;
static long g_reshuffleEventsSeen = 0; // lower bound -- see runOneGame()'s comment
static long g_humanWins = 0, g_aiWins = 0, g_unresolved = 0;
static long g_totalTurns = 0;

// Plays one random legal card (or draws) for the human, resolving any
// overlay first. Returns false (and does nothing) if it isn't the human's
// turn or the round is over -- the caller just calls playAiTurn() instead.
static bool randomHumanTurn(UnoRound &r) {
    if (r.result() != UnoRoundResult::IN_PROGRESS || !r.isHumanTurn()) return false;

    if (r.awaitingColorChoice()) {
        UnoColor c = static_cast<UnoColor>(testRandom() % 4);
        r.chooseHumanColor(c);
        return true;
    }
    if (r.awaitingHumanDrawDecision()) {
        if (testRandom() % 2 == 0) r.playHumanCard(r.humanHandCount() - 1);
        else r.keepHumanDrawnCard();
        return true;
    }

    UnoCardView top = r.topDiscard();
    UnoColor cur = r.currentColor();
    uint8_t legal[UNO_MAX_HAND];
    uint8_t legalN = 0;
    uint8_t n = r.humanHandCount();

    for (uint8_t i = 0; i < n; i++) {
        UnoCardView c = r.humanHandCard(i);
        bool predictedLegal = c.isWild() || c.color == cur || c.rank == top.rank;
        if (predictedLegal) {
            legal[legalN++] = i;
            continue;
        }
        // Cross-check: a predicted-ILLEGAL card must be rejected outright,
        // with no mutation -- safe to actually attempt since a true
        // rejection changes nothing, so the loop's remaining indices stay
        // valid. If our prediction was WRONG (the engine actually accepts
        // it), stop immediately -- state has now genuinely changed.
        g_legalityChecks++;
        uint8_t before = r.humanHandCount();
        bool accepted = r.playHumanCard(i);
        if (accepted || r.humanHandCount() != before) {
            g_legalityMismatches++;
            printf("!! legality mismatch: predicted ILLEGAL but engine accepted index %d (", i);
            printCard(c);
            printf(") on top of (");
            printCard(top);
            printf("), currentColor=%s\n", colorName(cur));
            return true; // state already changed this turn -- stop here, next call re-reads fresh state
        }
    }

    if (legalN == 0) {
        r.drawHumanCard();
        return true;
    }

    uint8_t pick = legal[testRandom() % legalN];
    UnoCardView chosen = r.humanHandCard(pick);
    uint8_t before = r.humanHandCount();
    g_legalityChecks++;
    bool ok = r.playHumanCard(pick);
    if (!ok || r.humanHandCount() != before - 1) {
        g_legalityMismatches++;
        printf("!! legality mismatch: predicted LEGAL but engine rejected (");
        printCard(chosen);
        printf(") on top of (");
        printCard(top);
        printf("), currentColor=%s\n", colorName(cur));
    }
    return true;
}

static void runOneGame(uint32_t shuffleSeed, int maxTurns, bool verboseTranscript, int verboseTurnLimit = 0) {
    seedUnoRandom(shuffleSeed);
    UnoRound r;
    r.reset();

    if (!r.debugCardsConserved()) {
        g_conservationFailures++;
        printf("!! card conservation FAILED immediately after reset (seed %u)\n", shuffleSeed);
    }

    uint8_t prevDrawCount = r.drawPileCount();
    int turn = 0;
    for (; turn < maxTurns; turn++) {
        if (r.result() != UnoRoundResult::IN_PROGRESS) break;

        if (verboseTranscript && turn < verboseTurnLimit) {
            printf("  turn %d: %s to act, top=(", turn, r.isHumanTurn() ? "human" : "AI");
            printCard(r.topDiscard());
            printf("), currentColor=%s, humanHand=%d, aiHand=%d, draw=%d\n",
                   colorName(r.currentColor()), r.humanHandCount(), r.aiHandCount(), r.drawPileCount());
        }

        if (r.isHumanTurn()) randomHumanTurn(r);
        else r.playAiTurn();

        g_totalTurns++;

        uint16_t total = static_cast<uint16_t>(r.drawPileCount()) + r.discardPileCount() +
                          r.humanHandCount() + r.aiHandCount();
        if (total != UNO_DECK_SIZE || !r.debugCardsConserved()) {
            g_conservationFailures++;
            printf("!! CARD CONSERVATION VIOLATION at turn %d (seed %u): total=%u\n", turn, shuffleSeed, total);
        }
        if (r.drawPileCount() > prevDrawCount) g_reshuffleEventsSeen++; // pile only ever grows via a reshuffle
        prevDrawCount = r.drawPileCount();
    }

    if (r.result() == UnoRoundResult::HUMAN_WINS) g_humanWins++;
    else if (r.result() == UnoRoundResult::AI_WINS) g_aiWins++;
    else {
        g_unresolved++;
        printf("!! game with seed %u did not resolve within %d turns (stuck?)\n", shuffleSeed, maxTurns);
    }

    if (verboseTranscript) {
        printf("  -> result: %s in %d turns\n",
               r.result() == UnoRoundResult::HUMAN_WINS ? "HUMAN WINS" :
               r.result() == UnoRoundResult::AI_WINS ? "AI WINS" : "UNRESOLVED",
               turn);
    }
}

static void manyGameSimulation() {
    printf("=== Watchable playthrough (one game, random human vs. real AI heuristic) ===\n");
    printf("  (transcript truncated to the first 40 turns for readability -- the game itself\n");
    printf("   still runs to a real conclusion, or up to the same 800-turn cap the simulation below uses)\n");
    runOneGame(/*seed=*/7, /*maxTurns=*/800, /*verboseTranscript=*/true, /*verboseTurnLimit=*/40);
    printf("\n");

    // The demo game above deliberately doesn't count toward the aggregate
    // stats below (its role is illustration, not another data point) --
    // reset the tallies it just contributed to. Legality/conservation
    // checks stay cumulative, since a real bug there is worth catching
    // regardless of which game surfaces it.
    g_humanWins = g_aiWins = g_unresolved = g_totalTurns = g_reshuffleEventsSeen = 0;

    printf("=== Random-vs-AI self-play simulation ===\n");
    const int GAME_COUNT = 4000;
    const int TURN_CAP = 800;
    for (int g = 1; g <= GAME_COUNT; g++) {
        runOneGame(static_cast<uint32_t>(g * 2654435761u + 1), TURN_CAP, false);
    }
    // A couple of extra-long single games specifically to push the draw
    // pile through many more reshuffle cycles than a typical short game would.
    runOneGame(0xABCDEF01u, 20000, false);
    runOneGame(0x13579BDFu, 20000, false);

    printf("Games played:              %d\n", GAME_COUNT + 2);
    printf("Total turns executed:      %ld\n", g_totalTurns);
    printf("Human wins:                %ld\n", g_humanWins);
    printf("AI wins:                   %ld\n", g_aiWins);
    printf("Unresolved (stuck/capped): %ld  <-- must be 0\n", g_unresolved);
    printf("Reshuffle events observed (lower bound): %ld\n", g_reshuffleEventsSeen);
    printf("Legality cross-checks run: %ld\n", g_legalityChecks);
    printf("Legality mismatches:       %ld  <-- must be 0\n", g_legalityMismatches);
    printf("Card-conservation failures: %ld  <-- must be 0\n", g_conservationFailures);

    check("both the human and the AI actually win at least once (a real, beatable-and-beating AI)",
          g_humanWins > 0 && g_aiWins > 0);
    check("every game resolved with a real winner (none stuck/capped)", g_unresolved == 0);
    check("at least one reshuffle-from-discard was actually exercised", g_reshuffleEventsSeen > 0);
    check("legal-play checking matched the documented rule on every check", g_legalityMismatches == 0);
    check("no card was ever duplicated or lost across any turn of any game", g_conservationFailures == 0);
    printf("\n");
}

// ---------------------------------------------------------------------------
// 5) UnoDisplay.cpp touch hit-testing -- pure math, no TFT_eSPI calls,
//    checked the same way Display.cpp's own hit-testing is in playtest.cpp.
// ---------------------------------------------------------------------------

// Independent re-derivation of UnoDisplay.cpp's (file-local, so not directly
// callable here) hand-layout formula -- same "recompute it ourselves rather
// than reach into the module's internals" approach playtest.cpp uses for
// TicTacToe's own cell-center math.
static int16_t testHandAdvance(const UnoLayout &l, uint8_t count) {
    int16_t natural = l.handCardW + l.handGap;
    if (count <= 1) return natural;
    int16_t maxRowWidth = SCREEN_WIDTH - 2 * l.handMarginX;
    int16_t needed = natural * (count - 1) + l.handCardW;
    if (needed <= maxRowWidth) return natural;
    int16_t compressed = (maxRowWidth - l.handCardW) / (count - 1);
    return compressed < 4 ? 4 : compressed;
}
static int16_t testHandLeft(const UnoLayout &l, uint8_t count, int16_t advance) {
    if (count == 0) return SCREEN_WIDTH / 2;
    int16_t totalWidth = advance * (count - 1) + l.handCardW;
    return (SCREEN_WIDTH - totalWidth) / 2;
}

static void unoDisplayHitTestChecks() {
    printf("=== UNO touch hit-testing checks (UnoDisplay.cpp, against Config.h's real screen size) ===\n");
    UnoLayout l = computeUnoLayout();
    printf("  layout: discard=(%d,%d) draw=(%d,%d) card=%dx%d hand y=%d card=%dx%d button=(%d,%d)\n",
           l.discardX, l.discardY, l.drawPileX, l.drawPileY, l.cardW, l.cardH,
           l.handY, l.handCardW, l.handCardH, l.buttonX, l.buttonY);

    check("status bar fits on screen", l.statusY >= 0 && l.statusH > 0 && l.statusY + l.statusH <= SCREEN_HEIGHT);
    check("discard pile fits on screen", l.discardX >= 0 && l.discardX + l.cardW <= SCREEN_WIDTH &&
                                          l.discardY >= 0 && l.discardY + l.cardH <= SCREEN_HEIGHT);
    check("draw pile fits on screen", l.drawPileX >= 0 && l.drawPileX + l.cardW <= SCREEN_WIDTH &&
                                        l.drawPileY >= 0 && l.drawPileY + l.cardH <= SCREEN_HEIGHT);
    check("discard and draw piles do not overlap",
          l.discardX + l.cardW <= l.drawPileX || l.drawPileX + l.cardW <= l.discardX);
    check("play-again button fits on screen", l.buttonX >= 0 && l.buttonX + l.buttonW <= SCREEN_WIDTH &&
                                                l.buttonY >= 0 && l.buttonY + l.buttonH <= SCREEN_HEIGHT);
    check("human hand row fits vertically on screen", l.handY >= 0 && l.handY + l.handCardH <= SCREEN_HEIGHT);

    int16_t dcx = l.drawPileX + l.cardW / 2, dcy = l.drawPileY + l.cardH / 2;
    check("draw pile center is a hit", hitTestUnoDrawPile(l, dcx, dcy));
    check("1px left of draw pile is not a hit", !hitTestUnoDrawPile(l, l.drawPileX - 1, dcy));
    check("1px right of draw pile is not a hit", !hitTestUnoDrawPile(l, l.drawPileX + l.cardW, dcy));
    check("1px above draw pile is not a hit", !hitTestUnoDrawPile(l, dcx, l.drawPileY - 1));
    check("1px below draw pile is not a hit", !hitTestUnoDrawPile(l, dcx, l.drawPileY + l.cardH));

    int16_t bcx = l.buttonX + l.buttonW / 2, bcy = l.buttonY + l.buttonH / 2;
    check("play-again button center is a hit", hitTestUnoPlayAgainButton(l, bcx, bcy));
    check("1px left of play-again button is not a hit", !hitTestUnoPlayAgainButton(l, l.buttonX - 1, bcy));
    check("1px right of play-again button is not a hit", !hitTestUnoPlayAgainButton(l, l.buttonX + l.buttonW, bcy));

    check("home button hit near status bar's top-left", hitTestUnoHomeButton(l, 15, l.statusY + l.statusH / 2));
    check("home button doesn't swallow taps mid status bar",
          !hitTestUnoHomeButton(l, SCREEN_WIDTH / 2, l.statusY + l.statusH / 2));

    // Human hand at several counts, including ones large enough to force the
    // overlap-compression branch (compare against Kotlin's own max hand
    // reality: penalties can easily push a hand past 7).
    const uint8_t counts[] = {1, 2, 7, 15, 25};
    for (uint8_t count : counts) {
        int16_t advance = testHandAdvance(l, count);
        int16_t left = testHandLeft(l, count, advance);
        bool allCentersOk = true;
        for (uint8_t i = 0; i < count; i++) {
            // When cards overlap (advance < handCardW), only the LAST card
            // is fully exposed -- every earlier card's right portion is
            // drawn over by the next one and correctly belongs to that next
            // (higher, topmost) index, matching drawUnoHumanHand()'s own
            // left-to-right draw order. So the point guaranteed to belong to
            // card i (and no other) is the middle of its EXPOSED sliver:
            // its full width for the last card, or just the `advance`-wide
            // strip before the next card starts, otherwise.
            int16_t exposed = (i == count - 1) ? l.handCardW : (advance < l.handCardW ? advance : l.handCardW);
            int16_t cx = left + i * advance + exposed / 2;
            int16_t cy = l.handY + l.handCardH / 2;
            uint8_t hit;
            bool ok = hitTestUnoHumanHandCard(l, count, cx, cy, hit) && hit == i;
            if (!ok) allCentersOk = false;
        }
        char label[96];
        snprintf(label, sizeof(label), "hand of %d cards: every card's own exposed area taps that same index", count);
        check(label, allCentersOk);

        uint8_t discard;
        int16_t aboveY = l.handY - 1, belowY = l.handY + l.handCardH;
        char label2[96];
        snprintf(label2, sizeof(label2), "hand of %d cards: 1px above the row is not a hit", count);
        check(label2, !hitTestUnoHumanHandCard(l, count, left, aboveY, discard));
        char label3[96];
        snprintf(label3, sizeof(label3), "hand of %d cards: 1px below the row is not a hit", count);
        check(label3, !hitTestUnoHumanHandCard(l, count, left, belowY, discard));

        if (count >= 2) {
            bool overlapping = advance < l.handCardW;
            uint8_t hit;
            int16_t boundaryX = left + advance; // start of card index 1, still inside card 0's span iff overlapping
            int16_t cy = l.handY + l.handCardH / 2;
            if (overlapping) {
                bool ok = hitTestUnoHumanHandCard(l, count, boundaryX, cy, hit) && hit == 1;
                char label4[112];
                snprintf(label4, sizeof(label4), "hand of %d cards (overlapping): shared pixel resolves to the higher (topmost) index", count);
                check(label4, ok);
            } else {
                // Natural, non-overlapping spacing leaves a real gap between
                // cards -- the midpoint of that gap must hit neither.
                int16_t gapX = left + l.handCardW + l.handGap / 2;
                char label4[112];
                snprintf(label4, sizeof(label4), "hand of %d cards (spaced out): the gap between cards is not a hit", count);
                check(label4, !hitTestUnoHumanHandCard(l, count, gapX, cy, hit));
            }
        }
    }
    uint8_t emptyHandHit;
    check("an empty hand (0 cards) never reports a hit", !hitTestUnoHumanHandCard(l, 0, SCREEN_WIDTH / 2, l.handY + 5, emptyHandHit));

    // Color-choice overlay.
    UnoColorOverlayLayout ol = computeUnoColorOverlayLayout();
    check("color overlay panel fits on screen", ol.panelX >= 0 && ol.panelX + ol.panelW <= SCREEN_WIDTH &&
                                                  ol.panelY >= 0 && ol.panelY + ol.panelH <= SCREEN_HEIGHT);
    static const UnoColor expected[4] = { UnoColor::RED, UnoColor::YELLOW, UnoColor::GREEN, UnoColor::BLUE };
    bool allSwatchesOk = true;
    for (uint8_t i = 0; i < 4; i++) {
        int16_t cx = ol.swatchX[i] + ol.swatchSize / 2, cy = ol.swatchY + ol.swatchSize / 2;
        UnoColor got;
        if (!hitTestUnoColorOverlay(ol, cx, cy, got) || got != expected[i]) allSwatchesOk = false;
    }
    check("each color swatch's own center resolves to the correct color, in RED/YELLOW/GREEN/BLUE order", allSwatchesOk);
    UnoColor discardColor;
    int16_t gapX = ol.swatchX[0] + ol.swatchSize + ol.swatchGap / 2;
    check("the gap between two swatches is not a hit", !hitTestUnoColorOverlay(ol, gapX, ol.swatchY + ol.swatchSize / 2, discardColor));
    check("1px above the swatches is not a hit", !hitTestUnoColorOverlay(ol, ol.swatchX[0] + 5, ol.swatchY - 1, discardColor));
    check("1px below the swatches is not a hit", !hitTestUnoColorOverlay(ol, ol.swatchX[0] + 5, ol.swatchY + ol.swatchSize, discardColor));

    printf("\n");
}

int main() {
    deckCompositionTest();
    freshRoundAndGuardChecks();
    scenarioSkip();
    scenarioReverseActsAsSkip();
    scenarioDrawTwo();
    scenarioWild();
    scenarioWildDrawFour();
    manyGameSimulation();
    unoDisplayHitTestChecks();

    printf("Total checks run: %d, failed: %d\n", g_checksRun, g_checksFailed);
    bool allGood = (g_checksFailed == 0);
    printf("=== OVERALL: %s ===\n", allGood ? "ALL UNO PLAYTEST CHECKS PASSED" : "FAILURES FOUND -- SEE ABOVE");
    return allGood ? 0 : 1;
}
