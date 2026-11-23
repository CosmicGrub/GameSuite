// Wave 5, slice S7: matchmaking. Two layers, both deterministic:
//   A. the algorithm, in-process against queue.js with a fake clock and fake sockets/rooms (fast, exhaustive), and
//   B. the wire, against a real relay process: queue/queueCancel/claim frames, claim tokens, matches invisible to peek,
//      no-show handling, CPU fill. Time is the relay's injectable clock (ENABLE_TEST_CONTROL), so nothing waits for real
//      seconds. Each wire scenario uses its own optionsKey, so queues never mix between scenarios.
const { createHarness } = require('./test-lib')
const { createMatchmaker, parseQueueFrame } = require('./queue')
const { loadConfig } = require('./config')
const seatRanges = require('./seat-ranges.json')

const h = createHarness('queue-test', { watchdogMs: 120000 })
const { check, isType } = h

// ---------------------------------------------------------------------------------------------
// A. in-process
// ---------------------------------------------------------------------------------------------

function world(overrides = {}) {
  let t = 1000000
  const config = { ...loadConfig({}), ...overrides }
  const log = new Map() // ws -> frames sent to it
  const rooms = []
  const aborted = []
  const rtts = new Map()
  let canForm = true
  const sendTo = (ws, frame) => { if (!log.has(ws)) log.set(ws, []); log.get(ws).push(frame) }
  const mm = createMatchmaker({
    now: () => t,
    config,
    seatRanges,
    deps: {
      send: (ticket, frame) => sendTo(ticket.ws, frame),
      rttOf: (ticket) => (rtts.has(ticket.ws) ? rtts.get(ticket.ws) : Infinity),
      createRoom: (info) => { rooms.push(info); return `ROOM${rooms.length}` },
      abortRoom: (code) => aborted.push(code),
      canFormRoom: () => canForm,
    },
  })
  const sockets = new Map()
  const w = {
    mm, rooms, aborted, config,
    ws(name) { if (!sockets.has(name)) sockets.set(name, { name }); return sockets.get(name) },
    q(name, extra = {}) { return mm.enqueue(w.ws(name), { type: 'queue', playerId: name, displayName: name, gameId: 'chess', ...extra }) },
    advance(ms) { t += ms; mm.tick() },
    get now() { return t },
    frames(name, type) { return (log.get(w.ws(name)) || []).filter((f) => !type || f.type === type) },
    found(name) { return w.frames(name, 'matchFound').slice(-1)[0] || null },
    rtt(name, ms) { rtts.set(w.ws(name), ms) },
    canForm(v) { canForm = v },
    /** Claims for `name` with the token it was sent; returns validateClaim's result (and seats it when valid). */
    claim(name, over = {}) {
      const f = w.found(name)
      const r = mm.validateClaim(w.ws(name), { roomCode: f.roomCode, playerId: name, claimToken: f.claimToken, ...over })
      return r
    },
    claimAndSeat(name) {
      const r = w.claim(name)
      if (r.ok) r.seated = mm.markSeated(r.ticket)
      return r
    },
  }
  return w
}

function algorithm() {
  // pairs two tickets
  let w = world()
  w.q('a'); w.rtt('a', 40)
  check('[algo] one ticket alone is never matched', !w.found('a') && w.mm.stats().queued === 1)
  const qf = w.frames('a', 'queued')[0]
  check('[algo] queued frame: ticketId, estimatedWaitMs null, queueSize null below 3', !!qf && /^t-/.test(qf.ticketId) && qf.estimatedWaitMs === null && qf.queueSize === null, qf)
  w.rtt('b', 20); w.q('b')
  const fa = w.found('a'), fb = w.found('b')
  check('[algo] two tickets for a 2-seat game are matched at once, same room, both told', !!fa && !!fb && fa.roomCode === fb.roomCode && fa.cpuSeats === 0 && fa.seats.length === 2, [fa, fb])
  check('[algo] each ticket gets its OWN claim token (192-bit base64url) and the room is reserved once', fa.claimToken !== fb.claimToken && /^[A-Za-z0-9_-]{32}$/.test(fa.claimToken) && w.rooms.length === 1, [fa.claimToken, fb.claimToken])
  check('[algo] seats list ids, names (no tokens), host first; claimWithinMs is 15000', fa.seats[0].playerId === fa.hostPlayerId && fa.seats.every((s) => !('claimToken' in s)) && fa.claimWithinMs === 15000)
  check('[algo] host = lowest measured RTT (b: 20 ms beats a: 40 ms)', fa.hostPlayerId === 'b' && w.rooms[0].hostPlayerId === 'b')
  check('[algo] matched tickets leave the queue', w.mm.stats().queued === 0 && w.mm.stats().pendingMatches === 1)

  // four seats
  w = world()
  for (const n of ['a', 'b', 'c']) w.q(n, { gameId: 'ludo' })
  check('[algo] four-seat game: three humans keep waiting (no fill opted in)', !w.found('a') && w.mm.stats().queued === 3)
  check('[algo] queueSize is reported once 3 are waiting (and only then)', w.frames('c', 'queued')[0].queueSize === 3 && w.frames('b', 'queued')[0].queueSize === null)
  w.q('d', { gameId: 'ludo' })
  check('[algo] the fourth completes the table', ['a', 'b', 'c', 'd'].every((n) => w.found(n) && w.found(n).seats.length === 4 && w.found(n).cpuSeats === 0))

  // FIFO
  w = world()
  const order = ['a', 'b', 'c', 'd', 'e', 'f']
  for (const n of order) { w.q(n, { gameId: 'ludo' }); w.advance(10) }
  check('[algo] FIFO: the four OLDEST tickets form the table; the two newest keep waiting', ['a', 'b', 'c', 'd'].every((n) => w.found(n)) && !w.found('e') && !w.found('f') && w.mm.stats().queued === 2)

  // buckets and windows
  w = world()
  w.q('a', { bucket: 0 }); w.q('b', { bucket: 2 })
  w.advance(14000)
  check('[algo] bucket 0 and bucket 2 do not match inside 14 s', !w.found('a'))
  w.advance(2000) // 16 s: window +-1
  check('[algo] ...nor at 16 s (window is +-1 bucket, they are 2 apart)', !w.found('a'))
  w.advance(15000) // 31 s: any bucket
  check('[algo] ...but match once the oldest has waited 30 s (any bucket)', !!w.found('a') && !!w.found('b'))
  w = world()
  w.q('a', { bucket: 0 }); w.q('b', { bucket: 1 })
  w.advance(14000)
  check('[algo] adjacent buckets do not match before 15 s', !w.found('a'))
  w.advance(1500)
  check('[algo] adjacent buckets match from 15 s (window widens to +-1)', !!w.found('a') && !!w.found('b'))
  w = world()
  w.q('a', { bucket: 0 }); w.q('b', { bucket: 0 })
  check('[algo] the same bucket matches immediately', !!w.found('a'))
  w = world()
  w.q('old', { bucket: 0 }); w.advance(20000)
  w.q('fresh', { bucket: 1 })
  check('[algo] the window comes from the OLDEST ticket: a bucket-1 newcomer joins a 20 s old bucket-0 ticket at once', !!w.found('old') && !!w.found('fresh'))
  w = world()
  w.q('old', { bucket: 0 }); w.advance(20000)
  w.q('far', { bucket: 2 })
  check('[algo] ...but a bucket-2 newcomer (2 apart) still waits', !w.found('old') && !w.found('far'))

  // CPU fill
  w = world()
  w.q('a', { gameId: 'ludo', fillAfterMs: 20000 }); w.q('b', { gameId: 'ludo', fillAfterMs: 20000 })
  w.advance(19900)
  check('[algo] CPU fill: nothing before fillAfterMs (19.9 s)', !w.found('a'))
  w.advance(200)
  const fill = w.found('a')
  check('[algo] CPU fill: after 20 s two opted-in humans get a table with 2 computers (cpuSeats 2)', !!fill && fill.cpuSeats === 2 && fill.seats.length === 2 && !!w.found('b'), fill)
  w = world()
  w.q('a', { gameId: 'ludo', fillAfterMs: 5000 })
  w.advance(100000)
  check('[algo] CPU fill never matches a lone human', !w.found('a') && w.mm.stats().queued === 1)
  w = world()
  w.q('a', { gameId: 'ludo', fillAfterMs: 20000 }); w.q('b', { gameId: 'ludo' })
  w.advance(60000)
  check('[algo] CPU fill needs opt-in: a human who did not opt in is never released into a table with computers', !w.found('a') && !w.found('b'))
  w = world()
  for (const n of ['a', 'b', 'c']) w.q(n, { gameId: 'ludo', fillAfterMs: 20000 })
  w.advance(20000)
  check('[algo] CPU fill: three opted-in humans in a 4-seat game get 1 computer', !!w.found('a') && w.found('a').cpuSeats === 1 && w.found('a').seats.length === 3)
  w = world()
  w.q('a', { fillAfterMs: 20000, bucket: 0 }); w.q('b', { fillAfterMs: 20000, bucket: 2 })
  w.advance(20000)
  check('[algo] a 2-seat table is never CPU filled (two humans or nothing): buckets 0 and 2 still wait for their window', !w.found('a'))
  w = world()
  w.q('a', { gameId: 'ludo', fillAfterMs: 20000, canHost: false }); w.q('b', { gameId: 'ludo', fillAfterMs: 20000, canHost: false })
  w.advance(30000)
  check('[algo] a group nobody in can host is not matched, even with CPU fill', !w.found('a') && w.mm.stats().queued === 2)

  // host choice
  w = world()
  w.rtt('a', 40); w.rtt('b', 20); w.rtt('c', 30); w.rtt('d', 25)
  w.q('a', { gameId: 'ludo' }); w.q('b', { gameId: 'ludo', canHost: false }); w.q('c', { gameId: 'ludo' }); w.q('d', { gameId: 'ludo' })
  check('[host] lowest RTT among those who canHost (b is fastest but canHost:false, so d at 25 ms)', w.found('a').hostPlayerId === 'd')
  w = world()
  w.rtt('a', 30); w.rtt('b', 30)
  w.q('a'); w.q('b')
  check('[host] an RTT tie goes to the older ticket', w.found('a').hostPlayerId === 'a')
  w = world()
  w.rtt('b', 500)
  w.q('a'); w.q('b')
  check('[host] an unmeasured RTT loses to any measured one', w.found('a').hostPlayerId === 'b')
  w = world()
  w.q('a', { canHost: false }); w.q('b', { canHost: false })
  check('[host] nobody can host: no match forms, both keep waiting', !w.found('a') && w.mm.stats().queued === 2)
  w.q('c')
  check('[host] ...and a hosting-capable arrival makes a table for the oldest hostless one', !!w.found('a') && w.found('a').hostPlayerId === 'c' && !w.found('b'))

  // claim tokens
  w = world()
  w.q('a'); w.q('b'); w.rtt('a', 1)
  const wrongPlayer = w.claim('a', { playerId: 'b' })
  const wrongToken = w.claim('a', { claimToken: w.found('b').claimToken })
  const wrongRoom = w.claim('a', { roomCode: 'ROOM9' })
  const noToken = w.claim('a', { claimToken: undefined })
  const numToken = w.claim('a', { claimToken: 12345 })
  const foreignWs = w.mm.validateClaim(w.ws('stranger'), { roomCode: w.found('a').roomCode, playerId: 'a', claimToken: w.found('a').claimToken })
  check('[claim] refused: another player\'s id, another player\'s token, a wrong room, a missing or non-string token, a socket with no ticket', [wrongPlayer, wrongToken, wrongRoom, noToken, numToken, foreignWs].every((r) => r.ok === false))
  const first = w.claim('a')
  check('[claim] failed attempts do not burn the token: the right one still works', first.ok === true && first.match.roomCode === w.found('a').roomCode)
  const second = w.claim('a')
  check('[claim] single use: the same token a second time is refused', second.ok === false)
  check('[claim] the lower-lowercase room code works (claim upper-cases like join)', w.claim('b', { roomCode: w.found('b').roomCode.toLowerCase() }).ok === true)
  w = world()
  w.q('a'); w.q('b')
  w.advance(14999)
  check('[claim] a claim just inside claimWithinMs is accepted', w.claim('a').ok === true)
  w = world()
  w.q('a'); w.q('b')
  w.now // eslint-disable-line no-unused-expressions
  const stale = w.found('a')
  w.advance(15000)
  check('[claim] an expired match is gone: its claim is refused', w.mm.validateClaim(w.ws('a'), { roomCode: stale.roomCode, playerId: 'a', claimToken: stale.claimToken }).ok === false)

  // all seated
  w = world()
  w.q('a'); w.q('b')
  const s1 = w.claimAndSeat('a')
  check('[claim] the first seat does not finish the match', s1.seated.allSeated === false && w.mm.stats().pendingMatches === 1)
  const s2 = w.claimAndSeat('b')
  check('[claim] the last seat finishes it: no longer pending, tickets forgotten (sockets may queue again)', s2.seated.allSeated === true && w.mm.stats().pendingMatches === 0 && w.mm.stats().tickets === 0)
  w.advance(60000)
  check('[claim] a completed match is never aborted by time', w.aborted.length === 0)

  // no-show: guest never claims
  w = world()
  w.rtt('a', 5); w.rtt('b', 50); w.q('a'); w.advance(3000); w.q('b') // a is the host and the OLDER ticket
  w.claimAndSeat('a')
  w.advance(15000)
  const abortedA = w.frames('a', 'matchAborted')[0], abortedB = w.frames('b', 'matchAborted')[0]
  check('[no-show] a guest that never claims: the claimed host gets matchAborted(no_show, requeued) and the guest (requeued false)', !!abortedA && abortedA.reason === 'no_show' && abortedA.requeued === true && !!abortedB && abortedB.requeued === false, [abortedA, abortedB])
  check('[no-show] the reserved room is discarded', w.aborted.length === 1 && w.aborted[0] === w.found('a').roomCode)
  const requeued = w.frames('a', 'queued').slice(-1)[0]
  check('[no-show] the survivor is queued again (queued frame says requeued)', !!requeued && requeued.requeued === true && w.mm.stats().queued === 1)
  const early = w.q('b')
  check('[no-show] the no-show cannot queue again for 30 s: queue_cooldown with retryAfterMs', early.ok === false && early.code === 'queue_cooldown' && early.retryAfterMs > 0 && early.retryAfterMs <= 30000, early)
  w.advance(30000)
  check('[no-show] ...and can afterwards', w.q('b').ok === true || w.mm.stats().queued >= 1)

  // survivors keep their ORIGINAL age
  w = world()
  w.rtt('a', 5); w.rtt('b', 50)
  w.q('a', { bucket: 0 }); w.q('b', { bucket: 0 }) // matched; host a
  w.claimAndSeat('a')
  w.advance(16000) // b never claims; a is requeued with its original enqueuedAt (16 s old now)
  w.q('c', { bucket: 1 })
  check('[no-show] survivors are re-queued with their ORIGINAL age: a (now 16 s old, window +-1) takes a bucket-1 newcomer at once', !!w.found('c') && w.found('c').seats.some((s) => s.playerId === 'a'))

  // host no-show
  w = world()
  w.rtt('a', 5); w.rtt('b', 50); w.q('a'); w.q('b')
  w.claimAndSeat('b') // the guest claims, the host (a) never does
  w.advance(15000)
  check('[no-show] a host that never claims aborts with host_failed and the guest is re-queued', w.frames('b', 'matchAborted')[0].reason === 'host_failed' && w.frames('b', 'matchAborted')[0].requeued === true && w.frames('a', 'matchAborted')[0].requeued === false)

  // cancel and socket close
  w = world()
  w.q('a')
  check('[cancel] cancel dequeues', w.mm.cancel(w.ws('a')) === true && w.mm.stats().queued === 0 && w.mm.stats().tickets === 0)
  check('[cancel] cancel is idempotent', w.mm.cancel(w.ws('a')) === false)
  w.q('a'); w.mm.socketGone(w.ws('a'))
  check('[cancel] socket close dequeues too', w.mm.stats().queued === 0 && w.mm.stats().tickets === 0)
  w.q('b'); w.q('c')
  check('[cancel] a dequeued ticket never matches anyone', !w.found('a') && !!w.found('b') && !w.found('a'))
  w = world()
  w.rtt('a', 5); w.q('a'); w.q('b')
  w.mm.cancel(w.ws('a')) // a is the host and cancels after matchFound
  check('[cancel] cancelling after matchFound aborts the match: b is told (host_failed) and re-queued', w.frames('b', 'matchAborted')[0].reason === 'host_failed' && w.frames('b', 'matchAborted')[0].requeued === true && w.mm.stats().queued === 1)
  w = world()
  w.rtt('a', 5); w.q('a'); w.q('b')
  w.claimAndSeat('b')
  w.mm.socketGone(w.ws('b')) // a seated-but-pending guest's socket closes
  check('[cancel] a closed socket of a pending match aborts it: the other is re-queued and the reserved room discarded', w.frames('a', 'matchAborted')[0].requeued === true && w.aborted.length === 1 && w.mm.stats().queued === 1)

  // caps and one-per-socket
  w = world({ queueMaxTickets: 3 })
  w.q('a', { bucket: 0 }); w.q('b', { bucket: 2, gameId: 'ludo' }); w.q('c', { bucket: 1, gameId: 'uno' })
  const over = w.q('d')
  check('[caps] the ticket cap refuses the next ticket: queue_full', over.ok === false && over.code === 'queue_full', over)
  w.mm.cancel(w.ws('a'))
  check('[caps] ...and frees up when one leaves', w.q('d').ok === true)
  w = world()
  w.q('a')
  const dupSocket = w.mm.enqueue(w.ws('a'), { type: 'queue', playerId: 'a2', gameId: 'chess' })
  check('[caps] one ticket per socket: already_queued', dupSocket.ok === false && dupSocket.code === 'already_queued', dupSocket)
  const dupPlayer = w.mm.enqueue(w.ws('other'), { type: 'queue', playerId: 'a', gameId: 'ludo' })
  check('[caps] one ticket per playerId: player_in_use', dupPlayer.ok === false && dupPlayer.code === 'player_in_use', dupPlayer)
  w = world({ queueMaxPending: 1 })
  w.q('a', { gameId: 'ludo' }); w.q('b', { gameId: 'ludo' }); w.q('c', { gameId: 'ludo' }); w.q('d', { gameId: 'ludo' })
  for (const n of ['e', 'f', 'g', 'h']) w.q(n, { gameId: 'ludo' })
  check('[caps] the pending-match cap holds a second table back until the first completes', !!w.found('a') && !w.found('e') && w.mm.stats().queued === 4)
  for (const n of ['a', 'b', 'c', 'd']) w.claimAndSeat(n)
  w.advance(1)
  check('[caps] ...then the next table forms', !!w.found('e'))
  w = world()
  w.canForm(false)
  w.q('a'); w.q('b')
  check('[caps] no match while the relay is at its room cap', !w.found('a'))
  w.canForm(true); w.advance(1000)
  check('[caps] ...and it forms on the next tick once there is room', !!w.found('a'))

  // validation
  const vf = (over) => parseQueueFrame({ type: 'queue', playerId: 'p', gameId: 'chess', ...over }, seatRanges)
  check('[valid] unknown game: unknown_game', vf({ gameId: 'definitely-not-a-game' }).code === 'unknown_game')
  check('[valid] malformed gameId, playerId, optionsKey, bucket, fillAfterMs, canHost, seats: bad_request',
    [{ gameId: 'Chess!' }, { gameId: 5 }, { playerId: '' }, { playerId: 'x'.repeat(65) }, { optionsKey: 'a b' }, { optionsKey: 5 }, { bucket: 3 }, { bucket: -1 }, { bucket: 1.5 }, { bucket: '1' },
      { fillAfterMs: 'soon' }, { fillAfterMs: 20000.5 }, { canHost: 'yes' }, { maxSeats: '2' }, { minSeats: 1.5 }].every((o) => vf(o).code === 'bad_request'), [{ bucket: 3 }].map(vf))
  const ludo = parseQueueFrame({ type: 'queue', playerId: 'p', gameId: 'ludo', maxSeats: 99, minSeats: 0 }, seatRanges).params
  check('[valid] seats are clamped into the generated range of the game (ludo 2-4)', ludo.maxSeats === 4 && ludo.minSeats === 2, ludo)
  const chess = parseQueueFrame({ type: 'queue', playerId: 'p', gameId: 'chess', maxSeats: 4 }, seatRanges).params
  check('[valid] a forged maxSeats 4 for chess becomes 2: a forged range cannot create a table the game cannot seat', chess.maxSeats === 2, chess)
  const defaults = parseQueueFrame({ type: 'queue', playerId: 'p', gameId: 'president' }, seatRanges).params
  check('[valid] defaults: optionsKey std, bucket 1, no fill, canHost true, seats from the file (president 3-6)', defaults.optionsKey === 'std' && defaults.bucket === 1 && defaults.fillAfterMs === null && defaults.canHost === true && defaults.minSeats === 3 && defaults.maxSeats === 6, defaults)
  check('[valid] fillAfterMs is clamped to 5 s .. 120 s', parseQueueFrame({ type: 'queue', playerId: 'p', gameId: 'ludo', fillAfterMs: 1 }, seatRanges).params.fillAfterMs === 5000 && parseQueueFrame({ type: 'queue', playerId: 'p', gameId: 'ludo', fillAfterMs: 10 ** 9 }, seatRanges).params.fillAfterMs === 120000)
  const named = parseQueueFrame({ type: 'queue', playerId: 'p', gameId: 'chess', displayName: ' A\u0000B‮ ', avatar: 'pip|sky|circle' }, seatRanges).params
  check('[valid] the ticket name is sanitized and a valid avatar kept', named.displayName === 'A B' && named.avatar === 'pip|sky|circle', named)

  // options keys separate queues; seat counts separate queues
  w = world()
  w.q('a', { optionsKey: 'std' }); w.q('b', { optionsKey: 'blitz' })
  check('[options] different optionsKey never meet', !w.found('a') && !w.found('b'))
  w = world()
  w.q('a', { gameId: 'ludo', maxSeats: 2 }); w.q('b', { gameId: 'ludo', maxSeats: 4 })
  check('[options] different table sizes never meet', !w.found('a') && !w.found('b'))
  w = world()
  w.q('a', { gameId: 'ludo', maxSeats: 2 }); w.q('b', { gameId: 'ludo', maxSeats: 2 })
  check('[options] a 2-seat ludo table is allowed within the 2-4 range', !!w.found('a'))

  // progress updates and ticket expiry
  w = world()
  w.q('a')
  w.advance(4999)
  check('[updates] no queueUpdate before 5 s', w.frames('a', 'queueUpdate').length === 0)
  w.advance(1)
  const u1 = w.frames('a', 'queueUpdate')[0]
  check('[updates] a queueUpdate at 5 s: waitedMs and widened false', !!u1 && u1.waitedMs === 5000 && u1.widened === false, u1)
  w.advance(11000)
  const ups = w.frames('a', 'queueUpdate')
  check('[updates] widened turns true once the window has widened (16 s)', ups.length >= 2 && ups[ups.length - 1].widened === true && ups[ups.length - 1].waitedMs === 16000, ups)
  w.advance(10 * 60 * 1000)
  check('[expiry] a ticket older than 3 minutes is expired: queueExpired and it is gone', w.frames('a', 'queueExpired').length === 1 && w.mm.stats().tickets === 0)
}

// ---------------------------------------------------------------------------------------------
// B. wire
// ---------------------------------------------------------------------------------------------

const MATCH_CODE = /^[A-HJ-NP-Z2-9]{8}$/
let scenarioNo = 0
const queueFrame = (id, over = {}) => ({ type: 'queue', playerId: id, displayName: id, gameId: 'chess', ...over })

async function player(relay, id, { rtt } = {}) {
  const c = await h.client(relay)
  if (rtt !== undefined) await c.request({ type: '__test', op: 'setRtt', ms: rtt }, (m) => m.type === '__test' && m.op === 'setRtt')
  return { c, id }
}

async function queueUp(p, over = {}) {
  const reply = await p.c.request(queueFrame(p.id, over), (m) => m.type === 'queued' || m.type === 'error')
  return reply
}

const claimFrame = (p, match, over = {}) => ({ type: 'claim', roomCode: match.roomCode, playerId: p.id, claimToken: match.claimToken, displayName: p.id, ...over })

async function waitState(ctl, pred, timeoutMs = 3000) {
  const deadline = Date.now() + timeoutMs
  let s = await ctl.state()
  while (!(s && pred(s)) && Date.now() < deadline) { await h.wait(25); s = await ctl.state() }
  return s
}

/** Closes a scenario's sockets (which drops any ticket they hold) and waits until the relay holds none, so every
 *  scenario starts from an empty queue and its state assertions can be absolute. */
async function endScenario(ctl, ...players) {
  for (const p of players) { try { (p.c || p).ws.terminate() } catch { /* already closed */ } }
  const s = await waitState(ctl, (st) => st.tickets === 0)
  check('[wire] scenario cleanup: the relay holds no tickets', s.tickets === 0, s)
}

async function wireMatchAndClaim(relay, ctl) {
  const opts = { optionsKey: `w${++scenarioNo}` }
  const a = await player(relay, 'wq-a', { rtt: 50 })
  const b = await player(relay, 'wq-b', { rtt: 5 })
  const qa = await queueUp(a, opts)
  check('[wire] queue is answered with queued (ticketId, estimatedWaitMs null, queueSize null)', !!qa && qa.type === 'queued' && /^t-/.test(qa.ticketId) && qa.estimatedWaitMs === null && qa.queueSize === null, qa)
  await queueUp(b, opts)
  const ma = await a.c.waitFor(isType('matchFound'))
  const mb = await b.c.waitFor(isType('matchFound'))
  check('[wire] two players are matched: the same 8-character room id, seats for both, no CPU, 15 s to claim',
    !!ma && !!mb && ma.roomCode === mb.roomCode && MATCH_CODE.test(ma.roomCode) && ma.seats.length === 2 && ma.cpuSeats === 0 && ma.claimWithinMs === 15000, [ma, mb])
  check('[wire] the host is the lower measured RTT (b: 5 ms vs a: 50 ms)', ma.hostPlayerId === 'wq-b' && mb.hostPlayerId === 'wq-b' && ma.seats[0].playerId === 'wq-b')
  check('[wire] each player is given its own claim token and never sees the other\'s', ma.claimToken !== mb.claimToken && !a.c.raw.some((t) => t.includes(mb.claimToken)) && !b.c.raw.some((t) => t.includes(ma.claimToken)))

  // matches never appear in peek or join
  const probe = await h.client(relay)
  const peek = await probe.request({ type: 'peek', roomCode: ma.roomCode }, (m) => m.type === 'room' || m.type === 'error')
  check('[wire] a match never appears in peek: no_such_room', !!peek && peek.type === 'error' && peek.code === 'no_such_room', peek)
  const join = await probe.request({ type: 'join', roomCode: ma.roomCode, playerId: 'wq-intruder' }, (m) => m.type === 'joined' || m.type === 'error')
  check('[wire] ...and cannot be joined by code: Room not found', !!join && join.type === 'error' && join.message === 'Room not found', join)
  const joinSpec = await probe.request({ type: 'join', roomCode: ma.roomCode, playerId: 'wq-intruder2', spectator: true }, (m) => m.type === 'joined' || m.type === 'error')
  check('[wire] ...not even as a spectator', !!joinSpec && joinSpec.type === 'error' && joinSpec.message === 'Room not found', joinSpec)

  // claims: bad ones first (they must not burn the real token), the guest before the host
  const thief = await h.client(relay)
  const stolen = await thief.request(claimFrame(a, ma), (m) => m.type === 'error' || m.type === 'joined' || m.type === 'hosted')
  check('[claim] a third socket with a stolen token (right id, right room) is refused: claim_invalid', !!stolen && stolen.code === 'claim_invalid', stolen)
  const crossed = await b.c.request(claimFrame(a, ma), (m) => m.type === 'error' || m.type === 'joined' || m.type === 'hosted')
  check('[claim] the host\'s socket presenting the guest\'s id and token is refused (bound to the socket that holds the ticket)', !!crossed && crossed.code === 'claim_invalid', crossed)
  const wrongToken = await a.c.request(claimFrame(a, ma, { claimToken: 'x'.repeat(32) }), (m) => m.type === 'error' || m.type === 'joined')
  check('[claim] a wrong token is refused', !!wrongToken && wrongToken.code === 'claim_invalid', wrongToken)
  const wrongRoom = await a.c.request(claimFrame(a, ma, { roomCode: 'AAAAAAAA' }), (m) => m.type === 'error' || m.type === 'joined')
  check('[claim] the right token for a wrong room is refused', !!wrongRoom && wrongRoom.code === 'claim_invalid', wrongRoom)
  const wrongId = await a.c.request(claimFrame(a, ma, { playerId: 'wq-b' }), (m) => m.type === 'error' || m.type === 'joined')
  check('[claim] ...and so is another player\'s id with your own token', !!wrongId && wrongId.code === 'claim_invalid', wrongId)
  const noTicket = await (await h.client(relay)).request({ type: 'claim', roomCode: ma.roomCode, playerId: 'wq-z', claimToken: 'y'.repeat(32) }, (m) => m.type === 'error')
  check('[claim] a socket that never queued is refused', !!noTicket && noTicket.code === 'claim_invalid', noTicket)

  const joined = await a.c.request(claimFrame(a, ma, { avatar: 'pip|sky|circle' }), (m) => m.type === 'joined' || m.type === 'error')
  check('[claim] the guest claims first: joined with hostPlayerId, a reconnectToken, matchmade, the seats and cpuSeats',
    !!joined && joined.type === 'joined' && joined.roomCode === ma.roomCode && joined.hostPlayerId === 'wq-b' && typeof joined.reconnectToken === 'string' && joined.reconnectToken.length >= 32 && joined.matchmade === true && joined.cpuSeats === 0 && joined.seats.length === 2, joined)
  check('[claim] the host has not claimed yet, so the guest\'s roster is empty', !!joined && Array.isArray(joined.players) && joined.players.length === 0, joined)
  const replay = await a.c.request(claimFrame(a, ma), (m) => m.type === 'error' || m.type === 'joined')
  check('[claim] single use: replaying the claim is refused', !!replay && replay.code === 'claim_invalid', replay)
  const hostSeen = b.c.mark()
  const hosted = await b.c.request(claimFrame(b, mb), (m) => m.type === 'hosted' || m.type === 'error')
  check('[claim] the host claims: hosted with a reconnectToken and the seats', !!hosted && hosted.type === 'hosted' && hosted.roomCode === ma.roomCode && hosted.playerId === 'wq-b' && typeof hosted.reconnectToken === 'string' && hosted.matchmade === true, hosted)
  const replayed = await b.c.waitFor((m) => m.type === 'playerJoined' && m.playerId === 'wq-a', { since: hostSeen })
  check('[claim] the host that claims after a guest still sees the guest arrive (playerJoined replayed), with its avatar', !!replayed && replayed.avatar === 'pip|sky|circle', replayed)
  const readyA = await a.c.waitFor(isType('matchReady'))
  const readyB = await b.c.waitFor(isType('matchReady'))
  check('[claim] once every seat is taken both get matchReady (the host may auto-start)', !!readyA && !!readyB && readyA.roomCode === ma.roomCode)

  // from here it is an ordinary room: stamped messages, chat, reconnect
  const am = a.c.mark(), bm = b.c.mark()
  b.c.send({ type: 'message', toPlayerId: null, payloadBase64: Buffer.from('from-host').toString('base64') })
  a.c.send({ type: 'message', toPlayerId: 'wq-b', payloadBase64: Buffer.from('from-guest').toString('base64') })
  const toGuest = await a.c.waitFor(isType('message'), { since: am })
  const toHost = await b.c.waitFor(isType('message'), { since: bm })
  check('[room] messages are relayed and stamped from the socket in a matchmade room', !!toGuest && toGuest.fromPlayerId === 'wq-b' && !!toHost && toHost.fromPlayerId === 'wq-a', [toGuest, toHost])
  const cm = b.c.mark()
  a.c.send({ type: 'chat', phraseId: 1 })
  const chat = await b.c.waitFor(isType('chat'), { since: cm })
  check('[room] quick-chat works in a matchmade room', !!chat && chat.fromPlayerId === 'wq-a' && chat.phraseId === 1, chat)
  const lockR = await b.c.request({ type: 'lock', locked: false }, (m) => m.type === 'roomState' || m.type === 'error')
  check('[room] the matchmade host is the room host (host-only frames work) and the room reports started', !!lockR && lockR.type === 'roomState' && lockR.state === 'started', lockR)
  const stillHidden = await probe.request({ type: 'peek', roomCode: ma.roomCode }, (m) => m.type === 'room' || m.type === 'error')
  check('[room] a fully claimed match is still invisible to peek', !!stillHidden && stillHidden.type === 'error', stillHidden)
  const st = await ctl.state()
  check('[wire] after the match completed no ticket or pending match is left (it is an ordinary room now)', st.tickets === 0 && st.pendingMatches === 0 && st.queued === 0, st)
  // reconnect inherits the normal machinery
  a.c.ws.terminate()
  await b.c.waitFor((m) => m.type === 'playerDisconnected' && m.playerId === 'wq-a')
  const back = await h.client(relay)
  const re = await back.request({ type: 'join', roomCode: ma.roomCode, playerId: 'wq-a', reconnectToken: joined.reconnectToken }, (m) => m.type === 'reconnected' || m.type === 'error')
  check('[room] a matchmade seat reconnects with its reconnectToken like any other seat', !!re && re.type === 'reconnected', re)
  const reHack = await (await h.client(relay)).request({ type: 'join', roomCode: ma.roomCode, playerId: 'wq-new', reconnectToken: joined.reconnectToken }, (m) => m.type === 'reconnected' || m.type === 'joined' || m.type === 'error')
  check('[room] ...but join still cannot ENTER a matchmade room under any other id', !!reHack && reHack.type === 'error' && reHack.message === 'Room not found', reHack)
  await endScenario(ctl, a, b, back, probe, thief)
}

async function wireNoShow(relay, ctl) {
  const opts = { optionsKey: `w${++scenarioNo}` }
  const g = await player(relay, 'ns-g', { rtt: 50 })
  const f = await player(relay, 'ns-f', { rtt: 5 }) // f hosts
  await queueUp(g, opts)
  await queueUp(f, opts)
  const mg = await g.c.waitFor(isType('matchFound'))
  await g.c.request(claimFrame(g, mg), (m) => m.type === 'joined' || m.type === 'error')
  const gm = g.c.mark()
  await ctl.advance(16000)
  const abortG = await g.c.waitFor(isType('matchAborted'), { since: gm })
  const abortF = await f.c.waitFor(isType('matchAborted'))
  check('[no-show] the host never claimed: the guest is told matchAborted (host_failed) and will be re-queued', !!abortG && abortG.reason === 'host_failed' && abortG.requeued === true, abortG)
  check('[no-show] the no-show host is told too, not re-queued', !!abortF && abortF.requeued === false)
  const requeued = await g.c.waitFor((m) => m.type === 'queued' && m.requeued === true, { since: gm })
  check('[no-show] the survivor gets a fresh queued frame (requeued:true)', !!requeued)
  const stale = await g.c.request(claimFrame(g, mg), (m) => m.type === 'error' || m.type === 'joined')
  check('[no-show] the old claim token is dead after the abort', !!stale && stale.code === 'claim_invalid', stale)
  const cool = await queueUp(f, opts)
  check('[no-show] the no-show cannot queue again for 30 s: queue_cooldown', !!cool && cool.type === 'error' && cool.code === 'queue_cooldown' && cool.retryAfterMs > 0, cool)
  const h2 = await player(relay, 'ns-h', { rtt: 1 })
  const gMark = g.c.mark()
  await queueUp(h2, opts)
  const m2 = await g.c.waitFor(isType('matchFound'), { since: gMark })
  const m2h = await h2.c.waitFor(isType('matchFound'))
  check('[no-show] the survivor is matched at once with a newcomer (its original age was kept) into a NEW room', !!m2 && !!m2h && m2.roomCode === m2h.roomCode && m2.roomCode !== mg.roomCode && m2.hostPlayerId === 'ns-h', [m2, m2h])
  await ctl.advance(31000) // cool down f; the pending g/h match also expires (nobody claims): both re-queued
  const later = await f.c.request(queueFrame(f.id, opts), (m) => m.type === 'queued' || m.type === 'error')
  check('[no-show] after 30 s the no-show can queue again', !!later && later.type === 'queued', later)
  await endScenario(ctl, g, f, h2)
}

async function wireCancelAndClose(relay, ctl) {
  const opts = { optionsKey: `w${++scenarioNo}` }
  const x = await player(relay, 'cc-x')
  await queueUp(x, opts)
  const c1 = await x.c.request({ type: 'queueCancel' }, isType('queueCancelled'))
  const c2 = await x.c.request({ type: 'queueCancel' }, isType('queueCancelled'))
  check('[cancel] queueCancel is answered queueCancelled, twice (idempotent)', !!c1 && !!c2)
  const s1 = await waitState(ctl, (s) => s.queued === 0)
  check('[cancel] the cancelled ticket is gone', s1.queued === 0, s1)
  const again = await queueUp(x, opts)
  check('[cancel] the same socket may queue again after a cancel', !!again && again.type === 'queued', again)
  const dup = await queueUp(x, { ...opts, playerId: 'cc-x2' })
  check('[caps] one ticket per socket: already_queued', !!dup && dup.code === 'already_queued', dup)
  const other = await player(relay, 'cc-other')
  const sameId = await other.c.request(queueFrame('cc-x', opts), (m) => m.type === 'queued' || m.type === 'error')
  check('[caps] one ticket per playerId: player_in_use', !!sameId && sameId.code === 'player_in_use', sameId)
  x.c.ws.terminate()
  const s2 = await waitState(ctl, (s) => s.queued === 0 && s.tickets === 0)
  check('[cancel] closing the socket dequeues the ticket', s2.queued === 0 && s2.tickets === 0, s2)
  const y = await player(relay, 'cc-y')
  await queueUp(y, opts)
  await h.wait(100)
  check('[cancel] a closed socket\'s ticket never matches anyone (y keeps waiting)', y.c.of('matchFound').length === 0)
  await y.c.request({ type: 'queueCancel' }, isType('queueCancelled'))

  // a socket of a pending match closing aborts it
  const opts2 = { optionsKey: `w${++scenarioNo}` }
  const p = await player(relay, 'cc-p', { rtt: 50 })
  const q = await player(relay, 'cc-q', { rtt: 5 }) // q hosts
  await queueUp(p, opts2)
  await queueUp(q, opts2)
  await p.c.waitFor(isType('matchFound'))
  const pm = p.c.mark()
  q.c.ws.terminate()
  const ab = await p.c.waitFor(isType('matchAborted'), { since: pm })
  check('[cancel] the host closing before claiming aborts the match: the guest gets matchAborted(host_failed), re-queued', !!ab && ab.reason === 'host_failed' && ab.requeued === true, ab)
  const rq = await p.c.waitFor((m) => m.type === 'queued' && m.requeued === true, { since: pm })
  check('[cancel] ...and is queued again', !!rq)
  await p.c.request({ type: 'queueCancel' }, isType('queueCancelled'))

  // cancel after matchFound
  const opts3 = { optionsKey: `w${++scenarioNo}` }
  const r = await player(relay, 'cc-r', { rtt: 50 })
  const s = await player(relay, 'cc-s', { rtt: 5 })
  await queueUp(r, opts3)
  await queueUp(s, opts3)
  await r.c.waitFor(isType('matchFound'))
  const rm = r.c.mark(), sm = s.c.mark()
  s.c.send({ type: 'queueCancel' })
  const sAb = await s.c.waitFor(isType('matchAborted'), { since: sm })
  const rAb = await r.c.waitFor(isType('matchAborted'), { since: rm })
  check('[cancel] cancelling after matchFound: the canceller is told (not re-queued), the other is re-queued', !!sAb && sAb.requeued === false && !!rAb && rAb.requeued === true, [sAb, rAb])
  await r.c.request({ type: 'queueCancel' }, isType('queueCancelled'))
  await endScenario(ctl, r, s, p, q, x, y, other)
}

async function wireCpuFillAndBuckets(relay, ctl) {
  const opts = { optionsKey: `w${++scenarioNo}`, gameId: 'ludo', fillAfterMs: 20000 }
  const a = await player(relay, 'cf-a', { rtt: 10 })
  const b = await player(relay, 'cf-b', { rtt: 20 })
  await queueUp(a, opts)
  await queueUp(b, opts)
  await ctl.advance(19000)
  await h.wait(50)
  check('[fill] no table before fillAfterMs', a.c.of('matchFound').length === 0)
  await ctl.advance(1500)
  const ma = await a.c.waitFor(isType('matchFound'))
  check('[fill] after fillAfterMs two opted-in players get a table with 2 computers (cpuSeats 2, 2 human seats)', !!ma && ma.cpuSeats === 2 && ma.seats.length === 2 && ma.hostPlayerId === 'cf-a', ma)
  await a.c.request({ type: 'queueCancel' }, isType('queueCancelled'))
  await b.c.request({ type: 'queueCancel' }, isType('queueCancelled'))

  const lone = await player(relay, 'cf-lone')
  await queueUp(lone, { optionsKey: `w${++scenarioNo}`, gameId: 'ludo', fillAfterMs: 5000 })
  await ctl.advance(60000)
  await h.wait(50)
  check('[fill] a lone human is never matched, however long it waits', lone.c.of('matchFound').length === 0)

  const bo = { optionsKey: `w${++scenarioNo}` }
  const lo = await player(relay, 'bk-lo')
  const hi = await player(relay, 'bk-hi')
  await queueUp(lo, { ...bo, bucket: 0 })
  await queueUp(hi, { ...bo, bucket: 2 })
  await ctl.advance(14000)
  await h.wait(50)
  check('[bucket] bucket 0 and 2 are not matched inside 14 s', lo.c.of('matchFound').length === 0)
  await ctl.advance(17000)
  const found = await lo.c.waitFor(isType('matchFound'))
  check('[bucket] ...but are once the oldest has waited 30 s', !!found)
  await lo.c.request({ type: 'queueCancel' }, isType('queueCancelled'))
  await hi.c.request({ type: 'queueCancel' }, isType('queueCancelled'))
  await endScenario(ctl, a, b, lone, lo, hi)
}

async function wireValidationAndUpdates(relay, ctl) {
  const c = await h.client(relay)
  const unknown = await c.request(queueFrame('v-1', { gameId: 'no-such-game' }), (m) => m.type === 'error')
  check('[valid] an unknown game is refused: unknown_game', unknown.code === 'unknown_game', unknown)
  const badBucket = await c.request(queueFrame('v-1', { bucket: 7 }), (m) => m.type === 'error')
  check('[valid] a bad bucket is bad_request', badBucket.code === 'bad_request', badBucket)
  const noPlayer = await c.request({ type: 'queue', gameId: 'chess' }, (m) => m.type === 'error')
  check('[valid] a missing playerId is bad_request', noPlayer.code === 'bad_request', noPlayer)

  const room = await h.hostRoom(relay, 'v-host', { gameId: 'chess', maxSeats: 2 })
  const inRoom = await room.c.request(queueFrame('v-host', { optionsKey: 'inroom' }), (m) => m.type === 'error' || m.type === 'queued')
  check('[valid] a socket that is in a room cannot queue: already_in_room', inRoom.code === 'already_in_room', inRoom)

  const opts = { optionsKey: `w${++scenarioNo}` }
  const u = await player(relay, 'u-1')
  await queueUp(u, opts)
  const um = u.c.mark()
  await ctl.advance(5100)
  const up1 = await u.c.waitFor(isType('queueUpdate'), { since: um })
  check('[updates] a queueUpdate after 5 s with waitedMs and widened:false', !!up1 && up1.waitedMs >= 5000 && up1.widened === false, up1)
  const um2 = u.c.mark()
  await ctl.advance(11000)
  const up2 = await u.c.waitFor(isType('queueUpdate'), { since: um2 })
  check('[updates] widened:true once the window has widened (16 s)', !!up2 && up2.widened === true && up2.waitedMs >= 16000, up2)
  const um3 = u.c.mark()
  await ctl.advance(170000)
  const expired = await u.c.waitFor(isType('queueExpired'), { since: um3 })
  check('[expiry] after 3 minutes the ticket expires: queueExpired', !!expired)
  const s = await ctl.state()
  check('[expiry] ...and is gone from the queue', s.tickets === 0 && s.queued === 0, s)
  // leaving via host/join drops a ticket
  const lv = await player(relay, 'lv-1')
  await queueUp(lv, { optionsKey: `w${++scenarioNo}` })
  const hostedAfter = await lv.c.request({ type: 'host', playerId: 'lv-1' }, (m) => m.type === 'hosted' || m.type === 'error')
  check('[valid] a queued socket that hosts a friend room drops its ticket and hosts', !!hostedAfter && hostedAfter.type === 'hosted')
  const s3 = await waitState(ctl, (st) => st.queued === 0)
  check('[valid] ...no ticket remains', s3.queued === 0, s3)
  await endScenario(ctl, u, lv, room, { c })
}

async function wireCap() {
  const relay = await h.startRelay('qcap', { QUEUE_MAX_TICKETS: '2' })
  const ps = []
  for (let i = 0; i < 3; i++) ps.push(await player(relay, `cap-${i}`))
  const r0 = await queueUp(ps[0], { optionsKey: 'cap-a', gameId: 'ludo' })
  const r1 = await queueUp(ps[1], { optionsKey: 'cap-b', gameId: 'ludo' })
  const r2 = await queueUp(ps[2], { optionsKey: 'cap-c', gameId: 'ludo' })
  check('[caps] over the wire the ticket cap answers queue_full', r0.type === 'queued' && r1.type === 'queued' && r2.type === 'error' && r2.code === 'queue_full', [r0, r1, r2])
}

h.run(async () => {
  algorithm()
  const relay = await h.startRelay('queue')
  const ctl = await h.controller(relay)
  await wireMatchAndClaim(relay, ctl)
  await wireNoShow(relay, ctl)
  await wireCancelAndClose(relay, ctl)
  await wireCpuFillAndBuckets(relay, ctl)
  await wireValidationAndUpdates(relay, ctl)
  await wireCap()
})
