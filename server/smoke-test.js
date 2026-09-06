// Protocol regression test for index.js — not shipped inside the app, but
// worth keeping in the repo since it exercises the exact host/join/message/
// broadcast/leave/error paths OnlineTransport.kt depends on, plus the crash
// and room-leak fixes those paths used to be vulnerable to.
//
// Fully self-contained: this file spawns `node index.js` itself, polls until
// it's accepting connections, runs every check below, then always kills the
// child process before exiting — so `npm test` works standalone on a clean
// checkout with nothing else pre-started. See server/README.md.
const WebSocket = require('ws')
const { spawn } = require('child_process')

// A random high port (rather than the default 8080) so this can run
// alongside a developer's own `npm start` without a port clash.
const TEST_PORT = process.env.SMOKE_TEST_PORT
  ? parseInt(process.env.SMOKE_TEST_PORT, 10)
  : 20000 + Math.floor(Math.random() * 10000)
const WS_URL = `ws://localhost:${TEST_PORT}`

let pass = 0, fail = 0
function check(label, cond) {
  if (cond) { pass++; console.log('PASS:', label) }
  else { fail++; console.log('FAIL:', label) }
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** Opens a WebSocket to the test server and collects every parsed JSON message it receives. */
function connect() {
  const ws = new WebSocket(WS_URL)
  ws.on('error', () => {}) // a few checks below deliberately provoke server-side disconnects
  const messages = []
  ws.on('message', (m) => {
    try { messages.push(JSON.parse(m.toString())) } catch { /* the raw-frame test below parses its own sends, not replies */ }
  })
  return { ws, messages }
}

function opened(ws) {
  return new Promise((resolve, reject) => {
    ws.once('open', resolve)
    ws.once('error', reject)
  })
}

/** Polls with fresh connection attempts until the server accepts one, or gives up after timeoutMs. */
function waitForServer(timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const probe = new WebSocket(WS_URL)
      probe.once('open', () => { probe.close(); resolve() })
      probe.once('error', () => {
        probe.removeAllListeners()
        if (Date.now() > deadline) reject(new Error(`Server never started accepting connections on ${WS_URL}`))
        else setTimeout(attempt, 100)
      })
    }
    attempt()
  })
}

async function main() {
  const child = spawn(process.execPath, ['index.js'], {
    cwd: __dirname,
    env: { ...process.env, PORT: String(TEST_PORT), RECONNECT_GRACE_MS_OVERRIDE: '500' },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let childOutput = ''
  child.stdout.on('data', (d) => { childOutput += d.toString() })
  child.stderr.on('data', (d) => { childOutput += d.toString() })

  try {
    await waitForServer()

    const host = new WebSocket(WS_URL)
    const guest = new WebSocket(WS_URL)
    host.on('error', () => {})
    guest.on('error', () => {})

    const hostMsgs = []
    const guestMsgs = []
    host.on('message', (m) => hostMsgs.push(JSON.parse(m.toString())))
    guest.on('message', (m) => guestMsgs.push(JSON.parse(m.toString())))

    await opened(host)
    await opened(guest)

    host.send(JSON.stringify({ type: 'host', playerId: 'p1', displayName: 'Alex' }))
    await wait(200)

    const hosted = hostMsgs.find((m) => m.type === 'hosted')
    check('host receives hosted with roomCode', !!hosted && !!hosted.roomCode)
    const roomCode = hosted.roomCode

    guest.send(JSON.stringify({ type: 'join', roomCode, playerId: 'p2', displayName: 'Sam' }))
    await wait(200)

    const joined = guestMsgs.find((m) => m.type === 'joined')
    check('guest receives joined with existing player list', !!joined && joined.players.length === 1 && joined.players[0].playerId === 'p1')
    const playerJoined = hostMsgs.find((m) => m.type === 'playerJoined')
    check('host is notified of playerJoined', !!playerJoined && playerJoined.playerId === 'p2')

    const payload = Buffer.from('hello world').toString('base64')
    host.send(JSON.stringify({ type: 'message', toPlayerId: 'p2', payloadBase64: payload }))
    await wait(200)
    const directMsg = guestMsgs.find((m) => m.type === 'message')
    check('direct message relayed to target with correct fromPlayerId', !!directMsg && directMsg.fromPlayerId === 'p1' && directMsg.payloadBase64 === payload)

    const broadcastPayload = Buffer.from('broadcast').toString('base64')
    guest.send(JSON.stringify({ type: 'message', toPlayerId: null, payloadBase64: broadcastPayload }))
    await wait(200)
    const broadcastMsg = hostMsgs.find((m) => m.type === 'message' && m.payloadBase64 === broadcastPayload)
    check('broadcast (toPlayerId null) reaches the other room member', !!broadcastMsg && broadcastMsg.fromPlayerId === 'p2')

    // --- duplicate playerId join rejection ---
    const dupJoiner = connect()
    await opened(dupJoiner.ws)
    dupJoiner.ws.send(JSON.stringify({ type: 'join', roomCode, playerId: 'p1' })) // p1 is already the host in this room
    await wait(200)
    check(
      'joining with a playerId already in the room is rejected',
      dupJoiner.messages.some((m) => m.type === 'error' && /already in this room/i.test(m.message)),
    )
    dupJoiner.ws.close()

    // --- room-full rejection ---
    const fullHost = connect()
    await opened(fullHost.ws)
    fullHost.ws.send(JSON.stringify({ type: 'host', playerId: 'full-0' }))
    await wait(200)
    const fullRoomCode = fullHost.messages.find((m) => m.type === 'hosted').roomCode
    const fullMembers = [fullHost]
    for (let i = 1; i < 8; i++) { // MAX_PLAYERS_PER_ROOM is 8; fill the remaining 7 seats
      const member = connect()
      await opened(member.ws)
      member.ws.send(JSON.stringify({ type: 'join', roomCode: fullRoomCode, playerId: `full-${i}` }))
      await wait(100)
      fullMembers.push(member)
    }
    const overflow = connect()
    await opened(overflow.ws)
    overflow.ws.send(JSON.stringify({ type: 'join', roomCode: fullRoomCode, playerId: 'full-overflow' }))
    await wait(200)
    check('joining a full room is rejected', overflow.messages.some((m) => m.type === 'error' && /full/i.test(m.message)))
    overflow.ws.close()
    for (const member of fullMembers) member.ws.close()

    // --- malformed / non-JSON input never crashes the server ---
    // (JSON.parse('null') SUCCEEDS and yields JS `null` — that's the bug this
    // fix targets, so it's exercised alongside genuinely invalid JSON.)
    const rawSocket = connect()
    await opened(rawSocket.ws)
    rawSocket.ws.send('this is not json')
    await wait(150)
    rawSocket.ws.send('null')
    await wait(150)
    rawSocket.ws.send('42') // valid JSON, but not an object
    await wait(150)
    rawSocket.ws.send('[1,2,3]') // valid JSON, but an array rather than an object
    await wait(150)
    check(
      'malformed/non-object JSON frames get error replies, not a crash',
      rawSocket.messages.filter((m) => m.type === 'error').length >= 4,
    )
    // Prove the server process itself is still alive and still serving other clients.
    rawSocket.ws.send(JSON.stringify({ type: 'host', playerId: 'still-alive' }))
    await wait(200)
    check('server keeps working after malformed input', rawSocket.messages.some((m) => m.type === 'hosted'))
    check('server process did not crash', child.exitCode === null && !child.killed)
    rawSocket.ws.close()

    // --- re-host cleans up the socket's previous room (no room leak) ---
    const reA = connect()
    const reB = connect()
    await opened(reA.ws)
    await opened(reB.ws)
    reA.ws.send(JSON.stringify({ type: 'host', playerId: 'reA' }))
    await wait(150)
    const firstRoom = reA.messages.find((m) => m.type === 'hosted').roomCode
    reB.ws.send(JSON.stringify({ type: 'join', roomCode: firstRoom, playerId: 'reB' }))
    await wait(150)
    reA.messages.length = 0
    reA.ws.send(JSON.stringify({ type: 'host', playerId: 'reA' })) // re-host while already hosting firstRoom
    await wait(200)
    const secondHosted = reA.messages.find((m) => m.type === 'hosted')
    check('re-host succeeds and returns a new room', !!secondHosted && secondHosted.roomCode !== firstRoom)
    check(
      'previous room member is told the re-hosting player left (no leaked membership)',
      reB.messages.some((m) => m.type === 'playerLeft' && m.playerId === 'reA'),
    )

    // --- re-join cleans up the socket's previous room, and the vacated room is deleted once empty ---
    const reC = connect()
    await opened(reC.ws)
    reC.ws.send(JSON.stringify({ type: 'join', roomCode: firstRoom, playerId: 'reC' })) // firstRoom now only has reB
    await wait(150)
    const reD = connect()
    await opened(reD.ws)
    reD.ws.send(JSON.stringify({ type: 'host', playerId: 'reD' }))
    await wait(150)
    const otherRoom = reD.messages.find((m) => m.type === 'hosted').roomCode
    reB.messages.length = 0
    reC.ws.send(JSON.stringify({ type: 'join', roomCode: otherRoom, playerId: 'reC' })) // re-join: leave firstRoom, join otherRoom
    await wait(200)
    check('re-join succeeds in the new room', reC.messages.some((m) => m.type === 'joined' && m.roomCode === otherRoom))
    check(
      'previous room member is told the re-joining player left (no leaked membership)',
      reB.messages.some((m) => m.type === 'playerLeft' && m.playerId === 'reC'),
    )

    // firstRoom now has zero members (reA re-hosted away, reC re-joined away) — it must have been deleted.
    // An explicit "leave" (matching what OnlineTransport.disconnect() actually sends before
    // closing) skips the reconnect grace period, same as re-host/re-join above — a bare
    // ws.close() with no "leave" first is covered separately below (the unexpected-drop path).
    reB.ws.send(JSON.stringify({ type: 'leave' }))
    await wait(150)
    reB.ws.close()
    const lateJoiner = connect()
    await opened(lateJoiner.ws)
    lateJoiner.ws.send(JSON.stringify({ type: 'join', roomCode: firstRoom, playerId: 'late' }))
    await wait(200)
    check(
      'a room with no members left is deleted (later join fails with Room not found)',
      lateJoiner.messages.some((m) => m.type === 'error' && /not found/i.test(m.message)),
    )
    lateJoiner.ws.close()
    reA.ws.close(); reC.ws.close(); reD.ws.close()

    // Explicit "leave" (see the comment above) so this exercises the deliberate-departure path,
    // not the reconnect grace period exercised separately below.
    guest.send(JSON.stringify({ type: 'leave' }))
    await wait(150)
    guest.close()
    await wait(200)
    const left = hostMsgs.find((m) => m.type === 'playerLeft')
    check('host notified when guest disconnects', !!left && left.playerId === 'p2')

    // --- reconnect: a dropped connection's seat is held open, not immediately vacated ---
    const rgA = connect() // host
    const rgB = connect() // the one whose connection will drop and come back
    await opened(rgA.ws)
    await opened(rgB.ws)
    rgA.ws.send(JSON.stringify({ type: 'host', playerId: 'rgA' }))
    await wait(150)
    const graceRoom = rgA.messages.find((m) => m.type === 'hosted').roomCode
    rgB.ws.send(JSON.stringify({ type: 'join', roomCode: graceRoom, playerId: 'rgB' }))
    await wait(150)
    rgA.messages.length = 0
    rgB.ws.terminate() // simulate a dropped connection, NOT a clean {"type":"leave"}
    await wait(150)
    check(
      'a dropped connection is announced as playerDisconnected, not playerLeft',
      rgA.messages.some((m) => m.type === 'playerDisconnected' && m.playerId === 'rgB') &&
        !rgA.messages.some((m) => m.type === 'playerLeft' && m.playerId === 'rgB'),
    )

    const rgBAgain = connect()
    await opened(rgBAgain.ws)
    rgA.messages.length = 0
    rgBAgain.ws.send(JSON.stringify({ type: 'join', roomCode: graceRoom, playerId: 'rgB' }))
    await wait(150)
    check(
      'reconnecting within the grace period gets "reconnected" with the existing roster',
      rgBAgain.messages.some((m) => m.type === 'reconnected' && m.roomCode === graceRoom && m.players.some((p) => p.playerId === 'rgA')),
    )
    check(
      'the other member is told playerReconnected, not a fresh playerJoined',
      rgA.messages.some((m) => m.type === 'playerReconnected' && m.playerId === 'rgB') &&
        !rgA.messages.some((m) => m.type === 'playerJoined' && m.playerId === 'rgB'),
    )

    // --- reconnect: past the grace period, the seat actually vacates ---
    rgA.messages.length = 0
    rgBAgain.ws.terminate()
    await wait(150) // still within the (RECONNECT_GRACE_MS_OVERRIDE=500ms) grace window
    check('no playerLeft yet, still inside the grace window', !rgA.messages.some((m) => m.type === 'playerLeft' && m.playerId === 'rgB'))
    await wait(600) // now past the 500ms override
    check('playerLeft fires once the grace period actually expires', rgA.messages.some((m) => m.type === 'playerLeft' && m.playerId === 'rgB'))
    rgA.ws.close()

    // --- spectator: read-only seat, excluded from the game roster, included in broadcasts ---
    const specHost = connect()
    const specPlayer = connect()
    const spectator = connect()
    await opened(specHost.ws)
    await opened(specPlayer.ws)
    await opened(spectator.ws)
    specHost.ws.send(JSON.stringify({ type: 'host', playerId: 'specHost' }))
    await wait(150)
    const specRoom = specHost.messages.find((m) => m.type === 'hosted').roomCode
    specPlayer.ws.send(JSON.stringify({ type: 'join', roomCode: specRoom, playerId: 'specPlayer' }))
    await wait(150)
    spectator.ws.send(JSON.stringify({ type: 'join', roomCode: specRoom, playerId: 'spec1', spectator: true }))
    await wait(150)
    const specJoined = spectator.messages.find((m) => m.type === 'joined')
    check(
      'a spectator is excluded from the game-relevant roster it receives',
      !!specJoined && !specJoined.players.some((p) => p.playerId === 'spec1'),
    )
    check(
      "a spectator joining doesn't broadcast playerJoined to real seats (silent, read-only entry)",
      !specHost.messages.some((m) => m.type === 'playerJoined' && m.playerId === 'spec1'),
    )
    const specPayload = Buffer.from('spectator sees this').toString('base64')
    specHost.ws.send(JSON.stringify({ type: 'message', toPlayerId: null, payloadBase64: specPayload }))
    await wait(200)
    check(
      'a spectator receives the same broadcasts real seats do',
      spectator.messages.some((m) => m.type === 'message' && m.payloadBase64 === specPayload),
    )
    specHost.ws.close(); specPlayer.ws.close(); spectator.ws.close()

    // Wrong room code
    const stray = new WebSocket(WS_URL)
    stray.on('error', () => {})
    await opened(stray)
    const strayMsgs = []
    stray.on('message', (m) => strayMsgs.push(JSON.parse(m.toString())))
    stray.send(JSON.stringify({ type: 'join', roomCode: 'ZZZZ', playerId: 'p3' }))
    await wait(200)
    check('joining a nonexistent room returns an error', strayMsgs.some((m) => m.type === 'error'))

    host.close(); stray.close()
    await wait(100)
  } finally {
    child.kill()
    await wait(100)
    if (child.exitCode === null && !child.killed) child.kill('SIGKILL')
  }

  if (fail > 0) console.log('\n--- server output (for debugging failures) ---\n' + childOutput)
  console.log(`\n${pass} passed, ${fail} failed`)
  process.exit(fail > 0 ? 1 : 0)
}

main().catch((err) => {
  console.error('Smoke test crashed:', err)
  process.exit(1)
})
