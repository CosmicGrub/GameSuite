// Wave 5, slice S5 (server half): quick-chat. Ids only, from quickchat-allowlist.json (12 phrases + 6 emotes of the
// design doc, section 4.2), validated, rate limited, relayed with the sender stamped from the socket, spectators
// denied, and kept entirely off the game 'message' path. Time (the limiter's windows) is the relay's injectable clock.
const { createHarness } = require('./test-lib')

const h = createHarness('quickchat-test')
const { check, isType } = h

const PHRASES = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
const EMOTES = [101, 102, 103, 104, 105, 106]
const chatErr = (m) => m.type === 'error' && typeof m.code === 'string' && m.code.startsWith('chat_')

async function main() {
  // The allowlist file is the contract with the Kotlin client and its EN/ES strings test.
  const list = require('./quickchat-allowlist.json')
  check('[allowlist] exactly phrases 1-12 and emotes 101-106 (design doc 4.2, ids used exactly)',
    JSON.stringify(list.phrases) === JSON.stringify(PHRASES) && JSON.stringify(list.emotes) === JSON.stringify(EMOTES), list)

  const relay = await h.startRelay('chat')
  const ctl = await h.controller(relay)
  const host = await h.hostRoom(relay, 'qc-host', { gameId: 'uno', maxSeats: 4, displayName: 'Hana' })
  const g1 = await h.joinRoom(relay, host.roomCode, 'qc-g1')
  const g2 = await h.joinRoom(relay, host.roomCode, 'qc-g2')
  const spec = await h.joinRoom(relay, host.roomCode, 'qc-spec', { spectator: true })
  const members = [g1, g2, spec]

  // 1. a valid id reaches everybody else (guests and spectators), stamped, and not the sender
  const marks = members.map((m) => m.c.mark())
  const hm = host.c.mark()
  host.c.send({ type: 'chat', phraseId: 4 })
  const seen = []
  for (let i = 0; i < members.length; i++) seen.push(await members[i].c.waitFor(isType('chat'), { since: marks[i] }))
  check('[chat] a valid phrase is relayed to both guests and the spectator as {type, fromPlayerId, phraseId}',
    seen.every((m) => m && m.fromPlayerId === 'qc-host' && m.phraseId === 4 && JSON.stringify(Object.keys(m).sort()) === JSON.stringify(['fromPlayerId', 'phraseId', 'type'])), seen)
  check('[chat] the sender gets no echo and no error', host.c.of('chat', hm).length === 0 && host.c.of('error', hm).length === 0)

  // 2. every id of the allowlist is accepted (the clock is advanced past the limiter between sends)
  const accepted = []
  for (const id of [...PHRASES, ...EMOTES]) {
    await ctl.advance(21 * 1000)
    const before = g1.c.mark()
    host.c.send({ type: 'chat', phraseId: id })
    const got = await g1.c.waitFor((m) => m.type === 'chat' && m.phraseId === id, { since: before })
    if (got) accepted.push(id)
  }
  check('[chat] all 18 allowlisted ids are relayed (12 phrases, 6 emotes)', accepted.length === 18, accepted)

  // 3. anything else is refused, relayed to nobody, and costs nothing (no rate-limit slot is spent on a rejected frame)
  await ctl.advance(21 * 1000)
  const invalid = [0, 13, 100, 107, -1, 4.5, '4', 'hi', null, true, false, [4], { id: 4 }, 1e9, NaN, Infinity, 2 ** 53]
  const gm = g1.c.mark()
  const gm2 = g2.c.mark()
  const gms = spec.c.mark()
  const hm2 = host.c.mark()
  const rejections = []
  for (const bad of invalid) {
    const before = host.c.mark()
    host.c.sendRaw(JSON.stringify({ type: 'chat', phraseId: bad })) // NaN/Infinity serialise to null: still invalid
    const r = await host.c.waitFor(chatErr, { since: before })
    rejections.push(r && r.code)
  }
  host.c.sendRaw(JSON.stringify({ type: 'chat' }))
  host.c.sendRaw(JSON.stringify({ type: 'chat', phraseId: '12', text: 'free text' }))
  host.c.sendRaw(JSON.stringify({ type: 'chat', text: 'hello everyone' }))
  host.c.sendRaw(JSON.stringify({ type: 'chat', phraseId: 3 })) // the valid barrier: also proves the rejected ones used no budget
  const barrier = await g1.c.waitFor(isType('chat'), { since: gm })
  check('[chat] invalid ids (0, 13, 100, 107, negative, float, strings, null, booleans, arrays, objects, huge, NaN) are each refused with chat_invalid',
    rejections.length === invalid.length && rejections.every((c) => c === 'chat_invalid'), rejections)
  check('[chat] a missing id, a numeric string and free text are refused too, and the next valid phrase goes straight through',
    host.c.of('error', hm2).filter((m) => m.code === 'chat_invalid').length === invalid.length + 3 && !!barrier && barrier.phraseId === 3, host.c.frames.slice(hm2).length)
  check('[chat] the first and only chat anybody received was the valid barrier: nothing invalid leaked', g1.c.of('chat', gm).length === 1 && g2.c.of('chat', gm2).length === 1 && spec.c.of('chat', gms).length === 1)
  check('[chat] no frame of any kind ever carried text from a client', ![...g1.c.raw, ...g2.c.raw, ...spec.c.raw].some((t) => t.includes('free text') || t.includes('hello everyone')))

  // 4. the sender is stamped from the socket, never from the frame
  await ctl.advance(21 * 1000)
  const sm = g2.c.mark()
  g1.c.send({ type: 'chat', phraseId: 2, fromPlayerId: 'qc-host', toPlayerId: 'qc-g2', payloadBase64: 'ZXZpbA==' })
  const stamped = await g2.c.waitFor(isType('chat'), { since: sm })
  check('[chat] a forged fromPlayerId is ignored: stamped as the real sender', !!stamped && stamped.fromPlayerId === 'qc-g1' && stamped.phraseId === 2, stamped)
  check('[chat] ...and extra fields (toPlayerId, payloadBase64) are not relayed', !!stamped && !('toPlayerId' in stamped) && !('payloadBase64' in stamped))
  await h.wait(100)
  check('[chat] the chat is a broadcast: the host and the spectator received it as well', host.c.frames.some((m) => m.type === 'chat' && m.fromPlayerId === 'qc-g1') && spec.c.frames.some((m) => m.type === 'chat' && m.fromPlayerId === 'qc-g1'))

  // 5. chat never travels the game message path (no message frame is produced, from chat or for it)
  const messageCount = (c) => c.frames.filter((m) => m.type === 'message').length
  check('[path] across everything above no recipient received a single game "message" frame', [host, g1, g2, spec].every((m) => messageCount(m.c) === 0))
  await ctl.advance(21 * 1000)
  const pm = g2.c.mark()
  host.c.send({ type: 'message', toPlayerId: null, payloadBase64: Buffer.from(JSON.stringify({ type: 'chat', phraseId: 1 })).toString('base64') })
  const asMessage = await g2.c.waitFor(isType('message'), { since: pm })
  check('[path] a game message whose payload looks like chat stays a message (never promoted to chat)', !!asMessage && g2.c.of('chat', pm).length === 0)

  // 6. spectators cannot send; not-in-room sockets cannot send
  const em = host.c.mark()
  const em1 = g1.c.mark()
  const denied = await spec.c.request({ type: 'chat', phraseId: 1 }, (m) => m.type === 'error' || m.type === 'chat')
  check('[chat] a spectator sending is refused with chat_denied', !!denied && denied.code === 'chat_denied', denied)
  await h.wait(100)
  check('[chat] ...and nobody received it', host.c.of('chat', em).length === 0 && g1.c.of('chat', em1).length === 0)
  const lone = await h.client(relay)
  const noRoom = await lone.request({ type: 'chat', phraseId: 1 }, (m) => m.type === 'error')
  check('[chat] a socket in no room gets not_in_room', !!noRoom && noRoom.code === 'not_in_room', noRoom)

  // 7. rate limits: 1 per 1.2 s, 5 per 20 s, the same id not twice within 5 s
  await ctl.advance(21 * 1000)
  const rl = g1.c.mark()
  g2.c.send({ type: 'chat', phraseId: 5 })
  await g1.c.waitFor((m) => m.type === 'chat' && m.phraseId === 5, { since: rl })
  const tooFast = await g2.c.request({ type: 'chat', phraseId: 6 }, chatErr)
  check('[rate] a second message inside 1.2 s is dropped with chat_rate', !!tooFast && tooFast.code === 'chat_rate', tooFast)
  await h.wait(50)
  check('[rate] ...and was not relayed', !g1.c.frames.slice(rl).some((m) => m.type === 'chat' && m.phraseId === 6))
  await ctl.advance(1300)
  const sameId = await g2.c.request({ type: 'chat', phraseId: 5 }, (m) => chatErr(m) || m.type === 'chat')
  check('[rate] the same id again within 5 s is dropped even after the 1.2 s gap', !!sameId && sameId.code === 'chat_rate', sameId)
  const results = []
  for (const id of [7, 8, 9, 10]) {
    await ctl.advance(1300)
    const before = g1.c.mark()
    const reply = await g2.c.request({ type: 'chat', phraseId: id }, chatErr, { timeoutMs: 400 })
    const relayed = await g1.c.waitFor((m) => m.type === 'chat' && m.phraseId === id, { since: before, timeoutMs: 400 })
    results.push(relayed ? 'sent' : reply ? reply.code : 'lost')
  }
  check('[rate] five messages fit in a 20 s window (the first plus four more 1.3 s apart)', JSON.stringify(results) === JSON.stringify(['sent', 'sent', 'sent', 'sent']), results)
  await ctl.advance(1300)
  const sixth = await g2.c.request({ type: 'chat', phraseId: 11 }, (m) => chatErr(m) || m.type === 'chat', { timeoutMs: 600 })
  check('[rate] the sixth message inside 20 s is dropped with chat_rate', !!sixth && sixth.code === 'chat_rate', sixth)
  await ctl.advance(21 * 1000)
  const afterMark = g1.c.mark()
  const recovered = await g2.c.request({ type: 'chat', phraseId: 11 }, chatErr, { timeoutMs: 300 })
  const relayedAfter = await g1.c.waitFor((m) => m.type === 'chat' && m.phraseId === 11, { since: afterMark })
  check('[rate] after the window passes the sender can chat again', !!relayedAfter && !recovered, recovered)
  const hostBudget = await host.c.request({ type: 'chat', phraseId: 12 }, (m) => chatErr(m) || m.type === 'chat', { timeoutMs: 300 })
  check('[rate] the limit is per sender: the host\'s own budget was untouched by the guest', !hostBudget || hostBudget.type !== 'error', hostBudget)

  // 8. a kicked player's socket can no longer chat
  host.c.send({ type: 'kick', playerId: 'qc-g2' })
  await g2.c.waitFor(isType('kicked'))
  const afterKick = await g2.c.request({ type: 'chat', phraseId: 1 }, (m) => m.type === 'error')
  check('[chat] a kicked socket gets not_in_room', !!afterKick && afterKick.code === 'not_in_room', afterKick)

  // 9. chat does not count as room activity: a room that only chats is still reaped by the idle rule
  const quiet = await h.hostRoom(relay, 'qc-quiet-host', { gameId: 'uno', maxSeats: 4 })
  const quietGuest = await h.joinRoom(relay, quiet.roomCode, 'qc-quiet-guest')
  await ctl.advance(20 * 60 * 1000)
  quiet.c.send({ type: 'chat', phraseId: 1 })
  await quietGuest.c.waitFor(isType('chat'))
  await ctl.advance(11 * 60 * 1000) // 31 minutes since the last join; the chat at minute 20 must not have counted
  const peeker = await h.client(relay)
  const gone = await peeker.request({ type: 'peek', roomCode: quiet.roomCode }, (m) => m.type === 'error' || m.type === 'room')
  check('[chat] chat is not room activity: spam cannot keep an otherwise idle room alive', !!gone && gone.type === 'error', gone)
}

h.run(main)
