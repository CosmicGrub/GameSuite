# Slide and Climb (`chutes-race`): what the coordinator must integrate

Nothing in this package was compiled or run. The author did not run Gradle, tests or an emulator.
Everything below touches shared files that the game's owner was told not to edit.

Game id and route: `chutes-race`. Title: **Slide and Climb** (Spanish: Desliza y Sube). Local only:
pass and play and vs computers, 2 to 4 seats. Not networked.

## 1. Hub spec (`core/GameHubSpecs.kt`)

Add `SlideClimbHubSpecs.slideClimb` (object in `com.gamesuite.games.slideclimb`) to `baseSpecs()`, in the
BOARD section (next to Ludo). The spec already sets `audience = GameAudience.KIDS, minAge = 4`.

Do NOT add `"chutes-race"` to `GameHubSpecs.networkedGames` (the engine has no host or message type).

Optional: `GameAudienceTable` needs no row (the spec's own tags win). If `GameAudienceTest` insists on a row
for every KIDS game, add `put("chutes-race", k(4))`.

## 2. Glyph (`core/GameCatalog.kt`, the `when` of menu glyphs)

```
"chutes-race" -> "🎢"   // roller coaster; also SlideClimbHubSpecs.GLYPH
```
Chosen because it exists on every supported Android version (the ladder and playground-slide emoji do not
render before Android 12/13) and no other game uses it.

## 3. Accent colours (`ui/GameTheme.kt`, `GameThemes.accents`)

```
"chutes-race" to GameAccent(0xFF541380, 0xFFAF85CC),
```
(also `SlideClimbHubSpecs.ACCENT_LIGHT` / `ACCENT_DARK`; `SlideClimbHubSpecsTest` checks the contrast.)

| | colour | vs lightSurfaces (FFFBFE / E6E0E9) | vs darkSurfaces (1C1B1F / 36343B) | label text |
|---|---|---|---|---|
| light | 0xFF541380 | 11.5 / 9.1 | n/a | white 11.8 |
| dark  | 0xFFAF85CC | n/a | 5.75 / 4.12 | near-black (101014) 6.37 |

All are above the 3.0 non-text and 4.5 text minimums in `GameThemes`. Distinctness from the existing
accents (plain RGB distance, nearest first): light 541380 is 43 from `paddle-duel`, 49 from `darts`, 60 from
`bowling`; dark AF85CC is 43 from `six-ball-puzzle`, 45 from `bowling` and `chess`. That is the largest gap
available at these contrast levels (the palette is crowded; a search over all hues found nothing better
than 43). If you would rather avoid the violet family, the next best option found was a red-orange.

## 4. Route (`MainActivity.kt`)

Next to the `ludo` / `pig-dice` routes:

```kotlin
composable("chutes-race") {
    val g = rememberActiveModule(sessionManager, "chutes-race") { SlideClimbGame() }
    SlideClimbScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, onMatchEnded = finishMatch)
}
```
Imports: `com.gamesuite.games.slideclimb.SlideClimbGame`, `com.gamesuite.ui.SlideClimbScreen`.

## 5. Tips (`ui/GameTips.kt`, `GameTips.tips`)

Literals must equal the resources in `res/values/slide_climb_coach_strings.xml` (`tip_chutes_race_1..3`; Spanish in
`values-es`). No apostrophes, so no escaping is needed:

```kotlin
"chutes-race" to listOf(
    "Tap the big die to roll. Your piece hops that many squares.",
    "Land at the foot of a rope to climb up. Land at the top of a slide and down you go.",
    "First to square 100 wins. A spare roll lets you swap one roll for a new one."
),
```
(`GameTipsTest.stringsDir` reads every file in `res/values`, so the tips can stay in our coach file; move them
to `polish_strings.xml` only if you prefer them all in one place.)

## 6. Coach card (`core/GameCoach.kt`)

Motif: `"chutes-race" to CoachMotif.DICE` in `motifs`. Entry:

```kotlin
entry("chutes-race", listOf(R.string.coach_chutes_race_s1, R.string.coach_chutes_race_s2, R.string.coach_chutes_race_s3,
    R.string.coach_chutes_race_s4, R.string.coach_chutes_race_s5), R.string.coach_chutes_race_fact),
```
Strings are in `slide_climb_coach_strings.xml` (EN and ES). `SlideClimbScreen` opens the coach itself through
`rememberClCoach("chutes-race", ...)` (the Club card and the Club `coachSeen` flag, exactly like Ludo and Pig).

**ownCoach decision: do NOT add `"chutes-race"` to `GameCoach.ownCoachInGame`.** The screen has no card of its own,
it reuses the Club card, so the Hub's first-visit auto-open and the in-game first-run open share one flag and
a child never sees two cards.

## 7. Things to know

- `GameHubSpec(audience, minAge)` and `GameAudience` already exist in core; the spec uses those exact names.
- The Hub options reach the screen as `LaunchOptions.extras`: `exactFinish` ("true"/"false", default false: past 100
  you bounce back) and `handicap` ("none" | "all" | "1".."4": that player, 1-based, holds one spare roll). Parsing
  is `parseSlideClimbHandicap(value, seats)`; the keys are deliberately simple so you can standardise handicaps later.
- Handicap = one spare roll per board, per chosen seat. After rolling, that seat sees two round buttons (a check to
  keep the roll, a die in a circular arrow to roll again, once); every other seat moves at once. The computer decides
  with `SlideClimbBot.wantsSpareRoll` (the only computer decision in the game): Easy flips a coin derived from the
  state, Medium rerolls a slide or standing still, Hard also rerolls a roll that gains fewer than 2 squares. The
  "Computer level" hub option is therefore hidden (`visibleWhen`) until a handicap is chosen.
- Rules: no extra turn on a six, no undo (chance), 8 ropes and 8 slides from `SlideClimbLayout.DEFAULT`; a seeded
  layout (`SlideClimbGame.layoutSeed`) exists in the engine but no hub option exposes it.
- New string files (no edits to shared strings): `slide_climb_strings.xml`, `slide_climb_coach_strings.xml`,
  `slide_climb_hub_strings.xml`, each in `values` and `values-es`. The screen reuses existing shared strings
  `ccl_leave_title`, `ccl_leave_body`, `ccl_leave_body_session`, `ccl_session`, `ccl_cpu_tag`, `cc_why_cpu`, `cc_why_over`,
  `yg_win`, `hub_you`. The hub texts reuse existing `hub_*` entries for "vs Computers", "Pass & Play", the Ludo
  blurbs and "Exact roll to finish"; the new ones are `hub_slide_and_climb`, `hub_roll_the_dice_and_race_to_square_100`,
  `hub_on_land_on_100_exactly_off_bounce_back`, `hub_spare_roll`, `hub_one_extra_roll_to_use_instead_of_a_bad_one`,
  `hub_no_spare_roll`, `hub_everyone_has_one`, `hub_player_1_only` .. `hub_player_4_only`. A scan of every
  `values*/` file found no name clashes at the time of writing.
- Files: `games/slideclimb/{SlideClimbLayout,SlideClimbRules,SlideClimbGame,SlideClimbHubSpecs}.kt`,
  `ui/SlideClimbScreen.kt`, `ui/SlideClimbArt.kt`, tests in `app/src/test/java/com/gamesuite/games/slideclimb/`.
  `SlideClimbGame.forcePositions` is `internal` and exists only for tests.
- The screen depends on these existing internals (same module, package `com.gamesuite.ui`): `ChineseSeatColors`,
  `drawMarble`, `drawSeatGlyph`, `drawBurst`, `Die3DView`, `ClSeatBadge`, `ClStatusLine`, `ClWhyLine`, `ClFinishedPanel`,
  `rememberClCoach`, `ClCoachButton`, `youAware`, `seatLabel`, `isYouName`. If one of them is renamed by a concurrent
  change the screen will not compile.

## 8. What to run at the gate

`./gradlew :app:testDebugUnitTest --tests "com.gamesuite.games.slideclimb.*"`, then the whole unit-test suite
(HubLocalizationTest, GameTipsTest, GameCoachTest, GameThemeTest and GameAudienceTest all iterate over every spec).
Then play it once on a device: portrait, landscape and a tablet; Reduced Motion on and off; TalkBack on; a 4 seat
game with one handicap seat.
