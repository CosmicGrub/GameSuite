# GameSuite Privacy Policy

*Last updated: 2026*

GameSuite is a local Android game collection. This policy describes exactly
what the app does and does not do with your data — written to match the
actual code, not a generic template.

## What GameSuite stores on your device

- **Settings** (theme, sound, haptics, text size, accessibility toggles,
  default CPU difficulty, card size, your configured online relay address) —
  stored locally via Android's DataStore, never transmitted anywhere.
- **Game stats** (matches played, wins, losses, draws per game) and
  **Sliding Puzzle's best-move/best-time records** — stored locally the same
  way. Nothing is uploaded to any server, and there is no account to sign
  into that would let this sync across devices.

None of this is encrypted at rest beyond whatever protection Android's own
app-sandboxed storage provides, and none of it is backed up off-device
unless you use Android's own system backup feature (`android:allowBackup`),
which restores it only to a device signed into the same Google account.

## What GameSuite does NOT do

- **No analytics or crash-reporting SDK** of any kind is included in the
  app — nothing about how you use GameSuite is collected, on-device or off.
- **No advertising SDK** — GameSuite shows no ads.
- **No account or sign-in** — there is no username/password/email
  collection anywhere in the app.
- **No location data** — Nearby Connections' Bluetooth/Wi-Fi permissions are
  requested with `neverForLocation` where the platform allows it, and the
  app never reads device location.

## Multiplayer data — what's actually exchanged, and with whom

GameSuite has three multiplayer modes, each with a different (and
increasingly wider) data footprint:

1. **Pass-and-play** (one device, players take turns): no network activity
   at all.
2. **Nearby (ad-hoc local multiplayer)**: uses Android's Nearby Connections
   API over Bluetooth/Wi-Fi Direct to talk directly to another device in
   physical range. Nothing leaves the two (or more) devices in the room —
   there is no internet involved and no third-party server sees any of it.
3. **Online multiplayer**: connects over the internet (WebSocket) to a relay
   server — see [server/README.md](server/README.md). This is **not** a
   GameSuite-operated cloud service: it's small, open-source relay code
   (`server/index.js`) that you or whoever's hosting your match runs
   yourselves, typically on a free-tier host or a home server. The relay is
   deliberately a "dumb" pipe: it never inspects, logs, or stores the
   contents of game moves — it only ever sees a room code, the display name
   and player ID you typed in for that match, and however many bytes your
   device sends as an opaque payload, all of which exist only in that
   server process's memory for the life of the room and are discarded when
   the room empties or the relay restarts. If you or someone else deploys
   the relay to a host that keeps its own request/connection logs (most
   hosting providers do, at the infrastructure level), that's a property of
   wherever it's deployed, not of the relay code itself — see that host's
   own privacy practices.

Whoever displayName you type into an online or Nearby match is visible to
the other players in that same room/match — that's the only "identity" any
multiplayer mode in GameSuite has.

## Changes to this policy

If GameSuite ever adds a feature that changes any of the above (an account
system, analytics, ads, a hosted online service), this document will be
updated to say so plainly before that feature ships.

## Questions

GameSuite is an independent, non-commercial project. See
[README.md](README.md) for how to reach the project.
