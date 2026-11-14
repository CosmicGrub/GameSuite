# Code review Low items 11-29: status (Team H audit leftovers, 2026-11-14)

Source: `docs/roadmap/CODE-REVIEW-WAVES-1-4.md` (Low section) and the idle-turn timer entry in
`docs/audit/05-quality-ops.md` / `UNBUILT-ITEMS` A. "Before" was verified against the code in this worktree.

| # | Title | Before | Now |
|---|-------|--------|-----|
| 7 (idle) | Idle-turn timer | Open | DONE for Hold'em: `core/IdleTurnPolicy` (60 s limit, pure), `HoldemGame.idleTimeoutAction/applyIdleTimeout` (host only, human seat, check if free else fold, stale-state safe), a host `LaunchedEffect` in `ui/HoldemScreen.kt`. Other networked games NOT done (each needs its own legal "pass" action and a screen hook); the helper is reusable. |
| 11 | Tennis rewind abuse | Fixed earlier (`hostSwingFromGuest` drops requests inside SWING_COOL/4 before rewinding) | none |
| 12 | Only SixBall caps payload | Open | NOT DONE: a transport-level cap changes behaviour for every game and both transports; needs a size survey of StateSync payloads first. |
| 13 | SixBall tie-break favours host | Open | NOT DONE: needs a protocol change (guest-sent elapsed time). |
| 14 | Any guest can skip results | Open | NOT DONE: "host only" would stall guests when the host is away; "all ready" is a UX/protocol decision. |
| 15 | Intents without state serial | Open | NOT DONE: protocol change. |
| 16 | Intent amplification | Open | NOT DONE: needs broadcast coalescing design (rate-limit class). |
| 17 | Non-Strict math in sims | Fixed earlier (SoccerWorld, TennisSim, SlotCarsTrack all use StrictMath) | none |
| 18 | Blackjack leave scores wagered chips lost | Fixed earlier (`netOf` adds back felt chips) | none |
| 19 | `restore()` refuses networked mode | Fixed earlier (darts, bowling, billiards, golf, tank, gallery carry the guard) | none |
| 20 | Gomoku not-over five | Fixed earlier (`gomokuPositionConsistent` scans when not over) | none |
| 21 | Morris repetition / boardOver | Open | NOT DONE: a repetition rule is a rules change; `validPosition` tweak needs the Morris engine tests reviewed. |
| 22 | Default Json (unknown keys throw) | Fixed earlier (hanafuda, blackjack, war use ignoreUnknownKeys). A protocol version field is NOT added (protocol change). | none |
| 23 | Listeners never unregistered | Open | PARTIAL: Hold'em `Act` now ignored when `ended` or `matchOver`, `StateSync` ignored when `ended` (other games' handlers not audited here). `MultiplayerTransport.unregister` NOT added: the interface lives in the KMP `shared` module and ~40 test fakes plus the Nearby/Online/LAN implementations would all change; needs its own change with a default no-op method. |
| 24 | Bot seats at engine level (Shogi/Morris/Hounds) | Open, unreachable today | NOT DONE: the same entry points may be used by bot-driving code; splitting needs a call-site review. |
| 25 | Dead `lastAction` Morris/Hounds | Open | NOT DONE: the field is part of snapshot and StateSync classes without a default; deleting it breaks older peers and saved games. Needs a versioned migration. |
| 26 | Dead code Blackjack/War | `WarRules.label` open; BjReject entries are used by the screen mapping; `BjEvent.DEALER_TURN` is in the `ALL` validation set | DONE: `WarRules.label` deleted. The rest NOT removed (removing `DEALER_TURN` would reject old saved event strings). |
| 27 | Duplicated gauss() | Open (5 copies, three different algorithms) | DONE: `core/Gaussian.kt` (`sum4`, `sum3`, `boxMuller`) with the exact arithmetic and draw order of each copy; billiards, golf, soccer CPU, toy tennis and darts delegate. `sendToHost/broadcast` boilerplate and spectator code NOT merged (too broad, about 30 games). Palette classes: none named in the doc. |
| 28 | Allocations in card art draw lambdas | Open | NOT DONE: needs visual verification on device. |
| 29 | ClubProfileStore.save three edits | Fixed earlier (single `edit`) | none |

Tests added: `core/GaussianAndIdleTurnTest`, `games/holdem/HoldemIdleTurnTest`.
