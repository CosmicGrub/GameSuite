#pragma once
#include <Arduino.h>

// Pure game logic for Spider Solitaire -- no display/touch code here at
// all, mirroring every other game's engine/screen split so this file can be
// unit-tested on a desktop compiler with zero changes (see
// native_test/spider_playtest.cpp).
//
// UNLIKE every other game ported this session, there is no existing
// GameSuite engine to port from -- Spider Solitaire isn't in the Android
// app at all yet (see the project's own tracked roadmap memory: "Spider
// Solitaire and a few variant versions that don't exist in the Android app
// yet and need building fresh"). This is a from-scratch design against the
// standard, real-world rules of the game, same spirit as this whole ESP32
// project's own relationship to GameSuite (shared game *design*, not shared
// code) -- just with no sibling Kotlin file to cross-check against here, so
// the rules below are written out explicitly and deliberately rather than
// assumed known.
//
// STANDARD 4-SUIT SPIDER SOLITAIRE, the real full/hardest variant (not a
// beginner 1-suit or 2-suit teaching version) -- this doesn't cost anything
// extra to implement over an easier variant (the data structures and rules
// engine are identical regardless of how many distinct suits the two decks
// use; only how HARD the resulting game is for a human differs), so there's
// no reason to ship the easier version first:
//   - Two full 52-card decks combined, no jokers: 104 cards, 8 copies total
//     of each of the 13 ranks (2 copies per suit x 4 suits).
//   - 10 tableau columns. Columns 0-3 get 6 cards each (5 face-down, 1
//     face-up on top); columns 4-9 get 5 cards each (4 face-down, 1
//     face-up). 54 dealt, 50 left for the stock.
//   - Tapping the stock deals ONE new face-up card onto EACH of the 10
//     columns at once (so the stock always holds an exact multiple of 10 --
//     50/10 = 5 deals available over a hand) -- but ONLY when every column
//     already holds at least one card; dealing onto an empty column is
//     illegal (the standard rule forcing you to fill gaps with tableau play
//     before drawing more).
//   - A card may move onto another exposed card that is EXACTLY one rank
//     higher, regardless of suit (looser than Klondike's alternating-color
//     rule) -- or onto an empty column, where ANY card is accepted (no
//     King-only restriction, unlike Klondike).
//   - The instant a column's own top 13 cards form an unbroken, same-suit,
//     strictly descending King-down-to-Ace run, that run is automatically
//     swept off the tableau as one completed sequence (see
//     checkForCompletedSequence()). There are 8 such sequences to complete
//     in a 104-card deck; completing all 8 wins the hand.
//   - Emptying a column's face-up cards down to nothing auto-flips the
//     next face-down card, same idea as Klondike's own auto-flip.
//
// DELIBERATE SIMPLIFICATION, same call this project's Klondike port
// (SolitaireGame.kt's own honest MVP cut, ported into SolitaireLogic.h) and
// every other tap-only game here already makes: SINGLE-CARD MOVES ONLY, no
// dragging a same-suit run as a group. This costs Spider noticeably more
// real convenience than it cost Klondike (Spider strategy leans on
// relocating whole runs far more often), but the underlying rule is
// unchanged (a run-move is still just several individual one-rank-lower
// placements in sequence) -- a player just has to walk a run through a
// spare/empty column one card at a time instead of moving it as a group,
// the exact same "slower, not impossible" tradeoff Klondike's own single-
// card-only design accepts. No drag input model exists on this hardware at
// all, so this stays consistent with everything else here rather than being
// Spider's own special case.
//
// DROPPED entirely (no equivalent built): Klondike's auto-complete feature.
// Spider has no analogous "once every card is face-up, a forced win is
// mathematically guaranteed" property SolitaireBoard::autoCompleteAvailable()
// relies on -- completing a 13-card same-suit sequence needs real strategic
// planning, not just walking exposed cards to a foundation, so there's no
// honest, simple heuristic to offer here the way Klondike's foundation-
// first-then-one-level-unblocking one is. Most real commercial Spider
// implementations don't offer a "solve for me" button either, for the same
// underlying reason.
//
// A column's own storage is modeled differently from SolitaireLogic.h's
// Klondike columns for a real structural reason, not just style: Klondike's
// TWO separate face-down/face-up arrays work because every face-up card was
// placed by a LEGAL move, so the whole face-up portion is always one valid,
// unbroken descending run. Spider's stock deal breaks that: it drops a new
// face-up card directly on top of a column with NO placement-rule check at
// all, so the column's true face-up-since-the-last-flip portion can be
// several unrelated same-suit-or-not fragments concatenated together, not
// one clean run -- and because cards only ever LEAVE the tableau in rare,
// coarse 13-card sweeps (not one at a time onto foundations, the way
// Klondike drains its tableau), a single column's total size is only
// loosely bounded (see SPIDER_MAX_COLUMN's own comment) rather than
// Klondike's tight, exactly-derivable 19-card cap. So each column here is
// ONE combined array (bottom-to-top) plus a single `faceDownCount` boundary
// index -- simpler to reason about for this game's actual shape, and
// "flipping" a card face-up becomes nothing more than decrementing that one
// boundary index, no data movement at all.

enum class SpiderSuit : uint8_t { CLUBS, DIAMONDS, HEARTS, SPADES };
enum class SpiderRank : uint8_t {
    ACE = 1, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE, TEN, JACK, QUEEN, KING
};
static const uint8_t SPIDER_RANK_COUNT = 13;
static const uint8_t SPIDER_SUIT_COUNT = 4;
static const uint8_t SPIDER_DECK_SIZE = 104; // two full 52-card decks
static const uint8_t SPIDER_COLUMN_COUNT = 10;
static const uint8_t SPIDER_SEQUENCE_LENGTH = 13; // King down to Ace, one suit
static const uint8_t SPIDER_TOTAL_SEQUENCES = SPIDER_DECK_SIZE / SPIDER_SEQUENCE_LENGTH; // 8
static const uint8_t SPIDER_INITIAL_STOCK = 50; // 104 - 54 dealt into the tableau at the start

// See this file's own top comment on why a column only gets one loose,
// generous bound rather than an exactly-derived one: the true worst case is
// "every other column holds its required minimum and this one absorbs
// everything else it can," which a quick exact derivation doesn't simplify
// much past "the whole deck" anyway -- so this is simply the entire deck
// size, safe by construction and cheap enough in RAM (a few hundred bytes
// per column) to not be worth tightening further. A column's own starting
// FACE-DOWN count, by contrast, is exact and small (at most 5, for columns
// 0-3 -- see dealFrom()) and only ever decreases via the auto-flip rule, but
// isn't worth its own named constant since nothing sizes an array off it.
static const uint8_t SPIDER_MAX_COLUMN = SPIDER_DECK_SIZE;

static const uint8_t SPIDER_MAX_UNDO = 5;

struct SpiderCardView {
    SpiderSuit suit;
    SpiderRank rank;
};

// Maps a card's instanceId (0-103) to the card it names: two identical
// 52-card decks back to back, id = deckHalf*52 + suit*13 + (rank-1) -- the
// two decks are indistinguishable in content, so which "half" an id falls
// in has no game meaning at all, it's just what makes 104 unique ids name
// only 52 distinct (suit, rank) pairs, each appearing exactly twice.
SpiderCardView spiderCardById(uint8_t instanceId);

// Seeds the shuffle RNG (a small self-contained xorshift32 -- see .cpp;
// kept out of Arduino's own random()/randomSeed() so this file compiles
// unchanged against native_test's no-op Arduino.h stub), exactly every
// other game's own seed*Random()'s reasoning and role.
void seedSpiderRandom(uint32_t seed);

class SpiderBoard {
public:
    // Shuffles a fresh 104-card double deck and deals a new hand.
    void reset();
    // Like reset(), but also zeroes gamesWonThisSession() -- call once when
    // the player first enters the game, mirroring SolitaireBoard's own
    // reset()/resetSession() split.
    void resetSession();

    bool isWon() const { return completedSequences_ == SPIDER_TOTAL_SEQUENCES; }
    uint8_t completedSequences() const { return completedSequences_; }
    uint16_t gamesWonThisSession() const { return gamesWon; }
    bool canUndo() const { return undoCount > 0; }

    // One-line feedback for the last tap -- same deliberate deviation from
    // this arcade's usual "silently ignore an illegal tap" convention
    // SolitaireLogic.h's own top comment explains for Klondike; a solo
    // puzzle benefits from it here too.
    const char *lastAction() const { return lastActionText; }

    // ---- Read-only board views ----------------------------------------
    uint8_t columnCount(uint8_t col) const { return columns[col].count; }
    uint8_t columnFaceDownCount(uint8_t col) const { return columns[col].faceDownCount; }
    // index 0 is the bottom of the column, columnCount(col)-1 is the
    // exposed, movable top card.
    SpiderCardView columnCardAt(uint8_t col, uint8_t index) const { return spiderCardById(columns[col].cards[index]); }

    uint8_t stockCount() const { return stockCount_; }
    // True only when the stock has cards left AND every column already
    // holds at least one card -- see this file's own top comment on why
    // dealing onto an empty column is illegal.
    bool canDealFromStock() const;

    // -1 = no column currently selected; otherwise the index of the column
    // whose own top card is selected, awaiting a destination tap.
    int8_t selectedColumn() const { return selColumn; }

    // Pure query -- never mutates the board, safe to call on every touch
    // event. True if `card` could legally move onto column `destCol`'s
    // current top card right now (or into it if it's empty).
    bool canPlaceOn(SpiderCardView card, uint8_t destCol) const;

    // ---- Actions --------------------------------------------------------
    // Deals one card onto every column's own top (see canDealFromStock()),
    // or reports why it can't right now.
    void tapStock();
    // The single tap target for column `col` -- deselect if this column is
    // already the selection; else attempt the move if a selection exists
    // and this column is a legal destination; else select this column's
    // own top card instead. Mirrors SolitaireBoard::tapTableau()'s exact
    // same three-way priority.
    void tapColumn(uint8_t col);

    void undo();

    // ---- Test/setup hooks ------------------------------------------------
    // Mirrors every other game's own test-only public seam (see
    // CheckersLogic.h's comment on why this is a normal public method
    // rather than a friend-class/#ifdef backdoor). The shipped .ino never
    // calls any of these.
    void dealFromOrderForTest(const uint8_t order[SPIDER_DECK_SIZE]);
    void setColumnForTest(uint8_t col, const uint8_t cardIds[], uint8_t count, uint8_t faceDownCount);
    void setStockForTest(const uint8_t ids[], uint8_t n);
    void setSelectionForTest(int8_t col);
    // Directly sets how many sequences are already banked -- a convenience
    // for testing win detection without needing to hand-build 8 full,
    // real 13-card same-suit runs first.
    void setCompletedSequencesForTest(uint8_t n) { completedSequences_ = n; }

    // Raw instanceIds (0-103), not decoded (suit, rank) views -- needed for
    // native_test's card-conservation check specifically: unlike every
    // other card game here, Spider legitimately has TWO copies of every
    // (suit, rank) pair, so knowing a card's decoded identity alone can't
    // distinguish "the same physical card counted twice" from "the two
    // genuinely different id'd copies that happen to share a suit and
    // rank" -- only the raw id tells them apart. The .ino never needs this
    // (Display only ever draws the decoded view).
    uint8_t columnCardIdForTest(uint8_t col, uint8_t index) const { return columns[col].cards[index]; }
    uint8_t stockIdForTest(uint8_t index) const { return stock[index]; }

private:
    struct Column {
        uint8_t cards[SPIDER_MAX_COLUMN];
        uint8_t count = 0;
        uint8_t faceDownCount = 0;
    };
    Column columns[SPIDER_COLUMN_COUNT];

    uint8_t stock[SPIDER_INITIAL_STOCK];
    uint8_t stockCount_ = 0;

    uint8_t completedSequences_ = 0;
    int8_t selColumn = -1;
    uint16_t gamesWon = 0;

    static const uint8_t LAST_ACTION_BUF_SIZE = 40;
    char lastActionText[LAST_ACTION_BUF_SIZE] = "";
    void setLastAction(const char *fmt, ...);

    // A snapshot mirrors every board field above (not gamesWon, which is
    // session-level, not per-move -- same reasoning as SolitaireLogic.h's
    // own Snapshot). Fixed ring of SPIDER_MAX_UNDO entries, oldest
    // overwritten once full.
    struct Snapshot {
        Column columns[SPIDER_COLUMN_COUNT];
        uint8_t stock[SPIDER_INITIAL_STOCK];
        uint8_t stockCount;
        uint8_t completedSequences;
    };
    Snapshot undoStack[SPIDER_MAX_UNDO];
    uint8_t undoCount = 0;
    void pushUndo();

    void dealFrom(const uint8_t order[SPIDER_DECK_SIZE]);
    void clearSelection() { selColumn = -1; }
    // Checks column `col`'s own top SPIDER_SEQUENCE_LENGTH cards for a
    // complete same-suit King-to-Ace run and sweeps it off (incrementing
    // completedSequences_, auto-flipping the column's new top card if
    // needed) if so. Called after every move/deal that could have just
    // completed one.
    void checkForCompletedSequence(uint8_t col);
};
