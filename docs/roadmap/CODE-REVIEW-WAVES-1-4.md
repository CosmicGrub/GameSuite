# Code review: Waves 1-4b new games + Club layer

Branch session/wave-3 (HEAD fb05659 plus uncommitted arcade work). Read-only review: no source edited, no Gradle run, nothing executed. Scope: the 32 new game packages (gomoku through fishing), core/Club*.kt, GameCoach.kt, CpuLevel.kt, ui/club/*.

Method: the lead read the network handlers of holdem/blackjack, the Club core files, and personally confirmed findings 1, 2, 3 and 4. Three parallel reviewers covered card games, board games, and physics/arcade games and verified their own findings by reading surrounding code. Findings marked (R) were verified by a reviewer, not re-read by the lead.

## Headline

The per-game code is well hardened where the tests look: every host handler uses the transport-stamped sender for seat identity, guest moves are checked against the engine's own legal-move list, shot parameters are range- and isFinite-checked, physics loops have step caps, bot work re-checks the position before applying, hidden information (shoe, deck, hole cards, Ludo dice) never goes on the wire, and ES strings are complete. No high-severity defect was confirmed. The real risk is cross-cutting: online messages arrive on a non-main thread and most games do not guard against that.

## Confirmed findings (grouped by severity)

### Medium

1. **Online transport callbacks run on the OkHttp thread and most games touch state unsynchronised.** `transport/OnlineTransport.kt:~409` invokes the message / left / joined listeners straight from the WebSocket `onMessage` with no hop to main (confirmed: no Dispatchers.Main / post in the file). Handlers in blackjack (`BlackjackGame.kt:872`), war (`:466`), holdem (`:468`), president (`:481`), sevens (`:406`), hanafuda (`:631`), ludo (`:598`), shogi (`:603`), chinese checkers (`:609`) and all turn-based physics games call `commit()`, bump `stateVersion`, mutate engine/deck fields and write Compose state from that thread while the UI thread and main-thread bots do the same. Scenario (holdem): guest `RequestNextHand` runs `engine.startHand` on the socket thread while the host taps Next Hand; both see `handOver == true`, both shuffle, guests receive two different deals for one hand number. Hanafuda `commit()` can run `tallyRound` twice. Ludo/Chinese Checkers: a guest leaving builds `s.copy(isBot = true)` from a stale `s` and overwrites a concurrent bot commit. Nearby delivers on main, so this is ONLINE only. Fix: marshal the three transport listeners onto `Dispatchers.Main.immediate` once, in OnlineTransport (or a shared wrapper), instead of per game.

2. **Two different monitors in nine games, so `@Synchronized` provides no exclusion against the network thread.** Confirmed in `toysoccer/ToySoccerGame.kt:175` (`private val lock = Any()`) against `@Synchronized` at `:246,255,258,277,315,...`; same pattern `speed/SpeedGame.kt:178` vs `:251,281,...`, `matching/MatchingGame.kt` (`:224,293,...`), `toytennis/ToyTennisGame.kt:114`, `slotcars/SlotCarsGame.kt:105`, `fishing/FishingGame.kt:115`. `handleNetworkMessage`/`onPlayerLeft` take `lock`, `advance/swing/tick/restore` take `this`. Scenario: guest `StateSync`/`FlickRequest` writes `world.x[]`/`sim.s[]` while `guestStep`/`hostStep` runs, giving torn state, flicks applied twice, lost `needSync` (speed: two plays pass `canPlay` on the same pile top; `doFlipRound` reshuffles during a play, so cards are lost or duplicated). ToyBoxing (`:184`) and ToyBaseball (`:157`) already do it right with `lock = this`. Fix: `private val lock: Any = this` in the seven games (or a single `@Synchronized` convention).

3. **Shogi guest crashes on a bad host state or a corrupt snapshot.** `shogi/ShogiGame.kt:631-655` (`applyRemoteState`) and `:450-469` (`decodeSnapshot`) validate the position via `positionFrom` but never validate `lastFrom`, `lastTo`, `lastCaptured` (confirmed). `ui/ShogiScreen.kt:796` does `s.cells[s.lastTo]` in composition. `lastTo = 500` -> IndexOutOfBounds in composition (guest, or on resume from a tampered snapshot). Fix: require `lastTo in -1 until n*n`, `lastFrom in -1 until cellCount`, `lastTo >= 0` to point at a non-empty cell, `lastCaptured` a valid code; share with the snapshot path.

4. **Ludo guest hang/OOM from an unbounded last-move range.** `ludo/LudoGame.kt:625-642` (`applyRemoteState`) and `:271-301` (snapshot) accept any `eventFrom`/`eventTo` (confirmed). `ui/LudoScreen.kt:~239` runs `for (p in (from + 1)..to) add(...)` in a LaunchedEffect on the main dispatcher. A host sending `eventTo = 2_000_000_000` with `moveSerial = last + 1` builds a multi-billion-entry list: ANR/OOM. Fix: require `eventFrom, eventTo in -1..LudoBoard.LAST_PROGRESS` with `eventTo >= eventFrom`; clamp the loop.

5. **Spider winnable/daily deal search is unbounded and not cancellable.** (R) `spider/SpiderSolver.kt` `SpiderDealer.winnable(seed, suits, attempts = 30)` can burn 30 attempts x 120k nodes, then 6 shuffles x 80k nodes; 4-suit worst case is minutes. `SpiderGame.kt:~195` `withContext(Dispatchers.Default)` never checks `isActive`, so leaving the screen does not stop it. `ui/SpiderScreen.kt:206-211` `newGame()` has no in-flight guard: two taps start two searches and the later finisher overwrites the table. Fix: `ensureActive()` plus a wall-clock cap in the dealer, and an in-flight guard.

6. **Matching: a guest dropping freezes the table.** (R) `matching/MatchingGame.kt:699-702` `handlePlayerLeft` sets `opponentLeft = true`, after which `flipCard` returns NOT_YOUR_TURN for everyone and no bot takes the seat (holdem, president, sevens, war, blackjack all convert to a bot). Fix: convert the seat to a bot or skip it.

7. **No session epoch on StateSync and no turn timer.** (R) Guests drop any sync with `version <= lastAppliedRemoteVersion` (e.g. `BlackjackGame.kt:~901`). If the host's game object is recreated (process death, rejoin), `stateVersion` restarts at 0 and a guest that keeps its counter ignores every later state. Separately, an idle connected guest stalls blackjack/holdem/president/sevens/matching/hanafuda indefinitely. Fix: add a session id to StateSync (reset the guest counter when it changes); add a host skip/timeout.

8. **Morris bot falls back to an arbitrary move on timeout.** (R) `morris/MorrisEngine.kt:226` `scoreRoot(...) ?: return moves[0]` and `:238` (`best = moves[0]` if depth 2 is cut off). `negamax` builds the full move list with array copies at every node via `outcome()`, so the 1.4 s cap is easy to hit on a slow phone; in the placing phase `moves[0]` is the lowest-index empty point, i.e. a visibly dumb move. Fix: fall back to the depth-1 best; make the terminal test cheap.

9. **Guest-side state validation is weaker than snapshot validation.** (R) Chinese Checkers `applyRemoteState` (`ChineseCheckersGame.kt:637-655`) skips the per-seat piece count and the "over, or side to move has a move" test that `decodeSnapshot` (`:282-301`) does. Ludo `applyRemoteState` skips "awaiting a move implies a legal token" and winner/boardOver agreement. In all seven board games `sessionWins`/`sessionDraws` from the host are assigned unchecked (negative values, unknown ids). Fix: one shared validator for snapshot and guest-state, as Hex and Gomoku already do.

10. **Per-frame recomposition and path rebuilds from animated values read in composition.** (R) `ui/GomokuScreen.kt:454-458` reads an infinite `animateFloat` in the board composable, so 225 cells recompose every frame (each rebuilding `stringResource`/`buildList`/concatenated descriptions at `:495-511`) even with no win. `ui/TankBattleScreen.kt:186` and `ui/DartsScreen.kt:191` do the same; Tank then rebuilds five terrain `Path`s over 320 points plus ridge paths each frame (`ui/TankBattleArt.kt:77-108`). Chinese Checkers (`ChineseCheckersScreen.kt:539-619`) allocates two Paths, a gradient, a HashMap and 121 radial brushes per pulse frame. Fix: read the animation inside the draw/graphicsLayer lambda, and cache static layers with `drawWithCache`/`remember`.

### Low

11. **Tennis rewind is cheap to abuse.** (R) `toytennis/ToyTennisGame.kt:360-382`: `SwingRequest.tick` is clamped to a 36-tick window, but each message triggers a rewind plus up to 36 replayed `hostStep`s with no rate limit, and `cmdLog.getOrPut(...).add(cmd)` has no per-tick cap. A flooding guest burns host CPU. Fix: one swing per guest per cooldown, drop during cooldown before rewinding.

12. **Only SixBall caps payload size.** (R) `sixball/SixBallGame.kt:571` has `MAX_PAYLOAD`; no other game does, and `OnlineTransport.kt:~405` / `NearbyConnectionsTransport.kt:~223` impose no cap. A multi-MB `ShotsRequest.shots` (gallery) or StateSync is fully decoded before the post-hoc size check. Fix: cap `payload.size` in the transport.

13. **SixBall race tie-break favours the host.** (R) `sixball/SixBallGame.kt:381` stamps `finishedAt = nowMillis()` on processing, so a guest's time includes RTT. Equal-tilt ties go to the host. Fix: send elapsed time from the guest (clamped) or share the win.

14. **Any seated guest can skip the results screen for everyone.** Blackjack `NextRoundRequest` (`BlackjackGame.kt:~896`), holdem `RequestNextHand` (`HoldemGame.kt:491`, can also restart a finished tournament), president/sevens `Continue`. Fix: only the host, or all-ready.

15. **Intents carry no state serial.** (R) President/Sevens `applyIntent`: a delayed or duplicated `Pass`/`Give` is honoured whenever that seat is current again. Fix: include the expected event serial and check it.

16. **Unsynchronised intent amplification.** (R) Fishing `Aim`, slotcars `Throttle`/`Ready`, toyboxing `Intent`, toysoccer `RequestState` each force a full-state encode and broadcast per message. Fix: coalesce broadcasts to a minimum interval.

17. **Non-Strict math in sims documented as deterministic.** (R) `toysoccer/SoccerWorld.kt:116,173` (hypot), `toytennis/TennisSim.kt:265,305,365` (hypot), `slotcars/SlotCarsTrack.kt:76,94,139,140` (hypot/atan2/sin/cos). Carrom/billiards/golf/curling use StrictMath. Impact is cosmetic drift between snapshots. Fix: StrictMath.

18. **Blackjack leaveSession scores wagered chips as lost.** (R) `BlackjackGame.kt:243` `netOf` = stack minus starting chips; bets/doubles are already deducted from `stack`, so leaving mid-round flips winner/leader. Fix: add back unsettled `hand.bet` and `insurance`.

19. **`restore()` does not refuse a networked mode** in darts, bowling, tankbattle, shootinggallery, billiards, golf (only `PigGame.kt:253` checks `activeMode`). `snapshot()` returns null for networked modes, but a snapshot could be applied to a networked session without bumping `stateVersion`. Fix: copy Pig's guard.

20. **Gomoku accepts a state that already contains a five but is not marked over.** (R) `gomokuPositionConsistent` (`GomokuGame.kt:64-79`) only scans for a winning line when `winnerIndex != null`. Fix: when not over, assert neither side has a win line (Hex already does the equivalent).

21. **Morris has no repetition rule and `validPosition` accepts `boardOver=true` where `outcome()` is still NONE.** (R) `MorrisEngine.outcome` ends a draw only after `QUIET_LIMIT = 100` plies.

22. **Default `Json` decode (unknown keys throw) in hanafuda, blackjack, war** (`HanafudaGame.kt:633`, `BlackjackGame.kt:874`, `WarGame.kt:468`) while holdem/president/sevens/speed/matching use `ignoreUnknownKeys`. A newer host adding a field makes an older guest drop every StateSync silently. Fix: `ignoreUnknownKeys = true` plus a protocol version field.

23. **Listeners are never unregistered on game exit.** (R) `MultiplayerTransport` has no unregister; `onMessageReceived` just overwrites. A stale host object keeps applying actions and broadcasting until replaced; holdem's `Act` path has no `ended` check (RequestNextHand does). Fix: check `ended`/`matchOver` in handlers; add an unregister.

24. **Offline entry points do not refuse bot seats at engine level** in Shogi `playMove`/`declareImpasse`, Morris `play`, Hounds `play` (Gomoku/Hex block this in `rejectionFor`). Screens guard it, so it is not reachable today. Fix: split human and bot entry points.

25. **Dead and unlocalised `lastAction`** in Morris and Hare and Hounds (`MorrisGame.kt:216,223,427,482-486,507-512`, `HareHoundsGame.kt:185,192,370,434,439`): English strings travel in StateSync and snapshots; no screen reads them. Delete.

26. **Dead code in Blackjack/War.** (R) `BjReject.NOT_BROKE`, `SPLIT_ACES`, `INSURANCE_OFF` only appear in the screen mapping (`BlackjackScreen.kt:326-332`); `BjEvent.DEALER_TURN` never emitted; `WarRules.label` (`WarRules.kt:136`) has no callers.

27. **Duplicated logic that should be shared.** `gauss()` in `billiards/BilliardsBot.kt:212`, `minigolf/GolfBot.kt:148`, `toysoccer/SoccerCpu.kt:43`, `toytennis/ToyTennisGame.kt:302`, `darts/DartsRules.kt:276`; `sendToHost/sendToGuest/broadcast` encode boilerplate copied into ~30 games with arbitrary Json configs; spectator/broadcast code copy-pasted across holdem, president, sevens (`*_MAX_SPECTATORS`, `*_SPECTATOR_SENDER`).

28. **Allocations in card art draw lambdas.** (R, skimmed) `Path()`/`Brush.*` created inside Canvas draw in `HoldemCardArt.kt:67,90,104,151-160`, `CardArt.kt:58-89`, `CasinoCards.kt:86,274`, `PsCardUi.kt:96-136`. Hoist into `remember` for animated cards.

29. **Club layer (lead read).** `ClubProfileStore.save` writes name, avatar and favourite in three separate DataStore edits, so a process kill mid-save leaves a half-saved profile (low; use one `edit`). Otherwise decode is lenient and validated, `markCoachSeen` is atomic, `ClubViewModel` handles a corrupt store; `Avatar.forSeed` is safe. Three decorative `contentDescription = ""` in `ui/club/AvatarPicker.kt:112,135` and `LobbySeatRow.kt:29` are intentional (labels adjacent) but confirm the surrounding control has its own semantics.

30. **Gallery/SixBall logs can be forged to a perfect run.** (R) `shootinggallery/GalleryRules.kt:136-154` `validLog` checks structure and monotonic time only; the range is a pure function of the public seed. SixBall `Finish` likewise accepts a precomputed optimal solution. Inherent to guest-reported results; add a human-reaction floor if competitive integrity matters.

## Checked and found fine

Seat identity from transport-stamped sender in every handler; no id taken from payload. Blackjack `doBet` rejects non-multiples, below-min and above-stack amounts; holdem raise bounds checked in `HoldemRules`. Guest moves validated against engine legal moves (no `!!` or index crash reachable from a move message). kotlinx Json rejects NaN/Infinity by default and no file sets `allowSpecialFloatingPointValues`. Physics loops have `MAX_STEPS`; catch-up capped. Bots re-check `state.value !== s` off-main. Spider/Chinese Checkers hop search iterative; Shogi search bounded. Redaction (holdem, blackjack hole card `-1`, president, sevens, spectators), Ludo rolls, fishing `hooked` all verified not leaking. Shogi rules (nifu, uchifuzume, forced promotion, sennichite, impasse), Morris mills/flying, Hex swap, Hounds termination, Ludo capture/safe squares verified. ES string key and format-specifier parity complete for these games; no hard-coded `Text("...")` / `contentDescription` literals found beyond numeric/dealer-chip labels (`HoldemCardArt.kt:375`, `HoldemScreen.kt:1285,1302`, `PsCardUi.kt:484`) and a missing-ES list limited to `app_name` and 28 `game_*` names unrelated to these games.

## Suspected, unconfirmed

- Tennis match end after a rewind: `hostSwingFromGuest` may rewind across the tick where the match ended after `wonCounted`/`sessionWins` were incremented (could not show a window that crosses OVER).
- Billiards/golf `isValid` does not bound `lastShot.shot.power` in a host sync (animation only, host-trust).
- Ludo ring length (`LudoRules.kt:23,44`, `LAST_TRACK_PROGRESS = 50`): possibly a deliberate variant; check against the intended rules.
- Hare and Hounds `hasEscaped` uses `<=` (`HareHoundsEngine.kt:89-90`): likely a design choice.
- Shogi easy level (`ShogiEngine.kt:608-611`): if cut off during depth 1 with `noiseCp > 0`, every root score is 0 and the pool includes blunders; needs a 400 ms cutoff.
- Unbounded `cardIds` lists in President `Play`/`Give` (cost is one `toSet()`; bounded only by transport, see finding 12).

## Top 10 by severity

1. Online listeners run on the OkHttp thread with unsynchronised game state (finding 1).
2. Nine games lock `lock` in handlers but `this` in `@Synchronized` methods (finding 2).
3. Shogi guest/snapshot crash from unvalidated `lastTo` (finding 3).
4. Ludo guest ANR/OOM from unbounded `eventTo` (finding 4).
5. Spider deal search unbounded, not cancellable, no in-flight guard (finding 5).
6. Matching table freezes when a guest leaves (finding 6).
7. No StateSync session epoch / no idle-turn timer (finding 7).
8. Morris bot plays `moves[0]` on timeout (finding 8).
9. Guest-state validation weaker than snapshot validation, `sessionWins` unchecked (finding 9).
10. Per-frame recomposition and Path/brush rebuilds in Gomoku, Tank, Darts, Chinese Checkers (finding 10).

## Overall assessment

The new games are in better shape than the size and speed of the waves suggest. The trust model is applied uniformly and correctly: seat identity comes from the transport, guests only ask and the host validates against its own legal-move list, numeric inputs are bounded, hidden information is redacted per recipient, and the physics games are step-capped with bots that re-check the position. The shared defects are all structural rather than per-game: the online transport delivers on a background thread and no game (or the transport) hops to main, seven games additionally lock two different monitors, and guest-side validation of host state is inconsistent (Shogi and Ludo can be crashed or stalled by a hostile or corrupt host state). The cheapest, highest-value fixes are one dispatcher hop in OnlineTransport, `lock = this` in seven games, and one shared StateSync validator including the last-move fields. Everything else is low-severity polish: rate limits, a session epoch, performance of animated canvases, and duplicated helpers. This review was read-only; none of the findings were reproduced by running the app, and the tests/emulator coverage gap lies mainly in the ONLINE-only threading cases, which Nearby (main-thread delivery) never exercises.
