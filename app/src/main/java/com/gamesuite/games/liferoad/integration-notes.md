# Life Road (`life-road`): what the integrator must register

Status: engine, CPU players, resume, Hub spec, strings (EN+ES), coach/tips strings, Compose screen and tests are built.
Nothing below is registered yet (the brief forbids editing the shared files). **Compiled and unit-tested on the JVM; not
played on a device** (device installs are not allowed for this work).

Game id and route: `life-road`. Title **Life Road** (Spanish: Ruta de Vida). Local only (pass and play and vs
computers, 2 to 6 seats). Not networked. Audience FAMILY, minAge 8 (Junior rules, ages 6+, are a choice inside the spec).

## 1. Hub spec (`core/GameHubSpecs.kt`)
Add `LifeRoadHubSpecs.lifeRoad` to `baseSpecs()` (BOARD section, next to Ludo). Do NOT add `"life-road"` to `networkedGames`.
Hub options reach the screen as `LaunchOptions.extras` (`rules`, `length`, `handicap`, `handicapFor`, `catchUp`) and are parsed by
`LifeRoadLaunch.configFrom` (tested).

## 2. Glyph (`core/GameCatalog.kt`)
`"life-road" -> "🛣️"` (also `LifeRoadHubSpecs.GLYPH`).

## 3. Accent (`ui/GameTheme.kt`, `GameThemes.accents`)
`"life-road" to GameAccent(0xFF971A0C, 0xFFDE867C),` (also `LifeRoadHubSpecs.ACCENT_LIGHT/DARK`; `LifeRoadHubSpecsTest` checks contrast
and uniqueness).

## 4. Route (`MainActivity.kt`)
```kotlin
composable("life-road") {
    val g = rememberActiveModule(sessionManager, "life-road") { LifeRoadGame() }
    LifeRoadScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, onMatchEnded = finishMatch)
}
```
Imports: `com.gamesuite.games.liferoad.LifeRoadGame`, `com.gamesuite.ui.LifeRoadScreen`.

## 5. Tips (`ui/GameTips.kt`) - literals must equal `res/values/life_road_coach_strings.xml`
```kotlin
"life-road" to listOf(
    "Tap Spin to walk the road. Pay stops pay your salary when you pass them.",
    "At a fork, pick your road. Learn costs coins now and pays more later.",
    "Family and memories are worth points too. Retire with the most to win."
),
```

## 6. Coach (`core/GameCoach.kt`)
Motif `CoachMotif.DICE` (or any). Entry:
```kotlin
entry("life-road", listOf(R.string.coach_life_road_s1, R.string.coach_life_road_s2, R.string.coach_life_road_s3,
    R.string.coach_life_road_s4, R.string.coach_life_road_s5), R.string.coach_life_road_fact),
```
The screen does not open the coach itself (it is not registered yet); once registered, add `rememberClCoach("life-road", ...)` +
`ClCoachButton` to the screen header exactly as `SlideClimbScreen` does, and leave `"life-road"` out of `ownCoachInGame`.

## 7. Strings (new files only)
`life_road_strings.xml` (401 keys incl. `hub_*`) and `life_road_coach_strings.xml`, each in `values` and `values-es`. The screen reuses the
shared `ccl_leave_title`, `ccl_leave_body`, `ccl_leave_body_session`. Spanish still needs the native review (design doc OQ12).

## 8. Files
`games/liferoad/*.kt` (engine `StandardLifeRoadEngine`, `LifeRoadBot`, `LifeRoadValidation`, `LifeRoadSnapshot`, `LifeRoadLaunch` with
`LifeRoadLayout`), `ui/LifeRoadScreen.kt`, tests in `app/src/test/java/com/gamesuite/games/liferoad/`.

## Done / open / unverified
- Done: all rules of docs section 2 (board graph, spinner, forks, pay stops, bold squares, jobs, homes, family, milestones, cover, loans,
  fresh start, 64 + 16 cards, retirement lanes, settling, last light, scoring and tie-breaks, Junior, boost and catch-up), three CPU levels,
  validated snapshot (all TODO(P4) checks) with corruption tests, `PausableGame`, `LaunchPolicy.requestNewRound()` in `playAgain`.
- Open: the 20,000-game balance grid of docs 3.4 (three tests are `@Ignore`d as P5 tuning; CI-sized sanity runs pass), the hint button,
  the 16-step tutorial overlay, per-square juice and confetti, networked play (P8), Hard CPU route-value table (Hard uses the Medium
  heuristics plus the doc's lane, lucky-coin and market rules).
- Card chain rule detail: effects of one card run coins first, then a move, so a "move and collect" card collects at the square it started on.
- Unverified (compiled, not played): the Compose screen (layout in portrait, landscape, Fold and tablet, TalkBack order, Reduced Motion).
