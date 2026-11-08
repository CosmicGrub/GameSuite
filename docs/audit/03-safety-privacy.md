# Audit 03: Safety, privacy and multiplayer trust

Scope: Android app (`app/`), relay (`server/`), transports (`app/src/main/java/com/gamesuite/transport`).
Read-only static review, 2026-11-07. Not legal advice. COPPA / GDPR-K / Google Play Families are assessed in general terms only.
Legend: **V** = verified by reading the cited code; **G** = guessed or inferred, not confirmed. Effort: S < 1 day, M 1-3 days, L > 3 days.

## 0. Headline

The app is privacy-light by construction: no analytics, ads, IAP, accounts, location, mic or camera. The relay is well hardened
(rate limits, sanitised names, allowlisted chat, bearer tokens). The weak spots for children are all on the product-policy side:
**no parental gate, no way to turn online off, no client-side name filter or report/block (O9 still open), a hand-typed relay URL,
and a release build that is un-minified with auto-backup on**. Quick-chat is built on the relay but not wired into the client,
so today the one stranger-visible free-text channel is the display name.

## 1. Permissions (manifest `app/src/main/AndroidManifest.xml`) - V

| Permission | Lines | Justified? |
|---|---|---|
| INTERNET | 13 | Yes, only for the optional relay (OnlineTransport). |
| VIBRATE | 30 | Yes (Haptics.kt). |
| BLUETOOTH, BLUETOOTH_ADMIN (max API 30) | 33-34 | Yes, legacy Nearby. |
| ACCESS_WIFI_STATE, CHANGE_WIFI_STATE | 45-46 | Yes (radio precheck, Nearby). CHANGE_WIFI_STATE is broader than a pure read; low. |
| ACCESS_COARSE_LOCATION (max 28), ACCESS_FINE_LOCATION (29-31) | 49-53 | Legacy Nearby requirement only. Location is a **runtime prompt** on API 29-31 devices. Families policy discourages location even when nominal. Low/Med (G: no code reads location, grep for `LocationManager` found nothing). |
| BLUETOOTH_SCAN (neverForLocation), ADVERTISE, CONNECT | 59-63 | Yes (API 31+). |
| NEARBY_WIFI_DEVICES (neverForLocation) | 66-69 | Yes (API 33+). |

No RECORD_AUDIO, CAMERA, READ_MEDIA_*, contacts or storage permissions (V, grep). Music library uses SAF tree picker
(`MusicScanner.kt`, `MusicManager.kt:146` `takePersistableUriPermission` read-only); user-chosen folder only, no broad storage
access. Good. No `<queries>`, no deep-link intent filters (the "gamesuite://join" scheme does **not exist**: V, grep of manifest and
`intent.data` usage found nothing), so there is no deep-link hijack surface today.
Only exported component is `MainActivity` (line 79), required for LAUNCHER. V.

## 2. Data collected and stored locally - V

- `ClubProfileStore.kt:13`: DataStore `club_profile` with display name, avatar string, favourite game, coach-seen set.
- `PlayerNameStore.kt:25`, `PartyRoster.kt:116`, `FavoritesStore.kt:10`, `HubChoicesStore.kt:15`: plain SharedPreferences (`MODE_PRIVATE`).
- Per-game `*StatsStore` / `*PrefsStore` DataStores (about 20 files), AppSettings including `onlineServerUrl` (`AppSettings.kt:109`).
- Player ids: a stable `p-<UUID>` minted client side (OnlineTransport / lobby code); not tied to a device id (G, not traced end to end).
- No SSN/email/DOB/phone fields anywhere. Names are typed for pass-and-play seats too (`PlayerNamesDialog.kt:41,75`, cap 16 chars).

Findings:

| # | Sev | Finding | Evidence | Fix | Effort |
|---|---|---|---|---|---|
| D1 | **Med** | `android:allowBackup="true"` with no `dataExtractionRules` / `fullBackupContent`. The profile name, relay URL and stats are cloud-backed-up and device-transferred. Target SDK 34 expects explicit data-extraction rules. For kids' listings, name data leaving the device via Google backup is something to disclose or avoid. | Manifest line 69; `app/src/main/res/xml/` holds only `network_security_config.xml` (V) | Set `allowBackup="false"` (simplest) or add `data_extraction_rules.xml` excluding `club_profile`, `player_names`, `party_roster`; update PRIVACY_POLICY. | S |
| D2 | Low | Names/avatars of other people (pass-and-play seats, party roster) stored in plaintext prefs. Not sensitive by itself. | `PartyRoster.kt:116` | Acceptable; mention in policy. | - |
| D3 | Low | Release build: `isMinifyEnabled = false`. Not a privacy leak per se, but trivially reversible APK, and a hacked client is the threat model for relay trust. | `app/build.gradle.kts:24` | Enable R8 with keep rules for UniFFI/JNA. | M |

## 3. Data sent to the relay - V

Relay sees (`server/index.js` host/join/queue cases, `server/sanitize.js`): room code, playerId, displayName (<= 40 code points
server side, 16 client side), avatar string (strict regex `^[a-z]{2,12}\|...$`), gameId/seat meta, matchmaking ticket (skill bucket),
opaque base64 game payloads, and **the client IP** (transient, used for rate limits; logged only as a 6-hex salted hash,
`ratelimit.js:39-42`, rotated per process). Nothing persisted to disk; Dockerfile runs as `USER node`. Health endpoint returns counts only
(`index.js:429-441`).

| # | Sev | Finding | Evidence | Fix | Effort |
|---|---|---|---|---|---|
| P1 | **High** (for a kids listing) | **There is no operator-controlled relay.** The user types any `ws(s)://` URL (`SettingsScreen.kt:358-369`), default empty (`AppSettings.kt:109`). A child (or a link a stranger gives them) can point the app at an arbitrary server which then receives name, avatar, IP and traffic. No scheme/host validation (documented as deliberate, `AppSettings.kt:104-108`). | Same | Ship a vetted default relay and hide the URL field behind a developer toggle / parental gate; reject non-`wss://` in release (the network config already blocks cleartext, so this is UX only). | M |
| P2 | Med | PRIVACY_POLICY.md is out of date with wave 5: does not mention matchmaking tickets, avatar string, transient IP processing, hashed-IP logging, or Google Play Services Nearby (`play-services-nearby:18.7.0`, `build.gradle.kts:66`), which is itself a Google SDK that processes device identifiers. The design doc itself says a paragraph is owed (`WAVE-5-MULTIPLAYER-DESIGN.md:300`). It also says "GameSuite is a local game collection" and "no analytics SDK", which is accurate (V). No children's section, no data-deletion/contact section. | `PRIVACY_POLICY.md` | Add: relay data table, Nearby/Play Services note, children statement, contact + deletion route, link in app Settings and on store listing. | S |
| P3 | Low | Relay host platforms keep their own request logs (acknowledged in policy). | PRIVACY_POLICY.md | Pick a host/region, document it. | S |
| P4 | Low | `connect` and `host:` log lines include playerId (a pseudonymous UUID) in plaintext. Not IP. | `index.js:456, 521-543` | Hash or truncate playerId in logs. | S |

## 4. Online play with strangers

### 4.1 Name / avatar as an abuse vector

| # | Sev | Finding | Evidence | Fix | Effort |
|---|---|---|---|---|---|
| N1 | **High** | **Display name is unmoderated free text visible to strangers** (matchmade rooms and 4-letter codes). Server sanitiser strips control/bidi/zero-width chars and truncates (`sanitize.js:14-23`) but there is **no profanity/URL/PII filter** on either side. Client `cleanPlayerName` only trims, collapses whitespace and caps at 16 (`PlayerNameStore.kt:12-16`). The design docs already list this as open risk R7 / O9 (`WAVE-5-MULTIPLAYER-DESIGN.md:587,606`). | V | Client filter (wordlist + digits-run/URL/@ patterns) at entry and again on receive; show a neutral fallback ("Player 3") for names that fail. Server-side second pass is cheap too. For child accounts, offer preset names (adjective+animal) instead of typed names. | M |
| N2 | Med | Same name field is the only identity: a child can type their real full name, school, or phone number (16 chars suffices) and it is broadcast to strangers. | `ClubProfileStore.setDisplayName`, `PlayerNamesDialog.kt:41` | Warning copy at entry ("Do not use your real name"), preset-name picker for the under-13 mode. | S |
| N3 | Low | Avatar is safe by construction: server regex allowlist `[a-z]{2,12}` x3 (`sanitize.js:5,25-28`), client renders vector parts only. V. | - | None. | - |
| N4 | Low | Non-Latin and emoji names are allowed through, so a filter must be Unicode aware (confusables, leetspeak). | `sanitize.js:9-12` | Normalise (NFKC, strip diacritics, de-leet) before matching. | M |

### 4.2 Chat

- Quick-chat is **allowlist-only ids** (`quickchat-allowlist.json`: phrases 1-12, emotes 101-106), stamped with the socket seat, rate limited
  (min gap 1.2 s, 5 per 20 s, same id 5 s: `config.js:55-58`, `quickchat.js`), spectators denied (`index.js:753-777`). Excellent design. V.
- **Client UI for quick-chat is not present** (no `phraseId` / quick-chat references in `app/src/main/java`; V by grep). So the server feature is
  dormant and the "reactions only default" mitigation named in the design doc cannot be selected yet. When wiring it: receiver-localised strings
  only, a per-room mute, and a global "Off" switch.
- No free-text chat anywhere (grep for TextField only finds name and relay URL inputs). Voice: no `RECORD_AUDIO`, no AudioRecord (V). Mic readiness for voice:
  would need a new permission, a Play data-safety declaration, parental consent and moderation; treat as out of scope for a kids build.

### 4.3 Reporting, blocking, moderation

| # | Sev | Finding | Evidence | Fix | Effort |
|---|---|---|---|---|---|
| R1 | **High** | **No report or block/mute of another player** in the client (only host Kick exists: `OnlineHostLobbyScreen.kt:234`; `LOBBY_KICKED_MESSAGE`). A guest cannot hide an offensive name or leave-and-avoid a player. Planned in O9 ("Report hides player locally") but not built. | grep: no report/block UI | Local "hide this player" (replace name with "Player N", mute chat) + "Report" that at minimum records a hashed id locally and shows safety guidance; later a relay `report` frame logged for a moderation owner. | M |
| R2 | Med | No relay-side ban list or abuse record; the only sanction is a per-IP temporary block (`IP_BLOCK_MS` 60 s, `config.js:35`) for failed joins. A hostile host can create a new room immediately. Hosts can kick, guests cannot remove a hostile host except by leaving. | `config.js`, `index.js:313-316` | Per-IP escalating blocks after N reports; optional room "locked" default for kids. | M |
| R3 | Low | Spectators exist (`msg.spectator`) and receive redacted state (design says so); a stranger can watch a kid's game if they know the code. Public 4-char codes are listed nowhere, so discovery requires guessing. | `index.js:588-608` | For kids mode disable spectators. | S |

### 4.4 Room codes, matchmaking, brute force - V

- Room code: 4 chars from a 32-symbol alphabet (`index.js:98,101`), `crypto.randomInt` (not Math.random). ~1.05 M codes; up to 500 rooms (`MAX_ROOMS`, line 104),
  so ~1 in 2,100 guesses hits a live room. Per-IP limit: 10 weighted failed joins/peeks per minute then 60 s block (`config.js:34-36`) and a freed code cools down 60 s.
  An attacker with many IPs (botnet) can still scan. Medium risk: the exposure is joining a lobby, not seeing data, and the host can kick. **Med/low.**
  Fix: 5 chars for friend codes, or host-approval for joiners, or auto-lock when the first guest joins (kids).
- Reconnect tokens are 192-bit random (`index.js:109,176`), compared with `tokensMatch`; claim/seat identity hardened in wave 5 and PR #9 (V by reading `join` flow
  `index.js:615-640`). playerIds are public, tokens are the secret. Over plain `ws://` the token is sniffable (README:192).
- Matchmaking (`queue.js`): tickets capped (`queueMaxTickets` 2000, `queueMaxPending` 100), no-show cooldown 30 s, 3-minute max age, claim within 15 s, queue frames
  6/min/IP. Matchmaking matches by skill bucket only; **nothing considers age**, so an adult can be matched with a child (G; no age field exists in ticket,
  `WAVE-5-MULTIPLAYER-DESIGN.md:300`). For a kids listing, public matchmaking with strangers should be off (see Section 7).

### 4.5 Host authority and cheating

- Games are host-authoritative: the host owns game state and broadcasts `StateSync`; the relay is a dumb pipe stamping `fromPlayerId` from the socket (`index.js:812-835`).
  The relay does **not** validate game payloads (opaque base64, 64 KB cap, `MAX_PAYLOAD_BYTES`, line 106). So a malicious **host** can cheat or send arbitrary state to guests; guests
  check version monotonicity, seat ownership, and session tallies (`SyncEpoch.kt`, `RemoteStateChecks.kt`) (V by file headers; per-game completeness G). A malicious
  **guest** cannot move another seat because the host binds action to `fromPlayerId` (PR #9, G for each of 70+ games; the memory notes TicTacToe/Chess/Play-Again gaps handed off).
  Real-time physics games (air hockey, etc.) trust the host. Acceptable for casual play; not for competitive/ranked. **Med (G).**
- Hacked client can spam within the flood budget (240 frames/s, 1 MB/s, 3 strikes: `config.js:41-47`). Reasonable.
- Gambling-style games with online play: host controls the deck/shoe, so a hostile Hold'em or Blackjack host can see everyone's hole cards. Play chips only (see 6), so impact is trust, not money. **Low.**

### 4.6 Relay DoS and transport

| # | Sev | Finding | Evidence | Fix | Effort |
|---|---|---|---|---|---|
| T1 | Low | Per-IP and per-socket limits are good, but all keyed on IP; behind a proxy `TRUST_PROXY` must be set correctly or all clients share one IP (documented `config.js:21-25`). Default 0 is the safe default. | V | Document in deploy runbook. | S |
| T2 | Low | `MAX_ROOMS` 500 global cap is an easy fill target for a botnet (rooms per IP 30, host 6/min). Result is server_full, not memory blow-up. | `index.js:104,526` | Alerting; per-room-creation proof of work not needed. | S |
| T3 | Low | WebSocketServer has no `Origin` check and no message-type auth, so any script can connect. Expected for a native client. | `index.js:445` | None needed. | - |
| T4 | Low | `ENABLE_TEST_CONTROL` gate exists (`index.js:843`, default off, `config.js:23`); make sure it is never set in prod. | V | Startup warning if set and `NODE_ENV=production`. | S |
| T5 | **Med** | **Cleartext/TLS**: release `network_security_config.xml` forbids cleartext (V), debug override allows it (`app/src/debug/res/xml/network_security_config.xml`). Good. But the relay itself speaks only `ws://0.0.0.0` (`index.js:992`) and relies on a platform TLS proxy; there is **no certificate pinning** (`OnlineTransport.kt:69` plain `OkHttpClient`) and no check that a user-supplied URL is `wss://` before sending the player name. A user on a hostile Wi-Fi who set `ws://` in a debug build leaks bearer tokens. In release, cleartext fails closed. | V | Reject `ws://` in release in UI with a clear message; optionally pin the production relay host. | S |

## 5. Third-party libraries and SDKs - V (`app/build.gradle.kts:49-76`)

AndroidX, Compose, kotlinx-serialization, OkHttp 4.12.0, JNA 5.14.0, **`com.google.android.gms:play-services-nearby:18.7.0`**, UniFFI Rust cores. **No** Firebase, analytics, crash reporting,
ads, billing, attribution or social SDKs. No in-app purchases, no ads, no external links found (grep for `ACTION_VIEW`, `LocalUriHandler`, `openUri`: none; the only `startActivity` is the system
Bluetooth/Wi-Fi settings intent in `NearbyEntryScreen.kt:134`). That is a very strong position for the Families programme; Play Services Nearby is the only item needing a data-safety line (and
Families policy requires SDKs be Families-self-certified; Play Services is generally allowed but must be reviewed). Dependabot/OSV scan of OkHttp/JNA versions recommended. S.
Logging: no `Log.*` calls in app code (V by grep) so nothing sensitive reaches logcat. Server logs: see P4.

## 6. Nearby (local) multiplayer

| # | Sev | Finding | Evidence | Fix | Effort |
|---|---|---|---|---|---|
| L1 | **Med** | Nearby auto-accepts every incoming connection with no PIN comparison: `acceptConnection(endpointId, ...)` straight away; `authenticationDigits` is explicitly not used. Anyone within Bluetooth/Wi-Fi range running the app (service id `com.gamesuite.NEARBY_SERVICE`) can join an advertised room; host sees the name and can kick. Advertised name is the player's display name, broadcast to every nearby device (`startAdvertising(displayName, ...)`, line 151). | `NearbyConnectionsTransport.kt:150-179`, header comment line 53 | Show the 4-digit auth token on both devices and require host Accept (esp. for kids); advertise a generic "GameSuite room" rather than a real name. | M |
| L2 | Low | P2P_CLUSTER strategy and BT scan expose presence to nearby adults. Normal for Nearby. | line 150, 163 | Parental gate before enabling Nearby for kids mode. | S |

## 7. Kids specifically

State of play (V): no age gate, no parental gate, no play-time limits, no "kids mode", no switch that disables online, no COPPA/Families text in app, no in-app privacy link
(grep for `parent|child|kid|age gate|COPPA` found nothing in `app/src/main`). Online is, however, **off by default**, because the relay URL is empty and Host/Join is blocked until it is set
(`AppSettings.kt:101-109`). That default is accidental safety, not a control.

| # | Sev | Finding | Fix | Effort |
|---|---|---|---|---|
| K1 | **High** | No parental gate in front of the Online section of Settings or the Online/Nearby entry screens. A child can paste a server URL and play with strangers. | Add a parental gate (math puzzle or device-credential check) in front of Settings > Online, Nearby and any future chat. | M |
| K2 | **High** | No "Offline only / Family mode" master switch that hides Online, Nearby, matchmaking, spectators and chat. | A single setting persisted in DataStore, default **on** for a kids build, which removes the Online/Nearby tiles at the hub level. Gate on `GameHubSpecs` entries. | M |
| K3 | **High** | No age screen. Required before any online features if the app's audience includes under-13 (COPPA actual knowledge / Play Families target-audience rule). Use a neutral age screen, no data stored beyond a boolean. | Neutral age gate on first run; under-13 gets offline-only plus preset names. | M |
| K4 | Med | No play-time or break reminder. | Optional "play-time reminder" after N minutes, parental-set via the gate. | M |
| K5 | Med | Name entry safeguards: none beyond 16 char cap (see N1, N2). | Preset name picker, filter, warning copy. | M |
| K6 | Med | Policy/store: a Families listing requires an in-app privacy link and a reachable data-deletion route. | Add Settings > About > Privacy Policy opening a local page (no external link needed; local-render PRIVACY_POLICY content). | S |
| K7 | Low | The app has no external link surfaces at all, which is good: keep it that way (no web views, no store links without gate). | - | - |

### 7.1 Content suitability by game (rating guess, G unless stated)

All gambling-style games were checked at engine level: **Blackjack** is documented "PLAY CHIPS only (no money, no cash-out)" with rebuy (`BlackjackGame.kt:43-49`, V);
**Hold'em** is chips-only with `startingChips` config (`HoldemEngine.kt:39`, V); no IAP/ads/purchase path exists to buy chips (V: no billing dependency). Risk is **theme**, not mechanics:

| Game | Theme concern | Rating |
|---|---|---|
| Blackjack (`CasinoCards.kt`), Texas Hold'em | Simulated gambling (casino look, bets, chips). Google Play Families and PEGI/ESRB treat "simulated gambling" as 12+/Teen. This is the single biggest content-rating driver. | **Med-High**: tag 12+ or remove from a kids listing; or relabel as "card game" with no casino chrome and no bet UI. |
| Slot Cars | Racing toy cars; name only. Check no slot-machine art (G). | Low |
| Hanafuda (Koi-Koi), Mahjong, Dominoes, Sevens, President, War, UNO, cards | Traditional card/tile games, no wagering UI found. Hanafuda / Mahjong are culturally gambling-associated but fine at this level. | Low |
| Dice (Yahtzee, Pig, Party Toolkit dice) | Dice mechanics, no betting. | Low |
| Tank Battle, Shooting Gallery, Toy Boxing, Sumo, Tug-of-war | Cartoon/toy violence (cannon shells, boxing) with no gore (G: no blood strings found in `res/values`). | Low-Med: "mild cartoon violence" descriptor. |
| Fishing, Tower Defence | Fantasy action, mild. | Low |
| Wordgames / Crossword clue bank (`CrosswordClueBank.kt`) | Content is data authored in-repo; check it for mature clues. | Low (G) |

For a unified rating the realistic answer is **PEGI 3 / ESRB E10+ if the casino titles are removed or de-themed; otherwise 12+ / Teen (Simulated Gambling)**.

### 7.2 Photosensitivity, audio and haptics

| # | Sev | Finding | Evidence | Fix | Effort |
|---|---|---|---|---|---|
| S1 | Low (good) | Reaction Duel is already designed to avoid flashing: "steady change... never a flash"; glow is "a steady soft halo (never a blink)". | `ReactionDuelScreen.kt:136`, `ReactionDuelArt.kt:77` (V) | None. | - |
| S2 | Med | Reduced Motion exists (`LocalReducedMotion.kt`) and AGSL/shader effects exist in a few screens (AirHockey, Chess, Tile games, WordGuess matched shader/AGSL grep); but there is **no formal photosensitivity pass** (3 flashes/second, WCAG 2.3.1) over cameraShake, win bursts, Light Trail, Light-up (Lights Out), Whack-a-Mole. Whether Reduced Motion disables **all** strobing effects is unverified. | G | Run a flash-rate sweep on a device; make every burst/flash/shake obey Reduced Motion by default; add a one-time notice. | M |
| S3 | Low | Haptics: waveform patterns in `Haptics.kt`, with a master toggle (`LocalHapticsEnabled.kt`). Amplitude not capped in code review (G). | `haptics/Haptics.kt` | Confirm max amplitude and total duration per effect; keep toggle reachable from every game. | S |
| S4 | Low-Med | Audio is procedural (`ProceduralSfx.kt`, `AmbientMusicEngine.kt`); user music library player crossfades (`LibraryPlayer.kt`) with its own volume (0.6 default, `AppSettings.kt`). No loudness limiter or "headphone safety" audit (G). Music library plays **user-chosen files only**, so content is under the user's control, not the app's. | G | Peak-limit SFX; cap default master volume; do not auto-play loud on game start. | S |

## 8. What was good (keep)

- Cleartext off in release; INTERNET justified; no mic/camera/location use.
- No analytics, ads, IAP, accounts, external links, web views.
- Relay: salted-hash IP logs, per-IP and per-socket rate limits, strict name/avatar sanitising, allowlisted chat, reconnect tokens, seat retirement, matchmaking caps, non-root container.
- Chat design is already the safest viable (ids only).
- Chips-only casino games.

## 9. Must-do before any public release or kids-category listing

1. **Decide the audience.** If under-13 is in scope, add a neutral age screen and a Family/Offline-only mode that hides Online, Nearby, spectators and chat (K1-K3). Make it the default for a Families build.
2. **Parental gate** in front of Settings > Online, Nearby, any chat, and the relay URL field (K1, P1).
3. **Name safeguards (O9):** client-side filter, preset names for children, "do not use your real name" copy, fallback on receive (N1, N2).
4. **Report / hide-player** UI, even if local-only to start (R1). Wire quick-chat client only with a per-room mute and a global off switch (4.2).
5. **Ship a vetted default relay** (wss only, documented host/region) and hide the free-text URL field behind a gate (P1, T5).
6. **Backup rules:** `allowBackup=false` or explicit `dataExtractionRules` (D1).
7. **Refresh PRIVACY_POLICY.md** (matchmaking, avatars, IP transient use, Play Services Nearby, children section, contact, deletion route) and expose it in-app and on the store page (P2, K6).
8. **Content rating:** de-theme or age-restrict Blackjack and Hold'em; declare "simulated gambling" truthfully in the rating questionnaire (7.1).
9. **Photosensitivity sweep** plus verify Reduced Motion disables all flashes; audio peak/volume review (S2-S4).
10. **Nearby hardening:** show/confirm the auth digits and advertise a generic room name (L1).
11. **Release hygiene:** R8 minify, dependency vulnerability scan, `ENABLE_TEST_CONTROL` never set in prod (D3, T4).
12. **Host-authority caveat:** document that online play is non-competitive; finish the TicTacToe/Chess guest StateSync and Play-Again gaps already handed off (4.5).

## 10. Not verified / limits

No build, emulator or device run. Per-game host-authority completeness across 70+ games not individually traced. Content strings per game and Crossword clue bank not read in full.
Game art for slot machines/blood not inspected visually. Legal conclusions are general guidance only.
