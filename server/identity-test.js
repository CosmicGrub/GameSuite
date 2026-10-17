// Identity / seat-takeover regression test for index.js — the companion to smoke-test.js.
//
// index.js stamps every relayed message with the sender's 'fromPlayerId', and games build on that
// (a UNO guest only believes a StateSync that arrives from the host's playerId). That stamp is only
// worth anything if nobody can take over another player's seat, and playerIds are PUBLIC (the host's
// id is in every guest's roster). The relay therefore protects a seat with a per-seat reconnectToken
// (only the socket that took the seat ever sees it) and retires a vacated seat's playerId for the
// life of the room. This file attacks exactly those two things, plus every neighbouring way of
// getting a message stamped with somebody else's id, and pairs each attack with an honest-client
// control so a relay that "fixes" the hole by breaking reconnect cannot pass.
//
// Fully self-contained, like smoke-test.js: it spawns `node index.js` itself (three times: with a long
// reconnect grace period for everything that must happen INSIDE the grace window, with a short one for
// the checks that need a seat to really expire, and with a shrunken idle sweep and room-code space for
// the check on sockets left over from a reaped room), waits for each relay to accept connections, runs
// every check, then always kills all the children before exiting. `npm test` works standalone.
//
// Timing rule: nothing here sleeps and hopes. Every step awaits the specific frame it depends on
// (with a timeout that turns a stall into a failed check, not a hang). Where a check must show that
// something did NOT happen, an ordering barrier is used wherever one exists — the relay handles one
// socket's frames in order, so once a later frame has arrived, an earlier one that was going to arrive
// already has. The few places that have no barrier (waiting out a grace timer or an idle sweep, or
// letting a stale socket's frames drain) use a short fixed pause, always with generous margins.
const WebSocket = require('ws')
const { spawn } = require('child_process')

// Two random high ports (outside smoke-test.js's 20000-30000 range so the two can never clash).
const BASE_PORT = process.env.IDENTITY_TEST_PORT
  ? parseInt(process.env.IDENTITY_TEST_PORT, 10)
  : 30000 + Math.floor(Math.random() * 9000)
// Everything that has to happen while a dropped seat is still held (all the grace-window attacks and
// honest reconnects) runs against LONG, where the window is far longer than any step here takes, so
// a slow machine cannot expire a seat mid-test and turn an attack into a pass-for-the-wrong-reason.
const LONG_GRACE_MS = 20000
// The checks that need a seat to actually expire run against SHORT.
const SHORT_GRACE_MS = 1500
// The reaper relay (see s13StaleSocketAfterReap): its own grace period, long enough to outlive the idle sweep.
const REAP_GRACE_MS = 4200
const RETIRED = 'playerId can no longer be used in this room'
const IN_ROOM = 'playerId already in this room'
const NO_SEAT = 'no seat to resume in this room'

let pass = 0, fail = 0
function check(label, cond, detail) {
  if (cond) { pass++; console.log('PASS:', label) }
  else { fail++; console.log('FAIL:', label + (detail !== undefined ? `   [${typeof detail === 'string' ? detail : JSON.stringify(detail)}]` : '')) }
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * Resolves once `ws` is open. Several sockets are usually created back to back and then awaited one
 * at a time, so a later socket can finish its handshake while an earlier one is still being awaited:
 * its 'open' event has already fired by the time a listener is attached here, and a listener-only
 * version waits forever. Checking readyState first fixes that, and the timeout turns any remaining
 * stall into a clear failure instead of a silent hang.
 */
function opened(ws, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    if (ws.readyState === WebSocket.OPEN) return resolve()
    if (ws.readyState !== WebSocket.CONNECTING) return reject(new Error('socket closed before it was awaited'))
    const timer = setTimeout(() => reject(new Error(`WebSocket did not open within ${timeoutMs}ms`)), timeoutMs)
    ws.once('open', () => { clearTimeout(timer); resolve() })
    ws.once('error', (err) => { clearTimeout(timer); reject(err) })
  })
}

// ---------------------------------------------------------------------------------------------
// Relay processes
// ---------------------------------------------------------------------------------------------

const relays = []
function startRelay(name, port, graceMs, extraEnv = {}) {
  const relay = { name, port, url: `ws://127.0.0.1:${port}`, output: '', child: null }
  relay.child = spawn(process.execPath, ['index.js'], {
    cwd: __dirname,
    env: { ...process.env, PORT: String(port), RECONNECT_GRACE_MS_OVERRIDE: String(graceMs), ...extraEnv },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  relay.child.stdout.on('data', (d) => { relay.output += d.toString() })
  relay.child.stderr.on('data', (d) => { relay.output += d.toString() })
  relays.push(relay)
  return relay
}

function killRelays() {
  for (const relay of relays) {
    try { if (relay.child && relay.child.exitCode === null) relay.child.kill() } catch { /* already gone */ }
  }
}
// Belt and braces: even a process.exit() from anywhere (watchdog, crash handler) takes the relays down.
process.on('exit', killRelays)

/** Polls with fresh connection attempts until the relay is up, or gives up after timeoutMs. */
function waitForServer(relay, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs
  return new Promise((resolve, reject) => {
    const attempt = () => {
      if (relay.child.exitCode !== null) return reject(new Error(`relay ${relay.name} exited early (port clash?):\n${relay.output}`))
      // Never talk to a stranger that happens to own the port: only probe once OUR child says it listens.
      if (!relay.output.includes('listening on')) {
        if (Date.now() > deadline) return reject(new Error(`relay ${relay.name} never reported listening:\n${relay.output}`))
        return void setTimeout(attempt, 50)
      }
      const probe = new WebSocket(relay.url)
      probe.once('open', () => { probe.close(); resolve() })
      probe.once('error', () => {
        probe.removeAllListeners()
        if (Date.now() > deadline) reject(new Error(`Server never started accepting connections on ${relay.url}`))
        else setTimeout(attempt, 100)
      })
    }
    attempt()
  })
}

// ---------------------------------------------------------------------------------------------
// Clients and small protocol helpers
// ---------------------------------------------------------------------------------------------

let LONG = null // set in main()
let SHORT = null
let REAP = null

const allClients = []
/** A socket that records every parsed frame AND every raw text frame (for the token-leak scan). */
function newClient(relay) {
  const ws = new WebSocket(relay.url)
  const c = {
    ws, relay, frames: [], raw: [], closed: false,
    mark() { return c.frames.length },
    send(obj) { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj)) },
    sendRaw(text) { if (ws.readyState === WebSocket.OPEN) ws.send(text) },
  }
  ws.on('error', () => {}) // several checks deliberately provoke server-side disconnects
  ws.on('message', (data) => {
    const text = data.toString()
    c.raw.push(text)
    try { c.frames.push(JSON.parse(text)) } catch { /* the relay only ever sends JSON */ }
  })
  ws.on('close', () => { c.closed = true })
  allClients.push(c)
  return c
}

async function open(relay = LONG) {
  const c = newClient(relay)
  await opened(c.ws)
  return c
}

/** Resolves with the first frame at/after index `since` matching `pred`, or null after timeoutMs. */
function waitFor(c, pred, { since = 0, timeoutMs = 4000 } = {}) {
  const deadline = Date.now() + timeoutMs
  return new Promise((resolve) => {
    const poll = () => {
      for (let i = since; i < c.frames.length; i++) {
        if (pred(c.frames[i])) return resolve(c.frames[i])
      }
      if (Date.now() >= deadline) return resolve(null)
      setTimeout(poll, 10)
    }
    poll()
  })
}

function waitClosed(c, timeoutMs = 4000) {
  const deadline = Date.now() + timeoutMs
  return new Promise((resolve) => {
    const poll = () => {
      if (c.closed) return resolve(true)
      if (Date.now() >= deadline) return resolve(false)
      setTimeout(poll, 10)
    }
    poll()
  })
}

const REPLY_TYPES = ['hosted', 'joined', 'reconnected', 'error']
/** The relay's answer to a host/join: the first terminal frame after `since`. */
function firstReply(c, since, timeoutMs = 4000) {
  return waitFor(c, (m) => REPLY_TYPES.includes(m.type), { since, timeoutMs })
}
/** Sends one frame and returns the relay's terminal reply to it (or null on timeout). */
async function request(c, obj, timeoutMs = 4000) {
  const since = c.mark()
  c.send(obj)
  return firstReply(c, since, timeoutMs)
}

const b64 = (s) => Buffer.from(s).toString('base64')
function sendMsg(c, toPlayerId, text, extra = {}) {
  c.send({ type: 'message', toPlayerId, payloadBase64: b64(text), ...extra })
}
/** Waits for a relayed message carrying `text` (as sent via sendMsg). */
function gotMessage(c, text, since = 0, timeoutMs = 4000) {
  return waitFor(c, (m) => m.type === 'message' && m.payloadBase64 === b64(text), { since, timeoutMs })
}
const countFrames = (c, pred) => c.frames.filter(pred).length

/** Resolves true once at least `n` frames matching `pred` exist at/after `since` (false on timeout). */
async function waitForCount(c, pred, n, since = 0, timeoutMs = 4000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (c.frames.slice(since).filter(pred).length >= n) return true
    await wait(10)
  }
  return false
}

/** True iff this socket holds no seat: a message from it is answered "Not in a room". */
async function notInRoom(c) {
  const since = c.mark()
  c.send({ type: 'message', toPlayerId: null, payloadBase64: b64('probe') })
  const r = await waitFor(c, (m) => m.type === 'error' || m.type === 'message', { since })
  return !!r && r.type === 'error' && /not in a room/i.test(r.message)
}

const minted = [] // every reconnectToken any seat was ever given, for the uniqueness + log-leak checks
function noteToken(t) { if (typeof t === 'string') minted.push(t) }

/** Hosts a room. The returned object doubles as the host's seat ({c, id, token}). */
async function hostRoom(id, relay = LONG, extra = {}) {
  const c = await open(relay)
  const hosted = await request(c, { type: 'host', playerId: id, displayName: id, ...extra })
  if (!hosted || hosted.type !== 'hosted') throw new Error(`hosting as ${id} failed: ${JSON.stringify(hosted)}`)
  noteToken(hosted.reconnectToken)
  return { c, id, relay, roomCode: hosted.roomCode, token: hosted.reconnectToken, hosted }
}

/** Joins `room` as a real seat (or a spectator with {spectator:true}); throws if the relay refuses. */
async function joinRoom(room, id, opts = {}) {
  const c = await open(room.relay)
  const joined = await request(c, { type: 'join', roomCode: room.roomCode, playerId: id, displayName: id, ...opts })
  if (!joined || joined.type !== 'joined') throw new Error(`joining as ${id} failed: ${JSON.stringify(joined)}`)
  noteToken(joined.reconnectToken)
  return { c, id, room, token: joined.reconnectToken, joined }
}

/** Drops a seat's socket the ugly way (no 'leave') and waits until every observer has been told. */
async function dropAndAwait(seat, observers) {
  seat.c.ws.terminate()
  for (const obs of observers) {
    const f = await waitFor(obs.c, (m) => m.type === 'playerDisconnected' && m.playerId === seat.id)
    if (!f) throw new Error(`observer never saw playerDisconnected for ${seat.id}`)
  }
}

/** A fresh socket re-joins `seat`'s id, presenting `token` (default: the seat's own real token). */
async function resume(room, seat, token = seat.token, extra = {}) {
  const c = await open(room.relay)
  const join = { type: 'join', roomCode: room.roomCode, playerId: seat.id, displayName: seat.id, ...extra }
  if (token !== undefined) join.reconnectToken = token
  const reply = await request(c, join)
  return { c, id: seat.id, room, token: seat.token, reply }
}

/** A fresh outsider socket tries one join; returns { c, reply }. */
async function tryJoin(room, join) {
  const c = await open(room.relay)
  const reply = await request(c, { type: 'join', roomCode: room.roomCode, displayName: 'Mallory', ...join })
  return { c, reply }
}

const isErr = (reply, message) => !!reply && reply.type === 'error' && (message === undefined || reply.message === message)
const isJoined = (r) => !!r && !!r.reply && r.reply.type === 'joined'
const isTok = (t) => typeof t === 'string' && t.length >= 32 && /^[A-Za-z0-9_-]+$/.test(t)

// ---------------------------------------------------------------------------------------------
// Scenarios. Each is independent (its own room, its own ids) so they can run side by side.
// ---------------------------------------------------------------------------------------------

/** [1] Tokens are issued to the right sockets, are long, and are never shared. */
async function s1Tokens() {
  const rA = await hostRoom('s1-hostA')
  const rB = await hostRoom('s1-hostB')
  const gA = await joinRoom(rA, 's1-guestA')
  const gB = await joinRoom(rB, 's1-guestB')
  const spec = await joinRoom(rA, 's1-spectator', { spectator: true })

  check('[1] host gets a reconnectToken (base64url, >= 32 chars) in hosted', isTok(rA.token), rA.hosted)
  check('[1] guest gets a reconnectToken (base64url, >= 32 chars) in joined', isTok(gA.token), gA.joined)
  check("[1] host's and guest's tokens differ", rA.token !== gA.token)
  check('[1] two separately hosted rooms hand their hosts different tokens', rA.token !== rB.token)
  check('[1] guests of two different rooms get different tokens', gA.token !== gB.token)
  check('[1] no two of the four real seats share a token', new Set([rA.token, rB.token, gA.token, gB.token]).size === 4)
  check("[1] hosted echoes the host's own playerId", rA.hosted.playerId === rA.id)
  check("[1] a spectator's joined carries hostPlayerId", spec.joined.hostPlayerId === rA.id, spec.joined)
  check(
    "[1] a spectator's joined has NO reconnectToken (not as a field, not anywhere in the raw frame)",
    !('reconnectToken' in spec.joined) && !spec.c.raw.some((t) => t.includes('reconnectToken')),
    spec.joined,
  )
  check(
    "[1] every joined (guests in two rooms, and the spectator) carries hostPlayerId == that room's creator",
    gA.joined.hostPlayerId === rA.id && gB.joined.hostPlayerId === rB.id && spec.joined.hostPlayerId === rA.id,
    [gA.joined.hostPlayerId, gB.joined.hostPlayerId],
  )
  check("[1] the spectator's roster is the real seats only (host + guestA, never itself)",
    spec.joined.players.length === 2 && !spec.joined.players.some((p) => p.playerId === spec.id))
}

/** [2] A token only ever reaches the socket that took the seat. */
async function s2NoLeak() {
  const host = await hostRoom('s2-host')
  const g1 = await joinRoom(host, 's2-g1')
  const g2 = await joinRoom(host, 's2-g2')
  const spec = await joinRoom(host, 's2-spectator', { spectator: true })
  const g3 = await joinRoom(host, 's2-g3') // late joiner: playerJoined to everyone, a roster in its own joined
  await dropAndAwait(g1, [host, g2, g3])
  const g1b = await resume(host, g1) // reconnected roster to g1, playerReconnected to everyone else
  await dropAndAwait(host, [g2, g3])
  const hostB = await resume(host, host)
  const outsider = await open()
  await request(outsider, { type: 'join', roomCode: host.roomCode, playerId: g1.id })
  await request(outsider, { type: 'join', roomCode: host.roomCode, playerId: host.id, reconnectToken: 'not-a-real-token' })
  // barrier: a broadcast sent last reaches every remaining socket after everything above was relayed
  sendMsg(hostB.c, null, 's2-barrier')
  await Promise.all([g1b, g2, g3, spec].map((s) => gotMessage(s.c, 's2-barrier')))

  const sockets = [
    { name: 'host (dropped socket)', c: host.c, own: host.token },
    { name: 'host (resumed socket)', c: hostB.c, own: host.token },
    { name: 'g1 (dropped socket)', c: g1.c, own: g1.token },
    { name: 'g1 (resumed socket)', c: g1b.c, own: g1.token },
    { name: 'g2', c: g2.c, own: g2.token },
    { name: 'g3', c: g3.c, own: g3.token },
    { name: 'spectator', c: spec.c, own: null },
    { name: 'outsider probing the room', c: outsider, own: null },
  ]
  const allTokens = [host.token, g1.token, g2.token, g3.token]
  const leaks = []
  for (const s of sockets) {
    for (const t of allTokens) {
      if (t === s.own) continue
      if (s.c.raw.some((text) => text.includes(t))) leaks.push(`${s.name} saw a token it does not own`)
    }
  }
  check('[2] no socket ever receives another seat\'s token in any raw frame', leaks.length === 0, leaks)
  check(
    '[2] the scan was not vacuous: the spectator saw playerJoined (g3), playerDisconnected and playerReconnected, and the resumed sockets saw a reconnected roster',
    countFrames(spec.c, (m) => m.type === 'playerJoined') === 1 && // it joined after g1/g2, so only g3's join was broadcast to it
      countFrames(spec.c, (m) => m.type === 'playerDisconnected') >= 2 &&
      countFrames(spec.c, (m) => m.type === 'playerReconnected') >= 2 &&
      g1b.reply.type === 'reconnected' && hostB.reply.type === 'reconnected' && g3.joined.players.length === 3,
  )
  check(
    '[2] each owner did receive its own token (in hosted / joined) — the seat was really issued one',
    host.c.raw.some((t) => t.includes(host.token)) && g1.c.raw.some((t) => t.includes(g1.token)) &&
      g2.c.raw.some((t) => t.includes(g2.token)) && g3.c.raw.some((t) => t.includes(g3.token)),
  )
  check('[2] an outsider who only ever collected error frames holds no token at all', outsider.raw.every((t) => !allTokens.some((tok) => t.includes(tok))))
}

/** [3] Honest reconnects with the token, for a guest seat and for the host seat. */
async function s3HonestReconnect() {
  const host = await hostRoom('s3-host')
  const guest = await joinRoom(host, 's3-guest')
  const other = await joinRoom(host, 's3-other')

  // ---- guest seat ----
  await dropAndAwait(guest, [host, other])
  const hostMark = host.c.mark(), otherMark = other.c.mark()
  const resumed = await resume(host, guest)
  check(
    '[3] guest re-joins within grace WITH its token => reconnected, for the same room, with the others in the roster and not itself',
    resumed.reply.type === 'reconnected' && resumed.reply.roomCode === host.roomCode &&
      resumed.reply.players.some((p) => p.playerId === host.id) && resumed.reply.players.some((p) => p.playerId === other.id) &&
      !resumed.reply.players.some((p) => p.playerId === guest.id),
    resumed.reply,
  )
  const hostSaw = await waitFor(host.c, (m) => m.type === 'playerReconnected' && m.playerId === guest.id, { since: hostMark })
  const otherSaw = await waitFor(other.c, (m) => m.type === 'playerReconnected' && m.playerId === guest.id, { since: otherMark })
  check('[3] the other members get playerReconnected for the guest', !!hostSaw && !!otherSaw)
  check(
    '[3] ...and NOT a fresh playerJoined',
    !host.c.frames.slice(hostMark).some((m) => m.type === 'playerJoined') && !other.c.frames.slice(otherMark).some((m) => m.type === 'playerJoined'),
  )
  const mk = host.c.mark()
  sendMsg(resumed.c, host.id, 's3-from-resumed-guest')
  const gm = await gotMessage(host.c, 's3-from-resumed-guest', mk)
  check("[3] the resumed guest socket's next message is delivered, stamped with the SAME playerId", !!gm && gm.fromPlayerId === guest.id, gm)
  const rk = resumed.c.mark()
  sendMsg(host.c, guest.id, 's3-to-resumed-guest')
  const toGuest = await gotMessage(resumed.c, 's3-to-resumed-guest', rk)
  check('[3] a message addressed to the guest reaches its resumed socket (host-stamped)', !!toGuest && toGuest.fromPlayerId === host.id, toGuest)
  const ok = other.c.mark()
  sendMsg(resumed.c, null, 's3-broadcast-from-resumed-guest')
  const bm = await gotMessage(other.c, 's3-broadcast-from-resumed-guest', ok)
  check('[3] a broadcast from the resumed guest reaches the others, stamped with the guest id', !!bm && bm.fromPlayerId === guest.id)

  // the token stays valid: a SECOND drop and resume with the very same token works too
  const guestSeat2 = { c: resumed.c, id: guest.id, token: guest.token }
  await dropAndAwait(guestSeat2, [host, other])
  const resumed2 = await resume(host, guest)
  check('[3] the same token resumes the seat a second time (it is not single-use)', resumed2.reply.type === 'reconnected', resumed2.reply)

  // an honest live duplicate is still a duplicate: the seat is now held again, a further resume is refused
  const dupe = await resume(host, guest)
  check('[3] once resumed, the seat is live again: a further resume (even with the token) is refused', isErr(dupe.reply, IN_ROOM), dupe.reply)

  // ---- host seat ----
  await dropAndAwait(host, [other])
  const otherMark2 = other.c.mark()
  const hostResumed = await resume(host, host)
  check(
    '[3] the HOST seat re-joins within grace with its token => reconnected, roster lists the guests but not the host',
    hostResumed.reply.type === 'reconnected' && hostResumed.reply.players.some((p) => p.playerId === other.id) &&
      !hostResumed.reply.players.some((p) => p.playerId === host.id),
    hostResumed.reply,
  )
  check(
    '[3] the guests get playerReconnected (not playerJoined) for the host',
    !!(await waitFor(other.c, (m) => m.type === 'playerReconnected' && m.playerId === host.id, { since: otherMark2 })) &&
      !other.c.frames.slice(otherMark2).some((m) => m.type === 'playerJoined'),
  )
  const om = other.c.mark()
  sendMsg(hostResumed.c, other.id, 's3-from-resumed-host')
  const hm = await gotMessage(other.c, 's3-from-resumed-host', om)
  check("[3] the resumed host's message reaches a guest stamped with the HOST's playerId", !!hm && hm.fromPlayerId === host.id, hm)
  const hrk = hostResumed.c.mark()
  sendMsg(other.c, host.id, 's3-to-resumed-host')
  const toHost = await gotMessage(hostResumed.c, 's3-to-resumed-host', hrk)
  check('[3] a guest can address the resumed host and be delivered', !!toHost && toHost.fromPlayerId === other.id)
}

/** [3b] A resumed seat must not be vacated when the grace timer of its earlier drop would have fired
 *  (runs against the short-grace relay so the old timer's deadline is actually reached). */
async function s3bResumeSurvivesOldTimer() {
  for (let attempt = 0; attempt < 3; attempt++) {
    const host = await hostRoom(`s3b-host-${attempt}`, SHORT)
    const g = await joinRoom(host, `s3b-g-${attempt}`)
    await dropAndAwait(g, [host])
    const back = await resume(host, g)
    if (back.reply.type !== 'reconnected') continue // the machine stalled past the grace window before the resume: start over
    const hostMark = host.c.mark()
    await wait(SHORT_GRACE_MS + 700) // comfortably past when the ORIGINAL grace timer would have fired
    check('[3] a resumed seat is NOT vacated when its old grace timer would have fired (no playerLeft)', !host.c.frames.slice(hostMark).some((m) => m.type === 'playerLeft'))
    const stillLive = await tryJoin(host, { playerId: g.id })
    check('[3] ...and the seat is still held live afterwards (a duplicate gets the live-seat error, not the retired-seat one)', isErr(stillLive.reply, IN_ROOM), stillLive.reply)
    const mk = host.c.mark()
    sendMsg(back.c, host.id, 's3b-still-here')
    const m = await gotMessage(host.c, 's3b-still-here', mk)
    check('[3] ...and it still speaks with its own stamp', !!m && m.fromPlayerId === g.id)
    const bk = back.c.mark()
    sendMsg(host.c, g.id, 's3b-for-guest')
    check('[3] ...and still receives traffic addressed to it', !!(await gotMessage(back.c, 's3b-for-guest', bk)))
    return
  }
  check('[3] a resumed seat survives its old grace timer', false, 'could not resume inside the grace window in 3 attempts')
}

/** [4] ATTACK A: grace-window hijack of a GUEST seat with nothing but the public playerId. */
async function s4GraceHijackGuest() {
  const host = await hostRoom('s4-host')
  const victim = await joinRoom(host, 's4-victim')
  const bystander = await joinRoom(host, 's4-bystander')
  await dropAndAwait(victim, [host, bystander])

  const hostMark = host.c.mark(), byMark = bystander.c.mark()
  const atk = await tryJoin(host, { playerId: victim.id }) // knows only the (public) id
  check('[4] ATTACK A: a token-less join of a seat that is mid-grace is rejected with the plain duplicate error', isErr(atk.reply, IN_ROOM), atk.reply)
  check('[4] ATTACK A: the seat was NOT taken — the attacker socket is in no room (its message is answered "Not in a room")', await notInRoom(atk.c))
  const spoofMark = atk.c.mark()
  sendMsg(atk.c, host.id, 's4-spoof-as-victim')
  sendMsg(atk.c, null, 's4-spoof-broadcast')
  // both spoofs must be answered "Not in a room" before the next request, or its reply would be mistaken for theirs
  check('[4] ATTACK A: the attacker\'s spoofed directed + broadcast messages are each answered "Not in a room", not relayed',
    await waitForCount(atk.c, (m) => m.type === 'error' && /not in a room/i.test(m.message), 2, spoofMark))
  const spec = await request(atk.c, { type: 'join', roomCode: host.roomCode, playerId: victim.id, spectator: true })
  check('[4] ATTACK A: retrying as a spectator with the dropped seat\'s id is rejected too', isErr(spec, IN_ROOM), spec)
  const lower = await request(atk.c, { type: 'join', roomCode: host.roomCode.toLowerCase(), playerId: victim.id })
  check('[4] ATTACK A: lower-casing the room code does not get around it', isErr(lower, IN_ROOM), lower)
  check('[4] ATTACK A: none of the attacker\'s replies is a joined/reconnected', !atk.c.frames.some((m) => m.type === 'joined' || m.type === 'reconnected'))

  const owner = await resume(host, victim) // the honest owner comes back afterwards
  check('[4] ATTACK A: the genuine owner still reconnects afterwards with its token', owner.reply.type === 'reconnected', owner.reply)
  const mk = host.c.mark()
  sendMsg(owner.c, host.id, 's4-owner-hello')
  const hello = await gotMessage(host.c, 's4-owner-hello', mk)
  check('[4] ...and speaks with its own stamp', !!hello && hello.fromPlayerId === victim.id)
  check(
    '[4] ATTACK A: the host saw exactly ONE playerReconnected for the victim (the owner\'s) and never the attacker\'s spoofed traffic',
    countFrames(host.c, (m) => m.type === 'playerReconnected' && m.playerId === victim.id) === 1 &&
      !host.c.frames.slice(hostMark).some((m) => m.type === 'message' && (m.payloadBase64 === b64('s4-spoof-as-victim') || m.payloadBase64 === b64('s4-spoof-broadcast'))),
  )
  check(
    '[4] ATTACK A: the bystander never received the attacker\'s spoofed broadcast',
    !bystander.c.frames.slice(byMark).some((m) => m.type === 'message' && m.payloadBase64 === b64('s4-spoof-broadcast')),
  )
}

/** [5] ATTACK B: hijack of the HOST seat. The host's id is in every guest's roster. */
async function s5HostSeatHijack() {
  // ---- setup + baseline: what probing a LIVE host seat looks like (so grace-window answers can be compared: no oracle)
  const base = await hostRoom('s5-base-host')
  const baseGuest = await joinRoom(base, 's5-base-guest')
  check("[5] setup: the host's public id is visible to a guest (its joined roster lists it)", baseGuest.joined.players.some((p) => p.playerId === base.id) && baseGuest.joined.hostPlayerId === base.id)
  const liveProbe = await tryJoin(base, { playerId: base.id, reconnectToken: 'A'.repeat(32) })
  check('[5] control: probing the LIVE host seat is refused with the duplicate error', isErr(liveProbe.reply, IN_ROOM), liveProbe.reply)
  const liveReplyText = JSON.stringify(liveProbe.reply)

  // The token variants. (A relay that issued no token at all would leave host.token undefined; the
  // placeholder keeps every attack executing so a broken relay fails these checks because the hijack
  // works, not because the test threw.)
  const flip = (t, i) => t.slice(0, i) + (t[i] === 'A' ? 'B' : 'A') + t.slice(i + 1)
  const variantsFor = (host, otherGuest) => {
    const realTok = isTok(host.token) ? host.token : 'T'.repeat(32)
    return [
      ['no token', undefined],
      ['empty-string token', ''],
      ['wrong same-length token (all A)', 'A'.repeat(realTok.length)],
      ['real token with first char changed', flip(realTok, 0)],
      ['real token with LAST char changed', flip(realTok, realTok.length - 1)],
      ['wrong-length token (3 chars)', 'abc'],
      ['real token truncated by one char', realTok.slice(0, -1)],
      ['real token with a trailing char', realTok + 'A'],
      ['huge token (50000 chars)', 'x'.repeat(50000)],
      ['numeric token', 12345],
      ['object token', { a: 1 }],
      ['array token wrapping the real one', [realTok]],
      ['boolean token', true],
      ['null token', null],
      ["ANOTHER seat's valid token (a live guest's)", otherGuest.token],
    ]
  }
  const variantCount = variantsFor(base, baseGuest).length

  // Every variant gets its OWN room and its own dropped host, so one variant that happened to work
  // could never mask another (once a seat is hijacked every later attempt just meets a live seat).
  // After each attack the REAL host must still be able to come back and speak as the host.
  const failures = { outsider: [], insider: [], spectator: [], lockedOut: [], stamped: [], noise: [] }
  async function attackOnce(kind, index, name, tokenFor) {
    const tag = `s5-${kind}-${index}`
    const host = await hostRoom(`${tag}-host`)
    const guestA = await joinRoom(host, `${tag}-A`)
    const guestB = await joinRoom(host, `${tag}-B`)
    await dropAndAwait(host, [guestA, guestB])
    let atkSock, reply
    if (kind === 'insider') {
      // a seated guest: it learned the host id from its own roster. The relay makes a socket leave its seat before processing any join.
      const insider = await joinRoom(host, `${tag}-atk`)
      atkSock = insider.c
      const token = tokenFor(host, guestB, insider)
      const join = { type: 'join', roomCode: host.roomCode, playerId: host.id }
      if (token !== undefined) join.reconnectToken = token
      reply = await request(atkSock, join)
    } else {
      atkSock = await open()
      const token = tokenFor(host, guestB, null)
      const join = { type: 'join', roomCode: host.roomCode, playerId: host.id }
      if (kind === 'spectator') join.spectator = true
      if (token !== undefined) join.reconnectToken = token
      reply = await request(atkSock, join)
    }
    if (!isErr(reply, IN_ROOM) || JSON.stringify(reply) !== liveReplyText || !(await notInRoom(atkSock))) failures[kind].push(`${name}: ${JSON.stringify(reply)}`)
    // the real host returns and the room carries on with the right stamps
    const gk = guestA.c.mark()
    const back = await resume(host, host)
    if (back.reply.type !== 'reconnected') { failures.lockedOut.push(`${kind}/${name}: ${JSON.stringify(back.reply)}`); return }
    sendMsg(back.c, guestA.id, `${tag}-host-speaks`)
    const hs = await gotMessage(guestA.c, `${tag}-host-speaks`, gk)
    const hk = back.c.mark()
    sendMsg(guestA.c, host.id, `${tag}-guest-replies`)
    const gr = await gotMessage(back.c, `${tag}-guest-replies`, hk)
    if (!hs || hs.fromPlayerId !== host.id || !gr || gr.fromPlayerId !== guestA.id) failures.stamped.push(`${kind}/${name}`)
    if (countFrames(guestA.c, (m) => m.type === 'playerReconnected' && m.playerId === host.id) !== 1 ||
        countFrames(guestB.c, (m) => m.type === 'playerReconnected' && m.playerId === host.id) !== 1) failures.noise.push(`${kind}/${name}`)
  }

  const outsiderVariants = variantsFor(base, baseGuest)
  for (let i = 0; i < outsiderVariants.length; i++) {
    await attackOnce('outsider', i, outsiderVariants[i][0], (host, other) => variantsFor(host, other)[i][1])
  }
  const insiderNames = [...outsiderVariants.map(([n]) => n), "the attacker's OWN valid guest token"]
  for (let i = 0; i < insiderNames.length; i++) {
    await attackOnce('insider', i, insiderNames[i], (host, other, insider) =>
      i < outsiderVariants.length ? variantsFor(host, other)[i][1] : insider.token)
  }
  await attackOnce('spectator', 0, 'spectator join, no token', () => undefined)
  await attackOnce('spectator', 1, 'spectator join, wrong token', () => 'S'.repeat(32))

  check(`[5] ATTACK B (outsider): all ${variantCount} token variants against the host's seat in grace are refused, identically to a live-seat probe, and seat nothing`, failures.outsider.length === 0, failures.outsider)
  check(`[5] ATTACK B (insider guest): the same ${variantCount} attacks from a seated guest, plus its own valid guest token, are refused identically and leave it with no seat`, failures.insider.length === 0, failures.insider)
  check("[5] ATTACK B: joining as a spectator with the host's id (to receive host-addressed traffic) is refused, with and without a token", failures.spectator.length === 0, failures.spectator)
  check('[5] ATTACK B: after every one of those attacks the real host still reconnects with its own token (no attack locks it out)', failures.lockedOut.length === 0, failures.lockedOut)
  check('[5] ATTACK B: ...and the room continues with the host stamped as the host and the guest stamped as the guest', failures.stamped.length === 0, failures.stamped)
  check('[5] ATTACK B: ...and exactly one playerReconnected for the host reached each guest (the genuine one), never one caused by an attacker', failures.noise.length === 0, failures.noise)
}

/** Every way a vacated (retired) id must be refused. Returns the list of failures. */
async function retiredFailures(room, id, tokens) {
  const failures = []
  for (const token of tokens) {
    for (const spectator of [false, true]) {
      const join = { playerId: id }
      if (token !== undefined) join.reconnectToken = token
      if (spectator) join.spectator = true
      const atk = await tryJoin(room, join)
      if (!isErr(atk.reply, RETIRED) || !(await notInRoom(atk.c))) {
        failures.push(`${spectator ? 'spectator' : 'seat'} join, token ${JSON.stringify(token)?.slice(0, 12)}: ${JSON.stringify(atk.reply)}`)
      }
    }
  }
  return failures
}

/** [6a] ATTACK C after GRACE EXPIRY (runs against the short-grace relay). */
async function s6aVacatedByExpiry() {
  const host = await hostRoom('s6a-host', SHORT)
  const g = await joinRoom(host, 's6a-g')
  const bystander = await joinRoom(host, 's6a-bystander')
  await dropAndAwait(g, [host, bystander])
  const left = await waitFor(host.c, (m) => m.type === 'playerLeft' && m.playerId === g.id, { timeoutMs: SHORT_GRACE_MS + 6000 })
  check('[6] grace expiry vacates the seat (playerLeft is announced)', !!left)

  const failures = await retiredFailures(host, g.id, [undefined, g.token, ''])
  check('[6] ATTACK C: after grace expiry, a fresh join (seat AND spectator) with the vacated id is refused as retired — even presenting the old token', failures.length === 0, failures)
  const tooLate = await resume(host, g)
  check('[6] the too-late owner (honest client, valid old token) is refused rather than silently re-seated', isErr(tooLate.reply, RETIRED), tooLate.reply)

  const fresh = await joinRoom(host, 's6a-brand-new')
  check('[6] a brand-new id joins fine, sees the retired seat gone from its roster and the real hostPlayerId',
    fresh.joined.hostPlayerId === host.id && !fresh.joined.players.some((p) => p.playerId === g.id) && fresh.joined.players.length === 2, fresh.joined)
  check('[6] ...and the bystander was told it joined', !!(await waitFor(bystander.c, (m) => m.type === 'playerJoined' && m.playerId === 's6a-brand-new')))

  // a spectator with a DIFFERENT id must not be handed traffic addressed to the retired id
  const spy = await joinRoom(host, 's6a-spy', { spectator: true })
  const spyMark = spy.c.mark(), freshMark = fresh.c.mark()
  sendMsg(host.c, g.id, 's6a-hand-for-retired-seat')
  sendMsg(host.c, null, 's6a-barrier')
  await gotMessage(spy.c, 's6a-barrier', spyMark)
  await gotMessage(fresh.c, 's6a-barrier', freshMark)
  check('[6] a message addressed to the retired seat is delivered to nobody (not the spectator, not the newcomer)',
    !spy.c.frames.some((m) => m.payloadBase64 === b64('s6a-hand-for-retired-seat')) && !fresh.c.frames.some((m) => m.payloadBase64 === b64('s6a-hand-for-retired-seat')))
}

/** [6b] ATTACK C after an EXPLICIT LEAVE (guest seat). */
async function s6bVacatedByLeave() {
  const host = await hostRoom('s6b-host')
  const g = await joinRoom(host, 's6b-g')
  const bystander = await joinRoom(host, 's6b-bystander')
  g.c.send({ type: 'leave' })
  const left = await waitFor(host.c, (m) => m.type === 'playerLeft' && m.playerId === g.id)
  check('[6] an explicit leave vacates the seat at once (playerLeft, no grace)', !!left && !!(await waitFor(bystander.c, (m) => m.type === 'playerLeft' && m.playerId === g.id)))

  // the SAME socket that left tries to take its own id straight back, with its token
  const same = await request(g.c, { type: 'join', roomCode: host.roomCode, playerId: g.id, reconnectToken: g.token })
  check('[6] ATTACK C: the leaving socket cannot re-take its id with its old token', isErr(same, RETIRED), same)
  const failures = await retiredFailures(host, g.id, [undefined, g.token])
  check('[6] ATTACK C: after an explicit leave, a fresh join (seat AND spectator) with the vacated id is refused — with or without the old token', failures.length === 0, failures)
  const fresh = await joinRoom(host, 's6b-brand-new')
  check('[6] a brand-new id still joins fine', fresh.joined.hostPlayerId === host.id)
}

/** [6c] ATTACK C on the HOST id while guests remain: by explicit leave, and by grace expiry. */
async function s6cHostRetired(viaExpiry) {
  const relay = viaExpiry ? SHORT : LONG
  const tag = viaExpiry ? 's6c-exp' : 's6c-leave'
  const how = viaExpiry ? 'grace expiry' : 'an explicit leave'
  const host = await hostRoom(`${tag}-host`, relay)
  const g1 = await joinRoom(host, `${tag}-g1`)
  const g2 = await joinRoom(host, `${tag}-g2`)
  if (viaExpiry) await dropAndAwait(host, [g1, g2])
  else host.c.send({ type: 'leave' })
  const left1 = await waitFor(g1.c, (m) => m.type === 'playerLeft' && m.playerId === host.id, { timeoutMs: SHORT_GRACE_MS + 6000 })
  const left2 = await waitFor(g2.c, (m) => m.type === 'playerLeft' && m.playerId === host.id, { timeoutMs: SHORT_GRACE_MS + 6000 })
  check(`[6] the host seat is vacated by ${how}: both remaining guests get playerLeft(host)`, !!left1 && !!left2)

  const failures = await retiredFailures(host, host.id, [undefined, host.token, g1.token])
  check(`[6] ATTACK C: with the host id retired by ${how}, taking it as a seat or as a spectator is refused — even with the host's own old token`, failures.length === 0, failures)
  const lateHost = await resume(host, host)
  check(`[6] the too-late host itself (valid token) is refused too`, isErr(lateHost.reply, RETIRED), lateHost.reply)

  const g3 = await joinRoom(host, `${tag}-g3`)
  check(`[6] a brand-new guest still joins the host-less room; its joined names the retired host and its roster omits it`,
    g3.joined.hostPlayerId === host.id && !g3.joined.players.some((p) => p.playerId === host.id) && g3.joined.players.length === 2, g3.joined)

  // nobody can get a frame stamped with the retired host id, even by claiming it in the frame
  const mk1 = g1.c.mark(), mk2 = g2.c.mark()
  sendMsg(g3.c, null, `${tag}-claims-to-be-host`, { fromPlayerId: host.id })
  await gotMessage(g1.c, `${tag}-claims-to-be-host`, mk1)
  await gotMessage(g2.c, `${tag}-claims-to-be-host`, mk2)
  const claimed = g1.c.frames.find((m) => m.type === 'message' && m.payloadBase64 === b64(`${tag}-claims-to-be-host`))
  check('[6] after the host seat is retired, a frame claiming fromPlayerId=host is still stamped with the real sender', !!claimed && claimed.fromPlayerId === g3.id, claimed)
  check('[6] no frame stamped with the retired host id reached any guest after it left', !g1.c.frames.some((m) => m.type === 'message' && m.fromPlayerId === host.id) && !g2.c.frames.some((m) => m.type === 'message' && m.fromPlayerId === host.id))
}

/** [7] A live seat cannot be duplicated, token or no token. */
async function s7LiveDuplicate() {
  const host = await hostRoom('s7-host')
  const guest = await joinRoom(host, 's7-guest')
  const gk = guest.c.mark()
  const noTok = await tryJoin(host, { playerId: guest.id })
  const wrong = await tryJoin(host, { playerId: guest.id, reconnectToken: 'B'.repeat(32) })
  const valid = await tryJoin(host, { playerId: guest.id, reconnectToken: guest.token })
  const hostValid = await tryJoin(host, { playerId: host.id, reconnectToken: host.token })
  check('[7] a live seat refuses a token-less duplicate', isErr(noTok.reply, IN_ROOM), noTok.reply)
  check('[7] a live seat refuses a wrong-token duplicate', isErr(wrong.reply, IN_ROOM), wrong.reply)
  check('[7] a live seat refuses a duplicate that presents the VALID token while the old socket is live', isErr(valid.reply, IN_ROOM), valid.reply)
  check('[7] the live HOST seat refuses a duplicate presenting the host\'s valid token too', isErr(hostValid.reply, IN_ROOM), hostValid.reply)
  check('[7] none of the refused duplicates holds a seat', (await notInRoom(valid.c)) && (await notInRoom(hostValid.c)))
  check('[7] the original socket is untouched: still open and never told anything changed', guest.c.ws.readyState === WebSocket.OPEN && !guest.c.frames.slice(gk).some((m) => m.type === 'playerLeft' || m.type === 'playerDisconnected'))
  const hk = host.c.mark()
  sendMsg(guest.c, host.id, 's7-still-mine')
  const still = await gotMessage(host.c, 's7-still-mine', hk)
  check('[7] the original socket still speaks as the guest', !!still && still.fromPlayerId === guest.id)
  const ok = guest.c.mark()
  sendMsg(host.c, guest.id, 's7-for-original')
  const forOrig = await gotMessage(guest.c, 's7-for-original', ok)
  check('[7] traffic addressed to the seat still reaches the ORIGINAL socket, not the refused duplicate', !!forOrig && !valid.c.frames.some((m) => m.type === 'message'))
}

/** [8] Spectator/member id collisions in both orders, and honest spectator re-join. */
async function s8Collisions() {
  const host = await hostRoom('s8-host')

  // spectator first, then a real join with that id
  const specFirst = await joinRoom(host, 's8-x1', { spectator: true })
  const realOverSpec = await tryJoin(host, { playerId: 's8-x1' })
  check('[8] spectator first, then a real-seat join with the same id => refused', isErr(realOverSpec.reply, IN_ROOM), realOverSpec.reply)
  const realOverSpecTok = await tryJoin(host, { playerId: 's8-x1', reconnectToken: 'C'.repeat(32) })
  check('[8] ...and a token does not help (a spectator has no seat token to present)', isErr(realOverSpecTok.reply, IN_ROOM), realOverSpecTok.reply)
  check('[8] ...the spectator itself is untouched (still receives broadcasts)', await (async () => {
    const mk = specFirst.c.mark(); sendMsg(host.c, null, 's8-bcast-1'); return !!(await gotMessage(specFirst.c, 's8-bcast-1', mk))
  })())

  // real first, then a spectator join with that id
  const member = await joinRoom(host, 's8-m1')
  const specOverReal = await tryJoin(host, { playerId: member.id, spectator: true })
  check('[8] real seat first, then a spectator join with the same id => refused', isErr(specOverReal.reply, IN_ROOM), specOverReal.reply)
  const specOverRealTok = await tryJoin(host, { playerId: member.id, spectator: true, reconnectToken: member.token })
  check("[8] ...even presenting the seat's valid token as a spectator", isErr(specOverRealTok.reply, IN_ROOM), specOverRealTok.reply)

  // two spectators, same id
  const dupSpec = await tryJoin(host, { playerId: specFirst.id, spectator: true })
  check('[8] a second spectator with an id a spectator already holds => refused', isErr(dupSpec.reply, IN_ROOM), dupSpec.reply)

  // spectator with the id of a seat that is mid-grace (the member is still in the map)
  const dropper = await joinRoom(host, 's8-dropper')
  await dropAndAwait(dropper, [host])
  const specOverGrace = await tryJoin(host, { playerId: dropper.id, spectator: true })
  check('[8] a spectator join with the id of a seat that is in its grace window => refused', isErr(specOverGrace.reply, IN_ROOM), specOverGrace.reply)
  const dropperBack = await resume(host, dropper)
  check('[8] ...and the dropped seat\'s owner still resumes', dropperBack.reply.type === 'reconnected', dropperBack.reply)

  // honest control: a spectator that drops re-joins with the SAME id and it works (spectators are never retired)
  const spec = await joinRoom(host, 's8-honest-spec', { spectator: true })
  spec.c.ws.terminate()
  let rejoined = null
  for (let attempt = 0; attempt < 40 && !isJoined(rejoined); attempt++) {
    // The relay only frees the id once it has processed the close; a fresh socket per attempt keeps a
    // lost race from counting against one socket's join-failure budget.
    rejoined = await tryJoin(host, { playerId: spec.id, spectator: true })
    if (!isJoined(rejoined)) { rejoined.c.ws.terminate(); await wait(25) }
  }
  check('[8] control: a spectator that dropped re-joins fresh with the same id', isJoined(rejoined) && rejoined.reply.hostPlayerId === host.id && !('reconnectToken' in rejoined.reply), rejoined && rejoined.reply)
  const mk = rejoined.c.mark()
  sendMsg(host.c, null, 's8-bcast-2')
  check('[8] control: the re-joined spectator receives broadcasts again', !!(await gotMessage(rejoined.c, 's8-bcast-2', mk)))

  // a spectator's id is free again once it leaves, also as a real seat (spectator ids are not retired)
  rejoined.c.send({ type: 'leave' })
  let asSeat = null
  for (let attempt = 0; attempt < 40 && !isJoined(asSeat); attempt++) {
    asSeat = await tryJoin(host, { playerId: spec.id })
    if (!isJoined(asSeat)) { asSeat.c.ws.terminate(); await wait(25) }
  }
  check('[8] a spectator id is not retired: after the spectator leaves, the id can be a fresh real seat (with its own new token)', isJoined(asSeat) && isTok(asSeat.reply.reconnectToken), asSeat && asSeat.reply)
  if (isJoined(asSeat)) noteToken(asSeat.reply.reconnectToken)
}

/** [9] A client can never choose the 'fromPlayerId' its frames are relayed under. */
async function s9Spoofing() {
  const host = await hostRoom('s9-host')
  const a = await joinRoom(host, 's9-a')
  const b = await joinRoom(host, 's9-b')
  const spec = await joinRoom(host, 's9-spectator', { spectator: true })
  const forged = {
    fromPlayerId: host.id, from: host.id, playerId: host.id, senderId: host.id,
    reconnectToken: host.token, hostPlayerId: 'evil', type2: 'x',
  }

  const bk = b.c.mark()
  sendMsg(a.c, b.id, 's9-directed', forged)
  const directed = await gotMessage(b.c, 's9-directed', bk)
  check('[9] directed send: a member claiming fromPlayerId=host is delivered stamped with ITS OWN id', !!directed && directed.fromPlayerId === a.id, directed)
  check('[9] directed send: only type/fromPlayerId/payloadBase64 are relayed — forged extra fields are dropped',
    !!directed && JSON.stringify(Object.keys(directed).sort()) === JSON.stringify(['fromPlayerId', 'payloadBase64', 'type']), directed && Object.keys(directed))

  const marks = [[host, host.c.mark()], [b, b.c.mark()], [spec, spec.c.mark()]]
  sendMsg(a.c, null, 's9-broadcast', forged)
  const seen = []
  for (const [s, mk] of marks) seen.push(await gotMessage(s.c, 's9-broadcast', mk))
  check('[9] broadcast: the host, a guest and a spectator all see the sender\'s own id, never the forged host id', seen.every((m) => !!m && m.fromPlayerId === a.id), seen)

  const hk = host.c.mark()
  sendMsg(a.c, host.id, 's9-to-host', { fromPlayerId: b.id })
  const toHost = await gotMessage(host.c, 's9-to-host', hk)
  check('[9] a message to the host claiming to be another guest is stamped with the true sender', !!toHost && toHost.fromPlayerId === a.id, toHost)

  const specDirected = spec.c.mark()
  sendMsg(a.c, spec.id, 's9-to-spectator', { fromPlayerId: host.id })
  const toSpec = await gotMessage(spec.c, 's9-to-spectator', specDirected)
  check('[9] a directed message to a spectator is stamped with the true sender', !!toSpec && toSpec.fromPlayerId === a.id, toSpec)

  // a SPECTATOR speaking (it must be able to: it sends RequestState to the host) still cannot borrow an identity
  const hk2 = host.c.mark()
  sendMsg(spec.c, host.id, 's9-spectator-speaks', { fromPlayerId: host.id })
  const fromSpec = await gotMessage(host.c, 's9-spectator-speaks', hk2)
  check("[9] a spectator claiming fromPlayerId=host is stamped with the spectator's own id", !!fromSpec && fromSpec.fromPlayerId === spec.id, fromSpec)

  // non-string / structured claims
  const bk2 = b.c.mark()
  sendMsg(a.c, b.id, 's9-object-claim', { fromPlayerId: { playerId: host.id } })
  const objClaim = await gotMessage(b.c, 's9-object-claim', bk2)
  check('[9] a structured fromPlayerId claim is ignored too', !!objClaim && objClaim.fromPlayerId === a.id, objClaim)

  // raw frame with the claim written before "type" and duplicated keys
  const bk3 = b.c.mark()
  a.c.sendRaw(`{"fromPlayerId":"${host.id}","toPlayerId":"${b.id}","fromPlayerId":"${host.id}","payloadBase64":"${b64('s9-raw')}","type":"message"}`)
  const rawClaim = await gotMessage(b.c, 's9-raw', bk3)
  check('[9] a hand-built frame with the claim first (and duplicated) is stamped with the true sender', !!rawClaim && rawClaim.fromPlayerId === a.id, rawClaim)

  // and after a reconnect the resumed socket is stamped with the seat's id, not anything it sends
  await dropAndAwait(a, [host, b])
  const aBack = await resume(host, a)
  const bk4 = b.c.mark()
  sendMsg(aBack.c, b.id, 's9-after-resume', { fromPlayerId: host.id })
  const afterResume = await gotMessage(b.c, 's9-after-resume', bk4)
  check('[9] a resumed socket that claims fromPlayerId=host is still stamped with its own seat id', !!afterResume && afterResume.fromPlayerId === a.id, afterResume)
}

/** [10a] playerId length cap, host and join, at the boundary. */
async function s10aLengthCap() {
  const id64 = 'h'.repeat(64), id65 = 'h'.repeat(65)
  const okHost = await hostRoom(id64)
  check('[10] host with a 64-char playerId is accepted', okHost.hosted.playerId === id64 && isTok(okHost.token))

  const badHostSock = await open()
  const badHost = await request(badHostSock, { type: 'host', playerId: id65 })
  check('[10] host with a 65-char playerId is rejected with the length error', isErr(badHost, 'playerId must be at most 64 characters'), badHost)
  check('[10] ...and the rejected host socket holds no room/seat', await notInRoom(badHostSock))
  const recovered = await request(badHostSock, { type: 'host', playerId: 'ok-after-length-error' })
  check('[10] ...and the same socket can then host normally (the error did not poison it)', !!recovered && recovered.type === 'hosted', recovered)
  const huge = await request(await open(), { type: 'host', playerId: 'z'.repeat(10000) })
  check('[10] a 10000-char playerId is rejected', isErr(huge, 'playerId must be at most 64 characters'), huge)

  const g64 = await joinRoom(okHost, 'g'.repeat(64))
  check('[10] join with a 64-char playerId is accepted', g64.joined.reconnectToken === g64.token && isTok(g64.token))
  const bad = await tryJoin(okHost, { playerId: 'g'.repeat(65) })
  check('[10] join with a 65-char playerId is rejected with the length error', isErr(bad.reply, 'playerId must be at most 64 characters'), bad.reply)
  check('[10] ...and that socket holds no seat', await notInRoom(bad.c))
  const badSpec = await tryJoin(okHost, { playerId: 'g'.repeat(65), spectator: true })
  check('[10] a 65-char spectator id is rejected too', isErr(badSpec.reply, 'playerId must be at most 64 characters'), badSpec.reply)
  const badResume = await tryJoin(okHost, { playerId: 'g'.repeat(65), reconnectToken: g64.token })
  check('[10] a 65-char id with a valid token cannot alias the 64-char seat', isErr(badResume.reply, 'playerId must be at most 64 characters'), badResume.reply)
  const roster = await joinRoom(okHost, 's10a-late')
  check('[10] the rejected 65-char ids left nothing behind in the roster', roster.joined.players.length === 2, roster.joined.players)
}

/** [10b] The 64-distinct-seat-ids cap, reached by join/leave churn. */
async function s10bSeatIdCap() {
  const host = await hostRoom('s10b-host')
  const g0 = await joinRoom(host, 's10b-g0')
  // control: reconnecting the same seat over and over must NOT use up ids
  let seat = g0
  let reconnects = 0
  for (let i = 0; i < 6; i++) {
    await dropAndAwait(seat, [host])
    const r = await resume(host, g0)
    if (r.reply.type !== 'reconnected') break
    reconnects++
    seat = { c: r.c, id: g0.id, token: g0.token }
  }
  check('[10] control: six drop/resume cycles of one seat all succeed', reconnects === 6, reconnects)

  // host + g0 already account for 2 of the 64 ids; a churning socket takes 62 more
  const churner = await open()
  let joined = 0
  const churnFailures = []
  for (let i = 1; i <= 62; i++) {
    const reply = await request(churner, { type: 'join', roomCode: host.roomCode, playerId: `s10b-c${i}`, displayName: 'c' })
    if (reply && reply.type === 'joined') { joined++; noteToken(reply.reconnectToken) } else churnFailures.push(`c${i}: ${JSON.stringify(reply)}`)
    churner.send({ type: 'leave' })
  }
  check('[10] control: 62 further distinct guests joined and left one at a time, all accepted (64 ids used in total, resumes cost none)', joined === 62 && churnFailures.length === 0, churnFailures.slice(0, 3))

  const over = await tryJoin(host, { playerId: 's10b-one-too-many' })
  check("[10] the 65th distinct seat id is refused: 'Room is closed to new players'", isErr(over.reply, 'Room is closed to new players'), over.reply)
  check('[10] ...and it holds no seat', await notInRoom(over.c))
  const retiredAgain = await tryJoin(host, { playerId: 's10b-c5' })
  check('[10] an id retired during the churn is still refused as retired at the cap', isErr(retiredAgain.reply, RETIRED), retiredAgain.reply)

  // the cap only closes NEW seats: existing seats keep working
  await dropAndAwait(seat, [host])
  const g0Back = await resume(host, g0)
  check('[10] a closed room still lets an existing seat resume with its token', g0Back.reply.type === 'reconnected', g0Back.reply)
  await dropAndAwait(host, [{ c: g0Back.c, id: g0.id }])
  const hostBack = await resume(host, host)
  check('[10] ...including the host', hostBack.reply.type === 'reconnected', hostBack.reply)
  const spec = await joinRoom(host, 's10b-watcher', { spectator: true })
  check('[10] ...and spectators can still watch a closed room', spec.joined.hostPlayerId === host.id && !('reconnectToken' in spec.joined))
  const mk = g0Back.c.mark()
  sendMsg(hostBack.c, g0.id, 's10b-still-talking')
  check('[10] ...and the members still exchange messages', !!(await gotMessage(g0Back.c, 's10b-still-talking', mk)))
}

/** [10c] Control: ordinary small-game usage never comes near any of the new limits. */
async function s10cNormalUsage() {
  const host = await hostRoom('s10c-host')
  const g1 = await joinRoom(host, 's10c-g1')
  const g2 = await joinRoom(host, 's10c-g2')
  const g3 = await joinRoom(host, 's10c-g3')
  g1.c.send({ type: 'leave' })
  g2.c.send({ type: 'leave' })
  await waitFor(host.c, (m) => m.type === 'playerLeft' && m.playerId === g2.id)
  const g4 = await joinRoom(host, 's10c-g4')
  const g5 = await joinRoom(host, 's10c-g5')
  await dropAndAwait(g3, [host])
  const g3b = await resume(host, g3)
  const mk = g5.c.mark()
  sendMsg(g4.c, null, 's10c-hello')
  const got = await gotMessage(g5.c, 's10c-hello', mk)
  check('[10] control: a normal 5-player game with leaves, fresh joins and a reconnect is unaffected by the caps',
    g4.joined.hostPlayerId === host.id && g5.joined.players.length === 3 && g3b.reply.type === 'reconnected' && !!got && got.fromPlayerId === g4.id,
    { g5roster: g5.joined.players.length, g3b: g3b.reply.type })
}

/** [11] Brute-forcing a seat's token is throttled: 20 failed joins terminate the socket. */
async function s11RateLimit() {
  const host = await hostRoom('s11-host')
  const victim = await joinRoom(host, 's11-victim')
  const observer = await joinRoom(host, 's11-observer')
  await dropAndAwait(victim, [host, observer])

  const attacker = await open()
  const since = attacker.mark()
  for (let i = 0; i < 19; i++) {
    attacker.send({ type: 'join', roomCode: host.roomCode, playerId: victim.id, reconnectToken: `guess-${i}`.padEnd(32, 'x') })
  }
  const errors = await (async () => {
    const deadline = Date.now() + 6000
    while (Date.now() < deadline) {
      const n = attacker.frames.slice(since).filter((m) => m.type === 'error' && m.message === IN_ROOM).length
      if (n >= 19) return n
      await wait(10)
    }
    return attacker.frames.slice(since).filter((m) => m.type === 'error').length
  })()
  check('[11] 19 wrong reconnect guesses are each answered with the duplicate error', errors === 19, errors)
  check('[11] ...and the socket is still connected after 19 failures', attacker.ws.readyState === WebSocket.OPEN && !attacker.closed)
  attacker.send({ type: 'join', roomCode: host.roomCode, playerId: victim.id, reconnectToken: 'the-twentieth-guess'.padEnd(32, 'x') })
  check('[11] the 20th failed guess gets the socket terminated (JOIN_FAILURE_LIMIT)', await waitClosed(attacker, 5000))
  check('[11] no guess ever seated the attacker', !attacker.frames.some((m) => m.type === 'joined' || m.type === 'reconnected'))

  const owner = await resume(host, victim)
  check('[11] the throttled attack left the victim\'s seat intact: the owner still resumes afterwards', owner.reply.type === 'reconnected', owner.reply)

  // joins to a room that does not exist are charged against the same per-socket budget
  const prober = await open()
  const ps = prober.mark()
  for (let i = 0; i < 20; i++) prober.send({ type: 'join', roomCode: 'ZZZZ', playerId: `probe-${i}` })
  check('[11] 20 joins to a room that does not exist also get the socket terminated', await waitClosed(prober, 5000) && prober.frames.slice(ps).some((m) => m.type === 'error'))

  // Every other refusal of a well-formed join feeds the same limiter: a relay that forgot to count one of them would
  // hand an attacker unlimited free probes on it.
  async function floodTerminates(joinFor) {
    const c = await open()
    for (let i = 0; i < 20; i++) c.send({ type: 'join', roomCode: host.roomCode, ...joinFor(i) })
    return waitClosed(c, 5000)
  }
  check('[11] 20 token-less joins against a LIVE seat get the socket terminated', await floodTerminates(() => ({ playerId: host.id })))
  check('[11] 20 joins with a valid-looking wrong token against a LIVE seat get the socket terminated', await floodTerminates((i) => ({ playerId: host.id, reconnectToken: `live-${i}`.padEnd(32, 'y') })))
  const leaver = await joinRoom(host, 's11-leaver')
  leaver.c.send({ type: 'leave' })
  await waitFor(host.c, (m) => m.type === 'playerLeft' && m.playerId === leaver.id)
  check('[11] 20 joins with a RETIRED id get the socket terminated', await floodTerminates(() => ({ playerId: leaver.id, reconnectToken: leaver.token })))
  check('[11] 20 spectator joins colliding with a live seat get the socket terminated', await floodTerminates(() => ({ playerId: host.id, spectator: true })))
  check('[11] 20 spectator joins with a RETIRED id get the socket terminated', await floodTerminates(() => ({ playerId: leaver.id, spectator: true })))
}

/** [12] Everything else that could get a frame stamped with someone else's id. */
async function s12aSameSocketRejoin() {
  const host = await hostRoom('s12a-host')
  const g = await joinRoom(host, 's12a-g')
  const watcher = await joinRoom(host, 's12a-watcher')
  // A live member socket re-sends `join` for its OWN id, with its own valid token.
  const wk = watcher.c.mark()
  const again = await request(g.c, { type: 'join', roomCode: host.roomCode, playerId: g.id, reconnectToken: g.token })
  check('[12] a seated socket re-sending join for its own id (+valid token) does not get a second seat: the old seat is vacated and retired', isErr(again, RETIRED), again)
  check('[12] ...the others were told the old seat left', !!(await waitFor(watcher.c, (m) => m.type === 'playerLeft' && m.playerId === g.id, { since: wk })))
  check('[12] ...and the socket now holds nothing', await notInRoom(g.c))

  // A live member socket re-joins as a DIFFERENT id: no double identity, the old id is retired.
  const h = await joinRoom(host, 's12a-h')
  const hk = watcher.c.mark()
  const asOther = await request(h.c, { type: 'join', roomCode: host.roomCode, playerId: 's12a-h2' })
  noteToken(asOther && asOther.reconnectToken)
  check('[12] a seated socket re-joining as a new id gets the new seat', !!asOther && asOther.type === 'joined')
  check('[12] ...its previous id was vacated (playerLeft) ...', !!(await waitFor(watcher.c, (m) => m.type === 'playerLeft' && m.playerId === h.id, { since: hk })))
  const sk = watcher.c.mark()
  sendMsg(h.c, null, 's12a-after-rejoin', { fromPlayerId: h.id })
  const m = await gotMessage(watcher.c, 's12a-after-rejoin', sk)
  check('[12] ...and it is now stamped with the NEW id only, never the old one', !!m && m.fromPlayerId === 's12a-h2', m)
  const oldIdBack = await tryJoin(host, { playerId: h.id, reconnectToken: h.token })
  check('[12] the old id of a re-joined socket is retired (refused even with its token)', isErr(oldIdBack.reply, RETIRED), oldIdBack.reply)
}

/** [12] A 'host' re-issued by an attacker with the victim's id builds a separate room, never touches the victim's. */
async function s12bHostReissue() {
  const victimHost = await hostRoom('s12b-host')
  const guest = await joinRoom(victimHost, 's12b-guest')
  const atk = await open()
  const hostedByAtk = await request(atk, { type: 'host', playerId: victimHost.id, displayName: 'Definitely The Host' })
  check('[12] an attacker hosting with the victim host\'s id gets its OWN new room (a different code), not the victim\'s', !!hostedByAtk && hostedByAtk.type === 'hosted' && hostedByAtk.roomCode !== victimHost.roomCode, hostedByAtk)
  check('[12] ...with its own token, not the victim host\'s', !!hostedByAtk && hostedByAtk.reconnectToken !== victimHost.token && isTok(hostedByAtk.reconnectToken))
  noteToken(hostedByAtk && hostedByAtk.reconnectToken)
  const gk = guest.c.mark()
  sendMsg(atk, null, 's12b-attacker-broadcast')
  sendMsg(victimHost.c, guest.id, 's12b-real-host-msg')
  const real = await gotMessage(guest.c, 's12b-real-host-msg', gk)
  check('[12] the attacker\'s broadcast never reaches the victim\'s room (its room is a different room)', !!real && !guest.c.frames.slice(gk).some((m) => m.payloadBase64 === b64('s12b-attacker-broadcast')))
  check('[12] ...and the real host is still stamped as the host', real.fromPlayerId === victimHost.id)
  check('[12] the guest of the victim\'s room saw no roster change from the attacker hosting', !guest.c.frames.some((m) => m.type === 'playerLeft' || m.type === 'playerJoined' || m.type === 'playerReconnected'))

  // Same attack while the victim host is mid-grace: still a separate room, still no seat.
  await dropAndAwait(victimHost, [guest])
  const atk2 = await open()
  const hosted2 = await request(atk2, { type: 'host', playerId: victimHost.id })
  noteToken(hosted2 && hosted2.reconnectToken)
  check('[12] hosting with the id of a host that is mid-grace also just makes a separate room', !!hosted2 && hosted2.type === 'hosted' && hosted2.roomCode !== victimHost.roomCode)
  const back = await resume(victimHost, victimHost)
  check('[12] ...and the real host resumes its own room with its own token', back.reply.type === 'reconnected' && back.reply.roomCode === victimHost.roomCode, back.reply)

  // A host socket that re-issues 'host' leaves its old room and retires its old id there.
  const host2 = await hostRoom('s12b-host2')
  const g2 = await joinRoom(host2, 's12b-g2')
  const g3 = await joinRoom(host2, 's12b-g3')
  const reHosted = await request(host2.c, { type: 'host', playerId: 's12b-host2-again' })
  noteToken(reHosted && reHosted.reconnectToken)
  check('[12] a host socket re-issuing host moves to a new room', !!reHosted && reHosted.type === 'hosted' && reHosted.roomCode !== host2.roomCode)
  check('[12] ...the old room\'s guests see the old host leave', !!(await waitFor(g2.c, (m) => m.type === 'playerLeft' && m.playerId === host2.id)))
  const takeover = await tryJoin(host2, { playerId: host2.id, reconnectToken: host2.token })
  check('[12] ...and the abandoned host id cannot be taken over in the old room (retired, even with the old token)', isErr(takeover.reply, RETIRED), takeover.reply)
  check('[12] ...the remaining guests never got a host-stamped frame from anyone', !g3.c.frames.some((m) => m.type === 'message'))
}

/** [12] Tokens are per seat AND per room; a token never crosses rooms; a client cannot pick its own. */
async function s12cCrossRoomAndChosenTokens() {
  // Two rooms whose seats share the SAME public ids (allowed: rooms are separate namespaces).
  const roomA = await hostRoom('s12c-shared-host')
  const roomB = await hostRoom('s12c-shared-host')
  const ga = await joinRoom(roomA, 's12c-shared-guest')
  const gb = await joinRoom(roomB, 's12c-shared-guest')
  const obsA = await joinRoom(roomA, 's12c-obsA')
  const obsB = await joinRoom(roomB, 's12c-obsB')
  check('[12] setup: two rooms can each have a host and guest with identical public ids but different tokens',
    roomA.roomCode !== roomB.roomCode && roomA.token !== roomB.token && ga.token !== gb.token)
  await dropAndAwait(roomB, [gb, obsB])
  await dropAndAwait(gb, [obsB])
  const aTokenInB = await tryJoin(roomB, { playerId: roomB.id, reconnectToken: roomA.token })
  const aGuestTokenInB = await tryJoin(roomB, { playerId: gb.id, reconnectToken: ga.token })
  const aHostTokenForBGuest = await tryJoin(roomB, { playerId: gb.id, reconnectToken: roomA.token })
  check("[12] a seat's token from room A is refused for the same id in room B (host seat)", isErr(aTokenInB.reply, IN_ROOM), aTokenInB.reply)
  check("[12] ...and for a guest seat", isErr(aGuestTokenInB.reply, IN_ROOM), aGuestTokenInB.reply)
  check("[12] ...and room A's HOST token is no good for room B's guest id either", isErr(aHostTokenForBGuest.reply, IN_ROOM), aHostTokenForBGuest.reply)
  const bHost = await resume(roomB, roomB)
  const bGuest = await resume(roomB, gb)
  check("[12] control: room B's own tokens resume room B's seats", bHost.reply.type === 'reconnected' && bGuest.reply.type === 'reconnected', [bHost.reply.type, bGuest.reply.type])
  check('[12] room A is untouched by all of that', obsA.c.frames.every((m) => m.type !== 'playerDisconnected' && m.type !== 'playerReconnected'))

  // A client cannot pick its own token: whatever a fresh host/join sends, the relay mints its own.
  const chosen = 'MY-CHOSEN-TOKEN-0123456789ABCDEF'
  const hostChosen = await hostRoom('s12c-chooser-host', LONG, { reconnectToken: chosen })
  const guestChosen = await joinRoom(hostChosen, 's12c-chooser')
  check('[12] a client-supplied reconnectToken on host is ignored: the relay mints its own', hostChosen.token !== chosen && guestChosen.token !== chosen && isTok(guestChosen.token))
  // A first join never carries a token, so one that does is somebody trying to RESUME a seat this room lacks: refused,
  // not seated fresh (see the reaper scenario below for why that matters).
  const chosenOnFresh = await tryJoin(hostChosen, { playerId: 's12c-chooser-2', reconnectToken: chosen })
  check('[12] a fresh join that presents a token is refused ("no seat to resume"), not seated', isErr(chosenOnFresh.reply, NO_SEAT), chosenOnFresh.reply)
  const obs = await joinRoom(hostChosen, 's12c-obs')
  await dropAndAwait(guestChosen, [hostChosen, obs])
  const usingChosen = await tryJoin(hostChosen, { playerId: guestChosen.id, reconnectToken: chosen })
  check('[12] ...so the "chosen" value cannot later be used to take the seat', isErr(usingChosen.reply, IN_ROOM), usingChosen.reply)
  const realOne = await resume(hostChosen, guestChosen)
  check('[12] ...while the minted token does', realOne.reply.type === 'reconnected', realOne.reply)
}

/** [12] Room-code case, and a smuggled token via prototype-ish keys. */
async function s12dCaseAndSmuggling() {
  const host = await hostRoom('s12d-host')
  const g = await joinRoom(host, 's12d-g')
  const obs = await joinRoom(host, 's12d-obs')
  await dropAndAwait(g, [host, obs])
  const mixed = host.roomCode.split('').map((ch, i) => (i % 2 ? ch.toLowerCase() : ch)).join('')
  const lower = host.roomCode.toLowerCase()

  const atkLower = await tryJoin(host, { roomCode: lower, playerId: g.id })
  const atkMixed = await tryJoin(host, { roomCode: mixed, playerId: g.id, reconnectToken: 'D'.repeat(32) })
  check('[12] room-code case: a lower-case room code does not bypass the token requirement', isErr(atkLower.reply, IN_ROOM), atkLower.reply)
  check('[12] room-code case: a mixed-case room code with a wrong token is refused', isErr(atkMixed.reply, IN_ROOM), atkMixed.reply)
  const honestLower = await (async () => {
    const c = await open()
    const reply = await request(c, { type: 'join', roomCode: lower, playerId: g.id, reconnectToken: g.token })
    return { c, reply }
  })()
  check('[12] room-code case control: the honest owner resumes fine with a lower-case room code', honestLower.reply.type === 'reconnected' && honestLower.reply.roomCode === host.roomCode, honestLower.reply)

  // smuggling attempts: token hidden under keys that a naive `Object.assign`/prototype merge would honour
  await dropAndAwait({ c: honestLower.c, id: g.id }, [host, obs])
  const smuggle = await open()
  const since = smuggle.mark()
  smuggle.sendRaw(`{"type":"join","roomCode":"${host.roomCode}","playerId":"${g.id}","__proto__":{"reconnectToken":"${g.token}"}}`)
  smuggle.sendRaw(`{"type":"join","roomCode":"${host.roomCode}","playerId":"${g.id}","constructor":{"prototype":{"reconnectToken":"${g.token}"}}}`)
  smuggle.sendRaw(`{"type":"join","roomCode":"${host.roomCode}","playerId":"${g.id}","data":{"reconnectToken":"${g.token}"},"token":"${g.token}","reconnect_token":"${g.token}"}`)
  const replies = []
  const deadline = Date.now() + 4000
  while (Date.now() < deadline && replies.length < 3) {
    replies.splice(0, replies.length, ...smuggle.frames.slice(since).filter((m) => REPLY_TYPES.includes(m.type)))
    if (replies.length < 3) await wait(10)
  }
  check('[12] the token is read from "reconnectToken" only: prototype/nested/aliased copies of a REAL token are refused (3 attempts)', replies.length === 3 && replies.every((r) => isErr(r, IN_ROOM)), replies)
  const back = await resume(host, g)
  check('[12] ...and the seat is still there for its owner', back.reply.type === 'reconnected', back.reply)
}

/** [12] Race: the owner and several attackers all try to take a held seat at the same moment. */
async function s12eRace() {
  const host = await hostRoom('s12e-host')
  const victim = await joinRoom(host, 's12e-victim')
  const obs = await joinRoom(host, 's12e-obs')
  await dropAndAwait(victim, [host, obs])
  const attackers = [await open(), await open(), await open(), await open()]
  const owner = await open()
  const marks = [...attackers, owner].map((c) => c.mark())
  attackers[0].send({ type: 'join', roomCode: host.roomCode, playerId: victim.id })
  attackers[1].send({ type: 'join', roomCode: host.roomCode, playerId: victim.id, reconnectToken: '' })
  owner.send({ type: 'join', roomCode: host.roomCode, playerId: victim.id, reconnectToken: victim.token })
  attackers[2].send({ type: 'join', roomCode: host.roomCode, playerId: victim.id, reconnectToken: 'E'.repeat(32) })
  attackers[3].send({ type: 'join', roomCode: host.roomCode, playerId: victim.id, reconnectToken: String(victim.token).slice(0, 31) })
  const replies = await Promise.all([...attackers, owner].map((c, i) => firstReply(c, marks[i])))
  check('[12] race: the owner wins the seat regardless of interleaving', replies[4] && replies[4].type === 'reconnected', replies[4])
  check('[12] race: every one of the four attackers is refused, whether it arrived before or after the owner', replies.slice(0, 4).every((r) => isErr(r, IN_ROOM)), replies.slice(0, 4))
  const mk = host.c.mark()
  sendMsg(owner, host.id, 's12e-owner-speaks')
  const m = await gotMessage(host.c, 's12e-owner-speaks', mk)
  check('[12] race: afterwards the seat speaks as the owner', !!m && m.fromPlayerId === victim.id)

  // Two sockets grab the same brand-new id in the same tick: exactly one gets the seat.
  const x1 = await open(), x2 = await open()
  const xm1 = x1.mark(), xm2 = x2.mark()
  x1.send({ type: 'join', roomCode: host.roomCode, playerId: 's12e-contested' })
  x2.send({ type: 'join', roomCode: host.roomCode, playerId: 's12e-contested' })
  const [xr1, xr2] = await Promise.all([firstReply(x1, xm1), firstReply(x2, xm2)])
  const winners = [xr1, xr2].filter((r) => r && r.type === 'joined')
  const losers = [xr1, xr2].filter((r) => isErr(r, IN_ROOM))
  check('[12] race: two sockets joining the same brand-new id in the same tick => exactly one seat, the other refused', winners.length === 1 && losers.length === 1, [xr1, xr2])
  if (winners.length) noteToken(winners[0].reconnectToken)
  const winnerSock = xr1 && xr1.type === 'joined' ? x1 : x2
  const loserSock = winnerSock === x1 ? x2 : x1
  check('[12] race: the losing socket holds no seat', await notInRoom(loserSock))
  const wk = host.c.mark()
  sendMsg(winnerSock, host.id, 's12e-winner-speaks')
  const wm = await gotMessage(host.c, 's12e-winner-speaks', wk)
  check('[12] race: the winning socket speaks as the contested id', !!wm && wm.fromPlayerId === 's12e-contested', wm)

  // The genuine owner replays its valid token from two sockets at once: only one can hold the seat.
  const twin = await joinRoom(host, 's12e-twin')
  await dropAndAwait(twin, [host, obs])
  const t1 = await open(), t2 = await open()
  const tm1 = t1.mark(), tm2 = t2.mark()
  const resumeFrame = { type: 'join', roomCode: host.roomCode, playerId: twin.id, reconnectToken: twin.token }
  t1.send(resumeFrame)
  t2.send(resumeFrame)
  const [tr1, tr2] = await Promise.all([firstReply(t1, tm1), firstReply(t2, tm2)])
  check('[12] race: the owner replaying its token from two sockets at once seats exactly one of them', [tr1, tr2].filter((r) => r && r.type === 'reconnected').length === 1 && [tr1, tr2].filter((r) => isErr(r, IN_ROOM)).length === 1, [tr1, tr2])
  const twinWinner = tr1 && tr1.type === 'reconnected' ? t1 : t2
  const twinLoser = twinWinner === t1 ? t2 : t1
  check('[12] race: the duplicate socket holds no seat', await notInRoom(twinLoser))
  const tk = host.c.mark()
  sendMsg(twinWinner, host.id, 's12e-twin-speaks')
  const tmsg = await gotMessage(host.c, 's12e-twin-speaks', tk)
  check('[12] race: the surviving socket speaks as the seat', !!tmsg && tmsg.fromPlayerId === twin.id, tmsg)
}

/** [12] Look-alike ids are distinct identities; odd ids ('__proto__', 'constructor') are just ids. */
async function s12fOddIds() {
  const host = await hostRoom('s12f-Host')
  const guest = await joinRoom(host, 's12f-guest')
  const lookalikes = ['s12f-host', 's12f-Host ', 's12f-Host​', 'S12F-HOST', ' s12f-Host']
  const bad = []
  for (const id of lookalikes) {
    const seat = await joinRoom(host, id)
    const gk = guest.c.mark()
    sendMsg(seat.c, guest.id, 's12f-lookalike-msg', { fromPlayerId: host.id })
    const m = await gotMessage(guest.c, 's12f-lookalike-msg', gk)
    if (!m || m.fromPlayerId !== id || m.fromPlayerId === host.id) bad.push(`${JSON.stringify(id)} stamped as ${JSON.stringify(m && m.fromPlayerId)}`)
    seat.c.send({ type: 'leave' })
    await waitFor(guest.c, (f) => f.type === 'playerLeft' && f.playerId === id)
  }
  check('[12] look-alike ids (case, whitespace, zero-width) are separate identities stamped exactly as sent, never as the host', bad.length === 0, bad)
  const hk = guest.c.mark()
  sendMsg(host.c, guest.id, 's12f-real-host')
  const real = await gotMessage(guest.c, 's12f-real-host', hk)
  check('[12] ...and the real host is unaffected', !!real && real.fromPlayerId === host.id)

  // Map-keyed state: ids that collide with Object.prototype names are ordinary ids.
  const protoHost = await hostRoom('__proto__')
  const ctor = await joinRoom(protoHost, 'constructor')
  const ts = await joinRoom(protoHost, 'toString')
  const hp = await joinRoom(protoHost, 'hasOwnProperty')
  const pk = ts.c.mark()
  sendMsg(ctor.c, ts.id, 's12f-proto-msg')
  const pm = await gotMessage(ts.c, 's12f-proto-msg', pk)
  check("[12] ids named like Object.prototype members ('__proto__', 'constructor', 'toString') work as plain ids and are stamped correctly", !!pm && pm.fromPlayerId === 'constructor' && isTok(protoHost.token) && isTok(hp.token), pm)
  await dropAndAwait(ctor, [protoHost])
  const outsider = await tryJoin(protoHost, { playerId: 'constructor' })
  check("[12] ...and the grace-window rule holds for them too (token-less join of 'constructor' is refused)", isErr(outsider.reply, IN_ROOM), outsider.reply)
  const back = await resume(protoHost, ctor)
  check('[12] ...and the owner resumes with its token', back.reply.type === 'reconnected')
  const badRoom = await request(await open(), { type: 'join', roomCode: 'constructor', playerId: 'x' })
  const badRoom2 = await request(await open(), { type: 'join', roomCode: '__proto__', playerId: 'x' })
  check("[12] room codes named like Object.prototype members are just 'Room not found'", isErr(badRoom, 'Room not found') && isErr(badRoom2, 'Room not found'), [badRoom, badRoom2])
}

/** [12] Speaking right after a failed join, and after a failed host, is never possible. */
async function s12gMessageAfterFailure() {
  const host = await hostRoom('s12g-host')
  const guest = await joinRoom(host, 's12g-guest')
  const c = await open()
  const gk = guest.c.mark(), hk = host.c.mark()
  await request(c, { type: 'join', roomCode: host.roomCode, playerId: host.id }) // refused
  sendMsg(c, null, 's12g-after-failed-join')
  sendMsg(c, host.id, 's12g-directed-after-failed-join')
  const replyNotIn = await waitFor(c, (m) => m.type === 'error' && /not in a room/i.test(m.message))
  const c2 = await open()
  await request(c2, { type: 'join', roomCode: 'ZZZZ', playerId: 'nobody' }) // room not found
  sendMsg(c2, null, 's12g-after-no-room')
  const c3 = await open()
  await request(c3, { type: 'host', playerId: '' }) // refused: empty id
  sendMsg(c3, null, 's12g-after-failed-host')
  // barrier: a later frame from the host reaches the guest after any of the above would have
  sendMsg(host.c, guest.id, 's12g-barrier')
  await gotMessage(guest.c, 's12g-barrier', gk)
  check('[12] a message sent right after a refused join is answered "Not in a room"', !!replyNotIn)
  check('[12] ...and nothing the failed sockets sent (after a refused join, unknown room, or refused host) was relayed to anyone',
    !guest.c.frames.slice(gk).some((m) => /^s12g-(after|directed)/.test(Buffer.from(m.payloadBase64 || '', 'base64').toString())) &&
      !host.c.frames.slice(hk).some((m) => m.type === 'message'))
  const n = await request(await open(), { type: 'host' }) // missing playerId
  check('[12] host without a playerId is refused', isErr(n), n)
}

/** [12] Which direct messages a spectator can ever receive. */
async function s12hSpectatorNeverGetsSeatTraffic() {
  const host = await hostRoom('s12h-host')
  const victim = await joinRoom(host, 's12h-victim')
  const other = await joinRoom(host, 's12h-other')
  await dropAndAwait(victim, [host, other])
  // While the victim's seat is held, an attacker-spectator with a fresh id gets nothing addressed to the victim.
  const spy = await joinRoom(host, 's12h-spy', { spectator: true })
  const spyTry = await tryJoin(host, { playerId: victim.id, spectator: true })
  check('[12] a spectator cannot adopt a held seat\'s id to receive its directed traffic', isErr(spyTry.reply, IN_ROOM), spyTry.reply)
  const sk = spy.c.mark()
  sendMsg(host.c, victim.id, 's12h-secret-hand-for-victim')
  sendMsg(host.c, null, 's12h-barrier')
  await gotMessage(spy.c, 's12h-barrier', sk)
  check('[12] a spectator never receives a directed message addressed to a real seat (held or otherwise)', !spy.c.frames.slice(sk).some((m) => m.payloadBase64 === b64('s12h-secret-hand-for-victim')))
  const back = await resume(host, victim)
  check('[12] ...and the seat\'s owner, after resuming, is unaffected by the spectator\'s presence', back.reply.type === 'reconnected')
  // ...but a directed message to the spectator's OWN id does reach it (RequestState replies rely on this).
  const sk2 = spy.c.mark()
  sendMsg(host.c, spy.id, 's12h-for-spy')
  check('[12] control: a directed message addressed to the spectator\'s own id is delivered to it', !!(await gotMessage(spy.c, 's12h-for-spy', sk2)))
}

/** [13] A socket (or pending grace timer) of a room the idle sweep reaped must stay inert even after the
 *  room's code is reused by a new room. Runs against the reaper relay: 1-character room codes (32 of them, so
 *  reuse is quick), a 2s idle sweep, and a 4.2s reconnect grace period. Attempted twice at most: it needs the
 *  new room to be up before the stale grace timer fires, and a stalled machine can miss that window. */
async function s13StaleSocketAfterReap() {
  for (let attempt = 0; attempt < 2; attempt++) {
    if ((await s13Attempt(attempt === 1)) !== 'retry') return
  }
}

async function s13Attempt(lastChance) {
  // The stale room: a host and a guest, both under ids the NEW room will also use — the worst case, where an
  // attacker guessed them, so anything the stale sockets do in the new room acts on those very seats. The guest
  // drops at once, which leaves a grace timer running in the stale room; the host just sits idle (its connection
  // stays up, and heartbeats don't count as activity) until the sweep reaps the room.
  const stale = await hostRoom('s13-contested', REAP)
  const staleGuest = await joinRoom(stale, 's13-shared-guest')
  const staleSpecLeave = await joinRoom(stale, 's13-shared-spec-a', { spectator: true }) // will 'leave' from the stale room
  const staleSpecDrop = await joinRoom(stale, 's13-shared-spec-b', { spectator: true }) // will just drop
  const staleOnly = await joinRoom(stale, 's13-stale-only') // an honest seat of the stale room the NEW room has no seat for
  await dropAndAwait(staleGuest, [stale])
  await dropAndAwait(staleOnly, [stale])
  const dropObservedAt = Date.now()
  await wait(2700) // > the 2s idle timeout + a sweep tick: the stale room is reaped by now, its grace timer still pending
  const probe = await tryJoin(stale, { playerId: 's13-probe' })

  // A new room (same ids, as guessed) keeps drawing codes from the 32-code space until it lands on the reaped one.
  const V = await open(REAP)
  let victim = null
  for (let i = 0; i < 600 && !victim; i++) {
    const hosted = await request(V, { type: 'host', playerId: 's13-contested' })
    if (hosted && hosted.type === 'hosted' && hosted.roomCode === stale.roomCode) victim = { c: V, id: 's13-contested', relay: REAP, roomCode: hosted.roomCode, token: hosted.reconnectToken }
  }
  const guest = victim ? await joinRoom(victim, 's13-shared-guest') : null
  const specA = victim ? await joinRoom(victim, 's13-shared-spec-a', { spectator: true }) : null
  const specB = victim ? await joinRoom(victim, 's13-shared-spec-b', { spectator: true }) : null
  const timerFiresAt = dropObservedAt + REAP_GRACE_MS
  if (victim && Date.now() > timerFiresAt - 500 && !lastChance) return 'retry' // too slow this time: the stale timer may already have fired

  check('[13] setup: the idle sweep reaped the stale room (joining it is "Room not found")', isErr(probe.reply, 'Room not found'), probe.reply)
  check('[13] setup: a brand-new room was handed the reaped room\'s code', !!victim)
  if (!victim) return 'done'
  check('[13] setup: the new room was up, with its guest, before the stale seat\'s grace timer could fire', Date.now() < timerFiresAt - 200, Date.now() - timerFiresAt)

  // The new room's host keeps it warm so the idle sweep cannot reap it while the checks below run.
  const keepAlive = setInterval(() => sendMsg(victim.c, guest.id, 's13-keepalive'), 250)
  const injected = (frames) => frames.filter((m) => m.type === 'message' && Buffer.from(m.payloadBase64, 'base64').toString().startsWith('s13-injected'))
  try {
    // 0. An HONEST client of the reaped room (a real token, a real seat there) resumes into the reused code, e.g.
    //    after a relay restart or reap while a stranger re-hosted that code. It must be refused and told so, not
    //    seated fresh in the stranger's room as though it were its own (where the stranger could then pose as
    //    its host, and be sent that seat's directed traffic).
    const intoReused = await resume(victim, staleOnly)
    check('[13] an honest resume (valid token) of a reaped room\'s seat into the reused code is refused, not seated in the stranger\'s room', isErr(intoReused.reply, NO_SEAT), intoReused.reply)
    check('[13] ...and it left no seat behind in the new room (no playerJoined was announced for it)',
      !victim.c.frames.some((m) => m.type === 'playerJoined' && m.playerId === staleOnly.id) && !guest.c.frames.some((m) => m.type === 'playerJoined' && m.playerId === staleOnly.id))

    // 1. The stale host socket speaks: broadcast, directed to the guest, addressed to the host.
    const gk = guest.c.mark(), vk = victim.c.mark()
    sendMsg(stale.c, null, 's13-injected-broadcast')
    sendMsg(stale.c, guest.id, 's13-injected-directed')
    sendMsg(stale.c, victim.id, 's13-injected-to-host')
    await wait(300) // a fixed pause is the only way to show something did NOT happen; the barrier below proves ordering
    sendMsg(victim.c, guest.id, 's13-barrier')
    const barrier = await gotMessage(guest.c, 's13-barrier', gk)
    check('[13] control: the new room works — the real host reaches its guest, stamped as the host', !!barrier && barrier.fromPlayerId === victim.id, barrier)
    check('[13] a stale socket cannot speak into the reused room (no broadcast, directed or host-addressed frame arrives)',
      injected(guest.c.frames.slice(gk)).length === 0 && injected(victim.c.frames.slice(vk)).length === 0 &&
        !victim.c.frames.slice(vk).some((m) => m.type === 'message'),
      injected(guest.c.frames.slice(gk)))

    // 2. The stale guest's grace timer (started before the room was reaped) fires while the new room is up:
    //    it must not evict the same-named guest of the new room.
    await wait(Math.max(0, timerFiresAt + 500 - Date.now()))
    const tk = victim.c.mark()
    sendMsg(guest.c, victim.id, 's13-guest-after-timer')
    const afterTimer = await gotMessage(victim.c, 's13-guest-after-timer', tk)
    check('[13] a stale seat\'s grace timer firing later does not evict the same-named seat of the new room (no playerLeft, it still speaks)',
      !victim.c.frames.some((m) => m.type === 'playerLeft') && !!afterTimer && afterTimer.fromPlayerId === guest.id,
      victim.c.frames.filter((m) => m.type !== 'message'))

    // 3. The stale host socket departs: it must not evict the same-named host of the new room.
    const lk = guest.c.mark()
    stale.c.send({ type: 'leave' })
    await wait(300)
    sendMsg(victim.c, guest.id, 's13-after-leave')
    const afterLeave = await gotMessage(guest.c, 's13-after-leave', lk)
    check('[13] a stale socket\'s leave does not evict the same-named host of the new room (no playerLeft, the host still speaks)',
      !guest.c.frames.slice(lk).some((m) => m.type === 'playerLeft') && !!afterLeave && afterLeave.fromPlayerId === victim.id,
      guest.c.frames.slice(lk).filter((m) => m.type !== 'message'))

    // 4. ...and a stale connection dropping does not put the new room's seat into a grace period either.
    const dk = guest.c.mark()
    stale.c.ws.terminate()
    await wait(400)
    sendMsg(victim.c, guest.id, 's13-after-drop')
    await gotMessage(guest.c, 's13-after-drop', dk)
    check('[13] a stale socket dropping does not announce playerDisconnected or start a grace period for the new room\'s seat',
      !guest.c.frames.slice(dk).some((m) => m.type === 'playerDisconnected' || m.type === 'playerLeft'), guest.c.frames.slice(dk).filter((m) => m.type !== 'message'))

    // 5. Stale SPECTATOR sockets (their entries are keyed by id alone): one leaves, one drops. The new room's
    //    same-named spectators must keep receiving broadcasts.
    staleSpecLeave.c.send({ type: 'leave' })
    staleSpecDrop.c.ws.terminate()
    await wait(400)
    const ak = specA.c.mark(), bk = specB.c.mark()
    sendMsg(victim.c, null, 's13-broadcast-to-spectators')
    check('[13] a stale spectator\'s leave does not evict the same-named spectator of the new room (it still receives broadcasts)', !!(await gotMessage(specA.c, 's13-broadcast-to-spectators', ak)))
    check('[13] a stale spectator dropping does not evict the same-named spectator of the new room (it still receives broadcasts)', !!(await gotMessage(specB.c, 's13-broadcast-to-spectators', bk)))
  } finally {
    clearInterval(keepAlive)
  }
  return 'done'
}

// ---------------------------------------------------------------------------------------------

async function scenario(name, fn) {
  try {
    await fn()
  } catch (err) {
    check(`scenario "${name}" ran to completion`, false, err && err.message)
  }
}

/** Starts the three relays (long grace, short grace, reaper). The ports are random, so on the rare clash with something else already
 *  listening (the relay exits at once with EADDRINUSE) it picks new ones and tries again — unless
 *  the ports were pinned with IDENTITY_TEST_PORT, where a clash is the caller's to fix. */
async function startRelays() {
  const pinned = !!process.env.IDENTITY_TEST_PORT
  for (let attempt = 0; ; attempt++) {
    const base = pinned || attempt === 0 ? BASE_PORT : 30000 + Math.floor(Math.random() * 9000)
    LONG = startRelay('long-grace', base, LONG_GRACE_MS)
    SHORT = startRelay('short-grace', base + 1, SHORT_GRACE_MS)
    // Third relay for the idle-reaper check: a 1-character room-code space (32 codes) so a reaped room's code
    // is quickly handed to a new room, and a reaper that runs after 2s of idleness instead of 30 minutes.
    REAP = startRelay('reaper', base + 2, REAP_GRACE_MS, { ROOM_CODE_LENGTH_OVERRIDE: '1', ROOM_IDLE_TIMEOUT_MS_OVERRIDE: '2000', ROOM_SWEEP_INTERVAL_MS_OVERRIDE: '100' })
    try {
      await waitForServer(LONG)
      await waitForServer(SHORT)
      await waitForServer(REAP)
      return
    } catch (err) {
      killRelays()
      relays.length = 0
      if (pinned || attempt >= 3) throw err
      console.log(`relay start failed (${String(err.message).split('\n')[0]}); retrying on new ports`)
    }
  }
}

async function main() {
  // Whole-run watchdog: a healthy run takes seconds. If something ever waits forever, fail loudly
  // (and take the relays down with us) instead of hanging CI until the job's own timeout.
  const watchdogMs = parseInt(process.env.IDENTITY_TEST_TIMEOUT_MS || '90000', 10)
  setTimeout(() => {
    console.error(`Identity test exceeded ${watchdogMs}ms with ${pass} passed and ${fail} failed; aborting.
--- long-grace relay output (tail) ---
${LONG ? LONG.output.slice(-3000) : '(not started)'}
--- short-grace relay output (tail) ---
${SHORT ? SHORT.output.slice(-3000) : '(not started)'}
--- reaper relay output (tail) ---
${REAP ? REAP.output.slice(-3000) : '(not started)'}`)
    killRelays()
    process.exit(1)
  }, watchdogMs).unref()

  try {
    await startRelays()

    // Wave 1: everything that lives inside a (long) grace window, one scenario at a time — these are
    // quick (milliseconds), and running them serially keeps the output readable.
    await scenario('tokens', s1Tokens)
    await scenario('token leak', s2NoLeak)
    await scenario('honest reconnect', s3HonestReconnect)
    await scenario('attack A', s4GraceHijackGuest)
    await scenario('attack B', s5HostSeatHijack)
    await scenario('live duplicate', s7LiveDuplicate)
    await scenario('collisions', s8Collisions)
    await scenario('spoofing', s9Spoofing)
    await scenario('length cap', s10aLengthCap)
    await scenario('seat-id cap', s10bSeatIdCap)
    await scenario('normal usage', s10cNormalUsage)
    await scenario('rate limit', s11RateLimit)
    await scenario('same-socket re-join', s12aSameSocketRejoin)
    await scenario('host re-issue', s12bHostReissue)
    await scenario('cross-room and chosen tokens', s12cCrossRoomAndChosenTokens)
    await scenario('case and smuggling', s12dCaseAndSmuggling)
    await scenario('race', s12eRace)
    await scenario('odd ids', s12fOddIds)
    await scenario('message after failure', s12gMessageAfterFailure)
    await scenario('spectator traffic', s12hSpectatorNeverGetsSeatTraffic)

    // Wave 2: vacated seats. Explicit-leave is instant; the expiry ones each have to wait out the
    // (short) grace window, so they run side by side.
    await Promise.all([
      scenario('resume survives old timer', s3bResumeSurvivesOldTimer),
      scenario('stale socket after reap', s13StaleSocketAfterReap),
      scenario('vacated by expiry', s6aVacatedByExpiry),
      scenario('vacated by leave', s6bVacatedByLeave),
      scenario('host retired by expiry', () => s6cHostRetired(true)),
      scenario('host retired by leave', () => s6cHostRetired(false)),
    ])

    // Whole-run invariants.
    check('[2] every token minted during the whole run is unique', new Set(minted).size === minted.length && minted.length > 100, `${new Set(minted).size} unique of ${minted.length}`)
    check('[2] the relays\' own logs never contain a token', !minted.some((t) => LONG.output.includes(t) || SHORT.output.includes(t) || REAP.output.includes(t)))
    check('[all] all relay processes are still alive and healthy at the end', LONG.child.exitCode === null && SHORT.child.exitCode === null && REAP.child.exitCode === null)
    check('[all] no relay logged a crash (TypeError / ReferenceError / uncaught)', !/TypeError|ReferenceError|Uncaught|unhandled/i.test(LONG.output + SHORT.output + REAP.output))
  } finally {
    for (const c of allClients) { try { c.ws.terminate() } catch { /* already closed */ } }
    killRelays()
    await wait(100)
    for (const r of relays) if (r.child.exitCode === null) { try { r.child.kill('SIGKILL') } catch { /* gone */ } }
  }

  if (fail > 0) {
    console.log('\n--- long-grace relay output (tail, for debugging failures) ---\n' + LONG.output.slice(-4000))
    console.log('\n--- short-grace relay output (tail, for debugging failures) ---\n' + SHORT.output.slice(-4000))
    console.log('\n--- reaper relay output (tail, for debugging failures) ---\n' + REAP.output.slice(-4000))
  }
  console.log(`\n${pass} passed, ${fail} failed`)
  process.exit(fail > 0 ? 1 : 0)
}

main().catch((err) => {
  console.error('Identity test crashed:', err)
  killRelays()
  process.exit(1)
})
