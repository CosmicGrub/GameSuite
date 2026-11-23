# Act It Out (`act-it-out`): what the coordinator must add

Nothing below has been compiled, built or run. Everything was written against the sources as they stand in
`session/wave-3`; the first Gradle run is the first time any of it is checked.

## Hard dependency on another agent's work

`ActItOutHubSpecs.kt` imports `com.gamesuite.core.GameAudience` and passes `audience = GameAudience.FAMILY, minAge = 4`
to `GameHubSpec`. Both are being added by the core agent; until they land this file (and the test that reads
`spec.audience` / `spec.minAge`) does not compile. If the enum lives in another package, fix the one import.

## 1. Hub spec (`core/GameHubSpecs.kt`)

Add `ActItOutHubSpecs.actItOut` to `baseSpecs()` (or `ActItOutHubSpecs.all`). Do NOT add `"act-it-out"` to
`GameHubSpecs.networkedGames` (same-device only, no Nearby/Online).
Family used: `HubFamily.ARCADE_PUZZLE` (what the other party games use). `HubFamily.TOOLKIT` is the alternative if you
would rather list it with the Party Toolkit.

## 2. Glyph (`core/GameCatalog.kt`, the id -> glyph `when`)

`"act-it-out" -> "🎭"`

## 3. Accents (`ui/GameTheme.kt`, `GameThemes.accents`)

```kotlin
"act-it-out" to GameAccent(0xFF8E2B6D, 0xFFF08CCB),
```

Contrast checked against `GameThemes.lightSurfaces` / `darkSurfaces` (python, same WCAG formula as `Wcag`):
light 0xFF8E2B6D = 7.54 and 5.96, white label 7.73; dark 0xFFF08CCB = 7.60 and 5.45, near-black label 8.42.
Neither hex is used by any existing accent (`ActItOutHubSpecsTest` re-checks uniqueness and contrast).

## 4. Route (`MainActivity.kt`)

```kotlin
import com.gamesuite.games.actitout.ActItOutGame
import com.gamesuite.ui.ActItOutScreen
...
composable("act-it-out") {
    val g = rememberActiveModule(sessionManager, "act-it-out") { ActItOutGame() }
    ActItOutScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, onMatchEnded = finishMatch)
}
```

`settingsViewModel` is accepted only so the call looks like every other game's; the screen does not read it.
The screen calls `sessionManager.tryResume(game)`, so resume works through the normal Hub "Resume" card.

## 5. Tips (`ui/GameTips.kt`, `GameTips.tips`)

Copy `ActItOutHubSpecs.tips` (three lines; the English equals `tip_act_it_out_1..3` in
`res/values/act_it_out_coach_strings.xml`, Spanish in `values-es`):

```kotlin
"act-it-out" to listOf(
    "The actor never speaks or points at words. Everyone else shouts out guesses!",
    "Little kids can play from the picture. Choose Little kids for their team and turn on the bonus point.",
    "Hold the big pad to start, then tap Correct or Skip. You get three skips in each turn."
),
```

## 6. Coach (`core/GameCoach.kt`)

Strings exist in both locales: `coach_act_it_out_s1` .. `coach_act_it_out_s5` and `coach_act_it_out_fact`.

```kotlin
entry("act-it-out", listOf(R.string.coach_act_it_out_s1, R.string.coach_act_it_out_s2, R.string.coach_act_it_out_s3, R.string.coach_act_it_out_s4, R.string.coach_act_it_out_s5), R.string.coach_act_it_out_fact),
```

and a motif in the `motifs` map: `"act-it-out" to CoachMotif.CARDS`.

**ownCoach decision: NOT own.** The screen uses the shared Club coach (`rememberClCoach("act-it-out", ready = true)` and
`ClCoachButton`) exactly like Reaction Duel, so it shares the Hub's "seen" flag. Do not add the id to
`GameCoach.ownCoachInGame`.

## 7. Other things worth knowing

- **Hub strings**: `values{,-es}/act_it_out_hub_strings.xml` hold 28 `hubKey` names. Five texts the spec reuses already
  existed (`hub_30_seconds`, `hub_60_seconds`, `hub_90_seconds`, `hub_people_playing`, and the "Players who share this
  device..." help), so they are deliberately NOT redefined. Generic words I do define: `hub_everyone`, `hub_little_kids`,
  `hub_grown_ups`, `hub_two_teams`. If another wave-3 game's hub file defines the same key you will get a duplicate
  resource error: delete the duplicate from one file (keep the Spanish identical).
- **Names**: the Hub's pass-and-play name fields stop at 10 (`HUB_MAX_NAME_SLOTS` in `ui/GameHubScreen.kt`) but this game
  takes 3 to 12 people. Players 11 and 12 arrive as "Player 11/12" and can be renamed in the game's own setup step (every
  player has a name field there). Raising the constant to 12 is a one-line optional improvement in a shared file.
- **Category**: `GameCategory.OTHER`, so a tied top score is a DRAW for stats, not a loss (ARCADE/PUZZLE/WORD/UTILITY would
  turn "nobody won" into a loss). Local outcome follows the local seat (player 1) and their side's result.
- **Saved games** key on the roster and the Hub extras like every game. A turn in progress is saved as a curtain with the
  same card and the time that was left, so the card is never on screen when the app comes back.
- **Resources** also use these prefixes: `act_it_out_*` (screen text and 200 `act_it_out_card_<id>` cards),
  `coach_act_it_out_*`, `tip_act_it_out_*`, `hub_*` (hubKey names).
- **Tests added** (`app/src/test/java/com/gamesuite/games/actitout/`): `ActItOutDeckTest`, `ActItOutEngineTest`,
  `ActItOutHubSpecsTest`. The deck test holds the banned-word lists (substring list plus a whole-word list for short
  words such as "hand" or "ear" that would wrongly hit "handstand"/"bear").
- `GameCoachTest` / `GameTipsTest` / `HubLocalizationTest` will pick the game up once items 1, 5 and 6 are in.
