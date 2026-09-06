// Protocol regression test against a running instance of index.js — not
// shipped inside the app, but worth keeping in the repo (not throwing away)
// since it exercises the exact host/join/message/broadcast/leave/error
// paths OnlineTransport.kt depends on. Run: node index.js & then node
// smoke-test.js — see server/README.md.
const WebSocket = require('ws')

let pass = 0, fail = 0
function check(label, cond) {
  if (cond) { pass++; console.log('PASS:', label) }
  else { fail++; console.log('FAIL:', label) }
}

async function main() {
  const host = new WebSocket('ws://localhost:8080')
  const guest = new WebSocket('ws://localhost:8080')

  const hostMsgs = []
  const guestMsgs = []
  host.on('message', (m) => hostMsgs.push(JSON.parse(m.toString())))
  guest.on('message', (m) => guestMsgs.push(JSON.parse(m.toString())))

  await new Promise((r) => host.on('open', r))
  await new Promise((r) => guest.on('open', r))

  host.send(JSON.stringify({ type: 'host', playerId: 'p1', displayName: 'Alex' }))
  await new Promise((r) => setTimeout(r, 200))

  const hosted = hostMsgs.find((m) => m.type === 'hosted')
  check('host receives hosted with roomCode', !!hosted && !!hosted.roomCode)
  const roomCode = hosted.roomCode

  guest.send(JSON.stringify({ type: 'join', roomCode, playerId: 'p2', displayName: 'Sam' }))
  await new Promise((r) => setTimeout(r, 200))

  const joined = guestMsgs.find((m) => m.type === 'joined')
  check('guest receives joined with existing player list', !!joined && joined.players.length === 1 && joined.players[0].playerId === 'p1')
  const playerJoined = hostMsgs.find((m) => m.type === 'playerJoined')
  check('host is notified of playerJoined', !!playerJoined && playerJoined.playerId === 'p2')

  const payload = Buffer.from('hello world').toString('base64')
  host.send(JSON.stringify({ type: 'message', toPlayerId: 'p2', payloadBase64: payload }))
  await new Promise((r) => setTimeout(r, 200))
  const directMsg = guestMsgs.find((m) => m.type === 'message')
  check('direct message relayed to target with correct fromPlayerId', !!directMsg && directMsg.fromPlayerId === 'p1' && directMsg.payloadBase64 === payload)

  const broadcastPayload = Buffer.from('broadcast').toString('base64')
  guest.send(JSON.stringify({ type: 'message', toPlayerId: null, payloadBase64: broadcastPayload }))
  await new Promise((r) => setTimeout(r, 200))
  const broadcastMsg = hostMsgs.find((m) => m.type === 'message' && m.payloadBase64 === broadcastPayload)
  check('broadcast (toPlayerId null) reaches the other room member', !!broadcastMsg && broadcastMsg.fromPlayerId === 'p2')

  guest.close()
  await new Promise((r) => setTimeout(r, 200))
  const left = hostMsgs.find((m) => m.type === 'playerLeft')
  check('host notified when guest disconnects', !!left && left.playerId === 'p2')

  // Wrong room code
  const stray = new WebSocket('ws://localhost:8080')
  await new Promise((r) => stray.on('open', r))
  const strayMsgs = []
  stray.on('message', (m) => strayMsgs.push(JSON.parse(m.toString())))
  stray.send(JSON.stringify({ type: 'join', roomCode: 'ZZZZ', playerId: 'p3' }))
  await new Promise((r) => setTimeout(r, 200))
  check('joining a nonexistent room returns an error', strayMsgs.some((m) => m.type === 'error'))

  host.close(); stray.close()
  console.log(`\n${pass} passed, ${fail} failed`)
  process.exit(fail > 0 ? 1 : 0)
}

main()
