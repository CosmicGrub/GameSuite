// Shared harness for the wave-5 test files (room-meta-test, ratelimit-test, quickchat-test, queue-test).
// smoke-test.js and identity-test.js predate it and keep their own copies on purpose: they must stay untouched.
//
// Rules this harness enforces, same as identity-test.js:
//  - no sleep-and-hope: every step awaits the specific frame it depends on, with a timeout that turns a stall
//    into a failed check rather than a hang;
//  - every relay child process is killed on every exit path (normal, failure, watchdog, crash);
//  - time-driven behaviour (TTLs, windows, claim expiry) is driven through the relay's injectable clock
//    (ENABLE_TEST_CONTROL=1 and the "__test" frames), so nothing here waits 30 real seconds.
const WebSocket = require('ws')
const net = require('net')
const http = require('http')
const { spawn } = require('child_process')

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer()
    srv.once('error', reject)
    srv.listen(0, '127.0.0.1', () => {
      const port = srv.address().port
      srv.close(() => resolve(port))
    })
  })
}

function createHarness(title, { watchdogMs = 90000 } = {}) {
  let pass = 0
  let fail = 0
  const relays = []
  const clients = []

  function check(label, cond, detail) {
    if (cond) {
      pass++
      console.log('PASS:', label)
    } else {
      fail++
      console.log('FAIL:', label + (detail !== undefined ? `   [${typeof detail === 'string' ? detail : JSON.stringify(detail)}]` : ''))
    }
  }

  function killAll() {
    for (const c of clients) {
      try { c.ws.terminate() } catch { /* already closed */ }
    }
    for (const r of relays) {
      try { if (r.child && r.child.exitCode === null) r.child.kill() } catch { /* already gone */ }
    }
  }
  process.on('exit', killAll)

  /** Starts `node index.js` on a free port with the given env on top of a quiet baseline, and waits until it listens. */
  async function startRelay(name, env = {}) {
    const port = await freePort()
    const relay = { name, port, url: `ws://127.0.0.1:${port}`, httpUrl: `http://127.0.0.1:${port}`, output: '', child: null }
    relay.child = spawn(process.execPath, ['index.js'], {
      cwd: __dirname,
      env: {
        ...process.env,
        PORT: String(port),
        ENABLE_TEST_CONTROL: '1',
        RECONNECT_GRACE_MS_OVERRIDE: '20000',
        // Tests drive many frames back to back from one machine: lift the flood guard unless a test is about it.
        MSG_BURST: '1000000',
        MSG_RATE_PER_SEC: '1000000',
        MSG_BYTES_BURST: '1000000000',
        MSG_BYTES_PER_SEC: '1000000000',
        ...env,
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    relay.child.stdout.on('data', (d) => { relay.output += d.toString() })
    relay.child.stderr.on('data', (d) => { relay.output += d.toString() })
    relays.push(relay)
    const deadline = Date.now() + 8000
    while (!relay.output.includes('listening on')) {
      if (relay.child.exitCode !== null) throw new Error(`relay ${name} exited early:\n${relay.output}`)
      if (Date.now() > deadline) throw new Error(`relay ${name} never reported listening:\n${relay.output}`)
      await wait(25)
    }
    return relay
  }

  /** A socket that records every parsed frame and every raw text frame. Resolves once it is open. */
  async function client(relay, { headers } = {}) {
    const ws = new WebSocket(relay.url, { headers })
    const c = {
      ws, relay, frames: [], raw: [], closed: false,
      mark() { return c.frames.length },
      send(obj) { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj)) },
      sendRaw(text) { if (ws.readyState === WebSocket.OPEN) ws.send(text) },
      /** First frame at/after index `since` matching `pred`, or null after timeoutMs. */
      waitFor(pred, { since = 0, timeoutMs = 4000 } = {}) {
        const deadline = Date.now() + timeoutMs
        return new Promise((resolve) => {
          const poll = () => {
            for (let i = since; i < c.frames.length; i++) if (pred(c.frames[i])) return resolve(c.frames[i])
            if (Date.now() >= deadline) return resolve(null)
            setTimeout(poll, 5)
          }
          poll()
        })
      },
      /** Sends `obj` and resolves with the first reply after it that satisfies `pred` (default: any non-ack frame). */
      async request(obj, pred, opts) {
        const since = c.frames.length
        c.send(obj)
        return c.waitFor(pred || (() => true), { since, ...opts })
      },
      waitClosed(timeoutMs = 4000) {
        const deadline = Date.now() + timeoutMs
        return new Promise((resolve) => {
          const poll = () => {
            if (c.closed) return resolve(true)
            if (Date.now() >= deadline) return resolve(false)
            setTimeout(poll, 10)
          }
          poll()
        })
      },
      of(type, since = 0) { return c.frames.slice(since).filter((m) => m.type === type) },
    }
    ws.on('error', () => {}) // several checks deliberately provoke server-side disconnects
    ws.on('message', (data) => {
      const text = data.toString()
      c.raw.push(text)
      try { c.frames.push(JSON.parse(text)) } catch { /* the relay only sends JSON */ }
    })
    ws.on('close', () => { c.closed = true })
    clients.push(c)
    await new Promise((resolve, reject) => {
      if (ws.readyState === WebSocket.OPEN) return resolve()
      const timer = setTimeout(() => reject(new Error('socket did not open')), 5000)
      ws.once('open', () => { clearTimeout(timer); resolve() })
      ws.once('error', (e) => { clearTimeout(timer); reject(e) })
    })
    return c
  }

  const isType = (type) => (m) => m.type === type
  const isError = (code) => (m) => m.type === 'error' && (code === undefined || m.code === code)

  /** Hosts a room on a fresh socket and returns {c, id, roomCode, token, hosted}. */
  async function hostRoom(relay, id, extra = {}) {
    const c = await client(relay, { headers: extra.headers })
    const { headers, ...frame } = extra
    const hosted = await c.request({ type: 'host', playerId: id, displayName: id, ...frame }, (m) => m.type === 'hosted' || m.type === 'error')
    if (!hosted || hosted.type !== 'hosted') throw new Error(`host ${id} failed: ${JSON.stringify(hosted)}`)
    return { c, id, roomCode: hosted.roomCode, token: hosted.reconnectToken, hosted }
  }

  /** Joins `room` on a fresh socket; returns {c, id, joined} or {c, id, error} when refused. */
  async function joinRoom(relay, roomCode, id, extra = {}) {
    const c = await client(relay, { headers: extra.headers })
    const { headers, ...frame } = extra
    const reply = await c.request({ type: 'join', roomCode, playerId: id, displayName: id, ...frame }, (m) => m.type === 'joined' || m.type === 'error')
    if (reply && reply.type === 'joined') return { c, id, roomCode, joined: reply, token: reply.reconnectToken }
    return { c, id, roomCode, error: reply }
  }

  /** A control socket for the relay's injectable clock and state. */
  async function controller(relay) {
    const c = await client(relay)
    const ctl = {
      c,
      async advance(ms) { return c.request({ type: '__test', op: 'advanceClock', ms }, (m) => m.type === '__test' && m.op === 'advanceClock') },
      async state() { return c.request({ type: '__test', op: 'state' }, (m) => m.type === '__test' && m.op === 'state') },
    }
    return ctl
  }

  function httpGet(relay, path) {
    return new Promise((resolve, reject) => {
      http.get(relay.httpUrl + path, (res) => {
        let body = ''
        res.on('data', (d) => { body += d })
        res.on('end', () => resolve({ status: res.statusCode, body, headers: res.headers }))
      }).on('error', reject)
    })
  }

  async function run(main) {
    const watchdog = setTimeout(() => {
      console.error(`${title} exceeded ${watchdogMs}ms with ${pass} passed and ${fail} failed; aborting.`)
      for (const r of relays) console.error(`--- ${r.name} output ---\n${r.output.slice(-2000)}`)
      killAll()
      process.exit(2)
    }, watchdogMs)
    try {
      await main()
    } catch (err) {
      fail++
      console.log('FAIL: test run crashed:', err && err.stack ? err.stack : err)
      for (const r of relays) console.log(`--- ${r.name} output ---\n${r.output.slice(-2000)}`)
    } finally {
      clearTimeout(watchdog)
      for (const r of relays) {
        if (/TypeError|ReferenceError|Uncaught|unhandled/i.test(r.output)) {
          fail++
          console.log(`FAIL: relay ${r.name} logged a crash`)
          console.log(r.output.slice(-1500))
        }
        if (r.child.exitCode !== null) {
          fail++
          console.log(`FAIL: relay ${r.name} exited early with code ${r.child.exitCode}`)
        }
      }
      killAll()
    }
    console.log(`\n${title}: ${pass} passed, ${fail} failed`)
    process.exit(fail === 0 ? 0 : 1)
  }

  return { check, startRelay, client, hostRoom, joinRoom, controller, httpGet, run, isType, isError, wait, relays }
}

module.exports = { createHarness, wait, freePort }
