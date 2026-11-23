# Audit: Act It Out (`act-it-out`)

Scope: `games/actitout/*` (Engine, Game, Rules, Deck, Glyphs, HubSpecs), `ui/ActItOutScreen.kt`, `ui/ActItOutArt.kt`,
`res/values{,-es}/act_it_out_*.xml`, tests in `app/src/test/.../actitout`. Read-only audit, nothing built or run.
Skipped on request: `ActItOutDeckTest` glyph `e_actions`, the hard-coded team label in the screen.
"Verified" = confirmed by reading the code paths end to end (no device run). "Suspected" = depends on behaviour I could not execute.
Paths are relative to `app/src/main/java/com/gamesuite/` unless noted.

## What is solid (checked, no defect)

- Curtain secrecy on process death and restore: `snapshot()` stores a PLAYING turn as CURTAIN + `inProgress`
  (Engine.kt:298-316); `restore()` rejects PLAYING (350) and a card without a matching `used` entry (363-367). The card
  is never on screen after a restore. Orientation change is handled by `configChanges` in the manifest (line 85), so the
  composition is not recreated.
- Timer: injected `clock`, pause freezes `remainingFrozenMs`, resume re-bases the deadline, `tick()` is idempotent.
  `GameSessionManager.pauseActiveGame()` calls `module.pause()` before saving, so a backgrounded turn does not burn time.
- Deck: 200 cards, 200 unique ids, ES and EN keys 1:1, all `%` placeholders match, EN string equals deck text for all
  200 (I diffed the XML against the deck). 61 difficulty-1 cards, each with a unique glyph. Little kids pool is
  difficulty 1 only (Rules.kt:26). Only golf, curling, detective are identical EN/ES, which is correct Spanish.
- Hub/coach/tip strings: key and placeholder parity EN/ES for all three string files.
- Scoring arithmetic on restore: `score == correct * pointsFor(bonus)` per side (Engine.kt:354-357). Bonus is applied in
  `award()` from the acting side only, once per correct card (252-259). Ties: `leaders()` returns all; `isWinner` needs best > 0.
- Skip limit: 3 per turn, `skipsLeft + turnSkipped == 3` enforced on restore (360). Crash risks: no `!!`; `members[round % size]`
  safe because `validate()` rejects empty sides; END clamps `turnIndex` to `total-1`.

## Findings

### F1 (MED, verified) Player name fields detach from their player when teams change
`ui/ActItOutScreen.kt:431-447` (and `AiNameField` 357-366).
`AiNameField` keeps `var text by remember { mutableStateOf(initial) }` with no key, and the `for (team) { for (p) { ... } }`
loops have no `key(p)`. Compose identifies those rows by position, so when a player is moved ("Move to Team B", line 440)
or "Shuffle teams" (451) re-sorts the rows, each field keeps the TEXT of whoever used to be at that position while
`onName` now writes to a different seat. Result: the field shows a stale name, and typing in it renames the wrong player;
the curtain later says a name the setup screen never showed.
Trigger: Teams format, move any player to the other team, look at the name fields.
Fix: wrap each row in `androidx.compose.runtime.key(p) { Row(...) }` (same for the FFA loop at 455), and key the text state
on the seat. Add a Compose test that moves player 0 and asserts the field text.

### F2 (MED, verified) Tap that lands on the expiry frame is swallowed without a redraw or the time-up cue
`games/actitout/ActItOutGame.kt:117-118`, Engine.kt:195-213, `ui/ActItOutScreen.kt:182, 208`.
`engine.markCorrect()` and `skip()` call `tick()` first; if the deadline has passed that moves the engine to TIMES_UP and
returns 0 / false. The Game wrapper only `bump()`s when the return is positive/true, and the screen's frame loop then calls
`game.tick()`, which returns false (phase already TIMES_UP), so `version` is never bumped. Consequences:
(a) top-level `val phase = engine?.phase` (line 175) is stale, so `LaunchedEffect(phase)` (208) never runs: no buzzer,
no failure haptic, no "Time's up" status line, and `GameChrome(matchInProgress=...)` uses the stale phase;
(b) the screen only changes because `remaining` (a state) happens to change, which is luck, not design.
Trigger: tap Correct or Skip in the same frame the clock hits zero (more likely at 30 s turns with a fast tapper).
Fix: make the Game wrapper bump whenever `engine.phase` changed: capture `val before = e.phase` and
`if (e.phase != before || p > 0) bump()` in `markCorrect` and `skip`; or call `game.tick()` first in the tap handlers.

### F3 (LOW-MED, verified) A card dealt in the last milliseconds is offered as "got it just in time"
Engine.kt:195-201 + 165-172.
`markCorrect()` at e.g. 0.2 s left awards the card and immediately deals a new one; the next frame ends the turn, so
TIMES_UP shows (and burns, `usedSet`) a card the actor never saw, and the group is asked whether it was guessed.
Fix: in `markCorrect`/`skip`, if `remainingMs() < ~300` end the turn with no new card (`card = null`, go to TURN_SUMMARY),
or deal the replacement lazily only while the turn is still running.

### F4 (LOW-MED, suspected) A finished match can be left resumable
Game.kt:168-173 + `GameSessionManager.saveActiveProgress` (a null snapshot deliberately does not delete a save).
`snapshot()` returns null at END and during setup. Sequence: pause in the last turn (a save is written), finish the
match, tap "Play again", background the app: `engine == null` so the snapshot is null and the OLD save of the last
turn stays. The Hub then offers "Resume" of a match already played; finishing it again calls `recordFinished` and reports a
second result (stats double count). Normal "Back to menu" at END does delete it (`endActiveGame` non-aborted path).
Fix: `playAgain()` and `beginMatch()` should delete the save for the current key (needs a small hook to the session
manager), or the Game should expose a "nothing to resume" signal the shell acts on.

### F5 (LOW, verified) Dealing: resetting one band's pool also frees cards another band already used
Engine.kt:280-285. On exhaustion `usedSet.removeAll(pool.toSet())` removes every card the band can be dealt, including
cards other bands share (difficulty 1 is in LITTLE and EVERYONE; difficulty 2 in EVERYONE and GROWN_UPS). So when a LITTLE
side (61 cards) wraps, EVERYONE's already-seen difficulty-1 cards become fresh again and repeat before EVERYONE's own pool is
spent. Needs ~61 cards from the little pool in one match (5 rounds, 90 s), so rare. Also `lastDealtId` is not restored
for a non-inProgress curtain (Engine.kt:388), so the "no immediate repeat" guarantee does not survive a restore.
Fix: track `used` per band, or only remove ids this band exclusively owns; persist `lastDealtId` in the snapshot.
Test gap: no test mixes bands across an exhaustion.

### F6 (LOW, verified / suspected for release) Card text cached by id only; reflective string lookup
`ui/ActItOutScreen.kt:587` `remember(card.id) { ctx.cardText(card) }`. Locale is in `configChanges`, so the activity is not
recreated and the composition keeps the cached string until the next card. Fix: key on `LocalConfiguration.current`.
`cardText` (129-133) resolves `act_it_out_card_<id>` through `getIdentifier` by name at runtime. With R8 resource shrinking
those strings are not referenced from code and can be stripped from release builds unless a `keep.xml`
(`tools:keep="@string/act_it_out_card_*"`) exists; then every card silently falls back to English. (Suspected, release only;
check `res/raw/keep.xml` and a release build with Spanish.)

### F7 (LOW, suspected) The card can be seen outside the turn
- No `FLAG_SECURE` anywhere in the app (grep), so the Recents thumbnail of a backgrounded PLAYING turn shows the card.
- `pauseActiveGame` freezes the clock but the PLAYING screen (card) stays; on `onResume` the card is shown straight away,
  with no curtain. Anyone the phone is handed to during a pause sees the card and the turn continues. The engine already
  has the machinery: on `pause()` while PLAYING, set `phase = CURTAIN; inProgress = true; remainingFrozenMs = remainingMs()`
  and let the player hold the pad again (`resumedTurn` text exists).
- TalkBack: `AiCardFace` merges its text into one node, so a screen-reader actor focusing it hears the card aloud for the
  whole room. Inherent to the game; mention headphones in the help text. The status live region never contains the card (checked).
- Optional: `setRecentsScreenshotEnabled(false)` on API 33+ while PLAYING.

### F8 (LOW, verified) Restore does not cross-check the per-turn counters
Engine.kt:358-361. `turnPoints == turnCorrect * pointsFor(bonus)`, `turnCorrect <= correct[side]`, `turnSkipped <= skipped[side]`
are not validated; a tampered or buggy save can show a wrong "points this turn" in the summary (scores themselves are checked).
`inProgress` with `remainingMs == 0` is accepted: the curtain says "0 seconds left" and the first frame goes to TIMES_UP
showing the card (a turn that expired at save time). Fix: treat `inProgress && remainingMs == 0` as TIMES_UP at restore, add the
cross checks, add three rejection tests.

### F9 (LOW, verified) Setup quirks: bonus on any band, discarded choices, English default name
Rules.kt:241-246. `setBand` clears a bonus only when `!handicapOffered`, which is exactly when no bonus can exist, so that
branch is dead; with `handicapOffered` a side can move from LITTLE to GROWN_UPS and keep its bonus (a "little kids" bonus on an
adult side) and `build()` accepts it. Fix: clear the bonus when the band leaves LITTLE, or relabel as a handicap.
`setFormat` (252-257) discards per-side band and bonus choices when toggling Teams/Everyone, which is surprising.
`cleanPlayerName(n, "Player ${i + 1}")` (Rules.kt:211, 260) is a hard-coded English default; a Spanish user who blanks a name
gets "Player 3". Fix: pass a localized default from the screen into `ActItOutSetup`.

### F10 (LOW, verified) Text scaling, fixed sizes, recomposition design
`AiHoldPad` is a fixed `height(180.dp)` with `headlineMedium` text; `AiTimerRing` is 96 dp with `displayMedium` ("90" at 2x
font scale is wider than the ring, not scrollable). Fix: `heightIn(min = 180.dp)` for the pad; size the ring text from the ring.
`remember { CardSounds.get(androidContext) }` (line 153) is unused here and loads the card-sound pool for nothing.
`actItOutColors(dark)` builds a new object every recomposition (not remembered). That defeats skipping and is what makes
`AiSetup(game, setup, colors)` redraw on `version` bumps at all (its other args are the same instances; with strong skipping
it would otherwise be skipped). Fragile: pass `game.version.intValue` into each stage composable or read it inside them.

### F11 (INFO) Localisation and content review
- Hard-coded English: the setup default name (F9) and the known team label. Hub spec English literals map to `hubKey`
  strings (covered by `ActItOutHubSpecsTest`).
- Age appropriateness: all 200 cards are suitable. The HARD set (nerves before a show, jet lag, procrastination, typing an
  essay) is difficulty 3 only, so "Little kids" and "Everyone" never see them. "Moonwalk" names a dance (fine). Abstract HARD
  cards (Echo, Gravity, Teamwork) are hard to act by design.
- Banned list (`ActItOutDeckTest`): covers violence, body parts, adult, politics, brands in EN and ES. Whole-word entries
  mean "shotgun" or "handstand" would pass; consider adding "fire", "bite", "drug", "poison" if policy wants them out
  (none are in the deck now). Only EN/ES, matching the shipped languages.

## Threading / coroutines / Compose (checked)
- Clock loop (`withFrameMillis` + `game.tick()`) runs on the main dispatcher; the buzzer tick fires only when whole seconds
  change and are within 1..5, so it cannot repeat per frame. Starting a turn already inside the last 5 s skips the first tick (cosmetic).
- Hold pad: `detectTapGestures(onPress)` with `coroutineScope { launch ...; tryAwaitRelease(); job.cancel() }` cancels cleanly
  on lift and on cancel; `pointerInput(hold)` is not keyed on `onDone`, but `onDone` only captures stable `game`/`haptics`.
  After the hold completes the pad leaves composition and the gesture scope is cancelled: no leak. The TalkBack semantic
  click starts immediately by design; the tap option is a setup switch and works.
- `remaining` is set every frame, recomposing the whole PLAYING content at 60 fps including the card; acceptable at this size.

## Test gaps
- No test that the Game wrapper bumps `version` when a tap hits the expiry frame (F2); nothing exercises `ActItOutGame`
  with a fake clock at the exact deadline for `markCorrect`/`skip`.
- No Compose/UI test at all (name-field keying F1, hold pad, TalkBack click, 2x text scale).
- No mixed-band exhaustion test (F5); no restore-rejection tests for turn counters or `inProgress` with 0 ms (F8).
- No test of "play again then leave" with a stale save (F4); needs the session manager.
- Engine tests are otherwise thorough: clock, pause, skips, bonus, rotation, ffa, snapshot round trips, corrupt inputs.

## Ranked top list
1. F1 name fields desync on team change (user-visible wrong names).
2. F2 expiry-frame tap swallows the redraw and time-up cue.
3. F4 stale save can resume or double-record a finished match.
4. F7 card shown on resume without curtain / in Recents.
5. F3 never-seen card offered at time-up.
6. F6 locale cache and possible release string shrinking of card text.
7. F5, F8, F9, F10 polish.

## Verdict
The engine is sound, well tested and its restore path is strict; card secrecy across process death is correct. Nothing
ship-blocking. Fix F1 and F2 before release (both small); F4 and F7 are shell-level decisions worth a ticket; the rest is polish.
