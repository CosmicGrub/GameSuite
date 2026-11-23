// Wave 5, slice S0: per-IP budgets that survive a reconnect (the regression for the per-socket-only limit), host and
// queue limits, TRUST_PROXY handling with and without, the per-socket flood guard (including a legitimate 60 Hz
// stream), and the loopback exemption. Distinct client IPs are simulated with X-Forwarded-For on a relay started with
// TRUST_PROXY=1 and RATE_LIMIT_LOOPBACK=1 (loopback is otherwise exempt, see config.js). Time is the relay's injectable
// clock, so the 60 s windows cost nothing.
const { createHarness, wait } = require('./test-lib')

const h = createHarness('ratelimit-test', { watchdogMs: 120000 })
const { check, isType } = h

const xff = (ip) => ({ 'X-Forwarded-For': ip })
const ipsSeen = new Set()
function freshIp() {
  let ip
  do { ip = `203.0.113.${1 + Math.floor(Math.random() * 250)}` } while (ipsSeen.has(ip))
  ipsSeen.add(ip)
  return ip
}

const errCode = (m) => m.type === 'error'

/** One failed join (unknown room) on `c`; resolves with the error frame. */
function failJoin(c, i = 0) {
  return c.request({ type: 'join', roomCode: 'QQQQ', playerId: `bf-${i}` }, errCode)
}

async function joinFailureBudget(relay, ctl) {
  const ip = freshIp()
  const other = freshIp()
  const host = await h.hostRoom(relay, 'bf-host', { headers: xff(other) }) // a real room, hosted from another IP
  const a = await h.client(relay, { headers: { 'X-Forwarded-For': `198.51.100.99, ${ip}` } }) // spoofed left entry must be ignored

  let last = null
  for (let i = 0; i < 9; i++) last = await failJoin(a, i)
  check('[budget] 9 refused joins from one IP are still ordinary no_such_room answers', !!last && last.code === 'no_such_room', last)
  a.send({ type: 'join', roomCode: 'QQQQ', playerId: 'bf-10' })
  check('[budget] the 10th refusal exhausts the per-IP budget and the socket is terminated', await a.waitClosed())

  const again = await h.client(relay, { headers: { 'X-Forwarded-For': `1.2.3.4, ${ip}` } })
  const r1 = await again.request({ type: 'join', roomCode: 'QQQQ', playerId: 'bf-again' }, errCode)
  check('[budget] REGRESSION: reconnecting does not reset it: the new socket is answered rate_limited at once', !!r1 && r1.code === 'rate_limited' && r1.retryAfterMs > 0 && r1.retryAfterMs <= 60000, r1)
  const r2 = await again.request({ type: 'join', roomCode: host.roomCode, playerId: 'bf-real' }, errCode)
  check('[budget] ...and a join to a LIVE room is answered identically (no room-existence oracle while blocked)', !!r2 && r2.code === 'rate_limited' && r2.message === r1.message, r2)
  const r3 = await again.request({ type: 'peek', roomCode: host.roomCode }, errCode)
  check('[budget] peek is blocked too', !!r3 && r3.code === 'rate_limited', r3)
  const r4 = await again.request({ type: 'claim', roomCode: 'QQQQ', playerId: 'x', claimToken: 'y' }, errCode)
  check('[budget] claim is blocked too', !!r4 && r4.code === 'rate_limited', r4)
  const r5 = await again.request({ type: 'queue', playerId: 'x', gameId: 'chess' }, errCode)
  check('[budget] queue is blocked too', !!r5 && r5.code === 'rate_limited', r5)
  const hosted = await again.request({ type: 'host', playerId: 'bf-blocked-host' }, (m) => m.type === 'hosted' || m.type === 'error')
  check('[budget] hosting is blocked too', !!hosted && hosted.code === 'rate_limited', hosted)

  const control = await h.client(relay, { headers: xff(other) })
  const okJoin = await control.request({ type: 'join', roomCode: host.roomCode, playerId: 'bf-honest' }, (m) => m.type === 'joined' || m.type === 'error')
  check('[budget] control: a different IP is unaffected and joins normally', !!okJoin && okJoin.type === 'joined', okJoin)
  const control2 = await h.client(relay, { headers: xff(freshIp()) })
  const miss = await control2.request({ type: 'join', roomCode: 'QQQQ', playerId: 'bf-c2' }, errCode)
  check('[budget] control: another fresh IP still gets ordinary no_such_room', !!miss && miss.code === 'no_such_room', miss)

  await ctl.advance(61 * 1000)
  const later = await again.request({ type: 'join', roomCode: 'QQQQ', playerId: 'bf-later' }, errCode)
  check('[budget] the block lasts 60 s: after 61 s the same IP is answered normally again', !!later && later.code === 'no_such_room', later)

  // a fresh budget is a full one: nine more refusals are fine, the tenth blocks again
  let ok9 = true
  for (let i = 0; i < 8; i++) { const r = await failJoin(again, 100 + i); if (!r || r.code !== 'no_such_room') ok9 = false }
  check('[budget] a new window is a full budget again', ok9)
}

async function peekCosts(relay) {
  const ipHit = freshIp()
  const ipMiss = freshIp()
  const host = await h.hostRoom(relay, 'pk-host', { headers: xff(freshIp()), gameId: 'chess', maxSeats: 2 })
  const hitter = await h.client(relay, { headers: xff(ipHit) })
  let allRooms = true
  for (let i = 0; i < 99; i++) {
    const r = await hitter.request({ type: 'peek', roomCode: host.roomCode }, (m) => m.type === 'room' || m.type === 'error')
    if (!r || r.type !== 'room') allRooms = false
  }
  check('[peek-cost] 99 successful peeks cost 0.1 each and never block', allRooms)
  hitter.send({ type: 'peek', roomCode: host.roomCode })
  check('[peek-cost] the 100th successful peek exhausts the budget (10 x 0.1 per failure): the socket is terminated', await hitter.waitClosed())
  const afterHits = await (await h.client(relay, { headers: xff(ipHit) })).request({ type: 'peek', roomCode: host.roomCode }, (m) => m.type === 'error' || m.type === 'room')
  check('[peek-cost] ...and the IP is then blocked', !!afterHits && afterHits.code === 'rate_limited', afterHits)
  const misser = await h.client(relay, { headers: xff(ipMiss) })
  for (let i = 0; i < 9; i++) await misser.request({ type: 'peek', roomCode: 'QQQQ' }, errCode)
  misser.send({ type: 'peek', roomCode: 'QQQQ' })
  check('[peek-cost] a peek that finds no room costs a full failure: ten misses block the IP', await misser.waitClosed())
}

async function hostLimits(relay, ctl) {
  const ip = freshIp()
  const c = await h.client(relay, { headers: xff(ip) })
  let hosted = 0
  for (let i = 0; i < 6; i++) {
    const r = await c.request({ type: 'host', playerId: `hl-${i}` }, (m) => m.type === 'hosted' || m.type === 'error')
    if (r && r.type === 'hosted') hosted++
  }
  check('[host-limit] six hosts a minute from one IP are allowed', hosted === 6, hosted)
  const seventh = await c.request({ type: 'host', playerId: 'hl-6' }, (m) => m.type === 'hosted' || m.type === 'error')
  check('[host-limit] the seventh inside a minute is rate_limited', !!seventh && seventh.code === 'rate_limited', seventh)
  const other = await h.client(relay, { headers: xff(freshIp()) })
  const fine = await other.request({ type: 'host', playerId: 'hl-other' }, (m) => m.type === 'hosted' || m.type === 'error')
  check('[host-limit] control: another IP can host', !!fine && fine.type === 'hosted', fine)
  await ctl.advance(61 * 1000)
  const after = await c.request({ type: 'host', playerId: 'hl-after' }, (m) => m.type === 'hosted' || m.type === 'error')
  check('[host-limit] after a minute the IP can host again', !!after && after.type === 'hosted', after)
}

async function roomsPerIp(relay) {
  const ip = freshIp()
  const rooms = []
  for (let i = 0; i < 30; i++) rooms.push(await h.hostRoom(relay, `rp-${i}`, { headers: xff(ip) }))
  check('[room-cap] 30 live rooms from one IP are allowed', rooms.length === 30)
  const c = await h.client(relay, { headers: xff(ip) })
  const over = await c.request({ type: 'host', playerId: 'rp-over' }, (m) => m.type === 'hosted' || m.type === 'error')
  check('[room-cap] the 31st is refused with too_many_rooms', !!over && over.code === 'too_many_rooms', over)
  const elsewhere = await h.client(relay, { headers: xff(freshIp()) })
  const ok = await elsewhere.request({ type: 'host', playerId: 'rp-else' }, (m) => m.type === 'hosted' || m.type === 'error')
  check('[room-cap] control: another IP is not affected', !!ok && ok.type === 'hosted', ok)
  rooms[0].c.send({ type: 'leave' })
  let retry = null
  for (let i = 0; i < 40 && !(retry && retry.type === 'hosted'); i++) { // the leave travels on another socket: poll until the relay has seen it
    await h.wait(25)
    retry = await c.request({ type: 'host', playerId: 'rp-retry' }, (m) => m.type === 'hosted' || m.type === 'error')
  }
  check('[room-cap] closing a room frees the slot', !!retry && retry.type === 'hosted', retry)
}

async function queueFrameLimit(relay) {
  const c = await h.client(relay, { headers: xff(freshIp()) })
  const codes = []
  for (let i = 0; i < 6; i++) codes.push((await c.request({ type: 'queue', playerId: 'ql', gameId: 'no-such-game' }, errCode)).code)
  check('[queue-limit] six queue frames a minute per IP are processed (here: unknown_game)', codes.every((x) => x === 'unknown_game'), codes)
  const seventh = await c.request({ type: 'queue', playerId: 'ql', gameId: 'no-such-game' }, errCode)
  check('[queue-limit] the seventh is rate_limited', !!seventh && seventh.code === 'rate_limited', seventh)
}

async function trustProxyOff(relay) {
  const a = await h.client(relay, { headers: xff('9.9.9.1') })
  for (let i = 0; i < 9; i++) await failJoin(a, i)
  a.send({ type: 'join', roomCode: 'QQQQ', playerId: 'tp-10' })
  check('[proxy-off] ten refusals block the (socket) IP', await a.waitClosed())
  const b = await h.client(relay, { headers: xff('8.8.8.8') })
  const r = await b.request({ type: 'join', roomCode: 'QQQQ', playerId: 'tp-b' }, errCode)
  check('[proxy-off] X-Forwarded-For is IGNORED without TRUST_PROXY: another header value does not buy a fresh budget', !!r && r.code === 'rate_limited', r)
  const c = await h.client(relay)
  const r2 = await c.request({ type: 'join', roomCode: 'QQQQ', playerId: 'tp-c' }, errCode)
  check('[proxy-off] ...nor does omitting the header', !!r2 && r2.code === 'rate_limited', r2)
}

async function trustProxyMisc(relay) {
  const ip = freshIp()
  const a = await h.client(relay, { headers: xff(ip) })
  for (let i = 0; i < 9; i++) await failJoin(a, i)
  a.send({ type: 'join', roomCode: 'QQQQ', playerId: 'tm-10' })
  await a.waitClosed()
  const junk = await h.client(relay, { headers: xff('not an ip, <script>') })
  const j = await junk.request({ type: 'join', roomCode: 'QQQQ', playerId: 'tm-junk' }, errCode)
  check('[proxy-on] a malformed X-Forwarded-For falls back to the socket address (a different bucket than the real client)', !!j && j.code === 'no_such_room', j)
  const longer = await h.client(relay, { headers: xff(`7.7.7.7, 6.6.6.6, ${ip}`) })
  const l = await longer.request({ type: 'join', roomCode: 'QQQQ', playerId: 'tm-long' }, errCode)
  check('[proxy-on] with one trusted proxy the LAST entry is the client: still blocked however many entries the client prepends', !!l && l.code === 'rate_limited', l)
  const mapped = await h.client(relay, { headers: xff(`::ffff:${ip}`) })
  const m = await mapped.request({ type: 'join', roomCode: 'QQQQ', playerId: 'tm-map' }, errCode)
  check('[proxy-on] an IPv4-mapped IPv6 spelling of the blocked address is the same client', !!m && m.code === 'rate_limited', m)
}

async function trustProxyTwoHops(relay) {
  const ip = freshIp()
  const a = await h.client(relay, { headers: xff(`${ip}, 10.0.0.1`) })
  for (let i = 0; i < 9; i++) await failJoin(a, i)
  a.send({ type: 'join', roomCode: 'QQQQ', playerId: 'th-10' })
  await a.waitClosed()
  const b = await h.client(relay, { headers: xff(`${ip}, 10.0.0.2`) })
  const r = await b.request({ type: 'join', roomCode: 'QQQQ', playerId: 'th-b' }, errCode)
  check('[proxy-2] TRUST_PROXY=2 takes the second entry from the right: a different last hop is the same client', !!r && r.code === 'rate_limited', r)
  const c = await h.client(relay, { headers: xff(`${freshIp()}, 10.0.0.1`) })
  const r2 = await c.request({ type: 'join', roomCode: 'QQQQ', playerId: 'th-c' }, errCode)
  check('[proxy-2] ...and a different client address behind the same proxy is not', !!r2 && r2.code === 'no_such_room', r2)
}

async function loopbackExempt(relay) {
  const c = await h.client(relay)
  let ordinary = true
  for (let i = 0; i < 15; i++) { const r = await failJoin(c, i); if (!r || r.code !== 'no_such_room') ordinary = false }
  check('[loopback] by default loopback is exempt from the per-IP budget (local dev, the other test suites)', ordinary)
  let hostedOk = 0
  for (let i = 0; i < 10; i++) { const r = await c.request({ type: 'host', playerId: `lb-${i}` }, (m) => m.type === 'hosted' || m.type === 'error'); if (r && r.type === 'hosted') hostedOk++ }
  check('[loopback] ...including the host limit', hostedOk === 10, hostedOk)
}

async function flood(relay, ctl) {
  const host = await h.hostRoom(relay, 'fd-host', { headers: xff(freshIp()) })
  const guest = await h.joinRoom(relay, host.roomCode, 'fd-guest', { headers: xff(freshIp()) })
  const barrierProbe = (tag) => (m) => m.type === 'message' && Buffer.from(m.payloadBase64, 'base64').toString() === tag
  const payload = Buffer.from('x').toString('base64')

  // 1. over-burst: frames beyond the burst are dropped and the sender is told (once)
  const gm = guest.c.mark()
  const hm = host.c.mark()
  for (let i = 0; i < 700; i++) host.c.send({ type: 'message', toPlayerId: 'fd-guest', payloadBase64: payload })
  const notice = await host.c.waitFor((m) => m.type === 'error' && m.code === 'rate_limited', { since: hm })
  check('[flood] a burst far over budget is answered with ONE rate_limited error', !!notice)
  await wait(250) // the only way to show frames did NOT arrive: let the relay drain the socket's backlog
  const delivered = guest.c.of('message', gm).length
  check('[flood] frames beyond the burst (480, plus a little refill while sending) are dropped, not relayed', delivered >= 400 && delivered <= 540, delivered)
  check('[flood] the error is not repeated for every dropped frame', host.c.frames.slice(hm).filter((m) => m.code === 'rate_limited').length === 1, host.c.frames.slice(hm).filter((m) => m.code === 'rate_limited').length)
  check('[flood] one strike is not termination', !host.c.closed)

  // 2. strikes: a second and third flood, each after the relay clock moved on, terminate the socket
  await ctl.advance(1500)
  for (let i = 0; i < 700; i++) host.c.send({ type: 'message', toPlayerId: 'fd-guest', payloadBase64: payload })
  await wait(250)
  check('[flood] a second strike (1.5 s later) still does not terminate', !host.c.closed)
  await ctl.advance(1500)
  for (let i = 0; i < 700; i++) host.c.send({ type: 'message', toPlayerId: 'fd-guest', payloadBase64: payload })
  check('[flood] the third strike inside 10 s terminates the socket', await host.c.waitClosed())

  // 3. the flooder's seat is held for reconnect like any dropped socket; the guest keeps working
  const gk = guest.c.mark()
  guest.c.send({ type: 'message', toPlayerId: null, payloadBase64: Buffer.from('still-fine').toString('base64') })
  await wait(50)
  check('[flood] the other member is unaffected (its own budget is its own)', guest.c.of('error', gk).length === 0 && !guest.c.closed)

  // 4. a legitimate real-time stream: 60 Hz from a host with three guests, per-recipient addressed frames = 180 frames/s
  const rtHost = await h.hostRoom(relay, 'rt-host', { headers: xff(freshIp()) })
  const guests = []
  for (let i = 0; i < 3; i++) guests.push(await h.joinRoom(relay, rtHost.roomCode, `rt-g${i}`, { headers: xff(freshIp()) }))
  const marks = guests.map((g) => g.c.mark())
  const rtMark = rtHost.c.mark()
  const ticks = 120 // 2 seconds
  await new Promise((resolve) => {
    let n = 0
    const timer = setInterval(() => {
      for (const g of guests) rtHost.c.send({ type: 'message', toPlayerId: g.id, payloadBase64: Buffer.from(`tick-${n}`).toString('base64') })
      if (++n >= ticks) { clearInterval(timer); resolve() }
    }, 1000 / 60)
  })
  const got = []
  for (let i = 0; i < guests.length; i++) {
    await guests[i].c.waitFor(barrierProbe(`tick-${ticks - 1}`), { since: marks[i] })
    got.push(guests[i].c.of('message', marks[i]).length)
  }
  check('[flood] a 60 Hz real-time stream (3 addressed frames per tick, 180 frames/s) passes untouched: every frame delivered to every guest', got.every((n) => n === ticks), got)
  check('[flood] ...and the sender saw no rate_limited error', !rtHost.c.frames.slice(rtMark).some((m) => m.code === 'rate_limited') && !rtHost.c.closed)

  // 5. the byte budget: a sustained flood of 60 KiB frames (2 MiB burst) is throttled even though the frame count is low
  const bigHost = await h.hostRoom(relay, 'bb-host', { headers: xff(freshIp()) })
  const bigGuest = await h.joinRoom(relay, bigHost.roomCode, 'bb-guest', { headers: xff(freshIp()) })
  const big = Buffer.alloc(45 * 1024, 65).toString('base64') // ~60 KiB of base64 text per frame
  const bm = bigHost.c.mark()
  for (let i = 0; i < 40; i++) bigHost.c.send({ type: 'message', toPlayerId: 'bb-guest', payloadBase64: big })
  const bigNotice = await bigHost.c.waitFor((m) => m.type === 'error' && m.code === 'rate_limited', { since: bm })
  check('[flood] the byte budget drops oversize floods (40 x 60 KiB over a 2 MiB burst)', !!bigNotice)
  void bigGuest
}

h.run(async () => {
  const proxy = await h.startRelay('proxy', { TRUST_PROXY: '1', RATE_LIMIT_LOOPBACK: '1', MSG_BURST: '480', MSG_RATE_PER_SEC: '240', MSG_BYTES_BURST: String(2 * 1024 * 1024), MSG_BYTES_PER_SEC: String(1024 * 1024) })
  const ctl = await h.controller(proxy)
  await joinFailureBudget(proxy, ctl)
  await peekCosts(proxy)
  await hostLimits(proxy, ctl)
  await queueFrameLimit(proxy)
  await trustProxyMisc(proxy)
  await flood(proxy, ctl)

  const manyRooms = await h.startRelay('rooms', { TRUST_PROXY: '1', RATE_LIMIT_LOOPBACK: '1', HOST_PER_IP_PER_MIN: '1000' })
  await roomsPerIp(manyRooms)

  const noProxy = await h.startRelay('noproxy', { RATE_LIMIT_LOOPBACK: '1' })
  await trustProxyOff(noProxy)

  const twoHops = await h.startRelay('twohops', { TRUST_PROXY: '2', RATE_LIMIT_LOOPBACK: '1' })
  await trustProxyTwoHops(twoHops)

  const exempt = await h.startRelay('exempt', {})
  await loopbackExempt(exempt)
})
