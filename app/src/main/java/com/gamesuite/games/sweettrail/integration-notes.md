# Sweet Trail (`sweet-trail`): what the coordinator must integrate

The game is complete (engine, computers, resume, screen, EN + ES strings, tests). Only the shared registration
files were left alone, as agreed. Everything below is what to add to them.

Game id and route: `sweet-trail`. Title: **Sweet Trail** (Spanish: Sendero Dulce). Local only: pass and play and vs
computers, 2 to 4 seats. Not networked. Audience KIDS, `minAge = 3`.

## 1. Hub spec (`core/GameHubSpecs.kt`)

Add `SweetTrailHubSpecs.sweetTrail` (object in `com.gamesuite.games.sweettrail`) to `baseSpecs()`, in the BOARD
section (next to Slide and Climb). Do NOT add `"sweet-trail"` to `GameHubSpecs.networkedGames`.
`GameAudienceTable` needs no row (the spec's own `audience` / `minAge` win).

## 2. Glyph (`core/GameCatalog.kt`, the `when` of menu glyphs)

```
"sweet-trail" -> "🍬"   // also SweetTrailHubSpecs.GLYPH; unused by any other game
```

## 3. Accent colours (`ui/GameTheme.kt`, `GameThemes.accents`)

```
"sweet-trail" to GameAccent(0xFF660027, 0xFFFF4BFF),
```
(also `SweetTrailHubSpecs.ACCENT_LIGHT` / `ACCENT_DARK`; `SweetTrailHubSpecsTest.accentColoursPassContrast` checks them.)
This replaces the scaffold's first pair (0xFF9C1B52 / 0xFFF08FB4), which was only 16 / 10 RGB units from `hanafuda`.

| | colour | vs lightSurfaces (FFFBFE / E6E0E9) | vs darkSurfaces (1C1B1F / 36343B) | label text |
|---|---|---|---|---|
| light | 0xFF660027 | 12.85 / 10.16 | n/a | white 13.17 |
| dark  | 0xFFFF4BFF | n/a | 6.28 / 4.50 | near-black (101014) 6.96 |

All above the 3.0 non-text and 4.5 text minimums. Distinctness (plain RGB distance, computed from the 81 accents
in `GameTheme.kt`): light 660027 is 53 from `texas-holdem`; dark FF4BFF is 42 from `darts`. For a pink / berry hue
those are the largest gaps a 3-step RGB search found at these contrast levels (the palette is crowded; pure blue or
green would be further, but not candy-like).

## 4. Route (`MainActivity.kt`)

Next to the `chutes-race` route:

```kotlin
composable("sweet-trail") {
    val g = rememberActiveModule(sessionManager, "sweet-trail") { SweetTrailGame() }
    SweetTrailScreen(sessionManager = sessionManager, game = g, settingsViewModel = settingsViewModel, onMatchEnded = finishMatch)
}
```
Imports: `com.gamesuite.games.sweettrail.SweetTrailGame`, `com.gamesuite.ui.SweetTrailScreen`.

## 5. Tips (`ui/GameTips.kt`, `GameTips.tips`)

`GameTipsTest.noStrayTipResourcesAreLeftBehind` fails on any `tip_*_N` string that no `GameTips` entry owns, so the
three tips live as `draft_tip_sweet_trail_1..3` in `res/values{,-es}/sweet_trail_strings.xml` until you register
them. When you add the entry, rename those three resources to `tip_sweet_trail_1..3` in BOTH locale files (a plain
rename; nothing else uses them) and update `SweetTrailHubSpecsTest.tipsMatchTheStringResources` (it reads the
`draft_` names and asserts no `tip_sweet_trail_` exists yet). No apostrophes, so no escaping is needed. The
literals (also `SweetTrailHubSpecs.tips`):

```kotlin
"sweet-trail" to listOf(
    "Tap the card pile to draw. Your piece moves for you.",
    "Look for shortcuts: bridges, ladders and ziplines!",
    "Gooey Puddle: wait one turn. A wish lets you swap one card."
),
```
If you prefer them in `polish_strings.xml` with the other tips, move the six lines there (EN + ES) as `tip_sweet_trail_N`.

## 6. Coach card (`core/GameCoach.kt`)

Motif: `"sweet-trail" to CoachMotif.DICE` in `motifs` (or any motif you prefer). Entry:

```kotlin
entry("sweet-trail", listOf(R.string.coach_sweet_trail_s1, R.string.coach_sweet_trail_s2, R.string.coach_sweet_trail_s3,
    R.string.coach_sweet_trail_s4, R.string.coach_sweet_trail_s5), R.string.coach_sweet_trail_fact),
```
The strings are in `res/values{,-es}/sweet_trail_coach_strings.xml` (only `coach_` keys, every line under 120
characters). `SweetTrailScreen` opens the card itself through `rememberClCoach("sweet-trail", ...)` (the Club card and
the Club `coachSeen` flag, exactly like Ludo, Pig and Slide and Climb).

**ownCoach decision: do NOT add `"sweet-trail"` to `GameCoach.ownCoachInGame`.** The screen has no card of its own, so
the Hub's first-visit auto-open and the in-game first-run open share one flag and a child never sees two cards.
Register the coach entry BEFORE shipping the route: `rememberClCoach` opens `CoachCard("sweet-trail")`, which has
nothing to show until the entry exists.

## 7. Things to know

- Hub options reach the screen as `LaunchOptions.extras`: `length` ("short" | "standard" | "long", default standard),
  `sticky` ("true" | "false", default true) and the wish choice, offered once per table size so the Hub never names a
  seat that is not at the table: `wish` (2 seats), `wish3` (3), `wish4` (4), values "none" | "all" | "1".."n"
  (1-based seat). Parsed by `sweetTrailWishFromExtras(extras, seats)`. The plain `wish` of the first draft of the
  doc is the 2-seat key, so nothing in the doc's keys changed. The "Computer level" option is hidden until a wish
  is chosen (the only computer decision is whether to spend a wish).
- Wish = one swap per board per chosen seat: after drawing, that seat sees two round buttons (a check to keep the
  card, a star to discard it and draw a new one, which must be kept). Every other seat moves at once.
- Rules the doc left open, decided here: a piece only gets stuck in a puddle when it ARRIVES there (a landmark-card
  dance on a puddle square does not stick again); a stuck turn draws no card (so no wish); `turnsTaken` counts stuck
  waits (it always equals `moveSerial`).
- Look: warm cream board, rounded cards and chips, MaterialTheme typography only (no `.sp` literals, so the text size
  setting is honoured). The app has no Baloo font bundle yet (profiles/theme work), so the screen uses the system
  rounded-feeling default through `MaterialTheme.typography`; when the bundle lands only the typography changes.
  There is no text on the board canvas: every square has colour AND pattern (dots, stripes, rings, waves, checks,
  diamonds); numbers are announced to TalkBack, not drawn.
- With a screen reader on, every square (0..61) is its own labelled node (`sweet_trail_square_label`), the pile and
  the face-up card are labelled, and the status line is a polite live region carrying only the newest event;
  a blocked tap says why through an assertive line. A tap that arrives while the previous action is still animating
  says `sweet_trail_why_wait` (no buzzer, no haptic) and a second tap in the same input batch is dropped silently
  (`SweetTrailReject.BUSY`, see `holdInputUntilSettled` / `settled()`).
- Reduced Motion: the card flip becomes a cross-fade and pieces go straight to their square (no hops, no dances,
  no confetti). Game speed scales every pause, hop and the computer's delay (600 to 900 ms at normal speed).
  Dialogs, the coach card and loss of window focus pause the computer and the hops (`SweetTrailGame.pause()`).
- The coin the Easy computer flips is derived from the state (`Random(drawSerial * 31 + position + seat)`), never from
  the deck stream: the deck `Random` is asked for exactly one `nextLong()` per board (tested).
- New string files (no edits to shared strings): `sweet_trail_strings.xml` (screen, announcements, tips as `draft_tip_`,
  Hub texts) and `sweet_trail_coach_strings.xml`, each in `values` and `values-es`. The screen reuses the shared
  `ccl_leave_body_session`, `ccl_cpu_tag` and `hub_you`. Spanish is written by the author and flagged for native
  review in the XML comment.
- Files: `games/sweettrail/{SweetTrailBoard,SweetTrailCards,SweetTrailState,SweetTrailEngine,SweetTrailGame,SweetTrailLayout,SweetTrailHubSpecs}.kt`,
  `ui/SweetTrailScreen.kt`, `ui/SweetTrailArt.kt`, tests in `app/src/test/java/com/gamesuite/games/sweettrail/`
  (9 files, the old `@Ignore` stubs are all replaced).
- The screen depends on these existing internals (same module, package `com.gamesuite.ui`): `ChineseSeatColors`,
  `drawMarble`, `drawSeatGlyph`, `drawBurst`, `ClSeatBadge`, `ClStatusLine`, `ClWhyLine`, `rememberClCoach`,
  `ClCoachButton`, `rememberClTouchExploration`, `youAware`, `seatLabel`, `isYouName`, and `uikit.ChangeSetupButton`,
  `GameChrome`, `fitBoard`. If one is renamed by a concurrent change the screen will not compile.
- Not done (out of the MVP, per the doc): narration, stickers, "everyone finishes", Nearby / Online, sound beyond the
  existing procedural effects, a sticker or profile hook. No game id was added to any kid-blocked list.

## 8. What to run at the gate

`./gradlew :app:testDebugUnitTest --tests "com.gamesuite.games.sweettrail.*"`, then the whole unit-test suite
(HubLocalizationTest, GameTipsTest, GameCoachTest, GameThemeTest and GameAudienceTest all iterate over every spec).
Then play it once on a device: portrait, landscape and a tablet; Reduced Motion on and off; TalkBack on; a 4 seat
game with one wish seat; Short, Standard and Long; Gooey Puddle off. This team never ran the screen on a device or an
emulator (owner's rule), so the screen is compiled and unit-tested for its strings and sources but not seen.
