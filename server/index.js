// GameSuite relay server (roadmap item 12 — OnlineTransport's backend).
//
// This is deliberately a "dumb" relay, not a game server: it only tracks
// room membership and forwards opaque payloads between the players in a
// room, exactly mirroring MultiplayerTransport's own send()/
// onMessageReceived(fromPlayerId, payload) contract on the Android side
// (see app/src/main/java/com/gamesuite/transport/MultiplayerTransport.kt).
// It never parses or validates game state — every game in this app is
// host-authoritative (the same design NearbyConnectionsTransport already
// uses for local ad-hoc play), so trusting the relay with game logic was
// never the plan.
//
// Wire protocol: one JSON object per WebSocket text frame.
//
// Client -> Server:
//   {"type":"host","playerId":"p1","displayName":"Alex"}
//   {"type":"join","roomCode":"AB3D","playerId":"p2","displayName":"Sam"}
//   {"type":"join","roomCode":"AB3D","playerId":"p2","displayName":"Sam","reconnectToken":"..."}  (a reconnect, see below)
//   {"type":"join","roomCode":"AB3D","playerId":"p3","displayName":"Casey","spectator":true}
//   {"type":"message","toPlayerId":"p2"|null,"payloadBase64":"..."}
//   {"type":"leave"}
//
// Server -> Client:
//   {"type":"hosted","roomCode":"AB3D","playerId":"p1","reconnectToken":"..."}
//   {"type":"joined","roomCode":"AB3D","hostPlayerId":"p1","reconnectToken":"...","players":[{"playerId":"p1","displayName":"Alex"}, ...]}
//   {"type":"reconnected","roomCode":"AB3D","players":[...]}
//   {"type":"playerJoined","playerId":"p2","displayName":"Sam"}
//   {"type":"playerDisconnected","playerId":"p2"}
//   {"type":"playerReconnected","playerId":"p2"}
//   {"type":"playerLeft","playerId":"p2"}
//   {"type":"message","fromPlayerId":"p1","payloadBase64":"..."}
//   {"type":"error","message":"..."}
//
// toPlayerId == null in a "message" means broadcast to every other player
// in the room — the same null-means-broadcast convention MultiplayerTransport
// itself already uses.
//
// Identity: 'fromPlayerId' on every relayed message is stamped here from the seat the SENDING
// SOCKET registered when it hosted/joined — never from anything inside the frame — and games
// are meant to build on that (an engine checks an intent against the seat it acts for; a guest
// should only accept game state from the host's playerId).
// That only holds if nobody can take over someone else's seat, and playerIds are NOT secret (the
// host's is in every guest's roster), so a seat is protected by two things instead:
//   1. A per-seat reconnectToken, generated here (crypto.randomBytes) and sent only to the socket
//      that took the seat ('hosted' / 'joined'). Re-taking a seat mid-grace-period requires it.
//   2. A seat that has been vacated (explicit leave, or grace period expired) is retired for the
//      lifetime of the room: its playerId can never be claimed again, as a seat OR as a
//      spectator (a spectator with a retired id would be sent that seat's un-redacted hand).
// A 'join' with only a public playerId never succeeds against an existing or retired seat.
// A 'join' that presents a reconnectToken only ever RESUMES a seat: if this room has no such seat it is refused
// instead of seated fresh, so a client resuming into a room that reuses its code (relay restart, idle reap) is not
// handed to whoever re-hosted that code.
// A socket is also bound to the room OBJECT it registered in (see registeredRoom()), not just its code, so a
// socket left over from a room the idle sweep reaped stays inert if that code is later reused by a new room.
//
// Reconnect: a socket that closes unexpectedly (network blip, backgrounded app) does NOT
// immediately vacate its seat. Its seat is held for RECONNECT_GRACE_MS — other members are
// told 'playerDisconnected' (not 'playerLeft') — and a 'join' with the SAME roomCode+playerId
// AND the seat's reconnectToken within that window is treated as a reconnect: the new socket is
// swapped in, the sender gets 'reconnected' (like 'joined', but signals a resume) instead of an
// "already in this room" error, and other members get 'playerReconnected'. Only past the grace
// window (or an explicit {"type":"leave"}) does the seat actually vacate ('playerLeft'). On the
// Android side, a client that receives 'reconnected' should re-request full game state exactly
// like a fresh join does (see UnoGame.init()'s RequestState send /
// MultiplayerTransport.onReconnected) — messages may have been missed while disconnected.
//
// Spectator: "join" with spectator:true gets a read-only seat that never counts against
// MAX_PLAYERS_PER_ROOM and is never included in playerList() (the game-relevant roster a
// GameModule builds its GameContext.players from) — but IS included in every broadcast, so a
// spectator's client can render the live game state exactly like a real player would, using the
// same per-recipient hand-redaction each game already applies to any non-owned seat. There is no
// reconnect grace period for spectators (dropping just ends the watch).

const crypto = require('crypto')
const { WebSocketServer } = require('ws')

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 8080
const ROOM_CODE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789' // no 0/O/1/I — avoids read-aloud/typo ambiguity
// ROOM_CODE_LENGTH, ROOM_IDLE_TIMEOUT_MS and ROOM_SWEEP_INTERVAL_MS can be overridden (like RECONNECT_GRACE_MS_OVERRIDE
// below) so identity-test.js can reap a room and force its code to be reused without waiting half an hour.
const ROOM_CODE_LENGTH = process.env.ROOM_CODE_LENGTH_OVERRIDE ? parseInt(process.env.ROOM_CODE_LENGTH_OVERRIDE, 10) : 4
const MAX_PLAYERS_PER_ROOM = 8 // generous headroom above any current game's maxPlayers (UNO teams tops out at 4)
const MAX_ROOMS = 500 // this is a free/cheap-tier relay, not a scaled backend — cap total memory rather than fall over
const ROOM_CODE_ALLOC_ATTEMPTS = 50 // bound the collision-retry loop so a near-full code space can't spin forever
const MAX_PAYLOAD_BYTES = 64 * 1024 // ws defaults to 100MB/frame; a room-relay message is never legitimately that big
const MAX_DISPLAY_NAME_LENGTH = 40 // truncate rather than reject — a display name is cosmetic, not worth erroring over
const MAX_PLAYER_ID_LENGTH = 64 // the app mints "p-" + a UUID (38 chars); the cap bounds what a room remembers about retired seats
const MAX_SEAT_IDS_PER_ROOM = 64 // distinct playerIds a room will ever seat (see retired seats, above) — fail closed, don't grow forever
const RECONNECT_TOKEN_BYTES = 24 // 192 bits from crypto.randomBytes
const RETIRED_SEAT_MESSAGE = 'playerId can no longer be used in this room'
const NO_SUCH_SEAT_MESSAGE = 'no seat to resume in this room'
const JOIN_FAILURE_LIMIT = 20 // disconnect a socket that's clearly brute-forcing room codes or playerIds
const HEARTBEAT_INTERVAL_MS = 30 * 1000 // standard ws ping/pong liveness check — reclaims half-open sockets promptly
const ROOM_IDLE_TIMEOUT_MS = process.env.ROOM_IDLE_TIMEOUT_MS_OVERRIDE ? parseInt(process.env.ROOM_IDLE_TIMEOUT_MS_OVERRIDE, 10) : 30 * 60 * 1000 // reap rooms nobody's sent traffic in for a long time (abandoned/zombie hosts)
const ROOM_SWEEP_INTERVAL_MS = process.env.ROOM_SWEEP_INTERVAL_MS_OVERRIDE ? parseInt(process.env.ROOM_SWEEP_INTERVAL_MS_OVERRIDE, 10) : 5 * 60 * 1000
// Overridable so smoke-test.js doesn't have to actually wait 30s to test grace-period expiry.
const RECONNECT_GRACE_MS = process.env.RECONNECT_GRACE_MS_OVERRIDE
  ? parseInt(process.env.RECONNECT_GRACE_MS_OVERRIDE, 10)
  : 30 * 1000 // how long a dropped player's seat is held open for them to reconnect

/** roomCode -> { members: Map<playerId, {ws: WebSocket|null, displayName, disconnectTimer, reconnectToken}>,
 *               spectators: Map<playerId, {ws: WebSocket, displayName}>, lastActivity: number,
 *               hostPlayerId: string, usedSeatIds: Set<playerId> }
 *  A member's ws is null exactly while its seat is held open mid-reconnect-grace-period.
 *  usedSeatIds holds every playerId that has ever held a real seat in the room — the ones no longer
 *  in `members` are the retired seats nobody may claim again. */
const rooms = new Map()
/** WebSocket -> { roomCode, playerId, displayName, spectator, room } — for cleanup on close/error without a linear scan of every room.
 *  `room` is the room object the socket registered in; see registeredRoom(). */
const socketMeta = new WeakMap()
/** WebSocket -> count of consecutive failed 'join' attempts, for basic abuse rate-limiting. */
const joinFailureCounts = new WeakMap()

function log(...args) {
  console.log(new Date().toISOString(), ...args)
}

function randomRoomCode() {
  let code = ''
  for (let i = 0; i < ROOM_CODE_LENGTH; i++) {
    code += ROOM_CODE_CHARS[Math.floor(Math.random() * ROOM_CODE_CHARS.length)]
  }
  return code
}

/** Returns an unused room code, or null if the code space looks exhausted (caller should surface an error, not hang). */
function freshRoomCode() {
  for (let attempt = 0; attempt < ROOM_CODE_ALLOC_ATTEMPTS; attempt++) {
    const code = randomRoomCode()
    if (!rooms.has(code)) return code
  }
  return null
}

/** Truncates an incoming displayName to a sane length before it's ever stored or broadcast to other players. */
function sanitizeDisplayName(rawDisplayName, fallback) {
  if (typeof rawDisplayName !== 'string' || !rawDisplayName) return fallback
  return rawDisplayName.length > MAX_DISPLAY_NAME_LENGTH
    ? rawDisplayName.slice(0, MAX_DISPLAY_NAME_LENGTH)
    : rawDisplayName
}

function newReconnectToken() {
  return crypto.randomBytes(RECONNECT_TOKEN_BYTES).toString('base64url')
}

/** Constant-time check of a client-supplied reconnectToken against a seat's real one. Anything that
 *  isn't a string of exactly the right length (missing, a number, an object, ...) simply fails. */
function tokensMatch(expected, provided) {
  if (typeof expected !== 'string' || typeof provided !== 'string') return false
  const a = Buffer.from(expected)
  const b = Buffer.from(provided)
  return a.length === b.length && crypto.timingSafeEqual(a, b)
}

/** The room a socket registered in — but only while that is still the SAME room object, not merely a room
 *  with the same code. The idle sweep can reap a room whose sockets are still connected, and its code can
 *  then be handed to a brand-new room; going by the code alone, such a stale socket could speak into that
 *  room (stamped with the id it once registered under) or vacate a same-named seat there. Returns undefined
 *  for a stale socket, which is then as inert as it already was while its room simply did not exist. */
function registeredRoom(meta) {
  const room = rooms.get(meta.roomCode)
  return room !== undefined && room === meta.room ? room : undefined
}

function send(ws, obj) {
  if (ws.readyState !== ws.OPEN) return
  ws.send(JSON.stringify(obj))
}

/** Delivers to every connected (non-null-ws) member except [excludePlayerId], AND every
 *  spectator (spectators are never excluded — they never send anything a broadcast would be
 *  echoing back to them). A member mid-reconnect-grace-period (ws === null) is silently
 *  skipped, same as if they simply weren't there — they'll catch up via RequestState once
 *  they reconnect. */
function broadcastToRoom(roomCode, obj, excludePlayerId) {
  const room = rooms.get(roomCode)
  if (!room) return
  for (const [playerId, member] of room.members) {
    if (playerId === excludePlayerId) continue
    if (member.ws) send(member.ws, obj)
  }
  for (const spectator of room.spectators.values()) {
    send(spectator.ws, obj)
  }
}

/** The game-relevant roster — real seats only, never spectators (a GameModule's
 *  GameContext.players should never include a read-only watcher as a "player"). */
function playerList(room) {
  return Array.from(room.members.entries()).map(([playerId, member]) => ({
    playerId,
    displayName: member.displayName,
  }))
}

/** A deliberate departure — re-hosting, re-joining a different room, or an explicit
 *  {"type":"leave"} — skips the reconnect grace period entirely and vacates the seat now. */
function leaveImmediately(ws) {
  const meta = socketMeta.get(ws)
  if (!meta) return
  socketMeta.delete(ws)
  const room = registeredRoom(meta)
  if (!room) return
  if (meta.spectator) {
    room.spectators.delete(meta.playerId)
    log(`leave: spectator ${meta.playerId} left room ${meta.roomCode}`)
    if (room.members.size === 0 && room.spectators.size === 0) rooms.delete(meta.roomCode)
    return
  }
  const member = room.members.get(meta.playerId)
  if (member?.disconnectTimer) clearTimeout(member.disconnectTimer)
  finalizeLeave(meta.roomCode, meta.playerId, room)
}

/** The real cleanup for a seat that's actually vacating (grace period expired, or an
 *  immediate/explicit leave) — as opposed to [handleSocketGone], which only holds the seat
 *  open and waits. */
function finalizeLeave(roomCode, playerId, expectedRoom) {
  const room = rooms.get(roomCode)
  if (!room || room !== expectedRoom) return // the room this seat belonged to is gone (its code may already name a new room)
  if (!room.members.has(playerId)) return
  room.members.delete(playerId)
  broadcastToRoom(roomCode, { type: 'playerLeft', playerId })
  if (room.members.size === 0 && room.spectators.size === 0) rooms.delete(roomCode)
  log(`leave: ${playerId} left room ${roomCode}`)
}

/** Fired on an unexpected socket close/error (network blip, backgrounded app, crash) — as
 *  opposed to an explicit {"type":"leave"}. A real player's seat is held open for
 *  RECONNECT_GRACE_MS (see the header comment) rather than vacated immediately; a spectator
 *  (no reconnect story) is just removed. */
function handleSocketGone(ws) {
  const meta = socketMeta.get(ws)
  if (!meta) return
  socketMeta.delete(ws)
  const room = registeredRoom(meta)
  if (!room) return
  if (meta.spectator) {
    room.spectators.delete(meta.playerId)
    log(`disconnect: spectator ${meta.playerId} left room ${meta.roomCode}`)
    if (room.members.size === 0 && room.spectators.size === 0) rooms.delete(meta.roomCode)
    return
  }
  const member = room.members.get(meta.playerId)
  // Guard: if this member's ws isn't the one that just closed, a newer socket already
  // reconnected in its place (e.g. a fast reconnect racing this close event) — nothing to do.
  if (!member || member.ws !== ws) return
  member.ws = null
  log(`disconnect: ${meta.playerId} dropped from room ${meta.roomCode}, holding seat for ${RECONNECT_GRACE_MS / 1000}s`)
  broadcastToRoom(meta.roomCode, { type: 'playerDisconnected', playerId: meta.playerId })
  member.disconnectTimer = setTimeout(() => finalizeLeave(meta.roomCode, meta.playerId, room), RECONNECT_GRACE_MS)
}

/**
 * Counts a failed 'join' (unknown room, taken or retired playerId, wrong/missing/unknown reconnect
 * token, full or closed room; not a malformed one) against
 * this socket and disconnects it once the count crosses JOIN_FAILURE_LIMIT —
 * cheap protection against a client hammering the server with guesses.
 * Successful joins never touch this counter.
 */
function recordJoinFailure(ws) {
  const count = (joinFailureCounts.get(ws) ?? 0) + 1
  joinFailureCounts.set(ws, count)
  if (count >= JOIN_FAILURE_LIMIT) {
    log(`error: terminating socket after ${count} failed join attempts`)
    ws.terminate()
  }
}

const wss = new WebSocketServer({ port: PORT, maxPayload: MAX_PAYLOAD_BYTES })

wss.on('connection', (ws) => {
  log('connect')
  ws.isAlive = true
  ws.on('pong', () => {
    ws.isAlive = true
  })

  ws.on('message', (raw) => {
    let msg
    try {
      msg = JSON.parse(raw.toString())
    } catch {
      log('error: malformed (non-JSON) message received')
      send(ws, { type: 'error', message: 'Malformed JSON' })
      return
    }

    // JSON.parse succeeding doesn't mean we got an object: "null", "42" and
    // "\"hi\"" are all valid JSON that parse to a non-object. Reject anything
    // that isn't a plain object with a string "type" before switching on it —
    // otherwise `msg.type` throws (msg === null) or is simply undefined,
    // either of which used to crash or fall through unpredictably.
    if (typeof msg !== 'object' || msg === null || Array.isArray(msg) || typeof msg.type !== 'string') {
      log('error: message missing a valid "type"')
      send(ws, { type: 'error', message: 'Message must be a JSON object with a string "type"' })
      return
    }

    switch (msg.type) {
      case 'host': {
        leaveImmediately(ws) // a socket re-hosting must first leave whatever room it already belonged to
        if (typeof msg.playerId !== 'string' || !msg.playerId) {
          send(ws, { type: 'error', message: 'host requires playerId' })
          return
        }
        if (msg.playerId.length > MAX_PLAYER_ID_LENGTH) {
          send(ws, { type: 'error', message: `playerId must be at most ${MAX_PLAYER_ID_LENGTH} characters` })
          return
        }
        if (rooms.size >= MAX_ROOMS) {
          log(`host: rejected ${msg.playerId} — room cap (${MAX_ROOMS}) reached`)
          send(ws, { type: 'error', message: 'Server is at capacity, try again later' })
          return
        }
        const roomCode = freshRoomCode()
        if (!roomCode) {
          log('host: could not allocate a free room code')
          send(ws, { type: 'error', message: 'Could not allocate a room code, try again' })
          return
        }
        const displayName = sanitizeDisplayName(msg.displayName, msg.playerId)
        const reconnectToken = newReconnectToken()
        const newRoom = {
          members: new Map([[msg.playerId, { ws, displayName, disconnectTimer: null, reconnectToken }]]),
          spectators: new Map(),
          lastActivity: Date.now(),
          hostPlayerId: msg.playerId,
          usedSeatIds: new Set([msg.playerId]),
        }
        rooms.set(roomCode, newRoom)
        socketMeta.set(ws, { roomCode, playerId: msg.playerId, displayName, spectator: false, room: newRoom })
        log(`host: room ${roomCode} created by ${msg.playerId}`)
        send(ws, { type: 'hosted', roomCode, playerId: msg.playerId, reconnectToken })
        break
      }

      case 'join': {
        leaveImmediately(ws) // a socket re-joining must first leave whatever room it already belonged to
        if (typeof msg.roomCode !== 'string' || typeof msg.playerId !== 'string' || !msg.playerId) {
          send(ws, { type: 'error', message: 'join requires roomCode and playerId' })
          return
        }
        if (msg.playerId.length > MAX_PLAYER_ID_LENGTH) {
          send(ws, { type: 'error', message: `playerId must be at most ${MAX_PLAYER_ID_LENGTH} characters` })
          return
        }
        const roomCode = msg.roomCode.toUpperCase()
        const room = rooms.get(roomCode)
        if (!room) {
          send(ws, { type: 'error', message: 'Room not found' })
          recordJoinFailure(ws)
          return
        }

        if (msg.spectator === true) {
          // Read-only seat: never counts against MAX_PLAYERS_PER_ROOM, never appears in
          // playerList() (the game-relevant roster), but does receive every broadcast — see
          // the header comment. A spectator playerId colliding with a real seat or another
          // spectator is still rejected, same spirit as the real-seat duplicate check below —
          // and so is one that belonged to a real seat that has since been vacated: the host
          // addresses each seat's un-redacted hand to that seat's playerId, so a spectator
          // holding a retired seat's id would be handed that hand.
          if (room.members.has(msg.playerId) || room.spectators.has(msg.playerId)) {
            send(ws, { type: 'error', message: 'playerId already in this room' })
            recordJoinFailure(ws)
            return
          }
          if (room.usedSeatIds.has(msg.playerId)) {
            send(ws, { type: 'error', message: RETIRED_SEAT_MESSAGE })
            recordJoinFailure(ws)
            return
          }
          const spectatorName = sanitizeDisplayName(msg.displayName, msg.playerId)
          room.spectators.set(msg.playerId, { ws, displayName: spectatorName })
          socketMeta.set(ws, { roomCode, playerId: msg.playerId, displayName: spectatorName, spectator: true, room })
          log(`join: ${msg.playerId} is spectating room ${roomCode}`)
          send(ws, { type: 'joined', roomCode, hostPlayerId: room.hostPlayerId, players: playerList(room) })
          break
        }

        const existingMember = room.members.get(msg.playerId)
        if (existingMember) {
          // The seat is taken. It can only be re-taken by its own owner, mid-grace-period, and
          // the owner proves it with the seat's reconnectToken — the playerId alone is public.
          // Everything else (a live socket already holds it, or the token is missing/wrong) gets
          // the same answer, so a probe learns nothing about whether a seat is in its grace period.
          if (existingMember.ws !== null || !tokensMatch(existingMember.reconnectToken, msg.reconnectToken)) {
            send(ws, { type: 'error', message: 'playerId already in this room' })
            recordJoinFailure(ws)
            return
          }
        } else {
          if (room.usedSeatIds.has(msg.playerId)) {
            // Held a seat here before and was vacated (left, or its grace period ran out): retired.
            send(ws, { type: 'error', message: RETIRED_SEAT_MESSAGE })
            recordJoinFailure(ws)
            return
          }
          if (room.spectators.has(msg.playerId)) {
            send(ws, { type: 'error', message: 'playerId already in this room' })
            recordJoinFailure(ws)
            return
          }
          if (msg.reconnectToken !== undefined && msg.reconnectToken !== null) {
            // A first join never carries a token; this is somebody trying to RESUME a seat this room
            // doesn't have. That is what an honest client looks like after the relay restarted or reaped
            // its room and a stranger re-hosted the same code, and seating it fresh would hand the client
            // to that stranger's room as if it were its own. Refuse, so the client keeps looking for its.
            send(ws, { type: 'error', message: NO_SUCH_SEAT_MESSAGE })
            recordJoinFailure(ws)
            return
          }
          if (room.members.size >= MAX_PLAYERS_PER_ROOM) {
            send(ws, { type: 'error', message: 'Room is full' })
            recordJoinFailure(ws)
            return
          }
          if (room.usedSeatIds.size >= MAX_SEAT_IDS_PER_ROOM) {
            send(ws, { type: 'error', message: 'Room is closed to new players' })
            recordJoinFailure(ws)
            return
          }
        }

        const displayName = sanitizeDisplayName(msg.displayName, existingMember?.displayName ?? msg.playerId)
        room.lastActivity = Date.now()

        if (existingMember) {
          // Reconnect: this seat was held open after a drop (see handleSocketGone) — swap in
          // the new socket instead of treating this as a brand-new join.
          clearTimeout(existingMember.disconnectTimer)
          existingMember.disconnectTimer = null
          existingMember.ws = ws
          existingMember.displayName = displayName
          socketMeta.set(ws, { roomCode, playerId: msg.playerId, displayName, spectator: false, room })
          log(`reconnect: ${msg.playerId} rejoined room ${roomCode}`)
          send(ws, { type: 'reconnected', roomCode, players: playerList(room).filter((p) => p.playerId !== msg.playerId) })
          broadcastToRoom(roomCode, { type: 'playerReconnected', playerId: msg.playerId }, msg.playerId)
        } else {
          const existingPlayers = playerList(room) // snapshot BEFORE adding the joiner
          const reconnectToken = newReconnectToken()
          room.members.set(msg.playerId, { ws, displayName, disconnectTimer: null, reconnectToken })
          room.usedSeatIds.add(msg.playerId)
          socketMeta.set(ws, { roomCode, playerId: msg.playerId, displayName, spectator: false, room })
          log(`join: ${msg.playerId} joined room ${roomCode}`)
          send(ws, { type: 'joined', roomCode, hostPlayerId: room.hostPlayerId, reconnectToken, players: existingPlayers })
          broadcastToRoom(roomCode, { type: 'playerJoined', playerId: msg.playerId, displayName }, msg.playerId)
        }
        break
      }

      case 'message': {
        const meta = socketMeta.get(ws)
        if (!meta) {
          send(ws, { type: 'error', message: 'Not in a room — host or join first' })
          return
        }
        if (typeof msg.payloadBase64 !== 'string') {
          send(ws, { type: 'error', message: 'message requires payloadBase64' })
          return
        }
        const room = registeredRoom(meta)
        if (!room) return
        room.lastActivity = Date.now() // any relayed traffic counts as the room being alive, for the idle sweep below
        const envelope = { type: 'message', fromPlayerId: meta.playerId, payloadBase64: msg.payloadBase64 }
        if (msg.toPlayerId) {
          // A direct reply (e.g. RequestState's response) may be addressed to a spectator,
          // which lives in a separate map from real seats — check both.
          const target = room.members.get(msg.toPlayerId) ?? room.spectators.get(msg.toPlayerId)
          if (target?.ws) send(target.ws, envelope)
        } else {
          broadcastToRoom(meta.roomCode, envelope, meta.playerId)
        }
        break
      }

      case 'leave': {
        leaveImmediately(ws)
        break
      }

      default:
        log(`error: unknown message type "${msg.type}"`)
        send(ws, { type: 'error', message: `Unknown message type: ${msg.type}` })
    }
  })

  ws.on('close', () => handleSocketGone(ws))
  ws.on('error', (err) => {
    log('error:', err.message)
    handleSocketGone(ws)
  })
})

// Standard ws heartbeat: ping every open socket, and terminate whichever one
// didn't answer the previous ping (a half-open TCP connection — e.g. a phone
// that dropped off Wi-Fi without a clean close — would otherwise sit in
// `rooms` forever, looking alive).
const heartbeatInterval = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      log('error: terminating socket that missed a heartbeat ping')
      ws.terminate() // triggers 'close' -> handleSocketGone, no separate cleanup needed here
      continue
    }
    ws.isAlive = false
    ws.ping()
  }
}, HEARTBEAT_INTERVAL_MS)

// Belt-and-suspenders alongside the heartbeat above: a room can end up with
// no live traffic (e.g. every member's process was killed hard enough that
// even TCP RST never arrived) without ever hitting MAX_ROOMS. Sweep those out
// periodically instead of leaking them for the life of the process.
const roomSweepInterval = setInterval(() => {
  const now = Date.now()
  for (const [roomCode, room] of rooms) {
    if (now - room.lastActivity > ROOM_IDLE_TIMEOUT_MS) {
      log(`reaping room ${roomCode} — idle for over ${Math.round(ROOM_IDLE_TIMEOUT_MS / 60000)} minutes`)
      rooms.delete(roomCode)
    }
  }
}, ROOM_SWEEP_INTERVAL_MS)

wss.on('close', () => {
  clearInterval(heartbeatInterval)
  clearInterval(roomSweepInterval)
})

console.log(`GameSuite relay server listening on ws://0.0.0.0:${PORT}`)
