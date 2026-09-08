# GameSuite Settings, Theming & Accessibility — Scoping Document

## Context

GameSuite currently has **zero** settings infrastructure: one fixed light `MaterialTheme`, no settings screen, no persistence layer, and none of the per-game customization already latent in the code (e.g., `UnoRules.kt` has stacking/7-0/jump-in/force-play logic with no UI toggle for any of it). This document scopes what to build, organized so a developer can pick it up and start immediately.

---

## 1. App-Wide Settings

A single **Settings** screen, reachable from a gear icon on the game-selection/home screen, covering everything that applies across all 9 games:

| Setting | Options | Notes |
|---|---|---|
| **Theme mode** | System / Light / Dark | Drives `darkTheme` boolean into `AppTheme` |
| **Dynamic color (Material You)** | On / Off | Only shown/enabled on API 31+ (Android 12+); both target devices qualify |
| **Named theme** | Classic / High Contrast / Dark / Midnight Arcade / Felt Table (see §2) | Independent of light/dark — see theming architecture |
| **Master sound** | On / Off | Gates all SFX across all 9 games |
| **Master music** | On / Off (only if/when background music exists — currently likely SFX-only) | |
| **Master haptics** | On / Off | Gates all vibration; should also respect OS-level "silent mode" per arcade research |
| **Text size** | Small / Default / Large / Extra Large (or a slider, 0.85x–1.5x) | Separate from OS font scale — an in-app multiplier layered on top, since word games specifically benefit from independent control (§ word game research) |
| **Reduced motion** | On / Off / Follow system | Should default to reading `LocalAccessibilityManager.current.isAnimationEnabled`, with an explicit in-app override for users who want to force it regardless of OS setting |
| **Colorblind mode** | Off / Deuteranopia / Protanopia / Tritanopia (or a single "Colorblind-safe mode" boolean if a full 3-way filter is too much scope) | Drives the non-color differentiators called out per-game in §4, especially UNO |
| **Default CPU difficulty** | Easy / Medium / Hard | A *global default* that pre-selects each game's own difficulty picker; each game still allows per-session override |
| **Reset all settings** | Button | Restores defaults across the board |

**Design note:** keep this screen genuinely app-wide only. Anything specific to one game (card backs, board themes, house rules) belongs in that game's own pre-game setup screen, not bloating the global settings surface — see §5 for how the two connect.

---

## 2. App-Wide Theming System

### Architecture

Adopt the **Now-in-Android pattern** referenced in the theming research — it maps directly onto this codebase's needs:

```
AppTheme(
    themeMode: ThemeMode,       // LIGHT / DARK / SYSTEM
    dynamicColor: Boolean,
    namedTheme: NamedTheme,     // CLASSIC / HIGH_CONTRAST / DARK / MIDNIGHT_ARCADE / FELT_TABLE
    content: @Composable () -> Unit
)
```

- **Light/Dark/System**: resolve `darkTheme` from the stored `ThemeMode` enum, falling back to `isSystemInDarkTheme()` only for `SYSTEM`. Never hardcode the system call directly in composables — always thread it through the settings-derived value so the explicit Light/Dark choices actually override the OS.
- **Dynamic color**: gate behind `Build.VERSION.SDK_INT >= 31` and the user's dynamic-color toggle; when off (or unavailable, or a non-Classic named theme is picked — dynamic color and a curated named theme are mutually exclusive), fall back to the named theme's hand-authored `ColorScheme`.
- **Named themes** are literally alternate `lightColorScheme()`/`darkColorScheme()` definitions selected instead of (or in addition to) dynamic color — each is a `ColorScheme` object, no more.

### Layering game-specific accent on top of app chrome

This is the key architectural question: **UNO's red/yellow/green/blue must stay fixed for gameplay legibility regardless of app theme**, but the chrome around it (app bar, buttons, score panel, dialogs, settings sheets) should follow the app theme. Two mechanisms handle this cleanly, both already idiomatic in Compose:

1. **Game-critical colors are never `MaterialTheme.colorScheme` roles.** UNO's four suit colors, Dominoes' pip colors, Mancala's seed/pit colors — these live in each game's own constants file (e.g., `UnoColors.kt`) as fixed literals, completely independent of the ambient theme. This is already implicitly true today; the scoping work is just to make sure it *stays* true as theming is introduced, i.e. never let `MaterialTheme.colorScheme.primary` leak into "what color is this Wild card's chosen color" logic.
2. **Chrome (app bars, buttons, dialogs, backgrounds, score displays, settings UI) reads `MaterialTheme.colorScheme`** as normal, so it re-themes automatically with the rest of the app.
3. For a few per-game accent needs that aren't quite "chrome" but also aren't "must never change" (e.g., Word Tiles' premium-square tint, Air Hockey's table/ice color, Dominoes' felt color), use a **custom `CompositionLocal`** per the Now-in-Android `LocalGradientColors`/`LocalTintTheme` pattern — e.g. `LocalGameAccent provides GameAccent(tableFelt = ..., boardTint = ...)`, provided once at `AppTheme`'s root and overridable per named theme. This gives each of the 4 named themes below a coherent "table felt / board" look without touching MaterialTheme's semantic roles or hardcoding per-game colors.

### 3–5 Named Themes

| Theme | One-line description |
|---|---|
| **Classic** | The default: clean Material 3 light palette (or dynamic color from wallpaper if enabled), neutral surfaces, standard felt-green table accents. |
| **High Contrast** | WCAG AAA-oriented palette — near-black text on near-white (or near-white on near-black in dark), thick borders, no low-contrast pastels; designed for low-vision players. |
| **Dark** | True dark Material 3 scheme, dark surfaces with elevated-surface tonal steps, reduced brightness for night play — distinct from "System follows dark" so a user can pin it regardless of OS setting. |
| **Midnight Arcade** | **Retargeted** (roadmap-audit item D2) from the neon/glow concept originally sketched here — that concept was never implemented, and the GameSuite ESP32 hardware arcade cabinet (`esp32-tictactoe/`) meanwhile shipped its own proven, already-tuned dark palette, so this theme now ports that palette verbatim instead of inventing a second one: a cool charcoal/slate base, a teal accent for "you," and a warm amber accent for the opponent/AI, applied across chrome in all games — see `theme/Color.kt`'s `MidnightArcadeScheme` for the exact ported values and `esp32-tictactoe/TicTacToeESP32/Theme.h` for the source of truth. This also ties the app and the cabinet to one shared visual identity, which the neon/glow concept never did. |
| **Felt Table** | A warm wood-and-felt palette (deep green/burgundy felt accents, warm brown chrome) evoking a physical game table — ties UNO/Dominoes/Mancala's tabletop-game identity into the app's visual language. |

Classic, High Contrast, and Dark are the load-bearing three (cover the accessibility and baseline-taste requirements); Midnight Arcade and Felt Table are the "couple of fun options", each reinforcing the app's dual identity (arcade game + tabletop games).

---

## 3. Per-Game Settings / Customization

### 1. Tic-Tac-Toe
- No CPU exists currently (pass-and-play only, though this session's audit-fix workflow just added CPU bot logic — see below).
- **Board theme**: X/O style variants (classic, colored, icon-based) — low-effort, high-visibility customization for the simplest game in the suite.
- **Win-line animation toggle** (ties into reduced-motion setting rather than a separate one).
- **First-player selection**: X starts / O starts / random / winner-of-last-round starts.

### 2. UNO
- **This is the single biggest concrete gap in the whole app**: `UnoRules.kt` already implements stacking, 7-0, jump-in, and force-play, but there is **no settings UI to toggle any of them**. Every match currently runs whatever the hardcoded defaults are. Exposing these as a pre-game "House Rules" checklist is pure UI work on top of logic that already exists — highest ROI item in this entire document.
  - Stacking (Draw Two/Draw Four chaining) — on/off
  - 7-0 rule (swap/rotate hands) — on/off
  - Jump-in — on/off
  - Force-play (must play a legal card if available) — on/off
  - Wild Draw Four Challenge — confirm and expose
- **CPU difficulty**: Easy / Medium / Hard — Easy plays a random legal card, Medium adds basic heuristics (play highest-value card, hold Wilds, block opponent's near-win), Hard adds card-counting-aware color/number strategy and optimal Wild timing.
- **House-rule presets**: "Official Rules" vs "House Rules" vs "Custom" — Official disables all four toggles above, Custom exposes them individually.
- **Card back style / table felt color**: visual customization (deck face fixed to standard UNO deck for legibility).
- **Turn timer**: Off / 15s / 30s / 60s.
- **Team play toggle exposure**: 2v2 team mode already exists — confirm it's discoverable in game setup.
- **Target score for multi-round play**: currently fixed at 500 — expose as a setting (300/500/750/custom).

### 3. Hangman
- **Difficulty tiers**: Easy/Medium/Hard/Expert mapped to word length + rarity.
- **Category selection**: expose the word bank's categories as a filterable list.
- **Guess limit**: standard 6 misses vs. configurable (e.g., 8 for easier, 4 for hardcore).
- **"Vowels count as guesses" hard-mode toggle** — a real genre convention, cheap to implement.
- **Hint option**: reveal-a-letter, limited uses per game.

### 4. Word Search
- **Grid size / difficulty tiers**: Easy (small grid, few words) → Hard (large grid, dense word list, diagonal/reverse words enabled).
- **Word list length control**: independent of grid size if the generator supports it.
- **Timer**: optional, off by default or toggleable "relax mode" vs. timed mode.
- **Hint**: "reveal first letter of one word" or "highlight one word," rate-limited.
- **Font size for grid letters and word list**: word search apps commonly offer *direct* in-app font-size control since grid legibility is core to the mechanic.

### 5. Crossword
- **Difficulty via theme/clue-bank selection**: since clues are curated and themed, expose theme packs as a proxy for difficulty/interest.
- **Check/Reveal system**: tiered — check current letter, check word, check entire puzzle, reveal letter, reveal word, reveal puzzle.
- **Autocheck toggle**: live-highlight incorrect entries as typed.
- **Timer show/hide + pause**: optional and cosmetic.

### 6. Word Tiles (Scrabble-style)
- **CPU difficulty**: Easy (plays first valid word found, low-value) / Medium (heuristic scoring, prefers bonus squares) / Hard (near-exhaustive rack search maximizing score + bonus-square usage).
- **Tile style / board theme**: cosmetic skin for tiles and premium squares.
- **Board zoom / larger-tile mode**: important given the fixed 15x15 grid is genuinely small even on tablets in split layout.
- **Challenge rule toggle**: whether an opponent/bot can challenge a played word against the dictionary.
- **Turn timer**: optional per-turn clock, off by default.

### 7. Dominoes
- **CPU difficulty**: Easy (plays first valid tile) / Medium (blocks opponent's counts, prefers high-pip disposal) / Hard (tracks played tiles, plays to control the board's open ends).
- **Set size / rule variant**: standard double-six fixed; a "Draw" vs "Block" scoring variant toggle is the most standard Dominoes house-rule split.
- **Tile back / table felt customization**: same visual-customization pattern as UNO.
- **Score target**: expose the winning score threshold if multi-round scoring exists here too.

### 8. Mancala
- **CPU difficulty**: Easy (random legal move) / Medium (greedy — always takes an available extra-turn/capture) / Hard (minimax lookahead — Mancala is small enough for real minimax rather than heuristics, the one game in the suite best suited to a "true" minimax difficulty ladder).
- **Board/seed visual theme**: wood-board vs. stone vs. colorful seed skins.
- **Rule variant toggle**: "last-seed-in-empty-pit captures opposite" is Kalah's one commonly-varied rule.

### 9. Air Hockey
- **CPU difficulty**: Easy/Medium/Hard/Insane via three levers — reaction delay, max paddle speed cap, and prediction-error noise.
- **Puck/table speed**: an *explicit* speed slider independent of AI difficulty — the accessibility-guideline-recommended "adjustable game speed" control (Celeste Assist-Mode pattern), should be first-class, not folded into difficulty.
- **Table/paddle/puck visual theme**: classic-arcade vs. neon/glow skins — a per-game skin choice, independent of the app-wide "Midnight Arcade" theme (§2), which no longer carries a neon/glow look now that it ports the ESP32 cabinet's charcoal/teal/amber palette instead.
- **Win score**: first-to-7 vs first-to-10 vs untimed practice mode.
- **Paddle size**: an enlarged-paddle option, doing double duty as both a genre-standard "forgiveness" setting and an accessibility accommodation.

---

## 4. Per-Game Accessibility Options

### 1. Tic-Tac-Toe
- X/O marks already distinguished by shape, not just color — confirm win-line highlight isn't color-only (add a distinct outline/animation).
- TalkBack: each cell needs `contentDescription` announcing row/column and current mark; `liveRegion` on turn-indicator text.

### 2. UNO
- **Highest-priority accessibility gap in the app**: the four suit colors have no non-color differentiator. Apply the researched **UNO ColorADD precedent**: square = blue, circle = red, diamond = yellow, triangle = green, as a small badge/icon in the card corner alongside the existing number/symbol (Mattel shipped exactly this, both physically and in Mattel163's "Beyond Colors" digital update).
- Wild-color-picker dialog needs the same shape badges on its four color-choice buttons, not just colored swatches.
- Card fan/hand: `contentDescription` per card ("Red 7", "Wild Draw Four") for TalkBack, since `FannedHand`/`PlayingCardView` are custom-drawn and won't get semantics for free.
- Turn/action announcements (draw pile count, direction reversed, whose turn) via `liveRegion = Polite`.
- CPU "thinking" animations should respect reduced-motion.

### 3. Hangman
- Pair remaining-guess count with an explicit numeric/text indicator ("3 guesses left") rather than relying on the gallows drawing alone.
- On-screen letter keyboard: `stateDescription` for guessed/correct/incorrect, and adequate 48dp touch targets on the dense A–Z grid.
- Dyslexia-friendly font option (OpenDyslexic or similar) — a genuine genre gap.

### 4. Word Search
- **Zoom/larger-tile mode** — dense letter grid is the core legibility risk.
- Found-word drag/tap-to-select gesture needs a tap-only fallback (tap first letter, tap last letter).
- Word-list panel: non-color completion indicator (strikethrough, not just color-fade).
- Custom Canvas-drawn grid needs `semantics` + `collectionInfo`/`collectionItemInfo` added manually per cell for TalkBack.

### 5. Crossword
- Screen-reader / clue read-aloud support is rare even in mainstream apps — a genuine differentiator opportunity.
- Grid-cell `contentDescription` must announce cross-reference context ("Row 4, Column 2, part of 5-Across and 3-Down, currently blank").
- Clue-list ↔ grid-focus sync for TalkBack users.
- Same dyslexia-font and text-size needs as Hangman.

### 6. Word Tiles
- **Small 15x15 grid needs a zoom or larger-tile mode** — the single most cramped board in the suite; drag targets risk falling under the 48dp guidance.
- Drag-and-drop tile placement is the accessibility-riskiest interaction in the app — provide a tap-to-select-tile, tap-to-select-square alternative so drag is optional.
- Premium-square legend needs letter/abbreviation badges beyond color ("DL"/"TW"), with contrast checked.
- Rack tiles need `contentDescription` per tile (letter + point value).

### 7. Dominoes
- Pip counts are inherently non-color-dependent — verify contrast still hits 3:1 in "High Contrast" and "Dark" themes.
- Tile-matching drag/placement: same tap-to-place fallback pattern as Word Tiles.
- CPU move highlight/animation should respect reduced-motion.

### 8. Mancala
- Numeric label for seed-count-per-pit (visual + `contentDescription`), not just seed icons to count.
- Valid-move affordance shouldn't rely solely on subtle color cues — pair with border weight/icon.
- `liveRegion` announcements for extra-turn/capture events.

### 9. Air Hockey
- **Fast drag gameplay needs a speed/difficulty accommodation** — the explicit puck/game-speed slider (Celeste Assist-Mode pattern), decoupled from AI difficulty.
- **Enlarged paddle hit-target option** — WCAG 24×24px minimum / 44×44px AAA guidance; undersized targets show up to 75% higher error rates.
- One-handed reachability confirmation for all controls.
- Avoid rapid repeated-tap requirements for any mechanic.
- Score/goal events need a non-visual cue (haptic + optional sound) alongside the visual animation.

### Cross-cutting for all 9 games
- Every icon-only button (pause, undo, hint, settings gear, sound toggle) needs a `contentDescription`.
- Run `enableAccessibilityChecks()` (Compose 1.9's accessibility test artifacts) in CI once this work lands, to catch regressions automatically.

---

## 5. Recommended Technical Architecture

### Storage: Jetpack DataStore (Preferences DataStore)

Use **Preferences DataStore**, not SharedPreferences (legacy, blocking) and not full Proto DataStore (unnecessary schema ceremony for this scope).

- `app_settings` (Preferences DataStore) — theme mode, dynamic color, named theme, master sound/haptics, text size, reduced motion, colorblind mode, default CPU difficulty.
- Per-game settings live in the **same** DataStore file with prefixed keys (`uno_stacking_enabled`, `uno_cpu_difficulty`, `word_tiles_board_zoom`, etc.) — simpler to manage than 9 separate files.

### Repository + reactive exposure

```
SettingsRepository (wraps DataStore<Preferences>)
  → exposes Flow<AppSettings> and Flow<UnoSettings> / Flow<WordTilesSettings> etc.
SettingsViewModel (shared, app-scoped)
  → StateFlow<AppSettings> via .stateIn(viewModelScope, WhileSubscribed(5_000), ...)
Per-game ViewModels (existing, one per GameModule)
  → inject or read SettingsRepository directly for that game's own settings flow
```

Compose screens use `collectAsStateWithLifecycle()`, never raw `collectAsState()`, and never touch DataStore directly.

### Reaching each game's Compose screen: via `GameModule`

1. Add a `GameSettings` marker or a per-game settings data class as an optional associated type on `GameModule` (or a sibling interface, `GameSettingsProvider`) — Tic-Tac-Toe may not need one initially.
2. Each game's own pre-game "setup" screen reads its `GameSettings` flow directly from `SettingsRepository`.
3. **`UnoRules.kt` is the concrete template**: wire its existing toggle fields to read from `UnoSettings` at match start, constructed from the settings screen's toggles, rather than hardcoded defaults. Lowest-risk, highest-payoff first integration since the rule branches already exist — only the wiring is new.

### App-wide theme reaching every screen

`AppTheme` wraps the entire NavHost at the `MainActivity`/root-composable level, reading `AppSettings.themeMode` / `.dynamicColor` / `.namedTheme` via `collectAsStateWithLifecycle()` once at the root — every game screen automatically re-themes as long as games consistently use `MaterialTheme.colorScheme.*` rather than hardcoded `Color(...)` literals for non-gameplay-critical UI (worth a quick audit pass).

### Adding the Settings screen to the NavHost

Purely additive — a gear icon on the home/game-picker screen's top bar, and a `Screen.Settings` route. Per-game setting screens (UNO house rules, difficulty pickers) live as their own routes nested under each game's existing setup/lobby flow, not inside the global Settings screen.

---

## 6. Priority / Phasing Recommendation

### Phase 0 — Foundation (blocks everything else)
1. **DataStore + `SettingsRepository` + `SettingsViewModel`**.
2. **`AppTheme` parameterization** — ship with just **Classic, Dark, High Contrast** (defer Midnight Arcade / Felt Table).
3. **Settings screen + NavHost route** — minimal first version: theme mode, master sound/haptics, text size, reduced motion, colorblind mode toggle.

### Phase 1 — Highest-impact, lowest-risk closes
1. **UNO house-rule settings UI** + CPU difficulty + colorblind shape-badge fix (all touch the same card-rendering code path). — **UI shipped**, and **CPU difficulty shipped**: `UnoHouseRulesScreen.kt` exposes stacking/7-0/jump-in/force-play for local vs-bot and pass-and-play (not yet Nearby/Online lobbies, which still launch with classic defaults), and `UnoBot` now has a real EASY/MEDIUM/HARD ladder wired to "Default CPU difficulty." **Colorblind fix only partially done**: a text label and screen-reader `contentDescription` were added to each Wild-color-picker swatch (closing the "zero non-color differentiator" gap for that one dialog), but this is not the full ColorADD treatment §4.2 describes — there is no shape badge (square/circle/diamond/triangle) on the picker or on card corners themselves, and the fix is not gated by the "Colorblind-safe mode" setting (it's an always-on label). Full ColorADD shape badges remain deferred/unbuilt.
2. **Air Hockey CPU difficulty + game-speed slider + enlarged-paddle option** — physics engine already exists. — **CPU difficulty already shipped** (Roadmap item 9g, before this document's Phase 1 was even scoped). **Game-speed slider and enlarged-paddle option remain unbuilt/deferred.**
3. **Word Tiles zoom/larger-tile mode + tap-to-place fallback** — the single most acute accessibility risk in the suite. — **Tap-to-place fallback already shipped** (Roadmap item 6b, pre-dates this document). **Zoom/larger-tile mode remains unbuilt/deferred** — the 15x15 board is still fixed-size.

**Also closed, cross-cutting with Phase 0's "not yet wired" list** (see item 9e's roadmap note): Reduced Motion is now consumed, not just stored — wired into UNO's discard-pile transition, the shared `FannedHand` fan-lift (so every card game using it benefits), and Mancala's capture-scale animation, via a new `LocalReducedMotion` CompositionLocal. Text size (`textScale`) is now consumed too, app-wide via `AppTheme`'s scaled `Typography` — every screen's Material3 text respects the slider, which is a more complete fix than the per-game "Font size for grid letters" asks in §3.4/§3.6 anticipated, though it doesn't substitute for those sections' separate grid/board-zoom requests (still unbuilt, see item 3 above). Colorblind mode as a togglable *setting* (as opposed to the always-on UNO label above) still has no game screen reading it. Haptics toggle remains unwired (still fires unconditionally at every call site, per item 9e's original note).

### Phase 2 — Round out remaining games
4. CPU difficulty for Dominoes and Mancala.
5. Word Search / Hangman / Crossword: difficulty tiers, timer toggles, hint systems, dyslexia-friendly font toggle.
6. Tic-Tac-Toe cosmetic customization.

### Phase 3 — Polish & differentiation
7. Midnight Arcade / Felt Table named themes, visual customization across all games.
8. Crossword read-aloud / enhanced screen-reader support.
9. CI accessibility checks (`enableAccessibilityChecks()`).

**Rationale:** Phase 0 is a hard dependency for everything else. Phase 1's three picks each convert an already-identified, already-partially-built gap into a shipped feature with minimal new engine risk. That makes Phase 1 the best ratio of visible impact to engineering risk, right when the settings/theming foundation is brand new and unproven.
