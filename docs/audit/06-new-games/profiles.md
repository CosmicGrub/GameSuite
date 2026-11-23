# Audit: profiles / family mode / parent controls foundation

Scope: core/{ProfileScope,ProfileScopedDataStore,GameAudience,GameAudienceTable,LaunchPolicy,GameDataStore,FavoritesStore,HubChoicesStore,GameSessionManager}.kt, profiles/*.kt, ui/profiles/*.kt, MainActivity.kt profile wiring, Settings/Games/MainMenu screens, tests. Read-only review (no Gradle/adb run). "Verified" = confirmed by reading the code path end to end; "Suspected" = plausible, needs a device or a test to confirm.

Severity rule used: HIGH = can lose data or let a kid reach online / bypass the gate or the limits.

## What is sound (checked, no defect)

- Default-profile mapping: `scopedName(base, "default")` == base, so `datastore/<name>.preferences_pb`, `shared_prefs/favorites.xml`, `saved_games` are the pre-profiles files. Existing data stays readable. No file is moved, so there is no copy to interrupt and nothing to re-run: migration is idempotent by construction. The only "migration" is the one-time `ProfilesState` JSON write, which is one atomic DataStore edit.
- DataStore single-instance rule: every `gameDataStore("x")` property gets exactly one `ProfileScopedDelegate` (top-level extension property), which owns one `ProfileScopedDataStore`, which caches one real DataStore per suffix under a lock (`ProfileScopedDataStore.kt:112`). All 38 `gameDataStore(...)` names in main are unique (grep), so no two stores share a file. Activity recreation cannot duplicate (static delegates). Device-level stores (`app_settings`, `profiles`, `profile_controls`) use plain `preferencesDataStore`, no overlap with scoped names.
- No deep-link surface: manifest has only MAIN/LAUNCHER on MainActivity, MainActivity never reads `intent` / `onNewIntent`, no services, receivers or notifications. Intent extras, notifications and shortcuts cannot reach a route.
- Blocked ids match real `gameId`s (blackjack, texas-holdem, slot-cars, hanafuda, pig-dice) and none is in the audience table, so they are doubly hidden. Home Today row, Continue row, Favourites, Club favourite, Games search and KidsHome grid all read `LocalVisibleSpecs` / `access.visibleSpecs` (age band + hidden ids + blocked ids applied once in `visibleFor`).
- ES `prof_*` parity: 78/78 keys, placeholder shapes match.
- Restored back stack after process death: `parentUnlocked` is VM state (lost), so a restored `parent-dashboard` is redirected for a kid by the guard.

## Findings

### P1 [HIGH, verified] Parent gate lockout is not a lockout: it resets every time the screen is left
`ui/profiles/ParentGateScreen.kt:46` `val gate = remember { ParentGate() }`. `failures` and `lockedUntil` live only in that instance (`ParentGate.kt:38-39`). Trigger: on KidsHome (or the Time-up screen) the child taps lock/Parent, gets 3 wrong answers, presses Cancel (`onCancel = popBackStack`), opens the gate again: fresh instance, 3 fresh tries, no lockout. Also lost on process death. Also the multiplication branch has only 28 possible questions (13..19 x 6..9, ~28 distinct answers 78..171, `ParentGate.kt:77-79`), so a child can memorise the table or guess at ~1/28 per try; the addition branch (120..900 + 120..900) is easy for the 10-12 age band the app targets. Reaching the parent area lets a kid switch to the (unprotected) adult profile and so reach Online/Nearby.
Fix: hold gate state in a process-wide object (or `ProfilesViewModel`) and persist `failures`/`lockedUntil` in the `profile_controls` store using `SystemClock.elapsedRealtime`-based + wall-clock-max (so neither clock change helps), with escalating lockout (30 s, 5 min, 30 min); make the question harder than 10-12 y/o arithmetic (e.g. word-form number or 3-term expression, a large pool) or use the already-declared `DeviceCredentialFallback`. Add a test that builds two gates and shares state.

### P2 [HIGH, verified] Cold start is fail-open: full catalogue + `networkAllowed=true` until profiles/controls load
- `MainActivity.kt:291` calls `bootstrapBlocking`, which already returns the active `ProfilesState`, but the result is discarded. `ProfilesViewModel._profiles` starts `null` (`ProfilesViewModel.kt:27`) and is filled by an async DataStore collect.
- While null: `rememberProfileAccess` returns null (`ProfileShell.kt:63`), `ProfileShellObserver` guard returns early (`:110`), `LocalVisibleSpecs` falls back to `GameHubSpecs.all` (`MainActivity.kt:408`), NavHost shows "menu" (adult Home), `LaunchPolicy.networkAllowed` is its default `true` (`LaunchPolicy.kt:9`). A kid-active cold start therefore shows the whole catalogue (blackjack, Online badges) and any tap in that window calls `launchGame` with network allowed.
- Same for `_controls` (`ProfilesViewModel.kt:31`): defaults (no hidden ids, 60 min / 0 used) apply until `profile_controls` loads, so a kid with an exhausted limit gets a fresh status until the flow lands.
- Worse, if the first collect throws (`store.state.collect` at `ProfilesViewModel.kt:43` has no try/catch, unlike `ClubViewModel`), the VM scope crashes the app; if it were swallowed `_profiles` stays null forever and the app stays in the fail-open fallback.
Window is normally tens of ms but it is real and the fix is a few lines.
Fix: in `onCreate` use the bootstrap result: `LaunchPolicy.networkAllowed = !st.active.isKid && onlineEnabled(unknown yet -> false)`, seed `ProfilesViewModel` with the state (constructor arg / `setInitial`), make `LocalVisibleSpecs` fall back to `emptyList()` (or kid-safe subset) while `profileAccess == null`, make `LaunchPolicy.networkAllowed` default `false` and set true only after access resolves, and wrap both VM collectors in try/catch with a fail-closed fallback (kid profile if the active profile is a kid).

### P3 [MED-HIGH, verified] Profile list and parent controls are wiped by any decode failure (data loss + kid protection loss)
- `ProfilesLogic.decode` returns null on any exception (`Profile.kt:155`), and `ProfileStore.mutate` runs `sanitize(null)` -> `initial("")` -> writes it back (`ProfileStore.kt:35`). Likewise `ensureInitialized` treats "undecodable" as "first run" (`:45-52`). Enum values (`ProfileKind`, `AgeBand`) decode by name with no `coerceInputValues`: add/rename an enum in a later build (or roll back to this build) and the next switch/add/rename replaces ALL profiles with one default adult. Kid profiles vanish (their data files stay orphaned forever, `deleteProfileData` never runs), kid controls become unreachable, the device is effectively "no kids mode".
- `ControlsCodec.decode` is lenient the same way (`ParentControls.kt:54-56`) and `ParentControlsStore.update` re-encodes defaults + the change. Because the play-time ticker calls `update` every 15 s, one bad read wipes limits, hidden games and the Online switch within 15 s of the next kid session.
- `GameDataStore`'s ReplaceFileCorruptionHandler (used for `profiles`/`profile_controls` too, `GameDataStore.kt:29-31`) has the same effect on a truly corrupt file.
Fix: never overwrite on decode failure: in `mutate`/`update`, if the raw value is non-blank but fails to decode, abort the write (throw/skip) and keep the raw string; keep a `.last_good` copy key written after each successful encode and restore from it; set `Json { coerceInputValues = true }` with default enum values; add a schema `version` field. Test: decode of a state with an unknown enum must not reset.

### P4 [MED, verified] Time limits can be bypassed by changing the clock or time zone; limiter mixes wall clock and monotonic time
- `PlayTimeLimiter.rollover` (`PlayTimeLimiter.kt:40-44`) resets usage whenever stored day != today, in either direction. Setting the date forward one day, or flying west / switching zone so the local epoch day goes back, gives a fresh budget; going back also deletes the history entry (`prune` drops keys >= today, `:126`) so usage is lost, not merely reset.
- `ParentGate` lockout uses `System::currentTimeMillis` (`ParentGate.kt:33`): setting the clock forward skips the 30 s lockout.
- `millisUntilQuiet` (`:81-87`) does wall-minute arithmetic and ignores DST: on a transition day the "N minutes until quiet" and WARN toast are off by 60 min (the quiet check itself uses the real wall time so enforcement is right). Low impact.
Fix: only roll forward (`today > usageEpochDay`); keep usage if `today < usageEpochDay`. Track `lastWall` + `lastElapsed`; if wall time jumped by more than elapsed + 5 min treat it as tampering and keep today's usage until the monotonic clock covers the gap. Use `SystemClock.elapsedRealtime` in the gate lockout.

### P5 [MED, verified] Foreground accrual loses up to 15 s on every pause, and status is stale for 15 s after resume
`ProfileShell.kt:129-143`: `last` is reset at every RESUMED and the first accrual happens only after `delay(15 s)`; leaving (lock screen, app switch) inside that window discards the elapsed time. Sessions shorter than 15 s are never counted; normal use under-counts by ~7 s per pause on average. After resume `tick` is stale and `status` is a `remember(kc, inRound, tick)` (`:148`), so quiet-hour/limit state is not re-evaluated for up to 15 s (kid gets that much past quiet start/limit after a long background).
Fix: flush `now - last` in a `finally`/`onPause` (ProfilesViewModel.accrue already clamps), and set `tick = System.currentTimeMillis()` immediately at the start of each RESUMED block before the first delay.

### P6 [MED, verified] "Time up" is advisory in game and Play again is not gated; forced exit loses the game
- `LimitStatus.canStartNewGame` is computed but never read anywhere (grep: only the declaration). While a kid is on a game route, `inRound = !isIdleRoute(route)` is true even on the result panel, so after the limit the kid can chain "Play again" for the full 10-minute grace (`GRACE_MILLIS`) and `launchGame` does not consult the limiter.
- At grace expiry (`ProfileShell.kt:161-165`) the shell does `navigate(KIDS_HOME){popUpTo("menu")}`: the game composable is disposed (`rememberActiveModule` onDispose, `MainActivity.kt` end) without `saveActiveProgress()` or `endActiveGame`, so the kid's game is neither saved nor ended; `activeContext` stays non-null with a live `LocalPassAndPlayTransport`, and the next `launchGame` overwrites it. Progress saving only happens through GameChrome "Leave" or onPause.
Fix: expose a `LaunchPolicy.timeUp` (or have `launchGame` return false) so a limited kid cannot start a new round past the limit; before the forced navigation call `sessionManager.saveActiveProgress()` then `endActiveGame(GameResult aborted)`; treat a result panel as idle.

### P7 [MED, verified] Back-stack trap for kids: Back never leaves Kids home, and "menu" flashes
Every redirect is `navigate(home){popUpTo("menu"){inclusive=false}}` (`ProfileShell.kt:113`, `:163`, `ProfileRoutes.kt:241`), so for a kid the stack is `[menu, kids-home]`. System Back pops to "menu", the guard (route "menu" not allowed for a kid) runs one frame later and pushes kids-home again: the app can never be exited with Back, and MainMenuScreen composes each time (MusicManager context change, saved-game read, tab bar incl. Settings tab visible for a frame). Not an escape (Settings/Stats are guard-closed) but it is a loop and wastes work.
Fix: for kids use `popUpTo(navController.graph.id){inclusive=true}` and register kids-home as the new root, or `BackHandler` on kids-home that calls `moveTaskToBack(true)`.

### P8 [MED, verified] Club name of ANY profile overwrites the shared lobby host name (cross-profile + kid-name exposure)
`ClubProfileStore.save/setDisplayName` mirror the Club name into `PlayerNameStore.hostName` (`ClubProfileStore.kt:33,64`), and `PlayerNameStore` (`player_names`) is deliberately device-level (`ProfileScope.kt` header). `ClubViewModel.save` is called from the Home avatar editor. Result: a kid's name (or the real child's first name typed in the kid's Club editor) becomes the adult's Nearby/Online host and lobby name, and the adult's name defaults into the kid's pass-and-play seats. Same device-level leak for PartyRoster, GameTips coach flags, Yahtzee/Shogi prefs.
Fix: scope `player_names` and `party_roster` with `ProfileScope.prefsName(...)` (a one-line change each, as the header says) or never mirror a KID profile's name.

### P9 [MED, verified] `withoutNetworkModes` keeps network-only specs for kids
`GameAudience.kt:28`: `if (kept.isEmpty()) return this`. A spec whose modes are all NEARBY/ONLINE passes `visibleFor` for a kid (if tagged FAMILY/KIDS) with its network modes intact, so its Hub shows Nearby/Online buttons. The route guard and `LaunchPolicy` still stop the launch, so this is defence in depth, not a bypass today (no current spec is network-only; suspected). Fix: `visibleFor` should drop the spec when no local mode remains; add a unit test with a network-only spec.

### P10 [MED, verified] Home "Recently played" ignores profile visibility
`MainMenuScreen.kt:84-86,205` build the row from `GameHubSpecs.byId` (full catalogue) instead of `specs` (visible). For an adult with Online off the tile opens `openGameFromCard` with the unstripped spec; for a kid it matters only in the one-frame menu flash (P7) or after the parent hides a game the kid already played. Fix: `.filter { s -> specs.any { it.gameId == s.gameId } }` and use the visible spec.

### P11 [MED, verified] Race: whole-state writes from stale snapshots in ProfileStore
`add`/`rename`/`delete` compute from `current()` (a separate read) and then `mutate { r.state }` / `mutate { next }` (`ProfileStore.kt:54-71`): the transform ignores the fresh `cur` and writes the stale result. Two quick adds lose one profile (its files then orphaned); `delete` or `add` racing the asynchronous `switchTo` write can revert `activeId` to the previous profile (the in-memory scope then disagrees with the stored state until next launch, where `bootstrapBlocking` re-applies the persisted, wrong, profile: a kid could come up as the adult after a process kill). MAX_PROFILES can also be exceeded by a double tap. Fix: compute inside the edit: `mutate { cur -> ProfilesLogic.add(cur, ...) .stateOrCur }` and return the result via a captured var.

### P12 [MED, suspected] Bootstrap blocks the main thread and can crash the launch
`ProfileStore.bootstrapBlocking` (`ProfileStore.kt:84`) runs `runBlocking` on the main thread in `onCreate` on every Activity creation: creates a DataStore + scope, reads `profiles`, and on first run also reads `club_profile` and does a write. Typically 10-60 ms; on a cold, loaded device or full storage it is unbounded. No timeout, no StrictMode anywhere to catch it. A DataStore IOException (read error, disk full on the first-run write) is not caught (`ensureInitialized` only guards the Club read) and crashes `onCreate` on every launch. Corruption itself is handled (replace handler).
Fix: try/catch -> in-memory `initial()` state + retry later; `withTimeout(1500)`; better, mirror the active id and kind in a tiny SharedPreferences read synchronously and move the real DataStore work off the main thread. Also `ProfileStore.delete` runs `deleteProfileData` (file deletes, `deleteRecursively` of saved games) on `Dispatchers.Main.immediate` from `viewModelScope`: wrap in `withContext(Dispatchers.IO)`.

### P13 [MED, design] Adult profiles are unlocked and never re-lock
Only kid->adult is gated. A device left on an adult profile gives a child full Online/Nearby; there is no idle/background re-lock to the kid profile, and `parentUnlocked` has no timeout (it clears only when KidsHome composes or a switch happens): a parent who leaves the dashboard open and hands the device back leaves the kid at dashboard/picker (the guard allows them while `parentUnlocked`). Fix: clear `parentUnlocked` on `ON_STOP` and after ~2 min idle; optional "auto-lock to <kid> after N min in background" setting.

### P14 [LOW-MED, verified] Name validation rejects legitimate names and the count is by UTF-16
`Profile.kt:60` `isLetterOrDigit()` rejects combining marks (Mn/Mc), so Devanagari/Thai/Arabic-with-vowel-signs/NFD-typed accents are refused as "invalid characters", and hyphens/apostrophes ("Mary-Jane", "O'Brien") too: contradicts "letters (any language)". Supplementary-plane letters are rejected because `Char` is a surrogate. `length > 16` is in UTF-16 units but is effectively moot. Good: RTL override, zero-width and emoji are all rejected (they are not letters), NBSP is rejected, `lowercase()` is locale-neutral. Duplicate check is case-insensitive only (Cyrillic homoglyphs pass; cosmetic). Link check can false-positive on "...www..." after joining words. Fix: allow `Character.getType` Mn/Mc/Me plus `-`/`'`, count `codePointCount`, normalise to NFC before comparing.

### P15 [LOW, verified] Misc
- `resumeSavedGame` (`GameSessionManager.kt:169-191`): if `launchGame` is refused by `LaunchPolicy` it still sets `pendingResume` and returns true, so the caller navigates to the game with no/stale context and the stale `pendingResume` is consumed by the NEXT game, whose `restore` fails and deletes the saved game (`:210`). Only reachable if a save has mode ONLINE/LOCAL_AD_HOC (games refuse to save networked tables today). Fix: return false when `!LaunchPolicy.allows(mode)`; clear `pendingResume` on a mismatching route.
- `switchTo` + `seedClub` (`ProfilesViewModel.kt:72`): a second switch within ms makes `seedClub(A)` write A's name into B's blank Club profile.
- `ProfileScope.deleteProfileData` leaves live in-memory DataStore/SharedPreferences instances for the deleted id (a late write could resurrect a zombie file) and is non-atomic with the record removal (crash between = orphan files, never swept). Add an orphan sweep at bootstrap (suffix not in the profile list).
- `rememberProfileAccess` keys on the whole `controls` (`ProfileShell.kt:64`): usage changes every 15 s rebuild `ProfileAccess`. Equality keeps `LocalVisibleSpecs`/the guard stable (data-class equality), so cost only; key on `onlinePlayEnabled` + `hiddenGameIds`.
- `ProfilesLogic.initial` stores the English literal "Player" as a profile name; AddProfile avatar swatch `contentDescription = c.id` reads "pip"/"sky" in TalkBack; week bars say "Monday: 15" with no unit (`ParentDashboardScreen.kt` PlayTimeCard). Not in strings.xml.
- DrawGuess spec is FAMILY with no `minAge` (`DrawGuessHubSpecs.kt:66`), so it shows in the 4-6 band though it needs reading (content, not safety).
- Warn toast uses `remember`'d `warnedDay`, so it repeats per Activity instance.
- `pickerHandled` is per ViewModel, not "once per process"; a new Activity instance re-offers the picker to adults.
- Hold-to-open: touch needs 1.5 s, but `semantics { onClick }` (`KidsHomeScreen.kt:173`) opens the gate instantly for TalkBack/Switch Access. Acceptable (the gate is the protection) provided P1 is fixed, otherwise it is the cheapest brute-force path; Time-up "Parent" button has no hold at all. Targets are >= 48 dp everywhere I checked (lock 56, chips 48, swatches 48).

## Answers to the specific questions

- Kid reaching Online/Nearby/chat/spectate/lobby by: deep link (none exist), back stack (guard, see P7 loop), Settings (route closed for kids), saved-game resume (per-profile dir; launch refused by `LaunchPolicy`), recents/task restore (guard + restore pop), Play again (`launchGame` gated), notification (none), Nearby advertising (only started in lobby screens, which are guard-closed), intent extras (not read), mid-game profile switch (picker unreachable mid-game; no teardown of `pendingNearby/OnlineTransport` or `activeContext` on switch, suspected low). The only real windows are P2 (cold start) and P1/P13 (gate and unlocked adult profile).
- Blocked ids hidden in every list: yes via `LocalVisibleSpecs`, except P10 (Recent row) and the P2 window. Age band applies to Today and Continue (both use the visible specs; Continue's `remember(saves)` does not key on `specs`, harmless because the screen is recreated after a switch).
- Gate fairness/predictability: see P1 (28-question pool, easy for 10-12, `Random.Default` is not security relevant). Lockout is not persisted across process death or screen exit; it uses wall clock.
- Limiter: clock/timezone P4, midnight rollover correct for forward moves (tested), DST P4, backgrounding P5, grace + time-up P6 (dialog is not back-dismissible: `dismissOnBackPress=false`, `dismissOnClickOutside=false`, good), per-profile accumulation after a switch is correct (effect keyed on profile id, transform runs on the stored state), quiet window across midnight correct.
- Singletons: `ProfileScope.activeId` is process-wide by design; ViewModels are not recreated but all scoped stores are `flatMapLatest` flows so Stats/Club/Settings re-emit (verified); `ClubViewModel`/`StatsViewModel` briefly show the previous profile for a few ms (cosmetic). Saved-games re-point happens in a `LaunchedEffect` after `setActive` (one-frame lag; no game is active at that point). Club avatar and achievements do not cross-leak (both in scoped stores); the lobby name does (P8).

## Test gaps

Covered (pure): guard routes, audience table, limiter arithmetic, gate arithmetic, name rules, list logic, suffix naming. Not covered, in priority order:
1. `ProfileStore` bootstrap/ensureInitialized/delete and decode-failure behaviour (P3, P11, P12): needs Robolectric or an instrumented test with a temp DataStore.
2. `ProfileScopedDataStore`: one instance per file, `data` re-emits on `setActive`, `updateData` targets the right file, default id hits the legacy file (instrumented).
3. `ProfileScope.deleteProfileData` (pure `File` temp-dir test is possible today): own files removed, other profiles' and default files untouched.
4. Gate state shared across instances / persisted lockout; gate with a backwards clock (P1, P4).
5. Limiter with clock moving backwards, zone change, DST transition days (P4); `canStartNewGame` enforcement (P6).
6. `ProfileShellObserver` / `ProfilesViewModel` (compose test): cold start with kid profile never shows an adult spec (P2), Back from kids-home (P7), parentUnlocked clearing.
7. `visibleFor` with a network-only spec (P9); `resumeSavedGame` with a blocked mode (P15); `FavoritesStore`/`PrefsKeyValues` file names follow the profile.

## Ranked list

1. P1 gate lockout resets on screen exit, tiny question pool (HIGH)
2. P2 cold-start fail-open (full catalogue, network allowed) (HIGH)
3. P3 decode failure wipes profiles and parent controls (MED-HIGH, data loss + loses kid protection)
4. P6 time limit advisory, Play again ungated, forced exit loses the game (MED)
5. P4 clock/time-zone bypass of limits and gate lockout (MED)
6. P11 stale-snapshot writes in ProfileStore (lost profile, wrong active after restart) (MED)
7. P8 Club name leaks into shared lobby name (MED, privacy)
8. P13 adult profile never re-locks; parentUnlocked has no timeout (MED, design)
9. P12 main-thread blocking bootstrap, uncaught IO error (MED)
10. P7, P9, P10, P5, P14, P15 (MED/LOW)

## Verdict

Data layout and the DataStore scoping design are sound: default-profile data is untouched, there is no migration to interrupt, and no store is created twice. Route-level kid isolation is strong (strict allow-list, no deep-link surface, `LaunchPolicy` as a second wall). It is not ready to ship as a kid-safety feature: the parent gate can be brute-forced indefinitely by re-entering it (P1), cold start is fail-open (P2), and one unreadable JSON value silently deletes every profile and every limit (P3). Fix P1-P3 and P6 before enabling the feature for real children; the rest can follow.
