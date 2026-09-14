# Party Toolkit — Design

**Status:** Approved by the project owner via brainstorming — implemented and shipped as
`games/partytoolkit/PartyToolkitGame.kt` + `ui/PartyToolkitScreen.kt` (README Roadmap item 21),
with two revisions from this doc's original call (tabs instead of a card grid, independent
per-tool player lists instead of one shared roster — see **Architecture**/**Player lists** below)
**Date:** September 2026
**Deciders:** the project owner

## Context

Eighth item in the new-games batch (`docs/NEW_GAMES_BRAINSTORM.md`), picking up its own
"Boardgame Pal's utility toolkit" entry. That entry already established the core scope call —
Dice, Coin Toss, Random Letter, Scoreboard, Life Points, Hourglass, First Player, and Teams are
**not games in the `GameModule` sense at all**: none has a win condition, most have no "match"
with a start/end, and Scoreboard/Life Points are literally just persistent counters. Forcing
each into `GameModule`'s `startMatch()`/`endMatch(result: GameResult)` shape (which assumes
winners/losers/scores tied to a match) would be a bad architectural fit purely to reuse a shell
built for a different kind of thing — so, per that entry's own recommendation, this is **one
bundled "Party Toolkit" menu entry** with internal navigation between the 8 tools, not 8 separate
catalog entries. This doc settles the two questions that entry left explicitly open, plus each
tool's actual behavior — all decided through a round of brainstorming with the project owner.

## Architecture

- **Token `GameModule`**: `PartyToolkitGame` registers as an ordinary `GameModule` — `gameId`,
  `displayName`, appears in the existing menu/`GameSection` grid exactly like every other game —
  but `startMatch()`/`endMatch(result)` are no-ops. This was the brainstorm doc's own explicitly
  deferred architecture question, resolved in favor of its own recommended default: reusing all
  existing menu/nav plumbing (`GameSessionManager`, routing) outweighs the cost of a hollow
  `startMatch`/`endMatch` pair for a "game" with no real match concept. The alternative (a
  dedicated top-level menu section bypassing `GameModule` entirely) would be more architecturally
  "honest" but this app has no existing precedent for a non-`GameModule` top-level destination,
  and would mean real new menu-integration code for a single entry — not worth it here.
- **New `GameCategory.UTILITY`** value, added to the existing `BOARD, CARD, ARCADE, PUZZLE, WORD,
  OTHER` enum in `GameModule.kt`. The brainstorm doc's own flagged gap: `OTHER` is the closest
  existing fit but doesn't communicate "utility tool, not a game" to a player browsing the menu.
  A small, low-risk addition to a shared enum — Party Toolkit is the only `GameModule` that uses
  it for now, but it's a real, correctly-named category rather than a misfit squeezed into
  `OTHER`.
- **Landing/internal navigation: TABS, not a grid** — this is what actually shipped, revising
  this doc's own original call. Party Toolkit is a single screen with its own internal tab
  navigation across all 8 tools (one `selectedTool` piece of state), rather than a grid of cards
  you tap to drill into a full-screen tool. The original reasoning against tabs ("8 tabs is more
  than a tab bar comfortably holds") turned out not to be a blocker in practice — the built
  version doesn't render all 8 as a literal fixed bottom bar, sidestepping the crowding concern
  this doc originally raised.

## Player lists — independent per tool, not shared

**Revised from this doc's original call.** Scoreboard, Life Points, First Player, and Teams each
keep their own SEPARATE list of player names rather than one shared roster — explicitly a
deliberate scope cut in the shipped implementation, not an oversight: it avoids one more piece of
cross-tool shared state (and the empty/partial-roster edge cases a shared list raises across 4
different tools with different minimum-player requirements) for a real, if smaller, ongoing cost —
re-entering the same names in more than one tool. Scoreboard's and Life Points' own lists persist
(see **Persistence** below, since a running tally needs to survive a session); First Player's and
Teams' own lists do not, resetting whenever the tool is revisited.

## The 8 tools

| Tool | Behavior | State |
|---|---|---|
| **Dice** | d6 only (no die-type picker); a count stepper (1–6 dice); tap to roll, shows each die's result plus the total. | In-memory only |
| **Coin Toss** | Single flip, a large heads/tails result; tap to flip again. | In-memory only |
| **Random Letter** | Uniform random over A–Z, no exclusions or weighting. | In-memory only |
| **Scoreboard** | Its own player list; an arbitrary integer score per player (can go negative) with +/− steppers; a "reset all to 0" action. | **Persisted** |
| **Life Points** | Its own player list; a starting value (default 20, the most common tabletop total, changeable) every new player is added at and every player can be reset to; +/− steppers per player. | **Persisted** |
| **Hourglass** | A real countdown timer, hourglass-themed visuals (sand-drain animation while running): duration presets (1/3/5/10 min) plus a custom entry, start/pause/reset, a sound+vibration alert at zero. Not a purely decorative animation — the actual point is a usable turn/thinking-time timer. | In-memory only (a fresh timer each time the tool opens) |
| **First Player** | Its own player list; tap to randomly reveal one name. | In-memory only |
| **Teams** | Its own player list; a team-count stepper; tap to randomly shuffle every player into that many groups as evenly as possible (shuffle-then-round-robin-deal, so no team differs from any other's size by more than 1). | In-memory only |

Dice deliberately stays d6-only rather than adding a die-type picker (d4/d8/d10/d12/d20) — covers
the vast majority of real board-game dice needs with a simpler, more focused UI; a
configurable-die-type version is a reasonable future addition, not required for a correct first
version.

## Persistence

Scoreboard's and Life Points' own player lists (each including that tool's own per-player
counter) persist via `preferencesDataStore` — the same established pattern every existing stats
store in this app already uses (e.g. `LightsOutStatsStore`, `MinesweeperStatsStore`) — so a real
game night surviving a break (the app getting closed, a phone dying and restarting, switching to
another app and back) doesn't lose the running totals. Every other tool (Dice, Coin Toss, Random
Letter, Hourglass, First Player, Teams) is plain in-memory Compose state with no persistence need
— there's nothing about "what the last dice roll was," or First Player/Teams' own (unshared, see
above) name lists, worth remembering across app restarts.

## Deliberate scope cuts (honest MVP, same spirit as every other game's own documented cuts)

- **Dice is d6-only** — see **The 8 tools** above.
- **No flip/roll history** for Coin Toss or Dice beyond the current result — a real feature some
  players might want, but adds a second piece of state (and persistence question) to two tools
  that are otherwise deliberately stateless. Left for a later pass if requested.
- **No custom/weighted Random Letter modes** (excluding rare letters, non-English alphabets) —
  uniform A–Z is the honest, unsurprising default a tool literally named "Random Letter" should
  have; anything more specific is a real, separate feature request, not an oversight.
- **No per-tool theming beyond this batch's shared warm palette** — Party Toolkit's chrome
  follows the same "New games only" warm identity every other game in this batch already uses
  (see `docs/NEW_GAMES_BRAINSTORM.md`'s own scope decision #2); no bespoke visual identity per
  individual tool the way Color Flood's cell colors or Connect Four's board frame get one — these
  are small utility widgets, not games with their own visual signature to defend.

## Build order / next step

This was architecturally simpler than any prior engine in this batch — no generation algorithm,
no solver, no bot, no win condition to get right — and shipped without needing the kind of
dedicated adversarial-review pass Sudoku's or Connect Four's own nontrivial algorithms warranted.
The pure "random pick" logic (`PartyToolkitLogic.rollDice`/`flipCoin`/`randomLetter`/
`pickFirstPlayer`/`splitIntoTeams`) is extracted into its own testable, `Random`-injectable
object, same idiom every other engine's own generator functions in this app use.
