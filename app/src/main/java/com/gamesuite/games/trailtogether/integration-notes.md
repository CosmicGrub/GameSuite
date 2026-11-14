# Trail Together: what the coordinator must add

Game id `trail-together`, route `trail-together`, Kotlin package `com.gamesuite.games.trailtogether`, screen
`com.gamesuite.ui.TrailTogetherScreen` (art in `ui/TrailTogetherArt.kt`). Same screen only (solo, helper pals, up to 4
people): do NOT add the id to `GameHubSpecs.networkedGames`. Nothing in this branch touches a shared registration file.

I own only: `games/trailtogether/**`, `ui/TrailTogetherScreen.kt`, `ui/TrailTogetherArt.kt`, the string files
`res/values{,-es}/trail_together_{strings,hub_strings,coach_strings}.xml` and the tests in
`app/src/test/java/com/gamesuite/games/trailtogether/`.

## 0. Core dependency

`TrailTogetherHubSpecs` uses `GameAudience.KIDS` and `GameHubSpec(audience =, minAge = 4)` (both exist on this branch).
`TrailTogetherScreen` uses the existing `GameChrome`, `ClCoachButton`, `rememberClCoach`, `seatLabel`, `isYouName`,
`drawBurst`, `fitBoard`, `rememberParticleBurst`, `rememberProceduralSfx`, `rememberHaptics`. No shared code was changed.

## 1. Hub spec

`GameHubSpecs.baseSpecs()`: add `TrailTogetherHubSpecs.spec` (or `+ TrailTogetherHubSpecs.all`).
Family `HubFamily.BOARD`, audience `KIDS`, `minAge = 4`. Three modes, all route `trail-together`:

| mode id | kind | start | seats |
| --- | --- | --- | --- |
| `solo` | SOLO | immediate | 1 (you move two pals) |
| `cpu` | VS_CPU | immediate | 2-4 (you + 1..3 helper pals) |
| `same` | PASS_AND_PLAY | ready screen | 2-4 (people 2..4 + helper pals 0..2) |

Extras the screen reads from `LaunchOptions.extras`: `band` (little|medium|big), `hints` (auto|on|off; `auto` is the
design's rule "glow on for the Little trail only"), `rotate` (same screen only), and ONE handicap key for the table
size: `handicap` (2 seats), `handicap3`, `handicap4` (values none|p1..pN). The Hub hides the keys of the other table sizes
and `buildHubLaunch` drops hidden keys, so a stale choice for a seat that is not there never arrives
(`TrailHandicap.fromExtras` also refuses it). This replaces the design doc's single `handicap` row with the same
per-table-size pattern Bridge Builders uses. `difficulty` is the helper pals' level.

Hub strings are already in `res/values{,-es}/trail_together_hub_strings.xml` (28 `hubKey` names). Texts that already
existed elsewhere are reused and NOT repeated ("Same screen", "Nobody, all equal", "Player 1".."Player 4", "Never",
"A good mix for most families."). Watch for a duplicate resource name if another team adds the same plain words in their own
hub file (Android fails the build on a duplicate; keep one copy, the English must stay identical;
`TrailTogetherHubSpecsTest.everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey` and `HubLocalizationTest` check it).
The new names are: `hub_trail_together`, `hub_trail_size`, `hub_little_trail`, `hub_medium_trail`, `hub_big_trail`,
`hub_helper_glow`, `hub_little_trail_only`, `hub_always`, `hub_turn_the_far_hands_around`, `hub_helper_pal_level`,
`hub_helper_pals`, `hub_extra_card_for`, `hub_play_on_my_own`, `hub_play_with_helper_pals` and the long-text names.

## 2. Glyph (`core/GameCatalog.kt`, the `when` of game glyphs)

    "trail-together" -> "🦔"

(U+1F994 hedgehog.)

## 3. Accent (`ui/GameTheme.kt`, `GameThemes.accents`)

    "trail-together" to GameAccent(0xFF2E6B3F, 0xFF8FD6A0)

Contrast (WCAG formula, computed by hand and re-checked by `TrailTogetherHubSpecsTest` against `GameThemes.lightSurfaces`,
`darkSurfaces` and `onAccent`): light 0xFF2E6B3F is 6.22 on 0xFFFFFBFE and 4.92 on 0xFFE6E0E9 (needs 3.0); dark 0xFF8FD6A0
is 10.03 on 0xFF1C1B1F and 7.18 on 0xFF36343B; white on the light accent 6.38, near-black on the dark accent 12.29 (needs 4.5).
The test also asserts neither value equals an existing accent.

## 4. Route (`MainActivity.kt`, inside the NavHost)

```kotlin
composable("trail-together") {
    val g = rememberActiveModule(sessionManager, "trail-together") { TrailTogetherGame() }
    TrailTogetherScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, onMatchEnded = finishMatch)
}
```
Imports: `com.gamesuite.games.trailtogether.TrailTogetherGame`, `com.gamesuite.ui.TrailTogetherScreen`.

## 5. First-run tips (`ui/GameTips.kt`, `GameTips.tips`)

`TrailTogetherHubSpecs.tips["trail-together"]` holds three lines. The English literals equal `tip_trail_together_1..3`
in `res/values/trail_together_strings.xml` (Spanish in `values-es/`); each is under 140 characters. Paste:

```kotlin
"trail-together" to listOf(
    "A hop card sends a pal to the next stone of the same colour. A stone card lights a new stone at the end of the trail.",
    "Every colour has its own shape, so you can match stones and cards by shape too.",
    "Talk together before you pick a card: which one helps a friend the most?"
),
```
`GameTipsTest` reads only `polish_strings.xml`: move the three `tip_trail_together_*` strings (both locales) from
`trail_together_strings.xml` into `polish_strings.xml` when you paste. Until then `TrailTogetherHubSpecsTest` checks them
where they are; after the move change `moduleFile` reads accordingly or delete the three keys from my files (the test
`englishAndSpanishStringFilesDefineTheSameKeys` only needs both locales to agree).

## 6. Coach (`core/GameCoach.kt`)

Strings exist: `coach_trail_together_s1..s5` and `coach_trail_together_fact` in `trail_together_coach_strings.xml` (both
locales; that file holds only `coach_` keys). Add to `entries`:

```kotlin
entry("trail-together", listOf(R.string.coach_trail_together_s1, R.string.coach_trail_together_s2, R.string.coach_trail_together_s3, R.string.coach_trail_together_s4, R.string.coach_trail_together_s5), R.string.coach_trail_together_fact),
```
and to `motifs`: `"trail-together" to CoachMotif.PATH`.

## 7. ownCoachInGame decision

Do NOT add `trail-together` to `GameCoach.ownCoachInGame`. The screen uses the shared Club coach
(`rememberClCoach("trail-together", ready = true)` and `ClCoachButton`) and shows its own 8-picture first-run guide
(`TrailTutorial`, flag `tutorial_seen` in the private SharedPreferences file `trail_together_prefs`; the lantern
button next to the (?) button reopens it). Until the coach entry is registered the (?) button shows the title fallback.

## 8. GameAudienceTable

Nothing: the spec carries `audience = KIDS` and `minAge = 4` itself. Do not list the id in `KID_BLOCKED_GAME_IDS`.

## 9. Other things to know

* Category `GameCategory.BOARD`; `supportedModes` = vs-bot and pass-and-play. Snapshots are null in any other mode.
* Resume (`ResumableGame`): saved at every turn boundary, strictly validated, never repaired (see `TrailSaveCodec`).
* The parent-dashboard line is `trail_together_teaches`.
* No new permissions, no network, no stored personal data (only the existing profile player names are shown).
* The font: the app ships no Baloo (or any rounded) font file, so the screen uses the theme's `MaterialTheme.typography`
  with bold and extra-bold weights for the few headings (the tests also forbid fixed `sp` sizes, so text follows the
  system text-size setting). Adding a rounded font family is a coordinator decision.
* Deck tables are data (`TrailRules.deck`): tune the numbers, never the engine. `TrailTogetherEngineTest.smartPolicy...`
  asserts the measured win rates (see the design doc, section 3.10).
* If `GameCatalogTest`, `GameCoachTest`, `GameTipsTest`, `GameThemeTest`, `HubLocalizationTest` or `GameHubTest` enumerate
  games by hand-kept lists, add `trail-together` there too.
