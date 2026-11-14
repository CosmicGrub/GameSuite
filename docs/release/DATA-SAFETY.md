# Play Console "Data safety" answer sheet (draft)

Derived from the code and `app/src/main/AndroidManifest.xml` at commit 3d69c97. Where Play's rules leave a
judgement call it is marked **JUDGEMENT**, with the reasoning, for the owner to decide. This is a description of
behaviour, not legal advice.

## Facts the answers rest on

- No analytics, crash-reporting, advertising or attribution SDK. Dependencies that touch the network:
  OkHttp (the online relay WebSocket only) and Google Play Services Nearby Connections.
- No accounts, no sign-in, no purchases, no ads.
- Release network security config forbids cleartext: relay traffic must be `wss://` (TLS). The cleartext override
  exists only under `app/src/debug/`.
- There is **no built-in server address**. Online play is off until the user types a relay address in Settings.
  The relay (`server/`) keeps rooms and queue tickets in memory only, has no database, and logs only room codes,
  player IDs, event types and a salted, restart-rotating hash prefix of the IP.
- What an online room receives from an adult profile: room code or matchmaking request, a player ID (random
  per-profile identifier), display name, avatar identifier, and opaque game-move payloads. Child profiles never
  get Online or Nearby modes.
- Nearby play: Google's Nearby Connections (Play Services) over Bluetooth / Wi-Fi between devices in range; the
  host advertises "GameSuite host" plus a short random code, not a name. Display names are exchanged between
  players in the lobby.
- Everything else (profiles with name, avatar, age band; parent PIN as a salted PBKDF2 hash; per-day play time;
  settings; stats; saved games; music-folder URI) is stored on the device only, `allowBackup=false`.

## Section 1: data collection and security

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **Yes** (see JUDGEMENT below). If the owner decides online-room data qualifies for the "ephemeral processing" exclusion, this becomes **No**. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (wss only in release). |
| Do you provide a way for users to request that their data be deleted? | **Yes**, and nothing persists to delete: relay data lives only for the room or queue entry and disappears when it ends or the relay restarts. Local data is removed by Clear storage / uninstall. The policy says this. |

**JUDGEMENT: online rooms.** Play treats data sent off the device as "collected" unless it is processed only
ephemerally (held in memory, not stored) *and* is not sent by the developer's own backend in a way that logs it.
The relay holds name and avatar in memory only, but writes player IDs and room codes to a log, and whoever hosts
the relay is the user's chosen party, not necessarily the developer. Two defensible readings:

1. Conservative (recommended until the relay host is chosen; see TEAM-PLAN decision "Relay host"): declare as
   below, with **collection optional** and purpose App functionality.
2. Ephemeral: answer "No data collected" for the name/avatar, and keep the declaration for nothing else. Only
   defensible if the shipped relay stops logging player IDs, or if the owner hosts no relay at all and the feature
   ships empty.

## Section 2: data types (conservative reading)

| Data type | Collected? | Shared? | Optional? | Purpose | Notes |
| --- | --- | --- | --- | --- | --- |
| Personal info: **Name** | Yes | No | Yes (only if the user picks online play) | App functionality | Display name sent to the relay and shown to the other players in the room. Not linked to an account. |
| Personal info: **User IDs** | Yes | No | Yes (same trigger) | App functionality | Random per-profile player ID used for seat ownership and reconnect. Not an advertising ID. |
| App activity: **Other user-generated content / in-app actions** | Yes | No | Yes (same trigger) | App functionality | Game moves forwarded to the other players in the room. Not stored. |
| App info and performance (crash logs, diagnostics) | No | | | | No crash reporter. |
| Device or other IDs | No | | | | Nothing reads the advertising ID or hardware IDs. The relay sees the IP address of the connection (any internet connection exposes it); declare it under "Device or other IDs" only if the owner takes the stricter reading of IP address handling. |
| Location (approximate or precise) | No | | | | See permissions: the Bluetooth/Nearby permissions are `neverForLocation`. |
| Photos, files, audio, video, contacts, calendar, messages, health, financial | No | | | | The user's music folder is read on the device via the Android folder picker; nothing is uploaded. |
| Web browsing, search history | No | | | | |

"Shared" is **No** for all rows: data goes to the relay the user configured, which acts as a service provider for
transferring the user's own data to the other players; it is not shared with third parties. Data exchanged with
other devices through Nearby Connections is handled by Google Play Services under Google's policy, not collected
by the app's developer.

**Families (child profiles):** none of the above is ever collected from a child profile, because child profiles
have no online or Nearby modes. If the app joins the Designed for Families programme, the Data safety form must
still reflect adult-profile online play, and the listing text should say children's profiles never go online.

## Permissions and justification

| Permission | Why | Manifest limits |
| --- | --- | --- |
| `INTERNET` | WebSocket connection to the relay for online rooms, only after the user enters a relay address. | none |
| `VIBRATE` | Haptic feedback in game screens (`Haptics.kt`). | none |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | Legacy Bluetooth for Nearby play on Android 8 to 11. | `maxSdkVersion=30` |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Check that Wi-Fi is on before Nearby play and let Nearby set up its Wi-Fi link. | all API levels (the app calls `WifiManager.isWifiEnabled()` itself) |
| `ACCESS_COARSE_LOCATION` | Legacy requirement for Bluetooth scanning. | `maxSdkVersion=28` |
| `ACCESS_FINE_LOCATION` | Legacy requirement for Bluetooth/Wi-Fi discovery on Android 10 to 12. | `minSdkVersion=29`, `maxSdkVersion=31` |
| `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` | Find, advertise and connect to nearby players on Android 12+. | `minSdkVersion=31` |
| `NEARBY_WIFI_DEVICES` (`neverForLocation`) | Wi-Fi discovery and connection for Nearby play on Android 13+. | `minSdkVersion=33` |

**Location permission review:** the two location permissions are legacy-only (API 28 and API 29 to 31). Play
may ask why a games app requests location. The answer: it is required by Android for Bluetooth discovery on those
versions only, and the app never reads location. Both target devices run Android 14+, where neither is declared.
No permission is requested at install for any child profile flow: Nearby permissions are asked only when an adult
opens a Nearby lobby.

## Other declarations to make in Play Console

- Ads: **No**. In-app purchases: **No**. Government app: No.
- Target audience: see FAMILIES-CHECKLIST.md.
- App access: no login needed; the parent area is reachable by anyone who can pass the gate, so no reviewer
  credentials are required. Mention that a parent PIN is optional and a screen-lock or arithmetic unlock exists.
- Account deletion: not applicable (no accounts).
