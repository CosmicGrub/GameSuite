# Bump and Run (`bump-run`): what the coordinator must integrate

Status (see "Done / open / unverified" at the end). Game id and route: `bump-run`. Title: **Bump and Run** (Spanish:
Choca y Corre). Local only: pass and play and vs computers, 2 to 4 seats, optional 2 v 2 teams at four seats. Not
networked. Scope pack: `docs/design/boardgames/bump-run.md`.

## 1. Hub spec (`core/GameHubSpecs.kt`)

Add `BumpRunHubSpecs.bumpRun` (object in `com.gamesuite.games.bumprun`) to `baseSpecs()`, BOARD section (next to Ludo /
Slide and Climb). The spec sets `audience = GameAudience.FAMILY, minAge = 6`.

Do NOT add `"bump-run"` to `GameHubSpecs.networkedGames`. `GameAudienceTable` needs no row (the spec's own tags win).

## 2. Glyph (`core/GameCatalog.kt`, the menu-glyph `when`)

```
"bump-run" -> "🏃"   // also BumpRunHubSpecs.GLYPH
```

## 3. Accent colours (`ui/GameTheme.kt`, `GameThemes.accents`)

```
"bump-run" to GameAccent(0xFF0B5A6B, 0xFF6FD0DE),
```
(also `BumpRunHubSpecs.ACCENT_LIGHT` / `ACCENT_DARK`; `BumpRunHubSpecsTest` checks contrast against every theme surface and
that no other game uses the same two values. Visual distinctness from the neighbouring teal accents was NOT reviewed.)

## 4. Route (`MainActivity.kt`)

Next to the `ludo` route:

```kotlin
composable("bump-run") {
    val g = rememberActiveModule(sessionManager, "bump-run") { BumpRunGame() }
    BumpRunScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, onMatchEnded = finishMatch)
}
```
Imports: `com.gamesuite.games.bumprun.BumpRunGame`, `com.gamesuite.ui.BumpRunScreen`.

## 5. Tips (`ui/GameTips.kt`, `GameTips.tips`)

Literals equal `tip_bump_run_1..3` in `res/values/bump_run_strings.xml` (Spanish in `values-es`):

```kotlin
"bump-run" to listOf(
    "Tap the card pile, then tap a pawn to move it.",
    "Landing on a rival bumps it home. Watch for pawns just behind you!",
    "A star on a card can bring a pawn out of the Nest."
),
```

## 6. Coach card (`core/GameCoach.kt`)

Motif `"bump-run" to CoachMotif.CARDS` in `motifs`. Entry:

```kotlin
entry("bump-run", listOf(R.string.coach_bump_run_s1, R.string.coach_bump_run_s2, R.string.coach_bump_run_s3,
    R.string.coach_bump_run_s4, R.string.coach_bump_run_s5), R.string.coach_bump_run_fact),
```
`BumpRunScreen` opens the card itself through `rememberClCoach("bump-run", ...)` (the Club card, like Ludo and Slide
and Climb). **Do NOT add `"bump-run"` to `GameCoach.ownCoachInGame`.**

## 7. Things to know

- Hub options reach the screen as `LaunchOptions.extras`: `length` (quick | classic | long), `pads`, `autoPlay`
  ("true"/"false"), `helper` ("none" | "all" | "1".."4"), and `teams` (consumed by `buildHubLaunch` into
  `PlayerInfo.teamId`; the screen turns teams on when all four players carry a team id). The "Helper pack: Player N"
  choices are offered at every table size; a player number beyond the table is ignored (`parseBumpRunHelper`).
- Chance: one `nextLong()` from the injected `Random` per board seeds the shuffle; reshuffles derive from the seed. The
  snapshot stores the seed AND the explicit pile / discard order. The computer's decisions use a Random derived from
  `(turnSerial, seat)`, never the deck.
- Team rules as built: a seat may move any pawn of its team; bump / swap / chase only target the other team; a shield on
  a team's pawn expires at the start of the NEXT turn of either team member (the doc says "your next turn" for the
  shielder; with partners this is the simpler, slightly shorter rule).
- Resume: `ResumableGame`; the snapshot is validated by `StandardBumpRunEngine.validate` (schema version 1).
- `PausableGame` is implemented (`isPaused`); the screen pauses the bots and hops when the window loses focus or the
  coach card is up.
- Reduced Motion: no hops (every result shown at once), no confetti; game speed scales every pause and hop.
- Strings: new files `bump_run_strings.xml` and `bump_run_ui_strings.xml`, each in `values` and `values-es`
  (Spanish is the author's and flagged for native review). Reused shared strings: `ccl_cpu_tag`, `hub_you`.
- Screen depends on these existing internals (package `com.gamesuite.ui`): `ChineseSeatColors`, `drawMarble`,
  `drawSeatGlyph`, `drawBurst`, `ClSeatBadge`, `ClStatusLine`, `ClWhyLine`, `ClFinishedPanel`, `rememberClCoach`,
  `ClCoachButton`, `seatLabel`, `isYouName`. A rename elsewhere breaks compilation here.
- Computer Arena: NOT wired (no `ArenaHook`); the bot is a pure function, so a hook is straightforward later.
- Files: `games/bumprun/{BumpRunBoard,BumpRunCards,BumpRunState,BumpRunEngine,BumpRunBot,BumpRunGame,BumpRunHubSpecs}.kt`,
  `ui/BumpRunScreen.kt`, `ui/BumpRunArt.kt`, tests in `app/src/test/java/com/gamesuite/games/bumprun/`.

## 8. Gate to run after registering

`./gradlew :app:testDebugUnitTest --tests "com.gamesuite.games.bumprun.*"`, then the whole unit suite (HubLocalizationTest,
GameTipsTest, GameCoachTest, GameThemeTest, GameAudienceTest, EnglishLiteralRatchetTest iterate every spec).

## Done / open / unverified

- Done and JVM-tested: full rules (13 card types incl. fallbacks, bumps, swaps, shields, pads, exact finish, do-overs,
  helper pack, teams), validated snapshot with corruption tests, three computer levels, Hub spec, EN + ES strings.
- Compiled, NOT played on a device: the Compose screen (board canvas, pile, card faces, move buttons, hops, bump boing,
  confetti). Never run on a phone, tablet, Fold, with TalkBack or with Reduced Motion on.
- Open: Hard has exact card odds and chase threat but no second-ply search (the doc's "one ply" was not built;
  `hardBeatsMedium` is therefore not asserted, only Medium and Hard beating Easy); Gentle bump option; hint button;
  quick-chat; network play; trademark search for the name (owner task); native Spanish review; Arena hook;
  per-table-size helper choices; the rate of the doc's length table was only spot-checked (`lengthByModeStaysInBudget`).
