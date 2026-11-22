# Gem Cascade (`gem-cascade`): what the coordinator must integrate

Nothing here is registered anywhere yet (the owner of the shared files was told not to edit them). The working title
is a placeholder; it deliberately avoids the names, art, sounds and UI of existing match-3 games.
Local only: one person, not networked. Audience FAMILY, minAge 6.

Pure engine: `com.gamesuite.games.gemcascade` (JVM tested). Game module: `GemCascadeGame`. Screen:
`com.gamesuite.ui.GemCascadeScreen` (2D baseline, Canvas). Step log documentation: `docs/design/gem-cascade/EVENTS.md`.

## 1. Hub spec (`core/GameHubSpecs.kt`)

Add `GemCascadeHubSpecs.gemCascade` to `baseSpecs()` in the ARCADE_PUZZLE section. Do NOT add `"gem-cascade"` to
`GameHubSpecs.networkedGames`. The spec already sets `audience = FAMILY, minAge = 6`, so `GameAudienceTable` needs no
row (if `GameAudienceTest` insists: `put("gem-cascade", f(6))` in the table's own idiom).

It has two modes: `play` (SOLO, route `gem-cascade`; options `type`, `level`, `size`, `switchMode`) and `daily`
(DAILY, route `gem-cascade-daily`).

## 2. Glyph (`core/GameCatalog.kt`)

```
"gem-cascade" -> "💎"   // gem stone; also GemCascadeHubSpecs.GLYPH
```

## 3. Accent colours (`ui/GameTheme.kt`, `GameThemes.accents`)

```
"gem-cascade" to GameAccent(0xFF0B6B73, 0xFF5CD6D0),
```
(also `GemCascadeHubSpecs.ACCENT_LIGHT` / `ACCENT_DARK`; the hub-specs test checks contrast. Distinctness from
the other accents was not checked: it is a teal; pick another if it is too close to one already in the table.)

## 4. Routes (`MainActivity.kt`)

```kotlin
composable("gem-cascade") {
    val g = rememberActiveModule(sessionManager, "gem-cascade") { GemCascadeGame() }
    GemCascadeScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, onMatchEnded = finishMatch)
}
composable("gem-cascade-daily") {
    val g = rememberActiveModule(sessionManager, "gem-cascade-daily") { GemCascadeGame() }
    GemCascadeScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, daily = true, onMatchEnded = finishMatch)
}
```
Imports: `com.gamesuite.games.gemcascade.GemCascadeGame`, `com.gamesuite.ui.GemCascadeScreen`. If the daily route must
share the same module key as the normal one for saves, mirror how the other `-daily` routes are wired.
The daily board is `GemLevels.daily(UtcDate.epochDay(now))`: the same 8 x 8 score puzzle for everyone on a UTC day.

## 5. Tips (`ui/GameTips.kt`, `GameTips.tips`)

Literals must equal `tip_gem_cascade_1..3` in `res/values/gem_cascade_strings.xml` (no apostrophes):

```kotlin
"gem-cascade" to listOf(
    "Swap two neighbouring gems so three or more of a kind line up.",
    "Match four for a Flare, five for a Prism, or an L or T for a Burst.",
    "Stuck? Tap Hint. Boosters are free but limited in each game."
),
```

## 6. Coach card (`core/GameCoach.kt`)

Motif: `"gem-cascade" to CoachMotif.<any grid / tiles motif>`. Entry:

```kotlin
entry("gem-cascade", listOf(R.string.coach_gem_cascade_s1, R.string.coach_gem_cascade_s2, R.string.coach_gem_cascade_s3,
    R.string.coach_gem_cascade_s4, R.string.coach_gem_cascade_s5), R.string.coach_gem_cascade_fact),
```
The screen does not open the coach card itself (an unregistered id would fail). After registering, add
`rememberClCoach("gem-cascade", ready = true)` and a `ClCoachButton` in the header, as `SlideClimbScreen` does.

## 7. Assets and resources

* `app/src/main/assets/gem_cascade/levels.json`: the 30 Quest levels (validated and proven solvable by tests).
* Strings: `gem_cascade_strings.xml`, `gem_cascade_hub_strings.xml`, `gem_cascade_coach_strings.xml` in `values` and `values-es`.
  The hub file only holds texts that did not already exist; if another branch later defines the same `hub_*` key
  (for example `hub_zen`), delete the duplicate from ours.

## 8. Not done / for later tasks

* Personal-best and stats store (the game reports its score through `GameResult` only).
* Style packs: the screen draws the flat 2D look directly. Art-style packs should read the same `GemEvent` list.
* Haptic and sound cues use the existing `HapticSignal` / `SfxKind` sets; the richer cue sheet is on `session/ws-haptics`.
* Computer Arena hook: `GemBot` (Easy, Medium, Hard) is ready; no arena wiring was added.
* Nothing was run on a device or emulator.
