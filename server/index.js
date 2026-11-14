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
// Wire protocol: one JSON object per WebSocket text frame. The full reference, including every wave-5 frame
// (room metadata, peek/lock/kick/started, quick-chat, matchmaking) and every error code, is in README.md and
// CHANGELOG-wave5.md; this header lists the original frames.
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
//   {"type":"error","message":"...","code":"..."}     (code added in wave 5; old errors keep their exact message text)
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
//
// Wave 5 additions (all additive: an old client never sends them and keeps working unchanged):
//   room metadata  'host' may declare gameId/minSeats/maxSeats/avatar; 'peek' previews a room without a seat;
//                  the relay refuses a seat when the room is full, locked or started; 'lock'/'started'/'kick' are
//                  host-only (rooms-meta.js)
//   quick-chat     'chat' carries only an allowlisted phraseId (quickchat.js)
//   matchmaking    'queue'/'queueCancel'/'claim' (queue.js)
//   hardening      per-IP join-failure/host/queue budgets, a per-socket flood guard, lobby TTL, room-code
//                  cooldown, display-name sanitising (ratelimit.js, sanitize.js, config.js)

const crypto = require('crypto')
const http = require('http')
const { WebSocketServer } = require('ws')
const clock = require('./clock')
const cfg = require('./config').loadConfig()
const { sanitizeDisplayName, sanitizeAvatar } = require('./sanitize')
const { FloodGuard, IpLimiter, resolveClientIp, hashIp } = require('./ratelimit')
const roomsMeta = require('./rooms-meta')
const quickchat = require('./quickchat')
const { createMatchmaker } = require('./queue')
const seatRanges = require('./seat-ranges.json')

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 8080
const ROOM_CODE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789' // no 0/O/1/I — avoids read-aloud/typo ambiguity
// ROOM_CODE_LENGTH, ROOM_IDLE_TIMEOUT_MS and ROOM_SWEEP_INTERVAL_MS can be overridden (like RECONNECT_GRACE_MS_OVERRIDE
// below) so identity-test.js can reap a room and force its code to be reused without waiting half an hour.
const ROOM_CODE_LENGTH = process.env.ROOM_CODE_LENGTH_OVERRIDE ? parseInt(process.env.ROOM_CODE_LENGTH_OVERRIDE, 10) : 4
const MATCH_ROOM_CODE_LENGTH = 8 // matchmade rooms use an internal id that is never shown to users, so it cannot collide with a friend code
const MAX_PLAYERS_PER_ROOM = 8 // generous headroom above any current game's maxPlayers (UNO teams tops out at 4)
const MAX_ROOMS = 500 // this is a free/cheap-tier relay, not a scaled backend — cap total memory rather than fall over
const ROOM_CODE_ALLOC_ATTEMPTS = 50 // bound the collision-retry loop so a near-full code space can't spin forever
const MAX_PAYLOAD_BYTES = 64 * 1024 // ws defaults to 100MB/frame; a room-relay message is never legitimately that big
const MAX_PLAYER_ID_LENGTH = 64 // the app mints "p-" + a UUID (38 chars); the cap bounds what a room remembers about retired seats
const MAX_SEAT_IDS_PER_ROOM = 64 // distinct playerIds a room will ever seat (see retired seats, above) — fail closed, don't grow forever
const RECONNECT_TOKEN_BYTES = 24 // 192 bits from crypto.randomBytes
const RETIRED_SEAT_MESSAGE = 'playerId can no longer be used in this room'
const NO_SUCH_SEAT_MESSAGE = 'no seat to resume in this room'
const JOIN_FAILURE_LIMIT = 20 // disconnect a socket that's clearly brute-forcing room codes or playerIds (the per-IP budget in ratelimit.js survives a reconnect; this one does not)
const HEARTBEAT_INTERVAL_MS = 30 * 1000 // standard ws ping/pong liveness check — reclaims half-open sockets promptly
const ROOM_IDLE_TIMEOUT_MS = process.env.ROOM_IDLE_TIMEOUT_MS_OVERRIDE ? parseInt(process.env.ROOM_IDLE_TIMEOUT_MS_OVERRIDE, 10) : 30 * 60 * 1000 // reap rooms nobody's sent traffic in for a long time (abandoned/zombie hosts)
const ROOM_SWEEP_INTERVAL_MS = process.env.ROOM_SWEEP_INTERVAL_MS_OVERRIDE ? parseInt(process.env.ROOM_SWEEP_INTERVAL_MS_OVERRIDE, 10) : 5 * 60 * 1000
// Overridable so smoke-test.js doesn't have to actually wait 30s to test grace-period expiry.
const RECONNECT_GRACE_MS = process.env.RECONNECT_GRACE_MS_OVERRIDE
  ? parseInt(process.env.RECONNECT_GRACE_MS_OVERRIDE, 10)
  : 30 * 1000 // how long a dropped player's seat is held open for them to reconnect
// Per-IP rate limits fail-safe messages. Weights of refusals that are not guesses (the joiner already knew the code).
const SOFT_REFUSAL_WEIGHT = 0.25

/** roomCode -> { members: Map<playerId, {ws: WebSocket|null, displayName, avatar?, disconnectTimer, reconnectToken, chat}>,
 *               spectators: Map<playerId, {ws: WebSocket, displayName, avatar?}>, lastActivity: number,
 *               hostPlayerId: string, usedSeatIds: Set<playerId>,
 *               meta: {gameId,minSeats,maxSeats,public}|null, locked, started, matchmade, creatorIp, lobbySince }
 *  A member's ws is null exactly while its seat is held open mid-reconnect-grace-period.
 *  usedSeatIds holds every playerId that has ever held a real seat in the room — the ones no longer
 *  in `members` are the retired seats nobody may claim again.
 *  `meta` is null for a legacy host (an old client that sent no gameId). `matchmade` rooms are created by the
 *  matchmaker, are invisible to peek/join and are entered only through 'claim'. */
const rooms = new Map()
/** WebSocket -> { roomCode, playerId, displayName, spectator, room } — for cleanup on close/error without a linear scan of every room.
 *  `room` is the room object the socket registered in; see registeredRoom(). */
const socketMeta = new WeakMap()
/** WebSocket -> count of consecutive failed 'join' attempts, for basic abuse rate-limiting. */
const joinFailureCounts = new WeakMap()
/** roomCode -> time before which the code is not handed out again (see removeRoom). */
const codeCooldowns = new Map()

const ipLimiter = new IpLimiter(cfg)
const startedAtMs = Date.now()

function log(...args) {
  console.log(new Date().toISOString(), ...args)
}

function randomCode(length) {
  let code = ''
  for (let i = 0; i < length; i++) {
    code += ROOM_CODE_CHARS[crypto.randomInt(ROOM_CODE_CHARS.length)]
  }
  return code
}

/** Returns an unused room code, or null if the code space looks exhausted (caller should surface an error, not hang). */
function freshRoomCode() {
  const t = clock.now()
  for (let attempt = 0; attempt < ROOM_CODE_ALLOC_ATTEMPTS; attempt++) {
    const code = randomCode(ROOM_CODE_LENGTH)
    if (rooms.has(code)) continue
    const until = codeCooldowns.get(code)
    if (until !== undefined && until > t) continue // freed recently: a slow friend typing it must not land in a stranger's room
    return code
  }
  return null
}

/** The one place a room is removed, so the code cooldown is applied uniformly. */
function removeRoom(roomCode, { cooldown = true } = {}) {
  if (!rooms.delete(roomCode)) return
  if (cooldown && cfg.codeCooldownMs > 0) codeCooldowns.set(roomCode, clock.now() + cfg.codeCooldownMs)
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

function sendError(ws, code, message, extra) {
  send(ws, { type: 'error', code, message, ...(extra || {}) })
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

function playerEntry(playerId, member) {
  const entry = { playerId, displayName: member.displayName }
  if (member.avatar) entry.avatar = member.avatar
  return entry
}

/** The game-relevant roster — real seats only, never spectators (a GameModule's
 *  GameContext.players should never include a read-only watcher as a "player"). */
function playerList(room) {
  return Array.from(room.members.entries()).map(([playerId, member]) => playerEntry(playerId, member))
}

/** A deliberate departure — re-hosting, re-joining a different room, or an explicit
 *  {"type":"leave"} — skips the reconnect grace period entirely and vacates the seat now. */
function leaveImmediately(ws) {
  matchmaker.socketGone(ws) // a socket with a quick-match ticket that leaves also leaves the queue / aborts a pending match
  const meta = socketMeta.get(ws)
  if (!meta) return
  socketMeta.delete(ws)
  const room = registeredRoom(meta)
  if (!room) return
  if (meta.spectator) {
    room.spectators.delete(meta.playerId)
    log(`leave: spectator ${meta.playerId} left room ${meta.roomCode}`)
    if (room.members.size === 0 && room.spectators.size === 0) removeRoom(meta.roomCode)
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
  room.lobbySince = clock.now()
  broadcastToRoom(roomCode, { type: 'playerLeft', playerId })
  if (room.members.size === 0 && room.spectators.size === 0) removeRoom(roomCode)
  log(`leave: ${playerId} left room ${roomCode}`)
}

/** Fired on an unexpected socket close/error (network blip, backgrounded app, crash) — as
 *  opposed to an explicit {"type":"leave"}. A real player's seat is held open for
 *  RECONNECT_GRACE_MS (see the header comment) rather than vacated immediately; a spectator
 *  (no reconnect story) is just removed. */
function handleSocketGone(ws) {
  matchmaker.socketGone(ws)
  const meta = socketMeta.get(ws)
  if (!meta) return
  socketMeta.delete(ws)
  const room = registeredRoom(meta)
  if (!room) return
  if (meta.spectator) {
    room.spectators.delete(meta.playerId)
    log(`disconnect: spectator ${meta.playerId} left room ${meta.roomCode}`)
    if (room.members.size === 0 && room.spectators.size === 0) removeRoom(meta.roomCode)
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
 * The same failure also counts against the socket's IP (see ratelimit.js): that budget survives a reconnect, which
 * this per-socket counter does not. Successful joins never touch either counter.
 */
function recordJoinFailure(ws, weight = 1) {
  const count = (joinFailureCounts.get(ws) ?? 0) + 1
  joinFailureCounts.set(ws, count)
  if (count >= JOIN_FAILURE_LIMIT) {
    log(`error: terminating socket after ${count} failed join attempts`)
    ws.terminate()
    return
  }
  const verdict = ipLimiter.recordFailure(ws.ip, clock.now(), weight)
  if (verdict.blocked) {
    log(`error: ip ${hashIp(ws.ip)} exhausted its join-failure budget, blocked for ${verdict.retryAfterMs}ms`)
    ws.terminate()
  }
}

/** A refused join/claim: the error frame, then both failure counters. */
function refuseJoin(ws, code, message, weight = 1) {
  sendError(ws, code, message)
  recordJoinFailure(ws, weight)
}

/** True (after answering rate_limited) when this socket's IP is currently blocked from guessing. */
function rejectIfBlocked(ws) {
  const retryAfterMs = ipLimiter.blockedFor(ws.ip, clock.now())
  if (!retryAfterMs) return false
  sendError(ws, 'rate_limited', 'Too many attempts, try again later', { retryAfterMs })
  return true
}

function countRoomsForIp(ip) {
  let n = 0
  for (const room of rooms.values()) if (room.creatorIp === ip) n++
  return n
}

// ---------------------------------------------------------------------------------------------
// Matchmaking glue (queue.js owns the algorithm; these callbacks own sockets and rooms)
// ---------------------------------------------------------------------------------------------

const matchmaker = createMatchmaker({
  now: () => clock.now(),
  config: cfg,
  seatRanges,
  deps: {
    send: (ticket, frame) => send(ticket.ws, frame),
    rttOf: (ticket) => (ticket.ws.rttOverride !== undefined ? ticket.ws.rttOverride : ticket.ws.rttMs ?? Infinity),
    measureRtt: (ws) => pingForRtt(ws),
    canFormRoom: () => rooms.size < MAX_ROOMS,
    log,
    /** The reserved room: empty until its players claim their seats. Invisible to peek and join (matchmade). */
    createRoom({ hostPlayerId, gameId, maxSeats }) {
      let roomCode = null
      for (let attempt = 0; attempt < ROOM_CODE_ALLOC_ATTEMPTS && !roomCode; attempt++) {
        const candidate = randomCode(MATCH_ROOM_CODE_LENGTH)
        if (!rooms.has(candidate)) roomCode = candidate
      }
      if (!roomCode) return null
      const t = clock.now()
      rooms.set(roomCode, {
        members: new Map(),
        spectators: new Map(),
        lastActivity: t,
        hostPlayerId,
        usedSeatIds: new Set(),
        meta: { gameId, minSeats: 1, maxSeats, public: false },
        locked: true,
        started: true,
        matchmade: true,
        creatorIp: null,
        lobbySince: t,
      })
      return roomCode
    },
    /** Discards a reserved room, unseating anyone who had already claimed (they get matchAborted from the matchmaker). */
    abortRoom(roomCode) {
      const room = rooms.get(roomCode)
      if (!room) return
      for (const member of room.members.values()) {
        if (member.disconnectTimer) clearTimeout(member.disconnectTimer)
        if (member.ws) socketMeta.delete(member.ws)
      }
      removeRoom(roomCode, { cooldown: false })
    },
  },
})

/** Seats a validated claim in its reserved room, issuing the same hosted/joined frame a normal host/join gets. */
function seatClaim(ws, ticket, msg) {
  const room = rooms.get(ticket.match.roomCode)
  if (!room) return false
  const roomCode = ticket.match.roomCode
  const playerId = ticket.playerId
  const displayName = sanitizeDisplayName(msg.displayName, ticket.displayName)
  const avatar = sanitizeAvatar(msg.avatar) ?? ticket.avatar
  const reconnectToken = newReconnectToken()
  const isHost = playerId === room.hostPlayerId
  const alreadySeated = playerList(room) // snapshot BEFORE adding this seat
  const member = { ws, displayName, disconnectTimer: null, reconnectToken, chat: new quickchat.ChatLimiter(cfg) }
  if (avatar) member.avatar = avatar
  room.members.set(playerId, member)
  room.usedSeatIds.add(playerId)
  room.lastActivity = clock.now()
  socketMeta.set(ws, { roomCode, playerId, displayName, spectator: false, room })
  const seats = ticket.match.tickets.map((t) => ({ playerId: t.playerId, displayName: t.displayName, ...(t.avatar ? { avatar: t.avatar } : {}) }))
  const seatInfo = { matchmade: true, cpuSeats: ticket.match.cpuSeats, seats }
  if (isHost) {
    send(ws, { type: 'hosted', roomCode, playerId, reconnectToken, ...seatInfo })
    // The host may claim after some guests did: replay their arrival so the host sees the same frames it always does.
    for (const other of alreadySeated) send(ws, { type: 'playerJoined', ...other })
  } else {
    send(ws, { type: 'joined', roomCode, hostPlayerId: room.hostPlayerId, reconnectToken, players: alreadySeated, ...seatInfo })
    broadcastToRoom(roomCode, { type: 'playerJoined', ...playerEntry(playerId, member) }, playerId)
  }
  log(`claim: ${playerId} seated in matchmade room ${roomCode}`)
  const { allSeated } = matchmaker.markSeated(ticket)
  if (allSeated) broadcastToRoom(roomCode, { type: 'matchReady', roomCode })
  return true
}

// ---------------------------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------------------------

/** Health endpoint for hosting platforms (Render, Fly, Docker HEALTHCHECK, uptime monitors). No room data beyond counts. */
const httpServer = http.createServer((req, res) => {
  const path = (req.url || '').split('?')[0]
  if ((req.method === 'GET' || req.method === 'HEAD') && (path === '/health' || path === '/healthz')) {
    const body = JSON.stringify({
      status: 'ok',
      uptimeSec: Math.round((Date.now() - startedAtMs) / 1000),
      rooms: rooms.size,
      ...matchmaker.stats(),
    })
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    res.end(req.method === 'HEAD' ? undefined : body)
    return
  }
  res.writeHead(404, { 'Content-Type': 'text/plain' })
  res.end('GameSuite relay: connect with a WebSocket\n')
})
const wss = new WebSocketServer({ server: httpServer, maxPayload: MAX_PAYLOAD_BYTES })

function pingForRtt(ws) {
  if (ws.readyState !== ws.OPEN) return
  ws.pingSentAt = process.hrtime.bigint()
  ws.ping()
}

wss.on('connection', (ws, req) => {
  ws.ip = resolveClientIp(req, cfg.trustProxy)
  ws.flood = new FloodGuard(cfg)
  log(`connect (client ${hashIp(ws.ip)})`)
  ws.isAlive = true
  ws.on('pong', () => {
    ws.isAlive = true
    if (ws.pingSentAt !== undefined) {
      ws.rttMs = Number(process.hrtime.bigint() - ws.pingSentAt) / 1e6 // measured by the relay, never reported by a client
      ws.pingSentAt = undefined
    }
  })
  pingForRtt(ws) // an RTT sample is ready by the time the socket first queues

  ws.on('message', (raw) => {
    const verdict = ws.flood.check(raw.length, clock.now())
    if (verdict.action === 'terminate') {
      log(`error: terminating socket for flooding (client ${hashIp(ws.ip)})`)
      ws.terminate()
      return
    }
    if (verdict.action === 'drop') {
      if (verdict.notify) sendError(ws, 'rate_limited', 'Sending too fast: frames are being dropped')
      return
    }

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
        const parsedMeta = roomsMeta.parseHostMeta(msg, MAX_PLAYERS_PER_ROOM)
        if (!parsedMeta.ok) {
          sendError(ws, parsedMeta.code, parsedMeta.message)
          return
        }
        if (rejectIfBlocked(ws)) return
        if (!ipLimiter.allowHost(ws.ip, clock.now())) {
          log(`host: rejected ${msg.playerId} — host rate limit for client ${hashIp(ws.ip)}`)
          sendError(ws, 'rate_limited', 'Too many rooms created recently, try again later')
          return
        }
        if (!ipLimiter.exempt(ws.ip) && countRoomsForIp(ws.ip) >= cfg.maxRoomsPerIp) {
          log(`host: rejected ${msg.playerId} — room limit for client ${hashIp(ws.ip)}`)
          sendError(ws, 'too_many_rooms', 'Too many open rooms from your network, close one first')
          return
        }
        if (rooms.size >= MAX_ROOMS) {
          log(`host: rejected ${msg.playerId} — room cap (${MAX_ROOMS}) reached`)
          sendError(ws, 'server_full', 'Server is at capacity, try again later')
          return
        }
        const roomCode = freshRoomCode()
        if (!roomCode) {
          log('host: could not allocate a free room code')
          sendError(ws, 'no_room_code', 'Could not allocate a room code, try again')
          return
        }
        const displayName = sanitizeDisplayName(msg.displayName, msg.playerId)
        const avatar = sanitizeAvatar(msg.avatar)
        const reconnectToken = newReconnectToken()
        const t = clock.now()
        const hostMember = { ws, displayName, disconnectTimer: null, reconnectToken, chat: new quickchat.ChatLimiter(cfg) }
        if (avatar) hostMember.avatar = avatar
        const newRoom = {
          members: new Map([[msg.playerId, hostMember]]),
          spectators: new Map(),
          lastActivity: t,
          hostPlayerId: msg.playerId,
          usedSeatIds: new Set([msg.playerId]),
          meta: parsedMeta.meta,
          locked: false,
          started: false,
          matchmade: false,
          creatorIp: ws.ip,
          lobbySince: t,
        }
        rooms.set(roomCode, newRoom)
        socketMeta.set(ws, { roomCode, playerId: msg.playerId, displayName, spectator: false, room: newRoom })
        log(`host: room ${roomCode} created by ${msg.playerId}`)
        const hosted = { type: 'hosted', roomCode, playerId: msg.playerId, reconnectToken }
        if (newRoom.meta) {
          hosted.gameId = newRoom.meta.gameId
          hosted.minSeats = newRoom.meta.minSeats
          hosted.maxSeats = newRoom.meta.maxSeats
          hosted.expiresInMs = cfg.lobbyTtlMs
        }
        send(ws, hosted)
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
        if (rejectIfBlocked(ws)) return // before the room lookup, so a blocked guesser learns nothing at all
        const roomCode = msg.roomCode.toUpperCase()
        const room = rooms.get(roomCode)
        // A matchmade room is entered only through 'claim'. The one thing 'join' may still do there is RESUME an existing
        // seat (reconnect with its token); everybody else gets the very same answer as for a room that does not exist.
        if (!room || (room.matchmade && (msg.spectator === true || !room.members.has(msg.playerId)))) {
          refuseJoin(ws, 'no_such_room', 'Room not found')
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
            refuseJoin(ws, 'player_in_room', 'playerId already in this room')
            return
          }
          if (room.usedSeatIds.has(msg.playerId)) {
            refuseJoin(ws, 'player_retired', RETIRED_SEAT_MESSAGE)
            return
          }
          const spectatorName = sanitizeDisplayName(msg.displayName, msg.playerId)
          const spectatorAvatar = sanitizeAvatar(msg.avatar)
          room.spectators.set(msg.playerId, { ws, displayName: spectatorName, avatar: spectatorAvatar })
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
            refuseJoin(ws, 'player_in_room', 'playerId already in this room')
            return
          }
        } else {
          if (room.usedSeatIds.has(msg.playerId)) {
            // Held a seat here before and was vacated (left, or its grace period ran out): retired.
            refuseJoin(ws, 'player_retired', RETIRED_SEAT_MESSAGE)
            return
          }
          if (room.spectators.has(msg.playerId)) {
            refuseJoin(ws, 'player_in_room', 'playerId already in this room')
            return
          }
          if (msg.reconnectToken !== undefined && msg.reconnectToken !== null) {
            // A first join never carries a token; this is somebody trying to RESUME a seat this room
            // doesn't have. That is what an honest client looks like after the relay restarted or reaped
            // its room and a stranger re-hosted the same code, and seating it fresh would hand the client
            // to that stranger's room as if it were its own. Refuse, so the client keeps looking for its.
            refuseJoin(ws, 'no_seat_to_resume', NO_SUCH_SEAT_MESSAGE)
            return
          }
          // Wave 5: the host declared what the room is for, so the relay can say why a seat is unavailable
          // before the joiner ever speaks to the host. These refusals are not guesses (the joiner knew the code).
          if (room.meta && typeof msg.gameId === 'string' && msg.gameId !== room.meta.gameId) {
            refuseJoin(ws, 'wrong_game', 'This room is playing a different game', SOFT_REFUSAL_WEIGHT)
            return
          }
          if (room.started) {
            refuseJoin(ws, 'room_started', 'The game has already started', SOFT_REFUSAL_WEIGHT)
            return
          }
          if (room.locked) {
            refuseJoin(ws, 'room_locked', 'The host has locked this room', SOFT_REFUSAL_WEIGHT)
            return
          }
          const seatCap = room.meta ? Math.min(room.meta.maxSeats, MAX_PLAYERS_PER_ROOM) : MAX_PLAYERS_PER_ROOM
          if (room.members.size >= seatCap) {
            refuseJoin(ws, 'room_full', 'Room is full', SOFT_REFUSAL_WEIGHT)
            return
          }
          if (room.usedSeatIds.size >= MAX_SEAT_IDS_PER_ROOM) {
            refuseJoin(ws, 'room_closed', 'Room is closed to new players')
            return
          }
        }

        const displayName = sanitizeDisplayName(msg.displayName, existingMember?.displayName ?? msg.playerId)
        const avatar = sanitizeAvatar(msg.avatar)
        room.lastActivity = clock.now()
        room.lobbySince = room.lastActivity

        if (existingMember) {
          // Reconnect: this seat was held open after a drop (see handleSocketGone) — swap in
          // the new socket instead of treating this as a brand-new join.
          clearTimeout(existingMember.disconnectTimer)
          existingMember.disconnectTimer = null
          existingMember.ws = ws
          existingMember.displayName = displayName
          if (avatar) existingMember.avatar = avatar
          socketMeta.set(ws, { roomCode, playerId: msg.playerId, displayName, spectator: false, room })
          log(`reconnect: ${msg.playerId} rejoined room ${roomCode}`)
          send(ws, { type: 'reconnected', roomCode, players: playerList(room).filter((p) => p.playerId !== msg.playerId) })
          broadcastToRoom(roomCode, { type: 'playerReconnected', playerId: msg.playerId }, msg.playerId)
        } else {
          const existingPlayers = playerList(room) // snapshot BEFORE adding the joiner
          const reconnectToken = newReconnectToken()
          const member = { ws, displayName, disconnectTimer: null, reconnectToken, chat: new quickchat.ChatLimiter(cfg) }
          if (avatar) member.avatar = avatar
          room.members.set(msg.playerId, member)
          room.usedSeatIds.add(msg.playerId)
          socketMeta.set(ws, { roomCode, playerId: msg.playerId, displayName, spectator: false, room })
          log(`join: ${msg.playerId} joined room ${roomCode}`)
          send(ws, { type: 'joined', roomCode, hostPlayerId: room.hostPlayerId, reconnectToken, players: existingPlayers })
          broadcastToRoom(roomCode, { type: 'playerJoined', ...playerEntry(msg.playerId, member) }, msg.playerId)
        }
        break
      }

      case 'peek': {
        // A look at a room without taking a seat: no token is issued and no id or roster is revealed. A miss costs a
        // full join failure against this IP and a hit a tenth of one, so peek cannot be used to enumerate live rooms.
        if (rejectIfBlocked(ws)) return
        if (typeof msg.roomCode !== 'string') {
          sendError(ws, 'bad_request', 'peek requires roomCode')
          return
        }
        const roomCode = msg.roomCode.toUpperCase()
        const room = rooms.get(roomCode)
        if (!room || room.matchmade) {
          sendError(ws, 'no_such_room', 'Room not found')
          recordJoinFailure(ws)
          return
        }
        const verdict = ipLimiter.recordFailure(ws.ip, clock.now(), 0.1)
        send(ws, roomsMeta.peekView(roomCode, room, MAX_PLAYERS_PER_ROOM))
        if (verdict.blocked) ws.terminate()
        break
      }

      case 'lock':
      case 'started':
      case 'kick': {
        const meta = socketMeta.get(ws)
        if (!meta) {
          sendError(ws, 'not_in_room', 'Not in a room — host or join first')
          return
        }
        const room = registeredRoom(meta)
        if (!room) return
        // Host-only, decided by the seat THIS SOCKET registered, never by anything in the frame.
        if (meta.spectator || room.hostPlayerId !== meta.playerId) {
          sendError(ws, 'not_host', `Only the host can send ${msg.type}`)
          return
        }
        if (msg.type === 'lock') {
          if (typeof msg.locked !== 'boolean') {
            sendError(ws, 'bad_request', 'lock requires a boolean "locked"')
            return
          }
          room.locked = msg.locked
          sendRoomState(ws, meta.roomCode, room)
        } else if (msg.type === 'started') {
          room.started = true
          room.lastActivity = clock.now()
          sendRoomState(ws, meta.roomCode, room)
        } else {
          kickPlayer(ws, meta, room, msg.playerId)
        }
        break
      }

      case 'chat': {
        // Quick-chat: an allowlisted id, never text. Stamped from the socket like 'message', never stored, never
        // touches the game 'message' path, and does not count as room activity (spam cannot keep a room alive).
        const meta = socketMeta.get(ws)
        if (!meta) {
          sendError(ws, 'not_in_room', 'Not in a room — host or join first')
          return
        }
        const room = registeredRoom(meta)
        if (!room) return
        if (meta.spectator) {
          sendError(ws, 'chat_denied', 'Spectators cannot send quick-chat')
          return
        }
        if (!quickchat.isValidPhraseId(msg.phraseId)) {
          sendError(ws, 'chat_invalid', 'Unknown quick-chat phrase')
          return
        }
        const member = room.members.get(meta.playerId)
        if (!member || !member.chat.tryAcquire(msg.phraseId, clock.now())) {
          sendError(ws, 'chat_rate', 'Quick-chat is limited: wait a moment')
          return
        }
        broadcastToRoom(meta.roomCode, { type: 'chat', fromPlayerId: meta.playerId, phraseId: msg.phraseId }, meta.playerId)
        break
      }

      case 'queue': {
        if (socketMeta.get(ws)) {
          sendError(ws, 'already_in_room', 'Leave your room before searching for a match')
          return
        }
        if (rejectIfBlocked(ws)) return
        if (!ipLimiter.allowQueue(ws.ip, clock.now())) {
          sendError(ws, 'rate_limited', 'Too many searches, try again in a minute')
          return
        }
        const result = matchmaker.enqueue(ws, msg)
        if (!result.ok) sendError(ws, result.code, result.message, result.retryAfterMs !== undefined ? { retryAfterMs: result.retryAfterMs } : undefined)
        break
      }

      case 'queueCancel': {
        matchmaker.cancel(ws)
        send(ws, { type: 'queueCancelled' })
        break
      }

      case 'claim': {
        if (rejectIfBlocked(ws)) return
        const claim = matchmaker.validateClaim(ws, msg)
        if (!claim.ok) {
          refuseJoin(ws, 'claim_invalid', 'Claim refused')
          return
        }
        if (!seatClaim(ws, claim.ticket, msg)) refuseJoin(ws, 'claim_invalid', 'Claim refused')
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
        room.lastActivity = clock.now() // any relayed traffic counts as the room being alive, for the idle sweep below
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

      case '__test': {
        if (!cfg.enableTestControl) {
          log(`error: unknown message type "${msg.type}"`)
          send(ws, { type: 'error', message: `Unknown message type: ${msg.type}` })
          return
        }
        handleTestControl(ws, msg)
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

function sendRoomState(ws, roomCode, room) {
  send(ws, {
    type: 'roomState',
    roomCode,
    state: roomsMeta.roomState(room),
    seatsTaken: room.members.size,
    maxSeats: room.meta ? room.meta.maxSeats : MAX_PLAYERS_PER_ROOM,
  })
}

/** Host removes a seat (or a spectator): the target is told, its socket is unregistered so it is inert, and the id
 *  is retired through the same machinery a normal leave uses, so it can never be claimed again in this room. */
function kickPlayer(hostWs, hostMeta, room, targetId) {
  if (typeof targetId !== 'string' || !targetId) {
    sendError(hostWs, 'bad_request', 'kick requires playerId')
    return
  }
  if (targetId === room.hostPlayerId) {
    sendError(hostWs, 'cannot_kick_host', 'The host cannot kick itself')
    return
  }
  const member = room.members.get(targetId)
  const spectator = room.spectators.get(targetId)
  if (!member && !spectator) {
    sendError(hostWs, 'no_such_player', 'No such player in this room')
    return
  }
  const targetWs = (member || spectator).ws
  if (targetWs) {
    send(targetWs, { type: 'kicked' })
    socketMeta.delete(targetWs) // unregister first: the kicked socket's later close/leave must not touch the room
  }
  if (member) {
    if (member.disconnectTimer) clearTimeout(member.disconnectTimer)
    finalizeLeave(hostMeta.roomCode, targetId, room) // broadcasts playerLeft and retires the id (usedSeatIds)
  } else {
    room.spectators.delete(targetId)
    room.usedSeatIds.add(targetId) // spectator ids are normally reusable; a kicked one is not
    broadcastToRoom(hostMeta.roomCode, { type: 'playerLeft', playerId: targetId })
  }
  log(`kick: ${targetId} removed from room ${hostMeta.roomCode} by its host`)
}

/** Test-only control frames, enabled by ENABLE_TEST_CONTROL=1 (never in production):
 *    advanceClock {ms}   move the injectable clock forward and run every time-driven job once
 *    setRtt {ms}         pretend this socket's measured RTT is ms (null clears the override)
 *    tick                run every time-driven job once
 *    state               counts of rooms, tickets and pending matches */
function handleTestControl(ws, msg) {
  switch (msg.op) {
    case 'advanceClock':
      clock.advance(msg.ms)
      runTimeDrivenJobs()
      break
    case 'setRtt':
      ws.rttOverride = typeof msg.ms === 'number' ? msg.ms : undefined
      break
    case 'tick':
      runTimeDrivenJobs()
      break
    case 'state':
      break
    default:
      send(ws, { type: '__test', ok: false, error: `unknown op ${msg.op}` })
      return
  }
  send(ws, { type: '__test', op: msg.op, ok: true, now: clock.now(), rooms: rooms.size, ...matchmaker.stats() })
}

// Standard ws heartbeat: ping every open socket, and terminate whichever one
// didn't answer the previous ping (a half-open TCP connection — e.g. a phone
// that dropped off Wi-Fi without a clean close — would otherwise sit in
// `rooms` forever, looking alive). The pong also refreshes the socket's measured RTT.
const heartbeatInterval = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      log('error: terminating socket that missed a heartbeat ping')
      ws.terminate() // triggers 'close' -> handleSocketGone, no separate cleanup needed here
      continue
    }
    ws.isAlive = false
    pingForRtt(ws)
  }
}, HEARTBEAT_INTERVAL_MS)

/** Reaps abandoned rooms: idle ones (nobody has sent traffic in ROOM_IDLE_TIMEOUT_MS) and, for new-style rooms, a lobby
 *  that only ever held its host for LOBBY_TTL_MS. Also forgets expired cooldowns and per-IP state. */
function sweepRooms() {
  const now = clock.now()
  for (const [roomCode, room] of rooms) {
    if (now - room.lastActivity > ROOM_IDLE_TIMEOUT_MS) {
      log(`reaping room ${roomCode} — idle for over ${Math.round(ROOM_IDLE_TIMEOUT_MS / 60000)} minutes`)
      removeRoom(roomCode)
      continue
    }
    if (room.meta && !room.started && !room.matchmade && room.members.size <= 1 && now - room.lobbySince > cfg.lobbyTtlMs) {
      log(`reaping room ${roomCode} — lobby with no guests for over ${Math.round(cfg.lobbyTtlMs / 1000)} seconds`)
      for (const member of room.members.values()) if (member.ws) send(member.ws, { type: 'roomClosed', reason: 'lobby_expired' })
      for (const spectator of room.spectators.values()) send(spectator.ws, { type: 'roomClosed', reason: 'lobby_expired' })
      removeRoom(roomCode)
    }
  }
  for (const [code, until] of codeCooldowns) if (until <= now) codeCooldowns.delete(code)
  ipLimiter.sweep(now)
}

function runTimeDrivenJobs() {
  sweepRooms()
  matchmaker.tick()
}

// Belt-and-suspenders alongside the heartbeat above: a room can end up with
// no live traffic (e.g. every member's process was killed hard enough that
// even TCP RST never arrived) without ever hitting MAX_ROOMS. Sweep those out
// periodically instead of leaking them for the life of the process.
// The lobby TTL (10 minutes) and the ip/cooldown tables want a finer beat than the original 5-minute sweep, so the
// sweep runs at the shorter of ROOM_SWEEP_INTERVAL_MS and 15 seconds (identity-test.js shrinks the former to 100 ms).
const roomSweepInterval = setInterval(sweepRooms, Math.min(ROOM_SWEEP_INTERVAL_MS, 15 * 1000))
const queueInterval = setInterval(() => matchmaker.tick(), cfg.queueTickMs)

wss.on('close', () => {
  clearInterval(heartbeatInterval)
  clearInterval(roomSweepInterval)
  clearInterval(queueInterval)
})

httpServer.listen(PORT, () => {
  console.log(`GameSuite relay server listening on ws://0.0.0.0:${PORT}`)
})

// Containers stop with SIGTERM: close cleanly instead of waiting for the platform to SIGKILL after its grace period.
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    log(`${signal} received, shutting down`)
    for (const ws of wss.clients) ws.terminate()
    wss.close()
    httpServer.close(() => process.exit(0))
    setTimeout(() => process.exit(0), 2000).unref()
  })
}
