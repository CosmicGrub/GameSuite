# Property Tycoon (`property-tycoon`): what the integrator must register

Status: MVP engine (docs "MVP" column of 1.5), CPU players, validated resume, Hub spec, Compose screen and tests are built.
Nothing below is registered (the brief forbids editing the shared files). **Compiled and unit-tested on the JVM; not played on
a device** (device installs are not allowed for this work). Play money ("pearls") only; audience ADULTS, minAge 8.

## 1. Hub spec (`core/GameHubSpecs.kt`)
Add `PropertyTycoonHubSpecs.propertyTycoon` to `baseSpecs()` (BOARD section). Do NOT add `"property-tycoon"` to `networkedGames`.
The `auctions` and `trades` toggles are deliberately NOT offered yet (their engine features are gated by `PtRelease`, all false);
re-add them to `common` in `PropertyTycoonHubSpecs` when v1 ships. `PropertyTycoonLaunch.apply(game, extras, seats)` turns the
Hub's `LaunchOptions.extras` (`length`, `startPearls`, `handicap`, `handicapBoost`, `jackpot`, `doubleStipend`, `rentFreeSandbar`) into
game options and is tested.

## 2. Glyph (`core/GameCatalog.kt`)
`"property-tycoon" -> "🏝"` (also `PropertyTycoonHubSpecs.GLYPH`; desert island, check no other game uses it).

## 3. Accent (`ui/GameTheme.kt`, `GameThemes.accents`)
`"property-tycoon" to GameAccent(0xFF0A6B4B, 0xFF4FD6A0),` (also `PropertyTycoonHubSpecs.ACCENT_LIGHT/DARK`; `PropertyTycoonHubSpecsTest`
checks contrast and uniqueness).

## 4. Route (`MainActivity.kt`)
```kotlin
composable("property-tycoon") {
    val g = rememberActiveModule(sessionManager, "property-tycoon") { PropertyTycoonGame() }
    PropertyTycoonScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, onMatchEnded = finishMatch)
}
```
Imports: `com.gamesuite.games.propertytycoon.PropertyTycoonGame`, `com.gamesuite.ui.PropertyTycoonScreen`.

## 5. Tips (`ui/GameTips.kt`) - literals must equal `draft_tip_property_tycoon_1..3` in `res/values/property_tycoon_strings.xml`
```kotlin
"property-tycoon" to listOf(
    "Whole colours matter more than single tiles. Trade toward a set.",
    "Keep a cash cushion. One resort rent can end your game.",
    "Three cabins is the sweet spot: the rent jumps most for the price."
),
```
(The tips mention trading, which ships in v1; reword the first tip before registering if trades are still off.)

## 6. Coach (`core/GameCoach.kt`)
Entry from `coach_property_tycoon_s1..s5` and `coach_property_tycoon_fact`; motif `CoachMotif.DICE`. The screen does not open the coach itself
(not registered yet); add `rememberClCoach("property-tycoon", ...)` + `ClCoachButton` to its header as `SlideClimbScreen` does.

## 7. Strings
Existing `property_tycoon_strings.xml` in `values` and `values-es` (no new file was needed). Spanish still needs the native review.

## 8. Files
`games/propertytycoon/*.kt` (`StandardPropertyTycoonEngine`, `StandardPropertyTycoonBot`, `PtValidate`, `PropertyTycoonLaunch`, game wrapper),
`ui/PropertyTycoonScreen.kt`, tests in `app/src/test/java/com/gamesuite/games/propertytycoon/`.

## Done / open / unverified
- Done: 40-tile ring, two dice, doubles and the three-doubles Sandbar, buying, rent (colour sets, buildings, ferries, mills, mode rent percent,
  Rising Tide), taxes, 16 + 16 cards, the Sandbar (roll, fine, free-pass card, forced fine on the third turn), cabins and resorts with even
  building and a limited supply, debt interrupt (sell buildings, then bankruptcy to a player or to the bank), last player standing, round cap
  with net-worth winner, handicap, jackpot / double stipend / rent-free Sandbar toggles, three CPU levels, validated save and resume,
  `PausableGame`, `LaunchPolicy.requestNewRound()` in `playAgain`.
- Open (docs v1): auctions, pledging and the trading UI and bot trade policy (the engine answers `FEATURE_DISABLED`; tests for those stay `@Ignore`d),
  the 10,000-game balance grid (two tests `@Ignore`d as P4 tuning), tutorial overlay, nearby / online play.
- Rule details worth knowing: a card that makes every rival collect (Pay each rival) when the payer is short opens a debt to the bank that is
  split among the rivals on settlement; "collect from each rival" takes at most what a rival holds (no debt for the giver).
- Legal: the ring layout (40 tiles, four corners, ferries and mills) is the genre's usual shape; names, art and card text are original, but an owner
  trademark / trade-dress read is still required before a store listing (docs 1.4). The deny-list test only covers strings.
- Unverified (compiled, not played): the Compose screen (portrait, landscape, Fold, tablet, TalkBack order, Reduced Motion).
