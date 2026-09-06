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

/** roomCode -> { members: Map<playerId, WebSocket> } */
const rooms = new Map()
/** WebSocket -> { roomCode, playerId } — for cleanup on close/error without a linear scan of every room. */
const socketMeta = new WeakMap()

function randomRoomCode() {
  let code = ''
  for (let i = 0; i < ROOM_CODE_LENGTH; i++) {
    code += ROOM_CODE_CHARS[Math.floor(Math.random() * ROOM_CODE_CHARS.length)]
  }
  return code
}

function freshRoomCode() {
  let code = randomRoomCode()
  while (rooms.has(code)) code = randomRoomCode() // collision is rare at this alphabet/length but check anyway
  return code
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
  const room = rooms.get(meta.roomCode)
  if (!room) return
  room.members.delete(meta.playerId)
  broadcastToRoom(meta.roomCode, { type: 'playerLeft', playerId: meta.playerId })
  if (room.members.size === 0) rooms.delete(meta.roomCode)
  socketMeta.delete(ws)
}

const wss = new WebSocketServer({ port: PORT })

wss.on('connection', (ws) => {
  ws.on('message', (raw) => {
    let msg
    try {
      msg = JSON.parse(raw.toString())
    } catch {
      send(ws, { type: 'error', message: 'Malformed JSON' })
      return
    }

    switch (msg.type) {
      case 'host': {
        if (typeof msg.playerId !== 'string' || !msg.playerId) {
          send(ws, { type: 'error', message: 'host requires playerId' })
          return
        }
        const roomCode = freshRoomCode()
        rooms.set(roomCode, { members: new Map([[msg.playerId, ws]]) })
        socketMeta.set(ws, { roomCode, playerId: msg.playerId, displayName: msg.displayName ?? msg.playerId })
        send(ws, { type: 'hosted', roomCode, playerId: msg.playerId })
        break
      }

      case 'join': {
        if (typeof msg.roomCode !== 'string' || typeof msg.playerId !== 'string' || !msg.playerId) {
          send(ws, { type: 'error', message: 'join requires roomCode and playerId' })
          return
        }
        const room = rooms.get(msg.roomCode.toUpperCase())
        if (!room) {
          send(ws, { type: 'error', message: 'Room not found' })
          return
        }
        if (room.members.has(msg.playerId)) {
          send(ws, { type: 'error', message: 'playerId already in this room' })
          return
        }
        if (room.members.size >= MAX_PLAYERS_PER_ROOM) {
          send(ws, { type: 'error', message: 'Room is full' })
          return
        }
        const roomCode = msg.roomCode.toUpperCase()
        const existingPlayers = playerList(room) // snapshot BEFORE adding the joiner
        room.members.set(msg.playerId, ws)
        socketMeta.set(ws, { roomCode, playerId: msg.playerId, displayName: msg.displayName ?? msg.playerId })
        send(ws, { type: 'joined', roomCode, players: existingPlayers })
        broadcastToRoom(roomCode, { type: 'playerJoined', playerId: msg.playerId, displayName: msg.displayName ?? msg.playerId }, msg.playerId)
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
        send(ws, { type: 'error', message: `Unknown message type: ${msg.type}` })
    }
  })

  ws.on('close', () => removeFromRoom(ws))
  ws.on('error', () => removeFromRoom(ws))
})

console.log(`GameSuite relay server listening on ws://0.0.0.0:${PORT}`)
