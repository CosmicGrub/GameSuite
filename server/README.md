# GameSuite relay server

The backend for roadmap item 12's `OnlineTransport` — see that class's KDoc
in `app/src/main/java/com/gamesuite/transport/OnlineTransport.kt` for how the
Android client talks to this.

This is a **relay**, not a game server: it tracks WebSocket rooms and
forwards opaque payloads between the players in one, and never parses game
state. Every game in GameSuite is host-authoritative on-device already (the
same design the local Nearby transport uses) — this server doesn't change
that, it just gives that same host-authoritative model a path over the
open internet instead of only over Bluetooth/Wi-Fi Direct.

## Run it locally (same-Wi-Fi testing — no account, no deployment needed)

```bash
cd server
npm install
npm start
```

That starts listening on `ws://0.0.0.0:8080`. To test with real devices on
the same Wi-Fi network as the machine running this:

1. Find that machine's LAN IP (`ipconfig` on Windows, look for the Wi-Fi
   adapter's IPv4 address — something like `192.168.1.23`).
2. In GameSuite's Settings screen, set the online server address to
   `ws://192.168.1.23:8080` (see `SettingsScreen.kt`'s "Online server"
   field).
3. Host a match on one device, join with the room code shown on another —
   both need to be on the same Wi-Fi network as the server.

This is a fully working, real multiplayer path today — it's the deployment
step below that's optional and only needed for players who *aren't* on the
same network.

## Deploying for real internet-wide play

This step is **yours to do** — I can't create a hosting account on your
behalf (see GameSuite's own project notes on that boundary). The server
itself needs no code changes to go live; you're just choosing where it
runs. A few options that all support a plain Node WebSocket server with a
free tier, in roughly increasing setup effort:

- **Render.com** — connect this repo, "New Web Service", root directory
  `server/`, build command `npm install`, start command `npm start`. Render
  assigns a public `https://your-app.onrender.com` — use `wss://` (not
  `ws://`) for that URL in GameSuite's Settings once deployed.
- **Fly.io** — `fly launch` from the `server/` directory picks up
  `package.json` automatically; `fly deploy` after that.
- **Railway.app** — similar one-click "Deploy from GitHub" flow to Render.
- **Your own VPS** — `npm install && npm start` behind a process manager
  (e.g. `pm2`) and a reverse proxy (e.g. nginx/Caddy) for TLS, if you want
  `wss://` on a domain you own.

Whichever you pick, the only thing GameSuite's client needs afterward is
that service's `wss://` URL pasted into Settings — no code changes.

## Protocol reference

**Wave 5 (room metadata, `peek`/`lock`/`kick`/`started`, quick-chat, matchmaking, rate limits, error codes) is specified
frame by frame in [`CHANGELOG-wave5.md`](CHANGELOG-wave5.md); it is additive and an old client keeps working.** The rest of
this section describes the original frames.

See the comment block at the top of `index.js` for the exact JSON message
shapes (`host`/`join`/`message`/`leave` client→server,
`hosted`/`joined`/`reconnected`/`playerJoined`/`playerDisconnected`/
`playerReconnected`/`playerLeft`/`message`/`error` server→client).
`OnlineTransport.kt`'s KDoc cross-references this same protocol from the
client side.

Three fields carry seat identity (see "Identity and seat takeover" below).
`hosted` and a real-seat `joined` each include a `reconnectToken`, sent only to
the socket that took the seat; a reconnecting `join` presents it back as
`"reconnectToken"`; and every `joined`, a spectator's included, carries
`hostPlayerId`, the room creator's id. A spectator's `joined` has no token.

### Reconnect

A dropped connection (network blip, backgrounded app — anything that isn't
an explicit `leave`) doesn't immediately vacate its seat: the relay holds it
open for 30 seconds (`RECONNECT_GRACE_MS`, overridable via
`RECONNECT_GRACE_MS_OVERRIDE` for tests), telling the rest of the room
`playerDisconnected` rather than `playerLeft`. To resume, the client sends a
`join` with the same room code and playerId **and the `reconnectToken` the
relay gave it when it took the seat** (in `hosted` for the host, in `joined`
for a guest). The sender then gets `reconnected` (the existing roster, like
`joined`) instead of an error, and the rest of the room gets
`playerReconnected`. The token stays valid for as long as the seat exists, so
a seat can be resumed any number of times.

A `join` that reaches a held seat without the right token (none, an empty or
wrong one, a non-string, or another seat's) is refused with `playerId already
in this room`: the same answer a live duplicate gets, so a probe can't tell a
seat in its grace window from an occupied one. A seat whose original socket
is still connected can't be re-taken even with the correct token; the relay
has to notice the drop first (a clean close is noticed at once, a half-open
connection by the ping/pong heartbeat).

A `join` that presents a token for a seat *this room does not have* (the relay
restarted, or reaped the room and later handed its code to a new one) is
refused with `no seat to resume in this room`; it is never seated fresh. A first
join carries no token, so an honest client never hits this, while a client
resuming into a room that merely reuses its code is not handed to whoever
re-hosted that code. (The client applies the same rule from its side: a real
seat accepts only `reconnected`, and treats a fresh `joined` as a foreign room.)
A spectator has no seat and so no secret: it cannot tell a room reusing its code
from its own, which is acceptable for a watch-only seat.

`OnlineTransport.kt` implements the client half: it keeps the token, and
`handleUnexpectedDisconnect` retries with backoff (five attempts: 23 seconds of
back-off plus up to 3 more waiting on each attempt, so the last ones can land
after the 30 second window), presenting the token each time via
`attemptReconnect`, before giving up and surfacing `connectionError`. The
resumed connection is watched like the first one, so a second drop is recovered
the same way. A successful resume fires `MultiplayerTransport.onReconnected` so
the game layer re-requests full state (see `UnoGame.init()`) exactly like a
fresh join does — anything broadcast while disconnected was missed. The resumed
socket is what game sends use from that moment on, including the ones the game
makes from inside `onReconnected` (a game send made *before* the relay
acknowledges the resume is dropped, not queued ahead of the `join`).

### Spectator

`join` with `"spectator":true` gets a read-only seat: it never counts
against `MAX_PLAYERS_PER_ROOM`, never appears in the roster a `GameModule`
builds its player list from, but does receive every broadcast — the same
per-recipient hand-redaction any non-owned real seat already gets (see
UNO's `redactHandsExcept`) applies automatically, since a spectator is never
a real seat to exempt from redaction. `OnlineTransport.joinRoom(...,
spectator = true)` is the client-side entry point; no lobby-screen "Watch"
button exists yet — that's the remaining piece to actually expose this to a
player, not a server or transport limitation.

### Identity and seat takeover

Every relayed `message` carries a `fromPlayerId` that the relay stamps from the
seat the *sending socket* registered when it hosted or joined, never from
anything inside the frame, so a client can't choose it. Games are meant to lean
on that (an engine checks an intent against the seat it acts for, and a guest
should only accept game state from the host's playerId; whether a given game does
is that game's business, not this relay's), which only holds if nobody can take
over another player's seat. playerIds are
**not secret** (the host's is in every guest's roster), so the relay guards a
seat with more than the id:

- **Per-seat token.** When a socket takes a seat the relay generates a random
  token (`crypto.randomBytes(24)`, 32 base64url characters) and sends it to
  that socket alone, in `hosted` or `joined`. It is never part of a roster or
  a broadcast, never logged, and never sent to a spectator (there is no seat
  to resume). Re-taking a seat mid-grace-period requires it, compared in
  constant time. Tokens are per seat and per room, and a client can't pick its
  own. Refusals of well-formed joins count toward the join-failure limit below
  (a malformed join, or an over-long playerId, is rejected without counting).
- **Retired seats.** Every playerId that has held a real seat is remembered
  for the life of the room. A seat that is vacated (an explicit `leave`, or the
  grace period running out) is retired: a later `join` with that id, as a seat
  *or* as a spectator, gets `playerId can no longer be used in this room`,
  even with the old token. The spectator case matters because a host addresses
  each seat's un-redacted hand to that seat's playerId, so a spectator that
  could adopt a vacated id would be handed it. The host's id is retired like any
  other while guests remain. Spectator ids are not retired: a spectator that
  drops just joins again with the same id. A real seat can't take an id a live
  spectator holds, and a spectator can't take a live seat's.
- **Caps.** A playerId is at most 64 characters (the app mints `p-` plus a
  UUID, 38), and a room seats at most 64 distinct playerIds over its whole
  lifetime; after that it answers `Room is closed to new players`, while
  existing seats, their reconnects and spectators carry on. Both bound what a
  room has to remember about retired seats. The trade-off: anyone holding the
  room code can burn the 64 ids by churning joins and close that room to
  newcomers for good. That fails closed, and the same person could already fill
  the 8 seats.
- **Sockets belong to a room, not a room code.** Each socket is bound to the
  room object it registered in. The idle sweep (see Hardening) can reap a room
  whose sockets are still connected, and its code can later be handed to a new
  room; those stale sockets stay inert, so they can neither speak into the new
  room under the id they once held nor vacate a same-named seat there (their
  grace timers are bound the same way). For tests only, the sweep timeout and
  interval and the room-code length can be shrunk with
  `ROOM_IDLE_TIMEOUT_MS_OVERRIDE`, `ROOM_SWEEP_INTERVAL_MS_OVERRIDE` and
  `ROOM_CODE_LENGTH_OVERRIDE`, next to `RECONNECT_GRACE_MS_OVERRIDE`.

Known limits, accepted on purpose: a seat whose old socket is still connected
(a half-open connection the heartbeat hasn't caught yet) can't be resumed until
the relay notices, even with the right token, so an honest client can fail to
resume inside its retry window; a guest whose `joined` reply was lost can't
retry the same playerId (it never got the token, and the seat exists), so it has
to leave and rejoin with a fresh id, which the app's join screen does when it is
re-entered; and a spectator can't verify the room it resumes into (see
Reconnect).

The token is a bearer secret. Over plain `ws://` (the same-Wi-Fi setup above)
anyone on the network path can read it, just as they could read the whole
game; for play over the internet use `wss://`, which the hosting options above
give you. `identity-test.js` (run by `npm test`, next to `smoke-test.js`)
attacks all of this end to end: hijacking a guest seat and the host seat in
the grace window, taking a vacated id, forging `fromPlayerId`, token leaks, the
caps and stale sockets of a reaped room, each next to an honest-client control.

### Upgrading

Ship the app update first, then the relay. The two only work in one direction:

- **New app, old relay** works. The old relay ignores the extra
  `reconnectToken` field and issues no token, and the app falls back to the
  roster's first entry when `hostPlayerId` is missing.
- **Old app, new relay** connects, hosts and plays, but can no longer resume a
  dropped seat. An old build has no token to present, so its reconnect `join`
  gets `playerId already in this room` and it gives up after its retries; the
  seat then lapses when the grace period ends.

So deploying the relay before players have updated turns every network blip
for an old-build player into a lost seat. There is no flag to relax this: the
relay always requires the token.

## Configuration

Everything is optional. The relay is a single stateless Node process: rooms and matchmaking tickets live in memory and are
gone on restart (clients recover by reconnecting; see "Reconnect"). Listen address `0.0.0.0:$PORT`.

| variable | default | meaning |
|---|---|---|
| `PORT` | `8080` | listen port (hosting platforms set it) |
| `TRUST_PROXY` | `0` | `0`: ignore `X-Forwarded-For`. `1`: behind one reverse proxy (Fly, Render, nginx, Caddy): the client is the LAST entry of `X-Forwarded-For`. `N`: N proxies in a chain, the Nth from the right. **Set it to match your host**: unset behind a proxy, every client shares the proxy's single budget; set while directly exposed, it trusts a header clients can forge. |
| `RATE_LIMIT_LOOPBACK` | off | loopback addresses are exempt from per-IP budgets unless `1` (local dev, test suites) |
| `JOIN_FAIL_PER_IP` / `JOIN_FAIL_WINDOW_MS` / `IP_BLOCK_MS` | `10` / `60000` / `60000` | weighted refused joins per window before an IP is blocked, and for how long |
| `HOST_PER_IP_PER_MIN` / `MAX_ROOMS_PER_IP` | `6` / `30` | room-creation rate and live rooms per IP |
| `QUEUE_FRAMES_PER_IP_PER_MIN` | `6` | `queue` frames per IP per minute |
| `MSG_RATE_PER_SEC` / `MSG_BURST` | `240` / `480` | per-socket frame budget (a 60 Hz stream to 3 guests is 180/s) |
| `MSG_BYTES_PER_SEC` / `MSG_BYTES_BURST` | `1048576` / `2097152` | per-socket byte budget |
| `FLOOD_STRIKES` / `FLOOD_STRIKE_WINDOW_MS` | `3` / `10000` | strikes (at most one a second) that terminate a flooding socket |
| `LOBBY_TTL_MS` | `600000` | a new-style lobby holding only its host is closed after this long |
| `CODE_COOLDOWN_MS` | `60000` (`0` if `ROOM_CODE_LENGTH_OVERRIDE` is set) | a freed room code is not reallocated for this long |
| `CHAT_MIN_GAP_MS` / `CHAT_PER_20S` / `CHAT_SAME_ID_MS` | `1200` / `5` / `5000` | quick-chat limits per sender |
| `QUEUE_MAX_TICKETS` / `QUEUE_MAX_PENDING` | `2000` / `100` | matchmaking caps |
| `QUEUE_CLAIM_WITHIN_MS` / `QUEUE_TICKET_MAX_AGE_MS` / `QUEUE_NOSHOW_COOLDOWN_MS` | `15000` / `180000` / `30000` | claim window, ticket lifetime, no-show cooldown |
| `QUEUE_WINDOW1_MS` / `QUEUE_WINDOW2_MS` | `15000` / `30000` | skill-bucket windows (same bucket, then +-1, then any) |
| `RECONNECT_GRACE_MS_OVERRIDE`, `ROOM_IDLE_TIMEOUT_MS_OVERRIDE`, `ROOM_SWEEP_INTERVAL_MS_OVERRIDE`, `ROOM_CODE_LENGTH_OVERRIDE` | | test shrinkers, see "Identity and seat takeover" |
| `ENABLE_TEST_CONTROL` | off | enables the `__test` frames (injectable clock). **Never in production.** |

### Deploying (where it runs is the owner's decision; the server itself is ready)

* **Health**: `GET /health` returns `200` JSON counts (rooms, tickets, pending matches), no room data. Point the platform's
  health check and uptime monitor at it.
* **Docker**: `docker build -t gamesuite-relay server/ && docker run -p 8080:8080 -e TRUST_PROXY=1 gamesuite-relay`. The image is
  `node:20-alpine`, runs as the unprivileged `node` user, has a `HEALTHCHECK` on `/health`, and stops cleanly on `SIGTERM`.
* **Matchmaking needs an always-on host.** Rooms and tickets are in memory, so a platform that sleeps an idle instance (e.g. a free
  tier) loses every queue and room on cold start: fine for friend rooms, not for Quick match.
* **TLS**: terminate `wss://` at the platform/proxy; the reconnect and claim tokens are bearer secrets (see "Identity").
* **Logs** show a connection as `client <first 6 hex of a salted hash of the IP>` (the salt is per process); no IP is stored or
  logged in clear, and nothing is written to disk.
* **One instance only.** There is no shared state between instances; do not scale this horizontally.

### Seat ranges (matchmaking)

`seat-ranges.json` lists every networked game and its Online seat range, so a forged `queue` cannot ask for a table the game
cannot seat. It is **generated** from the Kotlin Game Hub specs by `node gen-seat-ranges.js` (a plain script that parses
`GameHubSpecs.kt` and `games/**/*HubSpecs.kt`; `--check` verifies the checked-in file) and committed. `seat-ranges-test.js` fails
`npm test` when it is stale and when its `onlineMaxSeats` differs from `MAX_PLAYERS_PER_ROOM` in `index.js` (the Kotlin
`ONLINE_MAX_SEATS` must stay equal to it). After adding a networked game or changing a seat range in Kotlin, rerun the generator.

### Extension points for voice (wave 6, nothing built)

The frame set is a closed allowlist in `index.js` (the `default:` branch rejects unknown types) and each wave-5 feature lives in
its own module (`rooms-meta.js`, `quickchat.js`, `queue.js`, `ratelimit.js`). A future `rtc` signalling frame
(`{"type":"rtc","toPlayerId","payloadBase64"}`) fits the existing stamp-and-forward model as one more `case` plus a small module;
audio itself would be peer-to-peer and never touch the relay. `clubId` (an opt-in pseudonymous install id) would be one more
optional field on `host`/`join`/`queue`, relayed like `avatar`. Voice defaults (off in matchmade rooms), `RECORD_AUDIO` timing and
the settings key are client-side decisions; see the design doc section 6.

## Hardening

This is a small free/cheap-tier relay exposed to the open internet, so it
carries a few defensive limits alongside the protocol above (all as named
constants at the top of `index.js`):

- **Payload cap** — the WebSocket server rejects any frame over 64KB
  (`maxPayload`) instead of the `ws` library's 100MB default.
- **Room/room-code caps** — at most 500 rooms exist at once, and the
  random-room-code allocator gives up (with a clear error) after 50
  collisions rather than looping forever as the code space fills.
- **Heartbeat + idle reap** — every open socket is ws ping/ponged every 30
  seconds and terminated if it misses a reply, so a half-open connection
  (e.g. a phone that dropped off Wi-Fi) doesn't sit in a room forever. On
  top of that, a sweep every 5 minutes deletes any room that's had no
  relayed traffic (`lastActivity`) for 30 minutes.
- **Join rate limiting** — a socket that racks up 20 refused `join` attempts
  (bad room code, taken or retired playerId, wrong or unknown reconnect token,
  full or closed room) is disconnected, as basic protection against
  brute-forcing room codes and reconnect tokens. Since wave 5 the same refusals also
  count against the client's **IP** (10 per minute, then a 60 s block that survives
  reconnecting), `peek` misses count too, and hosting, room count and queueing have
  per-IP limits (see `CHANGELOG-wave5.md` section 6 and "Configuration").
- **Flood guard** — each socket has a frame and byte budget; excess frames are dropped and a
  strike counted, three strikes in ten seconds terminate the socket. Defaults are far above
  any real-time game's needs.
- **Lobby TTL and code cooldown** — a new-style lobby that never gets a guest closes after 10
  minutes, and a freed room code is not handed out again for 60 seconds.
- **Display names** — control characters, zero-width and bidi-override characters are stripped,
  whitespace is collapsed, and the name is cut at 40 code points.
- **playerId caps** — a playerId is at most 64 characters and a room seats at
  most 64 distinct ids over its lifetime (see "Identity and seat takeover").
- **displayName length cap** — any `displayName` read off an incoming
  message is truncated to 40 characters before it's stored or broadcast.
- **Timestamped logging** — connect/host/join/leave/error events are logged
  with an ISO timestamp for basic operational visibility.

## Continuous integration

`.github/workflows/relay-server.yml` runs `npm ci && npm test` in this
directory on every push/PR that touches `server/**`. `npm test` runs, in order, the
self-starting `smoke-test.js`, `identity-test.js` (seat takeover), `seat-ranges-test.js`
(generated seat table is fresh and matches the relay's cap), `room-meta-test.js` (metadata,
peek, lock/kick/started, avatars, names, lobby TTL, code cooldown, health), `ratelimit-test.js`
(per-IP budgets, TRUST_PROXY with and without, flood guard incl. a 60 Hz stream),
`quickchat-test.js` and `queue-test.js` (matchmaking algorithm in-process, then over the wire),
so a broken protocol change, a regression of the crash/room-leak fixes, or a reopened seat-takeover
hole gets caught before merge. Every test spawns its own relay on a free port, awaits specific
frames (never sleeps and hopes), drives time through the relay's injectable clock
(`ENABLE_TEST_CONTROL=1`, so no real 30-second waits) and kills its children on every exit path.
Shared harness: `test-lib.js`. The freshness check of `seat-ranges.json` needs the Kotlin sources,
so it runs in the repo checkout CI uses and is skipped (loudly) in a server-only context.
