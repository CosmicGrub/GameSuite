# Draw and Guess: what the coordinator must add

Game id `draw-guess`. Nothing here has been compiled or run (see the end).

## 0. Core prerequisite (another agent)

`DrawGuessHubSpecs` passes `audience = GameAudience.FAMILY` and `minAge = 6` to `GameHubSpec`, and imports
`com.gamesuite.core.GameAudience`. They must exist (enum `GameAudience { ADULTS, FAMILY, KIDS }`, optional
`GameHubSpec(audience, minAge)`) before this package compiles.

## 1. Hub spec

Add `DrawGuessHubSpecs.drawGuess` (or `DrawGuessHubSpecs.all`) to `GameHubSpecs.baseSpecs()`. Family `HubFamily.WORD`.
One mode, "Same screen" (`PASS_AND_PLAY`, 2 to 8 seats, route `draw-guess`, `READY_SCREEN`). Do NOT add the id to
`GameHubSpecs.networkedGames` (the engine has no messages). Option keys that reach the screen as `LaunchOptions.extras`:
`timer` (0|30|60|90), `guess` (pick|type), `rounds` (1..4); `humans` and `teams` are the standard keys. Computers never
get a seat (the spec has no CPU stepper; the engine ignores bots if a stale `teams=true` ever makes the Hub add one).

Hub strings are in `res/values{,-es}/draw_guess_hub_strings.xml`. Texts other games already define ("Same screen",
"Two teams", "30/60/90 seconds", "No timer", "People playing" and its help) are deliberately NOT repeated; if the
shared definitions ever move, `DrawGuessHubSpecsTest` will say which key is missing.

## 2. Glyph (menu card)

`GameCatalog` glyph for `draw-guess`: `"✏"` (the catalog convention is one emoji or symbol per id; a pencil).

## 3. Accents (`GameThemes.accents`)

```kotlin
"draw-guess" to GameAccent(0xFFB45309, 0xFFFCD34D)
```
Checked offline against `ui/GameTheme.kt`: light vs both light surfaces 3.87:1 or better, dark vs both dark surfaces
well above 3:1, label on the accent 5.0:1 (light, white text) and 13.2:1 (dark, near-black text); neither value is used
by any existing accent. `DrawGuessHubSpecsTest` re-checks all of that, so if another agent picks the same amber the
test names the clash.

## 4. Route (`MainActivity`)

```kotlin
composable("draw-guess") {
    val g = rememberActiveModule(sessionManager, "draw-guess") { DrawGuessGame() }
    DrawGuessScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, onMatchEnded = finishMatch)
}
```
Imports: `com.gamesuite.games.drawguess.DrawGuessGame`, `com.gamesuite.ui.DrawGuessScreen`. The game is resumable
(`ResumableGame`): the screen calls `sessionManager.tryResume(game)` and the game saves through `GameChrome`'s save.

## 5. Tips (`GameTips.tips`), English literals equal the resources

```kotlin
"draw-guess" to listOf(
    "The drawer picks one of three pictures, then holds the button to see the word. Everyone else looks away.",
    "Think you know it? Tap your name to buzz in. The clock stops while you guess, and a wrong guess sits you out.",
    "Stick figures are fine. Quick, clear drawings earn more points than perfect ones."
)
```
These equal `tip_draw_guess_1..3` in `res/values/draw_guess_coach_strings.xml` (Spanish in `values-es/`).
`DrawGuessHubSpecsTest` checks them (3 lines, each at most 140 characters, EN literal
equal to the resource). `GameTipsTest` reads only `polish_strings.xml`, so move the six `tip_draw_guess_*` strings
there if that test must see them (same as the Tap Race notes).

## 6. Coach (`GameCoach`)

- `motifs`: `"draw-guess" to CoachMotif.PATH` (a drawn line; no better fit exists).
- `entries`: `entry("draw-guess", listOf(R.string.coach_draw_guess_s1, R.string.coach_draw_guess_s2, R.string.coach_draw_guess_s3, R.string.coach_draw_guess_s4, R.string.coach_draw_guess_s5), R.string.coach_draw_guess_fact)`
- Strings: `coach_draw_guess_s1..s5` and `coach_draw_guess_fact`, both locales (in `draw_guess_coach_strings.xml`).
- `ownCoach` decision: NO own coach. The shared coach card (five steps and a fact) explains the whole game; there is no
  in-game tutorial overlay. The game's own help dialog (`draw_guess_help_title/body`) is in the game menu.

## 7. Behaviour the integrator should know

- Same screen only; 2 to 8 humans; teams only at an even table of four or more (launched with `teams`; the engine
  checks the rosters' team ids 0/1 are balanced and otherwise plays without teams).
- The result reported at the end is every playing seat's score; the top scorer (or every member of the top team)
  is `isWinner`. A tie gives several winners; a tied team total gives none.
- Words follow the device language (Spanish for `es`, else English) at play time, not the language a saved game
  started in. The word bank (150 words) lives in code (`DrawGuessWords.kt`), not in resources, so the Spanish-string
  parity test on resources does not cover it; `DrawGuessWordBankTest` does.
- Back / Leave mid-game uses `abortMatch()` (nothing recorded, game saved by `GameChrome`); "Back to menu" on the
  final scores uses `leaveSession()` (records the result).

## Nothing was built or run

No Gradle, no compiler, no emulator and no unit test was run for this game; the code, strings and tests were written
and desk-checked only. Expect to fix compile errors and possibly a failing test on the first gate.
