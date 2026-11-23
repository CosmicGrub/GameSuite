# Bridge Builders: what the coordinator must add

Game id `bridge-builders`, route `bridge-builders`. Nothing here was built or run by the author.

I own only: `games/bridgebuilders/**`, `ui/BridgeBuildersScreen.kt`, the four-pair string files
`res/values{,-es}/bridge_builders_{strings,coach_strings,hub_strings}.xml`, and the tests in
`app/src/test/java/com/gamesuite/games/bridgebuilders/`. Everything below is in shared files I did not touch.

## 0. Core dependency (another agent)

`BridgeBuildersHubSpecs` uses `GameAudience.KIDS` (import `com.gamesuite.core.GameAudience`) and the `GameHubSpec`
params `audience` and `minAge = 5`. It will not compile until those exist in `core/GameHub.kt` with exactly those names.
`BridgeBuildersHubSpecsTest` reads `spec.audience` and `spec.minAge`.

## 1. Hub spec object

`GameHubSpecs.baseSpecs()`: add `BridgeBuildersHubSpecs.spec` (or `+ BridgeBuildersHubSpecs.all`).
Do NOT add `"bridge-builders"` to `GameHubSpecs.networkedGames` (same-screen only; the engine has no messages).
Family `HubFamily.ARCADE_PUZZLE`; modes `cpu` (VS_CPU, helper robots 1..3, immediate) and `same` (PASS_AND_PLAY, 2..4 people
plus 0..2 helper robots, ready screen). Option keys that reach the screen as `LaunchOptions.extras`:
`band` (little|medium|big), `relaxed`, `handicap` (none|p1..p4), `rotate`. `difficulty` is the helper robots' level.

## 2. Glyph (`core/GameCatalog.kt`, the `when` around line 121)

`"bridge-builders" -> "🌉"`

## 3. Accents (`ui/GameTheme.kt`, `GameThemes.accents`)

`"bridge-builders" to GameAccent(0xFF8A5A00, 0xFFF2C46B)`

Contrast (checked by hand with the WCAG formula, and again by `BridgeBuildersHubSpecsTest` against `GameThemes.lightSurfaces`
/ `darkSurfaces` and `onAccent`): light 0xFF8A5A00 is 5.78 on 0xFFFFFBFE and 4.57 on 0xFFE6E0E9 (needs 3.0); dark
0xFFF2C46B is 10.51 on 0xFF1C1B1F and 7.53 on 0xFF36343B; label ink on each is above 4.5. Neither value equals any existing
accent (Carrom is the nearest hue: 0xFF8C6A00 / 0xFFF2C14E).

## 4. Route (`MainActivity.kt`, next to `composable("tug-of-war")`)

```kotlin
composable("bridge-builders") {
    val g = rememberActiveModule(sessionManager, "bridge-builders") { BridgeBuildersGame() }
    BridgeBuildersScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, onMatchEnded = finishMatch)
}
```
Imports: `com.gamesuite.games.bridgebuilders.BridgeBuildersGame`, `com.gamesuite.ui.BridgeBuildersScreen`.

## 5. First-run tips (`ui/GameTips.kt`, `GameTips.tips`)

English literals equal `tip_bridge_builders_1..3` in `values/bridge_builders_coach_strings.xml` (Spanish in `values-es/`).
`BridgeBuildersHubSpecs.tips["bridge-builders"]` holds them; paste:

```kotlin
"bridge-builders" to listOf(
    "Tap your lane when the sliding cart is inside the green spot. The nearer the middle, the sturdier the plank.",
    "Nobody can build more than two planks ahead of the slowest lane, so wait for your friends: the rope is tight.",
    "Finish every lane before the water covers the bridge, then everybody crosses together."
),
```
`GameTipsTest` reads only `polish_strings.xml` (names `tip_<id with _>_<n>`): move the three `tip_bridge_builders_*` strings
(both locales) from my coach file into `polish_strings.xml` when you paste, as was done for the other party games.
(Until then the app still works: `GameTips.localized` falls back to the English literal when a string is missing,
and my own test checks the literals equal my resources.)

## 6. Coach card (`core/GameCoach.kt`)

Motif: `"bridge-builders" to CoachMotif.PATH` in `motifs`. Entry (names are `bridge_builders_coach_*`, not `coach_<id>_*`):

```kotlin
entry("bridge-builders", listOf(R.string.bridge_builders_coach_s1, R.string.bridge_builders_coach_s2, R.string.bridge_builders_coach_s3, R.string.bridge_builders_coach_s4, R.string.bridge_builders_coach_s5), R.string.bridge_builders_coach_fact),
```
The screen calls `rememberClCoach("bridge-builders", ...)` and `ClCoachButton("bridge-builders", ...)`, which need this entry.
Without it the game must not be reachable.

## 7. ownCoach decision

Do NOT add `"bridge-builders"` to `GameCoach.ownCoachInGame`. The screen uses the shared Club coach card (like Tug of War), so
the Hub's first-visit auto-open is the only first-run card. (`rememberClCoach` marks `ClubProfile.coachSeen` itself.)

## 8. Strings and tests that touch shared things

- Hub strings: `bridge_builders_hub_strings.xml` defines only keys that did not already exist when written. It deliberately
  reuses `hub_same_screen`, `hub_turn_the_far_lanes_around`, the rotate toggle's help text, and the difficulty words
  (all from `party_tap_tug_hub_strings.xml` / the core hub strings), so Tap Race / Tug of War strings must stay. If another
  agent's new hub file defines the same key as one of mine (candidates: `hub_lane_1`..`hub_lane_4`, `hub_helper_robots`),
  the resource build fails with a duplicate: delete my copy. `BridgeBuildersHubSpecsTest.ourHubFileDefinesOnlyKeys...`
  fails first and names the file.
- `hubKey` hash suffixes were computed with Java `String.hashCode` semantics and cross-checked against an existing key.
- The only English-only value is `bridge_builders_robot_tag` ("(Robot)"), identical in Spanish by design.

## 9. Behaviour worth knowing when reviewing

- Cooperative result: win = every lane has all planks AND the crossing finished before the water reached 100%.
  Lose = water covered it. Result text is "We did it!" / "Try again together" (no winner/loser words; tested).
  `GameResult` marks everyone `isWinner = true` only if at least one bridge was built in the session (so the stats layer sees
  a team WIN); leaving with none built is an abort (nothing recorded, nobody loses alone); leaving before any try ends goes through `abortMatch()`.
- Game speed: Slow slows the cart (pace = 1 / delayScale, clamped 0.4..1.8); the water is not scaled.
- Resume: a try is saved with its planks, sturdiness and water; the cart and green spots are not, so a resumed try restarts
  from the 3 second countdown. Backgrounding calls `pause()`, which stops the water and cart.
- `handicap` also accepts a comma list of fractions ("0,0.5,0,0.25") per lane, so a future profiles foundation can pass
  per-player bonuses through `extras["handicap"]` without engine changes.
- `TapTugBoard`, `TapTugZoneLayout`, `TapTugPointerTracker`, `ContactSizes` and the seat glyph/pattern helpers are reused from
  the party pack (palm rejection, multi-touch, rotated far zones, TalkBack double-tap = one tap): do not rename them.

## 10. Honest status

Written without compiling or running anything: no Gradle, no emulator, no unit test was run. Expect a first-compile round of
small errors (imports, a Compose API detail) and possibly a numeric test tolerance to adjust.
