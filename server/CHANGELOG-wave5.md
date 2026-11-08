# Relay wire changes for wave 5 (multiplayer)

Everything here is **additive**. An old client never sends any of it and keeps working unchanged, and an old relay
ignores the extra fields a new client adds (`WireMessage` ignores unknown keys). The existing frames (`host`, `join`,
`message`, `leave`, `hosted`, `joined`, `reconnected`, `playerJoined`, `playerDisconnected`, `playerReconnected`,
`playerLeft`, `message`, `error`) keep their names, fields and exact `message` texts. Design:
`docs/roadmap/WAVE-5-MULTIPLAYER-DESIGN.md`. This file is the contract for the Kotlin client; you should not need to
read the server code.

Frames are one JSON object per WebSocket text frame. `?` marks an optional field. "Host-only" and "stamped" mean the relay
decides from the socket's registered seat, never from anything inside the frame.

## 0. Errors

Every error is `{"type":"error","message":"<english text>","code":"<code>"}`. Clients must branch on `code`, not on
`message`. The pre-wave-5 errors that have no code (malformed JSON, missing `type`, `host requires playerId`,
`join requires roomCode and playerId`, `playerId must be at most 64 characters`, `message requires payloadBase64`,
`Not in a room — host or join first` from `message`, `Unknown message type: x`) are unchanged and still carry no code.
The refusals that existed before now also carry one, with the same `message` text as before:

| code | message | when |
|---|---|---|
| `no_such_room` | `Room not found` | `join`/`peek` to a code that does not exist, **or is a matchmade room** (indistinguishable) |
| `player_in_room` | `playerId already in this room` | duplicate id / held seat without the right token |
| `player_retired` | `playerId can no longer be used in this room` | the id once held a seat here (left, kicked, grace expired) |
| `no_seat_to_resume` | `no seat to resume in this room` | a reconnect token for a seat this room does not have |
| `room_full` | `Room is full` | seated humans already equal the host's declared `maxSeats` (or 8) |
| `room_closed` | `Room is closed to new players` | 64 distinct ids already used |
| `server_full` | `Server is at capacity, try again later` | 500 rooms exist |
| `no_room_code` | `Could not allocate a room code, try again` | code space exhausted (also while codes cool down) |

New codes:

| code | extra fields | meaning |
|---|---|---|
| `rate_limited` | `retryAfterMs` (IP block only) | this IP is blocked from guessing for a while, **or** this socket is over its flood budget (frame dropped, no `retryAfterMs`), **or** a per-IP host/queue budget is used up |
| `too_many_rooms` | | 30 live rooms already created from this IP |
| `bad_metadata` | | `host` metadata malformed (see 1.1) |
| `bad_request` | | a required field is missing or has the wrong type |
| `wrong_game` | | `join` carried a `gameId` that differs from the room's |
| `room_locked` | | the host locked the room |
| `room_started` | | the host marked the game started |
| `not_in_room` | | `lock`/`started`/`kick`/`chat` from a socket that is in no room |
| `not_host` | | `lock`/`started`/`kick` from a guest or spectator |
| `cannot_kick_host` | | |
| `no_such_player` | | `kick` target is not in the room |
| `chat_invalid` | | `phraseId` not in the allowlist |
| `chat_denied` | | a spectator tried to chat |
| `chat_rate` | | over the quick-chat rate limit (message dropped) |
| `unknown_game` | | `queue` for a game id with no entry in `seat-ranges.json` |
| `already_queued` | | this socket already has a ticket |
| `player_in_use` | | another live ticket already uses this `playerId` |
| `already_in_room` | | `queue` from a socket that is hosting/joined a room |
| `queue_cooldown` | `retryAfterMs` | the socket missed a match within the last 30 s |
| `queue_full` | | 2000 tickets already queued |
| `claim_invalid` | | `claim` refused. One answer for every reason (unknown room, wrong id, wrong/used/expired token, other socket) |

Refusals that make the relay count a "failed join" against the per-socket (20) and per-IP budget: every `join`/`claim`
refusal above, and a `peek` that finds no room. See section 6.

## 1. Room metadata

### 1.1 `host` (extended)

```
C->S {"type":"host","playerId":"p-...","displayName":"Sam",
      "avatar"?:"pip|sky|circle","gameId"?:"chess","minSeats"?:2,"maxSeats"?:2,"public"?:false}
S->C {"type":"hosted","roomCode":"AB3D","playerId":"p-...","reconnectToken":"...",
      "gameId"?:"chess","minSeats"?:2,"maxSeats"?:2,"expiresInMs"?:600000}
```
* A `host` without `gameId` is a **legacy host**: `hosted` is byte-for-byte what it was, the room has no declared game,
  `peek` reports `gameId:null, maxSeats:8, minSeats:2`, and the lobby TTL does not apply to it.
* With `gameId` (`^[a-z0-9-]{1,32}$`): `maxSeats` is clamped to 1..8 (default 8), `minSeats` to 1..maxSeats (default 2). The
  clamped values are echoed in `hosted`, with `expiresInMs` = the lobby TTL (default 600000) so the UI can show a countdown.
  Wrong types, a bad `gameId`, or `maxSeats`/`minSeats`/`public` without `gameId` -> `error bad_metadata`.
* `public` is stored and ignored (no public room list exists).
* `avatar`: a club avatar string `^[a-z]{2,12}\|[a-z]{2,12}\|[a-z]{2,12}$`, at most 40 chars. An invalid avatar is silently
  dropped (the client then draws one from the name).
* `displayName` is cleaned by the relay: control characters become spaces, zero-width and bidi-override characters are removed,
  whitespace is collapsed and trimmed, at most 40 **code points**. Empty after cleaning -> the `playerId`.

### 1.2 `join` (extended)

```
C->S {"type":"join","roomCode":"AB3D","playerId":"p-...","displayName":"Sam",
      "avatar"?:"pip|sky|circle","gameId"?:"chess","spectator"?:true,"reconnectToken"?:"..."}
S->C {"type":"joined","roomCode":"AB3D","hostPlayerId":"p-host","reconnectToken":"...",
      "players":[{"playerId":"p-host","displayName":"Sam","avatar"?:"pip|sky|circle"}, ...]}
S->C {"type":"playerJoined","playerId":"p-...","displayName":"Sam","avatar"?:"pip|sky|circle"}
S->C {"type":"reconnected","roomCode":"AB3D","players":[{"playerId","displayName","avatar"?}, ...]}
```
`avatar` is added to roster entries only when valid; no other profile field is ever relayed. Seat checks for a **new real
seat**, in this order (spectators and reconnects skip the ones marked *):
`no_such_room` (also for matchmade rooms) -> `player_retired` -> `player_in_room` -> `no_seat_to_resume` ->
`wrong_game`* (only if the frame has `gameId` and the room declared one) -> `room_started`* -> `room_locked`* -> `room_full`* ->
`room_closed`. A **spectator** `join` is never refused for locked/started/full (the host's `Hello` answer decides).
A **reconnect** (existing seat + correct token) is never refused for locked/started/full.
The `*` refusals count as 1/4 of a failure against the per-IP budget (the joiner already knew the code); the rest count as 1.

### 1.3 `peek`: look at a room without taking a seat

```
C->S {"type":"peek","roomCode":"AB3D"}
S->C {"type":"room","roomCode":"AB3D","gameId":"chess"|null,"seatsTaken":1,"minSeats":2,"maxSeats":2,
      "state":"lobby"|"locked"|"started","hostName":"Sam"|null}
S->C {"type":"error","code":"no_such_room","message":"Room not found"}
```
No seat, no token, **no player id, no roster, no avatar**. `seatsTaken` counts real seats (a seat in its reconnect grace counts).
`state`: `started` beats `locked` beats `lobby`. Matchmade rooms are never visible (`no_such_room`). Cost: a miss is a full failure
against the IP budget, a hit is 1/10 of one. Code is case-insensitive.

### 1.4 Host-only frames

```
C->S {"type":"lock","locked":true|false}
S->C {"type":"roomState","roomCode":"AB3D","state":"lobby|locked|started","seatsTaken":2,"maxSeats":2}   // ack to the host only
C->S {"type":"started"}                                    // room -> started; same ack
C->S {"type":"kick","playerId":"p-..."}                    // a seat or a spectator
S->C {"type":"kicked"}                                     // to the target only
S->C {"type":"playerLeft","playerId":"p-..."}              // to everyone left in the room, including the host
```
* Allowed only for the socket whose seat is the room's host; otherwise `not_host` (guests, spectators) or `not_in_room`.
* `lock` needs a boolean `locked` (`bad_request`). A locked room refuses new players (`room_locked`) but not spectators or reconnects.
* `started` is one-way. Started rooms refuse new players (`room_started`), not spectators or reconnects.
* `kick`: the target gets `kicked`, its socket is unregistered but **left open** (it can still `host`/`join`/`queue` a new
  room; it can no longer speak into the old one: `Not in a room`), and its id is retired for the life of the room, as a seat and as
  a spectator. A new `p-` id can rejoin (use `lock` for a real "no more"). Errors: `cannot_kick_host`, `no_such_player`, `bad_request`.

### 1.5 Lobby TTL and `roomClosed`

A room whose host declared a `gameId`, that is not `started`, and that holds **only the host**, is closed `LOBBY_TTL_MS`
(default 10 min) after its last roster change (creation, a join, a leave). The host gets
`{"type":"roomClosed","reason":"lobby_expired"}`; its socket stays open and may host again. Rooms with guests keep the
30-minute idle rule; legacy rooms keep it too. Closed codes are not reallocated for `CODE_COOLDOWN_MS` (default 60 s).

## 2. Quick-chat

```
C->S {"type":"chat","phraseId":4}
S->C {"type":"chat","fromPlayerId":"p-...","phraseId":4}     // to every other seat and every spectator; never to the sender
S->C {"type":"error","code":"chat_invalid|chat_denied|chat_rate|not_in_room", ...}
```
* Valid ids are exactly `quickchat-allowlist.json`: phrases **1-12**, emotes **101-106** (design doc 4.2). `phraseId` must be a
  JSON integer in that set; strings, floats, null, booleans, arrays, negatives and anything else -> `chat_invalid`. There is no text field.
* Extra fields in the frame (`toPlayerId`, `payloadBase64`, `fromPlayerId`, `text`) are ignored; the relayed frame has exactly
  `type`, `fromPlayerId` (stamped), `phraseId`.
* Spectators cannot send (`chat_denied`) but receive. Not stored. Does not count as room activity.
* Limits per sender seat: at least 1.2 s between messages, at most 5 in 20 s, the same id not twice within 5 s; violations are dropped
  with `chat_rate` and do not extend the wait. Rejected-invalid frames cost no budget.
* Chat never produces a `message` frame and `message` payloads are never inspected, so chat cannot reach or desync a game.

## 3. Matchmaking

The queue is per `gameId | optionsKey | maxSeats`. Only players who ask for the same game, the same options and the same table
size meet. The relay needs `seat-ranges.json` (generated, see README) to know which games exist and their Online seat range.

```
C->S {"type":"queue","playerId":"p-...","displayName":"Sam","avatar"?:"pip|sky|circle",
      "gameId":"chess","optionsKey"?:"std","minSeats"?:2,"maxSeats"?:2,
      "bucket"?:1,"fillAfterMs"?:20000|null,"canHost"?:true}
S->C {"type":"queued","ticketId":"t-...","estimatedWaitMs":null,"queueSize":null|N,"requeued"?:true}
S->C {"type":"queueUpdate","waitedMs":15000,"widened":true}          // every 5 s while waiting
C->S {"type":"queueCancel"}
S->C {"type":"queueCancelled"}                                         // always, idempotent
S->C {"type":"queueExpired"}                                           // ticket reached 3 minutes
S->C {"type":"matchFound","roomCode":"K7MQ2X9R","hostPlayerId":"p-...","claimToken":"...",
      "seats":[{"playerId","displayName","avatar"?}, ...],"cpuSeats":0,"claimWithinMs":15000}
C->S {"type":"claim","roomCode":"K7MQ2X9R","playerId":"p-...","claimToken":"...","displayName"?:"Sam","avatar"?:"..."}
S->C {"type":"hosted"|"joined", ... ,"matchmade":true,"cpuSeats":0,"seats":[...]}
S->C {"type":"matchReady","roomCode":"K7MQ2X9R"}                       // to every seated player once the last claim is in
S->C {"type":"matchAborted","reason":"no_show|host_failed","roomCode":"K7MQ2X9R","requeued":true|false}
```

Field rules (`queue`): `playerId` required, at most 64 chars. `gameId` must be in `seat-ranges.json` (else `unknown_game`).
`optionsKey` `^[A-Za-z0-9._+-]{1,32}$`, default `"std"`. `minSeats`/`maxSeats` are a *request*, clamped into the game's
generated Online range (a forged `maxSeats:4` for chess becomes 2). `bucket` integer 0..2 (default 1): Newcomer/Regular/Veteran.
`fillAfterMs`: absent/`null` = do not accept computer players; otherwise clamped to 5000..120000. `canHost` default true.
Wrong types -> `bad_request`.

Rules:
* One ticket per socket (`already_queued`) and per `playerId` (`player_in_use`). A socket in a room cannot queue
  (`already_in_room`). `host`, `join` and `leave` from a queued socket drop its ticket first.
* `queued.queueSize` is the number waiting in this queue **only when >= 3**, else `null`. `estimatedWaitMs` is always `null`.
* **Matching** (oldest ticket first, FIFO): a table is a full set of `maxSeats` humans. Skill window computed from the
  OLDEST ticket's wait: under 15 s same bucket only, 15-30 s +-1 bucket, over 30 s any. `queueUpdate.widened` is true once
  the window is wider than 0 (wait >= 15 s). A newcomer never worsens an old ticket's wait.
* **CPU fill**: when the oldest ticket has waited its own `fillAfterMs`, a group of >= 2 humans who ALL sent `fillAfterMs`
  is released with `cpuSeats = maxSeats - humans`. A lone human is never matched. Players who did not opt in keep waiting for
  humans. A 2-seat game never has computers. `seats` lists only the humans.
* **Host** = the ticket with the lowest relay-measured RTT among `canHost:true` (ties: older ticket; unmeasured RTT loses).
  If nobody in a group can host, no match forms. `matchFound.hostPlayerId` names it; `seats[0]` is the host.
* **Claim** (the ready check): within `claimWithinMs` (15 s) each player sends `claim` **from the same socket that queued**
  with its own `claimToken`. The claim token is 192-bit, bound to `{roomCode, playerId, socket}`, single use, and expires with
  the match. A failed claim does not burn it. The reply is the same `hosted` (host) or `joined` (guest) a normal host/join
  gets, with its own `reconnectToken`, plus `matchmade:true`, `cpuSeats` and `seats`; from then on it is an ordinary room
  (message relay, stamping, reconnect with the token, chat, host-only frames; `peek` still never shows it; `started` is already
  true, `locked` is true).
* Claim ordering is free: a guest may claim before the host. A guest's `joined.players` only lists seats claimed so far
  (the host is named by `hostPlayerId`). A host that claims after guests receives `hosted` followed by one `playerJoined` per
  already-seated guest, so it sees the same frames as in a friend room. Everyone seated gets `playerJoined` for later claims
  and `matchReady` when the last seat is taken. A host client auto-starts on `matchReady`.
* **No-show**: when `claimWithinMs` passes with seats unclaimed, or a participant cancels or its socket closes before
  `matchReady`, the reserved room is discarded and everybody gets `matchAborted`: the ones who caused it with `requeued:false`
  (and, for a no-show by timeout, a 30 s queue cooldown on that socket: `queue_cooldown`), the others with `requeued:true`
  followed by a fresh `queued` (`requeued:true`) - they keep their **original** place in line. `reason` is `host_failed` when
  the host seat was the one missing, else `no_show`.
* The room id of a match is 8 characters (`^[A-HJ-NP-Z2-9]{8}$`), never shown to users. Matches never appear in `peek`, and
  `join` by that code answers `no_such_room`; the only way in is `claim`, except that an existing matchmade seat may reconnect
  with `join` + its `reconnectToken` like any other seat.
* Caps: 2000 tickets, 100 pending matches, tickets expire at 3 minutes, 6 `queue` frames/min/IP.

## 4. Operations

* `GET /health` (and `/healthz`) -> `200 {"status":"ok","uptimeSec":N,"rooms":N,"tickets":N,"queued":N,"pendingMatches":N,"queues":N}`.
  Counts only. Any other HTTP path is a 404; WebSocket upgrades work on any path as before.
* Environment variables (all optional): see `README.md` ("Configuration").

## 5. Test-only control frames

With `ENABLE_TEST_CONTROL=1` (never set it in production; without it these frames are `Unknown message type`):
`{"type":"__test","op":"advanceClock","ms":N}` moves the relay's injectable clock and runs every time-driven job once;
`{"op":"setRtt","ms":N|null}` overrides this socket's measured RTT; `{"op":"tick"}`; `{"op":"state"}`. Each is answered
`{"type":"__test","op":...,"ok":true,"now","rooms","tickets","queued","pendingMatches","queues"}`.

## 6. Rate limits (all env-overridable, defaults generous)

| budget | default | on exceed |
|---|---|---|
| refused joins/claims + peek misses per IP (peek hit = 0.1, soft refusals = 0.25) | 10 per 60 s | socket terminated; the IP is blocked 60 s: `join`/`peek`/`claim`/`host`/`queue` from ANY socket of that IP get `rate_limited` + `retryAfterMs`, before any room lookup (no oracle) |
| refused joins per socket | 20 (unchanged) | socket terminated |
| `host` per IP | 6 per 60 s | `rate_limited` |
| live rooms created per IP | 30 | `too_many_rooms` |
| `queue` per IP | 6 per 60 s | `rate_limited` |
| frames per socket | 240/s sustained, burst 480 | dropped, one `rate_limited` per strike |
| bytes per socket | 1 MiB/s, burst 2 MiB | same |
| flood strikes | 3 within 10 s (a strike at most once a second) | socket terminated (its seat gets the normal reconnect grace) |

A sender that fans a 60 Hz state stream out to 3 guests as addressed frames sends 180 frames/s, which is inside the budget.
Loopback (127.0.0.1, ::1) is exempt from the per-IP budgets unless `RATE_LIMIT_LOOPBACK=1`. The client IP is the socket
address, or with `TRUST_PROXY=N` the Nth address from the right of `X-Forwarded-For` (never anything the client could have written).

## 7. Upgrading

Additive: deploy the relay any time; old clients are unaffected (the "ship the app first, then the relay" rule in README is
about the per-seat reconnect token from before wave 5, not about this). A new client talking to an old relay gets
`Unknown message type` for `peek`/`lock`/`kick`/`started`/`chat`/`queue`/`claim`; clients should treat that error (no `code`) on
those frames as "this server is too old" and fall back to the wave-4 flow.
