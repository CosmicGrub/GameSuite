# GameSuite Privacy Policy

*Last updated: November 2026*

GameSuite is an Android game collection. This policy describes what the app
does with data, written to match the code in this repository rather than a
generic template. It is a plain description of behaviour, not a statement of
legal compliance with any particular law or store programme.

## What GameSuite stores on your device

All of the following lives in the app's private storage on your device, via
Android's DataStore and similar local storage:

- **Club profile**: the display name you choose, your avatar (a short
  identifier string for a built-in avatar), your favourite game and which
  in-app hints you have already seen.
- **Settings**: theme, sound and music, haptics, text size, accessibility
  toggles, default CPU difficulty, card size, the online relay address you
  typed (if any) and the folder you picked for your own music (stored as an
  Android folder-access URI; the app reads audio from it on your device only).
- **Game stats and progress**: matches played, wins, losses, draws, streaks,
  achievements and per-game personal bests, saved games, and per-game
  preferences.

None of this is uploaded to a GameSuite server, and there is no account or
cross-device sync. It is not encrypted by the app beyond Android's normal
app-sandbox protection. The app sets `android:allowBackup="false"`, so this
data is not included in Android's automatic cloud backup or device-to-device
transfer.

### Deleting your data

- In Settings, the reset option restores every setting to its default.
- Android Settings, Apps, GameSuite, Storage, then **Clear storage** (or
  uninstalling the app) deletes everything the app stores locally, including
  the Club profile and all stats.
- Data held only for the duration of an online room (see below) disappears
  when the room ends or the relay restarts; there is nothing stored for you on
  the relay to delete afterwards.

## What GameSuite does NOT do

- **No analytics or crash-reporting SDK** is included. Nothing about how you
  use the app is collected.
- **No advertising SDK**: the app shows no ads.
- **No accounts**: there is no username, password or email collection.
- **No location use**: the app does not read device location. Some Android
  versions require a location or "nearby devices" permission for Bluetooth and
  Wi-Fi discovery; where the platform allows, these are declared with
  `neverForLocation`.
- **No microphone, camera or contacts access.**
- **No free-text chat.** The only text other players can see from you is your
  display name.

## Permissions

- **Internet**: used only to reach the optional online relay you configure.
- **Bluetooth, Wi-Fi state and Nearby Wi-Fi devices (and, on some older
  Android versions, location)**: used only by Nearby multiplayer to find and
  connect to devices in the same room.
- **Vibrate**: haptic feedback.

## Multiplayer: what is exchanged, and with whom

1. **Pass-and-play** (one device): no network activity.
2. **Nearby (local multiplayer)** uses Google's Nearby Connections API from
   **Google Play Services** (`play-services-nearby`) over Bluetooth and Wi-Fi
   to connect to devices in physical range. Game data goes directly between
   the devices, not through the internet or any server of ours. Google Play
   Services is Google software and handles device radios and identifiers under
   Google's own privacy policy; GameSuite has no control over that. A hosting
   device broadcasts a neutral label ("GameSuite host" plus a short random
   code), not your name, and accepts connections only while its lobby is open
   and a seat is free. Once guests are in the lobby, the display names players
   choose are shared among the people in that match.
3. **Online multiplayer** connects over WebSocket to a relay server
   (`server/index.js`, see [server/README.md](server/README.md)). The relay is
   open-source code that whoever sets it up runs; the app has no built-in
   address and online play stays off until you type one into Settings. The
   relay address you enter is the server your data goes to, so use one you
   trust.

### What the relay receives and keeps

To host, join, find a quick match or spectate, your device sends the relay:
a room code or matchmaking request, a player ID, your display name (shortened
and cleaned by the relay), your avatar identifier, and the game messages for
the match as opaque payloads. In matchmaking, a ticket with your name and
avatar is held in memory while you wait in the queue. The relay forwards
these to the other players in the same room (and spectators, if any). It
supports a fixed list of preset quick-chat phrase and emote IDs (numbers, no
free text); the Android app does not currently expose this feature.

The relay keeps this only in memory for the life of the room or queue entry;
it has no database. It does not write game moves, display names or avatars to
its log.

**IP addresses.** The relay sees the IP address of each connection. It holds
the address in memory to enforce rate limits (join attempts, rooms per
address, queue entries) and to limit how many rooms one address can host. When
it writes a log line that refers to a client, it prints only the first 6 hex
characters of a SHA-256 hash of the IP combined with a random salt generated
each time the process starts, so those log entries cannot be matched across
restarts. Log lines also contain room codes and player IDs, timestamps and
event types (for example, "room created", "player joined", "kicked"). If the
relay runs behind a proxy it may be configured to read the client address from
the proxy's forwarding header. Whoever hosts the relay also controls the
infrastructure, and hosting providers typically keep their own connection and
request logs, which are outside the relay code and covered by that provider's
policies.

Anything you type as a display name is visible to the other players in the
same Nearby or online match and to spectators of an online room.

## Children

GameSuite has no accounts, ads or analytics and no free-text chat. It does not
currently include a parental gate or a setting that turns off Online and
Nearby play. Online play stays disabled until a relay address is entered in
Settings, and a parent or guardian who does not want a child to play with
strangers should not configure one.

## Changes to this policy

If GameSuite adds a feature that changes any of the above (accounts,
analytics, ads, a hosted service, chat), this document will be updated before
that feature ships.

## Questions

GameSuite is an independent, non-commercial project. See
[README.md](README.md) for how to reach the project.
