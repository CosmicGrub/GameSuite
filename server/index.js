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
//   {"type":"message","toPlayerId":"p2"|null,"payloadBase64":"..."}
//   {"type":"leave"}
//
// Server -> Client:
//   {"type":"hosted","roomCode":"AB3D","playerId":"p1"}
//   {"type":"joined","roomCode":"AB3D","players":[{"playerId":"p1","displayName":"Alex"}, ...]}
//   {"type":"playerJoined","playerId":"p2","displayName":"Sam"}
//   {"type":"playerLeft","playerId":"p2"}
//   {"type":"message","fromPlayerId":"p1","payloadBase64":"..."}
//   {"type":"error","message":"..."}
//
// toPlayerId == null in a "message" means broadcast to every other player
// in the room — the same null-means-broadcast convention MultiplayerTransport
// itself already uses.

const { WebSocketServer } = require('ws')

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 8080
const ROOM_CODE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789' // no 0/O/1/I — avoids read-aloud/typo ambiguity
const ROOM_CODE_LENGTH = 4
const MAX_PLAYERS_PER_ROOM = 8 // generous headroom above any current game's maxPlayers (UNO teams tops out at 4)
const MAX_ROOMS = 500 // this is a free/cheap-tier relay, not a scaled backend — cap total memory rather than fall over
const ROOM_CODE_ALLOC_ATTEMPTS = 50 // bound the collision-retry loop so a near-full code space can't spin forever
const MAX_PAYLOAD_BYTES = 64 * 1024 // ws defaults to 100MB/frame; a room-relay message is never legitimately that big
const MAX_DISPLAY_NAME_LENGTH = 40 // truncate rather than reject — a display name is cosmetic, not worth erroring over
const JOIN_FAILURE_LIMIT = 20 // disconnect a socket that's clearly brute-forcing room codes or playerIds
const HEARTBEAT_INTERVAL_MS = 30 * 1000 // standard ws ping/pong liveness check — reclaims half-open sockets promptly
const ROOM_IDLE_TIMEOUT_MS = 30 * 60 * 1000 // reap rooms nobody's sent traffic in for a long time (abandoned/zombie hosts)
const ROOM_SWEEP_INTERVAL_MS = 5 * 60 * 1000

/** roomCode -> { members: Map<playerId, WebSocket>, lastActivity: number } */
const rooms = new Map()
/** WebSocket -> { roomCode, playerId, displayName } — for cleanup on close/error without a linear scan of every room. */
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

function send(ws, obj) {
  if (ws.readyState !== ws.OPEN) return
  ws.send(JSON.stringify(obj))
}

function broadcastToRoom(roomCode, obj, excludePlayerId) {
  const room = rooms.get(roomCode)
  if (!room) return
  for (const [playerId, memberWs] of room.members) {
    if (playerId === excludePlayerId) continue
    send(memberWs, obj)
  }
}

function playerList(room) {
  return Array.from(room.members.entries()).map(([playerId, ws]) => ({
    playerId,
    displayName: socketMeta.get(ws)?.displayName ?? playerId,
  }))
}

function removeFromRoom(ws) {
  const meta = socketMeta.get(ws)
  if (!meta) return
  // Clear this socket's own membership record first — even if its room was
  // already reaped (idle sweep) or otherwise gone, the socket itself must
  // not be left pointing at a stale roomCode.
  socketMeta.delete(ws)
  const room = rooms.get(meta.roomCode)
  if (!room) return
  room.members.delete(meta.playerId)
  broadcastToRoom(meta.roomCode, { type: 'playerLeft', playerId: meta.playerId })
  if (room.members.size === 0) rooms.delete(meta.roomCode)
  log(`leave: ${meta.playerId} left room ${meta.roomCode}`)
}

/**
 * Counts a failed 'join' (bad room code, taken playerId, full room) against
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
        removeFromRoom(ws) // a socket re-hosting must first leave whatever room it already belonged to
        if (typeof msg.playerId !== 'string' || !msg.playerId) {
          send(ws, { type: 'error', message: 'host requires playerId' })
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
        rooms.set(roomCode, { members: new Map([[msg.playerId, ws]]), lastActivity: Date.now() })
        socketMeta.set(ws, { roomCode, playerId: msg.playerId, displayName })
        log(`host: room ${roomCode} created by ${msg.playerId}`)
        send(ws, { type: 'hosted', roomCode, playerId: msg.playerId })
        break
      }

      case 'join': {
        removeFromRoom(ws) // a socket re-joining must first leave whatever room it already belonged to
        if (typeof msg.roomCode !== 'string' || typeof msg.playerId !== 'string' || !msg.playerId) {
          send(ws, { type: 'error', message: 'join requires roomCode and playerId' })
          return
        }
        const roomCode = msg.roomCode.toUpperCase()
        const room = rooms.get(roomCode)
        if (!room) {
          send(ws, { type: 'error', message: 'Room not found' })
          recordJoinFailure(ws)
          return
        }
        if (room.members.has(msg.playerId)) {
          send(ws, { type: 'error', message: 'playerId already in this room' })
          recordJoinFailure(ws)
          return
        }
        if (room.members.size >= MAX_PLAYERS_PER_ROOM) {
          send(ws, { type: 'error', message: 'Room is full' })
          recordJoinFailure(ws)
          return
        }
        const displayName = sanitizeDisplayName(msg.displayName, msg.playerId)
        const existingPlayers = playerList(room) // snapshot BEFORE adding the joiner
        room.members.set(msg.playerId, ws)
        room.lastActivity = Date.now()
        socketMeta.set(ws, { roomCode, playerId: msg.playerId, displayName })
        log(`join: ${msg.playerId} joined room ${roomCode}`)
        send(ws, { type: 'joined', roomCode, players: existingPlayers })
        broadcastToRoom(roomCode, { type: 'playerJoined', playerId: msg.playerId, displayName }, msg.playerId)
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
        const room = rooms.get(meta.roomCode)
        if (!room) return
        room.lastActivity = Date.now() // any relayed traffic counts as the room being alive, for the idle sweep below
        const envelope = { type: 'message', fromPlayerId: meta.playerId, payloadBase64: msg.payloadBase64 }
        if (msg.toPlayerId) {
          const target = room.members.get(msg.toPlayerId)
          if (target) send(target, envelope)
        } else {
          broadcastToRoom(meta.roomCode, envelope, meta.playerId)
        }
        break
      }

      case 'leave': {
        removeFromRoom(ws)
        break
      }

      default:
        log(`error: unknown message type "${msg.type}"`)
        send(ws, { type: 'error', message: `Unknown message type: ${msg.type}` })
    }
  })

  ws.on('close', () => removeFromRoom(ws))
  ws.on('error', (err) => {
    log('error:', err.message)
    removeFromRoom(ws)
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
      ws.terminate() // triggers 'close' -> removeFromRoom, no separate cleanup needed here
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
