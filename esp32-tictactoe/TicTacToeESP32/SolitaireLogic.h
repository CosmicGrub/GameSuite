#pragma once
#include <Arduino.h>

// Pure game logic for (Klondike) Solitaire -- no display/touch code here at
// all, mirroring GameLogic.h/CheckersLogic.h/MancalaLogic.h/DominoesLogic.h's
// engine/screen split so this file can be unit-tested on a desktop compiler
// with zero changes (see native_test/solitaire_playtest.cpp). Ported from
// GameSuite's own Android engine
// (app/src/main/java/com/gamesuite/games/solitaire/SolitaireGame.kt) --
// standard Klondike, single-card moves only (no multi-card run dragging --
// this board has no drag input model at all, same honest MVP cut the Kotlin
// source itself already makes, for the same reason: this project's own
// tap-select-then-tap-destination interaction, same shape Checkers/Chess/
// Dominoes already use here). Concretely, kept vs. dropped from the Kotlin
// source:
//
// KEPT (byte-for-byte the same rule, just re-expressed in C++):
//   - The real 52-card deck and the standard deal (column N gets N cards,
//     N-1 face-down then 1 face-up; 28 dealt, 24 left for the stock).
//   - canPlaceOnTableau (descending rank, alternating color; only a King
//     may start an empty column) and canPlaceOnFoundation (same suit,
//     ascending from Ace) exactly, and the auto-flip-the-next-face-down-
//     card-the-instant-a-column-empties-out-of-face-up-cards rule.
//   - Bounded undo (5 steps) and the single-step-at-a-time auto-complete
//     heuristic (foundation-ready cards first, then one level of
//     "unblocking" tableau-to-tableau moves) -- see autoCompleteStep()'s
//     own comment for the exact same "this is a heuristic, not a solver"
//     honesty the Kotlin source's KDoc states.
//   - +10 per card banked to a foundation, and a "games won this session"
//     tally that survives a fresh deal (New Game) but not leaving the
//     game entirely -- same split Mancala/Dominoes' own scores use for
//     "this round" vs. "this whole visit".
//   - Per-tap feedback text (lastAction()) -- unlike this arcade's 2-player
//     games (where an illegal tap is silently ignored, since a human
//     opponent watching the same board makes the rejected tap
//     self-explanatory), a solo puzzle genuinely benefits from a status
//     line saying WHY a tap did nothing, exactly as the Kotlin source's own
//     lastAction field does -- so this is a deliberate DEVIATION from this
//     project's usual "return false and stay silent" convention, not an
//     oversight.
// DROPPED for this scope (a deliberate, pragmatic cut, not an oversight):
//   - Draw-3 (drawThree): this board only ever draws one card at a time.
//     Rendering a 3-card fanned waste pile (only the top of which is ever
//     playable) is real extra Display work for a rules variant that
//     changes nothing about legality -- see SolitaireGame.kt's own KDoc on
//     drawThree, which confirms canPlaceOn*/cardAt never depend on it
//     either. A togglable draw-count is a reasonable follow-up, not a
//     rules gap.
//   - CardMove/setOnCardMoved's fly-to-destination animation event -- this
//     project's other multi-pile games (Mancala's seed-hop cascade aside)
//     redraw the whole board fresh after every move rather than animating
//     individual piece flight; Solitaire follows that same simpler
//     convention here.

enum class SolitaireSuit : uint8_t { CLUBS, DIAMONDS, HEARTS, SPADES };
// Ace is explicitly LOW (1), not the high-Ace (14) ranking GameSuite's own
// shared Rank.value uses for trick-taking/poker comparisons elsewhere --
// using that shared high-Ace value directly in the Kotlin source's first
// draft was a real, documented bug there (see SolitaireGame.kt's
// lowAceValue() KDoc): a foundation's `top.value + 1` never matched
// anything once an Ace (14) landed on it, permanently capping every
// foundation at one card. Defining the enum's own values as 1-13 up front
// sidesteps that whole class of bug instead of retrofitting a low-Ace
// remapping function the way the Kotlin source had to.
enum class SolitaireRank : uint8_t {
    ACE = 1, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE, TEN, JACK, QUEEN, KING
};
static const uint8_t SOLITAIRE_RANK_COUNT = 13;
static const uint8_t SOLITAIRE_SUIT_COUNT = 4;
static const uint8_t SOLITAIRE_DECK_SIZE = 52;
static const uint8_t SOLITAIRE_COLUMN_COUNT = 7;

// A column's face-up portion is always ONE continuous alternating-color,
// descending-rank run (any card added must match the current top exactly
// one rank down -- see canPlaceOnTableau()), so it can never hold more
// than a full King-to-Ace run: 13. Its face-down portion only ever
// SHRINKS (the auto-flip rule) from whatever the initial deal gave it, and
// the tallest dealt column (column 7) starts with 6 face-down cards -- so
// 6 is an exact bound too, not just a generous one.
static const uint8_t SOLITAIRE_MAX_COLUMN_FACEDOWN = 6;
static const uint8_t SOLITAIRE_MAX_COLUMN_FACEUP = 13;

// The 28 cards dealt into the tableau at the start of a hand never return
// to the stock or waste -- only cards already in the stock/waste ever move
// between those two piles (via a draw or a recycle) or leave them for good
// (via a move to a tableau column or a foundation) -- so stock+waste
// combined can only ever shrink from its own starting total of 24, never
// grow past it.
static const uint8_t SOLITAIRE_MAX_STOCKPILE = 24;

static const uint8_t SOLITAIRE_MAX_UNDO = 5;

struct SolitaireCard {
    SolitaireSuit suit;
    SolitaireRank rank;
    bool isRed() const { return suit == SolitaireSuit::DIAMONDS || suit == SolitaireSuit::HEARTS; }
};

// Maps a card's instanceId (0-51) to the card it names: id = suit*13 +
// (rank-1) -- a small fixed arithmetic encoding, same spirit as
// UnoLogic.h's unoCardById() but simpler since there are no duplicate
// ranks-per-suit to skip over.
SolitaireCard solitaireCardById(uint8_t instanceId);

// Seeds the shuffle RNG (a small self-contained xorshift32 -- see .cpp;
// kept out of Arduino's own random()/randomSeed() so this file compiles
// unchanged against native_test's no-op Arduino.h stub), exactly every
// other game's own seed*Random()'s reasoning and role. Call this once from
// setup() with real entropy (e.g. esp_random()) before the first real deal.
void seedSolitaireRandom(uint32_t seed);

// Where a pending move's card is coming from -- mirrors
// SelectionSource.kt's own sealed class (foundations are a destination
// only, never a source, exactly as there).
enum class SolitaireSelectionSource : uint8_t { NONE, WASTE, TABLEAU };

class SolitaireBoard {
public:
    // Shuffles a fresh 52-card deck and deals a new hand -- does NOT touch
    // gamesWonThisSession() (see that method's own comment) or clear the
    // undo history's PAST deals' relevance, since undo is cleared here
    // anyway (a fresh deal has nothing from a previous one to undo back
    // into, same as the Kotlin source's own startMatch() clearing its
    // history).
    void reset();

    // Like reset(), but also zeroes gamesWonThisSession() -- call this
    // once when the player FIRST enters the game (mirrors
    // SolitaireGame.init() clearing gamesWon while startMatch()/playAgain()
    // both leave it alone).
    void resetSession();

    bool isWon() const { return won; }
    uint16_t score() const { return score_; }
    // How many hands have been solved so far during this visit to the
    // game -- survives a fresh deal (New Game) but not resetSession()
    // (leaving and re-entering the game).
    uint16_t gamesWonThisSession() const { return gamesWon; }
    bool canUndo() const { return undoCount > 0; }

    // The standard "auto-complete available" check every commercial
    // Klondike implementation uses -- see SolitaireGame.kt's own KDoc on
    // autoCompleteAvailable for the exact reasoning this mirrors: once no
    // card anywhere is still face-down (every column's own face-down pile
    // is empty AND the stock is exhausted), the hand is a mathematically
    // forced win reachable via single-card moves alone.
    bool autoCompleteAvailable() const;

    // True while startAutoComplete()/autoCompleteStep() are mid-sequence --
    // the .ino should drive autoCompleteStep() repeatedly on a timer (same
    // millis()-gated pacing every other game's own AI-move scheduling
    // already uses) for as long as this stays true, one visible move at a
    // time, rather than resolving the whole thing in a single frame.
    bool isAutoCompleting() const { return autoCompleting; }

    // One-line feedback for the last tap -- see this file's own top
    // comment on why Solitaire (unlike this arcade's 2-player games)
    // exposes this rather than silently ignoring an illegal tap. Points at
    // a fixed internal buffer; valid until the next tap*()/undo()/reset()
    // call mutates it.
    const char *lastAction() const { return lastActionText; }

    // ---- Read-only board views ----------------------------------------
    uint8_t columnFaceDownCount(uint8_t col) const { return columns[col].faceDownCount; }
    uint8_t columnFaceUpCount(uint8_t col) const { return columns[col].faceUpCount; }
    // index 0 is the bottom of the face-up run, columnFaceUpCount(col)-1 is
    // the exposed, movable top card -- same bottom-to-top convention
    // TableauColumn.faceUp uses in the Kotlin source.
    SolitaireCard columnFaceUpCardAt(uint8_t col, uint8_t index) const { return solitaireCardById(columns[col].faceUp[index]); }

    uint8_t stockCount() const { return stockCount_; }
    uint8_t wasteCount() const { return wasteCount_; }
    SolitaireCard wasteTopCard() const { return solitaireCardById(waste[wasteCount_ - 1]); } // only valid when wasteCount() > 0

    // A foundation pile's content is always exactly Ace..(its own count)
    // of that one suit, in order -- there's never any ambiguity about
    // WHICH cards are on it, only how many -- so a count is the entire
    // representation this needs (no per-card storage at all).
    uint8_t foundationCount(SolitaireSuit suit) const { return foundations[(uint8_t)suit]; }

    SolitaireSelectionSource selectionSource() const { return selSource; }
    uint8_t selectionColumn() const { return selColumn; } // meaningful only when selectionSource() == TABLEAU
    // The actual card a pending selection would move, or false if there is
    // no selection right now -- a convenience so callers (Display's own
    // highlight logic, or the .ino's touch handler) don't need to re-derive
    // "which card is this selection pointing at" from selectionSource()/
    // selectionColumn() themselves.
    bool selectedCard(SolitaireCard &out) const;

    // ---- Pure legality queries -- never mutate the board, safe to call on
    // every touch event. Mirror SolitaireGame.kt's own canPlaceOnTableau()/
    // canPlaceOnFoundation() exactly. ----
    bool canPlaceOnTableau(SolitaireCard card, uint8_t destCol) const;
    bool canPlaceOnFoundation(SolitaireCard card, SolitaireSuit pile) const;

    // ---- Actions --------------------------------------------------------
    // Every tap*() method below always does SOMETHING (select, deselect,
    // move, draw, recycle, or just report why nothing happened via
    // lastAction()) -- see this file's own top comment on why there's no
    // separate "was this legal" boolean return the way this arcade's
    // 2-player games use. All are no-ops (beyond possibly updating
    // lastAction()) once isWon() or isAutoCompleting() is true.

    // Draws one card face-up onto the waste (see this file's own top
    // comment on why draw-3 is out of scope); once the stock is empty,
    // instead recycles the whole waste back into the stock, reversed, so
    // the next pass through draws in the exact same order as this one did.
    void tapStock();

    // Selects the waste's top card, or deselects it if it's already the
    // current selection.
    void tapWaste();

    // The single tap target for column `col`, whichever card in the
    // cascade was actually tapped (mirrors SolitaireGame.kt's own
    // tapTableau() -- see that file's KDoc on why there's no per-card tap
    // target within one column). Priority: deselect if this column is
    // already the selection; else attempt the move if a selection exists
    // and this column is a legal destination for it; else select this
    // column's own top card instead.
    void tapTableau(uint8_t col);

    // Attempts to move the current selection onto foundation pile `pile`.
    void tapFoundation(SolitaireSuit pile);

    // Starts the auto-complete sequence -- only takes effect if
    // autoCompleteAvailable() is actually true right now.
    void startAutoComplete();
    // Plays exactly one auto-complete move (a foundation-ready card first,
    // else one "unblocking" tableau relocation -- see .cpp), then returns;
    // stops itself (isAutoCompleting() becomes false) once the hand is won
    // or this simple heuristic runs out of moves it knows how to find, per
    // this file's own top comment. No-op if isAutoCompleting() is false.
    void autoCompleteStep();

    // Undoes the most recent mutating move (a draw/recycle, or a move onto
    // a tableau column or foundation) -- selecting/deselecting a card
    // doesn't push undo history, same as the Kotlin source's own recordHistory()
    // convention, since there'd be nothing meaningful to undo back to. No-op
    // (including before any move has been made) if canUndo() is false, so
    // wiring an Undo button straight to this call is always safe.
    void undo();

    // ---- Test/setup hooks ------------------------------------------------
    // Mirrors every other game's own test-only public seam here (see
    // CheckersLogic.h's comment on why this is a normal public method
    // rather than a friend-class/#ifdef backdoor). The shipped .ino never
    // calls any of these -- only reset()/resetSession()/the tap*()
    // methods/startAutoComplete()/autoCompleteStep()/undo().

    // Deals from a CALLER-SUPPLIED 52-card order (index 0 dealt first)
    // instead of a fresh shuffle, using the exact same dealing algorithm
    // reset() itself uses -- lets native_test verify the real deal logic
    // (which column gets how many face-down/face-up cards, and in what
    // order the remainder becomes the stock) against a known, reproducible
    // deck instead of an opaque shuffled one.
    void dealFromOrderForTest(const uint8_t order[SOLITAIRE_DECK_SIZE]);

    void setColumnForTest(uint8_t col, const uint8_t faceDownIds[], uint8_t faceDownN,
                           const uint8_t faceUpIds[], uint8_t faceUpN);
    void setStockForTest(const uint8_t ids[], uint8_t n);
    void setWasteForTest(const uint8_t ids[], uint8_t n);
    void setFoundationForTest(SolitaireSuit suit, uint8_t count);
    void setSelectionForTest(SolitaireSelectionSource source, uint8_t column);

    // Full stock/waste/face-down contents, otherwise never exposed beyond a
    // bare count -- the .ino has no reason to enumerate them (a face-down
    // card only ever draws as a back; the waste's only playable card is its
    // own top one) -- exists purely so native_test can verify full 52-card
    // conservation across every pile at once, the same reasoning
    // DominoesLogic.h's own aiHandTileForTest() test-only seam gives.
    uint8_t stockIdForTest(uint8_t index) const { return stock[index]; }
    uint8_t wasteIdForTest(uint8_t index) const { return waste[index]; }
    uint8_t columnFaceDownIdForTest(uint8_t col, uint8_t index) const { return columns[col].faceDown[index]; }

private:
    struct Column {
        uint8_t faceDown[SOLITAIRE_MAX_COLUMN_FACEDOWN];
        uint8_t faceDownCount = 0;
        uint8_t faceUp[SOLITAIRE_MAX_COLUMN_FACEUP];
        uint8_t faceUpCount = 0;
    };
    Column columns[SOLITAIRE_COLUMN_COUNT];

    uint8_t stock[SOLITAIRE_MAX_STOCKPILE];
    uint8_t stockCount_ = 0;
    uint8_t waste[SOLITAIRE_MAX_STOCKPILE];
    uint8_t wasteCount_ = 0;
    // See foundationCount()'s own comment on why a count is the whole
    // representation -- indexed by (uint8_t)SolitaireSuit.
    uint8_t foundations[SOLITAIRE_SUIT_COUNT] = {0, 0, 0, 0};

    SolitaireSelectionSource selSource = SolitaireSelectionSource::NONE;
    uint8_t selColumn = 0;

    uint16_t score_ = 0;
    bool won = false;
    bool autoCompleting = false;
    uint16_t gamesWon = 0;

    static const uint8_t LAST_ACTION_BUF_SIZE = 40;
    char lastActionText[LAST_ACTION_BUF_SIZE] = "";
    void setLastAction(const char *fmt, ...);

    // ---- Undo -------------------------------------------------------------
    // A snapshot mirrors every board field ABOVE this line exactly (not
    // gamesWon, which is session-level, not per-move -- undoing a move
    // should never un-award a games-won tally from an entirely earlier
    // deal). Stored as a fixed ring of SOLITAIRE_MAX_UNDO entries, oldest
    // overwritten once full -- same fixed-capacity-history idiom as the
    // Kotlin source's own bounded ArrayDeque, just without dynamic
    // allocation.
    struct Snapshot {
        Column columns[SOLITAIRE_COLUMN_COUNT];
        uint8_t stock[SOLITAIRE_MAX_STOCKPILE];
        uint8_t stockCount;
        uint8_t waste[SOLITAIRE_MAX_STOCKPILE];
        uint8_t wasteCount;
        uint8_t foundations[SOLITAIRE_SUIT_COUNT];
        uint16_t score;
        bool won;
    };
    Snapshot undoStack[SOLITAIRE_MAX_UNDO];
    uint8_t undoCount = 0;
    void pushUndo();

    // Shared dealing algorithm reset()/dealFromOrderForTest() both funnel
    // through -- see dealFromOrderForTest()'s own comment.
    void dealFrom(const uint8_t order[SOLITAIRE_DECK_SIZE]);

    // Removes the selected card from its source, auto-flipping the next
    // face-down card of a tableau source the instant its face-up portion
    // empties out -- mirrors removeFromSource()'s own exact behavior and
    // KDoc in the Kotlin source.
    void removeSelected();
    void applyMoveToTableau(uint8_t destCol, SolitaireCard card);
    void applyMoveToFoundation(SolitaireSuit pile, SolitaireCard card);
    void clearSelection() { selSource = SolitaireSelectionSource::NONE; selColumn = 0; }

    // Auto-complete's own two move-finders -- see autoCompleteStep()'s own
    // comment for the exact priority/heuristic this mirrors
    // (findFoundationMove()/findUnblockingTableauMove() in the Kotlin
    // source).
    bool findFoundationMove(SolitaireSelectionSource &outSource, uint8_t &outColumn, SolitaireSuit &outPile, SolitaireCard &outCard) const;
    bool findUnblockingTableauMove(uint8_t &outSrcCol, uint8_t &outDestCol) const;
};
