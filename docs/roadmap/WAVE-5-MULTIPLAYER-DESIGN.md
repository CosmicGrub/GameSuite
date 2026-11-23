# Wave 5 design: multiplayer superiority

Status: DESIGN ONLY (2026-11-07, branch session/wave-3). Nothing here is built. Scope is wave 5 of
`docs/roadmap/CLUBHOUSE-PARITY.md`: room codes, a matchmaking queue, 3-4 seat games, quick-chat.
Voice chat is wave 6 and is only *left room for* (section 6). Every gate in `QUALITY-BAR.md` (gate 6
multiplayer, gate 7 EN+ES, gate 5 accessibility) applies to each slice below.

Legend used throughout: **EXISTS** = read in the code today. **NEW** = this design. **GAP** = a defect or
hole found while auditing, which the design fixes or flags.

---------------------------------------------------------------------------------------------------

## 0. What exists today (audit summary)

### 0.1 Transport layer

| Piece | File | What it is |
|---|---|---|
| `MultiplayerTransport` | `shared/src/commonMain/.../transport/MultiplayerTransport.kt` | `connect/disconnect/send(from,to?,payload)/onMessageReceived/onPlayerJoined/onPlayerLeft` plus a default-no-op `onReconnected`. `fromPlayerId` on receive is transport-stamped, never sender-written. |
| `LocalPassAndPlayTransport` | `shared/.../LocalPassAndPlayTransport.kt` | same-device, echoes as given. |
| `NearbyConnectionsTransport` | `app/.../transport/NearbyConnectionsTransport.kt` | Google Nearby, `P2P_CLUSTER`. Every payload goes to the raw listener AND (if the endpoint has a seat) to the game listener (`payloadCallback.onPayloadReceived`). Lobby protocol in `NearbyLobbyProtocol.kt` (`JoinRequest`, `GameStart`), seating in `NearbyRoster.kt` (`LobbySeatLimits`, host assigns ids). |
| `OnlineTransport` | `app/.../transport/OnlineTransport.kt` | OkHttp WebSocket to the relay. `hostRoom/joinRoom`, `roomCode`, `connectedPlayers`, `hostPlayerId`, reconnect with 1/2/4/8/8 s backoff presenting the per-seat `reconnectToken`. One envelope `WireMessage` mirrors `server/index.js`. |
| `LanMultiplayerTransport` | `shared/src/jvmMain/.../LanMultiplayerTransport.kt` | JVM-only (desktop/tests). Not referenced from `app/src/main`. Out of scope. |
| Lobby protocol (Online) | `OnlineLobbyProtocol.kt` | `GameStart(gameRoute, players, gameDisplayName)`, `Rejected(reason)`, `Hello(spectator)`, `OnlineLateJoinResponder`, `seatOnlineGuests`, `isFromHost`, `localSeatIndex`. `LobbyIdentity.kt`: `guestSeatIndex` (rejects blank/duplicate ids and seat 0). `LobbyJson` ignores unknown fields. |

### 0.2 Relay server (`server/index.js`, 532 lines, Node + `ws`)

- Rooms are `Map<roomCode, {members, spectators, lastActivity, hostPlayerId, usedSeatIds}>`.
- Code: **4 characters** from a 32-symbol alphabet (no 0/O/1/I), so 32^4 = 1,048,576 codes. `MAX_ROOMS = 500`,
  collision retry bounded by `ROOM_CODE_ALLOC_ATTEMPTS = 50`.
- `MAX_PLAYERS_PER_ROOM = 8` (spectators do not count), `MAX_SEAT_IDS_PER_ROOM = 64`, `MAX_PAYLOAD_BYTES = 64 KiB`,
  `MAX_DISPLAY_NAME_LENGTH = 40`, idle reaping at 30 min, sweep every 5 min, ws heartbeat 30 s.
- Identity: `fromPlayerId` is stamped from the socket's registered seat. Per-seat `reconnectToken` (192-bit),
  retired seats can never be reclaimed, socket bound to room object (survives code reuse).
- Reconnect grace `RECONNECT_GRACE_MS = 30 s`, other members get `playerDisconnected`/`playerReconnected`.
- Abuse control: `JOIN_FAILURE_LIMIT = 20` failed joins *per socket* then terminate. **GAP**: it is per socket, not
  per IP, so a script reconnects for a fresh budget. 20 guesses/socket against ~500 live codes is ~1% per socket;
  with free reconnects, enumeration of live rooms is feasible. There is also no limit on `host` frequency, no
  limit on `message` rate, and no room-creation rate limit.
- **GAP**: the relay does not know the *game* or the *seat count* of a room, nor whether the match started.
  The host client enforces seats (`seatOnlineGuests`) and bounces overflow with `Rejected`. A joiner learns
  "wrong game / full / already started" only after joining and saying `Hello` (see `lobbyGameProblem`,
  `lobbyRejectionText` in `OnlineJoinLobbyScreen.kt`).
- **GAP**: relay `message` frames are unmetered; a room member can flood the room up to the socket's own speed.
- Tests: `server/smoke-test.js` and `server/identity-test.js` (spawn `node index.js` themselves, `npm test` runs both),
  CI in `.github/workflows/relay-server.yml`.

### 0.3 Client lobby/room flow

- Entry: `ui/OnlineEntryScreen.kt` (needs `settings.onlineServerUrl`, else sends the user to Settings),
  `LobbyNameField`, Host / Join buttons, each creates `OnlineTransport(serverUrl)` into
  `GameSessionManager.pendingOnlineTransport`.
- Host: `ui/OnlineHostLobbyScreen.kt` mints `"p-" + UUID`, calls `hostRoom`, shows the code in `displayMedium`
  text and "Share this with other players" (text only: no copy button, no share sheet, no deep link).
  Start button enabled by `LobbySeatLimits.canStart`. On Start: broadcasts `GameStart`, installs
  `OnlineLateJoinResponder`, calls `sessionManager.launchGame(PlayMode.ONLINE, players, 0, transport)`.
  Seats come from `LobbySeatLimits.forGame(gameId, HubModeKind.ONLINE)` (spec-driven).
- Guest: `ui/OnlineJoinLobbyScreen.kt`: types 4-char code (`ROOM_CODE_LENGTH = 4` duplicated from the server),
  optional "Watch" (spectator), `Hello` after join and after reconnect, acts only on host-stamped `GameStart`/`Rejected`.
  NavController.navigate must run on the main thread (see its long comment: the `pendingGameStart` state pattern).
- `LobbySeatRow` shows an avatar but derives it from the *name* (`Avatar.forSeed(name)`): **the relay never
  carries a player's real club avatar.** Club profile (`core/ClubProfile.kt`, `ClubProfileStore`, `ClubViewModel`)
  holds `displayName`, `Avatar(character,color,frame)` with `encode()/decode()` ("pip|sky|circle"), `favoriteGameId`,
  `coachSeen`. It holds **no win/loss or rating data**. Per-game stats stores exist (`BreakoutStatsStore`, ...)
  but are solo-game specific. Match results surface as `GameSessionManager.lastMatchOutcome`
  (`MatchOutcome(gameId, localOutcome, result)`), which is where a rating can be fed from.
- `PlayerInfo(playerId, displayName, isBot, teamId)` already carries `isBot`; engines such as `UnoGame` use it
  (`roster = context.players.map { UnoSnapshotSeat(it.playerId, it.isBot) }`, auto-resolving bot seats).
- `uikit/GameChrome.kt` wraps all games (108 call sites), the natural host for a game-agnostic overlay.
- Spectators: `GameHubSpecs.spectatorGames = setOf("uno")` only.
- **GAP (host leaves)**: in `UnoGame`, only the *host* registers `onPlayerLeft`. A guest whose host vanishes (relay
  `playerLeft(hostId)` after the 30 s grace) gets no game-level reaction, so the match is a zombie. There is no
  "host left" concept in the shared protocol.

### 0.4 Hub specs (`core/GameHubSpecs.kt`)

- `networkedGames` has 42 ids (uno ... pig-dice). `networkModes()` appends Nearby and Online modes with
  `minSeats` = smallest multi-seat mode min (>= 2) and `maxSeats` = largest mode max; Online is clamped
  `minOf(maxSeats, ONLINE_MAX_SEATS = 8)`. Note `ONLINE_MAX_SEATS` (Kotlin) must stay equal to
  `MAX_PLAYERS_PER_ROOM` (JS): there is no test tying them together (**GAP**).

---------------------------------------------------------------------------------------------------

## 1. Room codes: "create room, share code, friends join"

### 1.1 Today's flow
Host opens Online, taps Host, sees `AB3D`, tells friends by voice or chat; each friend opens the same game,
taps Join, types the 4 characters. It works end to end (relay + `OnlineJoinLobbyScreen`), but it is a developer
flow, not a Clubhouse one.

### 1.2 Missing for the Clubhouse feel (client)

1. **Share**: a "Share invite" button using `Intent.ACTION_SEND` with text
   `"Join my <Game> room on GameSuite: code AB3D  https://<invite-host>/j/AB3D"` and a "Copy code" button with a
   TalkBack-announced confirmation. Needs EN+ES strings (new `online_room_strings.xml` + `values-es`).
2. **Deep link**: `gamesuite://join?code=AB3D&game=chess` (custom scheme only in v1; an https App Link needs a
   hosted `assetlinks.json`, an owner decision). `MainActivity` parses it, opens the Online join screen for that game
   with the code prefilled. A link for a game id not in `networkedGames` or not installed on this build falls back to
   the Hub with a toast.
3. **Paste-friendly code field**: strip spaces/dashes, uppercase, accept the invite text pasted whole (extract
   `[A-HJ-NP-Z2-9]{4}`), reject `0/O/1/I` locally with a message (the server already excludes them; client currently
   does not say why a code is invalid). Put `ROOM_CODE_LENGTH` and the alphabet in one Kotlin constant used by
   field and tests, documented as mirroring the server.
4. **Roster with real avatars and names**: carry the avatar string over the relay (section 1.4) and render
   `AvatarBadge` from it instead of `Avatar.forSeed(name)`. Guests also see who is already in the room before
   Start (today a guest sees only "Waiting for the host...").
5. **Room metadata before join**: after typing a code the joiner sees "Chess room, 1 of 2 seats, host Sam" and a
   disabled Join with the reason if it is wrong-game/full/started (needs relay `peek`, section 1.3).
6. **Host controls**: Lock room (stop accepting), Kick (per-seat), Ready-check before Start (section 3.3).
7. **Room expiry in the UI**: a lobby that never starts shows "Room closes in 9:59" and extends on activity.

### 1.3 Relay changes (NEW, `server/index.js`, then split)

`index.js` is already 532 lines with one big `switch`. Add, in small steps (and extract new logic into new
modules rather than growing the file): `server/rooms-meta.js`, `server/ratelimit.js`, later `server/queue.js`,
`server/quickchat.js`.

Protocol additions (all additive; an old client never sends them, an old relay ignores unknown fields on the new client
because `WireMessage` uses `ignoreUnknownKeys`):

```
C->S {"type":"host","playerId","displayName","avatar":"pip|sky|circle",
      "gameId":"chess","maxSeats":2,"minSeats":2,"public":false}
S->C {"type":"hosted","roomCode","playerId","reconnectToken","expiresInMs":600000}
C->S {"type":"peek","roomCode":"AB3D"}                  // no seat taken, no token issued
S->C {"type":"room","roomCode","gameId","seatsTaken":1,"maxSeats":2,"state":"lobby|started|locked",
      "hostName":"Sam"}                                  // or {"type":"error","code":"no_such_room"}
C->S {"type":"lock","locked":true}                       // host only
C->S {"type":"started"}                                  // host only, sent with GameStart; room -> "started"
C->S {"type":"kick","playerId":"p-..."}                  // host only
S->C {"type":"kicked"}                                   // to the target, then its seat is retired
S->C {"type":"error","code":"room_full|room_locked|room_started|wrong_game|rate_limited|...","message":"..."}
```

Rules:
- **Metadata is advisory and host-declared** (relay stays "dumb"): `gameId` must match `^[a-z0-9-]{1,32}$`;
  `maxSeats` clamps to `1..MAX_PLAYERS_PER_ROOM`. The relay now *can* refuse a seat when `members.size >= maxSeats`
  (today the host client does it), which removes the join-then-bounce round trip. Keep the client-side
  `seatOnlineGuests` as defense in depth (a hostile host is not our threat model here; a stale client is).
- `kick` reuses the existing retirement machinery: `finalizeLeave` + `usedSeatIds` already make a vacated id
  unusable for the life of the room, so a kicked player cannot rejoin under the same id. They can mint a new
  `p-` id and rejoin: kick is a courtesy, not a ban. Combine with `lock` for a real "no more".
- Only the registered host (`room.hostPlayerId === meta.playerId`, derived from the socket, never the frame) may
  send `lock/started/kick`.
- **Spectators**: `join` with `spectator:true` is unaffected. A `locked` room still admits spectators if the game is
  in `spectatorGames` (host option `allowSpectators`, default true for those games, enforced by the host's
  `Hello` answer as today).

### 1.4 Avatar over the wire (privacy-checked)
`avatar` is a *club avatar string* only: server validates `^[a-z]{2,12}\|[a-z]{2,12}\|[a-z]{2,12}$`, max 40
chars, drops it silently if invalid (a missing avatar means the client uses `Avatar.forSeed`). It is relayed in
`joined.players[].avatar`, `playerJoined.avatar`. `Avatar.decode` is already lenient (unknown parts fall back to
defaults), so a hostile string cannot crash a client. No other profile field (favourite game, coachSeen) is sent.
`WirePlayer`/`OnlinePlayer` get an optional `avatar: String? = null`.

### 1.5 Collision, brute force, expiry

| Concern | Today | Design |
|---|---|---|
| Code space | 4 chars, 1.05M | Keep 4 for friend rooms (readable aloud). Matchmade rooms use an internal 8-char id never shown to users (section 2). |
| Guessing a live room | 20 failures/socket, no IP limit | Add `ratelimit.js`: token bucket per **IP** (`X-Forwarded-For` honoured only if `TRUST_PROXY=1`; Render/Fly sit behind proxies) for `join`: 10 failures/min then 60 s block; `host`: 6/min and 30 live rooms per IP; global `MAX_ROOMS` stays. Failed-join response time is constant (no oracle). |
| Probing existence via `peek` | n/a | `peek` counts as a failure-budget hit when it returns `no_such_room`; successful peeks cost 1/10 failure. |
| Idle expiry | 30 min since last traffic | Add lobby TTL: an unstarted room with only the host is closed after 10 min of no joins (`expiresInMs` sent so the UI can show it); a started room keeps the 30 min idle rule. Environment-overridable like the existing `ROOM_IDLE_TIMEOUT_MS_OVERRIDE`. |
| Code reuse after reap | stale sockets inert (`registeredRoom`) | Unchanged, covered by existing `identity-test.js`. Also add a short cooldown (60 s) before a reaped code is reallocated so a slow friend typing the code does not land in a stranger's room. |
| Message flood | none | Per-socket `message` bucket: 60 frames/s burst 120, bytes/s cap 256 KiB; over limit -> drop + `rate_limited` error, 3 strikes in 10 s -> terminate. Must not throttle legitimate real-time games (air hockey, tanks); the numbers are an owner-tunable default and are env-overridable. |
| Impersonation of a host | seat tokens | Unchanged. `peek` never reveals tokens or player ids, only counts and the host display name. |

---------------------------------------------------------------------------------------------------

## 2. Matchmaking queue

### 2.1 Principles
1. The relay stays a dumb relay for *game* traffic. The queue is a separate, small, stateless-on-disk module.
2. A match is handed to the **existing** host-authoritative flow unchanged: exactly one device acts as host
   (seat 0), `GameStart` is host-stamped, `OnlineLateJoinResponder` and reconnect all keep working.
3. Zero personal data: a ticket holds only `{gameId, seat range, skill bucket, club avatar string, sanitized
   display name, ephemeral playerId}`. Nothing is written to disk; tickets die with the socket.
4. v1 is **turn-based and board/card games only** (section 2.6). Real-time games wait for RTT gating.

### 2.2 Wire protocol (NEW)

```
C->S {"type":"queue","playerId":"p-...","displayName":"Sam","avatar":"pip|sky|circle",
      "gameId":"chess","optionsKey":"std","minSeats":2,"maxSeats":2,
      "bucket":1,"fillAfterMs":20000,"canHost":true}
S->C {"type":"queued","ticketId":"t-...","estimatedWaitMs":null,"queueSize":3}      // size only if >= 3, no exact counts below
S->C {"type":"queueUpdate","waitedMs":15000,"widened":true}                         // every 5 s, drives UI
C->S {"type":"queueCancel"}                                                         // also implicit on socket close
S->C {"type":"matchFound","roomCode":"K7MQ2X9R","hostPlayerId":"p-...","claimToken":"...",
      "seats":[{"playerId","displayName","avatar"}], "cpuSeats":0,"claimWithinMs":15000}
C->S {"type":"claim","roomCode","playerId","claimToken","displayName","avatar"}
S->C {"type":"hosted"|"joined", ...}                                                // identical to today, incl. reconnectToken
S->C {"type":"matchAborted","reason":"no_show|host_failed"}                         // others are re-queued with their original age
```

`claim` is implemented as an alternative front door to the *same* seating functions `host`/`join` use today
(`index.js` seats `members`, issues `reconnectToken`, fills `usedSeatIds`), so reconnect, retirement, token and
spectator logic are inherited, not re-implemented. A `claimToken` is single-use (192-bit, `crypto.randomBytes`),
bound to `{roomCode, playerId}`, and expires after `claimWithinMs`.

### 2.3 Forming a match (server, `server/queue.js`)

State: `queues: Map<queueKey, Ticket[]>` where `queueKey = gameId + '|' + optionsKey + '|' + maxSeats`.
Options that change the game (e.g. Chess clock, UNO house rules, board size) go into `optionsKey` so only
players who agreed to the same rules meet; v1 allows only the `"std"` option set in the quick-match UI and
exposes custom options only for friend rooms.

Algorithm, run on every enqueue, every cancel, and once per second:
1. For each queue, sort tickets by `enqueuedAt` (oldest first: fairness is FIFO, never skill-first).
2. **Skill bucketing**: `bucket in {0,1,2}` (Newcomer / Regular / Veteran, section 2.4). A ticket may match
   others within `window(wait)` buckets: 0 s..15 s same bucket only; 15 s..30 s +-1; beyond 30 s any. The window
   is computed from the *oldest* ticket in the candidate group so a new arrival never worsens an old ticket's wait.
3. A group is complete when `humans >= maxSeats`. For `minSeats < maxSeats` games, a group of `humans >= minSeats`
   is **also** released once its oldest ticket has waited `fillAfterMs` (default 20 s) -- this is the CPU-fill
   rule: `cpuSeats = maxSeats - humans` when the player opted in (`fillAfterMs != null`), else keep waiting.
   A single human is never "matched": the client offers "Play the computer now" locally (section 2.5).
4. **Host choice**: among the group's tickets with `canHost: true`, the one with the lowest server-measured
   ping RTT (the relay already pings every socket every 30 s; add an `rttMs` taken from ping/pong timestamps,
   updated at queue time with one extra ping), ties broken by oldest ticket. The relay can measure RTT; the
   client cannot be trusted to report it. `canHost:false` is sent by clients on metered/low battery/background
   restrictions (owner decision O6). If nobody `canHost`, no match forms and the ticket sees "no players available".
5. Create the room object **server-side** with `hostPlayerId` preset, reserved seats and `claimToken`s, then send
   `matchFound` to each ticket. The room is invisible to `peek` and has `public:false`.
6. If any ticket has not `claim`ed within `claimWithinMs` (15 s: this is the *ready check*, because a claim is only
   possible from a foregrounded app with a live socket), delete the reservation, send `matchAborted` to those who
   claimed, and **re-queue them with their original `enqueuedAt`** (no punishment to the innocent). The no-show
   ticket is dropped and gets a 30 s queue cooldown (socket-bound only; no identity to punish).

### 2.4 Skill bucketing from ClubProfile data
`ClubProfile` has no results data, so a rating must be NEW and local:
- `core/OnlineRatingStore.kt` (DataStore, same pattern as `ClubProfileStore`): per `gameId` `{games, rating}`
  with a simple Elo (K=24 below 20 games, 12 above, start 1000), updated from `GameSessionManager.lastMatchOutcome`
  when the match mode was `PlayMode.ONLINE` and the room was matchmade (`GameContext` gets a `matchmade` flag).
  CPU seats do not move a rating. An aborted match changes nothing (`GameResult.wasAborted`).
- `bucket(gameId)`: `games < 8 -> 0`, else `rating < 1100 -> 1`, else `2`. Only the integer bucket crosses the
  wire. The rating itself, game counts and `ClubProfile.favoriteGameId` never leave the device.
- It is self-reported and forgeable. That is acceptable because the bucket only affects *who you wait with*,
  and v1 has **no leaderboard, no ranked rewards**. If a leaderboard is ever added it needs real accounts: out of scope.

### 2.5 Client flow (NEW)

- `OnlineEntryScreen` gains a primary **Quick match** button above Host/Join (Host/Join keep today's behaviour for
  friend rooms). New screen `ui/QuickMatchScreen.kt` + `core/MatchmakingController.kt` (plain class driven by
  `OnlineTransport`, no Compose dependencies, so it is unit testable with a fake).
- States: `Idle -> Connecting -> Queued(waited, widened) -> MatchFound(claiming) -> Seated(host|guest) -> Starting`,
  plus `Failed(reason)`/`Cancelled`. The screen shows avatars as seats fill (from `matchFound.seats`), a
  Cancel button (always visible, 48 dp), and after `fillAfterMs` a secondary action "Play the computer instead"
  (cancels the ticket then launches the local vs-CPU mode through `buildHubLaunch`) -- the user is never trapped
  waiting.
- `OnlineTransport` needs: `queue(...)`, `cancelQueue()`, `onMatchFound` callback, `claim(...)`. `handleIncoming`
  gets `queued/queueUpdate/matchFound/matchAborted` branches; `WireMessage` gets the new optional fields.
- **Host driver**: when `matchFound.hostPlayerId == localPlayerId`, a `MatchHostDriver` plays the role the Start button plays
  today in `OnlineHostLobbyScreen` (builds `GameStart`, installs `OnlineLateJoinResponder`, calls `launchGame`). To avoid a
  third copy of that code, extract it from `OnlineHostLobbyScreen` into `core/OnlineMatchStarter.kt` first (slice S3)
  and have both call it. The host auto-starts when all `claim`s arrive (no Start button) with a 3 s "Match found!" beat.
- **Guest**: identical to `OnlineJoinLobbyScreen`'s `pendingGameStart` handling (including the main-thread navigation rule).
  Extract that too.
- **Backgrounding**: lifecycle `ON_STOP` while queued -> `cancelQueue()` (no foreground service in v1; a stale
  ticket that cannot claim only wastes other players' 15 s). A claim failure on resume shows "Match expired".
- **Cancel/leave**: `queueCancel` is instant and idempotent; closing the socket also cancels. Leaving a *started*
  matchmade game uses the normal leave path; a private counter `earlyLeaves` (local only) can drive a gentle note
  after repeated leaves, never a server penalty.

### 2.6 Which games get Quick match in v1
Turn-based with `GameModule` host authority already verified by tests: Chess, Checkers, Reversi, Connect Four,
Tic-Tac-Toe, Gomoku, Hex, Nine Men's Morris, Mancala, Dots and Boxes, Shogi/Mini Shogi, Chinese Checkers, Ludo,
Dominoes, UNO, Blackjack, War, President, Sevens, Texas Hold'em, Hanafuda, Pig Dice. Real-time/physics games
(Air Hockey, tanks, slot cars, toy sports, darts-like aiming, Speed, Matching) are **excluded** until RTT gating
exists: the host would enjoy zero input latency and the guest 2x RTT. Add a spec flag
`HubMode.quickMatch: Boolean` (default false) rather than hard-coding a list in the screen.

### 2.7 Host dropping, migration, reconnect
- **Short drop (< 30 s)**: already handled. Relay holds the seat, `OnlineTransport.attemptReconnect` re-joins with the
  `reconnectToken`, guests get `playerDisconnected/playerReconnected`, the returning host answers `RequestState`.
  NEW: surface it ("Sam is reconnecting...") from `playerDisconnected`, currently deliberately ignored in
  `OnlineTransport.handleIncoming`. Expose `MutableStateFlow<Set<String>> awayPlayers`.
- **Host gone (grace expired, `playerLeft(hostId)`)**: **v1 aborts the match cleanly.** NEW game-agnostic reaction in
  `GameChrome` or `GameSessionManager` (not per game): on `onPlayerLeft(hostPlayerId)` for a guest, show "The host
  left. Match ended." and call the existing abort path (`GameResult.wasAborted = true`), which records no win/loss and no
  rating change. This also fixes the GAP found in 0.3 for every currently networked game at once.
- **Migration (v2, research only)**: possible only where hidden information does not exist (board classics) because the
  successor would have to hold the full state. Sketch: seat 1 is the designated successor; each host commit also
  sends an addressed `SnapshotBackup` (existing `ResumableGame` validated snapshot string) to seat 1; on host loss
  seat 1 restores, announces `HostMigrated(newHostPlayerId)` signed by relay stamp, relay's `room.hostPlayerId` is
  updated by a new `promote` frame accepted only when the current host seat is vacated. Needs `OnlineTransport.hostPlayerId`
  to become mutable-by-event and every guest's host check (`isFromHost`) to follow. Not in wave 5; see O5.
- **Hidden-info games and a stranger host**: the host sees every hand (host-authoritative). With friends this is
  fine; with strangers a modified client could cheat. v1 mitigation is policy, not tech: matchmade games grant no
  rating-based rewards online beyond the (forgeable) local bucket, and the lobby tells hidden-info Quick match players
  nothing false. Real mitigation (mental poker / server authority) is out of scope. This is the main product risk (R1).

### 2.8 Fairness, abuse, privacy summary

| Topic | Rule |
|---|---|
| Fairness | FIFO by age, bucket window widens with age, no skill-first jumping, host chosen by lowest measured RTT, CPU fill labelled in the UI ("2 players + 2 computers"). |
| Re-match avoidance | Same two sockets are not re-matched within 60 s if another candidate exists (prevents farming/loops). Best-effort, socket-scoped. |
| Rate limits | 1 active ticket/socket; 6 `queue` frames/min/IP; ticket max age 3 min then `queueExpired`; `MAX_TICKETS = 2000`, `MAX_QUEUE_ROOMS_PENDING = 100`. Reuses `ratelimit.js`. |
| Queue spoofing | A forged `gameId` only wastes the forger's time. `maxSeats` clamped to the game's known range (relay keeps a tiny allowlist `{gameId:[min,max]}` generated from `GameHubSpecs` by a script and checked by a Kotlin test, section 7). |
| Privacy | Ticket = ephemeral playerId + name + avatar string + bucket. No device id, no IP persisted, logs show ticket counts and the first 6 chars of a hashed IP only. `PRIVACY_POLICY.md` needs one paragraph (the relay sees IP transiently; display name is user-chosen and visible to opponents). Child-safety: display names are free text today: add a client-side name filter later (O9). |
| Names | `sanitizeDisplayName` already truncates; add control-character stripping and collapse whitespace on the relay. |

---------------------------------------------------------------------------------------------------

## 3. 3-4 seat support audit and lobby changes

### 3.1 Audit: seat ranges (from the Hub specs) vs Nearby/Online readiness

Source of truth: `HubMode.minSeats/maxSeats` in `core/GameHubSpecs.kt` and the per-family `*HubSpecs.kt` files.
`LobbySeatLimits.forGame(gameId, kind)` converts a mode into lobby limits; `networkModes()` derives Nearby/Online seats.

| Game (id) | Seats | In `networkedGames`? | Notes for Online |
|---|---|---|---|
| UNO (`uno`) | 2-10 (team mode min 4) | yes | Online capped to 8 by `ONLINE_MAX_SEATS`; only game with spectators; `isBot` honoured. |
| Texas Hold'em | 2-6 | yes | `isBot` used in 3 files; hidden hole cards redacted per-recipient (verify per-seat redaction test). |
| President / Sevens | 3-6 | yes | min 3: `LobbySeatLimits.of` makes the lobby need 2 guests; **CPU fill is the natural way to reach 3**. |
| Chinese Checkers | 2-6 | yes | |
| Pig Dice | 2-6 | yes | |
| Ludo | 2-4 | yes | |
| Blackjack, War | 2-4 | yes | |
| Carrom, Curling, Darts, Bowling, Mini-golf, Slot Cars, Fishing, Tank Battle, Shooting Gallery, Six-Ball, Matching | 2-4 | yes | Real-time/aim games: lobby works, Quick match excluded (2.6). |
| Toy Soccer | **4-4** (min = max = 4) | yes | An Online lobby cannot start with fewer than 4 humans and has no CPU fill: effectively unplayable online with friends. **GAP**, fix via CPU fill (3.2). |
| Yahtzee | 2-4 | **no** (not in `networkedGames`) | Candidate for the first new networked 3-4 seat game: dice, no hidden info, engine seat-agnostic (verify). |
| Chess, Checkers, Reversi, Connect Four, Gomoku, Hex, Morris, Mancala, Shogi, Dots and Boxes, TTT, Hare and Hounds | 2 | yes | Unaffected. |
| Toy Tennis, Toy Boxing, Toy Baseball, Speed, Dominoes, Hanafuda, Word Tiles | see specs | yes | Seat counts were not captured by the grep used for this audit; the slice-S4 test (3.5) prints them. |

**Not verified in this read-only pass** (do these as the first task of slice S4, the answer decides scope): for each
3+ seat game, (a) does the engine, when `PlayMode.ONLINE` and `players` contains `isBot` seats, drive those seats on the
**host** only (UNO does at `UnoGame.kt:718`; `isBot` appears in the other games' sources but whether it is honoured
in networked mode is untested); (b) does `redact` send only the recipient's own hand to each guest; (c) does the
game cope with a seat vanishing mid-match (guest `playerLeft`): only UNO's host reacts today (a status line).

### 3.2 Lobby UI and logic changes

1. **Seat grid** replacing the flat "Joined:" list in `OnlineHostLobbyScreen` / `NearbyHostLobbyScreen`: N seat
   cards (N = `limits.maxPlayers`), each `Empty | Human(avatar,name,ready) | CPU(level) | Host`. 48 dp targets,
   TalkBack: "Seat 3 of 4: Sam, ready" / "Seat 4 of 4: empty, tap to add a computer player".
2. **CPU fill** (host-side, both Nearby and Online): empty seats can be filled by tapping, or "Fill with
   computers" fills all empties; roster entries become `PlayerInfo(playerId = "cpu-n", displayName = localized "Computer 2",
   isBot = true)`. `LobbySeatLimits` gains `fun canStart(humans, cpus)` where `humans + cpus` must be within
   `minPlayers..maxPlayers` and `humans >= 2` (a one-human table is a local game, not a lobby). CPU level comes from the shared
   CPU level (`core/CpuLevel.kt`, the wave-2 setting) chosen once in the lobby. Because `GameStart.players` already
   carries `isBot` and `guestSeatIndex` only rejects blank/duplicate ids and seat 0, no wire change is required;
   add `cpu-` ids to the validation tests so a guest never claims one.
3. **Toy Soccer fix**: with CPU fill, 2 humans + 2 computers starts. Same for President/Sevens (3 min).
4. **Min-seat messaging**: `LobbySeatLimits.waitingMessage` becomes localized and CPU-aware ("Add 1 more player or a
   computer").
5. **Seat order**: join order today. Add host drag/reorder only if a game cares (team games: UNO teams use
   `PlayerInfo.teamId`); v1 keeps join order, with a "Teams" toggle for team-capable games assigning alternating `teamId`.

### 3.3 Ready check
- NEW lobby messages (additive to `OnlineLobbyMessage`, mirrored in `NearbyLobbyMessage`): `Ready(ready: Boolean)` guest->host,
  `RosterUpdate(seats: List<SeatView>)` host->all (so every guest sees the same grid, with avatars and ready marks),
  and `StartCountdown(seconds)` host->all.
- Host's Start becomes "Start when everyone is ready" (enabled when all humans ready; or "Start anyway" after 30 s
  asks `Rejected`-style to un-readied guests to ready up or be dropped). A guest's `Ready` is only believed when
  the relay-stamped `fromPlayerId` is a seated guest (same trust pattern as `GameStart.isFromHost`).
- In matchmade rooms, `claim` is the ready check (2.3 step 6); no second prompt.

### 3.4 Kicking and spectator handoff
- Kick: host taps a seat -> relay `kick` (section 1.3); on Nearby, host disconnects the endpoint (`connectionsClient.disconnectFromEndpoint`;
  confirm in `NearbyRoster`). The kicked guest sees "The host removed you from the room." (a `Rejected`-like host-stamped reason, localized).
- Spectator handoff: a player who is kicked/leaves mid-game **with the game in `spectatorGames`** may be offered "Keep
  watching" (re-`join` with `spectator:true` is blocked for their retired id: they must use a *new* `p-` id,
  which the join screen already mints per screen instance). A spectator becoming a player mid-game is **not** supported
  in v1 (seat re-assignment mid-hand breaks hidden-info invariants). Between games ("Play again"), the host may seat
  waiting spectators via a fresh `GameStart`; that needs the host to keep the room open and is slice S5's stretch goal (O8).
- `spectatorGames` stays `{"uno"}` until each game delivers redacted state to spectators (its own doc comment says so).
  Wave 5 adds nothing here except the Hold'em/President candidates being listed in the open decisions.

### 3.5 Tests for 3-4 seats
`app/src/test/.../transport/ThreeFourSeatLobbyTest.kt` (parametric over `GameHubSpecs.all` networked ids with
`maxSeats >= 3`): `LobbySeatLimits.forGame` bounds, roster with CPU fill passes `guestSeatIndex` for every human seat,
`cpu-` ids never seatable by a guest, Toy Soccer starts at 2 humans + 2 CPU. Extend `LobbyCapacityTest`,
`ToySportsLobbySeatsTest`, `SlotCarsFishingLobbySeatsTest` (existing patterns).

---------------------------------------------------------------------------------------------------

## 4. Quick-chat (fixed phrases and emotes, no free text)

### 4.1 Scope and safety
- **No free text anywhere.** A message is `phraseId: Int`. The relay validates the id against an allowlist, so even a
  hacked client cannot send text: nothing to moderate, safe for children. Display names remain the only free text in
  the system (see O9).
- Works in Online and Nearby rooms (not pass-and-play, not CPU games). Spectators cannot send; they receive.

### 4.2 Phrase set v1 (stable ids; strings in new `quickchat_strings.xml` and `values-es/quickchat_strings.xml`)

| Id | EN | ES |
|---|---|---|
| 1 | Hello! | Hola! |
| 2 | Good luck! | Buena suerte! |
| 3 | Good game! | Buen juego! |
| 4 | Nice move! | Buena jugada! |
| 5 | Well played! | Bien jugado! |
| 6 | Oops! | Ups! |
| 7 | Thanks! | Gracias! |
| 8 | Take your time | Tomate tu tiempo |
| 9 | One moment, please | Un momento, por favor |
| 10 | Play again? | Jugamos otra vez? |
| 11 | I need a break | Necesito un descanso |
| 12 | Goodbye! | Adios! |
| 101-106 | Emotes: smile, clap, wow, think, heart, wave (vector glyphs in `uikit/QuickChatGlyphs.kt`, shape-distinct, not colour-only) | same glyphs, `contentDescription` localized |

Phrases are looked up by id at display time in the *receiver's* locale, so an EN player and an ES player see each
other's messages in their own language. Unknown ids (a future build's phrase) render nothing and are never announced.
(Spanish strings above need a native-speaker pass before release, per QUALITY-BAR gate 7.)

### 4.3 Transport message type

Online (relay-level, because the relay can validate and rate limit):
```
C->S {"type":"chat","phraseId":4}
S->C {"type":"chat","fromPlayerId":"p-...","phraseId":4}      // stamped from the socket, like 'message'
S->C {"type":"error","code":"chat_rate"}                       // dropped, sender informed
```
Relay: `quickchat.js` exports `VALID_IDS` (1..12, 101..106). Rejects spectators and sockets not in a room. Not stored.

Nearby (no relay): payloads reach both listeners (`NearbyConnectionsTransport.payloadCallback`), and games decode
payloads as JSON game messages, so chat must not look like a game message. Use a 3-byte magic prefix `0x00 'Q' <id>`
(JSON never starts with 0x00) consumed **in the transport** before the game listener is invoked, guest->host, and the host
re-fans it out stamped with the endpoint-derived `fromPlayerId` (guests are connected to the host; confirm the cluster
topology in `NearbyRoster`/`NearbyGameRouter`). The raw lobby listener must also skip these frames.

Common interface (additive, default no-op, same precedent as `onReconnected`), in `MultiplayerTransport.kt`:
```kotlin
fun sendQuickChat(phraseId: Int) {}
fun onQuickChat(listener: (fromPlayerId: String, phraseId: Int) -> Unit) {}
```
`LocalPassAndPlayTransport` keeps the no-op, which is also how the UI knows to hide chat (`val supportsQuickChat: Boolean get() = false`).

### 4.4 Limits and mute
- **Rate limit** (server and client, same numbers): at most 1 per 1.2 s and 5 per 20 s per sender; extras are dropped.
  Client greys the button with a short cooldown and says so to TalkBack. A repeat of the same id within 5 s is dropped.
- **Per-player mute**: in the chat sheet, each other player has a Mute toggle; muted ids are ignored on receive (no
  bubble, no announcement, no sound). Stored in memory per match (`GameContext`-scoped `MuteSet`), because playerIds are
  per-session random and cannot name a person across matches (see 6.3). Plus a persisted global setting
  "Quick chat: on / reactions only / off" in `settings/` (`SettingsViewModel`), default **on** for friend rooms and **reactions only** for matchmade rooms
  (O7). When off, nothing is shown or announced and the Chat button is hidden.
- Sending is never blocked on the game's state, and chat never goes through the game's own `send` path, so it cannot desync a match.

### 4.5 UI, accessibility, motion
- **One place for all 40+ games**: `uikit/QuickChatOverlay.kt` composed once inside `GameChrome` (`uikit/GameChrome.kt`),
  fed by a `QuickChatController` owned by `GameSessionManager` (collects `onQuickChat` for the active transport, exposes
  `StateFlow<List<ChatBubble>>`). Per-game work: none, apart from checking that the bubble does not cover the game's own
  controls on the Fold cover screen (layout test in the emulator pass).
- Chat button: 48 dp minimum, top corner, opens a bottom sheet grid (2 columns, each cell >= 48 dp, large-font safe),
  with TalkBack ordering: phrases, then emotes, then per-player mute.
- **Receive**: a bubble near the sender's seat label (or a top banner when seat position is unknown) for 4 s showing avatar + text/glyph.
  TalkBack: `liveRegion = LiveRegionMode.Polite` with text "Sam says: Good game!" (repo precedent: `BackgammonScreen`,
  `BilliardsScreen`). Never steals focus; dedupe repeated announcements; the "announce chat" toggle is part of the
  setting in 4.4.
- **Reduced motion** (`LocalReducedMotion`, used e.g. in `CheckersMotionPrefs`): no slide/bounce, a plain fade; emotes
  show as a static glyph. Haptic: none for incoming chat. Sound: none (avoids a griefing channel).
- Chat history is not persisted and not shown after the match.

---------------------------------------------------------------------------------------------------

## 5. Room code and matchmaking UI placement (summary of screens)

| Screen | Status | Notes |
|---|---|---|
| `OnlineEntryScreen` | edit | Quick match (primary), Host, Join; server-not-configured state kept. |
| `OnlineHostLobbyScreen` | edit | seat grid, share/copy/lock/kick, ready, CPU fill; shares `OnlineMatchStarter`. |
| `OnlineJoinLobbyScreen` | edit | paste handling, `peek` preview, roster with avatars, shares guest start handling. |
| `QuickMatchScreen` | new | queue states, Cancel, CPU alternative. |
| `uikit/QuickChatOverlay`, `QuickChatSheet` | new | in `GameChrome`. |
| Nearby host/join lobbies | edit | seat grid + CPU fill + ready (shared composables in `ui/club/`). |

All new strings in `online_room_strings.xml`, `quickmatch_strings.xml`, `quickchat_strings.xml` with `values-es` twins (the
repo's per-feature strings-file convention; the current lobby texts like "Room code" are hard-coded English: **GAP vs. gate 7**, fix
in S2 for every string touched).

---------------------------------------------------------------------------------------------------

## 6. Voice chat readiness (design for it, do not build it)

Voice is wave 6 and last. Wave 5 must not paint it into a corner. Leave room for:

1. **Signalling over the relay**: the relay already carries opaque addressed frames. A future `rtc` frame type
   (`{"type":"rtc","toPlayerId","payloadBase64"}`) fits the existing stamp-and-forward model; wave 5 therefore keeps
   "frame type is a closed server-side allowlist" (the `default:` branch in `index.js` already rejects unknown types) and
   puts all new types in modules that register themselves, so adding `rtc` is a one-file change. Media must be peer-to-peer
   (WebRTC) with a TURN fallback the owner would have to host (cost, O10); the relay never sees audio.
2. **Identity for mute/block**: voice makes abuse more serious than fixed phrases. Per-session random `playerId`s cannot be
   blocked across matches. Leave room for an optional install-scoped pseudonymous `clubId` (random UUID created once,
   sent only after the player opts in, never derived from hardware ids). Wave 5 does **not** add it; the avatar/name relay
   fields are designed so one more optional field slots in.
3. **Permissions**: `RECORD_AUDIO` requested only on first tap of a voice toggle, never at install or lobby entry; the app
   must work fully when denied. Microphone foreground-service type is needed only if voice continues in the background:
   do not support that (voice ends when the game screen stops).
4. **Push-to-talk first**: default PTT button (48 dp+), open-mic as an advanced option; mic state always visible (indicator
   plus TalkBack announcement); a mic icon on the seat card while transmitting (shape change, not only colour).
5. **Mute/block UI**: the same per-player mute list used by quick-chat (4.4) becomes the voice mute list. "Mute all" and
   "deafen" are one toggle each in the chat sheet.
6. **No recording, no storage**: audio stays in memory; the relay carries only signalling; privacy policy gets the statement
   before launch. Reporting needs a destination (an owner decision, not a relay feature).
7. **Child safety**: voice should be off by default in matchmade rooms with strangers, and on only in friend rooms by default
   (mirrors 4.4's default). Parental setting hook: `SettingsViewModel` gets a single `voiceAllowed` flag in wave 6.
8. Wave 5 deliverable for this: a short "extension points" section in `server/README.md` and the settings key reserved,
   nothing else.

---------------------------------------------------------------------------------------------------

## 7. Test plan

### 7.1 Server (`server/`, Node, run by `npm test` and `relay-server.yml`)
Follow `identity-test.js`'s rule: no sleeps-and-hope, await the specific frame, always kill children.
- `room-meta-test.js`: `host` with metadata, `peek` shapes (no tokens/ids leaked), `room_full` at `maxSeats` before any `Hello`,
  `lock`, `kick` + retired id cannot rejoin, non-host cannot `lock/kick/started`, lobby TTL reap with env override, code cooldown.
- `ratelimit-test.js`: per-IP join failure budget survives a reconnect (this is the GAP regression), host/queue limits,
  message flood drop + terminate, legit burst unaffected.
- `queue-test.js`: pairs two tickets; four-seat fills; FIFO fairness; bucket window widening with an injected clock
  (make `now()` injectable via env or a test hook so no real 30 s waits); CPU fill after `fillAfterMs`; host = lowest RTT / `canHost`;
  no-show -> `matchAborted` + survivors re-queued with original age; cancel and socket-close both dequeue; claim token single-use,
  bound to playerId and room; expired claim; ticket cap; one ticket per socket; no match leaks into `peek`.
- `quickchat-test.js`: valid id relayed with stamped `fromPlayerId`; invalid/negative/float/string ids dropped; spectator send denied;
  rate limit; chat never reaches the game `message` path.
- Existing `smoke-test.js`/`identity-test.js` must stay green unchanged (additive protocol).
- `package.json` `test` script extended; CI path filter already covers `server/**`.

### 7.2 Kotlin unit tests (JVM, no device)
- `OnlineTransport` wire: new `WireMessage` fields round-trip; unknown frames ignored (old-server compatibility).
- `MatchmakingControllerTest` with a **fake transport** (a fake `OnlineTransport` seam: extract a tiny interface
  `RelayClient` implemented by `OnlineTransport` and by a `FakeRelayClient` the test drives) covering every state transition,
  cancel at each state, background cancel, `matchAborted` re-queue, CPU alternative.
- `OnlineMatchStarterTest`, `ThreeFourSeatLobbyTest` (3.5), `LobbySeatLimitsCpuFillTest`, `RoomCodeParserTest` (paste extraction, banned characters).
- `OnlineRatingStoreTest` (Elo update, bucket thresholds, CPU/abort ignored) and `ClubProfile` untouched.
- `QuickChatControllerTest`: rate limit, mute, setting off, unknown id ignored, locale-resolved text; `NearbyQuickChatFrameTest` (magic prefix is consumed
  before the game listener, and a game payload starting with `{` is never mistaken for chat).
- `HubSpecServerParityTest`: `GameHubSpecs.ONLINE_MAX_SEATS == 8` and the seat allowlist embedded in `server/seat-ranges.json`
  (generated, checked in) matches `GameHubSpecs` for every networked game (fixes the unlinked-constant GAP).
- Extend `OnlineRelayEndToEndTest` for two-guest + chat + kick (check first whether it spawns the real server or a fake; follow its approach).
- Localization: a test that every phrase id in the relay allowlist has EN and ES strings.

### 7.3 Two-emulator end-to-end (slice S9, uses the real relay)
- Two AVDs on different ports (e.g. `-port 5554` and `-port 5556`, same API 34 image, `-no-snapshot`), relay started on the host
  machine; both emulators reach it at `ws://10.0.2.2:8080` (no tunnelling needed). Settings' "Online server" is set via an
  intent extra / debug-only preference, not UI taps. Driver script in `scripts/` (not `app/`), adapting `Z:\Dev\gs-sweep\tools\netlib.py` /
  `devtest.py` (they currently address two real devices by serial; make serials parameters).
- Scenarios: (a) friend room: A hosts, copies code, B joins by deep link `adb shell am start -a android.intent.action.VIEW -d gamesuite://join?...`,
  both start Chess, move, chat; (b) Quick match: both queue Connect Four, auto-match, play 2 moves; (c) kill host app -> guest sees "Host left"; (d) `adb shell svc wifi`
  is not available on emulators: simulate a drop with `adb emulator network` / airplane-mode via `adb shell cmd connectivity airplane-mode enable` and verify
  reconnect within 30 s and the "reconnecting" state; (e) 4-seat: two emulators + two CPU seats in President; (f) TalkBack/large-font/dark/rotation screenshots per QUALITY-BAR gate 8.
- One real-device pass (tablet + Fold, per the quality bar) before the wave merges; the Nearby paths **cannot** be exercised on emulators (no Nearby
  support), so Nearby chat/CPU-fill/ready are verified on the tablet+Fold only.
- Never run two Gradle builds in one checkout (CLAUDE.md); build the APK once and `adb install` it onto both AVDs.

---------------------------------------------------------------------------------------------------

## 8. Rollout: small independently shippable slices (file-level scope)

Order is chosen so each slice is useful alone, the server stays backward compatible (old clients keep working), and each slice
ends with its tests green and a verified launch. Each slice: its own strings files, EN+ES, gate checklist from QUALITY-BAR.

| # | Slice | Depends | Files (new = N) | Ships |
|---|---|---|---|---|
| S0 | Relay hardening: per-IP limits, flood limit, lobby TTL, code cooldown, name sanitizing, `ONLINE_MAX_SEATS` parity test | none | `server/index.js`, `server/ratelimit.js` N, `server/ratelimit-test.js` N, `server/package.json`, `app/.../core/GameHubSpecs.kt` (test only), `app/src/test/.../HubSpecServerParityTest.kt` N | safer relay, nothing visible |
| S1 | Room metadata + `peek/lock/kick/started` + avatar field | S0 | `server/index.js`, `server/rooms-meta.js` N, `server/room-meta-test.js` N, `transport/OnlineTransport.kt` (`WireMessage`, `OnlinePlayer.avatar`) | server + transport ready |
| S2 | Friend-room UX: share/copy/deep link, paste field, real avatars, `peek` preview, lock/kick UI, localized lobby strings | S1 | `ui/OnlineHostLobbyScreen.kt`, `ui/OnlineJoinLobbyScreen.kt`, `ui/OnlineEntryScreen.kt`, `MainActivity.kt` (intent filter + route, integrator-owned), `AndroidManifest.xml`, `ui/club/LobbySeatRow.kt`, `res/values{,-es}/online_room_strings.xml` N, `core/RoomCode.kt` N | Clubhouse-style room codes (shippable milestone M1) |
| S3 | Extract `OnlineMatchStarter` + guest start handling out of the two lobby screens (pure refactor, behaviour identical, tests prove it) | S2 | `core/OnlineMatchStarter.kt` N, the two lobby screens, `OnlineMatchStarterTest` N | refactor only |
| S4 | 3-4 seat audit tests + CPU fill + seat grid + ready check (Online first, Nearby after) | S3 | `transport/NearbyRoster.kt` (`LobbySeatLimits`), `transport/OnlineLobbyProtocol.kt`, `transport/NearbyLobbyProtocol.kt`, `ui/*HostLobbyScreen.kt`, `ui/*JoinLobbyScreen.kt`, `ui/club/LobbySeatGrid.kt` N, `ThreeFourSeatLobbyTest` N, per-game fixes found by the audit (game package owner only) | Toy Soccer/President/Sevens playable online with friends (M2) |
| S5 | Quick-chat (Online relay + Nearby prefix + overlay in GameChrome + settings) | S1 (relay structure); independent of S2-S4 | `server/quickchat.js` N, `server/quickchat-test.js` N, `shared/.../MultiplayerTransport.kt`, `transport/OnlineTransport.kt`, `transport/NearbyConnectionsTransport.kt`, `uikit/GameChrome.kt`, `uikit/QuickChatOverlay.kt` N, `uikit/QuickChatGlyphs.kt` N, `core/QuickChatController.kt` N, `settings/*` (one key), `res/values{,-es}/quickchat_strings.xml` N | chat in every networked game (M3) |
| S6 | Host-left handling (abort cleanly, `awayPlayers` "reconnecting" state) | S3 | `core/GameSessionManager.kt`, `transport/OnlineTransport.kt`, `uikit/GameChrome.kt`, tests | fixes the zombie match GAP now; useful before matchmaking |
| S7 | Matchmaking server | S0, S1 | `server/queue.js` N, `server/queue-test.js` N, `server/seat-ranges.json` N (+ generator script), `server/index.js` (wire `queue/queueCancel/claim`) | server only, dark-launched |
| S8 | Matchmaking client + rating + Quick match button (turn-based list only) | S3, S6, S7 | `core/MatchmakingController.kt` N, `core/OnlineRatingStore.kt` N, `ui/QuickMatchScreen.kt` N, `ui/OnlineEntryScreen.kt`, `transport/OnlineTransport.kt`, `core/GameHub.kt`/`HubMode.quickMatch` flag + per-family spec files, strings, tests (M4) | Quick match |
| S9 | Two-AVD harness + scenario scripts + docs | S2 minimum, grows with each slice | `scripts/e2e-two-emulators.py` N, `scripts/README` section, `server/README.md` protocol reference update | repeatable evidence |
| S10 | Wave-6 extension points (docs and a reserved settings key only) | S5 | `server/README.md`, `docs/roadmap/` note | nothing visible |

Each slice's merge gate: relevant unit tests green (`./gradlew :app:testDebugUnitTest :shared:allTests`, `cd server && npm test`),
a real launch, review clean of P1/P2, and the QUALITY-BAR gate 6/7/5 items it touches. Integrator-owned shared files
(`GameHubSpecs`, `MainActivity`, `GameCatalog`, `GameTheme`, `GameTips`) are edited by the main session only, per the house rules in
`CLUBHOUSE-PARITY.md`. Server and docs slices can run in a separate worktree in parallel with Kotlin slices.

---------------------------------------------------------------------------------------------------

## 9. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Host-authoritative + stranger host = a modified client can cheat in hidden-info games and sees all hands. | No ranked rewards in v1, bucket only; document; revisit with server-side authority or commit-reveal later. Quick match for perfect-information board games first. |
| R2 | The relay is a free/cheap single Node process (`MAX_ROOMS = 500`, in-memory). Queue and rooms vanish on restart; reconnect tokens refuse resuming into a recycled code (already handled). | Fine for friend scale; matchmaking requires an owner-chosen host that does not sleep (Render free tier sleeps: cold start breaks Quick match). Decision O1. |
| R3 | `X-Forwarded-For` spoofing if `TRUST_PROXY` mis-set. | Default off; document per host; tests with and without. |
| R4 | Rate limits tuned too tight for real-time games. | Env-overridable, defaults generous, flood test includes a 60 Hz message stream. |
| R5 | Nearby chat framing could collide with game decoders. | 0x00 prefix test (JSON never starts with 0x00); fuzz test that random game payloads never get consumed. |
| R6 | CPU-fill assumption: some engines may not drive bot seats in networked mode. | S4 audit is the first task and may carve out games. |
| R7 | Display names are free text (the one unmoderated channel) incl. in matchmade rooms visible to children. | O9; minimum is a profanity/URL filter on the client and "reactions only" default in matchmade rooms. |
| R8 | Deep links and share text: custom scheme can be hijacked by another app, https App Links need hosted files. | Link carries only a code and game id, never a token; worst case is a wrong-room join prompt, which `peek` previews first. |
| R9 | Scope: 42 networked games x lobby changes. | Everything is generic in lobbies/GameChrome; per-game edits only where the S4 audit finds a defect. |
| R10 | Existing uncommitted state: wave 3 is uncommitted on `session/wave-3` (148 files, tests unrun per memory notes). | Branch wave-5 work from the merged result; do not stack server/protocol work on an unverified tree. |

---------------------------------------------------------------------------------------------------

## 10. Open decisions for the owner (question, recommended default)

| # | Question | Recommended default |
|---|---|---|
| O1 | Where does the always-on relay run, and who pays? Matchmaking needs a host that does not sleep. | A small always-on instance (Fly.io/own VPS), `wss://`, `TRUST_PROXY=1`; keep the free tier for friend rooms only. |
| O2 | Allow CPU fill in matchmade games, and after how long? | Yes, opt-in per search, 20 s, always labelled "computers" in the UI. |
| O3 | Quick match availability: turn-based only in v1, real-time later with RTT gating? | Yes (list in 2.6). |
| O4 | Skill matching: local self-reported bucket (forgeable, no accounts) or wait for accounts? | Local bucket, no leaderboards, no ranked rewards. |
| O5 | Host loss: abort cleanly (v1) vs build host migration for perfect-information games? | Abort in v1; migration a later spike for board classics only. |
| O6 | Who may host a matchmade game: anyone, or only devices on unmetered networks / not low battery? | Anyone who reports `canHost`; clients set false when on metered data with Data Saver or battery saver. |
| O7 | Chat defaults: on in friend rooms, reactions-only in matchmade? | Yes. |
| O8 | Spectators becoming players between games ("Play again" seating)? | Defer; keep spectators watch-only for wave 5. |
| O9 | Display-name moderation for strangers (filter list, report path)? | Client-side filter and a "Report" action that just hides that player locally (no backend) until a moderation owner exists. |
| O10 | Voice (wave 6): host a TURN server, or friend-rooms-only with STUN? | Decide at wave 6 start; wave 5 only reserves the extension points. |
| O11 | Invite links: custom scheme only, or a domain with https App Links? | Custom scheme now; domain later if the owner has one. |
| O12 | Install-scoped pseudonymous `clubId` for cross-match block lists? | No in wave 5 (privacy); revisit with voice. |
| O13 | Phrase list: approve the 12 phrases + 6 emotes and get a native Spanish review? | Approve as drafted, with the ES review before release. |

---------------------------------------------------------------------------------------------------

## 11. S4 audit results (2026-11-07, measured by `NetworkedSeatAuditTest`, `ThreeFourSeatLobbyTest`)

Method: each engine is built once per device exactly as its screen does (`init(ctx)`, then `startMatch()` on every device,
`PlayMode.ONLINE`, host = seat 0) over `FakeRelay` (an in-memory relay that stamps `fromPlayerId` and never echoes to the sender),
for 3 and 4 seats inside the game's Online seat range, in three table shapes: HUMANS (all human devices), CPU_TAIL (host + 1 guest
+ computers at the end, the shape "fill with computers" produces) and CPU_FIRST (the host device plays seat 0 as a computer and the
only human is the last seat, the only generic way to make an engine reach a computer turn before any human acts; deals are
random, so it retries up to 25 fresh games). Checked: no throw on any device, guests end up with a state, the host drives
computer turns to quiescence (turn-based) or the simulation advances and guests follow it (real-time), no other seat's hidden
cards on a guest (UNO, Hold'em, President, Sevens), and a guest vanishing mid-match leaves the host with its state.

| Game | Online seats | Humans 3/4 | CPU seats driven on host 3/4 | Hidden info on guests | Guest leaves | Lobby computer fill |
|---|---|---|---|---|---|---|
| UNO | 2-8 | ok | ok | no leak | ok | yes |
| Texas Hold'em | 2-6 | ok | ok | no leak | ok | yes |
| President | 3-6 | ok | ok | no leak | ok | yes |
| Sevens | 3-6 | ok | ok | no leak | ok | yes |
| Chinese Checkers | 2-6 | ok | ok | n/a | ok | yes |
| Pig Dice | 2-6 | ok | ok | n/a | ok | yes |
| Ludo, Blackjack, War (Choose-3 variant), Carrom, Curling, Darts, Bowling, Mini-golf, Tank Battle, Shooting Gallery, Matching | 2-4 | ok | ok | n/a | ok | yes |
| Toy Soccer, Slot Cars, Fishing (real-time) | 2-4 | ok | host simulates, guests follow (CPU play quality not measured) | n/a | ok | yes |
| Six-Ball Puzzle | 2-4 | ok | **none**: every device races on its own board, there is no computer seat | n/a | ok | **no** |
| every two-seat game (Chess ... Dominoes, Speed, Toy Tennis/Boxing/Baseball, Billiards, Hanafuda, Word Tiles) | 2-2 | not applicable (a 2-seat table is host + guest) | n/a | n/a | n/a | no (nothing to fill) |

Findings:
- **Every 3+ seat networked game starts, syncs, and (where it has computer seats) drives them on the host**; the only engine
  defect was UNO's (below), so the work was mostly lobby side. `OnlineCpuFill.supportedGames` is exactly the rows
  marked "yes", and `ThreeFourSeatLobbyTest` fails if a new 3+ seat game is added without being audited or carved out.
- **Correction to section 3.1**: Toy Soccer's Online range is **2-4**, not 4-4 (the 4-4 is its pass-and-play "2v2" mode;
  `networkModes()` takes the smallest shared-table minimum). 1v1 online already worked; what was missing was 2v2 with two friends,
  which computer fill now gives (host + 1 guest + 2 computers). President/Sevens (min 3) reach a table with host + guest +
  computer.
- War's default Classic variant has no computer picks (everyone flips for the table), so a computer seat there simply takes
  part in each flip; the audited bot-driving path is the Choose-3 variant.
- **One engine defect found and fixed (UNO)**: `UnoScreen` asks every device's engine to drive a computer seat, and
  `UnoGame.playBotTurn` only guarded the host implicitly, so each guest sent the host a play/draw/colour intent for a seat it
  does not own (the host's seat check dropped them, so no wrong move, but one spurious message per guest per computer turn).
  It now returns early on a guest. The audit's "a guest asked to drive a computer puts nothing on the wire" check fails
  without that line (verified by removing it) and also guards every other engine. The KDoc claiming bots "never fire in
  networked play" was stale and is corrected. Dominoes' engine still refuses computer seats in networked mode, which is fine
  because its Online range is 2-2.
- Not covered by the audit, honestly: UNO at 5-8 seats, teams (`teamId`), computer play quality in the real-time games,
  hidden-information redaction for games other than the four named (blackjack, war, hanafuda are public or 2-seat), and anything on
  a real screen.
- A guest that proposes a `cpu-` player id would have collided with a computer seat (duplicate ids make every guest's roster
  invalid): Online hosts now seat no such guest (it is turned away like overflow), Nearby hosts mint it a fresh id, and
  `guestSeatIndex` never seats a `cpu-` id.

### What S4 built (deviations from the design are listed)
- `LobbySeatLimits.canStart(guests, cpus)`, `maxCpus`, `seatsStillNeeded`; `OnlineCpuFill`; `cpuSeatsFor`; `OnlineHostLobbyState`
  (seating, computer seats, ready check with a 30 s "start anyway", removal); `OnlineMatchStarter.startAsHost(cpuSeats = ...)`;
  `OnlineLobbyMessage.Ready` / `RosterUpdate` (additive; an older build ignores them); `ui/club/LobbySeatGrid`.
- Online host and guest lobbies: seat grid for both sides, fill/clear computers, CPU level (Easy/Medium/Hard/Expert, applied to the
  launch options so the host's engine uses it), ready toggle, remove a guest; Start/Cancel pinned, text in new EN+ES strings.
- Nearby host lobby: seat grid, computer fill, CPU level, localized text, pinned actions.
- **Deviation: Online "remove player"** is a host-stamped lobby `Rejected(kicked)` plus the host dropping that guest from the table, not
  the relay `kick` frame of section 1.3 (the guest leaves on the refusal; a modified client that stays is simply never announced).
  Switch to the relay frame once the Kotlin side of S1 lands.
- **Deviation / left conservative: Nearby ready check and Nearby remove-player were not built.** Neither can be exercised on an emulator
  (no Nearby support), and removing an endpoint changes connection state in a flow that works today. Nearby shows guests as ready.
  Nearby computer fill is pure roster logic (unit-tested) plus the same grid; its on-device behaviour is unverified.
- Computer seat names ("Computer 2") are written in the HOST's language into the roster; guests show what the host wrote.
- Not done in S4: `peek`-based room preview, Lock, share/deep link (S1/S2 work).
