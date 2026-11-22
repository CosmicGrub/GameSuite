# Gem Cascade: the step log (events)

Every engine operation (`GemEngine.swap`, `rotate`, `useBooster`, `elapse`, or `apply(GemAction)`) returns a
`MoveResult(accepted, reason, events)`. `events` is the **step log**: an ordered list of typed `GemEvent`s that
tells a renderer everything that happened, in the order it should be shown. 2D, 2.5D and 3D renderers, the
sound layer and the haptics layer all animate from this list and never read the engine's internals mid-move.

Source: `app/src/main/java/com/gamesuite/games/gemcascade/GemEvents.kt` (KDoc on every event).

## Completeness guarantee

`GemEventApplier.applyAll(boardBefore, events)` reproduces the engine's board after the move exactly. A unit test
(`GemEventLogTest`) checks this for every mode over many seeds, for accepted and rejected attempts, boosters and
shuffles. So a renderer can keep its own copy of the board and mutate it event by event while it animates.

## Order of a move

```
Swap(a, b)                         the player's gesture (Rotate(topLeft, cw, steps) for Twist)
MovesLeft(n)                       when moves are limited
SpeedMeter / SpeedBonus            Lightning only
--- one block per cascade step (n = 1 is the player's own match) ---
ChainStep(n, multiplier)
ComboActivated | SpecialActivated  forced effects of a special + special swap or a prism swap
Match(cells, shape, kind)          one per connected group of 3+
SpecialCreated(at, special, kind)  the 4-run / 5-run / L,T / double-4 reward; the gem stays and is not cleared
SpecialActivated(at, special, affected)   chain reactions, in the order they fire
ObstacleDamaged(at, ICE|LOCK|STONE|SPIDER, hpLeft)   hpLeft 0 = that layer is gone
BombDefused(at)
Clear(cells, cause, chain)         the gems that leave the board
ScoreDelta(points, total, reason)  MATCH | SPECIAL | COMBO, then OBSTACLE
TimeBonus(ms, reason)              Lightning only
LevelGoalProgress(goal, kind, current, target)
LevelUp(level)                     Classic threshold
Fall(from, to)*                    pieces dropping, bottom of each column segment first
Spawn(at, cell, fromRow)*          new gems entering from above (fromRow < at.r; negative = above the board)
Escaped(at, TREASURE)              a treasure reached the bottom row (then another Fall/Spawn round follows)
--- repeated while the new board has matches (ChainStep n + 1) ---
BombTick / BombExploded / PieceMoved / Escaped / Spawn   after-move rules (bombs, butterflies and spiders rising, spawn rules)
NoMovesShuffle(moves, regenerated) when no legal move was left (a redeal is followed by Spawn per changed cell)
BoosterUsed(...)                   first event of a booster operation
LevelComplete(stars, score) | GameOver(reason, score)   last event of a finished game
```

A rejected swap returns `accepted = false` and, for a swap that made no match, exactly `[Swap(a,b), SwapBack(a,b)]`
(the classic "swap back"). A rejected rotation returns `[RotateBack(topLeft, cw)]`. Other rejections carry no events.
`MoveResult.reason` is a `RejectReason`; nothing is consumed (no move, no random number) by a rejection.

## Example: a 4-run with a cascade

Swap two gems so four amber squares line up in a row, and the refill makes another match:

```
Swap((1,2),(2,2))
MovesLeft(24)
ChainStep(1, x1)
Match([(2,0),(2,1),(2,2),(2,3)], LINE_4, kind 1)
SpecialCreated((2,2), FLARE_COL, kind 1)         <- the dropped gem becomes a flare, it is not in Clear
Clear([(2,0),(2,1),(2,3)], MATCH, chain 1)
ScoreDelta(+50, total 50, MATCH)
Fall((1,0),(2,0)) ... Spawn((0,0), gem 4, fromRow -1) ...
ChainStep(2, x2)
Match([...], LINE_3, kind 4)
Clear([...], MATCH, chain 2)
ScoreDelta(+60, total 110, MATCH)
Fall ... Spawn ...
```

## How each layer uses it

* Visuals: tween each event (swap slide, match glow, clear pop, fall, spawn drop, special glow, chain pop text).
  Style packs only change how an event looks, never what the engine does.
* Sound: `Swap` tap, `Clear` place/capture by size, `ChainStep` rising pitch with `n`, `SpecialCreated` and
  `SpecialActivated` accents, `SwapBack` invalid blip, `LevelComplete` win, `GameOver` lose, `LevelUp` achieve.
* Haptics (cue sheet, branch session/ws-haptics): `Swap` light tick, `Clear` normal action, `SpecialActivated` and
  `ComboActivated` strong action, `ChainStep` n >= 3 escalating, `LevelComplete` celebration, `SwapBack` failure.
* Accessibility: the TalkBack live region speaks one line per finished move built from `Match`, `ChainStep` and
  `ScoreDelta`; it only changes when a new batch finishes animating.

## Other pure types a renderer may need

* `GemBoard` / `Cell` / `PieceType` / `SpecialKind`: the grid (`GemBoard.encode()` is a one-line text form).
* `GemKinds`: the seven gem kinds are told apart by shape and colour (`GemShape`, `GemKinds.argb`).
* `GemAction`: `SwapAction`, `RotateAction`, `BoosterAction`, `Elapse`; also the unit of replays.
* `GemSolver.hint(engine)`: best move for the hint button; `GemMoves.legal(board, rules)`: all legal moves.
* `GemReplay.encode/decode/run`: deterministic replay of a seed plus an action list.
