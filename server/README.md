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

See the comment block at the top of `index.js` for the exact JSON message
shapes (`host`/`join`/`message`/`leave` client→server,
`hosted`/`joined`/`playerJoined`/`playerLeft`/`message`/`error`
server→client). `OnlineTransport.kt`'s KDoc cross-references this same
protocol from the client side.

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
- **Join rate limiting** — a socket that racks up 20 failed `join` attempts
  (bad room code, taken playerId, full room) is disconnected, as basic
  protection against brute-forcing room codes.
- **displayName length cap** — any `displayName` read off an incoming
  message is truncated to 40 characters before it's stored or broadcast.
- **Timestamped logging** — connect/host/join/leave/error events are logged
  with an ISO timestamp for basic operational visibility.

## Continuous integration

`.github/workflows/relay-server.yml` runs `npm ci && npm test` in this
directory on every push/PR that touches `server/**`, using the self-starting
`smoke-test.js` above — so a broken protocol change or a regression of the
crash/room-leak fixes it covers gets caught before merge.
