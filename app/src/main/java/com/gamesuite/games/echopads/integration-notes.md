# Echo Pads: what the coordinator must add

Nothing below has been compiled, built or run by the author. Expect to fix compile errors in the first gate.

Game id `echo-pads`, title "Echo Pads", Kotlin package `com.gamesuite.games.echopads`, screen
`com.gamesuite.ui.EchoPadsScreen`. Same-device only (solo, pass and play 2-6, head-to-head duel): do NOT add the id to
`GameHubSpecs.networkedGames`.

## 0. Depends on the core change another agent is making

`EchoPadsHubSpecs` uses `GameAudience.KIDS` and `minAge = 4` as named parameters of `GameHubSpec`
(`enum class GameAudience { ADULTS, FAMILY, KIDS }` in `com.gamesuite.core`). It will not compile until that lands.

## 1. Hub spec (core/GameHubSpecs.kt)

Add `EchoPadsHubSpecs.echoPads` to `GameHubSpecs.baseSpecs()` (or `EchoPadsHubSpecs.all`). The spec has three modes:

| mode id | kind | route | seats |
| --- | --- | --- | --- |
| `solo` | SOLO | `echo-pads` | 1 |
| `party` | PASS_AND_PLAY | `echo-pads` | 2-6 (standard `humans` stepper) |
| `duel` | PASS_AND_PLAY | `echo-pads-duel` | 2 |

Hub strings are already in `res/values{,-es}/echo_pads_hub_strings.xml` (21 `hubKey` names; "Solo" already exists as `hub_solo`
elsewhere and is not repeated; "People playing" and its help come from `HubOptions.humans`). Watch for a duplicate
resource name if another agent adds the same plain words in their own hub file: `hub_slow`, `hub_normal`, `hub_fast`,
`hub_sound`, `hub_easy_mode`, `hub_pass_and_play`, `hub_head_to_head`. Android fails the build on a duplicate; keep one copy
(the English text must stay identical, which `HubLocalizationTest` checks).

Extras the screen reads from `LaunchOptions.extras`: `speed` (slow|normal|fast), `forgiving`, `sound`, `orient` (duel only).

## 2. Glyph (core/GameCatalog.kt, the `when` of game glyphs)

    "echo-pads" -> "🎶"

## 3. Accent (ui/GameTheme.kt `GameThemes.accents`)

    "echo-pads" to GameAccent(0xFF5D4DB8, 0xFFB3A8F5)

Contrast checked (WCAG, same formula as `Wcag`): light 6.36 / 5.03 against the two light surfaces, dark 8.02 / 5.75 against the
two dark surfaces; label on accent 6.52 (light) and 8.89 (dark). Neither value is used by an existing accent. `EchoPadsHubSpecsTest`
re-checks all of this against the live `GameThemes`, so it will fail loudly if another agent takes the same colour.

## 4. Routes (MainActivity.kt, inside the NavHost, next to `reaction-duel`)

    composable("echo-pads") {
        val g = rememberActiveModule(sessionManager, "echo-pads") { EchoPadsGame() }
        EchoPadsScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, duel = false, onMatchEnded = finishMatch)
    }
    composable("echo-pads-duel") {
        val g = rememberActiveModule(sessionManager, "echo-pads-duel") { EchoPadsGame() }
        EchoPadsScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, duel = true, onMatchEnded = finishMatch)
    }

Imports: `com.gamesuite.games.echopads.EchoPadsGame`, `com.gamesuite.ui.EchoPadsScreen`. A lone player on `echo-pads` is
Solo automatically; two or more is pass and play; the duel route only plays as a duel when exactly two players arrive.

## 5. Tips (ui/GameTips.kt `GameTips.tips`)

Paste `EchoPadsHubSpecs.tips` (one entry, key `"echo-pads"`). The English literals equal `tip_echo_pads_1..3` in
`res/values/echo_pads_strings.xml` (the Spanish is in `values-es/`), and each is under 140 characters:

    "echo-pads" to listOf(
        "Watch and listen while the pads light up, then tap the same pads in the same order.",
        "Every pad has its own shape, colour and note, so you can learn them by sight or by ear.",
        "Little ones? Turn on Easy mode and Slow speed in the setup for a free second try."
    )

(The resource lives in `echo_pads_strings.xml`, not `polish_strings.xml`; the name still follows `GameTips.resourceName`.)

## 6. Coach (core/GameCoach.kt)

Strings exist: `coach_echo_pads_s1..s4` and `coach_echo_pads_fact` in `echo_pads_coach_strings.xml` (both locales). Add to `entries`:

    entry("echo-pads", listOf(R.string.coach_echo_pads_s1, R.string.coach_echo_pads_s2, R.string.coach_echo_pads_s3, R.string.coach_echo_pads_s4), R.string.coach_echo_pads_fact),

and to `motifs`: `"echo-pads" to CoachMotif.TILES`.

## 7. ownCoachInGame decision

Do NOT add `echo-pads` to `GameCoach.ownCoachInGame`. The screen uses the shared Club coach (`rememberClCoach("echo-pads", ready = true)`
and `ClCoachButton`), the same as Reaction Duel, so the Hub may auto-open it on first visit and the in-game (?) button reopens it.
The screen never shows a card of its own.

## 8. Other shared things that may need a line

* `ProceduralSfx` has no pitched sound, so the four pad tones come from `EchoPadTones` (own cached-PCM player on its own
  background executor; it reads `CardSounds.soundEnabled` and obeys the master mute). Win/miss jingles use `playProceduralSfx`
  kinds through `rememberProceduralSfx()`. No change to `ProceduralSfx.kt`.
* Personal best: own DataStore `echo_pads_stats` through `gameDataStore(...)` (best per speed, Easy runs kept apart). There was
  no shared best-score store.
* Category is `GameCategory.ARCADE`; `supportedModes` = vs-bot (the lone player) and pass-and-play.
* If `GameCatalogTest`, `GameCoachTest`, `GameTipsTest`, `GameThemeTest`, `HubLocalizationTest` or `GameHubTest` enumerate games by
  hand-kept lists, add `echo-pads` there too.
* The Echo Pads strings share the `echo_pads_` / `tip_echo_pads_` / `coach_echo_pads_` prefixes, so they cannot clash with others.
