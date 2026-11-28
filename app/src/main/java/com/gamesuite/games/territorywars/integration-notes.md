# Territory Wars (`territory-wars`): what the integrator must register

Status: rules engine, three CPU levels, validated resume, Hub spec, Compose screen and tests are built. Nothing below is registered (the brief
forbids editing the shared files). **Compiled and unit-tested on the JVM; not played on a device** (device installs are not allowed for this work).
Audience ADULTS, minAge 10; local only (pass and play and vs computers, 2 to 6 seats).

## 1. Hub spec (`core/GameHubSpecs.kt`)
Add `TerritoryWarsHubSpecs.territoryWars` to `baseSpecs()` (BOARD section). Do NOT add `"territory-wars"` to `networkedGames`.
Hub options reach the game as `LaunchOptions.extras` and are parsed by `TerritoryWarsOptions.fromExtras` (tested).

## 2. Glyph (`core/GameCatalog.kt`)
`"territory-wars" -> "🗺"` (also `TerritoryWarsHubSpecs.GLYPH`).

## 3. Accent (`ui/GameTheme.kt`, `GameThemes.accents`)
`"territory-wars" to GameAccent(0xFF3B4D8A, 0xFF7F95CC),` (`TerritoryWarsHubSpecsTest` checks contrast; the doc notes distinctness from the other accents
was never checked, add it to the integrator's `GameThemeTest` run).

## 4. Route (`MainActivity.kt`)
```kotlin
composable("territory-wars") {
    val g = rememberActiveModule(sessionManager, "territory-wars") { TerritoryWarsGame() }
    TerritoryWarsScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, onMatchEnded = finishMatch)
}
```
Imports: `com.gamesuite.games.territorywars.TerritoryWarsGame`, `com.gamesuite.ui.TerritoryWarsScreen`. The default constructor already uses the real engine and bot.

## 5. Tips (`ui/GameTips.kt`) - literals must equal `res/values/territory_wars_coach_strings.xml`
```kotlin
"territory-wars" to listOf(
    "Tap your territory, then a neighbouring enemy, and roll to attack.",
    "Holding a whole region pays bonus armies every turn.",
    "Keep your armies together: one strong stack beats many thin ones."
),
```

## 6. Coach (`core/GameCoach.kt`)
Entry from `coach_territory_wars_s1..s5` and `coach_territory_wars_fact`; motif `CoachMotif.DICE`. The screen does not open the coach itself (not registered
yet); add `rememberClCoach("territory-wars", ...)` + `ClCoachButton` to its header as `SlideClimbScreen` does.

## 7. Strings
Existing `territory_wars_strings.xml` plus the new `territory_wars_coach_strings.xml`, both in `values` and `values-es`. Spanish still needs the native review.

## 8. Files
`games/territorywars/*.kt` (`StandardTerritoryWarsEngine` + `StandardBattleResolver`, `StandardTerritoryWarsBot`, `TerritoryWarsRestore`, game wrapper),
`ui/TerritoryWarsScreen.kt`, tests in `app/src/test/java/com/gamesuite/games/territorywars/`.

## Done / open / unverified
- Done: seeded deal, opening placement (batches of three) or auto-place, reinforcement with region bonuses and sets (shared escalating counter), forced turn-in at
  five cards, battles (1 to 3 dice vs 1 to 2, ties to the defender, exact-probability tests), move-in, conquest, region and elimination events (cards transferred),
  fortify (connected or adjacent), card draw, capture target, total conquest, round cap and stalemate guard with the tie-break chain, handicap, hand-over curtain,
  three CPU levels, validated save and resume (dice stream fast-forwarded), `PausableGame`, `LaunchPolicy.requestNewRound()` in `playAgain`.
- Open: missions (docs v1), the 10,000-war balance grid (two tests `@Ignore`d as P4 tuning), hint and tutorial overlays, battle dice animation (the screen announces
  the result in text and sound), pinch zoom of the map (the screen fits the whole map; the territory list is the accessible alternative), nearby / online play.
- Bots: Hard uses region-completion and region-denial weights plus threat-weighted reinforcement; it does not run the doc's search budget (decisions are single-pass).
- Unverified (compiled, not played): the Compose screen (map hit-testing, portrait / landscape / Fold / tablet layouts, TalkBack order, Reduced Motion).
