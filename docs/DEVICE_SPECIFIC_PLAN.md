# GameSuite — Device-Specific Implementation Plan
## Fold/Tab Adaptive Layout, Nearby Multiplayer, Activity Embedding, Tab S9 Input

## 1. True dual-screen / activity-embedding

**Verdict: Do not build. Skip permanently for this codebase, not just "later."**

GameSuite's existing `FoldAwareLayout`/`FoldState` in-app hinge split (single Activity, `WindowInfoTracker`-driven posture detection, shared Compose state across both panes) is *already the architecturally correct solution* for UNO/Word Tiles/Dominoes. `androidx.window.embedding` operates at Activity/Intent granularity — it places two separate Activity instances (two Windows, two lifecycles, two Compose compositions) side by side via `SplitPairRule`. Google's own docs say activity embedding targets "multiple-activity, legacy apps that can't be easily updated to MAD [modern android development]... Create new apps using MAD" — GameSuite is exactly the MAD single-Activity app that guidance points away from this API.

Adopting it would require either contriving a second `MainActivity` instance (same-class multi-instance launch + `SplitPairFilter` matching the class to itself) or building a genuinely separate Activity — both are a step backward: cross-Activity state sync (Intent extras / shared repo / `savedStateHandle`) replacing the zero-IPC Compose state hoisting you have now, for a use case (two panes rendering the *same* live match state, redrawing in lockstep every move) that in-process state sharing already handles better than IPC ever would.

**No build steps.** WindowManager 1.4 (Activity Stack Pinning, resize divider) doesn't change this calculus — still activity-level, still not applicable.

**Action item instead of building anything:** a manifest audit (see §4 below covers most of it) — confirm `MainActivity` has no explicit `android:resizeableActivity="false"`, no locked `android:screenOrientation`, and no stray `android:configChanges` for `screenSize|smallestScreenSize|screenLayout|orientation` left over from a View-based template (that would suppress the automatic Compose recompose-on-resize path you're relying on for fold/unfold and DeX resize). This is a five-minute grep-and-check, not a project. Only revisit ActivityEmbedding if GameSuite ever grows a genuinely separate, independently-launchable Activity (e.g., a legacy plugin/store screen) that should visually pair with another Activity — not applicable to any current or planned game screen.

---

## 2. Nearby Connections ad-hoc multiplayer

**Verdict: Worth building now.** This is the one item that adds a real new capability (multi-device play) rather than polishing an existing one, and the `MultiplayerTransport` interface was explicitly designed for exactly this swap-in.

### Technical approach
- New module `NearbyConnectionsTransport : MultiplayerTransport` implementing the existing interface — no changes to game logic.
- **Strategy: `P2P_CLUSTER`** — Google explicitly names this strategy for "multiplayer gaming" (mesh-like, host + guests all reachable), fits a host + up-to-3-4-guest shape, and turn payloads are trivially under P2P_CLUSTER's throughput needs.
- **Lifecycle mapping onto the existing interface:**
  - `connect()` (host) → `startAdvertising(endpointName, SERVICE_ID, ConnectionLifecycleCallback, AdvertisingOptions(P2P_CLUSTER))`
  - `connect()` (guest) → `startDiscovery(...)` → UI list from `onEndpointFound` → `requestConnection(...)`
  - `onPlayerJoined` ← `onConnectionResult(STATUS_OK)`
  - `onPlayerLeft` ← `onDisconnected`
  - `send()` ← `sendPayload(Payload.fromBytes(...))` — use **Bytes payload type only** (32KB limit, atomic delivery; ample for a serialized move/state-delta in JSON)
  - `onMessageReceived` ← `onPayloadReceived` / `onPayloadTransferUpdate(SUCCESS)`
- **Sequence numbers**: tag every payload with a turn/sequence number so receiving logic can detect/ignore stale or duplicate/out-of-order messages — Nearby's reliable path is not guaranteed in-order across all mediums.

### Build steps
1. Add manifest permissions per target-SDK-34 rules: `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (API 31+); `NEARBY_WIFI_DEVICES` with `android:usesPermissionFlags="neverForLocation"` (API 33+, avoids needing location permission); legacy fallbacks with `android:maxSdkVersion="30"`/`"31"` for `BLUETOOTH`/`BLUETOOTH_ADMIN`/`ACCESS_FINE_LOCATION`/`ACCESS_WIFI_STATE`/`CHANGE_WIFI_STATE` (relevant given minSdk 26, though both real target devices — Fold 5, Tab S9 — ship Android 14+ so the modern path is what actually executes on-device).
2. Add runtime permission request flow (all four Bluetooth/location perms are "dangerous" — advertising/discovery silently no-ops without them) — a pre-game "Enable Bluetooth & Nearby devices" screen/dialog gating entry into host/join flow.
3. Build a radio-state precheck (`BluetoothAdapter.isEnabled`, `WifiManager.isWifiEnabled`) with a user-facing "please turn on Bluetooth/Wi-Fi" prompt — build this now, not as a later patch, because Google's July 2026 blog post states the API will stop auto-enabling radios starting "late 2026" and explicitly calls out local multiplayer gaming as affected.
4. Implement `NearbyConnectionsTransportImpl` (connect/disconnect/send/callbacks as mapped above), guarding `send()` against `STATUS_ENDPOINT_UNKNOWN` (stale endpoint IDs post-disconnect).
5. Build host "Create game" / guest "Find nearby games" UI: endpoint list from `onEndpointFound`, connect/accept flow with the optional PIN/auth-token display from `onConnectionInitiated` for user-facing pairing confirmation.
6. Wire into the existing game-selection flow as a third transport option alongside `LocalPassAndPlayTransport`, gated behind the permission/radio checks from steps 2-3.
7. On-device test repeatedly (not once) between the actual Fold 5 and Tab S9 — connect/disconnect/reconnect cycles, since discovery flakiness requiring a device reboot is a documented issue on some OEM devices, and both target devices are Samsung.

### Risks/gotchas
- **Real deprecation-adjacent risk**: the "late 2026" radio-auto-enable removal — build the precheck now (step 3) rather than retrofitting later.
- Don't confuse with the deprecated **Nearby Messages** API (`com.google.android.gms.nearby.messages`) — the target is **Nearby Connections** (`.connection`), which is actively maintained.
- Discovery reliability quirks on repeated connect/disconnect — test cycles explicitly on both real devices, not just a single happy-path run.
- All four Bluetooth/location permissions are dangerous permissions requiring explicit runtime request before `startAdvertising`/`startDiscovery` will do anything.

---

## 3. Adaptive layout across Fold-cover / Fold-unfolded / Tab S9

**Verdict: Worth building now** — this is the biggest current gap (both device classes render the same stretched/shrunk single pane), and it's the one place where research converts cleanly into GameSuite-specific composables.

### Technical approach
Layer a size-class signal **alongside**, not instead of, the existing `FoldState`. Precedent file structure: mirror `FoldState.kt`/`FoldAwareLayout.kt` with a new `DeviceClass.kt` / `AdaptiveGameLayout.kt` pair.

- Use `currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)` (from `androidx.compose.material3.adaptive`) — callable from any composable, no Activity plumbing, and `supportLargeAndXLargeWidth = true` matters specifically because the Tab S9 landscape (1280dp width) crosses into the "Large" (1200-1600dp) bucket, distinguishing it from a merely-Expanded unfolded Fold.
- Decision tree per screen composable (game-specific, not global in `MainActivity`, since each board has different aspect-ratio needs — breakpoints shared, layouts per-game):
  1. Fold signal present + separating/book/tabletop posture → existing verified `FoldAwareLayout` split (untouched).
  2. Fold signal present + folded (Compact width, ~344dp) → **new cover layout**.
  3. No fold signal + Expanded/Large width (Tab S9) → **new tablet layout**.
  4. Everything else → current default single-pane (still correct for a plain phone, if ever relevant).
- **Cover layout** (Compact-width, tall, one-handed): bottom-anchored primary actions (thumb zone) — likely a `BottomSheetScaffold` for secondary controls (scoreboard/settings/rules); redesign the board itself for scroll/carousel (`LazyRow` "hand carousel") rather than shrinking tiles/cards below the 48dp touch-target floor; bottom nav bar, not a rail.
- **Tablet layout** (Tab S9, Expanded/Large-width, no fold signal): don't stretch — cap board width with `Modifier.widthIn(max = 840.dp)` and center it; use the freed width for a real supporting pane (`SupportingPaneScaffold`-style ~70/30 split: scoreboard, turn log, player list) rather than empty margin; nav rail or permanent drawer instead of bottom bar.
- Query size class at runtime rather than hardcoding researched dp numbers (344×882 cover, 690×829 unfolded, 800×1280/1280×800 Tab S9) — Settings → Display → Screen zoom changes reported density at runtime, so these are defaults to validate against, not constants to bake in.

### Build steps
1. Add `androidx.compose.material3.adaptive:adaptive:1.0.0` dependency if not already present.
2. Create `DeviceClass.kt`: thin wrapper exposing width/height `WindowSizeClass` via `currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)`.
3. Create `AdaptiveGameLayout.kt` (or per-game equivalents) implementing the 4-branch decision tree above, combining `DeviceClass` output with the existing `FoldState` signal — explicitly do not let size-class branching override an active fold/posture signal.
4. Build `CoverLayout` composable(s): bottom-sheet secondary UI + bottom-anchored primary actions + carousel/scroll board variant, per game (UNO, Word Tiles, Dominoes each need their own board treatment).
5. Build `TabletLayout` composable(s): capped/centered board + supporting side pane, per game.
6. Wire into each game's root composable (not `MainActivity`/`NavHost` globally).
7. On-device verify against real dp values on both physical devices — specifically re-check the Fold-5-unfolded borderline case (690dp width sits just under the 840dp Medium/Expanded cutoff, and 829dp height sits just under the 900dp Compact/Expanded-height cutoff), confirming the existing hinge-posture split still fires correctly there rather than falling into the new tablet branch.

### Risks/gotchas
- The Fold-unfolded dp numbers are right at breakpoint boundaries (690dp width vs 840dp cutoff; 829dp height vs 900dp cutoff) — this is a real "could silently pick the wrong branch" risk if size-class logic is ever allowed to run without the fold-signal check; treat `FoldState` as the higher-priority signal, size class as the tiebreaker only when no fold signal exists.
- Don't use the deprecated `androidx.compose.material3.windowsizeclass.calculateWindowSizeClass(activity)` — use `currentWindowAdaptiveInfo()`.
- Google explicitly warns against continuous scaling (shrinking touch targets/fonts to fit) — pick discrete layout variants, not a stretch/shrink continuum, which is precisely today's bug.

---

## 4. Tab S9 input (DeX, S Pen, mouse/keyboard/trackpad)

**Verdict: Mixed — one worth building now, two worth explicitly skipping.**

### 4a. DeX — skip (audit only, near-zero cost)
`android:resizeableActivity` defaults to `true` at targetSdk 34, so DeX/freeform already works today. `FoldAwareLayout` simply won't fire in a DeX window (no `FoldingFeature` present there) and falls through to the default single-pane layout — already correct behavior, not a bug to fix. Samsung's legacy `semDesktopModeEnabled`/`ENTER_KNOX_DESKTOP_MODE` mechanisms are vestigial pre-freeform cruft — not worth chasing.

**Build step (one-time, bundle into the same audit as §1):** grep the manifest for `<uses-configuration android:reqTouchScreen="finger"/>` or `<uses-feature android:name="android.hardware.touchscreen" android:required="true"/>` — either would block DeX/freeform launch outright and should be removed if present.

### 4b. S Pen — skip
Compose's `PointerInputChange.type` (`PointerType.Stylus`) already flows through the same `clickable`/`pointerInput` gesture code the tile-drag/tap game interactions already use — no dedicated stylus code needed for touch-first tap/drag gameplay. The one genuinely free win, take it opportunistically rather than as a project: Compose `TextField` gets built-in stylus handwriting-to-text on API 34+ / foundation 1.7+ with zero extra code — relevant only if/when a text-entry field (e.g. a future crossword-style answer box) is added. No dedicated `androidx.ink` investment; that library targets freeform drawing/note-taking, not board/tile games.

### 4c. Mouse/keyboard/trackpad hover+focus — worth building, modest scope
Real, reusable, first-class Compose APIs exist and this doubles as accessibility groundwork (useful given the Tab S9 keyboard cover scenario).

**Build steps:**
1. Add `Modifier.pointerHoverIcon(PointerIcon.Hand)` to interactive game pieces / drag targets (cards, tiles, dominoes) — cheap, immediate DeX/mouse UX improvement.
2. Verify Material3 components' default hover/focus indication (`ripple()` + `InteractionSource`) is inherited where stock M3 components are used; no extra code needed there.
3. Add `focusable()`/`focusGroup()` + `FocusRequester` + `onKeyEvent`/`onPreviewKeyEvent` for Tab/arrow-key traversal on menu and settings screens specifically (not in-game board interaction, which stays touch-first).
4. Optionally override `Activity.onProvideKeyboardShortcuts()` (plain View-system override, works fine hosting a single-Activity Compose app) for a `Meta+/` shortcuts sheet — nice-to-have, low priority.
5. Explicitly skip: custom right-click/context-click handling (`AndroidView` + `setOnContextClickListener` interop required, no pure-Compose API) and bespoke trackpad gesture handling — not worth it for touch-first gameplay. `LazyColumn`/`LazyRow` already handle mouse-wheel/trackpad scroll for free.

### Risks/gotchas
- None substantial — this is the lowest-risk area of the four. The only real gotcha is not over-investing: S Pen and DeX both have essentially nothing left to build once the manifest audit is done.

---

## Overall recommended build order

**1. Adaptive layout (Fold-cover / Fold-unfolded / Tab S9) — build first.**
This is the single highest player-facing-value gap: both real target devices currently get a visibly wrong layout every session, on devices already in hand for verification. It has no external dependencies (no new permissions, no OS-version gating, no third-party protocol), reuses the exact `FoldState` precedent already proven on-device, and every other area either depends on layouts existing correctly (multiplayer needs a sane per-device board layout to sync against) or is strictly smaller in scope (Tab S9 input polish). Do the manifest audit for DeX/ActivityEmbedding (5 minutes) at the start of this session since it's free and touches the same files.

**2. Nearby Connections multiplayer — build second.**
The only item that adds a genuinely new capability (multi-device play) rather than fixing/polishing what exists, and the `MultiplayerTransport` interface was purpose-built for this swap-in — high leverage, contained blast radius (game logic untouched). Sequence it after adaptive layout so that when guest/host devices connect, each is already rendering the correct board treatment for its own screen class rather than compounding two unfinished features at once. Build the radio-precheck (§2 step 3) proactively given the confirmed "late 2026" API behavior change — do it now while it's cheap, not as a future patch.

**3. Tab S9 mouse/keyboard/trackpad hover+focus (§4c only) — build third, small scope.**
Cheap, reusable, real UX value for keyboard-cover/DeX users, and functions as accessibility groundwork. Small enough to slot in after the two larger features, or even interleaved with layout work on the tablet-layout composables from area 3 since it touches the same tablet-facing screens.

**4. Skip / do not build: ActivityEmbedding (§1), DeX-specific handling beyond the manifest audit (§4a), S Pen-specific handling beyond free `TextField` handwriting (§4b).**
All three are either actively wrong for this architecture (ActivityEmbedding would regress the single-Activity design) or already solved for free by existing Compose/manifest defaults (DeX, S Pen). None represent deferred value — they're not "later," they're "don't," beyond the near-zero-cost one-time manifest audit that should happen in the same session as step 1 since it touches the same files.

**Rationale for this ordering as a whole:** GameSuite is a touch-first, two-specific-physical-device, local/CPU-opponent suite — not a productivity app chasing every large-screen input modality. The two areas with genuine unclaimed player value (correct board layout per device, and the ability to actually play against a second device) come first; the one area with modest incremental value (keyboard/mouse polish) comes third and small; the areas that are either architecturally wrong for this app or already handled by defaults are explicitly closed out, not left open as vague future work.
