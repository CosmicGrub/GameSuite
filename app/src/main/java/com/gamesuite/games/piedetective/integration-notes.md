# Pie Detective (`pie-detective`): what the coordinator must integrate

Nothing here is registered anywhere yet (the owner of the shared files was told not to edit them). Cooperative
same-screen whodunit for 2 to 4 seats (Sidekick computer seats fill empty seats). Audience KIDS, minAge 5. Not networked.
Design: `docs/design/kids-games/pie-detective.md`.

Pure engine (JVM tested): `com.gamesuite.games.piedetective` (`PieEngineImpl`, `PieRules`, `PieDealer`, `PieSaveCodec`).
Game module: `PieDetectiveGame` (`ResumableGame`, `PausableGame`). Art: `com.gamesuite.ui.PieDetectiveArt` (vector, no
bitmaps). Screen: `com.gamesuite.ui.PieDetectiveScreen`.

## 1. Hub spec (`core/GameHubSpecs.kt`)

Add `PieDetectiveHubSpecs.spec` to `baseSpecs()`, in the ARCADE_PUZZLE section. Do NOT add `"pie-detective"` to
`GameHubSpecs.networkedGames`. The spec already sets `audience = KIDS, minAge = 5`; `GameAudienceTable` needs no row (if
`GameAudienceTest` insists: `put("pie-detective", k(5))`). Modes: `cpu` (VS_CPU, route `pie-detective`) and `same`
(PASS_AND_PLAY). Option keys reaching the screen as extras: `band`, `hints`, `autoflip`, `handicap`, `rotate`.

## 2. Glyph (`core/GameCatalog.kt`)

```
"pie-detective" -> "🔍"   // magnifying glass; also PieDetectiveHubSpecs.GLYPH
```

## 3. Accent colours (`ui/GameTheme.kt`, `GameThemes.accents`)

```
"pie-detective" to GameAccent(0xFF8A2F5B, 0xFFF2A6C8),
```
(also `PieDetectiveHubSpecs.accents`; `PieDetectiveHubSpecsTest` checks contrast and that no other game already uses either colour.)

## 4. Route (`MainActivity.kt`)

```kotlin
composable("pie-detective") {
    val g = rememberActiveModule(sessionManager, "pie-detective") { PieDetectiveGame() }
    PieDetectiveScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, onMatchEnded = finishMatch)
}
```
Imports: `com.gamesuite.games.piedetective.PieDetectiveGame`, `com.gamesuite.ui.PieDetectiveScreen`.

## 5. Tips (`ui/GameTips.kt`, `GameTips.tips`)

Literals equal `tip_pie_detective_1..3` in `res/values/pie_detective_coach_strings.xml` (also `PieDetectiveHubSpecs.tips`):

```kotlin
"pie-detective" to listOf(
    "Pick a clue, hold the magic lens to peek, then tell your friends what you saw.",
    "Tap every animal that does not match the clue. They are not the thief.",
    "Stuck? Tap the Sidekick hint. Catch the thief before it reaches the back gate."
),
```

## 6. Coach card (`core/GameCoach.kt`)

Motif: `"pie-detective" to CoachMotif.<any board / lineup motif>`. Entry:

```kotlin
entry("pie-detective", listOf(R.string.coach_pie_detective_s1, R.string.coach_pie_detective_s2, R.string.coach_pie_detective_s3,
    R.string.coach_pie_detective_s4, R.string.coach_pie_detective_s5), R.string.coach_pie_detective_fact),
```
The screen does NOT open the coach card (an unregistered id would fail). After registering, add
`rememberClCoach("pie-detective", ready = true)` and a `ClCoachButton` in the header as `BridgeBuildersScreen` does, and the
first-run auto-open then comes for free. Until then the "?" button opens the 8-card tutorial dialog (`pie_detective_tutorial_*`).

## 7. Resources

`pie_detective_strings.xml`, `pie_detective_coach_strings.xml`, `pie_detective_hub_strings.xml`, each in `values` and
`values-es`. The hub file holds only texts that did not already exist (`hub_same_screen`, `hub_player_1..4`, `hub_nobody_all_equal`
etc. are reused); `PieDetectiveHubSpecsTest` proves every spec text resolves in both locales and that no other file
defines the same keys. If another branch later adds one of ours, delete the duplicate from our hub file.

## 8. Done vs open (so a fresh agent can resume from the branch alone)

Done and covered by JVM tests (`com.gamesuite.games.piedetective.*`): cast, rules, deterministic dealer, engine (peek,
flip, sneak, catch, Lucky peek, Sidekick, hint, case book), 3000-seed balance check per band against the design table,
validated resume snapshot (`PieSaveCodec`, rejects not repairs) plus corruption tests, game wrapper (abort, book win
rule O3, `playAgain` through `LaunchPolicy.requestNewRound()`, `PausableGame`), hub spec, EN + ES strings, tips, coach
text, screen string coverage and "no hard-coded English" checks.

Compiled, NOT played: the Compose screen has never run on a device or emulator (no installs were authorised). Unverified:
real lens press-and-hold feel, TalkBack announcements, 200 percent font layout, far-seat rotation, the Sidekick pacing.

Open / later:
* Coach card + first-run tutorial auto-open (needs the shared registration above).
* Sound pack and richer haptics (only the existing `SfxKind` / `HapticSignal` cues are used).
* Per-profile Lucky peek list through `extras["handicap"]` comma form: the engine parses it; no profile screen feeds it yet.
* Nearby private-peek mode (design doc section 3.6), `extras["seed"]` case numbers, extra cast skins.
* Playtests with children 5 to 7 (design doc 3.7 phase 4).
