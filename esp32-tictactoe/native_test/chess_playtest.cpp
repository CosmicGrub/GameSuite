// Native playtest driver for the ESP32 Chess game's ChessLogic.h/.cpp AND
// ChessDisplay.cpp's pure hit-testing math -- compiles and runs the EXACT,
// unmodified files shipped in esp32-tictactoe/TicTacToeESP32/ on a desktop
// (against the same no-op TFT_eSPI stub playtest.cpp already uses), so the
// rules engine, AI, and touch-math can all be exercised thousands of times
// in a few seconds instead of only via slow manual taps on real hardware.
// This does NOT and CANNOT verify actual pixel rendering, touch
// calibration, or any real SPI/TFT_eSPI behavior -- only the physical
// board can confirm those.
//
// Verification strategy, mirroring this project's existing playtest.cpp in
// spirit but scaled to chess's much larger state space:
//   1) Perft (move-count) verification from the standard starting position
//      against the well-known correct values (perft(1)=20, perft(2)=400,
//      perft(3)=8902) -- the standard way to catch move-generation bugs,
//      since a single off-by-one in ANY piece's move rules, castling, or en
//      passant will almost always shift these counts.
//   2) Targeted positions built directly via ChessBoard's test-only
//      setupEmpty()/setPiece() seam (see ChessLogic.h) for castling rights
//      (both the positive and negative cases), en passant (both capturing
//      it and it expiring after one move), promotion, check, checkmate,
//      stalemate, and a pinned piece having zero legal moves -- built
//      directly rather than via a long hand-written move sequence from the
//      opening, so there's no risk of the TEST ITSELF containing a
//      transcription mistake that reaches the wrong position.
//   3) One realistic full game (Fool's Mate) played via real moves from the
//      standard opening, confirming checkmate detection also fires
//      correctly at the end of an actual line of play, not just a
//      hand-placed position.
//   4) Two direct AI sanity checks -- the AI actually takes a free hanging
//      piece, and actually resolves its own queen being attacked profitably
//      -- concretely testing the "doesn't hang pieces for free" bar this
//      project's task set for a genuine, non-random opponent.
//   5) A many-hundred-game random-vs-random self-play simulation checking
//      for crashes, illegal states (wrong side to move, wrong king count,
//      material ever increasing), and non-termination within a generous
//      move cap -- see that section for why hitting the cap is treated as
//      an accepted outcome, not a failure, given this engine's documented
//      scope (no insufficient-material/repetition/50-move draws).

#include <cstdio>
#include <cstdlib>
#include <random>
#include <chrono>
// Includes the REAL, shipped files from ../TicTacToeESP32/ directly (never a
// duplicated copy) so this test always exercises exactly what gets flashed
// to the board -- see native_test/README.md for how to build/run it.
#include "../TicTacToeESP32/ChessLogic.h"
#include "../TicTacToeESP32/ChessDisplay.h"
#include "../TicTacToeESP32/Chrome.h"
#include "../TicTacToeESP32/Config.h"

// Square helper for test readability: S('e', 1) == e1's square index (4).
static uint8_t S(char file, int rank) { return (uint8_t)((rank - 1) * 8 + (file - 'a')); }

static char glyph(ChessPiece p) {
    if (p.type == CP_NONE) return '.';
    char upper;
    switch (p.type) {
        case CP_PAWN:   upper = 'P'; break;
        case CP_KNIGHT: upper = 'N'; break;
        case CP_BISHOP: upper = 'B'; break;
        case CP_ROOK:   upper = 'R'; break;
        case CP_QUEEN:  upper = 'Q'; break;
        case CP_KING:   upper = 'K'; break;
        default:        upper = '?'; break;
    }
    return (p.color == CC_WHITE) ? upper : (char)(upper + ('a' - 'A'));
}

static void printBoard(const ChessBoard &b) {
    for (int row = 7; row >= 0; row--) {
        printf(" ");
        for (int col = 0; col < 8; col++) printf("%c ", glyph(b.at(row * 8 + col)));
        printf("\n");
    }
}

static int checksRun = 0, checksFailed = 0;
static void check(const char *label, bool condition) {
    checksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) checksFailed++;
}

// ---------------------------------------------------------------------------
// 1) Perft -- counts the total number of legal move sequences of exactly
//    `depth` plies from `board`'s current position, via the exact same
//    generateLegalMoves()/applyMove() path playHuman()/playAi() use
//    internally (through the public legalMoveList()/playMove() test seam).
// ---------------------------------------------------------------------------
static long long perft(ChessBoard board, int depth) {
    if (depth == 0) return 1;
    uint8_t from[218], to[218];
    uint8_t n = board.legalMoveList(from, to);
    if (depth == 1) return n;
    long long total = 0;
    for (uint8_t i = 0; i < n; i++) {
        ChessBoard next = board;
        next.playMove(from[i], to[i]);
        total += perft(next, depth - 1);
    }
    return total;
}

static void perftTests() {
    printf("=== Perft (move-count) verification from the standard starting position ===\n");
    ChessBoard b;
    b.reset();

    struct { int depth; long long expected; } cases[] = {
        {1, 20}, {2, 400}, {3, 8902},
    };
    for (auto &c : cases) {
        auto t0 = std::chrono::steady_clock::now();
        long long got = perft(b, c.depth);
        auto t1 = std::chrono::steady_clock::now();
        double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        char label[64];
        snprintf(label, sizeof(label), "perft(%d) == %lld (got %lld, %.1fms)", c.depth, c.expected, got, ms);
        check(label, got == c.expected);
    }

    // Depth 4 (expected 197281) is NOT part of this project's required bar
    // (the task asked for depth 2-3) -- run it anyway as a bonus data point
    // on both correctness and how search cost grows, but don't fail the
    // suite if it's merely slow.
    auto t0 = std::chrono::steady_clock::now();
    long long p4 = perft(b, 4);
    auto t1 = std::chrono::steady_clock::now();
    double ms4 = std::chrono::duration<double, std::milli>(t1 - t0).count();
    printf("  [INFO] perft(4) = %lld (expected 197281), %.1fms -- bonus check, not required\n", p4, ms4);
    if (p4 != 197281) printf("  [WARN] perft(4) mismatch -- investigate if this regresses\n");

    printf("\n");
}

// ---------------------------------------------------------------------------
// 2) Targeted positions -- castling rights, en passant, promotion, check,
//    checkmate, stalemate, pins. Built directly via setupEmpty()/setPiece()
//    so each position is exactly what it says, with no risk of a
//    transcription mistake in a long move sequence landing somewhere else.
// ---------------------------------------------------------------------------
static bool listContains(ChessBoard &b, uint8_t from, uint8_t to) {
    uint8_t f[218], t[218];
    uint8_t n = b.legalMoveList(f, t);
    for (uint8_t i = 0; i < n; i++) if (f[i] == from && t[i] == to) return true;
    return false;
}

static void castlingTests() {
    printf("=== Castling rights + legality checks ===\n");

    // Plain kingside castle is legal and actually relocates the rook.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('h', 1), CP_ROOK, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setCastlingRights(true, false, false, false);
        b.setSideToMove(CC_WHITE);
        check("kingside castle is offered as a legal move", listContains(b, S('e', 1), S('g', 1)));
        bool ok = b.playMove(S('e', 1), S('g', 1));
        check("kingside castle is accepted", ok);
        check("king landed on g1", b.at(S('g', 1)).type == CP_KING && b.at(S('g', 1)).color == CC_WHITE);
        check("rook relocated to f1", b.at(S('f', 1)).type == CP_ROOK && b.at(S('f', 1)).color == CC_WHITE);
        check("e1 and h1 are now empty", b.at(S('e', 1)).type == CP_NONE && b.at(S('h', 1)).type == CP_NONE);
    }

    // Cannot castle out of check.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('h', 1), CP_ROOK, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setPiece(S('e', 5), CP_ROOK, CC_BLACK); // checks e1 along the e-file
        b.setCastlingRights(true, false, false, false);
        b.setSideToMove(CC_WHITE);
        check("king is actually in check in this setup", b.inCheck());
        check("cannot castle out of check", !listContains(b, S('e', 1), S('g', 1)));
    }

    // Cannot castle through an attacked square.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('h', 1), CP_ROOK, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setPiece(S('f', 8), CP_ROOK, CC_BLACK); // attacks f1, the transit square
        b.setCastlingRights(true, false, false, false);
        b.setSideToMove(CC_WHITE);
        check("not currently in check (sanity)", !b.inCheck());
        check("cannot castle through an attacked square", !listContains(b, S('e', 1), S('g', 1)));
    }

    // Cannot castle into an attacked square.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('h', 1), CP_ROOK, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setPiece(S('g', 8), CP_ROOK, CC_BLACK); // attacks g1, the landing square
        b.setCastlingRights(true, false, false, false);
        b.setSideToMove(CC_WHITE);
        check("cannot castle into an attacked square", !listContains(b, S('e', 1), S('g', 1)));
    }

    // Cannot castle queenside through an occupied square.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('a', 1), CP_ROOK, CC_WHITE);
        b.setPiece(S('c', 1), CP_BISHOP, CC_WHITE); // blocks the queenside path
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setCastlingRights(false, true, false, false);
        b.setSideToMove(CC_WHITE);
        check("cannot castle queenside through an occupied square", !listContains(b, S('e', 1), S('c', 1)));
    }

    // Rights are lost on the king's own move and STAY lost even after moving
    // back to the original squares.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('h', 1), CP_ROOK, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setCastlingRights(true, false, false, false);
        b.setSideToMove(CC_WHITE);
        b.playMove(S('e', 1), S('e', 2));
        b.playMove(S('e', 8), S('e', 7));
        b.playMove(S('e', 2), S('e', 1));
        b.playMove(S('e', 7), S('e', 8));
        check("king and rook are back on their original squares",
              b.at(S('e', 1)).type == CP_KING && b.at(S('h', 1)).type == CP_ROOK);
        check("castling rights do NOT come back after moving the king away and back",
              !listContains(b, S('e', 1), S('g', 1)));
    }

    // Rights are lost per-side when THAT rook moves, independent of the
    // other side's rights.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('h', 1), CP_ROOK, CC_WHITE);
        b.setPiece(S('a', 1), CP_ROOK, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setCastlingRights(true, true, false, false);
        b.setSideToMove(CC_WHITE);
        b.playMove(S('h', 1), S('h', 3));
        b.playMove(S('e', 8), S('e', 7));
        b.playMove(S('h', 3), S('h', 1));
        b.playMove(S('e', 7), S('e', 8));
        check("kingside rights lost after that rook moved (even having returned)",
              !listContains(b, S('e', 1), S('g', 1)));
        check("queenside rights untouched by the kingside rook moving",
              listContains(b, S('e', 1), S('c', 1)));
    }

    // Rights are lost when that corner's rook is CAPTURED, regardless of
    // what did the capturing -- and the flag itself is what's checked here,
    // not just "is there a rook," by putting a different rook back on h1
    // afterward and confirming castling is still refused.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('h', 1), CP_ROOK, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setPiece(S('g', 3), CP_KNIGHT, CC_BLACK); // knight on g3 attacks h1
        b.setCastlingRights(true, false, false, false);
        b.setSideToMove(CC_BLACK);
        bool captured = b.playMove(S('g', 3), S('h', 1));
        check("knight captures the h1 rook", captured && b.at(S('h', 1)).color == CC_BLACK);
        b.setPiece(S('h', 1), CP_ROOK, CC_WHITE); // put a fresh rook back, isolating the rights flag from mere rook presence
        check("kingside castling rights stay lost after that rook was captured, even with a new rook on h1",
              !listContains(b, S('e', 1), S('g', 1)));
    }

    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);
}

static void enPassantTests() {
    printf("=== En passant checks ===\n");

    // Capturing en passant immediately after the double push.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setPiece(S('e', 5), CP_PAWN, CC_WHITE);
        b.setPiece(S('d', 7), CP_PAWN, CC_BLACK);
        b.setSideToMove(CC_BLACK);
        b.playMove(S('d', 7), S('d', 5)); // double push -- sets the en-passant target
        check("en passant capture is offered right after the double push", listContains(b, S('e', 5), S('d', 6)));
        bool ok = b.playMove(S('e', 5), S('d', 6));
        check("en passant capture is accepted", ok);
        check("the captured pawn (on d5, not d6) is gone", b.at(S('d', 5)).type == CP_NONE);
        check("the capturing pawn landed on d6", b.at(S('d', 6)).type == CP_PAWN && b.at(S('d', 6)).color == CC_WHITE);
    }

    // The en-passant window closes after one move if not taken immediately.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setPiece(S('e', 5), CP_PAWN, CC_WHITE);
        b.setPiece(S('d', 7), CP_PAWN, CC_BLACK);
        b.setSideToMove(CC_BLACK);
        b.playMove(S('d', 7), S('d', 5));
        b.playMove(S('e', 1), S('e', 2)); // White does something else instead of capturing en passant
        b.playMove(S('e', 8), S('e', 7)); // Black filler move
        check("en passant is no longer available one move later", !listContains(b, S('e', 5), S('d', 6)));
    }

    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);
}

static void promotionTests() {
    printf("=== Promotion checks (always auto-queen) ===\n");

    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setPiece(S('a', 7), CP_PAWN, CC_WHITE);
        b.setSideToMove(CC_WHITE);
        bool ok = b.playMove(S('a', 7), S('a', 8));
        check("pushing a pawn to the back rank is accepted", ok);
        check("it arrives as a queen", b.at(S('a', 8)).type == CP_QUEEN && b.at(S('a', 8)).color == CC_WHITE);
        check("the source square is empty", b.at(S('a', 7)).type == CP_NONE);
    }

    // Promotion via capture.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setPiece(S('a', 7), CP_PAWN, CC_WHITE);
        b.setPiece(S('b', 8), CP_ROOK, CC_BLACK);
        b.setSideToMove(CC_WHITE);
        bool ok = b.playMove(S('a', 7), S('b', 8));
        check("capturing onto the back rank is accepted", ok);
        check("it arrives as a queen after capturing", b.at(S('b', 8)).type == CP_QUEEN && b.at(S('b', 8)).color == CC_WHITE);
    }

    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);
}

static void checkCheckmateStalemateTests() {
    printf("=== Check / checkmate / stalemate detection ===\n");

    // Plain check, game still in progress.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setPiece(S('e', 5), CP_ROOK, CC_BLACK);
        b.setSideToMove(CC_WHITE);
        check("king is in check", b.inCheck());
        check("game is still in progress (king can step aside)", b.result() == ChessRoundResult::IN_PROGRESS);
    }

    // Constructed back-rank checkmate.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('g', 1), CP_KING, CC_WHITE);
        b.setPiece(S('f', 2), CP_PAWN, CC_WHITE);
        b.setPiece(S('g', 2), CP_PAWN, CC_WHITE);
        b.setPiece(S('h', 2), CP_PAWN, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setPiece(S('a', 1), CP_ROOK, CC_BLACK);
        b.setSideToMove(CC_WHITE);
        check("back-rank mate is detected as checkmate (AI/Black wins)", b.result() == ChessRoundResult::AI_WINS);
        uint8_t from[218], to[218];
        check("checkmate implies zero legal moves for the mated side", b.legalMoveList(from, to) == 0);
    }

    // Constructed minimal stalemate: White king boxed into a1 with no
    // legal moves and NOT in check.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('a', 1), CP_KING, CC_WHITE);
        b.setPiece(S('c', 1), CP_KING, CC_BLACK);
        b.setPiece(S('b', 3), CP_QUEEN, CC_BLACK);
        b.setSideToMove(CC_WHITE);
        check("not in check in the stalemate position", !b.inCheck());
        check("stalemate is detected as a draw", b.result() == ChessRoundResult::DRAW_STALEMATE);
    }

    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);
}

static void pinTest() {
    printf("=== Pin enforcement ===\n");
    ChessBoard b;
    b.setupEmpty();
    b.setPiece(S('e', 1), CP_KING, CC_WHITE);
    b.setPiece(S('e', 4), CP_KNIGHT, CC_WHITE); // pinned to the king along the e-file
    b.setPiece(S('e', 7), CP_ROOK, CC_BLACK);
    b.setPiece(S('a', 8), CP_KING, CC_BLACK);
    b.setSideToMove(CC_WHITE);

    uint8_t dests[32];
    uint8_t n = b.legalDestinations(S('e', 4), dests);
    check("a pinned knight has zero legal destinations (any knight move leaves its own king in check)", n == 0);
    check("the king itself still has legal moves in this position", b.result() == ChessRoundResult::IN_PROGRESS);
    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);
}

// ---------------------------------------------------------------------------
// 3) A real game: Fool's Mate (the fastest possible checkmate), played via
//    actual moves from the standard opening rather than a hand-placed
//    position, confirming checkmate detection also fires correctly at the
//    end of a real line of play.
// ---------------------------------------------------------------------------
static void foolsMateTest() {
    printf("=== Fool's Mate, played from the standard opening via real moves ===\n");
    ChessBoard b;
    b.reset();
    bool m1 = b.playMove(S('f', 2), S('f', 3));
    bool m2 = b.playMove(S('e', 7), S('e', 5));
    bool m3 = b.playMove(S('g', 2), S('g', 4));
    bool m4 = b.playMove(S('d', 8), S('h', 4));
    check("all four moves of the line were accepted as legal", m1 && m2 && m3 && m4);
    if (!(m1 && m2 && m3 && m4)) printBoard(b);
    check("White is checkmated (AI/Black wins)", b.result() == ChessRoundResult::AI_WINS);
    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);
}

// ---------------------------------------------------------------------------
// 4) AI sanity: a genuine opponent doesn't hang pieces for free, and takes
//    free material when it's offered.
// ---------------------------------------------------------------------------
static void aiSanityTests() {
    printf("=== AI sanity checks (takes free material, doesn't hang its own) ===\n");

    // A hanging White queen the AI (Black) can simply capture with a knight.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('e', 1), CP_KING, CC_WHITE);
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setPiece(S('d', 5), CP_QUEEN, CC_WHITE); // undefended
        b.setPiece(S('b', 6), CP_KNIGHT, CC_BLACK); // attacks d5
        b.setSideToMove(CC_BLACK);
        b.playAi();
        check("AI captures a free hanging queen instead of playing something else",
              b.at(S('d', 5)).type == CP_KNIGHT && b.at(S('d', 5)).color == CC_BLACK);
    }

    // Black's own queen is attacked by an UNDEFENDED rook along an
    // otherwise-open file; capturing it resolves the attack AND wins
    // material outright. The White king sits far away (a8, not d1) so this
    // is a clean win, not a trap -- an earlier version of this test placed
    // the king adjacent to the rook, where recapturing would actually have
    // LOST the exchange (queen for rook), and the AI correctly declined
    // that "bait" instead of grabbing it, which briefly looked like a bug in
    // the AI when it was really a bug in the test.
    {
        ChessBoard b;
        b.setupEmpty();
        b.setPiece(S('a', 8), CP_KING, CC_WHITE); // far from d1 -- no recapture possible
        b.setPiece(S('e', 8), CP_KING, CC_BLACK);
        b.setPiece(S('d', 1), CP_ROOK, CC_WHITE); // undefended
        b.setPiece(S('d', 4), CP_QUEEN, CC_BLACK); // attacked by the rook along the d-file
        b.setSideToMove(CC_BLACK);
        b.playAi();
        check("AI resolves its attacked queen by capturing the undefended attacker for free",
              b.at(S('d', 1)).type == CP_QUEEN && b.at(S('d', 1)).color == CC_BLACK);
    }

    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);
}

// ---------------------------------------------------------------------------
// 5) Random-vs-random self-play: many games, both sides picking uniformly
//    at random among their own legal moves (via the unrestricted
//    legalMoveList()/playMove() test seam, not playHuman()/playAi() --
//    this section deliberately does NOT exercise the AI search, only the
//    rules engine's robustness under heavy, unpredictable use), checking
//    for crashes, illegal states, and non-termination.
//
// A move cap per game is REQUIRED, not just a safety margin: this engine
// does not detect insufficient-material draws (see ChessLogic.cpp's top
// comment), so a game that randomly trades down to bare kings (or another
// dead-drawn material balance) can never reach checkmate or stalemate and
// would otherwise loop forever. Hitting the cap is therefore an ACCEPTED,
// expected outcome here, not a failure -- it is specifically what that
// documented scope gap looks like when it's actually exercised.
// ---------------------------------------------------------------------------
static void randomSelfPlayTest() {
    printf("=== Random-vs-random self-play (crash/illegal-state/non-termination check) ===\n");
    const int GAMES = 400;
    const int MOVE_CAP = 300; // half-moves

    std::mt19937 rng(12345); // fixed seed -- reproducible test runs
    long checkmates = 0, stalemates = 0, cappedGames = 0;
    long totalPlies = 0;
    long minPlies = MOVE_CAP + 1, maxPlies = 0;
    int invariantFailures = 0;

    for (int g = 0; g < GAMES; g++) {
        ChessBoard b;
        b.reset();
        int ply = 0;
        for (; ply < MOVE_CAP; ply++) {
            ChessRoundResult r = b.result();
            if (r != ChessRoundResult::IN_PROGRESS) {
                if (r == ChessRoundResult::DRAW_STALEMATE) stalemates++;
                else checkmates++;
                break;
            }

            // Invariant checks, every ply: exactly one king per side, and
            // total piece count never increases (captures only remove
            // pieces; promotion/castling don't change the count).
            int whiteKings = 0, blackKings = 0, pieceCount = 0;
            for (uint8_t sq = 0; sq < 64; sq++) {
                ChessPiece p = b.at(sq);
                if (p.type == CP_NONE) continue;
                pieceCount++;
                if (p.type == CP_KING) { if (p.color == CC_WHITE) whiteKings++; else blackKings++; }
            }
            if (whiteKings != 1 || blackKings != 1) {
                invariantFailures++;
                printf("  [FAIL] game %d ply %d: expected exactly one king per side, got white=%d black=%d\n",
                       g, ply, whiteKings, blackKings);
                printBoard(b);
            }

            uint8_t from[218], to[218];
            uint8_t n = b.legalMoveList(from, to);
            if (n == 0) {
                // result() said IN_PROGRESS but there's no legal move --
                // a genuine contradiction, not just a slow game.
                invariantFailures++;
                printf("  [FAIL] game %d ply %d: result()==IN_PROGRESS but zero legal moves\n", g, ply);
                break;
            }
            std::uniform_int_distribution<int> pick(0, (int)n - 1);
            uint8_t idx = (uint8_t)pick(rng);
            bool ok = b.playMove(from[idx], to[idx]);
            if (!ok) {
                invariantFailures++;
                printf("  [FAIL] game %d ply %d: playMove rejected a move legalMoveList itself just offered\n", g, ply);
                break;
            }
        }
        if (ply >= MOVE_CAP) cappedGames++;
        totalPlies += ply;
        if (ply < minPlies) minPlies = ply;
        if (ply > maxPlies) maxPlies = ply;
    }

    printf("  Games played: %d\n", GAMES);
    printf("  Checkmates: %ld, Stalemates: %ld, Hit move cap (%d) without a result: %ld\n",
           checkmates, stalemates, MOVE_CAP, cappedGames);
    printf("  Plies per game -- min: %ld, max: %ld, average: %.1f\n",
           minPlies, maxPlies, (double)totalPlies / GAMES);
    printf("  (Games hitting the move cap are expected, not bugs -- see this function's\n");
    printf("   top comment: insufficient-material draws are out of scope, so a random\n");
    printf("   game that trades down to a dead-drawn material balance runs forever.)\n");

    check("no crashes, illegal states, or move-generation contradictions across all games", invariantFailures == 0);
    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);
}

// ---------------------------------------------------------------------------
// 6) Touch hit-testing: ChessDisplay.cpp's computeChessLayout()/
//    hitTestSquare()/hitTestChessPlayAgainButton() are pure math with no
//    TFT_eSPI calls -- checked the same way playtest.cpp checks
//    Tic-Tac-Toe's and checkers_playtest.cpp checks Checkers'.
// ---------------------------------------------------------------------------
static int layoutChecksRun = 0, layoutChecksFailed = 0;
static void checkLayout(const char *label, bool condition) {
    layoutChecksRun++;
    printf("  [%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) layoutChecksFailed++;
}

static void touchHitTestChecks() {
    printf("=== Touch hit-testing checks (ChessDisplay.cpp, against Config.h's real screen size) ===\n");
    ChessLayout l = computeChessLayout();
    int16_t boardSize = l.squareSize * 8;

    printf("  layout: board at (%d,%d), square=%dpx, button at (%d,%d) %dx%d\n",
           l.boardX, l.boardY, l.squareSize, l.buttonX, l.buttonY, l.buttonW, l.buttonH);

    checkLayout("board fits within the screen width", l.boardX >= 0 && l.boardX + boardSize <= SCREEN_WIDTH);
    checkLayout("board fits within the screen height (below the chrome bar)",
                l.boardY >= l.statusY + l.statusH && l.boardY + boardSize <= SCREEN_HEIGHT);
    checkLayout("play-again button fits within the screen",
                l.buttonX >= 0 && l.buttonX + l.buttonW <= SCREEN_WIDTH &&
                l.buttonY >= 0 && l.buttonY + l.buttonH <= SCREEN_HEIGHT);

    // Every square's own center must resolve back to that same (row, col),
    // AND the row/col must round-trip through the board's bottom-up screen
    // orientation correctly (a1 -- row 0, col 0 -- must be the BOTTOM-LEFT
    // square on screen, not the top-left).
    bool allCentersOk = true;
    for (uint8_t row = 0; row < 8; row++) {
        for (uint8_t col = 0; col < 8; col++) {
            int16_t cx = l.boardX + col * l.squareSize + l.squareSize / 2;
            int16_t cy = l.boardY + (7 - row) * l.squareSize + l.squareSize / 2;
            uint8_t hr, hc;
            bool ok = hitTestSquare(l, cx, cy, hr, hc) && hr == row && hc == col;
            if (!ok) {
                allCentersOk = false;
                printf("    square (row=%d,col=%d) center (%d,%d) resolved to (row=%d,col=%d)\n", row, col, cx, cy, hr, hc);
            }
        }
    }
    checkLayout("every square's own center taps that same (row,col), bottom-up orientation included", allCentersOk);
    checkLayout("a1 (row0,col0) is the bottom-left square on screen",
                [&]() {
                    int16_t x, y;
                    x = l.boardX + l.squareSize / 2;
                    y = l.boardY + 7 * l.squareSize + l.squareSize / 2; // bottom row on screen
                    uint8_t hr, hc;
                    return hitTestSquare(l, x, y, hr, hc) && hr == 0 && hc == 0;
                }());

    // Sweep every pixel inside the board and confirm no gaps/overlaps.
    bool allInBoundsPixelsResolve = true;
    for (int16_t x = l.boardX; x < l.boardX + boardSize; x++) {
        for (int16_t y = l.boardY; y < l.boardY + boardSize; y++) {
            uint8_t hr, hc;
            if (!hitTestSquare(l, x, y, hr, hc) || hr > 7 || hc > 7) allInBoundsPixelsResolve = false;
        }
    }
    checkLayout("every in-bounds board pixel resolves to a valid 0-7,0-7 square (no gaps/overlaps)", allInBoundsPixelsResolve);

    uint8_t discard;
    checkLayout("1px left of the board is not a hit", !hitTestSquare(l, l.boardX - 1, l.boardY + 1, discard, discard));
    checkLayout("1px above the board is not a hit", !hitTestSquare(l, l.boardX + 1, l.boardY - 1, discard, discard));
    checkLayout("1px right of the board is not a hit", !hitTestSquare(l, l.boardX + boardSize, l.boardY + 1, discard, discard));
    checkLayout("1px below the board is not a hit", !hitTestSquare(l, l.boardX + 1, l.boardY + boardSize, discard, discard));

    int16_t bcx = l.buttonX + l.buttonW / 2, bcy = l.buttonY + l.buttonH / 2;
    checkLayout("play-again button's own center is a hit", hitTestChessPlayAgainButton(l, bcx, bcy));
    checkLayout("1px left of the button is not a hit", !hitTestChessPlayAgainButton(l, l.buttonX - 1, bcy));
    checkLayout("1px above the button is not a hit", !hitTestChessPlayAgainButton(l, bcx, l.buttonY - 1));
    checkLayout("1px right of the button is not a hit", !hitTestChessPlayAgainButton(l, l.buttonX + l.buttonW, bcy));
    checkLayout("1px below the button is not a hit", !hitTestChessPlayAgainButton(l, bcx, l.buttonY + l.buttonH));

    printf("Layout checks run: %d, failed: %d\n\n", layoutChecksRun, layoutChecksFailed);
}

// ---------------------------------------------------------------------------
// 7) A quick reset()/opening sanity pass -- not a substitute for perft, but
//    a fast, readable confirmation that the standard position and a couple
//    of well-known opening move counts look right before the heavier tests
//    run.
// ---------------------------------------------------------------------------
static void openingSanityChecks() {
    printf("=== reset()/opening sanity checks ===\n");
    ChessBoard b;
    b.reset();
    check("White to move first", b.isHumanTurn());
    check("game starts in progress", b.result() == ChessRoundResult::IN_PROGRESS);
    check("a1 is a White rook", b.at(S('a', 1)).type == CP_ROOK && b.at(S('a', 1)).color == CC_WHITE);
    check("e1 is the White king", b.at(S('e', 1)).type == CP_KING && b.at(S('e', 1)).color == CC_WHITE);
    check("d8 is the Black queen", b.at(S('d', 8)).type == CP_QUEEN && b.at(S('d', 8)).color == CC_BLACK);
    check("e8 is the Black king", b.at(S('e', 8)).type == CP_KING && b.at(S('e', 8)).color == CC_BLACK);

    uint8_t dests[32];
    check("b1 knight has exactly 2 legal moves in the opening", b.legalDestinations(S('b', 1), dests) == 2);
    check("e2 pawn has exactly 2 legal moves in the opening (single + double push)",
          b.legalDestinations(S('e', 2), dests) == 2);
    check("e1 king has zero legal moves in the opening (boxed in)", b.legalDestinations(S('e', 1), dests) == 0);

    printf("Checks run: %d, failed: %d\n\n", checksRun, checksFailed);
}

// ---------------------------------------------------------------------------
// 8) AI search performance sample -- informational only (no pass/fail),
//    reported here because ChessLogic.cpp's top comment explicitly says
//    real per-move timing was never measured on actual ESP32 hardware, only
//    estimated from node counts; these are the concrete desktop numbers
//    that estimate was based on.
// ---------------------------------------------------------------------------
static void performanceSample() {
    printf("=== AI search performance sample (informational -- see ChessLogic.cpp's\n");
    printf("    top comment for why real ESP32 timing is an estimate, not a measurement) ===\n");

    {
        ChessBoard b;
        b.reset();
        b.playMove(S('e', 2), S('e', 4));
        auto t0 = std::chrono::steady_clock::now();
        b.playAi();
        auto t1 = std::chrono::steady_clock::now();
        double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        printf("  [INFO] AI's reply to 1.e4 (depth %d) took %.1fms on this desktop\n", 3, ms);
    }
    {
        std::mt19937 rng(999);
        ChessBoard b;
        b.reset();
        for (int i = 0; i < 5 && b.result() == ChessRoundResult::IN_PROGRESS; i++) {
            uint8_t from[218], to[218];
            uint8_t n = b.legalMoveList(from, to);
            if (n == 0) break;
            std::uniform_int_distribution<int> pick(0, (int)n - 1);
            uint8_t idx = (uint8_t)pick(rng); // same index for both arrays -- (from[idx], to[idx]) is ONE move
            b.playMove(from[idx], to[idx]);
        }
        if (b.result() == ChessRoundResult::IN_PROGRESS && !b.isHumanTurn()) {
            auto t0 = std::chrono::steady_clock::now();
            b.playAi();
            auto t1 = std::chrono::steady_clock::now();
            double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
            printf("  [INFO] AI's move after 5 random plies (depth %d) took %.1fms on this desktop\n", 3, ms);
        }
    }
    printf("\n");
}

int main() {
    openingSanityChecks();
    perftTests();
    castlingTests();
    enPassantTests();
    promotionTests();
    checkCheckmateStalemateTests();
    pinTest();
    foolsMateTest();
    aiSanityTests();
    randomSelfPlayTest();
    performanceSample();
    touchHitTestChecks();

    bool allGood = (checksFailed == 0) && (layoutChecksFailed == 0);
    printf("=== OVERALL: %s (%d/%d correctness checks passed, %d/%d layout checks passed) ===\n",
           allGood ? "ALL PLAYTEST CHECKS PASSED" : "FAILURES FOUND -- SEE ABOVE",
           checksRun - checksFailed, checksRun, layoutChecksRun - layoutChecksFailed, layoutChecksRun);
    return allGood ? 0 : 1;
}
