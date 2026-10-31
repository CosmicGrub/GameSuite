# Clubhouse parity roadmap (owner decisions 2026-10-31)

Goal: reach AND exceed Clubhouse Games: 51 Worldwide Classics in game count (51+) and in feel/multiplayer.
Owner decisions: (1) both: match the 51 count AND beat it on polish/multiplayer; (2) NO Nintendo/Mario assets, original art and names only,
generic public-domain classics; (3) voice chat comes LAST (after everything else).

Already have (overlap): Mancala, Dots and Boxes, Connect Four, Checkers, Chess, Dominoes, Backgammon, Yahtzee, Mahjong Solitaire,
Sliding Puzzle, Air Hockey, Mastermind, Solitaire (Klondike), UNO. Plus ~20 games they lack.

## Waves (in order)
1. Board classics: Gomoku, Nine Men's Morris, Hex, Chinese Checkers, Ludo, Hare and Hounds, Shogi, Mini Shogi.
2. Club layer: avatars + "Club" home, per-game coaching card (how to play + history trivia), shared CPU level in every game,
   unlockables (card decks / board themes via achievements), Mosaic-style multi-device (Air Hockey table across devices).
3. Card room: Blackjack, Texas Hold'em, President, Sevens, Speed, War, Matching, Spider Solitaire, Hanafuda.
4. Toy sports / arcade: Darts, Bowling, Carrom, Toy Curling, Billiards, Golf, Toy Soccer/Tennis/Boxing/Baseball, Slot Cars, Fishing,
   Battle Tanks, Shooting Gallery, 6-Ball Puzzle, Takoyaki-style, Pig's Tail-style.
5. Multiplayer superiority: matchmaking queue + room codes, 3-4 seat card/dice games, quick-chat reactions.
6. LAST: voice chat.

## Rules for every new game (house pattern)
GameModule + ResumableGame (validated snapshot/restore) + UndoableGame + hint + CPU levels (off main thread) + Nearby/Online via
MultiplayerTransport (host-authoritative, hidden info redacted) + Hub spec + EN/ES strings in its own <game>_strings.xml + tests.
Agents own only their game package + their own strings file; the integrator (main session) edits shared files
(GameHubSpecs, GameCatalog glyphs, GameTheme accents, MainActivity routes, GameTips).

Status: wave 3 of the previous program is still uncommitted on session/wave-3 (build green, 2660 tests). See docs/handoff/PAUSED-2026-10-18.md.
